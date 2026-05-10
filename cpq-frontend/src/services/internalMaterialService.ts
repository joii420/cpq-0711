import api from './api';

export const internalMaterialService = {
  list: (params: any) => api.get('/internal-materials', { params }) as Promise<any>,
  getById: (id: string) => api.get(`/internal-materials/${id}`) as Promise<any>,
  create: (data: any) => api.post('/internal-materials', data) as Promise<any>,
  update: (id: string, data: any) => api.put(`/internal-materials/${id}`, data) as Promise<any>,
  delete: (id: string) => api.delete(`/internal-materials/${id}`) as Promise<any>,
  importExcel: (file: File) => {
    const fd = new FormData();
    fd.append('file', file);
    return api.post('/internal-materials/import', fd, { headers: { 'Content-Type': 'multipart/form-data' } });
  },
};
