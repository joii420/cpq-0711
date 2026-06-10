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
 *   [alias.field]       — cross-tab detail ref (dot, no (总计)) → agg='NONE'
 *   [alias.field(总计)] — cross-tab aggregated column total   → agg='SUM'
 *   [alias(总计)]       — cross-tab whole-tab total (no dot, (总计) suffix) → agg='SUM', target=''
 *   {path}              — BNF path token (minimal / out-of-scope for page-tab formulas)
 *   + - * / × ÷         — arithmetic operators (× → *, ÷ → /)
 *   ( )                 — bracket_open / bracket_close
 *   numeric literals    — number tokens
 *
 * match row-key alignment convention:
 *   match[i] = { a: tabDef.rowKeyFields[i], b: selfRowKeyFields[i] }
 *   Aligned positionally; if one side runs out, remaining entries are omitted.
 *   If either array is empty, match = [].
 */

import type { FormulaToken } from './types';
import type { TabDef } from '../../services/tabJoinFormulaService';

// Re-export TabDef for convenience of tests (they import from this module)
export type { TabDef };

// ─────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────

/** Build the match array by positionally aligning rowKeyFields of the source (a) and self (b). */
function buildMatch(
  tabRowKeyFields: string[],
  selfRowKeyFields: string[] | undefined,
): Array<{ a: string; b: string }> {
  if (!tabRowKeyFields.length || !selfRowKeyFields?.length) return [];
  const count = Math.min(tabRowKeyFields.length, selfRowKeyFields.length);
  const result: Array<{ a: string; b: string }> = [];
  for (let i = 0; i < count; i++) {
    result.push({ a: tabRowKeyFields[i], b: selfRowKeyFields[i] });
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
  | { kind: 'whitespace' };

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

  for (const raw of rawTokens) {
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

          result.push({
            type: 'cross_tab_ref',
            source: tabDef.componentId,
            sourceLabel: tabDef.componentName ?? alias,
            target: fieldPart,
            agg: isAgg ? 'SUM' : 'NONE',
            match: buildMatch(tabDef.rowKeyFields ?? [], selfRowKeyFields),
          });
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
            result.push({
              type: 'cross_tab_ref',
              source: tabDef.componentId,
              sourceLabel: tabDef.componentName ?? alias,
              target: '',
              agg: 'SUM',
              match: buildMatch(tabDef.rowKeyFields ?? [], selfRowKeyFields),
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
