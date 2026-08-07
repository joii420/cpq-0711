/**
 * #49 返修验证：比对列 MISSING 的「缺失侧」文案三态。
 *
 *   QUOTE → 报价侧 / COSTING → 核价侧 / BOTH → 两侧
 *
 * 数据现状：库里 material_price_review_column.missing_side 只有 BOTH(30) 与 NULL(8)，
 * 没有 QUOTE / COSTING 行。故：
 *   · BOTH  用**真实数据**跑（不拦截，最强证据）；
 *   · QUOTE / COSTING 在**网络层改写同一份真实响应**的该字段（源码零 mock、零写库，
 *     不碰 CUST-0729-QA / QB / ZZ47 等他方数据域 —— 详情端点本身是纯 GET）。
 *
 * ⚠️ 评审列表是**移动靶**（他方测试并发建/作废评审，实测同一 spec 两次跑列表行数 14 → 0）。
 *    故 BOTH 那条用 MS_REVIEW_ID 钉死目标：只改写列表首行的 reviewId 把点击导过去，
 *    详情响应不拦截。跑之前先取一张当前带真实 BOTH 列的 PENDING 评审：
 *
 *      SELECT r.id FROM material_price_review r
 *        JOIN material_price_review_column c ON c.review_id = r.id
 *       WHERE c.missing_side='BOTH' AND r.status='PENDING'
 *       ORDER BY r.created_at DESC LIMIT 1;
 *
 * 跑法（主仓改动须自起临时端口，5174 是共享 dev server）：
 *   MS_REVIEW_ID=<上面查出的 id> PW_BASE_URL=http://localhost:5179 \
 *     npx playwright test --config=e2e/playwright.config.ts \
 *     e2e/tmp-missing-side-tristate.spec.ts --reporter=list
 * 用完即删。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const SHOT_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

const DETAIL_RE = /\/api\/cpq\/price-adjust\/reviews\/[0-9a-f-]{36}$/;

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

/**
 * 确定性导航：把列表**首行的 reviewId** 改写成 SQL 选定的那张（env `MS_REVIEW_ID` 传入）。
 *
 * 评审列表是移动靶 —— 他方测试并发建/作废评审，实测同一 spec 连跑列表行数 14 → 0 → 12 → 0，
 * 而「页面上没有可点的东西」和「功能坏了」在断言层面长得一模一样。钉行只改"我点哪一行"，
 * 详情响应该真实的仍然真实（BOTH 那条完全不拦截详情）。三条用例共用。
 */
async function pinFirstRow(page: Page, label: string) {
  const pinned = process.env.MS_REVIEW_ID;
  if (!pinned) { console.log(`[MS][${label}] 未设 MS_REVIEW_ID，退化为遍历列表`); return; }
  await page.route(/\/api\/cpq\/price-adjust\/reviews\?/, async (route) => {
    const response = await route.fetch();
    const json = await response.json().catch(() => null);
    const body = json?.data ?? json;
    const rows = body?.content ?? body?.records ?? body;
    if (Array.isArray(rows) && rows.length > 0) rows[0].reviewId = pinned;
    await route.fulfill({ response, json });
  });
  console.log(`[MS][${label}] 已把列表首行指向 review=${pinned}`);
}

/** 打开评审列表，逐个点「料号」链接，直到抽屉里出现 MISSING 单元格；返回该格文案 */
async function openUntilMissing(page: Page, label: string): Promise<string> {
  await page.goto('/pricing/reviews');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);
  const links = page.locator('table a[style*="monospace"], table td a');
  const n = await links.count();
  console.log(`[MS][${label}] 列表可点料号数=${n}`);
  for (let i = 0; i < Math.min(n, 12); i++) {
    await links.nth(i).click();
    // 抽屉内容是异步拉的：必须等 body 可见 + 比对表出现，光 sleep 1.6s 在冷启动下会假性跳过
    await page.locator('.ant-drawer-body').first().waitFor({ state: 'visible', timeout: 15_000 }).catch(() => {});
    await page.locator('.ant-drawer-body .ant-table-tbody tr').first()
      .waitFor({ state: 'visible', timeout: 12_000 }).catch(() => {});
    await page.waitForTimeout(1200);
    const cell = page.locator('.ant-drawer-body >> text=缺数据').first();
    if (await cell.count() > 0) {
      const txt = (await cell.innerText()).trim();
      console.log(`[MS][${label}] 第 ${i} 行命中 MISSING → "${txt}"`);
      return txt;
    }
    console.log(`[MS][${label}] 第 ${i} 行无 MISSING 列（抽屉表格行数=${await page.locator('.ant-drawer-body .ant-table-tbody tr').count()}）`);
    await page.keyboard.press('Escape');
    await page.waitForTimeout(700);
  }
  return '';
}

test('BOTH（真实数据，详情不拦截）→ 文案应为「两侧」，不得说成「核价侧」', async ({ page }) => {
  test.skip(!backendUp, 'backend down');
  await loginAsAdmin(page);

  await pinFirstRow(page, 'BOTH/真实数据');
  const txt = await openUntilMissing(page, 'BOTH/真实数据');
  await page.screenshot({ path: path.join(SHOT_DIR, 'ms-01-both-real.png'), fullPage: true }).catch(() => {});
  expect(txt, '应找到一个 MISSING 比对列').not.toBe('');
  expect(txt, 'BOTH 应显示「两侧」').toContain('两侧');
  // 归因不得再错报成单侧（只断言侧别标签，主干「缺数据」已中性）
  expect(txt, '不应把 BOTH 说成核价侧').not.toContain('：核价侧');
  expect(txt, '不应把 BOTH 说成报价侧').not.toContain('：报价侧');
});

for (const [val, expected] of [['QUOTE', '报价侧'], ['COSTING', '核价侧']] as const) {
  test(`${val}（网络层改写真实响应）→ 文案应为「${expected}」`, async ({ page }) => {
    test.skip(!backendUp, 'backend down');
    await loginAsAdmin(page);
    await pinFirstRow(page, `${val}/网络层改写`);
    await page.route(DETAIL_RE, async (route) => {
      const response = await route.fetch();
      const json = await response.json().catch(() => null);
      const body = json?.data ?? json;
      const cols = body?.comparisonColumns;
      if (Array.isArray(cols)) {
        for (const c of cols) if (c?.status === 'MISSING') c.missingSide = val;
      }
      await route.fulfill({ response, json });
    });
    const txt = await openUntilMissing(page, `${val}/网络层改写`);
    await page.screenshot({ path: path.join(SHOT_DIR, `ms-02-${val.toLowerCase()}.png`), fullPage: true }).catch(() => {});
    expect(txt, '应找到一个 MISSING 比对列').not.toBe('');
    expect(txt, `${val} 应显示「${expected}」`).toContain(expected);
    expect(txt, '不应显示「两侧」').not.toContain('两侧');
  });
}
