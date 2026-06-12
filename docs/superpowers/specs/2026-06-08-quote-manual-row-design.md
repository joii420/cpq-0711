# 报价单手动新增行（除公式列全空白）— 设计方案

> 日期：2026-06-08 | 状态：设计待评审 | 范围：仅报价侧（QUOTE），核价侧不允许编辑
> 决策：driver 删除标记按 **driverRow 整行内容规范化指纹（hash）** 匹配 + B2（分两阶段交付）

## 1. 需求（已澄清，9 条）

1. 手动新增行：**除公式列外全空白** —— 不带 FIXED_VALUE 固定值、不解析 DATA_SOURCE、不回填 BASIC_DATA 默认值。
2. 仅作用于**手动新增行**；driver 展开行保持原样（照常带数据/固定值/默认值）。
3. 手动行 + 用户填值**持久化**：保存后再打开原样保留，不被 DRAFT 重算清掉。
4. **两类页签**（有 driver / 无 driver）都支持手动新增；有 driver 页签里 driver 行与手动行共存。
5. 手动行公式列算出的金额**计入页签小计与报价单总价**。
6. 手动行、driver 行**都能删**；driver 行删除需**持久化"已删除"标记**，重开不复活。
7. **编辑态 + 详情只读态**（`ReadonlyProductCard`）一致显示：手动行在、已删 driver 行不出现。
8. **只动报价侧**；核价侧不允许用户编辑，移除（或确认本就无）[新增行]入口。
9. 手动行输入方式：
   - FORMULA → 自动算，不可填
   - INPUT_TEXT / INPUT_NUMBER → 文本/数字输入框，手填
   - FIXED_VALUE → **可填文本框**
   - DATA_SOURCE → **保留下拉选择器，默认空**，由用户从数据源选

## 2. 核心难点与已知坑

报价 DRAFT 再次打开走 `CardSnapshotService.buildCardValues`：报价侧复用 `snapshot_rows`（driver 展开）重组 `baseRows/editRows`，"加产品时 editRows 恒空"。手动行不属于 driver，现架构会在 refresh 时丢弃它（即 RECORD[2026-06-08] 记的"DRAFT 重算覆盖"）。故必须**改 refresh 合并语义**。

涉及的已知反模式：
- **AP-51 行数纪律**：手动行 + driver 行共存，rowCount = driver 权威 + 手动行数，**禁 `Math.max`**。
- **AP-44 字段类型渲染协议**：渲染层新增"手动行分支"属协议级改动，需 grep 全工程 + E2E 双 spec。
- **AP-54 下标映射**：`QuotationStep2` 渲染用过滤子集下标、写回用原集合，新增/删除下标须按对象引用映射回真实下标。
- **AP-50 详情页 single-source**：编辑态与只读态须走同一合并后的 rows + 共享 ComponentCell。

## 3. 数据模型（持久化）

- **行来源标记**：每行加 `_origin: 'driver' | 'manual'`，随 `quotation_line_component_data.row_data`（JSONB）持久化。无标记的历史行默认按 driver 处理。
- **driver 行删除标记**：`quotation_line_component_data` 新增 JSONB 列 `deleted_driver_keys`（或 row_data 同级元数据），存"已删 driver 行业务键集合"。
- **删除标记匹配键（指纹方案）**：driver 行**没有天然统一的业务键**（`ExpandDriverResponse.Row` 只有 `driverRow` Map + `basicDataValues`，各组件视图列不同：BOM=component_no、工序=operation_no/seq_no、元素=element_code）。故删除标记用 **driverRow 整行内容的规范化指纹（hash）**（键排序后 JSON → hash），不依赖列名/配置。基础资料任一列变化（含改料号/价格波动）→ 指纹变 → 视为新行、旧标记失效、新行照常出现，符合"基础资料变化旧标记不复活"语义。
- 手动行直接写进 `row_data`（`_origin='manual'`），随草稿保存端点持久化。

## 4. 改造点

### 4.1 后端 `CardSnapshotService.buildCardValues`（核心，Phase 2 含删除合并）
重开合并：`最终行 = driver 重新展开行（过滤命中 deleted_driver_keys 的）+ row_data 中 _origin='manual' 的手动行（追加末尾，含用户填值）`。rowCount 按 driver 权威 + 手动行数（禁 Math.max）。

### 4.2 前端 `QuotationStep2.tsx`
- `handleAddRow`（:1091）：新增行只设 `_origin:'manual'` + 公式列不存值，**其余列一律空**（FIXED_VALUE 不带 content、DATA_SOURCE 不置自动解析、不进 BASIC_DATA 回填）。
- 快照回填 effect（:1145）、`computeTabSubtotal`、`driverExpansionKey`、FIXED_VALUE 默认值回填（:522/:1751）等：对 `_origin==='manual'` 行短路，不自动带值。
- 渲染层（单元格分支）：`_origin==='manual'` 行 → FIXED_VALUE 渲染可填文本框、DATA_SOURCE 渲染默认空下拉、INPUT 照常、FORMULA 照常自动算；driver 行渲染不变。
- `handleDeleteRow`（:1082）：手动行 → 从 rows 移除；driver 行 → 移除 + 把业务键写入 `deleted_driver_keys`（Phase 2）。
- 下标映射按 AP-54：新增/删除走对象引用映射回真实 `componentData` 下标。

### 4.3 详情只读态 `ReadonlyProductCard`
走同一份合并后的 rows（手动行在、已删 driver 行不在），按 AP-50 用共享 ComponentCell 渲染，手动行只读展示用户填的值。

### 4.4 核价侧
确认核价渲染组件无手动新增入口（现 `handleAddRow` 仅存在于报价 `QuotationStep2` 与模板预览 `ComponentTablePreview`，核价侧 editRows 恒空、无编辑端点）；若存在入口则移除。核价不受本改动影响。

## 5. 分阶段交付（决策 B2）

### Phase 1 — 手动行主诉求
- 行来源标记 `_origin`
- `handleAddRow` 改全空白 + 渲染层手动行分支（FIXED_VALUE 文本框 / DATA_SOURCE 空下拉 / 公式自动算）
- 手动行持久化（写 row_data）+ 后端 refresh **保留手动行**（追加末尾）
- 小计/总价含手动行（AP-51 行数纪律）
- 详情只读态显示手动行
- 手动行可删（直接移除）
- 核价侧确认/移除新增入口
- E2E：加手动行→填值→保存→重开仍在；小计含手动行

### Phase 2 — driver 行删除标记
- `deleted_driver_keys` 列 + driverRow 整行内容指纹（hash）匹配
- `handleDeleteRow` driver 行写删除标记
- 后端 refresh 合并时过滤已删 driver 行
- 详情只读态不显示已删 driver 行
- E2E：删 driver 行→重开不复活；基础资料改料号→旧标记失效新行出现

## 6. 验证（CLAUDE.md 强制）

协议级前端（QuotationStep2 / ReadonlyProductCard / 渲染分支）+ 后端 CardSnapshotService → 必跑 E2E 双 spec（`quotation-flow` + `composite-product-flow`），`'加载中' final count = 0`，8 Tab 截图；加上述各 Phase 专项 E2E。AP-44 矩阵 grep 命中输出随 PR。

## 7. 待实现时核实的点

- driver 行业务键在 `comp.rows`/`snapshot_rows` 中的确切字段名（料号列 key 因组件而异，需从 driverRow/basicDataValues 取稳定键）。
- `deleted_driver_keys` 落 row_data 元数据 vs 新增列：实现时按改动面最小择一（新增列更清晰、需 Flyway）。
- 保存端点（SaveDraftRequest）是否原样回传 `_origin`/手动行（前端 → 后端 row_data 透传链路确认）。
