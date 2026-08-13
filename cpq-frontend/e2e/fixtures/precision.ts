import Decimal from 'decimal.js';
import { expect, type Locator, type Page } from '@playwright/test';
import type { DecimalString } from '../../src/utils/precision';

export const PRECISION_PARTS = {
  simple: 'TASK0810-SIMPLE',
  composite: 'TASK0810-COMPOSITE',
  partOne: 'TASK0810-PART-01',
  partTwo: 'TASK0810-PART-02',
} as const;

export const PRECISION_TABS = ['精度验证', '稳定页签'] as const;
export const PRECISION_ROW_KEYS = ['01', '02'] as const;
export const PRECISION_SENTINELS = [
  '1.234567891234',
  '98765431.123456789012',
] as const satisfies readonly DecimalString[];
export const STABILITY_SENTINELS = ['stable-01', 'stable-02'] as const;

export interface PrecisionFixtureContract {
  seedQuotationNo: string;
  tabName: string;
  inputField: string;
  resultField: string;
  positiveRowIndex: number;
  negativeRowIndex: number;
}

export const PRECISION_FIXTURE: PrecisionFixtureContract = {
  seedQuotationNo: process.env.PW_PRECISION_SEED_QUOTATION_NO || '',
  tabName: process.env.PW_PRECISION_TAB_NAME || '精度验证',
  inputField: process.env.PW_PRECISION_INPUT_FIELD || '精度输入',
  resultField: process.env.PW_PRECISION_RESULT_FIELD || '精度结果',
  positiveRowIndex: 0,
  negativeRowIndex: 1,
};

export const PRECISION_CASES = {
  positiveWork: '1.234567890499',
  positiveDisplay: '1.23456789',
  negativeWork: '-1.234567890500',
  negativeDisplay: '-1.234567891',
  largeWork: '98765431.123456789012',
  largeDisplay: '98765431.123456789',
  // TC-076（阻断组）：FR-12 已由 repair-0812 作废改写（问题说明.md §5/§7）——对账不再按 12 位
  // 工作值比，而是先把两侧归一到 FORMULA_RESULT_SCALE(9) 再比。这对哨兵的差异精确落在归一后的
  // 第 9 位（frontendFraction 第 9 位 '9' vs backendFraction 第 9 位 '8'），归一后仍不同，
  // 必须继续阻断提交——对应 repair-0812 test.md TC-09 / 单测 TC-02b 的"最小真差异边界"。
  reconcileFrontend: '7.123456789111',
  reconcileBackend: '7.123456788499',
  reconcileDisplay: '7.123456789',
  recoveredWork: '7.123456789112',
  // TC-076b（放行组）：差异只落在第 10~12 位（前 9 位完全相同），归一后两侧相等 —— 这正是
  // 被作废的旧 FR-12 曾要求阻断、repair-0812 裁决改为必须放行的那对值。
  reconcileSubScaleFrontend: '7.123456789111',
  reconcileSubScaleBackend: '7.123456789499',
  reconcileSubScaleDisplay: '7.123456789',
} as const satisfies Record<string, DecimalString>;

const BACKEND_URL = process.env.PW_BACKEND_URL || 'http://localhost:8081';

function unwrap(body: any): any {
  return body?.data ?? body;
}

async function jsonOrThrow(response: { ok(): boolean; status(): number; text(): Promise<string>; json(): Promise<any> }, label: string) {
  if (!response.ok()) throw new Error(`${label}失败: HTTP ${response.status()} ${await response.text()}`);
  const body = await response.json();
  if (typeof body?.code === 'number') {
    expect(body.code, `${label} API code 必须与 HTTP status 一致`).toBe(response.status());
  }
  return unwrap(body);
}

/**
 * 复制仓库约定的确定性 SIMPLE 种子单据。种子必须满足：
 * - 目标页签同时存在于报价/核价卡片，至少两行；
 * - inputField 为可编辑 INPUT_NUMBER；resultField 为恒等公式(input * 1)；
 * - 报价与核价的 resultField 使用相同公式，便于三视图逐值对拍。
 */
export async function copyPrecisionSeed(page: Page): Promise<string> {
  if (!PRECISION_FIXTURE.seedQuotationNo) {
    throw new Error(
      '必须显式设置 PW_PRECISION_SEED_QUOTATION_NO，指向 Stage H 已加载的确定性 SIMPLE 精度种子；' +
      '禁止回退到随机或普通业务单据。',
    );
  }
  const seedId = await findQuotationIdByNo(page, PRECISION_FIXTURE.seedQuotationNo);
  const copyResponse = await page.request.post(`${BACKEND_URL}/api/cpq/quotations/${seedId}/copy`, { data: {} });
  const copied = await jsonOrThrow(copyResponse, '复制精度种子报价单');
  const copiedId = copied?.id ?? copied?.quotationId;
  if (!copiedId) throw new Error(`复制响应缺少 quotation id: ${JSON.stringify(copied)}`);
  return String(copiedId);
}

export async function findQuotationIdByNo(page: Page, quotationNo: string): Promise<string> {
  const pageSize = 100;
  const exactMatches: any[] = [];
  let pageIndex = 0;
  let scanned = 0;
  let total = Number.POSITIVE_INFINITY;

  while (scanned < total) {
    const query = new URLSearchParams({
      keyword: quotationNo,
      page: String(pageIndex),
      size: String(pageSize),
    });
    const listResponse = await page.request.get(`${BACKEND_URL}/api/cpq/quotations?${query}`);
    const list = await jsonOrThrow(listResponse, `查询精度种子报价单第 ${pageIndex + 1} 页`);
    const items: any[] = Array.isArray(list) ? list : (list?.content ?? []);
    exactMatches.push(...items.filter((item) =>
      item?.quotationNumber === quotationNo || item?.quotationNo === quotationNo,
    ));
    scanned += items.length;
    const responseTotal = Number(list?.totalElements ?? list?.total);
    if (!Array.isArray(list) && Number.isFinite(responseTotal)) total = responseTotal;
    if (items.length === 0 || items.length < pageSize) break;
    pageIndex += 1;
    if (pageIndex > 10_000) throw new Error(`查询 quotationNo=${quotationNo} 分页超过安全上限`);
  }

  if (exactMatches.length !== 1 || !exactMatches[0]?.id) {
    throw new Error(
      `确定性 E2E 种子 quotationNo=${quotationNo} 必须唯一，实际精确匹配 ${exactMatches.length} 条。` +
      '请加载仓库 Stage H seed；不得使用第一页随机业务单据替代。',
    );
  }
  return String(exactMatches[0].id);
}

export async function assertPageLoadingSettled(page: Page, label: string): Promise<void> {
  const visibleLoading = page.locator(
    '.ant-btn-loading:visible, .ant-spin-spinning:visible, .anticon-loading:visible, [aria-label="loading"]:visible',
  );
  await expect(visibleLoading, `${label} 不得残留可见 loading`).toHaveCount(0, { timeout: 30_000 });
  await expect(page.getByText('加载中', { exact: false }), `${label} 不得残留“加载中”文案`)
    .toHaveCount(0, { timeout: 30_000 });
}

async function settleQuotationStep2(page: Page): Promise<void> {
  await page.waitForLoadState('networkidle');
  const cards = page.locator('.qt-product-card');
  if (!(await cards.first().isVisible().catch(() => false))) {
    const next = page.getByRole('button', { name: /下一步/ }).first();
    await expect(next, '编辑页 Step1 应可进入 Step2').toBeEnabled({ timeout: 20_000 });
    await next.click();
  }
  await expect(cards.first(), 'Step2 应渲染产品卡片').toBeVisible({ timeout: 30_000 });
  await assertPageLoadingSettled(page, 'Step2 页面');
}

export async function openQuotationStep2(page: Page, quotationId: string): Promise<void> {
  await page.goto(`/quotations/${quotationId}/edit`);
  await settleQuotationStep2(page);
}

export async function reloadQuotationStep2(page: Page): Promise<void> {
  await page.reload();
  await settleQuotationStep2(page);
}

export async function openDetail(page: Page, quotationId: string): Promise<void> {
  await page.goto(`/quotations/${quotationId}`);
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.qt-product-card').first(), '详情页应渲染产品卡片').toBeVisible({ timeout: 30_000 });
  await expect(page.getByText('加载中', { exact: false })).toHaveCount(0, { timeout: 30_000 });
}

export async function productCardByPartNo(page: Page, partNo: string): Promise<Locator> {
  const cards = page.locator('.qt-product-card').filter({
    has: page.locator('.qt-sku-badge').filter({ hasText: new RegExp(`料号:\\s*${escapeRegExp(partNo)}(?:\\s|$)`) }),
  });
  await expect(cards, `料号 ${partNo} 必须唯一对应一个可见产品卡`).toHaveCount(1);
  const card = cards.nth(0);
  await expect(card).toBeVisible();
  return card;
}

export async function assertVisibleProductCards(page: Page, expectedPartNos: readonly string[]): Promise<void> {
  const cards = page.locator('.qt-product-card');
  await expect(cards, `可见产品卡数量必须为 ${expectedPartNos.length}`).toHaveCount(expectedPartNos.length);
  for (const partNo of expectedPartNos) await productCardByPartNo(page, partNo);
}

export async function openCardTab(page: Page, card: Locator, tabName = PRECISION_FIXTURE.tabName): Promise<void> {
  const tabs = card.locator('button.qt-tab-btn');
  const tab = tabs.filter({ hasText: new RegExp(`^${escapeRegExp(tabName)}$`) });
  await expect(tab, `产品卡内页签「${tabName}」必须唯一`).toHaveCount(1);
  await expect(tab, `应存在页签「${tabName}」`).toBeVisible({ timeout: 20_000 });
  await tab.click();
  await expect(page.getByText('加载中', { exact: false })).toHaveCount(0, { timeout: 20_000 });
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

async function activeTable(card: Locator) {
  const tables = card.locator('table.qt-cost-table:visible');
  await expect(tables, '当前产品卡必须只有一张可见卡片表格').toHaveCount(1);
  const table = tables.nth(0);
  await expect(table, '当前页签应有可见卡片表格').toBeVisible({ timeout: 20_000 });
  return table;
}

async function columnIndex(card: Locator, fieldName: string): Promise<number> {
  const table = await activeTable(card);
  const headers = await table.locator('thead tr').last().locator('th').allInnerTexts();
  const index = headers.findIndex((text) => text.replace(/\s+/g, ' ').trim() === fieldName);
  if (index < 0) throw new Error(`当前表格缺少字段列「${fieldName}」，实际表头=${JSON.stringify(headers)}`);
  return index;
}

export async function editPrecisionValue(page: Page, card: Locator, rowIndex: number, value: DecimalString) {
  const table = await activeTable(card);
  const index = await columnIndex(card, PRECISION_FIXTURE.inputField);
  const row = table.locator('tbody tr').nth(rowIndex);
  const input = row.locator('td').nth(index).locator('input[type="number"]');
  await expect(input, `第 ${rowIndex + 1} 行「${PRECISION_FIXTURE.inputField}」应可编辑`).toBeVisible();

  const responsePromise = page.waitForResponse((response) =>
    response.request().method() === 'PUT' && response.url().includes('/quote-card-edit'),
  );
  await input.fill(value);
  await input.blur();
  const response = await responsePromise;
  expect(response.ok(), 'quote-card-edit 应成功').toBe(true);
  const payload = response.request().postDataJSON();
  expect(payload.value, '精度编辑请求必须发送 decimal string').toBe(value);
  expect(typeof payload.value).toBe('string');
  return { response, payload };
}

export async function readFieldText(card: Locator, rowIndex: number, fieldName = PRECISION_FIXTURE.resultField): Promise<string> {
  const table = await activeTable(card);
  const index = await columnIndex(card, fieldName);
  return (await table.locator('tbody tr').nth(rowIndex).locator('td').nth(index).innerText()).trim();
}

export async function readInputValue(card: Locator, rowIndex: number): Promise<string> {
  const table = await activeTable(card);
  const index = await columnIndex(card, PRECISION_FIXTURE.inputField);
  return table.locator('tbody tr').nth(rowIndex).locator('td').nth(index).locator('input').inputValue();
}

export function assertExactDisplay(actual: string, expected: string): void {
  const normalized = actual.replace(/,/g, '').replace(/^¥\s*/, '').trim();
  expect(normalized).toBe(expected);
  const decimals = normalized.includes('.') ? normalized.split('.')[1].length : 0;
  expect(decimals, `DOM 小数位不得超过 9 位: ${actual}`).toBeLessThanOrEqual(9);
}

export async function saveDraft(page: Page): Promise<void> {
  const saveButtons = page.getByRole('button', { name: /保存草稿$/ });
  await expect(saveButtons, '编辑页必须唯一存在保存草稿按钮').toHaveCount(1);
  const save = saveButtons.first();
  await expect(save).toBeEnabled({ timeout: 20_000 });
  const responsePromise = page.waitForResponse((response) =>
    response.request().method() === 'PUT' && /\/quotations\/[^/]+\/draft(?:\?|$)/.test(response.url()),
  );
  await save.click();
  const response = await responsePromise;
  expect(response.status(), '保存草稿 HTTP status').toBe(200);
  const responseBody = await response.json();
  expect(responseBody?.code, '保存草稿 API code').toBe(200);
  const request = response.request();
  const payload = request.postDataJSON();
  assertNoPrecisionNumbers(payload);
  await expect(page.locator('.ant-message-notice-content').filter({ hasText: /保存|草稿/ }).first()).toBeVisible({ timeout: 20_000 });
}

function assertNoPrecisionNumbers(value: unknown, key = ''): void {
  const structural = new Set([
    'annualVolume', 'decimals', 'index', 'itemSeq', 'page', 'pageSize', 'partVersionLocked',
    'rowIndex', 'seqNo', 'size', 'snapshotRows', 'sortOrder', 'sort_order', 'tempParentIndex', 'version',
  ]);
  if (typeof value === 'number') {
    expect(structural.has(key) && Number.isSafeInteger(value), `精度请求字段 ${key} 不得使用 JS number`).toBe(true);
    return;
  }
  if (Array.isArray(value)) return value.forEach((item) => assertNoPrecisionNumbers(item));
  if (value && typeof value === 'object') {
    for (const [childKey, child] of Object.entries(value)) assertNoPrecisionNumbers(child, childKey);
  }
}

export async function getQuotation(page: Page, quotationId: string): Promise<any> {
  const response = await page.request.get(`${BACKEND_URL}/api/cpq/quotations/${quotationId}`);
  return jsonOrThrow(response, `读取报价单 ${quotationId}`);
}

function hasExactFixtureTabs(raw: unknown): boolean {
  if (typeof raw !== 'string' && (raw === null || typeof raw !== 'object')) return false;
  try {
    const snapshot = typeof raw === 'string' ? JSON.parse(raw) : raw;
    const tabNames = (snapshot?.tabs ?? []).map((tab: any) => tab?.tabName);
    return tabNames.length === PRECISION_TABS.length
      && tabNames.every((tabName: unknown, index: number) => tabName === PRECISION_TABS[index]);
  } catch {
    return false;
  }
}

function hasCompleteFixtureCardValues(quotation: any, expectedPartNos: readonly string[]): boolean {
  if (quotation?.cardValuesWarming) return false;
  const lines: any[] = quotation?.lineItems ?? [];
  if (lines.length !== expectedPartNos.length) return false;
  return expectedPartNos.every((partNo) => {
    const matches = lines.filter((line) => line?.productPartNo === partNo);
    return matches.length === 1
      && hasExactFixtureTabs(matches[0]?.quoteCardValues)
      && hasExactFixtureTabs(matches[0]?.costingCardValues);
  });
}

export async function ensureCardValues(
  page: Page,
  quotationId: string,
  expectedPartNos?: readonly string[],
): Promise<any> {
  let quotation: any;
  await expect.poll(async () => {
    const response = await page.request.post(
      `${BACKEND_URL}/api/cpq/quotations/${quotationId}/ensure-card-values`,
    );
    quotation = await jsonOrThrow(response, `物化报价单 ${quotationId} 的报价/核价卡片值`);
    if (!expectedPartNos) return !quotation?.cardValuesWarming;
    return hasCompleteFixtureCardValues(quotation, expectedPartNos);
  }, {
    message: expectedPartNos
      ? `报价单 ${quotationId} 必须完成 ${expectedPartNos.join(', ')} 的报价/核价卡片物化`
      : `报价单 ${quotationId} 的 ensure-card-values 必须结束 warming`,
    timeout: 30_000,
  }).toBe(true);
  return quotation;
}

type CardSide = 'QUOTE' | 'COSTING';

function assertDecimalEquals(actual: unknown, expected: string, label: string): void {
  expect(typeof actual, `${label} 必须是 decimal string`).toBe('string');
  let equal = false;
  try {
    equal = new Decimal(String(actual)).eq(new Decimal(expected));
  } catch {
    equal = false;
  }
  expect(equal, `${label} 必须 Decimal 精确等于 ${expected}，实际 ${String(actual)}`).toBe(true);
}

function cardResultValues(raw: unknown, sideLabel: string): string[] {
  if (typeof raw !== 'string' && (raw === null || typeof raw !== 'object')) {
    throw new Error(`${sideLabel} 缺少卡片值快照`);
  }
  const values = typeof raw === 'string' ? JSON.parse(raw) : raw;
  const tabNames = (values?.tabs ?? []).map((item: any) => item?.tabName);
  expect(tabNames, `${sideLabel} 必须精确包含两个 Stage H 页签`).toEqual([...PRECISION_TABS]);
  const tab = (values?.tabs ?? []).find((item: any) => item?.tabName === PRECISION_FIXTURE.tabName);
  if (!tab) throw new Error(`${sideLabel} 快照缺少页签「${PRECISION_FIXTURE.tabName}」`);
  expect(tab?.baseRows?.length, `${sideLabel} 精度页签 baseRows`).toBe(2);
  const stabilityTab = (values?.tabs ?? []).find((item: any) => item?.tabName === PRECISION_TABS[1]);
  expect(stabilityTab?.baseRows?.length, `${sideLabel} 稳定页签 baseRows`).toBe(2);
  const results = (tab.formulaResults ?? []).map((row: any) => {
    const result = row?.values?.[PRECISION_FIXTURE.resultField];
    if (typeof result !== 'string') {
      throw new Error(`${sideLabel} ${PRECISION_FIXTURE.resultField} 必须是 decimal string，实际 ${typeof result}: ${String(result)}`);
    }
    return result;
  });
  expect(results.length, `${sideLabel} 精度公式结果行数`).toBe(2);
  return results;
}

export function snapshotResultValues(quotation: any, side: CardSide, productPartNo: string): string[] {
  const matches = (quotation?.lineItems ?? []).filter((item: any) => item?.productPartNo === productPartNo);
  expect(matches, `${side} 必须唯一命中 line productPartNo=${productPartNo}`).toHaveLength(1);
  const raw = matches[0]?.[side === 'QUOTE' ? 'quoteCardValues' : 'costingCardValues'];
  return cardResultValues(raw, `${side}/${productPartNo}`);
}

export interface FixtureLineFingerprint {
  id: string;
  partNo: string;
  compositeType: string;
  sortOrder: number;
  parentLineItemId: string | null;
  quoteValues: string[];
  costingValues: string[];
}

export const CARD_SNAPSHOT_FINGERPRINT_EXCLUDED_PATHS: readonly string[] = [];

export interface CardSnapshotSideFingerprint {
  raw: string;
  canonical: string;
  keyPaths: string[];
  tabs: Array<{
    componentId: string;
    tabName: string;
    componentType: string;
    baseRowKeys: string[];
    editRowKeys: string[];
    formulaRowKeys: string[];
    resolvedRowKeys: string[];
    subtotalPresent: boolean;
    subtotal: unknown;
    subtotalByColumnPresent: boolean;
    subtotalByColumn: unknown;
  }>;
}

export interface QuotationCardSnapshotFingerprint {
  lineItemId: string;
  productPartNo: string;
  quote: CardSnapshotSideFingerprint;
  costing: CardSnapshotSideFingerprint;
}

function canonicalizeSnapshot(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(canonicalizeSnapshot);
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>)
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([key, child]) => [key, canonicalizeSnapshot(child)]),
    );
  }
  return value;
}

function collectSnapshotKeyPaths(value: unknown, path = '$', output: string[] = []): string[] {
  if (Array.isArray(value)) {
    value.forEach((child, index) => collectSnapshotKeyPaths(child, `${path}[${index}]`, output));
  } else if (value !== null && typeof value === 'object') {
    for (const [key, child] of Object.entries(value as Record<string, unknown>)) {
      const childPath = `${path}.${key}`;
      output.push(childPath);
      collectSnapshotKeyPaths(child, childPath, output);
    }
  }
  return output;
}

function requiredArray(container: any, key: string, label: string): any[] {
  const value = container?.[key];
  if (!Array.isArray(value)) throw new Error(`${label}.${key} 必须是数组`);
  return value;
}

function cardSnapshotSideFingerprint(raw: unknown, label: string): CardSnapshotSideFingerprint {
  if (typeof raw !== 'string') throw new Error(`${label} 必须是原始 JSON string`);
  const parsed = JSON.parse(raw);
  const tabs = requiredArray(parsed, 'tabs', label).map((tab: any, tabIndex: number) => {
    const tabLabel = `${label}.tabs[${tabIndex}]`;
    const baseRows = requiredArray(tab, 'baseRows', tabLabel);
    const editRows = requiredArray(tab, 'editRows', tabLabel);
    const formulaResults = requiredArray(tab, 'formulaResults', tabLabel);
    const resolvedRows = requiredArray(tab, 'resolvedRows', tabLabel);
    expect(typeof tab?.componentId, `${tabLabel}.componentId`).toBe('string');
    expect(typeof tab?.tabName, `${tabLabel}.tabName`).toBe('string');
    expect(typeof tab?.componentType, `${tabLabel}.componentType`).toBe('string');

    const baseRowKeys = baseRows.map((row: any, rowIndex: number) => {
      expect(row?.driverRow && typeof row.driverRow === 'object', `${tabLabel}.baseRows[${rowIndex}].driverRow`).toBe(true);
      expect(row?.basicDataValues && typeof row.basicDataValues === 'object', `${tabLabel}.baseRows[${rowIndex}].basicDataValues`).toBe(true);
      return String(row.driverRow?.['行号'] ?? '');
    });
    const keyedRows = (rows: any[], key: string) => rows.map((row: any, rowIndex: number) => {
      expect(typeof row?.rowKey, `${tabLabel}.${key}[${rowIndex}].rowKey`).toBe('string');
      expect(row?.values && typeof row.values === 'object', `${tabLabel}.${key}[${rowIndex}].values`).toBe(true);
      return String(row.rowKey);
    });
    const resolvedRowKeys = resolvedRows.map((row: any, rowIndex: number) => {
      expect(row && typeof row === 'object', `${tabLabel}.resolvedRows[${rowIndex}]`).toBe(true);
      return String(row?.['行号'] ?? '');
    });

    return {
      componentId: tab.componentId,
      tabName: tab.tabName,
      componentType: tab.componentType,
      baseRowKeys,
      editRowKeys: keyedRows(editRows, 'editRows'),
      formulaRowKeys: keyedRows(formulaResults, 'formulaResults'),
      resolvedRowKeys,
      subtotalPresent: Object.hasOwn(tab, 'subtotal'),
      subtotal: tab.subtotal,
      subtotalByColumnPresent: Object.hasOwn(tab, 'subtotalByColumn'),
      subtotalByColumn: tab.subtotalByColumn,
    };
  });

  return {
    raw,
    canonical: JSON.stringify(canonicalizeSnapshot(parsed)),
    keyPaths: collectSnapshotKeyPaths(parsed).sort(),
    tabs,
  };
}

/**
 * 提交前后卡片值不可被状态流转改写。指纹覆盖快照中的全部键和值，数组顺序原样保留；
 * 同时保留 raw string 做最严格比较。本 Stage H 契约没有可排除的 volatile metadata。
 */
export function quotationCardSnapshotFingerprint(quotation: any): QuotationCardSnapshotFingerprint[] {
  expect(CARD_SNAPSHOT_FINGERPRINT_EXCLUDED_PATHS, '完整快照指纹不得排除业务或元数据字段').toEqual([]);
  return (quotation?.lineItems ?? []).map((line: any, lineIndex: number) => {
    if (line?.id == null || line?.productPartNo == null) {
      throw new Error(`lineItems[${lineIndex}] 缺少 id/productPartNo`);
    }
    return {
      lineItemId: String(line.id),
      productPartNo: String(line.productPartNo),
      quote: cardSnapshotSideFingerprint(line.quoteCardValues, `lineItems[${lineIndex}].quoteCardValues`),
      costing: cardSnapshotSideFingerprint(line.costingCardValues, `lineItems[${lineIndex}].costingCardValues`),
    };
  });
}

export function assertFixtureQuotationLines(
  quotation: any,
  composite: boolean,
  expectedStatus = 'DRAFT',
  expectedPrecisionValues: readonly DecimalString[] = PRECISION_SENTINELS,
  expectedCostingPrecisionValues: readonly DecimalString[] = expectedPrecisionValues,
): FixtureLineFingerprint[] {
  expect(quotation?.status, '报价单状态').toBe(expectedStatus);
  const expectedParts = composite
    ? [PRECISION_PARTS.composite, PRECISION_PARTS.partOne, PRECISION_PARTS.partTwo]
    : [PRECISION_PARTS.simple];
  const lines: any[] = quotation?.lineItems ?? [];
  expect(lines.map((line) => line?.productPartNo), 'Stage H line identity 与顺序').toEqual(expectedParts);
  expect(new Set(lines.map((line) => line?.id)).size, 'line id 必须唯一').toBe(expectedParts.length);

  const parentId = composite ? String(lines[0]?.id) : null;
  const fingerprints = lines.map((line, index): FixtureLineFingerprint => {
    const expectedType = composite ? (index === 0 ? 'COMPOSITE' : 'PART') : 'SIMPLE';
    expect(line?.compositeType, `${line?.productPartNo} compositeType`).toBe(expectedType);
    expect(line?.sortOrder, `${line?.productPartNo} sortOrder`).toBe(index);
    expect(line?.parentLineItemId ?? null, `${line?.productPartNo} parentLineItemId`).toBe(index === 0 ? null : parentId);
    const quoteValues = snapshotResultValues(quotation, 'QUOTE', line.productPartNo);
    const costingValues = snapshotResultValues(quotation, 'COSTING', line.productPartNo);
    expect(expectedPrecisionValues, '每条 fixture line 必须提供两个报价精度工作值').toHaveLength(2);
    expect(expectedCostingPrecisionValues, '每条 fixture line 必须提供两个核价精度工作值').toHaveLength(2);
    expectedPrecisionValues.forEach((expected, rowIndex) => {
      assertDecimalEquals(quoteValues[rowIndex], expected, `${line.productPartNo} quote row ${rowIndex + 1}`);
    });
    expectedCostingPrecisionValues.forEach((expected, rowIndex) => {
      assertDecimalEquals(costingValues[rowIndex], expected, `${line.productPartNo} costing row ${rowIndex + 1}`);
    });
    return {
      id: String(line.id),
      partNo: String(line.productPartNo),
      compositeType: String(line.compositeType),
      sortOrder: Number(line.sortOrder),
      parentLineItemId: line.parentLineItemId == null ? null : String(line.parentLineItemId),
      quoteValues,
      costingValues,
    };
  });
  return fingerprints;
}

export interface FixtureCardState {
  tabNames: string[];
  rowCounts: Record<string, number>;
  precisionInputs: string[];
  precisionResults: string[];
  stabilityValues: string[];
}

function normalizeDisplayedDecimal(value: string): string {
  return value.replace(/,/g, '').replace(/^¥\s*/, '').trim();
}

async function cellValue(cell: Locator): Promise<{ value: string; editable: boolean }> {
  const input = cell.locator('input').nth(0);
  if (await input.count()) return { value: await input.inputValue(), editable: true };
  return { value: normalizeDisplayedDecimal(await cell.innerText()), editable: false };
}

function expectedDisplay(value: string): string {
  return new Decimal(value).toDecimalPlaces(9, Decimal.ROUND_HALF_UP).toFixed();
}

export async function assertAllTabsSettled(
  page: Page,
  card: Locator,
  options: {
    expectedWorkValues?: readonly DecimalString[];
    exactRowsPerTab?: number;
    verifyStableRows?: boolean;
  } = {},
): Promise<FixtureCardState> {
  const expectedWorkValues = options.expectedWorkValues ?? PRECISION_SENTINELS;
  const names = (await card.locator('button.qt-tab-btn').allInnerTexts()).map((name) => name.trim()).filter(Boolean);
  expect(names, '当前产品卡必须精确包含 Stage H 两个页签').toEqual([...PRECISION_TABS]);
  const state: FixtureCardState = {
    tabNames: names,
    rowCounts: {},
    precisionInputs: [],
    precisionResults: [],
    stabilityValues: [],
  };

  for (const name of names) {
    await openCardTab(page, card, name);
    const table = await activeTable(card);
    const rowCount = await table.locator('tbody tr').count();
    expect(rowCount, `页签「${name}」不应空白`).toBeGreaterThan(0);
    if (options.exactRowsPerTab != null) {
      expect(rowCount, `页签「${name}」确定性行数`).toBe(options.exactRowsPerTab);
    }
    await expect(page.getByText('加载中', { exact: false }), `页签「${name}」加载中应为 0`).toHaveCount(0);
    state.rowCounts[name] = rowCount;

    if (name === PRECISION_FIXTURE.tabName) {
      const inputIndex = await columnIndex(card, PRECISION_FIXTURE.inputField);
      const resultIndex = await columnIndex(card, PRECISION_FIXTURE.resultField);
      const rows = table.locator('tbody tr');
      for (let rowIndex = 0; rowIndex < rowCount; rowIndex += 1) {
        const input = await cellValue(rows.nth(rowIndex).locator('td').nth(inputIndex));
        const result = await cellValue(rows.nth(rowIndex).locator('td').nth(resultIndex));
        const expectedIndex = rowIndex % expectedWorkValues.length;
        const work = expectedWorkValues[expectedIndex];
        const display = expectedDisplay(work);
        assertDecimalEquals(input.value, input.editable ? work : display, `精度输入 row ${rowIndex + 1}`);
        assertDecimalEquals(result.value, display, `精度结果 row ${rowIndex + 1}`);
        expect(result.value, `精度结果 row ${rowIndex + 1} 必须按 HALF_UP 最多显示9位`).toBe(display);
        state.precisionInputs.push(input.value);
        state.precisionResults.push(result.value);
      }
    } else if (name === PRECISION_TABS[1]) {
      const stableIndex = await columnIndex(card, '稳定值');
      const rows = table.locator('tbody tr');
      for (let rowIndex = 0; rowIndex < rowCount; rowIndex += 1) {
        const { value } = await cellValue(rows.nth(rowIndex).locator('td').nth(stableIndex));
        expect(value, `稳定页签 row ${rowIndex + 1}`).toBe(STABILITY_SENTINELS[rowIndex % STABILITY_SENTINELS.length]);
        state.stabilityValues.push(value);
      }
    }
  }

  if (options.verifyStableRows) {
    for (const name of names) {
      await openCardTab(page, card, name);
      const actualRows = await (await activeTable(card)).locator('tbody tr').count();
      expect(actualRows, `页签「${name}」往返切换不得累加/丢失行`).toBe(state.rowCounts[name]);
    }
  }
  return state;
}

export async function advanceToSubmit(page: Page): Promise<void> {
  for (let i = 0; i < 4; i += 1) {
    const submit = page.getByRole('button', { name: /提交审批$/ });
    if (await submit.count() === 1 && await submit.isVisible().catch(() => false)) return;
    const next = page.getByRole('button', { name: /下一步/ }).first();
    await expect(next, '应可继续到提交审批步骤').toBeEnabled({ timeout: 20_000 });
    await next.click();
    await page.waitForTimeout(300);
  }
  const submit = page.getByRole('button', { name: /提交审批$/ });
  await expect(submit, '提交步骤必须唯一存在提交审批按钮').toHaveCount(1);
  await expect(submit).toBeVisible();
}

export function rewriteQuoteCardEditResponse(body: any, from: DecimalString, to: DecimalString): any {
  const envelopeData = body?.data ?? body;
  const raw = envelopeData?.quoteCardValues;
  if (typeof raw !== 'string') throw new Error('quote-card-edit 响应缺少 string quoteCardValues，无法执行 TC-076');
  const cardValues = JSON.parse(raw);
  const tab = (cardValues?.tabs ?? []).find((item: any) => item?.tabName === PRECISION_FIXTURE.tabName);
  if (!tab) throw new Error(`quote-card-edit 响应缺少页签「${PRECISION_FIXTURE.tabName}」`);
  let changed = 0;
  for (const row of tab.formulaResults ?? []) {
    const values = row?.values;
    if (values?.[PRECISION_FIXTURE.resultField] === from) {
      values[PRECISION_FIXTURE.resultField] = to;
      changed += 1;
      break;
    }
  }
  if (changed !== 1) throw new Error(`TC-076 应精确篡改 1 个工作值，实际 ${changed}`);
  envelopeData.quoteCardValues = JSON.stringify(cardValues);
  return body;
}
