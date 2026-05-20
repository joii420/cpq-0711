import api from './api';

/**
 * K2: 数据源 resolver 类型列表 + 统一解析端点客户端.
 *
 * 后端 Phase I2 落地 — Resolver 通过 CDI 自动注册, 前端通过本接口动态获取
 * 当前可用 type 列表, 不再硬编码下拉选项.
 */
export interface ResolveRequest {
  type: string;
  config: Record<string, any>;
  driverRow?: Record<string, any>;
}

let _typesCache: string[] | null = null;
let _typesPromise: Promise<string[]> | null = null;

/** 类型 → UI 标签映射 (新加 type 时在这里补一行; 未配置则用 type 自身) */
export const RESOLVER_TYPE_LABEL: Record<string, string> = {
  DATABASE_QUERY: '数据库查询',
  GLOBAL_VARIABLE: '全局变量',
  BNF_PATH: 'BNF 路径',
  HTTP_API: 'HTTP API',
};

/** 进程级缓存, 避免每次打开编辑器都拉一次 */
export const dataSourceResolverService = {
  /** GET /data-sources/types — 返已注册的 type 列表 */
  async listTypes(): Promise<string[]> {
    if (_typesCache) return _typesCache;
    if (_typesPromise) return _typesPromise;
    _typesPromise = api.get('/data-sources/types')
      .then((r: any) => {
        const arr = r?.data?.data || r?.data || [];
        _typesCache = Array.isArray(arr) ? arr.sort() : [];
        return _typesCache;
      })
      .catch(() => {
        _typesCache = ['DATABASE_QUERY', 'GLOBAL_VARIABLE', 'BNF_PATH', 'HTTP_API']; // fallback 默认
        return _typesCache;
      })
      .finally(() => { _typesPromise = null; });
    return _typesPromise;
  },

  /** POST /data-sources/resolve — 统一解析入口 */
  resolve: (req: ResolveRequest) =>
    api.post('/data-sources/resolve', req) as Promise<any>,

  /** 强制清缓存 (新建 resolver 后) */
  evictTypesCache: () => { _typesCache = null; },
};
