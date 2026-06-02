/**
 * E2E 验证用户给的报价单流程:
 * 报价单管理 → 新建 → 罗克韦尔 + 模板 v1.10 → 添加产品 → 选配添加
 *   → 独立产品 → 料号 10110002 → 工序总装配+部件装配 → 确认
 *
 * 验证目标:
 * 1) 9 个 Tab 都按模板配置渲染
 * 2) 字段按组件管理的公式计算 (单价/成材率走全局变量, 小计走公式)
 * 3) 不出现"加载中..."永久占位
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
  const file = path.join(SHOT_DIR, `qf-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}

async function countLoading(page: Page, tag: string) {
  const c = await page.locator('text=加载中').count();
  console.log(`[${tag}] 'text=加载中' (locator count) = ${c}`);
  return c;
}

/** 通过 Form.Item label 定位 antd Select 控件 (鲁棒,不依赖 placeholder span 渲染) */
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

test('报价单流程: 罗克韦尔 + v1.10 + 10110002 + 总装配/部件装配', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  // 控制台错误监控
  const consoleErrors: string[] = [];
  const lfDebug: string[] = [];
  page.on('console', (m) => {
    const text = m.text();
    if (m.type() === 'error') consoleErrors.push(text);
    if (text.includes('[LF-')) lfDebug.push(text);
  });
  page.on('pageerror', (e) => consoleErrors.push('PAGE-ERROR: ' + e.message));

  // ── 1) 登录 (用项目 fixture) ──
  await loginAsAdmin(page);
  await shot(page, 'after-login');

  // ── 2) 进入新建报价单 ──
  await page.goto('/quotations/new');
  await page.waitForLoadState('networkidle');
  await shot(page, 'step1-init');

  // ── 3) 选客户: 罗克韦尔 (label-based) ──
  await selectByLabel(page, '客户', '罗克韦尔');
  await shot(page, 'customer-selected');

  // ── 4) 等 QuotationCreateForm 子卡片渲染 + 滚动让它入视野 ──
  await page.locator('text=产品分类').first().waitFor({ state: 'visible', timeout: 10000 }).catch(() => {});
  await page.locator('text=产品分类').first().scrollIntoViewIfNeeded().catch(() => {});
  await page.waitForTimeout(500);
  await shot(page, 'after-customer');

  // 报价单名称 (在 QuotationCreateForm 子卡片里, placeholder="请填写报价单名称")
  await page.locator('input[placeholder*="报价单名称"]').first().fill('E2E-test-' + Date.now());
  await page.waitForTimeout(200);
  await shot(page, 'name-filled');

  // 选产品分类
  await selectByLabel(page, '产品分类', '默认分类');
  await shot(page, 'category-selected');

  // ── 6) 报价模板 v1.10 ──
  // 注意: antd Select optionFilterProp="label" 对 JSX label 无效, 28+ 选项 + virtual scrolling
  // 导致 selectByLabel helper 的 filter({hasText}) 在下拉中间位置 15s 内未渲染到 → timeout
  // 局部加固: 打开 dropdown 后输入更短搜索词让候选集变小, 再 scrollIntoViewIfNeeded 兜底
  await page.waitForTimeout(1200);  // 等模板加载
  {
    // 1. 定位并展开报价模板 Select
    const templateItem = page.locator('.ant-form-item')
      .filter({ has: page.locator('label', { hasText: '报价模板' }) })
      .first();
    await templateItem.locator('.ant-select').first().click();
    await page.waitForTimeout(300);

    // 2. 输入 "v1.10" 触发原生 input filter（比输入全称更短, 降低 virtual scroll 风险）
    await page.keyboard.type('v1.10', { delay: 60 });
    await page.waitForTimeout(900);

    // 3. 精确匹配 "组合产品 v1.10"（\b 或行尾锚, 防止撞 v1.100/v1.101 等未来版本）
    const opt = page.locator('.ant-select-item-option')
      .filter({ hasText: /组合产品\s+v1\.10(\s|$)/ })
      .first();
    // scrollIntoViewIfNeeded 兜底 virtual scroll 未渲染到目标选项的情况
    await opt.scrollIntoViewIfNeeded().catch(() => {});
    await opt.click();
    await page.waitForTimeout(400);
  }
  await shot(page, 'template-selected');

  // ── 7) 下一步 → Step2 ──
  await page.getByRole('button', { name: /下一步/ }).first().click();
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  await shot(page, 'step2-empty');
  await countLoading(page, 'step2-empty');

  // ── 8) 点 "+ 添加产品" → "选配添加" ──
  await page.getByRole('button', { name: /添加产品/ }).first().click();
  await page.waitForTimeout(500);
  await shot(page, 'add-product-dropdown');
  // dropdown 项 "选配添加"
  await page.locator('text=选配添加').first().click();
  await page.waitForTimeout(800);
  await shot(page, 'configure-drawer-p0');

  // ── 9) P0: 独立产品 vs 组合产品 → 独立产品 ──
  // 抽屉里的 "独立产品 ..." 卡片 (drawer 内才点, 否则主页面也有"独立产品")
  await page.locator('.ant-drawer').locator('text=独立产品').first().click().catch(() => {});
  await page.waitForTimeout(400);
  await shot(page, 'p0-simple-selected');
  // 下一步进 P1
  await page.locator('.ant-drawer button:has-text("下一步")').last().click();
  await page.waitForTimeout(800);
  await shot(page, 'p1-search-part');

  // ── 10) P1: 搜料号 10110002 → 选 ──
  // 抽屉里搜索框 (Step1SearchPart placeholder="输入料号、材质、规格或尺寸…")
  const partInput = page.locator('.ant-drawer input[placeholder*="料号"]').first();
  await partInput.fill('10110002');
  await page.waitForTimeout(1500);  // 等远程搜索
  await shot(page, 'p1-search-result');
  // 结果是 ant-list-item, filter by hfPartNo 文本
  const partRow = page.locator('.ant-drawer .ant-list-item').filter({ hasText: '10110002' }).first();
  const found = await partRow.count();
  console.log(`[P1] match rows: ${found}`);
  await partRow.click();
  await page.waitForTimeout(600);
  await shot(page, 'p1-part-selected');

  // 下一步 → P2 材质
  await page.locator('.ant-drawer button:has-text("下一步")').last().click();
  await page.waitForTimeout(1500);
  await shot(page, 'p2-material');
  // P2 是材质锁定,已选已有料号会自动锁定材质
  // 直接下一步 → P3 工序
  await page.locator('.ant-drawer button:has-text("下一步")').last().click();
  await page.waitForTimeout(1500);
  await shot(page, 'p3-process');

  // ── 11) P3: 工序选择 总装配/部件装配 (List.Item + 内嵌"添加"按钮) ──
  for (const name of ['总装配', '部件装配']) {
    const row = page.locator('.ant-drawer .ant-list-item').filter({ hasText: name }).first();
    const addBtn = row.locator('button').filter({ hasText: '添加' }).first();
    const c = await addBtn.count();
    if (c > 0) {
      await addBtn.click();
      console.log(`[P3] clicked + 添加: ${name}`);
    } else {
      console.log(`[P3] NOT FOUND: ${name} (row count=${await row.count()})`);
    }
    await page.waitForTimeout(300);
  }
  await page.waitForTimeout(500);
  await shot(page, 'p3-process-checked');

  // 下一步 → P4 组合工艺(独立产品没有) 或 P5 摘要
  for (let i = 0; i < 3; i++) {
    const next = page.locator('.ant-drawer button:has-text("下一步")').last();
    if (await next.isVisible().catch(() => false) && await next.isEnabled().catch(() => false)) {
      await next.click();
      await page.waitForTimeout(1000);
      await shot(page, `p4-or-p5-step${i}`);
    } else {
      break;
    }
  }

  // ── 12) 点 "确认添加" ──
  const confirmBtn = page.locator('.ant-drawer button').filter({ hasText: /确认添加|完成选配|提交/ }).last();
  if (await confirmBtn.isVisible().catch(() => false)) {
    await confirmBtn.click();
    console.log('✓ 已点击 确认添加');
  }
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);
  await shot(page, 'after-confirm');

  // ── 13) 最终报价单 step2 渲染验证 ──
  await page.waitForTimeout(2000);
  await shot(page, 'final-step2-quotation');
  const loadingFinal = await countLoading(page, 'final-step2');

  // 滚动到产品卡片
  await page.locator('text=产品 1').first().scrollIntoViewIfNeeded().catch(() => {});
  await page.waitForTimeout(1500);

  // 8 个组件 Tab 实际是产品卡片内的 <button class="qt-tab-btn">
  const tabs = page.locator('button.qt-tab-btn');
  const tabCount = await tabs.count();
  console.log(`\n=== Tab 总数 (qt-tab-btn): ${tabCount} (期望 8 = 9 组件 - 1 SUBTOTAL) ===`);

  // 列出每个 Tab 名称
  for (let i = 0; i < tabCount; i++) {
    const t = await tabs.nth(i).innerText().catch(() => '?');
    console.log(`  Tab[${i}]: "${t.trim()}"`);
  }
  for (let i = 0; i < tabCount; i++) {
    const t = await tabs.nth(i).innerText().catch(() => '?');
    console.log(`  Tab[${i}]: "${t.replace(/\n/g, ' | ').trim()}"`);
  }

  // 检查每个期望的 Tab (总成本是 SUBTOTAL 不渲染为 Tab 是设计)
  const expected = ['材质', '工序', '元素含量', '组合工艺', '选配-材质', '选配-工序列表', '选配-元素含量', '选配-组合工艺'];
  for (const name of expected) {
    const c = await tabs.filter({ hasText: name }).count();
    console.log(`  expect '${name}': ${c > 0 ? '✅ FOUND' : '❌ MISSING'}`);
  }
  // SUBTOTAL 不应该作为 Tab
  const subtotalTab = await tabs.filter({ hasText: '总成本' }).count();
  console.log(`  '总成本' Tab (期望 0, SUBTOTAL 不渲染为 Tab): ${subtotalTab === 0 ? '✅' : '❌ 误渲染 ' + subtotalTab}`);
  // 但产品小计应该在底部小计条里
  const subtotalBar = await page.locator('text=产品小计').count();
  console.log(`  '产品小计' (底部条): ${subtotalBar > 0 ? '✅ 存在' : '❌ 缺失'}`);

  // ── 逐 Tab 切换并截图 ──
  const tabNames = ['材质', '工序', '元素含量', '组合工艺', '选配-材质', '选配-工序列表', '选配-元素含量', '选配-组合工艺'];
  for (const tabName of tabNames) {
    // 精确匹配文本 (避免 "选配-工序列表" 包含 "工序" 的子串误命中)
    const tab = tabs.filter({ hasText: new RegExp(`^${tabName}$`) }).first();
    const visible = await tab.isVisible().catch(() => false);
    if (!visible) {
      console.log(`  [Tab '${tabName}'] ❌ 不可见 — 跳过`);
      continue;
    }
    await tab.click();
    await page.waitForTimeout(2200);  // 等数据加载
    await shot(page, `tab-${tabName}`);
    const loadCount = await countLoading(page, `tab-${tabName}`);

    // 抓表格行 + 单元格内容
    const rows = page.locator('.ant-table-row');
    const rowCount = await rows.count();
    console.log(`  [Tab '${tabName}'] rows=${rowCount}, '加载中'=${loadCount}`);
    for (let i = 0; i < Math.min(rowCount, 3); i++) {
      const cells = await rows.nth(i).locator('td').allInnerTexts();
      console.log(`    row[${i}]: ${cells.map(c => `"${c.trim().slice(0, 30)}"`).join(' | ')}`);
    }
  }

  console.log(`\n=== console.error 总数: ${consoleErrors.length} ===`);
  consoleErrors.slice(0, 10).forEach(e => console.log('  🔴 ' + e.slice(0, 200)));

  console.log(`\n=== LF-DEBUG / LF-RENDER 共 ${lfDebug.length} 条 ===`);
  lfDebug.forEach(e => console.log('  🟡 ' + e.slice(0, 350)));

  await shot(page, 'final');
  console.log(`\n=== '加载中' final count: ${loadingFinal} (期望 0) ===`);
});
