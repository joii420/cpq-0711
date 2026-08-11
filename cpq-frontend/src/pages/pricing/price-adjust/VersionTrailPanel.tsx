import React, { useCallback, useEffect, useImperativeHandle, useState, forwardRef } from 'react';
import { Table, Button, Tag, Popconfirm, Alert, Drawer, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import type { ApiError } from '../../../services/api';
import { priceAdjustService } from '../../../services/priceAdjustService';
import { extractErrorPayload } from './errorPayload';
import type {
  VersionDTO, VersionItemDTO, PendingVersionExistsPayload, StrategyNoElementsPayload,
} from '../../../types/price-adjust';
import { formatNumber } from '../../../utils/formatNumber';
import { formatDisplayDecimal, toDecimal, type DecimalString } from '../../../utils/precision';

const PAGE_SIZE = 10;

const formatPrice = (value: DecimalString | null): string =>
  formatNumber(value, { isComputed: true, decimals: 2 }) ?? '—';

function formatRate(value: DecimalString | null): { text: string; color?: string } {
  if (value == null) return { text: '—' };
  const rate = toDecimal(value);
  const pct = formatDisplayDecimal(rate.times('100'), 1);
  return {
    text: `${rate.isPositive() ? '+' : ''}${pct}%`,
    color: rate.isPositive() ? '#cf1322' : rate.isNegative() ? '#389e0d' : undefined,
  };
}

export interface VersionTrailPanelHandle {
  /** 供父层在保存策略/元素清单成功后调用，让「最新已生成版本」等联动刷新。 */
  reload: () => void;
}

export interface VersionTrailPanelProps {
  customerNo: string;
  customerLabel: string;
  latestVersionNo: string | null;
  /** 生成成功后回调，父层据此重新拉取策略主体（更新 latestVersionNo/pendingVersionNo）。 */
  onGenerated?: () => void;
}

const statusTag = (status: VersionDTO['status']) => (
  <Tag color={status === 'PENDING' ? 'orange' : 'default'}>{status === 'PENDING' ? '待处理' : '已被取代'}</Tag>
);

const triggerTag = (t: VersionDTO['triggerType']) => (
  <Tag color={t === 'MANUAL' ? 'blue' : 'default'}>{t === 'MANUAL' ? '手动' : '定时'}</Tag>
);

const progressText = (p: VersionDTO['progress']) => {
  const parts = [`通过 ${p.approved}`, `驳回 ${p.rejected}`, `待处理 ${p.pending}`];
  if (p.budgeting > 0) parts.push(`预算计算中 ${p.budgeting}`);
  return parts.join(' / ');
};

/**
 * 屏 1 · 版本轨迹 + 立即生成一次（fronttask §1.5 / api.md §1.11-1.13）。
 * 🔒 版本状态只有两态（待处理/已被取代），不落"生效/驳回"等料号级概念（§11.3.3）。
 * 🔒 「立即生成一次」两种错误处置：
 *   - 400 STRATEGY_NO_ELEMENTS → 就地 Alert 提示，不弹全局 error
 *   - 409 PENDING_VERSION_EXISTS → 受控 Popconfirm 二次确认（任务硬约束：本任务除屏 5 外禁用 Modal，
 *     此处以 Popconfirm 承载二次确认语义，open 完全受控，与按钮 onClick 直接触发生成互不干扰）
 */
const VersionTrailPanel = forwardRef<VersionTrailPanelHandle, VersionTrailPanelProps>(
  ({ customerNo, customerLabel, latestVersionNo, onGenerated }, ref) => {
    const [loading, setLoading] = useState(false);
    const [rows, setRows] = useState<VersionDTO[]>([]);
    const [total, setTotal] = useState(0);
    const [page, setPage] = useState(1);

    const [generating, setGenerating] = useState(false);
    const [pendingConfirm, setPendingConfirm] = useState<PendingVersionExistsPayload | null>(null);
    const [noElementsError, setNoElementsError] = useState<string | null>(null);

    const [itemsDrawerVersion, setItemsDrawerVersion] = useState<VersionDTO | null>(null);
    const [items, setItems] = useState<VersionItemDTO[]>([]);
    const [itemsLoading, setItemsLoading] = useState(false);

    const load = useCallback(async (p = 1) => {
      setLoading(true);
      try {
        const res = await priceAdjustService.getVersions(customerNo, { page: p, size: PAGE_SIZE });
        setRows(res.content || []);
        setTotal(res.totalElements || 0);
        setPage(p);
      } catch (e: any) {
        message.error(e?.message || '加载版本轨迹失败');
      } finally {
        setLoading(false);
      }
    }, [customerNo]);

    useEffect(() => { load(1); }, [customerNo, load]);

    useImperativeHandle(ref, () => ({ reload: () => load(page) }), [load, page]);

    const runGenerate = async (confirmSupersede: boolean) => {
      setGenerating(true);
      setNoElementsError(null);
      try {
        const res = await priceAdjustService.generateVersion(customerNo, confirmSupersede);
        message.success(`已生成版本 ${res.versionNo}`);
        setPendingConfirm(null);
        onGenerated?.();
        load(1);
      } catch (e: unknown) {
        const err = e as ApiError;
        if (err.httpStatus === 400) {
          const payload = extractErrorPayload<StrategyNoElementsPayload>(e);
          if (payload?.code === 'STRATEGY_NO_ELEMENTS') {
            setNoElementsError('未配置参与调价元素，本策略不会生成版本');
            return;
          }
        }
        if (err.httpStatus === 409) {
          const payload = extractErrorPayload<PendingVersionExistsPayload>(e);
          if (payload?.code === 'PENDING_VERSION_EXISTS') {
            setPendingConfirm(payload);
            return;
          }
        }
        message.error(err.message || '生成失败');
      } finally {
        setGenerating(false);
      }
    };

    const openItems = async (v: VersionDTO) => {
      setItemsDrawerVersion(v);
      setItemsLoading(true);
      try {
        const res = await priceAdjustService.getVersionItems(v.versionId, { page: 1, size: 200 });
        setItems(res.content || []);
      } catch (e: any) {
        message.error(e?.message || '加载版本明细失败');
        setItems([]);
      } finally {
        setItemsLoading(false);
      }
    };

    const columns: ColumnsType<VersionDTO> = [
      { title: '版本号', dataIndex: 'versionNo', width: 120, render: (v: string) => <b style={{ fontFamily: 'monospace' }}>{v}</b> },
      { title: '生成时间', dataIndex: 'createdAt', width: 150, render: (v: string) => v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '—' },
      { title: '触发', dataIndex: 'triggerType', width: 70, render: triggerTag },
      { title: '基准日', dataIndex: 'baseDate', width: 100 },
      { title: '元素数', dataIndex: 'itemCount', width: 70, align: 'right' as const },
      { title: '版本状态', dataIndex: 'status', width: 90, render: statusTag },
      { title: '料号进度', render: (_: unknown, r: VersionDTO) => <span style={{ fontSize: 12 }}>{progressText(r.progress)}</span> },
      { title: '操作', width: 70, render: (_: unknown, r: VersionDTO) => <a onClick={() => openItems(r)}>明细</a> },
    ];

    return (
      <div style={{ border: '1px solid #f0f0f0', borderRadius: 8, marginTop: 16 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', padding: '12px 16px', borderBottom: '1px solid #f0f0f0' }}>
          <span style={{ fontWeight: 600 }}>价格版本轨迹 · {customerLabel}</span>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
            <span style={{ fontSize: 12.5, color: 'rgba(0,0,0,.45)' }}>
              最新已生成版本：<span style={{ fontFamily: 'monospace' }}>{latestVersionNo || '—'}</span>
              <span style={{ color: 'rgba(0,0,0,.35)' }}>（仅时间参照，具体料号在哪版看屏 7）</span>
            </span>
            <Popconfirm
              open={!!pendingConfirm}
              title="已有待处理版本"
              description={pendingConfirm ? (
                <span>
                  {pendingConfirm.pendingVersionNo} 将被作废，其中 <b>{pendingConfirm.pendingReviewCount}</b> 个待处理料号退出待办池
                  （已通过的 <b>{pendingConfirm.approvedReviewCount}</b> 个不回滚），确定重新生成？
                </span>
              ) : null}
              onConfirm={() => runGenerate(true)}
              onCancel={() => setPendingConfirm(null)}
              okText="确定重新生成"
              cancelText="取消"
            >
              <Button size="small" type="primary" loading={generating} disabled={!!pendingConfirm} onClick={() => runGenerate(false)}>
                ⚡ 立即生成一次
              </Button>
            </Popconfirm>
          </div>
        </div>
        <div style={{ padding: '0 16px' }}>
          {noElementsError && (
            <Alert type="warning" showIcon closable style={{ margin: '12px 0 0' }} message={noElementsError} onClose={() => setNoElementsError(null)} />
          )}
        </div>
        <div style={{ padding: 16, paddingTop: 12 }}>
          <Table<VersionDTO>
            size="small"
            rowKey="versionId"
            loading={loading}
            dataSource={rows}
            columns={columns}
            pagination={{
              current: page, pageSize: PAGE_SIZE, total, size: 'small',
              onChange: (p) => load(p), showTotal: (t) => `共 ${t} 条`,
            }}
          />
          <div style={{ marginTop: 10, fontSize: 12, color: 'rgba(0,0,0,.45)', lineHeight: 1.7 }}>
            版本号 = <span style={{ fontFamily: 'monospace' }}>V + YYMMDD + 两位当日流水</span>；同一客户同时只允许一个「待处理」版本，
            新一期生成时上一个自动转「已被取代」，仅供追溯，报价单里仍可切回它做只读预览。
            「立即生成一次」与定时任务走完全相同的代码路径。
          </div>
        </div>

        <Drawer
          title={itemsDrawerVersion ? `版本明细 · ${itemsDrawerVersion.versionNo}` : '版本明细'}
          placement="right"
          width={720}
          open={!!itemsDrawerVersion}
          onClose={() => setItemsDrawerVersion(null)}
          destroyOnClose
        >
          <Table<VersionItemDTO>
            size="small"
            rowKey="elementCode"
            loading={itemsLoading}
            dataSource={items}
            pagination={false}
            columns={[
              { title: '元素', render: (_: unknown, r: VersionItemDTO) => <span><b>{r.elementCode}</b> {r.elementName}</span> },
              { title: '本期价', dataIndex: 'currentPrice', align: 'right' as const, render: (v: DecimalString | null) => formatPrice(v) },
              { title: '上期价', dataIndex: 'previousPrice', align: 'right' as const, render: (v: DecimalString | null) => formatPrice(v) },
              {
                title: '涨跌', dataIndex: 'changeRate', align: 'right' as const,
                render: (v: DecimalString | null) => {
                  const rate = formatRate(v);
                  return <span style={{ color: rate.color }}>{rate.text}</span>;
                },
              },
              {
                title: '标记', render: (_: unknown, r: VersionItemDTO) => (
                  <>
                    {r.noPrice && <Tag color="orange">无价</Tag>}
                    {r.inheritedFromPrevious && <Tag color="default">沿用上一版</Tag>}
                  </>
                ),
              },
            ]}
          />
        </Drawer>
      </div>
    );
  },
);

export default VersionTrailPanel;
