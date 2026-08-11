import { describe, expect, it } from 'vitest';
import { normalizeDraftPayloadDecimals } from '../QuotationWizard';

describe('normalizeDraftPayloadDecimals', () => {
  it('normalizes known precision fields as decimal strings without losing the 12th place', () => {
    const result = normalizeDraftPayloadDecimals({
      lineItems: [{
        subtotal: '98765431.123456789012',
        lineUnitPrice: '1.230000000000',
        rowData: '[{"unit_price":"0.123456789012"}]',
      }],
    });

    expect(result.lineItems[0].subtotal).toBe('98765431.123456789012');
    expect(result.lineItems[0].lineUnitPrice).toBe('1.23');
    expect(result.lineItems[0].rowData).toBe('[{"unit_price":"0.123456789012"}]');
  });

  it('preserves structural integers and non-numeric fields', () => {
    const result = normalizeDraftPayloadDecimals({
      page: 2,
      sortOrder: 3,
      lineItems: [{ subtotal: '1', rowData: '[]', partNo: 'P-1' }],
    });

    expect(result.page).toBe(2);
    expect(result.sortOrder).toBe(3);
    expect(result.lineItems[0].partNo).toBe('P-1');
  });

  it('rejects JS numbers at precision-field boundaries instead of silently rounding them', () => {
    const result = normalizeDraftPayloadDecimals({
      lineItems: [{
        subtotal: 0.1 + 0.2,
        annualVolume: 1000,
        lineTotalAmount: 98765431.12345679,
      }],
    });

    expect(result.lineItems[0].subtotal).toBeNull();
    expect(result.lineItems[0].annualVolume).toBe(1000);
    expect(result.lineItems[0].lineTotalAmount).toBeNull();
  });

  it('converts annualVolume to the structural JSON integer contract', () => {
    const result = normalizeDraftPayloadDecimals({
      lineItems: [{ annualVolume: '800000' }],
    }) as any;

    expect(result.lineItems[0].annualVolume).toBe(800000);
    expect(typeof result.lineItems[0].annualVolume).toBe('number');
  });

  it('rejects fractional or unsafe annualVolume before SaveDraft', () => {
    expect(() => normalizeDraftPayloadDecimals({
      lineItems: [{ annualVolume: '1.5' }],
    })).toThrow(/annualVolume must be an integer/);

    expect(() => normalizeDraftPayloadDecimals({
      lineItems: [{ annualVolume: '9007199254740992' }],
    })).toThrow(/annualVolume must be a safe integer/);
  });

  it('preserves null and empty semantics for precision fields', () => {
    const result = normalizeDraftPayloadDecimals({
      subtotal: null,
      originalAmount: '',
      totalAmount: '-0.0000',
    });

    expect(result.subtotal).toBeNull();
    expect(result.originalAmount).toBe('');
    expect(result.totalAmount).toBe('0');
  });
});
