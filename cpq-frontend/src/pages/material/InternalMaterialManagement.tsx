import React, { useEffect, useState } from 'react';
import {
  Button, Drawer, Form, Input, InputNumber, Select, Space, message, Card,
} from 'antd';
import { PlusOutlined, DeleteOutlined, EditOutlined } from '@ant-design/icons';
import { materialMasterService, type MaterialMaster } from '../../services/materialMasterService';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';

const { Search } = Input;

// V6 material_master.material_type 字典:1.银点类 / 2.非银点类 / 组成件 / 边角料
const MATERIAL_TYPE_OPTIONS = [
  { label: '1.银点类', value: '1.银点类' },
  { label: '2.非银点类', value: '2.非银点类' },
  { label: '组成件', value: '组成件' },
  { label: '边角料', value: '边角料' },
];

// V6 material_master.usage_property 字典:1.正常 / 2.回收料
const USAGE_PROPERTY_OPTIONS = [
  { label: '1.正常', value: '1.正常' },
  { label: '2.回收料', value: '2.回收料' },
];

const InternalMaterialManagement: React.FC = () => {
  const [data, setData] = useState<MaterialMaster[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingRecord, setEditingRecord] = useState<MaterialMaster | null>(null);
  const [form] = Form.useForm();
  const [params, setParams] = useState({ page: 0, size: 20, keyword: '' });

  const fetchData = async () => {
    setLoading(true);
    try {
      const res = await materialMasterService.list({
        page: params.page,
        size: params.size,
        keyword: params.keyword || undefined,
      });
      setData(res.data?.content || []);
      setTotal(res.data?.totalElements || 0);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchData(); }, [params]);

  const openDrawer = (record?: MaterialMaster) => {
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
        await materialMasterService.update(editingRecord.id, values);
        message.success('更新成功');
      } else {
        await materialMasterService.create(values);
        message.success('创建成功');
      }
      setDrawerOpen(false);
      fetchData();
    } catch (e: any) {
      message.error(e.message);
    }
  };

  const columns = [
    {
      title: '料号', dataIndex: 'materialNo', key: 'materialNo', width: 140,
      render: (v: string, r: MaterialMaster) => (
        <a onClick={(e) => { e.stopPropagation(); openDrawer(r); }}>{v}</a>
      ),
    },
    { title: '名称', dataIndex: 'materialName', key: 'materialName', width: 160, ellipsis: true },
    { title: '规格', dataIndex: 'specification', key: 'specification', width: 140, ellipsis: true },
    { title: '尺寸', dataIndex: 'dimension', key: 'dimension', width: 120 },
    { title: '物料类型', dataIndex: 'materialType', key: 'materialType', width: 120 },
    { title: '使用属性', dataIndex: 'usageProperty', key: 'usageProperty', width: 100 },
    {
      title: '单重', dataIndex: 'unitWeight', key: 'unitWeight', width: 100,
      render: (v: number | null | undefined) => v != null ? Number(v).toString() : '—',
    },
    { title: '标准单位', dataIndex: 'standardUnit', key: 'standardUnit', width: 90 },
    { title: '老料号', dataIndex: 'oldMaterialNo', key: 'oldMaterialNo', width: 120, ellipsis: true },
  ];

  const actions: ToolbarAction<MaterialMaster>[] = [
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
        await runBatch(rows, (r) => materialMasterService.delete(r.id).then(() => undefined), {
          rowLabel: (r) => `${r.materialNo} ${r.materialName ?? ''}`,
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
      <Button type="primary" icon={<PlusOutlined />} onClick={() => openDrawer()}>新增料号</Button>
    </Space>
  );

  return (
    <Card title="产品主数据 (V6 material_master)">
      <SelectableTable<MaterialMaster>
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
        rowLabel={(r) => `${r.materialNo} ${r.materialName ?? ''}`}
      />

      <Drawer
        title={editingRecord ? '编辑料号' : '新增料号'}
        open={drawerOpen}
        onClose={() => { setDrawerOpen(false); setEditingRecord(null); }}
        destroyOnClose
        width={560}
      >
        <Form form={form} layout="vertical" onFinish={handleSave}>
          <Form.Item name="materialNo" label="料号" rules={[{ required: true, message: '请输入料号' }]}>
            <Input disabled={!!editingRecord} maxLength={20} placeholder="业务唯一键, 创建后不可改" />
          </Form.Item>
          <Form.Item name="materialName" label="名称">
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item name="specification" label="规格">
            <Input.TextArea rows={2} maxLength={100} />
          </Form.Item>
          <Form.Item name="dimension" label="尺寸">
            <Input maxLength={100} />
          </Form.Item>
          <Form.Item name="materialType" label="物料类型">
            <Select options={MATERIAL_TYPE_OPTIONS} allowClear placeholder="选择物料类型" />
          </Form.Item>
          <Form.Item name="usageProperty" label="使用属性">
            <Select options={USAGE_PROPERTY_OPTIONS} allowClear placeholder="选择使用属性" />
          </Form.Item>
          <Form.Item name="unitWeight" label="单重">
            <InputNumber style={{ width: '100%' }} precision={6} min={0} placeholder="数值, 6 位小数" />
          </Form.Item>
          <Form.Item name="standardUnit" label="标准单位">
            <Input maxLength={20} placeholder="如 PCS / KG / 个" />
          </Form.Item>
          <Form.Item name="oldMaterialNo" label="老料号">
            <Input maxLength={50} placeholder="可选, 用于历史关联" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block>保存</Button>
        </Form>
      </Drawer>
    </Card>
  );
};

export default InternalMaterialManagement;
