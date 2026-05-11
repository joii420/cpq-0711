import api from './api';

/** V149 变量标签 — 视图列的中文友好命名 SSOT */
export interface VariableLabel {
  id?: string;
  variablePath: string;       // 'v_c_summary_agg.packaging_fee'
  displayName: string;        // '包装材料费源'
  category: string;           // '成本汇总' / '费用比率' / ...
  dataType?: string | null;   // 'DECIMAL' / 'PERCENT' / 'STRING' / null
  unit?: string | null;       // '¥' / '%' / 'g' / null
  description?: string | null;
  exampleValue?: string | null;
  sourceType?: string;        // 默认 'VIEW_COLUMN'
  status?: string;            // 默认 'ACTIVE'
}

export type GroupedLabels = Record<string, VariableLabel[]>;

export interface QuickNameRequest {
  variablePath: string;
  displayName: string;
  category: string;
  dataType?: string | null;
  unit?: string | null;
  description?: string | null;
}

// in-memory cache — 一次会话内复用, 切换模板时调 clearCache
let listCache: VariableLabel[] | null = null;
let groupedCache: GroupedLabels | null = null;

export const variableLabelService = {
  /** 全部 ACTIVE 行 (扁平). 失败时返回空数组, 调用方退化到 raw path. */
  list: async (): Promise<VariableLabel[]> => {
    if (listCache) return listCache;
    try {
      const res = (await api.get('/variable-labels')) as unknown as { data: VariableLabel[] };
      listCache = Array.isArray(res?.data) ? res.data : [];
      return listCache;
    } catch {
      return [];
    }
  },

  /** 按 category 分组. 与 list() 共用底层数据, 但分别缓存避免转换. */
  grouped: async (): Promise<GroupedLabels> => {
    if (groupedCache) return groupedCache;
    try {
      const res = (await api.get('/variable-labels/grouped')) as unknown as { data: GroupedLabels };
      groupedCache = res?.data ?? {};
      return groupedCache;
    } catch {
      return {};
    }
  },

  /** 按 path 精确查. 未注册时返回 null (前端用于"退化到 raw path"判定). */
  byPath: async (path: string): Promise<VariableLabel | null> => {
    if (!path) return null;
    try {
      const res = (await api.get('/variable-labels/by-path', { params: { path } })) as unknown as { data: VariableLabel };
      return res?.data ?? null;
    } catch {
      return null;
    }
  },

  /** 渐进式起名: 用户在编辑器里点"未注册字段"时弹窗补名, 入库 + 清缓存. */
  upsert: async (req: QuickNameRequest): Promise<VariableLabel | null> => {
    try {
      const res = (await api.post('/variable-labels', req)) as unknown as { data: VariableLabel };
      // 改动后让 list/grouped 重新拉
      listCache = null;
      groupedCache = null;
      return res?.data ?? null;
    } catch {
      return null;
    }
  },

  /** V149 Phase 2: 按 hf_part_no 样本求当前值 (路径必须已注册才能查). */
  evalAt: async (path: string, hfPartNo?: string): Promise<unknown> => {
    if (!path) return null;
    try {
      const res = (await api.post('/variable-labels/eval', { path, hfPartNo })) as unknown as { data: { value: unknown } };
      return res?.data?.value ?? null;
    } catch {
      return null;
    }
  },

  clearCache: () => {
    listCache = null;
    groupedCache = null;
  },
};

export default variableLabelService;
