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
| 取值源 | `driverRow[f]` | `driverRow[f]` 非空优先，否则 **位置化行值 `rowValues[f]`** |
| 取值位置依据 | — | 按**行下标**取，不经 editRows-by-key（避开鸡生蛋） |

**撞名兜底**：保存行键配置时，若某输入字段名恰好等于另一 driver 列名 → 后端校验报错拒绝保存（方案 A 唯一风险点，显式拦）。

## 4. 改动点清单

### 4.1 配置端（放开勾选）

- **`ComponentDriverService.resolveRowKeyCandidates`**（`:1036`）：新增分支——字段 `field_type ∈ {INPUT_TEXT, INPUT_NUMBER}` 时 `eligible=true`、`resolvedColumn=字段名`、`reason=null`；无 `basic_data_path` 不再一律判 false。driver 字段判定逻辑不动。需把 `field_type` 透进该静态方法（当前入参 `fields: List<Map<String,Object>>` 已含 `field_type` 键，直接读）。
- **`RowKeyCandidatesResponse.Candidate`**：可加 `String source`（`"driver"` / `"input"`），给前端 tooltip 区分（非必须，但建议加便于文案）。
- **`FieldConfigTable.tsx`**（`:432`）：勾选逻辑不变（仍用 `cand.eligible` + `cand.resolvedColumn`）；输入字段的 `resolvedColumn` = 字段名，`onToggleRowKey(字段名, checked)` 自然写入 `rowKeyFields`。tooltip 文案按 `source` 区分（「行键列（driver）」/「行键列（手填）」）。

### 4.2 取值引擎（FE/BE 双镜像，AP-44 协议级）

- **前端 `useCardSnapshots.ts#computeRowKey`**（`:52`）：签名加 `rowValues`，逐字段 `nonEmpty(driverRow[f]) ? driverRow[f] : rowValues[f]`。两个调用点（`:136` `rowKeyOf`、`:148` `getCell`）补传当前行的位置化值（driverRow 来自 `vt.baseRows[i].driverRow`，输入值来自对应行 row_data / rawRow）。
- **`QuotationStep2.tsx`**（`:1900`）：`computeRowKey(activeRowKeyFields, driverRowForKey, rawRow, i)` —— driver 列走 driverRow，输入字段走 `rawRow`（`comp.rows[i]`，已含手填值，line 1380「INPUT 受控值仍读 comp.rows」）。
- **后端 `FormulaCalculator#computeRowKey`**（`:446`）：签名加 `JsonNode rowValues`，逐字段 driverRow 非空优先否则 rowValues。
  - 调用点 `:395`（computeRows）：rowKey 在 `mergeRow` **之前**算——`rowValues` 传 **baseRow 自身的位置化值**（手动行的 rowData 已并入 baseRow；driver 行的手填输入值同源）。**不传 editValues**（避免鸡生蛋）。
  - 其余调用点（`CardSnapshotService.java:824`）：按签名补传位置化 rowValues（无则传 `MissingNode` / 空对象，退化为旧行为）。

### 4.3 提交校验（Plan 1 补值源）

- **`RowKeyUniquenessService.collectConflicts`**（`:307`）：当前只喂 `baseRows[].driverRow`。改为对每行构造**位置化 rowValues**：
  - driver 行：driverRow（展开值）+ 该行 row_data 输入字段；
  - 手动行：row_data 即全部值。
  - 调 `computeRowKey(rowKeyFields, driverRow, rowValues)`。
- **数据来源核实（实施 Task 0，最高优先）**：用代码核实 submit 时手动行 / 输入值如何进入校验可见的数据：
  - 若 `quoteCardValues.baseRows` 已含手动行 + 输入值 → 直接用；
  - 若未含 → 改为 join `componentData[].rowData` 位置化对齐（driver 行在前、手动行追加末尾，与手动行 spec §4.1 排序一致）。
  - **这是本方案最大不确定点，必须先核实再定取数路径，不得凭假设写代码。**

### 4.4 录入实时判重（`QuotationStep2`）

- 新增纯函数 `findDuplicateRowKeys(rows, rowKeyFields)`：对当前 active 组件全部渲染行算 `computeRowKey`，按 key 分组，返回**出现 ≥2 次且 key 非空**的行下标集合（空 key = 未填完 → 不判重，与 Plan 1 detector「blank key 跳过」一致）。
- 渲染时（`:1899` 一带）：若当前行下标 ∈ 重复集合 → 行 / 行键单元格加红色边框 className + `Tooltip`「行键重复：与第 X 行冲突，提交前需修正」。
- 触发时机：`handleRowChange` / `handleInputBlur` / `handleAddRow` / `handleDeleteRow` 改 row 后，渲染自然重算（`useMemo` 依赖 comp.rows）。**不回滚、不拦 autosave**。
- 下标按 **AP-54** 用对象引用映射回真实 `comp.rows` 下标；行数按 **AP-51** 纪律（driver 权威 + 手动行，禁 `Math.max`）。

## 5. AP-44 / 协议影响与测试

- `computeRowKey` 双镜像 + 改 `useCardSnapshots.ts` / `QuotationStep2.tsx` / `FormulaCalculator.java` / `RowKeyUniquenessService.java` —— 命中 CLAUDE.md「协议级改动必跑 E2E」清单。
- **强制 E2E**：`quotation-flow.spec.ts` + `composite-product-flow.spec.ts` 双 spec，`'加载中' final count = 0`，8 Tab 截图。
- **新增专项 E2E**：
  1. 无 driver 组件加两条手填行键相同 → 实时标红 + 提交 422；
  2. driver 列 + 手填输入混合键 → 唯一时提交成功、撞键时标红 + 422；
  3. 行键勾选放开后 INPUT 字段可勾、FORMULA 仍 disabled。
- **单测**：
  - `RowKeyConflictDetectorTest`：不变（纯函数判重逻辑未变）；
  - `RowKeyUniquenessServiceTest`：补「输入字段键 / driver+输入混合键 / 撞名」用例；
  - `FormulaCalculator#computeRowKey`：补 `rowValues` 取值单测（driver 优先、driver 空回退 rowValues、混合键）；
  - 前端 `findDuplicateRowKeys`：vitest（无重复 / 单重复 / 多组重复 / 空 key 跳过）。

## 6. 不做（YAGNI）

- 不引入 `rowKeyFields` 结构化条目（方案 B/C）、不迁移存量数据。
- 实时判重**不回滚、不阻断**草稿保存（Q5=B）。
- driver 互撞虽实时标红（Q6=A），但不新增"driver 行专门去重"逻辑——复用统一分组即可。
- 不动核价侧（核价不允许编辑、无手动行）。

## 7. 主要风险与验证点

1. **submit 时输入值取数路径**（§4.3 核实点）——实施 Task 0 先核实，决定是否 join row_data。**最高风险**。
2. **撞名**（driver 列名 == 输入字段名）——保存期校验显式拒绝。
3. **鸡生蛋**——已用"位置化 rowValues、不碰 editRows-by-key"规避；需在 computeRows `:395` 处确保传的是位置化值而非 editValues。
4. **AP-51 行数纪律 / AP-54 下标映射**——实时判重用渲染行集合 + 对象引用映射，沿用既有纪律。

## 8. 验收标准

- 组件管理：INPUT_TEXT / INPUT_NUMBER 字段「行键」勾选框可勾，tooltip 显示「行键列（手填）」；FORMULA / DATA_SOURCE / FIXED_VALUE 仍 disabled。
- 报价录入：同组件组合键重复的行（含 driver 互撞、手动行撞 driver 行）实时全部标红 + tooltip 提示，草稿照常保存。
- 提交：含重复组合键（含输入字段键 / 混合键）的报价单 submit → 422 + 冲突明细行号；全唯一 → SUBMITTED。
- 双 spec E2E `'加载中' final count = 0`；新增 3 个专项 E2E 通过。
