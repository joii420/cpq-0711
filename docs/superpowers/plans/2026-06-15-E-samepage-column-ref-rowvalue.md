# E. 同页签列引用默认取同行值（材料成本等二阶列真修）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 `材料成本` 这类"引用本页签其它列"的公式：每行取**该行自己的值**(行内相加)，而不是整列总计标量；小计 = 各行之和。统一规则：同页签列引用(无`(总计)`)= 同行值，只有显式 `(总计)` 才取整列总计。

**Architecture:** 根因(线上数据确证)：`材料成本 = 来料材料费 + 外购件材料费 + 自制加工费 − 回收成本` 引用的是本组件(来料)的**小计列**；`formulaSerialize.ts:627` 因这些列 ∈ `tabDef.subtotalCols` 一律映射成 `component_subtotal`(整列总计标量)，且该判断**排在"同组件同行"判断(:635)之前** → 即使是自身组件也取总计 → 每行=同一标量(6.703)、小计=标量×行数(20.109)。修法：把"同组件列引用(无总计)"优先判为**同行 `field` token**(即使是小计列)；只有跨组件小计列引用才 `component_subtotal`。前后端引擎均已有公式列拓扑依赖排序(`getFormulaDeps`/`buildFormulaDeps` 收集 field 依赖 → 先算被引用列再算 `材料成本`)，故无需改引擎；存量公式**重存即自愈**(抽屉串 `[来料.来料材料费]` 经修复解析器→同行 field)。

**Tech Stack:** React+TS / Vitest（`formulaSerialize.ts` + `.test.ts`）；验证 `QuotationStep2.computeAllFormulas` + 后端 `FormulaCalculator`（已支持,加测试护栏）。

**复现实证（线上 QT-20260615-1727 来料 tab 持久化）：** `材料成本` resolvedRows = 6.703/6.703/6.703(三行同值=标量)，`subtotalByColumn.材料成本`=20.109=6.703×3。期望(每行自己的值)：行1=来料材料费_1+外购件材料费_1+自制加工费_1−回收成本_1，小计=Σ各行。

---

## 根因（已确认，代码级）

- `formulaSerialize.ts:627`：`if (!isAgg && tabDef.subtotalCols.includes(fieldPart))` → `component_subtotal`(总计)。该分支在 `:635`(自身组件 → field 同行)之前，且对自身组件小计列也命中 → 错取总计。
- `getFormulaDeps`(QuotationStep2:366) 收集顶层 `field` token 值为依赖；`computeAllFormulas` 拓扑序求值、结果回喂 `fieldValues`(:476/:623 后端)。→ field 引用公式列**已支持**。
- 回显 `tokensToDrawerExpression`:component_subtotal → `[label.col]`(:735)；故存量 `材料成本` 抽屉串 = `[来料.来料材料费]+...`，重存经修复解析器 → 同行 field（自愈）。

---

## File Structure

- `cpq-frontend/src/pages/component/formulaSerialize.ts:627-643` — 调整分支优先级：自身组件列引用(无总计)→ field；跨组件小计列→ component_subtotal。
- `cpq-frontend/src/pages/component/formulaSerialize.test.ts` — 自身组件小计列引用→field + 跨组件→component_subtotal + (总计)→component_subtotal + round-trip。
- `cpq-frontend/src/pages/quotation/buildCrossTabRows.test.ts`（或 computeAllFormulas 测试）— 护栏：`材料成本`(field 引用本组件公式列)逐行=该行各列之和、小计=Σ行。
- `cpq-backend/.../service/FormulaCalculatorTest.java` — 后端同款护栏（topo + 同行 field 引用公式列）。

**不变量：** ① 同组件列引用(无总计)→ `field`(同行)；② 跨组件小计列引用→ `component_subtotal`(总计)；③ `[X(总计)]`/`[alias.col(总计)]`→ `component_subtotal`；④ `材料成本` 逐行 = 该行 来料材料费+外购件材料费+自制加工费−回收成本，小计 = Σ各行（前后端一致）。

---

## Task E1：前端序列化 — 同组件小计列引用→同行 field 失败测试（RED）

**Files:**
- Test: `cpq-frontend/src/pages/component/formulaSerialize.test.ts`

- [ ] **Step 1: 读 `:595-670`(bracket_expr 含点分支) + `:728-740`(component_subtotal 回显)**。
- [ ] **Step 2: 写失败测试**

```ts
const tabDefs: TabDef[] = [
  { componentId: 'CID-来料', alias: 'COMP-0028', componentName: '来料', rowKeyFields: ['料件'], subtotalCols: ['来料材料费','外购件材料费','自制加工费','回收成本','材料成本'] },
  { componentId: 'CID-元素', alias: 'COMP-0029', componentName: '元素', rowKeyFields: ['料件','元素'], subtotalCols: [] },
];
it('E1 同组件小计列引用(无总计)→同行field', () => {
  const tokens = expressionToTokens('[来料.来料材料费] + [来料.外购件材料费]', tabDefs, ['料件'], 'CID-来料');
  const fields = tokens.filter(t => t.type === 'field');
  expect(fields.map(f => f.value)).toEqual(['来料材料费','外购件材料费']);   // 修复前: component_subtotal
  expect(tokens.some(t => t.type === 'component_subtotal')).toBe(false);
});
it('E1 同组件列显式(总计)→仍 component_subtotal', () => {
  const tokens = expressionToTokens('[来料.来料材料费(总计)]', tabDefs, ['料件'], 'CID-来料');
  expect(tokens.some(t => t.type === 'component_subtotal')).toBe(true);
});
it('E1 跨组件小计列引用→component_subtotal(不变)', () => {
  // 元素 无 subtotalCols；用一个有 subtotalCols 的兄弟组件验证跨组件仍取总计
  const tabs2: TabDef[] = [
    { componentId: 'CID-self', alias: 'SELF', componentName: '自', rowKeyFields: ['料件'], subtotalCols: [] },
    { componentId: 'CID-other', alias: 'OTH', componentName: '他', rowKeyFields: ['料件'], subtotalCols: ['费用小计'] },
  ];
  const tokens = expressionToTokens('[他.费用小计]', tabs2, ['料件'], 'CID-self');
  expect(tokens.some(t => t.type === 'component_subtotal')).toBe(true);
});
```
（TabDef 的 `subtotalCols` 字段名以实际定义为准；若 tabDefs 不带 subtotalCols 则按真实结构调整 fixture。）

- [ ] **Step 3: 跑确认失败** → `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts -t "E1"`，Expected: 第一个 FAIL（得到 component_subtotal）。
- [ ] **Step 4: 提交** `git commit -m "test(formula): 同组件小计列引用应取同行值失败用例 (RED)"`

---

## Task E2：前端序列化 — 调整分支优先级（GREEN）

**Files:**
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.ts:627-643`

- [ ] **Step 1: 自身组件分支前置**

把（约 :627-643）：
```ts
if (!isAgg && (tabDef.subtotalCols ?? []).includes(fieldPart)) {
  result.push({ type: 'component_subtotal', ... });
} else if (selfComponentId && tabDef.componentId === selfComponentId && !isAgg) {
  result.push({ type: 'field', value: fieldPart });
} else {
  result.push(makeCrossTabRef(...));
}
```
改为（自身组件优先 → 同行 field，即使是小计列）：
```ts
if (selfComponentId && tabDef.componentId === selfComponentId && !isAgg) {
  // 同组件列引用(无总计) → 同行值(field)，即使被引用列是小计列。
  // 引擎按 getFormulaDeps/拓扑序先算被引用公式列、再算本列(同行相加)。
  result.push({ type: 'field', value: fieldPart });
} else if (!isAgg && (tabDef.subtotalCols ?? []).includes(fieldPart)) {
  // 跨组件小计列引用 → component_subtotal(整列总计)
  result.push({ type: 'component_subtotal', value: fieldPart, tab_name: fieldPart,
    component_code: tabDef.alias, label: `${tabDef.componentName ?? tabDef.alias}·${fieldPart}` });
} else {
  result.push(makeCrossTabRef(alias, fieldPart, isAgg ? 'SUM' : 'NONE', tabDefs, selfRowKeyFields));
}
```
> `(总计)` 分支(isAgg)、whole-tab `[alias(总计)]` 分支(:644+)不动，仍 component_subtotal。

- [ ] **Step 2: 跑 E1** → PASS。
- [ ] **Step 3: 全量 formulaSerialize 不回归** → `npx vitest run src/pages/component/formulaSerialize.test.ts` 全 PASS（如有依赖旧"同组件小计列=component_subtotal"的用例，按新语义更新断言并注明）。
- [ ] **Step 4: tsc + Vite 200** → `npx tsc --noEmit -p tsconfig.json`；`curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/formulaSerialize.ts` 200。
- [ ] **Step 5: 提交** `git commit -m "fix(formula): 同组件列引用(无总计)取同行值, 跨组件小计列才取总计 (GREEN)"`

---

## Task E3：前端引擎护栏 — 材料成本逐行=各列之和

**Files:**
- Test: `cpq-frontend/src/pages/quotation/buildCrossTabRows.test.ts`（或 computeAllFormulas 专测）

- [ ] **Step 1: 写护栏测试** — 构造 来料 组件：来料材料费/外购件材料费 为 FORMULA 列(给定逐行值，如行1: 来料材料费=5, 外购件材料费=2; 行2: 0,3)，材料成本 = field(来料材料费)+field(外购件材料费)。断言 computeAllFormulas 逐行：行1 材料成本=7、行2=3（**非标量、非总计**）；小计=Σ=10。
- [ ] **Step 2: 跑** → 期望 PASS（引擎已支持 field 引用公式列 + 拓扑序）；若 FAIL 说明 getFormulaDeps/求值有缺口，按需补（getFormulaDeps:366 已收集 field deps）。
- [ ] **Step 3: 提交** `git commit -m "test(formula): 护栏 — 材料成本逐行=各列同行之和, 小计=Σ行"`

---

## Task E4：后端引擎护栏 — 同款

**Files:**
- Test: `cpq-backend/.../service/FormulaCalculatorTest.java`

- [ ] **Step 1: 写测试** — fields 含 来料材料费/外购件材料费(FORMULA, 逐行值) + 材料成本=field 引用二者；断言 `computeRows` 逐行 材料成本=同行之和、subtotalByColumn=Σ行。
- [ ] **Step 2: 跑** → 期望 PASS（后端 topoOrder:580 + buildFormulaDeps:1187 已支持）；FAIL 则补 `addExprFieldDeps` 覆盖。
- [ ] **Step 3: 不回归** → `cd cpq-backend && ./mvnw -q -Dtest=FormulaCalculatorTest,CardSnapshotCrossTabTest,ComponentSubtotalColumnKeyTest test` 全 PASS。
- [ ] **Step 4: 提交** `git commit -m "test(formula): 后端护栏 — 材料成本逐行=同行之和"`

---

## Task E5：E2E + 真机复核（合并后）

- [ ] **Step 1: E2E** → `cd cpq-frontend && npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list`，Expected: `1 passed`、加载中=0、8 Tab=0。
- [ ] **Step 2: 真机自愈复核** — 组件管理 来料 → `材料成本` 公式抽屉**重存一次**（抽屉串 `[来料.来料材料费]+...` 经修复解析器→同行 field）→ 库内该公式 token 由 component_subtotal 变 field。打开 QT-20260615-1727 来料 tab：材料成本逐行=该行各列之和(不再 6.703 三行同值)、小计=Σ行。
- [ ] **Step 3: 行值vs小计同源核对** — 同一 tab 行渲染与页脚小计一致（外购件材料费 行 sum == 小计；若仍分叉,排查实时渲染 footer 是否走 resolvedRows，复用 B 计划口径）。
- [ ] **Step 4: 存量说明** — 仅修代码；存量 `材料成本` 等"同组件列引用"公式**在编辑器重存一次即自愈**（无需手工重选，区别于 #1）。

---

## Self-Review

- **Spec 覆盖：** 同组件列→同行(E2) + 失败用例(E1) + 前后端逐行护栏(E3/E4) + E2E/真机/自愈(E5)。✓
- **语义边界：** (总计)/whole-tab(总计)/跨组件小计列 仍 component_subtotal，不破坏"报价小计引用各组件总计"等正当用法。✓
- **引擎零改：** 前后端均已 topo + field 依赖；E3/E4 为护栏，若意外缺口才补。✓
- **自愈优于 #1：** 抽屉串 `[来料.来料材料费]` 重存即转 field（#1 的 [元素.费用] 丢源不可自愈,本例可）。✓
- **风险：** AP-44 协议级(序列化语义)→ 强制 E2E；禁 git add -A、不提交 RECORD.md（主线合并后追加）。
- **范围外：** 外购件 重复行(外购件2/单价/0.802×2)致 cross_tab 求和=1.604 属行键匹配的"基础能力"正常结果；若用户认为重复行本身是 driver/行键 bug,另起排查(本计划只统一同页签引用语义+行小计同源)。
