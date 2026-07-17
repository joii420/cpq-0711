import React, { useEffect, useMemo, useState } from 'react';
import { Card, Drawer, Form, Input, Select, Segmented, Button, message, Tag, Checkbox } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, SwapOutlined } from '@ant-design/icons';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';
import { selTemplateService } from '../../services/selTemplateService';
import { productCategoryService, type ProductCategory } from '../../services/productCategoryService';

interface ParamTypeRow {
  code: string;
  name: string;
  valueMode: 'single' | 'multi' | 'adjust';
  dataSourceKey?: string | null;
  persistHandlerKey?: string | null;
  sortOrder: number;
}

interface CandidateOption {
  key: string;
  label: string;
}

interface TemplateItemDTO {
  paramTypeCode: string;
  enabled: boolean;
  sortOrder?: number;
  allowedValues: string[];
}

interface TemplateRow {
  id: string;
  productCategoryId: string;
  name: string;
  status: string;
  version?: number;
  items: TemplateItemDTO[];
}

interface ItemState {
  enabled: boolean;
  allowedValues: string[];
}

const STATUS_OPTIONS = [
  { value: 'ACTIVE', label: '启用' },
  { value: 'INACTIVE', label: '停用' },
];

// 参数 valueMode 的中文标注，拼成原型「（单选 · single）」样式
const VALUE_MODE_LABEL: Record<ParamTypeRow['valueMode'], string> = {
  single: '单选',
  multi: '多选',
  adjust: '微调',
};

// 参数说明文案（对照原型 .param-desc 逐字复刻；未知参数码走通用兜底）
const PARAM_DESC: Record<string, string> = {
  MATERIAL: '从材质库限定可选材质；下方多选下拉留空 = 不限，选配时可选任意材质',
  ELEMENT: '启用后允许在派生元素含量上微调（选配时列出所选材质派生的元素及含量，可微调数值）',
  PROCESS: '从工序库限定可选工序；下方多选下拉留空 = 不限，选配时可任意选择并排序',
};

const paramDescFor = (pt: ParamTypeRow): string =>
  PARAM_DESC[pt.code] ?? (pt.valueMode === 'adjust'
    ? '启用后允许在派生数值上微调'
    : '下方多选下拉留空 = 不限，选配时可任意选择');

const sectionTitleStyle = (first?: boolean): React.CSSProperties => ({
  fontSize: 14,
  fontWeight: 600,
  margin: first ? '0 0 10px' : '20px 0 10px',
  paddingTop: first ? 0 : 4,
  borderTop: first ? 'none' : '1px dashed #e4e7ed',
});

const SelTemplateManagement: React.FC = () => {
  const [templates, setTemplates] = useState<TemplateRow[]>([]);
  const [paramTypes, setParamTypes] = useState<ParamTypeRow[]>([]);
  const [categories, setCategories] = useState<ProductCategory[]>([]);
  const [loading, setLoading] = useState(false);

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<TemplateRow | null>(null);
  const [itemsState, setItemsState] = useState<Record<string, ItemState>>({});
  const [form] = Form.useForm();

  const [candidatesCache, setCandidatesCache] = useState<Record<string, CandidateOption[]>>({});
  const [candidatesLoading, setCandidatesLoading] = useState<Record<string, boolean>>({});

  const categoryNameMap = useMemo(() => {
    const map: Record<string, string> = {};
    categories.forEach((c) => { map[c.id] = c.name; });
    return map;
  }, [categories]);

  // 下拉展示：原型不在选项文案里附加编码，仅显示中文名
  const categoryOptions = useMemo(
    () => categories.map((c) => ({ value: c.id, label: c.name })),
    [categories],
  );

  // 新建时排除已配置模板的产品分类（D7/D10 一分类一套；对照原型 industryOptionsHTML() 的 avail 过滤）
  const usedCategoryIds = useMemo(
    () => new Set(templates.map((t) => t.productCategoryId)),
    [templates],
  );
  const createCategoryOptions = useMemo(
    () => categoryOptions.filter((o) => !usedCategoryIds.has(o.value)),
    [categoryOptions, usedCategoryIds],
  );

  const sortedParamTypes = useMemo(
    () => [...paramTypes].sort((a, b) => a.sortOrder - b.sortOrder),
    [paramTypes],
  );

  const fetchTemplates = async () => {
    setLoading(true);
    try {
      const res = await selTemplateService.list();
      setTemplates(res?.data ?? []);
    } catch (err: any) {
      message.error(err.message);
    } finally {
      setLoading(false);
    }
  };

  const fetchParamTypes = async () => {
    try {
      const res = await selTemplateService.listParamTypes();
      setParamTypes(res?.data ?? []);
    } catch (err: any) {
      message.error(err.message);
    }
  };

  const fetchCategories = async () => {
    try {
      const res = await productCategoryService.list('ACTIVE');
      setCategories(res?.data ?? []);
    } catch (err: any) {
      message.error(err.message);
    }
  };

  useEffect(() => {
    fetchTemplates();
    fetchParamTypes();
    fetchCategories();
  }, []);

  const ensureCandidatesFor = async (code: string) => {
    if (candidatesCache[code]) return;
    setCandidatesLoading((prev) => ({ ...prev, [code]: true }));
    try {
      const res = await selTemplateService.candidates(code);
      setCandidatesCache((prev) => ({ ...prev, [code]: res?.data ?? [] }));
    } catch (err: any) {
      message.error(err.message);
    } finally {
      setCandidatesLoading((prev) => ({ ...prev, [code]: false }));
    }
  };

  const openCreate = () => {
    if (sortedParamTypes.length === 0) {
      message.warning('参数池加载中，请稍候再试');
      return;
    }
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ status: 'ACTIVE' });
    const init: Record<string, ItemState> = {};
    sortedParamTypes.forEach((pt) => { init[pt.code] = { enabled: false, allowedValues: [] }; });
    setItemsState(init);
    setDrawerOpen(true);
  };

  const openEdit = async (row: TemplateRow) => {
    try {
      const res = await selTemplateService.getById(row.id);
      const detail: TemplateRow = res?.data;
      // 提前加载非「元素含量」类参数的候选值，保证已选值一进抽屉就能显示中文标签，而不是只显示 key
      await Promise.all(
        sortedParamTypes.filter((pt) => pt.valueMode !== 'adjust').map((pt) => ensureCandidatesFor(pt.code)),
      );
      const init: Record<string, ItemState> = {};
      sortedParamTypes.forEach((pt) => {
        const found = detail.items?.find((it) => it.paramTypeCode === pt.code);
        init[pt.code] = {
          enabled: found?.enabled ?? false,
          allowedValues: found?.allowedValues ?? [],
        };
      });
      setItemsState(init);
      setEditing(detail);
      form.setFieldsValue({ productCategoryId: detail.productCategoryId, name: detail.name, status: detail.status });
      setDrawerOpen(true);
    } catch (err: any) {
      message.error(err.message);
    }
  };

  const closeDrawer = () => {
    setDrawerOpen(false);
    setEditing(null);
  };

  // 组装完整 items payload（补齐未出现的参数类型为「未启用」，避免全量替换语义下静默丢配置）
  const buildItemsPayload = (existing: TemplateItemDTO[]): TemplateItemDTO[] =>
    sortedParamTypes.map((pt) => {
      const found = existing.find((it) => it.paramTypeCode === pt.code);
      return {
        paramTypeCode: pt.code,
        enabled: found?.enabled ?? false,
        allowedValues: pt.valueMode === 'adjust' ? [] : (found?.allowedValues ?? []),
      };
    });

  const handleSave = async (values: any) => {
    try {
      const items = sortedParamTypes.map((pt) => ({
        paramTypeCode: pt.code,
        enabled: itemsState[pt.code]?.enabled ?? false,
        allowedValues: pt.valueMode === 'adjust' ? [] : (itemsState[pt.code]?.allowedValues ?? []),
      }));
      await selTemplateService.upsert({
        productCategoryId: values.productCategoryId,
        name: values.name,
        status: values.status,
        items,
      });
      message.success('保存成功');
      closeDrawer();
      fetchTemplates();
    } catch (err: any) {
      message.error(err.message);
    }
  };

  const handleSaveFailed = () => {
    message.error('请完整填写产品分类和模板名');
  };

  // 列定义 —— 产品分类列作为主入口（点击打开编辑 Drawer），行内不放动作按钮
  const columns = [
    {
      title: '产品分类', dataIndex: 'productCategoryId', key: 'productCategoryId',
      render: (id: string, record: TemplateRow) => (
        <a onClick={(e) => { e.stopPropagation(); openEdit(record); }} style={{ fontWeight: 500 }}>
          {categoryNameMap[id] ?? id}
        </a>
      ),
    },
    { title: '模板名', dataIndex: 'name', key: 'name' },
    {
      title: '启用参数数', key: 'enabledCount',
      render: (_: any, record: TemplateRow) => {
        const total = sortedParamTypes.length || record.items.length || 3;
        const enabled = record.items.filter((i) => i.enabled).length;
        return `${enabled}/${total}`;
      },
    },
    {
      title: '状态', dataIndex: 'status', key: 'status',
      render: (s: string) => <Tag color={s === 'ACTIVE' ? 'green' : 'default'}>{STATUS_OPTIONS.find((o) => o.value === s)?.label ?? s}</Tag>,
    },
  ];

  // 工具栏动作 —— 变更类动作统一上提到顶部，选择驱动启用/禁用
  const actions: ToolbarAction<TemplateRow>[] = [
    {
      key: 'edit',
      label: '编辑',
      icon: <EditOutlined />,
      enabledWhen: (sel) => (sel.length === 1 ? true : '编辑一次只能选一行'),
      onClick: (sel) => openEdit(sel[0]),
    },
    {
      key: 'toggle-status',
      label: '停用/启用',
      icon: <SwapOutlined />,
      enabledWhen: (sel) => sel.length > 0,
      onClick: async (sel) => {
        await runBatch(sel, (r) => selTemplateService.upsert({
          productCategoryId: r.productCategoryId,
          name: r.name,
          status: r.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
          items: buildItemsPayload(r.items),
        }), {
          rowLabel: (r) => `${categoryNameMap[r.productCategoryId] ?? r.productCategoryId} - ${r.name}`,
          successMsg: `已切换 ${sel.length} 个模板的启用/停用状态`,
        });
        fetchTemplates();
      },
    },
    {
      key: 'delete',
      label: '删除',
      icon: <DeleteOutlined />,
      danger: true,
      enabledWhen: (sel) => sel.length > 0,
      needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 个选配模板？',
      confirmDescription: '⚠️ 删除后该产品分类将不再有专属选配模板，选配运行时会回退到默认模板（如有）。',
      onClick: async (sel) => {
        await runBatch(sel, (r) => selTemplateService.delete(r.id), {
          rowLabel: (r) => `${categoryNameMap[r.productCategoryId] ?? r.productCategoryId} - ${r.name}`,
        });
        fetchTemplates();
      },
    },
  ];

  return (
    <Card
      title="选配模板管理"
      extra={<Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建模板</Button>}
    >
      <SelectableTable<TemplateRow>
        rowKey="id"
        columns={columns}
        dataSource={templates}
        loading={loading}
        pagination={false}
        actions={actions}
        rowLabel={(r) => `${categoryNameMap[r.productCategoryId] ?? r.productCategoryId} - ${r.name}`}
        locale={{
          emptyText: (
            <div style={{ padding: '40px 0', color: '#909399' }}>
              暂无选配模板，点击右上「+ 新建模板」创建
            </div>
          ),
        }}
      />

      <Drawer
        title={editing ? '编辑选配模板' : '新建选配模板'}
        open={drawerOpen}
        onClose={closeDrawer}
        width={720}
        destroyOnClose
        footer={
          <div style={{ display: 'flex', gap: 8 }}>
            <Button style={{ flex: 1 }} onClick={closeDrawer}>取消</Button>
            <Button type="primary" style={{ flex: 2 }} onClick={() => form.submit()}>保存</Button>
          </div>
        }
      >
        <Form form={form} layout="vertical" onFinish={handleSave} onFinishFailed={handleSaveFailed}>
          <div style={sectionTitleStyle(true)}>基本信息</div>
          <Form.Item
            name="productCategoryId"
            label="产品分类"
            rules={[{ required: true, message: '请选择产品分类' }]}
            extra="一个产品分类仅可配置一套选配模板；产品分类确定后不可修改"
          >
            <Select
              options={editing ? categoryOptions : createCategoryOptions}
              disabled={!!editing}
              showSearch
              optionFilterProp="label"
              placeholder="请选择产品分类"
              notFoundContent="所有产品分类均已配置模板"
            />
          </Form.Item>
          <Form.Item name="name" label="模板名"
            rules={[{ required: true, message: '请输入模板名' }]}>
            <Input maxLength={100} placeholder="请输入模板名" />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Segmented options={STATUS_OPTIONS} />
          </Form.Item>

          <div style={sectionTitleStyle()}>选配参数</div>
          {sortedParamTypes.map((pt) => {
            const state = itemsState[pt.code] ?? { enabled: false, allowedValues: [] };
            const isAdjust = pt.valueMode === 'adjust';
            return (
              <div
                key={pt.code}
                style={{
                  border: `1px solid ${state.enabled ? '#91caff' : '#e4e7ed'}`,
                  background: state.enabled ? '#f7fbff' : '#fff',
                  borderRadius: 6,
                  padding: '12px 14px',
                  marginBottom: 10,
                  transition: 'all .15s',
                }}
              >
                <Checkbox
                  checked={state.enabled}
                  onChange={(e) => {
                    const checked = e.target.checked;
                    setItemsState((prev) => ({
                      ...prev,
                      [pt.code]: {
                        enabled: checked,
                        // 取消勾选时清空已限定值（元素含量无 allowedValues 概念，不受影响）
                        allowedValues: checked || isAdjust ? state.allowedValues : [],
                      },
                    }));
                  }}
                >
                  <span>{pt.name}</span>
                  <span style={{ fontSize: 12, color: '#909399', fontWeight: 400, marginLeft: 4 }}>
                    （{VALUE_MODE_LABEL[pt.valueMode] ?? pt.valueMode} · {pt.valueMode}）
                  </span>
                </Checkbox>

                <div style={{ marginLeft: 24 }}>
                  <div style={{ marginTop: 6, marginBottom: isAdjust ? 0 : 8, fontSize: 12.5, color: '#909399', lineHeight: 1.6 }}>
                    {paramDescFor(pt)}
                  </div>
                  {!isAdjust && (
                    <Select
                      mode="multiple"
                      allowClear
                      style={{ width: '100%' }}
                      placeholder="不限（留空 = 不限定可选值）"
                      disabled={!state.enabled}
                      loading={!!candidatesLoading[pt.code]}
                      value={state.allowedValues}
                      onDropdownVisibleChange={(open) => { if (open) ensureCandidatesFor(pt.code); }}
                      options={(candidatesCache[pt.code] ?? []).map((c) => ({ value: c.key, label: c.label }))}
                      onChange={(vals) => setItemsState((prev) => ({ ...prev, [pt.code]: { ...state, allowedValues: vals as string[] } }))}
                    />
                  )}
                </div>
              </div>
            );
          })}
        </Form>
      </Drawer>
    </Card>
  );
};

export default SelTemplateManagement;
