export interface CardRefSpec { tab: string; field?: string; mode?: 'FIRST_ROW'|'ROW_WHERE'; cond?: string; cols?: Record<string,string>; }

export const genAlias = (i: number): string => `c${i}`;

export const expandIn = (alias: string, values: string[]): string =>
  '(' + values.map(v => `${alias}=='${v}'`).join(' || ') + ')';

const BRACKET = /\[([^\[\]]+)]/g;
export function extractColKeyDeps(formula: string, allColKeys: string[]): string[] {
  const out: string[] = []; let m: RegExpExecArray | null;
  const re = new RegExp(BRACKET);
  while ((m = re.exec(formula)) !== null) {
    const ref = m[1].trim();
    if (!ref.includes('.') && allColKeys.includes(ref) && !out.includes(ref)) out.push(ref);
  }
  return out;
}

/** 列依赖环检测（Kahn）。formulas: col_key→formula。有环返回环成员，否则 []。 */
export function detectCycle(formulas: Record<string,string>): string[] {
  const cols = Object.keys(formulas);
  const deps: Record<string,string[]> = {};
  const indeg: Record<string,number> = {};
  for (const c of cols) { deps[c] = extractColKeyDeps(formulas[c] || '', cols); indeg[c] = deps[c].length; }
  const q = cols.filter(c => indeg[c] === 0); const order: string[] = [];
  while (q.length) {
    const c = q.shift()!; order.push(c);
    for (const o of cols) if (deps[o].includes(c) && --indeg[o] === 0) q.push(o);
  }
  return order.length === cols.length ? [] : cols.filter(c => !order.includes(c));
}

const ALLOWED = /^[\sA-Za-z0-9_+\-*/().,%<>=!&|'一-龥\[\]]*$/; // 含中文(字符串字面量/占位内)

export function validateCardFormula(
  col: { col_key: string; formula?: string; refs?: Record<string, CardRefSpec> },
  allColKeys: string[],
  allFormulas: Record<string,string>,
): string[] {
  const errs: string[] = [];
  const f = (col.formula || '').replace(/^=/, '');
  const refs = col.refs || {};
  let m: RegExpExecArray | null; const re = new RegExp(BRACKET);
  while ((m = re.exec(f)) !== null) {
    const tok = m[1].trim();
    if (tok.includes('.')) { if (!refs[tok]) errs.push(`占位 [${tok}] 缺少 ref 定义`); }
    else if (!allColKeys.includes(tok) && refs[tok]) {
      if (!refs[tok].cols || Object.keys(refs[tok].cols!).length === 0) errs.push(`聚合源 [${tok}] 缺少 cols 别名映射`);
    }
  }
  const cyc = detectCycle(allFormulas);
  if (cyc.includes(col.col_key)) errs.push(`列公式存在循环引用: ${cyc.join(',')}`);
  if (!ALLOWED.test(f)) errs.push('公式含非法字符');
  return errs;
}
