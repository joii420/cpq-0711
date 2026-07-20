/**
 * comparisonViewService —— task-0717 报价单比对视图新端点封装。
 * 契约见 dev-docs/task-0717-比对视图/api.md，字段名与后端
 * ComparisonMetaDTO / ComparisonDataDTO / ComparisonConfigDTO 逐字段对齐（camelCase 同名）。
 *
 * 前端不新增任何取值逻辑：本文件只负责 HTTP 调用 + 类型声明，
 * 组装/计算/着色由 ../pages/quotation/comparisonMapping.ts 纯函数完成。
 */
import api from './api';

export type ComparisonBucket = 'SALES' | 'FINANCE';
export type MetricType = 'SUBTOTAL_FIELD' | 'TAB_TOTAL';
export type RowPresence = 'BOTH' | 'QUOTE_ONLY' | 'COSTING_ONLY';
export type ColumnKind = 'PRODUCT_TOTAL' | 'TAB_PAIR';

/** api.md §1 — 连线抽屉数据源：单个可比对值节点（字段小计 或 页签合计）。 */
export interface ComparisonMetricMeta {
  /** 字段名（is_subtotal 列）或 "__TAB_TOTAL__"。 */
  key: string;
  label: string;
  type: MetricType;
}

/** api.md §1 — 单个页签（组件）及其下可比对值目录。 */
export interface ComparisonTabMeta {
  /** 页签(组件)ID，列配置里的稳定引用键。 */
  componentId: string;
  tabName: string;
  sortOrder: number;
  metrics: ComparisonMetricMeta[];
}

/** api.md §1 — GET .../comparison-view/meta 响应体。 */
export interface ComparisonMetaDTO {
  quoteTabs: ComparisonTabMeta[];
  costingTabs: ComparisonTabMeta[];
}

/** api.md §2 — 单页签取值：页签合计 + 逐字段小计。 */
export interface ComparisonTabValue {
  /** 页签合计（4 位口径）；缺失为 null/undefined。 */
  tabTotal?: number | null;
  /** key = is_subtotal 字段名；缺失为 null/undefined。 */
  subtotals?: Record<string, number | null> | null;
}

/** api.md §2 — 单侧（报价/核价）取值。 */
export interface ComparisonSideDTO {
  /** 产品卡片总计（2 位口径）。 */
  productTotal?: number | null;
  /** key = componentId（页签）。 */
  tabs?: Record<string, ComparisonTabValue> | null;
}

/** api.md §2 — 单个销售料号行。 */
export interface ComparisonRowDTO {
  partNo: string;
  productName?: string | null;
  presence: RowPresence;
  /** presence=COSTING_ONLY 时为 null。 */
  quote?: ComparisonSideDTO | null;
  /** presence=QUOTE_ONLY 时为 null。 */
  costing?: ComparisonSideDTO | null;
}

/** api.md §2 — GET .../comparison-view/data 响应体。 */
export interface ComparisonDataDTO {
  rows: ComparisonRowDTO[];
}

/** api.md §5 — 比对列定义（存于 config.columns JSONB）。 */
export interface ColumnDef {
  /** 列唯一 id（前端生成，删除/定位用）。 */
  id: string;
  kind: ColumnKind;
  /** 列顺序（追加到末尾 = 递增）。 */
  sortOrder: number;
  /** 差异阈值，默认 0。 */
  threshold: number;

  // kind=TAB_PAIR 时必填：
  quoteComponentId?: string;
  quoteMetric?: string;
  /** 冗余存展示名（模板漂移时兜底渲染），已含 "页签·小计" 格式化。 */
  quoteLabel?: string;
  costingComponentId?: string;
  costingMetric?: string;
  costingLabel?: string;
}

/** api.md §3/§4 — GET/PUT .../comparison-view/config 响应体。 */
export interface ComparisonConfigDTO {
  quotationId: string;
  bucket: ComparisonBucket;
  /** 从未保存过配置 → null（前端自行种入默认列，不落库）。 */
  columns: ColumnDef[] | null;
  updatedAt?: string;
}

export const comparisonViewService = {
  getMeta: (quotationId: string) =>
    api.get(`/quotations/${quotationId}/comparison-view/meta`) as Promise<{ data: ComparisonMetaDTO }>,

  getData: (quotationId: string, frozen?: boolean) =>
    api.get(`/quotations/${quotationId}/comparison-view/data`, {
      params: frozen ? { frozen: true } : undefined,
    }) as Promise<{ data: ComparisonDataDTO }>,

  getConfig: (quotationId: string, bucket: ComparisonBucket) =>
    api.get(`/quotations/${quotationId}/comparison-view/config`, {
      params: { bucket },
    }) as Promise<{ data: ComparisonConfigDTO }>,

  putConfig: (quotationId: string, bucket: ComparisonBucket, columns: ColumnDef[]) =>
    api.put(
      `/quotations/${quotationId}/comparison-view/config`,
      { columns },
      { params: { bucket } },
    ) as Promise<{ data: ComparisonConfigDTO }>,
};
