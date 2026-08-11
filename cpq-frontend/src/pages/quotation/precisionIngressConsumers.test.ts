import { describe, expect, it } from 'vitest';
import { buildSnapshotExpansions } from './QuotationStep2';
import { parseExcelSnapshotRows } from './useExcelSnapshotRows';
import { formatPathValue as formatComponentPathValue } from './components/formatPathValue';
import { formatPathValue as formatLinkedPathValue } from './useLinkedExcelRows';

const exact = '98765431.123456789012';

function lineWithFivePrecisionCarriers() {
  const cardJson = `{"tabs":[{"componentId":"component-1","baseRows":[{"driverRow":{"amount":${exact}},"basicDataValues":{"$amount":${exact}}}]}]}`;
  const excelJson = `{"rows":[{"amount":${exact}}]}`;
  return {
    id: 'line-1',
    productPartNo: 'P-001',
    componentData: [{
      componentId: 'component-1',
      fields: [],
      rows: [],
      dataDriverPath: '$material',
    }],
    quoteCardValues: cardJson,
    costingCardValues: cardJson,
    quoteExcelValues: excelJson,
    costingExcelValues: excelJson,
  } as any;
}

describe('TC-025 precision carrier consumer entries', () => {
  it.each(['QUOTE', 'COSTING'] as const)('%s CardValues reaches expansion state without a JS number', (side) => {
    const expansions = buildSnapshotExpansions([lineWithFivePrecisionCarriers()], side);
    const expansion = Object.values(expansions)[0];

    expect(expansion.rows[0].driverRow.amount).toBe(exact);
    expect(expansion.rows[0].basicDataValues.$amount).toBe(exact);
    expect(typeof expansion.rows[0].driverRow.amount).toBe('string');
  });

  it.each(['QUOTE', 'COSTING'] as const)('%s ExcelValues reaches row state without a JS number', (side) => {
    const rows = parseExcelSnapshotRows({
      lineItems: [lineWithFivePrecisionCarriers()],
      side,
      parsedColumns: [{ col_key: 'amount' }] as any,
    });

    expect(rows[0].amount).toBe(exact);
    expect(typeof rows[0].amount).toBe('string');
  });

  it('parses BASIC_DATA jsonb losslessly in both display consumers', () => {
    const value = { type: 'jsonb', value: `{"amount":${exact}}` };
    expect(formatComponentPathValue(value)).toBe(`amount=${exact}`);
    expect(formatLinkedPathValue(value)).toBe(`amount=${exact}`);
  });
});
