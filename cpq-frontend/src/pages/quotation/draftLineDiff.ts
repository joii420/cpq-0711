/**
 * task-260901 ②：保存草稿的**行级增量 diff**。
 *
 * 背景：`PUT /draft` 的请求体从「全量 lineItems」改成 `{added[], modified[], removed[]}` 之后，
 * 前端必须自己回答「哪些行真的变了」。本模块负责那个判定。
 *
 * ── 为什么指纹算在 LineItem（React state）上，而不是算在 payload 行上 ──────────────
 * `fronttask.md` F-1a 原文写的是「`stableDraftDedupKey` 的单行版本，口径必须与它一致
 * ——剔除 …/`componentData[].rowData`/…」。**照做会丢数据**：
 *   · payload 行里能代表「用户在页签里改了哪个格子」的字段**只有** `componentData[].rowData`；
 *   · 把 rowData 剔掉之后，payload 的 componentData 只剩 `{componentId, tabName, sortOrder}`，
 *     对单元格编辑完全盲 ⇒ AC-1（改一个格子 → modified 长度 1）和 AC-10（差一位小数必须判已变）
 *     双双失败，而且是**静默**失败：请求照发，只是那一行永远不在 modified 里。
 * 但 rowData 本身又不能直接进指纹：它是 `snapshotRows()` 从 `driverExpansions` **重算**出来的，
 * 而 `useSnapAll` 会在每次保存前后 live↔snapshot 翻转（见 `draftPayloadDedup.ts` 头注的三连发教训），
 * 同一份用户数据能算出**字符串不同**的 rowData ⇒ 1845 行会整体涌进 `modified`，AC-1 同样失败。
 *
 * 解法：取两者的**共同上游** —— `componentData[].rows`（React state 里的行数据，
 * 单元格编辑经 `handleRowChange` 直接写它，加载时由 `enrichComponentData` 从 rowData 解析而来）。
 * 它既能捕捉用户编辑，又不随 expansion 翻转而抖动。
 *
 * ⚠️ 本模块的失败方向必须是「多报 modified」（多存一行，慢一点），
 * 绝不能是「漏报」（那是静默丢数据）。新增字段时按此取舍。
 */

/** 稳定序列化：对象键递归排序，消除 `{a,b}` / `{b,a}` 这类顺序噪声造成的假阳性。 */
export function stableStringify(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value ?? null) ?? 'null';
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`;
  const obj = value as Record<string, unknown>;
  const keys = Object.keys(obj).sort();
  return `{${keys.map(k => `${JSON.stringify(k)}:${stableStringify(obj[k])}`).join(',')}}`;
}

/**
 * 行内容指纹的取值面。
 *
 * 收录：所有会随用户操作变化、且 `saveDraft` 会落库的行级字段 + 页签行数据。
 * 剔除：
 *   · `id` / `partVersionLocked` —— 服务端身份与派生版本号；
 *   · `subtotal` / `quoteExcelValues` / 四份卡片值 —— 派生数据（与 `stableDraftDedupKey` 同口径）；
 *   · `sortOrder` —— 见下方 {@link buildLineFingerprintMap} 的说明。
 */
function fingerprintSource(li: any): unknown {
  return {
    productId: li?.productId || null,
    templateId: li?.templateId || null,
    productPartNo: li?.productPartNo || null,
    productName: li?.productName || null,
    // buildDraftPayload 的同款兜底（customerProductNo → customerPartNo）
    customerPartNo: li?.customerPartNo || li?.customerProductNo || null,
    productAttributeValues: li?.productAttributeValues || {},
    processNos: Array.isArray(li?.processNos) ? li.processNos : [],
    compositeProcesses: Array.isArray(li?.compositeProcesses) ? li.compositeProcesses : [],
    seedProcessesFromBase: li?.seedProcessesFromBase ?? null,
    compositeType: li?.compositeType ?? null,
    parentLineItemId: li?.parentLineItemId ?? null,
    annualVolume: li?.annualVolume ?? null,
    discountSource: li?.discountSource ?? null,
    discountBaseAmount: li?.discountBaseAmount ?? null,
    discountRateApplied: li?.discountRateApplied ?? null,
    lineDiscountAmount: li?.lineDiscountAmount ?? null,
    lineUnitPrice: li?.lineUnitPrice ?? null,
    lineFinalPrice: li?.lineFinalPrice ?? null,
    lineTotalAmount: li?.lineTotalAmount ?? null,
    discountRuleCode: li?.discountRuleCode ?? null,
    componentData: (Array.isArray(li?.componentData) ? li.componentData : []).map((cd: any) => ({
      componentId: cd?.componentId || null,
      tabName: cd?.tabName || '',
      // 用户单元格编辑的唯一权威存储（handleRowChange / handleInputBlur / handleAddRow /
      // handleDeleteRow / applyQuoteProjection 全都写它）。
      rows: Array.isArray(cd?.rows) ? cd.rows : [],
      // driver 行墓碑：删行也是用户数据，漏掉会让删除在下一次保存被"复活"。
      deletedRowKeys: cd?.deletedRowKeys ?? null,
    })),
  };
}

/** 单行内容指纹。 */
export function lineFingerprint(li: any): string {
  return stableStringify(fingerprintSource(li));
}

/**
 * 行在基线里的键：已持久化行用 DB id，未持久化行用 `t:<tempId>`。
 * 两者都没有的行不可追踪（返回 null），由调用方按「新增」处理。
 */
export function lineBaselineKey(li: any): string | null {
  const id = li?.id;
  if (id != null && String(id) !== '') return String(id);
  const tempId = li?.tempId;
  if (tempId != null && String(tempId) !== '') return `t:${String(tempId)}`;
  return null;
}

export type LineFingerprintMap = Map<string, string>;

/**
 * 为整份 lineItems 建指纹表。
 *
 * ⚠️ **不含 `sortOrder`**：删掉中间一行会让其后所有行的下标整体前移，若把下标计入指纹，
 * 「删 1 行」会连带把后面 1844 行判成 modified（AC-3 要求此时 `modified` 为空数组）。
 * 报价单没有拖拽排序入口（产品只能追加/删除），删行后库里剩余行的 sort_order 虽出现空档
 * 但**相对顺序不变**，故不追踪它是安全的。将来若加了「上移/下移产品」，本条必须重新评估。
 */
export function buildLineFingerprintMap(lineItems: any[]): LineFingerprintMap {
  const map: LineFingerprintMap = new Map();
  for (const li of lineItems || []) {
    const key = lineBaselineKey(li);
    if (!key) continue;
    map.set(key, lineFingerprint(li));
  }
  return map;
}

export interface LineDiffResult {
  /** 未持久化行（payload 里 id 为 null）在 lineItems 中的下标。 */
  addedIndexes: number[];
  /** 内容相对基线发生变化的已持久化行下标。 */
  modifiedIndexes: number[];
  /** 基线里有、当前 lineItems 已不存在的 DB id。 */
  removedIds: string[];
}

/**
 * 与基线比对得出三数组。
 *
 * @param baseline   上一次「前端 == 库」时刻的指纹表
 * @param lineItems  当前 React state
 * @param persistedIdOf 取该行**发往后端的 id**（= `li.id || dbIdByTempId[tempId] || null`）。
 *                      必须与 `buildDraftPayload` 用的是同一口径，否则 added/modified 会分错边。
 */
export function diffLineItems(
  baseline: LineFingerprintMap,
  lineItems: any[],
  persistedIdOf: (li: any) => string | null,
): LineDiffResult {
  const addedIndexes: number[] = [];
  const modifiedIndexes: number[] = [];
  const seenIds = new Set<string>();

  (lineItems || []).forEach((li, idx) => {
    const persistedId = persistedIdOf(li);
    if (!persistedId) {
      // 没有 DB id ⇒ 从未落库 ⇒ 新增。判据与 buildDraftPayload 送出的 id 完全一致，
      // 不另立标准（否则会出现「payload 带 id 却被归进 added」这种后端必然 400 的组合）。
      addedIndexes.push(idx);
      return;
    }
    seenIds.add(persistedId);
    const base = baseline.get(persistedId) ?? baseline.get(`t:${String((li as any)?.tempId ?? '')}`);
    // 基线里查不到（首次保存 / 刚认领到新 id）⇒ 保守判「已变」，宁可多发一行。
    if (base === undefined || base !== lineFingerprint(li)) modifiedIndexes.push(idx);
  });

  const removedIds: string[] = [];
  for (const key of baseline.keys()) {
    if (key.startsWith('t:')) continue;     // 未持久化行不存在「删除」
    if (!seenIds.has(key)) removedIds.push(key);
  }

  return { addedIndexes, modifiedIndexes, removedIds };
}

/** 「没有任何行级改动」的 diff 常量（只改单头时用，AC-4 要求三数组全为空）。 */
export const EMPTY_LINE_DIFF: LineDiffResult = { addedIndexes: [], modifiedIndexes: [], removedIds: [] };

/**
 * 把「全量工作形状」的 payload（`{单头…, lineItems: LineItemDraft[]}`）转成
 * `api.md §1.2` 的线上形状（`{单头…, baseVersion, added, modified, removed}`）。
 *
 * 为什么保留全量工作形状：`stableDraftDedupKey` / `lineItemsDedupKey` / `headerDedupKey`
 * 与 localStorage 兜底备份都按 `payload.lineItems` 取值（`repair-260830` 的两层闸建立在它们之上）。
 * 在最后一步才转形状，那三个既有口径一个字都不用改。
 *
 * ⚠️ `added` 元素的 `id` 必须为 null、`modified` 元素的 `id` 必须非 null（否则后端 400）。
 * 本函数不做纠正，靠 `diffLineItems` 的判据与 `buildDraftPayload` 的 id 口径同源来保证。
 *
 * 🚫 `baseVersion` 是 `number` 而**不是** `number | null`：后端只要收到三数组就必然走增量协议，
 * 此时 `baseVersion` 缺失 = 400（`QuotationService.java:372-374`），而前端的 catch 会把它吞成
 * 「已保存到本地，网络恢复后将同步」——失败被伪装成成功。基线未知时的正确动作是**在调用本函数
 * 之前就拦下不发**（见 `QuotationWizard#requireVersionBaseline`），所以这里用类型把 null 挡在门外，
 * 让「忘了拦」变成一个编译错误而不是一条线上的静默丢数据。
 */
export function toIncrementalPayload(
  fullPayload: any,
  diff: LineDiffResult,
  baseVersion: number,
): any {
  const { lineItems: allLines, ...header } = fullPayload || {};
  const pick = (idxs: number[]) => idxs
    .map(i => (Array.isArray(allLines) ? allLines[i] : undefined))
    .filter((x: any) => x != null);
  return {
    ...header,
    baseVersion,
    added: pick(diff.addedIndexes),
    modified: pick(diff.modifiedIndexes),
    removed: [...diff.removedIds],
  };
}
