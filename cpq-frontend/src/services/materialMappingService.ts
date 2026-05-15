import api from './api';

/**
 * Material match Promise-cache — ProductCard 每行触发 1 次 match,N 个产品卡片重复渲染时
 * 会爆 N×k 个 match 请求(常见 200~500)。改成 in-flight Promise cache:
 * 同 (customerId, partNo) 并发/重复调用复用 1 个 Promise → 1 次 HTTP。
 *
 * mutation(create/delete/importExcel) 会清空相关客户的 cache 避免脏数据。
 * 失败 Promise 自动从 cache 移除允许重试。
 */
const matchPromiseCache = new Map<string, Promise<any>>();
const matchCacheKey = (customerId: string, partNo: string) => `${customerId}::${partNo}`;
function evictMatchCache(customerId?: string) {
  if (!customerId) {
    matchPromiseCache.clear();
    return;
  }
  const prefix = `${customerId}::`;
  for (const k of Array.from(matchPromiseCache.keys())) {
    if (k.startsWith(prefix)) matchPromiseCache.delete(k);
  }
}

export const materialMappingService = {
  list: (customerId: string, params?: any) =>
    api.get(`/customers/${customerId}/material-mappings`, { params }) as Promise<any>,
  create: (customerId: string, data: any) => {
    const p = api.post(`/customers/${customerId}/material-mappings`, data) as Promise<any>;
    p.finally(() => evictMatchCache(customerId));
    return p;
  },
  delete: (customerId: string, id: string) => {
    const p = api.delete(`/customers/${customerId}/material-mappings/${id}`) as Promise<any>;
    p.finally(() => evictMatchCache(customerId));
    return p;
  },
  importExcel: (customerId: string, file: File) => {
    const fd = new FormData();
    fd.append('file', file);
    const p = api.post(`/customers/${customerId}/material-mappings/import`, fd, {
      headers: { 'Content-Type': 'multipart/form-data' },
    }) as Promise<any>;
    p.finally(() => evictMatchCache(customerId));
    return p;
  },

  /** 原始 match — 不走缓存。一般场景**应该用 matchCached**。 */
  match: (customerId: string, partNo: string) =>
    api.get(`/customers/${customerId}/material-mappings/match`, { params: { partNo } }) as Promise<any>,

  /**
   * Promise-cached 版本 — 同 (customerId, partNo) 并发/重复调用复用 1 个 Promise → 1 次 HTTP。
   * 报价单 ProductCard 内 useEffect 走此版本,避免 N 个产品卡片各自 1 次 HTTP。
   */
  matchCached: (customerId: string, partNo: string): Promise<any> => {
    if (!customerId || !partNo) {
      return api.get(`/customers/${customerId}/material-mappings/match`, { params: { partNo } }) as Promise<any>;
    }
    const key = matchCacheKey(customerId, partNo);
    const cached = matchPromiseCache.get(key);
    if (cached) return cached;
    const p = (api.get(`/customers/${customerId}/material-mappings/match`, { params: { partNo } }) as Promise<any>)
      .catch((err) => {
        matchPromiseCache.delete(key);
        throw err;
      });
    matchPromiseCache.set(key, p);
    return p;
  },

  evictMatchCache,
};
