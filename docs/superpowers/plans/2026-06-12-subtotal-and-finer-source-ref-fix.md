# 小计输入列求和 + 细项裸引用(标红/报错)修复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** (问题1)让被标为"小计"的**输入型列**(汇率/单重等非公式列)在小计行实时正确求和;(问题4)细项页签(键⊋宿主)被**裸引用**(无 FN 聚合)时,在公式编辑器**标红**、在报价单/详情渲染呈**错误态 ⚠**(替代当前静默归 0)。

**Architecture:** 两问题合一分支,因均扩展前端 `computeAllFormulas` 的可选 out 对象。
- 问题1:`computeAllFormulas` 已在内部算好全字段值 `fieldValues`(含 INPUT/FIXED/DATA_SOURCE/BASIC_DATA,镜像渲染层取值),但只 return 公式结果。改为通过**可选 out 参** `out?.fieldValues` 暴露(返回类型不变,6 个不传 out 的 callsite 零改),小计累加 `cache[sf] ?? out.fieldValues[sf] ?? 0`;前后端 + 详情页三处同改(AP-50)。
- 问题4-编辑器:`parseFormulaSegments` 增 `fnDepth` 追踪 → `classifyRefSegment(insideFn)`,把"细 source 裸引用(非 FN 内)"判红(`needs-agg`),不误伤同级/粗裸引用(蓝)与 FN 内细引用(蓝)。
- 问题4-渲染:**evalCrossTab 求值逻辑一字不改**(严守 🔒 基线),错误走旁路:前端 `evaluateExpression` outDiag → `computeAllFormulas` `out?.errors` → ProductCard preComputedErrors → ComponentCell ⚠;后端 RowContext.rowErrors → RowResult.errors → snapshot.formulaResults.errors → ReadonlyProductCard ⚠。三视图一致。

**Tech Stack:** React 18 + TS + Ant Design + Quarkus/Java + Vitest + JUnit + Playwright;共享夹具 `cross-tab-cases.json` 锁前后端引擎一致。

**Spec 来源:** cpq-architect 设计(本会话)。问题4 用户拍板 C 方案(标红+报错,不硬拦保存)。

**前置(执行时):** 隔离 worktree(原生 EnterWorktree);`cpq-frontend/node_modules` symlink;复用主工作区 5174/8081,不另起 server。后端改 java 用 `touch` 触发 Quarkus 重启自检。

**协议警示:** 触碰 🔒 `FormulaCalculator.java`(求值基线,纪律=求值逻辑不改、仅加旁路)+ 报价单渲染基线;AP-50(三视图 ComponentCell 一致)、AP-44(渲染分支/formulaCache 协议,但**不新增 field_type** 故不触发 17 点全量);强制 E2E。

**测试命令:**
- 前端单测:`cd cpq-frontend && npx vitest run <文件>`
- 后端单测:`touch` 一个 java 文件等 5-7s 后 `curl`;`cd cpq-backend && ./mvnw test -Dtest=<类>`(或项目既有命令)
- 类型/transform/E2E:同计划 A 约定。

> **执行前必读真实代码**(架构给的行号为参考,以实际为准):前端 `QuotationStep2.tsx`(computeAllFormulas:377、fieldValues 收集:472-610、results:612-650、两处提前 return:401/422、computeTabSubtotalsByColumn:837-863、ProductCard 构建 allComponentSubtotals:1521-1546、footer 取值:2166-2178);`formulaEngine.ts`(evaluateExpression:251-295、crossTabError:279、注入 null.x:294、外层 catch:390);`formulaSerialize.ts`(classifyRefSegment:692、明细判色:733-738、isSubset/comparable:644-650、parseFormulaSegments:597-641);`ComponentCell.tsx`(formulaCache:103、FORMULA 渲染:271-276);`ReadonlyProductCard.tsx`(buildFormulaCache、列小计:304)。后端 `FormulaCalculator.java`(collectFieldValues:497、results:612-650、RowResult:423-436、computeTabSubtotalsByColumn:347-363、appendToken cross_tab_ref:159-166、evalCrossTab:230、evaluateExpression catch:78-80);`CardSnapshotService.java`(assembleTabsWithFormulaResults:716-718)。

---

## File Structure

| 文件 | 职责 | 动作 |
|---|---|---|
| `cpq-frontend/src/pages/quotation/QuotationStep2.tsx` | computeAllFormulas 加 out 对象(fieldValues+errors);提前 return 放行有输入型小计列;computeTabSubtotalsByColumn 回退取值;ProductCard 产 preComputedErrors | 修改 |
| `cpq-frontend/src/utils/formulaEngine.ts` | evaluateExpression 加 outDiag,多命中写错误原因(数值仍 0) | 修改 |
| `cpq-frontend/src/pages/component/formulaSerialize.ts` | parseFormulaSegments fnDepth;classifyRefSegment insideFn + 细 source 裸引用判红 | 修改 |
| `cpq-frontend/src/pages/component/formulaSerialize.test.ts` | 标红 TDD 用例 | 修改 |
| `cpq-frontend/src/pages/quotation/components/ComponentCell.tsx` | CellContext 加 formulaErrors;FORMULA 分支渲染 ⚠ | 修改 |
| `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx` | 列小计回退取值 + FORMULA cell ⚠(AP-50) | 修改 |
| `cpq-frontend/src/styles/*`(报价单样式) | `.qt-formula-cell-error` ⚠ 样式 | 修改/新增 |
| `cpq-backend/.../quotation/service/FormulaCalculator.java` | RowResult 加 fieldValues+errors;computeTabSubtotalsByColumn 回退;appendToken ERR 旁路记错(evalCrossTab 不改) | 修改 |
| `cpq-backend/.../quotation/service/CardSnapshotService.java` | formulaResults 透传 errors | 修改 |
| `cpq-backend/.../test/.../FormulaCalculatorTest.java` + `cross-tab-cases.json` | 输入型小计求和 + 多命中错误态对拍用例 | 修改 |

---

# 第一部分 — 问题 1:小计输入列求和

## Task 1: computeAllFormulas 加统一 out 对象 + 小计回退取值(前端,TDD)

**Files:** Modify `cpq-frontend/src/pages/quotation/QuotationStep2.tsx`;Test 在该文件配套 test(若有 `QuotationStep2.test.ts`/`formulaEngine.test.ts` 夹具)否则新建 `__tests__/subtotalInputColumn.test.ts`。

- [ ] **Step 1: 写失败测试**

构造一个组件:有 `is_subtotal` 的 `INPUT_NUMBER` 列(汇率),无任何 FORMULA 列,两行 汇率=7.12 / 3。断言 `computeTabSubtotalsByColumn` 返回 `{汇率: 10.12}`(而非 0)。

```ts
import { describe, it, expect } from 'vitest';
import { computeTabSubtotalsByColumn } from '../QuotationStep2';

const comp: any = {
  componentCode: 'CP', code: 'CP',
  fields: [
    { name: '料号', field_type: 'INPUT_TEXT' },
    { name: '汇率', field_type: 'INPUT_NUMBER', is_subtotal: true },
  ],
  formulas: [],
  rows: [{ 料号: 'A', 汇率: 7.12 }, { 料号: 'B', 汇率: 3 }],
};

describe('computeTabSubtotalsByColumn — 输入型小计列求和', () => {
  it('INPUT_NUMBER 小计列跨行求和(非 0)', () => {
    const out = computeTabSubtotalsByColumn(comp);
    expect(out['汇率']).toBeCloseTo(10.12, 4);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/quotation/__tests__/subtotalInputColumn.test.ts`
Expected: FAIL(返回 0)。

- [ ] **Step 3: 实现 — computeAllFormulas 加 out 对象 + 放行提前 return**

给 `computeAllFormulas` 末尾加一个可选参数 `out?: { fieldValues?: Record<string, number>; errors?: Record<string, string> }`:
- 在已有的 `fieldValues` 收集循环(覆盖 BASIC_DATA/DATA_SOURCE/FIXED/INPUT **所有** field_type 分支)算完后,若 `out?.fieldValues` 存在则 `Object.assign(out.fieldValues, fieldValues)`。
- 两处提前 return(`!comp.fields||!comp.formulas` / `formulaFields.length===0`):改为**当组件存在 `is_subtotal` 且非 FORMULA 的字段时,不提前 return**,继续走 fieldValues 收集再返回(可在函数早段 `const needFieldValues = (comp.fields ?? []).some(f => f.is_subtotal && f.field_type !== 'FORMULA');`,据此决定是否放行)。返回值仍是 `Record<string, number|null>`(只含 results)。

- [ ] **Step 4: 实现 — computeTabSubtotalsByColumn 回退取值**

```ts
const fv: Record<string, number> = {};
const cache = computeAllFormulas(comp, row, allComponentSubtotals, quotationFields, pathCache, partNo, basicDataValues, undefined, globalVariableDefs, { fieldValues: fv });
for (const sf of subtotalFields) out[sf.name] += (cache[sf.name] ?? fv[sf.name] ?? 0);
```

> 注意 `computeAllFormulas` 现有参数个数较多,out 对象放在最后一个可选参;其余 6 个 callsite 不传该参,行为不变。

- [ ] **Step 5: 跑测试确认通过 + 类型**

Run:
```bash
cd cpq-frontend && npx vitest run src/pages/quotation/__tests__/subtotalInputColumn.test.ts
npx tsc --noEmit -p tsconfig.json
```
Expected: passed;tsc 0。

- [ ] **Step 6: Commit**

```bash
git add cpq-frontend/src/pages/quotation/QuotationStep2.tsx cpq-frontend/src/pages/quotation/__tests__/subtotalInputColumn.test.ts
git commit -m "fix(quotation): 小计累加对输入型列回退读 fieldValues(修恒0)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: 后端 FormulaCalculator 小计回退取值(TDD)

**Files:** Modify `cpq-backend/.../quotation/service/FormulaCalculator.java`;Test `FormulaCalculatorTest.java`(+ 视情况 `cross-tab-cases.json`)。

- [ ] **Step 1: 写失败测试**

加用例:组件有 `is_subtotal` 的 INPUT_NUMBER 列,两行;断言 `computeTabSubtotalsByColumn` 该列返回行值之和(非 0)。(参考既有 FormulaCalculatorTest 夹具构造方式。)

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && ./mvnw test -Dtest=FormulaCalculatorTest#<新用例>`(或项目既有测试命令)
Expected: FAIL(返回 0)。

- [ ] **Step 3: 实现**

`RowResult` 增 `Map<String, Double> fieldValues` 字段;`computeRows` 构造 `new RowResult(effKey, results)` 处改为同时带 `collectFieldValues` 的结果(该 map 已在 computeRows 内算出,传入即可)。`computeTabSubtotalsByColumn`(347-363)累加改:
```java
Double v = rr.formulaValues.containsKey(sf) ? rr.formulaValues.get(sf) : rr.fieldValues.get(sf);
if (v != null) sum += v;
```

- [ ] **Step 4: 跑测试 + 重启自检**

Run:
```bash
cd cpq-backend && ./mvnw test -Dtest=FormulaCalculatorTest
touch src/main/java/com/cpq/quotation/service/FormulaCalculator.java   # 触发 Quarkus 重启
sleep 7; curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health
```
Expected: 测试 passed;health 200(或既有正常码,不要 500)。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java cpq-backend/src/test/java/com/cpq/quotation/<test>.java
git commit -m "fix(formula): 后端小计累加对输入型列回退读 fieldValues

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: 详情页 ReadonlyProductCard 列小计同步修(AP-50)

**Files:** Modify `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx`

详情页列小计(架构指 line 304 `formulaCaches.reduce((s,fc)=>s+(fc[sf.name]??0))`)有同一 bug。让 `buildFormulaCache` 也回填 fieldValues(用同一 out 对象模式),列小计累加同样 `?? fieldValues`。

- [ ] **Step 1: 改 buildFormulaCache 产出 + 列小计回退**

`buildFormulaCache` 调 `computeAllFormulas` 时传 `{ fieldValues: fv }`,把 fv 一并返回/保存;列小计累加改为 `s + (fc[sf.name] ?? fcFieldValues[sf.name] ?? 0)`。

- [ ] **Step 2: 类型 + transform**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
npx esbuild src/pages/quotation/ReadonlyProductCard.tsx --loader:.tsx=tsx --bundle=false > /dev/null && echo TRANSFORM_OK
```
Expected: tsc 0;TRANSFORM_OK。

- [ ] **Step 3: Commit**

```bash
git add cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx
git commit -m "fix(quotation): 详情页列小计对输入型列回退取值(AP-50 三视图一致)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

# 第二部分 — 问题 4:编辑器标红 + 渲染报错

## Task 4: 编辑器标红 — 细 source 裸引用判红(前端,TDD)

**Files:** Modify `cpq-frontend/src/pages/component/formulaSerialize.ts` + `.test.ts`

- [ ] **Step 1: 写失败测试**

在 `formulaSerialize.test.ts` 加块(夹具:宿主 selfRowKeyFields=['料件'];source「元素」rowKeyFields=['料件','元素'](严格更细);source「投料」rowKeyFields=['料件'](同级)):

```ts
describe('parseFormulaSegments — 细 source 裸引用判红(需聚合)', () => {
  it('裸 [元素.单价](细 source,非 FN 内)→ red(needs-agg)', () => {
    const segs = parseFormulaSegments('[元素.单价]', tabsWithFinerYS, ['料件'], /*enforceMappable*/ true);
    const blk = segs.find(s => s.display?.includes('元素'));
    expect(blk?.color).toBe('red');
  });
  it('SUM([元素.单价]) 内的 [元素.单价](FN 内)→ 仍 blue', () => {
    const segs = parseFormulaSegments('SUM([元素.单价])', tabsWithFinerYS, ['料件'], true);
    const blk = segs.find(s => s.display?.includes('元素'));
    expect(blk?.color).toBe('blue');
  });
  it('裸 [投料.重量](同级 source)→ 仍 blue', () => {
    const segs = parseFormulaSegments('[投料.重量]', tabsWithSameTL, ['料件'], true);
    const blk = segs.find(s => s.display?.includes('投料'));
    expect(blk?.color).toBe('blue');
  });
});
```

> 以 `parseFormulaSegments` 实际签名为准(架构标 597-641);若它不直接收 selfRowKeyFields/enforceMappable,按现有签名传参并在内部传递给 classifyRefSegment。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts`
Expected: FAIL(裸细引用当前判蓝)。

- [ ] **Step 3: 实现 — parseFormulaSegments 加 fnDepth**

主循环维护 `fnDepth`:识别 `FN名(`(SUM/AVG/MAX/MIN/COUNT 紧跟 `(`,复用 expressionToTokens 既有 func+paren 识别)→ `fnDepth++`;对应 `)` → `fnDepth--`。把 `insideFn = fnDepth > 0` 作为新参传入 `classifyRefSegment`。

- [ ] **Step 4: 实现 — classifyRefSegment 加 insideFn + 细 source 红判定**

`classifyRefSegment` 新增参数 `insideFn: boolean`(给默认值 `false` 或同步改唯一 caller parseFormulaSegments)。在明细判色分支(733-738),小计/总计判定之后、原蓝 return 之前插:

```ts
// 细 source 裸引用(无 FN 聚合)→ 红(语义:需聚合)。FN 内 / 同级粗 source 不红。
if (enforceMappable && !insideFn) {
  const self = selfRowKeyFields ?? [];
  const src = tab.rowKeyFields ?? [];
  const srcStrictlyFiner = isSubset(self, src) && !isSubset(src, self);
  if (srcStrictlyFiner) return { kind: 'needs-agg', color: 'red' };
}
```

(`isSubset` 已存在 644-650。务必放在"既有 match 空判红"之后不冲突;同级 source `isSubset(src,self)` 为真 → srcStrictlyFiner=false → 不红 → 仍蓝。)

- [ ] **Step 5: 跑测试 + 类型 + transform**

Run:
```bash
cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts
npx tsc --noEmit -p tsconfig.json
npx esbuild src/pages/component/formulaSerialize.ts --loader:.tsx=tsx --bundle=false > /dev/null && echo TRANSFORM_OK
```
Expected: 全绿;tsc 0;TRANSFORM_OK。`FormulaRichInput`/`TabFieldMatrix` 透传 color 自动红(BLOCK_STYLE.red 已存在)。

- [ ] **Step 6: Commit**

```bash
git add cpq-frontend/src/pages/component/formulaSerialize.ts cpq-frontend/src/pages/component/formulaSerialize.test.ts
git commit -m "feat(formula): 编辑器对细 source 裸引用(需聚合)判红

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: 渲染报错(前端)— 多命中错误旁路 + ⚠ 单元格(TDD)

**Files:** Modify `formulaEngine.ts` + `QuotationStep2.tsx` + `ComponentCell.tsx`(+ CSS)

- [ ] **Step 1: 写失败测试(formulaEngine 旁路诊断)**

```ts
import { evaluateExpression } from '../utils/formulaEngine'; // 以实际导出为准
it('细 source 多命中 → 数值 0 且 outDiag.crossTabError 有原因', () => {
  const diag: { crossTabError?: string } = {};
  const v = evaluateExpression(/*含命中2行的 cross_tab_ref agg=NONE 的 token + ctx*/, ctxMultiHit, diag);
  expect(v).toBe(0);
  expect(diag.crossTabError).toBeTruthy();
});
```

(构造 ctx 使某 cross_tab_ref agg=NONE 命中 2 行;参考 formulaEngine 既有测试夹具。)

- [ ] **Step 2: 跑测试确认失败 → 实现 evaluateExpression outDiag**

`evaluateExpression` 加可选 `outDiag?: { crossTabError?: string }`;在 crossTabError 分支(279)写 `outDiag.crossTabError = '细项引用命中多行,请用 SUM 等聚合'`(带上 alias.field 更友好)。**数值仍走原 (null.x)→catch→0**,不改。

- [ ] **Step 3: computeAllFormulas 透传 errors**

`computeAllFormulas` 对每个 FORMULA 字段求值时传入局部 `diag`,catch 后若 `diag.crossTabError` 则 `out?.errors && (out.errors[name] = diag.crossTabError)`。返回值 results[name] 仍 null/0。

- [ ] **Step 4: ProductCard 产 preComputedErrors → CellContext**

`ProductCard` 在预算 `preComputedCaches` 处同样产出 `preComputedErrors[rowIndex]`(调 computeAllFormulas 传 `{ errors: {} }`),经 props/CellContext 下发为 `formulaErrors`。`ComponentCell` 的 `CellContext` 加可选 `formulaErrors?: Record<string,string>`(不传=旧行为)。

- [ ] **Step 5: ComponentCell FORMULA 分支渲染 ⚠**

FORMULA 分支(271-276)改:
```tsx
const err = ctx.formulaErrors?.[field.name];
if (err) return <span className="qt-formula-cell-error" title={err}>⚠</span>;
const val = formulaCache[field.name];
return <span className="qt-formula-cell-value">{val != null ? val : '—'}</span>;
```
CSS 加 `.qt-formula-cell-error { color:#cf1322; cursor:help; }`。

- [ ] **Step 6: 跑测试 + 类型 + transform**

Run:
```bash
cd cpq-frontend
npx vitest run src/utils/  # formulaEngine 相关
npx tsc --noEmit -p tsconfig.json
for f in utils/formulaEngine.ts pages/quotation/QuotationStep2.tsx pages/quotation/components/ComponentCell.tsx; do
  npx esbuild src/$f --loader:.tsx=tsx --bundle=false > /dev/null && echo "$f OK"; done
```
Expected: 测试 passed;tsc 0;全 OK。

- [ ] **Step 7: Commit**

```bash
git add cpq-frontend/src/utils/formulaEngine.ts cpq-frontend/src/pages/quotation/QuotationStep2.tsx cpq-frontend/src/pages/quotation/components/ComponentCell.tsx cpq-frontend/src/styles/*
git commit -m "feat(quotation): 细项多命中渲染 ⚠ 错误态(替代静默 0,求值零改)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: 渲染报错(后端)— RowContext 旁路 + snapshot errors

**Files:** Modify `FormulaCalculator.java` + `CardSnapshotService.java` + `cross-tab-cases.json` + `FormulaCalculatorTest.java`

- [ ] **Step 1: 写失败对拍用例**

`cross-tab-cases.json` 加"细 source 多命中"用例:期望 `value=0` **且** `errors` 含该字段原因。`FormulaCalculatorCrossTabFixtureTest` 断言 value=0 且 errors 非空;同时既有单命中用例断言 errors 为空(防误报)。

- [ ] **Step 2: 跑测试确认失败 → 实现旁路(evalCrossTab 不改)**

- `RowContext` 加 `Map<String,String> rowErrors`。
- `appendToken` cross_tab_ref 分支(159-166):捕获 ERR 时,除现有 `throw` 外,**先**把原因写 `ctx.rowErrors.put(currentFieldName, "细项引用命中多行,请用 SUM 等聚合")`(需确保 appendToken 上下文能拿到"当前求值字段名";若拿不到,在 computeRows 求值每个 FORMULA 字段的 try/catch 外层捕获并记入,等效)。
- `evalCrossTab`(230)**返回值/判定一字不改**(仍 `hits.size()>1 → ERR`)。
- `RowResult` 加 `Map<String,String> errors`,computeRows 落入。
- `CardSnapshotService.assembleTabsWithFormulaResults`(716-718)序列化 errors 进 snapshot `formulaResults[rowKey].errors`(结构向后兼容,缺字段=无错)。

- [ ] **Step 3: 跑测试 + 重启自检**

Run:
```bash
cd cpq-backend && ./mvnw test -Dtest=FormulaCalculatorCrossTabFixtureTest,FormulaCalculatorTest
touch src/main/java/com/cpq/quotation/service/FormulaCalculator.java; sleep 7
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8081/q/health
```
Expected: 测试 passed(含新对拍 + 既有零回归);health 正常码非 500。

- [ ] **Step 4: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java cpq-backend/src/test/resources/**/cross-tab-cases.json cpq-backend/src/test/java/com/cpq/**
git commit -m "feat(formula): 细项多命中错误旁路进 snapshot(evalCrossTab 求值零改,基线合规)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: 详情页 ReadonlyProductCard 渲染 ⚠(AP-50 第三视图)

**Files:** Modify `cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx`

详情页读 snapshot `formulaResults.errors`,FORMULA cell 同款 ⚠(与报价/核价视图一致)。

- [ ] **Step 1: 读 snapshot errors + 渲染 ⚠**

`buildFormulaCache`/读 snapshot 处取出 `errors`,FORMULA cell 渲染前先判 `err → <span className="qt-formula-cell-error" title={err}>⚠</span>`,否则原 `val != null ? val : '—'`。

- [ ] **Step 2: 类型 + transform**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
npx esbuild src/pages/quotation/ReadonlyProductCard.tsx --loader:.tsx=tsx --bundle=false > /dev/null && echo TRANSFORM_OK
```
Expected: tsc 0;TRANSFORM_OK。

- [ ] **Step 3: Commit**

```bash
git add cpq-frontend/src/pages/quotation/ReadonlyProductCard.tsx
git commit -m "feat(quotation): 详情页 FORMULA cell 渲染细项多命中 ⚠(AP-50 三视图一致)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 8: 全量自检 + E2E + 真机验收

**Files:** 无(运行验证),`quotation-flow.spec.ts` 视需要加断言。

- [ ] **Step 1: 前端全量单测 + 类型**

Run:
```bash
cd cpq-frontend
npx vitest run src/pages/component/formulaSerialize.test.ts src/pages/quotation/__tests__/subtotalInputColumn.test.ts src/utils/
npx tsc --noEmit -p tsconfig.json
```
Expected: 全绿;tsc 0。

- [ ] **Step 2: 改动 .tsx/.ts transform**

Run(逐个):QuotationStep2 / formulaEngine / formulaSerialize / ComponentCell / ReadonlyProductCard → 全 TRANSFORM_OK。

- [ ] **Step 3: 后端测试 + 重启**

Run:`./mvnw test -Dtest=FormulaCalculator*`(全绿);`touch` java 重启;health 正常码。

- [ ] **Step 4:(合并后)E2E 双 spec**

```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
# 若组合产品走该公式: e2e/composite-product-flow.spec.ts
```
Expected: `1 passed`;`加载中 final=0`;全 Tab=0;含输入型小计列 Tab footer 显真实值(非 ¥0.00);含非法裸细引用列渲染 ⚠(非 0/非 —)。

- [ ] **Step 5: 真机验收点**
  - 组件管理公式编辑器插裸 `[元素.单价]`(细 source)→ chip/富文本块**红**;`SUM([元素.单价])` → 蓝;
  - 报价单 `3e959537-...`:汇率/单重所在 Tab 列小计显**真实数值**;保存刷新(后端 snapshot)一致;详情页同列一致;
  - 含裸 `[元素.单价]` 的「材料费」单元格显 **⚠ + tooltip**(细项命中多行,请用 SUM 等聚合),详情页同款 ⚠;
  - 把公式改成 `(SUM([元素.重量(g)]/1000*[元素.单价]*(1+[元素.损耗率]/100))+[外购件.费用])*[组成用量]` → 料8 材料费 = **25.41**(不再 0/不再 ⚠)。

- [ ] **Step 6: 完成宣告(附"已自检"声明行)**

例:
> 前端 vitest 全绿 ✅;tsc 0 ✅;5 .tsx/.ts TRANSFORM_OK ✅;后端 FormulaCalculator* 全绿 + health 正常 ✅;E2E quotation-flow 1 passed + 加载中=0 + 输入小计非0 + 细引用 ⚠ ✅;真机三视图一致(用户验收)。

---

## 真机验收(用户)
1. 小计:汇率/单重等输入型小计列实时显真实求和(报价/详情一致);
2. 编辑器:裸细引用 `[元素.单价]` 标红、`SUM(...)` 蓝;
3. 渲染:裸细引用单元格显 ⚠ 提示(非静默 0);
4. 改用 SUM 写法后料8 材料费 = 25.41。

## 收尾
按 `cpq-auto-finish-merge-e2e-cleanup` 习惯:评审通过即自动合并 master + 跑双 E2E + 清理 worktree。
