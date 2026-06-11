# 页签连表公式 — 行键宿主分组（包含关系）重设计 实施计划（批 3 / 需求 1）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 NORMAL/SUBTOTAL 页签连表公式按「宿主组件行键分组 + 集合包含（⊆/⊇）」对齐被引用页签：粗/同级 source 广播、细 source 强制 `FN()` 单列聚合（SUM/AVG/MAX/MIN/COUNT）、不可比/空键 source 置灰；试算改走 token 渲染引擎（试算=渲染逐行对拍）。

**Architecture:** 改动集中在「序列化桥」`formulaSerialize.ts`（match 配对算法 + FN 函数语法 lexer/状态机 + 回显归一）、置灰 UI `TabFieldMatrix.tsx`（宿主可比判定 + FN 录入）、前后端 mappability 双 validator（空 match 拒）、后端新增 token 试算端点（复用 `assembleTabsWithFormulaResults` + 草稿公式/行键双注入）。**求值引擎前后端均不改**（只读 `token.match`+`token.agg`），靠 `cross-tab-cases.json` 共享夹具锁前后端一致 + `CardSnapshot*Test` 锁三视图一致。

**Tech Stack:** 前端 React + TS + Vitest（`*.test.ts`）；后端 Java 17 + Quarkus + JUnit5 + `@QuarkusTest`；前后端共享夹具 `cross-tab-cases.json`。

**前置基线（v6-R）:** 本计划必须在 **批 2 已合并的 master** 起的 worktree 内执行（已满足：worktree `worktree-tabjoin-batch3-req1` HEAD=`1e8d539` 含批 2）。

**🔴 决策已拍死（spec v5/v6，动代码前不再讨论）:**
- H：NORMAL/SUBTOTAL 试算走 token 引擎（复用渲染装配），`TabJoinPlanEvaluator` 仅留 EXCEL。
- I：`FN([alias.field])` 本期**只做单列**；`FN(` 内出现运算符/多引用 → 报错（复合 targetExpr 留二期）。
- J：round-trip 归一——`agg=FN`（含 SUM）统一回显 `FN([alias.field])`，**SUM 不再回显 `(总计)`**（解析仍容旧串）。
- N：草稿试算**双注入**——草稿 `tokens` + 草稿 `selfRowKeyFields`，后端覆盖宿主 `rkfByComp`。

---

## 关键现状坐标（实地核查，执行时按此定位）

**前端**
- `cpq-frontend/src/pages/component/formulaSerialize.ts`：`buildMatch`（:40 位置 zip）、`lex`（:76，遇字母即抛错）、`expressionToTokens`（:143 线性发射）、`tokensToDrawerExpression`（:288，agg!=NONE 回显 `(总计)`）、`checkMappable`（:375，旧规则"≥2 NONE 拒"）。
- `cpq-frontend/src/pages/component/formulaSerialize.test.ts`（755 行）：需改的回显断言在 :309（`[COMP_RL.金额(总计)]`）、:323（`[COMP_RL(总计)]`）；`buildMatch` 现有断言 :217/:228/:233/:241/:246/:251。
- `cpq-frontend/src/utils/formulaEngine.ts`：`evaluateExpression` cross_tab_ref 分支（:251，只读 `match`+`agg`，**不改**）。
- `cpq-frontend/src/pages/template/tabjoin/TabFieldMatrix.tsx`：`parseActiveRowKeySig`（:11 取首明细令牌锁签名——**废**）、置灰逻辑（:60 activeSig / :107 sameClass / :174 明细 chip）、UI Props（:49，**新增 selfRowKeyFields**）。
- `cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx`：持 `selfRowKeyFields`（:37/:50），用于 `expressionToTokens`（:143）+ `checkMappable`（:150），**未转发给 TabFieldMatrix**。函数工具条已能插 `SUM()`（:266 一带）。
- `cpq-frontend/src/pages/component/ComponentManagement.tsx`：`selfRowKeyFields={rowKeyFields}` 传给 Drawer（:1260）。
- `TabDef`（`cpq-frontend/src/services/tabJoinFormulaService.ts:3`）：`{alias, tabKey, componentId?, componentName?, componentType?, sortOrder?, rowKeyFields[], detailFields[], subtotalCols[]}`。
- `FormulaToken`（`cpq-frontend/src/pages/component/types.ts`）：cross_tab_ref 字段 `source/sourceLabel/target/agg/match`。

**后端**
- `cpq-backend/.../quotation/service/FormulaCalculator.java`：`evalCrossTab`（:206，只读 `token.match`/`token.agg`，空 match 时 for 不执行→全行命中）、`computeRowKey`（:446）。
- `cpq-backend/.../component/formula/TokenMappabilityValidator.java`：`Result validate(List<Map<String,Object>> expr)`（旧规则"≥2 NONE 拒"）。
- `cpq-backend/.../quotation/service/CardSnapshotService.java`：`refreshQuoteCardValues`（:1137）、`assembleTabsWithFormulaResults`（:672，持久化解耦，已有 test seam `assembleTabsWithFormulaResultsForTest` :873）、`loadRowKeyFieldsNode`（:1292）、`rkfByComp` 组装（:679）、`buildTabNode.formulaResults`（逐行 `{rowKey, values}`）。
- 试算旧链（EXCEL 保留）：`POST /api/cpq/components/{id}/dry-run` → `ComponentTabJoinResource.dryRun`（:65）→ `ComponentSampleCardService.dryRunForComponent`（:132）→ `ExcelViewService.dryRunTabFormula`（:718）→ `TabJoinPlanEvaluator.evaluateColumn`。
- 夹具：`cpq-backend/src/test/resources/cross-tab-cases.json` ↔ `cpq-frontend/src/utils/__fixtures__/cross-tab-cases.json`（前后端必须同步，`FormulaCalculatorCrossTabFixtureTest` 加载后端份）。
- token 落库：camelCase 键，`match` 元素键 `{a,b}`，`agg` 大写枚举。

---

## 实施顺序（依赖）

```
T1 buildMatch 交集配对 ─┐
T2 lexer 函数名 token ──┼─→ T3 FN 状态机 ─→ T4 回显归一 ─→ T5 checkMappable+comparable(前端)
                        │
T6 TabFieldMatrix comparable+prop链(可单测部分) ─→ T7 TabFieldMatrix UI(细source聚合录入/置灰)
T8 后端 validator 空match拒 + comparable(Java) + evalCrossTab 防御
T9 后端 token 试算端点 + 草稿双注入（命门0 对拍）
T10 cross-tab-cases.json 宿主分组新用例（前后端同跑）
T11 三视图回归 + 全量自检 + 文档回写
```

T1–T5 纯前端序列化、可独立 TDD；T6–T7 置灰 UI；T8 后端校验；T9 后端试算（最重）；T10 夹具；T11 收尾。每个 Task 末尾 commit。

---

## Task 1：`buildMatch` 位置配对 → 公共字段名交集配对

**Files:**
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.ts:40-51`
- Test: `cpq-frontend/src/pages/component/formulaSerialize.test.ts`（新增 describe 块）

- [ ] **Step 1: 写失败测试**（追加到 test 文件末尾，import 已含 `expressionToTokens`）

```ts
// ── Task 1: buildMatch 公共字段名交集配对（顺序无关） ──
describe('buildMatch — 公共字段名交集配对', () => {
  // host=投料[子件]，source=加工[工序,子件] → 应配对公共字段 子件，而非位置 zip 错配 工序↔子件
  const tabs: TabDef[] = [
    { alias: 'JG', tabKey: 'jg', componentId: 'cid-jg', componentName: '加工',
      rowKeyFields: ['工序', '子件'], detailFields: ['工时'], subtotalCols: [] },
  ];
  it('host[子件] × source[工序,子件] → 配对 {a:子件,b:子件}（非位置 zip）', () => {
    const t = expressionToTokens('SUM([JG.工时])', tabs, ['子件']);
    expect((t[0] as any).match).toEqual([{ a: '子件', b: '子件' }]);
  });
  it('乱序同集 host[A,B] × source[B,A] → {a:A,b:A},{a:B,b:B}（顺序无关、按 host 序）', () => {
    const tabs2: TabDef[] = [
      { alias: 'X', tabKey: 'x', componentId: 'cid-x', rowKeyFields: ['B', 'A'],
        detailFields: ['v'], subtotalCols: [] },
    ];
    const t = expressionToTokens('SUM([X.v])', tabs2, ['A', 'B']);
    expect((t[0] as any).match).toEqual([{ a: 'A', b: 'A' }, { a: 'B', b: 'B' }]);
  });
  it('无公共字段 → match=[]（交给 validator 拒）', () => {
    const tabs3: TabDef[] = [
      { alias: 'Y', tabKey: 'y', componentId: 'cid-y', rowKeyFields: ['料号'],
        detailFields: ['v'], subtotalCols: [] },
    ];
    const t = expressionToTokens('SUM([Y.v])', tabs3, ['工序']);
    expect((t[0] as any).match).toEqual([]);
  });
});
```

> 注：这些用例用 `SUM(...)` 包裹（Task 3 后才解析）。**先只验证 `buildMatch` 算法**：临时改用裸 `[JG.工时(总计)]` 旧语法跑（`expressionToTokens('[JG.工时(总计)]', tabs, ['子件'])`），Task 3 完成后再切回 `SUM(...)`。**或**把本 Task 测试写成直接调内部 `buildMatch`——但它非 export。**采用前者**：本步用 `(总计)` 旧语法，断言 match 配对。

修正本 Task 测试为 `(总计)` 语法（不依赖 Task 3）：

```ts
describe('buildMatch — 公共字段名交集配对', () => {
  const tabs: TabDef[] = [
    { alias: 'JG', tabKey: 'jg', componentId: 'cid-jg', componentName: '加工',
      rowKeyFields: ['工序', '子件'], detailFields: ['工时'], subtotalCols: [] },
  ];
  it('host[子件] × source[工序,子件] → 配对 {a:子件,b:子件}', () => {
    const t = expressionToTokens('[JG.工时(总计)]', tabs, ['子件']);
    expect((t[0] as any).match).toEqual([{ a: '子件', b: '子件' }]);
  });
  it('乱序同集 host[A,B] × source[B,A] → 按 host 序 {A,A},{B,B}', () => {
    const tabs2: TabDef[] = [{ alias: 'X', tabKey: 'x', componentId: 'cid-x',
      rowKeyFields: ['B', 'A'], detailFields: ['v'], subtotalCols: [] }];
    const t = expressionToTokens('[X.v(总计)]', tabs2, ['A', 'B']);
    expect((t[0] as any).match).toEqual([{ a: 'A', b: 'A' }, { a: 'B', b: 'B' }]);
  });
  it('无公共字段 → match=[]', () => {
    const tabs3: TabDef[] = [{ alias: 'Y', tabKey: 'y', componentId: 'cid-y',
      rowKeyFields: ['料号'], detailFields: ['v'], subtotalCols: [] }];
    const t = expressionToTokens('[Y.v(总计)]', tabs3, ['工序']);
    expect((t[0] as any).match).toEqual([]);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts -t "交集配对"`
Expected: FAIL（现 `buildMatch` 位置 zip：host[子件](len1) × source[工序,子件] → `{a:工序,b:子件}` 错配）。

- [ ] **Step 3: 改实现**（替换 `buildMatch` :40-51）

```ts
/**
 * Build match[] by COMMON ROW-KEY FIELD NAME intersection (order-independent).
 * For each field f in selfRowKeyFields that also appears in source rowKeyFields,
 * emit { a: f, b: f }. Order follows selfRowKeyFields (host) for determinism.
 * No common field → [] (validator rejects empty match downstream).
 */
function buildMatch(
  tabRowKeyFields: string[],
  selfRowKeyFields: string[] | undefined,
): Array<{ a: string; b: string }> {
  if (!tabRowKeyFields.length || !selfRowKeyFields?.length) return [];
  const sourceSet = new Set(tabRowKeyFields);
  const result: Array<{ a: string; b: string }> = [];
  for (const f of selfRowKeyFields) {
    if (sourceSet.has(f)) result.push({ a: f, b: f });
  }
  return result;
}
```

同步更新文件头注释（:23-26 的「Aligned positionally」段）改为「common field-name intersection」。

- [ ] **Step 4: 跑测试确认通过 + 旧 buildMatch 断言回归**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts`
Expected: 新 3 例 PASS。**旧断言 :217/:228/:241**（`[{a:料号,b:料号}]`，同序同集）仍 PASS。**旧断言 :233**（如属乱序/位置依赖）若失败，按"公共字段名"语义更新其预期（同序同集不变；若该用例本是位置配对的乱序场景，改为交集预期）。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/component/formulaSerialize.ts cpq-frontend/src/pages/component/formulaSerialize.test.ts
git commit -m "feat(tabjoin): buildMatch 位置配对→公共行键字段名交集配对(顺序无关)"
```

---

## Task 2：lexer 识别函数名 token（SUM/AVG/MAX/MIN/COUNT）

**Files:**
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.ts`（`RawToken` :64-71 + `lex` :76-130）
- Test: `cpq-frontend/src/pages/component/formulaSerialize.test.ts`

- [ ] **Step 1: 写失败测试**

```ts
// ── Task 2: lexer 函数名 token ──
import { __lexForTest } from './formulaSerialize'; // 见 Step 3 导出
describe('lex — 函数名 token', () => {
  it('SUM( 识别为 func token，不再抛"无法识别字符"', () => {
    expect(() => __lexForTest('SUM([A.f])')).not.toThrow();
  });
  it('大小写不敏感 avg → AVG', () => {
    const raw = __lexForTest('avg([A.f])');
    expect(raw[0]).toEqual({ kind: 'func', name: 'AVG' });
  });
  it('非函数字母仍抛错', () => {
    expect(() => __lexForTest('FOO([A.f])')).toThrow(/无法识别/);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts -t "函数名 token"`
Expected: FAIL（现遇 `S` 抛"无法识别的字符"；`__lexForTest` 未导出）。

- [ ] **Step 3: 改实现**

`RawToken` 联合追加（:64-71）：

```ts
  | { kind: 'func'; name: string }           // SUM/AVG/MAX/MIN/COUNT
```

`lex` 内、在「Numeric literals」分支**之前**插入字母识别（:117 前）：

```ts
    // Function names: SUM/AVG/MAX/MIN/COUNT (case-insensitive)
    if (/[A-Za-z]/.test(ch)) {
      let word = '';
      while (i < expr.length && /[A-Za-z]/.test(expr[i])) word += expr[i++];
      const upper = word.toUpperCase();
      if (['SUM', 'AVG', 'MAX', 'MIN', 'COUNT'].includes(upper)) {
        tokens.push({ kind: 'func', name: upper });
        continue;
      }
      throw new Error(`表达式中含有无法识别的标识符 '${word}'（位置 ${i - word.length}）`);
    }
```

文件末尾导出测试桩：

```ts
/** test-only：暴露 lex 给单测 */
export const __lexForTest = (expr: string) => lex(expr);
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts -t "函数名 token"`
Expected: PASS（3 例）。全量 `npx vitest run src/pages/component/formulaSerialize.test.ts` 仍全绿。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/component/formulaSerialize.ts cpq-frontend/src/pages/component/formulaSerialize.test.ts
git commit -m "feat(tabjoin): lexer 识别 SUM/AVG/MAX/MIN/COUNT 函数名 token"
```

---

## Task 3：`expressionToTokens` 状态机折叠 `FN([alias.field])` → cross_tab_ref agg=FN（单列收口）

**Files:**
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.ts:143-275`（`expressionToTokens` 主循环）
- Test: `cpq-frontend/src/pages/component/formulaSerialize.test.ts`

- [ ] **Step 1: 写失败测试**

```ts
// ── Task 3: FN() 状态机 ──
describe('expressionToTokens — FN() 单列聚合', () => {
  const tabs: TabDef[] = [
    { alias: 'JG', tabKey: 'jg', componentId: 'cid-jg', componentName: '加工',
      rowKeyFields: ['工序', '子件'], detailFields: ['工时'], subtotalCols: [] },
  ];
  it('SUM([JG.工时]) → 单个 cross_tab_ref agg=SUM，吞外层括号', () => {
    const t = expressionToTokens('SUM([JG.工时])', tabs, ['子件']);
    expect(t).toHaveLength(1);
    expect(t[0]).toMatchObject({ type: 'cross_tab_ref', source: 'cid-jg', target: '工时',
      agg: 'SUM', match: [{ a: '子件', b: '子件' }] });
  });
  it('AVG/MAX/MIN/COUNT 各自映射 agg', () => {
    for (const fn of ['AVG', 'MAX', 'MIN', 'COUNT'] as const) {
      const t = expressionToTokens(`${fn}([JG.工时])`, tabs, ['子件']);
      expect(t[0]).toMatchObject({ type: 'cross_tab_ref', agg: fn });
    }
  });
  it('外层算式保留：[本] * SUM([JG.工时]) → field, op, cross_tab_ref', () => {
    const t = expressionToTokens('[本] * SUM([JG.工时])', tabs, ['子件']);
    expect(t.map(x => x.type)).toEqual(['field', 'operator', 'cross_tab_ref']);
  });
  it('FN 内运算符 → 报错（单列收口 v5-I）', () => {
    expect(() => expressionToTokens('SUM([JG.工时]+[JG.工时])', tabs, ['子件']))
      .toThrow(/只支持单列|单列|不支持/);
  });
  it('FN 内多引用 → 报错', () => {
    expect(() => expressionToTokens('SUM([JG.工时][JG.工时])', tabs, ['子件']))
      .toThrow(/只支持单列|单列|不支持/);
  });
  it('FN 内非明细（裸字段）→ 报错', () => {
    expect(() => expressionToTokens('SUM([本])', tabs, ['子件'])).toThrow();
  });
  it('旧 [JG.工时(总计)] 仍解析为 agg=SUM（兼容）', () => {
    const t = expressionToTokens('[JG.工时(总计)]', tabs, ['子件']);
    expect(t[0]).toMatchObject({ type: 'cross_tab_ref', agg: 'SUM', target: '工时' });
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts -t "FN() 单列聚合"`
Expected: FAIL（现循环把 `func` raw token 当 default 跳过 / 不识别）。

- [ ] **Step 3: 改实现**

把 `expressionToTokens` 的 `for (const raw of rawTokens)` 改为带前瞻的索引循环。在 `bracket_expr` 抽 cross-tab 的逻辑抽成 helper `parseCrossTabDetail(alias, fieldPart, isAgg, aggFn, tabDefs, selfRowKeyFields)` 复用。新增 `func` 分支：

```ts
  const rawTokens = lex(expr);
  const result: FormulaToken[] = [];
  let k = 0;
  while (k < rawTokens.length) {
    const raw = rawTokens[k];
    if (raw.kind === 'func') {
      // 期望序列：func '(' bracket_expr ')'，且 bracket_expr 必须是 [alias.field] 明细
      const open = rawTokens[k + 1];
      const inner = rawTokens[k + 2];
      const close = rawTokens[k + 3];
      if (!open || open.kind !== 'paren_open')
        throw new Error(`函数 ${raw.name} 后必须紧跟 '('`);
      if (!inner || inner.kind !== 'bracket_expr')
        throw new Error(`函数 ${raw.name}() 内只支持单列明细引用 [页签.字段]`);
      if (!close || close.kind !== 'paren_close')
        throw new Error(`函数 ${raw.name}() 内只支持单列明细引用（出现多引用或运算符）`);
      const body = inner.body.trim();
      if (!body.includes('.'))
        throw new Error(`函数 ${raw.name}() 内必须是跨页签明细 [页签.字段]，不能是本组件字段`);
      const dotIdx = body.indexOf('.');
      const alias = body.slice(0, dotIdx);
      const fieldPart = body.slice(dotIdx + 1).replace(/\(总计\)$/, '');
      result.push(makeCrossTabRef(alias, fieldPart, raw.name, tabDefs, selfRowKeyFields));
      k += 4;
      continue;
    }
    // ... 其余 raw.kind 分支同原 switch（whitespace/paren/operator/number/brace/bracket_expr）...
    k += 1;
  }
  return result;
```

原 `bracket_expr` 分支里产生 cross_tab_ref 的部分抽出为：

```ts
function makeCrossTabRef(
  alias: string, field: string, agg: FormulaToken['agg'],
  tabDefs: TabDef[], selfRowKeyFields?: string[],
): FormulaToken {
  const tabDef = tabDefs.find((d) => d.alias === alias);
  if (!tabDef) throw new Error(`表达式中引用了未知页签别名 "${alias}"`);
  if (!tabDef.componentId) throw new Error(`页签 "${alias}" 缺少 componentId`);
  return {
    type: 'cross_tab_ref',
    source: tabDef.componentId,
    sourceLabel: tabDef.componentName ?? alias,
    target: field,
    agg: agg ?? 'NONE',
    match: buildMatch(tabDef.rowKeyFields ?? [], selfRowKeyFields),
  };
}
```

原 `bracket_expr` 内 `[alias.field]` / `[alias.field(总计)]` 分支改为：subtotalCol 命中 → `component_subtotal`（不变）；否则裸明细 `agg='NONE'`、`(总计)` 旧语法 `agg='SUM'` → 调 `makeCrossTabRef(alias, fieldPart, isAgg ? 'SUM' : 'NONE', ...)`。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts`
Expected: Task 3 全 PASS；Task 1（已切回 `SUM(...)` 可选）、Task 2 仍 PASS；旧 expressionToTokens 用例（:111/:122/:185 等）仍 PASS。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/component/formulaSerialize.ts cpq-frontend/src/pages/component/formulaSerialize.test.ts
git commit -m "feat(tabjoin): expressionToTokens 折叠 FN([alias.field])→cross_tab_ref agg=FN(单列收口,FN内运算符/多引用报错)"
```

---

## Task 4：`tokensToDrawerExpression` 回显归一（agg=FN → `FN([alias.field])`，SUM 不再 `(总计)`）

**Files:**
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.ts:334-350`（cross_tab_ref 回显分支）
- Test: `cpq-frontend/src/pages/component/formulaSerialize.test.ts`（含改老断言 :309/:323）

- [ ] **Step 1: 写失败测试 + 改老断言**

新增：

```ts
// ── Task 4: 回显归一 + 往返稳定 ──
describe('tokensToDrawerExpression — FN 回显归一', () => {
  const tabs: TabDef[] = [
    { alias: 'JG', tabKey: 'jg', componentId: 'cid-jg', componentName: '加工',
      rowKeyFields: ['子件'], detailFields: ['工时'], subtotalCols: [] },
  ];
  it('agg=SUM → SUM([JG.工时])（不再 (总计)）', () => {
    const t = expressionToTokens('SUM([JG.工时])', tabs, ['子件']);
    expect(tokensToDrawerExpression(t, tabs)).toBe('SUM([JG.工时])');
  });
  it('AVG/MAX/MIN/COUNT 往返同串', () => {
    for (const fn of ['AVG', 'MAX', 'MIN', 'COUNT']) {
      const s = `${fn}([JG.工时])`;
      expect(tokensToDrawerExpression(expressionToTokens(s, tabs, ['子件']), tabs)).toBe(s);
    }
  });
  it('往返两次稳定 (idempotent)', () => {
    const s = 'AVG([JG.工时])';
    const once = tokensToDrawerExpression(expressionToTokens(s, tabs, ['子件']), tabs);
    const twice = tokensToDrawerExpression(expressionToTokens(once, tabs, ['子件']), tabs);
    expect(twice).toBe(once);
  });
  it('旧 [JG.工时(总计)] 解析后回显归一为 SUM([JG.工时])', () => {
    const t = expressionToTokens('[JG.工时(总计)]', tabs, ['子件']);
    expect(tokensToDrawerExpression(t, tabs)).toBe('SUM([JG.工时])');
  });
});
```

改老断言 :309-320（原 `toBe('[COMP_RL.金额(总计)]')`）→ `toBe('SUM([COMP_RL.金额])')`。
:323-334（empty target whole-tab，仍走 `[alias(总计)]`）保持不变（empty target 非 FN 单列场景）。

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts -t "FN 回显归一"`
Expected: FAIL（现 agg=SUM+target 回显 `[alias.field(总计)]`）。

- [ ] **Step 3: 改实现**（cross_tab_ref 回显分支 :339-348）

```ts
        if (!token.target) {
          parts.push(`[${alias}(总计)]`);                       // whole-tab total（empty target，旧路）
        } else if (token.agg && token.agg !== 'NONE') {
          parts.push(`${token.agg}([${alias}.${token.target}])`); // 归一：FN([alias.field])
        } else {
          parts.push(`[${alias}.${token.target}]`);              // 裸明细 NONE
        }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts`
Expected: Task 4 全 PASS；改后的 :309 PASS；全量 0 失败。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/component/formulaSerialize.ts cpq-frontend/src/pages/component/formulaSerialize.test.ts
git commit -m "feat(tabjoin): tokensToDrawerExpression 回显归一 agg=FN→FN([a.f]);SUM 不再(总计);改老测试+往返稳定用例(v5-J)"
```

---

## Task 5：`checkMappable` 新规则 + `comparable`/`isSubset`（前端）

**Files:**
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.ts:375-391`
- Test: `cpq-frontend/src/pages/component/formulaSerialize.test.ts`

- [ ] **Step 1: 写失败测试**

```ts
// ── Task 5: checkMappable 新规则 + comparable ──
import { comparable, isSubset } from './formulaSerialize';
describe('comparable / isSubset（集合包含，顺序无关）', () => {
  it('isSubset', () => {
    expect(isSubset(['子件'], ['工序', '子件'])).toBe(true);
    expect(isSubset(['工序', '子件'], ['子件'])).toBe(false);
  });
  it('comparable = 任一方 ⊆ 另一方（顺序无关）', () => {
    expect(comparable(['子件'], ['子件', '工序'])).toBe(true);   // host ⊆ source
    expect(comparable(['子件', '工序'], ['子件'])).toBe(true);   // source ⊆ host
    expect(comparable(['A', 'B'], ['B', 'A'])).toBe(true);       // 同集乱序
    expect(comparable(['料号'], ['工序'])).toBe(false);          // 不相交
  });
});
describe('checkMappable — 空 match 拒（v4-C 命门 1）', () => {
  it('cross_tab_ref 且 match=[] → 拒绝（含 agg=NONE）', () => {
    const tk: any = { type: 'cross_tab_ref', source: 'c', target: 'f', agg: 'NONE', match: [] };
    expect(checkMappable([tk]).mappable).toBe(false);
  });
  it('cross_tab_ref agg=SUM 且 match=[] → 拒绝', () => {
    const tk: any = { type: 'cross_tab_ref', source: 'c', target: 'f', agg: 'SUM', match: [] };
    expect(checkMappable([tk]).mappable).toBe(false);
  });
  it('非空 match 放行（含多个 NONE，各自命中≤1）', () => {
    const a: any = { type: 'cross_tab_ref', source: 'a', target: 'f', agg: 'NONE', match: [{ a: 'k', b: 'k' }] };
    const b: any = { type: 'cross_tab_ref', source: 'b', target: 'g', agg: 'NONE', match: [{ a: 'k', b: 'k' }] };
    expect(checkMappable([a, b]).mappable).toBe(true);   // 旧"≥2 NONE 拒"已作废
  });
  it('component_subtotal（无 match）不受影响', () => {
    const cs: any = { type: 'component_subtotal', component_code: 'X', value: '小计' };
    expect(checkMappable([cs]).mappable).toBe(true);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts -t "comparable|空 match"`
Expected: FAIL（`comparable`/`isSubset` 未导出；旧 checkMappable 对空 match 不拒、对 2 NONE 误拒）。

- [ ] **Step 3: 改实现**（替换 `checkMappable` + 新增 helper）

```ts
/** A ⊆ B（视为集合，顺序无关） */
export function isSubset(sub: string[], sup: string[]): boolean {
  const s = new Set(sup);
  return sub.every((x) => s.has(x));
}
/** 行键可比 = 任一方 ⊆ 另一方 */
export function comparable(a: string[], b: string[]): boolean {
  return isSubset(a, b) || isSubset(b, a);
}

/**
 * Gate 镜像后端 TokenMappabilityValidator（v4-C 收敛）：
 * 拒绝任何 cross_tab_ref 且 match 为空（含 agg=NONE）——空 match → 全源行命中
 * → 聚合退化全表 / NONE 静默广播或吞 0。component_subtotal 无 match，不受影响。
 * 旧"≥2 个 agg=NONE 即拒"已作废。
 */
export function checkMappable(tokens: FormulaToken[]): { mappable: boolean; reason?: string } {
  const emptyMatch = tokens.some(
    (t) => t.type === 'cross_tab_ref' && (!t.match || t.match.length === 0),
  );
  if (emptyMatch) {
    return { mappable: false,
      reason: '存在与宿主无公共行键的跨页签引用（match 为空），不可对齐。请改引可比页签或用其整页签小计 [页签(总计)]。' };
  }
  return { mappable: true };
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts`
Expected: 全 PASS。`TabJoinFormulaDrawer.tsx:150` 的 `checkMappable(tokens)` 调用签名不变，无需改 Drawer。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/component/formulaSerialize.ts cpq-frontend/src/pages/component/formulaSerialize.test.ts
git commit -m "feat(tabjoin): checkMappable 改'空match拒'(命门1)+导出 comparable/isSubset;作废≥2NONE旧规则"
```

---

## Task 6：`TabFieldMatrix` `parseActiveRowKeySig` 废除 → 宿主可比 + prop 链

**Files:**
- Modify: `cpq-frontend/src/pages/template/tabjoin/TabFieldMatrix.tsx`（Props :49、主组件 :59-110、删 `parseActiveRowKeySig` :11-44）
- Modify: `cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx`（转发 `selfRowKeyFields` 给 TabFieldMatrix）
- Test: `cpq-frontend/src/pages/template/tabjoin/tabFieldMatrix.test.ts`（新建，若不存在）

- [ ] **Step 1: 写失败测试**（新建 `tabFieldMatrix.test.ts`，只测纯函数 `comparable` 驱动的可比判定——把可比判定抽成 export 纯函数 `tabComparable`）

```ts
import { describe, it, expect } from 'vitest';
import { tabComparable } from './TabFieldMatrix';
describe('TabFieldMatrix — 宿主可比判定（替代 parseActiveRowKeySig）', () => {
  it('宿主[子件] vs 粗source[子件] → 可比(同级)', () => {
    expect(tabComparable(['子件'], ['子件'])).toBe(true);
  });
  it('宿主[子件] vs 细source[子件,工序] → 可比(⊆)', () => {
    expect(tabComparable(['子件'], ['子件', '工序'])).toBe(true);
  });
  it('宿主[子件] vs 不可比[料号] → 不可比', () => {
    expect(tabComparable(['子件'], ['料号'])).toBe(false);
  });
  it('空行键 source（SUBTOTAL rowKeyFields=[]） → 不可比（只留总计）', () => {
    expect(tabComparable(['子件'], [])).toBe(false);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/template/tabjoin/tabFieldMatrix.test.ts`
Expected: FAIL（`tabComparable` 未导出）。

- [ ] **Step 3: 改实现**

`TabFieldMatrix.tsx`：删 `parseActiveRowKeySig`（:11-44）；从 `formulaSerialize` import `comparable`；新增并导出：

```ts
import { comparable } from '../../component/formulaSerialize';
/** 宿主可比：source 行键与宿主 selfRowKeyFields 集合包含(⊆/⊇)；空行键 source 不可比 */
export function tabComparable(selfRowKeyFields: string[], sourceRowKeyFields: string[]): boolean {
  if (!sourceRowKeyFields.length) return false; // 空行键 source 只留总计
  return comparable(selfRowKeyFields ?? [], sourceRowKeyFields);
}
```

Props（:49）新增 `selfRowKeyFields?: string[]`；主组件签名解构加 `selfRowKeyFields`。删 `activeSig`（:60）。每个 tab 行内：

```tsx
const selfRKF = selfRowKeyFields ?? [];
const isComparable = tabComparable(selfRKF, def.rowKeyFields ?? []);
// 细 source 判定（source ⊋ host）：可比但 source 严格更细 → 裸明细须聚合
const sourceFiner = isComparable && (def.rowKeyFields ?? []).length > selfRKF.length;
```

「锁定状态条」文案（:73-96）整体重写为静态说明（无 activeSig）：

```tsx
<span style={{ fontSize: 12, color: '#8a909a' }}>
  宿主行键 <Text strong style={{ color: '#722ed1' }}>[{(selfRowKeyFields ?? []).join(' + ') || '—'}]</Text>
  ；可比页签明细可逐行对齐，更细页签字段需聚合（SUM/AVG/MAX/MIN/COUNT），不可比页签仅整页签小计可用。
</span>
```

`sameClass`/`rkActive`（:107-110）替换为 `isComparable`：不可比行 `background:#fafafa`；明细 chip 的 `disabled = !isComparable`（Task 7 再加细 source 的 FN 录入）。

`TabJoinFormulaDrawer.tsx`：渲染 `<TabFieldMatrix .../>` 处加 `selfRowKeyFields={selfRowKeyFields}`。

- [ ] **Step 4: 跑测试确认通过 + tsc**

Run: `cd cpq-frontend && npx vitest run src/pages/template/tabjoin/tabFieldMatrix.test.ts && node_modules/.bin/tsc --noEmit -p tsconfig.json`
Expected: PASS + tsc 0 错误。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/template/tabjoin/TabFieldMatrix.tsx cpq-frontend/src/pages/template/tabjoin/tabFieldMatrix.test.ts cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx
git commit -m "feat(tabjoin): TabFieldMatrix 废 parseActiveRowKeySig→宿主可比(集合包含)+selfRowKeyFields prop链(v4-D/v4-M)"
```

---

## Task 7：`TabFieldMatrix` UI — 细 source 强制聚合录入 + 置灰 + 空键只留总计

**Files:**
- Modify: `cpq-frontend/src/pages/template/tabjoin/TabFieldMatrix.tsx`（明细 chip 区 :171-204）

> 纯 UI 交互（无新增纯函数可单测）；逻辑正确性由 Task 6 `tabComparable` 单测 + Task 10 夹具 + 真机覆盖。

- [ ] **Step 1: 改明细 chip 渲染**

明细 chip onClick 按三态分流：
- 不可比（`!isComparable`）→ 置灰、tooltip「行键 [...] 与宿主 [...] 不可比；可改用其整页签小计」。
- 可比且同级/粗（`!sourceFiner`）→ 裸明细，`onInsert('[' + def.alias + '.' + f + ']')`。
- 可比且细（`sourceFiner`）→ **不可裸插**；点击弹函数选择（Ant `Dropdown`/`Popover`，选项 SUM/AVG/MAX/MIN/COUNT，默认 SUM），`onInsert(FN + '([' + def.alias + '.' + f + '])')`。chip 上加小标「需聚合」徽标。

实现（替换 :174-203 的 `def.detailFields.map`）：

```tsx
{def.detailFields.map((f) => {
  const disabled = !isComparable;
  if (disabled) {
    return (
      <Tooltip key={f} title={`行键 [${(def.rowKeyFields ?? []).join('+')}] 与宿主 [${selfRKF.join('+')}] 不可比；可改用「${def.alias}(总计)」`}>
        <Tag style={{ cursor: 'not-allowed', color: '#bfbfbf', background: '#fafafa',
          borderColor: '#f0f0f0', margin: 0, fontSize: 12, padding: '3px 9px', userSelect: 'none' }}>{f}</Tag>
      </Tooltip>
    );
  }
  if (sourceFiner) {
    const menu = ['SUM', 'AVG', 'MAX', 'MIN', 'COUNT'].map((fn) => ({
      key: fn, label: fn, onClick: () => onInsert(`${fn}([${def.alias}.${f}])`),
    }));
    return (
      <Dropdown key={f} menu={{ items: menu }} trigger={['click']}>
        <Tag style={{ cursor: 'pointer', background: '#fff', borderColor: '#91caff',
          margin: 0, fontSize: 12, padding: '3px 9px', userSelect: 'none' }}>
          {f} <span style={{ fontSize: 10, color: '#1677ff' }}>Σ需聚合</span>
        </Tag>
      </Dropdown>
    );
  }
  return (
    <Tag key={f} onClick={() => onInsert(`[${def.alias}.${f}]`)}
      style={{ cursor: 'pointer', background: '#fff', margin: 0, fontSize: 12,
        padding: '3px 9px', borderStyle: 'solid', userSelect: 'none' }}>{f}</Tag>
  );
})}
```

import 顶部加 `Dropdown`（antd）。

- [ ] **Step 2: tsc + Vite transform 校验**

Run: `cd cpq-frontend && node_modules/.bin/tsc --noEmit -p tsconfig.json`
Expected: 0 错误。
Run（Vite 实际转换器）：`node --input-type=module -e "import {transformWithOxc} from 'vite';import {readFileSync} from 'fs';await transformWithOxc(readFileSync('src/pages/template/tabjoin/TabFieldMatrix.tsx','utf8'),'src/pages/template/tabjoin/TabFieldMatrix.tsx');console.log('OK')"`
Expected: `OK`。

- [ ] **Step 3: Commit**

```bash
git add cpq-frontend/src/pages/template/tabjoin/TabFieldMatrix.tsx
git commit -m "feat(tabjoin): 细source裸明细→强制FN聚合下拉录入;不可比置灰;空键只留总计入口(需求1 UI)"
```

---

## Task 8：后端 `TokenMappabilityValidator` 空 match 拒 + `comparable`（Java）+ `evalCrossTab` 防御

**Files:**
- Modify: `cpq-backend/.../component/formula/TokenMappabilityValidator.java`
- Create: `cpq-backend/.../component/formula/RowKeyCompare.java`
- Modify: `cpq-backend/.../quotation/service/FormulaCalculator.java:206`（`evalCrossTab` 空 match 防御）
- Test: `cpq-backend/.../component/formula/TokenMappabilityValidatorTest.java`（已存在，新增用例）

- [ ] **Step 1: 写失败测试**（追加到 `TokenMappabilityValidatorTest`）

```java
@Test
void rejects_crossTabRef_with_empty_match() {
    var token = new java.util.HashMap<String, Object>();
    token.put("type", "cross_tab_ref");
    token.put("agg", "NONE");
    token.put("match", java.util.List.of());           // 空 match
    var r = validator.validate(java.util.List.of(token));
    org.junit.jupiter.api.Assertions.assertFalse(r.mappable(), "空 match 必拒");
}
@Test
void rejects_crossTabRef_SUM_empty_match() {
    var token = new java.util.HashMap<String, Object>();
    token.put("type", "cross_tab_ref"); token.put("agg", "SUM");
    token.put("match", java.util.List.of());
    org.junit.jupiter.api.Assertions.assertFalse(validator.validate(java.util.List.of(token)).mappable());
}
@Test
void allows_two_NONE_with_nonempty_match() {  // 旧"≥2 NONE 拒"作废
    var a = new java.util.HashMap<String, Object>();
    a.put("type", "cross_tab_ref"); a.put("agg", "NONE");
    a.put("match", java.util.List.of(java.util.Map.of("a", "k", "b", "k")));
    var b = new java.util.HashMap<>(a);
    org.junit.jupiter.api.Assertions.assertTrue(validator.validate(java.util.List.of(a, b)).mappable());
}
@Test
void rowKeyCompare_subset_and_comparable() {
    org.junit.jupiter.api.Assertions.assertTrue(RowKeyCompare.isSubset(java.util.List.of("子件"), java.util.List.of("子件", "工序")));
    org.junit.jupiter.api.Assertions.assertTrue(RowKeyCompare.comparable(java.util.List.of("子件", "工序"), java.util.List.of("子件")));
    org.junit.jupiter.api.Assertions.assertTrue(RowKeyCompare.comparable(java.util.List.of("A", "B"), java.util.List.of("B", "A")));
    org.junit.jupiter.api.Assertions.assertFalse(RowKeyCompare.comparable(java.util.List.of("料号"), java.util.List.of("工序")));
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && mvn -o test -Dtest=TokenMappabilityValidatorTest`
Expected: FAIL（旧 validator 不拒空 match；`RowKeyCompare` 不存在 → 编译错）。

- [ ] **Step 3: 改实现**

新建 `RowKeyCompare.java`：

```java
package com.cpq.component.formula;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 行键集合包含判定（顺序无关），前后端同语义（镜像 formulaSerialize.ts comparable/isSubset）。 */
public final class RowKeyCompare {
    private RowKeyCompare() {}
    public static boolean isSubset(List<String> sub, List<String> sup) {
        Set<String> s = new HashSet<>(sup);
        return sub.stream().allMatch(s::contains);
    }
    public static boolean comparable(List<String> a, List<String> b) {
        return isSubset(a, b) || isSubset(b, a);
    }
}
```

`TokenMappabilityValidator.validate` 改为：

```java
public Result validate(List<Map<String, Object>> expr) {
    for (Map<String, Object> t : expr) {
        if (!"cross_tab_ref".equals(t.get("type"))) continue;
        Object m = t.get("match");
        boolean emptyMatch = !(m instanceof List) || ((List<?>) m).isEmpty();
        if (emptyMatch) {
            return new Result(false,
                "存在与宿主无公共行键的跨页签引用（match 为空），不可对齐。" +
                "请改引可比页签或用其整页签小计 [页签(总计)]。");
        }
    }
    return new Result(true, null);
}
```

`FormulaCalculator.evalCrossTab`（:206）开头加空 match 防御（validator 漏网时返 ERR 而非全表）：

```java
    if (!token.path("match").isArray() || token.path("match").size() == 0) {
        return ERR;   // 空 match 不得进聚合/广播（v4-C 防御）
    }
```

> 注：该防御针对 cross_tab_ref。whole-tab total 走 component_subtotal（不进 evalCrossTab），不受影响。

- [ ] **Step 4: 跑测试确认通过 + 求值回归**

Run: `cd cpq-backend && mvn -o test -Dtest=TokenMappabilityValidatorTest,FormulaCalculatorTest,FormulaCalculatorCrossTabFixtureTest`
Expected: 全绿（夹具用例 match 均非空，evalCrossTab 防御不误伤）。

- [ ] **Step 5: Commit**

```bash
git add cpq-backend/src/main/java/com/cpq/component/formula/ cpq-backend/src/main/java/com/cpq/quotation/service/FormulaCalculator.java cpq-backend/src/test/java/com/cpq/component/formula/TokenMappabilityValidatorTest.java
git commit -m "feat(tabjoin): 后端 validator 改空match拒+RowKeyCompare(Java);evalCrossTab 空match防御返ERR(命门1后端)"
```

---

## Task 9：后端 token 试算端点 + 草稿双注入（🔴 命门 0：试算=渲染对拍）

**Files:**
- Modify: `cpq-backend/.../component/resource/ComponentTabJoinResource.java`（新增 `POST /{id}/dry-run-token`）
- Modify: `cpq-backend/.../component/service/ComponentSampleCardService.java`（新增 `dryRunTokenForComponent`）
- Modify: `cpq-backend/.../quotation/service/CardSnapshotService.java`（新增 `dryRunTokenRows` 复用装配 + 草稿双注入 seam）
- Test: `cpq-backend/.../quotation/service/CardSnapshotDryRunParityTest.java`（新建，`@QuarkusTest`）
- 前端：`cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx`（试算调用切到新端点；逐行小表展示）+ `tabJoinFormulaService.ts`（新增 service 方法）

> **最重 Task。** 旧 `dryRunForComponent`（EXCEL 链）**完全保留不动**（v6-P）。

- [ ] **Step 1: 写失败测试（命门 0 对拍）**

新建 `CardSnapshotDryRunParityTest`：造模板（宿主投料`[子件]` 金额=10 + 细 source 加工`[子件,工序]` 工时 3、5）+ quotation + lineItem + componentData。断言：
- token 试算 `dryRunTokenRows(componentId, lineItemId, 草稿tokens=[field 金额, op *, cross_tab_ref SUM 加工.工时], 草稿selfRowKeyFields=[子件])` 返回逐行 `[{rowKey:子件=螺丝, value: 10*8=80}, ...]`；
- 同一公式落库后 `refreshQuoteCardValues(li)` 渲染的宿主 tab `formulaResults` 逐行 value **逐行相等**（直接两条线断言相等）。
- 含「草稿改行键」场景：草稿 selfRowKeyFields 与持久化不同 → 试算用草稿行键分组，断言 == 用同草稿行键落库后渲染。

```java
@QuarkusTest
class CardSnapshotDryRunParityTest {
    @Inject CardSnapshotService cardSnapshotService;
    // ... @Inject EntityManager / 造数 helper（参照 CardSnapshotCrossTabTest 构造 snapshot+baseRows）...

    @Test @TestTransaction
    void tokenDryRun_equals_refreshRender_perRow() {
        // 1) 造 template snapshot：投料(rkf=[子件],金额) + 加工(rkf=[子件,工序],工时)
        // 2) 造 lineItem + componentData（投料:螺丝金额10; 加工:螺丝/钻3,螺丝/铣5）
        // 3) 草稿 tokens = 金额 * SUM(加工.工时)
        var dry = cardSnapshotService.dryRunTokenRows(hostCid, liId, draftTokens, List.of("子件"));
        // 4) 落库同公式 → refreshQuoteCardValues → 取宿主 formulaResults
        cardSnapshotService.refreshQuoteCardValues(li);
        var rendered = /* 读 li.quoteCardValues 宿主 tab formulaResults 逐行 value */;
        // 5) 对拍逐行相等
        assertEquals(rendered, dry, "试算逐行值必须 == 渲染逐行值");
    }
}
```

（造数细节执行时参照 `CardSnapshotCrossTabTest:25-135` 的 snapshot/baseRows 构造范式补全。）

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-backend && mvn -o test -Dtest=CardSnapshotDryRunParityTest`
Expected: FAIL（`dryRunTokenRows` 不存在 → 编译错）。

- [ ] **Step 3: 改实现 — `CardSnapshotService.dryRunTokenRows`（草稿双注入，复用装配）**

```java
/**
 * Token 试算（NORMAL/SUBTOTAL）：以样本 lineItem 为上下文，复用 assembleTabsWithFormulaResults，
 * 注入草稿公式 + 草稿行键，返回宿主组件逐行 [{rowKey, value(草稿公式列)}]。
 * 与 refreshQuoteCardValues 同装配 → 试算=渲染（命门 0）。EXCEL 仍走旧 dryRunForComponent。
 */
@Transactional
public List<Map<String, Object>> dryRunTokenRows(
        String hostComponentId, UUID lineItemId,
        JsonNode draftTokens, List<String> draftSelfRowKeyFields) {
    QuotationLineItem li = QuotationLineItem.findById(lineItemId);
    if (li == null) throw new BusinessException(400, "样本卡片不存在: " + lineItemId);
    JsonNode snapshot = loadComponentsSnapshot(li.templateId);

    // (1) 草稿公式注入：深拷贝 snapshot，按 componentId 定位宿主 tab（同 cid 多实例按 sortOrder，v6-O），
    //     替换其 formulas 为草稿单公式 [{fieldName:"__dryrun__", expression: draftTokens}]；兄弟 source 保持已存版本。
    ObjectNode snap2 = snapshot.deepCopy();
    injectDraftFormula(snap2, hostComponentId, draftTokens); // 见下

    // (2) baseRows 展开（与渲染同源）
    Map<String, ArrayNode> baseRowsByComp = expandTemplateDriverBaseRows(snap2, li);
    Map<String, ArrayNode> editRowsByComp = mergeRowDataInputsIntoEdits(
        extractEditRowsByComp(li.quoteCardValues), li, snap2);

    // (3) 草稿行键覆盖：装配里 rkfByComp 默认读 loadRowKeyFieldsNode(cid)=持久化行键，
    //     这里把宿主 cid 的行键覆盖为草稿行键（v6-N）。
    Map<String, JsonNode> rkfOverride = new LinkedHashMap<>();
    if (draftSelfRowKeyFields != null)
        rkfOverride.put(hostComponentId, MAPPER.valueToTree(draftSelfRowKeyFields));

    ObjectNode assembled = assembleTabsWithFormulaResults(snap2, baseRowsByComp, editRowsByComp, rkfOverride);

    // (4) 取宿主 tab 的 formulaResults 逐行，提取草稿列值
    return extractHostDryRunRows(assembled, hostComponentId, "__dryrun__");
}
```

`assembleTabsWithFormulaResults` 增加可选 `rkfOverride` 重载（不破坏现签名）：原方法 :679 组装 `rkfByComp` 后，`if (rkfOverride != null) rkfByComp.putAll(rkfOverride);`。原三参方法 delegate 到四参（`rkfOverride=null`），保证既有调用零影响。

`injectDraftFormula`：遍历 `snap2.tabs`，找 `componentId==hostComponentId`（多实例时取 `sortOrder` 最小或调用方传入的实例——本期单实例，按 cid 命中第一条即可，注释标注多实例 follow-up），`((ObjectNode)tab).set("formulas", buildSingleFormulaNode("__dryrun__", draftTokens))`。

`extractHostDryRunRows`：从 assembled tabs 找宿主 cid 的 tabNode，遍历 `formulaResults`，每行 `{rowKey: fr.rowKey, value: fr.values["__dryrun__"]}`。

- [ ] **Step 4: 改 service + resource 接线**

`ComponentSampleCardService.dryRunTokenForComponent`：

```java
public Map<String, Object> dryRunTokenForComponent(
        UUID componentId, UUID lineItemId, JsonNode draftTokens, List<String> draftSelfRowKeyFields) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (lineItemId == null) {
        out.put("rows", List.of());
        out.put("errors", List.of("试算不可用(无样本卡)：该组件尚无报价行引用"));
        return out;
    }
    try {
        var rows = cardSnapshotService.dryRunTokenRows(
            componentId.toString(), lineItemId, draftTokens, draftSelfRowKeyFields);
        out.put("rows", rows);
        out.put("errors", List.of());
    } catch (Exception e) {
        out.put("rows", List.of());
        out.put("errors", List.of(e.getMessage() == null ? "试算异常" : e.getMessage()));
    }
    return out;
}
```

`ComponentTabJoinResource` 新增端点：

```java
@POST @Path("/{id}/dry-run-token")
@SuppressWarnings("unchecked")
public ApiResponse<Map<String, Object>> dryRunToken(@PathParam("id") UUID id, Map<String, Object> body) {
    if (body == null) throw new BusinessException(400, "请求体不能为空");
    UUID lineItemId = body.get("lineItemId") != null && !body.get("lineItemId").toString().isBlank()
        ? UUID.fromString(body.get("lineItemId").toString()) : null;
    JsonNode tokens = MAPPER.valueToTree(body.get("tokens"));
    List<String> selfRkf = body.get("selfRowKeyFields") instanceof List
        ? (List<String>) body.get("selfRowKeyFields") : List.of();
    return ApiResponse.success(
        componentSampleCardService.dryRunTokenForComponent(id, lineItemId, tokens, selfRkf));
}
```

- [ ] **Step 5: 跑命门 0 测试确认通过**

Run: `cd cpq-backend && mvn -o test -Dtest=CardSnapshotDryRunParityTest`
Expected: PASS（逐行相等，含草稿改行键场景）。

- [ ] **Step 6: 前端接线**（试算切新端点 + 逐行小表）

`tabJoinFormulaService.ts` 新增：

```ts
export async function dryRunToken(componentId: string, body: {
  lineItemId: string | null; tokens: FormulaToken[]; selfRowKeyFields: string[];
}): Promise<{ rows: { rowKey: string; value: number | null }[]; errors: string[] }> {
  const res = await api.post(`/components/${componentId}/dry-run-token`, body);
  return res.data;
}
```

`TabJoinFormulaDrawer.tsx`：NORMAL/SUBTOTAL 的试算按钮调 `dryRunToken`（传 `expressionToTokens(expr, tabDefs, selfRowKeyFields)` + `selfRowKeyFields`）；结果 `rows` 渲染逐行小表（SUBTOTAL 收敛单值显示——取 rows 聚合或单行）。EXCEL 仍走旧 `dryRun`。

- [ ] **Step 7: 自检 + Commit**

Run: `cd cpq-frontend && node_modules/.bin/tsc --noEmit -p tsconfig.json`（0 错误）；后端 `touch` 重启 → `curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8081/api/cpq/components/<id>/dry-run-token -H 'Content-Type: application/json' -d '{}'`（期望 200/400，非 500）。

```bash
git add cpq-backend/src/main/java/com/cpq/component/ cpq-backend/src/main/java/com/cpq/quotation/service/CardSnapshotService.java cpq-backend/src/test/java/com/cpq/quotation/service/CardSnapshotDryRunParityTest.java cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx cpq-frontend/src/services/tabJoinFormulaService.ts
git commit -m "feat(tabjoin): 新增 token 试算端点+草稿公式/行键双注入,复用渲染装配;命门0试算=渲染逐行对拍绿(v5-H/v6-N/v6-P)"
```

---

## Task 10：`cross-tab-cases.json` 宿主分组新用例（前后端同跑）

**Files:**
- Modify: `cpq-backend/src/test/resources/cross-tab-cases.json`
- Modify: `cpq-frontend/src/utils/__fixtures__/cross-tab-cases.json`（必须逐字同步）
- Test: 既有 `FormulaCalculatorCrossTabFixtureTest`（后端）+ 前端夹具对等测试自动覆盖

- [ ] **Step 1: 两份夹具同步追加用例**（token 直接构造，按宿主行驱动语义）

追加用例（aRows=source 行，currentRow=宿主行，token=cross_tab_ref，expected=该宿主行结果）：

```json
{ "name": "粗host[子件]×细source[子件,工序] SUM 聚合",
  "aRows": [ {"子件":"螺丝","工序":"钻","工时":3}, {"子件":"螺丝","工序":"铣","工时":5}, {"子件":"垫片","工序":"钻","工时":9} ],
  "currentRow": {"子件":"螺丝"},
  "token": {"type":"cross_tab_ref","source":"A","target":"工时","match":[{"a":"子件","b":"子件"}],"agg":"SUM"},
  "expected": 8 },
{ "name": "粗host×细source AVG",
  "aRows": [ {"子件":"螺丝","工序":"钻","工时":3}, {"子件":"螺丝","工序":"铣","工时":5} ],
  "currentRow": {"子件":"螺丝"},
  "token": {"type":"cross_tab_ref","source":"A","target":"工时","match":[{"a":"子件","b":"子件"}],"agg":"AVG"},
  "expected": 4 },
{ "name": "细host[子件,工序]×粗source[子件] 广播 NONE 命中1",
  "aRows": [ {"子件":"螺丝","单价":10}, {"子件":"垫片","单价":2} ],
  "currentRow": {"子件":"螺丝","工序":"钻"},
  "token": {"type":"cross_tab_ref","source":"A","target":"单价","match":[{"a":"子件","b":"子件"}],"agg":"NONE"},
  "expected": 10 },
{ "name": "同级 1:1 NONE",
  "aRows": [ {"子件":"螺丝","值":7} ], "currentRow": {"子件":"螺丝"},
  "token": {"type":"cross_tab_ref","source":"A","target":"值","match":[{"a":"子件","b":"子件"}],"agg":"NONE"},
  "expected": 7 },
{ "name": "公共字段名乱序对齐 host[A,B]×source 同集 match{A,A}{B,B}",
  "aRows": [ {"A":"x","B":"y","值":11} ], "currentRow": {"A":"x","B":"y"},
  "token": {"type":"cross_tab_ref","source":"A","target":"值","match":[{"a":"A","b":"A"},{"a":"B","b":"B"}],"agg":"SUM"},
  "expected": 11 },
{ "name": "宿主行 source 无匹配 → 缺补 0",
  "aRows": [ {"子件":"螺丝","工时":3} ], "currentRow": {"子件":"垫片"},
  "token": {"type":"cross_tab_ref","source":"A","target":"工时","match":[{"a":"子件","b":"子件"}],"agg":"SUM"},
  "expected": 0 }
```

- [ ] **Step 2: 跑前后端夹具确认通过**

Run（后端）：`cd cpq-backend && mvn -o test -Dtest=FormulaCalculatorCrossTabFixtureTest`
Run（前端）：`cd cpq-frontend && npx vitest run src/utils` （加载前端夹具的对等测试）
Expected: 两侧全绿、**同夹具同结果**（前后端引擎对等）。若前端无加载该夹具的测试，新建 `formulaEngineCrossTabFixture.test.ts` 读 `__fixtures__/cross-tab-cases.json` 跑 `evaluateExpression` 单 token 断言（参照后端 FixtureTest 结构）。

- [ ] **Step 3: Commit**

```bash
git add cpq-backend/src/test/resources/cross-tab-cases.json cpq-frontend/src/utils/__fixtures__/cross-tab-cases.json cpq-frontend/src/utils/formulaEngineCrossTabFixture.test.ts
git commit -m "test(tabjoin): cross-tab-cases.json 新增宿主分组用例(粗host×细source SUM/AVG/广播/乱序对齐/缺补0),前后端同夹具锁一致"
```

---

## Task 11：三视图回归 + 全量自检 + 文档回写

**Files:**
- Test（回归，不改产线）：`CardSnapshotCrossTabTest` / `CardSnapshotSubtotalTest` / `CardSnapshotResolvedRowsTest`
- Modify: `docs/RECORD.md`、`docs/PRD-v3.md`（演进史）、`docs/方案制定前必读.md`（如需补连环 bug 案例）

- [ ] **Step 1: 后端全量连表相关测试**

Run: `cd cpq-backend && mvn -o test -Dtest='FormulaCalculator*,CardSnapshot*,TokenMappabilityValidatorTest,ComponentTabDefServiceTest'`
Expected: 全绿（三视图一致、无回归）。

- [ ] **Step 2: 前端全量序列化/引擎测试 + tsc**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts src/pages/template/tabjoin/tabFieldMatrix.test.ts src/utils && node_modules/.bin/tsc --noEmit -p tsconfig.json`
Expected: 全绿 + tsc 0 错误。

- [ ] **Step 3: Vite transform 校验所有改动 TSX**

Run: 对 `TabFieldMatrix.tsx` / `TabJoinFormulaDrawer.tsx` 跑 `transformWithOxc`（同 Task 7 Step 2）→ 全 `OK`。

- [ ] **Step 4: 真机试算复测**

样本卡修好（批 1）后：组件管理 → 含细 source 的页签公式 → 抽屉点细 source chip 弹 SUM/AVG 选择 → 插入 → 试算逐行小表 → 与卡片渲染逐行核对一致；不可比页签明细置灰、只留总计。

- [ ] **Step 5: 文档回写**

`docs/RECORD.md` 追加批 3 交付记录（含命门 0/1 对拍、FN 单列收口、试算改 token 引擎、空 match 拒）。`docs/PRD-v3.md` 演进史记录行键宿主分组语义。验收对照 spec §210-217 逐条勾。

- [ ] **Step 6: Commit**

```bash
git add docs/RECORD.md docs/PRD-v3.md
git commit -m "docs(tabjoin): 批3 行键宿主分组+FN聚合+试算=渲染 交付记录与PRD演进史回写"
```

---

## Self-Review（已执行）

**1. Spec 覆盖：** §需求1 三点差距 → T1（buildMatch 交集）/T7（细 source 强制聚合）/T6（宿主可比置灰）；FN 序列化三通道 → T2（lexer）/T3（状态机）/T4（回显归一）；mappability 双 validator → T5（前端）/T8（后端）；试算=渲染 + 草稿双注入 → T9（命门 0）；夹具宿主分组用例 → T10；三视图回归 → T11。命门 0/1/2 → T9/T5+T8/T10（三语义夹具）。FN round-trip 归一 → T4。✅ 全覆盖。需求 2/3/4 已在批 1/批 2 交付，本计划不含。

**2. 占位扫描：** 无 TODO/TBD；每个 code step 给了完整 test+impl 代码。T9 造数细节标注"参照 CardSnapshotCrossTabTest 范式补全"（执行时具体化，非占位逻辑）。

**3. 类型一致性：** `buildMatch(tabRowKeyFields, selfRowKeyFields?)`→`{a,b}[]`；`comparable/isSubset`（前端 export + 后端 `RowKeyCompare` 同名同义）；`makeCrossTabRef`/`injectDraftFormula`/`extractHostDryRunRows` 贯穿 T3/T9 命名一致；`dryRunTokenRows`(service)→`dryRunTokenForComponent`(app)→`/dry-run-token`(resource) 三层命名一致；agg 枚举 SUM/AVG/MAX/MIN/COUNT 前后端一致。

**4. 风险点：** ① T9 `assembleTabsWithFormulaResults` 加 `rkfOverride` 重载——必须保留三参 delegate 到四参，零破坏既有调用（AP-40/AP-51 高发区）。② 老测试 `(总计)` 回显断言（:309）必须随 T4 改，漏改即红。③ 前后端夹具逐字同步（T10），不同步则 FixtureTest 红。
