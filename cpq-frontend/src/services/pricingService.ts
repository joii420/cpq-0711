import api from './api';

export const pricingService = {
  list: (params: any) => api.get('/pricing-strategies', { params }) as Promise<any>,
  getById: (id: string) => api.get(`/pricing-strategies/${id}`) as Promise<any>,
  create: (data: any) => api.post('/pricing-strategies', data) as Promise<any>,
  update: (id: string, data: any) => api.put(`/pricing-strategies/${id}`, data) as Promise<any>,
  delete: (id: string) => api.delete(`/pricing-strategies/${id}`) as Promise<any>,
  updateStatus: (id: string, status: string) => api.patch(`/pricing-strategies/${id}`, { status }) as Promise<any>,
};
