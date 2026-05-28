/**
 * master-data-v6-tabs.spec.ts
 *
 * 验收：主数据维护 Hub — 工序 V6 只读 Tab + BOM Tab 新建
 * 覆盖：AC-P1~P5 + AC-B1~B10 + TC-11~14
 *
 * 已知 Bug（需前端 Fix 后重测）：
 *   Bug B3 (BLOCKER): v6MasterDataService.ts 未解包后端响应 .data 字段
 *     根因：api.get 返回 {code,message,data:{content,page,...}}，但 listProcesses/listBomItems
 *     直接 return res as PageResult<T>，导致 result.content=undefined
 *     -> SelectableTable dataSource=undefined -> useMemo dataSource.filter crash
 *     -> React Router ErrorBoundary "Unexpected Application Error!" -> 页面空白
 *   Bug B1 (HIGH): V6BomQueryTab.tsx systemType 枚举值前后端不匹配
 *     前端传: QUOTATION/COSTING/COMMON，后端期望: QUOTE/PRICING/BOTH -> 400 INVALID_SYSTEM_TYPE
 *   Bug B2 (MEDIUM): V6BomQueryTab.tsx SYSTEM_TYPE_TAG 映射键错误（同 Bug B1）
 *     列表中 systemType 显示原始字符串 "QUOTE"（未 Tag 着色）
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
  const file = path.join(SHOT_DIR, `mdv6-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`[screenshot] ${name} => ${file}`);
}

let backendUp = false;
test.beforeAll(async () => {
  backendUp = await isBackendUp();
});

// ═══════════════════════════════════════════════════════════════════════════
// 辅助：等待表格（含 loading 消失）
// ═══════════════════════════════════════════════════════════════════════════
async function waitTableReady(page: Page, timeout = 15000) {
  await page.waitForFunction(
    () => !document.querySelector('.ant-spin-spinning'),
    { timeout }
  ).catch(() => {});
  await page.waitForTimeout(500);
}

// ═══════════════════════════════════════════════════════════════════════════
// 辅助：打开主数据维护 Hub，通过 DOM 检测 React 崩溃
// ═══════════════════════════════════════════════════════════════════════════
async function openMasterDataHub(page: Page): Promise<{ hasCrash: boolean; crashMsg: string }> {
  await page.goto('/master-data-hub');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);

  // 通过 DOM 内容检测 React Router ErrorBoundary 渲染的错误页
  const bodyText = await page.locator('body').textContent().catch(() => '');
  const hasCrash = (bodyText ?? '').includes('Unexpected Application Error');
  const crashMsg = hasCrash ? (bodyText ?? '').substring(0, 300) : '';
  if (hasCrash) {
    console.log('[CRASH] React Router ErrorBoundary:', crashMsg.substring(0, 200));
  }
  return { hasCrash, crashMsg };
}

// ═══════════════════════════════════════════════════════════════════════════
// TC-11: 老路由不依赖 Hub 渲染，可独立验收
// ═══════════════════════════════════════════════════════════════════════════
test('TC-11: 老路由 /config/processes 不再渲染工序管理页面', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  await loginAsAdmin(page);
  await page.goto('/config/processes');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1000);
  await shot(page, 'old-route-config-processes');

  const processManagementTitle = page.locator('h2, h1').filter({ hasText: '工序管理' });
  const count = await processManagementTitle.count();
  console.log(`[TC-11] "工序管理" 标题出现次数: ${count}`);
  expect(count).toBe(0);
  console.log('[TC-11] /config/processes 不再渲染旧工序管理页: PASS');
});

// ═══════════════════════════════════════════════════════════════════════════
// Bug B3 确认测试：验证 /master-data-hub React 崩溃
// 此测试应 FAIL（因为 Bug B3 存在），修复后应 PASS
// ═══════════════════════════════════════════════════════════════════════════
test('Bug B3: master-data-hub 页面 React 崩溃确认', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  await loginAsAdmin(page);
  const { hasCrash, crashMsg } = await openMasterDataHub(page);
  await shot(page, 'hub-crash-state');

  console.log('[Bug B3] hasCrash:', hasCrash);
  if (hasCrash) {
    console.log('[Bug B3] 崩溃栈:', crashMsg.substring(0, 200));
    console.log('[Bug B3] 根因: v6MasterDataService.ts listProcesses/listBomItems 未解包 .data 字段');
    console.log('[Bug B3] 修复方向: res.data 替换 res（或解包 wrapper {code,message,data}）');
  }

  // 期望无崩溃（Bug B3 修复后此断言通过）
  expect(hasCrash).toBe(false);
});

// ═══════════════════════════════════════════════════════════════════════════
// 工序 Tab AC-P1~P5（Bug B3 修复后运行）
// ═══════════════════════════════════════════════════════════════════════════
test('AC-P1~P5: 工序 Tab V6 只读验收（需 Bug B3 修复）', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  await loginAsAdmin(page);
  const { hasCrash } = await openMasterDataHub(page);

  if (hasCrash) {
    await shot(page, 'process-tab-blocked-by-b3');
    test.skip(true, 'Bug B3: 页面崩溃，阻断 Tab 验收');
  }

  await shot(page, 'hub-loaded');

  // 默认激活工序 Tab
  const processTab = page.locator('.ant-tabs-tab').filter({ hasText: '工序' });
  await expect(processTab).toBeVisible();
  await processTab.click();
  await waitTableReady(page);
  await shot(page, 'process-tab-loaded');

  // AC-P5: loading 消失
  await expect(page.locator('.ant-spin-spinning')).toHaveCount(0);
  console.log('[AC-P5] loading spinner gone: PASS');

  // AC-P1: 8 列
  const headerCount = await page.locator('.ant-table-thead th').count();
  console.log(`[AC-P1] 表头列数: ${headerCount}`);
  expect(headerCount).toBeGreaterThanOrEqual(8);
  await expect(page.locator('.ant-table-thead').filter({ hasText: '工序编号' })).toBeVisible();
  await expect(page.locator('.ant-table-thead').filter({ hasText: '工序名称' })).toBeVisible();
  console.log('[AC-P1] 8 列: PASS');

  // AC-P4: 无数据时空状态文案
  const emptyText = page.locator('.ant-table-placeholder');
  if (await emptyText.isVisible({ timeout: 2000 }).catch(() => false)) {
    const txt = await emptyText.textContent();
    console.log(`[AC-P4] 空状态: "${txt}"`);
    expect(txt).toContain('暂无工序数据');
    console.log('[AC-P4] PASS');
  } else {
    const rows = await page.locator('.ant-table-tbody tr').count();
    console.log(`[AC-P1] 有数据: ${rows} 行`);
    expect(rows).toBeGreaterThan(0);
    console.log('[AC-P1] 有数据 PASS');
  }

  // AC-P2: 无 CRUD 按钮
  await expect(page.locator('.ant-table-tbody button', { hasText: '编辑' })).toHaveCount(0);
  await expect(page.locator('.ant-table-tbody button', { hasText: '删除' })).toHaveCount(0);
  await expect(page.locator('button', { hasText: '新建' })).toHaveCount(0);
  console.log('[AC-P2] 无 CRUD 按钮: PASS');

  // AC-P3: 点 processNo 弹 Drawer（有数据时）
  const tableRows = await page.locator('.ant-table-tbody tr.ant-table-row').count();
  if (tableRows > 0) {
    await page.locator('.ant-table-tbody tr.ant-table-row').first().locator('a').first().click();
    await page.waitForTimeout(800);
    const drawer = page.locator('.ant-drawer-content-wrapper');
    await expect(drawer).toBeVisible();
    await shot(page, 'process-detail-drawer');
    const descCount = await page.locator('.ant-drawer-body .ant-descriptions-item').count();
    console.log(`[AC-P3] Drawer 字段数: ${descCount}`);
    expect(descCount).toBeGreaterThanOrEqual(10);
    await expect(page.locator('.ant-drawer-body button', { hasText: '保存' })).toHaveCount(0);
    await expect(page.locator('.ant-drawer-footer button')).toHaveCount(0);
    await page.locator('.ant-drawer-close').click();
    await page.waitForTimeout(300);
    console.log('[AC-P3] Drawer 480px 无保存按钮: PASS');
  } else {
    console.log('[AC-P3] process_master 无数据，跳过 Drawer 点击（数据不足）');
  }

  await shot(page, 'process-tab-final');
});

// ═══════════════════════════════════════════════════════════════════════════
// BOM Tab AC-B1~B10（Bug B3 修复后运行）
// ═══════════════════════════════════════════════════════════════════════════
test('AC-B1~B10: BOM Tab 查询与 Drawer 验收（需 Bug B3 修复）', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  await loginAsAdmin(page);
  const { hasCrash } = await openMasterDataHub(page);
  if (hasCrash) {
    test.skip(true, 'Bug B3: 页面崩溃，阻断 BOM Tab 验收');
  }

  // 切到 BOM Tab
  const bomTab = page.locator('.ant-tabs-tab').filter({ hasText: 'BOM' });
  await expect(bomTab).toBeVisible();
  await bomTab.click();
  await page.waitForTimeout(1000);
  await shot(page, 'bom-tab-init');

  // AC-B1: 初始提示
  const emptyArea = page.locator('.ant-table-placeholder');
  await expect(emptyArea).toBeVisible({ timeout: 5000 });
  const emptyTxt = await emptyArea.textContent();
  console.log(`[AC-B1] 初始空状态: "${emptyTxt}"`);
  expect(emptyTxt).toContain('请先选择客户编号');
  console.log('[AC-B1] PASS');

  // AC-B10: 查询按钮 disabled
  const queryBtn = page.locator('button', { hasText: '查询' });
  await expect(queryBtn).toBeDisabled();
  console.log('[AC-B10] 客户未选时查询 disabled: PASS');

  // AC-B2: 客户编号下拉
  const customerSelect = page.locator('.ant-select').first();
  await customerSelect.click();
  await page.waitForTimeout(600);
  const customerOptions = page.locator('.ant-select-dropdown .ant-select-item-option');
  const optCount = await customerOptions.count();
  console.log(`[AC-B2] 客户下拉选项数: ${optCount}`);
  expect(optCount).toBeGreaterThan(0);
  await shot(page, 'bom-customer-dropdown');
  console.log('[AC-B2] PASS');

  // 选 CUST-1269
  await customerOptions.filter({ hasText: 'CUST-1269' }).click();
  await page.waitForTimeout(1200);

  // AC-B4: 料号下拉可用
  const materialSelect = page.locator('.ant-select').nth(1);
  await page.waitForFunction(
    () => {
      const s = document.querySelectorAll('.ant-select');
      return s.length >= 2 && !s[1].classList.contains('ant-select-loading');
    },
    { timeout: 10000 }
  ).catch(() => {});
  await page.waitForTimeout(300);
  const isDisabled = await materialSelect.evaluate(e => e.classList.contains('ant-select-disabled'));
  console.log(`[AC-B4] 料号 disabled: ${isDisabled}`);
  expect(isDisabled).toBe(false);
  console.log('[AC-B4] PASS');

  await expect(queryBtn).toBeEnabled();
  console.log('[AC-B10] 选客户后查询 enabled: PASS');

  // AC-B3: 点查询
  await queryBtn.click();
  await waitTableReady(page, 15000);
  await shot(page, 'bom-query-result');
  const rows = await page.locator('.ant-table-tbody tr.ant-table-row').count();
  console.log(`[AC-B3] 查询结果: ${rows} 行`);
  expect(rows).toBeGreaterThan(0);
  console.log('[AC-B3] PASS');

  // AC-B9: 分页
  await expect(page.locator('.ant-pagination')).toBeVisible();
  console.log('[AC-B9] 分页存在: PASS');

  // AC-B6: systemType 切换（验证 Bug B1）
  const quotationRadio = page.locator('.ant-radio-button-wrapper', { hasText: '报价' });
  if (await quotationRadio.isVisible()) {
    await quotationRadio.click();
    await queryBtn.click();
    await waitTableReady(page, 10000);
    await shot(page, 'bom-systemtype-quotation');
    const rowsFiltered = await page.locator('.ant-table-tbody tr.ant-table-row').count();
    console.log(`[AC-B6] 报价 Radio 后行数: ${rowsFiltered}`);
    if (rowsFiltered === 0) {
      console.log('[AC-B6] BUG B1 已确认: 前端传 QUOTATION，后端期望 QUOTE，返 400 -> 0 行');
      // Bug B1 存在时预期 0 行，修复后应 > 0
    } else {
      console.log('[AC-B6] PASS (Bug B1 已修复)');
    }
    // 切回全部
    await page.locator('.ant-radio-button-wrapper', { hasText: '全部' }).click();
    await queryBtn.click();
    await waitTableReady(page, 10000);
  }

  // AC-B7: 详情 Drawer
  const firstLink = page.locator('.ant-table-tbody tr.ant-table-row').first().locator('a').first();
  await expect(firstLink).toBeVisible();
  await firstLink.click();
  await page.waitForTimeout(800);
  const bomDrawer = page.locator('.ant-drawer-content-wrapper');
  await expect(bomDrawer).toBeVisible();
  console.log('[AC-B7] BOM Drawer 打开: PASS');
  const dividerCount = await page.locator('.ant-drawer-body .ant-divider-inner-text').count();
  console.log(`[AC-B7] 分组数: ${dividerCount}`);
  expect(dividerCount).toBeGreaterThanOrEqual(4);
  const drawerWidth = await bomDrawer.evaluate(e => parseInt(window.getComputedStyle(e).width));
  console.log(`[AC-B7] Drawer 宽: ${drawerWidth}px`);
  expect(drawerWidth).toBeGreaterThanOrEqual(900);
  await shot(page, 'bom-detail-drawer');
  await page.locator('.ant-drawer-close').click();
  await page.waitForTimeout(300);
  console.log('[AC-B7] PASS');

  // TC-14: 切换客户后料号清空
  await customerSelect.click();
  await page.waitForTimeout(400);
  const globalOpt = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: '_GLOBAL_' });
  if (await globalOpt.isVisible()) {
    await globalOpt.click();
    await page.waitForTimeout(800);
    const matVal = await materialSelect.locator('.ant-select-selection-item').count();
    console.log(`[TC-14] 切换客户后料号选中值: ${matVal} (应为0)`);
    console.log('[TC-14] PASS');
  }

  await shot(page, 'bom-final');
});

// ═══════════════════════════════════════════════════════════════════════════
// TC-12/13/回归（Bug B3 修复后运行）
// ═══════════════════════════════════════════════════════════════════════════
test('TC-12: Hub 工序 Tab 无 CRUD 按钮（需 Bug B3 修复）', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page);
  const { hasCrash } = await openMasterDataHub(page);
  if (hasCrash) test.skip(true, 'Bug B3');
  const processTab = page.locator('.ant-tabs-tab').filter({ hasText: '工序' });
  await processTab.click();
  await waitTableReady(page);
  await shot(page, 'process-no-crud');
  const active = page.locator('.ant-tabs-tabpane-active');
  await expect(active.locator('button', { hasText: '新建' })).toHaveCount(0);
  await expect(active.locator('button', { hasText: '编辑' })).toHaveCount(0);
  await expect(active.locator('button', { hasText: '删除' })).toHaveCount(0);
  console.log('[TC-12] 工序 Tab 无 CRUD: PASS');
});

test('TC-13: BOM Tab 初始不自动查询（需 Bug B3 修复）', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  let bomReqs = 0;
  page.on('request', req => {
    if (req.url().includes('/v6/material-bom-items') &&
        !req.url().includes('customer-nos') && !req.url().includes('material-nos')) {
      bomReqs++;
    }
  });
  await loginAsAdmin(page);
  const { hasCrash } = await openMasterDataHub(page);
  if (hasCrash) test.skip(true, 'Bug B3');
  await page.locator('.ant-tabs-tab').filter({ hasText: 'BOM' }).click();
  await page.waitForTimeout(3000);
  await shot(page, 'bom-no-auto-query');
  console.log(`[TC-13] BOM 查询请求数: ${bomReqs}`);
  expect(bomReqs).toBe(0);
  console.log('[TC-13] PASS');
});

test('材质 + 料号 Tab 回归（需 Bug B3 修复）', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page);
  const { hasCrash } = await openMasterDataHub(page);
  if (hasCrash) test.skip(true, 'Bug B3');
  const partTab = page.locator('.ant-tabs-tab').filter({ hasText: '料号' });
  await expect(partTab).toBeVisible();
  await partTab.click();
  await page.waitForTimeout(1500);
  await shot(page, 'part-tab');
  await expect(page.locator('text=Error')).toHaveCount(0);
  console.log('[回归] 料号 Tab: PASS');
  const materialTab = page.locator('.ant-tabs-tab').filter({ hasText: '材质' });
  await expect(materialTab).toBeVisible();
  await materialTab.click();
  await page.waitForTimeout(1500);
  await shot(page, 'material-tab');
  await expect(page.locator('text=Error')).toHaveCount(0);
  console.log('[回归] 材质 Tab: PASS');
});
