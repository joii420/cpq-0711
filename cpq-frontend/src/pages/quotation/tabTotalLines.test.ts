import { describe, it, expect } from 'vitest';
import { sumTabColumns } from './tabTotalLines';

const sub = {
  '投料#材料费': 40, '投料#加工费': 22, 'TOULIAO#材料费': 40, 'TOULIAO#加工费': 22,
  '元素#小计': 0, 'ELEM#小计': 0,
};

describe('sumTabColumns（仅汇总 is_amount && is_subtotal 列）', () => {
  it('多金额列之和', () => {
    const comp = {
      tabName: '投料', componentCode: 'TOULIAO',
      fields: [
        { name: '材料费', is_subtotal: true, is_amount: true },
        { name: '加工费', is_subtotal: true, is_amount: true },
        { name: '数量' },
      ],
    };
    expect(sumTabColumns(comp as any, sub)).toBe(62);
  });

  it('小计但非金额的列不计入', () => {
    const comp = {
      tabName: '投料', componentCode: 'TOULIAO',
      fields: [
        { name: '材料费', is_subtotal: true, is_amount: true },   // 计入
        { name: '加工费', is_subtotal: true },                     // 仅小计、非金额 → 不计入
      ],
    };
    expect(sumTabColumns(comp as any, sub)).toBe(40);
  });

  it('M1 保险：is_amount=true 但 is_subtotal=false 不计入', () => {
    const comp = {
      tabName: '投料', componentCode: 'TOULIAO',
      fields: [{ name: '材料费', is_amount: true }],  // 无 is_subtotal → 不计入
    };
    expect(sumTabColumns(comp as any, sub)).toBe(0);
  });

  it('componentCode 缺失时回退 tabName 键', () => {
    const comp = { tabName: '投料', fields: [{ name: '材料费', is_subtotal: true, is_amount: true }] };
    expect(sumTabColumns(comp as any, sub)).toBe(40);
  });

  it('无金额列 → 0', () => {
    const comp = { tabName: '来料', componentCode: 'LL', fields: [{ name: '品名' }, { name: '规格' }] };
    expect(sumTabColumns(comp as any, sub)).toBe(0);
  });

  it('键缺失时计 0', () => {
    const comp = { tabName: '未知', componentCode: 'X', fields: [{ name: '小计', is_subtotal: true, is_amount: true }] };
    expect(sumTabColumns(comp as any, sub)).toBe(0);
  });

  it('undefined 组件 → 0', () => {
    expect(sumTabColumns(undefined, sub)).toBe(0);
  });
});
