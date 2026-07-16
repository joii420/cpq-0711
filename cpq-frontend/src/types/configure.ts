// Configure Product types (matches backend DTOs in com.cpq.configure.dto)

export type ProductType = 'SIMPLE' | 'COMPOSITE';
export type PartMode = 'existing' | 'custom';
export type CompositeType = 'SIMPLE' | 'COMPOSITE' | 'PART';

export interface ElementOverride {
  elementCode: string;
  pct: number;
}

export interface PartRequest {
  name: string;
  partMode: PartMode;
  existingHfPartNo?: string;
  recipeCode?: string;
  elements?: ElementOverride[];
  /**
   * 工序编号数组（task-0712 缺口1 修复后）：值 = `process_master.process_no`
   * （选配候选 `effectiveValues[PROCESS].key` 原样透传，命中复用时忽略）。
   * 不再是 `process`(V4 表) 的 UUID。
   */
  processNos?: string[];
  unitWeightGrams?: number;
  /** 工序隔离键：SIMPLE 场景与顶层 tempId 同值，COMPOSITE 场景每个子件独立 UUID */
  quotationLineItemId?: string;
  /** 配件组成用量（仅 COMPOSITE 子件用），写入 material_bom_item.composition_qty。正整数，默认 1。 */
  quantity?: number;
}

export interface CompositeProcessRequest {
  defCode: string;
  participatingPartIndexes: number[];
  params: Record<string, any>;
}

export interface ConfigureProductRequest {
  productType: ProductType;
  parts: PartRequest[];
  compositeProcesses?: CompositeProcessRequest[];
  /** 主 lineItem.id UUID：后端用此 UUID insert，响应 lineItem.id === tempId，前后端 id 对齐 */
  tempId?: string;
}

export interface ConfigureProductResponse {
  lineItems: Array<Record<string, any>>;
  fingerprintMatched: boolean;
  reusedHfPartNos: string[];
  /**
   * 后端按 Σqty 兜底裁决后的有效 productType（api.md §3.3，D11+D12 架构决策1-A）：
   * Σqty==1 → SIMPLE；Σqty≥2 → COMPOSITE。可能与请求里的 req.productType 不同
   * （如单行 qty>=2 请求声明 SIMPLE 也会被裁成 COMPOSITE）。F5 消费此字段决定后续渲染/3D 切换。
   */
  productType: ProductType;
}

/**
 * P2→P3 之间"确认前"指纹预览请求（task-0712 缺口2·3a）。
 *
 * 形态对齐提交端 `ConfigureProductRequest`：customerNo + parts + compositeProcesses，
 * 与提交端 `configure()` 消费的形状一致——复用同一套指纹计算逻辑，保证
 * 「预览命中」= 「提交命中」。SIMPLE 场景 `parts` 恰 1 项；COMPOSITE 场景（Σquantity≥2）
 * `parts` 多项，`compositeProcesses` 可选。
 */
export interface LookupFingerprintRequest {
  /** 客户编码（customer.code）。 */
  customerNo: string;
  parts: PartRequest[];
  compositeProcesses?: CompositeProcessRequest[];
}

export interface LookupFingerprintSnapshot {
  unitWeightGrams?: number;
  processes: Array<{ processCode: string; seqNo: number; name?: string }>;
  compositeProcesses: Array<{
    defCode: string;
    seqNo: number;
    participatingParts: string[];
    paramValues: any;
  }>;
}

export interface LookupFingerprintResponse {
  matched: boolean;
  /** 命中的报价料号；与 `matchedPartNo` 同值，二选一读取皆可（后端字段兼容保留）。 */
  hfPartNo?: string;
  /** 命中的报价料号（task-0712 缺口2·3a 约定字段名）。 */
  matchedPartNo?: string;
  snapshot?: LookupFingerprintSnapshot;
}

export interface SearchPartResult {
  hfPartNo: string;
  partName?: string;
  specification?: string;
  sizeInfo?: string;
  statusCode?: string;
  recipeId?: string;
  recipeCode?: string;
  recipeSymbol?: string;
  recipeName?: string;
  recipeSpec?: string;
  recipeType?: 'locked' | 'editable' | 'partial';
}

// ─── F1(task-0712) 新增：有效选配模板 + 选配明细表 UI 状态类型 ────────────────
// 对齐 api.md §1.4 / fronttask.md F1 §1.5。

/** 选配参数类型码（种子数据：材质 / 元素含量 / 工序）。 */
export type SelParamTypeCode = 'MATERIAL' | 'ELEMENT' | 'PROCESS';
export type SelParamValueMode = 'single' | 'multi' | 'adjust';

/** 有效模板参数的单个候选值（`GET /sel-templates/effective` 返回，key 语义见 fronttask §8.2 开放点）。 */
export interface EffectiveTemplateValue {
  key: string;
  label: string;
}

export interface EffectiveTemplateParam {
  paramTypeCode: SelParamTypeCode;
  name: string;
  valueMode: SelParamValueMode;
  /** 限定后的可选值；adjust 类（元素含量）为空数组。allowedValues 留空(不限)时后端已回填全量。 */
  effectiveValues: EffectiveTemplateValue[];
}

/** `GET /sel-templates/effective?customerNo=` 响应（api.md §1.4）。选配添加抽屉打开即调（D6）。 */
export interface EffectiveTemplateDTO {
  customerNo: string;
  /** 实际命中的模板所属产品分类 id（UUID；可能是客户分类 / 默认分类兜底）；无模板时可能为空。 */
  resolvedCategoryId?: string;
  /** true = 回退到 __DEFAULT__ 通用模板。 */
  usedDefault: boolean;
  templateId?: string;
  /** false = 该客户行业与默认模板都没配，前端渲染"缺少选配模板"空态。 */
  hasTemplate: boolean;
  /** 仅含 enabled=true 的参数。 */
  params: EffectiveTemplateParam[];
}

/**
 * 选配添加明细表 · 单行 UI 状态（D11 明细表模型；前端本地状态，非请求 DTO，
 * 提交前经 F5 §5.3 映射规则组装为 `PartRequest`，不直接序列化提交）。
 */
export interface SelDetailRow {
  /** 前端本地 key（crypto.randomUUID()），非后端 id。 */
  rowId: string;
  recipeCode: string | null;
  /** 材质中文名，明细表列表展示用。 */
  recipeLabel: string;
  /** 元素含量覆盖值（elementCode → pct）。 */
  elementOverrides: Record<string, number>;
  /** 值 = `process_master.process_no`（选配候选 key 原样存储，见 `PartRequest.processNos`）。 */
  processNos: string[];
  /** 工序中文名，明细表列表展示用（与 processNos 同序）。 */
  processLabels: string[];
  /** 默认 1。 */
  quantity: number;
  unitWeightGrams: number | null;
}

/** 组合工艺条件区块 · 单条选择的 UI 状态（明细表 Σqty≥2 时可选）。 */
export interface CompositeSelectionState {
  /** = process_master.process_no（api.md §3.4 标识锚点，五处一致）。 */
  defCode: string;
  /** 展示用中文名。 */
  name: string;
}

/** 汇总区 · 指纹匹配结果的 UI 状态（`lookupFingerprint` 请求投影待 backtask 定稿，见 fronttask §8.3）。 */
export interface FingerprintSummaryState {
  /** 是否已发起过指纹检查（区分"未检查"与"检查后未命中"两种展示态）。 */
  checked: boolean;
  matched: boolean;
  hfPartNo?: string;
  snapshot?: LookupFingerprintSnapshot;
}
