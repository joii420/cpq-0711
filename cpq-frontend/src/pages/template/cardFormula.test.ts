import { describe, it, expect } from 'vitest';
import { genAlias, expandIn, extractColKeyDeps, validateCardFormula, buildCondRows, parseCondToRows, nextAggRefKey } from './cardFormula';

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
  it('拦截聚合被方括号包裹', () => {
    const errs = validateCardFormula(
      { col_key:'B', formula:"=[SUM_OVER([元素] WHERE c0=='x', c1)]", refs:{ '元素':{tab:'t:2', cols:{c0:'类',c1:'量'}} } } as any,
      ['B'], {});
    expect(errs.some(e => e.includes('聚合'))).toBe(true);
  });
  it('拦截括号不配平', () => {
    const errs = validateCardFormula({ col_key:'A', formula:'=ROUND([投料.小计], 2', refs:{'投料.小计':{tab:'t:0',field:'__subtotal__'}} } as any, ['A'], {});
    expect(errs.some(e => e.includes('括号'))).toBe(true);
  });
  it('拦截未知函数', () => {
    const errs = validateCardFormula({ col_key:'A', formula:'=FOO([投料.小计])', refs:{'投料.小计':{tab:'t:0',field:'__subtotal__'}} } as any, ['A'], {});
    expect(errs.some(e => e.includes('未知函数') || e.includes('FOO'))).toBe(true);
  });

  it('buildCondRows 过滤空字段 + 透传 rhs', () => {
    const rows = buildCondRows([
      { field: '关联号', op: 'eq', value: '__partNo__', logic: 'and', rhsType: 'product' },
      { field: '', op: 'eq', value: 'x', logic: 'and', rhsType: 'literal' },
      { field: '类型', op: 'eq', value: '电镀', logic: 'and', rhsType: 'literal' },
    ]);
    expect(rows).toEqual([
      { left: '关联号', op: 'eq', logic: 'and', rhs: { type: 'product', value: '__partNo__' } },
      { left: '类型', op: 'eq', logic: 'and', rhs: { type: 'literal', value: '电镀' } },
    ]);
  });

  it('parseCondToRows 反解析字面量 cond（含 && 与 IN）', () => {
    const cols = { c0: '工序', c1: '数量' };
    const rows = parseCondToRows("(c0=='镀铜' || c0=='镀镍') && c1>0", cols);
    expect(rows).toEqual([
      { left: '工序', op: 'in', logic: 'and', rhs: { type: 'literal', value: '镀铜,镀镍' } },
      { left: '数量', op: 'gt', logic: 'and', rhs: { type: 'literal', value: '0' } },
    ]);
  });

  it('parseCondToRows 空串 → []', () => {
    expect(parseCondToRows('', {})).toEqual([]);
  });

  it('validateCardFormula 接受含 # 的聚合 token [页签#N]', () => {
    const errs = validateCardFormula(
      { col_key: 'A', formula: "=SUM_OVER([投料#1], c0)", refs: { '投料#1': { tab: 't:0', cols: { c0: '量' } } } } as any,
      ['A'], {});
    expect(errs.some(e => e.includes('非法字符'))).toBe(false);
  });

  it('nextAggRefKey 同页签递增、跨页签独立、含旧无后缀', () => {
    expect(nextAggRefKey('投料', [])).toBe('投料#1');
    expect(nextAggRefKey('投料', ['投料'])).toBe('投料#1');           // 旧无后缀 → 新从 #1
    expect(nextAggRefKey('投料', ['投料#1'])).toBe('投料#2');
    expect(nextAggRefKey('投料', ['投料#1', '投料#3'])).toBe('投料#4'); // max+1
    expect(nextAggRefKey('投料', ['加工#5'])).toBe('投料#1');          // 跨页签独立
    expect(nextAggRefKey('投料', ['投料.小计', '投料.量(条件)'])).toBe('投料#1'); // 非聚合 key 不计
  });
});
