# 报价单 driver 默认行可永久删除 设计

> 日期：2026-06-17
> 模块：报价单编辑（Step2）/ driver 行渲染 + 快照 + 持久化
> 状态：设计待用户复审（方向已确认）

## 1. 背景与现状

报价单编辑页（`QuotationStep2`）每个页签的行分三类（渲染层 `QuotationStep2.tsx:2506-2527`）：
- `row._preset` 固定行 → 🔒 不可删；
- `isDriverBound` **基础数据自动展开行（driver 默认行）→ 🔗 不可删**（提示"请在基础数据导入侧调整"）；
- 手动新增行（`_origin='manual'`）→ ✕ 可删。

driver 默认行受「driver 行数权威」约束：渲染按 driver 展开 `rowCount` 出行，且对齐逻辑（`QuotationStep2.tsx:1467-1472`）把非手动行数对齐到 `rowCount`；后端 `CardSnapshotService.expandTemplateDriverBaseRows` / `refreshQuoteCardValues` 每次重展开 driver。因此**单纯放开删除按钮，删掉的行会在刷新/重算时被 driver 重新展开补回**（AP-51/AP-38 纪律）。

**用户需求（已确认）**：所有报价单的 driver 默认行允许用户自由删除，且**永久删除——刷新/重算后不回来**。

## 2. 现状关键链路（已核实）

- **行键 rowKey**：前端 `useCardSnapshots.computeRowKey` + `buildUniqueRowKeys`（撞键 #N 消歧）；后端 `FormulaCalculator.computeRowKey` + `uniquifyRowKeys`。两端同序同口径（raw key = rowKeyFields 值以 `||` 连接；全空 → 行号）。
- **driver 展开**：前端 `useDriverExpansions`；后端 `CardSnapshotService.expandTemplateDriverBaseRows`（调 `componentDriverService.expand` 每 driver 组件）。
- **行级数据持久化**：`quotation_line_component_data`（`row_data` 手动/输入层、`snapshot_rows` 基础冻结层）；报价侧渲染权威 = 行级 4 份值快照（`quote_card_values` 等）。
- **行数权威**：AP-51 —— `rowCount = expansion.rowCount > 0 ? expansion.rowCount : baseRows.length`，driver 优先。

## 3. 设计

### 3.1 核心机制：每页签持久化「已删行键集合」+ 先唯一化后过滤

- 新增持久化字段 **`deleted_row_keys`**（每 lineItem × component 一份，存被删 driver 行的**唯一化 effKey** 数组）。
- **过滤纪律（前后端统一，不可偏离）**：
  1. 对**完整** driver 展开集先做唯一化（`buildUniqueRowKeys` / `uniquifyRowKeys`，与现状同序同口径）；
  2. **再剔除** effKey ∈ `deleted_row_keys` 的行；
  3. 用过滤后的行集渲染 / 求值 / 落快照。
  - 唯一化对完整源集是确定性的、删除施加于其后 → 剩余行 #N 键稳定，删一行不致其他同名行键漂移。

### 3.2 数据模型
- Flyway 新迁移：`ALTER TABLE quotation_line_component_data ADD COLUMN deleted_row_keys jsonb NOT NULL DEFAULT '[]'`。
- 实体 `QuotationLineComponentData` 加 `public String deletedRowKeys = "[]";`（`@JdbcTypeCode(JSON)`）。
- 复制（`QuotationService.copy`）：`deleted_row_keys` 随页签数据一并拷贝（同模板）/ 换模板时按 tabName/componentId 配对迁移（与 row_data 同口径，配不上则 `[]`）。

### 3.3 删除动作（前端 Step2）
- 渲染层：driver 行（`isDriverBound`）的 🔗 改为 ✕「删除行」。`_preset` 固定行仍 🔒（不在本需求范围）。
- 点 ✕ → 计算该行的 uniquified effKey（复用渲染层已算出的 rowKey，AP-54 用对象引用映射真实行）→ 调新 mutator `handleDeleteDriverRow`：把该 effKey 追加进本组件 `deletedRowKeys`（去重）→ 触发 autosave/draft 持久化。
- 手动行删除：维持现状（从 `row_data` 物理删，不进 deletedRowKeys）。

### 3.4 前端过滤落点（协议传播）
- `useDriverExpansions`：展开结果对外暴露时，按所属组件的 `deletedRowKeys` 在「唯一化后」过滤；`rowCount` 改为过滤后的有效行数。
- `QuotationStep2.tsx`：
  - 行对齐（1467-1472）：非手动行数对齐到**过滤后**有效行数，不用裸 `exp.rowCount`；
  - 渲染逐行迭代：跳过被删 effKey；realRowIndex（AP-54）映射仍按对象引用回写；
  - rowCount=0 兜底（AP-38）：全删后该页签按 0 行渲染 "—" 兜底，不回退 globalPathCache。
- `useCardSnapshots`：baseRows/editRows/formulaResults 组装按过滤后行集。

### 3.5 后端过滤落点（协议传播）
- `CardSnapshotService.expandTemplateDriverBaseRows`：每组件展开后，唯一化 → 按该组件 `deleted_row_keys` 过滤 → 落 baseRows。
- `buildCardValues` / `buildResolvedRows` / `filterEditRowsToNewBaseRows` / `mergeRowDataInputsIntoEdits`：以过滤后 baseRows 为准（editRows 按 rowKey 对齐，被删行键自然不再有对应 base 行）。
- `FormulaCalculator.computeRows`：baseRows 已过滤；rawKeys/effKeys 在过滤后集合上计算（保持与前端同序）。
- Excel 视图取数（`ExcelViewService` / `CardEffectiveRows`）：按过滤后有效行。
- `ComponentDriverService.expand`：**不在此层过滤**（它是通用 driver 展开，不含报价单维度）；过滤统一在 CardSnapshotService 等"报价单上下文"层做，传入该 lineItem×component 的 deletedRowKeys。

### 3.6 行数权威（AP-51）
- 有效 `rowCount` = `uniquify(driver展开).length − 命中 deletedRowKeys 的数量`。
- 写 `snapshot_rows` / `computeTabSubtotal` / 持久化行数处一律用有效行数；禁止 `Math.max`。
- 自检：删行后刷新 3 次行数稳定（不回弹、不累加）。

## 4. 边界与决策
- **永久性**：删除登记墓碑（deletedRowKeys），跨刷新/重算/重进编辑都不回来。
- **基础数据漂移**：若底层基础资料（mat_* / 导入）自身增删导致源集顺序/内容变化，被删 effKey 可能匹配漂移（误删/漏删）——固有局限，本期接受，不做"按业务键+内容指纹"的强匹配。
- **撤销恢复**：本期不做"恢复已删行"UI（软删存了 key，未来可加"显示已删/恢复"）。
- **全删空页签**：允许；渲染按 0 行兜底 "—"（AP-38）。
- **范围**：所有报价单（不区分是否导入生成）；核价侧（costing card）editRows 恒空、行只读，**核价侧不开放删除**（仅报价侧 driver 行可删）；本期仅报价侧。
- **手动行**：行为不变。

## 5. 影响面 & 强制自检（协议级）
- Flyway 迁移成功（`success=t`）+ 实体加列编译。
- 触及 `useDriverExpansions.ts` / `QuotationStep2.tsx` / `useCardSnapshots.ts` / `CardSnapshotService.java` / `FormulaCalculator.java` / `QuotationService.copy` → 按 CLAUDE.md 强制跑 E2E `quotation-flow.spec.ts`（SIMPLE）+ `composite-product-flow.spec.ts`（COMPOSITE）。
- 必须验证：删 driver 行后 ① 该行消失 ② 刷新 3 次不回弹（行数稳定，AP-51）③ 列小计/金额按剩余行重算正确 ④ 全 Tab `'加载中'=0`（AP-31/38）⑤ 详情页（ReadonlyProductCard）渲染同样跳过被删行（AP-50 single-source）。
- PR 附：删除前/后该 Tab 截图 + 刷新 3 次行数证据 + E2E `1 passed` + `'加载中' final=0`。

## 6. 未决/待复审
- `deleted_row_keys` 是否需要后端校验上限（防异常超大数组）——暂不做。
- 详情页（只读视图 `ReadonlyProductCard`）是否也展示"已删"痕迹——本期不展示，直接按过滤后渲染（与报价侧渲染一致，守 AP-50）。
