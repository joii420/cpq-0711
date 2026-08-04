/**
 * 「本页签金额合计」（页签底部那行）= 该页签所有「金额列」(is_amount && is_subtotal) 的列小计之和。
 *
 * BL-0017（2026-06-30）：`[页签(总计)]` 公式引擎值已对齐到本口径 —— 通过哨兵列键
 * `${key}#${AMOUNT_TOTAL_KEY}`（= Σ金额列）实现，求值器不变、裸键不变。本行显示与
 * `[页签(总计)]` 现已**同值同口径**（不再分叉）。裸键 `allComponentSubtotals[tabName]` 仍 =
 * Σ所有 is_subtotal 列，专供 previous_row_subtotal / 产品小计兜底 / 折扣，未受 BL-0017 影响。
 *
 * task-0801：本文件两处累加改十进制精确（sumDecimal），不再 number `+=` 链式累加 —— 链路一
 * （单页签金额合计，量级 ≤10⁶）内部精确算，结果仍以 number 承载，呈现精度由显示层负责。
 */
import { sumDecimal } from '../../utils/precision';

/** BL-0017 哨兵列键：`${componentId|code|tabName}#__amount_total__` = 该页签金额列小计之和。 */
export const AMOUNT_TOTAL_KEY = '__amount_total__';

interface CompLike {
  componentType?: string;
  tabName: string;
  componentCode?: string;
  fields?: Array<{ name: string; is_subtotal?: boolean; is_amount?: boolean }>;
}

/**
 * BL-0017：从「列名→列小计」字典求金额列(is_amount && is_subtotal)之和。
 * 供 component_subtotal 各装配点登记哨兵键 `${key}#__amount_total__`。
 * @param fields 组件字段（带 is_amount / is_subtotal）
 * @param byCol  列名→列小计（如 computeTabSubtotalsByColumn / sumColumnsCanonical 的产物）
 */
export function sumAmountFromByCol(
  fields: Array<{ name: string; is_subtotal?: boolean; is_amount?: boolean }> | undefined,
  byCol: Record<string, number>,
): number {
  if (!fields) return 0;
  const amountCols = new Set(
    fields.filter((f) => f.is_amount && f.is_subtotal).map((f) => f.name),
  );
  const values: number[] = [];
  for (const [col, val] of Object.entries(byCol)) {
    if (amountCols.has(col)) values.push(val);
  }
  return sumDecimal(values).toNumber();
}

/**
 * @param comp        当前页签组件
 * @param subtotalMap per-column 列小计字典，键 `${componentCode}#${列名}` / `${tabName}#${列名}`
 * @returns 该组件所有「金额列」(is_amount && is_subtotal) 的列小计之和（无金额列 / 空组件 → 0）
 */
export function sumTabColumns(
  comp: CompLike | undefined,
  subtotalMap: Record<string, number>,
): number {
  if (!comp?.fields) return 0;
  const values: number[] = [];
  for (const f of comp.fields) {
    // M1 保险：金额合计只认「既是金额、又有真实小计值」的列（per-column 小计仅为 is_subtotal 列写入）
    if (!(f.is_amount && f.is_subtotal)) continue;
    values.push(
      subtotalMap[`${comp.componentCode}#${f.name}`]
      ?? subtotalMap[`${comp.tabName}#${f.name}`] ?? 0,
    );
  }
  const total = sumDecimal(values).toNumber();
  return total;
}
