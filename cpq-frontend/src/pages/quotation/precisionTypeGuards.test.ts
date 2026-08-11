import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

function source(relative: string): string {
  return readFileSync(fileURLToPath(new URL(relative, import.meta.url)), 'utf8');
}

describe('frontend precision type guards', () => {
  it('does not widen LIST_FORMULA defaultValue back to number', () => {
    const componentCell = source('./components/ComponentCell.tsx');
    expect(componentCell).toContain('defaultValue?: string | null;');
    expect(componentCell).not.toMatch(/defaultValue\?:\s*string\s*\|\s*number/);
  });

  it('uses the POST HTML service rather than opening a GET endpoint', () => {
    const detail = source('./QuotationDetail.tsx');
    expect(detail).not.toContain('/export/html?');
    expect(detail.match(/quotationService\.exportHtml\(id!/g)).toHaveLength(2);
  });

  it('wires both batch path consumers through the numeric-response guard', () => {
    const cardCache = source('./usePathFormulaCache.ts');
    const excelCache = source('./useLinkedExcelRows.ts');
    expect(cardCache).toContain('normalizePathFormulaResult(');
    expect(excelCache).toContain('normalizePathFormulaResult(');
    expect(cardCache).not.toContain('setGlobalPathCache(next as any)');
  });

  it('keeps configure quantity state and request DTOs string-only', () => {
    const configureTypes = source('../../types/configure.ts');
    const drawer = source('./ConfigureProductDrawer.tsx');
    const table = source('./configure/SelDetailTable.tsx');
    expect(configureTypes).not.toMatch(/quantity\??:\s*number/);
    expect(drawer).not.toContain('Math.floor');
    expect(table).toContain('stringMode');
  });
});
