/**
 * MasterDataTableViewerPage E2E — 2026-05-20
 * 验证: 登录 → 进入主表查看页 → 下拉切换 4 表 → 各表渲染表头 + 系统字段开关
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

let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `mdv-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

async function switchTable(page: Page, optionText: string) {
  // 第一个 ant-select 就是表选择器
  await page.locator('.ant-select').first().click();
  await page.waitForTimeout(300);
  await page.locator('.ant-select-item-option').filter({ hasText: optionText }).first().click();
  await page.waitForTimeout(1800);
}

test('主表查看页 — 4 表切换 + 系统字段开关', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  await loginAsAdmin(page);
  await page.goto('/master-data/viewer');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  await shot(page, 'init-mat-part');

  await expect(page.locator('text=主数据表查看').first()).toBeVisible();

  const initCols = await page.locator('.ant-table-thead th').count();
  console.log(`mat_part 默认列数: ${initCols}`);
  expect(initCols).toBeGreaterThan(3);  // 必须有业务字段, 不能是 1

  // 切到 mat_bom
  await switchTable(page, 'mat_bom');
  await shot(page, 'mat-bom');
  expect(await page.locator('.ant-table-thead th').count()).toBeGreaterThan(3);

  // 切到 mat_process
  await switchTable(page, 'mat_process');
  await shot(page, 'mat-process');
  expect(await page.locator('.ant-table-thead th').count()).toBeGreaterThan(3);

  // 切到 mat_composite_process
  await switchTable(page, 'mat_composite_process');
  await shot(page, 'mat-composite-process');
  expect(await page.locator('.ant-table-thead th').count()).toBeGreaterThan(3);

  // 切回 mat_part 测系统字段开关
  await switchTable(page, 'mat_part');
  const colsHidden = await page.locator('.ant-table-thead th').count();
  console.log(`mat_part 隐藏系统字段时列数: ${colsHidden}`);

  // 打开系统字段开关
  await page.locator('button[role="switch"]').first().click();
  await page.waitForTimeout(800);
  const colsShown = await page.locator('.ant-table-thead th').count();
  console.log(`mat_part 显示系统字段时列数: ${colsShown}`);
  expect(colsShown).toBeGreaterThan(colsHidden);  // 显示系统字段应该列更多
  await shot(page, 'mat-part-all-fields');

  await shot(page, 'final');
});
