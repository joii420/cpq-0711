import api from './api';
import type { DecimalString } from '../utils/precision';
import type { CardValues, ExcelValues } from './quotationService';

export interface CostingOrderListItem {
  costingOrderId: string;
  costingOrderNumber: string;
  quotationId: string;
  quotationNumber: string;
  customerName: string;
  currency: string;
  submittedByName: string;
  status: string;
  rejectReason?: string;
  createdAt: string;
  updatedAt?: string;
}

/**
 * task-0713：核价单版本 override 持久化结构（api.md §4）。
 * 唯一键 (costingOrderId, componentId, partNo)，costingOrderId 由外层 CostingOrderDetail 隐含。
 */
export interface CostingOrderVersionOverride {
  componentId: string;
  partNo: string;
  viewVersion: string;
}

/**
 * task-0713：costing_order.costing_render 缓存里单个 lineItem 的核价侧渲染结果（api.md §1）。
 * costingCardValues/costingExcelValues 与 LineItemSnapshotValues 同形状，
 * 但来源是"已应用本单 override"的核价专属缓存，不是 frozen_dto 里的报价侧字段。
 * 后端可能以 JSON 字符串或已解析对象两种形态返回（未定死），消费方需按 quotationService
 * 里既有的 parseJson 兼容写法处理。
 */
export interface CostingRenderEntry {
  costingCardValues?: string | CardValues | null;
  costingExcelValues?: string | ExcelValues | null;
}

export interface CostingOrderDetail {
  costingOrderId: string;
  quotationId: string;
  costingOrderNumber: string;
  status: string;
  rejectReason?: string;
  totalAmount?: DecimalString;
  frozenDto?: string;
  createdAt: string;
  reviewedAt?: string;
  /** task-0713（D1）：核价侧渲染缓存，keyed by lineItemId。报价侧仍读 frozenDto，两者物理隔离。 */
  costingRender?: Record<string, CostingRenderEntry>;
  /** task-0713（D1）：核价侧单据总价 = Σ核价成本 subtotal，不含 Step3 折扣。与 totalAmount（报价总额）是两列两值，不可混用。 */
  costingTotalAmount?: DecimalString;
  /** task-0713（api.md §4）：本单当前所有版本 override，标记"已切版本"用 */
  versionOverrides?: CostingOrderVersionOverride[];
  /** task-0713：= status==='PENDING' && role∈{PRICING_MANAGER,SYSTEM_ADMIN}，决定是否显示版本切换控件 */
  editable?: boolean;
}

/** task-0713（api.md §2）：GET version-options 响应，options 倒序，currentVersion 供高亮。 */
export interface VersionOptionsResult {
  componentId: string;
  partNo: string;
  currentVersion: string | null;
  /** view_version 候选列表，后端保证倒序 */
  options: string[];
}

/** task-0713（api.md §3）：POST version-switch 响应，前端只用这些字段做增量刷新，不整单重查（守 AP-31）。 */
export interface VersionSwitchResult {
  lineItemId: string;
  /** 该卡片重算后的核价卡片值（行内含 view_version），形状同 CostingRenderEntry.costingCardValues */
  costingCardValues: string | CardValues;
  /** 若受影响才带；命名沿用 api.md 原文（"columns"字样疑与"该卡片核价 Excel 值"语义对应，
   * 后端落地后需与实际返回结构核对，见前端 RECORD 备注） */
  costingExcelColumns?: string | ExcelValues;
  /** 更新后的单据总价（Σ核价成本 subtotal，不含 Step3 折扣） */
  costingTotalAmount: DecimalString;
  /** 实际触发重查/重算的页签 componentId 列表，便于前端定向刷新提示 */
  affectedTabs: string[];
}

/**
 * task-0721（api.md §1.1）：核价通过 preview 汇总——将升版 N 组 / 新增 X / 删除 Y / 改值 Z。
 * repair-0727（api.md §1.1）：新增 affectedProducts——涉及产品数（groups 里 productNo 去重，null 不计）。
 */
export interface CostingApprovePreviewSummary {
  versionedGroups: number;
  addedRows: number;
  deletedRows: number;
  changedRows: number;
  /** repair-0727 新增 */
  affectedProducts: number;
}

/** repair-0727（api.md §1.2）：行级列差异，取代旧 Record<col,[old,new]> 结构，带中文列名。 */
export interface CostingApprovePreviewChange {
  column: string;
  label: string;
  oldValue: string | null;
  newValue: string | null;
}

/** repair-0727（api.md §1.2）：ADD/DELETE 行的列值，带中文列名。 */
export interface CostingApprovePreviewValue {
  column: string;
  label: string;
  value: string | null;
}

/**
 * task-0721（api.md §1.1）：一行变更明细。ADD 无 __v6_id。
 * repair-0727（api.md §1.2）：changes/values 由对象改为数组；新增 rowLabel（该行业务身份）/ conflict（多页签冲突取先到值）。
 * 破坏性变更：旧 Record<col,[old,new]> 形状已废弃，后端不再兼容旧形状（同批次发布，无外部消费方）。
 */
export interface CostingApprovePreviewRow {
  op: 'CHANGE' | 'ADD' | 'DELETE';
  __v6_id: string | null;
  /** repair-0727 新增：该行业务身份，前端直接展示（如「组成件 W-1001（外购件）」） */
  rowLabel?: string;
  /** repair-0727 新增：同列被多页签 patch 且值不同 */
  conflict?: boolean;
  /** op=CHANGE 时带 */
  changes?: CostingApprovePreviewChange[];
  /** op=ADD / DELETE 时带 */
  values?: CostingApprovePreviewValue[];
}

/** repair-0727（api.md §1.1）：轴的人类可读表达，替代原始 V6 物理轴串给财务看。 */
export interface CostingApprovePreviewAxisLabel {
  column: string;
  label: string;
  value: string;
  /** 拼好的展示文案，如「苏州西门子（CUST-0001）」「S-3120014539 接触片组件」 */
  display: string;
}

/** task-0721（api.md §1.1）：一个 V6 目标表分组（按 groupKey 轴聚合的一次升版）。 */
export interface CostingApprovePreviewGroup {
  /** V6 目标表名（如 unit_price） */
  table: string;
  /** 报价单页签展示名（如 电镀费） */
  tabName: string;
  /** 轴摘要，键值对形式，纯展示用 */
  groupKey: Record<string, string>;
  /** 旧版本号，无则 null=首版 */
  versionFrom: string | null;
  versionTo: string;
  /** true=全局共享表（如电镀方案），前端需重点标注「影响所有客户」 */
  isGlobalShared: boolean;
  rows: CostingApprovePreviewRow[];

  // ── repair-0727 新增字段（api.md §1.1） ──
  /** 产品归属料号，无产品维度（如 plating_scheme）为 null，此时归入 globalShared */
  productNo?: string | null;
  productName?: string | null;
  /** 业务类别中文名（BOM 组成 / 材质元素构成 / 单价 / 工时产能 / 电镀方案） */
  categoryLabel?: string;
  /** patch 语义下该组走的路径 */
  route?: 'REBUILD' | 'FLIP' | 'OFFLINE';
  /** 基底行来源 */
  baseSource?: 'PENDING' | 'CURRENT' | 'NONE';
  /** 基底行数 */
  baseRowCount?: number;
  /** 通过后该组行数（预期值） */
  resultRowCount?: number;
  /** 轴的人类可读表达 */
  axisLabels?: CostingApprovePreviewAxisLabel[];
}

/** repair-0727（api.md §1.1）：按产品聚合的视图，groupIndexes 指向 groups 数组下标（避免重复传输）。 */
export interface CostingApprovePreviewProduct {
  productNo: string;
  productName: string | null;
  customerNo: string;
  customerName: string | null;
  groupIndexes: number[];
}

/** repair-0727（api.md §1.1）：无产品维度的全局共享组（当前仅 plating_scheme）。 */
export interface CostingApprovePreviewGlobalShared {
  groupIndexes: number[];
}

// ───────────────────────────────────────────────────────────────────────────
// task-260907 第二段（api.md §1）：核价通过 → ds_quote_* 基础数据回填升版预览。
// 老回填（上面的 products / globalShared / groups）保持原样，本段是并列新增的一段，
// 老单 applicable=false 时前端渲染空态，不渲染比对表格区（AC-15）。
// ───────────────────────────────────────────────────────────────────────────

/** 「对不上的行」——跨版重锚失败，确认后按新增写入，库里原行保留（AC-20③）。 */
export interface DsBackfillUnanchoredRow {
  recordId?: number | string | null;
  /** 快照时的主表行 id（已失效） */
  originId?: number | string | null;
  baseRowFingerprint?: string | null;
  /** 该行的人类可读身份，键为中文列名（如 { 项次: "90", 投入料号: "S-1630010773" }） */
  displayValues?: Record<string, string | number | null>;
  /** 后端原因常量，如 CROSS_VERSION_FINGERPRINT_MISS */
  reason?: string | null;
}

/**
 * AP-60 列维度判据：该页签表征了哪些列（patched）/ 没表征因而原样保留的列（preserved）。
 * ⚠️ 契约示例给的是物理列名（component_qty），原型图展示的是中文列名（组成数量）——
 * 前端原样渲染后端下发的字符串，不做映射（见回报「契约疑点」）。
 */
export interface DsBackfillColumnScope {
  patched?: string[];
  preserved?: string[];
}

/** 一个「表 × 轴值」组的回填结果预告——描述「将写入什么」，不是「哪些值变了」（AP-60 判据四）。 */
export interface DsBackfillGroup {
  /** 轴 = 报价单产品卡片的销售料号（D-3） */
  axisValue: string;
  /**
   * 客户号 = customer.code（如 CUST-0001）。api.md §4 已裁决类型为 string（DB 侧 varchar(20) NOT NULL），
   * ⚠️ 但上游 DDL 尚未落库 ⇒ 过渡期后端可能返 null，前端必须容忍不报错、渲染成「—」。
   */
  customerNo?: string | null;
  /** _record 拍快照时的版本 */
  baseVersionNo?: number | null;
  /** 库里当前版本 */
  currentVersionNo?: number | null;
  /** 将升到的版本 = max(current, historyMax) + 1 */
  targetVersionNo?: number | null;
  /** baseVersionNo != currentVersionNo → 走指纹重锚 */
  crossVersion?: boolean;
  result: 'CREATED' | 'UPGRADED' | 'UNCHANGED';
  /** 主表当前整组行数（= 基底行数） */
  baseRowCount?: number;
  /** 回填后该组行数 */
  resultRowCount?: number;
  /** _record 表征并覆盖了列的行数 */
  patchedRows?: number;
  /** 🔑 页签没表征、原样保留的行数——AP-60 守卫，必须渲染，不许省 */
  untouchedRows?: number;
  unanchoredRows?: DsBackfillUnanchoredRow[];
  columnScope?: DsBackfillColumnScope;
}

/** 一张 ds_quote_* 主表（对应报价单一个页签）下的全部料号组。 */
export interface DsBackfillTable {
  sheetKey: string;
  /** 与 Excel sheet 名逐字相等 */
  sheetName: string;
  tableName: string;
  groups: DsBackfillGroup[];
}

/** 抽屉顶部汇总条五项（AC-5③）。 */
export interface DsBackfillSummary {
  tables: number;
  axes: number;
  upgradedGroups: number;
  unchangedGroups: number;
  /** 🔴 >0 时前端必须显著提示：红色告警条 + 独立明细表 + 主按钮变红 */
  unanchoredRows: number;
  /** 🆕 D-33：不参与基础数据升版的组件数。后端未下发时前端按 nonParticipating.length 兜底 */
  nonParticipatingComponents?: number;
}

/** 只落 extend_column、不回填主表的字段（AC-3）。sheetName 需由 tables[] 按 sheetKey 反查。 */
export interface DsBackfillExtendColumnOnly {
  sheetKey: string;
  sheetName?: string;
  fields: string[];
}

/**
 * 🆕 D-33：不参与基础数据升版的组件（api.md §1 硬约束 4）。
 * 实测现网 156/228 个组件视图是手写的（无 builder_config）⇒ 常态非空，
 * 🚫 前端不许静默：不说，财务会以为本单全覆盖了 —— 与 AP-60 判据四同型的静默。
 */
export interface DsBackfillNonParticipating {
  componentId?: string | null;
  componentName?: string | null;
  /**
   * 八个已知常量：NO_BUILDER_CONFIG / NO_DRIVER_PATH / UNSUPPORTED_DRIVER_PATH /
   * BUILDER_CONFIG_CORRUPT / NOT_QUOTE_DIALECT / NO_TAB_TYPE / TAB_VIEW_NOT_FOUND /
   * NOT_VERSIONED_SHEET。⚠️ 后端可能再加 ⇒ 前端必须有未知值兜底，🚫 不许渲染成空白。
   */
  reason?: string | null;
}

/** api.md §1 新增段。 */
export interface DsBackfillPreview {
  /** false = 本单不走 ds_ 新回填（老单），前端渲染空态 */
  applicable: boolean;
  /** 恒 true —— 财务必须人工确认（D-25） */
  confirmRequired?: boolean;
  summary?: DsBackfillSummary;
  /**
   * ⚠️ applicable=true 时 tables 也可能是空数组 —— 后端 2026-09-07 放宽语义：
   * 只要有 nonParticipating 要说就 applicable=true。
   * 🚫 前端不得因 tables 为空就渲染「本单不涉及…」空态，否则 D-33 的告警会
   * 恰好在「100% 不参与」这一最常见场景里整块消失。
   */
  tables?: DsBackfillTable[];
  nonParticipating?: DsBackfillNonParticipating[];
  extendColumnOnly?: DsBackfillExtendColumnOnly[];
}

/** task-0721（api.md §1.1）：GET costing-approve/preview 响应体。只读、无副作用、幂等。 */
export interface CostingApprovePreviewResult {
  quotationId: string;
  /** 影响清单内容 hash，提交时须原样带回；预览后数据漂移会致提交 409 */
  previewToken: string;
  summary: CostingApprovePreviewSummary;
  /** repair-0727 新增：按产品聚合的主视图，渲染以此为主，groups 仍保留作为数据源 */
  products: CostingApprovePreviewProduct[];
  /** repair-0727 新增：无产品维度的全局共享组视图 */
  globalShared: CostingApprovePreviewGlobalShared;
  groups: CostingApprovePreviewGroup[];
  /**
   * task-260907 第二段（api.md §1）：ds_quote_* 新回填预览段。
   * 可选——后端未发布本段时字段缺失，前端优雅降级为「只渲染老回填」（不报错、不空白）。
   */
  dsBackfill?: DsBackfillPreview;
}

/** task-0721（api.md §1.2）：POST costing-approve 成功响应，除 QuotationDTO 字段外额外带 backfill 汇总。 */
export interface CostingApproveResult {
  backfill?: CostingApprovePreviewSummary;
  /** task-260907 第二段（api.md §2）：ds_ 新回填执行摘要，形状同预览的 dsBackfill.summary */
  dsBackfill?: DsBackfillSummary;
  [key: string]: unknown;
}

const base = '/costing-orders';

export const costingOrderService = {
  /**
   * 列表查询。status 为可重复参数（后端 List<String>），发出格式为 status=A&status=B。
   */
  list: (params?: { statuses?: string[]; keyword?: string; sort?: string }): Promise<{ data: CostingOrderListItem[] }> =>
    api.get(base, {
      params: { status: params?.statuses, keyword: params?.keyword, sort: params?.sort },
      paramsSerializer: { indexes: null },
    }) as Promise<{ data: CostingOrderListItem[] }>,

  getById: (coid: string): Promise<{ data: CostingOrderDetail }> =>
    api.get(`${base}/${coid}`) as Promise<{ data: CostingOrderDetail }>,

  /**
   * task-0721（api.md §1.1）：核价通过前置预览——只读、无副作用、幂等。
   * 拿到的 previewToken 必须原样带回 approve()，否则后端 400（强制先预览）。
   */
  previewApprove: (quotationId: string): Promise<{ data: CostingApprovePreviewResult }> =>
    api.get(`/quotations/${quotationId}/costing-approve/preview`) as Promise<{ data: CostingApprovePreviewResult }>,

  /**
   * task-0721（api.md §1.2）：核价通过并回填，两段式提交，previewToken 必填。
   * 若预览后数据发生漂移，后端返 409（message=报价数据在预览后发生变化，请重新预览），
   * 调用方需重新 previewApprove 拿新 token 后重试（CostingApprovePreviewDrawer 内已处理）。
   */
  approve: (quotationId: string, previewToken: string, comment?: string): Promise<{ data: CostingApproveResult }> =>
    api.post(`/quotations/${quotationId}/costing-approve`, { comment, previewToken }) as Promise<{ data: CostingApproveResult }>,

  reject: (quotationId: string, comment: string): Promise<{ data: unknown }> =>
    api.post(`/quotations/${quotationId}/costing-reject`, { comment }) as Promise<{ data: unknown }>,

  /**
   * task-0713（api.md §2）：查询某料号在某页签的可选版本（下拉数据源）。
   * 独立轻查（列出模式），不走带缓存的 batch-expand（守 AP-37 串号）。
   */
  getVersionOptions: (
    coid: string,
    params: { lineItemId: string; componentId: string; partNo: string },
  ): Promise<{ data: VersionOptionsResult }> =>
    api.get(`${base}/${coid}/version-options`, { params }) as Promise<{ data: VersionOptionsResult }>,

  /**
   * task-0713（api.md §3）：切换版本（核心写操作）。仅 PENDING + 财务/管理员可调，
   * 否则 403。响应只含受影响卡片的增量数据，前端不得据此重新 getById 整单（守 AP-31）。
   */
  switchVersion: (
    coid: string,
    body: { lineItemId: string; componentId: string; partNo: string; viewVersion: string },
  ): Promise<{ data: VersionSwitchResult }> =>
    api.post(`${base}/${coid}/version-switch`, body) as Promise<{ data: VersionSwitchResult }>,
};
