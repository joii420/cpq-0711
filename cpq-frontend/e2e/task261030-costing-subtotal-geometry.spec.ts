/**
 * task-261030 · S-UNIT · T-03 / AC-8 长值与窄屏几何（SSR 静态页 + Playwright）
 *
 * AC-8 原文：组件夹具传核价小计 `99999999999999.999999999`、`vatRateRatio="0.99999999999"`、币种 `USDT`、汇率 `0.5`，
 * 在 1280×720 与 390×844 视口各渲染一次。两种宽度下 4 段都在卡片内右对齐；放不下两列并排时（390 宽必然如此）
 * 第二列整列换到下一行、CNY 列在上，不出现半列拆开；两种宽度下 `(VAT)` 与其金额同行且在前，数值内部不断行、
 * 互不覆盖，`document.scrollWidth === document.clientWidth`。
 *
 * 静态页由 `task261030-costing-subtotal-unit.global-setup.ts` 每轮新生成（带 run id）。
 * 运行：`npx playwright test -c e2e/task261030-costing-subtotal-unit.config.ts`
 */
import { expect, test, type Page } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const TASK_DIR = path.resolve(
  HERE, '..', '..', 'dev-docs', 'task-260801-公式计算精度优化', 'task-261030-核价卡片小计展示调整',
);
const RUN_STAMP = new Date().toISOString().replace(/[:.]/g, '-');
const EVIDENCE_DIR = path.join(
  process.env.T261030_EVIDENCE_DIR?.trim() || path.join(TASK_DIR, '证据'),
  `S-UNIT-T03-${RUN_STAMP}`,
);

const LONG_LINES = [
  ['99999999999999.999999999 CNY', '(VAT) 199999999998999.999999998 CNY'],
  ['199999999999999.999999998 USDT', '(VAT) 399999999997999.999999996 USDT'],
];

type Box = { x: number; y: number; width: number; height: number; right: number; bottom: number };
type RowScroll = { scrollWidth: number; clientWidth: number; overflowX: string };

interface Measurement {
  runId: string;
  harnessStyled: boolean;
  rootCount: number;
  colCount: number;
  currencies: string[];
  lines: string[][];
  card: Box;
  content: { left: number; right: number };
  cols: Array<{
    main: Box; vatRow: Box; prefix: Box; value: Box;
    mainLines: number; valueLines: number; mainWhiteSpace: string; valueWhiteSpace: string;
    mainScroll: RowScroll; vatScroll: RowScroll;
    counts: { main: number; vat: number; prefix: number; value: number };
  }>;
  clips: Array<{
    element: string; elementRight: number; clipper: string; clipperOverflowX: string;
    clipperRight: number | null; clipperClientWidth: number | null; clipperScrollWidth: number | null;
  }>;
  scrollWidth: number;
  clientWidth: number;
}

function staticPage(file: string): string {
  const dir = process.env.T261030_STATIC_OUT;
  if (!dir) throw new Error('T261030_STATIC_OUT is not set: global-setup did not run');
  const target = path.join(dir, file);
  if (!fs.existsSync(target)) throw new Error(`static page missing: ${target}`);
  return pathToFileURL(target).href;
}

async function measure(page: Page): Promise<Measurement> {
  return page.evaluate(() => {
    const toBox = (rect: DOMRect) => ({
      x: rect.x, y: rect.y, width: rect.width, height: rect.height, right: rect.right, bottom: rect.bottom,
    });
    const lineCount = (element: Element | null) => {
      if (!element) return 0;
      const range = document.createRange();
      range.selectNodeContents(element);
      const tops: number[] = [];
      for (const rect of Array.from(range.getClientRects())) {
        if (rect.width === 0 && rect.height === 0) continue;
        if (!tops.some((top) => Math.abs(top - rect.top) <= 2)) tops.push(rect.top);
      }
      return tops.length;
    };
    const rowScroll = (element: Element | null) => (element
      ? { scrollWidth: element.scrollWidth, clientWidth: element.clientWidth, overflowX: getComputedStyle(element).overflowX }
      : { scrollWidth: 0, clientWidth: 0, overflowX: '<missing>' });
    const norm = (text: string | null | undefined) => (text ?? '').replace(/\s+/g, ' ').trim();
    const emptyRect = new DOMRect(0, 0, 0, 0);

    // harness 有效性：至少一条选择器提到 qt-subtotal-costing 的规则命中了页面元素。
    let harnessStyled = false;
    for (const sheet of Array.from(document.styleSheets)) {
      for (const rule of Array.from(sheet.cssRules)) {
        const selector = (rule as CSSStyleRule).selectorText;
        if (selector && selector.includes('qt-subtotal-costing')) {
          try { if (document.querySelector(selector)) harnessStyled = true; } catch { /* ignore invalid */ }
        }
      }
    }

    const card = document.querySelector('[data-t261030-card]')!;
    // 真实外壳（api.md §3.1）：编辑页 div.qt-subtotal-bar-multi / 只读页 div.qt-subtotal-bar.qt-subtotal-bar-multi。
    const bar = card.querySelector('[data-t261030-shell]') ?? card;
    const barStyle = getComputedStyle(bar);
    const barRect = bar.getBoundingClientRect();
    const roots = document.querySelectorAll('.qt-subtotal-costing');
    const cols = Array.from(roots[0]?.querySelectorAll('.qt-subtotal-costing-col') ?? []);
    return {
      runId: document.querySelector('meta[name="t261030-run-id"]')?.getAttribute('content') ?? '',
      harnessStyled,
      rootCount: roots.length,
      colCount: cols.length,
      currencies: cols.map((col) => col.getAttribute('data-currency') ?? '<missing>'),
      // 文本口径与 SSR 用例一致：第一行取 textContent；第二行 = `(VAT)` 前缀 + 空格 + 其余文本
      // （前缀与金额之间允许由元素边界 / CSS 间距分隔）。
      lines: cols.map((col) => {
        const vat = col.querySelector('.qt-subtotal-costing-vat');
        const prefix = vat?.querySelector('.qt-subtotal-vat-prefix');
        let vatText = norm(vat?.textContent);
        if (vat && prefix) {
          const clone = vat.cloneNode(true) as Element;
          clone.querySelector('.qt-subtotal-vat-prefix')?.remove();
          vatText = norm(`${prefix.textContent ?? ''} ${clone.textContent ?? ''}`);
        }
        return [norm(col.querySelector('.qt-subtotal-costing-main')?.textContent), vatText];
      }),
      card: toBox(card.getBoundingClientRect()),
      content: {
        left: barRect.left + parseFloat(barStyle.borderLeftWidth) + parseFloat(barStyle.paddingLeft),
        right: barRect.right - parseFloat(barStyle.borderRightWidth) - parseFloat(barStyle.paddingRight),
      },
      cols: cols.map((col) => {
        const main = col.querySelector('.qt-subtotal-costing-main');
        const vat = col.querySelector('.qt-subtotal-costing-vat');
        const prefix = vat?.querySelector('.qt-subtotal-vat-prefix') ?? null;
        const value = vat?.querySelector('.qt-subtotal-vat-value') ?? null;
        return {
          main: toBox(main?.getBoundingClientRect() ?? emptyRect),
          vatRow: toBox(vat?.getBoundingClientRect() ?? emptyRect),
          prefix: toBox(prefix?.getBoundingClientRect() ?? emptyRect),
          value: toBox(value?.getBoundingClientRect() ?? emptyRect),
          mainLines: lineCount(main),
          valueLines: lineCount(value),
          mainScroll: rowScroll(main),
          vatScroll: rowScroll(vat ?? null),
          mainWhiteSpace: main ? getComputedStyle(main).whiteSpace : '',
          valueWhiteSpace: value ? getComputedStyle(value).whiteSpace : '',
          counts: {
            main: col.querySelectorAll('.qt-subtotal-costing-main').length,
            vat: col.querySelectorAll('.qt-subtotal-costing-vat').length,
            prefix: vat?.querySelectorAll('.qt-subtotal-vat-prefix').length ?? 0,
            value: vat?.querySelectorAll('.qt-subtotal-vat-value').length ?? 0,
          },
        };
      }),
      // 仅记录（不参与断言）：每个第一行 / 金额节点最近的非 visible 溢出祖先（含自身），用于判断溢出是被裁剪还是可滚动。
      clips: Array.from(document.querySelectorAll('.qt-subtotal-costing-main, .qt-subtotal-vat-value')).map((el) => {
        let node: Element | null = el;
        while (node && node !== document.body && getComputedStyle(node).overflowX === 'visible') node = node.parentElement;
        const rect = node && node !== document.body ? node.getBoundingClientRect() : null;
        return {
          element: el.className,
          elementRight: el.getBoundingClientRect().right,
          clipper: node && node !== document.body ? node.className : '<none>',
          clipperOverflowX: node && node !== document.body ? getComputedStyle(node).overflowX : '',
          clipperRight: rect?.right ?? null,
          clipperClientWidth: node && node !== document.body ? node.clientWidth : null,
          clipperScrollWidth: node && node !== document.body ? node.scrollWidth : null,
        };
      }),
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    };
  });
}

const union = (a: Box, b: Box): Box => {
  const x = Math.min(a.x, b.x);
  const y = Math.min(a.y, b.y);
  const right = Math.max(a.right, b.right);
  const bottom = Math.max(a.bottom, b.bottom);
  return { x, y, right, bottom, width: right - x, height: bottom - y };
};
const verticalOverlap = (a: Box, b: Box) => Math.max(a.y, b.y) < Math.min(a.bottom, b.bottom);
const intersectArea = (a: Box, b: Box) =>
  Math.max(0, Math.min(a.right, b.right) - Math.max(a.x, b.x))
  * Math.max(0, Math.min(a.bottom, b.bottom) - Math.max(a.y, b.y));

// 不用 serial：各用例相互独立，一条失败不得让其余外壳 / 视口不执行。
test.beforeAll(() => {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
});

const SHELLS = [
  { name: 'edit', className: 'qt-subtotal-bar-multi' },
  { name: 'readonly', className: 'qt-subtotal-bar qt-subtotal-bar-multi' },
] as const;

for (const shell of SHELLS) {
test(`T-03 harness 阳性对照（${shell.name} 外壳）：默认夹具静态页已上样式且为 2 列（1280 宽下并排、CNY 在左）`, async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 720 });
  await page.goto(staticPage(`default-${shell.name}.html`));
  expect(await page.locator('[data-t261030-shell]').getAttribute('class'), '外壳 class 必须是该页面的真实容器')
    .toBe(shell.className);
  const m = await measure(page);
  console.info(`[T261030_COST_][T-03 control] ${JSON.stringify({ lines: m.lines, currencies: m.currencies, harnessStyled: m.harnessStyled })}`);
  expect(m.runId, '静态页必须是本轮 global-setup 生成的').toBe(process.env.T261030_STATIC_RUN_ID);
  expect(m.harnessStyled, 'harness 失效：没有任何 .qt-subtotal-costing 相关样式规则命中页面元素').toBe(true);
  expect(m.colCount).toBe(2);
  expect(m.lines).toEqual([
    ['1717.135540717 CNY', '(VAT) 1940.36316101 CNY'],
    ['241.055611185 USD', '(VAT) 272.392840639 USD'],
  ]);
  const [cny, fx] = m.cols.map((col) => union(col.main, col.vatRow));
  expect(verticalOverlap(cny, fx), '默认值在 1280 宽下两列应处于同一水平带').toBe(true);
  expect(cny.right, 'CNY 列在左').toBeLessThanOrEqual(fx.x + 0.5);
});

for (const viewport of [
  { width: 1280, height: 720, name: 'desktop', mustStack: false },
  { width: 390, height: 844, name: 'mobile', mustStack: true },
]) {
  test(`T-03 / AC-8 长值 ${shell.name} 外壳 ${viewport.width}×${viewport.height}`, async ({ page }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.goto(staticPage(`long-${shell.name}.html`));
    // 证伪钩子（testing.md §4.4）：T261030_FALSIFY=row-hidden 时把行容器改成 overflow-x:hidden，
    // 390 宽的「行必须可滚动」断言必须因此硬失败；正常运行不设该变量。
    if (process.env.T261030_FALSIFY === 'row-hidden') {
      await page.addStyleTag({ content: '.qt-subtotal-costing-main,.qt-subtotal-costing-vat{overflow-x:hidden !important}' });
    }
    expect(await page.locator('[data-t261030-shell]').getAttribute('class'), '外壳 class 必须是该页面的真实容器')
      .toBe(shell.className);
    const m = await measure(page);
    const stem = `T-03-long-${shell.name}-${viewport.width}x${viewport.height}`;
    fs.writeFileSync(path.join(EVIDENCE_DIR, `${stem}.json`), JSON.stringify(m, null, 2), 'utf8');
    await page.screenshot({ path: path.join(EVIDENCE_DIR, `${stem}.png`), fullPage: true });
    console.info(`[T261030_COST_][T-03 ${shell.name} ${viewport.name}] lines=${JSON.stringify(m.lines)} scroll=${m.scrollWidth}/${m.clientWidth} `
      + `whiteSpace=${JSON.stringify(m.cols.map((col) => [col.mainWhiteSpace, col.valueWhiteSpace]))}`);

    // ── 前置：页是本轮的、样式生效、结构非空 ──
    expect(m.runId, '静态页必须是本轮 global-setup 生成的').toBe(process.env.T261030_STATIC_RUN_ID);
    expect(m.harnessStyled, 'harness 失效：样式未作用于金额区').toBe(true);
    expect(m.rootCount, '.qt-subtotal-costing 恰好 1 个').toBe(1);
    expect(m.colCount, '长值夹具恰好 2 列').toBe(2);
    for (const [index, col] of m.cols.entries()) {
      expect(col.counts, `col${index} 每类节点各 1 个`).toEqual({ main: 1, vat: 1, prefix: 1, value: 1 });
    }
    expect(m.currencies).toEqual(['CNY', 'USDT']);
    expect(m.lines, '4 段文本逐字等于 AC-8 实算值').toEqual(LONG_LINES);

    // ── 数值内部不断行 ──
    for (const [index, col] of m.cols.entries()) {
      expect(col.mainLines, `col${index} 第一行必须单行（实际 ${col.mainLines} 行）`).toBe(1);
      expect(col.valueLines, `col${index} (VAT) 金额必须单行（实际 ${col.valueLines} 行）`).toBe(1);
    }

    // ── (VAT) 与金额同行且在前 ──
    for (const [index, col] of m.cols.entries()) {
      expect(col.prefix.right, `col${index} (VAT) 前缀在金额之前`).toBeLessThanOrEqual(col.value.x + 0.5);
      expect(verticalOverlap(col.prefix, col.value), `col${index} (VAT) 前缀与金额垂直区间相交（同行）`).toBe(true);
      expect(col.vatRow.y, `col${index} 第二行在第一行下方`).toBeGreaterThanOrEqual(col.main.bottom - 0.5);
    }

    // ── 都在卡片内 ──
    const segments = m.cols.flatMap((col, index) => [
      { name: `col${index}.main`, box: col.main },
      { name: `col${index}.prefix`, box: col.prefix },
      { name: `col${index}.value`, box: col.value },
    ]);
    for (const { name, box } of segments) {
      expect(box.width > 0 && box.height > 0, `${name} 必须有非空盒子`).toBe(true);
    }
    // AC-8 口径（test.md §1，主线 2026-10-30 依定稿原型裁定）：「在卡片内」判每一行的滚动容器
    // （.qt-subtotal-costing-main / .qt-subtotal-costing-vat）；行内金额节点可超出容器，但该行必须真的可横向滚动。
    for (const [index, col] of m.cols.entries()) {
      for (const row of [
        { name: `col${index}.main 行`, box: col.main, scroll: col.mainScroll, contentRight: col.main.right },
        { name: `col${index}.vat 行`, box: col.vatRow, scroll: col.vatScroll, contentRight: Math.max(col.prefix.right, col.value.right) },
      ]) {
        expect(row.box.x, `${row.name}：行容器左缘在卡片内`).toBeGreaterThanOrEqual(m.card.x - 0.5);
        expect(row.box.right, `${row.name}：行容器右缘在卡片内`).toBeLessThanOrEqual(m.card.right + 0.5);
        const overflowing = row.scroll.scrollWidth > row.scroll.clientWidth || row.contentRight > row.box.right + 0.5;
        console.info(`[T261030_COST_][T-03 ${shell.name} ${viewport.name}] ${row.name} scroll=${row.scroll.scrollWidth}/${row.scroll.clientWidth} overflowX=${row.scroll.overflowX} overflowing=${overflowing}`);
        if (overflowing) {
          expect(row.scroll.scrollWidth, `${row.name}：内容超出时该行必须可横向滚动（scrollWidth > clientWidth）`)
            .toBeGreaterThan(row.scroll.clientWidth);
          expect(['auto', 'scroll'], `${row.name}：内容超出时该行 overflow-x 必须允许滚动（不是被 hidden 截断）`)
            .toContain(row.scroll.overflowX);
        }
      }
      expect(col.prefix.x, `col${index}.prefix 左缘在卡片内`).toBeGreaterThanOrEqual(m.card.x - 0.5);
      expect(col.prefix.right, `col${index}.prefix 右缘在卡片内`).toBeLessThanOrEqual(m.card.right + 0.5);
    }

    // ── 互不覆盖 ──
    for (let i = 0; i < segments.length; i += 1) {
      for (let j = i + 1; j < segments.length; j += 1) {
        expect(intersectArea(segments[i].box, segments[j].box), `${segments[i].name} 与 ${segments[j].name} 不得覆盖`)
          .toBeLessThanOrEqual(1);
      }
    }

    // ── 整列换行，不半列拆开；CNY 在左 / 在上 ──
    const [cnyCol, fxCol] = m.cols.map((col) => union(col.main, col.vatRow));
    const sideBySide = verticalOverlap(cnyCol, fxCol) && cnyCol.right <= fxCol.x + 0.5;
    const stacked = fxCol.y >= cnyCol.bottom - 0.5;
    console.info(`[T261030_COST_][T-03 ${viewport.name}] layout=${sideBySide ? 'side-by-side' : stacked ? 'stacked' : 'SPLIT'}`);
    expect(sideBySide || stacked, '两列要么并排（CNY 在左），要么报价币种整列在 CNY 列下方；不得半列拆开').toBe(true);
    if (viewport.mustStack) {
      expect(stacked, '390 宽放不下两列并排：USDT 列必须整列位于 CNY 列下方').toBe(true);
    }

    // ── 右对齐 ──
    for (const [index, col] of m.cols.entries()) {
      expect(Math.abs(col.main.right - col.vatRow.right), `col${index} 两行右缘对齐`).toBeLessThanOrEqual(1.5);
    }
    if (stacked) {
      expect(Math.abs(cnyCol.right - fxCol.right), '上下两列右缘对齐').toBeLessThanOrEqual(1.5);
    }
    const rightGap = m.content.right - Math.max(cnyCol.right, fxCol.right);
    const leftGap = Math.min(cnyCol.x, fxCol.x) - m.content.left;
    expect(rightGap, `金额区贴右（rightGap=${rightGap.toFixed(1)} leftGap=${leftGap.toFixed(1)}）`)
      .toBeLessThanOrEqual(Math.max(leftGap, 0) + 1);

    // ── 页面无横向滚动 ──
    expect(m.scrollWidth, 'document.scrollWidth === document.clientWidth').toBe(m.clientWidth);
  });
}
}
