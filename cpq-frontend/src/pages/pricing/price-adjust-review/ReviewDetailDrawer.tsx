import React, { useEffect, useState } from 'react';
import { Drawer, Table, Tag, Spin, Alert, Typography, Tooltip } from 'antd';
import dayjs from 'dayjs';
import { useNavigate } from 'react-router-dom';
import { priceAdjustService } from '../../../services/priceAdjustService';
import type {
  ReviewDetailDTO, ElementChangeDTO, ComparisonColumnResultDTO, ReviewQuotationDTO,
  ComparisonMissingSide,
} from '../../../types/price-adjust';

const { Text } = Typography;

export interface ReviewDetailDrawerProps {
  open: boolean;
  reviewId: string | null;
  onClose: () => void;
}

function fmt(v: number | null | undefined, digits = 2): string {
  if (v == null) return '—';
  return v.toLocaleString('zh-CN', { minimumFractionDigits: digits, maximumFractionDigits: digits });
}
function fmtRate(v: number | null | undefined): { text: string; color?: string } {
  if (v == null) return { text: '—' };
  const pct = (v * 100).toFixed(1);
  if (v > 0) return { text: `+${pct}%`, color: '#cf1322' };
  if (v < 0) return { text: `${pct}%`, color: '#389e0d' };
  return { text: `${pct}%` };
}

/**
 * 缺失侧文案：**全量映射，不用二元三元**。
 * 后端 `ComparisonColumnEvaluator` 产出 QUOTE / COSTING / BOTH 三态，原写法
 * `=== 'QUOTE' ? '报价侧' : '核价侧'` 让 BOTH 静默落进 else 显示成「核价侧」，
 * 把业务排查方向带偏（实际两侧都没数据）。用 Record 后，后端再加枚举值时
 * 这里会直接编译不过，而不是又静默错一次。
 */
const MISSING_SIDE_LABEL: Record<ComparisonMissingSide, string> = {
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
const ReviewDetailDrawer: React.FC<ReviewDetailDrawerProps> = ({ open, reviewId, onClose }) => {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [detail, setDetail] = useState<ReviewDetailDTO | null>(null);

  useEffect(() => {
    if (!open || !reviewId) { setDetail(null); return; }
    setLoading(true);
    setError(null);
    priceAdjustService.getReviewDetail(reviewId)
      .then(setDetail)
      .catch((e: any) => setError(e?.message || '加载料号审核详情失败'))
      .finally(() => setLoading(false));
  }, [open, reviewId]);

  const elementColumns = [
    { title: '元素', render: (_: unknown, r: ElementChangeDTO) => <span><b>{r.elementCode}</b> {r.elementName}</span> },
    { title: '命中规则', dataIndex: 'matchedRule' },
    { title: '上版价', dataIndex: 'previousPrice', align: 'right' as const, render: (v: number | null) => fmt(v) },
    { title: '本版价', dataIndex: 'currentPrice', align: 'right' as const, render: (v: number | null) => fmt(v) },
    {
      title: '涨跌', dataIndex: 'changeRate', align: 'right' as const,
      render: (v: number | null) => { const r = fmtRate(v); return <span style={{ color: r.color }}>{r.text}</span>; },
    },
    { title: '该料号用量', dataIndex: 'usageQty', align: 'right' as const, render: (v: number | null) => v == null ? '—' : v },
    { title: '对单价影响', dataIndex: 'unitPriceImpact', align: 'right' as const, render: (v: number | null) => fmt(v) },
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
    { title: '阈值', dataIndex: 'threshold', align: 'right' as const, render: (v: number) => fmt(v) },
    { title: '报价·现', dataIndex: 'quoteCurrent', align: 'right' as const, render: (v: number | null) => fmt(v) },
    { title: '报价·调整后', dataIndex: 'quoteAdjusted', align: 'right' as const, render: (v: number | null) => fmt(v) },
    { title: '核价·现', dataIndex: 'costingCurrent', align: 'right' as const, render: (v: number | null) => fmt(v) },
    { title: '核价·调整后', dataIndex: 'costingAdjusted', align: 'right' as const, render: (v: number | null) => fmt(v) },
    {
      title: '差异', dataIndex: 'diffAdjusted', align: 'right' as const,
      render: (v: number | null, r: ComparisonColumnResultDTO) => {
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
    { title: '现小计', dataIndex: 'quoteSubtotalCurrent', align: 'right' as const, render: (v: number | null) => fmt(v) },
    {
      title: '调整后小计', dataIndex: 'quoteSubtotalAdjusted', align: 'right' as const,
      // repair-0807 FR-5：三态，不能塌成两态。
      //   adjustedComputed=false → 「未试算」（设计内：只对判断依据单试算，其余仅作参考）
      //   adjustedComputed=true 且有值 → 数值
      //   adjustedComputed=true 但值为 null → 「—」（试算跑了却没拿到值 = 异常态，必须与"未试算"区分开）
      // 🚨 判据必须是 adjustedComputed 这个显式布尔，不能用 v == null 顶替——那会把
      // "试算失败"也说成"未试算"，混淆两种完全不同的状态。
      render: (v: number | null, r: ReviewQuotationDTO) => {
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

  const impactCheck = detail
    ? (detail.quotations.find((q) => q.isBasis)?.quoteSubtotalAdjusted ?? null) != null
      && (detail.quotations.find((q) => q.isBasis)?.quoteSubtotalCurrent ?? null) != null
      ? (detail.quotations.find((q) => q.isBasis)!.quoteSubtotalAdjusted! - detail.quotations.find((q) => q.isBasis)!.quoteSubtotalCurrent!)
      : null
    : null;

  return (
    <Drawer
      title={detail ? `料号审核 · ${detail.materialNo} ${detail.materialName}` : '料号审核'}
      placement="right"
      width={1100}
      open={open}
      onClose={onClose}
      destroyOnClose
    >
      {loading && <div style={{ textAlign: 'center', padding: 48 }}><Spin tip="加载中…" /></div>}
      {error && <Alert type="error" showIcon message={error} />}
      {detail && (
        <>
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
                <span style={{ marginLeft: 12, color: Math.abs(impactCheck - detail.elementImpactTotal) < 0.01 ? '#389e0d' : '#cf1322' }}>
                  （财务自检：调整后报价 − 现报价 = {fmt(impactCheck)}
                  {Math.abs(impactCheck - detail.elementImpactTotal) < 0.01 ? '，对得上 ✓' : '，⚠️ 对不上'}）
                </span>
              )}
            </div>
          </section>

          <section style={{ marginBottom: 28 }}>
            <h4 style={{ marginBottom: 4 }}>二、能不能接受</h4>
            <Text type="secondary" style={{ fontSize: 12.5 }}>
              按该料号所属模板系列「{detail.templateSeriesName}」的配置逐列展开（比对列唯一完整体现处，只读，改配置请到定价策略 Tab）
            </Text>
            <Table
              style={{ marginTop: 10 }}
              size="small"
              rowKey="columnId"
              dataSource={detail.comparisonColumns}
              columns={comparisonColumns}
              pagination={false}
              rowClassName={(r) => (r.status === 'RED' ? 'padj-row-red' : r.status === 'AMBER' ? 'padj-row-amber' : '')}
            />
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
