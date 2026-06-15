import { describe, it, expect } from 'vitest';
import { buildSumifToken } from '../TabJoinFormulaDrawer';

describe('buildSumifToken', () => {
  it('builds cross_tab_ref token with predicate + agg SUM + targetExpr', () => {
    const token = buildSumifToken({
      func: 'SUMIF',
      source: 'compA',
      sourceLabel: '页签A',
      predicate: { op: '=', lhs: { kind: 'sourceField', field: '类型' }, rhs: { kind: 'literal', value: '管理费' } },
      valueExprTokens: [{ type: 'field', value: '金额', source: 'compA' }],
    });
    expect(token.type).toBe('cross_tab_ref');
    expect(token.agg).toBe('SUM');
    expect(token.predicate).toBeTruthy();
    expect(token.match).toEqual([]);
    expect(token.targetExpr?.length).toBe(1);
  });

  it('COUNTIF maps to agg COUNT and no targetExpr needed', () => {
    const token = buildSumifToken({
      func: 'COUNTIF',
      source: 'compA',
      sourceLabel: '页签A',
      predicate: { op: '=', lhs: { kind: 'sourceField', field: '类型' }, rhs: { kind: 'literal', value: '管理费' } },
      valueExprTokens: [],
    });
    expect(token.agg).toBe('COUNT');
  });

  it('AVGIF maps to agg AVG', () => {
    const token = buildSumifToken({
      func: 'AVGIF',
      source: 'compB',
      sourceLabel: '页签B',
      predicate: { op: '>', lhs: { kind: 'sourceField', field: '数量' }, rhs: { kind: 'literal', value: '10' } },
      valueExprTokens: [{ type: 'field', value: '金额', source: 'compB' }],
    });
    expect(token.type).toBe('cross_tab_ref');
    expect(token.agg).toBe('AVG');
    expect(token.source).toBe('compB');
    expect(token.sourceLabel).toBe('页签B');
    expect(token.predicate).toMatchObject({ op: '>' });
  });

  it('MINIF/MAXIF map to MIN/MAX', () => {
    const min = buildSumifToken({ func: 'MINIF', source: 'c', sourceLabel: 'x', predicate: null, valueExprTokens: [] });
    const max = buildSumifToken({ func: 'MAXIF', source: 'c', sourceLabel: 'x', predicate: null, valueExprTokens: [] });
    expect(min.agg).toBe('MIN');
    expect(max.agg).toBe('MAX');
  });

  it('null predicate produces token with predicate=null', () => {
    const token = buildSumifToken({
      func: 'SUMIF',
      source: 'compA',
      sourceLabel: '页签A',
      predicate: null,
      valueExprTokens: [],
    });
    expect(token.predicate).toBeNull();
  });

  it('AND predicate with two conditions', () => {
    const token = buildSumifToken({
      func: 'SUMIF',
      source: 'compA',
      sourceLabel: '页签A',
      predicate: {
        bool: 'AND',
        children: [
          { op: '=', lhs: { kind: 'sourceField', field: '类型' }, rhs: { kind: 'literal', value: '管理费' } },
          { op: '>', lhs: { kind: 'sourceField', field: '金额' }, rhs: { kind: 'literal', value: '0' } },
        ],
      },
      valueExprTokens: [{ type: 'field', value: '金额', source: 'compA' }],
    });
    expect(token.predicate).toMatchObject({ bool: 'AND' });
    expect((token.predicate as any).children).toHaveLength(2);
  });

  it('match is always empty array (predicate drives filtering, not match)', () => {
    const token = buildSumifToken({
      func: 'SUMIF',
      source: 'compX',
      sourceLabel: '页签X',
      predicate: { op: '!=', lhs: { kind: 'sourceField', field: 'f' }, rhs: { kind: 'literal', value: 'v' } },
      valueExprTokens: [{ type: 'field', value: '金额', source: 'compX' }],
    });
    expect(token.match).toEqual([]);
  });

  it('empty valueExprTokens produces undefined targetExpr', () => {
    const token = buildSumifToken({
      func: 'SUMIF',
      source: 'compA',
      sourceLabel: '页签A',
      predicate: null,
      valueExprTokens: [],
    });
    expect(token.targetExpr).toBeUndefined();
  });

  it('non-empty valueExprTokens preserves targetExpr array', () => {
    const tokens = [
      { type: 'field' as const, value: '金额', source: 'compA' },
      { type: 'operator' as const, value: '*' },
      { type: 'number' as const, value: '1.1' },
    ];
    const token = buildSumifToken({
      func: 'SUMIF',
      source: 'compA',
      sourceLabel: '页签A',
      predicate: null,
      valueExprTokens: tokens,
    });
    expect(token.targetExpr).toHaveLength(3);
    expect(token.targetExpr![1]).toMatchObject({ type: 'operator', value: '*' });
  });
});
