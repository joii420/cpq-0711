/**
 * TDD 单测：lineDiscount.ts
 *
 * 产品小计公式 = ASM.小计 + OTH.小计
 *   ASM.小计 = 8（组装加工费）
 *   OTH.小计 = 2（其他费用）
 *   product subtotal = 10
 */
import { describe, it, expect } from 'vitest';
import { extractDiscountSources, computeLineDiscount } from './lineDiscount';
import type { LineItem } from './QuotationStep2';

// ---------- 构造 mock LineItem ----------

/** evaluateExpression token: component_subtotal */
function tok(component_code: string, tab_name: string) {
  return { type: 'component_subtotal', component_code, value: component_code, tab_name };
}

/** 构造 mock driverExpansions —— 空 map（无 driver expand，走 comp.rows 旧路径） */
const mockExpansions = undefined;

/**
 * 构造一个最简 LineItem：
 *  - NORMAL 组件 ASM（小计 8）、OTH（小计 2）
 *  - SUBTOTAL 组件公式 = ASM + OTH
 */
function makeItem(): LineItem {
  const asmComp: any = {
    componentId: 'c-asm',
    componentCode: 'ASM',
    tabName: '组装加工费',
    componentType: 'NORMAL',
    fields: [
      {
        name: 'unit_price',
        field_type: 'INPUT_NUMBER',
        is_subtotal: true,
      },
    ],
    formulas: [],        // 必须提供（空数组），否则 computeAllFormulas 在 !comp.formulas 处提前返回空
    rows: [{ unit_price: 8 }],
  };

  const othComp: any = {
    componentId: 'c-oth',
    componentCode: 'OTH',
    tabName: '其他费用',
    componentType: 'NORMAL',
    fields: [
      {
        name: 'unit_price',
        field_type: 'INPUT_NUMBER',
        is_subtotal: true,
      },
    ],
    formulas: [],        // 同上
    rows: [{ unit_price: 2 }],
  };

  const subtotalComp: any = {
    componentId: 'c-sub',
    componentCode: 'SUBTOTAL',
    tabName: '产品小计',
    componentType: 'SUBTOTAL',
    fields: [],
    formulas: [
      {
        name: '产品单价',
        expression: [tok('ASM', '组装加工费'), { type: 'operator', value: '+' }, tok('OTH', '其他费用')],
      },
    ],
  };

  return {
    productPartNo: 'TEST-001',
    componentData: [asmComp, othComp, subtotalComp],
    productAttributes: [],
    productAttributeValues: {},
    subtotal: 0,
  } as unknown as LineItem;
}

// ============================================================
describe('extractDiscountSources', () => {
  it('opts[0] 固定为 SUBTOTAL 总金额', () => {
    const item = makeItem();
    const opts = extractDiscountSources(item);
    expect(opts[0]).toEqual({ value: 'SUBTOTAL', label: '总金额' });
  });

  it('包含 ASM 和 OTH 两个页签来源', () => {
    const item = makeItem();
    const opts = extractDiscountSources(item);
    const values = opts.map(o => o.value);
    expect(values).toContain('ASM');
    expect(values).toContain('OTH');
  });

  it('ASM 的 label 含"组装加工费"', () => {
    const item = makeItem();
    const opts = extractDiscountSources(item);
    const asm = opts.find(o => o.value === 'ASM');
    expect(asm?.label).toContain('组装加工费');
  });
});

// ============================================================
describe('computeLineDiscount — source=SUBTOTAL（整单价打折）', () => {
  it('折扣率 20%，年用量 100 => original=10, discounted=8, lineDiscountAmount=200, lineTotalAmount=800', () => {
    const item = makeItem();
    const r = computeLineDiscount(item, mockExpansions, undefined, 'SUBTOTAL', 20, 100);
    expect(r.original).toBeCloseTo(10, 2);
    expect(r.discounted).toBeCloseTo(8, 2);
    expect(r.lineDiscountAmount).toBeCloseTo(200, 2);
    expect(r.lineTotalAmount).toBeCloseTo(800, 2);
  });
});

// ============================================================
describe('computeLineDiscount — source=ASM（按页签小计代回公式重算）', () => {
  it('折扣率 20%，年用量 100 => discounted≈8.4, lineDiscountAmount≈160, lineTotalAmount≈840, discountBaseAmount≈8', () => {
    const item = makeItem();
    // ASM 打8折 => ASM*0.8=6.4，OTH=2，total=8.4
    const r = computeLineDiscount(item, mockExpansions, undefined, 'ASM', 20, 100);
    expect(r.discountBaseAmount).toBeCloseTo(8, 2);   // ASM 原始小计
    expect(r.discounted).toBeCloseTo(8.4, 2);          // 折后产品单价
    expect(r.lineDiscountAmount).toBeCloseTo(160, 2);  // (10-8.4)*100
    expect(r.lineTotalAmount).toBeCloseTo(840, 2);     // 8.4*100
  });

  it('折扣率 0% => lineDiscountAmount=0, discounted=original', () => {
    const item = makeItem();
    const r = computeLineDiscount(item, mockExpansions, undefined, 'ASM', 0, 100);
    expect(r.lineDiscountAmount).toBeCloseTo(0, 4);
    expect(r.discounted).toBeCloseTo(r.original, 4);
  });
});
