import React from 'react';
import { Tag, Spin, Tooltip } from 'antd';
import type { ReviewRowDTO } from '../../../types/price-adjust';
import { buildComparisonStatusLabel } from './reviewStatusLabel';

/**
 * 「比对状态」列单元格（task-260920 F-1；原型 审核列表-未计算态.html 状态 1 / 6）。
 * Extracted from PriceAdjustReviewPage so it can be rendered in isolation by the vitest SSR tests.
 *
 * 🔒 AC-27 / R-2: budgetStatus only means something on PENDING rows —
 *   non-PENDING: READY → the existing comparison label; anything else →「—」(🚫「未计算 / 计算」).
 *   PENDING: QUEUED → static grey「未计算」+「计算」link (🚫 spinner) · COMPUTING → Spin +「计算中」·
 *            FAILED → existing「预算失败」(+ reason tooltip) +「重算」· READY → existing label.
 */
const ReviewStatusCell: React.FC<{
  row: ReviewRowDTO;
  onCompute: (row: ReviewRowDTO) => void;
  onRecompute: (row: ReviewRowDTO) => void;
}> = ({ row: r, onCompute, onRecompute }) => {
  if (r.reviewStatus !== 'PENDING') {
    if (r.budgetStatus !== 'READY') return <span style={{ color: 'rgba(0,0,0,.45)' }}>—</span>;
    const label = buildComparisonStatusLabel(r);
    return <span style={{ fontWeight: r.rowRed ? 600 : undefined }}>{label.text}</span>;
  }
  if (r.budgetStatus === 'QUEUED') {
    return (
      <span>
        <Tag>未计算</Tag>
        <a onClick={(e) => { e.stopPropagation(); onCompute(r); }}>计算</a>
      </span>
    );
  }
  if (r.budgetStatus === 'COMPUTING') {
    return <span><Spin size="small" style={{ marginRight: 6 }} />计算中</span>;
  }
  if (r.budgetStatus === 'FAILED') {
    // repair-260918 F-3 (AC-26): hover shows the budgetError text verbatim; no reason => no tooltip.
    const failTag = <Tag color="red">预算失败</Tag>;
    return (
      <span>
        {r.budgetError?.trim() ? <Tooltip title={r.budgetError}>{failTag}</Tooltip> : failTag}
        <a onClick={(e) => { e.stopPropagation(); onRecompute(r); }}>重算</a>
      </span>
    );
  }
  const label = buildComparisonStatusLabel(r);
  return <span style={{ fontWeight: r.rowRed ? 600 : undefined }}>{label.text}</span>;
};

export default ReviewStatusCell;
