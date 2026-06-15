# D. cross_tab 多源 SUM targetExpr 字段 per-field source（#1 真修）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复组件管理公式编辑器里「选 `[来料加工费.费用]` 保存后显示成 `[元素.费用]`」——多页签混引的 SUM 里，targetExpr 的跨页签 field token 丢失各自来源、被回显到顶层 primary source（元素）。

**Architecture:** 根因（线上数据 + 代码链确证）：单个 SUM 混引多页签（`SUM([来料.毛重]*[元素.含量]*([元素.单价]+[来料加工费.费用]))`）走 N≥2 多源路径，`primaryTab=ordered[0]` 按 rowKeyFields 数量排序 → 元素(2键)排首 → 顶层 `source=元素`；而 targetExpr 的 field token 在 `:461` 只 push `{type:'field',value}` **不带 source**（KSUM 路径 `:372` 才带）。渲染 `renderTargetExprParts:770` 对无 source 的 field 回退用顶层 source 标签 → `费用` 显示成 `[元素.费用]`。修法 = **解析时给 targetExpr 的跨页签 field token 带上 `source: td.componentId`**（对齐 KSUM 路径）；渲染侧 `:770` 已支持 `te.source` 自动正确回显；后端 `evaluateTargetValue` 多源按字段名合并求值（同名不撞时本就对）→ 加 per-field source 优先取值作**跨源同名稳健加固**。

**Tech Stack:** React+TS / Vitest（`formulaSerialize.ts` + `.test.ts`）；Java/Quarkus（`FormulaCalculator.java` `targetRowValue`/`evaluateTargetValue`）。

**复现实证（线上 component COMP-0028 公式 `纯材料成本(来料)` 第一个 SUM）：** `source=元素`、`sources=[元素,来料加工费]`、targetExpr field `含量/单价/费用` 全 `src=null`；其中 `费用` 属 来料加工费 却回显为 元素.费用。

---

## 根因（已确认，代码级）

- 解析 `formulaSerialize.ts` 行级 body（`:438-463`）：`[别名.列]` 经 `findTabByRef` 命中非宿主 source → `srcTabsSeen` 收集该 source，但 push 的 field token 是 `{type:'field', value:col}`（`:461`，**无 source**）。对比 KSUM 内层（`:372`）push `{type:'field', value:col, source: td.componentId}`（**带 source**）。
- N≥2 组装（`:510-549`）：顶层 `source=primaryTab.componentId`（`ordered[0]`，按 rowKeyFields 长度降序 + componentId tie-break），`sources[]` 含全部源，targetExpr 原样（field 仍无 source）。
- 渲染 `tokensToDrawerExpression → renderTargetExprParts`（`:764-800`）：field token `te.source ? 查tabDef : outerAlias`（`:770-774`）。无 source → outerAlias = 顶层 source 标签（元素）→ 错显。
- 后端 `FormulaCalculator.targetRowValue`（`:325-375+`）：多源把 coarse source 行数值列**按名**并入 `sub.fieldValues`，field 求值按名取——字段名跨源唯一时正确，同名则后写覆盖（潜在串值）。

---

## File Structure

- `cpq-frontend/src/pages/component/formulaSerialize.ts` — 行级 body field push（`:454-462`）带 `source: td.componentId`；N=1 与 N≥2 组装路径不动（field token 自带 source 即可）。
- `cpq-frontend/src/pages/component/formulaSerialize.test.ts` — 多源 SUM field 带 source + round-trip 正确回显 + N=1 不回归。
- `cpq-backend/.../quotation/service/FormulaCalculator.java` — `evaluateTargetValue`/`targetRowValue`：field token 带 `source` 时优先按该 source 行取值（防跨源同名）。
- 后端测试：`FormulaCalculatorTest`（或 cross_tab 多源测试类）。

**不变量：** ① 多源 SUM 里每个跨页签 field token 携带其真实 source componentId；② `tokensToDrawerExpression` 回显每个 field 用其自身 source 的页签名（`费用`→`[来料加工费.费用]`，不再 `[元素.费用]`）；③ 后端求值结果不因加 source 改变（同名不撞场景逐字节一致），跨源同名场景按 per-field source 取对值。

---

## Task D1：前端 — 多源 SUM field token 丢 source 失败测试（RED）

**Files:**
- Test: `cpq-frontend/src/pages/component/formulaSerialize.test.ts`

- [ ] **Step 1: 读 `:438-549`(解析) + `:742-816`(回显) 现状**，确认 field push 点与 renderTargetExprParts te.source 分支。

- [ ] **Step 2: 写失败测试**（复刻线上场景：元素 2键、来料加工费 1键；元素排 primaryTab）

```ts
import { expressionToTokens, tokensToDrawerExpression, type TabDef } from './formulaSerialize';

const tabDefs: TabDef[] = [
  { componentId: 'CID-来料', alias: 'COMP-0028', componentName: '来料', rowKeyFields: ['料件'] },
  { componentId: 'CID-元素', alias: 'COMP-0029', componentName: '元素', rowKeyFields: ['料件','元素'] },
  { componentId: 'CID-来料加工费', alias: 'COMP-0036', componentName: '来料加工费', rowKeyFields: ['料件'] },
];

it('D1 多源SUM内每个跨页签field带各自source', () => {
  // 宿主=来料; SUM 混引 元素.含量/单价 + 来料加工费.费用
  const expr = 'SUM([元素.含量] * ([元素.单价] + [来料加工费.费用]))';
  const tokens = expressionToTokens(expr, tabDefs, ['料件'], 'CID-来料');
  const sum = tokens.find(t => t.type === 'cross_tab_ref')!;
  const te = sum.targetExpr!;
  const f含量 = te.find(t => t.type === 'field' && t.value === '含量')!;
  const f费用 = te.find(t => t.type === 'field' && t.value === '费用')!;
  expect(f含量.source).toBe('CID-元素');           // 修复前: undefined
  expect(f费用.source).toBe('CID-来料加工费');       // 修复前: undefined → 回显落元素
});

it('D1 回显: 来料加工费.费用 不再显示成 元素.费用', () => {
  const expr = 'SUM([元素.含量] * ([元素.单价] + [来料加工费.费用]))';
  const tokens = expressionToTokens(expr, tabDefs, ['料件'], 'CID-来料');
  const shown = tokensToDrawerExpression(tokens, tabDefs, 'CID-来料');
  expect(shown).toContain('[来料加工费.费用]');       // 修复前: 含 [元素.费用]
  expect(shown).not.toContain('[元素.费用]');
});
```

- [ ] **Step 3: 跑确认失败** → `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts -t "D1"`，Expected: FAIL（field.source undefined / 回显 [元素.费用]）。
- [ ] **Step 4: 提交** `git commit -m "test(formula): 多源SUM targetExpr field 丢 per-field source 失败用例 (RED)"`

---

## Task D2：前端 — 行级 body field token 带 source（GREEN）

**Files:**
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.ts:454-462`

- [ ] **Step 1: 给跨页签 field token 带 source**

把行级 body 解析里（非宿主分支，约 `:454-461`）的：
```ts
// field token 保持原样（不附带 source），多 source 信息通过外层 sources 数组传递
targetExpr.push({ type: 'field', value: col });
```
改为：
```ts
// field token 携带各自 source componentId（对齐 KSUM 路径 :372），
// 使多源 SUM 内跨页签字段在回显/求值时能归属到真实页签（修 [来料加工费.费用] 被显示成 [元素.费用]）
targetExpr.push({ type: 'field', value: col, source: td.componentId });
```
（宿主自身列仍走 b_field 分支不变；只改非宿主 source 的 field push。）

- [ ] **Step 2: 跑 D1** → PASS（含量 source=元素、费用 source=来料加工费、回显 [来料加工费.费用]）。
- [ ] **Step 3: 全量 formulaSerialize 测试不回归** → `npx vitest run src/pages/component/formulaSerialize.test.ts`，全 PASS。**若**某 N=1「字节级兼容」用例因 field 多了 source 字段而失败：确认该用例断言的是 `sources[]` 还是 field token 形状——前者不受影响；若确是 field token 形状的存量字节兼容断言，评估：N=1 单源下 field 带 source 不影响回显/求值（render te.source=单源=outerAlias 等价），更新该断言为含 source 的新形状（在测试里注明"per-field source 修复后 field token 带 source"）。不得为过兼容而丢掉本修复。
- [ ] **Step 4: tsc + Vite 200** → `npx tsc --noEmit -p tsconfig.json`；`curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/formulaSerialize.ts` 200。
- [ ] **Step 5: 提交** `git commit -m "fix(formula): 多源SUM targetExpr field 带 per-field source, 修跨页签字段错显 (GREEN)"`

---

## Task D3：后端 — evaluateTargetValue 按 per-field source 取值（跨源同名加固）

**Files:**
- Modify: `cpq-backend/.../quotation/service/FormulaCalculator.java`（`targetRowValue` 多源注入 + field 求值）
- Test: `cpq-backend/src/test/java/com/cpq/quotation/service/FormulaCalculator*Test.java`

- [ ] **Step 1: 读 `:325-400`（targetRowValue 多源广播 + sub.fieldValues 求值）** 确认 field 当前按名取值。
- [ ] **Step 2: 失败测试（跨源同名）** — 构造两源各有同名数值列（如 元素.费用=10、来料加工费.费用=3），SUM targetExpr 引 `[来料加工费.费用]`（field 带 source=来料加工费），断言取 3 不是 10。修复前按名后写覆盖 → 取错。
- [ ] **Step 3: 实现** — 多源注入时**按 source 分桶**保存（`sub` 维护 `source→{col→val}`），field 求值若 token 带 `source` 则优先从该 source 桶取值，否则回退现有按名 `sub.fieldValues`（向后兼容无 source 的存量 token）。最小改动：保留现有按名合并，额外维护 `Map<String,Map<String,Double>> bySource`，field 有 source 时 `bySource.get(source).get(col)` 优先。
- [ ] **Step 4: 跑通过 + 既有不回归** → `cd cpq-backend && ./mvnw -q -Dtest=FormulaCalculatorTest,FormulaCalculatorMultiSubtotalTest,CardSnapshotCrossTabTest test`，全 PASS。
- [ ] **Step 5: 提交** `git commit -m "fix(formula): 后端 evaluateTargetValue 按 per-field source 取值, 防跨源同名串值"`

---

## Task D4：E2E + 真机复核（合并后）

- [ ] **Step 1: E2E（协议级强制）** → `cd cpq-frontend && npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list`，Expected: `1 passed`、`'加载中' final=0`、8 Tab=0。
- [ ] **Step 2: 真机复核** — 组件管理 来料 → `纯材料成本(来料)` 公式抽屉重存一次（新解析带 source）→ 重开确认显示 `[来料加工费.费用]`（不再元素）；库内该 token 的 `费用` field `source`=来料加工费 componentId。
- [ ] **Step 3: 存量说明** — 仅修代码；历史已存公式需用户在编辑器重存一次触发新解析自愈（field 带 source）。报价渲染层因后端 per-field source 加固，跨源同名也不再串值。

---

## Self-Review

- **Spec 覆盖：** 解析带 source(D2) + 失败用例(D1) + 后端取值加固(D3) + E2E/真机(D4)。✓
- **渲染零改动：** `renderTargetExprParts:770` 已支持 te.source，D2 后自动正确回显。✓
- **兼容：** 无 source 的存量 token 后端仍按名回退求值；前端回显无 source 仍用 outerAlias（旧行为）。新存公式带 source。✓
- **风险：** AP-44 协议级（formulaSerialize 解析 + FormulaCalculator 求值）→ 强制 E2E；禁 `git add -A`、不提交 RECORD.md（主线合并后追加）。
- **N≥2 primaryTab 排序不改：** 顶层 source 仍是 primaryTab（仅作 sources[0] 镜像），字段归属已由 per-field source 解决，不依赖顶层 source 是谁。
