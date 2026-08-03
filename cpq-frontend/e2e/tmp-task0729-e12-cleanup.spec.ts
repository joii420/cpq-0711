/**
 * task-0729 E12 清理回归验证：定价策略页正常打开 + 无「全局」入口 + 正常选客户 +
 * 元素价格策略 Tab 不受影响。真实后端，非 mock。用完即删。
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const SHOT_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

test('E12 清理回归：无全局入口 + 选客户正常 + 元素价格策略 Tab 不受影响', async ({ page }) => {
  test.skip(!backendUp, 'backend down');
  const errs: string[] = [];
  page.on('pageerror', (e) => errs.push('PAGEERROR: ' + e.message));

  await loginAsAdmin(page);
  await page.goto('/pricing');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1000);

  // 🔒 「全局（核价成本口径）」固定项必须已消失
  await expect(page.locator('text=全局（核价成本口径）')).toHaveCount(0);
  await expect(page.locator('text=_GLOBAL_')).toHaveCount(0);
  await page.screenshot({ path: path.join(SHOT_DIR, 't0729-e12-01-list.png'), fullPage: true }).catch(() => {});

  // 选一个真实客户
  await page.locator('.ant-list-item').first().click();
  await page.waitForTimeout(1200);

  // 两个 Tab 都在，默认落在「元素价格策略」
  await expect(page.locator('.ant-tabs-tab', { hasText: '元素价格策略' })).toHaveCount(1);
  await expect(page.locator('.ant-tabs-tab', { hasText: '价格调整策略' })).toHaveCount(1);
  // 元素价格策略内容正常渲染（客户级默认策略卡片是其核心内容；antd Card 标题可能在多层
  // DOM 节点重复命中同一段文本，用 .first() 只验证"确实出现"而非精确计数）
  await expect(page.locator('text=客户级默认策略').first()).toBeVisible();
  await page.screenshot({ path: path.join(SHOT_DIR, 't0729-e12-02-element-tab.png'), fullPage: true }).catch(() => {});

  // 切到价格调整策略 Tab 同样正常
  await page.locator('.ant-tabs-tab', { hasText: '价格调整策略' }).click();
  await page.waitForTimeout(1200);
  await expect(page.locator('text=调价策略')).toHaveCount(1);
  await page.screenshot({ path: path.join(SHOT_DIR, 't0729-e12-03-priceadjust-tab.png'), fullPage: true }).catch(() => {});

  console.log('[T0729][E12] pageerror 数量=', errs.length, errs.slice(0, 5));
  expect(errs.length, 'no pageerror expected: ' + errs.join(' | ')).toBe(0);
});
