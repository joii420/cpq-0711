import api from './api';

/**
 * 模块级 Template Promise-cache — 报价单页面 N 个产品卡片各自调 `getById(templateId)` 会爆 N 个
 * 重复请求(常见 N=50~200)。这里把 in-flight Promise 缓存到模块作用域,N 个并发调用复用 1 个 Promise → 1 次 HTTP。
 *
 * 设计要点:
 *  - 存 Promise 不是结果,这样**并发**场景第一个调用还没 resolve 时第二个调用就能复用同一 in-flight Promise
 *  - 失败 Promise 自动从 cache 移除,允许下次重试(避免错误固化)
 *  - mutation 方法(update/delete/publish/archive/createNewDraft) 调用后立即 evict 对应 id,避免读到脏数据
 *  - 页面刷新自动清空(因为这是模块级 in-memory cache)
 */
const templatePromiseCache = new Map<string, Promise<any>>();

function evictTemplateCache(id?: string) {
  if (id) templatePromiseCache.delete(id);
  else templatePromiseCache.clear();
}

export const templateService = {
  list: (params?: any) => api.get('/templates', { params }) as Promise<any>,

  /** 原始 getById — 不走缓存。除非有特殊需求(强制刷新),否则**应该用 getByIdCached**。 */
  getById: (id: string) => api.get(`/templates/${id}`) as Promise<any>,

  /**
   * Promise-cached 版本 — 同 id 并发/重复调用复用 1 个 Promise → 1 次 HTTP。
   * 适用所有"按 id 拉模板元数据"场景(报价单产品卡片、Excel 视图、enrich 等)。
   */
  getByIdCached: (id: string): Promise<any> => {
    if (!id) return api.get(`/templates/${id}`) as Promise<any>;
    const cached = templatePromiseCache.get(id);
    if (cached) return cached;
    const p = (api.get(`/templates/${id}`) as Promise<any>).catch((err) => {
      templatePromiseCache.delete(id);
      throw err;
    });
    templatePromiseCache.set(id, p);
    return p;
  },

  /** 显式清缓存(测试或特殊刷新场景用)。 */
  evictByIdCache: evictTemplateCache,

  create: (data: any) => api.post('/templates', data) as Promise<any>,
  update: (id: string, data: any) => {
    const p = api.put(`/templates/${id}`, data) as Promise<any>;
    p.finally(() => evictTemplateCache(id));
    return p;
  },
  delete: (id: string) => {
    const p = api.delete(`/templates/${id}`) as Promise<any>;
    p.finally(() => evictTemplateCache(id));
    return p;
  },
  publish: (id: string, data?: any) => {
    const p = api.post(`/templates/${id}/publish`, data || {}) as Promise<any>;
    p.finally(() => evictTemplateCache(id));
    return p;
  },
  archive: (id: string, force = false) => {
    const p = api.post(`/templates/${id}/archive?force=${force}`, {}) as Promise<any>;
    p.finally(() => evictTemplateCache(id));
    return p;
  },
  createNewDraft: (id: string) => {
    const p = api.post(`/templates/${id}/new-draft`, {}) as Promise<any>;
    p.finally(() => evictTemplateCache(id));
    return p;
  },
  getVersionHistory: (seriesId: string) => api.get(`/templates/series/${seriesId}/versions`) as Promise<any>,
  // components
  listComponents: (templateId: string) => api.get(`/templates/${templateId}/components`) as Promise<any>,
  addComponent: (templateId: string, data: any) => api.post(`/templates/${templateId}/components`, data) as Promise<any>,
  removeComponent: (templateId: string, tcId: string) => api.delete(`/templates/${templateId}/components/${tcId}`) as Promise<any>,
  reorderComponents: (templateId: string, ids: string[]) => api.put(`/templates/${templateId}/components/reorder`, { ids }) as Promise<any>,
  updatePresetRows: (templateId: string, tcId: string, presetRows: any[]) =>
    api.patch(`/templates/${templateId}/components/${tcId}/preset-rows`, { presetRows }) as Promise<any>,
  updateFormulaAssignments: (templateId: string, tcId: string, formulaAssignments: Record<string, string>) =>
    api.patch(`/templates/${templateId}/components/${tcId}/formula-assignments`, { formulaAssignments }) as Promise<any>,
  /**
   * V204: 编辑模板组件 override.
   *   - 仅传 fieldsOverride: 只改字段集 (null = 清空走 component 默认)
   *   - 仅传 dataDriverPathOverride: 只改 driver
   *   - 同时传: 两者一起改
   *   - 键缺省: 不动该字段
   * 仅 DRAFT 模板可改, PUBLISHED 后端拒绝 400.
   */
  updateOverrides: (
    templateId: string,
    tcId: string,
    body: { fieldsOverride?: any[] | string | null; dataDriverPathOverride?: string | null }
  ) => api.patch(`/templates/${templateId}/components/${tcId}/overrides`, body) as Promise<any>,
  // Excel view config
  getExcelViewConfig: (id: string) => api.get(`/templates/${id}/excel-view-config`) as Promise<any>,
  // 后端解析后的有效列（v2 引用配置 excel_component_id 也能拿到 A/B/C 解析列）；供 saveDraft buildExcelSnapshot 取列。
  getEffectiveExcelColumns: (id: string) => api.get(`/templates/${id}/excel-view-config/effective-columns`) as Promise<any>,
  updateExcelViewConfig: (id: string, config: any) => api.put(`/templates/${id}/excel-view-config`, config) as Promise<any>,
  parseHeader: (templateId: string, file: File, sheetIndex: number, headerRowIndex: number) => {
    const fd = new FormData();
    fd.append('file', file);
    fd.append('sheetIndex', String(sheetIndex));
    fd.append('headerRowIndex', String(headerRowIndex));
    return api.post(`/templates/${templateId}/excel-view-config/parse-header`, fd, { headers: { 'Content-Type': 'multipart/form-data' } }) as Promise<any>;
  },
};
