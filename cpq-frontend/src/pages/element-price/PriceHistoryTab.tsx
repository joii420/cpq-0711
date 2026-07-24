import React, { useEffect, useState } from 'react';
import { Select, DatePicker, Input, Button, Table, Space, Alert, message } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { elementPriceStrategyService } from '../../services/elementPriceStrategyService';
import type { PriceSourceDTO, PriceHistoryDTO, PageResult } from '../../types/element-price-strategy';

const { RangePicker } = DatePicker;
const PAGE_SIZE = 20;

const actionTag: Record<PriceHistoryDTO['action'], { color: string; label: string }> = {
  CREATE: { color: 'blue', label: '新建' },
  UPDATE: { color: 'orange', label: '修改' },
  DELETE: { color: 'red', label: '删除' },
};

/**
 * 元素价格表 · 变更历史 Tab（update-0724 · F4）
 * 筛选与明细 Tab 完全一致（价格源 / 日期区间 / 元素），但日期区间过滤的是 changedAt（「变更时间」），
 * 与明细 Tab 过滤 priceDate 语义不同，标签故意区分（api.md §5）。
 * 渲染逻辑与 task-0722 策略历史 Tab（StrategyHistoryDrawer）同构，只读，不提供回滚/还原入口（U11）。
 */
interface Props {
  active: boolean;
  sources: PriceSourceDTO[];
}

const PriceHistoryTab: React.FC<Props> = ({ active, sources }) => {
  const [sourceId, setSourceId] = useState<string | undefined>(undefined);
  const [dateRange, setDateRange] = useState<[Dayjs, Dayjs]>([dayjs().subtract(29, 'day'), dayjs()]);
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<PageResult<PriceHistoryDTO>>({ content: [], totalElements: 0, page: 0, size: PAGE_SIZE });

  const query = async (p = 0) => {
    setLoading(true);
    try {
      const res = await elementPriceStrategyService.listPriceHistory({
        sourceId,
        from: dateRange[0]?.format('YYYY-MM-DD'),
        to: dateRange[1]?.format('YYYY-MM-DD'),
        keyword: keyword.trim() || undefined,
        page: p,
        size: PAGE_SIZE,
      });
      setData(res);
      setPage(p);
    } catch (e: any) {
      message.error(e?.message ?? '查询失败');
    } finally {
      setLoading(false);
    }
  };

  // 首次进入该 Tab 时查一次；之后由用户点「查询」驱动（与明细 Tab 一致）
  useEffect(() => { if (active) query(0); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [active]);

  const handleReset = () => {
    setSourceId(undefined);
    setDateRange([dayjs().subtract(29, 'day'), dayjs()]);
    setKeyword('');
  };

  const renderSnapshotSummary = (snap: Record<string, unknown>) => {
    const price = snap.price !== undefined && snap.price !== null ? Number(snap.price).toFixed(4) : '—';
    const currency = snap.currency !== undefined ? String(snap.currency) : '—';
    const priceUnit = snap.priceUnit !== undefined ? String(snap.priceUnit) : '—';
    return <span>单价 {price} {currency}/{priceUnit}</span>;
  };

  const renderContent = (r: PriceHistoryDTO) => {
    if (r.action === 'UPDATE') {
      if (r.changes.length === 0) return <span style={{ color: 'rgba(0,0,0,.45)' }}>—</span>;
      return (
        <div style={{ lineHeight: 1.9, fontSize: 12 }}>
          {r.changes.map((c, i) => (
            <div key={i}>
              {c.fieldLabel}：<span style={{ color: 'rgba(0,0,0,.45)' }}>{c.oldValue}</span> → <b style={{ color: '#d46b08' }}>{c.newValue}</b>
            </div>
          ))}
        </div>
      );
    }
    // CREATE / DELETE：changes 为空，渲染 snapshot 全量
    return <div style={{ fontSize: 12 }}>{renderSnapshotSummary(r.snapshot)}</div>;
  };

  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 16, marginBottom: 16, flexWrap: 'wrap' }}>
        <div style={{ width: 200 }}>
          <div style={{ marginBottom: 6 }}>价格源</div>
          <Select
            style={{ width: '100%' }}
            allowClear
            placeholder="全部"
            value={sourceId}
            onChange={setSourceId}
            options={sources.map((s) => ({ value: s.id, label: s.sourceName }))}
          />
        </div>
        <div>
          {/* 语义区别于明细 Tab 的「日期区间」：这里过滤的是 changedAt（变更发生时间），不是 priceDate */}
          <div style={{ marginBottom: 6 }}>变更时间</div>
          <RangePicker
            value={dateRange}
            onChange={(v) => v && v[0] && v[1] && setDateRange([v[0], v[1]])}
            allowClear={false}
          />
        </div>
        <div style={{ width: 220 }}>
          <div style={{ marginBottom: 6 }}>元素</div>
          <Input placeholder="符号或中文名" value={keyword} onChange={(e) => setKeyword(e.target.value)} allowClear />
        </div>
        <Space>
          <Button type="primary" loading={loading} onClick={() => query(0)}>查询</Button>
          <Button onClick={handleReset}>重置</Button>
        </Space>
      </div>

      <Table<PriceHistoryDTO>
        size="small"
        rowKey="id"
        loading={loading}
        dataSource={data.content}
        scroll={{ x: 'max-content' }}
        pagination={{
          current: page + 1,
          pageSize: PAGE_SIZE,
          total: data.totalElements,
          onChange: (p) => query(p - 1),
          showTotal: (total) => `共 ${total} 条`,
        }}
        columns={[
          { title: '变更时间', dataIndex: 'changedAt', width: 150, render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm') },
          { title: '操作人', dataIndex: 'changedByName', width: 90 },
          {
            title: '动作', dataIndex: 'action', width: 76,
            render: (v: PriceHistoryDTO['action']) => {
              const t = actionTag[v];
              return <span style={{
                display: 'inline-block', padding: '0 7px', borderRadius: 4, fontSize: 12,
                color: t.color === 'blue' ? '#1677ff' : t.color === 'orange' ? '#d46b08' : '#cf1322',
                background: t.color === 'blue' ? '#e6f4ff' : t.color === 'orange' ? '#fffbe6' : '#fff2f0',
                border: `1px solid ${t.color === 'blue' ? '#91caff' : t.color === 'orange' ? '#ffe58f' : '#ffccc7'}`,
              }}>{t.label}</span>;
            },
          },
          { title: '目标', dataIndex: 'targetLabel', width: 220 },
          { title: '变更内容', dataIndex: 'changes', render: (_: unknown, r) => renderContent(r) },
        ]}
      />

      <Alert
        style={{ marginTop: 14 }}
        type="info"
        showIcon
        message="「修改」行只列出真正变化的项，未变的项不展示。历史为只读，本 Tab 不提供编辑或回滚入口。"
      />
    </div>
  );
};

export default PriceHistoryTab;
