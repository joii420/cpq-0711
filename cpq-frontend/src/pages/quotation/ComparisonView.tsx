import React, { useEffect, useMemo, useState } from 'react';
import { Table, Spin, Alert, Tag, Button, Switch, Space, message } from 'antd';
import { DownloadOutlined } from '@ant-design/icons';
import { useLinkedExcelRows } from './useLinkedExcelRows';
import { buildComparisonModel } from './comparisonModel';
import type { TagMeta } from './comparisonModel';
import { comparisonTagService } from '../../services/comparisonTagService';
import { comparisonExportService } from '../../services/comparisonExportService';
import type { LineItem } from './QuotationStep2';

interface Props {
  quotationId: string;
  customerId?: string;
  quoteTemplateId?: string;
  costingTemplateId?: string;
  quoteLineItems: LineItem[];
  costingLineItems: LineItem[];
}

const HIGHLIGHT_BG = '#fff1f0';

const ComparisonView: React.FC<Props> = ({
  quotationId, customerId, quoteTemplateId, costingTemplateId, quoteLineItems, costingLineItems,
}) => {
  const quote = useLinkedExcelRows({
    linkedTemplateId: quoteTemplateId, lineItems: quoteLineItems, customerId, templateId: quoteTemplateId ?? null,
  });
  const costing = useLinkedExcelRows({
    linkedTemplateId: costingTemplateId, lineItems: costingLineItems, customerId, templateId: costingTemplateId ?? null,
  });

  const [tagMetas, setTagMetas] = useState<TagMeta[]>([]);
  const [onlyDiff, setOnlyDiff] = useState(false);
  const [exporting, setExporting] = useState(false);

  useEffect(() => {
    comparisonTagService.list('ACTIVE')
      .then((r) => setTagMetas((r.data || []).map((t) => ({
        code: t.code, label: t.label, groupName: t.groupName,
        groupSortOrder: t.groupSortOrder, tagSortOrder: t.tagSortOrder,
      }))))
      .catch(() => setTagMetas([]));
  }, []);

  const resolving = useMemo(
    () =>
      quote.rows.some((r) => Object.values(r).includes('__loading__')) ||
      costing.rows.some((r) => Object.values(r).includes('__loading__')),
    [quote.rows, costing.rows],
  );

  const model = useMemo(
    () => buildComparisonModel(quote.parsedColumns, quote.rows, costing.parsedColumns, costing.rows, tagMetas),
    [quote.parsedColumns, quote.rows, costing.parsedColumns, costing.rows, tagMetas],
  );

  const visibleRows = useMemo(
    () => onlyDiff
      ? model.rows.filter((r) => Object.values(r.cells).some((c) => c.highlighted))
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
          if (v === null || v === undefined || v === '' || v === '__loading__') return <span style={{ color: '#bbb' }}>—</span>;
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
      const blob = new Blob([res.data], { type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' });
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

  if (quote.error || costing.error) {
    return <Alert type="error" showIcon message={quote.error || costing.error || '加载失败'} style={{ margin: 16 }} />;
  }
  if (quote.loading || costing.loading) {
    return <div style={{ textAlign: 'center', padding: 48 }}><Spin tip="加载模板配置…" /></div>;
  }
  if (resolving) return <div style={{ textAlign: 'center', padding: 48 }}><Spin tip="正在求值…" /></div>;

  if (model.columns.length === 0) {
    return (
      <Alert type="warning" showIcon style={{ margin: 16 }}
        message="没有可比对的字段"
        description="报价单 Excel 模板与核价单 Excel 模板没有共同的 comparison_tag。请在两侧 Excel 模板的列上配置相同的业务标签。" />
    );
  }

  return (
    <div>
      <Space style={{ marginBottom: 12 }}>
        <Button icon={<DownloadOutlined />} onClick={handleExport} loading={exporting}>导出比对 Excel</Button>
        <span>仅看有差异的料号 <Switch checked={onlyDiff} onChange={setOnlyDiff} size="small" /></span>
        <span style={{ color: '#999', fontSize: 12 }}>共 {model.rows.length} 个料号，{model.columns.length} 个比对字段</span>
      </Space>
      <Table
        rowKey="key" size="small" bordered
        columns={columns} dataSource={dataSource}
        pagination={false} scroll={{ x: 'max-content' }}
      />
    </div>
  );
};

export default ComparisonView;
