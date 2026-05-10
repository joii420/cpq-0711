export type DdlImportance = 'CRITICAL' | 'IMPORTANT' | 'NORMAL';
export type DdlStatus = 'SUCCESS' | 'FAILED';

export interface DdlOperationDTO {
  id: string;
  tableName: string;
  columnName: string;
  dataType: string;
  defaultValue: string;
  importance: DdlImportance;
  affectsCalculation: boolean;
  remark?: string;
  status: DdlStatus;
  errorMessage?: string;
  migrationContent: string;
  flywayVersionHint?: string;
  createdByName: string;
  createdAt: string;
}

export interface ExtendColumnRequest {
  tableName: string;
  columnName: string;
  dataType: string;
  defaultValue: string;
  importance: DdlImportance;
  affectsCalculation: boolean;
  remark?: string;
}

export interface ExtensibleTableColumn {
  columnName: string;
  dataType: string;
  nullable: boolean;
}

export interface ExtensibleTableDTO {
  tableName: string;
  displayName: string;
  columnCount: number;
  columns: ExtensibleTableColumn[];
}

export interface DdlHistoryPage {
  content: DdlOperationDTO[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}
