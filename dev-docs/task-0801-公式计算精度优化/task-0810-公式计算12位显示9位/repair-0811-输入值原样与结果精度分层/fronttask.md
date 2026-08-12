# 前端任务

## 1. 改动范围

- `src/utils/precision.ts`：新增 `FORMULA_RESULT_SCALE`、`PRODUCT_CARD_SUBTOTAL_SCALE`、`QUOTATION_TOTAL_SCALE`；保留 `CALCULATION_SCALE=12`。三个结果常量当前均为 9，但不得互相引用；注释标明未来从系统参数覆盖。
- `src/utils/losslessJson.ts`：修正 `项次/_项次` 乱码；输入数值按字段语义保留原十进制文本，结构整数保持 number。
- `QuotationWizard.tsx` 与 draft payload builder：不对 `componentData.rowData` 中的输入字段做统一 decimal 规整；公式结果按 9 位生成。
- `QuotationStep2.tsx`、`tabTotalLines.ts`、Step3/总额相关 helper：产品卡片小计和报价总金额分别调用独立结果精度函数。
- 三视图、快照、Excel 数据组装：输入原值不被显示精度反向覆盖。

## 2. 实现规则

1. 字段分类兼容 `field_type` 与 `fieldType`；公式类型包括 `FORMULA` 和 `*_FORMULA`。
2. `INPUT_NUMBER` 内部类型使用 decimal string/Decimal，不得引入 `number|string` 精度联合类型。
3. 编辑控件收到 `"1.2300"` 时不得在 onChange/onBlur 中 normalize 为 `"1.23"`。
4. 产品小计使用专用 `formatProductCardSubtotal`/等价 helper；报价总额使用 `formatQuotationTotal`/等价 helper。
5. 两个 helper 的默认 scale 分别来自独立常量，注释预留系统参数，不在组件中散落字面量 9。

## 3. 自检

- `npx tsc --noEmit -p tsconfig.app.json`
- 定向 Vitest：lossless JSON、draft payload、公式引擎、组件小计、Step3/总额、三视图。
- Vite transform：所有改动 `.ts/.tsx` 返回 200，且不是 SPA fallback。
- Playwright：真实 DRAFT 打开、保存、刷新重开，验证 `项次=1` 和 `1.2300`。

## 4. 任务清单

- [ ] F1 定义四层精度常量并补注释
- [ ] F2 建立字段语义分类 helper
- [ ] F3 修复 lossless JSON 中文键乱码
- [ ] F4 输入字段原值贯穿编辑、保存、重开
- [ ] F5 公式最终结果收口 9 位
- [ ] F6 产品卡片小计接独立变量
- [ ] F7 报价单总额接独立变量
- [ ] F8 三视图与导出回归
- [ ] F9 TypeScript/Vitest/Vite/Playwright 自检
