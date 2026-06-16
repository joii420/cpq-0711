/**
 * 单位换算 cross_tab 回归（用户 bug QT-20260616-1736）：
 * 元素页签的「净用量」(INPUT_NUMBER) 配 unit_source_field='计价单位'；来料页签的「材料费」
 * (FORMULA) = cross_tab_ref SUM(元素.净用量)。改计价单位 KG→G 后，材料费应按换算后的净用量重算。
 *
 * 根因：buildCrossTabRows 喂给 cross_tab 消费的源行未做单位换算 → 材料费读到原值净用量。
 * 期望（修复后）：净用量 500 + 计价单位 'G' → canonical 0.5 → cross_tab SUM = 0.5（而非 500）。
 */
import { describe, it, expect } from 'vitest';
import { buildCrossTabRows } from './QuotationStep2';

// 元素：1 行，净用量(INPUT_NUMBER, 配单位来源=计价单位) + 计价单位(INPUT_TEXT='G')
const yuansuFields = [
  { name: '净用量', field_type: 'INPUT_NUMBER', unit_source_field: '计价单位' },
  { name: '计价单位', field_type: 'INPUT_TEXT' },
] as any;

const yuansuExpansion = {
  rowCount: 1,
  rows: [{ driverRow: { 净用量: 500, 计价单位: 'G' }, basicDataValues: {} }],
} as any;

// 来料：1 行，材料费(FORMULA, is_subtotal) = SUM(元素.净用量)
const llFields = [
  { name: '材料费', field_type: 'FORMULA', is_subtotal: true },
] as any;

const llExpansion = { rowCount: 1, rows: [{ driverRow: {}, basicDataValues: {} }] } as any;

const componentData = [
  {
    componentId: '元素', componentCode: '元素', tabName: '元素',
    componentType: 'NORMAL',
    fields: yuansuFields, formulas: [],
    rows: [{ 净用量: 500, 计价单位: 'G' }],
    componentData: [], snapshotRows: 1, subtotal: 0,
  },
  {
    componentId: '来料', componentCode: '来料', tabName: '来料',
    componentType: 'NORMAL',
    fields: llFields,
    formulas: [
      {
        name: '材料费',
        expression: [
          { type: 'cross_tab_ref', source: '元素', target: '净用量', match: [], agg: 'SUM' },
        ],
      },
    ],
    rows: [{}],
    componentData: [], snapshotRows: 1, subtotal: 0,
  },
] as any;

const lookupExpansion = (comp: any) => {
  if (comp.componentId === '元素') return yuansuExpansion;
  if (comp.componentId === '来料') return llExpansion;
  return undefined;
};

describe('单位换算 cross_tab', () => {
  it('来料.材料费 = SUM(元素.净用量)，净用量 500 + 计价单位 G → 跨页签读 canonical 0.5（非原值 500）', () => {
    const allComponentSubtotals: Record<string, number> = {};
    buildCrossTabRows(componentData, allComponentSubtotals, undefined, lookupExpansion);
    expect(allComponentSubtotals['来料#材料费']).toBeCloseTo(0.5, 6);
  });

  it('计价单位为 KG 时系数 ×1，材料费 = 500（确认仅按单位换算，未恒定缩放）', () => {
    const kgData = JSON.parse(JSON.stringify(componentData));
    kgData[0].rows[0].计价单位 = 'KG';
    const kgExpansion = { rowCount: 1, rows: [{ driverRow: { 净用量: 500, 计价单位: 'KG' }, basicDataValues: {} }] } as any;
    const lookup = (comp: any) => (comp.componentId === '元素' ? kgExpansion : comp.componentId === '来料' ? llExpansion : undefined);
    const subs: Record<string, number> = {};
    buildCrossTabRows(kgData, subs, undefined, lookup);
    expect(subs['来料#材料费']).toBeCloseTo(500, 6);
  });
});
