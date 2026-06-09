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
    // P1 targetExpr 仅含 field/b_field/operator/bracket/number。
    // global_variable 等其他 token 类型 P1 不支持插入（抽屉 TODO），故走 default。
    // 未来加全局变量插入时，必须在此 serializer 与 parseCrossTab 解析器两侧对称补齐。
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

export type ParseResult =
  | { token: CrossTabTokenLike }
  | { error: string };

/** 规范文本片段 → FormulaToken（A.x/B.x/运算符/括号/数字）。失败抛字符串。 */
function canonicalToExprTokens(targetText: string): FormulaToken[] {
  const parts = targetText.trim().split(/\s+/).filter(Boolean);
  return parts.map((p): FormulaToken => {
    if (p.startsWith('A.')) return { type: 'field', value: p.slice(2) };
    if (p.startsWith('B.')) return { type: 'b_field', value: p.slice(2) };
    if (p === '(') return { type: 'bracket_open', value: '(' };
    if (p === ')') return { type: 'bracket_close', value: ')' };
    if (['+', '-', '*', '/'].includes(p)) return { type: 'operator', value: p };
    if (/^-?\d+(\.\d+)?$/.test(p)) return { type: 'number', value: p };
    throw `无法识别的片段「${p}」`;
  });
}

export function parseCrossTab(text: string, siblings: SourceCompLike[]): ParseResult {
  const segs = text.split('|').map((s) => s.trim());
  if (segs.length !== 4) return { error: '格式应为：操作 | 源:CODE | 关联:a=b[,c=d] | 目标:…' };
  const [opSeg, srcSeg, matchSeg, targetSeg] = segs;
  const op = OPERATIONS.find((o) => o.label === opSeg);
  if (!op) return { error: `未知操作「${opSeg}」（应为 ${OPERATIONS.map((o) => o.label).join('/')}）` };
  if (!srcSeg.startsWith('源:')) return { error: '第 2 段应以「源:」开头' };
  const srcCode = srcSeg.slice(2).trim();
  const sourceComp = siblings.find((c) => c.code === srcCode);
  if (!sourceComp) return { error: `源组件 code「${srcCode}」不存在` };
  if (!matchSeg.startsWith('关联:')) return { error: '第 3 段应以「关联:」开头' };
  const pairText = matchSeg.slice(3).trim();
  const match: Array<{ a: string; b: string }> = [];
  for (const seg of pairText.split(',').map((s) => s.trim()).filter(Boolean)) {
    const [a, b] = seg.split('=').map((s) => s.trim());
    if (!a || !b) return { error: `关联对「${seg}」格式应为 a=b` };
    match.push({ a, b });
  }
  if (match.length === 0) return { error: '至少需要一组关联对' };
  if (!targetSeg.startsWith('目标:')) return { error: '第 4 段应以「目标:」开头' };
  const targetText = targetSeg.slice(3).trim();

  const base: CrossTabTokenLike = {
    type: 'cross_tab_ref', source: sourceComp.id, sourceLabel: sourceComp.name,
    target: '', targetExpr: undefined, match, agg: op.agg,
  };
  if (op.agg === 'COUNT') return { token: base };
  if (!targetText) return { error: '目标不能为空（计数以外的操作需指定目标列或公式）' };
  // 单列（A.单名、无运算符/无空格）vs 公式
  if (/^A\.[^\s]+$/.test(targetText)) {
    return { token: { ...base, target: targetText.slice(2) } };
  }
  try {
    return { token: { ...base, targetExpr: canonicalToExprTokens(targetText) } };
  } catch (e) {
    return { error: typeof e === 'string' ? e : '目标公式解析失败' };
  }
}
