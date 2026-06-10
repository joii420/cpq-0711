import React, { useState, useEffect, useMemo } from 'react';
import { useAuthStore } from '../../stores/authStore';
import type { ComponentDataItem, ComponentField } from './QuotationStep2';
import { computeAllFormulas, computeProductSubtotal, buildSnapshotExpansions, buildCrossTabRows, EMPTY_LINEITEMS } from './QuotationStep2';
import { enrichComponentData } from './enrichComponentData';
import { useDriverExpansions, driverExpansionKey, fieldsOverrideHash, bnfDriverLookupKey } from './useDriverExpansions';
import { layoutTreeRows, isTreeRowHidden, resolveTreeKey } from './treeTable';
import { splitRows, rowAt } from './manualRows';
import { useTreeCollapse } from './useTreeCollapse';
import { computeRowKey } from './useCardSnapshots';
import type { CardStructure, CardValues } from '../../services/quotationService';
import { useConfigTemplates } from './useConfigTemplates';
import { usePathFormulaCache } from './usePathFormulaCache';
import FieldTraceIcon from './components/FieldTraceIcon';
import ComponentCell from './components/ComponentCell';
import type { CellContext } from './components/ComponentCell';
import type { GlobalVariableDefinition } from '../../services/globalVariableService';
import { sumTabColumns } from './tabTotalLines';
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
}

function parseJson<T>(value: T | string | null | undefined, fallback: T): T {
  if (value == null) return fallback;
  if (typeof value === 'string') {
    try { return JSON.parse(value) as T; } catch { return fallback; }
  }
  return value;
}

/**
 * 按行预计算所有 FORMULA 字段值，支持 prev_row_subtotal 累加（与 QuotationStep2 同源）。
 * 返回 { row: 0, 1, ... } → formulaCache Map（按 rowIndex 索引）。
 */
function buildFormulaCache(
  comp: ComponentDataItem,
  rows: Record<string, any>[],
  compSubtotals: Record<string, number>,
  partNo?: string,
  globalVariableDefs?: Record<string, GlobalVariableDefinition>,
  // 2026-05-31 修复（小计合计/产品小计 ¥∞）：必须按行喂 driver 展开的 basicDataValues，
  // 否则 BASIC_DATA 分母字段（如 成材率）取不到值 → ?? 0 → 工序单价=单价÷0=Infinity →
  // 子小计求和 = ∞。与渲染层 preComputedCaches 同款（按 driver 行数 + 行级 bdv）。
  driverExpansion?: { rowCount: number; rows: Array<{ basicDataValues?: Record<string, any> }> },
  // cross_tab_ref 三视图对齐 (Task 4.3): PASS1 小计循环不传（undefined），
  // 仅渲染层 PASS2 才传 crossTabRows，镜像后端两阶段。
  crossTabRows?: Record<string, Array<Record<string, any>>>,
): Array<Record<string, number | null>> {
  const useDriver = !!(driverExpansion && driverExpansion.rowCount > 0);
  // AP-51 行数纪律：driver 权威优先，仅 rowCount=0 时退回持久化行数。
  const effectiveCount = useDriver ? driverExpansion!.rowCount : rows.length;
  const caches: Array<Record<string, number | null>> = [];
  // Plan 2b：上一行全量公式值，previous_row_subtotal 按本列取。
  let prevRowValues: Record<string, number | null> | undefined = undefined;
  for (let ri = 0; ri < effectiveCount; ri++) {
    const row = rows[ri] ?? {};
    const bdv = useDriver ? driverExpansion!.rows[ri]?.basicDataValues : undefined;
    const cache = computeAllFormulas(
      comp, row, compSubtotals,
      undefined, undefined, partNo, bdv,
      undefined, globalVariableDefs, crossTabRows, prevRowValues,
    );
    caches.push(cache);
    prevRowValues = cache;
  }
  return caches;
}

const formatCurrency = (val: number) =>
  `¥ ${(val || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

/** 单元格值格式化 — V197 同 QuotationStep2.formatPathValue 同款逻辑, 支持 JSONB 包装对象 */
const formatCellValue = (v: any): string => {
  if (v == null || v === '') return '—';
  if (typeof v === 'boolean') return v ? '是' : '否';
  if (typeof v === 'number' || typeof v === 'string') return String(v);
  // V197: PG JDBC jsonb 列读成 PGobject {type:'jsonb', value:'<json>'}, 单 cell 完整展开
  if (typeof v === 'object') {
    if (v.type === 'jsonb' && typeof v.value === 'string') {
      try {
        const parsed = JSON.parse(v.value);
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
}) => {
  const [activeTab, setActiveTab] = useState(0);
  const [components, setComponents] = useState<ComponentDataItem[]>([]);
  const [loading, setLoading] = useState(true);
  const { user } = useAuthStore();
  const treeCollapse = useTreeCollapse();

  const attrValues: Record<string, any> = parseJson(lineItem.productAttributeValues, {});

  // 料号版本 Tag 显示规则 (新需求):
  // - 草稿态 (DRAFT): 不显示 (草稿走 QuotationStep2 编辑视图, 那里已显示并可切换)
  // - 已提交态 (非 DRAFT): 仅销售经理(SALES_MANAGER) + 系统管理员(SYSTEM_ADMIN) 可见, 不可修改
  // - 其他角色 (SALES_REP / PRICING_MANAGER 等普通用户): 不显示
  const canSeeVersionTag = quotationStatus !== 'DRAFT'
      && (user?.role === 'SALES_MANAGER' || user?.role === 'SYSTEM_ADMIN');

  // Enrich componentData with fields/formulas from template snapshot.
  // Bug C (2026-05-20): 改调共享的 enrichComponentData，与 QuotationWizard 完全同源。
  // 保证详情页 = 编辑页只读版，4 个关键字段 (datasource_binding / global_variable_code /
  // default_source / sort_order) 在详情页也被透传，Tab 列表与编辑页对齐。
  useEffect(() => {
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
  }, [lineItem]);

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
  //   有 quoteCardValues 时: 不再 batch-expand(传 EMPTY_LINEITEMS), 改从行级值快照构造 driverExpansions
  //   (BASIC_DATA + 行数来自快照 baseRows); FORMULA 优先读 formulaResults[rowKey](真零计算)。
  //   只读页无受控 input, 故安全(不涉 AP-54)。无快照(存量单)回退实时 batch-expand。
  const useSnap = !!lineItem.quoteCardValues && components.length > 0;
  const { cache: liveExpansions } = useDriverExpansions(
    useSnap ? EMPTY_LINEITEMS : (lineItemsForDriver as any), customerId, quotationId);
  const snapExpansions = useMemo(
    () => (useSnap ? buildSnapshotExpansions(lineItemsForDriver as any, 'QUOTE', customerId) : {}),
    [useSnap, lineItemsForDriver, customerId],
  );
  const driverExpansions = useMemo(
    () => (useSnap ? snapExpansions : liveExpansions),
    [useSnap, snapExpansions, liveExpansions],
  );
  // 解析本侧值快照(formulaResults 真零计算) + rowKeyFields(对齐后端 rowKey)
  const sideCardValues = useMemo<CardValues | null>(() => {
    const json = lineItem.quoteCardValues as string | undefined;
    if (!json) return null;
    try { return typeof json === 'string' ? JSON.parse(json) as CardValues : (json as CardValues); } catch { return null; }
  }, [lineItem.quoteCardValues]);
  const rowKeyFieldsByComp = useMemo(() => {
    const m = new Map<string, string[]>();
    (quoteCardStructure?.tabs ?? []).forEach(t => { if (t.componentId) m.set(t.componentId, t.rowKeyFields ?? []); });
    return m;
  }, [quoteCardStructure]);
  const snapFormulaByComp = useMemo(() => {
    const m = new Map<string, { formula: Map<string, Record<string, any>>; driverRows: Record<string, any>[] }>();
    (sideCardValues?.tabs ?? []).forEach(vt => {
      if (!vt.componentId) return;
      const formula = new Map<string, Record<string, any>>();
      (vt.formulaResults ?? []).forEach(r => { if (r?.rowKey != null) formula.set(r.rowKey, r.values ?? {}); });
      const driverRows = (vt.baseRows ?? []).map(b => b?.driverRow ?? {});
      m.set(vt.componentId, { formula, driverRows });
    });
    return m;
  }, [sideCardValues]);

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

  // Compute subtotals using buildFormulaCache（支持 prev_row_subtotal 累加公式）
  // compSubtotals 先用空 map 初始化，按 component 顺序逐步填入，供后续组件引用前组件小计。
  const subtotalLineItemId = (lineItem as any).id || (lineItem as any).tempId || '';
  const compSubtotals: Record<string, number> = {};
  for (const comp of components) {
    if (!comp.fields) continue;
    // Plan 2-核心：多小计列 —— 取所有 is_subtotal 字段。
    const subtotalFields = comp.fields.filter((f: any) => f.is_subtotal);
    if (subtotalFields.length === 0) {
      compSubtotals[comp.tabName] = 0;
      if (comp.componentCode) compSubtotals[comp.componentCode] = 0;
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
    let st = 0;
    for (const sf of subtotalFields) {
      const colSum = formulaCaches.reduce((s, fc) => s + ((fc[sf.name] as number) ?? 0), 0);
      compSubtotals[`${comp.tabName}#${sf.name}`] = colSum;
      if (comp.componentCode) compSubtotals[`${comp.componentCode}#${sf.name}`] = colSum;
      st += colSum;
    }
    compSubtotals[comp.tabName] = st;
    if (comp.componentCode) compSubtotals[comp.componentCode] = st;
  }
  // cross_tab_ref 三视图对齐 (Task 4.3): PASS1（compSubtotals 循环）完成后构建 crossTabRows，
  // 镜像后端 CardSnapshotService PASS2。lookupExpansion 复用与 compSubtotals 循环相同的 key 构造。
  const crossTabRows = buildCrossTabRows(
    lineItem.componentData ?? [],
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
  const productSubtotal = computeProductSubtotal(
    { ...lineItem, componentData: components } as any,
    driverExpansions,
    customerId,
  );

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
              生产料号: {lineItem.productPartNo}
            </span>
          )}
          {/* 料号版本锁定 — 仅已提交报价单且角色为销售经理/系统管理员时显示, 不可修改 */}
          {canSeeVersionTag && lineItem.partVersionLocked != null && (
            <span
              className="qt-sku-badge"
              style={{ background: '#f6ffed', color: '#389e0d', border: '1px solid #b7eb8f' }}
              title="料号版本锁定 — 本行报价数据锁定版本 (不可修改)"
            >
              版本: v{lineItem.partVersionLocked}
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
      {loading ? (
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
              <table className="qt-cost-table">
                <thead>
                  <tr>
                    {activeComp.fields.map(field => (
                      <th key={field.name}>{field.label || field.name}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {(() => {
                    // Bug C 续 (2026-05-20): 与编辑页 ProductCard 对齐 —— 用 driver 行数限制渲染。
                    const lineItemId = (lineItem as any).id || (lineItem as any).tempId || '';
                    const activeDriverKey = driverExpansionKey(
                      lineItemId,
                      lineItem.productPartNo || '',
                      activeComp.componentId,
                      customerId,
                      activeComp.dataDriverPath,
                      fieldsOverrideHash(activeComp.fields as any[]),
                    );
                    const activeDriverExpansion = driverExpansions[activeDriverKey];
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
                    const preComputedCaches: Array<Record<string, number | null>> = [];
                    {
                      // Plan 2b：上一行全量公式值，previous_row_subtotal 按本列取。
                      let prevRowValues: Record<string, number | null> | undefined = undefined;
                      for (let ri = 0; ri < effectiveCount; ri++) {
                        const ra = rowAt(ri, activeComp, s);
                        const rawRow = ra.row;
                        const rowBdv = ra.expIndex >= 0 ? activeDriverExpansion!.rows[ra.expIndex]?.basicDataValues : undefined;
                        // Phase4 Task4: 优先读快照 formulaResults[rowKey](真零计算, 与编辑页 AP-50 同源), 缺时 computeAllFormulas 兜底。
                        const driverRowForKey = (ra.expIndex >= 0 ? activeDriverExpansion!.rows[ra.expIndex]?.driverRow : undefined) ?? activeSnap?.driverRows[ri] ?? rawRow;
                        const rowKey = useSnap ? computeRowKey(activeRowKeyFields, driverRowForKey, ri) : String(ri);
                        const snapFormula = useSnap ? activeSnap?.formula.get(rowKey) : undefined;
                        const cache = (snapFormula && Object.keys(snapFormula).length > 0)
                          ? (snapFormula as Record<string, number | null>)
                          : computeAllFormulas(
                              activeComp, rawRow, compSubtotals,
                              undefined, undefined, lineItem.productPartNo,
                              rowBdv, undefined, globalVariableDefs, crossTabRows, prevRowValues,
                            );
                        preComputedCaches.push(cache);
                        prevRowValues = cache;
                      }
                    }

                    return (
                      <>
                        {/* 2026-05-19 (方案 A): 模板配了组件但当前料号未匹配数据 → 显示 "暂无数据" 占位行 */}
                        {effectiveCount === 0 && (
                          <tr>
                            <td
                              colSpan={activeComp.fields.length || 1}
                              style={{ textAlign: 'center', color: '#999', padding: '16px 0' }}
                            >
                              暂无数据
                            </td>
                          </tr>
                        )}
                        {(() => {
                          const descriptors = Array.from({ length: effectiveCount }, (_, ri) => {
                            const ra = rowAt(ri, activeComp, s);
                            return {
                              ri,
                              rawRow: ra.row,
                              rowBdv: ra.expIndex >= 0 ? activeDriverExpansion!.rows[ra.expIndex]?.basicDataValues : undefined,
                              formulaCache: preComputedCaches[ri] ?? {},
                            };
                          });
                          const treeCfg = activeComp.treeConfig;
                          let ordered = descriptors.map(d => ({ ...d, _depth: 0, _hasChildren: false, _nodeKey: '' }));
                          if (treeCfg?.idField && treeCfg?.parentField) {
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
                          return ordered.map(({ ri, rawRow, rowBdv, formulaCache, _depth, _hasChildren, _nodeKey }) => (
                          <tr key={ri}>
                            {activeComp.fields.map((field) => {
                              const key = field.name || '';
                              const showTrace = !!(quotationId && isTraceField(field));
                              const isDraft = !quotationStatus || quotationStatus === 'DRAFT';
                              const compIndex = components.indexOf(activeComp);
                              const fieldPath = `lineItems[${index}].componentData[${compIndex}].rowData.${key}`;
                              const cellCtx: CellContext = {
                                basicDataValues: rowBdv,
                                pathCacheState: pathCacheState,
                                formulaCache,
                                partNo: lineItem.productPartNo,
                                activeComponent: activeComp,
                                activeDriverExpansion,
                                isListFormulaBound: false,
                                isDriverBound: useDriver && ri < driverCount,
                                configTemplates,
                                globalVariableDefs,
                              };
                              const isFirstField = activeComp.fields[0] === field;
                              const treeOn = !!(activeComp.treeConfig?.idField && activeComp.treeConfig?.parentField);
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
                          ));
                        })()}
                      </>
                    );
                  })()}
                </tbody>
                {activeComp.fields.some(f => f.is_subtotal) && (
                  <tfoot>
                    <tr className="qt-subtotal-row">
                      {activeComp.fields.map((field, fi) => {
                        if (field.is_subtotal) {
                          // Plan 2-核心：按列取本列总计，回退组件级兼容。
                          const v = compSubtotals[`${activeComp.componentCode}#${field.name}`]
                            ?? compSubtotals[`${activeComp.tabName}#${field.name}`]
                            ?? compSubtotals[activeComp.tabName] ?? 0;
                          return (
                            <td key={fi} className="qt-subtotal-cell">
                              {formatCurrency(v)}
                            </td>
                          );
                        }
                        return fi === 0
                          ? <td key={fi} className="qt-subtotal-label-cell">小计</td>
                          : <td key={fi} />;
                      })}
                    </tr>
                    {/* 本页签总计 = 该页签多个小计列之和（页签内展示，与编辑页一致） */}
                    <tr className="qt-subtotal-row qt-tab-total-row">
                      <td className="qt-subtotal-label-cell">本页签总计</td>
                      <td colSpan={Math.max(1, activeComp.fields.length - 1)} className="qt-subtotal-cell" style={{ textAlign: 'right' }}>
                        {formatCurrency(sumTabColumns(activeComp as any, compSubtotals))}
                      </td>
                    </tr>
                  </tfoot>
                )}
              </table>
            </div>
          )}
        </div>
      ) : (
        <div className="qt-no-component-data">��无组件数据</div>
      )}

      {/* 卡片底部只保留「产品小计」；各页签小计在各自页签内（本页签总计行）。 */}
      <div className="qt-subtotal-bar">
        <span className="qt-subtotal-label">产品小计</span>
        <span className="qt-subtotal-value">
          {formatCurrency(productSubtotal || lineItem.subtotal || 0)}
        </span>
      </div>
    </div>
  );
};

export default ReadonlyProductCard;
