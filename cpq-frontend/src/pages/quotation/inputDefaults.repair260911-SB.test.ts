/**
 * repair-260911 · 分片 S-B（精度回归）独立验收用例
 *
 * 覆盖：AC-R5（精度契约回归）+ AC-R3 的阳性对照单测。
 *
 * 纪律说明：
 * - 本文件的每一条断言均**逐字派生自 `问题说明.md §⑦` 的 AC 原文**，未参考 `inputDefaults.ts`
 *   的实现体，也未复用实现提交 a0b0e09d 自带的 `inputDefaults.test.ts`（那份与实现同源）。
 * - `basicDataValues` 的键 `'{$b._项次}'` 直接抄 AC-R3 原文给出的字面量，不经 `bnfDriverLookupKey`
 *   推导 —— 键格式若不对，下面的「字符串 '1' 对照组」会一起变红，从而与「number 被丢弃」区分开。
 * - 字段对象形状也逐字照抄 AC 原文（只有 name / field_type / default_source 三个键）。
 */
import { describe, it, expect } from 'vitest';
import { resolveInputDefaultSourceOnly } from './inputDefaults';

/** AC 原文给出的字段形状，原样构造；本片不依赖 ComponentField 的具体定义。 */
const 项次字段 = (fieldType: string = 'INPUT_NUMBER') =>
  ({
    name: '项次',
    field_type: fieldType,
    default_source: { type: 'BASIC_DATA', path: '$b._项次' },
  }) as never;

/** AC-R3 原文给出的 ctx 形状：键为 '{$b._项次}'，值为裸 number（模拟实时通道）。 */
const ctx = (v: unknown) => ({ basicDataValues: { '{$b._项次}': v } }) as never;

const 解析 = (v: unknown, fieldType?: string) =>
  resolveInputDefaultSourceOnly(项次字段(fieldType), ctx(v));

describe('S-B / AC-R3 阳性对照 · number vs string 单变量隔离', () => {
  it('AC-R3：裸 number 1 → "1"（改动前为 undefined，改完必须为 "1"）', () => {
    const actual = 解析(1);
    // eslint-disable-next-line no-console
    console.log('[S-B][AC-R3] resolve(number 1) =', JSON.stringify(actual));
    expect(actual).toBe('1');
  });

  it('AC-R3 对照组：同一路径喂字符串 "1" → "1"（改动前就该绿；它红说明是键格式/路径问题而非 number 问题）', () => {
    const actual = 解析('1');
    // eslint-disable-next-line no-console
    console.log('[S-B][AC-R3-ctrl] resolve(string "1") =', JSON.stringify(actual));
    expect(actual).toBe('1');
  });
});

describe('S-B / AC-R5 精度契约回归 · 只放行 Number.isSafeInteger', () => {
  it('量具自检：字面量 9007199254740993 本身已非安全整数', () => {
    expect(Number.isSafeInteger(9007199254740993)).toBe(false);
  });

  it('AC-R5①：1 → "1"', () => {
    const actual = 解析(1);
    // eslint-disable-next-line no-console
    console.log('[S-B][AC-R5-1] resolve(1) =', JSON.stringify(actual));
    expect(actual).toBe('1');
  });

  it('AC-R5②：1.5 → undefined（小数仍被丢弃）', () => {
    const actual = 解析(1.5);
    // eslint-disable-next-line no-console
    console.log('[S-B][AC-R5-2] resolve(1.5) =', JSON.stringify(actual));
    expect(actual).toBeUndefined();
  });

  it('AC-R5③：9007199254740993（超安全整数）→ undefined', () => {
    const actual = 解析(9007199254740993);
    // eslint-disable-next-line no-console
    console.log('[S-B][AC-R5-3] resolve(9007199254740993) =', JSON.stringify(actual));
    expect(actual).toBeUndefined();
  });

  it('AC-R5 边界补充：NaN / Infinity / -Infinity 均非安全整数 → undefined', () => {
    expect(解析(NaN)).toBeUndefined();
    expect(解析(Infinity)).toBeUndefined();
    expect(解析(-Infinity)).toBeUndefined();
  });

  it('AC-R5 边界补充：0 / -3 是安全整数 → 放行', () => {
    expect(解析(0)).toBe('0');
    expect(解析(-3)).toBe('-3');
  });

  it('AC-R5 边界补充：-1.5 与 1e21（非安全整数）→ undefined', () => {
    expect(Number.isSafeInteger(1e21)).toBe(false);
    expect(解析(-1.5)).toBeUndefined();
    expect(解析(1e21)).toBeUndefined();
  });
});
