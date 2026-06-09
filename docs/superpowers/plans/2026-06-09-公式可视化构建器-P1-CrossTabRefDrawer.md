# 公式可视化构建器 P1 — CrossTabRefDrawer 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把组件字段的「跨页签引用」配置抽屉 `CrossTabRefDrawer` 从"术语化插入表单"升级为"引导式 + 简单/高级分层 + 可视化↔原始文本双向同步"的纯可视化构建器,让不会写语法的用户也能点选配出"按键关联两个 Tab + 逐行相乘再求和(SUMPRODUCT)"。

**Architecture:** 纯前端 UX 改造,**底层 `cross_tab_ref` token 结构零变更**(向后兼容,无 AP-44 协议传播)。核心可测逻辑(操作↔聚合映射、token↔原始文本 序列化/解析)抽到新纯函数模块 `crossTabText.ts` 用 vitest 单测;抽屉 UI 改动用 tsc + Vite transform + Playwright E2E 验证。

**Tech Stack:** React + TypeScript(Vite)+ Ant Design(Drawer/Segmented/Select/TextArea)+ vitest(单测)+ Playwright(E2E)。

**已确认事实(实现依据):**
- 设计文档:`docs/superpowers/specs/2026-06-09-公式可视化构建器-design.md`(本计划落地其 §4 P1)。
- `CrossTabRefDrawer.tsx`(562 行)现状:已有 源下拉(`sourceId`)、匹配列对 a/b 双下拉(`matchPairs[{a,b}]`,**已是"只列可选项"形态**)、聚合下拉(`agg`:NONE/SUM/AVG/COUNT/MAX/MIN,`AGG_OPTIONS`)、单列 `target` + 「改用公式」(`useFormula`)的 `targetExpr` chip 构建器(插入 field/b_field/operator/bracket/number)、底部只读预览。
- token 形态(`CrossTabToken` 接口,L21-29):`{ type:'cross_tab_ref', source, sourceLabel, target, targetExpr?, match:[{a,b}], agg }`。**本计划不改它**。
- `FormulaToken`(`types.ts:124`):`type ∈ field|b_field|operator|bracket_open|bracket_close|number|global_variable|…`,字段 `value?/label?/code?`。
- 调用点:`ComponentManagement.tsx:944 <CrossTabRefDrawer .../>`,经「其他数据源」面板「+ 配置跨页签引用…」打开,`onConfirm(token)` 把 token 插入当前公式。
- 既有 E2E 范式:`cpq-frontend/e2e/cross-tab-ref.spec.ts` —— 进 `/components-raw` → 选 COMP-0002(同目录 10 兄弟组件)→「公式」标签 → 激活一条公式 →「+ 配置跨页签引用…」→ 抽屉标题「插入跨页签引用」→ 选源+匹配+目标+确定 → 断言公式区出现以「跨页签[」开头的 chip。**全程只读不保存**。

**数据约定(贯穿全计划):**
- 「操作」是面向用户的业务措辞,内部映射到既有 `agg`:`取一个值=NONE / 求和=SUM / 平均=AVG / 计数=COUNT / 最大=MAX / 最小=MIN`。
- 「原始文本」用**确定性可解析的规范文法**(field 用 `A.<name>`、b_field 用 `B.<name>`、ASCII 运算符 `* / + - ( )`、源用组件 `code`),与展示用 chip 文案(`本.` / `×÷`)分离。序列化产出此文法,解析只需消费此文法;用户乱填 → 行内报错、不静默。
- 简单模式默认只露「取一个值 / 求和」+ 单匹配对 + 单列/一条乘积式;高级模式露全部聚合、多匹配对、targetExpr 全积木、原始文本双模式。

**范围(P1 不含,见设计文档 §4.6):** 条件/过滤(SUMIF WHERE)→P2;IF 分支/函数→P3;CardFormulaDrawer(Excel 列)→P1.2;关联键体检/智能提示→明确不做(用户选择)。P0 数据备键(`ll_view` 加 `子料号=component_no`)是**本计划外的并行前置**,仅全量渲染 E2E 需要;本计划的 E2E 验证抽屉产出正确 token + 文本往返,不依赖 P0。

---

## Task 1: 新建 `crossTabText.ts` — 操作↔聚合映射

**Files:**
- Create: `cpq-frontend/src/pages/component/crossTabText.ts`
- Test: `cpq-frontend/src/pages/component/crossTabText.test.ts`

- [ ] **Step 1: 写失败测试**

```typescript
// crossTabText.test.ts
import { describe, it, expect } from 'vitest';
import { OPERATIONS, operationToAgg, aggToOperation } from './crossTabText';

describe('operation <-> agg 映射', () => {
  it('OPERATIONS 含 6 个操作且 simple 标记正确', () => {
    expect(OPERATIONS.map((o) => o.key)).toEqual(['single', 'sum', 'avg', 'count', 'max', 'min']);
    expect(OPERATIONS.filter((o) => o.simple).map((o) => o.key)).toEqual(['single', 'sum']);
  });
  it('operationToAgg 把操作 key 映射到 agg', () => {
    expect(operationToAgg('single')).toBe('NONE');
    expect(operationToAgg('sum')).toBe('SUM');
    expect(operationToAgg('count')).toBe('COUNT');
    expect(operationToAgg('max')).toBe('MAX');
  });
  it('aggToOperation 反向映射,未知 agg 回退 single', () => {
    expect(aggToOperation('NONE')).toBe('single');
    expect(aggToOperation('SUM')).toBe('sum');
    expect(aggToOperation('XYZ')).toBe('single');
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/crossTabText.test.ts`
Expected: FAIL（模块不存在）

- [ ] **Step 3: 实现映射**

```typescript
// crossTabText.ts
export interface OperationDef {
  key: string;
  label: string;
  agg: string;
  /** 简单模式是否露出 */
  simple: boolean;
}

export const OPERATIONS: OperationDef[] = [
  { key: 'single', label: '取一个值', agg: 'NONE', simple: true },
  { key: 'sum', label: '求和', agg: 'SUM', simple: true },
  { key: 'avg', label: '平均', agg: 'AVG', simple: false },
  { key: 'count', label: '计数', agg: 'COUNT', simple: false },
  { key: 'max', label: '最大', agg: 'MAX', simple: false },
  { key: 'min', label: '最小', agg: 'MIN', simple: false },
];

export function operationToAgg(key: string): string {
  return OPERATIONS.find((o) => o.key === key)?.agg ?? 'NONE';
}

export function aggToOperation(agg: string): string {
  return OPERATIONS.find((o) => o.agg === agg)?.key ?? 'single';
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/component/crossTabText.test.ts`
Expected: PASS（3 用例）

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/component/crossTabText.ts cpq-frontend/src/pages/component/crossTabText.test.ts
git commit -m "feat(formula-builder): crossTabText operation<->agg mapping (P1 Task 1)"
```

---

## Task 2: `crossTabText.ts` — 序列化 token → 规范原始文本

**Files:**
- Modify: `cpq-frontend/src/pages/component/crossTabText.ts`
- Test: `cpq-frontend/src/pages/component/crossTabText.test.ts`

- [ ] **Step 1: 写失败测试**

追加到 `crossTabText.test.ts`：

```typescript
import { serializeCrossTab } from './crossTabText';
import type { FormulaToken } from './types';

const ll = { id: 'id-ll', code: 'COMP-0028', name: '来料', fields: [{ name: '组成用量' }, { name: '子料号' }] };

describe('serializeCrossTab', () => {
  it('单列 SUM 目标', () => {
    const token: any = { type: 'cross_tab_ref', source: 'id-ll', sourceLabel: '来料', target: '组成用量', match: [{ a: '子料号', b: '料件' }], agg: 'SUM' };
    expect(serializeCrossTab(token, ll)).toBe('求和 | 源:COMP-0028 | 关联:子料号=料件 | 目标:A.组成用量');
  });
  it('targetExpr 乘积式(A列×本列)', () => {
    const expr: FormulaToken[] = [
      { type: 'field', value: '组成用量' }, { type: 'operator', value: '*' },
      { type: 'b_field', value: '含量' }, { type: 'operator', value: '*' }, { type: 'b_field', value: '单价' },
    ];
    const token: any = { type: 'cross_tab_ref', source: 'id-ll', sourceLabel: '来料', target: '', targetExpr: expr, match: [{ a: '子料号', b: '料件' }], agg: 'SUM' };
    expect(serializeCrossTab(token, ll)).toBe('求和 | 源:COMP-0028 | 关联:子料号=料件 | 目标:A.组成用量 * B.含量 * B.单价');
  });
  it('COUNT 无目标', () => {
    const token: any = { type: 'cross_tab_ref', source: 'id-ll', sourceLabel: '来料', target: '', match: [{ a: '子料号', b: '料件' }], agg: 'COUNT' };
    expect(serializeCrossTab(token, ll)).toBe('计数 | 源:COMP-0028 | 关联:子料号=料件 | 目标:(计数)');
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/crossTabText.test.ts`
Expected: FAIL（`serializeCrossTab` 未定义）

- [ ] **Step 3: 实现 serializeCrossTab**

追加到 `crossTabText.ts`（顶部加 `import type { FormulaToken } from './types';`）：

```typescript
import type { FormulaToken } from './types';

export interface CrossTabTokenLike {
  type: 'cross_tab_ref';
  source: string;
  sourceLabel: string;
  target: string;
  targetExpr?: FormulaToken[];
  match: Array<{ a: string; b: string }>;
  agg: string;
}

export interface SourceCompLike {
  id: string;
  code: string;
  name: string;
  fields: Array<{ name: string; label?: string }>;
}

/** targetExpr token → 规范机器可解析片段（A.x / B.x / ASCII 运算符 / 数字）。 */
function exprTokenToCanonical(tok: FormulaToken): string {
  switch (tok.type) {
    case 'field': return `A.${tok.value}`;
    case 'b_field': return `B.${tok.value}`;
    case 'operator': return tok.value || '';
    case 'bracket_open': return '(';
    case 'bracket_close': return ')';
    case 'number': return tok.value || '';
    default: return tok.value || '';
  }
}

export function serializeCrossTab(token: CrossTabTokenLike, sourceComp: SourceCompLike | null): string {
  const opLabel = OPERATIONS.find((o) => o.agg === token.agg)?.label ?? '取一个值';
  const srcCode = sourceComp?.code ?? token.source;
  const pairs = (token.match || []).filter((p) => p.a && p.b).map((p) => `${p.a}=${p.b}`).join(',');
  let targetText: string;
  if (token.agg === 'COUNT') {
    targetText = '(计数)';
  } else if (token.targetExpr && token.targetExpr.length > 0) {
    targetText = token.targetExpr.map(exprTokenToCanonical).join(' ');
  } else {
    targetText = `A.${token.target}`;
  }
  return `${opLabel} | 源:${srcCode} | 关联:${pairs} | 目标:${targetText}`;
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/component/crossTabText.test.ts`
Expected: PASS（+3 用例）

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/component/crossTabText.ts cpq-frontend/src/pages/component/crossTabText.test.ts
git commit -m "feat(formula-builder): serializeCrossTab token->canonical text (P1 Task 2)"
```

---

## Task 3: `crossTabText.ts` — 解析 规范原始文本 → token（双向同步写方向）

**Files:**
- Modify: `cpq-frontend/src/pages/component/crossTabText.ts`
- Test: `cpq-frontend/src/pages/component/crossTabText.test.ts`

- [ ] **Step 1: 写失败测试**

追加到 `crossTabText.test.ts`：

```typescript
import { parseCrossTab } from './crossTabText';

const siblings = [ll]; // 复用 Task 2 的 ll

describe('parseCrossTab', () => {
  it('单列 SUM round-trip', () => {
    const text = '求和 | 源:COMP-0028 | 关联:子料号=料件 | 目标:A.组成用量';
    const r = parseCrossTab(text, siblings);
    expect('token' in r).toBe(true);
    if ('token' in r) {
      expect(r.token.agg).toBe('SUM');
      expect(r.token.source).toBe('id-ll');
      expect(r.token.target).toBe('组成用量');
      expect(r.token.match).toEqual([{ a: '子料号', b: '料件' }]);
    }
  });
  it('targetExpr 乘积式 round-trip', () => {
    const text = '求和 | 源:COMP-0028 | 关联:子料号=料件 | 目标:A.组成用量 * B.含量 * B.单价';
    const r = parseCrossTab(text, siblings);
    expect('token' in r).toBe(true);
    if ('token' in r) {
      expect(r.token.target).toBe('');
      expect(r.token.targetExpr?.map((t) => t.type)).toEqual(['field', 'operator', 'b_field', 'operator', 'b_field']);
      expect(r.token.targetExpr?.[0].value).toBe('组成用量');
    }
  });
  it('源 code 不存在 → 报错', () => {
    const r = parseCrossTab('求和 | 源:NOPE | 关联:子料号=料件 | 目标:A.组成用量', siblings);
    expect('error' in r).toBe(true);
  });
  it('缺少分段 → 报错', () => {
    const r = parseCrossTab('求和 源:COMP-0028', siblings);
    expect('error' in r).toBe(true);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/crossTabText.test.ts`
Expected: FAIL（`parseCrossTab` 未定义）

- [ ] **Step 3: 实现 parseCrossTab**

追加到 `crossTabText.ts`：

```typescript
export type ParseResult =
  | { token: CrossTabTokenLike }
  | { error: string };

/** 规范文本片段 → FormulaToken（A.x/B.x/运算符/括号/数字）。失败抛字符串。 */
function canonicalToExprTokens(targetText: string): FormulaToken[] {
  const parts = targetText.trim().split(/\s+/).filter(Boolean);
  return parts.map((p): FormulaToken => {
    if (p.startsWith('A.')) return { type: 'field', value: p.slice(2) };
    if (p.startsWith('B.')) return { type: 'b_field', value: p.slice(2) };
    if (p === '(') return { type: 'bracket_open', value: '(' };
    if (p === ')') return { type: 'bracket_close', value: ')' };
    if (['+', '-', '*', '/'].includes(p)) return { type: 'operator', value: p };
    if (/^-?\d+(\.\d+)?$/.test(p)) return { type: 'number', value: p };
    throw `无法识别的片段「${p}」`;
  });
}

export function parseCrossTab(text: string, siblings: SourceCompLike[]): ParseResult {
  const segs = text.split('|').map((s) => s.trim());
  if (segs.length !== 4) return { error: '格式应为：操作 | 源:CODE | 关联:a=b[,c=d] | 目标:…' };
  const [opSeg, srcSeg, matchSeg, targetSeg] = segs;
  const op = OPERATIONS.find((o) => o.label === opSeg);
  if (!op) return { error: `未知操作「${opSeg}」（应为 ${OPERATIONS.map((o) => o.label).join('/')}）` };
  if (!srcSeg.startsWith('源:')) return { error: '第 2 段应以「源:」开头' };
  const srcCode = srcSeg.slice(2).trim();
  const sourceComp = siblings.find((c) => c.code === srcCode);
  if (!sourceComp) return { error: `源组件 code「${srcCode}」不存在` };
  if (!matchSeg.startsWith('关联:')) return { error: '第 3 段应以「关联:」开头' };
  const pairText = matchSeg.slice(3).trim();
  const match: Array<{ a: string; b: string }> = [];
  for (const seg of pairText.split(',').map((s) => s.trim()).filter(Boolean)) {
    const [a, b] = seg.split('=').map((s) => s.trim());
    if (!a || !b) return { error: `关联对「${seg}」格式应为 a=b` };
    match.push({ a, b });
  }
  if (match.length === 0) return { error: '至少需要一组关联对' };
  if (!targetSeg.startsWith('目标:')) return { error: '第 4 段应以「目标:」开头' };
  const targetText = targetSeg.slice(3).trim();

  const base: CrossTabTokenLike = {
    type: 'cross_tab_ref', source: sourceComp.id, sourceLabel: sourceComp.name,
    target: '', targetExpr: undefined, match, agg: op.agg,
  };
  if (op.agg === 'COUNT') return { token: base };
  // 单列（A.单名、无运算符/无空格）vs 公式
  if (/^A\.[^\s]+$/.test(targetText)) {
    return { token: { ...base, target: targetText.slice(2) } };
  }
  try {
    return { token: { ...base, targetExpr: canonicalToExprTokens(targetText) } };
  } catch (e) {
    return { error: typeof e === 'string' ? e : '目标公式解析失败' };
  }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/component/crossTabText.test.ts`
Expected: PASS（全部用例，含 round-trip）

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/component/crossTabText.ts cpq-frontend/src/pages/component/crossTabText.test.ts
git commit -m "feat(formula-builder): parseCrossTab canonical text->token (P1 Task 3)"
```

---

## Task 4: CrossTabRefDrawer — 简单/高级分层 + 操作选择器（替代裸 agg 术语）

**Files:**
- Modify: `cpq-frontend/src/pages/component/CrossTabRefDrawer.tsx`

- [ ] **Step 1: 引入映射 + mode/operation 状态**

顶部 import 加：

```typescript
import { Segmented } from 'antd';
import { OPERATIONS, operationToAgg, aggToOperation, serializeCrossTab, parseCrossTab } from './crossTabText';
```

在组件 state 区（现 `const [bFieldSel,...]` 之后，L113 附近）加：

```typescript
  const [mode, setMode] = useState<'simple' | 'advanced'>('simple');
  const [operation, setOperation] = useState<string>('single'); // OPERATIONS.key
```

在 open 重置 effect（L116-128）的 `if (!open)` 块内补：

```typescript
      setMode('simple');
      setOperation('single');
```

- [ ] **Step 2: 抽屉头部加「简单/高级」Segmented + 标题改为「公式构建器」**

把 `title="插入跨页签引用"`（L217）改为：

```tsx
      title={
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <span>跨页签公式构建器</span>
          <Segmented
            size="small"
            value={mode}
            onChange={(v) => setMode(v as 'simple' | 'advanced')}
            options={[{ label: '简单', value: 'simple' }, { label: '高级', value: 'advanced' }]}
          />
        </div>
      }
```

- [ ] **Step 3: 用「操作选择器」替换「3. 聚合方式」区块**

把现 L322-336「Aggregation」整块替换为操作选择器（操作驱动 agg；简单模式只露 `simple:true`）：

```tsx
        {/* 1.5 要算什么（操作选择器，驱动 agg） */}
        <div>
          <div style={{ fontWeight: 500, marginBottom: 6, fontSize: 13 }}>
            要算什么
            <span style={{ fontWeight: 400, color: '#8c8c8c', marginLeft: 8, fontSize: 12 }}>
              从源页签按匹配行取值或汇总
            </span>
          </div>
          <Select
            style={{ width: 280 }}
            value={operation}
            onChange={(v) => {
              setOperation(v);
              const a = operationToAgg(v);
              setAgg(a);
              if (a === 'COUNT') setTarget('');
            }}
            options={OPERATIONS.filter((o) => mode === 'advanced' || o.simple).map((o) => ({
              label: o.label, value: o.key,
            }))}
          />
        </div>
```

- [ ] **Step 4: 简单模式隐藏「添加匹配条件」与多对(只留首对) + 「改用公式」开关在简单模式也可见**

把「添加匹配条件」按钮（L310-318）包一层 `{mode === 'advanced' && (...)}`：

```tsx
            {mode === 'advanced' && (
              <Button
                size="small"
                icon={<PlusOutlined />}
                onClick={handleAddPair}
                style={{ alignSelf: 'flex-start' }}
                disabled={!sourceId}
              >
                添加匹配条件
              </Button>
            )}
```

> 「改用公式」(`useFormula`) 开关、目标区(L338-529)、删除匹配对按钮保持不变 —— 简单模式同样可"取一个值/求和"且可切公式;聚合方式由操作选择器驱动,`agg` 状态仍是唯一真相,既有 `handleConfirm` 产出 token 逻辑(L160-199)不动。

- [ ] **Step 5: 编译确认**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误

- [ ] **Step 6: Vite transform 200**

Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/CrossTabRefDrawer.tsx`
Expected: 200

- [ ] **Step 7: 提交**

```bash
git add cpq-frontend/src/pages/component/CrossTabRefDrawer.tsx
git commit -m "feat(formula-builder): CrossTabRefDrawer simple/advanced + operation selector (P1 Task 4)"
```

---

## Task 5: CrossTabRefDrawer — 原始文本双模式（高级，双向同步 + 解析报错）

**Files:**
- Modify: `cpq-frontend/src/pages/component/CrossTabRefDrawer.tsx`

- [ ] **Step 1: 原始文本 state + 解析报错 state**

state 区加：

```typescript
  const [rawText, setRawText] = useState<string>('');
  const [rawError, setRawError] = useState<string>('');
```

open 重置 effect 的 `if (!open)` 块补：

```typescript
      setRawText('');
      setRawError('');
```

- [ ] **Step 2: 可视化→文本（读方向）自动同步**

在 `sourceComp` 定义（L130）之后加 effect：

```typescript
  // 可视化状态变化 → 实时序列化为规范原始文本（仅高级模式展示）
  useEffect(() => {
    if (mode !== 'advanced') return;
    if (!sourceId) { setRawText(''); return; }
    const completePairs = matchPairs.filter((p) => p.a && p.b);
    const tokenLike = {
      type: 'cross_tab_ref' as const,
      source: sourceId,
      sourceLabel: sourceComp?.name ?? '',
      target: (useFormula && agg !== 'COUNT') ? '' : (agg === 'COUNT' ? '' : target),
      targetExpr: (useFormula && agg !== 'COUNT' && targetExpr.length > 0) ? targetExpr : undefined,
      match: completePairs,
      agg,
    };
    setRawText(serializeCrossTab(tokenLike, sourceComp));
    setRawError('');
  }, [mode, sourceId, sourceComp, matchPairs, useFormula, agg, target, targetExpr]);
```

- [ ] **Step 3: 文本→可视化（写方向）「应用文本」按钮**

在底部预览块（L531-556）之后、`</div>`(L557) 之前加高级模式原始文本面板：

```tsx
        {mode === 'advanced' && (
          <div>
            <div style={{ fontWeight: 500, marginBottom: 6, fontSize: 13 }}>
              原始公式文本
              <span style={{ fontWeight: 400, color: '#8c8c8c', marginLeft: 8, fontSize: 12 }}>
                可手改后点「应用文本」回填到上方构建器
              </span>
            </div>
            <Input.TextArea
              rows={2}
              value={rawText}
              onChange={(e) => setRawText(e.target.value)}
              style={{ fontFamily: 'monospace', fontSize: 12 }}
            />
            {rawError && (
              <div style={{ color: '#ff4d4f', fontSize: 12, marginTop: 4 }}>{rawError}</div>
            )}
            <Button
              size="small"
              style={{ marginTop: 6 }}
              onClick={() => {
                const r = parseCrossTab(rawText, siblingComponents);
                if ('error' in r) { setRawError(r.error); return; }
                setRawError('');
                setSourceId(r.token.source);
                setAgg(r.token.agg);
                setOperation(aggToOperation(r.token.agg));
                setMatchPairs(r.token.match.length ? r.token.match.map((p) => ({ a: p.a, b: p.b })) : [{ a: '', b: '' }]);
                if (r.token.targetExpr && r.token.targetExpr.length > 0) {
                  setUseFormula(true);
                  setTargetExpr(r.token.targetExpr);
                  setTarget('');
                } else {
                  setUseFormula(false);
                  setTarget(r.token.target);
                  setTargetExpr([]);
                }
              }}
            >
              应用文本
            </Button>
          </div>
        )}
```

并在顶部 import 把 `Input` 加入 antd 解构：`import { Drawer, Select, Button, Space, Switch, InputNumber, Tag, message, Segmented, Input } from 'antd';`

- [ ] **Step 4: 编译 + transform 确认**

Run: `cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
Expected: 0 错误
Run: `curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/component/CrossTabRefDrawer.tsx`
Expected: 200

- [ ] **Step 5: 提交**

```bash
git add cpq-frontend/src/pages/component/CrossTabRefDrawer.tsx
git commit -m "feat(formula-builder): CrossTabRefDrawer raw-text dual mode two-way sync (P1 Task 5)"
```

---

## Task 6: E2E — 引导式构建 + 文本往返（只读，模仿既有 cross-tab-ref.spec.ts）

**Files:**
- Create: `cpq-frontend/e2e/cross-tab-builder.spec.ts`

- [ ] **Step 1: 写 E2E（复用既有导航路径 + 选择器约定）**

先 Read `cpq-frontend/e2e/cross-tab-ref.spec.ts` 全文，照搬其「登录 → /components-raw → 选 COMP-0002 → 公式标签 → 激活公式 → 『+ 配置跨页签引用…』打开抽屉」前半段导航与选择器；本 spec 在抽屉打开后验证**新 UI**（全程只读、不点保存、不写库）：

```typescript
// cross-tab-builder.spec.ts —— 验证 CrossTabRefDrawer 引导式/分层/双模式 UI
// 复用 cross-tab-ref.spec.ts 的导航 helper（同款 loginAsAdmin + goto /components-raw + 选 COMP-0002 + 公式标签 + 「+ 配置跨页签引用…」）
// 抽屉打开后断言：
//   1. 标题为「跨页签公式构建器」+ 简单/高级 Segmented 存在
//   2. 简单模式下「要算什么」下拉只含「取一个值 / 求和」两项
//   3. 切「高级」后下拉出现「计数 / 最大 / 最小 / 平均」
//   4. 高级模式出现「原始公式文本」TextArea
//   5. 选源组件 + 选「求和」+ 填一对匹配 + 选单列目标 → 原始文本自动出现「求和 | 源:」前缀（读方向同步）
//   6. 不点确定 / 不保存（只读纪律）
// 断言示例：
//   await expect(page.getByText('跨页签公式构建器')).toBeVisible();
//   const opSelect = page.locator('...要算什么区 Select...');
//   ...简单模式 options 文本仅含 取一个值/求和...
//   await page.getByText('高级', { exact: true }).click();
//   await expect(page.locator('textarea')).toBeVisible();
```

> 选择器与中文 UTF-8 约定见 `docs/E2E测试方法.md`；操作下拉的定位优先用区块标题「要算什么」邻近 `.ant-select`，避免与匹配列/源下拉混淆。

- [ ] **Step 2: 跑专项 E2E**

```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/cross-tab-builder.spec.ts --reporter=list
```
Expected: passed（抽屉新 UI 五点断言通过）

- [ ] **Step 3: 回归 — 既有 cross-tab-ref 配置路径 + 报价流程不受影响**

```bash
cd cpq-frontend
npx playwright test --config=e2e/playwright.config.ts e2e/cross-tab-ref.spec.ts e2e/quotation-flow.spec.ts --reporter=list
```
Expected: 两 spec passed；`quotation-flow` `'加载中' final count = 0`（cross_tab_ref token 结构未变 → 求值链路无回归）

- [ ] **Step 4: 提交**

```bash
git add cpq-frontend/e2e/cross-tab-builder.spec.ts
git commit -m "test(formula-builder): E2E guided builder + raw-text round-trip (P1 Task 6)"
```

---

## Task 7: 文档回写 + 设计文档 P1 状态

**Files:**
- Modify: `docs/RECORD.md`（追加 P1 条目）
- Modify: `docs/superpowers/specs/2026-06-09-公式可视化构建器-design.md`（P1 状态标记为"已实施"）

- [ ] **Step 1: RECORD 追加（在文末归档 footer 之前插入）**

```
### [2026-06-09] 公式可视化构建器 P1 — CrossTabRefDrawer 引导式/分层/双模式 | crossTabText.ts(新)+crossTabText.test.ts + CrossTabRefDrawer.tsx | 纯前端 UX,cross_tab_ref token 零变更向后兼容;操作选择器(取值/求和…替裸 agg 术语)+简单/高级 Segmented 分层 + 原始文本双向同步(serialize/parseCrossTab,规范文法 A.x/B.x,解析失败行内报错);单测 6 用例 + E2E cross-tab-builder 只读验证 + cross-tab-ref/quotation-flow 回归。P0 备键(ll_view 子料号)与全量渲染验证另行。设计见 specs/2026-06-09-公式可视化构建器-design.md;路线图 P1.2(CardFormulaDrawer)/P2(条件 SUMIF)/P3(引擎函数化 IF)待续
```

- [ ] **Step 2: 设计文档 §9 待办接续标注 P1 已实施**

在 `docs/superpowers/specs/2026-06-09-公式可视化构建器-design.md` §9 第 1 条「本设计确认后 → 出 P1 实施计划」后追加一行：`> 2026-06-09：P1 实施计划 docs/superpowers/plans/2026-06-09-公式可视化构建器-P1-CrossTabRefDrawer.md 已落地并实现（Task 1~7）。`

- [ ] **Step 3: 提交**

```bash
git add docs/RECORD.md docs/superpowers/specs/2026-06-09-公式可视化构建器-design.md
git commit -m "docs(formula-builder): record P1 CrossTabRefDrawer + design status"
```

---

## Self-Review（已执行）

- **Spec 覆盖**（对照 design §4 P1）：
  - §4.2 引导式四步 → Task 4（操作选择器作"要算什么"，源/匹配/目标区既有，保留）✅
  - §4.3 简单/高级分层 → Task 4（Segmented + `mode` 驱动 OPERATIONS 过滤 / 多匹配对显隐）✅
  - §4.4 双模式双向同步 + 解析报错 → Task 2/3(serialize/parse) + Task 5(读 effect + 应用按钮 + rawError)✅
  - §4.5 关联键只列举不智能提示 → 既有 a/b 双下拉不动，未加任何校验/预警 ✅
  - §4.6 范围裁剪（不含条件/IF/CardFormula）→ 计划无相关任务 ✅
  - §7 校验测试（单测 + E2E + 回归 + tsc + Vite 200）→ Task 1-3 单测 / Task 6 E2E + 回归 / Task 4-5 tsc+Vite ✅
  - §8 向后兼容（token 零变更）→ 全程不改 `CrossTabToken`/`handleConfirm` 产出 ✅
- **占位扫描**：无 TBD/TODO；Task 6 Step1 明确"先 Read 既有 spec 照搬导航"，给了断言要点与示例,非占位（E2E 选择器须按既有 spec 实测对齐，属正常 E2E 编写）。
- **类型一致**：`OPERATIONS`/`operationToAgg`/`aggToOperation`/`serializeCrossTab`/`parseCrossTab`/`CrossTabTokenLike`/`SourceCompLike`/`ParseResult` 在 Task 1-3 定义，Task 4-5 一致引用；`mode`/`operation`/`rawText`/`rawError` 状态命名跨 Task 4-5 一致；`FormulaToken` 字段 `value/label` 用法与 `types.ts` 及既有 `tokenToText` 对齐。

## P0 前置（本计划外，并行）

- `ll_view` 增 `子料号 = mbi.component_no` 列（Flyway V_NN，AP-53：FROM V6 表、`$view` 引用、改后 touch java 重启 Quarkus），使 `来料.子料号` 与 `元素.料件(=ebi.material_no)` 可匹配。
- 仅"在 QT-20260609-1652 上配出跨页签求和并验证渲染总额"这一全链路验收需要它；本计划 7 个 Task 的单测 + 只读 E2E 不依赖 P0。
