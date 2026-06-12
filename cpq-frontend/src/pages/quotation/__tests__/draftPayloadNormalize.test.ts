import { describe, it, expect } from 'vitest';
import { normalizeDraftPayloadNumbers } from '../QuotationWizard';

describe('normalizeDraftPayloadNumbers — 浮点尾差规范化', () => {
  it('subtotal 浮点尾差规范化后相等', () => {
    const a = normalizeDraftPayloadNumbers({ lineItems: [{ subtotal: 25.40999999998, rowData: [] }] });
    const b = normalizeDraftPayloadNumbers({ lineItems: [{ subtotal: 25.41000000001, rowData: [] }] });
    expect(JSON.stringify(a)).toBe(JSON.stringify(b));
  });
  it('rowData 内数值字段同样规范化', () => {
    const a = normalizeDraftPayloadNumbers({ lineItems: [{ subtotal: 0, rowData: [{ 金额: 1.1500000001 }] }] });
    const b = normalizeDraftPayloadNumbers({ lineItems: [{ subtotal: 0, rowData: [{ 金额: 1.1499999999 }] }] });
    expect(JSON.stringify(a)).toBe(JSON.stringify(b));
  });
  it('非数值字段原样保留', () => {
    const r = normalizeDraftPayloadNumbers({ name: '料8', lineItems: [{ subtotal: 1, rowData: [{ 料件: '料8' }] }] });
    expect(r.name).toBe('料8');
    expect(r.lineItems[0].rowData[0]['料件']).toBe('料8');
  });
});
