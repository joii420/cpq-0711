import React, { useEffect, useMemo, useState } from 'react';
import { Spin, Alert } from 'antd';
import { useLinkedExcelRows } from './useLinkedExcelRows';
import { buildComparisonModel } from './comparisonModel';
import type { TagMeta } from './comparisonModel';
import { ComparisonTable } from './comparisonTable';
import { comparisonTagService } from '../../services/comparisonTagService';
import type { LineItem } from './QuotationStep2';

interface Props {
  quotationId: string;
  customerId?: string;
  quoteTemplateId?: string;
  costingTemplateId?: string;
  quoteLineItems: LineItem[];
  costingLineItems: LineItem[];
}

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

  if (quote.error || costing.error) {
    return <Alert type="error" showIcon message={quote.error || costing.error || '加载失败'} style={{ margin: 16 }} />;
  }
  if (quote.loading || costing.loading) {
    return <div style={{ textAlign: 'center', padding: 48 }}><Spin tip="加载模板配置…" /></div>;
  }
  if (resolving) {
    return <div style={{ textAlign: 'center', padding: 48 }}><Spin tip="正在求值…" /></div>;
  }

  if (model.columns.length === 0) {
    return (
      <Alert type="warning" showIcon style={{ margin: 16 }}
        message="没有可比对的字段"
        description="报价单 Excel 模板与核价单 Excel 模板没有共同的 comparison_tag。请在两侧 Excel 模板的列上配置相同的业务标签。" />
    );
  }

  return <ComparisonTable model={model} quotationId={quotationId} />;
};

export default ComparisonView;
