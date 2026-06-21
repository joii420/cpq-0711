import type { LineItem } from './QuotationStep2';
import { getComponentSubtotals, evalProductSubtotalFromSubtotals } from './QuotationStep2';
import type { DriverExpansionMap } from './useDriverExpansions';

export interface DiscountSourceOption {
  value: string;
  label: string;
}

/**
 * 折扣来源选项 = 总金额(默认置顶) + 产品小计公式里每个去重的 component_subtotal 页签。
 * 解析 SUBTOTAL 组件的 formulas[0].expression（或 item.subtotalFormula）中的
 * component_subtotal token，按 component_code 去重，生成下拉选项。
 */
export function extractDiscountSources(item: LineItem): DiscountSourceOption[] {
  const opts: DiscountSourceOption[] = [{ value: 'SUBTOTAL', label: '总金额' }];
  const subtotalComp = item.componentData?.find(c => c.componentType === 'SUBTOTAL');
  const expr: any[] =
    subtotalComp?.formulas?.[0]?.expression ??
    (item as any).subtotalFormula ??
    [];
  const seen = new Set<string>();
  for (const tok of expr) {
    if (tok?.type !== 'component_subtotal') continue;
    const code = tok.component_code ?? tok.value;
    if (!code || seen.has(code)) continue;
    seen.add(code);
    opts.push({ value: code, label: `${tok.tab_name ?? code}.小计` });
  }
  return opts;
}

export interface LineDiscountResult {
  /** 折前产品单价 */
  original: number;
  /** 折后产品单价 */
  discounted: number;
  /** 折扣基数（所选来源的页签小计，SUBTOTAL 时等于 original） */
  discountBaseAmount: number;
  /** 行级折扣金额 = (original - discounted) * annualVolume */
  lineDiscountAmount: number;
  /** 折后产品单价（与 discounted 相同，给 UI 字段映射用） */
  lineFinalPrice: number;
  /** 行级总金额 = discounted * annualVolume */
  lineTotalAmount: number;
}

/**
 * 按所选来源/折扣率/年用量计算折后小计与各金额。
 *
 * - source='SUBTOTAL'：整单价打折 → discounted = original * (1 - rate/100)
 * - source=某页签 code：把该页签小计缩放后代回公式重算
 *   （同时按 tab_name 也缩放，保证 evaluateExpression 命中）
 */
export function computeLineDiscount(
  item: LineItem,
  driverExpansions: DriverExpansionMap | undefined,
  customerId: string | undefined,
  source: string,
  ratePct: number,
  annualVolume: number,
): LineDiscountResult {
  const subs = getComponentSubtotals(item, driverExpansions, customerId);
  const s0 = evalProductSubtotalFromSubtotals(item, subs);
  const r = Math.max(0, Math.min(100, ratePct || 0));
  const scale = 1 - r / 100;
  const qty = annualVolume || 0;

  let s1: number;
  let base: number;

  if (!source || source === 'SUBTOTAL') {
    s1 = s0 * scale;
    base = s0;
  } else {
    base = subs[source] ?? 0;
    // 缩放对应页签的三个键（componentId/componentCode/tabName）
    const scaled = { ...subs };
    // 找到对应 token，一并缩放 tab_name 键（evaluateExpression 可能按 tab_name 解析）
    if (scaled[source] !== undefined) {
      scaled[source] = scaled[source] * scale;
    }
    // 同步缩放 tab_name 键（找 SUBTOTAL 公式 token 中对应 component_code 的 tab_name）
    const subtotalComp = item.componentData?.find(c => c.componentType === 'SUBTOTAL');
    const expr: any[] =
      subtotalComp?.formulas?.[0]?.expression ??
      (item as any).subtotalFormula ??
      [];
    for (const tok of expr) {
      if (tok?.type !== 'component_subtotal') continue;
      const code = tok.component_code ?? tok.value;
      if (code !== source) continue;
      const tabName: string | undefined = tok.tab_name;
      if (tabName && tabName !== source && scaled[tabName] !== undefined) {
        scaled[tabName] = scaled[tabName] * scale;
      }
      // 也同步缩放 componentId 键（如果与 source 不同）
      // （subs 里 componentId/componentCode/tabName 三键都有同值，缩放 source 键后其他键仍是旧值）
      // 找对应 NORMAL 组件的 componentId
      const normComp = item.componentData?.find(
        c => c.componentCode === source || c.tabName === source,
      );
      if (normComp?.componentId && normComp.componentId !== source && scaled[normComp.componentId] !== undefined) {
        scaled[normComp.componentId] = scaled[normComp.componentId] * scale;
      }
    }
    s1 = evalProductSubtotalFromSubtotals(item, scaled);
  }

  const round4 = (n: number) => Math.round(n * 10000) / 10000;
  return {
    original: round4(s0),
    discounted: round4(s1),
    discountBaseAmount: round4(base),
    lineDiscountAmount: round4((s0 - s1) * qty),
    lineFinalPrice: round4(s1),
    lineTotalAmount: round4(s1 * qty),
  };
}
