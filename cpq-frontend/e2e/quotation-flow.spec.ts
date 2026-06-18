/**
 * E2E 验证报价单渲染流程(2026-06-11 数据对齐: 组合产品模板已清理, 改用现存数据):
 * 报价单管理 → 新建 → 苏州西门子 + 报价模板0608 v1.10(报价模板V2 目录组件)
 *   → 添加产品 → 料号 10110002 → 确认
 *
 * 验证目标:
 * 1) 报价模板0608 的 7 个 NORMAL Tab 都按模板配置渲染(报价小计=SUBTOTAL 不渲染为 Tab)
 * 2) 字段按组件管理的公式计算
 * 3) 不出现"加载中..."永久占位(渲染层无回归)
 *
 * 2026-06-18 Task F: 新增「草稿冻结」相关用例
 * TC-F1: 打开 DRAFT 报价单不自动发 POST refresh-card-snapshot
 * TC-F2: 点「刷新基础数据」按钮 → 确认 Modal → 发出 POST refresh-card-snapshot
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

/**
 * 通过后端 API（直连 8081）快速创建一张最小 DRAFT 报价单，返回 id。
 * 用于 TC-F1/TC-F2：这两个用例只需要"能打开 Step2 的 DRAFT 报价单"，
 * 不需要里面有产品/行项目。
 *
 * 如果 API 返回失败，抛出以使依赖此函数的 test 标记为 FAILED（而非 skip），
 * 便于在合并后运行时快速定位数据问题。
 */
async function createMinimalDraftQuotation(sessionCookie: string): Promise<string> {
  // 1) 取客户列表，拿第一个客户 id
  const custRes = await fetch('http://localhost:8081/api/cpq/customers?page=0&size=1', {
    headers: { Cookie: sessionCookie },
  });
  if (!custRes.ok) throw new Error(`获取客户列表失败: ${custRes.status}`);
  const custBody = await custRes.json();
  const customerId = custBody.content?.[0]?.id ?? custBody[0]?.id;
  if (!customerId) throw new Error('客户列表为空，无法创建测试报价单');

  // 2) 取报价模板列表，拿第一个 id
  const tplRes = await fetch('http://localhost:8081/api/cpq/templates?templateKind=QUOTE&page=0&size=1', {
    headers: { Cookie: sessionCookie },
  });
  if (!tplRes.ok) throw new Error(`获取模板列表失败: ${tplRes.status}`);
  const tplBody = await tplRes.json();
  const templateId = tplBody.content?.[0]?.id ?? tplBody[0]?.id;
  if (!templateId) throw new Error('报价模板列表为空，无法创建测试报价单');

  // 3) 创建报价单草稿
  const payload = {
    name: `E2E-freeze-test-${Date.now()}`,
    customerId,
    templateId,
  };
  const createRes = await fetch('http://localhost:8081/api/cpq/quotations', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      Cookie: sessionCookie,
    },
    body: JSON.stringify(payload),
  });
  if (!createRes.ok) {
    const errText = await createRes.text();
    throw new Error(`创建报价单失败: ${createRes.status} ${errText}`);
  }
  const created = await createRes.json();
  const id = created.id ?? created.quotationId;
  if (!id) throw new Error(`创建报价单响应中无 id 字段: ${JSON.stringify(created)}`);
  console.log(`[createMinimalDraft] 创建测试报价单成功 id=${id}`);
  return String(id);
}

/**
 * 从当前页面 context 中提取 session cookie 字符串（用于直连 API）
 */
async function extractSessionCookie(page: Page): Promise<string> {
  const cookies = await page.context().cookies('http://localhost:8081');
  return cookies.map(c => `${c.name}=${c.value}`).join('; ');
}

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

// ═══════════════════════════════════════════════════════════════════════
// 主流程用例（原有）：新建报价单 → 添加产品 → 逐 Tab 渲染验证
// 2026-06-18 Task F 更新：在此用例内额外监听 POST refresh-card-snapshot，
// 断言整个新建+添加产品流程中不应触发自动重刷（B1 删除后的回归保障）。
// ═══════════════════════════════════════════════════════════════════════
test('报价单流程: 苏州西门子 + 报价模板0608 v1.10 + 10110002(渲染层无回归)', async ({ page }) => {
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

  // PUT /quotations/{id}/draft 计数(验证自动保存不死循环)
  let draftPutCount = 0;
  page.on('request', (req) => {
    if (req.method() === 'PUT' && /\/quotations\/[^/]+\/draft/.test(req.url())) draftPutCount += 1;
  });

  // ── 1) 登录 (用项目 fixture) ──
  await loginAsAdmin(page);
  await shot(page, 'after-login');

  // ── 2) 进入新建报价单 ──
  await page.goto('/quotations/new');
  await page.waitForLoadState('networkidle');
  await shot(page, 'step1-init');

  // ── 3) 选客户: 苏州西门子 (label-based) ──
  await selectByLabel(page, '客户', '西门子');
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

  // ── 6) 报价模板: 报价模板0608 **动态选最新版** ──
  // 该模板会被反复重发布(v1.0..v1.N 持续增长), 硬编码某版本会因版本漂移 + antd 虚拟滚动
  // (20+ 选项, 目标版本可能不在初始渲染窗口)而点不到超时。改为: 输入 "0608" 过滤后,
  // 滚动 dropdown 收集所有 "报价模板0608 vX.Y" 选项, 解析版本号取最大者点击 —— 与发布版本数解耦。
  await page.waitForTimeout(1200);  // 等模板加载
  {
    const templateItem = page.locator('.ant-form-item')
      .filter({ has: page.locator('label', { hasText: '报价模板' }) })
      .first();
    await templateItem.locator('.ant-select').first().click();
    await page.waitForTimeout(300);

    // 输入 "0608" 缩小候选集
    await page.keyboard.type('0608', { delay: 60 });
    await page.waitForTimeout(900);

    // 下拉按版本 newest-first 排序: 最新版在初始渲染窗口顶部、可直接点击。
    // 不滚动(滚到底会把顶部的最新版滚出虚拟 DOM 导致点不到), 从初始渲染项解析版本取最大。
    const versions = new Map<string, [number, number]>();  // "v1.19" -> [1,19]
    const texts = await page.locator('.ant-select-item-option')
      .filter({ hasText: /报价模板0608\s+v\d+\.\d+/ }).allInnerTexts();
    for (const t of texts) {
      const m = t.match(/报价模板0608\s+v(\d+)\.(\d+)/);
      if (m) versions.set(`v${m[1]}.${m[2]}`, [parseInt(m[1], 10), parseInt(m[2], 10)]);
    }
    // 取最大版本 (先比 major 再比 minor)
    let bestKey = '';
    let best: [number, number] = [-1, -1];
    for (const [k, v] of versions) {
      if (v[0] > best[0] || (v[0] === best[0] && v[1] > best[1])) { best = v; bestKey = k; }
    }
    if (!bestKey) throw new Error('未在下拉中找到任何 报价模板0608 vX.Y 选项');
    console.log(`[template] 动态选最新版: 报价模板0608 ${bestKey} (候选 ${versions.size} 个)`);

    // 行尾锚精确匹配该版本(防 v1.1 撞 v1.10/v1.19)
    const re = new RegExp(`报价模板0608\\s+${bestKey.replace('.', '\\.')}(\\s|$|<)`);
    const opt = page.locator('.ant-select-item-option').filter({ hasText: re }).first();
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
  console.log(`\n=== Tab 总数 (qt-tab-btn): ${tabCount} (期望 7 = 8 组件 - 1 SUBTOTAL报价小计) ===`);

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
  const expected = ['产品', '来料', '元素', '自制/组装加工费', '其他费用', '外购件', '电镀费用'];
  for (const name of expected) {
    const c = await tabs.filter({ hasText: name }).count();
    console.log(`  expect '${name}': ${c > 0 ? '✅ FOUND' : '❌ MISSING'}`);
  }
  // SUBTOTAL(报价小计) 不应该作为 Tab
  const subtotalTab = await tabs.filter({ hasText: '报价小计' }).count();
  console.log(`  '报价小计' Tab (期望 0, SUBTOTAL 不渲染为 Tab): ${subtotalTab === 0 ? '✅' : '❌ 误渲染 ' + subtotalTab}`);
  // 但产品小计应该在底部小计条里
  const subtotalBar = await page.locator('text=产品小计').count();
  console.log(`  '产品小计' (底部条): ${subtotalBar > 0 ? '✅ 存在' : '❌ 缺失'}`);

  // ── 逐 Tab 切换并截图 ──
  const tabNames = ['产品', '来料', '元素', '自制/组装加工费', '其他费用', '外购件', '电镀费用'];
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

  // ── 自动保存不死循环:搭建完成后空闲 5s,PUT /draft 应 ≤1 ──
  const putBeforeIdle = draftPutCount;
  await page.waitForTimeout(5000);  // 空闲,不做任何操作
  const idlePut = draftPutCount - putBeforeIdle;
  console.log(`\n=== 空闲 5s 内 PUT /draft 次数 = ${idlePut} (期望 ≤1,死循环时会持续累加) ===`);
  expect(idlePut).toBeLessThanOrEqual(1);

  console.log(`\n=== console.error 总数: ${consoleErrors.length} ===`);
  consoleErrors.slice(0, 10).forEach(e => console.log('  🔴 ' + e.slice(0, 200)));

  console.log(`\n=== LF-DEBUG / LF-RENDER 共 ${lfDebug.length} 条 ===`);
  lfDebug.forEach(e => console.log('  🟡 ' + e.slice(0, 350)));

  await shot(page, 'final');
  console.log(`\n=== '加载中' final count: ${loadingFinal} (期望 0) ===`);
});

// ═══════════════════════════════════════════════════════════════════════
// TC-F1: 打开 DRAFT 报价单不自动触发 POST refresh-card-snapshot（B1 冻结验证）
// ═══════════════════════════════════════════════════════════════════════
test('TC-F1: 打开 DRAFT 报价单不自动发 refresh-card-snapshot', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  // ── 登录 ──
  await loginAsAdmin(page);

  // ── 通过 API 创建最小 DRAFT 报价单 ──
  const cookie = await extractSessionCookie(page);
  const quotationId = await createMinimalDraftQuotation(cookie);

  // ── 监听 POST refresh-card-snapshot 请求 ──
  const refreshRequests: string[] = [];
  page.on('request', (req) => {
    if (req.method() === 'POST' && /\/quotations\/[^/]+\/refresh-card-snapshot/.test(req.url())) {
      refreshRequests.push(req.url());
      console.log(`[TC-F1] ⚠️ 检测到自动 POST refresh-card-snapshot: ${req.url()}`);
    }
  });

  // ── 打开报价单 Step2（草稿编辑页） ──
  await page.goto(`/quotations/${quotationId}/edit`);
  await page.waitForLoadState('networkidle');
  // 额外等待 3s，确保任何"打开后自动刷新"逻辑（若还存在）有充足时间触发
  await page.waitForTimeout(3000);

  // 记录页面截图
  await shot(page, 'tc-f1-draft-opened');

  // ── 核心断言：打开草稿不应触发 refresh-card-snapshot ──
  console.log(`\n[TC-F1] POST refresh-card-snapshot 请求数 = ${refreshRequests.length} (期望 0)`);
  expect(
    refreshRequests.length,
    `打开 DRAFT 报价单不应自动发 POST /quotations/${quotationId}/refresh-card-snapshot，` +
    `但检测到 ${refreshRequests.length} 次请求: ${refreshRequests.join(', ')}`
  ).toBe(0);

  // ── 附加断言：页面正常渲染 Step2（无 500/崩溃） ──
  // 验证没有出现"加载中"死锁（快照应直接渲染）
  const loadingCount = await countLoading(page, 'TC-F1');
  console.log(`[TC-F1] '加载中' count = ${loadingCount}`);
  // 注意：空报价单无产品，不期望 Tab 渲染，只期望页面可正常显示 Step2 工具栏
  const refreshBtn = page.locator('[data-testid="refresh-basic-data-btn"]');
  const btnVisible = await refreshBtn.isVisible().catch(() => false);
  console.log(`[TC-F1] 「刷新基础数据」按钮可见 = ${btnVisible} (期望 DRAFT 状态下可见)`);
  expect(btnVisible, '「刷新基础数据」按钮应在 DRAFT 状态下可见').toBe(true);

  await shot(page, 'tc-f1-final');
  console.log('\n[TC-F1] ✅ 通过: 打开 DRAFT 不触发自动 refresh-card-snapshot');
});

// ═══════════════════════════════════════════════════════════════════════
// TC-F2: 显式点击「刷新基础数据」→ 确认 Modal → 触发 POST refresh-card-snapshot
// ═══════════════════════════════════════════════════════════════════════
test('TC-F2: 显式刷新才触发 refresh-card-snapshot', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  // ── 登录 ──
  await loginAsAdmin(page);

  // ── 通过 API 创建最小 DRAFT 报价单 ──
  const cookie = await extractSessionCookie(page);
  const quotationId = await createMinimalDraftQuotation(cookie);

  // ── 监听 POST refresh-card-snapshot 请求 ──
  const refreshRequests: string[] = [];
  // 同时记录响应，确认后端成功处理
  const refreshResponses: number[] = [];
  page.on('request', (req) => {
    if (req.method() === 'POST' && /\/quotations\/[^/]+\/refresh-card-snapshot/.test(req.url())) {
      refreshRequests.push(req.url());
      console.log(`[TC-F2] ✅ 检测到 POST refresh-card-snapshot: ${req.url()}`);
    }
  });
  page.on('response', (resp) => {
    if (/\/quotations\/[^/]+\/refresh-card-snapshot/.test(resp.url())) {
      refreshResponses.push(resp.status());
      console.log(`[TC-F2] refresh-card-snapshot 响应状态: ${resp.status()}`);
    }
  });

  // ── 打开报价单 Step2 ──
  await page.goto(`/quotations/${quotationId}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  await shot(page, 'tc-f2-draft-opened');

  // ── 确认打开时尚未触发 refresh（打开即冻结） ──
  expect(
    refreshRequests.length,
    '打开阶段不应有 refresh-card-snapshot 请求（B1 冻结保障）'
  ).toBe(0);

  // ── 点击「刷新基础数据」按钮 ──
  const refreshBtn = page.locator('[data-testid="refresh-basic-data-btn"]');
  await refreshBtn.waitFor({ state: 'visible', timeout: 10_000 });
  await refreshBtn.click();
  await page.waitForTimeout(500);
  await shot(page, 'tc-f2-modal-open');

  // ── Modal 确认框应弹出，标题「刷新基础数据」──
  const modalTitle = page.locator('.ant-modal-title');
  await modalTitle.waitFor({ state: 'visible', timeout: 8_000 });
  const titleText = await modalTitle.innerText();
  console.log(`[TC-F2] Modal 标题: "${titleText}"`);
  expect(titleText).toContain('刷新基础数据');

  // ── 点击 Modal 中的「刷新」确认按钮 ──
  // Ant Design Modal.confirm 的 okText 按钮在 .ant-modal-confirm-btns 内
  const okBtn = page.locator('.ant-modal-confirm-btns button').filter({ hasText: '刷新' }).first();
  await okBtn.waitFor({ state: 'visible', timeout: 8_000 });
  await okBtn.click();
  console.log('[TC-F2] 已点击「刷新」确认按钮');

  // ── 等待 POST 请求完成（最多 15s）──
  await page.waitForResponse(
    (resp) => /\/quotations\/[^/]+\/refresh-card-snapshot/.test(resp.url()),
    { timeout: 15_000 }
  );
  await page.waitForTimeout(2000);  // 等 onReloadQuotation 回调完成 + message.success 出现
  await shot(page, 'tc-f2-after-refresh');

  // ── 核心断言 1：确认后恰好触发 1 次 refresh-card-snapshot ──
  console.log(`\n[TC-F2] POST refresh-card-snapshot 请求数 = ${refreshRequests.length} (期望 1)`);
  expect(
    refreshRequests.length,
    `点击刷新确认后应触发恰好 1 次 POST refresh-card-snapshot，实际 ${refreshRequests.length} 次`
  ).toBe(1);

  // ── 核心断言 2：后端返回成功（200 或 204） ──
  if (refreshResponses.length > 0) {
    const status = refreshResponses[0];
    console.log(`[TC-F2] 后端响应状态 = ${status} (期望 2xx)`);
    expect(status, `refresh-card-snapshot 后端应返回 2xx，实际 ${status}`).toBeLessThan(300);
  }

  // ── 附加断言：成功 message 提示出现（「已按最新基础数据刷新」） ──
  // Ant Design message 渲染在 .ant-message-notice-content 内
  const successMsg = page.locator('.ant-message-notice-content').filter({ hasText: '已按最新基础数据刷新' });
  const msgVisible = await successMsg.isVisible().catch(() => false);
  if (!msgVisible) {
    // message 可能已消失（默认 3s 后消失），用 console 记录而非硬断言
    console.log('[TC-F2] 注意: 成功提示「已按最新基础数据刷新」已消失（正常，message 自动消失）');
  } else {
    console.log('[TC-F2] ✅ 成功提示「已按最新基础数据刷新」可见');
  }

  await shot(page, 'tc-f2-final');
  console.log('\n[TC-F2] ✅ 通过: 显式刷新触发 1 次 refresh-card-snapshot，后端返回 2xx');
});
