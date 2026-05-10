// 版本管理 — 共享 TypeScript 类型定义
// 对应后端 /api/cpq/versioning/* 和 /api/cpq/change-log/* 接口

export interface VersionHistoryItemDTO {
  tableName: string;
  recordId: string;
  version: number;
  isCurrent: boolean;
  businessKey: Record<string, any>;
  customerId: string;
  hfPartNo: string;
  updatedAt: string;
  updatedBy: string;
  updatedByName: string;
}

export interface FieldDiff {
  fieldName: string;
  fieldLabel?: string;
  valueA: any;
  valueB: any;
  sameValue: boolean;
}

export interface VersionCompareDTO {
  tableName: string;
  recordA: VersionHistoryItemDTO;
  recordB: VersionHistoryItemDTO;
  fieldDiffs: FieldDiff[];
}

export interface ChangeLogEntryDTO {
  id: string;
  tableName: string;
  recordId: string;
  customerId: string;
  hfPartNo: string;
  fieldName: string;
  fieldLabel?: string;
  oldValue: any;
  newValue: any;
  importance: 'CRITICAL' | 'IMPORTANT' | 'NORMAL';
  affectsCalculation: boolean;
  changeSource: 'V5_IMPORT' | 'MANUAL_EDIT' | 'SYSTEM_INIT' | 'SYNC';
  note?: string;
  changedAt: string;
  changedBy: string;
  changedByName?: string;
  importRecordId?: string;
}

export interface VersionHistoryPageDTO {
  items: VersionHistoryItemDTO[];
  page: number;
  size: number;
  total: number;
}

export interface ChangeLogPageDTO {
  items: ChangeLogEntryDTO[];
  page: number;
  size: number;
  total: number;
}
