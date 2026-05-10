// V5 导入向导 — TypeScript 类型定义
// 对应后端 /cpq/import/basic-data/v5/* 接口

export type Importance = 'CRITICAL' | 'IMPORTANT' | 'NORMAL';
export type Decision = 'ACCEPT_NEW' | 'KEEP_OLD' | 'DELETE_ORPHAN' | 'KEEP_ORPHAN';
export type ResolutionType = 'BASIC_DIFF' | 'CUSTOMER_CONFLICT' | 'ORPHAN_ROW';

export interface BasicDataDiffDTO {
  tableName: string;
  rowKey: string;
  fieldName: string;
  fieldLabel: string;
  oldValue: any;
  newValue: any;
  importance: Importance;
  affectsCalculation: boolean;
}

export interface ConflictFieldDTO {
  fieldName: string;
  fieldLabel: string;
  existingValue: any;
  importValue: any;
  importance: Importance;
  affectsCalculation: boolean;
}

export interface CustomerDataConflictDTO {
  customerId: string;
  hfPartNo: string;
  tableName: string;
  rowKey: string;
  fields: ConflictFieldDTO[];
}

export interface ResolutionDTO {
  type: ResolutionType;
  tableName: string;
  rowKey: string;
  fieldName: string | null;
  decision: Decision;
  note: string | null;
  oldValueAtPreview?: any;
}

/** 孤儿行 DTO：存在于 DB 但本次 Excel 未覆盖的 is_current=true 行 */
export interface OrphanRowDTO {
  tableName: string;                  // "mat_fee" | "mat_process"
  rowKey: string;                     // 业务键拼接，提交 resolutions 时原样回传
  partNo: string;                     // 料号（UI 分组用）
  displayLabel: string;               // 用户友好标签，如 "INCOMING_FIXED 项次=3 ..."
  rowSnapshot: Record<string, any>;   // 完整字段值快照
  importance: Importance;
}

export interface ImportResultDTOV5 {
  status: string;
  importRecordId: string | null;
  totalRows: number;
  validation: {
    hasErrors: boolean;
    errors: any[];
    warnings: any[];
  };
  basicDataDiffs: BasicDataDiffDTO[];
  customerDataConflicts: CustomerDataConflictDTO[];
  orphanRows: OrphanRowDTO[];
}
