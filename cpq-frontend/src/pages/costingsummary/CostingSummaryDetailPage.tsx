/**
 * 核价单详情（Phase C）
 *
 * 顶部：核价单元信息 + 状态 + 操作（计算 / 发布 / 归档 / 返回）。
 * 主体 Tab：
 *   1. 计算结果（result 表，按 sortOrder 显示 7 项）
 *   2. 用户差量（覆盖默认基础数据值；DRAFT/COMPUTED 可改）
 */
import React, { useEffect, useState } from 'react';
import {
  Card, Tag, Button, Space, Spin, Descriptions, Tabs, Drawer, Form, Input,
  InputNumber, Select, message, Empty, Typography, Statistic, Row, Col, Alert,
} from 'antd';
import {
  ArrowLeftOutlined, PlayCircleOutlined, CheckCircleOutlined, InboxOutlined,
  PlusOutlined, EditOutlined, DeleteOutlined,
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import {
  costingSummaryService,
  type CostingSummary, type CostingSummaryOverride, type CostingSummaryResult,
} from '../../services/costingSummaryService';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';

const { Title, Text } = Typography;

const STATUS_LABEL: Record<string, string> = { DRAFT: '草稿', COMPUTED: '已计算', PUBLISHED: '已发布', ARCHIVED: '已归档' };
const STATUS_COLOR: Record<string, string> = { DRAFT: 'default', COMPUTED: 'blue', PUBLISHED: 'green', ARCHIVED: 'red' };

const KIND_LABEL: Record<string, string> = { ELEMENT: '元素', MATERIAL: '材料', EXCHANGE: '汇率' };

const CostingSummaryDetailPage: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [summary, setSummary] = useState<CostingSummary | null>(null);
  const [results, setResults] = useState<CostingSummaryResult[]>([]);
  const [overrides, setOverrides] = useState<CostingSummaryOverride[]>([]);
  const [loading, setLoading] = useState(true);
  const [computing, setComputing] = useState(false);
  const [publishing, setPublishing] = useState(false);

  const [overrideDrawerOpen, setOverrideDrawerOpen] = useState(false);
  const [editingOverride, setEditingOverride] = useState<CostingSummaryOverride | null>(null);
  const [overrideForm] = Form.useForm();

  const load = async () => {
    if (!id) return;
    setLoading(true);
    try {
      const [s, r, o] = await Promise.all([
        costingSummaryService.get(id),
        costingSummaryService.listResults(id),
        costingSummaryService.listOverrides(id),
      ]);
      setSummary(s.data);
      setResults(r.data);
      setOverrides(o.data);
    } catch (e: any) {
      message.error(e?.message ?? '加载失败');
    } finally { setLoading(false); }
  };
  useEffect(() => { load(); }, [id]);

  const handleCompute = async () => {
    if (!id) return;
    setComputing(true);
    try {
      const res = await costingSummaryService.compute(id);
      setResults(res.data);
      message.success(`计算完成（${res.data.length} 项指标）`);
      load();
    } catch (e: any) {
      message.error(e?.message ?? '计算失败');
    } finally { setComputing(false); }
  };

  const handlePublish = async () => {
    if (!id) return;
    setPublishing(true);
    try {
      await costingSummaryService.publish(id);
      message.success('已发布');
      load();
    } catch (e: any) {
      message.error(e?.message ?? '发布失败');
    } finally { setPublishing(false); }
  };

  const handleArchive = async () => {
    if (!id) return;
    try {
      await costingSummaryService.archive(id);
      message.success('已归档');
      load();
    } catch (e: any) {
      message.error(e?.message ?? '归档失败');
    }
  };

  // ─── Override 抽屉 ───
  const openOverrideEdit = (o: CostingSummaryOverride | null) => {
    setEditingOverride(o);
    overrideForm.resetFields();
    if (o) overrideForm.setFieldsValue(o);
    else overrideForm.setFieldsValue({ targetKind: 'ELEMENT', fieldName: 'costing_price' });
    setOverrideDrawerOpen(true);
  };

  const submitOverride = async () => {
    if (!id) return;
    try {
      const values = await overrideForm.validateFields();
      await costingSummaryService.saveOverride(id, { ...editingOverride, ...values });
      message.success('已保存');
      setOverrideDrawerOpen(false);
      load();
    } catch (e: any) {
      if (e?.errorFields) return;
      message.error(e?.message ?? '保存失败');
    }
  };

  const overrideCols = [
    {
      title: '目标类型', dataIndex: 'targetKind', width: 100,
      render: (v: string, r: CostingSummaryOverride) => (
        <a onClick={(e) => { e.stopPropagation(); openOverrideEdit(r); }}>
          <Tag color={v === 'ELEMENT' ? 'blue' : v === 'MATERIAL' ? 'purple' : 'cyan'}>{KIND_LABEL[v]}</Tag>
        </a>
      ),
    },
    { title: '业务键', dataIndex: 'targetKey', width: 200,
      render: (v: string) => <Text code>{v}</Text> },
    { title: '字段', dataIndex: 'fieldName', width: 140 },
    { title: '差量值', dataIndex: 'overrideValue', width: 130 },
    { title: '备注', dataIndex: 'notes', ellipsis: true },
  ];

  const editable = summary?.status === 'DRAFT' || summary?.status === 'COMPUTED';

  const overrideActions: ToolbarAction<CostingSummaryOverride>[] = [
    {
      key: 'edit', label: '编辑', icon: <EditOutlined />,
      enabledWhen: (rows) => {
        if (!editable) return '已发布/归档不可改';
        if (rows.length !== 1) return '一次只能选一行';
        return true;
      },
      onClick: (rows) => openOverrideEdit(rows[0]),
    },
    {
      key: 'delete', label: '删除', icon: <DeleteOutlined />, danger: true,
      enabledWhen: (rows) => {
        if (!editable) return '已发布/归档不可改';
        return rows.length > 0;
      },
      needsConfirm: true,
      confirmTitle: '确认删除选中的 {N} 条差量？',
      onClick: async (rows) => {
        await runBatch(rows, (r) => costingSummaryService.deleteOverride(r.id!).then(() => undefined),
          { rowLabel: (r) => `${KIND_LABEL[r.targetKind]} ${r.targetKey}.${r.fieldName}` });
        load();
      },
    },
  ];

  if (loading) return <div style={{ textAlign: 'center', padding: 48 }}><Spin /></div>;
  if (!summary) return <Empty description="核价单不存在" />;

  return (
    <div>
      <Space style={{ marginBottom: 12 }}>
        <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/costing-summary')}>返回列表</Button>
        <Title level={4} style={{ margin: 0 }}>{summary.summaryNo}</Title>
        <Tag color={STATUS_COLOR[summary.status || 'DRAFT']}>{STATUS_LABEL[summary.status || 'DRAFT']}</Tag>
      </Space>

      <Card size="small" style={{ marginBottom: 12 }}>
        <Descriptions column={3} size="small">
          <Descriptions.Item label="宏丰料号">{summary.hfPartNo}</Descriptions.Item>
          <Descriptions.Item label="报价货币">{summary.quoteCurrency}</Descriptions.Item>
          <Descriptions.Item label="差量数">{overrides.length}</Descriptions.Item>
          <Descriptions.Item label="创建时间">{summary.createdAt && new Date(summary.createdAt).toLocaleString('zh-CN')}</Descriptions.Item>
          <Descriptions.Item label="计算时间">{summary.computedAt ? new Date(summary.computedAt).toLocaleString('zh-CN') : '-'}</Descriptions.Item>
          <Descriptions.Item label="发布时间">{summary.publishedAt ? new Date(summary.publishedAt).toLocaleString('zh-CN') : '-'}</Descriptions.Item>
          {summary.notes && (
            <Descriptions.Item label="备注" span={3}>{summary.notes}</Descriptions.Item>
          )}
        </Descriptions>
        <Space style={{ marginTop: 12 }}>
          <Button
            type="primary" icon={<PlayCircleOutlined />}
            loading={computing}
            disabled={!editable}
            onClick={handleCompute}
          >
            {summary.status === 'COMPUTED' ? '重新计算' : '计算'}
          </Button>
          <Button
            icon={<CheckCircleOutlined />}
            loading={publishing}
            disabled={summary.status !== 'COMPUTED'}
            onClick={handlePublish}
          >
            发布
          </Button>
          <Button
            icon={<InboxOutlined />}
            disabled={summary.status !== 'PUBLISHED'}
            onClick={handleArchive}
          >
            归档
          </Button>
        </Space>
      </Card>

      <Tabs
        items={[
          {
            key: 'results', label: `计算结果 (${results.length})`, children: (
              results.length === 0 ? (
                <Alert
                  type="info" showIcon style={{ margin: 24 }}
                  message="还没有计算结果"
                  description={editable ? '点击顶部「计算」按钮触发求值。' : '该核价单已发布或归档，结果不可重算。'}
                />
              ) : (
                <Card size="small">
                  <Row gutter={[16, 16]}>
                    {results.map(r => (
                      <Col span={6} key={r.metricCode}>
                        <Statistic
                          title={r.metricLabel || r.metricCode}
                          value={r.value != null ? Number(r.value).toFixed(4) : '-'}
                          suffix={r.currency}
                        />
                        <div style={{ fontSize: 11, color: '#999', marginTop: 4 }}>
                          {r.formulaUsed}
                        </div>
                      </Col>
                    ))}
                  </Row>
                </Card>
              )
            ),
          },
          {
            key: 'overrides', label: `用户差量 (${overrides.length})`, children: (
              <SelectableTable<CostingSummaryOverride>
                rowKey="id"
                columns={overrideCols as any}
                dataSource={overrides}
                pagination={false}
                size="small"
                toolbar={
                  editable ? (
                    <Button type="primary" size="small" icon={<PlusOutlined />} onClick={() => openOverrideEdit(null)}>
                      新增差量
                    </Button>
                  ) : <Tag color="orange">{STATUS_LABEL[summary.status || 'DRAFT']}状态不可改差量</Tag>
                }
                actions={overrideActions}
                rowLabel={(r) => `${KIND_LABEL[r.targetKind]} ${r.targetKey}.${r.fieldName} = ${r.overrideValue}`}
              />
            ),
          },
        ]}
      />

      <Drawer
        title={editingOverride ? '编辑差量' : '新增差量'}
        open={overrideDrawerOpen}
        onClose={() => setOverrideDrawerOpen(false)}
        width={520}
        destroyOnClose
        footer={
          <div style={{ textAlign: 'right' }}>
            <Button onClick={() => setOverrideDrawerOpen(false)} style={{ marginRight: 8 }}>取消</Button>
            <Button type="primary" onClick={submitOverride}>保存</Button>
          </div>
        }
      >
        <Alert type="info" showIcon style={{ marginBottom: 12 }}
          message="差量仅在本核价单内生效，不写回基础数据"
          description="保存后核价单状态退回 DRAFT；需重新点击「计算」让差量生效。" />
        <Form form={overrideForm} layout="vertical">
          <Form.Item name="targetKind" label="差量类型" rules={[{ required: true }]}>
            <Select options={[
              { value: 'ELEMENT', label: '元素价格' },
              { value: 'MATERIAL', label: '材料价格' },
              { value: 'EXCHANGE', label: '汇率' },
            ]} />
          </Form.Item>
          <Form.Item
            name="targetKey" label="业务键" rules={[{ required: true }]}
            tooltip="ELEMENT 写元素代码（Ag/Cu）；MATERIAL 写材料料号；EXCHANGE 写 'CNY/USD' 这种"
          >
            <Input placeholder="如 Ag / 1610010128 / CNY/USD" />
          </Form.Item>
          <Form.Item name="fieldName" label="字段名" rules={[{ required: true }]}>
            <Select options={[
              { value: 'costing_price', label: '核价单价 / 核价汇率' },
              { value: 'costing_rate', label: 'costing_rate (汇率专用)' },
              { value: 'discount_rate', label: '折扣率%' },
            ]} />
          </Form.Item>
          <Form.Item name="overrideValue" label="覆盖值" rules={[{ required: true }]}>
            <InputNumber style={{ width: '100%' }} precision={6} />
          </Form.Item>
          <Form.Item name="notes" label="备注"><Input.TextArea rows={2} /></Form.Item>
        </Form>
      </Drawer>
    </div>
  );
};

export default CostingSummaryDetailPage;
