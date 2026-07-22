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

/**
 * 遍历 item.quoteCardValues 各页签(tabs)已渲染的 baseRows，抽取料号并按值去重。
 *
 * 2026-07-21 更正（需求说明 §4.3 规则一）：料号列**依据组件配置的 `partNoField` 显式取值，
 * 不靠字段名/label 含"料号"启发式猜测**——原实现的猜测法已确认为洞，业务已裁决补显式配置。
 * - 树页签（`comp.tabType==='BOM'`，或行本身带系统列）取 `__hfPartNo`，`partNoField` 可不配。
 * - 非树页签必须配 `partNoField`；未配置的页签本函数不产出候选（与后端「不参与类型判定匹配」一致）。
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
    // 显式取列：comp.partNoField 是字段 name，从该页签自身字段定义里找对应 field 用于 resolveTreeKey 求值。
    const partNoFieldName = comp.partNoField;
    const partNoFieldDef = partNoFieldName
      ? (comp.fields as ComponentField[] | undefined)?.find(
          (f) => (f.name || (f as any).key) === partNoFieldName,
        )
      : undefined;
    const baseRows: any[] = Array.isArray(vtab.baseRows) ? vtab.baseRows : [];
    for (const br of baseRows) {
      let partNo: string | null = null;
      if (br?.__hfPartNo) {
        // 树页签系统列最权威，与 partNoField 是否配置无关
        partNo = String(br.__hfPartNo);
      } else if (partNoFieldDef) {
        partNo = resolveTreeKey(partNoFieldDef, br?.driverRow ?? {}, br?.basicDataValues, bnfDriverLookupKey);
      }
      // 无 __hfPartNo 且未配置 partNoField 的页签：不产出候选（该页签未参与类型判定匹配）
      if (!partNo || seen.has(partNo)) continue;
      seen.add(partNo);
      out.push({ partNo, sourceComponentId: cid, sourceTabName: comp.tabName });
    }
  }
  return out;
}
