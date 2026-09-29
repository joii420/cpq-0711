import api from './api';
import type {
  CurrencyDTO,
  ExchangeRateDTO,
  ExchangeRateSimulationResult,
  ExchangeRateStrategyDTO,
} from '../types/currency';

const unwrap = <T>(response: any): T => (response?.data ?? response) as T;

export const currencyService = {
  list: (params?: { enabled?: boolean }) =>
    api.get('/currencies', { params }) as Promise<{ data: CurrencyDTO[] }>,
  create: (payload: Pick<CurrencyDTO, 'code' | 'name' | 'symbol' | 'sortOrder'>) =>
    api.post('/currencies', payload) as Promise<{ data: CurrencyDTO }>,
  update: (code: string, payload: Partial<Pick<CurrencyDTO, 'name' | 'symbol' | 'sortOrder' | 'enabled'>>) =>
    api.put(`/currencies/${encodeURIComponent(code)}`, payload) as Promise<{ data: CurrencyDTO }>,
  remove: (code: string) => api.delete(`/currencies/${encodeURIComponent(code)}`),
  rates: (code: string, params?: { fromDate?: string; toDate?: string; status?: string }) =>
    api.get(`/currencies/${encodeURIComponent(code)}/rates`, { params }) as Promise<{ data: { currency: string; items: ExchangeRateDTO[] } }>,
  addRate: (code: string, payload: Pick<ExchangeRateDTO, 'rate' | 'effectiveDate' | 'source'>) =>
    api.post(`/currencies/${encodeURIComponent(code)}/rates`, payload),
  importRates: (code: string, file: File) => {
    const formData = new FormData();
    formData.append('file', file);
    return api.post(`/currencies/${encodeURIComponent(code)}/rates/import`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 120000,
    });
  },
  getStrategy: async (customerNo: string) =>
    unwrap<ExchangeRateStrategyDTO | null>(await api.get(`/customers/${encodeURIComponent(customerNo)}/exchange-rate-strategy`)),
  saveStrategy: async (customerNo: string, payload: Omit<ExchangeRateStrategyDTO, 'customerNo' | 'id' | 'version' | 'updatedAt' | 'updatedByName'>) =>
    unwrap<ExchangeRateStrategyDTO>(await api.put(`/customers/${encodeURIComponent(customerNo)}/exchange-rate-strategy`, payload)),
  simulate: async (customerNo: string, payload: { currency: string; baseDate: string } & Omit<ExchangeRateStrategyDTO, 'customerNo' | 'id' | 'version' | 'updatedAt' | 'updatedByName'>) =>
    unwrap<ExchangeRateSimulationResult>(await api.post(`/customers/${encodeURIComponent(customerNo)}/exchange-rate-strategy/simulate`, payload)),
};

