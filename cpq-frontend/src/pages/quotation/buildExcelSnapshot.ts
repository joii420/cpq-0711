/**
 * buildExcelSnapshot.ts
 *
 * 前端 Excel 列求值器 — 让报价单 Excel 视图与产品卡片使用同一前端引擎求值，
 * 保证 Excel 值与卡片 footer 产品小计恒等（不再有后端/前端双引擎分叉）。
 *
 * ## 侦察结论（Step 1）
 *
 * ### expressionToTokens 签名
 *   `expressionToTokens(expr: string, tabDefs: TabDef[], selfRowKeyFields?, selfComponentId?)`
 *   - TAB_JOIN_FORMULA 列存储 `{source_type, expression: string, tabs: [{alias,tabKey,rowKeyFields}]}`
 *   - `col.tabs` 是 TabDef 的子集（含 alias/tabKey/rowKeyFields），可直接作为 tabDefs 传入
 *   - CARD_FORMULA 列的 `expression` 已是 FormulaToken[]，直接调用 evaluateExpression
 *
 * ### component_subtotal 键格式（formulaEngine.ts L257-274）
 *   查顺序：`${component_code}#${colName}` → `componentSubtotals[component_code]` → tab_name → value → 0
 *   `[报价小计(总计)]` 表达式对应 token: {type:'component_subtotal', component_code:..., tab_name:..., value:''}
 *   → 走整组件总小计键（3 个 key: componentId / componentCode / tabName）
 *
 * ### buildCrossTabRows 返回值
 *   `{store, columnSumsByComp}` — 用 store 作为 crossTabRows 传给 evaluateExpression
 *
 * ### SUBTOTAL 组件的产品小计注册
 *   evalProductSubtotalFromSubtotals 用 SUBTOTAL 组件的 formulas[0].expression 求值，
 *   结果须注册到 componentSubtotals 的三键（id/code/tabName）使后续 TAB_JOIN_FORMULA
 *   引用 `[报价小计(总计)]` 能取到正确值。
 *
 * ## 输出格式
 *   `{rows: [{col_key: value, __hfPartNo: partNo, _lineItemId: id}]}`
 *   与 useExcelSnapshotRows / quoteExcelValues 快照形态一致（rows[0] 即本行）。
 */

import type { LineItem } from './QuotationStep2';
import {
  getComponentSubtotals,
  buildCrossTabRows,
  evalProductSubtotalFromSubtotals,
} from './QuotationStep2';
import { evaluateExpression } from '../../utils/formulaEngine';
import type { GlobalVariableDefinition } from '../../utils/formulaEngine';
import { expressionToTokens } from '../component/formulaSerialize';
import type { CostingTemplateColumn } from '../../services/costingTemplateService';
import type { DriverExpansionMap } from './useDriverExpansions';
import { driverExpansionKey, fieldsOverrideHash } from './useDriverExpansions';
import {
  isLegacyVarCode,
  resolveVariable,
  evaluateFormula,
  formatPathValue,
  pathCacheKey,
} from './useLinkedExcelRows';

// ─── 类型扩展 ──────────────────────────────────────────────────────────────────

/**
 * TAB_JOIN_FORMULA 列在 excelColumns 中的完整存储形态（CostingTemplateColumn 的超集）。
 * `expression` 是 TAB_JOIN_FORMULA 的字符串表达式；`tabs` 是 TabDef 子集。
 */
interface TabJoinFormulaColumn extends CostingTemplateColumn {
  /** TabJoinFormulaDrawer 编辑的字符串表达式（如 "[来料.材料成本]"） */
  expression: string;
  /** 被引用页签集（alias/tabKey/rowKeyFields），来自 buildColumn 序列化时保存 */
  tabs: Array<{ alias: string; tabKey: string; rowKeyFields: string[] }>;
}

/**
 * buildExcelSnapshot 的附加上下文（全部可选，缺省时退化到离线计算模式）。
 * - pathCache: BNF 路径求值缓存，key = `${partNo}::${path}`
 * - basicDataValues: driver 行级 BASIC_DATA 值，key = `{path}`
 * - globalVariableDefs: 全局变量定义字典（code → def），供动态 key GV 路径重写
 * - quotationFields: 报价单级字段值（如汇率、报价日期等），供 quotation_field token
 * - lookupExpansion: 按 ComponentDataItem 查找 DriverExpansion，供 buildCrossTabRows 使用
 */
export interface BuildExcelSnapshotCtx {
  pathCache?: Record<string, any>;
  basicDataValues?: Record<string, any>;
  globalVariableDefs?: Record<string, GlobalVariableDefinition>;
  quotationFields?: Record<string, number>;
  lookupExpansion?: (comp: import('./QuotationStep2').ComponentDataItem) => import('./useDriverExpansions').DriverExpansion | undefined;
}

// ─── 主函数 ────────────────────────────────────────────────────────────────────

/**
 * 用前端同一引擎对单个 LineItem 的 Excel 列求值，产出 `{rows:[{col_key:value,...}]}` 快照。
 *
 * @param item            当前报价行
 * @param columns         Excel 模板列定义（CostingTemplateColumn[]，TAB_JOIN_FORMULA 列含 expression/tabs）
 * @param driverExpansions 已展开的 driver 行映射（可 undefined，离线/无 driver 场景）
 * @param customerId       客户 ID（供 driverExpansionKey 维度隔离）
 * @param ctx             附加求值上下文（pathCache / basicDataValues / globalVariableDefs 等）
 * @returns `{rows: [rowRecord]}` 与 quoteExcelValues 快照格式一致
 */
export function buildExcelSnapshot(
  item: LineItem,
  columns: CostingTemplateColumn[],
  driverExpansions: DriverExpansionMap | undefined,
  customerId: string | undefined,
  ctx: BuildExcelSnapshotCtx,
): { rows: Array<Record<string, any>> } {
  const partNo = item.productPartNo ?? '';

  // ── Step 1: PASS1 组件列小计 ────────────────────────────────────────────────
  // getComponentSubtotals 内部按 NORMAL 组件逐行算（不含 crossTabRows），
  // 是 buildCrossTabRows 依赖的"前期状态"。
  const componentSubtotals = getComponentSubtotals(item, driverExpansions, customerId);

  // ── Step 2: 构建 cross-tab 行并 PASS2 回填列小计 ────────────────────────────
  // buildCrossTabRows 按拓扑序算所有 NORMAL 组件的行，并将 is_subtotal 列值回填
  // componentSubtotals（覆盖 Step1 的 PASS1 结果，修正 cross_tab 列贡献 0 的问题）。
  //
  // lookupExpansion 与卡片同源（QuotationStep2.tsx:1201-1207 / 1983-1988）：
  //   lineItemId = item.id || item.tempId || ''（与卡片 Bug B 注释对齐，用 || 而非 ??）
  //   key 维度：lineItemId + partNo + componentId + customerId + dataDriverPath + fieldsOverrideHash
  // 若调用方已在 ctx.lookupExpansion 提供则优先使用（测试/特殊场景）；
  // 否则从 driverExpansions 参数自建（正常渲染路径）。
  const lookupExpansion = ctx.lookupExpansion ?? ((comp: import('./QuotationStep2').ComponentDataItem) => {
    if (!driverExpansions || !partNo || !comp.componentId) return undefined;
    // lineItemId 与卡片对齐：item.id || item.tempId || ''（|| 判 falsy，与卡片 1204 行一致）
    const lineItemId = (item as any).id || (item as any).tempId || '';
    const k = driverExpansionKey(lineItemId, partNo, comp.componentId, customerId, comp.dataDriverPath, fieldsOverrideHash(comp.fields as any[]));
    return driverExpansions[k];
  });
  const { store: crossTabRows } = buildCrossTabRows(
    item.componentData ?? [],
    componentSubtotals,
    partNo,
    lookupExpansion,
    ctx.globalVariableDefs,
  );

  // ── Step 3: 计算产品小计并注册到 componentSubtotals ───────────────────────
  // 必须在 PASS2 回填完成后求值，保证 cross_tab 列小计已正确。
  const productSubtotal = evalProductSubtotalFromSubtotals(item, componentSubtotals);

  // 将产品小计注册到 SUBTOTAL 组件的三键，使 TAB_JOIN_FORMULA 中
  // 引用 `[报价小计(总计)]` 能从 componentSubtotals 取到正确值。
  const subtotalComp = item.componentData?.find(c => c.componentType === 'SUBTOTAL');
  if (subtotalComp) {
    if (subtotalComp.componentId) componentSubtotals[subtotalComp.componentId] = productSubtotal;
    if (subtotalComp.componentCode) componentSubtotals[subtotalComp.componentCode] = productSubtotal;
    componentSubtotals[subtotalComp.tabName] = productSubtotal;
  }

  // ── Step 4: 构建 productAttributeValues（数值型 → number） ─────────────────
  const productAttrs: Record<string, number> = {};
  for (const attr of item.productAttributes ?? []) {
    if (attr.field_type === 'NUMBER') {
      const v = parseFloat(item.productAttributeValues?.[attr.name]);
      if (!isNaN(v)) productAttrs[attr.name] = v;
    }
  }

  // ── Step 5: 逐列求值 ─────────────────────────────────────────────────────────
  // FORMULA/EXCEL_FORMULA 列可能引用其他列的求值结果，需要二次遍历，
  // 所以先做 first pass（所有非 FORMULA 列），再做 second pass（FORMULA 列）。
  const cell: Record<string, any> = {};
  const varValues: Record<string, any> = {};

  // first pass: 所有非 FORMULA/EXCEL_FORMULA 列
  for (const col of columns) {
    if (col.source_type === 'FORMULA' || col.source_type === 'EXCEL_FORMULA') continue;
    cell[col.col_key] = evalColumn(col, item, partNo, productAttrs, componentSubtotals, crossTabRows, ctx, varValues);
  }

  // second pass: FORMULA/EXCEL_FORMULA 列（可引用 first pass 的结果）
  // 注意：单遍非拓扑，FORMULA 列若引用另一 FORMULA 列会取 undefined→evaluateFormula 强制为 0
  //（与 useLinkedExcelRows 源行为一致；多级 FORMULA 引用不支持）
  for (const col of columns) {
    if (col.source_type !== 'FORMULA' && col.source_type !== 'EXCEL_FORMULA') continue;
    // 复用 useLinkedExcelRows 的 evaluateFormula（替换 [col_key] 引用）
    cell[col.col_key] = evaluateFormula(col.formula, cell, varValues);
  }

  // ── Step 6: 构建输出行 ────────────────────────────────────────────────────────
  const lineItemId = (item as any).id ?? (item as any).tempId ?? '';
  const row: Record<string, any> = {
    ...cell,
    __hfPartNo: partNo,
    _lineItemId: lineItemId,
  };

  return { rows: [row] };
}

// ─── 列求值分发 ────────────────────────────────────────────────────────────────

/**
 * 对单列按 source_type 分发求值（FORMULA/EXCEL_FORMULA 不在此处理，由外层 second pass 处理）。
 * varValues 会在 VARIABLE legacy 分支被填入，供 FORMULA 列的 {CODE} 替换使用。
 */
function evalColumn(
  col: CostingTemplateColumn,
  item: LineItem,
  partNo: string,
  productAttrs: Record<string, number>,
  componentSubtotals: Record<string, number>,
  crossTabRows: Record<string, Array<Record<string, any>>>,
  ctx: BuildExcelSnapshotCtx,
  varValues: Record<string, any>,
): any {
  switch (col.source_type) {
    case 'TAB_JOIN_FORMULA':
    case 'CARD_FORMULA': {
      return evalTabJoinOrCard(col, partNo, productAttrs, componentSubtotals, crossTabRows, ctx);
    }

    case 'FIXED_VALUE': {
      // 优先解析为数字，失败则返回原始字符串
      const fv = col.fixed_value;
      if (fv == null || fv === '') return null;
      const n = Number(fv);
      return isNaN(n) ? fv : n;
    }

    case 'PRODUCT_ATTRIBUTE': {
      const fk = col.field_key ?? '';
      const raw = item.productAttributeValues?.[fk];
      if (raw == null) return null;
      const n = Number(raw);
      return isNaN(n) ? raw : n;
    }

    case 'COMPONENT_FIELD': {
      // 取第一个 NORMAL 组件第一行的 field_key 值
      const fk = col.field_key ?? '';
      for (const comp of item.componentData ?? []) {
        if (comp.componentType !== 'NORMAL') continue;
        const row = comp.rows?.[0];
        if (row && Object.prototype.hasOwnProperty.call(row, fk)) {
          const v = row[fk];
          const n = Number(v);
          return isNaN(n) ? v : n;
        }
      }
      return null;
    }

    case 'VARIABLE': {
      // ## 快照时不变量（snapshot invariant）
      // BNF 路径分支依赖 ctx.pathCache 在调用前已预填所有在用路径的值。
      // - 用于持久化（saveDraft / submit）：调用方必须保证 pathCache 对所有 BNF 路径已预填，
      //   否则 cache-miss 将产生 '__loading__' 哨兵，持久化快照会含不完整值。
      // - 用于显示场景：'__loading__' 哨兵透传给显示层（与 useLinkedExcelRows:292-293 一致），
      //   由显示层（stillLoading / noCostingData 整行置空逻辑）处理。
      const vp = (col.variable_path ?? '').trim();
      if (!vp) return null;
      if (isLegacyVarCode(vp)) {
        // 老 {CODE} 格式：按 lineItem / quotationContext 映射取值
        const v = resolveVariable(vp, item, ctx.quotationFields as any ?? {});
        // 填入 varValues 供 FORMULA 列 {CODE} 替换
        const m = vp.match(/^\{([^}]+)\}$/);
        if (m) varValues[m[1].trim().toLowerCase()] = v;
        return v;
      }
      // BNF 路径格式：从 pathCache 取（异步结果已由调用方预填）
      if (!partNo) return null;
      const k = pathCacheKey(partNo, vp);
      const cached = ctx.pathCache;
      // cache-miss → 返回 '__loading__' 哨兵（与 useLinkedExcelRows L292-293 源行为一致）
      if (!cached || !Object.prototype.hasOwnProperty.call(cached, k)) return '__loading__';
      return formatPathValue(cached[k]);
    }

    case 'FORMULA':
    case 'EXCEL_FORMULA':
      // second pass 处理，first pass 跳过
      return undefined;

    default:
      return null;
  }
}

// ─── TAB_JOIN_FORMULA / CARD_FORMULA 求值 ─────────────────────────────────────

/**
 * 对 TAB_JOIN_FORMULA 或 CARD_FORMULA 列调用 expressionToTokens + evaluateExpression。
 *
 * TAB_JOIN_FORMULA 列：
 *   - `col.expression` 是字符串（如 "[来料.材料成本]"）
 *   - `col.tabs` 是引用的页签定义列表（TabDef 子集）
 *   - 需要 expressionToTokens 将字符串解析成 FormulaToken[]
 *
 * CARD_FORMULA 列（理论上 expression 也可以是字符串或 token[]）：
 *   - 同 TAB_JOIN_FORMULA，统一走 expressionToTokens 路径
 */
function evalTabJoinOrCard(
  col: CostingTemplateColumn,
  partNo: string,
  productAttrs: Record<string, number>,
  componentSubtotals: Record<string, number>,
  crossTabRows: Record<string, Array<Record<string, any>>>,
  ctx: BuildExcelSnapshotCtx,
): number {
  const colAny = col as any as TabJoinFormulaColumn;
  const exprStr: string | undefined = colAny.expression;
  if (!exprStr) return 0;

  // expressionToTokens 需要 TabDef[]；col.tabs 已包含 alias/tabKey/rowKeyFields
  const tabs = (colAny.tabs ?? []) as Array<{ alias: string; tabKey: string; rowKeyFields: string[] }>;

  let tokens: ReturnType<typeof expressionToTokens>;
  try {
    tokens = expressionToTokens(exprStr, tabs as any);
  } catch (e) {
    console.warn('[buildExcelSnapshot] TAB_JOIN/CARD eval failed for col', col.col_key, e);
    return 0;
  }

  try {
    return evaluateExpression(
      tokens as any,
      {},                        // fieldValues（Excel 列无行级字段，固定传空）
      componentSubtotals,
      productAttrs,
      ctx.quotationFields,
      ctx.pathCache as Record<string, number> | undefined,
      partNo,
      ctx.basicDataValues,
      undefined,                 // previousRowSubtotal
      ctx.globalVariableDefs,
      undefined,                 // currentRow
      crossTabRows,
    );
  } catch (e) {
    console.warn('[buildExcelSnapshot] TAB_JOIN/CARD eval failed for col', col.col_key, e);
    return 0;
  }
}
