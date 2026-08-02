import React, { useState } from 'react';
import { Drawer, Button, Input, Alert, message } from 'antd';
import { priceAdjustService } from '../../../services/priceAdjustService';
import type { ReviewRowDTO } from '../../../types/price-adjust';

const { TextArea } = Input;

export interface RejectReasonDrawerProps {
  open: boolean;
  rows: ReviewRowDTO[];
  onClose: () => void;
  onSubmitted: () => void;
}

/**
 * 屏 3 · 驳回原因抽屉（fronttask §2.3：「点击弹 Drawer 填必填原因」/ api.md §2.5：reason 必填，空则 400）。
 * 驳回后：review 置 REJECTED，指针不动，两侧均保持原样，不产生任何 job（api.md §2.5）。
 */
const RejectReasonDrawer: React.FC<RejectReasonDrawerProps> = ({ open, rows, onClose, onSubmitted }) => {
  const [reason, setReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleClose = () => {
    setReason('');
    setError(null);
    onClose();
  };

  const handleSubmit = async () => {
    if (!reason.trim()) {
      setError('驳回原因为必填项');
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await priceAdjustService.rejectReviews(rows.map((r) => r.reviewId), reason.trim());
      message.success(`已驳回 ${rows.length} 项`);
      setReason('');
      onSubmitted();
    } catch (e: any) {
      message.error(e?.message || '驳回失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Drawer
      title={`驳回 · 共 ${rows.length} 个料号`}
      placement="right"
      width={480}
      open={open}
      onClose={handleClose}
      destroyOnClose
      footer={
        <div style={{ textAlign: 'right' }}>
          <Button onClick={handleClose} style={{ marginRight: 8 }}>取消</Button>
          <Button type="primary" danger loading={submitting} onClick={handleSubmit}>确认驳回</Button>
        </div>
      }
    >
      <div style={{ marginBottom: 12, fontSize: 12.5, color: 'rgba(0,0,0,.45)' }}>
        驳回后该料号停在原版本，指针不动，两侧数据均保持原样，不产生任何更新任务。
        下一期版本与「指针当前指向的那一版」比对，只要行情有变动就会重新进池。
      </div>
      <div style={{ maxHeight: 160, overflowY: 'auto', border: '1px solid #f0f0f0', borderRadius: 6, padding: 10, marginBottom: 14, fontSize: 12.5 }}>
        {rows.map((r) => (
          <div key={r.reviewId}>{r.customerName} · {r.materialNo} {r.materialName}</div>
        ))}
      </div>
      <div style={{ marginBottom: 6 }}><span style={{ color: '#ff4d4f' }}>*</span> 驳回原因</div>
      <TextArea
        rows={4}
        value={reason}
        onChange={(e) => { setReason(e.target.value); if (error) setError(null); }}
        placeholder="请填写驳回原因，例如：行情尚未确认"
      />
      {error && <Alert style={{ marginTop: 10 }} type="error" showIcon message={error} />}
    </Drawer>
  );
};

export default RejectReasonDrawer;
