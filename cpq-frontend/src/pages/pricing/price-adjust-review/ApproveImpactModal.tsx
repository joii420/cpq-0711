import React, { useEffect, useState } from 'react';
import { Modal, Button, Spin, Alert, Tag, Descriptions, message } from 'antd';
import { priceAdjustService } from '../../../services/priceAdjustService';
import type { ReviewRowDTO, ImpactPreviewDTO } from '../../../types/price-adjust';

export interface ApproveImpactModalProps {
  open: boolean;
  rows: ReviewRowDTO[];
  onClose: () => void;
  onApproved: () => void;
  /** 屏6 联动：approve 响应带回 jobId，交给上层立刻打开进度抽屉（fronttask §5.1）。 */
  onJobCreated?: (jobId: string) => void;
}

const STATUS_LABEL: Record<string, string> = {
  DRAFT: '草稿', SUBMITTED: '已提交', APPROVED: '已审批', COSTING_APPROVED: '核价已批', PENDING_REVIEW: '待审',
  SENT: '已发送', ACCEPTED: '已接受', EXPIRED: '已过期', CANCELLED: '已取消', REJECTED: '已驳回', COSTING_REJECTED: '核价驳回',
};

/**
 * 屏 5 · 通过前影响面确认（720px Modal · fronttask §4 明确的「本任务唯一允许的 Modal」）。
 * 只读预览（api.md §2.3，无副作用），确认后才真正调用 §2.4 approve。
 * 🔒 必须同时显式列出被排除的单（excludedByStatus）——SENT 等单会保持旧价，财务需要知道（§11.6.0）。
 *
 * 说明：本组件严格属于屏 3「通过并升版」工具栏动作的必要组成部分（裁决 25：
 * 「点通过时先弹确认亮出影响面，确认后异步执行」），并非本轮擅自扩大到独立的「屏 5 任务」——
 * 没有它，屏 3 的通过按钮就是违反硬约束的不完整实现。
 */
const ApproveImpactModal: React.FC<ApproveImpactModalProps> = ({ open, rows, onClose, onApproved, onJobCreated }) => {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [preview, setPreview] = useState<ImpactPreviewDTO | null>(null);
  const [confirming, setConfirming] = useState(false);

  useEffect(() => {
    if (!open || rows.length === 0) return;
    setLoading(true);
    setError(null);
    setPreview(null);
    priceAdjustService.getImpactPreview(rows.map((r) => r.reviewId))
      .then(setPreview)
      .catch((e: any) => setError(e?.message || '加载影响面预览失败'))
      .finally(() => setLoading(false));
  }, [open, rows]);

  const handleConfirm = async () => {
    setConfirming(true);
    try {
      const res = await priceAdjustService.approveReviews(rows.map((r) => r.reviewId));
      message.success(`已提交更新任务（jobId=${res.jobId}），共 ${res.quotationCount} 张单 / ${res.itemCount} 条明细`);
      onApproved();
      onJobCreated?.(res.jobId);
    } catch (e: any) {
      message.error(e?.message || '通过并升版失败');
    } finally {
      setConfirming(false);
    }
  };

  return (
    <Modal
      title={`通过前影响面确认 · 共 ${rows.length} 个料号`}
      open={open}
      onCancel={onClose}
      width={720}
      destroyOnClose
      footer={[
        <Button key="cancel" onClick={onClose}>取消</Button>,
        <Button key="ok" type="primary" loading={confirming} disabled={!preview} onClick={handleConfirm}>
          确认通过并升版
        </Button>,
      ]}
    >
      {loading && <div style={{ textAlign: 'center', padding: 32 }}><Spin tip="正在计算影响面…" /></div>}
      {error && <Alert type="error" showIcon message={error} />}
      {preview && (
        <>
          <Descriptions column={2} size="small" bordered style={{ marginBottom: 16 }}>
            <Descriptions.Item label="料号数">{preview.materialCount}</Descriptions.Item>
            <Descriptions.Item label="将更新的单数">{preview.quotationCount}</Descriptions.Item>
          </Descriptions>

          <div style={{ marginBottom: 6, fontWeight: 600, fontSize: 13 }}>版本推进路径</div>
          <div style={{ marginBottom: 16, fontSize: 12.5 }}>
            {preview.versionPaths.map((p) => (
              <div key={p.materialNo}>
                <span style={{ fontFamily: 'monospace' }}>{p.materialNo}</span>：{p.from || '（首次）'} → <b>{p.to}</b>
              </div>
            ))}
          </div>

          <div style={{ marginBottom: 6, fontWeight: 600, fontSize: 13 }}>将更新的单（按状态分组）</div>
          <div style={{ marginBottom: 16 }}>
            {Object.entries(preview.byStatus).map(([status, count]) => (
              <Tag key={status} color="blue" style={{ marginBottom: 4 }}>{STATUS_LABEL[status] || status} × {count}</Tag>
            ))}
          </div>

          {preview.excludedQuotationCount > 0 && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message={`另有 ${preview.excludedQuotationCount} 张单不会被更新`}
              description={
                <span>
                  {Object.entries(preview.excludedByStatus).map(([status, count], i) => (
                    <span key={status}>{i > 0 ? '、' : ''}{STATUS_LABEL[status] || status} {count}</span>
                  ))}
                  —— 这些单将保持旧价。
                </span>
              }
            />
          )}

          {preview.breachedMaterials.length > 0 && (
            <Alert
              type="error"
              showIcon
              message="以下料号仍有跌破预警线的比对列"
              description={preview.breachedMaterials.map((m) => `${m.materialNo}（${m.breachedCount} 列）`).join('、')}
            />
          )}
        </>
      )}
    </Modal>
  );
};

export default ApproveImpactModal;
