import { beforeEach, describe, expect, it, vi } from 'vitest';
import api from './api';
import { comparisonExportService } from './comparisonExportService';
import type { ComparisonModel } from '../pages/quotation/comparisonModel';

vi.mock('./api', () => ({
  default: { post: vi.fn() },
}));

const model: ComparisonModel = {
  columns: [{ tag: 'TOTAL', label: '总价' }],
  rows: [{
    partNo: 'P-001',
    presence: 'BOTH',
    cells: {
      TOTAL: {
        quote: '98765431.123456789012',
        costing: '98765431.123456789011',
        highlighted: true,
      },
    },
  }],
};

describe('comparisonExportService', () => {
  beforeEach(() => {
    vi.mocked(api.post).mockReset();
    vi.mocked(api.post).mockResolvedValue(new Blob());
  });

  it('posts quote/costing precision values as exact decimal strings', async () => {
    await comparisonExportService.export('Q-001', model);

    expect(api.post).toHaveBeenCalledWith(
      '/quotations/Q-001/comparison/export',
      model,
      { responseType: 'blob' },
    );
    const payload = vi.mocked(api.post).mock.calls[0][1] as ComparisonModel;
    expect(payload.rows[0].cells.TOTAL.quote).toBe('98765431.123456789012');
    expect(typeof payload.rows[0].cells.TOTAL.quote).toBe('string');
  });

  it('rejects a JS number instead of sending a precision JSON number', async () => {
    const invalid = structuredClone(model) as any;
    invalid.rows[0].cells.TOTAL.quote = 123.45;

    expect(() => comparisonExportService.export('Q-001', invalid)).toThrow(/decimal strings/);
    expect(api.post).not.toHaveBeenCalled();
  });
});
