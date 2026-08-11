/**
 * task-0810 TC-073~076: 真实前后端精度联调。
 *
 * 运行前置：Stage H 必须加载确定性 SIMPLE 种子，并通过
 * PW_PRECISION_SEED_QUOTATION_NO 明确指定 quotationNo；种子契约见 fixtures/precision.ts。
 * 本文件只 copy 种子，不修改或删除 seed。copy 留在隔离库中，由协调者在整轮结束后
 * drop/restore 隔离库；测试内不得 DELETE 已提交报价/核价。除 TC-076 按需求篡改后端返回的
 * 第 10~12 位外，所有 API 都连真实后端；提交阻断和恢复均由真实 reconcile/submit 完成。
 */
import { expect, test, type Route } from '@playwright/test';
import Decimal from 'decimal.js';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';
import {
  PRECISION_CASES,
  PRECISION_FIXTURE,
  PRECISION_PARTS,
  PRECISION_ROW_KEYS,
  PRECISION_SENTINELS,
  advanceToSubmit,
  assertAllTabsSettled,
  assertFixtureQuotationLines,
  assertVisibleProductCards,
  assertExactDisplay,
  copyPrecisionSeed,
  editPrecisionValue,
  ensureCardValues,
  getQuotation,
  openCardTab,
  openDetail,
  openQuotationStep2,
  productCardByPartNo,
  quotationCardSnapshotFingerprint,
  readFieldText,
  readInputValue,
  rewriteQuoteCardEditResponse,
  saveDraft,
  snapshotResultValues,
} from './fixtures/precision';

test.describe.configure({ mode: 'serial' });
test.setTimeout(300_000);

let backendUp = false;
let createdQuotationId: string | null = null;

test.beforeAll(async () => {
  backendUp = await isBackendUp();
  if (!backendUp) {
    throw new Error('后端未启动，Stage H 精度联调环境未就绪；TC-073~076 不允许跳过');
  }
});

function requireCreatedQuotation(): string {
  if (!createdQuotationId) throw new Error('TC-073 尚未创建精度测试 copy，串行前置失败');
  return createdQuotationId;
}

function cardValuesFromEditResponse(body: any): string[] {
  const data = body?.data ?? body;
  return snapshotResultValues({
    lineItems: [{
      productPartNo: PRECISION_PARTS.simple,
      quoteCardValues: data?.quoteCardValues,
    }],
  }, 'QUOTE', PRECISION_PARTS.simple);
}

function assertDecimalToken(actual: unknown, expected: string, label: string, canonical: boolean): void {
  expect(typeof actual, `${label} 必须是 JSON string`).toBe('string');
  const text = String(actual);
  expect(text, `${label} 必须是普通十进制，禁止科学计数法`).toMatch(/^-?(?:0|[1-9]\d*)(?:\.\d+)?$/);
  expect((text.split('.')[1] ?? '').length, `${label} 小数位不得超过12位`).toBeLessThanOrEqual(12);
  expect(new Decimal(text).eq(new Decimal(expected)), `${label} 必须 Decimal 精确等于 ${expected}`).toBe(true);
  if (canonical) {
    const canonicalText = new Decimal(text).isZero() ? '0' : new Decimal(text).toFixed();
    expect(text, `${label} 必须遵守 D-5 canonical decimal string`).toBe(canonicalText);
  }
}

function canonicalDecimalValues(values: unknown[], label: string): string[] {
  return values.map((value, index) => {
    assertDecimalToken(value, String(value), `${label}[${index}]`, true);
    return new Decimal(String(value)).toFixed();
  });
}

function canonicalExpected(values: readonly string[]): string[] {
  return values.map((value) => new Decimal(value).toFixed());
}

test('TC-073 SIMPLE: 创建/填写/保存/刷新/重开保持12位string，正负边界与大数按HALF_UP显示9位', async ({ page }) => {
  await loginAsAdmin(page);
  createdQuotationId = await copyPrecisionSeed(page);

  await openQuotationStep2(page, createdQuotationId);
  await assertVisibleProductCards(page, [PRECISION_PARTS.simple]);
  let card = await productCardByPartNo(page, PRECISION_PARTS.simple);
  await openCardTab(page, card);

  await editPrecisionValue(page, card, PRECISION_FIXTURE.positiveRowIndex, PRECISION_CASES.positiveWork);
  const negativeEdit = await editPrecisionValue(
    page,
    card,
    PRECISION_FIXTURE.negativeRowIndex,
    PRECISION_CASES.negativeWork,
  );
  await expect(page.getByText('加载中', { exact: false }), '填写完成后加载中应为0').toHaveCount(0);
  const editBody = await negativeEdit.response.json();
  const editResults = cardValuesFromEditResponse(editBody);
  assertDecimalToken(editResults[PRECISION_FIXTURE.positiveRowIndex], PRECISION_CASES.positiveWork, '正数编辑公式结果', true);
  assertDecimalToken(editResults[PRECISION_FIXTURE.negativeRowIndex], PRECISION_CASES.negativeWork, '负数编辑公式结果', true);

  assertExactDisplay(
    await readFieldText(card, PRECISION_FIXTURE.positiveRowIndex),
    PRECISION_CASES.positiveDisplay,
  );
  assertExactDisplay(
    await readFieldText(card, PRECISION_FIXTURE.negativeRowIndex),
    PRECISION_CASES.negativeDisplay,
  );

  await saveDraft(page);
  await expect(page.getByText('加载中', { exact: false }), '保存完成后加载中应为0').toHaveCount(0);

  // 浏览器刷新：React 受控 input state 必须仍是 12 位字符串，公式 DOM 仍是明确的 9 位边界结果。
  await page.reload();
  await openQuotationStep2(page, createdQuotationId);
  await assertVisibleProductCards(page, [PRECISION_PARTS.simple]);
  card = await productCardByPartNo(page, PRECISION_PARTS.simple);
  await openCardTab(page, card);
  assertDecimalToken(await readInputValue(card, PRECISION_FIXTURE.positiveRowIndex), PRECISION_CASES.positiveWork, '刷新后正数输入', false);
  assertDecimalToken(await readInputValue(card, PRECISION_FIXTURE.negativeRowIndex), PRECISION_CASES.negativeWork, '刷新后负数输入', false);
  assertExactDisplay(await readFieldText(card, PRECISION_FIXTURE.positiveRowIndex), PRECISION_CASES.positiveDisplay);
  assertExactDisplay(await readFieldText(card, PRECISION_FIXTURE.negativeRowIndex), PRECISION_CASES.negativeDisplay);

  // 大数也必须走真实编辑、保存、刷新和 GET API 往返，排除只验证格式函数的假覆盖。
  const largeEdit = await editPrecisionValue(
    page,
    card,
    PRECISION_FIXTURE.positiveRowIndex,
    PRECISION_CASES.largeWork,
  );
  const largeResults = cardValuesFromEditResponse(await largeEdit.response.json());
  assertDecimalToken(largeResults[PRECISION_FIXTURE.positiveRowIndex], PRECISION_CASES.largeWork, '大数编辑公式结果', true);
  assertExactDisplay(
    await readFieldText(card, PRECISION_FIXTURE.positiveRowIndex),
    PRECISION_CASES.largeDisplay,
  );
  await saveDraft(page);
  await page.reload();
  await openQuotationStep2(page, createdQuotationId);
  card = await productCardByPartNo(page, PRECISION_PARTS.simple);
  await openCardTab(page, card);
  assertDecimalToken(await readInputValue(card, PRECISION_FIXTURE.positiveRowIndex), PRECISION_CASES.largeWork, '刷新后大数输入', false);
  assertExactDisplay(
    await readFieldText(card, PRECISION_FIXTURE.positiveRowIndex),
    PRECISION_CASES.largeDisplay,
  );
  await expect.poll(async () => {
    const quotation = await getQuotation(page, createdQuotationId!);
    return canonicalDecimalValues(snapshotResultValues(quotation, 'QUOTE', PRECISION_PARTS.simple), '大数保存快照');
  }, { timeout: 30_000, message: '大数应以最多12位 canonical decimal string 完成保存/刷新/API 往返' }).toEqual(
    canonicalExpected([PRECISION_CASES.largeWork, PRECISION_CASES.negativeWork]),
  );

  // 恢复报价侧最终边界值并显式执行真实 warm。quote-card-edit 不得覆盖独立的核价输入。
  const restoredEdit = await editPrecisionValue(
    page,
    card,
    PRECISION_FIXTURE.positiveRowIndex,
    PRECISION_CASES.positiveWork,
  );
  const restoredResults = cardValuesFromEditResponse(await restoredEdit.response.json());
  assertDecimalToken(restoredResults[PRECISION_FIXTURE.positiveRowIndex], PRECISION_CASES.positiveWork, '恢复后的正数公式结果', true);
  await saveDraft(page);
  await ensureCardValues(page, createdQuotationId);
  const finalSnapshots = {
    quote: canonicalExpected([PRECISION_CASES.positiveWork, PRECISION_CASES.negativeWork]),
    costing: canonicalExpected(PRECISION_SENTINELS),
  };
  await expect.poll(async () => {
    try {
      const quotation = await getQuotation(page, createdQuotationId!);
      return {
        quote: canonicalDecimalValues(snapshotResultValues(quotation, 'QUOTE', PRECISION_PARTS.simple), '最终报价快照'),
        costing: canonicalDecimalValues(snapshotResultValues(quotation, 'COSTING', PRECISION_PARTS.simple), '最终核价快照'),
      };
    } catch (error) {
      return { pending: error instanceof Error ? error.message : String(error) };
    }
  }, { timeout: 30_000, message: 'ensure-card-values 后报价应保留编辑值，核价应保留独立 seed 值且均为12位工作值' }).toEqual(finalSnapshots);

  // 离开再重开，排除仅存在于页面内存的假通过。
  await page.goto('/quotations');
  await openQuotationStep2(page, createdQuotationId);
  card = await productCardByPartNo(page, PRECISION_PARTS.simple);
  await openCardTab(page, card);
  assertDecimalToken(await readInputValue(card, PRECISION_FIXTURE.positiveRowIndex), PRECISION_CASES.positiveWork, '重开后正数输入', false);
  assertDecimalToken(await readInputValue(card, PRECISION_FIXTURE.negativeRowIndex), PRECISION_CASES.negativeWork, '重开后负数输入', false);

  await expect.poll(async () => {
    const quotation = await getQuotation(page, createdQuotationId!);
    return canonicalDecimalValues(snapshotResultValues(quotation, 'QUOTE', PRECISION_PARTS.simple), '重开后的报价快照');
  }, { timeout: 30_000, message: 'GET quotation 的 quoteCardValues 应保存两个最多12位 canonical 工作值' }).toEqual(
    canonicalExpected([PRECISION_CASES.positiveWork, PRECISION_CASES.negativeWork]),
  );
  await expect(page.getByText('加载中', { exact: false })).toHaveCount(0);
});

test('TC-074 三视图: 报价编辑/详情报价逐值一致，详情核价独立验证精度，所有Tab加载中=0', async ({ page }) => {
  const quotationId = requireCreatedQuotation();
  await loginAsAdmin(page);

  // 视图1：报价编辑。
  await openQuotationStep2(page, quotationId);
  await assertVisibleProductCards(page, [PRECISION_PARTS.simple]);
  let card = await productCardByPartNo(page, PRECISION_PARTS.simple);
  await openCardTab(page, card);
  const editValues = [
    await readFieldText(card, PRECISION_FIXTURE.positiveRowIndex),
    await readFieldText(card, PRECISION_FIXTURE.negativeRowIndex),
  ];
  expect(editValues).toEqual([PRECISION_CASES.positiveDisplay, PRECISION_CASES.negativeDisplay]);
  await assertAllTabsSettled(page, card, {
    expectedWorkValues: [PRECISION_CASES.positiveWork, PRECISION_CASES.negativeWork],
    exactRowsPerTab: 2,
    verifyStableRows: true,
  });

  // 视图2：详情报价。
  await openDetail(page, quotationId);
  await assertVisibleProductCards(page, [PRECISION_PARTS.simple]);
  card = await productCardByPartNo(page, PRECISION_PARTS.simple);
  await openCardTab(page, card);
  const detailQuoteValues = [
    await readFieldText(card, PRECISION_FIXTURE.positiveRowIndex),
    await readFieldText(card, PRECISION_FIXTURE.negativeRowIndex),
  ];
  expect(detailQuoteValues).toEqual(editValues);
  await assertAllTabsSettled(page, card, {
    expectedWorkValues: [PRECISION_CASES.positiveWork, PRECISION_CASES.negativeWork],
    exactRowsPerTab: 2,
    verifyStableRows: true,
  });

  // 视图3：详情核价。
  const costing = page.locator('.ant-segmented-item').filter({ hasText: '核价单' }).first();
  await expect(costing, '确定性 seed 必须配置核价卡片').toBeVisible({ timeout: 20_000 });
  await costing.click();
  await expect(page.getByText('加载中', { exact: false })).toHaveCount(0, { timeout: 30_000 });
  card = await productCardByPartNo(page, PRECISION_PARTS.simple);
  await openCardTab(page, card);
  const detailCostingValues = [
    await readFieldText(card, PRECISION_FIXTURE.positiveRowIndex),
    await readFieldText(card, PRECISION_FIXTURE.negativeRowIndex),
  ];
  expect(detailCostingValues).toEqual(['1.234567891', PRECISION_CASES.largeDisplay]);
  await assertAllTabsSettled(page, card, {
    expectedWorkValues: PRECISION_SENTINELS,
    exactRowsPerTab: 2,
    verifyStableRows: true,
  });

  const quotation = await getQuotation(page, quotationId);
  expect(canonicalDecimalValues(
    snapshotResultValues(quotation, 'QUOTE', PRECISION_PARTS.simple),
    'TC-074 报价快照',
  )).toEqual(canonicalExpected([PRECISION_CASES.positiveWork, PRECISION_CASES.negativeWork]));
  expect(canonicalDecimalValues(
    snapshotResultValues(quotation, 'COSTING', PRECISION_PARTS.simple),
    'TC-074 核价快照',
  )).toEqual(canonicalExpected(PRECISION_SENTINELS));
});

test('TC-076 第10~12位差异: route篡改进入真实reconcile，真实submit阻断；unroute后恢复提交', async ({ page }) => {
  const quotationId = requireCreatedQuotation();
  await loginAsAdmin(page);
  await openQuotationStep2(page, quotationId);
  await assertVisibleProductCards(page, [PRECISION_PARTS.simple]);
  let card = await productCardByPartNo(page, PRECISION_PARTS.simple);
  await openCardTab(page, card);

  const beforeMutation = await getQuotation(page, quotationId);
  const simpleLines = (beforeMutation?.lineItems ?? []).filter(
    (line: any) => line?.productPartNo === PRECISION_PARTS.simple,
  );
  expect(simpleLines, 'TC-076 必须唯一命中 SIMPLE line').toHaveLength(1);
  const lineItemId = String(simpleLines[0].id);

  const [frontendInteger, frontendFraction = ''] = PRECISION_CASES.reconcileFrontend.split('.');
  const [backendInteger, backendFraction = ''] = PRECISION_CASES.reconcileBackend.split('.');
  expect(frontendInteger, 'route 篡改不得改变整数部分').toBe(backendInteger);
  expect(frontendFraction, 'route 前端 sentinel 必须精确 12 位').toHaveLength(12);
  expect(backendFraction, 'route 后端 sentinel 必须精确 12 位').toHaveLength(12);
  expect(frontendFraction.slice(0, 9), 'route 篡改不得改变前 9 位小数').toBe(backendFraction.slice(0, 9));
  expect(frontendFraction.slice(9), 'route 必须只让第 10~12 位产生差异').not.toBe(backendFraction.slice(9));

  let routeMutationCount = 0;
  const mutationHandler = async (route: Route) => {
    const response = await route.fetch();
    const body = await response.json();
    rewriteQuoteCardEditResponse(body, PRECISION_CASES.reconcileFrontend, PRECISION_CASES.reconcileBackend);
    routeMutationCount += 1;
    await route.fulfill({
      response,
      contentType: 'application/json; charset=utf-8',
      body: JSON.stringify(body),
    });
  };
  await page.route('**/api/cpq/quotations/line-items/*/quote-card-edit', mutationHandler);

  const reportPromise = page.waitForResponse((response) =>
    response.request().method() === 'POST' && response.url().includes('/reconcile-report'),
  );
  await editPrecisionValue(
    page,
    card,
    PRECISION_FIXTURE.positiveRowIndex,
    PRECISION_CASES.reconcileFrontend,
  );
  const reportResponse = await reportPromise;
  expect(reportResponse.status(), 'reconcile-report HTTP status').toBe(202);
  const reportBody = await reportResponse.json();
  expect(reportBody?.code, 'reconcile-report API code').toBe(202);
  expect(reportBody?.message, 'reconcile-report API message').toBe('accepted');
  expect(reportBody?.data?.recorded, '本轮必须记录 1 个精度差异').toBe(1);
  const report = reportResponse.request().postDataJSON();
  expect(routeMutationCount, 'page.route 必须实际篡改一次真实响应').toBe(1);
  const diff = (report?.diffs ?? []).find((item: any) => item?.fieldName === PRECISION_FIXTURE.resultField);
  expect(diff, '篡改后的第10~12位差异必须进入 reconcile-report').toBeDefined();
  expect(diff.tabName).toBe(PRECISION_FIXTURE.tabName);
  expect(diff.rowKey).toBe(PRECISION_ROW_KEYS[0]);
  expect(diff.fieldName).toBe(PRECISION_FIXTURE.resultField);
  expect(diff.frontendValue).toBe(PRECISION_CASES.reconcileFrontend);
  expect(diff.backendValue).toBe(PRECISION_CASES.reconcileBackend);
  expect(typeof diff.frontendValue).toBe('string');
  expect(typeof diff.backendValue).toBe('string');
  // 既有 S2 契约：差异态以警告替代数值，并在 tooltip 保留两端完整工作值。
  const mismatchIndicator = card
    .locator('table.qt-cost-table:visible tbody tr')
    .nth(PRECISION_FIXTURE.positiveRowIndex)
    .locator('.qt-formula-cell-error');
  await expect(mismatchIndicator, '第10~12位差异必须显示既有 S2 警告标记').toHaveText('⚠');
  const mismatchTooltip = await mismatchIndicator.getAttribute('title');
  expect(mismatchTooltip, 'S2 tooltip 必须保留前端完整12位工作值').toContain(
    `前端 ${PRECISION_CASES.reconcileFrontend}`,
  );
  expect(mismatchTooltip, 'S2 tooltip 必须保留后端完整12位工作值').toContain(
    `后端 ${PRECISION_CASES.reconcileBackend}`,
  );
  const reconciledDisplays = [
    PRECISION_CASES.reconcileFrontend,
    PRECISION_CASES.reconcileBackend,
  ].map(value => new Decimal(value).toDecimalPlaces(9, Decimal.ROUND_HALF_UP).toFixed());
  expect(reconciledDisplays, '两端工作值虽第10~12位不同，HALF_UP 9位显示必须相同').toEqual([
    PRECISION_CASES.reconcileDisplay,
    PRECISION_CASES.reconcileDisplay,
  ]);

  await advanceToSubmit(page);
  const blockedSubmitPromise = page.waitForResponse((response) =>
    response.request().method() === 'POST' && /\/quotations\/[^/]+\/submit(?:\?|$)/.test(response.url()),
  );
  await page.getByRole('button', { name: /提交审批$/ }).click();
  const blockedSubmit = await blockedSubmitPromise;
  expect(blockedSubmit.status(), '真实 submit 应按12位对账返回409').toBe(409);
  const blockedBody = await blockedSubmit.json();
  expect(blockedBody?.code, '阻断 submit API code').toBe(409);
  const blockedPayload = blockedBody?.data;
  expect(blockedPayload?.reason).toBe('RECONCILE_PENDING');
  expect(Array.isArray(blockedPayload?.conflicts), '409 必须返回 conflicts 数组').toBe(true);
  const conflict = (blockedPayload?.conflicts ?? []).find((item: any) =>
    item?.lineItemId === lineItemId
      && item?.productPartNo === PRECISION_PARTS.simple
      && item?.tabName === PRECISION_FIXTURE.tabName
      && item?.rowKey === PRECISION_ROW_KEYS[0]
      && item?.fieldName === PRECISION_FIXTURE.resultField,
  );
  expect(conflict, '409 conflicts 必须精确包含本次 SIMPLE 精度差异').toBeDefined();
  expect(conflict.frontendValue).toBe(PRECISION_CASES.reconcileFrontend);
  expect(conflict.backendValue).toBe(PRECISION_CASES.reconcileBackend);
  expect(typeof conflict.frontendValue, '409 frontendValue 必须保持 string token').toBe('string');
  expect(typeof conflict.backendValue, '409 backendValue 必须保持 string token').toBe('string');
  await expect(page.getByText('提交校验未通过：前后端算值不一致')).toBeVisible({ timeout: 20_000 });
  await expect(page.getByText(PRECISION_CASES.reconcileFrontend, { exact: true })).toBeVisible();
  await expect(page.getByText(PRECISION_CASES.reconcileBackend, { exact: true })).toBeVisible();

  // 关闭差异抽屉并移除唯一的响应篡改；重新从真实GET加载，再编辑触发空diff对账清除pending。
  await page.locator('.ant-drawer').filter({ hasText: '提交校验未通过' }).locator('button.ant-drawer-close').click();
  await page.unroute('**/api/cpq/quotations/line-items/*/quote-card-edit', mutationHandler);
  await openQuotationStep2(page, quotationId);
  await assertVisibleProductCards(page, [PRECISION_PARTS.simple]);
  card = await productCardByPartNo(page, PRECISION_PARTS.simple);
  await openCardTab(page, card);

  const recoveredReportPromise = page.waitForResponse((response) =>
    response.request().method() === 'POST' && response.url().includes('/reconcile-report'),
  );
  await editPrecisionValue(
    page,
    card,
    PRECISION_FIXTURE.positiveRowIndex,
    PRECISION_CASES.recoveredWork,
  );
  const recoveredReportResponse = await recoveredReportPromise;
  expect(recoveredReportResponse.status(), '恢复 reconcile-report HTTP status').toBe(202);
  const recoveredReportBody = await recoveredReportResponse.json();
  expect(recoveredReportBody?.code, '恢复 reconcile-report API code').toBe(202);
  expect(recoveredReportBody?.message, '恢复 reconcile-report API message').toBe('accepted');
  expect(recoveredReportBody?.data?.recorded, '恢复后 pending 差异数必须为 0').toBe(0);
  const recoveredReport = recoveredReportResponse.request().postDataJSON();
  expect(recoveredReport?.diffs ?? [], '移除route后真实前后端值应一致，pending差异必须清空').toEqual([]);
  assertExactDisplay(await readFieldText(card, PRECISION_FIXTURE.positiveRowIndex), PRECISION_CASES.reconcileDisplay);
  const beforeSubmitFingerprint = quotationCardSnapshotFingerprint(await getQuotation(page, quotationId));
  expect(beforeSubmitFingerprint, '提交前必须存在唯一 SIMPLE line 的报价/核价完整快照').toHaveLength(1);

  await advanceToSubmit(page);
  const successfulSubmitPromise = page.waitForResponse((response) =>
    response.request().method() === 'POST' && /\/quotations\/[^/]+\/submit(?:\?|$)/.test(response.url()),
  );
  await page.getByRole('button', { name: /提交审批$/ }).click();
  const successfulSubmit = await successfulSubmitPromise;
  expect(successfulSubmit.status(), 'unroute并重新对账后真实submit HTTP status').toBe(200);
  const successfulBody = await successfulSubmit.json();
  expect(successfulBody?.code, '成功 submit API code').toBe(200);
  expect(successfulBody?.data?.status, '成功 submit 响应状态').toBe('SUBMITTED');
  await expect(page.locator('.ant-message-notice-content').filter({ hasText: '报价单已提交审批' })).toBeVisible();

  await expect.poll(async () => (await getQuotation(page, quotationId))?.status, {
    timeout: 30_000,
    message: '提交成功后真实 GET 必须返回 SUBMITTED',
  }).toBe('SUBMITTED');
  const submitted = await getQuotation(page, quotationId);
  const afterSubmitFingerprint = quotationCardSnapshotFingerprint(submitted);
  expect(afterSubmitFingerprint, 'submit 只允许推进状态，不得改写任何 line 的报价/核价卡片快照').toEqual(
    beforeSubmitFingerprint,
  );
  beforeSubmitFingerprint.forEach((beforeLine, index) => {
    const afterLine = afterSubmitFingerprint[index];
    expect(afterLine.lineItemId, `line ${index + 1} identity`).toBe(beforeLine.lineItemId);
    expect(afterLine.productPartNo, `line ${index + 1} partNo`).toBe(beforeLine.productPartNo);
    expect(afterLine.quote.raw, `${beforeLine.productPartNo} quoteCardValues raw string`).toBe(beforeLine.quote.raw);
    expect(afterLine.costing.raw, `${beforeLine.productPartNo} costingCardValues raw string`).toBe(beforeLine.costing.raw);
    expect(afterLine.quote.canonical, `${beforeLine.productPartNo} quoteCardValues canonical`).toBe(beforeLine.quote.canonical);
    expect(afterLine.costing.canonical, `${beforeLine.productPartNo} costingCardValues canonical`).toBe(beforeLine.costing.canonical);
  });
  const submittedLines = assertFixtureQuotationLines(
    submitted,
    false,
    'SUBMITTED',
    [PRECISION_CASES.recoveredWork, PRECISION_CASES.negativeWork],
    PRECISION_SENTINELS,
  );
  expect(canonicalDecimalValues(
    submittedLines[0].quoteValues,
    '提交后报价精度快照',
  ), '提交后报价精度快照不得变化').toEqual(
    canonicalExpected([PRECISION_CASES.recoveredWork, PRECISION_CASES.negativeWork]),
  );
  expect(canonicalDecimalValues(
    submittedLines[0].costingValues,
    '提交后核价精度快照',
  ), '提交后核价精度快照不得变化').toEqual(canonicalExpected(PRECISION_SENTINELS));
});
