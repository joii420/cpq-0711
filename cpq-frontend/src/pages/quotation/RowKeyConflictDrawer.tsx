import React from 'react';
import { Drawer, Table, Typography, Button, Alert } from 'antd';

export interface RowKeyConflictDTO {
  lineItemId?: string;
  productName?: string;
  productPartNo?: string;
  componentId?: string;
  tabName?: string;
  rowKey: string;
  rowIndices: number[];
}

interface Props {
  open: boolean;
  conflicts: RowKeyConflictDTO[];
  onLocate: (c: RowKeyConflictDTO) => void;
  onClose: () => void;
}

const RowKeyConflictDrawer: React.FC<Props> = ({ open, conflicts, onLocate, onClose }) => {
  const columns = [
    {
      title: '料号',
      key: 'product',
      render: (_: any, c: RowKeyConflictDTO) => (
        <span>{c.productName ?? '—'}{c.productPartNo ? ` (${c.productPartNo})` : ''}</span>
      ),
    },
    { title: '页签', dataIndex: 'tabName', key: 'tabName', render: (v: string) => v ?? '—' },
    { title: '行键', dataIndex: 'rowKey', key: 'rowKey' },
    {
      title: '参考行号',
      key: 'rows',
      render: (_: any, c: RowKeyConflictDTO) => (c.rowIndices ?? []).join(', '),
    },
    {
      title: '操作',
      key: 'op',
      render: (_: any, c: RowKeyConflictDTO) => (
        <Button type="link" size="small" onClick={() => onLocate(c)}>定位</Button>
      ),
    },
  ];

  return (
    <Drawer title="提交校验未通过：行键重复" placement="right" width={720} open={open} onClose={onClose}>
      <Alert
        type="error"
        showIcon
        style={{ marginBottom: 16 }}
        message={`共 ${conflicts.length} 处行键重复，请逐个修正后重新提交`}
        description="点「定位」跳到对应料号卡片与页签。参考行号为后端校验序，仅作参考。"
      />
      <Table
        rowKey={(c, i) => `${c.lineItemId ?? ''}-${c.componentId ?? ''}-${c.rowKey}-${i}`}
        columns={columns}
        dataSource={conflicts}
        pagination={false}
        size="small"
      />
      <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>
        提示：行键由「行键字段」组合算出，同一组件内不可重复。请去重或调整行键字段配置后重试。
      </Typography.Paragraph>
    </Drawer>
  );
};

export default RowKeyConflictDrawer;
