import React, { useEffect, useState } from 'react';
import {
  Alert, Button, Card, DatePicker, Descriptions, Drawer, Form, Input, InputNumber,
  Modal, Popconfirm, Space, Switch, Table, Tabs, Tag, Upload, message,
} from 'antd';
import { DeleteOutlined, DownloadOutlined, EditOutlined, HistoryOutlined, PlusOutlined, UploadOutlined } from '@ant-design/icons';
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
  const [rateView, setRateView] = useState('detail');
  const [rateEditing, setRateEditing] = useState<ExchangeRateDTO | null>(null);
  const [rateModalOpen, setRateModalOpen] = useState(false);
  const [rateSaving, setRateSaving] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [importCode, setImportCode] = useState<string | null>(null);
  const [form] = Form.useForm();
  const [rateForm] = Form.useForm();

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
    setHistoryCode(code); setRateView('detail'); setHistoryLoading(true);
    try { const res = await currencyService.rates(code); setHistory(res.data?.items ?? []); }
    catch (e: any) { message.error(e?.message ?? '加载历史汇率失败'); setHistory([]); }
    finally { setHistoryLoading(false); }
  };

  const openRateEditor = (row?: ExchangeRateDTO) => {
    setRateEditing(row ?? null);
    rateForm.setFieldsValue(row ? { rate: Number(row.rate), effectiveDate: dayjs(row.effectiveDate), source: row.source } : { rate: undefined, effectiveDate: dayjs(), source: '' });
    setRateModalOpen(true);
  };

  const saveRate = async () => {
    if (!historyCode) return;
    try {
      const v = await rateForm.validateFields();
      setRateSaving(true);
      const payload = { rate: String(v.rate), effectiveDate: v.effectiveDate.format('YYYY-MM-DD'), source: v.source || undefined };
      if (rateEditing?.id) await currencyService.updateRate(historyCode, rateEditing.id, payload);
      else await currencyService.addRate(historyCode, payload);
      message.success(rateEditing ? '汇率已更新' : '汇率已新增');
      setRateModalOpen(false);
      await openHistory(historyCode);
      await load();
    } catch (e: any) {
      if (!e?.errorFields) message.error(e?.response?.data?.message ?? e?.message ?? '保存汇率失败');
    } finally { setRateSaving(false); }
  };

  const downloadTemplate = async () => {
    if (!importCode) return;
    try {
      const blob = await currencyService.downloadRateTemplate(importCode);
      const url = URL.createObjectURL(blob); const a = document.createElement('a');
      a.href = url; a.download = `${importCode}-汇率导入模板.xlsx`; document.body.appendChild(a); a.click(); a.remove(); URL.revokeObjectURL(url);
    } catch (e: any) { message.error(e?.message ?? '模板下载失败'); }
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
  const matrixByDate = new Map<string, ExchangeRateDTO>();
  history.forEach((row) => { if (!matrixByDate.has(row.effectiveDate)) matrixByDate.set(row.effectiveDate, row); });
  const matrixDates = Array.from(matrixByDate.keys()).sort().reverse();

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
    <Drawer title={`${historyCode ?? ''} 汇率`} open={!!historyCode} onClose={() => setHistoryCode(null)} width={1000} extra={historyCode && <Space><Button icon={<UploadOutlined />} onClick={() => { setImportCode(historyCode); setImportOpen(true); }}>导入汇率</Button><Button type="primary" icon={<PlusOutlined />} onClick={() => openRateEditor()}>新增汇率</Button></Space>}>
      <Tabs activeKey={rateView} onChange={setRateView} items={[
        { key: 'detail', label: '明细', children: <Table loading={historyLoading} rowKey={(r) => `${r.id ?? ''}-${r.effectiveDate}-${r.updatedAt ?? ''}`} dataSource={history} pagination={{ pageSize: 20 }} columns={[
          { title: '生效日期', dataIndex: 'effectiveDate' },
          { title: '汇率（目标币种 → CNY）', dataIndex: 'rate', render: (v: string) => formatRate(v) },
          { title: '来源', dataIndex: 'source', render: (v: string) => v || '-' },
          { title: '更新时间', dataIndex: 'updatedAt', render: (v: string) => v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-' },
          { title: '状态', dataIndex: 'status', render: (v: string, _r: ExchangeRateDTO, index: number) => <Tag color={index === 0 ? 'green' : 'default'}>{index === 0 ? '当天有效' : (v || '历史')}</Tag> },
          { title: '操作', key: 'action', render: (_: unknown, r: ExchangeRateDTO) => <Button type="link" icon={<EditOutlined />} onClick={() => openRateEditor(r)}>编辑</Button> },
        ]} /> },
        { key: 'matrix', label: '矩阵', children: <Table loading={historyLoading} rowKey="currency" dataSource={historyCode ? [{ currency: historyCode }] : []} pagination={false} scroll={{ x: 'max-content' }} columns={[
          { title: '币种', dataIndex: 'currency', fixed: 'left' as const, width: 100 },
          ...matrixDates.map((date) => ({ title: date.slice(5), key: date, align: 'right' as const, render: () => { const row = matrixByDate.get(date); return row ? formatRate(row.rate) : <span style={{ color: 'rgba(0,0,0,.45)' }}>—</span>; } })),
        ]} /> },
      ]} />
      <Descriptions size="small" column={1} style={{ marginTop: 16 }}><Descriptions.Item label="说明">同一生效日期允许多次更新，策略取当天最后更新的一条；汇率保存 12 位小数。</Descriptions.Item></Descriptions>
    </Drawer>
    <Modal open={rateModalOpen} title={rateEditing ? '编辑汇率' : '新增汇率'} onCancel={() => setRateModalOpen(false)} onOk={saveRate} confirmLoading={rateSaving} destroyOnClose>
      <Form form={rateForm} layout="vertical">
        <Form.Item name="effectiveDate" label="生效日期" rules={[{ required: true, message: '请选择生效日期' }]}><DatePicker style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="rate" label="汇率（1 目标币种 = N CNY）" rules={[{ required: true, message: '请输入汇率' }]}><InputNumber min={0} precision={12} style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="source" label="来源"><Input maxLength={200} /></Form.Item>
      </Form>
    </Modal>
    <Drawer title={`导入 ${importCode ?? ''} 汇率`} open={importOpen} onClose={() => setImportOpen(false)} size="default">
      <Alert type="info" showIcon message="Excel 列：生效日期*、汇率*、来源；整批校验，失败不会写入。" style={{ marginBottom: 16 }} />
      <Button icon={<DownloadOutlined />} onClick={downloadTemplate} style={{ marginBottom: 16 }}>下载导入模板</Button>
      <Upload beforeUpload={handleImport} maxCount={1} accept=".xlsx,.xls"><Button icon={<UploadOutlined />}>选择 Excel 文件</Button></Upload>
    </Drawer>
  </Card>;
};

export default CurrencyManagement;
