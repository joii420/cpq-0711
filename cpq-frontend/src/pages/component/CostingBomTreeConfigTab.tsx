import React, { useEffect, useState } from 'react';
import { Button, Drawer, Form, Input, Space, Tag, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import {
  costingBomTreeConfigService,
  type CostingBomTreeConfig,
} from '../../services/costingBomTreeConfigService';
import SelectableTable, { type ToolbarAction } from '../../components/SelectableTable';

const { TextArea } = Input;

const CostingBomTreeConfigTab: React.FC = () => {
  const [list, setList] = useState<CostingBomTreeConfig[]>([]);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<CostingBomTreeConfig | null>(null);
  const [saving, setSaving] = useState(false);
  const [form] = Form.useForm();

  const fetchData = async () => {
    setLoading(true);
    try {
      const res = await costingBomTreeConfigService.list();
      setList(res.data || []);
    } catch (err: any) {
      message.error(err?.message ?? '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
  }, []);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
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
      fetchData();
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
      message.success('已设为生效');
      fetchData();
    } catch (err: any) {
      message.error(err?.message ?? '设为生效失败');
    }
  };

  const handleDelete = async (rows: CostingBomTreeConfig[]) => {
    try {
      await Promise.all(rows.map((r) => costingBomTreeConfigService.remove(r.id)));
      message.success(`已删除 ${rows.length} 项`);
      fetchData();
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
      confirmTitle: '确认删除选中的 {N} 条核价树配置？',
      confirmDescription: '删除后不可恢复，若删除的是生效中的配置将导致核价树无法渲染。',
      onClick: (rows) => handleDelete(rows),
    },
  ];

  const toolbar = (
    <>
      <h3 style={{ margin: 0 }}>核价树配置</h3>
      <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
        新增
      </Button>
    </>
  );

  return (
    <div>
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
        title={editing ? '编辑核价树配置' : '新增核价树配置'}
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
          全局唯一一条「生效中」的核价树递归 SQL：输入参数 <code>:production_part_nos</code>（text[]），
          输出必须包含 5 列 <code>root_no / material_no / bom_version / parent_no / node_path</code>。
          保存时后端会对递归 SQL 做 dry-run 校验，失败会返回具体错误原因。
        </div>
        <Form form={form} layout="vertical">
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
