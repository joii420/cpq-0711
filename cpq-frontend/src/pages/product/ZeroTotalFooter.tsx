// ─────────────────────────────────────────────────────────────────────────────
// ZeroTotalFooter —— 空列表的「共 0 条」兜底（task-260903 · AC-13）
//
// 单独成文件的原因：eslint `react-refresh/only-export-components` 不允许一个文件
// 同时导出组件与普通函数，而 `productHubCells.tsx` 里全是普通渲染函数。
// ─────────────────────────────────────────────────────────────────────────────
import React from 'react';

/**
 * 空列表的「共 0 条」兜底（AC-13）。
 *
 * 🚨 组件库能力所限的等价实现，**必须保留**：
 *    antd 6 的 `InternalTable` 在 `if (pagination !== false && mergedPagination?.total)` 处硬判真值，
 *    `total = 0` 是 falsy ⇒ **分页器整个不渲染**，`.ant-pagination-total-text` 根本不存在。
 *    而 AC-13 / 原型「列表-空态」要求空态下仍显示「共 0 条」。
 *    ⇒ 只在 `total === 0` 时补一行同位置、同文案的总数行；`total > 0` 时交回 antd 分页器，
 *      两者互斥，不会重复渲染。
 *    （未做假的禁用翻页箭头：那属于原型没画到之外的自由发挥，且对用户零价值。）
 */
function ZeroTotalFooter({ total, loading }: { total: number; loading?: boolean }): React.ReactNode {
  if (loading || total !== 0) return null;
  return (
    <div style={{ display: 'flex', justifyContent: 'flex-end', padding: '16px 0' }}>
      <span style={{ color: 'rgba(0, 0, 0, 0.65)' }}>共 0 条</span>
    </div>
  );
}

export default ZeroTotalFooter;
