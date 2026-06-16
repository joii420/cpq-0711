/**
 * predicate 文本 ↔ ConditionPredicate 互转。
 * 镜像后端 ConditionPredicateParser.java 逐分支对齐。
 *
 * 文法：
 *   orExpr  := andExpr ('OR' andExpr)*
 *   andExpr := cmpExpr ('AND' cmpExpr)*
 *   cmpExpr := '(' orExpr ')' | operand op operand
 *   operand := '[别名.字段]' | '字符串' | 数字
 *   op      := >= | <= | <> | != | = | > | <   (长优先)
 *
 * [别名.字段]：首个出现的别名记为 source → sourceField，其余别名 → hostField。
 */
import type { ConditionPredicate, PredicateOperand } from './formulaEngine';

// ── 解析器状态 ──────────────────────────────────────────────────────────────

class Parser {
  private src: string;
  private pos: number;
  private firstTab: string | null;

  constructor(src: string) {
    this.src = src ?? '';
    this.pos = 0;
    this.firstTab = null;
  }

  parse(): ConditionPredicate {
    const p = this.orExpr();
    this.skipWs();
    if (this.pos !== this.src.length) {
      throw new Error(`条件解析残留: ${this.src.substring(this.pos)}`);
    }
    return p;
  }

  private orExpr(): ConditionPredicate {
    const parts: ConditionPredicate[] = [this.andExpr()];
    while (this.matchKeyword('OR')) {
      parts.push(this.andExpr());
    }
    return parts.length === 1 ? parts[0] : { bool: 'OR', children: parts };
  }

  private andExpr(): ConditionPredicate {
    const parts: ConditionPredicate[] = [this.cmpExpr()];
    while (this.matchKeyword('AND')) {
      parts.push(this.cmpExpr());
    }
    return parts.length === 1 ? parts[0] : { bool: 'AND', children: parts };
  }

  private cmpExpr(): ConditionPredicate {
    this.skipWs();
    if (this.peek() === '(') {
      this.pos++; // 吃 '('
      const inner = this.orExpr();
      this.skipWs();
      if (this.peek() !== ')') throw new Error('缺右括号');
      this.pos++; // 吃 ')'
      return inner;
    }
    const lhs = this.operand();
    const op = this.readOperator();
    const rhs = this.operand();
    return { op: op as ConditionPredicate extends { op: infer O } ? O : never, lhs, rhs };
  }

  private operand(): PredicateOperand {
    this.skipWs();
    const c = this.peek();
    if (c === '[') {
      // [别名.字段]
      const close = this.src.indexOf(']', this.pos);
      if (close < 0) throw new Error('缺 ]');
      const inner = this.src.substring(this.pos + 1, close).trim();
      this.pos = close + 1;
      const dot = inner.lastIndexOf('.');
      const tab = dot >= 0 ? inner.substring(0, dot) : '';
      const field = dot >= 0 ? inner.substring(dot + 1) : inner;
      if (this.firstTab === null) this.firstTab = tab;
      return tab === this.firstTab
        ? { kind: 'sourceField', field }
        : { kind: 'hostField', field };
    }
    if (c === "'" || c === '"') {
      // 字符串字面量
      const q = c;
      this.pos++;
      const close = this.src.indexOf(q, this.pos);
      if (close < 0) throw new Error('字符串未闭合');
      const v = this.src.substring(this.pos, close);
      this.pos = close + 1;
      return { kind: 'literal', value: v };
    }
    // 数字字面量（含 . 和 - 前缀）
    const start = this.pos;
    while (
      this.pos < this.src.length &&
      (this.isDigit(this.peek()) || this.peek() === '.' || this.peek() === '-')
    ) {
      this.pos++;
    }
    if (this.pos === start) throw new Error(`非法操作数 @${this.pos}`);
    return { kind: 'literal', value: this.src.substring(start, this.pos).trim() };
  }

  private readOperator(): string {
    this.skipWs();
    for (const op of ['>=', '<=', '<>', '!=', '=', '>', '<']) {
      if (this.src.startsWith(op, this.pos)) {
        this.pos += op.length;
        return op;
      }
    }
    throw new Error(`缺运算符 @${this.pos}`);
  }

  /**
   * 关键字后须是空白/括号/结尾，防止吞掉 ANDxxx。
   * 大小写不敏感。
   */
  private matchKeyword(kw: string): boolean {
    this.skipWs();
    if (this.pos + kw.length <= this.src.length) {
      const slice = this.src.substring(this.pos, this.pos + kw.length);
      if (slice.toUpperCase() === kw.toUpperCase()) {
        const after = this.pos + kw.length;
        if (
          after === this.src.length ||
          this.isWhitespace(this.src.charAt(after)) ||
          this.src.charAt(after) === '('
        ) {
          this.pos = after;
          return true;
        }
      }
    }
    return false;
  }

  private skipWs(): void {
    while (this.pos < this.src.length && this.isWhitespace(this.src.charAt(this.pos))) {
      this.pos++;
    }
  }

  private peek(): string {
    return this.pos < this.src.length ? this.src.charAt(this.pos) : '\0';
  }

  private isWhitespace(c: string): boolean {
    return c === ' ' || c === '\t' || c === '\r' || c === '\n';
  }

  private isDigit(c: string): boolean {
    return c >= '0' && c <= '9';
  }
}

// ── 公开 API ────────────────────────────────────────────────────────────────

/**
 * 将 predicate 文本解析为 ConditionPredicate 对象。
 * 镜像后端 ConditionPredicateParser.java。
 */
export function parsePredicateText(text: string): ConditionPredicate {
  return new Parser(text).parse();
}

/**
 * 将 ConditionPredicate 序列化为文本。
 * - sourceField → `[sourceAlias.field]`
 * - hostField   → `[hostAlias.field]`
 * - literal     → 纯数字裸写，否则加单引号
 * - op `!=`/`<>` 统一输出 `!=`（round-trip 稳定）
 * - Bool 子节点本身是 Bool 时加括号（保证优先级）
 */
export function serializePredicate(
  p: ConditionPredicate,
  ctx: { sourceAlias: string; hostAlias: string },
): string {
  if ('bool' in p) {
    const sep = p.bool === 'AND' ? ' AND ' : ' OR ';
    return p.children
      .map((child) => {
        const s = serializePredicate(child, ctx);
        return 'bool' in child ? `(${s})` : s;
      })
      .join(sep);
  }
  // Comparison
  const serOp = (op: string): string => (op === '<>' ? '!=' : op);
  const serOperand = (o: PredicateOperand): string => {
    if (o.kind === 'sourceField') return `[${ctx.sourceAlias}.${o.field}]`;
    if (o.kind === 'hostField') return `[${ctx.hostAlias}.${o.field}]`;
    // literal: 纯数字（含负数/小数）裸写，否则单引号
    return /^-?\d+(\.\d+)?$/.test(o.value) ? o.value : `'${o.value}'`;
  };
  return `${serOperand(p.lhs)} ${serOp(p.op)} ${serOperand(p.rhs)}`;
}
