import { test, expect } from '@playwright/test';

/**
 * S-UI contract slice.  The currency/customer payloads are isolated route
 * fixtures; no global list counts or shared business rows are asserted.
 */
test.describe('S-UI · task-260927 currency management and strategy', () => {
  test.beforeEach(async ({ page }) => {
    const login = await page.request.post('/api/cpq/auth/login', {
      data: { username: 'admin', password: 'Admin@2026' },
    });
    expect(login.ok(), 'API 登录应成功').toBeTruthy();
    await page.route('**/api/cpq/currencies**', async (route) => {
      const url = route.request().url();
      if (/\/rates(?:\/import)?/.test(url)) {
        if (route.request().method() === 'GET') {
          await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data: {
            currency: 'USD', items: [
              { id: 'r-2', rate: '7.300000000000', displayRate: '7.300000000000', effectiveDate: '2026-09-27', source: 'FX260927_LAST', updatedAt: '2026-09-27T11:00:00Z' },
              { id: 'r-1', rate: '7.200000000000', displayRate: '7.200000000000', effectiveDate: '2026-09-27', source: 'FX260927_FIRST', updatedAt: '2026-09-27T10:00:00Z' },
            ],
          } }) });
        } else {
          await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data: { insertedCount: 1, failedCount: 0 } }) });
        }
        return;
      }
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data: [
        { code: 'CNY', name: '人民币', symbol: '¥', enabled: true, isBase: true, latestRate: '1.000000000000' },
        { code: 'USD', name: '美元', symbol: '$', enabled: true, isBase: false, latestRate: '7.300000000000', latestRateDate: '2026-09-27' },
      ] }) });
    });
  });

  test('AC-1/2/24/25: currency list, rate detail/matrix and import entry are visible without refresh action', async ({ page }) => {
    await page.goto('/system/currencies');
    await expect(page.getByText('币种管理', { exact: true })).toBeVisible();
    await expect(page.getByText('1 USD = 7.300000000000 CNY', { exact: true })).toBeVisible();
    await expect(page.getByText('1.000000000000', { exact: true })).toBeVisible();
    await expect(page.getByRole('button', { name: /刷新当天汇率|刷新汇率/ })).toHaveCount(0);

    await page.getByRole('button', { name: '批量导入汇率' }).click();
    const importDrawer = page.locator('.ant-drawer').filter({ hasText: '批量导入汇率' }).last();
    await expect(importDrawer).toBeVisible();
    await expect(importDrawer.getByRole('button', { name: '下载导入模板' })).toBeVisible();
    await importDrawer.getByRole('button', { name: 'Close' }).click();

    await page.getByRole('button', { name: '历史汇率' }).last().click();
    const drawer = page.locator('.ant-drawer').filter({ hasText: 'USD 汇率' }).last();
    await expect(drawer).toBeVisible();
    await expect(drawer.getByRole('tab', { name: '明细' })).toBeVisible();
    await expect(drawer.getByRole('tab', { name: '矩阵' })).toBeVisible();
    await expect(drawer.getByText('同一生效日期允许多次更新，策略取当天最后更新的一条；汇率保存 12 位小数。')).toBeVisible();
    await expect(drawer.getByText('7.300000000000', { exact: true })).toBeVisible();
  });

  test('AC-10/14/27: strategy dynamic parameters distinguish latest, window and date modes', async ({ page }) => {
    await page.route('**/api/cpq/customers?page=0**', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data: {
        content: [{ id: 'customer-1', code: 'CUST-FX260927', name: 'FX 客户', level: 'GOLD' }], totalElements: 1,
      } }) });
    });
    await page.route('**/api/cpq/pricing-strategies**', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data: { content: [], totalElements: 0 } }) });
    });
    await page.route('**/api/cpq/customers/customer-1/exchange-rate-strategy', async (route) => {
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data: { method: 'LATEST' } }) });
    });

    await page.goto('/pricing');
    await expect(page.getByText('FX 客户', { exact: true })).toBeVisible();
    await page.getByText('FX 客户', { exact: true }).click();
    await page.getByRole('tab', { name: '汇率策略' }).click();
    await expect(page.getByText('无需填写，取目标日期当天最后更新的一条')).toBeVisible();
    await expect(page.getByText('窗口', { exact: true })).toHaveCount(0);

    const select = page.locator('.ant-form-item').filter({ hasText: '取值方式' }).locator('.ant-select');
    await select.click();
    await page.getByText('平均汇率', { exact: true }).click();
    await expect(page.getByText('窗口', { exact: true })).toBeVisible();
    await select.click();
    await page.getByText('每月', { exact: true }).click();
    await expect(page.getByText('日期', { exact: true }).last()).toBeVisible();
    await expect(page.getByText('日期以报价单创建日为基准；短月没有该日时跳过该月继续向前找，命中日期无汇率则创建失败。')).toBeVisible();
    await select.click();
    await page.getByText('指定日期', { exact: true }).click();
    await expect(page.locator('input[placeholder="请选择日期"]')).toBeVisible();
  });
});
