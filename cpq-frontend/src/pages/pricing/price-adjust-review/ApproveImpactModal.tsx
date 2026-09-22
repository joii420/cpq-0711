import React, { useEffect, useRef, useState } from 'react';
import { Modal, Button, Spin, Alert, Tag, Descriptions, Table, Progress, message } from 'antd';
import { priceAdjustService } from '../../../services/priceAdjustService';
import type { ReviewRowDTO, ImpactPreviewDTO, ImpactMaterialDTO } from '../../../types/price-adjust';
import { formatNumber } from '../../../utils/formatNumber';
import { DISPLAY_SCALE, toDecimal, type DecimalString } from '../../../utils/precision';
import { MISSING_SIDE_LABEL } from './ReviewDetailDrawer';
import {
  MSG_COMPUTE_SLOW, MSG_NOT_PENDING, MSG_SUPERSEDED, rowsNeedingCompute, runSequentialCompute,
  type ComputeOutcome,
} from './reviewCompute';

export interface ApproveImpactModalProps {
  open: boolean;
  /** everything the user selected (may contain QUEUED / FAILED rows — they are computed first, F-4) */
  rows: ReviewRowDTO[];
  /**
   * task-260920 F-4: the page's single compute routine (replaces the list row in place).
   * Called strictly one row at a time — never in parallel (J-2).
   */
  computeRow?: (row: ReviewRowDTO, signal: { aborted: boolean }) => Promise<ComputeOutcome>;
  /** computedCount = rows that became READY in step 1 of this run (for the AC-9「结果已保留」notice) */
  onClose: (computedCount: number) => void;
  onApproved: () => void;
  /** 屏6 联动：approve 响应带回 jobId，交给上层立刻打开进度抽屉（fronttask §5.1）。 */
  onJobCreated?: (jobId: string) => void;
}

const STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿', SUBMITTED: '已提交', APPROVED: '已审批', COSTING_APPROVED: '核价已批', PENDING_REVIEW: '待审',
  SENT: '已发送', ACCEPTED: '已接受', EXPIRED: '已过期', CANCELLED: '已取消', REJECTED: '已驳回', COSTING_REJECTED: '核价驳回',
};

/** 同列表 / 抽屉的唯一金额口径（DISPLAY_SCALE 去尾零），🚫 二次运算、🚫 改位数（F-5 / AC-10）。 */
function fmt(v: DecimalString | null | undefined): string {
  return formatNumber(v, { isComputed: true, decimals: DISPLAY_SCALE }) ?? '—';
}

export type BatchRowState = 'WAITING' | 'COMPUTING' | 'DONE' | 'FAILED';

export interface BatchFailure {
  reviewId: string;
  materialNo: string;
  reason: string;
}

/** Reason text for a row that did not end READY in step 1 (fed to the「计算失败」block). */
export function failureReason(outcome: ComputeOutcome): string {
  switch (outcome.kind) {
    case 'DONE': return outcome.row.budgetError?.trim() || '计算失败';
    case 'NOT_PENDING': return outcome.superseded ? MSG_SUPERSEDED : MSG_NOT_PENDING;
    case 'TIMEOUT': return MSG_COMPUTE_SLOW;
    case 'ABORTED': return '已取消';
    case 'ERROR': return outcome.message;
  }
}

/**
 * 🔒 The ONLY source of the reviewIds sent to impact / approve (F-5, AC-11 ②):
 * rows already READY + rows whose step-1 outcome is DONE/READY. Everything else becomes a failure.
 */
export function partitionAfterCompute(rows: ReviewRowDTO[], outcomes: Map<string, ComputeOutcome>):
  { ready: ReviewRowDTO[]; failures: BatchFailure[] } {
  const ready: ReviewRowDTO[] = [];
  const failures: BatchFailure[] = [];
  for (const r of rows) {
    const o = outcomes.get(r.reviewId);
    if (!o) {
      if (r.budgetStatus === 'READY') ready.push(r);
      else failures.push({ reviewId: r.reviewId, materialNo: r.materialNo, reason: '计算失败' });
      continue;
    }
    if (o.kind === 'DONE' && o.row.budgetStatus === 'READY') ready.push({ ...r, ...o.row });
    else failures.push({ reviewId: r.reviewId, materialNo: r.materialNo, reason: failureReason(o) });
  }
  return { ready, failures };
}

/** 新增块 ②（放最上方）：n 个料号计算失败，不参与本次升版（原型第 3 步）。 */
export const FailedMaterialsBlock: React.FC<{ failures: BatchFailure[]; selectedCount: number; readyCount: number }> = ({
  failures, selectedCount, readyCount,
}) => (
  <Alert
    type="error"
    showIcon
    style={{ marginBottom: 16 }}
    message={<b>{failures.length} 个料号计算失败，不参与本次升版</b>}
    description={
      <div>
        <ul style={{ margin: '6px 0 0 18px', padding: 0 }}>
          {failures.map((f) => (
            <li key={f.reviewId}><span style={{ fontFamily: 'monospace' }}>{f.materialNo}</span> —— {f.reason}</li>
          ))}
        </ul>
        <div style={{ marginTop: 6 }}>
          它们仍留在待办池，可稍后在列表点「重算」。本次勾选 {selectedCount} 个，实际升版 <b>{readyCount}</b> 个。
        </div>
      </div>
    }
  />
);

/**
 * 新增块 ①（放「按状态分组」之后）：各料号金额（判断依据单 · 报价侧）。
 * 🔒 按缺失侧显示：缺核价侧时报价侧两个值照常显示，只有差异列写「—（缺数据：核价侧）」。
 * 差异标红 = 该料号在服务端给的 breachedMaterials 里（原型第 2 步三个红值恰是跌破预警线的三个料号），
 * 🚫 前端自己按阈值判。
 */
export const MaterialAmountTable: React.FC<{ materials: ImpactMaterialDTO[]; breachedNos: Set<string> }> = ({ materials, breachedNos }) => {
  const muted: React.CSSProperties = { color: 'rgba(0,0,0,.45)' };
  const columns = [
    { title: '料号', dataIndex: 'materialNo', render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span> },
    {
      title: '报价侧·现', dataIndex: 'quoteCostCurrent', align: 'right' as const,
      render: (v: DecimalString | null) => <span style={v == null ? muted : undefined}>{fmt(v)}</span>,
    },
    {
      title: '报价侧·调整后', dataIndex: 'quoteCostAdjusted', align: 'right' as const,
      render: (v: DecimalString | null) => (v == null ? <span style={muted}>—</span> : <b>{fmt(v)}</b>),
    },
    {
      title: '差异', dataIndex: 'diffAdjusted', align: 'right' as const,
      render: (v: DecimalString | null, m: ImpactMaterialDTO) => {
        if (m.status === 'MISSING') return <span style={muted}>—（缺数据{m.missingSide ? `：${MISSING_SIDE_LABEL[m.missingSide]}` : ''}）</span>;
        if (m.status === 'STALE') return <span style={muted}>已失效</span>;
        if (v == null) return <span style={muted}>—</span>;
        if (toDecimal(v).isZero()) return <span style={muted}>{fmt(v)}</span>;
        return <span style={breachedNos.has(m.materialNo) ? { color: '#cf1322' } : undefined}>{fmt(v)}</span>;
      },
    },
  ];
  return (
    <div style={{ marginBottom: 16 }}>
      <div style={{ marginBottom: 6, fontWeight: 600, fontSize: 13 }}>各料号金额（判断依据单 · 报价侧）</div>
      <Table<ImpactMaterialDTO> size="small" rowKey="materialNo" dataSource={materials} columns={columns} pagination={false} />
    </div>
  );
};

/** 第 1 步逐行结果单元格：等待 / 计算中 / 已完成 / 失败。 */
export const BatchStateCell: React.FC<{ state: BatchRowState }> = ({ state }) => {
  if (state === 'WAITING') return <Tag>等待</Tag>;
  if (state === 'COMPUTING') return <span><Spin size="small" style={{ marginRight: 6 }} />计算中</span>;
  if (state === 'DONE') return <Tag color="green">已完成</Tag>;
  return <Tag color="red">失败</Tag>;
};

/**
 * 屏 5 · 通过前影响面确认（720px Modal · 本页唯一允许的 Modal）。
 * task-260920：同一个 Modal 内分两段 ——
 *   第 1 步（有 QUEUED/FAILED 行时）：逐条依次计算并显示进度；🚫 升版、🚫 并发。
 *   第 2 步：现网影响面确认，区块一个不删、顺序不变，只新增「计算失败」（最上方）与「各料号金额」（按状态分组之后）。
 * 🔒 impact / approve 只收 READY 行。
 */
const ApproveImpactModal: React.FC<ApproveImpactModalProps> = ({ open, rows, computeRow, onClose, onApproved, onJobCreated }) => {
  const [phase, setPhase] = useState<'computing' | 'confirm'>('confirm');
  const [batchStates, setBatchStates] = useState<Record<string, BatchRowState>>({});
  const [batchRows, setBatchRows] = useState<ReviewRowDTO[]>([]);
  const [elapsed, setElapsed] = useState(0);
  const [readyRows, setReadyRows] = useState<ReviewRowDTO[]>([]);
  const [failures, setFailures] = useState<BatchFailure[]>([]);
  const computedCountRef = useRef(0);
  const abortRef = useRef<{ aborted: boolean }>({ aborted: false });

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [preview, setPreview] = useState<ImpactPreviewDTO | null>(null);
  const [confirming, setConfirming] = useState(false);

  const loadPreview = (ready: ReviewRowDTO[]) => {
    setPreview(null);
    setError(null);
    if (ready.length === 0) return;
    setLoading(true);
    priceAdjustService.getImpactPreview(ready.map((r) => r.reviewId))
      .then(setPreview)
      .catch((e: any) => setError(e?.message || '加载影响面预览失败'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    if (!open || rows.length === 0) return;
    const signal = { aborted: false };
    abortRef.current = signal;
    computedCountRef.current = 0;
    const todo = computeRow ? rowsNeedingCompute(rows) : [];
    if (todo.length === 0) {
      const { ready, failures: f } = partitionAfterCompute(rows, new Map());
      setReadyRows(ready);
      setFailures(f);
      setPhase('confirm');
      loadPreview(ready);
      return () => { signal.aborted = true; };
    }

    setPhase('computing');
    setBatchRows(todo);
    setBatchStates(Object.fromEntries(todo.map((r) => [r.reviewId, 'WAITING' as BatchRowState])));
    setElapsed(0);
    const t0 = Date.now();
    const ticker = window.setInterval(() => setElapsed(Math.floor((Date.now() - t0) / 1000)), 1000);

    (async () => {
      // 🔒 strictly sequential (J-2): runSequentialCompute awaits each row before sending the next compute-now.
      const outcomes = await runSequentialCompute(todo, (r) => computeRow!(r, signal), (id, st) => {
        if (st === 'DONE') computedCountRef.current += 1;
        setBatchStates((s) => ({ ...s, [id]: st }));
      }, signal);
      if (!outcomes || signal.aborted) return;
      window.clearInterval(ticker);
      const { ready, failures: f } = partitionAfterCompute(rows, outcomes);
      setReadyRows(ready);
      setFailures(f);
      setPhase('confirm');
      loadPreview(ready);
    })();

    return () => { signal.aborted = true; window.clearInterval(ticker); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, rows]);

  const handleClose = () => {
    abortRef.current.aborted = true;
    onClose(computedCountRef.current);
  };

  const handleConfirm = async () => {
    setConfirming(true);
    try {
      // 🔒 只提交 READY 行（F-5 / AC-11 ②）
      const res = await priceAdjustService.approveReviews(readyRows.map((r) => r.reviewId));
      message.success(`已提交更新任务（jobId=${res.jobId}），共 ${res.quotationCount} 张单 / ${res.itemCount} 条明细`);
      onApproved();
      onJobCreated?.(res.jobId);
    } catch (e: any) {
      message.error(e?.message || '通过并升版失败');
    } finally {
      setConfirming(false);
    }
  };

  const total = batchRows.length;
  const done = batchRows.filter((r) => batchStates[r.reviewId] === 'DONE' || batchStates[r.reviewId] === 'FAILED').length;
  const current = Math.min(done + 1, total);
  const breachedNos = new Set((preview?.breachedMaterials ?? []).map((m) => m.materialNo));

  // 🔒 ONE Modal element for both steps (switching elements would unmount/remount the dialog and flash).
  return (
    <Modal
      title={phase === 'computing' ? '正在计算选中料号的影响' : `通过前影响面确认 · 共 ${readyRows.length} 个料号`}
      open={open}
      onCancel={handleClose}
      width={720}
      destroyOnClose
      footer={phase === 'computing'
        ? [
          <Button key="cancel" onClick={handleClose}>取消</Button>,
          <Button key="next" type="primary" disabled>下一步</Button>,
        ]
        : [
          <Button key="cancel" onClick={handleClose}>取消</Button>,
          <Button key="ok" type="primary" loading={confirming} disabled={!preview || readyRows.length === 0} onClick={handleConfirm}>
            确认通过并升版
          </Button>,
        ]}
    >
      {phase === 'computing' ? (
        <>
          <Alert
            type="info"
            style={{ marginBottom: 12 }}
            message={<span>共 {rows.length} 个料号，其中 <b>{total} 个</b>尚未计算金额。正在计算，完成后会显示影响面供你确认。</span>}
          />
          <div><Spin size="small" style={{ marginRight: 6 }} />正在计算 <b>{current} / {total}</b>　<span style={{ color: 'rgba(0,0,0,.45)' }}>· 已用 {elapsed} 秒</span></div>
          <Progress percent={total > 0 ? Math.round((current / total) * 100) : 0} showInfo={false} size="small" />
          <Table<ReviewRowDTO>
            style={{ marginTop: 12 }}
            size="small"
            rowKey="reviewId"
            dataSource={batchRows}
            pagination={false}
            columns={[
              { title: '料号', dataIndex: 'materialNo', render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span> },
              { title: '名称', dataIndex: 'materialName' },
              { title: '结果', render: (_: unknown, r) => <BatchStateCell state={batchStates[r.reviewId] ?? 'WAITING'} /> },
            ]}
          />
        </>
      ) : (
        <>
          {failures.length > 0 && (
            <FailedMaterialsBlock failures={failures} selectedCount={rows.length} readyCount={readyRows.length} />
          )}
          {loading && <div style={{ textAlign: 'center', padding: 32 }}><Spin tip="正在计算影响面…" /></div>}
          {error && <Alert type="error" showIcon message={error} />}
          {preview && (
            <>
              <Descriptions column={2} size="small" bordered style={{ marginBottom: 16 }}>
                <Descriptions.Item label="料号数">{preview.materialCount}</Descriptions.Item>
                <Descriptions.Item label="将更新的单数">{preview.quotationCount}</Descriptions.Item>
              </Descriptions>

              <div style={{ marginBottom: 6, fontWeight: 600, fontSize: 13 }}>版本推进路径</div>
              <div style={{ marginBottom: 16, fontSize: 12.5 }}>
                {preview.versionPaths.map((p) => (
                  <div key={p.materialNo}>
                    <span style={{ fontFamily: 'monospace' }}>{p.materialNo}</span>：{p.from || '（首次）'} → <b>{p.to}</b>
                  </div>
                ))}
              </div>

              <div style={{ marginBottom: 6, fontWeight: 600, fontSize: 13 }}>将更新的单（按状态分组）</div>
              <div style={{ marginBottom: 16 }}>
                {Object.entries(preview.byStatus).map(([status, count]) => (
                  <Tag key={status} color="blue" style={{ marginBottom: 4 }}>{STATUS_LABEL[status] || status} × {count}</Tag>
                ))}
              </div>

              {preview.materials && preview.materials.length > 0 && (
                <MaterialAmountTable materials={preview.materials} breachedNos={breachedNos} />
              )}

              {preview.excludedQuotationCount > 0 && (
                <Alert
                  type="warning"
                  showIcon
                  style={{ marginBottom: 16 }}
                  message={`另有 ${preview.excludedQuotationCount} 张单不会被更新`}
                  description={
                    <span>
                      {Object.entries(preview.excludedByStatus).map(([status, count], i) => (
                        <span key={status}>{i > 0 ? '、' : ''}{STATUS_LABEL[status] || status} {count}</span>
                      ))}
                      —— 这些单将保持旧价。
                    </span>
                  }
                />
              )}

              {preview.breachedMaterials.length > 0 && (
                <Alert
                  type="error"
                  showIcon
                  message="以下料号仍有跌破预警线的比对列"
                  description={preview.breachedMaterials.map((m) => `${m.materialNo}（${m.breachedCount} 列）`).join('、')}
                />
              )}
            </>
          )}
        </>
      )}
    </Modal>
  );
};

export default ApproveImpactModal;
