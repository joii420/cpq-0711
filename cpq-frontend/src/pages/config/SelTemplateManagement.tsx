import React, { useEffect, useMemo, useState } from 'react';
import { Drawer, Form, Input, Select, Button, message, Tag, Checkbox } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';
import { selTemplateService } from '../../services/selTemplateService';
import { industryService } from '../../services/industryService';

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
  industryCode: string;
  name: string;
  status: string;
  version?: number;
  items: TemplateItemDTO[];
}

interface IndustryOption {
  id: string;
  code: string;
  name: string;
  status: string;
}

interface ItemState {
  enabled: boolean;
  allowedValues: string[];
}

const STATUS_OPTIONS = [
  { value: 'ACTIVE', label: '启用' },
  { value: 'INACTIVE', label: '停用' },
];

// 保留行业码：不在 industry 表里，靠约定码区分（选配运行时/Plan 3 会读取）
const RESERVED_INDUSTRIES = [
  { code: '__DEFAULT__', name: '默认模板' },
  { code: '__GLOBAL__', name: '通用组合工艺' },
];

const SelTemplateManagement: React.FC = () => {
  const [templates, setTemplates] = useState<TemplateRow[]>([]);
  const [paramTypes, setParamTypes] = useState<ParamTypeRow[]>([]);
  const [industries, setIndustries] = useState<IndustryOption[]>([]);
  const [loading, setLoading] = useState(false);

  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<TemplateRow | null>(null);
  const [itemsState, setItemsState] = useState<Record<string, ItemState>>({});
  const [form] = Form.useForm();

  const [candidatesCache, setCandidatesCache] = useState<Record<string, CandidateOption[]>>({});
  const [candidatesLoading, setCandidatesLoading] = useState<Record<string, boolean>>({});

  const industryNameMap = useMemo(() => {
    const map: Record<string, string> = {};
    industries.forEach((i) => { map[i.code] = i.name; });
    RESERVED_INDUSTRIES.forEach((r) => { map[r.code] = r.name; });
    return map;
  }, [industries]);

  const industryOptions = useMemo(() => ([
    ...industries.map((i) => ({ value: i.code, label: i.name })),
    ...RESERVED_INDUSTRIES.map((r) => ({ value: r.code, label: `${r.name} (${r.code})` })),
  ]), [industries]);

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

  const fetchIndustries = async () => {
    try {
      const res = await industryService.listActive();
      setIndustries(res?.data ?? []);
    } catch (err: any) {
      message.error(err.message);
    }
  };

  useEffect(() => {
    fetchTemplates();
    fetchParamTypes();
    fetchIndustries();
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
      form.setFieldsValue({ industryCode: detail.industryCode, name: detail.name, status: detail.status });
      setDrawerOpen(true);
    } catch (err: any) {
      message.error(err.message);
    }
  };

  const handleSave = async (values: any) => {
    try {
      const items = sortedParamTypes.map((pt) => ({
        paramTypeCode: pt.code,
        enabled: itemsState[pt.code]?.enabled ?? false,
        allowedValues: pt.valueMode === 'adjust' ? [] : (itemsState[pt.code]?.allowedValues ?? []),
      }));
      await selTemplateService.upsert({
        industryCode: values.industryCode,
        name: values.name,
        status: values.status,
        items,
      });
      message.success(editing ? '更新成功' : '创建成功');
      setDrawerOpen(false);
      setEditing(null);
      fetchTemplates();
    } catch (err: any) {
      message.error(err.message);
    }
  };

  // 列定义 —— 行业列作为主入口（点击打开编辑 Drawer），行内不放动作按钮
  const columns = [
    {
      title: '行业', dataIndex: 'industryCode', key: 'industryCode',
      render: (code: string, record: TemplateRow) => (
        <a onClick={(e) => { e.stopPropagation(); openEdit(record); }} style={{ fontWeight: 500 }}>
          {industryNameMap[code] ?? code}
        </a>
      ),
    },
    { title: '模板名', dataIndex: 'name', key: 'name' },
    {
      title: '启用参数数', key: 'enabledCount',
      render: (_: any, record: TemplateRow) => record.items.filter((i) => i.enabled).length,
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
      key: 'delete',
      label: '删除',
      icon: <DeleteOutlined />,
      danger: true,
      enabledWhen: (sel) => sel.length > 0,
      needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 个选配模板？',
      confirmDescription: '⚠️ 删除后该行业将不再有专属选配模板，选配运行时会回退到默认模板（如有）。',
      onClick: async (sel) => {
        await runBatch(sel, (r) => selTemplateService.delete(r.id), {
          rowLabel: (r) => `${industryNameMap[r.industryCode] ?? r.industryCode} - ${r.name}`,
        });
        fetchTemplates();
      },
    },
  ];

  const toolbar = (
    <>
      <div />
      <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建模板</Button>
    </>
  );

  return (
    <div>
      <SelectableTable<TemplateRow>
        rowKey="id"
        columns={columns}
        dataSource={templates}
        loading={loading}
        toolbar={toolbar}
        actions={actions}
        rowLabel={(r) => `${industryNameMap[r.industryCode] ?? r.industryCode} - ${r.name}`}
      />

      <Drawer
        title={editing ? '编辑选配模板' : '新建选配模板'}
        open={drawerOpen}
        onClose={() => { setDrawerOpen(false); setEditing(null); }}
        width={720}
        destroyOnClose
      >
        <Form form={form} layout="vertical" onFinish={handleSave}>
          <Form.Item name="industryCode" label="行业"
            rules={[{ required: true, message: '请选择行业' }]}>
            <Select
              options={industryOptions}
              disabled={!!editing}
              showSearch
              optionFilterProp="label"
              placeholder="请选择行业（一行业一套模板）"
            />
          </Form.Item>
          <Form.Item name="name" label="模板名"
            rules={[{ required: true, message: '请输入模板名' }]}>
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select options={STATUS_OPTIONS} />
          </Form.Item>

          <div style={{ marginBottom: 8, fontWeight: 500 }}>选配参数</div>
          {sortedParamTypes.map((pt) => {
            const state = itemsState[pt.code] ?? { enabled: false, allowedValues: [] };
            const isAdjust = pt.valueMode === 'adjust';
            return (
              <div key={pt.code} style={{ border: '1px solid #f0f0f0', borderRadius: 6, padding: 12, marginBottom: 12 }}>
                <Checkbox
                  checked={state.enabled}
                  onChange={(e) => setItemsState((prev) => ({ ...prev, [pt.code]: { ...state, enabled: e.target.checked } }))}
                >
                  {pt.name}
                </Checkbox>
                {isAdjust ? (
                  <div style={{ marginTop: 8, color: '#8c8c8c', fontSize: 12 }}>
                    启用后允许在派生元素含量上微调
                  </div>
                ) : (
                  <Select
                    mode="multiple"
                    allowClear
                    style={{ width: '100%', marginTop: 8 }}
                    placeholder="不限（留空 = 不限制可选值）"
                    disabled={!state.enabled}
                    loading={!!candidatesLoading[pt.code]}
                    value={state.allowedValues}
                    onDropdownVisibleChange={(open) => { if (open) ensureCandidatesFor(pt.code); }}
                    options={(candidatesCache[pt.code] ?? []).map((c) => ({ value: c.key, label: c.label }))}
                    onChange={(vals) => setItemsState((prev) => ({ ...prev, [pt.code]: { ...state, allowedValues: vals as string[] } }))}
                  />
                )}
              </div>
            );
          })}

          <div style={{ marginTop: 24 }}>
            <Button type="primary" htmlType="submit" block>保存</Button>
          </div>
        </Form>
      </Drawer>
    </div>
  );
};

export default SelTemplateManagement;
