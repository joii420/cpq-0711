/**
 * E2E: 跨页签引用公式 (cross_tab_ref) — 配置路径端到端验证
 *
 * 背景 (controller Task 6.2):
 *   cross_tab_ref 新增了一个公式 token,触碰了协议级前端文件 (formulaEngine.ts /
 *   QuotationStep2.tsx / ReadonlyProductCard.tsx) 与组件配置 UI
 *   (ComponentManagement.tsx / CrossTabRefDrawer.tsx / FieldPanel.tsx / FormulaZone.tsx)。
 *
 * 本 spec 覆盖范围 (CONFIG 路径,浏览器端到端):
 *   1. 进入组件管理 (/components-raw)
 *   2. 选中一个有兄弟组件 + 已有公式的真实组件 (生产费用 COMP-0002,同目录 10 组件)
 *   3. 切到「公式」标签,激活一条已有公式 (activeFormulaKey)
 *   4. 右侧「其他数据源」面板点「+ 配置跨页签引用…」
 *   5. CrossTabRefDrawer (标题「跨页签公式构建器」) 打开 → 源页签下拉被真实兄弟组件填充
 *   6. 选源组件 + 匹配列对 (A.列 = 本.列) + 目标列,点「确定」
 *   7. 断言:公式区出现 cross_tab_ref chip (文本以「跨页签[」开头)
 *
 * 本 spec **不覆盖**(需要测试数据基建,见报告):
 *   - 三视图 (报价单 edit / 核价单 / 详情只读) 的 cross_tab_ref 计算值一致性。
 *     这需要先用 cross_tab_ref 配出组件 → 绑产品模板 → 跑报价单/核价单产生快照,
 *     当前 DB 无此种子数据,UI 全流程搭建过于脆弱。值一致性已由后端单测
 *     (FormulaCalculatorCrossTabTest / CardSnapshotCrossTabTest) + 前端单测
 *     (formulaEngine.test.ts,共享 cross-tab-cases.json 夹具) 覆盖。
 *
 * 设计纪律:全程只读 + 不点保存,绝不写库 (不持久化任何 cross_tab_ref 配置)。
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

// ── 夹具常量 (真实种子数据) ────────────────────────────────────────────
// 目录 85222ada... (默认分类) 含 10 个兄弟组件;
// 生产费用 COMP-0002 有 4 条已有公式,可直接激活后插入 cross_tab_ref。
const TARGET_COMPONENT_NAME = '生产费用';   // COMP-0002, B 组件
const SOURCE_COMPONENT_NAME = '投料成本';   // COMP-0001, 兄弟 A 组件

let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `ctr-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}

let backendUp = false;

test.beforeAll(async () => {
  backendUp = await isBackendUp();
  if (!backendUp) console.log('[beforeAll] 后端未启动,整套 skip');
});

test.beforeEach(async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page);
});

test('cross_tab_ref 配置路径: 抽屉填充兄弟组件 + 确定后公式区出现跨页签 chip', async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text());
  });

  // 1) 进入组件管理 (raw 版,跳过 Hub 的 Tabs 包装)
  await page.goto('/components-raw');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  await shot(page, 'component-mgmt');

  // 2) 在左侧组件树选中目标组件 (B = 生产费用)。
  //    目录节点初始折叠,先把所有可展开的目录展开 (点 collapsed switcher),组件才可见。
  const tree = page.locator('.cm-tree-container');
  await expect(tree, '组件树容器应可见').toBeVisible({ timeout: 10_000 });
  // 反复展开:每轮点击当前所有「折叠态」switcher,直到没有折叠节点 (最多 6 轮防死循环)
  for (let round = 0; round < 6; round++) {
    const collapsed = tree.locator('.ant-tree-switcher_close');
    const n = await collapsed.count();
    if (n === 0) break;
    for (let i = 0; i < n; i++) {
      // 每次点第一个,DOM 会重排,故始终取 first
      const sw = tree.locator('.ant-tree-switcher_close').first();
      if (await sw.isVisible().catch(() => false)) {
        await sw.click().catch(() => {});
        await page.waitForTimeout(120);
      }
    }
    await page.waitForTimeout(300);
  }
  await shot(page, 'tree-expanded');

  const targetNode = tree
    .locator('.ant-tree-node-content-wrapper')
    .filter({ hasText: new RegExp(`^\\s*${TARGET_COMPONENT_NAME}\\s*$`) })
    .first();
  await expect(targetNode, `组件树应有「${TARGET_COMPONENT_NAME}」节点`).toBeVisible({ timeout: 10_000 });
  await targetNode.click();
  await page.waitForTimeout(1200);
  // 确认中间面板标题切到目标组件
  await expect(page.locator('.cm-center-title')).toContainText(TARGET_COMPONENT_NAME, { timeout: 8000 });
  await shot(page, 'component-selected');

  // 3) 切到「公式」Tab (中间 Antd Tabs)。精确匹配避免误命中 "字段配置"/"SQL 视图"。
  const formulaTab = page
    .locator('.ant-tabs-tab')
    .filter({ hasText: /^公式$/ })
    .first();
  await expect(formulaTab, '应有「公式」Tab').toBeVisible({ timeout: 8000 });
  await formulaTab.click();
  await page.waitForTimeout(800);

  // FormulaBuilder 在 .cm-card-section (header「🧮 公式管理」),作用域内取公式行,
  // 避免 Antd Tabs 把隐藏的「字段配置」表行也算进来。
  const formulaSection = page.locator('.cm-card-section').filter({ hasText: '公式管理' }).first();
  await expect(formulaSection, '应有公式管理区').toBeVisible({ timeout: 8000 });
  const formulaRow = formulaSection.locator('.ant-table-tbody tr.ant-table-row').first();
  await expect(formulaRow, '生产费用应有已配置的公式行').toBeVisible({ timeout: 8000 });
  await formulaRow.click();
  await page.waitForTimeout(600);
  // 激活后该行应出现「● 活动中」标记
  await expect(formulaSection.locator('text=活动中').first(), '公式行应激活').toBeVisible({ timeout: 6000 });
  await shot(page, 'formula-activated');

  // 4) 右侧面板切到「其他数据源」标签 (cross-tab 按钮在此)
  const rightSubtotalsTab = page.locator('.cm-right-tab', { hasText: '其他数据源' }).first();
  await expect(rightSubtotalsTab, '右侧应有「其他数据源」标签').toBeVisible({ timeout: 8000 });
  await rightSubtotalsTab.click();
  await page.waitForTimeout(500);

  const crossTabBtn = page.locator('button', { hasText: '配置跨页签引用' }).first();
  await expect(crossTabBtn, '应有「+ 配置跨页签引用…」按钮').toBeVisible({ timeout: 8000 });
  // 公式已激活,按钮应可用 (hasSelection)
  await expect(crossTabBtn, '组件已选,按钮应启用').toBeEnabled();
  await shot(page, 'before-crosstab-click');
  await crossTabBtn.click();
  await page.waitForTimeout(1000);
  // 若误触发「请先添加一个公式」提示则明确报错 (说明 activeFormula 未保持)
  const infoMsg = await page.locator('.ant-message-notice', { hasText: '请先添加一个公式' }).count();
  expect(infoMsg, '不应提示「请先添加一个公式」(公式已激活)').toBe(0);
  await shot(page, 'after-crosstab-click');

  // 5) CrossTabRefDrawer 打开
  const drawer = page.locator('.ant-drawer').filter({ hasText: '跨页签公式构建器' }).first();
  await expect(drawer, '应弹出「跨页签公式构建器」抽屉').toBeVisible({ timeout: 8000 });
  await shot(page, 'drawer-open');

  // 5a) 源页签下拉应被真实兄弟组件填充
  const sourceSelect = drawer.locator('.ant-select').first();
  await sourceSelect.click();
  await page.waitForTimeout(500);
  // 选项列表里应出现兄弟组件 A (投料成本)
  const sourceOption = page
    .locator('.ant-select-item-option')
    .filter({ hasText: SOURCE_COMPONENT_NAME })
    .first();
  await expect(sourceOption, `源页签下拉应含兄弟组件「${SOURCE_COMPONENT_NAME}」`).toBeVisible({
    timeout: 8000,
  });
  await sourceOption.click();
  await page.waitForTimeout(600);
  await shot(page, 'source-selected');

  // 6) 匹配列对: A.列 (第 1 个 select)  = 本.列 (第 2 个 select)
  //    drawer 内 select 顺序: [0]=源页签, 然后匹配区 [1]=A.列 [2]=本.列, 聚合 [3], 目标列 [4]
  const allSelects = drawer.locator('.ant-select');
  const selCount = await allSelects.count();
  console.log(`[drawer] select 总数 = ${selCount}`);
  expect(selCount, 'drawer 应至少有 源/A列/本列/聚合/目标列 5 个 select').toBeGreaterThanOrEqual(5);

  // A.列 (index 1) — 选源组件第一个可选字段
  await allSelects.nth(1).click();
  await page.waitForTimeout(400);
  const aColOption = page.locator('.ant-select-dropdown:visible .ant-select-item-option').first();
  await expect(aColOption, 'A.列 下拉应有源组件字段').toBeVisible({ timeout: 6000 });
  const aColText = (await aColOption.innerText().catch(() => '')).trim();
  await aColOption.click();
  await page.waitForTimeout(400);

  // 本.列 (index 2) — 选当前组件第一个可选字段
  await allSelects.nth(2).click();
  await page.waitForTimeout(400);
  const bColOption = page.locator('.ant-select-dropdown:visible .ant-select-item-option').first();
  await expect(bColOption, '本.列 下拉应有当前组件字段').toBeVisible({ timeout: 6000 });
  await bColOption.click();
  await page.waitForTimeout(400);

  // 目标列 (最后一个 select, index = selCount-1) — 选源组件字段 (聚合默认 NONE,需要目标列)
  await allSelects.nth(selCount - 1).click();
  await page.waitForTimeout(400);
  const tgtOption = page.locator('.ant-select-dropdown:visible .ant-select-item-option').first();
  await expect(tgtOption, '目标列下拉应有源组件字段').toBeVisible({ timeout: 6000 });
  await tgtOption.click();
  await page.waitForTimeout(400);
  await shot(page, 'drawer-configured');
  console.log(`[drawer] A.列 选了「${aColText}」`);

  // 预览块应出现 (验证抽屉内部派生逻辑)
  await expect(drawer.locator('text=预览')).toBeVisible({ timeout: 6000 });

  // 7) 点「确定」(抽屉 footer 的 primary 按钮)→ 抽屉关闭 + 公式区出现 cross_tab_ref chip
  //    注意: Antd 会在两个 CJK 字符间自动插空格 → 按钮文本实际是「确 定」,
  //    故用 .ant-drawer-footer .ant-btn-primary 而非文本匹配。
  const confirmBtn = page.locator('.ant-drawer-footer .ant-btn-primary').first();
  await expect(confirmBtn, '抽屉 footer 应有 primary「确定」按钮').toBeVisible({ timeout: 8000 });
  await confirmBtn.click();
  await page.waitForTimeout(1200);

  // 抽屉应关闭:用 footer 不再可见判定 (Antd 关闭后 .ant-drawer 根节点仍在 DOM,
  // 但 footer / mask 隐藏更可靠)
  await expect(
    page.locator('.ant-drawer-footer'),
    '确定后抽屉 footer 应隐藏 (抽屉关闭)'
  ).toBeHidden({ timeout: 8000 });

  // 公式区 chip: FormulaZone.getTokenLabel 对 cross_tab_ref 返回 "跨页签[...]..."
  // 必须在公式管理区 (.cm-card-section) 内断言,避免命中已关闭抽屉残留 DOM。
  const chip = formulaSection.locator('text=/跨页签\\[/').first();
  await expect(chip, '公式区应出现「跨页签[...]」cross_tab_ref chip').toBeVisible({ timeout: 8000 });
  const chipText = (await chip.innerText().catch(() => '')).trim();
  console.log(`✅ cross_tab_ref chip 渲染 = "${chipText}"`);
  // chip 文本应含源组件名 (sourceLabel)
  expect(chipText, 'chip 应含源组件名').toContain(SOURCE_COMPONENT_NAME);
  await shot(page, 'chip-rendered');

  // 加载中=0 (协议级渲染兜底自检)
  const loading = await page.locator('text=加载中').count();
  console.log(`[final] '加载中' count = ${loading}`);
  expect(loading, '配置页不应有「加载中」残留').toBe(0);

  // console.error 中不应有 cross_tab_ref / formulaEngine 相关业务报错 (antd deprecated 警告忽略)
  const bizErrors = consoleErrors.filter(
    (e) =>
      !/antd|deprecated|React Router|Warning:/.test(e) &&
      /cross_tab|crossTab|formulaEngine|FormulaZone|undefined is not/.test(e)
  );
  expect(bizErrors, `不应有 cross_tab_ref 业务 console.error: ${bizErrors.join(' | ')}`).toHaveLength(0);
});
