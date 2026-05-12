/**
 * SEC-RBAC-01 菜单按角色过滤
 *
 * 验收标准（PRD 第 1.4 章权限矩阵）：
 * - SYSTEM_ADMIN 可见「系统管理」菜单
 * - SALES_REP 不可见「系统管理」、「数据源管理」等 SYSTEM_ADMIN 专属菜单
 * - 验证方式：分别以 admin、alice 登录，检查侧边栏菜单项
 *
 * Selector 说明：
 * - Ant Design Menu inline 模式渲染子菜单父项为 .ant-menu-submenu-title
 * - 叶子菜单项渲染为 .ant-menu-item
 * - 无论是否折叠，DOM 中都存在这些元素；直接用 page.getByText() 更宽泛
 */

import { test, expect } from '@playwright/test';
import { isBackendUp, loginAsAdmin, loginAsAlice } from './fixtures/auth';

let backendUp = false;

test.beforeAll(async () => {
  backendUp = await isBackendUp();
});

test('SEC-RBAC-01-01 admin 可见系统管理菜单', async ({ page }) => {
  if (!backendUp) {
    test.skip(true, '后端未启动，跳过 SEC-RBAC-01');
  }

  await loginAsAdmin(page);
  await page.goto('/dashboard');
  await page.waitForLoadState('networkidle');

  // 系统管理菜单项（子菜单父项）渲染在侧边栏，直接查找文本
  // 不限制在 .ant-menu 内，避免 shadow DOM 层级问题
  const systemMenu = page.getByText('系统管理').first();
  await expect(systemMenu).toBeVisible({ timeout: 10_000 });
});

test('SEC-RBAC-01-02 admin 可见数据源管理菜单', async ({ page }) => {
  if (!backendUp) {
    test.skip(true, '后端未启动，跳过 SEC-RBAC-01');
  }

  await loginAsAdmin(page);
  await page.goto('/dashboard');
  await page.waitForLoadState('networkidle');

  // 数据源管理是叶子菜单项，直接用 getByText
  const dsMenu = page.getByText('数据源管理').first();
  await expect(dsMenu).toBeVisible({ timeout: 10_000 });
});

test('SEC-RBAC-01-03 alice(SALES_MANAGER) 看不到系统管理菜单', async ({ page }) => {
  if (!backendUp) {
    test.skip(true, '后端未启动，跳过 SEC-RBAC-01');
  }

  await loginAsAlice(page);
  await page.goto('/dashboard');
  await page.waitForLoadState('networkidle');

  // v5-import-tester 是 SALES_MANAGER，系统管理菜单仅 SYSTEM_ADMIN 可见
  // 检查侧边栏 Menu 内没有「系统管理」文字
  const sider = page.locator('.ant-layout-sider');
  await expect(sider).toBeVisible({ timeout: 5_000 });
  // 等待菜单渲染完成
  await page.waitForTimeout(500);
  const systemMenuCount = await sider.getByText('系统管理').count();
  expect(systemMenuCount).toBe(0);
});

test('SEC-RBAC-01-04 alice(SALES_MANAGER) 看不到数据源管理菜单', async ({ page }) => {
  if (!backendUp) {
    test.skip(true, '后端未启动，跳过 SEC-RBAC-01');
  }

  await loginAsAlice(page);
  await page.goto('/dashboard');
  await page.waitForLoadState('networkidle');

  // 数据源管理仅 SYSTEM_ADMIN 可见
  const sider = page.locator('.ant-layout-sider');
  await expect(sider).toBeVisible({ timeout: 5_000 });
  await page.waitForTimeout(500);
  const dsMenuCount = await sider.getByText('数据源管理').count();
  expect(dsMenuCount).toBe(0);
});
