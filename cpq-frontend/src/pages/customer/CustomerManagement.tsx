import React, { useEffect, useState } from 'react';
import {
  Button, Drawer, Form, Input, Select, Space, Tag, Popconfirm,
  message, Tabs, InputNumber, Divider, Typography, Table, Upload, Tooltip, Alert,
} from 'antd';
import { PlusOutlined, EditOutlined, StopOutlined, StarFilled, StarOutlined, UploadOutlined, DownloadOutlined, CheckCircleFilled, CloseCircleFilled, LoadingOutlined } from '@ant-design/icons';
import { customerService } from '../../services/customerService';
import { industryService } from '../../services/industryService';
import { productCategoryService, type ProductCategory } from '../../services/productCategoryService';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';
import { formatDisplayDecimal, isDecimalString } from '../../utils/precision';

const { Text } = Typography;

const levelMap: Record<string, { label: string; color: string }> = {
  DIAMOND: { label: '钻石', color: 'purple' },
  VIP:     { label: 'VIP',  color: 'gold' },
  GOLD:    { label: '黄金',  color: 'orange' },
  SILVER:  { label: '白银',  color: 'default' },
  STANDARD:{ label: '标准',  color: 'blue' },
};

const LEVELS = ['DIAMOND', 'VIP', 'GOLD', 'SILVER', 'STANDARD'];

type ImportState = 'upload' | 'uploading' | 'success' | 'error';

interface ImportError {
  row?: number;
  column?: string;
  reason?: string;
}

interface ImportResult {
  totalRows: number;
  createdCustomers: number;
  createdContacts: number;
  errors?: ImportError[];
}

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
  const [industries, setIndustries] = useState<{ code: string; name: string }[]>([]);
  const [categories, setCategories] = useState<ProductCategory[]>([]);
  const [importDrawerOpen, setImportDrawerOpen] = useState(false);
  const [importState, setImportState] = useState<ImportState>('upload');
  const [importFile, setImportFile] = useState<File | null>(null);
  const [importResult, setImportResult] = useState<ImportResult | null>(null);
  const [importErrors, setImportErrors] = useState<ImportError[]>([]);

  useEffect(() => {
    industryService.listActive().then(res => setIndustries(res?.data ?? [])).catch(() => {});
  }, []);

  useEffect(() => {
    productCategoryService.list('ACTIVE').then(res => setCategories(res?.data ?? [])).catch(() => {});
  }, []);

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

  const openImportDrawer = () => {
    setImportDrawerOpen(true);
    setImportState('upload');
    setImportFile(null);
    setImportResult(null);
    setImportErrors([]);
  };

  const closeImportDrawer = () => {
    if (importState === 'uploading') return;
    setImportDrawerOpen(false);
    setImportFile(null);
    setImportResult(null);
    setImportErrors([]);
    setImportState('upload');
  };

  const handleDownloadTemplate = async () => {
    try {
      const blob = await customerService.downloadImportTemplate();
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = '客户导入模板.xlsx';
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
    } catch (err: any) {
      message.error(err.message || '模板下载失败');
    }
  };

  const handleImport = async () => {
    if (!importFile) {
      message.warning('请先选择 .xlsx 文件');
      return;
    }
    setImportState('uploading');
    setImportErrors([]);
    try {
      const response = await customerService.importCustomers(importFile);
      const result: ImportResult = response?.data ?? response;
      setImportResult({
        totalRows: Number(result?.totalRows ?? 0),
        createdCustomers: Number(result?.createdCustomers ?? 0),
        createdContacts: Number(result?.createdContacts ?? 0),
        errors: result?.errors ?? [],
      });
      setImportState('success');
      await fetchData();
    } catch (err: any) {
      const payload = err?.payload;
      const errors = Array.isArray(payload?.errors) ? payload.errors : [];
      setImportErrors(errors);
      setImportResult({
        totalRows: Number(payload?.totalRows ?? 0),
        createdCustomers: 0,
        createdContacts: 0,
        errors,
      });
      setImportState('error');
      if (!errors.length && err?.message) message.error(err.message);
    }
  };

  useEffect(() => {
    if (drawerOpen && editingCustomer) {
      setTimeout(() => {
        form.setFieldsValue({
          code: editingCustomer.code,
          name: editingCustomer.name,
          level: editingCustomer.level,
          industryCode: editingCustomer.industryCode,
          productCategoryId: editingCustomer.productCategoryId,
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

  // 新建客户默认分类种子（质量评审 Minor#2+#3）：唯一职责收敛到本 effect ——
  // 覆盖"打开时 categories 已加载"和"打开时未加载、稍后到位"两种时序，且用 `!current`
  // 守卫不覆盖用户已选值。原挂载 effect 里另一份重复种子逻辑已删除（曾在 setTimeout 闭包里
  // 读可变 categories 却未入依赖，属 eslint-disable 陷阱，且与本 effect 双写冗余）。
  // 60ms 延时是必须的、不是随意值：上面 effect 里"新建"分支的 form.resetFields() 延时 50ms 执行；
  // 若本 effect 不晚于它写入（例如同步写或延时更短），会出现"categories 已预加载"这一常见路径下
  // 本 effect 先种下默认分类 → resetFields() 随后把刚写的值连同其它字段一并清空 → 默认分类种子
  // 100% 被吞掉的时序 bug（推导自代码走查，故显式延后到 resetFields 之后再读/写，而非二者同批同步执行）。
  useEffect(() => {
    if (!drawerOpen || editingCustomer || categories.length === 0) return;
    const timer = setTimeout(() => {
      const current = form.getFieldValue('productCategoryId');
      if (!current) {
        const def = categories.find(c => c.name === '默认分类');
        if (def) form.setFieldsValue({ productCategoryId: def.id });
      }
    }, 60);
    return () => clearTimeout(timer);
  }, [categories, drawerOpen, editingCustomer]);

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
    {
      title: '行业', dataIndex: 'industryCode', key: 'industryCode',
      render: (code: string, record: any) =>
        industries.find(i => i.code === code)?.name ?? record.industry ?? '—',
    },
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
        <Button icon={<UploadOutlined />} onClick={openImportDrawer}>导入客户</Button>
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
          <Divider titlePlacement="left" plain>基本信息</Divider>
          <Form.Item
            name="code"
            label="客户编号"
            extra={editingCustomer ? '客户编号创建后不可修改' : '选填，留空由系统自动生成'}
          >
            <Input maxLength={50} disabled={Boolean(editingCustomer)} placeholder="选填，例如 ABC-001" />
          </Form.Item>
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
          <Form.Item name="industryCode" label="所属行业">
            <Select
              placeholder="请选择所属行业"
              allowClear
              showSearch
              optionFilterProp="label"
              options={industries.map(i => ({ value: i.code, label: `${i.name}（${i.code}）` }))}
            />
          </Form.Item>
          <Form.Item name="productCategoryId" label="产品分类"
            rules={[{ required: true, message: '请选择产品分类' }]}>
            <Select
              showSearch
              optionFilterProp="label"
              placeholder="请选择产品分类"
              options={categories.map(c => ({ value: c.id, label: c.name }))}
            />
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

          <Divider titlePlacement="left" plain>统计信息（只读）</Divider>
          {editingCustomer && (
            <Space wrap style={{ marginBottom: 16 }}>
              <Text type="secondary">累计金额：</Text>
              <Text strong>{editingCustomer.accumulatedAmount ?? 0}</Text>
              <Text type="secondary" style={{ marginLeft: 16 }}>信用额度：</Text>
              <Text strong>{editingCustomer.creditLimit ?? '-'}</Text>
              <Text type="secondary" style={{ marginLeft: 16 }}>历史订单数：</Text>
              <Text strong>{editingCustomer.quotationCount ?? 'N/A'}</Text>
              <Text type="secondary" style={{ marginLeft: 16 }}>平均折扣率：</Text>
              <Text strong>{isDecimalString(editingCustomer.avgDiscountRate) ? `${formatDisplayDecimal(editingCustomer.avgDiscountRate, 1)}%` : 'N/A'}</Text>
            </Space>
          )}
          <Form.Item name="creditLimit" label="信用额度">
            <InputNumber style={{ width: '100%' }} precision={2} min={0} />
          </Form.Item>

          <Divider titlePlacement="left" plain>联系人</Divider>
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
        title="导入客户"
        open={importDrawerOpen}
        width={620}
        destroyOnClose
        closable={importState !== 'uploading'}
        maskClosable={importState !== 'uploading'}
        onClose={closeImportDrawer}
        extra={importState === 'uploading' ? (
          <Tooltip title="导入进行中，请等待完成后关闭">
            <LoadingOutlined spin />
          </Tooltip>
        ) : null}
      >
        {importState === 'upload' && (
          <>
            <Alert
              type="info"
              showIcon
              message="一行一个客户，同时填写一个主要联系人"
              description="客户编号可留空；客户名称、联系人姓名和联系人电话为必填项。任何一行校验失败，整份文件都不会写入。"
              style={{ marginBottom: 20 }}
            />
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              <Button icon={<DownloadOutlined />} onClick={handleDownloadTemplate}>
                下载客户导入模板
              </Button>
              <Upload
                accept=".xlsx,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                maxCount={1}
                multiple={false}
                beforeUpload={(file) => {
                  setImportFile(file as File);
                  return false;
                }}
                onRemove={() => {
                  setImportFile(null);
                  return true;
                }}
                fileList={importFile ? [{ uid: 'customer-import-file', name: importFile.name, status: 'done' } as any] : []}
              >
                <Button icon={<UploadOutlined />}>选择 .xlsx 文件</Button>
              </Upload>
              <Text type="secondary">支持 .xlsx 文件，单文件导入。建议使用下载的模板填写。</Text>
              <Button
                type="primary"
                icon={<UploadOutlined />}
                disabled={!importFile}
                onClick={handleImport}
                block
              >
                开始导入
              </Button>
            </Space>
          </>
        )}

        {importState === 'uploading' && (
          <div style={{ padding: '56px 0', textAlign: 'center' }}>
            <LoadingOutlined spin style={{ fontSize: 32, color: '#1677ff' }} />
            <Typography.Title level={5} style={{ marginTop: 20 }}>正在校验并导入客户</Typography.Title>
            <Text type="secondary">文件处理完成前无法关闭此抽屉，请稍候。</Text>
          </div>
        )}

        {importState === 'success' && importResult && (
          <div>
            <div style={{ textAlign: 'center', padding: '20px 0 28px' }}>
              <CheckCircleFilled style={{ fontSize: 48, color: '#52c41a' }} />
              <Typography.Title level={4} style={{ margin: '14px 0 8px' }}>导入成功</Typography.Title>
              <Text type="secondary">客户列表已刷新，新增数据可立即使用。</Text>
            </div>
            <Space size={0} split={<Divider type="vertical" />} style={{ width: '100%', justifyContent: 'center', marginBottom: 28 }}>
              <div style={{ textAlign: 'center', minWidth: 120 }}><Text type="secondary">读取行数</Text><Typography.Title level={3} style={{ margin: '4px 0 0' }}>{importResult.totalRows}</Typography.Title></div>
              <div style={{ textAlign: 'center', minWidth: 120 }}><Text type="secondary">新增客户</Text><Typography.Title level={3} style={{ margin: '4px 0 0' }}>{importResult.createdCustomers}</Typography.Title></div>
              <div style={{ textAlign: 'center', minWidth: 120 }}><Text type="secondary">新增联系人</Text><Typography.Title level={3} style={{ margin: '4px 0 0' }}>{importResult.createdContacts}</Typography.Title></div>
            </Space>
            <Button type="primary" block onClick={closeImportDrawer}>完成并刷新列表</Button>
          </div>
        )}

        {importState === 'error' && (
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 12 }}>
              <CloseCircleFilled style={{ fontSize: 26, color: '#ff4d4f' }} />
              <div>
                <Typography.Title level={5} style={{ margin: 0 }}>导入失败，未写入任何数据</Typography.Title>
                <Text type="secondary">请根据下方错误修正文件后重新选择并导入。</Text>
              </div>
            </div>
            <Table<ImportError>
              size="small"
              bordered
              pagination={{ pageSize: 8, showSizeChanger: false }}
              rowKey={(record, index) => `${record.row ?? 'unknown'}-${index ?? 0}`}
              dataSource={importErrors}
              locale={{ emptyText: '未返回详细错误，请查看服务端提示' }}
              columns={[
                { title: 'Excel 行号', dataIndex: 'row', width: 100, render: (value: number) => value ?? '—' },
                { title: '列名', dataIndex: 'column', width: 120, render: (value: string) => value || '—' },
                { title: '原因', dataIndex: 'reason', render: (value: string) => value || '导入校验失败' },
              ]}
            />
            <Space style={{ width: '100%', justifyContent: 'flex-end', marginTop: 20 }}>
              <Button onClick={closeImportDrawer}>关闭</Button>
              <Button type="primary" onClick={() => { setImportState('upload'); setImportErrors([]); setImportResult(null); }}>重新选择文件</Button>
            </Space>
          </div>
        )}
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
