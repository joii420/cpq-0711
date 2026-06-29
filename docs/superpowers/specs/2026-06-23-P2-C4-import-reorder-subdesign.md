# P2-C4 补充子方案：核价侧跨行 partSet union 在导入主路径的时序重排

> 立项 2026-06-23（architect）。本文是 `docs/superpowers/specs/2026-06-23-P2-C4-costing-partset-union-design.md`（主设计）§2.2(2) 标「需 codegraph 确认」的那个缺口的精确子方案。
> 工作分支 `worktree-p2-costing-partset`（基于含 P1 的 master）。本文所有结论用 codegraph + 真实代码核实（见 §1 追踪结论），不写实现代码、不改任何 `.java`。
>
> 🔑 **核实结论一句话**：导入主路径上，核价侧（`buildCostingCardValues`）与报价侧（`buildCardValues`）在 `CardSnapshotService` 里物理隔离，**且导入路径根本不写 `snapshot_rows`** → 报价侧 `buildCardValues` 在导入时本就读不到任何 driver 数据（恒算空 baseRows，与 cd 的 persist 时序无关）。因此推荐的落地形态是 **§3 的拆分形态（a+c 合一）：报价侧 snapshotLineValues 留 pass1 原位逐行不动，核价侧 buildCostingCardValues 延到 pass2 整单 union** —— **报价侧逐位零改，可由本文 §4 证明 + §6 md5 门禁守住。**

---

## 0. TL;DR

| 项 | 结论 |
|---|---|
| 报价侧 `buildCardValues` 数据来源 | `template.components_snapshot`（tab 顺序）+ `quotation_line_component_data.snapshot_rows`（**键 `line_item_id`**，`CardSnapshotService.java:501-505`）。`snapshot_rows` 由 `ConfigureSnapshotService.writeSnapshot` 写 |
| 导入路径写不写 `snapshot_rows`？ | **不写。** `ImportExecutionService` 全文无 `ConfigureSnapshotService` 调用；两循环里唯一 persist 的 cd（`:228/:646`）是 `componentId=null` / `tabName="Import"` 的「Import」行，**不是 snapshot_rows 载体**，`buildCardValues` 按 `r[0]==null continue`（`:510`）直接忽略它 |
| 故导入时报价侧算什么 | `snapByCompId` 恒空 → 每组件 `buildBaseRowsFromSnapshotRows(null, cid)` → 空 baseRows。**报价侧 quote_card_values 在导入时本就是「空 driver、只有 formulaResults 框架」**，与「cd 是否已 persist」**完全无关**（cd 那行根本不被读） |
| 核价侧 `buildCostingCardValues` 依赖报价侧吗？ | **不依赖。** 它读 `template.components_snapshot` + `bomClosureService.compute(li.productPartNoSnapshot)` + driver expand（`:545-578`），**完全不碰 `snapshot_rows` / 不碰报价侧 cd / editRows 恒空**。核价侧可独立延后 |
| 推荐落地形态 | **拆分 + 二次循环**：pass1 逐行 `li.persist()` + `cd.persist()` + 报价侧 `snapshotLineValues` 原位（实际等价于「只算报价侧空框架」）；pass2 全 li 建好后 `precomputeCostingDriverUnion(quotationId)` 一次 → 逐行 `buildCostingCardValues(li, unionByComp)`。报价侧零改 |
| 报价侧等价确切依据 | (a) 报价侧不依赖核价侧、不依赖 cd persist 时序（cd 那行不被读）；且导入路径不写 snapshot_rows → 报价侧产出与「cd 在 snapshot 前/后 persist」无关 → **重排对报价侧逐位零影响** |
| saveDraft / ConfigureProduct 复用 | 同一 `precomputeCostingDriverUnion` 入口；N=1 退化为逐行（无收益也无害），N>1 命中 union |
| 测试门禁增量 | C4 主设计 §5 基础上**新增报价侧 `md5(quote_card_values + quote_excel_values)` 前后对账**（证明重排没动报价侧）+ 连跑两次 + 既有套件 + E2E 双 spec；A/B 用 `git stash` 切新旧导入路径 |
| 风险评级 | **低**（拆分形态）。报价侧零改有 md5 门禁兜底；核价侧延后 union 风险沿用主设计 §3 的等价论证 |

---

## 1. codegraph 追踪结论：报价侧 / 核价侧数据来源 + 导入时序

### 1.1 `snapshotLineValues(li)` 内部分叉（`CardSnapshotService.java:401-441`）

`@Transactional`，按 `li.id` 在本事务内重新 `findById` 取 managed 实体，然后**同时**算三块：

```
snapshotLineValues(li):
  managed = findById(li.id); q = findById(managed.quotationId)
  ── 报价侧 ──
  managed.quoteCardValues  = buildCardValues(managed, q.customerTemplateId)              // :413
  if (managed.quoteExcelValues == null)                                                   // :420 新行 bootstrap
      managed.quoteExcelValues = buildExcelValues(managed, q.customerTemplateId, ...)
  ── 核价侧（与报价侧无数据交叉）──
  if (q.costingCardTemplateId != null):                                                    // :426
      managed.costingCardValues  = buildCostingCardValues(managed, q.costingCardTemplateId, q.customerId, q.id)
      managed.costingExcelValues = buildExcelValues(managed, q.costingCardTemplateId, ..., managed.costingCardValues, true)
  managed.cardSnapshotAt = now; managed.quoteValuesAt = now
```

**关键：报价侧与核价侧是两段彼此不传值的并列写。** 唯一耦合是「核价 Excel 透传 `managed.costingCardValues`」（同侧核价，不碰报价）。报价 Excel 透传 `managed.quoteCardValues`（同侧报价，不碰核价）。两侧 Excel 都只吃**同侧**卡片值。

### 1.2 报价侧 `buildCardValues` 读什么（`:485-533`）

1. `SELECT components_snapshot FROM template WHERE id = :tid`（模板 tab 顺序 + componentId）。
2. **`SELECT component_id, snapshot_rows, deleted_row_keys FROM quotation_line_component_data WHERE line_item_id = :lid`**（`:501-505`）—— 这是报价侧 driver 行的**唯一**来源。
3. `for (Object[] r : compData)`：**`if (r[0] == null) continue;`**（`:510`）→ `componentId=null` 的行被丢弃；其余按 `componentId → snapshot_rows` 入 `snapByCompId`。
4. 每 tab：`buildBaseRowsFromSnapshotRows(snapByCompId.get(cid), cid)` → 组装 `quote_card_values`。

**结论**：报价侧 baseRows 完全来自 `quotation_line_component_data.snapshot_rows`（键 `line_item_id`、需 `component_id` 非空）。它**不读核价侧任何东西，也不 expand**（与核价侧 buildCostingCardValues 形成对照，注释 `:543` 明说「报价侧复用 snapshot_rows，非双写 expand」）。

### 1.3 `snapshot_rows` 由谁写、何时写

- 写入者 = `ConfigureSnapshotService.writeSnapshot(lineItemId, componentId, tabName, rowsJson)`（`:597`），上游 `snapshotLines` / `snapshotQuotation`（`:87/:158`）逐 (line × driver 组件) `expand` 后 `writeSnapshot`。
- 调用链：加产品走 `ConfigureProduct...`、saveDraft 走 `snapshotService.snapshotQuotation`（增量）。
- **导入路径核实**：`ImportExecutionService` 全文 grep `ConfigureSnapshotService` / `snapshotQuotation` / `snapshotLines` / `writeSnapshot` = **0 命中**。导入路径**从不写 `snapshot_rows`**。

### 1.4 两个导入循环里 cd 的真实身份（推翻「cd 是报价侧 snapshot_rows 载体」的隐含假设）

两循环结构相同（loop A `:196-253`、loop B `:621-669`）：

```
li = new QuotationLineItem(); ...; li.persist();              // A:203 / B:628
try { ensureStructure(qId); snapshotLineValues(li); }         // A:206-207 / B:631-632  ← 报价+核价快照都在这
cd = new QuotationLineComponentData();
cd.lineItemId = li.id;
cd.componentId = null;            // ⚠️ A:228 / B:646  —— componentId 恒 null
cd.tabName     = "Import";        //    A:229 / B:647
cd.rowData     = "[{...导入行原值...}]";
cd.persist();                     // A:232 / B:650  ← 在 snapshotLineValues 之后
```

**这行 cd 不是 `snapshot_rows` 载体**：它 `componentId=null`、`snapshot_rows` 字段为 null、`tabName="Import"`。报价侧 `buildCardValues` 的 `:510 if(r[0]==null) continue;` 把它直接跳过。它存在的目的是给「导入视图/Excel 原始行值回看」（`row_data`），不参与 driver 渲染。

> **由此推翻主设计 §2.2(2) 的担忧前提**：主设计担心「snapshotLineValues 的报价侧读 `quotation_line_component_data.snapshot_rows`，若 cd 仍在 `:644` 才写则报价侧读空」。真相是：①该 cd 根本不是 snapshot_rows 载体（componentId=null）；②导入路径压根不写 snapshot_rows。所以报价侧在导入时**无论 cd 何时 persist 都读空**——这不是 bug，是导入路径既有现状（导入后报价卡 driver 行靠后续「草稿打开 refresh-card-snapshot / saveDraft」才填）。

### 1.5 核价侧 `buildCostingCardValues` 的输入闭合性（`:545-585`）

```
buildCostingCardValues(li, costingTemplateId, customerId, quotationId):
  SELECT components_snapshot FROM template WHERE id = :costingTemplateId        // 核价模板
  closure = bomClosureService.compute(li.productPartNoSnapshot, {})             // 只吃 li 的 part_no 快照
  baseRowsByComp = expandTemplateDriverBaseRows(costingTemplateId, li, customerId, quotationId, closure)
  root = assembleTabsWithFormulaResults(snapshot, baseRowsByComp, null)         // editRows=null（核价 editRows 恒空）
```

输入只有：核价模板 snapshot + `li.productPartNoSnapshot` + `customerId` + `quotationId`（设 QuotationIdContext/SpineKeysContext 用）+ driver 视图查询。**不读 `quotation_line_component_data`、不读 `snapshot_rows`、不读报价侧任何列、不读本行 cd。** 与记忆 `quote-card-values-excludes-manual-input-rows` / 主设计 §6 的「核价侧 editRows 恒空、与报价侧物理隔离」一致。

**核价侧可被整体延后到 pass2，且其结果只由「该行 part_no + customer + 核价模板 + driver 视图」决定，与建行顺序/cd 时序无关。**

---

## 2. 为什么 lazy 形态对导入不成立、必须二次循环

主设计 §2.2(2) 给了两个候选：方案 A「全行预取 + 二次循环」和「保守降级 lazy 请求级缓存」。对**导入路径**，lazy 形态不成立：

- lazy 形态依赖「首个核价行触发整单预取 → 那一刻能枚举该 quotationId 全部 li」。
- 但导入是**边建边快照**（`li.persist()` 紧跟 `snapshotLineValues(li)`，loop 内逐行）。第 1 行触发预取时，第 2..N 行的 li 还没 `persist`，`QuotationLineItem.list("quotationId", quotationId)` 只能查到第 1 行 → union 退化成逐行，预取层每行各算一次 = 零收益（甚至多一层枚举开销）。

**故导入路径必须把核价快照抽到「全部 li 建好之后」的二次循环**（pass2），union 才有全集可并。saveDraft 路径不同（行已在库，lazy 可成立），见 §5。

---

## 3. 精确的二次循环重排设计（两个循环同构改造）

采用 **拆分形态**：报价侧留 pass1 原位、核价侧延到 pass2 union。这是 §0 推荐、§4 可证报价侧零改的形态。

### 3.1 当前单循环（每行）副作用清单（loop A `:196-253` / loop B `:621-669`）

| 步骤 | loop A 行 | loop B 行 | 写库内容 | 归属 |
|---|---|---|---|---|
| S1 build li 字段 + `li.persist()` | 196-203 | 621-628 | `quotation_line_item` 行 | pass1 |
| S2 `ensureStructure(qId)` | 206 | 631 | 4 份结构（幂等，整单一次即可） | pass1（提整单一次） |
| S3 `snapshotLineValues(li)` | 207 | 632 | `quoteCardValues / quoteExcelValues / costingCardValues / costingExcelValues / cardSnapshotAt / quoteValuesAt` | **拆**：报价 4 列+时间戳 pass1；核价 2 列 pass2 |
| S4 build `cd`（componentId=null/Import）+ `cd.persist()` | 226-232 | 644-650 | `quotation_line_component_data`（Import 行） | pass1 |
| S5 build `excelViewSnapshot` 写回 `li.excelViewSnapshot` | 234-251 | 652-669 | `quotation_line_item.excel_view_snapshot` | pass1 |
| S6 `successRows++` 等统计 | 253 | 669+ | 内存计数 | pass1 |

### 3.2 重排：拆 `snapshotLineValues`，核价侧延后

**改造点 A — `CardSnapshotService` 新增两个细分入口**（不改 `snapshotLineValues` 既有语义，新增 delegate）：

1. `snapshotQuoteSideOnly(QuotationLineItem li)`：只做 §1.1 的报价侧两段（`buildCardValues` + 新行 `buildExcelValues` bootstrap）+ 写 `cardSnapshotAt/quoteValuesAt`。**与现状报价侧逐位同源**（同一 `buildCardValues`/`buildExcelValues` 调用，参数一字不差）。
2. `snapshotCostingSideOnly(QuotationLineItem li, Map<UUID,Map<String,ExpandDriverResponse>> unionByComp)`：只做 §1.1 核价侧两段，`buildCostingCardValues` 走主设计 §2.2(3)(4) 的 union 重载（unionByComp 命中组件复用整单一次查，否则回落逐行）。
3. 既有 `snapshotLineValues(li)` 保持不变（= `snapshotQuoteSideOnly(li)` + `snapshotCostingSideOnly(li, null)`），供加产品 N=1 / 既有测试零破坏复用。

> 拆分可行性（主设计 §4 问的）：**可拆**。依据 §1.5——核价侧 `buildCostingCardValues` 不依赖报价侧已写的 `snapshot_rows`、不依赖 `managed.quoteCardValues`（核价 Excel 只透传 `managed.costingCardValues`，是核价侧自产）。两段在 `snapshotLineValues` 里本就是无数据流的并列写，物理上独立。唯一共享的 `managed.cardSnapshotAt/quoteValuesAt` 时间戳——pass1 报价段写一次即可，pass2 核价段不必重写（或重写为同值，幂等）。

**改造点 B — 两个导入循环改两遍（pass1 / pass2），写库内容与顺序语义不变**：

```
pass1（沿用现有逐行循环，仅把 snapshotLineValues 换成 snapshotQuoteSideOnly）:
  ensureStructure(quotationId)                      // 提到循环外，整单一次（幂等，等价）
  for row in dataRows:
    li = new ...; li.persist();                     // S1 不变
    snapshotQuoteSideOnly(li);                       // S3 报价侧（原位、逐行、逐位等价）
    cd(componentId=null/Import); cd.persist();       // S4 不变
    li.excelViewSnapshot = ...;                       // S5 不变
    successRows++;                                    // S6 不变

pass2（全部 li 建好后，一次）:
  unionByComp = precomputeCostingDriverUnion(quotationId);   // 整单一次：并 partSet → 每 recursive 组件一次 expandMulti
  for li in QuotationLineItem.list("quotationId", quotationId 且属本批) :
    snapshotCostingSideOnly(li, unionByComp);                 // S3 核价侧（延后 + union）
```

**写库内容/顺序语义保持点**：
- `quotation_line_item` / `cd(Import)` / `excel_view_snapshot` 的写入内容、每行写入顺序、行间相对顺序**与现状逐行循环完全一致**（pass1 没动这些）。
- 报价侧 4 列在 pass1 原位写（与现状同一次 `findById` 后写同一 managed 实体），逐位等价（§4）。
- 核价侧 2 列从「pass1 原位」挪到「pass2 union」——值由 §1.5 闭合输入决定，与挪动位置无关；union 等价由主设计 §3 论证（partSet 并集对无行维度谓词视图逐位一致）。
- 时间戳：pass1 报价段写 `cardSnapshotAt/quoteValuesAt`；pass2 核价段不重写（避免「core 段把时间戳推后」造成与现状的纳秒级差异）。md5 对账显式排除时间戳列（§6），不构成等价隐患。

> **pass1/pass2 都在同一 `processImport` 的事务边界内**（`snapshotLineValues` 本身 `@Transactional`，按 li 各自子事务）。pass2 的 `QuotationLineItem.list` 能查到 pass1 已 persist+flush 的全部行（同请求、Panache flush-on-query）。落地需确认 pass1 各行 `li.persist()` 在 pass2 查询前已对该 PersistenceContext 可见（Panache 默认 flush-before-query=AUTO 成立）。

### 3.3 `precomputeCostingDriverUnion` 顺带折叠的 N 次小查

主设计 §1.2 提到「每行重查模板组件清单」（`SELECT DISTINCT c.id, bom_recursive_expand ...`，N 次）。二次循环形态天然可把它折叠为 pass2 整单一次（预取层取一次 recursive 组件清单 + 各行闭包）。这是二次循环相对 lazy 形态的额外收益（lazy 也能折叠，但导入用不了 lazy）。

---

## 4. 报价侧逐位等价证明（结论 = a，且因导入特性比 a 更强）

主设计 §4 要求在 (a)/(b)/(c) 三选一并论证。**本子方案结论 = (a) 报价侧不依赖 cd → 重排零影响**，并因导入路径的特殊性给出比 (a) 更硬的证明：

**命题**：pass1/pass2 拆分重排后，导入产出的 `quote_card_values` 与 `quote_excel_values` 与改造前逐位相同。

**证明**：
1. 报价侧产出由 `snapshotQuoteSideOnly`（= 现状 `snapshotLineValues` 的报价两段，逐字相同调用 `buildCardValues(managed, q.customerTemplateId)` + 新行 `buildExcelValues(...)`）决定。pass1 仍在「`li.persist()` 之后」原位逐行调它 → 调用参数、调用时序、所读实体状态与现状一致。
2. `buildCardValues` 的输入 = `template.components_snapshot`（不随本行 cd 变）+ `quotation_line_component_data WHERE line_item_id=li.id AND component_id 非空` 的 `snapshot_rows`（§1.2）。
3. 导入路径**不写任何 component_id 非空的 cd / 不写 snapshot_rows**（§1.3/§1.4）。无论 cd（componentId=null/Import 行）在 `snapshotQuoteSideOnly` 之前还是之后 persist，第 2 步查询都因 `r[0]==null continue` 跳过它 → `snapByCompId` 恒空 → 报价 baseRows 恒空。**报价侧产出与 cd persist 时序完全无关**（cd 那行根本不进结果）。
4. `quote_excel_values`：`buildExcelValues(managed, customerTemplateId, customerId, managed.quoteCardValues)`，吃同侧 `quoteCardValues`（第 1-3 步证其不变）+ `li`（productAttributeValues 等在 `li.persist` 时已定，pass1 不变）。故逐位不变。
5. ∴ 报价侧 4 列逐位不变。核价侧延后不回写报价侧任何列（§1.1 两段无数据流交叉）→ 不污染报价侧。∎

**比 (a) 更强之处**：常规 (a) 是「报价侧不读 cd 故重排无影响」；这里额外证明了「导入路径报价侧本就读空、与 cd 存在与否都无关」，所以即便有人未来误把 cd 改成 component_id 非空，只要不写 snapshot_rows，报价侧仍读空——但那已超出本次重排范围。**本次重排的报价侧零影响是无条件成立的。**

> 为何不选 (b)/(c)？(b)「依赖但仍逐位相同」不适用——报价侧根本不依赖 cd。(c)「会变 → 拆分」——本子方案虽形态上就是拆分，但拆分动机不是「会变」而是「让核价侧能延后做 union」；报价侧侧本身零改、无需为等价而拆。

---

## 5. saveDraft / ConfigureProduct 复用同一 union 入口

| 入口 | N | 重排形态 | union 复用 |
|---|---|---|---|
| 导入（`ImportExecutionService` 两循环） | 多 | **二次循环（§3）**：pass1 报价 + pass2 核价 union | pass2 调 `precomputeCostingDriverUnion(quotationId)`，全行命中 |
| saveDraft（`QuotationResource.java:144-145`，仅 `quoteCardValues==null` 新行） | 多/少 | 行已在库（save 前已 persist），**可 lazy 或一次预取**。推荐：在 `:137` 的 `for` 之前对「本次将被 snapshot 的新行集合」算一次 `precomputeCostingDriverUnion`（或对全 quotation 算，预取层内部只对 recursive 组件并集，cost 与行数线性）→ 循环内 `snapshotLineValues` 透传 unionByComp | 命中 |
| ConfigureProduct 加单产品（`ConfigureProductResource.java:77`） | 1 | 单行，union 退化逐行 | `precomputeCostingDriverUnion` 对 N=1 = 逐行查（partSet 并集=该行 partSet），无收益也无害；或直接走 `snapshotLineValues(li, null)` 旧路径 |

**统一入口纪律**：所有路径的核价快照最终都经 `snapshotCostingSideOnly(li, unionByComp)`（unionByComp 可 null=逐行）。`unionByComp==null` 时 `buildCostingCardValues` 完全走旧逐行分支（主设计 §2.2(3) 零破坏 delegate）。这保证：① 导入/saveDraft 多行享 union；② 加产品 N=1 / 既有测试 / `refreshCostingCardValues` 单行刷新全部走 `null` 路径 = 改造前行为。

> saveDraft 注意（沿用 `:124-132` 既有铁律）：**严禁**在高频防抖 saveDraft 对**已有行**做全量重 expand。union 预取只覆盖 `quoteCardValues==null` 的新行（与现状 `:142-144` 跳过条件一致），不扩大重算面。预取层应只对「本次将 snapshot 的新行」并 partSet（而非全 quotation），避免给已有行徒增闭包计算。

---

## 6. 测试 / 门禁增量（在 C4 主设计 §5 基础上叠加）

### 6.1 新增：报价侧 md5 前后对账（本子方案的核心新增门禁）

C4 主设计 §5.2 已含 `quote_card_values`/`quote_excel_values` 的 md5 对账，但那是「守隔离未被破坏」的旁证。本子方案因**重排了导入循环结构**，必须把报价侧 md5 提升为**一等门禁**：

- **A/B 构造**：同一份导入 Excel，在 worktree 内用 `git stash` 切换「旧导入路径（单循环）」与「新导入路径（pass1/pass2）」，各跑一次真实 `processImport`（仿 P1 `MaterialMasterBatchImportIntegrationTest` 的 `@QuarkusTest` + 真实 `processImport` 调用 + `Statistics` 往返计数手法）。
- **报价侧逐位门禁**（必须全等，否则不合入）：
  ```
  md5( string_agg(quote_card_values  ORDER BY line_item_id) )   旧 == 新
  md5( string_agg(quote_excel_values ORDER BY line_item_id) )   旧 == 新
  ```
  这直接证明「pass1/pass2 重排没动报价侧一个字节」，对应 §4 的代码级证明 + 运行级背书。
- **核价侧门禁**（主设计 §5.2）：`md5(costing_card_values)` / `md5(costing_excel_values)` 旧 == 新（union 等价）。
- **其余守隔离**：`quotation_line_item`（**排除 `card_snapshot_at` / `quote_values_at` 时间戳列** + snapshot_rows 列）、`quotation_view_structure`、`quotation_line_component_data`（Import 行内容不变）md5 旧 == 新。

### 6.2 连跑两次 md5 一致（专项覆盖 union 共享 Map 可变别名，主设计 §3.4）

同一份导入连续跑两遍（各自清相关 li 的 4 份快照列 + Import cd → 重导），两遍 `costing_card_values` md5 一致。检测「共享 `unionByComp` 被就地 mutate 致执行序敏感」。

### 6.3 往返度量（仿 P1）

`Statistics.getPrepareStatementCount()` 包住 pass2，新旧各跑：核价 driver 往返从 ~N×M_rec 降到 ~M_rec。门槛：往返下降 **且** §6.1 全部 md5 全等，二者同时满足。

### 6.4 既有套件 + E2E（门禁）

- 后端：`SnapshotReconcileTest` / `CardValuesSnapshotTest`（含 `snapshotLineValues_isIdempotent`、`snapshotLineValues_writesValueColumns_withTabs`——拆分后这两个测试必须仍绿，证明 `snapshotLineValues(li)` 不带 union 时行为不变）/ `CardSnapshotFreezeTest` / `SubmitFreezeSnapshotTest` / `ConfigureProductServiceTest` / `CostingBomTreeSnapshotTest` 全绿。
- 新增 A/B 等价单测 `CostingImportReorderEquivTest`（导入路径专用，区别于主设计 §5.1 的 `CostingPartSetUnionEquivTest`）：构造含 N≥3 行、行间 partSet 重叠的导入，断言「单循环旧路径 vs pass1/pass2 新路径」产出的 4 份快照逐位相同（报价 2 份 + 核价 2 份）。
- E2E：`quotation-flow.spec.ts`（SIMPLE）+ `composite-product-flow.spec.ts`（COMPOSITE，验 composite 回落分支未被 union 误吞）双 spec `passed` + `'加载中' final=0`。
- **worktree 纪律**：后端测试在 worktree 的 `cpq-backend` 里 `./mvnw test` 跑（`cpq-worktree-maven-test-tree`）；主线亲验，不信子代理自报（`cpq-deliver-agents-overreport`）。

---

## 7. 风险评级 + 回滚

### 7.1 风险评级：**低**

| 维度 | 评估 |
|---|---|
| 报价侧 | **零改**：pass1 原位逐行调 `snapshotQuoteSideOnly`（= 现状报价两段）。§4 代码级证明 + §6.1 md5 门禁双保险 |
| 核价侧 union 等价 | 沿用主设计 §3 论证（partSet 并集对 `$cz_view` 无行维度谓词视图逐位一致；spineKeys/composite/lineItemId 全在 recursive=false 分支不碰；四条闸门 + componentId 消歧守门） |
| 循环重排 | pass1 保留全部既有写库内容与顺序；仅把核价 2 列从「行内原位」挪到「pass2 整单一次」。无新增写点、无并行（串行重排，符合 `cpq-expand-layer-not-threadsafe`） |
| 共享 `unionByComp` 可变别名 | 主设计 §3.4：深拷贝点 `rowToNode`（`valueToTree`）已存在；§6.2 连跑两次 md5 兜底 |
| 事务/可见性 | pass2 `QuotationLineItem.list` 需见 pass1 已 persist 行（Panache flush-on-query），落地需一条断言验证 |
| 最大残余风险 | pass1/pass2 跨整单失败语义：若 pass1 某行抛错（现状 `catch Exception 尽力而为`），pass2 union 仍对已建行算核价——与现状「某行报价快照失败但核价照算」语义一致，无回归 |

### 7.2 回滚（分层）

1. **形态回滚**：保留 `snapshotLineValues(li)` 整体调用、不拆 pass1/pass2 → 退回 lazy 形态（仅 saveDraft/加产品受益，导入不受益）或全逐行（改造前行为）。
2. **入参回滚**：`snapshotCostingSideOnly(li, null)` / `buildCostingCardValues(..., null)` → 核价侧走旧逐行（主设计 §2.2(3) 零破坏 delegate）。
3. **闸门回滚**：`eligibleForUnion` 收紧/恒 false → 全组件逐行 = 改造前。
4. **整段回滚**：`git revert` 本特性分支（独立 worktree，未合 master 前零影响）。
5. **守门失败即不合入**：§6 任一对账（报价 md5 / 核价 md5 / 连跑两次 / 往返下降 / 既有套件 / E2E 双 spec / A/B 逐位）不过 → 不合入。

---

## 8. 落地顺序建议

1. `CardSnapshotService` 新增 `snapshotQuoteSideOnly` / `snapshotCostingSideOnly`（拆分既有 `snapshotLineValues`，保持 `snapshotLineValues(li)` = 两者串联，既有测试零破坏）。
2. `precomputeCostingDriverUnion(quotationId / newLineIds)`（主设计 §2.2(1) + §4 闸门）。
3. 两导入循环改 pass1/pass2（§3.2）；`ensureStructure` 提整单一次。
4. saveDraft（§5）：循环前算一次 union（仅新行），透传。ConfigureProduct N=1 走 null 路径。
5. TDD：先写 `CostingImportReorderEquivTest`（§6.4）红→绿；再跑 §6 全套（报价 md5 + 核价 md5 + 连跑两次 + 往返 + 既有套件 + E2E 双 spec）。
6. 全绿 + 用户确认 → `superpowers:finishing-a-development-branch` 合 master + 清 worktree。

---

## 附录 A：codegraph / 真实代码核实命中清单

| 事实 | 出处（worktree 绝对路径相对 `cpq-backend/`） |
|---|---|
| `snapshotLineValues` 报价/核价并列写、无数据交叉 | `src/main/java/com/cpq/quotation/service/CardSnapshotService.java:401-441` |
| 报价侧 `buildCardValues` 读 `snapshot_rows`（键 line_item_id、需 component_id 非空，`r[0]==null continue`） | 同上 `:485-533`（查询 `:501-505`，跳过 `:510`） |
| 核价侧 `buildCostingCardValues` 闭合输入（不读 snapshot_rows/报价侧） | 同上 `:545-585` |
| `expandTemplateDriverBaseRows` recursive 分支（C4 现场）/ `buildSpineBaseRows` | 同上 `:1157-1208` / `:1214-1228` |
| `snapshot_rows` 写入者 `writeSnapshot` / 上游 `snapshotLines` | `src/main/java/com/cpq/configure/service/ConfigureSnapshotService.java:597` / `:158-216` |
| 导入 loop A：li.persist→snapshotLineValues→cd(componentId=null/Import).persist | `src/main/java/com/cpq/importexcel/service/ImportExecutionService.java:203/207/227-232` |
| 导入 loop B：同构 | 同上 `:628/632/645-650` |
| `ImportExecutionService` 无 `ConfigureSnapshotService` 调用（不写 snapshot_rows） | grep 全文 0 命中（`snapshotQuotation`/`snapshotLines`/`writeSnapshot`/`ConfigureSnapshotService`） |
| saveDraft 仅对 `quoteCardValues==null` 新行 snapshot | `src/main/java/com/cpq/quotation/resource/QuotationResource.java:133-152`（条件 `:142-145`） |
