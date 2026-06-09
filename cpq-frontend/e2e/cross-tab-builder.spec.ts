/**
 * E2E: CrossTabRefDrawer 可视化构建器 UI 验证 (Phase 1 Task 6)
 *
 * 覆盖范围（全程只读，不点保存，不写库）:
 *   1. 抽屉标题显示「跨页签公式构建器」+ 简单/高级 Segmented 可见
 *   2. 简单模式: 「要算什么」操作选择器仅含「取一个值」「求和」，无「计数」「平均」「最大」「最小」
 *   3. 切到高级模式: 操作选择器额外出现「计数」，且「原始公式文本」textarea 可见
 *   4. 读取方向同步: 高级模式下选好源/匹配/操作「求和」/目标列后，
 *      rawText textarea 自动以「求和 | 源:」开头 (serializeCrossTab)
 *   5. 「加载中」count = 0，无 cross_tab 业务级 console.error
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

const TARGET_COMPONENT_NAME = '生产费用';   // COMP-0002，同目录含 10 个兄弟组件
const SOURCE_COMPONENT_NAME = '投料成本';   // COMP-0001，兄弟 A 组件

let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `ctb-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}

let backendUp = false;

test.beforeAll(async () => {
  backendUp = await isBackendUp();
  if (!backendUp) console.log('[beforeAll] 后端未启动，整套 skip');
});

test.beforeEach(async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page);
});

/** 复用 cross-tab-ref.spec.ts 的导航路径，直到抽屉打开后返回 drawer locator */
async function navigateToDrawer(page: Page) {
  const consoleErrors: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text());
  });

  // 1) 进入组件管理
  await page.goto('/components-raw');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  await shot(page, 'component-mgmt');

  // 2) 展开左侧组件树，选中目标组件
  const tree = page.locator('.cm-tree-container');
  await expect(tree, '组件树容器应可见').toBeVisible({ timeout: 10_000 });
  for (let round = 0; round < 6; round++) {
    const collapsed = tree.locator('.ant-tree-switcher_close');
    const n = await collapsed.count();
    if (n === 0) break;
    for (let i = 0; i < n; i++) {
      const sw = tree.locator('.ant-tree-switcher_close').first();
      if (await sw.isVisible().catch(() => false)) {
        await sw.click().catch(() => {});
        await page.waitForTimeout(120);
      }
    }
    await page.waitForTimeout(300);
  }

  const targetNode = tree
    .locator('.ant-tree-node-content-wrapper')
    .filter({ hasText: new RegExp(`^\\s*${TARGET_COMPONENT_NAME}\\s*$`) })
    .first();
  await expect(targetNode, `组件树应有「${TARGET_COMPONENT_NAME}」节点`).toBeVisible({ timeout: 10_000 });
  await targetNode.click();
  await page.waitForTimeout(1200);
  await expect(page.locator('.cm-center-title')).toContainText(TARGET_COMPONENT_NAME, { timeout: 8000 });

  // 3) 切到「公式」Tab
  const formulaTab = page
    .locator('.ant-tabs-tab')
    .filter({ hasText: /^公式$/ })
    .first();
  await expect(formulaTab, '应有「公式」Tab').toBeVisible({ timeout: 8000 });
  await formulaTab.click();
  await page.waitForTimeout(800);

  // 4) 激活一条已有公式
  const formulaSection = page.locator('.cm-card-section').filter({ hasText: '公式管理' }).first();
  await expect(formulaSection, '应有公式管理区').toBeVisible({ timeout: 8000 });
  const formulaRow = formulaSection.locator('.ant-table-tbody tr.ant-table-row').first();
  await expect(formulaRow, '生产费用应有已配置的公式行').toBeVisible({ timeout: 8000 });
  await formulaRow.click();
  await page.waitForTimeout(600);
  await expect(formulaSection.locator('text=活动中').first(), '公式行应激活').toBeVisible({ timeout: 6000 });

  // 5) 右侧切到「其他数据源」，点「+ 配置跨页签引用…」
  const rightSubtotalsTab = page.locator('.cm-right-tab', { hasText: '其他数据源' }).first();
  await expect(rightSubtotalsTab, '右侧应有「其他数据源」标签').toBeVisible({ timeout: 8000 });
  await rightSubtotalsTab.click();
  await page.waitForTimeout(500);

  const crossTabBtn = page.locator('button', { hasText: '配置跨页签引用' }).first();
  await expect(crossTabBtn, '应有「+ 配置跨页签引用…」按钮').toBeVisible({ timeout: 8000 });
  await expect(crossTabBtn, '公式已激活，按钮应启用').toBeEnabled();
  await crossTabBtn.click();
  await page.waitForTimeout(1200);

  // 抽屉应打开
  const drawer = page.locator('.ant-drawer').filter({ hasText: '跨页签公式构建器' }).first();
  await expect(drawer, '应弹出「跨页签公式构建器」抽屉').toBeVisible({ timeout: 10_000 });
  await shot(page, 'drawer-open');

  return { drawer, consoleErrors };
}

// ─── 测试 1: 标题 + Segmented + 简单模式操作选项 ─────────────────────────────
test('builder-UI: 标题含「跨页签公式构建器」+ 简单/高级 Segmented + 简单模式操作仅含取一个值/求和', async ({ page }) => {
  const { drawer, consoleErrors } = await navigateToDrawer(page);

  // 1a) 标题
  const titleSpan = drawer.locator('.ant-drawer-title span').filter({ hasText: '跨页签公式构建器' }).first();
  await expect(titleSpan, '抽屉标题应含「跨页签公式构建器」').toBeVisible({ timeout: 6000 });
  await shot(page, 'title-ok');

  // 1b) 简单/高级 Segmented 可见
  const segmented = drawer.locator('.ant-segmented').first();
  await expect(segmented, '简单/高级 Segmented 应可见').toBeVisible({ timeout: 6000 });
  // 两个选项文本
  await expect(segmented.locator('text=简单'), '简单 option 应可见').toBeVisible({ timeout: 4000 });
  await expect(segmented.locator('text=高级'), '高级 option 应可见').toBeVisible({ timeout: 4000 });
  await shot(page, 'segmented-ok');

  // 2) 简单模式：打开操作 Select，断言选项
  // drawer 内 .ant-select 顺序: [0]=源页签 [1]=A.列 [2]=本.列 [3]=操作 [4]=目标列
  // 简单模式无公式子 select，操作 select = index 3
  const opSelect = drawer.locator('.ant-select').nth(3);
  await opSelect.click();
  await page.waitForTimeout(400);

  // 选项渲染在 portal — 取所有可见 .ant-select-item-option-content 文本
  const optionTexts = await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option-content')
    .allInnerTexts();
  console.log('[simple mode] 操作 select 选项:', optionTexts);

  // 简单模式应含
  expect(optionTexts, '简单模式应含「取一个值」').toContain('取一个值');
  expect(optionTexts, '简单模式应含「求和」').toContain('求和');
  // 简单模式不应含高级选项
  expect(optionTexts, '简单模式不应含「计数」').not.toContain('计数');
  expect(optionTexts, '简单模式不应含「平均」').not.toContain('平均');
  expect(optionTexts, '简单模式不应含「最大」').not.toContain('最大');
  expect(optionTexts, '简单模式不应含「最小」').not.toContain('最小');

  await shot(page, 'simple-mode-options');
  // 关闭下拉（点 Escape 或点 drawer 外）
  await page.keyboard.press('Escape');
  await page.waitForTimeout(200);

  // 加载中=0
  const loading = await page.locator('text=加载中').count();
  console.log(`[final] '加载中' count = ${loading}`);
  expect(loading, '不应有「加载中」残留').toBe(0);

  // 无跨页签业务 console.error
  const bizErrors = consoleErrors.filter(
    (e) =>
      !/antd|deprecated|React Router|Warning:/.test(e) &&
      /cross_tab|crossTab|formulaEngine|FormulaZone|undefined is not/.test(e)
  );
  expect(bizErrors, `不应有 cross_tab 业务 console.error: ${bizErrors.join(' | ')}`).toHaveLength(0);
});

// ─── 测试 2: 切高级模式 → 「计数」出现 + textarea 可见 ──────────────────────
test('builder-UI: 切「高级」模式后操作含「计数」且「原始公式文本」textarea 出现', async ({ page }) => {
  const { drawer, consoleErrors } = await navigateToDrawer(page);

  // 点「高级」Segmented 选项
  const advancedOption = drawer.locator('.ant-segmented-item').filter({ hasText: '高级' }).first();
  await expect(advancedOption, '高级 Segmented item 应可见').toBeVisible({ timeout: 6000 });
  await advancedOption.click();
  await page.waitForTimeout(500);
  await shot(page, 'advanced-mode-activated');

  // 3a) 操作 Select 应含「计数」
  const opSelect = drawer.locator('.ant-select').nth(3);
  await opSelect.click();
  await page.waitForTimeout(400);

  const optionTexts = await page
    .locator('.ant-select-dropdown:visible .ant-select-item-option-content')
    .allInnerTexts();
  console.log('[advanced mode] 操作 select 选项:', optionTexts);

  expect(optionTexts, '高级模式应含「取一个值」').toContain('取一个值');
  expect(optionTexts, '高级模式应含「求和」').toContain('求和');
  expect(optionTexts, '高级模式应含「计数」').toContain('计数');
  expect(optionTexts, '高级模式应含「平均」').toContain('平均');
  expect(optionTexts, '高级模式应含「最大」').toContain('最大');
  expect(optionTexts, '高级模式应含「最小」').toContain('最小');

  await shot(page, 'advanced-mode-options');
  await page.keyboard.press('Escape');
  await page.waitForTimeout(200);

  // 3b) 「原始公式文本」textarea 应可见（高级模式最后一个元素）
  const rawTextLabel = drawer.locator('text=原始公式文本').first();
  await expect(rawTextLabel, '高级模式应有「原始公式文本」标签').toBeVisible({ timeout: 6000 });

  const rawTextArea = drawer.locator('textarea').first();
  await expect(rawTextArea, '「原始公式文本」textarea 应可见').toBeVisible({ timeout: 6000 });
  await shot(page, 'raw-textarea-visible');

  // 「应用文本」按钮也应存在
  const applyBtn = drawer.locator('button', { hasText: '应用文本' }).first();
  await expect(applyBtn, '「应用文本」按钮应可见').toBeVisible({ timeout: 6000 });

  // 加载中=0
  const loading = await page.locator('text=加载中').count();
  expect(loading, '不应有「加载中」残留').toBe(0);

  const bizErrors = consoleErrors.filter(
    (e) =>
      !/antd|deprecated|React Router|Warning:/.test(e) &&
      /cross_tab|crossTab|formulaEngine|FormulaZone|undefined is not/.test(e)
  );
  expect(bizErrors, `不应有 cross_tab 业务 console.error: ${bizErrors.join(' | ')}`).toHaveLength(0);
});

// ─── 测试 3: 读取方向同步 — 选好源/匹配/求和/目标后 rawText 自动填充 ─────────
test('builder-UI: 高级模式选好源+匹配+求和+目标后 rawText 自动以「求和 | 源:」开头', async ({ page }) => {
  const { drawer, consoleErrors } = await navigateToDrawer(page);

  // 切到高级模式
  const advancedOption = drawer.locator('.ant-segmented-item').filter({ hasText: '高级' }).first();
  await advancedOption.click();
  await page.waitForTimeout(500);

  // 选源组件 (index 0 = 源页签 Select)
  const allSelects = drawer.locator('.ant-select');
  const selCount = await allSelects.count();
  console.log(`[drawer advanced] select 总数 = ${selCount}`);
  expect(selCount, 'drawer 高级模式应至少有 5 个 select').toBeGreaterThanOrEqual(5);

  // 源页签 select (index 0)
  await allSelects.nth(0).click();
  await page.waitForTimeout(400);
  const sourceOption = page
    .locator('.ant-select-item-option')
    .filter({ hasText: SOURCE_COMPONENT_NAME })
    .first();
  await expect(sourceOption, `源页签应含「${SOURCE_COMPONENT_NAME}」`).toBeVisible({ timeout: 8000 });
  await sourceOption.click();
  await page.waitForTimeout(600);
  await shot(page, 'source-selected');

  // A.列 匹配 (index 1)
  await allSelects.nth(1).click();
  await page.waitForTimeout(400);
  const aColOption = page.locator('.ant-select-dropdown:visible .ant-select-item-option').first();
  await expect(aColOption, 'A.列 应有源组件字段').toBeVisible({ timeout: 6000 });
  const aColText = (await aColOption.innerText().catch(() => '')).trim();
  await aColOption.click();
  await page.waitForTimeout(400);

  // 本.列 匹配 (index 2)
  await allSelects.nth(2).click();
  await page.waitForTimeout(400);
  const bColOption = page.locator('.ant-select-dropdown:visible .ant-select-item-option').first();
  await expect(bColOption, '本.列 应有当前组件字段').toBeVisible({ timeout: 6000 });
  await bColOption.click();
  await page.waitForTimeout(400);

  // 操作 select (index 3) — 选「求和」
  await allSelects.nth(3).click();
  await page.waitForTimeout(400);
  const sumOption = page
    .locator('.ant-select-dropdown:visible .ant-select-item-option')
    .filter({ hasText: '求和' })
    .first();
  await expect(sumOption, '操作选项应含「求和」').toBeVisible({ timeout: 6000 });
  await sumOption.click();
  await page.waitForTimeout(400);

  // 目标列 (高级模式下 index 4，select 个数≥5 时最后一个 select 是目标列)
  // 高级模式因无新增 select（useFormula=false），顺序仍是 [0]源 [1]A [2]本 [3]操作 [4]目标
  await allSelects.nth(selCount - 1).click();
  await page.waitForTimeout(400);
  const tgtOption = page.locator('.ant-select-dropdown:visible .ant-select-item-option').first();
  await expect(tgtOption, '目标列应有源组件字段').toBeVisible({ timeout: 6000 });
  await tgtOption.click();
  await page.waitForTimeout(800); // 等待 useEffect 序列化触发
  await shot(page, 'form-filled');

  console.log(`[drawer] A.列 选了「${aColText}」`);

  // 4) rawText textarea 应以「求和 | 源:」开头
  const rawTextArea = drawer.locator('textarea').first();
  await expect(rawTextArea, '「原始公式文本」textarea 应可见').toBeVisible({ timeout: 6000 });

  // 等待 textarea 填充（useEffect 异步 → 最多等 2s）
  await expect(rawTextArea, 'rawText 应以「求和 | 源:」开头').toHaveValue(/^求和 \| 源:/, { timeout: 5000 });

  const rawVal = await rawTextArea.inputValue();
  console.log(`✅ rawText = "${rawVal}"`);
  await shot(page, 'rawtext-synced');

  // 加载中=0
  const loading = await page.locator('text=加载中').count();
  console.log(`[final] '加载中' count = ${loading}`);
  expect(loading, '不应有「加载中」残留').toBe(0);

  const bizErrors = consoleErrors.filter(
    (e) =>
      !/antd|deprecated|React Router|Warning:/.test(e) &&
      /cross_tab|crossTab|formulaEngine|FormulaZone|undefined is not/.test(e)
  );
  expect(bizErrors, `不应有 cross_tab 业务 console.error: ${bizErrors.join(' | ')}`).toHaveLength(0);
});
