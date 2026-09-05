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
// 🆕 子任务 `task-260903-产品维护能力增强`（F-2 / AC-6）：**「生产料号」一列改为可编辑单元格**。
//    🚫 其余 6 列（销售料号/品名/规格/尺寸/旧料号/单重）的 render **一个字都没动**，
//       抽屉**维持全只读**（AC-7 反向断言）—— 编辑能力只在列表这一格上，不得往下渗。
//    🔐 四个角色都渲染编辑能力（用户 2026-09-03 裁决）⇒ 本文件不读 authStore、无角色分支。
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
import EditableProductionNoCell from './EditableProductionNoCell';
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

  /**
   * 生产料号保存成功后**只改本地这一行**（F-2 / AC-10 ②）。
   *
   * 🚫 刻意不整表重取：重取会把当前页码 / 搜索词下的滚动位置刷掉，而且改一格重拉一页
   *    对 42 行没必要。刷新页面后仍是新值，靠的是后端已落库，不是这份本地状态。
   */
  const handleProductionNoSaved = useCallback((axisValue: string, next: string | null) => {
    setItems((prev) => prev.map(
      (it) => (it.axisValue === axisValue ? { ...it, productionNo: next } : it),
    ));
  }, []);

  // 列顺序即 AC-4，不得调整
  const columns: ColumnsType<PartListItem> = [
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
      //    代价：这一格单击不再开抽屉（同行其余 6 格照常开）。
      onCell: () => ({
        onClick: (e: React.MouseEvent) => { e.stopPropagation(); },
      }),
      render: (_: unknown, row: PartListItem) => (
        <EditableProductionNoCell
          axisValue={row.axisValue}
          value={row.productionNo}
          onSaved={(next) => handleProductionNoSaved(row.axisValue, next)}
        />
      ),
    },
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
