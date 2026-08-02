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
  missingSide?: 'QUOTE' | 'COSTING';
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
