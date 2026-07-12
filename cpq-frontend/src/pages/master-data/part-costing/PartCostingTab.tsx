// ─────────────────────────────────────────────────────────────────────────────
// PartCostingTab —— 料号核价列表（F2）
//   有核价数据的销售料号列表；搜索（料号/品名，防抖）；点行开抽屉。
//   本页主入口 = 点行进抽屉，无批量动作，属「列表操作规范」例外白名单
//   （纯查看 / Master-Detail 导航），用可点击行的 Table。
// ─────────────────────────────────────────────────────────────────────────────
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Table, Input, Tag, Space, Typography, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { listParts } from './api';
import type { PartRow } from './types';
import PartCostingDrawer from './PartCostingDrawer';

const { Text } = Typography;
const { Search } = Input;

function fmtTime(iso?: string | null): string {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString('zh-CN', { hour12: false });
  } catch {
    return iso;
  }
}

const PartCostingTab: React.FC = () => {
  const [keyword, setKeyword] = useState('');
  const [items, setItems] = useState<PartRow[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [loading, setLoading] = useState(false);

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [activeMaterialNo, setActiveMaterialNo] = useState<string | null>(null);

  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const fetchList = useCallback(async (kw: string, p: number, s: number) => {
    setLoading(true);
    try {
      const r = await listParts({ keyword: kw || undefined, page: p, size: s });
      setItems(r.items ?? []);
      setTotal(r.total ?? 0);
    } catch (e: any) {
      message.error(e?.message ?? '查询失败');
      setItems([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchList(keyword, page, size);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page, size]);

  const onKeywordChange = (v: string) => {
    setKeyword(v);
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
    debounceTimer.current = setTimeout(() => {
      setPage(1);
      fetchList(v, 1, size);
    }, 350);
  };

  const openDrawer = (row: PartRow) => {
    setActiveMaterialNo(row.materialNo);
    setDrawerOpen(true);
  };

  const columns: ColumnsType<PartRow> = [
    {
      title: '品名',
      dataIndex: 'materialName',
      key: 'materialName',
      render: (v: string, row) => <a onClick={() => openDrawer(row)}>{v || '—'}</a>,
    },
    { title: '料号', dataIndex: 'materialNo', key: 'materialNo', width: 160 },
    { title: '规格', dataIndex: 'specification', key: 'specification', width: 140, render: (v) => v || '—' },
    { title: '尺寸', dataIndex: 'dimension', key: 'dimension', width: 140, render: (v) => v || '—' },
    {
      title: '已配置',
      key: 'configured',
      width: 120,
      render: (_: unknown, row) => {
        const done = row.configuredCount >= row.totalSheets && row.totalSheets > 0;
        return (
          <Tag color={done ? 'green' : 'blue'}>
            {row.configuredCount}/{row.totalSheets}
          </Tag>
        );
      },
    },
    {
      title: '最近更新',
      dataIndex: 'lastUpdatedAt',
      key: 'lastUpdatedAt',
      width: 180,
      render: (v: string | null) => <Text type="secondary">{fmtTime(v)}</Text>,
    },
  ];

  return (
    <div>
      <Space style={{ marginBottom: 12 }}>
        <Search
          allowClear
          placeholder="按料号 / 品名搜索"
          style={{ width: 320 }}
          value={keyword}
          onChange={(e) => onKeywordChange(e.target.value)}
          onSearch={(v) => { setPage(1); fetchList(v, 1, size); }}
        />
      </Space>

      <Table<PartRow>
        rowKey="materialNo"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={items}
        locale={{ emptyText: '暂无有核价数据的料号' }}
        onRow={(row) => ({
          onClick: (e) => {
            const target = e.target as HTMLElement;
            if (target.closest('a, button')) return;
            openDrawer(row);
          },
          style: { cursor: 'pointer' },
        })}
        pagination={{
          current: page,
          pageSize: size,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => { setPage(p); setSize(s); },
        }}
      />

      <PartCostingDrawer
        open={drawerOpen}
        materialNo={activeMaterialNo}
        onClose={() => setDrawerOpen(false)}
      />
    </div>
  );
};

export default PartCostingTab;
