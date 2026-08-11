import { describe, it, expect } from 'vitest';
import {
  evaluateExpression as evaluateExpressionDecimal,
  isWithinTolerance as isWithinToleranceDecimal,
} from './formulaEngine';
import type { ExpressionToken } from './formulaEngine';
import type { DecimalString } from './precision';
import crossTabCases from './__fixtures__/cross-tab-cases.json';

function decimalizeLegacyFixture(value: any): any {
  if (typeof value === 'number') return String(value);
  if (Array.isArray(value)) return value.map(decimalizeLegacyFixture);
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, decimalizeLegacyFixture(child)]));
  }
  return value;
}

/** Legacy semantic fixtures contain small numeric literals; production output remains decimal string. */
function evaluateExpression(...args: any[]): DecimalString {
  const normalized = [...args];
  for (const index of [1, 2, 3, 4, 5, 7, 8, 10, 11, 14]) {
    if (normalized[index] !== undefined) normalized[index] = decimalizeLegacyFixture(normalized[index]);
  }
  return evaluateExpressionDecimal(...normalized as Parameters<typeof evaluateExpressionDecimal>);
}

function expectLegacyDecimal(actual: DecimalString, expected: string | number): void {
  expect(actual).toBe(String(expected));
}

function isWithinTolerance(left: number, right: number, tolerance?: number): boolean {
  return isWithinToleranceDecimal(String(left), String(right), tolerance == null ? undefined : String(tolerance));
}

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
    expectLegacyDecimal(evaluateExpression(tokens, values), '225');
  });

  it('加法: A + B', () => {
    const tokens = [field('A'), op('+'), field('B')];
    expectLegacyDecimal(evaluateExpression(tokens, { A: 100, B: 50 }), '150');
  });

  it('减法: A - B', () => {
    const tokens = [field('A'), op('-'), field('B')];
    expectLegacyDecimal(evaluateExpression(tokens, { A: 100, B: 30 }), '70');
  });

  it('除法: A / B', () => {
    const tokens = [field('A'), op('/'), field('B')];
    expectLegacyDecimal(evaluateExpression(tokens, { A: 100, B: 4 }), '25');
  });

  it('Unicode 乘号 ×', () => {
    const tokens = [field('单价'), op('×'), field('数量')];
    expectLegacyDecimal(evaluateExpression(tokens, { 单价: 10, 数量: 3 }), '30');
  });

  it('Unicode 除号 ÷', () => {
    const tokens = [field('总价'), op('÷'), field('数量')];
    expectLegacyDecimal(evaluateExpression(tokens, { 总价: 100, 数量: 4 }), '25');
  });
});

// ─── Brackets / operator precedence ──────────────────────────────────────────

describe('formulaEngine - brackets and precedence', () => {
  it('(A + B) × C', () => {
    const tokens = [open, field('A'), op('+'), field('B'), close, op('*'), field('C')];
    expectLegacyDecimal(evaluateExpression(tokens, { A: 10, B: 5, C: 3 }), '45');
  });

  it('A × (B + C)', () => {
    const tokens = [field('A'), op('*'), open, field('B'), op('+'), field('C'), close];
    expectLegacyDecimal(evaluateExpression(tokens, { A: 2, B: 10, C: 5 }), '30');
  });

  it('nested brackets: (A + (B × C))', () => {
    const tokens = [
      open, field('A'), op('+'), open, field('B'), op('*'), field('C'), close, close,
    ];
    expectLegacyDecimal(evaluateExpression(tokens, { A: 10, B: 3, C: 5 }), '25');
  });
});

// ─── Missing / zero field values ─────────────────────────────────────────────

describe('formulaEngine - missing and zero values', () => {
  it('missing field defaults to 0', () => {
    const tokens = [field('单价'), op('*'), field('数量')];
    // 数量 not provided → defaults to 0
    expectLegacyDecimal(evaluateExpression(tokens, { 单价: 45 }), '0');
  });

  it('all fields missing → 0', () => {
    const tokens = [field('A'), op('+'), field('B')];
    expectLegacyDecimal(evaluateExpression(tokens, {}), '0');
  });

  it('field value is 0 → computes correctly', () => {
    const tokens = [field('A'), op('+'), field('B')];
    expectLegacyDecimal(evaluateExpression(tokens, { A: 0, B: 5 }), '5');
  });

  it('division by zero field → returns 0 (engine catches error)', () => {
    const tokens = [field('A'), op('/'), field('B')];
    // B missing → 0, division by 0.
    // task-0801：evaluateArithmetic 对除以 0 有确定性契约（G-9）—— 恒返回 0，不再依赖
    // decimal.js 对 Infinity 的隐式兼容；断言收紧为精确值（原 [Infinity, 0] 宽松断言已可收紧）。
    const result = evaluateExpression(tokens, { A: 100 });
    expectLegacyDecimal(result, '0');
  });
});

// ─── Literal number tokens ──────────────────────────────────────────────────

describe('formulaEngine - number literal tokens', () => {
  it('A × 1.05 (tax rate)', () => {
    const tokens = [field('A'), op('*'), num('1.05')];
    const result = evaluateExpression(tokens, { A: 100 });
    expectLegacyDecimal(result, '105');
  });

  it('pure numbers: 10 + 20', () => {
    const tokens = [num('10'), op('+'), num('20')];
    expectLegacyDecimal(evaluateExpression(tokens, {}), '30');
  });
});

// ─── Decimal precision ──────────────────────────────────────────────────────

describe('formulaEngine - decimal precision', () => {
  it('avoids IEEE 754 float errors (0.1 + 0.2)', () => {
    const tokens = [num('0.1'), op('+'), num('0.2')];
    expect(evaluateExpression(tokens, {})).toBe('0.3');
  });

  it('task-0801: 不再中途 4 位截断 —— 除法走 DIVISION_SCALE(12) 位中间精度，非 4 位', () => {
    // [语义变化，非 bug]：evaluateExpression 内部 toDecimalPlaces(4) 已随 task-0801 移除
    // （中间不截断，见 formulaEngine.ts:584 注释）；除法本身仍受 evaluateArithmetic 的
    // DIVISION_SCALE(12) 位中间精度约束（12 个 3），而不是无限精度、也不再是旧的 4 位。
    const tokens = [num('1'), op('/'), num('3')];
    expect(evaluateExpression(tokens, {})).toBe('0.333333333333');
  });

  it('large numbers maintain precision', () => {
    const tokens = [field('A'), op('*'), field('B')];
    expectLegacyDecimal(evaluateExpression(tokens, { A: '999999.99', B: '100' }), '99999999');
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
    expectLegacyDecimal(evaluateExpression(tokens, {}, subtotals), '1500');
  });

  it('missing component subtotal defaults to 0', () => {
    const tokens = [compSub('COMP-001', '投料金额'), op('+'), num('100')];
    expectLegacyDecimal(evaluateExpression(tokens, {}, {}), '100');
  });

  it('mixed: field + component subtotal', () => {
    const tokens = [field('手工费'), op('+'), compSub('COMP-001', '投料金额')];
    expectLegacyDecimal(evaluateExpression(tokens, { 手工费: 200 }, { 'COMP-001': 800 }), '1000');
  });
});

// ─── Product attribute tokens ───────────────────────────────────────────────

describe('formulaEngine - product_attribute tokens', () => {
  it('references product attribute', () => {
    const tokens = [prodAttr('数量'), op('*'), field('单价')];
    expectLegacyDecimal(evaluateExpression(tokens, { 单价: 50 }, {}, { 数量: 10 }), '500');
  });

  it('missing product attribute defaults to 0', () => {
    const tokens = [prodAttr('数量'), op('*'), num('100')];
    expectLegacyDecimal(evaluateExpression(tokens, {}, {}, {}), '0');
  });
});

// ─── Complex real-world formulas ────────────────────────────────────────────

describe('formulaEngine - real-world scenarios', () => {
  it('投料小计: 单价 × 数量 (PRD standard)', () => {
    const tokens = [field('单价'), op('×'), field('数量')];
    expectLegacyDecimal(evaluateExpression(tokens, { 单价: 45, 数量: 5 }), '225');
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
    expectLegacyDecimal(evaluateExpression(
      tokens,
      {},
      { 'COMP-001': 100, 'COMP-002': 50 },
      { 数量: 3 },
    ), '450');
  });

  it('discount formula: 原始总价 × 折扣率 / 100', () => {
    const tokens = [field('原始总价'), op('*'), field('折扣率'), op('/'), num('100')];
    expectLegacyDecimal(evaluateExpression(tokens, { 原始总价: 10000, 折扣率: 85 }), '8500');
  });

  it('empty expression → returns 0', () => {
    expectLegacyDecimal(evaluateExpression([], {}), '0');
  });
});

// ─── Edge cases and error handling ──────────────────────────────────────────

describe('formulaEngine - edge cases', () => {
  it('single field token (no operator)', () => {
    const tokens = [field('A')];
    expectLegacyDecimal(evaluateExpression(tokens, { A: 42 }), '42');
  });

  it('single number token', () => {
    const tokens = [num('99.5')];
    expectLegacyDecimal(evaluateExpression(tokens, {}), '99.5');
  });

  it('malformed expression (operator only) → returns 0', () => {
    const tokens = [op('+')];
    expectLegacyDecimal(evaluateExpression(tokens, {}), '0');
  });

  it('unmatched brackets → returns 0', () => {
    const tokens = [open, field('A'), op('+'), field('B')];
    // Missing close bracket — Function constructor may handle it or throw
    const result = evaluateExpression(tokens, { A: 1, B: 2 });
    // Should gracefully return 0 on error
    expect(typeof result).toBe('string');
  });

  it('negative result', () => {
    const tokens = [field('A'), op('-'), field('B')];
    expectLegacyDecimal(evaluateExpression(tokens, { A: 10, B: 30 }), '-20');
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
  ): DecimalString {
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
    expectLegacyDecimal(evalCrossTab([tokenNone], { 子件: 'P1' }, { A: rows2 }), '0.8');
  });

  it('NONE — zero match → 0', () => {
    expectLegacyDecimal(evalCrossTab([tokenNone], { 子件: 'P9' }, { A: aRows }), '0');
  });

  it('NONE — multi match → 0 (error swallowed by outer try/catch)', () => {
    // aRows has two P1 rows → multi match → throws → caught → 0
    expectLegacyDecimal(evalCrossTab([tokenNone], { 子件: 'P1' }, { A: aRows }), '0');
  });

  it('SUM — multi match sums values: 0.8 + 0.5 = 1.3', () => {
    const token: ExpressionToken = { ...tokenNone, agg: 'SUM' };
    expectLegacyDecimal(evalCrossTab([token], { 子件: 'P1' }, { A: aRows }), '1.3');
  });

  it('COUNT — counts matching rows: 2', () => {
    const token: ExpressionToken = { ...tokenNone, agg: 'COUNT' };
    expectLegacyDecimal(evalCrossTab([token], { 子件: 'P1' }, { A: aRows }), '2');
  });

  it('AVG — two matching values (0.8 + 0.5) / 2 = 0.65', () => {
    const token: ExpressionToken = { ...tokenNone, agg: 'AVG' };
    expectLegacyDecimal(evalCrossTab([token], { 子件: 'P1' }, { A: aRows }), '0.65');
  });

  it('MAX — returns larger value 0.8', () => {
    const token: ExpressionToken = { ...tokenNone, agg: 'MAX' };
    expectLegacyDecimal(evalCrossTab([token], { 子件: 'P1' }, { A: aRows }), '0.8');
  });

  it('MIN — returns smaller value 0.5', () => {
    const token: ExpressionToken = { ...tokenNone, agg: 'MIN' };
    expectLegacyDecimal(evalCrossTab([token], { 子件: 'P1' }, { A: aRows }), '0.5');
  });

  it('null/blank match key on currentRow → no match → 0', () => {
    expectLegacyDecimal(evalCrossTab([tokenNone], { 子件: '' }, { A: aRows }), '0');
  });

  it('null match key on aRow → that row excluded', () => {
    const rowsWithNull = [{ 子件: null, 单重: 0.8 }, { 子件: 'P2', 单重: 0.3 }];
    expectLegacyDecimal(evalCrossTab([tokenNone], { 子件: 'P1' }, { A: rowsWithNull }), '0');
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
    expectLegacyDecimal(evalCrossTab([tokenAnd], { 子件: 'P1', 类型: 'X' }, { A: rowsAnd }), '1');
  });

  it('SUM — non-numeric target value → error path → returns 0', () => {
    // 匹配行的 target 字段为字符串 'abc'(非数字)→ nums.some(n === null) → crossTabError → 0
    const rowsNonNumeric = [{ 子件: 'P1', 单重: 'abc' }, { 子件: 'P2', 单重: 0.3 }];
    const tokenSum: ExpressionToken = { ...tokenNone, agg: 'SUM' };
    expectLegacyDecimal(evalCrossTab([tokenSum], { 子件: 'P1' }, { A: rowsNonNumeric }), '0');
  });

  it('SUM — whitespace-only match key on aRow → row excluded → SUM returns 0 (Fix 1 验证)', () => {
    // aRow.子件 = '   '(全空白)→ blank() = true → 该行被排除 → 无命中 → SUM = 0
    const rowsWhitespace = [{ 子件: '   ', 单重: 5 }, { 子件: 'P2', 单重: 0.3 }];
    const tokenSum: ExpressionToken = { ...tokenNone, agg: 'SUM' };
    expectLegacyDecimal(evalCrossTab([tokenSum], { 子件: '   ' }, { A: rowsWhitespace }), '0');
  });

  it('targetExpr NONE A.单价*本.数量', () => {
    const aRows = [{ 子件: 'P1', 单价: 2 }];
    const tok = [{ type: 'cross_tab_ref', source: 'A', agg: 'NONE',
      match: [{ a: '子件', b: '子件' }],
      targetExpr: [{ type: 'field', value: '单价' }, { type: 'operator', value: '*' }, { type: 'b_field', value: '数量' }] }];
    const r = evaluateExpression(tok as any, {}, undefined, undefined, undefined, undefined, undefined,
      undefined, undefined, undefined, { 子件: 'P1', 数量: 3 }, { A: aRows });
    expectLegacyDecimal(r, '6');
  });
  it('targetExpr SUM per-row then aggregate', () => {
    const aRows = [{ 子件: 'P1', 单价: 2, 数量: 3 }, { 子件: 'P1', 单价: 4, 数量: 1 }];
    const tok = [{ type: 'cross_tab_ref', source: 'A', agg: 'SUM',
      match: [{ a: '子件', b: '子件' }],
      targetExpr: [{ type: 'field', value: '单价' }, { type: 'operator', value: '*' }, { type: 'field', value: '数量' }] }];
    const r = evaluateExpression(tok as any, {}, undefined, undefined, undefined, undefined, undefined,
      undefined, undefined, undefined, { 子件: 'P1' }, { A: aRows });
    expectLegacyDecimal(r, '10');
  });

  // ─── outDiag 错误旁路 (细项多命中渲染 ⚠) ─────────────────────────────────
  it('NONE — multi match → 0 且 outDiag.crossTabError 被写入', () => {
    // aRows 含两个 P1 行 → NONE 多命中 → crossTabError → 数值仍 0,但 outDiag 透出原因
    const outDiag: { crossTabError?: string } = {};
    const r = evaluateExpression(
      [tokenNone],
      {},        // fieldValues
      undefined, // componentSubtotals
      undefined, // productAttributes
      undefined, // quotationFields
      undefined, // pathCache
      undefined, // partNo
      undefined, // basicDataValues
      undefined, // previousRowSubtotal
      undefined, // globalVariableDefs
      { 子件: 'P1' }, // currentRow
      { A: aRows },    // crossTabRows
      outDiag,         // ← NEW trailing param
    );
    expectLegacyDecimal(r, '0');
    expect(outDiag.crossTabError).toBeTruthy();
  });

  it('SUM — non-numeric target → 0 且 outDiag.crossTabError 被写入', () => {
    const rowsNonNumeric = [{ 子件: 'P1', 单重: 'abc' }, { 子件: 'P2', 单重: 0.3 }];
    const tokenSum: ExpressionToken = { ...tokenNone, agg: 'SUM' };
    const outDiag: { crossTabError?: string } = {};
    const r = evaluateExpression(
      [tokenSum], {}, undefined, undefined, undefined, undefined, undefined,
      undefined, undefined, undefined, { 子件: 'P1' }, { A: rowsNonNumeric }, outDiag,
    );
    expectLegacyDecimal(r, '0');
    expect(outDiag.crossTabError).toBeTruthy();
  });

  it('NONE — single match → outDiag.crossTabError 不被写入', () => {
    const rows2 = [{ 子件: 'P1', 单重: 0.8 }, { 子件: 'P2', 单重: 0.3 }];
    const outDiag: { crossTabError?: string } = {};
    const r = evaluateExpression(
      [tokenNone], {}, undefined, undefined, undefined, undefined, undefined,
      undefined, undefined, undefined, { 子件: 'P1' }, { A: rows2 }, outDiag,
    );
    expectLegacyDecimal(r, '0.8');
    expect(outDiag.crossTabError).toBeUndefined();
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
 *
 * Fixture fields:
 *   name         - test name (required)
 *   token        - single ExpressionToken (legacy; used when tokens absent)
 *   tokens       - ExpressionToken[] array (optional; overrides [token])
 *   aRows        - rows for source "A" (legacy; used when crossTabRows absent)
 *   crossTabRows - Record<source, rows[]> (optional; overrides {A: aRows})
 *   currentRow   - current row values (optional)
 *   expected     - expected numeric result (required)
 *   expectError  - if true: assert result===0 only (no crossTabError check at fixture level)
 */
describe('cross-tab fixture', () => {
  for (const c of crossTabCases) {
    const caseName = (c as any).name as string;
    const tokenRaw = (c as any).token as ExpressionToken | undefined;
    const tokensRaw = (c as any).tokens as ExpressionToken[] | undefined;
    const currentRow = (c as any).currentRow as Record<string, any> | undefined;
    const aRows = (c as any).aRows as Array<Record<string, any>> | undefined;
    const crossTabRowsRaw = (c as any).crossTabRows as Record<string, Array<Record<string, any>>> | undefined;
    const quotationFieldsRaw = (c as any).quotationFields as Record<string, number> | undefined;
    const componentSubtotalsRaw = (c as any).componentSubtotals as Record<string, number> | undefined;
    // repair-0803：宿主已算字段值（b_field 在 currentRow 键缺失时的回落来源）。
    // 后端 FormulaCalculatorCrossTabFixtureTest 消费同一字段填 ctx.hostFieldValues —— 这是 AC-2 对拍锚点。
    const hostFieldValuesRaw = (c as any).hostFieldValues as Record<string, number> | undefined;
    const expected = (c as any).expected as number;
    const expectError = (c as any).expectError as boolean | undefined;

    // Resolve tokens array: explicit tokens[] overrides [token]
    const resolvedTokens: ExpressionToken[] = tokensRaw
      ? tokensRaw
      : tokenRaw
        ? [tokenRaw]
        : [];

    // Resolve crossTabRows: explicit map overrides {A: aRows}
    const resolvedCrossTabRows: Record<string, Array<Record<string, any>>> = crossTabRowsRaw
      ? crossTabRowsRaw
      : { A: aRows ?? [] };

    it(caseName, () => {
      const result = evaluateExpression(
        resolvedTokens,
        {},                         // fieldValues
        componentSubtotalsRaw,      // componentSubtotals (optional)
        undefined,                  // productAttributes
        quotationFieldsRaw,         // quotationFields (optional)
        undefined, // pathCache
        undefined, // partNo
        undefined, // basicDataValues
        undefined, // previousRowSubtotal
        undefined, // globalVariableDefs
        currentRow,
        resolvedCrossTabRows,
        undefined, // outDiag
        undefined, // treeCtx
        hostFieldValuesRaw, // repair-0803：宿主已算字段值（b_field 回落来源）
      );
      if (expectError) {
        // Error-path cases: result collapses to 0 (crossTabError check is engine-level, not fixture-level)
        expectLegacyDecimal(result, '0');
      } else {
        expectLegacyDecimal(result, expected);
      }
    });
  }
});

// ─── T5 前端引擎 KSUM ──────────────────────────────────────────────────────
//
// 测试场景:
//   元素组件 (ELEM_ID): 2 行 [{料件:'Cu', 单价:2}, {料件:'Ni', 单价:3}]
//   外购件组件 (WGJ_ID): 2 行 [{来料:'X', 费用:1.0}, {来料:'X', 费用:0.5}]
//
// 公式: SUM([元素.单价] + KSUM([外购件.费用]))
//   外层 match=[] → hits = 所有元素行
//   对每行 evalRow(ar): 单价 + KSUM_scalar
//     KSUM_scalar: source=WGJ_ID, projectToHostKey=true, match=[], SUM(费用) → 1.0+0.5=1.5
//   Cu行: 2+1.5=3.5 ; Ni行: 3+1.5=4.5 ; 外层 SUM=8
//
// I-1 决策 K: KSUM 空集 → 0 (静默, 无 crossTabError)
//   把 WGJ_ID 行清空 → KSUM_scalar=0 → Cu:2+0=2, Ni:3+0=3 → SUM=5
//
// I-2: KAVG 空集 → 整外层表达式塌 0 + crossTabError 非空

describe('T5 前端引擎 KSUM', () => {
  const ELEM_ID = 'elem-comp-id';
  const WGJ_ID = 'wgj-comp-id';

  const elemRows = [
    { 料件: 'Cu', 单价: 2 },
    { 料件: 'Ni', 单价: 3 },
  ];
  const wgjRows = [
    { 来料: 'X', 费用: 1.0 },
    { 来料: 'X', 费用: 0.5 },
  ];

  // KSUM 子 token: projectToHostKey=true, match=[]（无约束→全量塌缩）
  const ksumSubToken: any = {
    type: 'cross_tab_ref',
    projectToHostKey: true,
    source: WGJ_ID,
    sourceLabel: '外购件',
    agg: 'SUM',
    match: [],
    targetExpr: [{ type: 'field', value: '费用' }],
  };

  // 外层 SUM([元素.单价] + KSUM([外购件.费用]))
  // targetExpr: [field 单价] + [KSUM 子 token]
  const outerToken: any = {
    type: 'cross_tab_ref',
    source: ELEM_ID,
    sourceLabel: '元素',
    agg: 'SUM',
    match: [],
    targetExpr: [
      { type: 'field', value: '单价' },
      { type: 'operator', value: '+' },
      ksumSubToken,
    ],
  };

  // KAVG 变体（空集→整外层塌 0）
  const kavgSubToken: any = {
    ...ksumSubToken,
    agg: 'AVG',
  };
  const outerTokenKavg: any = {
    ...outerToken,
    targetExpr: [
      { type: 'field', value: '单价' },
      { type: 'operator', value: '+' },
      kavgSubToken,
    ],
  };

  function evalKsum(
    token: any,
    crossTabRows: Record<string, Array<Record<string, any>>>,
    outDiag?: { crossTabError?: string },
  ): DecimalString {
    return evaluateExpression(
      [token] as any,
      {},        // fieldValues
      undefined, // componentSubtotals
      undefined, // productAttributes
      undefined, // quotationFields
      undefined, // pathCache
      undefined, // partNo
      undefined, // basicDataValues
      undefined, // previousRowSubtotal
      undefined, // globalVariableDefs
      undefined, // currentRow (外层 match=[] 时不需要 join)
      crossTabRows,
      outDiag,
    );
  }

  it('KSUM 按宿主键塌缩 = Σ费用=1.5, 广播进每元素驱动行 → (2+1.5)+(3+1.5)=8', () => {
    const result = evalKsum(outerToken, { [ELEM_ID]: elemRows, [WGJ_ID]: wgjRows });
    expectLegacyDecimal(result, '8');
  });

  it('决策 K: KSUM 空集(WGJ 无行) → scalar=0 静默, 无 crossTabError → (2+0)+(3+0)=5', () => {
    const diag: { crossTabError?: string } = {};
    const result = evalKsum(outerToken, { [ELEM_ID]: elemRows, [WGJ_ID]: [] }, diag);
    expectLegacyDecimal(result, '5');
    expect(diag.crossTabError).toBeUndefined();
  });

  it('决策 K + I-2: KAVG 空集 → 整外层表达式塌 0 + crossTabError 非空', () => {
    const diag: { crossTabError?: string } = {};
    const result = evalKsum(outerTokenKavg, { [ELEM_ID]: elemRows, [WGJ_ID]: [] }, diag);
    expectLegacyDecimal(result, '0');
    expect(diag.crossTabError).toBeTruthy();
  });
});

// ─── 多 source 广播（§4.3 token.sources 求值）────────────────────────────────
//
// 场景: 公式 SUM([元素.单价] + [来料.组成用量])
//   驱动 = 元素组件(ELEM_ID), 2 行: [{料件:'A', 单价:2}, {料件:'A', 单价:3}]
//   更粗 source = 来料组件(MAT_ID), 按料件与驱动行匹配
//   token.sources = [
//     { source: ELEM_ID, match: [{a:'料件', b:'料件'}] },   // sources[0] = 驱动
//     { source: MAT_ID,  match: [{a:'料件', b:'料件'}] },   // sources[1] = 更粗
//   ]
//   targetExpr = [field('单价'), op('+'), field('组成用量')]
//   (单价来自驱动行 ar, 组成用量来自来料广播行)
//
// 期望:
//   来料 1 行(料件='A', 组成用量=10) → 广播合并
//   驱动行0: {单价:2} + 广播{组成用量:10} = 12
//   驱动行1: {单价:3} + 广播{组成用量:10} = 13
//   SUM = 25

describe('多 source 广播 (token.sources §4.3)', () => {
  const ELEM_ID = 'elem-id';
  const MAT_ID = 'mat-id';

  // 构造带 sources 的外层 SUM token
  const makeMultiSrcToken = (
    elemRows: Array<Record<string, any>>,
    matRows: Array<Record<string, any>>,
  ) => {
    const token: ExpressionToken = {
      type: 'cross_tab_ref',
      source: ELEM_ID,  // 镜像 sources[0]
      agg: 'SUM',
      match: [],  // 外层 match=[] → hits = 所有驱动行
      targetExpr: [
        { type: 'field', value: '单价' },
        { type: 'operator', value: '+' },
        { type: 'field', value: '组成用量' },
      ],
      sources: [
        { source: ELEM_ID, match: [{ a: '料件', b: '料件' }] },  // sources[0] = 驱动
        { source: MAT_ID,  match: [{ a: '料件', b: '料件' }] },  // sources[1] = 更粗
      ],
    };
    return { token, crossTabRows: { [ELEM_ID]: elemRows, [MAT_ID]: matRows } as Record<string, Array<Record<string, any>>> };
  };

  function evalMultiSrc(
    token: ExpressionToken,
    crossTabRows: Record<string, Array<Record<string, any>>>,
    outDiag?: { crossTabError?: string },
  ): DecimalString {
    return evaluateExpression(
      [token],
      {},
      undefined, undefined, undefined, undefined, undefined,
      undefined, undefined, undefined,
      undefined,  // currentRow（外层 match=[] 时不需要）
      crossTabRows,
      outDiag,
    );
  }

  it('多 source 链: SUM([元素.单价] + [来料.组成用量]) 驱动=元素, 来料更粗按料件广播', () => {
    // 驱动 2 行 + 来料 1 行(料件=A 命中两驱动行)
    const elemRows = [
      { 料件: 'A', 单价: 2 },
      { 料件: 'A', 单价: 3 },
    ];
    const matRows = [
      { 料件: 'A', 组成用量: 10 },
    ];
    const { token, crossTabRows } = makeMultiSrcToken(elemRows, matRows);
    // 驱动行0: 单价=2 + 广播 组成用量=10 = 12
    // 驱动行1: 单价=3 + 广播 组成用量=10 = 13
    // SUM = 25
    expectLegacyDecimal(evalMultiSrc(token, crossTabRows), '25');
  });

  it('多 source: 粗 source 0 命中 → 该项=0 (不报 crossTabError)', () => {
    // 来料无与驱动行(料件=A)匹配的行
    const elemRows = [
      { 料件: 'A', 单价: 2 },
      { 料件: 'A', 单价: 3 },
    ];
    const matRows: Array<Record<string, any>> = [];  // 空 → 0 命中
    const { token, crossTabRows } = makeMultiSrcToken(elemRows, matRows);
    const outDiag: { crossTabError?: string } = {};
    // 组成用量不注入 → aFieldValues.组成用量 = 0
    // 驱动行0: 2 + 0 = 2 ; 驱动行1: 3 + 0 = 3 ; SUM = 5
    expectLegacyDecimal(evalMultiSrc(token, crossTabRows, outDiag), '5');
    expect(outDiag.crossTabError).toBeUndefined();
  });

  it('多 source: 粗 source >1 命中 → multiMatchErr → 整项塌 0 + crossTabError', () => {
    // 来料 2 行同料件='A' → 粗 source 多命中 → multiSrcHitErr=true → multiMatchErr=true → 塌 0
    const elemRows = [
      { 料件: 'A', 单价: 2 },
    ];
    const matRows = [
      { 料件: 'A', 组成用量: 10 },
      { 料件: 'A', 组成用量: 20 },  // 重复行 → 多命中
    ];
    const { token, crossTabRows } = makeMultiSrcToken(elemRows, matRows);
    const outDiag: { crossTabError?: string } = {};
    expectLegacyDecimal(evalMultiSrc(token, crossTabRows, outDiag), '0');
    expect(outDiag.crossTabError).toBeTruthy();
  });
});

// ─── isWithinTolerance ──────────────────────────────────────────────────────

describe('isWithinTolerance', () => {
  it('identical values', () => {
    expect(isWithinTolerance(100, 100)).toBe(true);
  });

  it('uses the 12-place working-value tolerance', () => {
    expect(isWithinToleranceDecimal('100.000000000001', '100')).toBe(true);
  });

  it('exceeds default tolerance', () => {
    expect(isWithinToleranceDecimal('100.000000000002', '100')).toBe(false);
  });

  it('custom tolerance', () => {
    expect(isWithinTolerance(100, 100.5, 1)).toBe(true);
    expect(isWithinTolerance(100, 101.5, 1)).toBe(false);
  });
});
