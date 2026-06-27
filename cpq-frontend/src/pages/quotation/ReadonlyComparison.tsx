/**
 * ReadonlyComparison —— 详情页只读比对。
 * 零计算：吃两份已冻结 Excel 快照 + comparison_tag → buildComparisonModel → ComparisonTable。
 * 不走 useLinkedExcelRows，不触发任何公式求值。
 * 任一侧快照全部缺失 → 显示"暂无数据"。
 */
import React, { useEffect, useMemo, useState } from 'react';
import { Empty } from 'antd';
import { useExcelSnapshotRows } from './useExcelSnapshotRows';
import { buildComparisonModel } from './comparisonModel';
import type { TagMeta } from './comparisonModel';
import { ComparisonTable } from './comparisonTable';
import { comparisonTagService } from '../../services/comparisonTagService';
import type { CostingTemplateColumn } from '../../services/costingTemplateService';
import type { LineItem } from './QuotationStep2';

interface Props {
  quotationId: string;
  lineItems: LineItem[];
  quoteColumns: CostingTemplateColumn[] | null | undefined;
  costingColumns: CostingTemplateColumn[] | null | undefined;
}

const EMPTY_COLS: CostingTemplateColumn[] = [];

const ReadonlyComparison: React.FC<Props> = ({ quotationId, lineItems, quoteColumns, costingColumns }) => {
  const [tagMetas, setTagMetas] = useState<TagMeta[]>([]);

  useEffect(() => {
    comparisonTagService.list('ACTIVE')
      .then((r: any) => setTagMetas((r.data || []).map((t: any) => ({
        code: t.code, label: t.label, groupName: t.groupName,
        groupSortOrder: t.groupSortOrder, tagSortOrder: t.tagSortOrder,
      }))))
      .catch(() => setTagMetas([]));
  }, []);

  const quoteCols = quoteColumns ?? EMPTY_COLS;
  const costingCols = costingColumns ?? EMPTY_COLS;

  const { rows: quoteRows } = useExcelSnapshotRows({ lineItems, side: 'QUOTE', parsedColumns: quoteCols });
  const { rows: costingRows } = useExcelSnapshotRows({ lineItems, side: 'COSTING', parsedColumns: costingCols });

  // 任一侧所有行都带 __noData（即无快照 json）→ 视为暂无数据
  const bothHaveSnapshot =
    quoteRows.some((r) => !(r as any).__noData) && costingRows.some((r) => !(r as any).__noData);

  const model = useMemo(
    () => buildComparisonModel(quoteCols, quoteRows, costingCols, costingRows, tagMetas),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [quoteCols, quoteRows, costingCols, costingRows, tagMetas],
  );

  if (!bothHaveSnapshot || model.columns.length === 0) {
    return <Empty description="暂无数据" style={{ padding: 48 }} />;
  }

  return <ComparisonTable model={model} quotationId={quotationId} />;
};

export default ReadonlyComparison;
