// ─────────────────────────────────────────────────────────────────────────────
// ValidationErrorTable —— 校验错误的统一呈现（task-260902 · F-9）
//
// 导入（F-6）与保存（F-4）复用同一个组件，字段 sheet / row / column / reason，
// 与 `api.md §1`（400 校验失败）逐字对齐。
//
// 🚫 AC-10：**一次列全**，不 fail-fast、不「仅显示前 N 条」、不截断 ——
//    超过阈值时表格**内部**纵向滚动（原型「数据导入-校验失败」的 max-height:300px）。
// ─────────────────────────────────────────────────────────────────────────────
import React from 'react';
import { Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { ValidationError } from './types';

const { Text } = Typography;

export interface ValidationErrorTableProps {
  errors: ValidationError[];
  /** 超过该条数时启用表格内部滚动（默认 8 行 ≈ 原型的 300px） */
  scrollAfter?: number;
}

const ValidationErrorTable: React.FC<ValidationErrorTableProps> = ({ errors, scrollAfter = 8 }) => {
  const columns: ColumnsType<ValidationError> = [
    { title: 'Sheet', dataIndex: 'sheet', key: 'sheet', width: 180 },
    { title: '行号', dataIndex: 'row', key: 'row', width: 90 },
    { title: '列名', dataIndex: 'column', key: 'column', width: 150 },
    {
      title: '原因',
      dataIndex: 'reason',
      key: 'reason',
      render: (reason: string, r: ValidationError & { value?: unknown }) => (
        <span>
          <Tag color="red">{reason}</Tag>
          {r.value !== undefined && r.value !== null && r.value !== '' && (
            <Text type="secondary">
              值 <code>{String(r.value)}</code>
            </Text>
          )}
        </span>
      ),
    },
  ];

  return (
    <Table<ValidationError>
      size="small"
      rowKey={(e, i) => `${e.sheet}-${e.row}-${e.column}-${e.reason}-${i}`}
      columns={columns}
      dataSource={errors}
      // 🚫 分页会变相「仅显示前 N 条」，AC-10 明确禁止 —— 一律关分页、走内部滚动
      pagination={false}
      scroll={errors.length > scrollAfter ? { y: 300 } : undefined}
    />
  );
};

export default ValidationErrorTable;
