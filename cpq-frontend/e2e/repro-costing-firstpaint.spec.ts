/**
 * 复现(多产品首屏): 直接加载已存在的多产品报价单编辑页, 核价树首屏是否"加载中"
 * QID 由环境变量 REPRO_QID 传入。卡片值须在跑前清空以模拟"刚导入未 warm"。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const __dirnameLocal = path.dirname(fileURLToPath(import.meta.url));
const SHOT_DIR = path.join(__dirnameLocal, 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });
const QID = process.env.REPRO_QID || '';

let shotIdx = 0;
async function shot(page: Page, name: string) {
  await page.screenshot({ path: path.join(SHOT_DIR, `repro-fp-${String(++shotIdx).padStart(2, '0')}-${name}.png`), fullPage: true }).catch(() => {});
}

async function toCosting(page: Page): Promise<void> {
  for (let s = 0; s < 3; s++) {
    if (await page.locator('.ant-segmented-item', { hasText: '核价单' }).count() > 0) break;
    const n = page.locator('button', { hasText: /下一步|继续/ }).first();
    if (await n.count() > 0 && await n.isEnabled().catch(() => false)) { await n.click().catch(() => {}); await page.waitForTimeout(1500); } else break;
  }
  const costing = page.locator('.ant-segmented-item', { hasText: '核价单' }).first();
  if (await costing.count() > 0) { await costing.click().catch(() => {}); await page.waitForTimeout(1200); }
  const cardSeg = page.locator('.ant-segmented-item', { hasText: '产品卡片' }).first();
  if (await cardSeg.count() > 0) { await cardSeg.click().catch(() => {}); await page.waitForTimeout(800); }
}

async function scanTabs(page: Page, tag: string): Promise<number> {
  let total = 0;
  const tabs = page.locator('.qt-tab-btn');
  const n = await tabs.count();
  for (let i = 0; i < n; i++) {
    const name = (await tabs.nth(i).innerText().catch(() => '')).trim();
    await tabs.nth(i).click().catch(() => {});
    await page.waitForTimeout(900);
    const lc = await page.locator('text=加载中').count();
    const rows = await page.locator('.qt-cost-table tbody tr').count();
    console.log(`[${tag}] tab '${name}': 加载中=${lc}, 行数=${rows}`);
    total += lc;
  }
  return total;
}

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

test('多产品首屏核价树加载中', async ({ page }) => {
  test.skip(!backendUp || !QID, '缺后端或 REPRO_QID');
  test.setTimeout(120000);
  await loginAsAdmin(page);

  // 产品卡片视图: 报价单 + 核价单, 遍历每个产品
  await page.goto(`/quotations/${QID}/edit`);
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(2000);
  await toCosting(page);
  await shot(page, 'costing-first');

  // 遍历每个产品卡片 (产品行切换)
  const productTabs = page.locator('.qt-product-tab, .ant-tabs-tab');
  const pN = await productTabs.count();
  console.log(`产品卡片/tab 数(粗) = ${pN}`);
  let firstPaint = await scanTabs(page, '首屏P1');
  // 切到第2个产品(若有 .qt-product-tab)
  const prodTabs = page.locator('.qt-product-tab');
  if (await prodTabs.count() > 1) {
    await prodTabs.nth(1).click().catch(() => {});
    await page.waitForTimeout(1200);
    firstPaint += await scanTabs(page, '首屏P2');
    await shot(page, 'costing-p2');
  }
  console.log(`>>> 多产品首屏(不手刷) 加载中总数 = ${firstPaint}`);
});
