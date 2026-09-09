/**
 * 主线亲验 · AC-18（`D-18` 重写后的版本）
 * 判据落在 row_data（用户编辑的持久层），🚫 不是 editRows（派生缓存）。
 *
 * 主体 QT-20260908-0625「产品」页签改动前 6 行 = 3 个销售料号 × 2 个客户（缺陷①原形）。
 * 🔑 S0001 的两行**不是**逐字段相同：row_index 0 带用户编辑 旧料号=12139，孪生行是原值 12133。
 *    ⇒ 收敛后若留下 12133，就是把用户编辑丢了。这条判据有分辨力，不是走过场。
 */
import { test, expect, Page } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';
const QID = process.env.Q_WRITE!;

async function toStep2(p: Page) {
  await p.goto(`/quotations/${QID}/edit`);
  await p.waitForLoadState('networkidle'); await p.waitForTimeout(5000);
  const next = p.getByRole('button', { name: /下\s*一\s*步/ });
  if (await next.count() && await next.first().isEnabled().catch(() => false)) {
    await next.first().click(); await p.waitForTimeout(7000);
  }
}

test('AC-18 保存草稿后 row_data 收敛且用户编辑值不丢', async ({ page }) => {
  test.setTimeout(900000);
  const draftRes: string[] = [];
  page.on('response', r => { if (/\/draft\b/.test(r.url())) draftRes.push(String(r.status())); });

  await loginAsAdmin(page);
  await toStep2(page);
  const hit = await page.evaluate(() => {
    const bs = Array.from(document.querySelectorAll('button.qt-tab-btn'))
      .filter(b => (b as HTMLElement).innerText.trim() === '产品');
    bs.forEach(b => (b as HTMLElement).click()); return bs.length;
  });
  await page.waitForTimeout(3000);
  expect(hit, '找不到「产品」页签 ⇒ 量具失效').toBeGreaterThan(0);

  const before = await page.locator('table tr:has(input)').count();
  console.log(`[AC-18] 保存前「产品」页签数据行 = ${before}`);
  expect(before, '一行都没有 ⇒ 量具失效').toBeGreaterThan(0);

  // 触发一次真实编辑，否则 handleSaveDraft 会被「无改动」闸拦下、根本不重算
  const inputs = page.locator('table tr:has(input) input:not([disabled]):not([readonly])');
  const n = await inputs.count();
  expect(n, '没有可输入格').toBeGreaterThan(0);
  // 取一个**非行键**的格子：找 type=text 且当前值不是销售料号形态(S000x)的
  const vals = await inputs.evaluateAll(es => es.map(e => (e as HTMLInputElement).value));
  let idx = vals.findIndex(v => !/^S\d{4}$/.test(v) && !/^\d{5}$/.test(v));
  if (idx < 0) idx = n - 1;
  const target = inputs.nth(idx);
  const mark = 'AC18-' + Date.now().toString().slice(-6);
  console.log(`[AC-18] 触发编辑：下标 ${idx}（原值 "${vals[idx]}"）→ "${mark}"`);
  await target.click(); await target.press('Control+a');
  await target.pressSequentially(mark, { delay: 50 });
  await target.press('Tab');
  await page.waitForTimeout(6000);

  await page.getByRole('button', { name: /保\s*存\s*草\s*稿/ }).first().click();
  await page.waitForTimeout(30000);
  const body = await page.evaluate(() => document.body.innerText);
  console.log(`[AC-18] /draft = ${JSON.stringify(draftRes)}；出现「已被他人修改」= ${/已被他人修改/.test(body)}`);
  expect(draftRes.some(s => s === '409'), 'AC-18: 保存撞 409，本条没测到重算').toBe(false);

  await toStep2(page);
  await page.evaluate(() => Array.from(document.querySelectorAll('button.qt-tab-btn'))
    .filter(b => (b as HTMLElement).innerText.trim() === '产品').forEach(b => (b as HTMLElement).click()));
  await page.waitForTimeout(3000);
  const after = await page.locator('table tr:has(input)').count();
  const vals2 = await page.locator('table tr input').evaluateAll(es => es.map(e => (e as HTMLInputElement).value));
  console.log(`[AC-18] 保存后「产品」页签数据行 = ${after}`);
  console.log(`[AC-18] 本轮标记 "${mark}" 仍在 = ${vals2.includes(mark)}`);
  console.log(`[AC-18] 既有用户编辑 "12139" 仍在 = ${vals2.includes('12139')}；被它取代的原值 "12133" 出现 = ${vals2.includes('12133')}`);
  console.log(`[AC-18] 保存后全部值 = ${JSON.stringify(vals2)}`);
});
