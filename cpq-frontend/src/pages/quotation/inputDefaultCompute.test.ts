import { describe, it, expect } from 'vitest';
import { computeAllFormulas } from './QuotationStep2';
import type { ComponentDataItem } from './QuotationStep2';

// 组件：单价(INPUT_NUMBER, 无行值, content='8') + 金额(FORMULA = 单价)
const comp: ComponentDataItem = {
  componentId: 'c1',
  componentCode: 'C1',
  componentType: 'NORMAL',
  tabName: 'T',
  fields: [
    {
      name: '单价',
      field_type: 'INPUT_NUMBER',
      key: '单价',
      label: '单价',
      content: '8',
    } as any,
    {
      name: '金额',
      field_type: 'FORMULA',
      key: '金额',
      label: '金额',
    } as any,
  ],
  formulas: [
    {
      name: '金额',
      expression: [{ type: 'field', value: '单价' }],
      result_type: 'NUMBER',
    } as any,
  ],
  formulaAssignments: {},
  rows: [],
  subtotal: 0,
};

describe('computeAllFormulas INPUT 静态 content 参与公式', () => {
  it('单价无源 content=8 → 金额=8，fieldValues 含单价=8', () => {
    const out: { fieldValues: Record<string, number>; errors: Record<string, string> } = {
      fieldValues: {},
      errors: {},
    };
    // 签名: (comp, row, allComponentSubtotals?, quotationFields?, pathCache?, partNo?,
    //         basicDataValues?, previousRowSubtotal?, globalVariableDefs?, crossTabRows?,
    //         previousRowValues?, out?)
    const cache = computeAllFormulas(
      comp,
      {},       // row — 无任何行值，让 content 兜底生效
      {},       // allComponentSubtotals
      {},       // quotationFields
      undefined, // pathCache
      undefined, // partNo
      undefined, // basicDataValues
      undefined, // previousRowSubtotal
      undefined, // globalVariableDefs
      undefined, // crossTabRows
      undefined, // previousRowValues
      out,
    );
    // 金额公式 = 单价 = 8（content 兜底后参与公式计算）
    expect(cache['金额']).toBe(8);
    // fieldValues 里单价也应被写入（out.fieldValues 由函数填入）
    expect(out.fieldValues['单价']).toBe(8);
  });

  it('INPUT_TEXT 字段 content 兜底同样生效（文字型默认值参与 fieldValues）', () => {
    const compText: ComponentDataItem = {
      ...comp,
      fields: [
        {
          name: '备注',
          field_type: 'INPUT_TEXT',
          key: '备注',
          label: '备注',
          content: 'default_remark',
        } as any,
        // INPUT_NUMBER 引用 INPUT_TEXT 在公式里意义有限，但 fieldValues 应有值
        {
          name: '单价',
          field_type: 'INPUT_NUMBER',
          key: '单价',
          label: '单价',
          content: '5',
        } as any,
        {
          name: '金额',
          field_type: 'FORMULA',
          key: '金额',
          label: '金额',
        } as any,
      ],
    };
    const out: { fieldValues: Record<string, number>; errors: Record<string, string> } = {
      fieldValues: {},
      errors: {},
    };
    computeAllFormulas(
      compText, {}, {}, {}, undefined, undefined, undefined,
      undefined, undefined, undefined, undefined, out,
    );
    // INPUT_TEXT content='default_remark' 是字符串，parseFloat 为 NaN → fieldValues 里不写入数值
    // 但 INPUT_NUMBER content='5' → fieldValues['单价'] = 5
    expect(out.fieldValues['单价']).toBe(5);
    // 确认 INPUT_TEXT 不产生 NaN 写入（fieldValues['备注'] 不存在，而非 NaN）
    expect(Object.prototype.hasOwnProperty.call(out.fieldValues, '备注')).toBe(false);
  });

  it('显式清空("")→ 不再 content 兜底，按 0 算（金额=0，fieldValues 无单价）', () => {
    const out: { fieldValues: Record<string, number>; errors: Record<string, string> } = {
      fieldValues: {},
      errors: {},
    };
    const cache = computeAllFormulas(
      comp,
      { '单价': '' }, // 用户主动清空 → key 存在但值为 ''（区别于 key 缺失）
      {}, {}, undefined, undefined, undefined,
      undefined, undefined, undefined, undefined, out,
    );
    expect(cache['金额']).toBe(0);
    expect(Object.prototype.hasOwnProperty.call(out.fieldValues, '单价')).toBe(false);
  });

  it('key 缺失（从未填/未烘焙）仍按 content 兜底（区别于显式清空）', () => {
    const out: { fieldValues: Record<string, number>; errors: Record<string, string> } = {
      fieldValues: {}, errors: {},
    };
    const cache = computeAllFormulas(
      comp, {}, {}, {}, undefined, undefined, undefined,
      undefined, undefined, undefined, undefined, out,
    );
    expect(cache['金额']).toBe(8);
    expect(out.fieldValues['单价']).toBe(8);
  });

  it('行值优先于 content（有行值时不用 content 兜底）', () => {
    const out: { fieldValues: Record<string, number>; errors: Record<string, string> } = {
      fieldValues: {},
      errors: {},
    };
    const cache = computeAllFormulas(
      comp,
      { '单价': 99 }, // row 里有值
      {}, {}, undefined, undefined, undefined,
      undefined, undefined, undefined, undefined, out,
    );
    expect(cache['金额']).toBe(99);
    expect(out.fieldValues['单价']).toBe(99);
  });
});
