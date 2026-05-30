import { describe, it, expect } from 'vitest';
import { toNumber, valuesDiffer, buildComparisonModel } from './comparisonModel';
import type { CostingTemplateColumn } from '../../services/costingTemplateService';

const col = (col_key: string, comparison_tag?: string): CostingTemplateColumn =>
  ({ col_key, title: col_key, source_type: 'VARIABLE', comparison_tag } as CostingTemplateColumn);

describe('toNumber', () => {
  it('parses numbers and numeric strings, NaN otherwise', () => {
    expect(toNumber(12)).toBe(12);
    expect(toNumber('12.5')).toBe(12.5);
    expect(toNumber(' 3 ')).toBe(3);
    expect(Number.isNaN(toNumber('abc'))).toBe(true);
    expect(Number.isNaN(toNumber(''))).toBe(true);
    expect(Number.isNaN(toNumber(null))).toBe(true);
  });
});

describe('valuesDiffer', () => {
  it('12 vs 12.0 are equal (no diff)', () => {
    expect(valuesDiffer(12, '12.0')).toBe(false);
  });
  it('within tolerance → no diff', () => {
    expect(valuesDiffer(1, 1.0000005)).toBe(false);
  });
  it('beyond tolerance → diff', () => {
    expect(valuesDiffer(1, 1.1)).toBe(true);
  });
  it('strings trimmed strict equal → no diff', () => {
    expect(valuesDiffer(' abc ', 'abc')).toBe(false);
  });
  it('different strings → diff', () => {
    expect(valuesDiffer('abc', 'abd')).toBe(true);
  });
});

describe('buildComparisonModel', () => {
  const quoteColumns = [col('A', 'MATERIAL'), col('B', 'PROCESS')];
  const costingColumns = [col('X', 'MATERIAL'), col('Y', 'FREIGHT')];
  const tagMetas = [
    { code: 'MATERIAL', label: '材料费', groupName: '成本', groupSortOrder: 1, tagSortOrder: 1 },
    { code: 'PROCESS', label: '加工费', groupName: '成本', groupSortOrder: 1, tagSortOrder: 2 },
    { code: 'FREIGHT', label: '运费', groupName: '其它', groupSortOrder: 2, tagSortOrder: 1 },
  ];

  it('columns = intersection of tags on both sides', () => {
    const m = buildComparisonModel(quoteColumns, [], costingColumns, [], tagMetas);
    expect(m.columns.map((c) => c.tag)).toEqual(['MATERIAL']);
    expect(m.columns[0].label).toBe('材料费');
  });

  it('rows = union of part numbers, with presence flags', () => {
    const quoteRows = [{ __hfPartNo: 'P1', A: 10 }, { __hfPartNo: 'P2', A: 20 }];
    const costingRows = [{ __hfPartNo: 'P1', X: 10 }, { __hfPartNo: 'P3', X: 30 }];
    const m = buildComparisonModel(quoteColumns, quoteRows as any, costingColumns, costingRows as any, tagMetas);
    expect(m.rows.map((r) => r.partNo)).toEqual(['P1', 'P2', 'P3']);
    expect(m.rows.find((r) => r.partNo === 'P1')!.presence).toBe('BOTH');
    expect(m.rows.find((r) => r.partNo === 'P2')!.presence).toBe('QUOTE_ONLY');
    expect(m.rows.find((r) => r.partNo === 'P3')!.presence).toBe('COSTING_ONLY');
  });

  it('highlights only when both present and differ', () => {
    const quoteRows = [{ __hfPartNo: 'P1', A: 10 }, { __hfPartNo: 'P2', A: 20 }];
    const costingRows = [{ __hfPartNo: 'P1', X: 11 }, { __hfPartNo: 'P3', X: 30 }];
    const m = buildComparisonModel(quoteColumns, quoteRows as any, costingColumns, costingRows as any, tagMetas);
    expect(m.rows.find((r) => r.partNo === 'P1')!.cells['MATERIAL'].highlighted).toBe(true);
    expect(m.rows.find((r) => r.partNo === 'P2')!.cells['MATERIAL'].highlighted).toBe(false);
  });

  it('equal values within tolerance not highlighted', () => {
    const quoteRows = [{ __hfPartNo: 'P1', A: 10 }];
    const costingRows = [{ __hfPartNo: 'P1', X: '10.0' }];
    const m = buildComparisonModel(quoteColumns, quoteRows as any, costingColumns, costingRows as any, tagMetas);
    expect(m.rows[0].cells['MATERIAL'].highlighted).toBe(false);
  });
});
