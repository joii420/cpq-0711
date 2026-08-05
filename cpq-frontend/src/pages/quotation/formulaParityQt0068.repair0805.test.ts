/**
 * repair-0805 / BL-0112 —— T3 · T4 · T5（真实单据前后端对拍，最强门禁）
 *
 * 夹具全部取自线上单据 `QT-20260804-0068`（见 __fixtures__/qt20260804-0068/ 各文件头）：
 * 前端跑真实的 `buildComponentDataFromStructure → buildSnapshotExpansions → buildCrossTabRows`
 * （逐字复刻 ProductCard QuotationStep2.tsx:2819 / ReadonlyProductCard.tsx:456 的调用形状），
 * 结果与后端持久化的 `quote_card_values.formulaResults` / `subtotalByColumn` 逐值比对。
 *
 *   T3  条件公式按 id 解析（AC-4）
 *   T4  6 行 × 11 列前后端对拍（AC-1）+ 反向门禁（抹掉 formulas[].id 必须失败）
 *   T5  列小计（AC-2）
 *
 * 用例书：dev-docs/repair-0803-BL0098-公式绑定改绑ID/repair-0805-… 目录下的 test.md
 */
import { describe, it, expect } from 'vitest';
import { buildCrossTabRows, computeTabFormulasTree } from './QuotationStep2';
import type { ComponentDataItem, TreeFormulaRowInput } from './QuotationStep2';
import {
  QT0068, WULIAO_FORMULA_COLUMNS, MATERIAL_COST_FORMULA_IDS,
  QT0068_QUOTE_CARD_STRUCTURE, QT0068_SAVED_COMPONENT_DATA,
  valuesTab, buildQt0068ComponentData, buildQt0068ExpansionLookup, deepClone,
} from './__fixtures__/qt20260804-0068';

/**
 * 浮点比较口径：相对误差 ≤ 1e-12。
 * 66 个格子里 65 个前后端**逐位相同**，只有 row2「材料成本」差在第 17 位有效数字
 * （前端 0.14716242167146049 / 后端 0.14716242167146046）——同一串乘除的结合序差异，
 * 与本缺陷无关。用相对 1e-12 既容得下这一个 ULP，又拦得住任何真实口径分歧。
 */
function expectNumEq(actual: unknown, expected: number, label: string) {
  expect(typeof actual, `${label} 不是数字（实际 ${JSON.stringify(actual)}）`).toBe('number');
  const a = actual as number;
  expect(Number.isFinite(a), `${label} 不是有限数：${a}`).toBe(true);
  const tol = 1e-12 * Math.max(1, Math.abs(expected));
  expect(
    Math.abs(a - expected) <= tol,
    `${label} 前端 ${a} ≠ 后端 ${expected}（相对误差 ${Math.abs(a - expected) / Math.max(1, Math.abs(expected))}）`,
  ).toBe(true);
}

/** 跑一遍完整渲染管线，返回「物料」页签的 resolvedRows + 全部页签的列小计。 */
function runPipeline(
  structure: any = QT0068_QUOTE_CARD_STRUCTURE,
  saved: any[] = QT0068_SAVED_COMPONENT_DATA,
) {
  const componentData = buildQt0068ComponentData(structure, saved);
  const lookupExpansion = buildQt0068ExpansionLookup(componentData);
  const allComponentSubtotals: Record<string, number> = {};
  const { store, columnSumsByComp } = buildCrossTabRows(
    componentData, allComponentSubtotals, QT0068.productPartNo, lookupExpansion,
  );
  return {
    componentData,
    lookupExpansion,
    rows: (store[QT0068.wuliaoComponentId] ?? []) as Array<Record<string, any>>,
    columnSums: (columnSumsByComp[QT0068.wuliaoComponentId] ?? {}) as Record<string, number>,
    allComponentSubtotals,
  };
}

/** T4.1 的断言体，抽出来供 T4.3 反向门禁复用（同一份断言换个夹具跑）。 */
function assertAllFormulaCellsHaveValues(structure: any) {
  const { rows } = runPipeline(structure);
  expect(rows).toHaveLength(6);
  for (let i = 0; i < rows.length; i++) {
    for (const col of WULIAO_FORMULA_COLUMNS) {
      const v = rows[i][col];
      expect(
        typeof v === 'number' && Number.isFinite(v),
        `第 ${i} 行「${col}」没算出数：${JSON.stringify(v)}（线上表现即单元格 '—'）`,
      ).toBe(true);
    }
  }
}

const backend = valuesTab(QT0068.wuliaoTabName);

describe('repair-0805 T4 · 前后端对拍（AC-1，最强门禁）', () => {
  it('T4.1 6 行 × 11 列全部有值（没有 undefined、没有 null、没有 NaN）', () => {
    assertAllFormulaCellsHaveValues(QT0068_QUOTE_CARD_STRUCTURE);
  });

  it('T4.2 与后端 formulaResults 逐值相等（6 行 × 11 列 = 66 格）', () => {
    const { rows } = runPipeline();
    expect(backend.formulaResults).toHaveLength(6);
    let compared = 0;
    for (let i = 0; i < 6; i++) {
      const be = backend.formulaResults[i].values as Record<string, number>;
      for (const col of WULIAO_FORMULA_COLUMNS) {
        expect(
          Object.prototype.hasOwnProperty.call(be, col),
          `夹具自检：后端 formulaResults[${i}] 没有「${col}」列，夹具被改坏了`,
        ).toBe(true);
        expectNumEq(rows[i][col], be[col], `row${i}.${col}`);
        compared++;
      }
    }
    expect(compared, '实际比对的格子数不是 66，夹具被裁剪坏了').toBe(66);
  });

  it('T4.3 反向门禁：把夹具里 formulas[].id 抹成 undefined → T4.1 必须失败', () => {
    // 这是「测试真的守着本次缺陷、而不是碰巧恒绿」的证明。
    // 抹掉的正是 repair-0805 里 enrich 漏搬的那个键——等价于把修复回滚。
    const broken = deepClone(QT0068_QUOTE_CARD_STRUCTURE);
    let wiped = 0;
    for (const tab of broken.tabs as any[]) {
      for (const fm of (tab.formulas ?? [])) {
        if (fm.id !== undefined) { delete fm.id; wiped++; }
      }
    }
    expect(wiped, '夹具里本来就没有 formulas[].id，反向门禁失去意义').toBe(16);  // 物料 16 条，另 3 个源页签 formulas=[]

    expect(() => assertAllFormulaCellsHaveValues(broken)).toThrow(/没算出数/);
  });

  it('T4.3b 反向门禁的失败形状与线上一致：单一模式列 key 缺失、条件模式列为 null', () => {
    const broken = deepClone(QT0068_QUOTE_CARD_STRUCTURE);
    for (const tab of broken.tabs as any[]) {
      for (const fm of (tab.formulas ?? [])) delete fm.id;
    }
    const { rows, columnSums } = runPipeline(broken);

    // 单一模式（10 个）：resolveFormula 返 undefined → 字段整个不进 formulaFields
    //   → buildResolvedRow 的 `key in formulaCache` 为假 → 保留 row 原值。
    //   夹具已剔除 row_data 里的公式旧值，所以这里就是 undefined（线上则是"旧值伪装"）。
    expect(rows[3]['材料价格']).toBeUndefined();
    expect(rows[3]['原材料成本']).toBeUndefined();
    // 条件模式（材料成本）：rules 被 .filter 清空 + default 为 undefined → expr undefined → null
    expect(rows[3]['材料成本']).toBeNull();
    // 列小计随之塌成 0（= 线上截图里的「材料成本 ¥ 0」）
    expect(columnSums['材料成本']).toBe(0);
  });
});

describe('repair-0805 T5 · 列小计（AC-2）', () => {
  it('T5.1 columnSumsByComp[物料].材料成本 = 623.5975043517194（而非 ¥ 0）', () => {
    const { columnSums } = runPipeline();
    expectNumEq(columnSums['材料成本'], 623.5975043517194, '列小计.材料成本');
    expectNumEq(columnSums['材料损耗成本'], 27.614070796432575, '列小计.材料损耗成本');
  });

  it('T5.2 与后端持久化 subtotalByColumn 的 6 个键逐值一致', () => {
    const { columnSums } = runPipeline();
    const be = backend.subtotalByColumn as Record<string, number>;
    const keys = Object.keys(be);
    expect(keys.length, '夹具自检：后端 subtotalByColumn 不是 6 个键').toBe(6);
    for (const k of keys) {
      expectNumEq(columnSums[k], be[k], `subtotalByColumn.${k}`);
    }
  });
});

// ─────────────────────────────────────────────────────────────────────────────
// T3 · 条件公式按 id 解析（AC-4）
//
// 真实配置（组件 COMP-0185 字段「材料成本」）：
//   conditional_formula.rules[0] = { when: 产出类型 = '非银点类', formula_id: cb3ea05c-… }
//   conditional_formula.default_formula_id = c9213b82-…（银点材料成本公式）
// 两条都按 id 解析；丢了公式侧 id → rules 被 .filter 清空、default 为 undefined → 求值返 null。
// ─────────────────────────────────────────────────────────────────────────────

describe('repair-0805 T3 · 条件公式按 id 解析（AC-4）', () => {
  it('T3.0 夹具自检：字段绑的两个 id 就是排查报告里那两条，且都能在 comp.formulas 里查到', () => {
    const { componentData } = runPipeline();
    const wuliao = componentData.find(c => c.componentId === QT0068.wuliaoComponentId)!;
    const cf = (wuliao.fields.find(f => f.name === '材料成本') as any).conditional_formula;
    expect(cf.rules).toHaveLength(1);
    expect(cf.rules[0].formula_id).toBe(MATERIAL_COST_FORMULA_IDS.rule0);
    expect(cf.default_formula_id).toBe(MATERIAL_COST_FORMULA_IDS.default);

    const byId = new Map(wuliao.formulas.map(f => [f.id, f.name]));
    expect(byId.get(MATERIAL_COST_FORMULA_IDS.rule0)).toBe(MATERIAL_COST_FORMULA_IDS.rule0Name);
    expect(byId.get(MATERIAL_COST_FORMULA_IDS.default)).toBe(MATERIAL_COST_FORMULA_IDS.defaultName);
  });

  it('T3.1 rules 没被 .filter 清空、default 也不是 undefined —— 两条分支都算得出非 null 值', () => {
    const { rows } = runPipeline();
    // row2 = H85（产出类型='非银点类' → 走 rules[0]）；row3 = Ag粉（'银点类' → 走 default）
    expect(rows[2]['材料成本']).not.toBeNull();
    expect(rows[3]['材料成本']).not.toBeNull();
    // 两行值不同 → 证明确实分流到了两条不同的公式，不是都落在同一条上
    expect(rows[2]['材料成本']).not.toBe(rows[3]['材料成本']);
  });

  it('T3.2 产出类型 = 非银点类 → 命中 rules[0]「非银点类材料成本公式」', () => {
    const { rows } = runPipeline();
    const drv = (QT0068_SAVED_COMPONENT_DATA as any[])
      .find(c => c.tabName === QT0068.wuliaoTabName).rows[2];
    expect(drv['产出类型'], '夹具自检：第 2 行不是非银点类').toBe('非银点类');
    expectNumEq(rows[2]['材料成本'], 0.14716242167146046, 'row2.材料成本(非银点类分支)');

    // 反事实：把该行产出类型翻成'银点类'，值必须变（证明是 when 判据在选分支，而不是巧合相等）
    const saved = deepClone(QT0068_SAVED_COMPONENT_DATA);
    (saved as any[]).find(c => c.tabName === QT0068.wuliaoTabName).rows[2]['产出类型'] = '银点类';
    const flipped = runPipeline(QT0068_QUOTE_CARD_STRUCTURE, saved as any[]);
    expect(flipped.rows[2]['材料成本']).not.toBe(rows[2]['材料成本']);
  });

  it('T3.3 产出类型 = 其它（银点类）→ 落 default「银点材料成本公式」', () => {
    const { rows } = runPipeline();
    const savedRows = (QT0068_SAVED_COMPONENT_DATA as any[])
      .find(c => c.tabName === QT0068.wuliaoTabName).rows;
    expect(savedRows[3]['产出类型']).toBe('银点类');
    expectNumEq(rows[3]['材料成本'], 608.77581120944, 'row3.材料成本(default 分支)');
    expectNumEq(rows[4]['材料成本'], 7.44745950274, 'row4.材料成本(default 分支)');
    expectNumEq(rows[5]['材料成本'], 7.227071217868, 'row5.材料成本(default 分支)');
  });

  it('T3.4 collectFormulaDefs（BOM 树求值入口 :798）跑同一份配置，结论与 computeAllFormulas 一致', () => {
    // task-0803 把同样的按 id 查找复制进了 collectFormulaFieldDefsForTree —— 它是第三处 id 解析点，
    // 树页签走的是这条路。两条入口的条件分支解析结论必须一致，否则「同一格在树页签和普通页签算出两个数」。
    const { componentData, lookupExpansion, rows } = runPipeline();
    const wuliao = componentData.find(c => c.componentId === QT0068.wuliaoComponentId)!;
    const exp = lookupExpansion(wuliao)!;

    // 先跑一遍拿 crossTabRows store（树入口的 cross_tab_ref 取数依赖它）
    const subs: Record<string, number> = {};
    const { store } = buildCrossTabRows(
      componentData, subs, QT0068.productPartNo, lookupExpansion,
    );

    const treeRows: TreeFormulaRowInput[] = exp.rows.map((r: any, i: number) => ({
      row: (wuliao.rows ?? [])[i] ?? {},
      basicDataValues: r.basicDataValues,
      nodeId: r.__sys?.nodeId,
      parentId: r.__sys?.parentId,
      lvl: r.__sys?.lvl,
    }));
    expect(treeRows).toHaveLength(6);

    const treeResults = computeTabFormulasTree(
      wuliao as ComponentDataItem, treeRows, subs, undefined, undefined,
      QT0068.productPartNo, undefined, store,
    );

    for (let i = 0; i < 6; i++) {
      expectNumEq(treeResults[i]['材料成本'], rows[i]['材料成本'] as number, `树入口 row${i}.材料成本`);
    }
    // 树入口下条件分支同样必须分流（非银点类 ≠ 银点类）
    expect(treeResults[2]['材料成本']).not.toBe(treeResults[3]['材料成本']);
  });
});
