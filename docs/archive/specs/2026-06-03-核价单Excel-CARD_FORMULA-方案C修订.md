# 核价单 Excel CARD_FORMULA — 方案 C 修订设计（取数源更正）

> 🚫 **本方案已作废（2026-06-03）**，由 `2026-06-03-报价核价Excel-CARD_FORMULA取数统一-design.md` 取代。
> 作废原因：方案 C 的核心前提「合并行只能在前端算出」**不成立**——后端 `CardSnapshotService.buildCostingCardValues` 早已 expand 核价 driver 组件、产出带"类型"的核价 baseRows 并落库 `costing_card_values`。真因只是 `ExcelViewService.buildRowData` 算 CARD_FORMULA 时取数源取错（读了报价侧 `quotation_line_component_data` 而非同侧卡片快照有效行）。且这是**报价/核价共用渲染逻辑**，非核价专属。**勿按本文实现**（不要做"前端送行 + 新 evaluate 端点"）。

- 日期：2026-06-03
- 状态：**作废**（见上方）
- 取代：`2026-06-03-核价单Excel视图CARD_FORMULA修复-design.md` 里"读持久化 componentData + sortOrder 回退"的取数方式（**作废**）

## 0. 为什么之前的方案错了（已逐条核实）
1. 核价"元素"卡片数据**不持久化**到核价组件 id(`b3359f70`)下；持久化的只有**报价侧快照**(`d18ac7e4`)。
2. 报价/核价两个"元素"组件**各有自己的 `$ys_view`（同名不同义）**：
   - 核价 `b3359f70.ys_view` 有 `CASE … '非银点类' … end` → **产出"类型"列**；
   - 报价 `d18ac7e4.ys_view` **没有"类型"列**。
3. 所以"读持久化(报价快照) + sortOrder 回退"会把核价公式接到**报价 ys_view 的数据**上（无"类型"）→ 用户 B 列 `类型=='非银点类'` 永远 0。
4. 核价卡片显示的是**核价 `$ys_view` 实时驱动行 + 用户保存的覆盖**（用户可手改，见确认 B）。

**结论**：CARD_FORMULA 必须用**与核价卡片视图完全相同的合并行**，而那份行**只在前端算得出**（驱动展开缓存 + componentData 覆盖合并）。

## 1. 方案 C：前端送合并行，后端复用引擎算
**前端**把卡片"有效行"(effectiveRows) 连同列配置 POST 给后端；**后端**用现成 `CardFormulaEvaluator` 在**传入的行**上求值，返回每行列值。不读持久化、不在后端重算驱动、不复刻合并逻辑、不重复公式引擎。

### 1.1 合并行(effectiveRows)定义（与卡片渲染一致）
对某 lineItem 的某组件(tab)：
- 若有驱动(`data_driver_path`)且 `driverExpansion.rowCount>0`：第 i 行 = `{ ...driverExpansion.rows[i].basicDataValues, ...nonEmpty(comp.rows[i]) }`（驱动实时字段为底，用户覆盖在上）。
- 否则：用 `comp.rows`。
- 这与 `computeTabSubtotal`/ProductCard 的 effectiveRows 取值口径一致（QuotationStep2 现有逻辑）。

### 1.2 后端端点（扩展 dry-run 形态）
`POST /api/cpq/quotations/{id}/excel-view/evaluate`
```json
{
  "templateId": "<渲染模板id(报价/核价)>",
  "columns": [ <excel_view_config 列对象(含 CARD_FORMULA)> ],
  "lineItems": [
    { "lineItemId":"...", "partNo":"...",
      "tabs": [ { "tabKey":"<componentId:sortOrder>", "subtotal": <num>, "rows":[ {字段:值, ...}, ... ] } ] }
  ]
}
```
返回 `{ columns, rows:[ {<col_key>:值, _lineItemId } ] }`（与 getExcelView 同形态）。

**后端实现**：新增 `ExcelViewService.evaluateWithRows(templateId, columns, lineItemsPayload)`：
- 对每个 payload lineItem：用其 `tabs` 构造一个 `CardDataProvider`（直接吃传入的 rows/subtotal，按 `tabKey` 索引）；
- 调 `CardFormulaEvaluator.evaluateColumns(columns, <由 tabs 构造的轻量 tabs>, customerId, partNo, ...)`；
- 注：CardDataProvider 现按 `QuotationLineComponentData` 构造；需加一个**从 payload 直接构造**的入口（`CardDataProvider.fromTabs(List<{tabKey,rows,subtotal}>)`），或让 evaluateColumns 接受已构造的 provider。
- **keying 天然正确**：payload 的 tabKey 用渲染模板组件 id（核价 `b3359f70:2`），CARD_FORMULA 引用也是 `b3359f70:2` → 精确命中，**不需要 sortOrder 回退**。

### 1.3 前端
`useBackendExcelRows`（新模型分支）改为：
- 不再 GET `getExcelView`；而是**组装 payload**（遍历本视图 lineItems 的各 tab，按 §1.1 合成 effectiveRows + subtotal + tabKey），POST `/excel-view/evaluate`，渲染返回的列值。
- 组装所需输入：lineItems（quote 或 costing 视图各自的）、对应的 driverExpansions（`driverExpansions`/`driverExpansionsCosting`）、各组件 `data_driver_path`/`fields`（算 driverExpansionKey）。
- 报价视图、核价视图**统一走这条路**（各自传各自的 lineItems + 模板 id + 驱动展开）。

## 2. 保留 / 返工
| 已交付 | 处理 |
|---|---|
| getExcelView 加 templateId 覆盖(Task2) | 保留（evaluate 端点也按 templateId 取模板公式上下文）；getExcelView 本身可留作兼容 |
| 公式校验强化(Task3)、试算按钮(Task6) | 保留（试算改调 evaluate 端点、带当前卡片行） |
| dry-run 端点(Task4) | **并入/升级**为 `evaluate`（带 rows）；或 dry-run 复用 evaluate |
| 前端 useBackendExcelRows/LinkedExcelView 透传 templateId(Task5) | 保留并扩展为"组装 rows + POST evaluate" |
| CardDataProvider sortOrder 回退(Task1) | **降级为兜底**（方案 C 下 tabKey 精确命中，回退不再是主路径；保留无害） |
| buildRowData 读持久化算 CARD_FORMULA | 核价视图不再用它取数（evaluate 端点用 payload rows）；getExcelView 旧路径保留兼容报价旧模型 |

## 3. 验收
- 后端单测：`CardDataProvider.fromTabs(payload)` + `evaluateWithRows`（喂含"类型"的元素行 → B 按 `类型=='非银点类'` 命中、A=含量合计、C=A+B）。
- E2E（非破坏）：核价单 Excel A/B/C 出值，且**值与核价卡片显示一致**（同源）；报价单 Excel 仍正确（回归 QT-1497=515.5632）。
- 一致性核对：核价 Excel 某列值 == 核价卡片该页签按同公式手算值。

## 4. 风险
- payload 体积（lineItems × tabs × rows）：常规报价单规模可接受；超大单可分批。
- effectiveRows 组装必须与卡片渲染口径**逐字段一致**（同 basicDataValues 键名 + 覆盖优先级）；抽成共享工具，避免与 ProductCard 渲染分叉（参照 computeTabSubtotal 的 effectiveRows）。
- 统一两视图走 evaluate 后，**必须回归报价侧 E2E**(QT-1497) 防破坏既有正确行为。
