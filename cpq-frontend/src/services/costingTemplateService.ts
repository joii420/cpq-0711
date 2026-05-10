import api from './api';

export interface CostingTemplateColumn {
  col_key: string;
  title: string;
  source_type: 'VARIABLE' | 'FORMULA';
  variable_path?: string;
  formula?: string;
  comparison_tag?: string;
  /** V86：隐藏列。仍参与 FORMULA 取值链路，但不在「核价单 Excel 视图」/「报价单 Excel 视图」展示。 */
  hidden?: boolean;
}

export interface CostingTemplate {
  id: string;
  seriesId: string;
  name: string;
  isDefault: boolean;
  version: string;
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
  description?: string;
  columns: string;              // JSON
  referencedVariables: string;  // JSON
  // V73：关联到「模板配置」中的具体模板（template.id），V74 起本字段是默认 Excel 模板的唯一性维度
  linkedTemplateId?: string;
  linkedTemplateName?: string;
  linkedTemplateKind?: 'QUOTATION' | 'COSTING';
  linkedTemplateVersion?: string;
  createdBy?: string;
  publishedAt?: string;
  createdAt?: string;
  updatedAt?: string;
}

export const costingTemplateService = {
  // V74：移除 categoryId 入参 —— Excel 模板按 linkedTemplateId 调用
  list: (params?: { status?: string; linkedTemplateId?: string }) =>
    api.get('/costing-templates', { params }) as Promise<{ data: CostingTemplate[] }>,
  getById: (id: string) =>
    api.get(`/costing-templates/${id}`) as Promise<{ data: CostingTemplate }>,
  create: (data: any) =>
    api.post('/costing-templates', data) as Promise<{ data: CostingTemplate }>,
  update: (id: string, data: any) =>
    api.put(`/costing-templates/${id}`, data) as Promise<{ data: CostingTemplate }>,
  delete: (id: string) =>
    api.delete(`/costing-templates/${id}`) as Promise<{ data: void }>,
  publish: (id: string) =>
    api.post(`/costing-templates/${id}/publish`) as Promise<{ data: CostingTemplate }>,
  archive: (id: string) =>
    api.post(`/costing-templates/${id}/archive`) as Promise<{ data: CostingTemplate }>,
  // 已归档 / 已发布 → 派生新草稿（同 series，复制 columns / linked_template / description）
  createNewDraft: (id: string) =>
    api.post(`/costing-templates/${id}/new-draft`) as Promise<{ data: CostingTemplate }>,
  // V73：设置/清除关联模板（templateId 为 undefined/null 表示清除）
  setLinkedTemplate: (id: string, templateId?: string | null) =>
    api.put(`/costing-templates/${id}/linked-template`,
      undefined,
      { params: templateId ? { templateId } : {} }
    ) as Promise<{ data: CostingTemplate }>,
};
