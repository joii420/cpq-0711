import api from './api';

/**
 * 选配参数候选（`GET /api/cpq/sel-param-types/{code}/candidates`）。
 *
 * 🚨 **响应包装格式不统一**（task-260902 · api.md §3，2026-09-02 实调 8081 确认）：
 *   - `/material-recipes`、`/material-recipes/{id}/configs` → **裸数组**，`res` 本身就是数组
 *   - `/sel-param-types/**`                                 → **信封** `{code,message,data:[…]}`，要取 `res.data`
 *   同一个后端两种格式，🚫 不要假设统一。本模块负责把信封解开，调用方只面对数组。
 *
 * 数据源：`process_master`（工序主数据，业务在「主数据维护 → 工序」页自行维护，不随迁移交付）。
 */
export interface SelParamCandidate {
  /** PROCESS 时 = `process_master.process_no`，原样进 `PartRequest.processNos`。 */
  key: string;
  label: string;
  /**
   * 🚧 **契约缺口（已报主线）**：`原型图/3-…html` 状态 H 的工序选择器画了「分类」「加工方式」两列，
   * 但 `api.md §3` 把本端点列为「复用、不改」，实际只返回 `{key,label}`。
   * 这两个字段按**可选**声明：后端补上就显示，没有就渲染「—」（不隐藏列 —— §1.2 禁止隐藏能力）。
   */
  category?: string | null;
  processType?: string | null;
}

export const selParamCandidateService = {
  async list(paramTypeCode: string): Promise<SelParamCandidate[]> {
    const res: any = await api.get(`/sel-param-types/${paramTypeCode}/candidates`);
    const payload = res && typeof res === 'object' && 'data' in res ? res.data : res;
    return Array.isArray(payload) ? (payload as SelParamCandidate[]) : [];
  },
};
