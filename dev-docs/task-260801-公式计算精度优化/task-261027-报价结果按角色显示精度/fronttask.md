# 前端任务：报价结果按角色显示精度

## 结论

前端只在报价呈现边界应用当前角色的最大显示位数。销售代表、销售经理为 4；定价经理、系统管理员为 9。计算、草稿组装、快照、对账和 API decimal string 不变。

采用方案甲：认证状态只在报价 React 组件中以原始 `user?.role` 订阅，角色上限同步派生并显式传给格式化调用。通用 `formatNumber` 不读取全局 store，报价以外的调用方省略新参数后继续最多显示 9 位。

## 任务分解

| 编号 | 服务的 AC | 内容 |
|---|---|---|
| F-1 | AC-1、AC-2、AC-3、AC-6 | 增加纯函数 `quotationResultDisplayScale(role)` 与报价侧 Hook；四个既有角色严格映射为 4/9。扩展 `DecimalSpec` 支持可选最大显示位数：仅 `isComputed=true` 时取“字段显式位数、全局 9 位、角色上限”的最小值，继续 `HALF_UP`、去尾零；默认行为不变。 |
| F-2 | AC-1、AC-2、AC-4 | 报价列表、编辑向导 Step 2/Step 3、产品卡片、报价汇总、详情汇总和快照页签的计算值接入角色上限。`ComponentCell` 通过显式 prop 接收上限，编辑态与只读态共用同一口径。 |
| F-3 | AC-3、AC-4、AC-9 | 报价 Excel 视图的编辑态/只读态渲染接入上限；`renderCellValue` 显式接收上限。计算列含百分比展示时才受角色上限，产品属性、组件输入、汇率、税率、利润快照等原始值保持原显示。 |
| F-4 | AC-2、AC-3、AC-4、AC-6 | 比对视图由 `ComparisonBoard` 取得角色上限，显式传给 `ComparisonTable` 和 `comparisonMapping` 纯函数；差异值、报价值、核价值使用同一上限，阈值计算仍使用未格式化 decimal string。 |
| F-5 | AC-5 | 保持导出请求体和下载调用不新增角色参数，角色由服务端会话判定；前端不得把已显示的 4 位文本作为导出或邮件附件的数据源。 |
| F-6 | AC-7、AC-8 | 保存、快照和对账路径继续使用现有 `formatFormulaResult`、`formatProductCardSubtotal`、`formatQuotationTotal` 与 12 位工作值；角色切换只触发重渲染，不修改 React 业务 state、草稿 payload 或本地缓存。 |
| F-7 | AC-9、AC-10、AC-11 | 补纯函数、共享单元格、Excel/比对渲染与报价主链测试；执行类型检查、定向单测、生产构建和浏览器角色切换回归，确认报价外页面及权限行为无变化。 |

## 文件与调用边界

- 角色策略建议独立放在报价模块内；通用 `utils/formatNumber.ts` 只接受可选参数，不导入 `authStore`。
- 直接呈现入口至少覆盖：`QuotationList.tsx`、`QuotationWizard.tsx`、`QuotationStep2.tsx`、`QuotationStep3.tsx`、`ProductDetailViews.tsx`、`ReadonlyProductCard.tsx`、`components/ComponentCell.tsx`、`components/SnapshotTab.tsx`。
- Excel 入口至少覆盖：`LinkedExcelView.tsx`、`ReadonlyExcelView.tsx`、`ExcelView.tsx`、`excelCellFormat.tsx`。
- 比对入口至少覆盖：`ComparisonBoard.tsx`、`ComparisonTable.tsx`、`comparisonMapping.ts`。
- `QuotationPriceRevisionsDrawer` 的既有 2 位金额不因本任务扩大；`QuotationWizard` 的客户累计金额不是本报价计算结果，不套角色上限。
- `profitBadge.ts` 展示的是冻结的基础属性，不套角色上限；利润仍按自身最多 9 位契约显示。

## 精度与状态纪律

- 不修改 `CALCULATION_SCALE=12`、`DISPLAY_SCALE=9`、公式结果/产品小计/报价总额的保存边界常量。
- 不让 `formatFormulaResult`、`formatProductCardSubtotal`、`formatQuotationTotal` 读取角色；这些函数参与工作值或保存值生成，保持角色无关。
- 显式配置 2 位时四角色都为 2 位；配置 6 位时销售角色为 4 位、其他角色为 6 位。
- 原始值即使有 9 位以上小数，也不得因为登录角色变为销售而统一截到 4 位。
- 角色变化是同步派生值，不新增 effect、本地 state 或不必要的 memo；Hook 只订阅 `user?.role`，避免订阅整个认证对象导致无关重渲染。

## 原型判定

本任务不新增页面、列、控件、布局或交互状态，只替换既有数字文本的最大位数，不属于页面设计类改动，因此不新增 HTML 原型。视觉验收以现有页面布局不变、文本不溢出、加载占位归零和控制台无错误为准。

## 不做

- 不在通用格式化器中读取认证 store。
- 不修改报价以外的主数据、价格管理、选配、独立核价页面的显示精度。
- 不把税率、利润、汇率、用户输入或基础资料取数值视为计算结果。
- 不改变权限菜单、路由守卫、请求参数或保存 payload。
- 不用 JavaScript `number` 承接精度值。

## 前端自检

- `cd cpq-frontend && npx tsc -b`
- 执行本任务新增定向 Vitest/组件测试与既有 precision、formatNumber、ComponentCell、Excel、比对测试。
- 执行生产构建；浏览器分别以四角色打开同一私有报价，核对列表、编辑、详情、Excel、比对和连续切换。
- 浏览器验证 `加载中…` 计数最终为 0、无错误遮罩、`console.error` 为 0；保存前后抓取原始响应确认逐字不变。
