import api from './api';

// v0.4 §6 3D 模型 + 源文件
export const partModelService = {
  list: (params: any) => api.get('/part-models', { params }) as Promise<any>,
  getById: (id: string) => api.get(`/part-models/${id}`) as Promise<any>,
  register: (data: any) => api.post('/part-models', data) as Promise<any>,
  setCurrent: (id: string) => api.post(`/part-models/${id}/set-current`) as Promise<any>,
  upload: (data: any) => api.post('/part-models/upload', data) as Promise<any>,
};
