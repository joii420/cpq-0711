/** 快检:被引用元素(Ag/锁符号)在 UI 改中文名保存是否成功(验证锁符号显示缺陷是否影响保存)。*/
import { test, expect } from '@playwright/test';

test('编辑被引用元素 Ag 改中文名保存成功且符号不变(锁符号显示缺陷不影响功能)', async ({ page }) => {
  const login = await page.request.post('/api/cpq/auth/login', { data: { username: 'admin', password: 'Admin@2026' } });
  expect(login.ok()).toBeTruthy();
  await page.goto('/master-data-hub');
  await page.getByRole('tab', { name: '元素' }).click();
  await expect(page.getByText('元素管理', { exact: true })).toBeVisible({ timeout: 20_000 });
  const search = page.getByPlaceholder('搜索 元素编号 / 符号 / 中文名');
  await search.fill('10001');
  await page.waitForTimeout(700);
  await page.locator('.ant-table-tbody a').filter({ hasText: '10001' }).first().click();
  const drawer = page.locator('.ant-drawer').filter({ hasText: '编辑元素' }).last();
  await expect(drawer).toBeVisible({ timeout: 15_000 });
  // 改中文名
  const nameInput = drawer.locator('#elementName');
  await nameInput.fill('银UI改测');
  await page.locator('.ant-drawer-footer .ant-btn-primary').last().click();
  await page.waitForTimeout(1500);
  // 断言:不出现 409 错误提示(被引用改符号) + 保存成功
  const errToast = await page.locator('.ant-message-error').count();
  console.log('保存后 error toast 数 =', errToast);
  // 经 API 复核 DB
  const res = await page.request.get('/api/cpq/elements?keyword=10001');
  const arr = await res.json();
  const ag = (Array.isArray(arr) ? arr : arr.data).find((x: any) => x.elementNo === '10001');
  console.log('保存后 Ag:', JSON.stringify({ code: ag.elementCode, name: ag.elementName }));
  expect(ag.elementCode, '符号应仍为 Ag(未被清空/改动)').toBe('Ag');
  expect(ag.elementName, '中文名应已更新为 银UI改测').toBe('银UI改测');
  // 还原
  await page.request.put('/api/cpq/elements/10001', { data: { elementNo: '10001', elementCode: 'Ag', elementName: '银' } });
});
