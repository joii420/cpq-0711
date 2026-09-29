import { beforeEach, describe, expect, it, vi } from 'vitest';
import api from './api';
import { currencyService } from './currencyService';

vi.mock('./api', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

describe('currencyService REST contract', () => {
  beforeEach(() => {
    vi.mocked(api.get).mockReset();
    vi.mocked(api.post).mockReset();
    vi.mocked(api.put).mockReset();
    vi.mocked(api.delete).mockReset();
    vi.mocked(api.get).mockResolvedValue({ code: 200, data: [] } as any);
    vi.mocked(api.post).mockResolvedValue({ code: 200, data: {} } as any);
    vi.mocked(api.put).mockResolvedValue({ code: 200, data: {} } as any);
  });

  it('keeps currency paths encoded and sends the declared create/update payload', async () => {
    await currencyService.create({ code: 'USD', name: '美元', symbol: '$', sortOrder: 20 });
    expect(api.post).toHaveBeenCalledWith('/currencies', {
      code: 'USD', name: '美元', symbol: '$', sortOrder: 20,
    });

    await currencyService.update('X/Y', { name: 'test', enabled: false });
    expect(api.put).toHaveBeenCalledWith('/currencies/X%2FY', { name: 'test', enabled: false });
    await currencyService.remove('X/Y');
    expect(api.delete).toHaveBeenCalledWith('/currencies/X%2FY');
  });

  it('preserves exchange rates as decimal strings and carries date/source', async () => {
    const rate = '7.200000000000';
    await currencyService.addRate('USD', { rate, effectiveDate: '2026-09-27', source: 'FX260927_TEST' });
    expect(api.post).toHaveBeenCalledWith('/currencies/USD/rates', {
      rate, effectiveDate: '2026-09-27', source: 'FX260927_TEST',
    });
  });

  it('supports rate editing, matrix queries and template downloads', async () => {
    await currencyService.updateRate('USD', 'rate-1', { rate: '7.300000000000', effectiveDate: '2026-09-27', source: 'MANUAL' });
    expect(api.put).toHaveBeenCalledWith('/currencies/USD/rates/rate-1', {
      rate: '7.300000000000', effectiveDate: '2026-09-27', source: 'MANUAL',
    });
    await currencyService.matrixRates('USD', { fromDate: '2026-09-01', toDate: '2026-09-30' });
    expect(api.get).toHaveBeenCalledWith('/currencies/USD/rates/matrix', { params: { fromDate: '2026-09-01', toDate: '2026-09-30' } });
    await currencyService.downloadRateTemplate('USD');
    expect(api.get).toHaveBeenCalledWith('/currencies/USD/rates/import-template', { responseType: 'blob' });
  });

  it('builds a single-currency multipart import request', async () => {
    const file = new File(['xlsx'], 'rates.xlsx', { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
    await currencyService.importRates('USD', file);
    const [path, body, config] = vi.mocked(api.post).mock.calls[0] as any;
    expect(path).toBe('/currencies/USD/rates/import');
    expect(body).toBeInstanceOf(FormData);
    expect((body as FormData).get('file')).toBe(file);
    expect(config).toMatchObject({ headers: { 'Content-Type': 'multipart/form-data' }, timeout: 120000 });
  });

  it('sends the date-rule strategy without price-source/factor/premium fields', async () => {
    await currencyService.saveStrategy('CUST-260927', {
      method: 'MONTH_DAY', dayOfMonth: 31, windowNum: null, windowUnit: null, specificDate: null,
    });
    const payload = vi.mocked(api.put).mock.calls[0][1] as Record<string, unknown>;
    expect(api.put).toHaveBeenCalledWith('/customers/CUST-260927/exchange-rate-strategy', payload);
    expect(payload).toMatchObject({ method: 'MONTH_DAY', dayOfMonth: 31 });
    expect(payload).not.toHaveProperty('priceSource');
    expect(payload).not.toHaveProperty('factor');
    expect(payload).not.toHaveProperty('premium');

    await currencyService.simulate('CUST-260927', {
      currency: 'USD', baseDate: '2026-02-28', method: 'MONTH_DAY', dayOfMonth: 31,
    });
    expect(api.post).toHaveBeenLastCalledWith('/customers/CUST-260927/exchange-rate-strategy/simulate', {
      currency: 'USD', baseDate: '2026-02-28', method: 'MONTH_DAY', dayOfMonth: 31,
    });
  });
});
