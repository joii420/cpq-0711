/**
 * Task 9 TDD — computeNonSubtotalColumnSums 输入列换算（物化点2）
 *
 * 验证：INPUT_NUMBER 列配置了 unit_source_field 时，
 * computeNonSubtotalColumnSums 读的是换算后 canonical 值，而非原始 row 值。
 *
 * 场景：重量列配置 unit_source_field='单位'，两行：
 *   row0: 重量=500, 单位='g'  → canonical 0.5 kg
 *   row1: 重量=1000, 单位='g' → canonical 1.0 kg
 * 期望合计 out['重量'] ≈ 1.5（不是 1500）
 */
import { describe, it, expect } from 'vitest';
import { computeNonSubtotalColumnSums } from './QuotationStep2';

describe('computeNonSubtotalColumnSums — 物化点2 输入列换算', () => {
  it('INPUT_NUMBER 配置 unit_source_field 时，按换算后 canonical 值累加', () => {
    const comp: any = {
      componentCode: 'UC', code: 'UC',
      fields: [
        {
          name: '重量',
          field_type: 'INPUT_NUMBER',
          // is_subtotal 不设（falsy）→ 进入 targetFields
          unit_source_field: '单位',
        },
        {
          name: '单位',
          field_type: 'INPUT_TEXT',
        },
      ],
      formulas: [],
      // 两行：500g 和 1000g，单位 'g' → 换算为 kg 时各 /1000
      rows: [
        { 重量: 500, 单位: 'g' },
        { 重量: 1000, 单位: 'g' },
      ],
    };

    // 调用时不传 driverExpansion，函数走 comp.rows（s.totalRows = 2）
    const out = computeNonSubtotalColumnSums(comp);

    // 换算后：0.5 + 1.0 = 1.5
    expect(out['重量']).toBeCloseTo(1.5, 4);
  });

  it('未配置 unit_source_field 时行为不变（原值累加）', () => {
    const comp: any = {
      componentCode: 'UC2', code: 'UC2',
      fields: [
        { name: '数量', field_type: 'INPUT_NUMBER' },  // 无 unit_source_field
      ],
      formulas: [],
      rows: [{ 数量: 3 }, { 数量: 7 }],
    };

    const out = computeNonSubtotalColumnSums(comp);
    expect(out['数量']).toBeCloseTo(10, 4);
  });
});
