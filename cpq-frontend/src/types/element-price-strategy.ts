/**
 * 元素单价维护与价格策略（task-0722）— 类型定义
 * 权威依据：dev-docs/task-0722-元素价格策略/api.md
 *
 * 🔒 customerNo 全链路用 string，`_GLOBAL_` 原样传递，任何一层都不转 UUID（api.md §0 / §11.11.4）。
 * 响应为裸 DTO，无 {code,data} 信封（api.md §0）。
 */

// ── 枚举 ──

export type PriceMethod = 'LATEST' | 'AVG' | 'MAX' | 'MIN';
export type WindowUnit = 'DAY' | 'WEEK' | 'MONTH' | 'YEAR';
export type SourceStatus = 'ACTIVE' | 'DISABLED';
export type ImportRowResult = 'CREATED' | 'UPDATED' | 'FAILED';
export type HitRule = 'EXCEPTION' | 'DEFAULT';
export type HistoryAction = 'CREATE' | 'UPDATE' | 'DELETE';

export const METHOD_LABEL: Record<PriceMethod, string> = {
  LATEST: '最新一条价',
  AVG: '窗口内平均值',
  MAX: '窗口内最高值',
  MIN: '窗口内最低值',
};

export const UNIT_LABEL: Record<WindowUnit, string> = {
  DAY: '天',
  WEEK: '周',
  MONTH: '月',
  YEAR: '年',
};

/** 全局策略（核价成本口径）的客户标识 —— 与后端字面量一致，全链路字符串 */
export const GLOBAL_CUSTOMER_NO = '_GLOBAL_';

// ── §1 价格源 ──

export interface PriceSourceDTO {
  id: string;
  sourceName: string;
  sourceUrl?: string | null;
  sourceType: string; // 固定 'MANUAL'，后端写死
  description?: string | null;
  status: SourceStatus;
  createdAt?: string;
  updatedAt?: string;
}

export interface PriceSourceUpsertRequest {
  sourceName: string;
  sourceUrl?: string;
  description?: string;
  status?: SourceStatus;
}

// ── §2 价格导入 ──

export interface PriceImportRowDTO {
  rowNo: number;
  elementCode: string;
  price: number;
  currency?: string;
  priceUnit?: string;
  result: ImportRowResult;
  message?: string | null;
}

export interface PriceImportResultDTO {
  sourceId: string;
  sourceName: string;
  priceDate: string;
  operatorName: string;
  elapsedMs: number;
  createdCount: number;
  updatedCount: number;
  failedCount: number;
  rows: PriceImportRowDTO[];
}

// ── §3 价格表查询 ──

export interface ElementPriceRowDTO {
  elementCode: string;
  elementName: string;
  priceDate: string;
  sourceId: string;
  sourceName: string;
  sourceStatus: SourceStatus;
  price: number;
  currency: string;
  priceUnit: string;
  operatorName: string;
  updatedAt: string;
}

export interface PageResult<T> {
  content: T[];
  totalElements: number;
  page: number;
  size: number;
}

export interface PriceMatrixRowDTO {
  elementCode: string;
  elementName: string;
  /** 与 dates 等长、按下标对齐；null = 当天该源无价格记录，前端渲染「—」，不补零 */
  prices: (number | null)[];
}

export interface PriceMatrixDTO {
  sourceId: string;
  sourceName: string;
  dates: string[];
  rows: PriceMatrixRowDTO[];
}

// ── §4.1 元素各源最新价 ──

export interface ElementLatestPriceDTO {
  sourceId: string;
  sourceName: string;
  sourceStatus: SourceStatus;
  price: number;
  currency: string;
  priceUnit: string;
  priceDate: string;
}

// ── §5 价格策略 ──

export interface StrategyDTO {
  id: string;
  elementCode?: string | null;
  elementName?: string | null;
  sourceId: string;
  sourceName: string;
  method: PriceMethod;
  windowNum: number | null;
  windowUnit: WindowUnit | null;
  factor: number;
  premium: number;
  updatedAt: string;
  updatedByName: string;
}

export interface StrategyBundleDTO {
  customerNo: string;
  default: StrategyDTO | null;
  exceptions: StrategyDTO[];
}

export interface StrategyUpsertRequest {
  customerNo: string;
  sourceId: string;
  method: PriceMethod;
  /** method='LATEST' 时不要传本字段（传了后端返 400） */
  windowNum?: number | null;
  /** method='LATEST' 时不要传本字段（传了后端返 400） */
  windowUnit?: WindowUnit | null;
  factor?: number;
  premium?: number;
  /** 仅元素级例外新建/修改必填 */
  elementCode?: string;
}

// ── §6 策略试算 ──

export interface SimulateDraftStrategy {
  id?: string | null;
  sourceId: string;
  method: PriceMethod;
  windowNum?: number | null;
  windowUnit?: WindowUnit | null;
  factor?: number;
  premium?: number;
}

export interface SimulateDraftException extends SimulateDraftStrategy {
  elementCode: string;
}

export interface SimulateDraft {
  default: SimulateDraftStrategy | null;
  exceptions: SimulateDraftException[];
}

export interface SimulateRequest {
  customerNo: string;
  baseDate: string;
  /** 可选：传了则按草稿试算（含未保存改动）；不传则按库中已存策略试算 */
  draft?: SimulateDraft;
}

export interface SimulateRowDTO {
  elementCode: string;
  elementName: string;
  hitRule: HitRule;
  sourceName: string;
  method: PriceMethod;
  rawValue: number | null;
  factor: number;
  premium: number;
  finalPrice: number | null;
  sampleDays: number;
  hasPrice: boolean;
}

// ── §7 策略变更历史 ──

export interface StrategyChangeDTO {
  field: string;
  fieldLabel: string;
  oldValue: string;
  newValue: string;
}

/** 变更快照：字段随策略内容而定，前端按需读取展示 */
export interface StrategyHistorySnapshot {
  sourceName?: string;
  method?: PriceMethod;
  windowNum?: number | null;
  windowUnit?: WindowUnit | null;
  factor?: number;
  premium?: number;
  [key: string]: unknown;
}

export interface StrategyHistoryDTO {
  id: string;
  changedAt: string;
  changedByName: string;
  targetLabel: string;
  elementCode: string | null;
  action: HistoryAction;
  /** action='UPDATE' 时非空，只含真正变化的字段；CREATE/DELETE 为空数组 */
  changes: StrategyChangeDTO[];
  /** CREATE = 新建后的全量配置；DELETE = 删除前的全量配置；UPDATE 一般不需要用它 */
  snapshot: StrategyHistorySnapshot;
}
