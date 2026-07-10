import api from './api';

/**
 * 元素主表（Element）服务层 — task-0709 / BL-0040
 * 对接后端 /api/cpq/elements
 * 主键语义：element_no（元素编号）为不可改业务主键；element_code（符号）被引用即锁。
 */

export interface ElementItem {
  id: string;
  elementNo: string;        // 业务主键(不可改)
  elementCode: string;      // 符号(被引用即锁)
  elementName: string;      // 中文
  status: 'ACTIVE' | 'INACTIVE';
  referencedCount: number;  // 被引用材质数
  codeLocked: boolean;      // true → 禁用符号输入
  createdAt?: string;
  updatedAt?: string;
}

export interface ElementUpsertRequest {
  elementNo: string;
  elementCode: string;
  elementName: string;
  /**
   * 状态：仅编辑(update)时下发（api.md PUT「status 随时可改」；抽屉是唯一可重新启用的入口）。
   * 新建不传，后端默认 ACTIVE。
   */
  status?: 'ACTIVE' | 'INACTIVE';
}

export const elementService = {
  async list(keyword?: string): Promise<ElementItem[]> {
    const res = await api.get('/elements', { params: keyword ? { keyword } : undefined });
    return (res as unknown as ElementItem[]) ?? [];
  },
  async create(req: ElementUpsertRequest): Promise<ElementItem> {
    return (await api.post('/elements', req)) as unknown as ElementItem;
  },
  async update(elementNo: string, req: ElementUpsertRequest): Promise<ElementItem> {
    return (await api.put(`/elements/${encodeURIComponent(elementNo)}`, req)) as unknown as ElementItem;
  },
  async deleteSoft(elementNo: string): Promise<void> {
    await api.delete(`/elements/${encodeURIComponent(elementNo)}`);
  },
};
