// ─────────────────────────────────────────────────────────────────────────────
// ProductSalesPartTab —— 产品管理「销售产品」页签（task-260903 · F-3；task-260907 · F-4/F-7 再改）
//   数据源：`GET /dataset/quote/parts`（ds_quote_material，轴 = 销售料号）
//   服务端分页 + 服务端搜索；**点行开抽屉**（Master-Detail 导航）。
//
// 📋 本页属 `docs/列表操作规范.md §12` 例外白名单（Master-Detail 导航，点行进抽屉）
//    ⇒ 用可点击行的裸 <Table>，工具栏自套 TOOLBAR_ROW_STYLE。
//
// 🚫 无导入按钮 —— 导入入口留在报价单管理的「导入报价数据」（需求文档 ② 明确不做）。
//
// 🆕 子任务 `task-260903-产品维护能力增强`（F-2 / AC-6）：**「生产料号」一列改为可编辑单元格**。
//    🚫 其余 6 列（销售料号/品名/规格/尺寸/旧料号/单重）的 render **一个字都没动**，
//       抽屉**维持全只读**（AC-7 反向断言）—— 编辑能力只在列表这一格上，不得往下渗。
//    🔐 四个角色都渲染编辑能力（用户 2026-09-03 裁决）⇒ 本文件不读 authStore、无角色分支。
//
// 🚧 过渡（2026-09-03 主线情报更正）：原计划复用的 `<SheetPartListTab>` 公共件不会存在了
//    （task-260902 改为零触碰 legacy + 新建 `pages/master-data/dataset/`，该目录尚未合入 master）。
//    **不得 import 也不得修改主数据维护核价侧旧公共件目录（已于 2026-09-07 迁至 `shared/`）
//    下任何文件**，故照当时核价侧列表页签的结构
//    新写一份平行实现；数据集相关部分已参数化为 `basePath`，日后收敛时改动面最小。
//
// 🔄 task-260907-产品管理客户过滤 · F-4 / F-7（本次改动）：
//    · 接壳页下发的 `customerNo` / `customerLabel` / `ready` / `available` 四个 props
//      （与 `ProductCustomerPartTab` 同一套口径，见该文件注释——`available=false` 时
//      是状态 C「候选加载失败」，与 F-7 状态 A「该客户确实没有销售产品数据」必须长得不一样）。
//    · **新增两列**「客户编号」「客户名称」，置最左（AC-5②，原型 `03-销售产品-当前客户.html`）。
//    · 🚨 `rowKey` 改为 `` `${row.customerNo}|${row.axisValue}` ``——仍用 `axisValue` 会让
//      切换客户时 React 复用行组件，显示上一个客户的单元格值（AC-16②）。
//    · 切客户回到第 1 页（同 F-1 第 8 条）；`handleProductionNoSaved` 按 (客户, 料号) 匹配。
//    · 抽屉的客户上下文改传壳页 `customerNo`/`customerLabel`（F-5，`D-10`）——不是
//      `activeRow.customerNo`，即使两者此刻恒相等，见 `ProductSalesPartDrawer.tsx` 顶部注释。
//    · `EditableProductionNoCell` 恒传**所在行**的 `customerNo`（F-6，行级维度）。
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
import EditableProductionNoCell from './EditableProductionNoCell';
import {
  SEARCH_WIDTH,
  SEARCH_DEBOUNCE_MS,
  DEFAULT_PAGE_SIZE,
  commonPagination,
  TOOLBAR_ROW_STYLE,
} from '../master-data/listConventions';

const { Search } = Input;

/** 状态 C 文案（原型 `05-空态与降级.html`），与 F-7 状态 A 的业务空态刻意不同措辞——
 *  与 `ProductCustomerPartTab` 用的是同一份口径，两个页签必须长得一样。 */
const CANDIDATES_FAILED_MESSAGE = '客户候选加载失败，无法展示数据';

export interface ProductSalesPartTabProps {
  /**
   * 壳页当前选中的客户号。🔄 客户必选（`D-7`）——只有 `available=true` 时它才是一个真实
   * 客户号；`available=false` 时为空串，本组件据此判断"没有可查的客户"而不是"发一个空参数请求"。
   */
  customerNo: string;
  /** 客户的展示文案，透传给抽屉标题/提示条（F-5，原型 `04`）。 */
  customerLabel: string;
  /** 壳页的客户上下文是否已就绪。就绪前不发起任何请求（AC-10②）。 */
  ready: boolean;
  /** 壳页候选是否可用。`false` 时本组件不发请求，直接展示状态 C 文案。 */
  available: boolean;
}

const ProductSalesPartTab: React.FC<ProductSalesPartTabProps> = ({
  customerNo, customerLabel, ready, available,
}) => {
  const [inputValue, setInputValue] = useState('');
  const [keyword, setKeyword] = useState('');
  const [items, setItems] = useState<PartListItem[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1); // antd 的 current，**1-based**
  const [size, setSize] = useState(DEFAULT_PAGE_SIZE);
  // 初值 true：避免「上下文未就绪」期间先短暂闪出一个空态表格（同 `ProductCustomerPartTab`）
  const [loading, setLoading] = useState(true);

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [activeRow, setActiveRow] = useState<PartListItem | null>(null);

  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => { if (debounceTimer.current) clearTimeout(debounceTimer.current); }, []);

  // 切客户时回到第 1 页（fronttask F-1 第 8 条）——否则会停在「上一个客户的第 3 页」，
  // 而新客户可能只有 1 页，表现为假空态。首次挂载也会命中一次，无害（page 本来就是 1）。
  useEffect(() => { setPage(1); }, [customerNo]);

  const fetchList = useCallback(async () => {
    if (!ready || !available) return; // AC-10②/状态 C：上下文未就绪或没有可查的客户时不发请求
    setLoading(true);
    try {
      const r = await quoteSheetApi.listParts({
        keyword: keyword || undefined,
        // 🔄 F-4：客户必选，恒传一个真实客户号（不再有 `|| undefined` 的"所有客户"转换）。
        customerNo,
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
  }, [keyword, customerNo, page, size, ready, available]);

  useEffect(() => { void fetchList(); }, [fetchList]);

  // 状态 C：候选不可用时 `fetchList` 直接短路、永远不会走到 `setLoading(false)`——
  // 这里补一次，避免表格停在永久 loading（AP-31 缺陷族的典型形态）。
  useEffect(() => {
    if (ready && !available) {
      setItems([]);
      setTotal(0);
      setLoading(false);
    }
  }, [ready, available]);

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

  /**
   * 生产料号保存成功后**只改本地这一行**（F-2 / AC-10 ②）。
   *
   * 🚫 刻意不整表重取：重取会把当前页码 / 搜索词下的滚动位置刷掉，而且改一格重拉一页
   *    对 42 行没必要。刷新页面后仍是新值，靠的是后端已落库，不是这份本地状态。
   *
   * 🔄 F-4：行语义已变（一行 = 客户 × 料号），匹配条件必须**同时**按 `customerNo` 与
   *    `axisValue` 判断——只按 `axisValue` 匹配在复合轴下会同时改中另一客户的同料号行
   *    （即使当前列表恒为单客户不会真的显示串号，语义上仍是错的）。
   */
  const handleProductionNoSaved = useCallback((rowCustomerNo: string, axisValue: string, next: string | null) => {
    setItems((prev) => prev.map(
      (it) => (it.customerNo === rowCustomerNo && it.axisValue === axisValue
        ? { ...it, productionNo: next }
        : it),
    ));
  }, []);

  // 列顺序即 AC-4/AC-5②，不得调整
  const columns: ColumnsType<PartListItem> = [
    // 🆕 F-4 / AC-5②：客户两列置最左——它们是行身份的一部分，不是附属信息
    //    （原型 `03-销售产品-当前客户.html`）。
    { title: '客户编号', dataIndex: 'customerNo', key: 'customerNo', width: 120, ellipsis: true, render: renderTextCell },
    // customerName 由后端同一条 SELECT 内 LEFT JOIN customer 带出；JOIN 不到（未建档客户）
    // 时为 null → 渲染 `—`，**不是空白**（AC-5④）。
    { title: '客户名称', dataIndex: 'customerName', key: 'customerName', width: 150, ellipsis: true, render: renderTextCell },
    { title: '销售料号', dataIndex: 'axisValue', key: 'axisValue', width: 170, ellipsis: true, render: renderTextCell },
    // 🆕 F-5 / AC-8：产品分类列。**位置第 2 位（销售料号之后）是用户 2026-09-03 裁决**，
    //    照报价 Excel 物料 sheet 的列序（销售料号 · 产品分类 · 品名 · 规格 · 尺寸 · 旧料号 ·
    //    单重 · 生产料号 · 类型），让页面与导入模板保持一致。
    // 🚩 **只显示 `categoryName`（如「默认分类」），不显示 `categoryCode`（`000000`）** —— 同一裁决。
    // ⚠️ 表头写「产品分类」**不带空格**：Excel 列名里那个 `产品 分类` 的空格是模板缺陷
    //    （task-260902 已加硬拦截并请用户删除），属导入侧列名匹配的事，UI 标题不照抄。
    // ⏸ 对方 B-16 未落库前该字段为 undefined ⇒ renderTextCell 兜底渲染 `—`，
    //    **不因缺字段而崩溃或整列不渲染**（与 productionNo 同一套兜底）。
    { title: '产品分类', dataIndex: 'categoryName', key: 'categoryName', width: 140, ellipsis: true, render: renderTextCell },
    { title: '品名', dataIndex: 'materialName', key: 'materialName', width: 230, ellipsis: true, render: renderTextCell },
    { title: '规格', dataIndex: 'specification', key: 'specification', width: 120, ellipsis: true, render: renderTextCell },
    { title: '尺寸', dataIndex: 'dimension', key: 'dimension', width: 140, ellipsis: true, render: renderTextCell },
    { title: '旧料号', dataIndex: 'oldMaterialNo', key: 'oldMaterialNo', width: 140, ellipsis: true, render: renderTextCell },
    // 单重是数值：后端**以字符串回传保留 scale**，走 Decimal 格式化，右对齐
    { title: '单重', dataIndex: 'unitWeight', key: 'unitWeight', width: 130, align: 'right', ellipsis: true, render: renderDecimalCell },
    // 🆕 F-2：唯一可编辑的一列。宽度 210 取自增量原型 `销售产品-可编辑生产料号.html` 的 `width:210px`
    //    （编辑态要放得下 Input）。
    // ⚠️ 这里**不能开 `ellipsis`**：ellipsis 会把 render 结果再包一层带 `title` 的省略号容器，
    //    编辑态的 Input 会被裁掉一截。省略号由单元格组件自己在**只读态**做。
    // ⚠️ productionNo 字段缺失（后端未补齐）时按空值渲染 `—`，**不崩溃、不整列消失**。
    {
      title: '生产料号',
      dataIndex: 'productionNo',
      key: 'productionNo',
      width: 210,
      // 🚨 这一列的 `td` 吞掉点击：`onRow.onClick` 会开抽屉，不吞的话「双击进编辑」
      //    会先被解释成两次开抽屉，编辑根本进不去。放在 `onCell` 上而不是只放在内层
      //    容器上，是为了连 `td` 的 padding 区域一起覆盖（点在边距上同样不该开抽屉）。
      //    代价：这一格单击不再开抽屉（同行其余列照常开）。
      onCell: () => ({
        onClick: (e: React.MouseEvent) => { e.stopPropagation(); },
      }),
      render: (_: unknown, row: PartListItem) => (
        <EditableProductionNoCell
          axisValue={row.axisValue}
          // 🔄 F-6：恒传所在行的 customerNo（行级维度，与抽屉取壳页值不同——见文件头注释）
          customerNo={row.customerNo}
          value={row.productionNo}
          onSaved={(next) => handleProductionNoSaved(row.customerNo, row.axisValue, next)}
        />
      ),
    },
  ];

  // 三种空态必须可区分（原型 05）：
  //   状态 C（候选加载失败）> F-7 状态 A（该客户确实没数据）> 通用空态（搜索命中 0 行等）。
  let emptyNode: React.ReactNode;
  if (!available) {
    emptyNode = <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={CANDIDATES_FAILED_MESSAGE} />;
  } else if (!keyword) {
    // F-7 / AC-13：选中具体客户且确实 0 行时，空态文案带上客户身份；
    // 搜索关键字导致的 0 行仍用通用空态，不冒充「该客户没有数据」。
    emptyNode = (
      <Empty
        image={Empty.PRESENTED_IMAGE_SIMPLE}
        description={(
          <span>
            该客户暂无销售产品数据
            <br />
            <span style={{ fontSize: 12, color: 'rgba(0, 0, 0, 0.45)' }}>
              客户「{customerLabel}」下还没有导入过报价数据。切换客户或先导入数据。
            </span>
          </span>
        )}
      />
    );
  } else {
    emptyNode = <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无数据" />;
  }

  return (
    <div>
      {/* 工具栏：左＝搜索，右＝刷新。空态下仍然渲染，否则用户连「刷新重试」都点不到 */}
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
        // 🚨 F-4 / AC-16②：rowKey 必须含 customerNo——单客户下虽不会同屏出现两行，
        //    但切换客户时 React 会复用行组件，key 不含客户号会导致切换后仍显示上一个
        //    客户的单元格值。这是本任务最容易漏、且症状最像「玄学 bug」的一处。
        rowKey={(r) => `${r.customerNo}|${r.axisValue}`}
        size="small"
        loading={loading}
        columns={columns}
        dataSource={items}
        tableLayout="fixed"
        // 🆕 F-4 / AC-15：新增客户两列后各列宽度之和已明显超出常见视口宽度——
        //    表格必须自己横向滚动，🚫 不能让溢出撑开页面 body（同目录 `ReadonlySheetTable.tsx`
        //    已是这个用法，`master-data` 下多处宽表也是这套约定）。
        scroll={{ x: 'max-content' }}
        locale={{ emptyText: emptyNode }}
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
        // 🔄 F-5 / D-10：抽屉的客户上下文 = 壳页所选客户，**不是** `activeRow.customerNo`。
        //    客户必选后两者恒相等，但契约以壳页为准——见 `ProductSalesPartDrawer.tsx` 顶部注释。
        customerNo={customerNo}
        customerLabel={customerLabel}
        onClose={() => setDrawerOpen(false)}
      />
    </div>
  );
};

export default ProductSalesPartTab;
