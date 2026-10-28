import { formatNumber } from '../../utils/formatNumber';

/** Element actual price is a frozen price lookup, not a computed quotation result. */
export function formatElementActualPrice(value: string): string {
  return formatNumber(value, { isComputed: true }) ?? '0';
}
