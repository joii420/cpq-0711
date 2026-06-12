# 页签连表公式编辑器:配色统一 + 富文本原子块 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 统一 `TabFieldMatrix` chip 配色,并把 `TabJoinFormulaDrawer` 的纯文本公式输入框升级为彩色原子块富文本编辑器,配色与矩阵同源。

**Architecture:** 纯函数配色分类器(`parseFormulaSegments`/`classifyRefSegment`)放进已测试覆盖的 `formulaSerialize.ts`,与保存期 `checkMappable` 同源(判红镜像 `buildMatch` 是否空,`enforceMappable` 区分 EXCEL/token 组件)。新建受控 `contentEditable` 组件 `FormulaRichInput` 消费分类器渲染彩色块;`expression` 字符串契约不变,保存/试算/序列化全不动。

**Tech Stack:** React 18 + TypeScript + Ant Design + Vitest(单测)+ Playwright(E2E)。

**Spec:** `docs/superpowers/specs/2026-06-12-tabjoin-formula-editor-coloring-design.md`

**前置(执行时):** 本计划须在隔离 worktree 分支内执行(用 `superpowers:using-git-worktrees` 创建)。worktree 只隔离 git 工作区,**复用主工作区已运行的前端 dev server(5174),不另起、不重装依赖**。

**测试命令约定:**
- 单测:`cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts`
- 类型:`cd cpq-frontend && npx tsc --noEmit -p tsconfig.json`
- Vite transform:`curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/<相对路径>`

---

## File Structure

| 文件 | 职责 | 动作 |
|---|---|---|
| `cpq-frontend/src/pages/component/formulaSerialize.ts` | 纯模块:新增 `classifyRefSegment` / `blockDisplay` / `parseFormulaSegments` 三个导出 + `FormulaSegment`/`SegmentColor` 类型 | 修改 |
| `cpq-frontend/src/pages/component/formulaSerialize.test.ts` | 分类器/切分单测 | 修改 |
| `cpq-frontend/src/pages/template/tabjoin/TabFieldMatrix.tsx` | 矩阵 chip 配色统一(纯样式) | 修改 |
| `cpq-frontend/src/pages/template/tabjoin/FormulaRichInput.tsx` | 受控 contentEditable 彩色块编辑器 | 新建 |
| `cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx` | 用 `FormulaRichInput` 替换 `Input.TextArea`,接线 insertAtCursor | 修改 |

---

## Task 1: Point 1 — `TabFieldMatrix` chip 配色统一(纯样式)

**Files:**
- Modify: `cpq-frontend/src/pages/template/tabjoin/TabFieldMatrix.tsx`

纯样式改动,无单测。改三处 Tag 样式:普通可比明细加蓝边、小计列绿改黄、(总计与不可比不动)。

- [ ] **Step 1: 普通可比明细 chip 加蓝边**

定位 `TabFieldMatrix.tsx` 第 162-166 行(`sourceFiner` 之外的普通可比明细分支,当前 `background:'#fff'` 无 borderColor):

```tsx
                    return (
                      <Tag key={f} onClick={() => onInsert(`[${ref}.${f}]`)}
                        style={{ cursor: 'pointer', background: '#fff', borderColor: '#91caff', margin: 0, fontSize: 12,
                          padding: '3px 9px', borderStyle: 'solid', userSelect: 'none' }}>{f}</Tag>
                    );
```

(相对原代码只加了 `borderColor: '#91caff'`,与上方 `sourceFiner` 的蓝边统一。)

- [ ] **Step 2: 小计列 chip 绿改黄**

定位第 182-200 行小计列 `Tag` 的 style,把绿色三项改黄:

```tsx
                      <Tag
                        key={f}
                        style={{
                          cursor: 'pointer',
                          color: '#fa8c16',
                          borderColor: '#ffd591',
                          borderStyle: 'dashed',
                          background: '#fff',
                          margin: 0,
                          fontSize: 12,
                          padding: '3px 9px',
                          userSelect: 'none',
                        }}
                        onClick={() => onInsert(`[${ref}.${f}]`)}
                      >
                        {f}(小计)
                      </Tag>
```

(只把 `color:'#389e0d'`→`'#fa8c16'`、`borderColor:'#b7eb8f'`→`'#ffd591'`;`borderStyle:'dashed'`/`background:'#fff'` 不变。页签总计 Tag 第 215-230 行**保持绿色不动**。)

- [ ] **Step 3: 类型检查 + Vite transform 自检**

```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/template/tabjoin/TabFieldMatrix.tsx
```

Expected: tsc 0 错误;curl 输出 `200`。

- [ ] **Step 4: Commit**

```bash
git add cpq-frontend/src/pages/template/tabjoin/TabFieldMatrix.tsx
git commit -m "feat(tabjoin): 矩阵 chip 配色统一(明细蓝/小计黄/总计绿)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `classifyRefSegment` 单段判色(TDD)

**Files:**
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.test.ts`(加 describe 块)
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.ts`(加类型 + 函数)

判色逻辑严格对齐 spec §3.4 判色表,与保存期同源:`component_subtotal`(小计列/页签总计)无 match 约束恒绿/黄;`cross_tab_ref`(明细)在 `enforceMappable` 下 match 空判红。`buildMatch`/`findTabByRef` 是同模块私有函数,直接复用。

- [ ] **Step 1: 写失败测试**

先把 `classifyRefSegment` 与 `parseFormulaSegments` 加进**已有的顶部 import**(第 23-30 行那个 `from './formulaSerialize'`),避免中途再写 import:

```ts
import {
  expressionToTokens,
  tokensToDrawerExpression,
  checkMappable,
  comparable,
  isSubset,
  __lexForTest,
  classifyRefSegment,
  parseFormulaSegments,
} from './formulaSerialize';
```

然后在 `formulaSerialize.test.ts` 末尾追加:

```ts
// ─────────────────────────────────────────────
// classifyRefSegment — 配色分类(spec §3.4)
// ─────────────────────────────────────────────
describe('classifyRefSegment', () => {
  const self = ['料号'];

  it('页签总计 [回料(总计)] → green', () => {
    expect(classifyRefSegment('回料(总计)', allTabs, self, true)).toEqual({ kind: 'tab-total', color: 'green' });
  });
  it('页签总计但别名查不到 → red', () => {
    expect(classifyRefSegment('不存在(总计)', allTabs, self, true).color).toBe('red');
  });
  it('小计列 [回料.金额](金额∈subtotalCols) → yellow', () => {
    expect(classifyRefSegment('回料.金额', allTabs, self, true)).toEqual({ kind: 'subtotal', color: 'yellow' });
  });
  it('可比明细 [回料.用量](用量∈detailFields,可比) → blue', () => {
    expect(classifyRefSegment('回料.用量', allTabs, self, true)).toEqual({ kind: 'detail', color: 'blue' });
  });
  it('不可比明细 [无键页签.费率](rowKeyFields=[] → match 空) + enforceMappable → red', () => {
    expect(classifyRefSegment('无键页签.费率', allTabs, self, true).color).toBe('red');
  });
  it('同一不可比明细,enforceMappable=false(EXCEL)→ blue', () => {
    expect(classifyRefSegment('无键页签.费率', allTabs, self, false).color).toBe('blue');
  });
  it('selfRowKeyFields=[] + 明细 + enforceMappable → red(镜像 buildMatch 空)', () => {
    expect(classifyRefSegment('回料.用量', allTabs, [], true).color).toBe('red');
  });
  it('字段不在该 tab 任何字段 [回料.不存在列] → red', () => {
    expect(classifyRefSegment('回料.不存在列', allTabs, self, true).color).toBe('red');
  });
  it('宿主自身列 [单重](无点无总计)→ blue self-field', () => {
    expect(classifyRefSegment('单重', allTabs, self, true)).toEqual({ kind: 'self-field', color: 'blue' });
  });
  it('[别名(总计)] 优先于 self-field(行序)', () => {
    expect(classifyRefSegment('回料(总计)', allTabs, self, true).kind).toBe('tab-total');
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts`
Expected: FAIL（`classifyRefSegment is not a function` / 未导出）。

- [ ] **Step 3: 实现 `classifyRefSegment`**

在 `formulaSerialize.ts` 末尾(`checkMappable` 之后)追加。`buildMatch` 与 `findTabByRef` 已在本模块定义,直接调用:

```ts
// ─────────────────────────────────────────────
// 配色分类器(显示侧,与保存期 checkMappable 同源)
// ─────────────────────────────────────────────

export type SegmentColor = 'blue' | 'yellow' | 'green' | 'red' | null;

export interface FormulaSegment {
  /** 原始片段文本(块含括号,文本原样) */
  raw: string;
  /** true=原子块([...]/{...});false=普通文本 */
  isBlock: boolean;
  /** 块展示文本(去括号、'.'→'·');文本段等于 raw */
  display: string;
  /** 块配色;文本段 null */
  color: SegmentColor;
}

/**
 * 单个 [...] body 判色(body 已去外层方括号且已 trim)。
 * 行序即优先级(spec §3.4):总计无点 → 小计列 → 明细 → 查不到 → self-field。
 * enforceMappable: NORMAL/SUBTOTAL=true(明细 match 空判红);EXCEL=false(解析得到即蓝)。
 */
export function classifyRefSegment(
  body: string,
  tabDefs: TabDef[],
  selfRowKeyFields: string[] | undefined,
  enforceMappable: boolean,
): { kind: string; color: SegmentColor } {
  // 1) 无点 + (总计) 结尾 → 整页签小计(component_subtotal,无 match 约束)
  if (!body.includes('.') && body.endsWith('(总计)')) {
    const alias = body.slice(0, -'(总计)'.length);
    return findTabByRef(tabDefs, alias)
      ? { kind: 'tab-total', color: 'green' }
      : { kind: 'invalid', color: 'red' };
  }

  // 含点 → 跨页签引用
  if (body.includes('.')) {
    const dotIdx = body.indexOf('.');
    const alias = body.slice(0, dotIdx);
    let field = body.slice(dotIdx + 1);
    const isAgg = field.endsWith('(总计)');
    if (isAgg) field = field.slice(0, -'(总计)'.length);

    const tab = findTabByRef(tabDefs, alias);
    if (!tab) return { kind: 'invalid', color: 'red' };

    // 2) 非聚合 + 字段∈subtotalCols → 小计列(component_subtotal,无 match 约束)
    if (!isAgg && (tab.subtotalCols ?? []).includes(field)) {
      return { kind: 'subtotal', color: 'yellow' };
    }

    // 字段必须是该 tab 的真实列(明细或小计),否则查不到 → 红
    const known = new Set([...(tab.detailFields ?? []), ...(tab.subtotalCols ?? [])]);
    if (!known.has(field)) return { kind: 'invalid', color: 'red' };

    // 3/4) 明细 cross_tab_ref:enforceMappable 下镜像 buildMatch 是否空判红
    if (enforceMappable) {
      const matchEmpty = buildMatch(tab.rowKeyFields ?? [], selfRowKeyFields).length === 0;
      if (matchEmpty) return { kind: 'invalid', color: 'red' };
    }
    return { kind: 'detail', color: 'blue' };
  }

  // 6) 无点无总计 → 宿主自身列(tabDefs 无法证伪)
  return { kind: 'self-field', color: 'blue' };
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts`
Expected: PASS（含原有用例 + 10 条新 classifyRefSegment 用例）。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/component/formulaSerialize.ts cpq-frontend/src/pages/component/formulaSerialize.test.ts
git commit -m "feat(formula): classifyRefSegment 单段判色(与 checkMappable 同源)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `parseFormulaSegments` 宽容切分(TDD)

**Files:**
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.test.ts`
- Modify: `cpq-frontend/src/pages/component/formulaSerialize.ts`

把整串切成有序 `FormulaSegment[]`,`[...]`/`{...}` 成块、其余成文本;对未闭合括号宽容降级;块 body 先 trim 再判色;无损 round-trip。

- [ ] **Step 1: 写失败测试**

在 `formulaSerialize.test.ts` 末尾追加(`parseFormulaSegments` 已在 Task 2 的 import 引入):

```ts
describe('parseFormulaSegments', () => {
  const self = ['料号'];

  it('混合串切分顺序:SUM([投料.金额] * [回料.用量])', () => {
    const segs = parseFormulaSegments('SUM([投料.金额] * [回料.用量])', allTabs, self, true);
    expect(segs.map((s) => s.raw)).toEqual(['SUM(', '[投料.金额]', ' * ', '[回料.用量]', ')']);
    expect(segs.map((s) => s.isBlock)).toEqual([false, true, false, true, false]);
    // 投料.金额∈subtotalCols → yellow;回料.用量∈detailFields 可比 → blue
    expect(segs[1].color).toBe('yellow');
    expect(segs[3].color).toBe('blue');
  });

  it('块 display 去括号、点换 ·', () => {
    const segs = parseFormulaSegments('[投料.金额]', allTabs, self, true);
    expect(segs[0].display).toBe('投料·金额');
  });

  it('[回料(总计)] display 保留总计、green', () => {
    const segs = parseFormulaSegments('[回料(总计)]', allTabs, self, true);
    expect(segs[0].display).toBe('回料(总计)');
    expect(segs[0].color).toBe('green');
  });

  it('回显形态 SUM([回料.用量]):SUM(/)为文本,内层块 blue', () => {
    const segs = parseFormulaSegments('SUM([回料.用量])', allTabs, self, true);
    expect(segs.map((s) => s.isBlock)).toEqual([false, true, false]);
    expect(segs[1].color).toBe('blue');
  });

  it('带空格 [投料. 金额]:body trim 后判色(金额∈subtotalCols → yellow)', () => {
    const segs = parseFormulaSegments('[投料. 金额]', allTabs, self, true);
    expect(segs[0].color).toBe('yellow');
  });

  it('未闭合 [ 不抛错,降级文本段', () => {
    const segs = parseFormulaSegments('[投料.金额', allTabs, self, true);
    expect(segs).toEqual([{ raw: '[投料.金额', isBlock: false, display: '[投料.金额', color: null }]);
  });

  it('{路径} → 中性块 color null', () => {
    const segs = parseFormulaSegments('{a.b}', allTabs, self, true);
    expect(segs[0]).toMatchObject({ isBlock: true, color: null, display: 'a.b' });
  });

  it('空串 → []', () => {
    expect(parseFormulaSegments('', allTabs, self, true)).toEqual([]);
  });

  it('round-trip 无损:raw 拼接 === 原串', () => {
    const expr = 'SUM([投料.金额] * [回料.用量]) + [回料(总计)] - 3.5';
    const segs = parseFormulaSegments(expr, allTabs, self, true);
    expect(segs.map((s) => s.raw).join('')).toBe(expr);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts`
Expected: FAIL（`parseFormulaSegments is not a function`）。

- [ ] **Step 3: 实现 `blockDisplay` + `parseFormulaSegments`**

在 `formulaSerialize.ts` 中 `classifyRefSegment` 之后追加:

```ts
/** 块展示文本:去外层括号已在调用处剥离;此处把首个 '.' 换 '·'(总计/裸字段不含点则原样) */
function blockDisplay(body: string): string {
  return body.replace('.', '·');
}

/**
 * 把表达式串切成有序 FormulaSegment[](块 + 文本交替),供 FormulaRichInput 渲染。
 * 宽容:未闭合 [ / { 降级为文本段(用户正在打字),绝不抛错。
 * 块 body 先 trim 再判色,与 lex() 内 body.trim() 对齐。
 */
export function parseFormulaSegments(
  expr: string,
  tabDefs: TabDef[],
  selfRowKeyFields: string[] | undefined,
  enforceMappable: boolean,
): FormulaSegment[] {
  const segs: FormulaSegment[] = [];
  let textBuf = '';
  const flush = () => {
    if (textBuf) {
      segs.push({ raw: textBuf, isBlock: false, display: textBuf, color: null });
      textBuf = '';
    }
  };

  let i = 0;
  while (i < expr.length) {
    const ch = expr[i];
    if (ch === '[') {
      const end = expr.indexOf(']', i);
      if (end === -1) { textBuf += expr.slice(i); break; }
      flush();
      const raw = expr.slice(i, end + 1);
      const body = expr.slice(i + 1, end).trim();
      const { color } = classifyRefSegment(body, tabDefs, selfRowKeyFields, enforceMappable);
      segs.push({ raw, isBlock: true, display: blockDisplay(body), color });
      i = end + 1;
      continue;
    }
    if (ch === '{') {
      const end = expr.indexOf('}', i);
      if (end === -1) { textBuf += expr.slice(i); break; }
      flush();
      const raw = expr.slice(i, end + 1);
      const body = expr.slice(i + 1, end).trim();
      segs.push({ raw, isBlock: true, display: body, color: null });
      i = end + 1;
      continue;
    }
    textBuf += ch;
    i++;
  }
  flush();
  return segs;
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts`
Expected: PASS（全部 describe 绿）。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/component/formulaSerialize.ts cpq-frontend/src/pages/component/formulaSerialize.test.ts
git commit -m "feat(formula): parseFormulaSegments 宽容切分 + round-trip 无损

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `FormulaRichInput` 受控 contentEditable 组件

**Files:**
- Create: `cpq-frontend/src/pages/template/tabjoin/FormulaRichInput.tsx`

无单测(contentEditable headless 难驱动);靠 tsc + Vite 200 + 真机验收。组件职责 = 字符串 ⇄ 彩色块 DOM 双向渲染 + 光标/插入/IME 处理。

- [ ] **Step 1: 写组件**

创建 `cpq-frontend/src/pages/template/tabjoin/FormulaRichInput.tsx`,完整内容:

```tsx
import React, {
  forwardRef, useCallback, useEffect, useImperativeHandle, useRef,
} from 'react';
import type { TabDef } from '../../../services/tabJoinFormulaService';
import { parseFormulaSegments, type SegmentColor } from '../../component/formulaSerialize';

export interface FormulaRichInputHandle {
  /** 在当前光标处插入文本;caretOffsetFromEnd 用于把光标落到 fn() 括号内 */
  insertAtCursor: (text: string, caretOffsetFromEnd?: number) => void;
}

interface Props {
  value: string;
  onChange: (next: string) => void;
  tabDefs: TabDef[];
  selfRowKeyFields?: string[];
  /** EXCEL→false(不按 match 红);NORMAL/SUBTOTAL→true */
  enforceMappable: boolean;
  placeholder?: string;
}

const BLOCK_STYLE: Record<NonNullable<SegmentColor> | 'neutral', React.CSSProperties> = {
  blue:    { background: '#e6f4ff', border: '1px solid #91caff', color: '#0958d9' },
  yellow:  { background: '#fffbe6', border: '1px solid #ffd591', color: '#d46b08' },
  green:   { background: '#f6ffed', border: '1px solid #b7eb8f', color: '#389e0d' },
  red:     { background: '#fff1f0', border: '1px solid #ffa39e', color: '#cf1322' },
  neutral: { background: '#f5f5f5', border: '1px solid #d9d9d9', color: '#595959' },
};

/** 读 contentEditable DOM 回字符串:文本节点取 textContent,块取 data-raw,递归兜底 wrapper */
function readBack(root: HTMLElement): string {
  let out = '';
  root.childNodes.forEach((node) => {
    if (node.nodeType === Node.TEXT_NODE) {
      out += node.textContent ?? '';
    } else if (node instanceof HTMLElement) {
      const raw = node.getAttribute('data-raw');
      if (raw != null) out += raw;
      else if (node.tagName === 'BR') { /* 单行公式,忽略换行 */ }
      else out += readBack(node);
    }
  });
  return out;
}

/** 计算光标在「raw 字符串」里的偏移(块按 data-raw 长度整体计) */
function caretOffset(root: HTMLElement): number {
  const sel = window.getSelection();
  if (!sel || sel.rangeCount === 0) return root.textContent ? readBack(root).length : 0;
  const range = sel.getRangeAt(0);
  const pre = range.cloneRange();
  pre.selectNodeContents(root);
  pre.setEnd(range.endContainer, range.endOffset);
  // 用一个临时片段读出 pre 范围内的 raw 长度
  const frag = pre.cloneContents();
  const tmp = document.createElement('div');
  tmp.appendChild(frag);
  return readBack(tmp).length;
}

/** 重建 DOM 后把光标恢复到 raw 偏移 offset(块原子:落到块边界) */
function restoreCaret(root: HTMLElement, offset: number) {
  const sel = window.getSelection();
  if (!sel) return;
  let acc = 0;
  for (const node of Array.from(root.childNodes)) {
    if (node.nodeType === Node.TEXT_NODE) {
      const len = (node.textContent ?? '').length;
      if (offset <= acc + len) {
        const r = document.createRange();
        r.setStart(node, Math.max(0, offset - acc));
        r.collapse(true);
        sel.removeAllRanges();
        sel.addRange(r);
        return;
      }
      acc += len;
    } else if (node instanceof HTMLElement) {
      const raw = node.getAttribute('data-raw') ?? '';
      const len = raw.length;
      if (offset <= acc) { // 落在该块前
        const r = document.createRange();
        r.setStartBefore(node);
        r.collapse(true);
        sel.removeAllRanges();
        sel.addRange(r);
        return;
      }
      acc += len;
    }
  }
  // 落到末尾
  const r = document.createRange();
  r.selectNodeContents(root);
  r.collapse(false);
  sel.removeAllRanges();
  sel.addRange(r);
}

const FormulaRichInput = forwardRef<FormulaRichInputHandle, Props>(function FormulaRichInput(
  { value, onChange, tabDefs, selfRowKeyFields, enforceMappable, placeholder }, ref,
) {
  const editorRef = useRef<HTMLDivElement>(null);
  const composingRef = useRef(false);
  /** 最近一次由本组件 emit 的字符串,用来判断 value 是否外部变更(避免无谓重建打断光标) */
  const lastEmittedRef = useRef<string | null>(null);

  /** 把 value 渲染进编辑器 DOM(块 + 文本节点),可选恢复光标偏移 */
  const renderInto = useCallback((str: string, caret?: number) => {
    const el = editorRef.current;
    if (!el) return;
    const segs = parseFormulaSegments(str, tabDefs, selfRowKeyFields, enforceMappable);
    el.innerHTML = '';
    for (const s of segs) {
      if (!s.isBlock) {
        el.appendChild(document.createTextNode(s.raw));
      } else {
        const span = document.createElement('span');
        span.setAttribute('contenteditable', 'false');
        span.setAttribute('data-raw', s.raw);
        const sty = BLOCK_STYLE[(s.color ?? 'neutral') as keyof typeof BLOCK_STYLE];
        Object.assign(span.style, {
          ...sty, borderRadius: '4px', padding: '0 5px', margin: '0 1px',
          fontSize: '13px', whiteSpace: 'nowrap', userSelect: 'none', cursor: 'default',
        } as CSSStyleDeclaration);
        span.textContent = s.display;
        el.appendChild(span);
      }
    }
    if (caret != null) restoreCaret(el, caret);
  }, [tabDefs, selfRowKeyFields, enforceMappable]);

  // 外部 value 变化(打开公式/清空/插入后父级回写)→ 重建 DOM。
  // 本组件自身 emit 的同值跳过,避免重建打断正在输入的光标。
  useEffect(() => {
    if (value === lastEmittedRef.current) return;
    renderInto(value);
    lastEmittedRef.current = value;
  }, [value, renderInto]);

  const emit = useCallback(() => {
    const el = editorRef.current;
    if (!el) return;
    const str = readBack(el);
    lastEmittedRef.current = str;
    onChange(str);
  }, [onChange]);

  const handleInput = useCallback(() => {
    if (composingRef.current) return; // IME 进行中:不读回、不重建
    const el = editorRef.current;
    if (!el) return;
    const offset = caretOffset(el);
    const str = readBack(el);
    lastEmittedRef.current = str;
    onChange(str);
    // 重建以收块/上色,并恢复光标
    renderInto(str, offset);
  }, [onChange, renderInto]);

  const handleCompositionEnd = useCallback(() => {
    composingRef.current = false;
    handleInput();
  }, [handleInput]);

  // 退格:光标贴在块右边界(块原子)→ 删整块
  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key !== 'Backspace') return;
    const sel = window.getSelection();
    if (!sel || sel.rangeCount === 0 || !sel.isCollapsed) return;
    const range = sel.getRangeAt(0);
    let prev: Node | null = null;
    if (range.startContainer.nodeType === Node.TEXT_NODE && range.startOffset === 0) {
      prev = range.startContainer.previousSibling;
    } else if (range.startContainer === editorRef.current) {
      prev = editorRef.current?.childNodes[range.startOffset - 1] ?? null;
    }
    if (prev instanceof HTMLElement && prev.getAttribute('data-raw') != null) {
      e.preventDefault();
      prev.remove();
      handleInput();
    }
  }, [handleInput]);

  // 粘贴强制纯文本
  const handlePaste = useCallback((e: React.ClipboardEvent) => {
    e.preventDefault();
    const text = e.clipboardData.getData('text/plain');
    document.execCommand('insertText', false, text);
  }, []);

  useImperativeHandle(ref, () => ({
    insertAtCursor: (text: string, caretOffsetFromEnd = 0) => {
      const el = editorRef.current;
      if (!el) return;
      el.focus();
      const sel = window.getSelection();
      // 光标不在编辑器内 → 落到末尾
      if (!sel || sel.rangeCount === 0 || !el.contains(sel.getRangeAt(0).startContainer)) {
        const r = document.createRange();
        r.selectNodeContents(el);
        r.collapse(false);
        sel?.removeAllRanges();
        sel?.addRange(r);
      }
      document.execCommand('insertText', false, text);
      // 计算插入后目标偏移并重建上色
      const el2 = editorRef.current;
      if (el2) {
        const off = Math.max(0, caretOffset(el2) - caretOffsetFromEnd);
        const str = readBack(el2);
        lastEmittedRef.current = str;
        onChange(str);
        renderInto(str, off);
      }
    },
  }), [onChange, renderInto]);

  return (
    <div
      ref={editorRef}
      contentEditable
      suppressContentEditableWarning
      onInput={handleInput}
      onCompositionStart={() => { composingRef.current = true; }}
      onCompositionEnd={handleCompositionEnd}
      onKeyDown={handleKeyDown}
      onPaste={handlePaste}
      data-placeholder={placeholder}
      style={{
        minHeight: 52, border: '1px solid #d9d9d9', borderRadius: 6,
        padding: '8px 11px', marginTop: 4, lineHeight: '24px',
        fontFamily: 'SF Mono, Consolas, Monaco, monospace', fontSize: 13,
        outline: 'none', overflowWrap: 'anywhere', background: '#fff',
      }}
    />
  );
});

export default FormulaRichInput;
```

- [ ] **Step 2: 类型检查 + Vite transform 自检**

```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/template/tabjoin/FormulaRichInput.tsx
```

Expected: tsc 0 错误;curl `200`。

- [ ] **Step 3: Commit**

```bash
git add cpq-frontend/src/pages/template/tabjoin/FormulaRichInput.tsx
git commit -m "feat(tabjoin): FormulaRichInput 受控 contentEditable 彩色块编辑器

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: 接线 — `TabJoinFormulaDrawer` 用 `FormulaRichInput` 替换 TextArea

**Files:**
- Modify: `cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx`

替换输入框 + 删旧 `insertAtCursor`(textarea selection 版)+ `exprRef` 类型迁移 + 推导 `enforceMappable`。OPS/FUNCS 工具条与矩阵 `onInsert` 调用点不变(仍调 `insertAtCursor`,但现在走组件 ref)。

- [ ] **Step 1: import + 类型 + enforceMappable**

在 `TabJoinFormulaDrawer.tsx` 顶部 import 区加:

```tsx
import FormulaRichInput, { type FormulaRichInputHandle } from './tabjoin/FormulaRichInput';
```

把 `exprRef`(第 57 行 `const exprRef = useRef<any>(null);`)改为:

```tsx
  const exprRef = useRef<FormulaRichInputHandle | null>(null);
```

在 `expression` state 附近加(组件体内,return 之前):

```tsx
  const enforceMappable = componentType !== 'EXCEL';
```

- [ ] **Step 2: 删旧 `insertAtCursor`,改为转发组件 ref**

删除第 86-103 行整个旧 `insertAtCursor`(基于 `exprRef.current?.resizableTextArea?.textArea` 的 textarea 版),替换为:

```tsx
  /** 在富文本光标处插入文本(转发给 FormulaRichInput),caretOffsetFromEnd 用于 fn() 光标落括号内 */
  const insertAtCursor = (text: string, caretOffsetFromEnd = 0) => {
    exprRef.current?.insertAtCursor(text, caretOffsetFromEnd);
  };
```

(OPS 第 309-311 行、FUNCS 第 317-323 行、矩阵 `onInsert` 第 360-362 行**不动**,仍调 `insertAtCursor`。)

- [ ] **Step 3: 替换输入框**

把第 294-301 行的 `<Input.TextArea ... />` 整块替换为:

```tsx
      <FormulaRichInput
        ref={exprRef}
        value={expression}
        onChange={setExpression}
        tabDefs={tabDefs}
        selfRowKeyFields={selfRowKeyFields}
        enforceMappable={enforceMappable}
        placeholder="例:[投料.金额] * [加工.工时] + [回料(总计)]"
      />
```

若 `Input` 在替换后不再被使用,从第 2 行 antd import 中移除 `Input`(用 `npx tsc` 的 unused 报错确认)。

- [ ] **Step 4: 类型检查 + Vite transform 自检**

```bash
cd cpq-frontend && npx tsc --noEmit -p tsconfig.json
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:5174/src/pages/template/TabJoinFormulaDrawer.tsx
```

Expected: tsc 0 错误;curl `200`。

- [ ] **Step 5: Commit**

```bash
git add cpq-frontend/src/pages/template/TabJoinFormulaDrawer.tsx
git commit -m "feat(tabjoin): TabJoinFormulaDrawer 接入 FormulaRichInput(替换 TextArea)

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: 协议自检 — 双 spec E2E 回归

**Files:** 无(仅运行验证)

`TabJoinFormulaDrawer` 是公式协议入口,按 CLAUDE.md 跑双 spec 兜底未回归。

- [ ] **Step 1: 全量单测**

Run: `cd cpq-frontend && npx vitest run src/pages/component/formulaSerialize.test.ts`
Expected: 全绿。

- [ ] **Step 2: E2E 双 spec**

Run:
```bash
cd cpq-frontend && npx playwright test --config=e2e/playwright.config.ts e2e/quotation-flow.spec.ts e2e/composite-product-flow.spec.ts --reporter=list
```
Expected: 全部 `passed`,`'加载中' final count = 0`。若某 spec 因环境(后端/DB)无法跑,记录原因并请用户在真机复测。

- [ ] **Step 3: 真机验收(用户)**

请用户在浏览器实测以下交互(headless 测不到):
1. 打开公式抽屉 → 点矩阵明细/小计/总计 chip → 输入框出现对应蓝/黄/绿块;
2. 手敲 `[` 别名 `.` 字段 → 闭合 `]` 后收成彩色块;
3. 中文输入法打页签名 → 候选不被打断;
4. 退格 → 整块删除;
5. 手敲一个不可比明细引用 → 显示红块;
6. 打开一条已存公式(含 `SUM(...)` / 行级聚合)→ 正确回显彩色块;
7. 保存 → 与改造前行为一致(token/excel 落库不变)。

- [ ] **Step 4: 完成宣告(附"已自检"声明行)**

例:
> 单测全绿 ✅;tsc 0 错 ✅;TabFieldMatrix/FormulaRichInput/TabJoinFormulaDrawer → Vite 200 ✅;E2E quotation-flow + composite-product-flow passed ✅;真机交互待用户验收。

---

## 收尾(用户确认功能达标后)

走 `superpowers:finishing-a-development-branch` 合并并清理:切回 `master` → `git merge <特性分支>` → 跑单测确认 → `git worktree remove` + 删特性分支。
