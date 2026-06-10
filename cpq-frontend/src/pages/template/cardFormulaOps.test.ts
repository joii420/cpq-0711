import { describe, it, expect } from 'vitest';
import { CARD_OPERATIONS, opToRefType, refTypeToOp } from './cardFormulaOps';

describe('card operation 映射', () => {
  it('CARD_OPERATIONS: 8 项, simple 标记正确', () => {
    expect(CARD_OPERATIONS.map((o) => o.key)).toEqual(
      ['subtotal', 'lookup_first', 'lookup_where', 'sum', 'avg', 'count', 'max', 'min']
    );
    expect(CARD_OPERATIONS.filter((o) => o.simple).map((o) => o.key)).toEqual(
      ['subtotal', 'lookup_first', 'lookup_where', 'sum']
    );
  });
  it('opToRefType: 操作 key → {refType, aggFunc}', () => {
    expect(opToRefType('subtotal')).toEqual({ refType: 'subtotal', aggFunc: undefined });
    expect(opToRefType('lookup_where')).toEqual({ refType: 'row_where', aggFunc: undefined });
    expect(opToRefType('sum')).toEqual({ refType: 'aggregate', aggFunc: 'SUM' });
    expect(opToRefType('count')).toEqual({ refType: 'aggregate', aggFunc: 'COUNT' });
  });
  it('refTypeToOp: (refType, aggFunc) → 操作 key, 非聚合忽略 aggFunc', () => {
    expect(refTypeToOp('subtotal')).toBe('subtotal');
    expect(refTypeToOp('first_row')).toBe('lookup_first');
    expect(refTypeToOp('row_where')).toBe('lookup_where');
    expect(refTypeToOp('aggregate', 'SUM')).toBe('sum');
    expect(refTypeToOp('aggregate', 'MAX')).toBe('max');
    expect(refTypeToOp('aggregate')).toBe('sum'); // 缺 aggFunc 默认 sum
    expect(refTypeToOp('unknown' as any)).toBe('subtotal'); // 未知回退
  });
});
