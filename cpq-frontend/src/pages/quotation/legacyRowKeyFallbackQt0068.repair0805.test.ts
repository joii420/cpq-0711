/**
 * repair-0805 / BL-0112 —— T8（阶段二 · F6：存量 legacy 键回退，AC-8）
 *
 * ## 这条测试守的是什么
 *
 * F5 把前端 rowKey 的段解析口径换了代（camel-only `defaultSource` → camel/snake 双读 +
 * `basic_data_path`）。换代的**代价**是：F5 之前由前端算键写进库的存量条目，用新口径再也查不到。
 * F6 的 `buildLegacyRowKeySets` + `getByKeyWithLegacyFallback` 就是这笔代价的对冲。
 *
 * 键口径共两次换代，库里可能并存三代形态：
 *
 * | 写入时期                | `nodeId::` 前缀 | 段解析 | QT0068「物料」的实际形态         |
 * |---|---|---|---|
 * | repair-0727 F0 之前     | 无              | 旧     | `"0" … "5"`（纯行号）            |
 * | F0 之后 ~ 0805 F5 之前  | 有              | 旧     | `"3120011203::0"` …（前缀+行号） |
 * | F5 之后（当前）         | 有              | 新     | `"3120011203/3110520422::AgNi10/Cu触点"` … |
 *
 * ## ⚠️ 两条回退通路的存活状态（2026-08-05 实测，勿据用例名想当然）
 *
 * | 消费者 | 查的表 | 状态 |
 * |---|---|---|
 * | `QuotationStep2.tsx:3291` `getByKeyWithLegacyFallback(activeSnap?.formula, …)` | `formulaResults` | ✅ **活的** |
 * | `ReadonlyProductCard.tsx:694` 同上 | `formulaResults` | ✅ **活的** |
 * | `QuotationStep2.tsx:2663` `snapByComp` 里建的 `edit` Map | `editRows` | ❌ **死代码**：全文件无人读 `.edit`（只读 `.formula` / `.driverRows`），原因见 `QuotationStep2.tsx:2614-2615`（INPUT 受控值仍读 `comp.rows`，叠 `editRows` 会丢按键 = AP-54） |
 * | `useCardSnapshots#getCell` 内 `findKeyedValuesWithLegacy(vt.editRows, …)` | `editRows` | ❌ **死代码**：`useCardSnapshots` hook 本体全工程零调用点（仅测试文件 import 它导出的纯函数） |
 *
 * 所以 **T8.2 / T8.3（formulaResults）测的是线上真跑的那条路**；
 * **T8.4（editRows 形态）测的是同一个纯函数在 editRows 语义下的行为，当前无活调用方** ——
 * 保留它是因为 editRows 回退随时可能被重新接活（Task3 注释里那条路径），
 * 但**不许**把它当成"存量单据里用户编辑值不会丢"的现网证据。
 *
 * @see dev-docs/repair-0803-BL0098-公式绑定改绑ID/repair-0805-渲染侧丢公式id致公式列全空/test.md §T8
 */
import { describe, it, expect } from 'vitest';
import { buildUniqueRowKeys, buildLegacyRowKeySets, getByKeyWithLegacyFallback } from './useCardSnapshots';
import {
  QT0068, WULIAO_FORMULA_COLUMNS, QT0068_QUOTE_CARD_VALUES,
  structureTab, valuesTab, buildQt0068ComponentData, buildQt0068ExpansionLookup, deepClone,
} from './__fixtures__/qt20260804-0068';

const backendTab = valuesTab(QT0068.wuliaoTabName);
const BASE_ROWS = backendTab.baseRows as any[];
/** 后端 `FormulaCalculator#buildRawRowKeys` 落库的 6 个当代键。 */
const BACKEND_ROW_KEYS: string[] = backendTab.formulaResults.map((r: any) => r.rowKey);

/** 渲染模型字段（snake `default_source`）—— 三个活调用点真实传进去的那份。 */
function renderModelFields() {
  const cd = buildQt0068ComponentData();
  return cd.find(c => c.componentId === QT0068.wuliaoComponentId)!.fields as any[];
}
/** 结构快照字段（camel `defaultSource`）。 */
function structureFields() {
  return structureTab(QT0068.wuliaoTabName).fields as any[];
}

/** F0 之前那代：无前缀 + 旧解析 → 纯行号（渲染模型 snake 形状下旧解析恒落空）。 */
const EXPECTED_LEGACY_NO_PREFIX = ['0', '1', '2', '3', '4', '5'];
/** F0 之后 ~ F5 之前那代：有前缀 + 旧解析 → `nodeId::行号`。 */
const EXPECTED_LEGACY_PREFIXED = [
  '3120011203::0',
  '3120011203/3110520422::1',
  '3120011203/00144::2',
  '3120011203/3110520422/00255::3',
  '3120011203/3110520422/00256::4',
  '3120011203/3110520422/00257::5',
];

/** 计数 Map：断言"同一个键不重复查"这条既有优化没被 F6 的可变参数改造弄丢。 */
class CountingMap<T> extends Map<string, T> {
  readonly gets: string[] = [];
  override get(k: string): T | undefined {
    this.gets.push(k);
    return super.get(k);
  }
}

// ═══════════════════════════════════════════════════════════════════════════
// T8.0 —— buildLegacyRowKeySets 两档产物 = F5 之前的真实口径
// ═══════════════════════════════════════════════════════════════════════════
describe('repair-0805 T8.0 · buildLegacyRowKeySets 复刻的确实是 F5 之前那两代口径', () => {
  it('T8.0a 渲染模型（snake）：legacyNoPrefix = 纯行号（F0 之前那代）', () => {
    // F5 之前的 resolveRowKeyPart 只认 camel `f.defaultSource`，渲染模型只有 snake
    // `default_source` → 解析恒落空；driverRow 的列名是 `_料件` 而字段名是 `料件` → 直读也落空
    // → 全空 → 行号兜底。这正是问题分析报告 §4.1 描述的那个退化。
    const sets = buildLegacyRowKeySets(renderModelFields(), QT0068.wuliaoRowKeyFields, BASE_ROWS, true);
    expect(sets.legacyNoPrefix).toEqual(EXPECTED_LEGACY_NO_PREFIX);
  });

  it('T8.0b 渲染模型（snake）：legacyPrefixed = nodeId::行号（F0 之后 ~ F5 之前那代）', () => {
    const sets = buildLegacyRowKeySets(renderModelFields(), QT0068.wuliaoRowKeyFields, BASE_ROWS, true);
    expect(sets.legacyPrefixed).toEqual(EXPECTED_LEGACY_PREFIXED);
  });

  it('T8.0c 当代键与两档 legacy 互不相同 —— 否则回退是空转，T8.2/T8.3 恒绿', () => {
    const nowKeys = buildUniqueRowKeys(renderModelFields(), QT0068.wuliaoRowKeyFields, BASE_ROWS, true);
    expect(nowKeys).toEqual(BACKEND_ROW_KEYS);            // F5 后前后端一致（T7.1 的结论）
    expect(nowKeys).not.toEqual(EXPECTED_LEGACY_PREFIXED);
    expect(nowKeys).not.toEqual(EXPECTED_LEGACY_NO_PREFIX);
    // 第 0 行（料件为空）三代恰好都落 `3120011203::0`；其余 5 行三代两两不同。
    expect(nowKeys[0]).toBe(EXPECTED_LEGACY_PREFIXED[0]);
    for (let i = 1; i < 6; i++) {
      expect(nowKeys[i], `第 ${i} 行当代键不应与 legacyPrefixed 相同`).not.toBe(EXPECTED_LEGACY_PREFIXED[i]);
      expect(nowKeys[i], `第 ${i} 行当代键不应与 legacyNoPrefix 相同`).not.toBe(EXPECTED_LEGACY_NO_PREFIX[i]);
    }
  });

  it('T8.0d 结构快照（camel）：legacy 与当代键相同 —— 复刻的正是"只认 camelCase"这个特征', () => {
    // F5 之前 camel 形状的调用方本来就解析得通，故 legacy 口径在 camel 下与新口径重合。
    // 这条同时证明 legacy 不是"无脑退行号"，而是真的按旧算法逐级跑。
    const camel = structureFields();
    const sets = buildLegacyRowKeySets(camel, QT0068.wuliaoRowKeyFields, BASE_ROWS, true);
    const nowKeys = buildUniqueRowKeys(camel, QT0068.wuliaoRowKeyFields, BASE_ROWS, true);
    expect(sets.legacyPrefixed).toEqual(nowKeys);
    expect(sets.legacyPrefixed).toEqual(BACKEND_ROW_KEYS);
    // 无前缀那档只差 `nodeId::` 这一层
    expect(sets.legacyNoPrefix).toEqual(
      BACKEND_ROW_KEYS.map((k, i) => k.slice(`${BASE_ROWS[i].__nodeId}::`.length)),
    );
  });

  it('T8.0e applyNodePrefix=false（核价侧 / 非树行）时两档逐字节相同', () => {
    const sets = buildLegacyRowKeySets(renderModelFields(), QT0068.wuliaoRowKeyFields, BASE_ROWS, false);
    expect(sets.legacyPrefixed).toEqual(sets.legacyNoPrefix);
  });

  it('T8.0f legacy 口径保留了后端没有的「driverRow[path 末段]」那一级（F5 删掉的 X5）', () => {
    // 旧实现第 4 级：defaultSource.path = "$v.料件" → 末段 "料件" → 读 driverRow["料件"]。
    // 新口径已按后端契约删除这一级；legacy 必须留着，否则读不回那代写下的键。
    // 对照组：driverRow 有与字段名同名的列 → 第 1 级就命中，两代同结果
    const f1 = [{ name: '料件', fieldType: 'INPUT_TEXT', defaultSource: { type: 'BASIC_DATA', path: '$v.料件' } }];
    const r1 = [{ driverRow: { 料件: 'X1' }, basicDataValues: {} }];
    expect(buildLegacyRowKeySets(f1, ['料件'], r1).legacyNoPrefix).toEqual(['X1']);
    expect(buildUniqueRowKeys(f1, ['料件'], r1)).toEqual(['X1']);
    // 第 4 级专项：driverRow 无字段名同名列，但有 path 末段同名列 → 只有 legacy 认
    const f2 = [{ name: '料件', fieldType: 'INPUT_TEXT', defaultSource: { type: 'BASIC_DATA', path: '$v._料件' } }];
    const r2 = [{ driverRow: { _料件: 'Y' }, basicDataValues: {} }];
    expect(buildLegacyRowKeySets(f2, ['料件'], r2).legacyNoPrefix).toEqual(['Y']);   // 旧：末段降级命中
    expect(buildUniqueRowKeys(f2, ['料件'], r2)).toEqual(['0']);                      // 新：后端无此级 → 行号
  });
});

// ═══════════════════════════════════════════════════════════════════════════
// T8.1 —— getByKeyWithLegacyFallback 可变参数语义
// ═══════════════════════════════════════════════════════════════════════════
describe('repair-0805 T8.1 · getByKeyWithLegacyFallback 可变参数回退语义', () => {
  const mk = () => new Map<string, { v: number }>([
    ['now', { v: 0 }], ['legacyA', { v: 1 }], ['legacyB', { v: 2 }],
  ]);

  it('T8.1a 新键命中 → 直接返回，一个 legacy 都不查', () => {
    const m = new CountingMap<{ v: number }>([['now', { v: 0 }], ['legacyA', { v: 1 }]]);
    expect(getByKeyWithLegacyFallback(m, 'now', 'legacyA', 'legacyB')).toEqual({ v: 0 });
    expect(m.gets).toEqual(['now']);
  });

  it('T8.1b 新键未命中 → 第一档 legacy 命中', () => {
    expect(getByKeyWithLegacyFallback(mk(), 'miss', 'legacyA', 'legacyB')).toEqual({ v: 1 });
  });

  it('T8.1c 新键 + 第一档都未命中 → 第二档命中', () => {
    expect(getByKeyWithLegacyFallback(mk(), 'miss', 'alsoMiss', 'legacyB')).toEqual({ v: 2 });
  });

  it('T8.1d 两档都有值时按参数序取第一档（新→旧，顺序不许乱）', () => {
    expect(getByKeyWithLegacyFallback(mk(), 'miss', 'legacyA', 'legacyB')).toEqual({ v: 1 });
    expect(getByKeyWithLegacyFallback(mk(), 'miss', 'legacyB', 'legacyA')).toEqual({ v: 2 });
  });

  it('T8.1e legacyKey === key 时不重复查（既有优化，改可变参数后仍在）', () => {
    const m = new CountingMap<{ v: number }>([['legacyA', { v: 1 }]]);
    expect(getByKeyWithLegacyFallback(m, 'now', 'now', 'now')).toBeUndefined();
    expect(m.gets).toEqual(['now']);   // 3 个同名候选只查 1 次
  });

  it('T8.1f 两档 legacy 彼此相同时也只查一次（核价侧常态）', () => {
    const m = new CountingMap<{ v: number }>([['x', { v: 9 }]]);
    expect(getByKeyWithLegacyFallback(m, 'now', 'same', 'same')).toBeUndefined();
    expect(m.gets).toEqual(['now', 'same']);
  });

  it('T8.1g undefined 候选被跳过，不打断后续回退', () => {
    expect(getByKeyWithLegacyFallback(mk(), 'miss', undefined, 'legacyB')).toEqual({ v: 2 });
  });

  it('T8.1h 全未命中 → undefined；map 缺失 → undefined；零 legacy 参数 → 退化成 map.get', () => {
    expect(getByKeyWithLegacyFallback(mk(), 'miss', 'x', 'y')).toBeUndefined();
    expect(getByKeyWithLegacyFallback(undefined, 'now', 'legacyA')).toBeUndefined();
    expect(getByKeyWithLegacyFallback(mk(), 'now')).toEqual({ v: 0 });
    expect(getByKeyWithLegacyFallback(mk(), 'miss')).toBeUndefined();
  });
});

// ═══════════════════════════════════════════════════════════════════════════
// T8.2 / T8.3 —— 真实存量形态（formulaResults，线上活路径）
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 复刻 `QuotationStep2.tsx:3218-3234 + :3291` 与 `ReadonlyProductCard.tsx:690-695` 的取值三元组：
 * `(rowKey=__effKey, legacyPrefixed=__legacyEffKeyPrefixed, legacyNoPrefix=__legacyEffKey)` ——
 * 三个键都由 `buildSnapshotExpansions` 在**完整** baseRows 上算好盖到每行（AP-54 单一口径）。
 *
 * @param mutateRowKey 把 `formulaResults[i].rowKey` 改写成某代历史形态（模拟存量单据）
 */
function buildRead(mutateRowKey?: (i: number) => string) {
  const cardValues = deepClone(QT0068_QUOTE_CARD_VALUES) as any;
  const tab = cardValues.tabs.find((t: any) => t.tabName === QT0068.wuliaoTabName);
  if (mutateRowKey) {
    tab.formulaResults.forEach((r: any, i: number) => { r.rowKey = mutateRowKey(i); });
  }
  const componentData = buildQt0068ComponentData();
  const lookup = buildQt0068ExpansionLookup(componentData, cardValues);
  const comp = componentData.find(c => c.componentId === QT0068.wuliaoComponentId)!;
  const exp = lookup(comp);
  // 渲染层 snapByComp.formula 同构（QuotationStep2.tsx:2665-2666）
  const formulaMap = new Map<string, Record<string, any>>();
  (tab.formulaResults ?? []).forEach((r: any) => formulaMap.set(r.rowKey, r.values ?? {}));
  return { exp, formulaMap, tab };
}

/** 逐行按渲染层口径取值（活路径复刻）。`withFallback=false` 时只查新键 = F6 之前的行为。 */
function readAllRows(
  exp: any,
  formulaMap: Map<string, Record<string, any>>,
  withFallback = true,
): Array<Record<string, any> | undefined> {
  return (exp.rows as any[]).map((r) => withFallback
    ? getByKeyWithLegacyFallback(formulaMap, r.__effKey, r.__legacyEffKeyPrefixed, r.__legacyEffKey)
    : getByKeyWithLegacyFallback(formulaMap, r.__effKey));
}

describe('repair-0805 T8.2 · 存量 formulaResults 用「前缀+行号」旧键 → 回退仍读得出（线上活路径）', () => {
  it('T8.2a 前置自检：buildSnapshotExpansions 盖出的三个键 = 三代形态', () => {
    const { exp } = buildRead();
    expect(exp.rowCount).toBe(6);
    expect((exp.rows as any[]).map(r => r.__effKey)).toEqual(BACKEND_ROW_KEYS);
    expect((exp.rows as any[]).map(r => r.__legacyEffKeyPrefixed)).toEqual(EXPECTED_LEGACY_PREFIXED);
    expect((exp.rows as any[]).map(r => r.__legacyEffKey)).toEqual(EXPECTED_LEGACY_NO_PREFIX);
  });

  it('T8.2b 旧键存量单据：6 行 × 11 公式列全部读得出，且逐值等于后端', () => {
    const { exp, formulaMap } = buildRead((i) => EXPECTED_LEGACY_PREFIXED[i]);
    const rows = readAllRows(exp, formulaMap);
    expect(rows).toHaveLength(6);
    rows.forEach((vals, i) => {
      expect(vals, `第 ${i} 行经 legacyPrefixed 回退应命中`).toBeDefined();
      for (const col of WULIAO_FORMULA_COLUMNS) {
        expect(vals![col], `第 ${i} 行「${col}」不该缺失`).not.toBeUndefined();
        // 与未改键的原快照逐值相等（改的只是键，值原封不动）
        expect(vals![col]).toStrictEqual(backendTab.formulaResults[i].values[col]);
      }
    });
  });

  it('T8.2c 反向门禁：去掉 legacy 参数（= F6 之前的行为）→ 6 行里 5 行读不到', () => {
    const { exp, formulaMap } = buildRead((i) => EXPECTED_LEGACY_PREFIXED[i]);
    const rows = readAllRows(exp, formulaMap, /* withFallback */ false);
    // 第 0 行三代键恰好重合（料件为空）→ 仍能命中；其余 5 行必须全部落空。
    expect(rows[0]).toBeDefined();
    expect(rows.slice(1).every(v => v === undefined), '无回退时第 1~5 行应全部读不到').toBe(true);
  });
});

describe('repair-0805 T8.3 · 更老的一代（无前缀纯行号键）→ 走第二档回退', () => {
  it('T8.3a 6 行 × 11 列全部读得出，逐值等于后端', () => {
    const { exp, formulaMap } = buildRead((i) => EXPECTED_LEGACY_NO_PREFIX[i]);
    const rows = readAllRows(exp, formulaMap);
    rows.forEach((vals, i) => {
      expect(vals, `第 ${i} 行经 legacyNoPrefix 回退应命中`).toBeDefined();
      for (const col of WULIAO_FORMULA_COLUMNS) {
        expect(vals![col]).toStrictEqual(backendTab.formulaResults[i].values[col]);
      }
    });
  });

  it('T8.3b 反向门禁：无回退时 6 行全部落空（无前缀键与当代键无一重合）', () => {
    const { exp, formulaMap } = buildRead((i) => EXPECTED_LEGACY_NO_PREFIX[i]);
    expect(readAllRows(exp, formulaMap, false).every(v => v === undefined)).toBe(true);
  });

  it('T8.3c 当代键（未改写）依然直接命中，回退不改变现行为', () => {
    const { exp, formulaMap } = buildRead();
    const rows = readAllRows(exp, formulaMap);
    rows.forEach((vals, i) => {
      expect(vals).toBeDefined();
      for (const col of WULIAO_FORMULA_COLUMNS) {
        expect(vals![col]).toStrictEqual(backendTab.formulaResults[i].values[col]);
      }
    });
  });

  it('T8.3d 改造窗口期：同一页签新旧键混存 → 两种都读得出，且不互相串行', () => {
    // 偶数行留当代键、奇数行改成旧键（真实迁移窗口里最可能出现的形态）
    const { exp, formulaMap } = buildRead((i) => (i % 2 === 0 ? BACKEND_ROW_KEYS[i] : EXPECTED_LEGACY_PREFIXED[i]));
    const rows = readAllRows(exp, formulaMap);
    rows.forEach((vals, i) => {
      expect(vals, `第 ${i} 行（${i % 2 === 0 ? '当代键' : '旧键'}）应命中`).toBeDefined();
      // 串行门禁：读到的必须是**本行**的整份值，不能是别行的
      expect(vals).toStrictEqual(backendTab.formulaResults[i].values);
    });
    // 6 行的整份 values 两两不同，确保上面那条串行门禁不是空转
    // （逐列比不行：「材料成本」第 0/1 行都是 0.0，单列不足以区分行）
    const sigs = backendTab.formulaResults.map((r: any) => JSON.stringify(r.values));
    expect(new Set(sigs).size, '夹具 6 行 values 应互不相同，否则串行门禁无效').toBe(6);
  });
});

// ═══════════════════════════════════════════════════════════════════════════
// T8.4 —— editRows 形态（⚠️ 当前无活调用方，见文件头表格）
// ═══════════════════════════════════════════════════════════════════════════
describe('repair-0805 T8.4 · editRows 形态的同一回退【⚠️ 当前调用方为死代码，非现网证据】', () => {
  /**
   * `QuotationStep2.tsx:2663` 建的 `edit` Map 全文件无人读；
   * `useCardSnapshots#getCell` 的 editRows 回退所在 hook 全工程零调用点。
   * 下面测的是同一个纯函数在 editRows 语义下的行为，供该通路被重新接活时兜底。
   */
  const editMapOf = (rows: Array<{ rowKey: string; values: Record<string, any> }>) => {
    const m = new Map<string, Record<string, any>>();
    rows.forEach(r => m.set(r.rowKey, r.values));
    return m;
  };

  it('T8.4a 旧行号键写入的用户编辑值，用当代内容键 + 两档回退能读回', () => {
    const edit = editMapOf([
      { rowKey: EXPECTED_LEGACY_PREFIXED[1], values: { 组成数量: 7 } },
      { rowKey: EXPECTED_LEGACY_NO_PREFIX[2], values: { 组成数量: 9 } },
    ]);
    expect(getByKeyWithLegacyFallback(edit, BACKEND_ROW_KEYS[1], EXPECTED_LEGACY_PREFIXED[1], EXPECTED_LEGACY_NO_PREFIX[1]))
      .toEqual({ 组成数量: 7 });
    expect(getByKeyWithLegacyFallback(edit, BACKEND_ROW_KEYS[2], EXPECTED_LEGACY_PREFIXED[2], EXPECTED_LEGACY_NO_PREFIX[2]))
      .toEqual({ 组成数量: 9 });
  });

  it('T8.4b 当代键写入的直接命中，不走回退', () => {
    const edit = new CountingMap<Record<string, any>>([[BACKEND_ROW_KEYS[3], { 组成数量: 3 }]]);
    expect(getByKeyWithLegacyFallback(edit, BACKEND_ROW_KEYS[3], EXPECTED_LEGACY_PREFIXED[3], EXPECTED_LEGACY_NO_PREFIX[3]))
      .toEqual({ 组成数量: 3 });
    expect(edit.gets).toEqual([BACKEND_ROW_KEYS[3]]);
  });

  it('T8.4c 新旧键混存不互相覆盖：当代键优先，各行取到自己的值', () => {
    const edit = editMapOf([
      { rowKey: BACKEND_ROW_KEYS[4], values: { 组成数量: 44 } },        // 第 4 行：当代键
      { rowKey: EXPECTED_LEGACY_PREFIXED[4], values: { 组成数量: 999 } }, // 同一行的旧键（脏残留）
      { rowKey: EXPECTED_LEGACY_PREFIXED[5], values: { 组成数量: 55 } },  // 第 5 行：只有旧键
    ]);
    expect(getByKeyWithLegacyFallback(edit, BACKEND_ROW_KEYS[4], EXPECTED_LEGACY_PREFIXED[4], EXPECTED_LEGACY_NO_PREFIX[4]))
      .toEqual({ 组成数量: 44 });   // 当代键赢，旧键脏残留不得反向覆盖
    expect(getByKeyWithLegacyFallback(edit, BACKEND_ROW_KEYS[5], EXPECTED_LEGACY_PREFIXED[5], EXPECTED_LEGACY_NO_PREFIX[5]))
      .toEqual({ 组成数量: 55 });
  });
});
