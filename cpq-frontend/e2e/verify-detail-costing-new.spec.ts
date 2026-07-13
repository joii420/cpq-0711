/**
 * 验证(task-0712 展示修复): 导入建**新单** → 打开**只读详情页** → 核价单视图应正常渲染核价树，
 * 不再「无组件数据」。证明服务端整单物化(建行+snapshotQuotation+ensureStructure+ensureCardValues)
 * 让详情页(readonly, 不触发 warm)开箱即用。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const __filename = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filename);
const SHOT_DIR = path.join(__dirnameLocal, 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });
const XLSX = path.resolve(__dirnameLocal, '..', '..',
  'docs/table/报价测试数据/报价系统功能基础数据功能结构所需字段V3-罗克韦尔.xlsx');
const CUSTOMER = '罗克韦尔';

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

/** 在核价单只读视图内统计: 无组件数据占位数 / 核价树行数 / 加载中数 */
async function inspectCosting(page: Page, tag: string) {
  const costing = page.locator('.ant-segmented-item', { hasText: '核价单' }).first();
  await costing.waitFor({ timeout: 15000 });
  await costing.click().catch(() => {});
  await page.waitForTimeout(1500);
  const cardSeg = page.locator('.ant-segmented-item', { hasText: '产品卡片' }).first();
  if (await cardSeg.count() > 0) { await cardSeg.click().catch(() => {}); await page.waitForTimeout(800); }
  await page.waitForTimeout(1500);

  const noData = await page.locator('.qt-no-component-data').count();
  const innerTabs = page.locator('.qt-tab-btn');
  const tabN = await innerTabs.count();
  let totalRows = 0, totalLoading = 0;
  for (let i = 0; i < tabN; i++) {
    const name = (await innerTabs.nth(i).innerText().catch(() => '')).trim();
    await innerTabs.nth(i).click().catch(() => {});
    await page.waitForTimeout(900);
    const lc = await page.locator('text=加载中').count();
    const rows = await page.locator('.qt-cost-table tbody tr').count();
    console.log(`[${tag}] tab '${name}': 行数=${rows}, 加载中=${lc}`);
    totalRows += rows; totalLoading += lc;
  }
  console.log(`[${tag}] 无组件数据占位=${noData}, 内部tab数=${tabN}, 总行数=${totalRows}, 总加载中=${totalLoading}`);
  return { noData, tabN, totalRows, totalLoading };
}

test('新单详情页(只读)核价单开箱渲染核价树, 无「无组件数据」', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  expect(fs.existsSync(XLSX)).toBe(true);
  test.setTimeout(180000);
  const errors: string[] = [];
  page.on('pageerror', e => errors.push('PAGE-ERROR: ' + e.message));

  await loginAsAdmin(page);

  // 1) 导入建新单
  await page.goto('/quotations');
  await page.waitForLoadState('networkidle');
  await page.locator('button', { hasText: '从基础数据导入' }).first().click();
  await page.waitForTimeout(800);
  await page.locator('.ant-drawer .ant-select').first().click();
  await page.waitForTimeout(300);
  await page.keyboard.type(CUSTOMER);
  await page.waitForTimeout(700);
  await page.locator('.ant-select-item-option', { hasText: CUSTOMER }).first().click();
  await page.locator('.ant-drawer input[type=file]').setInputFiles(XLSX);
  await page.waitForTimeout(600);
  await page.locator('button', { hasText: '开始导入' }).first().click();
  await page.locator('.ant-alert', { hasText: /导入完成|成功/ }).first().waitFor({ timeout: 120000 }).catch(() => {});
  await page.waitForTimeout(1000);
  await page.locator('button', { hasText: '下一步' }).first().click();
  await page.waitForTimeout(2500);
  const createBtn = page.locator('button', { hasText: '创建报价单' }).first();
  await createBtn.waitFor({ timeout: 15000 });
  await createBtn.click();
  await page.waitForURL(/\/quotations\/[0-9a-f-]+\/edit/, { timeout: 30000 });
  const qid = page.url().match(/quotations\/([0-9a-f-]+)\/edit/)?.[1];
  console.log('新建报价单 id =', qid);
  expect(qid).toBeTruthy();

  // 2) 打开只读详情页(不是 /edit) —— 详情页不触发 warm, 纯读持久化快照
  await page.goto(`/quotations/${qid}`);
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(2500);
  await page.screenshot({ path: path.join(SHOT_DIR, 'verify-detail-01-costing.png'), fullPage: true }).catch(() => {});

  const r = await inspectCosting(page, '详情页-只读');
  console.log('PAGE ERRORS:', JSON.stringify(errors));

  // 断言: 详情页核价单不应是「无组件数据」, 应渲染出核价 tab + 核价树行
  expect(r.tabN, '详情页核价单应有内部页签(配件/元素/核价小计)').toBeGreaterThan(0);
  expect(r.noData, '详情页核价单不应出现「无组件数据」占位').toBe(0);
  expect(r.totalLoading, '详情页核价树不应「加载中」').toBe(0);
  expect(r.totalRows, '详情页核价树应至少渲染出行(根节点)').toBeGreaterThan(0);
});
