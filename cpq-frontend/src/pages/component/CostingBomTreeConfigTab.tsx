import React, { useEffect, useState } from 'react';
import { Alert, Button, Drawer, Empty, Form, Input, Segmented, Select, Space, Tag, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import {
  costingBomTreeConfigService,
  type BomTreeConfigUsage,
  type CostingBomTreeConfig,
} from '../../services/costingBomTreeConfigService';
import SelectableTable, { type ToolbarAction } from '../../components/SelectableTable';

const { TextArea } = Input;

/**
 * task-260909 F-1：三档标签。
 * 🔑 用词与全站逐字一致 —— 取自 `src/pages/master-data/dataset/datasetConfig.ts` 的
 *    `DATASETS['cost-basic'].label = '基础核价'` / `DATASETS['cost-detail'].label = '详细核价'`。
 *    🚫 不要自创「明细核价」等变体。
 */
const USAGE_LABEL: Record<BomTreeConfigUsage, string> = {
  QUOTE: '报价',
  COST_BASIC: '基础核价',
  COST_DETAIL: '详细核价',
};

/** 切换控件的选项顺序 = 原型图 `01-核价树配置-基础核价.html` 的顺序，勿调换。 */
const USAGE_OPTIONS: { label: string; value: BomTreeConfigUsage }[] = [
  { label: USAGE_LABEL.QUOTE, value: 'QUOTE' },
  { label: USAGE_LABEL.COST_BASIC, value: 'COST_BASIC' },
  { label: USAGE_LABEL.COST_DETAIL, value: 'COST_DETAIL' },
];

/** 三套口径的说明文案（Alert description + 抽屉提示共用同一口径，避免两处漂移）。 */
const USAGE_SCOPE_DESC =
  '报价、基础核价、详细核价三套各自独立维护、独立生效（active），互不影响：' +
  '切换上方开关只改变本页面查看/操作的范围，激活某一套的配置不会下线另外两套的现役配置。';

const CostingBomTreeConfigTab: React.FC = () => {
  // task-0721 F6 / task-260909 F-2：usage 维度切换 —— 三套配置分列管理，各自独立 active。
  // 默认落「基础核价」（AC-1）。
  const [usage, setUsage] = useState<BomTreeConfigUsage>('COST_BASIC');
  const [list, setList] = useState<CostingBomTreeConfig[]>([]);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<CostingBomTreeConfig | null>(null);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  const fetchData = async (u: BomTreeConfigUsage) => {
    setLoading(true);
    try {
      const res = await costingBomTreeConfigService.list(u);
      setList(res.data || []);
    } catch (err: any) {
      message.error(err?.message ?? '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData(usage);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [usage]);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ usage });
    setDrawerOpen(true);
  };

  const openEdit = (record: CostingBomTreeConfig) => {
    setEditing(record);
    form.setFieldsValue(record);
    setDrawerOpen(true);
  };

  const closeDrawer = () => {
    setDrawerOpen(false);
    setEditing(null);
    form.resetFields();
  };

  const handleSave = async () => {
    try {
      const values = await form.validateFields();
      setSaving(true);
      if (editing) {
        await costingBomTreeConfigService.update(editing.id, values);
        message.success('更新成功');
      } else {
        await costingBomTreeConfigService.create(values);
        message.success('创建成功');
      }
      closeDrawer();
      fetchData(usage);
    } catch (err: any) {
      // 表单校验失败（antd 抛出的 errorFields 对象）不提示后端消息
      if (err?.errorFields) return;
      message.error(err?.message ?? '保存失败');
    } finally {
      setSaving(false);
    }
  };

  const handleActivate = async (record: CostingBomTreeConfig) => {
    try {
      await costingBomTreeConfigService.activate(record.id);
      message.success(`已设为生效（仅影响${USAGE_LABEL[usage]}，不影响另外两套配置）`);
      fetchData(usage);
    } catch (err: any) {
      message.error(err?.message ?? '设为生效失败');
    }
  };

  const handleDelete = async (rows: CostingBomTreeConfig[]) => {
    try {
      await Promise.all(rows.map((r) => costingBomTreeConfigService.remove(r.id)));
      message.success(`已删除 ${rows.length} 项`);
      fetchData(usage);
    } catch (err: any) {
      message.error(err?.message ?? '删除失败');
    }
  };

  const columns = [
    {
      title: '名称',
      dataIndex: 'name',
      key: 'name',
      render: (v: string, r: CostingBomTreeConfig) => (
        <a onClick={(e) => { e.stopPropagation(); openEdit(r); }}>{v}</a>
      ),
    },
    {
      title: '状态',
      dataIndex: 'isActive',
      key: 'isActive',
      width: 120,
      render: (active: boolean) => (
        <Tag color={active ? 'green' : 'default'}>{active ? '生效中' : '未生效'}</Tag>
      ),
    },
    {
      title: '更新时间',
      dataIndex: 'updatedAt',
      key: 'updatedAt',
      width: 200,
    },
  ];

  const actions: ToolbarAction<CostingBomTreeConfig>[] = [
    {
      key: 'edit',
      label: '编辑',
      enabledWhen: (rows) => (rows.length === 1 ? true : '请选择一条'),
      onClick: (rows) => openEdit(rows[0]),
    },
    {
      key: 'activate',
      label: '设为生效',
      enabledWhen: (rows) => (rows.length === 1 ? true : '请选择一条'),
      onClick: (rows) => handleActivate(rows[0]),
    },
    {
      key: 'delete',
      label: '删除',
      danger: true,
      enabledWhen: (rows) => (rows.length >= 1 ? true : '请选择'),
      needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 条递归 SQL 配置？',
      confirmDescription: '删除后不可恢复，若删除的是生效中的配置将导致对应数据集（报价/基础核价/详细核价）BOM 树无法渲染。',
      onClick: (rows) => handleDelete(rows),
    },
  ];

  const toolbar = (
    <>
      <h3 style={{ margin: 0 }}>{USAGE_LABEL[usage]}树配置</h3>
      {/* task-260909 F-2：usage 维度切换 —— 报价 / 基础核价 / 详细核价各自独立管理 + 独立 active */}
      <Segmented
        value={usage}
        onChange={(v) => setUsage(v as BomTreeConfigUsage)}
        options={USAGE_OPTIONS}
      />
      <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
        新增
      </Button>
    </>
  );

  return (
    <div>
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 12 }}
        message={`当前管理「${USAGE_LABEL[usage]}」的递归 SQL 配置`}
        description={USAGE_SCOPE_DESC}
      />
      <SelectableTable<CostingBomTreeConfig>
        rowKey="id"
        columns={columns}
        dataSource={list}
        loading={loading}
        pagination={{ pageSize: 50 }}
        locale={{
          // task-260909 F-3（AC-2）：空态文案按当前数据集动态生成，如「暂无详细核价树配置」。
          // 🚫 空态下「新增」按钮保持可点 —— 它在 toolbar 里，不受 SelectableTable 的选中态影响。
          emptyText: (
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={`暂无${USAGE_LABEL[usage]}树配置`}
            />
          ),
        }}
        toolbar={toolbar}
        actions={actions}
        rowLabel={(r) => `${r.name}${r.isActive ? '（生效中）' : ''}`}
      />

      <Drawer
        title={editing ? '编辑递归 SQL 配置' : `新增${USAGE_LABEL[usage]}递归 SQL 配置`}
        placement="right"
        width={960}
        open={drawerOpen}
        onClose={closeDrawer}
        destroyOnClose
        extra={
          <Space>
            <Button onClick={closeDrawer}>取消</Button>
            <Button type="primary" loading={saving} onClick={handleSave}>
              保存
            </Button>
          </Space>
        }
      >
        <div
          style={{
            marginBottom: 16,
            padding: '10px 12px',
            background: '#fffbe6',
            border: '1px solid #ffe58f',
            borderRadius: 6,
            fontSize: 12,
            color: '#874d00',
            lineHeight: 1.7,
          }}
        >
          每个用途（报价 / 基础核价 / 详细核价）各自至多一条「生效中」的递归 SQL：输入参数 <code>:production_part_nos</code>（text[]），
          输出必须包含 5 列 <code>root_no / material_no / bom_version / parent_no / node_path</code>。
          保存时后端会对递归 SQL 做 dry-run 校验，失败会返回具体错误原因。
        </div>
        <Form form={form} layout="vertical">
          <Form.Item
            name="usage"
            label="用途"
            rules={[{ required: true, message: '请选择用途' }]}
            tooltip="创建/编辑时必须指定用途；激活仅影响当前用途，不影响另外两套配置"
          >
            <Select options={USAGE_OPTIONS} />
          </Form.Item>
          <Form.Item
            name="name"
            label="配置名称"
            rules={[{ required: true, message: '请输入配置名称' }]}
          >
            <Input placeholder="如 标准核价树 v1" />
          </Form.Item>
          <Form.Item
            name="sqlTemplate"
            label="递归 SQL"
            rules={[{ required: true, message: '请输入递归 SQL' }]}
          >
            <TextArea
              rows={20}
              style={{ fontFamily: 'monospace', fontSize: 13 }}
              placeholder={'WITH RECURSIVE tree AS (\n  ...\n)\nSELECT root_no, material_no, bom_version, parent_no, node_path FROM tree'}
            />
          </Form.Item>
        </Form>
      </Drawer>
    </div>
  );
};

export default CostingBomTreeConfigTab;
