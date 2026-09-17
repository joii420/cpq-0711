/**
 * repair-260916 F-7 / F-8 — Excel 连表公式列前后端对拍（AC-14）。
 *
 * 读取共享夹具 __fixtures__/tabjoin-excel-cases.json（与后端
 * cpq-backend/src/test/resources/tabjoin-excel-cases.json 逐字节相同，由后端 B-5 定稿、此处原样拷贝）。
 *
 * 两层断言：
 *   A. evaluateTabJoinColumn + 夹具直供的 provider（与后端 evaluateColumn 的 CardDataProvider 同形）
 *      → 每条 case 与 expected 数值相等（无容差）。
 *   B. buildExcelSnapshot 端到端：用夹具行构造报价行（字段按 fields 声明、行值原样），走真实的
 *      getComponentSubtotals → buildCrossTabRows → TAB_JOIN_FORMULA 列求值，结果同样等于 expected。
 *      页签合计（tabTotal）无法由夹具行推出，故给每个页签补一个「金额列」，其各行之和 = tabTotal
 *      （只影响 [页签(总计)]，不影响其它 case 引用的列）。
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import Decimal from 'decimal.js';
import fixture from './__fixtures__/tabjoin-excel-cases.json';
import { evaluateTabJoinColumn, MSG_SUBTOTAL_WITHOUT_COLUMN, type TabJoinDataProvider } from './tabJoinExcelEval';
import { buildExcelSnapshot } from './buildExcelSnapshot';
import type { CostingTemplateColumn } from '../../services/costingTemplateService';

type FxTab = {
  alias: string; tabKey: string; rowKeyFields: string[];
  fields: Array<{ name: string; is_subtotal: boolean }>;
  rows: Array<Record<string, string>>;
  subtotalByColumn: Record<string, string>;
  tabTotal: string;
};
// JSON 推断出的联合类型与 FxTab 不重叠（各页签键集不同），经 unknown 转换
const tabs = fixture.tabs as unknown as FxTab[];
const cases = fixture.cases as unknown as Array<{ id: string; expression: string; expected: string }>;

const tabRefs = tabs.map((t) => ({ alias: t.alias, tabKey: t.tabKey, rowKeyFields: t.rowKeyFields }));
const byKey = new Map(tabs.map((t) => [t.tabKey, t]));
const provider: TabJoinDataProvider = {
  rowsOf: (tab) => byKey.get(tab.tabKey)?.rows ?? [],
  subtotalOfColumn: (tab, col) => byKey.get(tab.tabKey)?.subtotalByColumn?.[col],
  subtotalOf: (tab) => byKey.get(tab.tabKey)?.tabTotal,
};

const sameNumber = (actual: string, expected: string) => new Decimal(actual).equals(new Decimal(expected));

describe('夹具完整性', () => {
  it('前后端两份文件逐字节相同', () => {
    const fe = readFileSync(fileURLToPath(new URL('./__fixtures__/tabjoin-excel-cases.json', import.meta.url)));
    const be = readFileSync(fileURLToPath(new URL(
      '../../../../cpq-backend/src/test/resources/tabjoin-excel-cases.json', import.meta.url)));
    expect(fe.equals(be)).toBe(true);
  });

  it('非空：含 AC-14 a~e 五条及物料 6 行', () => {
    expect(cases.map((c) => c.id)).toEqual(expect.arrayContaining(['AC-14a', 'AC-14b', 'AC-14c', 'AC-14d', 'AC-14e']));
    expect(tabs.find((t) => t.alias === '物料')?.rows).toHaveLength(6);
  });
});

describe('A. evaluateTabJoinColumn × 夹具 provider（后端 evaluateColumn 同口径）', () => {
  for (const c of cases) {
    it(`${c.id}  ${c.expression} = ${c.expected}`, () => {
      const actual = evaluateTabJoinColumn(c.expression, tabRefs, provider);
      console.log(`[A] ${c.id} actual=${actual} expected=${c.expected}`);
      expect(sameNumber(actual, c.expected)).toBe(true);
    });
  }

  it('[物料(小计)] 没写列名 → 抛 5.1 文案（后端同样抛错）', () => {
    expect(() => evaluateTabJoinColumn('[物料(小计)]', tabRefs, provider))
      .toThrow(new Error(MSG_SUBTOTAL_WITHOUT_COLUMN));
  });
});

// ─── B. buildExcelSnapshot 端到端 ─────────────────────────────────────────────

const AMOUNT_COL = '__fixture_amount__';

function lineItemFromFixture(): any {
  const componentData = tabs.map((t) => {
    const fields = [
      ...t.fields.map((f) => ({
        name: f.name,
        field_type: f.is_subtotal ? 'INPUT_NUMBER' : (t.rowKeyFields.includes(f.name) ? 'INPUT_TEXT' : 'INPUT_NUMBER'),
        is_subtotal: f.is_subtotal,
      })),
      { name: AMOUNT_COL, field_type: 'INPUT_NUMBER', is_subtotal: true, is_amount: true },
    ];
    const rows = t.rows.map((r, i) => ({ ...r, [AMOUNT_COL]: i === 0 ? t.tabTotal : '0' }));
    return {
      componentId: t.tabKey,
      componentCode: `CODE-${t.alias}`,
      tabName: t.alias,
      componentType: 'NORMAL',
      rowKeyFields: t.rowKeyFields,
      fields,
      formulas: [],
      rows,
      subtotal: '0',
    };
  });
  return {
    id: 'li-fixture', productId: 'p', productName: 'p', productPartNo: 'S3120011203', templateId: 't',
    productAttributeValues: {}, productAttributes: [], componentData, subtotal: '0',
  };
}

describe('B. buildExcelSnapshot 端到端（真实 PASS1/PASS2 管线 → TAB_JOIN_FORMULA 列）', () => {
  const item = lineItemFromFixture();
  const columns = cases.map((c, i) => ({
    col_key: `c${i}`, title: c.id, source_type: 'TAB_JOIN_FORMULA',
    expression: c.expression, tabs: tabRefs,
  })) as unknown as CostingTemplateColumn[];
  const row = buildExcelSnapshot(item, columns, undefined, undefined, {}).rows[0];

  cases.forEach((c, i) => {
    it(`${c.id}  ${c.expression} = ${c.expected}`, () => {
      console.log(`[B] ${c.id} actual=${row[`c${i}`]} expected=${c.expected}`);
      expect(typeof row[`c${i}`]).toBe('string');
      expect(sameNumber(row[`c${i}`], c.expected)).toBe(true);
    });
  });

  it('非法写法 [物料(小计)] → 该列降级为 0，不影响其它列', () => {
    const bad = [{ col_key: 'bad', title: 'bad', source_type: 'TAB_JOIN_FORMULA', expression: '[物料(小计)]', tabs: tabRefs }] as unknown as CostingTemplateColumn[];
    expect(buildExcelSnapshot(item, bad, undefined, undefined, {}).rows[0].bad).toBe('0');
  });
});

// ─── 5.3 口径细节（与后端实测行为一致，见回报「证据」中的 JEXL 探针输出）──────────────

describe('口径细节', () => {
  const one = (expr: string) => evaluateTabJoinColumn(expr, tabRefs, provider);

  it('除数为 0 → 被除数（SafeArithmetic），不是 0', () => {
    expect(one('[物料.材料成本(小计)] / 0')).toBe('1.978941064');
  });
  it('除以不带 B 的小数字面量 → 抛错（后端 PrecisionPolicy.of 拒绝浮点）', () => {
    expect(() => one('[物料.材料成本(小计)] / 1.13')).toThrow(/floating point/);
  });
  it('AVG 按对齐行（全外连）计数：物料 6 行', () => {
    expect(one('COUNT([物料.X])')).toBe('6');
    expect(sameNumber(one('AVG([物料.X])'), new Decimal('155.932743363').dividedBy(6).toDecimalPlaces(12, Decimal.ROUND_HALF_UP).toFixed())).toBe(true);
  });
  it('未在 tabs 声明的页签：明细按 0、标量按 0', () => {
    expect(one('[未声明.X] + [未声明(总计)] + 1')).toBe('1');
  });
  it('顶层减号拆项：裸明细项逐行求和后相减', () => {
    expect(one('[物料(总计)] - [物料.X]')).toBe(new Decimal('767.382030838').minus('155.932743363').toFixed());
  });
  it('空表达式 → 0', () => {
    expect(one('   ')).toBe('0');
  });
});
