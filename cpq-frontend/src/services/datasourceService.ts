import api from './api';

export const datasourceService = {
  list: (params: any) => api.get('/datasources', { params }) as Promise<any>,
  getById: (id: string) => api.get(`/datasources/${id}`) as Promise<any>,
  create: (data: any) => api.post('/datasources', data) as Promise<any>,
  update: (id: string, data: any) => api.put(`/datasources/${id}`, data) as Promise<any>,
  delete: (id: string) => api.delete(`/datasources/${id}`) as Promise<any>,
  test: (id: string, params: any) => api.post(`/datasources/${id}/test`, params) as Promise<any>,
  execute: (id: string, params: any) => api.post(`/datasources/${id}/execute`, params) as Promise<any>,
};
