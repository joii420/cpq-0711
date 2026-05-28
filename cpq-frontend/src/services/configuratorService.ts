import api from './api';

// v0.4 3D 选配 — 模板 + 实例
export const configuratorTemplateService = {
  list: (params: any) => api.get('/configurator-templates', { params }) as Promise<any>,
  getById: (id: string) => api.get(`/configurator-templates/${id}`) as Promise<any>,
  create: (data: any) => api.post('/configurator-templates', data) as Promise<any>,
  update: (id: string, data: any) => api.put(`/configurator-templates/${id}`, data) as Promise<any>,
  publish: (id: string) => api.post(`/configurator-templates/${id}/publish`) as Promise<any>,
  archive: (id: string) => api.post(`/configurator-templates/${id}/archive`) as Promise<any>,

  listOptions: (templateId: string) => api.get(`/configurator-templates/${templateId}/options`) as Promise<any>,
  addOption: (templateId: string, data: any) => api.post(`/configurator-templates/${templateId}/options`, data) as Promise<any>,
  updateOption: (optionId: string, patch: any) => api.put(`/configurator-templates/options/${optionId}`, patch) as Promise<any>,
  deleteOption: (optionId: string) => api.delete(`/configurator-templates/options/${optionId}`) as Promise<any>,
  listValues: (optionId: string) => api.get(`/configurator-templates/options/${optionId}/values`) as Promise<any>,
  addValue: (optionId: string, data: any) => api.post(`/configurator-templates/options/${optionId}/values`, data) as Promise<any>,
  updateValue: (valueId: string, patch: any) => api.put(`/configurator-templates/values/${valueId}`, patch) as Promise<any>,
  deleteValue: (valueId: string) => api.delete(`/configurator-templates/values/${valueId}`) as Promise<any>,

  // 3D 规则
  list3DRules: (valueId: string) => api.get(`/configurator-templates/values/${valueId}/3d-rules`) as Promise<any>,
  add3DRule: (valueId: string, data: any) => api.post(`/configurator-templates/values/${valueId}/3d-rules`, data) as Promise<any>,
  update3DRule: (ruleId: string, patch: any) => api.put(`/configurator-templates/3d-rules/${ruleId}`, patch) as Promise<any>,
  delete3DRule: (ruleId: string) => api.delete(`/configurator-templates/3d-rules/${ruleId}`) as Promise<any>,

  // 业务实体引用（V245 §18A 收敛 mat_feature_reference 替代）
  listRefs: (valueId: string) => api.get(`/configurator-templates/values/${valueId}/refs`) as Promise<any>,
  addRef: (valueId: string, data: any) => api.post(`/configurator-templates/values/${valueId}/refs`, data) as Promise<any>,
  updateRef: (refId: string, patch: any) => api.put(`/configurator-templates/refs/${refId}`, patch) as Promise<any>,
  deleteRef: (refId: string) => api.delete(`/configurator-templates/refs/${refId}`) as Promise<any>,

  // 版本管理（V246 §13）
  listVersions: (templateId: string) => api.get(`/configurator-templates/${templateId}/versions`) as Promise<any>,
  createSnapshot: (templateId: string, label?: string, changeSummary?: string) =>
    api.post(`/configurator-templates/${templateId}/versions/snapshot`, { label, changeSummary }) as Promise<any>,
  diffVersions: (v1: string, v2: string) =>
    api.get(`/configurator-templates/versions/diff?v1=${v1}&v2=${v2}`) as Promise<any>,
  rollbackVersion: (templateId: string, versionId: string) =>
    api.post(`/configurator-templates/${templateId}/versions/${versionId}/rollback`) as Promise<any>,

  // 后续切片
  importFeatures: (templateId: string, fieldIds: number[]) =>
    api.post(`/configurator-templates/${templateId}/import-features`, { feature_field_ids: fieldIds }) as Promise<any>,
  setBaseModel: (templateId: string, modelId: string, version: number) =>
    api.post(`/configurator-templates/${templateId}/base-model`, { model_id: modelId, version }) as Promise<any>,
};

export const configuratorInstanceService = {
  list: (params: any) => api.get('/configurator/instances', { params }) as Promise<any>,
  getById: (id: string) => api.get(`/configurator/instances/${id}`) as Promise<any>,
  create: (data: any) => api.post('/configurator/instances', data) as Promise<any>,
  update: (id: string, data: any) => api.put(`/configurator/instances/${id}`, data) as Promise<any>,
  delete: (id: string) => api.delete(`/configurator/instances/${id}`) as Promise<any>,

  // 切片 3
  evaluateByTemplate: (templateId: string, selectedValues: any) =>
    api.post(`/configurator/instances/evaluate-by-template/${templateId}`, { selectedValues }) as Promise<any>,
  linkAction: (id: string, action: 'NEW_QUOTATION' | 'LINK_EXISTING', quotationId?: string) =>
    api.post(`/configurator/instances/${id}/link-action`, { action, quotation_id: quotationId }) as Promise<any>,
  unlink: (id: string) => api.post(`/configurator/instances/${id}/unlink`) as Promise<any>,
};

// §17 分享链接
export const configuratorShareService = {
  list: (params: any) => api.get('/configurator/shares', { params }) as Promise<any>,
  getById: (id: string) => api.get(`/configurator/shares/${id}`) as Promise<any>,
  stats: () => api.get('/configurator/shares/stats') as Promise<any>,
  accessLog: (id: string) => api.get(`/configurator/shares/${id}/access-log`) as Promise<any>,
  create: (instanceId: string, payload: { email?: string; share_type?: string; days?: number; can_modify?: boolean }) =>
    api.post('/configurator/shares', { instance_id: instanceId, ...payload }) as Promise<any>,
  getByToken: (token: string) => api.get(`/configurator/shares/by-token/${token}`) as Promise<any>,
  extend: (id: string, days: number) =>
    api.post(`/configurator/shares/${id}/extend`, { days }) as Promise<any>,
  revoke: (id: string, reason: string) =>
    api.post(`/configurator/shares/${id}/revoke`, { reason }) as Promise<any>,
};
