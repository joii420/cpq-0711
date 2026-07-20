/**
 * comparisonMapping —— 比对视图纯函数层（task-0717）。
 * 由 columns（用户配置的比对列）+ data.rows（后端取值矩阵）算出每料号每列的取值/差异/着色，
 * 并提供差异料号判定、排序、过滤、分页、显示格式化。
 * 严格对齐 dev-docs/task-0717-比对视图/{api.md,fronttask.md} + prototype-比对视图.html。
 * 不发起任何网络请求、不读取任何全局状态 —— 全部输入以参数传入，便于单测。
 */
import { genUUID } from '../../utils/uuid';
import type { ColumnDef, ComparisonRowDTO, ComparisonBucket } from '../../services/comparisonViewService';

export type { ColumnDef } from '../../services/comparisonViewService';

export const TAB_TOTAL_KEY = '__TAB_TOTAL__';
export const PRODUCT_TOTAL_COLUMN_ID = '__product_total__';

export type Side = 'quote' | 'costing';

/** 差异着色三态：红（diff<0，优先）/ 橙（diff<threshold）/ 无色。 */
export type DiffColor = 'red' | 'orange' | 'none';

// ───────────────────────── 默认列 ─────────────────────────

/** api.md §5.1 — 默认列「产品卡片总计」：不可删、阈值可改（默认 0）。 */
export function makeDefaultColumn(): ColumnDef {
  return { id: PRODUCT_TOTAL_COLUMN_ID, kind: 'PRODUCT_TOTAL', sortOrder: 0, threshold: 0 };
}

/**
 * 从后端 config.columns（null=从未保存）出发，保证：
 * 1) 恒有且仅有一条 PRODUCT_TOTAL 列；
 * 2) PRODUCT_TOTAL 列恒排在第一位（api.md §5.1「约定：列表中恒为第一列」）。
 * 不做网络请求、不落库 —— 由调用方决定是否随下次保存一并持久化。
 */
export function ensureColumns(raw: ColumnDef[] | null | undefined): ColumnDef[] {
  const cols = raw ? raw.slice() : [];
  const idx = cols.findIndex((c) => c.kind === 'PRODUCT_TOTAL');
  if (idx === -1) return [makeDefaultColumn(), ...cols];
  if (idx === 0) return cols;
  const [pt] = cols.splice(idx, 1);
  return [pt, ...cols];
}

// ───────────────────────── 显示格式 ─────────────────────────

/** 比对值显示格式：末尾"小计"/"合计"前插入间隔点（api.md §11.H）。 */
export function formatMetricLabel(label: string | null | undefined): string {
  if (!label) return '';
  return label.replace(/(小计|合计)$/, '·$1');
}

/** 连线配对确定时用于生成 ColumnDef.quoteLabel/costingLabel（tab 名 + 已格式化的比对值名）。 */
export function buildTabPairLabel(tabName: string, metricLabel: string): string {
  return `${tabName}·${formatMetricLabel(metricLabel)}`;
}

/** 列的数值展示精度：产品总计列 2 位，页签列（字段小计/页签合计）4 位（docs/小数显示口径）。 */
export function columnDecimals(col: ColumnDef): number {
  return col.kind === 'PRODUCT_TOTAL' ? 2 : 4;
}

/** 数值展示：固定小数位 + 千分位；空值 "—"。 */
export function formatComparisonNumber(value: number | null | undefined, decimals: number): string {
  if (value == null || Number.isNaN(value)) return '—';
  return value.toLocaleString('zh-CN', { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
}

/** 差异值展示：固定小数位 + 千分位 + 正数带 "+" 号；空值 "—"。 */
export function formatDiffNumber(diff: number | null | undefined, decimals: number): string {
  if (diff == null || Number.isNaN(diff)) return '—';
  const factor = Math.pow(10, decimals);
  const rounded = Math.round(diff * factor) / factor;
  const sign = rounded > 0 ? '+' : '';
  return sign + rounded.toLocaleString('zh-CN', { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
}

// ───────────────────────── 取值 / 差异 / 着色 ─────────────────────────

/**
 * 取某料号某列某侧的原始数值。api.md §2 语义：
 * - PRODUCT_TOTAL → side.productTotal
 * - TAB_PAIR → side.tabs[componentId].tabTotal（metric===__TAB_TOTAL__）或 .subtotals[metric]
 * 取不到任一层级 → undefined（前端显示 "—"）。
 */
export function getColumnValue(row: ComparisonRowDTO, col: ColumnDef, side: Side): number | undefined {
  const sideData = side === 'quote' ? row.quote : row.costing;
  if (!sideData) return undefined;

  if (col.kind === 'PRODUCT_TOTAL') {
    return sideData.productTotal ?? undefined;
  }

  const componentId = side === 'quote' ? col.quoteComponentId : col.costingComponentId;
  const metric = side === 'quote' ? col.quoteMetric : col.costingMetric;
  if (!componentId || !metric) return undefined;

  const tab = sideData.tabs?.[componentId];
  if (!tab) return undefined;

  if (metric === TAB_TOTAL_KEY) return tab.tabTotal ?? undefined;
  return tab.subtotals?.[metric] ?? undefined;
}

/** 差异值 = 报价值 − 核价值；任一侧取不到 → undefined（不参与着色判定）。 */
export function computeDiff(quoteVal: number | undefined, costingVal: number | undefined): number | undefined {
  if (quoteVal == null || costingVal == null) return undefined;
  return quoteVal - costingVal;
}

/** 着色判定：diff<0 红（固定红线，优先）；否则 diff<threshold 橙；否则无色。 */
export function classifyDiff(diff: number | undefined, threshold: number): DiffColor {
  if (diff == null) return 'none';
  if (diff < 0) return 'red';
  if (diff < threshold) return 'orange';
  return 'none';
}

// ───────────────────────── 差异料号判定 / 排序 / 过滤 / 分页 ─────────────────────────

/**
 * 该料号是否算「差异料号」：单边料号（presence≠BOTH）优先级最高恒真；
 * 否则任一列差异格标橙/标红即真（api.md §6 / fronttask §4.1）。
 */
export function rowIsDiff(row: ComparisonRowDTO, columns: ColumnDef[]): boolean {
  if (row.presence !== 'BOTH') return true;
  for (const col of columns) {
    const diff = computeDiff(getColumnValue(row, col, 'quote'), getColumnValue(row, col, 'costing'));
    if (classifyDiff(diff, col.threshold) !== 'none') return true;
  }
  return false;
}

/** 差异料号前置：勾选后差异料号排前，其余保持原顺序在后（Array.prototype.sort 稳定排序，非过滤）。 */
export function sortRowsDiffFirst(
  rows: ComparisonRowDTO[],
  columns: ColumnDef[],
  onlyDiff: boolean,
): ComparisonRowDTO[] {
  if (!onlyDiff) return rows;
  return rows
    .map((row, i) => ({ row, i, diff: rowIsDiff(row, columns) }))
    .sort((a, b) => {
      if (a.diff === b.diff) return a.i - b.i;
      return a.diff ? -1 : 1;
    })
    .map((x) => x.row);
}

/** 过滤框：销售料号子串模糊匹配。 */
export function filterRowsByPartNo(rows: ComparisonRowDTO[], filterText: string): ComparisonRowDTO[] {
  const t = filterText.trim();
  if (!t) return rows;
  return rows.filter((r) => r.partNo.includes(t));
}

/** 按料号块分页（一个料号 3 行不切断，因为分页作用在「料号」这一粒度，不在展开后的行）。 */
export function paginateRows(rows: ComparisonRowDTO[], page: number, pageSize: number): ComparisonRowDTO[] {
  const start = (page - 1) * pageSize;
  return rows.slice(start, start + pageSize);
}

// ───────────────────────── 连线配对 → TAB_PAIR 列 ─────────────────────────

/** LinkConfigDrawer 已配对清单里的一条原始配对（尚未转成持久化 ColumnDef）。 */
export interface LinkPairInput {
  quoteComponentId: string;
  quoteMetric: string;
  quoteTabName: string;
  quoteMetricLabel: string;
  costingComponentId: string;
  costingMetric: string;
  costingTabName: string;
  costingMetricLabel: string;
  threshold: number;
}

/**
 * 连线抽屉「确定」时：把已配对清单（按连线顺序）转成 TAB_PAIR ColumnDef[]，
 * sortOrder 从 startSortOrder 递增（api.md §5.2「按连线顺序 sortOrder 递增、追加到末尾」）。
 */
export function buildTabPairColumns(pairs: LinkPairInput[], startSortOrder: number): ColumnDef[] {
  return pairs.map((p, i) => ({
    id: genUUID(),
    kind: 'TAB_PAIR',
    sortOrder: startSortOrder + i,
    threshold: p.threshold,
    quoteComponentId: p.quoteComponentId,
    quoteMetric: p.quoteMetric,
    quoteLabel: buildTabPairLabel(p.quoteTabName, p.quoteMetricLabel),
    costingComponentId: p.costingComponentId,
    costingMetric: p.costingMetric,
    costingLabel: buildTabPairLabel(p.costingTabName, p.costingMetricLabel),
  }));
}

/** 新列追加末尾时的下一个 sortOrder（现有列为空则从 1 开始，PRODUCT_TOTAL 恒占用 0）。 */
export function nextSortOrder(columns: ColumnDef[]): number {
  if (!columns.length) return 1;
  return Math.max(...columns.map((c) => c.sortOrder ?? 0)) + 1;
}

/** 桶隔离场景下的空态提示文案（供 ComparisonBoard 复用，避免散落各处硬编码）。 */
export function bucketLabel(bucket: ComparisonBucket): string {
  return bucket === 'SALES' ? '销售' : '财务';
}
