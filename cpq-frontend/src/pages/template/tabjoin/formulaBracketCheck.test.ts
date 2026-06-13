import { describe, it, expect } from 'vitest';
import { checkParenBalance } from './formulaBracketCheck';

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
});
