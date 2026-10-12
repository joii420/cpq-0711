import api from './api';
import { downloadExport, exportFileName } from '../utils/exportDownload';

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
  /**
   * 最后修改时间（task-0722 · api.md §4.2 新增）
   * = MAX(element.updated_at, 该元素所有价格记录的 updated_at)，查询时取较大值，不反写 element.updated_at。
   * 列表默认排序：启用优先 → 本字段倒序。
   */
  lastModifiedAt?: string;
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

export interface ElementImportReport {
  totalRows: number;
  createdCount: number;
  updatedNameCount: number;
  unchangedCount: number;
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

  /** POST /elements/import — 原子导入元素主数据；元素编号由后端发号。 */
  async importLibrary(file: File): Promise<ElementImportReport> {
    const fd = new FormData();
    fd.append('file', file);
    return (await api.post('/elements/import', fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })) as unknown as ElementImportReport;
  },

  /** GET /elements/import/template — 下载仅含符号/中文名两列的模板。 */
  async downloadImportTemplate(): Promise<Blob> {
    const data: unknown = await api.get('/elements/import/template', { responseType: 'blob' });
    return data instanceof Blob
      ? data
      : new Blob([data as BlobPart], {
        type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      });
  },

  /** GET /elements/export — 导出元素编号、符号、中文名、状态。 */
  async exportLibrary(): Promise<void> {
    await downloadExport('/elements/export', undefined, exportFileName('元素库'));
  },
};
