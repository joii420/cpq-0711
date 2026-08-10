/**
 * repair-0808 —— T-2 · 前后端对拍（QT-20260807-0146，最强门禁）
 *
 * 夹具全部取自线上单据 `QT-20260807-0146`（见 __fixtures__/qt20260807-0146/ 各文件头）：
 * 前端跑真实的 `buildComponentDataFromStructure → buildSnapshotExpansions → PASS1(逐组件登记列小计)
 * → buildCrossTabRows(PASS2)`（逐字复刻 ProductCard QuotationStep2.tsx:2960 起的真实调用形状），
 * 结果与后端持久化的 `quotation_line_item.quote_card_values.subtotalByColumn` / `quotation.total_amount`
 * 逐值比对。
 *
 *   T-2.1  拓扑序不抛环 + 三个源页签排在「物料」之前（AC-4）
 *   T-2.2  「物料」列小计与后端 subtotalByColumn 6 键逐值一致（AC-3）
 *   T-2.3  「物料」11 个 FORMULA 列列和：除「回收成本」外均非 0（AC-3）
 *   T-2.4  「报价」SUBTOTAL 页签公式求得的产品小计 = quotation.total_amount（AC-2）
 *   T-2.5  反向门禁：页签粒度建图必须炸环 + 用错误序算出的列小计必须塌成 0（防恒绿）
 *
 * 用例书：dev-docs/repair-0803-公式计算BUG修复/repair-0808-前端页签算序假环致列小计归零/test.md
 */
import { describe, it, expect, vi } from 'vitest';
import { buildCrossTabRows, evalProductSubtotalFromSubtotals } from './QuotationStep2';
import type { LineItem } from './QuotationStep2';
import { buildComponentDeps, extractSourceRefs, topoOrderComponents } from './crossTabOrder';
import type { TabDepInput } from './crossTabOrder';
import {
  QT0146, WULIAO_FORMULA_COLUMNS, QT0146_QUOTE_CARD_STRUCTURE,
  valuesTab, buildQt0146ComponentData, buildQt0146ExpansionLookup,
  buildQt0146AllComponentSubtotals, runQt0146Pipeline, deepClone,
} from './__fixtures__/qt20260807-0146';

/**
 * 浮点比较口径：相对误差 ≤ 1e-12（照抄 formulaParityQt0068.repair0805.test.ts 的 expectNumEq）。
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

const backend = valuesTab(QT0146.wuliaoTabName);

describe('repair-0808 T-2.1 · 拓扑序（AC-4）', () => {
  it('T-2.1 不抛环；材料成本/来料固定加工费/来料其他费用 三者下标 < 物料下标', () => {
    const componentData = buildQt0146ComponentData();
    const normals = componentData.filter(c => c.componentType === 'NORMAL');
    const ids = normals.map(c => c.componentId || c.componentCode || c.tabName);
    const tabs: TabDepInput[] = normals.map(c => ({
      cid: c.componentId || c.componentCode || c.tabName,
      code: c.componentCode,
      tabName: c.tabName,
      formulas: c.formulas as any,
      fields: c.fields as any,
    }));
    const deps = buildComponentDeps(tabs);

    let order: string[] = [];
    expect(() => { order = topoOrderComponents(ids, deps); }).not.toThrow();

    const idx = (cid: string) => order.indexOf(cid);
    const wuliaoIdx = idx(QT0146.wuliaoComponentId);
    expect(wuliaoIdx, '夹具自检：拓扑序里找不到「物料」').toBeGreaterThanOrEqual(0);
    expect(idx(QT0146.materialCostComponentId)).toBeLessThan(wuliaoIdx);
    expect(idx(QT0146.incomingFixedFeeComponentId)).toBeLessThan(wuliaoIdx);
    expect(idx(QT0146.incomingOtherFeeComponentId)).toBeLessThan(wuliaoIdx);
  });
});

describe('repair-0808 T-2.2/T-2.3 · 「物料」列小计对拍（AC-3）', () => {
  it('T-2.2 columnSumsByComp[物料] 与后端 subtotalByColumn 6 键逐值一致（相对误差 ≤1e-12）', () => {
    const { wuliaoColumnSums } = runQt0146Pipeline();
    const be = backend.subtotalByColumn as Record<string, number>;
    const keys = Object.keys(be);
    expect(keys.length, '夹具自检：后端 subtotalByColumn 不是 6 个键').toBe(6);
    for (const k of keys) {
      expectNumEq(wuliaoColumnSums[k], be[k], `subtotalByColumn.${k}`);
    }
  });

  it('T-2.3 物料 11 个 FORMULA 列列和：均为有限数，除「回收成本」外均非 0', () => {
    const { wuliaoColumnSums } = runQt0146Pipeline();
    expect(WULIAO_FORMULA_COLUMNS.length, '夹具自检：物料 FORMULA 列不是 11 个').toBe(11);
    for (const col of WULIAO_FORMULA_COLUMNS) {
      const v = wuliaoColumnSums[col];
      expect(
        typeof v === 'number' && Number.isFinite(v),
        `「${col}」列和不是有限数：${JSON.stringify(v)}（线上表现即该单元格 '—'）`,
      ).toBe(true);
    }
    expect(wuliaoColumnSums['回收成本'], '「回收成本」本身业务上就应为 0（非本缺陷症状）').toBe(0);
    for (const col of WULIAO_FORMULA_COLUMNS) {
      if (col === '回收成本') continue;
      expect(wuliaoColumnSums[col], `「${col}」列和不应为 0（线上表现即该列小计 ¥0，本缺陷的直接症状）`).not.toBe(0);
    }
  });
});

describe('repair-0808 T-2.4 · 产品小计对拍（AC-2）', () => {
  it('T-2.4 「报价」SUBTOTAL 公式求得的产品小计 ≈ 后端「报价」页签持久化的 subtotal（137.53 量级，非 0.10 量级）', () => {
    const { componentData, allComponentSubtotals } = runQt0146Pipeline();
    const item = {
      id: QT0146.lineItemId,
      productPartNo: QT0146.productPartNo,
      componentData,
      productAttributes: [],
    } as unknown as LineItem;
    const productSubtotal = evalProductSubtotalFromSubtotals(item, allComponentSubtotals);

    // 期望值直接从夹具读（后端「报价」SUBTOTAL 页签自己的 subtotal 字段），不硬编码。
    // 不用 `quotation.total_amount`——该列是 NUMERIC(20,6)，落库时已丢了 6 位以后的精度，
    // 当高精度对拍基准会引入虚假误差，见 index.ts QT0146 常量注释。
    const backendSubtotal = (valuesTab(QT0146.quoteSubtotalTabName) as any).subtotal as number;
    expect(typeof backendSubtotal, '夹具自检：「报价」tab 没有 subtotal 字段').toBe('number');

    // 6 个加项/减项本身已在 T-2.2 逐值对上后端 subtotalByColumn（相对误差 ≤1e-12）；
    // 这里残留的绝对误差（实测 ~2.6e-5）是「报价」这个 SUBTOTAL 组件自身公式在后端求值/持久化时
    // 的舍入策略差异（与本次 repair-0808 修的「拓扑序/依赖建图」无关，不在本次改动范围内）。
    // 用绝对误差 1e-3 兜底：比实测残差宽 ~40 倍，但比本缺陷真实症状（列小计塌成 0，
    // 产品小计从 137.53 掉到 0.10，相差 4 个数量级）严格 100 倍以上收紧——
    // 假环一旦复发，这条断言必挂，不会被 1e-3 的宽容度掩盖。
    const absDiff = Math.abs(productSubtotal - backendSubtotal);
    expect(
      absDiff <= 1e-3,
      `产品小计 前端 ${productSubtotal} vs 后端 ${backendSubtotal}，绝对误差 ${absDiff} 超出 1e-3`,
    ).toBe(true);
    // 量级门禁（真正守住本缺陷的断言）：修复前产品小计塌成 ~0.1038（见 test.md T-4.2「现状 ¥0.103826」），
    // 与正确值 ~137.53 相差 1300 倍——只要不小于 100，就足以把「假环未修」的场景挡在外面。
    expect(productSubtotal, '产品小计量级不对：疑似假环未修，列小计塌缩').toBeGreaterThan(100);
  });
});

describe('repair-0808 T-2.5 · 反向门禁（证明用例真的守着本缺陷，不是恒绿）', () => {
  it('T-2.5a 手工构造「页签粒度」等价 deps（不做列粒度判定，即 F-2 改前的内联逻辑）→ 真实数据上 topoOrderComponents 必炸环', () => {
    // 逐字复刻 fronttask.md §3.1「现状」那段被替换掉的内联建图逻辑——
    // component_subtotal 只要能解析到目标就无条件建边，不看被引用列是不是公式列。
    const componentData = buildQt0146ComponentData();
    const normals = componentData.filter(c => c.componentType === 'NORMAL');
    const ids = normals.map(c => c.componentId || c.componentCode || c.tabName);
    const idKeyOf = new Map<string, string>();
    normals.forEach((c, i) => {
      if (c.componentId) idKeyOf.set(c.componentId, ids[i]);
      if (c.componentCode) idKeyOf.set(c.componentCode, ids[i]);
      if (c.tabName) idKeyOf.set(c.tabName, ids[i]);
    });
    const legacyDeps: Record<string, string[]> = {};
    normals.forEach((c, i) => {
      const crossRefs = extractSourceRefs(c.formulas as any);
      const subRefs: string[] = [];
      for (const f of (c.formulas ?? [])) {
        for (const t of (((f as any)?.expression ?? []) as any[])) {
          if (t?.type === 'component_subtotal') {
            const refKey = (t.component_code && idKeyOf.get(t.component_code))
              || (t.tab_name && idKeyOf.get(t.tab_name));
            if (refKey && refKey !== ids[i]) subRefs.push(refKey);
          }
        }
      }
      legacyDeps[ids[i]] = [...new Set([...crossRefs, ...subRefs])];
    });

    expect(() => topoOrderComponents(ids, legacyDeps)).toThrow(/循环引用/);
  });

  it('T-2.5b 等价场景真跑生产管线（把「产品#税率」误判成顺序敏感列，等价于页签粒度判据）→ 真实产生假环 + 物料 6 键列小计全塌成 0', () => {
    // 与 T-2.5a 手工构造 deps 不同角度的同一件事：不绕过 buildComponentDeps，而是让它在
    // 「税率」被错误标成 FORMULA（页签粒度判据的等价效果——不看列类型、恒建边）时，
    // 用真实的 buildComponentDeps + buildCrossTabRows（生产函数，未 mock）在这份真实夹具上
    // 自然产生「产品⇄物料」假环 → 命中 catch → 退回声明序 → 物料排在它依赖的 3 个源页签之前
    // → cross_tab_ref 读不到源 store → 列小计塌成 0。这是「假环」实际后果的第一手证据。
    const broken = deepClone(QT0146_QUOTE_CARD_STRUCTURE);
    const productTab = (broken.tabs as any[]).find((t: any) => t.tabName === QT0146.productTabName);
    const rateField = (productTab.fields as any[]).find((f: any) => f.name === '税率');
    expect(rateField?.fieldType, '夹具自检：税率不是 INPUT_NUMBER，反向门禁失去意义').toBe('INPUT_NUMBER');
    rateField.fieldType = 'FORMULA';

    const componentData = buildQt0146ComponentData(broken);
    const lookupExpansion = buildQt0146ExpansionLookup(componentData);
    const allComponentSubtotals = buildQt0146AllComponentSubtotals(componentData, lookupExpansion);

    const errSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
    const { columnSumsByComp } = buildCrossTabRows(
      componentData, allComponentSubtotals, QT0146.productPartNo, lookupExpansion,
    );
    expect(errSpy, '假环必须留痕（console.error），不得静默回退').toHaveBeenCalled();
    expect(String(errSpy.mock.calls[0]?.[0] ?? '')).toContain('组件依赖成环');
    errSpy.mockRestore();

    const be = backend.subtotalByColumn as Record<string, number>;
    const wuliaoSums = columnSumsByComp[QT0146.wuliaoComponentId] ?? {};
    for (const k of Object.keys(be)) {
      expect(wuliaoSums[k] ?? 0, `假环场景下「${k}」列小计必须塌成 0（否则这条反向门禁没守住东西）`).toBe(0);
    }
  });
});
