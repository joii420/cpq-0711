import React, { useEffect, useMemo, useState } from 'react';
import { Button, Modal, Form, Input, InputNumber, Tag, message, Select } from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { comparisonTagService } from '../../services/comparisonTagService';
import type { ComparisonTag } from '../../services/comparisonTagService';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';

const ComparisonTagManagement: React.FC = () => {
  const [list, setList] = useState<ComparisonTag[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<ComparisonTag | null>(null);
  const [form] = Form.useForm();

  const fetchData = async () => {
    setLoading(true);
    try {
      const res = await comparisonTagService.list();
      setList(res.data || []);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchData(); }, []);

  const groupOptions = useMemo(() => {
    const set = new Set<string>();
    list.forEach((t) => set.add(t.groupName));
    return Array.from(set).map((g) => ({ label: g, value: g }));
  }, [list]);

  const openCreate = () => {
    setEditing(null);
    form.resetFields();
    form.setFieldsValue({ status: 'ACTIVE', groupSortOrder: 0, tagSortOrder: 0 });
    setModalOpen(true);
  };

  const openEdit = (record: ComparisonTag) => {
    setEditing(record);
    form.setFieldsValue(record);
    setModalOpen(true);
  };

  const handleSave = async (values: any) => {
    try {
      if (editing) {
        await comparisonTagService.update(editing.id, values);
        message.success('更新成功');
      } else {
        await comparisonTagService.create(values);
        message.success('创建成功');
      }
      setModalOpen(false);
      form.resetFields();
      setEditing(null);
      fetchData();
    } catch (err: any) {
      message.error(err.message);
    }
  };

  const columns = [
    { title: '分组', dataIndex: 'groupName', key: 'groupName', width: 160 },
    {
      title: '编码', dataIndex: 'code', key: 'code', width: 220,
      render: (v: string, r: ComparisonTag) => (
        <a onClick={(e) => { e.stopPropagation(); openEdit(r); }}>{v}</a>
      ),
    },
    { title: '标签', dataIndex: 'label', key: 'label' },
    {
      title: '类型', dataIndex: 'isBuiltin', key: 'isBuiltin', width: 100,
      render: (b: boolean) => <Tag color={b ? 'blue' : 'default'}>{b ? '内置' : '自定义'}</Tag>,
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 100,
      render: (s: string) => <Tag color={s === 'ACTIVE' ? 'green' : 'default'}>{s === 'ACTIVE' ? '启用' : '停用'}</Tag>,
    },
  ];

  const actions: ToolbarAction<ComparisonTag>[] = [
    {
      key: 'edit', label: '编辑', icon: <EditOutlined />,
      enabledWhen: (rows) => rows.length === 1 ? true : '编辑一次只能选一行',
      onClick: (rows) => openEdit(rows[0]),
    },
    {
      key: 'delete', label: '删除', icon: <DeleteOutlined />, danger: true,
      enabledWhen: (rows) => {
        if (rows.length === 0) return false;
        if (rows.some((r) => r.isBuiltin)) return '内置标签不可删除';
        return true;
      },
      needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 个标签？',
      onClick: async (rows) => {
        await runBatch(rows, (r) => comparisonTagService.delete(r.id).then(() => undefined), {
          rowLabel: (r) => `${r.code} ${r.label}`,
          successMsg: `已删除 ${rows.length} 项`,
        });
        fetchData();
      },
    },
  ];

  const toolbar = (
    <>
      <h2 style={{ margin: 0 }}>业务标签字典</h2>
      <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增标签</Button>
    </>
  );

  return (
    <div>
      <SelectableTable<ComparisonTag>
        rowKey="id"
        columns={columns}
        dataSource={list}
        loading={loading}
        pagination={{ pageSize: 50 }}
        toolbar={toolbar}
        actions={actions}
        rowLabel={(r) => `${r.code} ${r.label}${r.isBuiltin ? '（内置）' : ''}`}
        getCheckboxProps={(r) => ({ disabled: r.isBuiltin })}
      />
      <Modal
        title={editing ? '编辑标签' : '新增标签'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        destroyOnClose
      >
        <Form form={form} layout="vertical" onFinish={handleSave}>
          <Form.Item name="code" label="编码" rules={[{ required: true, message: '请输入编码' }]}>
            <Input disabled={!!editing && editing.isBuiltin} placeholder="如 MATERIAL_COST_AG" />
          </Form.Item>
          <Form.Item name="label" label="标签名称" rules={[{ required: true, message: '请输入标签名称' }]}>
            <Input placeholder="如 Ag材料成本" />
          </Form.Item>
          <Form.Item name="groupName" label="分组" rules={[{ required: true, message: '请选择或输入分组' }]}>
            <Select options={groupOptions} mode="tags" maxCount={1} placeholder="选择已有分组或输入新分组" />
          </Form.Item>
          <Form.Item name="groupSortOrder" label="分组排序">
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="tagSortOrder" label="组内排序">
            <InputNumber style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select options={[{ label: '启用', value: 'ACTIVE' }, { label: '停用', value: 'DISABLED' }]} />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default ComparisonTagManagement;
