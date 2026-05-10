// ============================================================
// types/quotation-snapshot.ts
// UI-8 数据来源 Tab + UI-9 字段级追溯 所需类型
// ============================================================

/** 引用版本条目 */
export interface ReferencedVersionEntry {
  tableName: string;
  businessKey: string;
  version: string;
  displayName?: string;
}

/** 元素实际单价条目 */
export interface ElementActualPriceEntry {
  elementName: string;
  price: number;
  currency?: string;
}

/** 公式定义条目 */
export interface FormulaDefinitionEntry {
  name: string;
  expression: string;
  description?: string;
  category?: string;
}

/** 主数据快照条目（KV 形式） */
export interface MasterDataSnapshotEntry {
  tableName: string;
  fieldName: string;
  value: any;
  displayName?: string;
}

/** 完整提交快照（来自后端 submission_snapshot JSONB） */
export interface SubmissionSnapshot {
  referencedVersions: ReferencedVersionEntry[] | Record<string, any>;
  elementActualPrices: ElementActualPriceEntry[] | Record<string, any>;
  formulaDefinitions: FormulaDefinitionEntry[] | Record<string, any>;
  masterDataSnapshot: MasterDataSnapshotEntry[] | Record<string, any>;
  snapshotAt: string;
}

/** 字段来源类型 */
export type FieldSourceType =
  | 'FORMULA'
  | 'MANUAL_INPUT'
  | 'MASTER_DATA'
  | 'CUSTOMER_DATA'
  | 'ELEMENT_PRICE';

/** 字段追溯 DTO */
export interface FieldTraceDTO {
  fieldPath: string;
  fieldLabel?: string;
  currentValue: any;
  sourceType: FieldSourceType;
  referencedVersion?: string;
  formula?: string;
  formulaInputs?: Record<string, any>;
  lastModifiedBy?: string;
  lastModifiedAt?: string;
  /** 是否涉及多步公式嵌套（需要展开详情 Drawer） */
  isComplex?: boolean;
}

/** source type 中文映射 */
export const SOURCE_TYPE_LABEL: Record<FieldSourceType, string> = {
  FORMULA: '公式计算',
  MANUAL_INPUT: '手动输入',
  MASTER_DATA: '主数据',
  CUSTOMER_DATA: '客户数据',
  ELEMENT_PRICE: '元素单价',
};

/** source type tag 颜色 */
export const SOURCE_TYPE_COLOR: Record<FieldSourceType, string> = {
  FORMULA: 'purple',
  MANUAL_INPUT: 'blue',
  MASTER_DATA: 'cyan',
  CUSTOMER_DATA: 'green',
  ELEMENT_PRICE: 'orange',
};
