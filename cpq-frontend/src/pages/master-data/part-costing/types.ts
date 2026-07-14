// ─────────────────────────────────────────────────────────────────────────────
// 主数据维护-核价基础数据维护（task-0712）· 前端类型定义
// 与 api.md 的 7 组接口响应体一一对应；列元数据（role/dropdown）驱动渲染，
// 不在前端硬编码 16 组列结构（保持与后端 PricingSheetRegistry 单一真源）。
// ─────────────────────────────────────────────────────────────────────────────

// ── §1 料号列表 ──────────────────────────────────────────────────────────────
export interface PartRow {
  materialNo: string;
  materialName: string;
  specification?: string | null;
  dimension?: string | null;
  /** 已配置版本组数 N */
  configuredCount: number;
  /** 固定 16 */
  totalSheets: number;
  lastUpdatedAt?: string | null;
}

export interface PartListResult {
  total: number;
  page: number;
  size: number;
  items: PartRow[];
}

// ── §2 Sheet 元数据 · 列定义 ─────────────────────────────────────────────────
/** AXIS=轴（锁定，前端不渲染为可编辑）/ SUBDIM=子维度编码列 / VALUE=普通值列 / NAME=只读名称列 */
export type ColumnRole = 'AXIS' | 'SUBDIM' | 'VALUE' | 'NAME';
export type ColumnType = 'STRING' | 'NUMBER' | 'DECIMAL' | 'BOOLEAN' | 'ENUM';
/** MASTER=走主表 lookup / ENUM=固定枚举 / FREE=自由文本 */
export type DropdownKind = 'MASTER' | 'ENUM' | 'FREE';
export type MasterType = 'process' | 'element' | 'material';

export interface DropdownDef {
  kind: DropdownKind;
  /** kind=MASTER 时：process / element / material */
  master?: MasterType;
  /** kind=MASTER 时：联动只读名称列名 */
  nameColumn?: string;
  /** kind=ENUM 时：固定候选值（含 CHECK 约束值） */
  options?: string[];
}

export interface ColumnDef {
  name: string;
  label: string;
  type: ColumnType;
  role: ColumnRole;
  editable: boolean;
  dropdown?: DropdownDef;
}

export interface SheetMeta {
  sheetKey: string;
  tabName: string;
  /** FEE / BOM / CAPACITY_ENERGY / TOOLING */
  group: string;
  order: number;
  /** P06/P07 主从 BOM 为 true */
  masterDetail: boolean;
  salesPartAnchor: string;
  columns: ColumnDef[];
}

export interface SheetsResult {
  sheets: SheetMeta[];
}

// ── §3 料号概览 · 16 组当前状态 ──────────────────────────────────────────────
export interface OverviewSheet {
  sheetKey: string;
  hasData: boolean;
  currentVersion: string | null;
  versionCount: number;
  lastUpdatedAt: string | null;
}

export interface PartOverview {
  materialNo: string;
  materialName: string;
  specification?: string | null;
  dimension?: string | null;
  sheets: OverviewSheet[];
}

// ── §4 某组数据行 ────────────────────────────────────────────────────────────
/**
 * ELEMENT_BOM sheet 专属已知字段（childtask-1 · F2）：
 * 后端 readRows 两跳 join `material_part_no → material_master.material_recipe_id → material_recipe.name`
 * 带出的材质名，随 sheet.columns 的 `nameCol("material_recipe_name","材质名")` 定义一并下发。
 * 未绑定 material_recipe_id 时为 null（前端渲染灰字「未绑定」，见 EditableSheetTable.renderNameOrHint）。
 * 其余 sheet 无此字段，值为 undefined。
 */
export interface SheetRowKnownFields {
  material_recipe_name?: string | null;
}

/** 行数据为动态列结构（列名 → 值）；值均以字符串/原始类型透传，保留后端精度 */
export type SheetRow = SheetRowKnownFields & Record<string, unknown>;

export interface SheetRowsResult {
  sheetKey: string;
  materialNo: string;
  version: string;
  isCurrent: boolean;
  /** isCurrent && 当前用户有编辑权（C7：历史版恒 false） */
  editable: boolean;
  rows: SheetRow[];
  /** P06/P07 主从 BOM 的主表信息（bom_type / production_no 等） */
  masterInfo?: Record<string, unknown> | null;
}

// ── §5 版本列表 ──────────────────────────────────────────────────────────────
export interface VersionInfo {
  version: string;
  isCurrent: boolean;
  /** MANUAL / IMPORT */
  source: string;
  operator: string;
  operatedAt: string;
}

export interface VersionsResult {
  versions: VersionInfo[];
}

// ── §6 保存整组 ──────────────────────────────────────────────────────────────
export interface SaveRowsRequest {
  /** 乐观锁：加载时的当前版本号；空 tab 从零新建传 null */
  expectedCurrentVersion: string | null;
  rows: SheetRow[];
}

/** UNCHANGED（未变，复用旧版）/ UPGRADED（升版）/ CREATED（从零新建=2000） */
export type SaveResultType = 'UNCHANGED' | 'UPGRADED' | 'CREATED';

export interface SaveResult {
  result: SaveResultType;
  version: string;
  isCurrent: boolean;
}

// ── §7 主表候选下拉 ──────────────────────────────────────────────────────────
export interface LookupItem {
  code: string;
  name: string;
}

export interface LookupResult {
  items: LookupItem[];
}
