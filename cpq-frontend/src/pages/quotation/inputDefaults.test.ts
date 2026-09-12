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
    expect(resolveInputDefault(field, { basicDataValues: { '@gvar:TAX': '13' } })).toBe('13');
  });
  it('BNF_PATH basicDataValues 缺 → pathCache 兜底', () => {
    const field = f({ field_type: 'INPUT_NUMBER', default_source: { type: 'BNF_PATH', path: '$v.a' } });
    expect(resolveInputDefault(field, { basicDataValues: {}, partNo: 'P1', pathCache: { 'P1::$v.a': '9' } })).toBe('9');
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
    expect(coerceInputNumber('100')).toBe('100');
    expect(coerceInputNumber('-1.5')).toBe('-1.5');
    expect(coerceInputNumber('98765431.123456789012')).toBe('98765431.123456789012');
    expect(coerceInputNumber('1.2300')).toBe('1.2300');
    expect(coerceInputNumber(42)).toBeUndefined();
    expect(coerceInputNumber('abc')).toBeUndefined();
    expect(coerceInputNumber('1e3')).toBeUndefined();
    expect(coerceInputNumber('')).toBeUndefined();
  });
});

// repair-260911 F-1 · AC-R3 / AC-R5
// 背景：实时 batch-expand 通道走 axios 默认 JSON.parse（无 lossless），BASIC_DATA 源值到前端是
// 裸 JS number；而快照通道走 tryParseSnapshotJsonLossless，同一个值是字符串 "1"。
// 本组用例**精确锁住「number vs string」这一个变量**：把入参从 1 换成 "1"，改动前就绿。
describe('resolveInputDefaultSourceOnly · 裸 JS number 源值（安全整数放行 / 其余仍丢弃）', () => {
  const 项次 = (): ComponentField =>
    f({ name: '项次', field_type: 'INPUT_NUMBER', default_source: { type: 'BASIC_DATA', path: '$b._项次' } });
  const bdv = (v: unknown) => ({ basicDataValues: { [bnfDriverLookupKey('$b._项次')]: v } });

  // AC-R3 的现成断言（诊断 J2）：改回 `return undefined` 这条必须变红
  it('安全整数 1 → "1"（AC-R3：BOM 项次列不再全空）', () => {
    expect(resolveInputDefaultSourceOnly(项次(), bdv(1))).toBe('1');
  });

  it('对照组：同一路径喂字符串 "1" → "1"（改动前后都绿，用来隔离变量）', () => {
    expect(resolveInputDefaultSourceOnly(项次(), bdv('1'))).toBe('1');
  });

  // AC-R5：task-0810 精度契约的原意必须保住
  it('小数 1.5 → undefined（IEEE-754 可能已丢精度，仍丢弃）', () => {
    expect(resolveInputDefaultSourceOnly(项次(), bdv(1.5))).toBeUndefined();
  });

  it('超安全整数 9007199254740993 → undefined', () => {
    expect(Number.isSafeInteger(9007199254740993)).toBe(false); // 量具自检：字面量本身已被 double 改写
    expect(resolveInputDefaultSourceOnly(项次(), bdv(9007199254740993))).toBeUndefined();
  });

  it('NaN / Infinity → undefined', () => {
    expect(resolveInputDefaultSourceOnly(项次(), bdv(NaN))).toBeUndefined();
    expect(resolveInputDefaultSourceOnly(项次(), bdv(Infinity))).toBeUndefined();
  });

  it('负整数 / 0 也放行', () => {
    expect(resolveInputDefaultSourceOnly(项次(), bdv(0))).toBe('0');
    expect(resolveInputDefaultSourceOnly(项次(), bdv(-3))).toBe('-3');
  });

  it('INPUT_TEXT 字段同样放行安全整数（放行判据在源值类型，与 field_type 无关）', () => {
    const field = f({ name: '项次', field_type: 'INPUT_TEXT', default_source: { type: 'BASIC_DATA', path: '$b._项次' } });
    expect(resolveInputDefaultSourceOnly(field, bdv(2))).toBe('2');
  });

  it('bake 链路同步生效（QuotationStep2 的 resolveInputDefaultForBake → 本函数）', () => {
    expect(resolveInputDefaultForBake(项次(), bdv(1))).toBe('1');
    expect(resolveInputDefaultForBake(项次(), bdv(1.5))).toBeUndefined();
  });
});
