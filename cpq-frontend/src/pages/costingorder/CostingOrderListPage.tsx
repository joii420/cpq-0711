/**
 * 核价管理列表（Phase 1 — 财务核价入口）
 *
 * 列出所有进入核价流程的报价单，财务人员在此页面执行核价通过/驳回。
 * 工具栏动作：进入核价（单选）/ 核价通过（批量）/ 驳回（批量，需填理由）
 */
import React, { useEffect, useState } from 'react';
import {
  Tag, Space, Select, Typography, Form, Input, Modal, message, Tooltip,
} from 'antd';
import { CheckOutlined, CloseOutlined, ArrowRightOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import {
  costingOrderService,
  type CostingOrderListItem,
} from '../../services/costingOrderService';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';

const { Title } = Typography;

// 后端返回英文状态码，前端统一映射中文标签
const COSTING_STATUS: Record<string, { label: string; color: string }> = {
  PENDING:   { label: '待核价', color: 'orange' },
  APPROVED:  { label: '已审核', color: 'green' },
  REJECTED:  { label: '已驳回', color: 'red' },
  WITHDRAWN: { label: '已撤回', color: 'default' },
};

const CostingOrderListPage: React.FC = () => {
  const navigate = useNavigate();
  const [data, setData] = useState<CostingOrderListItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [statusFilter, setStatusFilter] = useState<string[]>([]);
  const [keyword, setKeyword] = useState('');

  // 驳回 Modal 状态
  const [rejectOpen, setRejectOpen] = useState(false);
  const [rejectRows, setRejectRows] = useState<CostingOrderListItem[]>([]);
  const [rejectLoading, setRejectLoading] = useState(false);
  const [rejectForm] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try {
      const res = await costingOrderService.list({
        statuses: statusFilter.length > 0 ? statusFilter : undefined,
        keyword: keyword || undefined,
      });
      setData(res.data);
    } catch (e: any) {
      message.error(e?.message ?? '加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, [statusFilter, keyword]);

  const cols = [
    {
      title: '核价单号',
      dataIndex: 'costingOrderNumber',
      width: 180,
      render: (v: string, r: CostingOrderListItem) => (
        <a
          onClick={(e) => {
            e.stopPropagation();
            navigate(`/costing-orders/${r.costingOrderId}/review`);
          }}
        >
          {v}
        </a>
      ),
    },
    {
      title: '报价单号',
      dataIndex: 'quotationNumber',
      width: 180,
      render: (v: string, r: CostingOrderListItem) => (
        <a
          onClick={(e) => {
            e.stopPropagation();
            navigate(`/quotations/${r.quotationId}`);
          }}
        >
          {v}
        </a>
      ),
    },
    { title: '客户', dataIndex: 'customerName', width: 180 },
    { title: '货币', dataIndex: 'currency', width: 80 },
    { title: '提交人', dataIndex: 'submittedByName', width: 120 },
    {
      title: '当前状态',
      dataIndex: 'status',
      width: 110,
      render: (s: string) => {
        const info = COSTING_STATUS[s];
        return <Tag color={info?.color ?? 'default'}>{info?.label ?? s}</Tag>;
      },
    },
    {
      title: '创建时间',
      dataIndex: 'createdAt',
      width: 170,
      render: (v: string) => (v ? new Date(v).toLocaleString('zh-CN') : '—'),
    },
    {
      title: '退回原因',
      dataIndex: 'rejectReason',
      width: 200,
      render: (v: string) => {
        if (!v) return '—';
        return (
          <Tooltip title={v}>
            <span
              style={{
                display: 'inline-block',
                maxWidth: 180,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
                verticalAlign: 'middle',
              }}
            >
              {v}
            </span>
          </Tooltip>
        );
      },
    },
    {
      title: '修改时间',
      dataIndex: 'updatedAt',
      width: 170,
      render: (v: string) => (v ? new Date(v).toLocaleString('zh-CN') : '—'),
    },
  ];

  const isPendingCosting = (r: CostingOrderListItem) => r.status === 'PENDING';

  const actions: ToolbarAction<CostingOrderListItem>[] = [
    {
      key: 'enter-review',
      label: '进入核价',
      icon: <ArrowRightOutlined />,
      enabledWhen: (rows) => {
        if (rows.length !== 1) return '请选择一个核价单';
        return true;
      },
      onClick: (rows) => {
        navigate(`/costing-orders/${rows[0].costingOrderId}/review`);
      },
    },
    {
      key: 'approve',
      label: '核价通过',
      icon: <CheckOutlined />,
      enabledWhen: (rows) => {
        if (rows.length === 0) return false;
        if (!rows.every(isPendingCosting)) return '所选项必须均处于"待核价"状态';
        return true;
      },
      needsConfirm: true,
      confirmTitle: '确认核价通过所选 {N} 个核价单？',
      onClick: async (rows) => {
        await runBatch(
          rows,
          (r) => costingOrderService.approve(r.quotationId).then(() => undefined),
          { rowLabel: (r) => `${r.quotationNumber} ${r.customerName}`, successMsg: '核价通过成功' },
        );
        load();
      },
    },
    {
      key: 'reject',
      label: '驳回',
      icon: <CloseOutlined />,
      danger: true,
      enabledWhen: (rows) => {
        if (rows.length === 0) return false;
        if (!rows.every(isPendingCosting)) return '所选项必须均处于"待核价"状态';
        return true;
      },
      onClick: (rows) => {
        setRejectRows(rows);
        rejectForm.resetFields();
        setRejectOpen(true);
      },
    },
  ];

  const handleRejectConfirm = async () => {
    let comment: string;
    try {
      const values = await rejectForm.validateFields();
      comment = values.comment as string;
    } catch {
      return;
    }
    setRejectLoading(true);
    try {
      await runBatch(
        rejectRows,
        (r) => costingOrderService.reject(r.quotationId, comment).then(() => undefined),
        { rowLabel: (r) => `${r.quotationNumber} ${r.customerName}`, successMsg: '驳回成功' },
      );
      setRejectOpen(false);
      load();
    } finally {
      setRejectLoading(false);
    }
  };

  return (
    <div>
      <div style={{ marginBottom: 12 }}>
        <Title level={4} style={{ margin: 0 }}>核价管理</Title>
        <span style={{ color: '#8c8c8c', fontSize: 12 }}>
          财务核价入口：查看待核价报价单，执行通过或驳回操作。
        </span>
      </div>

      <SelectableTable<CostingOrderListItem>
        rowKey="costingOrderId"
        columns={cols as any}
        dataSource={data}
        loading={loading}
        pagination={{ pageSize: 50 }}
        toolbar={
          <Space wrap>
            <Select
              mode="multiple"
              allowClear
              placeholder="按状态过滤"
              style={{ minWidth: 200 }}
              value={statusFilter}
              onChange={setStatusFilter}
              options={Object.entries(COSTING_STATUS).map(([v, { label }]) => ({ value: v, label }))}
            />
            <Input.Search
              placeholder="按报价单号搜索"
              allowClear
              style={{ width: 220 }}
              onSearch={(v) => setKeyword(v)}
              onChange={(e) => { if (!e.target.value) setKeyword(''); }}
            />
          </Space>
        }
        actions={actions}
        rowLabel={(r) => `${r.costingOrderNumber ?? r.quotationNumber} ${r.customerName} (${COSTING_STATUS[r.status]?.label ?? r.status})`}
      />

      {/* 驳回弹窗：单独处理，因为需要填写驳回原因（不能走 needsConfirm 的通用 Modal） */}
      <Modal
        title={`驳回所选 ${rejectRows.length} 个核价单`}
        open={rejectOpen}
        onCancel={() => setRejectOpen(false)}
        onOk={handleRejectConfirm}
        okText="确认驳回"
        okButtonProps={{ danger: true, loading: rejectLoading }}
        cancelText="取消"
        destroyOnClose
      >
        <div style={{ marginBottom: 12, color: '#666' }}>所选项：</div>
        <ul
          style={{
            maxHeight: 160,
            overflowY: 'auto',
            margin: '0 0 16px',
            paddingLeft: 18,
            background: '#fafafa',
            border: '1px solid #f0f0f0',
            borderRadius: 4,
            padding: 12,
          }}
        >
          {rejectRows.slice(0, 20).map((r) => (
            <li key={r.costingOrderId} style={{ marginBottom: 2 }}>
              {r.costingOrderNumber ?? r.quotationNumber} — {r.customerName}
            </li>
          ))}
          {rejectRows.length > 20 && (
            <li style={{ color: '#999', listStyle: 'none', marginTop: 6 }}>
              …等共 {rejectRows.length} 项
            </li>
          )}
        </ul>
        <Form form={rejectForm} layout="vertical">
          <Form.Item
            name="comment"
            label="驳回原因"
            rules={[{ required: true, message: '请填写驳回原因' }]}
          >
            <Input.TextArea rows={3} placeholder="请说明驳回原因" maxLength={500} showCount />
          </Form.Item>
        </Form>
      </Modal>
    </div>
  );
};

export default CostingOrderListPage;
