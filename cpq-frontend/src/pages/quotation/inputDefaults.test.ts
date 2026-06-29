import { describe, it, expect } from 'vitest';
import { resolveInputDefault, resolveInputDefaultSourceOnly, resolveInputDefaultForBake, coerceInputNumber } from './inputDefaults';
import type { ComponentField } from './QuotationStep2';
import { bnfDriverLookupKey } from './useDriverExpansions';

const f = (over: Partial<ComponentField>): ComponentField =>
  ({ name: 'X', field_type: 'INPUT_TEXT', key: 'X', label: 'X', ...over } as ComponentField);

describe('resolveInputDefault', () => {
  it('default_source BASIC_DATA 命中 basicDataValues（TEXT）', () => {
    const field = f({ field_type: 'INPUT_TEXT', default_source: { type: 'BASIC_DATA', path: '$ys_view.单位' } });
    const bdv = { [bnfDriverLookupKey('$ys_view.单位')]: 'PCS' };
    expect(resolveInputDefault(field, { basicDataValues: bdv })).toBe('PCS');
  });
  it('default_source 取空 → 回退静态 content', () => {
    const field = f({ field_type: 'INPUT_TEXT', content: 'KG', default_source: { type: 'BASIC_DATA', path: '$ys_view.单位' } });
    expect(resolveInputDefault(field, { basicDataValues: {} })).toBe('KG');
  });
  it('无 default_source → 直接静态 content（TEXT）', () => {
    expect(resolveInputDefault(f({ content: 'RMB' }), {})).toBe('RMB');
  });
  it('GLOBAL_VARIABLE 命中 @gvar', () => {
    const field = f({ field_type: 'INPUT_NUMBER', default_source: { type: 'GLOBAL_VARIABLE', code: 'TAX' } });
    expect(resolveInputDefault(field, { basicDataValues: { '@gvar:TAX': 13 } })).toBe(13);
  });
  it('BNF_PATH basicDataValues 缺 → pathCache 兜底', () => {
    const field = f({ field_type: 'INPUT_NUMBER', default_source: { type: 'BNF_PATH', path: '$v.a' } });
    expect(resolveInputDefault(field, { basicDataValues: {}, partNo: 'P1', pathCache: { 'P1::$v.a': 9 } })).toBe(9);
  });
  it('BASIC_DATA 不走 pathCache(单列ASCII失败) → 仅行级, 缺则 content/undefined', () => {
    const field = f({ field_type: 'INPUT_TEXT', default_source: { type: 'BASIC_DATA', path: '$v.b' } });
    expect(resolveInputDefault(field, { basicDataValues: {}, partNo: 'P1', pathCache: { 'P1::$v.b': 'X' } })).toBeUndefined();
  });
  it('全空 → undefined', () => {
    expect(resolveInputDefault(f({ content: '' }), {})).toBeUndefined();
  });
});

describe('resolveInputDefaultSourceOnly（不回退 content）', () => {
  it('源命中 → 返回源值', () => {
    const field = f({ field_type: 'INPUT_TEXT', content: 'KG', default_source: { type: 'BASIC_DATA', path: '$ys_view.单位' } });
    const bdv = { [bnfDriverLookupKey('$ys_view.单位')]: 'PCS' };
    expect(resolveInputDefaultSourceOnly(field, { basicDataValues: bdv })).toBe('PCS');
  });
  it('源未命中 → undefined（不回退 content）', () => {
    const field = f({ field_type: 'INPUT_TEXT', content: 'KG', default_source: { type: 'BASIC_DATA', path: '$ys_view.单位' } });
    expect(resolveInputDefaultSourceOnly(field, { basicDataValues: {} })).toBeUndefined();
  });
  it('无 default_source → undefined（绝不取 content）', () => {
    expect(resolveInputDefaultSourceOnly(f({ content: 'RMB' }), {})).toBeUndefined();
  });
});

describe('resolveInputDefaultForBake（导入带出一次性烘焙：源命中→源值；无源→静态content）', () => {
  it('无 default_source → 烘焙静态 content（不依赖 basicDataValues）', () => {
    expect(resolveInputDefaultForBake(f({ content: 'RMB' }), {})).toBe('RMB');
  });
  it('有 default_source 且源命中 → 烘焙源值（优先于 content）', () => {
    const field = f({ field_type: 'INPUT_TEXT', content: 'KG', default_source: { type: 'BASIC_DATA', path: '$ys_view.单位' } });
    const bdv = { [bnfDriverLookupKey('$ys_view.单位')]: 'PCS' };
    expect(resolveInputDefaultForBake(field, { basicDataValues: bdv })).toBe('PCS');
  });
  it('有 default_source 但源未命中 → undefined（绝不提前冻结 content，等驱动补值）', () => {
    const field = f({ field_type: 'INPUT_TEXT', content: 'KG', default_source: { type: 'BASIC_DATA', path: '$ys_view.单位' } });
    expect(resolveInputDefaultForBake(field, { basicDataValues: {} })).toBeUndefined();
  });
  it('content 为空串 + 无源 → undefined（无可烘焙值）', () => {
    expect(resolveInputDefaultForBake(f({ content: '' }), {})).toBeUndefined();
  });
  it('非 INPUT* 字段 → undefined', () => {
    expect(resolveInputDefaultForBake(f({ field_type: 'FORMULA' as any, content: 'X' }), {})).toBeUndefined();
  });
});

describe('coerceInputNumber', () => {
  it('合法转数、非法 undefined', () => {
    expect(coerceInputNumber('100')).toBe(100);
    expect(coerceInputNumber('-1.5')).toBe(-1.5);
    expect(coerceInputNumber(42)).toBe(42);
    expect(coerceInputNumber('abc')).toBeUndefined();
    expect(coerceInputNumber('1e3')).toBeUndefined();
    expect(coerceInputNumber('')).toBeUndefined();
  });
});
