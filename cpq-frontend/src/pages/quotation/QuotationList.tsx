import React, { useEffect, useState } from 'react';
import {
  Button, Input, Space, Tag, Card, message, Tabs,
} from 'antd';
import {
  PlusOutlined, EditOutlined, DeleteOutlined, CopyOutlined,
  SendOutlined, CheckOutlined, CloseOutlined,
  RollbackOutlined,
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
  SUBMITTED: { label: '待核价', color: 'processing' },
  APPROVED: { label: '已审核', color: 'success' },
  SENT: { label: '已发送', color: 'cyan' },
  ACCEPTED: { label: '已接受', color: 'green' },
  REJECTED: { label: '客户已拒绝', color: 'error' },
  EXPIRED: { label: '已过期', color: 'warning' },
  COSTING_REJECTED: { label: '已驳回', color: 'error' },
};

const statusTabs = [
  { key: '', label: '全部' },
  { key: 'DRAFT', label: '草稿' },
  { key: 'SUBMITTED', label: '待核价' },
  { key: 'COSTING_REJECTED', label: '已驳回' },
  { key: 'APPROVED', label: '已审核' },
  { key: 'SENT', label: '已发送' },
  { key: 'ACCEPTED', label: '已接受' },
  { key: 'REJECTED', label: '客户已拒绝' },
  { key: 'EXPIRED', label: '已过期' },
];

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
  const [basicImportOpen, setBasicImportOpen] = useState(false);
  const [copySource, setCopySource] = useState<{ id: string; templateId?: string } | null>(null);

  const loadData = async () => {
    setLoading(true);
    try {
      const salesRepFilter = user?.role === 'SALES_REP' ? user.id : undefined;
      const res = await quotationService.list({
        page,
        size,
        status: statusFilter || undefined,
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
        const s = rows[0].status;
        if (['SUBMITTED', 'APPROVED'].includes(s)) return '请先撤回再编辑';
        if (!['DRAFT', 'COSTING_REJECTED'].includes(s)) return '当前状态不可编辑';
        return true;
      },
      onClick: async (rows) => {
        const row = rows[0];
        if (row.status === 'COSTING_REJECTED') {
          try {
            await quotationService.beginEdit(row.id);
          } catch (e: any) {
            message.error(e.message || '转草稿失败');
            return;
          }
        }
        navigate(`/quotations/${row.id}/edit`);
      },
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
        if (rows.some((r: any) => !['SUBMITTED', 'COSTING_REJECTED', 'APPROVED'].includes(r.status)))
          return '仅待核价/已驳回/已审核可撤回';
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
        items={statusTabs.map(t => ({ key: t.key, label: t.label }))}
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
