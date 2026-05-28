import api from './api';

export interface ProductProcessItem {
  processId: string;
  sortOrder: number;
  isRequired: boolean;
}

export interface Process {
  id: string;
  code: string;
  name: string;
  category: string;
  description?: string;
  isRequired?: boolean;
  sortOrder?: number;
  status?: 'ACTIVE' | 'DISABLED';
}

// 以下方法被 ProcessSelection / AddProductModal / ProductTemplateBinding 依赖，保留
export const processService = {
  listAll: (category?: string) => api.get('/processes', { params: { category } }) as Promise<any>,
  getProductProcesses: (productId: string) => api.get(`/processes/products/${productId}/processes`) as Promise<any>,
  bindProcesses: (productId: string, processes: ProductProcessItem[]) =>
    api.post(`/processes/products/${productId}/processes`, { processes }) as Promise<any>,
  unbindAll: (productId: string) => api.delete(`/processes/products/${productId}/processes`) as Promise<any>,
};
