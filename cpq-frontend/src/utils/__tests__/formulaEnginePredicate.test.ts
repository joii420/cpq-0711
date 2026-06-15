import { describe, it, expect } from 'vitest';
import { evalPredicate, type ConditionPredicate } from '../formulaEngine';

describe('evalPredicate (与后端 ConditionPredicateEvaluator 逐用例对齐)', () => {
  const arow = { 类型: '管理费', 金额: '1500' };
  const host = { b: 'X' };

  it('null → true', () => expect(evalPredicate(null, {}, {})).toBe(true));

  it('source = literal text', () => {
    const p: ConditionPredicate = { op: '=', lhs: { kind: 'sourceField', field: '类型' }, rhs: { kind: 'literal', value: '管理费' } };
    expect(evalPredicate(p, arow, host)).toBe(true);
    expect(evalPredicate(p, { 类型: '运费' }, host)).toBe(false);
  });

  it('gt numeric only', () => {
    const p: ConditionPredicate = { op: '>', lhs: { kind: 'sourceField', field: '金额' }, rhs: { kind: 'literal', value: '1000' } };
    expect(evalPredicate(p, arow, host)).toBe(true);
    expect(evalPredicate(p, { 金额: '非数字' }, host)).toBe(false);
  });

  it('AND/OR nesting', () => {
    const c1: ConditionPredicate = { op: '=', lhs: { kind: 'sourceField', field: '类型' }, rhs: { kind: 'literal', value: '管理费' } };
    const c2: ConditionPredicate = { op: '>', lhs: { kind: 'sourceField', field: '金额' }, rhs: { kind: 'literal', value: '1000' } };
    expect(evalPredicate({ bool: 'AND', children: [c1, c2] }, arow, host)).toBe(true);
    expect(evalPredicate({ bool: 'AND', children: [c1, c2] }, { 类型: '管理费', 金额: '500' }, host)).toBe(false);
  });

  it('blank → eq false, ne true', () => {
    const eq: ConditionPredicate = { op: '=', lhs: { kind: 'sourceField', field: 'a' }, rhs: { kind: 'literal', value: 'x' } };
    const ne: ConditionPredicate = { op: '!=', lhs: { kind: 'sourceField', field: 'a' }, rhs: { kind: 'literal', value: 'x' } };
    expect(evalPredicate(eq, { a: null }, {})).toBe(false);
    expect(evalPredicate(ne, { a: null }, {})).toBe(true);
  });

  it('hostField operand — source matches host', () => {
    const p: ConditionPredicate = { op: '=', lhs: { kind: 'sourceField', field: 'a' }, rhs: { kind: 'hostField', field: 'b' } };
    expect(evalPredicate(p, { a: 'X' }, { b: 'X' })).toBe(true);
    expect(evalPredicate(p, { a: 'X' }, { b: 'Y' })).toBe(false);
  });

  it('<> operator (alias for !=)', () => {
    const p: ConditionPredicate = { op: '<>', lhs: { kind: 'sourceField', field: '类型' }, rhs: { kind: 'literal', value: '运费' } };
    expect(evalPredicate(p, { 类型: '管理费' }, {})).toBe(true);
    expect(evalPredicate(p, { 类型: '运费' }, {})).toBe(false);
  });

  it('OR — any child true → true; all false → false', () => {
    const c1: ConditionPredicate = { op: '=', lhs: { kind: 'sourceField', field: '类型' }, rhs: { kind: 'literal', value: '管理费' } };
    const c2: ConditionPredicate = { op: '>', lhs: { kind: 'sourceField', field: '金额' }, rhs: { kind: 'literal', value: '2000' } };
    // c1=true, c2=false → OR true
    expect(evalPredicate({ bool: 'OR', children: [c1, c2] }, arow, host)).toBe(true);
    // c1=false, c2=false → OR false
    expect(evalPredicate({ bool: 'OR', children: [c1, c2] }, { 类型: '运费', 金额: '500' }, host)).toBe(false);
  });

  it('AND empty children → true; OR empty children → false', () => {
    expect(evalPredicate({ bool: 'AND', children: [] }, {}, {})).toBe(true);
    expect(evalPredicate({ bool: 'OR', children: [] }, {}, {})).toBe(false);
  });

  it('numeric format-insensitive equality (1000.0 == 1000)', () => {
    const p: ConditionPredicate = { op: '=', lhs: { kind: 'sourceField', field: 'n' }, rhs: { kind: 'literal', value: '1000' } };
    expect(evalPredicate(p, { n: '1000.0' }, {})).toBe(true);
    expect(evalPredicate(p, { n: '999.9' }, {})).toBe(false);
  });
});
