import { describe, it, expect } from 'vitest';
import { factorFor, applyUnitConversion } from './unitConversion';

describe('factorFor', () => {
  // 与后端 UnitConversionTest 同一组输入（跨端对拍护栏）
  const cases: [string, number][] = [
    ['克', 0.001], ['g', 0.001], ['G', 0.001],
    ['千克', 1], ['KG', 1], ['kG', 1],
    ['吨', 1000], ['t', 1000],
    ['片', 1], ['pcs', 1],
    ['KPCS', 1000], ['千片', 1000],
    ['g/PCS', 0.001], ['G/pcs', 0.001],
    [' g / PCS ', 0.001],
    ['g/KPCS', 0.000001], ['G/kpcs', 0.000001], [' g / KPCS ', 0.000001],
    ['mm', 1], ['', 1], ['  ', 1],
  ];
  it.each(cases)('factorFor(%s) = %d', (unit, expected) => {
    expect(factorFor(unit)).toBeCloseTo(expected, 10);
  });
  it('null/undefined → 1', () => {
    expect(factorFor(undefined)).toBe(1);
    expect(factorFor(null as any)).toBe(1);
  });
});

describe('applyUnitConversion', () => {
  const fields = [
    { name: '重量', field_type: 'INPUT_NUMBER', unit_source_field: '单位' },
    { name: '单位', field_type: 'INPUT_TEXT' },
    { name: '数量', field_type: 'INPUT_NUMBER' },
  ];
  it('换配置列、保留 D 与未配列、不 mutate 原行', () => {
    const row = { 重量: '500', 单位: 'g', 数量: 3 };
    const out = applyUnitConversion(fields as any, row);
    expect(out.重量).toBeCloseTo(0.5, 10);
    expect(out.单位).toBe('g');
    expect(out.数量).toBe(3);
    expect(row.重量).toBe('500');   // 原行未被 mutate
    expect(out).not.toBe(row);       // 返回新对象
  });
  it('未知单位透传', () => {
    const out = applyUnitConversion(fields as any, { 重量: '500', 单位: 'mm' });
    expect(out.重量).toBeCloseTo(500, 10);
  });
  it('无配置列时原样返回', () => {
    const row = { a: 1 };
    expect(applyUnitConversion([{ name: 'a', field_type: 'INPUT_NUMBER' }] as any, row)).toBe(row);
  });
});
