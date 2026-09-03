import axios from 'axios';

const api = axios.create({
  baseURL: '/api/cpq',
  // repair-260829 F-1（AC-15）：30s → 60s。大单量（如 1845 行）保存草稿端到端实测 21~35s，
  // 原 30s 客户端超时与后端事务墙不匹配，会造成「后端已存成功，前端却报 net::ERR_ABORTED」的
  // 不稳定假失败。上调为 60s 与后端事务预算对齐。见 dev-docs/task-260721-.../repair-260829-.../问题说明.md AC-15。
  timeout: 60000,
  withCredentials: true,
  headers: {
    'Content-Type': 'application/json',
  },
});

// (debug 2026-05-15) 报价详情死循环排查 — 把 /formulas/evaluate 单点调用的发起方栈打印出来
api.interceptors.request.use((config) => {
  const url = config.url || '';
  if (url.includes('/formulas/evaluate') && !url.includes('batch-evaluate')) {
    // eslint-disable-next-line no-console
    console.warn('[single-evaluate-trace]', { url, data: config.data, stack: new Error().stack });
  }
  return config;
});

export interface ApiError extends Error {
  payload: unknown;
  httpStatus?: number;
  /**
   * 错误信封的业务错误码（如 `RECIPE_HAS_NO_CONFIG` / `MATERIAL_RATIO_SUM_INVALID`）。
   * task-260902 · F-9 新增：确认页要按错误码给不同的指路按钮，只有 message 做不到
   * （文案会被后端改，错误码不会）。取自 `response.data.code`。
   */
  code?: string;
  /**
   * 错误信封的 `detail` 附加载荷（**与 `payload` 不同层**：`payload` 取的是 `data.data`）。
   * 例：`MATERIAL_RATIO_SUM_INVALID` 的 `{ actualSum, expected }`（api.md §1.2）。
   */
  detail?: unknown;
}

export function buildApiError(error: any): ApiError {
  const err = new Error(error?.response?.data?.message || 'Network error') as ApiError;
  err.payload = error?.response?.data?.data ?? null;   // 信封.data，与成功侧 response.data 同层级
  err.httpStatus = error?.response?.status;
  err.code = error?.response?.data?.code ?? undefined;
  err.detail = error?.response?.data?.detail ?? undefined;
  return err;
}

api.interceptors.response.use(
  (response) => {
    return response.data;
  },
  (error) => {
    const url = error.config?.url || '';
    const isAuthEndpoint = url.includes('/auth/login') || url.includes('/auth/forgot-password') || url.includes('/auth/reset-password');
    if (error.response?.status === 401 && !isAuthEndpoint) {
      window.location.href = '/login';
    }
    return Promise.reject(buildApiError(error));
  }
);

export default api;
