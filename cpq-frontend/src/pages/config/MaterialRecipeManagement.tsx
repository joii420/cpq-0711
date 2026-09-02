import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Tag, Button, Space, Input, Select, Drawer, Alert, Table, message } from 'antd';
import {
  PlusOutlined, ImportOutlined, ReloadOutlined, DownloadOutlined,
} from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import SelectableTable from '../../components/SelectableTable';
import type { ToolbarAction } from '../../components/SelectableTable';
import {
  SEARCH_WIDTH, FILTER_MIN_WIDTH, SEARCH_DEBOUNCE_MS, DEFAULT_PAGE_SIZE, commonPagination,
} from '../master-data/listConventions';
import { NO_SORT, clientSortProps, nextClientSort, type ClientSortState } from '../master-data/clientSorters';
import {
  materialRecipeService,
  type MaterialRecipeLite,
} from '../../services/materialRecipeService';
import MaterialRecipeEditDrawer from './MaterialRecipeEditDrawer';
import MaterialRecipeCreateDrawer from './MaterialRecipeCreateDrawer';
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
 * 材质页签（task-0728 · F3；task-260901 · F-1 / F-2 改版）
 *
 * task-260901 三点变化，对照 `原型图/1-材质管理页.html` 状态 A / B / C / D：
 *   ① 列表新增三列：**元素组成**（`elementCodes`，权威源是材质的元素组成表 ——
 *      **0 配置的材质这一列照样有值**）、**含量配置**（`configCount` 组；0 → 金色 tag「未配置含量」）、
 *      **支持自定义含量**（是/否）。
 *   ② 🚫 **不做行展开** —— 不加展开箭头、不做展开区（闸门 A 裁决：元素种类多时展开区放不下，
 *      配置统一进材质编辑抽屉）。列表只回答「有几组」，要看内容点「编辑」。
 *   ③ 工具栏 = `新建材质 / 编辑 / 停用 / 导入材质库 / 下载导入模板`，
 *      ⚠️ **没有「新增含量配置」按钮**（配置操作全在抽屉内）。
 *
 * 「新建材质」与「编辑材质」是**两套不同形态的抽屉**（新建走配方卡片，见 F-13），不共用组件。
 * → 服务 AC-13 / AC-17 / AC-29
 */
const MaterialRecipeManagement: React.FC = () => {
  const [list, setList] = useState<MaterialRecipeLite[]>([]);
  const [loading, setLoading] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [downloading, setDownloading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const debounceRef = useRef<number | undefined>(undefined);

  // 停用二次确认（frontend.md §1.2 危险动作走弹层并逐条列出所选项；AC-29）
  const [disableTargets, setDisableTargets] = useState<MaterialRecipeLite[]>([]);
  const [disableOpen, setDisableOpen] = useState(false);
  const [disabling, setDisabling] = useState(false);

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

  const openEdit = (id: string) => {
    setEditingId(id);
    setEditOpen(true);
  };

  /**
   * F-14 / AC-37：**点行任意处打开编辑抽屉**（用户 2026-09-02 裁决）。
   *
   * 为什么是「点行」而不是「勾选就开」：`停用` 按 AC-29 支持多选，
   * **勾第 1 条就弹抽屉会遮住列表、勾不了第 2 条**。所以复选框只管选择，绝不开抽屉。
   *
   * 为什么用**原生捕获阶段委托**而不是给 SelectableTable 加 prop：
   *   ① `SelectableTable.tsx` 是全项目列表页共用的组件，本任务不改它（越界）；
   *   ② 它自己的 `onRow.onClick` 会把点中的行**切成已选**。React 的 onClick 挂在根容器上走冒泡，
   *      而本监听器挂在 wrapper 上走捕获 —— 先于目标元素触发，`stopPropagation()` 后
   *      冒泡阶段根本不会发生 ⇒ 既能开抽屉，又不会顺手把这行选中（否则关掉抽屉会留下一个莫名其妙的选中态）。
   *
   * 🚫 三类必须排除（排除时**不**调 stopPropagation，让它们各自的原逻辑照常跑）：
   *   1. 复选框单元格 —— 见上，多选停用的前提
   *   2. 材质编号链接 `<a>` —— 既有主入口已能打开，不能触发两次
   *   3. 行内其他可点元素（button / input / label / Select 等）
   */
  const tableWrapRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = tableWrapRef.current;
    if (!el) return;
    const onRowClickCapture = (e: MouseEvent) => {
      const target = e.target as HTMLElement | null;
      if (!target) return;
      if (target.closest(
        '.ant-table-selection-column, .ant-table-selection, .ant-checkbox-wrapper, .ant-checkbox,'
        + ' a, button, input, label, .ant-select, .ant-dropdown, .ant-pagination, .ant-table-thead',
      )) return;
      const tr = target.closest('tr.ant-table-row') as HTMLElement | null;
      if (!tr) return;                                   // 表头 / 空态占位行不算
      const key = tr.getAttribute('data-row-key');
      if (!key) return;
      e.stopPropagation();                               // 截断，避免被 SelectableTable 切成「已选」
      // setState 是稳定引用，这里不依赖闭包里的任何业务值，故 effect 依赖为空数组是安全的
      setEditingId(key);
      setEditOpen(true);
    };
    el.addEventListener('click', onRowClickCapture, true);
    return () => el.removeEventListener('click', onRowClickCapture, true);
  }, []);

  const handleDownloadTemplate = async () => {
    setDownloading(true);
    try {
      const blob = await materialRecipeService.downloadTemplate();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'material_library_template.xlsx';
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch {
      message.error('模板下载失败，请稍后重试');
    } finally {
      setDownloading(false);
    }
  };

  const confirmDisable = async () => {
    setDisabling(true);
    const failed: string[] = [];
    for (const r of disableTargets) {
      try {
        await materialRecipeService.deleteSoft(r.id);
      } catch (e: any) {
        failed.push(`${r.code} ${r.symbol}：${e?.message ?? '失败'}`);
      }
    }
    setDisabling(false);
    setDisableOpen(false);
    setDisableTargets([]);
    if (failed.length > 0) {
      // 「部分失败」必须聚合并列出失败明细（frontend.md §1.2）
      message.error(`${failed.length} 项停用失败：${failed.join('；')}`);
    } else {
      message.success(`已停用 ${disableTargets.length} 项`);
    }
    refresh(keyword.trim() || undefined);
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
      title: '材质名 / 化学式',
      dataIndex: 'symbol',
      key: 'symbol',
      width: 160,
      ...sortable('symbol', (r) => r.symbol, 'text'),
      render: (v: string) => <b>{v}</b>,
    },
    {
      title: '名称',
      dataIndex: 'name',
      key: 'name',
      width: 150,
      ...sortable('name', (r) => r.name, 'text'),
    },
    {
      // ⚠️ BC-2b：权威源是 material_recipe_composition，**0 配置的材质照样有值** ——
      // 🚫 不许写成「无配置就显示 —」
      title: '元素组成',
      key: 'elementCodes',
      width: 200,
      render: (_: unknown, r: MaterialRecipeLite) => {
        const codes = r.elementCodes ?? [];
        if (codes.length === 0) return <span style={{ color: 'rgba(0,0,0,.25)' }}>—</span>;
        return (
          <Space size={[4, 4]} wrap>
            {codes.map((c) => <Tag key={c} color="blue">{c}</Tag>)}
          </Space>
        );
      },
    },
    {
      title: '含量配置',
      key: 'configCount',
      width: 120,
      ...sortable('configCount', (r) => r.configCount ?? 0, 'number'),
      render: (_: unknown, r: MaterialRecipeLite) => (
        (r.configCount ?? 0) > 0
          ? <Tag color="green">{r.configCount} 组</Tag>
          : <Tag color="gold">未配置含量</Tag>
      ),
    },
    {
      title: '支持自定义含量',
      key: 'allowCustomContent',
      width: 130,
      ...sortable('allowCustomContent', (r) => (r.allowCustomContent ? 1 : 0), 'number'),
      render: (_: unknown, r: MaterialRecipeLite) => (
        r.allowCustomContent ? <Tag color="blue">是</Tag> : <Tag>否</Tag>
      ),
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
  ];

  // ⚠️ 工具栏没有「新增含量配置」—— 配置操作一律在材质编辑抽屉内（AC-29）
  const actions: ToolbarAction<MaterialRecipeLite>[] = [
    {
      key: 'edit',
      label: '编辑',
      // 🚫 刻意不挂图标：原型 1 的工具栏里「编辑」「停用」是纯文字按钮（只有「+ 新建材质」带号）。
      // 附带好处 —— AntD 图标是 `role="img" aria-label="edit"`，会被算进按钮的**可访问名**
      // （变成 "edit编辑"），挂了图标就再也用 `name: /^编辑$/` 定位不到这个按钮。
      // 禁用但可见 + hover 给原因；0 行与多行两种原因分开写（AC-29）
      enabledWhen: (rows) => {
        if (rows.length === 0) return '请先选择一个材质（当前选中 0 个）';
        if (rows.length > 1) return `只能选择一个材质（当前选中 ${rows.length} 个）`;
        return true;
      },
      onClick: (rows) => openEdit(rows[0].id),
    },
    {
      key: 'disable',
      label: '停用',
      danger: true,
      enabledWhen: (rows) => {
        if (rows.length === 0) return '请先选择材质（当前选中 0 个）';
        if (rows.some((r) => !isActive(r.status))) return '仅启用状态可停用';
        return true;
      },
      // 走自建 Drawer 二次确认（SelectableTable 内置 needsConfirm 是 Modal，AC-29 要求抽屉）
      onClick: (rows) => { setDisableTargets(rows); setDisableOpen(true); },
    },
  ];

  // 工具栏：左＝查询（搜索 → 过滤下拉），右＝动作（刷新 → 导入 → 下载模板 → 新建）。
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
        {/* 🚫 固定枚举，不开 showSearch（fronttask §0 #5/#6） */}
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
        <Button icon={<DownloadOutlined />} loading={downloading} onClick={handleDownloadTemplate}>
          下载导入模板
        </Button>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
          新建材质
        </Button>
      </Space>
    </>
  );

  return (
    <>
      {/* F-14：整行可点。委托挂在这层 wrapper 上，SelectableTable 本身零改动 */}
      <div ref={tableWrapRef}>
      <SelectableTable<MaterialRecipeLite>
        rowKey="id"
        size="small"
        columns={columns}
        dataSource={filteredList}
        loading={loading}
        toolbar={toolbar}
        // 列变多后横向滚动交给表格自身，页面 body 不出现横向滚动条
        scroll={{ x: 'max-content' }}
        pagination={{
          ...commonPagination,
          current: page,
          pageSize,
          onChange: (p, ps) => { setPage(p); setPageSize(ps); },
        }}
        actions={actions}
        rowLabel={(r) => `${r.code} ${r.symbol}`}
      />
      </div>

      <MaterialRecipeCreateDrawer
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={() => { setCreateOpen(false); refresh(keyword.trim() || undefined); }}
      />

      <MaterialRecipeEditDrawer
        open={editOpen}
        recipeId={editingId}
        onClose={() => setEditOpen(false)}
        onSaved={() => { refresh(keyword.trim() || undefined); }}
      />

      <MaterialImportDrawer
        open={importOpen}
        onClose={() => setImportOpen(false)}
        onImported={() => { setImportOpen(false); refresh(keyword.trim() || undefined); }}
      />

      {/* 停用二次确认抽屉：逐条列出将被停用的材质编号与名称（AC-29） */}
      <Drawer
        title="停用材质"
        open={disableOpen}
        onClose={() => setDisableOpen(false)}
        width={560}
        placement="right"
        maskClosable={false}
        destroyOnClose
        footer={
          <div style={{ textAlign: 'right' }}>
            <Space>
              <Button onClick={() => setDisableOpen(false)}>取消</Button>
              <Button danger type="primary" loading={disabling} onClick={confirmDisable}>
                确认停用
              </Button>
            </Space>
          </div>
        }
      >
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message={`将停用以下 ${disableTargets.length} 项材质：`}
          description="停用后选配抽屉将不再显示该材质。可在材质编辑抽屉把状态改回「启用」。"
        />
        <Table<MaterialRecipeLite>
          rowKey="id"
          size="small"
          pagination={false}
          dataSource={disableTargets}
          columns={[
            { title: '材质编号', dataIndex: 'code', key: 'code', width: 120 },
            { title: '材质名 / 化学式', dataIndex: 'symbol', key: 'symbol' },
            {
              title: '含量配置',
              key: 'configCount',
              width: 110,
              render: (_: unknown, r: MaterialRecipeLite) =>
                (r.configCount ?? 0) > 0 ? `${r.configCount} 组` : '未配置含量',
            },
          ]}
        />
      </Drawer>
    </>
  );
};

export default MaterialRecipeManagement;
