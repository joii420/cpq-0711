/**
 * task-0712 F6(E2E 验证) — 选配添加·COMPOSITE(Σqty≥2)冒烟测试
 *
 * 覆盖 quotation-flow.spec.ts 主流程未覆盖的分支：明细表单行 quantity 改为 2
 * （架构评审 2026-07-14 决策1：单料号 qty≥2 = 父 COMPOSITE + 1 个去重子件，
 * `composition_qty=qty`；指纹 `COMBO=P:qty`）触发 `CompositeProcessSection` 组合工艺
 * 条件区出现，选一个 `process_master.process_category='ASSEMBLY'` 候选，确认加入，
 * 验证卡片渲染无「加载中」死锁。
 *
 * 依赖：与 quotation-flow.spec.ts 同一套 fixture（苏州西门子 + 报价模板0608 最新版）+
 * F6 补的 `__DEFAULT__` 选配模板（sel_template，若客户/行业已有专属模板则复用现有）。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';

const __filename = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filename);
const SHOT_DIR = path.join(__dirnameLocal, 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `cps-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}

async function selectByLabel(page: Page, label: string, search: string, optionText?: string) {
  const item = page.locator('.ant-form-item').filter({ has: page.locator('label', { hasText: label }) }).first();
  const sel = item.locator('.ant-select').first();
  await sel.click();
  await page.waitForTimeout(300);
  if (search) {
    await page.keyboard.type(search, { delay: 60 });
    await page.waitForTimeout(900);
  }
  const opt = optionText || search;
  await page.locator('.ant-select-item-option').filter({ hasText: opt }).first().click();
  await page.waitForTimeout(400);
}

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

test('选配添加 COMPOSITE(Σqty=2 单料号去重子件) + 组合工艺区出现 + 卡片渲染无死锁', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  await loginAsAdmin(page);
  await page.goto('/quotations/new');
  await page.waitForLoadState('networkidle');

  await selectByLabel(page, '客户', '西门子');
  await page.locator('text=产品分类').first().waitFor({ state: 'visible', timeout: 10000 }).catch(() => {});
  await page.locator('text=产品分类').first().scrollIntoViewIfNeeded().catch(() => {});
  await page.waitForTimeout(800);

  // 名称字段填在「产品分类」/「报价模板」都选定之后（而非之前）——QuotationCreateForm 挂载时
  // productCategoryService.list().then() 的默认分类回填用的是 mount 时闭包住的 value（stale closure），
  // 若该异步早于用户填名称完成之后才 resolve，会用旧值(name='')回写覆盖用户刚填的名称，
  // 复现见 quotation-flow.spec.ts 调试记录；分类/模板都已落定后再填名称可完全绕开这个时序窗口。
  await selectByLabel(page, '产品分类', '默认分类');
  await page.keyboard.press('Escape');
  await page.waitForTimeout(300);

  await page.waitForTimeout(1200);
  {
    const templateItem = page.locator('.ant-form-item')
      .filter({ has: page.locator('label', { hasText: '报价模板' }) })
      .first();
    await templateItem.locator('.ant-select').first().click();
    await page.waitForTimeout(300);
    await page.keyboard.type('0608', { delay: 60 });
    await page.waitForTimeout(900);
    const versions = new Map<string, [number, number]>();
    const texts = await page.locator('.ant-select-item-option')
      .filter({ hasText: /报价模板0608\s+v\d+\.\d+/ }).allInnerTexts();
    for (const t of texts) {
      const m = t.match(/报价模板0608\s+v(\d+)\.(\d+)/);
      if (m) versions.set(`v${m[1]}.${m[2]}`, [parseInt(m[1], 10), parseInt(m[2], 10)]);
    }
    let bestKey = '';
    let best: [number, number] = [-1, -1];
    for (const [k, v] of versions) {
      if (v[0] > best[0] || (v[0] === best[0] && v[1] > best[1])) { best = v; bestKey = k; }
    }
    if (!bestKey) throw new Error('未在下拉中找到任何 报价模板0608 vX.Y 选项');
    const re = new RegExp(`报价模板0608\\s+${bestKey.replace('.', '\\.')}(\\s|$|<)`);
    const opt = page.locator('.ant-select-item-option').filter({ hasText: re }).first();
    await opt.scrollIntoViewIfNeeded().catch(() => {});
    await opt.click();
    await page.waitForTimeout(400);
    await page.keyboard.press('Escape');
    await page.waitForTimeout(300);
  }

  // 分类/模板均已落定(异步默认值回填 effect 早已 resolve)，此时才填名称，不会被 stale closure 覆盖。
  await page.locator('input[placeholder*="报价单名称"]').first().fill('E2E-composite-smoke-' + Date.now());
  await page.waitForTimeout(300);
  await shot(page, 'step1-ready');

  const nextBtn = page.getByRole('button', { name: /下一步/ }).first();
  await expect(nextBtn, 'Step1 校验应通过').toBeEnabled({ timeout: 15000 });
  await nextBtn.click();
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);

  await page.getByRole('button', { name: /添加产品/ }).first().click();
  await page.waitForTimeout(500);
  await page.locator('text=选配添加').first().click();
  await page.waitForTimeout(800);

  const addRowBtn = page.locator('.ant-drawer button:has-text("新增材质料号")');
  await addRowBtn.first().waitFor({ state: 'visible', timeout: 8000 });
  await addRowBtn.first().click();
  await page.waitForTimeout(500);

  // Step① 材质：选 00001(Ag，单元素默认配比=100%)。
  await page.locator('.ant-drawer').getByText('00001', { exact: true }).first().click();
  await page.waitForTimeout(300);
  await page.locator('.ant-drawer button:has-text("下一步")').last().click();
  await page.waitForTimeout(700);

  // Step② 元素含量：默认配比已=100%，直接下一步。
  await page.locator('.ant-drawer button:has-text("下一步")').last().click();
  await page.waitForTimeout(500);

  // Step③ 工序：勾选第一个候选。
  const firstProcessChip = page.locator('.ant-drawer label').filter({ has: page.locator('input[type="checkbox"]') }).first();
  if (await firstProcessChip.count() > 0) {
    await firstProcessChip.click();
    await page.waitForTimeout(200);
  }
  await page.locator('.ant-drawer button:has-text("确认添加")').last().click();
  await page.waitForTimeout(600);
  await shot(page, 'row-added-qty1');

  // ── 明细表：数量改为 2（Σqty=2 → COMPOSITE，架构决策1单料号去重子件场景）──
  const qtyInput = page.locator('.ant-drawer .ant-table-tbody .ant-input-number-input').first();
  await qtyInput.click();
  await qtyInput.fill('2');
  await qtyInput.blur();
  await page.waitForTimeout(600);
  await shot(page, 'qty-set-2');

  // ── 组合工艺条件区应出现（Σqty≥2）──
  const comboSection = page.locator('.ant-drawer').getByText('组合工艺', { exact: false });
  await expect(comboSection.first(), 'Σqty≥2 应出现组合工艺条件区').toBeVisible({ timeout: 5000 });
  console.log('[COMPOSITE] ✅ 组合工艺条件区已出现(Σqty≥2)');

  // 勾选一个组合工艺候选（process_master ASSEMBLY，如"总装配"）。
  const comboChip = page.locator('.ant-drawer').locator('label').filter({ has: page.locator('input[type="checkbox"]') })
    .filter({ hasText: /装配|焊接|连接/ }).first();
  const comboChipCount = await comboChip.count();
  console.log(`[COMPOSITE] 组合工艺候选命中数(装配/焊接/连接) = ${comboChipCount}`);
  if (comboChipCount > 0) {
    await comboChip.click();
    await page.waitForTimeout(300);
  }
  await shot(page, 'combo-process-picked');

  // ── 确认加入 ──
  await page.locator('.ant-drawer button:has-text("确认加入")').last().click();
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3500);
  await shot(page, 'after-confirm');

  // ── 卡片渲染验证：无「加载中」死锁 ──
  const loadingCount = await page.locator('text=加载中').count();
  console.log(`[COMPOSITE] 卡片渲染 '加载中' count = ${loadingCount} (期望 0)`);
  expect(loadingCount).toBe(0);

  const tabs = page.locator('button.qt-tab-btn');
  const tabCount = await tabs.count();
  console.log(`[COMPOSITE] Tab 总数 = ${tabCount}`);
  expect(tabCount).toBeGreaterThan(0);

  await shot(page, 'final');
  console.log('[COMPOSITE] ✅ 冒烟通过：Σqty=2 触发组合工艺区 + 提交成功 + 卡片渲染无死锁');
});
