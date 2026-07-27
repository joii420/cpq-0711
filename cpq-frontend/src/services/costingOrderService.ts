import api from './api';

export interface CostingOrderListItem {
  costingOrderId: string;
  costingOrderNumber: string;
  quotationId: string;
  quotationNumber: string;
  customerName: string;
  currency: string;
  submittedByName: string;
  status: string;
  rejectReason?: string;
  createdAt: string;
  updatedAt?: string;
}

/**
 * task-0713：核价单版本 override 持久化结构（api.md §4）。
 * 唯一键 (costingOrderId, componentId, partNo)，costingOrderId 由外层 CostingOrderDetail 隐含。
 */
export interface CostingOrderVersionOverride {
  componentId: string;
  partNo: string;
  viewVersion: string;
}

/**
 * task-0713：costing_order.costing_render 缓存里单个 lineItem 的核价侧渲染结果（api.md §1）。
 * costingCardValues/costingExcelValues 与 LineItemSnapshotValues 同形状，
 * 但来源是"已应用本单 override"的核价专属缓存，不是 frozen_dto 里的报价侧字段。
 * 后端可能以 JSON 字符串或已解析对象两种形态返回（未定死），消费方需按 quotationService
 * 里既有的 parseJson 兼容写法处理。
 */
export interface CostingRenderEntry {
  costingCardValues?: any;
  costingExcelValues?: any;
}

export interface CostingOrderDetail {
  costingOrderId: string;
  quotationId: string;
  costingOrderNumber: string;
  status: string;
  rejectReason?: string;
  totalAmount?: number;
  frozenDto?: string;
  createdAt: string;
  reviewedAt?: string;
  /** task-0713（D1）：核价侧渲染缓存，keyed by lineItemId。报价侧仍读 frozenDto，两者物理隔离。 */
  costingRender?: Record<string, CostingRenderEntry>;
  /** task-0713（D1）：核价侧单据总价 = Σ核价成本 subtotal，不含 Step3 折扣。与 totalAmount（报价总额）是两列两值，不可混用。 */
  costingTotalAmount?: number;
  /** task-0713（api.md §4）：本单当前所有版本 override，标记"已切版本"用 */
  versionOverrides?: CostingOrderVersionOverride[];
  /** task-0713：= status==='PENDING' && role∈{PRICING_MANAGER,SYSTEM_ADMIN}，决定是否显示版本切换控件 */
  editable?: boolean;
}

/** task-0713（api.md §2）：GET version-options 响应，options 倒序，currentVersion 供高亮。 */
export interface VersionOptionsResult {
  componentId: string;
  partNo: string;
  currentVersion: string | null;
  /** view_version 候选列表，后端保证倒序 */
  options: string[];
}

/** task-0713（api.md §3）：POST version-switch 响应，前端只用这些字段做增量刷新，不整单重查（守 AP-31）。 */
export interface VersionSwitchResult {
  lineItemId: string;
  /** 该卡片重算后的核价卡片值（行内含 view_version），形状同 CostingRenderEntry.costingCardValues */
  costingCardValues: any;
  /** 若受影响才带；命名沿用 api.md 原文（"columns"字样疑与"该卡片核价 Excel 值"语义对应，
   * 后端落地后需与实际返回结构核对，见前端 RECORD 备注） */
  costingExcelColumns?: any;
  /** 更新后的单据总价（Σ核价成本 subtotal，不含 Step3 折扣） */
  costingTotalAmount: number;
  /** 实际触发重查/重算的页签 componentId 列表，便于前端定向刷新提示 */
  affectedTabs: string[];
}

/**
 * task-0721（api.md §1.1）：核价通过 preview 汇总——将升版 N 组 / 新增 X / 删除 Y / 改值 Z。
 * repair-0727（api.md §1.1）：新增 affectedProducts——涉及产品数（groups 里 productNo 去重，null 不计）。
 */
export interface CostingApprovePreviewSummary {
  versionedGroups: number;
  addedRows: number;
  deletedRows: number;
  changedRows: number;
  /** repair-0727 新增 */
  affectedProducts: number;
}

/** repair-0727（api.md §1.2）：行级列差异，取代旧 Record<col,[old,new]> 结构，带中文列名。 */
export interface CostingApprovePreviewChange {
  column: string;
  label: string;
  oldValue: string | null;
  newValue: string | null;
}

/** repair-0727（api.md §1.2）：ADD/DELETE 行的列值，带中文列名。 */
export interface CostingApprovePreviewValue {
  column: string;
  label: string;
  value: string | null;
}

/**
 * task-0721（api.md §1.1）：一行变更明细。ADD 无 __v6_id。
 * repair-0727（api.md §1.2）：changes/values 由对象改为数组；新增 rowLabel（该行业务身份）/ conflict（多页签冲突取先到值）。
 * 破坏性变更：旧 Record<col,[old,new]> 形状已废弃，后端不再兼容旧形状（同批次发布，无外部消费方）。
 */
export interface CostingApprovePreviewRow {
  op: 'CHANGE' | 'ADD' | 'DELETE';
  __v6_id: string | null;
  /** repair-0727 新增：该行业务身份，前端直接展示（如「组成件 W-1001（外购件）」） */
  rowLabel?: string;
  /** repair-0727 新增：同列被多页签 patch 且值不同 */
  conflict?: boolean;
  /** op=CHANGE 时带 */
  changes?: CostingApprovePreviewChange[];
  /** op=ADD / DELETE 时带 */
  values?: CostingApprovePreviewValue[];
}

/** repair-0727（api.md §1.1）：轴的人类可读表达，替代原始 V6 物理轴串给财务看。 */
export interface CostingApprovePreviewAxisLabel {
  column: string;
  label: string;
  value: string;
  /** 拼好的展示文案，如「苏州西门子（CUST-0001）」「S-3120014539 接触片组件」 */
  display: string;
}

/** task-0721（api.md §1.1）：一个 V6 目标表分组（按 groupKey 轴聚合的一次升版）。 */
export interface CostingApprovePreviewGroup {
  /** V6 目标表名（如 unit_price） */
  table: string;
  /** 报价单页签展示名（如 电镀费） */
  tabName: string;
  /** 轴摘要，键值对形式，纯展示用 */
  groupKey: Record<string, string>;
  /** 旧版本号，无则 null=首版 */
  versionFrom: string | null;
  versionTo: string;
  /** true=全局共享表（如电镀方案），前端需重点标注「影响所有客户」 */
  isGlobalShared: boolean;
  rows: CostingApprovePreviewRow[];

  // ── repair-0727 新增字段（api.md §1.1） ──
  /** 产品归属料号，无产品维度（如 plating_scheme）为 null，此时归入 globalShared */
  productNo?: string | null;
  productName?: string | null;
  /** 业务类别中文名（BOM 组成 / 材质元素构成 / 单价 / 工时产能 / 电镀方案） */
  categoryLabel?: string;
  /** patch 语义下该组走的路径 */
  route?: 'REBUILD' | 'FLIP' | 'OFFLINE';
  /** 基底行来源 */
  baseSource?: 'PENDING' | 'CURRENT' | 'NONE';
  /** 基底行数 */
  baseRowCount?: number;
  /** 通过后该组行数（预期值） */
  resultRowCount?: number;
  /** 轴的人类可读表达 */
  axisLabels?: CostingApprovePreviewAxisLabel[];
}

/** repair-0727（api.md §1.1）：按产品聚合的视图，groupIndexes 指向 groups 数组下标（避免重复传输）。 */
export interface CostingApprovePreviewProduct {
  productNo: string;
  productName: string | null;
  customerNo: string;
  customerName: string | null;
  groupIndexes: number[];
}

/** repair-0727（api.md §1.1）：无产品维度的全局共享组（当前仅 plating_scheme）。 */
export interface CostingApprovePreviewGlobalShared {
  groupIndexes: number[];
}

/** task-0721（api.md §1.1）：GET costing-approve/preview 响应体。只读、无副作用、幂等。 */
export interface CostingApprovePreviewResult {
  quotationId: string;
  /** 影响清单内容 hash，提交时须原样带回；预览后数据漂移会致提交 409 */
  previewToken: string;
  summary: CostingApprovePreviewSummary;
  /** repair-0727 新增：按产品聚合的主视图，渲染以此为主，groups 仍保留作为数据源 */
  products: CostingApprovePreviewProduct[];
  /** repair-0727 新增：无产品维度的全局共享组视图 */
  globalShared: CostingApprovePreviewGlobalShared;
  groups: CostingApprovePreviewGroup[];
}

/** task-0721（api.md §1.2）：POST costing-approve 成功响应，除 QuotationDTO 字段外额外带 backfill 汇总。 */
export interface CostingApproveResult {
  backfill?: CostingApprovePreviewSummary;
  [key: string]: unknown;
}

const base = '/costing-orders';

export const costingOrderService = {
  /**
   * 列表查询。status 为可重复参数（后端 List<String>），发出格式为 status=A&status=B。
   */
  list: (params?: { statuses?: string[]; keyword?: string; sort?: string }): Promise<{ data: CostingOrderListItem[] }> =>
    api.get(base, {
      params: { status: params?.statuses, keyword: params?.keyword, sort: params?.sort },
      paramsSerializer: { indexes: null },
    }) as Promise<{ data: CostingOrderListItem[] }>,

  getById: (coid: string): Promise<{ data: CostingOrderDetail }> =>
    api.get(`${base}/${coid}`) as Promise<{ data: CostingOrderDetail }>,

  /**
   * task-0721（api.md §1.1）：核价通过前置预览——只读、无副作用、幂等。
   * 拿到的 previewToken 必须原样带回 approve()，否则后端 400（强制先预览）。
   */
  previewApprove: (quotationId: string): Promise<{ data: CostingApprovePreviewResult }> =>
    api.get(`/quotations/${quotationId}/costing-approve/preview`) as Promise<{ data: CostingApprovePreviewResult }>,

  /**
   * task-0721（api.md §1.2）：核价通过并回填，两段式提交，previewToken 必填。
   * 若预览后数据发生漂移，后端返 409（message=报价数据在预览后发生变化，请重新预览），
   * 调用方需重新 previewApprove 拿新 token 后重试（CostingApprovePreviewDrawer 内已处理）。
   */
  approve: (quotationId: string, previewToken: string, comment?: string): Promise<{ data: CostingApproveResult }> =>
    api.post(`/quotations/${quotationId}/costing-approve`, { comment, previewToken }) as Promise<{ data: CostingApproveResult }>,

  reject: (quotationId: string, comment: string): Promise<{ data: unknown }> =>
    api.post(`/quotations/${quotationId}/costing-reject`, { comment }) as Promise<{ data: unknown }>,

  /**
   * task-0713（api.md §2）：查询某料号在某页签的可选版本（下拉数据源）。
   * 独立轻查（列出模式），不走带缓存的 batch-expand（守 AP-37 串号）。
   */
  getVersionOptions: (
    coid: string,
    params: { lineItemId: string; componentId: string; partNo: string },
  ): Promise<{ data: VersionOptionsResult }> =>
    api.get(`${base}/${coid}/version-options`, { params }) as Promise<{ data: VersionOptionsResult }>,

  /**
   * task-0713（api.md §3）：切换版本（核心写操作）。仅 PENDING + 财务/管理员可调，
   * 否则 403。响应只含受影响卡片的增量数据，前端不得据此重新 getById 整单（守 AP-31）。
   */
  switchVersion: (
    coid: string,
    body: { lineItemId: string; componentId: string; partNo: string; viewVersion: string },
  ): Promise<{ data: VersionSwitchResult }> =>
    api.post(`${base}/${coid}/version-switch`, body) as Promise<{ data: VersionSwitchResult }>,
};
