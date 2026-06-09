export type CondOp = 'eq' | 'ne' | 'gt' | 'gte' | 'lt' | 'lte' | 'in';

export type CondTree =
  | { kind: 'group'; logic: 'and' | 'or'; children: CondTree[] }
  | { kind: 'leaf'; left: string; op: CondOp; rhs: { type: 'literal' | 'column'; value: string } };

/** lookup(col) 返回该列原始值（数字/字符串/undefined）。解析或求值异常 → false（保守不命中）。 */
export function evalCondTree(tree: CondTree | null | undefined, lookup: (col: string) => any): boolean {
  if (!tree) return true; // 空条件 = 默认分支（总为真）
  try {
    return evalNode(tree, lookup);
  } catch {
    return false;
  }
}

function evalNode(t: CondTree, lookup: (col: string) => any): boolean {
  if (t.kind === 'group') {
    const children = t.children || [];
    if (children.length === 0) return t.logic === 'and';
    return t.logic === 'and'
      ? children.every(c => evalNode(c, lookup))
      : children.some(c => evalNode(c, lookup));
  }
  const L = lookup(t.left);
  const R = t.rhs.type === 'column' ? lookup(t.rhs.value) : t.rhs.value;
  return cmp(t.op, L, R);
}

function cmp(op: CondOp, L: any, R: any): boolean {
  if (op === 'in') {
    if (L == null) return false;
    const set = String(R ?? '').split(',').map(s => s.trim());
    return set.includes(String(L).trim());
  }
  const ln = toNum(L), rn = toNum(R);
  if (op === 'gt' || op === 'gte' || op === 'lt' || op === 'lte') {
    if (ln == null || rn == null) return false;
    if (op === 'gt') return ln > rn;
    if (op === 'gte') return ln >= rn;
    if (op === 'lt') return ln < rn;
    return ln <= rn;
  }
  // eq / ne：数值优先，否则字符串
  let eq: boolean;
  if (ln != null && rn != null) eq = ln === rn;
  else eq = String(L ?? '') === String(R ?? '');
  return op === 'eq' ? eq : !eq;
}

function toNum(v: any): number | null {
  if (typeof v === 'number') return isNaN(v) ? null : v;
  if (v == null) return null;
  const n = parseFloat(String(v));
  return isNaN(n) ? null : n;
}

/** 收集条件树引用的列名（leaf.left + column 型 rhs），供拓扑依赖。 */
export function condTreeColumns(tree: CondTree | null | undefined): string[] {
  const out: string[] = [];
  const walk = (t: CondTree) => {
    if (t.kind === 'group') (t.children || []).forEach(walk);
    else { out.push(t.left); if (t.rhs.type === 'column') out.push(t.rhs.value); }
  };
  if (tree) walk(tree);
  return out;
}
