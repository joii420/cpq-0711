/**
 * E2E: 核价 BOM 递归展开 组件级开关（bom_recursive_expand）—— 混合组件渲染。
 *
 * 验证同一核价单/模板里：
 *   - 勾选(true)组件「材质」→ BOM 树展开 + 3 系统固定列（料号/父料号/版本）。
 *   - 未勾选(false)组件「工序」→ 普通表（无系统列三连），按根料号单料号取数。
 *   - 加载中 = 0。
 *
 * 夹具（同 costing-bom-tree.spec.ts，DRAFT，多层 BOM 根 3120018220）：
 *   - 报价单 QT-20260604-1577, id = 14c72d60-46c3-4eed-bdef-115ab98050b0
 *   - 核价模板 driver 组件：材质 1f2914e5(保持 true) / 工序 54805dfa(测试设 false)
 *
 * 数据纪律：beforeAll 把「工序」组件 bom_recursive_expand 临时设 false，afterAll 还原 true（组件级全局，
 *   测试短暂；绝不删业务数据）。
 */
import { test, expect, Page } from '@playwright/test';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';
import { execSync } from 'child_process';

const QUOTATION_ID = '14c72d60-46c3-4eed-bdef-115ab98050b0';
const BACKEND_URL = 'http://localhost:8081';
const COMP_PLAIN = '54805dfa-ebcc-4de6-94f2-a8ef1cb7cf80'; // 工序：测试设 false
const TAB_RECURSIVE = '材质';   // 保持 true → 树 + 系统列
const TAB_PLAIN = '工序';       // false → 普通表无系统列

function psql(sql: string): string {
  try {
    return execSync('PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db',
      { input: sql, encoding: 'utf-8', stdio: ['pipe', 'pipe', 'pipe'] });
  } catch (e: any) { return (e.stdout ?? e.stderr ?? String(e)); }
}

async function enterCostingCard(page: Page): Promise<boolean> {
  await page.goto(`/quotations/${QUOTATION_ID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  const nextBtn = page.locator('button', { hasText: /下一步|继续/ }).first();
  if (await nextBtn.count() > 0 && await nextBtn.isEnabled().catch(() => false)) {
    await nextBtn.click().catch(() => {});
    await page.waitForTimeout(1200);
  }
  const costing = page.locator('.ant-segmented-item', { hasText: '核价单' }).first();
  if (await costing.count() === 0) return false;
  await costing.click().catch(() => {});
  await page.waitForTimeout(1000);
  const cardSeg = page.locator('.ant-segmented-item', { hasText: '产品卡片' }).first();
  if (await cardSeg.count() > 0) { await cardSeg.click().catch(() => {}); await page.waitForTimeout(800); }
  await page.waitForTimeout(1500);
  return true;
}

async function clickInnerTab(page: Page, name: string) {
  const tab = page.locator('.qt-tab-btn', { hasText: name }).first();
  await tab.click().catch(() => {});
  await page.waitForTimeout(1000);
}

async function tableHeaders(page: Page): Promise<string[]> {
  const ths = page.locator('.qt-cost-table thead th');
  const n = await ths.count();
  const out: string[] = [];
  for (let i = 0; i < n; i++) out.push((await ths.nth(i).innerText().catch(() => '')).trim());
  return out;
}

function isBomTriple(h: string[]): boolean {
  return h[0] === '料号' && h[1] === '父料号' && h[2] === '版本';
}

let backendUp = false;

test.beforeAll(async () => {
  backendUp = await isBackendUp();
  if (!backendUp) return;
  psql(`UPDATE component SET bom_recursive_expand=false WHERE id='${COMP_PLAIN}';`);
});

test.afterAll(async () => {
  if (!backendUp) return;
  psql(`UPDATE component SET bom_recursive_expand=true WHERE id='${COMP_PLAIN}';`);
});

test('核价混合: 勾选(材质)出树系统列 + 未勾选(工序)普通表无系统列 + 加载中=0', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  await loginAsAdmin(page);
  expect(page.url()).not.toContain('/login');

  // 按新配置(工序=false)重算核价快照
  const refresh = await page.request.post(
    `${BACKEND_URL}/api/cpq/configure-product/quotations/${QUOTATION_ID}/refresh-snapshot`);
  expect(refresh.status(), 'refresh-snapshot 应 200').toBe(200);

  const entered = await enterCostingCard(page);
  expect(entered, '应能进入核价单卡片视图').toBe(true);

  // 勾选组件「材质」→ 系统列三连(料号/父料号/版本)
  await clickInnerTab(page, TAB_RECURSIVE);
  const hRec = await tableHeaders(page);
  console.log('[CBToggle] 材质(true)表头:', JSON.stringify(hRec));
  expect(isBomTriple(hRec), '勾选组件(材质)应出 料号/父料号/版本 系统固定列三连').toBe(true);

  // 未勾选组件「工序」→ 无系统列三连(普通表)
  await clickInnerTab(page, TAB_PLAIN);
  const hPlain = await tableHeaders(page);
  console.log('[CBToggle] 工序(false)表头:', JSON.stringify(hPlain));
  expect(isBomTriple(hPlain), '未勾选组件(工序)不应出系统固定列三连(普通渲染)').toBe(false);
  // 工序普通表不应有 disabled 版本下拉
  const verSelect = page.locator('.qt-cost-table tbody select[disabled]');
  expect(await verSelect.count(), '未勾选组件不应有版本下拉占位').toBe(0);

  // 加载中 = 0（逐 tab 切一遍）
  const innerTabs = page.locator('.qt-tab-btn');
  const tabN = await innerTabs.count();
  for (let i = 0; i < tabN; i++) {
    await innerTabs.nth(i).click().catch(() => {});
    await page.waitForTimeout(700);
    const lc = await page.locator('text=加载中').count();
    const name = (await innerTabs.nth(i).innerText().catch(() => '')).trim();
    expect(lc, `tab '${name}' 加载中应为 0`).toBe(0);
  }
  console.log('[CBToggle] ✅ 混合渲染: 材质树+系统列 / 工序普通表 / 加载中=0');
});
