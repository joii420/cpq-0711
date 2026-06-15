import { describe, it, expect } from 'vitest';
import { computeAllFormulas } from './QuotationStep2';
import type { ComponentDataItem } from './QuotationStep2';

// ─── Fixtures ────────────────────────────────────────────────────────────────

/**
 * comp with unit_source_field configured: 重量 → uses 单位 column for conversion.
 * 数量 has no unit_source_field → stays as-is.
 * formulas = [] so no FORMULA fields; function runs to populate fieldValues bag.
 */
const compWithUnit: ComponentDataItem = {
  componentId: 'unit-test-1',
  componentCode: 'UNIT_TEST',
  tabName: '投料',
  fields: [
    { name: '重量', field_type: 'INPUT_NUMBER', unit_source_field: '单位' },
    { name: '单位', field_type: 'INPUT_TEXT' },
    { name: '数量', field_type: 'INPUT_NUMBER' },
  ],
  formulas: [],
  rows: [],
  subtotal: 0,
};

/**
 * comp without unit_source_field: no conversion should occur.
 */
const compNoUnit: ComponentDataItem = {
  componentId: 'unit-test-2',
  componentCode: 'UNIT_TEST_NO_UNIT',
  tabName: '投料',
  fields: [
    { name: '重量', field_type: 'INPUT_NUMBER' },
    { name: '数量', field_type: 'INPUT_NUMBER' },
  ],
  formulas: [],
  rows: [],
  subtotal: 0,
};

// ─── Tests ───────────────────────────────────────────────────────────────────

describe('computeAllFormulas — 物化点1：unit_source_field 换算', () => {
  it('配置了 unit_source_field 的列 (重量 g→KG) 在公式求值前归一到标准单位', () => {
    const row = { 重量: '500', 单位: 'g', 数量: 3 };
    const out: { fieldValues: Record<string, number> } = { fieldValues: {} };

    // param order: comp, row, allComponentSubtotals, quotationFields, pathCache,
    //              partNo, basicDataValues, previousRowSubtotal, globalVariableDefs,
    //              crossTabRows, previousRowValues, out
    computeAllFormulas(
      compWithUnit, row,
      undefined, undefined, undefined, undefined, undefined, undefined, undefined, undefined, undefined,
      out,
    );

    // 重量 500g → 500 × 0.001 = 0.5 KG (canonical)
    expect(out.fieldValues['重量']).toBeCloseTo(0.5, 6);
    // 数量 has no unit_source_field → unchanged
    expect(out.fieldValues['数量']).toBeCloseTo(3, 6);
  });

  it('入参 row 对象不被 mutate（原始显示值不变）', () => {
    const row = { 重量: '500', 单位: 'g', 数量: 3 };
    const out: { fieldValues: Record<string, number> } = { fieldValues: {} };

    computeAllFormulas(
      compWithUnit, row,
      undefined, undefined, undefined, undefined, undefined, undefined, undefined, undefined, undefined,
      out,
    );

    // 关键不变量：渲染层用同一 row 对象显示原始值，不应被修改
    expect((row as any)['重量']).toBe('500');
    expect((row as any)['单位']).toBe('g');
  });

  it('未配置 unit_source_field 时不做换算（重量原值 500 进 fieldValues）', () => {
    const row = { 重量: '500', 数量: 3 };
    const out: { fieldValues: Record<string, number> } = { fieldValues: {} };

    computeAllFormulas(
      compNoUnit, row,
      undefined, undefined, undefined, undefined, undefined, undefined, undefined, undefined, undefined,
      out,
    );

    // 无换算，500 原值
    expect(out.fieldValues['重量']).toBeCloseTo(500, 6);
    expect(out.fieldValues['数量']).toBeCloseTo(3, 6);
  });
});
