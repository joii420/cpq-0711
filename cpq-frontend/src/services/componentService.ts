import api from './api';

// ── batch-expand 类型定义 ─────────────────────────────────────────
export interface BatchExpandTask {
  componentId: string;
  customerId?: string | null;
  partNo?: string | null;
  /** 料号版本号（可选）。传入后后端注入 AND part_version=N 过滤，避免历史版本叠加重复。 */
  partVersion?: number | null;
}

export interface BatchExpandResultItem {
  /** 与后端 cache key 一致: componentId:customerId:partNo，null 填 "_" */
  key: string;
  status: 'OK' | 'ERROR';
  data?: {
    rowCount: number;
    driverPath?: string;
    rows: Array<{ driverRow: Record<string, any>; basicDataValues: Record<string, any> }>;
  } | null;
  error?: string | null;
}

/**
 * 构造与后端 cacheKey 一致的字符串，用于匹配 batch 结果。
 * 后端规则: componentId:customerId:partNo:partVersion，null/undefined 填 "_"
 */
export function buildBatchKey(
  componentId: string,
  customerId?: string | null,
  partNo?: string | null,
  partVersion?: number | null,
): string {
  return `${componentId}:${customerId ?? '_'}:${partNo ?? '_'}:${partVersion ?? '_'}`;
}

/**
 * POST /api/cpq/components/batch-expand
 * 自动按 100 拆分(后端上限)，顺序请求后合并。
 * status=ERROR 的条目仍写入结果（data=null），避免调用方反复重试。
 */
export async function batchExpandDriver(tasks: BatchExpandTask[]): Promise<BatchExpandResultItem[]> {
  if (tasks.length === 0) return [];
  const CHUNK = 100;
  const chunks: BatchExpandTask[][] = [];
  for (let i = 0; i < tasks.length; i += CHUNK) {
    chunks.push(tasks.slice(i, i + CHUNK));
  }
  const allResults: BatchExpandResultItem[] = [];
  for (const chunk of chunks) {
    const resp: any = await api.post('/components/batch-expand', { tasks: chunk });
    // 外层 ApiResponse 包裹: resp.data.results 或 resp.results
    const results: BatchExpandResultItem[] = resp?.data?.results ?? resp?.results ?? [];
    allResults.push(...results);
  }
  return allResults;
}
// ──────────────────────────────────────────────────────────────────

export const componentService = {
  listDirectories: (params?: { keyword?: string }) => api.get('/component-directories', { params }) as Promise<any>,
  createDirectory: (data: any) => api.post('/component-directories', data) as Promise<any>,
  updateDirectory: (id: string, data: any) => api.put(`/component-directories/${id}`, data) as Promise<any>,
  deleteDirectory: (id: string) => api.delete(`/component-directories/${id}`) as Promise<any>,
  list: (params: any) => api.get('/components', { params }) as Promise<any>,
  getById: (id: string) => api.get(`/components/${id}`) as Promise<any>,
  create: (data: any) => api.post('/components', data) as Promise<any>,
  update: (id: string, data: any) => api.put(`/components/${id}`, data) as Promise<any>,
  delete: (id: string) => api.delete(`/components/${id}`) as Promise<any>,
  toggleStatus: (id: string) => api.patch(`/components/${id}/toggle-status`) as Promise<any>,
  /**
   * Y1.5: 按组件 dataDriverPath 展开 N 行（单个，兜底场景保留）。
   * 无 dataDriverPath → rowCount=0(前端按单行兜底)
   */
  expandDriver: (id: string, params: { customerId?: string; partNo?: string }) =>
    api.post(`/components/${id}/expand-driver`, params) as Promise<any>,
};
