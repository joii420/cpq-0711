import axios from 'axios';

const api = axios.create({
  baseURL: '/api/cpq',
  timeout: 30000,
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
}

export function buildApiError(error: any): ApiError {
  const err = new Error(error?.response?.data?.message || 'Network error') as ApiError;
  err.payload = error?.response?.data?.data ?? null;   // 信封.data，与成功侧 response.data 同层级
  err.httpStatus = error?.response?.status;
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
