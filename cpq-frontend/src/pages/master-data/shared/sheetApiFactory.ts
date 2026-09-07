// ─────────────────────────────────────────────────────────────────────────────
// 主数据维护·「料号 × sheet × 版本」类端点的通用调用器工厂（task-260902 · F-1 抽出）
//
// 后端可能返统一 ApiResponse<T> 包裹，也可能直接返 body；unwrap 两者兼容
// （payload 本身无顶层 data 键，故安全）。
//
// task-260907 · F-4 · AC-6/AC-16：本文件由核价侧旧公共件目录的 `api.ts` 原样迁入并改名。
//   - 只保留 `createSheetApi` 工厂（函数体一个字节未改）与 `LookupFn` 类型；
//   - 随旧维护端点一并下线的 7 个具名导出、它们共用的实例与 BASE 常量、
//     `PartSortBy` 类型已删除；
//   - 现存消费方只剩 `/dataset/{dataset}` 三个数据集页签，它们在 `../dataset/api.ts`
//     里各自做字段映射，不把契约字段名回灌进本文件。
// ─────────────────────────────────────────────────────────────────────────────
import api from '../../../services/api';
import type { LookupResult, MasterType } from './types';

const unwrap = <T>(r: any): T =>
  r && typeof r === 'object' && 'data' in r ? (r.data as T) : (r as T);

// ── F-1 · 通用工厂 ───────────────────────────────────────────────────────────
/**
 * 按 basePath 生成一组「料号 × sheet × 版本」端点的原始调用器。
 *
 * 只负责 **URL 拼装 + 路径段编码 + ApiResponse 解包**，不做任何字段映射 ——
 * 字段映射由各调用方在自己的类型层做，互不污染。
 *
 * 路径形状（各消费方完全同构，故可共用）：
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

/**
 * EditableSheetTable 的 `lookupFn` prop 类型。
 *
 * 结构与原来的 `lookup(masterType, keyword?, limit?)` 具名导出逐字一致；
 * 该具名导出随其端点一并下线（task-260907 · F-4），故改为直接声明。
 * 现实现方：`../dataset/api.ts` 的 `datasetApi(ds).lookup`。
 */
export type LookupFn = (
  masterType: MasterType,
  keyword?: string,
  limit?: number,
) => Promise<LookupResult>;
