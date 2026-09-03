// ─────────────────────────────────────────────────────────────────────────────
// PlatingSchemeTab —— 「电镀方案」页签（task-260902 · F-10 / S-9，裁决 D-21）
//
// 视觉基准：`原型图/电镀方案-页签.html`。服务的 AC：AC-48 / AC-49 / AC-50 / AC-51。
//
// 三条硬约束：
//   1. 🚨 **列定义完全由 `GET /dataset/{ds}/plating-schemes` 的 `columns` 驱动**，
//      前端不得写死任何一列 —— 报价 10 列、详细核价 8 列，字段本就不同（AC-49）。
//   2. 🚫 **只读**：本页签不得出现「新增 / 编辑 / 删除 / 保存」任何按钮，
//      单元格不可进入编辑态（AC-51）。故**不复用** EditableSheetTable，用普通只读 <Table>。
//   3. 一个页签 + 页内数据集下拉（报价 / 详细核价），默认「报价」；
//      🚫 下拉里没有「基础核价」—— 它没有电镀方案表，后端对该 dataset 返 404。
// ─────────────────────────────────────────────────────────────────────────────
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Table, Input, Select, Button, Space, Alert, Typography, message } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { createDatasetApi } from './api';
import type { PlatingDatasetKey, PlatingSchemeColumn } from './types';
import type { ColumnType } from '../part-costing/types';
import {
  SEARCH_WIDTH, SEARCH_DEBOUNCE_MS, DEFAULT_PAGE_SIZE, commonPagination, TOOLBAR_ROW_STYLE,
} from '../listConventions';
import { normalizeDecimalString, isDecimalString } from '../../../utils/precision';

const { Search } = Input;
const { Text } = Typography;

/** 页顶固定说明（AC-51 逐字） */
const READONLY_HINT = '电镀方案为导入维护，如需修改请通过「导入报价数据」/「导入核价数据」重新导入';

/** 空态（AC-51 同屏） */
const EMPTY_TEXT = '暂无电镀方案数据，请先导入';

/** 数据集下拉：只有这两个有电镀方案表 */
const DATASET_OPTIONS: { value: PlatingDatasetKey; label: string }[] = [
  { value: 'quote', label: '报价' },
  { value: 'cost-detail', label: '详细核价' },
];

/**
 * 单元格展示：数值列**只去尾零、不截位**（与 EditableSheetTable.displayText 同口径）。
 * 后端按库中 scale 定标回传（"0.031000000000"），原型显示 `0.031`。
 * 🚫 不用 formatDisplayDecimal（截 9 位）—— 这里是只读展示，没有理由丢有效位。
 *
 * 🚨 F-11 同款纪律：**由接口下发的列类型决定**，不按值的形状猜。
 *    否则 `方案编号` / `版本` 这类 STRING 编码列只要长得像数字就会被抹掉前导零
 *    （`00001` → `1`），与 AC-53 是同一个缺陷。
 */
function displayCell(v: unknown, type?: ColumnType): React.ReactNode {
  if (v === null || v === undefined || v === '') return <Text type="secondary">—</Text>;
  if (typeof v === 'boolean') return v ? '是' : '否';
  const isNumericColumn = type === 'DECIMAL' || type === 'NUMBER';
  if (isNumericColumn && typeof v === 'string' && isDecimalString(v)) return normalizeDecimalString(v);
  return String(v);
}

const PlatingSchemeTab: React.FC = () => {
  const [dataset, setDataset] = useState<PlatingDatasetKey>('quote');
  const api = useMemo(() => createDatasetApi(dataset), [dataset]);

  const [inputValue, setInputValue] = useState('');
  const [keyword, setKeyword] = useState('');
  const [columns, setColumns] = useState<PlatingSchemeColumn[]>([]);
  const [items, setItems] = useState<Record<string, unknown>[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);            // UI 1-based；请求时减 1（契约 0-based）
  const [size, setSize] = useState(DEFAULT_PAGE_SIZE);
  const [loading, setLoading] = useState(false);

  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => { if (debounceTimer.current) clearTimeout(debounceTimer.current); }, []);

  const fetchList = useCallback(async () => {
    setLoading(true);
    try {
      const r = await api.listPlatingSchemes({
        page: Math.max(0, page - 1),
        size,
        keyword: keyword || undefined,
      });
      setColumns(r.columns ?? []);
      setItems(r.items ?? []);
      setTotal(r.total ?? 0);
    } catch (e: any) {
      message.error(e?.message ?? '查询失败');
      // 🚫 失败也要落到空态，不留「加载中…」永久占位（AP-31 / AP-38 族）
      setColumns([]);
      setItems([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [api, page, size, keyword]);

  useEffect(() => { void fetchList(); }, [fetchList]);

  const onKeywordChange = (v: string) => {
    setInputValue(v);
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
    debounceTimer.current = setTimeout(() => { setKeyword(v); setPage(1); }, SEARCH_DEBOUNCE_MS);
  };

  const onKeywordSearch = (v: string) => {
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
    setInputValue(v);
    setKeyword(v);
    setPage(1);
  };

  // 🚨 AC-49：列全部来自接口，这里只做「后端列 → antd 列」的机械映射，不加任何硬编码列
  const tableColumns: ColumnsType<Record<string, unknown>> = useMemo(
    () => (columns ?? []).map((c) => ({
      title: c.label,
      dataIndex: c.name,
      key: c.name,
      render: (v: unknown) => displayCell(v, c.type),
    })),
    [columns],
  );

  return (
    <div>
      <Alert type="info" showIcon message={READONLY_HINT} style={{ marginBottom: 12 }} />

      {/* 工具栏：左＝数据集下拉 + 搜索，右＝刷新。🚫 只读页签，右侧没有任何写入口按钮 */}
      <div style={TOOLBAR_ROW_STYLE}>
        <Space wrap>
          <Text type="secondary">数据集</Text>
          <Select<PlatingDatasetKey>
            value={dataset}
            style={{ width: 150 }}
            options={DATASET_OPTIONS}
            onChange={(v) => {
              // 切数据集 = 换一张表（列数都不同）：列与数据一并重拉，页码回第 1 页
              setDataset(v);
              setColumns([]);
              setItems([]);
              setTotal(0);
              setPage(1);
            }}
          />
          <Search
            allowClear
            placeholder="搜索方案编号 / 电镀元素名称"
            style={{ width: SEARCH_WIDTH }}
            value={inputValue}
            onChange={(e) => onKeywordChange(e.target.value)}
            onSearch={onKeywordSearch}
          />
        </Space>
        <Space wrap>
          <Button icon={<ReloadOutlined />} onClick={() => { void fetchList(); }}>刷新</Button>
        </Space>
      </div>

      <Table<Record<string, unknown>>
        rowKey={(r, i) => `${String(r.scheme_no ?? '')}-${String(r.scheme_version ?? '')}-${String(r.item_seq ?? '')}-${i}`}
        size="small"
        loading={loading}
        columns={tableColumns}
        dataSource={items}
        locale={{ emptyText: EMPTY_TEXT }}
        scroll={{ x: 'max-content' }}
        pagination={{
          ...commonPagination,
          current: page,
          pageSize: size,
          total,
          onChange: (p, s) => { setPage(p); setSize(s); },
        }}
      />
    </div>
  );
};

export default PlatingSchemeTab;
