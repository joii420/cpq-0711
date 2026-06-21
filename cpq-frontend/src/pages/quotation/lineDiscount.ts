import type { LineItem } from './QuotationStep2';
import { getComponentSubtotals, evalProductSubtotalFromSubtotals } from './QuotationStep2';
import type { DriverExpansionMap } from './useDriverExpansions';

export interface DiscountSourceOption {
  value: string;
  label: string;
}

/**
 * 折扣来源选项 = 总金额(默认置顶) + 产品小计公式里每个 component_subtotal 引用项（按字段粒度）。
 *
 * 解析 SUBTOTAL 组件 formulas[0].expression（或 item.subtotalFormula）里的 component_subtotal token，
 * **每个 token 一项**（不按 component_code 折叠——否则同页签多列如 [来料.材料成本]+[来料.材料损耗成本] 会丢项）。
 * - value = `component_code#列名`（唯一列键，与前/后端 componentSubtotals 列键格式一致）；
 * - label = token 自带的 `label`（如「来料·材料成本」，页签·字段，清晰唯一），缺失时退回列名。
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
    const col: string | undefined = tok.value;          // 列名(字段), 如 材料成本 / 费用
    const compCode: string | undefined = tok.component_code;
    const key = compCode && col ? `${compCode}#${col}` : (compCode ?? col);
    if (!key || seen.has(key)) continue;
    seen.add(key);
    const label = (tok.label && String(tok.label).trim()) || col || key;
    opts.push({ value: key, label });
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
 * - source=`component_code#列名`：把该列小计缩放后代回公式重算。
 *   evaluateExpression 解析 component_subtotal token 时优先用 `component_code#列名` 键，
 *   故只需缩放 scaled[source] 即命中（getComponentSubtotals 已写入该列键）。
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
    // source = `component_code#列名`（按列折扣）：缩放该列键后代回公式重算。
    base = subs[source] ?? 0;
    const scaled = { ...subs };
    if (scaled[source] !== undefined) scaled[source] = scaled[source] * scale;
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
