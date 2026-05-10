import api from './api';

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
