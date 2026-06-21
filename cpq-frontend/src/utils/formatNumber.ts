import Decimal from 'decimal.js';

export interface DecimalSpec {
  /** 显式配置位数：字段 decimals 或列 display_format.decimals。null/undefined = 未配。 */
  decimals?: number | null;
  /** 是否为"计算得出的列"(FORMULA/TAB_JOIN/CARD_FORMULA/小计/总计/is_subtotal)；未配时兜底 2 位。 */
  isComputed?: boolean;
  /** PERCENT 列：值 ×100 加 % 后缀（默认 2 位）。 */
  isPercent?: boolean;
}

// 计算列未配位数时的兜底位数。⚠️ 与后端保持同步：
// NumberFormatUtil.COMPUTED_FALLBACK + ExcelViewService.COMPUTED_FALLBACK_DECIMALS（导出走 POI 故另有一份）。
const COMPUTED_FALLBACK = 2;

export function resolveDecimals(spec: DecimalSpec): number | null {
  if (spec.decimals != null) return spec.decimals;
  if (spec.isComputed) return COMPUTED_FALLBACK;
  return null;
}

function trimTrailing(s: string): string {
  return s.includes('.') ? s.replace(/\.?0+$/, '') : s;
}

/** 统一数字格式化口径（卡片/Excel视图/导出共用）。返回 null 表示应显示占位 "—"。 */
export function formatNumber(value: unknown, spec: DecimalSpec = {}): string | null {
  if (value == null || value === '') return null;
  let d: Decimal;
  try { d = new Decimal(typeof value === 'number' ? value : String(value).trim()); }
  catch { return null; }
  if (!d.isFinite()) return null;

  if (spec.isPercent) {
    const dec = spec.decimals ?? 2;
    return `${d.times(100).toDecimalPlaces(dec, Decimal.ROUND_HALF_UP).toFixed(dec)}%`;
  }
  const dec = resolveDecimals(spec);
  if (dec == null) return trimTrailing(d.toString());
  return trimTrailing(d.toDecimalPlaces(dec, Decimal.ROUND_HALF_UP).toFixed(dec));
}
