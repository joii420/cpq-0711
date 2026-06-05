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
    return (lineItems || []).flatMap((li, i) => {
      const json = side === 'QUOTE' ? li.quoteExcelValues : li.costingExcelValues;
      let arr: any[] = [];
      if (json) {
        try {
          const parsed = JSON.parse(json);
          arr = Array.isArray(parsed?.rows) ? parsed.rows : [];
        } catch { /* 缺快照/解析失败 → 空 → 显示"—" */ }
      }
      const hfPartNo = li.productPartNo;
      // P2-B 核价 Excel 树：快照行带 __nodeId → 每个 BOM 节点出一行；否则(报价/旧快照)退化单行
      const isTree = side === 'COSTING' && arr.length > 0 && arr.some(r => r && r.__nodeId !== undefined);
      const src = isTree ? arr : [arr.length > 0 ? arr[0] : {}];
      return src.map((cell: any, ni: number) => {
        const cm = (cell && typeof cell === 'object') ? cell : {};
        const cellValues: Record<string, any> = {};
        for (const col of parsedColumns) cellValues[col.col_key] = normalize(cm[col.col_key]);
        const nodeId = cm.__nodeId;
        return {
          __key: li.id
            ? `snap-${side}-${li.id}-${isTree ? (nodeId || ni) : 'r'}`
            : `snap-${side}-row-${i}-${ni}`,
          __label: (isTree ? cm.__hfPartNo : hfPartNo) ?? `产品 ${i + 1}`,
          __hfPartNo: isTree ? cm.__hfPartNo : hfPartNo,
          __parentNo: isTree ? cm.__parentNo : undefined,
          __bomVersion: isTree ? cm.__bomVersion : undefined,
          __lvl: isTree ? (cm.__lvl ?? 0) : 0,
          __noData: !json,
          ...cellValues,
        } as LinkedExcelRow;
      });
    });
  }, [lineItems, side, parsedColumns]);

  return { rows };
}
