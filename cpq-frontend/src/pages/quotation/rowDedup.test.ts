import { describe, it, expect } from 'vitest';
import { computeDedupKey, findDuplicateRowKeys } from './rowDedup';
import { bnfDriverLookupKey } from './useDriverExpansions';

describe('computeDedupKey', () => {
  it('driver 列取自 driverRow', () => {
    expect(computeDedupKey(['child_no', 'elem'], { child_no: 'P1', elem: 'Cu' }, {})).toBe('P1||Cu');
  });
  it('输入字段从 rowValues 回退取', () => {
    expect(computeDedupKey(['child_no', 'material'], { child_no: 'P1' }, { material: 'SUS304' })).toBe('P1||SUS304');
  });
  it('driver 非空优先于 rowValues', () => {
    expect(computeDedupKey(['elem'], { elem: 'Cu' }, { elem: 'Ni' })).toBe('Cu');
  });
  it('全空 → null', () => {
    expect(computeDedupKey(['a', 'b'], {}, {})).toBeNull();
  });
  it('空 rowKeyFields → null', () => {
    expect(computeDedupKey([], { x: 1 }, {})).toBeNull();
  });
  it('__seq_no__ 哨兵 → null', () => {
    expect(computeDedupKey(['__seq_no__'], {}, {})).toBeNull();
  });
});

describe('findDuplicateRowKeys', () => {
  it('无重复 → 空集', () => {
    const rows = [
      { driverRow: { child_no: 'P1' }, rowValues: { material: 'Cu' } },
      { driverRow: { child_no: 'P1' }, rowValues: { material: 'Ni' } },
    ];
    expect(findDuplicateRowKeys(rows, ['child_no', 'material']).size).toBe(0);
  });
  it('一组重复 → 两个下标', () => {
    const rows = [
      { driverRow: { child_no: 'P1' }, rowValues: { material: 'Cu' } },
      { driverRow: { child_no: 'P1' }, rowValues: { material: 'Cu' } },
      { driverRow: { child_no: 'P2' }, rowValues: { material: 'Cu' } },
    ];
    expect([...findDuplicateRowKeys(rows, ['child_no', 'material'])].sort()).toEqual([0, 1]);
  });
  it('手动行 vs driver 行跨来源撞键', () => {
    const rows = [
      { driverRow: { child_no: 'P1' }, rowValues: { material: 'Cu' } },
      { driverRow: {}, rowValues: { child_no: 'P1', material: 'Cu' } },
    ];
    expect(findDuplicateRowKeys(rows, ['child_no', 'material']).size).toBe(2);
  });
  it('全空键不判重', () => {
    const rows = [{ driverRow: {}, rowValues: {} }, { driverRow: {}, rowValues: {} }];
    expect(findDuplicateRowKeys(rows, ['a', 'b']).size).toBe(0);
  });
});

// ─── T7：computeDedupKey 字段感知版（外购件场景）────────────────────────────
// 外购件 rowKeyFields=["料件","运费"]，driverRow 键为 _前缀别名，BDV 有正确键

describe('computeDedupKey — 字段感知版（5-arg）', () => {
  const FIELDS = [
    {
      name: '料件',
      fieldType: 'INPUT_TEXT',
      defaultSource: { type: 'BASIC_DATA', path: '$wgj_view._料件' },
    },
    {
      name: '运费',
      fieldType: 'INPUT_NUMBER',
      defaultSource: { type: 'BASIC_DATA', path: '$wgj_view._运费' },
    },
  ];
  const bdvKey料件 = bnfDriverLookupKey('$wgj_view._料件');
  const bdvKey运费 = bnfDriverLookupKey('$wgj_view._运费');

  it('外购件：BDV 解析 → "料9||运费"', () => {
    const driverRow = { _料件: '料9', _运费: '运费' };
    const bdv = { [bdvKey料件]: '料9', [bdvKey运费]: '运费' };
    const result = computeDedupKey(['料件', '运费'], driverRow, {}, FIELDS, bdv);
    // 修复前：driverRow["料件"]=undefined → 全空 → null；修复后 → "料9||运费"
    expect(result).toBe('料9||运费');
  });

  it('旧场景（无 fields/bdv）：driverRow 直读 → 兼容', () => {
    const driverRow = { child_no: 'P1', elem: 'Cu' };
    expect(computeDedupKey(['child_no', 'elem'], driverRow, {})).toBe('P1||Cu');
  });

  it('fields 传入但 driverRow 直读成功 → 直读优先', () => {
    const driverRow = { 料件: '料9', 运费: '运费' };
    const result = computeDedupKey(['料件', '运费'], driverRow, {}, FIELDS, {});
    expect(result).toBe('料9||运费');
  });

  it('全空（driverRow 无直读，BDV 也无键）→ null', () => {
    const result = computeDedupKey(['料件', '运费'], {}, {}, FIELDS, {});
    expect(result).toBeNull();
  });

  it('rowValues 兜底（driver/BDV 均无）→ 取手填值', () => {
    const rowValues = { 料件: '料9', 运费: '运费' };
    const result = computeDedupKey(['料件', '运费'], {}, rowValues, FIELDS, {});
    expect(result).toBe('料9||运费');
  });
});
