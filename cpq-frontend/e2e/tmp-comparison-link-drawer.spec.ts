/**
 * 补 1 端到端复验：真实点开「配置比对列」→ 连线抽屉打开 → 左右两侧页签树有内容。
 * （后端 meta 端点 6601c40d 已补交；后端只验到"不会抛错"，这里把"看见"补上。）
 *
 * 两项：
 *   ⒜ CUST-0001 / 系列「施耐德BUG2」(83b2f4cd) → 抽屉打开，左 9 组、右 18 组
 *   ⒝ 空态：costingTabs=[] + costingSource=NONE → 右侧显示「暂无核价侧页签」而非崩溃/空白
 *      🔒 当前库**跑不出真实空态样本**（5 个系列全是 costingTabs=18 / SERIES_LATEST），
 *         故用网络层改写把该系列的 costingTabs 置空 —— 零写库，被测的是 UI 空态分支本身。
 *
 * 🔒 全程只开不存：绝不点连线抽屉的确定/保存（CUST-0001 是业务方的真实客户，
 *    写入会改它的比对列配置）。关闭一律走「取消」/Esc。
 *
 * 跑法：PW_BASE_URL=http://localhost:5179 npx playwright test --config=e2e/playwright.config.ts \
 *        e2e/tmp-comparison-link-drawer.spec.ts --reporter=list
 * 用完即删。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const SHOT_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

const SERIES_NAME = '施耐德BUG2';
const META_API = /\/api\/cpq\/price-adjust\/template-series\/[^/]+\/comparison-view-meta/;

let backendUp = false;
test.beforeAll(async () => {
  for (let i = 0; i < 6 && !backendUp; i++) {
    backendUp = await isBackendUp();
    if (!backendUp) await new Promise((r) => setTimeout(r, 5000));
  }
  console.log(`[LK] backendUp=${backendUp}`);
});

/** 进入 CUST-0001 的价格调整策略 Tab，并在比对列配置区选中指定模板系列 */
async function openPanelAndSelectSeries(page: Page) {
  await page.goto('/pricing');
  await page.waitForLoadState('networkidle');
  // 页面首绘可能撞上后端重启窗口，显式等搜索框出现再操作（否则 fill 直接超时）
  await page.locator('input[placeholder="搜索客户"]').first()
    .waitFor({ state: 'visible', timeout: 30_000 });
  await page.waitForTimeout(800);
  await page.locator('input[placeholder="搜索客户"]').first().fill('罗克韦尔');
  await page.locator('input[placeholder="搜索客户"]').first().press('Enter');
  await page.waitForTimeout(1800);
  await page.locator('.ant-list-item').filter({ hasText: '罗克韦尔' }).first().click();
  await page.waitForTimeout(1500);
  await page.locator('.ant-tabs-tab').filter({ hasText: '价格调整策略' }).first().click();
  await page.waitForTimeout(2500);

  // 比对列配置区的模板系列下拉
  const seriesSelect = page.locator('.ant-select').filter({ hasText: /模板系列|施耐德|罗克韦尔/ }).first();
  await seriesSelect.scrollIntoViewIfNeeded();
  await seriesSelect.click();
  await page.waitForTimeout(800);
  await page.locator('.ant-select-item-option').filter({ hasText: SERIES_NAME }).first().click();
  await page.waitForTimeout(1500);
}

async function openLinkDrawer(page: Page) {
  // ⚠️ 入口不是 <button> 而是 <a onClick={openDrawer}>⚙ 配置比对列</a>（ComparisonColumnPanel:198），
  //    按 button 找会一直等到超时。
  const entry = page.locator('a:visible').filter({ hasText: '配置比对列' }).first();
  await entry.scrollIntoViewIfNeeded();
  await entry.click();
  await page.waitForTimeout(2500);
}

test('⒜ 真实点开「配置比对列」→ 抽屉打开且左右页签树有内容', async ({ page }) => {
  test.skip(!backendUp, 'backend down');
  await loginAsAdmin(page);
  await openPanelAndSelectSeries(page);
  await openLinkDrawer(page);

  // ⚠️ DOM 里同时挂着 3 个 .ant-drawer-content（其它组件的已关闭抽屉也在），
  //    .first() 会选到隐藏的那个 → 必须限定 .ant-drawer-open 下面那个
  // 判据用**内容**而非 class：DOM 里挂着多个 .ant-drawer-content，且 antd 的 open 类名位置
  // 不稳（实测 .ant-drawer-open 选不中）。LinkConfigDrawer 底部这句固定提示只在抽屉内渲染，
  // 它出现 = 抽屉真的打开并渲染完毕。
  const drawerHint = page.locator('text=点击左侧报价节点');
  const drawerVisible = (await drawerHint.count()) > 0;
  const quoteEmpty = await page.locator('text=暂无报价侧页签').count();
  const costingEmpty = await page.locator('text=暂无核价侧页签').count();
  const metaErr = await page.locator('text=无法打开连线配置').count();
  // 左右两栏的分组数（renderGroup 每个页签一组）
  const bodyText = await page.locator('.ant-drawer-body').first().innerText().catch(() => '');
  console.log(`[LK][⒜] 抽屉可见=${drawerVisible} 报价侧空提示=${quoteEmpty} 核价侧空提示=${costingEmpty} metaError提示=${metaErr}`);
  console.log(`[LK][⒜] 抽屉正文前 160 字：${bodyText.slice(0, 160).replace(/\n/g, ' | ')}`);
  await page.screenshot({ path: path.join(SHOT_DIR, 'lk-01-drawer-open.png'), fullPage: true }).catch(() => {});

  expect(metaErr, '🔒 不应再出现 meta 加载失败提示（端点已补交）').toBe(0);
  expect(drawerVisible, '🔒 连线抽屉应真的打开').toBe(true);
  expect(quoteEmpty, '报价侧应有页签（预期 9 组）').toBe(0);
  expect(costingEmpty, '核价侧应有页签（预期 18 组）').toBe(0);

  // 只开不存：用 Esc 关闭，绝不点确定
  await page.keyboard.press('Escape');
  await page.waitForTimeout(800);
});

test('⒝ 空态：costingTabs=[] → 右侧显示「暂无核价侧页签」，不崩溃', async ({ page }) => {
  test.skip(!backendUp, 'backend down');
  await loginAsAdmin(page);

  // 零写库伪造空态（当前库无 costingSource=NONE 的真实样本）
  await page.route(META_API, async (route) => {
    const response = await route.fetch();
    const json = await response.json().catch(() => null);
    const body = json?.data ?? json;
    if (body) { body.costingTabs = []; body.costingSource = 'NONE'; }
    await route.fulfill({ response, json });
  });

  await openPanelAndSelectSeries(page);
  await openLinkDrawer(page);

  const drawerVisible = (await page.locator('text=点击左侧报价节点').count()) > 0;
  const costingEmpty = await page.locator('text=暂无核价侧页签').count();
  const quoteEmpty = await page.locator('text=暂无报价侧页签').count();
  const crashed = await page.locator('.ant-result-error, vite-error-overlay').count();
  console.log(`[LK][⒝] 抽屉可见=${drawerVisible} 核价侧空提示=${costingEmpty} 报价侧空提示=${quoteEmpty} 崩溃标志=${crashed}`);
  await page.screenshot({ path: path.join(SHOT_DIR, 'lk-02-empty-costing.png'), fullPage: true }).catch(() => {});

  expect(crashed, '不应崩溃').toBe(0);
  expect(drawerVisible, '抽屉仍应正常打开').toBe(true);
  expect(costingEmpty, '🔒 核价侧应显示「暂无核价侧页签」').toBeGreaterThan(0);
  expect(quoteEmpty, '报价侧不受影响，仍有页签').toBe(0);

  await page.keyboard.press('Escape');
  await page.waitForTimeout(800);
});
