// ─────────────────────────────────────────────────────────────────────────────
// ProductCustomerPartTab —— 产品管理「客户产品」页签（task-260903 · F-2）
//   数据源：`GET /dataset/quote/customer-parts`（ds_quote_customer_part + LEFT JOIN customer）
//   服务端分页 + 服务端搜索；**纯只读，点行无任何反应**（AC-3）。
//
// 📋 本页属 `docs/列表操作规范.md §12` 例外白名单（纯查看，无批量动作）
//    ⇒ 用裸 <Table> 不用 SelectableTable；工具栏须**自套** TOOLBAR_ROW_STYLE
//      （裸 Table 没有 SelectableTable 的 toolbar 容器）。
//
// 🚫 工具栏只有「搜索 + 刷新」：无新增 / 编辑 / 删除 / 导入 —— 本页定位纯只读，
//    写入通道只有 task-260902 的导入，不产生第二条写入路径（需求文档 ② 明确不做）。
// ─────────────────────────────────────────────────────────────────────────────
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Table, Input, Button, Space, Empty, message } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { listCustomerParts } from './productHubApi';
import type { CustomerPartItem } from './productHubTypes';
import { renderTextCell } from './productHubCells';
import ZeroTotalFooter from './ZeroTotalFooter';
import {
  SEARCH_WIDTH,
  SEARCH_DEBOUNCE_MS,
  DEFAULT_PAGE_SIZE,
  commonPagination,
  TOOLBAR_ROW_STYLE,
} from '../master-data/listConventions';

const { Search } = Input;

const ProductCustomerPartTab: React.FC = () => {
  const [inputValue, setInputValue] = useState('');
  const [keyword, setKeyword] = useState('');
  const [items, setItems] = useState<CustomerPartItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1); // antd 的 current，**1-based**
  const [size, setSize] = useState(DEFAULT_PAGE_SIZE);
  const [loading, setLoading] = useState(false);

  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => { if (debounceTimer.current) clearTimeout(debounceTimer.current); }, []);

  const fetchList = useCallback(async () => {
    setLoading(true);
    try {
      const r = await listCustomerParts({
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
      setPage(1); // 搜索变化回第 1 页
    }, SEARCH_DEBOUNCE_MS);
  };

  const onKeywordSearch = (v: string) => {
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
    setInputValue(v);
    setKeyword(v);
    setPage(1);
  };

  // 列顺序即 AC-2，不得调整
  const columns: ColumnsType<CustomerPartItem> = [
    { title: '客户编号', dataIndex: 'customerNo', key: 'customerNo', width: 130, ellipsis: true, render: renderTextCell },
    // 客户名称由后端 LEFT JOIN customer 得出；JOIN 不到时回 null → 渲染 `—`
    // （现网 17 行中 3 行 JOIN 不到，是真实状态不是缺陷）
    { title: '客户名称', dataIndex: 'customerName', key: 'customerName', width: 200, ellipsis: true, render: renderTextCell },
    { title: '客户料号名称', dataIndex: 'customerPartName', key: 'customerPartName', width: 180, ellipsis: true, render: renderTextCell },
    { title: '客户产品编号', dataIndex: 'customerProductNo', key: 'customerProductNo', width: 190, ellipsis: true, render: renderTextCell },
    { title: '客户图号', dataIndex: 'customerDrawingNo', key: 'customerDrawingNo', width: 140, ellipsis: true, render: renderTextCell },
    { title: '销售料号', dataIndex: 'materialNo', key: 'materialNo', width: 160, ellipsis: true, render: renderTextCell },
  ];

  return (
    <div>
      {/* 工具栏：左＝搜索，右＝刷新。空态下**仍然渲染**，否则用户连「刷新重试」都点不到 */}
      <div style={TOOLBAR_ROW_STYLE}>
        <Space wrap>
          <Search
            allowClear
            placeholder="搜索客户编号 / 客户产品编号 / 销售料号"
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

      <Table<CustomerPartItem>
        rowKey={(r) => `${r.customerNo}|${r.customerProductNo}|${r.materialNo}`}
        size="small"
        loading={loading}
        columns={columns}
        dataSource={items}
        tableLayout="fixed"
        // 🚫 刻意不传 onRow —— 行不可点击、无 cursor:pointer、点任意单元格不弹抽屉（AC-3）
        locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" /> }}
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
    </div>
  );
};

export default ProductCustomerPartTab;
