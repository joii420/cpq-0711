/**
 * 真实单据夹具入口 —— repair-0808（前端页签算序假环致列小计归零，`QT-20260807-0146`）
 *
 * 三份数据体的来源、导出日期、裁剪口径见各自文件头。这里只放「测试要引用的常量」与
 * 「把夹具喂进生产函数的装配器」——装配器逐字复刻 `ProductCard`（QuotationStep2.tsx:2960 起）
 * 真实调用 `buildComponentDataFromStructure → buildSnapshotExpansions → PASS1 → buildCrossTabRows`
 * 的那段代码，不另起一套简化装配，否则测的就不是线上那条链路了。
 *
 * 🚨 PASS1 不能省：`computeTabSubtotalsByColumn` 逐组件登记列小计（含「产品#税率」这类
 * INPUT_NUMBER 列，被「材料成本」条件公式的 component_subtotal token 引用）。物料在 PASS2 里
 * 按拓扑序排在「产品」之前处理，若没有 PASS1 预先把「产品#税率」写进 allComponentSubtotals，
 * 物料的条件公式会读到 undefined，掩盖真问题、也可能引入假阴性/假阳性。
 */
import { buildSnapshotExpansions, buildCrossTabRows, computeTabSubtotalsByColumn } from '../../QuotationStep2';
import { driverExpansionKey, fieldsOverrideHash } from '../../useDriverExpansions';
import { buildComponentDataFromStructure } from '../../enrichComponentData';
import type { ComponentDataItem, LineItem, GlobalVariableDefinition } from '../../QuotationStep2';

export { QT0146_QUOTE_CARD_STRUCTURE } from './quoteCardStructure';
export { QT0146_QUOTE_CARD_VALUES } from './quoteCardValues';
export { QT0146_SAVED_COMPONENT_DATA } from './savedComponentData';

import { QT0146_QUOTE_CARD_STRUCTURE } from './quoteCardStructure';
import { QT0146_QUOTE_CARD_VALUES } from './quoteCardValues';
import { QT0146_SAVED_COMPONENT_DATA } from './savedComponentData';

/** 报价单 / 行项 / 模板 / 料号 —— 排查报告与本轮全部用例引用的同一份单据。 */
export const QT0146 = {
  quotationId: '6d014a9a-fe27-432a-bce9-7f6c86c50775',
  lineItemId: '5eb0f2de-dc0e-42bf-a979-9240940507ec',
  templateId: '7fd1ecd8-ec52-4bc5-9104-98189d1e0761',
  customerId: undefined as string | undefined,
  productPartNo: '3120011203',
  /** 「产品」页签 = COMP-0224（税率=INPUT_NUMBER，是假环的另一端）。 */
  productComponentId: '8e06c482-4ca7-47b1-85bb-694c077451c3',
  productTabName: '产品',
  /** 「物料」页签 = COMP-0228，本缺陷的报障页签（11 个 FORMULA 列全空）。 */
  wuliaoComponentId: '7f7b57ac-b368-4250-969a-b5612b6f92fb',
  wuliaoTabName: '物料',
  /** 「物料」cross_tab_ref 真正依赖的 3 个源页签（AC-4：拓扑序必须排在「物料」之前）。 */
  materialCostComponentId: '1054217f-059b-43d3-9c6b-8e41062ebf07',
  materialCostTabName: '材料成本',
  incomingFixedFeeComponentId: 'd6b5add7-b8f4-4ff5-bc95-6a112a206682',
  incomingFixedFeeTabName: '来料固定加工费',
  incomingOtherFeeComponentId: '00783228-b913-41a5-8e7e-d486acbffa78',
  incomingOtherFeeTabName: '来料其他费用',
  /** 「报价」SUBTOTAL 页签（产品小计，AC-2）。产品小计的后端权威值读 `valuesTab('报价').subtotal`
   *  （T-2.4 不用这里硬编码，直接从夹具读——`quotation.total_amount` 是 NUMERIC(20,6) 落库后
   *  精度损失过的展示值，不能当高精度对拍基准，见 crossTabOrderParityQt0146.repair0808.test.ts 头部注释）。 */
  quoteSubtotalComponentId: 'fedc4207-ca54-4989-9f5e-c7fe494f3059',
  quoteSubtotalTabName: '报价',
} as const;

/** 「物料」页签 11 个 FORMULA 列（结构快照 fieldType==='FORMULA' 的字段名，按 sortOrder）。 */
export const WULIAO_FORMULA_COLUMNS = [
  '来料回收费', '来料财务费', '材料成本', '材料损耗成本', '来料损耗率',
  '来料加工费', '回收成本', '公式10', '原材料成本', '材料价格', '铆钉额外费用',
] as const;

/** 结构快照里的某个页签（camelCase 形状，`CardStructureTab`）。 */
export function structureTab(tabName: string): any {
  const t = (QT0146_QUOTE_CARD_STRUCTURE.tabs as any[]).find((x: any) => x.tabName === tabName);
  if (!t) throw new Error(`fixture: 结构快照里没有页签 ${tabName}`);
  return t;
}

/** 值快照里的某个页签（`CardValuesTab`：baseRows / formulaResults / subtotalByColumn）。 */
export function valuesTab(tabName: string): any {
  const t = (QT0146_QUOTE_CARD_VALUES.tabs as any[]).find((x: any) => x.tabName === tabName);
  if (!t) throw new Error(`fixture: 值快照里没有页签 ${tabName}`);
  return t;
}

/**
 * 结构快照 + 行项 saved 行 → 渲染模型（`buildComponentDataFromStructure`，
 * 报价单编辑页 / 详情页 / 核价侧共用的同步组装路径）。
 *
 * @param structure 允许传入「深拷贝后改过的」结构，用于反向门禁场景。
 * @param saved     允许传入改过的 saved 行。
 */
export function buildQt0146ComponentData(
  structure: any = QT0146_QUOTE_CARD_STRUCTURE,
  saved: any[] = QT0146_SAVED_COMPONENT_DATA,
): ComponentDataItem[] {
  return buildComponentDataFromStructure(structure, saved);
}

/**
 * 复刻 ProductCard（QuotationStep2.tsx:2960 起）的 `lookupExpansion`：
 * 值快照 → `buildSnapshotExpansions` → 按 `driverExpansionKey` 取本组件的 DriverExpansion。
 *
 * @param cardValues 允许调用方传入改过的值快照（反向门禁用）。
 */
export function buildQt0146ExpansionLookup(
  componentData: ComponentDataItem[],
  cardValues: any = QT0146_QUOTE_CARD_VALUES,
): (comp: ComponentDataItem) => any {
  const item = {
    id: QT0146.lineItemId,
    productPartNo: QT0146.productPartNo,
    componentData,
    quoteCardValues: JSON.stringify(cardValues),
  } as unknown as LineItem;

  const rowKeyFieldsByComp = new Map<string, string[]>(
    (QT0146_QUOTE_CARD_STRUCTURE.tabs as any[])
      .filter((t: any) => t.componentId)
      .map((t: any) => [t.componentId as string, (t.rowKeyFields ?? []) as string[]]),
  );

  const map = buildSnapshotExpansions([item], 'QUOTE', QT0146.customerId, rowKeyFieldsByComp);
  return (comp: ComponentDataItem) => (comp.componentId
    ? map[driverExpansionKey(
        QT0146.lineItemId, QT0146.productPartNo, comp.componentId, QT0146.customerId,
        comp.dataDriverPath, fieldsOverrideHash(comp.fields as any[]),
      )]
    : undefined);
}

/**
 * PASS1（逐组件登记页签/列小计，镜像 ProductCard QuotationStep2.tsx:2976-3008）——
 * 在 PASS2（`buildCrossTabRows`）之前，把每个组件自己的 `is_subtotal` 列小计
 * （含裸键 / `#列名` 键 / `#__amount_total__` 哨兵键，componentId + componentCode + tabName 三键）
 * 登记进 `allComponentSubtotals`，供 PASS2 处理顺序更靠前的组件的 `component_subtotal`
 * token（如「物料」引用「产品#税率」）在自己被处理时就能取到值，不必等「产品」本身被 PASS2 处理。
 *
 * 按 `item.componentData` 的**原始声明序**遍历（与 ProductCard 一致），不看拓扑序——
 * PASS1 本身就是为了打破「先有鸡还是先有蛋」的顺序依赖而存在的。
 */
export function buildQt0146AllComponentSubtotals(
  componentData: ComponentDataItem[],
  lookupExpansion: (comp: ComponentDataItem) => any,
  partNo: string = QT0146.productPartNo,
  globalVariableDefs?: Record<string, GlobalVariableDefinition>,
): Record<string, number> {
  const AMOUNT_TOTAL_KEY = '__amount_total__';
  const allComponentSubtotals: Record<string, number> = {};
  for (const comp of componentData) {
    const expansion = (partNo && comp.componentId) ? lookupExpansion(comp) : undefined;
    const byCol = computeTabSubtotalsByColumn(
      comp, allComponentSubtotals, undefined, undefined, partNo, expansion, globalVariableDefs,
    );
    const subtotal = Object.values(byCol).reduce((s, v) => s + v, 0);
    if (comp.componentId) allComponentSubtotals[comp.componentId] = subtotal;
    if (comp.componentCode) allComponentSubtotals[comp.componentCode] = subtotal;
    allComponentSubtotals[comp.tabName] = subtotal;
    for (const [colName, colVal] of Object.entries(byCol)) {
      if (comp.componentId) allComponentSubtotals[`${comp.componentId}#${colName}`] = colVal;
      if (comp.componentCode) allComponentSubtotals[`${comp.componentCode}#${colName}`] = colVal;
      allComponentSubtotals[`${comp.tabName}#${colName}`] = colVal;
    }
    const amountTotalP1 = Object.entries(byCol)
      .filter(([colName]) => (comp.fields ?? []).find((f: any) => (f.name ?? f.key) === colName)?.is_amount)
      .reduce((s, [, v]) => s + v, 0);
    if (comp.componentId) allComponentSubtotals[`${comp.componentId}#${AMOUNT_TOTAL_KEY}`] = amountTotalP1;
    if (comp.componentCode) allComponentSubtotals[`${comp.componentCode}#${AMOUNT_TOTAL_KEY}`] = amountTotalP1;
    allComponentSubtotals[`${comp.tabName}#${AMOUNT_TOTAL_KEY}`] = amountTotalP1;
  }
  return allComponentSubtotals;
}

/**
 * 跑一遍完整渲染管线（PASS1 + PASS2），返回「物料」页签的 resolvedRows + 全部页签的列小计。
 * 逐字复刻 ProductCard 的调用形状（QuotationStep2.tsx:2960-3023）。
 */
export function runQt0146Pipeline(
  structure: any = QT0146_QUOTE_CARD_STRUCTURE,
  saved: any[] = QT0146_SAVED_COMPONENT_DATA,
  cardValues: any = QT0146_QUOTE_CARD_VALUES,
) {
  const componentData = buildQt0146ComponentData(structure, saved);
  const lookupExpansion = buildQt0146ExpansionLookup(componentData, cardValues);
  const allComponentSubtotals = buildQt0146AllComponentSubtotals(componentData, lookupExpansion);
  const { store, columnSumsByComp } = buildCrossTabRows(
    componentData, allComponentSubtotals, QT0146.productPartNo, lookupExpansion,
  );
  return {
    componentData,
    lookupExpansion,
    allComponentSubtotals,
    store,
    columnSumsByComp,
    wuliaoRows: (store[QT0146.wuliaoComponentId] ?? []) as Array<Record<string, any>>,
    wuliaoColumnSums: (columnSumsByComp[QT0146.wuliaoComponentId] ?? {}) as Record<string, number>,
  };
}

/** 深拷贝——反向门禁改夹具前必须先拷贝，避免污染同文件其它用例。 */
export function deepClone<T>(o: T): T {
  return JSON.parse(JSON.stringify(o)) as T;
}
