/**
 * task-261030 · 核价卡片小计展示调整 · S-FLOW 页面级用例（T-05 ~ T-09）
 *
 * AC 原文：`dev-docs/task-260801-公式计算精度优化/task-261030-核价卡片小计展示调整/需求文档.md` ③。
 *   AC-1 默认布局与精确值 · AC-3 报价币种 CNY / 空 · AC-4 没有核价数据 · AC-6 编辑/详情连续一致 · AC-7 其他入口与保存无回归
 *   AC-9 的「每个目标页面唯一命中 1 张目标卡片，控制台 error/warn 为 0，无框架错误遮罩」由本文件每条用例的前后置断言承担。
 *
 * 运行（主线解锁后再定执行方式）：
 *   candidate： npx playwright test -c e2e/task261030-costing-subtotal-flow.config.ts
 *   baseline ： T261030_TARGET=baseline T261030_VITE_ROOT=<master 检出的 cpq-frontend> npx playwright test -c e2e/task261030-costing-subtotal-flow.config.ts
 *   A/B 用例（T-05b / T-09②③④）在 candidate 侧读取 baseline 侧写下的记录；缺记录 = 硬失败（不许静默通过）。
 *
 * 全程只读：`/api/**` 由 page.route 用 `task261030-costing-subtotal.fixtures.ts` 应答，不连后端、不连任何库。
 *
 * ⚠️ backend-audit §5-1（推断、未运行验证）：保存草稿响应 SaveDraftResponse 不含 currency / exchangeRate，前端整体替换后
 *    可能退回 CNY / 1。本文件的 T-08 序列不触发保存（「下一步」无改动不发请求）；T-09④ 只断言请求体，不断言保存后的渲染。
 *    该现象若要验，需主线另行裁决是否纳入（它不是本任务 AC）。
 */
import { expect, test, type Locator, type Page } from '@playwright/test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  IDS,
  PART_NO,
  QUOTATION_ID_BY_VARIANT,
  installApiMocks,
  type MockLedger,
} from './task261030-costing-subtotal.fixtures';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const TASK_DIR = path.resolve(
  HERE, '..', '..', 'dev-docs', 'task-260801-公式计算精度优化', 'task-261030-核价卡片小计展示调整',
);
const TARGET = process.env.T261030_TARGET === 'baseline' ? 'baseline' : 'candidate';
const IS_BASELINE = TARGET === 'baseline';
const BASE_URL = process.env.T261030_BASE_URL ?? '';
const RUN_STAMP = new Date().toISOString().replace(/[:.]/g, '-');
const EVIDENCE_DIR = path.join(
  process.env.T261030_EVIDENCE_DIR?.trim() || path.join(TASK_DIR, '证据'),
  `S-FLOW-${TARGET}-${RUN_STAMP}`,
);
const AB_DIR = process.env.T261030_AB_DIR?.trim() || path.join(os.tmpdir(), 'task261030-costing-subtotal-ab');
const AB_BASELINE_FILE = path.join(AB_DIR, 'baseline.json');
const AB_CANDIDATE_FILE = path.join(AB_DIR, 'candidate.json');

// ── AC 原文期望值 ──
const AC1_LINES = [
  ['1717.135540717 CNY', '(VAT) 1940.36316101 CNY'],
  ['241.055611185 USD', '(VAT) 272.392840639 USD'],
];
const AC3_LINES = [['1717.135540717 CNY', '(VAT) 1940.36316101 CNY']];
const EMPTY_LINES = [['-', '(VAT) -']];
const QUOTE_LINES_USD = ['888.123456789 USD', '(VAT) 1003.579506172 USD'];
const WHITE = 'rgb(255, 255, 255)';

// ───────────────────────────── 通用前后置 ─────────────────────────────

const consoleProblems = new WeakMap<Page, string[]>();
const ledgers = new WeakMap<Page, MockLedger>();

// 不用 serial：用例相互独立（各自装拦截、各自导航），一条失败不得让其余用例不执行；workers=1 已保证顺序执行。

test.beforeAll(async ({ browser }) => {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  fs.mkdirSync(AB_DIR, { recursive: true });
  expect(BASE_URL, 'config 必须提供 T261030_BASE_URL').toMatch(/^http:\/\/127\.0\.0\.1:\d+$/);

  // 探活验明正身：同一端口上必须是期望的那份前端代码（candidate 有新组件文件，master 没有）。
  const context = await browser.newContext();
  try {
    const probe = await context.request.get(`${BASE_URL}/src/pages/quotation/CostingSubtotalSummary.tsx`, {
      headers: { accept: '*/*' },
    });
    const body = await probe.text();
    const isModule = probe.status() === 200 && !/^\s*<!doctype html/i.test(body);
    console.info(`[T261030_COST_][identity] target=${TARGET} status=${probe.status()} module=${isModule}`);
    expect(isModule, IS_BASELINE
      ? '基线端口上居然有 CostingSubtotalSummary.tsx：服务的不是 master'
      : '候选端口上没有 CostingSubtotalSummary.tsx：服务的不是本 worktree').toBe(!IS_BASELINE);

    // testing.md §4.2.6：新起的 Vite 首次打开页面要按需转换全部模块，先预热一次再开跑。
    const page = await context.newPage();
    await installApiMocks(page);
    await page.goto(`${BASE_URL}/quotations/${IDS.quotationUsd}`, { timeout: 180_000 });
    await page.locator('.qt-product-card').first().waitFor({ state: 'visible', timeout: 180_000 }).catch(() => {
      console.warn('[T261030_COST_][warm-up] 预热未等到产品卡片（夹具未就绪时属预期，用例本体会给出明确失败）');
    });
  } finally {
    await context.close();
  }
});

test.beforeEach(async ({ page }) => {
  const problems: string[] = [];
  consoleProblems.set(page, problems);
  page.on('console', (message) => {
    if (message.type() === 'error' || message.type() === 'warning') problems.push(`${message.type()}: ${message.text()}`);
  });
  page.on('pageerror', (error) => problems.push(`pageerror: ${error.message}`));
  ledgers.set(page, await installApiMocks(page));
});

test.afterEach(async ({ page }, testInfo) => {
  const ledger = ledgers.get(page)!;
  const problems = consoleProblems.get(page) ?? [];
  fs.writeFileSync(
    path.join(EVIDENCE_DIR, `${testInfo.title.replace(/[^\w一-龥-]+/g, '_').slice(0, 80)}.ledger.json`),
    JSON.stringify({ hits: ledger.hits, unhandled: ledger.unhandled, console: problems }, null, 2),
    'utf8',
  );
  expect(ledger.unhandled, '夹具不完整：有接口没被夹具应答（见 TODO(backend-audit)）').toEqual([]);
  if (!IS_BASELINE) {
    expect(problems, 'AC-9：目标页面控制台 error/warn 必须为 0').toEqual([]);
  } else if (problems.length) {
    console.info(`[T261030_COST_][baseline console] ${JSON.stringify(problems)}`);
  }
});

// ───────────────────────────── 页面动作 ─────────────────────────────

async function assertNoOverlay(page: Page, label: string): Promise<void> {
  await expect(page.locator('vite-error-overlay'), `${label}: 无 Vite 错误遮罩`).toHaveCount(0);
  await expect(
    page.getByText(/Unexpected Application Error|Internal Server Error|Failed to compile/i),
    `${label}: 无框架错误遮罩`,
  ).toHaveCount(0);
}

/** AC-6：「加载中」、红色错误提示、框架错误遮罩最终均为 0。`allowErrorAlert` 仅给 AC-4 生成失败卡片用。 */
async function assertSettled(page: Page, label: string, opts: { allowErrorAlert?: boolean } = {}): Promise<void> {
  await expect(page.getByText('加载中', { exact: false }), `${label}: 「加载中」最终为 0`).toHaveCount(0, { timeout: 30_000 });
  await expect(
    page.locator('.ant-spin-spinning:visible, .ant-btn-loading:visible'),
    `${label}: 可见加载指示最终为 0`,
  ).toHaveCount(0, { timeout: 30_000 });
  if (!opts.allowErrorAlert) {
    await expect(page.locator('.ant-alert-error:visible, .ant-message-error:visible'), `${label}: 无红色错误提示`).toHaveCount(0);
  }
  await assertNoOverlay(page, label);
}

function assertHit(page: Page, key: string): void {
  const hits = ledgers.get(page)!.hits[key] ?? 0;
  expect(hits, `阳性对照：夹具接口 ${key} 必须被命中（否则渲染的不是夹具数据）`).toBeGreaterThan(0);
}

async function openEditStep2(page: Page, quotationId: string): Promise<void> {
  await page.goto(`/quotations/${quotationId}/edit`);
  await page.waitForLoadState('networkidle');
  await gotoStep2IfNeeded(page);
  assertHit(page, 'D-1/E-1 quotation.get');
}

async function gotoStep2IfNeeded(page: Page): Promise<void> {
  const cards = page.locator('.qt-product-card');
  if (!(await cards.first().isVisible().catch(() => false))) {
    const next = page.getByRole('button', { name: /下一步/ }).first();
    await expect(next, '第 1 步必须有可用的「下一步」').toBeEnabled();
    await next.click();
  }
  await expect(cards.first(), '第 2 步必须渲染出产品卡片').toBeVisible({ timeout: 30_000 });
}

async function openDetail(page: Page, quotationId: string): Promise<void> {
  await page.goto(`/quotations/${quotationId}`);
  await page.waitForLoadState('networkidle');
  assertHit(page, 'D-1/E-1 quotation.get');
}

async function openCostingReview(page: Page): Promise<void> {
  await page.goto(`/costing-orders/${IDS.costingOrder}/review`);
  await page.waitForLoadState('networkidle');
  assertHit(page, 'C-1 costingOrder.get');
}

/** 顶部视图切换（`.ant-segmented-item`，E2E测试方法 §2.3）。TODO(selector)：「产品卡片」「Excel」「比对」的确切文案待首跑核对。 */
async function chooseSegment(page: Page, text: RegExp | string, label: string): Promise<void> {
  const item = page.locator('.ant-segmented-item').filter({ hasText: text }).first();
  await expect(item, `${label}: 视图切换项必须存在`).toBeVisible();
  await item.click();
  await page.waitForLoadState('networkidle');
}

async function showCards(page: Page, side: '核价单' | '报价单'): Promise<void> {
  await chooseSegment(page, side, `切到「${side}」`);
  const cardsItem = page.locator('.ant-segmented-item').filter({ hasText: '产品卡片' }).first();
  if (await cardsItem.isVisible().catch(() => false)) {
    await cardsItem.click();
    await page.waitForLoadState('networkidle');
  }
  await expect(page.locator('.qt-product-card').first(), `「${side} · 产品卡片」必须有卡片`).toBeVisible({ timeout: 30_000 });
}

/** AC-9：每个目标页面唯一命中 1 张目标卡片。 */
async function targetCard(page: Page): Promise<Locator> {
  const all = page.locator('.qt-product-card');
  expect(await all.count(), '页面卡片数必须非 0').toBeGreaterThan(0);
  const card = all.filter({ hasText: PART_NO });
  await expect(card, `目标卡片（${PART_NO}）必须唯一命中 1 张`).toHaveCount(1);
  await expect(card).toBeVisible();
  return card;
}

interface CostingAreaView {
  rootCount: number;
  colCount: number;
  currencies: string[];
  empties: string[];
  lines: string[][];
  rootText: string;
  rootHtml: string;
  colors: Array<{ where: string; color: string; opacity: string }>;
  colBoxes: Array<{ x: number; y: number; right: number; bottom: number }>;
}

async function readCostingArea(card: Locator): Promise<CostingAreaView> {
  return card.evaluate((cardEl) => {
    const norm = (text: string | null | undefined) => (text ?? '').replace(/\s+/g, ' ').trim();
    const roots = cardEl.querySelectorAll('.qt-subtotal-costing');
    const root = roots[0];
    const cols = Array.from(root?.querySelectorAll('.qt-subtotal-costing-col') ?? []);
    const colors: Array<{ where: string; color: string; opacity: string }> = [];
    cols.forEach((col, index) => {
      // 4 段文字 + (VAT) 前缀，以及其下所有带文字的子孙节点（币种若单独包一层也算在内）。
      const targets = [
        { where: `col${index}.main`, el: col.querySelector('.qt-subtotal-costing-main') },
        { where: `col${index}.vat`, el: col.querySelector('.qt-subtotal-costing-vat') },
        { where: `col${index}.prefix`, el: col.querySelector('.qt-subtotal-vat-prefix') },
        { where: `col${index}.value`, el: col.querySelector('.qt-subtotal-vat-value') },
      ];
      for (const { where, el } of targets) {
        if (!el) { colors.push({ where, color: '<missing>', opacity: '' }); continue; }
        const nodes = [el, ...Array.from(el.querySelectorAll('*'))].filter((node) => norm(node.textContent).length > 0);
        nodes.forEach((node, n) => {
          const style = getComputedStyle(node);
          colors.push({ where: n === 0 ? where : `${where} > ${node.tagName.toLowerCase()}[${n}]`, color: style.color, opacity: style.opacity });
        });
      }
    });
    return {
      rootCount: roots.length,
      colCount: cols.length,
      currencies: cols.map((col) => col.getAttribute('data-currency') ?? '<missing>'),
      empties: cols.map((col) => col.getAttribute('data-empty') ?? '<missing>'),
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
      rootText: root?.textContent ?? '',
      rootHtml: root?.innerHTML ?? '',
      colors,
      colBoxes: cols.map((col) => {
        const rect = col.getBoundingClientRect();
        return { x: rect.x, y: rect.y, right: rect.right, bottom: rect.bottom };
      }),
    };
  });
}

/** 先断言金额区存在、列数符合预期，再返回（防空集合假绿），并打印实际值。 */
async function costingArea(card: Locator, expectedCols: number, label: string): Promise<CostingAreaView> {
  const view = await readCostingArea(card);
  console.info(`[T261030_COST_][${label}] ${JSON.stringify({ cols: view.colCount, currencies: view.currencies, lines: view.lines })}`);
  expect(view.rootCount, `${label}: 核价卡片 .qt-subtotal-costing 恰好 1 个`).toBe(1);
  expect(view.colCount, `${label}: 金额区列数`).toBe(expectedCols);
  return view;
}

function assertAreaExcludes(view: CostingAreaView, forbidden: string[], label: string): void {
  for (const text of forbidden) {
    expect(view.rootText.includes(text) || view.rootHtml.includes(text), `${label}: 金额区不得含「${text}」`).toBe(false);
  }
}

/** task-261028 报价侧金额区两行。 */
async function quoteLines(card: Locator, label: string): Promise<string[]> {
  const lines = card.locator('.qt-subtotal-quote-lines .qt-subtotal-line');
  await expect(lines, `${label}: 报价侧金额区恰好两行`).toHaveCount(2);
  const texts = (await lines.allInnerTexts()).map((text) => text.replace(/\s+/g, ' ').trim());
  console.info(`[T261030_COST_][${label}] quote lines=${JSON.stringify(texts)}`);
  return texts;
}

async function shot(page: Page, name: string): Promise<void> {
  await page.screenshot({ path: path.join(EVIDENCE_DIR, `${name}.png`), fullPage: true });
}

/** 卡片「头部 + 页签 + 表格」文本（去掉底部金额区），用于 AC-1「与改造前一致」的 A/B。 */
async function cardChromeText(card: Locator): Promise<string> {
  return card.evaluate((cardEl) => {
    const clone = cardEl.cloneNode(true) as Element;
    clone.querySelectorAll('.qt-subtotal-costing, .qt-subtotal-bar, .qt-subtotal-bar-multi').forEach((node) => node.remove());
    return (clone.textContent ?? '').replace(/\s+/g, ' ').trim();
  });
}

// ───────────────────────────── A/B 记录 ─────────────────────────────

type AbRecord = Record<string, unknown>;

function readAb(file: string): AbRecord {
  return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) as AbRecord : {};
}

function recordAb(key: string, value: unknown): void {
  const file = IS_BASELINE ? AB_BASELINE_FILE : AB_CANDIDATE_FILE;
  const current = readAb(file);
  current[key] = value;
  fs.writeFileSync(file, JSON.stringify(current, null, 2), 'utf8');
  fs.writeFileSync(path.join(EVIDENCE_DIR, `ab-${TARGET}.json`), JSON.stringify(current, null, 2), 'utf8');
}

/** candidate 侧：与 master 同夹具同操作的记录逐字比较；缺基线 = 硬失败。 */
function compareWithBaseline(key: string, candidateValue: unknown): void {
  recordAb(key, candidateValue);
  if (IS_BASELINE) return;
  const baseline = readAb(AB_BASELINE_FILE);
  expect(Object.hasOwn(baseline, key), `A/B 基线缺「${key}」：先以 T261030_TARGET=baseline 在 master 上跑（${AB_BASELINE_FILE}）`).toBe(true);
  expect(candidateValue, `A/B「${key}」：分支与 master 同夹具同操作结果必须逐字相同`).toEqual(baseline[key]);
}

function keyPaths(value: unknown, trail = '$'): string[] {
  if (Array.isArray(value)) return [...new Set(value.flatMap((item) => keyPaths(item, `${trail}[]`)))];
  if (!value || typeof value !== 'object') return [];
  return Object.entries(value as Record<string, unknown>).flatMap(([key, child]) => [`${trail}.${key}`, ...keyPaths(child, `${trail}.${key}`)]);
}

// ───────────────────────────── 夹具自证（仅 baseline / master） ─────────────────────────────

test.describe('夹具自证（baseline 模式在 master 上跑）', () => {
  test.skip(!IS_BASELINE, '仅 baseline 模式运行');

  test('CAL-1 夹具 C 在 master 上算出核价 1717.135540717、报价 888.123456789 USD 两行', async ({ page }) => {
    await openEditStep2(page, IDS.quotationUsd);
    await showCards(page, '报价单');
    expect(await quoteLines(await targetCard(page), 'CAL-1 quote')).toEqual(QUOTE_LINES_USD);
    await showCards(page, '核价单');
    const footer = (await (await targetCard(page)).innerText()).replace(/\s+/g, ' ');
    console.info(`[T261030_COST_][CAL-1 master costing card] ${footer}`);
    expect(footer, 'master 旧核价金额区必须显示夹具核价小计（否则夹具没算出 1717.135540717）').toContain('1717.135540717');
    await shot(page, 'CAL-1-master-edit-costing');
  });

  test('CAL-2 无核价结构 / 生成失败夹具在 master 详情页复现原缺陷（核价卡片显示了金额而非 -）', async ({ page }) => {
    for (const variant of ['NO_COSTING_STRUCTURE', 'COSTING_FAILED'] as const) {
      await openDetail(page, QUOTATION_ID_BY_VARIANT[variant]);
      await showCards(page, '核价单');
      const text = (await (await targetCard(page)).innerText()).replace(/\s+/g, ' ');
      console.info(`[T261030_COST_][CAL-2 ${variant}] ${text}`);
      expect(text, `${variant}: 卡片主体必须是对应的无数据形态`)
        .toContain(variant === 'NO_COSTING_STRUCTURE' ? '暂无组件数据' : '核价数据生成失败');
      if (variant === 'NO_COSTING_STRUCTURE') {
        // ① 无核价结构：master 退回读报价侧小计（需求文档 ① 原缺陷）。
        expect(text, `${variant}: master 上必须复现「显示报价侧金额」（证明 T-07 有能力打红）`).toContain('888.123456789');
      } else {
        // ② 生成失败：主线 2026-10-30 更正——master 实际显示 `产品小计 ¥ 0 外币小计 0 USD`；
        //    证伪预期改为「金额区出现 ¥ 或任何币种金额」（显示了非 - 的金额），T-07② 不变。
        const amountShown = /[¥￥]\s*-?\d|\d[\d.]*\s*(?:CNY|USD)\b|\b(?:CNY|USD)\s*-?\d/.test(text);
        expect(amountShown, `${variant}: master 上金额区必须出现 ¥ 或带币种的金额（证明 T-07② 有能力打红），实际：${text}`)
          .toBe(true);
      }
      await shot(page, `CAL-2-master-${variant}`);
    }
  });
});

// ───────────────────────────── T-05 / AC-1 ─────────────────────────────

test('T-05 / AC-1 编辑页「核价单 · 产品卡片」默认布局、精确值与白色字', async ({ page }) => {
  test.skip(IS_BASELINE, 'master 上没有新金额区');
  await openEditStep2(page, IDS.quotationUsd);
  await showCards(page, '核价单');
  await assertSettled(page, 'T-05');
  const card = await targetCard(page);
  const view = await costingArea(card, 2, 'T-05');

  expect(view.currencies, 'DOM 中 CNY 列在前、USD 列在后').toEqual(['CNY', 'USD']);
  expect(view.lines, 'AC-1 四段文本逐字').toEqual(AC1_LINES);

  const [cny, usd] = view.colBoxes;
  expect(Math.max(cny.y, usd.y) < Math.min(cny.bottom, usd.bottom), '1280 宽下两列处于同一水平带').toBe(true);
  expect(cny.right, 'CNY 列在左').toBeLessThanOrEqual(usd.x + 0.5);

  console.info(`[T261030_COST_][T-05 colors] ${JSON.stringify(view.colors)}`);
  expect(view.colors.length, '颜色采样必须非空').toBeGreaterThanOrEqual(8);
  expect(view.colors.filter((sample) => sample.color !== WHITE), '4 段文字与 (VAT) 前缀计算字色均为白色（外币列不是蓝色）')
    .toEqual([]);

  assertAreaExcludes(view, ['产品小计', '外币小计', '¥', '￥', '888.123456789'], 'T-05');
  await shot(page, 'T-05-edit-costing-1280');
});

test('T-05b / AC-1 卡片头部、页签、表格与改造前一致（A/B）', async ({ page }) => {
  await openEditStep2(page, IDS.quotationUsd);
  await showCards(page, '核价单');
  const editChrome = await cardChromeText(await targetCard(page));
  await openDetail(page, IDS.quotationUsd);
  await showCards(page, '核价单');
  const detailChrome = await cardChromeText(await targetCard(page));
  expect(editChrome.length > 0 && detailChrome.length > 0, '卡片头部/页签/表格文本必须非空').toBe(true);
  compareWithBaseline('T-05b.costingCardChrome', { edit: editChrome, detail: detailChrome });
});

// ───────────────────────────── T-06 / AC-3 ─────────────────────────────

test('T-06 / AC-3 报价币种 CNY（编辑页 + 详情页）与币种为空（详情页）只显示 CNY 一列', async ({ page }) => {
  test.skip(IS_BASELINE, 'master 上没有新金额区');
  const cases = [
    { label: 'T-06 CNY edit', open: () => openEditStep2(page, IDS.quotationCny) },
    { label: 'T-06 CNY detail', open: () => openDetail(page, IDS.quotationCny) },
    { label: 'T-06 blank-currency detail', open: () => openDetail(page, IDS.quotationBlankCurrency) },
  ];
  for (const { label, open } of cases) {
    await open();
    await showCards(page, '核价单');
    await assertSettled(page, label);
    const view = await costingArea(await targetCard(page), 1, label);
    expect(view.currencies, `${label}: 唯一一列为 CNY`).toEqual(['CNY']);
    expect(view.lines, `${label}: 文本`).toEqual(AC3_LINES);
    expect((view.rootText.match(/USD/g) ?? []).length, `${label}: 金额区内 USD 出现 0 次`).toBe(0);
    await shot(page, label.replace(/\s+/g, '-'));
  }
});

// ───────────────────────────── T-07 / AC-4 ─────────────────────────────

test('T-07 / AC-4 详情页没有核价数据（无核价结构 / 生成失败）金额区只显示 - / (VAT) -，报价侧照常', async ({ page }) => {
  test.skip(IS_BASELINE, 'master 上由 CAL-2 复现原缺陷');
  const cases = [
    { variant: 'NO_COSTING_STRUCTURE' as const, bodyText: '暂无组件数据', allowErrorAlert: false },
    { variant: 'COSTING_FAILED' as const, bodyText: '核价数据生成失败', allowErrorAlert: true },
  ];
  for (const { variant, bodyText, allowErrorAlert } of cases) {
    const label = `T-07 ${variant}`;
    await openDetail(page, QUOTATION_ID_BY_VARIANT[variant]);
    await showCards(page, '核价单');
    await assertSettled(page, label, { allowErrorAlert });
    const card = await targetCard(page);
    await expect(card.getByText(bodyText, { exact: false }), `${label}: 卡片主体先出现「${bodyText}」`).toHaveCount(1);

    const view = await costingArea(card, 1, label);
    expect(view.empties, `${label}: 无数据列 data-empty="true"`).toEqual(['true']);
    expect(view.currencies, `${label}: 无数据列 data-currency=""`).toEqual(['']);
    expect(view.lines, `${label}: - / (VAT) -`).toEqual(EMPTY_LINES);
    assertAreaExcludes(view, ['888.123456789', '1717.135540717', 'CNY', 'USD', '¥', '￥'], label);
    await shot(page, `T-07-${variant}-costing`);

    await showCards(page, '报价单');
    await assertSettled(page, `${label} quote`);
    const quoteCard = await targetCard(page);
    expect(await quoteLines(quoteCard, `${label} quote`), `${label}: 同一张单报价侧仍是 888.123456789 USD 及其 (VAT) 行`)
      .toEqual(QUOTE_LINES_USD);
    await shot(page, `T-07-${variant}-quote`);
  }
});

// ───────────────────────────── T-08 / AC-6 ─────────────────────────────

test('T-08 / AC-6 编辑 → 报价单 → 核价单 → 详情 → 编辑刷新，三次记录逐字等于 AC-1', async ({ page }) => {
  test.skip(IS_BASELINE, 'master 上没有新金额区');
  const records: Record<string, string[][]> = {};

  // ① 编辑页核价卡片
  await openEditStep2(page, IDS.quotationUsd);
  await showCards(page, '核价单');
  await assertSettled(page, 'T-08 ① edit costing');
  records.editFirst = (await costingArea(await targetCard(page), 2, 'T-08 ①')).lines;

  // 中途：报价单视图是 task-261028 的两行
  await showCards(page, '报价单');
  await assertSettled(page, 'T-08 quote');
  const quoteCard = await targetCard(page);
  expect(await quoteLines(quoteCard, 'T-08 quote'), '中途「报价单」视图为 task-261028 的两行').toEqual(QUOTE_LINES_USD);
  expect(await quoteCard.locator('.qt-subtotal-costing').count(), '报价卡片不得出现核价金额区').toBe(0);

  // 切回核价单（中间态，额外记录）
  await showCards(page, '核价单');
  await assertSettled(page, 'T-08 back to costing');
  records.editBack = (await costingArea(await targetCard(page), 2, 'T-08 back')).lines;

  // ② 详情页核价卡片
  await openDetail(page, IDS.quotationUsd);
  await showCards(page, '核价单');
  await assertSettled(page, 'T-08 ② detail costing');
  records.detail = (await costingArea(await targetCard(page), 2, 'T-08 ②')).lines;

  // ③ 回编辑页并刷新
  await openEditStep2(page, IDS.quotationUsd);
  await page.reload();
  await page.waitForLoadState('networkidle');
  await gotoStep2IfNeeded(page);
  await showCards(page, '核价单');
  await assertSettled(page, 'T-08 ③ edit after reload');
  records.editAfterReload = (await costingArea(await targetCard(page), 2, 'T-08 ③')).lines;
  await shot(page, 'T-08-edit-after-reload');

  fs.writeFileSync(path.join(EVIDENCE_DIR, 'T-08-records.json'), JSON.stringify(records, null, 2), 'utf8');
  expect(records, '三次记录（及切回中间态）都与 AC-1 逐字相同、顺序不变').toEqual({
    editFirst: AC1_LINES,
    editBack: AC1_LINES,
    detail: AC1_LINES,
    editAfterReload: AC1_LINES,
  });
});

// ───────────────────────────── T-09 / AC-7 ─────────────────────────────

test('T-09 ① / AC-7 报价侧编辑/详情卡片仍是 task-261028 两行，且无核价金额区与第二列', async ({ page }) => {
  for (const [label, open] of [
    ['T-09① edit', () => openEditStep2(page, IDS.quotationUsd)],
    ['T-09① detail', () => openDetail(page, IDS.quotationUsd)],
  ] as const) {
    await open();
    await showCards(page, '报价单');
    await assertSettled(page, label);
    const card = await targetCard(page);
    expect(await quoteLines(card, label), `${label}: 报价侧两行`).toEqual(QUOTE_LINES_USD);
    expect(await card.locator('.qt-subtotal-costing').count(), `${label}: .qt-subtotal-costing 计数 0`).toBe(0);
    expect(await card.locator('.qt-subtotal-costing-col').count(), `${label}: 不出现第二列`).toBe(0);
  }
});

test('T-09 ② / AC-7 核价管理页：核价金额区同 AC-1；「核价单据总价」一行与改造前逐字相同（A/B）', async ({ page }) => {
  await openCostingReview(page);
  await expect(page.locator('.qt-product-card').first(), '核价管理页必须渲染出产品卡片').toBeVisible({ timeout: 30_000 });
  // fixture-recipe §5.1：核价管理页进页默认停在「报价单」分段，「核价单据总价」只在「核价单 · 产品卡片」下渲染。
  await showCards(page, '核价单');
  await assertSettled(page, 'T-09②');
  if (!IS_BASELINE) {
    const view = await costingArea(await targetCard(page), 2, 'T-09② review');
    expect(view.currencies).toEqual(['CNY', 'USD']);
    expect(view.lines, '核价管理页金额区与 AC-1 相同').toEqual(AC1_LINES);
    assertAreaExcludes(view, ['产品小计', '外币小计', '¥', '￥', '888.123456789'], 'T-09② review');
  }
  // fixture-recipe §5.1：标签为「核价单据总价：」（全角冒号），金额在嵌套 <strong> 里 ⇒ 正则匹配，取父节点文本。
  const totalLabel = page.getByText(/核价单据总价/).first();
  await expect(totalLabel, '「核价单据总价」一行必须存在').toBeVisible();
  const totalRow = await totalLabel.evaluate((el) => (el.parentElement?.textContent ?? el.textContent ?? '').replace(/\s+/g, ' ').trim());
  console.info(`[T261030_COST_][T-09② total row] ${totalRow}`);
  expect(totalRow.length, '「核价单据总价」行文本非空').toBeGreaterThan('核价单据总价'.length);
  await shot(page, 'T-09-2-costing-review');
  compareWithBaseline('T-09②.costingTotalRow', totalRow);
});

/** 详情页视图内容文本。TODO(selector)：视图内容区容器待首跑核对（先取整个布局内容区，两侧同口径）。 */
async function viewRegionText(page: Page): Promise<string> {
  const region = page.locator('.ant-layout-content').first();
  await expect(region, '视图内容区必须存在').toBeVisible();
  return (await region.innerText()).replace(/\s+/g, ' ').trim();
}

test('T-09 ③a / AC-7 详情页核价 Excel 视图的金额文本与改造前相同（A/B）', async ({ page }) => {
  await openDetail(page, IDS.quotationUsd);
  await chooseSegment(page, '核价单', 'T-09③a 核价');
  await chooseSegment(page, /Excel/, 'T-09③a Excel'); // TODO(selector)
  await assertSettled(page, 'T-09③a costing excel');
  const excelText = await viewRegionText(page);
  expect(excelText.length, '核价 Excel 视图文本非空').toBeGreaterThan(0);
  console.info(`[T261030_COST_][T-09③a] excel=${excelText.length} chars`);
  await shot(page, 'T-09-3a-costing-excel');
  compareWithBaseline('T-09③a.costingExcelText', excelText);
});

test('T-09 ③b / AC-7 详情页比对视图的金额文本与改造前相同（A/B）', async ({ page }) => {
  // 比对视图三个接口按 backend-audit §6+.3 样本应答；阳性对照：三者都必须被命中。
  await openDetail(page, IDS.quotationUsd);
  await chooseSegment(page, /比对/, 'T-09③b 比对'); // TODO(selector)
  await assertSettled(page, 'T-09③b comparison');
  for (const key of ['D-8 comparison.meta', 'D-8 comparison.data', 'D-8 comparison.config']) assertHit(page, key);
  const comparisonText = await viewRegionText(page);
  expect(comparisonText.length, '比对视图文本非空').toBeGreaterThan(0);
  console.info(`[T261030_COST_][T-09③b] comparison=${comparisonText.length} chars`);
  await shot(page, 'T-09-3b-comparison');
  compareWithBaseline('T-09③b.comparisonText', comparisonText);
});

test('T-09 ④ / AC-7 只改「关联项目」后保存：请求体键集合与改造前相同，不含含税/外币派生字段（A/B）', async ({ page }) => {
  const ledger = ledgers.get(page)!;
  await page.goto(`/quotations/${IDS.quotationUsd}/edit`);
  await page.waitForLoadState('networkidle');
  assertHit(page, 'D-1/E-1 quotation.get');
  const input = page.getByPlaceholder('关联项目', { exact: true }).first();
  await expect(input, '「关联项目」输入框必须可见').toBeVisible();
  expect(ledger.draftBodies.length, '改动前不应已有保存请求').toBe(0);
  await input.fill(`${PART_NO}_PROJECT_SAVE`);

  // backend-audit §2.2：「下一步」会先 handleSaveDraft(true)（有改动才发 PUT）。第 1 步若有「保存草稿」按钮就点它，
  // 否则以「下一步」触发保存；两侧（master / 分支）走同一个动作，记录下来供 A/B 核对。
  const saveButton = page.getByRole('button', { name: /保\s*存\s*草\s*稿$/ }).first();
  const trigger = (await saveButton.isVisible().catch(() => false)) ? 'save-button' : 'next-step';
  await (trigger === 'save-button' ? saveButton : page.getByRole('button', { name: /下一步/ }).first()).click();
  await expect.poll(() => ledger.draftBodies.length, { message: '只改「关联项目」后必须发出 1 次 PUT /draft', timeout: 20_000 })
    .toBeGreaterThan(0);
  await assertSettled(page, 'T-09④ save');
  const { json: body, text: bodyText } = ledger.draftBodies[0];
  const keys = keyPaths(body).sort();
  recordAb('T-09④.saveTrigger', trigger);
  console.info(`[T261030_COST_][T-09④] ${keys.length} key paths`);
  expect(keys.length, '保存请求体键集合必须非空').toBeGreaterThan(0);
  expect(bodyText, '一处已授权改动必须真的在请求体里').toContain(`${PART_NO}_PROJECT_SAVE`);
  fs.writeFileSync(path.join(EVIDENCE_DIR, `T-09-4-save-body-${TARGET}.json`), JSON.stringify(body, null, 2), 'utf8');

  for (const derived of ['1940.36316101', '241.055611185', '272.392840639']) {
    expect(bodyText.includes(derived), `请求体不得含渲染出来的派生值 ${derived}`).toBe(false);
  }
  expect(keys.filter((key) => /\.(?:vatInclusive|taxInclusive)[^.]*$/i.test(key)), '请求体不含 vatInclusive*/taxInclusive* 键')
    .toEqual([]);
  if (!IS_BASELINE) {
    const baseline = readAb(AB_BASELINE_FILE)['T-09④.saveKeyPaths'] as string[] | undefined;
    if (baseline) {
      const added = keys.filter((key) => !baseline.includes(key));
      expect(added.filter((key) => /\.(?:vatInclusive|taxInclusive|foreign|display)[^.]*$/i.test(key)),
        '相对 master 新增的键中不得有 vatInclusive / taxInclusive / foreign / display 前缀键').toEqual([]);
    }
  }
  compareWithBaseline('T-09④.saveKeyPaths', keys);
});

// ───────────────────────────── T-11 / AC-10 ─────────────────────────────

const CURRENCY_KEYS = ['currency', 'exchangeRate', 'exchangeRateDate', 'exchangeRateSource', 'exchangeRateCapturedAt'];

/** 读当前编辑页第 2 步的核价金额区与报价侧两行（不刷新、不重新导航）。 */
async function readEditStep2Pair(page: Page, label: string): Promise<{ costing: CostingAreaView; costingFooter: string; quote: string[] }> {
  await showCards(page, '核价单');
  await assertSettled(page, `${label} costing`);
  const costingCard = await targetCard(page);
  const costing = await readCostingArea(costingCard);
  const costingFooter = (await costingCard.innerText()).replace(/\s+/g, ' ');
  console.info(`[T261030_COST_][${label} costing] ${JSON.stringify({ cols: costing.colCount, lines: costing.lines })}`);
  await showCards(page, '报价单');
  await assertSettled(page, `${label} quote`);
  const quote = await quoteLines(await targetCard(page), `${label} quote`);
  return { costing, costingFooter, quote };
}

test('T-11 / AC-10 只改「关联项目」保存后不刷新：核价仍 2 列同 AC-1、报价首行仍为 USD；刷新后不变', async ({ page }) => {
  const ledger = ledgers.get(page)!;
  await page.goto(`/quotations/${IDS.quotationUsd}/edit`);
  await page.waitForLoadState('networkidle');
  assertHit(page, 'D-1/E-1 quotation.get');
  const input = page.getByPlaceholder('关联项目', { exact: true }).first();
  await expect(input, '「关联项目」输入框必须可见').toBeVisible();
  expect(ledger.draftBodies.length, '改动前不应已有保存请求').toBe(0);
  await input.fill(`${PART_NO}_PROJECT_AC10`);

  // 与 T-09④ 同一个触发动作（第 1 步有「保存草稿」就点它，否则「下一步」—— 后者会先 handleSaveDraft(true)）。
  const saveButton = page.getByRole('button', { name: /保\s*存\s*草\s*稿$/ }).first();
  const trigger = (await saveButton.isVisible().catch(() => false)) ? 'save-button' : 'next-step';
  const responsePromise = page.waitForResponse((response) => response.request().method() === 'PUT'
    && new RegExp(`/api/cpq/quotations/${IDS.quotationUsd}/draft(?:\\?|$)`).test(response.url()));
  await (trigger === 'save-button' ? saveButton : page.getByRole('button', { name: /下一步/ }).first()).click();
  const response = await responsePromise; // 等保存请求返回
  expect(response.status(), '保存请求必须成功').toBe(200);
  expect(ledger.draftResponses.length, '保存响应必须来自夹具（阳性对照）').toBe(1);

  // 先证明观测到的是前端沿用而非后端返回：响应体 data 里确无币种 / 汇率键。
  const responseJson = JSON.parse(ledger.draftResponses[0]) as { data?: Record<string, unknown> };
  expect(responseJson.data && typeof responseJson.data === 'object', '保存响应 data 必须是对象').toBe(true);
  expect(CURRENCY_KEYS.filter((key) => Object.hasOwn(responseJson.data!, key)), '保存响应体不含 currency / exchangeRate*')
    .toEqual([]);
  expect(ledger.draftBodies[0].text, '保存的就是这一处授权改动').toContain(`${PART_NO}_PROJECT_AC10`);

  // 不刷新，进入第 2 步
  if (trigger === 'save-button') {
    await page.getByRole('button', { name: /下一步/ }).first().click();
  }
  await expect(page.locator('.qt-product-card').first(), '第 2 步必须渲染出产品卡片').toBeVisible({ timeout: 30_000 });
  const afterSave = await readEditStep2Pair(page, 'T-11 after save');
  await shot(page, `T-11-after-save-${TARGET}`);

  // 刷新后再读
  await page.reload();
  await page.waitForLoadState('networkidle');
  await gotoStep2IfNeeded(page);
  const afterReload = await readEditStep2Pair(page, 'T-11 after reload');
  await shot(page, `T-11-after-reload-${TARGET}`);

  fs.writeFileSync(path.join(EVIDENCE_DIR, `T-11-${TARGET}.json`), JSON.stringify({
    trigger,
    saveResponse: responseJson,
    afterSave: { costingCols: afterSave.costing.colCount, costingLines: afterSave.costing.lines, costingFooter: afterSave.costingFooter, quote: afterSave.quote },
    afterReload: { costingCols: afterReload.costing.colCount, costingLines: afterReload.costing.lines, quote: afterReload.quote },
  }, null, 2), 'utf8');

  if (IS_BASELINE) {
    // 证伪：master 上同一步骤必须复现「保存后丢币种」（报价首行变成 CNY，或旧核价金额区不再出现 USD）。
    const reproduced = afterSave.quote[0] === '888.123456789 CNY' || !afterSave.costingFooter.includes('USD');
    console.info(`[T261030_COST_][T-11 baseline] quoteFirst=${afterSave.quote[0]} costingHasUSD=${afterSave.costingFooter.includes('USD')} reproduced=${reproduced}`);
    expect(reproduced, 'master 上必须复现保存后丢币种（证明 T-11 能打红）；复现不出 = 夹具或步骤没走到被测路径').toBe(true);
    expect(afterReload.quote[0], 'master 刷新后重新 getById，报价首行回到 USD（对照）').toBe('888.123456789 USD');
    return;
  }

  for (const [label, snapshot] of [['不刷新', afterSave], ['刷新后', afterReload]] as const) {
    expect(snapshot.costing.rootCount, `${label}: 核价 .qt-subtotal-costing 恰好 1 个`).toBe(1);
    expect(snapshot.costing.colCount, `${label}: 核价金额区仍为 2 列（不是 1 列 CNY）`).toBe(2);
    expect(snapshot.costing.currencies, `${label}: 列币种`).toEqual(['CNY', 'USD']);
    expect(snapshot.costing.lines, `${label}: 4 段文本与 AC-1 逐字相同`).toEqual(AC1_LINES);
    expect(snapshot.quote[0], `${label}: 报价侧首行仍为 888.123456789 USD`).toBe('888.123456789 USD');
  }
});
