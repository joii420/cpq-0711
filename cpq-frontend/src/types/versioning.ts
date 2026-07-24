// 变更日志 — 共享 TypeScript 类型定义
// 对应后端 /api/cpq/change-log/* 接口
// task-0723：原「版本管理」(/api/cpq/versioning/*) 专属类型 VersionHistoryItemDTO / FieldDiff /
// VersionCompareDTO / VersionHistoryPageDTO 已随「料号版本历史对比」功能一并下线并移除，
// 本文件只保留 change-log 侧仍在用的类型。

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

export interface ChangeLogPageDTO {
  items: ChangeLogEntryDTO[];
  page: number;
  size: number;
  total: number;
}
