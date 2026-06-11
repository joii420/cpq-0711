/**
 * formulaSerialize.ts
 *
 * Task 4.4 — Token 序列化桥 (pure module, no side effects, no imports from React/api).
 *
 * Converts between:
 *   - TabJoinFormulaDrawer's bracket-string expression (the "drawer string") used for
 *     TAB_JOIN_FORMULA columns in the Excel-view config, and
 *   - The component-formula FormulaToken[] model used in component definitions.
 *
 * Grammar of the drawer string (confirmed from TabJoinFormulaDrawer.buildColumn + TabFieldMatrix):
 *   [field]             — same-row field of THIS component  (no dot, no suffix)
 *   [alias.subtotalCol] — sibling's SUBTOTAL column (字段 ∈ tabDef.subtotalCols) → component_subtotal
 *   [alias.field]       — cross-tab detail ref (dot, no (总计), detailField) → cross_tab_ref agg='NONE'
 *   [alias.field(总计)] — cross-tab aggregated DETAIL column total           → cross_tab_ref agg='SUM'
 *   [alias(总计)]       — sibling's SUBTOTAL (no dot, (总计) suffix) → component_subtotal
 *                         (value = tab's first/primary subtotalCol, or '' if none)
 *   {path}              — BNF path token (minimal / out-of-scope for page-tab formulas)
 *   + - * / × ÷         — arithmetic operators (× → *, ÷ → /)
 *   ( )                 — bracket_open / bracket_close
 *   numeric literals    — number tokens
 *
 * match row-key alignment convention:
 *   match = common field-name intersection of source rowKeyFields and self rowKeyFields.
 *   For each field f in selfRowKeyFields that also appears in source rowKeyFields,
 *   emit { a: f, b: f }. Order follows selfRowKeyFields (host) for determinism.
 *   No common field → [] (validator rejects empty match downstream).
 *   If either array is empty, match = [].
 */

import type { FormulaToken } from './types';
import type { TabDef } from '../../services/tabJoinFormulaService';

// Re-export TabDef for convenience of tests (they import from this module)
export type { TabDef };

// ─────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────

/**
 * Build match[] by COMMON ROW-KEY FIELD NAME intersection (order-independent).
 * For each field f in selfRowKeyFields that also appears in source rowKeyFields,
 * emit { a: f, b: f }. Order follows selfRowKeyFields (host) for determinism.
 * No common field → [] (validator rejects empty match downstream).
 */
function buildMatch(
  tabRowKeyFields: string[],
  selfRowKeyFields: string[] | undefined,
): Array<{ a: string; b: string }> {
  if (!tabRowKeyFields.length || !selfRowKeyFields?.length) return [];
  const sourceSet = new Set(tabRowKeyFields);
  const result: Array<{ a: string; b: string }> = [];
  for (const f of selfRowKeyFields) {
    if (sourceSet.has(f)) result.push({ a: f, b: f });
  }
  return result;
}

/** Normalize × → * and ÷ → / */
function normalizeOp(op: string): string {
  if (op === '×') return '*';
  if (op === '÷') return '/';
  return op;
}

// ─────────────────────────────────────────────
// Tokeniser: lex the drawer expression string into raw segments
// ─────────────────────────────────────────────

type RawToken =
  | { kind: 'bracket_expr'; body: string }   // [...]
  | { kind: 'brace_expr'; body: string }     // {...}
  | { kind: 'number'; value: string }
  | { kind: 'operator'; value: string }
  | { kind: 'paren_open' }
  | { kind: 'paren_close' }
  | { kind: 'whitespace' }
  | { kind: 'func'; name: string }           // SUM/AVG/MAX/MIN/COUNT

/**
 * Lex `expr` into raw tokens. Throws with a descriptive message on unrecognised input.
 */
function lex(expr: string): RawToken[] {
  const tokens: RawToken[] = [];
  let i = 0;
  while (i < expr.length) {
    const ch = expr[i];

    // Whitespace — skip
    if (/\s/.test(ch)) {
      i++;
      continue;
    }

    // Bracketed expression [...]
    if (ch === '[') {
      const end = expr.indexOf(']', i);
      if (end === -1) throw new Error(`表达式中 '[' 缺少对应的 ']'，位置 ${i}`);
      tokens.push({ kind: 'bracket_expr', body: expr.slice(i + 1, end) });
      i = end + 1;
      continue;
    }

    // Braced path {path}
    if (ch === '{') {
      const end = expr.indexOf('}', i);
      if (end === -1) throw new Error(`表达式中 '{' 缺少对应的 '}'，位置 ${i}`);
      tokens.push({ kind: 'brace_expr', body: expr.slice(i + 1, end) });
      i = end + 1;
      continue;
    }

    // Parentheses
    if (ch === '(') { tokens.push({ kind: 'paren_open' }); i++; continue; }
    if (ch === ')') { tokens.push({ kind: 'paren_close' }); i++; continue; }

    // Operators (single-char): + - * / × ÷
    if ('+-*/×÷'.includes(ch)) {
      tokens.push({ kind: 'operator', value: ch });
      i++;
      continue;
    }

    // Function names: SUM/AVG/MAX/MIN/COUNT (case-insensitive)
    if (/[A-Za-z]/.test(ch)) {
      let word = '';
      while (i < expr.length && /[A-Za-z]/.test(expr[i])) word += expr[i++];
      const upper = word.toUpperCase();
      if (['SUM', 'AVG', 'MAX', 'MIN', 'COUNT'].includes(upper)) {
        tokens.push({ kind: 'func', name: upper });
        continue;
      }
      throw new Error(`表达式中含有无法识别的标识符 '${word}'（位置 ${i - word.length}）`);
    }

    // Numeric literals (integer or decimal, optional leading sign NOT consumed here)
    if (/[0-9]/.test(ch) || (ch === '.' && /[0-9]/.test(expr[i + 1] ?? ''))) {
      let num = '';
      while (i < expr.length && /[0-9.]/.test(expr[i])) {
        num += expr[i++];
      }
      tokens.push({ kind: 'number', value: num });
      continue;
    }

    throw new Error(`表达式中含有无法识别的字符 '${ch}'（位置 ${i}）`);
  }
  return tokens;
}

// ─────────────────────────────────────────────
// makeCrossTabRef helper
// ─────────────────────────────────────────────

/**
 * Build a cross_tab_ref FormulaToken from an alias + field + agg.
 * Throws if the alias is not found in tabDefs or lacks a componentId.
 */
function makeCrossTabRef(
  alias: string,
  field: string,
  agg: FormulaToken['agg'],
  tabDefs: TabDef[],
  selfRowKeyFields?: string[],
): FormulaToken {
  const tabDef = tabDefs.find((d) => d.alias === alias);
  if (!tabDef) throw new Error(`表达式中引用了未知页签别名 "${alias}"`);
  if (!tabDef.componentId) throw new Error(`页签 "${alias}" 缺少 componentId`);
  return {
    type: 'cross_tab_ref',
    source: tabDef.componentId,
    sourceLabel: tabDef.componentName ?? alias,
    target: field,
    agg: agg ?? 'NONE',
    match: buildMatch(tabDef.rowKeyFields ?? [], selfRowKeyFields),
  };
}

// ─────────────────────────────────────────────
// expressionToTokens
// ─────────────────────────────────────────────

/**
 * Convert a TabJoinFormulaDrawer expression string to FormulaToken[].
 *
 * @param expr              The drawer expression string (e.g. "[单重] * [COMP_RL.金额(总计)]")
 * @param tabDefs           TabDef list for the current template (for alias → componentId resolution)
 * @param selfRowKeyFields  Row-key fields of THIS (self) component, used to build match[] pairs
 */
export function expressionToTokens(
  expr: string,
  tabDefs: TabDef[],
  selfRowKeyFields?: string[],
): FormulaToken[] {
  const rawTokens = lex(expr);
  const result: FormulaToken[] = [];

  let k = 0;
  while (k < rawTokens.length) {
    const raw = rawTokens[k];

    // ── FN([alias.field]) 前瞻折叠 ──
    // Pattern: func paren_open bracket_expr paren_close
    if (raw.kind === 'func') {
      const fnName = raw.name as NonNullable<FormulaToken['agg']>;
      const next1 = rawTokens[k + 1];
      const next2 = rawTokens[k + 2];
      const next3 = rawTokens[k + 3];

      // Validate: must be followed by ( bracket_expr )
      if (!next1 || next1.kind !== 'paren_open') {
        throw new Error(`函数 ${fnName} 后必须紧跟 '('`);
      }
      // next2 must be exactly ONE bracket_expr (no operators or extra tokens before close)
      if (!next2 || next2.kind !== 'bracket_expr') {
        throw new Error(`${fnName}() 只支持单列引用，括号内不能含运算符或其他表达式`);
      }
      // next3 must be paren_close — if not, there are multiple tokens inside parens
      if (!next3 || next3.kind !== 'paren_close') {
        throw new Error(`${fnName}() 只支持单列引用，括号内不能含多个 token 或运算符`);
      }

      // Validate: bracket_expr body must be a cross-tab reference (contains '.')
      const body = next2.body.trim();
      if (!body.includes('.')) {
        throw new Error(
          `${fnName}() 内只支持跨页签明细引用 [alias.field]，不支持裸字段 [${body}]`,
        );
      }

      const dotIdx = body.indexOf('.');
      const alias = body.slice(0, dotIdx);
      const fieldPart = body.slice(dotIdx + 1);

      result.push(makeCrossTabRef(alias, fieldPart, fnName, tabDefs, selfRowKeyFields));
      k += 4; // consume: func + paren_open + bracket_expr + paren_close
      continue;
    }

    switch (raw.kind) {
      case 'whitespace':
        // already skipped in lex
        break;

      case 'paren_open':
        result.push({ type: 'bracket_open' });
        break;

      case 'paren_close':
        result.push({ type: 'bracket_close' });
        break;

      case 'operator':
        result.push({ type: 'operator', value: normalizeOp(raw.value) });
        break;

      case 'number':
        result.push({ type: 'number', value: raw.value });
        break;

      case 'brace_expr':
        // BNF path token — minimal support
        result.push({ type: 'path', path: raw.body });
        break;

      case 'bracket_expr': {
        const body = raw.body.trim();

        if (body.includes('.')) {
          // Cross-tab reference: [alias.field] or [alias.field(总计)]
          const dotIdx = body.indexOf('.');
          const alias = body.slice(0, dotIdx);
          let fieldPart = body.slice(dotIdx + 1);

          // Detect (总计) suffix on the field part
          const isAgg = fieldPart.endsWith('(总计)');
          if (isAgg) {
            fieldPart = fieldPart.slice(0, -'(总计)'.length);
          }

          // Resolve alias → tabDef
          const tabDef = tabDefs.find((d) => d.alias === alias);
          if (!tabDef) {
            throw new Error(
              `表达式中引用了未知页签别名 "${alias}"，请检查别名是否与模板中页签配置一致`,
            );
          }
          if (!tabDef.componentId) {
            throw new Error(
              `页签 "${alias}" 缺少 componentId，无法构建跨页签引用 token`,
            );
          }

          // Disambiguate subtotal column vs detail field:
          //   if 字段 ∈ tabDef.subtotalCols → component_subtotal (scalar sibling subtotal),
          //   else (and NOT aggregated) → cross_tab_ref detail (row-aligned).
          // Note: an explicit (总计) aggregate over a DETAIL field stays a cross_tab_ref SUM.
          if (!isAgg && (tabDef.subtotalCols ?? []).includes(fieldPart)) {
            result.push({
              type: 'component_subtotal',
              value: fieldPart,
              tab_name: fieldPart,
              component_code: alias,
              label: `${tabDef.componentName ?? alias}·${fieldPart}`,
            });
          } else {
            result.push(
              makeCrossTabRef(alias, fieldPart, isAgg ? 'SUM' : 'NONE', tabDefs, selfRowKeyFields),
            );
          }
        } else {
          // Check for whole-tab total: [alias(总计)] (no dot but ends in (总计))
          if (body.endsWith('(总计)')) {
            const alias = body.slice(0, -'(总计)'.length);
            const tabDef = tabDefs.find((d) => d.alias === alias);
            if (!tabDef) {
              throw new Error(
                `表达式中引用了未知页签别名 "${alias}"（总计引用），请检查别名是否与模板中页签配置一致`,
              );
            }
            if (!tabDef.componentId) {
              throw new Error(
                `页签 "${alias}" 缺少 componentId，无法构建跨页签引用 token`,
              );
            }
            // FIX (2026-06-10): bare [alias(总计)] means "the SUBTOTAL of tab alias",
            // which the evaluator consumes as a component_subtotal token (NOT cross_tab_ref).
            // Convention for the column to use:
            //   • if the tab has ≥1 subtotalCol → use the FIRST (primary) subtotalCol as value;
            //   • if the tab has NO subtotalCol → emit empty value (component_code still set),
            //     which renders back as [alias(总计)] and lets the evaluator resolve the
            //     component's default subtotal.
            const primarySubtotal = (tabDef.subtotalCols ?? [])[0] ?? '';
            result.push({
              type: 'component_subtotal',
              value: primarySubtotal,
              tab_name: primarySubtotal,
              component_code: alias,
              label: primarySubtotal
                ? `${tabDef.componentName ?? alias}·${primarySubtotal}`
                : (tabDef.componentName ?? alias),
            });
          } else {
            // Plain same-component field: [field]
            result.push({ type: 'field', value: body });
          }
        }
        break;
      }

      default:
        // Exhaustive check
        break;
    }

    k += 1;
  }

  return result;
}

// ─────────────────────────────────────────────
// tokensToDrawerExpression
// ─────────────────────────────────────────────

/**
 * Convert FormulaToken[] back to a drawer expression string.
 * Inverse of expressionToTokens (round-trips the subset that expressionToTokens produces).
 *
 * @param tokens  FormulaToken[] array
 * @param tabDefs TabDef list (for componentId → alias resolution in cross_tab_ref tokens)
 */
export function tokensToDrawerExpression(
  tokens: FormulaToken[],
  tabDefs: TabDef[],
): string {
  const parts: string[] = [];

  for (const token of tokens) {
    switch (token.type) {
      case 'field':
        parts.push(`[${token.value ?? ''}]`);
        break;

      case 'operator':
        // Preserve * and / literally (normalisation already done on parse)
        parts.push(` ${token.value ?? ''} `);
        break;

      case 'bracket_open':
        parts.push('(');
        break;

      case 'bracket_close':
        parts.push(')');
        break;

      case 'number':
        parts.push(token.value ?? '');
        break;

      case 'path':
        parts.push(`{${token.path ?? ''}}`);
        break;

      case 'component_subtotal': {
        // Render from the TOKEN'S OWN fields — robust even if the referenced
        // sibling component is not present in tabDefs (display must not break).
        const code = token.component_code ?? '';
        const col = token.value ?? '';
        if (col) {
          parts.push(`[${code}.${col}]`);
        } else {
          parts.push(`[${code}(总计)]`);
        }
        break;
      }

      case 'cross_tab_ref': {
        // Resolve componentId → alias
        const tabDef = tabDefs.find((d) => d.componentId === token.source);
        const alias = tabDef?.alias ?? token.sourceLabel ?? token.source ?? '';

        if (!token.target) {
          // Whole-tab total [alias(总计)]
          parts.push(`[${alias}(总计)]`);
        } else if (token.agg && token.agg !== 'NONE') {
          // Aggregated column: [alias.field(总计)]
          parts.push(`[${alias}.${token.target}(总计)]`);
        } else {
          // Detail reference: [alias.field]
          parts.push(`[${alias}.${token.target}]`);
        }
        break;
      }

      default:
        // Other token types (component_subtotal, product_attribute, etc.) are not produced
        // by expressionToTokens and have no drawer-string representation — skip.
        break;
    }
  }

  // Collapse multiple spaces around operators but preserve surrounding spaces neatly
  return parts.join('').replace(/\s{2,}/g, ' ').trim();
}

// ─────────────────────────────────────────────
// checkMappable
// ─────────────────────────────────────────────

/** test-only：暴露 lex 给单测 */
export const __lexForTest = (expr: string) => lex(expr);

/**
 * Gate check mirroring the backend TokenMappabilityValidator rule:
 * If ≥ 2 cross_tab_ref tokens have agg === 'NONE' (row-detail alignment),
 * the formula cannot be trivially mapped to a row-keyed join and is "unmappable".
 *
 * One detail ref is fine (it drives the row iteration).
 * Two or more require multi-table alignment that Excel handles better.
 */
export function checkMappable(tokens: FormulaToken[]): {
  mappable: boolean;
  reason?: string;
} {
  const noneCount = tokens.filter(
    (t) => t.type === 'cross_tab_ref' && (!t.agg || t.agg === 'NONE'),
  ).length;

  if (noneCount >= 2) {
    return {
      mappable: false,
      reason: '存在 2+ 个未聚合的跨页签明细引用，请改用 Excel 组件',
    };
  }

  return { mappable: true };
}
