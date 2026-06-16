/**
 * 端到端复现用户真实场景 QT-20260616-1736 / 纯材料成本(来料)：
 * 来料.来料材料费 = 纯材料成本(来料)，其中含一个 cross_tab_ref(单源 元素, targetExpr=净用量×单价)。
 * 元素.净用量 配 unit_source_field=计价单位（均为普通 INPUT，值在 row 里）。
 * 走完整链路：buildCrossTabRows 建 store → computeAllFormulas 求 来料材料费。
 * 期望：计价单位 G 时的 来料材料费 = KG 时的 1/1000（净用量按 G 归一到 KG）。
 */
import { describe, it, expect } from 'vitest';
import { buildCrossTabRows, computeAllFormulas } from './QuotationStep2';

const 元素Id = 'ad99c10d';

function makeData(unit: string) {
  const ysExpansion = {
    rowCount: 1,
    rows: [{ driverRow: { 料件: 'P1', 净用量: 100, 单价: 100, 计价单位: unit }, basicDataValues: {} }],
  } as any;
  const componentData = [
    {
      componentId: 元素Id, componentCode: 元素Id, tabName: '元素', componentType: 'NORMAL',
      fields: [
        { name: '料件', field_type: 'INPUT_TEXT' },
        { name: '净用量', field_type: 'INPUT_NUMBER', unit_source_field: '计价单位' },
        { name: '单价', field_type: 'INPUT_NUMBER' },
        { name: '计价单位', field_type: 'INPUT_TEXT' },
      ],
      formulas: [],
      rows: [{ 料件: 'P1', 净用量: 100, 单价: 100, 计价单位: unit }],
      componentData: [], snapshotRows: 1, subtotal: 0,
    },
    {
      componentId: '来料', componentCode: '来料', tabName: '来料', componentType: 'NORMAL',
      fields: [
        { name: '料件', field_type: 'INPUT_TEXT' },
        { name: '来料材料费', field_type: 'FORMULA' },
      ],
      formulas: [{
        name: '来料材料费',
        expression: [{
          type: 'cross_tab_ref', agg: 'SUM', source: 元素Id, target: '',
          match: [{ a: '料件', b: '料件' }],
          targetExpr: [
            { type: 'field', value: '净用量', source: 元素Id },
            { type: 'operator', value: '*' },
            { type: 'field', value: '单价', source: 元素Id },
          ],
        }],
      }],
      rows: [{ 料件: 'P1' }],
      componentData: [], snapshotRows: 1, subtotal: 0,
    },
  ] as any;
  return componentData;
}

function compute来料材料费(unit: string): number {
  const data = makeData(unit);
  const subs: Record<string, number> = {};
  const store = buildCrossTabRows(data, subs, undefined,
    (c: any) => (c.componentId === 元素Id ? { rowCount: 1, rows: [{ driverRow: { 料件: 'P1', 净用量: 100, 单价: 100, 计价单位: unit }, basicDataValues: {} }] } as any : undefined));
  const llComp = data[1];
  const res = computeAllFormulas(llComp, { 料件: 'P1' }, subs, undefined, undefined, undefined,
    undefined, undefined, undefined, store);
  return res['来料材料费'] as number;
}

describe('cross_tab E2E 纯材料成本(来料) 净用量 随计价单位换算', () => {
  it('KG → 净用量×单价 = 100×100 = 10000', () => {
    expect(compute来料材料费('KG')).toBeCloseTo(10000, 4);
  });
  it('G → 净用量(100g→0.1kg)×单价 = 0.1×100 = 10 (= KG 的 1/1000)', () => {
    expect(compute来料材料费('G')).toBeCloseTo(10, 4);
  });
});
