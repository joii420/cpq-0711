# 行键放开输入字段 + 录入实时判重 — 设计方案

> 日期：2026-06-09 | 状态：设计已评审通过 | 范围：组件管理（行键勾选）+ 报价侧录入/提交（QUOTE）
> 决策：方案 A —— 异构字符串 `rowKeyFields`（driver 叶子列名 + 输入字段名），零数据迁移
> 关联前序：`docs/superpowers/specs/2026-06-09-multi-subtotal-conditional-formula-design.md` §7 / 设计 E（Plan 1 后端提交期校验）；`docs/superpowers/specs/2026-06-08-quote-manual-row-design.md`（手动行）

## 1. 背景与需求

「组件设置行键」已落地，但当前限制：**只有能解析出 driver 真实列的字段**（有 `basic_data_path` 且叶子列命中 driver 视图声明列）才可勾为行键；输入字段无 `basic_data_path` → 一律 `eligible=false`（提示「该字段无 driver 列，不能作行键」）。

**核心诉求（用户 7 条澄清结论）：**

| # | 结论 |
|---|---|
| 1 | 触发场景：**手动行靠手填字段当行键**，现在 INPUT 字段勾不上 |
| 2 | 放开范围：**driver 字段 + 输入字段（INPUT_TEXT / INPUT_NUMBER）**；FORMULA / DATA_SOURCE / FIXED_VALUE 仍**不可**作行键（派生值不该由人定身份） |
| 3 | 重复拦截时机：**录入时实时软提示（标红 + tooltip，不回滚、不阻断草稿）** + 提交时硬拦（保留 Plan 1） |
| 4 | 实时比较范围：手填行键 vs **同组件全部行**（driver 展开行 + 手动行） |
| 5 | 实时拦截方式：**只标红 + 提示，值仍留草稿**，真正阻断在提交 |
| 6 | 实时标红口径：同组件内**组合键重复的行全部标红，不分来源**（driver 行互撞也标红） |
| 7 | 组合键**可混用** driver 列 + 输入字段 → 取值要能同时从 driverRow 和手填值里取 |

## 2. 核心技术难点

放开输入字段后：

1. **`rowKeyFields` 变异构**：从"全是 driver 真实列名"变成 driver 列名 + 输入字段名混存。
2. **`computeRowKey` 当前只读 `driverRow`**（前端 `useCardSnapshots.ts:52` + 后端 `FormulaCalculator.java:446`），必须改成能从 **driverRow ⊕ 行级手填值** 取。
3. **鸡生蛋**：后端 `computeRows`（`FormulaCalculator.java:395`）在 `mergeRow(driverRow, editValues)`**之前**算 rowKey（因为要先有 rowKey 才能按 key 查 editRows）。若输入字段值只存 editRows（按 rowKey 索引），算 rowKey 时拿不到 → 循环依赖。
   - **解法**：算 rowKey 时从**位置化（按行下标）的行值**取输入字段，**不碰 editRows-by-key**。位置化输入值的权威源是 `componentData[M].rowData`（comp.rows → autosave → row_data），按行下标对齐，无循环。

## 3. 方案 A — 口径变化

| 维度 | 现状 | 改后 |
|---|---|---|
| `rowKeyFields` 条目语义 | 全是 driver 叶子列名 | 异构：driver 字段→叶子列名（不变）；输入字段→**字段名** |
| 数据结构 | `string[]` | `string[]`（不变，**零迁移**） |
| 判重取值源 | （旧无判重）`computeRowKey` 只读 `driverRow` | 新 `computeDedupKey`：`driverRow[f]` 非空优先，否则 **位置化行值 `rowValues[f]`** |
| 取值位置依据 | — | 按**行下标**取，不经 editRows-by-key（避开鸡生蛋）；现有 `computeRowKey` 不动 |

**撞名兜底**：方案 A 唯一风险是"输入字段名 == 某 driver 列名"导致取值歧义。处理：在 `resolveRowKeyCandidates` 判定输入字段合格时，若字段名命中 driver 列集合 → `eligible=false`、`reason="字段名与 driver 列撞名，不能作行键"`，从源头不让勾（无需单独的保存期校验）。

## 4. 改动点清单

### 4.1 配置端（放开勾选）

- **`ComponentDriverService.resolveRowKeyCandidates`**（`:1036`）：新增分支——字段 `field_type ∈ {INPUT_TEXT, INPUT_NUMBER}` 时，若字段名**未**命中 driver 列集合 → `eligible=true`、`resolvedColumn=字段名`、`source="input"`、`reason=null`；若**命中** driver 列名（撞名）→ `eligible=false`、`reason="字段名与 driver 列撞名，不能作行键"`。无 `basic_data_path` 不再一律判 false。driver 字段判定逻辑不动（命中 driver 列 → `source="driver"`）。`field_type` 从入参 `fields: List<Map<String,Object>>` 的 `field_type` 键直接读。
- **`RowKeyCandidatesResponse.Candidate`**：可加 `String source`（`"driver"` / `"input"`），给前端 tooltip 区分（非必须，但建议加便于文案）。
- **`FieldConfigTable.tsx`**（`:432`）：勾选逻辑不变（仍用 `cand.eligible` + `cand.resolvedColumn`）；输入字段的 `resolvedColumn` = 字段名，`onToggleRowKey(字段名, checked)` 自然写入 `rowKeyFields`。tooltip 文案按 `source` 区分（「行键列（driver）」/「行键列（手填）」）。

### 4.2 取值引擎 —— 新增 input-inclusive 的 `computeDedupKey`（FE/BE 双镜像）

**关键决策（降风险）**：**不改**现有 `computeRowKey`。现有 `computeRowKey(rowKeyFields, driverRow)`（前端 `useCardSnapshots.ts:52` + 后端 `FormulaCalculator.java:446`）服务 **editRows / formulaResults 行对齐**（AP-54 业务键对齐），改其签名/口径会牵动公式编辑值对齐链路，风险大。改用**并行新增**一个判重专用函数：

- **新增 `computeDedupKey(rowKeyFields, driverRow, rowValues)`**：逐字段 `nonEmpty(driverRow[f]) ? driverRow[f] : rowValues[f]`，`||` 拼接；空/null/`__seq_no__` 哨兵口径与 `computeRowKey` 一致（空 → 不可用，调用方按"跳过判重"处理）。
  - 前端：放在 `useCardSnapshots.ts`（与 `computeRowKey` 并列导出）或新建 `rowDedupKey.ts`。
  - 后端：`FormulaCalculator` 新增 public 方法（与 `computeRowKey` 并列）。
- **唯二使用方**：① 前端实时判重 `findDuplicateRowKeys`（§4.4）；② 后端提交校验 `RowKeyUniquenessService`（§4.3）。**不接入任何 editRows / formula 路径**，故无 AP-54 对齐风险、不触发鸡生蛋（editRows-by-key 完全不参与）。
- 现有 `computeRowKey` 两个前端调用点（`:136`/`:148`）、后端 `:395` / `CardSnapshotService:824` 一律**保持不变**。

### 4.3 提交校验（Plan 1 改取数源 —— 已核实定案）

**已核实事实**（`CardSnapshotService.buildCardValues:465-511` + `QuotationLineComponentData` 实体）：
- `quoteCardValues.baseRows` 仅来自 `snapshot_rows`（driver 展开），**不含手动行、不含输入字段值** → Plan 1 现取数源对输入字段键不够。
- 权威**位置化（按行下标）**源为两路：
  - **驱动列** ← `quotation_line_component_data.snapshot_rows`（按行下标 → `driverRow`）；
  - **输入值 + 手动行** ← `quotation_line_component_data.row_data`（JSONB 数组，按行下标；手动行 `_origin='manual'` 追加末尾，排序见手动行 spec §4.1）。

**定案改法 —— `RowKeyUniquenessService` 重构为两路位置化合并：**
- 入参从 `LineItemRows(label, valuesJson)` 改为按**行明细 × 组件**提供：`componentId`、`snapshot_rows` JSON、`row_data` JSON。
- 对组件每行下标 `i` 构造取值：`driverRow_i = snapshot_rows[i].driverRow`（`i ≥ driverCount` 的手动行 → 空对象）；`rowDataRow_i = row_data[i]`。
- 逐行调 `formulaCalculator.computeDedupKey(rowKeyFields, driverRow_i, rowDataRow_i)`（driver 行在 row_data 里取**非 manual 子序列**按下标对齐 `driverDataRows[i]`；超出部分的 `_origin='manual'` 行 driverRow 传空对象）→ 交 `RowKeyConflictDetector.detect`。
- 该两路合并是**安全超集**：driver 列优先取展开值，输入字段/手动行从 row_data 取，与 rowData 是否也含 driver 列无关。
- rowKeyFields（含 AP-39 冻入）仍从 `quotation_view_structure` 的 `QUOTE_CARD` 份取（`QuotationService.submit:680` 既有逻辑不变）。

### 4.4 录入实时判重（`QuotationStep2`）

- 新增纯函数 `findDuplicateRowKeys(rows, rowKeyFields)`：对当前 active 组件全部渲染行算 `computeDedupKey`（driver 列从该行 driverRow 取、输入字段从 rawRow 取），按 key 分组，返回**出现 ≥2 次且 key 非空**的行下标集合（空 key = 未填完 → 不判重，与 Plan 1 detector「blank key 跳过」一致）。
- 渲染时（`:1899` 一带）：若当前行下标 ∈ 重复集合 → 行 / 行键单元格加红色边框 className + `Tooltip`「行键重复：与第 X 行冲突，提交前需修正」。
- 触发时机：`handleRowChange` / `handleInputBlur` / `handleAddRow` / `handleDeleteRow` 改 row 后，渲染自然重算（`useMemo` 依赖 comp.rows）。**不回滚、不拦 autosave**。
- 下标按 **AP-54** 用对象引用映射回真实 `comp.rows` 下标；行数按 **AP-51** 纪律（driver 权威 + 手动行，禁 `Math.max`）。

## 5. AP-44 / 协议影响与测试

- 新增 `computeDedupKey` 双镜像（不改 `computeRowKey`）+ 改 `QuotationStep2.tsx`（渲染层标红）/ `FormulaCalculator.java`（加方法）/ `RowKeyUniquenessService.java`（重构取数）—— 改 `QuotationStep2.tsx` 命中 CLAUDE.md「协议级改动必跑 E2E」清单。
- **强制 E2E**：`quotation-flow.spec.ts` + `composite-product-flow.spec.ts` 双 spec，`'加载中' final count = 0`，8 Tab 截图。
- **新增专项 E2E**：
  1. 无 driver 组件加两条手填行键相同 → 实时标红 + 提交 422；
  2. driver 列 + 手填输入混合键 → 唯一时提交成功、撞键时标红 + 422；
  3. 行键勾选放开后 INPUT 字段可勾、FORMULA 仍 disabled。
- **单测**：
  - `RowKeyConflictDetectorTest`：不变（纯函数判重逻辑未变）；
  - `RowKeyUniquenessServiceTest`：补「输入字段键 / driver+输入混合键 / 撞名」用例；
  - `FormulaCalculator#computeDedupKey`：新方法单测（driver 优先、driver 空回退 rowValues、混合键、空哨兵）；现有 `computeRowKey` 单测不动；
  - 前端 `findDuplicateRowKeys`：vitest（无重复 / 单重复 / 多组重复 / 空 key 跳过）。

## 6. 不做（YAGNI）

- 不引入 `rowKeyFields` 结构化条目（方案 B/C）、不迁移存量数据。
- 实时判重**不回滚、不阻断**草稿保存（Q5=B）。
- driver 互撞虽实时标红（Q6=A），但不新增"driver 行专门去重"逻辑——复用统一分组即可。
- 不动核价侧（核价不允许编辑、无手动行）。

## 7. 主要风险与验证点

1. **行下标对齐**（§4.3 两路合并）——`snapshot_rows` 与 `row_data` 必须同序（driver 行在前、手动行追加末尾）。实施时用单测固定一份 driver+manual 混合样本验证对齐；若发现某 line item 两路长度不一致，以 `row_data` 长度为行数权威（AP-51 纪律），缺失 driverRow 按空对象处理。
2. **撞名**（driver 列名 == 输入字段名）——`resolveRowKeyCandidates` 判定输入字段时命中 driver 列即 `eligible=false`，从源头不让勾。
3. **鸡生蛋**——`computeDedupKey` 仅用位置化取值（snapshot_rows + row_data），完全不碰 editRows-by-key；现有 `computeRowKey`（editRows 对齐用）原样不动，故无循环依赖。
4. **AP-51 行数纪律 / AP-54 下标映射**——实时判重用渲染行集合 + 对象引用映射，沿用既有纪律。

## 8. 验收标准

- 组件管理：INPUT_TEXT / INPUT_NUMBER 字段「行键」勾选框可勾，tooltip 显示「行键列（手填）」；FORMULA / DATA_SOURCE / FIXED_VALUE 仍 disabled。
- 报价录入：同组件组合键重复的行（含 driver 互撞、手动行撞 driver 行）实时全部标红 + tooltip 提示，草稿照常保存。
- 提交：含重复组合键（含输入字段键 / 混合键）的报价单 submit → 422 + 冲突明细行号；全唯一 → SUBMITTED。
- 双 spec E2E `'加载中' final count = 0`；新增 3 个专项 E2E 通过。
