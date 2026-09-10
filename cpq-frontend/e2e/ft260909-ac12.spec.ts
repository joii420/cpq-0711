/**
 * task-260909 F-6 · AC-12 序列：BASIC_DATA → INPUT_TEXT → 再改回 BASIC_DATA
 * 每一步都在自建的 T260909FT- 产物上验：值不变、控件形态跟着变。
 * 环境由外部脚本准备（改配置 + 重建结构），本 spec 只负责「读一屏」。
 */
import { test, expect, Page, Locator } from '@playwright/test';

const QID = '56bb60e2-3718-4dc0-97cf-2c340ec45fb4';
const PART = 'S0001';
const TAB = 'T260909FT-BASIC';
const PHASE = process.env.FT_PHASE || 'unknown';
const EXPECT_INPUT = process.env.FT_EXPECT_INPUT === '1';

async function uiLogin(page: Page) {
  await page.context().clearCookies();
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|components|change-password)/, { timeout: 30_000 });
  if (page.url().includes('/change-password')) { await page.goto('/dashboard'); await page.waitForLoadState('networkidle'); }
}

test(`AC-12 阶段=${PHASE}`, async ({ page }) => {
  test.setTimeout(300_000);
  await uiLogin(page);
  await page.goto(`/quotations/${QID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2500);
  const nextBtn = page.getByRole('button', { name: /下一步|继续/ }).first();
  if (await nextBtn.count() > 0 && await nextBtn.isEnabled().catch(() => false)) {
    await nextBtn.click().catch(() => {}); await page.waitForLoadState('networkidle'); await page.waitForTimeout(2500);
  }
  await page.locator('.ant-segmented-item').filter({ hasText: /核价单/ }).first().click();
  await page.waitForTimeout(1800);
  const cardSeg = page.locator('.ant-segmented-item').filter({ hasText: /产品卡片/ }).first();
  if (await cardSeg.count() > 0) { await cardSeg.click().catch(() => {}); await page.waitForTimeout(1500); }
  await page.waitForTimeout(2500);

  const card: Locator = page.locator('.qt-product-card').filter({ hasText: PART }).first();
  await card.scrollIntoViewIfNeeded();
  await card.locator('button.qt-tab-btn').filter({ hasText: new RegExp(`^\\s*${TAB}\\s*$`) }).first().click();
  await page.waitForTimeout(3000);

  const headers = (await card.locator('.qt-cost-table thead th').allInnerTexts()).map((s) => s.replace(/\s+/g, ''));
  const col = headers.indexOf('元素代码');
  expect(col, '找不到「元素代码」列').toBeGreaterThanOrEqual(0);
  const rows = card.locator('.qt-cost-table tbody tr');
  const rc = await rows.count();
  const vals: string[] = [];
  let inputs = 0;
  for (let i = 0; i < rc; i++) {
    const cell = rows.nth(i).locator('td').nth(col);
    const inp = cell.locator('input, textarea');
    if (await inp.count() > 0) { inputs += await inp.count(); vals.push(String(await inp.first().inputValue())); }
    else vals.push((await cell.innerText()).replace(/\s+/g, ' ').trim());
  }
  console.log(`[AC12][${PHASE}] 行数=${rc} 「元素代码」列值=${JSON.stringify(vals)} 该列 input 数=${inputs}`);
  await card.screenshot({ path: `e2e/screenshots/ft6-ac12-${PHASE}.png` }).catch(() => {});
  expect(rc, '0 行 ⇒ 空跑').toBe(4);
  expect(vals, '「元素代码」列值应始终为 Cu/301/Cu/Ag').toEqual(['Cu', '301', 'Cu', 'Ag']);
  if (EXPECT_INPUT) expect(inputs, 'INPUT_TEXT 阶段该列必须是 <input>').toBe(4);
  else expect(inputs, 'BASIC_DATA 阶段该列不得有 <input>').toBe(0);
});
