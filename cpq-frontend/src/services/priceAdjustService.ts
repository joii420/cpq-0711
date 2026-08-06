import api from './api';
import type {
  PriceAdjustStrategyDTO,
  StrategySaveRequest,
  StrategySaveResponse,
  PageResult,
  MaterialRowDTO,
  MaterialsQueryParams,
  MaterialsSaveRequest,
  ElementsMatrixResponse,
  ElementsQueryParams,
  ElementsSaveRequest,
  StrategyLogDTO,
  TemplateSeriesDTO,
  ComparisonColumnsResponse,
  ComparisonColumnsSaveResponse,
  PriceAdjustColumnDef,
  GenerateVersionResponse,
  VersionDTO,
  VersionItemDTO,
  ReviewRowDTO,
  ReviewsQueryParams,
  ReviewDetailDTO,
  ImpactPreviewDTO,
  ApproveResponse,
  UpdateJobDTO,
  JobsQueryParams,
  UpdateJobItemDTO,
} from '../types/price-adjust';

/**
 * 客户价格调整策略与价格版本（task-0729）— 屏 1 + 屏 3/4/5 服务层。
 * 权威依据：dev-docs/task-0729-客户价格调整策略和价格版本/api.md §1（屏1）+ §2（屏3/4/5）。
 * 前缀 /api/cpq/price-adjust（api baseURL 已含 /api/cpq，此处只写 /price-adjust/...）。
 *
 * 响应为裸 DTO/裸数组，不解 {code,data} 信封（api.md §0.2：「沿用 /api/cpq/element-price/* 风格，
 * 不另造包装层」）——与 comparisonViewService.ts(task-0717) 的 {data:...} 风格不同，勿混淆。
 *
 * ⚠️ 后端接口尚未实现（backtask 并行开发中）：本文件按 api.md 契约先行编写，
 * 调用会 404/网络失败，属预期——不得据此反向修改契约（任务纪律）。
 */
const BASE = '/price-adjust';

export const priceAdjustService = {
  // ── §1.1 / §1.2 策略主体 ──

  async getStrategy(customerNo: string): Promise<PriceAdjustStrategyDTO> {
    return (await api.get(`${BASE}/strategies/${encodeURIComponent(customerNo)}`)) as unknown as PriceAdjustStrategyDTO;
  },

  async saveStrategy(customerNo: string, req: StrategySaveRequest): Promise<StrategySaveResponse> {
    return (await api.put(`${BASE}/strategies/${encodeURIComponent(customerNo)}`, req)) as unknown as StrategySaveResponse;
  },

  // ── §1.3 / §1.4 指定料号矩阵 ──

  async getMaterials(customerNo: string, params: MaterialsQueryParams): Promise<PageResult<MaterialRowDTO>> {
    return (await api.get(`${BASE}/strategies/${encodeURIComponent(customerNo)}/materials`, { params })) as unknown as PageResult<MaterialRowDTO>;
  },

  async saveMaterials(customerNo: string, req: MaterialsSaveRequest): Promise<void> {
    await api.put(`${BASE}/strategies/${encodeURIComponent(customerNo)}/materials`, req);
  },

  // ── §1.5 / §1.6 参与调价元素矩阵 ──

  async getElements(customerNo: string, params: ElementsQueryParams): Promise<ElementsMatrixResponse> {
    return (await api.get(`${BASE}/strategies/${encodeURIComponent(customerNo)}/elements`, {
      params: { includeDisabled: true, ...params },
    })) as unknown as ElementsMatrixResponse;
  },

  async saveElements(customerNo: string, req: ElementsSaveRequest): Promise<void> {
    await api.put(`${BASE}/strategies/${encodeURIComponent(customerNo)}/elements`, req);
  },

  // ── §1.7 变更历史 ──

  async getLogs(customerNo: string, params: { page: number; size: number }): Promise<PageResult<StrategyLogDTO>> {
    return (await api.get(`${BASE}/strategies/${encodeURIComponent(customerNo)}/logs`, { params })) as unknown as PageResult<StrategyLogDTO>;
  },

  // ── §1.8~§1.10 比对列配置 ──

  async getTemplateSeries(customerNo: string): Promise<TemplateSeriesDTO[]> {
    return (await api.get(`${BASE}/strategies/${encodeURIComponent(customerNo)}/template-series`)) as unknown as TemplateSeriesDTO[];
  },

  async getComparisonColumns(customerNo: string, templateSeriesId: string): Promise<ComparisonColumnsResponse> {
    return (await api.get(`${BASE}/comparison-columns`, {
      params: { customerNo, templateSeriesId },
    })) as unknown as ComparisonColumnsResponse;
  },

  async saveComparisonColumns(
    customerNo: string,
    templateSeriesId: string,
    columns: PriceAdjustColumnDef[],
  ): Promise<ComparisonColumnsSaveResponse> {
    return (await api.put(`${BASE}/comparison-columns`, {
      customerNo, templateSeriesId, columns,
    })) as unknown as ComparisonColumnsSaveResponse;
  },

  /**
   * 比对列配置区的 meta 数据源：模板系列 → 页签/可比对值目录。
   * 端点已于 2026-08-06 由后端补交（api.md §1.10a）：
   *   GET /price-adjust/template-series/{id}/comparison-view-meta
   *
   * 📌 为什么 DTO 形状是复用 task-0717 的 ComparisonMetaDTO（保留这段历史，别删）：
   * task-0717 原端点 GET /quotations/{id}/comparison-view/meta 是按 **quotationId** 取，
   * 本屏按 **templateSeriesId** 取，语义不同故不能复用同一 URL；但两者产出的
   * 「页签 → 可比对值」目录结构完全一致，因此 URL 另开、DTO 形状照搬，
   * LinkConfigDrawer 得以零改动复用。
   */
  async getComparisonMeta(templateSeriesId: string): Promise<{ quoteTabs: any[]; costingTabs: any[] }> {
    return (await api.get(`${BASE}/template-series/${encodeURIComponent(templateSeriesId)}/comparison-view-meta`)) as unknown as {
      quoteTabs: any[]; costingTabs: any[];
    };
  },

  // ── §1.11~§1.13 版本生成 / 轨迹 / 明细 ──

  async generateVersion(customerNo: string, confirmSupersede: boolean): Promise<GenerateVersionResponse> {
    return (await api.post(`${BASE}/versions/generate`, { customerNo, confirmSupersede })) as unknown as GenerateVersionResponse;
  },

  async getVersions(customerNo: string, params: { page: number; size: number }): Promise<PageResult<VersionDTO>> {
    return (await api.get(`${BASE}/versions`, { params: { customerNo, ...params } })) as unknown as PageResult<VersionDTO>;
  },

  async getVersionItems(versionId: string, params: { page: number; size: number }): Promise<PageResult<VersionItemDTO>> {
    return (await api.get(`${BASE}/versions/${encodeURIComponent(versionId)}/items`, { params })) as unknown as PageResult<VersionItemDTO>;
  },

  // ── §2.1 待办池（屏 3） ──

  async getReviews(params: ReviewsQueryParams): Promise<PageResult<ReviewRowDTO>> {
    return (await api.get(`${BASE}/reviews`, { params })) as unknown as PageResult<ReviewRowDTO>;
  },

  // ── §2.2 料号审核抽屉（屏 4） ──

  async getReviewDetail(reviewId: string): Promise<ReviewDetailDTO> {
    return (await api.get(`${BASE}/reviews/${encodeURIComponent(reviewId)}`)) as unknown as ReviewDetailDTO;
  },

  // ── §2.3 通过前影响面确认（屏 5，只读预览，无副作用） ──

  async getImpactPreview(reviewIds: string[]): Promise<ImpactPreviewDTO> {
    return (await api.post(`${BASE}/reviews/impact`, { reviewIds })) as unknown as ImpactPreviewDTO;
  },

  // ── §2.4 通过并升版 ──

  async approveReviews(reviewIds: string[], comment?: string): Promise<ApproveResponse> {
    return (await api.post(`${BASE}/reviews/approve`, { reviewIds, comment: comment ?? '' })) as unknown as ApproveResponse;
  },

  // ── §2.5 驳回（reason 必填） ──

  async rejectReviews(reviewIds: string[], reason: string): Promise<void> {
    await api.post(`${BASE}/reviews/reject`, { reviewIds, reason });
  },

  // ── §2.6 单条预算重试（budgetStatus=FAILED 时） ──

  async recomputeBudget(reviewId: string): Promise<void> {
    await api.post(`${BASE}/reviews/${encodeURIComponent(reviewId)}/recompute-budget`);
  },

  // ── §3.1~§3.3 更新任务（屏 6 + 常驻页） ──

  async getJobs(params: JobsQueryParams): Promise<PageResult<UpdateJobDTO>> {
    return (await api.get(`${BASE}/jobs`, { params })) as unknown as PageResult<UpdateJobDTO>;
  },

  async getJob(jobId: string): Promise<UpdateJobDTO> {
    return (await api.get(`${BASE}/jobs/${encodeURIComponent(jobId)}`)) as unknown as UpdateJobDTO;
  },

  async getJobItems(jobId: string, params: { page: number; size: number; status?: string }): Promise<PageResult<UpdateJobItemDTO>> {
    return (await api.get(`${BASE}/jobs/${encodeURIComponent(jobId)}/items`, { params })) as unknown as PageResult<UpdateJobItemDTO>;
  },

  // ── §3.4~§3.5 重试 ──

  async retryJob(jobId: string): Promise<void> {
    await api.post(`${BASE}/jobs/${encodeURIComponent(jobId)}/retry`);
  },

  async retryJobItem(itemId: string): Promise<void> {
    await api.post(`${BASE}/job-items/${encodeURIComponent(itemId)}/retry`);
  },
};

export default priceAdjustService;
