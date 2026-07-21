// task-0721 F5：报价侧 BOM 树 —— 删除确认弹窗（核心交互）。
//
// 所有删除操作（剪枝 PRUNE / 行删除 ROW）都必须先调预览接口，展示完整删减明细后用户确认才执行，
// 不允许静默级联（需求说明 §4.3 规则四 / api.md §4-§5）。
//
// 第③块「因仍有其他引用而不删的料号(retainedParts)」不能省——DAG 重复子件场景下用户会疑惑
// "为什么材质页签数据还在"，这块就是回答他的（design.md §6.4）。
import React, { useEffect, useState } from 'react';
import { Alert, Button, Drawer, Empty, Space, Table, Tag, message } from 'antd';
import type { ApiError } from '../../services/api';
import { quotationService } from '../../services/quotationService';
import type {
  TreeDeletePreviewResult,
} from '../../services/quotationService';
import type { LineItem } from './QuotationStep2';

export interface BomTreeDeleteRequest {
  componentId: string;
  mode: 'PRUNE' | 'ROW';
  nodeId: string;
  rowKey?: string;
}

interface Props {
  item: LineItem;
  quotationId?: string;
  request: BomTreeDeleteRequest | null;
  onClose: () => void;
  onApplied: (quoteCardValues: string) => void;
}

const BomTreeDeleteConfirmDrawer: React.FC<Props> = ({ item, quotationId, request, onClose, onApplied }) => {
  const [loading, setLoading] = useState(false);
  const [preview, setPreview] = useState<TreeDeletePreviewResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirming, setConfirming] = useState(false);

  const lineItemId = (item as any).id || (item as any).tempId;

  const runPreview = async () => {
    if (!request || !quotationId || !lineItemId) return;
    setLoading(true);
    setError(null);
    setPreview(null);
    try {
      const res = await quotationService.previewTreeDelete(quotationId, lineItemId, {
        componentId: request.componentId,
        mode: request.mode,
        nodeId: request.nodeId,
        rowKey: request.rowKey,
      });
      setPreview((res as any)?.data ?? null);
    } catch (e: unknown) {
      const err = e as ApiError;
      setError(err.message || '预览删除影响面失败');
    } finally {
      setLoading(false);
    }
  };

  // 每次 request 变化（新的一次剪枝/删行点击）都重新预览
  useEffect(() => {
    if (request) runPreview();
    else {
      setPreview(null);
      setError(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [request]);

  const handleClose = () => {
    setPreview(null);
    setError(null);
    onClose();
  };

  const handleConfirm = async () => {
    if (!request || !preview || !quotationId || !lineItemId) return;
    setConfirming(true);
    try {
      const res = await quotationService.executeTreeDelete(quotationId, lineItemId, {
        componentId: request.componentId,
        mode: request.mode,
        nodeId: request.nodeId,
        rowKey: request.rowKey,
        previewToken: preview.previewToken,
      });
      const data = (res as any)?.data;
      if (data?.quoteCardValues) onApplied(data.quoteCardValues);
      message.success(request.mode === 'PRUNE' ? '已剪枝并同步级联删除' : '已删除该行');
      handleClose();
    } catch (e: unknown) {
      const err = e as ApiError;
      if (err.httpStatus === 409) {
        // previewToken 失效（树在预览后变化）→ 提示并自动重新预览（api.md §5）
        message.warning('数据已变化，请重新确认');
        runPreview();
      } else {
        message.error(err.message || '删除失败');
      }
    } finally {
      setConfirming(false);
    }
  };

  return (
    <Drawer
      title={request?.mode === 'PRUNE' ? '确认剪枝（含子树）' : '确认删除该行'}
      placement="right"
      width={720}
      open={!!request}
      onClose={handleClose}
      destroyOnClose
      extra={
        <Space>
          <Button onClick={handleClose}>取消</Button>
          <Button danger type="primary" loading={confirming} disabled={!preview || loading} onClick={handleConfirm}>
            确认删除
          </Button>
        </Space>
      }
    >
      {loading && <div style={{ padding: 24, textAlign: 'center', color: '#999' }}>正在计算删除影响面…</div>}
      {error && <Alert type="error" showIcon message="预览失败" description={error} style={{ marginBottom: 12 }} />}
      {preview && (
        <>
          <Alert
            type="warning"
            showIcon
            style={{ marginBottom: 16 }}
            message="此操作不可静默撤销"
            description="确认后将按下方明细写入墓碑记录（可由管理员协助恢复），不做物理删除；请仔细核对三块内容后再确认。"
          />

          <h4>① 将从树上移除的节点</h4>
          {preview.treeNodes.length === 0 ? (
            <Empty description="无" style={{ margin: '8px 0 16px' }} />
          ) : (
            <Table
              size="small"
              rowKey="nodeId"
              pagination={false}
              style={{ marginBottom: 16 }}
              dataSource={preview.treeNodes}
              columns={[
                { title: '料号', dataIndex: 'partNo', key: 'partNo' },
                { title: '层级', dataIndex: 'lvl', key: 'lvl', width: 80 },
              ]}
            />
          )}

          <h4>② 各页签将被级联删除的行</h4>
          {preview.cascadeTabs.length === 0 ? (
            <Empty description="无其余页签数据受影响" style={{ margin: '8px 0 16px' }} />
          ) : (
            preview.cascadeTabs.map((tab) => (
              <div key={tab.componentId} style={{ marginBottom: 12 }}>
                <Tag color="orange">{tab.tabName}</Tag>
                <Table
                  size="small"
                  rowKey="rowKey"
                  pagination={false}
                  style={{ marginTop: 6 }}
                  dataSource={tab.rows}
                  columns={[
                    { title: '料号', dataIndex: 'partNo', key: 'partNo' },
                    { title: '摘要', dataIndex: 'summary', key: 'summary' },
                  ]}
                />
              </div>
            ))
          )}

          {/* 第③块不能省 —— DAG 重复子件场景下用户会疑惑"为什么材质页签数据还在"，
              retainedParts 就是回答这个问题的（design.md §6.4）。 */}
          <h4>③ 因仍有其他引用而【不删】的料号</h4>
          {preview.retainedParts.length === 0 ? (
            <Empty description="无" style={{ margin: '8px 0 16px' }} />
          ) : (
            <Table
              size="small"
              rowKey="partNo"
              pagination={false}
              dataSource={preview.retainedParts}
              columns={[
                { title: '料号', dataIndex: 'partNo', key: 'partNo' },
                { title: '剩余引用数', dataIndex: 'remainingOccurrences', key: 'remainingOccurrences', width: 100 },
                { title: '理由', dataIndex: 'reason', key: 'reason' },
              ]}
            />
          )}
        </>
      )}
    </Drawer>
  );
};

export default BomTreeDeleteConfirmDrawer;
