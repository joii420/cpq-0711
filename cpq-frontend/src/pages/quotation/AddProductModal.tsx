/**
 * AddProductModal — 报价单 Step2「添加产品 ▾ → 从已有产品添加」抽屉。
 *
 * 🎨 视觉基准（task-260909 定稿，取代 task-0712 那份历史快照）：
 *    dev-docs/task-260909-已有产品抽屉数据源收敛/原型图/已有产品抽屉-默认态.html
 *                                              /已有产品抽屉-空态与边界.html
 * 抽屉(960，宽度不变) → 顶部 4 过滤(客户产品编号/销售料号/品名/规格 + 查询/重置)
 *   → **占满全宽**的 7 列列表(多选+全选)：来源 / 客户产品编号 / 客户图号 / 客户物料名 /
 *     销售料号 / 品名 / 规格
 *   → 底部"已选 N 项" + 取消/加入报价单。
 *
 * 🚫 **不再有右侧 3D 预览区**（task-260909 · AC-7，用户 2026-09-09 裁决「先暂时移除，只显示列表」）：
 *    预览面板 / 缩略图 /「⤢ 交互查看」/ 随选中行实时拉 `/model-configs/current` 的 effect 整块删除。
 *    ⚠️ `activeRow` **保留** —— 它今天只承担「行选中高亮」，不再驱动任何取数。
 *    3D 模型管理功能本身（配置中心 → 3D 模型配置）不在本次范围，端点与服务层原样保留。
 *
 * 语义（D8，取代旧"三步向导：选产品→选工序→选模板"）：直接加成品销售料号（可多选批量），
 * 套报价单已绑客户报价模板渲染，不再走材质/工序配置。
 * 数据源（task-260909 收敛后）：**单表 `ds_quote_customer_part`**，按本报价单客户过滤
 * （服务端从 quotation 派生 customer_no，前端不传客户，见 api.md §1.1）。
 *
 * 落库：不新建端点，复用 BulkImportPartsDrawer.buildLineItemFromTemplate 把 ExistingProductDTO
 * 映射成 LineItem，父组件(QuotationWizard)负责去重追加 + 既有 saveDraft 落库。
 */
import React, { useEffect, useState } from 'react';
import { Drawer, Table, Input, Button, Empty, Tooltip, message, Tag } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { LineItem } from './QuotationStep2';
import { buildLineItemFromTemplate } from './BulkImportPartsDrawer';
import { quotationService } from '../../services/quotationService';
import { templateService } from '../../services/templateService';
import type { ExistingProductDTO, ExistingProductQueryParams } from '../../types/existingProduct';

export interface AddProductModalProps {
  open: boolean;
  /** 查候选用（api.md §1.1 服务端从 quotation 派生客户）。Step2 打开此抽屉时报价单已创建，恒非空。 */
  quotationId: string | undefined;
  /** 已绑定的客户报价模板 id；用于「加入报价单」时展开 LineItem。 */
  customerTemplateId: string | undefined;
  /**
   * 打开时预置的过滤条（task-260902 · F-1 / AC-2 新增，**唯一的改动点**）。
   *
   * 用途：选配抽屉在步骤 1 发现客户产品编号已被占用时，提供「→ 打开『从产品库添加』并定位到
   * 该产品」的出口 —— 由宿主关掉选配抽屉、带着该编号打开本弹层。
   * 🚫 本次**不改本页其他任何东西**（列表、表单、校验一概不动，见 fronttask「明确不做」）。
   * 不传时行为与改动前**逐字节一致**（回落到全空过滤条）。
   */
  initialFilters?: ExistingProductQueryParams;
  onCancel: () => void;
  onConfirm: (lineItems: LineItem[]) => void;
}

const EMPTY_FILTERS: ExistingProductQueryParams = {
  customerProductNo: '',
  salesPartNo: '',
  productName: '',
  spec: '',
};

const PAGE_SIZE = 20;

const AddProductModal: React.FC<AddProductModalProps> = ({
  open,
  quotationId,
  customerTemplateId,
  initialFilters,
  onCancel,
  onConfirm,
}) => {
  const [filters, setFilters] = useState<ExistingProductQueryParams>(EMPTY_FILTERS);
  const [appliedFilters, setAppliedFilters] = useState<ExistingProductQueryParams>({});
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);

  const [list, setList] = useState<ExistingProductDTO[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedRowKeys, setSelectedRowKeys] = useState<string[]>([]);
  /**
   * 当前高亮行。task-260909 起**只用于行选中高亮**（`onRow` 背景色），
   * 🚫 不再驱动任何取数 —— 原来挂在它身上的 3D 预览 effect 已随 AC-7 整块删除。
   */
  const [activeRow, setActiveRow] = useState<ExistingProductDTO | null>(null);

  const [confirming, setConfirming] = useState(false);

  // 每次打开重置为初始态（过滤条 + 选中态 + 高亮行全部重置）。
  useEffect(() => {
    if (!open) return;
    // task-260902：有预置过滤条时用它开局（否则与改动前一致，回落全空）
    const seed = initialFilters ? { ...EMPTY_FILTERS, ...initialFilters } : EMPTY_FILTERS;
    setFilters(seed);
    setAppliedFilters(initialFilters ? { ...initialFilters } : {});
    setPage(0);
    setSelectedRowKeys([]);
    setActiveRow(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  // 拉列表：打开 / 过滤条件变化 / 翻页 时查询。
  useEffect(() => {
    if (!open || !quotationId) return;
    let cancelled = false;
    setLoading(true);
    quotationService
      .listExistingProducts(quotationId, { ...appliedFilters, page, size: PAGE_SIZE })
      .then((res) => {
        if (cancelled) return;
        const content = res.content || [];
        setList(content);
        setTotal(res.totalElements || 0);
        // 若当前高亮行不在新结果集中（切换过滤/翻页），回退到结果首行。
        setActiveRow((prev) => {
          if (prev && content.some((p) => p.materialNo === prev.materialNo)) return prev;
          return content[0] ?? null;
        });
      })
      .catch((e: any) => {
        if (cancelled) return;
        message.error(e?.message || '加载已有产品列表失败');
        setList([]);
        setTotal(0);
        setActiveRow(null);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [open, quotationId, appliedFilters, page]);

  /**
   * 当前是否带着过滤条件在查（只看 4 个过滤字段，🚫 不看 page/size —— 那些恒有值，
   * 一起算进来会让空态永远走"过滤没命中"分支，AC-12 的「暂无数据」就再也出不来）。
   */
  const hasActiveFilter = (['customerProductNo', 'salesPartNo', 'productName', 'spec'] as const).some(
    (k) => !!appliedFilters[k] && String(appliedFilters[k]).trim() !== '',
  );

  const handleQuery = () => {
    setPage(0);
    setAppliedFilters({ ...filters });
  };

  const handleReset = () => {
    setFilters(EMPTY_FILTERS);
    setPage(0);
    setAppliedFilters({});
  };

  const handleFilterKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter') handleQuery();
  };

  const handleConfirm = async () => {
    if (selectedRowKeys.length === 0) return;
    if (!customerTemplateId) {
      message.error('当前报价单未绑定客户报价模板，无法加入产品');
      return;
    }
    setConfirming(true);
    try {
      const tplRes = await templateService.getById(customerTemplateId);
      const tmpl = tplRes.data;
      const selected = list.filter((p) => selectedRowKeys.includes(p.materialNo));
      const lineItems = selected.map((p) =>
        buildLineItemFromTemplate(tmpl, {
          partNo: p.materialNo,
          partName: p.productName || p.customerMaterialName || p.materialNo,
          customerProductNo: p.customerProductNo || undefined,
          customerPartName: p.customerMaterialName || undefined,
          customerSpecific: false,
        }),
      );
      onConfirm(lineItems);
      message.success(`已加入 ${lineItems.length} 个产品`);
    } catch (e: any) {
      message.error(e?.message || '加入报价单失败');
    } finally {
      setConfirming(false);
    }
  };

  const columns: ColumnsType<ExistingProductDTO> = [
    {
      title: '来源',
      dataIndex: 'source',
      key: 'source',
      width: 76,
      render: (v: string | null, row: ExistingProductDTO) => v === 'CONFIGURED'
        ? <Tag color="purple">选配{row.configProductType === 'COMPOSITE' ? '·组合' : row.configProductType === 'SIMPLE' ? '·单件' : ''}</Tag>
        : <Tag>已有</Tag>,
    },
    {
      title: '客户产品编号',
      dataIndex: 'customerProductNo',
      key: 'customerProductNo',
      width: 168,
      /*
       * task-260902 · AC-12b⑤-b：一个销售料号可以对应**多个**客户产品编号
       * （方案甲下 `sel_product_no.quote_part_no` 刻意不唯一），后端列表用
       * `DISTINCT ON (material_no)` 取 `created_at` 最早的作代表 ⇒ 后配的那个人
       * 在列表里看到的是别人的编号，**认不出这是自己的产品**。
       *
       * 三条实现纪律：
       *  1. 🚫 `customerProductNos` **缺失 / 为空一律回退到 `customerProductNo`**
       *     （后端未上线、或 mcm 来源的老数据都会走这条）。绝不渲染 undefined 或空单元格。
       *  2. 单编号（绝大多数行）**视觉零变化** —— 与改动前逐字节一致。
       *  3. 多编号时「等 N 个」这个标记**必须活过列宽截断**：编号本身可以省略号，
       *     标记不能跟着一起被截掉 —— 否则用户只看到一个被截断的号，反而不知道还有别的。
       *     所以编号走 `flex:1; min-width:0` 省略，标记走 `flex:none` 的 Tag。
       */
      render: (v: string | null, row: ExistingProductDTO) => {
        const all = (row.customerProductNos ?? []).filter((x) => !!x && String(x).trim() !== '');
        // 回退链：全量列表 → 代表编号 → '—'
        const primary = all[0] ?? v ?? '';
        if (!primary) return '—';
        if (all.length <= 1) {
          // 单编号：原样一行文本，与改动前一致
          return <Tooltip title={primary}><span>{primary}</span></Tooltip>;
        }
        return (
          <Tooltip title={<div>{all.map((n) => <div key={n}>{n}</div>)}</div>}>
            <span style={{ display: 'flex', alignItems: 'center', gap: 6, minWidth: 0 }}>
              <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {primary}
              </span>
              <Tag style={{ flex: 'none', marginInlineEnd: 0 }}>等 {all.length} 个</Tag>
            </span>
          </Tooltip>
        );
      },
    },
    {
      // 🆕 task-260909 · AC-4：客户图号。实测正泰第一页 20 行里有 11 行图号为空
      // （`0028-*` 系列按料号升序恰好排最前）⇒ **整屏都是 `—` 是正常现象，不是 bug**。
      // 🚫 空值必须落 `—`，不许渲染成空白 / undefined / null（AP-31 族：宁可占位也不要空白）。
      title: '客户图号',
      dataIndex: 'customerDrawingNo',
      key: 'customerDrawingNo',
      /*
       * ⚠️ 列宽为何与原型标的不一致（唯一一处刻意偏差，AC 优先于原型）：
       *   原型两份 HTML 标的是 96/200/130/160/150/130，但那是在**自身 1180px 画布**上标的；
       *   本抽屉按需求文档 §「抽屉宽度调整 ❌」保持 **960**，可用宽度只有约 912-40=872px。
       *   照搬 866px 固定列 ⇒ 只剩 6px 给「规格」，实测表头被压成竖排「规 格」、
       *   销售料号/品名换行。⇒ 按同一比例收敛为 76/168/112/132/136/112（合计 736），
       *   给「规格」留约 136px。列**顺序与构成完全不变**（AC-3 断言的是那个，不是像素）。
       */
      width: 112,
      render: (v: string | null) => v || '—',
    },
    {
      // task-260909 · AC-5：**客户侧**名称（后端取 ds_quote_customer_part.customer_part_name）。
      // 与「品名」是两个不同的列、两个不同的值，🚫 不许再共用一个字段。
      title: '客户物料名',
      dataIndex: 'customerMaterialName',
      key: 'customerMaterialName',
      width: 132,
      render: (v: string | null) => v || '—',
    },
    { title: '销售料号', dataIndex: 'materialNo', key: 'materialNo', width: 136 },
    {
      // task-260909 · AC-5：**主数据侧**品名（后端取 v_compat_material_master.material_name）。
      //
      // AC-5b 兜底：品名为空时显示**销售料号**（如 `0028-2609000001`），
      //   🚫 不显示 `—`，更 🚫 不回退到 `customerMaterialName` —— 回退到客户名会让两列
      //   又变回相同的值，等于 AC-5 白修。
      // ⚠️ 后端（api.md §1.3）已做同样的兜底；这里再兜一层是**渲染层护栏**：
      //   AC-5b 断言的是「用户看到什么」，不能只依赖上游某一层不为空。
      title: '品名',
      dataIndex: 'productName',
      key: 'productName',
      width: 112,
      render: (v: string | null, row: ExistingProductDTO) => v || row.materialNo || '—',
    },
    { title: '规格', dataIndex: 'spec', key: 'spec', render: (v: string | null) => v || '—' },
  ];

  return (
    <Drawer
      title="添加产品 — 从已有产品"
      placement="right"
      width={960}
      open={open}
      onClose={onCancel}
      destroyOnClose
      footer={
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <div style={{ marginRight: 'auto', color: '#606266' }}>已选 {selectedRowKeys.length} 项</div>
          <Button onClick={onCancel} disabled={confirming}>
            取消
          </Button>
          {selectedRowKeys.length === 0 ? (
            <Tooltip title="请至少选择一项产品">
              <Button type="primary" disabled>
                加入报价单
              </Button>
            </Tooltip>
          ) : (
            <Button type="primary" loading={confirming} onClick={handleConfirm}>
              加入报价单
            </Button>
          )}
        </div>
      }
    >
      {/*
        * 过滤条 —— 对齐原型「默认态」`.filters`：**4 个裸 Input 平铺 + 查询/重置**，
        * `gap:8`、每个 `width:180`、无字段标签、无下边框。
        *
        * 🔧 task-260909 改动：placeholder 由示例值（`如 SP-10110001`）改为**字段名本身**
        *   （`销售料号`），并去掉上方的 `<label>` —— 原型的 4 个 input 就是
        *   `placeholder="客户产品编号|销售料号|品名|规格"`，字段名在 placeholder 里而不在标签里。
        * ⚠️ 这不只是观感：过滤框的**可定位性**挂在 placeholder 上 ——
        *   按字段名找输入框是 AC-10/AC-14 的操作前提，改前四个 placeholder 全是示例值，
        *   按「销售料号」根本定位不到，那种失败长得像产品 bug，实则是实现与原型不一致。
        */}
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap', marginBottom: 16 }}>
        <Input
          style={{ width: 180 }}
          placeholder="客户产品编号"
          value={filters.customerProductNo}
          onChange={(e) => setFilters((f) => ({ ...f, customerProductNo: e.target.value }))}
          onKeyDown={handleFilterKeyDown}
        />
        <Input
          style={{ width: 180 }}
          placeholder="销售料号"
          value={filters.salesPartNo}
          onChange={(e) => setFilters((f) => ({ ...f, salesPartNo: e.target.value }))}
          onKeyDown={handleFilterKeyDown}
        />
        <Input
          style={{ width: 180 }}
          placeholder="品名"
          value={filters.productName}
          onChange={(e) => setFilters((f) => ({ ...f, productName: e.target.value }))}
          onKeyDown={handleFilterKeyDown}
        />
        <Input
          style={{ width: 180 }}
          placeholder="规格"
          value={filters.spec}
          onChange={(e) => setFilters((f) => ({ ...f, spec: e.target.value }))}
          onKeyDown={handleFilterKeyDown}
        />
        <Button type="primary" onClick={handleQuery}>
          查询
        </Button>
        <Button onClick={handleReset}>重置</Button>
      </div>

      {/*
        * 产品列表 —— task-260909 · AC-7 起**占满抽屉全宽**（原右侧 3D 预览区整块移除）。
        * 🚫 抽屉宽度保持 960 不变：腾出来的空间正好给新增的「客户图号」列。
        */}
      <Table<ExistingProductDTO>
        rowKey="materialNo"
        size="small"
        loading={loading}
        columns={columns}
        dataSource={list}
        pagination={
          total > PAGE_SIZE
            ? {
                current: page + 1,
                pageSize: PAGE_SIZE,
                total,
                size: 'small',
                showSizeChanger: false,
                /*
                 * task-260909：补「共 N 条」总数文案，对齐原型「默认态」分页条左侧的
                 * `.pg-total`（`<span class="pg-total">共 10 条</span>`）—— 改动前 AntD
                 * 默认不渲染任何总数，这块是原型画了而实现没有的 1:1 还原缺口。
                 * ⚠️ 只补文案，🚫 不动 `total > PAGE_SIZE` 的渲染门槛，也不动 PAGE_SIZE：
                 *   「total ≤ 20 不显示分页器」是原型「空态与边界」①明确要求「保持不变」的。
                 */
                showTotal: (t) => `共 ${t} 条`,
                onChange: (p) => setPage(p - 1),
              }
            : false
        }
        rowSelection={{
          selectedRowKeys,
          onChange: (keys) => setSelectedRowKeys(keys as string[]),
          preserveSelectedRowKeys: true,
        }}
        onRow={(record) => ({
          // 单击整行 = 高亮该行（原型「默认态」的 tr.active 底色 #e6f4ff）。
          // 🚫 它**不再触发任何请求** —— 3D 预览已随 AC-7 移除。
          onClick: () => setActiveRow(record),
          style: {
            cursor: 'pointer',
            background: activeRow?.materialNo === record.materialNo ? '#e6f4ff' : undefined,
          },
        })}
        /*
         * 空态 = AntD 标准 <Empty>（原型「空态与边界」①，AC-12）。
         * 🚫 绝不能是红色遮罩，也不能是永久「加载中…」占位（AP-31 族）——
         *   加载中由 Table 自带的 `loading` 承担，且它会随 finally 结束。
         * 文案分两种，因为「这个客户没产品」和「过滤没命中」是两件事，不该长得一样：
         *   · 无过滤条件（AC-12 那一屏）→ 原型文案「暂无数据」
         *   · 有过滤条件            → 保留既有引导文案（改动前行为，未回归）
         */
        locale={{
          emptyText: (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={hasActiveFilter ? '未查到匹配的产品，请调整过滤条件后重试' : '暂无数据'}
              style={{ padding: '36px 0' }}
            />
          ),
        }}
      />
    </Drawer>
  );
};

export default AddProductModal;
