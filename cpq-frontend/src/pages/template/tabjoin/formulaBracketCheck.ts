/**
 * formulaBracketCheck.ts
 *
 * 页签连表公式表达式的圆括号 () 数量+顺序校验（纯函数，无 React / api 依赖）。
 *
 * 关键约束：`(总计)` 后缀用 ASCII 圆括号，且总出现在 [...] 字段块内部
 * （如 [COMP_RL.金额(总计)]）。这些不是分组括号，必须排除。
 * 因此扫描时遇 '[' 跳到配对 ']'、遇 '{' 跳到配对 '}'，整体跳过块内字符，
 * 只对真正的分组圆括号做深度计数。
 *
 * 假设 [...] / {...} 内不含同类括号（当前公式文法保证 —— 字段块/路径块不嵌套），
 * 故用 indexOf 取首个闭合符即块边界。若未闭合（indexOf=-1）则跳到串尾。
 * 本函数只负责圆括号；[] / {} 缺配对仍由 formulaSerialize.lex() 在保存时报。
 */

export interface ParenCheckResult {
  ok: boolean;
  error?: string;
}

export interface ParenInfo {
  /** 该括号字符在原始表达式串中的下标 */
  index: number;
  /** '(' 或 ')' */
  ch: '(' | ')';
  /** 嵌套深度，最外层 = 0；着色用 depth % 4 取色 */
  depth: number;
  /** 配对括号的 index；未配对时为 null */
  matchIndex: number | null;
  /** true = 未闭合的 '(' 或无匹配的 ')' */
  error: boolean;
}

/**
 * 扫描表达式中的**分组圆括号**（跳过 [...] 与 {...} 块内的圆括号）。
 * 返回按 index 升序的信息数组。纯函数，无副作用。
 *
 * 跳过规则与本文件历史上的 `checkParenBalance` 完全一致：
 * 遇 '[' → indexOf(']')，遇 '{' → indexOf('}')，未闭合则跳到串尾。
 */
export function scanParens(expr: string): ParenInfo[] {
  const result: ParenInfo[] = [];
  // 栈中存放未闭合 '(' 在 result 数组中的下标
  const stack: number[] = [];
  let i = 0;
  while (i < expr.length) {
    const ch = expr[i];
    // 跳过 [...] 块（含块内 (总计)）；未闭合则跳到串尾
    if (ch === '[') {
      const end = expr.indexOf(']', i);
      i = end === -1 ? expr.length : end + 1;
      continue;
    }
    // 跳过 {...} 路径块
    if (ch === '{') {
      const end = expr.indexOf('}', i);
      i = end === -1 ? expr.length : end + 1;
      continue;
    }
    if (ch === '(') {
      const depth = stack.length;
      result.push({ index: i, ch: '(', depth, matchIndex: null, error: false });
      stack.push(result.length - 1);
    } else if (ch === ')') {
      const openResultIdx = stack.pop();
      if (openResultIdx === undefined) {
        // 栈空：无匹配的右括号
        result.push({ index: i, ch: ')', depth: 0, matchIndex: null, error: true });
      } else {
        const openInfo = result[openResultIdx];
        openInfo.matchIndex = i;
        result.push({ index: i, ch: ')', depth: openInfo.depth, matchIndex: openInfo.index, error: false });
      }
    }
    i += 1;
  }
  // 扫完后栈中残留的 '(' 均未闭合
  for (const idx of stack) {
    result[idx].error = true;
  }
  return result;
}

export function checkParenBalance(expr: string): ParenCheckResult {
  const parens = scanParens(expr);
  // 与原实现同序：左到右扫描时第一个"无匹配右括号"即报错（忽略其后任何未闭合左括号）
  const firstUnmatchedClose = parens.find((p) => p.ch === ')' && p.error);
  if (firstUnmatchedClose) {
    return { ok: false, error: '括号不匹配：多了 1 个右括号 ")"（出现无匹配的右括号）' };
  }
  const unmatchedOpenCount = parens.filter((p) => p.ch === '(' && p.error).length;
  if (unmatchedOpenCount > 0) {
    return { ok: false, error: `括号不匹配：缺少 ${unmatchedOpenCount} 个右括号 ")"` };
  }
  return { ok: true };
}
