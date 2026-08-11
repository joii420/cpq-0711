import Decimal from 'decimal.js';
import {
  DISPLAY_SCALE,
  ROUNDING,
  type DecimalValue,
  formatDisplayDecimal,
  isDecimalString,
  toDecimal,
} from './precision';

export interface DecimalSpec {
  /** Field/column display precision. Structural metadata may remain a number. */
  decimals?: number | null;
  /** FORMULA/TAB_JOIN/CARD_FORMULA/subtotal/total value. */
  isComputed?: boolean;
  /** Existing percentage presentation contract remains unchanged. */
  isPercent?: boolean;
}

export function resolveDecimals(spec: DecimalSpec): number | null {
  if (spec.decimals != null) {
    const configured = Math.max(0, Math.trunc(spec.decimals));
    return spec.isComputed ? Math.min(DISPLAY_SCALE, configured) : configured;
  }
  return spec.isComputed ? DISPLAY_SCALE : null;
}

/** Shared UI presentation boundary. Precision values must arrive as Decimal/string. */
export function formatNumber(value: DecimalValue | null | undefined, spec: DecimalSpec = {}): string | null {
  if (value == null || value === '') return null;
  if (!(value instanceof Decimal) && !isDecimalString(value)) return null;
  const decimal = toDecimal(value);
  if (!decimal.isFinite()) return null;

  if (spec.isPercent) {
    const decimals = Math.max(0, Math.trunc(spec.decimals ?? 2));
    return `${decimal.times('100').toDecimalPlaces(decimals, ROUNDING).toFixed(decimals)}%`;
  }

  const decimals = resolveDecimals(spec);
  if (decimals == null) {
    const text = decimal.toFixed();
    return text === '-0' ? '0' : text;
  }
  return formatDisplayDecimal(decimal, decimals);
}
