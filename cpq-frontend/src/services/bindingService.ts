import api from './api';

export const bindingService = {
  listByProduct: (productId: string) => api.get(`/products/${productId}/template-bindings`) as Promise<any>,
  create: (productId: string, data: any) => api.post(`/products/${productId}/template-bindings`, data) as Promise<any>,
  delete: (productId: string, bindingId: string) => api.delete(`/products/${productId}/template-bindings/${bindingId}`) as Promise<any>,
  setDefault: (productId: string, bindingId: string) => api.put(`/products/${productId}/template-bindings/${bindingId}/set-default`) as Promise<any>,
  matchTemplates: (productId: string, processIds: string[]) => api.get(`/products/${productId}/template-bindings/match`, { params: { processIds: processIds.join(',') } }) as Promise<any>,
  compareTemplates: (templateAId: string, templateBId: string) => api.post('/templates/compare', { templateAId, templateBId }) as Promise<any>,
};
