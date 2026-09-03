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

/**
 * 去掉小数点后多余的尾随 0 —— **纯字符串处理，绝不经过 JS `number`**（task-260901 · F-12 / AC-30）。
 *
 *   '90.000000000000'   → '90'
 *   '12.345678901200'   → '12.3456789012'
 *   '0.150000000000'    → '0.15'
 *   '100.000000000000'  → '100'
 *   '100'               → '100'（无小数点原样返回）
 *
 * 🚨 禁止改成 `Number(s).toString()`：`numeric(16,12)` 的 12 位小数过 JS `number` 会丢尾数，
 * 且 `12.345678901200` 与 `12.3456789012` 在 double 下无法区分。
 * 🚨 本函数只作用于**渲染层与输入框回填**，不改变存储 / 接口 / 比较的精度：
 * `'88'` 与 `'88.000000000000'` 在 `numeric(16,12)` 与 `BigDecimal.compareTo` 下同值。
 */
export function trimTrailingZeros(value: string | null | undefined): string {
  if (value == null) return '';
  const text = String(value).trim();
  if (!text) return '';
  return text.replace(/(\.\d*?)0+$/, '$1').replace(/\.$/, '');
}

/**
 * 含量的显示文本（不带 % 号）。空值回退 '—'。
 * 与 `trimTrailingZeros` 的区别只在空值处理，供表格单元格直接使用。
 */
export function formatPctText(value: string | null | undefined, fallback = '—'): string {
  const trimmed = trimTrailingZeros(value);
  return trimmed === '' ? fallback : trimmed;
}
