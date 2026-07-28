import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Tag, Button, Space, Input, Select, Dropdown, Tooltip, message } from 'antd';
import {
  PlusOutlined, EditOutlined, DeleteOutlined, LockOutlined,
  LinkOutlined, ImportOutlined, TableOutlined, DownOutlined, ReloadOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import SelectableTable, { runBatch } from '../../components/SelectableTable';
import type { ToolbarAction } from '../../components/SelectableTable';
import {
  SEARCH_WIDTH, FILTER_MIN_WIDTH, SEARCH_DEBOUNCE_MS, DEFAULT_PAGE_SIZE, commonPagination,
} from '../master-data/listConventions';
import { NO_SORT, clientSortProps, nextClientSort, type ClientSortState } from '../master-data/clientSorters';
import { elementService, type ElementItem } from '../../services/elementService';
import ElementEditDrawer from './ElementEditDrawer';
import PriceSourceManagerDrawer from '../element-price/PriceSourceManagerDrawer';
import PriceImportDrawer from '../element-price/PriceImportDrawer';
import ElementPriceTableDrawer from '../element-price/ElementPriceTableDrawer';

/** 时间格式化 YYYY-MM-DD HH:mm；空值回退 '—' */
const fmtTime = (v?: string) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '—');

/** 与表格渲染口径一致：仅 'ACTIVE' 算启用 */
const isActive = (s?: string) => s === 'ACTIVE';
const statusLabel = (s?: string) => (isActive(s) ? '启用' : '停用');

/**
 * 元素页签（task-0728 · F4）
 *
 * 版式：不套 Card（页签名即标题）；工具栏一行两组（左＝搜索 + 状态过滤，右＝元素价格▾ / 刷新 / 新建）；
 * 关键字仍走后端（返全量），状态过滤 + 分页 + 排序全在前端内存里做。
 * 三个价格入口（价格源管理 / 价格导入 / 元素价格表）收进「元素价格 ▾」下拉，
 * ⚠️ 三个 Drawer 的挂载与 props 一律不动，只换触发入口（task-0728 · D3）。
 */
const ElementManagement: React.FC = () => {
  const [list, setList] = useState<ElementItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<ElementItem | null>(null);
  const [keyword, setKeyword] = useState('');
  const debounceRef = useRef<number | undefined>(undefined);

  // 前端过滤（D5：元素＝状态）
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);

  // 前端分页 / 排序
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState<number>(DEFAULT_PAGE_SIZE);
  const [sort, setSort] = useState<ClientSortState>(NO_SORT);

  // task-0722 · F1：3 个新入口（价格源管理 / 价格导入 / 元素价格表）
  const [sourceManagerOpen, setSourceManagerOpen] = useState(false);
  const [priceImportOpen, setPriceImportOpen] = useState(false);
  const [priceTableOpen, setPriceTableOpen] = useState(false);

  // 排序由后端定(启用优先→最后修改时间倒序)，未点击表头时不做本地 sort（= 三态里的「取消」态）。
  const refresh = async (kw?: string) => {
    setLoading(true);
    try {
      setList(await elementService.list(kw));
    } catch (e: any) {
      message.error(e?.message ?? '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { refresh(); }, []);

  // 搜索防抖 300ms；清空拉全量
  const onKeywordChange = (v: string) => {
    setKeyword(v);
    setPage(1);
    window.clearTimeout(debounceRef.current);
    debounceRef.current = window.setTimeout(() => refresh(v.trim() || undefined), SEARCH_DEBOUNCE_MS);
  };
  useEffect(() => () => window.clearTimeout(debounceRef.current), []);

  const openCreate = () => { setEditing(null); setDrawerOpen(true); };
  const openEdit = (row: ElementItem) => { setEditing(row); setDrawerOpen(true); };

  /** 排序三态推进；排序变化后回到第 1 页（需求说明 §4.3） */
  const cycleSort = (key: string) => {
    setSort((prev) => nextClientSort(prev, key));
    setPage(1);
  };
  const sortable = (key: string, get: (r: ElementItem) => unknown, kind: 'text' | 'number' | 'time') =>
    clientSortProps<ElementItem>(key, sort, cycleSort, get, kind);

  /** 状态过滤 —— 关键字已在后端过滤过 */
  const filteredList = useMemo(
    () => list.filter((r) =>
      !statusFilter || (statusFilter === 'ACTIVE' ? isActive(r.status) : !isActive(r.status))),
    [list, statusFilter],
  );

  // 过滤后条数变少时，避免停在越界页码上
  useEffect(() => {
    const maxPage = Math.max(1, Math.ceil(filteredList.length / pageSize));
    if (page > maxPage) setPage(maxPage);
  }, [filteredList.length, pageSize, page]);

  const columns: ColumnsType<ElementItem> = [
    {
      title: '元素编号',
      dataIndex: 'elementNo',
      key: 'elementNo',
      width: 120,
      ...sortable('elementNo', (r) => r.elementNo, 'text'),
      render: (v: string, r: ElementItem) => (
        <a onClick={(e) => { e.stopPropagation(); openEdit(r); }}>{v}</a>
      ),
    },
    {
      title: '符号',
      dataIndex: 'elementCode',
      key: 'elementCode',
      width: 120,
      // ⚠️ render 里带锁图标包了 <Space>，排序必须取 elementCode 原值，不受 render 影响
      ...sortable('elementCode', (r) => r.elementCode, 'text'),
      render: (v: string, r: ElementItem) => (
        <Space size={4}>
          <span>{v}</span>
          {r.codeLocked && (
            <Tooltip title={`已被 ${r.referencedCount} 个材质引用，符号不可修改`}>
              <LockOutlined style={{ color: '#8c8c8c' }} />
            </Tooltip>
          )}
        </Space>
      ),
    },
    {
      title: '中文名',
      dataIndex: 'elementName',
      key: 'elementName',
      width: 140,
      ...sortable('elementName', (r) => r.elementName, 'text'),
    },
    {
      title: '被引用材质数',
      dataIndex: 'referencedCount',
      key: 'referencedCount',
      width: 130,
      ...sortable('referencedCount', (r) => r.referencedCount, 'number'),
      render: (n: number) => <Tag color={n > 0 ? 'blue' : 'default'}>{n ?? 0}</Tag>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      ...sortable('status', (r) => statusLabel(r.status), 'text'),
      render: (s: string) => (
        <Tag color={isActive(s) ? 'green' : 'default'}>{statusLabel(s)}</Tag>
      ),
    },
    {
      // task-0722 · F1：「创建时间」+「修改时间」合并为「最后修改时间」
      // = MAX(元素主档 updated_at, 该元素所有价格记录的 updated_at)，价格导入也算一次修改（api.md §4.2）。
      // 默认序由后端定(启用优先→本字段倒序)；点表头才前端排序，取原始 ISO 串比较。
      title: '最后修改时间',
      dataIndex: 'lastModifiedAt',
      key: 'lastModifiedAt',
      width: 160,
      ...sortable('lastModifiedAt', (r) => r.lastModifiedAt, 'time'),
      render: (v?: string) => fmtTime(v),
    },
  ];

  const actions: ToolbarAction<ElementItem>[] = [
    {
      key: 'edit',
      label: '编辑',
      icon: <EditOutlined />,
      enabledWhen: (rows) => rows.length === 1 ? true : '编辑一次只能选一行',
      onClick: (rows) => openEdit(rows[0]),
    },
    {
      key: 'delete',
      label: '停用',
      icon: <DeleteOutlined />,
      danger: true,
      enabledWhen: (rows) => {
        if (rows.length === 0) return false;
        if (rows.some(r => r.status !== 'ACTIVE')) return '仅启用状态可停用';
        return true;
      },
      needsConfirm: true,
      confirmTitle: '确认停用选中的 {N} 个元素?',
      confirmDescription: '停用后不再可被新材质/新导入选用；历史材质靠元素编号照常显示。可在编辑抽屉重新启用。',
      onClick: async (rows) => {
        await runBatch(
          rows,
          (r) => elementService.deleteSoft(r.elementNo).then(() => undefined),
          { rowLabel: (r) => `${r.elementNo} ${r.elementCode}`, successMsg: `已停用 ${rows.length} 个元素` },
        );
        refresh();
      },
    },
  ];

  // D3：三个价格入口收进「元素价格 ▾」下拉；抽屉本身与 props 不动，只换触发入口
  const priceMenuItems = [
    { key: 'source', label: '价格源管理', icon: <LinkOutlined /> },
    { key: 'import', label: '价格导入', icon: <ImportOutlined /> },
    { key: 'table', label: '元素价格表', icon: <TableOutlined /> },
  ];
  const onPriceMenuClick = ({ key }: { key: string }) => {
    if (key === 'source') setSourceManagerOpen(true);
    else if (key === 'import') setPriceImportOpen(true);
    else if (key === 'table') setPriceTableOpen(true);
  };

  // 工具栏：左＝查询（搜索 → 状态过滤），右＝动作（元素价格▾ → 刷新 → 新建）。
  // ⚠️ SelectableTable 内部已是 space-between 的 flex 容器，这里**不能**再包一层 div，否则右组会被挤到左边。
  const toolbar = (
    <>
      <Space wrap>
        <Input.Search
          placeholder="搜索 元素编号 / 符号 / 中文名"
          allowClear
          style={{ width: SEARCH_WIDTH }}
          value={keyword}
          onChange={(e) => onKeywordChange(e.target.value)}
          onSearch={(v) => { setPage(1); refresh(v.trim() || undefined); }}
        />
        <Select
          allowClear
          placeholder="状态：全部"
          style={{ minWidth: FILTER_MIN_WIDTH }}
          value={statusFilter}
          onChange={(v) => { setStatusFilter(v); setPage(1); }}
          options={[
            { value: 'ACTIVE', label: '启用' },
            { value: 'INACTIVE', label: '停用' },
          ]}
        />
      </Space>
      <Space wrap>
        {/* task-0722 · F1 的 3 个入口，可见性沿用本页现有权限，不新增权限判断 */}
        <Dropdown menu={{ items: priceMenuItems, onClick: onPriceMenuClick }}>
          <Button>
            <Space size={4}>
              元素价格
              <DownOutlined />
            </Space>
          </Button>
        </Dropdown>
        <Button icon={<ReloadOutlined />} onClick={() => refresh(keyword.trim() || undefined)}>
          刷新
        </Button>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新建元素
        </Button>
      </Space>
    </>
  );

  return (
    <>
      <SelectableTable<ElementItem>
        rowKey="id"
        size="small"
        columns={columns}
        dataSource={filteredList}
        loading={loading}
        toolbar={toolbar}
        pagination={{
          ...commonPagination,
          current: page,
          pageSize,
          onChange: (p, ps) => { setPage(p); setPageSize(ps); },
        }}
        actions={actions}
        rowLabel={(r) => `${r.elementNo} ${r.elementCode}`}
      />
      <ElementEditDrawer
        open={drawerOpen}
        editing={editing}
        onClose={() => setDrawerOpen(false)}
        onSaved={() => { setDrawerOpen(false); refresh(); }}
      />
      <PriceSourceManagerDrawer
        open={sourceManagerOpen}
        onClose={() => setSourceManagerOpen(false)}
      />
      <PriceImportDrawer
        open={priceImportOpen}
        onClose={() => setPriceImportOpen(false)}
        onImported={() => refresh(keyword.trim() || undefined)}
      />
      <ElementPriceTableDrawer
        open={priceTableOpen}
        onClose={() => setPriceTableOpen(false)}
      />
    </>
  );
};

export default ElementManagement;
