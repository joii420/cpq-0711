import React, { useEffect, useState } from 'react';
import { Card, Button, Modal, Input, Tag, Space, message, Popconfirm } from 'antd';
import { useAuthStore } from '../../stores/authStore';
import { quotationWithdrawService } from '../../services/quotationWithdrawService';
import type { WithdrawRequest } from '../../services/quotationWithdrawService';

interface Props {
  quotationId: string;
  quotationStatus: string;
  assignedApproverId?: string;
  onChanged?: () => void;
}

/**
 * 撤回请求小卡片：
 *  - APPROVED 状态显示 "请求撤回" 按钮（销售代表）
 *  - 有 PENDING 撤回请求时，原审批人或管理员显示 "同意/拒绝" 按钮
 */
const WithdrawSection: React.FC<Props> = ({ quotationId, quotationStatus, assignedApproverId, onChanged }) => {
  const user = useAuthStore((s) => s.user);
  const [pending, setPending] = useState<WithdrawRequest | null>(null);
  const [history, setHistory] = useState<WithdrawRequest[]>([]);
  const [requestModal, setRequestModal] = useState(false);
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const fetch = async () => {
    try {
      const [p, h] = await Promise.all([
        quotationWithdrawService.getPending(quotationId),
        quotationWithdrawService.list(quotationId),
      ]);
      setPending(p.data);
      setHistory(h.data || []);
    } catch {}
  };

  useEffect(() => { fetch(); }, [quotationId]);

  const isApprover = user?.role === 'SYSTEM_ADMIN' || user?.id === assignedApproverId;
  const canRequest = quotationStatus === 'APPROVED' && !pending;

  const handleRequest = async () => {
    if (!reason.trim()) { message.warning('请填写撤回原因'); return; }
    setSubmitting(true);
    try {
      await quotationWithdrawService.request(quotationId, reason);
      message.success('已提交撤回请求');
      setRequestModal(false);
      setReason('');
      fetch();
      onChanged?.();
    } catch (err: any) {
      message.error(err.message);
    } finally {
      setSubmitting(false);
    }
  };

  const handleApprove = async () => {
    try {
      await quotationWithdrawService.approve(quotationId);
      message.success('已同意撤回（报价单已退回草稿）');
      fetch();
      onChanged?.();
    } catch (err: any) { message.error(err.message); }
  };

  const handleReject = async () => {
    try {
      await quotationWithdrawService.reject(quotationId);
      message.success('已拒绝撤回');
      fetch();
      onChanged?.();
    } catch (err: any) { message.error(err.message); }
  };

  if (history.length === 0 && !canRequest) return null;

  return (
    <Card size="small" title="撤回审批" style={{ marginBottom: 16 }}>
      {pending && (
        <div style={{ marginBottom: 12, padding: 8, background: '#fff7e6', borderRadius: 4 }}>
          <Tag color="orange">待处理</Tag> <strong>{pending.requestedByName}</strong> 申请撤回:
          <div style={{ marginTop: 4 }}>{pending.reason}</div>
          {isApprover && (
            <Space style={{ marginTop: 8 }}>
              <Popconfirm title="确认同意撤回？报价单将退回草稿状态" onConfirm={handleApprove}>
                <Button type="primary" size="small">同意撤回</Button>
              </Popconfirm>
              <Popconfirm title="确认拒绝撤回？" onConfirm={handleReject}>
                <Button danger size="small">拒绝撤回</Button>
              </Popconfirm>
            </Space>
          )}
        </div>
      )}

      {canRequest && (
        <Button onClick={() => setRequestModal(true)}>请求撤回审批</Button>
      )}

      {history.length > 0 && (
        <div style={{ marginTop: 12 }}>
          <strong>历史撤回记录</strong>
          <ul style={{ marginTop: 4, paddingLeft: 20 }}>
            {history.map((r) => (
              <li key={r.id}>
                <Tag color={r.status === 'APPROVED' ? 'green' : (r.status === 'REJECTED' ? 'red' : 'orange')}>
                  {r.status}
                </Tag>
                {r.requestedByName} 于 {new Date(r.createdAt).toLocaleString()} - {r.reason}
                {r.decidedByName && <span> · 处理人: {r.decidedByName}</span>}
              </li>
            ))}
          </ul>
        </div>
      )}

      <Modal title="请求撤回审批" open={requestModal} onCancel={() => setRequestModal(false)} onOk={handleRequest} confirmLoading={submitting}>
        <p>报价单已批准。撤回后将变回草稿状态，需重新提交审批。</p>
        <Input.TextArea rows={4} value={reason} onChange={(e) => setReason(e.target.value)} placeholder="撤回原因（必填）" />
      </Modal>
    </Card>
  );
};

export default WithdrawSection;
