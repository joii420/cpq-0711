# 报价小计体系调整 — 页签总计 footer + 产品小计默认求和 + 解除小计组件强制 设计方案

> 日期：2026-06-10 | 状态：设计已评审通过（Q&A 澄清完毕） | 范围：报价/核价卡片小计渲染 + 产品小计口径 + 模板发布校验
> 分支：feat/subtotal-tab-total

## 1. 背景

用户在报价 Step2「产品」页签看到卡片底部「产品小计」上方多出一条 **「元素 · 小计 ¥0.00」**。排查确认：这是 **Plan 2-核心「多小计列向上汇总」**（commit `a8e6475`，2026-06-09）的有意设计——卡片底部对**每个非 SUBTOTAL 组件的每个 `is_subtotal` 列**各渲染一条 `「组件 · 列名」` 汇总线。元素组件有名为「小计」的 `is_subtotal` 字段 → 冒出该行；值 ¥0.00 因元素页签当前无数据。

用户期望调整该行为（详见澄清结论）。

## 2. 需求（Q&A 澄清结论）

| # | 结论 |
|---|---|
| 1 | 卡片底部汇总条改为 **按页签一条**（替代"按列一条"）：每个组件 → 把它**所有 `is_subtotal` 列的列小计求和** → 出一条 **`页签名 · 总计`**。标签用 **`元素 · 总计`**（页签名 + "总计"）。 |
| 2 | **小计的按列计算不变**（每列各算各的）；**页签内每列小计行不变**（切到该页签仍逐列显示各列小计）。只改卡片底部汇总条的聚合粒度。 |
| 3 | **产品小计**：模板拖了 SUBTOTAL（计算公式）组件 → = 该组件 `formulas` 算的（**现有行为不变**，可自定义、不一定等于各页签总计之和，用户接受）；模板**没拖** SUBTOTAL 组件 → 默认 = **所有页签总计之和**。 |
| 4 | **解除"必须配小计"的发布强制**：允许模板**不配 SUBTOTAL 组件**也能发布，运行时产品小计走 #3 默认求和。 |
| 5 | **三视图一致（AP-50）**：编辑页 / 详情页 / 核价页底部都要显示「各页签 · 总计」多条 + 产品小计，长一样。 |

## 3. 已核实代码事实（勿重新发明）

- **卡片底部汇总条（编辑页）**：`QuotationStep2.tsx:2198-2228`。现遍历每个非 SUBTOTAL 组件的每个 `is_subtotal` 字段各出一条 `「comp.tabName · f.name」`，值取 `allComponentSubtotals[`${comp.componentCode}#${f.name}`] ?? allComponentSubtotals[`${comp.tabName}#${f.name}`] ?? 0`；末尾 `产品小计` 走 `computeProductSubtotal`。
- **卡片底部（详情/核价页）**：`ReadonlyProductCard.tsx:629-632`。**现只有一条「产品小计」**，无任何分条。
- **页签内每列小计行**：编辑页 `QuotationStep2.tsx:2150-2179` `<tfoot>`；详情页 `ReadonlyProductCard.tsx:599-617`。逐 `is_subtotal` 字段在各自列位置显示列小计 + 列 0 显示「小计」标签。**本次不动。**
- **每列列小计计算**：`computeTabSubtotalsByColumn`（`QuotationStep2.tsx:836-862`，导出）逐 `is_subtotal` 列对各行求和；`computeTabSubtotal`（`:864-882`）= 该组件所有列小计之和。**本次不动计算逻辑。**
- **产品小计计算**：`computeProductSubtotal`（`QuotationStep2.tsx:885-957`，导出，**编辑页 + 详情页 `ReadonlyProductCard.tsx:337` 共用**）。流程：
  1. 逐 NORMAL 组件算 `computeTabSubtotal` 存入 `componentSubtotals`，**按 3 个键存**（componentId / componentCode / tabName，`:913-915`）；
  2. 有 SUBTOTAL 组件且有公式 → 用其 `formulas[0]` 求值（`:918-937`）；
  3. 旧 `subtotalFormula` token 兜底（`:939-953`）；
  4. **最终兜底：`Object.values(componentSubtotals).reduce(+)`（`:955-956`）**。
- 🐛 **潜在 bug**：最终兜底（4）对按 3 键存的 `componentSubtotals` 取 `Object.values` 求和 → **每个组件重复累加约 3 次**。因当前发布校验**强制**有 SUBTOTAL 组件，此兜底几乎从不执行，bug 潜伏。解除强制后（需求 #4）无 SUBTOTAL 组件的模板会踩到它 → **必须修**。
- **发布强制校验**：`TemplateService.java:204-217`。无 `subtotalFormula` token 且无 SUBTOTAL 组件 → 抛 `「模板发布前必须配置小计:请拖入一个『小计』类型的组件,或在模板属性中填写小计公式」`。
- **引导文案**：`ConfigGuideDrawer.tsx:266`「SUBTOTAL 必须有 formulas，否则发布校验阻塞」/ `:61`「模板里只允许 1 个」/ `:98`「is_subtotal 只 SUBTOTAL 组件用」（与 NORMAL 组件实际也用 is_subtotal 不符的旧文案）。
- **后端产品小计**：`FormulaCalculationService.calculateProductSubtotal`（`:120`）是**旧 token 公式路径**（`subtotalFormulaJson` 为空 → 返 `ZERO`，**不默认求和**）；`CardSnapshotService:213` 搬运 componentCode 供产品小计。后端产品小计用于何视图（Excel / snapshot 持久化）+ 无 SUBTOTAL 组件时是否默认求和 → **见 §6 验证项**。

## 4. 设计

### 4.1 ① 卡片底部汇总条：按页签一条（编辑页 + 详情/核价页）

**编辑页 `QuotationStep2.tsx:2200-2217`**：把"遍历每个 `is_subtotal` 字段各出一条"改为"**遍历每个非 SUBTOTAL 组件 → 累加它所有 `is_subtotal` 列的列小计 → 出一条**"：

```tsx
for (const comp of item.componentData) {
  if (!comp?.fields || comp.componentType === 'SUBTOTAL') continue;
  const subFields = comp.fields.filter(f => f.is_subtotal);
  if (subFields.length === 0) continue;
  let tabTotal = 0;
  for (const f of subFields) {
    tabTotal += allComponentSubtotals[`${comp.componentCode}#${f.name}`]
             ?? allComponentSubtotals[`${comp.tabName}#${f.name}`] ?? 0;
  }
  lines.push({ label: `${comp.tabName} · 总计`, value: tabTotal });
}
```
（复用已算好的 `allComponentSubtotals` 列小计，不重算；标签 `页签名 · 总计`。）

**详情/核价页 `ReadonlyProductCard.tsx:629`**：当前只有单条「产品小计」。改为与编辑页同构——先渲染「各页签 · 总计」多条，再「产品小计」。复用详情页已有的 per-column 小计 map（`:276-283` 一带算出的 subtotal 源），按相同口径累加成每页签一条。**三视图一致（AP-50）。**

> 抽取共享：把"按页签累加成 lines"的逻辑抽成一个纯函数（如 `buildTabTotalLines(componentData, allComponentSubtotals)`，放 `QuotationStep2.tsx` 导出或单独 util），编辑页 + 详情页共用，避免两处口径漂移。

### 4.2 ② 产品小计：修三重累加 + 默认求和自洽

**`computeProductSubtotal` 最终兜底（`:955-956`）** 改为**逐组件只累加一次**（不取多键 map 的 values）：

```tsx
// Final fallback: 无 SUBTOTAL 组件/公式 → 各页签总计之和（逐组件一次，避免多键重复累加）
let sum = 0;
for (const comp of item.componentData) {
  if (!comp?.fields || comp.componentType === 'SUBTOTAL') continue;
  if (!comp.fields.some(f => f.is_subtotal)) continue;
  sum += (comp.componentId && componentSubtotals[comp.componentId]) ?? 0;
}
return sum;
```
（或直接用 `buildTabTotalLines` 的结果求和，保证"产品小计 = 各页签总计之和"严格自洽。）

- 编辑页 + 详情页**共用** `computeProductSubtotal` → 一处修复两视图一致。
- 有 SUBTOTAL 组件时（`:918-937`）路径不变；默认求和仅在无 SUBTOTAL 组件且无 `subtotalFormula` 时生效。

### 4.3 ③ 解除发布强制 + 文案

**`TemplateService.java:204-217`**：删除"无 subtotalFormula 且无 SUBTOTAL 组件 → 抛错"的分支（`:214-216` 的 throw）。保留 `:201` 的"至少一个组件"校验。SUBTOTAL 组件仍可选配（配了则其 formulas 仍走原校验：`ConfigGuideDrawer` 提到的"SUBTOTAL 必须有 formulas"若是独立校验则保留——见 §6 验证项确认该 formulas 校验在何处、是否随之放开）。

**`ConfigGuideDrawer.tsx`**：`:266` 等措辞由"必须配置小计/必须拖 SUBTOTAL"改为"**可选**：不配则产品小计默认 = 各页签总计之和"。

## 5. 不做（YAGNI）

- 不改每列列小计的计算逻辑（`computeTabSubtotalsByColumn` / `computeTabSubtotal`）。
- 不改页签内每列小计行（两视图 `<tfoot>`）。
- 不强制"有 SUBTOTAL 组件时产品小计 = 各页签总计之和"（用户接受公式可自定义、可不自洽）。
- 不动 SUBTOTAL 组件"每模板只 1 个"的约束（仅解除"必须有"）。

## 6. 验证项（实施前/中必须用代码核实，勿假设）

1. **后端产品小计默认求和落点**：`calculateProductSubtotal` 只认旧 token。需查清后端在**无 SUBTOTAL 组件**时，Excel 视图 / quote snapshot 的产品小计走哪条路、是否需要补"默认求和"。若后端某视图产品小计恒 0/异常 → 在对应后端落点补默认求和（与前端口径一致）。
2. **"SUBTOTAL 必须有 formulas"校验**：确认它与 `:204-217` 是否同一处。解除"必须有 SUBTOTAL 组件"后，若用户**配了** SUBTOTAL 组件但没填 formulas，是否仍应报错（倾向保留：配了就得填）。
3. **详情页 per-column 小计源**：确认 `ReadonlyProductCard` 里可复用的 `allComponentSubtotals` 等价 map（`:276-283` 一带），口径与编辑页 `allComponentSubtotals` 一致，确保抽取的 `buildTabTotalLines` 两处通用。

## 7. 影响面 / 测试（AP-50 + 协议级）

- 改 `QuotationStep2.tsx`（footer）+ `ReadonlyProductCard.tsx`（footer）+ `TemplateService.java`（校验）+ `ConfigGuideDrawer.tsx`（文案）+ 可能后端产品小计落点。命中 CLAUDE.md「协议级 + 三视图」→ 必跑 E2E。
- **单测**：`computeProductSubtotal` 默认求和（无 SUBTOTAL 组件，多组件多列，验证不重复累加 + = 各页签总计之和）vitest；`buildTabTotalLines` 纯函数 vitest（多列合一条 / 多组件多条 / 无小计列不出条）。
- **E2E**：`quotation-flow` 回归（编辑页 footer 现为「页签·总计」多条 + 产品小计）；详情页/核价页底部同构截图；模板「不配 SUBTOTAL 组件也能发布」用例。
- **三视图一致**：编辑 / 详情 / 核价 三处 footer 数字与结构一致（共享 `buildTabTotalLines` + `computeProductSubtotal`）。

## 8. 验收标准

- 编辑页底部：每个有小计列的页签一条 `「页签名 · 总计」`（= 该页签多列小计之和）+ 末尾「产品小计」。
- 详情页 / 核价页底部：同构（多条页签总计 + 产品小计）。
- 模板**无** SUBTOTAL 组件：可发布；产品小计 = 各页签总计之和（不重复累加）。
- 模板**有** SUBTOTAL 组件：产品小计 = 其公式值（现有行为不变）。
- 页签内每列小计行、每列计算：不变。
