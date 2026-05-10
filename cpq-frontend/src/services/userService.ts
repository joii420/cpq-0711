import api from './api';

export const userService = {
  list: (params: { page?: number; size?: number; role?: string; status?: string; keyword?: string }) =>
    api.get('/users', { params }) as Promise<any>,
  create: (data: any) => api.post('/users', data) as Promise<any>,
  update: (id: string, data: any) => api.put(`/users/${id}`, data) as Promise<any>,
  updateStatus: (id: string, status: string) => api.patch(`/users/${id}`, { status }) as Promise<any>,
  resetPassword: (id: string) => api.post(`/users/${id}/reset-password`) as Promise<any>,
};
