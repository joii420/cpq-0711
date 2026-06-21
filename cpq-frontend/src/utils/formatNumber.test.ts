import { describe, it, expect } from 'vitest';
import { formatNumber, resolveDecimals } from './formatNumber';

describe('resolveDecimals', () => {
  it('显式 decimals 优先', () => { expect(resolveDecimals({ decimals: 3, isComputed: true })).toBe(3); });
  it('计算列未配 → 兜底 2', () => { expect(resolveDecimals({ isComputed: true })).toBe(2); });
  it('输入/取数列未配 → null(保留原精度)', () => { expect(resolveDecimals({ isComputed: false })).toBeNull(); });
});

describe('formatNumber', () => {
  it('计算列兜底 2 位、HALF_UP', () => {
    expect(formatNumber(0.144, { isComputed: true })).toBe('0.14');
    expect(formatNumber(0.145, { isComputed: true })).toBe('0.15');
  });
  it('"最多两位"去尾零', () => {
    expect(formatNumber(0.1, { isComputed: true })).toBe('0.1');
    expect(formatNumber(5, { isComputed: true })).toBe('5');
  });
  it('输入/取数列保留原精度(汇率不被压)', () => {
    expect(formatNumber(6.9755, { isComputed: false })).toBe('6.9755');
  });
  it('显式 decimals 覆盖', () => {
    expect(formatNumber(6.9755, { decimals: 4 })).toBe('6.9755');
    expect(formatNumber(6.9755, { decimals: 2 })).toBe('6.98');
  });
  it('PERCENT: *100 + %', () => {
    expect(formatNumber(0.0825, { isPercent: true, decimals: 2 })).toBe('8.25%');
  });
  it('空/非数字 → null', () => {
    expect(formatNumber('', {})).toBeNull();
    expect(formatNumber(null, {})).toBeNull();
    expect(formatNumber('abc', {})).toBeNull();
  });
});
