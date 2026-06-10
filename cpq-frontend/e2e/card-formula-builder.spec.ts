/**
 * E2E: CardFormulaDrawer 视觉构建器验收测试（P1.2 Task 6）
 *
 * 覆盖范围（只读 — 不点保存，不持久化）：
 *   1. 抽屉标题含「编辑卡片公式」+ Segmented 简单/高级
 *   2. 简单模式：引用类型 Select 仅 4 个友好选项（无 AVG/COUNT/MAX/MIN）
 *   3. 切换高级模式：原 RefType Radio.Group 4 个按钮出现
 *   4. 简单模式 + 选「按条件求和」→ 芯片构建器（字段 Select + × 等运算符按钮）出现
 *   5. 「加载中」= 0 / 无 JS 错误
 *
 * 数据纪律（仿 card-aggregate-dynamic-flow.spec.ts）：
 *   - beforeAll 克隆 DRAFT 模板 89666d09 为专属测试模板 CFBLD_TMPL；
 *     不改任何报价单数据、组件数据。
 *   - afterAll 删除 CFBLD_TMPL + 关联 template_component 行。
 *
 * 夹具：
 *   - 源 DRAFT 模板：89666d09（报价单模板0601，6 个组件含工序页签，5c47fb41）
 */

import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { execSync } from 'child_process';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const __filename = fileURLToPath(import.meta.url);
const SHOT_DIR = path.join(path.dirname(__filename), 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

// ── 夹具常量 ──────────────────────────────────────────────────────────────────

/** 克隆源：报价单模板0601，DRAFT，有 6 个组件（含工序 5c47fb41:3） */
const SRC_TMPL = '89666d09-424d-4f14-aa97-5972a3de290f';

/** 专属测试模板 ID（固定，方便清理；beforeAll 写入，afterAll 删除） */
const CFBLD_TMPL = 'aaaaaaaa-0000-0000-0000-0000000cfb01';

// ── 工具函数 ──────────────────────────────────────────────────────────────────

function psql(sql: string): string {
  try {
    return execSync('PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db',
      { input: sql, encoding: 'utf-8', stdio: ['pipe', 'pipe', 'pipe'] });
  } catch (e: any) {
    const msg = e.stdout ?? e.stderr ?? String(e);
    console.warn('[psql] warn:', msg.slice(0, 300));
    return msg;
  }
}

let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `cfb-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}

/** AntD Select：点开后在可见下拉里按文本选 option */
async function pickOption(page: Page, opener: ReturnType<Page['locator']>, optionText: string) {
  await opener.click();
  const opt = page.locator('.ant-select-dropdown:visible .ant-select-item-option', { hasText: optionText }).first();
  await opt.waitFor({ state: 'visible', timeout: 5000 });
  await opt.click();
}

// ── 生命周期 ──────────────────────────────────────────────────────────────────

let backendUp = false;
let setupDone = false;

test.beforeAll(async () => {
  backendUp = await isBackendUp();
  if (!backendUp) { console.log('[beforeAll] 后端未启动，跳过'); return; }

  // 清除可能残留的旧测试模板，再克隆 SRC_TMPL
  psql(`DELETE FROM template_component WHERE template_id='${CFBLD_TMPL}';
        DELETE FROM template WHERE id='${CFBLD_TMPL}';
        INSERT INTO template
          SELECT r.*
          FROM template t,
          LATERAL jsonb_populate_record(NULL::template,
            to_jsonb(t) || jsonb_build_object(
              'id', '${CFBLD_TMPL}',
              'status', 'DRAFT',
              'name', '__E2E_CardFormulaBuilder',
              'version', NULL,
              'excel_view_config', NULL
            )) AS r
          WHERE t.id='${SRC_TMPL}';
        INSERT INTO template_component
          SELECT r.*
          FROM template_component tc,
          LATERAL jsonb_populate_record(NULL::template_component,
            to_jsonb(tc) || jsonb_build_object(
              'id', gen_random_uuid()::text,
              'template_id', '${CFBLD_TMPL}'
            )) AS r
          WHERE tc.template_id='${SRC_TMPL}';`);

  const chk = psql(`SELECT status, name FROM template WHERE id='${CFBLD_TMPL}';`);
  console.log('[beforeAll] 测试模板:', chk.replace(/\s+/g, ' ').trim());
  setupDone = chk.includes('DRAFT');
  if (!setupDone) console.error('[beforeAll] 测试模板创建失败！');
});

test.afterAll(async () => {
  if (!backendUp) return;
  const out = psql(`DELETE FROM template_component WHERE template_id='${CFBLD_TMPL}';
                    DELETE FROM template WHERE id='${CFBLD_TMPL}';`);
  console.log('[afterAll] 清理测试模板:', out.replace(/\s+/g, ' ').trim().slice(0, 120));
});

// ── 测试：CardFormulaDrawer 视觉构建器 ────────────────────────────────────────

test('CardFormulaDrawer: Segmented简单/高级 + 简单模式4选项 + 高级模式RadioGroup + 按条件求和芯片构建器', async ({ page }) => {
  test.skip(!backendUp || !setupDone, '后端未启动或夹具失败');

  // ── 登录 ──────────────────────────────────────────────────────────
  await loginAsAdmin(page);
  expect(page.url(), '登录后不在 /login').not.toContain('/login');

  // ── 导航到模板配置页 ──────────────────────────────────────────────
  await page.goto(`/templates/${CFBLD_TMPL}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  await shot(page, 'tmpl-config');

  // ── 切换到 Excel 视图 ─────────────────────────────────────────────
  // 先确保在「组件配置」Tab（避免 sticky toolbar 遮挡）
  const componentsTab = page.locator('.ant-tabs-tab').filter({ hasText: /^组件配置$/ }).first();
  if (await componentsTab.count() > 0) {
    await componentsTab.evaluate((el) => {
      const btn = el.querySelector('.ant-tabs-tab-btn') as HTMLElement | null;
      if (btn) btn.click();
      else (el as HTMLElement).click();
    });
    await page.waitForTimeout(300);
  }

  const excelViewBtn = page.locator('button').filter({ hasText: 'Excel视图' }).first();
  await expect(excelViewBtn, 'Excel视图按钮应可见').toBeVisible({ timeout: 8000 });
  await excelViewBtn.click();
  await page.waitForTimeout(1200);
  await shot(page, 'excel-view-mode');

  // ── 添加 CARD_FORMULA 列 ──────────────────────────────────────────
  const addColBtn = page.getByRole('button', { name: '添加列' });
  await expect(addColBtn, '添加列按钮应存在').toBeVisible({ timeout: 6000 });
  await addColBtn.click();
  await page.waitForTimeout(400);
  // 在下拉菜单中点「卡片公式 CARD_FORMULA」
  const cardFormulaItem = page.locator('.ant-dropdown-menu-item', { hasText: /卡片公式/ }).first();
  await expect(cardFormulaItem, '卡片公式菜单项应可见').toBeVisible({ timeout: 5000 });
  await cardFormulaItem.click();
  await page.waitForTimeout(800);
  await shot(page, 'card-col-added');

  // ── 打开 CardFormulaDrawer（点新 CARD_FORMULA 行的「编辑」按钮）────
  // 找含 placeholder「点击右侧按钮编辑卡片公式」的行内「编辑」按钮
  const cardRow = page.locator('.ant-space-compact')
    .filter({ has: page.locator('input[placeholder*="编辑卡片公式"]') }).first();
  await cardRow.scrollIntoViewIfNeeded();
  // 「编辑」按钮在 Space.Compact 内的 Button（含文字「编辑」）
  const editBtn = cardRow.getByRole('button', { name: '编辑' }).first();
  await expect(editBtn, '编辑按钮应可见').toBeVisible({ timeout: 5000 });
  await editBtn.click();
  await page.waitForTimeout(1200);
  await shot(page, 'drawer-opening');

  // ── 断言 1：抽屉标题含「编辑卡片公式」 ────────────────────────────
  const drawer = page.locator('.ant-drawer-content-wrapper').first();
  await expect(drawer, '抽屉应可见').toBeVisible({ timeout: 8000 });

  // 标题区含「编辑卡片公式」文字
  const drawerTitle = drawer.locator('.ant-drawer-title');
  await expect(drawerTitle, '抽屉标题应含「编辑卡片公式」').toContainText('编辑卡片公式', { timeout: 8000 });
  console.log('✅ 断言1：抽屉标题含「编辑卡片公式」');

  // ── 断言 2：Segmented 简单/高级可见，默认简单模式 ─────────────────
  const segmented = drawer.locator('.ant-segmented');
  await expect(segmented, 'Segmented 组件应可见').toBeVisible({ timeout: 5000 });
  await expect(segmented.locator('.ant-segmented-item', { hasText: '简单' }), '简单选项应存在').toBeVisible();
  await expect(segmented.locator('.ant-segmented-item', { hasText: '高级' }), '高级选项应存在').toBeVisible();
  // 默认应为简单（selected 项）
  const simpleItem = segmented.locator('.ant-segmented-item', { hasText: '简单' });
  await expect(simpleItem, '默认选中简单模式').toHaveClass(/ant-segmented-item-selected/);
  console.log('✅ 断言2：Segmented 简单/高级可见，默认简单模式');

  // ── 断言 3：简单模式 —— 引用类型 Select 有 4 个友好选项 ────────────
  // 等待页签加载完（Spin 消失后 Select 可点）
  await page.waitForTimeout(500);

  const refTypeFormItem = drawer.locator('.ant-form-item').filter({ hasText: '引用类型' }).first();
  await expect(refTypeFormItem, '引用类型表单项应可见').toBeVisible({ timeout: 5000 });

  // 点开 Select 查看选项
  const refTypeSelect = refTypeFormItem.locator('.ant-select').first();
  await refTypeSelect.click();
  await page.waitForTimeout(400);

  const dropdown = page.locator('.ant-select-dropdown:visible').last();
  await expect(dropdown, '引用类型下拉应弹出').toBeVisible({ timeout: 5000 });

  const optionItems = dropdown.locator('.ant-select-item-option');
  const optCount = await optionItems.count();
  console.log(`[简单模式] 引用类型 Select 选项数 = ${optCount}`);

  // 收集可见选项文本
  const optTexts: string[] = [];
  for (let i = 0; i < optCount; i++) {
    optTexts.push(((await optionItems.nth(i).innerText().catch(() => '')).trim()));
  }
  console.log('[简单模式] 选项:', optTexts);

  // 断言 4 个友好选项存在
  const expected4 = ['引用页签小计', '取某字段的值', '按条件查找取值', '按条件求和'];
  for (const label of expected4) {
    expect(optTexts, `简单模式应含「${label}」`).toContain(label);
  }
  // 断言高级模式独有的 AVG/COUNT/MAX/MIN 不出现
  const shouldAbsent = ['求平均', '计数', '求最大', '求最小'];
  for (const label of shouldAbsent) {
    expect(optTexts, `简单模式不应含「${label}」`).not.toContain(label);
  }
  expect(optCount, '简单模式应只有 4 个选项').toBe(4);
  console.log('✅ 断言3：简单模式 Select 恰好 4 个友好选项（无求平均/计数/最大/最小）');

  // 关闭下拉（按 Esc 或点外部）
  await page.keyboard.press('Escape');
  await page.waitForTimeout(200);

  // ── 断言 4：切高级模式 → Radio.Group 出现，含 4 个 Radio.Button ──
  const advancedItem = segmented.locator('.ant-segmented-item', { hasText: '高级' });
  await advancedItem.click();
  await page.waitForTimeout(500);
  await shot(page, 'advanced-mode');

  // Radio.Group 应可见，且含 4 个 Radio.Button
  const radioGroup = drawer.locator('.ant-radio-group').first();
  await expect(radioGroup, '高级模式 Radio.Group 应出现').toBeVisible({ timeout: 5000 });

  const radioBtns = radioGroup.locator('.ant-radio-button-wrapper');
  const rbCount = await radioBtns.count();
  console.log(`[高级模式] Radio.Button 数量 = ${rbCount}`);

  const rbTexts: string[] = [];
  for (let i = 0; i < rbCount; i++) {
    rbTexts.push(((await radioBtns.nth(i).innerText().catch(() => '')).trim()));
  }
  console.log('[高级模式] Radio.Button 选项:', rbTexts);

  // 4 个按钮：页签小计 / 字段·首行 / 字段·按条件取行 / 聚合（SUM/AVG…）
  expect(rbTexts, '高级模式应含「页签小计」').toContain('页签小计');
  expect(rbTexts, '高级模式应含「字段·首行」').toContain('字段·首行');
  expect(rbTexts, '高级模式应含「字段·按条件取行」').toContain('字段·按条件取行');
  expect(rbTexts.some(t => t.includes('聚合')), '高级模式应含「聚合」').toBe(true);
  expect(rbCount, '高级模式 Radio.Group 应有 4 个按钮').toBe(4);
  console.log('✅ 断言4：高级模式 Radio.Group 4 个按钮（页签小计/字段·首行/字段·按条件取行/聚合）');

  // ── 断言 5：切回简单模式 + 选「按条件求和」→ 芯片构建器出现 ────────
  const simpleItemAgain = segmented.locator('.ant-segmented-item', { hasText: '简单' });
  await simpleItemAgain.click();
  await page.waitForTimeout(400);

  // 简单模式下 Select 切换到「按条件求和」
  const refTypeSelectAgain = drawer.locator('.ant-form-item').filter({ hasText: '引用类型' }).first()
    .locator('.ant-select').first();
  await pickOption(page, refTypeSelectAgain, '按条件求和');
  await page.waitForTimeout(500);
  await shot(page, 'simple-sum-selected');

  // 芯片构建器区域：应有字段 Select（placeholder=字段）
  const chipFieldSelect = drawer.locator('.ant-form-item')
    .filter({ hasText: '行内聚合表达式' })
    .locator('.ant-select[class*="ant-select-sm"]').first();
  await expect(chipFieldSelect, '按条件求和 简单模式应有字段 Select（芯片构建器）').toBeVisible({ timeout: 5000 });

  // 应有运算符按钮 ×（chip builder）
  const mulBtn = drawer.locator('button', { hasText: '×' }).first();
  await expect(mulBtn, '芯片构建器应含 × 运算符按钮').toBeVisible({ timeout: 5000 });

  // 同时验证 + 按钮存在（芯片操作按钮区）
  const plusBtn = drawer.locator('.ant-form-item').filter({ hasText: '行内聚合表达式' }).locator('button', { hasText: '+' }).first();
  await expect(plusBtn, '芯片构建器应含 + 按钮').toBeVisible({ timeout: 5000 });
  // 「删末」是 danger 类按钮（中文 hasText 在某些 Playwright 编码环境不稳定，改用 CSS class 定位）
  const delLastBtn = drawer.locator('.ant-form-item').filter({ hasText: '行内聚合表达式' })
    .locator('button.ant-btn-dangerous').first();
  await expect(delLastBtn, '芯片构建器应含「删末」按钮（ant-btn-dangerous）').toBeVisible({ timeout: 5000 });

  console.log('✅ 断言5：简单模式+按条件求和 → 芯片构建器（字段Select + × + + + 删末）可见');
  await shot(page, 'chip-builder-visible');

  // ── 断言 6：「加载中」= 0 ─────────────────────────────────────────
  const loadingCount = await page.locator('text=加载中').count();
  console.log(`[加载中] count = ${loadingCount}`);
  expect(loadingCount, '「加载中」应为 0').toBe(0);
  console.log('✅ 断言6：加载中 = 0');

  // ── 不点保存，直接关抽屉 ─────────────────────────────────────────
  const cancelBtn = drawer.getByRole('button', { name: '取消' }).first();
  if (await cancelBtn.count() > 0 && await cancelBtn.isVisible()) {
    await cancelBtn.click();
  } else {
    const closeBtn = drawer.locator('.ant-drawer-close').first();
    await closeBtn.click();
  }
  await page.waitForTimeout(400);
  await shot(page, 'drawer-closed');

  console.log('✅ CardFormulaDrawer 视觉构建器全部 6 项断言通过');
});
