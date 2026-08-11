import React, { useState, useEffect, useMemo } from 'react';
import type { ComponentDataItem, ComponentField } from './QuotationStep2';
import {
  computeAllFormulas, computeProductSubtotal, buildSnapshotExpansions, buildCrossTabRows, EMPTY_LINEITEMS,
  computeTabFormulasTree, usesTreeTokensTab, type TreeFormulaRowInput,
} from './QuotationStep2';
import { enrichComponentData, buildComponentDataFromStructure } from './enrichComponentData';
import { useDriverExpansions, driverExpansionKey, fieldsOverrideHash, bnfDriverLookupKey } from './useDriverExpansions';
import { layoutTreeRows, isTreeRowHidden, resolveTreeKey } from './treeTable';
import { splitRows, rowAt } from './manualRows';
import { useTreeCollapse } from './useTreeCollapse';
import { computeRowKey, buildUniqueRowKeys, buildLegacyRowKeySets, getByKeyWithLegacyFallback } from './useCardSnapshots';
import type { CardStructure, CardValues } from '../../services/quotationService';
import { useConfigTemplates } from './useConfigTemplates';
import { usePathFormulaCache } from './usePathFormulaCache';
import FieldTraceIcon from './components/FieldTraceIcon';
import ComponentCell from './components/ComponentCell';
import type { CellContext } from './components/ComponentCell';
import type { GlobalVariableDefinition } from '../../services/globalVariableService';
import { sumTabColumns } from './tabTotalLines';
import { formatNumber } from '../../utils/formatNumber';
import { resolveFieldWidth } from '../component/types';
import VersionSelectDropdown from './VersionSelectDropdown';
import type { VersionSwitchResult } from '../../services/costingOrderService';
import type { DecimalContext } from '../../utils/formulaEngine';
import {
  sumDecimal,
  toCalculationString,
  toDecimal,
  type DecimalString,
  type DecimalValue,
} from '../../utils/precision';
import { parseSnapshotJsonLossless, tryParseSnapshotJsonLossless } from '../../utils/losslessJson';
import {
  assembleReadonlySnapshotRow,
  buildReadonlySnapshotIndex,
  materializeReadonlySnapshotInputs,
} from './readonlySnapshotRows';
import './quotation.css';

/** Readonly product card for quotation detail page */

// 关键字段：显示 FieldTraceIcon 的字段名集合
const TRACE_FIELD_NAMES = new Set([
  'unit_price',
  'process_cost',
  'material_cost',
  'total_price',
  'element_actual_unit_price',
]);

/** 判断是否为需要追溯的关键字段（单价/费用/公式衍生字段） */
function isTraceField(field: ComponentField): boolean {
  const key = field.name || '';
  return (
    TRACE_FIELD_NAMES.has(key) ||
    field.is_amount === true ||
    field.is_subtotal === true ||
    field.field_type === 'FORMULA'
  );
}

interface ReadonlyProductCardProps {
  lineItem: any;  // QuotationDTO.LineItemDTO from backend
  index: number;
  /** 报价单 ID（从父组件传入，用于 FieldTraceIcon） */
  quotationId?: string;
  /** 报价单状态（用于 FieldTraceIcon isDraft 判断） */
  quotationStatus?: string;
  /** 报价单客户 ID（用于 driver 展开 cache key；缺省时 driver 不区分客户） */
  customerId?: string;
  /** B-GV-2 修复: 动态 key 全局变量定义字典, 供 FORMULA 字段 evaluateExpression 使用 */
  globalVariableDefs?: Record<string, GlobalVariableDefinition>;
  /** Phase4 Task4: 报价卡片结构快照(顶层, 提供 rowKeyFields) — 详情页读 formulaResults 对齐编辑页(AP-50) */
  quoteCardStructure?: CardStructure | null;
  /** 视图侧：缺省 'QUOTE'（报价卡片，现状不变）；'COSTING' 走核价结构驱动快照 */
  side?: 'QUOTE' | 'COSTING';
  /** 核价卡片结构快照（顶层，提供 tabs 结构 + rowKeyFields，用于结构驱动组装 componentData） */
  costingCardStructure?: CardStructure | null;
  /** Plan 1b 详情页定位:目标页签 componentId(仅目标卡非空);locateSeq 变化时切到该页签 */
  locateComponentId?: string;
  locateSeq?: number;
  /**
   * frozen 模式（核价工作台读冻结副本）：
   * QUOTE 分支用 buildComponentDataFromStructure 离线组装，不发 /templates 请求。
   * COSTING 分支本就离线，frozen 对其无影响。
   */
  frozen?: boolean;
  /** task-0713：核价单 ID。仅 CostingReviewPage（mainTab==='costing'）传入；报价单视图不传，
   *  版本下拉不出现，QUOTE 分支渲染逻辑一行不改。 */
  coid?: string;
  /** task-0713（F4）：= PENDING + 财务/管理员，决定版本下拉是否可交互（false 时纯文本只读展示）。 */
  editable?: boolean;
  /** task-0713（F3）：版本切换成功后的增量回调，上抛给 CostingReviewPage 合并到本地状态。 */
  onVersionSwitched?: (result: VersionSwitchResult) => void;
}

function parseJson<T>(value: T | string | null | undefined, fallback: T): T {
  if (value == null) return fallback;
  if (typeof value === 'string') {
    return tryParseSnapshotJsonLossless<T>(value) ?? fallback;
  }
  return value;
}

/**
 * 按行预计算所有 FORMULA 字段值，支持 prev_row_subtotal 累加（与 QuotationStep2 同源）。
 * 返回每行 { formulaCache, fieldValues } 数组：
 *   - formulaCache：FORMULA 字段求值结果（原有语义）
 *   - fieldValues：所有字段（含 INPUT/FIXED/DATA_SOURCE/BASIC_DATA）的数值，供列小计回退取值
 * AP-50 三视图一致修复：列小计累加时对输入型小计列使用 fieldValues 回退，与
 * computeTabSubtotalsByColumn (Task1) 和后端 (Task2) 口径一致。
 */
function buildFormulaCache(
  comp: ComponentDataItem,
  rows: Record<string, any>[],
  compSubtotals: DecimalContext,
  partNo?: string,
  globalVariableDefs?: Record<string, GlobalVariableDefinition>,
  // 2026-05-31 修复（小计合计/产品小计 ¥∞）：必须按行喂 driver 展开的 basicDataValues，
  // 否则 BASIC_DATA 分母字段（如 成材率）取不到值 → ?? 0 → 工序单价=单价÷0=Infinity →
  // 子小计求和 = ∞。与渲染层 preComputedCaches 同款（按 driver 行数 + 行级 bdv）。
  driverExpansion?: { rowCount: number; rows: Array<{ basicDataValues?: Record<string, any>; __sys?: { nodeId?: string; parentId?: string | null; lvl?: number } }> },
  // cross_tab_ref 三视图对齐 (Task 4.3): PASS1 小计循环不传（undefined），
  // 仅渲染层 PASS2 才传 crossTabRows，镜像后端两阶段。
  crossTabRows?: Record<string, Array<Record<string, any>>>,
): Array<{ formulaCache: Record<string, DecimalString | null>; fieldValues: DecimalContext }> {
  const useDriver = !!(driverExpansion && driverExpansion.rowCount > 0);
  // AP-51 行数纪律：driver 权威优先，仅 rowCount=0 时退回持久化行数。
  const effectiveCount = useDriver ? driverExpansion!.rowCount : rows.length;
  const caches: Array<{ formulaCache: Record<string, DecimalString | null>; fieldValues: DecimalContext }> = [];

  // task-0803 Task 7：BOM 树页签分流（AP-50：与编辑页 QuotationStep2 共用 computeTabFormulasTree 入口，
  // 不各写一份）。previous_row_subtotal 链在 BOM 页签禁用（§4.3.7），命中即整页签批量算一次。
  const hasBomSysRows = useDriver && !!driverExpansion!.rows.some((r) => r?.__sys?.nodeId !== undefined);
  const useTree = hasBomSysRows && usesTreeTokensTab(comp);
  let treeResults: Record<number, Record<string, DecimalString | null>> | undefined;
  let treeFieldValuesByRow: DecimalContext[] | undefined;
  if (useTree) {
    const treeRowInputs: TreeFormulaRowInput[] = [];
    for (let ri = 0; ri < effectiveCount; ri++) {
      const row = rows[ri] ?? {};
      const expRow = driverExpansion!.rows[ri];
      const sys = expRow?.__sys;
      treeRowInputs.push({
        row, basicDataValues: expRow?.basicDataValues,
        nodeId: sys?.nodeId, parentId: sys?.parentId, lvl: sys?.lvl,
      });
    }
    treeFieldValuesByRow = [];
    treeResults = computeTabFormulasTree(
      comp, treeRowInputs, compSubtotals, undefined, undefined, partNo, globalVariableDefs, crossTabRows,
      { fieldValuesByRow: treeFieldValuesByRow });
  }

  // Plan 2b：上一行全量公式值，previous_row_subtotal 按本列取。
  let prevRowValues: Record<string, DecimalString | null> | undefined = undefined;
  for (let ri = 0; ri < effectiveCount; ri++) {
    const row = rows[ri] ?? {};
    const bdv = useDriver ? driverExpansion!.rows[ri]?.basicDataValues : undefined;
    if (treeResults) {
      caches.push({ formulaCache: treeResults[ri] ?? {}, fieldValues: treeFieldValuesByRow?.[ri] ?? {} });
      continue;
    }
    // AP-50 修复：传入 out.fieldValues 让 computeAllFormulas 回填所有字段（含输入型），
    // 用于列小计累加时对输入型小计列回退取值（与 computeTabSubtotalsByColumn 同口径）。
    const fv: DecimalContext = {};
    const formulaCache = computeAllFormulas(
      comp, row, compSubtotals,
      undefined, undefined, partNo, bdv,
      undefined, globalVariableDefs, crossTabRows, prevRowValues,
      { fieldValues: fv },
    );
    caches.push({ formulaCache, fieldValues: fv });
    prevRowValues = formulaCache;
  }
  return caches;
}

// task-0801（AP-50：详情页须与编辑页 QuotationStep2.formatCurrency 同口径）：不再固定 2 位
// toLocaleString，改走 formatNumber（DISPLAY_SCALE=6 兜底去尾零）。
const formatCurrency = (val: DecimalValue | null | undefined) =>
  `¥ ${formatNumber(val ?? '0', { isComputed: true }) ?? '0'}`;

/** 单元格值格式化 — V197 同 QuotationStep2.formatPathValue 同款逻辑, 支持 JSONB 包装对象 */
const formatCellValue = (v: any): string => {
  if (v == null || v === '') return '—';
  if (typeof v === 'boolean') return v ? '是' : '否';
  if (typeof v === 'number') return '—';
  if (typeof v === 'string') return v;
  // V197: PG JDBC jsonb 列读成 PGobject {type:'jsonb', value:'<json>'}, 单 cell 完整展开
  if (typeof v === 'object') {
    if (v.type === 'jsonb' && typeof v.value === 'string') {
      try {
        const parsed = parseSnapshotJsonLossless<any>(v.value);
        if (Array.isArray(parsed)) {
          if (parsed.length === 0) return '—';
          return parsed.map(it => formatCellValue(it)).filter(s => s && s !== '—').join(', ') || '—';
        }
        if (parsed && typeof parsed === 'object') {
          const keys = Object.keys(parsed);
          if (keys.length === 0) return '—';
          return keys.map(k => {
            const sub = formatCellValue(parsed[k]);
            return sub && sub !== '—' ? `${k}=${sub}` : null;
          }).filter(Boolean).join(', ') || '—';
        }
        return String(parsed);
      } catch { return v.value; }
    }
    if (Array.isArray(v)) {
      if (v.length === 0) return '—';
      return v.map(it => formatCellValue(it)).filter(s => s && s !== '—').join(', ') || '—';
    }
    // 普通 object 取首个非空字段
    for (const k of Object.keys(v)) {
      if (v[k] != null && v[k] !== '') return formatCellValue(v[k]);
    }
    return '—';
  }
  return String(v);
};

const ReadonlyProductCard: React.FC<ReadonlyProductCardProps> = ({
  lineItem,
  index,
  quotationId,
  quotationStatus,
  customerId,
  globalVariableDefs,
  quoteCardStructure,
  side: sideProp,
  costingCardStructure,
  locateComponentId,
  locateSeq,
  frozen,
  coid,
  editable,
  onVersionSwitched,
}) => {
  const side = sideProp ?? 'QUOTE';
  const isCosting = side === 'COSTING';
  // task-0806 FR-1（阶段①，AP-50：与 QuotationStep2.tsx 的 isDraft 同一判定口径）：
  // DRAFT 单据的行内 FORMULA 单元格取值改走前端引擎；非 DRAFT 仍读快照 formulaResults。
  // 提到最前面统一算一次，供下方 useSnap（快照读取总闸）之外单独叠加到 snapFormula 的读分支——
  // 不改 useSnap 本身：useSnap 同时驱动是否发 batch-expand（driver 展开）等更大范围的决策
  // （见该变量声明处大段注释：『零 batch-expand 约定』），阶段①范围只是"显示读哪份"，不动这个闸门。
  const isDraft = !quotationStatus || quotationStatus === 'DRAFT';
  const [activeTab, setActiveTab] = useState(0);
  const [components, setComponents] = useState<ComponentDataItem[]>([]);
  const [loading, setLoading] = useState(true);
  const treeCollapse = useTreeCollapse();

  const attrValues: Record<string, any> = parseJson(lineItem.productAttributeValues, {});

  // Enrich componentData with fields/formulas from template snapshot.
  // Bug C (2026-05-20): 改调共享的 enrichComponentData，与 QuotationWizard 完全同源。
  // 保证详情页 = 编辑页只读版，4 个关键字段 (datasource_binding / global_variable_code /
  // default_source / sort_order) 在详情页也被透传，Tab 列表与编辑页对齐。
  //
  // COSTING 分支（side='COSTING'）：用 buildComponentDataFromStructure 从 costingCardStructure
  // 同步组装，不发网络请求，与编辑态 costingLineItems 的结构驱动路径对齐（零计算、零 batch-expand）。
  useEffect(() => {
    if (isCosting) {
      // COSTING: 从结构快照同步组装，无需 GET /templates
      if (!costingCardStructure) {
        setComponents([]);
        setLoading(false);
        return;
      }
      // 传 [] 而非 lineItem.componentData（报价侧）：核价的值/行数来自 costingCardValues 快照，
      // 报价 componentData 里的 rows 若与核价 tabName 撞键会通过 savedByTab 兜底污染核价 scaffold 行数。
      const built = buildComponentDataFromStructure(costingCardStructure, []);
      setComponents(built);
      setLoading(false);
      return;
    }
    // QUOTE：优先用 quoteCardStructure 离线组装（不发 /templates 请求）。
    //
    // 🔒 C3（2026-08-04，#42③ 翻案返修）：判据从 `frozen &&` 放宽为**编辑页同款** canUseStruct
    //    （镜像 QuotationWizard.tsx:468-481，同一判据 + 同一组装函数）。
    //    背景：元素角色字段（elementCodeField/elementPriceField/elementCurrencyField）等 tab 级
    //    结构性字段只冻进 quotation_view_structure，模板 components_snapshot 里没有；详情页此前只在
    //    frozen 模式走结构、live 模式走 enrichComponentData(模板快照) → activeComponent.elementPriceField
    //    恒 undefined → 单价列锁定徽标对**所有行（含 driver 行）**都不出现。AP-50 要的"双端一致"
    //    此前只在代码层面成立、生产上并未兑现。放宽后详情页与编辑页**取源同源**，结构才是这张单的
    //    权威（quotation_view_structure 创建即冻、永不重建；模板快照会随 republish 漂移 —— AP-39）。
    //
    // 🔒 `frozen ||` 刻意保留：核价工作台（CostingReviewPage 传 frozen DTO）今天不看 templateId
    //    就走结构，保留该短路让那条路径**逐字节不变**（其设计目标之一是"零 live /templates 请求"，
    //    若 frozen DTO 的 templateId 与行不匹配而落到 enrich 分支就会破坏该保证）。
    //
    // 无结构 / templateId 对不上（存量单）→ canUseStruct 为假 → 原样回退下面的 enrich，零影响。
    const canUseStruct = !!quoteCardStructure
      && Array.isArray(quoteCardStructure.tabs) && quoteCardStructure.tabs.length > 0
      && (!quoteCardStructure.templateId
          || String(quoteCardStructure.templateId) === String(lineItem.templateId));
    if (quoteCardStructure && (frozen || canUseStruct)) {
      const built = buildComponentDataFromStructure(quoteCardStructure, lineItem.componentData || []);
      setComponents(built);
      setLoading(false);
      return;
    }
    // QUOTE: 异步 enrich（现状不变）
    const enrich = async () => {
      const rawCompData: any[] = lineItem.componentData || [];
      if (!lineItem.templateId) {
        setComponents([]);
        setLoading(false);
        return;
      }
      try {
        const enriched = await enrichComponentData(lineItem.templateId, rawCompData);
        setComponents(enriched);
      } catch {
        setComponents([]);
      } finally {
        setLoading(false);
      }
    };
    enrich();
  }, [lineItem, isCosting, costingCardStructure, frozen, quoteCardStructure]);

  // Bug C 续 (2026-05-20): 引入 useDriverExpansions，与编辑页渲染行数对齐。
  // 问题根因：enrichComponentData 直接返回 saved.rows（DB 持久化行数，历史上可能含多余行），
  // 编辑页 ProductCard 用 driverCount 屏蔽超出 driver 的尾行，而详情页直接渲染全部 rows
  // → 相同料号详情页比编辑页多 1 行（已被 driver 过滤的陈旧持久化行）。
  // 修法：把 lineItem 包成 LineItem[] 传入 useDriverExpansions，取得 driverExpansions cache，
  // 渲染时按 driverCount 限制行数（与编辑页 ProductCard 第 1339-1361 行逻辑完全对齐）。
  //
  // 2026-05-31 修复（详情页 BASIC_DATA 公式输入全取 0 → 元素小计=0 / 工序单价=单价÷(成材率÷100)=Infinity）：
  //   必须喂 enrich 后的 `components`（含 dataDriverPath + fields），而不是 raw `lineItem`。
  //   后端 ComponentDataDTO 不持久化 dataDriverPath/fields，raw lineItem.componentData 只有
  //   {componentId, tabName, rowData, subtotal, sortOrder} → useDriverExpansions/usePathFormulaCache
  //   走 `!hasDriver && !hasFields → continue` 跳过所有 tab → driver 永不展开 → computeAllFormulas
  //   的 BASIC_DATA 字段（成材率/含量/单重/组成用量）取不到值 → ?? 0 → 乘法公式归 0、除法公式除零 Infinity。
  //   编辑页（QuotationWizard）传的是 enrich 后的 lineItems（见其 2026-05-19 同类修复注释），故正常。
  //   列单元格因 ComponentCell 会回退 row[key] 仍显示持久化值，唯独 FORMULA 输入不回退 → 本 bug。
  const lineItemsForDriver = useMemo(
    () => [{ ...lineItem, componentData: components.length > 0 ? components : (lineItem.componentData || []) }],
    [lineItem, components],
  );
  // Phase4 Task4: 详情页读快照(AP-50 与编辑页 single-source)。
  //   有 quoteCardValues/costingCardValues 时: 不再 batch-expand(传 EMPTY_LINEITEMS), 改从行级值快照
  //   构造 driverExpansions (BASIC_DATA + 行数来自快照 baseRows); FORMULA 优先读 formulaResults[rowKey]。
  //   只读页无受控 input, 故安全(不涉 AP-54)。无快照(存量单)回退实时 batch-expand。
  //   COSTING/QUOTE 两侧各读自己的 cardValues，绝不串源。
  //
  //   竞态修复（2026-06-29）：去掉旧的 `&& components.length > 0` 守卫。
  //   该守卫是 Phase4 Task4 引入的"等 async enrich 完成"保守项，但副作用是首渲染时
  //   components=[]（enrich/rebuild 未完成）→ useSnap=false → driver 闸门开放 → 发 batch-expand，
  //   违反"零 batch-expand"约定（E2E 可见 3 次 /api/cpq/components/batch-expand）。
  //   cardValues 是服务端字段，打开即确定、稳定，以它为唯一闸门。
  //   useSnap=true 但 components=[] 时渲染层 normalComponents=[] → 显示"加载组件结构..."占位，
  //   无错误、无串值（AP-38 驱动行 0 时已有 "—" 兜底）。
  const useSnap = !!(isCosting ? lineItem.costingCardValues : lineItem.quoteCardValues);
  const { cache: liveExpansions } = useDriverExpansions(
    useSnap ? EMPTY_LINEITEMS : (lineItemsForDriver as any), customerId, quotationId,
    isCosting ? 'COSTING' : 'QUOTE');
  // rowKeyFieldsByComp 须先于 snapExpansions 构建（snapExpansions 依赖它做墓碑过滤 AP-54）
  // COSTING/QUOTE 两侧各读自己的结构，绝不串源。
  const rowKeyFieldsByComp = useMemo(() => {
    const m = new Map<string, string[]>();
    ((isCosting ? costingCardStructure : quoteCardStructure)?.tabs ?? []).forEach(t => { if (t.componentId) m.set(t.componentId, t.rowKeyFields ?? []); });
    return m;
  }, [isCosting, costingCardStructure, quoteCardStructure]);
  const snapExpansions = useMemo(
    () => (useSnap ? buildSnapshotExpansions(lineItemsForDriver as any, isCosting ? 'COSTING' : 'QUOTE', customerId, rowKeyFieldsByComp) : {}),
    [useSnap, lineItemsForDriver, isCosting, customerId, rowKeyFieldsByComp],
  );
  const driverExpansions = useMemo(
    () => (useSnap ? snapExpansions : liveExpansions),
    [useSnap, snapExpansions, liveExpansions],
  );
  // 解析本侧值快照(formulaResults 真零计算) + rowKeyFields(对齐后端 rowKey)
  // COSTING/QUOTE 各读自己的 cardValues，绝不串源。
  const sideCardValues = useMemo<CardValues | null>(() => {
    const json = (isCosting ? lineItem.costingCardValues : lineItem.quoteCardValues) as string | undefined;
    if (!json) return null;
    return typeof json === 'string'
      ? tryParseSnapshotJsonLossless<CardValues>(json)
      : (json as CardValues);
  }, [isCosting, lineItem.quoteCardValues, lineItem.costingCardValues]);
  // task-0712 展示修复：后端整单渲染失败会落 __cardValueFailed 哨兵（含 __errorMsg 原文）。
  // 只读面显式提示，不误导为「无组件数据」。
  const cardValueFailed = !!(sideCardValues as any)?.__cardValueFailed;
  const cardValueErrorMsg = (sideCardValues as any)?.__errorMsg as string | undefined;
  const snapFormulaByComp = useMemo(
    () => buildReadonlySnapshotIndex(sideCardValues),
    [sideCardValues],
  );

  // 详情页 LIST_FORMULA 模板加载（与编辑页 useConfigTemplates 同款）
  const configTemplates = useConfigTemplates(lineItemsForDriver as any);

  // 详情页 path/formula cache 预热（让 BASIC_DATA + FORMULA 字段能正确查到全局路径值）
  const pathCacheState = usePathFormulaCache(lineItemsForDriver as any, customerId, globalVariableDefs);

  // 2026-05-19 用户决议 (方案 A): 严格按模板, 不再隐藏空数据 Tab.
  //   模板 publish 时配置的全部 NORMAL 组件都展示, SUBTOTAL 仍单独走"产品小计".
  //   空 Tab 内部表格显示"暂无数据"占位 (走 Ant Design Table 默认 emptyText).
  //   这一改动同时修复 ReadonlyProductCard 与 QuotationStep2 Tab 数量不一致的问题.
  const normalComponents = components
    .filter(c => (c as any)?.componentType === 'NORMAL');
  const activeComp = normalComponents[activeTab];

  // activeTab 越界钳位
  useEffect(() => {
    if (normalComponents.length > 0 && activeTab >= normalComponents.length) {
      setActiveTab(0);
    }
  }, [normalComponents.length, activeTab]);

  // Plan 1b 详情页定位：locateSeq 变化时切到目标 componentId 对应页签（AP-54：用 normalComponents 下标）
  useEffect(() => {
    if (!locateComponentId) return;
    const idx = normalComponents.findIndex((c: any) => c.componentId === locateComponentId);
    if (idx >= 0) setActiveTab(idx);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [locateSeq]);

  // Compute subtotals using buildFormulaCache（支持 prev_row_subtotal 累加公式）
  // compSubtotals 先用空 map 初始化，按 component 顺序逐步填入，供后续组件引用前组件小计。
  const subtotalLineItemId = (lineItem as any).id || (lineItem as any).tempId || '';
  const compSubtotals: DecimalContext = {};
  for (const comp of components) {
    if (!comp.fields) continue;
    // Plan 2-核心：多小计列 —— 取所有 is_subtotal 字段。
    const subtotalFields = comp.fields.filter((f: any) => f.is_subtotal);
    if (subtotalFields.length === 0) {
      compSubtotals[comp.tabName] = '0';
      if (comp.componentCode) compSubtotals[comp.componentCode] = '0';
      continue;
    }
    // 2026-05-31 修复（合计/产品小计 ¥∞）：取该组件 driver 展开（与渲染层同 key），
    // 让 BASIC_DATA 分母字段（成材率等）按行取 driver 值，避免子小计求和爆 Infinity。
    const compDriverKey = driverExpansionKey(
      subtotalLineItemId,
      lineItem.productPartNo || '',
      comp.componentId,
      customerId,
      comp.dataDriverPath,
      fieldsOverrideHash(comp.fields as any[]),
    );
    const compDriverExpansion = driverExpansions[compDriverKey];
    // 使用 buildFormulaCache 支持 prev_row_subtotal 累加 + 行级 driver bdv
    const formulaCaches = buildFormulaCache(
      comp, comp.rows, compSubtotals,
      lineItem.productPartNo, globalVariableDefs,
      compDriverExpansion,
    );
    // Plan 2-核心：逐列求和 + per-column 键 `${code|tabName}#${列名}`，组件级 = 各列之和。
    // AP-50 修复：优先取 formulaCache（FORMULA 字段），回退取 fieldValues（输入型字段），
    // 与 computeTabSubtotalsByColumn (Task1) 和后端 (Task2) 口径一致（三视图对齐）。
    let st = toDecimal('0');
    for (const sf of subtotalFields) {
      const colSum = toCalculationString(sumDecimal(formulaCaches.map(
        ({ formulaCache: fc, fieldValues: fv }) => fc[sf.name] ?? fv[sf.name] ?? '0',
      )));
      compSubtotals[`${comp.tabName}#${sf.name}`] = colSum;
      if (comp.componentCode) compSubtotals[`${comp.componentCode}#${sf.name}`] = colSum;
      st = st.plus(colSum);
    }
    const componentSubtotal = toCalculationString(st);
    compSubtotals[comp.tabName] = componentSubtotal;
    if (comp.componentCode) compSubtotals[comp.componentCode] = componentSubtotal;
  }
  // cross_tab_ref 三视图对齐 (Task 4.3): PASS1（compSubtotals 循环）完成后构建 crossTabRows，
  // 镜像后端 CardSnapshotService PASS2。lookupExpansion 复用与 compSubtotals 循环相同的 key 构造。
  // 必须喂 enrich 后的 `components`（含 fields/componentType/dataDriverPath），不能用 raw
  // `lineItem.componentData`——后端 ComponentDataDTO 不持久化 fields/componentType，
  // buildCrossTabRows 首行按 `c?.fields && c.componentType==='NORMAL'` 过滤会滤掉全部组件，
  // 导致 crossTabRows={} → 所有跨页签(cross_tab_ref)公式列/小计/总计求值为 0（详情页专有回归）。
  const { store: crossTabRows, columnSumsByComp } = buildCrossTabRows(
    components,
    compSubtotals,
    lineItem.productPartNo || undefined,
    (comp) => {
      const k = driverExpansionKey(
        subtotalLineItemId,
        lineItem.productPartNo || '',
        comp.componentId,
        customerId,
        comp.dataDriverPath,
        fieldsOverrideHash(comp.fields as any[]),
      );
      return driverExpansions[k];
    },
    globalVariableDefs,
  );

  // 2026-05-31 修复（产品小计金额不对，¥1032.83）：原 `Object.values(compSubtotals).reduce(+)`
  //   把每个组件按 tabName + componentCode 双键存的小计、以及「产品小计」SUBTOTAL 组件自身
  //   全部无差别相加 → 重复累加、超额。权威定义（用户确认）= 「产品小计」页签 SUBTOTAL 组件的
  //   公式结果（产品单价 = 元素小计 + 工艺单价）。直接复用编辑页同源的 computeProductSubtotal：
  //   它内部只算 NORMAL 组件小计（带 driver 行级展开）再求 SUBTOTAL 组件公式，与编辑页完全一致。
  //   注意喂 enrich 后的 components（含 fields/dataDriverPath），否则函数内 lookupExpansion 失效。
  // B3: 传 compSubtotals（buildCrossTabRows 回填后，含 cross_tab 列+二阶列正确小计），
  //   消除函数内 PASS1 重算双口径，保证详情页产品小计与渲染行同源。
  const productSubtotal = computeProductSubtotal(
    { ...lineItem, componentData: components } as any,
    driverExpansions,
    customerId,
    compSubtotals,
  );

  // task-0712 只读核价树修复：activeDriverExpansion 提升到组件顶层作用域（与编辑页
  // QuotationStep2.tsx activeDriverExpansion / activeComponentBomTree 同源），
  // 供表头（BOM 系统列）、表体（树布局/单元格）、表尾（占位对齐）三处共用。
  const activeLineItemId = (lineItem as any).id || (lineItem as any).tempId || '';
  const activeDriverKey = activeComp ? driverExpansionKey(
    activeLineItemId,
    lineItem.productPartNo || '',
    activeComp.componentId,
    customerId,
    activeComp.dataDriverPath,
    fieldsOverrideHash(activeComp.fields as any[]),
  ) : undefined;
  const activeDriverExpansion = activeDriverKey ? driverExpansions[activeDriverKey] : undefined;
  // BOM 递归展开 组件级开关：仅当该组件 baseRows 含 spine 系统列(__sys.nodeId) 才走树+系统列；
  // 未勾选(bom_recursive_expand=false)组件后端不发系统列 → 此处 false → 普通表渲染。
  // task-0721 F1（AP-50 同步）：去掉 isCosting 闸门，与编辑页 QuotationStep2.tsx 同款改法 ——
  // 报价侧接后端 B3 后 baseRows 同样带 __sys.nodeId，数据驱动天然生效；接入前恒 false，零变化。
  const activeComponentBomTree =
    !!activeDriverExpansion?.rows?.some((r: any) => r?.__sys?.nodeId !== undefined);
  // task-0713（D2/F2）：非主树页签版本下拉的组件级开关——仅当该组件驱动行的原始 $view 输出
  // 含约定列 view_version（前端只认这一个键名）才出下拉；树页签走上面 bomSys.bomVersion，
  // 两者互斥（树页签恒 !activeComponentBomTree 为 false）。无 view_version 列的组件/全局费率类
  // 不受影响（对齐 api.md §0 "前端只认 view_version 这一个键名"）。
  const activeComponentVersionable = isCosting && !activeComponentBomTree
    && !!activeDriverExpansion?.rows?.some((r: any) => r?.driverRow?.view_version != null);

  return (
    <div className="qt-product-card">
      <div className="qt-card-header">
        <div className="qt-card-header-left">
          <span className="qt-product-name">
            {/* 与 QuotationStep2 编辑卡片对齐：客户视角优先（customerPartName）→ HF 名 → 快照 partNo → 产品 N */}
            {lineItem.customerPartName
              || attrValues['产品名称']
              || attrValues['名称']
              || lineItem.productName
              || lineItem.snapshot?.productPartNo
              || `产品 ${index + 1}`}
          </span>
          {(lineItem.customerProductNo || lineItem.productPartNo || lineItem.snapshot?.productPartNo) && (
            <span className="qt-sku-badge">
              {lineItem.customerProductNo
                ? `客户产品编号: ${lineItem.customerProductNo}`
                : `料号: ${lineItem.productPartNo || lineItem.snapshot?.productPartNo}`}
            </span>
          )}
          {lineItem.productPartNo && lineItem.customerProductNo && (
            // 同时存在客户产品编号与生产料号时，两者并列显示便于审阅人对照
            <span
              className="qt-sku-badge"
              style={{ background: '#e6f4ff', color: '#0958d9', border: '1px solid #91caff' }}
            >
              料号: {lineItem.productPartNo}
            </span>
          )}
          {lineItem.snapshot?.productCategory && (
            <span className="qt-template-badge">{lineItem.snapshot.productCategory}</span>
          )}
        </div>
      </div>

      {/* Product attributes */}
      {Object.keys(attrValues).length > 0 && (
        <div className="qt-product-attrs">
          <div className="qt-attrs-title">产品属性</div>
          <div className="qt-attrs-grid">
            {Object.entries(attrValues).map(([k, v]) => (
              <div key={k} className="qt-attr-field">
                <label className="qt-attr-label">{k}</label>
                <span className="qt-attr-input" style={{ background: '#fafafa', border: '1px solid #e8e8e8', padding: '4px 8px', borderRadius: 4 }}>
                  {v != null ? formatCellValue(v) : '—'}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Component Tabs — 排除 SUBTOTAL */}
      {/* task-0712 展示修复：本侧卡片值命中失败哨兵时，优先显式红字错误占位，
          不落入下方 loading/normalComponents 分支 —— 结构快照（costingCardStructure/quoteCardStructure）
          可能仍加载成功，导致 normalComponents.length > 0 为真，若不在此处拦截会显示空数据的组件页签，
          误导为「无组件数据」而非「后端渲染失败」（AP-50）。 */}
      {cardValueFailed ? (
        <div className="qt-no-component-data" style={{ color: '#cf1322' }}>
          {isCosting ? '核价' : '报价'}数据生成失败{cardValueErrorMsg ? `：${cardValueErrorMsg}` : ''}
        </div>
      ) : loading ? (
        <div style={{ padding: 24, textAlign: 'center', color: '#999' }}>加载组件结构...</div>
      ) : normalComponents.length > 0 ? (
        <div className="qt-tab-section">
          <div className="qt-tab-header">
            {normalComponents.map((comp, ci) => (
              <button
                key={ci}
                className={`qt-tab-btn${activeTab === ci ? ' active' : ''}`}
                type="button"
                onClick={() => setActiveTab(ci)}
              >
                {comp.tabName}
              </button>
            ))}
          </div>

          {activeComp && activeComp.fields && (
            <div>
              <div className="qt-cost-table-wrap">
              <table className="qt-cost-table">
                <thead>
                  <tr>
                    {/* 核价 BOM 递归展开：固定列仅"勾选递归"组件出（数据驱动 activeComponentBomTree），
                        与编辑页 QuotationStep2.tsx 表头列一致：料号 + 版本。 */}
                    {activeComponentBomTree && (
                      <>
                        {/* repair-071501（Bug1 版本列过宽）：系统列必须给显式 width，否则在
                            table-layout:auto + width:100% 下会独吞全部剩余水平空间被撑爆
                            （字段列都有显式 width，无 width 列成唯一 slack 吸收者）。 */}
                        <th style={{ width: 130, minWidth: 120 }}>料号</th>
                        <th style={{ width: 100, minWidth: 90 }}>版本</th>
                      </>
                    )}
                    {/* task-0713（F2）：非树页签版本系统列，仅该组件驱动行含 view_version 时出现 */}
                    {activeComponentVersionable && (
                      <th style={{ width: 100, minWidth: 90 }}>版本</th>
                    )}
                    {activeComp.fields.map(field => {
                      const w = resolveFieldWidth(field.width);
                      return (
                        <th key={field.name} style={{ width: w, minWidth: w }}>
                          {field.label || field.name}
                        </th>
                      );
                    })}
                  </tr>
                </thead>
                <tbody>
                  {(() => {
                    // Bug C 续 (2026-05-20): 与编辑页 ProductCard 对齐 —— 用 driver 行数限制渲染。
                    // task-0712: activeDriverExpansion 已提升至组件顶层作用域（表头/表体/表尾共用），此处直接复用外层闭包变量。
                    const s = splitRows(activeComp, activeDriverExpansion as any);
                    const useDriver = s.useDriver;
                    const driverCount = s.driverCount;
                    const effectiveCount = s.totalRows;

                    // 风险 1 缓解（架构师决议）：详情页也按行预计算 formulaCache，
                    // 支持 prev_row_subtotal 累加公式（与编辑页 preComputedCaches 同款逻辑）。
                    // 每行的 basicDataValues 取 driver expansion 对应行的数据（如有）。
                    // Phase4 Task4: 本组件快照 formula 映射 + rowKeyFields(报价侧 AP-50)
                    const activeSnap = useSnap ? snapFormulaByComp.get(activeComp.componentId) : undefined;
                    const activeRowKeyFields = rowKeyFieldsByComp.get(activeComp.componentId);
                    const preComputedCaches: Array<Record<string, DecimalString | null>> = [];
                    // 错误旁路(AP-50: 详情页与编辑页口径对齐) — cross_tab_ref 细项多命中等场景,
                    // 数值已静默归 0; 详情页同样显示 ⚠ 而非误导的 0。
                    const preComputedErrors: Array<Record<string, string>> = [];
                    {
                      // task-0803 Task 7：BOM 树页签分流。AP-50：与编辑页 QuotationStep2 共用同一个
                      // computeTabFormulasTree 入口（不各写一份）；命中 snapFormula(快照真零计算)仍优先。
                      const useTreeActive = activeComponentBomTree && usesTreeTokensTab(activeComp);
                      const treeResultsActive = useTreeActive
                        ? computeTabFormulasTree(
                            activeComp,
                            Array.from({ length: effectiveCount }, (_, ri) => {
                              const ra = rowAt(ri, activeComp, s);
                              const sys = ra.expIndex >= 0 ? (activeDriverExpansion!.rows[ra.expIndex] as any)?.__sys : undefined;
                              return {
                                row: ra.row,
                                basicDataValues: ra.expIndex >= 0 ? activeDriverExpansion!.rows[ra.expIndex]?.basicDataValues : undefined,
                                nodeId: sys?.nodeId, parentId: sys?.parentId, lvl: sys?.lvl,
                              };
                            }),
                            compSubtotals, undefined, undefined, lineItem.productPartNo,
                            globalVariableDefs, crossTabRows,
                          )
                        : undefined;
                      // Plan 2b：上一行全量公式值，previous_row_subtotal 按本列取。
                      let prevRowValues: Record<string, DecimalString | null> | undefined = undefined;
                      // 撞键消歧：详情/核价侧也按组件成批算唯一 rowKey（与编辑页 + 后端一致）。
                      // repair-0727 F0：QUOTE 侧（!isCosting）树行加 nodeId 前缀，对齐后端 B0
                      // buildRawRowKeys；roLegacyUniqRowKeys 并行算旧口径（无前缀），供下方 snapFormula
                      // 查表未命中新键时回退，兼容改造前写入的存量单据。COSTING 侧两者逐字节相同。
                      const roUniqRowKeyTuples = useSnap
                        ? Array.from({ length: effectiveCount }, (_, ri) => {
                            const ra = rowAt(ri, activeComp, s);
                            const assembled = assembleReadonlySnapshotRow({
                              persistedRow: ra.row,
                              rowIndex: ri,
                              expIndex: ra.expIndex,
                              expandedRows: activeDriverExpansion?.rows,
                              snapshotDriverRows: activeSnap?.driverRows,
                              snapshotBasicDataRows: activeSnap?.basicDataRows,
                            });
                            const drv = assembled.driverRow;
                            const bdv = assembled.basicDataValues;
                            const nodeId = ra.expIndex >= 0 ? (activeDriverExpansion!.rows[ra.expIndex] as any)?.__sys?.nodeId : undefined;
                            return { driverRow: drv, basicDataValues: bdv, __nodeId: nodeId };
                          })
                        : [];
                      const roUniqRowKeys = useSnap
                        ? buildUniqueRowKeys(activeComp.fields, activeRowKeyFields, roUniqRowKeyTuples, !isCosting)
                        : [];
                      // repair-0805 F6：两档历史口径键（无前缀旧解析 / 带前缀旧解析），查表未命中新键时依次回退。
                      const roLegacyRowKeySets = useSnap
                        ? buildLegacyRowKeySets(activeComp.fields, activeRowKeyFields, roUniqRowKeyTuples, !isCosting)
                        : { legacyPrefixed: [] as string[], legacyNoPrefix: [] as string[] };
                      for (let ri = 0; ri < effectiveCount; ri++) {
                        const ra = rowAt(ri, activeComp, s);
                        const { rawRow, basicDataValues: rowBdv } = assembleReadonlySnapshotRow({
                          persistedRow: ra.row,
                          rowIndex: ri,
                          expIndex: ra.expIndex,
                          expandedRows: activeDriverExpansion?.rows,
                          snapshotDriverRows: activeSnap?.driverRows,
                          snapshotBasicDataRows: activeSnap?.basicDataRows,
                        });
                        // Phase4 Task4: 优先读快照 formulaResults[rowKey](真零计算, 与编辑页 AP-50 同源), 缺时 computeAllFormulas 兜底。
                        const rowKey = useSnap ? (roUniqRowKeys[ri] ?? String(ri)) : String(ri);
                        // F0 + F6：新键未命中时按同一行位置的两档历史口径键依次回退（存量单据兼容）。
                        const legacyRowKeyPrefixed = useSnap ? (roLegacyRowKeySets.legacyPrefixed[ri] ?? String(ri)) : String(ri);
                        const legacyRowKey = useSnap ? (roLegacyRowKeySets.legacyNoPrefix[ri] ?? String(ri)) : String(ri);
                        // task-0806 FR-1（阶段①，AP-50 与 QuotationStep2.tsx 同款分流）：DRAFT 恒
                        // undefined → 落到 treeResultsActive/computeAllFormulas 前端引擎（AC-1）；
                        // 非 DRAFT 行为不变（AC-2）。只加到这一处『读』分支，不动 useSnap 本身
                        // （原因见组件顶部 isDraft 声明处注释）。
                        const snapFormula = (useSnap && !isDraft)
                          ? getByKeyWithLegacyFallback(activeSnap?.formula, rowKey, legacyRowKeyPrefixed, legacyRowKey)
                          : undefined;
                        const errForRow: Record<string, string> = {};
                        const cache: Record<string, DecimalString | null> = (snapFormula && Object.keys(snapFormula).length > 0)
                          ? (snapFormula as Record<string, DecimalString | null>)
                          : treeResultsActive
                          ? (treeResultsActive[ri] ?? {})
                          : computeAllFormulas(
                              activeComp, rawRow, compSubtotals,
                              undefined, undefined, lineItem.productPartNo,
                              rowBdv, undefined, globalVariableDefs, crossTabRows, prevRowValues,
                              { errors: errForRow },
                            );
                        preComputedCaches.push(cache);
                        preComputedErrors.push(errForRow);
                        prevRowValues = cache;
                      }
                    }

                    return (
                      <>
                        {/* 2026-05-19 (方案 A): 模板配了组件但当前料号未匹配数据 → 显示 "暂无数据" 占位行 */}
                        {effectiveCount === 0 && (
                          <tr>
                            <td
                              colSpan={(activeComp.fields.length || 1) + (activeComponentBomTree ? 2 : 0) + (activeComponentVersionable ? 1 : 0)}
                              style={{ textAlign: 'center', color: '#999', padding: '16px 0' }}
                            >
                              暂无数据
                            </td>
                          </tr>
                        )}
                        {(() => {
                          const descriptors = Array.from({ length: effectiveCount }, (_, ri) => {
                            const ra = rowAt(ri, activeComp, s);
                            // task-0713（F2）：非树页签版本/料号——取本行驱动展开的原始 $view 行
                            // （driverRow，与 __sys 同一来源，早于 __ 前缀重打包），只在
                            // activeComponentVersionable 时有意义，其余场景恒 undefined、零影响。
                            const assembled = assembleReadonlySnapshotRow({
                              persistedRow: ra.row,
                              rowIndex: ri,
                              expIndex: ra.expIndex,
                              expandedRows: activeDriverExpansion?.rows,
                              snapshotDriverRows: activeSnap?.driverRows,
                              snapshotBasicDataRows: activeSnap?.basicDataRows,
                            });
                            const driverRowRaw = assembled.driverRow;
                            const rowBdv = assembled.basicDataValues;
                            return {
                              ri,
                              rawRow: materializeReadonlySnapshotInputs(activeComp.fields, assembled.rawRow, rowBdv),
                              // task-0729 联调修复（2026-08-02）：priceLocked/priceVersion 标记挂在
                              // driverRow 上（与 __viewVersion/__rowPartNo 同源），需透传给下游 ordered.map
                              // 消费；之前只在本闭包内部用 driverRowRaw 算 __viewVersion/__rowPartNo 就丢弃了，
                              // 未传出去导致下方 priceLocked 只能误读 rawRow（=row_data，不携带该标记）。
                              driverRow: driverRowRaw,
                              rowBdv,
                              formulaCache: preComputedCaches[ri] ?? {},
                              formulaErrors: preComputedErrors[ri] ?? {},
                              // 核价 BOM 递归展开（task-0712）：spine 系统列（仅 COSTING 行有值），
                              // 与编辑页 QuotationStep2.tsx effectiveRows.__sys 同源。
                              __sys: ra.expIndex >= 0 ? (activeDriverExpansion!.rows[ra.expIndex] as any)?.__sys : undefined,
                              __viewVersion: driverRowRaw?.view_version ?? null,
                              // ⚠️ 集成待确认：api.md 未定死非树行的"料号"列约定键名（后端各 $view 现状不一，
                              // 见 task-0713 前端调查）。按 task-0708 语义（销售料号=material_no）优先取
                              // material_no，其余为兜底候选，后端落地 B4 后需与实际列名核对。
                              __rowPartNo: driverRowRaw?.material_no ?? driverRowRaw?.hf_part_no
                                ?? driverRowRaw?.sales_part_no ?? driverRowRaw?.partNo ?? null,
                            };
                          });
                          // 核价 BOM 递归展开（task-0712）：COSTING 侧按 spine 系统列 __parentId→__nodeId 建树
                          // （不是料号），优先于 treeConfig（对齐编辑页 QuotationStep2.tsx isBomTree 分支）。
                          // 归一化：根 nodeId='' → '__bomroot__'；根直接子 parentId='' → '__bomroot__'；根自身 parentId=null → null。
                          const isBomTree = activeComponentBomTree
                            && descriptors.some(d => (d as any).__sys?.nodeId !== undefined);
                          const treeCfg = activeComp.treeConfig;
                          let ordered = descriptors.map(d => ({ ...d, _depth: 0, _hasChildren: false, _nodeKey: '' }));
                          if (isBomTree) {
                            const normId = (v: any) => (v === '' || v == null) ? '__bomroot__' : String(v);
                            const keyPrefixBom = activeComp.componentId || activeComp.tabName || 'bomtree';
                            const laidBom = layoutTreeRows(
                              descriptors,
                              (it) => { const sys = (it as any).__sys; return sys?.nodeId === undefined ? null : normId(sys.nodeId); },
                              (it) => { const sys = (it as any).__sys; return (sys?.parentId == null) ? null : (sys.parentId === '' ? '__bomroot__' : String(sys.parentId)); },
                              keyPrefixBom,
                            );
                            const collapsedBom = treeCollapse.collapsedSet(Object.values(laidBom.nodeKeyByIndex), true);
                            ordered = laidBom.rows
                              .filter(r => !isTreeRowHidden(r.originalIndex, laidBom.parentIndexByIndex, laidBom.nodeKeyByIndex, collapsedBom))
                              .map(r => ({ ...r.item, _depth: r.depth, _hasChildren: r.hasChildren, _nodeKey: r.nodeKey }));
                          } else if (treeCfg?.idField && treeCfg?.parentField) {
                            const idFieldDef = activeComp.fields.find(f => (f.name || (f as any).key) === treeCfg.idField);
                            const parentFieldDef = activeComp.fields.find(f => (f.name || (f as any).key) === treeCfg.parentField);
                            const keyPrefix = activeComp.componentId || activeComp.tabName || 'tree';
                            const laid = layoutTreeRows(
                              descriptors,
                              (it) => idFieldDef ? resolveTreeKey(idFieldDef, it.rawRow, it.rowBdv, bnfDriverLookupKey) : null,
                              (it) => parentFieldDef ? resolveTreeKey(parentFieldDef, it.rawRow, it.rowBdv, bnfDriverLookupKey) : null,
                              keyPrefix,
                            );
                            const defExp = treeCfg.defaultExpanded ?? true;
                            const collapsed = treeCollapse.collapsedSet(Object.values(laid.nodeKeyByIndex), defExp);
                            ordered = laid.rows
                              .filter(r => !isTreeRowHidden(r.originalIndex, laid.parentIndexByIndex, laid.nodeKeyByIndex, collapsed))
                              .map(r => ({ ...r.item, _depth: r.depth, _hasChildren: r.hasChildren, _nodeKey: r.nodeKey }));
                          }
                          // task-0713（F2 非树页签）：按销售料号做"相邻行分组"，组内首行 rowSpan 覆盖
                          // 整组、共享一个下拉；同组其余行不渲染该 <td>（标准 HTML rowSpan 用法）。
                          // 仅 activeComponentVersionable 时计算，其余场景数组恒为空、零开销。
                          const versionGroupInfo: Array<{ isGroupStart: boolean; rowSpan: number }> = [];
                          if (activeComponentVersionable) {
                            for (let oi = 0; oi < ordered.length; oi++) {
                              const cur = (ordered[oi] as any).__rowPartNo;
                              const prev = oi > 0 ? (ordered[oi - 1] as any).__rowPartNo : undefined;
                              if (oi === 0 || cur !== prev) {
                                let span = 1;
                                while (oi + span < ordered.length && (ordered[oi + span] as any).__rowPartNo === cur) span++;
                                versionGroupInfo[oi] = { isGroupStart: true, rowSpan: span };
                              } else {
                                versionGroupInfo[oi] = { isGroupStart: false, rowSpan: 0 };
                              }
                            }
                          }
                          return ordered.map(({ ri, rawRow, driverRow, rowBdv, formulaCache, formulaErrors, _depth, _hasChildren, _nodeKey, __sys, __viewVersion, __rowPartNo }, oi) => {
                          const bomSys = activeComponentBomTree ? (__sys as import('./useDriverExpansions').BomSysCols | undefined) : undefined;
                          // task-0713（F2/F4）：树页签版本切换 —— 仅 COSTING + 有 coid + 该节点有料号时可下拉；
                          // editable=false 时纯文本只读展示（不显示交互控件，同下方非树分支同款判断）。
                          const canSwitchTreeVersion = isCosting && !!coid && !!bomSys?.hfPartNo;
                          return (
                          <tr key={ri}>
                            {/* 核价 BOM 递归展开（task-0712，只读版）：2 系统固定列，料号列承载树缩进/折叠箭头；
                                只读页无编辑交互，版本列直接文本展示（不用编辑页的 disabled <select>）。 */}
                            {activeComponentBomTree && (
                              <>
                                <td style={bomSys?.isCycle ? { color: '#cf1322' } : undefined}
                                    title={bomSys?.isCycle ? '该料号存在 BOM 环，已截断展开' : undefined}>
                                  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                                    <span style={{ display: 'inline-block', width: (_depth ?? 0) * 16 }} />
                                    {_hasChildren ? (
                                      <button type="button" onClick={() => treeCollapse.toggle(_nodeKey)}
                                        style={{ border: 'none', background: 'none', cursor: 'pointer', fontSize: 10, width: 14, padding: 0, color: '#888' }} title="展开/折叠">
                                        {treeCollapse.isCollapsed(_nodeKey, true) ? '▶' : '▼'}
                                      </button>
                                    ) : (<span style={{ display: 'inline-block', width: 14 }} />)}
                                    <span>{bomSys?.hfPartNo ?? '—'}</span>
                                  </span>
                                </td>
                                <td style={{ width: 100 }}>
                                  {/* task-0713（F2/F3/F4）：主树版本切换——editable 时下拉，否则纯文本 */}
                                  {canSwitchTreeVersion && editable ? (
                                    <VersionSelectDropdown
                                      coid={coid!}
                                      lineItemId={activeLineItemId}
                                      componentId={activeComp.componentId}
                                      partNo={bomSys!.hfPartNo!}
                                      currentVersion={bomSys?.bomVersion ?? null}
                                      onSwitched={onVersionSwitched}
                                    />
                                  ) : (
                                    bomSys?.bomVersion ?? '—'
                                  )}
                                </td>
                              </>
                            )}
                            {/* task-0713（F2 非树页签）：组内首行渲染共享下拉，rowSpan 覆盖整组；组内其余行不渲染此列 */}
                            {activeComponentVersionable && versionGroupInfo[oi]?.isGroupStart && (
                              <td rowSpan={versionGroupInfo[oi].rowSpan} style={{ verticalAlign: 'top', width: 100 }}>
                                {isCosting && !!coid && __rowPartNo != null && editable ? (
                                  <VersionSelectDropdown
                                    coid={coid}
                                    lineItemId={activeLineItemId}
                                    componentId={activeComp.componentId}
                                    partNo={String(__rowPartNo)}
                                    currentVersion={__viewVersion != null ? String(__viewVersion) : null}
                                    onSwitched={onVersionSwitched}
                                  />
                                ) : (
                                  __viewVersion != null ? String(__viewVersion) : '—'
                                )}
                              </td>
                            )}
                            {activeComp.fields.map((field) => {
                              const key = field.name || '';
                              const showTrace = !!(quotationId && isTraceField(field));
                              // isDraft 已提升至组件顶层（task-0806 FR-1 复用同一份判定，见顶部声明处）。
                              const compIndex = components.indexOf(activeComp);
                              const fieldPath = `lineItems[${index}].componentData[${compIndex}].rowData.${key}`;
                              const cellCtx: CellContext = {
                                basicDataValues: rowBdv,
                                pathCacheState: pathCacheState,
                                formulaCache,
                                formulaErrors,
                                partNo: lineItem.productPartNo,
                                activeComponent: activeComp,
                                activeDriverExpansion,
                                isListFormulaBound: false,
                                isDriverBound: useDriver && ri < driverCount,
                                configTemplates,
                                globalVariableDefs,
                                // 核价 BOM 树行：BASIC_DATA 缺值直接 "—"，不按根料号走 globalPathCache
                                // （对齐编辑页 QuotationStep2.tsx cellCtx.isBomTreeRow，防子料号行误显根值/"加载中"）。
                                isBomTreeRow: !!bomSys,
                                // task-0729 跨屏·元素单价列只读态（AP-50：编辑页/详情页两处渲染同步，
                                // 与编辑页 QuotationStep2.tsx 对齐同一读取口径）。🚨 R1：详情页本来就
                                // readonly=true，priceLocked 判定不受 readonly 影响，这里必须照样透传，
                                // 不能因为"反正详情页已经只读了"就省略——版本徽标只有这里才会挂上去。
                                // 🚨 标记有【两个落点，两个都要读】（2026-08-04 验收返修，与编辑页
                                // QuotationStep2.tsx 逐字同款）：driver 行在 driverRow（quoteCardValues
                                // 快照 baseRows[].driverRow，与 _元素/_料号 系统列同级）；手动行在 rawRow
                                // （= row_data，PriceReconciler#reconcileRows 的第二个循环按"元素∈调价
                                // 清单"写入）。手动行 rowAt().expIndex 恒 -1 → driverRow 恒 undefined，
                                // 只读 driverRow 会让手动行永远锁不住（2026-08-02 那版"不影响非驱动行"
                                // 的判断已被实测证伪）。故 driverRow 优先、rawRow 兜底。
                                priceLocked: !!((driverRow as any)?.__priceLocked ?? (rawRow as any)?.__priceLocked),
                                priceVersionNo: (driverRow as any)?.__priceVersion ?? (rawRow as any)?.__priceVersion,
                              };
                              const isFirstField = activeComp.fields[0] === field;
                              // BOM 树激活时缩进已移到系统「料号」列，字段首列不再重复缩进（避免双重缩进）。
                              const treeOn = !activeComponentBomTree
                                && !!(activeComp.treeConfig?.idField && activeComp.treeConfig?.parentField);
                              const cellInner = (
                                <span style={showTrace ? { display: 'inline-flex', alignItems: 'center', gap: 2 } : undefined}>
                                  <ComponentCell
                                    field={field}
                                    row={rawRow}
                                    rowIndex={ri}
                                    fieldKey={key}
                                    readonly={true}
                                    context={cellCtx}
                                  />
                                  {showTrace && (
                                    <FieldTraceIcon
                                      quotationId={quotationId!}
                                      fieldPath={fieldPath}
                                      isDraft={isDraft}
                                    />
                                  )}
                                </span>
                              );
                              return (
                                <td
                                  key={key}
                                  className={[
                                    field.field_type === 'FORMULA' ? 'qt-formula-cell' : '',
                                    field.field_type === 'LIST_FORMULA' ? 'qt-formula-cell' : '',
                                  ].filter(Boolean).join(' ') || undefined}
                                >
                                  {isFirstField && treeOn ? (
                                    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                                      <span style={{ display: 'inline-block', width: (_depth ?? 0) * 16 }} />
                                      {_hasChildren ? (
                                        <button
                                          type="button"
                                          onClick={() => treeCollapse.toggle(_nodeKey)}
                                          style={{ border: 'none', background: 'none', cursor: 'pointer', fontSize: 10, width: 14, padding: 0, color: '#888' }}
                                          title="展开/折叠"
                                        >
                                          {treeCollapse.isCollapsed(_nodeKey, activeComp.treeConfig!.defaultExpanded ?? true) ? '▶' : '▼'}
                                        </button>
                                      ) : (
                                        <span style={{ display: 'inline-block', width: 14 }} />
                                      )}
                                      {cellInner}
                                    </span>
                                  ) : cellInner}
                                </td>
                              );
                            })}
                          </tr>
                          );
                          });
                        })()}
                      </>
                    );
                  })()}
                </tbody>
                {/* Tab subtotal footer（对齐编辑页 QuotationStep2）
                    显示门槛：有任意 is_subtotal 列才显示 footer。
                    小计行：只对 is_subtotal 列求和（读 columnSumsByComp 单一来源）；非小计列一律留空。
                           is_amount=true 显示 ¥ + 通用精度；否则纯数字（最多4位小数，去末尾0）。
                    本页签金额合计行：只汇总金额列(is_amount&&is_subtotal)，无金额列整行隐藏。
                */}
                {activeComp.fields.some(f => f.is_subtotal) && (
                  <tfoot>
                    <tr className="qt-subtotal-row">
                      {/* 核价 BOM 递归展开：与 2 个系统固定列对齐的占位单元格（仅"勾选递归"组件） */}
                      {activeComponentBomTree && (<><td /><td /></>)}
                      {/* task-0713：与非树版本系统列对齐的占位单元格 */}
                      {activeComponentVersionable && <td />}
                      {activeComp.fields.map((field, fi) => {
                        const colName = field.name || field.key || '';
                        // 单一来源：columnSumsByComp（buildCrossTabRows resolvedRows Σ行）
                        const compKey = activeComp.componentId || activeComp.componentCode || activeComp.tabName;
                        const colSums = (columnSumsByComp && compKey) ? (columnSumsByComp[compKey] ?? {}) : {};
                        // C1：小计行只对勾选了 is_subtotal 的列求和；非小计列一律留空。
                        const isNumericCol = !!field.is_subtotal;
                        if (isNumericCol && colName && colName in colSums) {
                          const v = colSums[colName] ?? '0';
                          // ¥ 仅当 is_amount===true；其他数值列（含管理费/利润等 is_subtotal 但非金额列）纯数字
                          // C2：金额列 = ¥ + 通用精度（task-0801：改走 formatNumber DISPLAY_SCALE=6
                          // 去尾零兜底，与编辑页 QuotationStep2 同款同口径，AP-50）
                          const plain = toDecimal(v).isZero() ? '0' : (formatNumber(v, { isComputed: true }) ?? '0');
                          const text = field.is_amount === true ? `¥ ${plain}` : plain;
                          return (
                            <td key={colName || fi} className="qt-subtotal-cell" style={field.is_amount === true ? undefined : { color: '#595959' }}>
                              {text}
                            </td>
                          );
                        }
                        if (fi === 0) {
                          return <td key={colName || fi} className="qt-subtotal-label-cell">小计</td>;
                        }
                        return <td key={colName || fi} />;
                      })}
                    </tr>
                    {/* 本页签金额合计 = 该页签所有金额列(is_amount&&is_subtotal)之和；无金额列整行隐藏 */}
                    {activeComp.fields.some(f => f.is_amount) && (
                      <tr className="qt-subtotal-row qt-tab-total-row">
                        {activeComponentBomTree && (<><td /><td /></>)}
                        {activeComponentVersionable && <td />}
                        <td className="qt-subtotal-label-cell">合计</td>
                        <td colSpan={Math.max(1, activeComp.fields.length - 1)} className="qt-subtotal-cell" style={{ textAlign: 'right' }}>
                          {/* task-0801：全口径统一 6 位去尾零（formatNumber 兜底），产品小计/页签合计不再分叉 2 位 vs 4 位 */}
                          {`¥ ${formatNumber(sumTabColumns(activeComp as any, compSubtotals), { isComputed: true }) ?? '0'}`}
                        </td>
                      </tr>
                    )}
                  </tfoot>
                )}
              </table>
              </div>
            </div>
          )}
        </div>
      ) : (
        <div className="qt-no-component-data">暂无组件数据</div>
      )}

      {/* 卡片底部只保留「产品小计」；各页签小计在各自页签内（本页签金额合计行）。 */}
      <div className="qt-subtotal-bar">
        <span className="qt-subtotal-label">产品小计</span>
        <span className="qt-subtotal-value">
          {formatCurrency(productSubtotal ?? (isCosting ? '0' : (lineItem.subtotal ?? '0')))}
        </span>
      </div>
    </div>
  );
};

export default ReadonlyProductCard;
