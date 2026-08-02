import { describe, it, expect } from 'vitest';
import { buildComparisonStatusLabel } from './reviewStatusLabel';

describe('buildComparisonStatusLabel（屏3 比对状态标记，api.md §2.1 / fronttask §2.1）', () => {
  it('全通过：breachedCount=0 且 amberCount=0 → "✓ N 列全通过"', () => {
    const r = buildComparisonStatusLabel({ columnCount: 3, breachedCount: 0, amberCount: 0, missingCount: 0 });
    expect(r.text).toBe('✓ 3 列全通过');
    expect(r.hasBreached).toBe(false);
  });

  it('有橙无红：仅显示橙色计数，不标红', () => {
    const r = buildComparisonStatusLabel({ columnCount: 3, breachedCount: 0, amberCount: 1, missingCount: 0 });
    expect(r.text).toBe('🟠1 / 3列');
    expect(r.hasBreached).toBe(false);
  });

  it('红橙都有：红橙分开计数，不用「M/N 列跌破」旧写法', () => {
    const r = buildComparisonStatusLabel({ columnCount: 3, breachedCount: 1, amberCount: 1, missingCount: 0 });
    expect(r.text).toBe('🔴1 🟠1 / 3列');
    expect(r.hasBreached).toBe(true);
  });

  it('缺核价数据：missingCount>0 时追加「⚪K」', () => {
    const r = buildComparisonStatusLabel({ columnCount: 4, breachedCount: 1, amberCount: 0, missingCount: 1 });
    expect(r.text).toBe('🔴1 🟠0 / 4列 ⚪1');
    expect(r.hasBreached).toBe(true);
  });

  it('原型屏3第5行 10120240 样例：产品总价健康(仅自定义列卖穿)仍应 breachedCount>0 → 标红', () => {
    // 该样例的关键点是 rowRed 由服务端给（本函数不产出 rowRed），这里只验证文案不掩盖 breached
    const r = buildComparisonStatusLabel({ columnCount: 3, breachedCount: 1, amberCount: 0, missingCount: 0 });
    expect(r.hasBreached).toBe(true);
    expect(r.text).toContain('🔴1');
  });
});
