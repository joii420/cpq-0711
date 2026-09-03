/**
 * task-260902 · AC-55 决定性 A/B（**真实后端**，非打桩）
 * 取一个真有核价数据的料号，打开「物料与元素BOM」tab，逐格 dump。
 * before=5174（主工作区，F-11 之前） / after=5203（worktree，F-11 之后）。
 * 期望：唯一差异是前导零编码列由被抹零恢复为原值；数值列格式/列头/行数/按钮不变。
 */
import { test, expect, Page } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';

const TAG = process.env.AB_TAG || 'unknown';
const PART = process.env.AB_PART || '3120018220';

async function dumpTab(page: Page, tabName: string) {
  const tab = page.locator('.ant-drawer .ant-tabs-left .ant-tabs-tab', { hasText: tabName }).first();
  if (await tab.count() === 0) { console.log(`[${TAG}] ${tabName}: TAB_NOT_FOUND`); return; }
  await tab.click();
  await page.waitForTimeout(2500);
  const pane = page.locator('.ant-drawer .ant-tabs-tabpane-active');
  const headers = (await pane.locator('.ant-table-thead th').allInnerTexts()).map((h) => h.trim());
  console.log(`[${TAG}] ${tabName} HEADERS = ${JSON.stringify(headers)}`);
  const rows = pane.locator('.ant-table-tbody tr.ant-table-row');
  const n = await rows.count();
  console.log(`[${TAG}] ${tabName} ROW_COUNT = ${n}`);
  for (let i = 0; i < n; i++) {
    const texts = (await rows.nth(i).locator('td').allInnerTexts()).map((c) => c.replace(/\s+/g, ' ').trim());
    const inputs = await rows.nth(i).locator('input').evaluateAll((els) => els.map((e) => (e as HTMLInputElement).value));
    console.log(`[${TAG}] ${tabName} R${i + 1}_TEXT = ${JSON.stringify(texts)}`);
    console.log(`[${TAG}] ${tabName} R${i + 1}_INPUT = ${JSON.stringify(inputs)}`);
  }
}

test('AC-55 · 前导零修复前后逐格比对', async ({ page }) => {
  test.setTimeout(240_000);
  await loginAsAdmin(page);
  await page.goto('/master-data-hub');
  await page.waitForSelector('.ant-tabs-nav .ant-tabs-tab', { timeout: 60_000 });
  await page.waitForSelector('.ant-table-tbody tr.ant-table-row', { timeout: 30_000 });
  await page.waitForTimeout(1200);

  // 列表分页 20/页，目标料号可能不在首页 —— 先用搜索框定位
  await page.locator('input[placeholder="按料号 / 品名搜索"]').first().fill(PART);
  await page.waitForTimeout(2500);
  console.log(`[${TAG}] SEARCH_HIT_ROWS = ${await page.locator('.ant-table-tbody tr.ant-table-row').count()}`);

  const row = page.locator('.ant-table-tbody tr.ant-table-row', { hasText: PART }).first();
  await row.locator('td').nth(1).click();
  await page.waitForSelector('.ant-drawer', { timeout: 20_000 });
  await page.waitForTimeout(2500);

  console.log(`[${TAG}] DRAWER_TITLE = ${JSON.stringify((await page.locator('.ant-drawer-title').innerText()).replace(/\s+/g, ' ').trim())}`);
  const tabs = (await page.locator('.ant-drawer .ant-tabs-left .ant-tabs-tab').allInnerTexts()).map((t) => t.replace(/\s+/g, ' ').trim());
  console.log(`[${TAG}] TAB_BADGES = ${JSON.stringify(tabs)}`);

  for (const t of ['物料与元素BOM', '物料BOM', '来料加工费']) await dumpTab(page, t);

  const btns = (await page.locator('.ant-drawer button').allInnerTexts()).map((b) => b.replace(/\s+/g, '')).filter(Boolean);
  console.log(`[${TAG}] DRAWER_BUTTONS = ${JSON.stringify(btns)}`);
  await page.screenshot({ path: `e2e/screenshots/ac55-${TAG}-elementbom.png`, fullPage: true });
  expect(tabs.length).toBeGreaterThan(0);
});
