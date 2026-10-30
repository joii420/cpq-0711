/**
 * task-261030 · S-FLOW 的组件级部分
 *
 *   - T-04 / AC-4：没有核价数据（`subtotal: null`，即「加载组件结构...」「暂无组件数据」「核价数据生成失败…」
 *     三种状态由页面传入的值）→ 金额区恰好 1 列：`-` / `(VAT) -`，不出现任何金额、任何币种、`¥`。
 *   - T-06c / AC-3（组件级补充，页面级见 T-06）：报价币种 `CNY` / 空串 / 仅空白 / 未传 → 只有 CNY 一列。
 *
 * 🚫 只依赖 `api.md §3.1` 组件契约与 §3 DOM 定位点，不读实现。
 */
import { describe, expect, it } from 'vitest';
import {
  FIXTURE_C,
  columnLines,
  columnShapeProblems,
  logView,
  readCostingSummary,
  renderCostingSummary,
  type CostingSummaryProps,
} from './__tests__/task261030-costing-subtotal.ssr';

describe('task261030-costing-subtotal / S-FLOW component level', () => {
  it.each([
    { name: '夹具 C 其余字段齐全（USD / 7.1234 / 0.13）', props: { ...FIXTURE_C, subtotal: null } },
    { name: '报价币种 CNY', props: { ...FIXTURE_C, subtotal: null, currency: 'CNY', exchangeRate: '1' } },
    { name: '4 位上限', props: { ...FIXTURE_C, subtotal: null, maxDisplayDecimals: 4 } },
  ] satisfies Array<{ name: string; props: CostingSummaryProps }>)('T-04 / AC-4 subtotal=null：$name', ({ name, props }) => {
    expect(() => renderCostingSummary(props), `${name}: 无核价数据不得抛异常`).not.toThrow();
    const view = readCostingSummary(renderCostingSummary(props));
    logView(`T-04 ${name}`, view);

    expect(view.rootCount, '.qt-subtotal-costing 必须恰好 1 个（无数据也要渲染金额区）').toBe(1);
    expect(view.columns.length, '无核价数据时金额区恰好 1 列').toBe(1);
    expect(columnShapeProblems(view), '唯一一列仍须有第一行 / 第二行 / (VAT) 前缀 / 金额节点').toEqual([]);
    const [col] = view.columns;
    expect(col.empty, '无数据列 data-empty="true"').toBe('true');
    expect(col.currency, '无数据列 data-currency=""').toBe('');
    expect(columnLines(view)).toEqual([['-', '(VAT) -']]);

    for (const forbidden of ['CNY', 'USD', '¥', '￥', '1717.135540717', '888.123456789', '产品小计', '外币小计']) {
      expect(view.html.includes(forbidden), `${name}: 无数据金额区标记不得含「${forbidden}」`).toBe(false);
    }
  });

  it.each([
    { name: "currency='CNY'、汇率 1", props: { currency: 'CNY', exchangeRate: '1' } },
    { name: "currency=''（空串）、汇率 1", props: { currency: '', exchangeRate: '1' } },
    { name: "currency='   '（trim 后为空）、汇率 1", props: { currency: '   ', exchangeRate: '1' } },
    { name: 'currency 未传、汇率 1', props: { currency: undefined, exchangeRate: '1' } },
  ] satisfies Array<{ name: string; props: Partial<CostingSummaryProps> }>)('T-06c / AC-3 $name → 只有 CNY 一列', ({ name, props }) => {
    const view = readCostingSummary(renderCostingSummary({ ...FIXTURE_C, ...props }));
    logView(`T-06c ${name}`, view);

    expect(view.rootCount, '.qt-subtotal-costing 必须恰好 1 个').toBe(1);
    expect(view.columns.length, '报价币种为 CNY / 空时列数为 1').toBe(1);
    expect(columnShapeProblems(view)).toEqual([]);
    expect(view.columns[0].currency).toBe('CNY');
    expect(columnLines(view)).toEqual([['1717.135540717 CNY', '(VAT) 1940.36316101 CNY']]);
    expect(view.rootText.includes('USD'), '金额区内 USD 出现 0 次').toBe(false);
  });
});
