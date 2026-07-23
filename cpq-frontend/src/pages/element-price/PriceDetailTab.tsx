import React, { useEffect, useState } from 'react';
import { Select, DatePicker, Input, Button, Table, Space, message } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import { elementPriceStrategyService } from '../../services/elementPriceStrategyService';
import type { PriceSourceDTO, ElementPriceRowDTO, PageResult } from '../../types/element-price-strategy';

const { RangePicker } = DatePicker;
const PAGE_SIZE = 20;

/**
 * 元素价格表 · 明细 Tab（task-0722 · F5）
 * 筛选：价格源（含全部）/ 日期区间（默认最近 30 天）/ 元素（符号或中文名模糊）；分页走后端参数；支持导出 Excel。
 */
interface Props {
  active: boolean;
  sources: PriceSourceDTO[];
}

const PriceDetailTab: React.FC<Props> = ({ active, sources }) => {
  const [sourceId, setSourceId] = useState<string | undefined>(undefined);
  const [dateRange, setDateRange] = useState<[Dayjs, Dayjs]>([dayjs().subtract(29, 'day'), dayjs()]);
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [data, setData] = useState<PageResult<ElementPriceRowDTO>>({ content: [], totalElements: 0, page: 0, size: PAGE_SIZE });

  const buildParams = (p: number) => ({
    sourceId,
    from: dateRange[0]?.format('YYYY-MM-DD'),
    to: dateRange[1]?.format('YYYY-MM-DD'),
    keyword: keyword.trim() || undefined,
    page: p,
    size: PAGE_SIZE,
  });

  const query = async (p = 0) => {
    setLoading(true);
    try {
      const res = await elementPriceStrategyService.listPrices(buildParams(p));
      setData(res);
      setPage(p);
    } catch (e: any) {
      message.error(e?.message ?? '查询失败');
    } finally {
      setLoading(false);
    }
  };

  // 首次进入该 Tab 时查一次；之后由用户点「查询」驱动
  useEffect(() => { if (active) query(0); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [active]);

  const handleReset = () => {
    setSourceId(undefined);
    setDateRange([dayjs().subtract(29, 'day'), dayjs()]);
    setKeyword('');
  };

  const handleExport = async () => {
    setExporting(true);
    try {
      const blob = await elementPriceStrategyService.exportPrices({
        sourceId,
        from: dateRange[0]?.format('YYYY-MM-DD'),
        to: dateRange[1]?.format('YYYY-MM-DD'),
        keyword: keyword.trim() || undefined,
      });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = '元素价格明细.xlsx';
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch (e: any) {
      message.error(e?.message ?? '导出失败');
    } finally {
      setExporting(false);
    }
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
          <Button type="primary" loading={loading} onClick={() => query(0)}>查询</Button>
          <Button onClick={handleReset}>重置</Button>
        </Space>
        <div style={{ flex: 1 }} />
        <Button icon={<DownloadOutlined />} loading={exporting} onClick={handleExport}>导出 Excel</Button>
      </div>

      <Table<ElementPriceRowDTO>
        size="small"
        rowKey={(r) => `${r.elementCode}__${r.sourceId}__${r.priceDate}`}
        loading={loading}
        dataSource={data.content}
        scroll={{ x: 'max-content' }}
        columns={[
          { title: '元素符号', dataIndex: 'elementCode' },
          { title: '中文名', dataIndex: 'elementName' },
          { title: '价格日期', dataIndex: 'priceDate' },
          {
            title: '价格源',
            dataIndex: 'sourceName',
            render: (v: string, r) => r.sourceStatus === 'DISABLED' ? <span style={{ color: 'rgba(0,0,0,.45)' }}>{v}</span> : v,
          },
          { title: '单价', dataIndex: 'price', align: 'right' as const, render: (v: number) => v.toFixed(4) },
          { title: '货币', dataIndex: 'currency' },
          { title: '计价单位', dataIndex: 'priceUnit' },
          { title: '录入人', dataIndex: 'operatorName' },
          {
            title: '录入时间',
            dataIndex: 'updatedAt',
            render: (v: string) => v ? dayjs(v).format('MM-DD HH:mm') : '—',
          },
        ]}
        pagination={{
          current: page + 1,
          pageSize: PAGE_SIZE,
          total: data.totalElements,
          onChange: (p) => query(p - 1),
          showTotal: (total) => `共 ${total} 条`,
        }}
      />
    </div>
  );
};

export default PriceDetailTab;
