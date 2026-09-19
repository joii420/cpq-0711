# api · repair-260918 报价 Excel 视图后端重算改读卡片值

## 总述

- **不新增、不删除端点；方法 / 路径 / 请求参数 / 响应结构全部不变。**
- 变的是下列端点**报价侧**返回值的**取数来源**（`row_data` → 该行 `quote_card_values`），以及「正式账不可用的行」的取值（数字 → `null`）。
- 「正式账不可用」口径（全部端点同一口径，`backtask.md` B-1）：`quote_card_values` 为 `null` / 空白；JSON 解析失败；含 `"__cardValueFailed": true`；`tabs` 缺失、非数组或长度为 0。
- 核价侧（`templateId` 为本单核价模板时的 `/excel-view`、`costingExcelValues` 补算）**不变**。

## 受影响端点

### `GET /api/cpq/quotations/{id}/excel-view`

- 响应结构不变：`{columns:[…], rows:[{col_key:value, …, "_lineItemId": "…"}]}`。
- 报价侧：`source_type` 为 `TAB_JOIN_FORMULA` / `CARD_FORMULA` 的列值按该行正式账求值；该行正式账不可用 → 这两类列值为 `null`（其余列类型不变）。
- 不写库（与改前一致）。

### `POST /api/cpq/quotations/{id}/excel-view/dry-run`

- 请求体不变：`{templateId?, columns:[…]}`；`templateId` 只用于取模板公式。
- 行值取数同上；不可用行 → 两类列 `null`。

### `GET /api/cpq/quotations/{id}/export-excel-view`

- 不变：优先取 `quote_excel_values`；该行存值为空时整行用 `/excel-view` 的行兜底 —— 兜底值随上一条变为「按正式账」；正式账也不可用时兜底单元格为空。

### `POST /api/cpq/templates/{id}/excel-view-config/dry-run-tab-formula` 与 `POST /api/cpq/components/{id}/dry-run`

- 请求体不变：`{lineItemId, column, cardValuesJson?}`。
- 取数优先级：入参 `cardValuesJson` 可用 → 用它；否则用该行 `quote_card_values`；都不可用 →
  ```json
  {"value": null, "errors": ["样本卡片的卡片值不可用（尚未计算或计算失败），无法试算"]}
  ```
  （**新增错误文案，逐字固定**；HTTP 200，与既有「求值异常」同一返回形态）
- 改前：未传 `cardValuesJson` 时读组件数据（`CardDataProvider(cdList)`），立项实测 0881 三列均返回 `"0"`。

### `POST /api/cpq/quotations/{id}/ensure-excel-values`（以及 `POST /{id}/submit` 内部的同一补算）

- 响应结构不变。
- 报价侧：对「`quote_excel_values` 为 NULL 且正式账不可用」的行**不写值**（保持 NULL，待下次补算）；改前会按 `row_data` 算出错值写入。

## `main-api.md` 回写

- 需回写：上述端点在总账中的说明补一句「报价侧按该行 `quote_card_values` 求值，正式账不可用时相关列为 `null` / 不写值」，以及 `dry-run-tab-formula` 的新错误文案。
- 时机：测试完成、合并 master 之前（`task-docs.md §2.5`）。
