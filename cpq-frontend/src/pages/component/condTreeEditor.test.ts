import { describe, it, expect } from 'vitest';
import { emptyLeaf, emptyGroup } from './CondTreeEditor';

describe('CondTreeEditor helpers', () => {
  it('emptyLeaf 形状正确', () => {
    expect(emptyLeaf('类型')).toEqual({ kind: 'leaf', left: '类型', op: 'eq', rhs: { type: 'literal', value: '' } });
  });
  it('emptyGroup 形状正确', () => {
    expect(emptyGroup()).toEqual({ kind: 'group', logic: 'and', children: [] });
  });
});
