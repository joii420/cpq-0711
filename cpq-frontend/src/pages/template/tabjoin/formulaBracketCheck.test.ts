import { describe, it, expect } from 'vitest';
import { checkParenBalance, scanParens } from './formulaBracketCheck';

describe('checkParenBalance', () => {
  it('平衡表达式 → ok', () => {
    expect(checkParenBalance('([单重]+[A.金额(总计)])*2')).toEqual({ ok: true });
  });

  it('空串 → ok', () => {
    expect(checkParenBalance('')).toEqual({ ok: true });
  });

  it('纯文本无括号 → ok', () => {
    expect(checkParenBalance('abc+1')).toEqual({ ok: true });
  });

  it('嵌套平衡 → ok', () => {
    expect(checkParenBalance('((1+2)*(3+4))')).toEqual({ ok: true });
  });

  it('块内 (总计) 不计入 → ok', () => {
    expect(checkParenBalance('[COMP_RL.金额(总计)]')).toEqual({ ok: true });
  });

  it('裸字段总计 [alias(总计)] 不计入 → ok', () => {
    expect(checkParenBalance('[COMP_RL(总计)] + 1')).toEqual({ ok: true });
  });

  it('缺 1 个右括号 → 报缺少', () => {
    const r = checkParenBalance('([单重]+1');
    expect(r.ok).toBe(false);
    expect(r.error).toContain('缺少 1 个右括号');
  });

  it('缺 2 个右括号 → 报缺少 2 个', () => {
    const r = checkParenBalance('((1+2');
    expect(r.ok).toBe(false);
    expect(r.error).toContain('缺少 2 个右括号');
  });

  it('多了右括号 → 报多余', () => {
    const r = checkParenBalance('[单重])');
    expect(r.ok).toBe(false);
    expect(r.error).toContain('多了');
  });

  it('顺序错 )( → 先遇无匹配右括号 → 报多余', () => {
    const r = checkParenBalance(')(');
    expect(r.ok).toBe(false);
    expect(r.error).toContain('多了');
  });

  it('块内圆括号被排除后仍能抓到块外真错', () => {
    const r = checkParenBalance('([COMP_RL.金额(总计)]');
    expect(r.ok).toBe(false);
    expect(r.error).toContain('缺少 1 个右括号');
  });

  // 已知 + 有意：未闭合 [ / { 块跳到串尾，本函数不报 []/{} 缺配对（由 lex() 保存时报）
  it('未闭合 [ 块被跳过 → ok（圆括号检查不负责 [] 配对）', () => {
    expect(checkParenBalance('[abc')).toEqual({ ok: true });
  });

  it('{...} 路径块内 () 不计入 → ok', () => {
    expect(checkParenBalance('{SUM(a,b)} + 1')).toEqual({ ok: true });
  });
});

describe('scanParens', () => {
  it('正常嵌套 → 4 个括号，depth 依次 0/1/1/0，两两配对，error 全 false', () => {
    const r = scanParens('((1+2)*3)');
    expect(r).toHaveLength(4);
    expect(r.map((p) => p.ch)).toEqual(['(', '(', ')', ')']);
    expect(r.map((p) => p.depth)).toEqual([0, 1, 1, 0]);
    expect(r.every((p) => !p.error)).toBe(true);
    // 两两配对：outer(0) <-> outer close(最后一个)，inner(1) <-> inner close
    expect(r[0].matchIndex).toBe(r[3].index);
    expect(r[3].matchIndex).toBe(r[0].index);
    expect(r[1].matchIndex).toBe(r[2].index);
    expect(r[2].matchIndex).toBe(r[1].index);
  });

  it('4 层以上循环 → depth 0/1/2/3/3/2/1/0（着色侧 %4 后循环）', () => {
    const r = scanParens('((((1))))');
    expect(r).toHaveLength(8);
    expect(r.map((p) => p.depth)).toEqual([0, 1, 2, 3, 3, 2, 1, 0]);
    expect(r.every((p) => !p.error)).toBe(true);
  });

  it('未闭合 → 该 "(" error: true，checkParenBalance().ok === false', () => {
    const r = scanParens('SUM([投料.金额]');
    expect(r).toHaveLength(1);
    expect(r[0].ch).toBe('(');
    expect(r[0].error).toBe(true);
    expect(r[0].matchIndex).toBeNull();
    expect(checkParenBalance('SUM([投料.金额]').ok).toBe(false);
  });

  it('多余右括号 → 该 ")" error: true', () => {
    const r = scanParens('1+2)');
    expect(r).toHaveLength(1);
    expect(r[0].ch).toBe(')');
    expect(r[0].error).toBe(true);
    expect(r[0].matchIndex).toBeNull();
  });

  it('[X(总计)] 不计数 → 只返 2 个括号（(1) 的），ok === true', () => {
    const r = scanParens('[回料(总计)] + (1)');
    expect(r).toHaveLength(2);
    expect(r.map((p) => p.ch)).toEqual(['(', ')']);
    expect(r[0].matchIndex).toBe(r[1].index);
    expect(r[1].matchIndex).toBe(r[0].index);
    expect(r.every((p) => !p.error)).toBe(true);
    expect(checkParenBalance('[回料(总计)] + (1)').ok).toBe(true);
  });

  it('{} 内不计数 → 只返 2 个，ok === true', () => {
    const r = scanParens('{a(b)} + (1)');
    expect(r).toHaveLength(2);
    expect(r.map((p) => p.ch)).toEqual(['(', ')']);
    expect(r[0].matchIndex).toBe(r[1].index);
    expect(checkParenBalance('{a(b)} + (1)').ok).toBe(true);
  });

  it('空串 / 无括号 → 返回 []，ok === true', () => {
    expect(scanParens('')).toEqual([]);
    expect(scanParens('[投料.金额]')).toEqual([]);
    expect(checkParenBalance('').ok).toBe(true);
    expect(checkParenBalance('[投料.金额]').ok).toBe(true);
  });
});
