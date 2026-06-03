/**
 * useExcelSnapshotRows —— 从同侧 Excel 值快照(quoteExcelValues/costingExcelValues)解析渲染行。
 *
 * 与卡片视图读 quote/costing card values 同构：后端已在加产品/草稿重刷/编辑时算好 Excel 值快照，
 * 前端只渲染快照。缺快照 → 该行无值(显示"—")，不回退实时 getExcelView（设计：渲染纯走快照）。
 */
import { useMemo } from 'react';
import type { CostingTemplateColumn } from '../../services/costingTemplateService';
import type { LinkedExcelRow } from './useLinkedExcelRows';
import type { LineItem } from './QuotationStep2';

export interface UseExcelSnapshotRowsParams {
  lineItems: LineItem[];
  side: 'QUOTE' | 'COSTING';
  parsedColumns: CostingTemplateColumn[];
}

function normalize(v: any): any {
  if (v === null || v === undefined || v === '' || v === '—') return null;
  return v;
}

export function useExcelSnapshotRows(params: UseExcelSnapshotRowsParams): { rows: LinkedExcelRow[] } {
  const { lineItems, side, parsedColumns } = params;

  const rows = useMemo<LinkedExcelRow[]>(() => {
    return (lineItems || []).map((li, i) => {
      const json = side === 'QUOTE' ? li.quoteExcelValues : li.costingExcelValues;
      let cellMap: Record<string, any> = {};
      if (json) {
        try {
          const parsed = JSON.parse(json);
          const arr = Array.isArray(parsed?.rows) ? parsed.rows : [];
          if (arr.length > 0 && arr[0] && typeof arr[0] === 'object') cellMap = arr[0];
        } catch { /* 缺快照/解析失败 → 空 → 显示"—" */ }
      }
      const hfPartNo = li.productPartNo;
      const cellValues: Record<string, any> = {};
      for (const col of parsedColumns) cellValues[col.col_key] = normalize(cellMap[col.col_key]);
      return {
        __key: li.id ? `snap-${side}-${li.id}` : `snap-${side}-row-${i}`,
        __label: hfPartNo ?? `产品 ${i + 1}`,
        __hfPartNo: hfPartNo,
        __noData: !json,
        ...cellValues,
      };
    });
  }, [lineItems, side, parsedColumns]);

  return { rows };
}
