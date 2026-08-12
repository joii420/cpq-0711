import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { ComponentDataItem, ComponentField } from '../QuotationStep2';
import { ComponentCell } from './ComponentCell';

function renderCell(
  field: ComponentField,
  value: string,
  readonly = true,
  priceLocked = false,
): string {
  const key = field.name;
  const row = { [key]: value };
  const activeComponent = {
    componentId: 'component-1',
    componentCode: 'PRECISION',
    componentType: 'NORMAL',
    tabName: 'Precision',
    fields: [field],
    formulas: [],
    rows: [row],
    subtotal: '0',
    elementPriceField: priceLocked ? key : undefined,
  } as ComponentDataItem;
  return renderToStaticMarkup(
    <ComponentCell
      field={field}
      row={row}
      rowIndex={0}
      fieldKey={key}
      readonly={readonly}
      context={{
        pathCacheState: {},
        formulaCache: {},
        partNo: 'P-1',
        activeComponent,
        priceLocked,
        priceVersionNo: priceLocked ? 'PV-1' : undefined,
      }}
    />,
  );
}

describe('ReadonlyProductCard shared INPUT precision presentation', () => {
  it.each(['1.2300', '1.234567890499', '-1.2345678905'])(
    'preserves a readonly INPUT_NUMBER exactly: %s',
    (workValue) => {
    const html = renderCell({ name: '精度输入', field_type: 'INPUT_NUMBER' }, workValue);
    expect(html).toBe(`<span>${workValue}</span>`);
    },
  );

  it('does not apply a configured display precision to INPUT_NUMBER source text', () => {
    const html = renderCell({ name: '精度输入', field_type: 'INPUT_NUMBER', decimals: 4 }, '1.234567890499');
    expect(html).toBe('<span>1.234567890499</span>');
  });

  it('formats an explicitly amount-bearing INPUT field without changing plain INPUT_TEXT', () => {
    const workValue = '1.234567890499';
    expect(renderCell({ name: '金额', field_type: 'INPUT_TEXT', is_amount: true }, workValue))
      .toBe('<span>1.23456789</span>');
    expect(renderCell({ name: '原始文本', field_type: 'INPUT_TEXT' }, workValue))
      .toBe(`<span>${workValue}</span>`);
  });

  it('does not format BASIC_DATA raw text that happens to look numeric', () => {
    const rawCode = '001.230000000000';
    const html = renderCell({
      name: '基础编码',
      field_type: 'BASIC_DATA',
      basic_data_path: 'material.code',
    }, rawCode);
    expect(html).toBe(`<span class="qt-ds-value">${rawCode}</span>`);
  });

  it('keeps the 12-place work value in editable input state', () => {
    const workValue = '1.234567890499';
    const html = renderCell({ name: '精度输入', field_type: 'INPUT_NUMBER' }, workValue, false);
    expect(html).toContain(`value="${workValue}"`);
    expect(html).not.toContain('value="1.23456789"');
  });

  it('preserves the source text in a version-locked INPUT_NUMBER amount cell', () => {
    const workValue = '1.234567890499';
    const html = renderCell({ name: '元素单价', field_type: 'INPUT_NUMBER', is_amount: true }, workValue, true, true);
    expect(html).toContain(`<span>${workValue}</span>`);
    expect(html).toContain('🔒PV-1');
  });
});
