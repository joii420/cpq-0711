// 报价漂移检测 — TypeScript 类型定义
// 对应后端 QuotationDetailDTO 中的 driftDetection 字段

export interface DriftedRecord {
  tableName: string;
  recordId: string;
  hfPartNo: string;
  referencedVersion: number;
  currentVersion: number;
  // 可选：显示版本信息文案
  displayMessage?: string;
}

export interface DriftDetectionResult {
  hasDrift: boolean;
  driftedRecords: DriftedRecord[];
  checkedAt?: string;
}
