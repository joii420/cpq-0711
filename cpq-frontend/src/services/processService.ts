import api from './api';

export interface ProductProcessItem {
  processId: string;
  sortOrder: number;
  isRequired: boolean;
}

export const processService = {
  listAll: (category?: string) => api.get('/processes', { params: { category } }) as Promise<any>,
  getProductProcesses: (productId: string) => api.get(`/processes/products/${productId}/processes`) as Promise<any>,
  bindProcesses: (productId: string, processes: ProductProcessItem[]) =>
    api.post(`/processes/products/${productId}/processes`, { processes }) as Promise<any>,
  unbindAll: (productId: string) => api.delete(`/processes/products/${productId}/processes`) as Promise<any>,
};
