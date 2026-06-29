import React from 'react';
import type { CostingTemplateColumn } from '../../services/costingTemplateService';
import { formatNumber } from '../../utils/formatNumber';

export { formatNumber };

/**
 * 判断 Excel 列是否为计算列（未配 decimals 时兜底 2 位小数）。
 * 计算列：FORMULA / CARD_FORMULA / TAB_JOIN_FORMULA / EXCEL_FORMULA
 * 取数列：VARIABLE / PRODUCT_ATTRIBUTE / COMPONENT_FIELD / FIXED_VALUE 等 → 保留原精度
 */
export function isComputedExcelColumn(sourceType: string | undefined): boolean {
  return ['FORMULA', 'CARD_FORMULA', 'TAB_JOIN_FORMULA', 'EXCEL_FORMULA'].includes(sourceType ?? '');
}

/**
 * 格式化单元格值（统一接入 formatNumber，与卡片视图同口径）：
 * - null/undefined/''/''—'' → 显示 "—"
 * - PERCENT 格式：经 formatNumber(isPercent) → 值 ×100 + % 后缀（默认 2 位）
 * - 计算列（FORMULA/CARD_FORMULA/TAB_JOIN_FORMULA/EXCEL_FORMULA）未配 decimals → 兜底 2 位；
 *   原始/取数列未配 decimals → 保留原精度（如汇率 6.9755）
 * - 非数值字符串原样显示
 */
export function renderCellValue(val: any, col: CostingTemplateColumn): React.ReactNode {
  if (val === null || val === undefined || val === '' || val === '—') {
    return <span style={{ color: '#bbb' }}>—</span>;
  }

  const fmt = col.display_format;
  if (fmt?.type === 'PERCENT') {
    const out = formatNumber(val, { isPercent: true, decimals: fmt.decimals ?? 2 });
    return out ?? <span style={{ color: '#bbb' }}>—</span>;
  }

  const isComputed = isComputedExcelColumn(col.source_type);
  // 数值（含数值字符串）走 formatNumber；返回 null（空/非数值）→ 占位 "—"
  const out = formatNumber(val, { decimals: fmt?.decimals ?? null, isComputed });
  if (out != null) return out;
  // formatNumber 判定非数值 → 保持原字符串
  if (typeof val === 'number') return <span style={{ color: '#bbb' }}>—</span>;
  return String(val);
}
