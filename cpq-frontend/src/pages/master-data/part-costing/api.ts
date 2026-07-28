// ─────────────────────────────────────────────────────────────────────────────
// 主数据维护-核价基础数据维护（task-0712）· 前端服务层
// Base path：/api/cpq/pricing-basic-data（axios baseURL 已含 /api/cpq）
// 后端可能返统一 ApiResponse<T> 包裹，也可能直接返 body；unwrap 两者兼容
// （payload 本身无顶层 data 键，故安全）。
// ─────────────────────────────────────────────────────────────────────────────
import api from '../../../services/api';
import type {
  PartListResult,
  SheetsResult,
  PartOverview,
  SheetRowsResult,
  VersionsResult,
  SaveRowsRequest,
  SaveResult,
  LookupResult,
  MasterType,
} from './types';

const BASE = '/pricing-basic-data';

const unwrap = <T>(r: any): T =>
  r && typeof r === 'object' && 'data' in r ? (r.data as T) : (r as T);

/**
 * §1 料号列表（有核价数据的销售料号）
 *
 * task-0728 · api.md A1：新增 `sortBy` / `sortOrder` / `configured` 三个**可选**参数（加法式）。
 * - `page` 为 **1-based**（注意与工序列表 A2 的 0-based 不同）；
 * - `sortBy` 只能取白名单值（见 PartCostingTab 的 SORT_KEY_MAP），非法值后端忽略并回退默认序；
 * - `sortOrder` 仅在 `sortBy` 有值时才有意义；
 * - `configured`：true＝已配齐、false＝未配齐、undefined＝全部。
 *
 * axios 默认会丢弃值为 `undefined` 的 params（不会序列化成空串），故此处直接透传即可。
 */
export type PartSortBy =
  | 'materialName'
  | 'materialNo'
  | 'specification'
  | 'dimension'
  | 'configuredCount'
  | 'lastUpdatedAt';

export async function listParts(params: {
  keyword?: string;
  page?: number;
  size?: number;
  sortBy?: PartSortBy;
  sortOrder?: 'asc' | 'desc';
  configured?: boolean;
}): Promise<PartListResult> {
  const res = await api.get(`${BASE}/parts`, { params });
  return unwrap<PartListResult>(res);
}

/** §2 Sheet 元数据（16 组列定义，静态可缓存） */
export async function getSheets(): Promise<SheetsResult> {
  const res = await api.get(`${BASE}/sheets`);
  return unwrap<SheetsResult>(res);
}

/** §3 料号概览（16 组当前状态，抽屉 tab 徽标） */
export async function getOverview(materialNo: string): Promise<PartOverview> {
  const res = await api.get(`${BASE}/parts/${encodeURIComponent(materialNo)}/overview`);
  return unwrap<PartOverview>(res);
}

/** §4 读取某组数据（当前版 / 历史版；version 不传=当前版） */
export async function getRows(
  materialNo: string,
  sheetKey: string,
  version?: string,
): Promise<SheetRowsResult> {
  const res = await api.get(
    `${BASE}/parts/${encodeURIComponent(materialNo)}/sheets/${encodeURIComponent(sheetKey)}/rows`,
    { params: version ? { version } : {} },
  );
  return unwrap<SheetRowsResult>(res);
}

/** §5 版本列表（版本切换下拉 + 操作留痕） */
export async function getVersions(
  materialNo: string,
  sheetKey: string,
): Promise<VersionsResult> {
  const res = await api.get(
    `${BASE}/parts/${encodeURIComponent(materialNo)}/sheets/${encodeURIComponent(sheetKey)}/versions`,
  );
  return unwrap<VersionsResult>(res);
}

/** §6 保存整组（编辑升版）。乐观锁/护栏冲突以 error.httpStatus 区分（409/422/400）。 */
export async function saveRows(
  materialNo: string,
  sheetKey: string,
  body: SaveRowsRequest,
): Promise<SaveResult> {
  const res = await api.put(
    `${BASE}/parts/${encodeURIComponent(materialNo)}/sheets/${encodeURIComponent(sheetKey)}/rows`,
    body,
  );
  return unwrap<SaveResult>(res);
}

/** §7 主表候选下拉（远程搜索） */
export async function lookup(
  masterType: MasterType,
  keyword?: string,
  limit = 20,
): Promise<LookupResult> {
  const res = await api.get(`${BASE}/lookup/${masterType}`, {
    params: { keyword, limit },
  });
  return unwrap<LookupResult>(res);
}
