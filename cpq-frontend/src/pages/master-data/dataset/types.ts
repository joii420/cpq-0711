// ─────────────────────────────────────────────────────────────────────────────
// 数据集维护（task-260902）· 前端类型定义
// 与 `api.md` 的 8 个端点响应体一一对应。
//
// ⚠️ 与现有 `part-costing/types.ts` **刻意不合并**：两套端点的字段名不同
//    （sheetName↔tabName / sortOrder↔order / versionNo↔version / isLatest↔isCurrent /
//     masterType↔master），把新契约的字段名回灌进旧类型会改动现有页签的解析口径（AC-42）。
//    只有 `ColumnDef` / `SheetRow` 是共用的（EditableSheetTable 直接消费），
//    由本文件的 `toColumnDefs()` 把新契约映射过去。
// ─────────────────────────────────────────────────────────────────────────────
import type { ColumnDef, ColumnType, ColumnRole, MasterType, DropdownKind } from '../part-costing/types';

/** 三个数据集（api.md §0） */
export type DatasetKey = 'quote' | 'cost-basic' | 'cost-detail';

// ── §1 导入 ──────────────────────────────────────────────────────────────────
export interface ImportSheetSummary {
  sheet: string;
  versioned: boolean;
  /** 带版本 sheet：本次涉及的轴值数 */
  axisCount?: number;
  /** 带版本 sheet */
  created?: number;
  upgraded?: number;
  unchanged?: number;
  /** 免版本 sheet */
  inserted?: number;
  updated?: number;
}

export interface ImportResult {
  dataset: DatasetKey;
  fileName: string;
  durationMs: number;
  summary: ImportSheetSummary[];
}

/** 校验失败（400）payload —— 导入与保存同构（api.md §1 / §7） */
export interface ValidationError {
  sheet: string;
  row: number;
  column: string;
  /** 封闭集，见 api.md §1 */
  reason: string;
  /**
   * ⚠️ api.md §1 的 errors 结构里**没有**这个字段，但原型「数据导入-校验失败」
   *    在原因后面画了「值 `ZZZZ`」。此处按可选字段防御式渲染：后端下发就显示，
   *    不下发就只显示原因（不会报错）。已在回报里列为原型 vs 契约的差异。
   */
  value?: string | null;
}

export interface ValidationErrorPayload {
  errors: ValidationError[];
}

// ── §2 sheet 元数据 ──────────────────────────────────────────────────────────
export interface DatasetDropdownDef {
  kind: DropdownKind;
  /** kind=MASTER 时的主数据类型（注意：字段名是 masterType，不是旧契约的 master） */
  masterType?: MasterType;
  nameColumn?: string;
  options?: string[];
}

export interface DatasetColumnDef {
  name: string;
  label: string;
  role: ColumnRole;
  type?: ColumnType;
  editable?: boolean;
  required?: boolean;
  /** 是否比对项（参与行指纹）。前端只读展示，列头打 🔗 */
  compared?: boolean;
  dropdown?: DatasetDropdownDef;
}

export interface DatasetSheetMeta {
  sheetKey: string;
  sheetName: string;
  sortOrder: number;
  axisColumn: string;
  axisLabel: string;
  columns: DatasetColumnDef[];
}

export interface DatasetSheetsResult {
  sheets: DatasetSheetMeta[];
}

// ── §3 料号列表 ──────────────────────────────────────────────────────────────
export interface DatasetPartRow {
  axisValue: string;
  materialName?: string | null;
  specification?: string | null;
  dimension?: string | null;
  oldMaterialNo?: string | null;
  unitWeight?: string | null;
  /**
   * 生产料号（api.md §3，2026-09-03 补）。
   * ⚠️ 仅 `dataset=quote` 有值；`cost-basic` / `cost-detail` 的物料表没有这一列，
   *    响应里**整个省略该字段**（不是 null）—— 故此处是可选属性，
   *    🚫 不要写成「期望存在且可能为 null」的渲染逻辑。
   *    本期没有报价数据维护页签（AC-38），暂无渲染方消费它。
   */
  productionNo?: string | null;
  configuredCount: number;
  totalSheetCount: number;
  lastUpdatedAt?: string | null;
}

export interface DatasetPartListResult {
  total: number;
  items: DatasetPartRow[];
}

// ── §4 抽屉徽标 ──────────────────────────────────────────────────────────────
export interface DatasetOverviewSheet {
  sheetKey: string;
  rowCount: number;
  /** null = 该 sheet 该轴值从未有过数据（tab 不打徽标，进去是空态） */
  versionNo: number | null;
  lastUpdatedAt?: string | null;
  source?: string | null;
}

export interface DatasetPartOverview {
  axisValue: string;
  materialName?: string | null;
  sheets: DatasetOverviewSheet[];
}

// ── §5 行数据 ────────────────────────────────────────────────────────────────
/** 行值一律按字符串透传（保留库中 scale，避免 JS 精度丢失） */
export type DatasetSheetRow = Record<string, unknown>;

export interface DatasetRowsResult {
  versionNo: number | null;
  isLatest: boolean;
  readOnly: boolean;
  source?: string | null;
  rows: DatasetSheetRow[];
}

// ── §6 版本列表 ──────────────────────────────────────────────────────────────
export interface DatasetVersionInfo {
  versionNo: number;
  isLatest: boolean;
  rowCount?: number;
  archivedAt?: string | null;
  archivedBy?: string | null;
  archiveReason?: string | null;
  updatedAt?: string | null;
  updatedBy?: string | null;
  source?: string | null;
}

export interface DatasetVersionsResult {
  versions: DatasetVersionInfo[];
}

// ── §7 保存 ──────────────────────────────────────────────────────────────────
export interface DatasetSaveRequest {
  /** 乐观锁基线；该轴值该 sheet 从未有数据时传 null */
  baseVersion: number | null;
  rows: DatasetSheetRow[];
}

export type DatasetSaveResultType = 'UNCHANGED' | 'UPGRADED' | 'CREATED';

export interface DatasetSaveResult {
  result: DatasetSaveResultType;
  versionNo: number;
  rowCount?: number;
  message?: string;
}

/** 409 冲突 payload */
export interface DatasetConflictPayload {
  currentVersion: number;
  baseVersion: number;
}

// ── §8 主数据下拉 ────────────────────────────────────────────────────────────
export interface DatasetLookupResult {
  items: { code: string; name: string }[];
}

// ── 契约映射：DatasetColumnDef[] → EditableSheetTable 消费的 ColumnDef[] ──────
/**
 * 字段名差异只在此一处收敛：
 *   `dropdown.masterType` → `dropdown.master`；`type` 缺省按 STRING。
 * `compared` / `required` 原样透传（ColumnDef 上是可选字段）。
 */
export function toColumnDefs(cols: DatasetColumnDef[]): ColumnDef[] {
  return (cols ?? []).map((c) => ({
    name: c.name,
    label: c.label,
    type: (c.type ?? 'STRING') as ColumnType,
    role: c.role,
    // NAME 列后端可能不下发 editable；按契约 NAME 恒只读
    editable: c.role === 'NAME' ? false : c.editable !== false,
    compared: c.compared,
    required: c.required,
    dropdown: c.dropdown
      ? {
          kind: c.dropdown.kind,
          master: c.dropdown.masterType,
          nameColumn: c.dropdown.nameColumn,
          options: c.dropdown.options,
        }
      : undefined,
  }));
}

// ── §8.5 电镀方案只读列表（S-9 / AC-48~51）──────────────────────────────────
/** 仅 quote 与 cost-detail 有电镀方案表；cost-basic 没有（后端返 404） */
export type PlatingDatasetKey = Extract<DatasetKey, 'quote' | 'cost-detail'>;

export interface PlatingSchemeColumn {
  name: string;
  label: string;
  type?: ColumnType;
}

export interface PlatingSchemeResult {
  total: number;
  /** 🚨 列定义**由后端按数据集下发**（报价 10 列 / 详细核价 8 列），前端不得写死任何一列（AC-49） */
  columns: PlatingSchemeColumn[];
  items: Record<string, unknown>[];
}
