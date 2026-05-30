import React from 'react';
import { Table, Card, Spin, Alert, Tag } from 'antd';
import type { CostingTemplateColumn } from '../../services/costingTemplateService';
import type { LineItem } from './QuotationStep2';
import { useLinkedExcelRows } from './useLinkedExcelRows';

interface Props {
  linkedTemplateId?: string;
  lineItems: LineItem[];
  quotationContext?: Record<string, any>;
  viewLabel?: string;
  customerId?: string;
  quotationId?: string | null;
  quotationStatus?: string | null;
  templateId?: string | null;
  /** @deprecated 旧字段，已由 templateId 替代。 */
  costingTemplateId?: string | null;
}

const LinkedExcelView: React.FC<Props> = ({
  linkedTemplateId,
  lineItems,
  quotationContext,
  viewLabel,
  customerId,
  quotationId,
  quotationStatus,
  templateId,
  costingTemplateId: _costingTemplateId, // @deprecated 已由 templateId 替代, 接收但不使用
}) => {
  const { rows, parsedColumns, excelTemplate, loading, error } = useLinkedExcelRows({
    linkedTemplateId, lineItems, customerId, templateId, quotationContext, quotationId, quotationStatus,
  });

  if (!linkedTemplateId) {
    return (
      <Alert type="info" showIcon
        message={`本报价单未绑定${viewLabel || ''}模板`}
        description="无法定位关联 Excel 模板。请先确认报价单已选择模板。"
        style={{ margin: 16 }} />
    );
  }
  if (loading) return <div style={{ textAlign: 'center', padding: 48 }}><Spin /></div>;
  if (error) return <Alert type="error" showIcon message={error} style={{ margin: 16 }} />;
  if (!excelTemplate) {
    return (
      <Alert type="warning" showIcon
        message="未找到关联的 Excel 模板"
        description="请前往「Excel 模板配置」菜单：在某个 Excel 模板上把「关联模板」设为本报价单使用的模板，并发布；同一关联模板内可设一份默认。"
        style={{ margin: 16 }} />
    );
  }
  if (parsedColumns.length === 0) {
    return (
      <Alert type="warning" showIcon
        message="关联的 Excel 模板未配置任何列"
        description={`Excel 模板「${excelTemplate.name}」没有列定义。请前往「Excel 模板配置」打开它，添加列后重试。`}
        style={{ margin: 16 }} />
    );
  }

  const visibleColumns = parsedColumns.filter((col: CostingTemplateColumn) => !col.hidden);
  const tableColumns = [
    {
      title: '客户料号', dataIndex: '__label', key: '__label',
      fixed: 'left' as const, width: 200,
      render: (v: string) => <span style={{ fontFamily: 'monospace' }}>{v}</span>,
    },
    ...visibleColumns.map((col) => ({
      title: (
        <span>
          <span style={{ color: '#999', marginRight: 4 }}>[{col.col_key}]</span>
          {col.title}
        </span>
      ),
      dataIndex: col.col_key, key: col.col_key, width: 140,
      render: (val: any) => {
        if (val === '__loading__') return <span style={{ color: '#bbb' }}>加载中…</span>;
        if (val === null || val === undefined || val === '') return <span style={{ color: '#bbb' }}>—</span>;
        if (typeof val === 'number') return val.toLocaleString();
        return String(val);
      },
    })),
  ];

  return (
    <div>
      <Card size="small" style={{ marginBottom: 12 }}
        title={
          <span>
            {viewLabel || 'Excel 视图'} · <Tag color="blue">{excelTemplate.name}</Tag>
            <Tag>{excelTemplate.version}</Tag>
            {excelTemplate.isDefault && <Tag color="gold">默认</Tag>}
            <Tag color={excelTemplate.status === 'PUBLISHED' ? 'green' : 'default'}>
              {excelTemplate.status === 'PUBLISHED' ? '已发布' : excelTemplate.status}
            </Tag>
          </span>
        }
        extra={<span style={{ fontSize: 12, color: '#999' }}>按本报价单产品行渲染，共 {rows.length} 行</span>}
      />
      <Table rowKey="__key" size="small" columns={tableColumns}
        dataSource={rows} pagination={false} scroll={{ x: 'max-content' }} />
    </div>
  );
};

export default LinkedExcelView;
