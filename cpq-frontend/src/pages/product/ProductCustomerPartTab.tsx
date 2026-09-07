// ─────────────────────────────────────────────────────────────────────────────
// ProductCustomerPartTab —— 产品管理「客户产品」页签（task-260903 · F-2）
//   数据源：`GET /dataset/quote/customer-parts`（ds_quote_customer_part + LEFT JOIN customer）
//   服务端分页 + 服务端搜索；**纯只读，点行无任何反应**（AC-3）。
//
// 📋 本页属 `docs/列表操作规范.md §12` 例外白名单（纯查看，无批量动作）
//    ⇒ 用裸 <Table> 不用 SelectableTable；工具栏须**自套** TOOLBAR_ROW_STYLE
//      （裸 Table 没有 SelectableTable 的 toolbar 容器）。
//
// 🚫 工具栏只有「搜索 + 刷新」：无新增 / 编辑 / 删除 / 导入 —— 本页**仍是纯只读**，
//    写入通道只有 task-260902 的导入，不产生第二条写入路径（需求文档 ② 明确不做）。
//
// 🔄 task-260907-产品管理客户过滤 · F-3（本次改动）：
//    客户过滤下拉**已摘除**，改由壳页 `ProductHubPage` 统一加载候选 + 持有客户上下文，
//    本组件只接收 `customerNo` / `customerLabel` / `ready` 三个 props。
//    ⚠️ 改动面刻意最小：`listCustomerParts` 的调用参数、列定义、渲染逻辑、`rowKey`
//       一行都没动；三条成文纪律继续有效——照单全收后端 items（不按 customerName 是否
//       为空再筛）、过滤必须在后端做、`page` 是 0-based（传 `current - 1`）。
//    🆕 F-7（AC-13）：选中一个没有任何客户产品数据的客户时，空态文案改成
//       「该客户暂无客户产品数据」，而不是通用的「暂无数据」。
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

/** 「所有客户」哨兵值，与壳页 `ProductHubPage` 的 `ALL_CUSTOMERS` 同一口径（空串）。 */
const ALL_CUSTOMERS = '';

export interface ProductCustomerPartTabProps {
  /** 壳页当前选中的客户号；空串 = 所有客户。取代本组件原有的内部 `useState`（F-3）。 */
  customerNo: string;
  /**
   * 客户的展示文案（如 `苏州西门子（CUST-0031）` 或未建档时的 `Q13CUST0617`），
   * 由壳页根据候选列表算好传入 —— 本组件不持有候选，无法自己拼。仅用于 F-7 空态文案。
   */
  customerLabel: string;
  /**
   * 壳页的客户上下文是否已就绪（localStorage 读取 + 候选校验完成）。
   * 🚨 就绪前不发起任何请求——避免「先出全量再过滤」的一闪而过（AC-10②）。
   */
  ready: boolean;
}

const ProductCustomerPartTab: React.FC<ProductCustomerPartTabProps> = ({
  customerNo, customerLabel, ready,
}) => {
  const [inputValue, setInputValue] = useState('');
  const [keyword, setKeyword] = useState('');
  const [items, setItems] = useState<CustomerPartItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1); // antd 的 current，**1-based**
  const [size, setSize] = useState(DEFAULT_PAGE_SIZE);
  // 初值 true：避免「上下文未就绪」期间先短暂闪出一个空态表格（见下方 fetchList 的 ready 门槛）
  const [loading, setLoading] = useState(true);

  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => { if (debounceTimer.current) clearTimeout(debounceTimer.current); }, []);

  // 切客户时回到第 1 页（fronttask F-1 第 8 条）——否则会停在「上一个客户的第 3 页」，
  // 而新客户可能只有 1 页，表现为假空态。首次挂载也会命中一次，无害（page 本来就是 1）。
  useEffect(() => { setPage(1); }, [customerNo]);

  const fetchList = useCallback(async () => {
    if (!ready) return; // AC-10②：客户上下文未就绪前不发请求
    setLoading(true);
    try {
      const r = await listCustomerParts({
        keyword: keyword || undefined,
        // 🚨 过滤**在后端做**（AC-3 原型注解）：前端捞全量自己 filter 会让 total 与翻页错乱。
        //    空串（所有客户）转 undefined ⇒ axios 不序列化该参数 ＝ 省略 ＝ 不过滤。
        customerNo: customerNo || undefined,
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
  }, [keyword, customerNo, page, size, ready]);

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

  // F-7 / AC-13：选中具体客户且确实 0 行时，空态文案带上客户身份；
  // 「所有客户」态或搜索关键字导致的 0 行仍用通用空态，不冒充「该客户没有数据」。
  const emptyNode = (customerNo !== ALL_CUSTOMERS && !keyword)
    ? (
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={(
          <span>
            该客户暂无客户产品数据
            <br />
            <span style={{ fontSize: 12, color: 'rgba(0, 0, 0, 0.45)' }}>
              客户「{customerLabel}」下还没有导入过报价数据。切换客户或先导入数据。
            </span>
          </span>
        )}
      />
    )
    : <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />;

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
        locale={{ emptyText: emptyNode }}
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
