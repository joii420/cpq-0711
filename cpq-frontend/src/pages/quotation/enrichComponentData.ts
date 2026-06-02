/**
 * enrichComponentData — 共享的模板快照 enrich 工具
 *
 * 抽出自 QuotationWizard.tsx (L78-L213, 2026-05-20 Bug C 重构)。
 * ReadonlyProductCard.tsx 与 QuotationWizard.tsx 同源使用，保证
 * 详情页 = 编辑页只读版，4 个关键字段在两处均被透传：
 *   datasource_binding / global_variable_code / default_source / sort_order
 *
 * 约束：逻辑零变更，仅移文件，不动 SIMPLE / COMPOSITE 任何行为。
 */
import { templateService } from '../../services/templateService';
import { buildComponentDataFromTemplate } from './BulkImportPartsDrawer';
import type { ComponentDataItem, ComponentField, ComponentFormula } from './QuotationStep2';

function parseJson<T>(value: T | string | null | undefined, fallback: T): T {
  if (value == null) return fallback;
  if (typeof value === 'string') {
    try { return JSON.parse(value) as T; } catch { return fallback; }
  }
  return value;
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
  return 'INPUT';
}

/** snapshot/结构里的 tree_config(snake)或 treeConfig(camel)→ 前端 TreeConfig(无效→undefined) */
function normalizeTreeConfig(raw: any): import('../component/types').TreeConfig | undefined {
  const o = raw?.tree_config ?? raw?.treeConfig ?? raw;
  if (!o || typeof o !== 'object') return undefined;
  const idField = o.idField ?? o.id_field;
  const parentField = o.parentField ?? o.parent_field;
  if (!idField || !parentField) return undefined;
  return {
    idField: String(idField),
    parentField: String(parentField),
    defaultExpanded: o.defaultExpanded ?? o.default_expanded ?? true,
  };
}

/**
 * Template fetch dedupe — 走 service-level Promise-cache(`templateService.getByIdCached`),
 * 与其他报价单组件(ReadonlyProductCard、Step2 等)共享同一个 in-flight Promise → 同 templateId 全局 1 次 HTTP。
 */
function fetchTemplateOnce(templateId: string): Promise<any> {
  return templateService.getByIdCached(templateId);
}

/** Enrich saved componentData with fields/formulas from template snapshot.
 *
 * <p>2026-05-17 修复:savedCompData=[] 时不再直接 return [],而是从模板 snapshot
 * 构建初始 componentData(类似 BulkImport 的 buildLineItemFromTemplate)。这避免
 * 选配创建的 lineItem(后端不写 component_data → 前端 componentData=[])在卡片渲染时
 * 拿不到模板组件结构,导致"卡片中没有显示模板的内容"。
 *
 * <p>Bug C (2026-05-20): 同源化 — 详情页 ReadonlyProductCard 改调此函数，
 * 保证 4 个关键字段 (datasource_binding / global_variable_code / default_source / sort_order)
 * 在详情页也被透传，与编辑页行为一致。
 */
export async function enrichComponentData(
  templateId: string,
  savedCompData: any[],
): Promise<ComponentDataItem[]> {
  if (!templateId) return savedCompData;
  if (savedCompData.length === 0) {
    // 选配创建分支:从模板 snapshot 构建初始 componentData
    try {
      const res = await fetchTemplateOnce(templateId);
      return buildComponentDataFromTemplate(res.data);
    } catch {
      return [];
    }
  }
  // 先把 rowData 字符串解析成 rows，作为最低保障——即使后面 templateSnapshot
  // 拉取失败 / 匹配不到，至少 input 单元格能从 row[key] 拿到值，不会出现
  // "列表行数在但所有单元格空白" 的情况（Bug E）。
  const withRows = savedCompData.map((saved: any) => {
    if (Array.isArray(saved.rows)) return saved;
    const parsed = parseJson<any[]>(saved.rowData, []);
    return { ...saved, rows: Array.isArray(parsed) ? parsed : [] };
  });
  try {
    const res = await fetchTemplateOnce(templateId);
    const tmpl = res.data;
    const snapshot: any[] = parseJson(tmpl.componentsSnapshot, []);

    // 2026-05-19 改: 以 snapshot 为权威, 用 savedCompData 仅回填 row 数据.
    //   原实现按 savedCompData.map 遍历, 模板新增了组件(如组合产品 v1.0→v1.2 补齐
    //   CHILD-PARTS+WEIGHT)时旧报价单不会显现新 Tab — 因 saved 没这两条行.
    //   现在按 snapshot 遍历, 让模板变更后已有报价单自动获得新 Tab(空数据).
    // AP-37 续: 模板允许同 componentId 实例化多次(同组件挂不同 dataDriverPath
    // 形成两个 Tab, 例 v_composite_child_processes 与 mat_process), 此时按 componentId
    // 反查 saved 会让 4 个 snapshot entry 都对到同一条 saved → tabName 被覆盖成相同名
    // (典型现象: 标准/选配-* 两组 Tab 全变成"选配-*"), 进而行数据也彼此污染.
    //   修法: 把 saved 按 componentId 分组成队列, 同 cid 多条按 (cid, tabName) 优先
    //   精确匹配; 匹配后从队列剔除, 保证不重复使用同一条.
    const savedQueueByCid: Map<string, any[]> = new Map();
    const savedByTab: Record<string, any> = {};
    for (const s of withRows) {
      if (s.componentId) {
        if (!savedQueueByCid.has(s.componentId)) savedQueueByCid.set(s.componentId, []);
        savedQueueByCid.get(s.componentId)!.push(s);
      }
      if (s.tabName) savedByTab[s.tabName] = s;
    }

    return snapshot.map((snapshotComp: any) => {
      const snapId = snapshotComp.componentId || snapshotComp.component_id || '';
      const snapTab = snapshotComp.tabName || snapshotComp.tab_name || '';
      let saved: any = {};
      const queue = savedQueueByCid.get(snapId);
      if (queue && queue.length > 0) {
        // 1) (cid, tab) 精确匹配优先
        let idx = queue.findIndex(s => (s.tabName || '') === snapTab);
        // 2) 退回到同 cid 第一条还没被领走的
        if (idx < 0) idx = 0;
        saved = queue.splice(idx, 1)[0] || {};
      } else if (savedByTab[snapTab]) {
        saved = savedByTab[snapTab];
      }

      const fields: ComponentField[] = (snapshotComp.fields || []).map((f: any) => ({
        name: f.name || f.key || '',
        field_type: normalizeFieldType(f.field_type || f.type || ''),
        content: f.content,
        is_amount: f.is_amount,
        is_subtotal: f.is_subtotal,
        is_required: f.is_required,
        formula_name: f.formula_name,
        // Bug C 关键字段透传: 详情页与编辑页同源
        datasource_binding: f.datasource_binding,
        // BASIC_DATA 字段必须带上 basic_data_path —— 渲染分支 / driver 展开 lookup
        // 都靠它，缺了 BASIC_DATA 直接显示"未配置路径"。
        basic_data_path: f.basic_data_path,
        // Bug C 关键字段透传
        global_variable_code: f.global_variable_code,
        default_source: f.default_source,
        // V203/Phase B: LIST_FORMULA 字段的配置 — 缺了 useConfigTemplates 看不到 → 模板永不加载 → 永久"加载中"
        list_formula_config: f.list_formula_config,
        // Bug C 关键字段透传
        sort_order: f.sort_order,
        label: f.label || f.name || '',
        key: f.name || f.key || '',
      }));

      const formulas: ComponentFormula[] = (snapshotComp.formulas || []).map((fm: any) => ({
        name: fm.name || '',
        expression: Array.isArray(fm.expression) ? fm.expression : [],
        result_type: fm.result_type,
      }));

      // Merge: keep saved row data, enrich with fields/formulas from snapshot
      // rowData from backend is a JSON string; rows from fresh add is an array
      // withRows 已保证 saved.rows 存在；空数组时退回 [{}] 至少给一行让 UI 不空白
      const savedRows = Array.isArray(saved.rows) ? saved.rows : [];
      const rows: Record<string, any>[] = savedRows.length > 0 ? savedRows : [{}];

      // 从模板快照补回 componentType 和 dataDriverPath —— 后端 ComponentDataDTO 不保存它们，
      // 不补的话刷新后小计组件会被当成普通 tab 渲染、driver 展开也无法触发（AP-2 续）。
      // AP-37: 同 cid 多实例时 dataDriverPath 区分各 Tab; 历史 saved 可能错配,
      //   一律以 snapshot 为准（snapshot 是 publish 时定格的结构权威）。
      const componentType = snapshotComp.component_type
        || snapshotComp.componentType
        || saved.componentType
        || 'NORMAL';
      const dataDriverPath = snapshotComp.data_driver_path
        || snapshotComp.dataDriverPath
        || saved.dataDriverPath
        || undefined;
      // 结构性字段一律以 snapshot 为权威, saved 只贡献"行数据/小计".
      // 历史报价单可能因前次 bug 把 tabName 写成同名重复(AP-37), 这里强制以模板修复.
      return {
        componentId: snapshotComp.componentId || saved.componentId || '',
        componentCode: snapshotComp.componentCode || saved.componentCode || '',
        componentType,
        tabName: snapshotComp.tabName || snapshotComp.tab_name || saved.tabName || '',
        fields,
        formulas,
        rows,
        subtotal: saved.subtotal || 0,
        dataDriverPath,
        treeConfig: normalizeTreeConfig(snapshotComp),
      } as ComponentDataItem;
    });
  } catch {
    // 模板拉取失败时也至少返回 withRows，保证 input 不会因为 rows=undefined 全空
    return withRows;
  }
}

/**
 * Phase4 Task5 — 从「报价单整份快照结构」(quote_card_structure, structure v2) 同步组装 componentData，
 * **不发任何网络请求**（旁路 enrichComponentData 的 GET /templates）。
 *
 * <p>结构 v2 已冻进全部 config keys（fieldType/basicDataPath/datasourceBinding/globalVariableCode/
 * defaultSource/listFormulaConfig/formulaName/isSubtotal/isAmount/isRequired/sortOrder）+ rowKeyFields，
 * 故组装结果与 enrichComponentData(读模板 componentsSnapshot) 等价。saved rows 按 componentId 队列回填（同 enrich）。
 *
 * <p>无结构 / templateId 不匹配时调用方应回退 enrichComponentData。
 */
export function buildComponentDataFromStructure(
  structure: import('../../services/quotationService').CardStructure,
  savedCompData: any[],
): ComponentDataItem[] {
  const withRows = (savedCompData || []).map((saved: any) => {
    if (Array.isArray(saved.rows)) return saved;
    const parsed = parseJson<any[]>(saved.rowData, []);
    return { ...saved, rows: Array.isArray(parsed) ? parsed : [] };
  });
  // saved 按 componentId 分组队列 + tabName 索引（同 enrichComponentData，AP-37 同 cid 多实例不互污染）
  const savedQueueByCid: Map<string, any[]> = new Map();
  const savedByTab: Record<string, any> = {};
  for (const s of withRows) {
    if (s.componentId) {
      if (!savedQueueByCid.has(s.componentId)) savedQueueByCid.set(s.componentId, []);
      savedQueueByCid.get(s.componentId)!.push(s);
    }
    if (s.tabName) savedByTab[s.tabName] = s;
  }

  return (structure.tabs || []).map((tab) => {
    const snapId = tab.componentId || '';
    const snapTab = tab.tabName || '';
    let saved: any = {};
    const queue = savedQueueByCid.get(snapId);
    if (queue && queue.length > 0) {
      let idx = queue.findIndex(s => (s.tabName || '') === snapTab);
      if (idx < 0) idx = 0;
      saved = queue.splice(idx, 1)[0] || {};
    } else if (savedByTab[snapTab]) {
      saved = savedByTab[snapTab];
    }

    const fields: ComponentField[] = (tab.fields || []).map((f: any) => ({
      name: f.name || '',
      field_type: normalizeFieldType(f.fieldType || ''),
      content: f.defaultValue,
      is_amount: f.isAmount,
      is_subtotal: f.isSubtotal,
      is_required: f.isRequired,
      formula_name: f.formulaName,
      datasource_binding: f.datasourceBinding,
      basic_data_path: f.basicDataPath,
      global_variable_code: f.globalVariableCode,
      default_source: f.defaultSource,
      list_formula_config: f.listFormulaConfig,
      sort_order: f.sortOrder,
      label: f.label || f.name || '',
      key: f.name || '',
    }));

    const formulas: ComponentFormula[] = (tab.formulas || []).map((fm: any) => ({
      name: fm.name || '',
      expression: Array.isArray(fm.expression) ? fm.expression : [],
      result_type: fm.result_type,
    }));

    const savedRows = Array.isArray(saved.rows) ? saved.rows : [];
    const rows: Record<string, any>[] = savedRows.length > 0 ? savedRows : [{}];

    return {
      componentId: snapId || saved.componentId || '',
      // 产品小计=0 修复(2026-06-02): 优先取结构 tab.componentCode(含 __impN 多实例后缀)，
      // 它是 SUBTOTAL 公式 component_subtotal token 的引用键；saved.componentCode 多为空。
      componentCode: tab.componentCode || saved.componentCode || '',
      componentType: tab.componentType || saved.componentType || 'NORMAL',
      tabName: snapTab || saved.tabName || '',
      fields,
      formulas,
      rows,
      subtotal: saved.subtotal || 0,
      dataDriverPath: tab.dataDriverPath || saved.dataDriverPath || undefined,
      treeConfig: normalizeTreeConfig(tab),
    } as ComponentDataItem;
  });
}

/** Phase4 Task5 — 从结构快照取 productAttributes schema（旁路 loadProductAttributes 的 GET /templates）。 */
export function productAttributesFromStructure(
  structure: import('../../services/quotationService').CardStructure,
): NonNullable<import('./QuotationStep2').LineItem['productAttributes']> {
  return (structure.productAttributes || []).map((attr: any) => ({
    name: attr.name || (attr as any).key || '',
    field_type: attr.field_type || (attr as any).fieldType || 'TEXT',
    required: !!attr.required,
    default_value: attr.default_value ?? (attr as any).defaultValue ?? '',
    source: attr.source ?? '',
  }));
}

/**
 * 从模板拉取 productAttributes schema（字段定义列表）。
 * LineItem.productAttributes 是 schema 而非值——后端 SaveDraftRequest 没有这个维度，
 * 刷新后必须从模板再拉一次回填，否则产品卡片"产品属性"区域整块空白（AP-2 续）。
 */
export async function loadProductAttributes(templateId: string): Promise<NonNullable<import('./QuotationStep2').LineItem['productAttributes']>> {
  if (!templateId) return [];
  try {
    const res = await fetchTemplateOnce(templateId);
    const tmpl = res.data;
    const productAttrs: any[] = parseJson(tmpl.productAttributes, []);
    return productAttrs.map((attr: any) => ({
      name: attr.name || attr.key || attr.fieldKey || '',
      field_type: attr.field_type || attr.fieldType || 'TEXT',
      required: !!attr.required,
      default_value: attr.default_value ?? attr.defaultValue ?? '',
      source: attr.source ?? '',
    }));
  } catch {
    return [];
  }
}
