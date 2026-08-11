import { describe, expect, it } from 'vitest';
import type { SelDetailRow } from '../../../types/configure';
import {
  buildConfigurePartsRequest,
  normalizeQuantityInput,
  sumQuantity,
} from './configureRequest';

function row(quantity: string): SelDetailRow {
  return {
    rowId: `row-${quantity}`,
    recipeCode: 'R-1',
    recipeLabel: 'Recipe',
    elementOverrides: {},
    processNos: [],
    processLabels: [],
    quantity,
    unitWeightGrams: null,
  };
}

describe('configure quantity decimal-string boundary', () => {
  it('sums and classifies quantity with Decimal while preserving request strings', () => {
    const rows = [row('1'), row('9007199254740993')];
    const request = buildConfigurePartsRequest(rows, []);

    expect(sumQuantity(rows).toFixed()).toBe('9007199254740994');
    expect(request.productType).toBe('COMPOSITE');
    expect(request.parts.map((part) => part.quantity)).toEqual(['1', '9007199254740993']);
    expect(request.parts.every((part) => typeof part.quantity === 'string')).toBe(true);
  });

  it.each([
    ['2', '2'],
    ['0003', '3'],
    ['0', '1'],
    ['1.5', '1'],
    [null, '1'],
  ] as const)('normalizes positive integer input %s to %s', (input, expected) => {
    expect(normalizeQuantityInput(input)).toBe(expected);
  });
});
