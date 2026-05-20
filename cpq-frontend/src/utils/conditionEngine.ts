/**
 * conditionEngine — LIST_FORMULA 字段类型的条件表达式引擎 (Phase B3).
 *
 * 支持语法:
 *   - 原子比较: [字段] op X  其中 op ∈ { > < = != >= <= }, X 可为字面值或 [字段]
 *   - 字面值:   数字 (3 / 3.14) / 字符串 ('AgCu85') / 字段引用 [name]
 *   - 组合:     AND / OR (扁平左结合, 无嵌套括号)
 *   - 大小写不敏感: and / AND / And 等价
 *
 * 示例:
 *   [厚度] > 5
 *   [厚度] > 5 AND [材质] = 'AgCu85'
 *   [厚度] > 5 OR [厚度] = 3
 *   [a] != [b]
 *
 * 不支持 (Phase B 范围外):
 *   - 嵌套括号 ((A AND B) OR C)
 *   - NOT 运算符
 *   - IN / BETWEEN / LIKE
 *   - 函数调用
 *
 * 默认分支约定: 如果 condition 字符串为空 (`""` / `null`), 视为"无条件 = 总是 true" — 即默认分支.
 */

/** 比较运算符枚举 */
type CompOp = '>' | '<' | '=' | '!=' | '>=' | '<=';

/** 原子比较项 — [field] op operand */
interface AtomNode {
  type: 'atom';
  field: string;          // [field] 中的 field 名
  op: CompOp;
  operand: Operand;
}

/** 比较操作数: 数字 / 字符串字面量 / 字段引用 */
type Operand =
  | { kind: 'number'; value: number }
  | { kind: 'string'; value: string }
  | { kind: 'field';  field: string };

/** 布尔组合节点 — atom AND/OR atom AND/OR ... (扁平左结合) */
interface ChainNode {
  type: 'chain';
  atoms: AtomNode[];
  ops: ('AND' | 'OR')[];  // ops[i] 连接 atoms[i] 和 atoms[i+1]
}

type AstNode = AtomNode | ChainNode;

/**
 * 主入口: 求值条件表达式 in 上下文.
 *
 * @param condition 条件字符串. 空 / null → 视为"总是 true" (默认分支)
 * @param fieldValues  当前行的字段值映射 ([字段] token 求值时按 name 取)
 * @returns boolean — 解析失败 / 求值出错 → false (保守不命中)
 */
export function evaluateCondition(
  condition: string | undefined | null,
  fieldValues: Record<string, any>,
): boolean {
  if (condition == null) return true;
  const trimmed = condition.trim();
  if (trimmed === '') return true;
  try {
    const ast = parseConditionExpression(trimmed);
    return evalNode(ast, fieldValues);
  } catch {
    return false;
  }
}

// ── Parser ──────────────────────────────────────────────────────────────────

/**
 * 解析表达式 → AST. 拆分思路:
 *   1. 按 AND/OR (大小写不敏感, 单词边界) 分割成原子片段 + 运算符序列
 *   2. 每个原子片段解析成 AtomNode (匹配 `[<field>] <op> <operand>`)
 */
function parseConditionExpression(expr: string): AstNode {
  // 把 AND/OR 替换为标准 token, 区分字段名里的子串
  // 简化: AND/OR 仅在单词边界出现 (前后是空格 / 边界), 字段名用 [] 包裹不会冲突
  const tokens: string[] = [];
  const ops: ('AND' | 'OR')[] = [];

  // 正则按 AND/OR 切分 (case-insensitive, 单词边界)
  const splitRe = /\s+(AND|OR|and|or|And|Or)\s+/g;
  let lastIdx = 0;
  let m: RegExpExecArray | null;
  while ((m = splitRe.exec(expr)) !== null) {
    tokens.push(expr.substring(lastIdx, m.index).trim());
    ops.push(m[1].toUpperCase() as 'AND' | 'OR');
    lastIdx = m.index + m[0].length;
  }
  tokens.push(expr.substring(lastIdx).trim());

  // 每个 token 解析为 atom
  const atoms = tokens.map(parseAtom);
  if (atoms.length === 1) return atoms[0];
  return { type: 'chain', atoms, ops };
}

/**
 * 解析单个原子: `[<field>] <op> <operand>`.
 * op 优先匹配 2-char (!=, >=, <=) 再单字符.
 */
function parseAtom(s: string): AtomNode {
  // 字段名: 必须以 [ 开头, 取到第一个 ]
  if (s[0] !== '[') {
    throw new Error(`atom must start with [: ${s}`);
  }
  const fieldEnd = s.indexOf(']');
  if (fieldEnd < 0) throw new Error(`unclosed [field]: ${s}`);
  const field = s.substring(1, fieldEnd).trim();
  if (!field) throw new Error(`empty field: ${s}`);

  let rest = s.substring(fieldEnd + 1).trimStart();

  // op: 优先 2-char
  let op: CompOp | null = null;
  for (const cand of ['>=', '<=', '!=']) {
    if (rest.startsWith(cand)) { op = cand as CompOp; rest = rest.substring(2).trimStart(); break; }
  }
  if (!op) {
    for (const cand of ['>', '<', '=']) {
      if (rest.startsWith(cand)) { op = cand as CompOp; rest = rest.substring(1).trimStart(); break; }
    }
  }
  if (!op) throw new Error(`no op in atom: ${s}`);

  // operand: [field] / 'string' / number
  const operand = parseOperand(rest);
  return { type: 'atom', field, op, operand };
}

function parseOperand(s: string): Operand {
  const t = s.trim();
  if (!t) throw new Error(`empty operand`);
  // [field]
  if (t[0] === '[') {
    const end = t.indexOf(']');
    if (end < 0) throw new Error(`unclosed [field]: ${t}`);
    return { kind: 'field', field: t.substring(1, end).trim() };
  }
  // 'string'
  if (t[0] === "'") {
    const end = t.indexOf("'", 1);
    if (end < 0) throw new Error(`unclosed string: ${t}`);
    return { kind: 'string', value: t.substring(1, end) };
  }
  // "string"
  if (t[0] === '"') {
    const end = t.indexOf('"', 1);
    if (end < 0) throw new Error(`unclosed string: ${t}`);
    return { kind: 'string', value: t.substring(1, end) };
  }
  // number
  const n = parseFloat(t);
  if (!isNaN(n)) return { kind: 'number', value: n };
  throw new Error(`invalid operand: ${t}`);
}

// ── Evaluator ───────────────────────────────────────────────────────────────

function evalNode(node: AstNode, ctx: Record<string, any>): boolean {
  if (node.type === 'atom') return evalAtom(node, ctx);
  // chain: 左结合 — (((a OP1 b) OP2 c) OP3 d) ...
  let acc = evalAtom(node.atoms[0], ctx);
  for (let i = 0; i < node.ops.length; i++) {
    const op = node.ops[i];
    const next = evalAtom(node.atoms[i + 1], ctx);
    acc = op === 'AND' ? (acc && next) : (acc || next);
  }
  return acc;
}

function evalAtom(node: AtomNode, ctx: Record<string, any>): boolean {
  const left = ctx[node.field];
  const right = resolveOperand(node.operand, ctx);
  return compare(left, node.op, right);
}

function resolveOperand(op: Operand, ctx: Record<string, any>): any {
  if (op.kind === 'field') return ctx[op.field];
  return op.value;
}

/**
 * 类型自动协调: 比较两侧
 *  - 都是数字 / 字符串-can-be-number → 数字比较
 *  - 其余 → 字符串比较 (严格 = / != 用 String() 转后比)
 */
function compare(l: any, op: CompOp, r: any): boolean {
  // null / undefined 处理: 任何与 null/undefined 比较一律 false (除 != 时 vs non-null = true)
  const lNull = l == null || l === '';
  const rNull = r == null || r === '';

  if (op === '=') {
    if (lNull && rNull) return true;
    if (lNull || rNull) return false;
    // 优先数字相等
    const ln = toNumber(l); const rn = toNumber(r);
    if (ln != null && rn != null) return ln === rn;
    return String(l) === String(r);
  }
  if (op === '!=') {
    if (lNull && rNull) return false;
    if (lNull || rNull) return true;
    const ln = toNumber(l); const rn = toNumber(r);
    if (ln != null && rn != null) return ln !== rn;
    return String(l) !== String(r);
  }

  // 大小比较: 仅在两边都能转数字时才比较, 否则 false
  if (lNull || rNull) return false;
  const ln = toNumber(l); const rn = toNumber(r);
  if (ln == null || rn == null) {
    // 字符串字典序比较
    const ls = String(l); const rs = String(r);
    if (op === '>') return ls > rs;
    if (op === '<') return ls < rs;
    if (op === '>=') return ls >= rs;
    if (op === '<=') return ls <= rs;
    return false;
  }
  if (op === '>')  return ln > rn;
  if (op === '<')  return ln < rn;
  if (op === '>=') return ln >= rn;
  if (op === '<=') return ln <= rn;
  return false;
}

function toNumber(v: any): number | null {
  if (typeof v === 'number') return isFinite(v) ? v : null;
  if (typeof v === 'boolean') return v ? 1 : 0;
  if (typeof v === 'string') {
    const t = v.trim();
    if (t === '') return null;
    const n = parseFloat(t);
    return isNaN(n) ? null : n;
  }
  return null;
}
