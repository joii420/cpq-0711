/**
 * AP-50 Bug-R1 最终验收截图 v3
 *
 * 验收标准:
 *   Given: 已保存的报价单 9ecf8630，ComponentCell.tsx 3 处"加载中..."已加 readonly 守卫
 *   When: 打开详情页，切到「工序」Tab
 *   Then: 单价列不再显示"加载中..."，应显示"—"或具体数值
 *
 * 生成截图: ap50-detail-processes-v3.png
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';

const __filename = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filename);
const SHOT_DIR = path.join(__dirnameLocal, 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`SHOT: ${name} -> ${file}`);
}

async function countLoading(page: Page, tag: string): Promise<number> {
  const c = await page.locator('text=加载中').count();
  console.log(`[${tag}] loading=${c}`);
  return c;
}

async function clickTab(page: Page, tabName: string): Promise<boolean> {
  const tabLocator = page
    .locator('button.qt-tab-btn')
    .filter({ hasText: new RegExp(`^${tabName}$`) })
    .first();
  const visible = await tabLocator.isVisible().catch(() => false);
  if (!visible) {
    console.log(`  Tab "${tabName}": NOT VISIBLE`);
    return false;
  }
  await tabLocator.click();
  await page.waitForTimeout(2500);
  return true;
}

let backendUp = false;
test.beforeAll(async () => {
  backendUp = await isBackendUp();
});

test('AP-50 Bug-R1 最终验收: 详情页工序 Tab 单价列不再"加载中..."', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(180_000);

  const quotationId = '9ecf8630-9e53-4fac-9ce7-6e03d534b451';

  await loginAsAdmin(page);

  console.log('\n===== 详情页 /quotations/:id =====');
  await page.goto(`/quotations/${quotationId}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(4000);

  // 尝试切到「工序」Tab（先试直接名称，再试「选配-工序列表」）
  let tabFound = await clickTab(page, '工序');
  if (!tabFound) {
    tabFound = await clickTab(page, '选配-工序列表');
    console.log(`  降级到 选配-工序列表: ok=${tabFound}`);
  }

  // 等待渲染稳定
  await page.waitForTimeout(2000);

  const loadingCount = await countLoading(page, 'detail-工序');

  // 拍截图
  await shot(page, 'ap50-detail-processes-v3');

  console.log('\n===== Bug-R1 验收结论 =====');
  console.log(`详情页工序 Tab loading=${loadingCount} (期望 0)`);

  expect.soft(
    loadingCount,
    '详情页工序 Tab 不应有"加载中..."（Bug-R1 readonly 守卫修复后）'
  ).toBe(0);
});
