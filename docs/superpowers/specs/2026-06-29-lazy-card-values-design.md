# 卡片值懒算（ensureCardValues，仿 lazy-excel）设计

> 2026-06-29 立项。解决「打开报价单触发大量 `/api/cpq/components/batch-expand` + `/api/cpq/formulas/batch-evaluate` 请求风暴」的根因，且不让首存变慢、不改 batch 计算接口。

---

## 1. 背景与根因（已实证）

### 1.1 症状
从报价单列表打开报价单（如 `QT-20260629-1889`，77 行）触发**几百次** `batch-expand` + `batch-evaluate`，打开慢、占满后端 worker 线程（与历史「打开空白/autosave 风暴」同一战场）。

### 1.2 根因链（代码级 + 真实库实证）
1. **前端渲染 gate 按卡片值快照判定**（`QuotationStep2.tsx:3069-3070`）：
   ```ts
   const useSnapQuote   = lineItems.length>0 && lineItems.every(li => !!li.quoteCardValues);
   const useSnapCosting = costingLineItems.length>0 && costingLineItems.every(li => !!li.costingCardValues);
   ```
   两者为 `false` 时，报价/核价两侧都回退**实时计算路径** → `useDriverExpansions` 发 batch-expand × 每行每驱动页签 + `usePathFormulaCache` 发 batch-evaluate × 每路径公式。
2. **该单缺卡片值快照**：实测 `quote_card_values`、`costing_card_values`、`costing_excel_values` 均 `0/77`，仅 `quote_excel_values` `77/77`。
3. **卡片值从未落库的确切机制**：`QuotationResource.java:173-176` 用于「查无快照新行」的原生 SQL 对 **jsonb** 列调用 `btrim(quote_card_values)`，而 PostgreSQL **不存在 `btrim(jsonb)` 函数 → 查询在解析期必然抛 `function btrim(jsonb) does not exist`**（与数据无关，每次必抛）。异常被外层 `catch (Exception ignore)`（`QuotationResource.java:227`）静默吞掉 → `newLines` 恒为 0 → `snapshotNewLinesCardValues` 从未被调用 → 卡片值永不落库。后端日志佐证：该单每次保存 `[draft-profile] ... newLines=0`，且全日志内 `s3-detail ... batch` 出现 **0** 次（批量卡片值路径从未真正执行过）。
4. **系统性回归**：06-26 15:40 引入该 btrim 形式（FIX2 `2440ab3`，默认开关 ON）起，凡走批量路径的单卡片值全部静默丢失；06-26 之前/开关 OFF 走的是逐行老路（Java 侧判空、无 SQL btrim），故 06-26 单 `qc=77/cc=77`。
5. `quote_excel_values` 由独立的 `ensureExcelValues`（lazy-excel）路径写入，与卡片值路径无关，故唯独它有值。

### 1.3 修复约束（用户拍板，硬约束）
- **不可让首存（saveDraft）变慢**。
- **不可让计算接口（batch-expand / batch-evaluate）变慢**。
- 故卡片值计算既不能放回 saveDraft，也不能塞进 batch 接口。

### 1.4 方案选择（用户拍板）
**两侧统一后端懒算（仿现有 lazy-excel）**。理由：核价侧 BOM 闭包树**只有后端 `buildCostingCardValues`（注入闭包 partSet）才算得出**（前端 live 兜底只显示根料号层，见 `QuotationStep2.tsx:3071-3079` + 记忆 `costing-bom-tree-full-spine-render`），纯前端回存对核价侧不成立；统一后端懒算概念最少、一套机制、与已上线 lazy-excel 同源。

---

## 2. 架构（一句话）

把卡片值计算从 **saveDraft（首存）** 整段摘除，改由新端点 **`ensureCardValues`** 在**打开报价单时**懒算一次并落库；之后所有打开读快照秒开。完全复刻已上线的 lazy-excel 形态。

```
首存(saveDraft)         ：只存行 + snapshot_rows，不算卡片值      → 首存不变慢
打开 Step2(第一次)       ：缺卡片值 → POST ensure-card-values 算+落库一次(显示 loading) → 读快照渲染
打开 Step2(之后每次)     ：卡片值已在 → 直接读快照秒开，batch-expand/evaluate 一次不发
提交(submit)            ：不变，后端权威重算覆盖
```

---

## 3. 后端改动

### 3.1 新增 `CardSnapshotService.ensureCardValues(UUID quotationId): int`
照搬 `ensureExcelValues`（`CardSnapshotService.java:583`）骨架：
- 查该单**缺卡片值**的行 id —— 判空用**修正后的** `quote_card_values IS NULL`（jsonb 没有 btrim；新行恒 NULL，无需 btrim/blank 判断）。彻底绕掉抛错的查询。
  - 同时覆盖「报价模板有但核价模板后加 / 仅核价缺」的边界：判定缺失 = `quote_card_values IS NULL`（核价侧由 `snapshotNewLinesCardValues` 内部按 `q.costingCardTemplateId != null` 决定是否算 `costing_card_values`，与现有逐行/批量路径一致）。
- 命中后：`precomputeCostingDriverUnion(quotationId)` + `precomputeCardValuesPrefetch(quotationId, allLineIds)` 一次性预取 → 调**现成的** `snapshotNewLinesCardValues(quotationId, missingIds, union, prefetch)`（已落 `@Transactional`、两侧卡片值 + BOM 树、Pass1 build / Pass2 赋值单事务 flush；逐位等价由 `CardValuesBatchPersistEquivTest` + `GoldenCardValuesEquivTest` 守）→ 落库。
- **幂等**：仅对缺失行计算，已有卡片值的行跳过 → 反复调零开销、第二次返回 0。
- 返回实际补算（落库）的行数。
- 单线程顺序执行，守 `cpq-expand-layer-not-threadsafe`（不并行 expand/公式/快照层）。
- 失败处理：单行 `safeCall` 吞到行级并记 `warn`（不静默 `ignore`），其余行照落；下次打开按幂等补缺。

### 3.2 新增端点 `POST /quotations/{id}/ensure-card-values`
仿 `ensureExcelValues` 端点（`QuotationResource.java:306`）。调 `cardSnapshotService.ensureCardValues(id)`，随后 `em.clear()` + `getById(id)` 返回刷新后的 `QuotationDTO`（已带卡片值），供前端一次回灌进入快照模式。

### 3.3 saveDraft 资源层：删除卡片值计算块
**删除** `QuotationResource.java` 约 `144-231` 整段卡片值计算块（含 `cardValuesBatch` 开关、那条 btrim 原生查询、`snapshotNewLinesCardValues` 调用、逐行回退分支、以及吞异常的 `catch (Exception ignore)`）。
- 首存从此**不算卡片值** → 首存路径不变、甚至略快（省掉那段 `ensureStructure`+`loadQuotationLines`+查询 ≈ 438ms）。
- `snapshotQuotation(id, true)`（S2，写 `snapshot_rows`）**保留不动** —— 它是卡片值的冻结输入来源（见 §6 一致性）。
- `ensureStructure(id)`（卡片**结构**而非值）：若其有独立用途（固定 4 份结构）则保留；实现时核验，结构创建与值计算解耦。
- btrim bug 随该块删除而消失，无需单独 SQL 修补。

### 3.4 受影响后端文件（PR 自检按 AP-44 / 协议级改动跑 E2E）
- `CardSnapshotService.java`（新增 `ensureCardValues`）—— 属强制 E2E 触发文件之一。
- `QuotationResource.java`（新增端点 + 删卡片值块）。

---

## 4. 前端改动 & 数据流

### 4.1 打开/进入 Step2 的统一懒算守卫
在打开/加载流程（`QuotationWizard.loadQuotation` → `applyQuotationData`，或 Step2 进入处）加**一次性 ref 守卫**（防重复触发风暴）：
1. `getById` 回来后，若**任一行缺 `quoteCardValues` 或 `costingCardValues`** → 置 loading（一次性「计算中」）→ `POST /quotations/{id}/ensure-card-values`。
2. 用返回的 `QuotationDTO` 回灌 `lineItems`（携卡片值）→ `useSnapQuote/useSnapCosting=true` → `buildSnapshotExpansions` 读快照渲染。
3. **等 ensure 完成再渲染 Step2 卡片区**：这一程把 batch-expand / batch-evaluate 直接 gate 掉、一次都不发（比现状实时风暴更快，且只此一次）。
4. ensure 整体失败时：回退到现有实时渲染路径（即今天的行为，不比现状差），记 `console.warn`。

### 4.2 覆盖两条入口
- ① 打开存量已存报价单。
- ② 导入首存后进入 Step2（同一守卫；首存响应不再含卡片值，由守卫统一补算）。

### 4.3 受影响前端文件
- `QuotationWizard.tsx`（loadQuotation 守卫 + ensure 调用 + loading）。
- `QuotationStep2.tsx`（进入渲染前确保快照在；gate 逻辑不变，仅确保 ensure 完成前不发 batch 请求）。
- 两者均属强制 E2E 触发文件。

---

## 5. 存量自愈（无需迁移脚本）

`ensureCardValues` 幂等 → 06-27 起所有缺快照的受损单，**下次被打开时自动补算落库**，第二次打开即秒开。不需要单独的批量重算迁移。

---

## 6. 一致性论证（首存显示 vs 懒算结果）

**结论：懒算不引入新的不一致。**

1. **首存时页面显示的来源**：saveDraft 从不算卡片值，首存那刻页面是**前端实时引擎**（batch-expand 取驱动 + `computeAllFormulas` 算公式）渲染的 live 结果。
2. **驱动数据在首存即冻结**：saveDraft 的 S2 `snapshotQuotation` 已把驱动数据落进 `snapshot_rows`；`buildCardValues` **复用 `snapshot_rows`、不二次 expand**（`CardSnapshotService.java:517` 注释「卡片值复用 snapshot_rows，不二次 expand」）。
3. 故 `ensureCardValues` 无论哪天打开才跑，**吃的都是首存那刻冻结的同一份输入** → 与首存显示用的输入一致；**懒算 vs eager（当初在 saveDraft 立即算）输入相同、产出相同**，一致性上不比 eager 差。
4. **渲染层在 live 与快照两模式都用同一前端 `computeAllFormulas` 从 baseRows 重算公式**（`QuotationStep2.tsx:1368`）→ 公式显示同引擎同输入 → 一致。
5. **核价 BOM 树无「不一致」问题**：首存时前端 live 根本不显示树（只根料号层），树是 ensure 第一次才出现，走 submit 同款后端权威路径，无可对比的旧显示。

**残留尾差（既有架构、已接受，非本方案引入）**：快照中**直接取用**（而非前端重算）的值由后端 BigDecimal 算，前端 live 为 JS number，极端下末位可能差 1。此「双引擎精度契约」是现有快照架构本有的，项目已明确「后端权威不变、不碰精度契约」；且后端快照为权威口径、submit 再权威重算覆盖。

---

## 7. 错误处理

| 场景 | 行为 |
|---|---|
| 单行卡片值 build 失败 | `safeCall` 吞到行级 + `warn`，其余行照落；下次打开幂等补缺 |
| ensureCardValues 整体异常 | 端点返回错误；前端回退实时渲染路径（同今天行为），`console.warn`，不静默 |
| 并发/重复触发 | 前端一次性 ref 守卫只发一次；后端只写 2 个 jsonb 列、无全删全建 → 不复发 autosave/FK/乐观锁风暴 |
| 线程安全 | 单请求顺序循环，不并行（守 `cpq-expand-layer-not-threadsafe`） |

---

## 8. 测试

1. **后端逐位等价**：复用 `GoldenCardValuesEquivTest` md5 基线 —— `ensureCardValues` 落出的卡片值与逐行路径逐位等价（本就同款 `buildCardValues`/`buildCostingCardValues`）。
2. **幂等测试**（新增）：`ensureCardValues` 第二次调返回 0、不改值、md5 不变。
3. **一致性闸（新增，落实 §6）**：同一测试单，**快照模式渲染结果 == live 模式渲染结果**（E2E 视觉 + 关键 Tab 取值比对），把「理论一致」落成「实测一致」，任何真实背离在上线前暴露。
4. **E2E `quotation-flow.spec.ts`**：打开 → ensure → 8 Tab `加载中=0`；ensure 完成后 **batch-expand / batch-evaluate 不再发**（Network 断言）。
5. **首存计时回归**：确认 saveDraft 不含卡片值计算（`newLines` 概念移除），首存耗时 ≤ 现状。
6. **存量自愈验证**：打开一张 06-27 后受损单 → 第一次打开补算 + 落库；第二次打开秒开、4 份快照齐全（核价 Excel 仍由 lazy-excel 在开核价 Excel 视图时补）。

---

## 9. 验收标准

- 打开 `QT-20260629-1889`（或任一受损单）：第一次打开 ensure 一次（loading），第二次打开 `/batch-expand` 与 `/batch-evaluate` **请求数为 0**。
- 首存耗时不高于现状。
- `batch-expand` / `batch-evaluate` 接口实现零改动。
- 新建单导入→首存→打开：卡片值正确落库，渲染与首存显示一致（§6/§8.3 验证通过）。
- `GoldenCardValuesEquivTest` + 幂等测试 + E2E 全绿。

---

## 10. 范围外（YAGNI）

- 不重写前端权威 4 快照引擎（已否决）。
- 不做后台异步线程预算核价（已否决，踩 `cpq-expand-layer-not-threadsafe` + FK）。
- 不改 batch-expand / batch-evaluate 接口本身。
- 不碰 lazy-excel（Excel 值仍由 `ensureExcelValues` 在开 Excel 视图/导出/提交前补算）。
- 不单独写存量批量重算迁移（靠 ensureCardValues 幂等自愈）。
