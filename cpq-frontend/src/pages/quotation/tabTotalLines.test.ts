import { describe, it, expect } from 'vitest';
import { buildTabTotalLines } from './tabTotalLines';

const sub = {
  '投料#材料费': 40, '投料#加工费': 22, 'TOULIAO#材料费': 40, 'TOULIAO#加工费': 22,
  '投料': 62, 'TOULIAO': 62,
  '元素#小计': 0, 'ELEM#小计': 0, '元素': 0, 'ELEM': 0,
};

describe('buildTabTotalLines', () => {
  it('每个有小计列的组件出一条 = 该组件多列之和，标签 页签·总计', () => {
    const cd = [
      { componentType: 'NORMAL', tabName: '投料', componentCode: 'TOULIAO',
        fields: [{ name: '材料费', is_subtotal: true }, { name: '加工费', is_subtotal: true }, { name: '数量' }] },
      { componentType: 'NORMAL', tabName: '元素', componentCode: 'ELEM',
        fields: [{ name: '小计', is_subtotal: true }] },
    ];
    const lines = buildTabTotalLines(cd as any, sub);
    expect(lines).toEqual([
      { label: '投料 · 总计', value: 62 },
      { label: '元素 · 总计', value: 0 },
    ]);
  });

  it('跳过 SUBTOTAL 组件与无小计列组件', () => {
    const cd = [
      { componentType: 'SUBTOTAL', tabName: '产品小计', componentCode: 'ST', fields: [] },
      { componentType: 'NORMAL', tabName: '来料', componentCode: 'LL',
        fields: [{ name: '品名' }, { name: '规格' }] },
    ];
    expect(buildTabTotalLines(cd as any, sub)).toEqual([]);
  });

  it('componentCode 缺失时回退 tabName 键', () => {
    const cd = [{ componentType: 'NORMAL', tabName: '投料', fields: [{ name: '材料费', is_subtotal: true }] }];
    expect(buildTabTotalLines(cd as any, sub)).toEqual([{ label: '投料 · 总计', value: 40 }]);
  });

  it('键缺失时该列计 0', () => {
    const cd = [{ componentType: 'NORMAL', tabName: '未知', componentCode: 'X',
      fields: [{ name: '小计', is_subtotal: true }] }];
    expect(buildTabTotalLines(cd as any, sub)).toEqual([{ label: '未知 · 总计', value: 0 }]);
  });
});
