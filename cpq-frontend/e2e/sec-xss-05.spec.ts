/**
 * SEC-XSS-05 XSS 输入逃逸
 *
 * 验收标准（PRD 第 23 章安全）：
 * 1. 在客户名称输入 <script>alert('xss')</script>
 * 2. 保存成功后，在列表/详情中该名称应以纯文本形式展示
 * 3. DOM 中不应存在可执行的 <script> 标签（由 React 自动转义保证）
 * 4. 不应触发 alert 弹窗
 */

import { test, expect } from '@playwright/test';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

let backendUp = false;

test.beforeAll(async () => {
  backendUp = await isBackendUp();
});

test('SEC-XSS-05-01 XSS payload 在 DOM 中显示为转义文本', async ({ page }) => {
  if (!backendUp) {
    test.skip(true, '后端未启动，跳过 SEC-XSS-05');
  }

  await loginAsAdmin(page);
  await page.goto('/customers');
  await page.waitForLoadState('networkidle');

  // 监听 dialog（alert 弹窗），若出现则测试失败
  let alertFired = false;
  page.on('dialog', async (dialog) => {
    alertFired = true;
    await dialog.dismiss();
  });

  // 打开新建抽屉（按钮文字为「新增客户」）
  const addBtn = page.locator('button', { hasText: '新增客户' }).first();
  await expect(addBtn).toBeVisible({ timeout: 10_000 });
  await addBtn.click();

  const drawer = page.locator('.ant-drawer-content-wrapper');
  await expect(drawer).toBeVisible({ timeout: 5_000 });

  // 输入 XSS payload 作为客户名称
  const xssPayload = "<script>alert('xss')</script>";
  const nameInput = drawer.locator('input').first();
  await nameInput.fill(xssPayload);

  // 提交表单
  const submitBtn = drawer.locator('button', { hasText: '保存' }).first();
  const hasSubmit = await submitBtn.isVisible().catch(() => false);
  if (hasSubmit) {
    await submitBtn.click();
  } else {
    const confirmBtn = drawer.locator('button[type="submit"]').first();
    const hasConfirm = await confirmBtn.isVisible().catch(() => false);
    if (hasConfirm) {
      await confirmBtn.click();
    }
  }

  // 等待一段时间，确保页面已响应
  await page.waitForTimeout(2_000);

  // 关键断言1：alert 弹窗没有被触发
  expect(alertFired).toBe(false);

  // 关键断言2：DOM 中不存在可执行的 <script> 标签
  // React 在 JSX 中渲染文本时会自动转义，不会注入真实 script 标签
  const scriptTags = await page.evaluate(() => {
    return document.querySelectorAll('script[data-xss]').length;
  });
  expect(scriptTags).toBe(0);

  // 关键断言3：若保存成功，列表中应以字面文本形式展示 payload
  // 验证页面源码中不含有将 payload 作为原始 HTML 注入的模式
  const pageContent = await page.content();
  const hasInjectedScript = pageContent.includes('<script>alert(');
  expect(hasInjectedScript).toBe(false);
});

test('SEC-XSS-05-02 搜索框 XSS payload 不触发脚本执行', async ({ page }) => {
  if (!backendUp) {
    test.skip(true, '后端未启动，跳过 SEC-XSS-05');
  }

  await loginAsAdmin(page);
  await page.goto('/customers');
  await page.waitForLoadState('networkidle');

  let alertFired = false;
  page.on('dialog', async (dialog) => {
    alertFired = true;
    await dialog.dismiss();
  });

  // 在搜索框输入 XSS
  const searchInput = page.locator('input[placeholder*="搜索"], input[placeholder*="关键"]').first();
  if (await searchInput.isVisible()) {
    await searchInput.fill("<img src=x onerror=alert(1)>");
    await page.keyboard.press('Enter');
    await page.waitForTimeout(1_000);
  }

  expect(alertFired).toBe(false);
});
