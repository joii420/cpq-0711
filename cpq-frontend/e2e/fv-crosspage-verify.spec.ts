/**
 * 复验（第三位测试工程师，独立确认）：#2 料号矩阵 + ElementMatrix 同款 preserveSelectedRowKeys。
 *
 * 🔒 不复用开发者自检的三条（跨页 / 保存 / 反向），只补它没验的三个角度：
 *   A  跨页 × 筛选**叠加**（它分别验了，没验叠加）
 *   B  seed 回填的 key 与用户新勾的 key **混在一起**时 preserve 的行为
 *   C  ZZFV0729X 落库链路：元素已勾选并落库 → 被停用 → 以 includeDisabled=false 打开
 *      （该元素根本不在任何一页的行集里）→ 保存 → 它必须仍在清单里
 *      失败模式是**静默数据丢失**：财务毫无感知地把一个元素踢出调价清单。
 *      次级信号 = 意外弹出「需要二次确认」（后端 UNSELECT_NEEDS_CONFIRM），也断言它不出现。
 *
 * 数据域：自建 CUST-0729-FV（25 行 material_customer_map > 每页 20 必然分页）+ 全局 element 表
 * 加法式新插 ZZFV0729X(INACTIVE)。不碰 CUST-0001 / CUST-0729-QA / CUST-0805-FE。
 *
 * 🔒 本 spec 踩过并已规避的三个 antd/DOM 坑（前两条同 docs/E2E测试方法.md §4.6.1b）：
 *   1. antd 给**两字中文按钮**插空格 → 实际文本是「查 询」「保 存」，hasText:'查询' 永不命中。
 *      本 spec 一律用 /查\s*询/ 这类容空白正则；筛选优先直接 onPressEnter，绕开按钮歧义。
 *   2. 配了 scroll={{x:'max-content'}} 的表格（ElementMatrix 有、MaterialRangeMatrix 没有），
 *      tbody 第一行是 antd 隐藏测量行 tr.ant-table-measure-row（height:0 永远 hidden）
 *      → 行选择器一律 tr.ant-table-row。
 *   3. `.ant-pagination` 渲染在 `.ant-table` **之外**（同属 .ant-table-wrapper）——
 *      锚点若取 .ant-table，翻页器定位恒空。故容器锚点一律取 .ant-table-wrapper。
 */
import { test, expect, Page, Locator } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const SHOT_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

const CUSTOMER_NAME = 'FV0729复验客户';

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

/** 料号矩阵容器（wrapper 才含分页器）——用只有它才有的 FV-0729-M 行锁定 */
const matWrap = (page: Page) => page.locator('.ant-table-wrapper').filter({ hasText: 'FV-0729-M' }).first();
/** 元素矩阵容器——用元素矩阵独有的表头「元素符号」锁定 */
const elWrap = (page: Page) => page.locator('.ant-table-wrapper').filter({ hasText: '元素符号' }).first();
/** 料号矩阵计数器：只有它带「跨页保留选中」字样 */
const matCounter = (page: Page) => page.locator('span').filter({ hasText: '跨页保留选中' }).first();
/** 元素矩阵计数器：带「右侧为该客户最近 10 个版本的价格」字样 */
const elCounter = (page: Page) => page.locator('span').filter({ hasText: '右侧为该客户最近' }).first();

async function readCount(loc: Locator): Promise<number> {
  const t = await loc.innerText();
  const m = t.match(/已选\s*(\d+)\s*项/);
  return m ? Number(m[1]) : -1;
}

/** 🔒 tr.ant-table-row：跳过 antd 的隐藏测量行（坑 2） */
const rowOf = (wrap: Locator, text: string) =>
  wrap.locator('tbody tr.ant-table-row').filter({ hasText: text }).first();

async function toggleByText(page: Page, wrap: Locator, text: string) {
  const row = rowOf(wrap, text);
  await expect(row).toBeVisible({ timeout: 10_000 });
  await row.locator('input[type="checkbox"]').click();
  await page.waitForTimeout(350);
}

const isChecked = (wrap: Locator, text: string) =>
  rowOf(wrap, text).locator('input[type="checkbox"]').isChecked();

async function gotoPage(page: Page, wrap: Locator, n: number) {
  await wrap.locator(`.ant-pagination li.ant-pagination-item-${n} a`).first().click();
  await page.waitForTimeout(900);
}

/** 用 onPressEnter 触发查询，绕开「查 询」按钮的空格坑 + 两个矩阵同名按钮的歧义 */
async function filterMaterial(page: Page, value: string) {
  const input = page.locator('input[placeholder="销售料号"]').first();
  await input.fill(value);
  await input.press('Enter');
  await page.waitForTimeout(1400);
}

async function openStrategyTab(page: Page) {
  await page.goto('/pricing');
  await page.waitForLoadState('networkidle');
  await page.locator('input[placeholder="搜索客户"]').first().fill(CUSTOMER_NAME);
  await page.waitForTimeout(700);
  await page.locator('.ant-list-item').filter({ hasText: CUSTOMER_NAME }).first().click();
  await page.waitForTimeout(700);
  await page.locator('.ant-tabs-tab').filter({ hasText: '价格调整策略' }).first().click();
  await page.waitForTimeout(2000);
}

/** 🔒 /^保\s*存$/：antd 把「保存」渲染成「保 存」；且必须排除 Popconfirm 的「确认保存」 */
async function save(page: Page) {
  await page.getByRole('button', { name: /^保\s*存$/ }).first().click();
  await page.waitForTimeout(3000);
}

test('A+B 料号矩阵：跨页×筛选叠加 + seed回填与新勾混合', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page);
  await openStrategyTab(page);

  const mw = matWrap(page);
  await expect(mw).toBeVisible({ timeout: 20_000 });

  // ── B 起点：seed 回填 5 条（M01/M02/M03 在第1页，M21/M22 在第2页）
  expect(await readCount(matCounter(page))).toBe(5);
  expect(await isChecked(mw, 'FV-0729-M01')).toBe(true);
  await page.screenshot({ path: path.join(SHOT_DIR, 'fv-01-seed5.png'), fullPage: true });

  // ── A 筛选：只显示 FV-0729-M1x（10 行，seed 的 5 条一条都不在结果集里）
  await filterMaterial(page, 'FV-0729-M1');
  expect(await mw.locator('tbody tr.ant-table-row').count()).toBe(10);
  // 🔒 被筛掉的已选项不能掉：计数器仍须 5
  expect(await readCount(matCounter(page))).toBe(5);

  // ── B 混合：在筛选视图里新勾 2 条 → seed 的 5 条 + 新勾 2 条
  await toggleByText(page, mw, 'FV-0729-M10');
  await toggleByText(page, mw, 'FV-0729-M11');
  expect(await readCount(matCounter(page))).toBe(7);
  await page.screenshot({ path: path.join(SHOT_DIR, 'fv-02-filter-mix7.png'), fullPage: true });

  // ── A 叠加：清筛选 → 翻第2页 → 再勾 1 条（选中集横跨 筛选态 + 第1页 + 第2页 三种来源）
  await filterMaterial(page, '');
  expect(await readCount(matCounter(page))).toBe(7);
  await gotoPage(page, mw, 2);
  await toggleByText(page, mw, 'FV-0729-M23');
  expect(await readCount(matCounter(page))).toBe(8);

  // ── A 回第1页：seed 的 M01/M02/M03 与筛选态勾的 M10/M11 都必须还在
  await gotoPage(page, mw, 1);
  expect(await readCount(matCounter(page))).toBe(8);
  for (const m of ['FV-0729-M01', 'FV-0729-M02', 'FV-0729-M03', 'FV-0729-M10', 'FV-0729-M11']) {
    expect(await isChecked(mw, m), `${m} 应仍勾选`).toBe(true);
  }
  await page.screenshot({ path: path.join(SHOT_DIR, 'fv-03-back-p1-8.png'), fullPage: true });

  await save(page);
  await page.screenshot({ path: path.join(SHOT_DIR, 'fv-04-saved.png'), fullPage: true });
});

test('C 元素矩阵：已落库的停用元素在 includeDisabled=false 下保存不被踢出', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page);
  await openStrategyTab(page);

  const ew = elWrap(page);
  await expect(ew).toBeVisible({ timeout: 20_000 });

  // seed = Ag + Cu + ZZFV0729X(已停用) = 3
  expect(await readCount(elCounter(page))).toBe(3);
  await expect(elCounter(page)).toContainText('共 38 条');
  await page.screenshot({ path: path.join(SHOT_DIR, 'fv-05a-el-seed3-of38.png'), fullPage: true });

  // 关掉「含已停用」→ ZZFV0729X 从 dataSource 整体消失（38→37，且它不在任何一页）
  await page.locator('label').filter({ hasText: '含已停用' }).first().locator('input[type="checkbox"]').uncheck();
  await page.waitForTimeout(2000);
  await expect(elCounter(page)).toContainText('共 37 条');
  // 🔒 计数器仍须 3：不可见 ≠ 未选中
  expect(await readCount(elCounter(page))).toBe(3);
  await page.screenshot({ path: path.join(SHOT_DIR, 'fv-05b-el-hidden-still3.png'), fullPage: true });

  // 在「它不可见」的状态下勾一个别的元素（触发 handleSelectionChange 这条路径）
  // 🔒 必须挑 includeDisabled=false 时**第1页真实存在**的 code：
  //    第1页(1-20) = 191,206,223,258,301,304,316,430,721,Ag,Al,Be,C,Cd,Ce,Cr,Cu,DC04,Fe,H70
  //    （Ni36/SnO2 都在第2页 —— 选错页只会超时，是选点错误不是产品缺陷）
  await toggleByText(page, ew, 'Cd');
  expect(await readCount(elCounter(page))).toBe(4);

  // 再翻第2页勾一个，叠加跨页维度
  await gotoPage(page, ew, 2);
  await toggleByText(page, ew, 'SnO2');
  expect(await readCount(elCounter(page))).toBe(5);
  await page.screenshot({ path: path.join(SHOT_DIR, 'fv-06-el-p2-5.png'), fullPage: true });

  // 保存 —— 🔒 若 preserve 失效，payload 会缺 ZZFV0729X，后端判定为「取消勾选」返 409
  //          UNSELECT_NEEDS_CONFIRM → 弹「需要二次确认」。断言它不出现。
  await save(page);
  const confirmPopup = page.locator('.ant-popover').filter({ hasText: '需要二次确认' });
  expect(await confirmPopup.count(), '不应出现取消勾选二次确认（出现即说明 ZZFV0729X 被丢了）').toBe(0);
  await page.screenshot({ path: path.join(SHOT_DIR, 'fv-07-el-saved.png'), fullPage: true });
});
