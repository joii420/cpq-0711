/**
 * dataset-quote-and-perms.spec.ts
 *
 * task-260902 · L4 E2E 第二组
 * 覆盖 AC-35（报价单管理工具栏）/ AC-38（无报价维护页签）/ AC-31（权限：可见但禁用）/ AC-41（并发冲突 UI 半边）。
 *
 * 判据来源：需求文档.md ④ F 组 + E 组；原型图/报价单管理-工具栏.html；原型图/核价数据-保存冲突.html。
 * 🚫 不读实现源码。
 */

import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { loginAsAdmin, loginAs, isBackendUp } from './fixtures/auth';

const __filenameLocal = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filenameLocal);

const SHOT_DIR = path.resolve(
  __dirnameLocal,
  '../../dev-docs/task-260902-报价与核价建表与导入方案新规范/证据/e2e'
);
fs.mkdirSync(SHOT_DIR, { recursive: true });

let shotIdx = 100;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `${++shotIdx}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`[screenshot] ${name} => ${file}`);
}

const AXIS_BASIC = 'TEST-DS-3120014539';

/** AC-35 原文点名的工具栏顺序。 */
const TOOLBAR = ['导入历史', '从基础数据导入', '导入报价数据', '新建报价单'];

let backendUp = false;
test.beforeAll(async () => {
  backendUp = await isBackendUp();
});
test.beforeEach(async () => {
  test.skip(!backendUp, '后端未启动 —— 记为「未验证」，不是通过');
});

// ═══════════════════════════════════════════════════════════════════
// AC-35 报价单管理工具栏
// ═══════════════════════════════════════════════════════════════════

test('TQ-01 / AC-35：工具栏依次为 导入历史/从基础数据导入/导入报价数据/新建报价单；旧按钮行为不变', async ({ page }) => {
  await loginAsAdmin(page);
  await page.goto('/quotations');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1200);

  const texts = (await page.locator('button').allTextContents())
    .map((t) => t.replace(/\s+/g, ''))
    .filter((t) => TOOLBAR.some((n) => t.includes(n.replace(/\s+/g, ''))));
  console.log('[TQ-01] 命中的工具栏按钮:', texts);

  for (const name of TOOLBAR) {
    await expect(
      page.locator('button', { hasText: new RegExp(name.split('').join('\\s*')) }).first(),
      `AC-35：工具栏缺按钮「${name}」（原型图/报价单管理-工具栏.html）`
    ).toBeVisible({ timeout: 10_000 });
  }

  // 顺序判据：按 DOM 出现次序比对
  const order: string[] = [];
  const all = await page.locator('button').allTextContents();
  for (const raw of all) {
    const t = raw.replace(/\s+/g, '');
    const hit = TOOLBAR.find((n) => t === n.replace(/\s+/g, '') || t.endsWith(n.replace(/\s+/g, '')));
    if (hit && !order.includes(hit)) order.push(hit);
  }
  expect(order, `AC-35：工具栏顺序应为 ${JSON.stringify(TOOLBAR)}，实际 ${JSON.stringify(order)}`)
    .toEqual(TOOLBAR);
  await shot(page, 'ac35-toolbar');

  // 旧按钮行为不变：点「从基础数据导入」打开的仍是原有抽屉
  await page.locator('button', { hasText: /从\s*基础数据导入/ }).first().click();
  const drawer = page.locator('.ant-drawer-content');
  await expect(drawer, 'AC-35：「从基础数据导入」没打开抽屉 ⇒ 旧行为被破坏（AC-43 双轨约束）')
    .toBeVisible({ timeout: 10_000 });
  const title = (await drawer.locator('.ant-drawer-title').first().textContent()) ?? '';
  console.log('[TQ-01] 旧抽屉标题:', title);
  expect(title, 'AC-35：「从基础数据导入」打开的抽屉标题不该是新的「导入报价数据」')
    .not.toContain('导入报价数据');
  await shot(page, 'ac35-legacy-drawer');
});

// ═══════════════════════════════════════════════════════════════════
// AC-38 本期不做报价维护页签
// ═══════════════════════════════════════════════════════════════════

test('TQ-04 / AC-38：主数据维护里不存在「报价数据」页签', async ({ page }) => {
  await loginAsAdmin(page);
  await page.goto('/master-data-hub');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1200);

  const tabs = (await page.locator('.ant-tabs > .ant-tabs-nav .ant-tabs-tab').allTextContents())
    .map((t) => t.trim());
  console.log('[TQ-04] 页签:', tabs);
  expect(tabs.length, 'AC-38 前置：一个页签都没读到 ⇒ 「不存在」是空验证').toBeGreaterThan(0);
  expect(tabs, 'AC-38：本期明确不做报价维护页签，却出现了「报价数据」')
    .not.toContain('报价数据');
  await shot(page, 'ac38-no-quote-tab');
});

// ═══════════════════════════════════════════════════════════════════
// AC-31 权限：可见但禁用
// ═══════════════════════════════════════════════════════════════════

test('TE-08 UI 半边 / AC-31：非核价管理员登录 → 保存/新增行/导入核价数据 可见但禁用，hover 提示「需要核价管理员权限」', async ({ page }) => {
  const user = process.env.PW_NON_PRICING_USER;
  const pass = process.env.PW_NON_PRICING_PASS ?? 'Admin@2026';

  // 🚨 不许拿 admin 冒充「非核价管理员」，那样断言必然反向假绿。
  //    实测 alice / bob（test0806_alice / test0806_bob）在 cpq_db_0724 里是 INACTIVE，
  //    登录不上 ⇒ 无合适账号时明确 skip 并记为「未验证」，🚫 不许改用户状态来凑（testing.md §4.3 全局状态纪律）。
  test.skip(!user,
    'AC-31 未验证：库中没有可用的「非 PRICING_MANAGER/SYSTEM_ADMIN 且 ACTIVE」账号。'
    + '需主线提供后设 PW_NON_PRICING_USER 环境变量重跑。'
    + '🚫 本用例不会自行创建/启用账号 —— 那是改共享库全局状态。');

  await loginAs(page, user!, pass);
  await page.goto('/master-data-hub');
  await page.waitForLoadState('networkidle');
  await page.locator('.ant-tabs-tab', { hasText: '基础核价' }).first().click();
  await page.waitForTimeout(1200);

  const importBtn = page.locator('button', { hasText: /导入核价数据/ }).first();
  await expect(importBtn, 'AC-31：导入按钮应「可见但禁用」，不是隐藏').toBeVisible();
  await expect(importBtn, 'AC-31：导入按钮应为禁用态').toBeDisabled();

  await importBtn.hover({ force: true }).catch(() => {});
  await page.waitForTimeout(600);
  const tip = (await page.locator('.ant-tooltip-inner').first().textContent().catch(() => '')) ?? '';
  expect(tip, 'AC-31：hover 提示应为「需要核价管理员权限」').toContain('需要核价管理员权限');

  const row = page.locator('.ant-table-row').first();
  await expect(row, 'AC-31：列表为空 ⇒ 抽屉内按钮断言会空跑').toBeVisible({ timeout: 15_000 });
  await row.click();
  const drawer = page.locator('.ant-drawer-content');
  await expect(drawer).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(800);

  for (const [label, re] of [['保存', /保\s*存/], ['新增行', /新\s*增\s*行/]] as const) {
    const btn = drawer.locator('button', { hasText: re }).first();
    await expect(btn, `AC-31：「${label}」应可见`).toBeVisible();
    await expect(btn, `AC-31：「${label}」应禁用`).toBeDisabled();
  }
  await shot(page, 'ac31-permission-disabled');
});

// ═══════════════════════════════════════════════════════════════════
// AC-41 并发冲突（UI 半边：两个标签页）
// ═══════════════════════════════════════════════════════════════════

test('TB-03 UI 半边 / AC-41：两个标签页同料号同 tab，A 先存成功后 B 存 → B 收到冲突提示且未写入', async ({ browser }) => {
  const ctx = await browser.newContext();
  const pageA = await ctx.newPage();
  const pageB = await ctx.newPage();

  try {
    await loginAsAdmin(pageA);

    const open = async (p: Page) => {
      await p.goto('/master-data-hub');
      await p.waitForLoadState('networkidle');
      await p.locator('.ant-tabs-tab', { hasText: '基础核价' }).first().click();
      await p.waitForTimeout(1000);
      const search = p.locator('.ant-input-search input, input[placeholder*="搜索"]').first();
      await search.fill(AXIS_BASIC);
      await search.press('Enter');
      await p.waitForTimeout(1200);
      const row = p.locator('.ant-table-row', { hasText: AXIS_BASIC }).first();
      await expect(row, `AC-41 前置：找不到 ${AXIS_BASIC}`).toBeVisible({ timeout: 15_000 });
      await row.click();
      await expect(p.locator('.ant-drawer-content')).toBeVisible({ timeout: 10_000 });
      await p.locator('.ant-drawer-content .ant-tabs-tab', { hasText: '物料BOM' }).first().click();
      await p.waitForTimeout(800);
    };

    // 🚨 两边必须在 A 保存之前都已加载，否则 B 拿到的是新版本号，冲突根本不会发生（断言空跑）
    await open(pageA);
    await open(pageB);

    const versionOf = async (p: Page) => {
      const t = (await p.locator('.ant-drawer-content').textContent()) ?? '';
      const m = t.match(/v(\d+)\s*（当前）/);
      expect(m, 'AC-41：读不到当前版本号').not.toBeNull();
      return Number(m![1]);
    };
    const vA = await versionOf(pageA);
    const vB = await versionOf(pageB);
    expect(vA, 'AC-41 前置：两个标签页的基线版本应相同').toBe(vB);

    const editAndSave = async (p: Page, value: string) => {
      const input = p
        .locator('.ant-drawer-content .ant-table-tbody tr')
        .first()
        .locator('.ant-input-number-input, input')
        .first();
      await input.fill(value);
      await input.blur();
      await p.locator('.ant-drawer-content button', { hasText: /保\s*存/ }).first().click();
    };

    await editAndSave(pageA, '5');
    await expect(pageA.locator('.ant-message-notice-content', { hasText: /已升版至 v\d+/ }).first())
      .toBeVisible({ timeout: 10_000 });
    await pageA.waitForTimeout(1500);
    const newV = await versionOf(pageA);
    expect(newV, 'AC-41 前置：A 保存后版本应 +1').toBe(vA + 1);

    // B 仍拿旧 baseVersion
    await editAndSave(pageB, '9');
    const conflict = pageB.locator(`text=数据已被他人更新至 v${newV}，请刷新后重试`).first();
    await expect(conflict, `AC-41：B 应收到「数据已被他人更新至 v${newV}，请刷新后重试」`)
      .toBeVisible({ timeout: 15_000 });
    await shot(pageB, 'ac41-conflict');

    // B 的提交未写入：库中版本仍是 A 写的那个
    const resp = await pageB.request.get(
      `/api/cpq/dataset/cost-basic/parts/${encodeURIComponent(AXIS_BASIC)}/overview`
    );
    expect(resp.status(), 'AC-41：overview 接口不可用，「未写入」无从验证').toBe(200);
    const sheets = (await resp.json())?.data?.sheets ?? [];
    const bom = sheets.find((s: any) => s.sheetKey && String(s.sheetKey).includes('MATERIAL_BOM'));
    expect(bom, 'AC-41：overview 里找不到物料BOM ⇒ 断言空跑').toBeTruthy();
    expect(bom.versionNo, `AC-41：B 冲突后版本号应仍为 ${newV}`).toBe(newV);
  } finally {
    await ctx.close();
  }
});
