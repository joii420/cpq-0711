import api from './api';

export const materialMappingService = {
  list: (customerId: string, params?: any) =>
    api.get(`/customers/${customerId}/material-mappings`, { params }) as Promise<any>,
  create: (customerId: string, data: any) =>
    api.post(`/customers/${customerId}/material-mappings`, data) as Promise<any>,
  delete: (customerId: string, id: string) =>
    api.delete(`/customers/${customerId}/material-mappings/${id}`) as Promise<any>,
  importExcel: (customerId: string, file: File) => {
    const fd = new FormData();
    fd.append('file', file);
    return api.post(`/customers/${customerId}/material-mappings/import`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
  match: (customerId: string, partNo: string) =>
    api.get(`/customers/${customerId}/material-mappings/match`, { params: { partNo } }) as Promise<any>,
};
