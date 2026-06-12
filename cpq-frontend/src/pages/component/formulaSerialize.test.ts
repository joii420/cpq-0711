/**
 * formulaSerialize.test.ts
 *
 * Task 4.4 — TDD tests for the token serialisation bridge.
 *
 * Grammar under test (bracket syntax from TabJoinFormulaDrawer):
 *   [field]             → {type:'field', value}
 *   [alias.field]       → {type:'cross_tab_ref', agg:'NONE', ...}
 *   [alias.field(总计)] → {type:'cross_tab_ref', agg:'SUM', target:'field', ...}
 *   [alias(总计)]       → {type:'cross_tab_ref', agg:'SUM', target:'', ...}  (whole-tab total)
 *   + - * / × ÷        → {type:'operator', value: normalised}
 *   ( )                → bracket_open / bracket_close
 *   numeric literals   → {type:'number', value}
 *   {path}             → {type:'path', path}
 *
 * Alias convention: alias in the expression == tabDef.alias (confirmed from TabFieldMatrix
 * and buildColumn in TabJoinFormulaDrawer which do `tabDefs.find(d => d.alias === a)`).
 *
 * match row-key alignment: positional zip of tabDef.rowKeyFields (a) × selfRowKeyFields (b).
 */

import { describe, it, expect } from 'vitest';
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
import type { TabDef } from '../../services/tabJoinFormulaService';
import type { FormulaToken } from './types';

// ─────────────────────────────────────────────
// Shared fixtures
// ─────────────────────────────────────────────

/** 回料 tab definition */
const tabRL: TabDef = {
  alias: 'COMP_RL',
  tabKey: 'tab-rl',
  componentId: 'uuid-rl',
  componentName: '回料',
  componentType: 'RECYCLE',
  rowKeyFields: ['料号'],
  detailFields: ['金额', '用量'],
  subtotalCols: ['金额'],
};

/** 投料 tab definition (different row key) */
const tabInv: TabDef = {
  alias: 'COMP_INV',
  tabKey: 'tab-inv',
  componentId: 'uuid-inv',
  componentName: '投料',
  componentType: 'INVEST',
  rowKeyFields: ['料号'],
  detailFields: ['单重', '单价', '金额'],
  subtotalCols: ['金额'],
};

/** Tab with two row-key fields (multi-key join scenario) */
const tabMultiKey: TabDef = {
  alias: 'COMP_MK',
  tabKey: 'tab-mk',
  componentId: 'uuid-mk',
  componentName: '多键',
  componentType: 'INVEST',
  rowKeyFields: ['料号', '工序'],
  detailFields: ['单价'],
  subtotalCols: [],
};

/** Tab with no row-key fields */
const tabNoKey: TabDef = {
  alias: 'COMP_NK',
  tabKey: 'tab-nk',
  componentId: 'uuid-nk',
  componentName: '无键页签',
  componentType: 'INVEST',
  rowKeyFields: [],
  detailFields: ['费率'],
  subtotalCols: [],
};

const allTabs: TabDef[] = [tabRL, tabInv, tabMultiKey, tabNoKey];

// Self (this component) row-key fields — matches tabRL and tabInv
const selfRKF = ['料号'];

// ─────────────────────────────────────────────
// 1. Core expression → tokens
// ─────────────────────────────────────────────

describe('expressionToTokens — basic', () => {
  it('plain same-component field [单重]', () => {
    const tokens = expressionToTokens('[单重]', allTabs, selfRKF);
    expect(tokens).toHaveLength(1);
    expect(tokens[0]).toEqual({ type: 'field', value: '单重' });
  });

  it('cross-tab detail ref [COMP_RL.用量] → agg NONE (用量 is a detailField, not subtotalCol)', () => {
    const tokens = expressionToTokens('[COMP_RL.用量]', allTabs, selfRKF);
    expect(tokens).toHaveLength(1);
    expect(tokens[0]).toMatchObject({
      type: 'cross_tab_ref',
      source: 'uuid-rl',
      sourceLabel: '回料',
      target: '用量',
      agg: 'NONE',
    });
  });

  it('cross-tab aggregated [COMP_RL.金额(总计)] → agg SUM', () => {
    const tokens = expressionToTokens('[COMP_RL.金额(总计)]', allTabs, selfRKF);
    expect(tokens).toHaveLength(1);
    expect(tokens[0]).toMatchObject({
      type: 'cross_tab_ref',
      source: 'uuid-rl',
      target: '金额',
      agg: 'SUM',
    });
  });

  it('whole-tab total [COMP_RL(总计)] → component_subtotal (uses tab primary subtotalCol)', () => {
    // FIX (2026-06-10): bare [alias(总计)] now maps to component_subtotal, not cross_tab_ref.
    // COMP_RL has a single subtotalCol 金额, so the bare total resolves to that column.
    const tokens = expressionToTokens('[COMP_RL(总计)]', allTabs, selfRKF);
    expect(tokens).toHaveLength(1);
    expect(tokens[0]).toMatchObject({
      type: 'component_subtotal',
      component_code: 'COMP_RL',
      value: '金额',
    });
  });

  it('numeric literal', () => {
    const tokens = expressionToTokens('3.14', allTabs);
    expect(tokens).toHaveLength(1);
    expect(tokens[0]).toEqual({ type: 'number', value: '3.14' });
  });

  it('operators + - * / are preserved', () => {
    const tokens = expressionToTokens('1 + 2 - 3 * 4 / 5', allTabs);
    const ops = tokens.filter((t) => t.type === 'operator').map((t) => t.value);
    expect(ops).toEqual(['+', '-', '*', '/']);
  });

  it('× is normalised to *', () => {
    const tokens = expressionToTokens('2 × 3', allTabs);
    const ops = tokens.filter((t) => t.type === 'operator').map((t) => t.value);
    expect(ops).toEqual(['*']);
  });

  it('÷ is normalised to /', () => {
    const tokens = expressionToTokens('6 ÷ 2', allTabs);
    const ops = tokens.filter((t) => t.type === 'operator').map((t) => t.value);
    expect(ops).toEqual(['/']);
  });

  it('parentheses → bracket_open / bracket_close', () => {
    const tokens = expressionToTokens('(1 + 2)', allTabs);
    expect(tokens[0].type).toBe('bracket_open');
    expect(tokens[tokens.length - 1].type).toBe('bracket_close');
  });

  it('{path} → path token', () => {
    const tokens = expressionToTokens('{mat_part.unit_weight}', allTabs);
    expect(tokens).toHaveLength(1);
    expect(tokens[0]).toEqual({ type: 'path', path: 'mat_part.unit_weight' });
  });

  it('whitespace is ignored and does not produce tokens', () => {
    const a = expressionToTokens('[单重]', allTabs);
    const b = expressionToTokens('  [ 单重 ]  ', allTabs);
    // The body is trimmed inside expressionToTokens
    expect(b).toHaveLength(1);
    expect(b[0]).toMatchObject({ type: 'field', value: '单重' });
    expect(a[0]).toMatchObject({ type: 'field', value: '单重' });
  });
});

// ─────────────────────────────────────────────
// 2. Task requirement: "[单重] * [单价] - [COMP_RL.金额(总计)]"
// ─────────────────────────────────────────────

describe('expressionToTokens — requirement example', () => {
  const expr = '[单重] * [单价] - [COMP_RL.金额(总计)]';

  it('produces 5 tokens total', () => {
    const tokens = expressionToTokens(expr, allTabs, selfRKF);
    expect(tokens).toHaveLength(5);
  });

  it('field tokens for 单重 and 单价', () => {
    const tokens = expressionToTokens(expr, allTabs, selfRKF);
    expect(tokens[0]).toEqual({ type: 'field', value: '单重' });
    expect(tokens[2]).toEqual({ type: 'field', value: '单价' });
  });

  it('operators * and -', () => {
    const tokens = expressionToTokens(expr, allTabs, selfRKF);
    expect(tokens[1]).toEqual({ type: 'operator', value: '*' });
    expect(tokens[3]).toEqual({ type: 'operator', value: '-' });
  });

  it('cross_tab_ref token: source uuid-rl, target 金额, agg SUM', () => {
    const tokens = expressionToTokens(expr, allTabs, selfRKF);
    const xref = tokens[4] as FormulaToken & { type: 'cross_tab_ref' };
    expect(xref.type).toBe('cross_tab_ref');
    expect(xref.source).toBe('uuid-rl');
    expect(xref.sourceLabel).toBe('回料');
    expect(xref.target).toBe('金额');
    expect(xref.agg).toBe('SUM');
  });

  it('cross_tab_ref match: [{a:"料号", b:"料号"}] (single row-key align)', () => {
    const tokens = expressionToTokens(expr, allTabs, selfRKF);
    const xref = tokens[4];
    expect(xref.match).toEqual([{ a: '料号', b: '料号' }]);
  });
});

// ─────────────────────────────────────────────
// 3. match row-key alignment rules
// ─────────────────────────────────────────────

describe('match row-key alignment', () => {
  it('single key: [{a: tabKey, b: selfKey}]', () => {
    const tokens = expressionToTokens('[COMP_RL.用量]', allTabs, ['料号']);
    expect(tokens[0].match).toEqual([{ a: '料号', b: '料号' }]);
  });

  it('multi-key tab + multi-key self: positional zip', () => {
    const tokens = expressionToTokens('[COMP_MK.单价]', allTabs, ['料号', '工序']);
    expect(tokens[0].match).toEqual([
      { a: '料号', b: '料号' },
      { a: '工序', b: '工序' },
    ]);
  });

  it('tab has 2 keys, self has 1: only 1 pair', () => {
    const tokens = expressionToTokens('[COMP_MK.单价]', allTabs, ['料号']);
    expect(tokens[0].match).toEqual([{ a: '料号', b: '料号' }]);
  });

  it('tab has no rowKeyFields: match = []', () => {
    const tokens = expressionToTokens('[COMP_NK.费率]', allTabs, ['料号']);
    expect(tokens[0].match).toEqual([]);
  });

  it('selfRowKeyFields undefined: match = []', () => {
    const tokens = expressionToTokens('[COMP_RL.用量]', allTabs, undefined);
    expect(tokens[0].match).toEqual([]);
  });
});

// ─────────────────────────────────────────────
// 4. Error cases
// ─────────────────────────────────────────────

describe('expressionToTokens — errors', () => {
  it('unknown alias in cross-tab detail ref throws', () => {
    expect(() => expressionToTokens('[UNKNOWN.金额]', allTabs, selfRKF)).toThrow(
      /未知页签.*UNKNOWN/,
    );
  });

  it('unknown alias in whole-tab total throws', () => {
    expect(() => expressionToTokens('[GHOST(总计)]', allTabs, selfRKF)).toThrow(
      /未知页签.*GHOST/,
    );
  });

  it('unmatched [ throws', () => {
    expect(() => expressionToTokens('[unclosed', allTabs)).toThrow(/'\['/);
  });

  it('unmatched { throws', () => {
    expect(() => expressionToTokens('{unclosed', allTabs)).toThrow(/'\{'/);
  });

  it('unrecognised character throws', () => {
    expect(() => expressionToTokens('1 $ 2', allTabs)).toThrow(/无法识别的字符/);
  });
});

// ─────────────────────────────────────────────
// 5. tokensToDrawerExpression (inverse)
// ─────────────────────────────────────────────

describe('tokensToDrawerExpression', () => {
  it('field token → [field]', () => {
    const tokens: FormulaToken[] = [{ type: 'field', value: '单重' }];
    expect(tokensToDrawerExpression(tokens, allTabs)).toBe('[单重]');
  });

  it('cross_tab_ref agg=NONE → [alias.field]', () => {
    const tokens: FormulaToken[] = [
      {
        type: 'cross_tab_ref',
        source: 'uuid-rl',
        sourceLabel: '回料',
        target: '金额',
        agg: 'NONE',
        match: [{ a: '料号', b: '料号' }],
      },
    ];
    expect(tokensToDrawerExpression(tokens, allTabs)).toBe('[回料.金额]');
  });

  it('cross_tab_ref agg=SUM with target → SUM([alias.field])', () => {
    const tokens: FormulaToken[] = [
      {
        type: 'cross_tab_ref',
        source: 'uuid-rl',
        sourceLabel: '回料',
        target: '金额',
        agg: 'SUM',
        match: [{ a: '料号', b: '料号' }],
      },
    ];
    expect(tokensToDrawerExpression(tokens, allTabs)).toBe('SUM([回料.金额])');
  });

  it('cross_tab_ref agg=SUM with empty target → [alias(总计)]', () => {
    const tokens: FormulaToken[] = [
      {
        type: 'cross_tab_ref',
        source: 'uuid-rl',
        sourceLabel: '回料',
        target: '',
        agg: 'SUM',
        match: [],
      },
    ];
    expect(tokensToDrawerExpression(tokens, allTabs)).toBe('[回料(总计)]');
  });

  it('operator → spaced', () => {
    const tokens: FormulaToken[] = [{ type: 'operator', value: '*' }];
    expect(tokensToDrawerExpression(tokens, allTabs)).toBe('*');
  });

  it('bracket_open / bracket_close → ( )', () => {
    const tokens: FormulaToken[] = [
      { type: 'bracket_open' },
      { type: 'number', value: '1' },
      { type: 'bracket_close' },
    ];
    expect(tokensToDrawerExpression(tokens, allTabs)).toBe('(1)');
  });

  it('path token → {path}', () => {
    const tokens: FormulaToken[] = [{ type: 'path', path: 'mat_part.unit_weight' }];
    expect(tokensToDrawerExpression(tokens, allTabs)).toBe('{mat_part.unit_weight}');
  });

  it('fallback to sourceLabel when componentId not found in tabDefs', () => {
    const tokens: FormulaToken[] = [
      {
        type: 'cross_tab_ref',
        source: 'uuid-not-in-tabdefs',
        sourceLabel: '孤立页签',
        target: '字段',
        agg: 'NONE',
        match: [],
      },
    ];
    // Should not throw; falls back to sourceLabel
    const result = tokensToDrawerExpression(tokens, allTabs);
    expect(result).toBe('[孤立页签.字段]');
  });
});

// ─────────────────────────────────────────────
// 6. Round-trip: expressionToTokens → tokensToDrawerExpression ≈ original
// ─────────────────────────────────────────────

describe('round-trip', () => {
  const normalise = (s: string) => s.replace(/\s+/g, ' ').trim();

  it('[COMP_RL.金额(总计)] 解析后回显归一为 SUM([COMP_RL.金额])', () => {
    // 旧格式仍可解析（兼容），但回显收敛到 FN() 新格式
    const expr = '[COMP_RL.金额(总计)]';
    const tokens = expressionToTokens(expr, allTabs, selfRKF);
    const back = tokensToDrawerExpression(tokens, allTabs);
    expect(normalise(back)).toBe(normalise('SUM([回料.金额])'));
  });

  it('[COMP_RL.金额] round-trips', () => {
    const expr = '[回料.金额]';
    const tokens = expressionToTokens(expr, allTabs, selfRKF);
    const back = tokensToDrawerExpression(tokens, allTabs);
    expect(normalise(back)).toBe(normalise(expr));
  });

  it('[COMP_RL(总计)] whole-tab → component_subtotal → normalises to [COMP_RL.金额]', () => {
    // FIX (2026-06-10): bare total now resolves to the tab's primary subtotalCol (金额),
    // so the canonical round-trip form is [COMP_RL.金额] (the explicit subtotal column).
    const expr = '[COMP_RL(总计)]';
    const tokens = expressionToTokens(expr, allTabs, selfRKF);
    const back = tokensToDrawerExpression(tokens, allTabs);
    expect(normalise(back)).toBe(normalise('[回料.金额]'));
    // And re-parsing the canonical form is stable (idempotent)
    const back2 = tokensToDrawerExpression(
      expressionToTokens(back, allTabs, selfRKF),
      allTabs,
    );
    expect(normalise(back2)).toBe(normalise('[回料.金额]'));
  });

  it('compound expression "[单重] * [单价] - [COMP_RL.金额(总计)]" 解析后回显归一', () => {
    // 旧格式 [alias.field(总计)] 解析后回显为新格式 SUM([alias.field])
    const expr = '[单重] * [单价] - [COMP_RL.金额(总计)]';
    const tokens = expressionToTokens(expr, allTabs, selfRKF);
    const back = tokensToDrawerExpression(tokens, allTabs);
    expect(normalise(back)).toBe(normalise('[单重] * [单价] - SUM([回料.金额])'));
  });

  it('expression with only same-component fields round-trips', () => {
    const expr = '[单重] + [单价]';
    const tokens = expressionToTokens(expr, allTabs, selfRKF);
    const back = tokensToDrawerExpression(tokens, allTabs);
    expect(normalise(back)).toBe(normalise(expr));
  });

  it('numeric expression round-trips', () => {
    const expr = '3.14 * [单重]';
    const tokens = expressionToTokens(expr, allTabs);
    const back = tokensToDrawerExpression(tokens, allTabs);
    expect(normalise(back)).toBe(normalise(expr));
  });

  it('× operator normalises to * on round-trip', () => {
    const expr = '[单重] × [单价]';
    const tokens = expressionToTokens(expr, allTabs);
    const back = tokensToDrawerExpression(tokens, allTabs);
    // After normalisation × becomes *
    expect(normalise(back)).toBe(normalise('[单重] * [单价]'));
  });
});

// ─────────────────────────────────────────────
// 7. checkMappable
// ─────────────────────────────────────────────

describe('checkMappable', () => {
  it('no cross_tab_ref → mappable', () => {
    const tokens: FormulaToken[] = [
      { type: 'field', value: '单重' },
      { type: 'operator', value: '*' },
      { type: 'number', value: '2' },
    ];
    expect(checkMappable(tokens)).toEqual({ mappable: true });
  });

  it('one agg=SUM cross_tab_ref with non-empty match → mappable', () => {
    // v4-C 新规则：match 非空则放行，agg 类型无关
    const tokens: FormulaToken[] = [
      {
        type: 'cross_tab_ref',
        source: 'uuid-rl',
        sourceLabel: '回料',
        target: '金额',
        agg: 'SUM',
        match: [{ a: '料号', b: '料号' }],
      },
    ];
    expect(checkMappable(tokens)).toEqual({ mappable: true });
  });

  it('one agg=NONE cross_tab_ref with non-empty match → mappable', () => {
    // v4-C 新规则：match 非空则放行
    const tokens: FormulaToken[] = [
      {
        type: 'cross_tab_ref',
        source: 'uuid-rl',
        sourceLabel: '回料',
        target: '金额',
        agg: 'NONE',
        match: [{ a: '料号', b: '料号' }],
      },
    ];
    expect(checkMappable(tokens)).toEqual({ mappable: true });
  });

  it('cross_tab_ref with match=[] → NOT mappable（v4-C 命门1：空match即拒）', () => {
    // 旧规则"≥2 NONE 才拒"已作废；新规则：任何 cross_tab_ref 且 match 为空即拒
    const tokens: FormulaToken[] = [
      {
        type: 'cross_tab_ref',
        source: 'uuid-rl',
        target: '金额',
        agg: 'NONE',
        match: [],
      },
    ];
    const result = checkMappable(tokens);
    expect(result.mappable).toBe(false);
    expect(result.reason).toBeTruthy();
  });

  it('two agg=NONE cross_tab_refs with match=[] → NOT mappable', () => {
    const tokens: FormulaToken[] = [
      {
        type: 'cross_tab_ref',
        source: 'uuid-rl',
        target: '金额',
        agg: 'NONE',
        match: [],
      },
      { type: 'operator', value: '+' },
      {
        type: 'cross_tab_ref',
        source: 'uuid-inv',
        target: '用量',
        agg: 'NONE',
        match: [],
      },
    ];
    const result = checkMappable(tokens);
    expect(result.mappable).toBe(false);
    expect(result.reason).toBeTruthy();
  });

  it('three agg=NONE with match=[] → NOT mappable', () => {
    const makeRef = (src: string): FormulaToken => ({
      type: 'cross_tab_ref',
      source: src,
      target: '字段',
      agg: 'NONE',
      match: [],
    });
    const tokens: FormulaToken[] = [makeRef('a'), makeRef('b'), makeRef('c')];
    expect(checkMappable(tokens).mappable).toBe(false);
  });

  it('two cross_tab_refs where one is SUM and one is NONE, both match=[] → NOT mappable', () => {
    // v4-C 新规则：任一 cross_tab_ref match 为空即拒，agg 类型无关
    const tokens: FormulaToken[] = [
      {
        type: 'cross_tab_ref',
        source: 'uuid-rl',
        target: '金额',
        agg: 'NONE',
        match: [],
      },
      { type: 'operator', value: '+' },
      {
        type: 'cross_tab_ref',
        source: 'uuid-inv',
        target: '用量',
        agg: 'SUM',
        match: [],
      },
    ];
    expect(checkMappable(tokens).mappable).toBe(false);
  });

  it('cross_tab_ref with match=[] → NOT mappable（match 为空拒绝，不计 agg）', () => {
    // v4-C 新规则：match 为空即拒
    const tokens: FormulaToken[] = [
      {
        type: 'cross_tab_ref',
        source: 'uuid-rl',
        target: '金额',
        // agg intentionally omitted
        match: [],
      },
      { type: 'operator', value: '+' },
      {
        type: 'cross_tab_ref',
        source: 'uuid-inv',
        target: '用量',
        // agg intentionally omitted
        match: [],
      },
    ];
    const result = checkMappable(tokens);
    expect(result.mappable).toBe(false);
  });
});

// ─────────────────────────────────────────────
// 9. component_subtotal (SUBTOTAL component formulas reference sibling subtotal cols)
// ─────────────────────────────────────────────

describe('component_subtotal — subtotal column references', () => {
  it('[COMP_RL.金额] + [COMP_INV.金额] → TWO component_subtotal tokens (金额 is a subtotalCol of both)', () => {
    const tokens = expressionToTokens(
      '[COMP_RL.金额] + [COMP_INV.金额]',
      allTabs,
      selfRKF,
    );
    expect(tokens).toHaveLength(3);
    expect(tokens[0]).toMatchObject({
      type: 'component_subtotal',
      component_code: 'COMP_RL',
      value: '金额',
      tab_name: '金额',
      label: '回料·金额',
    });
    expect(tokens[1]).toEqual({ type: 'operator', value: '+' });
    expect(tokens[2]).toMatchObject({
      type: 'component_subtotal',
      component_code: 'COMP_INV',
      value: '金额',
      tab_name: '金额',
      label: '投料·金额',
    });
  });

  it('mixed: [单重] * [COMP_RL.用量] — 用量 is a DETAIL field (not subtotalCol) → field + cross_tab_ref', () => {
    const tokens = expressionToTokens('[单重] * [COMP_RL.用量]', allTabs, selfRKF);
    expect(tokens).toHaveLength(3);
    expect(tokens[0]).toEqual({ type: 'field', value: '单重' });
    expect(tokens[1]).toEqual({ type: 'operator', value: '*' });
    expect(tokens[2]).toMatchObject({
      type: 'cross_tab_ref',
      source: 'uuid-rl',
      target: '用量',
      agg: 'NONE',
    });
  });

  it('tokensToDrawerExpression renders component_subtotal using token-own fields even with EMPTY tabDefs', () => {
    const tokens: FormulaToken[] = [
      {
        type: 'component_subtotal',
        value: '行小计',
        tab_name: '行小计',
        component_code: 'COMP-A',
        label: '来料BOM·行小计',
      },
      { type: 'operator', value: '+' },
      {
        type: 'component_subtotal',
        value: '工序加工费',
        tab_name: '工序加工费',
        component_code: 'COMP-B',
        label: '工序成本·工序加工费',
      },
    ];
    expect(tokensToDrawerExpression(tokens, [])).toBe(
      '[COMP-A.行小计] + [COMP-B.工序加工费]',
    );
  });

  it('component_subtotal with empty value renders [code(总计)]', () => {
    const tokens: FormulaToken[] = [
      {
        type: 'component_subtotal',
        value: '',
        tab_name: '',
        component_code: 'COMP-A',
        label: '来料BOM',
      },
    ];
    expect(tokensToDrawerExpression(tokens, [])).toBe('[COMP-A(总计)]');
  });

  it('[alias(总计)] bare-total maps to component_subtotal (single subtotalCol used)', () => {
    // COMP_RL has subtotalCols ['金额'] → bare total uses that column
    const tokens = expressionToTokens('[COMP_RL(总计)]', allTabs, selfRKF);
    expect(tokens).toHaveLength(1);
    expect(tokens[0]).toMatchObject({
      type: 'component_subtotal',
      component_code: 'COMP_RL',
      value: '金额',
    });
  });

  it('round-trip: subtotal formula tokens → expr → tokens preserves component_code/value', () => {
    const original: FormulaToken[] = [
      {
        type: 'component_subtotal',
        value: '金额',
        tab_name: '金额',
        component_code: 'COMP_RL',
        label: '回料·金额',
      },
      { type: 'operator', value: '+' },
      {
        type: 'component_subtotal',
        value: '金额',
        tab_name: '金额',
        component_code: 'COMP_INV',
        label: '投料·金额',
      },
    ];
    const expr = tokensToDrawerExpression(original, allTabs);
    const back = expressionToTokens(expr, allTabs, selfRKF);
    expect(back).toHaveLength(3);
    expect(back[0]).toMatchObject({
      type: 'component_subtotal',
      component_code: 'COMP_RL',
      value: '金额',
    });
    expect(back[2]).toMatchObject({
      type: 'component_subtotal',
      component_code: 'COMP_INV',
      value: '金额',
    });
  });

  it('checkMappable: 8 component_subtotal tokens + operators → mappable:true', () => {
    const tokens: FormulaToken[] = [];
    for (let i = 0; i < 8; i++) {
      if (i > 0) tokens.push({ type: 'operator', value: '+' });
      tokens.push({
        type: 'component_subtotal',
        value: '金额',
        tab_name: '金额',
        component_code: `COMP-${i}`,
        label: `组件${i}·金额`,
      });
    }
    expect(checkMappable(tokens)).toEqual({ mappable: true });
  });

  it('checkMappable: component_subtotal 不受"空match拒"规则影响；cross_tab_ref match=[] 仍拒（v4-C）', () => {
    // component_subtotal 无 match 字段，不触发空 match 规则；
    // 但 cross_tab_ref match=[] 仍触发拒绝
    const tokens: FormulaToken[] = [
      {
        type: 'component_subtotal',
        value: '金额',
        tab_name: '金额',
        component_code: 'COMP-A',
        label: 'A·金额',
      },
      { type: 'operator', value: '+' },
      {
        type: 'component_subtotal',
        value: '金额',
        tab_name: '金额',
        component_code: 'COMP-B',
        label: 'B·金额',
      },
      { type: 'operator', value: '+' },
      {
        type: 'cross_tab_ref',
        source: 'uuid-rl',
        target: '用量',
        agg: 'NONE',
        match: [],
      },
    ];
    // cross_tab_ref match=[] → 拒绝（v4-C 命门1）
    expect(checkMappable(tokens).mappable).toBe(false);
  });

  it('checkMappable: 纯 component_subtotal（无 cross_tab_ref）→ mappable', () => {
    const tokens: FormulaToken[] = [
      {
        type: 'component_subtotal',
        value: '金额',
        tab_name: '金额',
        component_code: 'COMP-A',
        label: 'A·金额',
      },
      { type: 'operator', value: '+' },
      {
        type: 'component_subtotal',
        value: '金额',
        tab_name: '金额',
        component_code: 'COMP-B',
        label: 'B·金额',
      },
    ];
    // 纯 component_subtotal，无 cross_tab_ref → 通过
    expect(checkMappable(tokens)).toEqual({ mappable: true });
  });
});

// ── Task 1: buildMatch 公共字段名交集配对（顺序无关） ──
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

// ─────────────────────────────────────────────
// 8. Integration: expressionToTokens → checkMappable
// ─────────────────────────────────────────────

describe('integration: expression → tokens → checkMappable', () => {
  it('one SUM agg ref → mappable', () => {
    const tokens = expressionToTokens('[单重] * [COMP_RL.金额(总计)]', allTabs, selfRKF);
    expect(checkMappable(tokens)).toEqual({ mappable: true });
  });

  it('one detail ref → mappable', () => {
    const tokens = expressionToTokens('[单重] * [COMP_RL.金额]', allTabs, selfRKF);
    expect(checkMappable(tokens)).toEqual({ mappable: true });
  });

  it('two detail refs with non-empty match (same row key) → mappable（v4-C 新规则）', () => {
    // v4-C 新规则：match 非空即放行。COMP_RL/COMP_INV 均有 rowKeyFields=['料号']，
    // selfRKF=['料号']，expressionToTokens 会生成 match=[{a:'料号',b:'料号'}]，match 非空 → 通过。
    // 旧规则"≥2 NONE 即拒"已作废。
    const tokens = expressionToTokens(
      '[COMP_RL.用量] + [COMP_INV.单价]',
      allTabs,
      selfRKF,
    );
    const result = checkMappable(tokens);
    expect(result.mappable).toBe(true);
  });
});

// ── Task 2: lexer 函数名 token ──
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
  it('FN 内运算符 → 行级聚合 targetExpr（批4 取消 v5-I 单列收口）', () => {
    const t = expressionToTokens('SUM([JG.工时]+[JG.工时])', tabs, ['子件']);
    expect(t).toHaveLength(1);
    expect(t[0]).toMatchObject({ type: 'cross_tab_ref', source: 'cid-jg', agg: 'SUM' });
    expect(t[0].targetExpr).toEqual([
      { type: 'field', value: '工时' },
      { type: 'operator', value: '+' },
      { type: 'field', value: '工时' },
    ]);
  });
  it('FN 内多引用且缺运算符 → 仍报错（[a][b] 畸形）', () => {
    expect(() => expressionToTokens('SUM([JG.工时][JG.工时])', tabs, ['子件']))
      .toThrow(/缺少运算符|运算符/);
  });
  it('FN 内非明细（裸字段）→ 报错', () => {
    expect(() => expressionToTokens('SUM([本])', tabs, ['子件'])).toThrow();
  });
  it('旧 [JG.工时(总计)] 仍解析为 agg=SUM（兼容）', () => {
    const t = expressionToTokens('[JG.工时(总计)]', tabs, ['子件']);
    expect(t[0]).toMatchObject({ type: 'cross_tab_ref', agg: 'SUM', target: '工时' });
  });
});

// ── Task 4: 回显归一 + 往返稳定 ──
describe('tokensToDrawerExpression — FN 回显归一', () => {
  const tabs: TabDef[] = [
    { alias: 'JG', tabKey: 'jg', componentId: 'cid-jg', componentName: '加工',
      rowKeyFields: ['子件'], detailFields: ['工时'], subtotalCols: [] },
  ];
  it('agg=SUM → SUM([JG.工时])（不再 (总计)）', () => {
    const t = expressionToTokens('SUM([JG.工时])', tabs, ['子件']);
    expect(tokensToDrawerExpression(t, tabs)).toBe('SUM([加工.工时])');
  });
  it('AVG/MAX/MIN/COUNT 往返同串', () => {
    for (const fn of ['AVG', 'MAX', 'MIN', 'COUNT']) {
      const s = `${fn}([加工.工时])`;
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
    expect(tokensToDrawerExpression(t, tabs)).toBe('SUM([加工.工时])');
  });
});

// ── Task 5: checkMappable 新规则 + comparable ──
describe('comparable / isSubset（集合包含，顺序无关）', () => {
  it('isSubset', () => {
    expect(isSubset(['子件'], ['工序', '子件'])).toBe(true);
    expect(isSubset(['工序', '子件'], ['子件'])).toBe(false);
  });
  it('comparable = 任一方 ⊆ 另一方（顺序无关）', () => {
    expect(comparable(['子件'], ['子件', '工序'])).toBe(true);
    expect(comparable(['子件', '工序'], ['子件'])).toBe(true);
    expect(comparable(['A', 'B'], ['B', 'A'])).toBe(true);
    expect(comparable(['料号'], ['工序'])).toBe(false);
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
    expect(checkMappable([a, b]).mappable).toBe(true);
  });
  it('component_subtotal（无 match）不受影响', () => {
    const cs: any = { type: 'component_subtotal', component_code: 'X', value: '小计' };
    expect(checkMappable([cs]).mappable).toBe(true);
  });
});

// ─────────────────────────────────────────────
// 批4 · 行级聚合 targetExpr（SUMPRODUCT）：
//   FN 括号内允许「宿主列 × 细 source 列」子表达式，按行键 LEFT JOIN
//   逐行算 targetExpr 再按宿主行键聚合。宿主列→b_field、source列→field。
//   判定靠第 4 参 selfComponentId：alias 的 componentId === self → 宿主列(b_field)。
// ─────────────────────────────────────────────
describe('expressionToTokens — FN 行级聚合 targetExpr (SUMPRODUCT)', () => {
  const host: TabDef = {
    alias: 'TL', tabKey: 'tl', componentId: 'cid-host', componentName: '投料',
    rowKeyFields: ['子件'], detailFields: ['单价'], subtotalCols: [],
  };
  const src: TabDef = {
    alias: 'JG', tabKey: 'jg', componentId: 'cid-jg', componentName: '加工',
    rowKeyFields: ['子件', '工序'], detailFields: ['数量', '工时'], subtotalCols: [],
  };
  const tabs: TabDef[] = [host, src];

  it('SUM([TL.单价] * [JG.数量]) → 单 cross_tab_ref(source=细页签) + targetExpr(b_field 单价 * field 数量)', () => {
    const t = expressionToTokens('SUM([TL.单价] * [JG.数量])', tabs, ['子件'], 'cid-host');
    expect(t).toHaveLength(1);
    expect(t[0]).toMatchObject({
      type: 'cross_tab_ref',
      source: 'cid-jg',
      agg: 'SUM',
      match: [{ a: '子件', b: '子件' }],
    });
    expect(t[0].targetExpr).toEqual([
      { type: 'b_field', value: '单价' },
      { type: 'operator', value: '*' },
      { type: 'field', value: '数量' },
    ]);
    expect(t[0].target ?? '').toBe('');
  });

  it('source 列在前：SUM([JG.数量] * [TL.单价]) → targetExpr(field 数量 * b_field 单价)，source 仍=细页签', () => {
    const t = expressionToTokens('SUM([JG.数量] * [TL.单价])', tabs, ['子件'], 'cid-host');
    expect(t[0].source).toBe('cid-jg');
    expect(t[0].targetExpr).toEqual([
      { type: 'field', value: '数量' },
      { type: 'operator', value: '*' },
      { type: 'b_field', value: '单价' },
    ]);
  });

  it('同一 source 两列：SUM([JG.数量] * [JG.工时]) → targetExpr(field 数量 * field 工时)', () => {
    const t = expressionToTokens('SUM([JG.数量] * [JG.工时])', tabs, ['子件', '工序'], 'cid-host');
    expect(t[0].source).toBe('cid-jg');
    expect(t[0].targetExpr).toEqual([
      { type: 'field', value: '数量' },
      { type: 'operator', value: '*' },
      { type: 'field', value: '工时' },
    ]);
  });

  it('含数字常量：SUM([JG.数量] * 2) → targetExpr(field 数量 * number 2)', () => {
    const t = expressionToTokens('SUM([JG.数量] * 2)', tabs, ['子件'], 'cid-host');
    expect(t[0].targetExpr).toEqual([
      { type: 'field', value: '数量' },
      { type: 'operator', value: '*' },
      { type: 'number', value: '2' },
    ]);
  });

  it('AVG 也走行级聚合：AVG([TL.单价] * [JG.数量]) → agg=AVG + targetExpr', () => {
    const t = expressionToTokens('AVG([TL.单价] * [JG.数量])', tabs, ['子件'], 'cid-host');
    expect(t[0]).toMatchObject({ type: 'cross_tab_ref', source: 'cid-jg', agg: 'AVG' });
    expect(t[0].targetExpr).toHaveLength(3);
  });

  it('跨两个 source 页签且行键不可比（互不包含）→ 报错', () => {
    // JG rowKeyFields=['子件','工序']，HL2 rowKeyFields=['料号'] — 互不包含 → 不可比 → 应报错
    const src2: TabDef = {
      alias: 'HL', tabKey: 'hl', componentId: 'cid-hl', componentName: '回料',
      rowKeyFields: ['料号'], detailFields: ['费率'], subtotalCols: [],
    };
    expect(() =>
      expressionToTokens('SUM([JG.数量] * [HL.费率])', [host, src, src2], ['子件'], 'cid-host'),
    ).toThrow(/不可比|行键|KSUM/);
  });

  it('FN 内只有宿主列、无细 source → 报错（没有可聚合的行集）', () => {
    expect(() =>
      expressionToTokens('SUM([TL.单价] * 2)', tabs, ['子件'], 'cid-host'),
    ).toThrow();
  });

  it('单列仍走旧路径：SUM([JG.数量]) → 无 targetExpr、target=数量（向后兼容）', () => {
    const t = expressionToTokens('SUM([JG.数量])', tabs, ['子件'], 'cid-host');
    expect(t[0].targetExpr).toBeUndefined();
    expect(t[0].target).toBe('数量');
    expect(t[0].agg).toBe('SUM');
  });

  it('round-trip：SUM([TL.单价] * [JG.数量]) 回显归一', () => {
    const t = expressionToTokens('SUM([TL.单价] * [JG.数量])', tabs, ['子件'], 'cid-host');
    const back = tokensToDrawerExpression(t, tabs, 'cid-host');
    expect(back.replace(/\s+/g, ' ').trim()).toBe('SUM([投料.单价] * [加工.数量])');
    // 幂等：回显串再解析再回显应稳定
    const t2 = expressionToTokens(back, tabs, ['子件'], 'cid-host');
    expect(tokensToDrawerExpression(t2, tabs, 'cid-host').replace(/\s+/g, ' ').trim())
      .toBe('SUM([投料.单价] * [加工.数量])');
  });
});

// ─────────────────────────────────────────────
// 用页签名称(componentName)作公式标识：插入/解析/回显都用中文名而非编号(alias)
//   名称优先、编号兜底(旧公式兼容)；token 内部(source=componentId、component_code=alias)不变
// ─────────────────────────────────────────────
describe('expressionToTokens / 回显 — 页签名称(componentName)作公式标识', () => {
  // 复用顶部 allTabs：tabRL(alias=COMP_RL,name=回料,detail[金额,用量],subtotal[金额])，selfRKF=['料号']
  it('用名称解析明细 [回料.用量] → cross_tab_ref(source=componentId)', () => {
    const t = expressionToTokens('[回料.用量]', allTabs, selfRKF);
    expect(t[0]).toMatchObject({ type: 'cross_tab_ref', source: 'uuid-rl', target: '用量', agg: 'NONE' });
  });
  it('编号仍兼容 [COMP_RL.用量]（旧公式不破）', () => {
    const t = expressionToTokens('[COMP_RL.用量]', allTabs, selfRKF);
    expect(t[0]).toMatchObject({ type: 'cross_tab_ref', source: 'uuid-rl', target: '用量' });
  });
  it('用名称解析小计 [回料.金额] → component_subtotal(component_code 仍存 alias，后端不变)', () => {
    const t = expressionToTokens('[回料.金额]', allTabs, selfRKF);
    expect(t[0]).toMatchObject({ type: 'component_subtotal', component_code: 'COMP_RL', value: '金额' });
  });
  it('用名称解析整页签总计 [回料(总计)] → component_subtotal', () => {
    const t = expressionToTokens('[回料(总计)]', allTabs, selfRKF);
    expect(t[0]).toMatchObject({ type: 'component_subtotal', component_code: 'COMP_RL', value: '金额' });
  });
  it('回显 cross_tab_ref 用名称 → [回料.用量]', () => {
    const tokens: FormulaToken[] = [
      { type: 'cross_tab_ref', source: 'uuid-rl', sourceLabel: '回料', target: '用量', agg: 'NONE', match: [{ a: '料号', b: '料号' }] },
    ];
    expect(tokensToDrawerExpression(tokens, allTabs)).toBe('[回料.用量]');
  });
  it('回显 component_subtotal 用名称 → [回料.金额]（tabDefs 非空时按 component_code 查名称）', () => {
    const tokens: FormulaToken[] = [
      { type: 'component_subtotal', value: '金额', tab_name: '金额', component_code: 'COMP_RL', label: '回料·金额' },
    ];
    expect(tokensToDrawerExpression(tokens, allTabs)).toBe('[回料.金额]');
  });
  it('回显 SUM 聚合用名称 → SUM([回料.金额])', () => {
    const tokens: FormulaToken[] = [
      { type: 'cross_tab_ref', source: 'uuid-rl', sourceLabel: '回料', target: '金额', agg: 'SUM', match: [{ a: '料号', b: '料号' }] },
    ];
    expect(tokensToDrawerExpression(tokens, allTabs)).toBe('SUM([回料.金额])');
  });
  it('名称 round-trip 幂等：[回料.用量] → tokens → [回料.用量]', () => {
    const back = tokensToDrawerExpression(expressionToTokens('[回料.用量]', allTabs, selfRKF), allTabs);
    expect(back).toBe('[回料.用量]');
  });
});

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
  it('宿主自身列 [单重](无点无总计)→ purple self-field', () => {
    expect(classifyRefSegment('单重', allTabs, self, true)).toEqual({ kind: 'self-field', color: 'purple' });
  });
  it('[别名(总计)] 优先于 self-field(行序)', () => {
    expect(classifyRefSegment('回料(总计)', allTabs, self, true).kind).toBe('tab-total');
  });
});

describe('parseFormulaSegments', () => {
  const self = ['料号'];

  it('混合串切分顺序:SUM([投料.金额] * [回料.用量])', () => {
    const segs = parseFormulaSegments('SUM([投料.金额] * [回料.用量])', allTabs, self, true);
    expect(segs.map((s) => s.raw)).toEqual(['SUM(', '[投料.金额]', ' * ', '[回料.用量]', ')']);
    expect(segs.map((s) => s.isBlock)).toEqual([false, true, false, true, false]);
    expect(segs[1].color).toBe('yellow'); // 投料.金额∈subtotalCols
    expect(segs[3].color).toBe('blue');   // 回料.用量∈detailFields 可比
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

  it('整体空格 [ 投料.金额 ]:整 body trim 后判色(金额∈subtotalCols → yellow)', () => {
    const segs = parseFormulaSegments('[ 投料.金额 ]', allTabs, self, true);
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

// ─────────────────────────────────────────────
// 宿主自身字段 → 紫(spec §5)
// ─────────────────────────────────────────────
describe('classifyRefSegment — 宿主自身字段(紫)', () => {
  const self = ['料号'];
  const tabSelf: TabDef = {
    alias: 'COMP_SELF', tabKey: 'tab-self', componentId: 'uuid-self',
    componentName: '宿主组件', componentType: 'NORMAL', self: true,
    rowKeyFields: ['料号'], detailFields: ['组成用量'], subtotalCols: ['金额小计'],
  };
  const tabs = [...allTabs, tabSelf];

  it('宿主明细字段 [宿主组件.组成用量] → purple', () => {
    expect(classifyRefSegment('宿主组件.组成用量', tabs, self, true))
      .toEqual({ kind: 'self-field', color: 'purple' });
  });
  it('宿主小计列 [宿主组件.金额小计] → yellow(小计优先于 self)', () => {
    expect(classifyRefSegment('宿主组件.金额小计', tabs, self, true))
      .toEqual({ kind: 'subtotal', color: 'yellow' });
  });
  it('宿主自聚合 [宿主组件.组成用量(总计)] → red(本期不支持)', () => {
    expect(classifyRefSegment('宿主组件.组成用量(总计)', tabs, self, true).color).toBe('red');
  });
  it('宿主未知字段 [宿主组件.不存在] → red', () => {
    expect(classifyRefSegment('宿主组件.不存在', tabs, self, true).color).toBe('red');
  });
  it('兄弟明细仍蓝 [回料.用量] → blue', () => {
    expect(classifyRefSegment('回料.用量', tabs, self, true).color).toBe('blue');
  });
});

// ─────────────────────────────────────────────
// 宿主自引用归一 field (spec §4)
// ─────────────────────────────────────────────

describe('expressionToTokens — 宿主自引用归一 field(spec §4)', () => {
  const rkf = ['料号'];

  it('[回料.用量] + selfComponentId=回料 → field token', () => {
    const t = expressionToTokens('[回料.用量]', allTabs, rkf, 'uuid-rl');
    expect(t).toHaveLength(1);
    expect(t[0]).toEqual({ type: 'field', value: '用量' });
  });
  it('[回料.用量] + selfComponentId=别的(uuid-inv) → cross_tab_ref(回归)', () => {
    const t = expressionToTokens('[回料.用量]', allTabs, rkf, 'uuid-inv');
    expect(t[0]).toMatchObject({ type: 'cross_tab_ref', source: 'uuid-rl' });
  });
  it('[回料.金额](金额∈subtotalCols) + self=回料 → 仍 component_subtotal', () => {
    const t = expressionToTokens('[回料.金额]', allTabs, rkf, 'uuid-rl');
    expect(t[0]).toMatchObject({ type: 'component_subtotal' });
  });
  it('[回料.用量(总计)](自聚合) + self=回料 → 仍 cross_tab_ref(不归一)', () => {
    const t = expressionToTokens('[回料.用量(总计)]', allTabs, rkf, 'uuid-rl');
    expect(t[0]).toMatchObject({ type: 'cross_tab_ref', agg: 'SUM', source: 'uuid-rl' });
  });
  it('不传 selfComponentId → cross_tab_ref(旧行为保留)', () => {
    const t = expressionToTokens('[回料.用量]', allTabs, rkf);
    expect(t[0]).toMatchObject({ type: 'cross_tab_ref' });
  });
});

// ─────────────────────────────────────────────
// parseFormulaSegments — 细 source 裸引用判红(需聚合)
// 宿主 selfRowKeyFields=['料件']
// 「元素」tab rowKeyFields=['料件','元素'] — 键严格超集(更细)
// 「投料」tab rowKeyFields=['料件'] — 同级
// ─────────────────────────────────────────────
describe('parseFormulaSegments — 细 source 裸引用判红(需聚合)', () => {
  // 「元素」tab：rowKeyFields=['料件','元素']（严格更细于宿主 ['料件']）
  const tabYuanSu: TabDef = {
    alias: 'COMP_YS',
    tabKey: 'tab-ys',
    componentId: 'uuid-ys',
    componentName: '元素',
    componentType: 'NORMAL',
    rowKeyFields: ['料件', '元素'],
    detailFields: ['单价'],
    subtotalCols: ['单价(总计)'],
  };
  // 「投料」tab：rowKeyFields=['料件']（与宿主同级）
  const tabTouLiao: TabDef = {
    alias: 'COMP_TL',
    tabKey: 'tab-tl',
    componentId: 'uuid-tl',
    componentName: '投料',
    componentType: 'NORMAL',
    rowKeyFields: ['料件'],
    detailFields: ['重量'],
    subtotalCols: [],
  };

  const tabsFiner: TabDef[] = [tabYuanSu, tabTouLiao];
  const tabsSame: TabDef[] = [tabTouLiao];

  it('裸 [元素.单价](细 source，非 FN 内) → red', () => {
    const segs = parseFormulaSegments('[元素.单价]', tabsFiner, ['料件'], true);
    const blk = segs.find(s => s.isBlock);
    expect(blk?.color).toBe('red');
  });

  it('SUM([元素.单价])(FN 内细引用) → blue', () => {
    const segs = parseFormulaSegments('SUM([元素.单价])', tabsFiner, ['料件'], true);
    const blk = segs.find(s => s.isBlock);
    expect(blk?.color).toBe('blue');
  });

  it('[元素.单价(总计)](inline 聚合) → blue', () => {
    const segs = parseFormulaSegments('[元素.单价(总计)]', tabsFiner, ['料件'], true);
    const blk = segs.find(s => s.isBlock);
    expect(blk?.color).toBe('blue');
  });

  it('裸 [投料.重量](同级 source) → blue', () => {
    const segs = parseFormulaSegments('[投料.重量]', tabsSame, ['料件'], true);
    const blk = segs.find(s => s.isBlock);
    expect(blk?.color).toBe('blue');
  });
});

// ─────────────────────────────────────────────
// T2: lexer K* func + C3 误拆文案 + 多 source 成链
// ─────────────────────────────────────────────
describe('T2 lexer K* + C3 + 多 source', () => {
  // 元素 tab：rowKeyFields=['料件','元素']（严格更细于宿主）
  const tabYS: TabDef = {
    alias: 'COMP_YS',
    tabKey: 'tab-ys',
    componentId: 'uuid-ys',
    componentName: '元素',
    componentType: 'NORMAL',
    rowKeyFields: ['料件', '元素'],
    detailFields: ['单价'],
    subtotalCols: [],
  };
  // 来料 tab（宿主）：rowKeyFields=['料件']
  const tabLL: TabDef = {
    alias: 'COMP_LL',
    tabKey: 'tab-ll',
    componentId: 'uuid-ll',
    componentName: '来料',
    componentType: 'NORMAL',
    rowKeyFields: ['料件'],
    detailFields: ['组成用量'],
    subtotalCols: [],
  };
  const multiSrcTabs: TabDef[] = [tabYS, tabLL];
  // selfRowKeyFields = 来料的 rowKeyFields；selfComponentId = 来料
  const selfRKF2 = ['料件'];
  const selfCid = 'uuid-ll';

  it('KSUM 连写 → func token name=KSUM (不被切成 K+SUM)', () => {
    const toks = __lexForTest('KSUM([外购件.费用])');
    expect(toks.find(t => (t as any).kind === 'func')).toMatchObject({ kind: 'func', name: 'KSUM' });
  });

  it('K SUM(...) 拆写 → 专门 C3 文案 (非通用无法识别)', () => {
    expect(() => __lexForTest('K SUM([外购件.费用])'))
      .toThrow(/不能拆写.*请连写/);
  });

  it('多 source 链 SUM (元素细 + 来料粗，两两可比) → 不抛"只允许同一细页签"', () => {
    // 元素 rowKeyFields=['料件','元素'] ⊇ 来料 rowKeyFields=['料件'] → 可比成链
    expect(() =>
      expressionToTokens('SUM([元素.单价] + [来料.组成用量])', multiSrcTabs, selfRKF2, selfCid),
    ).not.toThrow();
  });
});

// ─────────────────────────────────────────────
// T1 token schema 扩展
// ─────────────────────────────────────────────
describe('T1 token schema 扩展', () => {
  it('FormulaToken 支持 sources 与 projectToHostKey（可选,缺省兼容）', () => {
    const legacy: FormulaToken = { type: 'cross_tab_ref', source: 'X', agg: 'SUM' };
    expect(legacy.projectToHostKey).toBeUndefined();   // 缺省 = 旧 token

    const ksum: FormulaToken = {
      type: 'cross_tab_ref', projectToHostKey: true, source: 'WGJ', agg: 'SUM',
      match: [{ a: '料件', b: '料件' }], targetExpr: [{ type: 'field', value: '费用', source: 'WGJ' }],
    };
    expect(ksum.projectToHostKey).toBe(true);

    const multi: FormulaToken = {
      type: 'cross_tab_ref', source: 'YS', agg: 'SUM',
      sources: [{ source: 'YS', sourceLabel: '元素', match: [{ a: '料件', b: '料件' }] }],
    };
    expect(multi.sources?.length).toBe(1);
  });
});
