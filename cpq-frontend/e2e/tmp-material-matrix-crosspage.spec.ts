/**
 * #2 返修验证：指定料号矩阵「跨页保留选中」（MaterialRangeMatrix + preserveSelectedRowKeys）。
 *
 * 五项（对应技术总监自检清单）：
 *   ① 正向跨页  第1页勾3 → 第2页勾2 → 计数器=5 → 回第1页那3个仍勾着
 *   ② 保存链路  保存 → 后端 selectedOnly 查询回 5 行（DB 由外层 SQL 再核一次）
 *   ③ 反向不误伤 取消勾选仍能移除（不能写成"只增不减"）
 *   ④ 筛选副作用 切筛选后被筛掉的已选项不被误删
 *   ⑤ tsc / transform 在 spec 外跑
 *
 * 数据域：自建 `CUST-0805-FE`（客户「前端验证专用0805」+ 25 行 material_customer_map，
 * 25 > PAGE_SIZE 20 故必然分页）。**不碰** CUST-0729-QA / QB、ZZ47-* / ZZ61-*。
 * 建数（跑前）：
 *   INSERT INTO customer (name, code) VALUES ('前端验证专用0805','CUST-0805-FE');
 *   INSERT INTO material_customer_map (material_no, customer_no, system_type,
 *          customer_material_name, customer_product_no)
 *   SELECT 'FE0805-'||lpad(g::text,2,'0'), 'CUST-0805-FE', 'QUOTE', '验证料件'||g,
 *          'CP-FE0805-'||lpad(g::text,2,'0') FROM generate_series(1,25) g;
 *
 * 跑法（主仓改动须自起临时端口，5174 是共享 dev server）：
 *   PW_BASE_URL=http://localhost:5179 npx playwright test --config=e2e/playwright.config.ts \
 *     e2e/tmp-material-matrix-crosspage.spec.ts --reporter=list
 * 用完即删（含上面建的数据）。
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

/** 料号矩阵表格（用只有它才有的 FE0805- 行锁定，避免与元素矩阵串台） */
const matrixTable = (page: Page) => page.locator('.ant-table').filter({ hasText: 'FE0805-' }).first();
/** 料号矩阵计数器（元素矩阵的计数器没有「跨页保留选中」字样） */
const counterSpan = (page: Page) => page.locator('span').filter({ hasText: '跨页保留选中' }).first();

async function readCounter(page: Page): Promise<number> {
  const t = await counterSpan(page).innerText();
  const m = t.match(/已选\s*(\d+)\s*项/);
  return m ? Number(m[1]) : -1;
}

async function checkedCountOnPage(page: Page): Promise<number> {
  return await matrixTable(page).locator('tbody tr input[type="checkbox"]:checked').count();
}

async function toggleRow(page: Page, idx: number) {
  await matrixTable(page).locator('tbody tr').nth(idx).locator('input[type="checkbox"]').click();
  await page.waitForTimeout(350);
}

/**
 * 勾选当前页**前 n 个尚未勾选**的行。
 * ⚠️ 不能盲点下标 0/1/2：一旦上一轮保存过，父层 seed 会把它们预勾上，再点等于**取消**
 *   （实测症状：「勾3个后 计数器=2」，看着像功能坏了，其实是用例的状态依赖）。
 */
async function checkFirstUnchecked(page: Page, n: number) {
  for (let i = 0; i < n; i++) {
    const box = matrixTable(page).locator('tbody tr input[type="checkbox"]:not(:checked)').first();
    await box.click();
    await page.waitForTimeout(350);
  }
}

/** 取消当前页第一个已勾选的行 */
async function uncheckFirstChecked(page: Page) {
  await matrixTable(page).locator('tbody tr input[type="checkbox"]:checked').first().click();
  await page.waitForTimeout(350);
}

async function gotoPage(page: Page, n: number) {
  await page.locator(`.ant-pagination-item-${n}`).first().click();
  await page.waitForTimeout(1500);
}

/** 进入 /pricing → 选客户 → 价格调整策略 Tab → 料号范围=指定料号 → 矩阵可见 */
async function openMatrix(page: Page) {
  await page.goto('/pricing');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  // antd <Search> 是 onSearch 触发（回车/放大镜），只 fill 不回车列表不会刷新
  await page.locator('input[placeholder="搜索客户"]').first().fill(CUSTOMER_NAME);
  await page.locator('input[placeholder="搜索客户"]').first().press('Enter');
  await page.waitForTimeout(1800);
  await page.locator('.ant-list-item').filter({ hasText: CUSTOMER_NAME }).first().click();
  await page.waitForTimeout(1800);
  await page.locator('.ant-tabs-tab').filter({ hasText: '价格调整策略' }).first().click();
  await page.waitForTimeout(2000);
  await page.locator('.ant-radio-wrapper').filter({ hasText: '指定料号' }).first().click();
  await page.waitForTimeout(2500);
  await matrixTable(page).locator('tbody tr').first().waitFor({ state: 'visible', timeout: 20_000 });
}

test('① 正向跨页：第1页勾3 + 第2页勾2 = 5，回第1页仍勾着', async ({ page }) => {
  test.skip(!backendUp, 'backend down');
  await loginAsAdmin(page);
  await openMatrix(page);

  await checkFirstUnchecked(page, 3);
  const afterP1 = await readCounter(page);
  console.log(`[MM][①] 第1页勾3个后 计数器=${afterP1}`);
  expect(afterP1, '第1页勾3个').toBe(3);

  await gotoPage(page, 2);
  await checkFirstUnchecked(page, 2);
  const afterP2 = await readCounter(page);
  const checkedOnP2 = await checkedCountOnPage(page);
  console.log(`[MM][①] 第2页勾2个后 计数器=${afterP2}（本页勾中 ${checkedOnP2}）`);
  await page.screenshot({ path: path.join(SHOT_DIR, 'mm-01-page2.png'), fullPage: true }).catch(() => {});
  expect(afterP2, '🔒 跨页累计应为 5（bug 时为 2）').toBe(5);

  await gotoPage(page, 1);
  const checkedBackOnP1 = await checkedCountOnPage(page);
  const counterBack = await readCounter(page);
  console.log(`[MM][①] 回第1页 本页勾中=${checkedBackOnP1} 计数器=${counterBack}`);
  await page.screenshot({ path: path.join(SHOT_DIR, 'mm-02-back-page1.png'), fullPage: true }).catch(() => {});
  expect(checkedBackOnP1, '🔒 回第1页那3个仍应勾着（bug 时为 0）').toBe(3);
  expect(counterBack).toBe(5);

  // ② 保存链路：保存后按后端 selectedOnly 复查（用户的实际损失发生在保存）
  // ⚠️ 两个坑叠在一起，缺一个都点不到：
  //    1) 页面上有 3 个「保存」按钮（元素价格策略 Tab 面板未激活但仍挂在 DOM 里）→ 用 :visible 限定；
  //    2) 🔒 antd 对**两个汉字**的按钮自动插空格（ant-btn 两字规则），DOM 里实际是 "保 存" / "查 询"，
  //       所以 /^保存$/ 这类精确匹配**永远不命中**（实测卡了两轮）。凡两字中文按钮一律写 /^保\s*存$/。
  await page.locator('button:visible').filter({ hasText: /^保\s*存$/ }).first().click();
  await page.waitForTimeout(3500);
  await page.screenshot({ path: path.join(SHOT_DIR, 'mm-03-saved.png'), fullPage: true }).catch(() => {});
  const res = await page.request.get(
    `/api/cpq/price-adjust/strategies/${CUSTOMER_NO}/materials?page=1&size=100&selectedOnly=true`);
  const json = await res.json().catch(() => null);
  const body = json?.data ?? json;
  const saved = (body?.content ?? []).map((r: any) => r.materialNo).sort();
  console.log(`[MM][②] 保存后后端已选 ${saved.length} 个: ${JSON.stringify(saved)}`);
  expect(saved.length, '🔒 落库应为 5 个料号（bug 时只存翻页后勾的 2 个）').toBe(5);

  // 顺带验证 seed 回填：重进页面，父层 selectedOnly 大分页应把 5 个预选回来
  await openMatrix(page);
  const reseeded = await readCounter(page);
  console.log(`[MM][②] 重进页面后 seed 回填计数器=${reseeded}`);
  expect(reseeded, '重进后应从已保存的 5 个 seed 回来').toBe(5);
});

test('③ 反向不误伤：取消勾选仍能移除', async ({ page }) => {
  test.skip(!backendUp, 'backend down');
  await loginAsAdmin(page);
  await openMatrix(page);
  // 自给自足：不依赖上一条用例的保存结果（用例间零耦合，单跑也成立）
  const base = await readCounter(page);
  await checkFirstUnchecked(page, 3);
  const afterCheck = await readCounter(page);
  console.log(`[MM][③] 基线=${base} 勾3个后=${afterCheck}`);

  await uncheckFirstChecked(page);                // 取消一个已勾选的
  const afterUncheck = await readCounter(page);
  console.log(`[MM][③] 取消1个后 计数器=${afterUncheck}`);
  expect(afterUncheck, '🔒 取消勾选必须真的移除（不能写成只增不减）').toBe(afterCheck - 1);

  // 跨页取消也要生效：翻到第2页勾1个再取消
  await gotoPage(page, 2);
  await checkFirstUnchecked(page, 1);
  const p2Add = await readCounter(page);
  await uncheckFirstChecked(page);
  const p2Del = await readCounter(page);
  console.log(`[MM][③] 第2页 勾后=${p2Add} 再取消后=${p2Del}`);
  expect(p2Del, '🔒 跨页取消同样要能移除').toBe(p2Add - 1);
});

test('④ 筛选副作用：切筛选后被筛掉的已选项不被误删', async ({ page }) => {
  test.skip(!backendUp, 'backend down');
  await loginAsAdmin(page);
  await openMatrix(page);
  // 自给自足：先在第 1 页勾 2 个（FE0805-01/02，必然落在下面 FE0805-2x 筛选结果之外）
  await checkFirstUnchecked(page, 2);
  const seeded = await readCounter(page);
  console.log(`[MM][④] 勾2个后 计数器=${seeded}`);
  expect(seeded).toBeGreaterThan(0);

  // 筛到一个必然不含已选项的窄结果集（FE0805-2x 区间），再勾一个新行
  await page.locator('input[placeholder="销售料号"]').first().fill('FE0805-2');
  await page.locator('button').filter({ hasText: /^查\s*询$/ }).first().click();
  await page.waitForTimeout(2000);
  const rowsAfterFilter = await matrixTable(page).locator('tbody tr').count();
  const counterAfterFilter = await readCounter(page);
  console.log(`[MM][④] 筛选后 行数=${rowsAfterFilter} 计数器=${counterAfterFilter}`);
  expect(counterAfterFilter, '🔒 仅切筛选不应改变已选集合').toBe(seeded);

  await checkFirstUnchecked(page, 1);
  const counterAfterPick = await readCounter(page);
  console.log(`[MM][④] 筛选结果里再勾1个后 计数器=${counterAfterPick}`);
  await page.screenshot({ path: path.join(SHOT_DIR, 'mm-04-filtered.png'), fullPage: true }).catch(() => {});
  expect(counterAfterPick, '🔒 应在原有基础上 +1（被筛掉的已选项不得丢失）').toBe(seeded + 1);
});
