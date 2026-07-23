import React, { useEffect, useState } from 'react';
import { Drawer, Select, DatePicker, Input, Button, Table, Space, Alert, message } from 'antd';
import dayjs, { type Dayjs } from 'dayjs';
import { elementPriceStrategyService } from '../../../services/elementPriceStrategyService';
import { formatMethod, formatWindow } from './strategyFormat';
import type { StrategyHistoryDTO, PageResult } from '../../../types/element-price-strategy';

const { RangePicker } = DatePicker;
const PAGE_SIZE = 20;

const actionTag: Record<StrategyHistoryDTO['action'], { color: string; label: string }> = {
  CREATE: { color: 'blue', label: '新建' },
  UPDATE: { color: 'orange', label: '修改' },
  DELETE: { color: 'red', label: '删除' },
};

/**
 * 策略变更历史抽屉（960） —— task-0722 · F8
 * 差异由后端算好，前端只渲染 changes/snapshot，不做比对逻辑（api.md §7）；只读，无编辑/回滚入口。
 */
interface Props {
  open: boolean;
  onClose: () => void;
  customerNo: string;
  customerLabel: string;
  /** 当前客户的元素例外列表，用于「变更对象」筛选下拉的"元素例外 · X"选项 */
  exceptionElements: { elementCode: string; elementName?: string | null }[];
}

const StrategyHistoryDrawer: React.FC<Props> = ({ open, onClose, customerNo, customerLabel, exceptionElements }) => {
  const [target, setTarget] = useState<string | undefined>(undefined);
  const [timeRange, setTimeRange] = useState<[Dayjs, Dayjs] | null>(null);
  const [changedBy, setChangedBy] = useState('');
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<PageResult<StrategyHistoryDTO>>({ content: [], totalElements: 0, page: 0, size: PAGE_SIZE });

  const query = async (p = 0) => {
    setLoading(true);
    try {
      const res = await elementPriceStrategyService.listHistory({
        customerNo,
        elementCode: target,
        from: timeRange?.[0]?.format('YYYY-MM-DD'),
        to: timeRange?.[1]?.format('YYYY-MM-DD'),
        changedBy: changedBy.trim() || undefined,
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

  useEffect(() => {
    if (!open) return;
    setTarget(undefined);
    setTimeRange(null);
    setChangedBy('');
    query(0);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  const handleReset = () => {
    setTarget(undefined);
    setTimeRange(null);
    setChangedBy('');
    query(0);
  };

  const renderSnapshotSummary = (snap: StrategyHistoryDTO['snapshot']) => (
    <div style={{ lineHeight: 1.9 }}>
      价格源：<b>{String(snap.sourceName ?? '—')}</b><br />
      取值方式：<b>{formatMethod(snap.method as any)}</b>　窗口：<b>{formatWindow(snap.windowNum as any, snap.windowUnit as any)}</b><br />
      系数：<b>{snap.factor !== undefined ? Number(snap.factor).toFixed(2) : '—'}</b>　加价：<b>{snap.premium !== undefined ? Number(snap.premium).toFixed(2) : '—'}</b>
    </div>
  );

  const renderContent = (r: StrategyHistoryDTO) => {
    if (r.action === 'UPDATE') {
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
    if (r.action === 'CREATE') {
      return <div style={{ fontSize: 12 }}>{renderSnapshotSummary(r.snapshot)}</div>;
    }
    // DELETE：单行摘要
    const snap = r.snapshot;
    return (
      <div style={{ fontSize: 12 }}>
        删除前：{String(snap.sourceName ?? '—')} · {formatMethod(snap.method as any)} · {formatWindow(snap.windowNum as any, snap.windowUnit as any)} · ×{snap.factor !== undefined ? Number(snap.factor).toFixed(2) : '—'} +{snap.premium !== undefined ? Number(snap.premium).toFixed(2) : '—'}
      </div>
    );
  };

  return (
    <Drawer
      title={<div><div>价格策略变更历史 · {customerLabel}</div><div style={{ fontSize: 12, fontWeight: 400, color: 'rgba(0,0,0,.45)', marginTop: 2 }}>含客户级默认策略与全部元素级例外的变更留痕</div></div>}
      open={open}
      onClose={onClose}
      width={960}
      placement="right"
      destroyOnClose
      footer={<div style={{ textAlign: 'right' }}><Button onClick={onClose}>关闭</Button></div>}
    >
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 16, marginBottom: 16, flexWrap: 'wrap' }}>
        <div style={{ width: 200 }}>
          <div style={{ marginBottom: 6 }}>变更对象</div>
          <Select
            style={{ width: '100%' }}
            allowClear
            placeholder="全部"
            value={target}
            onChange={setTarget}
            options={[
              { value: '__DEFAULT__', label: '客户级默认策略' },
              ...exceptionElements.map((e) => ({ value: e.elementCode, label: `元素例外 · ${e.elementCode}${e.elementName ? ' ' + e.elementName : ''}` })),
            ]}
          />
        </div>
        <div>
          <div style={{ marginBottom: 6 }}>变更时间</div>
          <RangePicker value={timeRange} onChange={(v) => setTimeRange(v && v[0] && v[1] ? [v[0], v[1]] : null)} />
        </div>
        <div style={{ width: 170 }}>
          <div style={{ marginBottom: 6 }}>变更用户</div>
          <Input placeholder="姓名" value={changedBy} onChange={(e) => setChangedBy(e.target.value)} allowClear />
        </div>
        <Space>
          <Button type="primary" loading={loading} onClick={() => query(0)}>查询</Button>
          <Button onClick={handleReset}>重置</Button>
        </Space>
      </div>

      <Table<StrategyHistoryDTO>
        size="small"
        rowKey="id"
        loading={loading}
        dataSource={data.content}
        pagination={{
          current: page + 1,
          pageSize: PAGE_SIZE,
          total: data.totalElements,
          onChange: (p) => query(p - 1),
          showTotal: (total) => `共 ${total} 条`,
        }}
        columns={[
          { title: '变更时间', dataIndex: 'changedAt', width: 150, render: (v: string) => dayjs(v).format('YYYY-MM-DD HH:mm') },
          { title: '变更用户', dataIndex: 'changedByName', width: 90 },
          { title: '变更对象', dataIndex: 'targetLabel', width: 150 },
          {
            title: '操作', dataIndex: 'action', width: 76,
            render: (v: StrategyHistoryDTO['action']) => {
              const t = actionTag[v];
              return <span style={{
                display: 'inline-block', padding: '0 7px', borderRadius: 4, fontSize: 12,
                color: t.color === 'blue' ? '#1677ff' : t.color === 'orange' ? '#d46b08' : '#cf1322',
                background: t.color === 'blue' ? '#e6f4ff' : t.color === 'orange' ? '#fffbe6' : '#fff2f0',
                border: `1px solid ${t.color === 'blue' ? '#91caff' : t.color === 'orange' ? '#ffe58f' : '#ffccc7'}`,
              }}>{t.label}</span>;
            },
          },
          { title: '变更内容', dataIndex: 'changes', render: (_: unknown, r) => renderContent(r) },
        ]}
      />

      <Alert
        style={{ marginTop: 14 }}
        type="info"
        showIcon
        message="「修改」行只列出真正变化的项，未变的项不展示。历史为只读，本抽屉不提供编辑或回滚入口。"
      />
    </Drawer>
  );
};

export default StrategyHistoryDrawer;
