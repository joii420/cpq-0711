import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { QuotationVatSubtotal } from './QuotationVatSubtotal';

type VatSubtotalProps = React.ComponentProps<typeof QuotationVatSubtotal>;

function renderSubtotal(props: Partial<VatSubtotalProps> = {}): string {
  return renderToStaticMarkup(
    <QuotationVatSubtotal
      subtotal="1717.135540717"
      vatRateRatio="0.13"
      currency="USD"
      maxDisplayDecimals={9}
      {...props}
    />,
  );
}

function textContent(fragment: string): string {
  return fragment
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/\s+/g, ' ')
    .trim();
}

function subtotalLines(html: string): string[] {
  return Array.from(
    html.matchAll(/<div[^>]*class="[^"]*\bqt-subtotal-line\b[^"]*"[^>]*>([\s\S]*?)<\/div>/g),
    (match) => textContent(match[1]),
  );
}

describe('task261028-vat-subtotal / S-UNIT', () => {
  it('T-01 / AC-1 renders the two exact quotation subtotal lines without price labels', () => {
    const html = renderSubtotal();

    expect(subtotalLines(html), '报价总计必须非空且恰好渲染税前/VAT 两行').toEqual([
      '1717.135540717 USD',
      '(VAT) 1940.36316101 USD',
    ]);
    expect(html.match(/\bqt-subtotal-value\b/g), '首行必须保留既有定位点').toHaveLength(1);
    expect(html).not.toContain('产品小计');
    expect(html).not.toContain('税前价格');
    expect(html).not.toContain('税后价格');
  });

  it.each([
    {
      limit: 4,
      expected: ['1717.1355 USD', '(VAT) 1940.3632 USD'],
      role: 'SALES_REP / SALES_MANAGER',
    },
    {
      limit: 9,
      expected: ['1717.135540717 USD', '(VAT) 1940.36316101 USD'],
      role: 'PRICING_MANAGER / SYSTEM_ADMIN',
    },
  ])('T-02 / AC-2 applies the $limit-place computed-result cap for $role', ({ limit, expected }) => {
    const originalSubtotal = '1717.135540717';
    const html = renderSubtotal({ subtotal: originalSubtotal, maxDisplayDecimals: limit });

    expect(subtotalLines(html)).toEqual(expected);
    expect(originalSubtotal, '显示舍入不得修改调用方持有的 canonical decimal string')
      .toBe('1717.135540717');
  });

  it.each([
    {
      name: 'ratio=0 keeps the two values equal',
      props: { vatRateRatio: '0' },
      expectedVat: '(VAT) 1717.135540717 USD',
    },
    {
      name: 'missing ratio falls back to percentage vatRate',
      props: { vatRateRatio: undefined, vatRate: '13' },
      expectedVat: '(VAT) 1940.36316101 USD',
    },
    {
      name: 'authoritative ratio wins over a conflicting percentage vatRate',
      props: { vatRateRatio: '0.13', vatRate: '5' },
      expectedVat: '(VAT) 1940.36316101 USD',
    },
    {
      name: 'ratio=1 is the inclusive upper boundary',
      props: { vatRateRatio: '1' },
      expectedVat: '(VAT) 3434.271081434 USD',
    },
    {
      name: 'both VAT fields missing degrades only the VAT line',
      props: { vatRateRatio: undefined, vatRate: undefined },
      expectedVat: '(VAT) -',
    },
    {
      name: 'an unparsable authoritative ratio does not fall back',
      props: { vatRateRatio: 'not-a-number', vatRate: '13' },
      expectedVat: '(VAT) -',
    },
    {
      name: 'a negative authoritative ratio degrades locally',
      props: { vatRateRatio: '-0.000000000001', vatRate: '13' },
      expectedVat: '(VAT) -',
    },
    {
      name: 'a ratio above one degrades locally',
      props: { vatRateRatio: '1.000000000001', vatRate: '13' },
      expectedVat: '(VAT) -',
    },
  ] satisfies Array<{
    name: string;
    props: Partial<VatSubtotalProps>;
    expectedVat: string;
  }>)('T-03 / AC-4 $name', ({ props, expectedVat }) => {
    expect(() => renderSubtotal(props), 'VAT 异常不得抛到报价卡片').not.toThrow();
    const html = renderSubtotal(props);
    const lines = subtotalLines(html);

    expect(lines, '异常夹具仍必须渲染非空的两行总计').toHaveLength(2);
    expect(lines[0], 'VAT 异常不得影响税前首行').toBe('1717.135540717 USD');
    expect(lines[1]).toBe(expectedVat);
  });

  it('T-03 / AC-2 preserves decimal-string precision beyond the JavaScript safe integer range', () => {
    const html = renderSubtotal({
      subtotal: '9007199254740993.000000001',
      vatRateRatio: '0.13',
      maxDisplayDecimals: 9,
    });

    expect(subtotalLines(html)).toEqual([
      '9007199254740993.000000001 USD',
      '(VAT) 10178135157857322.090000001 USD',
    ]);
    expect(html).not.toContain('10178135157857324');
  });
});
