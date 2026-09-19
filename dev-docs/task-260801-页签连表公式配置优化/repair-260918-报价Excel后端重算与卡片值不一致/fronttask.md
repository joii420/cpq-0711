# fronttask · repair-260918 报价 Excel 视图后端重算改读卡片值

## 结论：前端零改动，本任务无 `F-x`

按 `CLAUDE.md §4.0` / `subagents.md §1 ②`：前端这一路**没有任务**（不是免派），故本任务不派前端工程师。

## 为什么不改（判定依据，立项期逐条实查）

| 前端消费点 | 现在怎么用后端这组值 | 本次后端改动对它的影响 |
|---|---|---|
| 编辑页 Excel 视图 `LinkedExcelView.tsx`（报价侧 `side="QUOTE"`，`QuotationStep2.tsx:4796`） | 经 `useBackendExcelRows` 调 `GET /quotations/{id}/excel-view`，**只取 `columns`（列定义）**；行值由前端 `buildExcelSnapshot` 自算（`LinkedExcelView.tsx:193` 的选择分支：报价侧且 `frontendRows != null` 时用前端行） | `columns` 结构不变 ⇒ 无影响。后端 `rows` 值变了，前端本就丢弃 |
| 编辑页 Excel 视图（核价侧 `side="COSTING"`，`QuotationStep2.tsx:4717`） | 取后端 `rows` 显示 | 核价侧后端分支不动（`backtask.md` 总原则 3）⇒ 无影响 |
| 切到 Excel 视图前的补算 `quotationService.ensureExcelValues`（`QuotationStep2.tsx:4607`） | 调后端补存值 | 后端对「正式账不可用的行」改为不写值（B-5）；前端拿到的是 `getById` 返回的整单 DTO，缺值行照旧由前端实时算显示 ⇒ 显示无变化 |
| `quotationService.exportExcelView` / `dryRunExcelView` / `updateExcelViewCell` | **前端无任何调用方**（只有服务层定义；`CardFormulaDrawer` 未被任何页面挂载） | — |
| `buildExcelSnapshot` / 前端公式引擎 | 与后端无关 | 不动 |

## 回归确认清单（由测试片 S3 与主线亲验覆盖，见 `test.md`）

- `QT-20260916-0881` 编辑页 → Excel 视图：列标题「材料成本 / 回收价格 / 产品单价」仍在，数值与改前一致（AC-9）
- 切走再切回、刷新页面后仍一致，且打开过程不改写库里的存值与正式账（AC-9）

## 二期触发条件（出现以下任一，前端才需要介入，另立项）

- 要给「Excel 视图导出」（`/export-excel-view`）或 Excel 列试算抽屉（`CardFormulaDrawer`）加页面入口；
- 报价侧 Excel 视图改为显示后端 `rows`（即退回「后端为列值权威」，需先改 `三大核心模块基线.md §4.7`）。
