# api · repair-260916 接口与模块契约

## 结论：HTTP 接口零改动；前端两个公式入口签名不变、语义收紧

`main-api.md`：**本次无 HTTP 契约变更，无需回写**（`task-docs.md` §2.5）。

## 1. HTTP 接口（全部不变，列出本任务用到的）

| 方法 | 路径 | 本任务用途 | 变化 |
|---|---|---|---|
| POST | `/api/cpq/quotations/{id}/copy` | 测试造副本 Q′（请求体 `{}` = 同模板复制，整份继承值快照） | 无 |
| DELETE | `/api/cpq/quotations/{id}` | 测试回收自己建的 Q′ | 无 |
| **PUT** | `/api/cpq/quotations/line-items/{lineItemId}/quote-card-edit` | 页面编辑单元格（请求体 `{componentId,rowKey,fieldName,value}`，值为字符串）；响应带 `quoteCardValues` / `quoteExcelValues` / `quoteValuesAt`。📝 2026-09-16 更正：原写 POST，实测与代码均为 PUT（`QuotationResource` 注解、`quotationService.editQuoteCardValue` 用 `api.put`） | 无 |
| POST | `/api/cpq/quotations/line-items/{lineItemId}/reconcile-report` | 前端对账上报（AC-2 阳性对照时可观察） | 无 |
| PUT | `/api/cpq/quotations/{id}/draft` | 存草稿（编辑页「保存草稿」按钮触发）；请求体的行数组是 **`added[]` / `modified[]`**（📝 2026-09-16 更正：原写 `lineItems[]`，S-E 实测），每行 `componentData[].rowData` 是 **JSON 字符串**（需再解析一次），`quoteExcelValues` 也是 JSON 字符串；AC-12 抓该请求体 | 无 |
| GET | `/api/cpq/quotations/{id}/export-excel-view` | AC-6 下载导出文件（xlsx）。📝 2026-09-16 更正：表头是**列键** `col_1` / `col_2` / `col_3`（该模板 Excel 列配置只有 `title` 没有 `label`，导出取 `label`，缺省回落列键），分别对应「材料成本」「回收价格」「产品单价」 | 无 |

## 2. 前端模块契约（`cpq-frontend/src/pages/quotation/QuotationStep2.tsx`）

签名不变（测试可直接 import）：

```ts
export function computeAllFormulas(
  comp: ComponentDataItem, row: Record<string, any>,
  allComponentSubtotals?, quotationFields?, pathCache?, partNo?: string,
  basicDataValues?: Record<string, any>, previousRowSubtotal?, globalVariableDefs?,
  crossTabRows?: Record<string, Array<Record<string, any>>>,
  previousRowValues?, out?: { fieldValues?; errors?: Record<string, string> },
): Record<string, DecimalString | null>

export function computeTabFormulasTree(
  comp: ComponentDataItem,
  rows: Array<{ row: Record<string, any>; basicDataValues?; nodeId?; parentId?; lvl? }>,
  allComponentSubtotals?, quotationFields?, pathCache?, partNo?: string,
  globalVariableDefs?, crossTabRows?, outBag?,
): Record<number, Record<string, DecimalString | null>>
```

**语义收紧（本任务新增的契约）**：

| # | 契约 | 修复前 | 修复后 |
|---|---|---|---|
| C-1 | 入参 `row` 里**本组件 `FORMULA` 字段的键**不参与求值（`b_field` 引用、跨页签匹配、条件公式取值） | 键在就用（值可能是上次保存的旧结果） | 忽略，一律用本轮算出的值 |
| C-2 | 入参 `row` 里**非公式字段**的键 | 优先使用；`''` 视为显式清空 | 不变 |
| C-3 | 入参 `row` 对象本身 | 不被修改 | 不被修改（键集合与值调用前后相同） |
| C-4 | 返回值、`out.errors`（细项多命中等错误原因） | — | 结构不变 |

`allComponentSubtotals` 的键约定（测试造数用）：`<componentCode>#<列名>`，例 `COMP-0001#税率`、`COMP-0004#加工费`。
`crossTabRows` 的键：源组件 `componentId` 与 `componentCode` 各登记一份，值为按**字段名**铺开的行数组。

## 3. 与后端的对称关系（不改，供理解）

后端 `FormulaCalculator` 的「本行原始数据」本就不含公式结果，C-1 让前端与之对齐。
