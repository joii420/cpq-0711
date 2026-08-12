import Decimal from 'decimal.js';

export const CALCULATION_SCALE = 12;
export const DISPLAY_SCALE = 9;
// Future system parameters will override these result-boundary defaults independently.
export const FORMULA_RESULT_SCALE = 9;
export const PRODUCT_CARD_SUBTOTAL_SCALE = 9;
export const QUOTATION_TOTAL_SCALE = 9;
export const DIVISION_SCALE = CALCULATION_SCALE;
export const ROUNDING = Decimal.ROUND_HALF_UP;

export type DecimalString = string;
export type DecimalValue = DecimalString | Decimal;
export type PrecisionJsonValue =
  | DecimalString
  | boolean
  | null
  | PrecisionJsonValue[]
  | { [key: string]: PrecisionJsonValue };

// Keep enough significant digits for numeric(26,12) values and intermediate products.
Decimal.set({
  precision: 80,
  rounding: ROUNDING,
  toExpNeg: -1_000_000,
  toExpPos: 1_000_000,
});

const PLAIN_DECIMAL = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/;

export function isDecimalString(value: unknown): value is DecimalString {
  return typeof value === 'string' && PLAIN_DECIMAL.test(value.trim());
}

/** Strict precision entry point. JS numbers are deliberately rejected. */
export function toDecimal(value: DecimalValue | null | undefined): Decimal {
  if (value == null || value === '') return new Decimal(0);
  if (value instanceof Decimal) return value;
  const text = value.trim();
  if (!PLAIN_DECIMAL.test(text)) return new Decimal(0);
  try {
    const decimal = new Decimal(text);
    return decimal.isFinite() ? decimal : new Decimal(0);
  } catch {
    return new Decimal(0);
  }
}

export function roundToCalculation(value: DecimalValue): Decimal {
  return toDecimal(value).toDecimalPlaces(CALCULATION_SCALE, ROUNDING);
}

function trimFixed(value: string): DecimalString {
  const trimmed = value.includes('.') ? value.replace(/\.?0+$/, '') : value;
  return trimmed === '-0' || trimmed === '+0' || trimmed === '' ? '0' : trimmed;
}

export function toCalculationString(value: DecimalValue): DecimalString {
  return trimFixed(roundToCalculation(value).toFixed(CALCULATION_SCALE));
}

export function normalizeDecimalString(value: DecimalValue): DecimalString {
  return trimFixed(toDecimal(value).toFixed());
}

export function formatDisplayDecimal(
  value: DecimalValue,
  scale: number = DISPLAY_SCALE,
): DecimalString {
  const boundedScale = Math.max(0, Math.min(DISPLAY_SCALE, Math.trunc(scale)));
  return trimFixed(toDecimal(value).toDecimalPlaces(boundedScale, ROUNDING).toFixed(boundedScale));
}

function formatResultDecimal(value: DecimalValue, scale: number): DecimalString {
  const boundedScale = Math.max(0, Math.min(CALCULATION_SCALE, Math.trunc(scale)));
  return trimFixed(toDecimal(value).toDecimalPlaces(boundedScale, ROUNDING).toFixed(boundedScale));
}

export function formatFormulaResult(
  value: DecimalValue,
  scale: number = FORMULA_RESULT_SCALE,
): DecimalString {
  return formatResultDecimal(value, scale);
}

export function formatProductCardSubtotal(
  value: DecimalValue,
  scale: number = PRODUCT_CARD_SUBTOTAL_SCALE,
): DecimalString {
  return formatResultDecimal(value, scale);
}

export function formatQuotationTotal(
  value: DecimalValue,
  scale: number = QUOTATION_TOTAL_SCALE,
): DecimalString {
  return formatResultDecimal(value, scale);
}

export function isFormulaFieldType(fieldType: string | null | undefined): boolean {
  return fieldType === 'FORMULA' || fieldType?.endsWith('_FORMULA') === true;
}

/** Compatibility name for UI call sites. It returns display text, never a JS number. */
export function roundToDisplay(value: DecimalValue): DecimalString {
  return formatDisplayDecimal(value);
}

export function sumDecimal(values: ReadonlyArray<DecimalValue | null | undefined>): Decimal {
  return values.reduce<Decimal>((sum, value) => sum.plus(toDecimal(value)), new Decimal(0));
}

export function divideDecimal(left: DecimalValue, right: DecimalValue): Decimal {
  const divisor = toDecimal(right);
  if (divisor.isZero()) return new Decimal(0);
  return toDecimal(left).dividedBy(divisor).toDecimalPlaces(DIVISION_SCALE, ROUNDING);
}

class ArithDecimalParser {
  private index = 0;
  private readonly expression: string;

  constructor(expression: string) {
    this.expression = expression;
  }

  parse(): Decimal {
    const value = this.expr();
    this.skipSpaces();
    if (this.index < this.expression.length) {
      throw new Error(`Unexpected token at ${this.index}`);
    }
    return value;
  }

  private skipSpaces(): void {
    while (/\s/.test(this.expression[this.index] ?? '')) this.index += 1;
  }

  private expr(): Decimal {
    let value = this.term();
    for (;;) {
      this.skipSpaces();
      const operator = this.expression[this.index];
      if (operator !== '+' && operator !== '-') return value;
      this.index += 1;
      const right = this.term();
      value = operator === '+' ? value.plus(right) : value.minus(right);
    }
  }

  private term(): Decimal {
    let value = this.factor();
    for (;;) {
      this.skipSpaces();
      const operator = this.expression[this.index];
      if (operator !== '*' && operator !== '/') return value;
      this.index += 1;
      const right = this.factor();
      value = operator === '*' ? value.times(right) : divideDecimal(value, right);
    }
  }

  private factor(): Decimal {
    this.skipSpaces();
    const token = this.expression[this.index];
    if (token === '+') {
      this.index += 1;
      return this.factor();
    }
    if (token === '-') {
      this.index += 1;
      return this.factor().negated();
    }
    if (token === '(') {
      this.index += 1;
      const value = this.expr();
      this.skipSpaces();
      if (this.expression[this.index] !== ')') throw new Error('Missing closing parenthesis');
      this.index += 1;
      return value;
    }
    return this.number();
  }

  private number(): Decimal {
    this.skipSpaces();
    const remaining = this.expression.slice(this.index);
    const match = remaining.match(/^(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?/);
    if (!match) throw new Error(`Expected number at ${this.index}`);
    this.index += match[0].length;
    return new Decimal(match[0]);
  }
}

export function evaluateArithmetic(expression: string | null | undefined): Decimal | null {
  if (typeof expression !== 'string') return null;
  const normalized = expression.replace(/×/g, '*').replace(/÷/g, '/');
  if (!normalized.trim()) return null;
  try {
    const value = new ArithDecimalParser(normalized).parse();
    return value.isFinite() ? value : new Decimal(0);
  } catch {
    return null;
  }
}
