/**
 * BulkImportPartsDrawer —— ⚠️ 组件本体已删，本文件现在只是工具函数的载体。
 *
 * 历史：这里原本是报价单 Step2 的「批量从基础数据导入产品」抽屉
 * （候选料号列表 → 按客户报价模板批量生成 LineItem）。
 *
 * task-260910(F-7)：用户问「这个功能入口在哪」，实查发现
 * `<BulkImportPartsDrawer` 在整个 src/ 零命中 —— 组件本体无人渲染、`export default` 无消费者，
 * Step2 的「+ 添加产品」下拉只有「从已有产品添加」/「选配添加」两项，从来没有批量导入这一项。
 * ⇒ 删组件本体 + `export default` + 只被它用到的 import / Props / hooks。
 *
 * 🚫 文件本身不能删 —— 下面两个 `export function` 仍是活的公共构件：
 *   · buildComponentDataFromTemplate  ← enrichComponentData.ts
 *                                       + templateSnapshot.precision.test.ts / formulaIdCarry.repair0805.test.ts
 *   · buildLineItemFromTemplate       ← QuotationStep2.tsx / QuotationWizard.tsx / AddProductModal.tsx
 *                                       + keyPresenceAuthority.test.ts
 * 同理 `CustomerPartCandidate` 也保留 —— 它是 buildLineItemFromTemplate 的入参类型。
 *
 * 🚫 也不要顺手动 `quotationService.listCustomerPartCandidates`：
 *   删掉的组件不是它唯一的调用方，QuotationWizard.tsx 里「导入报价数据后自动建单」仍在调它（无 UI 列表）。
 *
 * 文件名与 .tsx 扩展名保持不变 —— 三个源文件 + 三个测试按 './BulkImportPartsDrawer' 导入，改名是另一件事。
 */
import type { LineItem, ComponentDataItem, ComponentField, ComponentFormula } from './QuotationStep2';
import { genUUID } from '../../utils/uuid';
import type { DecimalString } from '../../utils/precision';
import { tryParseSnapshotJsonLossless } from '../../utils/losslessJson';
import { parseTemplateComponentsSnapshot } from './templateSnapshot';

/** buildLineItemFromTemplate 的入参类型 —— 后端 CustomerPartCandidateDTO 的前端镜像。
 *  🚫 不要跟着组件一起删：它是仍在服役的工具函数签名的一部分。 */
interface CustomerPartCandidate {
  partNo: string;
  partName?: string;
  unitWeight?: DecimalString;
  weightUnit?: string;
  customerProductNo?: string;
  customerPartName?: string;
  customerDrawingNo?: string;
  baseCurrency?: string;
  /** 生产料号详情。task-260910(B-4/F-5)：换源到
   *  ds_quote_material.production_no → ds_cost_basic_material ∪ ds_cost_detail_material，
   *  字段集与 QuotationDTO.HfPartInfo 对齐（删 statusCode、加 oldMaterialNo）。 */
  hfPartInfo?: {
    partNo?: string;
    partName?: string;
    specification?: string;
    sizeInfo?: string;
    oldMaterialNo?: string;
  };
  quoteCurrency?: string;
  customerSpecific: boolean;
  /** mat_customer_part_mapping.current_version — 后端 V161+ 透传, 用于初始化 LineItem.partVersionLocked */
  currentVersion?: number;
}

// ─── helpers(简化版,只做必要字段映射) ──────────────────────────────────────

function parseJsonSafe<T>(v: T | string | null | undefined, fallback: T): T {
  if (v == null) return fallback;
  if (typeof v === 'string') {
    return tryParseSnapshotJsonLossless<T>(v) ?? fallback;
  }
  return v;
}

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
  return 'INPUT_TEXT';
}

function buildEmptyRow(fields: ComponentField[]): Record<string, any> {
  const row: Record<string, any> = { row_index: 0 };
  for (const f of fields) {
    if (f.field_type === 'FIXED_VALUE') {
      row[f.name] = f.content ?? '';
    } else if (f.field_type === 'FORMULA' || f.field_type === 'BASIC_DATA' || f.field_type === 'DATA_SOURCE') {
      row[f.name] = null;
    }
    // INPUT_TEXT/INPUT_NUMBER/INPUT：**一个键都不写**。
    //
    // 意图与改动前一致（默认值由 resolveInputDefault 在渲染/计算/快照回填/snapshotRows 动态给出，
    // 不在建行写死），但表达方式必须换 —— spec 2026-08-03「键存在即权威」把 `''` 定义为
    // 「用户已定值为空」，此处再写 `''` 等于宣告这些格子已定值，bake effect 与 snapshotRows
    // 的 isKeyUnset 判据会一律跳过 → **新加产品/批量导入的首行默认值永不填充**（汇率/损耗率
    // 一类被公式引用的列会因此算成 0）。键缺失才是「从未定值」的正确表达。
  }
  return row;
}

/** 把 customer-quote 模板 + 单个料号信息 → LineItem(导出供 QuotationStep2 自动展开复用) */
/**
 * 从模板的 componentsSnapshot 构建初始 componentData(每组件 1 行空 / preset_rows).
 *
 * <p>抽出此函数让"选配创建的 lineItem"(QuotationWizard.enrichComponentData) 在
 * savedCompData=[] 时复用,而不是返回空数组 → 卡片渲染无组件结构的 bug.
 *
 * @param tmpl 完整模板对象(GET /templates/{id} 返回的 data)
 */
export function buildComponentDataFromTemplate(tmpl: any): ComponentDataItem[] {
  const componentsSnapshot = parseTemplateComponentsSnapshot(tmpl.componentsSnapshot);
  return componentsSnapshot.map((comp: any) => {
    const fields: ComponentField[] = (comp.fields || []).map((f: any) => ({
      name: f.name || f.key || f.fieldKey || '',
      field_type: normalizeFieldType(f.field_type || f.type || f.fieldType || ''),
      content: f.content,
      is_amount: f.is_amount,
      is_subtotal: f.is_subtotal,
      formula_name: f.formula_name,
      datasource_binding: f.datasource_binding,
      basic_data_path: f.basic_data_path,
      // V109: 全局变量徽章; V190 default_source 统一默认值来源
      global_variable_code: f.global_variable_code,
      default_source: f.default_source,
      // V203/Phase B: LIST_FORMULA 字段的配置
      list_formula_config: f.list_formula_config,
      sort_order: f.sort_order,
      // 单位换算：透传 unit_source_field，供 computeAllFormulas 换算时按同行单位归一
      unit_source_field: f.unit_source_field,
      label: f.label || f.fieldLabel || f.name || '',
      key: f.name || f.key || f.fieldKey || '',
    }));
    const formulas: ComponentFormula[] = (comp.formulas || []).map((fm: any) => ({
      name: fm.name || fm.fieldKey || fm.key || '',
      expression: Array.isArray(fm.expression) ? fm.expression : [],
      result_type: fm.result_type,
    }));
    const presetRows: any[] = comp.preset_rows || comp.presetRows || [];
    const initialRows = presetRows.length > 0
      ? presetRows.map((pr: any, ri: number) => ({
          ...buildEmptyRow(fields),
          ...pr,
          _preset: true,
          row_index: ri,
        }))
      : [buildEmptyRow(fields)];
    const rawFa = comp.formula_assignments || comp.formulaAssignments || {};
    const formulaAssignments: Record<string, string> = typeof rawFa === 'string'
      ? JSON.parse(rawFa) : rawFa;
    const compType = comp.component_type || comp.componentType || 'NORMAL';
    return {
      componentId: comp.component_id || comp.componentId || '',
      componentCode: comp.component_code || comp.componentCode || '',
      componentType: compType,
      tabName: comp.tab_name || comp.tabName || comp.name || 'Tab',
      fields,
      formulas,
      formulaAssignments,
      dataDriverPath: comp.data_driver_path || comp.dataDriverPath || undefined,
      rows: compType !== 'NORMAL' ? [] : initialRows,
      subtotal: '0',
      // task-0721 F2：页签类型属性 + 料号列标识透传（新建产品首次加入报价单场景；
      // 后续保存/刷新会走 enrichComponentData，以 snapshot 为权威覆盖此处初值）
      tabType: comp.tab_type || comp.tabType || undefined,
      partNoField: comp.part_no_field || comp.partNoField || undefined,
      partNameField: comp.part_name_field || comp.partNameField || undefined,
      // task-0729 B10：元素角色字段透传（同上，初值——后续 enrichComponentData 以 snapshot 为权威覆盖）
      elementCodeField: comp.element_code_field || comp.elementCodeField || undefined,
      elementPriceField: comp.element_price_field || comp.elementPriceField || undefined,
      elementCurrencyField: comp.element_currency_field || comp.elementCurrencyField || undefined,
    };
  });
}

export function buildLineItemFromTemplate(tmpl: any, part: CustomerPartCandidate): LineItem {
  const productAttrs: any[] = parseJsonSafe(tmpl.productAttributes, []);

  const productAttributes: LineItem['productAttributes'] = productAttrs.map((attr: any) => ({
    name: attr.name || attr.key || attr.fieldKey || '',
    field_type: attr.field_type || attr.fieldType || 'TEXT',
    required: attr.required ?? false,
    default_value: attr.default_value ?? attr.defaultValue,
    source: attr.source,
  }));

  const productAttributeValues: Record<string, any> = {};
  for (const attr of productAttributes) {
    if (!attr.name) continue;
    productAttributeValues[attr.name] = attr.default_value ?? '';
  }
  // PRD：产品图号属性绑定客户料号映射的 customer_drawing_no——
  // 命名约定上模板里有"图号"字段时自动用 customerDrawingNo 兜底，避免新建报价单还要用户手填一遍。
  // 用户后续若手工修改也会被 productAttributeValues 持久化覆盖。
  if (part.customerDrawingNo
      && Object.prototype.hasOwnProperty.call(productAttributeValues, '图号')
      && !productAttributeValues['图号']) {
    productAttributeValues['图号'] = part.customerDrawingNo;
  }

  const componentData: ComponentDataItem[] = buildComponentDataFromTemplate(tmpl);

  const subtotalFormula: any[] = parseJsonSafe(tmpl.subtotalFormula || tmpl.subtotal_formula, []);

  return {
    // Bug B (2026-05-20): 新建 lineItem 时生成 tempId，用于 driverExpansionKey lineItemId 维度。
    // 保证同报价单内两条相同料号的行各自独立缓存 driver 展开结果，不相互污染。
    tempId: genUUID(),
    productId: '',  // V5 流程不依赖 v3 product 表
    productName: part.partName || part.customerPartName || part.partNo,
    productPartNo: part.partNo,
    // V161+ 修复: 后端 listCandidates 透传 mapping.current_version, 这里直接写入 LineItem.
    // 避免首次从 import 跳转过来 partVersion 缺省 → ImplicitJoinRewriter 不注入版本过滤 → 多版本叠加.
    // 缺省 2000 作为兜底(新料号或 mapping 缺失时 driver 也能查到默认版本数据).
    partVersionLocked: part.currentVersion ?? 2000,
    // PRD：客户视角的产品卡片 — 候选数据里就有 customerPartName / customerProductNo / customerDrawingNo，
    // 这里直接装进 LineItem 字段，让导入后第一次进入编辑页就以"客户料号名称"为主显示。
    // 否则要等保存草稿+刷新走 loadLineItems 路径才能从 mat_customer_part_mapping 反查回来。
    customerPartName: part.customerPartName || '',
    customerProductNo: part.customerProductNo || '',
    customerDrawingNo: part.customerDrawingNo || '',
    // 生产料号管理 视角详情（卡片右侧 popover 用）—— 候选 API 已 LEFT JOIN internal_material 返回
    hfPartInfo: part.hfPartInfo,
    templateId: tmpl.id,
    // task-260910(F-6)：原先这里还设 templateName，供卡片头部「模板: xxx」徽标显示。
    // 用户裁决不需要该徽标 ⇒ 徽标与 LineItem.templateName 字段一并删除。
    // 该字段从未进过 saveDraft payload（payload 只送 templateId），删除不影响持久化。
    productAttributeValues,
    productAttributes,
    componentData,
    subtotal: '0',
    subtotalFormula,
    // 导入来源标记:saveDraft 据此从该料号基础工序 seed 本行 quotation_line_process,
    // 使 [选配-工序列表] 与选配产品渲染一致(选配路径不设此标记,保持"没选工序=空")。
    seedProcessesFromBase: true,
  };
}
