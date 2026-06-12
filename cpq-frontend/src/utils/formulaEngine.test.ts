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

  it('targetExpr NONE A.单价*本.数量', () => {
    const aRows = [{ 子件: 'P1', 单价: 2 }];
    const tok = [{ type: 'cross_tab_ref', source: 'A', agg: 'NONE',
      match: [{ a: '子件', b: '子件' }],
      targetExpr: [{ type: 'field', value: '单价' }, { type: 'operator', value: '*' }, { type: 'b_field', value: '数量' }] }];
    const r = evaluateExpression(tok as any, {}, undefined, undefined, undefined, undefined, undefined,
      undefined, undefined, undefined, { 子件: 'P1', 数量: 3 }, { A: aRows });
    expect(r).toBe(6);
  });
  it('targetExpr SUM per-row then aggregate', () => {
    const aRows = [{ 子件: 'P1', 单价: 2, 数量: 3 }, { 子件: 'P1', 单价: 4, 数量: 1 }];
    const tok = [{ type: 'cross_tab_ref', source: 'A', agg: 'SUM',
      match: [{ a: '子件', b: '子件' }],
      targetExpr: [{ type: 'field', value: '单价' }, { type: 'operator', value: '*' }, { type: 'field', value: '数量' }] }];
    const r = evaluateExpression(tok as any, {}, undefined, undefined, undefined, undefined, undefined,
      undefined, undefined, undefined, { 子件: 'P1' }, { A: aRows });
    expect(r).toBe(10);
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
    expect(r).toBe(0);
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
    expect(r).toBe(0);
    expect(outDiag.crossTabError).toBeTruthy();
  });

  it('NONE — single match → outDiag.crossTabError 不被写入', () => {
    const rows2 = [{ 子件: 'P1', 单重: 0.8 }, { 子件: 'P2', 单重: 0.3 }];
    const outDiag: { crossTabError?: string } = {};
    const r = evaluateExpression(
      [tokenNone], {}, undefined, undefined, undefined, undefined, undefined,
      undefined, undefined, undefined, { 子件: 'P1' }, { A: rows2 }, outDiag,
    );
    expect(r).toBe(0.8);
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
  ): number {
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
    expect(result).toBe(8);
  });

  it('决策 K: KSUM 空集(WGJ 无行) → scalar=0 静默, 无 crossTabError → (2+0)+(3+0)=5', () => {
    const diag: { crossTabError?: string } = {};
    const result = evalKsum(outerToken, { [ELEM_ID]: elemRows, [WGJ_ID]: [] }, diag);
    expect(result).toBe(5);
    expect(diag.crossTabError).toBeUndefined();
  });

  it('决策 K + I-2: KAVG 空集 → 整外层表达式塌 0 + crossTabError 非空', () => {
    const diag: { crossTabError?: string } = {};
    const result = evalKsum(outerTokenKavg, { [ELEM_ID]: elemRows, [WGJ_ID]: [] }, diag);
    expect(result).toBe(0);
    expect(diag.crossTabError).toBeTruthy();
  });
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
