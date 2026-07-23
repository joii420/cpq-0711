import React, { useState } from 'react';
import { Select, DatePicker, Input, Button, Table, Space, Alert, message } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { elementPriceStrategyService } from '../../services/elementPriceStrategyService';
import type { PriceSourceDTO, PriceMatrixDTO } from '../../types/element-price-strategy';

const { RangePicker } = DatePicker;

/**
 * 元素价格表 · 矩阵 Tab（task-0722 · F5）
 * 行=元素、列=日期；价格源必填单选（无"全部"）；跨度默认最近 30 天、最长 90 天；
 * null 渲染「—」，绝不渲染 0（api.md §3.2）。
 */
interface Props {
  sources: PriceSourceDTO[];
}

const PriceMatrixTab: React.FC<Props> = ({ sources }) => {
  const [sourceId, setSourceId] = useState<string | undefined>(undefined);
  const [dateRange, setDateRange] = useState<[Dayjs, Dayjs]>([dayjs().subtract(29, 'day'), dayjs()]);
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [data, setData] = useState<PriceMatrixDTO | null>(null);

  const buildParams = () => ({
    sourceId: sourceId as string,
    from: dateRange[0]?.format('YYYY-MM-DD'),
    to: dateRange[1]?.format('YYYY-MM-DD'),
    keyword: keyword.trim() || undefined,
  });

  const query = async () => {
    if (!sourceId) { message.warning('矩阵视图必须先选定单一价格源'); return; }
    setLoading(true);
    try {
      const res = await elementPriceStrategyService.matrixPrices(buildParams());
      setData(res);
    } catch (e: any) {
      // 400（缺源 / 跨度超限）→ 提示，不清空已有结果
      message.warning(e?.message ?? '查询失败');
    } finally {
      setLoading(false);
    }
  };

  const handleExport = async () => {
    if (!sourceId) { message.warning('矩阵视图必须先选定单一价格源'); return; }
    setExporting(true);
    try {
      const blob = await elementPriceStrategyService.exportMatrix(buildParams());
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = '元素价格矩阵.xlsx';
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch (e: any) {
      message.warning(e?.message ?? '导出失败');
    } finally {
      setExporting(false);
    }
  };

  const dateColumns = (data?.dates ?? []).map((d, idx) => ({
    title: d.slice(5), // MM-DD
    key: d,
    align: 'right' as const,
    render: (_: unknown, r: PriceMatrixDTO['rows'][number]) => {
      const v = r.prices[idx];
      return v === null || v === undefined
        ? <span style={{ color: 'rgba(0,0,0,.45)' }}>—</span>
        : v.toFixed(2);
    },
  }));

  return (
    <div>
      <Alert
        type="warning"
        showIcon
        style={{ marginBottom: 16 }}
        message="矩阵视图日期跨度默认最近 30 天、最长 90 天；必须先选定单一价格源（同一天多个源各有各的价，一格放不下）。"
      />
      <div style={{ display: 'flex', alignItems: 'flex-end', gap: 16, marginBottom: 16, flexWrap: 'wrap' }}>
        <div style={{ width: 200 }}>
          <div style={{ marginBottom: 6 }}><span style={{ color: '#ff4d4f', marginRight: 3 }}>*</span>价格源</div>
          <Select
            style={{ width: '100%' }}
            placeholder="请选择价格源"
            value={sourceId}
            onChange={setSourceId}
            options={sources.map((s) => ({ value: s.id, label: s.sourceName }))}
          />
        </div>
        <div>
          <div style={{ marginBottom: 6 }}>日期区间</div>
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
          <Button type="primary" loading={loading} onClick={query}>查询</Button>
        </Space>
        <div style={{ flex: 1 }} />
        <Button icon={<DownloadOutlined />} loading={exporting} onClick={handleExport}>导出 Excel</Button>
      </div>

      <Table
        size="small"
        rowKey="elementCode"
        loading={loading}
        dataSource={data?.rows ?? []}
        scroll={{ x: 'max-content' }}
        pagination={false}
        columns={[
          {
            title: '元素',
            key: 'elementCode',
            fixed: 'left' as const,
            render: (_: unknown, r) => <b>{r.elementCode} {r.elementName}</b>,
          },
          ...dateColumns,
        ]}
      />
      <div style={{ marginTop: 10, fontSize: 12, color: 'rgba(0,0,0,.45)' }}>
        「—」= 当天该源无价格记录（周末 / 未录入）。
      </div>
    </div>
  );
};

export default PriceMatrixTab;
