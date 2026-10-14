import { describe, it, expect } from 'vitest';
import { buildCrossTabRows, targetUnitFor } from './QuotationStep2';

describe('KPCS 与重量结算单位跨类型换算', () => {
  it('KPCS 行在 g 结算单位下保持原值，小计不套 legacy 0.001', () => {
    const comp: any = {
      componentId: 'ASSEMBLY',
      componentCode: 'ASSEMBLY',
      tabName: '组装加工费',
      componentType: 'NORMAL',
      targetUnit: 'g',
      fields: [
        { name: '加工费', field_type: 'INPUT_NUMBER', is_subtotal: true, is_amount: true, unit_source_field: '计价单位' },
        { name: '计价单位', field_type: 'INPUT_TEXT' },
      ],
      formulas: [],
      rows: [{ 加工费: '39.54034733', 计价单位: 'KPCS' }],
    };
    const result = buildCrossTabRows([comp], {}, undefined, () => undefined);
    expect(result.columnSumsByComp.ASSEMBLY?.加工费).toBe('39.54034733');
  });

  it('报价结算单位与核价基础/详细计价单位彼此独立', () => {
    const item: any = {
      settlementUnit: 'g',
      basicPricingUnit: 'kg',
      detailPricingUnit: 't',
      hfPartInfo: { basicPricingUnit: 'kg', detailPricingUnit: 't' },
    };
    expect(targetUnitFor(item, { dialect: 'QUOTE' } as any, 'QUOTE')).toBe('g');
    expect(targetUnitFor(item, { dialect: 'COST_BASIC' } as any, 'COSTING')).toBe('kg');
    expect(targetUnitFor(item, { dialect: 'COST_DETAIL' } as any, 'COSTING')).toBe('t');
  });
});
