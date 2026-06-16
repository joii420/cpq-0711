import { describe, it, expect } from 'vitest';
import { parsePredicateText, serializePredicate } from '../predicateText';
import type { ConditionPredicate } from '../formulaEngine';

describe('parsePredicateText (镜像后端 ConditionPredicateParser)', () => {
  it('field = string literal', () => {
    expect(parsePredicateText("[其他费用.类型] = '管理费'")).toEqual({
      op: '=', lhs: { kind: 'sourceField', field: '类型' }, rhs: { kind: 'literal', value: '管理费' },
    });
  });
  it('first alias = source, others = host', () => {
    expect(parsePredicateText('[A.a] = [B.b]')).toEqual({
      op: '=', lhs: { kind: 'sourceField', field: 'a' }, rhs: { kind: 'hostField', field: 'b' },
    });
  });
  it('AND with number + parens', () => {
    const p = parsePredicateText("[A.类型]='管理费' AND [A.金额] > 1000");
    expect(p).toEqual({ bool: 'AND', children: [
      { op: '=', lhs: { kind: 'sourceField', field: '类型' }, rhs: { kind: 'literal', value: '管理费' } },
      { op: '>', lhs: { kind: 'sourceField', field: '金额' }, rhs: { kind: 'literal', value: '1000' } },
    ]});
  });
  it('!= / <> aliases', () => {
    expect((parsePredicateText("[A.x] <> '运费'") as any).op).toBe('<>');
  });
  it('malformed throws', () => {
    expect(() => parsePredicateText('[A.x] =')).toThrow();
  });
});

describe('serializePredicate (token → 文本, 用 sumif 源别名/宿主别名)', () => {
  const ctx = { sourceAlias: '其他费用', hostAlias: '来料' };
  it('round-trips a literal comparison', () => {
    const p: ConditionPredicate = { op: '=', lhs: { kind: 'sourceField', field: '类型' }, rhs: { kind: 'literal', value: '管理费' } };
    const s = serializePredicate(p, ctx);
    expect(s).toBe("[其他费用.类型] = '管理费'");
    expect(parsePredicateText(s)).toEqual(p);
  });
  it('serializes hostField with hostAlias + AND tree', () => {
    const p: ConditionPredicate = { bool: 'AND', children: [
      { op: '=', lhs: { kind: 'sourceField', field: 'a' }, rhs: { kind: 'hostField', field: 'b' } },
      { op: '>', lhs: { kind: 'sourceField', field: '金额' }, rhs: { kind: 'literal', value: '1000' } },
    ]};
    const s = serializePredicate(p, ctx);
    expect(s).toBe("[其他费用.a] = [来料.b] AND [其他费用.金额] > 1000");
  });
  it('numeric literal not quoted', () => {
    const p: ConditionPredicate = { op: '>', lhs: { kind: 'sourceField', field: '金额' }, rhs: { kind: 'literal', value: '1000' } };
    expect(serializePredicate(p, ctx)).toBe('[其他费用.金额] > 1000');
  });
});
