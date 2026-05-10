import api from './api';

export interface ProductCategory {
  id: string;
  code: string;
  name: string;
  description?: string;
  parentId?: string;
  status: 'ACTIVE' | 'DISABLED';
  sortOrder: number;
  createdAt?: string;
  updatedAt?: string;
}

export const productCategoryService = {
  list: (status?: string) =>
    api.get('/product-categories', { params: status ? { status } : undefined }) as Promise<{ data: ProductCategory[] }>,
  getById: (id: string) =>
    api.get(`/product-categories/${id}`) as Promise<{ data: ProductCategory }>,
  create: (data: Partial<ProductCategory>) =>
    api.post('/product-categories', data) as Promise<{ data: ProductCategory }>,
  update: (id: string, data: Partial<ProductCategory>) =>
    api.put(`/product-categories/${id}`, data) as Promise<{ data: ProductCategory }>,
  delete: (id: string) =>
    api.delete(`/product-categories/${id}`) as Promise<{ data: void }>,
};
