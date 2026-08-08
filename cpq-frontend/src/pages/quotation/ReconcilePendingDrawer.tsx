import React from 'react';
import { Drawer, Table, Typography, Button, Alert } from 'antd';
import type { ColumnsType } from 'antd/es/table';

/**
 * task-0806 · api.md API-3 §「新增错误响应 409」reason=RECONCILE_PENDING 的 conflicts 元素。
 * 与 RowKeyConflictDrawer.tsx 的 RowKeyConflictDTO 是两个不同的 409 场景（不同 reason），
 * 字段形状也不同 —— 不复用同一个 Drawer/类型，避免把两条互不相干的校验硬揉进一份数据结构。
 */
export interface ReconcileConflictDTO {
  lineItemId?: string;
  productPartNo?: string;
  tabName?: string;
  rowKey: string;
  fieldName: string;
  frontendValue: any;
  backendValue: any;
}

interface Props {
  open: boolean;
  conflicts: ReconcileConflictDTO[];
  /**
   * 定位到该格：api.md 的 conflicts 元素不带 componentId（只有 tabName），故只能定位到
   * 对应产品卡片（scroll into view），不能像 RowKeyConflictDrawer 那样精确切到目标页签。
   * 已知限制，见 test-report.md。
   */
  onLocate: (c: ReconcileConflictDTO) => void;
  onClose: () => void;
}

const fmtVal = (v: any): string => (typeof v === 'number' ? String(v) : (v == null ? '—' : String(v)));

const ReconcilePendingDrawer: React.FC<Props> = ({ open, conflicts, onLocate, onClose }) => {
  const columns: ColumnsType<ReconcileConflictDTO> = [
    { title: '料号', dataIndex: 'productPartNo', key: 'productPartNo', render: (v: string) => v ?? '—' },
    { title: '页签', dataIndex: 'tabName', key: 'tabName', render: (v: string) => v ?? '—' },
    { title: '行', dataIndex: 'rowKey', key: 'rowKey' },
    { title: '列', dataIndex: 'fieldName', key: 'fieldName' },
    {
      title: '前端值',
      key: 'frontendValue',
      render: (_: any, c: ReconcileConflictDTO) => fmtVal(c.frontendValue),
    },
    {
      title: '后端值',
      key: 'backendValue',
      render: (_: any, c: ReconcileConflictDTO) => fmtVal(c.backendValue),
    },
    {
      title: '操作',
      key: 'op',
      render: (_: any, c: ReconcileConflictDTO) => (
        <Button type="link" size="small" onClick={() => onLocate(c)}>定位到该格</Button>
      ),
    },
  ];

  return (
    <Drawer title="提交校验未通过：前后端算值不一致" placement="right" width={960} open={open} onClose={onClose}>
      <Alert
        type="error"
        showIcon
        style={{ marginBottom: 16 }}
        message={`共 ${conflicts.length} 处前后端算值分歧，请逐格核对后重新提交`}
        description="点「定位到该格」跳到对应料号卡片；该格在编辑页会显示 ⚠ 徽标，鼠标悬停查看两边输入明细。"
      />
      <Table
        rowKey={(c, i) => `${c.lineItemId ?? ''}-${c.tabName ?? ''}-${c.rowKey}-${c.fieldName}-${i}`}
        columns={columns}
        dataSource={conflicts}
        pagination={false}
        size="small"
      />
      <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>
        提示：DRAFT 单据行内取值走前端引擎、后端异步照算对账；出现分歧多数是"输入不是同一份"
        （字段解析口径/行序/公式没被解析到），真实算术差异反而最少见。
      </Typography.Paragraph>
    </Drawer>
  );
};

export default ReconcilePendingDrawer;
