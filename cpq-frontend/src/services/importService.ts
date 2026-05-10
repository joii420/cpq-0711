import api from './api';

export const importService = {
  // Legacy v1/v2 (kept for backward compat)
  execute: (
    file: File,
    customerId: string,
    excelTemplateId: string,
    mappingTemplateId: string,
  ) => {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('customerId', customerId);
    fd.append('excelTemplateId', excelTemplateId);
    fd.append('mappingTemplateId', mappingTemplateId);
    return api.post('/imports/execute', fd, { headers: { 'Content-Type': 'multipart/form-data' } });
  },
  list: (params?: any) => api.get('/imports', { params }) as Promise<any>,
  getById: (id: string) => api.get(`/imports/${id}`) as Promise<any>,
  download: (id: string) =>
    api.get(`/imports/${id}/download`, { responseType: 'blob' }) as Promise<any>,

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
