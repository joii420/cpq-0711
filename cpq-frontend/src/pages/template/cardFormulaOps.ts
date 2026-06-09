// 友好操作 ↔ CardFormulaDrawer 内部 RefType/AggFunc 映射（松类型,避免跨文件 type 耦合;drawer 侧 cast）。
export interface CardOperationDef {
  key: string;
  label: string;
  refType: string;          // 'subtotal' | 'first_row' | 'row_where' | 'aggregate'
  aggFunc?: string;         // 仅 aggregate: 'SUM'|'AVG'|'COUNT'|'MAX'|'MIN'
  /** 简单模式是否露出 */
  simple: boolean;
}

export const CARD_OPERATIONS: CardOperationDef[] = [
  { key: 'subtotal',     label: '引用页签小计',   refType: 'subtotal',  simple: true },
  { key: 'lookup_first', label: '取某字段的值',   refType: 'first_row', simple: true },
  { key: 'lookup_where', label: '按条件查找取值', refType: 'row_where', simple: true },
  { key: 'sum',          label: '按条件求和',     refType: 'aggregate', aggFunc: 'SUM',   simple: true },
  { key: 'avg',          label: '求平均',         refType: 'aggregate', aggFunc: 'AVG',   simple: false },
  { key: 'count',        label: '计数',           refType: 'aggregate', aggFunc: 'COUNT', simple: false },
  { key: 'max',          label: '求最大',         refType: 'aggregate', aggFunc: 'MAX',   simple: false },
  { key: 'min',          label: '求最小',         refType: 'aggregate', aggFunc: 'MIN',   simple: false },
];

export function opToRefType(key: string): { refType: string; aggFunc: string | undefined } {
  const o = CARD_OPERATIONS.find((x) => x.key === key);
  return { refType: o?.refType ?? 'subtotal', aggFunc: o?.aggFunc };
}

export function refTypeToOp(refType: string, aggFunc?: string): string {
  if (refType === 'aggregate') {
    return CARD_OPERATIONS.find((o) => o.refType === 'aggregate' && o.aggFunc === aggFunc)?.key ?? 'sum';
  }
  return CARD_OPERATIONS.find((o) => o.refType === refType)?.key ?? 'subtotal';
}
