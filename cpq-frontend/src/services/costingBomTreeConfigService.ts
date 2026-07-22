import api from './api';

/**
 * task-0721 F6：递归 SQL 配置增加 usage 维度（报价侧 QUOTE / 核价侧 COSTING 各自独立管理 + 独立 active）。
 * ⚠️ 2026-07-21 更正：端点路径**保持单数** `/api/cpq/costing-bom-tree-config`（后端现网既有路径，
 * 核价配置页依赖，不改名）。此前按 api.md 误写的复数 `costing-bom-tree-configs` 已撤回——
 * 那是 api.md 笔误，非后端真实契约。`usage` 查询参数/请求体字段保留。
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
  /** 列出核价树递归 SQL 配置；不传 usage = 返回全部（向后兼容） */
  list: (usage?: BomTreeConfigUsage): Promise<{ data: CostingBomTreeConfig[] }> =>
    api.get('/costing-bom-tree-config', { params: usage ? { usage } : undefined }) as Promise<any>,

  /** 创建配置（后端会 dry-run 校验递归 SQL；usage 必填） */
  create: (payload: CostingBomTreeConfigPayload): Promise<{ data: CostingBomTreeConfig }> =>
    api.post('/costing-bom-tree-config', payload) as Promise<any>,

  /** 更新配置 */
  update: (id: string, payload: CostingBomTreeConfigPayload): Promise<{ data: CostingBomTreeConfig }> =>
    api.put(`/costing-bom-tree-config/${id}`, payload) as Promise<any>,

  /** 设为生效（每个 usage 至多一条；只下线同 usage 的其他配置，不影响另一侧） */
  activate: (id: string): Promise<{ data: CostingBomTreeConfig }> =>
    api.post(`/costing-bom-tree-config/${id}/activate`) as Promise<any>,

  /** 删除配置 */
  remove: (id: string): Promise<void> =>
    api.delete(`/costing-bom-tree-config/${id}`) as Promise<any>,
};
