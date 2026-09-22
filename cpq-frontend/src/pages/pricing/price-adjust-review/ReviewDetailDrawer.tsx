import React, { useEffect, useRef, useState } from 'react';
import { Drawer, Table, Tag, Spin, Alert, Typography, Tooltip, Button } from 'antd';
import dayjs from 'dayjs';
import { useNavigate } from 'react-router-dom';
import { priceAdjustService } from '../../../services/priceAdjustService';
import type {
  ReviewDetailDTO, ElementChangeDTO, ComparisonColumnResultDTO, ReviewQuotationDTO,
  ComparisonMissingSide, ReviewRowDTO,
} from '../../../types/price-adjust';
import {
  drawerComputeMode, MSG_COMPUTE_SLOW, MSG_NOT_PENDING, MSG_SUPERSEDED,
  type ComputeOutcome,
} from './reviewCompute';
import { formatNumber } from '../../../utils/formatNumber';
import {
  DISPLAY_SCALE,
  ELEMENT_PRICE_SCALE,
  formatChangeRate,
  normalizeDecimalString,
  toDecimal,
  type DecimalString,
} from '../../../utils/precision';

const { Text } = Typography;

export interface ReviewDetailDrawerProps {
  open: boolean;
  reviewId: string | null;
  onClose: () => void;
  /**
   * task-260920 F-2: the list row that was clicked (title + budgetStatus decide whether to compute
   * first). Absent → behaves like before (fetch detail straight away).
   */
  row?: ReviewRowDTO | null;
  /** task-260920 F-2: the page's single compute routine (also replaces the list row in place). */
  computeRow?: (row: ReviewRowDTO, mode: 'compute' | 'waitOnly', signal: { aborted: boolean }) => Promise<ComputeOutcome>;
}

/** F-2: map a non-DONE compute outcome to the drawer's error line (fixed copy, shared with the page). */
function outcomeError(o: ComputeOutcome): string | null {
  switch (o.kind) {
    case 'DONE': return null;
    case 'NOT_PENDING': return o.superseded ? MSG_SUPERSEDED : MSG_NOT_PENDING;
    case 'TIMEOUT': return MSG_COMPUTE_SLOW;
    case 'ABORTED': return null;
    case 'ERROR': return o.message;
  }
}

/** 抽屉状态 1（原型 审核抽屉-触发计算.html）：整个抽屉显示「正在计算」，期间不发详情请求。 */
export const DrawerComputingPlaceholder: React.FC = () => (
  <div style={{ textAlign: 'center', padding: '64px 0', color: 'rgba(0,0,0,.65)' }}>
    <Spin size="large" style={{ display: 'block', marginBottom: 12 }} />
    正在计算该料号的影响…
    <div style={{ color: 'rgba(0,0,0,.45)', fontSize: 12.5, marginTop: 6 }}>后台正在计算同一张单时需要排队，可能要几秒</div>
  </div>
);

/** 抽屉状态 3：「二、能不能接受」在计算失败时的占位 + 「重新计算」（点了才调 compute-now）。 */
export const DrawerFailedSection: React.FC<{ onRecompute?: () => void }> = ({ onRecompute }) => (
  <div style={{ textAlign: 'center', padding: '28px 0', color: 'rgba(0,0,0,.65)' }}>
    <div style={{ fontSize: 26, opacity: 0.25 }}>⚠️</div>
    计算未完成，暂无法判断是否可接受
    <div style={{ marginTop: 10 }}><Button type="primary" onClick={onRecompute} disabled={!onRecompute}>重新计算</Button></div>
  </div>
);

/**
 * 本抽屉全部金额的唯一展示口径：最多 DISPLAY_SCALE(9) 位，尾零由
 * formatDisplayDecimal -> trimFixed 自动去掉（'1.717061326000' -> '1.717061326'，
 * '0.500000000' -> '0.5'）。
 *
 * 🔒 默认位数曾写死为 2，把 numeric(26,12) 存下的值截成 '1.72'（比对列 7 位有效数字
 * 全部看不见）。task-260916 当时只把上版价/本版价接到 ELEMENT_PRICE_SCALE，其余金额列漏了。
 * 🚫 不要再往调用点传字面量位数——要调整口径请改 precision.ts 的常量，保持前后端同值。
 */
function fmt(v: DecimalString | null | undefined, digits = DISPLAY_SCALE): string {
  return formatNumber(v, { isComputed: true, decimals: digits }) ?? '—';
}

/**
 * 缺失侧文案：**全量映射，不用二元三元**。
 * 后端 `ComparisonColumnEvaluator` 产出 QUOTE / COSTING / BOTH 三态，原写法
 * `=== 'QUOTE' ? '报价侧' : '核价侧'` 让 BOTH 静默落进 else 显示成「核价侧」，
 * 把业务排查方向带偏（实际两侧都没数据）。用 Record 后，后端再加枚举值时
 * 这里会直接编译不过，而不是又静默错一次。
 */
export const MISSING_SIDE_LABEL: Record<ComparisonMissingSide, string> = {
  QUOTE: '报价侧',
  COSTING: '核价侧',
  BOTH: '两侧',
};

const cellStyle: Record<ComparisonColumnResultDTO['status'], React.CSSProperties> = {
  RED: { background: '#fff1f0', color: '#cf1322' },
  AMBER: { background: '#fffbe6', color: '#d46b08' },
  NORMAL: {},
  MISSING: { color: 'rgba(0,0,0,.35)' },
  STALE: { color: 'rgba(0,0,0,.35)' },
};

/**
 * 屏 4 · 料号审核抽屉（1100px Drawer，fronttask §3 / api.md §2.2）。
 * 三段结构（对应财务思考顺序，缺一不可）：① 为什么变 ② 能不能接受 ③ 下钻。
 * 🔒 抽屉内无任何可修改比对列的控件（只读展示）——改比对列会触发预算重算。
 */
const ReviewDetailDrawer: React.FC<ReviewDetailDrawerProps> = ({ open, reviewId, onClose, row, computeRow }) => {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [computing, setComputing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [detail, setDetail] = useState<ReviewDetailDTO | null>(null);
  const signalRef = useRef<{ aborted: boolean }>({ aborted: false });

  const fetchDetail = (id: string, signal: { aborted: boolean }) => {
    setLoading(true);
    return priceAdjustService.getReviewDetail(id)
      .then((d) => { if (!signal.aborted) setDetail(d); })
      .catch((e: any) => { if (!signal.aborted) setError(e?.message || '加载料号审核详情失败'); })
      .finally(() => { if (!signal.aborted) setLoading(false); });
  };

  /**
   * 🔒 F-2 ④ 先算、后取详情：compute-now（或仅轮询）结束之后才调 GET /{id}，两者绝不并发 ——
   * 详情接口自带元素影响试算，并发会对同一行同时试算两次（评审【2】）。
   */
  const computeThenFetch = async (target: ReviewRowDTO, mode: 'compute' | 'waitOnly', signal: { aborted: boolean }) => {
    if (!computeRow) { await fetchDetail(target.reviewId, signal); return; }
    setComputing(true);
    setDetail(null);
    setError(null);
    const outcome = await computeRow(target, mode, signal);
    if (signal.aborted) return;
    setComputing(false);
    const err = outcomeError(outcome);
    if (err) { setError(err); return; }
    await fetchDetail(target.reviewId, signal);
  };

  useEffect(() => {
    signalRef.current.aborted = true;
    const signal = { aborted: false };
    signalRef.current = signal;
    if (!open || !reviewId) { setDetail(null); setComputing(false); setError(null); return; }
    setError(null);
    setDetail(null);
    const mode = row && row.reviewId === reviewId ? drawerComputeMode(row.reviewStatus, row.budgetStatus) : 'none';
    if (mode !== 'none' && row) {
      computeThenFetch(row, mode, signal);
    } else {
      fetchDetail(reviewId, signal);
    }
    return () => { signal.aborted = true; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, reviewId]);

  const handleRecompute = () => {
    if (!detail || !reviewId) return;
    const target: ReviewRowDTO = row && row.reviewId === reviewId
      ? row
      : ({ reviewId, materialNo: detail.materialNo, materialName: detail.materialName } as ReviewRowDTO);
    computeThenFetch(target, 'compute', signalRef.current);
  };

  const isFailed = detail?.budgetStatus === 'FAILED' && detail.reviewStatus === 'PENDING';
  const headerTag = computing
    ? <Tag style={{ fontWeight: 400, marginLeft: 8 }}>未计算</Tag>
    : isFailed ? <Tag color="red" style={{ fontWeight: 400, marginLeft: 8 }}>预算失败</Tag> : null;
  const titleNo = detail?.materialNo ?? (row && row.reviewId === reviewId ? row.materialNo : undefined);
  const titleName = detail?.materialName ?? (row && row.reviewId === reviewId ? row.materialName : undefined);

  const elementColumns = [
    { title: '元素', render: (_: unknown, r: ElementChangeDTO) => <span><b>{r.elementCode}</b> {r.elementName}</span> },
    { title: '命中规则', dataIndex: 'matchedRule' },
    { title: '上版价', dataIndex: 'previousPrice', align: 'right' as const, render: (v: DecimalString | null) => fmt(v, ELEMENT_PRICE_SCALE) },
    { title: '本版价', dataIndex: 'currentPrice', align: 'right' as const, render: (v: DecimalString | null) => fmt(v, ELEMENT_PRICE_SCALE) },
    {
      title: '涨跌', dataIndex: 'changeRate', align: 'right' as const,
      render: (v: DecimalString | null) => { const r = formatChangeRate(v); return <span style={{ color: r.color }}>{r.text}</span>; },
    },
    { title: '该料号用量', dataIndex: 'usageQty', align: 'right' as const, render: (v: DecimalString | null) => v ?? '—' },
    { title: '对单价影响', dataIndex: 'unitPriceImpact', align: 'right' as const, render: (v: DecimalString | null) => fmt(v) },
    {
      title: '标记', render: (_: unknown, r: ElementChangeDTO) => (
        <>
          {r.noPrice && <Tag color="orange">无价</Tag>}
          {r.inheritedFromPrevious && <Tag>沿用上一版</Tag>}
        </>
      ),
    },
  ];

  const comparisonColumns = [
    { title: '#', width: 40, render: (_: unknown, __: ComparisonColumnResultDTO, i: number) => i + 1 },
    { title: '比对列', dataIndex: 'label' },
    { title: '阈值', dataIndex: 'threshold', align: 'right' as const, render: (v: DecimalString) => fmt(v) },
    { title: '报价·现', dataIndex: 'quoteCurrent', align: 'right' as const, render: (v: DecimalString | null) => fmt(v) },
    { title: '报价·调整后', dataIndex: 'quoteAdjusted', align: 'right' as const, render: (v: DecimalString | null) => fmt(v) },
    { title: '核价·现', dataIndex: 'costingCurrent', align: 'right' as const, render: (v: DecimalString | null) => fmt(v) },
    { title: '核价·调整后', dataIndex: 'costingAdjusted', align: 'right' as const, render: (v: DecimalString | null) => fmt(v) },
    {
      title: '差异', dataIndex: 'diffAdjusted', align: 'right' as const,
      render: (v: DecimalString | null, r: ComparisonColumnResultDTO) => {
        // 主干用中性「缺数据」：原文案「缺核价数据」在 missingSide=QUOTE 时自相矛盾
        // （说缺核价数据、又说缺在报价侧），BOTH 同样别扭。改后三态都读得通：
        // 缺数据：报价侧 / 核价侧 / 两侧；missingSide 为空时退化为「—（缺数据）」。
        if (r.status === 'MISSING') return <span style={cellStyle.MISSING}>—（缺数据{r.missingSide ? `：${MISSING_SIDE_LABEL[r.missingSide]}` : ''}）</span>;
        if (r.status === 'STALE') return <Tooltip title="该比对列配置已失效（模板改版后 componentId/指标找不到），不计入标红判定"><span style={cellStyle.STALE}>已失效</span></Tooltip>;
        return <b>{fmt(v)}</b>;
      },
    },
  ];

  const quotationColumns = [
    {
      title: '单号', dataIndex: 'quotationNo', render: (v: string, r: ReviewQuotationDTO) => (
        <a onClick={() => navigate(r.comparisonViewUrl)}>{v}</a>
      ),
    },
    { title: '创建日期', dataIndex: 'createdAt', render: (v: string) => v ? dayjs(v).format('YYYY-MM-DD') : '—' },
    { title: '状态', dataIndex: 'status' },
    { title: '现小计', dataIndex: 'quoteSubtotalCurrent', align: 'right' as const, render: (v: DecimalString | null) => fmt(v) },
    {
      title: '调整后小计', dataIndex: 'quoteSubtotalAdjusted', align: 'right' as const,
      // repair-0807 FR-5：三态，不能塌成两态。
      //   adjustedComputed=false → 「未试算」（设计内：只对判断依据单试算，其余仅作参考）
      //   adjustedComputed=true 且有值 → 数值
      //   adjustedComputed=true 但值为 null → 「—」（试算跑了却没拿到值 = 异常态，必须与"未试算"区分开）
      // 🚨 判据必须是 adjustedComputed 这个显式布尔，不能用 v == null 顶替——那会把
      // "试算失败"也说成"未试算"，混淆两种完全不同的状态。
      render: (v: DecimalString | null, r: ReviewQuotationDTO) => {
        if (!r.adjustedComputed) {
          return <Tooltip title="仅对判断依据单试算，其余单据仅作参考"><span style={{ color: 'rgba(0,0,0,.35)' }}>未试算</span></Tooltip>;
        }
        return fmt(v);
      },
    },
    {
      title: '标记', render: (_: unknown, r: ReviewQuotationDTO) => r.isBasis
        ? <Tag color="blue">判断依据</Tag>
        : <Tag>仅作参考</Tag>,
    },
    {
      title: '操作', render: (_: unknown, r: ReviewQuotationDTO) => (
        <a onClick={() => navigate(r.comparisonViewUrl)}>直达比对视图</a>
      ),
    },
  ];

  const basisQuotation = detail?.quotations.find((q) => q.isBasis);
  const impactCheck = basisQuotation?.quoteSubtotalAdjusted != null && basisQuotation.quoteSubtotalCurrent != null
    ? normalizeDecimalString(toDecimal(basisQuotation.quoteSubtotalAdjusted).minus(basisQuotation.quoteSubtotalCurrent))
    : null;
  const impactMatches = impactCheck != null && detail != null
    ? toDecimal(impactCheck).minus(detail.elementImpactTotal).abs().lessThan('0.01')
    : false;

  return (
    <Drawer
      title={<span>{titleNo ? `料号审核 · ${titleNo} ${titleName ?? ''}` : '料号审核'}{headerTag}</span>}
      placement="right"
      width={1100}
      open={open}
      onClose={onClose}
      destroyOnClose
    >
      {computing && <DrawerComputingPlaceholder />}
      {!computing && loading && <div style={{ textAlign: 'center', padding: 48 }}><Spin tip="加载中…" /></div>}
      {error && <Alert type="error" showIcon message={error} />}
      {detail && !computing && (
        <>
          {isFailed && (
            <Alert
              type="error"
              showIcon
              style={{ marginBottom: 16 }}
              message={<span><b>该料号的影响计算失败</b>{detail.budgetError?.trim() ? `：${detail.budgetError}` : ''}</span>}
            />
          )}
          <section style={{ marginBottom: 28 }}>
            <h4 style={{ marginBottom: 10 }}>一、为什么变</h4>
            <Table
              size="small"
              rowKey="elementCode"
              dataSource={detail.elementChanges}
              columns={elementColumns}
              pagination={false}
            />
            <div style={{ marginTop: 8, fontSize: 12.5, color: 'rgba(0,0,0,.65)' }}>
              合计对单价影响：<b>{fmt(detail.elementImpactTotal)}</b>
              {impactCheck != null && (
                <span style={{ marginLeft: 12, color: impactMatches ? '#389e0d' : '#cf1322' }}>
                  （财务自检：调整后报价 − 现报价 = {fmt(impactCheck)}
                  {impactMatches ? '，对得上 ✓' : '，⚠️ 对不上'}）
                </span>
              )}
            </div>
          </section>

          <section style={{ marginBottom: 28 }}>
            <h4 style={{ marginBottom: 4 }}>二、能不能接受</h4>
            <Text type="secondary" style={{ fontSize: 12.5 }}>
              按该料号所属模板系列「{detail.templateSeriesName}」的配置逐列展开（比对列唯一完整体现处，只读，改配置请到定价策略 Tab）
            </Text>
            {isFailed ? (
              <DrawerFailedSection onRecompute={computeRow ? handleRecompute : undefined} />
            ) : (
              <Table
                style={{ marginTop: 10 }}
                size="small"
                rowKey="columnId"
                dataSource={detail.comparisonColumns}
                columns={comparisonColumns}
                pagination={false}
                rowClassName={(r) => (r.status === 'RED' ? 'padj-row-red' : r.status === 'AMBER' ? 'padj-row-amber' : '')}
              />
            )}
          </section>

          <section>
            <h4 style={{ marginBottom: 4 }}>三、下钻</h4>
            <Text type="secondary" style={{ fontSize: 12.5 }}>
              最近一张单挂「判断依据」，其余仅作参考；本抽屉不重复实现页签级比对，直达 task-0717 比对视图查看逐字段明细
            </Text>
            <Table
              style={{ marginTop: 10 }}
              size="small"
              rowKey="quotationId"
              dataSource={detail.quotations}
              columns={quotationColumns}
              pagination={false}
            />
          </section>

          <style>{`
            .padj-row-red > td { background: #fff1f0 !important; }
            .padj-row-amber > td { background: #fffbe6 !important; }
          `}</style>
        </>
      )}
    </Drawer>
  );
};

export default ReviewDetailDrawer;
