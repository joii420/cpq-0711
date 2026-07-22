/**
 * CostingApprovePreviewDrawer —— 核价通过前置预览抽屉（task-0721 F1）
 *
 * 场景：财务点「核价通过」时，不直接提交，先调 GET .../costing-approve/preview 拿到
 * 本次通过将对基础数据造成的增/删/改清单 + previewToken，抽屉展示后财务确认才真正提交
 * POST .../costing-approve（带回 previewToken）。
 *
 * 契约：dev-docs/task-0721-报价升版逻辑/api.md §1；交互规范：fronttask.md F1。
 *
 * 错误处理（api.md §1.2 错误码表）：
 *   - 409（previewToken 漂移）：message.error 提示后自动重新拉 preview 刷新抽屉内容，不关闭。
 *   - 400/403：按 message 提示，不关抽屉。
 *   - 500：按 message 提示，关闭抽屉（整体失败，提示重试）。
 */
import React, { useEffect, useState, useCallback } from 'react';
import {
  Drawer, Button, Space, Row, Col, Card, Statistic, Collapse, Tag, Table,
  Spin, Alert, Typography, message,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { PlusOutlined, MinusOutlined, EditOutlined } from '@ant-design/icons';
import { costingOrderService } from '../../services/costingOrderService';
import type {
  CostingApprovePreviewResult,
  CostingApprovePreviewGroup,
  CostingApprovePreviewRow,
  CostingApproveResult,
} from '../../services/costingOrderService';

const { Text } = Typography;

interface Props {
  open: boolean;
  quotationId: string | undefined;
  /** 提交时附带的审批意见（可选，沿用现有 approve 入参） */
  comment?: string;
  onClose: () => void;
  /** 提交成功回调，拿到 approve 响应（含 backfill 汇总） */
  onApproved: (result: CostingApproveResult) => void;
}

const opTagOf = (op: CostingApprovePreviewRow['op']) => {
  if (op === 'ADD') return <Tag color="green" icon={<PlusOutlined />}>新增</Tag>;
  if (op === 'DELETE') return <Tag color="red" icon={<MinusOutlined />}>删除</Tag>;
  return <Tag color="orange" icon={<EditOutlined />}>改值</Tag>;
};

const contentOf = (row: CostingApprovePreviewRow): React.ReactNode => {
  if (row.op === 'CHANGE') {
    const entries = Object.entries(row.changes ?? {});
    if (entries.length === 0) return <Text type="secondary">—</Text>;
    return (
      <Space direction="vertical" size={2}>
        {entries.map(([col, [oldV, newV]]) => (
          <span key={col}>
            {col}：<Text delete type="secondary">{oldV ?? '—'}</Text> → <Text strong>{newV ?? '—'}</Text>
          </span>
        ))}
      </Space>
    );
  }
  const entries = Object.entries(row.values ?? {});
  const text = entries.map(([k, v]) => `${k}: ${v ?? '—'}`).join('，');
  if (row.op === 'DELETE') {
    return <Text delete type="danger">{text || '—'}</Text>;
  }
  return <Text type="success">{text || '—'}</Text>;
};

const groupKeySummary = (groupKey: Record<string, string>): string =>
  Object.entries(groupKey ?? {}).map(([k, v]) => `${k}=${v}`).join('，') || '—';

const rowColumns: ColumnsType<CostingApprovePreviewRow> = [
  { title: '操作', key: 'op', width: 90, render: (_: any, r) => opTagOf(r.op) },
  { title: '内容', key: 'content', render: (_: any, r) => contentOf(r) },
];

const CostingApprovePreviewDrawer: React.FC<Props> = ({ open, quotationId, comment, onClose, onApproved }) => {
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [preview, setPreview] = useState<CostingApprovePreviewResult | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);

  const loadPreview = useCallback(async () => {
    if (!quotationId) return;
    setLoading(true);
    setLoadError(null);
    try {
      const res = await costingOrderService.previewApprove(quotationId);
      setPreview(res.data);
    } catch (e: any) {
      setPreview(null);
      setLoadError(e?.message || '加载预览失败');
    } finally {
      setLoading(false);
    }
  }, [quotationId]);

  useEffect(() => {
    if (open) {
      setPreview(null);
      setLoadError(null);
      loadPreview();
    }
  }, [open, loadPreview]);

  const handleConfirm = async () => {
    if (!quotationId || !preview) return;
    setSubmitting(true);
    try {
      const res = await costingOrderService.approve(quotationId, preview.previewToken, comment);
      message.success('核价通过');
      onApproved(res.data);
      onClose();
    } catch (e: any) {
      if (e?.httpStatus === 409) {
        message.error('报价数据在预览后发生变化，请重新预览');
        await loadPreview();
      } else if (e?.httpStatus === 500) {
        message.error(e?.message || '核价通过失败，请重试');
        onClose();
      } else {
        message.error(e?.message || '操作失败');
      }
    } finally {
      setSubmitting(false);
    }
  };

  const summary = preview?.summary;
  const noImpact = !!summary
    && summary.versionedGroups === 0 && summary.addedRows === 0
    && summary.deletedRows === 0 && summary.changedRows === 0;

  const collapseItems = (preview?.groups ?? []).map((g: CostingApprovePreviewGroup, i: number) => ({
    key: String(i),
    label: (
      <Space wrap size={6}>
        <Tag>{g.tabName}</Tag>
        <Text type="secondary" style={{ fontSize: 12 }}>{groupKeySummary(g.groupKey)}</Text>
        <Text type="secondary" style={{ fontSize: 12 }}>
          {g.versionFrom ?? '首版'} → {g.versionTo}
        </Text>
        {g.isGlobalShared && <Tag color="red">全局共享，影响所有客户</Tag>}
        <Text type="secondary" style={{ fontSize: 12 }}>（{g.rows.length} 行变更）</Text>
      </Space>
    ),
    children: (
      <Table<CostingApprovePreviewRow>
        size="small"
        rowKey={(r, idx) => r.__v6_id ?? `add-${idx}`}
        columns={rowColumns}
        dataSource={g.rows}
        pagination={g.rows.length > 20 ? { pageSize: 20, size: 'small' } : false}
      />
    ),
  }));

  return (
    <Drawer
      title="核价通过预览"
      placement="right"
      width={960}
      open={open}
      onClose={onClose}
      destroyOnClose
      extra={
        <Space>
          <Button onClick={onClose}>取消</Button>
          <Button
            type="primary"
            loading={submitting}
            disabled={loading || !preview}
            onClick={handleConfirm}
          >
            确认通过
          </Button>
        </Space>
      }
    >
      {loading && (
        <div style={{ textAlign: 'center', padding: 60 }}>
          <Spin size="large" tip="正在计算本次通过将造成的基础数据变更…" />
        </div>
      )}

      {!loading && loadError && (
        <Alert
          type="error"
          showIcon
          message="预览加载失败"
          description={loadError}
          action={<Button size="small" onClick={loadPreview}>重试</Button>}
        />
      )}

      {!loading && !loadError && preview && (
        <>
          <Row gutter={16} style={{ marginBottom: 16 }}>
            <Col span={6}>
              <Card size="small">
                <Statistic title="将升版组数" value={summary?.versionedGroups ?? 0} />
              </Card>
            </Col>
            <Col span={6}>
              <Card size="small">
                <Statistic
                  title="新增行"
                  value={summary?.addedRows ?? 0}
                  prefix={<PlusOutlined />}
                  valueStyle={{ color: '#52c41a' }}
                />
              </Card>
            </Col>
            <Col span={6}>
              <Card size="small">
                <Statistic
                  title="删除行"
                  value={summary?.deletedRows ?? 0}
                  prefix={<MinusOutlined />}
                  valueStyle={{ color: '#ff4d4f' }}
                />
              </Card>
            </Col>
            <Col span={6}>
              <Card size="small">
                <Statistic
                  title="改值行"
                  value={summary?.changedRows ?? 0}
                  prefix={<EditOutlined />}
                  valueStyle={{ color: '#fa8c16' }}
                />
              </Card>
            </Col>
          </Row>

          {noImpact ? (
            <Alert
              type="info"
              showIcon
              message="本次通过无基础数据变更，仅完成审核状态流转"
              style={{ marginBottom: 16 }}
            />
          ) : (
            <Collapse size="small" items={collapseItems} />
          )}
        </>
      )}
    </Drawer>
  );
};

export default CostingApprovePreviewDrawer;
