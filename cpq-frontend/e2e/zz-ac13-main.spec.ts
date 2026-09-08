import { test, expect } from '@playwright/test';
const BASE = 'http://localhost:5196';

test('AC-13 主线亲验：旧按钮消失 / 新按钮在 / 工具栏顺序', async ({ page }) => {
  await page.goto(BASE + '/login');
  await page.fill('input[placeholder*="用户名"], #username, input[name="username"]', 'admin');
  await page.fill('input[type="password"]', 'Admin@2026');
  await page.click('button[type="submit"], button:has-text("登 录"), button:has-text("登录")');
  await page.waitForURL(/^(?!.*login).*$/, { timeout: 20000 });

  await page.goto(BASE + '/quotations');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2500);

  // ① 旧按钮：DOM 中无该文案（antd 两字按钮会被插空格，用 normalize）
  const html = await page.content();
  const flat = html.replace(/\s+/g, '');
  const oldGone = !flat.includes('从基础数据导入');
  const newThere = flat.includes('导入报价数据');
  console.log('LF-EVAL 旧按钮「从基础数据导入」存在 =', !oldGone);
  console.log('LF-EVAL 新按钮「导入报价数据」存在 =', newThere);

  // ③ 工具栏顺序：取所有 button 的可见文本
  const btns = await page.locator('button').allInnerTexts();
  const clean = btns.map(t => t.replace(/\s+/g, '')).filter(Boolean);
  console.log('LF-EVAL 按钮序列 =', JSON.stringify(clean));

  await page.screenshot({ path: process.env.SHOT || 'ac13.png', fullPage: false });
  expect(oldGone, '「从基础数据导入」应已从 DOM 消失').toBeTruthy();
  expect(newThere, '「导入报价数据」应存在').toBeTruthy();
});
