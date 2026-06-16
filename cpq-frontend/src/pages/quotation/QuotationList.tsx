import React, { useEffect, useState } from 'react';
import {
  Button, Input, Space, Tag, Card, message, Tabs, Modal,
} from 'antd';
import {
  PlusOutlined, EditOutlined, DeleteOutlined, CopyOutlined,
  SendOutlined, CheckOutlined, CloseOutlined,
  CheckCircleOutlined, CloseCircleOutlined, RollbackOutlined,
  ImportOutlined, HistoryOutlined,
} from '@ant-design/icons';
import { useNavigate } from 'react-router-dom';
import { quotationService } from '../../services/quotationService';
import { quotationSnapshotService } from '../../services/quotationSnapshotService';
import { useAuthStore } from '../../stores/authStore';
import QuoteBasicDataImportV6Drawer from './QuoteBasicDataImportV6Drawer';
import CopyQuotationDrawer from './CopyQuotationDrawer';
import SelectableTable, { runBatch, type ToolbarAction } from '../../components/SelectableTable';

const { Search } = Input;

const statusMap: Record<string, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: 'default' },
  SUBMITTED: { label: '审批中', color: 'processing' },
  APPROVED: { label: '已批准', color: 'success' },
  SENT: { label: '已发送', color: 'cyan' },
  ACCEPTED: { label: '已接受', color: 'green' },
  REJECTED: { label: '已退回', color: 'error' },
  EXPIRED: { label: '已过期', color: 'warning' },
};

const statusTabs = [
  { key: '', label: '全部' },
  { key: 'DRAFT', label: '草稿' },
  { key: 'SUBMITTED', label: '审批中' },
  { key: 'APPROVED', label: '已批准' },
  { key: 'SENT', label: '已发送' },
  { key: 'ACCEPTED', label: '已接受' },
  { key: 'REJECTED', label: '已退回' },
  { key: 'EXPIRED', label: '已过期' },
];

const PENDING_APPROVAL_TAB = '__pending_approval__';

const QuotationList: React.FC = () => {
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const [data, setData] = useState<any[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [size] = useState(20);
  const [keyword, setKeyword] = useState('');
  const [statusFilter, setStatusFilter] = useState<string>('');
  const [loading, setLoading] = useState(false);
  // 审批意见 Modal（需要文本输入，独立于 SelectableTable 的简单 Modal 确认）
  const [approveModalOpen, setApproveModalOpen] = useState(false);
  const [rejectModalOpen, setRejectModalOpen] = useState(false);
  const [actionTargets, setActionTargets] = useState<any[]>([]);  // 暂存所选行
  const [rejectComment, setRejectComment] = useState('');
  const [approveComment, setApproveComment] = useState('');
  const [actionLoading, setActionLoading] = useState(false);
  const [basicImportOpen, setBasicImportOpen] = useState(false);
  const [copySource, setCopySource] = useState<{ id: string; templateId?: string } | null>(null);

  const isPendingApprovalTab = statusFilter === PENDING_APPROVAL_TAB;

  const loadData = async () => {
    setLoading(true);
    try {
      const salesRepFilter = user?.role === 'SALES_REP' ? user.id : undefined;
      const res = await quotationService.list({
        page,
        size,
        status: isPendingApprovalTab ? 'SUBMITTED' : (statusFilter || undefined),
        assignedApproverId: isPendingApprovalTab && user && user.role !== 'SYSTEM_ADMIN' ? user.id : undefined,
        salesRepId: salesRepFilter,
        keyword: keyword || undefined,
      });
      setData(res.data?.content || []);
      setTotal(res.data?.totalElements || 0);
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { loadData(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [page, statusFilter, keyword]);

  const handleApprove = async () => {
    if (actionTargets.length !== 1) return;
    setActionLoading(true);
    try {
      await quotationService.approve(actionTargets[0].id, approveComment || undefined);
      message.success('审批通过');
      setApproveModalOpen(false);
      setApproveComment('');
      setActionTargets([]);
      loadData();
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setActionLoading(false);
    }
  };

  const handleReject = async () => {
    if (!rejectComment.trim()) {
      message.warning('请填写退回原因');
      return;
    }
    if (actionTargets.length !== 1) return;
    setActionLoading(true);
    try {
      await quotationService.reject(actionTargets[0].id, rejectComment);
      message.success('已退回');
      setRejectModalOpen(false);
      setRejectComment('');
      setActionTargets([]);
      loadData();
    } catch (e: any) {
      message.error(e.message);
    } finally {
      setActionLoading(false);
    }
  };

  // 列定义 —— 报价单号点击进详情；不再有"操作"列
  const columns = [
    {
      title: '报价单号', dataIndex: 'quotationNumber', key: 'quotationNumber', width: 180,
      render: (v: string, record: any) => (
        <a onClick={(e) => { e.stopPropagation(); navigate(`/quotations/${record.id}`); }}>{v}</a>
      ),
    },
    { title: '名称', dataIndex: 'name', key: 'name', ellipsis: true },
    { title: '客户', dataIndex: 'snapshotCustomerName', key: 'customer' },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 100,
      render: (s: string) => {
        const m = statusMap[s] || { label: s, color: 'default' };
        return <Tag color={m.color}>{m.label}</Tag>;
      },
    },
    {
      title: '总金额', dataIndex: 'totalAmount', key: 'totalAmount', width: 130,
      render: (v: number) => v != null ? `¥${Number(v).toLocaleString()}` : '-',
    },
    { title: '到期日', dataIndex: 'expiryDate', key: 'expiryDate', width: 120 },
  ];

  // PRICING_MANAGER 角色不参与动作，直接给空 actions
  const isPricingManager = user?.role === 'PRICING_MANAGER';

  const actions: ToolbarAction<any>[] = isPricingManager ? [] : [
    {
      key: 'edit',
      label: '编辑',
      icon: <EditOutlined />,
      enabledWhen: (rows) => {
        if (rows.length !== 1) return '编辑一次只能选一行';
        if (['ACCEPTED', 'EXPIRED'].includes(rows[0].status)) return '已接受/已过期的报价单不可编辑';
        return true;
      },
      onClick: (rows) => navigate(`/quotations/${rows[0].id}/edit`),
    },
    {
      key: 'copy',
      label: '复制',
      icon: <CopyOutlined />,
      enabledWhen: (rows) => rows.length === 1 ? true : '复制一次只能选一行',
      onClick: (rows) => {
        const r: any = rows[0];
        setCopySource({ id: r.id, templateId: r.customerTemplateId ?? r.templateId });
      },
    },
    {
      key: 'submit',
      label: '提交审批',
      icon: <SendOutlined />,
      enabledWhen: (rows) => {
        if (rows.length === 0) return false;
        if (rows.some((r: any) => r.status !== 'DRAFT')) return '仅草稿可提交审批';
        return true;
      },
      needsConfirm: true,
      confirmTitle: '确认提交选中的 {N} 个报价单审批？',
      onClick: async (rows) => {
        await runBatch(rows, (r: any) => quotationSnapshotService.submit(r.id).then(() => undefined), {
          rowLabel: (r: any) => `${r.quotationNumber} ${r.name}`,
          successMsg: `已提交 ${rows.length} 项`,
        });
        loadData();
      },
    },
    {
      key: 'withdraw',
      label: '撤回',
      icon: <RollbackOutlined />,
      enabledWhen: (rows) => {
        if (rows.length === 0) return false;
        if (rows.some((r: any) => r.status !== 'SUBMITTED')) return '仅审批中的可撤回';
        if (rows.some((r: any) => r.salesRepId !== user?.id)) return '只能撤回自己提交的报价单';
        return true;
      },
      needsConfirm: true,
      confirmTitle: '撤回 {N} 个报价单？',
      confirmDescription: '撤回后报价单回到草稿状态，需重新提交审批。',
      onClick: async (rows) => {
        await runBatch(rows, (r: any) => quotationService.withdraw(r.id).then(() => undefined), {
          rowLabel: (r: any) => `${r.quotationNumber} ${r.name}`,
          successMsg: `已撤回 ${rows.length} 项`,
        });
        loadData();
      },
    },
    {
      key: 'approve',
      label: '审批通过',
      icon: <CheckCircleOutlined />,
      enabledWhen: (rows) => {
        if (!isPendingApprovalTab) return '请切到「待我审批」tab 后再审批';
        if (rows.length !== 1) return '审批一次只能选一行（需要填写意见）';
        const r = rows[0];
        if (r.status !== 'SUBMITTED') return '仅审批中状态可通过';
        const canApprove = user?.role === 'SYSTEM_ADMIN' || r.assignedApproverId === user?.id;
        if (!canApprove) return '您不是该报价单的审批人';
        return true;
      },
      onClick: (rows) => {
        setActionTargets(rows);
        setApproveComment('');
        setApproveModalOpen(true);
      },
    },
    {
      key: 'reject-approval',
      label: '审批退回',
      icon: <CloseCircleOutlined />,
      danger: true,
      enabledWhen: (rows) => {
        if (!isPendingApprovalTab) return '请切到「待我审批」tab 后再审批';
        if (rows.length !== 1) return '退回一次只能选一行（需要填写原因）';
        const r = rows[0];
        if (r.status !== 'SUBMITTED') return '仅审批中状态可退回';
        const canApprove = user?.role === 'SYSTEM_ADMIN' || r.assignedApproverId === user?.id;
        if (!canApprove) return '您不是该报价单的审批人';
        return true;
      },
      onClick: (rows) => {
        setActionTargets(rows);
        setRejectComment('');
        setRejectModalOpen(true);
      },
    },
    {
      key: 'send',
      label: '发送给客户',
      icon: <SendOutlined />,
      enabledWhen: (rows) => {
        if (rows.length !== 1) return '发送一次只能选一行';
        if (rows[0].status !== 'APPROVED') return '仅已批准状态可发送';
        return true;
      },
      onClick: (rows) => navigate(`/quotations/${rows[0].id}`),
    },
    {
      key: 'accept',
      label: '客户接受',
      icon: <CheckOutlined />,
      enabledWhen: (rows) => {
        if (rows.length === 0) return false;
        if (rows.some((r: any) => r.status !== 'SENT')) return '仅已发送状态可标记接受';
        return true;
      },
      needsConfirm: true,
      confirmTitle: '确认客户已接受 {N} 个报价单？',
      onClick: async (rows) => {
        await runBatch(rows, (r: any) => quotationService.accept(r.id).then(() => undefined), {
          rowLabel: (r: any) => `${r.quotationNumber} ${r.name}`,
          successMsg: `已标记接受 ${rows.length} 项`,
        });
        loadData();
      },
    },
    {
      key: 'reject-customer',
      label: '客户拒绝',
      icon: <CloseOutlined />,
      danger: true,
      enabledWhen: (rows) => {
        if (rows.length === 0) return false;
        if (rows.some((r: any) => r.status !== 'SENT')) return '仅已发送状态可标记拒绝';
        return true;
      },
      needsConfirm: true,
      confirmTitle: '确认客户已拒绝 {N} 个报价单？',
      onClick: async (rows) => {
        await runBatch(rows, (r: any) => quotationService.rejectByCustomer(r.id, '客户拒绝').then(() => undefined), {
          rowLabel: (r: any) => `${r.quotationNumber} ${r.name}`,
          successMsg: `已标记拒绝 ${rows.length} 项`,
        });
        loadData();
      },
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
      confirmTitle: '确认删除选中的 {N} 个报价单？',
      confirmDescription: '⚠️ 此操作不可撤销。',
      onClick: async (rows) => {
        await runBatch(rows, (r: any) => quotationService.delete(r.id).then(() => undefined), {
          rowLabel: (r: any) => `${r.quotationNumber} ${r.name}`,
          successMsg: `已删除 ${rows.length} 项`,
        });
        loadData();
      },
    },
  ];

  const toolbar = (
    <>
      <Search
        placeholder="搜索报价单号/名称/客户"
        onSearch={(v) => { setKeyword(v); setPage(0); }}
        allowClear
        style={{ width: 300 }}
      />
      <Space>
        <Button icon={<HistoryOutlined />} onClick={() => navigate('/import-history')}>
          导入历史
        </Button>
        <Button type="primary" icon={<ImportOutlined />} onClick={() => setBasicImportOpen(true)}>
          从基础数据导入
        </Button>
        <Button icon={<PlusOutlined />} onClick={() => navigate('/quotations/new')}>
          新建报价单
        </Button>
      </Space>
    </>
  );

  return (
    <Card title="报价单管理">
      <Tabs
        items={[
          ...statusTabs.map(t => ({ key: t.key, label: t.label })),
          ...(['SALES_MANAGER', 'SYSTEM_ADMIN'].includes(user?.role || '')
            ? [{ key: PENDING_APPROVAL_TAB, label: '待我审批' }]
            : []),
        ]}
        activeKey={statusFilter}
        onChange={(k) => { setStatusFilter(k); setPage(0); }}
        style={{ marginBottom: 8 }}
      />

      <SelectableTable<any>
        rowKey="id"
        columns={columns}
        dataSource={data}
        loading={loading}
        pagination={{
          current: page + 1,
          pageSize: size,
          total,
          onChange: (p) => setPage(p - 1),
          showTotal: (t) => `共 ${t} 条`,
        }}
        toolbar={toolbar}
        actions={actions}
        rowLabel={(r: any) => `${r.quotationNumber} ${r.name}${r.snapshotCustomerName ? ' · ' + r.snapshotCustomerName : ''}`}
      />

      <Modal
        title={`审批通过${actionTargets[0] ? ' — ' + actionTargets[0].quotationNumber : ''}`}
        open={approveModalOpen}
        onCancel={() => { setApproveModalOpen(false); setApproveComment(''); setActionTargets([]); }}
        onOk={handleApprove}
        confirmLoading={actionLoading}
        okText="确认通过"
        okButtonProps={{ style: { backgroundColor: '#52c41a', borderColor: '#52c41a' } }}
      >
        <Input.TextArea rows={3} placeholder="审批意见（可选）" value={approveComment} onChange={e => setApproveComment(e.target.value)} />
      </Modal>
      <Modal
        title={`退回报价单${actionTargets[0] ? ' — ' + actionTargets[0].quotationNumber : ''}`}
        open={rejectModalOpen}
        onCancel={() => { setRejectModalOpen(false); setRejectComment(''); setActionTargets([]); }}
        onOk={handleReject}
        confirmLoading={actionLoading}
        okText="确认退回"
        okButtonProps={{ danger: true }}
      >
        <Input.TextArea rows={3} placeholder="请填写退回原因（必填）" value={rejectComment} onChange={e => setRejectComment(e.target.value)} />
      </Modal>
      <QuoteBasicDataImportV6Drawer open={basicImportOpen} onClose={() => { setBasicImportOpen(false); loadData(); }} />
      <CopyQuotationDrawer
        open={!!copySource}
        defaultTemplateId={copySource?.templateId}
        onClose={() => setCopySource(null)}
        onConfirm={async (templateId) => {
          try {
            const res = await quotationService.copy(copySource!.id, templateId);
            message.success('复制成功');
            setCopySource(null);
            navigate(`/quotations/${res.data.id}/edit`);
          } catch (e: any) { message.error(e.message); }
        }}
      />
    </Card>
  );
};

export default QuotationList;
