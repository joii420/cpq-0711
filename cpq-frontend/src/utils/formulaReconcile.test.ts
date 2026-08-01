import { describe, it, expect } from 'vitest';
import { evaluateExpression } from './formulaEngine';
import type { ExpressionToken } from './formulaEngine';
import fixture from './__fixtures__/formula-reconcile-cases.json';

/**
 * Phase4 Task6 — 公式引擎防漂移红线（前端侧）。
 *
 * 唯一权威样本 `__fixtures__/formula-reconcile-cases.json` 由前端 formulaEngine 与
 * 后端 FormulaCalculator(SnapshotReconcileTest#reconcileFixture) 同读：两侧对同一组 token+输入
 * 必须各自算出相同的结果。任一引擎漂移 → 一侧测试变红。
 *
 * task-0801（2026-08-01）口径变更：evaluateExpression 内部不再 toDecimalPlaces(4) 中途截断
 * （见 formulaEngine.ts:584 注释），fixture 的 `expected` 字段随之改为**原始十进制精确值**
 * （除法受 DIVISION_SCALE=12 中间精度约束，如 1/3 → 0.333333333333），不再是"4 位 HALF_UP 后的值"。
 * 原先此处额外做 `round4()` 把前端结果砍到 4 位再比对——这一步在新口径下已是错误的比较基准，
 * 会把 fixture 里刻意保留的 12 位除法精度砍掉后再比对，导致本应通过的用例误判为失败。
 * 故直接比较原始值（不四舍五入），保留 Infinity/NaN → 0 的既定跨引擎契约不变。
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

function reconcileValue(n: number): number {
  // 已记录的刻意微偏离（Phase2 Task2）：前端 evaluateExpression 原始返回对除零=Infinity、
  // 异常=NaN，而后端 FormulaCalculator 在归一前 isInfinite/isNaN → 归 0（task-0801 起
  // evaluateArithmetic 已把除以 0 收敛为确定性 0，此分支现主要防御性保留，理论上不会再触发 Infinity）。
  // 二者的「有效落库/显示值」一致(均 0；且 card 渲染读后端 formulaResults)，故此处把非有限值归 0 后比对，
  // 既编码该既定契约，又仍能捕获任何「有限数值层面」的真实漂移。
  return Number.isFinite(n) ? n : 0;
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
      // toBeCloseTo(…, 9)：容许 JS number ↔ decimal.js 往返转换的末位浮点噪声（远小于本任务
      // 关心的精度量级），同时仍能捕获任何有意义的（4~9 位小数级）真实漂移。
      expect(reconcileValue(raw)).toBeCloseTo(c.expected, 9);
    });
  }
});
