import { describe, it, expect } from 'vitest';
import { OPERATIONS, operationToAgg, aggToOperation, serializeCrossTab } from './crossTabText';
import type { FormulaToken } from './types';

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
