/**
 * ComparisonTable —— 共享比对表渲染组件（零取数，纯展示）。
 * 消费方：ComparisonView（编辑态，吃 useLinkedExcelRows 算值）
 *         ReadonlyComparison（详情页只读，吃冻结 Excel 快照）
 * 两侧取数方式不同，但渲染逻辑完全共用，避免 AP-50 双源分叉。
 */
import React, { useMemo, useState } from 'react';
import { Table, Tag, Button, Switch, Space, message } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import type { ComparisonModel } from './comparisonModel';
import { comparisonExportService } from '../../services/comparisonExportService';

const HIGHLIGHT_BG = '#fff1f0';

export const ComparisonTable: React.FC<{ model: ComparisonModel; quotationId: string }> = ({
  model,
  quotationId,
}) => {
  const [onlyDiff, setOnlyDiff] = useState(false);
  const [exporting, setExporting] = useState(false);

  // 「仅看有差异的料号」：单边料号(仅报价/仅核价)本身即最大差异，过滤时也保留
  const visibleRows = useMemo(
    () =>
      onlyDiff
        ? model.rows.filter((r) => r.presence !== 'BOTH' || Object.values(r.cells).some((c) => c.highlighted))
        : model.rows,
    [model.rows, onlyDiff],
  );

  // 每个料号展开为两行（报价 / 核价）；料号列用 rowSpan 合并
  const dataSource = useMemo(() => {
    const out: any[] = [];
    for (const r of visibleRows) {
      out.push({ key: `${r.partNo}__q`, partNo: r.partNo, presence: r.presence, cells: r.cells, side: 'quote', _span: 2 });
      out.push({ key: `${r.partNo}__c`, partNo: r.partNo, presence: r.presence, cells: r.cells, side: 'costing', _span: 0 });
    }
    return out;
  }, [visibleRows]);

  const columns = useMemo(() => {
    const base: any[] = [
      {
        title: '料号', key: 'partNo', fixed: 'left', width: 200,
        onCell: (rec: any) => ({ rowSpan: rec._span }),
        render: (_: any, rec: any) => (
          <span style={{ fontFamily: 'monospace' }}>
            {rec.partNo}
            {rec.presence !== 'BOTH' && (
              <Tag color="orange" style={{ marginLeft: 6 }}>
                {rec.presence === 'QUOTE_ONLY' ? '仅报价' : '仅核价'}
              </Tag>
            )}
          </span>
        ),
      },
      {
        title: '口径', key: 'side', fixed: 'left', width: 80,
        render: (_: any, rec: any) => (rec.side === 'quote' ? '报价' : '核价'),
      },
    ];
    for (const col of model.columns) {
      base.push({
        title: (
          <span>
            {col.groupName && <span style={{ color: '#999', marginRight: 4 }}>[{col.groupName}]</span>}
            {col.label}
          </span>
        ),
        key: col.tag, width: 140,
        onCell: (rec: any) => (rec.cells[col.tag]?.highlighted ? { style: { background: HIGHLIGHT_BG } } : {}),
        render: (_: any, rec: any) => {
          const pair = rec.cells[col.tag];
          const v = rec.side === 'quote' ? pair?.quote : pair?.costing;
          if (v === null || v === undefined || v === '' || v === '__loading__')
            return <span style={{ color: '#bbb' }}>—</span>;
          return typeof v === 'number' ? v.toLocaleString() : String(v);
        },
      });
    }
    return base;
  }, [model.columns]);

  const handleExport = async () => {
    setExporting(true);
    try {
      const res = await comparisonExportService.export(quotationId, model);
      const blob = new Blob([res.data], {
        type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `comparison-${quotationId}.xlsx`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e: any) {
      message.error(e?.message || '导出失败');
    } finally {
      setExporting(false);
    }
  };

  return (
    <div>
      <Space style={{ marginBottom: 12 }}>
        <Button icon={<DownloadOutlined />} onClick={handleExport} loading={exporting}>
          导出比对 Excel
        </Button>
        <span>
          仅看有差异的料号 <Switch checked={onlyDiff} onChange={setOnlyDiff} size="small" />
        </span>
        <span style={{ color: '#999', fontSize: 12 }}>
          共 {model.rows.length} 个料号，{model.columns.length} 个比对字段
        </span>
      </Space>
      <Table
        rowKey="key"
        size="small"
        bordered
        columns={columns}
        dataSource={dataSource}
        pagination={false}
        scroll={{ x: 'max-content' }}
      />
    </div>
  );
};
