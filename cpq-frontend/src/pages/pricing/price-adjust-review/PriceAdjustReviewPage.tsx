import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Input, Select, Checkbox, Space, Tag, Spin, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import SelectableTable, { runBatch } from '../../../components/SelectableTable';
import type { ToolbarAction } from '../../../components/SelectableTable';
import { priceAdjustService } from '../../../services/priceAdjustService';
import { buildComparisonStatusLabel } from './reviewStatusLabel';
import ReviewDetailDrawer from './ReviewDetailDrawer';
import ApproveImpactModal from './ApproveImpactModal';
import RejectReasonDrawer from './RejectReasonDrawer';
import JobProgressDrawer from '../price-adjust-jobs/JobProgressDrawer';
import type { ReviewRowDTO, ReviewStatus } from '../../../types/price-adjust';
import { formatNumber } from '../../../utils/formatNumber';
import { toDecimal, type DecimalString } from '../../../utils/precision';

const PAGE_SIZE = 20;

const REVIEW_STATUS_OPTIONS: { value: ReviewStatus; label: string }[] = [
  { value: 'PENDING', label: '待处理' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'VOIDED', label: '已作废' },
];

function fmt(v: DecimalString | null | undefined): string {
  return formatNumber(v, { isComputed: true, decimals: 2 }) ?? '—';
}

/** 🔒 全部数据列都挂同一个 onCell，实现"整行标红"——rowRed 由服务端权威给出，
 *  不在本文件重算。之所以用 onCell 而非 rowClassName：SelectableTable 未透出
 *  rowClassName 透传口（改共享组件影响面更大），onCell 是 antd Table 逐列自带的
 *  标准扩展点，零侵入达到同等视觉效果。 */
function redCell(record: ReviewRowDTO) {
  return record.rowRed ? { style: { background: '#fff1f0' } } : {};
}

/**
 * 屏 3 · 价格调整审核 · 料号待办池（fronttask §2 / api.md §2.1）。
 * 落位：定价管理 → 价格调整审核（PRICING_MANAGER / SYSTEM_ADMIN）。
 * 🔒 严格按 docs/列表操作规范.md：SelectableTable + 行内零动作按钮 + 动作全部上提工具栏。
 */
const PriceAdjustReviewPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [rows, setRows] = useState<ReviewRowDTO[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);

  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState<ReviewStatus>('PENDING');
  const [breachedOnly, setBreachedOnly] = useState(false);

  const [detailReviewId, setDetailReviewId] = useState<string | null>(null);
  const [approveRows, setApproveRows] = useState<ReviewRowDTO[] | null>(null);
  const [rejectRows, setRejectRows] = useState<ReviewRowDTO[] | null>(null);
  // 屏6 联动：通过并升版成功后拿到 jobId，立刻打开进度抽屉（fronttask §5.1）
  const [progressJobId, setProgressJobId] = useState<string | null>(null);

  const pollRef = useRef<number | null>(null);

  const load = useCallback(async (p = 1) => {
    setLoading(true);
    try {
      const res = await priceAdjustService.getReviews({
        page: p, size: PAGE_SIZE, status,
        keyword: keyword.trim() || undefined,
        breachedOnly: breachedOnly || undefined,
      });
      setRows(res.content || []);
      setTotal(res.totalElements || 0);
      setPage(p);
    } catch (e: any) {
      message.error(e?.message || '加载待办池失败');
    } finally {
      setLoading(false);
    }
  }, [status, keyword, breachedOnly]);

  useEffect(() => { load(1); }, [status, breachedOnly, load]);

  // 🆕 预算中间态（E14-3）：页面里有 QUEUED/COMPUTING 行时轮询，算完自动转可审，无需用户手动刷新
  useEffect(() => {
    const hasBudgeting = rows.some((r) => r.budgetStatus === 'QUEUED' || r.budgetStatus === 'COMPUTING');
    if (pollRef.current) { window.clearInterval(pollRef.current); pollRef.current = null; }
    if (hasBudgeting) {
      pollRef.current = window.setInterval(() => load(page), 5000);
    }
    return () => { if (pollRef.current) window.clearInterval(pollRef.current); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, page]);

  const handleQuery = () => load(1);
  const handleReset = () => { setKeyword(''); setBreachedOnly(false); setStatus('PENDING'); };

  const handleRecomputeOne = async (reviewId: string) => {
    try {
      await priceAdjustService.recomputeBudget(reviewId);
      message.success('已提交重算，预算完成后自动刷新');
      load(page);
    } catch (e: any) {
      message.error(e?.message || '提交重算失败');
    }
  };

  const columns: ColumnsType<ReviewRowDTO> = [
    { title: '客户', dataIndex: 'customerName', width: 140, onCell: redCell },
    {
      title: '料号', dataIndex: 'materialNo', width: 130, onCell: redCell,
      render: (v: string, r) => (
        // 🔒 抽屉入口 = 料号链接，须 stopPropagation，避免同时触发行选中切换
        <a onClick={(e) => { e.stopPropagation(); setDetailReviewId(r.reviewId); }} style={{ fontFamily: 'monospace' }}>{v}</a>
      ),
    },
    { title: '料号名称', dataIndex: 'materialName', width: 140, onCell: redCell },
    {
      title: '当前版本 → 目标版本', width: 190, onCell: redCell,
      render: (_: unknown, r) => <span style={{ fontFamily: 'monospace', fontSize: 12.5 }}>{r.currentVersionNo || '（首次）'} → {r.targetVersionNo}</span>,
    },
    {
      title: '依据单号 / 日期', width: 160, onCell: redCell,
      render: (_: unknown, r) => (
        <div style={{ fontSize: 12.5 }}>
          <div>{r.basisQuotationNo || '—'}</div>
          <div style={{ color: 'rgba(0,0,0,.45)' }}>{r.basisQuotationDate ? dayjs(r.basisQuotationDate).format('YYYY-MM-DD') : ''}</div>
        </div>
      ),
    },
    {
      title: '报价侧成本(现→调整后)', width: 170, align: 'right' as const, onCell: redCell,
      render: (_: unknown, r) => <span>{fmt(r.quoteCostCurrent)} → <b>{fmt(r.quoteCostAdjusted)}</b></span>,
    },
    { title: '核价侧成本', dataIndex: 'costingCost', width: 110, align: 'right' as const, onCell: redCell, render: (v: DecimalString | null) => fmt(v) },
    {
      title: '差异', dataIndex: 'diffAdjusted', width: 100, align: 'right' as const, onCell: redCell,
      render: (v: DecimalString | null) => <span style={{ color: v != null && toDecimal(v).isNegative() ? '#cf1322' : undefined }}>{fmt(v)}</span>,
    },
    {
      title: '比对状态', width: 190, onCell: redCell,
      render: (_: unknown, r) => {
        if (r.budgetStatus === 'QUEUED' || r.budgetStatus === 'COMPUTING') {
          return <span><Spin size="small" style={{ marginRight: 6 }} />预算计算中</span>;
        }
        if (r.budgetStatus === 'FAILED') {
          return (
            <span>
              <Tag color="red">预算失败</Tag>
              <a onClick={(e) => { e.stopPropagation(); handleRecomputeOne(r.reviewId); }}>重算</a>
            </span>
          );
        }
        const label = buildComparisonStatusLabel(r);
        return <span style={{ fontWeight: r.rowRed ? 600 : undefined }}>{label.text}</span>;
      },
    },
    {
      title: '审核状态', dataIndex: 'reviewStatus', width: 100, onCell: redCell,
      render: (v: ReviewStatus) => {
        const map: Record<ReviewStatus, { color: string; label: string }> = {
          PENDING: { color: 'orange', label: '待处理' },
          APPROVED: { color: 'green', label: '已通过' },
          REJECTED: { color: 'default', label: '已驳回' },
          VOIDED: { color: 'default', label: '已作废' },
        };
        return <Tag color={map[v]?.color}>{map[v]?.label || v}</Tag>;
      },
    },
  ];

  const allPendingReady = (selected: ReviewRowDTO[]) => {
    if (selected.length === 0) return false;
    const notPending = selected.filter((r) => r.reviewStatus !== 'PENDING');
    if (notPending.length > 0) return `含 ${notPending.length} 项非「待处理」状态，不能操作`;
    const notReady = selected.filter((r) => r.budgetStatus !== 'READY');
    if (notReady.length > 0) return `含 ${notReady.length} 项预算尚未计算完成，不能操作`;
    return true;
  };

  const actions: ToolbarAction<ReviewRowDTO>[] = [
    {
      key: 'approve',
      label: '通过并升版',
      enabledWhen: allPendingReady,
      onClick: async (selected) => setApproveRows(selected),
    },
    {
      key: 'reject',
      label: '驳回',
      danger: true,
      enabledWhen: allPendingReady,
      onClick: async (selected) => setRejectRows(selected),
    },
    {
      key: 'recompute',
      label: '重算预算',
      enabledWhen: (selected) => selected.some((r) => r.budgetStatus === 'FAILED') ? true : '选中项需包含「预算失败」的料号',
      onClick: async (selected) => {
        const failed = selected.filter((r) => r.budgetStatus === 'FAILED');
        await runBatch(failed, (r) => priceAdjustService.recomputeBudget(r.reviewId), {
          rowLabel: (r) => `${r.customerName} · ${r.materialNo}`,
          successMsg: `已提交 ${failed.length} 项重算`,
        });
        load(page);
      },
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 12, display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
        <Input placeholder="搜索客户 / 料号 / 料号名称" style={{ width: 240 }} value={keyword}
          onChange={(e) => setKeyword(e.target.value)} onPressEnter={handleQuery} allowClear />
        <Select style={{ width: 130 }} value={status} onChange={setStatus} options={REVIEW_STATUS_OPTIONS} />
        <Checkbox checked={breachedOnly} onChange={(e) => setBreachedOnly(e.target.checked)}>只看标红</Checkbox>
        <Space>
          <a onClick={handleQuery}>查询</a>
          <a onClick={handleReset}>重置</a>
        </Space>
      </div>

      <SelectableTable<ReviewRowDTO>
        rowKey="reviewId"
        columns={columns}
        dataSource={rows}
        loading={loading}
        actions={actions}
        rowLabel={(r) => `${r.customerName} · ${r.materialNo} ${r.materialName}`}
        scroll={{ x: 'max-content' }}
        pagination={{
          current: page, pageSize: PAGE_SIZE, total, size: 'small',
          onChange: (p) => load(p), showTotal: (t) => `共 ${t} 条`,
        }}
      />

      <ReviewDetailDrawer open={!!detailReviewId} reviewId={detailReviewId} onClose={() => setDetailReviewId(null)} />

      <ApproveImpactModal
        open={!!approveRows}
        rows={approveRows || []}
        onClose={() => setApproveRows(null)}
        onApproved={() => { setApproveRows(null); load(page); }}
        onJobCreated={(jobId) => setProgressJobId(jobId)}
      />

      <RejectReasonDrawer
        open={!!rejectRows}
        rows={rejectRows || []}
        onClose={() => setRejectRows(null)}
        onSubmitted={() => { setRejectRows(null); load(page); }}
      />

      <JobProgressDrawer open={!!progressJobId} jobId={progressJobId} onClose={() => setProgressJobId(null)} />
    </div>
  );
};

export default PriceAdjustReviewPage;
