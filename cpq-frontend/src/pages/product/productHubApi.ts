// ─────────────────────────────────────────────────────────────────────────────
// 产品管理页（task-260903 · F-5）· API 对接层
//
// 契约主源：`dev-docs/task-260902-.../api.md`（已冻结）。本任务**新建 0 个端点、改 0 个端点**。
//
// 🚧 过渡说明（2026-09-03 主线情报更正）：
//    派工时说的公共件 `createSheetApi` / `SheetPartListTab` / `SheetPartDrawer` **不会存在了** ——
//    task-260902 实测新旧抽屉 UI 逐项不同（tab 徽标 / 版本下拉格式 / 比对项提示条 / 归档告警 /
//    409 冲突条），抽公共件要在 386 行组件里穿 12 处 variant 分支，改为
//    **零触碰 legacy + 新建 `pages/master-data/dataset/DatasetSheetDrawer.tsx`**。
//    该目录当前尚未合入 master（实测不存在），本任务无从参照 ⇒ 维持本地平行实现。
//    ⚠️ 本任务**不得 import 也不得修改 `pages/master-data/part-costing/` 下任何文件**。
//    ⇒ 日后若要与 `dataset/` 收敛，删掉下面这个 `createSheetApi` 改成 import 即可，
//      调用方（ProductSalesPartTab / ProductSalesPartDrawer）一行都不用改。
//
// 🚫 **本文件刻意不实现** `import` / `saveRows` / `lookup` 三个函数：
//    本页纯只读，调 `PUT rows` 就产生了第二条升版路径（报价侧交接说明点名禁止），
//    `lookup` 只服务可编辑表格的下拉候选，本页无编辑态。写了就是超范围（api.md §3）。
// ─────────────────────────────────────────────────────────────────────────────
import api from '../../services/api';
import type {
  PartListResult,
  SheetsResult,
  PartOverview,
  SheetRowsResult,
  VersionsResult,
  CustomerPartListResult,
} from './productHubTypes';

/**
 * 统一响应包解包。
 *
 * 🚨 后端真实信封是 `{ code, message, data }`（`ApiResponse.java`），**没有 `success` 字段**
 *    —— api.md 示例里的 `"success": true` 是文档笔误，主源已于 2026-09-03 更正。
 *    因此这里**只按有无 `data` 键解包，绝不读 `success`**（读了会恒为 falsy）。
 * axios 拦截器（`services/api.ts`）已经返回 `response.data`，即信封本身。
 */
const unwrap = <T>(r: unknown): T =>
  r && typeof r === 'object' && 'data' in r ? ((r as { data: T }).data) : (r as T);

/**
 * 列表查询参数。🚨 `page` 是 **0-based**（api.md 消费方硬约束 1），调用方务必传 `current - 1`。
 *
 * ⚠️ 本文件的类型一律取自 `./productHubTypes`，**刻意不复用 `part-costing/types.ts`**：
 *    api.md §2 写的「结构完全对齐现有 SheetMeta」措辞不准（对方 2026-09-03 主动更正），
 *    实际三处不同名 —— `sheetName`↔`tabName` / `sortOrder`↔`order` / `masterType`↔`master`。
 *    喂错类型不会报编译错，只会在运行时静默取到 undefined（tab 名空白、排序乱序）。
 */
export interface DatasetListParams {
  /** **0-based** */
  page?: number;
  size?: number;
  keyword?: string;
  sortBy?: string;
  sortDir?: 'asc' | 'desc';
}

export interface SheetDatasetApi {
  listParts(params: DatasetListParams): Promise<PartListResult>;
  getSheets(): Promise<SheetsResult>;
  getOverview(axisValue: string): Promise<PartOverview>;
  getRows(axisValue: string, sheetKey: string, version?: number): Promise<SheetRowsResult>;
  getVersions(axisValue: string, sheetKey: string): Promise<VersionsResult>;
}

/**
 * 按数据集 basePath 生成整套**只读**端点调用。
 * @param basePath 形如 `/dataset/quote`（axios baseURL 已含 `/api/cpq`）
 */
export function createSheetApi(basePath: string): SheetDatasetApi {
  const enc = encodeURIComponent;
  return {
    async listParts(params) {
      const res = await api.get(`${basePath}/parts`, { params });
      return unwrap<PartListResult>(res);
    },
    async getSheets() {
      const res = await api.get(`${basePath}/sheets`);
      return unwrap<SheetsResult>(res);
    },
    async getOverview(axisValue) {
      const res = await api.get(`${basePath}/parts/${enc(axisValue)}/overview`);
      return unwrap<PartOverview>(res);
    },
    async getRows(axisValue, sheetKey, version) {
      const res = await api.get(
        `${basePath}/parts/${enc(axisValue)}/sheets/${enc(sheetKey)}/rows`,
        // version 为 undefined 时 axios 不会序列化该参数 ⇒ 等价于「省略 = 当前版本」
        { params: version === undefined ? {} : { version } },
      );
      return unwrap<SheetRowsResult>(res);
    },
    async getVersions(axisValue, sheetKey) {
      const res = await api.get(
        `${basePath}/parts/${enc(axisValue)}/sheets/${enc(sheetKey)}/versions`,
      );
      return unwrap<VersionsResult>(res);
    },
  };
}

/** 本页的数据集：报价数据。轴 = 销售料号，带版本 sheet 13 张。 */
export const QUOTE_BASE_PATH = '/dataset/quote';

/** 销售产品页签 + 抽屉共用的报价侧只读 API（C-1 ~ C-5）。 */
export const quoteSheetApi = createSheetApi(QUOTE_BASE_PATH);

/**
 * C-6 客户产品列表（`GET /dataset/{dataset}/customer-parts`）。
 *
 * ⚠️ 该端点属 api.md §2 缺口 1，**待主源 `task-260902` 承接**。未就绪时本函数会 404，
 *    调用方按普通请求失败处理（列表空态 + 错误提示），不做任何 mock 兜底 ——
 *    在生产代码里塞 mock 会把「后端没就绪」伪装成「查不到数据」，故意不做。
 */
export async function listCustomerParts(
  params: DatasetListParams,
  basePath: string = QUOTE_BASE_PATH,
): Promise<CustomerPartListResult> {
  const res = await api.get(`${basePath}/customer-parts`, { params });
  return unwrap<CustomerPartListResult>(res);
}
