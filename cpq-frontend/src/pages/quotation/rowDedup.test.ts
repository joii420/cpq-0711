import { describe, it, expect } from 'vitest';
import { computeDedupKey, findDuplicateRowKeys } from './rowDedup';

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
