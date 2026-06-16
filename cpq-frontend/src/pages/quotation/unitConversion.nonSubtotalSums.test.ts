/**
 * 物化点2 — INPUT_NUMBER 列 unit_source_field canonical 换算求和
 *
 * 验证：INPUT_NUMBER 列配置了 unit_source_field 时，
 * footer 列小计（columnSumsByComp）读的是换算后 canonical 值，而非原始 row 值。
 *
 * 场景：重量列配置 unit_source_field='单位'，两行：
 *   row0: 重量=500, 单位='g'  → canonical 0.5 kg
 *   row1: 重量=1000, 单位='g' → canonical 1.0 kg
 * 期望合计 columnSumsByComp['UC']['重量'] ≈ 1.5（不是 1500）
 *
 * 迁移说明（Task2 评审跟进）：
 *   原测试调用 computeNonSubtotalColumnSums（已删除死函数），
 *   改为调用 buildCrossTabRows 取 columnSumsByComp——单一来源，口径对齐生产渲染路径。
 */
import { describe, it, expect } from 'vitest';
import { buildCrossTabRows } from './QuotationStep2';

describe('columnSumsByComp — 物化点2 INPUT_NUMBER 列 unit_source_field canonical 换算', () => {
  it('INPUT_NUMBER 配置 unit_source_field 时，按换算后 canonical 值累加', () => {
    const comp: any = {
      componentId: 'UC', componentCode: 'UC', tabName: 'UC',
      componentType: 'NORMAL',
      fields: [
        {
          name: '重量',
          field_type: 'INPUT_NUMBER',
          // is_subtotal 不设（falsy）→ 非小计列，进入 columnSumsByComp
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
      componentData: [], snapshotRows: 2, subtotal: 0,
    };

    const allSubs: Record<string, number> = {};
    // lookupExpansion 返回 undefined → buildCrossTabRows 回退到 comp.rows（totalRows=2）
    const { columnSumsByComp } = buildCrossTabRows([comp], allSubs, undefined, () => undefined);

    // 换算后：500g→0.5kg，1000g→1.0kg，合计 1.5
    // 确保不是原值之和 1500
    expect(columnSumsByComp['UC']?.['重量']).toBeCloseTo(1.5, 4);
    expect(columnSumsByComp['UC']?.['重量']).not.toBeCloseTo(1500, 0);
  });

  it('未配置 unit_source_field 时行为不变（原值累加）', () => {
    const comp: any = {
      componentId: 'UC2', componentCode: 'UC2', tabName: 'UC2',
      componentType: 'NORMAL',
      fields: [
        { name: '数量', field_type: 'INPUT_NUMBER' },  // 无 unit_source_field
      ],
      formulas: [],
      rows: [{ 数量: 3 }, { 数量: 7 }],
      componentData: [], snapshotRows: 2, subtotal: 0,
    };

    const allSubs: Record<string, number> = {};
    const { columnSumsByComp } = buildCrossTabRows([comp], allSubs, undefined, () => undefined);

    expect(columnSumsByComp['UC2']?.['数量']).toBeCloseTo(10, 4);
  });
});
