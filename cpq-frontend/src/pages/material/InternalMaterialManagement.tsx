import React, { useEffect, useState } from 'react';
import {
  Button, Drawer, Form, Input, Select, Space, Tag, message, Card, Upload, Modal,
} from 'antd';
import { PlusOutlined, UploadOutlined, DeleteOutlined, EditOutlined } from '@ant-design/icons';
import { internalMaterialService } from '../../services/internalMaterialService';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';

const { Search } = Input;

const statusMap: Record<string, { label: string; color: string }> = {
  Y: { label: '可生产', color: 'green' },
  N: { label: '停产', color: 'red' },
};

const InternalMaterialManagement: React.FC = () => {
  const [data, setData] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingRecord, setEditingRecord] = useState<any>(null);
  const [form] = Form.useForm();
  const [params, setParams] = useState({ page: 0, size: 20, keyword: '', statusCode: '' });
  const [importModalOpen, setImportModalOpen] = useState(false);
  const [importFile, setImportFile] = useState<File | null>(null);
  const [importLoading, setImportLoading] = useState(false);

  const fetchData = async () => {
    setLoading(true);
    try {
      const res = await internalMaterialService.list({
        page: params.page,
        size: params.size,
        keyword: params.keyword || undefined,
        statusCode: params.statusCode || undefined,
      });
      setData(res.data?.content || res.data || []);
      setTotal(res.data?.totalElements || 0);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchData(); }, [params]);

  const openDrawer = (record?: any) => {
    setEditingRecord(record || null);
    setDrawerOpen(true);
    if (record) {
      setTimeout(() => form.setFieldsValue(record), 50);
    } else {
      setTimeout(() => form.resetFields(), 50);
    }
  };

  const handleSave = async (values: any) => {
    try {
      if (editingRecord) {
        await internalMaterialService.update(editingRecord.id, values);
        message.success('更新成功');
      } else {
        await internalMaterialService.create(values);
        message.success('创建成功');
      }
      setDrawerOpen(false);
      fetchData();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const handleImport = async () => {
    if (!importFile) {
      message.warning('请选择文件');
      return;
    }
    setImportLoading(true);
    try {
      await internalMaterialService.importExcel(importFile);
      message.success('导入成功');
      setImportModalOpen(false);
      setImportFile(null);
      fetchData();
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setImportLoading(false);
    }
  };

  const columns = [
    {
      title: '料号', dataIndex: 'materialNo', key: 'materialNo', width: 160,
      render: (v: string, r: any) => (
        <a onClick={(e) => { e.stopPropagation(); openDrawer(r); }}>{v}</a>
      ),
    },
    { title: '名称', dataIndex: 'name', key: 'name' },
    { title: '规格', dataIndex: 'specification', key: 'specification', ellipsis: true },
    { title: '尺寸', dataIndex: 'size', key: 'size', width: 120 },
    {
      title: '状态', dataIndex: 'statusCode', key: 'statusCode', width: 100,
      render: (v: string) => {
        const s = statusMap[v];
        return s ? <Tag color={s.color}>{s.label}</Tag> : <Tag>{v}</Tag>;
      },
    },
  ];

  const actions: ToolbarAction<any>[] = [
    {
      key: 'edit', label: '编辑', icon: <EditOutlined />,
      enabledWhen: (rows) => rows.length === 1 ? true : '编辑一次只能选一行',
      onClick: (rows) => openDrawer(rows[0]),
    },
    {
      key: 'delete', label: '删除', icon: <DeleteOutlined />, danger: true,
      enabledWhen: (rows) => rows.length > 0 ? true : false,
      needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 个料号？',
      onClick: async (rows) => {
        await runBatch(rows, (r: any) => internalMaterialService.delete(r.id).then(() => undefined), {
          rowLabel: (r: any) => `${r.materialNo} ${r.name}`,
          successMsg: `已删除 ${rows.length} 项`,
        });
        fetchData();
      },
    },
  ];

  const toolbar = (
    <Space wrap>
      <Search
        placeholder="搜索料号/名称"
        onSearch={v => setParams(p => ({ ...p, keyword: v, page: 0 }))}
        allowClear
        style={{ width: 260 }}
      />
      <Select
        placeholder="状态筛选" allowClear style={{ width: 120 }}
        onChange={v => setParams(p => ({ ...p, statusCode: v || '', page: 0 }))}
      >
        <Select.Option value="Y">可生产</Select.Option>
        <Select.Option value="N">停产</Select.Option>
      </Select>
      <Button icon={<UploadOutlined />} onClick={() => setImportModalOpen(true)}>Excel导入</Button>
      <Button type="primary" icon={<PlusOutlined />} onClick={() => openDrawer()}>新增料号</Button>
    </Space>
  );

  return (
    <Card title="生产料号管理">
      <SelectableTable<any>
        rowKey="id"
        columns={columns}
        dataSource={data}
        loading={loading}
        pagination={{
          current: params.page + 1,
          pageSize: params.size,
          total,
          showTotal: t => `共 ${t} 条`,
          onChange: p => setParams(prev => ({ ...prev, page: p - 1 })),
        }}
        toolbar={toolbar}
        actions={actions}
        rowLabel={(r: any) => `${r.materialNo} ${r.name}`}
      />

      <Drawer
        title={editingRecord ? '编辑料号' : '新增料号'}
        open={drawerOpen}
        onClose={() => { setDrawerOpen(false); setEditingRecord(null); }}
        destroyOnClose
        width={480}
      >
        <Form form={form} layout="vertical" onFinish={handleSave}>
          <Form.Item name="materialNo" label="料号" rules={[{ required: true, message: '请输入料号' }]}>
            <Input disabled={!!editingRecord} />
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="specification" label="规格">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="size" label="尺寸">
            <Input />
          </Form.Item>
          <Form.Item name="statusCode" label="状态" initialValue="Y" rules={[{ required: true, message: '请选择状态' }]}>
            <Select>
              <Select.Option value="Y">可生产</Select.Option>
              <Select.Option value="N">停产</Select.Option>
            </Select>
          </Form.Item>
          <Button type="primary" htmlType="submit" block>保存</Button>
        </Form>
      </Drawer>

      <Modal
        title="Excel批量导入"
        open={importModalOpen}
        onCancel={() => { setImportModalOpen(false); setImportFile(null); }}
        onOk={handleImport}
        confirmLoading={importLoading}
        okText="开始导入"
      >
        <Upload
          accept=".xlsx,.xls"
          maxCount={1}
          beforeUpload={file => { setImportFile(file); return false; }}
          onRemove={() => setImportFile(null)}
        >
          <Button icon={<UploadOutlined />}>选择Excel文件</Button>
        </Upload>
        <div style={{ marginTop: 8, color: '#888', fontSize: 12 }}>
          支持 .xlsx / .xls 格式，请确保文件包含：料号、名称、规格、尺寸、状态码列
        </div>
      </Modal>
    </Card>
  );
};

export default InternalMaterialManagement;
