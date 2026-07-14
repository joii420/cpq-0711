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

  approve: (quotationId: string, comment?: string): Promise<{ data: unknown }> =>
    api.post(`/quotations/${quotationId}/costing-approve`, { comment }) as Promise<{ data: unknown }>,

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
