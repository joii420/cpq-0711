/**
 * 单测：lineDiscount.ts（按字段粒度折扣）
 *
 * 还原真实场景：产品小计公式 =
 *   [来料.材料成本] + [来料.材料损耗成本] + [组装加工费.费用] + [其他费用.费用]
 * 其中「来料」一个组件贡献两列（材料成本=5、材料损耗成本=3），
 * 「组装加工费.费用」=8、「其他费用.费用」=2 → 产品单价 S0 = 5+3+8+2 = 18。
 *
 * component_subtotal token 结构（与库内真实数据一致）：
 *   { type, component_code, value=列名, tab_name=列名(数据里被写成字段名), label='页签·字段' }
 */
import { describe, it, expect } from 'vitest';
import { extractDiscountSources, computeLineDiscount } from './lineDiscount';
import type { LineItem } from './QuotationStep2';

function tok(component_code: string, col: string, label: string) {
  // 还原数据怪癖：tab_name 被写成字段名；但 label 是清晰的「页签·字段」
  return { type: 'component_subtotal', component_code, value: col, tab_name: col, label };
}

function normalComp(componentId: string, componentCode: string, tabName: string,
                    cols: Record<string, number>): any {
  return {
    componentId,
    componentCode,
    tabName,
    componentType: 'NORMAL',
    fields: Object.keys(cols).map(name => ({ name, field_type: 'INPUT_NUMBER', is_subtotal: true })),
    formulas: [],          // 必须为数组，否则 computeAllFormulas 提前返回空
    rows: [{ ...cols }],
  };
}

const mockExpansions = undefined;

function makeItem(): LineItem {
  const ll = normalComp('c-ll', 'LL', '来料', { 材料成本: 5, 材料损耗成本: 3 });
  const asm = normalComp('c-asm', 'ASM', '组装加工费', { 费用: 8 });
  const oth = normalComp('c-oth', 'OTH', '其他费用', { 费用: 2 });
  const sub: any = {
    componentId: 'c-sub',
    componentCode: 'SUBTOTAL',
    tabName: '产品小计',
    componentType: 'SUBTOTAL',
    fields: [],
    formulas: [{
      name: '产品单价',
      expression: [
        tok('LL', '材料成本', '来料·材料成本'),
        { type: 'operator', value: '+' },
        tok('LL', '材料损耗成本', '来料·材料损耗成本'),
        { type: 'operator', value: '+' },
        tok('ASM', '费用', '组装加工费·费用'),
        { type: 'operator', value: '+' },
        tok('OTH', '费用', '其他费用·费用'),
      ],
    }],
  };
  return {
    productPartNo: 'TEST-001',
    componentData: [ll, asm, oth, sub],
    productAttributes: [],
    productAttributeValues: {},
    subtotal: 0,
  } as unknown as LineItem;
}

describe('extractDiscountSources（按字段，每项一个，标签用 token.label）', () => {
  it('opts[0] 固定为 总金额', () => {
    expect(extractDiscountSources(makeItem())[0]).toEqual({ value: 'SUBTOTAL', label: '总金额' });
  });

  it('公式 4 项各成一项，不折叠同组件多列；value=code#列名', () => {
    const opts = extractDiscountSources(makeItem());
    expect(opts.map(o => o.value)).toEqual([
      'SUBTOTAL', 'LL#材料成本', 'LL#材料损耗成本', 'ASM#费用', 'OTH#费用',
    ]);
  });

  it('标签清晰唯一，用 token.label（来料·材料成本 / 组装加工费·费用 …）', () => {
    const opts = extractDiscountSources(makeItem());
    const byVal = Object.fromEntries(opts.map(o => [o.value, o.label]));
    expect(byVal['LL#材料成本']).toBe('来料·材料成本');
    expect(byVal['LL#材料损耗成本']).toBe('来料·材料损耗成本');
    expect(byVal['ASM#费用']).toBe('组装加工费·费用');
    expect(byVal['OTH#费用']).toBe('其他费用·费用');
  });
});

describe('computeLineDiscount', () => {
  it('source=SUBTOTAL 整单价打折：率20/量100 => S0=18, 折后=14.4, 折扣=360, 行合计=1440', () => {
    const r = computeLineDiscount(makeItem(), mockExpansions, undefined, 'SUBTOTAL', 20, 100);
    expect(r.original).toBeCloseTo(18, 2);
    expect(r.discounted).toBeCloseTo(14.4, 2);
    expect(r.lineDiscountAmount).toBeCloseTo(360, 2);
    expect(r.lineTotalAmount).toBeCloseTo(1440, 2);
  });

  it('source=LL#材料成本 只折该列(同组件另一列不动)：率20/量100 => 折后=17, 折扣=100, 基数=5', () => {
    // 材料成本 5*0.8=4，材料损耗成本 3 不动，+8+2 => 17
    const r = computeLineDiscount(makeItem(), mockExpansions, undefined, 'LL#材料成本', 20, 100);
    expect(r.original).toBeCloseTo(18, 2);
    expect(r.discountBaseAmount).toBeCloseTo(5, 2);
    expect(r.discounted).toBeCloseTo(17, 2);
    expect(r.lineDiscountAmount).toBeCloseTo(100, 2);   // (18-17)*100
    expect(r.lineTotalAmount).toBeCloseTo(1700, 2);     // 17*100
  });

  it('source=ASM#费用 只折组装加工费：率20/量100 => 折后=16.4, 基数=8', () => {
    const r = computeLineDiscount(makeItem(), mockExpansions, undefined, 'ASM#费用', 20, 100);
    expect(r.discountBaseAmount).toBeCloseTo(8, 2);
    expect(r.discounted).toBeCloseTo(16.4, 2);          // 5+3+6.4+2
    expect(r.lineDiscountAmount).toBeCloseTo(160, 2);
  });

  it('率0 不打折', () => {
    const r = computeLineDiscount(makeItem(), mockExpansions, undefined, 'LL#材料成本', 0, 100);
    expect(r.lineDiscountAmount).toBeCloseTo(0, 4);
    expect(r.discounted).toBeCloseTo(r.original, 4);
  });
});
