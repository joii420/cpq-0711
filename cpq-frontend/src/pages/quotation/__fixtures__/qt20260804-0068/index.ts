/**
 * 真实单据夹具入口 —— repair-0805 / BL-0112（`QT-20260804-0068`）
 *
 * 三份数据体的来源、导出日期、裁剪口径见各自文件头。这里只放「测试要引用的常量」
 * 与「把夹具喂进生产函数的装配器」——装配器刻意复刻 `ProductCard`（QuotationStep2.tsx:2819）
 * 与 `ReadonlyProductCard`（:456）真实调用 `buildCrossTabRows` 的那段代码，
 * 不另起一套简化装配，否则测的就不是线上那条链路了。
 */
import { buildSnapshotExpansions } from '../../QuotationStep2';
import { driverExpansionKey, fieldsOverrideHash } from '../../useDriverExpansions';
import { buildComponentDataFromStructure } from '../../enrichComponentData';
import type { ComponentDataItem, LineItem } from '../../QuotationStep2';

export { QT0068_QUOTE_CARD_STRUCTURE } from './quoteCardStructure';
export { QT0068_QUOTE_CARD_VALUES } from './quoteCardValues';
export { QT0068_TEMPLATE_COMPONENTS_SNAPSHOT } from './templateComponentsSnapshot';
export { QT0068_SAVED_COMPONENT_DATA } from './savedComponentData';

import { QT0068_QUOTE_CARD_STRUCTURE } from './quoteCardStructure';
import { QT0068_QUOTE_CARD_VALUES } from './quoteCardValues';
import { QT0068_SAVED_COMPONENT_DATA } from './savedComponentData';

/** 报价单 / 行项 / 模板 / 料号 —— 排查报告与本轮全部用例引用的同一份单据。 */
export const QT0068 = {
  quotationId: 'be95ded9-6bda-4c25-8c88-ea836acd5d0d',
  lineItemId: '8205e1d8-ebe3-4a43-b6f9-4579afe94eec',
  templateId: '88d5d815-385b-45ca-bd4b-de0e0bad8a30',
  customerId: undefined as string | undefined,
  productPartNo: '3120011203',
  /** 「物料」页签 = COMP-0185，本缺陷的报障页签（11 个 FORMULA 列全空）。 */
  wuliaoComponentId: '2db185d6-2b5f-4617-bbc5-6957d6b735e2',
  wuliaoTabName: '物料',
  /** 「物料」的 rowKeyFields（结构快照 tabs[].rowKeyFields）。 */
  wuliaoRowKeyFields: ['料件'] as string[],
} as const;

/** 「物料」页签 11 个 FORMULA 列（结构快照 fieldType==='FORMULA' 的字段名，按 sortOrder）。 */
export const WULIAO_FORMULA_COLUMNS = [
  '来料回收费', '来料财务费', '材料成本', '材料损耗成本', '来料损耗率',
  '来料加工费', '回收成本', '公式10', '原材料成本', '材料价格', '铆钉额外费用',
] as const;

/** 「材料成本」字段（唯一的条件公式字段）绑定的两条公式 id —— T3 按 id 解析的靶子。 */
export const MATERIAL_COST_FORMULA_IDS = {
  /** rules[0]：产出类型 = '非银点类' 时命中 */
  rule0: 'cb3ea05c-74d4-4a69-870f-79cd5b74376a',
  rule0Name: '非银点类材料成本公式',
  /** default：其余情况兜底 */
  default: 'c9213b82-cd55-484d-87e1-f9d95c13718f',
  defaultName: '银点材料成本公式',
} as const;

/** 结构快照里的某个页签（camelCase 形状，`CardStructureTab`）。 */
export function structureTab(tabName: string): any {
  const t = (QT0068_QUOTE_CARD_STRUCTURE.tabs as any[]).find((x: any) => x.tabName === tabName);
  if (!t) throw new Error(`fixture: 结构快照里没有页签 ${tabName}`);
  return t;
}

/** 值快照里的某个页签（`CardValuesTab`：baseRows / editRows / formulaResults / subtotalByColumn）。 */
export function valuesTab(tabName: string): any {
  const t = (QT0068_QUOTE_CARD_VALUES.tabs as any[]).find((x: any) => x.tabName === tabName);
  if (!t) throw new Error(`fixture: 值快照里没有页签 ${tabName}`);
  return t;
}

/**
 * 结构快照 + 行项 saved 行 → 渲染模型（`buildComponentDataFromStructure`，
 * 报价单编辑页 / 详情页 / 核价侧共用的同步组装路径）。
 *
 * saved 侧传真实 `lineItem.componentData`（已剔除公式输出列，见 savedComponentData.ts 文件头）——
 * 传 `[]` 会让 `comp.rows` 变成 `[{}]`，条件公式的 when 判据（产出类型）永远取不到值，
 * 测出来的分支走向就不是线上的了。
 *
 * @param structure 允许传入「深拷贝后改过的」结构，用于反向门禁（T4.3：抹掉 formulas[].id）。
 * @param saved     允许传入改过的 saved 行（T3 反事实：翻转产出类型）。
 */
export function buildQt0068ComponentData(
  structure: any = QT0068_QUOTE_CARD_STRUCTURE,
  saved: any[] = QT0068_SAVED_COMPONENT_DATA,
): ComponentDataItem[] {
  return buildComponentDataFromStructure(structure, saved);
}

/**
 * 复刻 ProductCard（QuotationStep2.tsx:2819）的 `lookupExpansion`：
 * 值快照 → `buildSnapshotExpansions` → 按 `driverExpansionKey` 取本组件的 DriverExpansion。
 *
 * @param cardValues 允许调用方传入改过的值快照（反向门禁用）。
 */
export function buildQt0068ExpansionLookup(
  componentData: ComponentDataItem[],
  cardValues: any = QT0068_QUOTE_CARD_VALUES,
): (comp: ComponentDataItem) => any {
  const item = {
    id: QT0068.lineItemId,
    productPartNo: QT0068.productPartNo,
    componentData,
    quoteCardValues: JSON.stringify(cardValues),
  } as unknown as LineItem;

  const rowKeyFieldsByComp = new Map<string, string[]>(
    (QT0068_QUOTE_CARD_STRUCTURE.tabs as any[])
      .filter((t: any) => t.componentId)
      .map((t: any) => [t.componentId as string, (t.rowKeyFields ?? []) as string[]]),
  );

  const map = buildSnapshotExpansions([item], 'QUOTE', QT0068.customerId, rowKeyFieldsByComp);
  return (comp: ComponentDataItem) => (comp.componentId
    ? map[driverExpansionKey(
        QT0068.lineItemId, QT0068.productPartNo, comp.componentId, QT0068.customerId,
        comp.dataDriverPath, fieldsOverrideHash(comp.fields as any[]),
      )]
    : undefined);
}

/** 深拷贝——反向门禁改夹具前必须先拷贝，避免污染同文件其它用例。 */
export function deepClone<T>(o: T): T {
  return JSON.parse(JSON.stringify(o)) as T;
}
