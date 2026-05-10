import api from './api';
export const regionService = {
  list: (params?: { page?: number; size?: number }) => api.get('/regions', { params }) as Promise<any>,
  create: (data: any) => api.post('/regions', data) as Promise<any>,
  update: (id: string, data: any) => api.put(`/regions/${id}`, data) as Promise<any>,
  updateStatus: (id: string, status: string) => api.patch(`/regions/${id}`, { status }) as Promise<any>,
};
