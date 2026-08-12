import { describe, expect, it } from 'vitest';
import { applyFormulaResultsToDraftRow, toDraftComponentSubtotal } from './draftPrecision';

function serializeComponent(subtotal: unknown, rowData: Record<string, unknown>) {
  return JSON.parse(JSON.stringify({
    subtotal: toDraftComponentSubtotal(subtotal),
    rowData: JSON.stringify(rowData),
  })) as { subtotal: unknown; rowData: string };
}

describe('draft save precision boundary', () => {
  it.each([null, ''])('serializes an empty component subtotal as decimal string zero: %s', (subtotal) => {
    const payload = serializeComponent(subtotal, {});
    expect(payload.subtotal).toBe('0');
    expect(typeof payload.subtotal).toBe('string');
  });

  it('keeps INPUT_NUMBER spelling and rounds only FORMULA/*_FORMULA results', () => {
    const row = applyFormulaResultsToDraftRow(
      { quantity: '1.2300', label: '001.2300', formula: 'stale', listFormula: 'stale' },
      [
        { name: 'quantity', field_type: 'INPUT_NUMBER' },
        { name: 'label', fieldType: 'INPUT_TEXT' },
        { name: 'formula', field_type: 'FORMULA' },
        { name: 'listFormula', fieldType: 'LIST_FORMULA' },
      ],
      {
        quantity: '9.999999999999',
        formula: '1.2345678905',
        listFormula: '-1.2345678905',
      },
    );
    const payload = serializeComponent(null, row);
    const savedRow = JSON.parse(payload.rowData) as Record<string, unknown>;

    expect(savedRow.quantity).toBe('1.2300');
    expect(savedRow.label).toBe('001.2300');
    expect(savedRow.formula).toBe('1.234567891');
    expect(savedRow.listFormula).toBe('-1.234567891');
  });
});
