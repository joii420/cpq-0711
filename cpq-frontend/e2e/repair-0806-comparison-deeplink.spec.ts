/**
 * repair-0806：价格调整审核「直达比对视图」链接 404。
 *
 * 症状：/pricing/reviews → 料号审核抽屉 → 点「直达比对视图」→
 *       路由 /quotations/{id}/comparison 报 react-router 默认 404 错误页。
 * 根因：后端 PriceAdjustReviewService 按 api.md 契约下发 /quotations/{id}/comparison，
 *       但 task-0717 的比对视图是 ProductDetailViews 内的 Segmented 子视图，
 *       前端 router 从未注册该路径。
 *
 * 本 spec 直接打深链，断言：不是路由 404 + 比对视图子页签被预选中。
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const SHOT_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

// 开发库 cpq_db_0724 现存单：QT-20260806-0083（DRAFT，1 个 SIMPLE 产品行）
const QUOTATION_ID = process.env.PW_QUOTATION_ID || 'b8371fb7-7b94-4a67-b2d1-32bee69cb9c0';

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

test('比对视图深链 /quotations/:id/comparison 可直达且预选中比对视图', async ({ page }) => {
  test.skip(!backendUp, 'backend down');

  await loginAsAdmin(page);
  await page.goto(`/quotations/${QUOTATION_ID}/comparison`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);

  // ① 不是 react-router 默认错误页
  await expect(page.locator('text=Unexpected Application Error')).toHaveCount(0);
  await expect(page.locator('text=404 Not Found')).toHaveCount(0);

  // ② 落在报价单详情页（产品明细卡片存在）
  await expect(page.locator('.ant-card-head-title', { hasText: '产品明细' })).toHaveCount(1);

  // ③ 一级视图 Segmented 预选中「比对视图」
  const selected = page.locator('.ant-segmented-item-selected', { hasText: '比对视图' });
  await expect(selected).toHaveCount(1);

  // ④ ComparisonBoard 真的挂上了（工具栏过滤框）且没有加载错误 Alert
  const card = page.locator('.ant-card', { has: page.locator('.ant-card-head-title', { hasText: '产品明细' }) });
  await expect(card.locator('input[placeholder="输入销售料号过滤"]')).toHaveCount(1);
  await expect(card.locator('.ant-alert-error')).toHaveCount(0);

  await card.scrollIntoViewIfNeeded();
  await card.screenshot({ path: path.join(SHOT_DIR, 'r0806-comparison-deeplink.png') }).catch(() => {});

  // ⑤ 回归：不带 /comparison 的普通详情深链仍默认「报价单」子视图
  await page.goto(`/quotations/${QUOTATION_ID}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1200);
  await expect(page.locator('.ant-segmented-item-selected', { hasText: '报价单' })).toHaveCount(1);
  await expect(page.locator('.ant-segmented-item-selected', { hasText: '比对视图' })).toHaveCount(0);
});
