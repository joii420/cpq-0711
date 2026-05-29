/**
 * comparisonModel —— 比对视图纯函数层。
 * 由报价侧/核价侧的 (parsedColumns, rows) + comparison_tag 元数据，
 * 构建「料号并集 × tag 交集」的双行对比模型，并按数值容差+字符严格判定差异。
 */
import type { CostingTemplateColumn } from '../../services/costingTemplateService';

/** 数值容差常量（集中定义，可调） */
export const ABS_EPS = 1e-6;
export const REL_EPS = 1e-6;

export interface TagMeta {
  code: string;
  label: string;
  groupName?: string;
  groupSortOrder?: number;
  tagSortOrder?: number;
}

export interface ComparisonColumnDef {
  tag: string;
  label: string;
  groupName?: string;
}

export interface ComparisonCellPair {
  quote: any;
  costing: any;
  highlighted: boolean;
}

export type RowPresence = 'BOTH' | 'QUOTE_ONLY' | 'COSTING_ONLY';

export interface ComparisonRowModel {
  partNo: string;
  presence: RowPresence;
  cells: Record<string, ComparisonCellPair>;
}

export interface ComparisonModel {
  columns: ComparisonColumnDef[];
  rows: ComparisonRowModel[];
}

interface ExcelRowLike {
  __hfPartNo?: string;
  [colKey: string]: any;
}

export function toNumber(v: any): number {
  if (typeof v === 'number') return Number.isFinite(v) ? v : NaN;
  if (typeof v === 'string') {
    const t = v.trim();
    if (t === '') return NaN;
    const n = Number(t);
    return Number.isFinite(n) ? n : NaN;
  }
  return NaN;
}

/** 数值容差 + 字符严格：返回「两值是否不同」 */
export function valuesDiffer(a: any, b: any): boolean {
  const na = toNumber(a);
  const nb = toNumber(b);
  if (!Number.isNaN(na) && !Number.isNaN(nb)) {
    const tol = Math.max(ABS_EPS, REL_EPS * Math.max(Math.abs(na), Math.abs(nb)));
    return Math.abs(na - nb) > tol;
  }
  return String(a ?? '').trim() !== String(b ?? '').trim();
}

function isBlank(v: any): boolean {
  return v === null || v === undefined || v === '' || v === '__loading__';
}

/** 该侧某 tag 取值列：列定义顺序第一个打了该 tag 的 col_key */
function tagToColKey(columns: CostingTemplateColumn[], tag: string): string | null {
  for (const c of columns) if (c.comparison_tag === tag) return c.col_key;
  return null;
}

function tagsOf(columns: CostingTemplateColumn[]): Set<string> {
  const s = new Set<string>();
  for (const c of columns) {
    const t = (c.comparison_tag || '').trim();
    if (t) s.add(t);
  }
  return s;
}

export function buildComparisonModel(
  quoteColumns: CostingTemplateColumn[],
  quoteRows: ExcelRowLike[],
  costingColumns: CostingTemplateColumn[],
  costingRows: ExcelRowLike[],
  tagMetas: TagMeta[],
): ComparisonModel {
  const metaByCode: Record<string, TagMeta> = {};
  for (const m of tagMetas) metaByCode[m.code] = m;

  const qTags = tagsOf(quoteColumns);
  const cTags = tagsOf(costingColumns);
  const tags = Array.from(qTags).filter((t) => cTags.has(t));

  tags.sort((a, b) => {
    const ma = metaByCode[a];
    const mb = metaByCode[b];
    const ga = ma?.groupSortOrder ?? 9999;
    const gb = mb?.groupSortOrder ?? 9999;
    if (ga !== gb) return ga - gb;
    const ta = ma?.tagSortOrder ?? 9999;
    const tb = mb?.tagSortOrder ?? 9999;
    if (ta !== tb) return ta - tb;
    return a.localeCompare(b);
  });

  const columns: ComparisonColumnDef[] = tags.map((t) => ({
    tag: t,
    label: metaByCode[t]?.label || t,
    groupName: metaByCode[t]?.groupName,
  }));

  const qColKey: Record<string, string | null> = {};
  const cColKey: Record<string, string | null> = {};
  for (const t of tags) {
    qColKey[t] = tagToColKey(quoteColumns, t);
    cColKey[t] = tagToColKey(costingColumns, t);
  }

  const quoteByPart: Record<string, ExcelRowLike> = {};
  for (const r of quoteRows) {
    const p = r.__hfPartNo;
    if (p && !(p in quoteByPart)) quoteByPart[p] = r;
  }
  const costingByPart: Record<string, ExcelRowLike> = {};
  for (const r of costingRows) {
    const p = r.__hfPartNo;
    if (p && !(p in costingByPart)) costingByPart[p] = r;
  }

  const partOrder: string[] = [];
  const seen = new Set<string>();
  for (const r of quoteRows) {
    const p = r.__hfPartNo;
    if (p && !seen.has(p)) { seen.add(p); partOrder.push(p); }
  }
  for (const r of costingRows) {
    const p = r.__hfPartNo;
    if (p && !seen.has(p)) { seen.add(p); partOrder.push(p); }
  }

  const rows: ComparisonRowModel[] = partOrder.map((partNo) => {
    const qrow = quoteByPart[partNo];
    const crow = costingByPart[partNo];
    const presence: RowPresence = qrow && crow ? 'BOTH' : qrow ? 'QUOTE_ONLY' : 'COSTING_ONLY';
    const cells: Record<string, ComparisonCellPair> = {};
    for (const t of tags) {
      const quote = qrow ? qrow[qColKey[t] as string] : undefined;
      const costing = crow ? crow[cColKey[t] as string] : undefined;
      const highlighted =
        presence === 'BOTH' && !isBlank(quote) && !isBlank(costing) && valuesDiffer(quote, costing);
      cells[t] = { quote, costing, highlighted };
    }
    return { partNo, presence, cells };
  });

  return { columns, rows };
}
