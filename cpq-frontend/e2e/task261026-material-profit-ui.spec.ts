/**
 * task-261026 · S-UI（AC-7~AC-10）。
 * 判据只来自本任务文档与原型；列表/PUT 使用 Mock API，不读写共享业务数据。
 */
import { expect, test, type Locator, type Page, type Route } from '@playwright/test';
import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import {
  assertIsolatedEnv, cellByHeader, gotoProductHub, headerTexts, loginAs, switchTab,
} from './product-hub.helpers';

test.describe.configure({ mode: 'serial' });

const PREFIX = 'T261026_UI_';
const CUSTOMER_NO = `${PREFIX}CUST_A`;
const EVIDENCE_DIR = process.env.TASK261026_EVIDENCE_DIR
  ? resolve(process.env.TASK261026_EVIDENCE_DIR)
  : resolve(
    process.cwd(),
    '..',
    'dev-docs',
    'task-261026-物料利润字段',
    '证据',
    'S-UI',
    new Date().toISOString().replace(/[:.]/g, '-'),
  );

type ProfitRow = {
  axisValue: string;
  customerNo: string;
  customerName: string;
  materialName: string;
  name: string;
  category: string;
  specification: string;
  dimension: string;
  oldMaterialNo: string;
  unitWeight: string;
  productionNo: string;
  profit: string | null;
};

type PutObservation = {
  axisValue: string;
  customerNo: string | null;
  body: Record<string, unknown>;
};

type MockState = {
  rows: ProfitRow[];
  puts: PutObservation[];
  failNext: boolean;
  delayNextMs: number;
};

function fixtureRows(): ProfitRow[] {
  const base = {
    customerNo: CUSTOMER_NO,
    customerName: 'T261026 UI 客户',
    category: '默认分类',
    specification: 'Φ12',
    dimension: '12×8×3',
    oldMaterialNo: 'OLD-261026',
    unitWeight: '16.7',
  };
  return [
    { ...base, axisValue: `${PREFIX}NEG`, materialName: '负利润物料', name: '负利润物料', productionNo: 'P-NEG', profit: '-12.34' },
    { ...base, axisValue: `${PREFIX}ZERO`, materialName: '零利润物料', name: '零利润物料', productionNo: 'P-ZERO', profit: '0' },
    { ...base, axisValue: `${PREFIX}LONG`, materialName: '极值物料', name: '极值物料', productionNo: 'P-LONG', profit: '99999999999999.999999999999' },
    { ...base, axisValue: `${PREFIX}NULL`, materialName: '空利润物料', name: '空利润物料', productionNo: 'P-NULL', profit: null },
  ];
}

function respond(route: Route, status: number, body: unknown) {
  return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
}

async function installProfitApiMock(page: Page, state: MockState) {
  await page.route('**/api/cpq/dataset/quote/parts**', async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    const basePath = '/api/cpq/dataset/quote/parts';

    if (request.method() === 'GET' && url.pathname === basePath) {
      const keyword = (url.searchParams.get('keyword') ?? '').trim().toLowerCase();
      const items = keyword
        ? state.rows.filter((row) => `${row.axisValue} ${row.materialName}`.toLowerCase().includes(keyword))
        : state.rows;
      await respond(route, 200, {
        code: 200,
        data: { items, page: 0, size: 20, total: items.length, totalPages: 1 },
      });
      return;
    }

    if (request.method() === 'PUT' && url.pathname.startsWith(`${basePath}/`)) {
      const axisValue = decodeURIComponent(url.pathname.substring(basePath.length + 1));
      const body = (request.postDataJSON() ?? {}) as Record<string, unknown>;
      const observation = { axisValue, customerNo: url.searchParams.get('customerNo'), body };
      state.puts.push(observation);

      if (state.delayNextMs > 0) {
        const delay = state.delayNextMs;
        state.delayNextMs = 0;
        await new Promise((resolve) => setTimeout(resolve, delay));
      }
      if (state.failNext) {
        state.failNext = false;
        await respond(route, 500, { code: 500, message: 'T261026_UI_ simulated save failure' });
        return;
      }

      const hit = state.rows.find((row) => row.axisValue === axisValue
        && row.customerNo === observation.customerNo);
      if (hit && Object.prototype.hasOwnProperty.call(body, 'profit')) {
        hit.profit = body.profit == null ? null : String(body.profit);
      }
      await respond(route, 200, {
        code: 200,
        data: { dataset: 'quote', axisValue, updated: { profit: body.profit }, source: 'IMPORT' },
      });
      return;
    }

    if (request.method() === 'GET' && url.pathname.endsWith('/overview')) {
      await respond(route, 200, { code: 200, data: { sheets: [] } });
      return;
    }
    await respond(route, 404, { code: 404, message: `unmocked T261026_UI_ route: ${url.pathname}` });
  });
}

async function openSalesList(page: Page, state: MockState) {
  await installProfitApiMock(page, state);
  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);
  await switchTab(page, '销售产品');
  await expect(page.getByText(`${PREFIX}NEG`, { exact: true })).toBeVisible({ timeout: 15_000 });
}

function rowOf(page: Page, axisValue: string): Locator {
  return page.locator('.ant-table-tbody tr.ant-table-row')
    .filter({ has: page.getByText(axisValue, { exact: true }) }).first();
}

async function profitCell(page: Page, axisValue: string): Promise<Locator> {
  const row = rowOf(page, axisValue);
  await expect(row, `销售产品列表必须显示 ${axisValue}`).toBeVisible();
  return cellByHeader(page, row, '利润');
}

async function enterByDoubleClick(page: Page, axisValue: string): Promise<Locator> {
  const cell = await profitCell(page, axisValue);
  await cell.dblclick();
  const input = cell.locator('input').first();
  await expect(input, 'AC-8：双击利润格必须进入数字编辑态').toBeVisible();
  return input;
}

async function enterByEditIcon(page: Page, axisValue: string): Promise<Locator> {
  const cell = await profitCell(page, axisValue);
  await cell.hover();
  const edit = cell.locator(
    '.anticon-edit, [aria-label*="edit" i], [aria-label*="编辑"], svg[data-icon="edit"]',
  ).first();
  await expect(edit, 'AC-8：利润格悬停后必须提供编辑图标').toBeVisible();
  await edit.click();
  const input = cell.locator('input').first();
  await expect(input, 'AC-8：点击编辑图标必须进入编辑态').toBeVisible();
  return input;
}

function expectLastPut(state: MockState, axisValue: string, expectedProfit: string | null) {
  const last = state.puts.at(-1);
  expect(last, 'AC-8/9：保存必须发出 PUT').toBeTruthy();
  expect(last!.axisValue, 'AC-10：URL 必须携带被编辑行的销售料号').toBe(axisValue);
  expect(last!.customerNo, 'AC-10：PUT customerNo 必须来自被编辑行').toBe(CUSTOMER_NO);
  expect(Object.keys(last!.body), '单列更新不得夹带其他字段').toEqual(['profit']);
  if (expectedProfit == null) {
    expect(last!.body.profit, 'AC-9：显式清空必须发 JSON null').toBeNull();
  } else {
    expect(typeof last!.body.profit, '利润必须以精度保真字符串提交').toBe('string');
    expect(last!.body.profit, '利润字符串必须保留用户输入数值').toBe(expectedProfit);
  }
}

async function archiveScreenshot(page: Page, filename: string) {
  await mkdir(EVIDENCE_DIR, { recursive: true });
  await page.screenshot({
    path: resolve(EVIDENCE_DIR, filename),
    fullPage: true,
    animations: 'disabled',
    caret: 'hide',
  });
}

test.beforeAll(() => assertIsolatedEnv());

test('T261026_UI_01 / AC-7：列序、右对齐、NULL 破折号、极值与横向滚动', async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', (entry) => {
    if (entry.type() === 'error') consoleErrors.push(entry.text());
  });
  await page.setViewportSize({ width: 980, height: 760 });
  const state: MockState = { rows: fixtureRows(), puts: [], failNext: false, delayNextMs: 0 };
  await openSalesList(page, state);

  expect(page.url(), '页面必须停在产品管理壳页').toContain('/products-hub');
  await expect(page).toHaveTitle('cpq-frontend');
  await expect(page.getByRole('heading', { name: '产品管理', exact: true })).toBeVisible();
  await expect(page.locator('vite-error-overlay, webpack-dev-server-client-overlay, nextjs-portal'))
    .toHaveCount(0);

  const headers = await headerTexts(page);
  const vatAt = headers.indexOf('增值税');
  expect(vatAt, '必须先找到增值税列，否则相对列序断言会空跑').toBeGreaterThanOrEqual(0);
  const profitAt = headers.indexOf('利润');
  expect(profitAt, 'AC-7：利润必须紧跟增值税').toBe(vatAt + 1);
  expect(headers.indexOf('产品分类'), 'AC-7：利润后应为产品分类').toBe(profitAt + 1);

  const negative = await profitCell(page, `${PREFIX}NEG`);
  expect(await negative.evaluate((el) => getComputedStyle(el).textAlign), 'AC-7：利润列必须右对齐')
    .toMatch(/right|end/);
  await expect(negative).toContainText('-12.34');
  await expect(await profitCell(page, `${PREFIX}ZERO`)).toContainText('0');
  await expect(await profitCell(page, `${PREFIX}NULL`)).toContainText('—');

  const longCell = await profitCell(page, `${PREFIX}LONG`);
  await expect(longCell).toContainText('99999999999999.999999999999');
  const ordinaryBox = await negative.boundingBox();
  const longBox = await longCell.boundingBox();
  expect(ordinaryBox, '普通利润单元格必须可见').not.toBeNull();
  expect(longBox, '极值利润单元格必须可见').not.toBeNull();
  expect(Math.abs(longBox!.width - ordinaryBox!.width),
    'AC-7：极值格与普通格必须保持同一利润列宽，只容忍浏览器亚像素舍入')
    .toBeLessThanOrEqual(0.75);
  const scrollable = await page.locator('.ant-table-content, .ant-table-body').evaluateAll((nodes) =>
    nodes.some((node) => node instanceof HTMLElement && node.offsetParent !== null
      && node.scrollWidth > node.clientWidth));
  expect(scrollable, 'AC-7：宽表必须保持水平滚动').toBe(true);

  await negative.click();
  await expect(page.locator('.ant-drawer-open'), '利润格单击不得误开抽屉').toHaveCount(0);
  await archiveScreenshot(page, '01-default-null-extreme-horizontal-scroll.png');
  expect(consoleErrors, '默认态不得有浏览器 console error').toEqual([]);
});

test('T261026_UI_02 / AC-8：双击/图标编辑，Esc 取消，Enter 与失焦保存', async ({ page }) => {
  const state: MockState = { rows: fixtureRows(), puts: [], failNext: false, delayNextMs: 0 };
  await openSalesList(page, state);

  let input = await enterByDoubleClick(page, `${PREFIX}NEG`);
  await input.fill('88');
  await input.press('Escape');
  expect(state.puts, 'AC-8：Esc 只取消，不得提交').toHaveLength(0);
  await expect(await profitCell(page, `${PREFIX}NEG`)).toContainText('-12.34');

  input = await enterByEditIcon(page, `${PREFIX}NEG`);
  await archiveScreenshot(page, '02-editing-by-icon.png');
  await input.fill('-2.5');
  await input.press('Enter');
  await expect.poll(() => state.puts.length).toBe(1);
  expectLastPut(state, `${PREFIX}NEG`, '-2.5');

  input = await enterByDoubleClick(page, `${PREFIX}NEG`);
  await input.fill('0');
  await input.blur();
  await expect.poll(() => state.puts.length).toBe(2);
  expectLastPut(state, `${PREFIX}NEG`, '0');

  input = await enterByDoubleClick(page, `${PREFIX}LONG`);
  await input.fill('123.456789012345');
  await input.press('Enter');
  await expect.poll(() => state.puts.length).toBe(3);
  expectLastPut(state, `${PREFIX}LONG`, '123.456789012345');
  const savedLongCell = await profitCell(page, `${PREFIX}LONG`);
  await expect(savedLongCell, 'AC-8：成功保存后应回到展示态并显示新值')
    .toContainText('123.456789012345');
  await expect(savedLongCell.locator('input'), 'AC-8：成功保存后应退出编辑态').toHaveCount(0);
  await archiveScreenshot(page, '02-saved-negative-zero-decimal.png');
});

test('T261026_UI_03 / AC-8~AC-10：非数字、保存中、清空 null、失败回滚与客户号', async ({ page }) => {
  const state: MockState = { rows: fixtureRows(), puts: [], failNext: false, delayNextMs: 0 };
  await openSalesList(page, state);

  let input = await enterByDoubleClick(page, `${PREFIX}NEG`);
  await input.fill('abc');
  await input.press('Enter');
  await expect(page.getByText('利润必须是数字', { exact: false }),
    'AC-8：非数字必须显示可读提示').toBeVisible();
  expect(state.puts, 'AC-8：非数字不得发 PUT').toHaveLength(0);
  await input.press('Escape');

  state.delayNextMs = 700;
  input = await enterByDoubleClick(page, `${PREFIX}ZERO`);
  await input.fill('7.25');
  await input.press('Enter');
  await expect(await profitCell(page, `${PREFIX}ZERO`), 'AC-8：等待 PUT 时必须呈现保存中态')
    .toContainText('保存中');
  await expect.poll(() => state.puts.length).toBe(1);
  expectLastPut(state, `${PREFIX}ZERO`, '7.25');
  const savedZeroCell = await profitCell(page, `${PREFIX}ZERO`);
  await expect(savedZeroCell, 'AC-8：延迟响应完成后应回填已保存的 7.25').toContainText('7.25');
  await expect(savedZeroCell, 'AC-8：下一次编辑前必须已退出保存中态').not.toContainText('保存中');

  input = await enterByDoubleClick(page, `${PREFIX}ZERO`);
  await input.fill('');
  await input.press('Enter');
  await expect.poll(() => state.puts.length).toBe(2);
  expectLastPut(state, `${PREFIX}ZERO`, null);
  await expect(await profitCell(page, `${PREFIX}ZERO`)).toContainText('—');

  state.failNext = true;
  input = await enterByDoubleClick(page, `${PREFIX}NEG`);
  await input.fill('99');
  await input.press('Enter');
  await expect(page.getByText('保存失败，已还原', { exact: false }),
    'AC-9：4xx/5xx 必须显示回滚文案').toBeVisible({ timeout: 10_000 });
  await expect(await profitCell(page, `${PREFIX}NEG`), 'AC-9：保存失败必须恢复 props 原值')
    .toContainText('-12.34');
  expectLastPut(state, `${PREFIX}NEG`, '99');
  await archiveScreenshot(page, '03-save-failure-rollback.png');
});
