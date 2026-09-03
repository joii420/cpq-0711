// ─────────────────────────────────────────────────────────────────────────────
// 主数据维护-核价基础数据维护（task-0712）· 前端服务层
// Base path：/api/cpq/pricing-basic-data（axios baseURL 已含 /api/cpq）
// 后端可能返统一 ApiResponse<T> 包裹，也可能直接返 body；unwrap 两者兼容
// （payload 本身无顶层 data 键，故安全）。
//
// task-260902 · F-1：URL 构造/编码/unwrap 抽成 `createSheetApi(basePath)` 工厂。
//   - 本文件下方 7 个具名导出＝`createSheetApi('/pricing-basic-data')` 的薄封装，
//     **请求 URL / query / body 与改造前逐字一致**（AC-42 零回归的前提）；
//   - 新的 `/dataset/{dataset}` 三个数据集复用同一工厂（路径形状完全同构），
//     但响应体字段名不同，故各自在 `../dataset/api.ts` 里做类型映射，
//     不把新契约的字段名回灌进本文件的既有类型。
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

// ── F-1 · 通用工厂 ───────────────────────────────────────────────────────────
/**
 * 按 basePath 生成一组「料号 × sheet × 版本」端点的原始调用器。
 *
 * 只负责 **URL 拼装 + 路径段编码 + ApiResponse 解包**，不做任何字段映射 ——
 * 字段映射由各调用方（legacy / dataset）在自己的类型层做，互不污染。
 *
 * 路径形状（legacy 与 dataset 完全同构，故可共用）：
 *   GET  {base}/parts
 *   GET  {base}/sheets
 *   GET  {base}/parts/{axis}/overview
 *   GET  {base}/parts/{axis}/sheets/{sheetKey}/rows
 *   GET  {base}/parts/{axis}/sheets/{sheetKey}/versions
 *   PUT  {base}/parts/{axis}/sheets/{sheetKey}/rows
 *   GET  {base}/lookup/{masterType}
 *   POST {base}/import              （multipart，仅 dataset 侧使用）
 */
export function createSheetApi(basePath: string) {
  const enc = encodeURIComponent;
  const sheetPath = (axisValue: string, sheetKey: string) =>
    `${basePath}/parts/${enc(axisValue)}/sheets/${enc(sheetKey)}`;

  return {
    basePath,

    /**
     * 通用只读 GET：`{basePath}/{subPath}`。
     * 给上面 7 个具名端点之外的子路径用（如 `/dataset/{ds}/plating-schemes`），
     * 保持工厂本身不对任何具体页签做特化假设。
     */
    get: async <T>(subPath: string, params?: Record<string, unknown>): Promise<T> =>
      unwrap<T>(await api.get(`${basePath}/${subPath}`, { params })),

    listParts: async <T>(params: Record<string, unknown>): Promise<T> =>
      unwrap<T>(await api.get(`${basePath}/parts`, { params })),

    getSheets: async <T>(): Promise<T> =>
      unwrap<T>(await api.get(`${basePath}/sheets`)),

    getOverview: async <T>(axisValue: string): Promise<T> =>
      unwrap<T>(await api.get(`${basePath}/parts/${enc(axisValue)}/overview`)),

    getRows: async <T>(
      axisValue: string,
      sheetKey: string,
      params: Record<string, unknown>,
    ): Promise<T> =>
      unwrap<T>(await api.get(`${sheetPath(axisValue, sheetKey)}/rows`, { params })),

    getVersions: async <T>(axisValue: string, sheetKey: string): Promise<T> =>
      unwrap<T>(await api.get(`${sheetPath(axisValue, sheetKey)}/versions`)),

    saveRows: async <T>(axisValue: string, sheetKey: string, body: unknown): Promise<T> =>
      unwrap<T>(await api.put(`${sheetPath(axisValue, sheetKey)}/rows`, body)),

    lookup: async <T>(masterType: string, params: Record<string, unknown>): Promise<T> =>
      unwrap<T>(await api.get(`${basePath}/lookup/${masterType}`, { params })),

    /** Excel 导入（multipart/form-data，字段名 file）。仅 `/dataset/{dataset}` 侧使用。 */
    importFile: async <T>(file: File): Promise<T> => {
      const form = new FormData();
      form.append('file', file);
      return unwrap<T>(
        await api.post(`${basePath}/import`, form, {
          headers: { 'Content-Type': 'multipart/form-data' },
        }),
      );
    },
  };
}

/** 现有「料号核价」页签的实例（basePath 与改造前一致，行为零变化） */
const legacy = createSheetApi(BASE);

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
  return legacy.listParts<PartListResult>(params);
}

/** §2 Sheet 元数据（16 组列定义，静态可缓存） */
export async function getSheets(): Promise<SheetsResult> {
  return legacy.getSheets<SheetsResult>();
}

/** §3 料号概览（16 组当前状态，抽屉 tab 徽标） */
export async function getOverview(materialNo: string): Promise<PartOverview> {
  return legacy.getOverview<PartOverview>(materialNo);
}

/** §4 读取某组数据（当前版 / 历史版；version 不传=当前版） */
export async function getRows(
  materialNo: string,
  sheetKey: string,
  version?: string,
): Promise<SheetRowsResult> {
  return legacy.getRows<SheetRowsResult>(materialNo, sheetKey, version ? { version } : {});
}

/** §5 版本列表（版本切换下拉 + 操作留痕） */
export async function getVersions(
  materialNo: string,
  sheetKey: string,
): Promise<VersionsResult> {
  return legacy.getVersions<VersionsResult>(materialNo, sheetKey);
}

/** §6 保存整组（编辑升版）。乐观锁/护栏冲突以 error.httpStatus 区分（409/422/400）。 */
export async function saveRows(
  materialNo: string,
  sheetKey: string,
  body: SaveRowsRequest,
): Promise<SaveResult> {
  return legacy.saveRows<SaveResult>(materialNo, sheetKey, body);
}

/** §7 主表候选下拉（远程搜索） */
export async function lookup(
  masterType: MasterType,
  keyword?: string,
  limit = 20,
): Promise<LookupResult> {
  return legacy.lookup<LookupResult>(masterType, { keyword, limit });
}

/** EditableSheetTable 的 `lookupFn` prop 类型（默认实现即上面的 `lookup`） */
export type LookupFn = typeof lookup;
