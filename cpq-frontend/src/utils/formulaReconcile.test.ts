import { describe, it, expect } from 'vitest';
import { evaluateExpression } from './formulaEngine';
import type { ExpressionToken } from './formulaEngine';
import fixture from './__fixtures__/formula-reconcile-cases.json';

/**
 * Phase4 Task6 — 公式引擎防漂移红线（前端侧）。
 *
 * 唯一权威样本 `__fixtures__/formula-reconcile-cases.json` 由前端 formulaEngine 与
 * 后端 FormulaCalculator(SnapshotReconcileTest#reconcileFixture) 同读：两侧对同一组 token+输入
 * 必须各自算出相同的 4 位 HALF_UP 结果。任一引擎漂移 → 一侧测试变红。
 *
 * <p>注：前端 evaluateExpression 返回原始浮点（不四舍五入），后端 setScale(4, HALF_UP)。
 * 故此处把前端结果四舍五入到 4 位再比对（与后端落库的 formulaResults 同口径）。
 */

interface Case {
  name: string;
  tokens: ExpressionToken[];
  fieldValues?: Record<string, number>;
  componentSubtotals?: Record<string, number>;
  productAttributes?: Record<string, number>;
  quotationFields?: Record<string, number>;
  basicDataValues?: Record<string, any>;
  previousRowSubtotal?: number | null;
  expected: number;
}

function round4(n: number): number {
  // 已记录的刻意微偏离（Phase2 Task2）：前端 evaluateExpression 原始返回对除零=Infinity、
  // 异常=NaN，而后端 FormulaCalculator 在 setScale 前 isInfinite/isNaN → 归 0。
  // 二者的「有效落库/显示值」一致(均 0；且 card 渲染读后端 formulaResults)，故此处把非有限值归 0 后比对，
  // 既编码该既定契约，又仍能捕获任何「有限数值层面」的真实漂移。
  if (!Number.isFinite(n)) return 0;
  // HALF_UP 到 4 位（与后端 BigDecimal.setScale(4, HALF_UP) 同口径）
  return Math.round((n + Number.EPSILON) * 10000) / 10000;
}

describe('Phase4 Task6 — formulaEngine 与后端 FormulaCalculator 逐分对账(共享样本)', () => {
  const cases = (fixture as { cases: Case[] }).cases;

  it('样本非空', () => {
    expect(cases.length).toBeGreaterThan(0);
  });

  for (const c of cases) {
    it(`reconcile: ${c.name} → ${c.expected}`, () => {
      const raw = evaluateExpression(
        c.tokens,
        c.fieldValues ?? {},
        c.componentSubtotals ?? {},
        c.productAttributes ?? {},
        c.quotationFields ?? {},
        undefined, // pathCache
        undefined, // partNo
        c.basicDataValues ?? {},
        c.previousRowSubtotal == null ? undefined : c.previousRowSubtotal,
      );
      expect(round4(raw)).toBe(c.expected);
    });
  }
});
