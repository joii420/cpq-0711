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
      /未知页签别名.*UNKNOWN/,
    );
  });

  it('unknown alias in whole-tab total throws', () => {
    expect(() => expressionToTokens('[GHOST(总计)]', allTabs, selfRKF)).toThrow(
      /未知页签别名.*GHOST/,
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
    expect(tokensToDrawerExpression(tokens, allTabs)).toBe('[COMP_RL.金额]');
  });

  it('cross_tab_ref agg=SUM with target → [alias.field(总计)]', () => {
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
    expect(tokensToDrawerExpression(tokens, allTabs)).toBe('[COMP_RL.金额(总计)]');
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
    expect(tokensToDrawerExpression(tokens, allTabs)).toBe('[COMP_RL(总计)]');
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

  it('[COMP_RL.金额(总计)] round-trips', () => {
    const expr = '[COMP_RL.金额(总计)]';
    const tokens = expressionToTokens(expr, allTabs, selfRKF);
    const back = tokensToDrawerExpression(tokens, allTabs);
    expect(normalise(back)).toBe(normalise(expr));
  });

  it('[COMP_RL.金额] round-trips', () => {
    const expr = '[COMP_RL.金额]';
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
    expect(normalise(back)).toBe(normalise('[COMP_RL.金额]'));
    // And re-parsing the canonical form is stable (idempotent)
    const back2 = tokensToDrawerExpression(
      expressionToTokens(back, allTabs, selfRKF),
      allTabs,
    );
    expect(normalise(back2)).toBe(normalise('[COMP_RL.金额]'));
  });

  it('compound expression "[单重] * [单价] - [COMP_RL.金额(总计)]" round-trips', () => {
    const expr = '[单重] * [单价] - [COMP_RL.金额(总计)]';
    const tokens = expressionToTokens(expr, allTabs, selfRKF);
    const back = tokensToDrawerExpression(tokens, allTabs);
    expect(normalise(back)).toBe(normalise(expr));
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

  it('one agg=SUM cross_tab_ref → mappable', () => {
    const tokens: FormulaToken[] = [
      {
        type: 'cross_tab_ref',
        source: 'uuid-rl',
        sourceLabel: '回料',
        target: '金额',
        agg: 'SUM',
        match: [],
      },
    ];
    expect(checkMappable(tokens)).toEqual({ mappable: true });
  });

  it('one agg=NONE cross_tab_ref → mappable', () => {
    const tokens: FormulaToken[] = [
      {
        type: 'cross_tab_ref',
        source: 'uuid-rl',
        sourceLabel: '回料',
        target: '金额',
        agg: 'NONE',
        match: [],
      },
    ];
    expect(checkMappable(tokens)).toEqual({ mappable: true });
  });

  it('two agg=NONE cross_tab_refs → NOT mappable with reason', () => {
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
    expect(result.reason).toMatch(/2\+/);
  });

  it('three agg=NONE → NOT mappable', () => {
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

  it('two cross_tab_refs where one is SUM and one is NONE → still mappable', () => {
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
    // Only 1 agg=NONE → under threshold
    expect(checkMappable(tokens)).toEqual({ mappable: true });
  });

  it('agg undefined treated as NONE', () => {
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

  it('checkMappable: component_subtotal does NOT count toward the 2+ cross_tab_ref rule', () => {
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
    // only 1 cross_tab_ref agg=NONE → still mappable
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

  it('two detail refs → NOT mappable', () => {
    // 用量 (COMP_RL) and 单价 (COMP_INV) are DETAIL fields, not subtotalCols
    const tokens = expressionToTokens(
      '[COMP_RL.用量] + [COMP_INV.单价]',
      allTabs,
      selfRKF,
    );
    const result = checkMappable(tokens);
    expect(result.mappable).toBe(false);
    expect(result.reason).toBeTruthy();
  });
});
