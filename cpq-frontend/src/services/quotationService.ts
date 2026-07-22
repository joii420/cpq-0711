import api from './api';
import type { ExistingProductDTO, ExistingProductQueryParams, PageResult } from '../types/existingProduct';

// ─── 报价单整份快照类型（Phase 2）─────────────────────────────────────────────
// 报价单级 4 份结构快照（getById 顶层，JsonNode → 已解析对象）；
// 产品行级 4 份值快照（LineItem 上，后端以 JSON **字符串**返回，消费方需 JSON.parse）。

/** 卡片结构 · 字段（spec §3.1，camelCase）。 */
export interface CardStructureField {
  name: string;
  fieldType: string;
  label?: string;
  sortOrder?: number;
  isAmount?: boolean;
  isRequired?: boolean;
  editable?: boolean;
  defaultValue?: string | null;
  basicDataPath?: string | null;
  datasourceBinding?: any;
  // Task5(structure v2): 补全 config keys, 供前端旁路 enrich 组装完整 componentData(AP-44)
  isSubtotal?: boolean;
  formulaName?: string | null;
  globalVariableCode?: string | null;
  defaultSource?: any;
  listFormulaConfig?: any;
}

/** 卡片结构 · 页签。 */
export interface CardStructureTab {
  componentId: string;
  // 产品小计=0 修复(2026-06-02): SUBTOTAL 公式按 component_code(含 __impN) 引用各 tab 小计，
  // 前端旁路 enrich 组装 componentData 时必须从结构取回，否则公式解析全落空。
  componentCode?: string;
  tabName: string;
  sortOrder?: number;
  componentType?: string;
  dataDriverPath?: string;
  rowKeyFields?: string[];
  treeConfig?: { idField: string; parentField: string; defaultExpanded?: boolean };
  fields: CardStructureField[];
  formulas?: Array<{ name: string; expression: any[] }>;
  /**
   * task-0721 F2：页签类型属性 + 料号列标识（需求说明 §4.3 规则一，2026-07-21 补充）。
   * BOM 树页签的料号取系统列 __hfPartNo，partNoField 可空；非树页签须配 partNoField。
   * 后端 CardSnapshotService 尚未把这三个字段回填进结构快照前，恒为 undefined（等价于历史行为）。
   */
  tabType?: string;
  partNoField?: string;
  partNameField?: string;
}

export interface CardStructure {
  version?: number;
  templateId?: string;
  templateKind?: string;
  tabs: CardStructureTab[];
  /** Task5(structure v2): 产品属性 schema, 前端旁路 loadProductAttributes(GET /templates) */
  productAttributes?: Array<{ name?: string; field_type?: string; required?: boolean; default_value?: any; source?: string }>;
}

/** 卡片值 · 基础冻结行（driver 展开结果）。 */
export interface CardValueBaseRow {
  driverRow: Record<string, any>;
  basicDataValues: Record<string, any>;
}

/** 卡片值 · 按 rowKey 索引的行（editRows / formulaResults 共用形状）。 */
export interface CardValueKeyedRow {
  rowKey: string;
  values: Record<string, any>;
}

export interface CardValuesTab {
  componentId: string;
  tabName: string;
  baseRows: CardValueBaseRow[];
  editRows: CardValueKeyedRow[];
  formulaResults: CardValueKeyedRow[];
}

export interface CardValues {
  tabs: CardValuesTab[];
}

/** Excel 结构 · 列。 */
export interface ExcelStructureColumn {
  colKey: string;
  title?: string;
  sourceType?: string;
  variablePath?: string;
  formula?: string;
  hidden?: boolean;
  comparisonTag?: string;
}

export interface ExcelStructure {
  version?: number;
  templateId?: string;
  templateKind?: string;
  columns: ExcelStructureColumn[];
}

/** Excel 值（算好的列值）。 */
export interface ExcelValues {
  rows: Array<Record<string, any>>;
}

/** getById 捎回的有效 Excel 列（snake_case + display_format），可直接当 CostingTemplateColumn 用。 */
export type ExcelEffectiveColumn = import('./costingTemplateService').CostingTemplateColumn;

/** getById 顶层附带的 4 份结构快照（JsonNode → 已解析对象，可能为 null）。 */
export interface QuotationSnapshotStructures {
  quoteCardStructure?: CardStructure | null;
  quoteExcelStructure?: ExcelStructure | null;
  costingCardStructure?: CardStructure | null;
  costingExcelStructure?: ExcelStructure | null;
}

/** LineItem 上的 4 份值快照（后端返 JSON **字符串**，消费方 JSON.parse）。 */
export interface LineItemSnapshotValues {
  quoteCardValues?: string | null;
  quoteExcelValues?: string | null;
  costingCardValues?: string | null;
  costingExcelValues?: string | null;
}

// ─── task-0721：报价侧 BOM 树上编辑（加叶子 / 删除预览+执行）── api.md §3-§5 ─────────────

export interface TreeAddLeafResult {
  nodeId: string;
  nodeType: string;
  /** 整单卡片值（JSON 字符串），前端直接回灌，不二次拉取 */
  quoteCardValues: string;
}

export interface TreeDeleteNodePreview {
  nodeId: string;
  partNo: string;
  lvl: number;
}

export interface TreeDeleteCascadeRow {
  rowKey: string;
  partNo: string;
  summary: string;
}

export interface TreeDeleteCascadeTab {
  componentId: string;
  tabName: string;
  rows: TreeDeleteCascadeRow[];
}

export interface TreeDeleteRetainedPart {
  partNo: string;
  remainingOccurrences: number;
  reason: string;
}

export interface TreeDeletePreviewResult {
  /** 执行删除时须回传，防预览与执行之间数据漂移（api.md §5） */
  previewToken: string;
  treeNodes: TreeDeleteNodePreview[];
  cascadeTabs: TreeDeleteCascadeTab[];
  retainedParts: TreeDeleteRetainedPart[];
}

export interface TreeDeleteExecResult {
  deletedNodeIds: string[];
  cascadeDeletedRowKeys: Record<string, string[]>;
  quoteCardValues: string;
}

export const quotationService = {
  list: (params: any) => api.get('/quotations', { params }) as Promise<any>,
  getById: (id: string) => api.get(`/quotations/${id}`) as Promise<any>,
  /** Step2 批量导入产品 — 列出该客户的料号候选;importRecordId 可选,精确"这次导入"语义 */
  listCustomerPartCandidates: (customerId: string, importRecordId?: string) =>
    api.get('/quotations/customer-part-candidates', {
      params: importRecordId ? { customerId, importRecordId } : { customerId },
    }) as Promise<any>,
  /**
   * F4「从已有产品添加」— GET /quotations/{quotationId}/existing-products（api.md §2.1，task-0712 B3）。
   * 数据源 material_customer_map，服务端从 quotation 派生 customer_no 过滤（前端不传客户）；
   * 4 个查询参数全可选、AND 组合、模糊匹配。已内部解开 ApiResponse 信封，直接返回 PageResult
   * （与 `selTemplateService.effective` 同惯例，见该方法注释；调用方不需要再 `.then(res => res.data)`）。
   */
  listExistingProducts: async (
    quotationId: string,
    params: ExistingProductQueryParams,
  ): Promise<PageResult<ExistingProductDTO>> => {
    const res: any = await api.get(`/quotations/${quotationId}/existing-products`, { params });
    return (res && typeof res === 'object' && 'data' in res ? res.data : res) as PageResult<ExistingProductDTO>;
  },
  create: (data: any) => api.post('/quotations', data) as Promise<any>,
  saveDraft: (id: string, data: any) => api.put(`/quotations/${id}/draft`, data) as Promise<any>,
  /** lazy-cardvalues：懒算并落库整单卡片值。warm 与打开兜底复用。返回 { data: QuotationDTO(含 cardValuesWarming) }。 */
  ensureCardValues: (id: string) => api.post(`/quotations/${id}/ensure-card-values`) as Promise<any>,
  /** 报价单整份快照 Phase2 §5: 草稿态重刷报价侧卡片值(按行键保编辑); 仅 DRAFT 生效, 非 DRAFT no-op 返 refreshed=0 */
  refreshCardSnapshot: (id: string) => api.post(`/quotations/${id}/refresh-card-snapshot`) as Promise<any>,
  /** P3 lazy-excel: 懒算并落库整单 Excel 值(首存只算卡片、Excel 留空); 开 Excel 视图/导出前调, 幂等; 返回含 Excel 值的最新 DTO */
  ensureExcelValues: (id: string) => api.post(`/quotations/${id}/ensure-excel-values`) as Promise<any>,
  /**
   * 报价单整份快照 Phase2 §6: 编辑回写报价卡片单元格(替代旧 autosave 写 row_data)。
   * body: {componentId, rowKey, fieldName, value}; 写 editRows + 重算 formulaResults/报价Excel; 核价不动。
   * 仅 DRAFT; 返回 {quoteCardValues, quoteExcelValues, quoteValuesAt} 供前端就地刷新(AP-50)。
   * (前端单元格编辑改调此端点 + 渲染读快照在 Task 8 一起落地)
   */
  editQuoteCardValue: (lineItemId: string, body: { componentId: string; rowKey: string; fieldName: string; value: any }) =>
    api.put(`/quotations/line-items/${lineItemId}/quote-card-edit`, body) as Promise<any>,
  calculateDiscount: (id: string, originalAmount: number) => api.post(`/quotations/${id}/calculate-discount`, { originalAmount }) as Promise<any>,
  // submit 已统一迁移至 quotationSnapshotService.submit（含快照写入逻辑）
  approve: (id: string, comment?: string) => api.post(`/quotations/${id}/approve`, { comment }) as Promise<any>,
  reject: (id: string, comment: string) => api.post(`/quotations/${id}/reject`, { comment }) as Promise<any>,
  withdraw: (id: string) => api.post(`/quotations/${id}/withdraw`) as Promise<any>,
  /** 被驳回单转草稿（COSTING_REJECTED → DRAFT），清自身快照，核价单仍保持 REJECTED */
  beginEdit: (id: string) => api.post(`/quotations/${id}/begin-edit`) as Promise<any>,
  copy: (id: string, templateId?: string) =>
    api.post(`/quotations/${id}/copy`, templateId ? { templateId } : {}) as Promise<any>,
  delete: (id: string) => api.delete(`/quotations/${id}`) as Promise<any>,

  // M5: Output module
  send: (id: string, params: { to: string; cc?: string; subject?: string; body?: string; attachExcel?: boolean }) =>
    api.post(`/quotations/${id}/send`, params) as Promise<any>,
  extend: (id: string, newExpiryDate: string) =>
    api.put(`/quotations/${id}/extend`, { newExpiryDate }) as Promise<any>,
  accept: (id: string) => api.post(`/quotations/${id}/accept`) as Promise<any>,
  rejectByCustomer: (id: string, comment?: string) =>
    api.post(`/quotations/${id}/reject-by-customer`, { comment }) as Promise<any>,

  exportHtml: (id: string, options?: { showDiscount?: boolean; showProcesses?: boolean; showTabDetails?: boolean }) => {
    const params = new URLSearchParams();
    if (options?.showDiscount !== undefined) params.set('showDiscount', String(options.showDiscount));
    if (options?.showProcesses !== undefined) params.set('showProcesses', String(options.showProcesses));
    if (options?.showTabDetails !== undefined) params.set('showTabDetails', String(options.showTabDetails));
    return `/api/cpq/quotations/${id}/export/html?${params.toString()}`;
  },

  exportExcel: (id: string, options?: { showDiscount?: boolean; includeRawData?: boolean }) =>
    api.post(`/quotations/${id}/export/excel`, options || {}, { responseType: 'blob' }) as Promise<any>,

  // Excel view (Import v2)
  getExcelView: (id: string, templateId?: string) =>
    api.get(`/quotations/${id}/excel-view`, { params: templateId ? { templateId } : undefined }) as Promise<any>,
  dryRunExcelView: (id: string, body: { templateId?: string; columns: any[] }) =>
    api.post(`/quotations/${id}/excel-view/dry-run`, body) as Promise<any>,
  updateExcelViewCell: (id: string, data: any) => api.put(`/quotations/${id}/excel-view`, data) as Promise<any>,
  exportExcelView: (id: string) => api.get(`/quotations/${id}/export-excel-view`, { responseType: 'blob' }) as Promise<any>,

  // Import v3
  importPreview: (file: File, templateId: string, customerId: string) => {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('templateId', templateId);
    fd.append('customerId', customerId);
    return api.post('/quotations/import-excel', fd, { headers: { 'Content-Type': 'multipart/form-data' } }) as Promise<any>;
  },
  confirmImport: (data: any) => api.post('/quotations/confirm-import', data) as Promise<any>,

  // driver 默认行永久删除 / 还原（Task 8）
  deleteDriverRow: (qid: string, lid: string, componentId: string, effKey: string, fp: string) =>
    api.post(`/quotations/${qid}/line-items/${lid}/delete-driver-row`, { componentId, effKey, fp }) as Promise<any>,
  restoreDriverRows: (qid: string, lid: string, componentId: string) =>
    api.post(`/quotations/${qid}/line-items/${lid}/restore-driver-rows`, { componentId }) as Promise<any>,

  // task-0721 F4/F5：报价侧 BOM 树上编辑（api.md §3-§5）。
  // 类型判定 / 级联影响面计算均在后端（架构红线，前端不得自行实现），
  // 前端仅负责发起请求 + 用返回的 quoteCardValues 直接回灌（不二次拉取）。
  addTreeLeaf: (qid: string, lid: string, body: { componentId: string; hostNodeId: string; partNo: string }) =>
    api.post(`/quotations/${qid}/line-items/${lid}/tree/add-leaf`, body) as Promise<any>,
  previewTreeDelete: (
    qid: string,
    lid: string,
    body: { componentId: string; mode: 'PRUNE' | 'ROW'; nodeId: string; rowKey?: string },
  ) => api.post(`/quotations/${qid}/line-items/${lid}/tree/delete-preview`, body) as Promise<any>,
  executeTreeDelete: (
    qid: string,
    lid: string,
    body: { componentId: string; mode: 'PRUNE' | 'ROW'; nodeId: string; rowKey?: string; previewToken: string },
  ) => api.post(`/quotations/${qid}/line-items/${lid}/tree/delete`, body) as Promise<any>,
};
