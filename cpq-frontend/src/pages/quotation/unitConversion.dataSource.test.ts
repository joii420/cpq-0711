/**
 * 单位换算时机回归（用户真实场景 COMP-0028 / QT-20260616-1736）：
 * 组成用量 = INPUT_NUMBER + default_source $ll_view._组成用量（值来自数据源，不在 row 里），
 * 配 unit_source_field=单位（单位也来自 $ll_view._单位）。同页签公式 材料费 = 组成用量。
 *
 * 根因：换算若在 computeAllFormulas 顶部对 row 做，则数据源列(此刻 row 里还没值)被漏换。
 * 期望：组成用量 500 + 单位 G → 材料费 = 0.5（而非原值 500）。
 */
import { describe, it, expect } from 'vitest';
import { computeAllFormulas } from './QuotationStep2';
import { bnfDriverLookupKey } from './useDriverExpansions';

const fields = [
  { name: '组成用量', field_type: 'INPUT_NUMBER', default_source: { type: 'BASIC_DATA', path: '$ll_view._组成用量' }, unit_source_field: '单位' },
  { name: '单位', field_type: 'INPUT_TEXT', default_source: { type: 'BASIC_DATA', path: '$ll_view._单位' } },
  { name: '材料费', field_type: 'FORMULA' },
] as any;

const comp = {
  componentId: '来料', componentCode: '来料', tabName: '来料', componentType: 'NORMAL',
  fields,
  formulas: [{ name: '材料费', expression: [{ type: 'field', value: '组成用量' }] }],
  rows: [], componentData: [], snapshotRows: 0, subtotal: 0,
} as any;

const bdv = (qty: number, unit: string) => ({
  [bnfDriverLookupKey('$ll_view._组成用量')]: qty,
  [bnfDriverLookupKey('$ll_view._单位')]: unit,
});

describe('单位换算 同页签 + 数据源列 (default_source)', () => {
  it('组成用量 来自 $ll_view, 单位=G → 材料费(=组成用量) = 0.5 (而非原值 500)', () => {
    const res = computeAllFormulas(comp, {}, undefined, undefined, undefined, '料8', bdv(500, 'G'));
    expect(res['材料费']).toBeCloseTo(0.5, 6);
  });
  it('单位=KG → ×1 → 材料费 = 500', () => {
    const res = computeAllFormulas(comp, {}, undefined, undefined, undefined, '料8', bdv(500, 'KG'));
    expect(res['材料费']).toBeCloseTo(500, 6);
  });
});
