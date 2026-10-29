# 前端任务：报价卡片含税总计展示

## 结论

只在报价侧产品卡片的最终呈现边界新增一个不落库的含税派生值。编辑态与只读态复用同一个纯 Decimal 计算函数和同一个呈现组件，不复制两套 VAT 逻辑。

定稿布局：第一行仅显示现有税前金额，第二行显示 `(VAT) 含税金额`；不显示“产品小计”“税前价格”“税后价格”。

## 任务分解

| 编号 | 服务的 AC | 内容 |
|---|---|---|
| F-1 | AC-1、AC-2、AC-4、AC-5 | 新增共享纯函数：输入产品小计、`vatRateRatio` / `vatRate`、币种与 `maxDisplayDecimals`，用 Decimal 算 `subtotal × (1 + ratio)`，进入现有 12 位工作值边界后再按角色 4/9 位显示。`vatRateRatio` 存在时优先；仅有 `vatRate` 时除以 100；缺失/非法/越界返回空结果供 UI 显示 `-`。不经 JavaScript `number`。 |
| F-2 | AC-1、AC-2、AC-3、AC-4、AC-5 | 新增共享报价侧总计呈现组件，第一行保留 `.qt-subtotal-value` 作为既有税前定位点，第二行增加独立 VAT 前缀/金额定位点。`QuotationStep2` 报价分支和 `ReadonlyProductCard` 报价分支同时复用，不把派生值写入 line item、React 业务 state 或保存 payload。 |
| F-3 | AC-1、AC-6、AC-7 | 调整 `quotation.css` 的报价侧总计行样式：两行右对齐，第二行字号较小，`(VAT)` 与金额同行，长值局部滚动/不拆词。核价分支现有类名与 CNY/外币两行结构不变。 |
| F-4 | AC-1~AC-8 | 补纯函数 Vitest、`renderToStaticMarkup` 组件契约测试和任务专项 Playwright。实测编辑/详情连续导航、无关文本保存、核价反向、1280/390 视口和错误遮罩/控制台计数。 |

## 建议文件边界

- 共享计算/呈现：在 `cpq-frontend/src/pages/quotation/` 下新增一个轻量模块（可命名为 `QuotationVatSubtotal.tsx`），不依赖 `QuotationStep2` 重组件。
- 接入：`QuotationStep2.tsx` 只改 `cardSide === 'QUOTE'` 总计分支；`ReadonlyProductCard.tsx` 只改 `!isCosting` 总计分支。
- 样式：`quotation.css` 新类名必须收窄到报价 VAT 两行，不改 `.qt-subtotal-row` 表格小计。
- 测试：共享模块定向测试放同目录；E2E 使用独立任务名与数据前缀，不改已知失败的 `quotation-flow.spec.ts` 来制造假绿。

## 计算与显示纪律

- 含税工作值：`toCalculationString(toDecimal(subtotal).times(toDecimal('1').plus(ratio)))`；展示继续走 `formatNumber(..., { isComputed: true, maxDisplayDecimals })`。
- ratio 的合法范围是 `0..1`（含边界）；字符串无法解析、超出范围或缺失时第二行显示 `-`，不回查产品主数据。
- 如果 `vatRateRatio` 非空但非法，不用 `vatRate` 掩盖损坏的权威快照；只有 ratio 缺失时才用百分比字段回退。
- 币种字符串沿用既有卡片值；不作汇率换算。
- 角色上限只影响文本；新派生值不参与报价总额、折扣、对账、快照或导出。

## 原型 1:1 对照

- 📌 当前基准：`05-定稿候选-编辑态.html`、`06-定稿候选-只读与边界态.html`。
- 实现必须逐屏比对：桌面编辑态、桌面详情态、390 px 窄屏、0% VAT、缺失/非法 VAT、长金额。
- 原方案甲/乙仅作否决历史记录，不得照其中任何“税前价格/税后价格”文案实现。

## 不做

- 不改报价总额、导出、Excel 视图、比对视图、邮件或 PDF。
- 不新增表单字段、保存字段、API 字段或本地缓存键。
- 不修改核价侧总计文案与布局。
- 不回算历史报价的税率或产品小计。

## 前端自检

- `cd cpq-frontend && npx tsc -b`
- 运行新增 VAT 总计纯函数/组件 Vitest，以及相关精度/产品小计测试。
- `npm run build`（以实际 package script 为准）。
- 在临时端口的本 worktree 栈运行任务专项 Playwright，不连用户验收服务或其他会话的进程。
- 对照原型截图，记录差异为 0 或明确的有意偏差；控制台 `error/warn`、“加载中”和错误遮罩计数必须入证据。
