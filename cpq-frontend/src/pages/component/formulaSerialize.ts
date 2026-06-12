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
  | { kind: 'func'; name: string }           // SUM/AVG/MAX/MIN/COUNT/KSUM/KAVG/KMAX/KMIN/KCOUNT

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

    // Function names: SUM/AVG/MAX/MIN/COUNT/KSUM/KAVG/KMAX/KMIN/KCOUNT (case-insensitive)
    if (/[A-Za-z]/.test(ch)) {
      let word = '';
      while (i < expr.length && /[A-Za-z]/.test(expr[i])) word += expr[i++];
      const upper = word.toUpperCase();
      const OUTER_FNS = ['SUM', 'AVG', 'MAX', 'MIN', 'COUNT'];
      const INNER_FNS = ['KSUM', 'KAVG', 'KMAX', 'KMIN', 'KCOUNT'];
      if ([...OUTER_FNS, ...INNER_FNS].includes(upper)) {
        tokens.push({ kind: 'func', name: upper });
        continue;
      }
      // 【C3】单字母 K + 空白 + 聚合词 → 误拆专门文案
      if (upper === 'K') {
        let j = i; while (j < expr.length && /\s/.test(expr[j])) j++;
        let peek = ''; let p = j; while (p < expr.length && /[A-Za-z]/.test(expr[p])) peek += expr[p++];
        if (OUTER_FNS.includes(peek.toUpperCase())) {
          throw new Error(`KSUM/KAVG/KMAX/KMIN/KCOUNT 不能拆写，请连写（应写成 "K${peek.toUpperCase()}"，不要写成 "K ${peek.toUpperCase()}"）`);
        }
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
 * 按引用串定位页签：**名称(componentName)优先、编号(alias)兜底**。
 * 公式里用页签中文名(如 [元素.单价])更可读；旧公式用编号(如 [COMP-0029.单价])仍兼容。
 * 同名页签罕见，按名称命中第一个；否则回退编号匹配。
 */
function findTabByRef(tabDefs: TabDef[], ref: string): TabDef | undefined {
  return tabDefs.find((d) => d.componentName === ref) ?? tabDefs.find((d) => d.alias === ref);
}

/**
 * Build a cross_tab_ref FormulaToken from a ref(名称或编号) + field + agg.
 * Throws if the ref is not found in tabDefs or lacks a componentId.
 */
function makeCrossTabRef(
  alias: string,
  field: string,
  agg: FormulaToken['agg'],
  tabDefs: TabDef[],
  selfRowKeyFields?: string[],
): FormulaToken {
  const tabDef = findTabByRef(tabDefs, alias);
  if (!tabDef) throw new Error(`表达式中引用了未知页签 "${alias}"`);
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
  /**
   * 宿主(self)组件 componentId。提供时，FN(...) 行级表达式内 `[别名.列]` 若 alias 的
   * componentId === selfComponentId，判为宿主自身列 → b_field（逐 join 行广播宿主当前行值）；
   * 否则判为细 source 列 → field（逐命中行取值）。不传时一律按 source 列处理（旧行为）。
   */
  selfComponentId?: string,
): FormulaToken[] {
  const rawTokens = lex(expr);
  const result: FormulaToken[] = [];

  let k = 0;
  while (k < rawTokens.length) {
    const raw = rawTokens[k];

    // ── FN(...) 折叠 ──
    //   单列   FN([alias.field])            → 旧 cross_tab_ref（单 target，向后兼容）
    //   行级   FN([宿主.列] * [source.列])  → cross_tab_ref + targetExpr（SUMPRODUCT）
    //          按行键 LEFT JOIN，逐 join 行算 targetExpr，再按宿主行键聚合。
    if (raw.kind === 'func') {
      const fnName = raw.name as NonNullable<FormulaToken['agg']>;
      const next1 = rawTokens[k + 1];
      if (!next1 || next1.kind !== 'paren_open') {
        throw new Error(`函数 ${fnName} 后必须紧跟 '('`);
      }

      // 扫描与 FN 的 '(' 匹配的 ')'（支持嵌套括号）
      let depth = 0;
      let closeIdx = -1;
      for (let j = k + 1; j < rawTokens.length; j++) {
        const rt = rawTokens[j];
        if (rt.kind === 'paren_open') depth++;
        else if (rt.kind === 'paren_close') {
          depth--;
          if (depth === 0) { closeIdx = j; break; }
        }
      }
      if (closeIdx === -1) throw new Error(`函数 ${fnName} 的 '(' 缺少对应的 ')'`);
      const bodyTokens = rawTokens.slice(k + 2, closeIdx); // FN 括号内 raw tokens

      // 单列快捷路径：恰好一个 [alias.field] → 旧单 target cross_tab_ref（保持现状）
      if (bodyTokens.length === 1 && bodyTokens[0].kind === 'bracket_expr') {
        const body = bodyTokens[0].body.trim();
        if (!body.includes('.')) {
          throw new Error(
            `${fnName}() 内只支持跨页签明细引用 [alias.field]，不支持裸字段 [${body}]`,
          );
        }
        const dotIdx = body.indexOf('.');
        result.push(
          makeCrossTabRef(body.slice(0, dotIdx), body.slice(dotIdx + 1), fnName, tabDefs, selfRowKeyFields),
        );
        k = closeIdx + 1;
        continue;
      }

      // 行级表达式路径：解析 bodyTokens → targetExpr（宿主列 b_field / source列 field）
      const targetExpr: FormulaToken[] = [];
      // 多 source 收集：按遇到顺序去重（同一 componentId 只保留一份）
      const srcTabsSeen: TabDef[] = [];
      const srcTabSeenIds = new Set<string>();
      let lastKind: string | null = null;
      for (const rt of bodyTokens) {
        // 相邻两"值项"之间必须有运算符（防 `[a][b]` 这类畸形 → 求值静默返 0）
        const startsValue =
          rt.kind === 'bracket_expr' || rt.kind === 'number' || rt.kind === 'paren_open';
        const lastEndsValue =
          lastKind === 'bracket_expr' || lastKind === 'number' || lastKind === 'paren_close';
        if (startsValue && lastEndsValue) {
          throw new Error(`${fnName}() 内表达式缺少运算符（相邻两项之间需 + - * /）`);
        }
        lastKind = rt.kind;
        switch (rt.kind) {
          case 'operator':
            targetExpr.push({ type: 'operator', value: normalizeOp(rt.value) });
            break;
          case 'number':
            targetExpr.push({ type: 'number', value: rt.value });
            break;
          case 'paren_open':
            targetExpr.push({ type: 'bracket_open' });
            break;
          case 'paren_close':
            targetExpr.push({ type: 'bracket_close' });
            break;
          case 'func':
            throw new Error(`${fnName}() 内暂不支持嵌套聚合函数`);
          case 'brace_expr':
            throw new Error(`${fnName}() 内暂不支持 {路径} 引用`);
          case 'bracket_expr': {
            const bb = rt.body.trim();
            if (!bb.includes('.')) {
              // 裸字段 = 宿主本行列 → b_field（逐 join 行广播）
              targetExpr.push({ type: 'b_field', value: bb });
              break;
            }
            const di = bb.indexOf('.');
            const al = bb.slice(0, di);
            const col = bb.slice(di + 1);
            const td = findTabByRef(tabDefs, al);
            if (!td) throw new Error(`${fnName}() 内引用了未知页签 "${al}"`);
            if (!td.componentId) throw new Error(`页签 "${al}" 缺少 componentId`);
            if (selfComponentId && td.componentId === selfComponentId) {
              // 宿主自身列 → b_field
              targetExpr.push({ type: 'b_field', value: col });
            } else {
              // 细/兄弟 source 列 → field；记录 source componentId 供多 source 校验
              if (!srcTabSeenIds.has(td.componentId)) {
                srcTabSeenIds.add(td.componentId);
                srcTabsSeen.push(td);
              }
              // field token 保持原样（不附带 source），多 source 信息通过外层 sources 数组传递
              targetExpr.push({ type: 'field', value: col });
            }
            break;
          }
          default:
            break;
        }
      }
      if (srcTabsSeen.length === 0) {
        throw new Error(
          `${fnName}() 行级聚合必须引用至少一个细页签明细列 [页签别名.字段]`,
        );
      }

      // ── 多 source 校验 + token 组装 ──
      if (srcTabsSeen.length === 1) {
        // N=1：原行为，不写 sources（字节级兼容存量 token）
        const srcTabDef = srcTabsSeen[0];
        result.push({
          type: 'cross_tab_ref',
          source: srcTabDef.componentId,
          sourceLabel: srcTabDef.componentName ?? srcTabDef.alias,
          target: '',
          agg: fnName,
          match: buildMatch(srcTabDef.rowKeyFields ?? [], selfRowKeyFields),
          targetExpr,
        });
      } else {
        // N>=2：验证所有 source 页签行键两两可比（含宿主，宿主用 selfRowKeyFields）
        const selfRKF = selfRowKeyFields ?? [];
        const allSets: Array<{ tab: TabDef | null; rkf: string[] }> = [
          { tab: null, rkf: selfRKF },
          ...srcTabsSeen.map((td) => ({ tab: td, rkf: td.rowKeyFields ?? [] })),
        ];
        for (let a = 0; a < allSets.length; a++) {
          for (let b = a + 1; b < allSets.length; b++) {
            if (!comparable(allSets[a].rkf, allSets[b].rkf)) {
              const nameA = allSets[a].tab?.componentName ?? allSets[a].tab?.alias ?? '宿主';
              const nameB = allSets[b].tab?.componentName ?? allSets[b].tab?.alias ?? '宿主';
              throw new Error(
                `页签「${nameA}」与「${nameB}」行键不可比（互不包含），无法同进一个 ${fnName}；请改用 KSUM 聚合其中更细/不可比的页签`,
              );
            }
          }
        }
        // 按 rowKeyFields 长度排序：最细（字段最多）→ 更粗
        const ordered = [...srcTabsSeen].sort(
          (x, y) => (y.rowKeyFields?.length ?? 0) - (x.rowKeyFields?.length ?? 0),
        );
        // source 镜像为最细 sources[0]
        const primaryTab = ordered[0];
        result.push({
          type: 'cross_tab_ref',
          source: primaryTab.componentId,
          sourceLabel: primaryTab.componentName ?? primaryTab.alias,
          target: '',
          agg: fnName,
          match: buildMatch(primaryTab.rowKeyFields ?? [], selfRowKeyFields),
          sources: ordered.map((td) => ({
            source: td.componentId,
            sourceLabel: td.componentName ?? td.alias,
            match: buildMatch(td.rowKeyFields ?? [], selfRowKeyFields),
          })),
          targetExpr,
        });
      }
      k = closeIdx + 1;
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

          // Resolve ref(名称/编号) → tabDef
          const tabDef = findTabByRef(tabDefs, alias);
          if (!tabDef) {
            throw new Error(
              `表达式中引用了未知页签 "${alias}"，请检查名称/编号是否与模板中页签配置一致`,
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
          //   component_code 始终存权威 alias(tabDef.alias)，与后端解析一致；不受用户输入名/编号影响。
          if (!isAgg && (tabDef.subtotalCols ?? []).includes(fieldPart)) {
            result.push({
              type: 'component_subtotal',
              value: fieldPart,
              tab_name: fieldPart,
              component_code: tabDef.alias,
              label: `${tabDef.componentName ?? tabDef.alias}·${fieldPart}`,
            });
          } else if (selfComponentId && tabDef.componentId === selfComponentId && !isAgg) {
            // 宿主自身明细字段 → 同行裸字段 token(不成环、读本行)。
            // 小计列已被上面的 if 截走;自聚合(isAgg)不在此归一,仍走下面 cross_tab_ref。
            result.push({ type: 'field', value: fieldPart });
          } else {
            result.push(
              makeCrossTabRef(alias, fieldPart, isAgg ? 'SUM' : 'NONE', tabDefs, selfRowKeyFields),
            );
          }
        } else {
          // Check for whole-tab total: [alias(总计)] (no dot but ends in (总计))
          if (body.endsWith('(总计)')) {
            const alias = body.slice(0, -'(总计)'.length);
            const tabDef = findTabByRef(tabDefs, alias);
            if (!tabDef) {
              throw new Error(
                `表达式中引用了未知页签 "${alias}"（总计引用），请检查名称/编号是否与模板中页签配置一致`,
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
              component_code: tabDef.alias,
              label: primarySubtotal
                ? `${tabDef.componentName ?? tabDef.alias}·${primarySubtotal}`
                : (tabDef.componentName ?? tabDef.alias),
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
  /** 宿主 componentId — 用于把 targetExpr 内 b_field 回显为 [宿主别名.列]（不传则回显裸 [列]） */
  selfComponentId?: string,
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
        // 优先用页签名称回显（按 component_code=alias 查 tabDef→componentName）；
        // tabDefs 空/查不到时回退 component_code，保证显示不破。
        const code = token.component_code ?? '';
        const label = tabDefs.find((d) => d.alias === code)?.componentName ?? code;
        const col = token.value ?? '';
        if (col) {
          parts.push(`[${label}.${col}]`);
        } else {
          parts.push(`[${label}(总计)]`);
        }
        break;
      }

      case 'cross_tab_ref': {
        // Resolve componentId → 页签名称(优先) / 编号(兜底)
        const tabDef = tabDefs.find((d) => d.componentId === token.source);
        const alias = tabDef?.componentName ?? tabDef?.alias ?? token.sourceLabel ?? token.source ?? '';

        if (token.targetExpr && token.targetExpr.length > 0) {
          // 行级聚合 SUMPRODUCT：FN(回显 targetExpr)，field→[source名.列]、b_field→[宿主名.列]
          const hostTab = selfComponentId
            ? tabDefs.find((d) => d.componentId === selfComponentId)
            : undefined;
          const hostAlias = hostTab?.componentName ?? hostTab?.alias ?? '';
          const inner = token.targetExpr
            .map((te) => {
              switch (te.type) {
                case 'field': return `[${alias}.${te.value ?? ''}]`;
                case 'b_field': return hostAlias ? `[${hostAlias}.${te.value ?? ''}]` : `[${te.value ?? ''}]`;
                case 'operator': return ` ${te.value ?? ''} `;
                case 'number': return te.value ?? '';
                case 'bracket_open': return '(';
                case 'bracket_close': return ')';
                default: return '';
              }
            })
            .join('')
            .replace(/\s{2,}/g, ' ')
            .trim();
          parts.push(`${token.agg ?? 'SUM'}(${inner})`);
        } else if (!token.target) {
          // Whole-tab total [alias(总计)] (empty target, 旧路保留)
          parts.push(`[${alias}(总计)]`);
        } else if (token.agg && token.agg !== 'NONE') {
          // 归一：FN([alias.field])，含 SUM（不再回显 (总计)）
          parts.push(`${token.agg}([${alias}.${token.target}])`);
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

/** 块展示文本：去外层括号已在调用处剥离；把所有 '.' 换 '·'（总计/裸字段不含点则原样） */
function blockDisplay(body: string): string {
  return body.replaceAll('.', '·');
}

/**
 * 把表达式串切成有序 FormulaSegment[]（块 + 文本交替），供 FormulaRichInput 渲染。
 * 宽容：未闭合 [ / { 降级为文本段（用户正在打字），绝不抛错。
 * 块 body 先 trim 再判色，与 lex() 内 body.trim() 对齐。
 */
export function parseFormulaSegments(
  expr: string,
  tabDefs: TabDef[],
  selfRowKeyFields: string[] | undefined,
  enforceMappable: boolean,
): FormulaSegment[] {
  const segs: FormulaSegment[] = [];
  let textBuf = '';
  const flush = () => {
    if (textBuf) {
      segs.push({ raw: textBuf, isBlock: false, display: textBuf, color: null });
      textBuf = '';
    }
  };

  // FN(...) 深度追踪：判断 [引用] 是否处于 FN 括号内
  const FN_NAMES = new Set(['SUM', 'AVG', 'MAX', 'MIN', 'COUNT']);
  let parenDepth = 0;
  const fnOpenDepths: number[] = [];
  let word = '';

  let i = 0;
  while (i < expr.length) {
    const ch = expr[i];
    if (ch === '[') {
      const end = expr.indexOf(']', i);
      if (end === -1) { textBuf += expr.slice(i); break; }
      flush();
      const raw = expr.slice(i, end + 1);
      const body = expr.slice(i + 1, end).trim();
      const insideFn = fnOpenDepths.length > 0;
      const { color } = classifyRefSegment(body, tabDefs, selfRowKeyFields, enforceMappable, insideFn);
      segs.push({ raw, isBlock: true, display: blockDisplay(body), color });
      word = ''; // 方括号内容不贡献函数名
      i = end + 1;
      continue;
    }
    if (ch === '{') {
      const end = expr.indexOf('}', i);
      if (end === -1) { textBuf += expr.slice(i); break; }
      flush();
      const raw = expr.slice(i, end + 1);
      const body = expr.slice(i + 1, end).trim();
      segs.push({ raw, isBlock: true, display: body, color: null });
      word = ''; // 花括号内容不贡献函数名
      i = end + 1;
      continue;
    }
    // 追踪括号深度和函数名，以便判断 [引用] 是否在 FN(...) 内
    if (/[A-Za-z]/.test(ch)) {
      word += ch;
    } else {
      if (ch === '(') {
        parenDepth++;
        if (FN_NAMES.has(word.toUpperCase())) fnOpenDepths.push(parenDepth);
      } else if (ch === ')') {
        if (fnOpenDepths.length && fnOpenDepths[fnOpenDepths.length - 1] === parenDepth) {
          fnOpenDepths.pop();
        }
        parenDepth--;
      }
      word = '';
    }
    textBuf += ch;
    i++;
  }
  flush();
  return segs;
}

/** A ⊆ B（视为集合，顺序无关） */
export function isSubset(sub: string[], sup: string[]): boolean {
  const s = new Set(sup);
  return sub.every((x) => s.has(x));
}
/** 行键可比 = 任一方 ⊆ 另一方 */
export function comparable(a: string[], b: string[]): boolean {
  return isSubset(a, b) || isSubset(b, a);
}

/**
 * Gate 镜像后端 TokenMappabilityValidator（v4-C 收敛）：
 * 拒绝任何 cross_tab_ref 且 match 为空（含 agg=NONE）——空 match → 全源行命中
 * → 聚合退化全表 / NONE 静默广播或吞 0。component_subtotal 无 match，不受影响。
 * 旧"≥2 个 agg=NONE 即拒"已作废。
 */
export function checkMappable(tokens: FormulaToken[]): { mappable: boolean; reason?: string } {
  const emptyMatch = tokens.some(
    (t) => t.type === 'cross_tab_ref' && (!t.match || t.match.length === 0),
  );
  if (emptyMatch) {
    return { mappable: false,
      reason: '存在与宿主无公共行键的跨页签引用（match 为空），不可对齐。请改引可比页签或用其整页签小计 [页签(总计)]。' };
  }
  return { mappable: true };
}

// ─────────────────────────────────────────────
// 配色分类器(显示侧,与保存期 checkMappable 同源)
// ─────────────────────────────────────────────

export type SegmentColor = 'blue' | 'yellow' | 'green' | 'red' | 'purple' | null;

export interface FormulaSegment {
  /** 原始片段文本(块含括号,文本原样) */
  raw: string;
  /** true=原子块([...]/{...});false=普通文本 */
  isBlock: boolean;
  /** 块展示文本(去括号、'.'→'·');文本段等于 raw */
  display: string;
  /** 块配色;文本段 null */
  color: SegmentColor;
}

/**
 * 单个 [...] body 判色(body 已去外层方括号且已 trim)。
 * 行序即优先级(spec §3.4 / §5):总计无点(绿) → 小计列(黄) → 宿主自身字段(紫,self-agg 红) → 明细(蓝) → 查不到(红) → 无点裸字段(紫)。
 * enforceMappable: NORMAL/SUBTOTAL=true(明细 match 空判红);EXCEL=false(解析得到即蓝)。
 */
export function classifyRefSegment(
  body: string,
  tabDefs: TabDef[],
  selfRowKeyFields: string[] | undefined,
  enforceMappable: boolean,
  insideFn: boolean = false,
): { kind: string; color: SegmentColor } {
  // 1) 无点 + (总计) 结尾 → 整页签小计(component_subtotal,无 match 约束)
  if (!body.includes('.') && body.endsWith('(总计)')) {
    const alias = body.slice(0, -'(总计)'.length);
    return findTabByRef(tabDefs, alias)
      ? { kind: 'tab-total', color: 'green' }
      : { kind: 'invalid', color: 'red' };
  }

  // 含点 → 跨页签引用
  if (body.includes('.')) {
    const dotIdx = body.indexOf('.');
    const alias = body.slice(0, dotIdx);
    let field = body.slice(dotIdx + 1);
    const isAgg = field.endsWith('(总计)');
    if (isAgg) field = field.slice(0, -'(总计)'.length);

    const tab = findTabByRef(tabDefs, alias);
    if (!tab) return { kind: 'invalid', color: 'red' };

    // 2) 非聚合 + 字段∈subtotalCols → 小计列(component_subtotal,无 match 约束)
    if (!isAgg && (tab.subtotalCols ?? []).includes(field)) {
      return { kind: 'subtotal', color: 'yellow' };
    }

    // 宿主自身字段(spec §5):tabDef.self → 紫;自聚合(isAgg)本期不支持 → 红
    if (tab.self) {
      if (isAgg) return { kind: 'invalid', color: 'red' };
      if (!(tab.detailFields ?? []).includes(field)) return { kind: 'invalid', color: 'red' };
      return { kind: 'self-field', color: 'purple' };
    }

    // 字段必须是该 tab 的真实列(明细或小计),否则查不到 → 红
    const known = new Set([...(tab.detailFields ?? []), ...(tab.subtotalCols ?? [])]);
    if (!known.has(field)) return { kind: 'invalid', color: 'red' };

    // 3/4) 明细 cross_tab_ref:enforceMappable 下镜像 buildMatch 是否空判红
    if (enforceMappable) {
      const matchEmpty = buildMatch(tab.rowKeyFields ?? [], selfRowKeyFields).length === 0;
      if (matchEmpty) return { kind: 'invalid', color: 'red' };
    }

    // 5) 细 source 裸引用（既非 FN 包裹、也非 (总计) inline 聚合）→ 红：需聚合，否则求值命中多行归 0
    if (enforceMappable && !insideFn && !isAgg) {
      const self = selfRowKeyFields ?? [];
      const src = tab.rowKeyFields ?? [];
      const srcStrictlyFiner = isSubset(self, src) && !isSubset(src, self);
      if (srcStrictlyFiner) return { kind: 'needs-agg', color: 'red' };
    }

    return { kind: 'detail', color: 'blue' };
  }

  // 无点无总计 → 宿主自身列(裸字段)→ 紫
  return { kind: 'self-field', color: 'purple' };
}
