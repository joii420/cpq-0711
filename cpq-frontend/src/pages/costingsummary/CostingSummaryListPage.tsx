/**
 * 核价单列表（Phase C）
 *
 * 列表 + 创建抽屉。状态机：DRAFT → COMPUTED → PUBLISHED → ARCHIVED。
 * 行内只有「核价单号」链接进详情；动作工具栏：删除 / 归档。
 */
import React, { useEffect, useState } from 'react';
import {
  Drawer, Form, Input, Select, Tag, Space, Button, message, Typography, Alert,
} from 'antd';
import { PlusOutlined, DeleteOutlined, InboxOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { costingSummaryService, type CostingSummary, type CostingSummaryStatus } from '../../services/costingSummaryService';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';

const { Title } = Typography;

const STATUS_LABEL: Record<string, string> = { DRAFT: '草稿', COMPUTED: '已计算', PUBLISHED: '已发布', ARCHIVED: '已归档' };
const STATUS_COLOR: Record<string, string> = { DRAFT: 'default', COMPUTED: 'blue', PUBLISHED: 'green', ARCHIVED: 'red' };

const CostingSummaryListPage: React.FC = () => {
  const navigate = useNavigate();
  const [data, setData] = useState<CostingSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [partFilter, setPartFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState<CostingSummaryStatus | undefined>();

  const [createOpen, setCreateOpen] = useState(false);
  const [form] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try {
      const res = await costingSummaryService.list({ hfPartNo: partFilter || undefined, status: statusFilter });
      setData(res.data);
    } finally { setLoading(false); }
  };
  useEffect(() => { load(); }, [partFilter, statusFilter]);

  // V105: 核价单解绑版本号. 创建表单不再选 element/material/exchange 版本,
  // 计算时统一走 v_costing_*_price 视图 (即「全局变量配置」展示的当前默认 PUBLISHED 版本).
  const openCreate = () => {
    form.resetFields();
    form.setFieldsValue({ quoteCurrency: 'USD' });
    setCreateOpen(true);
  };

  const submitCreate = async () => {
    try {
      const values = await form.validateFields();
      const res = await costingSummaryService.create(values);
      message.success(`已创建：${res.data.summaryNo}`);
      setCreateOpen(false);
      // 直接跳到详情页
      if (res.data.id) navigate(`/costing-summary/${res.data.id}`);
      load();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.message ?? '创建失败');
    }
  };

  const cols = [
    {
      title: '核价单号', dataIndex: 'summaryNo', width: 180,
      render: (v: string, r: CostingSummary) => (
        <a onClick={(e) => { e.stopPropagation(); navigate(`/costing-summary/${r.id}`); }}>{v}</a>
      ),
    },
    { title: '宏丰料号', dataIndex: 'hfPartNo', width: 130 },
    { title: '报价货币', dataIndex: 'quoteCurrency', width: 80 },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (s: string) => <Tag color={STATUS_COLOR[s]}>{STATUS_LABEL[s] || s}</Tag>,
    },
    { title: '创建时间', dataIndex: 'createdAt', width: 170,
      render: (v: string) => v ? new Date(v).toLocaleString('zh-CN') : '-' },
    { title: '计算时间', dataIndex: 'computedAt', width: 170,
      render: (v: string) => v ? new Date(v).toLocaleString('zh-CN') : '-' },
    { title: '发布时间', dataIndex: 'publishedAt', width: 170,
      render: (v: string) => v ? new Date(v).toLocaleString('zh-CN') : '-' },
    { title: '备注', dataIndex: 'notes', ellipsis: true },
  ];

  const actions: ToolbarAction<CostingSummary>[] = [
    {
      key: 'archive', label: '归档', icon: <InboxOutlined />,
      enabledWhen: (rows) => {
        if (rows.length !== 1) return '一次只能归档一个';
        if (rows[0].status !== 'PUBLISHED') return '仅已发布可归档';
        return true;
      },
      needsConfirm: true,
      confirmTitle: '确认归档？',
      onClick: async (rows) => {
        try {
          await costingSummaryService.archive(rows[0].id!);
          message.success('已归档');
          load();
        } catch (e: any) { message.error(e?.message ?? '归档失败'); }
      },
    },
    {
      key: 'delete', label: '删除', icon: <DeleteOutlined />, danger: true,
      enabledWhen: (rows) => {
        if (rows.length === 0) return false;
        if (rows.some(r => r.status === 'PUBLISHED')) return '已发布的核价单请先归档';
        return true;
      },
      needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 个核价单？',
      confirmDescription: '⚠️ 差量与计算结果一并清除（CASCADE）',
      onClick: async (rows) => {
        await runBatch(rows, (r) => costingSummaryService.delete(r.id!).then(() => undefined),
          { rowLabel: (r) => `${r.summaryNo} ${r.hfPartNo}` });
        load();
      },
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        <Title level={4} style={{ margin: 0 }}>核价单</Title>
        <span style={{ color: '#8c8c8c', fontSize: 12 }}>
          每个核价单 = 一个料号 × 当前生效的元素/材料/汇率全局变量。已发布单的指标值已冻结到结果表。状态机：草稿 → 已计算 → 已发布 → 已归档。
        </span>
      </div>

      <SelectableTable<CostingSummary>
        rowKey="id"
        columns={cols as any}
        dataSource={data}
        loading={loading}
        pagination={{ pageSize: 50 }}
        toolbar={
          <Space wrap>
            <Input.Search
              placeholder="按料号过滤"
              value={partFilter}
              onChange={(e) => setPartFilter(e.target.value)}
              onSearch={(v) => setPartFilter(v.trim())}
              style={{ width: 220 }}
              allowClear
            />
            <Select
              allowClear placeholder="按状态过滤" style={{ width: 140 }}
              value={statusFilter} onChange={setStatusFilter}
              options={['DRAFT','COMPUTED','PUBLISHED','ARCHIVED'].map(s => ({ value: s, label: STATUS_LABEL[s] }))}
            />
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建核价单</Button>
          </Space>
        }
        actions={actions}
        rowLabel={(r) => `${r.summaryNo} ${r.hfPartNo} (${STATUS_LABEL[r.status || 'DRAFT']})`}
      />

      <Drawer
        title="新建核价单"
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        width={520}
        destroyOnClose
        footer={
          <div style={{ textAlign: 'right' }}>
            <Button onClick={() => setCreateOpen(false)} style={{ marginRight: 8 }}>取消</Button>
            <Button type="primary" onClick={submitCreate}>创建</Button>
          </div>
        }
      >
        <Form form={form} layout="vertical">
          <Form.Item name="hfPartNo" label="宏丰料号" rules={[{ required: true }]}>
            <Input placeholder="如 3100080003" />
          </Form.Item>
          <Form.Item name="quoteCurrency" label="报价货币" rules={[{ required: true }]}>
            <Select options={['USD','EUR','JPY','CNY'].map(v => ({ value: v, label: v }))} />
          </Form.Item>
          <Alert
            type="info"
            showIcon
            style={{ marginBottom: 12 }}
            message="元素 / 材料 / 汇率取「全局变量配置」当前生效值"
            description="新核价单不再绑定特定版本号; 计算时实时读取全局变量当前值, 计算后结果落入快照, 后续价格变更不再追溯影响。"
          />
          <Form.Item name="notes" label="备注">
            <Input.TextArea rows={2} placeholder="本次核价的场景说明" />
          </Form.Item>
        </Form>
      </Drawer>
    </div>
  );
};

export default CostingSummaryListPage;
