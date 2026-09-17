import { describe, expect, it } from 'vitest';
import Decimal from 'decimal.js';
import {
  CALCULATION_SCALE,
  DISPLAY_SCALE,
  DIVISION_SCALE,
  ELEMENT_PRICE_SCALE,
  FORMULA_RESULT_SCALE,
  PRODUCT_CARD_SUBTOTAL_SCALE,
  QUOTATION_TOTAL_SCALE,
  ROUNDING,
  divideDecimal,
  evaluateArithmetic,
  formatDisplayDecimal,
  formatElementPriceInput,
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
    expect(formatProductCardSubtotal('1.2345678905')).toBe('1.234567891');
    expect(formatQuotationTotal('1.2345678905')).toBe('1.234567891');
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

describe('element price scale (task-260916)', () => {
  it('locks ELEMENT_PRICE_SCALE=9 (mirrors backend PrecisionPolicy.ELEMENT_PRICE_SCALE)', () => {
    expect(ELEMENT_PRICE_SCALE).toBe(9);
  });

  it('displays element prices with at most 9 decimals, trailing zeros trimmed, HALF_UP', () => {
    expect(formatDisplayDecimal('101.139210000000', ELEMENT_PRICE_SCALE)).toBe('101.13921');
    expect(formatDisplayDecimal('105.000000000000', ELEMENT_PRICE_SCALE)).toBe('105');
    expect(formatDisplayDecimal('2.0000000005', ELEMENT_PRICE_SCALE)).toBe('2.000000001');
    expect(formatDisplayDecimal('0.1234567894', ELEMENT_PRICE_SCALE)).toBe('0.123456789');
    expect(formatDisplayDecimal('171.368000000000', ELEMENT_PRICE_SCALE)).toBe('171.368');
  });

  it('input formatter trims padded zeros when idle and echoes raw text while typing (M-1)', () => {
    const idle = (v: string | undefined) => formatElementPriceInput(v, { userTyping: false, input: '' });
    expect(idle('1')).toBe('1');
    expect(idle('1.000000000')).toBe('1');
    expect(idle('101.139220000')).toBe('101.13922');
    expect(idle('0.000000000')).toBe('0');
    expect(idle('-0.500000000')).toBe('-0.5');
    expect(idle('3.123456789')).toBe('3.123456789');
    expect(idle(undefined)).toBe('');
    expect(idle('')).toBe('');
    expect(idle('-')).toBe('-');
    expect(formatElementPriceInput('1.2', { userTyping: true, input: '1.20' })).toBe('1.20');
    expect(formatElementPriceInput('1', { userTyping: true, input: '1.' })).toBe('1.');
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
