/**
 * 复现: 报价单「从基础数据导入」→ 建单 → 进核价单页签, 核价树永久"加载中…"(需手刷才好)
 *
 * 目的: 用罗克韦尔.xlsx 实跑真实导入路径, 抓 autoPopulate 与 warm 回灌竞态导致的
 *       核价树页签"加载中…"现场(不手动刷新), 并对照手刷后是否恢复。
 *
 * 证据: e2e/screenshots/repro-costing-*.png
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

let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `repro-costing-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}

/** 进核价单卡片视图, 遍历内部 tab 统计"加载中"总数 */
async function countCostingLoading(page: Page, tag: string): Promise<number> {
  // 编辑页首屏停在 Step1(客户信息), 报价单/核价单 Segmented 在 Step2 → 先推进到 Step2
  for (let step = 0; step < 3; step++) {
    if (await page.locator('.ant-segmented-item', { hasText: '核价单' }).count() > 0) break;
    const nextBtn = page.locator('button', { hasText: /下一步|继续|下—步/ }).first();
    if (await nextBtn.count() > 0 && await nextBtn.isEnabled().catch(() => false)) {
      await nextBtn.click().catch(() => {});
      await page.waitForTimeout(1500);
    } else break;
  }
  const costing = page.locator('.ant-segmented-item', { hasText: '核价单' }).first();
  if (await costing.count() === 0) { console.log(`[${tag}] 未找到核价单 Segmented`); return -1; }
  await costing.click().catch(() => {});
  await page.waitForTimeout(1200);
  const cardSeg = page.locator('.ant-segmented-item', { hasText: '产品卡片' }).first();
  if (await cardSeg.count() > 0) { await cardSeg.click().catch(() => {}); await page.waitForTimeout(800); }
  await page.waitForTimeout(1500);

  let total = 0;
  const innerTabs = page.locator('.qt-tab-btn');
  const tabN = await innerTabs.count();
  console.log(`[${tag}] 核价内部 tab 数 = ${tabN}`);
  for (let i = 0; i < tabN; i++) {
    const name = (await innerTabs.nth(i).innerText().catch(() => '')).trim();
    await innerTabs.nth(i).click().catch(() => {});
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

test('复现: 导入建单后进核价单, 核价树是否"加载中"(不手刷) + 手刷后恢复', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  expect(fs.existsSync(XLSX), `测试文件应存在: ${XLSX}`).toBe(true);
  test.setTimeout(180000);

  const errors: string[] = [];
  page.on('pageerror', e => errors.push('PAGE-ERROR: ' + e.message));

  await loginAsAdmin(page);
  expect(page.url()).not.toContain('/login');

  // 1) 报价单列表 → 从基础数据导入
  await page.goto('/quotations');
  await page.waitForLoadState('networkidle');
  await page.locator('button', { hasText: '从基础数据导入' }).first().click();
  await page.waitForTimeout(800);
  await shot(page, 'drawer-step1');

  // 2) 选客户 + 上传 xlsx
  //   客户 select 是 showSearch(optionFilterProp=label)：客户多时选项虚拟化，
  //   必须先输入搜索词把「罗克韦尔」过滤进视口再点，否则点开即找选项会超时。
  await page.locator('.ant-drawer .ant-select').first().click();
  await page.waitForTimeout(300);
  await page.keyboard.type(CUSTOMER);
  await page.waitForTimeout(700);
  await page.locator('.ant-select-item-option', { hasText: CUSTOMER }).first().click();
  await page.locator('.ant-drawer input[type=file]').setInputFiles(XLSX);
  await page.waitForTimeout(600);
  await page.locator('button', { hasText: '开始导入' }).first().click();

  // 3) 等导入完成
  await page.locator('.ant-alert', { hasText: /导入完成|成功/ }).first()
    .waitFor({ timeout: 120000 }).catch(() => {});
  await page.waitForTimeout(1000);
  await shot(page, 'import-done');

  // 4) 下一步 → 选模板(自动带出) → 创建报价单
  await page.locator('button', { hasText: '下一步' }).first().click();
  await page.waitForTimeout(2500);
  await shot(page, 'step2-templates');
  const createBtn = page.locator('button', { hasText: '创建报价单' }).first();
  await createBtn.waitFor({ timeout: 15000 });
  await createBtn.click();

  // 5) 等跳转到编辑页(autoPopulate=1)
  await page.waitForURL(/\/quotations\/[0-9a-f-]+\/edit/, { timeout: 30000 });
  const qid = page.url().match(/quotations\/([0-9a-f-]+)\/edit/)?.[1];
  console.log('创建的报价单 id =', qid, 'url=', page.url());
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(2000);

  // 6) 立即进核价单(不手刷) 统计加载中
  const loadingBefore = await countCostingLoading(page, '首屏-不手刷');
  await shot(page, 'costing-before-refresh');
  console.log(`>>> 首屏(不手刷) 核价"加载中"总数 = ${loadingBefore}`);

  // 7) 手动刷新整页 → 再统计
  await page.reload();
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(3000);
  const loadingAfter = await countCostingLoading(page, '手刷后');
  await shot(page, 'costing-after-refresh');
  console.log(`>>> 手刷后 核价"加载中"总数 = ${loadingAfter}`);

  console.log('PAGE ERRORS:', JSON.stringify(errors, null, 2));
  console.log(`\n===== 复现结论 =====\n首屏不手刷 加载中=${loadingBefore}\n手刷后 加载中=${loadingAfter}\nqid=${qid}\n`);
});
