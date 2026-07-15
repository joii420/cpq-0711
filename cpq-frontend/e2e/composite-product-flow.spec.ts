/**
 * 2026-05-20: 组合产品流程 E2E 测试 (v1.16)
 * 报价单管理 → 新建 → 罗克韦尔 + 模板 v1.16
 *   → 添加产品 → 选配添加 → 组合产品 (2 配件)
 *   → 配件1: existing 料号 10110002 + 工序总装配/部件装配/电镀
 *   → 配件2: custom 无匹配 + 选 AgCu90 银铜合金 + 工序总装配/部件装配/电镀
 *   → 组合工艺: 铆接 (用户原话"铆钉")
 *   → 确认添加
 *
 * 注意: 用户原话"组合工序选 (铆钉+电镀)" — 实际数据:
 *   - 铆钉 = 组合工艺库 RIVET (铆接) ✅
 *   - 电镀 = 普通工序 MRO-LP-0001 (SURFACE_TREATMENT) — 不在组合工艺库
 * 因此电镀加在每个配件的 P3 工序里 (Step3Process), 组合工艺只选铆接.
 *
 * ══════════════════════════════════════════════════════════════════════════
 * ⚠️ 2026-07-15 task-0712 F6(E2E 验证) 停用说明 — 本 spec 已确认对当前代码失效，暂 skip：
 *
 * 1) **UI 层全部过时**：task-0712 F5(commit 590ab6f)把选配添加抽屉从本文件依赖的
 *    P0(独立产品/组合产品)→P1(搜料号)→P2(材质)→P3(工序)→P4(组合工艺)→
 *    StepAccessoryQuantity(配件数量)→P5(摘要) 逐步向导**整体废弃**（Step0~5 8 个文件已
 *    `git rm`），改写为单屏「明细表(SelDetailTable) + 内层子框(AddPartSubDrawer 材质→
 *    元素含量→工序 3 步) + 组合工艺条件区(CompositeProcessSection)」模型（D11）。
 *    本文件用到的 `drawerNext()`(点 .ant-drawer 内"下一步")、`pickProcess()`(找
 *    ant-list-item + 内嵌"添加"按钮)、"独立产品/组合产品"卡片选择、配件数量步骤等
 *    选择器在新 UI 里全部不存在。
 * 2) **数据契约也变了**：组合工艺标识锚点从 `composite_process_def.code`(如 "RIVET")
 *    切到 `process_master.process_no`(task-0712 B6，如 "MRO-AS-0001")；`processIds`
 *    (UUID)已改 `processNos`(process_master.process_no 字符串，task-0712 缺口1)。
 * 3) **Tab 命名体系不同源**：本文件断言的 `选配-材质`/`选配-工序列表`/`选配-元素含量`/
 *    `选配-组合工艺`/`选配-总成本` 是旧「组合产品 v1.16」模板的自定义 Tab 命名，与当前
 *    V6 落库改造(task-0712 B2)后台账体系（来料/元素/自制加工费等，见
 *    quotation-flow.spec.ts 现役断言）不是同一套；"组合产品 v1.16" 模板在当前共享
 *    DB 是否仍存在/仍可用未核实。
 *
 * **重写指引**（供后续任务）：参照 `quotation-flow.spec.ts` 里 2026-07-15 改写的
 * "选配添加" 段落（新增材质料号 → 材质卡片选 code → 元素含量确认 → 工序勾选 →
 * 确认添加 → 明细表数量合计）为基础，在明细表新增 ≥2 行材质料号（或单行 quantity=2，
 * 对齐架构评审决策1"Σqty≥2"）触发 `CompositeProcessSection` 出现后，勾选一个
 * `process_master.process_category='ASSEMBLY'` 候选，再点「确认加入」，最后按当前
 * 报价单模板的真实 Tab 命名断言渲染（不要硬编码"选配-*"旧命名）。
 * ══════════════════════════════════════════════════════════════════════════
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
  const file = path.join(SHOT_DIR, `cpf-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
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

/** 点选配抽屉内的下一步按钮 (last 避开主页面 next) */
async function drawerNext(page: Page) {
  const btn = page.locator('.ant-drawer button:has-text("下一步")').last();
  await btn.click();
  await page.waitForTimeout(800);
}

/** 给当前配件点击工序"+ 添加"按钮 */
async function pickProcess(page: Page, name: string) {
  const row = page.locator('.ant-drawer .ant-list-item').filter({ hasText: name }).first();
  const addBtn = row.locator('button').filter({ hasText: '添加' }).first();
  const c = await addBtn.count();
  if (c > 0) {
    await addBtn.click();
    console.log(`  [process] + ${name}`);
    await page.waitForTimeout(300);
  } else {
    console.log(`  [process] NOT FOUND: ${name}`);
  }
}

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

test('组合产品: 罗克韦尔 + v1.16 + 配件1(10110002 existing) + 配件2(custom AgCu90) + 组合工艺(铆接)', async ({ page }) => {
  // task-0712 F6：UI/数据契约已被 F5 明细表重构 + B6 组合工艺双轨收敛整体废弃，见文件头注释。
  // 待重写前统一 skip，避免在 CI/回归里长期显示为"失败"掩盖真正的新回归。
  test.skip(true, 'task-0712 F5 明细表重构后本 spec 选择器/Tab 命名全部过时，待按文件头注释重写(见 dev-docs/task-0712-选配模板和报价单选配功能/)');
  test.skip(!backendUp, '后端未启动');

  const consoleErrors: string[] = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('PAGE-ERROR: ' + e.message));

  // ── 1) 登录 + 进入新建报价单 ──
  await loginAsAdmin(page);
  await page.goto('/quotations/new');
  await page.waitForLoadState('networkidle');
  await shot(page, 'step1-init');

  // ── 2) Step1: 客户 + 名称 + 分类 + 模板 v1.16 ──
  await selectByLabel(page, '客户', '罗克韦尔');
  await page.locator('text=产品分类').first().waitFor({ state: 'visible', timeout: 10000 }).catch(() => {});
  await page.locator('text=产品分类').first().scrollIntoViewIfNeeded().catch(() => {});
  await page.waitForTimeout(500);

  await page.locator('input[placeholder*="报价单名称"]').first().fill('E2E-composite-' + Date.now());
  await selectByLabel(page, '产品分类', '默认分类');
  await page.waitForTimeout(1200);
  // 注意精确匹配 v1.16 — 同名模板有多版本
  await selectByLabel(page, '报价模板', '组合产品 v1.16', '组合产品 v1.16');
  await shot(page, 'step1-template-v1.16');

  // 下一步 → Step2
  await page.getByRole('button', { name: /下一步/ }).first().click();
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  await shot(page, 'step2-empty');
  await countLoading(page, 'step2-empty');

  // ── 3) Step2: 点击"+ 添加产品" → "选配添加" ──
  await page.getByRole('button', { name: /添加产品/ }).first().click();
  await page.waitForTimeout(500);
  await page.locator('text=选配添加').first().click();
  await page.waitForTimeout(800);
  await shot(page, 'drawer-step0-product-type');

  // ── 4) Step 0: 选组合产品 (配件数默认 2) ──
  await page.locator('.ant-drawer').locator('text=组合产品').first().click();
  await page.waitForTimeout(400);
  await shot(page, 'step0-composite-selected');
  await drawerNext(page);
  await shot(page, 'step1-part1-search');

  // ── 5) 配件 1: P1 料号匹配 10110002 ──
  const partInput = page.locator('.ant-drawer input[placeholder*="料号"]').first();
  await partInput.fill('10110002');
  await page.waitForTimeout(1500);
  await shot(page, 'part1-search-result');
  // 选第一个匹配的 ant-list-item (10110002 行)
  await page.locator('.ant-drawer .ant-list-item').filter({ hasText: '10110002' }).first().click();
  await page.waitForTimeout(500);
  await shot(page, 'part1-selected-existing');

  // 配件 1: 下一步 → P2 材质 (existing 应该自动锁定 AgCu90)
  await drawerNext(page);
  await shot(page, 'part1-step2-material-locked');
  await countLoading(page, 'part1-step2-material');

  // 下一步 → P3 工序选择
  await drawerNext(page);
  await shot(page, 'part1-step3-process');

  // 配件 1 工序: 总装配 + 部件装配 + 电镀
  await pickProcess(page, '总装配');
  await pickProcess(page, '部件装配');
  await pickProcess(page, '电镀');
  await shot(page, 'part1-processes-picked');

  // 下一步 → 切换到配件 2
  await drawerNext(page);
  await shot(page, 'part2-step1-search');

  // ── 6) 配件 2: P1 料号匹配 - 选"无匹配料号,进入自定义材质选配" ──
  // 找含该文字的 Card (Step1SearchPart L111 渲染的)
  const customCard = page.locator('.ant-drawer').locator('text=无匹配料号').first();
  await customCard.click();
  await page.waitForTimeout(500);
  await shot(page, 'part2-custom-selected');

  // 下一步 → P2 材质 (custom 路径)
  await drawerNext(page);
  await shot(page, 'part2-step2-material-list');
  await countLoading(page, 'part2-step2-material');

  // ── 7) 配件 2 P2: 在材质列表选 AgCu90 ──
  // ant-list-item 含 'AgCu' + '90/10'
  const agcuRow = page.locator('.ant-drawer .ant-list-item').filter({ hasText: 'AgCu' }).filter({ hasText: '90/10' }).first();
  await agcuRow.click();
  await page.waitForTimeout(800);
  await shot(page, 'part2-agcu90-selected');

  // 下一步 → P3 工序
  await drawerNext(page);
  await shot(page, 'part2-step3-process');

  await pickProcess(page, '总装配');
  await pickProcess(page, '部件装配');
  await pickProcess(page, '电镀');
  await shot(page, 'part2-processes-picked');

  // 下一步 → 新增「配件数量」步骤（仅 COMPOSITE）
  await drawerNext(page);
  await shot(page, 'accessory-quantity-step');
  // 把配件 1 的数量改为 3（验证 composition_qty 落库）
  const qtyInput = page.locator('.ant-drawer .ant-card').first().locator('input.ant-input-number-input').first();
  await qtyInput.fill('3');
  await page.waitForTimeout(300);
  await shot(page, 'accessory-quantity-set-3');

  // 下一步 → 组合工艺
  await drawerNext(page);
  await shot(page, 'composite-step2-process-init');

  // ── 8) Step 2 组合工艺: 选铆接 (用户原话"铆钉" → 实际为 RIVET 铆接) ──
  // 点左侧工艺库 Card "铆接"
  await page.locator('.ant-drawer').locator('text=铆接').first().click();
  await page.waitForTimeout(500);
  await shot(page, 'composite-rivet-added');

  // 下一步 → Step 3 完成选配
  await drawerNext(page);
  await shot(page, 'step3-summary');

  // ── 9) 确认添加 ──
  const confirmBtn = page.locator('.ant-drawer button').filter({ hasText: /确认添加|完成选配|提交/ }).last();
  if (await confirmBtn.isVisible().catch(() => false)) {
    await confirmBtn.click();
    console.log('✓ 已点击 确认添加');
  } else {
    // 兜底再点一次"下一步" — 可能 Step 3 摘要后还需要 next
    await drawerNext(page);
  }
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3500);
  await shot(page, 'after-confirm');

  // ── 10) 最终报价单 step2 渲染验证 ──
  await page.waitForTimeout(2000);
  await shot(page, 'final-quotation-step2');
  await countLoading(page, 'final');

  // 滚到产品 1 卡片
  await page.locator('text=产品 1').first().scrollIntoViewIfNeeded().catch(() => {});
  await page.waitForTimeout(1500);

  // 统计 Tab (CPQ 用 qt-tab-btn 自定义按钮)
  const tabs = page.locator('button.qt-tab-btn');
  const tabCount = await tabs.count();
  console.log(`\n=== Tab 总数 (qt-tab-btn): ${tabCount} ===`);
  for (let i = 0; i < tabCount; i++) {
    const t = await tabs.nth(i).innerText().catch(() => '?');
    console.log(`  Tab[${i}]: "${t.trim()}"`);
  }

  // v1.16: 5 个"选配-*" Tab (关心度顺序: 材质→工序→元素→组合工艺→总成本)
  // 注意: "选配-总成本" 是 SUBTOTAL 类型，可能渲染为 Tab 也可能渲染为底部小计条
  const expected = ['选配-材质', '选配-工序列表', '选配-元素含量', '选配-组合工艺', '选配-总成本'];

  // expectedRowCount: 每个 Tab 期望的最少数据行数
  const expectedRowCount: Record<string, number> = {
    '选配-材质':    2,  // 子件 10110002 + CFG-AgCu-xxxxx
    '选配-工序列表': 6,  // 2 子件 × 3 工序
    '选配-元素含量': 2,  // AgCu90 的 Ag 90% + Cu 10%（至少）
    '选配-组合工艺': 1,  // 1 行 RIVET
    '选配-总成本':   4,  // 材料成本 + 加工费 + 组合工艺费 + 产品总成本
  };

  // 先检查每个 Tab 是否存在（或"选配-总成本"以底部条形式出现）
  for (const name of expected) {
    const c = await tabs.filter({ hasText: new RegExp(`^${name}$`) }).count();
    console.log(`  expect '${name}': ${c > 0 ? '✅ Tab 存在' : '❌ MISSING (可能为底部条)'}`);
  }
  const subtotalBar = await page.locator('text=产品小计').count();
  console.log(`  '产品小计' 底部条: ${subtotalBar > 0 ? '✅' : '❌'}`);

  // 逐 Tab 切换截图 + 内容断言
  for (const name of expected) {
    const tab = tabs.filter({ hasText: new RegExp(`^${name}$`) }).first();
    const tabVisible = await tab.isVisible().catch(() => false);

    if (!tabVisible) {
      // "选配-总成本" 若不渲染为 Tab，单独断言底部小计条非 0
      if (name === '选配-总成本') {
        console.log(`  [Tab '${name}'] 非 Tab 按钮，改断言底部小计条`);
        const subtotalCount = await page.locator('text=产品小计').count();
        console.log(`    '产品小计' 底部条: ${subtotalCount > 0 ? '✅' : '❌'}`);
        expect(subtotalCount).toBeGreaterThan(0);
      } else {
        console.log(`  [Tab '${name}'] ❌ 不可见，跳过`);
      }
      continue;
    }

    await tab.click();
    await page.waitForTimeout(2200);
    await shot(page, `tab-${name}`);

    // ── 加载中 = 0 ──
    const loading = await countLoading(page, `tab-${name}`);
    expect(loading).toBe(0);

    // ── 行数 ≥ 期望值 ──
    const rows = page.locator('.qt-cost-table tbody tr');
    const rowCount = await rows.count();
    console.log(`  [${name}] rows=${rowCount}, expected>=${expectedRowCount[name]}, '加载中'=${loading}`);
    expect(rowCount).toBeGreaterThanOrEqual(expectedRowCount[name]);

    // ── 关键列非空（取 row[0] 的所有 td，过滤空/占位符） ──
    const cells = await rows.first().locator('td').allInnerTexts();
    const nonEmptyCells = cells.filter(c => {
      const t = c.trim();
      return t !== '' && t !== '—' && t !== '...' && t !== '加载中';
    });
    console.log(`    row[0] cells(${cells.length}): ${cells.map(c => `"${c.trim().slice(0, 25)}"`).join(' | ')}`);
    console.log(`    non-empty cells: ${nonEmptyCells.length}`);
    expect(nonEmptyCells.length).toBeGreaterThan(0);

    // ── 打印前 3 行用于调试 ──
    for (let i = 0; i < Math.min(rowCount, 3); i++) {
      const r = await rows.nth(i).locator('td').allInnerTexts();
      console.log(`    row[${i}]: ${r.map(c => `"${c.trim().slice(0, 30)}"`).join(' | ')}`);
    }

    // ── 子件列断言（3 个 NORMAL Tab 须含两个子件料号） ──
    if (['选配-材质', '选配-元素含量', '选配-工序列表'].includes(name)) {
      const allText = await page.locator('.qt-cost-table').first().innerText().catch(() => '');
      console.log(`  [${name}] 检查子件列: 含 10110002=${allText.includes('10110002')}, 含 CFG-AgCu=${/CFG-AgCu/.test(allText)}`);
      expect(allText).toContain('10110002');
      expect(allText).toMatch(/CFG-AgCu/);
    }

    // ── 选配-组合工艺: 参与配件断言 ──
    if (name === '选配-组合工艺') {
      const allText = await page.locator('.qt-cost-table').first().innerText().catch(() => '');
      console.log(`  [${name}] 检查参与配件: 含 10110002=${allText.includes('10110002')}, 含 CFG-AgCu=${/CFG-AgCu/.test(allText)}`);
      expect(allText).toContain('10110002');
      expect(allText).toMatch(/CFG-AgCu/);
    }
  }

  console.log(`\n=== console.error 总数: ${consoleErrors.length} ===`);
  consoleErrors.slice(0, 10).forEach(e => console.log('  ERR: ' + e.slice(0, 200)));

  await shot(page, 'final');
});
