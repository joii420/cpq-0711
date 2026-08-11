import type { CardValues } from '../../services/quotationService';
import type { ComponentField } from './QuotationStep2';
import { resolveInputDefaultSourceOnly } from './inputDefaults';

export interface ReadonlySnapshotComponentIndex {
  formula: Map<string, Record<string, any>>;
  driverRows: Record<string, any>[];
  basicDataRows: Record<string, any>[];
}

export function buildReadonlySnapshotIndex(
  cardValues: CardValues | null | undefined,
): Map<string, ReadonlySnapshotComponentIndex> {
  const index = new Map<string, ReadonlySnapshotComponentIndex>();
  for (const tab of cardValues?.tabs ?? []) {
    if (!tab.componentId) continue;
    const formula = new Map<string, Record<string, any>>();
    for (const result of tab.formulaResults ?? []) {
      if (result?.rowKey != null) formula.set(result.rowKey, result.values ?? {});
    }
    index.set(tab.componentId, {
      formula,
      driverRows: (tab.baseRows ?? []).map((baseRow) => baseRow?.driverRow ?? {}),
      basicDataRows: (tab.baseRows ?? []).map((baseRow) => baseRow?.basicDataValues ?? {}),
    });
  }
  return index;
}

/** Driver snapshot is the base row; persisted edits override matching fields. */
export function mergeReadonlySnapshotRow(
  persistedRow: Record<string, any> | null | undefined,
  driverRow: Record<string, any> | null | undefined,
): Record<string, any> {
  return { ...(driverRow ?? {}), ...(persistedRow ?? {}) };
}

export interface ReadonlySnapshotRowAssemblyInput {
  persistedRow: Record<string, any> | null | undefined;
  rowIndex: number;
  expIndex: number;
  expandedRows?: ReadonlyArray<{
    driverRow?: Record<string, any>;
    basicDataValues?: Record<string, any>;
  }>;
  snapshotDriverRows?: ReadonlyArray<Record<string, any>>;
  snapshotBasicDataRows?: ReadonlyArray<Record<string, any>>;
}

/** Uses the value snapshot when the expansion cache key is unavailable. */
export function assembleReadonlySnapshotRow({
  persistedRow,
  rowIndex,
  expIndex,
  expandedRows,
  snapshotDriverRows,
  snapshotBasicDataRows,
}: ReadonlySnapshotRowAssemblyInput): {
  driverRow: Record<string, any> | undefined;
  basicDataValues: Record<string, any> | undefined;
  rawRow: Record<string, any>;
} {
  const driverRow = (expIndex >= 0 ? expandedRows?.[expIndex]?.driverRow : undefined)
    ?? snapshotDriverRows?.[rowIndex]
    ?? persistedRow
    ?? undefined;
  return {
    driverRow,
    basicDataValues: (expIndex >= 0 ? expandedRows?.[expIndex]?.basicDataValues : undefined)
      ?? snapshotBasicDataRows?.[rowIndex],
    rawRow: mergeReadonlySnapshotRow(persistedRow, driverRow),
  };
}

/** Materializes missing INPUT field-name keys from the authoritative row snapshot. */
export function materializeReadonlySnapshotInputs(
  fields: ReadonlyArray<ComponentField>,
  row: Record<string, any>,
  basicDataValues: Record<string, any> | undefined,
): Record<string, any> {
  let output = row;
  for (const field of fields) {
    const key = field.name || field.key || '';
    if (!key || Object.prototype.hasOwnProperty.call(output, key) || !field.default_source) continue;
    const value = resolveInputDefaultSourceOnly(field, { basicDataValues });
    if (value === undefined) continue;
    if (output === row) output = { ...row };
    output[key] = value;
  }
  return output;
}
