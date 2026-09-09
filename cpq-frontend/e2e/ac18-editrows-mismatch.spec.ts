/**
 * AC-18 · 行键变化后既有 editRows 的失配必须「响亮失败」（repair-260908 · D-14）
 *
 * 做的事：真实用户动作 —— 打开 QT-20260908-0624 编辑页 → 进 Step2 → 改一个格子 → 保存草稿。
 * 保存草稿会把 quote_card_values 置 NULL 并从 row_data 重算，重算路径会调
 * CardSnapshotService#filterEditRowsToNewBaseRows —— 那正是失配发生的那一刻。
 *
 * 🚫 本 spec 不做断言判定 AC-18 成败：失配计数由后端 LOG.warn 落在 8098 日志里，
 *    由主线在日志侧取数。这里只负责「真实触发一次」并留截图。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';

const __filename = fileURLToPath(import.meta.url);
const SHOT_DIR = path.join(path.dirname(__filename), 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

const QUOTATION_ID = '4ce0fcc4-a73b-4672-ba45-6387e03491ad'; // QT-20260908-0624
let idx = 0;
async function shot(page: Page, name: string) {
  const f = path.join(SHOT_DIR, `ac18-${String(++idx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: f, fullPage: true }).catch(() => {});
  console.log(`[shot] ${name} -> ${f}`);
}

let up = false;
test.beforeAll(async () => { up = await isBackendUp(); });

test('AC-18 触发：编辑一个格子并保存草稿', async ({ page }) => {
  test.setTimeout(300000);
  test.skip(!up, '后端未启动');

  const errs: string[] = [];
  page.on('console', m => { if (m.type() === 'error') errs.push(m.text()); });
  page.on('pageerror', e => errs.push('PAGE-ERROR: ' + e.message));

  await loginAsAdmin(page);
  await page.goto(`/quotations/${QUOTATION_ID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);
  await shot(page, 'step1');

  // 走到 Step2（编辑页是 5 步向导）
  for (let i = 0; i < 3; i++) {
    const next = page.getByRole('button', { name: /下\s*一\s*步/ });
    if (await next.count() === 0) break;
    if (!(await next.first().isEnabled().catch(() => false))) break;
    await next.first().click();
    await page.waitForTimeout(2500);
    const hasCard = await page.locator('.ant-tabs').count();
    if (hasCard > 0) break;
  }
  await page.waitForTimeout(3000);
  await shot(page, 'step2');

  // 「产品」页签行数 —— AC-1 的 UI 层证据
  const productTab = page.getByRole('tab', { name: /产品/ }).first();
  if (await productTab.count() > 0) {
    await productTab.click().catch(() => {});
    await page.waitForTimeout(2000);
    await shot(page, 'product-tab');
    const rows = await page.locator('.ant-tabs-tabpane-active .ant-table-tbody tr.ant-table-row').count();
    console.log(`[AC-1·UI] 「产品」页签可见数据行 = ${rows}`);
  }

  // 改一个可输入格子
  const input = page.locator('.ant-tabs-tabpane-active input:not([disabled])').first();
  if (await input.count() > 0) {
    await input.click();
    await input.fill('7');
    await input.blur();
    await page.waitForTimeout(1500);
    console.log('[AC-18] 已修改一个输入格');
  } else {
    console.log('[AC-18] ⚠️ 未找到可输入格，跳过编辑，仅保存');
  }
  await shot(page, 'after-edit');

  // 保存草稿（antd 两字按钮会被渲染成「保 存」，用正则容忍空格）
  const save = page.getByRole('button', { name: /保\s*存\s*草\s*稿|保\s*存/ }).first();
  await expect(save).toBeVisible({ timeout: 15000 });
  await save.click();
  await page.waitForTimeout(8000);
  await shot(page, 'after-save');

  console.log(`[console errors] ${errs.length}`);
  errs.slice(0, 5).forEach(e => console.log('  ' + e));
});
