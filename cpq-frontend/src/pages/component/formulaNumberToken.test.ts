import { describe, expect, it } from 'vitest';
import { createFormulaNumberToken } from './formulaNumberToken';

describe('formula number token precision', () => {
  it('preserves a 12-place large decimal literal exactly', () => {
    expect(createFormulaNumberToken('98765431.123456789012')).toEqual({
      type: 'number',
      value: '98765431.123456789012',
      label: '98765431.123456789012',
    });
  });

  it('rejects JS numbers before they enter a formula token', () => {
    expect(createFormulaNumberToken(98765431.12345679)).toBeNull();
  });
});
