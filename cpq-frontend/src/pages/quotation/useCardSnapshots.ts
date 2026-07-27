import { useMemo } from 'react';
import { bnfDriverLookupKey } from './useDriverExpansions';
import type {
  CardStructure,
  CardStructureTab,
  CardStructureField,
  CardValues,
  CardValuesTab,
} from '../../services/quotationService';

/**
 * Phase 2 Task 4 — 报价单整份快照「读」hook（**旁路**，不改渲染）。
 *
 * <p>给定 quotation（顶层带 4 份结构快照）+ lineItem（带 4 份值快照 JSON 字符串）+ side，
 * 解析出该侧卡片的 structure + values，并提供按 (componentId, rowIndex, fieldName) 的取值器。
 *
 * <p><b>取值优先级</b>（与前端 ComponentCell / computeAllFormulas 口径对齐）：
 * <ol>
 *   <li>editRows[rowKey].values[field]（用户编辑，最高）</li>
 *   <li>FORMULA → formulaResults[rowKey].values[field]</li>
 *   <li>BASIC_DATA → baseRows[i].basicDataValues[{basicDataPath}]</li>
 *   <li>DATA_SOURCE → basicDataValues 的 @gvar:CODE / {bnf_path}（按 binding）</li>
 *   <li>FIXED_VALUE → field.defaultValue</li>
 *   <li>其余（INPUT 等）→ driverRow[field] ?? field.defaultValue</li>
 * </ol>
 *
 * <p><b>纪律</b>：本 hook 只读快照，**不调** batch-expand / enrich（彻底脱钩）。
 * <p><b>已知边界</b>：LIST_FORMULA 的字符串公式结果暂未进 formulaResults（后端只算 token 型 FORMULA），
 * 旁路阶段落 driverRow/default 兜底，留 Task 8 渲染切换时处理。
 */

export type CardSide = 'QUOTE' | 'COSTING';

export interface CardSnapshotReader {
  /** 该侧结构快照（可能为 null）。 */
  structure: CardStructure | null;
  /** 该侧值快照（已解析；可能为 null）。 */
  values: CardValues | null;
  /** 是否拿到可用快照（structure + values 同时存在且有 tabs）。 */
  hasSnapshot: boolean;
  /** 结构页签（按 sortOrder 已是后端固定顺序）。 */
  tabs: CardStructureTab[];
  /** 某组件的行数（以值快照 baseRows 为准；AP-51 driver 权威）。 */
  rowCount: (componentId: string) => number;
  /** 某组件某行的 rowKey（按 rowKeyFields 从 driverRow 拼；空/哨兵 → 行号）。 */
  rowKey: (componentId: string, rowIndex: number) => string;
  /** 取某组件某行某字段的值（按上面的优先级）。 */
  getCell: (componentId: string, rowIndex: number, fieldName: string) => any;
}

/**
 * 单个 rowKey 段解析（字段感知）。
 *
 * 优先级：
 * 1. driverRow[fieldName] 直读（字段名即为视图列名的旧场景，如 material_no）
 * 2. defaultSource.GLOBAL_VARIABLE → basicDataValues["@gvar:CODE"]
 * 3. defaultSource.BNF_PATH / BASIC_DATA → basicDataValues[bnfDriverLookupKey(path)]
 * 4. 降级：driverRow[path 末段]（path = "$view.col" 时取 "col"，兼容部分旧场景）
 * 全空 → undefined（调用方按行号兜底）。
 *
 * 对齐后端 FormulaCalculator 4-arg computeRowKey 的 resolveRowByFieldName 分支。
 */
function resolveRowKeyPart(
  fieldName: string,
  defaultSource: { type?: string; code?: string; path?: string } | undefined | null,
  driverRow: Record<string, any> | undefined,
  basicDataValues: Record<string, any> | undefined,
): string | undefined {
  // 1. 直读 driverRow（兼容字段名 == 视图列名的旧场景）
  if (driverRow) {
    const direct = driverRow[fieldName];
    if (direct != null && String(direct).length > 0) return String(direct);
  }

  // 2/3. defaultSource 解析
  if (defaultSource && basicDataValues) {
    const dsType = defaultSource.type;
    if (dsType === 'GLOBAL_VARIABLE' && defaultSource.code) {
      const gvKey = `@gvar:${defaultSource.code}`;
      const v = basicDataValues[gvKey];
      if (v != null && String(v).length > 0) return String(v);
    } else if ((dsType === 'BNF_PATH' || dsType === 'BASIC_DATA') && defaultSource.path) {
      const lookupKey = bnfDriverLookupKey(defaultSource.path);
      const v = basicDataValues[lookupKey];
      if (v != null && String(v).length > 0) return String(v);
    }
  }

  // 4. 降级：driverRow[path 末段]（如 "$wgj_view._料件" → "_料件"）
  if (defaultSource?.path && driverRow) {
    const lastSeg = defaultSource.path.split('.').pop() ?? '';
    if (lastSeg) {
      const v = driverRow[lastSeg];
      if (v != null && String(v).length > 0) return String(v);
    }
  }

  return undefined;
}

/**
 * rowKey（字段感知版）：修复 driverRow 键为视图列别名（如 `_料件`）而 rowKeyFields 存字段名
 * （如 `料件`）时直接读 driverRow 取不到值的 bug。
 *
 * 新签名：(fields, rowKeyFields, driverRow, rowIndex, basicDataValues?)
 * 对齐后端 FormulaCalculator.computeRowKey(rowKeyFields, fields, driverRow, basicDataValues)。
 *
 * 分隔符 `||`，全空 → 行号字符串（与后端 null → 调用方按 idx 兜底 对齐）。
 */
export function computeRowKey(
  fields: Array<{ name: string; fieldType?: string; defaultSource?: { type?: string; code?: string; path?: string } | null }> | undefined | null,
  rowKeyFields: string[] | undefined | null,
  driverRow: Record<string, any> | undefined,
  rowIndex: number,
  basicDataValues?: Record<string, any>,
): string {
  if (!rowKeyFields || rowKeyFields.length === 0) return String(rowIndex);
  if (rowKeyFields.length === 1 && rowKeyFields[0] === '__seq_no__') return String(rowIndex);

  // 懒建字段 map（大多数调用只有少量 rowKeyFields，按需查找即可）
  const fieldMap = new Map<string, { defaultSource?: { type?: string; code?: string; path?: string } | null }>();
  for (const f of (fields ?? [])) fieldMap.set(f.name, f);

  let any = false;
  const parts = rowKeyFields.map((fieldName) => {
    const fd = fieldMap.get(fieldName);
    const part = resolveRowKeyPart(fieldName, fd?.defaultSource, driverRow, basicDataValues);
    if (part !== undefined) { any = true; return part; }
    return '';
  });

  // 全空 → 行号（与后端 null → 调用方 effKey=idx 对齐）
  if (!any) return String(rowIndex);
  return parts.join('||');
}

/** 行键唯一化：同一组件内出现 ≥2 次的 rowKey 按出现序追加 `#<0基序号>`；
 *  出现 1 次的键保持原样（向后兼容，现有非撞键报价单 editRows 仍绑定）。
 *  修复撞键导致 editRows 写覆盖/读串行 → resolvedRows「末值×行数」塌缩。
 *  与后端 FormulaCalculator.uniquifyRowKeys 逐字节等价。 */
export function uniquifyRowKeys(keys: string[]): string[] {
  const counts = new Map<string, number>();
  for (const k of keys) counts.set(k, (counts.get(k) ?? 0) + 1);
  const running = new Map<string, number>();
  return keys.map((k) => {
    if ((counts.get(k) ?? 0) <= 1) return k;
    const n = running.get(k) ?? 0;
    running.set(k, n + 1);
    return `${k}#${n}`;
  });
}

/**
 * 按组件 baseRows 成批算 rowKey 并唯一化。序号按 baseRows 数组序（与后端同序）。
 *
 * repair-0727 F0：`applyNodePrefix` 对齐后端 `FormulaCalculator#buildRawRowKeys` 的单一口径
 * —— 后端在「报价侧信号」（`deleted != null`，即使空列表）且该行顶层携带非空 `__nodeId`
 * （树页签行）时，以 `nodeId + "::" + 内容键` 作为最终 rawKey（节点维度天然消歧 DAG 重复子件）。
 * 前端等价信号 = 调用方显式传 `applyNodePrefix=true`（由调用方按自己的 side==='QUOTE' 上下文判断，
 * 与后端 `deleted != null` 语义对齐）；`baseRows[i].__nodeId` 缺失/为空则不加前缀。
 *
 * **不传第 4 参（或传 false）时，逐字节维持旧行为**（核价侧 / 非树行 / 未接入 F0 的历史调用点零影响）。
 *
 * 加前缀发生在 `uniquifyRowKeys` 之前 —— 即先拼 `nodeId::base`，再对拼接后的完整字符串做撞键消歧，
 * 与后端顺序一致（先加前缀、后唯一化），确保 `#N` 消歧序号在两侧算出相同结果。
 */
export function buildUniqueRowKeys(
  fields: any[] | undefined,
  rowKeyFields: string[] | undefined | null,
  baseRows: Array<{ driverRow?: Record<string, any>; basicDataValues?: Record<string, any>; __nodeId?: string | null }> | undefined,
  applyNodePrefix?: boolean,
): string[] {
  const raw = (baseRows ?? []).map((br, i) => {
    const base = computeRowKey(fields, rowKeyFields, br?.driverRow, i, br?.basicDataValues);
    const nodeId = applyNodePrefix ? br?.__nodeId : undefined;
    return nodeId ? `${nodeId}::${base}` : base;
  });
  return uniquifyRowKeys(raw);
}

/**
 * repair-0727 F0：查表旧键回退。新键（树行可能带 `nodeId::` 前缀）未命中时，用调用方并行算出的
 * 旧口径键（不传 `applyNodePrefix`，逐字节等于改造前的 `buildUniqueRowKeys` 产物）再查一次。
 *
 * 兼容存量单据：`editRows`（用户编辑，前端写入时用当时的 rowKey 存）与 `formulaResults`
 * （后端计算结果，仅在下次重算前维持旧值）在 B0/F0 落地前写入的条目都是不带前缀的旧键，
 * 不加回退会让历史编辑值 / 尚未重算的公式结果全部读不到（存量单据"编辑值消失"）。
 *
 * `legacyKey === key`（非树行 / 无 nodeId）时不重复查第二次。
 */
export function getByKeyWithLegacyFallback<T>(
  map: Map<string, T> | undefined,
  key: string,
  legacyKey?: string,
): T | undefined {
  if (!map) return undefined;
  const hit = map.get(key);
  if (hit !== undefined) return hit;
  if (legacyKey !== undefined && legacyKey !== key) return map.get(legacyKey);
  return undefined;
}

function safeParse<T>(json: string | null | undefined): T | null {
  if (!json || typeof json !== 'string' || !json.trim()) return null;
  try {
    return JSON.parse(json) as T;
  } catch {
    return null;
  }
}

function findKeyedValues(
  rows: Array<{ rowKey: string; values: Record<string, any> }> | undefined,
  rowKey: string,
): Record<string, any> | undefined {
  if (!rows) return undefined;
  const found = rows.find((r) => r.rowKey === rowKey);
  return found?.values;
}

function isEmpty(v: any): boolean {
  return v == null || v === '';
}

function resolveDataSource(field: CardStructureField, baseRow: { basicDataValues?: Record<string, any>; driverRow?: Record<string, any> } | undefined): any {
  const binding = field.datasourceBinding;
  const bdv = baseRow?.basicDataValues;
  if (binding && bdv) {
    const dsType = binding.type ?? 'DATABASE_QUERY';
    if (dsType === 'GLOBAL_VARIABLE' && binding.global_variable_code) {
      const v = bdv[`@gvar:${binding.global_variable_code}`];
      if (!isEmpty(v) && !(Array.isArray(v) && v.length === 0)) return v;
    } else if (dsType === 'BNF_PATH' && binding.bnf_path) {
      const v = bdv[bnfDriverLookupKey(binding.bnf_path)];
      if (!isEmpty(v) && !(Array.isArray(v) && v.length === 0)) return v;
    }
  }
  // DATABASE_QUERY / HTTP_API：旁路阶段无实时查询，落 driverRow / default 兜底
  const raw = baseRow?.driverRow?.[field.name];
  if (!isEmpty(raw)) return raw;
  return field.defaultValue ?? null;
}

export function useCardSnapshots(
  quotation: any,
  lineItem: any,
  side: CardSide,
): CardSnapshotReader {
  return useMemo<CardSnapshotReader>(() => {
    const structure: CardStructure | null = side === 'QUOTE'
      ? (quotation?.quoteCardStructure ?? null)
      : (quotation?.costingCardStructure ?? null);

    const valuesJson: string | null = side === 'QUOTE'
      ? (lineItem?.quoteCardValues ?? null)
      : (lineItem?.costingCardValues ?? null);
    const values = safeParse<CardValues>(valuesJson);

    const tabs: CardStructureTab[] = structure?.tabs ?? [];
    const hasSnapshot = !!structure && !!values && tabs.length > 0;

    // componentId → struct tab / value tab 索引
    const structByComp = new Map<string, CardStructureTab>();
    for (const t of tabs) structByComp.set(t.componentId, t);
    const valByComp = new Map<string, CardValuesTab>();
    for (const t of (values?.tabs ?? [])) valByComp.set(t.componentId, t);

    // 每组件唯一化 rowKey 表（撞键消歧）：rowKeyOf/getCell 按下标取，保证与写路径 + 后端一致。
    // repair-0727 F0：QUOTE 侧树行加 nodeId 前缀（对齐后端 buildRawRowKeys）；同时并行算一份
    // legacyKeysByComp（不加前缀，逐字节等于改造前产物），供 getCell 查 editRows/formulaResults
    // 未命中时按同一行位置回退，兼容改造前写入的存量单据（旧键）。COSTING 侧两份表逐字节相同
    // （applyNodePrefix=false 时行为不变），回退恒等价于直接命中，零副作用。
    const applyNodePrefix = side === 'QUOTE';
    const uniqKeysByComp = new Map<string, string[]>();
    const legacyKeysByComp = new Map<string, string[]>();
    for (const t of tabs) {
      const vt = valByComp.get(t.componentId);
      uniqKeysByComp.set(t.componentId, buildUniqueRowKeys(t.fields, t.rowKeyFields, vt?.baseRows, applyNodePrefix));
      legacyKeysByComp.set(t.componentId, buildUniqueRowKeys(t.fields, t.rowKeyFields, vt?.baseRows));
    }

    const rowKeyOf = (componentId: string, rowIndex: number): string => {
      const keys = uniqKeysByComp.get(componentId);
      if (keys && rowIndex < keys.length) return keys[rowIndex];
      // 兜底（无快照/越界）：退回单行算法
      const st = structByComp.get(componentId);
      const vt = valByComp.get(componentId);
      const baseRow = vt?.baseRows?.[rowIndex];
      return computeRowKey(st?.fields, st?.rowKeyFields, baseRow?.driverRow, rowIndex, baseRow?.basicDataValues);
    };

    const rowCount = (componentId: string): number =>
      valByComp.get(componentId)?.baseRows?.length ?? 0;

    const getCell = (componentId: string, rowIndex: number, fieldName: string): any => {
      const st = structByComp.get(componentId);
      const vt = valByComp.get(componentId);
      if (!st || !vt) return undefined;
      const field = st.fields?.find((f) => f.name === fieldName);
      const baseRow = vt.baseRows?.[rowIndex];
      const rk = (uniqKeysByComp.get(componentId)?.[rowIndex])
        ?? computeRowKey(st.fields, st.rowKeyFields, baseRow?.driverRow, rowIndex, baseRow?.basicDataValues);
      // F0：旧键回退 —— 未命中新键（可能带 nodeId 前缀）时按同一行位置试旧键。
      const legacyRk = legacyKeysByComp.get(componentId)?.[rowIndex];

      // 1. 编辑覆盖
      let editVals = findKeyedValues(vt.editRows, rk);
      if (!editVals && legacyRk !== undefined && legacyRk !== rk) editVals = findKeyedValues(vt.editRows, legacyRk);
      if (editVals && !isEmpty(editVals[fieldName])) return editVals[fieldName];

      if (!field) {
        // 未知字段：尝试 driverRow
        return baseRow?.driverRow?.[fieldName];
      }

      // 2. 按字段类型
      switch (field.fieldType) {
        case 'FORMULA': {
          let fr = findKeyedValues(vt.formulaResults, rk);
          if (!fr && legacyRk !== undefined && legacyRk !== rk) fr = findKeyedValues(vt.formulaResults, legacyRk);
          return fr ? fr[fieldName] : undefined;
        }
        case 'BASIC_DATA': {
          if (!field.basicDataPath) return undefined;
          return baseRow?.basicDataValues?.[bnfDriverLookupKey(field.basicDataPath)];
        }
        case 'DATA_SOURCE':
          return resolveDataSource(field, baseRow);
        case 'FIXED_VALUE':
          return field.defaultValue ?? null;
        default: {
          // INPUT / INPUT_NUMBER / LIST_FORMULA 等：driverRow ?? default
          const raw = baseRow?.driverRow?.[fieldName];
          if (!isEmpty(raw)) return raw;
          return field.defaultValue ?? null;
        }
      }
    };

    return { structure, values, hasSnapshot, tabs, rowCount, rowKey: rowKeyOf, getCell };
  }, [quotation, lineItem, side]);
}
