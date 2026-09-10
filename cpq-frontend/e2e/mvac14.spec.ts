/**
 * task-260909 · 主线亲验 AC-14（报价侧仍可编辑可保存 —— 零回归门禁）
 * 载体：测试员用 API 建的一次性单 QT-20260909-0799 / T260909FT-AC14#r3xck1
 * 🚫 不在任何既有报价单上改数据。
 */
import { test, expect } from '@playwright/test';
const SHOT='/tmp/claude-1000/-home-joii-project-cpq/dd3dc6c5-18d8-4be3-b01e-677976918873/scratchpad/verify';
test.use({ viewport: { width: 1920, height: 1200 } });

test('AC-14 亲验：报价侧 INPUT_* 仍是 <input>，改值走 quote-card-edit 并可读回', async ({ page }) => {
  test.setTimeout(600000);
  const calls: string[] = [];
  page.on('response', (r:any) => {
    const u = r.url();
    if (u.includes('quote-card-edit') || u.includes('save-draft') || u.includes('saveDraft'))
      calls.push(`${r.status()} ${r.request().method()} ${u.split('/api/cpq')[1]||u}`);
  });

  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products)/,{timeout:20000});

  await page.goto('/quotations');
  await page.waitForLoadState('networkidle');
  const box = page.locator('input[placeholder*="搜索报价单号"]');
  await box.fill('QT-20260909-0799'); await box.press('Enter');
  await page.waitForTimeout(3000);
  expect(await page.locator('tbody tr').count(), '搜索须命中且真过滤').toBe(1);
  await page.locator('tbody tr').first().locator('a').first().click();
  await page.waitForTimeout(4000);
  await page.locator('button').filter({ hasText: /^\s*编\s*辑\s*$/ }).first().click();
  await page.waitForTimeout(5000);
  await page.locator('button').filter({ hasText: /下\s*一\s*步/ }).first().click();
  await page.waitForTimeout(10000);
  await page.screenshot({ path: SHOT+'/60-ac14-step2.png', fullPage: true });

  const card = page.locator('.qt-product-card').first();
  const tabs = await card.locator('button.qt-tab-btn').allInnerTexts();
  console.log('报价侧页签 =', JSON.stringify(tabs.map(t=>t.replace(/\s+/g,''))));
  const target = tabs.map(t=>t.replace(/\s+/g,'')).find(t=>t.includes('T260909FT'));
  expect(target, 'AC-14：必须找到 T260909FT 的报价侧页签').toBeTruthy();
  // ⚠️ 测试员建载体时把 COMP-2424 追加了两次 ⇒ 同名页签出现 2 个（strict mode violation）。
  //    取第一个即可；这是造数残留，不影响 AC-14 本身。
  await card.locator('button.qt-tab-btn').filter({ hasText: target! }).first().click();
  await page.waitForTimeout(6000);

  const inputs = card.locator('table:visible tbody td input');
  const n = await inputs.count();
  console.log(`AC-14 前半段：该页签承载值 <input> 数 = ${n}`);
  expect(n, 'AC-14：QUOTE 方言的 INPUT_* 字段必须仍渲染为 <input>').toBeGreaterThan(0);

  // 改值 → 失焦
  const stamp = 'MAINVERIFY-' + Date.now().toString().slice(-6);
  const first = inputs.first();
  const before = await first.inputValue();
  await first.fill(stamp);
  await first.blur();
  await page.waitForTimeout(6000);
  console.log(`AC-14：改值 "${before}" → "${stamp}"`);
  console.log('AC-14：观测到的写入调用 =', JSON.stringify(calls));
  expect(calls.filter(c=>c.includes('quote-card-edit')).length,
    'AC-14：必须观测到 quote-card-edit 调用').toBeGreaterThan(0);
  expect(calls.some(c=>c.startsWith('200')), 'AC-14：写入必须返回 200').toBeTruthy();

  // 刷新读回
  await page.reload();
  await page.waitForTimeout(6000);
  await page.locator('button').filter({ hasText: /下\s*一\s*步/ }).first().click().catch(()=>{});
  await page.waitForTimeout(10000);
  const card2 = page.locator('.qt-product-card').first();
  await card2.locator('button.qt-tab-btn').filter({ hasText: target! }).first().click().catch(()=>{});
  await page.waitForTimeout(6000);
  const readBack = await card2.locator('table:visible tbody td input').first().inputValue().catch(()=>'(读不到)');
  console.log(`AC-14：刷新后读回 = "${readBack}"`);
  await page.screenshot({ path: SHOT+'/61-ac14-读回.png', fullPage: true });
  expect(readBack, 'AC-14：刷新后必须读回刚写的值').toBe(stamp);
  console.log('✅ AC-14 亲验通过');
});
