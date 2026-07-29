# repair-0729 · 后端实现计划（backtask）

> 依据：本目录 `需求文档.md`（现象/复现/根因 A~E）。
> 分支：`fix/repair-0729-copy-data-loss`（worktree `.claude/worktrees/repair-0729-copy-fix`，基于 master `424f09aa`）。
> **本文件是给实现子代理的唯一施工依据**；决策已由技术总监裁定，不得自行更改方案。

---

## 〇、方案裁决（含被否决方案的实证理由）

### 0.1 否决「迁移 pending 基础数据」（需求文档决策 1 的 1-A / 1-B）

实测证据（`cpq_db_0724`）：

- 带 `pending_quotation_id` 的表共 **9 张**：`capacity` / `element_bom` / `element_bom_item` / `material_bom` / `material_bom_item` / `material_customer_map` / `material_master` / `plating_scheme` / `unit_price`；
- **9 张表的唯一索引全都不含 `pending_quotation_id`**（如 `uq_material_bom_item(system_type, customer_no, material_no, COALESCE(characteristic,''), COALESCE(bom_version,''), COALESCE(seq_no,0), COALESCE(component_no,''), COALESCE(part_no,''))`）。

⇒ **1-A（物理复制 pending 行）必撞 23505**，除非同时改 9 个唯一索引——那会连带影响 `is_current` 语义与 `VersionedV6Writer` 的撞键自愈，风险远超本次修复。
⇒ **1-B（可见性并集）**：新单继承源单 pending 后，一旦新单自己编辑基础数据，写路径要么 UPDATE 到源单的行（污染源单）、要么 INSERT 撞唯一键。**pending 模型本身不支持"同一份基础数据的两个并行草稿"**，这是架构级约束，不在本次修复范围内改。

### 0.2 采纳「值快照整份继承」

**同模板复制 = 复制冻结结果，而不是"换个 id 重新取一遍数"。**

一次性解决：
- 根因 B（BOM 树空）—— 直接继承源行 `snapshot_rows`（树 spine 在里面）；
- 根因 E（金额静默错）—— 直接继承金额与 4 份值快照；
- 根因 C（saveDraft 空覆盖）—— **自动消解**：`snapshot_rows` 非空 ⇒ 前端渲染正确 ⇒ 回传的 `row_data` 就是正确的；且 `ConfigureSnapshotService.snapshotQuotation(id, skipRowsWithSnapshot=true)` 对"所有 driver 组件都已有 snapshot_rows"的行整行跳过重 expand；
- 根因 D —— 补 `ensureStructure`。

**换模板复制**（`templateId != source.customerTemplateId`）保持现状不动（结构不同，必须重建）。

### 0.3 护栏（通用不变量，独立价值）

「**任何情况下都不得用一次重查得到的空结果覆盖已持久化的非空数据**」——与 AP-60（页签投影当整组权威致抹数据）同族。本次落两处（§二 Task2），并留可观测计数器。

**明确不做**：不在 `saveDraft` 的 `row_data` 入参上加护栏。用户真实删行走的正是这条路径，误拦会导致"删不掉行"。该路径由 0.2 的继承 + Task2 的两处护栏兜底。

---

## 一、Task1 —— `copy` 值快照整份继承 + 补 `ensureStructure`

**文件**：`cpq-backend/src/main/java/com/cpq/quotation/service/QuotationService.java`

### 1.1 判定 sameTemplate 并提升作用域

现状：`boolean sameTemplate = ...` 在行循环内计算（`:1479` 附近）。改为在循环外计算一次（单据头也要用），语义不变：

```java
boolean sameTemplate = newTemplateId != null && newTemplateId.equals(source.customerTemplateId);
```

### 1.2 单据头金额（`:1428-1431`）

- `sameTemplate == true`：`copy.originalAmount = source.originalAmount; copy.totalAmount = source.totalAmount;`
- `sameTemplate == false`：保持现状（`BigDecimal.ZERO` 占位）。
- 其余单据头字段一律不动。

### 1.3 行级快照继承（`:1450-1478` 建 `newLi` 处）

`sameTemplate == true` 时，从 `srcLi` 逐字段继承（`false` 时保持现状留空）：

| 字段 | 说明 |
|---|---|
| `subtotal` | 现为 `ZERO`，改为继承 |
| `quoteCardValues` / `quoteExcelValues` | 报价侧两份值快照 |
| `costingCardValues` / `costingExcelValues` | 核价侧两份值快照 |
| `cardSnapshotAt` / `quoteValuesAt` | 冻结时间戳（继承后 `refreshQuoteCardValues(force=false)` 会自然短路，见 1.5） |
| `excelViewSnapshot` | Excel 视图快照 |
| `deletedTreeNodes` | **树节点剪枝墓碑**——不继承会让源单上已剪掉的枝在新单复活 |

### 1.4 组件数据继承（`migrateAndCreateComponentData`，`:1505-1540`）

`sameTemplate == true` 时（`false` 分支逐字节保持现状）：

- `newCd.snapshotRows = match.snapshotRows`（**核心**，BOM 树 spine 在此）；
- `newCd.rowData = match.rowData`（同模板结构一致，直接整份继承，**不走 `mapInputRowData` 的 INPUT 过滤**——那会丢掉 `__nodeId` 等系统列与非 INPUT 值）；
- `newCd.subtotal = match.subtotal`；
- `deletedRowKeys` 保持现状（已按 componentId 原样拷贝，与继承的快照同源，正确）。

⚠️ `match == null`（源单缺该页签）时仍走现有兜底（`rowData="[]"`, `snapshotRows=null`），不要因继承分支漏掉空指针保护。

### 1.5 跳过快照重建（`:1489-1494`）

`sameTemplate == true` 时**显式跳过** `cardSnapshotService.refreshQuoteCardValues(newLi)` 与 `refreshCostingCardValues(copy.id)`，并加注释说明理由：

> 继承路径下重建会在**新单的 pending 可见域**下重查 SQL，把刚继承的正确值刷成空（本 bug 的根因 A）。

（即便不显式跳过，`force=false` + `cardSnapshotAt != null` 也会短路 no-op；但**必须显式跳过**，不依赖隐式行为——后续有人改短路条件就会静默回归。）

`sameTemplate == false` 时保持现状调用。

### 1.6 补建结构快照

在 `copy()` 返回 DTO 之前，best-effort 调用（**两种路径都要**）：

```java
try { cardSnapshotService.ensureStructure(copy.id); } catch (Exception ignore) { /* 结构快照尽力而为 */ }
```

口径与 `QuotationResource.java:128` 的 `saveDraft` 补建完全一致（幂等、失败不阻断）。

### 1.7 Task1 禁止事项

- 不得改动 `mapInputRowData` / `parseTemplateTabFields` 本体（换模板路径仍在用）；
- 不得改动 `QuotePendingScope` / 任何 pending 相关代码；
- 不得动核价侧任何链路（核价侧已实证零影响，见需求文档 §3.6）。

---

## 二、Task2 —— 空覆盖护栏

### 2.1 `CardSnapshotService.refreshQuoteCardValues`（`:2114`）

在 `overlayTreeTabsFromFrozenSnapshot(...)`（`:2141`）之后、`assembleTabsWithFormulaResults(...)`（`:2158`）之前插入：

- 取旧值 `managed.quoteCardValues` 的 per-component baseRows（复用现成的 `extractBaseRowsByComp`，`:2032`）；
- 对每个组件：**新 baseRows 为 0 行 且 旧 baseRows > 0 行** → 用旧 baseRows 覆盖回 `baseRowsByComp`，`LOG.warnf` 记录 `lineItemId/componentId/oldRowCount`，并 `EMPTY_OVERWRITE_BLOCKED_COUNT.incrementAndGet()`；
- 其余情况一律不动（新值非空 → 正常更新；新旧都空 → 无操作）。

新增可观测计数器（供测试断言/监控，与既有 `ROW_KEY_FIELDS_QUERY_COUNT`(`:2547`) 同风格）：

```java
public static final java.util.concurrent.atomic.AtomicLong EMPTY_OVERWRITE_BLOCKED_COUNT =
        new java.util.concurrent.atomic.AtomicLong();
```

### 2.2 `ConfigureSnapshotService` 写 `snapshot_rows` 处

在真正写入某组件 `snapshot_rows` 前：**待写值为空（`null`/`[]`）且库内现值非空** → 跳过该组件写入 + `LOG.warnf` + 同一计数器自增（计数器放 `CardSnapshotService` 上，`ConfigureSnapshotService` 引用即可；若造成循环依赖则各自留一个，测试分别断言）。

### 2.3 Task2 禁止事项

- **不得**在 `saveDraft` 的 `row_data` 入参上加护栏（理由见 §0.3）；
- **不得**触碰核价侧 `buildCostingCardValues` / `precomputeCostingDriverUnion` / `CostingVersionService`（AC-17 白名单纪律）；
- 禁止 `Math.max(expansion.rowCount, baseRows.length)` 式写法（AP-51）。

---

## 三、Task3 —— 测试

### 3.1 后端测试（新增，放 `cpq-backend/src/test/java/com/cpq/quotation/service/`）

| 用例 | 断言 |
|---|---|
| T1 同模板复制继承 | 复制后逐组件 `snapshot_rows` / `row_data` 行数与源单**逐一相等**；`subtotal` / `original_amount` / `total_amount` 相等；`deleted_tree_nodes` 相等 |
| T2 同模板复制不重建 | 复制过程中 `refreshQuoteCardValues` 未被调用（或 `quote_card_values` 与源单逐字节相等） |
| T3 结构补建 | 复制后 `quotation_view_structure` 该单 4 份齐全 |
| T4 换模板复制零回归 | 换模板路径行为与改动前一致（仅迁移 INPUT 字段、`snapshot_rows` 为 null、走重建） |
| T5 护栏 · 重查空 | 构造"driver 重查 0 行 + 旧快照非空" → 旧 baseRows 保留，`EMPTY_OVERWRITE_BLOCKED_COUNT` +1 |
| T6 护栏 · 正常更新不受影响 | 新 baseRows 非空时正常覆盖，计数器不变 |

> 测试连库注意：后端测试 profile 连的库与 dev server 可能不同（需求文档 §7.4 同款坑），夹具不要硬编码具体 quotationId。

### 3.2 主线亲验（技术总监执行，不派子代理）

1. 真实 `POST /quotations/{源单}/copy` → 逐页签 SQL 比对；
2. 浏览器走完向导（**必须点「下一步」**）→ 再比对，确认 `row_data` 不被抹；
3. 浏览器点「刷新基础数据」→ 确认页签不变空；
4. 核价侧 18 页签行数与需求文档 §3.6 基线一致。

---

## 四、验收对照（需求文档 §六）

本期覆盖 AC-1 / AC-2 / AC-3 / AC-4 / AC-5 / AC-7 / AC-8 / AC-9 / AC-12。
AC-6（源单再编辑不影响新单）在继承方案下**天然成立**（新单值已落库，不再取数）。
AC-10（组合产品）/ AC-11（冻结态源单复制）本期按"不回归"要求，若无现成夹具则登记 BACKLOG。
AC-13 E2E 由主线在亲验阶段跑。
