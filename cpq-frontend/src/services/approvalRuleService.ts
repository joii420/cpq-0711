import api from './api';

export const approvalRuleService = {
  list: () => api.get('/approval-rules') as Promise<any>,
  create: (data: any) => api.post('/approval-rules', data) as Promise<any>,
  update: (id: string, data: any) => api.put(`/approval-rules/${id}`, data) as Promise<any>,
  delete: (id: string) => api.delete(`/approval-rules/${id}`) as Promise<any>,
};
