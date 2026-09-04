// ─────────────────────────────────────────────────────────────────────────────
// ProductCustomerPartTab —— 产品管理「客户产品」页签（task-260903 · F-2）
//   数据源：`GET /dataset/quote/customer-parts`（ds_quote_customer_part + LEFT JOIN customer）
//   服务端分页 + 服务端搜索；**纯只读，点行无任何反应**（AC-3）。
//
// 📋 本页属 `docs/列表操作规范.md §12` 例外白名单（纯查看，无批量动作）
//    ⇒ 用裸 <Table> 不用 SelectableTable；工具栏须**自套** TOOLBAR_ROW_STYLE
//      （裸 Table 没有 SelectableTable 的 toolbar 容器）。
//
// 🚫 工具栏只有「客户过滤 + 搜索 + 刷新」：无新增 / 编辑 / 删除 / 导入 —— 本页**仍是纯只读**，
//    写入通道只有 task-260902 的导入，不产生第二条写入路径（需求文档 ② 明确不做）。
//
// 🆕 子任务 `task-260903-产品维护能力增强`（F-1 / AC-1~AC-5、AC-14）：工具栏加**客户过滤下拉**。
//    子任务只给本页加了「过滤」这一件事，**没有**给本页加任何编辑能力（用户裁决：客户产品页签
//    是纯列表只读）。
// ─────────────────────────────────────────────────────────────────────────────
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Table, Input, Button, Space, Empty, Select, message } from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { listCustomerParts, listCustomerPartCustomers } from './productHubApi';
import type { CustomerPartItem, CustomerOption } from './productHubTypes';
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

/**
 * 「所有客户」的哨兵值（AC-1 默认项 / AC-4 切回还原）。
 *
 * 用空串而不是 `undefined`：antd 的 `Select` 拿到 `undefined` 会显示 placeholder 而不是选项文案，
 * 而 AC-1 断言默认值**文案就是「所有客户」**，必须是一个真正被选中的选项。
 * 发请求前再转回 `undefined`（省略该 query ＝ 所有客户）。
 */
const ALL_CUSTOMERS = '';

/** 过滤下拉宽度，取自原型 `客户产品-过滤器.html` 的 `width:200px` */
const CUSTOMER_FILTER_WIDTH = 200;
/** 展开面板宽度，取自原型 `.dd{width:260px}` —— 比选择框宽，长客户编号 + 后缀 + 数量才放得下
 *  （实测 200px 时 `Q13CUST0617（未建档）` 会把右侧数量挤出可视区）。 */
const CUSTOMER_FILTER_POPUP_WIDTH = 260;

/** 未在 `customer` 表建档的客户，下拉后缀文案与配色（原型 `.warn{color:#d46b08}`） */
const UNREGISTERED_SUFFIX = '（未建档）';
const WARN_STYLE: React.CSSProperties = { color: '#d46b08' };
/** 数量提示（原型 `.cnt`） */
const COUNT_STYLE: React.CSSProperties = { color: 'rgba(0, 0, 0, 0.45)', fontSize: 12 };
/** 下拉项内的编号与名称之间用全角空格分隔（原型里显示为 `CUST-0004` + 全角空格 + `正泰`）。
 *  写成转义：eslint `no-irregular-whitespace` 禁止源码里出现裸全角空格。 */
const NBSP_WIDE = '\u3000';

const ProductCustomerPartTab: React.FC = () => {
  const [inputValue, setInputValue] = useState('');
  const [keyword, setKeyword] = useState('');
  // AC-1：默认「所有客户」
  const [customerNo, setCustomerNo] = useState<string>(ALL_CUSTOMERS);
  const [customerOptions, setCustomerOptions] = useState<CustomerOption[]>([]);
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
  }, [keyword, customerNo, page, size]);

  useEffect(() => { void fetchList(); }, [fetchList]);

  /**
   * 过滤器候选（AC-5）。挂载时取一次即可 —— 候选来自数据分布，不随分页/搜索变化。
   *
   * 🚨 **照单全收后端返回的 items**：🚫 不得在此按 `customerName` 是否为空再筛一遍。
   *    `Q13CUST0617` / `C1` 正是没有 `customerName` 的那两个，筛掉就等于把 AC-5 亲手做废。
   * ⚠️ 端点未就绪（404）时降级为「只有『所有客户』一项」，列表照常可用 ——
   *    🚫 不做 mock 兜底，那会把「后端没就绪」伪装成「这个客户真的没有产品」。
   */
  const loadCustomerOptions = useCallback(async () => {
    try {
      const r = await listCustomerPartCustomers();
      setCustomerOptions(r.items ?? []);
    } catch {
      setCustomerOptions([]);
    }
  }, []);

  useEffect(() => { void loadCustomerOptions(); }, [loadCustomerOptions]);

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

  /**
   * 下拉候选（AC-1 / AC-5）。第一项恒为「所有客户」，其后按后端返回的顺序原样排列。
   *
   * `count` 是可选的（api.md §2 B-2 注明实现可省）：**全部候选都带 count 时**才给「所有客户」
   * 算合计并显示数量；只要有一个缺就整体不显示 —— 显示一个算不准的合计比不显示更糟。
   */
  const filterOptions = useMemo(() => {
    const opts = customerOptions.map((c) => {
      const name = c.customerName ?? null;
      const registered = name !== null && name !== '';
      return {
        value: c.customerNo,
        // 选中框里的文案（纯文本）；下拉项里的富文本由 optionRender 单独画
        label: registered
          ? `${c.customerNo}${NBSP_WIDE}${name}`
          : `${c.customerNo}${UNREGISTERED_SUFFIX}`,
        customerNo: c.customerNo,
        customerName: name,
        registered,
        count: typeof c.count === 'number' ? c.count : null,
      };
    });
    const allHaveCount = opts.length > 0 && opts.every((o) => o.count !== null);
    const allCount = allHaveCount
      ? opts.reduce((sum, o) => sum + (o.count ?? 0), 0)
      : null;
    return [
      {
        value: ALL_CUSTOMERS,
        label: '所有客户',
        customerNo: ALL_CUSTOMERS,
        customerName: null as string | null,
        registered: true,
        count: allCount,
      },
      ...opts,
    ];
  }, [customerOptions]);

  /**
   * 切客户（AC-2 / AC-4）。
   * 🚫 **只重置页码，绝不清空搜索框** —— AC-3 断言「过滤 + 搜索」叠加后清空搜索会回到 11 行
   *    而不是 17 行，清了搜索这条就永远验不出来。
   */
  const onCustomerChange = (v: string) => {
    setCustomerNo(v);
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
          {/* AC-1：客户过滤在**搜索框左侧**，默认文案「所有客户」（原型「客户产品-过滤器」）。
              🚨 AC-14：命中 0 行时**过滤器仍可交互** —— 刻意不加 disabled，
                 否则用户筛出空结果后连「切回所有客户」都点不了，直接卡死。 */}
          <Select<string>
            style={{ width: CUSTOMER_FILTER_WIDTH }}
            popupMatchSelectWidth={CUSTOMER_FILTER_POPUP_WIDTH}
            value={customerNo}
            onChange={onCustomerChange}
            options={filterOptions}
            optionRender={(option) => {
              const d = option.data as (typeof filterOptions)[number];
              return (
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 8 }}>
                  <span>
                    {d.customerNo === ALL_CUSTOMERS ? '所有客户' : d.customerNo}
                    {d.registered && d.customerName ? `${NBSP_WIDE}${d.customerName}` : null}
                    {/* 未建档：橙色后缀，与列表里客户名称列渲染 `—` 的口径呼应 */}
                    {d.customerNo !== ALL_CUSTOMERS && !d.registered
                      ? <span style={WARN_STYLE}>{UNREGISTERED_SUFFIX}</span>
                      : null}
                  </span>
                  {d.count !== null ? <span style={COUNT_STYLE}>{d.count}</span> : null}
                </div>
              );
            }}
          />
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
