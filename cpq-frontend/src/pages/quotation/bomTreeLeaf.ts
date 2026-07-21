// task-0721 F4：报价侧 BOM 树「加叶子」——候选料号本地采集。
//
// 架构红线（api.md §7）：候选料号列表**不调用任何远程端点**（不查 pricing-basic-data/lookup，
// 不新增后端接口）——数据已在前端 componentData / quoteCardValues 快照中，本地过滤即可。
//
// 匹配范围 = 当前报价单各页签**已渲染**的行（quoteCardValues.tabs[].baseRows），
// 不是基础数据主表全量（design.md §5.2）。

import type { LineItem, ComponentField } from './QuotationStep2';
import { resolveTreeKey } from './treeTable';
import { bnfDriverLookupKey } from './useDriverExpansions';

export interface BomLeafCandidate {
  partNo: string;
  /** 该料号取自哪个页签（componentId），供后端 §3 判定 + 前端展示来源用 */
  sourceComponentId: string;
  sourceTabName: string;
}

/** 字段是否为"料号"列的启发式判定：字段名/显示名含"料号"（现役配置的通用命名习惯，
 *  BOM 树系统列固定表头即为"料号"，非树页签沿用同一命名约定）。 */
function isPartNoField(field: ComponentField): boolean {
  const label = String(field.label || field.name || '');
  return label.includes('料号');
}

/**
 * 遍历 item.quoteCardValues 各页签(tabs)已渲染的 baseRows，抽取料号并按值去重。
 * 优先取 BOM 系统列 __hfPartNo（该页签本身是树页签时最权威）；否则按"料号"字段名
 * 匹配该页签字段定义，用 resolveTreeKey 解析（同建树取值口径，兼容 BASIC_DATA / DATA_SOURCE.BNF_PATH / 普通行值）。
 */
export function collectBomLeafCandidates(item: LineItem): BomLeafCandidate[] {
  const seen = new Set<string>();
  const out: BomLeafCandidate[] = [];
  if (!item.quoteCardValues) return out;
  let parsed: any;
  try {
    parsed = JSON.parse(item.quoteCardValues);
  } catch {
    return out;
  }
  const tabs = Array.isArray(parsed?.tabs) ? parsed.tabs : [];
  for (const vtab of tabs) {
    const cid = vtab?.componentId;
    if (!cid) continue;
    const comp = item.componentData?.find((c) => c.componentId === cid);
    if (!comp || comp.componentType === 'SUBTOTAL') continue;
    const partNoField = (comp.fields as ComponentField[] | undefined)?.find(isPartNoField);
    const baseRows: any[] = Array.isArray(vtab.baseRows) ? vtab.baseRows : [];
    for (const br of baseRows) {
      let partNo: string | null = null;
      if (br?.__hfPartNo) {
        partNo = String(br.__hfPartNo);
      } else if (partNoField) {
        partNo = resolveTreeKey(partNoField, br?.driverRow ?? {}, br?.basicDataValues, bnfDriverLookupKey);
      }
      if (!partNo || seen.has(partNo)) continue;
      seen.add(partNo);
      out.push({ partNo, sourceComponentId: cid, sourceTabName: comp.tabName });
    }
  }
  return out;
}
