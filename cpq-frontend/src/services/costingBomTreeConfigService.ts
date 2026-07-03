import api from './api';

export interface CostingBomTreeConfig {
  id: string;
  name: string;
  sqlTemplate: string;
  isActive: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface CostingBomTreeConfigPayload {
  name: string;
  sqlTemplate: string;
}

export const costingBomTreeConfigService = {
  /** 列出所有核价树递归 SQL 配置 */
  list: (): Promise<{ data: CostingBomTreeConfig[] }> =>
    api.get('/costing-bom-tree-config') as Promise<any>,

  /** 创建配置（后端会 dry-run 校验递归 SQL） */
  create: (payload: CostingBomTreeConfigPayload): Promise<{ data: CostingBomTreeConfig }> =>
    api.post('/costing-bom-tree-config', payload) as Promise<any>,

  /** 更新配置 */
  update: (id: string, payload: CostingBomTreeConfigPayload): Promise<{ data: CostingBomTreeConfig }> =>
    api.put(`/costing-bom-tree-config/${id}`, payload) as Promise<any>,

  /** 设为生效（全局唯一，单选） */
  activate: (id: string): Promise<{ data: CostingBomTreeConfig }> =>
    api.post(`/costing-bom-tree-config/${id}/activate`) as Promise<any>,

  /** 删除配置 */
  remove: (id: string): Promise<void> =>
    api.delete(`/costing-bom-tree-config/${id}`) as Promise<any>,
};
