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
  /**
   * `GET /quotations/configure/search-parts`（task-260910 · api.md §2.1，AC-5 / AC-7 / AC-8 / AC-9）。
   *
   * 🆕 **`customerNo` 必填**（`customer.code`）：后端已按客户维度隔离，缺参直接 400
   *    `CUSTOMER_NO_REQUIRED`（🚫 不许静默跨客户查，D-2）。
   *    ⇒ 声明成**必填位置参数**就是为了让漏传在 `tsc` 阶段就红，而不是运行时 400。
   *    调用方拿不到客户号时**不要调本方法**，渲染「请先为报价单选择客户」空态。
   * 🔄 返回的材质字段已由单值改多值 `materials[]`；**空数组是正常状态**（外购件 / 组合父料号）。
   */
  async searchParts(customerNo: string, q: string, size = 50): Promise<SearchPartResult[]> {
    const res = await api.get('/quotations/configure/search-parts', {
      params: { customerNo, q, size },
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
   * `GET /quotations/configure/outsourced-parts`（task-260910 · api.md §2.2，AC-6）。
   *
   * 🆕 **`customerNo` 必填**：数据源已从 `v_compat_material_master` 改为
   *    `ds_quote_material WHERE customer_no = :customerNo AND material_type = '外购件'`。
   *    🔑 不带客户过滤会**列表出双份** —— 实测 5 个外购件料号同时挂 `CUST-0001` 与
   *    `CUST-0004`（共 10 行）；兼容视图原先靠 `DISTINCT ON` 收敛，直连新表等于绕过那次修复。
   *    ⇒ 同上：声明成**必填字段**，漏传在 `tsc` 阶段就红。
   *
   * ⚠️ **返回 0 条是正常业务状态**，不是错误 —— 调用方必须渲染空态而不是「加载中…」永久占位（AP-31 族）。
   * ⚠️ **分页本期仍不接**（api.md §2.2）：调用方 `page` 恒 1 / `size` 恒 20 / `pagination={false}`，
   *    候选超 20 个时选不到第 21 个，现被数据量掩盖（实测 5 条），已登记 BACKLOG。
   * 形状兜底只做形状，不做语义：字段缺省给 `{ total: 0, items: [] }`。
   */
  async listOutsourcedParts(
    params: { customerNo: string; keyword?: string; page?: number; size?: number },
  ): Promise<OutsourcedPartPage> {
    const res = await api.get('/quotations/configure/outsourced-parts', {
      params: {
        customerNo: params.customerNo,
        keyword: params.keyword || undefined,
        page: params.page ?? 1,
        size: params.size ?? 20,
      },
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
