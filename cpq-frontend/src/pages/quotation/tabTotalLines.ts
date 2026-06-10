/** 单个组件"本页签总计" = 其所有 is_subtotal 列的列小计之和（显示在该页签表格底部）。 */
interface CompLike {
  componentType?: string;
  tabName: string;
  componentCode?: string;
  fields?: Array<{ name: string; is_subtotal?: boolean }>;
}

/**
 * @param comp        当前页签组件
 * @param subtotalMap per-column 列小计字典，键 `${componentCode}#${列名}` / `${tabName}#${列名}`
 * @returns 该组件所有 is_subtotal 列的列小计之和（无小计列 / 空组件 → 0）
 */
export function sumTabColumns(
  comp: CompLike | undefined,
  subtotalMap: Record<string, number>,
): number {
  if (!comp?.fields) return 0;
  let total = 0;
  for (const f of comp.fields) {
    if (!f.is_subtotal) continue;
    total += subtotalMap[`${comp.componentCode}#${f.name}`]
          ?? subtotalMap[`${comp.tabName}#${f.name}`] ?? 0;
  }
  return total;
}
