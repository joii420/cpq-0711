import api from './api';

export interface GlobalVariableDefinition {
  code: string;
  name: string;
  varType: 'LOOKUP_TABLE' | 'SCALAR';
  sourceView: string;
  keyColumns: string[];
  valueColumn: string;
  labelTemplate?: string;
  unit?: string;
  description?: string;
  sortOrder?: number;
  isActive?: boolean;
  /** V190: KV_TABLE = 查 global_variable_value 单表 (轻量配置); COSTING_VIEW = 查源视图 (核价 3 张) */
  valueSourceType?: 'KV_TABLE' | 'COSTING_VIEW';
  /** V190: PUBLIC = 全局变量页可见可编辑; COSTING_INTERNAL = Picker 可选但 UI 列表隐藏 */
  visibility?: 'PUBLIC' | 'COSTING_INTERNAL';
}

export interface GlobalVariableKeyOption {
  key_values: Record<string, any>;
  value: number | string | null;
  label: string;
}

export interface ChangeLogEntry {
  id: string;
  varCode: string;
  keyId: string;
  action: 'INSERT' | 'UPDATE' | 'DELETE';
  oldValue: number | null;
  newValue: number | null;
  changedBy: string | null;
  changedByName: string | null;
  note: string | null;
  changedAt: string;
}

export const globalVariableService = {
  list: () => api.get('/global-variables') as Promise<any>,
  /** G1: 新建变量定义 (PRICING_MANAGER+); 形态强制 KV_TABLE + PUBLIC */
  create: (body: Partial<GlobalVariableDefinition>) =>
    api.post('/global-variables', body) as Promise<any>,
  /** G1: 删除变量定义 (核价变量拒删, 普通变量级联清值) */
  remove: (code: string) =>
    api.delete(`/global-variables/${code}`) as Promise<any>,
  getOne: (code: string) => api.get(`/global-variables/${code}`) as Promise<any>,
  listKeys: (code: string, limit = 1000) =>
    api.get(`/global-variables/${code}/keys`, { params: { limit } }) as Promise<any>,
  /** 直接取值, 复合键时 key 列名直接作 query 参数, 例: { from_currency:'CNY', to_currency:'USD' } */
  getValue: (code: string, keyParams: Record<string, string>) =>
    api.get(`/global-variables/${code}/value`, { params: keyParams }) as Promise<any>,
  /** V106: Upsert 一条明细 (写入物理表当前默认 PUBLISHED 版本). 二次确认应在 UI 层做 */
  upsertEntry: (code: string, body: { keyValues: Record<string, any>; value: number; note?: string }) =>
    api.post(`/global-variables/${code}/entries`, body) as Promise<any>,
  /** V106: 删除明细行 */
  deleteEntry: (code: string, body: { keyValues: Record<string, any>; note?: string }) =>
    api.request({
      url: `/global-variables/${code}/entries`,
      method: 'DELETE',
      data: body,
    }) as Promise<any>,
  /** V106: 变更日志 (code 留空 = 全部) */
  listChangeLog: (code?: string, limit = 100) =>
    api.get('/global-variables/change-log', { params: { code, limit } }) as Promise<any>,
};

/**
 * V104: 把全局变量 token 编译成 BNF path 字符串.
 * - 静态 key (key_values): 直接固化到 path
 * - 动态 key (key_field_refs): 留空预编译, 求值期由 driver 行重写
 *
 * @param def        注册表行
 * @param keyValues  静态 key 值映射 (列名 → 值), 至少要等于 def.keyColumns 数量
 */
export function compileGlobalVariableToPath(
  def: GlobalVariableDefinition,
  keyValues: Record<string, any>,
): string {
  const parts: string[] = [];
  for (const col of def.keyColumns) {
    const v = keyValues[col];
    if (v === undefined || v === null || v === '') {
      throw new Error(`缺少 key 列: ${col}`);
    }
    const lit = typeof v === 'number' || typeof v === 'boolean'
      ? String(v)
      : `'${String(v).replace(/'/g, "''")}'`;
    parts.push(`${col}=${lit}`);
  }
  return `${def.sourceView}[${parts.join(' AND ')}].${def.valueColumn}`;
}
