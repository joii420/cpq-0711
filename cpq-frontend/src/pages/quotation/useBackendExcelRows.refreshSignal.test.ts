import { describe, it, expect } from 'vitest';
import { excelRefreshSignal } from './useBackendExcelRows';
import type { LineItem } from './QuotationStep2';

/**
 * Excel 视图取数刷新信号（取值时机修复）单测。
 *
 * 约定：用户在产品卡片改任意数据触发公式计算 → 后端重算+物化该料号 row_data → 前端 patch
 * quoteValuesAt → excelRefreshSignal 变化 → useBackendExcelRows 重取最新 row_data。
 * 故核心不变量：编辑落库（quoteValuesAt 变）后信号必须变化。
 */
const li = (over: Partial<LineItem>): LineItem => ({
  id: 'L1', productPartNo: 'P1', productName: 'p', productId: 'pid',
  templateId: 't', templateName: 'tn', productAttributeValues: {},
  componentData: [], subtotal: 0, ...over,
} as LineItem);

describe('excelRefreshSignal', () => {
  it('编辑落库（quoteValuesAt 变化）→ 信号变化', () => {
    const before = excelRefreshSignal([li({ quoteValuesAt: '2026-06-21T06:26:43Z' })]);
    const after = excelRefreshSignal([li({ quoteValuesAt: '2026-06-21T06:27:50Z' })]);
    expect(after).not.toBe(before);
  });

  it('无编辑（同 quoteValuesAt）→ 信号稳定（不触发多余重取）', () => {
    const a = excelRefreshSignal([li({ quoteValuesAt: 'T1' })]);
    const b = excelRefreshSignal([li({ quoteValuesAt: 'T1' })]);
    expect(b).toBe(a);
  });

  it('无 quoteValuesAt 时回退 quoteCardValues 内容长度（卡片值变化仍能反映）', () => {
    const s1 = excelRefreshSignal([li({ quoteCardValues: '{"tabs":[]}' })]);
    const s2 = excelRefreshSignal([li({ quoteCardValues: '{"tabs":[{"x":1}]}' })]);
    expect(s2).not.toBe(s1);
  });

  it('多 lineItem：任一料号落库变化 → 整体信号变化', () => {
    const base = [li({ id: 'A', quoteValuesAt: 'T1' }), li({ id: 'B', quoteValuesAt: 'T1' })];
    const edited = [li({ id: 'A', quoteValuesAt: 'T1' }), li({ id: 'B', quoteValuesAt: 'T2' })];
    expect(excelRefreshSignal(edited)).not.toBe(excelRefreshSignal(base));
  });

  it('空/undefined → 空字符串（不抛错）', () => {
    expect(excelRefreshSignal([])).toBe('');
    expect(excelRefreshSignal(undefined)).toBe('');
  });
});
