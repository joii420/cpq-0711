/**
 * 配置模板 API client.
 *
 * V203 / Phase B1: LIST_FORMULA 字段类型的独立数据源.
 *
 * 资源结构:
 *   config_template
 *     ├ config_category (1:N)
 *         └ config_item (1:N)
 *
 * 状态机: DRAFT / PUBLISHED / ARCHIVED
 *   - DRAFT 不能被组件字段引用
 *   - PUBLISHED 可用
 *   - ARCHIVED 历史 snapshot 保护可继续渲染, 不能新建绑定
 */
import api from './api';

export interface ConfigItem {
  id: string;
  categoryId: string;
  code: string;
  name: string;
  defaultValue?: string;
  sortOrder: number;
  status: 'ACTIVE' | 'INACTIVE';
}

export interface ConfigCategory {
  id: string;
  templateId: string;
  code: string;
  name: string;
  sortOrder: number;
  status: 'ACTIVE' | 'INACTIVE';
  items: ConfigItem[];
}

export interface ConfigTemplate {
  id: string;
  code: string;
  name: string;
  description?: string;
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
  createdAt?: string;
  updatedAt?: string;
  createdBy?: string;
  publishedAt?: string;
  categories: ConfigCategory[];
}

export interface TemplateUpsert {
  code: string;
  name: string;
  description?: string;
}
export interface CategoryUpsert {
  code: string;
  name: string;
  sortOrder?: number;
  status?: string;
}
export interface ItemUpsert {
  code: string;
  name: string;
  defaultValue?: string;
  sortOrder?: number;
  status?: string;
}

export const configTemplateService = {
  list: (status?: string): Promise<{ data: ConfigTemplate[] }> =>
    api.get('/config-templates', { params: status ? { status } : {} }) as Promise<any>,

  getById: (id: string): Promise<{ data: ConfigTemplate }> =>
    api.get(`/config-templates/${id}`) as Promise<any>,

  create: (req: TemplateUpsert): Promise<{ data: ConfigTemplate }> =>
    api.post('/config-templates', req) as Promise<any>,

  update: (id: string, req: TemplateUpsert): Promise<{ data: ConfigTemplate }> =>
    api.put(`/config-templates/${id}`, req) as Promise<any>,

  delete: (id: string): Promise<any> => api.delete(`/config-templates/${id}`) as Promise<any>,

  publish: (id: string): Promise<{ data: ConfigTemplate }> =>
    api.post(`/config-templates/${id}/publish`, {}) as Promise<any>,

  archive: (id: string): Promise<{ data: ConfigTemplate }> =>
    api.post(`/config-templates/${id}/archive`, {}) as Promise<any>,

  // categories
  createCategory: (templateId: string, req: CategoryUpsert): Promise<{ data: ConfigCategory }> =>
    api.post(`/config-templates/${templateId}/categories`, req) as Promise<any>,
  updateCategory: (id: string, req: CategoryUpsert): Promise<{ data: ConfigCategory }> =>
    api.put(`/config-categories/${id}`, req) as Promise<any>,
  deleteCategory: (id: string): Promise<any> =>
    api.delete(`/config-categories/${id}`) as Promise<any>,

  // items
  createItem: (categoryId: string, req: ItemUpsert): Promise<{ data: ConfigItem }> =>
    api.post(`/config-categories/${categoryId}/items`, req) as Promise<any>,
  updateItem: (id: string, req: ItemUpsert): Promise<{ data: ConfigItem }> =>
    api.put(`/config-items/${id}`, req) as Promise<any>,
  deleteItem: (id: string): Promise<any> =>
    api.delete(`/config-items/${id}`) as Promise<any>,
};
