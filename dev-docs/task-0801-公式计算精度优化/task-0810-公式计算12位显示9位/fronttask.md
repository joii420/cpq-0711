# fronttask.md · 公式计算 12 位、显示 9 位

> 前端实现输入。以同目录 `需求文档.md` 和 `api.md` 为准；任何类型/接口调整先更新文档再编码。

## 1. 页面与模块清单

| 范围 | 主要文件 | 责任 |
|---|---|---|
| 精度基础设施 | `src/utils/precision.ts`, `formatNumber.ts` | 12 位工作值、9 位显示、规范十进制字符串 |
| 公式引擎 | `src/utils/formulaEngine.ts`, `formulaGolden.test.ts` | Decimal 上下文、节点输出、BL-0160 |
| 报价卡片 | `QuotationStep2.tsx`, `tabTotalLines.ts`, `crossTabOrder.ts` | 行公式、列小计、页签合计、产品小计 |
| 汇总与折扣 | `QuotationStep3.tsx`, `lineDiscount.ts`, `QuotationWizard.tsx` | 产品小计以上全程 Decimal |
| 快照 | `useCardSnapshots.ts`, `useExcelSnapshotRows.ts`, `buildExcelSnapshot*`, `snapshotRows*` | 历史 numeric token 无损解析，新写 decimal string |
| 显示 | `ReadonlyProductCard.tsx`, `QuotationList.tsx`, `ProductDetailViews.tsx`, `SnapshotTab.tsx`, `CostingSummaryDetailPage.tsx`, `ExcelView.tsx` | 统一最多 9 位 |
| API 类型 | `src/services/quotationService.ts` 及核价/公式 service | 精度字段请求/响应均为 `DecimalString`，业务态使用 `Decimal` |
| 导出触发 | 报价导出/邮件调用点 | 不在前端二次格式化工作值 |

无新页面、无新交互、无原型。

## 2. 类型与精度基础设施

### 2.1 类型

新增集中定义，禁止各页面重复声明：

```ts
export type DecimalString = string;
export type DecimalValue = DecimalString | Decimal;
```

规范字符串要求：普通十进制、无科学计数法、去尾零、`-0` 归 `0`。空值不允许通过 `"0"` 冒充。

### 2.2 `precision.ts`

- `CALCULATION_SCALE = 12`
- `DISPLAY_SCALE = 9`
- `DIVISION_SCALE` 兼容别名指向 `CALCULATION_SCALE`，逐步消除双常量漂移。
- `roundToCalculation(value): Decimal`
- `toCalculationString(value): DecimalString`
- `formatDisplayDecimal(value): DecimalString`
- `sumDecimal` 保持返回 Decimal。
- 删除/停用会在计算链中返回 `number` 的 `roundToDisplay()` 用法。
- 除法在 12 位 HALF_UP 收口；加减乘不逐操作 round。

### 2.3 `formatNumber.ts`

- 计算列默认最多 9 位。
- 显式 decimals 小于 9 时尊重配置；大于 9 时计算列封顶 9。
- 原始/取数列未配位数仍显示原精度。
- 百分比现有业务位数不变。
- 输入支持 `DecimalValue`，禁止 `number` 重载和 `Number(value)`。

## 3. 公式与聚合链路

### 3.1 `formulaEngine.ts`

- `evaluateExpression`、`evaluateListFormulaString` 和内部聚合不再以 `number` 作为结果契约。
- field、component subtotal、previous-row、product attribute、quotation field、path/global variable、cross-tab 值统一通过 Decimal 转换入口。
- SUM/AVG/MAX/MIN、KSUM/KAVG/KMAX/KMIN 全程 Decimal。
- 每个公式节点输出调用 `toCalculationString`，后续节点从字符串构造 Decimal。
- 除零、非法表达式、null/空值保持父任务语义。
- 修复 BL-0160：`component_subtotal` 同时识别 `component_code#__amount_total__`、`tab_name#__amount_total__` 和既有回退键。

### 3.2 报价卡片与跨页签

- `QuotationStep2` 的 fieldValues、componentSubtotals、columnSums、resolvedRows 由 `DecimalValue` 承载，类型定义中不得出现 `| number`。
- `tabTotalLines.ts` 返回规范字符串或 Decimal，不得 `.toNumber()`。
- `sumColumnsCanonical`、`subtotalsFromResolvedRows`、`computeTabSubtotal`、`getComponentSubtotals` 复用同一 Decimal helper。
- 对账比较按 Decimal 差值，不用 `Math.abs(number)`；阈值为 `10^-12` 的工作值比较，不以 9 位显示相同作为相等。

### 3.3 产品小计以上链路

- `lineDiscount.ts` 的单价、折后价、折扣金额、行总额返回 decimal string。
- `QuotationStep3` 的整单原价、折扣额、总额只在 JSX 处格式化。
- `QuotationWizard` 保存草稿时不调用显示 round；以 `normalizeDraftPayloadDecimals` 统一输出 decimal string，不保留 `normalizeDraftPayloadNumbers` 浮点兼容旁路。
- localStorage 草稿保留 decimal string，不通过 JSON 克隆把 Decimal 变 number。

## 4. 快照与 API 适配

### 4.1 无损兼容与新写

- 新增成熟的无损 JSON parser（优先 `lossless-json`）；历史 numeric token 必须从原始字面量直接变为 DecimalString/Decimal，禁止普通 `JSON.parse` 生成 JS `number` 后再补救。
- 新生成的 `formulaResults`、`resolvedRows`、`subtotalByColumn`、Excel 计算列、金额汇总值写 decimal string。
- BASIC_DATA/INPUT 数值进入计算前使用其原始文本构造 Decimal；不得全量 `parseFloat`。
- JSON 序列化使用普通 `JSON.stringify` 即可，因为权威 decimal 已是字符串；禁止二次转 number。

### 4.2 Service 类型

- top-level 金额字段、行金额字段、核价金额、公式求值结果只声明为 `DecimalString`；进入计算时构造 `Decimal`。
- 请求 DTO 的精度字段使用 `DecimalString`，发出前统一规范化；禁止发送 JSON number。
- 结构整数（页码、sortOrder、计数、HTTP 状态）保持 number。
- 结构性整数 `number` 不属于精度值，但不得流入公式、金额、数量、费率、汇总或差异计算。
- 不全局修改 Axios；本任务在 quotation/costing/formula service 和嵌套快照层显式保证 decimal string/无损解析。

## 5. 显示与导出调用

- `QuotationStep2`、`ReadonlyProductCard`、`QuotationList`、`ProductDetailViews`、`SnapshotTab`、`CostingSummaryDetailPage`、`ExcelView` 全部调用 `formatNumber`。
- 不允许 `toLocaleString()`、`toFixed(9)`、`Number(...).toString()` 自持精度规则。
- 空值仍显示 `—`，业务定义为零的值显示 `0`。
- 前端只提交导出参数，导出数值文本由后端 9 位策略生成。

## 6. 状态、缓存与兼容边界

- 不新增缓存 key，不改变 driverExpansion/cache 的业务维度。
- 快照指纹和草稿去重必须对 decimal string 做规范化后比较，避免 `1.0` 与 `1` 造成假 dirty。
- 老快照 numeric token 由无损 parser 直接规范化为 DecimalString/Decimal；任何精度值进入 React state 时不得是 JS `number`，非 DRAFT 不得触发保存。
- DRAFT 显式保存时允许转换为新 string 格式，这是预期迁移。
- AP-50：编辑、详情、核价三视图不得各自实现解析/格式化。
- AP-57：footer 使用现有 columnSums 权威源，不新增独立 reduce。
- AP-59：报价 Excel 继续由前端计算并随 saveDraft 写入；后端不接管第二套报价 Excel 计算。

## 7. 接口对应

- `API-P1`：报价列表/详情/草稿/重算/编辑/快照类接口。
- `API-P2`：公式单算与批算。
- `API-P3`：Excel 视图与导出。
- `API-P4`：核价、比较、价格调整派生金额。

具体方法、路径和字段见 `api.md`，前端不得自行恢复 JSON number。

## 8. 自检项

- `npm test -- --run` 或项目当前 `npm test`：精度、格式化、公式、快照、折扣、对账用例全绿。
- `npx tsc --noEmit -p tsconfig.json` 0 错误；若根配置只做引用，补跑实际 app tsconfig。
- 逐个改动 `.tsx/.ts` 通过 Vite 模块请求，响应 200 且不是 SPA fallback。
- 搜索受影响链路的 `.toNumber()`、`Number(`、`parseFloat(`、`toFixed(` 和精度类型的 `| number`；精度链路必须清零，结构整数逐处说明用途。
- Playwright 覆盖保存、刷新、三视图和大金额 12 位样本，最终“加载中”计数 0。

## 9. Task 列表

- [ ] F1 新增 DecimalValue/DecimalString 与规范化 helper，精度类型禁止 `number`。
- [ ] F2 拆分 12 位计算和 9 位显示常量/函数，更新单测。
- [ ] F3 公式引擎与 LIST_FORMULA 全程 Decimal，修复 BL-0160。
- [ ] F4 行公式、跨页签、列/页签/产品小计切换 Decimal 上下文。
- [ ] F5 折扣、年用量、整单总额和对账比较消除 number 权威值。
- [ ] F6 引入无损 JSON parser，历史 numeric token 直转 Decimal，新写 decimal string。
- [ ] F7 quotation/costing/formula service 类型与请求规范化。
- [ ] F8 报价、核价、详情、列表、快照、Excel 显示统一 9 位。
- [ ] F9 更新共享黄金、单测和确定性 fixture。
- [ ] F10 完成 TypeScript、Vitest、Vite、Playwright 自检并提交精确文件清单。
