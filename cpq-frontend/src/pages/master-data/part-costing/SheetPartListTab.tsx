// ─────────────────────────────────────────────────────────────────────────────
// SheetPartListTab —— 「料号列表 → 点行开抽屉」页签的通用骨架（task-260902 · F-1）
//
// 由现有 `PartCostingTab` 原样抽出，供三方共用：
//   · 料号核价（/pricing-basic-data）      —— PartCostingTab 薄封装
//   · 基础核价（/dataset/cost-basic）      —— DatasetPartListTab 薄封装
//   · 详细核价（/dataset/cost-detail）     —— 同上，仅 dataset 不同
//
// 本组件只装**行为**（防抖搜索 / 三态服务端排序 / 服务端分页 / 配置状态过滤 /
// 点行开抽屉），**不装任何列定义与文案** —— 列、占位符、空态文案、工具栏动作、
// 抽屉全部由调用方通过 render prop 提供，因此各页签的视觉可以各自对齐自己的原型，
// 不需要在本文件里塞 variant 分支。
//
// ⚠️ 抽取纪律（AC-42）：从 PartCostingTab 搬过来的逻辑**一行未改语义** ——
//    含「搜索/排序变化回第 1 页」「sortOrder 为空时不发 sortBy」「点 a/button 不触发行点击」
//    「裸 <Table> 自套 TOOLBAR_ROW_STYLE」等既有行为，逐条保留。
// ─────────────────────────────────────────────────────────────────────────────
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Table, Input, Select, Button, Space, message } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType, TableProps } from 'antd/es/table';
import type { SortOrder } from 'antd/es/table/interface';
import {
  SEARCH_WIDTH,
  FILTER_MIN_WIDTH,
  SEARCH_DEBOUNCE_MS,
  DEFAULT_PAGE_SIZE,
  commonPagination,
  TOOLBAR_ROW_STYLE,
} from '../listConventions';

const { Search } = Input;

/** 「配置状态」过滤的 UI 三态 */
export type ConfiguredFilter = 'ALL' | 'DONE' | 'TODO';

export interface FetchPartsParams {
  keyword?: string;
  /** **1-based**。0-based 的数据源在自己的 fetchParts 里减 1（见 dataset/api.ts） */
  page: number;
  size: number;
  sortBy?: string;
  sortOrder?: 'asc' | 'desc';
  configured?: boolean;
}

/** 调用方 render prop 能拿到的上下文 */
export interface SheetPartListCtx<T> {
  /** 重新拉取当前页（导入完成后刷新列表用） */
  refresh: () => void;
  /** 受控 sortOrder：同一时刻只有命中 sortBy 的那一列高亮 */
  orderOf: (columnKey: string) => SortOrder;
  openDrawer: (row: T) => void;
  drawerOpen: boolean;
  activeRow: T | null;
  closeDrawer: () => void;
}

export interface SheetPartListTabProps<T> {
  fetchParts: (p: FetchPartsParams) => Promise<{ items: T[]; total: number }>;
  /** 行主键取值（= 轴值） */
  rowKeyOf: (row: T) => string;
  /** 列 columnKey → 后端 sortBy 白名单值 */
  sortKeyMap: Record<string, string>;
  searchPlaceholder: string;
  /** 「配置状态」过滤下拉的候选（含各自原型的文案） */
  configuredOptions: { value: ConfiguredFilter; label: string }[];
  /** 表格空态文案 */
  emptyText: string;
  columns: (ctx: SheetPartListCtx<T>) => ColumnsType<T>;
  /** 工具栏右侧「刷新」之后的动作（如导入按钮）。刷新按钮由本组件提供 */
  toolbarActions?: (ctx: SheetPartListCtx<T>) => React.ReactNode;
  /** 抽屉等挂件 */
  children?: (ctx: SheetPartListCtx<T>) => React.ReactNode;
  /**
   * 表格横向滚动配置（可选）。
   * 不传＝不设 scroll，与现有「料号核价」页签改造前一致（AC-42）。
   * dataset 侧列多且都定宽，需给一个 x 让窄视口横向滚动而不是把列挤到换行
   *（原型「核价数据-列表」要求「列不换行、不撑破布局」）。
   */
  tableScroll?: TableProps<T>['scroll'];
}

function SheetPartListTab<T>(props: SheetPartListTabProps<T>) {
  const {
    fetchParts, rowKeyOf, sortKeyMap, searchPlaceholder,
    configuredOptions, emptyText, columns, toolbarActions, children, tableScroll,
  } = props;

  // 搜索：inputValue = 输入框即时值；keyword = 防抖后真正参与查询的值
  const [inputValue, setInputValue] = useState('');
  const [keyword, setKeyword] = useState('');
  const [configuredFilter, setConfiguredFilter] = useState<ConfiguredFilter>('ALL');

  // 排序（服务端）：sortBy 为契约白名单值；sortOrder 为 antd 三态（null = 取消，回默认序）
  const [sortBy, setSortBy] = useState<string | undefined>(undefined);
  const [sortOrder, setSortOrder] = useState<SortOrder>(null);

  const [items, setItems] = useState<T[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(DEFAULT_PAGE_SIZE);
  const [loading, setLoading] = useState(false);

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [activeRow, setActiveRow] = useState<T | null>(null);

  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => { if (debounceTimer.current) clearTimeout(debounceTimer.current); }, []);

  const configuredParam = useMemo<boolean | undefined>(() => {
    if (configuredFilter === 'DONE') return true;
    if (configuredFilter === 'TODO') return false;
    return undefined;
  }, [configuredFilter]);

  const fetchRef = useRef(fetchParts);
  fetchRef.current = fetchParts;

  const fetchList = useCallback(async () => {
    setLoading(true);
    try {
      const r = await fetchRef.current({
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
      setPage(1); // 搜索变化回第 1 页
    }, SEARCH_DEBOUNCE_MS);
  };

  const onKeywordSearch = (v: string) => {
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
    setInputValue(v);
    setKeyword(v);
    setPage(1);
  };

  const openDrawer = useCallback((row: T) => {
    setActiveRow(row);
    setDrawerOpen(true);
  }, []);

  /**
   * 排序变化（三态：升序 → 降序 → 取消）。
   * - 只处理 `extra.action === 'sort'`；翻页/改页大小走 pagination.onChange，
   *   否则会被这里的 setPage(1) 顶回第 1 页；
   * - `sorter.order` 为 undefined/null = 第三态「取消」→ 清空 sortBy + sortOrder 回默认序。
   */
  const handleTableChange: TableProps<T>['onChange'] = (_pagination, _filters, sorter, extra) => {
    if (extra?.action !== 'sort') return;
    const s = Array.isArray(sorter) ? sorter[0] : sorter;
    const order = s?.order ?? null;
    if (!order) {
      setSortBy(undefined);
      setSortOrder(null);
    } else {
      const columnKey = String(s?.columnKey ?? '');
      setSortBy(sortKeyMap[columnKey]);
      setSortOrder(order);
    }
    setPage(1); // 排序变化回第 1 页
  };

  const orderOf = useCallback(
    (columnKey: string): SortOrder =>
      (sortBy && sortKeyMap[columnKey] === sortBy ? sortOrder : null),
    [sortBy, sortOrder, sortKeyMap],
  );

  const ctx: SheetPartListCtx<T> = {
    refresh: () => { void fetchList(); },
    orderOf,
    openDrawer,
    drawerOpen,
    activeRow,
    closeDrawer: () => setDrawerOpen(false),
  };

  return (
    <div>
      {/* F0-3 工具栏：左＝搜索 + 过滤，右＝刷新 + 动作 */}
      <div style={TOOLBAR_ROW_STYLE}>
        <Space wrap>
          <Search
            allowClear
            placeholder={searchPlaceholder}
            style={{ width: SEARCH_WIDTH }}
            value={inputValue}
            onChange={(e) => onKeywordChange(e.target.value)}
            onSearch={onKeywordSearch}
          />
          <Select<ConfiguredFilter>
            value={configuredFilter}
            style={{ minWidth: FILTER_MIN_WIDTH }}
            options={configuredOptions}
            onChange={(v) => { setConfiguredFilter(v ?? 'ALL'); setPage(1); }}
          />
        </Space>
        <Space wrap>
          <Button icon={<ReloadOutlined />} onClick={() => { void fetchList(); }}>刷新</Button>
          {toolbarActions?.(ctx)}
        </Space>
      </div>

      <Table<T>
        rowKey={(r) => rowKeyOf(r)}
        size="small"
        loading={loading}
        columns={columns(ctx)}
        dataSource={items}
        onChange={handleTableChange}
        locale={{ emptyText }}
        scroll={tableScroll}
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

      {children?.(ctx)}
    </div>
  );
}

export default SheetPartListTab;
