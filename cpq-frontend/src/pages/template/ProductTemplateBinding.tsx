import React, { useState, useEffect, useCallback } from 'react';
import {
  Layout,
  Input,
  List,
  Typography,
  Tabs,
  Table,
  Button,
  Tag,
  Space,
  Modal,
  Steps,
  Select,
  Switch,
  message,
  Spin,
  Empty,
  Tooltip,
  Card,
  Row,
  Col,
  Checkbox,
  Badge,
  Divider,
} from 'antd';
import {
  PlusOutlined,
  DeleteOutlined,
  StarFilled,
  LockOutlined,
} from '@ant-design/icons';
import { productService } from '../../services/productService';
import { templateService } from '../../services/templateService';
import { bindingService } from '../../services/bindingService';
import { processService } from '../../services/processService';

const { Sider, Content } = Layout;
const { Search } = Input;
const { Title, Text } = Typography;

const CATEGORY_COLOR: Record<string, string> = {
  SURFACE_TREATMENT: 'blue',
  MACHINING: 'cyan',
  HEAT_TREATMENT: 'red',
  ASSEMBLY: 'green',
  INSPECTION: 'orange',
  PACKAGING: 'purple',
};

const CATEGORY_LABEL: Record<string, string> = {
  SURFACE_TREATMENT: '表面处理',
  MACHINING: '机加',
  HEAT_TREATMENT: '热处理',
  ASSEMBLY: '装配',
  INSPECTION: '检测',
  PACKAGING: '包装',
};

interface ProductProcessItem {
  id: string;
  processId: string;
  code: string;
  name: string;
  description: string;
  category: string;
  isRequired: boolean;
  sortOrder: number;
}

interface BindingRecord {
  id: string;
  productId: string;
  processIds: string[];
  processIdsHash: string;
  templateId: string;
  isDefault: boolean;
  createdAt: string;
}

const ProductTemplateBinding: React.FC = () => {
  const [products, setProducts] = useState<any[]>([]);
  const [productSearch, setProductSearch] = useState('');
  const [selectedProduct, setSelectedProduct] = useState<any>(null);
  const [loadingProducts, setLoadingProducts] = useState(false);

  // Product's configured processes (from product management)
  const [productProcesses, setProductProcesses] = useState<ProductProcessItem[]>([]);
  const [loadingProcesses, setLoadingProcesses] = useState(false);

  // Bindings state
  const [bindings, setBindings] = useState<BindingRecord[]>([]);
  const [loadingBindings, setLoadingBindings] = useState(false);

  // Modal state
  const [modalOpen, setModalOpen] = useState(false);
  const [modalStep, setModalStep] = useState(0);
  const [modalProcessIds, setModalProcessIds] = useState<Set<string>>(new Set());
  const [publishedTemplates, setPublishedTemplates] = useState<any[]>([]);
  const [selectedTemplateId, setSelectedTemplateId] = useState<string | undefined>();
  const [creating, setCreating] = useState(false);

  // Load products
  const loadProducts = useCallback(async () => {
    setLoadingProducts(true);
    try {
      const res = await productService.list({ keyword: productSearch || undefined, size: 100 });
      const data = res.data;
      setProducts(Array.isArray(data) ? data : (data?.content || []));
    } catch {
      message.error('加载产品失败');
    } finally {
      setLoadingProducts(false);
    }
  }, [productSearch]);

  useEffect(() => { loadProducts(); }, [loadProducts]);

  // Load product's configured processes when product selected
  const loadProductProcesses = useCallback(async () => {
    if (!selectedProduct) return;
    setLoadingProcesses(true);
    try {
      const res = await processService.getProductProcesses(selectedProduct.id);
      const data: ProductProcessItem[] = res.data || [];
      setProductProcesses(data);
    } catch {
      setProductProcesses([]);
    } finally {
      setLoadingProcesses(false);
    }
  }, [selectedProduct]);

  useEffect(() => { loadProductProcesses(); }, [loadProductProcesses]);

  // Load bindings for selected product
  const loadBindings = useCallback(async () => {
    if (!selectedProduct) return;
    setLoadingBindings(true);
    try {
      const res = await bindingService.listByProduct(selectedProduct.id);
      const data = res.data;
      setBindings(Array.isArray(data) ? data : (data?.content || []));
    } catch {
      message.error('加载绑定失败');
    } finally {
      setLoadingBindings(false);
    }
  }, [selectedProduct]);

  useEffect(() => { loadBindings(); }, [loadBindings]);

  const handleDeleteBinding = async (bindingId: string) => {
    if (!selectedProduct) return;
    Modal.confirm({
      title: '确认删除绑定',
      onOk: async () => {
        try {
          await bindingService.delete(selectedProduct.id, bindingId);
          message.success('删除成功');
          loadBindings();
        } catch (e: any) {
          message.error(e.message || '删除失败');
        }
      },
    });
  };

  const handleSetDefault = async (bindingId: string) => {
    if (!selectedProduct) return;
    try {
      await bindingService.setDefault(selectedProduct.id, bindingId);
      message.success('已设为默认');
      loadBindings();
    } catch (e: any) {
      message.error(e.message || '操作失败');
    }
  };

  const openCreateModal = async () => {
    setModalStep(0);
    // Auto-select required processes
    const requiredIds = new Set(
      productProcesses.filter((p) => p.isRequired).map((p) => p.processId)
    );
    setModalProcessIds(requiredIds);
    setSelectedTemplateId(undefined);
    setModalOpen(true);

    // Load published templates
    try {
      const res = await templateService.list({ status: 'PUBLISHED', size: 100 });
      const tdata = res.data;
      setPublishedTemplates(Array.isArray(tdata) ? tdata : (tdata?.content || []));
    } catch {
      message.error('加载模板失败');
    }
  };

  const handleCreateBinding = async () => {
    if (!selectedProduct || !selectedTemplateId) {
      message.warning('请选择模板');
      return;
    }
    setCreating(true);
    try {
      await bindingService.create(selectedProduct.id, {
        templateId: selectedTemplateId,
        processIds: Array.from(modalProcessIds),
        isDefault: false,
      });
      message.success('绑定创建成功');
      setModalOpen(false);
      loadBindings();
    } catch (e: any) {
      message.error(e.message || '创建失败');
    } finally {
      setCreating(false);
    }
  };

  const getProcessName = (id: string) => {
    const p = productProcesses.find((x) => x.processId === id);
    return p ? p.name : id.substring(0, 8) + '...';
  };

  const getTemplateName = (templateId: string) => {
    const t = publishedTemplates.find((x) => x.id === templateId);
    return t ? `${t.name} (${t.version || '-'})` : templateId.substring(0, 8) + '...';
  };

  const bindingColumns = [
    {
      title: '工序组合',
      dataIndex: 'processIds',
      key: 'processIds',
      render: (ids: string[]) =>
        ids.length === 0
          ? <Tag>无特定工序</Tag>
          : ids.map((id) => (
              <Tag key={id} color="blue" style={{ marginBottom: 2 }}>
                {getProcessName(id)}
              </Tag>
            )),
    },
    {
      title: '模板',
      dataIndex: 'templateId',
      key: 'templateId',
      render: (tid: string) => getTemplateName(tid),
    },
    {
      title: '默认',
      dataIndex: 'isDefault',
      key: 'isDefault',
      width: 80,
      render: (val: boolean, record: BindingRecord) => (
        <Tooltip title={val ? '当前默认' : '设为默认'}>
          <Switch
            checked={val}
            size="small"
            onChange={() => !val && handleSetDefault(record.id)}
          />
        </Tooltip>
      ),
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      render: (v: string) => v ? new Date(v).toLocaleString('zh-CN') : '-',
    },
    {
      title: '操作',
      key: 'actions',
      render: (_: any, record: BindingRecord) => (
        <Button
          type="link"
          danger
          icon={<DeleteOutlined />}
          onClick={() => handleDeleteBinding(record.id)}
        >
          删除
        </Button>
      ),
    },
  ];

  // Group product processes by category for display
  const groupedProcesses: Record<string, ProductProcessItem[]> = {};
  for (const p of productProcesses) {
    if (!groupedProcesses[p.category]) groupedProcesses[p.category] = [];
    groupedProcesses[p.category].push(p);
  }
  const availableCategories = Object.keys(groupedProcesses);

  const renderStep0 = () => {
    if (productProcesses.length === 0) {
      return (
        <Empty
          description="该产品尚未配置工序，请先在产品管理中配置工序"
          style={{ padding: '40px 0' }}
        />
      );
    }

    return (
      <div style={{ display: 'flex', gap: 12, height: 380 }}>
        {/* Process list from product configuration */}
        <div style={{ flex: 1, overflow: 'auto' }}>
          <Text type="secondary" style={{ display: 'block', marginBottom: 12, fontSize: 12 }}>
            以下工序来自产品管理中的配置，必选工序已自动勾选
          </Text>
          {availableCategories.map((cat) => (
            <div key={cat} style={{ marginBottom: 16 }}>
              <Tag color={CATEGORY_COLOR[cat]} style={{ marginBottom: 8 }}>
                {CATEGORY_LABEL[cat] || cat}
              </Tag>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(180px, 1fr))', gap: 8 }}>
                {groupedProcesses[cat].map((proc) => {
                  const checked = modalProcessIds.has(proc.processId);
                  return (
                    <Card
                      key={proc.processId}
                      size="small"
                      hoverable={!proc.isRequired}
                      onClick={() => {
                        if (proc.isRequired) return;
                        setModalProcessIds((prev) => {
                          const next = new Set(prev);
                          if (next.has(proc.processId)) next.delete(proc.processId);
                          else next.add(proc.processId);
                          return next;
                        });
                      }}
                      style={{
                        border: checked ? '2px solid #1677ff' : '1px solid #f0f0f0',
                        background: checked ? '#f0f7ff' : '#fff',
                        cursor: proc.isRequired ? 'default' : 'pointer',
                      }}
                      styles={{ body: { padding: '8px 10px' } }}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
                        <Checkbox
                          checked={checked}
                          disabled={proc.isRequired}
                          onChange={() => {}}
                          onClick={(e) => e.stopPropagation()}
                        />
                        {proc.isRequired && (
                          <Tag color="red" icon={<LockOutlined />} style={{ fontSize: 10 }}>必选</Tag>
                        )}
                      </div>
                      <div style={{ fontWeight: 600, fontSize: 13, marginTop: 4 }}>{proc.name}</div>
                      <div style={{ color: '#888', fontSize: 11 }}>{proc.code}</div>
                    </Card>
                  );
                })}
              </div>
            </div>
          ))}
        </div>
        {/* Selected summary */}
        <div style={{ width: 180, flexShrink: 0, border: '1px solid #f0f0f0', borderRadius: 8, background: '#fff', overflow: 'auto', padding: 12 }}>
          <Text strong>已选 {modalProcessIds.size} 项</Text>
          <Divider style={{ margin: '8px 0' }} />
          {availableCategories.map((cat) => {
            const catProcs = groupedProcesses[cat].filter((p) => modalProcessIds.has(p.processId));
            if (catProcs.length === 0) return null;
            return (
              <div key={cat} style={{ marginBottom: 8 }}>
                <Tag color={CATEGORY_COLOR[cat]}>{CATEGORY_LABEL[cat] || cat}</Tag>
                {catProcs.map((p) => (
                  <div key={p.processId} style={{ fontSize: 12, padding: '2px 0', display: 'flex', alignItems: 'center', gap: 4 }}>
                    {p.name}
                    {p.isRequired && <LockOutlined style={{ fontSize: 10, color: '#ff4d4f' }} />}
                  </div>
                ))}
              </div>
            );
          })}
          {modalProcessIds.size === 0 && <Text type="secondary" style={{ fontSize: 12 }}>未选择工序</Text>}
        </div>
      </div>
    );
  };

  const renderStep1 = () => (
    <div>
      <Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>
        已选择 {modalProcessIds.size} 个工序，请从已发布模板中选择：
      </Text>
      <Select
        placeholder="搜索并选择模板"
        showSearch
        style={{ width: '100%' }}
        value={selectedTemplateId}
        onChange={setSelectedTemplateId}
        filterOption={(input, option) =>
          (option?.label as string ?? '').toLowerCase().includes(input.toLowerCase())
        }
        options={publishedTemplates.map((t) => ({
          value: t.id,
          label: `${t.name}  (${t.version || '-'})  [${t.category || '-'}]`,
        }))}
      />
      {selectedTemplateId && (
        <Card size="small" style={{ marginTop: 16 }}>
          {(() => {
            const t = publishedTemplates.find((x) => x.id === selectedTemplateId);
            if (!t) return null;
            return (
              <>
                <div><Text strong>{t.name}</Text> <Tag color="green">已发布</Tag></div>
                <div style={{ color: '#888', fontSize: 12, marginTop: 4 }}>版本：{t.version || '-'} | 分类：{t.category || '-'}</div>
                {t.description && <div style={{ fontSize: 12, marginTop: 4 }}>{t.description}</div>}
              </>
            );
          })()}
        </Card>
      )}
    </div>
  );

  // Process overview tab: show product's configured processes (read-only)
  const renderProcessOverview = () => (
    <Spin spinning={loadingProcesses}>
      {productProcesses.length === 0 ? (
        <Empty description="该产品尚未配置工序" />
      ) : (
        <div>
          <Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>
            以下工序在产品管理中配置，按设定顺序排列
          </Text>
          <Table
            dataSource={productProcesses}
            rowKey="processId"
            size="small"
            pagination={false}
            columns={[
              { title: '序号', key: 'index', width: 60, render: (_: any, __: any, i: number) => i + 1 },
              { title: '工序名称', dataIndex: 'name', key: 'name' },
              { title: '编码', dataIndex: 'code', key: 'code' },
              {
                title: '分类',
                dataIndex: 'category',
                key: 'category',
                render: (cat: string) => (
                  <Tag color={CATEGORY_COLOR[cat]}>{CATEGORY_LABEL[cat] || cat}</Tag>
                ),
              },
              {
                title: '必选',
                dataIndex: 'isRequired',
                key: 'isRequired',
                width: 80,
                render: (val: boolean) =>
                  val ? <Tag color="red" icon={<LockOutlined />}>必选</Tag> : <Tag>可选</Tag>,
              },
            ]}
          />
        </div>
      )}
    </Spin>
  );

  return (
    <Layout style={{ background: '#fff', minHeight: '100%' }}>
      {/* Product sidebar */}
      <Sider
        width={240}
        style={{ background: '#fafafa', borderRight: '1px solid #f0f0f0', padding: '12px 0' }}
      >
        <div style={{ padding: '0 12px 12px' }}>
          <Title level={5} style={{ margin: '0 0 8px' }}>产品列表</Title>
          <Search
            placeholder="搜索产品"
            size="small"
            onSearch={setProductSearch}
            onChange={(e) => !e.target.value && setProductSearch('')}
            allowClear
          />
        </div>
        <Spin spinning={loadingProducts}>
          <List
            size="small"
            dataSource={products}
            renderItem={(item: any) => (
              <List.Item
                onClick={() => setSelectedProduct(item)}
                style={{
                  cursor: 'pointer',
                  padding: '8px 16px',
                  background: selectedProduct?.id === item.id ? '#e6f4ff' : 'transparent',
                  borderLeft: selectedProduct?.id === item.id ? '3px solid #1677ff' : '3px solid transparent',
                }}
              >
                <div>
                  <div style={{ fontWeight: selectedProduct?.id === item.id ? 600 : 400, fontSize: 13 }}>
                    {item.name}
                  </div>
                  <div style={{ color: '#888', fontSize: 11 }}>{item.partNo}</div>
                </div>
              </List.Item>
            )}
            locale={{ emptyText: <Empty description="无产品" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }}
          />
        </Spin>
      </Sider>

      {/* Main content */}
      <Content style={{ padding: 24 }}>
        {!selectedProduct ? (
          <Empty description="请从左侧选择一个产品" style={{ marginTop: 80 }} />
        ) : (
          <>
            <Row justify="space-between" align="middle" style={{ marginBottom: 16 }}>
              <Col>
                <Title level={4} style={{ margin: 0 }}>
                  {selectedProduct.name}
                  <Tag color="blue" style={{ marginLeft: 8, fontSize: 13 }}>{selectedProduct.partNo}</Tag>
                </Title>
              </Col>
            </Row>

            <Tabs
              defaultActiveKey="bindings"
              items={[
                {
                  key: 'process',
                  label: '产品工序',
                  children: renderProcessOverview(),
                },
                {
                  key: 'bindings',
                  label: '绑定管理',
                  children: (
                    <>
                      <Row justify="end" style={{ marginBottom: 12 }}>
                        <Button type="primary" icon={<PlusOutlined />} onClick={openCreateModal}>
                          新建绑定
                        </Button>
                      </Row>
                      <Table
                        dataSource={bindings}
                        columns={bindingColumns}
                        rowKey="id"
                        loading={loadingBindings}
                        pagination={{ pageSize: 10 }}
                        locale={{ emptyText: <Empty description="暂无绑定记录" /> }}
                      />
                    </>
                  ),
                },
                {
                  key: 'versions',
                  label: '模板版本概览',
                  children: (
                    <Spin spinning={loadingBindings}>
                      {bindings.length === 0 ? (
                        <Empty description="暂无绑定的模板" />
                      ) : (
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12 }}>
                          {[...new Set(bindings.map((b) => b.templateId))].map((tid) => {
                            const t = publishedTemplates.find((x) => x.id === tid);
                            const bindingsForTemplate = bindings.filter((b) => b.templateId === tid);
                            const hasDefault = bindingsForTemplate.some((b) => b.isDefault);
                            return (
                              <Card
                                key={tid}
                                size="small"
                                style={{ width: 260 }}
                                title={
                                  <Space>
                                    <Text strong style={{ fontSize: 13 }}>{t?.name || tid.substring(0, 8)}</Text>
                                    {hasDefault && <StarFilled style={{ color: '#faad14' }} />}
                                  </Space>
                                }
                                extra={t ? <Tag color="green">{t.version || '-'}</Tag> : null}
                              >
                                <div style={{ fontSize: 12, color: '#888' }}>
                                  绑定数：{bindingsForTemplate.length}
                                </div>
                                {t?.category && <div style={{ fontSize: 12, marginTop: 4 }}>分类：{t.category}</div>}
                              </Card>
                            );
                          })}
                        </div>
                      )}
                    </Spin>
                  ),
                },
              ]}
            />
          </>
        )}
      </Content>

      {/* Create binding modal */}
      <Modal
        title="新建绑定"
        open={modalOpen}
        width={800}
        onCancel={() => setModalOpen(false)}
        footer={
          <Space>
            {modalStep > 0 && (
              <Button onClick={() => setModalStep((s) => s - 1)}>上一步</Button>
            )}
            {modalStep < 1 ? (
              <Button type="primary" onClick={() => setModalStep(1)}>
                下一步
              </Button>
            ) : (
              <Button type="primary" loading={creating} onClick={handleCreateBinding} disabled={!selectedTemplateId}>
                确认绑定
              </Button>
            )}
          </Space>
        }
        destroyOnClose
      >
        <Steps
          current={modalStep}
          style={{ marginBottom: 24 }}
          items={[
            { title: '选择工序组合' },
            { title: '选择模板' },
          ]}
        />
        {modalStep === 0 ? renderStep0() : renderStep1()}
      </Modal>
    </Layout>
  );
};

export default ProductTemplateBinding;
