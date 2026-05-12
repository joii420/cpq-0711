// V6 staging-based 导入向导 — TypeScript 类型定义
// 对应后端 /cpq/import-session/* 接口
// 设计文档：docs/superpowers/specs/2026-05-12-import-v6-staging-design.md

// ────────────────────────────────────────────────
// 决策类型枚举
// ────────────────────────────────────────────────
export type DecisionType = 'PART_VERSION' | 'CUSTOMER_CONFLICT' | 'ORPHAN';

export type PartVersionAction = 'BUMP' | 'NO_BUMP' | 'NEW';
export type CustomerConflictAction = 'USE_EXCEL' | 'USE_DB' | 'SKIP';
export type OrphanAction = 'CREATE_NEW' | 'LINK_EXISTING' | 'DISCARD';

// ────────────────────────────────────────────────
// Row-level 差异
// ────────────────────────────────────────────────
export interface RowDiff {
  rowKey: string;
  field: string;
  oldValue: string;
  newValue: string;
}

// ────────────────────────────────────────────────
// 料号版本决策条目
// ────────────────────────────────────────────────
export interface PartVersionDecisionItem {
  /** decision_key 格式："{customerProductNo}|{hfPartNo}" */
  key: string;
  customerProductNo: string;
  hfPartNo: string;
  /** null 表示全新料号 */
  currentVersion: number | null;
  suggestedVersion: number;
  /** true 表示全新料号，强制 NEW，禁用 BUMP/NO_BUMP */
  isNew: boolean;
  /** 用户当前选择的动作，默认由后端 defaultAction 决定 */
  action: PartVersionAction;
  /** Sheet 级别差异计数：key = sheet 名称（bom/process/fee 等），value = 差异行数 */
  sheetDiffs: Record<string, number>;
  /** Row-level 差异详情：key = sheet 名称，value = 差异行列表 */
  rowLevelDiff: Record<string, RowDiff[]>;
}

// ────────────────────────────────────────────────
// 客户冲突决策条目
// ────────────────────────────────────────────────
export interface CustomerConflictItem {
  /** decision_key 格式："{conflictType}|{primaryKey}" */
  key: string;
  conflictType: string;
  primaryKey: string;
  description: string;
  action: CustomerConflictAction;
}

// ────────────────────────────────────────────────
// 孤儿行决策条目
// ────────────────────────────────────────────────
export interface OrphanItem {
  /** decision_key 格式："{sheetCode}|{rowIndex}" */
  key: string;
  sheetCode: string;
  rowIndex: number;
  description: string;
  rowSnapshot: Record<string, any>;
  action: OrphanAction;
}

// ────────────────────────────────────────────────
// 校验结果
// ────────────────────────────────────────────────
export interface ValidationResult {
  hasErrors: boolean;
  errors: string[];
  warnings: string[];
}

// ────────────────────────────────────────────────
// diffPayload（上传后返回）
// ────────────────────────────────────────────────
export interface DiffPayload {
  partVersionDecisions: PartVersionDecisionItem[];
  customerConflicts: CustomerConflictItem[];
  orphanRows: OrphanItem[];
  validation: ValidationResult;
}

// ────────────────────────────────────────────────
// 上传端点返回
// ────────────────────────────────────────────────
export interface UploadResult {
  sessionId: string;
  diffPayload: DiffPayload;
}

// ────────────────────────────────────────────────
// 决策提交（PUT /sessions/{id}/decisions）
// ────────────────────────────────────────────────
export interface DecisionEntry {
  decisionType: DecisionType;
  decisionKey: string;
  action: PartVersionAction | CustomerConflictAction | OrphanAction;
  /** 孤儿行 LINK_EXISTING 时使用 */
  targetPartId?: string;
}

export interface DecisionUpdateRequest {
  decisions: DecisionEntry[];
}

// ────────────────────────────────────────────────
// Commit 端点（POST /sessions/{id}/commit）
// ────────────────────────────────────────────────
export interface CommitRequest {
  name: string;
  categoryId: string;
  customerTemplateId: string;
  costingTemplateId?: string;
}

export interface CommitResult {
  quotationId: string;
  sessionId: string;
}
