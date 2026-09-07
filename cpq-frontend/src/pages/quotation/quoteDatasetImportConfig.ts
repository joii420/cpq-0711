// ─────────────────────────────────────────────────────────────────────────────
// 「导入报价数据」建单链路的静态配置（task-260907 · F-2 / F-3）
//
// 单独成文件而不是挂在抽屉组件上：抽屉是组件模块，从组件模块导出常量会破坏 Vite 的
// react-refresh（`react-refresh/only-export-components`），与 `master-data/dataset/datasetConfig.ts`
// 同一处理方式。
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 建单导入的角色白名单（`api.md §1`，**对齐 V6 导入**）。
 * 🚫 不是 `DatasetImportDrawer` 那套 `PRICING_MANAGER / SYSTEM_ADMIN`（AC-19）——
 *    那是【基础资料维护】写端点的口径，与建单入口无关。
 */
export const QUOTE_IMPORT_ROLES = ['SALES_REP', 'SALES_MANAGER', 'SYSTEM_ADMIN'];

/** AC-19 / 原型 `01-报价单列表.html` 禁用态 tooltip 文案（逐字）。 */
export const QUOTE_IMPORT_NO_PERMISSION_TIP = '仅销售/销售经理/管理员可导入报价数据';

/** 原型 `02-导入抽屉-Step1-选客户上传.html` 上传区禁用态文案（逐字，AC-2①）。 */
export const UPLOAD_NEED_CUSTOMER_TIP = '请先选择客户';

/** 原型 02 上传区副文案（16 sheet 清单）。 */
export const SHEET_HINT =
  '支持 .xlsx，需含 16 个 Sheet（物料 / 客户料号 / 物料BOM / 物料与元素BOM / 8 张费用表 / 3 张年降表 / 电镀方案）';
