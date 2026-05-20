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
    return (res as unknown as SearchPartResult[]) ?? [];
  },

  async lookupFingerprint(req: LookupFingerprintRequest): Promise<LookupFingerprintResponse> {
    // hotfix: 后端 ConfigureProductResource @Path 从 /api/cpq/quotations 改成
    // /api/cpq/configure-product 避开和 QuotationResource 同父路径 RestEasy 匹配冲突
    const res = await api.post('/configure-product/lookup-fingerprint', req);
    return res as unknown as LookupFingerprintResponse;
  },

  async configureProduct(
    quotationId: string,
    req: ConfigureProductRequest,
  ): Promise<ConfigureProductResponse> {
    const res = await api.post(`/configure-product/quotations/${quotationId}`, req);
    return res as unknown as ConfigureProductResponse;
  },
};
