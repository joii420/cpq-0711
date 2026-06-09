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
