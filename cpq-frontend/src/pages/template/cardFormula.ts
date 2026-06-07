export type CondRhsType = 'literal' | 'product' | 'column';
export type CondOp = 'eq' | 'ne' | 'gt' | 'gte' | 'lt' | 'lte' | 'in';

export interface CondRowSpec {
  left: string;
  op: CondOp;
  logic: 'and' | 'or';
  rhs: { type: CondRhsType; value: string };
}

export interface CardRefSpec {
  tab: string;
  field?: string;
  mode?: 'FIRST_ROW' | 'ROW_WHERE';
  cond?: string;
  cols?: Record<string, string>;
  condRows?: CondRowSpec[];
}

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

const ALLOWED = /^[\sA-Za-z0-9_+\-*/().,%<>=!&|'#一-龥\[\]]*$/; // 含中文 + #(聚合唯一 token [页签#N])

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
  // 聚合被方括号包裹：[ ... SUM_OVER(...) ... ]
  if (/\[[^\[\]]*\b(SUM|AVG|COUNT|MIN|MAX)_OVER\s*\(/.test(f)) {
    errs.push('聚合函数不能包在 [] 里。正确写法：SUM_OVER([页签] WHERE 条件, 表达式)');
  }
  // 括号/方括号配平
  const bal = (open: string, close: string) => {
    let n = 0; for (const ch of f) { if (ch === open) n++; else if (ch === close) { n--; if (n < 0) return false; } } return n === 0;
  };
  if (!bal('(', ')')) errs.push('圆括号 ( ) 不配平');
  if (!bal('[', ']')) errs.push('方括号 [ ] 不配平');
  // 未知函数名（白名单外的 标识符( ）
  const FN_WHITELIST = new Set(['IF','ROUND','ABS','SUM_OVER','AVG_OVER','COUNT_OVER','MIN_OVER','MAX_OVER']);
  const fnCall = /([A-Za-z_][A-Za-z0-9_]*)\s*\(/g;
  let fm: RegExpExecArray | null;
  while ((fm = fnCall.exec(f)) !== null) {
    if (!FN_WHITELIST.has(fm[1])) errs.push(`未知函数 ${fm[1]}（支持：IF/ROUND/ABS/SUM_OVER/AVG_OVER/COUNT_OVER/MIN_OVER/MAX_OVER）`);
  }
  return errs;
}

const JEXL_TO_OP: Record<string, CondOp> = {
  '==': 'eq', '!=': 'ne', '>=': 'gte', '<=': 'lte', '>': 'gt', '<': 'lt',
};

/** 把条件构建器行（含 rhsType）转成结构化 condRows，过滤空字段行。 */
export function buildCondRows(
  conds: { field: string; op: CondOp; value: string; logic: 'and' | 'or'; rhsType: CondRhsType }[],
): CondRowSpec[] {
  return conds
    .filter(c => c.field)
    .map(c => ({ left: c.field, op: c.op, logic: c.logic, rhs: { type: c.rhsType, value: c.value } }));
}

/**
 * 反解析旧式字面量 cond（由 buildCondJexl 生成）→ condRows（rhs 全为 literal）。
 * 支持：`alias op literal` 用 ` && ` / ` || ` 连接；IN 形如 `(alias=='v1' || alias=='v2')`。
 * cols：别名→中文字段名。解析失败的段跳过；空串 → []。
 */
export function parseCondToRows(cond: string, cols: Record<string, string>): CondRowSpec[] {
  const s = (cond || '').trim();
  if (!s) return [];
  // 1. 按顶层 && / || 切段，记录每段后面的连接符
  const segs: { text: string; logicAfter: 'and' | 'or' }[] = [];
  let depth = 0, buf = '';
  for (let i = 0; i < s.length; i++) {
    const ch = s[i];
    if (ch === '(' || ch === '[') depth++;
    else if (ch === ')' || ch === ']') depth--;
    if (depth === 0 && (s.startsWith(' && ', i) || s.startsWith(' || ', i))) {
      segs.push({ text: buf.trim(), logicAfter: s.startsWith(' && ', i) ? 'and' : 'or' });
      buf = ''; i += 3; continue;
    }
    buf += ch;
  }
  if (buf.trim()) segs.push({ text: buf.trim(), logicAfter: 'and' });

  const out: CondRowSpec[] = [];
  for (const seg of segs) {
    const t = seg.text.trim();
    const inMatch = t.startsWith('(') && t.endsWith(')');
    if (inMatch) {
      // IN 组：(alias=='v1' || alias=='v2')
      const inner = t.slice(1, -1);
      const parts = inner.split('||').map(p => p.trim());
      const eqRe = /^([A-Za-z_]\w*)\s*==\s*'(.*)'$/;
      let alias = ''; const vals: string[] = [];
      let ok = true;
      for (const p of parts) {
        const mm = p.match(eqRe);
        if (!mm) { ok = false; break; }
        alias = mm[1]; vals.push(mm[2]);
      }
      if (ok && alias) {
        out.push({ left: cols[alias] ?? alias, op: 'in', logic: seg.logicAfter,
                   rhs: { type: 'literal', value: vals.join(',') } });
        continue;
      }
    }
    // 标量段：alias op literal
    const m = t.match(/^([A-Za-z_]\w*)\s*(==|!=|>=|<=|>|<)\s*(.+)$/);
    if (!m) continue;
    const alias = m[1]; const op = JEXL_TO_OP[m[2]] ?? 'eq';
    let lit = m[3].trim();
    if (lit.startsWith("'") && lit.endsWith("'")) lit = lit.slice(1, -1);
    out.push({ left: cols[alias] ?? alias, op, logic: seg.logicAfter,
               rhs: { type: 'literal', value: lit } });
  }
  // 末段 logicAfter 无意义，但保持 'and' 即可（构建时末条不用 logic）
  return out;
}

/**
 * 为某页签生成下一个唯一聚合 refKey `页签名#N`（N 从 1 起，取该页签已有 `页签名#数字` 的最大值 +1）。
 * 旧无后缀 `页签名` 视为占位但不计入序号（新建仍从 #1 起，与旧并存不冲突）。
 * 非聚合 key（如 `页签名.字段(条件)`）不计入。
 */
export function nextAggRefKey(tabName: string, existingKeys: string[]): string {
  const re = new RegExp(`^${tabName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}#(\\d+)$`);
  let max = 0;
  for (const k of existingKeys) {
    const m = k.match(re);
    if (m) max = Math.max(max, Number(m[1]));
  }
  return `${tabName}#${max + 1}`;
}
