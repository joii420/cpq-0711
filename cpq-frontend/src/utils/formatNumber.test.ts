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

  it('keeps the existing percent contract', () => {
    expect(formatNumber('0.0825', { isPercent: true, decimals: 2 })).toBe('8.25%');
  });

  it('returns null for empty or invalid values', () => {
    expect(formatNumber('', {})).toBeNull();
    expect(formatNumber(null, {})).toBeNull();
    expect(formatNumber('abc', {})).toBeNull();
  });
});
