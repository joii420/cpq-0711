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
    if (op === 'gt') return ln.greaterThan(rn);
    if (op === 'gte') return ln.greaterThanOrEqualTo(rn);
    if (op === 'lt') return ln.lessThan(rn);
    return ln.lessThanOrEqualTo(rn);
  }
  // eq / ne：数值优先，否则字符串
  let eq: boolean;
  if (ln != null && rn != null) eq = ln.equals(rn);
  else eq = String(L ?? '') === String(R ?? '');
  return op === 'eq' ? eq : !eq;
}

function toNum(v: any): Decimal | null {
  return isDecimalString(v) ? toDecimal(v) : null;
}

/**
 * task-0803（2026-08-04）：条件公式里的树属性保留字。与表达式内口径逐字一致
 * （`pages/component/formulaSerialize.ts` 的 TREE_ATTR_RESERVED 键）与后端
 * `FormulaCalculator.TREE_ATTR_COLS` 三处必须同步。
 *
 * 🔒 保留字**优先于同名列** —— 与表达式内一致；实现上由各 lookup 在最前面拦截。
 */
export const TREE_ATTR_COLS = new Set(['层级', '是否叶子', '是否根']);

/** 条件树里是否用到树属性保留字（供路由判据 + 保存期闸门用；leaf.left 与 column 型 rhs 都算）。 */
export function condTreeUsesTreeAttr(tree: CondTree | null | undefined): boolean {
  if (!tree) return false;
  if (tree.kind === 'group') return (tree.children || []).some(condTreeUsesTreeAttr);
  if (TREE_ATTR_COLS.has(tree.left)) return true;
  return tree.rhs.type === 'column' && TREE_ATTR_COLS.has(tree.rhs.value);
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
import Decimal from 'decimal.js';
import { isDecimalString, toDecimal } from './precision';
