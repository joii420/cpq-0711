/**
 * task-260909 F-1 / F-2 · 配置器 UI 自检（前端工程师开发期实测，不是正式测试用例）
 * 覆盖：AC-1（每列有选择器 + 恰好 3 项）· AC-2（整列批量设为）· AC-11（刷新后回填）
 * 🚫 只读 + 只在自建的 T260909FT- 组件上操作。
 */
import { test, expect, Page } from '@playwright/test';
import { openComponentByCode, switchTab } from './task260908-s2.helpers';

const CODE_BASIC = 'COMP-2422';   // T260909FT-BASIC（8 列全 BASIC_DATA）
const CODE_QUOTE = 'COMP-2424';   // T260909FT-QUOTE-NEW（9 列 INPUT_*）

async function login(page: Page) {
  await page.goto('/login');
  await page.fill('input[placeholder*="用户名"], input#username', 'admin');
  await page.fill('input[type="password"]', 'Admin@2026');
  await page.getByRole('button', { name: /登\s*录/ }).first().click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|components)/, { timeout: 30_000 });
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(3000);
}

/**
 * 读「已选输出列」里每行的字段类型选择器当前值。
 * 🚨 **不要按 `.ant-select-selection-item` 定位**：本仓 antd 是 v6，选中值的类名已变成
 *    `.ant-select-content-value`（@rc-component/select 的 SelectInput/Content/SingleContent.js）。
 *    按 v5 的类名写会**匹配 0 个**，症状是"读出来是空数组"，不是报错 —— 我第一跑就踩了。
 *    ⇒ 一律按 `[data-role="field-type-select"]` 定位、读整个 Select 根的 innerText。
 */
async function readFieldTypes(page: Page): Promise<string[]> {
  const sels = page.locator('[data-role="selected-column"] [data-role="field-type-select"]');
  const n = await sels.count();
  const out: string[] = [];
  for (let i = 0; i < n; i++) out.push((await sels.nth(i).innerText()).replace(/\s+/g, '').trim());
  return out;
}

test('AC-1 / AC-11：BASIC_DATA 组件的每列选择器回填「基础数据」，展开恰好 3 项', async ({ page }) => {
  test.setTimeout(240_000);
  await login(page);
  await openComponentByCode(page, CODE_BASIC);
  await switchTab(page, '取数配置');
  await expect(page.locator('.svb-recipe-bar').first(), '取数配置面板不可见 ⇒ 后面全是空跑').toBeVisible({ timeout: 25_000 });
  await page.waitForTimeout(4000);

  const rows = page.locator('[data-role="selected-column"]');
  const rowCount = await rows.count();
  console.log('[CFG] 钩子探针: field-type-select=' + await page.locator('[data-role="field-type-select"]').count()
    + ' bulk-field-type=' + await page.locator('[data-role="bulk-field-type"]').count()
    + ' apply-field-type-all=' + await page.locator('[data-role="apply-field-type-all"]').count()
    + ' .svb-ftype=' + await page.locator('.svb-ftype').count());
  const fts = await readFieldTypes(page);
  console.log(`[CFG] 已选输出列行数=${rowCount}，字段类型回填值=${JSON.stringify(fts)}`);
  expect(rowCount, '已选输出列为 0 ⇒ 回填没成功，后面断言无意义').toBe(8);
  expect(fts.length, '字段类型选择器个数应等于列数（AC-1：每一列都有）').toBe(8);
  for (const v of fts) expect(v, 'AC-11：应回填为「基础数据」').toBe('基础数据');

  // AC-1：展开第一列的选择器 —— 恰好 3 项且文案逐字
  await page.locator('[data-role="field-type-select"]').first().click();
  await page.waitForTimeout(800);
  const opts = await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option').allInnerTexts();
  const texts = opts.map((t) => t.replace(/\s+/g, ' ').trim());
  console.log(`[CFG] 选择器展开项 = ${JSON.stringify(texts)}`);
  await page.screenshot({ path: 'e2e/screenshots/ft6-cfg-选择器展开态.png' });
  expect(texts.length, 'AC-1：值域必须恰好 3 个').toBe(3);
  expect(texts[0]).toContain('基础数据');
  expect(texts[0]).toContain('只读展示');
  expect(texts[1]).toBe('文本输入');
  expect(texts[2]).toBe('数字输入');
  for (const bad of ['FORMULA', 'DATA_SOURCE', 'FIXED_VALUE', '公式', '固定值', '数据源']) {
    expect(texts.join('|'), `AC-1：不应出现 ${bad}`).not.toContain(bad);
  }
  await page.keyboard.press('Escape');
  await page.waitForTimeout(500);

  // AC-2：整列批量设为「文本输入」→ 应用到全部列
  const bulk = page.locator('.svb-ftype-bulk');
  await expect(bulk, 'AC-2：整列批量设置入口不可见').toBeVisible();
  await page.screenshot({ path: 'e2e/screenshots/ft6-cfg-列配置默认态.png' });
  await page.locator('[data-role="bulk-field-type"]').click();
  await page.waitForTimeout(600);
  await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')
    .filter({ hasText: /文本输入/ }).first().click();
  await page.waitForTimeout(500);
  await page.locator('[data-role="apply-field-type-all"]').click();
  await page.waitForTimeout(1200);
  const after = await readFieldTypes(page);
  console.log(`[CFG] 批量应用后 = ${JSON.stringify(after)}`);
  await page.screenshot({ path: 'e2e/screenshots/ft6-cfg-批量应用后.png' });
  for (const v of after) expect(v, 'AC-2：批量应用后每列都应变为「文本输入」').toBe('文本输入');
  // 🚫 不保存 —— 本条只验界面行为，落库由 AC-3/4/5 那条验
});

test('AC-5 对照：QUOTE 方言组件回填仍是 INPUT_*（文本/数字输入）', async ({ page }) => {
  test.setTimeout(240_000);
  await login(page);
  await openComponentByCode(page, CODE_QUOTE);
  await switchTab(page, '取数配置');
  await expect(page.locator('.svb-recipe-bar').first()).toBeVisible({ timeout: 25_000 });
  await page.waitForTimeout(4000);
  const fts = await readFieldTypes(page);
  console.log(`[CFG] QUOTE 组件字段类型回填 = ${JSON.stringify(fts)}`);
  await page.screenshot({ path: 'e2e/screenshots/ft6-cfg-报价方言对照.png' });
  expect(fts.length, '已选输出列为 0 ⇒ 空跑').toBe(9);
  expect(fts.filter((v) => v === '基础数据').length, 'AC-5：报价侧不该出现「基础数据」').toBe(0);
  expect(new Set(fts).size).toBeGreaterThan(1); // 文本/数字两种都在
  expect(fts).toEqual(['文本输入','数字输入','文本输入','文本输入','数字输入','文本输入','数字输入','数字输入','文本输入']);
});
