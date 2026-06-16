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
import { parsePredicateText, serializePredicate } from '../../utils/predicateText';

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
  | { kind: 'sumif_call'; funcName: string; body: string } // SUMIF/COUNTIF/AVGIF/MINIF/MAXIF(...) 整体

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

    // Function names (case-insensitive)
    if (/[A-Za-z]/.test(ch)) {
      let word = '';
      while (i < expr.length && /[A-Za-z]/.test(expr[i])) word += expr[i++];
      const upper = word.toUpperCase();
      const OUTER_FNS = ['SUM', 'AVG', 'MAX', 'MIN', 'COUNT'];
      const INNER_FNS = ['KSUM', 'KAVG', 'KMAX', 'KMIN', 'KCOUNT'];
      // SUMIF 族：把整个 FUNC(...) 括号内容原样抽出为 sumif_call token。
      // 括号内可包含单引号字符串、比较运算符等，lex 无法逐字符解析，故整体保留为原始文本。
      const SUMIF_FNS = ['SUMIF', 'COUNTIF', 'AVGIF', 'MINIF', 'MAXIF'];
      if (SUMIF_FNS.includes(upper)) {
        // 跳过空白，期待紧跟 '('
        let j = i;
        while (j < expr.length && /\s/.test(expr[j])) j++;
        if (j >= expr.length || expr[j] !== '(') {
          throw new Error(`函数 ${upper} 后必须紧跟 '('`);
        }
        // 扫描匹配括号（处理嵌套括号，但括号内可有单引号字符串，引号内括号不计深度）
        let depth = 0;
        let inStr = false;
        let strChar = '';
        let bodyStart = j + 1;
        let closeIdx = -1;
        for (let p = j; p < expr.length; p++) {
          const c = expr[p];
          if (inStr) {
            if (c === strChar) inStr = false;
          } else {
            if (c === "'" || c === '"') { inStr = true; strChar = c; }
            else if (c === '(') depth++;
            else if (c === ')') {
              depth--;
              if (depth === 0) { closeIdx = p; break; }
            }
          }
        }
        if (closeIdx === -1) throw new Error(`函数 ${upper} 的 '(' 缺少对应的 ')'`);
        const body = expr.slice(bodyStart, closeIdx);
        tokens.push({ kind: 'sumif_call', funcName: upper, body });
        i = closeIdx + 1;
        continue;
      }
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
 * 按引用串定位页签：**稳定键（alias → componentId）优先、名称(componentName)兜底**。
 *
 * 优先级：
 *   1. alias 精确匹配（COMP-0029 等编号格式，编辑器插入的稳定标识）
 *   2. componentId 精确匹配（直接用 componentId 串作引用，极少但须支持）
 *   3. componentName 兜底（旧公式/名称唯一时可读性好；同名时命中第一个仍有歧义，但属于配置问题）
 *
 * 修复原因（AP fix 2026-06-15）：
 *   旧顺序 componentName → alias 时，若某页签的 componentName 恰好等于另一页签的 alias 字符串，
 *   则 alias 格式的引用串会被 componentName 优先截走，命中错误页签（source 串号）。
 *   改为 alias 优先后，编辑器产出的稳定 alias 格式始终精确命中正确页签。
 */
function findTabByRef(tabDefs: TabDef[], ref: string): TabDef | undefined {
  return (
    tabDefs.find((d) => d.alias === ref)
    ?? tabDefs.find((d) => d.componentId === ref)
    ?? tabDefs.find((d) => d.componentName === ref)
  );
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
// parseValueExpr: SUMIF 第二参数（值表达式）解析
// ─────────────────────────────────────────────

/**
 * 解析 SUMIF 的值表达式（第二个参数，形如 `[别名.字段]` 或 `[别名.字段] * [别名.字段]`）。
 * 只允许 [别名.字段]、运算符、数字、括号；不允许 SUMIF 族嵌套。
 * 结果产出 FormulaToken[]（field/operator/number/bracket_open/bracket_close），
 * 供 tokensToDrawerExpression 的 renderTargetExprParts 还原。
 */
function parseValueExpr(
  text: string,
  tabDefs: TabDef[],
  selfRowKeyFields?: string[],
  selfComponentId?: string,
): FormulaToken[] {
  // valueExpr 不含单引号字符串，可直接用 lex（但 lex 不支持 SUMIF 族，不会有问题）
  const rawToks = lex(text);
  const result: FormulaToken[] = [];
  for (const rt of rawToks) {
    switch (rt.kind) {
      case 'bracket_expr': {
        const bb = rt.body.trim();
        if (!bb.includes('.')) {
          // 裸字段 → b_field（宿主本行列）
          result.push({ type: 'b_field', value: bb });
        } else {
          const di = bb.indexOf('.');
          const al = bb.slice(0, di);
          const col = bb.slice(di + 1);
          const td = findTabByRef(tabDefs, al);
          if (!td) throw new Error(`SUMIF 值表达式中引用了未知页签 "${al}"`);
          if (!td.componentId) throw new Error(`页签 "${al}" 缺少 componentId`);
          if (selfComponentId && td.componentId === selfComponentId) {
            result.push({ type: 'b_field', value: col });
          } else {
            result.push({ type: 'field', value: col, source: td.componentId });
          }
        }
        break;
      }
      case 'operator':
        result.push({ type: 'operator', value: normalizeOp(rt.value) });
        break;
      case 'number':
        result.push({ type: 'number', value: rt.value });
        break;
      case 'paren_open':
        result.push({ type: 'bracket_open' });
        break;
      case 'paren_close':
        result.push({ type: 'bracket_close' });
        break;
      default:
        throw new Error(`SUMIF 值表达式中不支持 ${rt.kind} 类型`);
    }
  }
  return result;
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

  // K* 函数名集合（不允许出现在顶层）
  const INNER_FNS_SET = new Set(['KSUM', 'KAVG', 'KMAX', 'KMIN', 'KCOUNT']);
  // K* → 对应外层聚合名映射
  const INNER_TO_AGG: Record<string, string> = {
    KSUM: 'SUM', KAVG: 'AVG', KMAX: 'MAX', KMIN: 'MIN', KCOUNT: 'COUNT',
  };

  let k = 0;
  while (k < rawTokens.length) {
    const raw = rawTokens[k];

    // ── M: 顶层裸 K* 拒绝 ──
    if (raw.kind === 'func' && INNER_FNS_SET.has(raw.name)) {
      throw new Error(
        `${raw.name} 只能写在外层 SUM/AVG/MAX/MIN/COUNT 函数内，不支持顶层直接使用`,
      );
    }

    // ── SUMIF 族解析 ──
    // lex 已把 SUMIF(body) 整体抽出；此处解析 body → predicate + 可选 targetExpr
    if (raw.kind === 'sumif_call') {
      const FUNC_TO_AGG: Record<string, string> = {
        SUMIF: 'SUM', COUNTIF: 'COUNT', AVGIF: 'AVG', MINIF: 'MIN', MAXIF: 'MAX',
      };
      const agg = FUNC_TO_AGG[raw.funcName] ?? 'SUM';

      // 用顶层逗号切 cond 与 valueExpr（跳过单引号字符串内、括号内的逗号）
      const splitTopLevelComma = (text: string): [string, string | undefined] => {
        let depth = 0;
        let inStr = false;
        let strChar = '';
        for (let pi = 0; pi < text.length; pi++) {
          const c = text[pi];
          if (inStr) {
            if (c === strChar) inStr = false;
          } else if (c === "'" || c === '"') {
            inStr = true; strChar = c;
          } else if (c === '(') {
            depth++;
          } else if (c === ')') {
            depth--;
          } else if (c === ',' && depth === 0) {
            return [text.slice(0, pi).trim(), text.slice(pi + 1).trim()];
          }
        }
        return [text.trim(), undefined];
      };

      const [condText, valueText] = splitTopLevelComma(raw.body);

      // 解析 cond → ConditionPredicate
      const predicate = parsePredicateText(condText);

      // 从 condText 中提取首个 [别名.字段] 的别名（= SUMIF source 页签 alias）
      const firstBracketMatch = condText.match(/\[([^\].]+)\./);
      const sourceAlias = firstBracketMatch ? firstBracketMatch[1] : '';
      const srcTabDef = findTabByRef(tabDefs, sourceAlias);
      if (!srcTabDef) {
        throw new Error(`SUMIF 条件中引用了未知页签 "${sourceAlias}"`);
      }
      if (!srcTabDef.componentId) {
        throw new Error(`页签 "${sourceAlias}" 缺少 componentId`);
      }

      // 解析可选 valueExpr（用现有行级 targetExpr 解析逻辑）
      let targetExpr: FormulaToken[] | undefined;
      if (valueText && valueText.length > 0 && raw.funcName !== 'COUNTIF') {
        // valueExpr 是合法的表达式串（仅含 [别名.字段] 和运算符/数字），可用 expressionToTokens 递归解析
        // 但为避免无限递归且 valueExpr 语义简单，直接解析 [别名.字段] 片段
        targetExpr = parseValueExpr(valueText, tabDefs, selfRowKeyFields, selfComponentId);
      }

      result.push({
        type: 'cross_tab_ref',
        source: srcTabDef.componentId,
        sourceLabel: srcTabDef.componentName ?? sourceAlias,
        target: '',
        agg: agg as FormulaToken['agg'],
        match: [],  // SUMIF 族：match 为空，按 predicate 过滤而非行键 JOIN
        predicate,
        ...(targetExpr && targetExpr.length > 0 ? { targetExpr } : {}),
      });
      k++;
      continue;
    }

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
      // KSUM 折叠：记录 KSUM 内被聚合的 source componentId，用于 I2 冲突校验
      const ksumWrappedSources = new Set<string>();
      let lastKind: string | null = null;
      let bodyIdx = 0;
      while (bodyIdx < bodyTokens.length) {
        const rt = bodyTokens[bodyIdx];
        // 相邻两"值项"之间必须有运算符（防 `[a][b]` 这类畸形 → 求值静默返 0）
        const startsValue =
          rt.kind === 'bracket_expr' || rt.kind === 'number' || rt.kind === 'paren_open'
          || (rt.kind === 'func' && INNER_FNS_SET.has(rt.name));
        const lastEndsValue =
          lastKind === 'bracket_expr' || lastKind === 'number' || lastKind === 'paren_close'
          || lastKind === 'ksum_fold'; // KSUM 折叠后虚拟 kind
        if (startsValue && lastEndsValue) {
          throw new Error(`${fnName}() 内表达式缺少运算符（相邻两项之间需 + - * /）`);
        }

        // ── KSUM 折叠（C2）：先于单列 shortcut 捕获 inner K* func ──
        if (rt.kind === 'func' && INNER_FNS_SET.has(rt.name)) {
          const innerFnName = rt.name;
          const innerAgg = INNER_TO_AGG[innerFnName];
          const next = bodyTokens[bodyIdx + 1];
          if (!next || next.kind !== 'paren_open') {
            throw new Error(`函数 ${innerFnName} 后必须紧跟 '('`);
          }
          // 找 inner K* 的匹配闭括号
          let innerDepth = 0;
          let innerCloseIdx = -1;
          for (let jj = bodyIdx + 1; jj < bodyTokens.length; jj++) {
            const rjj = bodyTokens[jj];
            if (rjj.kind === 'paren_open') innerDepth++;
            else if (rjj.kind === 'paren_close') {
              innerDepth--;
              if (innerDepth === 0) { innerCloseIdx = jj; break; }
            }
          }
          if (innerCloseIdx === -1) throw new Error(`${innerFnName}() 的 '(' 缺少对应的 ')'`);
          const innerBodyTokens = bodyTokens.slice(bodyIdx + 2, innerCloseIdx);

          // J: inner body 仍含 K* → K 套 K 拒绝
          const hasNestedK = innerBodyTokens.some(
            rr => rr.kind === 'func' && INNER_FNS_SET.has(rr.name),
          );
          if (hasNestedK) {
            throw new Error(`${innerFnName}() 内不能再嵌套 K 套 K（KSUM/KAVG/...）`);
          }

          // 从 innerBodyTokens 收集被聚合 source（只允许同一页签）
          const innerSrcSeen: TabDef[] = [];
          const innerSrcSeenIds = new Set<string>();
          const innerTargetExpr: FormulaToken[] = [];
          for (const irr of innerBodyTokens) {
            if (irr.kind === 'bracket_expr') {
              const bb = irr.body.trim();
              if (!bb.includes('.')) {
                // 裸字段（无点）= 宿主列 → KSUM 内不允许
                throw new Error(
                  `${innerFnName}() 内不能引用宿主自身裸字段 [${bb}]，宿主列请放到外层 SUM`,
                );
              }
              const di = bb.indexOf('.');
              const al = bb.slice(0, di);
              let col = bb.slice(di + 1);
              // 修复 I-3：检测 (总计) 后缀，静默产出怪字段名违反"忌静默失败"纪律
              if (col.endsWith('(总计)')) {
                throw new Error(
                  `${innerFnName}() 内不支持 (总计) 总计引用 [${al}.${col}]，请引用明细字段或把总计放到外层`,
                );
              }
              const td = findTabByRef(tabDefs, al);
              if (!td) throw new Error(`${innerFnName}() 内引用了未知页签 "${al}"`);
              if (!td.componentId) throw new Error(`页签 "${al}" 缺少 componentId`);
              // 白名单：不允许宿主自身列
              if (selfComponentId && td.componentId === selfComponentId) {
                throw new Error(
                  `${innerFnName}() 内不能引用宿主自身列 [${al}.${col}]，宿主列请放到外层 SUM`,
                );
              }
              // 记录被聚合 source
              if (!innerSrcSeenIds.has(td.componentId)) {
                innerSrcSeenIds.add(td.componentId);
                innerSrcSeen.push(td);
              }
              // C2 关键：field token 携带 source（被聚合页签 componentId），强制 targetExpr（不走单列 shortcut）
              innerTargetExpr.push({ type: 'field', value: col, source: td.componentId });
            } else if (irr.kind === 'operator') {
              innerTargetExpr.push({ type: 'operator', value: normalizeOp(irr.value) });
            } else if (irr.kind === 'number') {
              innerTargetExpr.push({ type: 'number', value: irr.value });
            } else if (irr.kind === 'paren_open') {
              innerTargetExpr.push({ type: 'bracket_open' });
            } else if (irr.kind === 'paren_close') {
              innerTargetExpr.push({ type: 'bracket_close' });
            } else if (irr.kind === 'brace_expr') {
              // 修复 spec §2.4：global_variable {路径} 是与行无关的常量，逐行广播安全 → 放行为 path token
              innerTargetExpr.push({ type: 'path', path: irr.body });
            } else {
              // 不支持 func 等在 KSUM 内（J 已处理 K 套 K，其他聚合也禁止）
              throw new Error(`${innerFnName}() 内不支持 ${irr.kind} 类型的 token`);
            }
          }
          // 白名单：KSUM 内必须恰好引用同一个页签（不能跨页签）
          if (innerSrcSeen.length === 0) {
            throw new Error(`${innerFnName}() 内必须引用至少一个页签明细列 [页签别名.字段]`);
          }
          if (innerSrcSeen.length > 1) {
            throw new Error(
              `${innerFnName}() 内只能引用同一个页签的列，跨页签引用请分别放到外层 SUM 内`,
            );
          }
          const kSrc = innerSrcSeen[0];
          // 记录 KSUM 折叠的 source 供 I2 校验
          ksumWrappedSources.add(kSrc.componentId!);
          // 构建 match
          const kMatch = buildMatch(kSrc.rowKeyFields ?? [], selfRowKeyFields);
          // 推入 KSUM 子 token（projectToHostKey=true，target='', targetExpr 强制写入）
          targetExpr.push({
            type: 'cross_tab_ref',
            projectToHostKey: true,
            source: kSrc.componentId,
            sourceLabel: kSrc.componentName ?? kSrc.alias,
            target: '',
            agg: innerAgg,
            match: kMatch,
            targetExpr: innerTargetExpr,
          });
          lastKind = 'ksum_fold';
          bodyIdx = innerCloseIdx + 1;
          continue;
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
            // 普通聚合函数（非 K*）嵌套仍不支持
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
              // field token 带各自 source componentId，供回显时精确找到页签名
              // （修复 D1: 多 source 时 [来料加工费.费用] 不再错显为 [元素.费用]）
              targetExpr.push({ type: 'field', value: col, source: td.componentId });
            }
            break;
          }
          default:
            break;
        }
        bodyIdx++;
      }
      // I2: 同页签既被 KSUM 聚合、又在外层 FN body 裸引用 → 冲突（二选一）
      for (const td of srcTabsSeen) {
        if (ksumWrappedSources.has(td.componentId!)) {
          throw new Error(
            `页签「${td.componentName ?? td.alias}」已被 KSUM 聚合，不能在同一 SUM 内再被裸引用；请二选一`,
          );
        }
      }
      // 外层 body 既无 cross-tab source 也无 KSUM 折叠 → 什么都没引用
      if (srcTabsSeen.length === 0 && ksumWrappedSources.size === 0) {
        throw new Error(
          `${fnName}() 行级聚合必须引用至少一个细页签明细列 [页签别名.字段]`,
        );
      }

      // ── 多 source 校验 + token 组装 ──
      if (srcTabsSeen.length === 0 && ksumWrappedSources.size > 0) {
        // 纯 KSUM 容器: match=[] 表示外层无需直接 join, 求值器应下钻 targetExpr 内 projectToHostKey 子 token
        // (T6 后端镜像此契约)
        result.push({
          type: 'cross_tab_ref',
          source: selfComponentId ?? '',
          sourceLabel: '',
          target: '',
          agg: fnName,
          match: [],
          targetExpr,
        });
      } else if (srcTabsSeen.length === 1) {
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
        // 按 rowKeyFields 长度排序：最细（字段最多）→ 更粗；等长时按 componentId 字典序作 tie-breaker 保证快照确定性
        const ordered = [...srcTabsSeen].sort(
          (x, y) =>
            (y.rowKeyFields?.length ?? 0) - (x.rowKeyFields?.length ?? 0) ||
            x.componentId!.localeCompare(y.componentId!),
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
            source: td.componentId!,
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

          // Disambiguate subtotal column vs detail field vs same-component row-aligned field:
          //
          // Priority order (highest → lowest):
          //   1. 同组件列引用(无总计) → field(同行值)，即使该列是小计列。
          //      引擎拓扑序先算被引用公式列，再算本列，行内相加无循环依赖。
          //   2. 跨组件小计列引用(无总计) → component_subtotal(整列总计标量)。
          //   3. 其余（显式总计/跨组件明细） → cross_tab_ref。
          //
          // 注意：isAgg(显式 "(总计)") 不进入 1/2，直接走 3 的 cross_tab_ref SUM，
          //       component_code 始终存权威 alias(tabDef.alias)，与后端解析一致。
          if (selfComponentId && tabDef.componentId === selfComponentId && !isAgg) {
            // 同组件列引用(无总计) → 同行值(field token)，引擎拓扑序保证被引用列先算。
            // 即使该列是小计列也取同行值；只有显式 "(总计)" 才取整列总计。
            result.push({ type: 'field', value: fieldPart });
          } else if (!isAgg && (tabDef.subtotalCols ?? []).includes(fieldPart)) {
            // 跨组件小计列引用(无总计) → component_subtotal(整列总计标量)。
            result.push({
              type: 'component_subtotal',
              value: fieldPart,
              tab_name: fieldPart,
              component_code: tabDef.alias,
              label: `${tabDef.componentName ?? tabDef.alias}·${fieldPart}`,
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

        // ── SUMIF 族序列化（predicate 存在 → 走 SUMIF/COUNTIF/AVGIF/MINIF/MAXIF 路径）──
        if (token.predicate) {
          const AGG_TO_IFUNC: Record<string, string> = {
            SUM: 'SUMIF', COUNT: 'COUNTIF', AVG: 'AVGIF', MIN: 'MINIF', MAX: 'MAXIF',
          };
          const ifuncName = AGG_TO_IFUNC[(token.agg ?? 'SUM').toUpperCase()] ?? 'SUMIF';
          // sourceAlias = 该 cross_tab_ref 的 source 页签名（用于 serializePredicate）
          const sourceAlias = alias;
          // hostAlias = tabDefs 里 self===true 的别名
          const hostTabDef = tabDefs.find((d) => d.self === true);
          const hostAlias = hostTabDef?.componentName ?? hostTabDef?.alias ?? '';

          const condStr = serializePredicate(token.predicate, { sourceAlias, hostAlias });

          if (token.targetExpr && token.targetExpr.length > 0) {
            // 行内值表达式回显：field→[source名.列]、b_field→[宿主名.列]
            const valueStr = token.targetExpr
              .map((te) => {
                switch (te.type) {
                  case 'field': {
                    const ftd = te.source ? tabDefs.find((d) => d.componentId === te.source) : undefined;
                    const fAlias = ftd?.componentName ?? ftd?.alias ?? sourceAlias;
                    return `[${fAlias}.${te.value ?? ''}]`;
                  }
                  case 'b_field':
                    return hostAlias ? `[${hostAlias}.${te.value ?? ''}]` : `[${te.value ?? ''}]`;
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
            parts.push(`${ifuncName}(${condStr}, ${valueStr})`);
          } else {
            // COUNTIF：无值表达式
            parts.push(`${ifuncName}(${condStr})`);
          }
          break;
        }

        if (token.targetExpr && token.targetExpr.length > 0) {
          // 行级聚合 SUMPRODUCT：FN(回显 targetExpr)，field→[source名.列]、b_field→[宿主名.列]
          const hostTab = selfComponentId
            ? tabDefs.find((d) => d.componentId === selfComponentId)
            : undefined;
          const hostAlias = hostTab?.componentName ?? hostTab?.alias ?? '';

          // AGG 名 → K* 函数名（KSUM 递归回显用）
          const AGG_TO_KFUNC: Record<string, string> = {
            SUM: 'KSUM', AVG: 'KAVG', MAX: 'KMAX', MIN: 'KMIN', COUNT: 'KCOUNT',
          };

          /**
           * 递归回显一个 targetExpr 的子 token 序列。
           * @param teList  子 token 列表
           * @param outerAlias  外层 cross_tab_ref 的页签名（供无 source 的 field token 使用）
           */
          const renderTargetExprParts = (teList: FormulaToken[], outerAlias: string): string => {
            return teList
              .map((te) => {
                switch (te.type) {
                  case 'field': {
                    // field token 可能携带 source（KSUM 内层赋值的 componentId）
                    const fieldTabDef = te.source
                      ? tabDefs.find((d) => d.componentId === te.source)
                      : undefined;
                    const fieldAlias = fieldTabDef?.componentName ?? fieldTabDef?.alias ?? outerAlias;
                    return `[${fieldAlias}.${te.value ?? ''}]`;
                  }
                  case 'b_field': return hostAlias ? `[${hostAlias}.${te.value ?? ''}]` : `[${te.value ?? ''}]`;
                  case 'operator': return ` ${te.value ?? ''} `;
                  case 'number': return te.value ?? '';
                  case 'bracket_open': return '(';
                  case 'bracket_close': return ')';
                  case 'path': return `{${te.path ?? ''}}`;
                  case 'cross_tab_ref': {
                    // projectToHostKey=true → KSUM 子 token，回显为 K<AGG>(...)
                    if (te.projectToHostKey) {
                      const kTabDef = tabDefs.find((d) => d.componentId === te.source);
                      const kAlias = kTabDef?.componentName ?? kTabDef?.alias ?? te.sourceLabel ?? te.source ?? '';
                      const kFuncName = AGG_TO_KFUNC[(te.agg ?? 'SUM').toUpperCase()] ?? 'KSUM';
                      const kInner = te.targetExpr && te.targetExpr.length > 0
                        ? renderTargetExprParts(te.targetExpr, kAlias)
                        : '';
                      return `${kFuncName}(${kInner})`;
                    }
                    return '';
                  }
                  default: return '';
                }
              })
              .join('')
              .replace(/\s{2,}/g, ' ')
              .trim();
          };

          const inner = renderTargetExprParts(token.targetExpr, alias);
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
  // K* 系列也纳入 FN_NAMES，使 insideFn=true → 跳过 needs-agg（KSUM 内细 source 合法聚合）
  const FN_NAMES = new Set(['SUM', 'AVG', 'MAX', 'MIN', 'COUNT', 'KSUM', 'KAVG', 'KMAX', 'KMIN', 'KCOUNT']);
  // KFUNC_NAMES：K* 函数子集，用于独立追踪 insideKsum 上下文
  const KFUNC_NAMES = new Set(['KSUM', 'KAVG', 'KMAX', 'KMIN', 'KCOUNT']);
  let parenDepth = 0;
  const fnOpenDepths: number[] = [];
  const ksumOpenDepths: number[] = []; // K* 开启的括号深度栈
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
      const insideKsum = ksumOpenDepths.length > 0;
      const { color } = classifyRefSegment(body, tabDefs, selfRowKeyFields, enforceMappable, insideFn, insideKsum);
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
    // 追踪括号深度和函数名，以便判断 [引用] 是否在 FN(...) / KSUM(...) 内
    if (/[A-Za-z]/.test(ch)) {
      word += ch;
    } else {
      if (ch === '(') {
        parenDepth++;
        const upperWord = word.toUpperCase();
        if (FN_NAMES.has(upperWord)) fnOpenDepths.push(parenDepth);
        if (KFUNC_NAMES.has(upperWord)) ksumOpenDepths.push(parenDepth);
      } else if (ch === ')') {
        if (fnOpenDepths.length && fnOpenDepths[fnOpenDepths.length - 1] === parenDepth) {
          fnOpenDepths.pop();
        }
        if (ksumOpenDepths.length && ksumOpenDepths[ksumOpenDepths.length - 1] === parenDepth) {
          ksumOpenDepths.pop();
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
 * insideKsum: 当处于 K*(…) 括号区间内时为 true —— 宿主自身字段(tab.self)在此区间违规 → 红。
 */
export function classifyRefSegment(
  body: string,
  tabDefs: TabDef[],
  selfRowKeyFields: string[] | undefined,
  enforceMappable: boolean,
  insideFn: boolean = false,
  insideKsum: boolean = false,
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
    // 特例: insideKsum 内宿主自身字段 → 违规 → red（KSUM 内不能引用宿主列）
    if (tab.self) {
      if (isAgg) return { kind: 'invalid', color: 'red' };
      if (!(tab.detailFields ?? []).includes(field)) return { kind: 'invalid', color: 'red' };
      if (insideKsum) return { kind: 'insideKsum-illegal', color: 'red' };
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
  // insideKsum 内裸字段引用宿主列也是违规 → red
  if (insideKsum) return { kind: 'insideKsum-illegal', color: 'red' };
  return { kind: 'self-field', color: 'purple' };
}
