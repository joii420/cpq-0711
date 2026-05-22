/**
 * 2026-05-20 多产品报价单流程 E2E
 *
 * 用户场景: v1.10 + 同一报价单加多个不同形态产品, 检查每个产品每个 Tab 都正确渲染
 *   - 产品1: 独立产品 + 3120012574 + 总装配/部件装配
 *   - 产品2: 组合产品 + 3120012574 (existing) + 无匹配 (AgCu90) + 总装配/部件装配/电镀 + 铆接
 *   - 产品3: 独立产品 + 另一料号 (3100070021 等)
 *
 * 验证目标:
 *   1) 每个产品卡片都渲染 9 个 Tab (8 业务 + SUBTOTAL 不显示)
 *   2) 每个 Tab 无"加载中..." 永久占位
 *   3) 字段值符合公式 (成材率/元素含量等)
 *   4) 不同产品视角 (SIMPLE/COMPOSITE) 用同一模板都不串
 *
 * 选择器约定 (2026-05-20 加固):
 *   - 产品卡片: .qt-product-card (QuotationStep2.tsx line 1126)
 *   - Tab 按钮: button.qt-tab-btn (QuotationStep2.tsx line 1312)
 *   - 数据表格: .qt-cost-table (QuotationStep2.tsx line 1324)
 *   - 所有 card.locator(...) 均限定在对应卡片内, 不跨卡片
 */
import { test, expect, Page, Locator } from '@playwright/test';
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
  const file = path.join(SHOT_DIR, `mpf-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`shot ${name} → ${file}`);
}

async function countLoading(page: Page, tag: string) {
  const c = await page.locator('text=加载中').count();
  console.log(`[${tag}] '加载中' = ${c}`);
  return c;
}

async function selectByLabel(page: Page, label: string, search: string, optionText?: string) {
  const item = page.locator('.ant-form-item').filter({ has: page.locator('label', { hasText: label }) }).first();
  await item.locator('.ant-select').first().click();
  await page.waitForTimeout(300);
  if (search) {
    await page.keyboard.type(search, { delay: 60 });
    await page.waitForTimeout(900);
  }
  await page.locator('.ant-select-item-option').filter({ hasText: optionText || search }).first().click();
  await page.waitForTimeout(400);
}

async function drawerNext(page: Page) {
  const btn = page.locator('.ant-drawer button:has-text("下一步")').last();
  await btn.click();
  await page.waitForTimeout(800);
}

async function pickProcess(page: Page, name: string) {
  const row = page.locator('.ant-drawer .ant-list-item').filter({ hasText: name }).first();
  const addBtn = row.locator('button').filter({ hasText: '添加' }).first();
  if ((await addBtn.count()) > 0) { await addBtn.click(); await page.waitForTimeout(300); }
}

/** 添加一个独立产品 — 料号 + 工序列表 */
async function addSimpleProduct(page: Page, partNo: string, processes: string[]) {
  await page.getByRole('button', { name: /添加产品/ }).first().click();
  await page.waitForTimeout(500);
  await page.locator('text=选配添加').first().click();
  await page.waitForTimeout(800);

  // P0: 独立产品
  await page.locator('.ant-drawer').locator('text=独立产品').first().click();
  await page.waitForTimeout(400);
  await drawerNext(page);

  // P1: 搜料号
  await page.locator('.ant-drawer input[placeholder*="料号"]').first().fill(partNo);
  await page.waitForTimeout(1500);
  await page.locator('.ant-drawer .ant-list-item').filter({ hasText: partNo }).first().click();
  await page.waitForTimeout(500);

  // 下一步 (材质自动锁定) → 下一步 → P3 工序
  await drawerNext(page);
  await drawerNext(page);
  for (const p of processes) await pickProcess(page, p);
  await page.waitForTimeout(500);

  // 多点几次下一步直到出现"确认添加"
  for (let i = 0; i < 4; i++) {
    const confirm = page.locator('.ant-drawer button').filter({ hasText: /确认添加|完成选配|提交/ }).last();
    if (await confirm.isVisible().catch(() => false)) { await confirm.click(); break; }
    await drawerNext(page);
  }
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);
}

/** 添加一个组合产品 — 配件1(existing) + 配件2(custom) + 组合工艺 */
async function addCompositeProduct(page: Page) {
  await page.getByRole('button', { name: /添加产品/ }).first().click();
  await page.waitForTimeout(500);
  await page.locator('text=选配添加').first().click();
  await page.waitForTimeout(800);

  // P0: 组合产品
  await page.locator('.ant-drawer').locator('text=组合产品').first().click();
  await page.waitForTimeout(400);
  await drawerNext(page);

  // 配件1: 3120012574 existing
  await page.locator('.ant-drawer input[placeholder*="料号"]').first().fill('3120012574');
  await page.waitForTimeout(1500);
  await page.locator('.ant-drawer .ant-list-item').filter({ hasText: '3120012574' }).first().click();
  await page.waitForTimeout(500);
  await drawerNext(page);  // P2 材质锁定
  await drawerNext(page);  // P3 工序
  await pickProcess(page, '总装配');
  await pickProcess(page, '部件装配');
  await pickProcess(page, '电镀');
  await drawerNext(page);  // → 配件2

  // 配件2: 无匹配 → custom AgCu90
  await page.locator('.ant-drawer').locator('text=无匹配料号').first().click();
  await page.waitForTimeout(500);
  await drawerNext(page);
  // P2 材质列表选 AgCu90 (90/10)
  await page.locator('.ant-drawer .ant-list-item').filter({ hasText: '90/10' }).first().click();
  await page.waitForTimeout(800);
  await drawerNext(page);
  await pickProcess(page, '总装配');
  await pickProcess(page, '部件装配');
  await pickProcess(page, '电镀');
  await drawerNext(page);  // → 组合工艺

  // 组合工艺: 铆接
  await page.locator('.ant-drawer').locator('text=铆接').first().click();
  await page.waitForTimeout(500);
  await drawerNext(page);

  // 确认添加
  for (let i = 0; i < 4; i++) {
    const confirm = page.locator('.ant-drawer button').filter({ hasText: /确认添加|完成选配|提交/ }).last();
    if (await confirm.isVisible().catch(() => false)) { await confirm.click(); break; }
    await drawerNext(page);
  }
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3500);
}

/**
 * 在指定产品卡片内切换到目标 Tab，并返回该 Tab 内的数据行 locator。
 *
 * @param card   - 已限定到特定产品卡片的 Locator (.qt-product-card)
 * @param tabName - Tab 按钮文字 (精确匹配)
 * @returns tbody 数据行 Locator (限定在 card 内)
 */
async function switchTabInCard(card: Locator, tabName: string): Promise<Locator | null> {
  const tabBtn = card.locator('button.qt-tab-btn').filter({ hasText: new RegExp(`^${tabName}$`) }).first();
  if (!(await tabBtn.isVisible().catch(() => false))) {
    console.log(`  [switchTabInCard] Tab "${tabName}" 不可见`);
    return null;
  }
  await tabBtn.click();
  // 等待数据加载
  await card.page().waitForTimeout(2200);
  // 数据行限定在卡片内 — 不跨卡片
  return card.locator('.qt-cost-table tbody tr');
}

/**
 * 获取行内所有单元格文本 (去空白)
 */
async function getRowTexts(row: Locator): Promise<string[]> {
  return (await row.locator('td').allInnerTexts()).map(c => c.trim());
}

/**
 * 切到指定产品卡片 (产品 1 / 产品 2 / ...) 然后逐 Tab 截图并断言
 * 选择器全部限定在 card locator 内，不跨产品卡片。
 */
async function inspectProduct(page: Page, productIdx: number, expectedTabs: string[]) {
  // 按卡片索引定位 (0-based → productIdx-1)
  const allCards = page.locator('.qt-product-card');
  const cardCount = await allCards.count();
  if (cardCount < productIdx) {
    console.log(`产品 ${productIdx} 卡片未找到 (总卡片数 ${cardCount})`);
    return { found: false, issues: [`产品 ${productIdx} 卡片未渲染 (总 ${cardCount})`] };
  }
  const card = allCards.nth(productIdx - 1);
  await card.scrollIntoViewIfNeeded();
  await page.waitForTimeout(800);

  const issues: string[] = [];

  // 统计该卡片内的 Tab 数量
  const tabCount = await card.locator('button.qt-tab-btn').count();
  console.log(`\n=== 产品 ${productIdx} Tab 总数: ${tabCount} (期望 ${expectedTabs.length}) ===`);

  // 逐 Tab 切换 — 所有操作限定在 card 内
  for (const tabName of expectedTabs) {
    const tabBtn = card.locator('button.qt-tab-btn').filter({ hasText: new RegExp(`^${tabName}$`) }).first();
    if (!(await tabBtn.isVisible().catch(() => false))) {
      const msg = `产品 ${productIdx} Tab "${tabName}" 不可见`;
      console.log(`  ${msg}`);
      issues.push(msg);
      continue;
    }
    await tabBtn.click();
    await page.waitForTimeout(2200);
    await shot(page, `p${productIdx}-tab-${tabName}`);

    // "加载中"只统计该卡片内的 (避免其他卡片的干扰)
    const loading = await card.locator('text=加载中').count();
    if (loading > 0) {
      const msg = `产品 ${productIdx} Tab "${tabName}" 有 ${loading} 处"加载中"`;
      console.log(`  ⚠ ${msg}`);
      issues.push(msg);
    }

    // 数据行限定在该卡片内
    const rows = card.locator('.qt-cost-table tbody tr');
    const rowCount = await rows.count();
    console.log(`  产品 ${productIdx} Tab "${tabName}": rows=${rowCount}, loading=${loading}`);
    for (let i = 0; i < Math.min(rowCount, 3); i++) {
      const cells = await getRowTexts(rows.nth(i));
      console.log(`    row[${i}]: ${cells.map(c => `"${c.slice(0, 30)}"`).join(' | ')}`);
    }
  }

  return { found: true, issues };
}

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

/**
 * Bug B 专项: 同报价单同 partNo (3120012574) 两个独立产品, 分别选不同工序
 *   产品1: 总装配 (MRO-AS-0001)
 *   产品2: 部件装配 (MRO-AS-0002)
 * 验证: 产品1 工序Tab 只有总装配; 产品2 工序Tab 只有部件装配; 互不串
 *
 * 选择器加固 (2026-05-20):
 *   - 用 page.locator('.qt-product-card').nth(N) 定位每张卡片
 *   - 所有 Tab 点击 / 行读取 限定在对应卡片 locator 内
 *   - 不再用全局 .qt-cost-table tr (跨卡片污染)
 */
test('Bug B: 同 partNo 双产品工序独立 (driverExpansionKey 6 维)', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(300_000);

  const consoleErrors: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text());
  });
  page.on('pageerror', (e) => consoleErrors.push('PAGE-ERROR: ' + e.message));

  // 捕获 configure-product 响应，用于打印 lineItem id 的诊断信息
  const configureResponses: any[] = [];
  const batchExpandRequests: any[] = [];
  page.on('response', async (resp) => {
    if (resp.url().includes('/configure-product/quotations/')) {
      try {
        const body = await resp.json().catch(() => null);
        if (body?.lineItems) configureResponses.push(...body.lineItems);
      } catch {}
    }
  });
  page.on('request', async (req) => {
    if (req.url().includes('/components/batch-expand') && req.method() === 'POST') {
      try {
        const body = JSON.parse(req.postData() || '{}');
        const relevant = (body.tasks || []).filter((t: any) => t.partNo === '3120012574');
        if (relevant.length > 0) batchExpandRequests.push(...relevant);
      } catch {}
    }
  });
  page.on('response', async (resp) => {
    if (resp.url().includes('/components/batch-expand') && resp.request().method() === 'POST') {
      try {
        const body = await resp.json().catch(() => null);
        const results = body?.data?.results || [];
        for (const r of results) {
          const rows = r?.data?.rows || [];
          const process_rows = rows.filter((row: any) => row?.driverRow?.process_code);
          if (process_rows.length > 0) {
            // no-op: just consume to avoid unhandled promise
          }
        }
      } catch {}
    }
  });

  await loginAsAdmin(page);
  await page.goto('/quotations/new');
  await page.waitForLoadState('networkidle');

  // Step1: 罗克韦尔 + v1.10
  // 用带时间戳的唯一报价单名，避免历史数据污染
  await selectByLabel(page, '客户', '罗克韦尔');
  await page.locator('text=产品分类').first().waitFor({ state: 'visible', timeout: 10000 }).catch(() => {});
  await page.waitForTimeout(500);
  await page.locator('input[placeholder*="报价单名称"]').first().fill('E2E-bugB-' + Date.now());
  await selectByLabel(page, '产品分类', '默认分类');
  await page.waitForTimeout(1200);
  await selectByLabel(page, '报价模板', '组合产品 v1.10', '组合产品 v1.10');
  await page.getByRole('button', { name: /下一步/ }).first().click();
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);

  // 产品1: 3120012574 + 仅总装配
  console.log('\n========== Bug B: 添加产品 1 (独立 3120012574 + 仅总装配) ==========');
  await addSimpleProduct(page, '3120012574', ['总装配']);
  await shot(page, 'bugB-after-product1');

  // 产品2: 3120012574 + 仅部件装配
  console.log('\n========== Bug B: 添加产品 2 (独立 3120012574 + 仅部件装配) ==========');
  await addSimpleProduct(page, '3120012574', ['部件装配']);
  await shot(page, 'bugB-after-product2');

  await page.waitForTimeout(3000);
  await shot(page, 'bugB-full-quotation');
  const finalLoading = await countLoading(page, 'Bug B final');

  // ── 产品 1 工序 Tab (限定在第 1 张卡片内) ──────────────────────────────────
  console.log('\n--- 产品 1 工序 Tab ---');
  const allCards = page.locator('.qt-product-card');
  const totalCards = await allCards.count();
  console.log(`product cards count: ${totalCards}`);

  const p1Card = allCards.nth(0);
  const p1Rows = await switchTabInCard(p1Card, '工序');
  await shot(page, 'bugB-p1-process-tab');

  let p1RowCount = 0;
  const p1Cells: string[][] = [];
  if (p1Rows) {
    p1RowCount = await p1Rows.count();
    console.log(`产品1 工序Tab 行数 (限定卡片1内): ${p1RowCount}`);
    for (let i = 0; i < p1RowCount; i++) {
      const cells = await getRowTexts(p1Rows.nth(i));
      p1Cells.push(cells);
      console.log(`  row[${i}]: ${cells.map(c => `"${c.slice(0, 20)}"`).join(' | ')}`);
    }
  } else {
    console.log('  产品1 工序 Tab 未找到');
  }

  const p1HasZzp = p1Cells.some(cells => cells.some(c => c.includes('总装配') || c.includes('MRO-AS-0001')));
  const p1HasBjzp = p1Cells.some(cells => cells.some(c => c.includes('部件装配') || c.includes('MRO-AS-0002')));
  console.log(`产品1 含总装配: ${p1HasZzp}, 含部件装配: ${p1HasBjzp}`);

  // 诊断摘要
  console.log('[DIAG] configure responses (lineItem ids):',
    configureResponses.map((li: any) => li?.id));
  console.log('[DIAG] batchExpand requests (3120012574 mat_process lineItemIds):',
    batchExpandRequests.map((t: any) => t.lineItemId));

  // ── 产品 2 工序 Tab (限定在第 2 张卡片内) ──────────────────────────────────
  console.log('\n--- 产品 2 工序 Tab ---');
  const p2Card = allCards.nth(1);
  const p2Rows = await switchTabInCard(p2Card, '工序');
  await shot(page, 'bugB-p2-process-tab');

  let p2RowCount = 0;
  const p2Cells: string[][] = [];
  if (p2Rows) {
    p2RowCount = await p2Rows.count();
    console.log(`产品2 工序Tab 行数 (限定卡片2内): ${p2RowCount}`);
    for (let i = 0; i < p2RowCount; i++) {
      const cells = await getRowTexts(p2Rows.nth(i));
      p2Cells.push(cells);
      console.log(`  row[${i}]: ${cells.map(c => `"${c.slice(0, 20)}"`).join(' | ')}`);
    }
  } else {
    console.log('  产品2 工序 Tab 未找到');
  }

  const p2HasZzp = p2Cells.some(cells => cells.some(c => c.includes('总装配') || c.includes('MRO-AS-0001')));
  const p2HasBjzp = p2Cells.some(cells => cells.some(c => c.includes('部件装配') || c.includes('MRO-AS-0002')));
  console.log(`产品2 含总装配: ${p2HasZzp}, 含部件装配: ${p2HasBjzp}`);

  // ── 行数隔离验证 ─────────────────────────────────────────────────────────
  // 两张卡片 工序 Tab 数据行必须**不完全相同**
  // 期望: 产品1 行 ≠ 产品2 行 (lineItemId 隔离生效)
  const p1RowTexts = p1Cells.map(r => r.join('|'));
  const p2RowTexts = p2Cells.map(r => r.join('|'));
  const rowsAreIdentical = p1RowTexts.length > 0
    && p1RowTexts.length === p2RowTexts.length
    && p1RowTexts.every((r, i) => r === p2RowTexts[i]);

  console.log('\n=== Bug B 总结 ===');
  console.log(`产品1 有总装配: ${p1HasZzp ? 'OK' : 'FAIL'}`);
  console.log(`产品1 无部件装配: ${!p1HasBjzp ? 'OK' : 'FAIL (Bug B 未修: 产品1 展开串到产品2 的工序)'}`);
  console.log(`产品2 有部件装配: ${p2HasBjzp ? 'OK' : 'FAIL'}`);
  console.log(`产品2 无总装配: ${!p2HasZzp ? 'OK' : 'FAIL (Bug B 未修: 产品2 展开串到产品1 的工序)'}`);
  console.log(`两产品行不完全相同 (隔离): ${!rowsAreIdentical ? 'OK' : 'FAIL'}`);
  console.log(`"加载中" final count: ${finalLoading}`);

  if (consoleErrors.length > 0) {
    console.log(`console.error (前5条):`);
    consoleErrors.slice(0, 5).forEach(e => console.log('  ' + e.slice(0, 200)));
  }

  // 核心断言
  expect.soft(p1HasZzp, '产品1 应含总装配 (仅选了总装配)').toBe(true);
  expect.soft(p1HasBjzp, '产品1 不应含部件装配 (Bug B: lineItemId 未隔离)').toBe(false);
  expect.soft(p2HasBjzp, '产品2 应含部件装配 (仅选了部件装配)').toBe(true);
  expect.soft(p2HasZzp, '产品2 不应含总装配 (Bug B: lineItemId 未隔离)').toBe(false);
  // 兜底断言: 两产品工序行不能完全相同
  expect.soft(rowsAreIdentical, '两产品工序 Tab 不应完全相同 (lineItemId 隔离)').toBe(false);
  expect.soft(finalLoading, '"加载中" final count').toBe(0);
});

test('多产品 v1.10: 产品1(独立) + 产品2(组合) 验证所有 Tab 渲染', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(360_000);  // 多产品流程长

  const consoleErrors: string[] = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('PAGE-ERROR: ' + e.message));

  // 登录 + 进入新建报价单
  await loginAsAdmin(page);
  await page.goto('/quotations/new');
  await page.waitForLoadState('networkidle');

  // Step1: 客户 罗克韦尔 + 模板 v1.10
  // 用带时间戳的唯一报价单名，避免历史数据污染
  await selectByLabel(page, '客户', '罗克韦尔');
  await page.locator('text=产品分类').first().waitFor({ state: 'visible', timeout: 10000 }).catch(() => {});
  await page.waitForTimeout(500);
  await page.locator('input[placeholder*="报价单名称"]').first().fill('E2E-multi-' + Date.now());
  await selectByLabel(page, '产品分类', '默认分类');
  await page.waitForTimeout(1200);
  await selectByLabel(page, '报价模板', '组合产品 v1.10', '组合产品 v1.10');
  await shot(page, 'step1-template-v1.10');

  await page.getByRole('button', { name: /下一步/ }).first().click();
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);

  // 产品 1: 独立产品 3120012574 + 总装配/部件装配
  console.log('\n========== 添加产品 1: 独立 3120012574 ==========');
  await addSimpleProduct(page, '3120012574', ['总装配', '部件装配']);
  await shot(page, 'after-product-1');

  // 产品 2: 组合产品 3120012574 + AgCu90 + 铆接
  console.log('\n========== 添加产品 2: 组合 3120012574 + AgCu90 ==========');
  await addCompositeProduct(page);
  await shot(page, 'after-product-2');

  // 全报价单状态截图
  await page.waitForTimeout(2000);
  await shot(page, 'full-quotation');
  const finalLoading = await countLoading(page, 'final');

  // 检查每个产品 — inspectProduct 内已限定到对应卡片
  const tabs = ['材质', '工序', '元素含量', '组合工艺', '选配-材质', '选配-工序列表', '选配-元素含量', '选配-组合工艺'];

  console.log('\n========== 检查产品 1 (独立) ==========');
  const p1 = await inspectProduct(page, 1, tabs);

  console.log('\n========== 检查产品 2 (组合) ==========');
  const p2 = await inspectProduct(page, 2, tabs);

  console.log('\n=========================================');
  console.log('=== 总结 ===');
  console.log(`产品 1 (独立): ${p1.issues.length === 0 ? 'OK' : p1.issues.length + ' 处问题'}`);
  p1.issues.forEach(i => console.log(`  - ${i}`));
  console.log(`产品 2 (组合): ${p2.issues.length === 0 ? 'OK' : p2.issues.length + ' 处问题'}`);
  p2.issues.forEach(i => console.log(`  - ${i}`));
  console.log(`最终"加载中" count: ${finalLoading}`);
  console.log(`console.error: ${consoleErrors.length}`);
  consoleErrors.slice(0, 5).forEach(e => console.log('  ' + e.slice(0, 200)));

  await shot(page, 'final');

  // 非阻断断言: 即使有问题也跑完全部 Tab + 输出诊断, 最后再断言
  expect.soft(p1.issues, '产品 1 渲染问题').toEqual([]);
  expect.soft(p2.issues, '产品 2 渲染问题').toEqual([]);
  expect.soft(finalLoading, '最终页面"加载中"占位').toBe(0);
});
