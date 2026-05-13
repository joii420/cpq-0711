import api from './api';
import type {
  ConfigureProductRequest,
  ConfigureProductResponse,
  LookupFingerprintRequest,
  LookupFingerprintResponse,
  SearchPartResult,
} from '../types/configure';

export const configureProductService = {
  async searchParts(q: string, size = 50): Promise<SearchPartResult[]> {
    const res = await api.get('/quotations/configure/search-parts', {
      params: { q, size },
    });
    return (res as SearchPartResult[]) ?? [];
  },

  async lookupFingerprint(req: LookupFingerprintRequest): Promise<LookupFingerprintResponse> {
    const res = await api.post('/quotations/configure/lookup-fingerprint', req);
    return res as LookupFingerprintResponse;
  },

  async configureProduct(
    quotationId: string,
    req: ConfigureProductRequest,
  ): Promise<ConfigureProductResponse> {
    const res = await api.post(`/quotations/${quotationId}/configure-product`, req);
    return res as ConfigureProductResponse;
  },
};
