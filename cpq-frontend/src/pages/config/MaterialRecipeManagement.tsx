import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Tag, Button, Space, Input, Select, message } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, ImportOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import SelectableTable, { runBatch } from '../../components/SelectableTable';
import type { ToolbarAction } from '../../components/SelectableTable';
import {
  SEARCH_WIDTH, FILTER_MIN_WIDTH, SEARCH_DEBOUNCE_MS, DEFAULT_PAGE_SIZE, commonPagination,
} from '../master-data/listConventions';
import { NO_SORT, clientSortProps, nextClientSort, type ClientSortState } from '../master-data/clientSorters';
import {
  materialRecipeService,
  type MaterialRecipeLite,
  type MaterialRecipeDetail,
} from '../../services/materialRecipeService';
import MaterialRecipeEditDrawer from './MaterialRecipeEditDrawer';
import MaterialImportDrawer from './MaterialImportDrawer';

const recipeTypeTag: Record<string, { label: string; color: string }> = {
  locked:   { label: '标准锁定', color: 'red' },
  editable: { label: '含量可调', color: 'green' },
  partial:  { label: '部分可调', color: 'orange' },
};

const recipeTypeLabel = (t?: string) => (t ? recipeTypeTag[t]?.label ?? t : '');
/** 与表格渲染口径一致：仅 'ACTIVE' 算启用，其余（含 undefined）都渲染/过滤为停用 */
const isActive = (s?: string) => s === 'ACTIVE';
const statusLabel = (s?: string) => (isActive(s) ? '启用' : '停用');

/** 时间格式化 YYYY-MM-DD HH:mm；空值回退 '—' */
const fmtTime = (v?: string) => (v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '—');

/**
 * 材质页签（task-0728 · F3）
 *
 * 版式：不套 Card（页签名即标题）；工具栏一行两组（左＝搜索 + 过滤，右＝刷新 / 导入 / 新建）；
 * 关键字仍走后端（`list({keyword})` 返全量），类型 / 状态两个过滤 + 分页 + 排序全在前端内存里做。
 */
const MaterialRecipeManagement: React.FC = () => {
  const [list, setList] = useState<MaterialRecipeLite[]>([]);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingDetail, setEditingDetail] = useState<MaterialRecipeDetail | null>(null);
  const [importOpen, setImportOpen] = useState(false);
  const [keyword, setKeyword] = useState('');
  const debounceRef = useRef<number | undefined>(undefined);

  // 前端过滤（D5：材质＝类型 + 状态，与关系）
  const [typeFilter, setTypeFilter] = useState<string | undefined>(undefined);
  const [statusFilter, setStatusFilter] = useState<string | undefined>(undefined);

  // 前端分页 / 排序
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState<number>(DEFAULT_PAGE_SIZE);
  const [sort, setSort] = useState<ClientSortState>(NO_SORT);

  // 列表顺序由后端定(启用优先→改时倒序→建时倒序)，未点击表头时不做本地 sort（= 三态里的「取消」态）。
  const refresh = async (kw?: string) => {
    setLoading(true);
    try {
      const data = await materialRecipeService.list(kw ? { keyword: kw } : undefined);
      setList(data);
    } catch (e: any) {
      message.error(e?.message ?? '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { refresh(); }, []);

  // 搜索框输入防抖 300ms → refresh(keyword)；清空拉全量
  const onKeywordChange = (v: string) => {
    setKeyword(v);
    setPage(1);
    window.clearTimeout(debounceRef.current);
    debounceRef.current = window.setTimeout(() => refresh(v.trim() || undefined), SEARCH_DEBOUNCE_MS);
  };
  useEffect(() => () => window.clearTimeout(debounceRef.current), []);

  const openCreate = () => {
    setEditingDetail(null);
    setDrawerOpen(true);
  };

  const openEdit = async (id: string) => {
    try {
      const detail = await materialRecipeService.detail(id);
      setEditingDetail(detail);
      setDrawerOpen(true);
    } catch (e: any) {
      message.error(e?.message ?? '加载详情失败');
    }
  };

  /** 排序三态推进；排序变化后回到第 1 页（需求说明 §4.3） */
  const cycleSort = (key: string) => {
    setSort((prev) => nextClientSort(prev, key));
    setPage(1);
  };
  const sortable = (key: string, get: (r: MaterialRecipeLite) => unknown, kind: 'text' | 'number' | 'time') =>
    clientSortProps<MaterialRecipeLite>(key, sort, cycleSort, get, kind);

  /** 类型 / 状态过滤（与关系）——关键字已在后端过滤过 */
  const filteredList = useMemo(
    () => list.filter((r) =>
      (!typeFilter || r.recipeType === typeFilter)
      && (!statusFilter || (statusFilter === 'ACTIVE' ? isActive(r.status) : !isActive(r.status)))),
    [list, typeFilter, statusFilter],
  );

  // 过滤后条数变少时，避免停在越界页码上
  useEffect(() => {
    const maxPage = Math.max(1, Math.ceil(filteredList.length / pageSize));
    if (page > maxPage) setPage(maxPage);
  }, [filteredList.length, pageSize, page]);

  const columns: ColumnsType<MaterialRecipeLite> = [
    {
      title: '材质编号',
      dataIndex: 'code',
      key: 'code',
      width: 120,
      ...sortable('code', (r) => r.code, 'text'),
      render: (v: string, r: MaterialRecipeLite) => (
        <a onClick={(e) => { e.stopPropagation(); openEdit(r.id); }}>{v}</a>
      ),
    },
    {
      title: '化学式',
      dataIndex: 'symbol',
      key: 'symbol',
      width: 140,
      ...sortable('symbol', (r) => r.symbol, 'text'),
    },
    {
      title: '名称',
      dataIndex: 'name',
      key: 'name',
      width: 160,
      ...sortable('name', (r) => r.name, 'text'),
    },
    {
      title: '类型',
      dataIndex: 'recipeType',
      key: 'recipeType',
      width: 100,
      // 按展示的中文标签排序，保证肉眼看到的顺序单调（同类型仍聚在一起）
      ...sortable('recipeType', (r) => recipeTypeLabel(r.recipeType), 'text'),
      render: (t: string) => (
        <Tag color={recipeTypeTag[t]?.color}>{recipeTypeTag[t]?.label ?? t}</Tag>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 80,
      ...sortable('status', (r) => statusLabel(r.status), 'text'),
      render: (s: string) => (
        <Tag color={isActive(s) ? 'green' : 'default'}>{statusLabel(s)}</Tag>
      ),
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 150,
      // 取原始 ISO 串比较，不用 fmtTime 的展示串
      ...sortable('createdAt', (r) => r.createdAt, 'time'),
      render: (v?: string) => fmtTime(v),
    },
    {
      title: '修改时间',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      width: 150,
      ...sortable('updatedAt', (r) => r.updatedAt, 'time'),
      render: (v?: string) => fmtTime(v),
    },
    {
      title: '排序',
      dataIndex: 'sortOrder',
      key: 'sortOrder',
      width: 80,
      ...sortable('sortOrder', (r) => r.sortOrder, 'number'),
    },
  ];

  const actions: ToolbarAction<MaterialRecipeLite>[] = [
    {
      key: 'edit',
      label: '编辑',
      icon: <EditOutlined />,
      enabledWhen: (rows) => rows.length === 1 ? true : '编辑一次只能选一行',
      onClick: (rows) => openEdit(rows[0].id),
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
      confirmTitle: '确认停用选中的 {N} 项材质?',
      confirmDescription: '停用后选配抽屉将不再显示。可在后台手动恢复 status=ACTIVE。',
      onClick: async (rows) => {
        await runBatch(
          rows,
          (r) => materialRecipeService.deleteSoft(r.id).then(() => undefined),
          { rowLabel: (r) => `${r.code} ${r.symbol}`, successMsg: `已停用 ${rows.length} 项` },
        );
        refresh();
      },
    },
  ];

  // 工具栏：左＝查询（搜索 → 过滤下拉），右＝动作（刷新 → 导入 → 新建）。
  // ⚠️ SelectableTable 内部已是 space-between 的 flex 容器，这里**不能**再包一层 div，否则右组会被挤到左边。
  const toolbar = (
    <>
      <Space wrap>
        <Input.Search
          placeholder="搜索 材质编号 / 化学式 / 名称 / 元素"
          allowClear
          style={{ width: SEARCH_WIDTH }}
          value={keyword}
          onChange={(e) => onKeywordChange(e.target.value)}
          onSearch={(v) => { setPage(1); refresh(v.trim() || undefined); }}
        />
        <Select
          allowClear
          placeholder="类型：全部"
          style={{ minWidth: FILTER_MIN_WIDTH }}
          value={typeFilter}
          onChange={(v) => { setTypeFilter(v); setPage(1); }}
          options={[
            { value: 'locked', label: '标准锁定' },
            { value: 'editable', label: '含量可调' },
            { value: 'partial', label: '部分可调' },
          ]}
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
        <Button icon={<ReloadOutlined />} onClick={() => refresh(keyword.trim() || undefined)}>
          刷新
        </Button>
        <Button icon={<ImportOutlined />} onClick={() => setImportOpen(true)}>
          导入材质库
        </Button>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新建材质
        </Button>
      </Space>
    </>
  );

  return (
    <>
      <SelectableTable<MaterialRecipeLite>
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
        rowLabel={(r) => `${r.code} ${r.symbol}`}
      />
      <MaterialRecipeEditDrawer
        open={drawerOpen}
        editingDetail={editingDetail}
        onClose={() => setDrawerOpen(false)}
        onSaved={() => { setDrawerOpen(false); refresh(); }}
        onPartsChanged={refresh}
      />
      <MaterialImportDrawer
        open={importOpen}
        onClose={() => setImportOpen(false)}
        onImported={() => { setImportOpen(false); refresh(); }}
      />
    </>
  );
};

export default MaterialRecipeManagement;
