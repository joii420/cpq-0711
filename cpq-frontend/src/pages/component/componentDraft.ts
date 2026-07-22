import type { FieldItem, FormulaItem } from './types';

export interface DraftSnapshot {
  fields: Omit<FieldItem, 'key'>[];
  formulas: Omit<FormulaItem, 'key'>[];
  dataDriverPath: string;
  rowKeyFields: string[];
  excelColumns: any[];
  bomRecursiveExpand: boolean;
  /** task-0721 F2：页签类型属性草稿(可空) */
  tabType?: string;
  /** task-0721 F2（2026-07-21 补充）：料号列/料号名称列字段名草稿(可空) */
  partNoField?: string;
  partNameField?: string;
}

export interface DraftEnvelope {
  savedAt: number;
  baselineUpdatedAt?: string;
  snapshot: DraftSnapshot;
}

let keySeq = 0;
function freshKey(prefix: string): string {
  keySeq += 1;
  return `${prefix}-${keySeq}-${Math.floor(performance.now())}`;
}

export function stripFieldKeys<T extends { key?: string }>(arr: T[]): Omit<T, 'key'>[] {
  return (arr ?? []).map(({ key: _k, ...rest }) => rest);
}

export function rebuildFieldKeys<T extends object>(arr: T[]): (T & { key: string })[] {
  return (arr ?? []).map((f) => ({ ...f, key: freshKey('field') }));
}

export function rebuildFormulaKeys<T extends object>(arr: T[]): (T & { key: string })[] {
  return (arr ?? []).map((f) => ({ ...f, key: freshKey('formula') }));
}

export function buildDraftSnapshot(input: {
  fields: FieldItem[];
  formulas: FormulaItem[];
  dataDriverPath: string;
  rowKeyFields: string[];
  excelColumns: any[];
  bomRecursiveExpand: boolean;
  tabType?: string;
  partNoField?: string;
  partNameField?: string;
}): DraftSnapshot {
  return {
    fields: stripFieldKeys(input.fields),
    formulas: stripFieldKeys(input.formulas),
    dataDriverPath: input.dataDriverPath ?? '',
    rowKeyFields: input.rowKeyFields ?? [],
    excelColumns: input.excelColumns ?? [],
    bomRecursiveExpand: !!input.bomRecursiveExpand,
    tabType: input.tabType,
    partNoField: input.partNoField,
    partNameField: input.partNameField,
  };
}

export function isDraftStale(baselineUpdatedAt?: string, currentUpdatedAt?: string): boolean {
  if (!baselineUpdatedAt || !currentUpdatedAt) return false;
  return baselineUpdatedAt !== currentUpdatedAt;
}
