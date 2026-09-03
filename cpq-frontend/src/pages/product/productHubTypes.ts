// ─────────────────────────────────────────────────────────────────────────────
// 产品管理页（task-260903）· 前端类型定义
//
// 对齐 `dev-docs/task-260902-.../api.md` 的 `{dataset}` 参数化只读端点契约，
// **不是** `part-costing/types.ts`（那套是核价侧旧契约：SheetMeta.tabName / version:string，
// 与新契约的 sheetName / versionNo:number 不同名不同型，直接复用会静默取到 undefined）。
//
// 🚫 本文件只声明本页**读端点**用得到的形状。写端点（PUT rows / POST import / lookup）
//    本页一个都不调（api.md §3），故不声明其请求体类型 —— 声明了就等于给后人开口子。
// ─────────────────────────────────────────────────────────────────────────────

// ── 列元数据（GET /dataset/{dataset}/sheets）──────────────────────────────────

/** AXIS=轴列（抽屉内隐藏）/ SUBDIM=子维度编码列 / VALUE=普通值列 / NAME=主数据 JOIN 带出的只读名称列 */
export type ColumnRole = 'AXIS' | 'SUBDIM' | 'VALUE' | 'NAME';

/**
 * 🚨 渲染必须按本字段判断对齐与格式化，**禁止按列名硬编码**。
 * task-260902 刚修正 50 列类型（31 列方向错），典型如 `pricing_unit`（计价单位）
 * 由 DECIMAL 改为 STRING —— 按列名猜必然过时。
 */
export type ColumnType = 'STRING' | 'NUMBER' | 'DECIMAL' | 'BOOLEAN' | 'ENUM';

export type DropdownKind = 'MASTER' | 'ENUM' | 'FREE';

export interface DropdownDef {
  kind: DropdownKind;
  /** kind=MASTER 时的主数据类型（material / process / element / recipe / customer） */
  masterType?: string;
  /** kind=MASTER 时联动的只读名称列名 */
  nameColumn?: string;
  /** kind=ENUM 时的固定候选 */
  options?: string[];
}

export interface ColumnDef {
  name: string;
  label: string;
  role: ColumnRole;
  /** NAME 列后端可能不下发 type（它不是库字段），故为可选 */
  type?: ColumnType;
  editable?: boolean;
  required?: boolean;
  /** 是否比对项。本页只读，不参与任何逻辑 */
  compared?: boolean;
  dropdown?: DropdownDef;
}

export interface SheetMeta {
  sheetKey: string;
  sheetName: string;
  sortOrder: number;
  axisColumn: string;
  axisLabel: string;
  columns: ColumnDef[];
}

export interface SheetsResult {
  sheets: SheetMeta[];
}

// ── 料号列表（GET /dataset/{dataset}/parts）───────────────────────────────────

/**
 * 🚨 数值列（unitWeight）后端**以字符串回传**保留库中 scale。
 * 禁止在此声明为 number —— 声明成 number 会诱导 `Number()` 转换，丢精度（api.md 硬约束 2）。
 */
export interface PartListItem {
  axisValue: string;
  materialName?: string | null;
  specification?: string | null;
  dimension?: string | null;
  oldMaterialNo?: string | null;
  unitWeight?: string | null;
  /**
   * dataset=quote 专有（api.md §3 缺口2 补齐）。
   * ⚠️ 后端未补齐前该字段为 undefined —— 渲染必须兜底 `—`，不得崩溃、不得整列不渲染。
   */
  productionNo?: string | null;
  configuredCount?: number;
  totalSheetCount?: number;
  lastUpdatedAt?: string | null;
}

export interface PartListResult {
  total: number;
  items: PartListItem[];
}

// ── 抽屉徽标（GET /dataset/{dataset}/parts/{axisValue}/overview）──────────────

export interface OverviewSheet {
  sheetKey: string;
  rowCount: number;
  /** null = 该 sheet 该轴值**从未有过数据** → tab 不打徽标，进去是空态（api.md 硬约束 6） */
  versionNo: number | null;
  lastUpdatedAt?: string | null;
  source?: string | null;
}

export interface PartOverview {
  axisValue: string;
  materialName?: string | null;
  sheets: OverviewSheet[];
}

// ── 行数据（GET .../sheets/{sheetKey}/rows）──────────────────────────────────

/** 行为动态列结构（列名 → 值）；值原样透传，保留后端精度 */
export type SheetRow = Record<string, unknown>;

export interface SheetRowsResult {
  versionNo: number | null;
  isLatest?: boolean;
  /**
   * 🚨 本页**无论该字段为何值一律只读渲染**（api.md 硬约束 7 / AC-8）。
   * 保留它只为契约完整，**不得据其推导出可编辑分支**。
   */
  readOnly?: boolean;
  source?: string | null;
  rows: SheetRow[];
}

// ── 版本列表（GET .../sheets/{sheetKey}/versions）────────────────────────────

export interface VersionInfo {
  versionNo: number;
  isLatest?: boolean;
  rowCount?: number;
  archivedAt?: string | null;
  updatedAt?: string | null;
  updatedBy?: string | null;
  source?: string | null;
}

export interface VersionsResult {
  versions: VersionInfo[];
}

// ── 客户产品列表（GET /dataset/{dataset}/customer-parts · api.md §2 缺口1）───

export interface CustomerPartItem {
  customerNo: string;
  /**
   * 后端 LEFT JOIN `customer.code` 得出。
   * ⚠️ 实测 17 行中 3 行 JOIN 不到（`Q13CUST0617`×2 / `C1`×1 未在客户档案登记）→ 回 null，
   *    前端渲染 `—`。这是现网真实状态，不是缺陷（AC-2）。
   */
  customerName?: string | null;
  customerPartName?: string | null;
  customerProductNo: string;
  customerDrawingNo?: string | null;
  materialNo: string;
}

export interface CustomerPartListResult {
  total: number;
  items: CustomerPartItem[];
}
