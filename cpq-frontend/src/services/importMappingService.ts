import api from './api';

export const importMappingService = {
  list: (params?: any) => api.get('/import-mappings', { params }) as Promise<any>,
  getById: (id: string) => api.get(`/import-mappings/${id}`) as Promise<any>,
  create: (data: any) => api.post('/import-mappings', data) as Promise<any>,
  update: (id: string, data: any) => api.put(`/import-mappings/${id}`, data) as Promise<any>,
  delete: (id: string) => api.delete(`/import-mappings/${id}`) as Promise<any>,
};
