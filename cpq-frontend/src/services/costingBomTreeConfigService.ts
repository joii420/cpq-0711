import api from './api';

/**
 * task-0721 F6：递归 SQL 配置增加 usage 维度（各数据集各自独立管理 + 独立 active）。
 * ⚠️ 2026-07-21 更正：端点路径**保持单数** `/api/cpq/costing-bom-tree-config`（后端现网既有路径，
 * 核价配置页依赖，不改名）。此前按 api.md 误写的复数 `costing-bom-tree-configs` 已撤回——
 * 那是 api.md 笔误，非后端真实契约。`usage` 查询参数/请求体字段保留。
 *
 * task-260909 F-1：usage 由 2 值扩到 3 值，与三个数据集一一对齐
 * （`QUOTE` 报价 / `COST_BASIC` 基础核价 / `COST_DETAIL` 详细核价）。
 * 🚫 **前端不再产出 `'COSTING'`**：它在后端只是 `COST_BASIC` 的**只读兼容别名**（`api.md §1.1`），
 *    写入 `usage='COSTING'` 后端直接 400。故本类型里**不含** `'COSTING'` ——
 *    让「新建/编辑发老值」在编译期就不可能，而不是等运行时 400。
 */
export type BomTreeConfigUsage = 'QUOTE' | 'COST_BASIC' | 'COST_DETAIL';

export interface CostingBomTreeConfig {
  id: string;
  name: string;
  sqlTemplate: string;
  isActive: boolean;
  /** 用途维度：QUOTE=报价 / COST_BASIC=基础核价 / COST_DETAIL=详细核价。每个 usage 至多一条 active（api.md §1.1）。 */
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

  /** 创建配置（后端会 dry-run 校验递归 SQL；usage 必填，未传返 400 —— api.md §1.1 取消了原「兜底 COSTING」行为） */
  create: (payload: CostingBomTreeConfigPayload): Promise<{ data: CostingBomTreeConfig }> =>
    api.post('/costing-bom-tree-config', payload) as Promise<any>,

  /** 更新配置 */
  update: (id: string, payload: CostingBomTreeConfigPayload): Promise<{ data: CostingBomTreeConfig }> =>
    api.put(`/costing-bom-tree-config/${id}`, payload) as Promise<any>,

  /** 设为生效（每个 usage 至多一条；只下线同 usage 的其他配置，不影响另外两套） */
  activate: (id: string): Promise<{ data: CostingBomTreeConfig }> =>
    api.post(`/costing-bom-tree-config/${id}/activate`) as Promise<any>,

  /** 删除配置 */
  remove: (id: string): Promise<void> =>
    api.delete(`/costing-bom-tree-config/${id}`) as Promise<any>,
};
