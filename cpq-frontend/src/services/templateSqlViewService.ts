import api from './api';
import type { SqlViewColumn, DryRunResult } from './componentSqlViewService';

// ── 类型定义 ─────────────────────────────────────────────────────────

export type { SqlViewColumn, DryRunResult };

export interface TemplateSqlView {
  id: string;
  templateId: string;
  sqlViewName: string;
  sqlTemplate: string;
  declaredColumns: SqlViewColumn[] | string;
  requiredVariables: string[];
  /** 仅 LOCAL 有效（隔离设计，不支持跨模板引用） */
  scope: 'LOCAL';
  status: 'ACTIVE' | 'INACTIVE';
  description?: string | null;
  createdAt?: string;
  updatedAt: string;
}

export interface TemplateSqlViewPayload {
  sqlViewName: string;
  sqlTemplate: string;
  scope?: 'LOCAL';
  status?: 'ACTIVE' | 'INACTIVE';
  description?: string;
}

// ── API 客户端 ───────────────────────────────────────────────────────

export const templateSqlViewService = {
  /** 列出模板的所有 SQL 视图 */
  list: (templateId: string): Promise<{ data: TemplateSqlView[] }> =>
    api.get(`/templates/${templateId}/sql-views`) as Promise<any>,

  /** 获取单个 SQL 视图（后端路由含 templateId） */
  get: (templateId: string, id: string): Promise<{ data: TemplateSqlView }> =>
    api.get(`/templates/${templateId}/sql-views/${id}`) as Promise<any>,

  /** 创建 SQL 视图 */
  create: (
    templateId: string,
    payload: TemplateSqlViewPayload,
  ): Promise<{ data: TemplateSqlView }> =>
    api.post(`/templates/${templateId}/sql-views`, payload) as Promise<any>,

  /** 更新 SQL 视图（后端路由含 templateId） */
  update: (
    templateId: string,
    id: string,
    payload: TemplateSqlViewPayload,
  ): Promise<{ data: TemplateSqlView }> =>
    api.put(`/templates/${templateId}/sql-views/${id}`, payload) as Promise<any>,

  /** 删除 SQL 视图（后端路由含 templateId） */
  delete: (templateId: string, id: string): Promise<void> =>
    api.delete(`/templates/${templateId}/sql-views/${id}`) as Promise<any>,

  /** Dry-run 校验：提取列签名 + 变量、检查禁用词（后端路由含 templateId） */
  dryRun: (payload: { templateId: string; sqlTemplate: string }): Promise<{ data: DryRunResult }> =>
    api.post(`/templates/${payload.templateId}/sql-views/dry-run`, {
      sqlTemplate: payload.sqlTemplate,
    }) as Promise<any>,

  /** 老路径盘点（运维工具） */
  listLegacyPaths: (): Promise<{ data: any[] }> =>
    api.get('/templates/legacy-paths') as Promise<any>,
};
