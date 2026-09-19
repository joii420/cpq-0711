/**
 * repair-260918 · S-UI · T-UI-26 —— 审核列表「预算失败」标签悬停显示原因（D-8 新增）。
 *
 * AC-26（单点 · 显示 · D-8）：审核列表里预算状态为「预算失败」的料号，鼠标悬停红色「预算失败」标签 ⇒ 出现提示，
 *        内容为该料号 budgetError 原文（如「预算试算超时（超过 60 秒）」）；budgetError 为空时标签照常显示、悬停无提示；
 *        「重算」链接照常可点。
 * 做法（test.md §3.3）：/pricing/reviews，page.route 把审核列表响应整体换成两条伪造的 budgetStatus=FAILED 行
 *        （料号带 RP0918-MOCK 字样）：A 带 budgetError，B 的 budgetError=null。
 * 断言：悬停 A 的标签 ⇒ 提示文本逐字等于原文；悬停 B 的标签 ⇒ 无提示；两行「重算」都可见（🚫 不点）。
 * 阳性对照：A 的提示能被同一个「提示定位器」抓到，才说明 B 的「无提示」不是量具抓不到。
 * 零业务写入：非 GET 的 /api 一律 abort，结束断言为空。
 */
import { test, expect, type Locator, type Page } from '@playwright/test';
import {
  apiLogin, assertEnvIdentity, assertNoOtherPlaywright, blockWrites, dump, ensureDirs, gotoApp, info, shot, unwrap, writeEvid,
} from './repair260918-sui.helpers';

const ERR = '预算试算超时（超过 60 秒）';
const MAT_A = 'RP0918-MOCK-FAIL-A';
const MAT_B = 'RP0918-MOCK-FAIL-B';

const row = (over: Record<string, unknown>) => ({
  reviewId: '0918a0a0-0000-4000-8000-0000000026a0', customerNo: 'CUST-0004', customerName: '正泰',
  materialNo: MAT_A, materialName: 'RP0918 注入数据',
  currentVersionNo: 'V26091801', targetVersionNo: 'V26091899',
  budgetStatus: 'FAILED', budgetError: ERR, reviewStatus: 'PENDING',
  basisQuotationNo: 'QT-RP0918-MOCK-0001', basisQuotationDate: '2026-09-18',
  quoteCostCurrent: 10, quoteCostAdjusted: null, costingCost: null, diffCurrent: null, diffAdjusted: null,
  columnCount: 1, breachedCount: 0, amberCount: 0, missingCount: 0, staleCount: 0, rowRed: false,
  ...over,
});
const ROWS = [
  row({}),
  row({ reviewId: '0918a0a0-0000-4000-8000-0000000026b0', materialNo: MAT_B, budgetError: null }),
];

/** 页面上可见的提示浮层（antd v6：.ant-tooltip 本体即含文本，.ant-tooltip-inner 不存在；兼容 role=tooltip）。 */
// 第 1 次执行实测：.ant-tooltip 与其内层 [role=tooltip] 同时命中，同一提示被数成 2 条 ⇒ 只认 role=tooltip 一层
const tooltips = (page: Page) => page.locator('[role="tooltip"]:visible');

async function visibleTooltipTexts(page: Page): Promise<string[]> {
  return (await tooltips(page).allInnerTexts()).map(t => t.replace(/\s+/g, ' ').trim()).filter(Boolean);
}

async function moveAway(page: Page) {
  await page.mouse.move(5, 5);
  await expect.poll(() => visibleTooltipTexts(page), { message: '移开鼠标后提示应消失', timeout: 5000 }).toEqual([]);
}

function failTag(r: Locator) {
  return r.getByText('预算失败', { exact: true }).first();
}

test.describe('S-UI · AC-26 预算失败原因悬停提示', () => {
  test.beforeAll(async ({ browser }) => {
    ensureDirs();
    assertNoOtherPlaywright();
    const page = await browser.newPage();
    await assertEnvIdentity(page);
    await page.close();
  });

  test('T-UI-26 · 悬停「预算失败」⇒ 提示 = budgetError 原文；budgetError 为空 ⇒ 无提示；「重算」可见', async ({ page }, ti) => {
    const blocked = await blockWrites(page);
    const listHits: string[] = [];
    await page.route(/\/api\/cpq\/price-adjust\/reviews(\?|$)/, async (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const resp = await route.fetch(); // 只为探明包装形态
      const json = await resp.json().catch(() => null);
      const wrapped = !!(json && typeof json === 'object' && !Array.isArray(json) && json.data && typeof json.data === 'object');
      const base = unwrap(json);
      const body = { ...(base && typeof base === 'object' && !Array.isArray(base) ? base : {}), content: ROWS, page: 1, size: 20, totalElements: 2, totalPages: 1 };
      listHits.push(`${route.request().url()} real=${resp.status()} wrapped=${wrapped}`);
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(wrapped ? { data: body } : body) });
    });

    await apiLogin(page);
    await gotoApp(page, '/pricing/reviews');
    const rowA = page.locator('tr.ant-table-row').filter({ hasText: MAT_A }).first();
    const rowB = page.locator('tr.ant-table-row').filter({ hasText: MAT_B }).first();
    await expect(rowA, `列表应出现注入行 ${MAT_A}`).toBeVisible({ timeout: 30_000 });
    await expect(rowB, `列表应出现注入行 ${MAT_B}`).toBeVisible();
    info(ti, 'AC-26 列表改写', listHits);

    // 两行：标签照常显示 + 「重算」可见（不点）
    const tagA = failTag(rowA), tagB = failTag(rowB);
    await expect(tagA, 'A 行应显示「预算失败」标签').toBeVisible();
    await expect(tagB, 'B 行（budgetError 为空）标签照常显示').toBeVisible();
    const tagColor = await tagA.evaluate(e => getComputedStyle(e).color);
    for (const [name, r] of [['A', rowA], ['B', rowB]] as const) {
      await expect(r.getByText(/^重\s*算$/).first(), `${name} 行「重算」链接应可见`).toBeVisible();
    }

    await moveAway(page);
    // A：悬停 ⇒ 提示逐字等于原文（同时是阳性对照）
    await tagA.hover();
    await expect.poll(() => visibleTooltipTexts(page), { message: 'A：悬停「预算失败」应出现提示', timeout: 8000 })
      .not.toEqual([]);
    const textsA = await visibleTooltipTexts(page);
    await shot(page, 'AC-26-悬停-有原因');
    expect(textsA, `A：提示内容应逐字为「${ERR}」`).toEqual([ERR]);

    // B：悬停 ⇒ 等足够时间仍无提示
    await moveAway(page);
    await tagB.hover();
    await page.waitForTimeout(2000); // antd 默认 mouseEnterDelay 0.1s；A 的提示在此时间内已出现，2s 足够判「不出现」
    const textsB = await visibleTooltipTexts(page);
    await shot(page, 'AC-26-悬停-无原因');

    const evidence = { tagColor, tooltipA: textsA, tooltipB: textsB, listHits, blocked };
    writeEvid('AC-26-results.json', evidence);
    info(ti, 'AC-26 结果', evidence);
    expect(textsB, 'B：budgetError 为空 ⇒ 悬停不得出现提示').toEqual([]);
    if (blocked.length) await dump(page, 'AC-26-blocked');
    expect(blocked, '零业务写入（「重算」等按钮未被点击）').toEqual([]);
  });
});
