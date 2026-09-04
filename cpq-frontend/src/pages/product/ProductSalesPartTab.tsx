// ─────────────────────────────────────────────────────────────────────────────
// ProductSalesPartTab —— 产品管理「销售产品」页签（task-260903 · F-3）
//   数据源：`GET /dataset/quote/parts`（ds_quote_material，轴 = 销售料号）
//   服务端分页 + 服务端搜索；**点行开抽屉**（Master-Detail 导航）。
//
// 📋 本页属 `docs/列表操作规范.md §12` 例外白名单（Master-Detail 导航，点行进抽屉）
//    ⇒ 用可点击行的裸 <Table>，工具栏自套 TOOLBAR_ROW_STYLE。
//
// 🚫 无导入按钮 —— 导入入口留在报价单管理的「导入报价数据」（需求文档 ② 明确不做）。
//
// 🚧 过渡（2026-09-03 主线情报更正）：原计划复用的 `<SheetPartListTab>` 公共件不会存在了
//    （task-260902 改为零触碰 legacy + 新建 `pages/master-data/dataset/`，该目录尚未合入 master）。
//    **不得 import 也不得修改 `part-costing/` 下任何文件**，故照 `PartCostingTab.tsx` 的结构
//    新写一份平行实现；数据集相关部分已参数化为 `basePath`，日后收敛时改动面最小。
// ─────────────────────────────────────────────────────────────────────────────
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Table, Input, Button, Space, Empty, message } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { quoteSheetApi } from './productHubApi';
import type { PartListItem } from './productHubTypes';
import { renderTextCell, renderDecimalCell } from './productHubCells';
import ZeroTotalFooter from './ZeroTotalFooter';
import ProductSalesPartDrawer from './ProductSalesPartDrawer';
import {
  SEARCH_WIDTH,
  SEARCH_DEBOUNCE_MS,
  DEFAULT_PAGE_SIZE,
  commonPagination,
  TOOLBAR_ROW_STYLE,
} from '../master-data/listConventions';

const { Search } = Input;

const ProductSalesPartTab: React.FC = () => {
  const [inputValue, setInputValue] = useState('');
  const [keyword, setKeyword] = useState('');
  const [items, setItems] = useState<PartListItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1); // antd 的 current，**1-based**
  const [size, setSize] = useState(DEFAULT_PAGE_SIZE);
  const [loading, setLoading] = useState(false);

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [activeRow, setActiveRow] = useState<PartListItem | null>(null);

  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => { if (debounceTimer.current) clearTimeout(debounceTimer.current); }, []);

  const fetchList = useCallback(async () => {
    setLoading(true);
    try {
      const r = await quoteSheetApi.listParts({
        keyword: keyword || undefined,
        // 🚨 契约 page 是 **0-based**，antd current 是 1-based ⇒ 必须减 1，
        //    否则首页取到第二页（api.md 消费方硬约束 1）。
        page: page - 1,
        size,
      });
      setItems(r.items ?? []);
      setTotal(r.total ?? 0);
    } catch (e) {
      message.error((e as Error)?.message ?? '查询失败');
      // 失败时清空并显示空态，**不停在 loading**（AC-13：不许白屏、不许无限转圈）
      setItems([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [keyword, page, size]);

  useEffect(() => { void fetchList(); }, [fetchList]);

  const onKeywordChange = (v: string) => {
    setInputValue(v);
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
    debounceTimer.current = setTimeout(() => {
      setKeyword(v);
      setPage(1);
    }, SEARCH_DEBOUNCE_MS);
  };

  const onKeywordSearch = (v: string) => {
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
    setInputValue(v);
    setKeyword(v);
    setPage(1);
  };

  const openDrawer = (row: PartListItem) => {
    setActiveRow(row);
    setDrawerOpen(true);
  };

  // 列顺序即 AC-4，不得调整
  const columns: ColumnsType<PartListItem> = [
    { title: '销售料号', dataIndex: 'axisValue', key: 'axisValue', width: 170, ellipsis: true, render: renderTextCell },
    { title: '品名', dataIndex: 'materialName', key: 'materialName', width: 230, ellipsis: true, render: renderTextCell },
    { title: '规格', dataIndex: 'specification', key: 'specification', width: 120, ellipsis: true, render: renderTextCell },
    { title: '尺寸', dataIndex: 'dimension', key: 'dimension', width: 140, ellipsis: true, render: renderTextCell },
    { title: '旧料号', dataIndex: 'oldMaterialNo', key: 'oldMaterialNo', width: 140, ellipsis: true, render: renderTextCell },
    // 单重是数值：后端**以字符串回传保留 scale**，走 Decimal 格式化，右对齐
    { title: '单重', dataIndex: 'unitWeight', key: 'unitWeight', width: 130, align: 'right', ellipsis: true, render: renderDecimalCell },
    // ⚠️ productionNo 依赖后端补齐（api.md §2 缺口2）。字段缺失时 renderTextCell 兜底渲染 `—`，
    //    **不因缺字段而崩溃或整列不渲染**。
    { title: '生产料号', dataIndex: 'productionNo', key: 'productionNo', width: 150, ellipsis: true, render: renderTextCell },
  ];

  return (
    <div>
      {/* 工具栏：左＝搜索，右＝刷新。空态下仍然渲染 */}
      <div style={TOOLBAR_ROW_STYLE}>
        <Space wrap>
          <Search
            allowClear
            placeholder="搜索销售料号 / 品名"
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

      <Table<PartListItem>
        rowKey="axisValue"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={items}
        tableLayout="fixed"
        locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" /> }}
        onRow={(row) => ({
          onClick: () => openDrawer(row),
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

      {/* AC-13：antd 6 在 total=0 时整个不渲染分页器，此处补「共 0 条」（见 ZeroTotalFooter 注释） */}
      <ZeroTotalFooter total={total} loading={loading} />

      <ProductSalesPartDrawer
        open={drawerOpen}
        axisValue={activeRow?.axisValue ?? null}
        fallbackMaterialName={activeRow?.materialName ?? null}
        onClose={() => setDrawerOpen(false)}
      />
    </div>
  );
};

export default ProductSalesPartTab;
