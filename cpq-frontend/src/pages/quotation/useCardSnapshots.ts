import { useMemo } from 'react';
import { bnfDriverLookupKey } from './useDriverExpansions';
import type {
  CardStructure,
  CardStructureTab,
  CardStructureField,
  CardValues,
  CardValuesTab,
} from '../../services/quotationService';

/**
 * Phase 2 Task 4 — 报价单整份快照「读」hook（**旁路**，不改渲染）。
 *
 * <p>给定 quotation（顶层带 4 份结构快照）+ lineItem（带 4 份值快照 JSON 字符串）+ side，
 * 解析出该侧卡片的 structure + values，并提供按 (componentId, rowIndex, fieldName) 的取值器。
 *
 * <p><b>取值优先级</b>（与前端 ComponentCell / computeAllFormulas 口径对齐）：
 * <ol>
 *   <li>editRows[rowKey].values[field]（用户编辑，最高）</li>
 *   <li>FORMULA → formulaResults[rowKey].values[field]</li>
 *   <li>BASIC_DATA → baseRows[i].basicDataValues[{basicDataPath}]</li>
 *   <li>DATA_SOURCE → basicDataValues 的 @gvar:CODE / {bnf_path}（按 binding）</li>
 *   <li>FIXED_VALUE → field.defaultValue</li>
 *   <li>其余（INPUT 等）→ driverRow[field] ?? field.defaultValue</li>
 * </ol>
 *
 * <p><b>纪律</b>：本 hook 只读快照，**不调** batch-expand / enrich（彻底脱钩）。
 * <p><b>已知边界</b>：LIST_FORMULA 的字符串公式结果暂未进 formulaResults（后端只算 token 型 FORMULA），
 * 旁路阶段落 driverRow/default 兜底，留 Task 8 渲染切换时处理。
 */

export type CardSide = 'QUOTE' | 'COSTING';

export interface CardSnapshotReader {
  /** 该侧结构快照（可能为 null）。 */
  structure: CardStructure | null;
  /** 该侧值快照（已解析；可能为 null）。 */
  values: CardValues | null;
  /** 是否拿到可用快照（structure + values 同时存在且有 tabs）。 */
  hasSnapshot: boolean;
  /** 结构页签（按 sortOrder 已是后端固定顺序）。 */
  tabs: CardStructureTab[];
  /** 某组件的行数（以值快照 baseRows 为准；AP-51 driver 权威）。 */
  rowCount: (componentId: string) => number;
  /** 某组件某行的 rowKey（按 rowKeyFields 从 driverRow 拼；空/哨兵 → 行号）。 */
  rowKey: (componentId: string, rowIndex: number) => string;
  /** 取某组件某行某字段的值（按上面的优先级）。 */
  getCell: (componentId: string, rowIndex: number, fieldName: string) => any;
}

/** rowKey = 按 rowKeyFields 从 driverRow 取值用 `||` 拼接；空/null/哨兵 → 行号（与后端 FormulaCalculator.computeRowKey 一致）。 */
export function computeRowKey(
  rowKeyFields: string[] | undefined | null,
  driverRow: Record<string, any> | undefined,
  rowIndex: number,
): string {
  if (!rowKeyFields || rowKeyFields.length === 0) return String(rowIndex);
  if (rowKeyFields.length === 1 && rowKeyFields[0] === '__seq_no__') return String(rowIndex);
  const parts = rowKeyFields.map((f) => {
    const v = driverRow ? driverRow[f] : undefined;
    return v != null ? String(v) : '';
  });
  const joined = parts.join('||');
  return joined.length > 0 ? joined : String(rowIndex);
}

function safeParse<T>(json: string | null | undefined): T | null {
  if (!json || typeof json !== 'string' || !json.trim()) return null;
  try {
    return JSON.parse(json) as T;
  } catch {
    return null;
  }
}

function findKeyedValues(
  rows: Array<{ rowKey: string; values: Record<string, any> }> | undefined,
  rowKey: string,
): Record<string, any> | undefined {
  if (!rows) return undefined;
  const found = rows.find((r) => r.rowKey === rowKey);
  return found?.values;
}

function isEmpty(v: any): boolean {
  return v == null || v === '';
}

function resolveDataSource(field: CardStructureField, baseRow: { basicDataValues?: Record<string, any>; driverRow?: Record<string, any> } | undefined): any {
  const binding = field.datasourceBinding;
  const bdv = baseRow?.basicDataValues;
  if (binding && bdv) {
    const dsType = binding.type ?? 'DATABASE_QUERY';
    if (dsType === 'GLOBAL_VARIABLE' && binding.global_variable_code) {
      const v = bdv[`@gvar:${binding.global_variable_code}`];
      if (!isEmpty(v) && !(Array.isArray(v) && v.length === 0)) return v;
    } else if (dsType === 'BNF_PATH' && binding.bnf_path) {
      const v = bdv[bnfDriverLookupKey(binding.bnf_path)];
      if (!isEmpty(v) && !(Array.isArray(v) && v.length === 0)) return v;
    }
  }
  // DATABASE_QUERY / HTTP_API：旁路阶段无实时查询，落 driverRow / default 兜底
  const raw = baseRow?.driverRow?.[field.name];
  if (!isEmpty(raw)) return raw;
  return field.defaultValue ?? null;
}

export function useCardSnapshots(
  quotation: any,
  lineItem: any,
  side: CardSide,
): CardSnapshotReader {
  return useMemo<CardSnapshotReader>(() => {
    const structure: CardStructure | null = side === 'QUOTE'
      ? (quotation?.quoteCardStructure ?? null)
      : (quotation?.costingCardStructure ?? null);

    const valuesJson: string | null = side === 'QUOTE'
      ? (lineItem?.quoteCardValues ?? null)
      : (lineItem?.costingCardValues ?? null);
    const values = safeParse<CardValues>(valuesJson);

    const tabs: CardStructureTab[] = structure?.tabs ?? [];
    const hasSnapshot = !!structure && !!values && tabs.length > 0;

    // componentId → struct tab / value tab 索引
    const structByComp = new Map<string, CardStructureTab>();
    for (const t of tabs) structByComp.set(t.componentId, t);
    const valByComp = new Map<string, CardValuesTab>();
    for (const t of (values?.tabs ?? [])) valByComp.set(t.componentId, t);

    const rowKeyOf = (componentId: string, rowIndex: number): string => {
      const st = structByComp.get(componentId);
      const vt = valByComp.get(componentId);
      const driverRow = vt?.baseRows?.[rowIndex]?.driverRow;
      return computeRowKey(st?.rowKeyFields, driverRow, rowIndex);
    };

    const rowCount = (componentId: string): number =>
      valByComp.get(componentId)?.baseRows?.length ?? 0;

    const getCell = (componentId: string, rowIndex: number, fieldName: string): any => {
      const st = structByComp.get(componentId);
      const vt = valByComp.get(componentId);
      if (!st || !vt) return undefined;
      const field = st.fields?.find((f) => f.name === fieldName);
      const baseRow = vt.baseRows?.[rowIndex];
      const rk = computeRowKey(st.rowKeyFields, baseRow?.driverRow, rowIndex);

      // 1. 编辑覆盖
      const editVals = findKeyedValues(vt.editRows, rk);
      if (editVals && !isEmpty(editVals[fieldName])) return editVals[fieldName];

      if (!field) {
        // 未知字段：尝试 driverRow
        return baseRow?.driverRow?.[fieldName];
      }

      // 2. 按字段类型
      switch (field.fieldType) {
        case 'FORMULA': {
          const fr = findKeyedValues(vt.formulaResults, rk);
          return fr ? fr[fieldName] : undefined;
        }
        case 'BASIC_DATA': {
          if (!field.basicDataPath) return undefined;
          return baseRow?.basicDataValues?.[bnfDriverLookupKey(field.basicDataPath)];
        }
        case 'DATA_SOURCE':
          return resolveDataSource(field, baseRow);
        case 'FIXED_VALUE':
          return field.defaultValue ?? null;
        default: {
          // INPUT / INPUT_NUMBER / LIST_FORMULA 等：driverRow ?? default
          const raw = baseRow?.driverRow?.[fieldName];
          if (!isEmpty(raw)) return raw;
          return field.defaultValue ?? null;
        }
      }
    };

    return { structure, values, hasSnapshot, tabs, rowCount, rowKey: rowKeyOf, getCell };
  }, [quotation, lineItem, side]);
}
