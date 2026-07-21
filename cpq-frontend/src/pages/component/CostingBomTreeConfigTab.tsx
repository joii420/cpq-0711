import React, { useEffect, useState } from 'react';
import { Alert, Button, Drawer, Form, Input, Segmented, Select, Space, Tag, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import {
  costingBomTreeConfigService,
  type BomTreeConfigUsage,
  type CostingBomTreeConfig,
} from '../../services/costingBomTreeConfigService';
import SelectableTable, { type ToolbarAction } from '../../components/SelectableTable';

const { TextArea } = Input;

const USAGE_LABEL: Record<BomTreeConfigUsage, string> = {
  QUOTE: '报价侧',
  COSTING: '核价侧',
};

const CostingBomTreeConfigTab: React.FC = () => {
  // task-0721 F6：usage 维度切换 —— 报价侧 / 核价侧配置分列管理，各自独立 active。
  const [usage, setUsage] = useState<BomTreeConfigUsage>('COSTING');
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
      message.success(`已设为生效（仅影响${USAGE_LABEL[usage]}，不影响另一侧配置）`);
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
      confirmDescription: '删除后不可恢复，若删除的是生效中的配置将导致对应侧（报价/核价）BOM 树无法渲染。',
      onClick: (rows) => handleDelete(rows),
    },
  ];

  const toolbar = (
    <>
      <h3 style={{ margin: 0 }}>{USAGE_LABEL[usage]}树配置</h3>
      {/* task-0721 F6：usage 维度切换 —— 报价侧 / 核价侧各自独立管理 + 独立 active */}
      <Segmented
        value={usage}
        onChange={(v) => setUsage(v as BomTreeConfigUsage)}
        options={[
          { label: '报价侧', value: 'QUOTE' },
          { label: '核价侧', value: 'COSTING' },
        ]}
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
        description="报价侧与核价侧各自独立维护、独立生效（active），互不影响：切换上方开关只改变本页面查看/操作的范围，激活某一侧的配置不会下线另一侧现役配置。"
      />
      <SelectableTable<CostingBomTreeConfig>
        rowKey="id"
        columns={columns}
        dataSource={list}
        loading={loading}
        pagination={{ pageSize: 50 }}
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
          每个用途（报价侧/核价侧）各自至多一条「生效中」的递归 SQL：输入参数 <code>:production_part_nos</code>（text[]），
          输出必须包含 5 列 <code>root_no / material_no / bom_version / parent_no / node_path</code>。
          保存时后端会对递归 SQL 做 dry-run 校验，失败会返回具体错误原因。
        </div>
        <Form form={form} layout="vertical">
          <Form.Item
            name="usage"
            label="用途"
            rules={[{ required: true, message: '请选择用途' }]}
            tooltip="创建/编辑时必须指定用途；激活仅影响当前用途，不影响另一侧配置"
          >
            <Select
              options={[
                { value: 'QUOTE', label: '报价侧' },
                { value: 'COSTING', label: '核价侧' },
              ]}
            />
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
