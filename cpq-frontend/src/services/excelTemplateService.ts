import api from './api';

export const excelTemplateService = {
  list: (params?: any) => api.get('/excel-templates', { params }) as Promise<any>,
  getById: (id: string) => api.get(`/excel-templates/${id}`) as Promise<any>,
  create: (data: any) => api.post('/excel-templates', data) as Promise<any>,
  update: (id: string, data: any) => api.put(`/excel-templates/${id}`, data) as Promise<any>,
  delete: (id: string) => api.delete(`/excel-templates/${id}`) as Promise<any>,
  parseHeaders: (file: File, sheetIndex: number, headerRowIndex: number) => {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('sheetIndex', String(sheetIndex));
    fd.append('headerRowIndex', String(headerRowIndex));
    return api.post('/excel-templates/parse-headers', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
};
