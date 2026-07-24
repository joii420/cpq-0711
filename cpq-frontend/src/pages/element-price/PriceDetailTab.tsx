import React, { useEffect, useState } from 'react';
import { Select, DatePicker, Input, Button, Space, Tag, message } from 'antd';
import { DownloadOutlined, PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import dayjs, { type Dayjs } from 'dayjs';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';
import { elementPriceStrategyService } from '../../services/elementPriceStrategyService';
import type { PriceSourceDTO, ElementPriceRowDTO, PageResult, PriceFetchStatus } from '../../types/element-price-strategy';
import PriceEditDrawer from './PriceEditDrawer';

const { RangePicker } = DatePicker;
const PAGE_SIZE = 20;

const FETCH_STATUS_TAG: Record<PriceFetchStatus, { color?: string; label: string }> = {
  MANUAL: { color: 'blue', label: '手工' },
  IMPORT: { color: undefined, label: '导入' },
  SUCCESS: { color: 'green', label: 'SUCCESS' },
  FAILED: { color: 'red', label: 'FAILED' },
};

/**
 * 元素价格表 · 明细 Tab（task-0722 · F5 → update-0724 · F2 改造为 SelectableTable + 工具栏）
 * 筛选：价格源（含全部）/ 日期区间（默认最近 30 天）/ 元素（符号或中文名模糊）；分页走后端参数；支持导出 Excel。
 * 行内不放动作按钮，新建/编辑/删除全部上提到顶部工具栏（docs/列表操作规范.md 强制项）。
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

  // 价格编辑抽屉（二级，480）
  const [editorOpen, setEditorOpen] = useState(false);
  const [editorMode, setEditorMode] = useState<'create' | 'edit'>('create');
  const [editingRow, setEditingRow] = useState<ElementPriceRowDTO | null>(null);

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

  const openEditor = (mode: 'create' | 'edit', row?: ElementPriceRowDTO) => {
    setEditorMode(mode);
    setEditingRow(mode === 'edit' ? (row ?? null) : null);
    setEditorOpen(true);
  };

  const rowLabel = (r: ElementPriceRowDTO) => `${r.elementCode} · ${r.sourceName} · ${r.priceDate}`;

  const actions: ToolbarAction<ElementPriceRowDTO>[] = [
    {
      key: 'create',
      label: '新建',
      icon: <PlusOutlined />,
      enabledWhen: () => true,
      onClick: () => openEditor('create'),
    },
    {
      key: 'edit',
      label: '编辑',
      icon: <EditOutlined />,
      enabledWhen: (rows) => (rows.length === 1 ? true : '编辑一次只能选一行'),
      onClick: (rows) => openEditor('edit', rows[0]),
    },
    {
      key: 'delete',
      label: '删除',
      icon: <DeleteOutlined />,
      danger: true,
      needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 条价格？',
      confirmDescription: '删除后该源该日期的价格将从取价窗口中消失，可能影响客户报价成本。',
      enabledWhen: (rows) => (rows.length >= 1 ? true : '请先勾选要删除的价格'),
      onClick: async (rows) => {
        const { ok } = await runBatch(
          rows,
          (r) => elementPriceStrategyService.deletePrice(r.id),
          { rowLabel },
        );
        if (ok > 0) message.success(`已删除 ${ok} 条`);
        query(page); // 刷新当前页
      },
    },
  ];

  return (
    <div>
      <SelectableTable<ElementPriceRowDTO>
        size="small"
        rowKey="id"
        loading={loading}
        dataSource={data.content}
        scroll={{ x: 'max-content' }}
        actions={actions}
        rowLabel={rowLabel}
        toolbar={
          <div style={{ display: 'flex', alignItems: 'flex-end', gap: 16, flexWrap: 'wrap', width: '100%' }}>
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
        }
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
          {
            title: '数据来源',
            dataIndex: 'fetchStatus',
            render: (v: PriceFetchStatus) => {
              const t = FETCH_STATUS_TAG[v] ?? { label: v };
              return <Tag color={t.color}>{t.label}</Tag>;
            },
          },
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

      <PriceEditDrawer
        open={editorOpen}
        mode={editorMode}
        editing={editingRow}
        sources={sources}
        onClose={() => setEditorOpen(false)}
        onSaved={() => query(page)}
      />
    </div>
  );
};

export default PriceDetailTab;
