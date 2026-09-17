# backtask · repair-260916 后端任务分解

## 结论：后端零改动，本任务不派后端工程师

> 按 `docs/rules/subagents.md` §1②：判据是「这一端有没有 `B-x`」。本任务后端**没有任务**，不是「有任务但判断不必派」。

## 为什么不改（逐条对应 AC）

| AC | 后端涉及点 | 为什么不用改 |
|---|---|---|
| AC-1 / AC-3 / AC-4 / AC-5 | `FormulaCalculator` 的 `b_field` 取值、`buildRowEvalCtx` | 后端「本行原始数据」`currentRowRaw = driverRow + editValues`（+ INPUT default_source 补值），**不含公式结果** ⇒ `b_field` 引用公式列时键缺失、回落本轮算出值。立项期实测：本单 6 行 × 8 公式列的后端值即正确值（证据 §3.1 对照组逐格一致） |
| AC-11a | `FormulaCalculator#selectConditionalExpr` | 取值顺序：树属性 → `currentRowRaw` → BASIC_DATA 原值 → `fieldValues`；公式列不在 `currentRowRaw` 里 ⇒ 实际取到的就是本轮算出值，与前端修复后一致 |
| AC-2 | `POST /quotations/line-items/{id}/reconcile-report`、`ReconcileDiffStore` | 前端修复后不再产生差异；上报链路本身的问题登记为 `BL-0299`，不在本次 |
| AC-6 | `GET /quotations/{id}/export-excel-view`（`ExcelViewService#exportExcelView`） | 有前端快照时按快照输出；快照由前端存草稿时算，前端修复即修正 |
| AC-6（页面 Excel 视图） | `ExcelViewService#getExcelView` → `ComponentDataEffectiveRows.compute` | 报价侧按 `row_data` 现算；本单物化值为 0 属 `BL-0298`，**本次明确不修**（`问题说明.md` ⑤「本期明确不做」） |
| AC-12 | `QuotationResource#saveDraft` / `RowDataMaterializer` | 存草稿接收前端 `rowData` 的既有契约不变 |
| AC-8 / AC-9 / AC-10 | 核价侧渲染、详情页取数 | 草稿单详情页走前端引擎；非草稿读后端快照，后端未改 |

## 回归确认清单（由测试 S-E 片与主线亲验覆盖，后端不写代码）

- [ ] `git diff --stat master -- cpq-backend` 为空
- [ ] 共享后端 `8081` 在测试期间不重启（前端改动不触发它热重载）

## 二期触发条件

- 后端写时算齐的公式列物化值与卡片值不一致 → `BL-0298`
- 前端对账差异似乎从未到达后端、提交闸门可能不生效 → `BL-0299`
