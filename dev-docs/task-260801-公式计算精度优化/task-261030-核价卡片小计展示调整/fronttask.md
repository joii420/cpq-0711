# 前端任务：核价卡片小计展示调整

## 结论

只改**核价侧**产品卡片底部金额区的最终呈现：编辑态（`QuotationStep2` COSTING 分支）与只读态（`ReadonlyProductCard` COSTING 分支）共用一个新的核价金额区呈现组件；VAT 规则复用 `task-261028` 的 `resolveVatRatio` / `calculateVatInclusiveSubtotal`，不写第二套。报价侧分支一行不动。

定稿布局见 `原型图/01-核价编辑态.html`、`原型图/02-核价只读与边界态.html`：左列 CNY、右列报价币种；每列上行税前、下行 `(VAT)` 含税；报价币种为 CNY 只留一列；没有核价数据只显示一列 `-` / `(VAT) -`。

## 任务分解

| 编号 | 服务的 AC | 内容 |
|---|---|---|
| F-1 | AC-1、AC-2、AC-3、AC-5、AC-8 | 新增共享核价金额区呈现组件（建议与 `QuotationVatSubtotal.tsx` 同目录，纯组件、无 hook / effect / 网络）。输入：核价小计（`DecimalValue \| null`，`null` = 没有核价数据）、报价币种、汇率、`vatRateRatio`、`vatRate`、`maxDisplayDecimals`。CNY 列恒有；报价币种（`trim` 后为空视作 `CNY`）不是 `CNY` 时追加外币列。外币税前 = CNY 税前 ÷ 汇率；外币含税 = CNY 含税 ÷ 汇率；汇率缺失或为 0 时沿用现行「按 1」兜底。全程 Decimal，先进 12 位工作值（`toCalculationString`）再按 `formatNumber(..., { isComputed: true, maxDisplayDecimals })` 显示，不经 JavaScript `number`。税率不合法时两列第二行都显示 `(VAT) -`。输入为 `null` 时只渲染一列 `-` / `(VAT) -`，不带币种。DOM 定位点按 `api.md §3`。 |
| F-2 | AC-1、AC-2、AC-3、AC-6、AC-7 | `QuotationStep2.tsx` 产品卡片底部 `cardSide === 'COSTING'` 分支改用 F-1 组件，删除「产品小计 / 外币小计」两行 JSX；核价侧不再走币种在前的 `formatCurrency`（若改完后已无调用方，一并删除）。`cardSide === 'QUOTE'` 分支、生成失败告警分支、「未绑定核价模板」整页提示保持原样。 |
| F-3 | AC-1、AC-3、AC-4、AC-6、AC-7 | `ReadonlyProductCard.tsx` 底部 `isCosting` 分支改用 F-1 组件。**卡片主体处于「加载组件结构...」「暂无组件数据」或「核价数据生成失败」时传 `null`**（判据与主体渲染分支同源：`loading`、`cardValueFailed`、`normalComponents.length === 0`），不得再落到 `computeProductSubtotal` 读报价行 `subtotal` 的兜底。删除只服务核价侧的 `¥` 版 `formatCurrency` 与 `foreignProductSubtotal`（外币换算移入 F-1）。`!isCosting` 报价分支保持原样。 |
| F-4 | AC-1、AC-3、AC-8 | `quotation.css` 新增核价金额区样式：两列 `flex-wrap` 右对齐，列内两行右对齐；放不下时第二列整列换到下一行，CNY 在上；首行字号与现行 `.qt-subtotal-value` 一致，`(VAT)` 行与报价侧 `.qt-subtotal-vat-*` 同级（前缀 opacity 0.82、金额 0.94）；**所有金额文字（含 `(VAT)` 前缀与 `-`）字色白色**，删除外币列现行的 `#1677ff` 内联色；数值 `white-space: nowrap`，超宽在本行内横向滚动。新类名收窄在核价金额区，不改报价侧与 `.qt-subtotal-row` 表格小计行。 |
| F-5 | AC-9 | 前端自检与原型逐屏比对（见下）。 |

## 建议文件边界

- 新增：核价金额区组件文件（或并入 `QuotationVatSubtotal.tsx`，以不扩大报价侧组件的 props 为准）。
- 修改：`QuotationStep2.tsx`（仅 COSTING 金额区分支 + 失去调用方的 `formatCurrency`）、`ReadonlyProductCard.tsx`（仅 COSTING 金额区分支 + 无数据判据 + 删除 `¥` 格式化）、`quotation.css`。
- 不改：`computeProductSubtotal` / `evalProductSubtotalFromSubtotals` 的取数与兜底语义（报价侧仍依赖它们），`costingLineItems` 组装逻辑，任何 service / API 调用。

## 计算与显示纪律

- 含税：`toCalculationString(toDecimal(subtotal).times(toDecimal('1').plus(ratio)))`，`ratio` 只能来自 `resolveVatRatio(vatRateRatio, vatRate)`。
- 外币：对 12 位工作值再 `dividedBy(rate)`，结果再进 12 位工作值后显示；夹具 C 实算 `241.055611185` / `272.392840639`（9 位）。
- 币种归一：`trim()` 后比较；空串按 `CNY`。报价币种不做大小写改写，原样显示。
- 角色上限只影响文本；派生值不写回 line item、React 业务 state、保存 payload、快照或缓存键。

## 原型 1:1 对照

- 📌 当前基准：`原型图/01-核价编辑态.html`、`原型图/02-核价只读与边界态.html`。
- 逐屏比对：编辑默认态、编辑 CNY 态、4 位角色、详情默认态、三种无数据态、VAT 0% / 缺失、长金额、390 宽窄屏；偏差逐条记入 `test-report.md`，只允许「组件库能力所限的等价实现」。

## 不做

- 报价侧金额区、`QuotationVatSubtotal` 的报价侧 props 与 DOM。
- 核价管理页「核价单据总价：¥…」、Excel 视图、比对视图、导出 / PDF / 邮件。
- 编辑页生成失败告警卡与「未绑定核价模板」提示。
- 任何新增表单字段、保存字段、API 字段或本地缓存键。

## 前端自检

- `cd cpq-frontend && npx tsc -b`
- 运行 `task261028-vat-subtotal.test.tsx`、`task261027RoleDisplayPrecision.test.ts`、`ComponentCell.readonlyPrecision.test.tsx` 及本任务新增用例。
- `npm run build`。
- 只在本 worktree 的临时 Vite 端口自看页面，不连他人服务；控制台 `error/warn`、「加载中」、错误遮罩计数写入回报。
