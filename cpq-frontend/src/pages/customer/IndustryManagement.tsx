import React, { useEffect, useState } from 'react';
import { Drawer, Form, Input, Select, Button, message, Tag } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';
import { industryService } from '../../services/industryService';

interface IndustryRow {
  id: string;
  code: string;
  name: string;
  status: string;
}

const STATUS_OPTIONS = [
  { value: 'ACTIVE', label: '启用' },
  { value: 'INACTIVE', label: '停用' },
];

const IndustryManagement: React.FC = () => {
  const [rows, setRows] = useState<IndustryRow[]>([]);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState<IndustryRow | null>(null);
  const [form] = Form.useForm();

  const fetchData = async () => {
    setLoading(true);
    try {
      const res = await industryService.list({ page: 0, size: 200 });
      setRows(res?.data?.content ?? []);
    } catch (err: any) {
      message.error(err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchData(); }, []);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ status: 'ACTIVE' });
    setDrawerOpen(true);
  };

  const openEdit = (row: IndustryRow) => {
    setEditing(row);
    form.setFieldsValue(row);
    setDrawerOpen(true);
  };

  const handleSave = async (values: any) => {
    try {
      if (editing) {
        await industryService.update(editing.id, values);
        message.success('更新成功');
      } else {
        await industryService.create(values);
        message.success('创建成功');
      }
      setDrawerOpen(false);
      setEditing(null);
      fetchData();
    } catch (err: any) {
      message.error(err.message);
    }
  };

  // 列定义 —— 行业编码作为主入口（点击打开编辑 Drawer），行内不放动作按钮
  const columns = [
    {
      title: '行业编码', dataIndex: 'code', key: 'code',
      render: (code: string, record: IndustryRow) => (
        <a onClick={(e) => { e.stopPropagation(); openEdit(record); }} style={{ fontWeight: 500 }}>{code}</a>
      ),
    },
    { title: '行业名称', dataIndex: 'name', key: 'name' },
    {
      title: '状态', dataIndex: 'status', key: 'status',
      render: (s: string) => <Tag color={s === 'ACTIVE' ? 'green' : 'default'}>{STATUS_OPTIONS.find(o => o.value === s)?.label ?? s}</Tag>,
    },
  ];

  // 工具栏动作 —— 变更类动作统一上提到顶部，选择驱动启用/禁用
  const actions: ToolbarAction<IndustryRow>[] = [
    {
      key: 'edit',
      label: '编辑',
      icon: <EditOutlined />,
      enabledWhen: (sel) => sel.length === 1 ? true : '编辑一次只能选一行',
      onClick: (sel) => openEdit(sel[0]),
    },
    {
      key: 'delete',
      label: '删除',
      icon: <DeleteOutlined />,
      danger: true,
      enabledWhen: (sel) => sel.length > 0,
      needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 个行业？',
      confirmDescription: '⚠️ 已被客户引用的行业将删除失败（引用完整性保护）。',
      onClick: async (sel) => {
        // runBatch 已在内部按结果分支弹 toast(全成功→message.success；有失败→message.error 列出明细)，此处不再重复弹
        await runBatch(sel, (r) => industryService.delete(r.id), {
          rowLabel: (r) => `${r.code} ${r.name}`,
        });
        fetchData();
      },
    },
  ];

  const toolbar = (
    <>
      <div />
      <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建行业</Button>
    </>
  );

  return (
    <div>
      <SelectableTable<IndustryRow>
        rowKey="id"
        columns={columns}
        dataSource={rows}
        loading={loading}
        toolbar={toolbar}
        actions={actions}
        rowLabel={(r) => `${r.code} ${r.name}`}
      />

      <Drawer
        title={editing ? '编辑行业' : '新建行业'}
        open={drawerOpen}
        onClose={() => { setDrawerOpen(false); setEditing(null); }}
        width={480}
        destroyOnClose
      >
        <Form form={form} layout="vertical" onFinish={handleSave}>
          <Form.Item name="code" label="行业编码"
            rules={[{ required: true, message: '请输入行业编码' }]}>
            <Input maxLength={50} disabled={!!editing} />
          </Form.Item>
          <Form.Item name="name" label="行业名称"
            rules={[{ required: true, message: '请输入行业名称' }]}>
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select options={STATUS_OPTIONS} />
          </Form.Item>
          <div style={{ marginTop: 24 }}>
            <Button type="primary" htmlType="submit" block>保存</Button>
          </div>
        </Form>
      </Drawer>
    </div>
  );
};

export default IndustryManagement;
