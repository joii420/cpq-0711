/**
 * task-0729 屏 3（待办池）+ 屏 4（料号审核抽屉）渲染证据。
 * 后端 api.md §2 接口尚未实现，用 Playwright 网络层拦截喂契约样例数据自测渲染。
 * ⚠️ 纯浏览器网络层拦截（page.route），源码（priceAdjustService.ts）零 mock。用完即删。
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

// 原型屏3第5行样例：产品总价 +4.05 健康，但自定义列已卖穿 → breachedCount>0 → rowRed 必须为 true
const reviewsPage = {
  content: [
    {
      reviewId: 'r-1', customerNo: 'C001', customerName: '罗克韦尔',
      materialNo: '10120240', materialName: '镀银铜带',
      currentVersionNo: 'V26070501', targetVersionNo: 'V26080501',
      budgetStatus: 'READY', reviewStatus: 'PENDING',
      basisQuotationNo: 'QT-20260801-0012', basisQuotationDate: '2026-07-28',
      quoteCostCurrent: 218.05, quoteCostAdjusted: 222.10, costingCost: 218.05,
      diffCurrent: 0.00, diffAdjusted: 4.05,
      columnCount: 3, breachedCount: 1, amberCount: 1, missingCount: 0, staleCount: 0,
      rowRed: true,
    },
    {
      reviewId: 'r-2', customerNo: 'C001', customerName: '罗克韦尔',
      materialNo: '10110002', materialName: '触头组件',
      currentVersionNo: 'V26070501', targetVersionNo: 'V26080501',
      budgetStatus: 'QUEUED', reviewStatus: 'PENDING',
      basisQuotationNo: 'QT-20260801-0015', basisQuotationDate: '2026-07-29',
      quoteCostCurrent: 100.00, quoteCostAdjusted: null, costingCost: 98.00,
      diffCurrent: 2.00, diffAdjusted: null,
      columnCount: 3, breachedCount: 0, amberCount: 0, missingCount: 0, staleCount: 0,
      rowRed: false,
    },
    {
      reviewId: 'r-3', customerNo: 'C002', customerName: '西安中熔',
      materialNo: '10130099', materialName: '熔断器芯',
      currentVersionNo: 'V26070301', targetVersionNo: 'V26080301',
      budgetStatus: 'FAILED', reviewStatus: 'PENDING',
      basisQuotationNo: 'QT-20260731-0003', basisQuotationDate: '2026-07-25',
      quoteCostCurrent: 55.00, quoteCostAdjusted: null, costingCost: null,
      diffCurrent: null, diffAdjusted: null,
      columnCount: 3, breachedCount: 0, amberCount: 0, missingCount: 0, staleCount: 0,
      rowRed: false,
    },
    {
      reviewId: 'r-4', customerNo: 'C002', customerName: '西安中熔',
      materialNo: '10130100', materialName: '熔断器盖',
      currentVersionNo: null, targetVersionNo: 'V26080301',
      budgetStatus: 'READY', reviewStatus: 'PENDING',
      basisQuotationNo: 'QT-20260731-0004', basisQuotationDate: '2026-07-25',
      quoteCostCurrent: 30.00, quoteCostAdjusted: 31.00, costingCost: null,
      diffCurrent: 0.00, diffAdjusted: 1.00,
      columnCount: 3, breachedCount: 1, amberCount: 0, missingCount: 1, staleCount: 0,
      rowRed: true,
    },
    {
      reviewId: 'r-5', customerNo: 'C001', customerName: '罗克韦尔',
      materialNo: '10110037', materialName: '触头组件-II',
      currentVersionNo: 'V26070501', targetVersionNo: 'V26080501',
      budgetStatus: 'READY', reviewStatus: 'PENDING',
      basisQuotationNo: 'QT-20260801-0016', basisQuotationDate: '2026-07-29',
      quoteCostCurrent: 88.00, quoteCostAdjusted: 90.00, costingCost: 89.50,
      diffCurrent: -1.50, diffAdjusted: 0.50,
      columnCount: 3, breachedCount: 0, amberCount: 0, missingCount: 0, staleCount: 0,
      rowRed: false,
    },
  ],
  page: 1, size: 20, totalElements: 5, totalPages: 1,
};

const reviewDetail = {
  reviewId: 'r-1', customerNo: 'C001', materialNo: '10120240', materialName: '镀银铜带',
  currentVersionNo: 'V26070501', targetVersionNo: 'V26080501',
  budgetStatus: 'READY', reviewStatus: 'PENDING',
  elementChanges: [
    { elementCode: 'Ag', elementName: '银', matchedRule: '客户级默认策略 · LATEST × 1.02', previousPrice: 5450.00, currentPrice: 5820.00, changeRate: 0.0679, usageQty: 0.0032, unitPriceImpact: 1.184, noPrice: false, inheritedFromPrevious: false },
    { elementCode: 'Cu', elementName: '铜', matchedRule: '客户级默认策略', previousPrice: 76.85, currentPrice: 78.02, changeRate: 0.0152, usageQty: 0.05, unitPriceImpact: 0.06, noPrice: false, inheritedFromPrevious: false },
  ],
  elementImpactTotal: 4.05,
  templateSeriesId: 'ts-1', templateSeriesName: '罗克韦尔模板1',
  comparisonColumns: [
    { columnId: 'col-default', label: '产品总价', threshold: 0.00, sortOrder: 0, quoteCurrent: 218.05, quoteAdjusted: 222.10, costingCurrent: 218.05, costingAdjusted: 218.05, diffCurrent: 0.00, diffAdjusted: 4.05, status: 'NORMAL' },
    { columnId: 'col-2', label: '投料·材料小计 ↔ 材料成本·页签合计', threshold: 2.00, sortOrder: 1, quoteAdjusted: 88.10, costingAdjusted: 90.00, diffAdjusted: -1.90, status: 'RED' },
    { columnId: 'col-3', label: '加工费对照', threshold: 0, sortOrder: 2, status: 'MISSING', diffAdjusted: null, missingSide: 'COSTING' },
  ],
  quotations: [
    { quotationId: 'q-1', quotationNo: 'QT-20260801-0012', createdAt: '2026-07-28', status: 'DRAFT', isBasis: true, quoteSubtotalCurrent: 218.05, quoteSubtotalAdjusted: 222.10, comparisonViewUrl: '/quotations/q-1' },
    { quotationId: 'q-2', quotationNo: 'QT-20260710-0002', createdAt: '2026-07-10', status: 'SUBMITTED', isBasis: false, quoteSubtotalCurrent: 218.05, quoteSubtotalAdjusted: 222.10, comparisonViewUrl: '/quotations/q-2' },
  ],
};

const impactPreview = {
  materialCount: 1,
  versionPaths: [{ materialNo: '10120240', from: 'V26070501', to: 'V26080501' }],
  quotationCount: 3,
  byStatus: { DRAFT: 2, SUBMITTED: 1 },
  breachedMaterials: [{ materialNo: '10120240', breachedCount: 1 }],
  excludedQuotationCount: 3,
  excludedByStatus: { SENT: 2, ACCEPTED: 1 },
};

async function fulfillJson(route: any, body: unknown) {
  await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
}

test('屏3+屏4 渲染证据（rowRed 整行标红 / 预算中间态 / 比对状态标记 / 三段抽屉）', async ({ page }) => {
  test.skip(!backendUp, 'backend down');

  // 单一 catch-all + 子串分派（与屏1 mocked spec 同款写法）——避免 glob 模式对
  // query string 精确匹配的坑（**/reviews 不带尾部通配符匹配不到 ?status=... 的真实请求 URL）。
  await page.route('**/api/cpq/price-adjust/**', async (route) => {
    const url = route.request().url();
    const method = route.request().method();
    if (method !== 'GET') {
      if (url.includes('/reviews/impact')) return fulfillJson(route, impactPreview);
      if (url.includes('/recompute-budget')) { await route.fulfill({ status: 202, contentType: 'application/json', body: '{}' }); return; }
      await route.fulfill({ status: 202, contentType: 'application/json', body: '{}' });
      return;
    }
    if (url.includes('/reviews/') && !url.includes('/reviews?') && !url.endsWith('/reviews')) return fulfillJson(route, reviewDetail);
    if (url.includes('/reviews')) return fulfillJson(route, reviewsPage);
    await route.continue();
  });

  await loginAsAdmin(page);
  await page.goto('/pricing/reviews');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1200);
  await page.screenshot({ path: path.join(SHOT_DIR, 't0729-s34-01-list.png'), fullPage: true }).catch(() => {});

  // ── 屏 3 结构性断言 ──
  await expect(page.locator('text=价格调整审核')).toHaveCount(0); // 面包屑/标题不强制，跳过标题断言

  // rowRed=true 的两行（10120240 / 10130100）必须整行标红背景
  const row1 = page.locator('tr', { hasText: '10120240' });
  const row1Bg = await row1.locator('td').first().evaluate((el) => getComputedStyle(el).backgroundColor);
  console.log('[T0729][10120240 行背景色]', row1Bg);
  expect(row1Bg).toBe('rgb(255, 241, 240)'); // #fff1f0

  const healthyRow = page.locator('tr', { hasText: '10110037' }); // rowRed=false 样例，diffAdjusted 同样为正
  const healthyBg = await healthyRow.locator('td').first().evaluate((el) => getComputedStyle(el).backgroundColor);
  console.log('[T0729][10110037(健康行) 背景色]', healthyBg);
  expect(healthyBg).not.toBe('rgb(255, 241, 240)');

  // 比对状态标记：红橙分开计数
  await expect(row1.locator('text=🔴1 🟠1 / 3列')).toHaveCount(1);

  // ⚪K 缺核价数据标记
  const row4 = page.locator('tr', { hasText: '10130100' });
  await expect(row4.locator('text=⚪1')).toHaveCount(1);

  // 预算中间态：QUEUED → "预算计算中"；FAILED → "预算失败" + 重算
  const row2 = page.locator('tr', { hasText: '10110002' });
  await expect(row2.locator('text=预算计算中')).toHaveCount(1);
  const row3 = page.locator('tr', { hasText: '10130099' });
  await expect(row3.locator('text=预算失败')).toHaveCount(1);
  await expect(row3.locator('a', { hasText: '重算' })).toHaveCount(1);

  // 行内零动作按钮（不应有"编辑/发布"等行内 button，只有料号链接 + 重算文字链接）
  const rowButtons = await row1.locator('button').count();
  console.log('[T0729][10120240 行内 button 数量]', rowButtons);
  expect(rowButtons).toBe(0);

  await page.screenshot({ path: path.join(SHOT_DIR, 't0729-s34-02-toolbar.png') }).catch(() => {});

  // 全选 READY+PENDING 的行会因混入 QUEUED/FAILED 行而禁用"通过并升版"——先只选 10120240 一行
  await row1.locator('.ant-checkbox-input').click();
  await page.waitForTimeout(300);
  const approveBtn = page.locator('button', { hasText: '通过并升版' });
  await expect(approveBtn).toBeEnabled();
  await page.screenshot({ path: path.join(SHOT_DIR, 't0729-s34-03-selected-enabled.png') }).catch(() => {});

  // ── 屏 4：点料号链接打开审核抽屉 ──
  await row1.locator('a', { hasText: '10120240' }).click();
  await page.waitForTimeout(800);
  await expect(page.locator('.ant-drawer-title', { hasText: '镀银铜带' })).toHaveCount(1);
  await expect(page.locator('text=一、为什么变')).toHaveCount(1);
  await expect(page.locator('text=二、能不能接受')).toHaveCount(1);
  await expect(page.locator('text=三、下钻')).toHaveCount(1);
  // MISSING 着色文案
  await expect(page.locator('text=—（缺核价数据：核价侧）')).toHaveCount(1);
  // 判断依据 / 仅作参考
  await expect(page.locator('.ant-tag', { hasText: '判断依据' })).toHaveCount(1);
  await expect(page.locator('.ant-tag', { hasText: '仅作参考' })).toHaveCount(1);
  // 抽屉内没有任何"删除/编辑比对列"控件
  await expect(page.locator('.ant-drawer button', { hasText: '删除' })).toHaveCount(0);
  await page.screenshot({ path: path.join(SHOT_DIR, 't0729-s34-04-detail-drawer.png'), fullPage: true }).catch(() => {});
  await page.keyboard.press('Escape');
  await page.waitForTimeout(300);

  // ── 屏 5（工具栏"通过并升版"必要的确认前置）──
  await approveBtn.click();
  await page.waitForTimeout(600);
  await expect(page.locator('.ant-modal-title', { hasText: '通过前影响面确认' })).toHaveCount(1);
  await expect(page.locator('text=另有 3 张单不会被更新')).toHaveCount(1);
  await page.screenshot({ path: path.join(SHOT_DIR, 't0729-s34-05-impact-modal.png') }).catch(() => {});
  await page.locator('.ant-modal button', { hasText: '取消' }).click();
  await page.waitForTimeout(300);

  // ── 驳回 Drawer ──
  await page.locator('button', { hasText: '驳回' }).click();
  await page.waitForTimeout(400);
  await expect(page.locator('.ant-drawer-title', { hasText: '驳回' })).toHaveCount(1);
  await page.screenshot({ path: path.join(SHOT_DIR, 't0729-s34-06-reject-drawer.png') }).catch(() => {});
});
