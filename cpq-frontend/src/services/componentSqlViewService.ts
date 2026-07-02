import api from './api';

// ── 类型定义 ─────────────────────────────────────────────────────────

export interface SqlViewColumn {
  name: string;
  dataType: string;
  nullable: boolean;
}

export interface ComponentSqlView {
  id: string;
  componentId: string;
  /** 组件 code（业务标识符），跨组件 BNF 引用 $$<componentCode>.<sql_view_name> 用此字段。 */
  componentCode?: string;
  sqlViewName: string;
  sqlTemplate: string;
  declaredColumns: SqlViewColumn[];
  requiredVariables: string[];
  scope: 'COMPONENT' | 'GLOBAL';
  status: 'ACTIVE' | 'INACTIVE';
  description?: string;
  updatedAt: string;
}

export interface ComponentSqlViewPayload {
  sqlViewName: string;
  sqlTemplate: string;
  scope: 'COMPONENT' | 'GLOBAL';
  status?: 'ACTIVE' | 'INACTIVE';
  description?: string;
}

export interface DryRunResult {
  success: boolean;
  declaredColumns?: SqlViewColumn[];
  requiredVariables?: string[];
  error?: string;
}

// ── API 客户端 ───────────────────────────────────────────────────────

export const componentSqlViewService = {
  /** 列出组件的所有 SQL 视图 */
  list: (componentId: string): Promise<{ data: ComponentSqlView[] }> =>
    api.get(`/components/${componentId}/sql-views`) as Promise<any>,

  /** 创建 SQL 视图 */
  create: (componentId: string, payload: ComponentSqlViewPayload): Promise<{ data: ComponentSqlView }> =>
    api.post(`/components/${componentId}/sql-views`, payload) as Promise<any>,

  /** 更新 SQL 视图 */
  update: (
    componentId: string,
    viewId: string,
    payload: ComponentSqlViewPayload,
  ): Promise<{ data: ComponentSqlView }> =>
    api.put(`/components/${componentId}/sql-views/${viewId}`, payload) as Promise<any>,

  /** 删除 SQL 视图 */
  delete: (componentId: string, viewId: string): Promise<void> =>
    api.delete(`/components/${componentId}/sql-views/${viewId}`) as Promise<any>,

  /** Dry-run 校验：提取列签名 + 变量、检查禁用词 */
  dryRun: (componentId: string, sqlTemplate: string): Promise<{ data: DryRunResult }> =>
    api.post(`/components/${componentId}/sql-views/dry-run`, { sqlTemplate }) as Promise<any>,

  /** 列出全局（scope=GLOBAL）的 SQL 视图（跨组件可引用） */
  listGlobal: (): Promise<{ data: ComponentSqlView[] }> =>
    api.get('/sql-views/global') as Promise<any>,

  /** 设置/清空组件驱动视图。sqlViewName=null 表示取消驱动。返回更新后的组件。 */
  setDriver: (
    componentId: string,
    sqlViewName: string | null,
  ): Promise<{ data: { id: string; dataDriverPath?: string } }> =>
    api.put(`/components/${componentId}/driver-view`, { sqlViewName }) as Promise<any>,
};
