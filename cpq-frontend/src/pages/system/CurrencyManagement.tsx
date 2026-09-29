import React, { useEffect, useState } from 'react';
import {
  Alert, Button, Card, DatePicker, Descriptions, Drawer, Form, Input, InputNumber,
  Modal, Popconfirm, Space, Switch, Table, Tag, Upload, message,
} from 'antd';
import { DeleteOutlined, EditOutlined, HistoryOutlined, PlusOutlined, UploadOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { currencyService } from '../../services/currencyService';
import type { CurrencyDTO, ExchangeRateDTO } from '../../types/currency';

const formatRate = (value: unknown) => {
  const n = Number(value);
  return Number.isFinite(n) ? n.toFixed(12) : String(value ?? '-');
};

const CurrencyManagement: React.FC = () => {
  const [currencies, setCurrencies] = useState<CurrencyDTO[]>([]);
  const [loading, setLoading] = useState(false);
  const [editing, setEditing] = useState<CurrencyDTO | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [historyCode, setHistoryCode] = useState<string | null>(null);
  const [history, setHistory] = useState<ExchangeRateDTO[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [importCode, setImportCode] = useState<string | null>(null);
  const [form] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try {
      const res = await currencyService.list();
      setCurrencies(res.data ?? []);
    } catch (e: any) {
      message.error(e?.message ?? '加载币种失败');
    } finally { setLoading(false); }
  };

  useEffect(() => { load(); }, []);

  const openEdit = (currency?: CurrencyDTO) => {
    setEditing(currency ?? null);
    form.setFieldsValue(currency ? currency : { code: '', name: '', symbol: '', sortOrder: 100 });
    setModalOpen(true);
  };

  const save = async () => {
    try {
      const values = await form.validateFields();
      if (editing) {
        await currencyService.update(editing.code, { name: values.name, symbol: values.symbol, sortOrder: values.sortOrder, enabled: values.enabled });
      } else {
        await currencyService.create({ code: String(values.code).trim().toUpperCase(), name: values.name, symbol: values.symbol, sortOrder: values.sortOrder });
      }
      message.success('已保存'); setModalOpen(false); load();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.response?.data?.message ?? e?.message ?? '保存失败');
    }
  };

  const openHistory = async (code: string) => {
    setHistoryCode(code); setHistoryLoading(true);
    try { const res = await currencyService.rates(code); setHistory(res.data?.items ?? []); }
    catch (e: any) { message.error(e?.message ?? '加载历史汇率失败'); setHistory([]); }
    finally { setHistoryLoading(false); }
  };

  const handleImport = async (file: File) => {
    if (!importCode) return false;
    try { await currencyService.importRates(importCode, file); message.success('汇率导入成功'); setImportOpen(false); if (historyCode === importCode) openHistory(importCode); }
    catch (e: any) { message.error(e?.response?.data?.message ?? e?.message ?? '汇率导入失败'); }
    return false;
  };

  const columns = [
    { title: '币种代码', dataIndex: 'code', width: 110, render: (v: string, r: CurrencyDTO) => <Space><b>{v}</b>{r.isBase && <Tag color="blue">基准</Tag>}</Space> },
    { title: '币种名称', dataIndex: 'name', width: 140 },
    { title: '汇率（目标币种 → CNY）', key: 'rate', render: (_: unknown, r: CurrencyDTO) => r.isBase ? '1.000000000000' : `1 ${r.code} = ${formatRate(r.latestRate)} CNY` },
    { title: '生效日期', dataIndex: 'latestRateDate', width: 130, render: (v: string) => v || '-' },
    { title: '状态', dataIndex: 'enabled', width: 90, render: (v: boolean) => <Tag color={v ? 'green' : 'default'}>{v ? '启用' : '停用'}</Tag> },
    { title: '操作', key: 'action', width: 270, render: (_: unknown, r: CurrencyDTO) => <Space>
      <Button type="link" icon={<HistoryOutlined />} onClick={() => openHistory(r.code)}>历史汇率</Button>
      {!r.isBase && <Button type="link" icon={<EditOutlined />} onClick={() => openEdit(r)}>编辑</Button>}
      {!r.isBase && <Button type="link" icon={<UploadOutlined />} onClick={() => { setImportCode(r.code); setImportOpen(true); }}>导入汇率</Button>}
      {!r.isBase && <Popconfirm title="确认删除该币种？" onConfirm={async () => { try { await currencyService.remove(r.code); message.success('已删除'); load(); } catch (e: any) { message.error(e?.response?.data?.message ?? '删除失败'); } }}><Button type="link" danger icon={<DeleteOutlined />}>删除</Button></Popconfirm>}
    </Space> },
  ];

  return <Card title="币种管理" extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => openEdit()}>新增币种</Button>}>
    <Alert type="info" showIcon message="汇率方向：1 目标币种 = N CNY；报价单创建时固化汇率快照。" style={{ marginBottom: 16 }} />
    <Table rowKey="code" loading={loading} dataSource={currencies} columns={columns} pagination={false} />
    <Modal open={modalOpen} title={editing ? '编辑币种' : '新增币种'} onCancel={() => setModalOpen(false)} onOk={save} destroyOnClose>
      <Form form={form} layout="vertical">
        <Form.Item name="code" label="币种代码" rules={[{ required: true, len: 3, message: '请输入 3 位 ISO 币种代码' }]}><Input disabled={!!editing} maxLength={3} /></Form.Item>
        <Form.Item name="name" label="币种名称" rules={[{ required: true, message: '请输入币种名称' }]}><Input /></Form.Item>
        <Form.Item name="symbol" label="符号"><Input maxLength={8} /></Form.Item>
        <Form.Item name="sortOrder" label="排序"><InputNumber min={0} precision={0} style={{ width: '100%' }} /></Form.Item>
        {editing && !editing.isBase && <Form.Item name="enabled" label="状态" valuePropName="checked"><Switch checkedChildren="启用" unCheckedChildren="停用" /></Form.Item>}
      </Form>
    </Modal>
    <Drawer title={`${historyCode ?? ''} 历史汇率`} open={!!historyCode} onClose={() => setHistoryCode(null)} size="large" extra={historyCode && <Button icon={<UploadOutlined />} onClick={() => { setImportCode(historyCode); setImportOpen(true); }}>导入汇率</Button>}>
      <Table loading={historyLoading} rowKey={(r) => `${r.id ?? ''}-${r.effectiveDate}-${r.updatedAt ?? ''}`} dataSource={history} pagination={{ pageSize: 20 }} columns={[
        { title: '生效日期', dataIndex: 'effectiveDate' },
        { title: '汇率', dataIndex: 'rate', render: (v: string) => formatRate(v) },
        { title: '来源', dataIndex: 'source', render: (v: string) => v || '-' },
        { title: '更新时间', dataIndex: 'updatedAt', render: (v: string) => v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-' },
        { title: '状态', dataIndex: 'status', render: (v: string, _r: ExchangeRateDTO, index: number) => <Tag color={index === 0 ? 'green' : 'default'}>{index === 0 ? '当天有效' : (v || '历史')}</Tag> },
      ]} />
      <Descriptions size="small" column={1} style={{ marginTop: 16 }}><Descriptions.Item label="说明">同一生效日期允许多次更新，策略取当天最后更新的一条。</Descriptions.Item></Descriptions>
    </Drawer>
    <Drawer title={`导入 ${importCode ?? ''} 汇率`} open={importOpen} onClose={() => setImportOpen(false)} size="default">
      <Alert type="info" showIcon message="Excel 列：生效日期*、汇率*、来源；整批校验，失败不会写入。" style={{ marginBottom: 16 }} />
      <Upload beforeUpload={handleImport} maxCount={1} accept=".xlsx,.xls"><Button icon={<UploadOutlined />}>选择 Excel 文件</Button></Upload>
    </Drawer>
  </Card>;
};

export default CurrencyManagement;
