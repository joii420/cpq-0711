import { beforeEach, describe, expect, it, vi } from 'vitest';
import { templateService } from '../../services/templateService';
import { enrichComponentData } from './enrichComponentData';
import { buildComponentDataFromStructure } from './enrichComponentData';

vi.mock('../../services/templateService', () => ({
  templateService: {
    getByIdCached: vi.fn(),
  },
}));

describe('enrichComponentData precision ingress', () => {
  beforeEach(() => {
    vi.mocked(templateService.getByIdCached).mockReset();
    vi.mocked(templateService.getByIdCached).mockResolvedValue({
      data: {
        componentsSnapshot: JSON.stringify([{
          componentId: 'component-1',
          componentCode: 'MATERIAL',
          tabName: 'Material',
          fields: [],
          formulas: [],
        }]),
      },
    } as any);
  });

  it('converts legacy rowData numeric tokens directly to exact decimal strings', async () => {
    const result = await enrichComponentData('template-1', [{
      componentId: 'component-1',
      tabName: 'Material',
      rowData: '[{"unit_price":98765431.123456789012,"quantity":3}]',
    }]);

    expect(result[0].rows?.[0].unit_price).toBe('98765431.123456789012');
    expect(result[0].rows?.[0].quantity).toBe('3');
    expect(typeof result[0].rows?.[0].unit_price).toBe('string');
  });

  it('uses a decimal string zero when structure data has no saved subtotal', () => {
    const result = buildComponentDataFromStructure({
      templateId: 'template-1',
      tabs: [{ componentId: 'component-1', componentCode: 'MATERIAL', tabName: 'Material', fields: [], formulas: [] }],
    } as any, [{ componentId: 'component-1', rows: [{}] }]);

    expect(result[0].subtotal).toBe('0');
    expect(typeof result[0].subtotal).toBe('string');
  });
});
