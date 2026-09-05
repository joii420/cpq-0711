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
// 🚫 **本文件刻意不实现** `import` / `saveRows`（`PUT .../sheets/{key}/rows`）/ `lookup` 三个函数：
//    调 `PUT rows` 就产生了第二条升版路径（报价侧交接说明点名禁止），且**抽屉维持全只读**
//    （子任务 AC-7 反向断言）；`lookup` 只服务下拉候选，本页唯一的编辑控件是自由文本输入。
//    写了就是超范围（子任务 api.md §3）。
//
// 🆕 子任务 `task-260903-产品维护能力增强`（2026-09-03）在**只读之外**新增了两个调用，
//    两者都经过用户裁决，不是顺手加的：
//      · `updateDatasetPart` —— `PUT /dataset/{dataset}/parts/{axisValue}`（对方 B-20，闸门 A0 走方案甲）
//        列表上**只开放 `production_no` 一列**，写入语义仍归 task-260902 单一维护。
//      · `listCustomerPartCustomers` —— 客户过滤器候选（本任务 B-2）。
// ─────────────────────────────────────────────────────────────────────────────
import api from '../../services/api';
import type {
  PartListResult,
  SheetsResult,
  PartOverview,
  SheetRowsResult,
  VersionsResult,
  CustomerPartListResult,
  CustomerOptionsResult,
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
  params: CustomerPartListParams,
  basePath: string = QUOTE_BASE_PATH,
): Promise<CustomerPartListResult> {
  const res = await api.get(`${basePath}/customer-parts`, { params });
  return unwrap<CustomerPartListResult>(res);
}

/**
 * 客户产品列表的查询参数。
 *
 * 🚨 `customerNo` 与 `keyword` 是 **AND 叠加**，且过滤**必须在后端做**（AC-3 / 原型注解）：
 *    前端捞 17 行自己 filter 会让 `total` 与翻页错乱（第 2 页拿到的是未过滤的第 2 页）。
 *    省略 `customerNo` ＝「所有客户」；传一个数据里不存在的值 ⇒ 后端回 `total:0` 而**不是 404**。
 */
export interface CustomerPartListParams extends DatasetListParams {
  customerNo?: string;
}

/**
 * B-2 客户过滤器候选（`GET /dataset/{dataset}/customer-parts/customers`）。
 *
 * 🚨 **候选必须来自 `ds_quote_customer_part` 的 `SELECT DISTINCT customer_no`，不是 `customer` 表**
 *    —— 这是 AC-5 的全部意义（`Q13CUST0617` / `C1` 未建档，从主数据取会漏掉它们的 3 行产品）。
 *    该口径落在后端 B-2，前端只负责**照单全收地渲染**，🚫 不得在此再按 `customerName` 是否为空过滤，
 *    那等于把后端好不容易带出来的未建档客户又筛掉一遍。
 *
 * ⚠️ 端点未就绪时会 404 —— 调用方按「候选为空」降级（过滤器只剩「所有客户」，列表照常可用），
 *    **不做任何 mock 兜底**：塞 mock 会把「后端没就绪」伪装成「这个客户真的没有产品」。
 */
export async function listCustomerPartCustomers(
  basePath: string = QUOTE_BASE_PATH,
): Promise<CustomerOptionsResult> {
  const res = await api.get(`${basePath}/customer-parts/customers`);
  return unwrap<CustomerOptionsResult>(res);
}

/**
 * C-1 料号单列更新（`PUT /dataset/{dataset}/parts/{axisValue}`，对方 B-20）。
 *
 * 🚫 **只传要改的字段，绝不整行回传**（子任务 api.md §1 硬约束 1）——
 *    后端实现是「只更新传入的列 + `updated_at`/`updated_by`」，整行回传会把没改的列一起写。
 * 🚨 **`source` 不由前端传、也不要期望它变**（硬约束 2）：它是**行级**来源（`IMPORT`/`MANUAL`），
 *    页面改一列就把整行标成 `MANUAL` 会误导，AC-10 断言它保持 `IMPORT`。
 * 🚨 白名单在**后端** Registry 的 `ColumnDef.editable` 上（硬约束 3）。前端不渲染输入框
 *    只是第一道，**不是防线** —— 绕过 UI 直接打接口也必须被拦住（AC-11）。
 */
export interface UpdatePartPayload {
  /**
   * 生产料号。
   * 🚨 `null` = **显式清空**，期望落库 `NULL`（AC-12，不是空字符串）。
   *    ⚠️ 与导入侧的 `COALESCE` 是两件事：页面显式清空 ⇒ 真的变 NULL；
   *      导入时 Excel 空着 ⇒ 保留库里的值（对方 B-19）。别用同一条分支理解。
   */
  productionNo?: string | null;
}

export async function updateDatasetPart(
  axisValue: string,
  payload: UpdatePartPayload,
  basePath: string = QUOTE_BASE_PATH,
): Promise<void> {
  await api.put(`${basePath}/parts/${encodeURIComponent(axisValue)}`, payload);
}
