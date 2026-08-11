import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import { buildComponentDataFromTemplate } from './BulkImportPartsDrawer';
import { parseTemplateComponentsSnapshot } from './templateSnapshot';

const exact = '98765431.123456789012';

describe('template componentsSnapshot precision ingress', () => {
  it('preserves a historical numeric field content and preset row without JSON.parse rounding', () => {
    const snapshot = `[{"componentId":"C-1","fields":[{"name":"price","field_type":"FIXED_VALUE","content":${exact}}],"preset_rows":[{"price":${exact}}]}]`;
    const parsed = parseTemplateComponentsSnapshot(snapshot);
    const built = buildComponentDataFromTemplate({ componentsSnapshot: snapshot });

    expect(parsed[0].fields[0].content).toBe(exact);
    expect(built[0].fields[0].content).toBe(exact);
    expect(built[0].rows?.[0].price).toBe(exact);
    expect(typeof built[0].rows?.[0].price).toBe('string');
  });

  it('keeps both quote and costing template consumers off ordinary JSON.parse', () => {
    const source = readFileSync(fileURLToPath(new URL('./QuotationStep2.tsx', import.meta.url)), 'utf8');
    expect(source).not.toMatch(/JSON\.parse\(tmpl\.componentsSnapshot\)/);
    expect(source.match(/parseTemplateComponentsSnapshot\(tmpl\?\.componentsSnapshot\)/g)).toHaveLength(2);
  });
});
