import { test, expect } from '@playwright/test';

const FE = 'http://localhost:5176';
const SHOT = 'e2e/screenshots';

test('TC0712 编辑保存 + 版本切换只读', async ({ page, context }) => {
  const msgs: string[] = [];
  page.on('console', (m) => { if (m.type() === 'error') console.log('LF-ERR', m.text()); });

  const lr = await context.request.post(`${FE}/api/cpq/auth/login`, {
    data: { username: 'admin', password: 'Admin@2026' }, headers: { 'Content-Type': 'application/json' },
  });
  expect(lr.ok()).toBeTruthy();

  await page.goto(`${FE}/master-data-hub`, { waitUntil: 'networkidle' });
  await page.getByText('料号核价', { exact: true }).first().click();
  await page.waitForTimeout(1000);
  const search = page.locator('input[placeholder*="搜索"]').first();
  await search.fill('S-3120014539');
  await page.waitForTimeout(1200);
  await page.getByRole('cell', { name: 'S-3120014539', exact: true }).first().click();
  await expect(page.locator('.ant-drawer').first()).toBeVisible({ timeout: 8000 });
  await page.waitForTimeout(5000);

  // 切到 电镀成本（PLATING，无 MASTER 列，可编辑）
  await page.locator('.ant-drawer .ant-tabs-tab', { hasText: '电镀成本' }).first().click();
  await page.waitForTimeout(2500);

  // 编辑权：保存按钮 + 新增行 应可见
  const saveBtn = page.getByRole('button', { name: /保存/ });
  await expect(saveBtn, 'A-06: 编辑角色应见保存按钮').toBeVisible({ timeout: 6000 });
  console.log('LF-EDIT-SAVE-VISIBLE = true');
  await page.screenshot({ path: `${SHOT}/tc0712-07-plating-edit.png` });

  // 改价：定位第一个 InputNumber，改值
  const numInput = page.locator('.ant-drawer .ant-input-number-input').first();
  const before = await numInput.inputValue();
  const newVal = (Number(before || '1') + 3).toString();
  await numInput.click();
  await numInput.fill(newVal);
  await numInput.blur();
  console.log('LF-PRICE-CHANGE =', before, '->', newVal);

  // 保存
  await saveBtn.click();
  await page.waitForTimeout(2500);
  const okMsg = await page.locator('.ant-message-success, .ant-message-info').allInnerTexts().catch(() => []);
  console.log('LF-SAVE-MSG =', JSON.stringify(okMsg));
  await page.screenshot({ path: `${SHOT}/tc0712-08-saved.png` });
  expect(okMsg.join(' '), 'F5: 保存后应有 success/info 提示').toMatch(/已保存|版本|未变化/);

  // 版本切换只读：打开版本下拉，选历史版
  const vsel = page.locator('.ant-drawer .ant-select').first();
  await vsel.click();
  await page.waitForTimeout(800);
  const opts = page.locator('.ant-select-item-option');
  const optCount = await opts.count();
  console.log('LF-VERSION-OPTIONS =', optCount);
  if (optCount > 1) {
    await opts.nth(1).click(); // 选一个历史版
    await page.waitForTimeout(2000);
    const saveAfter = await page.getByRole('button', { name: /保存/ }).count();
    console.log('LF-SAVE-BTN-ON-HISTORY =', saveAfter, '(应=0 只读)');
    await page.screenshot({ path: `${SHOT}/tc0712-09-history-readonly.png` });
    expect(saveAfter, 'E-04: 历史版应只读(无保存)').toBe(0);
  }
  console.log('LF-DONE');
});
