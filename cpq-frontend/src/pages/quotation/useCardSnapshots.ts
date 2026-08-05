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

/** rowKey 解析用的默认值来源（后端 `default_source`）。 */
export interface RowKeyDefaultSource {
  type?: string | null;
  code?: string | null;
  path?: string | null;
}

/** rowKey 解析用的数据源绑定（后端 `datasource_binding`）。 */
export interface RowKeyDatasourceBinding {
  type?: string | null;
  global_variable_code?: string | null;
  bnf_path?: string | null;
}

/**
 * repair-0805 F7 —— rowKey 解析所需的字段形状（**精确类型，禁止再退回 `any[]`**）。
 *
 * 同一份 rowKey 算法被两种键风格的调用方共用，两边都必须能编译期检查：
 * - **camelCase**：`CardStructureTab.fields`（后端 `CardSnapshotService#buildCardStructure` 冻结结构）
 * - **snake_case**：`ComponentField`（`enrichComponentData` 两条路径产出的渲染模型）
 *
 * 与后端 `FormulaCalculator` 的访问器（`:2600-2634`）逐个对齐：每个语义键都 camel/snake 双读。
 *
 * ⚠️ 历史教训（本次事故的元教训）：本类型此前写成 `fields: any[]`，把 6 个调用点的类型检查
 * 全部吞掉 —— 渲染模型是 snake 而解析只认 camel 这件事因此静默了 3 周。**不要再放宽它。**
 */
export interface RowKeyFieldDef {
  name?: string | null;
  /** 后端 `fieldName(f)`：`name` → `key`。 */
  key?: string | null;
  fieldType?: string | null;
  field_type?: string | null;
  defaultSource?: RowKeyDefaultSource | null;
  default_source?: RowKeyDefaultSource | null;
  basicDataPath?: string | null;
  basic_data_path?: string | null;
  datasourceBinding?: RowKeyDatasourceBinding | null;
  datasource_binding?: RowKeyDatasourceBinding | null;
  /** 后端 `content(f)`：`defaultValue` → `content`。 */
  defaultValue?: unknown;
  content?: unknown;
}

// ── 字段访问器：与后端 FormulaCalculator:2600-2634 逐个对齐（snake / camel 双读）───────────
const fieldNameOf = (f: RowKeyFieldDef): string => String(f.name ?? f.key ?? '');
const fieldTypeOf = (f: RowKeyFieldDef): string => String(f.fieldType ?? f.field_type ?? '').toUpperCase();
const defaultSourceOf = (f: RowKeyFieldDef) => f.defaultSource ?? f.default_source ?? null;
const basicDataPathOf = (f: RowKeyFieldDef) => f.basicDataPath ?? f.basic_data_path ?? null;
const datasourceBindingOf = (f: RowKeyFieldDef) => f.datasourceBinding ?? f.datasource_binding ?? null;
const contentOf = (f: RowKeyFieldDef) => f.defaultValue ?? f.content ?? null;

/**
 * 非空判据 —— 对齐后端 `pickNonEmpty:1594-1600`：**空串视为未命中**（不是 `!= null`）。
 * 空数组 `String([]) === ''` 亦落空，与后端 `nonEmpty` 的「空数组 → false」一致。
 */
function nonEmptyStr(v: unknown): string | undefined {
  if (v == null) return undefined;
  const s = String(v);
  return s.length > 0 ? s : undefined;
}

/** `default_source` 取值：GLOBAL_VARIABLE → `@gvar:CODE`；BNF_PATH / BASIC_DATA → `{path}`。 */
function fromDefaultSource(
  ds: RowKeyDefaultSource | null,
  basicDataValues: Record<string, any> | undefined,
): string | undefined {
  if (!ds || !basicDataValues) return undefined;
  const dsType = ds.type;
  if (dsType === 'GLOBAL_VARIABLE' && ds.code) {
    return nonEmptyStr(basicDataValues[`@gvar:${ds.code}`]);
  }
  if ((dsType === 'BNF_PATH' || dsType === 'BASIC_DATA') && ds.path) {
    return nonEmptyStr(basicDataValues[bnfDriverLookupKey(ds.path)]);
  }
  return undefined;
}

/** `basic_data_path` 取值（后端 BASIC_DATA 分支 `:1775-1780`）。 */
function fromBasicDataPath(
  path: string | null,
  basicDataValues: Record<string, any> | undefined,
): string | undefined {
  if (!path || !basicDataValues) return undefined;
  return nonEmptyStr(basicDataValues[bnfDriverLookupKey(path)]);
}

/** `datasource_binding` 取值（后端 DATA_SOURCE 分支 `:1792-1814`）。 */
function fromDatasourceBinding(
  binding: RowKeyDatasourceBinding | null,
  basicDataValues: Record<string, any> | undefined,
): string | undefined {
  if (!binding || !basicDataValues) return undefined;
  const t = binding.type ?? 'DATABASE_QUERY';
  if (t === 'GLOBAL_VARIABLE' && binding.global_variable_code) {
    return nonEmptyStr(basicDataValues[`@gvar:${binding.global_variable_code}`]);
  }
  if (t === 'BNF_PATH' && binding.bnf_path) {
    return nonEmptyStr(basicDataValues[bnfDriverLookupKey(binding.bnf_path)]);
  }
  return undefined;
}

/**
 * 单个 rowKey 段解析（字段感知）—— **逐条镜像后端 `FormulaCalculator#resolveRowByFieldName`**
 * （`:1714-1824`，口径书面化见 `dev-docs/.../repair-0805-.../backend-rowkey-contract.md` §1.1）。
 *
 * ```
 * ① driverRow[fieldName] 直读                （后端 pickNonEmpty:1427，空串 = 未命中）
 * ② 按 field_type 分支：
 *    FORMULA / LIST_FORMULA          → 无（后端取 formulaValues，rowKey 场景恒传 null）
 *    BASIC_DATA                      → basic_data_path → content
 *    DATA_SOURCE                     → datasource_binding → content
 *    INPUT / INPUT_TEXT / INPUT_NUMBER → default_source → content
 *    FIXED_VALUE                     → content
 *    (缺失 / 未知类型)                → default_source → basic_data_path → datasource_binding → content
 * ```
 * 全空 → `undefined`（调用方按行号兜底，对齐后端 `!any → null` + `buildRawRowKeys:1481`）。
 *
 * **repair-0805 F5 相对旧实现的三处改动**（依据后端契约文档 §1.7 未对齐表）：
 * - **X1**：`default_source` 现在 camel/snake 双读 —— 旧实现只认 `f.defaultSource`，
 *   而渲染模型统一输出 `default_source` → 179 处绑定（106 个组件）解析恒落空、退化成行号。
 * - **X2**：补齐 `BASIC_DATA` 字段按 `basic_data_path` 解析这一级 —— 旧实现完全没有这条通路，
 *   42 处绑定（17 个核价通用模板组件 COMP-0048~0063）**连 camelCase 调用方也一起坏着**。
 * - **X3/X4**：补齐 `content`/`defaultValue` 兜底与按 `field_type` 分支（当前库 0 处命中，
 *   属把口径补完整，不改变现有数据的结果）。
 *
 * **删掉的一级（X5）**：旧实现第 4 级「降级读 `driverRow[path 末段]`」后端并不存在。
 * 保留它会在「`basicDataValues` 缺该键但 `driverRow` 有同名别名列」时让前端算出内容键、
 * 后端算出行号 —— 即用一个新分歧换掉旧分歧，快照照样命中不了。故按「以后端口径为准」删除。
 * 详细论证见提交说明。
 */
function resolveRowKeyPart(
  fieldName: string,
  field: RowKeyFieldDef | undefined,
  driverRow: Record<string, any> | undefined,
  basicDataValues: Record<string, any> | undefined,
): string | undefined {
  // ① 直读 driverRow（兼容字段名 == 视图列名的旧场景）
  const direct = driverRow ? nonEmptyStr(driverRow[fieldName]) : undefined;
  if (direct !== undefined) return direct;
  if (!field) return undefined;

  // ② 按 field_type 分支
  const content = () => nonEmptyStr(contentOf(field));
  switch (fieldTypeOf(field)) {
    case 'FORMULA':
    case 'LIST_FORMULA':
      return undefined;
    case 'BASIC_DATA':
      return fromBasicDataPath(basicDataPathOf(field), basicDataValues) ?? content();
    case 'DATA_SOURCE':
      return fromDatasourceBinding(datasourceBindingOf(field), basicDataValues) ?? content();
    case 'INPUT':
    case 'INPUT_TEXT':
    case 'INPUT_NUMBER':
      return fromDefaultSource(defaultSourceOf(field), basicDataValues) ?? content();
    case 'FIXED_VALUE':
      return content();
    default:
      // 类型缺失 / 未知：取各分支并集（后端此处落 FIXED_VALUE 分支，但前端渲染模型经
      // normalizeFieldType 后类型恒非空、结构快照由后端白名单写入亦恒非空 —— 真实数据到不了这里。
      // 走并集是为「形状不全的调用方」保持既有的宽松行为，不收窄。
      return fromDefaultSource(defaultSourceOf(field), basicDataValues)
        ?? fromBasicDataPath(basicDataPathOf(field), basicDataValues)
        ?? fromDatasourceBinding(datasourceBindingOf(field), basicDataValues)
        ?? content();
  }
}

/**
 * repair-0805 F6 —— **旧口径**单段解析：逐字节冻结 F5 之前的实现，只用来读存量键。
 *
 * F5 把前端键从「解析落空 → 行号」改成了「内容键」。存量 `editRows` 是用户在 F5 之前编辑时
 * 由前端算出的键写进去的（行号形态），换口径后会变孤儿。渲染层查表未命中新键时按本函数
 * 算出的旧键再试一次即可读回。
 *
 * **不要"修正"本函数** —— 它的价值恰恰在于复刻旧的（错的）口径，包括只认 camelCase
 * `defaultSource`、以及那一级后端没有的 `driverRow[path 末段]` 降级。
 */
function resolveRowKeyPartLegacy(
  fieldName: string,
  field: RowKeyFieldDef | undefined,
  driverRow: Record<string, any> | undefined,
  basicDataValues: Record<string, any> | undefined,
): string | undefined {
  const defaultSource = field?.defaultSource ?? undefined;
  if (driverRow) {
    const direct = driverRow[fieldName];
    if (direct != null && String(direct).length > 0) return String(direct);
  }
  if (defaultSource && basicDataValues) {
    const dsType = defaultSource.type;
    if (dsType === 'GLOBAL_VARIABLE' && defaultSource.code) {
      const v = basicDataValues[`@gvar:${defaultSource.code}`];
      if (v != null && String(v).length > 0) return String(v);
    } else if ((dsType === 'BNF_PATH' || dsType === 'BASIC_DATA') && defaultSource.path) {
      const v = basicDataValues[bnfDriverLookupKey(defaultSource.path)];
      if (v != null && String(v).length > 0) return String(v);
    }
  }
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
  fields: RowKeyFieldDef[] | undefined | null,
  rowKeyFields: string[] | undefined | null,
  driverRow: Record<string, any> | undefined,
  rowIndex: number,
  basicDataValues?: Record<string, any>,
  /** repair-0805 F6：true = 用 F5 之前的旧口径解析（只读存量键用，见 resolveRowKeyPartLegacy）。 */
  legacyResolution?: boolean,
): string {
  if (!rowKeyFields || rowKeyFields.length === 0) return String(rowIndex);
  if (rowKeyFields.length === 1 && rowKeyFields[0] === '__seq_no__') return String(rowIndex);

  // 懒建字段 map（大多数调用只有少量 rowKeyFields，按需查找即可）
  const fieldMap = new Map<string, RowKeyFieldDef>();
  for (const f of (fields ?? [])) fieldMap.set(fieldNameOf(f), f);

  const resolve = legacyResolution ? resolveRowKeyPartLegacy : resolveRowKeyPart;
  let any = false;
  const parts = rowKeyFields.map((fieldName) => {
    const part = resolve(fieldName, fieldMap.get(fieldName), driverRow, basicDataValues);
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
  fields: RowKeyFieldDef[] | undefined | null,
  rowKeyFields: string[] | undefined | null,
  baseRows: Array<{ driverRow?: Record<string, any>; basicDataValues?: Record<string, any>; __nodeId?: string | null }> | undefined,
  applyNodePrefix?: boolean,
  /**
   * repair-0805 F6：true = 按 F5 之前的旧口径算键（解析落空 → 行号），**只用于查存量数据**。
   * 与 `applyNodePrefix` 正交 —— 两个开关组合出三种历史键形态，见 `buildLegacyRowKeySets`。
   */
  legacyResolution?: boolean,
): string[] {
  const raw = (baseRows ?? []).map((br, i) => {
    const base = computeRowKey(fields, rowKeyFields, br?.driverRow, i, br?.basicDataValues, legacyResolution);
    const nodeId = applyNodePrefix ? br?.__nodeId : undefined;
    return nodeId ? `${nodeId}::${base}` : base;
  });
  return uniquifyRowKeys(raw);
}

/**
 * repair-0805 F6 —— 一次算齐**全部历史口径键**，供渲染层查表未命中新键时按序回退。
 *
 * 键口径共经历两次换代，库里因此可能同时存在三种形态：
 *
 * | 写入时期 | `nodeId::` 前缀 | 段解析口径 | 本函数产出 |
 * |---|---|---|---|
 * | repair-0727 F0 之前 | 无 | 旧（camel-only `defaultSource`） | `legacyNoPrefix` |
 * | F0 之后 ~ 0805 F5 之前 | 有（报价侧树行） | 旧 | `legacyPrefixed` |
 * | F5 之后（当前） | 有 | 新（camel/snake 双读 + `basic_data_path`） | 新键，不由本函数产出 |
 *
 * 核价侧 / 非树行 `applyNodePrefix=false` 时两者逐字节相同，`getByKeyWithLegacyFallback` 内去重。
 */
export function buildLegacyRowKeySets(
  fields: RowKeyFieldDef[] | undefined | null,
  rowKeyFields: string[] | undefined | null,
  baseRows: Array<{ driverRow?: Record<string, any>; basicDataValues?: Record<string, any>; __nodeId?: string | null }> | undefined,
  applyNodePrefix?: boolean,
): { legacyPrefixed: string[]; legacyNoPrefix: string[] } {
  return {
    legacyPrefixed: buildUniqueRowKeys(fields, rowKeyFields, baseRows, applyNodePrefix, true),
    legacyNoPrefix: buildUniqueRowKeys(fields, rowKeyFields, baseRows, false, true),
  };
}

/**
 * 查表旧键回退。新键未命中时，按调用方给出的**历史口径键**依次再查。
 *
 * 兼容存量单据：`editRows`（用户编辑，前端写入时用当时的 rowKey 存）与 `formulaResults`
 * （后端计算结果，仅在下次重算前维持旧值）在换代前写入的条目都是老形态的键，
 * 不加回退会让历史编辑值 / 尚未重算的公式结果全部读不到（存量单据"编辑值消失"）。
 *
 * - repair-0727 F0：第一档 legacy = 无 `nodeId::` 前缀。
 * - repair-0805 F6：追加一档 legacy = **旧段解析口径**（解析落空 → 行号）。
 *   两档正交，故按 `buildLegacyRowKeySets` 的产物**依次**传入即可。
 *
 * 与 key 相同 / 彼此相同的候选只查一次（既有优化保留，避免同一 Map 反复 get）。
 */
export function getByKeyWithLegacyFallback<T>(
  map: Map<string, T> | undefined,
  key: string,
  ...legacyKeys: Array<string | undefined>
): T | undefined {
  if (!map) return undefined;
  const hit = map.get(key);
  if (hit !== undefined) return hit;
  const tried = new Set<string>([key]);
  for (const lk of legacyKeys) {
    if (lk === undefined || tried.has(lk)) continue;
    tried.add(lk);
    const v = map.get(lk);
    if (v !== undefined) return v;
  }
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

/** 数组版查表旧键回退（editRows / formulaResults 是数组不是 Map），语义同 getByKeyWithLegacyFallback。 */
function findKeyedValuesWithLegacy(
  rows: Array<{ rowKey: string; values: Record<string, any> }> | undefined,
  rowKey: string,
  legacyKeys: Array<string | undefined>,
): Record<string, any> | undefined {
  if (!rows) return undefined;
  const hit = findKeyedValues(rows, rowKey);
  if (hit) return hit;
  const tried = new Set<string>([rowKey]);
  for (const lk of legacyKeys) {
    if (lk === undefined || tried.has(lk)) continue;
    tried.add(lk);
    const v = findKeyedValues(rows, lk);
    if (v) return v;
  }
  return undefined;
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
    // repair-0727 F0：QUOTE 侧树行加 nodeId 前缀（对齐后端 buildRawRowKeys）。
    // repair-0805 F6：同时并行算两档历史口径键（见 buildLegacyRowKeySets），供 getCell 查
    // editRows/formulaResults 未命中新键时按同一行位置依次回退，兼容两次换代前写入的存量单据。
    // COSTING 侧两档逐字节相同，getByKeyWithLegacyFallback 内去重，零额外开销。
    const applyNodePrefix = side === 'QUOTE';
    const uniqKeysByComp = new Map<string, string[]>();
    const legacyKeysByComp = new Map<string, { legacyPrefixed: string[]; legacyNoPrefix: string[] }>();
    for (const t of tabs) {
      const vt = valByComp.get(t.componentId);
      uniqKeysByComp.set(t.componentId, buildUniqueRowKeys(t.fields, t.rowKeyFields, vt?.baseRows, applyNodePrefix));
      legacyKeysByComp.set(t.componentId, buildLegacyRowKeySets(t.fields, t.rowKeyFields, vt?.baseRows, applyNodePrefix));
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
      // F0 + F6：旧键回退 —— 未命中新键时按同一行位置依次试两档历史口径键。
      const legacySets = legacyKeysByComp.get(componentId);
      const legacyRks = [legacySets?.legacyPrefixed?.[rowIndex], legacySets?.legacyNoPrefix?.[rowIndex]];

      // 1. 编辑覆盖
      const editVals = findKeyedValuesWithLegacy(vt.editRows, rk, legacyRks);
      if (editVals && !isEmpty(editVals[fieldName])) return editVals[fieldName];

      if (!field) {
        // 未知字段：尝试 driverRow
        return baseRow?.driverRow?.[fieldName];
      }

      // 2. 按字段类型
      switch (field.fieldType) {
        case 'FORMULA': {
          const fr = findKeyedValuesWithLegacy(vt.formulaResults, rk, legacyRks);
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
