import api from './api';

/**
 * task-0721 F6：递归 SQL 配置增加 usage 维度（报价侧 QUOTE / 核价侧 COSTING 各自独立管理 + 独立 active）。
 * ⚠️ 契约变更说明（api.md §2）：端点由现网单数 `/costing-bom-tree-config` 改为复数
 * `/costing-bom-tree-configs`，与后端 B2（重命名/扩展现有端点）同步推进。后端 B2 落地前，
 * 本文件按 api.md 契约先行开发；若后端未按此重命名，需与后端对齐后二选一调整（已在交付说明中注明）。
 */
export type BomTreeConfigUsage = 'QUOTE' | 'COSTING';

export interface CostingBomTreeConfig {
  id: string;
  name: string;
  sqlTemplate: string;
  isActive: boolean;
  /** 用途维度：QUOTE=报价侧 / COSTING=核价侧。每个 usage 至多一条 active（api.md §2.3）。 */
  usage: BomTreeConfigUsage;
  createdAt?: string;
  updatedAt?: string;
}

export interface CostingBomTreeConfigPayload {
  name: string;
  sqlTemplate: string;
  usage: BomTreeConfigUsage;
}

export const costingBomTreeConfigService = {
  /** 列出核价树递归 SQL 配置；不传 usage = 返回全部（向后兼容，api.md §2.1） */
  list: (usage?: BomTreeConfigUsage): Promise<{ data: CostingBomTreeConfig[] }> =>
    api.get('/costing-bom-tree-configs', { params: usage ? { usage } : undefined }) as Promise<any>,

  /** 创建配置（后端会 dry-run 校验递归 SQL；usage 必填） */
  create: (payload: CostingBomTreeConfigPayload): Promise<{ data: CostingBomTreeConfig }> =>
    api.post('/costing-bom-tree-configs', payload) as Promise<any>,

  /** 更新配置 */
  update: (id: string, payload: CostingBomTreeConfigPayload): Promise<{ data: CostingBomTreeConfig }> =>
    api.put(`/costing-bom-tree-configs/${id}`, payload) as Promise<any>,

  /** 设为生效（每个 usage 至多一条；只下线同 usage 的其他配置，不影响另一侧，api.md §2.3） */
  activate: (id: string): Promise<{ data: CostingBomTreeConfig }> =>
    api.post(`/costing-bom-tree-configs/${id}/activate`) as Promise<any>,

  /** 删除配置 */
  remove: (id: string): Promise<void> =>
    api.delete(`/costing-bom-tree-configs/${id}`) as Promise<any>,
};
