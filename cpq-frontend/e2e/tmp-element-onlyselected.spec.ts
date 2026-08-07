/**
 * 需求 2 验证：元素矩阵「只看已选」（ElementMatrix，照搬 MaterialRangeMatrix 的纯前端复核视图）。
 *
 * 六项：
 *   ① 勾若干（含跨页）→ 切「只看已选」→ 全部可见且数量吻合
 *   ② 不受筛选影响：在该视图下改筛选条件，内容不变
 *   ③ 不请求后端：切换「只看已选」期间 elements 接口零新增请求
 *   ④ includeDisabled=false 时，已勾选的停用元素在该视图里仍可见并标「已停用」（#51）
 *   ⑤ 在该视图里取消勾选能正常移除
 *   ⑥ tsc / transform 在 spec 外跑
 *
 * ④ 的停用元素用**网络层改写**伪造：现网 element 表 37 行全为 ACTIVE，而 element 是**全局主数据**
 * （测试 A 的 #51 正在用），不为测试往里插停用行。改写等价后端 `AND status='ACTIVE'`，零写库。
 *
 * 数据域：自建 `CUST-0805-FE`（仅一条 customer）。不碰 CUST-0001 / CUST-0729-*。
 * 建数：INSERT INTO customer (name, code) VALUES ('前端验证专用0805','CUST-0805-FE');
 * 跑法：PW_BASE_URL=http://localhost:5179 npx playwright test --config=e2e/playwright.config.ts \
 *        e2e/tmp-element-onlyselected.spec.ts --reporter=list
 * 用完即删。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const SHOT_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

const CUSTOMER_NAME = '前端验证专用0805';
const ELEMENTS_API = /\/api\/cpq\/price-adjust\/strategies\/[^/]+\/elements\?/;

let backendUp = false;
// ⚠️ 后端此刻正在被另一路并发部署（反复重启），isBackendUp 的一次性 3s 探测经常正好撞上
// 重启窗口 → 整个 spec 被 skip（skipped 不是绿，别当通过）。这里只给**本 spec** 的门禁加重试，
// 不动共享 fixture；真的没后端时 6 次都失败，仍会如常 skip。
test.beforeAll(async () => {
  for (let i = 0; i < 6 && !backendUp; i++) {
    backendUp = await isBackendUp();
    if (!backendUp) await new Promise((r) => setTimeout(r, 5000));
  }
  console.log(`[OS] backendUp=${backendUp}`);
});

const elemTable = (page: Page) => page.locator('.ant-table').filter({ hasText: '元素符号' }).first();
const elemWrapper = (page: Page) => page.locator('.ant-table-wrapper').filter({ hasText: '元素符号' }).first();
const counterSpan = (page: Page) =>
  page.locator('span').filter({ hasText: '右侧为该客户最近 10 个版本的价格' }).first();

async function readCounter(page: Page): Promise<number> {
  const m = (await counterSpan(page).innerText()).match(/已选\s*(\d+)\s*项/);
  return m ? Number(m[1]) : -1;
}
/** ⚠️ scroll={{x}} 的表格 tbody 首行是隐藏测量行 ant-table-measure-row，必须用 .ant-table-row */
const rowsOf = (page: Page) => elemTable(page).locator('tbody tr.ant-table-row');
async function checkFirstUnchecked(page: Page, n: number) {
  for (let i = 0; i < n; i++) {
    await rowsOf(page).locator('input[type="checkbox"]:not(:checked)').first().click();
    await page.waitForTimeout(350);
  }
}
async function gotoPage(page: Page, n: number) {
  await elemWrapper(page).locator(`.ant-pagination-item-${n}`).first().click();
  await page.waitForTimeout(1500);
}
/** 「只看已选」复选框（与「含已停用」区分） */
const onlySelectedBox = (page: Page) =>
  page.locator('.ant-checkbox-wrapper').filter({ hasText: '只看已选' }).first();
const includeDisabledBox = (page: Page) =>
  page.locator('.ant-checkbox-wrapper').filter({ hasText: '含已停用' }).first();

async function openTab(page: Page) {
  await page.goto('/pricing');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  await page.locator('input[placeholder="搜索客户"]').first().fill(CUSTOMER_NAME);
  await page.locator('input[placeholder="搜索客户"]').first().press('Enter');
  await page.waitForTimeout(1800);
  await page.locator('.ant-list-item').filter({ hasText: CUSTOMER_NAME }).first().click();
  await page.waitForTimeout(1500);
  await page.locator('.ant-tabs-tab').filter({ hasText: '价格调整策略' }).first().click();
  await page.waitForTimeout(2500);
  await rowsOf(page).first().waitFor({ state: 'visible', timeout: 20_000 });
}

test('①③⑤ 跨页勾选 → 只看已选全部可见 / 切换零后端请求 / 视图内可取消', async ({ page }) => {
  test.skip(!backendUp, 'backend down');
  await loginAsAdmin(page);
  await openTab(page);

  // 第1页勾 3 个 + 第2页勾 2 个（跨页）
  await checkFirstUnchecked(page, 3);
  await gotoPage(page, 2);
  await checkFirstUnchecked(page, 2);
  const selectedCount = await readCounter(page);
  console.log(`[OS][①] 跨页共勾选 计数器=${selectedCount}`);
  expect(selectedCount).toBe(5);

  // ③ 切「只看已选」期间统计 elements 接口请求数
  let apiCalls = 0;
  const onReq = (r: import('@playwright/test').Request) => { if (ELEMENTS_API.test(r.url())) apiCalls++; };
  page.on('request', onReq);
  await onlySelectedBox(page).click();
  await page.waitForTimeout(2000);
  page.off('request', onReq);
  const shownRows = await rowsOf(page).count();
  console.log(`[OS][①] 只看已选：表内行数=${shownRows}；[③] 切换期间 elements 请求数=${apiCalls}`);
  await page.screenshot({ path: path.join(SHOT_DIR, 'os-01-onlyselected.png'), fullPage: true }).catch(() => {});
  expect(shownRows, '🔒 已选 5 个应全部可见（跨页的也在）').toBe(5);
  expect(apiCalls, '🔒 纯前端视图：切换时不得请求后端').toBe(0);

  // ⑤ 在该视图里取消勾选 → 移除且行数同步减少
  await rowsOf(page).locator('input[type="checkbox"]:checked').first().click();
  await page.waitForTimeout(600);
  const afterUncheck = await readCounter(page);
  const rowsAfter = await rowsOf(page).count();
  console.log(`[OS][⑤] 视图内取消1个后 计数器=${afterUncheck} 行数=${rowsAfter}`);
  expect(afterUncheck, '🔒 视图内取消勾选必须能移除').toBe(4);
  expect(rowsAfter, '移除后该行应从视图消失').toBe(4);
});

test('② 不受筛选影响：只看已选期间改筛选条件，内容不变', async ({ page }) => {
  test.skip(!backendUp, 'backend down');
  await loginAsAdmin(page);
  await openTab(page);
  await checkFirstUnchecked(page, 3);
  await onlySelectedBox(page).click();
  await page.waitForTimeout(1500);
  const before = await rowsOf(page).count();
  const beforeCodes = (await rowsOf(page).locator('td:nth-child(2)').allInnerTexts()).join(',');
  console.log(`[OS][②] 切入只看已选：行数=${before}`);

  // 改关键字（此时「查询」被禁用，仅输入即可；再切 includeDisabled 也不应影响视图）
  await page.locator('input[placeholder="元素符号 / 名称"]').first().fill('Zn');
  await page.waitForTimeout(800);
  await includeDisabledBox(page).click();
  await page.waitForTimeout(2000);
  const after = await rowsOf(page).count();
  const afterCodes = (await rowsOf(page).locator('td:nth-child(2)').allInnerTexts()).join(',');
  const queryDisabled = await page.locator('button:visible').filter({ hasText: /^查\s*询$/ }).first().isDisabled();
  console.log(`[OS][②] 改筛选后：行数=${after} 查询按钮禁用=${queryDisabled}`);
  await page.screenshot({ path: path.join(SHOT_DIR, 'os-02-filter-immune.png'), fullPage: true }).catch(() => {});
  expect(after, '🔒 视图内容不受筛选影响').toBe(before);
  expect(afterCodes, '🔒 视图内的元素也应逐个不变').toBe(beforeCodes);
  expect(queryDisabled, '只看已选时「查询」应禁用').toBe(true);
});

test('④ 停用元素：includeDisabled=false 隐藏后，只看已选里仍可见并标「已停用」(#51)', async ({ page }) => {
  test.skip(!backendUp, 'backend down');
  await loginAsAdmin(page);

  // 零写库伪造停用元素：标 elementEnabled=false；includeDisabled=false 的请求里把它剔出结果集
  let disabledCode = '';
  await page.route(ELEMENTS_API, async (route) => {
    const excluded = /includeDisabled=false/.test(route.request().url());
    const response = await route.fetch();
    const json = await response.json().catch(() => null);
    const body = json?.data ?? json;
    const rows = body?.content;
    if (Array.isArray(rows) && rows.length > 0) {
      if (!disabledCode) disabledCode = rows[0].elementCode;
      if (excluded) {
        body.content = rows.filter((r: any) => r.elementCode !== disabledCode);
        if (typeof body.totalElements === 'number') body.totalElements -= 1;
      } else {
        for (const r of rows) if (r.elementCode === disabledCode) r.elementEnabled = false;
      }
    }
    await route.fulfill({ response, json });
  });

  await openTab(page);
  await checkFirstUnchecked(page, 2);   // 第一个就是被标停用的那个
  console.log(`[OS][④] 已勾选（含停用元素 ${disabledCode}）计数器=${await readCounter(page)}`);

  // 关掉「含已停用」→ 该元素从主列表消失
  await includeDisabledBox(page).click();
  await page.waitForTimeout(2500);
  const inMainList = (await rowsOf(page).locator('td:nth-child(2)').allInnerTexts()).some(t => t.includes(disabledCode));
  console.log(`[OS][④] 关掉含已停用后，主列表里还有 ${disabledCode} 吗 = ${inMainList}`);
  expect(inMainList, '前提：该元素应已从主列表隐藏').toBe(false);

  // 🔒 切「只看已选」→ 它必须仍在，且带「已停用」标签
  await onlySelectedBox(page).click();
  await page.waitForTimeout(2000);
  const codes = await rowsOf(page).locator('td:nth-child(2)').allInnerTexts();
  const visible = codes.some(t => t.includes(disabledCode));
  const disabledTagCount = await rowsOf(page).locator('text=已停用').count();
  console.log(`[OS][④] 只看已选：元素=${JSON.stringify(codes)}；${disabledCode} 可见=${visible}；「已停用」标签数=${disabledTagCount}`);
  await page.screenshot({ path: path.join(SHOT_DIR, 'os-03-disabled-in-view.png'), fullPage: true }).catch(() => {});
  expect(visible, '🔒 已勾选的停用元素必须在「只看已选」里可见（#51 最需要复核的一类）').toBe(true);
  expect(disabledTagCount, '🔒 且仍标「已停用」').toBeGreaterThan(0);
});
