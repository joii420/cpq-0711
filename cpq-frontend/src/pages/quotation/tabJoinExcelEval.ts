/**
 * tabJoinExcelEval.ts — repair-260916 F-7 (AC-14 / AC-15)
 *
 * Pure port of the backend Excel TAB_JOIN_FORMULA column evaluator
 * `TabJoinPlanEvaluator.evaluateColumn` (cpq-backend .../quotation/service/tabjoin/), so that the
 * page (buildExcelSnapshot) and the server compute the same value for the same column text
 * (问题说明 5.3). The two sides are locked together by the shared fixture
 * `__fixtures__/tabjoin-excel-cases.json` (byte-identical copy of the backend test resource).
 *
 * Rules (问题说明 5.3, mirroring the backend line by line):
 *   1. split the expression into top-level `+` / `-` terms (parenthesis-aware);
 *   2. `[tab(总计)]` / `[tab.col(总计)]` / `[tab.col(小计)]` are scalars
 *      (whole-tab total / column subtotal / column subtotal);
 *   3. every un-suffixed `[tab.col]` tab is aligned into wide rows by the row keys of the FIRST such
 *      tab that has row keys (full outer join; a missing row reads 0);
 *   4. `SUM/AVG/MIN/MAX/COUNT(...)` evaluates its body on every aligned row, then aggregates;
 *   5. a term that still has an un-suffixed reference outside aggregates is evaluated on every aligned
 *      row and the results are ADDED; otherwise the term is evaluated once.
 *
 * Arithmetic mirrors JEXL 3.3 + `SafeArithmetic` + `PrecisionPolicy` (verified against the real
 * classes, see the task evidence):
 *   - references are substituted as BigDecimal literals; integer literals stay integers; decimal
 *     literals without the `B` suffix are Java doubles;
 *   - `+ - *` with a BigDecimal operand: each operand rounded to 12 places (HALF_EVEN), result kept at
 *     34 significant digits (DECIMAL128); integer-only stays exact; double-only uses double arithmetic;
 *   - `/`: dividend not rounded; divisor 0 → dividend rounded to 12 places (HALF_UP);
 *     otherwise quotient at 12 places HALF_UP; a double literal (e.g. `/ 1.13`) is converted through
 *     its decimal text first (repair-260916 D-10 / backend B-8 `SafeArithmetic.exact`);
 *   - `%`: remainder with the dividend's sign, same operand typing as `*`; divisor 0 → 0;
 *   - `< <= > >= == !=`: numeric comparison (BigDecimal operands rounded to 12 places first),
 *     not chainable; a comparison against null is false (except `==` / `!=`); a boolean result
 *     counts as 0 when it is the value of a row; booleans count as 1 / 0 in `+ - * %`;
 *   - every row result and the column result are rounded to 12 places HALF_UP;
 *   - syntax errors throw; an unknown identifier / function call evaluates to null (= 0).
 * Not supported (throws): logical operators, ternary, strings, regex operators.
 *
 * Errors are thrown; the caller decides how to degrade (buildExcelSnapshot returns '0').
 */

import Decimal from 'decimal.js';
import { CALCULATION_SCALE, toCalculationString, type DecimalString } from '../../utils/precision';

/** `[tab(小计)]` without a column — same verbatim message as 问题说明 5.1 / backend. */
export const MSG_SUBTOTAL_WITHOUT_COLUMN =
  '「(小计)」要写在列名后面，如 [页签.列(小计)]；整页签合计请写 [页签(总计)]';

const SUFFIX_TOTAL = '(总计)';
const SUFFIX_SUBTOTAL = '(小计)';
const MC_PRECISION = 34; // MathContext.DECIMAL128

/** One entry of a TAB_JOIN_FORMULA column's `tabs` array. */
export interface TabJoinTabRef {
  alias: string;
  tabKey: string;
  rowKeyFields?: string[];
}

/** Data source for one Excel row (mirror of the backend `CardDataProvider`). Missing → null/undefined → 0. */
export interface TabJoinDataProvider {
  rowsOf(tab: TabJoinTabRef): Array<Record<string, unknown>>;
  subtotalOfColumn(tab: TabJoinTabRef, column: string): string | null | undefined;
  subtotalOf(tab: TabJoinTabRef): string | null | undefined;
}

// ─── token parsing (backend parseTok) ─────────────────────────────────────────

interface Tok {
  raw: string;
  total: boolean;
  alias: string;
  column: string | null;
}

/** Java String.trim(): strips chars <= U+0020 only. */
function javaTrim(s: string): string {
  let a = 0;
  let b = s.length;
  while (a < b && s.charCodeAt(a) <= 0x20) a++;
  while (b > a && s.charCodeAt(b - 1) <= 0x20) b--;
  return s.slice(a, b);
}

function parseTok(rawIn: string): Tok {
  const raw = javaTrim(rawIn);
  const suffix = raw.endsWith(SUFFIX_TOTAL) ? SUFFIX_TOTAL
    : raw.endsWith(SUFFIX_SUBTOTAL) ? SUFFIX_SUBTOTAL : null;
  const total = suffix !== null;
  const body = suffix ? raw.slice(0, raw.length - suffix.length) : raw;
  const dot = body.indexOf('.');
  const alias = dot >= 0 ? body.slice(0, dot) : body;
  const column = dot >= 0 ? body.slice(dot + 1) : null;
  if (suffix === SUFFIX_SUBTOTAL && (column === null || javaTrim(column) === '')) {
    throw new Error(MSG_SUBTOTAL_WITHOUT_COLUMN);
  }
  return { raw, total, alias, column };
}

const TOKEN_RE = /\[([^[\]]+)]/g;

function forEachToken(expr: string, fn: (inner: string) => void): void {
  const re = new RegExp(TOKEN_RE.source, 'g');
  let m: RegExpExecArray | null;
  while ((m = re.exec(expr)) !== null) fn(m[1]);
}

// ─── expression structure helpers (backend splitTerms / aggregates) ──────────

interface Term { sign: 1 | -1; text: string }

function splitTerms(expr: string): Term[] {
  const out: Term[] = [];
  let depth = 0;
  let sign: 1 | -1 = 1;
  let cur = '';
  for (const ch of expr) {
    if (ch === '(') depth++; else if (ch === ')') depth--;
    if (depth === 0 && (ch === '+' || ch === '-') && javaTrim(cur).length > 0) {
      out.push({ sign, text: cur });
      cur = '';
      sign = ch === '+' ? 1 : -1;
    } else {
      cur += ch;
    }
  }
  if (javaTrim(cur).length > 0) out.push({ sign, text: cur });
  return out;
}

/** Java `\s`: [ \t\n\x0B\f\r]. */
const AGG_CALL_SRC = '\\b(SUM|AVG|MIN|MAX|COUNT)[ \\t\\n\\x0B\\f\\r]*\\(';

/** Start index of the next aggregate function name (followed by `(`), or -1. */
function findAggCall(expr: string, from: number): number {
  const re = new RegExp(AGG_CALL_SRC, 'gi');
  re.lastIndex = from;
  const m = re.exec(expr);
  return m ? m.index : -1;
}

function matchParen(s: string, open: number): number {
  let depth = 0;
  for (let k = open; k < s.length; k++) {
    if (s[k] === '(') depth++;
    else if (s[k] === ')') {
      depth--;
      if (depth === 0) return k;
    }
  }
  return s.length - 1;
}

function blankOutAggregates(expr: string): string {
  let out = '';
  let i = 0;
  while (i < expr.length) {
    const fnStart = findAggCall(expr, i);
    if (fnStart < 0) { out += expr.slice(i); break; }
    const open = expr.indexOf('(', fnStart);
    const close = matchParen(expr, open);
    out += expr.slice(i, fnStart) + '0';
    i = close + 1;
  }
  return out;
}

function hasBareDetail(term: string): boolean {
  let found = false;
  forEachToken(blankOutAggregates(term), (inner) => {
    if (!found && !parseTok(inner).total) found = true;
  });
  return found;
}

type WideRow = Record<string, unknown>;
type Scalars = Map<string, Decimal>;

function replaceAggregates(expr: string, rows: WideRow[], scalars: Scalars): string {
  let out = '';
  let i = 0;
  while (i < expr.length) {
    const fnStart = findAggCall(expr, i);
    if (fnStart < 0) { out += expr.slice(i); break; }
    const open = expr.indexOf('(', fnStart);
    const fn = javaTrim(expr.slice(fnStart, open)).toUpperCase();
    const close = matchParen(expr, open);
    const inner = expr.slice(open + 1, close);
    out += expr.slice(i, fnStart);
    out += reduceAgg(fn, inner, rows, scalars).toFixed() + 'B';
    i = close + 1;
  }
  return out;
}

function reduceAgg(fn: string, inner: string, rows: WideRow[], scalars: Scalars): Decimal {
  const vals = rows.map((r) => evalRow(inner, r, scalars));
  switch (fn) {
    case 'SUM':
      return vals.reduce((a, b) => a.plus(b), new Decimal(0));
    case 'COUNT':
      return new Decimal(vals.length);
    case 'AVG':
      return vals.length === 0
        ? new Decimal(0)
        : vals.reduce((a, b) => a.plus(b), new Decimal(0))
          .dividedBy(vals.length)
          .toDecimalPlaces(CALCULATION_SCALE, Decimal.ROUND_HALF_UP);
    case 'MIN':
      return vals.length === 0 ? new Decimal(0) : Decimal.min(...vals);
    case 'MAX':
      return vals.length === 0 ? new Decimal(0) : Decimal.max(...vals);
    default:
      return new Decimal(0);
  }
}

// ─── literal substitution (backend evalRow / numLit) ─────────────────────────

const JAVA_BIGDECIMAL = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?$/;

function numLit(v: unknown): string {
  if (v === null || v === undefined) return '0B';
  const text = javaTrim(typeof v === 'string' ? v : String(v));
  if (!JAVA_BIGDECIMAL.test(text)) return '0B';
  return new Decimal(text).toFixed() + 'B';
}

function evalRow(expr: string, row: WideRow, scalars: Scalars): Decimal {
  const re = new RegExp(TOKEN_RE.source, 'g');
  const substituted = expr.replace(re, (_m, inner: string) => {
    const tok = parseTok(inner);
    if (tok.total) {
      const s = scalars.get(tok.raw);
      return (s ? s.toFixed() : '0') + 'B';
    }
    return numLit(row[tok.raw]);
  });
  return toBig(new JexlLite(substituted).evaluate());
}

// ─── JEXL-subset arithmetic ──────────────────────────────────────────────────

type Val =
  | { t: 'null' }
  | { t: 'bool'; b: boolean }
  | { t: 'int'; d: Decimal }
  | { t: 'dec'; d: Decimal }
  | { t: 'dbl'; n: number };

const NULL: Val = { t: 'null' };

function round12(d: Decimal, rm: Decimal.Rounding): Decimal {
  return d.toDecimalPlaces(CALCULATION_SCALE, rm);
}

function doubleToDecimal(n: number): Decimal {
  if (!Number.isFinite(n)) throw new Error('non-finite double');
  return new Decimal(String(n));
}

/** JexlArithmetic.toBigDecimal (mathScale 12, DECIMAL128 → HALF_EVEN). Boolean → 1 / 0. */
function toBD(v: Val): Decimal {
  switch (v.t) {
    case 'null': return new Decimal(0);
    case 'bool': return new Decimal(v.b ? 1 : 0);
    case 'int': return v.d;
    case 'dec': return round12(v.d, Decimal.ROUND_HALF_EVEN);
    case 'dbl': return Number.isNaN(v.n) ? new Decimal(0) : round12(doubleToDecimal(v.n), Decimal.ROUND_HALF_EVEN);
  }
}

function toDouble(v: Val): number {
  switch (v.t) {
    case 'null': return 0;
    case 'bool': return v.b ? 1 : 0;
    case 'dbl': return v.n;
    default: return v.d.toNumber();
  }
}

/** Operands JEXL treats as long numbers (Integer/Long/Boolean; null counts as 0). */
function isIntLike(v: Val): boolean {
  return v.t === 'int' || v.t === 'null' || v.t === 'bool';
}

type ArithOp = '+' | '-' | '*' | '%';

const DBL_ZERO: Val = { t: 'dbl', n: 0 };

function arith(op: ArithOp, a: Val, b: Val): Val {
  const apply = (x: Decimal, y: Decimal): Decimal =>
    op === '+' ? x.plus(y) : op === '-' ? x.minus(y) : op === '*' ? x.times(y) : x.mod(y);
  if (isIntLike(a) && isIntLike(b)) {
    const x = toBD(a);
    const y = toBD(b);
    // JEXL: modulo by zero is swallowed (non-strict) and yields 0.0 (Double)
    if (op === '%' && y.isZero()) return DBL_ZERO;
    return { t: 'int', d: apply(x, y) };
  }
  if (a.t === 'dec' || b.t === 'dec') {
    const x = toBD(a);
    const y = toBD(b);
    if (op === '%' && y.isZero()) return DBL_ZERO;
    const r = apply(x, y).toSignificantDigits(MC_PRECISION, Decimal.ROUND_HALF_EVEN);
    // JexlArithmetic.narrowBigDecimal: an integer operand + exact integer result → Integer/Long
    if ((isIntLike(a) || isIntLike(b)) && r.isInteger()) return { t: 'int', d: r };
    return { t: 'dec', d: r };
  }
  const x = toDouble(a);
  const y = toDouble(b);
  if (op === '%') return y === 0 ? DBL_ZERO : { t: 'dbl', n: x % y };
  return { t: 'dbl', n: op === '+' ? x + y : op === '-' ? x - y : x * y };
}

/**
 * SafeArithmetic.exact (repair-260916 B-8 / D-10): a double/float literal is converted through its
 * shortest decimal text (`1.13` → 1.13), non-finite → 0; everything else goes through
 * PrecisionPolicy.of (null / boolean / non-numeric → 0).
 */
function exact(v: Val): Decimal {
  switch (v.t) {
    case 'dbl': return Number.isFinite(v.n) ? doubleToDecimal(v.n) : new Decimal(0);
    case 'int':
    case 'dec': return v.d;
    default: return new Decimal(0);
  }
}

/** SafeArithmetic.divide. */
function divide(a: Val, b: Val): Val {
  const dividend = exact(a);
  if (b.t === 'null' || toBD(b).isZero()) {
    return { t: 'dec', d: round12(dividend, Decimal.ROUND_HALF_UP) };
  }
  const divisor = exact(b);
  // PrecisionPolicy.divide: divisor 0 (e.g. a boolean divisor) → 0
  if (divisor.isZero()) return { t: 'dec', d: new Decimal(0) };
  return { t: 'dec', d: round12(dividend.dividedBy(divisor), Decimal.ROUND_HALF_UP) };
}

function negate(v: Val): Val {
  switch (v.t) {
    case 'null': return v;
    case 'bool': return { t: 'bool', b: !v.b }; // JEXL: negating a Boolean is logical NOT
    case 'dbl': return { t: 'dbl', n: -v.n };
    default: return { t: v.t, d: v.d.negated() };
  }
}

type CmpOp = '==' | '!=' | '<' | '<=' | '>' | '>=';

/** Numeric comparison with JEXL operand typing; returns -1 / 0 / 1. */
function compareNumeric(a: Val, b: Val): number {
  if (a.t === 'dec' || b.t === 'dec') return toBD(a).comparedTo(toBD(b));
  if (a.t === 'dbl' || b.t === 'dbl') {
    const x = toDouble(a);
    const y = toDouble(b);
    return x < y ? -1 : x > y ? 1 : 0;
  }
  return toBD(a).comparedTo(toBD(b));
}

function compare(op: CmpOp, a: Val, b: Val): Val {
  if (op === '==' || op === '!=') {
    let eq: boolean;
    if (a.t === 'null' || b.t === 'null') eq = a.t === b.t;
    else if (a.t === 'bool' && b.t === 'bool') eq = a.b === b.b;
    else eq = compareNumeric(a, b) === 0;
    return { t: 'bool', b: op === '==' ? eq : !eq };
  }
  if (a.t === 'null' || b.t === 'null') return { t: 'bool', b: false };
  const c = compareNumeric(a, b);
  const r = op === '<' ? c < 0 : op === '<=' ? c <= 0 : op === '>' ? c > 0 : c >= 0;
  return { t: 'bool', b: r };
}

/** TabJoinPlanEvaluator.toBig: round to 12 places HALF_UP; null / boolean (non-numeric text) → 0. */
function toBig(v: Val): Decimal {
  switch (v.t) {
    case 'null':
    case 'bool':
      return new Decimal(0);
    case 'dbl':
      return Number.isFinite(v.n) ? round12(doubleToDecimal(v.n), Decimal.ROUND_HALF_UP) : new Decimal(0);
    default:
      return round12(v.d, Decimal.ROUND_HALF_UP);
  }
}

const NUMBER_RE = /^(?:\d+(?:\.\d+)?)(?:[eE][+-]?\d+)?([BbDdFfLlHh])?/;
const IDENT_RE = /^[A-Za-z_$][A-Za-z0-9_$]*/;
const CMP_RELATIONAL = ['<=', '>=', '<', '>'] as const;
const CMP_EQUALITY = ['==', '!='] as const;

/**
 * Recursive-descent evaluator for the subset of JEXL the backend actually meets:
 * numbers, booleans (true/false), unary +/-, `* / %`, `+ -`, one relational comparison
 * (`< <= > >=`), one equality comparison (`== !=`), parentheses, identifiers and function
 * calls (→ null). Chained comparisons are parse errors (as in JEXL). Anything else
 * (assignment, logical operators, ternary, strings, …) throws.
 */
class JexlLite {
  private i = 0;
  private readonly s: string;

  constructor(s: string) {
    this.s = s;
  }

  evaluate(): Val {
    this.ws();
    if (this.i >= this.s.length) return NULL; // empty script → null
    const v = this.equality();
    this.ws();
    if (this.i < this.s.length) this.fail();
    return v;
  }

  private fail(): never {
    throw new Error(`parsing error at ${this.i} in '${this.s}'`);
  }

  private ws(): void {
    while (this.i < this.s.length && /\s/.test(this.s[this.i])) this.i++;
  }

  private peekOp(ops: readonly string[]): string | null {
    this.ws();
    for (const op of ops) if (this.s.startsWith(op, this.i)) return op;
    return null;
  }

  private equality(): Val {
    const left = this.relational();
    const op = this.peekOp(CMP_EQUALITY);
    if (!op) return left;
    this.i += op.length;
    const v = compare(op as CmpOp, left, this.relational());
    if (this.peekOp(CMP_EQUALITY)) this.fail(); // JEXL: equality is not associative
    return v;
  }

  private relational(): Val {
    const left = this.additive();
    const op = this.peekOp(CMP_RELATIONAL);
    if (!op) return left;
    this.i += op.length;
    const v = compare(op as CmpOp, left, this.additive());
    if (this.peekOp(CMP_RELATIONAL)) this.fail(); // JEXL: relational is not associative
    return v;
  }

  private additive(): Val {
    let v = this.multiplicative();
    for (;;) {
      this.ws();
      const c = this.s[this.i];
      if (c !== '+' && c !== '-') return v;
      this.i++;
      v = arith(c, v, this.multiplicative());
    }
  }

  private multiplicative(): Val {
    let v = this.unary();
    for (;;) {
      this.ws();
      const c = this.s[this.i];
      if (c !== '*' && c !== '/' && c !== '%') return v;
      if ('*/%'.includes(this.s[this.i + 1] ?? '')) this.fail();
      this.i++;
      const r = this.unary();
      v = c === '/' ? divide(v, r) : arith(c, v, r);
    }
  }

  private unary(): Val {
    this.ws();
    const c = this.s[this.i];
    if (c === '-') { this.i++; return negate(this.unary()); }
    if (c === '+') { this.i++; return this.unary(); }
    return this.primary();
  }

  private primary(): Val {
    this.ws();
    const rest = this.s.slice(this.i);
    if (rest.startsWith('(')) {
      this.i++;
      const v = this.equality();
      this.ws();
      if (this.s[this.i] !== ')') this.fail();
      this.i++;
      return v;
    }
    const num = NUMBER_RE.exec(rest);
    if (num) {
      this.i += num[0].length;
      const text = num[0].replace(/[BbDdFfLlHh]$/, '');
      const suffix = (num[1] ?? '').toUpperCase();
      if (suffix === 'B') return { t: 'dec', d: new Decimal(text) };
      if (suffix === 'D' || suffix === 'F') return { t: 'dbl', n: Number(text) };
      if (suffix === 'L' || suffix === 'H') return { t: 'int', d: new Decimal(text) };
      if (/[.eE]/.test(text)) return { t: 'dbl', n: Number(text) };
      return { t: 'int', d: new Decimal(text) };
    }
    const id = IDENT_RE.exec(rest);
    if (id) {
      this.i += id[0].length;
      if (id[0] === 'true' || id[0] === 'false') return { t: 'bool', b: id[0] === 'true' };
      this.ws();
      if (this.s[this.i] === '(') {
        // unknown function (strict=false, silent=true) → null; arguments must still parse
        this.i++;
        this.ws();
        if (this.s[this.i] !== ')') {
          for (;;) {
            this.equality();
            this.ws();
            if (this.s[this.i] === ',') { this.i++; continue; }
            break;
          }
        }
        if (this.s[this.i] !== ')') this.fail();
        this.i++;
      }
      return NULL; // unknown variable / function → null
    }
    this.fail();
  }
}

// ─── alignment + column entry (backend alignByRowKey / evaluateColumn) ───────

function javaStr(v: unknown): string {
  return v === null || v === undefined ? 'null' : javaTrim(String(v));
}

function alignByRowKey(
  rowKeyFields: string[],
  tabRows: Map<string, Array<Record<string, unknown>>>,
): WideRow[] {
  const byKey = new Map<string, WideRow>();
  for (const [alias, rows] of tabRows) {
    for (const r of rows) {
      const keyStr = rowKeyFields.map((k) => javaStr(r[k])).join('');
      let merged = byKey.get(keyStr);
      if (!merged) { merged = {}; byKey.set(keyStr, merged); }
      for (const [fk, fv] of Object.entries(r)) merged[`${alias}.${fk}`] = fv;
    }
  }
  return [...byKey.values()];
}

function scalarOf(v: string | null | undefined): Decimal {
  if (v === null || v === undefined) return new Decimal(0);
  const text = javaTrim(String(v));
  return JAVA_BIGDECIMAL.test(text) ? new Decimal(text) : new Decimal(0);
}

/**
 * Evaluate one TAB_JOIN_FORMULA column (backend `evalExpression` over `evaluateColumn`'s plan).
 * Returns a 12-place calculation string ('0' for a blank expression). Throws on invalid input.
 */
export function evaluateTabJoinColumn(
  expression: string | null | undefined,
  tabs: TabJoinTabRef[],
  provider: TabJoinDataProvider,
): DecimalString {
  const expr = expression ?? '';
  if (javaTrim(expr) === '') return '0';

  // LinkedHashMap semantics: a later duplicate alias replaces the value, keeps first position.
  const tabOf = new Map<string, TabJoinTabRef>();
  for (const t of tabs ?? []) tabOf.set(t.alias, t);

  const detailAliases = new Set<string>();
  const scalars: Scalars = new Map();
  forEachToken(expr, (inner) => {
    const tok = parseTok(inner);
    const tab = tabOf.get(tok.alias);
    if (tok.total) {
      let s: string | null | undefined;
      if (tab) {
        s = tok.column !== null ? provider.subtotalOfColumn(tab, tok.column) : provider.subtotalOf(tab);
      }
      scalars.set(tok.raw, scalarOf(s));
    } else if (tab) {
      detailAliases.add(tok.alias);
    }
    // undeclared detail alias: skipped (reads 0), same as the backend
  });

  let aligned: WideRow[] = [];
  if (detailAliases.size > 0) {
    let rkf: string[] = [];
    for (const alias of detailAliases) {
      const candidate = tabOf.get(alias)?.rowKeyFields ?? [];
      if (candidate.length > 0) { rkf = candidate; break; }
    }
    const tabRows = new Map<string, Array<Record<string, unknown>>>();
    for (const alias of detailAliases) {
      const tab = tabOf.get(alias)!;
      if (tab.tabKey != null) tabRows.set(alias, provider.rowsOf(tab) ?? []);
    }
    aligned = alignByRowKey(rkf, tabRows);
  }

  let total = new Decimal(0);
  for (const term of splitTerms(expr)) {
    const resolved = replaceAggregates(term.text, aligned, scalars);
    let termVal: Decimal;
    if (hasBareDetail(term.text)) {
      termVal = aligned.reduce((acc, r) => acc.plus(evalRow(resolved, r, scalars)), new Decimal(0));
    } else {
      termVal = evalRow(resolved, {}, scalars);
    }
    total = term.sign >= 0 ? total.plus(termVal) : total.minus(termVal);
  }
  return toCalculationString(total);
}
