import React, { useEffect, useState } from 'react';
import {
  Button, Drawer, Form, Input, Select, Space, Tag, Popconfirm,
  message, Tabs, InputNumber, Divider, Typography, Table,
} from 'antd';
import { PlusOutlined, DeleteOutlined, EditOutlined, StopOutlined, StarFilled, StarOutlined } from '@ant-design/icons';
import { customerService } from '../../services/customerService';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';

const { Text } = Typography;

const levelMap: Record<string, { label: string; color: string }> = {
  DIAMOND: { label: '钻石', color: 'purple' },
  VIP:     { label: 'VIP',  color: 'gold' },
  GOLD:    { label: '黄金',  color: 'orange' },
  SILVER:  { label: '白银',  color: 'default' },
  STANDARD:{ label: '标准',  color: 'blue' },
};

const LEVELS = ['DIAMOND', 'VIP', 'GOLD', 'SILVER', 'STANDARD'];

interface Contact {
  id?: string;
  name: string;
  role?: string;
  phone: string;
  email?: string;
  wechat?: string;
  isPrimary?: boolean;
}

const CustomerManagement: React.FC = () => {
  const [data, setData] = useState<any>({ content: [], totalElements: 0 });
  const [loading, setLoading] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editingCustomer, setEditingCustomer] = useState<any>(null);
  const [form] = Form.useForm();
  const [contactForm] = Form.useForm();
  const [params, setParams] = useState({ page: 0, size: 20, level: '', status: '', keyword: '' });
  const [contacts, setContacts] = useState<Contact[]>([]);
  const [contactDrawerOpen, setContactDrawerOpen] = useState(false);
  const [editingContact, setEditingContact] = useState<Contact | null>(null);
  const [currentCustomerId, setCurrentCustomerId] = useState<string | null>(null);

  const fetchData = async () => {
    setLoading(true);
    try {
      const res = await customerService.list(params);
      setData(res.data);
    } catch (err: any) {
      message.error(err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchData(); }, [params]);

  const openDrawer = async (customer?: any) => {
    if (customer) {
      try {
        const res = await customerService.getById(customer.id);
        const fullCustomer = res.data;
        setEditingCustomer(fullCustomer);
        setContacts(fullCustomer.contacts || []);
        setCurrentCustomerId(fullCustomer.id);
      } catch (err: any) {
        message.error('加载客户详情失败: ' + (err.message || ''));
        return;
      }
    } else {
      setEditingCustomer(null);
      setContacts([]);
      setCurrentCustomerId(null);
    }
    setDrawerOpen(true);
  };

  useEffect(() => {
    if (drawerOpen && editingCustomer) {
      setTimeout(() => {
        form.setFieldsValue({
          name: editingCustomer.name,
          level: editingCustomer.level,
          industry: editingCustomer.industry,
          region: editingCustomer.region,
          address: editingCustomer.address,
          creditLimit: editingCustomer.creditLimit,
          paymentMethod: editingCustomer.paymentMethod,
          remarks: editingCustomer.remarks,
        });
      }, 50);
    } else if (drawerOpen && !editingCustomer) {
      setTimeout(() => form.resetFields(), 50);
    }
  }, [drawerOpen, editingCustomer]);

  const handleSave = async (values: any) => {
    try {
      if (editingCustomer) {
        await customerService.update(editingCustomer.id, values);
        message.success('更新成功');
      } else {
        if (contacts.length === 0) {
          message.error('至少需要添加一个联系人');
          return;
        }
        const hasPrimary = contacts.some(c => c.isPrimary);
        if (!hasPrimary) {
          message.error('至少需要设置一个主要联系人');
          return;
        }
        await customerService.create({ ...values, contacts });
        message.success('创建成功');
      }
      setDrawerOpen(false);
      setEditingCustomer(null);
      setContacts([]);
      fetchData();
    } catch (err: any) {
      message.error(err.message);
    }
  };

  // Contact management within create drawer (local state)
  const addLocalContact = (values: any) => {
    const newContact: Contact = { ...values };
    if (contacts.length === 0) {
      newContact.isPrimary = true;
    }
    setContacts(prev => [...prev, newContact]);
    contactForm.resetFields();
    setContactDrawerOpen(false);
  };

  const removeLocalContact = (index: number) => {
    const updated = contacts.filter((_, i) => i !== index);
    if (contacts[index].isPrimary && updated.length > 0) {
      updated[0].isPrimary = true;
    }
    setContacts(updated);
  };

  const setPrimaryLocal = (index: number) => {
    setContacts(prev => prev.map((c, i) => ({ ...c, isPrimary: i === index })));
  };

  const handleAddContact = async (values: any) => {
    if (!currentCustomerId) return;
    try {
      await customerService.createContact(currentCustomerId, values);
      message.success('联系人已添加');
      const res = await customerService.listContacts(currentCustomerId);
      setContacts(res.data);
      contactForm.resetFields();
      setContactDrawerOpen(false);
    } catch (err: any) {
      message.error(err.message);
    }
  };

  const handleUpdateContact = async (values: any) => {
    if (!currentCustomerId || !editingContact?.id) return;
    try {
      await customerService.updateContact(currentCustomerId, editingContact.id, values);
      message.success('联系人已更新');
      const res = await customerService.listContacts(currentCustomerId);
      setContacts(res.data);
      contactForm.resetFields();
      setContactDrawerOpen(false);
      setEditingContact(null);
    } catch (err: any) {
      message.error(err.message);
    }
  };

  const handleDeleteContact = async (contactId: string) => {
    if (!currentCustomerId) return;
    try {
      await customerService.deleteContact(currentCustomerId, contactId);
      message.success('联系人已删除');
      const res = await customerService.listContacts(currentCustomerId);
      setContacts(res.data);
    } catch (err: any) {
      message.error(err.message);
    }
  };

  const handleSetPrimary = async (contactId: string) => {
    if (!currentCustomerId) return;
    try {
      await customerService.setPrimary(currentCustomerId, contactId);
      message.success('已设为主要联系人');
      const res = await customerService.listContacts(currentCustomerId);
      setContacts(res.data);
    } catch (err: any) {
      message.error(err.message);
    }
  };

  const contactColumns = [
    { title: '姓名', dataIndex: 'name', key: 'name' },
    { title: '职务', dataIndex: 'role', key: 'role' },
    { title: '电话', dataIndex: 'phone', key: 'phone' },
    { title: '邮箱', dataIndex: 'email', key: 'email' },
    {
      title: '主要联系人', dataIndex: 'isPrimary', key: 'isPrimary',
      render: (v: boolean) => v ? <StarFilled style={{ color: '#faad14' }} /> : <StarOutlined />
    },
    {
      title: '操作', key: 'actions',
      render: (_: any, record: Contact, index: number) => {
        if (editingCustomer) {
          return (
            <Space>
              <a onClick={() => {
                setEditingContact(record);
                contactForm.setFieldsValue(record);
                setContactDrawerOpen(true);
              }}>编辑</a>
              {!record.isPrimary && (
                <a onClick={() => record.id && handleSetPrimary(record.id)}>设主要</a>
              )}
              <Popconfirm title="确认删除？" onConfirm={() => record.id && handleDeleteContact(record.id)}>
                <a style={{ color: 'red' }}>删除</a>
              </Popconfirm>
            </Space>
          );
        }
        return (
          <Space>
            {!record.isPrimary && <a onClick={() => setPrimaryLocal(index)}>设主要</a>}
            <a style={{ color: 'red' }} onClick={() => removeLocalContact(index)}>删除</a>
          </Space>
        );
      }
    },
  ];

  // 列定义 —— 客户名称作为主入口（点击打开编辑 Drawer）
  const columns = [
    {
      title: '客户名称', dataIndex: 'name', key: 'name',
      render: (name: string, record: any) => (
        <a onClick={(e) => { e.stopPropagation(); openDrawer(record); }} style={{ fontWeight: 500 }}>{name}</a>
      ),
    },
    { title: '客户编码', dataIndex: 'code', key: 'code' },
    {
      title: '等级', dataIndex: 'level', key: 'level',
      render: (l: string) => <Tag color={levelMap[l]?.color}>{levelMap[l]?.label || l}</Tag>
    },
    { title: '行业', dataIndex: 'industry', key: 'industry' },
    { title: '区域', dataIndex: 'region', key: 'region' },
    {
      title: '状态', dataIndex: 'status', key: 'status',
      render: (s: string) => <Tag color={s === 'ACTIVE' ? 'green' : 'default'}>{s === 'ACTIVE' ? '启用' : '停用'}</Tag>
    },
  ];

  // 工具栏动作
  const actions: ToolbarAction<any>[] = [
    {
      key: 'edit',
      label: '编辑',
      icon: <EditOutlined />,
      enabledWhen: (rows) => rows.length === 1 ? true : '编辑一次只能选一行',
      onClick: (rows) => openDrawer(rows[0]),
    },
    {
      key: 'disable',
      label: '停用',
      icon: <StopOutlined />,
      danger: true,
      enabledWhen: (rows) => {
        if (rows.length === 0) return false;
        if (rows.some((r: any) => r.status !== 'ACTIVE')) return '仅启用状态的可停用';
        return true;
      },
      needsConfirm: true,
      confirmTitle: '确认停用选中的 {N} 个客户？',
      confirmDescription: '⚠️ 停用后客户不能用于新建报价单；已存在的历史报价单不受影响。',
      onClick: async (rows) => {
        if (rows.length === 1) {
          // 单条走 delete（语义同停用）
          try {
            await customerService.delete(rows[0].id);
            message.success('已停用');
          } catch (err: any) {
            message.error(err.message);
          }
        } else {
          // 多条走 batchDelete（后端原本就是停用语义）
          try {
            await customerService.batchDelete(rows.map((r: any) => r.id));
            message.success(`已停用 ${rows.length} 条记录`);
          } catch (err: any) {
            message.error(err.message);
          }
        }
        fetchData();
      },
    },
  ];

  const levelTabs = [
    { key: '', label: '全部' },
    ...LEVELS.map(l => ({ key: l, label: levelMap[l]?.label || l })),
  ];

  const toolbar = (
    <>
      <Space wrap>
        <Input.Search
          placeholder="搜索客户名称/编码"
          onSearch={v => setParams(p => ({ ...p, keyword: v, page: 0 }))}
          allowClear
          style={{ width: 260 }}
        />
        <Select
          placeholder="状态"
          allowClear
          style={{ width: 100 }}
          onChange={v => setParams(p => ({ ...p, status: v || '', page: 0 }))}
        >
          <Select.Option value="ACTIVE">启用</Select.Option>
          <Select.Option value="INACTIVE">停用</Select.Option>
        </Select>
      </Space>
      <Button type="primary" icon={<PlusOutlined />} onClick={() => openDrawer()}>新增客户</Button>
    </>
  );

  return (
    <div>
      <Tabs
        items={levelTabs.map(t => ({ key: t.key, label: t.label }))}
        activeKey={params.level}
        onChange={v => setParams(p => ({ ...p, level: v, page: 0 }))}
        style={{ marginBottom: 8 }}
      />

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
          showTotal: total => `共 ${total} 条`,
          onChange: (p, s) => setParams(prev => ({ ...prev, page: p - 1, size: s || 20 })),
        }}
        toolbar={toolbar}
        actions={actions}
        rowLabel={(r: any) => `${r.name}${r.code ? ' (' + r.code + ')' : ''}`}
        getCheckboxProps={(r: any) => ({ disabled: r.status === 'INACTIVE' })}
      />

      <Drawer
        title={editingCustomer ? '编辑客户' : '新增客户'}
        open={drawerOpen}
        onClose={() => { setDrawerOpen(false); setEditingCustomer(null); setContacts([]); }}
        size="large"
        destroyOnClose
      >
        <Form form={form} layout="vertical" onFinish={handleSave}>
          <Divider orientation="left" plain>基本信息</Divider>
          <Form.Item name="name" label="客户名称" rules={[{ required: true, message: '请输入客户名称' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="level" label="客户等级">
            <Select placeholder="请选择等级">
              {LEVELS.map(l => (
                <Select.Option key={l} value={l}>
                  <Tag color={levelMap[l]?.color}>{levelMap[l]?.label}</Tag>
                </Select.Option>
              ))}
            </Select>
          </Form.Item>
          <Form.Item name="industry" label="所属行业">
            <Input />
          </Form.Item>
          <Form.Item name="region" label="所属区域">
            <Input />
          </Form.Item>
          <Form.Item name="address" label="地址">
            <Input.TextArea rows={2} />
          </Form.Item>
          <Form.Item name="paymentMethod" label="付款方式">
            <Input />
          </Form.Item>
          <Form.Item name="remarks" label="备注">
            <Input.TextArea rows={2} />
          </Form.Item>

          <Divider orientation="left" plain>统计信息（只读）</Divider>
          {editingCustomer && (
            <Space wrap style={{ marginBottom: 16 }}>
              <Text type="secondary">累计金额：</Text>
              <Text strong>{editingCustomer.accumulatedAmount ?? 0}</Text>
              <Text type="secondary" style={{ marginLeft: 16 }}>信用额度：</Text>
              <Text strong>{editingCustomer.creditLimit ?? '-'}</Text>
              <Text type="secondary" style={{ marginLeft: 16 }}>历史订单数：</Text>
              <Text strong>{editingCustomer.quotationCount ?? 'N/A'}</Text>
              <Text type="secondary" style={{ marginLeft: 16 }}>平均折扣率：</Text>
              <Text strong>{editingCustomer.avgDiscountRate != null ? `${Number(editingCustomer.avgDiscountRate).toFixed(1)}%` : 'N/A'}</Text>
            </Space>
          )}
          <Form.Item name="creditLimit" label="信用额度">
            <InputNumber style={{ width: '100%' }} precision={2} min={0} />
          </Form.Item>

          <Divider orientation="left" plain>联系人</Divider>
          <div style={{ marginBottom: 8 }}>
            <Button
              size="small"
              icon={<PlusOutlined />}
              onClick={() => {
                setEditingContact(null);
                contactForm.resetFields();
                setContactDrawerOpen(true);
              }}
            >
              添加联系人
            </Button>
          </div>
          <Table
            size="small"
            columns={contactColumns}
            dataSource={contacts}
            rowKey={(r: any) => r.id || r.phone}
            pagination={false}
          />

          <div style={{ marginTop: 24 }}>
            <Button type="primary" htmlType="submit" block>保存</Button>
          </div>
        </Form>
      </Drawer>

      <Drawer
        title={editingContact ? '编辑联系人' : '添加联系人'}
        open={contactDrawerOpen}
        onClose={() => { setContactDrawerOpen(false); setEditingContact(null); }}
        size="default"
        destroyOnClose
      >
        <Form
          form={contactForm}
          layout="vertical"
          onFinish={editingCustomer
            ? (editingContact ? handleUpdateContact : handleAddContact)
            : addLocalContact
          }
        >
          <Form.Item name="name" label="姓名" rules={[{ required: true, message: '请输入姓名' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="role" label="职务">
            <Input />
          </Form.Item>
          <Form.Item
            name="phone"
            label="电话"
            rules={[
              { required: true, message: '请输入电话' },
              { pattern: /^\d{11}$/, message: '请输入11位手机号' },
            ]}
          >
            <Input maxLength={11} />
          </Form.Item>
          <Form.Item name="email" label="邮箱" rules={[{ type: 'email', message: '请输入有效邮箱' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="wechat" label="微信">
            <Input />
          </Form.Item>
          <Form.Item name="isPrimary" label="设为主要联系人" valuePropName="checked">
            <input type="checkbox" />
          </Form.Item>
          <Button type="primary" htmlType="submit" block>保存</Button>
        </Form>
      </Drawer>
    </div>
  );
};

export default CustomerManagement;
