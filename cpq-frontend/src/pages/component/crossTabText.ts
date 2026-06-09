import type { FormulaToken } from './types';

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

export interface CrossTabTokenLike {
  type: 'cross_tab_ref';
  source: string;
  sourceLabel: string;
  target: string;
  targetExpr?: FormulaToken[];
  match: Array<{ a: string; b: string }>;
  agg: string;
}

export interface SourceCompLike {
  id: string;
  code: string;
  name: string;
  fields: Array<{ name: string; label?: string }>;
}

/** targetExpr token → 规范机器可解析片段（A.x / B.x / ASCII 运算符 / 数字）。 */
function exprTokenToCanonical(tok: FormulaToken): string {
  switch (tok.type) {
    case 'field': return `A.${tok.value}`;
    case 'b_field': return `B.${tok.value}`;
    case 'operator': return tok.value || '';
    case 'bracket_open': return '(';
    case 'bracket_close': return ')';
    case 'number': return tok.value || '';
    default: return tok.value || '';
  }
}

export function serializeCrossTab(token: CrossTabTokenLike, sourceComp: SourceCompLike | null): string {
  const opLabel = OPERATIONS.find((o) => o.agg === token.agg)?.label ?? '取一个值';
  const srcCode = sourceComp?.code ?? token.source;
  const pairs = (token.match || []).filter((p) => p.a && p.b).map((p) => `${p.a}=${p.b}`).join(',');
  let targetText: string;
  if (token.agg === 'COUNT') {
    targetText = '(计数)';
  } else if (token.targetExpr && token.targetExpr.length > 0) {
    targetText = token.targetExpr.map(exprTokenToCanonical).join(' ');
  } else {
    targetText = `A.${token.target}`;
  }
  return `${opLabel} | 源:${srcCode} | 关联:${pairs} | 目标:${targetText}`;
}
