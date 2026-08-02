import api from './api';
import type { PriceRevisionsResponse, RevisionPreviewResponse } from '../types/price-adjust';

/**
 * 报价单价格版本（task-0729 屏 7）— 服务层。
 * 权威依据：dev-docs/task-0729-客户价格调整策略和价格版本/api.md §4。
 * 路径挂在既有 /quotations 命名空间下（不是 /price-adjust 前缀），沿用 §0.2 同款
 * 裸 DTO 风格（不解 {code,data} 信封）。全角色只读端点：销售可见但只读（api.md §0.1）。
 *
 * ⚠️ 后端接口尚未实现：本文件按 api.md 契约先行编写，调用会 404/网络失败，属预期。
 */
export const priceRevisionService = {
  async getPriceRevisions(quotationId: string): Promise<PriceRevisionsResponse> {
    return (await api.get(`/quotations/${encodeURIComponent(quotationId)}/price-revisions`)) as unknown as PriceRevisionsResponse;
  },

  async getPriceRevisionPreview(quotationId: string, revisionId: string): Promise<RevisionPreviewResponse> {
    return (await api.get(
      `/quotations/${encodeURIComponent(quotationId)}/price-revisions/${encodeURIComponent(revisionId)}/preview`,
    )) as unknown as RevisionPreviewResponse;
  },
};

export default priceRevisionService;
