import { describe, it, expect } from 'vitest';
import { uniquifyRowKeys, buildUniqueRowKeys, getByKeyWithLegacyFallback } from './useCardSnapshots';
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

// ── repair-0727 F0：applyNodePrefix（对齐后端 FormulaCalculator#buildRawRowKeys）───────────────
describe('buildUniqueRowKeys applyNodePrefix (F0)', () => {
  const fields = [{ name: '料件', defaultSource: undefined }];
  const rowKeyFields = ['料件'];
  // 992 挂两父：driverRow 内容完全相同（同 base key），仅 __nodeId 不同
  const rowP = { driverRow: { 料件: '992' }, basicDataValues: {}, __nodeId: 'S-3120014539/992' };
  const rowQ = { driverRow: { 料件: '992' }, basicDataValues: {}, __nodeId: 'S-80011/992' };

  it('不传第4参（旧签名）：即便行带 __nodeId，也不加前缀，逐字节维持旧行为', () => {
    // 两行 base key 相同（'992'）且不加前缀 → 撞键 → #0/#1 消歧（旧行为，AC-10 核价侧零回归）
    expect(buildUniqueRowKeys(fields, rowKeyFields, [rowP, rowQ]))
      .toEqual(['992#0', '992#1']);
  });

  it('applyNodePrefix=false 显式传入：同上，不加前缀', () => {
    expect(buildUniqueRowKeys(fields, rowKeyFields, [rowP, rowQ], false))
      .toEqual(['992#0', '992#1']);
  });

  it('applyNodePrefix=true + 行带 __nodeId：先加前缀再唯一化 → 两个前缀不同 → 不撞键，无 #N 后缀', () => {
    expect(buildUniqueRowKeys(fields, rowKeyFields, [rowP, rowQ], true))
      .toEqual(['S-3120014539/992::992', 'S-80011/992::992']);
  });

  it('applyNodePrefix=true 但行无 __nodeId（非树行）：不加前缀，逐字节等于旧口径', () => {
    const rowNoNode = { driverRow: { 料件: 'P1' }, basicDataValues: {} };
    expect(buildUniqueRowKeys(fields, rowKeyFields, [rowNoNode], true))
      .toEqual(buildUniqueRowKeys(fields, rowKeyFields, [rowNoNode]));
  });

  it('applyNodePrefix=true 场景下，同 __nodeId 的两行仍会撞键消歧（结构身份相同则退化旧行为）', () => {
    const rowP2 = { driverRow: { 料件: '992' }, basicDataValues: {}, __nodeId: 'S-3120014539/992' };
    expect(buildUniqueRowKeys(fields, rowKeyFields, [rowP, rowP2], true))
      .toEqual(['S-3120014539/992::992#0', 'S-3120014539/992::992#1']);
  });
});

describe('getByKeyWithLegacyFallback (F0 查表旧键回退)', () => {
  it('新键命中：直接返回，不查旧键', () => {
    const m = new Map([['new-key', { v: 1 }], ['legacy-key', { v: 2 }]]);
    expect(getByKeyWithLegacyFallback(m, 'new-key', 'legacy-key')).toEqual({ v: 1 });
  });

  it('新键未命中、旧键命中：回退到旧键（存量单据兼容）', () => {
    const m = new Map([['legacy-key', { v: 2 }]]);
    expect(getByKeyWithLegacyFallback(m, 'new-key', 'legacy-key')).toEqual({ v: 2 });
  });

  it('新旧键都未命中：返回 undefined', () => {
    const m = new Map([['other-key', { v: 3 }]]);
    expect(getByKeyWithLegacyFallback(m, 'new-key', 'legacy-key')).toBeUndefined();
  });

  it('legacyKey 与 key 相同（非树行）：不重复查询，行为等价于普通 map.get', () => {
    const m = new Map([['k', { v: 4 }]]);
    expect(getByKeyWithLegacyFallback(m, 'k', 'k')).toEqual({ v: 4 });
    expect(getByKeyWithLegacyFallback(m, 'miss', 'miss')).toBeUndefined();
  });

  it('map 为 undefined：返回 undefined（不抛异常）', () => {
    expect(getByKeyWithLegacyFallback(undefined, 'k', 'legacy')).toBeUndefined();
  });

  it('legacyKey 未传：只查新键', () => {
    const m = new Map([['legacy-key', { v: 5 }]]);
    expect(getByKeyWithLegacyFallback(m, 'new-key')).toBeUndefined();
  });
});
