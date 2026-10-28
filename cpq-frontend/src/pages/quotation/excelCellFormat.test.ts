import { describe, it, expect } from 'vitest';
import { renderCellValue, isComputedExcelColumn } from './excelCellFormat';
import type { CostingTemplateColumn } from '../../services/costingTemplateService';

const col = (over: Partial<CostingTemplateColumn> = {}): CostingTemplateColumn =>
  ({ col_key: 'c', title: 'C', source_type: 'COMPONENT_FIELD', ...over } as CostingTemplateColumn);

describe('excelCellFormat', () => {
  it('空值渲染占位（不抛、返回真值）', () => {
    expect(renderCellValue(null, col())).toBeTruthy();
    expect(renderCellValue('', col())).toBeTruthy();
  });
  it('计算列识别', () => {
    expect(isComputedExcelColumn('EXCEL_FORMULA')).toBe(true);
    expect(isComputedExcelColumn('CARD_FORMULA')).toBe(true);
    expect(isComputedExcelColumn('COMPONENT_FIELD')).toBe(false);
  });

  it('计算列应用角色上限，原始列保持原精度', () => {
    expect(renderCellValue('1.2345678915', col({ source_type: 'EXCEL_FORMULA' }), 4)).toBe('1.2346');
    expect(renderCellValue('1.2345678915', col({ source_type: 'COMPONENT_FIELD' }), 4)).toBe('1.2345678915');
  });

  it('仅对计算百分比列应用角色上限', () => {
    const display_format = { type: 'PERCENT', decimals: 6 } as CostingTemplateColumn['display_format'];
    expect(renderCellValue('0.123456789', col({ source_type: 'EXCEL_FORMULA', display_format }), 4)).toBe('12.3457%');
    expect(renderCellValue('0.123456789', col({ source_type: 'COMPONENT_FIELD', display_format }), 4)).toBe('12.345679%');
  });
});
