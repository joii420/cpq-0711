import { beforeEach, describe, expect, it, vi } from 'vitest';
import api from './api';
import { quotationService } from './quotationService';

vi.mock('./api', () => ({
  default: {
    post: vi.fn(),
    put: vi.fn(),
  },
}));

const exact = '98765431.123456789012';

describe('quotationService decimal request boundaries', () => {
  beforeEach(() => {
    vi.mocked(api.post).mockReset();
    vi.mocked(api.put).mockReset();
    vi.mocked(api.post).mockResolvedValue({});
    vi.mocked(api.put).mockResolvedValue({});
  });

  it('preserves draft decimal strings and allows annualVolume as a structural integer', () => {
    quotationService.saveDraft('Q-1', {
      lineItems: [{ subtotal: exact, annualVolume: 800000, sortOrder: 0 }],
      totalAmount: exact,
    });

    const payload = vi.mocked(api.put).mock.calls[0][1] as any;
    expect(payload.lineItems[0].subtotal).toBe(exact);
    expect(payload.lineItems[0].annualVolume).toBe(800000);
    expect(typeof payload.totalAmount).toBe('string');
  });

  it('rejects a precision JS number nested in SaveDraft before calling api', () => {
    expect(() => quotationService.saveDraft('Q-1', {
      lineItems: [{ subtotal: 98765431.12345679, annualVolume: 800000 }],
    })).toThrow(/decimal strings, not JS numbers/);
    expect(api.put).not.toHaveBeenCalled();
  });

  it('keeps quote-card edit and Excel cell values string-only', () => {
    quotationService.editQuoteCardValue('L-1', {
      componentId: 'C-1', rowKey: 'R-1', fieldName: 'amount', value: exact,
    });
    quotationService.updateExcelViewCell('Q-1', { rowIndex: 0, colKey: 'amount', value: exact });

    expect((vi.mocked(api.put).mock.calls[0][1] as any).value).toBe(exact);
    expect((vi.mocked(api.put).mock.calls[1][1] as any).value).toBe(exact);

    expect(() => quotationService.editQuoteCardValue('L-1', {
      componentId: 'C-1', rowKey: 'R-1', fieldName: 'amount', value: 1.25,
    } as any)).toThrow(/decimal strings, not JS numbers/);
  });

  it('allows protocol row_index while preserving reconciliation decimal strings', () => {
    const base = {
      reconciledAt: '2026-08-10T00:00:00Z',
      diffs: [{
        componentId: 'C-1', tabName: 'Material', rowKey: 'R-1', fieldName: 'amount',
        frontendValue: exact, backendValue: exact,
        frontendInputs: { row_index: 0, amount: exact },
        backendInputs: { row_index: 0, amount: exact },
      }],
    };
    quotationService.reconcileReport('L-1', base as any);
    const payload = vi.mocked(api.post).mock.calls[0][1] as any;
    expect(payload.diffs[0].frontendInputs.row_index).toBe(0);
    expect(payload.diffs[0].frontendInputs.amount).toBe(exact);
    expect(payload.diffs[0].frontendValue).toBe(exact);
  });

  it('still rejects precision numbers beside an allowed row_index', () => {
    const invalid = {
      reconciledAt: '2026-08-10T00:00:00Z',
      diffs: [{
        componentId: 'C-1', tabName: 'Material', rowKey: 'R-1', fieldName: 'amount',
        frontendValue: exact, backendValue: exact,
        frontendInputs: { row_index: 0, precision_input: 1 },
        backendInputs: { row_index: 0, precision_input: exact },
      }],
    };

    expect(() => quotationService.reconcileReport('L-1', invalid as any)).toThrow(/decimal strings, not JS numbers/);
    expect(api.post).not.toHaveBeenCalled();
  });

  it('requests HTML with POST query parameters and a text response', () => {
    quotationService.exportHtml('Q-1', {
      showDiscount: true,
      showProcesses: false,
      showTabDetails: true,
    });

    expect(api.post).toHaveBeenCalledWith('/quotations/Q-1/export/html', null, {
      params: { showDiscount: true, showProcesses: false, showTabDetails: true },
      responseType: 'text',
    });
  });
});
