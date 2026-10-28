/**
 * task-261027 · S-FLOW（AC-1/2/4/7/8/10；AC-5 由后端真实输出流测试覆盖，
 * AC-11 由主线解锁后的构建/回归命令覆盖）。
 *
 * 真实前置夹具由主线在一次性库中准备：四个 T261027_RDP_ 账号，以及一张
 * T261027_RDP_FLOW_ 报价。该报价的公式格、产品小计、报价总额、Excel 视图和比对视图
 * 必须都包含 canonical decimal string 1.2345678915；测试先验证这些前置非空再断言显示。
 */
import { expect, request as playwrightRequest, test, type Locator, type Page } from '@playwright/test';
import { execFileSync } from 'child_process';
import * as XLSX from 'xlsx';

type Role = 'SALES_REP' | 'SALES_MANAGER' | 'PRICING_MANAGER' | 'SYSTEM_ADMIN';
type Credentials = { username: string; password: string };

const USER_PREFIX = 'T261027_RDP_';
const DATA_PREFIX = 'T261027_RDP_FLOW_';
const RAW = '1.2345678915';
const SALES_DISPLAY = '1.2346';
const OTHER_DISPLAY = '1.234567892';

function required(name: string): string {
  const value = process.env[name]?.trim();
  if (!value) throw new Error(`T261027_RDP_FLOW_ 缺少必填环境变量 ${name}`);
  return value;
}

const BACKEND_URL = required('PW_BACKEND_URL').replace(/\/$/, '');
const DB_NAME = required('PW_DB');
const DB_HOST = required('PW_DB_HOST');
const DB_USER = required('PW_DB_USER');
const DB_PASS = required('PW_DB_PASS');
const QUOTATION_ID = required('PW_RDP_QUOTATION_ID');
const QUOTATION_NO = required('PW_RDP_QUOTATION_NO');
const PART_NO = required('PW_RDP_PART_NO');
const TAB_NAME = process.env.PW_RDP_TAB_NAME?.trim() || '精度验证';
const FORMULA_FIELD = process.env.PW_RDP_FORMULA_FIELD?.trim() || '精度结果';

const ROLE_ENV: Record<Role, [string, string]> = {
  SALES_REP: ['PW_RDP_USER_SALES_REP', 'PW_RDP_PWD_SALES_REP'],
  SALES_MANAGER: ['PW_RDP_USER_SALES_MANAGER', 'PW_RDP_PWD_SALES_MANAGER'],
  PRICING_MANAGER: ['PW_RDP_USER_PRICING_MANAGER', 'PW_RDP_PWD_PRICING_MANAGER'],
  SYSTEM_ADMIN: ['PW_RDP_USER_SYSTEM_ADMIN', 'PW_RDP_PWD_SYSTEM_ADMIN'],
};

const CREDENTIALS = Object.fromEntries(
  Object.entries(ROLE_ENV).map(([role, [userEnv, passwordEnv]]) => {
    const credentials = { username: required(userEnv), password: required(passwordEnv) };
    if (!credentials.username.startsWith(USER_PREFIX)) {
      throw new Error(`${role} 测试账号必须以 ${USER_PREFIX} 开头，实际=${credentials.username}`);
    }
    return [role, credentials];
  }),
) as Record<Role, Credentials>;

if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(QUOTATION_ID)) {
  throw new Error(`PW_RDP_QUOTATION_ID 必须是 UUID，实际=${QUOTATION_ID}`);
}
if (!QUOTATION_NO.startsWith(DATA_PREFIX) || !PART_NO.startsWith(DATA_PREFIX)) {
  throw new Error(
    `报价号和产品料号必须以 ${DATA_PREFIX} 开头，实际 quotationNo=${QUOTATION_NO} partNo=${PART_NO}`,
  );
}
if (!/^[A-Za-z0-9_]+$/.test(DB_NAME)) {
  throw new Error(`PW_DB 含非法字符：${DB_NAME}`);
}

test.describe.configure({ mode: 'serial' });

function asObject(value: unknown, label: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${label} 必须是 JSON object，实际=${JSON.stringify(value)}`);
  }
  return value as Record<string, unknown>;
}

function unwrap(body: unknown): unknown {
  const object = asObject(body, 'API 响应');
  return Object.hasOwn(object, 'data') ? object.data : object;
}

async function login(page: Page, role: Role): Promise<void> {
  await page.context().clearCookies();
  const credentials = CREDENTIALS[role];
  const response = await page.request.post(`${BACKEND_URL}/api/cpq/auth/login`, {
    data: credentials,
  });
  const text = await response.text();
  expect(response.status(), `${role} 真实登录失败：${text}`).toBe(200);
  const body = asObject(unwrap(JSON.parse(text)), '登录 data');
  expect(body.role, `${credentials.username} 的真实角色错误`).toBe(role);
  expect((await page.context().cookies()).some((cookie) => cookie.name === 'CPQ_SESSION'),
    `${role} 登录 200 但没有 CPQ_SESSION`).toBe(true);
}

async function logout(page: Page): Promise<void> {
  const response = await page.request.post(`${BACKEND_URL}/api/cpq/auth/logout`);
  expect(response.ok(), `真实退出失败：HTTP ${response.status()} ${await response.text()}`).toBe(true);
  await page.context().clearCookies();
}

async function getQuotation(page: Page): Promise<Record<string, unknown>> {
  const response = await page.request.get(`${BACKEND_URL}/api/cpq/quotations/${QUOTATION_ID}`);
  const text = await response.text();
  expect(response.status(), `GET quotation 失败：${text}`).toBe(200);
  const quotation = asObject(unwrap(JSON.parse(text)), '报价 data');
  expect(String(quotation.id), 'GET 必须返回指定报价 id').toBe(QUOTATION_ID);
  expect(String(quotation.quotationNumber ?? quotation.quotationNo), 'GET 必须返回前缀报价号')
    .toBe(QUOTATION_NO);
  return quotation;
}

function exactStringCount(value: unknown, wanted: string): number {
  if (typeof value === 'string') return value === wanted ? 1 : 0;
  if (Array.isArray(value)) return value.reduce((sum, item) => sum + exactStringCount(item, wanted), 0);
  if (value && typeof value === 'object') {
    return Object.values(value).reduce((sum, item) => sum + exactStringCount(item, wanted), 0);
  }
  return 0;
}

function sqlOne(query: string): string {
  const output = execFileSync(
    'psql',
    ['-h', DB_HOST, '-U', DB_USER, '-d', DB_NAME, '-tA', '-v', 'ON_ERROR_STOP=1', '-c', query],
    { env: { ...process.env, PGPASSWORD: DB_PASS }, encoding: 'utf8' },
  ).trim();
  if (!output) throw new Error(`SQL 未返回行：${query}`);
  return output;
}

type DbSnapshot = {
  updatedAt: string;
  originalAmount: string | null;
  totalAmount: string | null;
  lines: Array<Record<string, string | null>>;
};

function dbSnapshot(): DbSnapshot {
  const result = sqlOne(`
    SELECT jsonb_build_object(
      'updatedAt', q.updated_at::text,
      'originalAmount', q.original_amount::text,
      'totalAmount', q.total_amount::text,
      'lines', COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
          'id', li.id::text,
          'subtotal', li.subtotal::text,
          'lineUnitPrice', li.line_unit_price::text,
          'lineDiscountAmount', li.line_discount_amount::text,
          'lineFinalPrice', li.line_final_price::text,
          'lineTotalAmount', li.line_total_amount::text,
          'quoteCardValues', li.quote_card_values::text,
          'quoteExcelValues', li.quote_excel_values::text
        ) ORDER BY li.id::text)
        FROM quotation_line_item li WHERE li.quotation_id = q.id
      ), '[]'::jsonb)
    )::text
    FROM quotation q
    WHERE q.id = '${QUOTATION_ID}'::uuid
  `);
  const parsed = JSON.parse(result) as DbSnapshot;
  expect(parsed.lines.length, '前置报价必须至少有一条真实产品行，禁止空跑').toBeGreaterThan(0);
  expect(JSON.stringify(parsed), `DB 前置必须包含目标未舍入值 ${RAW}`).toContain(RAW);
  return parsed;
}

function calculationState(snapshot: DbSnapshot): Omit<DbSnapshot, 'updatedAt'> {
  return {
    originalAmount: snapshot.originalAmount,
    totalAmount: snapshot.totalAmount,
    lines: snapshot.lines,
  };
}

function numberTokens(text: string): string[] {
  return (text.match(/-?(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d+)?/g) ?? [])
    .map((token) => token.replace(/,/g, ''));
}

async function expectRenderedValue(
  scope: Locator,
  expected: string,
  forbidden: string,
  label: string,
): Promise<void> {
  await expect(scope, `${label} 容器必须可见`).toBeVisible();
  const text = await scope.innerText();
  const tokens = numberTokens(text);
  expect(tokens, `${label} 必须非空显示 ${expected}；实际文本=${text}`).toContain(expected);
  expect(tokens, `${label} 不得泄漏未舍入文本 ${forbidden}；实际文本=${text}`).not.toContain(forbidden);
}

async function assertSettled(page: Page, label: string): Promise<void> {
  await expect(page.getByText('加载中', { exact: false }), `${label} “加载中”最终必须为 0`)
    .toHaveCount(0, { timeout: 30_000 });
  await expect(
    page.locator('.ant-spin-spinning:visible, .ant-btn-loading:visible, .anticon-loading:visible'),
    `${label} 可见 loading 最终必须为 0`,
  ).toHaveCount(0, { timeout: 30_000 });
  await expect(page.locator('.ant-alert-error:visible'), `${label} 不得出现红色错误 Alert`).toHaveCount(0);
  await expect(page.getByText('Unexpected Application Error', { exact: false }), `${label} 不得出现路由错误遮罩`)
    .toHaveCount(0);
}

async function openStep2(page: Page): Promise<void> {
  await page.goto(`/quotations/${QUOTATION_ID}/edit`);
  await page.waitForLoadState('networkidle');
  const cards = page.locator('.qt-product-card');
  if (!(await cards.first().isVisible().catch(() => false))) {
    const next = page.getByRole('button', { name: /下一步/ }).first();
    await expect(next, '编辑步骤 1 必须可进入产品卡片步骤').toBeEnabled();
    await next.click();
  }
  await expect(cards.first(), '编辑步骤必须渲染产品卡片').toBeVisible({ timeout: 30_000 });
  await assertSettled(page, '报价编辑步骤');
}

async function fixtureCard(page: Page): Promise<Locator> {
  const card = page.locator('.qt-product-card').filter({ hasText: PART_NO });
  await expect(card, `产品卡片必须唯一命中 ${PART_NO}`).toHaveCount(1);
  const tab = card.locator('button.qt-tab-btn').filter({ hasText: new RegExp(`^${TAB_NAME}$`) });
  await expect(tab, `产品卡必须存在页签 ${TAB_NAME}`).toHaveCount(1);
  await tab.click();
  await assertSettled(page, `${PART_NO}/${TAB_NAME}`);
  return card;
}

async function formulaCell(card: Locator): Promise<Locator> {
  const table = card.locator('table.qt-cost-table:visible');
  await expect(table, '当前产品页签必须只有一张可见表').toHaveCount(1);
  const headers = await table.locator('thead tr').last().locator('th').allInnerTexts();
  const column = headers.findIndex((header) => header.replace(/\s+/g, ' ').trim() === FORMULA_FIELD);
  expect(column, `当前表头缺少公式列 ${FORMULA_FIELD}：${JSON.stringify(headers)}`).toBeGreaterThanOrEqual(0);
  const rows = table.locator('tbody tr:not(.qt-subtotal-row)');
  expect(await rows.count(), '公式列必须至少有一条真实数据行').toBeGreaterThan(0);
  return rows.first().locator('td').nth(column);
}

async function assertDetailAndRaw(page: Page, role: Role, expected: string): Promise<void> {
  await page.goto(`/quotations/${QUOTATION_ID}`);
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.qt-product-card').first(), `${role} 详情必须渲染产品卡`).toBeVisible();
  const card = await fixtureCard(page);
  await expectRenderedValue(await formulaCell(card), expected, RAW, `${role} 详情公式单元格`);
  await expectRenderedValue(card.locator('.qt-subtotal-value').first(), expected, RAW, `${role} 详情产品小计`);
  await expectRenderedValue(
    page.getByText(/^报价总金额[：:]/).first(),
    expected,
    RAW,
    `${role} 详情报价汇总`,
  );

  const quotation = await getQuotation(page);
  expect(exactStringCount(quotation, RAW), `${role} GET 响应必须至少保留一个 ${RAW} string`)
    .toBeGreaterThan(0);
}

async function assertDetailOnly(page: Page, role: Role, expected: string): Promise<void> {
  await login(page, role);
  await assertDetailAndRaw(page, role, expected);
  await logout(page);
}

async function assertListEditAndDetail(page: Page, role: Role, expected: string): Promise<void> {
  await login(page, role);

  await page.goto(`/quotations?keyword=${encodeURIComponent(QUOTATION_NO)}`);
  await page.waitForLoadState('networkidle');
  const listRow = page.locator('tr').filter({ hasText: QUOTATION_NO });
  await expect(listRow, `${role} 报价列表必须唯一命中私有报价`).toHaveCount(1);
  await expectRenderedValue(listRow, expected, RAW, `${role} 报价列表总额`);
  await assertSettled(page, `${role} 报价列表`);

  await openStep2(page);
  const card = await fixtureCard(page);
  await expectRenderedValue(await formulaCell(card), expected, RAW, `${role} 编辑页公式单元格`);
  await expectRenderedValue(card.locator('.qt-subtotal-value').first(), expected, RAW, `${role} 编辑页产品小计`);

  await assertDetailAndRaw(page, role, expected);
  await logout(page);
}

async function assertExcelAndComparison(page: Page, role: Role, expected: string): Promise<void> {
  await login(page, role);
  await page.goto(`/quotations/${QUOTATION_ID}`);
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.qt-product-card').first(), `${role} 详情必须渲染产品卡`).toBeVisible();

  const excel = page.locator('.ant-segmented-item').filter({ hasText: 'Excel 视图' }).first();
  await expect(excel, `${role} 必须可进入 Excel 视图`).toBeVisible();
  await excel.click();
  await assertSettled(page, `${role} Excel 视图`);
  const excelTable = page.locator('table:visible').last();
  const excelRow = excelTable.locator('tbody tr:not(.ant-table-measure-row):visible').filter({ hasText: PART_NO });
  await expect(excelRow, `${role} Excel 视图必须唯一命中真实产品行`).toHaveCount(1);
  await expectRenderedValue(excelRow, expected, RAW, `${role} Excel 视图计算值`);

  const comparison = page.locator('.ant-segmented-item').filter({ hasText: '比对视图' }).first();
  await expect(comparison, `${role} 必须可进入比对视图`).toBeVisible();
  await comparison.click();
  await assertSettled(page, `${role} 比对视图`);
  const comparisonTable = page.locator('table:visible').last();
  const comparisonRow = comparisonTable.locator('tbody tr:not(.ant-table-measure-row):visible')
    .filter({ hasText: PART_NO });
  await expect(comparisonRow, `${role} 比对视图必须唯一命中真实产品行`).toHaveCount(1);
  await expectRenderedValue(comparisonRow, expected, RAW, `${role} 比对视图计算值`);
  await logout(page);
}

async function saveProjectName(page: Page, projectName: string): Promise<void> {
  await page.goto(`/quotations/${QUOTATION_ID}/edit`);
  await page.waitForLoadState('networkidle');
  const input = page.getByPlaceholder('关联项目', { exact: true }).first();
  await expect(input, '草稿基本信息必须存在关联项目文本字段').toBeVisible();
  await input.fill(projectName);
  await page.getByRole('button', { name: /下一步/ }).first().click();
  await expect(page.locator('.qt-product-card').first(), '修改无关文本后必须进入产品步骤').toBeVisible();
  const responsePromise = page.waitForResponse((response) =>
    response.request().method() === 'PUT'
      && new RegExp(`/quotations/${QUOTATION_ID}/draft(?:\\?|$)`).test(response.url()),
  );
  await page.getByRole('button', { name: /保存草稿$/ }).click();
  const response = await responsePromise;
  expect(response.status(), `保存草稿失败：${await response.text()}`).toBe(200);
  await assertSettled(page, '保存草稿后');
}

function comparisonPayload(extra: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    columns: [{ tag: 'TOTAL', label: '总价' }],
    rows: [{
      partNo: PART_NO,
      presence: 'BOTH',
      cells: { TOTAL: { quote: RAW, costing: RAW, highlighted: false } },
    }],
    ...extra,
  };
}

function workbookStrings(bytes: Buffer): string[] {
  const workbook = XLSX.read(bytes, { type: 'buffer', raw: true });
  return workbook.SheetNames.flatMap((name) => {
    const sheet = workbook.Sheets[name];
    return Object.keys(sheet)
      .filter((key) => !key.startsWith('!'))
      .map((key) => String(sheet[key]?.v ?? ''));
  });
}

test.beforeAll(() => {
  const users = Object.values(CREDENTIALS).map((credentials) => credentials.username);
  expect(new Set(users).size, '四个角色必须使用四个独立前缀账号').toBe(4);
  const snapshot = dbSnapshot();
  expect(snapshot.lines.length, '真实前置产品行不得为空').toBeGreaterThan(0);
});

test('T-04 / AC-1、AC-4：销售代表/经理的列表、编辑、产品卡、详情均显示 1.2346，GET 原值不变', async ({ page }) => {
  for (const role of ['SALES_REP', 'SALES_MANAGER'] as const) {
    await assertListEditAndDetail(page, role, SALES_DISPLAY);
  }
});

test('T-05 / AC-2、AC-4：定价经理详情及管理员列表/编辑/详情均显示 1.234567892', async ({ page }) => {
  await assertDetailOnly(page, 'PRICING_MANAGER', OTHER_DISPLAY);
  await assertListEditAndDetail(page, 'SYSTEM_ADMIN', OTHER_DISPLAY);
});

test('T-06 / AC-4：四角色 Excel/比对视图口径一致且所有入口加载中最终为 0', async ({ page }) => {
  for (const role of Object.keys(CREDENTIALS) as Role[]) {
    const expected = role === 'SALES_REP' || role === 'SALES_MANAGER' ? SALES_DISPLAY : OTHER_DISPLAY;
    await assertExcelAndComparison(page, role, expected);
  }
});

test('T-08 / AC-7：销售经理只改无关项目文本并保存，不把 4 位显示回写到 GET、金额列或快照', async ({ page }) => {
  await login(page, 'SALES_MANAGER');
  const beforeApi = await getQuotation(page);
  const rawCountBefore = exactStringCount(beforeApi, RAW);
  expect(rawCountBefore, `保存前 GET 必须非空包含 ${RAW}`).toBeGreaterThan(0);
  const beforeDb = dbSnapshot();
  const originalProjectName = String(beforeApi?.projectName ?? '');
  const changedProjectName = `${DATA_PREFIX}SAVE_TEXT`;
  let firstSaveCompleted = false;
  let primaryFailure: unknown;
  let restoreFailure: unknown;

  try {
    await saveProjectName(page, changedProjectName);
    firstSaveCompleted = true;
    const afterApi = await getQuotation(page);
    expect(String(afterApi?.projectName ?? ''), '无关项目文本必须真实保存到 GET 响应')
      .toBe(changedProjectName);
    expect(exactStringCount(afterApi, RAW), '保存后 GET 中目标 canonical decimal string 数量必须不变')
      .toBe(rawCountBefore);
    expect(calculationState(dbSnapshot()), '关系型金额列和 JSONB 快照必须与保存前逐字相等')
      .toEqual(calculationState(beforeDb));
  } catch (error) {
    primaryFailure = error;
  } finally {
    if (firstSaveCompleted) {
      try {
        await saveProjectName(page, originalProjectName);
        const restoredApi = await getQuotation(page);
        expect(String(restoredApi?.projectName ?? ''), 'finally 必须精确还原原项目名称')
          .toBe(originalProjectName);
      } catch (error) {
        restoreFailure = error;
      }
    }
    try {
      await logout(page);
    } catch (error) {
      restoreFailure ??= error;
    }
  }

  if (primaryFailure && restoreFailure) {
    throw new AggregateError([primaryFailure, restoreFailure], 'T-08 主断言失败，且项目名称还原也失败');
  }
  if (primaryFailure) throw primaryFailure;
  if (restoreFailure) throw restoreFailure;
});

test('T-09 / AC-8：销售代表→管理员→销售代表连续退出登录，4/9/4 恢复且 DB 全快照不变', async ({ page }) => {
  const baseline = dbSnapshot();
  const sequence: Array<[Role, string]> = [
    ['SALES_REP', SALES_DISPLAY],
    ['SYSTEM_ADMIN', OTHER_DISPLAY],
    ['SALES_REP', SALES_DISPLAY],
  ];

  for (const [role, expected] of sequence) {
    await login(page, role);
    await page.goto(`/quotations/${QUOTATION_ID}`);
    await page.waitForLoadState('networkidle');
    const card = await fixtureCard(page);
    await expectRenderedValue(await formulaCell(card), expected, RAW, `${role} 连续切换公式单元格`);
    expect(dbSnapshot(), `${role} 纯查看不得改变 updated_at、快照或金额`).toEqual(baseline);
    await logout(page);
  }
});

test('T-10 / AC-10：未登录查询/六输出均 401，伪造角色或位数不覆盖真实销售经理会话', async ({ page }) => {
  const anonymous = await playwrightRequest.newContext({ baseURL: BACKEND_URL });
  try {
    const probes = [
      await anonymous.get(`/api/cpq/quotations/${QUOTATION_ID}`),
      await anonymous.post(`/api/cpq/quotations/${QUOTATION_ID}/export/html`, { data: {} }),
      await anonymous.post(`/api/cpq/quotations/${QUOTATION_ID}/export/pdf`, { data: {} }),
      await anonymous.post(`/api/cpq/quotations/${QUOTATION_ID}/export/excel`, { data: {} }),
      await anonymous.get(`/api/cpq/quotations/${QUOTATION_ID}/export-excel-view`),
      await anonymous.post(`/api/cpq/quotations/${QUOTATION_ID}/comparison/export`, {
        data: comparisonPayload(),
      }),
      await anonymous.post(`/api/cpq/quotations/${QUOTATION_ID}/send`, {
        data: { to: 't261027-rdp@example.invalid', subject: DATA_PREFIX, body: 'precision', attachExcel: false },
      }),
    ];
    expect(probes.map((response) => response.status()), 'GET + 六个输出端点未登录必须逐一返回 401')
      .toEqual([401, 401, 401, 401, 401, 401, 401]);
  } finally {
    await anonymous.dispose();
  }

  await login(page, 'SALES_MANAGER');
  const forgedHtml = await page.request.post(
    `${BACKEND_URL}/api/cpq/quotations/${QUOTATION_ID}/export/html?role=SYSTEM_ADMIN&displayDecimals=9`,
    {
      headers: { 'X-Role': 'SYSTEM_ADMIN', 'X-Display-Decimals': '9' },
      data: { role: 'SYSTEM_ADMIN', displayDecimals: 9 },
    },
  );
  expect(forgedHtml.status(), `伪造参数不应破坏原有销售经理权限：${await forgedHtml.text()}`).toBe(200);
  const htmlTokens = numberTokens(await forgedHtml.text());
  expect(htmlTokens, '销售经理伪造 SYSTEM_ADMIN 后仍必须输出 4 位').toContain(SALES_DISPLAY);
  expect(htmlTokens, '销售经理伪造 SYSTEM_ADMIN 不得升级为 9 位').not.toContain(OTHER_DISPLAY);

  const forgedComparison = await page.request.post(
    `${BACKEND_URL}/api/cpq/quotations/${QUOTATION_ID}/comparison/export?role=SYSTEM_ADMIN&displayDecimals=9`,
    {
      headers: { 'X-Role': 'SYSTEM_ADMIN', 'X-Display-Decimals': '9' },
      data: comparisonPayload({ role: 'SYSTEM_ADMIN', displayDecimals: 9 }),
    },
  );
  expect(forgedComparison.status(), `比对导出伪造参数不应改变原有权限：${await forgedComparison.text()}`).toBe(200);
  const cells = workbookStrings(Buffer.from(await forgedComparison.body()));
  expect(cells, '比对 Excel 必须包含销售经理 4 位文本').toContain(SALES_DISPLAY);
  expect(cells, '比对 Excel 不得因伪造参数出现管理员 9 位文本').not.toContain(OTHER_DISPLAY);
  await logout(page);
});
