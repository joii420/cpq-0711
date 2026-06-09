export interface OperationDef {
  key: string;
  label: string;
  agg: string;
  /** 简单模式是否露出 */
  simple: boolean;
}

export const OPERATIONS: OperationDef[] = [
  { key: 'single', label: '取一个值', agg: 'NONE', simple: true },
  { key: 'sum', label: '求和', agg: 'SUM', simple: true },
  { key: 'avg', label: '平均', agg: 'AVG', simple: false },
  { key: 'count', label: '计数', agg: 'COUNT', simple: false },
  { key: 'max', label: '最大', agg: 'MAX', simple: false },
  { key: 'min', label: '最小', agg: 'MIN', simple: false },
];

export function operationToAgg(key: string): string {
  return OPERATIONS.find((o) => o.key === key)?.agg ?? 'NONE';
}

export function aggToOperation(agg: string): string {
  return OPERATIONS.find((o) => o.agg === agg)?.key ?? 'single';
}
