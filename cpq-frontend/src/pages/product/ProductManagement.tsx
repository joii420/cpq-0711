import React, { useEffect, useState } from 'react';
import {
  Button, Drawer, Form, Input, Select, Space, Tag, message, Tabs, Upload, Modal,
  Descriptions, Typography,
} from 'antd';
import { useNavigate } from 'react-router-dom';
import {
  PlusOutlined, EditOutlined, DeleteOutlined, UploadOutlined, InboxOutlined,
  SettingOutlined, LinkOutlined,
} from '@ant-design/icons';
import { productService } from '../../services/productService';
import { productCategoryService } from '../../services/productCategoryService';
import type { ProductCategory } from '../../services/productCategoryService';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';

const { Dragger } = Upload;
const { Text } = Typography;

const ProductManagement: React.FC = () => {
  const navigate = useNavigate();
  const [data, setData] = useState<any>({ content: [], totalElements: 0 });
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingProduct, setEditingProduct] = useState<any>(null);
  const [form] = Form.useForm();
  const [categories, setCategories] = useState<ProductCategory[]>([]);
  const [params, setParams] = useState<any>({
    page: 0,
    size: 20,
    categoryId: '',
    status: '',
    keyword: '',
  });
  const [activeTab, setActiveTab] = useState('ALL');

  // Import state
  const [importModalOpen, setImportModalOpen] = useState(false);
  const [importLoading, setImportLoading] = useState(false);
  const [importResult, setImportResult] = useState<any>(null);
  const [pendingFile, setPendingFile] = useState<File | null>(null);

  const fetchData = async (overrideParams?: any) => {
    setLoading(true);
    try {
      const p = overrideParams ?? params;
      const cleanParams = Object.fromEntries(
        Object.entries(p).filter(([, v]) => v !== '' && v !== undefined)
      );
      const res = await productService.list(cleanParams);
      setData(res.data);
    } catch (err: any) {
      message.error(err?.response?.data?.message || '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchData();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [params]);

  useEffect(() => {
    productCategoryService.list('ACTIVE').then((res) => setCategories(res.data || [])).catch(() => {});
  }, []);

  const handleTabChange = (tab: string) => {
    setActiveTab(tab);
    setParams({ ...params, page: 0, categoryId: tab === 'ALL' ? '' : tab });
  };

  const handleSearch = (keyword: string) => setParams((prev: any) => ({ ...prev, page: 0, keyword }));
  const handleStatusFilter = (status: string) => setParams((prev: any) => ({ ...prev, page: 0, status }));

  const openCreate = () => {
    setEditingProduct(null);
    form.resetFields();
    setDrawerOpen(true);
  };

  const openEdit = (record: any) => {
    setEditingProduct(record);
    form.setFieldsValue({
      name: record.name,
      partNo: record.partNo,
      categoryId: record.categoryId,
      specification: record.specification,
      drawingNo: record.drawingNo,
      dimension: record.dimension,
      material: record.material,
      status: record.status,
      tags: record.tags || [],
    });
    setDrawerOpen(true);
  };

  const handleSave = async () => {
    try {
      const values = await form.validateFields();
      if (editingProduct) {
        await productService.update(editingProduct.id, values);
        message.success('更新成功');
      } else {
        await productService.create(values);
        message.success('创建成功');
      }
      setDrawerOpen(false);
      fetchData();
    } catch (err: any) {
      if (err?.response?.data?.message) message.error(err.response.data.message);
    }
  };

  const handleImport = async () => {
    if (!pendingFile) {
      message.warning('请选择文件');
      return;
    }
    setImportLoading(true);
    try {
      const res = await productService.importExcel(pendingFile);
      setImportResult(res.data);
      fetchData();
    } catch (err: any) {
      message.error(err?.response?.data?.message || '导入失败');
    } finally {
      setImportLoading(false);
    }
  };

  const columns = [
    {
      title: '产品名称', dataIndex: 'name', key: 'name', width: 200,
      render: (v: string, r: any) => (
        <a onClick={(e) => { e.stopPropagation(); openEdit(r); }}>{v}</a>
      ),
    },
    { title: '产品料号', dataIndex: 'partNo', key: 'partNo', width: 140 },
    {
      title: '分类', dataIndex: 'categoryName', key: 'categoryName', width: 120,
      render: (val: string, row: any) => <Tag color="blue">{val || row.category || '-'}</Tag>,
    },
    { title: '规格', dataIndex: 'specification', key: 'specification', ellipsis: true },
    {
      title: '标签', dataIndex: 'tags', key: 'tags', width: 160,
      render: (tags: string[]) =>
        tags?.map((t) => (<Tag key={t} style={{ marginBottom: 2 }}>{t}</Tag>)),
    },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 80,
      render: (val: string) => (
        <Tag color={val === 'ACTIVE' ? 'success' : 'default'}>
          {val === 'ACTIVE' ? '启用' : '停用'}
        </Tag>
      ),
    },
  ];

  const actions: ToolbarAction<any>[] = [
    {
      key: 'edit', label: '编辑', icon: <EditOutlined />,
      enabledWhen: (rows) => rows.length === 1 ? true : '编辑一次只能选一行',
      onClick: (rows) => openEdit(rows[0]),
    },
    {
      key: 'process', label: '配置工序', icon: <SettingOutlined />,
      enabledWhen: (rows) => rows.length === 1 ? true : '配置工序一次只能选一个产品',
      onClick: (rows) => navigate(`/products/${rows[0].id}/processes`),
    },
    {
      key: 'delete', label: '删除', icon: <DeleteOutlined />, danger: true,
      enabledWhen: (rows) => rows.length > 0 ? true : false,
      needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 个产品？',
      confirmDescription: '⚠️ 此操作不可撤销。已被报价单引用的产品可能删除失败。',
      onClick: async (rows) => {
        await runBatch(rows, (r: any) => productService.delete(r.id).then(() => undefined), {
          rowLabel: (r: any) => `${r.partNo} ${r.name}`,
          successMsg: `已删除 ${rows.length} 项`,
        });
        fetchData();
      },
    },
  ];

  const tabItems = [
    { key: 'ALL', label: '全部' },
    ...categories.map((c) => ({ key: c.id, label: c.name })),
  ];

  const toolbar = (
    <>
      <Typography.Title level={4} style={{ margin: 0 }}>产品管理</Typography.Title>
      <Space>
        <Input.Search placeholder="搜索产品名称/料号" allowClear style={{ width: 240 }} onSearch={handleSearch} />
        <Select
          placeholder="状态筛选" allowClear style={{ width: 120 }}
          onChange={(val) => handleStatusFilter(val ?? '')}
        >
          <Select.Option value="ACTIVE">启用</Select.Option>
          <Select.Option value="INACTIVE">停用</Select.Option>
        </Select>
        <Button icon={<LinkOutlined />} onClick={() => navigate('/template-bindings')}>模板绑定</Button>
        <Button
          icon={<UploadOutlined />}
          onClick={() => { setImportResult(null); setPendingFile(null); setImportModalOpen(true); }}
        >
          导入Excel
        </Button>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增产品</Button>
      </Space>
    </>
  );

  return (
    <div style={{ padding: 24, background: '#fff', borderRadius: 8 }}>
      <Tabs activeKey={activeTab} items={tabItems} onChange={handleTabChange} style={{ marginBottom: 8 }} />

      <SelectableTable<any>
        rowKey="id"
        columns={columns}
        dataSource={data.content}
        loading={loading}
        pagination={{
          current: params.page + 1,
          pageSize: params.size,
          total: data.totalElements,
          showSizeChanger: true,
          showTotal: (total: number) => `共 ${total} 条`,
          onChange: (page: number, pageSize: number) =>
            setParams((prev: any) => ({ ...prev, page: page - 1, size: pageSize })),
        }}
        toolbar={toolbar}
        actions={actions}
        rowLabel={(r: any) => `${r.partNo} ${r.name}`}
      />

      {/* Create/Edit Drawer */}
      <Drawer
        title={editingProduct ? '编辑产品' : '新增产品'}
        size="large"
        open={drawerOpen}
        onClose={() => setDrawerOpen(false)}
        extra={
          <Space>
            <Button onClick={() => setDrawerOpen(false)}>取消</Button>
            <Button type="primary" onClick={handleSave}>保存</Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical">
          <Form.Item label="产品名称" name="name" rules={[{ required: true, message: '请输入产品名称' }]}>
            <Input placeholder="请输入产品名称" />
          </Form.Item>
          <Form.Item label="产品料号" name="partNo" rules={[{ required: true, message: '请输入产品料号' }]}>
            <Input placeholder="请输入产品料号" disabled={!!editingProduct} />
          </Form.Item>
          <Form.Item label="分类" name="categoryId" rules={[{ required: true, message: '请选择分类' }]}>
            <Select
              placeholder="请选择分类"
              options={categories.map((c) => ({ label: c.name, value: c.id }))}
              showSearch optionFilterProp="label"
            />
          </Form.Item>
          <Form.Item label="规格" name="specification">
            <Input.TextArea rows={2} placeholder="请输入规格描述" />
          </Form.Item>
          <Form.Item label="图号" name="drawingNo">
            <Input placeholder="请输入图号" />
          </Form.Item>
          <Form.Item label="尺寸" name="dimension">
            <Input placeholder="请输入尺寸" />
          </Form.Item>
          <Form.Item label="材质" name="material">
            <Input placeholder="请输入材质" />
          </Form.Item>
          <Form.Item label="状态" name="status" initialValue="ACTIVE">
            <Select>
              <Select.Option value="ACTIVE">启用</Select.Option>
              <Select.Option value="INACTIVE">停用</Select.Option>
            </Select>
          </Form.Item>
          <Form.Item label="标签" name="tags">
            <Select mode="tags" placeholder="输入标签后回车添加" tokenSeparators={[',']} />
          </Form.Item>
        </Form>
      </Drawer>

      {/* Import Modal */}
      <Modal
        title="导入产品 (Excel)"
        open={importModalOpen}
        onCancel={() => setImportModalOpen(false)}
        footer={
          importResult ? (
            <Button type="primary" onClick={() => setImportModalOpen(false)}>关闭</Button>
          ) : (
            <Space>
              <Button onClick={() => setImportModalOpen(false)}>取消</Button>
              <Button type="primary" loading={importLoading} onClick={handleImport} disabled={!pendingFile}>
                开始导入
              </Button>
            </Space>
          )
        }
      >
        {!importResult ? (
          <Dragger
            accept=".xlsx,.xls"
            maxCount={1}
            beforeUpload={(file) => { setPendingFile(file); return false; }}
            onRemove={() => setPendingFile(null)}
          >
            <p className="ant-upload-drag-icon"><InboxOutlined /></p>
            <p className="ant-upload-text">点击或拖拽Excel文件到此处</p>
            <p className="ant-upload-hint">
              支持 .xlsx / .xls 格式，列顺序：名称、料号、分类、规格、标签（逗号分隔）
            </p>
          </Dragger>
        ) : (
          <>
            <Descriptions bordered column={3} style={{ marginBottom: 16 }}>
              <Descriptions.Item label="新增"><Text type="success">{importResult.added}</Text></Descriptions.Item>
              <Descriptions.Item label="跳过"><Text type="warning">{importResult.skipped}</Text></Descriptions.Item>
              <Descriptions.Item label="失败"><Text type="danger">{importResult.failed}</Text></Descriptions.Item>
            </Descriptions>
            {importResult.errors?.length > 0 && (
              <div style={{ maxHeight: 200, overflowY: 'auto', background: '#fff2f0', padding: 12, borderRadius: 4 }}>
                {importResult.errors.map((e: string, i: number) => (
                  <div key={i} style={{ color: '#ff4d4f', fontSize: 13 }}>{e}</div>
                ))}
              </div>
            )}
          </>
        )}
      </Modal>
    </div>
  );
};

export default ProductManagement;
