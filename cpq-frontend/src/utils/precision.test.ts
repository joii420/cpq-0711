import { describe, expect, it } from 'vitest';
import Decimal from 'decimal.js';
import {
  CALCULATION_SCALE,
  DISPLAY_SCALE,
  DIVISION_SCALE,
  FORMULA_RESULT_SCALE,
  PRODUCT_CARD_SUBTOTAL_SCALE,
  QUOTATION_TOTAL_SCALE,
  ROUNDING,
  divideDecimal,
  evaluateArithmetic,
  formatDisplayDecimal,
  formatFormulaResult,
  formatProductCardSubtotal,
  formatQuotationTotal,
  isFormulaFieldType,
  isDecimalString,
  normalizeDecimalString,
  roundToCalculation,
  sumDecimal,
  toCalculationString,
  toDecimal,
} from './precision';

describe('precision policy', () => {
  it('locks calculation=12, display=9 and HALF_UP', () => {
    expect(CALCULATION_SCALE).toBe(12);
    expect(DISPLAY_SCALE).toBe(9);
    expect(DIVISION_SCALE).toBe(CALCULATION_SCALE);
    expect(ROUNDING).toBe(Decimal.ROUND_HALF_UP);
  });

  it('keeps formula, product subtotal and quotation total result scales independent', () => {
    expect(FORMULA_RESULT_SCALE).toBe(9);
    expect(PRODUCT_CARD_SUBTOTAL_SCALE).toBe(9);
    expect(QUOTATION_TOTAL_SCALE).toBe(9);
    expect(formatFormulaResult('1.2345678905')).toBe('1.234567891');
    expect(formatProductCardSubtotal('1.2345678905', 7)).toBe('1.2345679');
    expect(formatQuotationTotal('1.2345678905', 8)).toBe('1.23456789');
  });

  it('recognizes formula field types without classifying input fields', () => {
    expect(isFormulaFieldType('FORMULA')).toBe(true);
    expect(isFormulaFieldType('LIST_FORMULA')).toBe(true);
    expect(isFormulaFieldType('INPUT_NUMBER')).toBe(false);
    expect(isFormulaFieldType(undefined)).toBe(false);
  });

  it('accepts plain decimal strings and rejects scientific API strings', () => {
    expect(isDecimalString('98765431.123456789012')).toBe(true);
    expect(isDecimalString('-0.5')).toBe(true);
    expect(isDecimalString('1.2E+8')).toBe(false);
  });

  it('normalizes trailing zeros and negative zero', () => {
    expect(normalizeDecimalString('5.0000')).toBe('5');
    expect(normalizeDecimalString('-0.0000')).toBe('0');
  });

  it('rounds formula nodes to 12 places HALF_UP', () => {
    expect(toCalculationString('1.2345678912345')).toBe('1.234567891235');
    expect(roundToCalculation('-1.2345678912345').toFixed(12)).toBe('-1.234567891235');
  });

  it('formats at most 9 places without changing the working value', () => {
    const working = '98765431.123456789012';
    expect(formatDisplayDecimal(working)).toBe('98765431.123456789');
    expect(working).toBe('98765431.123456789012');
  });

  it('covers positive and negative display HALF_UP boundaries', () => {
    expect(formatDisplayDecimal('1.2345678914')).toBe('1.234567891');
    expect(formatDisplayDecimal('1.2345678915')).toBe('1.234567892');
    expect(formatDisplayDecimal('-1.2345678915')).toBe('-1.234567892');
    expect(formatDisplayDecimal('0.0000000004')).toBe('0');
    expect(formatDisplayDecimal('0.0000000005')).toBe('0.000000001');
  });
});

describe('decimal arithmetic', () => {
  it('does exact addition and multiplication', () => {
    expect(sumDecimal(['0.1', '0.2']).toString()).toBe('0.3');
    expect(toDecimal('2.5').times('0.4').toString()).toBe('1');
  });

  it('rounds division to 12 places', () => {
    expect(divideDecimal('1', '3').toFixed(12)).toBe('0.333333333333');
    expect(divideDecimal('5', '0').toString()).toBe('0');
  });

  it('keeps a large 12-place input exact', () => {
    expect(toDecimal('98765431.123456789012').toFixed()).toBe('98765431.123456789012');
  });
});

describe('arithmetic parser', () => {
  it.each([
    ['0.1+0.2', '0.3'],
    ['1/3', '0.333333333333'],
    ['10/3*3', '9.999999999999'],
    ['-(2+3)*2', '-10'],
    ['2+3*4', '14'],
    ['2×3÷4', '1.5'],
    ['5/0', '0'],
    ['1e-7', '0.0000001'],
  ])('%s => %s', (expression, expected) => {
    expect(evaluateArithmetic(expression)?.toFixed()).toBe(expected);
  });

  it('returns null for invalid input', () => {
    expect(evaluateArithmetic('')).toBeNull();
    expect(evaluateArithmetic('(1+2')).toBeNull();
    expect(evaluateArithmetic('(null.x)')).toBeNull();
  });
});
