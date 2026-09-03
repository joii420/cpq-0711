import api from './api';
import type {
  CheckProductNoResponse,
  ConfigureProductRequest,
  ConfigureProductResponse,
  LookupFingerprintRequest,
  LookupFingerprintResponse,
  OutsourcedPartPage,
  SearchPartResult,
} from '../types/configure';

export const configureProductService = {
  async searchParts(q: string, size = 50): Promise<SearchPartResult[]> {
    const res = await api.get('/quotations/configure/search-parts', {
      params: { q, size },
    });
    return (res as unknown as SearchPartResult[]) ?? [];
  },

  /**
   * `GET /quotations/configure/check-product-no`（task-260902 · api.md §2.1，AC-1 / AC-2）。
   *
   * 🚨 **不阻塞输入**：调用方 debounce 400ms 后调，只驱动提示与「下一步」禁用态，
   *    绝不 await 完再更新输入框的 value。
   * 🚨 网络/后端异常时**不当成"已占用"**：查不到就返回 `{taken:false}`，
   *    否则一次 500 会把用户永久挡在第一步（前端只是体验层，后端仍会硬拦）。
   */
  async checkProductNo(customerNo: string, productNo: string): Promise<CheckProductNoResponse> {
    const res = await api.get('/quotations/configure/check-product-no', {
      params: { customerNo, productNo },
    });
    return (res as unknown as CheckProductNoResponse) ?? { taken: false };
  },

  /**
   * `GET /quotations/configure/outsourced-parts`（task-260902 · api.md §2.2，AC-5 / AC-16）。
   *
   * ⚠️ 实测该条件当前只命中 1 条，**返回 0 条是正常业务状态**，不是错误 —— 调用方必须渲染
   *    空态而不是「加载中…」永久占位（AP-31 族）。
   * 形状兜底只做形状，不做语义：字段缺省给 `{ total: 0, items: [] }`。
   */
  async listOutsourcedParts(
    params: { keyword?: string; page?: number; size?: number } = {},
  ): Promise<OutsourcedPartPage> {
    const res = await api.get('/quotations/configure/outsourced-parts', {
      params: { keyword: params.keyword || undefined, page: params.page ?? 1, size: params.size ?? 20 },
    });
    const page = res as unknown as OutsourcedPartPage | null;
    return { total: page?.total ?? 0, items: page?.items ?? [] };
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
