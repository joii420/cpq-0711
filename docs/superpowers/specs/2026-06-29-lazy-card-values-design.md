# 卡片值懒算 + 首存后 eager warm（ensureCardValues，仿 lazy-excel）设计

> 2026-06-29 立项。解决「从列表打开报价单触发大量 `/api/cpq/components/batch-expand` + `/api/cpq/formulas/batch-evaluate` 请求风暴」的根因；约束：**首存不变慢、batch 计算接口不改、第一次打开与后续每次打开都快**。
>
> 决策轨迹（用户拍板）：① 计算时机＝两侧统一后端懒算（非前端回存，因核价 BOM 树只能后端算）；② 触发策略＝**首存后前端 eager warm**（首存零影响）+ 打开兜底 + 后端单飞；③ 不起服务端后台线程（踩 `cpq-expand-layer-not-threadsafe`，9/73 静默错价已回滚，见 §11）。本版已并入一次独立评审的全部修正（§10）。

---

## 1. 背景与根因（已实证）

### 1.1 症状
从报价单列表打开报价单（如 `QT-20260629-1889`，77 行）触发**几百次** batch-expand + batch-evaluate，打开慢、占满后端 worker 线程。

### 1.2 根因链（代码级 + 真实库实证）
1. **前端渲染 gate 按卡片值快照判定**（`QuotationStep2.tsx:3069-3070`）：
   ```ts
   const useSnapQuote   = lineItems.length>0 && lineItems.every(li => !!li.quoteCardValues);
   const useSnapCosting = costingLineItems.length>0 && costingLineItems.every(li => !!li.costingCardValues);
   ```
   两者为 `false` → 报价/核价两侧回退**实时计算路径** → `useDriverExpansions` 发 batch-expand × 每行每驱动页签 + `usePathFormulaCache` 发 batch-evaluate × 每路径公式。**注意此 gate 是「全有或全无」：任一行缺卡片值 → 整侧风暴**（§3.5 失败哨兵据此设计）。
2. **该单缺卡片值快照**：实测 `quote_card_values`、`costing_card_values`、`costing_excel_values` 均 `0/77`，仅 `quote_excel_values` `77/77`。
3. **卡片值从未落库的确切机制**：`QuotationResource.java:173-176` 用于「查无快照新行」的原生 SQL 对 **jsonb** 列调用 `btrim(quote_card_values)`，而 PostgreSQL **不存在 `btrim(jsonb)` → 解析期必抛 `function btrim(jsonb) does not exist`**（与数据无关，每次必抛）。异常被外层 `catch (Exception ignore)`（`QuotationResource.java:227`）静默吞掉 → `newLines` 恒 0 → `snapshotNewLinesCardValues` 从未被调用 → 卡片值永不落库。日志佐证：该单每次保存 `[draft-profile] ... newLines=0`，全日志内 `s3-detail ... batch` 出现 **0** 次。
4. **系统性回归**：06-26 15:40 引入该 btrim 形式（FIX2 `2440ab3`，开关默认 ON）起，凡走批量路径的单卡片值全部静默丢失。
5. `quote_excel_values` 由独立 `ensureExcelValues`（lazy-excel）写入，与卡片值路径无关，故唯独它有值。

### 1.3 硬约束（用户拍板）
- **不可让首存（saveDraft）变慢**。
- **不可让计算接口（batch-expand / batch-evaluate）变慢/改动**。
- **第一次打开与后续每次打开都要快速浏览**。
- 不起服务端后台线程做核价计算（已被否决，§11）。

---

## 2. 架构与时序

把卡片值计算从 **saveDraft（首存）** 整段摘除；改由幂等端点 **`ensureCardValues`** 计算落库，触发分三层保证「打开就快」：

```
首存(saveDraft)        ：只存行 + snapshot_rows，不算卡片值                 → 首存不变慢/略快
首存成功后(前端)        ：fire-and-forget POST ensure-card-values 做 warm     → 用户无感、不阻塞
  └ 后端 ensureCardValues：单飞(advisory lock/quotationId) 计算两侧卡片值+落库
第一次打开              ：常态(隔一阵再开)→ warm 已完成 → 读快照秒开
  └ 窄窗口(刚导入立刻开,warm 在飞) → 打开调同一 ensure，单飞合并到在飞计算，显示进度(不双算)
后续每次打开            ：卡片值已落库 → 读快照秒开（硬保证）
提交(submit)           ：不变，后端权威重算覆盖（不依赖卡片值已落库，§9）
```

- **后续打开**：硬保证快（快照已落库、幂等）。
- **第一次打开**：常态秒开；唯一非秒开 = 「刚导入完立刻回列表打开同一大单」的 ~10s 窄窗口，单飞使其等同一次计算并显示进度，绝不双算、绝不更差。

---

## 3. 后端改动

### 3.1 新增 `CardSnapshotService.ensureCardValues(UUID quotationId): int`
照搬 `ensureExcelValues`（`CardSnapshotService.java:583`）骨架：
- 查该单**缺卡片值**的行 id。判空谓词必须**同时覆盖「仅核价缺」**（评审 [高]）：
  ```sql
  SELECT id FROM quotation_line_item
  WHERE quotation_id = :q
    AND ( quote_card_values IS NULL
          OR (:hasCostingTpl AND costing_card_values IS NULL) )
  ```
  其中 `:hasCostingTpl = (q.costingCardTemplateId != null)`。**用 `IS NULL`，不用 `btrim`（jsonb 无 btrim；这正是原 bug）。** 失败哨兵（§3.5）写入的是**非 NULL** 值，故不会被反复选中。
- 命中后：`precomputeCostingDriverUnion(quotationId)` + `precomputeCardValuesPrefetch(quotationId, allLineIds)` 一次预取 → 调**现成的** `snapshotNewLinesCardValues(quotationId, missingIds, union, prefetch)`（已 `@Transactional`、两侧卡片值 + 核价 BOM 树、Pass1 build / Pass2 赋值单事务；逐位等价由 `CardValuesBatchPersistEquivTest` + `GoldenCardValuesEquivTest` 守）→ 落库。
- **幂等**：仅对缺失行计算，已有值（含哨兵）的行跳过 → 反复调零开销、第二次返回 0。
- 返回实际落库行数。单线程顺序，守 `cpq-expand-layer-not-threadsafe`（不并行 expand/公式/快照层）。

### 3.2 新增端点 `POST /quotations/{id}/ensure-card-values`
仿 `ensureExcelValues` 端点（`QuotationResource.java:306`）。调 `cardSnapshotService.ensureCardValues(id)`，随后 `em.clear()` + `getById(id)` 返回刷新后的 `QuotationDTO`（已带卡片值），供前端回灌进入快照模式。eager warm 与打开兜底**复用同一端点**。

### 3.3 单飞（single-flight）守卫
`ensureCardValues` 入口对 `quotationId` 取 **PostgreSQL 事务级 advisory lock**（`pg_advisory_xact_lock(hashtext(quotationId))`）：
- warm 与「窄窗口打开」「并发多端打开」落到同一锁 → 串行；第二个进来时第一个已落库 → 其 `IS NULL` 查询返空 → 直接返回 0，**不双算**（解决评审 [中] 并发双算）。
- 与 saveDraft 协调：saveDraft 重建子表前后不持该锁；warm 在飞时用户又保存 → 见 §7「保存让路」。

### 3.4 saveDraft 资源层：删除卡片值计算块
**删除** `QuotationResource.java` 约 `144-231` 整段卡片值计算块（含 `cardValuesBatch` 开关、btrim 原生查询、`snapshotNewLinesCardValues` 调用、逐行回退分支、`catch (Exception ignore)`）。
- 首存从此**不算卡片值** → 路径不变、略快（省 `ensureStructure`+`loadQuotationLines`+查询 ≈438ms，及 `em.clear()+getById` 往返）。
- `snapshotQuotation(id,true)`（S2，写 `snapshot_rows`，:127）**保留**。
- `ensureStructure(id)`：结构（非值）；若有独立用途则保留，实现时核验结构创建与值计算解耦。
- **耦合提示（评审确认）**：删块后 saveDraft 响应 DTO 不再带卡片值，导入后进 Step2 不再自动处于快照模式 → 由 §4 前端守卫（warm + 兜底）补位。成本挪到「打开前的 warm」，不加到首存。

### 3.5 失败哨兵（评审 [高]：防确定性失败 → 整单永久风暴）
`safeCall` 失败返回 `null`，若直接写入 `quoteCardValues` 则该行 `null` → 「全有或全无」gate 把**整侧**打回风暴，且 `IS NULL` 谓词每次又选中它 → **每次打开都重算 + 风暴，永不自愈**。
对策：
- `ensureCardValues` 落库时，对某行某侧 build 返回 `null`（确定性失败）的，写入**非 NULL 的失败哨兵 JSON** `{"tabs":[],"__cardValueFailed":true}` 而非 `null`。
- 效果：gate 视该行「已算」→ 不再整侧风暴；`buildSnapshotExpansions` 遇空 `tabs` 平稳跳过 → 该行渲染为**显式「数据待重算」占位**（不是静默空白，避免重蹈 06-27 静默降级覆辙）。
- **不静默**：写哨兵必记 `LOG.warn`；前端遇 `__cardValueFailed` 显示可点「重算此行」入口（复用既有 `POST /components/{id}/refresh-template-snapshots` 或单行重算）。
- 哨兵为非 NULL → 幂等查询不再反复选中（不无限重算）；人工/管理端重算可清哨兵重建。

### 3.6 受影响后端文件（按协议级改动跑 E2E）
`CardSnapshotService.java`（新增 `ensureCardValues` + 哨兵）、`QuotationResource.java`（新增端点 + 单飞 + 删卡片值块）。

---

## 4. 前端改动 & 数据流

### 4.1 首存成功后 eager warm（首存零影响、用户无感）
import-first-save / 手动存草稿成功回调里，**fire-and-forget** 触发 `POST /quotations/{id}/ensure-card-values`：
- 不阻塞、不挡操作；可显示一个不打断的「正在准备快速浏览…」轻提示（可选）。
- 它是**普通前台 HTTP 请求**（非服务端后台线程），并发特性与「打开时触发 ensure」完全一致 → 不触碰 §11 的线程安全雷区。
- 失败/超时静默忽略（warn）：兜底由 §4.2 打开守卫补。

### 4.2 打开兜底守卫（保证第一次打开也快）
打开/进入 Step2（`QuotationWizard.loadQuotation` → `applyQuotationData`）加**一次性 ref 守卫**：
1. `getById` 回来后，若**任一行缺 `quoteCardValues`/`costingCardValues`（哨兵视为已算）** → 置 loading（「正在准备…」+ 大单显示进度/行数）→ `POST ensure-card-values`（命中在飞 warm 则单飞合并）。
2. 用返回 DTO 回灌 `lineItems` → `useSnapQuote/useSnapCosting=true` → `buildSnapshotExpansions` 读快照渲染。
3. **等 ensure 完成再渲染 Step2 卡片区**：这一程 batch-expand/batch-evaluate 一次不发。
4. ensure 整体失败 → 回退现有实时渲染路径（同今天行为，不更差），`console.warn`。

### 4.3 覆盖入口
① 打开存量已存单；② 导入首存后进 Step2（warm 常已就绪，否则走 4.2 兜底）。

### 4.4 受影响前端文件
`QuotationWizard.tsx`（warm 触发、打开守卫、loading/进度）、`QuotationStep2.tsx`（gate 不变，确保 ensure 完成前不发 batch；`__cardValueFailed` 占位 + 重算入口）。均属强制 E2E 触发文件。

---

## 5. 存量自愈（无需迁移脚本）
`ensureCardValues` 幂等 → 06-27 起所有缺快照的受损单，**下次被打开（或下次保存触发的 warm）时自动补算落库**，之后秒开。无需单独批量重算迁移。

---

## 6. 一致性论证（首存显示 vs 懒算结果）

**结论：不引入用户可见的新不一致。** 但报价侧与核价侧机理不同，须据实说明（评审订正）：

- **报价侧（吃冻结输入，强一致）**：`buildCardValues`（`CardSnapshotService.java:933`）只读 `quotation_line_component_data.snapshot_rows`（955-961），**不二次 expand**；snapshot_rows 由 saveDraft S2 冻结。故懒算无论何时跑，吃的都是首存冻结输入 → 与首存时前端 live 显示同输入；渲染层 live 与快照两模式都用同一前端 `computeAllFormulas` 从 baseRows 重算公式（`QuotationStep2.tsx:1368`）→ 一致。
- **核价侧（实时重算，但首存未显示故无可比）**：`buildCostingCardValues`（:1016）走 `bomClosureService.compute`（:1037，实时查 BOM 闭包）+ `expandTemplateDriverBaseRows`（:1044，实时 expand）→ **不复用冻结 snapshot_rows**，吃的是 ensure 运行那刻的 DB。**因此「懒算吃首存冻结同一份输入」对核价侧不成立**（这是上一版 §6 的事实错误，已订正）。但首存时前端核价侧根本不显示 BOM 树（只根料号层，见记忆 `costing-bom-tree-full-spine-render`），树是 ensure 第一次才出现 → **无旧显示可对比，故无用户可见不一致**。语义代价：同一未提交单不同时刻第一次打开，若期间 BOM/价格变动，核价值可能不同（既有架构特性，非本方案引入；submit 权威重算为最终口径）。

**残留尾差（既有架构、已接受）**：快照中直接取用（非前端重算）的值由后端 BigDecimal 算、前端 live 为 JS number，极端下末位可能差 1。项目已定「后端权威不变、不碰精度契约」；submit 再权威重算覆盖。

---

## 7. 错误处理 & 并发

| 场景 | 行为 |
|---|---|
| 单行某侧 build 确定性失败 | 写**失败哨兵**（§3.5）+ `LOG.warn`；该行渲染「数据待重算」占位，不拖整侧进风暴；提供重算入口 |
| ensure 整体异常 | 端点返错；前端回退实时渲染（同今天），`console.warn`，不静默 |
| eager warm 失败/超时 | 静默忽略（warn）；打开守卫兜底 |
| 并发：warm 在飞 + 打开同单 / 多端打开 | §3.3 advisory lock 串行单飞；后到者见已落库直接返 0，**不双算**。前端 ref 守卫仅防同端重复，跨端靠后端单飞 |
| warm 在飞 + 用户又保存（重建子表） | saveDraft 不持 ensure 锁；warm 读 snapshot_rows 期间被重建有竞态 → **保存让路**：saveDraft 成功后置「快照脏」标记并重新触发一次 warm，旧 warm 结果以最新一次为准（幂等覆盖）。实现时核验：以 saveDraft 完成时刻的 snapshot_rows 为准 |
| 线程安全 | 单请求顺序循环，不并行（守 `cpq-expand-layer-not-threadsafe`）；**不起服务端后台线程**（§11） |

---

## 8. 测试

1. **后端逐位等价**：复用 `GoldenCardValuesEquivTest` md5 —— `ensureCardValues` 落出卡片值与逐行路径逐位等价。
2. **幂等**（新增）：第二次调返回 0、不改值、md5 不变。
3. **缺失谓词覆盖「仅核价缺」**（新增）：构造「报价已算、核价 NULL」行 → ensure 必须选中并补核价（防核价侧永久风暴）。
4. **失败哨兵**（新增）：模拟某行 build 抛错 → 落非 NULL 哨兵 + warn；该行不致整侧 gate 翻 false；不被幂等查询反复选中。
5. **单飞**（新增）：并发两次 ensure 同单 → 仅一次实算，另一次返 0。
6. **一致性闸**（新增，落实 §6）：同测试单**快照模式渲染 == live 模式渲染**（E2E 视觉 + 关键 Tab 取值比对）。
7. **E2E `quotation-flow.spec.ts`**：打开 → ensure → 8 Tab `加载中=0`；ensure 后 **batch-expand/batch-evaluate 请求数=0**（Network 断言）；warm 路径单测/E2E 验证首存后自动落库。
8. **首存计时回归**：saveDraft 不含卡片值计算，首存耗时 ≤ 现状。

---

## 9. 验收标准

- **后续每次打开**：`/batch-expand` 与 `/batch-evaluate` 请求数 **=0**，读快照秒开（硬保证）。
- **第一次打开（常态：隔一阵再开）**：warm 已就绪 → 请求数 =0、秒开。
- **第一次打开（窄窗口：刚导入立刻开大单）**：单飞合并、显示进度、不双算；落库后请求数 =0。
- **首存**：耗时不高于现状；`batch-expand`/`batch-evaluate` 接口实现零改动。
- **第一次打开延迟基准**（新增闸）：记录大单（罗克韦尔 170 行）首开 ensure 阻塞时长基准，设上限闸纳入回归。
- 新建单导入→首存→（warm）→打开：卡片值正确落库，渲染与首存显示一致（§6/§8.6）。
- `GoldenCardValuesEquivTest` + 幂等/谓词/哨兵/单飞 + E2E 全绿；`submit` 不依赖卡片值落库（§下）仍正常。

> **submit 独立性（评审确认）**：`QuotationService.submit`（:781）行键校验读 `cd.snapshotRows/rowData`，提交快照 `SnapshotCollectorService` 读 `cd.rowData`，**均不读卡片值**；Excel 由 resource submit 先 `ensureExcelValues` 兜底。即便用户首存后不打开直接提交，submit 仍从 rowData+snapshot_rows 权威重建。

---

## 10. 评审采纳清单（2026-06-29 独立评审）
- [采纳][高] §3.1 缺失谓词加 `OR (hasCostingTpl AND costing_card_values IS NULL)`，覆盖「仅核价缺」。
- [采纳][高] §3.5 失败哨兵，防确定性失败 → 整单永久风暴/不自愈；且非静默。
- [采纳][中] §3.3 单飞 advisory lock，解决并发双算。
- [采纳][中] §7 据实写明并发/保存让路，不再声称「只发一次」。
- [采纳][低] §9 第一次打开延迟基准 + 上限闸。
- [采纳][订正] §6 核价侧措辞：核价实时重算、非复用冻结；结论靠「核价首存无显示」豁免成立。
- [确认] 首存变快无隐藏拖慢；submit 不依赖卡片值落库。

---

## 11. 范围外 / 已否决（YAGNI）
- **不起服务端后台线程做核价计算**：踩 `cpq-expand-layer-not-threadsafe`（expand/公式/快照层返回共享可变缓存对象引用，并发改 → 9/73 静默错价已实测回滚）+ saveDraft 子表重建 FK/竞态。`eager warm` 是**前端发普通前台请求**，非此项。
- 不重写前端权威 4 快照引擎（已否决）。
- 不改 batch-expand / batch-evaluate 接口本身。
- 不碰 lazy-excel（Excel 值仍由 `ensureExcelValues` 在开 Excel 视图/导出/提交前补）。
- 不写存量批量重算迁移（靠幂等自愈）。
- 不做「打开零延迟硬保证 via 导入尾同步算」（用户选 eager warm，接受窄窗口单飞进度）。
