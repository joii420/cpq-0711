// Configure Product types (matches backend DTOs in com.cpq.configure.dto)

import type { DecimalString } from '../utils/precision';

export type ProductType = 'SIMPLE' | 'COMPOSITE';
/**
 * 配件来源模式。
 * 🔄 task-260902 · api.md §1.2：`'custom'` 更名为 `'new'`，后端两个值都接受，
 *    **前端新代码只发 `'new'`**；`'custom'` 保留仅为让 task-0712 遗留的
 *    `configure/configureRequest.ts`（已停用但保留以免破坏既有单测）继续通过类型检查。
 */
export type PartMode = 'existing' | 'new' | 'custom';

/** 配件类型（task-260902 · AC-5 新增的中间层）：本厂零件 / 外购件。 */
export type PartType = 'PART' | 'OUTSOURCED';
export type CompositeType = 'SIMPLE' | 'COMPOSITE' | 'PART';

export interface ElementOverride {
  elementCode: string;
  pct: DecimalString;
}

/**
 * 单个材质的选择（task-260902 · api.md §1.2 `PartRequest.materials[]`）。
 * 取代 task-0712 的单值 `recipeCode` + `configNo` + `elements`（老字段保留为回落分支）。
 */
export interface PartMaterialRequest {
  recipeCode: string;
  /** 标准含量配置编号，如 '00006-01'。与 `elements` **互斥且必须恰好给一个**。 */
  configNo?: string | null;
  /** 材质占比 %（100 制、12 位小数字符串）→ `material_bom_item.material_ratio`。 */
  ratio: DecimalString;
  /** 自定义含量。与 `configNo` 互斥；材质 `allowCustomContent=false` 时给了它 → 403。 */
  elements?: ElementOverride[] | null;
}

export interface PartRequest {
  name: string;
  partMode: PartMode;
  /** 🆕 task-260902：'PART' | 'OUTSOURCED'。缺省按 'PART' 解释（后端回落）。 */
  partType?: PartType;
  /** 🆕 规格 → material_master.specification */
  spec?: string;
  /** 🆕 尺寸 → material_master.dimension */
  dimension?: string;
  /** 🆕 partType=OUTSOURCED 时必填 */
  outsourcedPartNo?: string;
  /**
   * 🆕 多材质（AC-3 / AC-4）。非空时后端用它；为空才回落到下面的单值 `recipeCode`。
   * 🚨 占比是**字符串**，提交时原样发送 —— 不补零、不过 JS `number`。
   */
  materials?: PartMaterialRequest[];
  existingHfPartNo?: string;
  /** @deprecated task-260902 起由 `materials[]` 取代，仅并发分支安全回落用。 */
  recipeCode?: string;
  /**
   * 含量配置编号（task-260901 · api.md §2.4 新增），如 '00006-01'。
   * 🚨 **与 `elements` 互斥，必须恰好给一个**：两个都给或都不给 → 400 `MATERIAL_SOURCE_AMBIGUOUS`。
   */
  configNo?: string;
  /** 自定义含量（材质 `allowCustomContent=false` 时给了它 → 403 `CUSTOM_CONTENT_NOT_ALLOWED`） */
  elements?: ElementOverride[];
  /**
   * 工序编号数组（task-0712 缺口1 修复后）：值 = `process_master.process_no`
   * （选配候选 `effectiveValues[PROCESS].key` 原样透传，命中复用时忽略）。
   * 不再是 `process`(V4 表) 的 UUID。
   */
  processNos?: string[];
  unitWeightGrams?: DecimalString;
  /** 工序隔离键：SIMPLE 场景与顶层 tempId 同值，COMPOSITE 场景每个子件独立 UUID */
  quotationLineItemId?: string;
  /** 配件组成用量（仅 COMPOSITE 子件用），写入 material_bom_item.composition_qty。正整数，默认 1。 */
  quantity?: DecimalString;
}

export interface CompositeProcessRequest {
  defCode: string;
  participatingPartIndexes: number[];
  params: Record<string, any>;
}

export interface ConfigureProductRequest {
  productType: ProductType;
  /** 🆕 客户产品编号（AC-1 / AC-2，必填）。 */
  customerProductNo?: string;
  /** 🆕 客户产品名称（选填）。 */
  customerProductName?: string;
  parts: PartRequest[];
  compositeProcesses?: CompositeProcessRequest[];
  /** 主 lineItem.id UUID：后端用此 UUID insert，响应 lineItem.id === tempId，前后端 id 对齐 */
  tempId?: string;
}

/** 命中复用时带出的销售产品信息（task-260902 · api.md §1.3，AC-7 状态 C）。 */
export interface ReusedProductInfo {
  hfPartNo: string;
  partName?: string | null;
  specification?: string | null;
  dimension?: string | null;
  unitWeight?: DecimalString | null;
  materials?: Array<{ recipeCode: string; name?: string | null; ratio?: DecimalString | null }> | null;
  firstCreatedAt?: string | null;
  lastQuotedPrice?: DecimalString | null;
}

export interface ConfigureProductResponse {
  lineItems: Array<Record<string, any>>;
  fingerprintMatched: boolean;
  reusedHfPartNos: string[];
  /** 🆕 命中复用时带出销售产品信息（可空 —— 未命中或后端未回填时为 undefined）。 */
  reusedProductInfo?: ReusedProductInfo | null;
  /** 🆕 本次指纹结构版本（'v2'），便于前端与排查对账。 */
  structureVersion?: string;
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
  unitWeightGrams?: DecimalString;
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

/** 已有零件的单个材质构成项（多材质零件在 task-260902 之后才会出现）。 */
export interface SearchPartMaterial {
  recipeCode?: string | null;
  recipeSymbol?: string | null;
  recipeName?: string | null;
  /** 占比 %（100 制字符串）；单材质零件为 '100' 或空。 */
  ratio?: DecimalString | null;
}

export interface SearchPartResult {
  hfPartNo: string;
  partName?: string;
  specification?: string;
  sizeInfo?: string;
  unitWeight?: DecimalString | null;
  statusCode?: string;
  /**
   * 🚧 **契约缺口（已报主线）**：`原型图/4-已有零件与工序.html` 状态 A 要求「材质构成」列能显示
   * N 个材质标签，但 `api.md §3` 把 `search-parts` 列为「复用、不改」，DTO 里只有单值
   * `recipeCode/recipeSymbol`。本字段按**可选**声明：后端补上就渲染 N 个标签，
   * 没有就回落到下面的单值字段渲染 1 个标签（不报错、不空白）。
   */
  materials?: SearchPartMaterial[] | null;
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
  /**
   * 含量来源（task-260901 · F-8/F-9）：
   *   'config' = 选材质库的标准配置（走 `configNo`）
   *   'custom' = 自定义含量（走 `elementOverrides`，仅材质 allowCustomContent=true 时可选）
   * 未定义时按历史行为兜底：有 elementOverrides 就当 custom（老草稿回填友好）。
   */
  contentMode?: 'config' | 'custom';
  /** 选中的含量配置编号，如 '00006-01'。contentMode='config' 时必填。 */
  configNo?: string | null;
  /** 含量配置的展示文案，如 '00006-01（Ag 90% / Ni 10%）'。 */
  configLabel?: string;
  /** 元素含量覆盖值（elementCode → pct）。contentMode='custom' 时使用。 */
  elementOverrides: Record<string, DecimalString>;
  /** 值 = `process_master.process_no`（选配候选 key 原样存储，见 `PartRequest.processNos`）。 */
  processNos: string[];
  /** 工序中文名，明细表列表展示用（与 processNos 同序）。 */
  processLabels: string[];
  /** 默认 1。 */
  quantity: DecimalString;
  unitWeightGrams: DecimalString | null;
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

// ─────────────────────────────────────────────────────────────────────────────
// task-260902 · 选配流程重构：4 步向导的 UI 状态类型
//
// 🚨 与上面 task-0712 的 `SelDetailRow` 是**两套模型**，不是替换关系：
//    `SelDetailRow`（一行 = 一个材质料号）是旧的两层模型，本次重构后不再被
//    `ConfigureProductDrawer` 使用，但**文件与类型都保留** —— `configure/SelDetailTable.tsx`
//    与 `configure/configureRequest.ts` 及其单测仍引用它，删了会连带弄红别人的产出。
//    新流程用下面的三层模型：产品 → 配件(ConfigurePart) → 材质(ConfigurePartMaterial)。
// ─────────────────────────────────────────────────────────────────────────────

/** 选配里选中的一个工序（有序列表的一项；**允许重复**，故 uid 与 processNo 分开）。 */
export interface SelectedProcess {
  /** 前端本地 key —— 同一个 processNo 可重复加入（AC-20 焊两次 ≠ 焊一次）。 */
  uid: string;
  /** = `process_master.process_no`，原样进 `PartRequest.processNos`。 */
  processNo: string;
  name: string;
  category?: string | null;
  processType?: string | null;
}

/** 配件下挂的一个材质（UI 状态）。 */
export interface ConfigurePartMaterial {
  uid: string;
  recipeCode: string;
  /** 材质名（symbol，展示用）。 */
  recipeName: string;
  /** 该材质是否允许自定义含量（来自 `MaterialRecipeLite.allowCustomContent`）。 */
  allowCustomContent: boolean;
  contentMode: 'config' | 'custom';
  configNo: string | null;
  /** 含量配置的展示文案，如 `00006-01（Ag 90% / Ni 10%）`（已去尾随零）。 */
  configLabel?: string;
  /** 自定义含量：元素只能改含量、不能增删。 */
  elements: Array<{ elementNo: string; elementCode: string; elementName: string; pct: DecimalString }>;
  /**
   * 材质占比 %（**用户输入的原始字符串**，原样提交）。
   * 🚨 合计校验走定点整数（`configure/ratioRules.ts`），🚫 不许 `Number` 累加。
   */
  ratio: DecimalString;
}

/** 一个配件（本次重构新增的中间层）。 */
export interface ConfigurePart {
  uid: string;
  partType: PartType;
  /** partType='PART' 时有意义；'OUTSOURCED' 恒为 'new'（不参与判断）。 */
  partMode: PartMode;

  // ── partType=PART & partMode=new ──
  name: string;
  spec: string;
  dimension: string;
  unitWeightGrams: DecimalString;
  materials: ConfigurePartMaterial[];

  // ── partType=PART & partMode=existing ──
  existingHfPartNo?: string;
  existingPartName?: string;
  existingSpec?: string;
  existingMaterialSummary?: string;

  // ── partType=OUTSOURCED ──
  outsourcedPartNo?: string;
  outsourcedName?: string;
  outsourcedSpec?: string;

  /** 三条路径共用：工序有序列表。 */
  processes: SelectedProcess[];
}

/** 组合工序的一条（步骤 3）。 */
export interface CompositeProcessItem {
  uid: string;
  /** = `process_master.process_no`（五处标识锚点之一）。 */
  defCode: string;
  name: string;
}

/** `GET /quotations/configure/check-product-no` 响应（api.md §2.1）。 */
export interface CheckProductNoResponse {
  taken: boolean;
  hfPartNo?: string | null;
  createdAt?: string | null;
}

/** `GET /quotations/configure/outsourced-parts` 的单项（api.md §2.2）。 */
export interface OutsourcedPartDTO {
  materialNo: string;
  materialName?: string | null;
  specification?: string | null;
  unitWeight?: DecimalString | null;
}

export interface OutsourcedPartPage {
  total: number;
  items: OutsourcedPartDTO[];
}
