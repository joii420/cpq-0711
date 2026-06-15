import { describe, it, expect } from 'vitest';
import { buildSumifToken, splitSumifTokens } from '../TabJoinFormulaDrawer';
import type { FormulaToken } from '../../component/types';

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

// ── splitSumifTokens round-trip 测试 ────────────────────────────────────────

describe('splitSumifTokens', () => {
  const predicate = {
    op: '=' as const,
    lhs: { kind: 'sourceField' as const, field: '类型' },
    rhs: { kind: 'literal' as const, value: '管理费' },
  };

  it('带 predicate 的 cross_tab_ref 进 sumifTokens，普通 token 进 exprTokens', () => {
    const sumifTok = buildSumifToken({
      func: 'SUMIF',
      source: 'compA',
      sourceLabel: '页签A',
      predicate,
      valueExprTokens: [{ type: 'field', value: '金额', source: 'compA' }],
    });
    const fieldTok: FormulaToken = { type: 'field', value: '数量' };
    const operatorTok: FormulaToken = { type: 'operator', value: '+' };

    // 模拟落库后从 FormulaToken[] 重新打开（predicate 在运行时存在于对象上）
    const allTokens: FormulaToken[] = [fieldTok, operatorTok, sumifTok as unknown as FormulaToken];

    const { sumifTokens, exprTokens } = splitSumifTokens(allTokens);

    // 带 predicate 的 cross_tab_ref 进 sumifTokens
    expect(sumifTokens).toHaveLength(1);
    expect(sumifTokens[0].type).toBe('cross_tab_ref');
    expect(sumifTokens[0].predicate).toMatchObject({ op: '=' });
    expect(sumifTokens[0].agg).toBe('SUM');

    // 普通 token 进 exprTokens
    expect(exprTokens).toHaveLength(2);
    expect(exprTokens[0]).toBe(fieldTok);
    expect(exprTokens[1]).toBe(operatorTok);
  });

  it('round-trip：合并后 predicate 完整保留', () => {
    const sumifTok = buildSumifToken({
      func: 'AVGIF',
      source: 'compB',
      sourceLabel: '页签B',
      predicate,
      valueExprTokens: [{ type: 'field', value: '单价', source: 'compB' }],
    });
    const fieldTok: FormulaToken = { type: 'field', value: '数量' };
    const allTokens: FormulaToken[] = [fieldTok, sumifTok as unknown as FormulaToken];

    const { sumifTokens, exprTokens } = splitSumifTokens(allTokens);

    // 拼回去（模拟 save 时的 [...exprTokens, ...sumifTokens]）
    const restored = [...exprTokens, ...(sumifTokens as unknown as FormulaToken[])];

    expect(restored).toHaveLength(2);
    // 普通 field token 原位保留
    expect(restored[0]).toMatchObject({ type: 'field', value: '数量' });
    // SUMIF token predicate 完整保留，不丢失
    expect(restored[1]).toMatchObject({
      type: 'cross_tab_ref',
      agg: 'AVG',
      source: 'compB',
    });
    expect((restored[1] as any).predicate).toMatchObject({ op: '=' });
    expect((restored[1] as any).predicate.lhs).toMatchObject({ kind: 'sourceField', field: '类型' });
    expect((restored[1] as any).predicate.rhs).toMatchObject({ kind: 'literal', value: '管理费' });
  });

  it('无 predicate 的 cross_tab_ref（普通跨页签引用）进 exprTokens，不进 sumifTokens', () => {
    const normalCrossRef: FormulaToken = {
      type: 'cross_tab_ref',
      source: 'compC',
      sourceLabel: '页签C',
      target: '金额',
      agg: 'SUM',
      match: [{ a: '料号', b: '料号' }],
      // predicate 缺省（undefined）
    };
    const { sumifTokens, exprTokens } = splitSumifTokens([normalCrossRef]);

    expect(sumifTokens).toHaveLength(0);
    expect(exprTokens).toHaveLength(1);
    expect(exprTokens[0]).toBe(normalCrossRef);
  });

  it('空数组输入产出两个空数组', () => {
    const { sumifTokens, exprTokens } = splitSumifTokens([]);
    expect(sumifTokens).toHaveLength(0);
    expect(exprTokens).toHaveLength(0);
  });

  it('多个 SUMIF token 全部进 sumifTokens', () => {
    const tok1 = buildSumifToken({ func: 'SUMIF', source: 'c1', sourceLabel: 'C1', predicate, valueExprTokens: [] });
    const tok2 = buildSumifToken({ func: 'COUNTIF', source: 'c2', sourceLabel: 'C2', predicate, valueExprTokens: [] });
    const allTokens: FormulaToken[] = [tok1 as unknown as FormulaToken, tok2 as unknown as FormulaToken];

    const { sumifTokens, exprTokens } = splitSumifTokens(allTokens);
    expect(sumifTokens).toHaveLength(2);
    expect(exprTokens).toHaveLength(0);
  });
});
