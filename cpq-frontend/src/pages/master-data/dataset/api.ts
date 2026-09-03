// ─────────────────────────────────────────────────────────────────────────────
// 数据集维护（task-260902）· 前端服务层
// Base path：/api/cpq/dataset/{dataset}（axios baseURL 已含 /api/cpq）
//
// 复用 `part-costing/api.ts` 的 `createSheetApi(basePath)` 工厂（F-1）：
//   路径形状与 /pricing-basic-data 完全同构，只有响应体字段名不同，
//   故 URL 构造/编码/unwrap 共用，字段映射留在本文件。
// ─────────────────────────────────────────────────────────────────────────────
import { createSheetApi } from '../part-costing/api';
import type { LookupResult, MasterType } from '../part-costing/types';
import type {
  DatasetKey,
  DatasetPartListResult,
  DatasetSheetsResult,
  DatasetPartOverview,
  DatasetRowsResult,
  DatasetVersionsResult,
  DatasetSaveRequest,
  DatasetSaveResult,
  DatasetLookupResult,
  ImportResult,
  PlatingSchemeResult,
} from './types';

export interface DatasetListPartsParams {
  keyword?: string;
  /** **0-based**（api.md §3） */
  page?: number;
  size?: number;
  sortBy?: string;
  sortDir?: 'asc' | 'desc';
  /**
   * ⚠️ 契约缺口：原型「核价数据-列表」画了「配置状态」过滤下拉，但 api.md §3 的 Query
   *    只列了 page/size/keyword/sortBy/sortDir，**没有这个参数**。已上报主线。
   *    此处按现有 /pricing-basic-data 的同名参数发送；后端未实现时该参数被忽略（过滤不生效），
   *    不会引发报错。
   */
  configured?: boolean;
}

export function createDatasetApi(dataset: DatasetKey) {
  const raw = createSheetApi(`/dataset/${dataset}`);

  return {
    dataset,

    /** §1 Excel 导入（multipart，字段 file） */
    importFile: (file: File) => raw.importFile<ImportResult>(file),

    /** §2 带版本 sheet 元数据（tab 数由它决定，前端不写死） */
    getSheets: () => raw.getSheets<DatasetSheetsResult>(),

    /** §3 料号列表（page 0-based） */
    listParts: (p: DatasetListPartsParams) =>
      raw.listParts<DatasetPartListResult>({
        keyword: p.keyword,
        page: p.page,
        size: p.size,
        sortBy: p.sortBy,
        sortDir: p.sortDir,
        configured: p.configured,
      }),

    /** §4 抽屉徽标 */
    getOverview: (axisValue: string) => raw.getOverview<DatasetPartOverview>(axisValue),

    /** §5 行数据（version 省略 = 当前版本） */
    getRows: (axisValue: string, sheetKey: string, version?: number) =>
      raw.getRows<DatasetRowsResult>(
        axisValue,
        sheetKey,
        version === undefined || version === null ? {} : { version },
      ),

    /** §6 版本列表（倒序） */
    getVersions: (axisValue: string, sheetKey: string) =>
      raw.getVersions<DatasetVersionsResult>(axisValue, sheetKey),

    /** §7 保存整组（必带 baseVersion，乐观锁） */
    saveRows: (axisValue: string, sheetKey: string, body: DatasetSaveRequest) =>
      raw.saveRows<DatasetSaveResult>(axisValue, sheetKey, body),

    /**
     * §8.5 电镀方案只读列表（AC-48~51）。
     * ⚠️ `page` 0-based；`cost-basic` 没有电镀方案表，后端返 404。
     * 🚫 只读，无写端点 —— 电镀方案只能经导入通道覆盖更新。
     */
    listPlatingSchemes: (p: { page?: number; size?: number; keyword?: string }) =>
      raw.get<PlatingSchemeResult>('plating-schemes', {
        page: p.page, size: p.size, keyword: p.keyword,
      }),

    /**
     * §8 主数据下拉。
     * 返回体与旧契约同构（`{items:[{code,name}]}`），直接适配 EditableSheetTable 的 `lookupFn`。
     */
    lookup: async (masterType: MasterType, keyword?: string, limit = 20): Promise<LookupResult> => {
      const r = await raw.lookup<DatasetLookupResult>(masterType, { keyword, limit });
      return { items: r?.items ?? [] };
    },
  };
}

export type DatasetApi = ReturnType<typeof createDatasetApi>;
