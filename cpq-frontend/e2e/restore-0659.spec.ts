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

  // 🚨 v1 的坑：用 `input[value="${BAD}"]` 定位 —— Playwright 每个动作都会**重新求值** locator，
  //    值一改就再也匹配不上 ⇒ pressSequentially 卡到超时。改用「与写入时同一条选择器 + 按
  //    inputValue() 找下标」，拿到稳定的 nth() 之后就不再依赖值本身。
  const all = page.locator('table tr:has(input) input:not([disabled]):not([readonly])');
  const n = await all.count();
  const vals0 = await all.evaluateAll(es => es.map(e => (e as HTMLInputElement).value));
  const idx = vals0.indexOf(BAD);
  console.log(`[复原] 可输入格 ${n} 个；"${BAD}" 在下标 ${idx}`);
  expect(idx, `找不到 "${BAD}" ⇒ 没有可复原的目标（可能已被复原）`).toBeGreaterThanOrEqual(0);

  const bad = all.nth(idx);
  await bad.click();
  await bad.press('Control+a');
  await bad.pressSequentially(GOOD, { delay: 60 });
  await bad.press('Tab');
  console.log(`[复原] 写入后回读 = "${await bad.inputValue()}"`);
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
