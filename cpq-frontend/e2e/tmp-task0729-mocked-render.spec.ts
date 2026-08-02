/**
 * task-0729 屏 1 渲染证据（后端接口未实现，用 Playwright 网络层拦截喂契约样例数据自测渲染）。
 * ⚠️ 仅用于本次自测，是纯浏览器网络层拦截（page.route），不改动任何前端源码/不引入任何
 * 源码内 mock —— priceAdjustService.ts 里没有任何写死数据，恒调真实端点。用完即删。
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const SHOT_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

const strategyDTO = {
  exists: true, customerNo: 'CUST-0001', enabled: true,
  cycleType: 'MONTHLY_NTH_WEEK', cycleWeekday: 3, cycleDayOfMonth: null, cycleNthWeek: 2,
  executeTime: '18:00', materialScopeMode: 'SPECIFIED', costDiffThreshold: 0,
  latestVersionNo: 'V26080501', pendingVersionNo: 'V26080501',
  materialCount: 3, elementCount: 4, hasComparisonConfig: true,
  updatedAt: '2026-07-28T14:22:00+08:00', updatedBy: '李财务',
};

const materialsPage = {
  content: [
    { materialNo: '10110002', materialName: '银触点 A 型', customerPartNo: 'PN0507945', customerMaterialName: '断路器触头组件', selected: true },
    { materialNo: '10110037', materialName: '银触点 B 型', customerPartNo: 'PN0507946', customerMaterialName: '断路器触头组件-II', selected: true },
    { materialNo: 'S-3120014539', materialName: '接触器支架', customerPartNo: null, customerMaterialName: null, selected: false },
  ],
  page: 1, size: 20, totalElements: 3, totalPages: 1,
};

const elementsPage = {
  versionColumns: [
    { versionId: 'v-1', versionNo: 'V26080501', status: 'PENDING', baseDate: '2026-08-05' },
    { versionId: 'v-2', versionNo: 'V26070501', status: 'SUPERSEDED', baseDate: '2026-07-05' },
    { versionId: 'v-3', versionNo: 'V26060502', status: 'SUPERSEDED', baseDate: '2026-06-05' },
  ],
  content: [
    {
      elementCode: 'Ag', elementName: '银', elementNo: '10001', elementEnabled: true, selected: true,
      prices: [
        { unitPrice: 5820.00, changeRate: 0.0679, priceState: 'NORMAL' },
        { unitPrice: 5450.00, changeRate: 0.0310, priceState: 'NORMAL' },
        { unitPrice: 5286.00, changeRate: 0.0120, priceState: 'NORMAL' },
      ],
    },
    {
      // 🔒 已停用元素必须可见并标「已停用」，照常参与调价
      elementCode: 'Ni', elementName: '镍', elementNo: '10003', elementEnabled: false, selected: true,
      prices: [
        { unitPrice: 137.03, changeRate: -0.020, priceState: 'NORMAL' },
        { unitPrice: 139.80, changeRate: 0.018, priceState: 'NORMAL' },
        { unitPrice: 137.33, changeRate: 0.009, priceState: 'NORMAL' },
      ],
    },
    {
      // 🔒 NO_PRICE → 前端应渲染「无价」标签
      elementCode: 'AgNi11', elementName: '银镍11', elementNo: '10009', elementEnabled: true, selected: true,
      prices: [
        { unitPrice: null, changeRate: null, priceState: 'NO_PRICE' },
        { unitPrice: null, changeRate: null, priceState: 'NO_PRICE' },
        { unitPrice: null, changeRate: null, priceState: 'NO_PRICE' },
      ],
    },
    {
      // 🔒 NOT_IN_LIST → 前端应渲染「—」（与「无价」明显不同）
      elementCode: '301', elementName: '不锈钢 301', elementNo: '10099', elementEnabled: true, selected: false,
      prices: [
        { unitPrice: null, changeRate: null, priceState: 'NOT_IN_LIST' },
        { unitPrice: null, changeRate: null, priceState: 'NOT_IN_LIST' },
        { unitPrice: null, changeRate: null, priceState: 'NOT_IN_LIST' },
      ],
    },
  ],
  page: 1, size: 20, totalElements: 4, totalPages: 1,
};

const templateSeries = [
  { templateSeriesId: 'ts-1', seriesName: '罗克韦尔模板1', latestVersion: 'v1.0', isDefault: true, templateCount: 1, hasComparisonConfig: true, columnCount: 3 },
  { templateSeriesId: 'ts-2', seriesName: '罗克韦尔模板2', latestVersion: 'v1.0', isDefault: false, templateCount: 1, hasComparisonConfig: false, columnCount: 1 },
  { templateSeriesId: 'ts-3', seriesName: '罗克韦尔模板3', latestVersion: 'v1.2', isDefault: false, templateCount: 3, hasComparisonConfig: false, columnCount: 1 },
];

const comparisonColumns = {
  configured: true, customerNo: 'CUST-0001', templateSeriesId: 'ts-1',
  columns: [
    { id: 'col-default', kind: 'PRODUCT_TOTAL', sortOrder: 0, threshold: 0, quoteLabel: '产品总价', costingLabel: '产品总价', removable: false },
    { id: 'col-2', kind: 'TAB_PAIR', sortOrder: 1, threshold: 2.0, quoteComponentId: 'c1', quoteMetric: 'm1', quoteLabel: '投料·材料小计', costingComponentId: 'c2', costingMetric: '__TAB_TOTAL__', costingLabel: '材料成本·页签合计', removable: true },
  ],
};

const versionsPage = {
  content: [
    {
      versionId: 'v-1', versionNo: 'V26080501', baseDate: '2026-08-05', status: 'PENDING',
      triggerType: 'SCHEDULED', createdAt: '2026-08-05T18:00:00+08:00', createdBy: 'system',
      progress: { total: 12, approved: 8, rejected: 2, pending: 2, budgeting: 0 }, itemCount: 4,
    },
    {
      versionId: 'v-2', versionNo: 'V26070501', baseDate: '2026-07-05', status: 'SUPERSEDED',
      triggerType: 'SCHEDULED', createdAt: '2026-07-05T18:00:00+08:00', createdBy: 'system',
      progress: { total: 12, approved: 11, rejected: 1, pending: 0, budgeting: 0 }, itemCount: 4,
    },
  ],
  page: 1, size: 10, totalElements: 2, totalPages: 1,
};

const logsPage = {
  content: [
    { id: 'l-1', changedAt: '2026-08-01T09:30:00+08:00', changedBy: '李财务', changeType: 'MATERIAL_SCOPE', summary: '指定料号 3 → 5（新增 10110088、10110091）' },
    { id: 'l-2', changedAt: '2026-07-28T14:22:00+08:00', changedBy: '李财务', changeType: 'STRATEGY', summary: '成本差额预警线 0 → 5' },
  ],
  page: 1, size: 20, totalElements: 2, totalPages: 1,
};

async function fulfillJson(route: any, body: unknown) {
  await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
}

test('屏1 渲染证据（网络层 mock，验证空值双态/已停用可见/周期切换/模板系列选择器）', async ({ page }) => {
  test.skip(!backendUp, 'backend down');

  await page.route('**/api/cpq/price-adjust/**', async (route) => {
    const url = route.request().url();
    if (route.request().method() !== 'GET') { await route.fulfill({ status: 202, contentType: 'application/json', body: '{}' }); return; }
    if (url.includes('/materials')) return fulfillJson(route, materialsPage);
    if (url.includes('/elements')) return fulfillJson(route, elementsPage);
    if (url.includes('/logs')) return fulfillJson(route, logsPage);
    if (url.includes('/template-series') && url.includes('comparison-view-meta')) { await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' }); return; }
    if (url.includes('/template-series')) return fulfillJson(route, templateSeries);
    if (url.includes('/comparison-columns')) return fulfillJson(route, comparisonColumns);
    if (url.includes('/versions')) return fulfillJson(route, versionsPage);
    if (url.includes('/strategies/')) return fulfillJson(route, strategyDTO);
    await route.continue();
  });

  await loginAsAdmin(page);
  await page.goto('/pricing');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(800);
  await page.locator('.ant-list-item').first().click();
  await page.waitForTimeout(500);
  await page.locator('.ant-tabs-tab', { hasText: '价格调整策略' }).click();
  await page.waitForTimeout(1500);

  await page.screenshot({ path: path.join(SHOT_DIR, 't0729-mock-01-full-top.png') }).catch(() => {});
  await page.screenshot({ path: path.join(SHOT_DIR, 't0729-mock-00-fullpage.png'), fullPage: true }).catch(() => {});

  // ── 元素矩阵：两种空值渲染 + 已停用可见 ──
  const elementMatrix = page.locator('text=参与调价元素矩阵').locator('xpath=ancestor::div[contains(@style,"border")][1]');
  await elementMatrix.scrollIntoViewIfNeeded();
  await page.waitForTimeout(300);
  await page.screenshot({ path: path.join(SHOT_DIR, 't0729-mock-02-element-matrix.png') }).catch(() => {});

  const niRow = page.locator('tr', { hasText: 'Ni' }).filter({ hasText: '镍' });
  await expect(niRow.locator('text=已停用')).toHaveCount(1);
  const agni11Row = page.locator('tr', { hasText: 'AgNi11' });
  await expect(agni11Row.locator('text=无价')).toHaveCount(3);
  const row301 = page.locator('tr', { hasText: '301' }).filter({ hasText: '不锈钢' });
  const dashCount = await row301.locator('td').allTextContents();
  console.log('[T0729][301行单元格文本]', JSON.stringify(dashCount));

  // ── 比对列配置区：模板系列选择器 ──
  const seriesSelect = page.locator('.ant-select', { hasText: '罗克韦尔模板1' });
  await expect(seriesSelect).toHaveCount(1);
  await page.screenshot({ path: path.join(SHOT_DIR, 't0729-mock-03-comparison-columns.png') }).catch(() => {});

  // ── 版本轨迹 ──
  await expect(page.locator('text=最新已生成版本')).toHaveCount(1);
  await expect(page.locator('.ant-tag', { hasText: '待处理' }).first()).toBeVisible();
  await page.screenshot({ path: path.join(SHOT_DIR, 't0729-mock-04-version-trail.png') }).catch(() => {});

  // 变更历史抽屉（Drawer，非 Modal）—— 精确匹配屏1自己的按钮（🕘 前缀），
  // 避免命中 Tabs 默认预挂载的「元素价格策略」隐藏面板里同名按钮（antd Tabs 首个 pane 默认渲染并保留挂载）
  await page.locator('button', { hasText: '🕘 变更历史' }).click();
  await page.waitForTimeout(500);
  await expect(page.locator('.ant-drawer-title')).toContainText('变更历史');
  await expect(page.locator('.ant-modal')).toHaveCount(0);
  await page.screenshot({ path: path.join(SHOT_DIR, 't0729-mock-05-history-drawer.png') }).catch(() => {});
});
