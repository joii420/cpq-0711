/** 一次性：把量具误改的行键写回（同一 UI 路径，不动生 SQL） */
import { test, expect } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';
const QID = process.env.Q_WRITE!, BAD = process.env.BAD_VAL!, GOOD = process.env.GOOD_VAL!;

test('restore row key', async ({ page }) => {
  test.setTimeout(600000);
  await loginAsAdmin(page);
  await page.goto(`/quotations/${QID}/edit`);
  await page.waitForLoadState('networkidle'); await page.waitForTimeout(5000);
  const next = page.getByRole('button', { name: /下\s*一\s*步/ });
  if (await next.count() && await next.first().isEnabled().catch(() => false)) {
    await next.first().click(); await page.waitForTimeout(7000);
  }
  await page.evaluate(() => Array.from(document.querySelectorAll('button.qt-tab-btn'))
    .filter(b => (b as HTMLElement).innerText.trim() === '产品')
    .forEach(b => (b as HTMLElement).click()));
  await page.waitForTimeout(3000);

  const bad = page.locator(`table tr input[value="${BAD}"]`);
  const n = await bad.count();
  console.log(`[复原] 找到值为 "${BAD}" 的格子 ${n} 个`);
  expect(n, `找不到 "${BAD}" ⇒ 没有可复原的目标（可能已被复原或量具选择器失效）`).toBe(1);

  await bad.first().click();
  await bad.first().press('Control+a');
  await bad.first().pressSequentially(GOOD, { delay: 60 });
  await bad.first().press('Tab');
  await page.waitForTimeout(4000);
  await page.getByRole('button', { name: /保\s*存\s*草\s*稿/ }).first().click();
  await page.waitForTimeout(20000);

  await page.reload(); await page.waitForLoadState('networkidle'); await page.waitForTimeout(5000);
  const nx = page.getByRole('button', { name: /下\s*一\s*步/ });
  if (await nx.count() && await nx.first().isEnabled().catch(() => false)) { await nx.first().click(); await page.waitForTimeout(7000); }
  await page.evaluate(() => Array.from(document.querySelectorAll('button.qt-tab-btn'))
    .filter(b => (b as HTMLElement).innerText.trim() === '产品').forEach(b => (b as HTMLElement).click()));
  await page.waitForTimeout(3000);
  const vals = await page.locator('table tr input').evaluateAll(es => es.map(e => (e as HTMLInputElement).value));
  console.log(`[复原] 重开后 "${GOOD}" 在 = ${vals.includes(GOOD)} ; "${BAD}" 还在 = ${vals.includes(BAD)}`);
  expect(vals.includes(GOOD), '复原失败').toBe(true);
  expect(vals.includes(BAD), '坏值仍在').toBe(false);
});
