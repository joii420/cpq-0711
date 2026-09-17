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
 *     otherwise quotient at 12 places HALF_UP; a double operand on either side THROWS
 *     (`PrecisionPolicy.of` rejects floating point) — the backend throws too;
 *   - every row result and the column result are rounded to 12 places HALF_UP;
 *   - syntax errors throw; an unknown identifier / function call evaluates to null (= 0).
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

/** JexlArithmetic.toBigDecimal (mathScale 12, DECIMAL128 → HALF_EVEN). */
function toBD(v: Val): Decimal {
  switch (v.t) {
    case 'null': return new Decimal(0);
    case 'int': return v.d;
    case 'dec': return round12(v.d, Decimal.ROUND_HALF_EVEN);
    case 'dbl': return Number.isNaN(v.n) ? new Decimal(0) : round12(doubleToDecimal(v.n), Decimal.ROUND_HALF_EVEN);
  }
}

function toDouble(v: Val): number {
  switch (v.t) {
    case 'null': return 0;
    case 'dbl': return v.n;
    default: return v.d.toNumber();
  }
}

function isIntLike(v: Val): boolean {
  return v.t === 'int' || v.t === 'null';
}

function arith(op: '+' | '-' | '*', a: Val, b: Val): Val {
  if (isIntLike(a) && isIntLike(b)) {
    const x = toBD(a);
    const y = toBD(b);
    return { t: 'int', d: op === '+' ? x.plus(y) : op === '-' ? x.minus(y) : x.times(y) };
  }
  if (a.t === 'dec' || b.t === 'dec') {
    const x = toBD(a);
    const y = toBD(b);
    const raw = op === '+' ? x.plus(y) : op === '-' ? x.minus(y) : x.times(y);
    const r = raw.toSignificantDigits(MC_PRECISION, Decimal.ROUND_HALF_EVEN);
    // JexlArithmetic.narrowBigDecimal: an integer operand + exact integer result → Integer/Long
    if ((a.t === 'int' || b.t === 'int') && r.isInteger()) return { t: 'int', d: r };
    return { t: 'dec', d: r };
  }
  const x = toDouble(a);
  const y = toDouble(b);
  return { t: 'dbl', n: op === '+' ? x + y : op === '-' ? x - y : x * y };
}

/** PrecisionPolicy.of: floating point is rejected. */
function precisionOf(v: Val): Decimal {
  if (v.t === 'dbl') throw new Error('Precision-sensitive values must not use floating point');
  return v.t === 'null' ? new Decimal(0) : v.d;
}

/** SafeArithmetic.divide. */
function divide(a: Val, b: Val): Val {
  const dividend = precisionOf(a);
  if (b.t === 'null' || toBD(b).isZero()) {
    return { t: 'dec', d: round12(dividend, Decimal.ROUND_HALF_UP) };
  }
  const divisor = precisionOf(b);
  return { t: 'dec', d: round12(dividend.dividedBy(divisor), Decimal.ROUND_HALF_UP) };
}

function negate(v: Val): Val {
  switch (v.t) {
    case 'null': return v;
    case 'dbl': return { t: 'dbl', n: -v.n };
    default: return { t: v.t, d: v.d.negated() };
  }
}

/** ExcelViewService-side TabJoinPlanEvaluator.toBig: round to 12 places HALF_UP; null → 0. */
function toBig(v: Val): Decimal {
  switch (v.t) {
    case 'null': return new Decimal(0);
    case 'dbl':
      return Number.isFinite(v.n) ? round12(doubleToDecimal(v.n), Decimal.ROUND_HALF_UP) : new Decimal(0);
    default:
      return round12(v.d, Decimal.ROUND_HALF_UP);
  }
}

const NUMBER_RE = /^(?:\d+(?:\.\d+)?)(?:[eE][+-]?\d+)?([BbDdFfLlHh])?/;
const IDENT_RE = /^[A-Za-z_$][A-Za-z0-9_$]*/;

/**
 * Recursive-descent evaluator for the arithmetic subset of JEXL the backend actually meets:
 * numbers, unary +/-, binary + - * /, parentheses, identifiers and function calls (→ null).
 * Anything else (comparisons, assignment, `%`, strings, …) throws.
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
    const v = this.additive();
    this.ws();
    if (this.i < this.s.length) throw new Error(`parsing error at ${this.i} in '${this.s}'`);
    return v;
  }

  private ws(): void {
    while (this.i < this.s.length && /\s/.test(this.s[this.i])) this.i++;
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
      if (c !== '*' && c !== '/') return v;
      if (this.s[this.i + 1] === '*' || this.s[this.i + 1] === '/') {
        throw new Error(`parsing error at ${this.i} in '${this.s}'`);
      }
      this.i++;
      const r = this.unary();
      v = c === '*' ? arith('*', v, r) : divide(v, r);
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
      const v = this.additive();
      this.ws();
      if (this.s[this.i] !== ')') throw new Error(`parsing error at ${this.i} in '${this.s}'`);
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
      this.ws();
      if (this.s[this.i] === '(') {
        // unknown function (strict=false, silent=true) → null; arguments must still parse
        this.i++;
        this.ws();
        if (this.s[this.i] !== ')') {
          for (;;) {
            this.additive();
            this.ws();
            if (this.s[this.i] === ',') { this.i++; continue; }
            break;
          }
        }
        if (this.s[this.i] !== ')') throw new Error(`parsing error at ${this.i} in '${this.s}'`);
        this.i++;
      }
      return NULL; // unknown variable / function → null
    }
    throw new Error(`parsing error at ${this.i} in '${this.s}'`);
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
