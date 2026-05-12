import { describe, it, expect } from 'vitest';

// We test the computeFormula logic by importing the component data types
// and simulating the formula matching behavior
import type { ComponentDataItem } from './QuotationStep2';

// Replicate the computeFormula logic (extracted for testability)
import { evaluateExpression } from '../../utils/formulaEngine';

function computeFormula(
  comp: ComponentDataItem,
  formulaFieldName: string,
  row: Record<string, any>,
  allComponentSubtotals?: Record<string, number>,
): number | null {
  // 1. Exact name match
  let formula = comp.formulas.find(f => f.name === formulaFieldName);

  // 2. Fallback: positional index match
  if (!formula) {
    const formulaFields = comp.fields.filter(f => f.field_type === 'FORMULA');
    const fieldIndex = formulaFields.findIndex(f => (f.name || f.key) === formulaFieldName);
    if (fieldIndex >= 0 && fieldIndex < comp.formulas.length) {
      formula = comp.formulas[fieldIndex];
    }
  }

  if (!formula || !formula.expression || formula.expression.length === 0) return null;

  const fieldValues: Record<string, number> = {};
  for (const f of comp.fields) {
    if (f.field_type !== 'FORMULA') {
      const key = f.name || f.key || '';
      const val = parseFloat(row[key]);
      if (!isNaN(val)) fieldValues[key] = val;
    }
  }

  try {
    return evaluateExpression(formula.expression, fieldValues, allComponentSubtotals || {});
  } catch {
    return null;
  }
}

// ─── Test fixtures ──────────────────────────────────────────────────────────

// Scenario: formula.name matches FORMULA field.name (PRD standard)
const compExactMatch: ComponentDataItem = {
  componentId: 'comp-1',
  componentCode: 'COMP_EXACT',
  tabName: '投料金额',
  fields: [
    { name: '单价', field_type: 'INPUT' },
    { name: '数量', field_type: 'INPUT' },
    { name: '金额', field_type: 'FORMULA', is_subtotal: true },
  ],
  formulas: [
    {
      name: '金额',
      expression: [
        { type: 'field', value: '单价' },
        { type: 'operator', value: '*' },
        { type: 'field', value: '数量' },
      ],
    },
  ],
  rows: [],
  subtotal: 0,
};

// Scenario: formula.name does NOT match (real bug: "物料公式" vs "金额")
const compNameMismatch: ComponentDataItem = {
  componentId: 'comp-2',
  componentCode: 'COMP_NAME_MISMATCH',
  tabName: '投料金额',
  fields: [
    { name: '单价', field_type: 'INPUT' },
    { name: '数量', field_type: 'INPUT' },
    { name: '金额', field_type: 'FORMULA', is_subtotal: true },
  ],
  formulas: [
    {
      name: '物料公式',  // Mismatch! Field is "金额" but formula is "物料公式"
      expression: [
        { type: 'field', value: '单价' },
        { type: 'operator', value: '*' },
        { type: 'field', value: '数量' },
      ],
    },
  ],
  rows: [],
  subtotal: 0,
};

// Scenario: multiple FORMULA fields, positional matching
const compMultiFormula: ComponentDataItem = {
  componentId: 'comp-3',
  componentCode: 'COMP_MULTI',
  tabName: '加工费用',
  fields: [
    { name: '工时', field_type: 'INPUT' },
    { name: '时薪', field_type: 'INPUT' },
    { name: '数量', field_type: 'INPUT' },
    { name: '工费', field_type: 'FORMULA' },
    { name: '总价', field_type: 'FORMULA', is_subtotal: true },
  ],
  formulas: [
    {
      name: '工费公式',  // Mismatch with "工费"
      expression: [
        { type: 'field', value: '工时' },
        { type: 'operator', value: '*' },
        { type: 'field', value: '时薪' },
      ],
    },
    {
      name: '总价公式',  // Mismatch with "总价"
      expression: [
        { type: 'field', value: '工时' },
        { type: 'operator', value: '*' },
        { type: 'field', value: '时薪' },
        { type: 'operator', value: '*' },
        { type: 'field', value: '数量' },
      ],
    },
  ],
  rows: [],
  subtotal: 0,
};

// Scenario: no formulas defined
const compNoFormulas: ComponentDataItem = {
  componentId: 'comp-4',
  componentCode: 'COMP_NO_FORMULAS',
  tabName: '其他',
  fields: [
    { name: '备注', field_type: 'INPUT' },
    { name: '总计', field_type: 'FORMULA' },
  ],
  formulas: [],
  rows: [],
  subtotal: 0,
};

// Scenario: formula references a FORMULA field (circular — should get 0 from fieldValues)
const compCircularRef: ComponentDataItem = {
  componentId: 'comp-5',
  componentCode: 'COMP_CIRCULAR',
  tabName: '投料金额',
  fields: [
    { name: '单价', field_type: 'INPUT' },
    { name: '数量', field_type: 'INPUT' },
    { name: '金额', field_type: 'FORMULA', is_subtotal: true },
  ],
  formulas: [
    {
      name: '金额',
      expression: [
        { type: 'field', value: '单价' },
        { type: 'operator', value: '*' },
        { type: 'field', value: '金额' }, // Self-reference! "金额" is the FORMULA field itself
      ],
    },
  ],
  rows: [],
  subtotal: 0,
};

// ─── Tests ──────────────────────────────────────────────────────────────────

describe('computeFormula - exact name match', () => {
  it('formula.name === field.name → calculates correctly', () => {
    const row = { 单价: 45, 数量: 5 };
    expect(computeFormula(compExactMatch, '金额', row)).toBe(225);
  });
});

describe('computeFormula - positional fallback', () => {
  it('formula.name !== field.name → falls back to positional matching', () => {
    const row = { 单价: 45, 数量: 5 };
    // Field "金额" is the 1st FORMULA field, matches formulas[0] "物料公式"
    expect(computeFormula(compNameMismatch, '金额', row)).toBe(225);
  });

  it('multiple mismatched formulas: 1st FORMULA field → formulas[0]', () => {
    const row = { 工时: 8, 时薪: 50, 数量: 3 };
    // "工费" is 1st FORMULA field → formulas[0] "工费公式": 工时 × 时薪
    expect(computeFormula(compMultiFormula, '工费', row)).toBe(400);
  });

  it('multiple mismatched formulas: 2nd FORMULA field → formulas[1]', () => {
    const row = { 工时: 8, 时薪: 50, 数量: 3 };
    // "总价" is 2nd FORMULA field → formulas[1] "总价公式": 工时 × 时薪 × 数量
    expect(computeFormula(compMultiFormula, '总价', row)).toBe(1200);
  });
});

describe('computeFormula - no formula defined', () => {
  it('no formulas → returns null', () => {
    const row = { 备注: 'test' };
    expect(computeFormula(compNoFormulas, '总计', row)).toBeNull();
  });
});

describe('computeFormula - circular reference protection', () => {
  it('formula referencing own FORMULA field → FORMULA fields excluded from fieldValues → gets 0', () => {
    const row = { 单价: 45, 数量: 5 };
    // Expression: 单价 × 金额, but "金额" is FORMULA field → excluded → value = 0
    // Result: 45 × 0 = 0
    expect(computeFormula(compCircularRef, '金额', row)).toBe(0);
  });
});

describe('computeFormula - empty / edge cases', () => {
  it('empty row → all fields default to 0 → result is 0', () => {
    expect(computeFormula(compExactMatch, '金额', {})).toBe(0);
  });

  it('non-numeric values in row → treated as 0', () => {
    const row = { 单价: 'abc', 数量: 5 };
    // parseFloat('abc') = NaN → excluded → 0
    expect(computeFormula(compExactMatch, '金额', row)).toBe(0);
  });

  it('requesting unknown field name → returns null', () => {
    expect(computeFormula(compExactMatch, '不存在的字段', {})).toBeNull();
  });
});
