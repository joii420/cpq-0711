import api from './api';

export const productService = {
  list: (params: any) => api.get('/products', { params }) as Promise<any>,
  create: (data: any) => api.post('/products', data) as Promise<any>,
  update: (id: string, data: any) => api.put(`/products/${id}`, data) as Promise<any>,
  delete: (id: string) => api.delete(`/products/${id}`) as Promise<any>,
  importExcel: (file: File) => {
    const formData = new FormData();
    formData.append('file', file);
    return api.post('/products/import', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }) as Promise<any>;
  },
};
