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

function normStr(v: any): string | null {
  if (v == null || v === '') return null;
  if (Array.isArray(v)) return v.length > 0 ? normStr(v[0]) : null;
  if (typeof v === 'object') return null;
  const s = String(v).trim();
  return s === '' ? null : s;
}

/**
 * 按字段定义解析该行的字符串取值——通用于 partNoField 与 partNameField（2026-07-23 起
 * 两者取值口径完全一致，只是字段配置不同，故合用一个解析函数，不重复实现）。
 *
 * 2026-07-21 真实 fixture 验证发现：`resolveTreeKey`（treeTable.ts）只认两种历史机制
 * （`field_type==='BASIC_DATA'`+`basic_data_path`，或 `DATA_SOURCE`+`datasource_binding.type==='BNF_PATH'`），
 * 不认 V190 统一默认值来源 `default_source.type==='BASIC_DATA'`——而 task-0721 材质页签 fixture
 * 的"料号"字段恰好是 `field_type: 'INPUT_TEXT'` + `default_source: {type:'BASIC_DATA', path:...}`，
 * 命中 resolveTreeKey 的口径外，落到它的最终兜底 `row[field.name]`（driverRow 实际 key 带 `_` 前缀，
 * 如 `_料号`，与字段 name「料号」不同名）→ 恒返回 null，候选采集不到该页签数据。
 * 本函数不改 treeTable.ts（该文件与树布局共用，不属于本次改动范围），只在本文件内先补上
 * default_source.BASIC_DATA 分支，再退回 resolveTreeKey 覆盖其余历史机制，两者互补。
 */
function resolveFieldStringValue(
  field: ComponentField,
  driverRow: Record<string, any>,
  basicDataValues: Record<string, any> | undefined,
): string | null {
  const dsPath = field.default_source?.type === 'BASIC_DATA' ? field.default_source.path : undefined;
  if (dsPath && basicDataValues) {
    const lk = bnfDriverLookupKey(dsPath);
    if (Object.prototype.hasOwnProperty.call(basicDataValues, lk)) {
      const got = normStr(basicDataValues[lk]);
      if (got != null) return got;
    }
  }
  return resolveTreeKey(field, driverRow, basicDataValues, bnfDriverLookupKey);
}

/** 从组件字段定义里按字段 name 查找对应 field（partNoField/partNameField 存的是字段 name）。 */
function findFieldByName(
  fields: ComponentField[] | undefined,
  fieldName: string | undefined,
): ComponentField | undefined {
  if (!fieldName) return undefined;
  return fields?.find((f) => (f.name || (f as any).key) === fieldName);
}

/**
 * 遍历 item.quoteCardValues 各页签(tabs)已渲染的 baseRows，抽取匹配标识值并按值去重。
 *
 * 2026-07-21 更正（需求说明 §4.3 规则一）：标识列**依据组件显式配置取值，不靠字段名/label
 * 含"料号"启发式猜测**——原实现的猜测法已确认为洞，业务已裁决补显式配置。
 *
 * 2026-07-23 修订（匹配标识放宽）：非树页签的标识不一定是料号，也可能是名称（如"外购件/费用"类
 * 页签用"料件名称=组成件1"而无料号列）。取值口径改为 **partNoField 优先，为空则 partNameField 兜底**：
 * - 树页签（`comp.tabType==='BOM'`，或行本身带系统列）取 `__hfPartNo`，两列均可不配。
 * - 非树页签：先试 partNoField，取不到值再试 partNameField；两者都未配置/都取不到值的页签
 *   不产出候选（与后端「不参与类型判定匹配」一致）。
 * - 候选列表因此可能混合料号值与名称值，均为纯字符串，后端按同一口径比对（不做料号↔名称映射）。
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
    const partNoFieldDef = findFieldByName(comp.fields as ComponentField[] | undefined, comp.partNoField);
    const partNameFieldDef = findFieldByName(comp.fields as ComponentField[] | undefined, comp.partNameField);
    const baseRows: any[] = Array.isArray(vtab.baseRows) ? vtab.baseRows : [];
    for (const br of baseRows) {
      let identity: string | null = null;
      if (br?.__hfPartNo) {
        // 树页签系统列最权威，与 partNoField/partNameField 是否配置无关
        identity = String(br.__hfPartNo);
      } else {
        // 非树页签：料号列优先，取不到再退名称列兜底（与后端判定口径一致）
        if (partNoFieldDef) {
          identity = resolveFieldStringValue(partNoFieldDef, br?.driverRow ?? {}, br?.basicDataValues);
        }
        if (identity == null && partNameFieldDef) {
          identity = resolveFieldStringValue(partNameFieldDef, br?.driverRow ?? {}, br?.basicDataValues);
        }
      }
      // 无 __hfPartNo 且 partNoField/partNameField 均未配置或取不到值的页签：不产出候选
      if (!identity || seen.has(identity)) continue;
      seen.add(identity);
      out.push({ partNo: identity, sourceComponentId: cid, sourceTabName: comp.tabName });
    }
  }
  return out;
}
