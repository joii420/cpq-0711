// ─────────────────────────────────────────────────────────────────────────────
// V6ProcessCrudTab —— 工序主数据（task-0728 · F5）
//   服务端分页 + 服务端排序 + 服务端过滤（是否外协 / 工序分类）。
//   ⚠️ 走 SelectableTable：它内部的 toolbar 容器**已经是**同一套
//      space-between / gap 8 / wrap / marginBottom 12 的 flex，
//      所以这里**不要**再包一层 TOOLBAR_ROW_STYLE 的 div，
//      直接把左右两组作为并列子节点传给 toolbar（见 listConventions.ts 用法说明 2）。
// ─────────────────────────────────────────────────────────────────────────────
import React, { useState, useCallback, useEffect, useMemo, useRef } from 'react';
import {
  Input, Button, Space, Select, Tooltip, Tag, Typography, Drawer, Form, Switch, InputNumber, message,
} from 'antd';
import { ReloadOutlined, PlusOutlined, EditOutlined, DeleteOutlined, ImportOutlined } from '@ant-design/icons';
import type { ColumnsType, TableProps } from 'antd/es/table';
import type { SortOrder } from 'antd/es/table/interface';
import SelectableTable, { runBatch } from '../../components/SelectableTable';
import {
  listProcesses, listProcessCategories, createProcess, updateProcess, deleteProcess,
} from '../../services/v6MasterDataService';
import type { ProcessMasterDTO, ProcessMasterUpsert, ProcessSortBy } from '../../services/v6MasterDataService';
import V6ProcessDetailDrawer from './V6ProcessDetailDrawer';
import ProcessMasterImportDrawer from './ProcessMasterImportDrawer';
import {
  SEARCH_WIDTH,
  FILTER_MIN_WIDTH,
  SEARCH_DEBOUNCE_MS,
  DEFAULT_PAGE_SIZE,
  commonPagination,
} from './listConventions';
import { formatDisplayDecimal, type DecimalString } from '../../utils/precision';

const { Search } = Input;

/**
 * 列 dataIndex → `api.md` A2 `sortBy` 白名单值。
 * 8 个数据列的 dataIndex 与白名单 key 逐字一致，这里仍显式列一张表，
 * 避免以后加了非白名单列（如渲染列）时把野值发给后端。
 */
const SORT_KEY_MAP: Record<string, ProcessSortBy> = {
  processNo: 'processNo',
  processName: 'processName',
  processCategory: 'processCategory',
  isOutsource: 'isOutsource',
  standardCurrency: 'standardCurrency',
  standardUnit: 'standardUnit',
  defaultDefectRate: 'defaultDefectRate',
  updatedAt: 'updatedAt',
};

/** 「是否外协」过滤：UI 三态 → A2 的 `isOutsource`（boolean | undefined）。
 *  ⚠️ 契约：`is_outsource IS NULL` 的行既不归「外协」也不归「自制」，只在「全部」下出现。 */
type OutsourceFilter = 'ALL' | 'OUT' | 'IN';
const OUTSOURCE_OPTIONS = [
  { value: 'ALL', label: '全部' },
  { value: 'OUT', label: '外协' },
  { value: 'IN', label: '自制' },
];

const V6ProcessCrudTab: React.FC = () => {
  const [keyword, setKeyword] = useState('');
  const [inputValue, setInputValue] = useState('');
  const [loading, setLoading] = useState(false);
  const [data, setData] = useState<ProcessMasterDTO[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(DEFAULT_PAGE_SIZE);

  // 过滤（服务端）
  const [outsourceFilter, setOutsourceFilter] = useState<OutsourceFilter>('ALL');
  const [categoryFilter, setCategoryFilter] = useState<string | undefined>(undefined);
  const [categories, setCategories] = useState<string[]>([]);

  // 排序（服务端）：sortOrder 为 antd 三态（null = 取消，回默认序 process_no ASC）
  const [sortBy, setSortBy] = useState<ProcessSortBy | undefined>(undefined);
  const [sortOrder, setSortOrder] = useState<SortOrder>(null);

  // 详情(只读)抽屉
  const [detailOpen, setDetailOpen] = useState(false);
  const [detailRecord, setDetailRecord] = useState<ProcessMasterDTO | null>(null);

  // 新建/编辑 表单抽屉
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<ProcessMasterDTO | null>(null); // null = 新建
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm<ProcessMasterUpsert>();

  // 批量导入抽屉（childtask-1 · F1）
  const [importOpen, setImportOpen] = useState(false);

  const debounceTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  useEffect(() => () => { if (debounceTimer.current) clearTimeout(debounceTimer.current); }, []);

  const isOutsourceParam = useMemo<boolean | undefined>(() => {
    if (outsourceFilter === 'OUT') return true;
    if (outsourceFilter === 'IN') return false;
    return undefined;
  }, [outsourceFilter]);

  const fetchData = useCallback(async () => {
    setLoading(true);
    try {
      const result = await listProcesses({
        keyword: keyword || undefined,
        page: page - 1, // ⚠️ 后端 0-based，别把这个 -1 弄丢
        size,
        // sortOrder 仅在 sortBy 有值时才发送
        sortBy: sortOrder ? sortBy : undefined,
        sortOrder: sortOrder ? (sortOrder === 'ascend' ? 'asc' : 'desc') : undefined,
        isOutsource: isOutsourceParam,
        processCategory: categoryFilter || undefined,
      });
      setData(result.content);
      setTotal(result.totalElements);
    } catch {
      setData([]);
      setTotal(0);
    } finally {
      setLoading(false);
    }
  }, [keyword, page, size, sortBy, sortOrder, isOutsourceParam, categoryFilter]);

  useEffect(() => { void fetchData(); }, [fetchData]);

  // 工序分类选项（A3）：挂载时拉一次；返空数组时下拉禁用 + tooltip
  useEffect(() => {
    let alive = true;
    listProcessCategories()
      .then((list) => { if (alive) setCategories(list ?? []); })
      .catch(() => { if (alive) setCategories([]); });
    return () => { alive = false; };
  }, []);

  const handleInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = e.target.value;
    setInputValue(val);
    if (debounceTimer.current) clearTimeout(debounceTimer.current);
    debounceTimer.current = setTimeout(() => {
      setKeyword(val);
      setPage(1);
    }, SEARCH_DEBOUNCE_MS);
  };

  const handleRefresh = () => { void fetchData(); };

  /**
   * 排序变化（三态：升序 → 降序 → 取消）。
   * 只处理 `extra.action === 'sort'`；翻页/改页大小走 pagination.onChange，
   * 否则会被这里的 setPage(1) 顶回第 1 页。
   */
  const handleTableChange: TableProps<ProcessMasterDTO>['onChange'] = (_p, _f, sorter, extra) => {
    if (extra?.action !== 'sort') return;
    const s = Array.isArray(sorter) ? sorter[0] : sorter;
    const order = s?.order ?? null;
    if (!order) {
      setSortBy(undefined);
      setSortOrder(null);
    } else {
      const columnKey = String(s?.columnKey ?? s?.field ?? '');
      setSortBy(SORT_KEY_MAP[columnKey]);
      setSortOrder(order);
    }
    setPage(1);
  };

  /** 受控 sortOrder：同一时刻只有命中 sortBy 的那一列高亮 */
  const orderOf = (columnKey: string): SortOrder =>
    sortBy && SORT_KEY_MAP[columnKey] === sortBy ? sortOrder : null;

  const openDetail = (record: ProcessMasterDTO) => {
    setDetailRecord(record);
    setDetailOpen(true);
  };

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ isOutsource: false });
    setFormOpen(true);
  };

  const openEdit = (record: ProcessMasterDTO) => {
    setEditing(record);
    form.setFieldsValue({
      processNo: record.processNo,
      processName: record.processName,
      processCategory: record.processCategory,
      isOutsource: record.isOutsource ?? false,
      standardCurrency: record.standardCurrency,
      standardUnit: record.standardUnit,
      defaultDefectRate: record.defaultDefectRate,
    });
    setFormOpen(true);
  };

  const handleSubmit = async () => {
    let values: ProcessMasterUpsert;
    try {
      values = await form.validateFields();
    } catch {
      return; // 校验失败, AntD 已高亮
    }
    setSaving(true);
    try {
      if (editing) {
        await updateProcess(editing.id, values);
        message.success('工序已更新');
      } else {
        await createProcess(values);
        message.success('工序已新建');
      }
      setFormOpen(false);
      void fetchData();
    } catch (e: any) {
      message.error(e?.message ?? (editing ? '更新失败' : '新建失败'));
    } finally {
      setSaving(false);
    }
  };

  const columns: ColumnsType<ProcessMasterDTO> = [
    {
      title: '工序编号',
      dataIndex: 'processNo',
      key: 'processNo',
      width: 160,
      sorter: true,
      sortOrder: orderOf('processNo'),
      render: (val: string, record) => (
        <Typography.Link onClick={() => openDetail(record)}>{val}</Typography.Link>
      ),
    },
    {
      title: '工序名称',
      dataIndex: 'processName',
      key: 'processName',
      width: 180,
      sorter: true,
      sortOrder: orderOf('processName'),
      render: (v: string) => v || '—',
    },
    {
      title: '工序分类',
      dataIndex: 'processCategory',
      key: 'processCategory',
      width: 120,
      sorter: true,
      sortOrder: orderOf('processCategory'),
      render: (v: string) => v || '—',
    },
    {
      title: '是否外协',
      dataIndex: 'isOutsource',
      key: 'isOutsource',
      width: 90,
      sorter: true,
      sortOrder: orderOf('isOutsource'),
      render: (v: boolean | undefined) =>
        v === true ? <Tag color="orange">外协</Tag> : v === false ? <Tag color="default">自制</Tag> : '—',
    },
    {
      title: '标准货币',
      dataIndex: 'standardCurrency',
      key: 'standardCurrency',
      width: 100,
      sorter: true,
      sortOrder: orderOf('standardCurrency'),
      render: (v: string) => v || '—',
    },
    {
      title: '标准单位',
      dataIndex: 'standardUnit',
      key: 'standardUnit',
      width: 90,
      sorter: true,
      sortOrder: orderOf('standardUnit'),
      render: (v: string) => v || '—',
    },
    {
      title: '默认不良率',
      dataIndex: 'defaultDefectRate',
      key: 'defaultDefectRate',
      width: 100,
      sorter: true,
      sortOrder: orderOf('defaultDefectRate'),
      render: (v: DecimalString | null | undefined) => (v != null ? formatDisplayDecimal(v) : '—'),
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      width: 160,
      sorter: true,
      sortOrder: orderOf('updatedAt'),
      render: (v: string) => v || '—',
    },
  ];

  const noCategory = categories.length === 0;

  // ⚠️ SelectableTable 内部 toolbar 容器已是 space-between 的 flex，这里直接给它两个并列子节点，
  //    不要再包一层 div，否则右组会被挤到左边。
  const toolbar = (
    <>
      <Space wrap>
        <Search
          placeholder="搜索工序编号 / 名称"
          value={inputValue}
          onChange={handleInputChange}
          onSearch={(v) => {
            if (debounceTimer.current) clearTimeout(debounceTimer.current);
            setKeyword(v);
            setPage(1);
          }}
          allowClear
          style={{ width: SEARCH_WIDTH }}
          onClear={() => {
            if (debounceTimer.current) clearTimeout(debounceTimer.current);
            setInputValue('');
            setKeyword('');
            setPage(1);
          }}
        />
        <Select<OutsourceFilter>
          value={outsourceFilter}
          style={{ minWidth: FILTER_MIN_WIDTH }}
          options={OUTSOURCE_OPTIONS}
          onChange={(v) => { setOutsourceFilter(v ?? 'ALL'); setPage(1); }}
        />
        <Tooltip title={noCategory ? '暂无分类数据' : ''}>
          <span style={{ display: 'inline-block' }}>
            <Select<string | undefined>
              allowClear
              disabled={noCategory}
              value={categoryFilter}
              placeholder="工序分类"
              style={{ minWidth: FILTER_MIN_WIDTH }}
              options={categories.map((c) => ({ value: c, label: c }))}
              onChange={(v) => { setCategoryFilter(v ?? undefined); setPage(1); }}
            />
          </span>
        </Tooltip>
      </Space>
      <Space wrap>
        <Button icon={<ReloadOutlined />} onClick={handleRefresh}>刷新</Button>
        <Button icon={<ImportOutlined />} onClick={() => setImportOpen(true)}>导入工序</Button>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增工序</Button>
      </Space>
    </>
  );

  return (
    <>
      <SelectableTable<ProcessMasterDTO>
        rowKey="id"
        size="small"
        columns={columns}
        dataSource={data}
        loading={loading}
        rowLabel={(r) => `${r.processNo} ${r.processName}`}
        toolbar={toolbar}
        onChange={handleTableChange}
        actions={[
          {
            key: 'edit',
            label: '编辑',
            icon: <EditOutlined />,
            enabledWhen: (rows) => (rows.length === 1 ? true : '编辑一次只能选一行'),
            onClick: (rows) => openEdit(rows[0]),
          },
          {
            key: 'delete',
            label: '删除',
            icon: <DeleteOutlined />,
            danger: true,
            needsConfirm: true,
            confirmTitle: '确认删除选中的 {N} 个工序?',
            confirmDescription: '硬删除不可恢复。若被报价单工序引用, 引用处会回退显示工序代码(不影响报价单本身)。',
            enabledWhen: (rows) => (rows.length >= 1 ? true : '请先选择要删除的工序'),
            onClick: async (rows) => {
              await runBatch(
                rows,
                (r) => deleteProcess(r.id),
                { rowLabel: (r) => `${r.processNo} ${r.processName}`, successMsg: `已删除 ${rows.length} 个工序` },
              );
              void fetchData();
            },
          },
        ]}
        locale={{ emptyText: keyword ? `未找到匹配"${keyword}"的工序数据` : '暂无工序数据' }}
        pagination={{
          ...commonPagination,
          current: page,
          pageSize: size,
          total,
          onChange: (p, s) => { setPage(p); setSize(s); },
        }}
        scroll={{ x: 900 }}
      />

      <V6ProcessDetailDrawer open={detailOpen} record={detailRecord} onClose={() => setDetailOpen(false)} />

      <ProcessMasterImportDrawer
        open={importOpen}
        onClose={() => setImportOpen(false)}
        onImported={() => { void fetchData(); }}
      />

      <Drawer
        title={editing ? `编辑工序 · ${editing.processNo}` : '新建工序'}
        placement="right"
        width={480}
        open={formOpen}
        onClose={() => setFormOpen(false)}
        destroyOnClose
        footer={
          <Space style={{ float: 'right' }}>
            <Button onClick={() => setFormOpen(false)}>取消</Button>
            <Button type="primary" loading={saving} onClick={handleSubmit}>保存</Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical" initialValues={{ isOutsource: false }}>
          <Form.Item
            label="工序编号"
            name="processNo"
            rules={[{ required: true, message: '请输入工序编号' }, { max: 20, message: '不超过 20 字符' }]}
            extra={editing ? '工序编号为业务主键, 编辑时不可修改' : undefined}
          >
            <Input placeholder="如 OP-001" disabled={!!editing} />
          </Form.Item>
          <Form.Item
            label="工序名称"
            name="processName"
            rules={[{ required: true, message: '请输入工序名称' }, { max: 50, message: '不超过 50 字符' }]}
          >
            <Input placeholder="如 冲压" />
          </Form.Item>
          <Form.Item label="工序分类" name="processCategory">
            <Input placeholder="如 制造 / 组装 / 电镀 / 包装 / 清洗" />
          </Form.Item>
          <Form.Item label="是否外协" name="isOutsource" valuePropName="checked">
            <Switch checkedChildren="外协" unCheckedChildren="自制" />
          </Form.Item>
          <Form.Item label="标准货币" name="standardCurrency">
            <Input placeholder="如 CNY" />
          </Form.Item>
          <Form.Item label="标准单位" name="standardUnit">
            <Input placeholder="如 PCS / KG" />
          </Form.Item>
          <Form.Item label="默认不良率" name="defaultDefectRate">
            <InputNumber<string> stringMode min="0" step="0.01" style={{ width: '100%' }} placeholder="如 0.02 表示 2%" />
          </Form.Item>
        </Form>
      </Drawer>
    </>
  );
};

export default V6ProcessCrudTab;
