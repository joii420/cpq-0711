/**
 * task-261026 · S-QUI（AC-16）。
 *
 * 先在隔离栈创建 T261026_QUI_ 私有报价并取得完整真实 GET 响应，再只覆写
 * lineItems[].profit；不手写不完整整单 Mock，也不依赖固定现网报价 UUID。
 */
import { expect, test, type Locator, type Page, type TestInfo } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';

const PREFIX = 'T261026_QUI_';

type ProfitMode = string | null | 'MISSING';

type QuotationResponse = {
  data?: {
    lineItems?: Array<Record<string, unknown>>;
    [key: string]: unknown;
  };
  [key: string]: unknown;
};

type Fixture = {
  quotationId: string;
  partNo: string;
  fullResponse: QuotationResponse;
};

function isKnownBaselineConsoleError(text: string): boolean {
  const value = text.trim();
  return /^Warning: \[antd: (?:Drawer|Tabs|Modal)\] [`']?(?:width|destroyInactiveTabPane|destroyOnClose)[`']? is deprecated\b/.test(value)
    || /^Warning: \[antd: Space\] [`']?direction[`']? is deprecated\b/.test(value)
    || /^Warning: \[antd: Alert\] [`']?message[`']? is deprecated\b/.test(value)
    || /^Warning: \[antd: message\] Static function can not consume context like dynamic theme\./.test(value)
    || /^(?:Warning:\s*)?In HTML, (?:<form>|%s) cannot be a descendant of (?:<form>|%s)\./.test(value)
    || /^(?:Warning:\s*)?<form> cannot contain a nested <form>\./.test(value)
    || /^Warning: validateDOMNesting\(\.\.\.\): <form> cannot appear as a descendant of <form>\./.test(value);
}

function requiredEnv(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) {
    throw new Error(
      `${name} 未设置：S-QUI 必须由主代理从一次性库提供客户/分类/报价模板 UUID，`
      + '不得回退到固定现网报价或共享数据库。',
    );
  }
  return value;
}

async function createFixture(page: Page): Promise<Fixture> {
  const customerId = requiredEnv('T261026_QUI_CUSTOMER_ID');
  const categoryId = requiredEnv('T261026_QUI_CATEGORY_ID');
  const customerTemplateId = requiredEnv('T261026_QUI_TEMPLATE_ID');
  const costingTemplateId = process.env.T261026_QUI_COSTING_TEMPLATE_ID?.trim() || null;
  const suffix = crypto.randomUUID().replaceAll('-', '').slice(0, 8);
  const partNo = `${PREFIX}MAT_${suffix}`;

  const created = await page.request.post('/api/cpq/quotations', {
    data: {
      customerId,
      name: `${PREFIX}${suffix}`,
      categoryId,
      customerTemplateId,
      costingTemplateId,
    },
  });
  expect(created.status(), `创建 S-QUI 私有报价失败：${await created.text()}`).toBe(200);
  const createBody = await created.json();
  const quotationId = createBody?.data?.id;
  const baseVersion = createBody?.data?.userDataVersion;
  expect(quotationId, '创建响应缺 data.id').toMatch(/^[0-9a-f-]{36}$/i);
  expect(typeof baseVersion, '创建响应缺 data.userDataVersion').toBe('number');

  const tempId = crypto.randomUUID();
  const saved = await page.request.put(`/api/cpq/quotations/${quotationId}/draft`, {
    data: {
      baseVersion,
      added: [{
        id: null,
        tempId,
        productId: null,
        templateId: customerTemplateId,
        productPartNo: partNo,
        productName: `${PREFIX}最长产品名称用于布局边界验证`,
        customerPartNo: `${PREFIX}CPN_${suffix}`,
        customerProductNo: `${PREFIX}CPN_${suffix}`,
        productAttributeValues: '{}',
        subtotal: '0',
        sortOrder: 0,
        processNos: [],
        componentData: [],
        compositeType: 'SIMPLE',
        seedProcessesFromBase: false,
        compositeProcesses: [],
        quoteExcelValues: null,
      }],
      modified: [],
      removed: [],
    },
  });
  expect(saved.status(), `S-QUI 私有报价新增行失败：${await saved.text()}`).toBe(200);

  const detail = await page.request.get(`/api/cpq/quotations/${quotationId}`);
  expect(detail.status(), `GET S-QUI 私有报价失败：${await detail.text()}`).toBe(200);
  const fullResponse = await detail.json() as QuotationResponse;
  const lines = fullResponse?.data?.lineItems;
  expect(Array.isArray(lines), '完整真实响应缺 data.lineItems[]').toBe(true);
  if (!Array.isArray(lines)) {
    throw new Error('完整真实响应缺 data.lineItems[]');
  }
  expect(lines.length, 'S-QUI 私有报价必须实际有 1 行，0 行不得进入 DOM 断言').toBe(1);
  expect(lines[0]?.productPartNo, '真实报价行身份与本次夹具不符').toBe(partNo);

  return { quotationId, partNo, fullResponse };
}

async function installProfitOverlay(page: Page, fixture: Fixture) {
  let mode: ProfitMode = null;
  const pathname = `/api/cpq/quotations/${fixture.quotationId}`;
  await page.route(`**${pathname}`, async (route) => {
    const requestUrl = new URL(route.request().url());
    if (route.request().method() !== 'GET' || requestUrl.pathname !== pathname) {
      await route.continue();
      return;
    }
    const body = JSON.parse(JSON.stringify(fixture.fullResponse));
    const lines = body?.data?.lineItems;
    if (!Array.isArray(lines) || lines.length !== 1) {
      throw new Error(`利润覆写前完整响应的行数不再为 1：${JSON.stringify(lines)}`);
    }
    if (mode === 'MISSING') {
      delete lines[0].profit;
    } else {
      lines[0].profit = mode;
    }
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
  });
  return (next: ProfitMode) => { mode = next; };
}

async function openReadonly(page: Page, fixture: Fixture) {
  await page.goto(`/quotations/${fixture.quotationId}`);
  await page.waitForLoadState('networkidle');
  await expect(page).toHaveURL(new RegExp(`/quotations/${fixture.quotationId}(?:[?#].*)?$`));
  await expect.poll(async () => (await page.title()).trim(), {
    message: '只读详情页 title 不得为空',
  }).not.toBe('');
  await expect(page.locator('.qt-product-card'), '只读详情必须且只能渲染私有夹具的 1 张产品卡')
    .toHaveCount(1);
}

async function openEditStep2(page: Page, fixture: Fixture) {
  await openReadonly(page, fixture);
  const edit = page.getByRole('button', { name: /^\s*编\s*辑\s*$/ }).first();
  await expect(edit, '编辑态必须按“详情 → 编辑”入口进入').toBeVisible();
  await edit.click();
  const next = page.getByRole('button', { name: /下\s*一\s*步/ }).first();
  await expect(next, 'Step 1 必须提供“下一步”进入产品卡').toBeVisible();
  await expect(next, '隔离夹具必须带有效分类和模板，下一步不得禁用').toBeEnabled();
  await next.click();
  await expect(page.locator('.qt-product-card'), '编辑态 Step 2 必须且只能渲染 1 张产品卡')
    .toHaveCount(1, { timeout: 60_000 });
}

async function assertHeaderContract(
  page: Page,
  expectedProfit: string,
  readonly: boolean,
): Promise<{ card: Locator; profit: Locator }> {
  const card = page.locator('.qt-product-card').first();
  const header = card.locator('.qt-card-header-left');
  await expect(header, '产品卡必须有 .qt-card-header-left').toHaveCount(1);

  const unit = header
    .locator(':scope > .qt-sku-badge')
    .filter({ hasText: /^\s*单位\s*[:：]/ });
  const vat = header.locator(':scope > .qt-vat-badge');
  const profit = header.locator(':scope > .qt-profit-badge');
  await expect(vat, 'VAT 提示框必须是头部直接子元素').toHaveCount(1);
  await expect(profit, '利润提示框必须是头部直接子元素').toHaveCount(1);
  const unitCount = await unit.count();
  expect(unitCount, '单位提示框为条件渲染；存在时必须唯一且是头部直接子元素')
    .toBeLessThanOrEqual(1);
  if (unitCount === 1) {
    await expect(unit).toContainText(/^\s*单位\s*[:：]/);
  }
  await expect(profit).toHaveText(expectedProfit);
  await expect(
    header.locator(':scope > .qt-vat-badge + .qt-profit-badge'),
    '利润必须是 VAT 的下一个直接兄弟，且两者都在头部直接子级',
  ).toHaveCount(1);

  const order = await header.evaluate((element) => {
    const children = Array.from(element.children);
    return {
      unit: children.findIndex((child) => child.matches('.qt-sku-badge')
        && /^\s*单位\s*[:：]/.test(child.textContent ?? '')),
      vat: children.findIndex((child) => child.matches('.qt-vat-badge')),
      profit: children.findIndex((child) => child.matches('.qt-profit-badge')),
    };
  });
  if (unitCount === 1) {
    expect(order.unit, '单位存在时必须位于 VAT 之前').toBeGreaterThanOrEqual(0);
    expect(order.vat, '单位存在时 VAT 必须位于单位之后').toBeGreaterThan(order.unit);
  } else {
    expect(order.unit, '无结算单位的真实报价不得误把客户产品编号识别成单位').toBe(-1);
  }
  expect(order.vat, 'VAT 必须存在于头部直接子级').toBeGreaterThanOrEqual(0);
  expect(order.profit, '利润必须紧跟 VAT，不能插到产品名/销售料号之后').toBe(order.vat + 1);

  const deleteButton = card.locator('button.qt-action-btn.delete');
  if (readonly) {
    await expect(deleteButton, '只读态仍不得出现删除按钮').toHaveCount(0);
  } else {
    await expect(deleteButton, '编辑态删除按钮不得因新增利润提示框消失').toHaveCount(1);
    await expect(deleteButton).toBeVisible();
    await expect(deleteButton).toBeEnabled();
  }
  await assertNoHeaderOverflow(page, card, profit);
  await assertNoErrorOverlay(page);
  return { card, profit };
}

async function assertNoHeaderOverflow(page: Page, card: Locator, profit: Locator) {
  const profitBox = await profit.boundingBox();
  const right = card.locator('.qt-card-header-right');
  await expect(right, '产品名/销售料号所在的头部右区必须保留').toHaveCount(1);
  const rightBox = await right.boundingBox();
  expect(profitBox, '利润提示框必须有可观测布局框').not.toBeNull();
  expect(rightBox, '头部右区必须有可观测布局框').not.toBeNull();
  const overlaps = profitBox!.x < rightBox!.x + rightBox!.width
    && profitBox!.x + profitBox!.width > rightBox!.x
    && profitBox!.y < rightBox!.y + rightBox!.height
    && profitBox!.y + profitBox!.height > rightBox!.y;
  expect(overlaps, '最长利润不得覆盖右侧产品名/销售料号区域').toBe(false);

  const overflow = await page.evaluate(() => {
    const cardElement = document.querySelector('.qt-product-card') as HTMLElement | null;
    return {
      page: document.documentElement.scrollWidth - document.documentElement.clientWidth,
      card: cardElement ? cardElement.scrollWidth - cardElement.clientWidth : -1,
    };
  });
  expect(overflow.card, '产品卡不得产生意外横向溢出').toBeLessThanOrEqual(1);
  expect(overflow.page, '1440×900 页面不得产生意外横向溢出').toBeLessThanOrEqual(1);
}

async function assertNoErrorOverlay(page: Page) {
  await expect(page.locator([
    'vite-error-overlay',
    '.ant-result-error',
    '.ant-alert-error',
    '[data-testid="error-overlay"]',
  ].join(',')), '页面不得出现错误遮罩').toHaveCount(0);
  await expect(page.locator('body'), '页面不得空白').not.toHaveText(/^\s*$/);
}

async function screenshot(page: Page, testInfo: TestInfo, name: string) {
  await page.screenshot({
    path: testInfo.outputPath(`${name}.png`),
    fullPage: true,
    animations: 'disabled',
    caret: 'hide',
  });
}

test('T261026_QUI_19 / AC-16：编辑/只读顺序、值/NULL/缺字段、HALF_UP、极值布局与删除按钮', async ({ page }, testInfo) => {
  test.setTimeout(240_000);
  const knownBaselineConsoleErrors: string[] = [];
  const unexpectedConsoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on('console', (message) => {
    if (message.type() !== 'error') return;
    const text = message.text();
    if (isKnownBaselineConsoleError(text)) {
      knownBaselineConsoleErrors.push(text);
    } else {
      unexpectedConsoleErrors.push(text);
    }
  });
  page.on('pageerror', (error) => pageErrors.push(error.message));

  await loginAsAdmin(page);
  const fixture = await createFixture(page);
  const setProfit = await installProfitOverlay(page, fixture);

  try {
    setProfit('12.500000000000');
    await openReadonly(page, fixture);
    await assertHeaderContract(page, '利润: 12.5', true);
    await screenshot(page, testInfo, '01-readonly-value');

    setProfit(null);
    await page.reload({ waitUntil: 'networkidle' });
    await assertHeaderContract(page, '利润: -', true);

    setProfit('MISSING');
    await page.reload({ waitUntil: 'networkidle' });
    await assertHeaderContract(page, '利润: -', true);
    await screenshot(page, testInfo, '02-readonly-null-and-legacy-missing');

    setProfit('99999999999999.999999999999');
    await page.reload({ waitUntil: 'networkidle' });
    await assertHeaderContract(page, '利润: 100000000000000', true);
    await screenshot(page, testInfo, '03-readonly-max-value-layout');

    setProfit('1.234567891500');
    await openEditStep2(page, fixture);
    const { card } = await assertHeaderContract(page, '利润: 1.234567892', false);
    await screenshot(page, testInfo, '04-edit-half-up-and-delete-button');

    const deleteButton = card.locator('button.qt-action-btn.delete');
    const cardsBefore = await page.locator('.qt-product-card').count();
    await deleteButton.click();
    const confirmation = page.locator('.ant-popover:visible, .ant-modal-confirm:visible, [role="dialog"]:visible');
    const confirmationVisible = await confirmation.first().isVisible().catch(() => false);
    if (confirmationVisible) {
      await expect(confirmation.first(), '删除按钮必须触发既有确认交互').toBeVisible();
      await page.keyboard.press('Escape');
    } else {
      await expect(page.locator('.qt-product-card'), '无二次确认时删除按钮必须实际移除卡片')
        .toHaveCount(cardsBefore - 1);
    }

    if (knownBaselineConsoleErrors.length > 0) {
      console.log('[T261026_QUI_19][观察] 已有 AntD deprecated/nested form console.error：',
        knownBaselineConsoleErrors);
    }
    expect(pageErrors, `pageerror 一律不得豁免：\n${pageErrors.join('\n')}`)
      .toEqual([]);
    expect(unexpectedConsoleErrors,
      `除明确的 AntD deprecated/nested form 基线外，console.error 必须为 0：\n${unexpectedConsoleErrors.join('\n')}`)
      .toEqual([]);
  } finally {
    await page.unroute(`**/api/cpq/quotations/${fixture.quotationId}`).catch(() => {});
    const removed = await page.request.delete(`/api/cpq/quotations/${fixture.quotationId}`, { data: {} });
    expect([200, 404], `清理私有报价失败：${removed.status()} ${await removed.text()}`)
      .toContain(removed.status());
  }
});
