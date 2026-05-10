import React, { useEffect, useState } from 'react';
import { Table, Button, Modal, Form, Input, InputNumber, Space, Tag, Popconfirm, message } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import { regionService } from '../../services/regionService';

const RegionManagement: React.FC = () => {
  const [data, setData] = useState<any>({ content: [], totalElements: 0 });
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<any>(null);
  const [form] = Form.useForm();

  const fetchData = async () => {
    setLoading(true);
    try {
      const res = await regionService.list({ size: 100 });
      setData(res.data);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchData(); }, []);

  const handleSave = async (values: any) => {
    try {
      if (editing) {
        await regionService.update(editing.id, values);
        message.success('更新成功');
      } else {
        await regionService.create(values);
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
    { title: '编码', dataIndex: 'code', key: 'code' },
    { title: '名称', dataIndex: 'name', key: 'name' },
    { title: '排序', dataIndex: 'sortOrder', key: 'sortOrder' },
    {
      title: '状态', dataIndex: 'status', key: 'status',
      render: (s: string) => <Tag color={s === 'ACTIVE' ? 'green' : 'default'}>{s === 'ACTIVE' ? '启用' : '停用'}</Tag>,
    },
    {
      title: '操作', key: 'actions',
      render: (_: any, record: any) => (
        <Space>
          <a onClick={() => { setEditing(record); form.setFieldsValue(record); setModalOpen(true); }}>编辑</a>
          {record.status === 'ACTIVE' ? (
            <Popconfirm title="确认停用？" onConfirm={async () => {
              try { await regionService.updateStatus(record.id, 'DISABLED'); message.success('已停用'); fetchData(); }
              catch (e: any) { message.error(e.message); }
            }}>
              <a style={{ color: 'red' }}>停用</a>
            </Popconfirm>
          ) : (
            <a onClick={async () => { await regionService.updateStatus(record.id, 'ACTIVE'); fetchData(); }}>启用</a>
          )}
        </Space>
      ),
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'flex-end' }}>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); form.resetFields(); setModalOpen(true); }}>新增区域</Button>
      </div>
      <Table columns={columns} dataSource={data.content} rowKey="id" loading={loading} pagination={false} />
      <Modal title={editing ? '编辑区域' : '新增区域'} open={modalOpen} onCancel={() => setModalOpen(false)} onOk={() => form.submit()} destroyOnClose>
        <Form form={form} layout="vertical" onFinish={handleSave}>
          <Form.Item name="code" label="编码" rules={[{ required: !editing, message: '请输入编码' }]}><Input disabled={!!editing} /></Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}><Input /></Form.Item>
          <Form.Item name="sortOrder" label="排序"><InputNumber style={{ width: '100%' }} /></Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default RegionManagement;
