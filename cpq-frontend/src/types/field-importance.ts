export type ImportanceLevel = 'CRITICAL' | 'IMPORTANT' | 'NORMAL';

export interface FieldImportanceItem {
  id: string;
  configId: string;
  columnLetter: string;
  columnTitle: string;
  variableCode: string;
  variableLabel: string;
  dataType: 'IDENTIFIER' | 'VALUE';
  importanceLevel: ImportanceLevel;
  affectsCalculation: boolean;
  /** V58: 导入时该字段是否为必填 */
  isRequired?: boolean;
  remark?: string;
  status: 'ACTIVE' | 'DISABLED';
  sortOrder: number;
}

export interface UpdateFieldImportanceRequest {
  importanceLevel: ImportanceLevel;
  affectsCalculation: boolean;
  /** V58: 导入时该字段是否为必填 */
  isRequired?: boolean;
  remark?: string;
}
