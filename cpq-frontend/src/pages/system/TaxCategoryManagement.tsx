import React, { useCallback, useEffect, useState } from 'react';
import {
  Alert, Button, DatePicker, Drawer, Form, Input, InputNumber, Modal, Popconfirm,
  Select, Space, Table, Tag, Typography, message,
} from 'antd';
import { EditOutlined, PlusOutlined, ReloadOutlined, SettingOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs, { type Dayjs } from 'dayjs';
import {
  taxCategoryService,
  type TaxCategory,
  type TaxCategorySaveRequest,
  type TaxVatVersion,
} from '../../services/taxCategoryService';

const { Title, Text } = Typography;

const dateText = (value?: string | null) => value ? dayjs(value).format('YYYY-MM-DD') : '长期有效';
const percentText = (value?: string | number | null) => value == null || value === '' ? '—' : `${value}%`;

const TaxCategoryManagement: React.FC = () => {
  const [rows, setRows] = useState<TaxCategory[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState<string | undefined>('ACTIVE');
  const [editing, setEditing] = useState<TaxCategory | null>(null);
  const [categoryModalOpen, setCategoryModalOpen] = useState(false);
  const [categoryForm] = Form.useForm<TaxCategorySaveRequest>();
  const [versionCategory, setVersionCategory] = useState<TaxCategory | null>(null);
  const [versions, setVersions] = useState<TaxVatVersion[]>([]);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [versionModalOpen, setVersionModalOpen] = useState(false);
  const [editingVersion, setEditingVersion] = useState<TaxVatVersion | null>(null);
  const [versionForm] = Form.useForm<{ vatRate: number; effectiveFrom: Dayjs; effectiveTo?: Dayjs; status?: string }>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const result = await taxCategoryService.list({ keyword: keyword || undefined, status });
      setRows(result.data ?? []);
    } catch (error: any) {
      message.error(error?.message || '加载税务分类失败');
    } finally {
      setLoading(false);
    }
  }, [keyword, status]);

  useEffect(() => { void load(); }, [load]);

  const openCreate = () => {
    setEditing(null);
    categoryForm.resetFields();
    categoryForm.setFieldsValue({ status: 'ACTIVE' });
    setCategoryModalOpen(true);
  };

  const openEdit = (row: TaxCategory) => {
    setEditing(row);
    categoryForm.setFieldsValue({ name: row.name, status: row.status });
    setCategoryModalOpen(true);
  };

  const saveCategory = async () => {
    const values = await categoryForm.validateFields();
    try {
      if (editing) await taxCategoryService.update(editing.id, values);
      else await taxCategoryService.create(values);
      message.success(editing ? '税务分类已更新' : '税务分类已创建');
      setCategoryModalOpen(false);
      await load();
    } catch (error: any) {
      message.error(error?.message || '保存税务分类失败');
    }
  };

  const openVersions = async (row: TaxCategory) => {
    setVersionCategory(row);
    setVersionsLoading(true);
    try {
      const result = await taxCategoryService.versions(row.id);
      setVersions(result.data ?? []);
    } catch (error: any) {
      message.error(error?.message || '加载税率版本失败');
    } finally {
      setVersionsLoading(false);
    }
  };

  const saveVersion = async () => {
    if (!versionCategory) return;
    const values = await versionForm.validateFields();
    const payload = {
      vatRate: values.vatRate,
      effectiveFrom: values.effectiveFrom.format('YYYY-MM-DD'),
      effectiveTo: values.effectiveTo?.format('YYYY-MM-DD') || null,
      status: values.status || 'ACTIVE',
    };
    try {
      if (editingVersion) {
        await taxCategoryService.updateVersion(versionCategory.id, editingVersion.id, payload);
      } else {
        await taxCategoryService.addVersion(versionCategory.id, payload);
      }
      message.success(editingVersion ? '税率版本已更新' : '税率版本已新增');
      setVersionModalOpen(false);
      setEditingVersion(null);
      await openVersions(versionCategory);
      await load();
    } catch (error: any) {
      const messageByCode: Record<string, string> = {
        TAX_RATE_PERIOD_OVERLAP: '税率生效区间与已有启用版本重叠，请先设置原版本失效日期',
        TAX_CATEGORY_VERSION_IN_USE: '该税率版本已被报价快照使用，只能调整失效日期或启用/停用，不能修改税率和生效日期',
      };
      message.error(messageByCode[error?.message] || error?.message || '保存税率版本失败');
    }
  };

  const openVersionEdit = (version: TaxVatVersion) => {
    setEditingVersion(version);
    versionForm.setFieldsValue({
      vatRate: Number(version.vatRate),
      effectiveFrom: dayjs(version.effectiveFrom),
      effectiveTo: version.effectiveTo ? dayjs(version.effectiveTo) : undefined,
      status: version.status,
    });
    setVersionModalOpen(true);
  };

  const columns: ColumnsType<TaxCategory> = [
    { title: '编码', dataIndex: 'code', key: 'code', width: 190, render: (value: string) => <Text code>{value}</Text> },
    { title: '税务分类', dataIndex: 'name', key: 'name', width: 180 },
    { title: '当前有效 VAT', dataIndex: 'effectiveVatRate', key: 'effectiveVatRate', width: 140, render: percentText },
    { title: '默认分类', dataIndex: 'isDefault', key: 'isDefault', width: 110,
      render: (value: boolean) => value ? <Tag color="blue">系统默认</Tag> : <Text type="secondary">—</Text> },
    { title: '状态', dataIndex: 'status', key: 'status', width: 100,
      render: (value: string) => <Tag color={value === 'ACTIVE' ? 'green' : 'default'}>{value === 'ACTIVE' ? '启用' : '停用'}</Tag> },
    { title: '操作', key: 'actions', width: 250, fixed: 'right', render: (_: unknown, row) => (
      <Space size="small">
        <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(row)}>编辑</Button>
        <Button type="link" size="small" onClick={() => void openVersions(row)}>税率版本</Button>
        {!row.isDefault && row.status === 'ACTIVE' && (
          <Popconfirm title="设为系统默认税务分类？" okText="确认" cancelText="取消"
            onConfirm={async () => { try { await taxCategoryService.setDefault(row.id); message.success('默认分类已更新'); await load(); } catch (e: any) { message.error(e?.message || '设置默认分类失败'); } }}>
            <Button type="link" size="small">设为默认</Button>
          </Popconfirm>
        )}
      </Space>
    ) },
  ];

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12, flexWrap: 'wrap', marginBottom: 20 }}>
        <Space>
          <SettingOutlined style={{ color: '#1677ff', fontSize: 22 }} />
          <Title level={4} style={{ margin: 0 }}>税务分类与增值税率</Title>
          <Text type="secondary">产品税率来源与版本管理</Text>
        </Space>
        <Space wrap>
          <Input.Search allowClear placeholder="搜索编码 / 分类名称" style={{ width: 230 }} onSearch={(value) => setKeyword(value)} />
          <Select value={status} allowClear placeholder="全部状态" style={{ width: 110 }} onChange={setStatus}
            options={[{ value: 'ACTIVE', label: '启用' }, { value: 'INACTIVE', label: '停用' }]} />
          <Button icon={<ReloadOutlined />} onClick={() => void load()}>刷新</Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建分类</Button>
        </Space>
      </div>
      <Alert type="info" showIcon style={{ marginBottom: 16 }}
        message="报价取值顺序：产品税务分类有效 VAT → 报价单默认 VAT → 系统默认分类有效 VAT。已生成报价行保存税率快照，不随这里的修改回溯。" />
      <Table<TaxCategory> rowKey="id" loading={loading} columns={columns} dataSource={rows} scroll={{ x: 1050 }} pagination={{ pageSize: 20 }} />

      <Modal title={editing ? `编辑税务分类 · ${editing.code}` : '新建税务分类'} open={categoryModalOpen}
        onCancel={() => setCategoryModalOpen(false)} onOk={() => void saveCategory()} okText="保存" cancelText="取消">
        <Form form={categoryForm} layout="vertical">
          {!editing && <Form.Item name="code" label="分类编码" rules={[{ required: true, message: '请输入分类编码' }]}><Input placeholder="例如 GENERAL_GOODS" /></Form.Item>}
          <Form.Item name="name" label="分类名称" rules={[{ required: true, message: '请输入分类名称' }]}><Input placeholder="例如 一般货物" /></Form.Item>
          <Form.Item name="status" label="状态"><Select options={[{ value: 'ACTIVE', label: '启用' }, { value: 'INACTIVE', label: '停用' }]} /></Form.Item>
        </Form>
      </Modal>

      <Drawer title={versionCategory ? `${versionCategory.name} · VAT 税率版本` : 'VAT 税率版本'} open={!!versionCategory}
        onClose={() => setVersionCategory(null)} width={720} extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditingVersion(null); versionForm.resetFields(); versionForm.setFieldsValue({ status: 'ACTIVE', effectiveFrom: dayjs() }); setVersionModalOpen(true); }}>新增版本</Button>}>
        <Table<TaxVatVersion> rowKey="id" loading={versionsLoading} dataSource={versions} pagination={false} columns={[
          { title: 'VAT', dataIndex: 'vatRate', render: percentText },
          { title: '生效日期', dataIndex: 'effectiveFrom' },
          { title: '失效日期', dataIndex: 'effectiveTo', render: dateText },
          { title: '状态', dataIndex: 'status', render: (value: string) => <Tag color={value === 'ACTIVE' ? 'green' : 'default'}>{value === 'ACTIVE' ? '启用' : '停用'}</Tag> },
          { title: '操作', key: 'actions', width: 80, render: (_: unknown, row: TaxVatVersion) => <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openVersionEdit(row)}>编辑</Button> },
        ]} />
      </Drawer>

      <Modal title={editingVersion ? '编辑 VAT 税率版本' : '新增 VAT 税率版本'} open={versionModalOpen} onCancel={() => { setVersionModalOpen(false); setEditingVersion(null); }} onOk={() => void saveVersion()} okText="保存" cancelText="取消">
        <Form form={versionForm} layout="vertical">
          <Form.Item name="vatRate" label="VAT 税率（百分比）" rules={[{ required: true, message: '请输入税率' }]}><InputNumber min={0} max={100} precision={6} addonAfter="%" style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="effectiveFrom" label="生效日期" rules={[{ required: true, message: '请选择生效日期' }]}><DatePicker style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="effectiveTo" label="失效日期"><DatePicker style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="status" label="状态"><Select options={[{ value: 'ACTIVE', label: '启用' }, { value: 'INACTIVE', label: '停用' }]} /></Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default TaxCategoryManagement;
