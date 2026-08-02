import type { ReviewRowDTO } from '../../../types/price-adjust';

/**
 * 屏 3 · 比对状态标记的纯函数层（fronttask §2.1 / api.md §2.1）。
 * 🔒 红橙分开计数，禁用旧写法「M/N 列跌破」；🚨 breachedCount>0 才标红，
 * 不是「产品总价差异<0」——那要看服务端 rowRed 字段，本文件不产出 rowRed。
 */
export interface ComparisonStatusLabel {
  /** 展示文案，如 "🔴1 🟠1 / 3列 ⚪1" */
  text: string;
  /** true = breachedCount>0（与 row.rowRed 应恒一致，但渲染只信 row.rowRed，本字段仅供单测比对） */
  hasBreached: boolean;
}

export function buildComparisonStatusLabel(
  row: Pick<ReviewRowDTO, 'columnCount' | 'breachedCount' | 'amberCount' | 'missingCount'>,
): ComparisonStatusLabel {
  const { columnCount, breachedCount, amberCount, missingCount } = row;
  let text: string;
  if (breachedCount === 0 && amberCount === 0) {
    text = `✓ ${columnCount} 列全通过`;
  } else if (breachedCount === 0) {
    text = `🟠${amberCount} / ${columnCount}列`;
  } else {
    text = `🔴${breachedCount} 🟠${amberCount} / ${columnCount}列`;
  }
  if (missingCount > 0) {
    text += ` ⚪${missingCount}`;
  }
  return { text, hasBreached: breachedCount > 0 };
}
