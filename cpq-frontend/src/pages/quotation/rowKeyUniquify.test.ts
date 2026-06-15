import { describe, it, expect } from 'vitest';
import { uniquifyRowKeys, buildUniqueRowKeys } from './useCardSnapshots';
import { bnfDriverLookupKey } from './useDriverExpansions';

describe('uniquifyRowKeys 撞键消歧', () => {
  it('唯一键保持原样（向后兼容）', () => {
    expect(uniquifyRowKeys(['a', 'b', 'c'])).toEqual(['a', 'b', 'c']);
  });
  it('撞键按出现序追加 #0/#1', () => {
    expect(uniquifyRowKeys(['||单价', '||单价'])).toEqual(['||单价#0', '||单价#1']);
  });
  it('混合：只动撞键，唯一键不动', () => {
    expect(uniquifyRowKeys(['x', 'k', 'x', 'k', 'x']))
      .toEqual(['x#0', 'k#0', 'x#1', 'k#1', 'x#2']);
  });
  it('空数组', () => {
    expect(uniquifyRowKeys([])).toEqual([]);
  });
});

describe('buildUniqueRowKeys 复现外购件撞键', () => {
  // 生产形状：fields 用 camelCase defaultSource；basicDataValues 用 bnfDriverLookupKey 键
  const fields = [
    { name: '料件', defaultSource: { type: 'BASIC_DATA', path: '$wgj_view._料件' } },
    { name: '要素', defaultSource: { type: 'BASIC_DATA', path: '$wgj_view._要素' } },
  ];
  // 两行：料件=null(空) + 要素='单价' → raw key 都是 "||单价"（撞键）
  const makeRow = (fei: number) => ({
    driverRow: { _料件: null, _要素: '单价', 费用: fei },
    basicDataValues: {
      [bnfDriverLookupKey('$wgj_view._料件')]: null,
      [bnfDriverLookupKey('$wgj_view._要素')]: '单价',
      [bnfDriverLookupKey('$wgj_view.费用')]: fei,
    },
  });
  const baseRows = [makeRow(0.6892), makeRow(0.802)];
  it('两行 (空料件+单价) → ||单价#0 / ||单价#1（不再塌缩）', () => {
    expect(buildUniqueRowKeys(fields, ['料件', '要素'], baseRows))
      .toEqual(['||单价#0', '||单价#1']);
  });
  it('baseRows 为空 → []', () => {
    expect(buildUniqueRowKeys(fields, ['料件', '要素'], undefined)).toEqual([]);
  });
});
