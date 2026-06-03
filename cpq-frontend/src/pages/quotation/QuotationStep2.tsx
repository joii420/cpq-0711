import React, { useState, useRef, useEffect, useCallback } from 'react';
import { Popover, Button, Segmented, Alert, Space, message, Dropdown } from 'antd';
import { DatabaseOutlined, SettingOutlined, PlusOutlined, DownOutlined } from '@ant-design/icons';
import { evaluateExpression, getGlobalPathCache, evaluateListFormulaString } from '../../utils/formulaEngine';
import { usePathFormulaCache } from './usePathFormulaCache';
import { globalVariableService, type GlobalVariableDefinition } from '../../services/globalVariableService';
import { useDriverExpansions, driverExpansionKey, bnfDriverLookupKey, fieldsOverrideHash } from './useDriverExpansions';
import { useConfigTemplates, type ConfigTemplateMap } from './useConfigTemplates';
import { evaluateCondition } from '../../utils/conditionEngine';
import type { DriftDetectionResult } from '../../types/quotation-drift';
import { quotationDriftService } from '../../services/quotationDriftService';
import { useAuthStore } from '../../stores/authStore';
import LinkedExcelView from './LinkedExcelView';
import ComparisonView from './ComparisonView';
import { datasourceService } from '../../services/datasourceService';
import { materialMappingService } from '../../services/materialMappingService';
import ElementPriceHint from './components/ElementPriceHint';
import ComponentCell from './components/ComponentCell';
import type { CellContext } from './components/ComponentCell';
import { buildLineItemFromTemplate } from './BulkImportPartsDrawer';
import { quotationService } from '../../services/quotationService';
import type { CardStructure, CardValues } from '../../services/quotationService';
import { computeRowKey } from './useCardSnapshots';
import { partVersionService } from '../../services/partVersionService';
import PartVersionDrawer from '../../components/PartVersionDrawer';
import { templateService } from '../../services/templateService';
import { layoutTreeRows, isTreeRowHidden, resolveTreeKey } from './treeTable';
import { useTreeCollapse } from './useTreeCollapse';
import './quotation.css';

// 与 QuotationWizard / BulkImportPartsDrawer / ReadonlyProductCard 中的同名函数保持完全对齐。
// BASIC_DATA / INPUT_TEXT / INPUT_NUMBER 缺一个就会让对应字段被误归为通用 INPUT，
// 触发渲染分支用 <input> 而不是 BASIC_DATA 的只读 span。
function normalizeFieldType(raw: string):
  'FIXED_VALUE' | 'DATA_SOURCE' | 'INPUT' | 'INPUT_TEXT' | 'INPUT_NUMBER' | 'FORMULA' | 'BASIC_DATA' | 'LIST_FORMULA' {
  const t = (raw || '').toUpperCase();
  if (t === 'FORMULA') return 'FORMULA';
  if (t === 'FIXED_VALUE' || t === 'FIXED') return 'FIXED_VALUE';
  if (t === 'DATA_SOURCE') return 'DATA_SOURCE';
  if (t === 'BASIC_DATA') return 'BASIC_DATA';
  if (t === 'INPUT_TEXT') return 'INPUT_TEXT';
  if (t === 'INPUT_NUMBER') return 'INPUT_NUMBER';
  if (t === 'LIST_FORMULA') return 'LIST_FORMULA';  // V203/Phase B
  return 'INPUT';
}

export interface ComponentField {
  name: string;
  field_type: 'FIXED_VALUE' | 'DATA_SOURCE' | 'INPUT' | 'INPUT_TEXT' | 'INPUT_NUMBER' | 'FORMULA' | 'BASIC_DATA' | 'LIST_FORMULA';
  content?: string;
  is_amount?: boolean;
  is_subtotal?: boolean;
  is_required?: boolean;
  formula_name?: string;  // FORMULA fields: which formula definition to use
  datasource_binding?: {
    /** V190+: 数据源类型枚举; 缺省值 = DATABASE_QUERY 兼容历史配置 */
    type?: 'DATABASE_QUERY' | 'GLOBAL_VARIABLE' | 'BNF_PATH' | 'HTTP_API';
    datasource_id?: string;
    datasource_code?: string;
    datasource_name?: string;
    param_bindings?: { param_code: string; param_name: string; bound_field_name: string }[];
    /** GLOBAL_VARIABLE 配置 */
    global_variable_code?: string;
    key_field_refs?: Record<string, string>;
    /** BNF_PATH 配置 */
    bnf_path?: string;
    /** HTTP_API 配置(Phase D follow-up) */
    api_config?: Record<string, any>;
  };
  /**
   * LIST_FORMULA 字段配置 (Phase B - 配置模板驱动).
   * 渲染时该组件按 config_template / category.items 每项展开 1 行,
   * 该字段 cell 按 per_item_rules[item.code].branches 顺序求值, 第一个 condition=true 的取其 formula.
   * 全不命中 → default_formula → 再无 → item.default_value (config_item.default_value).
   */
  list_formula_config?: {
    config_template_id: string;
    config_template_code?: string;
    config_template_name?: string;
    category_code: string;
    category_name?: string;
    per_item_rules: Record<string, {
      branches: { condition: string; formula: string }[];
      default_formula?: string;
    }>;
  };
  /** BASIC_DATA 字段绑定的 BNF 路径 */
  basic_data_path?: string;
  /** V109: 全局变量徽章 (BASIC_DATA 字段配套显示) */
  global_variable_code?: string;
  /** V190: 统一默认值来源结构 (替代 V184 散字段) */
  default_source?: {
    type: 'GLOBAL_VARIABLE' | 'BNF_PATH' | 'HTTP_API';
    code?: string;
    key_values?: Record<string, any>;
    key_field_refs?: Record<string, string>;
    path?: string;
    api_config?: Record<string, any>;
  };
  sort_order?: number;
  // Backward-compat aliases
  key?: string;
  label?: string;
}

export interface ComponentFormula {
  name: string;
  expression: any[];
  result_type?: string;
}

export interface ComponentDataItem {
  componentId: string;
  componentCode: string;
  componentType?: string;  // 'NORMAL' | 'SUBTOTAL'
  tabName: string;
  fields: ComponentField[];
  formulas: ComponentFormula[];
  formulaAssignments?: Record<string, string>;  // fieldIndex -> formulaName, from template (legacy)
  rows: Record<string, any>[];
  subtotal: number;
  /** Y1.5 行驱动 BNF 路径(从模板快照透传)— 非空时 Step2 按 expand-driver 返回的 N 行渲染 */
  dataDriverPath?: string;
  treeConfig?: import('../component/types').TreeConfig;
}

export interface LineItem {
  productId: string;
  productName: string;
  productPartNo: string;
  /** PRD: 产品卡片优先用"客户视角"展示——customer_part_name + customer_product_no
   *  来自 mat_customer_part_mapping 按 (customerId, hfPartNo) 反查；缺失则回退 productName/productPartNo。 */
  customerPartName?: string;
  customerProductNo?: string;
  customerDrawingNo?: string;
  /** PRD: 卡片右侧"生产料号"小卡片——按 productPartNo 查 mat_part 主档 */
  hfPartInfo?: {
    partNo?: string;
    partName?: string;
    specification?: string;
    sizeInfo?: string;
    statusCode?: string;
  };
  templateId: string;
  templateName: string;
  productAttributeValues: Record<string, any>;
  productAttributes?: { name: string; field_type: string; required: boolean; default_value?: string; source?: string }[];
  componentData: ComponentDataItem[];
  subtotal: number;
  subtotalFormula?: any[];  // Token array from template.subtotal_formula
  /** 导入来源标记:从基础数据导入加入报价单的行设 true,后端 saveDraft 据此从基础工序 seed 本行 quotation_line_process */
  seedProcessesFromBase?: boolean;
  /** 料号版本锁定: 本行报价使用的 (customer_product_no, hf_part_no) 版本号 */
  partVersionLocked?: number;
  /** line_item id (后端 PATCH 需要; 新创建未持久化的 line_item 无 id) */
  id?: string;
  /**
   * 报价单整份快照 Phase2: 行级值快照(后端 JSON 字符串, tabs[].{baseRows,editRows,formulaResults})。
   * Task 8 渲染脱钩: 有值时从此构造 driverExpansions(BASIC_DATA/driver 行不再 batch-expand)。
   */
  quoteCardValues?: string;
  costingCardValues?: string;
  /** Excel 值快照（后端已算好，形态 `{rows:[{colKey:value}]}`，每 lineItem 一行 rows[0]）。缺值→显示"—"，不降级实时拉数。 */
  quoteExcelValues?: string;
  costingExcelValues?: string;
  /**
   * Bug B (2026-05-20): 前端临时 id，用于 driverExpansionKey lineItemId 维度。
   * 新建 lineItem 时生成 (crypto.randomUUID())，后端持久化后 id 接管。
   * 保证同 productPartNo / componentId / customerId / driverPath / fieldsHash 组合
   * 在同一报价单内被多个 lineItem 共用时各自独立缓存，不相互污染。
   */
  tempId?: string;
  /**
   * 产品类型 — 后端反查 mat_part.product_type ('SIMPLE' / 'COMPOSITE').
   * 用途: ProductCard 按类型条件渲染 Tab(COMPOSITE 专属 Tab 在 SIMPLE 下隐藏).
   * 不读 quotation_line_item.composite_type(saveDraft 重建时会被覆盖回 SIMPLE,不可靠).
   */
  productType?: 'SIMPLE' | 'COMPOSITE';
  /**
   * 选配组合产品的 line_item 关系 — 来自后端 buildLineItemDTO:
   * - compositeType='SIMPLE' / 'COMPOSITE' (父级) / 'PART' (组合产品子件)
   * - parentLineItemId 仅 PART 行非空
   * 前端渲染层 filter PART 隐藏子卡片, 父卡片 Tab 内通过聚合视图展示所有子件数据.
   */
  compositeType?: 'SIMPLE' | 'COMPOSITE' | 'PART';
  parentLineItemId?: string;

  // ★ Step3 新增 9 字段（spec §5.3，字段名严格按 camelCase 锁定）
  /** 年用量（用户输入，驱动阶梯折扣） */
  annualVolume?: number;
  /** 优惠金额来源 metric_code（MATERIAL_COST/PROCESS_FEE/.../SUBTOTAL） */
  discountSource?: string;
  /** 可优惠金额基数 — commit 时快照 */
  discountBaseAmount?: number;
  /** 实际折扣率 % (0-100，V1 由阶梯引擎硬算) */
  discountRateApplied?: number;
  /** 单件优惠金额（= base × rate / 100） */
  lineDiscountAmount?: number;
  /** 单价 = subtotal 快照（防 Step2 后续被改） */
  lineUnitPrice?: number;
  /** 优惠后单价（= lineUnitPrice - lineDiscountAmount） */
  lineFinalPrice?: number;
  /** 行总金额（= annualVolume × lineFinalPrice；Σ 即整单 total_amount） */
  lineTotalAmount?: number;
  /** 命中的折扣规则编号（V1 = ANNUAL_VOLUME_STEP_V1） */
  discountRuleCode?: string;
}

export type LineItemUpdater = Partial<LineItem> | ((prev: LineItem) => Partial<LineItem>);

export interface QuotationStep2Props {
  lineItems: LineItem[];
  onAddProduct: () => void;
  /** 选配添加 — 打开 ConfigureProductDrawer */
  onAddConfigured?: () => void;
  onRemoveProduct: (index: number) => void;
  onUpdateLineItem: (index: number, data: LineItemUpdater) => void;
  /** 批量从基础数据导入产品 — 由父组件实现 setLineItems(prev => [...prev, ...newItems]) */
  onAddBatch?: (newItems: LineItem[]) => void;
  customerId?: string;
  quotationId?: string;
  /** 已绑定的客户报价模板 ID(BasicDataImportV5ToQuotation 创建时写入 quotation) */
  customerTemplateId?: string;
  /** V72：核价模板 ID(template 表 templateKind='COSTING')。
   *  「核价单」视图的产品卡片按这套组件渲染；与 customerTemplateId 同等地位，但用于不同视图。 */
  costingCardTemplateId?: string;
  /** 报价漂移检测结果(来自报价单 getById 返回的 driftDetection 字段) */
  driftDetection?: DriftDetectionResult;
  /** 刷新报价单后的回调(用于横幅刷新按钮) */
  onRefreshQuotation?: () => void;
  /** Phase4 Task3: 报价卡片结构快照(顶层, 提供 rowKeyFields) — 报价侧渲染读 editRows/formulaResults + 编辑回写需要 */
  quoteCardStructure?: CardStructure | null;
  /** Phase4 Task3: 核价卡片结构快照(暂仅占位, 核价侧本任务不切换) */
  costingCardStructure?: CardStructure | null;
}

// ─── BASIC_DATA path 求值结果格式化 ─────────────────────────────────────────
// 后端 FormulaEngine `{path}` 返回值可能是: number / string / boolean / null /
// mat_fee.fee_type / mat_bom.bom_type 等 enum 值的中文标签
// 与 V58_5__basic_data_seed.sql 配置的 sheet_name 对齐,用户视图侧统一显示中文
const ENUM_LABEL: Record<string, string> = {
  // mat_fee.fee_type (V58_5 line 51-89)
  INCOMING_FIXED: '来料固定加工费',
  INCOMING_OTHER: '来料其他费用',
  FINISHED_FIXED: '成品固定加工费',
  FINISHED_OTHER: '成品其他费用',
  INCOMING_ANNUAL_DOWN: '来料年降',
  ASSEMBLY_PROCESS: '组装加工费',
  ASSEMBLY_ANNUAL_DOWN: '组装加工费年降',
  ANNUAL_REDUCTION_FACTOR: '年降系数',
  // mat_bom.bom_type
  ELEMENT: '元素 BOM',
  INCOMING: '来料 BOM',
  ASSEMBLY: '组装 BOM',
};

// Map(单行多列) / List<Map>(多行)。BASIC_DATA 字段是单元格语义,这里把任意类型
// 投影成显示字符串。null/empty → 返回 null(UI 显示 —);多值 → 第一个 + "(+N)"提示。
function formatPathValue(v: any): string | null {
  if (v == null || v === '') return null;
  if (typeof v === 'number' || typeof v === 'boolean') return String(v);
  if (typeof v === 'string') {
    // 已知 enum 值显示中文标签;陌生字符串原样返回
    return ENUM_LABEL[v] ?? v;
  }
  if (Array.isArray(v)) {
    if (v.length === 0) return null;
    const first = formatPathValue(v[0]);
    if (v.length === 1) return first;
    return first ? `${first} (共 ${v.length} 项)` : `共 ${v.length} 项`;
  }
  if (typeof v === 'object') {
    // V197 hotfix: PG JDBC 把 jsonb 列读成 PGobject {type:'jsonb', value:'<json string>'}.
    // JSONB 是「单 cell 内的结构化值」(数组/对象), 不应该走通用 array 的"首值+共N项"截短语义
    // (后者针对 driver expand 多行聚合). 单 cell jsonb 应完整展开 list / k=v 显示.
    if (v.type === 'jsonb' && typeof v.value === 'string') {
      try {
        const parsed = JSON.parse(v.value);
        if (Array.isArray(parsed)) {
          if (parsed.length === 0) return null;
          const items = parsed.map(it => formatPathValue(it) ?? '').filter(Boolean);
          return items.length > 0 ? items.join(', ') : null;
        }
        if (parsed && typeof parsed === 'object') {
          const keys = Object.keys(parsed);
          if (keys.length === 0) return null;  // 空对象 {} → 显示 —
          const pairs = keys.map(k => {
            const sub = formatPathValue(parsed[k]);
            return sub != null ? `${k}=${sub}` : null;
          }).filter(Boolean);
          return pairs.length > 0 ? pairs.join(', ') : null;
        }
        // 标量 (number/string/boolean)
        return formatPathValue(parsed);
      } catch {
        return v.value;  // 解析失败原样返回 string
      }
    }
    // 取第一个非 null 字段值
    for (const k of Object.keys(v)) {
      if (v[k] != null && v[k] !== '') {
        const sub = formatPathValue(v[k]);
        if (sub) return sub;
      }
    }
    // 2026-05-19 修: 全为空时直接返 null (上层 placeholder '—').
    // 旧实现 fallback 到 JSON.stringify(v) 截短, 但会把 "{param_values:{type:'jsonb',value:'{}'}}"
    // 这种"装载空 jsonb 的结构对象"原样 JSON dump 出来, 用户看到的就是 `{"param_values":{"type":"jsonb...`
    return null;
  }
  return String(v);
}

// ─── Formula helpers ─────────────────────────────────────────────────────────

/** Resolve which formula definition applies to a FORMULA field */
function resolveFormula(
  comp: ComponentDataItem,
  formulaFieldName: string
): ComponentFormula | undefined {
  if (!comp.formulas) return undefined;

  const field = comp.fields.find(f => (f.name || f.key) === formulaFieldName && f.field_type === 'FORMULA');
  const fieldIndex = field ? comp.fields.indexOf(field) : -1;

  // 0. (2026-05-20) field.formula_name 显式绑定 — 组件管理 UI 通过 Select 写入此字段, 优先级最高
  if (field?.formula_name) {
    const found = comp.formulas.find(f => f.name === field.formula_name);
    if (found) return found;
    // 显式绑定了但 formulas 数组里找不到 → 配置漂移 (公式被删 / 改名), 不要 fallback 到下游
    // 避免渲染显示别的公式结果误导用户; 返 undefined 让渲染层显示 '—'
    return undefined;
  }

  // 1. Template-level binding (template_component.formula_assignments)
  if (fieldIndex >= 0 && comp.formulaAssignments) {
    const assignedName = comp.formulaAssignments[String(fieldIndex)];
    if (assignedName) {
      const found = comp.formulas.find(f => f.name === assignedName);
      if (found) return found;
    }
  }

  // 2. Exact name match (field name == formula name) — 兼容老配置
  const byName = comp.formulas.find(f => f.name === formulaFieldName);
  if (byName) return byName;

  // 3. Positional fallback — 兼容更老配置 (无 name 匹配时按 FORMULA 字段在 fields 中的相对位置)
  if (fieldIndex >= 0) {
    const posIdx = comp.fields
      .filter(f => f.field_type === 'FORMULA')
      .findIndex(f => (f.name || f.key) === formulaFieldName);
    if (posIdx >= 0 && posIdx < comp.formulas.length) return comp.formulas[posIdx];
  }
  return undefined;
}

/** Get dependency field names from a formula expression (only 'field' type tokens) */
function getFormulaDeps(formula: ComponentFormula): string[] {
  if (!formula.expression) return [];
  return formula.expression
    .filter((t: any) => t.type === 'field' && t.value)
    .map((t: any) => t.value as string);
}

/**
 * Compute ALL formula fields for a single row in topological (dependency) order.
 * Returns a map of fieldName -> computed value.
 */
function computeAllFormulas(
  comp: ComponentDataItem,
  row: Record<string, any>,
  allComponentSubtotals?: Record<string, number>,
  quotationFields?: Record<string, number>,
  pathCache?: Record<string, number>,
  partNo?: string,
  // Y1.5 driver 行级 BASIC_DATA 值：key = "{path}", value = 该行该路径的标量/列表。
  // 提供后，BASIC_DATA 字段优先按当前行取值；缺失再回退到 partNo 维度的 globalPathCache。
  // 不传则保持旧行为（第一行公式覆盖全行）。
  basicDataValues?: Record<string, any>,
  // 2026-05-17 累加公式: 上一行的 is_subtotal 字段值, 让 previous_row_subtotal token 求值.
  // 调用方按 row_index 顺序遍历, 行 0 不传或传 undefined 走 token 的 fallback_component_code.
  previousRowSubtotal?: number,
  // 动态 key 全局变量运行时 path 重写 (AP-bug 修复): 向后兼容, 老调用不传时动态 key 兜底 0
  globalVariableDefs?: Record<string, GlobalVariableDefinition>,
): Record<string, number | null> {
  if (!comp.fields || !comp.formulas) return {};

  // Collect FORMULA fields and their resolved formulas
  const formulaFields: { name: string; formula: ComponentFormula }[] = [];
  for (const f of comp.fields) {
    if (f.field_type !== 'FORMULA') continue;
    const name = f.name || f.key || '';
    const formula = resolveFormula(comp, name);
    if (formula) formulaFields.push({ name, formula });
  }
  if (formulaFields.length === 0) return {};

  // Build dependency graph among formula fields
  const formulaNameSet = new Set(formulaFields.map(ff => ff.name));
  const deps: Record<string, string[]> = {};
  for (const ff of formulaFields) {
    deps[ff.name] = getFormulaDeps(ff.formula).filter(d => formulaNameSet.has(d));
  }

  // Topological sort (Kahn's algorithm)
  const inDegree: Record<string, number> = {};
  for (const ff of formulaFields) inDegree[ff.name] = 0;
  for (const ff of formulaFields) {
    for (const dep of deps[ff.name]) {
      inDegree[dep] = (inDegree[dep] || 0); // ensure dep exists
    }
  }
  // inDegree counts how many formulas depend on each
  // Actually we need: for each formula, how many of its deps are not yet computed
  const revInDegree: Record<string, number> = {};
  for (const ff of formulaFields) revInDegree[ff.name] = deps[ff.name].length;
  const queue: string[] = formulaFields.filter(ff => revInDegree[ff.name] === 0).map(ff => ff.name);
  const order: string[] = [];
  while (queue.length > 0) {
    const cur = queue.shift()!;
    order.push(cur);
    // Find formulas that depend on cur
    for (const ff of formulaFields) {
      if (deps[ff.name].includes(cur)) {
        revInDegree[ff.name]--;
        if (revInDegree[ff.name] === 0) queue.push(ff.name);
      }
    }
  }
  // Any remaining (cycle) — add them at end, they'll get null
  for (const ff of formulaFields) {
    if (!order.includes(ff.name)) order.push(ff.name);
  }

  // Evaluate in order, feeding results into fieldValues
  const fieldValues: Record<string, number> = {};
  for (const f of comp.fields) {
    if (f.field_type !== 'FORMULA') {
      const key = f.name || f.key || '';
      // BASIC_DATA 字段：优先用本行 driver 展开值（按 {path} 索引），
      // 否则回退到 partNo 级 path cache。后者是聚合多行结果，对于 driver
      // 展开（如 mat_bom 元素行）会让所有行算出同一个值——必须用前者。
      if (f.field_type === 'BASIC_DATA' && f.basic_data_path && partNo) {
        const effPath = f.basic_data_path;
        let cached: any = undefined;
        if (basicDataValues) {
          const lookupKey = bnfDriverLookupKey(effPath);
          if (Object.prototype.hasOwnProperty.call(basicDataValues, lookupKey)) {
            cached = basicDataValues[lookupKey];
          }
        }
        if (cached === undefined) {
          const cacheKey = `${partNo}::${effPath}`;
          const cache = pathCache ?? (getGlobalPathCache() as Record<string, any>);
          cached = cache[cacheKey];
        }
        // 数值直接用；字符串/对象通过 formatPathValue 取首值再 parseFloat
        let num: number | null = null;
        if (typeof cached === 'number') {
          num = cached;
        } else if (cached != null) {
          const formatted = formatPathValue(cached);
          if (formatted != null) {
            const parsed = parseFloat(formatted);
            if (!isNaN(parsed)) num = parsed;
          }
        }
        fieldValues[key] = num ?? 0;  // 未求值或非数值时占 0,UI 重渲染后刷新
        continue;
      }
      // DATA_SOURCE 字段(Phase J 后 4 sub-type) — 把解析结果回填到 fieldValues,
      // 公式中 `datasource_field` / `field_value` token 引用本字段才能拿到值.
      // AP-37 协议第 7 处:  computeAllFormulas 字段值收集循环必须覆盖每个 field_type.
      if (f.field_type === 'DATA_SOURCE' && f.datasource_binding) {
        const binding = f.datasource_binding;
        const dsType = binding.type ?? 'DATABASE_QUERY';
        let resolved: any = row[key];  // DATABASE_QUERY / HTTP_API 走 dsLoading 写回 row 模式
        if (basicDataValues) {
          if (dsType === 'GLOBAL_VARIABLE' && binding.global_variable_code) {
            const gvKey = `@gvar:${binding.global_variable_code}`;
            if (Object.prototype.hasOwnProperty.call(basicDataValues, gvKey)) {
              const v = basicDataValues[gvKey];
              if (v != null && !(Array.isArray(v) && v.length === 0)) resolved = v;
            }
          } else if (dsType === 'BNF_PATH' && binding.bnf_path) {
            const lookupKey = bnfDriverLookupKey(binding.bnf_path);
            if (Object.prototype.hasOwnProperty.call(basicDataValues, lookupKey)) {
              const v = basicDataValues[lookupKey];
              if (v != null && !(Array.isArray(v) && v.length === 0)) resolved = v;
            }
            if ((resolved === undefined || resolved === null || resolved === '') && partNo) {
              const cache = pathCache ?? (getGlobalPathCache() as Record<string, any>);
              const v = cache[`${partNo}::${binding.bnf_path}`];
              if (v != null && !(Array.isArray(v) && v.length === 0)) resolved = v;
            }
          }
        }
        // field.content 静态兜底
        if ((resolved === undefined || resolved === null || resolved === '')
            && f.content != null && f.content !== '') {
          resolved = f.content;
        }
        let num: number | null = null;
        if (typeof resolved === 'number') {
          num = resolved;
        } else if (resolved != null) {
          const formatted = formatPathValue(resolved);
          if (formatted != null) {
            const parsed = parseFloat(formatted);
            if (!isNaN(parsed)) num = parsed;
          }
        }
        if (num != null) fieldValues[key] = num;
        continue;
      }
      // FIXED_VALUE 字段如果当前行没值（driver 展开行 / 旧 row 回读未经 handleAddRow），
      // 用 field.content 兜底，避免公式把 材料损耗 这类配置型字段按 0 算。
      let raw: any = row[key];
      if ((raw === undefined || raw === null || raw === '')
          && f.field_type === 'FIXED_VALUE'
          && f.content != null && f.content !== '') {
        raw = f.content;
      }
      // V190: INPUT_NUMBER 行值为空 → 默认值兜底链:
      //   1) default_source.GLOBAL_VARIABLE → basicDataValues['@gvar:CODE'] (行级 KV)
      //   2) default_source.BNF_PATH        → basicDataValues[bnfDriverLookupKey(path)] / pathCache
      //   3) field.content 字面量 (静态兜底, 例: 成材率 = 100)
      // V193 已清理 V184 散字段, 不再兼容 default_basic_data_path 旧路径
      if ((raw === undefined || raw === null || raw === '')
          && f.field_type === 'INPUT_NUMBER') {
        let resolved: any = undefined;
        const ds = f.default_source;
        if (ds && basicDataValues) {
          if (ds.type === 'GLOBAL_VARIABLE' && ds.code) {
            const gvKey = `@gvar:${ds.code}`;
            if (Object.prototype.hasOwnProperty.call(basicDataValues, gvKey)) {
              const v = basicDataValues[gvKey];
              if (v != null && !(Array.isArray(v) && v.length === 0)) resolved = v;
            }
          } else if (ds.type === 'BNF_PATH' && ds.path) {
            const lookupKey = bnfDriverLookupKey(ds.path);
            if (Object.prototype.hasOwnProperty.call(basicDataValues, lookupKey)) {
              const v = basicDataValues[lookupKey];
              if (v != null && !(Array.isArray(v) && v.length === 0)) resolved = v;
            }
            if (resolved === undefined && partNo) {
              const cache = pathCache ?? (getGlobalPathCache() as Record<string, any>);
              const v = cache[`${partNo}::${ds.path}`];
              if (v != null && !(Array.isArray(v) && v.length === 0)) resolved = v;
            }
          }
        }
        if (resolved !== undefined) {
          if (typeof resolved === 'number') {
            raw = resolved;
          } else {
            const formatted = formatPathValue(resolved);
            if (formatted != null) raw = formatted;
          }
        } else if (f.content != null && f.content !== '') {
          raw = f.content;  // 静态兜底
        }
      }
      const val = parseFloat(raw);
      if (!isNaN(val)) fieldValues[key] = val;
    }
  }

  const results: Record<string, number | null> = {};
  for (const name of order) {
    const ff = formulaFields.find(f => f.name === name)!;
    try {
      const val = evaluateExpression(
        ff.formula.expression, fieldValues,
        allComponentSubtotals || {}, undefined, quotationFields,
        pathCache, partNo, basicDataValues, previousRowSubtotal,
        globalVariableDefs, row,
      );
      results[name] = val;
      fieldValues[name] = val; // feed result for downstream formulas
    } catch {
      results[name] = null;
    }
  }
  return results;
}

/** Compute a single formula field value (convenience wrapper using computeAllFormulas cache) */
function computeFormula(
  comp: ComponentDataItem,
  formulaFieldName: string,
  row: Record<string, any>,
  allComponentSubtotals?: Record<string, number>,
  _formulaCache?: Record<string, number | null>,
  quotationFields?: Record<string, number>,
  pathCache?: Record<string, number>,
  partNo?: string,
): number | null {
  // Use cache if provided (from computeAllFormulas)
  if (_formulaCache && formulaFieldName in _formulaCache) return _formulaCache[formulaFieldName];

  // Fallback: compute all and return the requested one
  const all = computeAllFormulas(comp, row, allComponentSubtotals, quotationFields, pathCache, partNo);
  return all[formulaFieldName] ?? null;
}

/** FIXED_VALUE 默认值兜底 — 与 effectiveRows 派生处保持完全一致 */
function fillFixedDefaults(
  fields: ComponentField[],
  raw: Record<string, any>,
): Record<string, any> {
  let cloned: Record<string, any> | null = null;
  for (const f of fields) {
    if (f.field_type !== 'FIXED_VALUE') continue;
    if (f.content == null || f.content === '') continue;
    const k = f.name || f.key || '';
    if (!k) continue;
    if (raw[k] !== undefined && raw[k] !== null && raw[k] !== '') continue;
    if (!cloned) cloned = { ...raw };
    cloned[k] = f.content;
  }
  return cloned ?? raw;
}

function computeTabSubtotal(
  comp: ComponentDataItem,
  allComponentSubtotals?: Record<string, number>,
  quotationFields?: Record<string, number>,
  pathCache?: Record<string, number>,
  partNo?: string,
  // Y1.5：driver 行展开。提供后按 rowCount 迭代，每行用各自的 basicDataValues
  // —— 与 ProductCard 渲染层 effectiveRows 一致；不传则退化为按 comp.rows 迭代（旧行为）。
  driverExpansion?: import('./useDriverExpansions').DriverExpansion,
  // 动态 key 全局变量运行时 path 重写: 向后兼容, 老调用不传时动态 key 兜底 0
  globalVariableDefs?: Record<string, GlobalVariableDefinition>,
): number {
  if (!comp?.fields || !comp?.rows) return 0;
  const subtotalField = comp.fields.find(f => f.is_subtotal);
  if (!subtotalField) return 0;
  // V160/V161 后修订: driver 是数据真相源 (mat_bom / mat_fee / costing_part_* 全部版本化).
  // 早期 V126.1 取 max 让"用户追加行"也参与列小计, 但 V160/V161 之前 driver 因视图缺
  // part_version 列返多版本叠加, autoSave 会把过量行回写 quotation_line_component_data;
  // 修复后 driver 正确返 N 行 → max(N, M>N)=M → 多 M-N 行成"鬼魂"行 (BASIC_DATA 永远加载中).
  // 用 driverCount 为准: driver-bound 模式下 comp.rows 多出的尾巴视为陈旧持久化, 不参与小计.
  const useDriver = driverExpansion && driverExpansion.rowCount > 0;
  const driverCount = useDriver ? driverExpansion!.rowCount : 0;
  const rowCount = useDriver ? driverCount : comp.rows.length;
  let sum = 0;
  for (let i = 0; i < rowCount; i++) {
    const baseRow = comp.rows[i] ?? {};
    const row = fillFixedDefaults(comp.fields, baseRow);
    const basicDataValues = (useDriver && i < driverCount) ? driverExpansion!.rows[i]?.basicDataValues : undefined;
    const cache = computeAllFormulas(
      comp, row, allComponentSubtotals, quotationFields, pathCache, partNo, basicDataValues,
      undefined, globalVariableDefs,
    );
    sum += cache[subtotalField.name] ?? 0;
  }
  return sum;
}


function computeProductSubtotal(
  item: LineItem,
  // 让 caller 把 driverExpansions / customerId 透传进来 —— 不传则退化为旧行为（仅 comp.rows）
  driverExpansions?: import('./useDriverExpansions').DriverExpansionMap,
  customerId?: string,
): number {
  if (!item.componentData || item.componentData.length === 0) return item.subtotal || 0;

  const partNo = item.productPartNo;
  // V203/Phase B: 必须传 comp.dataDriverPath 才能区分同 componentId 不同 driver 的两个组件实例
  // V196 (2026-05-19): 加 fieldsHash 维度区分同 cid 同 driver 不同 fields_override 的两个 Tab
  const lookupExpansion = (comp: ComponentDataItem) => {
    if (!driverExpansions || !partNo || !comp.componentId) return undefined;
    // Bug B: lineItemId = item.id || item.tempId || ''
    const lineItemId = (item as any).id || (item as any).tempId || '';
    const k = driverExpansionKey(lineItemId, partNo, comp.componentId, customerId, comp.dataDriverPath, fieldsOverrideHash(comp.fields as any[]));
    return driverExpansions[k];
  };

  // Compute NORMAL component subtotals first
  const componentSubtotals: Record<string, number> = {};
  for (const comp of item.componentData) {
    if (!comp?.fields || comp.componentType === 'SUBTOTAL') continue;
    // partNo + driverExpansion 一起传 —— BASIC_DATA 字段才能按行取值，
    // 不然落到全局 path cache 第一项 / 当 0 算（产品小计 156.80 vs 列小计 750.80 的根因）
    const subtotal = computeTabSubtotal(
      comp, componentSubtotals, undefined, undefined, partNo, lookupExpansion(comp),
    );
    if (comp.componentId) componentSubtotals[comp.componentId] = subtotal;
    if (comp.componentCode) componentSubtotals[comp.componentCode] = subtotal;
    componentSubtotals[comp.tabName] = subtotal;
  }

  // Find SUBTOTAL component and use its first formula as product subtotal
  const subtotalComp = item.componentData.find(c => c.componentType === 'SUBTOTAL');
  if (subtotalComp && subtotalComp.formulas && subtotalComp.formulas.length > 0) {
    const formula = subtotalComp.formulas[0];
    if (formula.expression && formula.expression.length > 0) {
      // Build NUMBER product attribute values
      const productAttrs: Record<string, number> = {};
      for (const attr of item.productAttributes || []) {
        if (attr.field_type === 'NUMBER') {
          const val = parseFloat(item.productAttributeValues?.[attr.name]);
          if (!isNaN(val)) productAttrs[attr.name] = val;
        }
      }
      try {
        return evaluateExpression(formula.expression, {}, componentSubtotals, productAttrs);
      } catch {
        return 0;
      }
    }
  }

  // Fallback: use legacy subtotalFormula if present
  if (item.subtotalFormula && item.subtotalFormula.length > 0) {
    const productAttrs: Record<string, number> = {};
    for (const attr of item.productAttributes || []) {
      if (attr.field_type === 'NUMBER') {
        const val = parseFloat(item.productAttributeValues?.[attr.name]);
        if (!isNaN(val)) productAttrs[attr.name] = val;
      }
    }
    try {
      return evaluateExpression(item.subtotalFormula, {}, componentSubtotals, productAttrs);
    } catch {
      return 0;
    }
  }

  // Final fallback: sum of all component subtotals
  return Object.values(componentSubtotals).reduce((s, v) => s + v, 0);
}

/** 稳定空数组引用 — 传给 useDriverExpansions 表示"无需 batch-expand"(快照模式), 避免每渲染新建 [] 触发 churn。 */
export const EMPTY_LINEITEMS: LineItem[] = [];

/**
 * Task 8 渲染脱钩: 从行级值快照(quoteCardValues/costingCardValues)构造 DriverExpansionMap,
 * 键与 ProductCard 渲染查找完全一致(driverExpansionKey)。
 *
 * <p>有此 map 时, activeDriverExpansion 命中快照 baseRows → driver 行数 + BASIC_DATA 值直接来自快照,
 * 渲染期不再调 /batch-expand。FORMULA 仍由 computeAllFormulas 按快照 basicDataValues 实时算(同引擎同输入, 防漂移)。
 */
export function buildSnapshotExpansions(
  items: LineItem[],
  side: 'QUOTE' | 'COSTING',
  customerId?: string,
): import('./useDriverExpansions').DriverExpansionMap {
  const map: import('./useDriverExpansions').DriverExpansionMap = {};
  for (const item of items) {
    const json = side === 'QUOTE' ? item.quoteCardValues : item.costingCardValues;
    if (!json) continue;
    let parsed: any;
    try { parsed = JSON.parse(json); } catch { continue; }
    const tabs = parsed?.tabs;
    if (!Array.isArray(tabs)) continue;
    const partNo = item.productPartNo;
    if (!partNo) continue;
    const lineItemId = (item as any).id || (item as any).tempId || '';
    for (const vtab of tabs) {
      const cid = vtab?.componentId;
      if (!cid) continue;
      const comp = item.componentData?.find(c => c.componentId === cid);
      if (!comp) continue; // 结构来自 componentData(enrich), 快照值按 componentId 对齐
      const baseRows: any[] = Array.isArray(vtab.baseRows) ? vtab.baseRows : [];
      const key = driverExpansionKey(
        lineItemId, partNo, cid, customerId,
        comp.dataDriverPath, fieldsOverrideHash(comp.fields as any[]),
      );
      map[key] = {
        rowCount: baseRows.length,
        rows: baseRows.map((br: any) => ({
          driverRow: br?.driverRow ?? {},
          basicDataValues: br?.basicDataValues ?? {},
        })),
      };
    }
  }
  return map;
}

// ─── ProductCard ─────────────────────────────────────────────────────────────

interface ProductCardProps {
  item: LineItem;
  index: number;
  onRemove: () => void;
  onUpdate: (data: LineItemUpdater) => void;
  customerId?: string;
  /** Y1.5 行驱动展开结果(由父级 useDriverExpansions 返回) */
  driverExpansions?: import('./useDriverExpansions').DriverExpansionMap;
  /** LIST_FORMULA 字段类型的 config_template 详情缓存 (key=config_template_id → {template, loading, error}) */
  configTemplates?: ConfigTemplateMap;
  /** 报价单 ID (用于料号版本切换 PATCH 调用); 未传则版本 Tag 仅显示不可点击 */
  quotationId?: string;
  /** 2026-05-20: usePathFormulaCache 返的 React state cache, 给 LIST_FORMULA BNF fallback 用 */
  pathCacheState?: Record<string, any>;
  /** 动态 key 全局变量定义字典 (code → def), 用于运行时 path 重写 */
  globalVariableDefs?: Record<string, GlobalVariableDefinition>;
  /** Phase4 Task3: 本卡片所属视图侧 — 'QUOTE' 走快照 editRows/formulaResults 读 + editQuoteCardValue 写; 'COSTING' 维持旧路径 */
  cardSide?: 'QUOTE' | 'COSTING';
  /** Phase4 Task3: 本侧卡片结构快照(提供 rowKeyFields, 用于 rowKey 计算对齐后端) */
  cardStructure?: CardStructure | null;
}

const ProductCard: React.FC<ProductCardProps> = ({ item, index, onRemove, onUpdate, customerId, driverExpansions, configTemplates, quotationId, pathCacheState, globalVariableDefs, cardSide, cardStructure }) => {
  const [activeTab, setActiveTab] = useState(0);
  const [versionDrawerOpen, setVersionDrawerOpen] = useState(false);
  const [dsLoading, setDsLoading] = useState<Record<string, boolean>>({});
  const [dsErrors, setDsErrors] = useState<Record<string, string>>({});
  // Material match state for border coloring
  const [matchStatus, setMatchStatus] = useState<'MATCHED_Y' | 'MATCHED_N' | 'NO_MATCH' | null>(null);
  const [matchInfo, setMatchInfo] = useState<any>(null);
  // 树表折叠态(仅 treeConfig 组件使用,非树表组件零开销)
  const treeCollapse = useTreeCollapse();

  // Match customerPartNo when customerId is available
  useEffect(() => {
    const customerPartNo = (item as any).customerPartNo;
    if (!customerId || !customerPartNo) return;
    let cancelled = false;
    // 用 cached 版本: 同 (customerId, partNo) 并发/重复调用复用 1 个 in-flight Promise → 1 次 HTTP
    materialMappingService.matchCached(customerId, customerPartNo)
      .then((res: any) => {
        if (cancelled) return;
        const matched = res.data || res;
        if (matched && matched.materialNo) {
          const status = matched.statusCode === 'Y' ? 'MATCHED_Y' : 'MATCHED_N';
          setMatchStatus(status);
          setMatchInfo(matched);
        } else {
          setMatchStatus('NO_MATCH');
          setMatchInfo(null);
        }
      })
      .catch(() => {
        if (!cancelled) { setMatchStatus('NO_MATCH'); setMatchInfo(null); }
      });
    return () => { cancelled = true; };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [customerId, (item as any).customerPartNo]);

  // V160/V161 修订: 自动清理 driver 之外的陈旧持久化行.
  // 历史 BUMP 链路若在视图未暴露 part_version 时跑过, autoSave 会把多版本叠加的 N+M 行
  // 写进 quotation_line_component_data. V160/V161 修好后 driver 正确返 N 行, 但 comp.rows
  // 仍有 N+M 行 → 渲染层用 driverCount 已隐去 (前两处修改), 这里同步把 state 收窄,
  // 防止下次 autoSave 把这 M 行又持久化回 DB.
  // 依赖只放 driverExpansions: 仅在 driver 加载/刷新时检查; 收窄后 comp.rows.length<=N 稳定不再触发.
  useEffect(() => {
    if (!driverExpansions || !item.productPartNo || !item.componentData) return;
    let needPrune = false;
    const newComponentData = item.componentData.map(comp => {
      // SUBTOTAL 组件 / 未 enrich 完成的组件 comp.rows 可能 undefined; 缺 componentId 也跳过
      if (!comp || !comp.componentId || !Array.isArray(comp.rows)) return comp;
      // Bug B: lineItemId = item.id || item.tempId || ''
      const lineItemIdPrune = (item as any).id || (item as any).tempId || '';
      const key = driverExpansionKey(lineItemIdPrune, item.productPartNo, comp.componentId, customerId, comp.dataDriverPath, fieldsOverrideHash(comp.fields as any[]));
      const exp = driverExpansions[key];
      if (!exp || exp.rowCount === 0) return comp;
      if (comp.rows.length > exp.rowCount) {
        needPrune = true;
        return { ...comp, rows: comp.rows.slice(0, exp.rowCount) };
      }
      return comp;
    });
    if (needPrune) {
      onUpdate(() => ({ componentData: newComponentData }));
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [driverExpansions]);

  // 300ms debounce timers per blur event
  const debounceTimers = useRef<Record<string, ReturnType<typeof setTimeout>>>({});

  // 5-minute cache: cacheKey → { value, timestamp }
  const dsCache = useRef<Map<string, { value: any; timestamp: number }>>(new Map());

  const handleAttrChange = (fieldKey: string, value: string) => {
    onUpdate((prevItem: LineItem) => ({
      productAttributeValues: {
        ...prevItem.productAttributeValues,
        [fieldKey]: value,
      },
    }));
  };

  // 三个 row-mutator 全部使用函数式 setState（onUpdate(prev => ...)），
  // 避免与 driver 展开异步回填、autoSave 定时保存、DATA_SOURCE auto-query
  // 等异步事件竞争时把旧版本 item.componentData 写回，造成"看着输入了但只
  // 留下一两次按键"的 race（典型表现：产品卡 A 的多行被保住，产品卡 B
  // 同样操作只剩 1 行）。
  const handleRowChange = (
    tabIndex: number,
    rowIndex: number,
    fieldKey: string,
    value: any
  ) => {
    onUpdate((prevItem: LineItem) => ({
      componentData: prevItem.componentData.map((comp, ci) => {
        if (ci !== tabIndex) return comp;
        // 在 driver 展开模式下，UI 行数 (rowCount) 可能 > comp.rows.length。
        // rowIndex 越界则先补齐空行再 patch。
        let rows = comp.rows;
        if (rowIndex >= rows.length) {
          const filler = Array.from({ length: rowIndex - rows.length + 1 }, () => ({}));
          rows = [...rows, ...filler];
        }
        const newRows = rows.map((row, ri) =>
          ri !== rowIndex ? row : { ...row, [fieldKey]: value }
        );
        return { ...comp, rows: newRows };
      }),
    }));
  };

  const handleDeleteRow = (tabIndex: number, rowIndex: number) => {
    onUpdate((prevItem: LineItem) => ({
      componentData: prevItem.componentData.map((comp, ci) => {
        if (ci !== tabIndex) return comp;
        return { ...comp, rows: comp.rows.filter((_, ri) => ri !== rowIndex) };
      }),
    }));
  };

  const handleAddRow = (tabIndex: number) => {
    onUpdate((prevItem: LineItem) => ({
      componentData: prevItem.componentData.map((comp, ci) => {
        if (ci !== tabIndex) return comp;
        const emptyRow: Record<string, any> = { row_index: comp.rows.length };
        comp.fields.forEach(f => {
          const key = f.name || f.key || '';
          if (f.field_type === 'FIXED_VALUE') {
            emptyRow[key] = f.content ?? '';
          } else if (f.field_type === 'FORMULA') {
            // no stored value
          } else if (f.field_type === 'DATA_SOURCE') {
            emptyRow[key] = null;
          } else {
            emptyRow[key] = '';
          }
        });
        return { ...comp, rows: [...comp.rows, emptyRow] };
      }),
    }));
  };

  // Functional row update: reads latest state from parent, only patches one field.
  // This prevents stale-closure overwrites when DS query returns after user has edited other fields.
  const patchRowField = useCallback((
    tabIndex: number,
    rowIndex: number,
    fieldKey: string,
    value: any,
  ) => {
    onUpdate((prevItem: LineItem) => ({
      componentData: prevItem.componentData.map((comp, ci) => {
        if (ci !== tabIndex) return comp;
        let rows = comp.rows;
        if (rowIndex >= rows.length) {
          const filler = Array.from({ length: rowIndex - rows.length + 1 }, () => ({}));
          rows = [...rows, ...filler];
        }
        const newRows = rows.map((row, ri) =>
          ri !== rowIndex ? row : { ...row, [fieldKey]: value }
        );
        return { ...comp, rows: newRows };
      }),
    }));
  }, [onUpdate]);

  // Execute a single DATA_SOURCE field query for a specific row
  const executeDsQuery = useCallback(async (
    tabIndex: number,
    rowIndex: number,
    dsField: ComponentField,
  ) => {
    const binding = dsField.datasource_binding!;
    // executeDsQuery 仅服务于 DATABASE_QUERY 子类型(GLOBAL_VARIABLE / BNF_PATH 走 computeAllFormulas 内的 enrich)
    if (!binding.datasource_id) return;
    const paramBindings = binding.param_bindings || [];
    const comp = item.componentData[tabIndex];
    if (!comp) return;

    // Collect all param values — all must be filled
    const params: Record<string, string> = {};
    let allFilled = true;
    for (const pb of paramBindings) {
      const val = comp.rows[rowIndex]?.[pb.bound_field_name];
      if (val == null || val === '') { allFilled = false; break; }
      params[pb.param_code] = String(val);
    }
    if (!allFilled) return;

    const dsKey = dsField.name || dsField.key || '';
    const loadingKey = `${tabIndex}-${rowIndex}-${dsField.name}`;
    const errorKey = loadingKey;

    // Check 5-minute cache
    const cacheKey = `${binding.datasource_id}-${JSON.stringify(params)}`;
    const cached = dsCache.current.get(cacheKey);
    if (cached && Date.now() - cached.timestamp < 5 * 60 * 1000) {
      patchRowField(tabIndex, rowIndex, dsKey, cached.value);
      setDsErrors(prev => {
        const next = { ...prev };
        delete next[errorKey];
        return next;
      });
      return;
    }

    // Show loading state
    setDsLoading(prev => ({ ...prev, [loadingKey]: true }));
    setDsErrors(prev => {
      const next = { ...prev };
      delete next[errorKey];
      return next;
    });

    try {
      const res = await datasourceService.execute(binding.datasource_id, { testParams: params });

      let result: string | number | null = null;
      const payload = res?.data;
      if (payload != null && typeof payload === 'object') {
        result = payload.extractedValue ?? payload.rawResponse ?? null;
      } else if (typeof payload === 'string' || typeof payload === 'number') {
        result = payload;
      }
      if (result != null && typeof result === 'object') {
        result = String(result);
      }

      dsCache.current.set(cacheKey, { value: result, timestamp: Date.now() });
      // Use functional update — reads latest row from state, won't overwrite user's concurrent edits
      patchRowField(tabIndex, rowIndex, dsKey, result);
    } catch (e: any) {
      setDsErrors(prev => ({ ...prev, [errorKey]: e.message || '查询失败' }));
      patchRowField(tabIndex, rowIndex, dsKey, null);
    } finally {
      setDsLoading(prev => ({ ...prev, [loadingKey]: false }));
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item.componentData, patchRowField]);

  const handleInputBlur = (tabIndex: number, rowIndex: number, fieldName: string) => {
    const timerKey = `${tabIndex}-${rowIndex}-${fieldName}`;
    if (debounceTimers.current[timerKey]) {
      clearTimeout(debounceTimers.current[timerKey]);
    }
    debounceTimers.current[timerKey] = setTimeout(() => {
      const comp = item.componentData[tabIndex];
      if (!comp) return;

      for (const dsField of comp.fields) {
        if (dsField.field_type !== 'DATA_SOURCE' || !dsField.datasource_binding) continue;
        const binding = dsField.datasource_binding;
        // H2/K hotfix: 只有 DATABASE_QUERY type 走老 execute 路径
        const dsType = binding.type ?? 'DATABASE_QUERY';
        if (dsType !== 'DATABASE_QUERY') continue;
        const paramBindings = binding.param_bindings || [];

        if (paramBindings.length > 0) {
          const paramBinding = paramBindings.find(p => p.bound_field_name === fieldName);
          if (!paramBinding) continue;
        }

        executeDsQuery(tabIndex, rowIndex, dsField);
      }
    }, 300);
  };

  // ─── Phase4 Task3: 报价侧快照编辑（FORMULA 读 formulaResults + 编辑写 editQuoteCardValue）──
  // 仅 QUOTE 侧 + 已持久化(有 line id)启用; 否则退回旧 comp.rows/computeAllFormulas 路径。
  // INPUT 受控值仍读 comp.rows(ComponentCell value={row[key]} 全受控, 叠加 editRows 会丢按键 = AP-54),
  // 故 Task3 不对 INPUT 叠加 editRows; editRows 由 editQuoteCardValue 服务端写入 + 重算 formulaResults,
  // 渲染 FORMULA 改读 formulaResults(真零计算), 编辑值经 comp.rows 即时反馈 + autosave/row_data 兜底持久化。
  // row_data 完整退役(改 INPUT 本地态)留 Task6。
  const useSnapEdit = cardSide === 'QUOTE' && !!quotationId && !!(item as any).id;
  const quoteValuesJson = (item as any).quoteCardValues as string | undefined;
  const sideCardValues = React.useMemo<CardValues | null>(() => {
    if (cardSide !== 'QUOTE' || !quoteValuesJson) return null;
    try { return JSON.parse(quoteValuesJson) as CardValues; } catch { return null; }
  }, [cardSide, quoteValuesJson]);
  const rowKeyFieldsByComp = React.useMemo(() => {
    const m = new Map<string, string[]>();
    (cardStructure?.tabs ?? []).forEach(t => { if (t.componentId) m.set(t.componentId, t.rowKeyFields ?? []); });
    return m;
  }, [cardStructure]);
  // componentId → { edit: rowKey→values, formula: rowKey→values, driverRows: i→driverRow }
  const snapByComp = React.useMemo(() => {
    const m = new Map<string, { edit: Map<string, Record<string, any>>; formula: Map<string, Record<string, any>>; driverRows: Record<string, any>[] }>();
    (sideCardValues?.tabs ?? []).forEach(vt => {
      if (!vt.componentId) return;
      const edit = new Map<string, Record<string, any>>();
      (vt.editRows ?? []).forEach(r => { if (r?.rowKey != null) edit.set(r.rowKey, r.values ?? {}); });
      const formula = new Map<string, Record<string, any>>();
      (vt.formulaResults ?? []).forEach(r => { if (r?.rowKey != null) formula.set(r.rowKey, r.values ?? {}); });
      const driverRows = (vt.baseRows ?? []).map(b => b?.driverRow ?? {});
      m.set(vt.componentId, { edit, formula, driverRows });
    });
    return m;
  }, [sideCardValues]);
  // 报价单元格编辑回写: onBlur → editQuoteCardValue → 用响应 quoteCardValues 就地回灌(AP-50)
  const handleSnapshotCellEdit = useCallback(async (componentId: string, rowKey: string, fieldName: string, value: any) => {
    const lineItemId = (item as any).id as string | undefined;
    if (!lineItemId) return;
    try {
      const res = await quotationService.editQuoteCardValue(lineItemId, { componentId, rowKey, fieldName, value });
      const qcv = res?.data?.quoteCardValues;
      const qev = res?.data?.quoteExcelValues;
      if (qcv || qev) onUpdate(() => {
        const patch: Partial<LineItem> = {};
        if (qcv) patch.quoteCardValues = qcv;
        if (qev) patch.quoteExcelValues = qev;
        return patch;
      });
    } catch {
      // 网络失败保持旧 autosave 兜底(comp.rows 已被 handleRowChange 更新), 不阻塞用户
    }
  }, [item, onUpdate]);

  // Auto-trigger parameterless DATA_SOURCE queries when rows exist
  // H2/K hotfix: 仅 DATABASE_QUERY type 走老 datasourceService.execute 路径;
  // GLOBAL_VARIABLE / BNF_PATH / HTTP_API 由 ComponentDriverService.expand 通过
  // basicDataValues 合成 key 提供, 不需要前端调 execute
  const triggeredNoParam = useRef<Set<string>>(new Set());
  useEffect(() => {
    (item.componentData ?? []).forEach((comp, tabIndex) => {
      const fields = comp?.fields ?? [];
      const rows = comp?.rows ?? [];
      const noParamDsFields = fields.filter(f => {
        if (f.field_type !== 'DATA_SOURCE' || !f.datasource_binding) return false;
        // type 缺省 = DATABASE_QUERY (兼容老配置); 其他 type 不走老路径
        const dsType = f.datasource_binding.type ?? 'DATABASE_QUERY';
        if (dsType !== 'DATABASE_QUERY') return false;
        return !f.datasource_binding.param_bindings
          || f.datasource_binding.param_bindings.length === 0;
      });
      if (noParamDsFields.length === 0) return;

      rows.forEach((_row, rowIndex) => {
        for (const dsField of noParamDsFields) {
          const key = `${tabIndex}-${rowIndex}-${dsField.name}`;
          if (triggeredNoParam.current.has(key)) continue;
          triggeredNoParam.current.add(key);
          executeDsQuery(tabIndex, rowIndex, dsField);
        }
      });
    });
  }, [item.componentData, executeDsQuery]);

  const formatCurrency = (val: number) =>
    `¥ ${(val || 0).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

  // 2026-05-17 WYSIWYG 原则: 模板配几个组件就显示几个 Tab.
  // 2026-05-19 (方案 A 用户决议, 推翻同日 QT-1409 的"自动隐藏空 Tab"策略):
  //   严格按模板 publish 时的结构展示, **不再隐藏空数据 Tab**. SUBTOTAL 仍只渲染为底部"产品小计".
  //   理由:
  //     1. 用户期望"模板配 N 个 Tab → UI 看到 N 个 Tab", 隐藏空 Tab 会让用户误以为"功能丢失".
  //     2. 详情页 ReadonlyProductCard 与编辑页 QuotationStep2 Tab 数量必须一致;
  //        ReadonlyProductCard 不发 driver 请求, 无法用同款 4 分支判定, 只能"按模板 1:1 渲染".
  //     3. 空数据 Tab 内部表格自有"暂无数据"占位提示, 不会误导.
  //   AP-31 "0-row Tab by componentHasData 隐藏" 的设计在此**撤销** — driver 展开仍按需 fetch
  //   并填充单元格, 但 Tab 头不再依赖展开数据决定显隐.
  const normalComponents = (item.componentData ?? [])
    .filter(c => c?.componentType !== 'SUBTOTAL');
  const activeComponent = normalComponents[activeTab];

  // normalComponents 过滤掉了 SUBTOTAL 组件，其下标 (activeTab) 与底层 item.componentData
  // 下标不对齐（典型：SUBTOTAL "总成本" 排在第 0 位 → 整体偏移 +1）。
  // 所有按 Tab 改 row 数据的 mutator（handleRowChange / handleInputBlur / handleDeleteRow /
  // handleAddRow）以及 DATA_SOURCE loading/state key 都直接用下标索引 item.componentData，
  // 因此必须把 normalComponents 下标映射回底层真实下标，否则编辑会写到错位的 Tab，
  // 受控 <input> 的 value 永远回退 → 表现为"文本/数字输入框无法输入字符"。
  // 用引用相等定位（filter 保留同一对象引用），activeComponent 不存在时退回 activeTab。
  const activeComponentDataIndex = activeComponent
    ? item.componentData.indexOf(activeComponent)
    : activeTab;

  // 当过滤后 activeTab 越界 → 钳到首个有效 tab,避免 Tab 列消失后头部空白
  useEffect(() => {
    if (normalComponents.length > 0 && activeTab >= normalComponents.length) {
      setActiveTab(0);
    }
  }, [normalComponents.length, activeTab]);

  // Y1.5: 当前 active 组件的 driver 展开结果(可空)
  // 不依赖 dataDriverPath 字段(可能在快照里缺失) — 直接按 rowCount 判断是否走展开渲染
  const activeDriverExpansion = (() => {
    if (!activeComponent || !item.productPartNo) return undefined;
    if (!activeComponent.componentId) return undefined;
    // Bug B: lineItemId = item.id || item.tempId || ''
    const lineItemIdActive = (item as any).id || (item as any).tempId || '';
    const key = driverExpansionKey(lineItemIdActive, item.productPartNo, activeComponent.componentId, customerId, activeComponent.dataDriverPath, fieldsOverrideHash(activeComponent.fields as any[]));
    return driverExpansions?.[key];
  })();

  // Compute cross-component subtotals for formula evaluation in the active tab
  // Key by componentId (UUID), componentCode, and tabName for maximum compatibility
  const allComponentSubtotals: Record<string, number> = {};
  for (const comp of item.componentData) {
    // partNo + driverExpansion 一起传给 computeTabSubtotal —— 让含 BASIC_DATA 字段的公式
    // 按 rowCount 迭代 driver 展开行，每行用各自的 basicDataValues。
    // 不传 driverExpansion 时会退化为按 comp.rows 迭代 + 全局 path cache（旧行为，含量取首值），
    // 列小计与产品小计因此曾经各算各的（见反模式 AP-18）。
    // Bug B: lineItemId = item.id || item.tempId || ''
    const lineItemIdSub = (item as any).id || (item as any).tempId || '';
    const expansion = (item.productPartNo && comp.componentId)
      ? driverExpansions?.[driverExpansionKey(lineItemIdSub, item.productPartNo, comp.componentId, customerId, comp.dataDriverPath, fieldsOverrideHash(comp.fields as any[]))]
      : undefined;
    const subtotal = computeTabSubtotal(
      comp, allComponentSubtotals, undefined, undefined, item.productPartNo, expansion,
      globalVariableDefs,
    );
    if (comp.componentId) allComponentSubtotals[comp.componentId] = subtotal;
    if (comp.componentCode) allComponentSubtotals[comp.componentCode] = subtotal;
    allComponentSubtotals[comp.tabName] = subtotal;
  }

  // Dynamic product attribute fields from template definition
  const attrFields = item.productAttributes || [];

  const borderColor =
    matchStatus === 'MATCHED_Y' ? '#52c41a' :
    matchStatus === 'MATCHED_N' ? '#ff4d4f' :
    matchStatus === 'NO_MATCH' ? '#ff4d4f' :
    undefined;

  const matchPopoverContent = matchInfo ? (
    <div style={{ minWidth: 200 }}>
      <div><b>内部料号:</b> {matchInfo.materialNo}</div>
      <div><b>名称:</b> {matchInfo.name || matchInfo.materialName}</div>
      <div><b>规格:</b> {matchInfo.specification || '-'}</div>
      <div><b>状态:</b> {matchInfo.statusCode === 'Y' ? '可生产' : '停产'}</div>
    </div>
  ) : (
    <div>未找到匹配的内部料号</div>
  );

  return (
    <div
      className="qt-product-card"
      style={borderColor ? { border: `2px solid ${borderColor}`, borderRadius: 6 } : undefined}
    >
      {/* Card Header — 客户视角优先 */}
      <div className="qt-card-header">
        <div className="qt-card-header-left">
          <span className="qt-product-name">
            {item.customerPartName || item.productName || `产品 ${index + 1}`}
          </span>
          {(item.customerProductNo || item.productPartNo) && (
            <span className="qt-sku-badge">
              {item.customerProductNo ? `客户产品编号: ${item.customerProductNo}` : `料号: ${item.productPartNo}`}
            </span>
          )}
          {item.templateName && (
            <span className="qt-template-badge">模板: {item.templateName}</span>
          )}
          {matchStatus && (
            <Popover content={matchPopoverContent} title="料号信息" trigger="click">
              <Button
                size="small"
                type="link"
                style={{ padding: '0 4px', color: borderColor, fontSize: 12 }}
              >
                料号信息
              </Button>
            </Popover>
          )}
        </div>
        <div className="qt-card-header-right" style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          {item.productPartNo && (
            <Popover
              title="生产料号"
              trigger="click"
              placement="bottomRight"
              content={
                <div style={{ minWidth: 280, fontSize: 13 }}>
                  <div style={{ marginBottom: 6 }}>
                    <span style={{ color: '#8c8c8c' }}>料号：</span>
                    <span style={{ fontFamily: 'monospace' }}>{item.hfPartInfo?.partNo || item.productPartNo}</span>
                  </div>
                  <div style={{ marginBottom: 6 }}>
                    <span style={{ color: '#8c8c8c' }}>名称：</span>
                    <span>{item.hfPartInfo?.partName || '—'}</span>
                  </div>
                  <div style={{ marginBottom: 6 }}>
                    <span style={{ color: '#8c8c8c' }}>规格：</span>
                    <span>{item.hfPartInfo?.specification || '—'}</span>
                  </div>
                  <div style={{ marginBottom: 6 }}>
                    <span style={{ color: '#8c8c8c' }}>尺寸：</span>
                    <span>{item.hfPartInfo?.sizeInfo || '—'}</span>
                  </div>
                  <div>
                    <span style={{ color: '#8c8c8c' }}>生产状态：</span>
                    <span>{item.hfPartInfo?.statusCode || '—'}</span>
                  </div>
                </div>
              }
            >
              <span
                style={{
                  background: '#e6f4ff',
                  color: '#0958d9',
                  border: '1px solid #91caff',
                  borderRadius: 4,
                  padding: '2px 10px',
                  fontSize: 12,
                  fontFamily: 'monospace',
                  cursor: 'pointer',
                }}
                title="点击查看生产料号详情"
              >
                生产料号: {item.productPartNo}
              </span>
            </Popover>
          )}
          {/* 料号版本号 Tag — 草稿态可点击切换 (新需求) */}
          {item.customerProductNo && item.productPartNo && (
            <span
              style={{
                background: '#f6ffed',
                color: '#389e0d',
                border: '1px solid #b7eb8f',
                borderRadius: 4,
                padding: '2px 10px',
                fontSize: 12,
                fontFamily: 'monospace',
                cursor: quotationId && item.id ? 'pointer' : 'default',
              }}
              title={quotationId && item.id ? '点击切换料号版本' : '料号版本 (草稿创建后可切换)'}
              onClick={() => {
                if (quotationId && item.id) setVersionDrawerOpen(true);
              }}
            >
              版本: v{item.partVersionLocked ?? 2000}
            </span>
          )}
          <button className="qt-action-btn delete" type="button" onClick={onRemove}>
            删除
          </button>
        </div>
      </div>

      {/* 料号版本切换 Drawer — 报价单内只做"选择已存在的版本", 不升版.
          升版的语义属于"主数据导入"时, 报价单只是消费版本. */}
      {item.customerProductNo && item.productPartNo && quotationId && item.id && (
        <PartVersionDrawer
          open={versionDrawerOpen}
          onClose={() => setVersionDrawerOpen(false)}
          customerProductNo={item.customerProductNo}
          hfPartNo={item.productPartNo}
          mode="select"
          lockedVersion={item.partVersionLocked ?? 2000}
          onApplied={async (newVer) => {
            try {
              const result = await partVersionService.updateLineItemVersion(quotationId, item.id!, newVer);
              // V6：同步更新 excelViewSnapshot，让 ProductCard 立即按新版本数据渲染
              const updates: Record<string, any> = { partVersionLocked: result.partVersionLocked ?? newVer };
              if (result.excelViewSnapshot !== undefined) {
                updates.excelViewSnapshot = result.excelViewSnapshot;
              }
              onUpdate(updates);
              message.success(`已切换至 v${result.partVersionLocked ?? newVer}，公式已重算`);
            } catch (e) {
              message.error('切换失败: ' + (e as Error).message);
            }
          }}
        />
      )}

      {/* Product Attributes */}
      {attrFields.length > 0 && (
        <div className="qt-product-attrs">
          <div className="qt-attrs-title">产品属性</div>
          <div className="qt-attrs-grid">
            {attrFields.map(field => {
              const isReadonly = field.field_type === 'FIXED_VALUE' || (field.source && field.source !== 'CUSTOM');
              const inputType = field.field_type === 'NUMBER' ? 'number' : 'text';
              return (
                <div key={field.name} className="qt-attr-field">
                  <label className="qt-attr-label">
                    {field.name}
                    {field.required && <span style={{ color: '#e53e3e', marginLeft: 2 }}>*</span>}
                  </label>
                  {isReadonly ? (
                    <span
                      className="qt-attr-input"
                      style={{ background: '#f5f5f5', color: '#595959', cursor: 'default', display: 'inline-block', lineHeight: '28px' }}
                    >
                      {item.productAttributeValues?.[field.name] || '—'}
                    </span>
                  ) : field.field_type === 'TEXTAREA' ? (
                    <textarea
                      className="qt-attr-input"
                      rows={2}
                      placeholder={field.default_value ?? ''}
                      value={item.productAttributeValues?.[field.name] ?? ''}
                      onChange={e => handleAttrChange(field.name, e.target.value)}
                      style={{ resize: 'vertical' }}
                    />
                  ) : (
                    <input
                      className="qt-attr-input"
                      type={inputType}
                      step={inputType === 'number' ? 'any' : undefined}
                      placeholder={field.default_value ?? ''}
                      value={item.productAttributeValues?.[field.name] ?? ''}
                      onChange={e => handleAttrChange(field.name, e.target.value)}
                    />
                  )}
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Component Tabs */}
      {normalComponents.length > 0 ? (
        <div className="qt-tab-section">
          {/* Tab Header */}
          <div className="qt-tab-header">
            {normalComponents.map((comp, ci) => (
              <button
                key={`tab-${ci}-${comp.componentId}`}
                className={`qt-tab-btn${activeTab === ci ? ' active' : ''}`}
                type="button"
                onClick={() => setActiveTab(ci)}
              >
                {comp.tabName}
              </button>
            ))}
          </div>

          {/* Active Tab Content */}
          {activeComponent && activeComponent.fields && (
            <div>
              <table className="qt-cost-table">
                <thead>
                  <tr>
                    {activeComponent.fields.map(field => (
                      <th key={field.name || field.key}>{field.label || field.name}</th>
                    ))}
                    <th style={{ width: 40 }} />
                  </tr>
                </thead>
                <tbody>
                  {(() => {
                    // ── Phase4 Task3 S1 数据源现状(改造前) ────────────────────────────
                    //   row 源       : activeComponent.rows[i] (=comp.rows ← row_data 旧链路) + LIST_FORMULA 映射 + fillFixedDefaults
                    //   BASIC_DATA   : activeDriverExpansion.rows[i].basicDataValues (快照模式=buildSnapshotExpansions, 已脱钩)
                    //   formulaCache : computeAllFormulas 实时算(读快照 basicDataValues; 与后端 FormulaCalculator 对齐)
                    //   编辑写路径    : ComponentCell onCellChange/onCellBlur → handleRowChange/handleInputBlur → comp.rows → autosave → row_data
                    // ── Task3 目标(QUOTE 侧) ──────────────────────────────────────────
                    //   row 源       : 叠加 editRows[rowKey].values (快照, 优先于 comp.rows)
                    //   formulaCache : 优先 formulaResults[rowKey].values, 缺时 computeAllFormulas 兜底
                    //   编辑写路径    : onBlur → editQuoteCardValue(lineItemId,{componentId,rowKey,fieldName,value}) → 回灌 quoteCardValues
                    //   rowKey       : computeRowKey(structure.rowKeyFields, baseRows[i].driverRow, i) (对齐后端)
                    //   COSTING 侧不变(无编辑端点; 详情页 AP-50 归 Task4)。
                    // Y1.5: driver 展开存在且 rowCount > 0 → 仅渲染 driver 行 (V160/V161 修订).
                    // 历史 V126.1 取 max 是因当时考虑"用户追加行"场景, 但实际 driver-bound
                    // 组件不应让用户追加超过 driver 的额外行 — 这些行无 basicDataValues,
                    // BASIC_DATA cell 永停在"加载中…". 见上方 computeComponentSubtotal 同步注释.
                    const useDriver = activeDriverExpansion && activeDriverExpansion.rowCount > 0;
                    const driverCount = useDriver ? activeDriverExpansion!.rowCount : 0;
                    // LIST_FORMULA (Phase B+): 两种模式共存
                    //   - 独立模式: 无 dataDriverPath → LIST_FORMULA 字段当组件级 driver, 按 category.items 展开
                    //   - 共存模式: 有 dataDriverPath → driver 决定行数, LIST_FORMULA 字段按本行某列(如 process_code) 查 config_item 求值
                    const listFormulaField = activeComponent.fields.find(
                      f => f.field_type === 'LIST_FORMULA' && f.list_formula_config?.config_template_id
                    );
                    const lfState = listFormulaField?.list_formula_config?.config_template_id
                      ? configTemplates?.[listFormulaField.list_formula_config.config_template_id]
                      : undefined;
                    const lfCategory = lfState?.template?.categories.find(
                      c => c.code === listFormulaField?.list_formula_config?.category_code
                    );
                    const lfItems = lfCategory?.items || [];
                    // 独立模式仅在无 driver 时启用
                    const useListFormula = !useDriver && !!listFormulaField && lfItems.length > 0;
                    const lfRowCount = useListFormula ? lfItems.length : 0;
                    const effectiveCount = useDriver
                      ? driverCount
                      : useListFormula
                      ? lfRowCount
                      : activeComponent.rows.length;
                    // Phase4 Task3: 本组件的快照编辑/公式映射 + rowKeyFields(报价侧)。
                    const activeSnap = useSnapEdit ? snapByComp.get(activeComponent.componentId) : undefined;
                    const activeRowKeyFields = rowKeyFieldsByComp.get(activeComponent.componentId);
                    // FIXED_VALUE 默认值回填：driver 展开行 / 旧报价单回读的行都有可能没经过 handleAddRow，
                    // 导致 row[key] === undefined。回填后单元格 / 公式 / 列小计 / 产品小计 共享同一份数据视图。
                    const effectiveRows = Array.from({ length: effectiveCount }, (_, i) => {
                      const isDriverBound = useDriver && i < driverCount;
                      const isListFormulaBound = !useDriver && useListFormula && i < lfRowCount;
                      // LIST_FORMULA 字段在两种模式下都需要"本行对应的 config_item":
                      //   - 独立模式: 直接 lfItems[i]
                      //   - 共存模式 (driver 驱动): 按本行 basicDataValues / row 任意值与 item.code 字符串匹配
                      const rowBdv = isDriverBound ? activeDriverExpansion!.rows[i]?.basicDataValues : undefined;
                      const rawRow = activeComponent.rows[i] ?? {};
                      let lfItem: typeof lfItems[number] | undefined;
                      if (isListFormulaBound) {
                        lfItem = lfItems[i];
                      } else if (lfItems.length > 0 && (isDriverBound || rawRow)) {
                        // 共存模式: 拿 basicDataValues 所有值 + rawRow 所有值 候选, 找第一个 String(v) == item.code 的
                        const candidates: any[] = [];
                        if (rowBdv) {
                          for (const k of Object.keys(rowBdv)) candidates.push(rowBdv[k]);
                        }
                        for (const k of Object.keys(rawRow)) candidates.push(rawRow[k]);
                        for (const c of candidates) {
                          if (c == null) continue;
                          const cs = String(typeof c === 'object' && Array.isArray(c) && c.length > 0 ? c[0] : c).trim();
                          const found = lfItems.find(it => it.code === cs);
                          if (found) { lfItem = found; break; }
                        }
                      }
                      // LIST_FORMULA 行: 把 config_item 属性暴露到 row, 让用户的非 LIST_FORMULA 字段
                      // 用常见命名(代码/名称/序号/默认值)直接取值, 不必另配 basic_data_path.
                      // V207 (2026-05-19): "用户输入覆盖" 仅当 rawRow 字段值真有意义 — null/undefined/'' 不该
                      // 覆盖 lfItem 提供的映射值. 否则 driver-bound 场景下 autoSave 落库的空行(N 行 row=null)
                      // 会把工序代码/材质代码等 LIST_FORMULA condition 依赖的字段擦成 null,
                      // condition `[工序代码] = 'MRO-AS-0001'` 求值失败 → branches 全不命中 → "—".
                      const rawRowNonEmpty = rawRow ? Object.fromEntries(
                        Object.entries(rawRow).filter(([_, v]) => v !== null && v !== undefined && v !== '')
                      ) : {};
                      const baseRow = lfItem ? {
                        // 英文 keys
                        code: lfItem.code,
                        name: lfItem.name,
                        sortOrder: lfItem.sortOrder,
                        sort_order: lfItem.sortOrder,
                        default_value: lfItem.defaultValue,
                        defaultValue: lfItem.defaultValue,
                        // 常用中文别名 (匹配用户字段命名习惯)
                        '代码': lfItem.code,
                        '编码': lfItem.code,
                        '名称': lfItem.name,
                        '名字': lfItem.name,
                        '工序代码': lfItem.code,
                        '工序': lfItem.name,
                        '工序名': lfItem.name,
                        '材质代码': lfItem.code,
                        '材质': lfItem.name,
                        '材质名称': lfItem.name,
                        '料号': lfItem.code,
                        '名称_代码': lfItem.code,
                        '序号': i + 1,            // 行号 (1-based, 用户视角更友好)
                        '默认值': lfItem.defaultValue,
                        ...rawRowNonEmpty,         // 用户真实输入覆盖, null/undefined/'' 不动
                      } : rawRow;
                      // Phase4 Task3: rowKey 对齐后端(FormulaCalculator.computeRowKey) — 用于 formulaResults 查表 + 编辑回写。
                      const driverRowForKey = (isDriverBound ? activeDriverExpansion!.rows[i]?.driverRow : undefined) ?? activeSnap?.driverRows[i] ?? rawRow;
                      const rowKey = useSnapEdit ? computeRowKey(activeRowKeyFields, driverRowForKey, i) : String(i);
                      return {
                        row: fillFixedDefaults(activeComponent.fields, baseRow),
                        rowIndex: i,
                        rowKey,
                        basicDataValues: isDriverBound ? activeDriverExpansion!.rows[i]?.basicDataValues : undefined,
                        driverRow: isDriverBound ? activeDriverExpansion!.rows[i]?.driverRow : undefined,
                        isDriverBound,
                        isListFormulaBound,
                        // LIST_FORMULA 上下文
                        listFormulaItem: lfItem,
                        listFormulaField,
                      };
                    });
                    // 2026-05-17 累加公式: 预先按 row_index 顺序求值, 把上一行 is_subtotal 字段值
                    // 传给下一行作为 previous_row_subtotal token 的求值上下文.
                    // 单 row 场景(无累加 token)行为不变 — previousRowSubtotal 仅 token 命中时取.
                    const subtotalFieldName = activeComponent.fields?.find((f: any) => f.is_subtotal)?.name;
                    const preComputedCaches: Array<Record<string, number | null>> = [];
                    let prevRowSubtotal: number | undefined = undefined;
                    for (const r of effectiveRows) {
                      // Phase4 Task3: 报价侧优先读快照 formulaResults[rowKey](真零计算);
                      // 缺(无快照/新行/LIST_FORMULA 字符串公式未进 formulaResults)时 computeAllFormulas 兜底(防漂移)。
                      const snapFormula = useSnapEdit ? activeSnap?.formula.get(r.rowKey) : undefined;
                      const cache = (snapFormula && Object.keys(snapFormula).length > 0)
                        ? (snapFormula as Record<string, number | null>)
                        : computeAllFormulas(
                            activeComponent, r.row, allComponentSubtotals,
                            undefined, undefined, item.productPartNo, r.basicDataValues,
                            prevRowSubtotal, globalVariableDefs,
                          );
                      preComputedCaches.push(cache);
                      if (subtotalFieldName && typeof cache[subtotalFieldName] === 'number') {
                        prevRowSubtotal = cache[subtotalFieldName] as number;
                      }
                    }
                    const withCache = effectiveRows.map((er, idx) => ({ ...er, formulaCache: preComputedCaches[idx] }));
                    const treeCfg = activeComponent.treeConfig;
                    if (!treeCfg?.idField || !treeCfg?.parentField) {
                      // 非树表:原样平铺(行为零变化)
                      return withCache.map((r) => ({ ...r, _depth: 0, _hasChildren: false, _nodeKey: '' }));
                    }
                    const idFieldDef = activeComponent.fields.find(f => (f.name || (f as any).key) === treeCfg.idField);
                    const parentFieldDef = activeComponent.fields.find(f => (f.name || (f as any).key) === treeCfg.parentField);
                    const keyPrefix = activeComponent.componentId || activeComponent.tabName || 'tree';
                    const laid = layoutTreeRows(
                      withCache,
                      (it) => idFieldDef ? resolveTreeKey(idFieldDef, it.row, it.basicDataValues, bnfDriverLookupKey) : null,
                      (it) => parentFieldDef ? resolveTreeKey(parentFieldDef, it.row, it.basicDataValues, bnfDriverLookupKey) : null,
                      keyPrefix,
                    );
                    const defExp = treeCfg.defaultExpanded ?? true;
                    const collapsed = treeCollapse.collapsedSet(Object.values(laid.nodeKeyByIndex), defExp);
                    return laid.rows
                      .filter(r => !isTreeRowHidden(r.originalIndex, laid.parentIndexByIndex, laid.nodeKeyByIndex, collapsed))
                      .map(r => ({ ...r.item, _depth: r.depth, _hasChildren: r.hasChildren, _nodeKey: r.nodeKey }));
                  })().map(({ row, rowIndex, rowKey, basicDataValues, isDriverBound, isListFormulaBound, formulaCache, listFormulaItem, listFormulaField, _depth, _hasChildren, _nodeKey }) => {
                    return (
                    <tr key={rowIndex} style={(row._preset || isDriverBound) ? { background: '#fafafa' } : undefined}>
                      {activeComponent.fields.map(field => {
                        const key = field.name || field.key || '';
                        const loadingKey = `${activeComponentDataIndex}-${rowIndex}-${field.name}`;
                        const isRequiredEmpty =
                          field.field_type === 'DATA_SOURCE' &&
                          field.is_required &&
                          row[key] == null;
                        // ElementPriceHint: INPUT_NUMBER 类型元素单价字段的特殊提示（编辑页专属）
                        const elementName: string | undefined = row.element_name || undefined;
                        const isUnitPriceField =
                          field.is_amount === true ||
                          key === 'unit_price' ||
                          key === 'element_actual_unit_price';
                        const showElementHint = !!(elementName && isUnitPriceField
                          && (field.field_type === 'INPUT_NUMBER' || field.field_type === 'INPUT'));

                        const cellCtx: CellContext = {
                          basicDataValues,
                          pathCacheState: pathCacheState ?? {},
                          formulaCache,
                          partNo: item.productPartNo,
                          activeComponent,
                          activeDriverExpansion,
                          isListFormulaBound,
                          isDriverBound,
                          listFormulaItem,
                          listFormulaField,
                          configTemplates,
                          globalVariableDefs,
                          dsLoading,
                          dsErrors,
                          dsStateKey: `${activeComponentDataIndex}-${rowIndex}`,
                          onCellChange: (ri, k, val) => handleRowChange(activeComponentDataIndex, ri, k, val),
                          onCellBlur: (ri, k) => {
                            handleInputBlur(activeComponentDataIndex, ri, k);
                            // Phase4 Task3: 报价侧用户输入字段 onBlur → 写快照 editRows(替代仅靠 autosave 写 row_data)。
                            // 仅 INPUT* 类型(FORMULA/BASIC_DATA/DATA_SOURCE/FIXED 不在此触发); row[k] 为最新受控值。
                            const ft = field.field_type;
                            const isUserInputField = ft === 'INPUT' || ft === 'INPUT_TEXT' || ft === 'INPUT_NUMBER';
                            if (useSnapEdit && isUserInputField && activeComponent.componentId) {
                              handleSnapshotCellEdit(activeComponent.componentId, rowKey, k, row[k]);
                            }
                          },
                        };
                        const cellInner = showElementHint ? (
                          <div style={{ display: 'flex', alignItems: 'center', gap: 0, flexWrap: 'nowrap' }}>
                            <ComponentCell
                              field={field}
                              row={row}
                              rowIndex={rowIndex}
                              fieldKey={key}
                              readonly={false}
                              context={cellCtx}
                            />
                            <ElementPriceHint elementName={elementName!} />
                          </div>
                        ) : (
                          <ComponentCell
                            field={field}
                            row={row}
                            rowIndex={rowIndex}
                            fieldKey={key}
                            readonly={false}
                            context={cellCtx}
                          />
                        );
                        const isFirstField = activeComponent.fields[0] === field;
                        const treeOn = !!(activeComponent.treeConfig?.idField && activeComponent.treeConfig?.parentField);
                        return (
                          <td
                            key={key}
                            className={[
                              field.field_type === 'FORMULA' ? 'qt-formula-cell' : '',
                              field.field_type === 'LIST_FORMULA' ? 'qt-formula-cell' : '',
                              isRequiredEmpty ? 'qt-ds-required-empty' : '',
                            ].filter(Boolean).join(' ') || undefined}
                          >
                            {isFirstField && treeOn ? (
                              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                                <span style={{ display: 'inline-block', width: (_depth ?? 0) * 16 }} />
                                {_hasChildren ? (
                                  <button type="button" onClick={() => treeCollapse.toggle(_nodeKey)}
                                    style={{ border: 'none', background: 'none', cursor: 'pointer', fontSize: 10, width: 14, padding: 0, color: '#888' }} title="展开/折叠">
                                    {treeCollapse.isCollapsed(_nodeKey, activeComponent.treeConfig!.defaultExpanded ?? true) ? '▶' : '▼'}
                                  </button>
                                ) : (<span style={{ display: 'inline-block', width: 14 }} />)}
                                {cellInner}
                              </span>
                            ) : cellInner}
                          </td>
                        );
                      })}
                      <td style={{ textAlign: 'center' }}>
                        {row._preset ? (
                          <span title="固定行，不可删除" style={{ color: '#ccc', fontSize: 12, cursor: 'default' }}>🔒</span>
                        ) : isDriverBound ? (
                          <span title="基础数据自动展开行，不可删除（请在基础数据导入侧调整）" style={{ color: '#ccc', fontSize: 12, cursor: 'default' }}>🔗</span>
                        ) : (
                          <button
                            type="button"
                            onClick={() => handleDeleteRow(activeComponentDataIndex, rowIndex)}
                            style={{
                              background: 'none',
                              border: 'none',
                              color: '#ff4d4f',
                              cursor: 'pointer',
                              fontSize: 14,
                              padding: '0 4px',
                            }}
                            title="删除行"
                          >
                            ✕
                          </button>
                        )}
                      </td>
                    </tr>
                    );
                  })}
                </tbody>
                {/* Tab subtotal row */}
                {activeComponent.fields.some(f => f.is_subtotal) && (
                  <tfoot>
                    <tr className="qt-subtotal-row">
                      {activeComponent.fields.map((field, fi) => {
                        if (field.is_subtotal) {
                          const tabSubtotal = allComponentSubtotals[activeComponent.componentCode]
                            ?? allComponentSubtotals[activeComponent.tabName]
                            ?? 0;
                          return (
                            <td key={field.name || fi} className="qt-subtotal-cell">
                              {formatCurrency(tabSubtotal)}
                            </td>
                          );
                        }
                        if (fi === 0) {
                          return <td key={field.name || fi} className="qt-subtotal-label-cell">小计</td>;
                        }
                        return <td key={field.name || fi} />;
                      })}
                      <td />
                    </tr>
                  </tfoot>
                )}
              </table>
              <button
                className="qt-add-row-btn"
                type="button"
                onClick={() => handleAddRow(activeComponentDataIndex)}
              >
                + 添加行
              </button>
            </div>
          )}
        </div>
      ) : (
        // No component data — message instead of placeholder tabs
        <div className="qt-no-component-data">
          请通过添加产品选择模板后自动加载组件结构
        </div>
      )}

      {/* Subtotal Bar */}
      <div className="qt-subtotal-bar">
        <span className="qt-subtotal-label">产品小计</span>
        <span className="qt-subtotal-value">
          {formatCurrency(
            item.componentData.length > 0
              ? computeProductSubtotal(item, driverExpansions, customerId)
              : item.subtotal
          )}
        </span>
      </div>
    </div>
  );
};

// ─── QuotationStep2 ───────────────────────────────────────────────────────────

const QuotationStep2: React.FC<QuotationStep2Props> = ({
  lineItems,
  onAddProduct,
  onAddConfigured,
  onAddBatch,
  customerTemplateId,
  costingCardTemplateId,
  onRemoveProduct,
  onUpdateLineItem,
  customerId,
  quotationId,
  driftDetection,
  onRefreshQuotation,
  quoteCardStructure,
  costingCardStructure,
}) => {
  // 两级 tab：左侧 mainTab（报价单 / 核价单 / 比对视图），右侧 viewType（产品卡片 / Excel视图）。
  // 比对视图为单一展示，无 viewType 切换。
  const [mainTab, setMainTab] = useState<'quote' | 'costing' | 'comparison'>('quote');
  const [viewType, setViewType] = useState<'card' | 'excel'>('card');
  const [refreshing, setRefreshing] = useState(false);
  // autoPopulating state 保留 — UI(空状态文案、loading)仍在用,但实际触发已移除。
  // 父组件 QuotationWizard 已接管 autoPopulate(L631-668),Step2 内的重复实现会导致
  // 同 URL 含 ?autoPopulate=1 时 Wizard + Step2 各 fetch+append 一次,产生 2× 重复 lineItem,
  // 落库后表现为每个料号在 quotation_line_item 表里有 2 条同 partNo 的记录(134ms 间隔)。
  // 删除本 effect 后 autoPopulate 单一入口在 Wizard,不再双 append。
  const [autoPopulating, _setAutoPopulating] = useState(false);
  // 留个 setter 给可能的外部场景(目前未用); _setAutoPopulating 命名以表示有意未消费 var-warn
  void _setAutoPopulating;
  const { user } = useAuthStore();
  const isSalesRep = user?.role === 'SALES_REP';

  // 动态 key 全局变量定义字典 — 供 formulaEngine 运行时 path 重写使用
  // 空 map = 动态 key token 兜底 0 (旧行为); list() 失败时同样兜底 0 不影响静态 key 场景
  const [gvDefs, setGvDefs] = useState<Record<string, GlobalVariableDefinition>>({});
  useEffect(() => {
    globalVariableService.list()
      .then((res: any) => {
        const arr: GlobalVariableDefinition[] = Array.isArray(res) ? res
          : Array.isArray(res?.data) ? res.data
          : [];
        const map: Record<string, GlobalVariableDefinition> = {};
        for (const d of arr) { if (d?.code) map[d.code] = d; }
        setGvDefs(map);
      })
      .catch(() => setGvDefs({}));
  }, []);

  // BNF 路径异步求值缓存(Phase A4)— 公式含 path token 时,后端按 (partNo, path) 求值后写入模块级 cache
  // ProductCard 子组件中的 evaluateExpression 在不传 pathCache 时回退到模块级(setGlobalPathCache),
  // 此处保留 hook 调用以触发后台求值 + 组件 re-render
  // 2026-05-20: 接收返值供 LIST_FORMULA cell BNF path fallback 使用 (driver expand 注入驱动列谓词查空时)
  const quotationPathCache = usePathFormulaCache(lineItems, customerId, gvDefs);
  // 核价单视图同样需要按 costing 模板下的 path token 求值；hook 内部基于 (partNo, path) 缓存，
  // 与 quote 侧调用结果合并到同一全局 cache，互不冲突
  // 调用位置在 costingLineItems 声明之后

  // 报价模板 componentsSnapshot — 用来在「报价单」视图过滤掉核价模板独有的组件
  // （核价单卡片视图编辑时会把核价模板组件 union-merge 进底层 lineItems[i].componentData，
  //  报价单视图直接读 componentData 会把核价模板的 tab 也漏出来 — AP-19）
  const [quoteTemplateComponentIds, setQuoteTemplateComponentIds] = useState<Set<string> | null>(null);
  useEffect(() => {
    // Phase4 Task5: 有报价结构快照时直接取其 tabs 的 componentId 集合, 不再 GET 报价模板。
    if (quoteCardStructure && Array.isArray(quoteCardStructure.tabs) && quoteCardStructure.tabs.length > 0) {
      const ids = new Set<string>();
      for (const t of quoteCardStructure.tabs) { if (t.componentId) ids.add(String(t.componentId)); }
      setQuoteTemplateComponentIds(ids);
      return;
    }
    if (!customerTemplateId) {
      setQuoteTemplateComponentIds(null);
      return;
    }
    templateService.getByIdCached(customerTemplateId)
      .then((res: any) => {
        const tmpl = res.data;
        let snapshot: any[] = [];
        try {
          snapshot = tmpl?.componentsSnapshot
            ? (typeof tmpl.componentsSnapshot === 'string'
                ? JSON.parse(tmpl.componentsSnapshot)
                : tmpl.componentsSnapshot)
            : [];
        } catch { snapshot = []; }
        const ids = new Set<string>();
        if (Array.isArray(snapshot)) {
          for (const sc of snapshot) {
            const cid = sc?.componentId || sc?.component_id;
            if (cid) ids.add(String(cid));
          }
        }
        setQuoteTemplateComponentIds(ids);
      })
      .catch(() => setQuoteTemplateComponentIds(new Set()));
  }, [customerTemplateId, quoteCardStructure]);

  // 「报价单」视图过滤后的 lineItems：
  //   1. 隐藏 compositeType='PART' (组合产品子件) — 父卡片内 Tab 通过聚合视图展示子件数据
  //   2. componentData 仅保留 quoteTemplateComponentIds 命中的组件
  //     （快照拉到之前先放行 — 否则首屏会瞬间显示空 ProductCard）
  const quoteLineItems = React.useMemo<LineItem[]>(() => {
    const visible = lineItems.filter(li => li.compositeType !== 'PART');
    if (!quoteTemplateComponentIds || quoteTemplateComponentIds.size === 0) return visible;
    return visible.map(li => {
      const filtered = (li.componentData || []).filter(cd => {
        if (!cd?.componentId) return true; // 兼容老数据：没有 componentId 的不过滤
        return quoteTemplateComponentIds.has(String(cd.componentId));
      });
      // 长度一致就直接返回原对象（避免引用变化触发不必要的下游重渲染）
      if (filtered.length === li.componentData.length) return li;
      return { ...li, componentData: filtered };
    });
  }, [lineItems, quoteTemplateComponentIds]);

  // V72：核价模板 componentsSnapshot — 「核价单」视图按这套组件渲染产品卡片
  // 用 ref+state 缓存模板拉取结果，避免 mainTab 切换时重复发请求
  const [costingTemplateSnapshot, setCostingTemplateSnapshot] = useState<any[] | null>(null);
  const [costingTemplateProductAttrs, setCostingTemplateProductAttrs] = useState<any[] | null>(null);
  const [costingTemplateLoading, setCostingTemplateLoading] = useState(false);
  useEffect(() => {
    if (!costingCardTemplateId) {
      setCostingTemplateSnapshot(null);
      setCostingTemplateProductAttrs(null);
      return;
    }
    setCostingTemplateLoading(true);
    templateService.getByIdCached(costingCardTemplateId)
      .then((res: any) => {
        const tmpl = res.data;
        let snapshot: any[] = [];
        try {
          snapshot = tmpl?.componentsSnapshot
            ? (typeof tmpl.componentsSnapshot === 'string'
                ? JSON.parse(tmpl.componentsSnapshot)
                : tmpl.componentsSnapshot)
            : [];
        } catch { snapshot = []; }
        setCostingTemplateSnapshot(Array.isArray(snapshot) ? snapshot : []);
        setCostingTemplateProductAttrs(Array.isArray(tmpl?.productAttributes) ? tmpl.productAttributes : []);
      })
      .catch(() => {
        setCostingTemplateSnapshot([]);
        setCostingTemplateProductAttrs([]);
      })
      .finally(() => setCostingTemplateLoading(false));
  }, [costingCardTemplateId]);

  // 把 customerTemplate 视图下的 lineItem 映射成 costingTemplate 视图下的 lineItem：
  // 同 productId / productPartNo 行 → 复用产品身份（保持顺序、数量与"报价单"一致）；
  // componentData → 按 costingTemplate.componentsSnapshot 重建（rows 沿用底层 lineItem.componentData 中 componentId 命中的；否则空行）
  const costingLineItems = React.useMemo<LineItem[]>(() => {
    // 隐藏 compositeType='PART' (与 quoteLineItems 一致)
    const visible = lineItems.filter(li => li.compositeType !== 'PART');
    if (!costingTemplateSnapshot || costingTemplateSnapshot.length === 0) return visible;
    const buildField = (f: any): ComponentField => ({
      name: f.name || f.key || '',
      field_type: normalizeFieldType(f.field_type || f.type || ''),
      content: f.content,
      is_amount: f.is_amount,
      is_subtotal: f.is_subtotal,
      is_required: f.is_required,
      formula_name: f.formula_name,
      datasource_binding: f.datasource_binding,
      basic_data_path: f.basic_data_path,
      global_variable_code: f.global_variable_code,
      default_source: f.default_source,
      sort_order: f.sort_order,
      label: f.label || f.name || '',
      key: f.name || f.key || '',
    });
    return visible.map(li => {
      const baseByCompId = new Map<string, ComponentDataItem>();
      (li.componentData || []).forEach(cd => {
        if (cd.componentId) baseByCompId.set(cd.componentId, cd);
      });
      const newComponentData: ComponentDataItem[] = costingTemplateSnapshot.map((sc: any) => {
        const componentId = sc.componentId || sc.component_id || '';
        const matched = componentId ? baseByCompId.get(componentId) : undefined;
        const fields: ComponentField[] = (sc.fields || []).map(buildField);
        const formulas: ComponentFormula[] = (sc.formulas || []).map((fm: any) => ({
          name: fm.name || '',
          expression: Array.isArray(fm.expression) ? fm.expression : [],
          result_type: fm.result_type,
        }));
        const rows = matched?.rows && matched.rows.length > 0 ? matched.rows : [{}];
        return {
          componentId,
          componentCode: sc.componentCode || sc.component_code || matched?.componentCode || '',
          componentType: sc.component_type || sc.componentType || matched?.componentType || 'NORMAL',
          tabName: sc.tabName || sc.tab_name || '',
          fields,
          formulas,
          rows,
          subtotal: matched?.subtotal || 0,
          dataDriverPath: sc.data_driver_path || sc.dataDriverPath || matched?.dataDriverPath,
        } as ComponentDataItem;
      });
      // productAttributes 也一并替换为核价模板的 schema（产品属性区域显示与核价模板一致）
      const productAttributes = (costingTemplateProductAttrs || []).map((a: any) => ({
        name: a.name,
        field_type: a.field_type || a.type,
        required: !!a.required,
        default_value: a.default_value,
        source: a.source,
      }));
      return {
        ...li,
        componentData: newComponentData,
        productAttributes,
      };
    });
  }, [lineItems, costingTemplateSnapshot, costingTemplateProductAttrs]);

  // 核价单视图的产品卡片编辑回写：
  // 1. 收到的 update 是基于 costingLineItems[index] 的 partial（来自 ProductCard onUpdate）
  // 2. 如果改的是 componentData：把 costing 视图下的 componentData 按 componentId 合并回底层 lineItems[index].componentData（命中替换 / 缺失追加），未命中的 quote 视图组件保持不动 → 双视图共享同一份持久化数据
  // 3. 如果改的是 productAttributeValues / productAttributes 等行外字段：直接落到底层 lineItem
  const handleUpdateCostingLineItem = React.useCallback((index: number, updater: LineItemUpdater) => {
    if (!costingLineItems[index]) return;
    const partial: Partial<LineItem> = typeof updater === 'function'
      ? (updater as (prev: LineItem) => Partial<LineItem>)(costingLineItems[index])
      : updater;
    if (!partial) return;
    if (partial.componentData) {
      const costingNew = partial.componentData;
      onUpdateLineItem(index, (prev: LineItem) => {
        const baseList = Array.isArray(prev.componentData) ? [...prev.componentData] : [];
        const baseIdx = new Map<string, number>();
        baseList.forEach((cd, i) => { if (cd.componentId) baseIdx.set(cd.componentId, i); });
        costingNew.forEach(cn => {
          if (!cn.componentId) return;
          const at = baseIdx.get(cn.componentId);
          if (at != null) {
            baseList[at] = { ...baseList[at], ...cn };
          } else {
            // costing 模板独有组件 → 追加到底层 componentData，让保存时一并持久化
            baseList.push(cn);
            baseIdx.set(cn.componentId, baseList.length - 1);
          }
        });
        const rest = { ...partial };
        delete rest.componentData;
        return { componentData: baseList, ...rest };
      });
      return;
    }
    onUpdateLineItem(index, partial);
  }, [costingLineItems, onUpdateLineItem]);

  // 报价单视图的产品卡片编辑回写（同核价单逻辑，方向相反）：
  // 1. 收到的 update 基于 quoteLineItems[index] —— 它是 lineItems[index] 用 quoteTemplateComponentIds 过滤后的子集
  // 2. ProductCard 内部 onUpdate 回调按"过滤后子集"的位置索引（tabIndex）操作 componentData，
  //    若直接交给 handleUpdateLineItem，updater 会被 prev=完整 lineItem 调用 → 索引错位
  // 3. 这里在过滤子集上跑 updater 算出 partial.componentData，再按 componentId union-merge 回底层完整 componentData
  const handleUpdateQuoteLineItem = React.useCallback((index: number, updater: LineItemUpdater) => {
    if (!quoteLineItems[index]) return;
    const partial: Partial<LineItem> = typeof updater === 'function'
      ? (updater as (prev: LineItem) => Partial<LineItem>)(quoteLineItems[index])
      : updater;
    if (!partial) return;
    if (partial.componentData) {
      const quoteNew = partial.componentData;
      onUpdateLineItem(index, (prev: LineItem) => {
        const baseList = Array.isArray(prev.componentData) ? [...prev.componentData] : [];
        const baseIdx = new Map<string, number>();
        baseList.forEach((cd, i) => { if (cd.componentId) baseIdx.set(cd.componentId, i); });
        quoteNew.forEach(qn => {
          if (!qn.componentId) return;
          const at = baseIdx.get(qn.componentId);
          if (at != null) {
            baseList[at] = { ...baseList[at], ...qn };
          } else {
            // 报价模板独有组件（罕见场景，通常报价模板组件已在 baseList 里）→ 追加
            baseList.push(qn);
            baseIdx.set(qn.componentId, baseList.length - 1);
          }
        });
        const rest = { ...partial };
        delete rest.componentData;
        return { componentData: baseList, ...rest };
      });
      return;
    }
    onUpdateLineItem(index, partial);
  }, [quoteLineItems, onUpdateLineItem]);

  // 核价单视图的 path token 求值缓存（与上方 quote 侧 hook 调用合并到同一 globalPathCache）
  usePathFormulaCache(costingLineItems, customerId, gvDefs);

  // Y1.5: 行驱动展开 — 含 dataDriverPath 的组件按后端返回的 N 行渲染,BASIC_DATA 值直接来自此 hook
  // 报价单卡片所需的展开（按 customerTemplate 视图下的 componentData 收集）
  // Task 8 渲染脱钩: 当所有行已有值快照时, 从快照构造 expansions 并停掉 batch-expand(传 EMPTY_LINEITEMS);
  // 否则回退实时 batch-expand(旧链路, 兼容尚未生成快照的存量报价单)。报价/核价两侧独立判定。
  const useSnapQuote = lineItems.length > 0 && lineItems.every(li => !!li.quoteCardValues);
  const useSnapCosting = costingLineItems.length > 0 && costingLineItems.every(li => !!li.costingCardValues);
  // 2026-05-19 修: useDriverExpansions 返 {cache, invalidate} 而非纯 Map; 必须解构 .cache
  const { cache: driverExpansionsQuote } = useDriverExpansions(useSnapQuote ? EMPTY_LINEITEMS : lineItems, customerId, quotationId);
  // 核价单卡片所需的展开（按 costingTemplate 视图下的 componentData 收集）
  const { cache: driverExpansionsCosting } = useDriverExpansions(useSnapCosting ? EMPTY_LINEITEMS : costingLineItems, customerId, quotationId);
  // 快照模式: 从行级值快照构造 expansions(键与渲染查找一致)
  const snapExpansionsQuote = React.useMemo(
    () => (useSnapQuote ? buildSnapshotExpansions(lineItems, 'QUOTE', customerId) : {}),
    [lineItems, useSnapQuote, customerId],
  );
  const snapExpansionsCosting = React.useMemo(
    () => (useSnapCosting ? buildSnapshotExpansions(costingLineItems, 'COSTING', customerId) : {}),
    [costingLineItems, useSnapCosting, customerId],
  );
  // 合并：实时(回退) + 快照(优先, 后写入); 同 key 含 componentId, 两侧不互覆盖
  const driverExpansions = React.useMemo(() => ({
    ...driverExpansionsQuote,
    ...driverExpansionsCosting,
    ...snapExpansionsQuote,
    ...snapExpansionsCosting,
  }), [driverExpansionsQuote, driverExpansionsCosting, snapExpansionsQuote, snapExpansionsCosting]);

  // LIST_FORMULA (Phase B): 拉 config_template 详情 (含 categories + items)
  const configTemplatesQuote = useConfigTemplates(lineItems);
  const configTemplatesCosting = useConfigTemplates(costingLineItems);
  const configTemplates = React.useMemo(() => ({
    ...configTemplatesQuote,
    ...configTemplatesCosting,
  }), [configTemplatesQuote, configTemplatesCosting]);

  const handleRefreshVersions = async () => {
    if (!quotationId) return;
    setRefreshing(true);
    try {
      await quotationDriftService.refreshVersions(quotationId);
      message.success('已更新至最新版本基础数据');
      onRefreshQuotation?.();
    } catch {
      message.error('刷新版本失败，请稍后重试');
    } finally {
      setRefreshing(false);
    }
  };

  // 构建漂移横幅文案
  const buildDriftMessage = () => {
    if (!driftDetection?.driftedRecords?.length) return '基础数据已更新，部分版本已过期';
    const parts = driftDetection.driftedRecords.map((r) =>
      r.displayMessage || `${r.tableName} 升至 v${r.currentVersion}，原 v${r.referencedVersion} 已过期`
    );
    return `基础数据已更新（${parts.join('；')}）`;
  };

  return (
    <div>
      {/* 漂移横幅 */}
      {driftDetection?.hasDrift && (
        <Alert
          type="warning"
          showIcon
          style={{ marginBottom: 16 }}
          message={buildDriftMessage()}
          action={
            isSalesRep ? (
              <Button
                size="small"
                type="primary"
                loading={refreshing}
                onClick={handleRefreshVersions}
              >
                使用最新版本
              </Button>
            ) : undefined
          }
        />
      )}

      {/* Step Header — 两级 tab：mainTab 左、viewType 右；comparison 模式隐藏右侧 */}
      <div className="qt-step2-header">
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
          <Segmented
            options={[
              { label: '📝 报价单', value: 'quote' },
              { label: '📊 核价单', value: 'costing', disabled: !quotationId },
              { label: '📈 比对视图', value: 'comparison', disabled: !quotationId },
            ]}
            value={mainTab}
            onChange={v => setMainTab(v as any)}
            size="small"
          />
          {mainTab !== 'comparison' && (
            <span
              style={{
                padding: '2px 10px',
                borderRadius: 12,
                background: '#fff7e6',
                color: '#d46b08',
                fontSize: 12,
              }}
              title="此区域内的字段可以由用户直接填写"
            >
              📝 用户填写区，可编辑
            </span>
          )}
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          {mainTab !== 'comparison' && (
            <>
              <span style={{ fontSize: 12, color: '#8c8c8c' }}>视图:</span>
              <Segmented
                options={[
                  { label: '📋 产品卡片', value: 'card' },
                  { label: '📑 Excel 视图', value: 'excel' },
                ]}
                value={viewType}
                onChange={v => setViewType(v as any)}
                size="small"
              />
            </>
          )}
          {mainTab === 'quote' && (
            <Dropdown
              menu={{
                items: [
                  {
                    key: 'classic',
                    label: '从已有产品添加',
                    icon: <DatabaseOutlined />,
                    onClick: () => onAddProduct?.(),
                  },
                  {
                    key: 'configure',
                    // B.4: 模板未选时禁用 + tooltip 提示, 避免新建 line_item 缺 template_id
                    // (后端有 quotation.customer_template_id 全局兜底, 但缺模板 = 卡片 / Excel 视图都无渲染源)
                    label: customerTemplateId
                      ? '选配添加'
                      : <span title="请先回 Step1 选择报价模板">选配添加</span>,
                    icon: <SettingOutlined />,
                    disabled: !customerTemplateId,
                    onClick: () => {
                      if (!customerTemplateId) {
                        message.warning('请先回 Step1 选择报价模板,选配产品才能正确渲染卡片');
                        return;
                      }
                      onAddConfigured?.();
                    },
                  },
                ],
              }}
            >
              <Button type="primary">
                <PlusOutlined /> 添加产品 <DownOutlined />
              </Button>
            </Dropdown>
          )}
        </div>
      </div>

      {mainTab === 'comparison' && quotationId ? (
        <ComparisonView
          quotationId={quotationId}
          customerId={customerId}
          quoteTemplateId={customerTemplateId}
          costingTemplateId={costingCardTemplateId}
          quoteLineItems={quoteLineItems}
          costingLineItems={costingLineItems}
        />
      ) : mainTab === 'costing' && viewType === 'excel' && quotationId ? (
        // 核价单 — Excel 视图（V73/V74 起按 linkedTemplateId 反查 costing_template 渲染）：
        // 入口 = 报价单的 costingCardTemplateId（核价模板）→ 反查 linked_template_id 命中的 Excel 模板
        <LinkedExcelView
          linkedTemplateId={costingCardTemplateId}
          lineItems={costingLineItems}
          customerId={customerId}
          viewLabel="核价单 Excel 视图"
          templateId={costingCardTemplateId || null}
          quotationId={quotationId}
          side="COSTING"
        />
      ) : mainTab === 'costing' && viewType === 'card' && quotationId ? (
        // 核价单 — 产品卡片视图(V72)：与"报价单卡片视图"产品数量/排序一致，
        // 但卡片内部组件按"核价模板(template_kind='COSTING')"重建。
        // 编辑回写：handleUpdateCostingLineItem 把 componentData 按 componentId 合并回底层 lineItems[index]。
        !costingCardTemplateId ? (
          <Alert
            type="warning"
            showIcon
            message="该报价单未绑定核价模板"
            description="请回到「报价单管理」选择「编辑」后在产品分类抽屉里补选核价模板，或在数据库中通过 quotation.costing_card_template_id 字段配置。"
            style={{ margin: 24 }}
          />
        ) : costingTemplateLoading ? (
          <div style={{ textAlign: 'center', padding: 48, color: '#999' }}>正在加载核价模板…</div>
        ) : (costingTemplateSnapshot && costingTemplateSnapshot.length === 0) ? (
          <Alert
            type="warning"
            showIcon
            message="核价模板未配置任何组件"
            description="请前往「模板配置」打开该核价模板，添加组件并发布后重试。"
            style={{ margin: 24 }}
          />
        ) : lineItems.length === 0 ? (
          <div className="qt-empty-state">
            <div className="qt-empty-icon">📦</div>
            <div style={{ fontSize: 16 }}>暂无产品</div>
            <div style={{ marginTop: 8 }}>请先在「报价单」视图中添加产品</div>
          </div>
        ) : (
          <div className="qt-products-list">
            {costingLineItems.map((item, index) => (
              <ProductCard
                key={item.productId ? `costing-${item.productId}-${index}` : `costing-item-${index}`}
                item={item}
                index={index}
                onRemove={() => onRemoveProduct(index)}
                onUpdate={(data) => handleUpdateCostingLineItem(index, data)}
                customerId={customerId}
                quotationId={quotationId}
                driverExpansions={driverExpansions}
                configTemplates={configTemplates}
                pathCacheState={quotationPathCache}
                globalVariableDefs={gvDefs}
                cardSide="COSTING"
                cardStructure={costingCardStructure}
              />
            ))}
          </div>
        )
      ) : mainTab === 'quote' && viewType === 'excel' ? (
        // 报价单 — Excel 视图（V73/V74 起同样按 linkedTemplateId 反查 costing_template 渲染）：
        // 入口 = 报价单的 customerTemplateId（报价模板）→ 反查 linked_template_id 命中的 Excel 模板
        <LinkedExcelView
          linkedTemplateId={customerTemplateId}
          lineItems={quoteLineItems}
          customerId={customerId}
          viewLabel="报价单 Excel 视图"
          templateId={customerTemplateId || null}
          quotationId={quotationId}
          side="QUOTE"
        />
      ) : lineItems.length === 0 ? (
        <div className="qt-empty-state">
          <div className="qt-empty-icon">{autoPopulating ? '⏳' : '📦'}</div>
          <div style={{ fontSize: 16 }}>
            {autoPopulating ? '正在按已绑定模板自动加载产品...' : '还未添加任何产品'}
          </div>
          {!autoPopulating && (
            <div style={{ marginTop: 8 }}>
              {'点击"+ 添加产品"按钮开始配置'}
            </div>
          )}
        </div>
      ) : (
        <div className="qt-products-list">
          {quoteLineItems.map((item, index) => (
            <ProductCard
              key={item.productId ? `${item.productId}-${index}` : `item-${index}`}
              item={item}
              index={index}
              onRemove={() => onRemoveProduct(index)}
              onUpdate={(data) => handleUpdateQuoteLineItem(index, data)}
              customerId={customerId}
              quotationId={quotationId}
              driverExpansions={driverExpansions}
              configTemplates={configTemplates}
              pathCacheState={quotationPathCache}
              globalVariableDefs={gvDefs}
              cardSide="QUOTE"
              cardStructure={quoteCardStructure}
            />
          ))}
        </div>
      )}
    </div>
  );
};

export { computeProductSubtotal, computeAllFormulas };
export default QuotationStep2;
