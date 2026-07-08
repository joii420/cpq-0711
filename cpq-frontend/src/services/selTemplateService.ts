import api from './api';

export const selTemplateService = {
  listParamTypes: () => api.get('/sel-param-types') as Promise<any>,
  candidates: (code: string) => api.get(`/sel-param-types/${code}/candidates`) as Promise<any>,
  list: () => api.get('/sel-templates') as Promise<any>,
  getById: (id: string) => api.get(`/sel-templates/${id}`) as Promise<any>,
  upsert: (data: any) => api.post('/sel-templates', data) as Promise<any>,
  delete: (id: string) => api.delete(`/sel-templates/${id}`) as Promise<any>,
};
