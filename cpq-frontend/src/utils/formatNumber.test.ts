import { describe, expect, it } from 'vitest';
import { formatNumber, resolveDecimals } from './formatNumber';

describe('resolveDecimals', () => {
  it('uses 9 for computed values', () => {
    expect(resolveDecimals({ isComputed: true })).toBe(9);
  });

  it('respects a lower configured value and caps computed values at 9', () => {
    expect(resolveDecimals({ decimals: 3, isComputed: true })).toBe(3);
    expect(resolveDecimals({ decimals: 12, isComputed: true })).toBe(9);
  });

  it('does not cap an explicitly configured raw value', () => {
    expect(resolveDecimals({ decimals: 12, isComputed: false })).toBe(12);
    expect(resolveDecimals({ isComputed: false })).toBeNull();
  });

  it('applies a caller cap only to computed values', () => {
    expect(resolveDecimals({ isComputed: true, maxDisplayDecimals: 4 })).toBe(4);
    expect(resolveDecimals({ decimals: 6, isComputed: true, maxDisplayDecimals: 4 })).toBe(4);
    expect(resolveDecimals({ decimals: 2, isComputed: true, maxDisplayDecimals: 4 })).toBe(2);
    expect(resolveDecimals({ decimals: 12, isComputed: false, maxDisplayDecimals: 4 })).toBe(12);
  });
});

describe('formatNumber', () => {
  it('formats computed values at most 9 places and removes trailing zeros', () => {
    expect(formatNumber('0.333333333333', { isComputed: true })).toBe('0.333333333');
    expect(formatNumber('9.999999999999', { isComputed: true })).toBe('10');
    expect(formatNumber('5.000000000000', { isComputed: true })).toBe('5');
  });

  it('handles display rounding boundaries', () => {
    expect(formatNumber('1.2345678914', { isComputed: true })).toBe('1.234567891');
    expect(formatNumber('1.2345678915', { isComputed: true })).toBe('1.234567892');
    expect(formatNumber('-1.2345678915', { isComputed: true })).toBe('-1.234567892');
  });

  it('preserves unconfigured raw precision', () => {
    expect(formatNumber('0.123456789012', { isComputed: false })).toBe('0.123456789012');
  });

  it('formats sales-role computed boundaries at 4 places with HALF_UP and trims zeros', () => {
    const spec = { isComputed: true, maxDisplayDecimals: 4 } as const;
    expect(formatNumber('12.500000000000', spec)).toBe('12.5');
    expect(formatNumber('1.23444', spec)).toBe('1.2344');
    expect(formatNumber('1.23445', spec)).toBe('1.2345');
    expect(formatNumber('-1.23445', spec)).toBe('-1.2345');
  });

  it('uses the minimum of configured precision, global 9, and role cap', () => {
    expect(formatNumber('1.2345678915', { decimals: 2, isComputed: true, maxDisplayDecimals: 4 })).toBe('1.23');
    expect(formatNumber('1.2345678915', { decimals: 6, isComputed: true, maxDisplayDecimals: 4 })).toBe('1.2346');
    expect(formatNumber('1.2345678915', { decimals: 6, isComputed: true, maxDisplayDecimals: 9 })).toBe('1.234568');
  });

  it('keeps the existing percent contract', () => {
    expect(formatNumber('0.0825', { isPercent: true, decimals: 2 })).toBe('8.25%');
  });

  it('caps only computed percentages and preserves raw percentage precision', () => {
    expect(formatNumber('0.123456789', {
      isPercent: true, decimals: 6, isComputed: true, maxDisplayDecimals: 4,
    })).toBe('12.3457%');
    expect(formatNumber('0.123456789', {
      isPercent: true, decimals: 6, isComputed: false, maxDisplayDecimals: 4,
    })).toBe('12.345679%');
  });

  it('returns null for empty or invalid values', () => {
    expect(formatNumber('', {})).toBeNull();
    expect(formatNumber(null, {})).toBeNull();
    expect(formatNumber('abc', {})).toBeNull();
  });
});
