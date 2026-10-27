import { formatDisplayDecimal, isDecimalString } from '../../utils/precision';

/**
 * 报价行利润快照展示：仅接受十进制字符串，避免经由 JavaScript number 丢失精度。
 * 历史报价和异常/缺失快照统一显示短横线，不从当前销售产品回填。
 */
export function formatProfitBadgeText(value: unknown): string {
  return isDecimalString(value) ? `利润: ${formatDisplayDecimal(value)}` : '利润: -';
}
