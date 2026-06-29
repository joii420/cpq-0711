import { describe, it, expect } from 'vitest';
import { isComputedExcelColumn } from './excelCellFormat';

describe('isComputedExcelColumn', () => {
  it('TAB_JOIN_FORMULA is computed (core regression guard)', () => {
    expect(isComputedExcelColumn('TAB_JOIN_FORMULA')).toBe(true);
  });

  it('FORMULA is computed', () => {
    expect(isComputedExcelColumn('FORMULA')).toBe(true);
  });

  it('CARD_FORMULA is computed', () => {
    expect(isComputedExcelColumn('CARD_FORMULA')).toBe(true);
  });

  it('EXCEL_FORMULA is computed', () => {
    expect(isComputedExcelColumn('EXCEL_FORMULA')).toBe(true);
  });

  it('VARIABLE is NOT computed (preserves raw precision)', () => {
    expect(isComputedExcelColumn('VARIABLE')).toBe(false);
  });

  it('PRODUCT_ATTRIBUTE is NOT computed', () => {
    expect(isComputedExcelColumn('PRODUCT_ATTRIBUTE')).toBe(false);
  });

  it('FIXED_VALUE is NOT computed', () => {
    expect(isComputedExcelColumn('FIXED_VALUE')).toBe(false);
  });

  it('undefined returns false', () => {
    expect(isComputedExcelColumn(undefined)).toBe(false);
  });

  it('empty string returns false', () => {
    expect(isComputedExcelColumn('')).toBe(false);
  });
});
