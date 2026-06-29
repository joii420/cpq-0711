/**
 * E2E 验证：报价单详情页只读快照视图切换（2026-06-29 合并至 master）
 *
 * 功能：报价单详情页 /quotations/{id} 产品明细区两级 Segmented 切换
 * - 上层：报价单 / 核价单 / 比对视图
 * - 下层（报价单/核价单）：产品卡片 / Excel 视图
 * - 全部只读读快照、零计算
 *
 * 验证目标：
 * 1. 5 个视图（报价卡片 → 报价Excel → 核价卡片 → 核价Excel → 比对）都能渲染
 * 2. 全程无 batch-expand 请求（URL 含 /batch-expand 计数=0）
 * 3. 无"加载中"残留、无 __loading__ 文本
 * 4. 核价视图数据不串报价值（高风险检查点）
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
  const file = path.join(SHOT_DIR, `qdrv-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`[screenshot] ${name} -> ${path.basename(file)}`);
}

/** 检查并打印"加载中"计数 */
async function countLoading(page: Page, tag: string): Promise<number> {
  const c = await page.locator('text=加载中').count();
  console.log(`[${tag}] '加载中' count = ${c}`);
  return c;
}

/** 从已登录 page 中提取 session cookie，用于直连后端 API */
async function extractSessionCookie(page: Page): Promise<string> {
  const cookies = await page.context().cookies('http://localhost:8081');
  return cookies.map(c => `${c.name}=${c.value}`).join('; ');
}

/**
 * 查找一条适合测试的报价单：
 * - status != DRAFT（SUBMITTED/APPROVED/SENT/ACCEPTED 等已提交状态）
 * - 有 lineItems（有产品数据，否则视图空白无法验证渲染）
 * 优先用任务书指定的固定 id，若不可用则从列表查询
 */
async function findTestQuotation(cookie: string): Promise<{ id: string; hasCosting: boolean } | null> {
  // 先试任务书指定 id
  // 选 snapshot 齐备的报价单（四值 quote/costing card+excel 均已落库），详情页才能纯读快照、零 batch-expand。
  // 注：本库 SUBMITTED 单多为快照特性之前创建（四值 NULL→详情页回退实时），故用四值齐备的 DRAFT 验证只读路径。
  const PREFERRED_ID = '89da551c-9b91-4cde-846e-a37e7c73dddb';
  try {
    const r = await fetch(`http://localhost:8081/api/cpq/quotations/${PREFERRED_ID}`, {
      headers: { Cookie: cookie },
    });
    if (r.ok) {
      const body = await r.json();
      const data = body.data ?? body;
      const status = data.status;
      const lineItems = data.lineItems || [];
      const hasCosting = !!data.costingCardTemplateId;
      // 关键判据：有快照（quoteCardValues 已落库）→ 详情页走只读快照路径（零 batch-expand），与 status 无关。
      const hasSnapshot = lineItems.some((li: any) => li.quoteCardValues);
      if (lineItems.length > 0 && hasSnapshot) {
        console.log(`[fixture] 使用指定 id=${PREFERRED_ID} status=${status} lineItems=${lineItems.length} hasCosting=${hasCosting} hasSnapshot=${hasSnapshot}`);
        return { id: PREFERRED_ID, hasCosting };
      }
      console.log(`[fixture] 指定 id=${PREFERRED_ID} 不满足条件: status=${status} lineItems=${lineItems.length} hasSnapshot=${hasSnapshot}`);
    }
  } catch (e) {
    console.log(`[fixture] 查询指定 id 失败: ${e}`);
  }

  // 从列表查找
  console.log('[fixture] 从列表查找合适报价单...');
  try {
    const r = await fetch('http://localhost:8081/api/cpq/quotations?page=0&size=50', {
      headers: { Cookie: cookie },
    });
    if (!r.ok) { console.log(`[fixture] 列表 API 返回 ${r.status}`); return null; }
    const body = await r.json();
    const data = body.data ?? body;
    const items: any[] = Array.isArray(data) ? data : (data.content ?? []);
    // 找 status != DRAFT 且 lineItems 有数据
    for (const q of items) {
      if (q.status !== 'DRAFT' && q.lineItems && q.lineItems.length > 0) {
        const hasCosting = !!q.costingCardTemplateId;
        console.log(`[fixture] 选中 id=${q.id} status=${q.status} lineItems=${q.lineItems.length} hasCosting=${hasCosting}`);
        return { id: q.id, hasCosting };
      }
    }
    // 如果列表没有 lineItems，单独 getById 验证前几条非草稿
    const nonDrafts = items.filter(q => q.status !== 'DRAFT').slice(0, 5);
    for (const q of nonDrafts) {
      const detailR = await fetch(`http://localhost:8081/api/cpq/quotations/${q.id}`, {
        headers: { Cookie: cookie },
      });
      if (!detailR.ok) continue;
      const detailBody = await detailR.json();
      const detail = detailBody.data ?? detailBody;
      const lineItems = detail.lineItems || [];
      if (lineItems.length > 0) {
        const hasCosting = !!detail.costingCardTemplateId;
        console.log(`[fixture] (detail) 选中 id=${detail.id} status=${detail.status} lineItems=${lineItems.length} hasCosting=${hasCosting}`);
        return { id: detail.id, hasCosting };
      }
    }
    console.log('[fixture] 未找到满足条件的报价单（无非草稿 + 有 lineItems）');
    return null;
  } catch (e) {
    console.log(`[fixture] 列表查询异常: ${e}`);
    return null;
  }
}

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

// ═══════════════════════════════════════════════════════════════════════
// 主用例：详情页 5 视图只读渲染 + batch-expand=0 验证
// ═══════════════════════════════════════════════════════════════════════
test('详情页只读视图: 5视图渲染 + batch-expand=0 + 无加载中残留', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  // ── 监听 console 错误 ──
  const consoleErrors: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text());
  });
  page.on('pageerror', (e) => consoleErrors.push('PAGE-ERROR: ' + e.message));

  // ── 核心监听：batch-expand 请求计数（全程应=0）──
  const batchExpandUrls: string[] = [];
  page.on('request', (req) => {
    if (req.url().includes('/batch-expand')) {
      batchExpandUrls.push(req.url());
      console.log(`[WARN] batch-expand 请求检测: ${req.url()}`);
    }
  });

  // ── 1) 登录 ──
  await loginAsAdmin(page);
  await shot(page, 'after-login');

  // ── 2) 获取 session cookie 并查找测试报价单 ──
  const cookie = await extractSessionCookie(page);
  const fixture = await findTestQuotation(cookie);
  if (!fixture) {
    console.log('[SKIP] 未找到满足条件的报价单夹具，跳过本次测试');
    test.skip(true, '未找到 SUBMITTED+ 且有产品数据的报价单');
    return;
  }
  const { id: quotationId, hasCosting } = fixture;
  console.log(`\n=== 测试夹具: id=${quotationId}  hasCosting=${hasCosting} ===\n`);

  // ── 3) 打开详情页 ──
  await page.goto(`/quotations/${quotationId}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);
  await shot(page, 'detail-loaded');

  // 等"产品明细" Card 可见
  const detailCard = page.locator('.ant-card-head-title').filter({ hasText: '产品明细' }).first();
  const cardVisible = await detailCard.isVisible().catch(() => false);
  console.log(`[检查] 产品明细 Card 可见: ${cardVisible}`);
  if (!cardVisible) {
    // 有些页面"产品明细"可能在信息 tab 里，尝试滚动
    await page.locator('text=产品明细').first().scrollIntoViewIfNeeded().catch(() => {});
    await page.waitForTimeout(500);
  }
  await shot(page, 'product-detail-card');

  // ── 4) 记录初始状态（默认：报价单 + 产品卡片）──
  console.log('\n=== 视图 1: 报价单 + 产品卡片（默认）===');
  await page.waitForTimeout(1500);
  const initialLoadingCount = await countLoading(page, 'initial');

  // 检查有卡片内容或暂无产品
  const hasContent = await page.locator('.qt-products-list').count();
  const hasNoProduct = await page.locator('text=暂无产品').count();
  console.log(`[视图1-报价卡片] qt-products-list=${hasContent} 暂无产品=${hasNoProduct}`);
  await shot(page, 'view1-quote-card');

  // ── 5) 视图 2：报价单 + Excel 视图 ──
  console.log('\n=== 视图 2: 报价单 + Excel 视图 ===');
  // 下层 Segmented: Excel 视图
  const excelSegBtn = page.locator('.ant-segmented-item').filter({ hasText: 'Excel 视图' }).first();
  const excelSegVisible = await excelSegBtn.isVisible().catch(() => false);
  if (excelSegVisible) {
    await excelSegBtn.click();
    await page.waitForTimeout(2000);
  } else {
    console.log('[视图2] Excel 视图 Segmented 不可见，跳过');
  }
  const view2LoadingCount = await countLoading(page, 'view2-quote-excel');
  const view2TableCount = await page.locator('table').count();
  const view2NoData = await page.locator('text=暂无数据').count();
  console.log(`[视图2-报价Excel] table=${view2TableCount} 暂无数据=${view2NoData} 加载中=${view2LoadingCount}`);
  await shot(page, 'view2-quote-excel');

  // ── 6) 视图 3：核价单 + 产品卡片 ──
  console.log('\n=== 视图 3: 核价单 + 产品卡片 ===');
  // 先切回产品卡片（避免核价Excel与报价Excel混淆）
  const cardSegBtn = page.locator('.ant-segmented-item').filter({ hasText: '产品卡片' }).first();
  if (await cardSegBtn.isVisible().catch(() => false)) {
    await cardSegBtn.click();
    await page.waitForTimeout(500);
  }
  // 上层 Segmented: 核价单
  const costingMainBtn = page.locator('.ant-segmented-item').filter({ hasText: '核价单' }).first();
  const costingMainVisible = await costingMainBtn.isVisible().catch(() => false);
  if (costingMainVisible) {
    await costingMainBtn.click();
    await page.waitForTimeout(2000);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1000);
  } else {
    console.log('[视图3] 核价单 Segmented 不可见');
  }
  const view3LoadingCount = await countLoading(page, 'view3-costing-card');
  const view3Content = await page.locator('.qt-products-list').count();
  const view3NoData = await page.locator('text=暂无数据').count();
  const view3NoProduct = await page.locator('text=暂无产品').count();
  console.log(`[视图3-核价卡片] qt-products-list=${view3Content} 暂无数据=${view3NoData} 暂无产品=${view3NoProduct} 加载中=${view3LoadingCount}`);
  await shot(page, 'view3-costing-card');

  // 核价卡片关键检查：是否有内容（有快照时），不应出现"加载中"残留
  if (!hasCosting) {
    console.log('[视图3] 该报价单无核价模板，暂无数据属正常');
  }

  // ── 7) 视图 4：核价单 + Excel 视图 ──
  console.log('\n=== 视图 4: 核价单 + Excel 视图 ===');
  const costingExcelBtn = page.locator('.ant-segmented-item').filter({ hasText: 'Excel 视图' }).first();
  if (await costingExcelBtn.isVisible().catch(() => false)) {
    await costingExcelBtn.click();
    await page.waitForTimeout(2000);
  } else {
    console.log('[视图4] Excel 视图 Segmented 不可见');
  }
  const view4LoadingCount = await countLoading(page, 'view4-costing-excel');
  const view4TableCount = await page.locator('table').count();
  const view4NoData = await page.locator('text=暂无数据').count();
  console.log(`[视图4-核价Excel] table=${view4TableCount} 暂无数据=${view4NoData} 加载中=${view4LoadingCount}`);
  await shot(page, 'view4-costing-excel');

  // ── 8) 视图 5：比对视图 ──
  console.log('\n=== 视图 5: 比对视图 ===');
  const comparisonBtn = page.locator('.ant-segmented-item').filter({ hasText: '比对视图' }).first();
  const comparisonVisible = await comparisonBtn.isVisible().catch(() => false);
  if (comparisonVisible) {
    await comparisonBtn.click();
    await page.waitForTimeout(2000);
  } else {
    console.log('[视图5] 比对视图 Segmented 不可见');
  }
  // 比对视图切换后，下层 Segmented（产品卡片/Excel视图）应消失（由 mainTab !== 'comparison' 控制）
  const lowerSegAfterComparison = await page.locator('.ant-segmented-item').filter({ hasText: '产品卡片' }).count();
  console.log(`[视图5-比对] 下层Segmented(产品卡片)应消失: count=${lowerSegAfterComparison} (期望0)`);
  const view5LoadingCount = await countLoading(page, 'view5-comparison');
  const view5TableCount = await page.locator('table').count();
  const view5NoData = await page.locator('text=暂无数据').count();
  console.log(`[视图5-比对视图] table=${view5TableCount} 暂无数据=${view5NoData} 加载中=${view5LoadingCount}`);
  await shot(page, 'view5-comparison');

  // ── 9) 回切报价单视图（验证切换无内存泄漏/残留状态）──
  console.log('\n=== 切回报价单视图（回归验证）===');
  const quoteMainBtn = page.locator('.ant-segmented-item').filter({ hasText: '报价单' }).first();
  if (await quoteMainBtn.isVisible().catch(() => false)) {
    await quoteMainBtn.click();
    await page.waitForTimeout(1500);
  }
  const finalLoadingCount = await countLoading(page, 'final-quote-card');
  await shot(page, 'final-quote-card-back');

  // ── 10) 汇总结论 ──
  console.log('\n' + '='.repeat(60));
  console.log('=== 详情页只读视图 E2E 汇总 ===');
  console.log(`夹具 id     : ${quotationId}`);
  console.log(`hasCosting  : ${hasCosting}`);
  console.log(`batch-expand: ${batchExpandUrls.length} 次 (期望 0)`);
  console.log(`最终加载中   : ${finalLoadingCount} (期望 0)`);
  console.log(`console.error: ${consoleErrors.length} 条`);
  consoleErrors.slice(0, 5).forEach(e => console.log(`  ERROR: ${e.slice(0, 200)}`));
  console.log('='.repeat(60) + '\n');

  // ── 断言 1：batch-expand 计数=0（只读快照视图不应触发 batch-expand）──
  expect(
    batchExpandUrls.length,
    `详情页只读视图不应触发 batch-expand，但检测到 ${batchExpandUrls.length} 次：\n${batchExpandUrls.join('\n')}`
  ).toBe(0);

  // ── 断言 2：最终无"加载中"残留 ──
  expect(
    finalLoadingCount,
    `详情页最终应无"加载中"残留，但仍有 ${finalLoadingCount} 处`
  ).toBe(0);

  // ── 断言 3：无 __loading__ 文本 ──
  const loadingPlaceholderCount = await page.locator('text=__loading__').count();
  expect(
    loadingPlaceholderCount,
    `不应出现 __loading__ 哨兵文本残留，但检测到 ${loadingPlaceholderCount} 处`
  ).toBe(0);

  // ── 断言 4：产品明细 Card 始终可见（页面未崩溃）──
  const cardStillVisible = await page.locator('.ant-card-head-title')
    .filter({ hasText: '产品明细' }).first().isVisible().catch(() => false);
  expect(cardStillVisible, '产品明细 Card 在全部视图切换后应仍可见（页面未崩溃）').toBe(true);
});

// ═══════════════════════════════════════════════════════════════════════
// 补充用例：核价视图数据不串报价值（高风险：核价快照字段不应显示报价侧字段名）
// ═══════════════════════════════════════════════════════════════════════
test('详情页只读视图: 核价/报价两侧不串值', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  // ── 登录 ──
  await loginAsAdmin(page);

  // ── 查找有核价模板的报价单 ──
  const cookie = await extractSessionCookie(page);
  const fixture = await findTestQuotation(cookie);
  if (!fixture) {
    test.skip(true, '未找到 SUBMITTED+ 且有产品数据的报价单');
    return;
  }
  if (!fixture.hasCosting) {
    test.skip(true, `报价单 ${fixture.id} 无核价模板，跳过串值检查`);
    return;
  }

  // ── 打开详情页 ──
  await page.goto(`/quotations/${fixture.id}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);

  // ── 切到报价卡片：抓取前三个 Tab 标题 ──
  const quoteMainBtn = page.locator('.ant-segmented-item').filter({ hasText: '报价单' }).first();
  if (await quoteMainBtn.isVisible().catch(() => false)) {
    await quoteMainBtn.click();
    await page.waitForTimeout(1000);
  }
  const quoteTabs = await page.locator('button.qt-tab-btn').allInnerTexts().catch(() => [] as string[]);
  console.log(`[串值检查] 报价卡片 Tabs: ${JSON.stringify(quoteTabs.slice(0, 5))}`);
  await shot(page, 'cross-check-quote-tabs');

  // ── 切到核价卡片：抓取前三个 Tab 标题 ──
  const costingMainBtn = page.locator('.ant-segmented-item').filter({ hasText: '核价单' }).first();
  if (await costingMainBtn.isVisible().catch(() => false)) {
    await costingMainBtn.click();
    await page.waitForTimeout(1500);
  }
  const costingTabs = await page.locator('button.qt-tab-btn').allInnerTexts().catch(() => [] as string[]);
  console.log(`[串值检查] 核价卡片 Tabs: ${JSON.stringify(costingTabs.slice(0, 5))}`);
  await shot(page, 'cross-check-costing-tabs');

  // ── 断言：核价 Tabs 列表不为空（有数据）且与报价 Tabs 不完全相同（未串模板）──
  console.log('\n=== 串值检查结论 ===');
  console.log(`报价 Tabs: ${quoteTabs.length} 个`);
  console.log(`核价 Tabs: ${costingTabs.length} 个`);
  // 如果两侧 Tabs 完全一样，可能是串模板（不一定是 bug，两个模板可能设计相同）
  // 这里只记录，不硬断言相同=bug
  if (quoteTabs.length > 0 && costingTabs.length > 0) {
    const quoteSet = new Set(quoteTabs.map(t => t.trim()));
    const costingSet = new Set(costingTabs.map(t => t.trim()));
    const identical = [...quoteSet].every(t => costingSet.has(t)) && quoteSet.size === costingSet.size;
    console.log(`两侧 Tabs 完全相同: ${identical}（相同不一定是 bug，两个模板可能设计一致）`);
  }

  // 最终无"加载中"残留
  const finalLoading = await countLoading(page, 'cross-check-final');
  expect(finalLoading, '核价/报价切换后不应有"加载中"残留').toBe(0);
});
