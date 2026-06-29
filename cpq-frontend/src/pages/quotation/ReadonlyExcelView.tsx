/**
 * ReadonlyExcelView —— 详情页只读 Excel 视图。纯读快照：
 * 行值来自 useExcelSnapshotRows(quote/costingExcelValues)，列定义来自 getById 捎回的有效列。
 * 零后端值计算、零 ensure。核价侧为 BOM 树（按 __lvl 缩进）。
 */
import React from 'react';
import { Empty, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { useExcelSnapshotRows } from './useExcelSnapshotRows';
import { renderCellValue } from './excelCellFormat';
import type { CostingTemplateColumn } from '../../services/costingTemplateService';
import type { LineItem } from './QuotationStep2';

interface Props {
  lineItems: LineItem[];
  side: 'QUOTE' | 'COSTING';
  columns: CostingTemplateColumn[] | null | undefined;
}

const ReadonlyExcelView: React.FC<Props> = ({ lineItems, side, columns }) => {
  const parsedColumns = (columns ?? []).filter(c => !c.hidden);
  const { rows } = useExcelSnapshotRows({ lineItems, side, parsedColumns });

  const hasAnyValue = rows.some(r => !r.__noData);
  if (!parsedColumns.length || !hasAnyValue) {
    return <Empty description="暂无数据" style={{ padding: 48 }} />;
  }

  const tableColumns: ColumnsType<(typeof rows)[number]> = [
    {
      title: '产品/节点',
      dataIndex: '__label',
      key: '__label',
      fixed: 'left' as const,
      width: 220,
      render: (v: any, rec) => (
        <span style={{ paddingLeft: (rec['__lvl'] ?? 0) * 16, fontFamily: 'monospace' }}>
          {v}
        </span>
      ),
    },
    ...parsedColumns.map((col: CostingTemplateColumn) => ({
      title: col.title || col.col_key,
      dataIndex: col.col_key,
      key: col.col_key,
      render: (v: any) => renderCellValue(v, col),
    })),
  ];

  return (
    <Table
      rowKey="__key"
      size="small"
      bordered
      columns={tableColumns}
      dataSource={rows}
      pagination={false}
      scroll={{ x: 'max-content' }}
    />
  );
};

export default ReadonlyExcelView;
