import api from './api';

export const industryService = {
  list: (params: any) => api.get('/industries', { params }) as Promise<any>,
  listActive: () => api.get('/industries/active') as Promise<any>,
  getById: (id: string) => api.get(`/industries/${id}`) as Promise<any>,
  create: (data: any) => api.post('/industries', data) as Promise<any>,
  update: (id: string, data: any) => api.put(`/industries/${id}`, data) as Promise<any>,
  delete: (id: string) => api.delete(`/industries/${id}`) as Promise<any>,
  batchDelete: (ids: string[]) => api.post('/industries/batch-delete', { ids }) as Promise<any>,
};
