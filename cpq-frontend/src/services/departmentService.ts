import api from './api';

export const departmentService = {
  list: (params?: { page?: number; size?: number }) =>
    api.get('/departments', { params }) as Promise<any>,
  create: (data: any) => api.post('/departments', data) as Promise<any>,
  update: (id: string, data: any) => api.put(`/departments/${id}`, data) as Promise<any>,
  updateStatus: (id: string, status: string) => api.patch(`/departments/${id}`, { status }) as Promise<any>,
};
