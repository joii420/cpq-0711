import api from './api';

// v0.4 §17.5 客户线索（客户身份处理 SOP）
export const customerLeadService = {
  list: (params: any) => api.get('/customer-leads', { params }) as Promise<any>,
  getById: (id: string) => api.get(`/customer-leads/${id}`) as Promise<any>,
  create: (data: any) => api.post('/customer-leads', data) as Promise<any>,

  // 审核三选一：BIND_EXISTING / CREATE_NEW / REJECT
  review: (
    id: string,
    action: 'BIND_EXISTING' | 'CREATE_NEW' | 'REJECT',
    payload: { bound_customer_id?: string; review_note?: string }
  ) => api.post(`/customer-leads/${id}/review`, { action, ...payload }) as Promise<any>,
};
