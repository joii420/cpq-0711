/**
 * E2E-DRIFT-04 漂移检测
 *
 * 完整流程（PRD 第 20 章变更日志 / 主数据漂移）：
 * 1. 修改一条主数据字段值
 * 2. 系统自动或手动触发漂移检测
 * 3. 变更日志中出现对应的 DRIFT 记录
 * 4. 操作人可以确认或回滚漂移
 *
 * 当前状态：SKELETON（漂移检测依赖后台任务调度，E2E 难以精确控制触发时机）
 * TODO: 提供一个「立即检测」手动触发按钮后再实现完整流程
 *
 * 已解开的两个简化用例：
 * - 变更日志页可访问且不崩溃（修复了 changeLogService items/content 字段映射）
 * - 主数据总览页可访问（该页面用 Card 展示，不是 ant-table）
 */

import { test, expect } from '@playwright/test';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

let backendUp = false;

test.beforeAll(async () => {
  backendUp = await isBackendUp();
});

test.skip('E2E-DRIFT-04 完整漂移检测流程（骨架，依赖调度触发）', async ({ page }) => {
  // 前置条件：有主数据记录可修改
  //
  // Step 1: 修改主数据字段
  await loginAsAdmin(page);
  await page.goto('/master-data');
  await page.waitForLoadState('networkidle');

  // TODO: 找到一条记录并修改某字段
  // await page.locator('.ant-table-row').first().locator('button', { hasText: '编辑' }).click();
  // const drawer = page.locator('.ant-drawer-content-wrapper');
  // await drawer.locator('input').first().fill('漂移测试值_' + Date.now());
  // await drawer.locator('button', { hasText: '保存' }).click();

  // Step 2: 触发漂移检测（需要后端支持手动触发接口）
  // await page.request.post('/api/cpq/master-data/trigger-drift-check');

  // Step 3: 检查变更日志
  // await page.goto('/change-log');
  // await expect(page.locator('text=DRIFT').first()).toBeVisible({ timeout: 15_000 });

  expect(true).toBe(true);
});

// 变更日志页可访问（且不崩溃）
// 修复：changeLogService 将后端 Spring Page 格式 (content/totalElements) 映射为 (items/total)
test('E2E-DRIFT-04 变更日志页可访问', async ({ page }) => {
  if (!backendUp) {
    test.skip(true, '后端未启动，跳过 E2E-DRIFT-04');
  }

  await loginAsAdmin(page);
  await page.goto('/change-log');
  await page.waitForLoadState('networkidle');

  // 等待 React 渲染稳定（修复 ChangeLogCenterPage 的 items.length 崩溃后页面应正常渲染）
  await page.waitForTimeout(500);

  // 页面应显示标题（不应进入 ErrorBoundary）
  const title = page.getByText('变更日志中心').first();
  await expect(title).toBeVisible({ timeout: 10_000 });
});

// 主数据总览页可访问（该页面用 Card 展示而非 Table）
test('E2E-DRIFT-04 主数据总览页可访问', async ({ page }) => {
  if (!backendUp) {
    test.skip(true, '后端未启动，跳过 E2E-DRIFT-04');
  }

  await loginAsAdmin(page);
  await page.goto('/master-data');
  await page.waitForLoadState('networkidle');

  // 主数据页面用 Title 展示「主数据维护」，使用 Card 而不是 ant-table
  const title = page.getByText('主数据维护').first();
  await expect(title).toBeVisible({ timeout: 10_000 });
});
