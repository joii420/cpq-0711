/**
 * task-261030 · 核价卡片小计展示调整 · S-UNIT（T-01 / T-02 / T-03 文本部分）
 *
 * 认领 AC（原文见 `dev-docs/task-260801-公式计算精度优化/task-261030-核价卡片小计展示调整/需求文档.md` ③）：
 *   - AC-2 角色显示位数：4 位 `1717.1355 CNY` / `(VAT) 1940.3632 CNY`、`241.0556 USD` / `(VAT) 272.3928 USD`；
 *          9 位同 AC-1；派生值只在显示边界舍入，不经 JavaScript `number`。
 *   - AC-5 税率来源与异常：ratio=0 / 仅 vatRate=13 / ratio 与 vatRate 同在 / 缺失、非数字、<0、>1 → `(VAT) -`，不抛异常。
 *   - AC-8 长值：4 段文本（几何部分在 `e2e/task261030-costing-subtotal-geometry.spec.ts`）。
 *
 * 期望值用 Decimal 独立实算（python decimal, prec=80, 先 HALF_UP 到 12 位工作值再按上限显示并去尾零）：
 *   1717.135540717 × 1.13 = 1940.36316101021；÷ 7.1234 = 241.055611185249 / 272.392840639331。
 * 🚫 只依赖 `api.md §3.1` 组件契约与 §3 DOM 定位点，不读实现。
 */
import { describe, expect, it } from 'vitest';
import {
  FIXTURE_C,
  FIXTURE_LONG,
  columnLines,
  columnShapeProblems,
  logView,
  readCostingSummary,
  renderCostingSummary,
  type CostingSummaryProps,
} from './__tests__/task261030-costing-subtotal.ssr';

const AC1_LINES = [
  ['1717.135540717 CNY', '(VAT) 1940.36316101 CNY'],
  ['241.055611185 USD', '(VAT) 272.392840639 USD'],
];

function renderTwoColumns(label: string, props: Partial<CostingSummaryProps>) {
  const view = readCostingSummary(renderCostingSummary(props));
  logView(label, view);
  // 防空集合假绿：先断言根节点唯一、列数恰为 2、每列结构完整，再比文本。
  expect(view.rootCount, `${label}: .qt-subtotal-costing 必须恰好 1 个`).toBe(1);
  expect(view.columns.length, `${label}: .qt-subtotal-costing-col 必须恰好 2 个`).toBe(2);
  expect(columnShapeProblems(view), `${label}: 每列必须各有 1 个第一行 / 第二行 / (VAT) 前缀 / 金额节点`).toEqual([]);
  expect(view.columns.map((col) => col.currency), `${label}: DOM 中 CNY 列在前、报价币种列在后`)
    .toEqual(['CNY', String(props.currency ?? FIXTURE_C.currency)]);
  return view;
}

function assertNoForbiddenText(label: string, html: string) {
  for (const forbidden of ['产品小计', '外币小计', '¥', '￥']) {
    expect(html.includes(forbidden), `${label}: 金额区标记不得含「${forbidden}」`).toBe(false);
  }
}

describe('task261030-costing-subtotal / S-UNIT', () => {
  it.each([
    {
      limit: 4,
      role: 'SALES_REP / SALES_MANAGER（最多 4 位）',
      expected: [
        ['1717.1355 CNY', '(VAT) 1940.3632 CNY'],
        ['241.0556 USD', '(VAT) 272.3928 USD'],
      ],
    },
    {
      limit: 9,
      role: 'PRICING_MANAGER / SYSTEM_ADMIN（最多 9 位，同 AC-1）',
      expected: AC1_LINES,
    },
  ])('T-01 / AC-2 夹具 C 按 $role 显示', ({ limit, expected }) => {
    const props: CostingSummaryProps = { ...FIXTURE_C, maxDisplayDecimals: limit };
    const before = JSON.stringify(props);

    const view = renderTwoColumns(`T-01 limit=${limit}`, props);

    expect(columnLines(view), `T-01 limit=${limit}: 4 段文本逐字等于 AC-2`).toEqual(expected);
    // api.md §3.1 文本节点契约（主线 2026-10-30 补定）：前缀原文 "(VAT) "；「金额 币种」半角单空格、无多余空白。
    expect(view.columns.map((col) => col.prefixRaw), '(VAT) 前缀节点原文为 "(VAT) "').toEqual(['(VAT) ', '(VAT) ']);
    expect(view.columns.map((col) => col.mainRaw), '第一行原文即「金额 币种」，半角单空格').toEqual(expected.map((lines) => lines[0]));
    assertNoForbiddenText(`T-01 limit=${limit}`, view.html);
    expect(JSON.stringify(props), '显示舍入不得修改调用方传入的 props（输入值调用前后逐字不变）').toBe(before);
    expect(props.subtotal, '核价小计 canonical 字符串保持原样').toBe('1717.135540717');
  });

  it('T-01 / AC-2 4 位显示不得残留 9 位文本（舍入发生在显示边界而非只截了一列）', () => {
    const html = renderCostingSummary({ ...FIXTURE_C, maxDisplayDecimals: 4 });
    for (const nineDigit of ['1717.135540717', '1940.36316101', '241.055611185', '272.392840639']) {
      expect(html.includes(nineDigit), `4 位标记中不得出现 9 位文本 ${nineDigit}`).toBe(false);
    }
  });

  it.each([
    {
      limit: 9,
      expected: [
        ['9007199254740993.000000001 CNY', '(VAT) 10178135157857322.090000001 CNY'],
        ['4503599627370496.500000001 USD', '(VAT) 5089067578928661.045000001 USD'],
      ],
    },
    {
      limit: 4,
      expected: [
        ['9007199254740993 CNY', '(VAT) 10178135157857322.09 CNY'],
        ['4503599627370496.5 USD', '(VAT) 5089067578928661.045 USD'],
      ],
    },
  ])('T-01 / AC-2 超出 JS 安全整数的小计在 $limit 位下仍逐位精确（派生值不经 number）', ({ limit, expected }) => {
    // 2^53 + 1：一旦经过 JavaScript number 会变成 9007199254740992 / 9007199254740994。
    const view = renderTwoColumns(`T-01 big limit=${limit}`, {
      ...FIXTURE_C,
      subtotal: '9007199254740993.000000001',
      exchangeRate: '2',
      maxDisplayDecimals: limit,
    });
    expect(columnLines(view)).toEqual(expected);
    for (const lossy of ['9007199254740992', '9007199254740994', '10178135157857324', '4503599627370496 ']) {
      expect(view.html.includes(lossy), `不得出现经 number 丢精度的文本 ${lossy}`).toBe(false);
    }
  });

  const T02_CASES: Array<{
    name: string;
    props: Partial<CostingSummaryProps>;
    expectedVat: [string, string];
  }> = [
    {
      name: '① vatRateRatio="0" → 两列第二行等于第一行',
      props: { vatRateRatio: '0', vatRate: undefined },
      expectedVat: ['(VAT) 1717.135540717 CNY', '(VAT) 241.055611185 USD'],
    },
    {
      name: '② 无 vatRateRatio、vatRate="13" → 同 AC-1',
      props: { vatRateRatio: undefined, vatRate: '13' },
      expectedVat: ['(VAT) 1940.36316101 CNY', '(VAT) 272.392840639 USD'],
    },
    {
      name: '② 变体：vatRateRatio=null、vatRate="13" → 同 AC-1',
      props: { vatRateRatio: null, vatRate: '13' },
      expectedVat: ['(VAT) 1940.36316101 CNY', '(VAT) 272.392840639 USD'],
    },
    {
      name: '③ vatRateRatio="0.13"、vatRate="5" → ratio 优先，仍同 AC-1',
      props: { vatRateRatio: '0.13', vatRate: '5' },
      expectedVat: ['(VAT) 1940.36316101 CNY', '(VAT) 272.392840639 USD'],
    },
    {
      name: '边界（由「大于 1 → -」推出）：vatRateRatio="1" 为合法上界',
      props: { vatRateRatio: '1', vatRate: undefined },
      expectedVat: ['(VAT) 3434.271081434 CNY', '(VAT) 482.11122237 USD'],
    },
    {
      name: '④ 两字段都缺失（undefined）',
      props: { vatRateRatio: undefined, vatRate: undefined },
      expectedVat: ['(VAT) -', '(VAT) -'],
    },
    {
      name: '④ 两字段都缺失（null）',
      props: { vatRateRatio: null, vatRate: null },
      expectedVat: ['(VAT) -', '(VAT) -'],
    },
    {
      name: '④ ratio 非数字（无 vatRate）',
      props: { vatRateRatio: 'not-a-number', vatRate: undefined },
      expectedVat: ['(VAT) -', '(VAT) -'],
    },
    {
      name: '④ ratio 非数字且 vatRate="13" 存在 → 不回退（仅缺失时才用 vatRate）',
      props: { vatRateRatio: 'not-a-number', vatRate: '13' },
      expectedVat: ['(VAT) -', '(VAT) -'],
    },
    {
      name: '④ ratio 小于 0',
      props: { vatRateRatio: '-0.000000000001', vatRate: '13' },
      expectedVat: ['(VAT) -', '(VAT) -'],
    },
    {
      name: '④ ratio 大于 1',
      props: { vatRateRatio: '1.000000000001', vatRate: '13' },
      expectedVat: ['(VAT) -', '(VAT) -'],
    },
  ];

  it.each(T02_CASES)('T-02 / AC-5 $name', ({ name, props, expectedVat }) => {
    const merged = { ...FIXTURE_C, ...props } as CostingSummaryProps;
    expect(() => renderCostingSummary(merged), `${name}: 税率异常不得抛到核价卡片`).not.toThrow();

    const view = renderTwoColumns(`T-02 ${name}`, merged);
    expect(view.columns.every((col) => col.main.length > 0 && col.vat.length > 0), '两列都必须非空').toBe(true);
    expect(view.columns.map((col) => col.main), '税率来源 / 异常不得影响两列第一行')
      .toEqual(['1717.135540717 CNY', '241.055611185 USD']);
    expect(view.columns.map((col) => col.vat), `${name}: 两列第二行`).toEqual(expectedVat);
  });

  it('T-03 / AC-8 长值夹具的 4 段文本（几何由 Playwright 静态页量）', () => {
    const view = renderTwoColumns('T-03 long', { ...FIXTURE_LONG });
    expect(columnLines(view)).toEqual([
      ['99999999999999.999999999 CNY', '(VAT) 199999999998999.999999998 CNY'],
      ['199999999999999.999999998 USDT', '(VAT) 399999999997999.999999996 USDT'],
    ]);
    assertNoForbiddenText('T-03 long', view.html);
  });
});
