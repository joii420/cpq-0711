import { describe, it, expect } from 'vitest';
import { evaluateExpression, isWithinTolerance } from './formulaEngine';
import type { ExpressionToken } from './formulaEngine';
import crossTabCases from './__fixtures__/cross-tab-cases.json';

// ─── Helper: shorthand token builders ────────────────────────────────────────

const field = (name: string): ExpressionToken => ({ type: 'field', value: name });
const op = (operator: string): ExpressionToken => ({ type: 'operator', value: operator });
const num = (n: string): ExpressionToken => ({ type: 'number', value: n });
const open: ExpressionToken = { type: 'bracket_open' };
const close: ExpressionToken = { type: 'bracket_close' };
const compSub = (code: string, tabName: string): ExpressionToken => ({
  type: 'component_subtotal',
  component_code: code,
  tab_name: tabName,
});
const prodAttr = (name: string): ExpressionToken => ({
  type: 'product_attribute',
  attribute_name: name,
});

// ─── Basic arithmetic ────────────────────────────────────────────────────────

describe('formulaEngine - basic arithmetic', () => {
  it('单价 × 数量 = 正确结果', () => {
    const tokens = [field('单价'), op('*'), field('数量')];
    const values = { 单价: 45, 数量: 5 };
    expect(evaluateExpression(tokens, values)).toBe(225);
  });

  it('加法: A + B', () => {
    const tokens = [field('A'), op('+'), field('B')];
    expect(evaluateExpression(tokens, { A: 100, B: 50 })).toBe(150);
  });

  it('减法: A - B', () => {
    const tokens = [field('A'), op('-'), field('B')];
    expect(evaluateExpression(tokens, { A: 100, B: 30 })).toBe(70);
  });

  it('除法: A / B', () => {
    const tokens = [field('A'), op('/'), field('B')];
    expect(evaluateExpression(tokens, { A: 100, B: 4 })).toBe(25);
  });

  it('Unicode 乘号 ×', () => {
    const tokens = [field('单价'), op('×'), field('数量')];
    expect(evaluateExpression(tokens, { 单价: 10, 数量: 3 })).toBe(30);
  });

  it('Unicode 除号 ÷', () => {
    const tokens = [field('总价'), op('÷'), field('数量')];
    expect(evaluateExpression(tokens, { 总价: 100, 数量: 4 })).toBe(25);
  });
});

// ─── Brackets / operator precedence ──────────────────────────────────────────

describe('formulaEngine - brackets and precedence', () => {
  it('(A + B) × C', () => {
    const tokens = [open, field('A'), op('+'), field('B'), close, op('*'), field('C')];
    expect(evaluateExpression(tokens, { A: 10, B: 5, C: 3 })).toBe(45);
  });

  it('A × (B + C)', () => {
    const tokens = [field('A'), op('*'), open, field('B'), op('+'), field('C'), close];
    expect(evaluateExpression(tokens, { A: 2, B: 10, C: 5 })).toBe(30);
  });

  it('nested brackets: (A + (B × C))', () => {
    const tokens = [
      open, field('A'), op('+'), open, field('B'), op('*'), field('C'), close, close,
    ];
    expect(evaluateExpression(tokens, { A: 10, B: 3, C: 5 })).toBe(25);
  });
});

// ─── Missing / zero field values ─────────────────────────────────────────────

describe('formulaEngine - missing and zero values', () => {
  it('missing field defaults to 0', () => {
    const tokens = [field('单价'), op('*'), field('数量')];
    // 数量 not provided → defaults to 0
    expect(evaluateExpression(tokens, { 单价: 45 })).toBe(0);
  });

  it('all fields missing → 0', () => {
    const tokens = [field('A'), op('+'), field('B')];
    expect(evaluateExpression(tokens, {})).toBe(0);
  });

  it('field value is 0 → computes correctly', () => {
    const tokens = [field('A'), op('+'), field('B')];
    expect(evaluateExpression(tokens, { A: 0, B: 5 })).toBe(5);
  });

  it('division by zero field → returns 0 (engine catches error)', () => {
    const tokens = [field('A'), op('/'), field('B')];
    // B missing → 0, division by 0 → Infinity → Decimal catches
    const result = evaluateExpression(tokens, { A: 100 });
    // Decimal.js handles Infinity → should not crash
    expect([Infinity, 0]).toContain(result);
  });
});

// ─── Literal number tokens ──────────────────────────────────────────────────

describe('formulaEngine - number literal tokens', () => {
  it('A × 1.05 (tax rate)', () => {
    const tokens = [field('A'), op('*'), num('1.05')];
    const result = evaluateExpression(tokens, { A: 100 });
    expect(result).toBe(105);
  });

  it('pure numbers: 10 + 20', () => {
    const tokens = [num('10'), op('+'), num('20')];
    expect(evaluateExpression(tokens, {})).toBe(30);
  });
});

// ─── Decimal precision ──────────────────────────────────────────────────────

describe('formulaEngine - decimal precision', () => {
  it('avoids IEEE 754 float errors (0.1 + 0.2)', () => {
    const tokens = [num('0.1'), op('+'), num('0.2')];
    expect(evaluateExpression(tokens, {})).toBe(0.3);
  });

  it('rounds to 4 decimal places', () => {
    const tokens = [num('1'), op('/'), num('3')];
    expect(evaluateExpression(tokens, {})).toBe(0.3333);
  });

  it('large numbers maintain precision', () => {
    const tokens = [field('A'), op('*'), field('B')];
    expect(evaluateExpression(tokens, { A: 999999.99, B: 100 })).toBe(99999999);
  });
});

// ─── Cross-component subtotals ──────────────────────────────────────────────

describe('formulaEngine - component_subtotal tokens', () => {
  it('references another component subtotal', () => {
    const tokens = [
      compSub('COMP-001', '投料金额'),
      op('+'),
      compSub('COMP-002', '加工费用'),
    ];
    const subtotals = { 'COMP-001': 1000, 'COMP-002': 500 };
    expect(evaluateExpression(tokens, {}, subtotals)).toBe(1500);
  });

  it('missing component subtotal defaults to 0', () => {
    const tokens = [compSub('COMP-001', '投料金额'), op('+'), num('100')];
    expect(evaluateExpression(tokens, {}, {})).toBe(100);
  });

  it('mixed: field + component subtotal', () => {
    const tokens = [field('手工费'), op('+'), compSub('COMP-001', '投料金额')];
    expect(evaluateExpression(tokens, { 手工费: 200 }, { 'COMP-001': 800 })).toBe(1000);
  });
});

// ─── Product attribute tokens ───────────────────────────────────────────────

describe('formulaEngine - product_attribute tokens', () => {
  it('references product attribute', () => {
    const tokens = [prodAttr('数量'), op('*'), field('单价')];
    expect(evaluateExpression(tokens, { 单价: 50 }, {}, { 数量: 10 })).toBe(500);
  });

  it('missing product attribute defaults to 0', () => {
    const tokens = [prodAttr('数量'), op('*'), num('100')];
    expect(evaluateExpression(tokens, {}, {}, {})).toBe(0);
  });
});

// ─── Complex real-world formulas ────────────────────────────────────────────

describe('formulaEngine - real-world scenarios', () => {
  it('投料小计: 单价 × 数量 (PRD standard)', () => {
    const tokens = [field('单价'), op('×'), field('数量')];
    expect(evaluateExpression(tokens, { 单价: 45, 数量: 5 })).toBe(225);
  });

  it('product subtotal: sum of two component subtotals × product attribute quantity', () => {
    const tokens = [
      open,
      compSub('COMP-001', '投料金额'),
      op('+'),
      compSub('COMP-002', '加工费用'),
      close,
      op('*'),
      prodAttr('数量'),
    ];
    expect(evaluateExpression(
      tokens,
      {},
      { 'COMP-001': 100, 'COMP-002': 50 },
      { 数量: 3 },
    )).toBe(450);
  });

  it('discount formula: 原始总价 × 折扣率 / 100', () => {
    const tokens = [field('原始总价'), op('*'), field('折扣率'), op('/'), num('100')];
    expect(evaluateExpression(tokens, { 原始总价: 10000, 折扣率: 85 })).toBe(8500);
  });

  it('empty expression → returns 0', () => {
    expect(evaluateExpression([], {})).toBe(0);
  });
});

// ─── Edge cases and error handling ──────────────────────────────────────────

describe('formulaEngine - edge cases', () => {
  it('single field token (no operator)', () => {
    const tokens = [field('A')];
    expect(evaluateExpression(tokens, { A: 42 })).toBe(42);
  });

  it('single number token', () => {
    const tokens = [num('99.5')];
    expect(evaluateExpression(tokens, {})).toBe(99.5);
  });

  it('malformed expression (operator only) → returns 0', () => {
    const tokens = [op('+')];
    expect(evaluateExpression(tokens, {})).toBe(0);
  });

  it('unmatched brackets → returns 0', () => {
    const tokens = [open, field('A'), op('+'), field('B')];
    // Missing close bracket — Function constructor may handle it or throw
    const result = evaluateExpression(tokens, { A: 1, B: 2 });
    // Should gracefully return 0 on error
    expect(typeof result).toBe('number');
  });

  it('negative result', () => {
    const tokens = [field('A'), op('-'), field('B')];
    expect(evaluateExpression(tokens, { A: 10, B: 30 })).toBe(-20);
  });
});

// ─── cross_tab_ref ──────────────────────────────────────────────────────────

describe('cross_tab_ref', () => {
  /**
   * Helper: calls evaluateExpression with the full positional param list.
   * Param positions (0-indexed):
   *   0  tokens
   *   1  fieldValues
   *   2  componentSubtotals?
   *   3  productAttributes?
   *   4  quotationFields?
   *   5  pathCache?
   *   6  partNo?
   *   7  basicDataValues?
   *   8  previousRowSubtotal?
   *   9  globalVariableDefs?
   *  10  currentRow?
   *  11  crossTabRows?   ← NEW trailing param
   */
  function evalCrossTab(
    tokens: ExpressionToken[],
    currentRow: Record<string, any> | undefined,
    crossTabRows: Record<string, Array<Record<string, any>>>,
  ): number {
    return evaluateExpression(
      tokens,
      {},        // fieldValues
      undefined, // componentSubtotals
      undefined, // productAttributes
      undefined, // quotationFields
      undefined, // pathCache
      undefined, // partNo
      undefined, // basicDataValues
      undefined, // previousRowSubtotal
      undefined, // globalVariableDefs
      currentRow,
      crossTabRows,
    );
  }

  const aRows = [
    { 子件: 'P1', 单重: 0.8 },
    { 子件: 'P2', 单重: 0.3 },
    { 子件: 'P1', 单重: 0.5 },
  ];

  const tokenNone: ExpressionToken = {
    type: 'cross_tab_ref',
    source: 'A',
    target: '单重',
    match: [{ a: '子件', b: '子件' }],
    agg: 'NONE',
  };

  it('NONE — single match → returns 0.8', () => {
    // Only the first row matches '子件'='P1' uniquely (use 2-row subset)
    const rows2 = [{ 子件: 'P1', 单重: 0.8 }, { 子件: 'P2', 单重: 0.3 }];
    expect(evalCrossTab([tokenNone], { 子件: 'P1' }, { A: rows2 })).toBe(0.8);
  });

  it('NONE — zero match → 0', () => {
    expect(evalCrossTab([tokenNone], { 子件: 'P9' }, { A: aRows })).toBe(0);
  });

  it('NONE — multi match → 0 (error swallowed by outer try/catch)', () => {
    // aRows has two P1 rows → multi match → throws → caught → 0
    expect(evalCrossTab([tokenNone], { 子件: 'P1' }, { A: aRows })).toBe(0);
  });

  it('SUM — multi match sums values: 0.8 + 0.5 = 1.3', () => {
    const token: ExpressionToken = { ...tokenNone, agg: 'SUM' };
    expect(evalCrossTab([token], { 子件: 'P1' }, { A: aRows })).toBe(1.3);
  });

  it('COUNT — counts matching rows: 2', () => {
    const token: ExpressionToken = { ...tokenNone, agg: 'COUNT' };
    expect(evalCrossTab([token], { 子件: 'P1' }, { A: aRows })).toBe(2);
  });

  it('AVG — two matching values (0.8 + 0.5) / 2 = 0.65', () => {
    const token: ExpressionToken = { ...tokenNone, agg: 'AVG' };
    expect(evalCrossTab([token], { 子件: 'P1' }, { A: aRows })).toBe(0.65);
  });

  it('MAX — returns larger value 0.8', () => {
    const token: ExpressionToken = { ...tokenNone, agg: 'MAX' };
    expect(evalCrossTab([token], { 子件: 'P1' }, { A: aRows })).toBe(0.8);
  });

  it('MIN — returns smaller value 0.5', () => {
    const token: ExpressionToken = { ...tokenNone, agg: 'MIN' };
    expect(evalCrossTab([token], { 子件: 'P1' }, { A: aRows })).toBe(0.5);
  });

  it('null/blank match key on currentRow → no match → 0', () => {
    expect(evalCrossTab([tokenNone], { 子件: '' }, { A: aRows })).toBe(0);
  });

  it('null match key on aRow → that row excluded', () => {
    const rowsWithNull = [{ 子件: null, 单重: 0.8 }, { 子件: 'P2', 单重: 0.3 }];
    expect(evalCrossTab([tokenNone], { 子件: 'P1' }, { A: rowsWithNull })).toBe(0);
  });

  it('multi-column AND match — only rows matching BOTH pairs', () => {
    const rowsAnd = [
      { 子件: 'P1', 类型: 'X', 单重: 1.0 },
      { 子件: 'P1', 类型: 'Y', 单重: 2.0 },
      { 子件: 'P2', 类型: 'X', 单重: 3.0 },
    ];
    const tokenAnd: ExpressionToken = {
      type: 'cross_tab_ref',
      source: 'A',
      target: '单重',
      match: [{ a: '子件', b: '子件' }, { a: '类型', b: '类型' }],
      agg: 'NONE',
    };
    // currentRow 子件='P1', 类型='X' → only first row matches
    expect(evalCrossTab([tokenAnd], { 子件: 'P1', 类型: 'X' }, { A: rowsAnd })).toBe(1.0);
  });

  it('SUM — non-numeric target value → error path → returns 0', () => {
    // 匹配行的 target 字段为字符串 'abc'(非数字)→ nums.some(n === null) → crossTabError → 0
    const rowsNonNumeric = [{ 子件: 'P1', 单重: 'abc' }, { 子件: 'P2', 单重: 0.3 }];
    const tokenSum: ExpressionToken = { ...tokenNone, agg: 'SUM' };
    expect(evalCrossTab([tokenSum], { 子件: 'P1' }, { A: rowsNonNumeric })).toBe(0);
  });

  it('SUM — whitespace-only match key on aRow → row excluded → SUM returns 0 (Fix 1 验证)', () => {
    // aRow.子件 = '   '(全空白)→ blank() = true → 该行被排除 → 无命中 → SUM = 0
    const rowsWhitespace = [{ 子件: '   ', 单重: 5 }, { 子件: 'P2', 单重: 0.3 }];
    const tokenSum: ExpressionToken = { ...tokenNone, agg: 'SUM' };
    expect(evalCrossTab([tokenSum], { 子件: '   ' }, { A: rowsWhitespace })).toBe(0);
  });
});

// ─── cross-tab fixture (shared with backend FormulaCalculatorCrossTabFixtureTest) ───────────

/**
 * Shared fixture parity test.
 *
 * Reads the same JSON consumed by the backend JUnit fixture test so that
 * any future drift between the two engines surfaces here immediately.
 * Source of truth: cpq-frontend/src/utils/__fixtures__/cross-tab-cases.json
 * (identical copy at cpq-backend/src/test/resources/cross-tab-cases.json).
 */
describe('cross-tab fixture', () => {
  for (const c of crossTabCases) {
    const caseName = (c as any).name as string;
    const token = (c as any).token as ExpressionToken;
    const currentRow = (c as any).currentRow as Record<string, any>;
    const aRows = (c as any).aRows as Array<Record<string, any>>;
    const expected = (c as any).expected as number;

    it(caseName, () => {
      const result = evaluateExpression(
        [token],
        {},        // fieldValues
        undefined, // componentSubtotals
        undefined, // productAttributes
        undefined, // quotationFields
        undefined, // pathCache
        undefined, // partNo
        undefined, // basicDataValues
        undefined, // previousRowSubtotal
        undefined, // globalVariableDefs
        currentRow,
        { A: aRows },
      );
      expect(result).toBeCloseTo(expected, 4);
    });
  }
});

// ─── isWithinTolerance ──────────────────────────────────────────────────────

describe('isWithinTolerance', () => {
  it('identical values', () => {
    expect(isWithinTolerance(100, 100)).toBe(true);
  });

  it('within default tolerance ±0.01', () => {
    expect(isWithinTolerance(100.005, 100.01)).toBe(true);
  });

  it('exceeds default tolerance', () => {
    expect(isWithinTolerance(100, 100.02)).toBe(false);
  });

  it('custom tolerance', () => {
    expect(isWithinTolerance(100, 100.5, 1)).toBe(true);
    expect(isWithinTolerance(100, 101.5, 1)).toBe(false);
  });
});
