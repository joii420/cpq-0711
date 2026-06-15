/**
 * Task TDD — subtotalsFromResolvedRows 物化点5 前端对齐
 *
 * 验证：is_subtotal 列配置了 unit_source_field 时，
 * subtotalsFromResolvedRows 读的是换算后 canonical 值，而非原始 row 值。
 *
 * 场景：重量列配置 unit_source_field='单位'，is_subtotal=true，两行：
 *   row0: 重量=500, 单位='g'  → canonical 0.5 kg
 *   row1: 重量=1000, 单位='g' → canonical 1.0 kg
 * 期望 out['tab1#重量'] ≈ 1.5（不是 1500）
 * 同时断言：输入 rows 未被 mutate（rows[0].重量 === 500）
 */
import { describe, it, expect } from 'vitest';
import { subtotalsFromResolvedRows } from './QuotationStep2';

describe('subtotalsFromResolvedRows — 物化点5 前端对齐', () => {
  it('is_subtotal 列配置 unit_source_field 时，按换算后 canonical 值累加', () => {
    const comp: any = {
      tabName: 'tab1',
      fields: [
        {
          name: '重量',
          field_type: 'INPUT_NUMBER',
          is_subtotal: true,
          unit_source_field: '单位',
        },
        {
          name: '单位',
          field_type: 'INPUT_TEXT',
        },
      ],
    };

    const rows = [
      { 重量: 500, 单位: 'g' },
      { 重量: 1000, 单位: 'g' },
    ];

    const out: Record<string, number> = {};
    subtotalsFromResolvedRows(comp, rows, out);

    // 换算后：0.5 + 1.0 = 1.5（g → kg，系数 0.001）
    expect(out['tab1#重量']).toBeCloseTo(1.5, 4);

    // 输入 rows 不得被 mutate
    expect(rows[0]['重量']).toBe(500);
    expect(rows[1]['重量']).toBe(1000);
  });

  it('未配置 unit_source_field 时行为不变（原值累加 1500）', () => {
    const comp: any = {
      tabName: 'tab1',
      fields: [
        {
          name: '重量',
          field_type: 'INPUT_NUMBER',
          is_subtotal: true,
          // 无 unit_source_field
        },
      ],
    };

    const rows = [
      { 重量: 500 },
      { 重量: 1000 },
    ];

    const out: Record<string, number> = {};
    subtotalsFromResolvedRows(comp, rows, out);

    // 无换算，原值累加
    expect(out['tab1#重量']).toBeCloseTo(1500, 4);
  });
});
