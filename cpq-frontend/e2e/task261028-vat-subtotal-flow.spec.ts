import { expect, test, type Locator, type Page } from '@playwright/test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

type Credentials = { username: string; password: string };

const USER_PREFIX = 'T261028_VAT_';
const DATA_PREFIX = 'T261028_VAT_FLOW_';
const SUBTOTAL = '1717.135540717';
const VAT_RATIO = '0.13';
const NET_LINE = '1717.135540717 USD';
const VAT_LINE = '(VAT) 1940.36316101 USD';
const LONG_NET_LINE = '99999999999999.999999999 USDT';
const LONG_VAT_LINE = '(VAT) 199999999998999.999999998 USDT';

function required(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`T261028_VAT_FLOW_ missing required environment variable ${name}`);
  return value;
}

const BACKEND_URL = required('PW_BACKEND_URL').replace(/\/$/, '');
const QUOTATION_ID = required('PW_VAT_QUOTATION_ID');
const QUOTATION_NO = required('PW_VAT_QUOTATION_NO');
const PART_NO = required('PW_VAT_PART_NO');
const LONG_QUOTATION_ID = required('PW_VAT_LONG_QUOTATION_ID');
const LONG_PART_NO = required('PW_VAT_LONG_PART_NO');
const EVIDENCE_DIR = process.env.PW_VAT_EVIDENCE_DIR?.trim()
  || path.join(os.tmpdir(), 'task261028-vat-subtotal-evidence');

const ADMIN: Credentials = {
  username: required('PW_VAT_ADMIN_USER'),
  password: required('PW_VAT_ADMIN_PASSWORD'),
};
const SALES_MANAGER: Credentials = {
  username: required('PW_VAT_SALES_MANAGER_USER'),
  password: required('PW_VAT_SALES_MANAGER_PASSWORD'),
};

for (const [label, credentials] of Object.entries({ ADMIN, SALES_MANAGER })) {
  if (!credentials.username.startsWith(USER_PREFIX)) {
    throw new Error(`${label} account must start with ${USER_PREFIX}: ${credentials.username}`);
  }
}
if (!QUOTATION_NO.startsWith(DATA_PREFIX) || !PART_NO.startsWith(DATA_PREFIX)
  || !LONG_PART_NO.startsWith(DATA_PREFIX)) {
  throw new Error(
    `Quotation number and part numbers must start with ${DATA_PREFIX}: `
      + `${QUOTATION_NO}, ${PART_NO}, ${LONG_PART_NO}`,
  );
}
for (const [label, id] of Object.entries({ QUOTATION_ID, LONG_QUOTATION_ID })) {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(id)) {
    throw new Error(`${label} must be a UUID: ${id}`);
  }
}

test.describe.configure({ mode: 'serial' });

const consoleProblemsByPage = new WeakMap<Page, string[]>();

function asObject(value: unknown, label: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${label} must be an object: ${JSON.stringify(value)}`);
  }
  return value as Record<string, unknown>;
}

function unwrap(body: unknown): Record<string, unknown> {
  const object = asObject(body, 'API response');
  return asObject(Object.hasOwn(object, 'data') ? object.data : object, 'API data');
}

async function login(page: Page, credentials: Credentials): Promise<void> {
  await page.context().clearCookies();
  const response = await page.request.post(`${BACKEND_URL}/api/cpq/auth/login`, {
    data: credentials,
  });
  const body = await response.text();
  expect(response.status(), `Login failed for ${credentials.username}: ${body}`).toBe(200);
  expect(
    (await page.context().cookies()).some((cookie) => cookie.name === 'CPQ_SESSION'),
    'Login must set CPQ_SESSION',
  ).toBe(true);
}

async function getQuotation(page: Page, quotationId = QUOTATION_ID): Promise<Record<string, unknown>> {
  const response = await page.request.get(`${BACKEND_URL}/api/cpq/quotations/${quotationId}`);
  const body = await response.text();
  expect(response.status(), `GET quotation failed: ${body}`).toBe(200);
  const quotation = unwrap(JSON.parse(body));
  expect(String(quotation.id), 'GET must return the requested quotation').toBe(quotationId);
  return quotation;
}

async function assertSettled(page: Page, label: string): Promise<void> {
  await expect(page.getByText('加载中', { exact: false }), `${label}: loading text must settle`)
    .toHaveCount(0, { timeout: 30_000 });
  await expect(
    page.locator('.ant-spin-spinning:visible, .ant-btn-loading:visible, .anticon-loading:visible'),
    `${label}: visible loading indicators must settle`,
  ).toHaveCount(0, { timeout: 30_000 });
  await expect(page.locator('.ant-alert-error:visible'), `${label}: no error alert`).toHaveCount(0);
  await expect(
    page.getByText(/Unexpected Application Error|Internal Server Error|Failed to compile/i),
    `${label}: no framework overlay`,
  ).toHaveCount(0);
}

async function openEditStep2(page: Page, quotationId = QUOTATION_ID): Promise<void> {
  await page.goto(`/quotations/${quotationId}/edit`);
  await page.waitForLoadState('networkidle');
  const cards = page.locator('.qt-product-card');
  if (!(await cards.first().isVisible().catch(() => false))) {
    const next = page.getByRole('button', { name: /下一步/ }).first();
    await expect(next, 'Step 1 must provide an enabled next action').toBeEnabled();
    await next.click();
  }
  await expect(cards.first(), 'Step 2 must render a non-empty product card').toBeVisible({ timeout: 30_000 });
  await assertSettled(page, 'quotation edit step 2');
}

async function uniqueCard(page: Page, partNo: string): Promise<Locator> {
  const card = page.locator('.qt-product-card').filter({ hasText: partNo });
  await expect(card, `Product card must uniquely contain ${partNo}`).toHaveCount(1);
  await expect(card).toBeVisible();
  return card;
}

async function quotationSubtotalLines(card: Locator): Promise<string[]> {
  const footer = card.locator('.qt-subtotal-bar, .qt-subtotal-bar-multi').last();
  await expect(footer, 'Quotation card must have one visible subtotal footer').toBeVisible();
  const lines = footer.locator('.qt-subtotal-line');
  await expect(lines, 'Quotation subtotal footer must have exactly two non-empty lines').toHaveCount(2);
  const texts = (await lines.allInnerTexts()).map((text) => text.replace(/\s+/g, ' ').trim());
  expect(texts.every(Boolean), `Subtotal lines must be non-empty: ${JSON.stringify(texts)}`).toBe(true);
  expect(await card.locator('.qt-subtotal-value').count(), 'Existing net selector must remain unique').toBe(1);
  const footerText = await footer.innerText();
  expect(footerText).not.toContain('产品小计');
  expect(footerText).not.toContain('税前价格');
  expect(footerText).not.toContain('税后价格');
  return texts;
}

async function assertQuoteSubtotal(page: Page, partNo = PART_NO): Promise<string[]> {
  const card = await uniqueCard(page, partNo);
  const lines = await quotationSubtotalLines(card);
  expect(lines).toEqual([NET_LINE, VAT_LINE]);
  await assertSettled(page, `quotation subtotal ${partNo}`);
  return lines;
}

function removeAllowedTopLevelChanges(quotation: Record<string, unknown>): Record<string, unknown> {
  const stable = structuredClone(quotation);
  delete stable.projectName;
  delete stable.updatedAt;
  return stable;
}

function collectForbiddenKeys(value: unknown, trail = '$'): string[] {
  if (Array.isArray(value)) {
    return value.flatMap((item, index) => collectForbiddenKeys(item, `${trail}[${index}]`));
  }
  if (!value || typeof value !== 'object') return [];
  return Object.entries(value as Record<string, unknown>).flatMap(([key, child]) => {
    const current = `${trail}.${key}`;
    const own = /taxInclusive|vatInclusive/i.test(key) ? [current] : [];
    return own.concat(collectForbiddenKeys(child, current));
  });
}

async function saveProjectName(page: Page, projectName: string): Promise<unknown> {
  await page.goto(`/quotations/${QUOTATION_ID}/edit`);
  await page.waitForLoadState('networkidle');
  const input = page.getByPlaceholder('关联项目', { exact: true }).first();
  await expect(input, 'Project name input must be visible').toBeVisible();
  await input.fill(projectName);
  await page.getByRole('button', { name: /下一步/ }).first().click();
  await expect(page.locator('.qt-product-card').first(), 'Save path must reach product cards').toBeVisible();

  const requestPromise = page.waitForRequest((request) =>
    request.method() === 'PUT'
      && new RegExp(`/quotations/${QUOTATION_ID}/draft(?:\\?|$)`).test(request.url()));
  const responsePromise = page.waitForResponse((response) =>
    response.request().method() === 'PUT'
      && new RegExp(`/quotations/${QUOTATION_ID}/draft(?:\\?|$)`).test(response.url()));
  await page.getByRole('button', { name: /保存草稿$/ }).click();
  const [request, response] = await Promise.all([requestPromise, responsePromise]);
  expect(response.status(), `Save draft failed: ${await response.text()}`).toBe(200);
  await assertSettled(page, 'save draft');
  return request.postDataJSON();
}

async function switchToCosting(page: Page): Promise<void> {
  const costing = page.locator('.ant-segmented-item').filter({ hasText: '核价单' }).first();
  await expect(costing, 'Fixture must expose the costing card view').toBeVisible();
  await costing.click();
  await expect(page.locator('.qt-product-card').first(), 'Costing view must contain a product card').toBeVisible();
  await assertSettled(page, 'costing card view');
}

async function assertCostingFooterUnchanged(page: Page): Promise<void> {
  const card = await uniqueCard(page, PART_NO);
  const footer = card.locator('.qt-subtotal-bar, .qt-subtotal-bar-multi').last();
  await expect(footer.getByText('产品小计', { exact: true }), 'Costing net label must remain').toHaveCount(1);
  await expect(footer.getByText('外币小计', { exact: true }), 'Costing foreign label must remain').toHaveCount(1);
  expect(await footer.getByText('(VAT)', { exact: true }).count(), 'Costing footer must not gain VAT').toBe(0);
  expect((await footer.locator('.qt-subtotal-line').allInnerTexts()).every((text) => text.trim().length > 0),
    'Costing subtotal lines must be non-empty').toBe(true);
}

test.beforeAll(() => {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  expect(new Set([ADMIN.username, SALES_MANAGER.username]).size, 'Roles must use separate private accounts').toBe(2);
});

test.beforeEach(async ({ page }) => {
  const problems: string[] = [];
  consoleProblemsByPage.set(page, problems);
  page.on('console', (message) => {
    if (message.type() === 'error' || message.type() === 'warning') {
      problems.push(`${message.type()}: ${message.text()}`);
    }
  });
});

test.afterEach(async ({ page }) => {
  expect(consoleProblemsByPage.get(page) ?? [], 'Each target flow must emit zero console error/warn messages')
    .toEqual([]);
});

test('T-04 / AC-3: edit -> detail -> edit refresh keeps two exact quotation lines', async ({ page }) => {
  await login(page, ADMIN);

  await openEditStep2(page);
  const firstEdit = await assertQuoteSubtotal(page);

  await page.goto(`/quotations/${QUOTATION_ID}`);
  await page.waitForLoadState('networkidle');
  const detail = await assertQuoteSubtotal(page);

  await openEditStep2(page);
  await page.reload();
  await page.waitForLoadState('networkidle');
  const refreshedEdit = await assertQuoteSubtotal(page);

  expect([firstEdit, detail, refreshedEdit]).toEqual([
    [NET_LINE, VAT_LINE],
    [NET_LINE, VAT_LINE],
    [NET_LINE, VAT_LINE],
  ]);
});

test('T-05 / AC-5: saving only project text does not persist VAT-inclusive display data', async ({ page }) => {
  await login(page, SALES_MANAGER);
  const before = await getQuotation(page);
  const originalProjectName = String(before.projectName ?? '');
  const changedProjectName = `${DATA_PREFIX}PROJECT_SAVE`;
  expect(JSON.stringify(before), `Fixture must contain exact subtotal ${SUBTOTAL}`).toContain(SUBTOTAL);
  expect(JSON.stringify(before), `Fixture must contain exact VAT ratio ${VAT_RATIO}`).toContain(VAT_RATIO);
  expect(collectForbiddenKeys(before), 'GET baseline must not expose VAT-inclusive display fields').toEqual([]);
  let changed = false;
  let primaryFailure: unknown;
  let restoreFailure: unknown;

  try {
    const payload = await saveProjectName(page, changedProjectName);
    changed = true;
    expect(collectForbiddenKeys(payload), 'Save payload must not add VAT-inclusive fields').toEqual([]);
    expect(JSON.stringify(payload), 'Save payload must not contain rendered VAT-inclusive text')
      .not.toContain('1940.36316101');
    const after = await getQuotation(page);
    expect(String(after.projectName ?? ''), 'The one authorized text change must persist')
      .toBe(changedProjectName);
    expect(removeAllowedTopLevelChanges(after), 'Subtotal, VAT and all card snapshots must be byte-equivalent JSON')
      .toEqual(removeAllowedTopLevelChanges(before));
    expect(collectForbiddenKeys(after), 'GET after save must not expose VAT-inclusive fields').toEqual([]);
  } catch (error) {
    primaryFailure = error;
  } finally {
    if (changed) {
      try {
        await saveProjectName(page, originalProjectName);
        const restored = await getQuotation(page);
        expect(String(restored.projectName ?? ''), 'Private fixture project name must be restored in finally')
          .toBe(originalProjectName);
      } catch (error) {
        restoreFailure = error;
      }
    }
  }

  if (primaryFailure && restoreFailure) {
    throw new AggregateError([primaryFailure, restoreFailure], 'Primary save assertion and fixture restoration both failed');
  }
  if (primaryFailure) throw primaryFailure;
  if (restoreFailure) throw restoreFailure;
});

test('T-06 / AC-6: costing edit and readonly cards keep the legacy two labels and no VAT line', async ({ page }) => {
  await login(page, ADMIN);
  await openEditStep2(page);
  await switchToCosting(page);
  await assertCostingFooterUnchanged(page);

  await page.goto(`/quotations/${QUOTATION_ID}`);
  await page.waitForLoadState('networkidle');
  await switchToCosting(page);
  await assertCostingFooterUnchanged(page);
});

test('T-07 / AC-7: long totals remain ordered, single-line and inside the page at 1280 and 390', async ({ page }) => {
  await login(page, ADMIN);
  for (const viewport of [
    { width: 1280, height: 720, name: 'desktop' },
    { width: 390, height: 844, name: 'mobile' },
  ]) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.goto(`/quotations/${LONG_QUOTATION_ID}`);
    await page.waitForLoadState('networkidle');
    const card = await uniqueCard(page, LONG_PART_NO);
    const lines = await quotationSubtotalLines(card);
    expect(lines).toEqual([LONG_NET_LINE, LONG_VAT_LINE]);

    const footerLines = card.locator('.qt-subtotal-line');
    const firstBox = await footerLines.nth(0).boundingBox();
    const secondBox = await footerLines.nth(1).boundingBox();
    const prefix = footerLines.nth(1).getByText('(VAT)', { exact: true });
    const amount = footerLines.nth(1).getByText('199999999998999.999999998 USDT', { exact: true });
    const prefixBox = await prefix.boundingBox();
    const amountBox = await amount.boundingBox();
    expect(firstBox && secondBox && prefixBox && amountBox, 'All layout targets must have non-empty boxes').toBeTruthy();
    expect(secondBox!.y, 'VAT row must be below net row').toBeGreaterThanOrEqual(firstBox!.y + firstBox!.height - 1);
    expect(prefixBox!.x, 'VAT prefix must precede the amount').toBeLessThan(amountBox!.x);
    expect(
      Math.max(prefixBox!.y, amountBox!.y),
      'Different font sizes may have different top coordinates, but their vertical intervals must overlap',
    ).toBeLessThan(Math.min(prefixBox!.y + prefixBox!.height, amountBox!.y + amountBox!.height));
    const vatGroup = await prefix.evaluate((element, expectedAmount) => {
      const parent = element.parentElement;
      const style = parent ? getComputedStyle(parent) : null;
      return {
        hasExpectedAmount: Array.from(parent?.children ?? [])
          .some((child) => child.textContent?.trim() === expectedAmount),
        whiteSpace: style?.whiteSpace ?? '',
      };
    }, '199999999998999.999999998 USDT');
    expect(vatGroup.hasExpectedAmount, 'VAT prefix and amount must share one unique parent container').toBe(true);
    expect(vatGroup.whiteSpace, 'Shared VAT group must prohibit internal wrapping').toBe('nowrap');
    expect(await amount.evaluate((element) => getComputedStyle(element).whiteSpace), 'Long number must not wrap')
      .toBe('nowrap');
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth === document.documentElement.clientWidth),
      'Page must not overflow horizontally',
    )
      .toBe(true);
    await page.screenshot({
      path: path.join(EVIDENCE_DIR, `T-07-${viewport.name}-${viewport.width}x${viewport.height}.png`),
      fullPage: false,
    });
  }
});
