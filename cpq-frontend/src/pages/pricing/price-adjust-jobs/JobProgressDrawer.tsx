import React, { useCallback, useEffect, useRef, useState } from 'react';
import { Drawer, Table, Tag, Progress, Space, Tooltip, Button, message } from 'antd';
import dayjs from 'dayjs';
import { priceAdjustService } from '../../../services/priceAdjustService';
import type { UpdateJobDTO, UpdateJobItemDTO, JobItemStatus } from '../../../types/price-adjust';

const PAGE_SIZE = 20;
const POLL_INTERVAL_MS = 2000;

export interface JobProgressDrawerProps {
  open: boolean;
  jobId: string | null;
  onClose: () => void;
}

const ITEM_STATUS_TAG: Record<JobItemStatus, { color: string; label: string }> = {
  WAITING: { color: 'default', label: '等待' },
  RUNNING: { color: 'processing', label: '执行中' },
  SUCCESS: { color: 'green', label: '成功' },
  FAILED: { color: 'red', label: '失败' },
  CONFLICT: { color: 'orange', label: '冲突' },
  STALE: { color: 'default', label: '已失效' },
};

/**
 * 屏 6a · 更新执行进度抽屉（800px，fronttask §5.1 / api.md §3.2-3.3）。
 * 轮询 GET /jobs/{id}（2s），terminal 状态（非 RUNNING）自动停止轮询。
 * 「支持后台运行」= 关闭抽屉不影响后端任务继续执行（后端本就异步跑，前端无需做取消动作，
 * 关闭时只停止本地轮询，不调用任何"取消"接口）。
 * 明细支持单条重试（Drawer 内部子表例外，列表操作规范允许行内动作）；
 * 🔒 STALE 项重试按钮禁用 + hover 说明。
 */
const JobProgressDrawer: React.FC<JobProgressDrawerProps> = ({ open, jobId, onClose }) => {
  const [job, setJob] = useState<UpdateJobDTO | null>(null);
  const [loading, setLoading] = useState(false);
  const [items, setItems] = useState<UpdateJobItemDTO[]>([]);
  const [itemsTotal, setItemsTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [retryingItemId, setRetryingItemId] = useState<string | null>(null);
  const pollRef = useRef<number | null>(null);

  const loadJob = useCallback(async () => {
    if (!jobId) return;
    try {
      const j = await priceAdjustService.getJob(jobId);
      setJob(j);
    } catch (e: any) {
      message.error(e?.message || '加载任务进度失败');
    }
  }, [jobId]);

  const loadItems = useCallback(async (p = 1) => {
    if (!jobId) return;
    setLoading(true);
    try {
      const res = await priceAdjustService.getJobItems(jobId, { page: p, size: PAGE_SIZE });
      setItems(res.content || []);
      setItemsTotal(res.totalElements || 0);
      setPage(p);
    } catch (e: any) {
      message.error(e?.message || '加载明细失败');
    } finally {
      setLoading(false);
    }
  }, [jobId]);

  useEffect(() => {
    if (!open || !jobId) { setJob(null); setItems([]); return; }
    loadJob();
    loadItems(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, jobId]);

  useEffect(() => {
    if (pollRef.current) { window.clearInterval(pollRef.current); pollRef.current = null; }
    if (open && jobId && job?.status === 'RUNNING') {
      pollRef.current = window.setInterval(() => { loadJob(); loadItems(page); }, POLL_INTERVAL_MS);
    }
    return () => { if (pollRef.current) window.clearInterval(pollRef.current); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, jobId, job?.status]);

  const handleClose = () => {
    if (job?.status === 'RUNNING') {
      message.info('任务将继续在后台执行，可随时在「更新任务」页面查看进度');
    }
    onClose();
  };

  const handleRetryItem = async (item: UpdateJobItemDTO) => {
    setRetryingItemId(item.itemId);
    try {
      await priceAdjustService.retryJobItem(item.itemId);
      message.success('已提交重试');
      loadJob();
      loadItems(page);
    } catch (e: any) {
      message.error(e?.message || '重试失败');
    } finally {
      setRetryingItemId(null);
    }
  };

  // job 级只有 total/success/failed/conflict/stale，"等待" 数派生（fronttask §5.1 要求展示但 api.md 未给字段）
  const waiting = job ? Math.max(0, job.total - job.success - job.failed - job.conflict - job.stale) : 0;
  const donePercent = job && job.total > 0 ? Math.round(((job.total - waiting) / job.total) * 100) : 0;

  const columns = [
    { title: '报价单', dataIndex: 'quotationNo', width: 150 },
    { title: '料号', dataIndex: 'materialNo', width: 120, render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span> },
    {
      title: '状态', dataIndex: 'status', width: 90,
      render: (v: JobItemStatus) => <Tag color={ITEM_STATUS_TAG[v]?.color}>{ITEM_STATUS_TAG[v]?.label || v}</Tag>,
    },
    {
      title: '错误信息', dataIndex: 'errorMessage',
      render: (v: string | null, r: UpdateJobItemDTO) => (
        <span>
          {v || '—'}
          {r.errorCode === 'SUBTOTAL_MISMATCH' && r.diffValue != null && (
            <Tag color="volcano" style={{ marginLeft: 6 }}>差异 {r.diffValue}</Tag>
          )}
        </span>
      ),
    },
    { title: '重试次数', dataIndex: 'retryCount', width: 80, align: 'right' as const },
    {
      title: '操作', width: 90,
      render: (_: unknown, r: UpdateJobItemDTO) => {
        if (r.status === 'STALE') {
          return (
            <Tooltip title="所属版本已被新版取代，请在新版待办池重新处理">
              <Button size="small" disabled>重试</Button>
            </Tooltip>
          );
        }
        if (r.status !== 'FAILED' && r.status !== 'CONFLICT') return null;
        return (
          <Button size="small" loading={retryingItemId === r.itemId} onClick={() => handleRetryItem(r)}>重试</Button>
        );
      },
    },
  ];

  return (
    <Drawer
      title={job ? `更新执行进度 · ${job.versionNo}` : '更新执行进度'}
      placement="right"
      width={800}
      open={open}
      onClose={handleClose}
      destroyOnClose
    >
      {job && (
        <>
          <Space size="large" style={{ marginBottom: 16 }}>
            <span>总数 <b>{job.total}</b></span>
            <span style={{ color: '#389e0d' }}>成功 <b>{job.success}</b></span>
            <span style={{ color: '#cf1322' }}>失败 <b>{job.failed}</b></span>
            <span style={{ color: '#d46b08' }}>冲突 <b>{job.conflict}</b></span>
            <span style={{ color: 'rgba(0,0,0,.45)' }}>等待 <b>{waiting}</b></span>
            {job.stale > 0 && <span style={{ color: 'rgba(0,0,0,.45)' }}>已失效 <b>{job.stale}</b></span>}
          </Space>
          <Progress percent={donePercent} status={job.status === 'FAILED' ? 'exception' : job.status === 'RUNNING' ? 'active' : 'normal'} style={{ marginBottom: 16 }} />
          <div style={{ marginBottom: 16, fontSize: 12.5, color: 'rgba(0,0,0,.45)' }}>
            触发人 {job.triggeredBy} · {dayjs(job.triggeredAt).format('YYYY-MM-DD HH:mm')}
            {job.finishedAt && <span> · 完成于 {dayjs(job.finishedAt).format('YYYY-MM-DD HH:mm')}</span>}
          </div>
        </>
      )}
      <Table<UpdateJobItemDTO>
        size="small"
        rowKey="itemId"
        loading={loading}
        dataSource={items}
        columns={columns}
        pagination={{
          current: page, pageSize: PAGE_SIZE, total: itemsTotal, size: 'small',
          onChange: (p) => loadItems(p), showTotal: (t) => `共 ${t} 条`,
        }}
      />
    </Drawer>
  );
};

export default JobProgressDrawer;
