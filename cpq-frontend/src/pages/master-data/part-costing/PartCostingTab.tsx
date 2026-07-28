// ─────────────────────────────────────────────────────────────────────────────
// PartCostingTab —— 料号核价列表（task-0728 · F2）
//   有核价数据的销售料号列表；服务端分页 + 服务端排序 + 服务端过滤；点行开抽屉。
//   本页主入口 = 点行进抽屉，无批量动作，属「列表操作规范」例外白名单
//   （纯查看 / Master-Detail 导航），用可点击行的裸 <Table>。
//
//   ⚠️ 裸 <Table> 没有 SelectableTable 的 toolbar 容器，故工具栏要**自己套**
//      一层 TOOLBAR_ROW_STYLE（见 listConventions.ts 用法说明 1）。
// ─────────────────────────────────────────────────────────────────────────────
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Table, Input, Select, Button, Tag, Space, Typography, message } from 'antd';
import { ReloadOutlined, ImportOutlined } from '@ant-design/icons';
import type { ColumnsType, TableProps } from 'antd/es/table';
import type { SortOrder } from 'antd/es/table/interface';
import { listParts } from './api';
import type { PartSortBy } from './api';
import type { PartRow } from './types';
import PartCostingDrawer from './PartCostingDrawer';
import PricingBasicDataImportDrawer from '../PricingBasicDataImportDrawer';
import {
  SEARCH_WIDTH,
  FILTER_MIN_WIDTH,
  SEARCH_DEBOUNCE_MS,
  DEFAULT_PAGE_SIZE,
  commonPagination,
  TOOLBAR_ROW_STYLE,
} from '../listConventions';

const { Text } = Typography;
const { Search } = Input;

/**
 * 列 key → `api.md` A1 `sortBy` 白名单值。
 *
 * ⚠️ 「已配置」列只有 `key: 'configured'` 而**没有 dataIndex**，antd 回调里的
 *    `sorter.field` 会是 undefined，因此统一按 `sorter.columnKey` 查表，
 *    并把 `configured` 映射到契约里的 `configuredCount`。
 */
const SORT_KEY_MAP: Record<string, PartSortBy> = {
  materialName: 'materialName',
  materialNo: 'materialNo',
  specification: 'specification',
  dimension: 'dimension',
  configured: 'configuredCount',
  lastUpdatedAt: 'lastUpdatedAt',
};

/** 「配置状态」过滤：UI 三态 → A1 的 `configured`（boolean | undefined） */
type ConfiguredFilter = 'ALL' | 'DONE' | 'TODO';
const CONFIGURED_OPTIONS = [
  { value: 'ALL', label: '全部' },
  { value: 'DONE', label: '已配齐' },
  { value: 'TODO', label: '未配齐' },
];

function fmtTime(iso?: string | null): string {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString('zh-CN', { hour12: false });
  } catch {
    return iso;
  }
}

const PartCostingTab: React.FC = () => {
  // 搜索：inputValue = 输入框即时值；keyword = 防抖后真正参与查询的值
  const [inputValue, setInputValue] = useState('');
  const [keyword, setKeyword] = useState('');
  const [configuredFilter, setConfiguredFilter] = useState<ConfiguredFilter>('ALL');

  // 排序（服务端）：sortBy 为契约白名单值；sortOrder 为 antd 三态（null = 取消，回默认序）
  const [sortBy, setSortBy] = useState<PartSortBy | undefined>(undefined);
  const [sortOrder, setSortOrder] = useState<SortOrder>(null);

  const [items, setItems] = useState<PartRow[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(DEFAULT_PAGE_SIZE);
  const [loading, setLoading] = useState(false);

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [activeMaterialNo, setActiveMaterialNo] = useState<string | null>(null);

  // 导入核价数据抽屉（F1 从壳页顶部移入本页签，可见性沿用现状：不加角色判断）
  const [importOpen, setImportOpen] = useState(false);

  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => { if (debounceTimer.current) clearTimeout(debounceTimer.current); }, []);

  const configuredParam = useMemo<boolean | undefined>(() => {
    if (configuredFilter === 'DONE') return true;
    if (configuredFilter === 'TODO') return false;
    return undefined;
  }, [configuredFilter]);

  const fetchList = useCallback(async () => {
    setLoading(true);
    try {
      const r = await listParts({
        keyword: keyword || undefined,
        page,
        size,
        // sortOrder 仅在 sortBy 有值时才发送（契约：sortBy 为空时 sortOrder 无意义）
        sortBy: sortOrder ? sortBy : undefined,
        sortOrder: sortOrder ? (sortOrder === 'ascend' ? 'asc' : 'desc') : undefined,
        configured: configuredParam,
      });
      setItems(r.items ?? []);
      setTotal(r.total ?? 0);
    } catch (e: any) {
      message.error(e?.message ?? '查询失败');
      setItems([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [keyword, page, size, sortBy, sortOrder, configuredParam]);

  useEffect(() => { void fetchList(); }, [fetchList]);

  const onKeywordChange = (v: string) => {
    setInputValue(v);
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
    debounceTimer.current = setTimeout(() => {
      setKeyword(v);
      setPage(1); // 验收 12：搜索变化回第 1 页
    }, SEARCH_DEBOUNCE_MS);
  };

  const onKeywordSearch = (v: string) => {
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
    setInputValue(v);
    setKeyword(v);
    setPage(1);
  };

  const openDrawer = (row: PartRow) => {
    setActiveMaterialNo(row.materialNo);
    setDrawerOpen(true);
  };

  /**
   * 排序变化（三态：升序 → 降序 → 取消）。
   * - 只处理 `extra.action === 'sort'`；翻页/改页大小走 pagination.onChange，
   *   否则会被这里的 setPage(1) 顶回第 1 页；
   * - `sorter.order` 为 undefined/null = 第三态「取消」→ 清空 sortBy + sortOrder 回默认序。
   */
  const handleTableChange: TableProps<PartRow>['onChange'] = (_pagination, _filters, sorter, extra) => {
    if (extra?.action !== 'sort') return;
    const s = Array.isArray(sorter) ? sorter[0] : sorter;
    const order = s?.order ?? null;
    if (!order) {
      setSortBy(undefined);
      setSortOrder(null);
    } else {
      const columnKey = String(s?.columnKey ?? '');
      setSortBy(SORT_KEY_MAP[columnKey]);
      setSortOrder(order);
    }
    setPage(1); // 验收 19：排序变化回第 1 页
  };

  /** 受控 sortOrder：同一时刻只有命中 sortBy 的那一列高亮 */
  const orderOf = (columnKey: string): SortOrder =>
    sortBy && SORT_KEY_MAP[columnKey] === sortBy ? sortOrder : null;

  const columns: ColumnsType<PartRow> = [
    {
      title: '品名',
      dataIndex: 'materialName',
      key: 'materialName',
      sorter: true,
      sortOrder: orderOf('materialName'),
      render: (v: string, row) => <a onClick={() => openDrawer(row)}>{v || '—'}</a>,
    },
    {
      title: '料号',
      dataIndex: 'materialNo',
      key: 'materialNo',
      width: 160,
      sorter: true,
      sortOrder: orderOf('materialNo'),
    },
    {
      title: '规格',
      dataIndex: 'specification',
      key: 'specification',
      width: 140,
      sorter: true,
      sortOrder: orderOf('specification'),
      render: (v) => v || '—',
    },
    {
      title: '尺寸',
      dataIndex: 'dimension',
      key: 'dimension',
      width: 140,
      sorter: true,
      sortOrder: orderOf('dimension'),
      render: (v) => v || '—',
    },
    {
      title: '已配置',
      key: 'configured', // 无 dataIndex —— 排序按 columnKey 映射为 configuredCount
      width: 120,
      sorter: true,
      sortOrder: orderOf('configured'),
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
      sorter: true,
      sortOrder: orderOf('lastUpdatedAt'),
      render: (v: string | null) => <Text type="secondary">{fmtTime(v)}</Text>,
    },
  ];

  return (
    <div>
      {/* F0-3 工具栏：左＝搜索 + 过滤，右＝刷新 + 导入 */}
      <div style={TOOLBAR_ROW_STYLE}>
        <Space wrap>
          <Search
            allowClear
            placeholder="按料号 / 品名搜索"
            style={{ width: SEARCH_WIDTH }}
            value={inputValue}
            onChange={(e) => onKeywordChange(e.target.value)}
            onSearch={onKeywordSearch}
          />
          <Select<ConfiguredFilter>
            value={configuredFilter}
            style={{ minWidth: FILTER_MIN_WIDTH }}
            options={CONFIGURED_OPTIONS}
            onChange={(v) => { setConfiguredFilter(v ?? 'ALL'); setPage(1); }}
          />
        </Space>
        <Space wrap>
          <Button icon={<ReloadOutlined />} onClick={() => { void fetchList(); }}>刷新</Button>
          <Button type="primary" icon={<ImportOutlined />} onClick={() => setImportOpen(true)}>
            导入核价数据
          </Button>
        </Space>
      </div>

      <Table<PartRow>
        rowKey="materialNo"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={items}
        onChange={handleTableChange}
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
          ...commonPagination,
          current: page,
          pageSize: size,
          total,
          onChange: (p, s) => { setPage(p); setSize(s); },
        }}
      />

      <PartCostingDrawer
        open={drawerOpen}
        materialNo={activeMaterialNo}
        onClose={() => setDrawerOpen(false)}
      />

      {/* 导入完成后刷新列表（改造前壳页顶部按钮没有这一步，属顺手补齐的合理行为） */}
      <PricingBasicDataImportDrawer
        open={importOpen}
        onClose={() => { setImportOpen(false); void fetchList(); }}
      />
    </div>
  );
};

export default PartCostingTab;
