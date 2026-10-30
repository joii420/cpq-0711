# API 契约：核价卡片小计展示调整

## 1. 结论：HTTP / JSON 契约零变更

本任务不新增、删除或修改任何 HTTP 端点，不改请求方法、路径、参数、JSON 字段、字段类型、错误码或权限。测试完成后不回写 `dev-docs/main-api.md`，在 `test-report.md` 写明「本次无契约变更，无需回写 main-api.md」。

## 2. 现有输入契约（前端读取，语义不变）

| 值 | 前端读取处（立项时实查） | 本任务用途 |
|---|---|---|
| 报价币种 | 编辑：`quoteCurrency` prop；只读：`quotation.currency \|\| quotation.baseCurrency \|\| 'CNY'`（`ProductDetailViews.tsx:325`） | 判定是否显示外币列；外币列的币种缩写 |
| 汇率 | 编辑：`quoteExchangeRate` prop；只读：`quotation.exchangeRate \|\| '1'`（`ProductDetailViews.tsx:326`） | 外币 = CNY ÷ 汇率；缺失或 0 沿用「按 1」 |
| 核价卡片结构 | 只读：`quotation.costingCardStructure`（`ProductDetailViews.tsx:330`）；为空时核价卡片组件为空（`ReadonlyProductCard.tsx:296-301`） | 判定「没有核价数据」之一 |
| 核价卡片值 | `lineItem.costingCardValues`；生成失败标记 `__cardValueFailed` / `__errorMsg`（`ReadonlyProductCard.tsx:418-419`） | 判定「没有核价数据」之一 |
| `vatRateRatio` / `vatRate` | 报价行字段，`task-261028` 已使用 | 含税派生；规则同报价侧 |
| 报价行 `subtotal` | 报价侧产品小计（`quotation_line_item.subtotal`，该表唯一的小计列） | 🚫 **核价侧不得再用它兜底** |

> 真实字段名、类型、空值形态由 `B-1` 从后端 DTO 复核并落 `backend-audit.md`；测试 mock 夹具必须按该表造，不按本表的前端变量名猜。

## 3. 前端 DOM 契约（测试定位点）

核价金额区新增稳定定位点，报价侧既有定位点（`.qt-subtotal-value`、`.qt-subtotal-vat-prefix`、`.qt-subtotal-vat-value`、`.qt-subtotal-quote-lines`）不变：

| 定位点 | 含义 |
|---|---|
| `.qt-subtotal-costing` | 核价金额区根节点，每张核价卡片恰好 1 个；报价卡片 0 个 |
| `.qt-subtotal-costing-col` | 一列；`data-currency` 为该列币种缩写（`CNY` / `USD` / …），无数据列为 `data-currency=""` 且 `data-empty="true"` |
| `.qt-subtotal-costing-main` | 列内第一行（税前） |
| `.qt-subtotal-costing-vat` | 列内第二行；内含 `.qt-subtotal-vat-prefix`（文本 `(VAT)`）与 `.qt-subtotal-vat-value` |

列顺序：DOM 中 CNY 列在前、外币列在后。

## 4. 查询与保存契约不变

- 报价查询、草稿保存、卡片编辑、重算、快照、对账与提交的请求 / 响应不携带核价含税或外币派生值，不新增 `vatInclusive*` / `taxInclusive*` / `foreign*` / `display*` 字段。
- 销售角色看到的 4 位文本与其他角色的 9 位文本都不回写 API。

## 5. 错误、权限与降级

- 状态码、错误体、路由与操作权限不变。
- 没有核价数据、税率缺失 / 非法都不产生新请求或新错误码；金额区本地降级为 `-` / `(VAT) -`，卡片其他区域照常渲染。

## 6. AC 对应关系

| AC | 本契约落点 |
|---|---|
| AC-1、AC-2、AC-3、AC-5、AC-8 | §2 输入 + §3 DOM 契约 |
| AC-4 | §2「没有核价数据」两项判定 + 报价行 `subtotal` 禁用 |
| AC-6、AC-7 | §3 报价侧定位点不变；§4 保存契约不变 |
| AC-9 | 不改 API；构建与回归由前端 / 测试完成 |
