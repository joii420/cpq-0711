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

describe('嵌套括号 parse / round-trip', () => {
  // 用例 1：嵌套括号 parse
  it('nested parens parse: (OR) AND cmp → 顶层 AND, 左子 OR', () => {
    const p = parsePredicateText("([A.a]='1' OR [A.b]='2') AND [A.c]='3'");
    expect(p).toEqual({
      bool: 'AND',
      children: [
        {
          bool: 'OR',
          children: [
            { op: '=', lhs: { kind: 'sourceField', field: 'a' }, rhs: { kind: 'literal', value: '1' } },
            { op: '=', lhs: { kind: 'sourceField', field: 'b' }, rhs: { kind: 'literal', value: '2' } },
          ],
        },
        { op: '=', lhs: { kind: 'sourceField', field: 'c' }, rhs: { kind: 'literal', value: '3' } },
      ],
    });
  });

  // 用例 2：嵌套括号 round-trip（serialize 对 Bool 子节点加括号，保住优先级）
  it('nested parens round-trip: serialize → text → parse 结构不变', () => {
    const original = parsePredicateText("([A.a]='1' OR [A.b]='2') AND [A.c]='3'");
    const text = serializePredicate(original, { sourceAlias: 'A', hostAlias: 'H' });
    // 序列化结果必须含括号，否则 OR 优先级被破坏
    expect(text).toContain('(');
    const reparsed = parsePredicateText(text);
    expect(reparsed).toEqual(original);
  });
});

describe('hostField round-trip', () => {
  // 用例 3：hostField 序列化再解析后与原 predicate 相等
  it('single hostField comparison round-trips', () => {
    const p: ConditionPredicate = {
      op: '=',
      lhs: { kind: 'sourceField', field: 'a' },
      rhs: { kind: 'hostField', field: 'b' },
    };
    const text = serializePredicate(p, { sourceAlias: '其他费用', hostAlias: '来料' });
    // text = "[其他费用.a] = [来料.b]"
    // parse 时首个别名 = 其他费用 → sourceField，来料 → hostField
    const reparsed = parsePredicateText(text);
    expect(reparsed).toEqual(p);
  });
});

describe('非法输入抛错', () => {
  // 用例 4a：缺右操作数
  it('[A.x] > (缺右操作数) throws', () => {
    expect(() => parsePredicateText('[A.x] >')).toThrow();
  });

  // 用例 4b：缺右括号
  it("([A.x]='1' (缺右括号) throws", () => {
    expect(() => parsePredicateText("([A.x]='1'")).toThrow();
  });

  // 用例 4c：未闭合字符串
  it("[A.x]='unterminated (未闭合字符串) throws", () => {
    expect(() => parsePredicateText("[A.x]='unterminated")).toThrow();
  });
});
