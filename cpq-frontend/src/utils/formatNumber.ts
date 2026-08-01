import Decimal from 'decimal.js';
import { DISPLAY_SCALE } from './precision';

export interface DecimalSpec {
  /** 显式配置位数：字段 decimals 或列 display_format.decimals。null/undefined = 未配。 */
  decimals?: number | null;
  /** 是否为"计算得出的列"(FORMULA/TAB_JOIN/CARD_FORMULA/小计/总计/is_subtotal)；未配时兜底 DISPLAY_SCALE 位。 */
  isComputed?: boolean;
  /** PERCENT 列：值 ×100 加 % 后缀（默认 2 位）。 */
  isPercent?: boolean;
}

// 计算列未配位数时的兜底位数 —— 引用 precision.ts 的 DISPLAY_SCALE（不再自持字面量）。
//
// task-0801（2026-08-01）现口径：至多 6 位、去尾零，覆盖计算列 / 列小计 / 页签金额合计 /
// 产品小计 / 对外导出总额 —— 全部统一，不再有"小计 4 位、产品小计与导出 2 位"的分叉。
//
// ⚠️ 本条**推翻**了 2026-06-21 拍板的旧口径（见 docs/RECORD.md 2026-06-21 两条记录）：
//   旧口径 = 计算列/列小计/页签合计 4 位；产品小计 + 对外导出总额固定 2 位（走 formatCurrency
//   decimals:2，不经本兜底）。该旧口径当时的注释特别标注过"易被误当 bug 改回"——
//   现郑重声明：**该旧口径自 task-0801 起已作废**，全部改为本文件统一的 6 位去尾零兜底。
//   不要因为看到旧 RECORD 记录或旧 PR 讨论又把 2 位/4 位改回来。
//
// 与后端保持同步：NumberFormatUtil.COMPUTED_FALLBACK + ExcelViewService.COMPUTED_FALLBACK_DECIMALS
// （导出走 POI 故另有一份），三处常量值必须同为 6（单测各自锁定）。
const COMPUTED_FALLBACK = DISPLAY_SCALE;

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
