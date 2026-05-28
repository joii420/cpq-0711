import api from './api';

// v0.4 §18A 特征库（方案 B 快照复制）
export const featureLibraryService = {
  // Group
  listGroups: (params: any) => api.get('/feature-library/groups', { params }) as Promise<any>,
  getGroup: (id: number) => api.get(`/feature-library/groups/${id}`) as Promise<any>,
  createGroup: (data: any) => api.post('/feature-library/groups', data) as Promise<any>,
  updateGroup: (id: number, data: any) => api.put(`/feature-library/groups/${id}`, data) as Promise<any>,
  archiveGroup: (id: number) => api.post(`/feature-library/groups/${id}/archive`) as Promise<any>,

  // Field
  listFields: (groupId: number) => api.get(`/feature-library/groups/${groupId}/fields`) as Promise<any>,
  createField: (groupId: number, data: any) => api.post(`/feature-library/groups/${groupId}/fields`, data) as Promise<any>,
  updateField: (fieldId: number, data: any) => api.put(`/feature-library/fields/${fieldId}`, data) as Promise<any>,
  deleteField: (fieldId: number) => api.delete(`/feature-library/fields/${fieldId}`) as Promise<any>,

  // Value
  listValues: (fieldId: number) => api.get(`/feature-library/fields/${fieldId}/values`) as Promise<any>,
  createValue: (fieldId: number, data: any) => api.post(`/feature-library/fields/${fieldId}/values`, data) as Promise<any>,
  updateValue: (valueId: number, data: any) => api.put(`/feature-library/values/${valueId}`, data) as Promise<any>,
  deleteValue: (valueId: number) => api.delete(`/feature-library/values/${valueId}`) as Promise<any>,

  // 引用统计
  templateRefsByGroup: () => api.get('/feature-library/groups/template-refs') as Promise<any>,

  refreshDiff: (templateId: string) => api.get(`/feature-library/refresh-diff/${templateId}`) as Promise<any>,
};
