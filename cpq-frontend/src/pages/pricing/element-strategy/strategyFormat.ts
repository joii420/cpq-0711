import { METHOD_LABEL, UNIT_LABEL, type PriceMethod, type WindowUnit } from '../../../types/element-price-strategy';

/** 窗口展示：「最近 30 天」；LATEST 或缺失时「—」 */
export function formatWindow(windowNum?: number | null, windowUnit?: WindowUnit | null): string {
  if (!windowNum || !windowUnit) return '—';
  return `最近 ${windowNum} ${UNIT_LABEL[windowUnit]}`;
}

export function formatMethod(method?: PriceMethod | null): string {
  return method ? (METHOD_LABEL[method] ?? method) : '—';
}
