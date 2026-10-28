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
  /** Optional caller-specific display cap. Only applies to computed values. */
  maxDisplayDecimals?: number | null;
  /** Existing percentage presentation contract remains unchanged. */
  isPercent?: boolean;
}

export function resolveDecimals(spec: DecimalSpec): number | null {
  const computedCap = spec.isComputed
    ? Math.min(
        DISPLAY_SCALE,
        spec.maxDisplayDecimals == null
          ? DISPLAY_SCALE
          : Math.max(0, Math.trunc(spec.maxDisplayDecimals)),
      )
    : null;
  if (spec.decimals != null) {
    const configured = Math.max(0, Math.trunc(spec.decimals));
    return computedCap == null ? configured : Math.min(computedCap, configured);
  }
  return computedCap;
}

/** Shared UI presentation boundary. Precision values must arrive as Decimal/string. */
export function formatNumber(value: DecimalValue | null | undefined, spec: DecimalSpec = {}): string | null {
  if (value == null || value === '') return null;
  if (!(value instanceof Decimal) && !isDecimalString(value)) return null;
  const decimal = toDecimal(value);
  if (!decimal.isFinite()) return null;

  if (spec.isPercent) {
    const configured = Math.max(0, Math.trunc(spec.decimals ?? 2));
    const decimals = spec.isComputed
      ? (resolveDecimals({ ...spec, decimals: configured }) ?? configured)
      : configured;
    return `${decimal.times('100').toDecimalPlaces(decimals, ROUNDING).toFixed(decimals)}%`;
  }

  const decimals = resolveDecimals(spec);
  if (decimals == null) {
    const text = decimal.toFixed();
    return text === '-0' ? '0' : text;
  }
  return formatDisplayDecimal(decimal, decimals);
}
