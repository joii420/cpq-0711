import api from './api';

export const customerService = {
  list: (params: any) => api.get('/customers', { params }) as Promise<any>,
  getById: (id: string) => api.get(`/customers/${id}`) as Promise<any>,
  create: (data: any) => api.post('/customers', data) as Promise<any>,
  update: (id: string, data: any) => api.put(`/customers/${id}`, data) as Promise<any>,
  delete: (id: string) => api.delete(`/customers/${id}`) as Promise<any>,
  batchDelete: (ids: string[]) => api.post('/customers/batch-delete', { ids }) as Promise<any>,
  listContacts: (customerId: string) => api.get(`/customers/${customerId}/contacts`) as Promise<any>,
  createContact: (customerId: string, data: any) =>
    api.post(`/customers/${customerId}/contacts`, data) as Promise<any>,
  updateContact: (customerId: string, contactId: string, data: any) =>
    api.put(`/customers/${customerId}/contacts/${contactId}`, data) as Promise<any>,
  deleteContact: (customerId: string, contactId: string) =>
    api.delete(`/customers/${customerId}/contacts/${contactId}`) as Promise<any>,
  setPrimary: (customerId: string, contactId: string) =>
    api.put(`/customers/${customerId}/contacts/${contactId}/set-primary`) as Promise<any>,
};
