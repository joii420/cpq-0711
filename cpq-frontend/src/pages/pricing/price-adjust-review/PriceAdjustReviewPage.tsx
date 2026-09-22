import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Input, Select, Checkbox, Space, Tag, Spin, Alert, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import SelectableTable, { runBatch } from '../../../components/SelectableTable';
import type { ToolbarAction } from '../../../components/SelectableTable';
import { priceAdjustService } from '../../../services/priceAdjustService';
import ReviewStatusCell from './ReviewStatusCell';
import ReviewDetailDrawer from './ReviewDetailDrawer';
import ApproveImpactModal from './ApproveImpactModal';
import RejectReasonDrawer from './RejectReasonDrawer';
import JobProgressDrawer from '../price-adjust-jobs/JobProgressDrawer';
import type { ReviewRowDTO, ReviewStatus } from '../../../types/price-adjust';
import { formatNumber } from '../../../utils/formatNumber';
import { DISPLAY_SCALE, toDecimal, type DecimalString } from '../../../utils/precision';
import {
  approveEnabledWhen, approveHint, computeNowAndWait, createLatestGate, loadLatest, nextListPollDelay, rejectEnabledWhen,
  MSG_COMPUTE_SLOW, MSG_NOT_PENDING, MSG_SUPERSEDED,
  type ComputeOutcome,
} from './reviewCompute';

const PAGE_SIZE = 20;

const REVIEW_STATUS_OPTIONS: { value: ReviewStatus; label: string }[] = [
  { value: 'PENDING', label: '待处理' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'VOIDED', label: '已作废' },
];

/**
 * 列表金额的唯一展示口径：最多 DISPLAY_SCALE(9) 位，尾零自动去掉。
 * 🔒 与 ReviewDetailDrawer 的 fmt 同口径——同一个数在列表与抽屉里必须显示成同一个样子。
 * 🚫 不要改回字面量位数（曾写死 2，把 '1.717061326' 截成 '1.72'）。
 */
function fmt(v: DecimalString | null | undefined): string {
  return formatNumber(v, { isComputed: true, decimals: DISPLAY_SCALE }) ?? '—';
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

  // task-260920 F-1 / F-6: server-authoritative counters (api.md §1.1) — never computed from this page
  const [notComputedTotal, setNotComputedTotal] = useState(0);
  const [excludedByNotComputed, setExcludedByNotComputed] = useState(0);
  // AC-9: after cancelling the confirm dialog, tell the user the freshly computed rows were kept
  const [keptNotice, setKeptNotice] = useState<number | null>(null);

  const [detailReviewId, setDetailReviewId] = useState<string | null>(null);
  const [detailRow, setDetailRow] = useState<ReviewRowDTO | null>(null);
  const [approveRows, setApproveRows] = useState<ReviewRowDTO[] | null>(null);
  const [rejectRows, setRejectRows] = useState<ReviewRowDTO[] | null>(null);
  // 屏6 联动：通过并升版成功后拿到 jobId，立刻打开进度抽屉（fronttask §5.1）
  const [progressJobId, setProgressJobId] = useState<string | null>(null);

  const pollRef = useRef<number | null>(null);
  const [pageHidden, setPageHidden] = useState(() => typeof document !== 'undefined' && document.hidden);

  /**
   * 🔒 F-7 / AC-30: every list load (first screen, typing a keyword, 查询, paging, 只看标红, silent poll,
   * refresh after a failure / approve / reject) goes through this one function, and only the most recently
   * started request may write the table, the header count, the counters and the page number. An older
   * request answering late (e.g. the first-screen full list arriving after a keyword search, or the
   * StrictMode duplicate) is discarded — including its failure, which then shows no error.
   */
  const listGateRef = useRef(createLatestGate());
  const load = useCallback(async (p = 1, opts?: { silent?: boolean }) => {
    if (!opts?.silent) setLoading(true);
    await loadLatest(listGateRef.current, () => priceAdjustService.getReviews({
      page: p, size: PAGE_SIZE, status,
      keyword: keyword.trim() || undefined,
      breachedOnly: breachedOnly || undefined,
    }), {
      onData: (res) => {
        setRows(res.content || []);
        setTotal(res.totalElements || 0);
        setNotComputedTotal(res.notComputedTotal ?? 0);
        setExcludedByNotComputed(res.excludedByNotComputed ?? 0);
        setPage(p);
      },
      onError: (e: any) => {
        if (!opts?.silent) message.error(e?.message || '加载待办池失败');
      },
      // latest request only: clears a spinner left by an earlier non-silent request that went stale
      onSettled: () => setLoading(false),
    });
  }, [status, keyword, breachedOnly]);

  useEffect(() => { load(1); }, [status, breachedOnly, load]);

  // F-3 ③: pause polling while the tab is hidden, resume when it becomes visible again
  useEffect(() => {
    const onVis = () => setPageHidden(document.hidden);
    document.addEventListener('visibilitychange', onVis);
    return () => document.removeEventListener('visibilitychange', onVis);
  }, []);

  /**
   * 🔒 F-3 polling cadence (task-260920). The old rule「any QUEUED/COMPUTING row → full list every 5 s」
   * turned into a 15-minute storm right after generation (every row starts QUEUED). Cadence is now
   * decided by nextListPollDelay: COMPUTING → 5 s · only QUEUED → 30 s · hidden / non-PENDING filter → none.
   * One-shot setTimeout re-armed after every refresh (a slow response can't pile requests up).
   */
  useEffect(() => {
    if (pollRef.current) { window.clearTimeout(pollRef.current); pollRef.current = null; }
    const delay = nextListPollDelay({ statusFilter: status, rows, notComputedTotal, hidden: pageHidden });
    if (delay != null) {
      pollRef.current = window.setTimeout(() => load(page, { silent: true }), delay);
    }
    return () => { if (pollRef.current) { window.clearTimeout(pollRef.current); pollRef.current = null; } };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [rows, page, status, notComputedTotal, pageHidden]);

  // ── F-2: the page's single "compute one row" entry (list「计算」link, drawer, batch approve) ──
  const inflightRef = useRef(new Map<string, Promise<ComputeOutcome>>());
  const replaceRow = useCallback((next: ReviewRowDTO) => {
    setRows((prev) => prev.map((r) => (r.reviewId === next.reviewId ? { ...r, ...next } : r)));
  }, []);

  /**
   * 🔒 The caller's abort signal is deliberately NOT forwarded: once compute-now is accepted the server
   * is computing anyway, so the page keeps polling and still replaces the row in place even if the
   * drawer was closed / the batch was cancelled. Callers just ignore a late result themselves.
   */
  const computeRow = useCallback((row: ReviewRowDTO, mode: 'compute' | 'waitOnly'): Promise<ComputeOutcome> => {
    // 🔒 dedupe: a second click / the drawer on the same row joins the running computation
    const running = inflightRef.current.get(row.reviewId);
    if (running) return running;
    // row shows「计算中」immediately and its「计算」link disappears (no double click)
    setRows((prev) => prev.map((r) => (r.reviewId === row.reviewId ? { ...r, budgetStatus: 'COMPUTING' } : r)));
    const p = computeNowAndWait(row.reviewId, {
      computeNow: priceAdjustService.computeNow,
      getReviewRow: priceAdjustService.getReviewRow,
    }, { mode }).then((o) => {
      inflightRef.current.delete(row.reviewId);
      if (o.kind === 'DONE') {
        replaceRow(o.row); // 🔒 in-place replace, not load(page)
      } else if (o.kind === 'NOT_PENDING') {
        message.warning(o.superseded ? MSG_SUPERSEDED : MSG_NOT_PENDING);
        load(page, { silent: true });
      } else if (o.kind === 'TIMEOUT') {
        if (o.row) replaceRow(o.row);
        message.info(MSG_COMPUTE_SLOW);
      } else if (o.kind === 'ERROR') {
        message.error(o.message);
        load(page, { silent: true });
      }
      return o;
    });
    inflightRef.current.set(row.reviewId, p);
    return p;
  }, [replaceRow, load, page]);

  const openDetail = (r: ReviewRowDTO) => {
    setDetailRow(r);
    setDetailReviewId(r.reviewId);
  };

  const handleQuery = () => load(1);
  const handleReset = () => { setKeyword(''); setBreachedOnly(false); setStatus('PENDING'); };

  // F-2: the FAILED row's inline「重算」now goes through compute-now (explicit user click)
  const handleRecomputeOne = (r: ReviewRowDTO) => { computeRow(r, 'compute'); };

  const columns: ColumnsType<ReviewRowDTO> = [
    { title: '客户', dataIndex: 'customerName', width: 140, onCell: redCell },
    {
      title: '料号', dataIndex: 'materialNo', width: 130, onCell: redCell,
      render: (v: string, r) => (
        // 🔒 抽屉入口 = 料号链接，须 stopPropagation，避免同时触发行选中切换
        <a onClick={(e) => { e.stopPropagation(); openDetail(r); }} style={{ fontFamily: 'monospace' }}>{v}</a>
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
      render: (_: unknown, r) => (r.reviewStatus === 'PENDING' && r.budgetStatus === 'COMPUTING'
        ? <Spin size="small" />
        : <span>{fmt(r.quoteCostCurrent)} → <b>{fmt(r.quoteCostAdjusted)}</b></span>),
    },
    { title: '核价侧成本', dataIndex: 'costingCost', width: 110, align: 'right' as const, onCell: redCell, render: (v: DecimalString | null) => fmt(v) },
    {
      title: '差异', dataIndex: 'diffAdjusted', width: 100, align: 'right' as const, onCell: redCell,
      render: (v: DecimalString | null) => <span style={{ color: v != null && toDecimal(v).isNegative() ? '#cf1322' : undefined }}>{fmt(v)}</span>,
    },
    {
      title: '比对状态', width: 190, onCell: redCell,
      render: (_: unknown, r) => (
        <ReviewStatusCell row={r} onCompute={(x) => computeRow(x, 'compute')} onRecompute={handleRecomputeOne} />
      ),
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

  // F-4: the former shared allPendingReady is split into approveEnabledWhen / rejectEnabledWhen (reviewCompute.ts)
  const actions: ToolbarAction<ReviewRowDTO>[] = [
    {
      key: 'approve',
      label: '通过并升版',
      enabledWhen: approveEnabledWhen,
      onClick: async (selected) => { setKeptNotice(null); setApproveRows(selected); },
    },
    {
      key: 'reject',
      label: '驳回',
      danger: true,
      enabledWhen: rejectEnabledWhen,
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
        {status === 'PENDING' && notComputedTotal > 0 && (
          <span style={{ fontSize: 12.5, color: 'rgba(0,0,0,.45)', marginLeft: 4 }}>
            共 {total} 条，其中 <b style={{ color: '#d46b08' }}>{notComputedTotal} 条未计算</b>，正在后台计算…
          </span>
        )}
      </div>

      {breachedOnly && excludedByNotComputed > 0 && (
        <Alert
          type="warning"
          style={{ marginBottom: 12 }}
          message={<span>⚠️ 另有 <b>{excludedByNotComputed}</b> 个料号<b>尚未计算</b>，不参与本次筛选 —— 它们可能也超阈值。可点某个料号单独计算，或等后台算完。</span>}
        />
      )}

      {keptNotice != null && keptNotice > 0 && (
        <Alert
          type="info"
          closable
          onClose={() => setKeptNotice(null)}
          style={{ marginBottom: 12 }}
          message={<span>已取消升版。<b>刚才计算的 {keptNotice} 个料号结果已保留</b>，可直接再次勾选提交。</span>}
        />
      )}

      <SelectableTable<ReviewRowDTO>
        rowKey="reviewId"
        columns={columns}
        dataSource={rows}
        loading={loading}
        actions={actions}
        actionHint={(selected) => {
          const n = approveHint(selected);
          return n == null ? null : (
            <span style={{ fontSize: 12.5, color: 'rgba(0,0,0,.45)', marginLeft: 4 }}>
              其中 <b style={{ color: '#d46b08' }}>{n} 项未计算</b>，点通过会先计算
            </span>
          );
        }}
        rowLabel={(r) => `${r.customerName} · ${r.materialNo} ${r.materialName}`}
        scroll={{ x: 'max-content' }}
        pagination={{
          current: page, pageSize: PAGE_SIZE, total, size: 'small',
          onChange: (p) => load(p), showTotal: (t) => `共 ${t} 条`,
        }}
      />

      <ReviewDetailDrawer
        open={!!detailReviewId}
        reviewId={detailReviewId}
        row={detailRow}
        computeRow={computeRow}
        onClose={() => { setDetailReviewId(null); setDetailRow(null); }}
      />

      <ApproveImpactModal
        open={!!approveRows}
        rows={approveRows || []}
        computeRow={(r) => computeRow(r, 'compute')}
        onClose={(computedCount) => { setApproveRows(null); setKeptNotice(computedCount > 0 ? computedCount : null); }}
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
