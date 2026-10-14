import { describe, it, expect } from 'vitest';
import { factorFor, applyUnitConversion } from './unitConversion';

describe('directed unit factors', () => {
  it.each([
    ['EA', 'PCS', '1'], ['EA', 'KPCS', '0.001'],
    ['PCS', 'EA', '1'], ['pcs', 'kpcs', '0.001'],
    ['KPCS', 'PCS', '1000'],
    ['g', 'kg', '0.001'], ['kg', 'g', '1000'],
    ['kg', 't', '0.001'], ['t', 'kg', '1000'],
    ['g', '盎司', '0.03527396194958'], ['盎司', 'g', '28.349523125'],
  ])('factorFor(%s → %s) = %s', (source, target, expected) => {
    expect(factorFor(source, target)).toBe(expected);
  });

  it.each(['克', '千克', '吨', '片', '千片', 'G/PCS', 'KG/KPCS', 'mm'])
    ('unknown/compound value %s is identity passthrough', (source) => {
      expect(factorFor(source, 'kg')).toBe('1');
    });

  it('same code is implicit identity and matching ignores case', () => {
    expect(factorFor('Pcs', 'PCS')).toBe('1');
    expect(factorFor('KG', 'kg')).toBe('1');
  });

  it('cross-category conversion is identity', () => {
    expect(factorFor('kg', 'PCS')).toBe('1');
    expect(factorFor('PCS', 'kg')).toBe('1');
  });

  it('explicit relationship context keeps opposite directions independent', () => {
    const relations = [
      { source: 'PCS', target: 'KPCS', factor: '0.002' },
      { source: 'KPCS', target: 'PCS', factor: '1000' },
    ];
    expect(factorFor('pcs', 'kpcs', { relations })).toBe('0.002');
    expect(factorFor('kpcs', 'pcs', { relations })).toBe('1000');
  });
});

describe('applyUnitConversion', () => {
  const fields = [
    { name: '重量', field_type: 'INPUT_NUMBER', unit_source_field: '单位' },
    { name: '单位', field_type: 'INPUT_TEXT' },
    { name: '数量', field_type: 'INPUT_NUMBER' },
  ];

  it('uses settlement target and preserves unit_source_field behavior', () => {
    const row = { 重量: '1000', 单位: 'g', 数量: '3' };
    const out = applyUnitConversion(fields as any, row, 'kg');
    expect(out.重量).toBe('1');
    expect(out.单位).toBe('g');
    expect(out.数量).toBe('3');
    expect(row.重量).toBe('1000');
    expect(out).not.toBe(row);
  });

  it('unknown, compound, cross-category and missing target values pass through', () => {
    expect(applyUnitConversion(fields as any, { 重量: '500', 单位: 'mm' }, 'kg').重量).toBe('500');
    expect(applyUnitConversion(fields as any, { 重量: '500', 单位: 'G/PCS' }, 'kg').重量).toBe('500');
    expect(applyUnitConversion(fields as any, { 重量: '12.5', 单位: 'kg' }, 'PCS').重量).toBe('12.5');
    expect(applyUnitConversion(fields as any, { 重量: '1000', 单位: 'g' }, null as any).重量).toBe('1000');
  });

  it('no configured columns returns the original row', () => {
    const row = { a: '1' };
    expect(applyUnitConversion([{ name: 'a', field_type: 'INPUT_NUMBER' }] as any, row, 'kg')).toBe(row);
  });
});
