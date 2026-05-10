import api from './api';

export const templateService = {
  list: (params?: any) => api.get('/templates', { params }) as Promise<any>,
  getById: (id: string) => api.get(`/templates/${id}`) as Promise<any>,
  create: (data: any) => api.post('/templates', data) as Promise<any>,
  update: (id: string, data: any) => api.put(`/templates/${id}`, data) as Promise<any>,
  delete: (id: string) => api.delete(`/templates/${id}`) as Promise<any>,
  publish: (id: string, data?: any) => api.post(`/templates/${id}/publish`, data || {}) as Promise<any>,
  archive: (id: string, force = false) => api.post(`/templates/${id}/archive?force=${force}`, {}) as Promise<any>,
  createNewDraft: (id: string) => api.post(`/templates/${id}/new-draft`, {}) as Promise<any>,
  getVersionHistory: (seriesId: string) => api.get(`/templates/series/${seriesId}/versions`) as Promise<any>,
  // components
  listComponents: (templateId: string) => api.get(`/templates/${templateId}/components`) as Promise<any>,
  addComponent: (templateId: string, data: any) => api.post(`/templates/${templateId}/components`, data) as Promise<any>,
  removeComponent: (templateId: string, tcId: string) => api.delete(`/templates/${templateId}/components/${tcId}`) as Promise<any>,
  reorderComponents: (templateId: string, ids: string[]) => api.put(`/templates/${templateId}/components/reorder`, { ids }) as Promise<any>,
  updatePresetRows: (templateId: string, tcId: string, presetRows: any[]) =>
    api.patch(`/templates/${templateId}/components/${tcId}/preset-rows`, { presetRows }) as Promise<any>,
  updateFormulaAssignments: (templateId: string, tcId: string, formulaAssignments: Record<string, string>) =>
    api.patch(`/templates/${templateId}/components/${tcId}/formula-assignments`, { formulaAssignments }) as Promise<any>,
  // Excel view config
  getExcelViewConfig: (id: string) => api.get(`/templates/${id}/excel-view-config`) as Promise<any>,
  updateExcelViewConfig: (id: string, config: any) => api.put(`/templates/${id}/excel-view-config`, config) as Promise<any>,
  parseHeader: (templateId: string, file: File, sheetIndex: number, headerRowIndex: number) => {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('sheetIndex', String(sheetIndex));
    fd.append('headerRowIndex', String(headerRowIndex));
    return api.post(`/templates/${templateId}/excel-view-config/parse-header`, fd, { headers: { 'Content-Type': 'multipart/form-data' } }) as Promise<any>;
  },
};
