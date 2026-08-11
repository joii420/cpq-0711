import { describe, expect, it } from 'vitest';
import { normalizePathFormulaResult, PrecisionIngressError } from './pathFormulaResult';

describe('path formula precision ingress', () => {
  it('preserves decimal strings and string-only nested results', () => {
    const result = normalizePathFormulaResult({ rows: [{ amount: '98765431.123456789012' }] }, 'path');
    expect(result).toEqual({ rows: [{ amount: '98765431.123456789012' }] });
  });

  it.each([
    98765431.12345679,
    { amount: 98765431.12345679 },
    [{ amount: '1.25' }, { amount: 2.5 }],
  ])('rejects a JS number before it can enter a path cache: %j', (value) => {
    expect(() => normalizePathFormulaResult(value, 'batchEvaluate(P-1::amount)'))
      .toThrow(PrecisionIngressError);
  });
});
