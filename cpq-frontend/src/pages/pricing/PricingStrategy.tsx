import React, { useEffect, useState } from 'react';
import {
  Layout, List, Input, Tabs, Card, Button, Tag, Table, Space, Modal,
  Form, InputNumber, DatePicker, Select, Popconfirm, message, Alert,
  Typography, Divider,
} from 'antd';
import { PlusOutlined, EditOutlined, DeleteOutlined, StopOutlined, CheckCircleOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { customerService } from '../../services/customerService';
import { pricingService } from '../../services/pricingService';

const { Sider, Content } = Layout;
const { Search } = Input;
const { Text } = Typography;

const levelMap: Record<string, { label: string; color: string }> = {
  DIAMOND: { label: '钻石', color: 'purple' },
  VIP:     { label: 'VIP',  color: 'gold' },
  GOLD:    { label: '黄金', color: 'orange' },
  SILVER:  { label: '白银', color: 'default' },
  STANDARD:{ label: '标准', color: 'blue' },
};

const statusMap: Record<string, { label: string; color: string }> = {
  ACTIVE:   { label: '生效中', color: 'green' },
  EXPIRED:  { label: '已过期', color: 'orange' },
  DISABLED: { label: '已禁用', color: 'red' },
};

const LEVELS = ['ALL', 'DIAMOND', 'VIP', 'GOLD', 'SILVER', 'STANDARD'];

const PricingStrategy: React.FC = () => {
  const [customers, setCustomers] = useState<any[]>([]);
  const [customerTotal, setCustomerTotal] = useState(0);
  const [customerPage, setCustomerPage] = useState(0);
  const [customerKeyword, setCustomerKeyword] = useState('');
  const [levelFilter, setLevelFilter] = useState('ALL');
  const [selectedCustomer, setSelectedCustomer] = useState<any>(null);
  const [strategies, setStrategies] = useState<any[]>([]);
  const [loadingCustomers, setLoadingCustomers] = useState(false);
  const [loadingStrategies, setLoadingStrategies] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editingStrategy, setEditingStrategy] = useState<any>(null);
  const [form] = Form.useForm();
  const [rules, setRules] = useState<any[]>([]);

  // Load customers
  const fetchCustomers = async (page = 0, keyword = '', level = '') => {
    setLoadingCustomers(true);
    try {
      const params: any = { page, size: 20 };
      if (keyword) params.keyword = keyword;
      if (level && level !== 'ALL') params.level = level;
      const res = await customerService.list(params);
      setCustomers(res.data.content || []);
      setCustomerTotal(res.data.totalElements || 0);
      setCustomerPage(page);
    } catch (e: any) {
      message.error('加载客户失败');
    } finally {
      setLoadingCustomers(false);
    }
  };

  // Load strategies for selected customer
  const fetchStrategies = async (customerId: string) => {
    setLoadingStrategies(true);
    try {
      const res = await pricingService.list({ customerId, size: 100 });
      setStrategies(res.data.content || []);
    } catch (e: any) {
      message.error('加载策略失败');
    } finally {
      setLoadingStrategies(false);
    }
  };

  useEffect(() => {
    fetchCustomers(0, customerKeyword, levelFilter);
  }, [levelFilter]);

  useEffect(() => {
    if (selectedCustomer) {
      fetchStrategies(selectedCustomer.id);
    } else {
      setStrategies([]);
    }
  }, [selectedCustomer]);

  const activeCount = strategies.filter(s => s.status === 'ACTIVE').length;

  const openCreateModal = () => {
    setEditingStrategy(null);
    setRules([]);
    form.resetFields();
    setModalOpen(true);
  };

  const openEditModal = (strategy: any) => {
    setEditingStrategy(strategy);
    setRules(strategy.rules ? strategy.rules.map((r: any, i: number) => ({ ...r, _key: i })) : []);
    form.setFieldsValue({
      name: strategy.name,
      type: strategy.type,
      baseDiscount: strategy.baseDiscount,
      minOrderAmount: strategy.minOrderAmount,
      effectiveDate: strategy.effectiveDate ? dayjs(strategy.effectiveDate) : null,
      expirationDate: strategy.expirationDate ? dayjs(strategy.expirationDate) : null,
      priority: strategy.priority,
    });
    setModalOpen(true);
  };

  const handleSave = async () => {
    try {
      const values = await form.validateFields();
      const payload = {
        customerId: selectedCustomer.id,
        name: values.name,
        type: values.type || 'DISCOUNT',
        baseDiscount: values.baseDiscount,
        minOrderAmount: values.minOrderAmount || 0,
        effectiveDate: values.effectiveDate ? values.effectiveDate.format('YYYY-MM-DD') : null,
        expirationDate: values.expirationDate ? values.expirationDate.format('YYYY-MM-DD') : null,
        priority: values.priority || 1,
        rules: rules.map((r, i) => ({
          ruleType: r.ruleType || 'BULK_DISCOUNT',
          thresholdAmount: r.thresholdAmount,
          discountRate: r.discountRate,
          sortOrder: i + 1,
        })),
      };
      if (editingStrategy) {
        await pricingService.update(editingStrategy.id, payload);
        message.success('策略已更新');
      } else {
        await pricingService.create(payload);
        message.success('策略已创建');
      }
      setModalOpen(false);
      fetchStrategies(selectedCustomer.id);
    } catch (e: any) {
      if (e?.errorFields) return; // form validation
      message.error(e?.response?.data?.message || '操作失败');
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await pricingService.delete(id);
      message.success('策略已删除');
      fetchStrategies(selectedCustomer.id);
    } catch (e: any) {
      message.error('删除失败');
    }
  };

  const handleToggleStatus = async (strategy: any) => {
    const newStatus = strategy.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
    try {
      await pricingService.updateStatus(strategy.id, newStatus);
      message.success(`策略已${newStatus === 'ACTIVE' ? '启用' : '禁用'}`);
      fetchStrategies(selectedCustomer.id);
    } catch (e: any) {
      message.error('状态更新失败');
    }
  };

  const addRule = () => {
    setRules(prev => [...prev, { _key: Date.now(), ruleType: 'BULK_DISCOUNT', thresholdAmount: 0, discountRate: 0, sortOrder: prev.length + 1 }]);
  };

  const removeRule = (index: number) => {
    setRules(prev => prev.filter((_, i) => i !== index));
  };

  const updateRule = (index: number, field: string, value: any) => {
    setRules(prev => prev.map((r, i) => i === index ? { ...r, [field]: value } : r));
  };

  const ruleColumns = [
    {
      title: '起订金额',
      dataIndex: 'thresholdAmount',
      render: (_: any, __: any, index: number) => (
        <InputNumber
          value={rules[index]?.thresholdAmount}
          min={0}
          precision={2}
          style={{ width: 130 }}
          onChange={v => updateRule(index, 'thresholdAmount', v)}
        />
      ),
    },
    {
      title: '折扣率(%)',
      dataIndex: 'discountRate',
      render: (_: any, __: any, index: number) => (
        <InputNumber
          value={rules[index]?.discountRate}
          min={0}
          max={100}
          precision={2}
          style={{ width: 110 }}
          onChange={v => updateRule(index, 'discountRate', v)}
        />
      ),
    },
    {
      title: '规则类型',
      dataIndex: 'ruleType',
      render: (_: any, __: any, index: number) => (
        <Select
          value={rules[index]?.ruleType}
          style={{ width: 140 }}
          onChange={v => updateRule(index, 'ruleType', v)}
          options={[
            { value: 'BULK_DISCOUNT', label: '批量折扣' },
            { value: 'AMOUNT_DISCOUNT', label: '金额折扣' },
          ]}
        />
      ),
    },
    {
      title: '',
      render: (_: any, __: any, index: number) => (
        <Button type="link" danger icon={<DeleteOutlined />} onClick={() => removeRule(index)} />
      ),
    },
  ];

  const levelTabs = LEVELS.map(l => ({
    key: l,
    label: l === 'ALL' ? '全部' : (levelMap[l]?.label || l),
  }));

  return (
    <Layout style={{ height: 'calc(100vh - 112px)', background: 'transparent' }}>
      {/* Left sidebar: customer list */}
      <Sider
        width={260}
        theme="light"
        style={{
          background: '#fff',
          borderRight: '1px solid #f0f0f0',
          borderRadius: 8,
          overflow: 'auto',
          marginRight: 16,
        }}
      >
        <div style={{ padding: '16px 16px 8px' }}>
          <Text strong style={{ fontSize: 14 }}>客户列表</Text>
        </div>
        <div style={{ padding: '0 12px 8px' }}>
          <Search
            placeholder="搜索客户"
            allowClear
            onSearch={v => { setCustomerKeyword(v); fetchCustomers(0, v, levelFilter); }}
            size="small"
          />
        </div>
        <Tabs
          size="small"
          activeKey={levelFilter}
          onChange={l => setLevelFilter(l)}
          items={levelTabs}
          tabBarStyle={{ paddingLeft: 12, marginBottom: 0 }}
          tabBarGutter={4}
        />
        <List
          loading={loadingCustomers}
          dataSource={customers}
          renderItem={c => (
            <List.Item
              key={c.id}
              onClick={() => setSelectedCustomer(c)}
              style={{
                cursor: 'pointer',
                padding: '8px 16px',
                background: selectedCustomer?.id === c.id ? '#e6f4ff' : 'transparent',
                borderLeft: selectedCustomer?.id === c.id ? '3px solid #1677ff' : '3px solid transparent',
              }}
            >
              <div>
                <div style={{ fontWeight: 500 }}>{c.name}</div>
                <div>
                  <Tag color={levelMap[c.level]?.color} style={{ marginTop: 2, fontSize: 11 }}>
                    {levelMap[c.level]?.label || c.level}
                  </Tag>
                  <Text type="secondary" style={{ fontSize: 11 }}>{c.code}</Text>
                </div>
              </div>
            </List.Item>
          )}
          pagination={customerTotal > 20 ? {
            total: customerTotal,
            pageSize: 20,
            current: customerPage + 1,
            size: 'small',
            onChange: p => fetchCustomers(p - 1, customerKeyword, levelFilter),
            style: { padding: '8px 12px' },
          } : false}
        />
      </Sider>

      {/* Right: strategies */}
      <Content style={{ background: '#fff', borderRadius: 8, padding: 20, overflow: 'auto' }}>
        {!selectedCustomer ? (
          <div style={{ textAlign: 'center', color: '#999', paddingTop: 80 }}>
            请从左侧选择一个客户查看定价策略
          </div>
        ) : (
          <>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
              <div>
                <Text strong style={{ fontSize: 16 }}>{selectedCustomer.name}</Text>
                <Tag color={levelMap[selectedCustomer.level]?.color} style={{ marginLeft: 8 }}>
                  {levelMap[selectedCustomer.level]?.label || selectedCustomer.level}
                </Tag>
              </div>
              <Button type="primary" icon={<PlusOutlined />} onClick={openCreateModal}>
                新建策略
              </Button>
            </div>

            {activeCount > 0 && (
              <Alert
                type="info"
                message={`当前有 ${activeCount} 条生效策略`}
                style={{ marginBottom: 16 }}
                showIcon
              />
            )}

            {strategies.length === 0 && !loadingStrategies ? (
              <div style={{ textAlign: 'center', color: '#999', paddingTop: 60 }}>
                暂无定价策略，点击"新建策略"开始配置
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
                {strategies.map(s => (
                  <Card
                    key={s.id}
                    size="small"
                    loading={loadingStrategies}
                    title={
                      <Space>
                        <Text strong>{s.name}</Text>
                        <Tag color={statusMap[s.status]?.color}>
                          {statusMap[s.status]?.label || s.status}
                        </Tag>
                        <Text type="secondary" style={{ fontSize: 12 }}>优先级: {s.priority}</Text>
                      </Space>
                    }
                    extra={
                      <Space>
                        <Button size="small" icon={<EditOutlined />} onClick={() => openEditModal(s)}>
                          编辑
                        </Button>
                        <Button
                          size="small"
                          icon={s.status === 'ACTIVE' ? <StopOutlined /> : <CheckCircleOutlined />}
                          onClick={() => handleToggleStatus(s)}
                        >
                          {s.status === 'ACTIVE' ? '禁用' : '启用'}
                        </Button>
                        <Popconfirm
                          title="确认删除此策略？"
                          onConfirm={() => handleDelete(s.id)}
                          okText="删除"
                          cancelText="取消"
                        >
                          <Button size="small" danger icon={<DeleteOutlined />}>删除</Button>
                        </Popconfirm>
                      </Space>
                    }
                  >
                    <div style={{ display: 'flex', gap: 24, marginBottom: s.rules?.length > 0 ? 12 : 0, flexWrap: 'wrap' }}>
                      <div>
                        <Text type="secondary">基础折扣: </Text>
                        <Text strong>{s.baseDiscount}%</Text>
                      </div>
                      <div>
                        <Text type="secondary">最小订单金额: </Text>
                        <Text>¥{Number(s.minOrderAmount || 0).toLocaleString()}</Text>
                      </div>
                      {s.effectiveDate && (
                        <div>
                          <Text type="secondary">有效期: </Text>
                          <Text>{s.effectiveDate} ~ {s.expirationDate || '无限'}</Text>
                        </div>
                      )}
                    </div>

                    {s.rules && s.rules.length > 0 && (
                      <>
                        <Divider style={{ margin: '8px 0' }} />
                        <Table
                          size="small"
                          dataSource={s.rules}
                          rowKey="id"
                          pagination={false}
                          columns={[
                            {
                              title: '起订金额',
                              dataIndex: 'thresholdAmount',
                              render: v => `¥${Number(v).toLocaleString()}`,
                            },
                            {
                              title: '折扣率',
                              dataIndex: 'discountRate',
                              render: v => `${v}%`,
                            },
                            {
                              title: '规则类型',
                              dataIndex: 'ruleType',
                              render: v => v === 'BULK_DISCOUNT' ? '批量折扣' : v === 'AMOUNT_DISCOUNT' ? '金额折扣' : v,
                            },
                            {
                              title: '排序',
                              dataIndex: 'sortOrder',
                            },
                          ]}
                        />
                      </>
                    )}
                  </Card>
                ))}
              </div>
            )}
          </>
        )}
      </Content>

      {/* Create/Edit Modal */}
      <Modal
        open={modalOpen}
        title={editingStrategy ? '编辑定价策略' : '新建定价策略'}
        onOk={handleSave}
        onCancel={() => setModalOpen(false)}
        width={700}
        okText="保存"
        cancelText="取消"
        destroyOnClose
      >
        <Form form={form} layout="vertical" style={{ marginTop: 16 }}>
          <Form.Item name="name" label="策略名称" rules={[{ required: true, message: '请输入策略名称' }]}>
            <Input placeholder="如：大客户专属折扣" />
          </Form.Item>
          <div style={{ display: 'flex', gap: 16 }}>
            <Form.Item name="type" label="策略类型" style={{ flex: 1 }}>
              <Select
                options={[
                  { value: 'DISCOUNT', label: '折扣' },
                  { value: 'REBATE', label: '返利' },
                ]}
                defaultValue="DISCOUNT"
              />
            </Form.Item>
            <Form.Item name="priority" label="优先级" style={{ flex: 1 }}>
              <InputNumber min={1} style={{ width: '100%' }} placeholder="1" />
            </Form.Item>
          </div>
          <div style={{ display: 'flex', gap: 16 }}>
            <Form.Item name="baseDiscount" label="基础折扣(%)" style={{ flex: 1 }}>
              <InputNumber min={0} max={100} precision={2} style={{ width: '100%' }} placeholder="100" />
            </Form.Item>
            <Form.Item name="minOrderAmount" label="最小订单金额(¥)" style={{ flex: 1 }}>
              <InputNumber min={0} precision={2} style={{ width: '100%' }} placeholder="0" />
            </Form.Item>
          </div>
          <div style={{ display: 'flex', gap: 16 }}>
            <Form.Item name="effectiveDate" label="生效日期" style={{ flex: 1 }}>
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="expirationDate" label="到期日期" style={{ flex: 1 }}>
              <DatePicker style={{ width: '100%' }} />
            </Form.Item>
          </div>

          <Divider style={{ margin: '8px 0 12px' }}>
            阶梯规则
            <Button type="link" icon={<PlusOutlined />} onClick={addRule} style={{ marginLeft: 8 }}>
              添加规则
            </Button>
          </Divider>

          {rules.length > 0 && (
            <Table
              size="small"
              dataSource={rules}
              rowKey="_key"
              pagination={false}
              columns={ruleColumns}
            />
          )}
          {rules.length === 0 && (
            <div style={{ textAlign: 'center', color: '#999', padding: '12px 0' }}>
              暂无阶梯规则（可选）
            </div>
          )}
        </Form>
      </Modal>
    </Layout>
  );
};

export default PricingStrategy;
