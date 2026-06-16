/**
 * 「本页签金额合计」（页签底部那行）= 该页签所有「金额列」(is_amount && is_subtotal) 的列小计之和。
 *
 * ⚠️ 此行仅用于显示，**不参与任何公式计算**，没有任何 token 读它。
 * 切勿与公式引擎的「组件小计 component_subtotal」混淆 —— 后者 = 该页签所有 is_subtotal 列之和，
 * 存于 allComponentSubtotals[tabName]（裸键），喂产品小计 / 跨页签公式。两者改本设计后会分叉：
 * 本行显示 = 金额列之和；引擎值仍 = 小计列之和。改本函数不触公式引擎。
 */
interface CompLike {
  componentType?: string;
  tabName: string;
  componentCode?: string;
  fields?: Array<{ name: string; is_subtotal?: boolean; is_amount?: boolean }>;
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
  let total = 0;
  for (const f of comp.fields) {
    // M1 保险：金额合计只认「既是金额、又有真实小计值」的列（per-column 小计仅为 is_subtotal 列写入）
    if (!(f.is_amount && f.is_subtotal)) continue;
    total += subtotalMap[`${comp.componentCode}#${f.name}`]
          ?? subtotalMap[`${comp.tabName}#${f.name}`] ?? 0;
  }
  return total;
}
