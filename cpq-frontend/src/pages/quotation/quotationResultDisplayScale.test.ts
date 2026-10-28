import { describe, expect, it } from 'vitest';
import {
  quotationResultDisplayScale,
  quotationSurfaceDisplayScale,
} from './quotationResultDisplayScale';

describe('quotationResultDisplayScale', () => {
  it.each([
    ['SALES_REP', 4],
    ['SALES_MANAGER', 4],
    ['PRICING_MANAGER', 9],
    ['SYSTEM_ADMIN', 9],
  ] as const)('maps %s to %i display places', (role, expected) => {
    expect(quotationResultDisplayScale(role)).toBe(expected);
  });

  it('preserves the existing 9-place behavior while auth is unavailable', () => {
    expect(quotationResultDisplayScale(undefined)).toBe(9);
    expect(quotationResultDisplayScale(null)).toBe(9);
  });
});

describe('quotationSurfaceDisplayScale', () => {
  it('keeps quotation detail and SALES comparison on the role-derived scale', () => {
    expect(quotationSurfaceDisplayScale('QUOTATION', 4)).toBe(4);
    expect(quotationSurfaceDisplayScale('QUOTATION', 9)).toBe(9);
  });

  it('keeps FINANCE comparison and independent costing at 9 places', () => {
    expect(quotationSurfaceDisplayScale('INDEPENDENT_COSTING', 4)).toBe(9);
    expect(quotationSurfaceDisplayScale('INDEPENDENT_COSTING', 9)).toBe(9);
  });
});
