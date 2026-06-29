# 卡片值懒算 + 首存后 eager warm（ensureCardValues，仿 lazy-excel）设计 — v3

> 2026-06-29 立项。解决「从列表打开报价单触发大量 `/api/cpq/components/batch-expand` + `/api/cpq/formulas/batch-evaluate` 风暴」的根因；约束：**首存不变慢、batch 计算接口不改、第一次打开与后续每次打开都快**。
>
> 决策轨迹（用户拍板）：① 计算时机＝两侧统一后端懒算（核价 BOM 树只能后端算）；② 触发＝**首存后前端 eager warm**（首存零影响）+ 打开兜底 + 后端单飞；③ 不起服务端后台线程（§11）。
> v3 并入**两轮独立评审**的全部修正（§10）：单飞取锁顺序 + try-lock、失败哨兵的前端占位设计、保存让路改为服务端置 NULL 失效、golden 夹具约束、warm 去高频、安全论证措辞订正。推迟项见 §12 + `BACKLOG.md`。

---

## 1. 背景与根因（已实证）

### 1.1 症状
从报价单列表打开报价单（如 `QT-20260629-1889`，77 行）触发**几百次** batch-expand + batch-evaluate，打开慢、占满后端 worker 线程。

### 1.2 根因链（代码级 + 真实库实证）
1. **前端渲染 gate 按卡片值快照判定，且「全有或全无」**（`QuotationStep2.tsx:3069-3070`）：
   ```ts
   const useSnapQuote   = lineItems.every(li => !!li.quoteCardValues);
   const useSnapCosting = costingLineItems.every(li => !!li.costingCardValues);
   ```
   为 `false` → 两侧回退实时路径 → `useDriverExpansions` 发 batch-expand × 每行每页签 + `usePathFormulaCache` 发 batch-evaluate × 每路径公式。**任一行缺值 → 整侧风暴**（§3.5 哨兵据此设计）。
2. **该单缺卡片值快照**：`quote_card_values`/`costing_card_values`/`costing_excel_values` 均 `0/77`，仅 `quote_excel_values` `77/77`。
3. **卡片值从未落库的确切机制（最小根因）**：`QuotationResource.java:175` 对 **jsonb** 列误用 `btrim(quote_card_values)`，PostgreSQL **无 `btrim(jsonb)` → 解析期必抛**（与数据无关）；异常被 `QuotationResource.java:227` `catch (Exception ignore)` 静默吞 → `newLines` 恒 0 → `snapshotNewLinesCardValues` 从未被调 → 卡片值永不落库。日志佐证：该单每次 `newLines=0`，全日志 `s3-detail ... batch` 出现 0 次。**最小修＝`btrim(...)`→`IS NULL` + 去掉吞错 catch；** 本方案是「最小修 + 为守首存性能而把计算移出 saveDraft」的叠加（PR 描述须说清这两层，避免后人以为非整套懒算不可）。
4. **系统性回归**：06-26 15:40 引入该 btrim（FIX2 `2440ab3`，默认 ON）起，凡走批量路径的单卡片值全部静默丢失。

### 1.3 硬约束（用户拍板）
首存不变慢；batch-expand/batch-evaluate 接口不改；第一次与后续每次打开都快；不起服务端后台线程算核价（§11）。

---

## 2. 架构与时序

```
首存(saveDraft)        ：只存行 + snapshot_rows；不算卡片值；并把被重建行的卡片值置 NULL(§3.4)  → 首存不变慢/略快
首存成功后(前端)        ：仅「导入完成 / 显式手动保存」触发 fire-and-forget POST ensure-card-values，自带防抖(§4.1)
  └ ensureCardValues   ：try-advisory-lock 单飞 → 取锁后查缺失行 → 算两侧卡片值 + 落库(§3.1/§3.3)
第一次打开(常态)        ：warm 已完成 → 读快照秒开
第一次打开(窄窗口)      ：warm 在飞 → 打开调同一 ensure，try-lock 命中"warming" → 前端简单 spinner + 延迟闸；落库后秒开，绝不双算
后续每次打开           ：卡片值已落库 → 读快照秒开（硬保证）
提交(submit)           ：不变，从 rowData+snapshot_rows 权威重建，不依赖卡片值落库(§9)
```

- **后续打开**：硬保证快。**第一次打开**：常态秒开；唯一非秒开＝「刚导入立刻回列表打开同一大单」窄窗口，单飞 + spinner，绝不双算/不更差。

---

## 3. 后端改动

### 3.1 新增 `CardSnapshotService.ensureCardValues(UUID quotationId): int`
照搬 `ensureExcelValues`（`CardSnapshotService.java:583`）骨架，单线程顺序循环（守 `cpq-expand-layer-not-threadsafe`）：
- **先取单飞锁（§3.3），再查缺失行**（评审 B-2：顺序是正确性关键）。缺失谓词须覆盖「仅核价缺」（评审 v1[高]）：
  ```sql
  SELECT id FROM quotation_line_item
  WHERE quotation_id = :q
    AND ( quote_card_values IS NULL
          OR (:hasCostingTpl AND costing_card_values IS NULL) )
  ```
  `:hasCostingTpl = (q.costingCardTemplateId != null)`。**用 `IS NULL`，不用 `btrim`。** 失败哨兵（§3.5）为非 NULL → 不被反复选中。
- 命中后 `precomputeCostingDriverUnion` + `precomputeCardValuesPrefetch` 一次预取 → 调现成 `snapshotNewLinesCardValues(quotationId, missingIds, union, prefetch)`（已 `@Transactional`、两侧卡片值 + 核价 BOM 树、逐位等价由 `CardValuesBatchPersistEquivTest`/`GoldenCardValuesEquivTest` 守）→ 落库。
- **幂等**：仅算缺失行；返回落库行数；第二次返回 0。

### 3.2 新增端点 `POST /quotations/{id}/ensure-card-values`
仿 `ensureExcelValues` 端点（`QuotationResource.java:306`）。调 `ensureCardValues(id)` → `em.clear()` + `getById(id)` 返回带卡片值的 `QuotationDTO`。eager warm 与打开兜底复用同一端点。**取不到单飞锁时**返回轻量「warming-in-progress」状态（不阻塞 worker，见 §3.3）。

### 3.3 单飞（try-advisory-lock，评审 B 收口）
入口对 quotationId 取 **`pg_try_advisory_xact_lock`**（非阻塞版）：
- key：`('x'||substr(md5(:q::text),1,16))::bit(64)::bigint`（UUID→bigint，碰撞可忽略；不用 `hashtext` 的 int4 窄域，评审 B-1）。
- **取到锁** → 查缺失行 → 计算 → commit 释放（事务级锁，`@Transactional` 覆盖整个计算，评审 B-2）。
- **取不到锁**（已有 warm 在飞）→ 立即返回「warming-in-progress」，**不 park worker/DB 连接**（评审 B-3，防池饿死）；前端据此显示 spinner，由打开守卫稍后重试或读已落库快照。
- 多端/窄窗口打开同单 → 后到者要么命中 warming、要么取锁后见已落库 → 返 0，**绝不双算**。

### 3.4 saveDraft 资源层：删卡片值块 + 失效被重建行（评审 D-1）
- **删除** `QuotationResource.java` 约 `144-231` 整段卡片值计算块（含 `cardValuesBatch` 开关、btrim 查询、`snapshotNewLinesCardValues` 调用、逐行回退、**`catch (Exception ignore)` 一并删**，别把吞错带进新端点）。首存不再算卡片值 → 路径不变/略快（省 ≈438ms + `em.clear()+getById` 往返）。
- **失效写法（取代 v2「脏标记+最新为准」伪命题）**：`processBatchStage1` / 逐行路径**重建某行子表（snapshot_rows）时，把该行 `quote_card_values`、`costing_card_values` 一并置 NULL**（与既有列 UPDATE 同事务，零额外成本）。这样 re-warm 的 `IS NULL` 谓词能重新选中该行 → 用最新 snapshot_rows 重算，真正「最新为准」。失效判据落**服务端行级状态（值=NULL）**，不靠前端内存标记（跨端/刷新会丢）。
- `snapshotQuotation(id,true)`（S2，写 snapshot_rows，:127）保留；`ensureStructure(id)`（结构非值）若有独立用途保留，实现时核验解耦。

### 3.5 失败哨兵 + 前端占位（评审 C-2，必须闭环）
`safeCall` 失败返 `null`；若直接写入则该行 `null` → 全有或全无 gate 把整侧打回风暴，`IS NULL` 又反复选中 → 每次打开都重算+风暴、永不自愈。
- **后端**：build 返 `null`（确定性失败）的行某侧，写**非 NULL 失败哨兵 JSON** `{"tabs":[],"__cardValueFailed":true}` + `LOG.warn`（不静默）。哨兵非 NULL → gate 视为「已算」不再整侧风暴、`IS NULL` 不再反复选中。
- **前端（必做，不能只声明）**：空 `tabs` 会让该行不产生 DriverExpansionMap 条目 → `activeDriverExpansion=undefined` → **正中 AP-38「加载中鬼魂行」/AP-50 僵尸数据**。故 `buildSnapshotExpansions`/ProductCard 渲染层须**识别 `parsed.__cardValueFailed`**，对该 line 全部组件渲染**显式「数据待重算」占位**（非静默空白，不重蹈 06-27），并提供重算入口（本期复用既有 `POST /components/{id}/refresh-template-snapshots`；卡内「重算此行」按钮推迟，§12 / BL-0012）。
- **E2E 断言**：哨兵行 `加载中`=0、显示占位、不发 batch。

### 3.6 受影响后端文件（按协议级改动跑 E2E）
`CardSnapshotService.java`（新增 `ensureCardValues` + 哨兵）、`QuotationResource.java`（新增端点 + try-lock 单飞 + 删卡片值块）、`QuotationService.java`（§3.4 重建行置 NULL）。

---

## 4. 前端改动 & 数据流

### 4.1 首存后 eager warm（首存零影响、用户无感、去高频，评审 E-中）
**仅在「导入完成」与「显式手动保存草稿/下一步/上一步/提交前」触发**，并自带客户端防抖；**不挂在高频防抖保存回调上**（否则每次空存都抢锁+查询）。fire-and-forget `POST ensure-card-values`，不阻塞、不挡操作，可显示不打断的「正在准备快速浏览…」轻提示；失败/导航中断静默忽略（warn），由 §4.2 兜底。
> 它是**普通前台 HTTP 请求**（单线程顺序计算），与今天多用户同时打开报价单的并发模式**完全同构**，不引入任何「单个计算内跨线程共享」——故安全（§11 真正不变量）。

### 4.2 打开兜底守卫
打开/进入 Step2（`QuotationWizard.loadQuotation`）加一次性 ref 守卫：
1. `getById` 后若**任一行缺卡片值（哨兵视为已算）** → 置 loading（大单显示 spinner/行数）→ `POST ensure-card-values`（命中在飞 warm 则 warming-in-progress，稍后重试/读快照）。
2. 用返回 DTO 回灌 → `useSnapQuote/useSnapCosting=true` → `buildSnapshotExpansions` 读快照渲染。
3. **等 ensure 完成再渲染卡片区** → batch-expand/batch-evaluate 一次不发。
4. ensure 整体失败 → 回退现有实时渲染（同今天，不更差）+ `console.warn`。

### 4.3 覆盖入口
① 打开存量已存单；② 导入首存后进 Step2（warm 常已就绪，否则走 4.2）。

### 4.4 受影响前端文件
`QuotationWizard.tsx`（warm 触发去高频 + 打开守卫 + loading）、`QuotationStep2.tsx`（gate 不变；§3.5 `__cardValueFailed` 占位 + 重算入口）。均属强制 E2E 文件。

---

## 5. 存量自愈（无需迁移脚本）
`ensureCardValues` 幂等 → 06-27 起受损单**下次打开/下次显式保存触发的 warm**自动补算落库，之后秒开。

---

## 6. 一致性论证（评审订正后）

**结论：不引入用户可见的新不一致。** 两侧机理不同：
- **报价侧（吃冻结输入，强一致）**：`buildCardValues`（`CardSnapshotService.java:933/955`）只读 `snapshot_rows`、不二次 expand；snapshot_rows 由 S2 冻结。懒算无论何时跑吃的都是首存冻结输入；渲染层 live 与快照两模式都用同一前端 `computeAllFormulas` 从 baseRows 重算公式（`QuotationStep2.tsx:1368`）→ 一致。
- **核价侧（实时重算，但首存未显示故无可比）**：`buildCostingCardValues`（:1016/:1037/:1044）走实时 `bomClosureService.compute` + `expandTemplateDriverBaseRows`，**不复用冻结 snapshot_rows**（v2「核价也吃冻结」措辞错误，已订正）。但首存时前端核价侧不显示 BOM 树（只根料号层，记忆 `costing-bom-tree-full-spine-render`），树是 ensure 第一次才出现 → 无旧显示可对比 → 无用户可见不一致。语义代价：同单不同时刻首开若 BOM/价格变动，核价值可能不同（既有架构特性；submit 权威重算为最终口径）。

**残留尾差（既有架构、已接受）**：快照中直接取用值由后端 BigDecimal 算、前端 live 为 JS number，极端下末位差 1；项目已定「后端权威不变、不碰精度契约」，submit 权威重算覆盖。

---

## 7. 错误处理 & 并发

| 场景 | 行为 |
|---|---|
| 单行某侧 build 确定性失败 | 写失败哨兵（§3.5）+ `LOG.warn` + 前端「数据待重算」占位 + 重算入口；不拖整侧进风暴 |
| ensure 整体异常 | 端点返错；前端回退实时渲染（同今天）+ `console.warn`，不静默 |
| eager warm 失败/超时/导航中断 | 静默忽略（warn）；打开守卫兜底 |
| warm 在飞 + 打开同单 / 多端打开 | §3.3 try-lock：后到者命中 warming 或取锁后见已落库返 0，**不双算**；不 park worker |
| warm/ensure 与 saveDraft 重建子表交错 | MVCC READ COMMITTED 下读到一致快照（不抛错/不读半成品，评审 D）；saveDraft 已把被重建行卡片值置 NULL（§3.4）→ 下次 warm 必重算最新值，无陈旧残留 |
| 线程安全 | 单请求顺序计算，不在一个计算内跨线程共享 ThreadLocal(`PartVersionContext` 等)+union/prefetch 可变 Map；**不起服务端后台线程**（§11） |

---

## 8. 测试

1. **后端逐位等价**：`GoldenCardValuesEquivTest` md5 —— ensure 落库 == 逐行路径逐位等价。**约束（评审 E-高）：golden 夹具必须 failure-free（无确定性失败行）；含哨兵的行排除出 md5 等价比对**，否则 §8.1 与 §3.5 在 CI 打架。
2. **幂等**：第二次调返 0、不改值、md5 不变。
3. **缺失谓词覆盖「仅核价缺」**：构造「报价已算、核价 NULL」行 → ensure 必选中补核价。
4. **失败哨兵**：模拟某行 build 抛错 → 落非 NULL 哨兵 + warn；不致整侧 gate 翻 false；不被反复选中；**前端哨兵行 E2E：`加载中`=0、显占位、不发 batch**。
5. **单飞**：并发两次 ensure 同单 → 仅一次实算，另一次 warming/返 0；验证「先取锁后查缺失行」。
6. **失效重算（§3.4）**：保存改动某行 → 该行卡片值被置 NULL → 下次 warm 重算出新值（防陈旧残留）。
7. **一致性闸**：同测试单快照模式渲染 == live 模式渲染（E2E 视觉 + 关键 Tab 取值）。
8. **E2E `quotation-flow.spec.ts`**：打开 → ensure → 8 Tab `加载中=0`；ensure 后 batch-expand/batch-evaluate 请求数=0；warm 路径验证首存后自动落库。
9. **首存计时回归**：saveDraft 不含卡片值计算，耗时 ≤ 现状。

---

## 9. 验收标准

- **后续每次打开**：batch-expand/batch-evaluate 请求数 **=0**、读快照秒开（硬保证）。
- **第一次打开（常态）**：warm 已就绪 → 请求数=0、秒开。
- **第一次打开（窄窗口）**：单飞合并 + spinner，不双算；落库后请求数=0。
- **首存**：耗时 ≤ 现状；batch 接口实现零改动。
- **第一次打开延迟基准**：记录大单（罗克韦尔 170 行）首开 ensure 阻塞时长基准，纳入回归（自动上限闸推迟，§12 评估）。
- 新建单导入→首存→（warm）→打开：卡片值正确落库，渲染与首存显示一致（§6/§8.7）。
- `GoldenCardValuesEquivTest` + 幂等/谓词/哨兵/单飞/失效重算 + E2E 全绿；`submit` 仍正常（不依赖卡片值落库：`QuotationService.submit:827` 读 `cd.snapshotRows/rowData`，Excel 由 submit 先 `ensureExcelValues` 兜底）。

---

## 10. 评审采纳清单（两轮独立评审）

**v1 评审**：[采纳][高] §3.1 谓词覆盖「仅核价缺」；[采纳][高] §3.5 失败哨兵；[采纳][订正] §6 核价侧实时重算措辞；[确认] 首存变快、submit 独立。

**v2 评审**：
- [采纳][必改 D-1] §3.4 保存让路改为「重建行置 NULL」失效（取代 IS NULL 模型下不成立的「脏标记+最新为准」）；脏判据落服务端。
- [采纳][必改 C-2] §3.5 失败哨兵补**前端占位设计**（识别 `__cardValueFailed` → 显式「数据待重算」+ E2E 断言），杜绝单行 AP-38/静默降级。
- [采纳][应改 B] §3.3 单飞「先取锁后查缺失行」+ 改 `pg_try_advisory_xact_lock`（不 park worker）+ UUID→bigint 收敛碰撞。
- [采纳][应改 E-高] §8.1 golden 夹具 failure-free + 哨兵行排除 md5。
- [采纳][应改 E-中] §4.1 warm 去高频（仅导入完成/显式保存 + 防抖）。
- [采纳][订正 A] §4.1/§11 安全论证措辞改为真正不变量「单线程顺序、不在一个计算内跨线程共享 ThreadLocal+union/prefetch」（评审核证：`DataLoader.resultCache`@RequestScoped 不共享、`expandCache` Caffeine 只读消费、9/73 真因是计算内并行丢 ThreadLocal 版本上下文）。
- [推迟] 见 §12 + `BACKLOG.md`（BL-0010~0013）。

---

## 11. 已否决 / 安全不变量
- **不起服务端后台线程做核价计算**：真因＝「一个计算内跨线程并行会丢 `PartVersionContext` 等 ThreadLocal（注释明示 CompletableFuture 不传播）→ 注不进版本谓词 → 历史数据叠加 → 9/73 静默错价（已回滚）」+ saveDraft 子表重建 FK/竞态。**eager warm 是前端发普通前台顺序请求，不在一个计算内并行 → 不触此雷区。**
- 不重写前端权威 4 快照引擎；不改 batch-expand/batch-evaluate 接口；不碰 lazy-excel（Excel 仍由 `ensureExcelValues` 补）；不写存量批量重算迁移（幂等自愈）；不做「导入尾同步算」硬保证（用户选 eager warm）。

---

## 12. 本期推迟项（已登记 BACKLOG.md）
- **BL-0010 [P1]** 降低首开/warm 阻塞时长：核价卡片值 expand 集合化（动核心基线，需 architect，与 savedraft-setbased 集合化协同）。本期先用 eager warm 把成本移出用户感知路径。
- **BL-0011 [P2]** 窄窗口 warming-in-progress 前端轮询进度条（替代简单 spinner）。
- **BL-0012 [P2]** 哨兵行「重算此行」卡内交互入口（本期复用 admin `refresh-template-snapshots` 替代）。
- **BL-0013 [P2]** saveDraft 响应回 `hasMissingCardValues` 廉价提示，彻底去抖 warm 触发（本期靠「仅显式保存 + 防抖」已够）。
