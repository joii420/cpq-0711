import api from './api';

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
}

/** 卡片结构 · 页签。 */
export interface CardStructureTab {
  componentId: string;
  tabName: string;
  sortOrder?: number;
  componentType?: string;
  dataDriverPath?: string;
  rowKeyFields?: string[];
  fields: CardStructureField[];
  formulas?: Array<{ name: string; expression: any[] }>;
}

export interface CardStructure {
  version?: number;
  templateId?: string;
  templateKind?: string;
  tabs: CardStructureTab[];
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

export const quotationService = {
  list: (params: any) => api.get('/quotations', { params }) as Promise<any>,
  getById: (id: string) => api.get(`/quotations/${id}`) as Promise<any>,
  /** Step2 批量导入产品 — 列出该客户的料号候选;importRecordId 可选,精确"这次导入"语义 */
  listCustomerPartCandidates: (customerId: string, importRecordId?: string) =>
    api.get('/quotations/customer-part-candidates', {
      params: importRecordId ? { customerId, importRecordId } : { customerId },
    }) as Promise<any>,
  create: (data: any) => api.post('/quotations', data) as Promise<any>,
  saveDraft: (id: string, data: any) => api.put(`/quotations/${id}/draft`, data) as Promise<any>,
  /** 报价单整份快照 Phase2 §5: 草稿态重刷报价侧卡片值(按行键保编辑); 仅 DRAFT 生效, 非 DRAFT no-op 返 refreshed=0 */
  refreshCardSnapshot: (id: string) => api.post(`/quotations/${id}/refresh-card-snapshot`) as Promise<any>,
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
  copy: (id: string) => api.post(`/quotations/${id}/copy`) as Promise<any>,
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
  getExcelView: (id: string) => api.get(`/quotations/${id}/excel-view`) as Promise<any>,
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
};
