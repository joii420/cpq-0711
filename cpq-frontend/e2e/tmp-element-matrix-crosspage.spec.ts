/**
 * #2 同款返修验证：参与调价元素矩阵「跨页保留选中」（ElementMatrix + preserveSelectedRowKeys）。
 *
 * 六项：
 *   ① 正向跨页  第1页勾3 → 第2页勾2 → 计数器=5 → 回第1页那3个仍勾着
 *   ② 保存链路  保存 → customer_price_adjust_element 落库 5 行（DB 由外层 SQL 核）
 *   ③ 反向      本页取消 + **跨页取消** 都能移除
 *   ④ 筛选副作用 关键字筛掉已选项后，已选不被误删
 *   ⑥ includeDisabled × preserve 交互（验收 #51：停用元素仍在清单里）
 *      🔒 现网 element 表 37 行**全是 ACTIVE**，没有停用元素；而 element 是**全局主数据**
 *         （所有客户共用，测试 A 的 #51 正在用），不能为了测试往里插停用行。
 *         故用零写库的网络层改写：把某个已勾选元素标成 elementEnabled=false，并在
 *         includeDisabled=false 的那次请求里把它从结果集剔除 —— 与后端 `AND status='ACTIVE'`
 *         的效果逐字等价，被测的是前端"该 key 不在当前 dataSource 时会不会被抹掉"。
 *
 * 数据域：自建 `CUST-0805-FE`（仅一条 customer；元素矩阵的行来自全局 element 表，无需 seed 料号）。
 * 不碰 CUST-0729-QA（测试 A）/ CUST-0001（外部会话）/ ZZ*。
 * 建数：INSERT INTO customer (name, code) VALUES ('前端验证专用0805','CUST-0805-FE');
 *
 * 跑法：PW_BASE_URL=http://localhost:5179 npx playwright test --config=e2e/playwright.config.ts \
 *        e2e/tmp-element-matrix-crosspage.spec.ts --reporter=list
 * 用完即删（含上面建的客户）。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const SHOT_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

const CUSTOMER_NAME = '前端验证专用0805';
const CUSTOMER_NO = 'CUST-0805-FE';

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

/** 元素矩阵表格：用「元素符号」表头锁定（料号矩阵没有这列） */
const elemTable = (page: Page) =>
  page.locator('.ant-table').filter({ hasText: '元素符号' }).first();
/** 元素矩阵计数器：文案含「右侧为该客户最近 10 个版本的价格」，料号矩阵是「跨页保留选中」 */
const counterSpan = (page: Page) =>
  page.locator('span').filter({ hasText: '右侧为该客户最近 10 个版本的价格' }).first();

async function readCounter(page: Page): Promise<number> {
  const t = await counterSpan(page).innerText();
  const m = t.match(/已选\s*(\d+)\s*项/);
  return m ? Number(m[1]) : -1;
}

/** 勾选当前页前 n 个**尚未勾选**的行（盲点下标会在 seed 回填后变成"取消"，见 #2 教训） */
async function checkFirstUnchecked(page: Page, n: number) {
  for (let i = 0; i < n; i++) {
    await elemTable(page).locator('tbody tr.ant-table-row input[type="checkbox"]:not(:checked)').first().click();
    await page.waitForTimeout(350);
  }
}
async function uncheckFirstChecked(page: Page) {
  await elemTable(page).locator('tbody tr.ant-table-row input[type="checkbox"]:checked').first().click();
  await page.waitForTimeout(350);
}
async function checkedOnPage(page: Page): Promise<number> {
  return await elemTable(page).locator('tbody tr.ant-table-row input[type="checkbox"]:checked').count();
}
/** 元素矩阵的分页器：按 .ant-table-wrapper 作用域取（页面上可能有多个分页器） */
const elemWrapper = (page: Page) =>
  page.locator('.ant-table-wrapper').filter({ hasText: '元素符号' }).first();
async function gotoPage(page: Page, n: number) {
  await elemWrapper(page).locator(`.ant-pagination-item-${n}`).first().click();
  await page.waitForTimeout(1500);
}

/** 进入 /pricing → 选客户 → 价格调整策略 Tab（元素矩阵无需 SPECIFIED 即渲染） */
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
  await elemTable(page).locator('tbody tr.ant-table-row').first().waitFor({ state: 'visible', timeout: 20_000 });
}

test('① 正向跨页 + ② 保存链路', async ({ page }) => {
  test.skip(!backendUp, 'backend down');
  await loginAsAdmin(page);
  await openTab(page);

  await checkFirstUnchecked(page, 3);
  const p1 = await readCounter(page);
  console.log(`[EM][①] 第1页勾3个后 计数器=${p1}`);
  expect(p1).toBe(3);

  await gotoPage(page, 2);
  await checkFirstUnchecked(page, 2);
  const p2 = await readCounter(page);
  console.log(`[EM][①] 第2页勾2个后 计数器=${p2}（本页勾中 ${await checkedOnPage(page)}）`);
  await page.screenshot({ path: path.join(SHOT_DIR, 'em-01-page2.png'), fullPage: true }).catch(() => {});
  expect(p2, '🔒 跨页累计应为 5（bug 时为 2）').toBe(5);

  await gotoPage(page, 1);
  const back = await checkedOnPage(page);
  console.log(`[EM][①] 回第1页 本页勾中=${back} 计数器=${await readCounter(page)}`);
  expect(back, '🔒 回第1页那3个仍应勾着').toBe(3);

  // ② 保存（两字中文按钮 antd 会插空格 → /^保\s*存$/；且多个 Tab 面板都有保存 → :visible）
  await page.locator('button:visible').filter({ hasText: /^保\s*存$/ }).first().click();
  await page.waitForTimeout(3500);
  await page.screenshot({ path: path.join(SHOT_DIR, 'em-02-saved.png'), fullPage: true }).catch(() => {});
  await openTab(page);
  const reseeded = await readCounter(page);
  console.log(`[EM][②] 重进页面后 seed 回填计数器=${reseeded}`);
  expect(reseeded, '重进后应从已保存的 5 个 seed 回来').toBe(5);
});

test('③ 反向：本页取消 + 跨页取消', async ({ page }) => {
  test.skip(!backendUp, 'backend down');
  await loginAsAdmin(page);
  await openTab(page);
  const base = await readCounter(page);
  await checkFirstUnchecked(page, 2);
  const added = await readCounter(page);
  await uncheckFirstChecked(page);
  const afterUncheck = await readCounter(page);
  console.log(`[EM][③] 基线=${base} 勾2后=${added} 本页取消1后=${afterUncheck}`);
  expect(afterUncheck).toBe(added - 1);

  await gotoPage(page, 2);
  await checkFirstUnchecked(page, 1);
  const p2Add = await readCounter(page);
  await uncheckFirstChecked(page);
  const p2Del = await readCounter(page);
  console.log(`[EM][③] 第2页 勾后=${p2Add} 取消后=${p2Del}`);
  expect(p2Del, '🔒 跨页取消同样要能移除').toBe(p2Add - 1);
});

test('④ 筛选副作用：关键字筛掉已选项后不被误删', async ({ page }) => {
  test.skip(!backendUp, 'backend down');
  await loginAsAdmin(page);
  await openTab(page);
  await checkFirstUnchecked(page, 2);
  const seeded = await readCounter(page);
  console.log(`[EM][④] 勾2个后 计数器=${seeded}`);

  await page.locator('input[placeholder="元素符号 / 名称"]').first().fill('Zn');
  await page.locator('button:visible').filter({ hasText: /^查\s*询$/ }).first().click();
  await page.waitForTimeout(2000);
  const rows = await elemTable(page).locator('tbody tr.ant-table-row').count();
  const afterFilter = await readCounter(page);
  console.log(`[EM][④] 筛选后 行数=${rows} 计数器=${afterFilter}`);
  expect(afterFilter, '🔒 仅切筛选不应改变已选集合').toBe(seeded);

  await checkFirstUnchecked(page, 1);
  const afterPick = await readCounter(page);
  console.log(`[EM][④] 筛选结果里再勾1个后 计数器=${afterPick}`);
  await page.screenshot({ path: path.join(SHOT_DIR, 'em-03-filtered.png'), fullPage: true }).catch(() => {});
  expect(afterPick, '🔒 应 +1（被筛掉的已选项不得丢失）').toBe(seeded + 1);
});

test('⑥ includeDisabled × preserve：停用元素被隐藏后仍留在清单里（#51）', async ({ page }) => {
  test.skip(!backendUp, 'backend down');
  await loginAsAdmin(page);

  // 零写库地造出"停用元素"：把结果集里第一个元素标成 elementEnabled=false；
  // 当请求带 includeDisabled=false 时，把它从结果集剔除（等价后端 AND status='ACTIVE'）。
  let disabledCode = '';
  await page.route(/\/api\/cpq\/price-adjust\/strategies\/[^/]+\/elements\?/, async (route) => {
    const url = route.request().url();
    const excluded = /includeDisabled=false/.test(url);
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
  // 勾上那个"停用"元素（它此刻可见，含已停用=开）
  await checkFirstUnchecked(page, 1);
  const withDisabled = await readCounter(page);
  console.log(`[EM][⑥] 勾上停用元素 ${disabledCode} 后 计数器=${withDisabled}`);
  expect(withDisabled).toBeGreaterThan(0);

  // 关掉「含已停用」→ 该元素从 dataSource 消失
  await page.locator('.ant-checkbox-wrapper').filter({ hasText: '含已停用' }).first().click();
  await page.waitForTimeout(2500);
  const hiddenStill = await readCounter(page);
  const visibleCodes = await elemTable(page).locator('tbody tr.ant-table-row td:first-child').allInnerTexts();
  console.log(`[EM][⑥] 关掉含已停用后 计数器=${hiddenStill}；${disabledCode} 是否还在表内=${visibleCodes.some(t => t.includes(disabledCode))}`);
  expect(hiddenStill, '仅隐藏不应改变已选集合').toBe(withDisabled);

  // 🔒 关键：此时再勾一个可见行 —— bug 版会把"已勾但当前不可见"的停用元素一并抹掉
  await checkFirstUnchecked(page, 1);
  const afterPick = await readCounter(page);
  console.log(`[EM][⑥] 隐藏态下再勾1个后 计数器=${afterPick}`);
  await page.screenshot({ path: path.join(SHOT_DIR, 'em-04-disabled-hidden.png'), fullPage: true }).catch(() => {});
  expect(afterPick, '🔒 应 +1；停用元素不得因不可见而掉出清单（验收 #51）').toBe(withDisabled + 1);
});
