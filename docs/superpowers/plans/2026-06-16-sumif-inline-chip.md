# SUMIF 表达式框内联 chip 化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 把 SUMIF 族从"独立侧边列表（保存时硬拼到公式尾、无运算符、混用即归 0）"改成"作为可见 chip 内联在公式表达式框光标处，可与运算符自由组合"，组件线 + EXCEL 线统一。

**Architecture:** SUMIF 在表达式框里就是文本 `SUMIF([源.条件字段]op'值' AND …, [源.值字段]*…)`，复用现有 `formulaSerialize.ts` 的 `FN([...])` → cross_tab_ref + targetExpr 基建，只新增「cond（predicate）这一段」的前端文本解析/序列化（镜像后端 `ConditionPredicateParser`）。`expressionToTokens` 把 `SUMIF(...)` 解析成带 `predicate` 的 cross_tab_ref token（组件线后端已支持该 token）；`tokensToDrawerExpression` 反向渲染；`parseFormulaSegments`/`FN_NAMES` 加 SUMIF 着色。删除 `sumifTokens` 侧状态。

**Tech Stack:** React + TypeScript（Vitest）；Playwright E2E。后端无需改（组件线 predicate token + EXCEL 线 SUMIF 文本解析均已就绪）。

**权威背景:** 现状缺陷 = `TabJoinFormulaDrawer.save()` 的 `allTokens = [...exprTokens, ...sumifTokens]` 把 SUMIF 无运算符贴到公式尾 → 混用归 0；SUMIF 不在表达式框可见。用户已确认：要 chip 内联 + 两条线统一。

**强制纪律（CLAUDE.md）:** 隔离 worktree（已建 `worktree-feat-sumif-inline-chip`）；协议级改动（`formulaSerialize.ts` / `expressionToTokens` / `tokensToDrawerExpression`）跑双 E2E spec；前端自检 tsc+curl；提交只 add 本次改动文件、禁止 `git add -A`。

---

## SUMIF 表达式框文本语法（全计划共用）

```
SUMIF([源.条件字段] op '字面量' [AND|OR [(]…[)]] , <值表达式>)
COUNTIF([源.条件字段] op '字面量' …)              # 单参，无值表达式
```
- cond 文法（与后端 `ConditionPredicateParser` 逐字一致）：`orExpr := andExpr ('OR' andExpr)*`；`andExpr := cmpExpr ('AND' cmpExpr)*`；`cmpExpr := '(' orExpr ')' | operand op operand`；`operand := [别名.字段] | '字符串' | 数字`；`op := = | != | <> | > | < | >= | <=`。
- `[别名.字段]`：cond 内**首个出现的别名**=source(A)→`sourceField`，其余别名→`hostField`（与后端口径一致）。
- `<值表达式>` 复用现有 targetExpr 语法（如 `[源.金额]` 或 `[宿主别名.数量] * [源.金额]`）。
- 序列化口径：`sourceField.field` → `[<sumif源别名>.<field>]`；`hostField.field` → `[<宿主别名>.<field>]`（宿主别名 = 被编辑组件 alias，调用方传入）；literal → 数字裸写 / 字符串加单引号。

产出 token（与 Phase 5 已落地的 `buildSumifToken` 同形）：
```ts
{ type:'cross_tab_ref', source:<componentId>, sourceLabel, agg:'SUM'|'COUNT'|'AVG'|'MIN'|'MAX',
  match:[], predicate:<ConditionPredicate>, targetExpr:<FormulaToken[]> | undefined }
```

---

## 文件结构

**前端（新增）**
- `cpq-frontend/src/utils/predicateText.ts` — predicate 文本 ↔ `ConditionPredicate` 互转（`parsePredicateText` / `serializePredicate`），镜像后端 `ConditionPredicateParser` + 反向。

**前端（修改）**
- `cpq-frontend/src/pages/component/formulaSerialize.ts` — `expressionToTokens`（SUMIF 族分支）/ `tokensToDrawerExpression`（带 predicate 的 cross_tab_ref → `SUMIF(...)`）/ `parseFormulaSegments` + `FN_NAMES`（加 SUMIF 族）。
- `cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx` — 构造器"插入"改为往表达式框插 `SUMIF(...)` 文本；删除 `sumifTokens`/`splitSumifTokens`/侧边预览列表/save 合并；reopen 不再拆分（SUMIF 随表达式串走）。

**测试（新增/扩展）**
- `cpq-frontend/src/utils/__tests__/predicateText.test.ts`
- `cpq-frontend/src/pages/component/__tests__/formulaSerializeSumif.test.ts`（round-trip：文本→token→文本）
- 扩 `e2e/quotation-flow.spec.ts` + `composite-product-flow.spec.ts`

---

## Phase 1 — 前端 predicate 文本解析器 + 序列化器（TDD）

### Task 1: predicateText.ts

**Files:**
- Create: `cpq-frontend/src/utils/predicateText.ts`
- Test: `cpq-frontend/src/utils/__tests__/predicateText.test.ts`

- [ ] **Step 1: 写失败测试**

```ts
import { describe, it, expect } from 'vitest';
import { parsePredicateText, serializePredicate } from '../predicateText';
import type { ConditionPredicate } from '../formulaEngine';

describe('parsePredicateText (镜像后端 ConditionPredicateParser)', () => {
  it('field = string literal', () => {
    expect(parsePredicateText("[其他费用.类型] = '管理费'")).toEqual({
      op: '=', lhs: { kind: 'sourceField', field: '类型' }, rhs: { kind: 'literal', value: '管理费' },
    });
  });
  it('first alias = source, others = host', () => {
    expect(parsePredicateText('[A.a] = [B.b]')).toEqual({
      op: '=', lhs: { kind: 'sourceField', field: 'a' }, rhs: { kind: 'hostField', field: 'b' },
    });
  });
  it('AND with number + parens', () => {
    const p = parsePredicateText("[A.类型]='管理费' AND [A.金额] > 1000");
    expect(p).toEqual({ bool: 'AND', children: [
      { op: '=', lhs: { kind: 'sourceField', field: '类型' }, rhs: { kind: 'literal', value: '管理费' } },
      { op: '>', lhs: { kind: 'sourceField', field: '金额' }, rhs: { kind: 'literal', value: '1000' } },
    ]});
  });
  it('!= / <> aliases', () => {
    expect((parsePredicateText("[A.x] <> '运费'") as any).op).toBe('<>');
  });
  it('malformed throws', () => {
    expect(() => parsePredicateText('[A.x] =')).toThrow();
  });
});

describe('serializePredicate (token → 文本, 用 sumif 源别名/宿主别名)', () => {
  const ctx = { sourceAlias: '其他费用', hostAlias: '来料' };
  it('round-trips a literal comparison', () => {
    const p: ConditionPredicate = { op: '=', lhs: { kind: 'sourceField', field: '类型' }, rhs: { kind: 'literal', value: '管理费' } };
    const s = serializePredicate(p, ctx);
    expect(s).toBe("[其他费用.类型] = '管理费'");
    expect(parsePredicateText(s)).toEqual(p);
  });
  it('serializes hostField with hostAlias + AND tree', () => {
    const p: ConditionPredicate = { bool: 'AND', children: [
      { op: '=', lhs: { kind: 'sourceField', field: 'a' }, rhs: { kind: 'hostField', field: 'b' } },
      { op: '>', lhs: { kind: 'sourceField', field: '金额' }, rhs: { kind: 'literal', value: '1000' } },
    ]};
    const s = serializePredicate(p, ctx);
    expect(s).toBe("[其他费用.a] = [来料.b] AND [其他费用.金额] > 1000");
  });
  it('numeric literal not quoted', () => {
    const p: ConditionPredicate = { op: '>', lhs: { kind: 'sourceField', field: '金额' }, rhs: { kind: 'literal', value: '1000' } };
    expect(serializePredicate(p, ctx)).toBe('[其他费用.金额] > 1000');
  });
});
```

- [ ] **Step 2: 运行确认失败**

Run: `cd cpq-frontend && npx vitest run src/utils/__tests__/predicateText.test.ts`
Expected: FAIL（模块不存在）。

- [ ] **Step 3: 实现 predicateText.ts**

实现要点（递归下降，与后端 `ConditionPredicateParser.java` 逐分支对齐；`ConditionPredicate`/`PredicateOperand` 从 `formulaEngine.ts` import）：
- `parsePredicateText(text): ConditionPredicate` — orExpr/andExpr/cmpExpr/operand；`[别名.字段]` 取最后一个 `.` 后为 field、记首个别名为 source；`'...'`→literal、裸数字→literal（存字符串）；op 读取按 `>= <= <> != = > <` 长优先；`matchKeyword('AND'|'OR')` 后须空白/括号/结尾；残留/缺操作数/缺括号 throw。
- `serializePredicate(p, { sourceAlias, hostAlias }): string` — Comparison → `<lhs> <op> <rhs>`；operand：sourceField→`[sourceAlias.field]`、hostField→`[hostAlias.field]`、literal→纯数字裸写否则 `'值'`（用 `/^-?\d+(\.\d+)?$/` 判数）；Bool → children 用 ` AND `/` OR ` 连接，**子节点是 Bool 时加括号**保证优先级与 round-trip。
- 单值字面量比较存为字符串；op 序列化用原文（`!=`/`<>` 各自保留——为 round-trip 稳定，序列化统一输出 `!=`，并让 parse 接受 `!=` 与 `<>`；测试里 `<>` 用例仅验 parse 接受，不验序列化往返 `<>`）。

- [ ] **Step 4: 运行确认通过**

Run: `cd cpq-frontend && npx vitest run src/utils/__tests__/predicateText.test.ts`
Expected: 全绿。

- [ ] **Step 5: tsc 自检 + Commit**

```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json   # 0 错误
git add cpq-frontend/src/utils/predicateText.ts cpq-frontend/src/utils/__tests__/predicateText.test.ts
git commit -m "feat(frontend-formula): predicate text parser + serializer (mirror backend ConditionPredicateParser)"
```

---

## Phase 2 — formulaSerialize 集成（文本 ↔ token round-trip）

### Task 2: expressionToTokens 解析 SUMIF 族 + tokensToDrawerExpression 反向

**Files:**
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.ts`
- Test: `cpq-frontend/src/pages/component/__tests__/formulaSerializeSumif.test.ts`

- [ ] **Step 1: 写失败测试（round-trip + 与运算符组合）**

```ts
import { describe, it, expect } from 'vitest';
import { expressionToTokens, tokensToDrawerExpression } from '../formulaSerialize';
import type { TabDef } from '../../../services/tabJoinFormulaService';

// 两个页签：其他费用(源, componentId=cid-fee)、来料(宿主自身, componentId=cid-host)
const tabDefs: TabDef[] = [
  { alias: '来料', tabKey: 'cid-host', componentId: 'cid-host', componentName: '来料', rowKeyFields: ['料件'], detailFields: ['数量','金额'], allFields: ['料件','数量','金额'], subtotalCols: [], self: true },
  { alias: '其他费用', tabKey: 'cid-fee', componentId: 'cid-fee', componentName: '其他费用', rowKeyFields: ['项次'], detailFields: ['费用','比例'], allFields: ['项次','类型','费用','比例'], subtotalCols: [], self: false },
];

describe('SUMIF in formulaSerialize', () => {
  it('parses SUMIF text → cross_tab_ref token with predicate + targetExpr', () => {
    const toks = expressionToTokens("SUMIF([其他费用.类型]='管理费', [其他费用.费用])", tabDefs, ['料件'], 'cid-host');
    const ct = toks.find((t: any) => t.type === 'cross_tab_ref');
    expect(ct).toBeTruthy();
    expect((ct as any).agg).toBe('SUM');
    expect((ct as any).predicate).toEqual({ op: '=', lhs: { kind: 'sourceField', field: '类型' }, rhs: { kind: 'literal', value: '管理费' } });
    expect((ct as any).source).toBe('cid-fee');
  });

  it('round-trips SUMIF combined with an operator', () => {
    const expr = "[来料.数量] * SUMIF([其他费用.类型]='管理费', [其他费用.费用])";
    const toks = expressionToTokens(expr, tabDefs, ['料件'], 'cid-host');
    const back = tokensToDrawerExpression(toks, tabDefs);
    // 归一化空格后应等价
    expect(back.replace(/\s+/g, ' ').trim()).toBe(expr.replace(/\s+/g, ' ').trim());
  });

  it('COUNTIF single-arg parses with agg COUNT and no targetExpr', () => {
    const toks = expressionToTokens("COUNTIF([其他费用.类型]='管理费')", tabDefs, ['料件'], 'cid-host');
    const ct = toks.find((t: any) => t.type === 'cross_tab_ref');
    expect((ct as any).agg).toBe('COUNT');
    expect((ct as any).predicate).toBeTruthy();
  });
});
```

- [ ] **Step 2: 运行确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/__tests__/formulaSerializeSumif.test.ts`
Expected: FAIL（当前 SUMIF 不被解析）。

- [ ] **Step 3: 实现（formulaSerialize.ts）**

先读懂现有 `expressionToTokens` 的 `FN([...])` 分支（SUM/AVG/… → cross_tab_ref + targetExpr，含单列快捷 + 行级 targetExpr）与 `tokensToDrawerExpression` 的 `cross_tab_ref` 分支（`renderTargetExprParts`）。在其上加：
- **解析**：识别 `SUMIF|COUNTIF|AVGIF|MINIF|MAXIF`（大小写不敏感）。用 top-level 逗号把括号内切成 `cond` 与（可选）`valueExpr`；`cond` 走 `parsePredicateText`（import 自 `../../utils/predicateText`）；`valueExpr` 复用现有 targetExpr 解析逻辑产出 `targetExpr: FormulaToken[]`；`source` = cond/valueExpr 中首个 `[别名.字段]` 的别名 → 经现有 alias→componentId 解析；`agg` 由 `FUNC_TO_AGG`（SUMIF→SUM…）；产出带 `predicate` 的 cross_tab_ref（`match: []`）。COUNTIF 无 valueExpr → 无 targetExpr。
- **序列化**：`tokensToDrawerExpression` 的 cross_tab_ref 分支，若 `token.predicate` 存在 → 输出 `${AGG_TO_FUNC(agg)}(${serializePredicate(predicate, {sourceAlias, hostAlias})}${valueExpr ? ', ' + renderTargetExprParts(...) : ''})`，其中 `AGG_TO_FUNC`：SUM→SUMIF…；`sourceAlias` = 该 cross_tab_ref source 解析出的页签名；`hostAlias` = 宿主（self）页签 alias（从 tabDefs 里 `self===true` 取，没有则空串容错）。predicate 为空时维持现有 `SUM([...])` 渲染（零回归）。

- [ ] **Step 4: 运行确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/component/__tests__/formulaSerializeSumif.test.ts`
Expected: 全绿。

- [ ] **Step 5: formulaSerialize 既有测试回归（关键，防破坏 cross_tab/KSUM）**

Run: `cd cpq-frontend && npx vitest run src/pages/component`
Expected: 既有 `formulaSerialize`/`buildCrossTabRows` 等测试 0 失败（predicate 缺省路径逐字不变）。

- [ ] **Step 6: tsc + Commit**

```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
git add cpq-frontend/src/pages/component/formulaSerialize.ts cpq-frontend/src/pages/component/__tests__/formulaSerializeSumif.test.ts
git commit -m "feat(frontend-formula): SUMIF family <-> cross_tab_ref(predicate) round-trip in formulaSerialize"
```

---

## Phase 3 — chip 着色（parseFormulaSegments / FN_NAMES）

### Task 3: SUMIF 族进 FN_NAMES，表达式框正确着色为函数

**Files:**
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.ts`（`FN_NAMES` 约 :882 + `parseFormulaSegments`）
- Test: 扩 `formulaSerializeSumif.test.ts`

- [ ] **Step 1: 写失败测试**

```ts
import { parseFormulaSegments } from '../formulaSerialize';
it('parseFormulaSegments colors SUMIF as a function name (not unknown)', () => {
  const segs = parseFormulaSegments("SUMIF([其他费用.类型]='管理费', [其他费用.费用])", tabDefs, ['料件'], true);
  const sumifSeg = segs.find((s: any) => s.text.toUpperCase().includes('SUMIF'));
  expect(sumifSeg).toBeTruthy();
  expect(sumifSeg!.color).not.toBe('error'); // 不应被标红为未知
});
```

- [ ] **Step 2: 运行确认失败 / 现状**

Run: `cd cpq-frontend && npx vitest run src/pages/component/__tests__/formulaSerializeSumif.test.ts -t "colors SUMIF"`
Expected: 失败或 SUMIF 被当未知词标红。

- [ ] **Step 3: 实现**

`FN_NAMES`（:882）加入 `'SUMIF','COUNTIF','AVGIF','MINIF','MAXIF'`；确认 `parseFormulaSegments` 对函数名 + 其后括号/`[别名.字段]`/字面量/运算符的着色分支能覆盖 SUMIF 内部（字符串字面量 `'管理费'`、`=`/`>` 等比较符不被标红）。若 cond 里的比较运算符 `= > <` 等被着色逻辑当非法，放宽：在 `insideFn`（SUMIF 族）时允许比较运算符与字符串字面量。

- [ ] **Step 4: 运行确认通过 + 回归**

Run: `cd cpq-frontend && npx vitest run src/pages/component`
Expected: 新着色用例绿 + 既有 segments 测试 0 失败。

- [ ] **Step 5: tsc + Commit**

```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
git add cpq-frontend/src/pages/component/formulaSerialize.ts cpq-frontend/src/pages/component/__tests__/formulaSerializeSumif.test.ts
git commit -m "feat(frontend-formula): recognize SUMIF family in parseFormulaSegments coloring"
```

---

## Phase 4 — 构造器改插表达式框 + 删除侧状态

### Task 4: 构造器"插入"改为往表达式框插 SUMIF 文本；移除 sumifTokens 侧状态

**Files:**
- Modify: `cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx`
- Test: 更新 `cpq-frontend/src/pages/template/__tests__/sumifTokenBuild.test.ts`

- [ ] **Step 1: 改测试反映新行为**

`buildSumifToken` 保留（仍用于产出 token 结构的单测），但新增/改为测一个纯函数 `buildSumifText(input): string`（用 `serializePredicate` + targetExpr 文本拼成 `SUMIF(...)`）；删除 `splitSumifTokens` 相关测试（侧状态移除）。给 `buildSumifText` 写用例：SUMIF + 条件 + 值字段 → `SUMIF([源.类型]='管理费', [源.费用])`；COUNTIF → `COUNTIF([源.类型]='管理费')`。

- [ ] **Step 2: 运行确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/template/__tests__/sumifTokenBuild.test.ts`
Expected: FAIL（`buildSumifText` 不存在）。

- [ ] **Step 3: 实现 + 拆除侧状态**

- 新增导出纯函数 `buildSumifText(input: { func, sourceAlias, predicate, valueFieldRefs })` → 用 `serializePredicate` 拼 cond + `, ` + 值表达式（值字段 → `[源别名.字段]` 用 `+` 连接，多个时；COUNTIF 无值）。
- 构造器「插入」按钮：调用 `buildSumifText` 得到文本 → `insertAtCursor(text)` 插进表达式框（光标处），并 `message.success('已插入 SUMIF，可在表达式框继续编辑/加运算符')`。
- **移除**：`sumifTokens` / `setSumifTokens` 状态、侧边"已追加 token"预览列表 JSX、`save()` 里的 `[...exprTokens, ...sumifTokens]`（改回只用 `exprTokens`，因 SUMIF 现在就在表达式串里被 `expressionToTokens` 解析）、`splitSumifTokens` import 及 open 时拆分回填逻辑（reopen 时 SUMIF 已随 `tokensToDrawerExpression` 渲染进表达式串，无需侧状态）。
- 构造器面板自身（函数/源/条件/值字段编辑器）保留，仅把"产出物"从侧 token 改为插入文本。
- 组件线 + EXCEL 线统一：两条线"插入"都走 `insertAtCursor(buildSumifText(...))`；EXCEL 线本就解析文本，组件线靠 `expressionToTokens`（Phase 2）解析。

- [ ] **Step 4: 运行确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/template/__tests__/sumifTokenBuild.test.ts`
Expected: 全绿。

- [ ] **Step 5: 全前端自检（CLAUDE.md 强制）**

Run:
```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json   # 0 错误
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/template/TabJoinFormulaDrawer.tsx   # 200
npx vitest run src/pages/component src/pages/template src/utils   # SUMIF + 序列化 + 既有全绿
```

- [ ] **Step 6: Commit**

```bash
git add cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx cpq-frontend/src/pages/template/__tests__/sumifTokenBuild.test.ts
git commit -m "feat(ui): SUMIF builder inserts inline text into expression box; remove side-state list (component+excel unified)"
```

---

## Phase 5 — E2E + 验收

### Task 5: 双 E2E spec（合并后跑）+ 回归

**Files:**
- Modify: `cpq-frontend/e2e/quotation-flow.spec.ts` + `composite-product-flow.spec.ts`（按需）

- [ ] **Step 1: 跑现有双 spec 证明零回归**（注：dev server 服务 master，E2E 须合并后跑；见收尾）

```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected: `passed` + `'加载中' final count = 0`。

- [ ] **Step 2: （可行则）配一个 SUMIF inline 公式验证 chip 可见 + 求值**

UI：组件公式抽屉，用构造器插入 SUMIF → 确认表达式框里出现 `SUMIF(...)` chip（可见、可与运算符组合）→ 保存 → 报价单渲染该列分行出值、无"加载中"。受测试数据所限做不到则如实记录，以单测 round-trip + 后端既有覆盖为据。

- [ ] **Step 3: 截图证据 + Commit（若改了 spec）**

---

## 收尾

- [ ] 全前端 `npx vitest run`（0 失败）+ `npx tsc --noEmit`（0 错误）。
- [ ] 更新 `docs/RECORD.md`（合并后在工作区追加，不在分支改 RECORD）。
- [ ] 走 `superpowers:finishing-a-development-branch`：合并 master → dev server 服务合并代码 → 跑 E2E → 删 worktree + 分支。

---

## Self-Review

- **覆盖**：cond 文本解析/序列化（T1）；文本↔token round-trip + 与运算符组合（T2）；chip 着色（T3）；构造器插文本 + 删侧状态 + 两线统一（T4）；E2E（T5）✅。
- **零回归**：predicate 缺省路径 `formulaSerialize` 逐字不变（T2 Step5 回归）；既有 cross_tab/KSUM 不受影响 ✅。
- **类型一致**：`ConditionPredicate`/`PredicateOperand`（formulaEngine.ts）贯穿；`parsePredicateText`/`serializePredicate`/`buildSumifText`/`FUNC_TO_AGG`/`AGG_TO_FUNC` 命名一致 ✅。
- **占位**：E2E Step2 的真机验证依赖测试数据，标注为尽力而为 ✅。
