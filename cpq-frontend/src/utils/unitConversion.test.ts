import { describe, it, expect } from 'vitest';
import { factorFor, applyUnitConversion } from './unitConversion';

describe('factorFor', () => {
  // 与后端 UnitConversionTest 同一组输入（跨端对拍护栏）
  const cases: [string, number][] = [
    ['克', 0.001], ['g', 0.001], ['G', 0.001],
    ['千克', 1], ['KG', 1], ['kG', 1],
    ['吨', 1000], ['t', 1000],
    ['片', 1], ['pcs', 1],
    // KPCS / 千片：2026-07-28 业务修订为「每千片 → 每片」÷1000（原 ×1000）。中英别名必须同值。
    ['KPCS', 0.001], ['kpcs', 0.001], ['千片', 0.001],
    ['g/PCS', 0.001], ['G/pcs', 0.001],
    [' g / PCS ', 0.001],
    // KG/KPCS 与 G/PCS 数学等价（1 kg/千片 = 1 g/片）。
    ['KG/KPCS', 0.001], ['kg/kpcs', 0.001], [' kg / KPCS ', 0.001],
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

  // 中英别名必须同系数，否则同一行换个写法结果就变（KPCS/千片 相差 100 万倍是最易踩的坑）
  it.each([['KPCS', '千片'], ['KG', '千克'], ['G', '克'], ['T', '吨'], ['PCS', '片']])(
    '别名一致：%s === %s', (en, zh) => {
      expect(factorFor(en)).toBe(factorFor(zh));
    });

  it('KG/KPCS 与 G/PCS 数学等价', () => {
    expect(factorFor('KG/KPCS')).toBe(factorFor('G/PCS'));
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
