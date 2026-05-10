import React, { useState, useEffect } from 'react';
import {
  Button,
  Input,
  Select,
  Tag,
  Typography,
  Modal,
  Form,
  message,
  Row,
  Col,
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { templateService } from '../../services/templateService';
import { productCategoryService, type ProductCategory } from '../../services/productCategoryService';
import { customerService } from '../../services/customerService';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';

interface CustomerLite { id: string; name: string }

const { Title } = Typography;
const { Search } = Input;

const STATUS_COLORS: Record<string, string> = {
  DRAFT: 'blue',
  PUBLISHED: 'green',
  ARCHIVED: 'default',
};

const STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  ARCHIVED: '已归档',
};

const LEGACY_CATEGORY_LABELS: Record<string, string> = {
  STANDARD_PARTS: '标准件',
  CUSTOM_PARTS: '定制件',
  RAW_MATERIALS: '原材料',
};

const STATUS_OPTIONS = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'PUBLISHED', label: '已发布' },
  { value: 'ARCHIVED', label: '已归档' },
];

const KIND_LABELS: Record<string, string> = {
  QUOTATION: '报价模板',
  COSTING: '核价模板',
};
const KIND_COLORS: Record<string, string> = {
  QUOTATION: 'blue',
  COSTING: 'orange',
};
const KIND_OPTIONS = [
  { value: 'QUOTATION', label: '报价模板' },
  { value: 'COSTING', label: '核价模板' },
];

const TemplateList: React.FC = () => {
  const navigate = useNavigate();
  const [templates, setTemplates] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [categoryFilter, setCategoryFilter] = useState<string | undefined>();
  const [statusFilter, setStatusFilter] = useState<string | undefined>();
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [form] = Form.useForm();
  const [categories, setCategories] = useState<ProductCategory[]>([]);
  const [customers, setCustomers] = useState<CustomerLite[]>([]);
  const [customerFilter, setCustomerFilter] = useState<string | undefined>();
  const [scopeFilter, setScopeFilter] = useState<'ALL' | 'GENERAL' | 'SPECIFIC'>('ALL');
  const [kindFilter, setKindFilter] = useState<string | undefined>();

  const categoryOptions = categories.map(c => ({ value: c.id, label: c.name }));
  const customerOptions = customers.map(c => ({ value: c.id, label: c.name }));
  const categoryNameMap = React.useMemo(() => {
    const m: Record<string, string> = {};
    categories.forEach(c => { m[c.id] = c.name; });
    return m;
  }, [categories]);

  const loadTemplates = async () => {
    setLoading(true);
    try {
      const res = await templateService.list({
        keyword: keyword || undefined,
        categoryId: categoryFilter,
        customerId: scopeFilter === 'SPECIFIC' ? customerFilter : undefined,
        status: statusFilter,
        templateKind: kindFilter,
        size: 100,
      });
      let list = res.data || [];
      if (scopeFilter === 'GENERAL') {
        list = list.filter((t: any) => !t.customerId);
      }
      setTemplates(list);
    } catch (e: any) {
      message.error(e.message || '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    productCategoryService.list('ACTIVE')
      .then(res => setCategories(res.data || []))
      .catch(() => setCategories([]));
    customerService.list({ size: 500 })
      .then((res: any) => {
        const list = res.data?.content ?? res.data ?? [];
        setCustomers(list.map((c: any) => ({ id: c.id, name: c.name })));
      })
      .catch(() => setCustomers([]));
  }, []);

  useEffect(() => {
    loadTemplates();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [keyword, categoryFilter, statusFilter, customerFilter, scopeFilter, kindFilter]);

  const handleCreate = async (values: any) => {
    try {
      const res = await templateService.create(values);
      message.success('创建成功');
      setCreateModalOpen(false);
      form.resetFields();
      navigate(`/templates/${res.data.id}`);
    } catch (e: any) {
      message.error(e.message || '创建失败');
    }
  };

  // 列定义 —— 不再有"操作"列；模板名称作为主入口链接
  const columns = [
    {
      title: '模板名称',
      dataIndex: 'name',
      key: 'name',
      render: (text: string, record: any) => (
        <a onClick={(e) => { e.stopPropagation(); navigate(`/templates/${record.id}`); }}>{text}</a>
      ),
    },
    {
      title: '模板类型',
      dataIndex: 'templateKind',
      key: 'templateKind',
      width: 110,
      render: (v: string) => {
        const kind = v || 'QUOTATION';
        return <Tag color={KIND_COLORS[kind]}>{KIND_LABELS[kind] || kind}</Tag>;
      },
    },
    {
      title: '产品分类',
      key: 'category',
      render: (_: any, record: any) => {
        if (record.categoryName) return record.categoryName;
        if (record.categoryId && categoryNameMap[record.categoryId]) return categoryNameMap[record.categoryId];
        if (record.category) return LEGACY_CATEGORY_LABELS[record.category] || record.category;
        return '-';
      },
    },
    {
      title: '适用范围',
      key: 'scope',
      render: (_: any, record: any) => {
        if (record.customerName) return <Tag color="purple">客户专属:{record.customerName}</Tag>;
        if (record.customerId) return <Tag color="purple">客户专属</Tag>;
        return <Tag color="cyan">通用</Tag>;
      },
    },
    {
      title: '版本',
      dataIndex: 'version',
      key: 'version',
      render: (v: string) => v || '-',
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (v: string) => <Tag color={STATUS_COLORS[v]}>{STATUS_LABELS[v] || v}</Tag>,
    },
    {
      title: '描述',
      dataIndex: 'description',
      key: 'description',
      ellipsis: true,
      render: (v: string) => v || '-',
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (v: string) => v ? new Date(v).toLocaleString('zh-CN') : '-',
    },
  ];

  // 工具栏动作
  const actions: ToolbarAction<any>[] = [
    {
      key: 'edit',
      label: '编辑',
      icon: <EditOutlined />,
      enabledWhen: (rows) => rows.length === 1 ? true : '编辑一次只能选一行',
      onClick: (rows) => navigate(`/templates/${rows[0].id}`),
    },
    {
      key: 'delete',
      label: '删除',
      icon: <DeleteOutlined />,
      danger: true,
      enabledWhen: (rows) => {
        if (rows.length === 0) return false;
        if (rows.some((r: any) => r.status !== 'DRAFT')) return '仅草稿状态可删除';
        return true;
      },
      needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 个模板？',
      confirmDescription: '⚠️ 此操作不可撤销。已发布过的版本请改为归档。',
      onClick: async (rows) => {
        await runBatch(rows, (r: any) => templateService.delete(r.id).then(() => undefined), {
          rowLabel: (r: any) => `${r.name}${r.version ? ' ' + r.version : ''}`,
          successMsg: `已删除 ${rows.length} 项`,
        });
        loadTemplates();
      },
    },
  ];

  const toolbar = (
    <>
      <div>
        <Title level={4} style={{ margin: 0 }}>模板配置</Title>
        <span style={{ color: '#8c8c8c', fontSize: 12 }}>
          配置报价单的「产品卡片视图」（组件 tab、字段、公式、产品属性），按客户专属/通用维度管理
        </span>
      </div>
      <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateModalOpen(true)}>
        新建模板
      </Button>
    </>
  );

  return (
    <div>
      <Row gutter={12} style={{ marginBottom: 16 }}>
        <Col span={6}>
          <Search
            placeholder="搜索模板名称"
            onSearch={setKeyword}
            onChange={(e) => !e.target.value && setKeyword('')}
            allowClear
          />
        </Col>
        <Col span={4}>
          <Select
            placeholder="产品分类"
            options={categoryOptions}
            allowClear
            showSearch
            optionFilterProp="label"
            style={{ width: '100%' }}
            onChange={setCategoryFilter}
          />
        </Col>
        <Col span={4}>
          <Select
            placeholder="适用范围"
            options={[
              { value: 'ALL', label: '全部' },
              { value: 'GENERAL', label: '通用模板' },
              { value: 'SPECIFIC', label: '客户专属' },
            ]}
            value={scopeFilter}
            style={{ width: '100%' }}
            onChange={(v) => { setScopeFilter(v); if (v !== 'SPECIFIC') setCustomerFilter(undefined); }}
          />
        </Col>
        {scopeFilter === 'SPECIFIC' && (
          <Col span={5}>
            <Select
              placeholder="选择客户"
              options={customerOptions}
              allowClear
              showSearch
              optionFilterProp="label"
              style={{ width: '100%' }}
              onChange={setCustomerFilter}
              value={customerFilter}
            />
          </Col>
        )}
        <Col span={4}>
          <Select
            placeholder="模板类型"
            options={KIND_OPTIONS}
            allowClear
            value={kindFilter}
            style={{ width: '100%' }}
            onChange={setKindFilter}
          />
        </Col>
        <Col span={4}>
          <Select
            placeholder="状态筛选"
            options={STATUS_OPTIONS}
            allowClear
            style={{ width: '100%' }}
            onChange={setStatusFilter}
          />
        </Col>
      </Row>

      <SelectableTable<any>
        rowKey="id"
        columns={columns}
        dataSource={templates}
        loading={loading}
        pagination={{ pageSize: 20 }}
        toolbar={toolbar}
        actions={actions}
        rowLabel={(r: any) => `${r.name}${r.version ? ' ' + r.version : ''} (${STATUS_LABELS[r.status] || r.status})`}
      />

      <Modal
        title="新建模板"
        open={createModalOpen}
        onOk={() => form.submit()}
        onCancel={() => { setCreateModalOpen(false); form.resetFields(); }}
        destroyOnClose
      >
        <Form form={form} layout="vertical" onFinish={handleCreate} initialValues={{ templateKind: 'QUOTATION' }}>
          <Form.Item name="name" label="模板名称" rules={[{ required: true, message: '请输入模板名称' }]}>
            <Input placeholder="请输入模板名称" />
          </Form.Item>
          <Form.Item
            name="templateKind"
            label="模板类型"
            rules={[{ required: true, message: '请选择模板类型' }]}
            tooltip="报价模板：报价单的产品卡片视图。核价模板：核价单的产品卡片视图，留空适用客户表示对所有客户可用"
          >
            <Select options={KIND_OPTIONS} />
          </Form.Item>
          <Form.Item name="categoryId" label="产品分类">
            <Select
              options={categoryOptions}
              placeholder="请选择产品分类(由产品分类管理模块维护)"
              allowClear
              showSearch
              optionFilterProp="label"
            />
          </Form.Item>
          <Form.Item
            noStyle
            shouldUpdate={(prev, cur) => prev.templateKind !== cur.templateKind}
          >
            {({ getFieldValue }) => {
              const kind = getFieldValue('templateKind') || 'QUOTATION';
              const isCosting = kind === 'COSTING';
              return (
                <Form.Item
                  name="customerId"
                  label="适用客户"
                  tooltip={isCosting
                    ? '核价模板：留空表示对所有客户可用（推荐）；选择特定客户则只对该客户生效'
                    : '报价模板：留空 = 通用模板；选择客户 = 该客户专属模板。匹配时客户专属优先，无则回退通用'}
                >
                  <Select
                    options={customerOptions}
                    placeholder={isCosting ? '默认所有客户可用，可选择特定客户' : '不选则为通用模板，选择则为客户专属'}
                    allowClear
                    showSearch
                    optionFilterProp="label"
                  />
                </Form.Item>
              );
            }}
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={3} placeholder="请输入描述" />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default TemplateList;
