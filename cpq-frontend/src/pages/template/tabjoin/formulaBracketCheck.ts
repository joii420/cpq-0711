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

export function checkParenBalance(expr: string): ParenCheckResult {
  let depth = 0;
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
      depth += 1;
    } else if (ch === ')') {
      depth -= 1;
      if (depth < 0) {
        return { ok: false, error: '括号不匹配：多了 1 个右括号 ")"（出现无匹配的右括号）' };
      }
    }
    i += 1;
  }
  if (depth > 0) {
    return { ok: false, error: `括号不匹配：缺少 ${depth} 个右括号 ")"` };
  }
  return { ok: true };
}
