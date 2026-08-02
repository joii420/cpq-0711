import React, { useCallback, useEffect, useState } from 'react';
import { Input, Select, Space, Tag, message } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import SelectableTable, { runBatch } from '../../../components/SelectableTable';
import type { ToolbarAction } from '../../../components/SelectableTable';
import { priceAdjustService } from '../../../services/priceAdjustService';
import JobProgressDrawer from './JobProgressDrawer';
import type { UpdateJobDTO, JobStatus } from '../../../types/price-adjust';

const PAGE_SIZE = 20;

const STATUS_OPTIONS: { value: JobStatus; label: string }[] = [
  { value: 'RUNNING', label: '执行中' },
  { value: 'SUCCESS', label: '全部成功' },
  { value: 'PARTIAL', label: '部分成功' },
  { value: 'FAILED', label: '失败' },
  { value: 'STALE', label: '已失效' },
];
const STATUS_TAG: Record<JobStatus, { color: string; label: string }> = {
  RUNNING: { color: 'processing', label: '执行中' },
  SUCCESS: { color: 'green', label: '全部成功' },
  PARTIAL: { color: 'orange', label: '部分成功' },
  FAILED: { color: 'red', label: '失败' },
  STALE: { color: 'default', label: '已失效' },
};

/**
 * 屏 6b ·「更新任务」常驻页（fronttask §5.2 / api.md §3.1）。
 * 落位：定价管理 → 更新任务（与「价格调整审核」并列，权限同）。
 * 🔒 严格按 docs/列表操作规范.md：批次列表用 SelectableTable，行内零动作按钮，
 * 单条重试下沉到明细抽屉（Drawer 内部子表，规范例外白名单允许）。
 */
const UpdateJobsPage: React.FC = () => {
  const [loading, setLoading] = useState(false);
  const [rows, setRows] = useState<UpdateJobDTO[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);

  const [customerNo, setCustomerNo] = useState('');
  const [status, setStatus] = useState<JobStatus | undefined>(undefined);
  const [detailJobId, setDetailJobId] = useState<string | null>(null);

  const load = useCallback(async (p = 1) => {
    setLoading(true);
    try {
      const res = await priceAdjustService.getJobs({
        page: p, size: PAGE_SIZE, status, customerNo: customerNo.trim() || undefined,
      });
      setRows(res.content || []);
      setTotal(res.totalElements || 0);
      setPage(p);
    } catch (e: any) {
      message.error(e?.message || '加载更新任务失败');
    } finally {
      setLoading(false);
    }
  }, [status, customerNo]);

  useEffect(() => { load(1); }, [status, load]);

  const handleQuery = () => load(1);
  const handleReset = () => { setCustomerNo(''); setStatus(undefined); };

  const columns: ColumnsType<UpdateJobDTO> = [
    { title: '客户', dataIndex: 'customerNo', width: 100 },
    {
      title: '版本号', dataIndex: 'versionNo', width: 130,
      render: (v: string, r) => <a onClick={(e) => { e.stopPropagation(); setDetailJobId(r.jobId); }} style={{ fontFamily: 'monospace' }}>{v}</a>,
    },
    { title: '触发人', dataIndex: 'triggeredBy', width: 100 },
    { title: '触发时间', dataIndex: 'triggeredAt', width: 150, render: (v: string) => v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '—' },
    {
      title: '状态', dataIndex: 'status', width: 100,
      render: (v: JobStatus) => <Tag color={STATUS_TAG[v]?.color}>{STATUS_TAG[v]?.label || v}</Tag>,
    },
    { title: '总数', dataIndex: 'total', width: 70, align: 'right' as const },
    { title: '成功', dataIndex: 'success', width: 70, align: 'right' as const, render: (v: number) => <span style={{ color: '#389e0d' }}>{v}</span> },
    { title: '失败', dataIndex: 'failed', width: 70, align: 'right' as const, render: (v: number) => <span style={{ color: v > 0 ? '#cf1322' : undefined }}>{v}</span> },
    { title: '冲突', dataIndex: 'conflict', width: 70, align: 'right' as const, render: (v: number) => <span style={{ color: v > 0 ? '#d46b08' : undefined }}>{v}</span> },
    { title: '失效', dataIndex: 'stale', width: 70, align: 'right' as const },
  ];

  const actions: ToolbarAction<UpdateJobDTO>[] = [
    {
      key: 'retry-batch',
      label: '批量重试全部失败+冲突项',
      enabledWhen: (selected) => {
        if (selected.length === 0) return false;
        const ineligible = selected.filter((j) => j.status === 'STALE' || j.failed + j.conflict === 0);
        if (ineligible.length > 0) return `含 ${ineligible.length} 项已失效或无失败/冲突项，不能批量重试`;
        return true;
      },
      onClick: async (selected) => {
        await runBatch(selected, (j) => priceAdjustService.retryJob(j.jobId), {
          rowLabel: (j) => `${j.customerNo} · ${j.versionNo}`,
          successMsg: `已提交 ${selected.length} 个批次重试`,
        });
        load(page);
      },
    },
  ];

  return (
    <div>
      <div style={{ marginBottom: 12, display: 'flex', gap: 8, alignItems: 'center' }}>
        <Input placeholder="按客户编号搜索" style={{ width: 200 }} value={customerNo}
          onChange={(e) => setCustomerNo(e.target.value)} onPressEnter={handleQuery} allowClear />
        <Select placeholder="全部状态" allowClear style={{ width: 140 }} value={status} onChange={setStatus} options={STATUS_OPTIONS} />
        <Space>
          <a onClick={handleQuery}>查询</a>
          <a onClick={handleReset}>重置</a>
        </Space>
      </div>

      <SelectableTable<UpdateJobDTO>
        rowKey="jobId"
        columns={columns}
        dataSource={rows}
        loading={loading}
        actions={actions}
        rowLabel={(r) => `${r.customerNo} · ${r.versionNo}`}
        pagination={{
          current: page, pageSize: PAGE_SIZE, total, size: 'small',
          onChange: (p) => load(p), showTotal: (t) => `共 ${t} 条`,
        }}
      />

      <JobProgressDrawer open={!!detailJobId} jobId={detailJobId} onClose={() => setDetailJobId(null)} />
    </div>
  );
};

export default UpdateJobsPage;
