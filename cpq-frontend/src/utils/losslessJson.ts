import { isLosslessNumber, parse } from 'lossless-json';
import { normalizeDecimalString, type DecimalString } from './precision';

const STRUCTURAL_INTEGER_KEYS = new Set([
  '__lvl',
  'annualVolume',
  'decimals',
  'index',
  'page',
  'pageSize',
  'rowCount',
  'row_index',
  'snapshotRows',
  'sortOrder',
  'sort_order',
  'version',
  'width',
  '项次',
  '_项次',
]);

/**
 * Parse legacy snapshot JSON without creating an IEEE-754 number first.
 * Numeric tokens are converted from their original literal directly to decimal strings.
 */
export function parseSnapshotJsonLossless<T>(json: string): T {
  return parse(json, (key, value) => {
    if (!isLosslessNumber(value)) return value;
    if (STRUCTURAL_INTEGER_KEYS.has(key) && /^[+-]?\d+$/.test(value.value)) {
      const integer = parseInt(value.value, 10);
      if (Number.isSafeInteger(integer)) return integer;
    }
    return normalizeDecimalString(value.value) satisfies DecimalString;
  }) as T;
}

export function tryParseSnapshotJsonLossless<T>(json: string | null | undefined): T | null {
  if (!json) return null;
  try {
    return parseSnapshotJsonLossless<T>(json);
  } catch {
    return null;
  }
}
