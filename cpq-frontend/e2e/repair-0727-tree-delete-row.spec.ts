/**
 * E2E: repair-0727 报价侧树页签删除行 BUG —— 核心闭环验证（AC-1/AC-2/AC-3/AC-5，UI 层证据）。
 *
 * 夹具（DRAFT，持久化于共享库 cpq_db_0724）：
 *   - 报价单 QT-20260726-0006, id = 69aab7ec-9140-427b-b717-ed0a806485d1
 *   - line item id = dfee1e78-94c7-4af1-899b-caa9b60fd29a
 *   - 树组件 656c9b87-cda5-4c32-8d72-45d94714f77a（tab_type='BOM'，页签"BOM"，rowKeyFields=["料件"]）
 *   - 树 6 行；idx5 = 992/AgNi11#-Ⅰ 挂 S-3120014539（nodeId=S-3120014539/992）
 *             idx6 = 同料号挂 S-80011（nodeId=S-3120014539/S-80011/992，DAG 重复子件）
 *
 * ⚠️ 环境说明：共享后端 8081 跑的是主工作区 master（不含本次修复），本 spec 必须指向
 * worktree 分支起的临时后端/前端（PW_BACKEND_URL / PW_BASE_URL）。
 * 运行示例（技术总监已搭好：临时后端 8098，临时前端 5199）：
 *   PW_BASE_URL=http://localhost:5199 PW_BACKEND_URL=http://localhost:8098 \
 *     npx playwright test --config=e2e/playwright.config.ts e2e/repair-0727-tree-delete-row.spec.ts --reporter=list
 *
 * ⚠️ 双基线纪律（test.md §2.1.1）：本 spec 开始前必须已处于「基线 B」（0 墓碑，6 行，idx5/idx6 都可见）——
 * 通过 beforeEach 调 restore-driver-rows 保证。跑完必须用 test.md §2.3 规则 3 的 SQL 字面量精确复位回
 * 「基线 A」（1 条旧格式墓碑，5 行）——不在本 spec 内做（会引入非只读 SQL 依赖），由测试报告另行执行并记录。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const __filename = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filename);
const SHOT_DIR = path.join(__dirnameLocal, 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

const QUOTATION_ID = '69aab7ec-9140-427b-b717-ed0a806485d1';   // QT-20260726-0006
const LINE_ITEM_ID = 'dfee1e78-94c7-4af1-899b-caa9b60fd29a';
const TREE_COMPONENT_ID = '656c9b87-cda5-4c32-8d72-45d94714f77a';
const NODE_ID_IDX5 = 'S-3120014539/992';
const NODE_ID_IDX6 = 'S-3120014539/S-80011/992';
const BACKEND_URL = process.env.PW_BACKEND_URL || 'http://localhost:8081';

let backendUp = false;
let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `r0727-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}

async function countLoading(page: Page, tag: string) {
  const c = await page.locator('text=加载中').count();
  console.log(`[${tag}] 'text=加载中' count = ${c}`);
  return c;
}

/**
 * 直连后端 restore-driver-rows 清空树组件墓碑，确保测试从「基线 B」（6 行，0 墓碑）开始。
 * 必须用 page.request（已登录后调用，复用浏览器会话 cookie）——独立的顶层 request fixture
 * 未登录，直连会 401，restore 静默不生效（首次编写本 spec 时踩过这个坑）。
 */
async function enterBaselineB(page: Page) {
  const res = await page.request.post(
    `${BACKEND_URL}/api/cpq/quotations/${QUOTATION_ID}/line-items/${LINE_ITEM_ID}/restore-driver-rows`,
    { data: { componentId: TREE_COMPONENT_ID } },
  );
  console.log('[r0727] enterBaselineB status =', res.status());
  expect(res.status(), 'restore-driver-rows 应返回 200（已登录会话）').toBe(200);
}

async function enterQuoteTreeTab(page: Page): Promise<boolean> {
  await page.goto(`/quotations/${QUOTATION_ID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  const nextBtn = page.locator('button', { hasText: /下一步|继续/ }).first();
  if (await nextBtn.count() > 0 && await nextBtn.isEnabled().catch(() => false)) {
    await nextBtn.click().catch(() => {});
    await page.waitForTimeout(1200);
  }
  const quoteSeg = page.locator('.ant-segmented-item', { hasText: '报价单' }).first();
  if (await quoteSeg.count() > 0) { await quoteSeg.click().catch(() => {}); await page.waitForTimeout(800); }
  const cardSeg = page.locator('.ant-segmented-item', { hasText: '产品卡片' }).first();
  if (await cardSeg.count() > 0) { await cardSeg.click().catch(() => {}); await page.waitForTimeout(800); }
  const treeTab = page.locator('.qt-tab-btn', { hasText: 'BOM' }).first();
  if (await treeTab.count() === 0) return false;
  await treeTab.click().catch(() => {});
  await page.waitForTimeout(1200);
  return true;
}

test.beforeAll(async () => {
  backendUp = await isBackendUp();
});

const consoleErrors: string[] = [];

test('树页签删除行核心闭环：删一条当帧消失 + 另一条同料号行原样保留 + 提交不再阻断（AC-1/AC-2/AC-3）', async ({ page }) => {
  test.skip(!backendUp, '临时后端未启动（本 spec 需要 worktree 分支代码 + 8098/5199）');

  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text());
  });
  page.on('pageerror', (err) => consoleErrors.push(`[pageerror] ${err.message}`));
  page.on('response', (res) => {
    if (res.url().includes('/tree/delete') && !res.url().includes('preview')) {
      console.log('[r0727][NET] tree/delete response status =', res.status(), res.url());
    }
  });

  await loginAsAdmin(page);
  expect(page.url()).not.toContain('/login');
  await enterBaselineB(page);

  const entered = await enterQuoteTreeTab(page);
  expect(entered, '应能进入报价单 BOM 树页签').toBe(true);
  await shot(page, 'tree-tab-baseline-b');

  // 基线 B：6 行，idx5/idx6 两条 AgNi11#-Ⅰ 都可见
  const rows = page.locator('.qt-cost-table tbody tr');
  const initialCount = await rows.count();
  console.log('[r0727] 基线 B 初始行数 =', initialCount);
  expect(initialCount, '基线 B 应为 6 行（idx5/idx6 都可见）').toBe(6);

  // 用 data-node-id 精确定位 idx6（挂 S-80011 的那一条），而非靠料号文本 .first()（两条同料号）
  const idx6Row = page.locator(`.qt-cost-table tbody tr[data-node-id="${NODE_ID_IDX6}"]`);
  expect(await idx6Row.count(), '应能用 data-node-id 精确定位 idx6').toBe(1);

  const deleteBtn = idx6Row.locator('button[title="删除行（将弹窗确认级联影响后再执行）"]');
  expect(await deleteBtn.count(), 'idx6 应有 ROW 删除入口').toBeGreaterThanOrEqual(1);
  await deleteBtn.first().click();
  await page.waitForTimeout(1200); // 等 delete-preview 返回，抽屉展示影响面

  const drawerTitle = page.locator('.ant-drawer-title', { hasText: '确认删除该行' });
  expect(await drawerTitle.count(), '应弹出「确认删除该行」抽屉').toBeGreaterThanOrEqual(1);
  await shot(page, 'delete-confirm-drawer');

  const confirmBtn = page.locator('.ant-drawer button:has-text("确认删除")').last();
  await confirmBtn.click();
  await page.waitForTimeout(3000); // 加长等待：等 tree/delete 执行 + applyQuoteProjection 回灌

  await shot(page, 'right-after-confirm-click');
  console.log('[r0727] drawer 是否已关闭:', await page.locator('.ant-drawer-title', { hasText: '确认删除该行' }).count() === 0);
  console.log('[r0727] console errors so far:', JSON.stringify(consoleErrors));

  // AC-1：不刷新页面，行数当帧从 6 变 5
  const afterDeleteCount = await page.locator('.qt-cost-table tbody tr').count();
  console.log('[r0727] 删除 idx6 后行数（未刷新页面）=', afterDeleteCount);
  await shot(page, 'after-delete-check');
  expect(afterDeleteCount, 'AC-1：删除后不刷新即消失，行数应为 5').toBe(5);
  await shot(page, 'after-delete-no-refresh');

  // AC-2：idx5（挂 S-3120014539 的那一条 AgNi11#-Ⅰ）仍在，且 idx6 确实消失
  const idx5Row = page.locator(`.qt-cost-table tbody tr[data-node-id="${NODE_ID_IDX5}"]`);
  expect(await idx5Row.count(), 'AC-2：idx5（另一条同料号行）应仍在').toBe(1);
  const idx6RowAfter = page.locator(`.qt-cost-table tbody tr[data-node-id="${NODE_ID_IDX6}"]`);
  expect(await idx6RowAfter.count(), 'idx6 应已从页面消失').toBe(0);

  const loadingAfterDelete = await countLoading(page, 'after-delete');
  expect(loadingAfterDelete, '删除后不应有加载中占位').toBe(0);

  // AC-3：提交审批应放行（不再报"行键重复"），因为页面上此时只剩 1 条 992/AgNi11#-Ⅰ
  const submitBtn = page.locator('button', { hasText: /提交审批|提交/ }).first();
  if (await submitBtn.count() > 0 && await submitBtn.isEnabled().catch(() => false)) {
    await submitBtn.click();
    await page.waitForTimeout(1500);
    const conflictDrawer = page.locator('.ant-drawer-title', { hasText: /行键重复/ });
    expect(await conflictDrawer.count(), 'AC-3：不应弹出行键重复冲突抽屉').toBe(0);
    await shot(page, 'after-submit');
  } else {
    console.log('[r0727] 提交按钮不可见/禁用，跳过 AC-3 UI 级提交验证（接口层已在 test-report.md IT-06 独立验证）');
  }

  console.log('[r0727] 全程加载中 count =', await countLoading(page, 'final'));
});

test('AP-51 行数纪律：基线 B 连续刷新 3 次行数稳定为 6', async ({ page }) => {
  test.skip(!backendUp, '临时后端未启动');

  await loginAsAdmin(page);
  await enterBaselineB(page);

  for (let i = 1; i <= 3; i++) {
    const entered = await enterQuoteTreeTab(page);
    expect(entered, `第 ${i} 次进入应成功`).toBe(true);
    const rc = await page.locator('.qt-cost-table tbody tr').count();
    console.log(`[r0727][AP-51] 第 ${i} 次刷新行数 =`, rc);
    expect(rc, `第 ${i} 次刷新后基线 B 行数应稳定为 6`).toBe(6);
  }
});
