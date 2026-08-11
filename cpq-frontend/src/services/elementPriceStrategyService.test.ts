import { beforeEach, describe, expect, it, vi } from 'vitest';
import api from './api';
import { elementPriceStrategyService } from './elementPriceStrategyService';

vi.mock('./api', () => ({
  default: {
    post: vi.fn(),
    put: vi.fn(),
  },
}));

const exact = '98765431.123456789012';

describe('elementPriceStrategyService decimal request contract', () => {
  beforeEach(() => {
    vi.mocked(api.post).mockReset();
    vi.mocked(api.put).mockReset();
    vi.mocked(api.post).mockResolvedValue({});
    vi.mocked(api.put).mockResolvedValue({});
  });

  it('keeps price as an exact decimal string for create and update', async () => {
    await elementPriceStrategyService.createPrice({
      elementCode: 'Cu',
      sourceId: 'source-1',
      priceDate: '2026-08-10',
      price: exact,
      currency: 'CNY',
      priceUnit: 'kg',
    });
    await elementPriceStrategyService.updatePrice('price-1', {
      price: exact,
      currency: 'CNY',
      priceUnit: 'kg',
    });

    expect(vi.mocked(api.post).mock.calls[0][1]).toMatchObject({ price: exact });
    expect(vi.mocked(api.put).mock.calls[0][1]).toMatchObject({ price: exact });
    expect(typeof (vi.mocked(api.post).mock.calls[0][1] as { price: unknown }).price).toBe('string');
  });

  it('keeps strategy factor and premium as exact decimal strings', async () => {
    await elementPriceStrategyService.saveDefaultStrategy({
      customerNo: 'C-001',
      sourceId: 'source-1',
      method: 'AVG',
      windowNum: 30,
      windowUnit: 'DAY',
      factor: exact,
      premium: '-0.000000000001',
    });

    expect(vi.mocked(api.put).mock.calls[0][1]).toMatchObject({
      factor: exact,
      premium: '-0.000000000001',
    });
  });

  it('rejects precision numbers before any request is sent', async () => {
    await expect(elementPriceStrategyService.createPrice({
      elementCode: 'Cu',
      sourceId: 'source-1',
      priceDate: '2026-08-10',
      price: 123.45,
      currency: 'CNY',
      priceUnit: 'kg',
    } as any)).rejects.toThrow(/price must be a decimal string/);

    await expect(elementPriceStrategyService.createException({
      customerNo: 'C-001',
      sourceId: 'source-1',
      method: 'LATEST',
      elementCode: 'Cu',
      factor: 1.25,
      premium: 0,
    } as any)).rejects.toThrow(/factor must be a decimal string/);

    expect(api.post).not.toHaveBeenCalled();
    expect(api.put).not.toHaveBeenCalled();
  });
});
