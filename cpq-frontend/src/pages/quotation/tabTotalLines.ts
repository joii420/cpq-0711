/** 卡片底部"页签总计"汇总线：每个非 SUBTOTAL 组件 → 其所有 is_subtotal 列的列小计之和 → 一条。 */
export interface TabTotalLine {
  label: string;
  value: number;
}

interface CompLike {
  componentType?: string;
  tabName: string;
  componentCode?: string;
  fields?: Array<{ name: string; is_subtotal?: boolean }>;
}

/**
 * @param componentData 卡片组件列表
 * @param subtotalMap   per-column 列小计字典，键 `${componentCode}#${列名}` / `${tabName}#${列名}`
 */
export function buildTabTotalLines(
  componentData: CompLike[],
  subtotalMap: Record<string, number>,
): TabTotalLine[] {
  const lines: TabTotalLine[] = [];
  if (!Array.isArray(componentData)) return lines;
  for (const comp of componentData) {
    if (!comp?.fields || comp.componentType === 'SUBTOTAL') continue;
    const subFields = comp.fields.filter((f) => f.is_subtotal);
    if (subFields.length === 0) continue;
    let total = 0;
    for (const f of subFields) {
      total += subtotalMap[`${comp.componentCode}#${f.name}`]
            ?? subtotalMap[`${comp.tabName}#${f.name}`] ?? 0;
    }
    lines.push({ label: `${comp.tabName} · 总计`, value: total });
  }
  return lines;
}
