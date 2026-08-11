import { describe, expect, it } from 'vitest';
import { evaluateExpression, type DecimalContext, type ExpressionToken } from './formulaEngine';
import fixtureJson from './__fixtures__/formula-reconcile-cases.json?raw';
import { parseSnapshotJsonLossless } from './losslessJson';
import type { DecimalString } from './precision';

interface Case {
  name: string;
  tokens: ExpressionToken[];
  fieldValues?: DecimalContext;
  componentSubtotals?: DecimalContext;
  productAttributes?: DecimalContext;
  quotationFields?: DecimalContext;
  basicDataValues?: Record<string, unknown>;
  previousRowSubtotal?: DecimalString | null;
  expected: DecimalString;
}

describe('formula engine and backend calculator shared reconciliation fixture', () => {
  const cases = parseSnapshotJsonLossless<{ cases: Case[] }>(fixtureJson).cases;

  it('contains reconciliation cases', () => {
    expect(cases.length).toBeGreaterThan(0);
  });

  for (const testCase of cases) {
    it(`reconcile: ${testCase.name} -> ${testCase.expected}`, () => {
      const result = evaluateExpression(
        testCase.tokens,
        testCase.fieldValues ?? {},
        testCase.componentSubtotals ?? {},
        testCase.productAttributes ?? {},
        testCase.quotationFields ?? {},
        undefined,
        undefined,
        testCase.basicDataValues ?? {},
        testCase.previousRowSubtotal ?? undefined,
      );

      expect(result).toBe(testCase.expected);
    });
  }
});
