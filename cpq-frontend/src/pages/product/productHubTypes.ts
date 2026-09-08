// ─────────────────────────────────────────────────────────────────────────────
// 产品管理页（task-260903）· 前端类型定义
//
// 对齐 `dev-docs/task-260902-.../api.md` 的 `{dataset}` 参数化只读端点契约，
// **不是**主数据维护核价侧旧公共件的 `types.ts`（已于 2026-09-07 迁至 `pages/master-data/shared/`；
//   那套是核价侧旧契约：SheetMeta.tabName / version:string，
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
  /**
   * 🆕 task-260907-产品管理客户过滤 · F-4（api.md A-2）：复合轴落地后一行 = 「客户 × 销售料号」。
   * 🚨 **这是行身份的一部分**，`ProductSalesPartTab` 的 `rowKey` 必须含它——
   *    仅按 `axisValue` 会让切客户时 React 复用行组件，显示上一个客户的单元格值（AC-16②）。
   */
  customerNo: string;
  /**
   * 由后端**同一条 SELECT** 内 `LEFT JOIN customer ON customer.code = m.customer_no` 带出。
   * JOIN 不到（未建档客户，如 `C1`）时为 `null` ⇒ 渲染 `—`，**不是空白**（AC-5④）。
   */
  customerName?: string | null;
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
  /**
   * 产品分类（子任务 AC-8，来自 `task-260902` 的 B-16）。
   *
   * 🚩 **列表只显示 `categoryName`（如「默认分类」），不显示 `categoryCode`（`000000`）**
   *    —— 用户 2026-09-03 裁决。`categoryCode` 声明在此只为契约完整，**渲染层不要用它**。
   * ⏸ 对方 B-16 尚未落库 ⇒ 两个字段当前都是 undefined，渲染必须兜底 `—`、不得崩溃。
   * ⚠️ 字段名以 `api.md §1 C-2` 为准；联调时若实际不同**报主线**，🚫 不自行改契约。
   */
  categoryCode?: string | null;
  categoryName?: string | null;
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

// ── 客户过滤器候选（GET /dataset/{dataset}/customer-parts/customers · 本任务 api.md §2 B-2）──

/**
 * 客户过滤器的一个候选项。
 *
 * 🚨 **候选来自 `ds_quote_customer_part` 里实际出现过的 `customer_no`（SELECT DISTINCT），
 *    不是 `customer` 主数据表**（AC-5）。
 *    `Q13CUST0617` 与 `C1` 未在 `customer` 表建档 —— 若候选只从 `customer` 表取，
 *    这两个客户的 3 行产品在页面上看得见、却永远筛不出来。
 */
export interface CustomerOption {
  customerNo: string;
  /**
   * 后端 LEFT JOIN `customer.code` 得出；JOIN 不到时为 null
   * ⇒ 前端下拉显示 `客户编号（未建档）`（原型「客户产品-过滤器」）。
   */
  customerName?: string | null;
  /**
   * 该客户的产品行数，供下拉右侧显示「11」这类提示。
   * ⚠️ api.md §2 B-2 注明 **实现可省** ⇒ 缺失时前端不显示数量，不得据其做任何判断。
   */
  count?: number | null;
}

export interface CustomerOptionsResult {
  items: CustomerOption[];
}

// ── 壳页全局客户候选（GET /dataset/{dataset}/customers · task-260907-产品管理客户过滤 A-1）──
//
// 🚨 与上面的 `CustomerOption` / `listCustomerPartCustomers` 是**两套不同口径**，不要混用：
//    · 旧口径（`customer-parts/customers`）= `ds_quote_customer_part` 的 `DISTINCT customer_no`；
//    · 新口径（本节，`/customers`）= `customer` 主数据表全集 ∪ 报价业务表未建档客户号（并集，AC-2）。
//    本任务起，壳页全局选择器改调新口径；旧口径端点保留但不再被本页调用（api.md §3）。

/**
 * 壳页客户选择器的一个候选项。
 *
 * `registered=false` 时 `customerName` 恒为 `null`（未建档，仅在报价业务表出现过）——
 * 🚫 前端不得因 `customerName` 为空而把该候选过滤掉（AC-14③）。
 */
export interface CustomerCandidate {
  customerNo: string;
  customerName?: string | null;
  registered: boolean;
}

export interface CustomerCandidatesResult {
  items: CustomerCandidate[];
}
