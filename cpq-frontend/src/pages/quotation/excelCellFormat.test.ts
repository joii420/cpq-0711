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
});
