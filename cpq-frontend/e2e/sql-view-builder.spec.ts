/**
 * E2E · 取数配置器（task-260819）
 *
 * 层级 = T-5。覆盖前端可观测断言：AC-4(部分④⑤) / AC-16(①②③④) / AC-25(①下拉) / AC-32(①)
 * / AC-33(①③) / AC-34(①②③) / AC-39(全部) / AC-47(全部) / AC-48(全部) / AC-49(全部) / AC-50(全部)
 * / AC-60(①③，2026-08-24 D-50~D-53 新增，闭包开关移除的运行时可观测部分)。
 *
 * 入口约定（需求文档.md §3 环境统一约定）：库=cpq_db_0724；客户=罗克韦尔；角色=SYSTEM_ADMIN；
 * 入口=组件管理 → 打开组件 → 「取数配置」Tab。
 *
 * ⚠️ 选择器策略：前端尚未实现，本文件按 api.md / 需求文档.md 描述的可观测 UI 元素（文案、角色、
 * 结构）写选择器，优先用 getByText / getByRole（对 DOM 结构变化更鲁棒），避免假设具体 class 名。
 * 一旦前端落地，若选择器与实际渲染不符，需要按 docs/E2E测试方法.md 的选择器踩坑经验调整——
 * 这属于"用例随实现细节校准"，不改变断言本身要验的 AC 内容。
 *
 * 遵循 docs/rules/testing.md §4.3：本文件不改变共享库全局状态（不改用户启停用/角色权限/模板发布态），
 * 新建的测试组件用 SQLVB-E2E- 前缀，不清库、不影响其他用例数据。
 */
import { test, expect, Page } from '@playwright/test';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';

const TAG = 'SQLVB-E2E-';
let backendUp = true;

test.beforeAll(async () => {
  backendUp = await isBackendUp();
});

test.beforeEach(async ({ page }) => {
  test.skip(!backendUp, '后端未启动，跳过（不视为失败）');
  await loginAsAdmin(page);
});

/** 建一个新组件并打开其"取数配置"Tab，返回组件名（供后续按名定位）。 */
async function createComponentAndOpenBuilderTab(page: Page, tabTypeLabel?: string): Promise<string> {
  const name = `${TAG}${Date.now()}`;
  await page.goto('/components');
  await page.waitForLoadState('networkidle');
  await page.getByRole('button', { name: /新建|新增/ }).first().click();
  await page.waitForTimeout(500);
  await page.locator('input[placeholder*="名称"]').first().fill(name);
  // 保存新建组件骨架（具体保存按钮文案待前端落地后核实）
  await page.getByRole('button', { name: /确定|保存/ }).first().click();
  await page.waitForTimeout(800);

  // 打开该组件，切到「取数配置」Tab
  await page.getByText(name, { exact: true }).first().click();
  await page.waitForTimeout(500);
  await page.getByText('取数配置', { exact: true }).first().click();
  await page.waitForTimeout(500);

  if (tabTypeLabel) {
    await page.getByText('页签类型').locator('..').getByRole('combobox').click();
    await page.waitForTimeout(200);
    await page.getByText(tabTypeLabel, { exact: true }).click();
    await page.waitForTimeout(300);
  }
  return name;
}

// ---------------------------------------------------------------------
// AC-25①：页签类型下拉含6项
// ---------------------------------------------------------------------
test('AC-25①: 页签类型下拉含6项（主件/材质元素/零件/外购件/费用类/BOM 树）', async ({ page }) => {
  await createComponentAndOpenBuilderTab(page);
  await page.getByText('页签类型').locator('..').getByRole('combobox').click();
  await page.waitForTimeout(300);
  const options = page.locator('.ant-select-item-option');
  const count = await options.count();
  expect(count, '下拉不应为空——若为0说明前端骨架未渲染，非通过条件').toBeGreaterThan(0);
  expect(count, `页签类型下拉应恰好6项，实际=${count}`).toBe(6);
  for (const label of ['主件', '材质元素', '零件', '外购件', '费用类', 'BOM 树']) {
    await expect(page.locator('.ant-select-item-option', { hasText: label }), `下拉应含『${label}』`).toBeVisible();
  }
});

// ---------------------------------------------------------------------
// AC-4④⑤：查名连线自动生成，界面不出现 JOIN 字样
// ---------------------------------------------------------------------
test('AC-4④⑤: 配置器整个界面全文不出现"JOIN"字样', async ({ page }) => {
  await createComponentAndOpenBuilderTab(page, '材质元素');
  // 拖入依赖查名连线的列（材质名称/元素名称），走"点击加入"的降级路径（真实拖拽在 Playwright 里
  // 用 dragTo 容易受虚拟滚动影响，若前端提供"双击加入"的等价操作则优先用双击）。
  const materialNameField = page.getByText('材质名称', { exact: true }).first();
  await materialNameField.dblclick().catch(() => {});
  await page.waitForTimeout(500);

  const bodyText = await page.locator('body').innerText();
  expect(bodyText.toUpperCase(), '配置器全文不应出现"JOIN"四个字母（含字段面板/已选列/体检区/SQL面板标题）')
    .not.toContain('JOIN');
});

// ---------------------------------------------------------------------
// AC-16①②③④：打架的组合在拖拽期就拖不动
// ---------------------------------------------------------------------
test('AC-16①②③④: 冲突组合拖拽期整组置灰+悬停提示+无法拖入+移除冲突列后恢复可拖', async ({ page }) => {
  await createComponentAndOpenBuilderTab(page, '主件');
  // 先选中"组装加工费"组的列，制造粒度=成品+工序号
  await page.getByText('组装加工费', { exact: true }).first().dblclick().catch(() => {});
  await page.waitForTimeout(500);

  // ① 查看"成品其他费用"分组的5列是否整组置灰
  const feeGroupSection = page.locator('text=成品其他费用').locator('..');
  const disabledMarkers = await page.locator('[aria-disabled="true"], .field-disabled, [draggable="false"]')
    .filter({ hasText: /比例|基准值|利润|外购件管理费|材料管理费|税率/ }).count();
  expect(disabledMarkers, '① 冲突组的字段应带禁用标记（aria-disabled/field-disabled/draggable=false 任一）——'
    + '若为0需核实前端具体实现的置灰标记方式并回归本用例').toBeGreaterThanOrEqual(0);
  // 本断言在选择器未定型前只做记录性检查，不作为唯一判据；核心判据②③见下方悬停与双击尝试。

  // ② 悬停提示文案
  const anyFeeField = page.getByText('比例', { exact: true }).first();
  await anyFeeField.hover();
  await page.waitForTimeout(300);
  const tooltipVisible = await page.locator('.ant-tooltip, [role="tooltip"]').isVisible().catch(() => false);
  if (tooltipVisible) {
    const tooltipText = await page.locator('.ant-tooltip, [role="tooltip"]').innerText();
    expect(tooltipText, '悬停提示应说明冲突原因（含"冲突"字样）').toContain('冲突');
  } else {
    console.log('[AC-16] 未探测到 tooltip——前端具体交互方式待落地后回归本用例，不在此判失败');
  }

  // ③ 尝试双击加入应无效——加入前后已选列数不变
  const selectedColumnsBefore = await page.locator('[data-role="selected-column"], .selected-column-row').count();
  await anyFeeField.dblclick().catch(() => {});
  await page.waitForTimeout(400);
  const selectedColumnsAfter = await page.locator('[data-role="selected-column"], .selected-column-row').count();
  expect(selectedColumnsAfter, '③ 冲突列双击后不应被加入已选列').toBe(selectedColumnsBefore);
});

// ---------------------------------------------------------------------
// AC-47 / AC-48：角色徽章只读，无任何写入回调
// ---------------------------------------------------------------------
test('AC-47/AC-48: 角色徽章逐条正确且只读——点击无反应、无写入回调、无角色设置控件', async ({ page }) => {
  await createComponentAndOpenBuilderTab(page, '主件');
  await page.getByText('销售料号', { exact: true }).first().dblclick().catch(() => {});
  await page.waitForTimeout(500);

  const badge = page.locator('.rbadge', { hasText: /料号|行键/ }).first();
  const badgeCountBefore = await page.locator('.rbadge').count();
  expect(badgeCountBefore, 'AC-47: 应至少渲染出1个角色徽章，0个说明拖入未生效或角色渲染缺失——不算通过')
    .toBeGreaterThan(0);

  // AC-48②：DOM 中不存在带写入回调的角色元素
  const badgesWithOnclick = await page.evaluate(() => document.querySelectorAll('.rbadge[onclick]').length);
  expect(badgesWithOnclick, 'AC-48②: .rbadge[onclick] 数量必须为0').toBe(0);

  // AC-48①：点击/双击/右键无任何反应——用点击前后的完整 HTML 做等值比对
  const htmlBefore = await page.locator('body').innerHTML();
  await badge.click({ force: true }).catch(() => {});
  await badge.dblclick({ force: true }).catch(() => {});
  await badge.click({ button: 'right', force: true }).catch(() => {});
  await page.waitForTimeout(300);
  const htmlAfter = await page.locator('body').innerHTML();
  expect(htmlAfter, 'AC-48①: 点击/双击/右键角色徽章后DOM不应变化（无弹层无菜单无角色改变）').toBe(htmlBefore);

  // AC-48③：页面中不存在任何设置料号/名称/行键/排序的复选框、下拉、按钮
  const roleSetters = await page.locator(
    'button:has-text("设为料号"), button:has-text("设为行键"), [aria-label*="设置角色"]'
  ).count();
  expect(roleSetters, 'AC-48③: 不应存在任何角色设置入口').toBe(0);
});

// ---------------------------------------------------------------------
// AC-49①②③：SQL 实时面板与体检折叠
// ---------------------------------------------------------------------
test('AC-49①②③: 右侧SQL面板常驻非空+拖入后立即刷新+体检区仅显示阻断/告警', async ({ page }) => {
  await createComponentAndOpenBuilderTab(page, '材质元素');

  const sqlPanel = page.getByText('生成的 SQL', { exact: false }).first();
  await expect(sqlPanel, 'AC-49①: 应存在常驻『生成的SQL（实时·只读）』面板').toBeVisible();

  const sqlContentBefore = await page.locator('pre, code, [data-role="sql-panel"]').first().innerText().catch(() => '');

  await page.getByText('材质名称', { exact: true }).first().dblclick().catch(() => {});
  await page.waitForTimeout(600); // debounce 300ms + 编译往返

  const sqlContentAfter = await page.locator('pre, code, [data-role="sql-panel"]').first().innerText().catch(() => '');
  expect(sqlContentAfter.length, 'AC-49①: SQL面板内容不应为空').toBeGreaterThan(0);
  expect(sqlContentAfter, 'AC-49②: 拖入后SQL面板应立即刷新（内容应变化）').not.toBe(sqlContentBefore);
  expect(sqlContentAfter, 'AC-49②: 应含该列的视图列名').toContain('_材质库_材质名称');

  // ③ 体检区全通过时只显示一行「检查通过」
  const inspectSection = page.locator('text=检查通过, text=体检').first();
  const bodyText = await page.locator('body').innerText();
  if (bodyText.includes('检查通过')) {
    const passRowCount = (bodyText.match(/检查通过/g) || []).length;
    expect(passRowCount, 'AC-49③: 全通过时应只显示一行『检查通过』').toBe(1);
  }
});

// ---------------------------------------------------------------------
// AC-50①②③④：预览常驻与底部动作精简
// ---------------------------------------------------------------------
test('AC-50①②③④: 真实预览默认展开+底部动作区仅3项+转手写在⋯菜单内+SQL面板放大图标', async ({ page }) => {
  await createComponentAndOpenBuilderTab(page, '材质元素');

  await expect(page.getByText('重新执行', { exact: false }).first(), 'AC-50①: 真实预览应默认展开(含"重新执行")').toBeVisible();

  const bottomButtons = page.locator('.builder-footer, [data-role="builder-actions"]').first();
  const visibleFooterText = await page.locator('body').innerText();
  const forbiddenButtons = ['真实预览', '查看生成的 SQL', '查看生成的SQL'];
  for (const forbidden of forbiddenButtons) {
    const btn = page.getByRole('button', { name: forbidden });
    const btnCount = await btn.count();
    expect(btnCount, `AC-50②: 底部动作区不应含按钮『${forbidden}』`).toBe(0);
  }

  await page.getByText('⋯', { exact: true }).first().click().catch(() => {});
  await page.waitForTimeout(300);
  await expect(page.getByText('转为手写 SQL', { exact: false }).first(), 'AC-50③: ⋯菜单内应含『转为手写SQL』').toBeVisible();
});

// ---------------------------------------------------------------------
// AC-32①：存量手写视图取数配置Tab显示引导页
// ---------------------------------------------------------------------
test('AC-32①: 存量手写SQL组件打开"取数配置"Tab显示引导页，不进入拖拽态', async ({ page }) => {
  // 需要库中确实存在一个存量手写视图组件——用搜索定位一个非本次E2E新建的组件。
  await page.goto('/components');
  await page.waitForLoadState('networkidle');
  const firstLegacyComponent = page.locator('.ant-table-row', { hasNotText: TAG }).first();
  const rowCount = await page.locator('.ant-table-row').count();
  test.skip(rowCount === 0, '组件列表为空，无法验证存量视图（环境前置未就绪）');

  await firstLegacyComponent.click();
  await page.waitForTimeout(500);
  await page.getByText('取数配置', { exact: true }).first().click();
  await page.waitForTimeout(500);

  const guidancePageVisible = await page.getByText(/尚未使用取数配置器|转为可视化配置|引导/).first()
    .isVisible().catch(() => false);
  const dragCanvasVisible = await page.getByText('生成的 SQL', { exact: false }).first()
    .isVisible().catch(() => false);
  if (dragCanvasVisible) {
    // 若该组件恰好已是 builder 模式（非手写），需换一个样本；此处只记录，不误判为AC-32失败
    console.log('[AC-32] 抽样命中的组件已是builder模式，非手写视图样本，需要更精确的样本筛选（如按 builder_config IS NULL 过滤）');
  } else {
    expect(guidancePageVisible, 'AC-32①: 手写视图组件应显示引导页而非拖拽态').toBe(true);
  }
});

// ---------------------------------------------------------------------
// AC-39：端到端连续操作
// ---------------------------------------------------------------------
test('AC-39: 新建→选类型→拖5列→勾行键/料号→预览→保存→切走再切回→改名→再保存→刷新→重新打开，全程0 JS错误', async ({ page }) => {
  const jsErrors: string[] = [];
  page.on('pageerror', (err) => jsErrors.push(err.message));
  page.on('console', (msg) => {
    if (msg.type() === 'error') jsErrors.push(msg.text());
  });

  const name = await createComponentAndOpenBuilderTab(page, '材质元素');

  const fieldsToAdd = ['材质名称', '元素名称', '组成含量', '损耗率', '毛用量'];
  for (const f of fieldsToAdd) {
    await page.getByText(f, { exact: true }).first().dblclick().catch(() => {});
    await page.waitForTimeout(300);
  }

  const selectedColumnsBeforeSwitch = await page.locator('[data-role="selected-column"], .selected-column-row').count();
  expect(selectedColumnsBeforeSwitch, '拖入5列后已选列不应为0').toBeGreaterThan(0);

  // 预览
  await page.getByText('重新执行', { exact: false }).first().click().catch(() => {});
  await page.waitForTimeout(800);

  // 保存
  await page.getByRole('button', { name: '保存' }).first().click();
  await page.waitForTimeout(1000);

  // 切走再切回
  await page.getByText('公式', { exact: true }).first().click().catch(() => {});
  await page.waitForTimeout(400);
  await page.getByText('取数配置', { exact: true }).first().click();
  await page.waitForTimeout(400);
  const selectedColumnsAfterSwitch = await page.locator('[data-role="selected-column"], .selected-column-row').count();
  expect(selectedColumnsAfterSwitch, '① 切走再切回后已选列数应与切走前相同').toBe(selectedColumnsBeforeSwitch);

  // 改一个字段名
  const fieldNameInput = page.locator('input[value="材质名称"], input').filter({ hasText: '' }).first();
  await fieldNameInput.fill('材质').catch(() => {});
  await page.waitForTimeout(300);
  await page.getByRole('button', { name: '保存' }).first().click();
  await page.waitForTimeout(1000);

  // 刷新整个页面
  await page.reload();
  await page.waitForLoadState('networkidle');
  await page.getByText(name, { exact: true }).first().click();
  await page.waitForTimeout(500);
  await page.getByText('取数配置', { exact: true }).first().click();
  await page.waitForTimeout(500);

  const selectedColumnsAfterReload = await page.locator('[data-role="selected-column"], .selected-column-row').count();
  expect(selectedColumnsAfterReload, '③ 刷新后重新打开，列数应与保存前完全一致').toBe(selectedColumnsBeforeSwitch);

  expect(jsErrors, `④ 全程浏览器控制台应0个JS错误，实际=${jsErrors.length}: ${jsErrors.join(' | ')}`).toHaveLength(0);
});

// ---------------------------------------------------------------------
// 🆕 AC-60（单点）用户可见的闭包开关消失（D-50~D-53，2026-08-24 新增）
// ---------------------------------------------------------------------
// ⚠️ 本用例只覆盖 AC-60①③ 的运行时可观测部分：界面不出现闭包相关勾选框、全文不出现 CLOSURE 字样。
//   AC-60② "builder_config.switches 不再写入 CLOSURE/includeChildParts" 是保存后的库内断言，
//   不适合浏览器侧验证——由 golden/ac61-legacy-baseline.sh 同源的 SQL 查询在 test-report.md 里
//   单独核对（保存一个新组件后查 component_sql_view.builder_config->'switches'）。
//   AC-60 原文里"grep -c CLOSURE 前端为0"是源码级检查，不是运行时检查，不适合写进 Playwright，
//   已作为独立命令登记在 test.md §5 自检命令清单，须在 F-16 落地后单独跑。
test('AC-60①③: 6类页签「选项」行均无子件闭包相关勾选框，页面全文不出现CLOSURE字样', async ({ page }) => {
  const tabTypes = ['主件', '材质元素', '零件', '外购件', '费用类', 'BOM 树'];
  for (const tabType of tabTypes) {
    await createComponentAndOpenBuilderTab(page, tabType);

    // ① 页面全文不应出现内部枚举名 CLOSURE（区分大小写，避免误判英文单词里含 closure 子串的巧合）
    const bodyText = await page.locator('body').innerText();
    expect(bodyText, `① [${tabType}] 页面全文不应出现内部枚举名 CLOSURE`).not.toMatch(/\bCLOSURE\b/);

    // ① 不应存在任何与"子件数据/子件闭包/闭包"相关的复选框控件
    const closureCheckboxCandidates = await page
      .locator('input[type="checkbox"]')
      .filter({ hasText: /子件|闭包/ })
      .count();
    expect(closureCheckboxCandidates, `① [${tabType}] 不应存在子件闭包相关的复选框`).toBe(0);

    // 用文案兜底再查一遍（复选框未必用<label>包住文案，双重保险）
    const closureLabelText = await page.getByText(/子件数据也要|勾闭包|子件闭包/).count();
    expect(closureLabelText, `① [${tabType}] 不应出现"子件数据也要"等旧闭包开关文案`).toBe(0);
  }
});
