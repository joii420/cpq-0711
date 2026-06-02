import { describe, it, expect } from 'vitest';
import { genAlias, expandIn, extractColKeyDeps, validateCardFormula } from './cardFormula';

describe('cardFormula pure logic', () => {
  it('genAlias', () => { expect(genAlias(0)).toBe('c0'); expect(genAlias(3)).toBe('c3'); });
  it('expandIn → ||', () => {
    expect(expandIn('c0', ['电镀','酸洗'])).toBe("(c0=='电镀' || c0=='酸洗')");
  });
  it('extractColKeyDeps 只抓裸 col_key, 跳过含点占位', () => {
    expect(extractColKeyDeps('[投料.小计] + [A] * 2', ['A','B'])).toEqual(['A']);
  });
  it('validateCardFormula 报占位缺失', () => {
    const errs = validateCardFormula(
      { col_key:'B', formula:'=[加工.加工费]', refs:{} } as any, ['B'], {});
    expect(errs.length).toBeGreaterThan(0);
  });
  it('validateCardFormula 报列引列环', () => {
    const errs = validateCardFormula(
      { col_key:'A', formula:'=[B]+1', refs:{} } as any,
      ['A','B'], { A:'=[B]+1', B:'=[A]+1' });
    expect(errs.some(e => e.includes('循环'))).toBe(true);
  });
});
