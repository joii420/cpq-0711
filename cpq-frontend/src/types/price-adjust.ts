/**
 * 客户价格调整策略与价格版本（task-0729）— 前端类型定义。
 * 权威依据：dev-docs/task-0729-客户价格调整策略和价格版本/api.md §1（屏 1）+ §2（屏 3/4/5）。
 * 本文件覆盖屏 1（策略配置）+ 屏 3（待办池）+ 屏 4（审核抽屉）+ 屏 5（通过前影响面确认）；
 * 屏 6~8 的类型待后续屏交付时再补。
 */

export type CycleType = 'DAILY' | 'WEEKLY' | 'MONTHLY_DAY' | 'MONTHLY_NTH_WEEK';
export type MaterialScopeMode = 'ALL' | 'SPECIFIED';
/** 🔒 版本只有两态（§11.3.3）——不是原型早前的 4 态。 */
export type VersionStatus = 'PENDING' | 'SUPERSEDED';
/** 🔒 单元格两种空值语义必须分开渲染（§11.2.4）。 */
export type PriceCellState = 'NORMAL' | 'NOT_IN_LIST' | 'NO_PRICE';
export type TriggerType = 'SCHEDULED' | 'MANUAL';

export interface PageResult<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

// ───────────────────────── §1.1 / §1.2 策略主体 ─────────────────────────

export interface PriceAdjustStrategyDTO {
  exists: boolean;
  customerNo: string;
  enabled: boolean;
  cycleType: CycleType;
  cycleWeekday: number | null;
  cycleDayOfMonth: number | null;
  cycleNthWeek: number | null;
  executeTime: string; // HH:mm
  materialScopeMode: MaterialScopeMode;
  costDiffThreshold: number;
  latestVersionNo: string | null;
  pendingVersionNo: string | null;
  materialCount: number;
  elementCount: number;
  hasComparisonConfig: boolean;
  updatedAt: string | null;
  updatedBy: string | null;
}

export interface StrategySaveRequest {
  enabled: boolean;
  cycleType: CycleType;
  cycleWeekday?: number | null;
  cycleDayOfMonth?: number | null;
  cycleNthWeek?: number | null;
  executeTime: string;
  materialScopeMode: MaterialScopeMode;
  costDiffThreshold: number;
}

export interface BudgetRecomputeInfo {
  budgetRecomputeTriggered?: boolean;
  affectedReviewCount?: number;
}

export type StrategySaveResponse = PriceAdjustStrategyDTO & BudgetRecomputeInfo;

// ───────────────────────── §1.3 / §1.4 指定料号矩阵 ─────────────────────────

export interface MaterialRowDTO {
  materialNo: string;
  materialName: string | null;
  customerPartNo: string | null;
  customerMaterialName: string | null;
  selected: boolean;
}

export interface MaterialsQueryParams {
  page: number;
  size: number;
  customerPartNo?: string;
  customerMaterialName?: string;
  materialNo?: string;
  materialName?: string;
  selectedOnly?: boolean;
}

export interface MaterialsSaveRequest {
  materialNos: string[];
  confirmRemoval: boolean;
}

/** 409 REMOVAL_NEEDS_CONFIRM 响应体（api.md §1.4）。 */
export interface RemovalNeedsConfirmPayload {
  code: 'REMOVAL_NEEDS_CONFIRM';
  removedMaterialNos: string[];
  pendingReviewCount: number;
  unlockedQuotationCount: number;
}

// ───────────────────────── §1.5 / §1.6 参与调价元素矩阵 ─────────────────────────

export interface VersionColumnDTO {
  versionId: string;
  versionNo: string;
  status: VersionStatus;
  baseDate: string;
}

export interface ElementPriceCellDTO {
  unitPrice: number | null;
  changeRate: number | null;
  priceState: PriceCellState;
}

export interface ElementRowDTO {
  elementCode: string;
  elementName: string;
  elementNo?: string;
  /** 元素主表是否启用；false 时前端须标「已停用」且照常可见/可参与调价（禁止前端过滤）。 */
  elementEnabled: boolean;
  selected: boolean;
  /** 与 versionColumns 逐位对齐，长度相同。 */
  prices: ElementPriceCellDTO[];
}

export interface ElementsMatrixResponse {
  versionColumns: VersionColumnDTO[];
  content: ElementRowDTO[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ElementsQueryParams {
  page: number;
  size: number;
  keyword?: string;
  /** 默认 true —— 必须显式带上，禁止让后端默认值决定（§1.3 硬约束）。 */
  includeDisabled?: boolean;
}

export interface ElementsSaveRequest {
  elementCodes: string[];
  confirmUnselect: boolean;
}

/** 409 UNSELECT_NEEDS_CONFIRM 响应体（api.md §1.6）。 */
export interface UnselectNeedsConfirmPayload {
  code: 'UNSELECT_NEEDS_CONFIRM';
  removedElementCodes: string[];
  unlockedQuotationCount: number;
}

// ───────────────────────── §1.7 变更历史 ─────────────────────────

export type ChangeType = 'STRATEGY' | 'MATERIAL_SCOPE' | 'ELEMENT_LIST' | 'COMPARISON_COLUMN';

export interface StrategyLogDTO {
  id: string;
  changedAt: string;
  changedBy: string;
  changeType: ChangeType;
  summary: string;
  beforeSnapshot?: unknown;
  afterSnapshot?: unknown;
}

// ───────────────────────── §1.8~§1.10 比对列配置 ─────────────────────────

export interface TemplateSeriesDTO {
  templateSeriesId: string;
  seriesName: string;
  latestVersion: string;
  isDefault: boolean;
  templateCount: number;
  hasComparisonConfig: boolean;
  columnCount: number;
}

export type ColumnKind = 'PRODUCT_TOTAL' | 'TAB_PAIR';

/** 复用 task-0717 ColumnDef schema（同字段名），本任务额外要求 removable（api.md §1.9）。 */
export interface PriceAdjustColumnDef {
  id: string;
  kind: ColumnKind;
  sortOrder: number;
  threshold: number;
  quoteComponentId?: string;
  quoteMetric?: string;
  quoteLabel?: string;
  costingComponentId?: string;
  costingMetric?: string;
  costingLabel?: string;
  /** 默认「产品总价」列恒为 false，其余列恒为 true（§1.4 硬约束：默认列不可删）。 */
  removable: boolean;
}

export interface ComparisonColumnsResponse {
  configured: boolean;
  customerNo: string;
  templateSeriesId: string;
  columns: PriceAdjustColumnDef[];
}

export type ComparisonColumnsSaveResponse = BudgetRecomputeInfo;

// ───────────────────────── §1.11~§1.13 版本生成 / 轨迹 / 明细 ─────────────────────────

export interface GenerateVersionResponse {
  versionId: string;
  versionNo: string;
  baseDate: string;
  itemCount: number;
  budgetJobId?: string;
  budgetStatus?: string;
}

/** 400 STRATEGY_NO_ELEMENTS（api.md §0.3）。 */
export interface StrategyNoElementsPayload {
  code: 'STRATEGY_NO_ELEMENTS';
  message?: string;
}

/** 409 PENDING_VERSION_EXISTS（api.md §1.11）。 */
export interface PendingVersionExistsPayload {
  code: 'PENDING_VERSION_EXISTS';
  pendingVersionNo: string;
  pendingReviewCount: number;
  approvedReviewCount: number;
}

export interface VersionProgressDTO {
  total: number;
  approved: number;
  rejected: number;
  pending: number;
  budgeting: number;
}

export interface VersionDTO {
  versionId: string;
  versionNo: string;
  baseDate: string;
  status: VersionStatus;
  triggerType: TriggerType;
  createdAt: string;
  createdBy: string;
  /** 不落库，服务端实时派生（§11.3.3(2)）。 */
  progress: VersionProgressDTO;
  itemCount: number;
}

export interface VersionItemDTO {
  elementCode: string;
  elementName: string;
  currentPrice: number | null;
  previousPrice: number | null;
  changeRate: number | null;
  currency: string;
  priceUnit: string;
  noPrice: boolean;
  inheritedFromPrevious: boolean;
}

// ═════════════════════════ §2 审核（屏 3 / 屏 4 / 屏 5） ═════════════════════════

export type BudgetStatus = 'QUEUED' | 'COMPUTING' | 'READY' | 'FAILED';
export type ReviewStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'VOIDED';

// ───────────────────────── §2.1 待办池列表 ─────────────────────────

export interface ReviewRowDTO {
  reviewId: string;
  customerNo: string;
  customerName: string;
  materialNo: string;
  materialName: string;
  currentVersionNo: string | null;
  targetVersionNo: string;
  budgetStatus: BudgetStatus;
  reviewStatus: ReviewStatus;
  basisQuotationNo: string | null;
  basisQuotationDate: string | null;
  quoteCostCurrent: number | null;
  quoteCostAdjusted: number | null;
  costingCost: number | null;
  diffCurrent: number | null;
  diffAdjusted: number | null;
  /** 该料号所属模板系列的比对列总数 */
  columnCount: number;
  /** RED + MISSING 都计入 */
  breachedCount: number;
  amberCount: number;
  /** 计入 breachedCount，但单独暴露供「⚪K」显示 */
  missingCount: number;
  /** 不计入 breachedCount */
  staleCount: number;
  /** 🔒 服务端权威：整行是否标红，前端不得自行按产品总价重算 */
  rowRed: boolean;
}

export interface ReviewsQueryParams {
  page: number;
  size: number;
  customerNo?: string;
  /** 默认 PENDING */
  status?: ReviewStatus;
  breachedOnly?: boolean;
  keyword?: string;
  sort?: string;
}

// ───────────────────────── §2.2 料号审核抽屉（屏 4） ─────────────────────────

export interface ElementChangeDTO {
  elementCode: string;
  elementName: string;
  matchedRule: string;
  previousPrice: number | null;
  currentPrice: number | null;
  changeRate: number | null;
  usageQty: number | null;
  unitPriceImpact: number | null;
  noPrice: boolean;
  inheritedFromPrevious: boolean;
}

export type ComparisonCellStatus = 'NORMAL' | 'RED' | 'AMBER' | 'MISSING' | 'STALE';

/**
 * status=MISSING 时的缺失侧，与后端 `ComparisonColumnEvaluator` 三态严格对齐：
 * 两侧都取不到值 → `BOTH`，否则按缺的那一侧 `QUOTE` / `COSTING`。
 *
 * 🔒 消费方必须用**完整映射/穷举分支**（如 `Record<ComparisonMissingSide, string>`），
 *    禁止 `x === 'QUOTE' ? A : B` 这类二元判断 —— 那会让新枚举值静默落进 else
 *    （2026-08-05 实修：`BOTH` 曾被显示成「核价侧」，把业务排查方向带偏）。
 *    用映射表时，后端再加枚举值会在这里被 tsc 直接报出来。
 */
export type ComparisonMissingSide = 'QUOTE' | 'COSTING' | 'BOTH';

export interface ComparisonColumnResultDTO {
  columnId: string;
  label: string;
  threshold: number;
  sortOrder: number;
  quoteCurrent?: number | null;
  quoteAdjusted?: number | null;
  costingCurrent?: number | null;
  costingAdjusted?: number | null;
  diffCurrent?: number | null;
  diffAdjusted?: number | null;
  status: ComparisonCellStatus;
  /** status=MISSING 时标注缺失侧 */
  missingSide?: ComparisonMissingSide;
}

export interface ReviewQuotationDTO {
  quotationId: string;
  quotationNo: string;
  createdAt: string;
  status: string;
  /** 唯一一张判断依据单 */
  isBasis: boolean;
  quoteSubtotalCurrent: number | null;
  quoteSubtotalAdjusted: number | null;
  /**
   * repair-0807 FR-5 新增：该行是否跑过试算。
   * false → 前端渲染「未试算」；true 且值为 null → 渲染「—」（试算跑了但拿不到值，属异常态）。
   * 🔒 判据必须是这个显式布尔，不能用 quoteSubtotalAdjusted == null 顶替——那会把
   * "试算失败"也说成"未试算"，混淆两种完全不同的状态（api.md §1.2）。
   */
  adjustedComputed: boolean;
  comparisonViewUrl: string;
}

export interface ReviewDetailDTO {
  reviewId: string;
  customerNo: string;
  materialNo: string;
  materialName: string;
  currentVersionNo: string | null;
  targetVersionNo: string;
  budgetStatus: BudgetStatus;
  reviewStatus: ReviewStatus;

  // 一、为什么变
  elementChanges: ElementChangeDTO[];
  /** 须与「调整后报价 − 现报价」对得上（财务自检位） */
  elementImpactTotal: number;

  // 二、能不能接受
  templateSeriesId: string;
  templateSeriesName: string;
  comparisonColumns: ComparisonColumnResultDTO[];

  // 三、下钻
  quotations: ReviewQuotationDTO[];
}

// ───────────────────────── §2.3 通过前影响面确认（屏 5） ─────────────────────────

export interface ImpactVersionPathDTO {
  materialNo: string;
  from: string | null;
  to: string;
}

export interface ImpactBreachedMaterialDTO {
  materialNo: string;
  breachedCount: number;
}

export interface ImpactPreviewDTO {
  materialCount: number;
  versionPaths: ImpactVersionPathDTO[];
  quotationCount: number;
  /** 🔒 只统计 5 个可更新状态（活单白名单，E14-2） */
  byStatus: Record<string, number>;
  breachedMaterials: ImpactBreachedMaterialDTO[];
  excludedQuotationCount: number;
  /** 🔒 必须显式列出被排除的单（SENT/ACCEPTED/EXPIRED/CANCELLED） */
  excludedByStatus: Record<string, number>;
}

// ───────────────────────── §2.4~§2.6 通过 / 驳回 / 重算预算 ─────────────────────────

export interface ApproveResponse {
  jobId: string;
  materialCount: number;
  quotationCount: number;
  itemCount: number;
}

/** 409 REVIEW_BUDGET_NOT_READY / REVIEW_STATUS_CHANGED（api.md §2.4）。 */
export interface ReviewBatchRejectPayload {
  code: 'REVIEW_BUDGET_NOT_READY' | 'REVIEW_STATUS_CHANGED';
  message?: string;
  /** 不合格项列表，字段名未在 api.md 逐字给出，防御性可选 */
  invalidReviewIds?: string[];
}

// ═════════════════════════ §3 更新任务（屏 6 + 常驻页） ═════════════════════════

export type JobStatus = 'RUNNING' | 'SUCCESS' | 'PARTIAL' | 'FAILED' | 'STALE';
export type JobItemStatus =
  | 'WAITING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CONFLICT' | 'STALE'
  | 'SKIPPED'; // repair-0807 FR-4：该单未被更新，且重试无意义（无价格承载组件 / 补建结构失败）

export interface UpdateJobDTO {
  jobId: string;
  customerNo: string;
  versionNo: string;
  triggeredBy: string;
  triggeredAt: string;
  status: JobStatus;
  total: number;
  success: number;
  failed: number;
  conflict: number;
  stale: number;
  /**
   * repair-0807 FR-4 新增：该批次里"未被更新"的单数。
   * ⚠️ 命名沿用本 DTO 既有字段风格（total/success/failed/conflict/stale 均无 Count 后缀），
   * 未采用 api.md §3.1 JSON 示例里的 `skippedCount`——后端 JobDTO.java 现有同批字段
   * （total/success/…）已实证偏离 api.md 该处 JSON 示例的命名（无 Count 后缀），
   * 判断后端会延续同一约定新增 `skipped` 而非 `skippedCount`。若后端最终字段名不同，
   * 因 §3 边界约定"字段缺失时汇总区不显示该项"，不会崩，但需与后端对齐后修正此处。
   */
  skipped: number;
  finishedAt?: string | null;
  notified?: boolean;
}

export interface JobsQueryParams {
  page: number;
  size: number;
  status?: JobStatus;
  customerNo?: string;
}

export interface UpdateJobItemDTO {
  itemId: string;
  quotationId: string;
  quotationNo: string;
  materialNo: string;
  lineItemId: string;
  status: JobItemStatus;
  errorCode?: string | null;
  errorMessage?: string | null;
  /** L3 升版口径守卫专用（errorCode=SUBTOTAL_MISMATCH 时有值） */
  diffValue?: number | null;
  retryCount: number;
  updatedAt: string;
}

// ═════════════════════════ §4 报价单侧（屏 7）· 销售只读可见 ═════════════════════════

export interface PriceRevisionDTO {
  revisionId: string;
  revisionNo: string;
  /** 初版（isInitial=true）时为 null（D6：initial 不挂 based_version_id） */
  basedVersionNo: string | null;
  isInitial: boolean;
  sealed: boolean;
  firstEffectiveAt: string;
  lastUpdatedAt: string;
  /** 同一 V 版内多次升版累积（裁决30：同期合并进同一个 R 版本） */
  upgradedMaterialNos: string[];
  quoteTotalAmount: number;
}

export type MaterialVersionState = 'UPGRADED' | 'REJECTED' | 'NOT_UPDATED' | 'NOT_PARTICIPATING';

export interface MaterialVersionRowDTO {
  materialNo: string;
  materialName: string;
  currentVersionNo: string | null;
  state: MaterialVersionState;
  /** state=NOT_UPDATED 时可能带出，指向在途/失败的 job_item（仅供诊断，不直接展示版本号） */
  pendingJobItemId?: string | null;
}

export interface PriceRevisionsResponse {
  revisions: PriceRevisionDTO[];
  materialVersions: MaterialVersionRowDTO[];
}

export interface RevisionPreviewLineItemDTO {
  lineItemId: string;
  materialNo: string;
  /** 🔒 双侧都来自快照，禁止核价侧读当前值（验收 #55） */
  quoteCardValues: Record<string, unknown>;
  costingCardValues: Record<string, unknown>;
  snapshotRows?: Record<string, unknown>;
}

export interface RevisionPreviewResponse {
  revisionNo: string;
  readonly: true;
  lineItems: RevisionPreviewLineItemDTO[];
  quoteTotalAmount: number;
}
