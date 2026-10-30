/**
 * task-261030 · 核价卡片金额区 —— 组件级用例共用的 SSR 渲染与 DOM 读取工具。
 *
 * 只依赖 `api.md §3.1` 的组件契约（文件 / 具名导出 / props）与 `api.md §3` 的 DOM 定位点；
 * 🚫 不读、不引用实现内部函数。期望值全部来自 `需求文档.md` ③ AC 原文。
 *
 * 为什么自带一个最小 HTML 解析器：仓库没有 jsdom / happy-dom / RTL，且本任务禁止 `npm install`。
 * `renderToStaticMarkup` 的输出是 React 生成的良构 HTML（属性恒为双引号、无省略闭合标签），
 * 用一个栈式解析器即可按 class / data-* 精确定位，避免正则跨层匹配嵌套 div 的误判。
 */
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { CostingSubtotalSummary } from '../CostingSubtotalSummary';

export type CostingSummaryProps = React.ComponentProps<typeof CostingSubtotalSummary>;

/** 夹具 C（需求文档 ③）：报价币种 USD、汇率 7.1234、ratio 0.13、核价小计 1717.135540717。 */
export const FIXTURE_C = Object.freeze({
  subtotal: '1717.135540717',
  currency: 'USD',
  exchangeRate: '7.1234',
  vatRateRatio: '0.13',
  maxDisplayDecimals: 9,
});

/** AC-8 长值夹具。 */
export const FIXTURE_LONG = Object.freeze({
  subtotal: '99999999999999.999999999',
  currency: 'USDT',
  exchangeRate: '0.5',
  vatRateRatio: '0.99999999999',
  maxDisplayDecimals: 9,
});

export function renderCostingSummary(props: Partial<CostingSummaryProps>): string {
  const merged = { ...FIXTURE_C, ...props } as CostingSummaryProps;
  return renderToStaticMarkup(<CostingSubtotalSummary {...merged} />);
}

// ───────────────────────────── 最小 HTML 树 ─────────────────────────────

export interface HtmlElement {
  tag: string;
  attrs: Record<string, string>;
  children: Array<HtmlElement | string>;
}

const VOID_TAGS = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'source', 'track', 'wbr']);

export function decodeEntities(text: string): string {
  return text
    .replace(/&#x([0-9a-f]+);/gi, (_, hex: string) => String.fromCodePoint(parseInt(hex, 16)))
    .replace(/&#(\d+);/g, (_, dec: string) => String.fromCodePoint(Number(dec)))
    .replace(/&quot;/g, '"')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&nbsp;/g, '\u00a0')
    .replace(/&amp;/g, '&');
}

export function parseHtml(html: string): HtmlElement {
  const root: HtmlElement = { tag: '#root', attrs: {}, children: [] };
  const stack: HtmlElement[] = [root];
  const tokenRe = /<!--[\s\S]*?-->|<\/([a-zA-Z0-9-]+)\s*>|<([a-zA-Z0-9-]+)((?:\s+[^\s"'>/=]+(?:="[^"]*")?)*)\s*(\/?)>|([^<]+)/g;
  let match: RegExpExecArray | null;
  while ((match = tokenRe.exec(html)) !== null) {
    const [whole, closeTag, openTag, rawAttrs, selfClose, text] = match;
    const top = stack[stack.length - 1];
    if (whole.startsWith('<!--')) continue;
    if (text !== undefined) {
      top.children.push(decodeEntities(text));
      continue;
    }
    if (closeTag) {
      const name = closeTag.toLowerCase();
      if (top.tag !== name) {
        throw new Error(`SSR markup is not well-formed: </${name}> closes <${top.tag}>`);
      }
      stack.pop();
      continue;
    }
    const attrs: Record<string, string> = {};
    for (const attr of (rawAttrs ?? '').matchAll(/([^\s"'>/=]+)(?:="([^"]*)")?/g)) {
      attrs[attr[1]] = decodeEntities(attr[2] ?? '');
    }
    const element: HtmlElement = { tag: openTag.toLowerCase(), attrs, children: [] };
    top.children.push(element);
    if (!selfClose && !VOID_TAGS.has(element.tag)) stack.push(element);
  }
  if (stack.length !== 1) {
    throw new Error(`SSR markup is not well-formed: unclosed <${stack[stack.length - 1].tag}>`);
  }
  return root;
}

export function hasClass(element: HtmlElement, className: string): boolean {
  return (element.attrs.class ?? '').split(/\s+/).includes(className);
}

export function queryAll(scope: HtmlElement, predicate: (element: HtmlElement) => boolean): HtmlElement[] {
  const found: HtmlElement[] = [];
  const walk = (node: HtmlElement) => {
    for (const child of node.children) {
      if (typeof child === 'string') continue;
      if (predicate(child)) found.push(child);
      walk(child);
    }
  };
  walk(scope);
  return found;
}

export function byClass(scope: HtmlElement, className: string): HtmlElement[] {
  return queryAll(scope, (element) => hasClass(element, className));
}

/** 原始 textContent（不折叠空白），用于「不含某字符」的反向断言。 */
export function rawText(node: HtmlElement | string): string {
  if (typeof node === 'string') return node;
  return node.children.map(rawText).join('');
}

export function normalize(text: string): string {
  return text.replace(/\s+/g, ' ').trim();
}

// ───────────────────────────── 金额区视图 ─────────────────────────────

export interface CostingColumnView {
  currency: string | undefined;
  empty: string | undefined;
  mainCount: number;
  vatCount: number;
  prefixCount: number;
  valueCount: number;
  /** 第一行（税前）textContent 折叠空白。 */
  main: string;
  /**
   * 第二行文本：`(VAT)` 前缀 + 一个空格 + 第二行其余文本。
   * 前缀与金额之间允许由元素边界（CSS 间距）分隔，不强求字面空格；
   * 金额与币种之间则要求文本里真有空格（AC 写的是「金额 币种缩写」）。
   */
  vat: string;
  prefix: string;
  /** api.md §3.1 文本节点契约：前缀节点原始文本应为 `"(VAT) "`；第一行原始 textContent（未折叠）。 */
  prefixRaw: string;
  mainRaw: string;
}

export interface CostingSummaryView {
  html: string;
  rootCount: number;
  rootText: string;
  columns: CostingColumnView[];
}

function vatRowText(vatRow: HtmlElement): { prefix: string; text: string } {
  const prefixEl = byClass(vatRow, 'qt-subtotal-vat-prefix')[0];
  if (!prefixEl) return { prefix: '', text: normalize(rawText(vatRow)) };
  const withoutPrefix = (node: HtmlElement | string): string => {
    if (typeof node === 'string') return node;
    if (node === prefixEl) return '';
    return node.children.map(withoutPrefix).join('');
  };
  const prefix = normalize(rawText(prefixEl));
  return { prefix, text: normalize(`${prefix} ${withoutPrefix(vatRow)}`) };
}

export function readCostingSummary(html: string): CostingSummaryView {
  const tree = parseHtml(html);
  const roots = byClass(tree, 'qt-subtotal-costing');
  const root = roots[0];
  const columns = root
    ? byClass(root, 'qt-subtotal-costing-col').map((col): CostingColumnView => {
        const mains = byClass(col, 'qt-subtotal-costing-main');
        const vats = byClass(col, 'qt-subtotal-costing-vat');
        const vat = vats[0] ? vatRowText(vats[0]) : { prefix: '', text: '' };
        return {
          currency: col.attrs['data-currency'],
          empty: col.attrs['data-empty'],
          mainCount: mains.length,
          vatCount: vats.length,
          prefixCount: vats[0] ? byClass(vats[0], 'qt-subtotal-vat-prefix').length : 0,
          valueCount: vats[0] ? byClass(vats[0], 'qt-subtotal-vat-value').length : 0,
          main: mains[0] ? normalize(rawText(mains[0])) : '',
          vat: vat.text,
          prefix: vat.prefix,
          prefixRaw: vats[0] && byClass(vats[0], 'qt-subtotal-vat-prefix')[0]
            ? rawText(byClass(vats[0], 'qt-subtotal-vat-prefix')[0]) : '',
          mainRaw: mains[0] ? rawText(mains[0]) : '',
        };
      })
    : [];
  return { html, rootCount: roots.length, rootText: root ? rawText(root) : '', columns };
}

/** 每列只取 [第一行, 第二行]，便于与 AC 原文逐字比较。 */
export function columnLines(view: CostingSummaryView): string[][] {
  return view.columns.map((col) => [col.main, col.vat]);
}

/** 打印实际值（testing.md §3：断言前要求测试打印实际值）。 */
export function logView(label: string, view: CostingSummaryView): void {
  console.info(`[T261030_COST_][${label}] roots=${view.rootCount} columns=${JSON.stringify(
    view.columns.map((col) => ({ currency: col.currency, empty: col.empty, lines: [col.main, col.vat] })),
  )}`);
}

/** 结构性前置断言所需的信息：每列各 1 个第一行 / 第二行 / 前缀 / 金额节点。 */
export function columnShapeProblems(view: CostingSummaryView): string[] {
  return view.columns.flatMap((col, index) => {
    const problems: string[] = [];
    if (col.mainCount !== 1) problems.push(`col${index}: .qt-subtotal-costing-main x${col.mainCount}`);
    if (col.vatCount !== 1) problems.push(`col${index}: .qt-subtotal-costing-vat x${col.vatCount}`);
    if (col.prefixCount !== 1) problems.push(`col${index}: .qt-subtotal-vat-prefix x${col.prefixCount}`);
    if (col.valueCount !== 1) problems.push(`col${index}: .qt-subtotal-vat-value x${col.valueCount}`);
    if (col.prefix !== '(VAT)') problems.push(`col${index}: prefix text ${JSON.stringify(col.prefix)}`);
    return problems;
  });
}
