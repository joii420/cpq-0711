import { test, expect } from '@playwright/test';
const BASE = 'http://localhost:5196';
const QID  = 'aadf8c30-d0ba-4f3b-9fd9-e056a35bd1b8';

test('AC-9 主线亲验：改一格 → 保存 → 切走切回 → 刷新，三时点一致', async ({ page }) => {
  test.setTimeout(180000);
  await page.goto(BASE + '/login');
  await page.fill('input[placeholder*="用户名"], #username, input[name="username"]', 'admin');
  await page.fill('input[type="password"]', 'Admin@2026');
  await page.click('button[type="submit"], button:has-text("登 录"), button:has-text("登录")');
  await page.waitForTimeout(2500);

  await page.goto(`${BASE}/quotations/${QID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(6000);

  // 走到 Step2（产品卡片）
  for (let i = 0; i < 3; i++) {
    const next = page.locator('button:has-text("下一步"), button:has-text("下 一 步")').first();
    if (await next.count() && await next.isEnabled()) { await next.click(); await page.waitForTimeout(2500); }
    if (await page.locator('.qt-tab-btn').count() > 0) break;
  }
  const tabs = await page.locator('.qt-tab-btn').allInnerTexts();
  console.log('LF-EVAL 页签数 =', tabs.length, JSON.stringify(tabs.map(t=>t.replace(/\s+/g,'')).slice(0,20)));

  // 找第一个可编辑的数字输入框
  const inputs = page.locator('.qt-cell input, table input[type="text"], table input');
  const n = await inputs.count();
  console.log('LF-EVAL 可见输入框数 =', n);
  expect(n, '页面上必须有可编辑单元格，否则本用例是空验证').toBeGreaterThan(0);

  let target = -1, orig = '';
  for (let i = 0; i < Math.min(n, 40); i++) {
    const v = await inputs.nth(i).inputValue().catch(()=> '');
    if (/^-?\d+(\.\d+)?$/.test(v.trim()) && v.trim() !== '') { target = i; orig = v.trim(); break; }
  }
  console.log('LF-EVAL 选中下标 =', target, ' 原值 =', orig);
  expect(target, '必须找到一个有数值的单元格').toBeGreaterThanOrEqual(0);

  const NEWV = String(Number(orig) + 7.5);
  await inputs.nth(target).fill(NEWV);
  await inputs.nth(target).blur();
  await page.waitForTimeout(3000);

  // 保存
  await page.locator('button:has-text("保 存"), button:has-text("保存")').first().click();
  await page.waitForTimeout(6000);

  // 时点1：保存后当场
  const t1 = (await inputs.nth(target).inputValue()).trim();
  console.log('LF-EVAL 时点1(保存后) =', t1);

  // 时点2：切走再切回
  if (tabs.length > 1) {
    await page.locator('.qt-tab-btn').nth(1).click(); await page.waitForTimeout(2000);
    await page.locator('.qt-tab-btn').nth(0).click(); await page.waitForTimeout(3000);
  }
  const t2 = (await inputs.nth(target).inputValue()).trim();
  console.log('LF-EVAL 时点2(切走切回) =', t2);

  // 时点3：刷新页面
  await page.reload(); await page.waitForLoadState('networkidle'); await page.waitForTimeout(8000);
  for (let i = 0; i < 3; i++) {
    const next = page.locator('button:has-text("下一步"), button:has-text("下 一 步")').first();
    if (await next.count() && await next.isEnabled()) { await next.click(); await page.waitForTimeout(2500); }
    if (await page.locator('.qt-tab-btn').count() > 0) break;
  }
  const t3 = (await page.locator('.qt-cell input, table input[type="text"], table input').nth(target).inputValue()).trim();
  console.log('LF-EVAL 时点3(刷新后) =', t3, ' 期望 =', NEWV);

  expect(t1).toBe(NEWV);
  expect(t2).toBe(NEWV);
  expect(t3).toBe(NEWV);
});
