/**
 * task-260902「选配流程重构」E2E 公共夹具。
 *
 * 🚨 证据归档纪律（`testing.md §2`）：
 * 截图**不写 `e2e/screenshots/` 也不写 `test-results/`** —— 那两个目录下一轮开跑就被清空，
 * 「声明了截图证据、却留在会被清空的目录」等于没有证据。
 * 本文件统一把截图写进 **任务目录** `dev-docs/task-260902-选配流程重构/证据/e2e/`，
 * 随任务一起提交。
 *
 * 🚫 全局状态纪律（`testing.md §4.3`）：本套 E2E **不写共享库**。
 * 需要「0 条外购件」「0 组配置材质」「hasTemplate=false」这类现网构造不出的前置时，
 * 一律用 `page.route()` 拦截**该前端页面自己的请求**来注入，零全局副作用。
 * 这些前置的**后端半句**由 `cpq-backend/src/test/java/com/cpq/task260902/` 的接口层用例覆盖，
 * 不存在「只 mock 不验真」的缺口。
 */
import { Page, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { execSync } from 'child_process';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

/** 任务目录下的证据归档位（下一轮跑测试不会清空它）。 */
export const EVIDENCE_DIR = path.join(
  __dirname, '..', '..', '..',
  'dev-docs', 'task-260902-选配流程重构', '证据', 'e2e'
);
fs.mkdirSync(EVIDENCE_DIR, { recursive: true });

let shotIdx = 0;
/** 截图 → 归档到任务目录。文件名带序号与用例名，便于在 test-report.md 里逐条引用。 */
export async function shot(page: Page, name: string): Promise<string> {
  const file = path.join(EVIDENCE_DIR, `${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true });
  console.log(`📸 ${name} → ${file}`);
  return file;
}

/** 只读查库（取 fixture 用）。🚫 本套 E2E 不用它写库。 */
export function query(sql: string): string {
  return execSync(
    `PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db_0724 -tAc ${JSON.stringify(sql)}`,
    { encoding: 'utf-8', shell: '/bin/bash' }
  ).trim();
}

/** 抽屉根节点。 */
export const drawer = (page: Page) => page.locator('.ant-drawer').last();

/**
 * 新建一张报价单并走到第 2 步，打开「选配添加」抽屉。
 * 沿用 `tc0712-selconfig-composite-smoke.spec.ts` 已验证的下拉选择时序
 * （分类/模板落定后再填名称，避开 QuotationCreateForm 的 stale closure 回填竞态）。
 */
export async function openSelConfigDrawer(page: Page, label: string) {
  await page.goto('/quotations/new');
  await page.waitForLoadState('networkidle');

  await selectByLabel(page, '客户', '西门子');
  await page.waitForTimeout(1500);

  // 🚨 产品分类**不要选**：task-0712 update-071501 换轴到「客户产品分类」后，
  //    它随客户自动带出且渲染为 `ant-select-disabled`（2026-09-03 实测），
  //    点它不会出下拉 ⇒ 旧写法在这里必然 timeout，且长得像产品缺陷。
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(400);

  // 🚨 前置体检：产品分类是**必填**且随客户自动带出。若客户的 product_category_id 指向一条
  //    **已不存在**的分类，下拉找不到对应 option ⇒ 该必填项永远为空 ⇒「下一步」永远禁用。
  //    那种失败表现为「按钮一直不可点」的超时，和产品缺陷长得一模一样 ⇒ 这里提前判死并说清根因。
  await assertCategoryResolved(page);

  // 报价模板：当前表单里不一定有这个字段（实测选完客户后只有 客户/报价单名称/产品分类/核价模板），
  // 有就选、没有就跳过，🚫 不要因为夹具的一个可选步骤把整条用例判死
  await selectIfPresent(page, '报价模板', '0608');

  await page.locator('input[placeholder*="报价单名称"]').first().fill(`E2E-260902-${label}-${Date.now()}`);
  const next = page.getByRole('button', { name: /下一步/ }).first();
  await expect(next, 'Step1 校验应通过（前置：夹具报价单能建出来）').toBeEnabled({ timeout: 15000 });
  await next.click();
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1200);

  await page.getByRole('button', { name: /添加产品/ }).first().click();
  await page.waitForTimeout(400);
  await page.locator('text=选配添加').first().click();
  await page.waitForTimeout(800);
  await expect(drawer(page), '「选配添加」抽屉应打开').toBeVisible({ timeout: 10000 });
}

export async function selectByLabel(page: Page, label: string, search: string, optionText?: string) {
  // 🚨 先关掉上一个下拉：antd 选完之后浮层**仍挂在 DOM 里**，
  //    全局查 `.ant-select-item-option` 会命中上一个下拉的残留选项。
  //    实测症状：选「产品分类」时拿到的候选是 ["苏州西门子 (8000137)"]（客户下拉的），
  //    然后以 timeout 结束 —— 长得像产品缺陷，其实是选择器串台。
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(250);

  const item = page.locator('.ant-form-item')
    .filter({ has: page.locator('label', { hasText: label }) }).first();
  await item.locator('.ant-select').first().click();
  await page.waitForTimeout(300);
  if (search) {
    await page.keyboard.type(search, { delay: 60 });
    await page.waitForTimeout(900);
  }
  // 🚨 找不到选项时 **不要让它超时**：超时长得和产品缺陷一模一样
  //    （`cpq-playwright-selector-pitfalls`：四个选择器坑全表现为 timeout，最易误判）。
  //    这里把它转成一条带「当前实际候选」的诊断，读报告的人一眼能分清是夹具还是产品。
  const want = optionText || search;
  // 只在**当前可见**的那个下拉浮层里找选项（.last() = 最近打开的那个）
  const dropdown = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').last();
  const option = dropdown.locator('.ant-select-item-option').filter({ hasText: want }).first();
  try {
    await option.waitFor({ state: 'visible', timeout: 8000 });
  } catch {
    const candidates = await dropdown.locator('.ant-select-item-option').allInnerTexts().catch(() => []);
    throw new Error(
      `[夹具失败·非产品缺陷] 「${label}」下拉里找不到选项「${want}」。`
      + `当前实际候选（${candidates.length} 个）=${JSON.stringify(candidates)}。`
      + `⇒ 这是 E2E 夹具/选择器问题，不是被测功能的结论。`);
  }
  await option.click();
  await page.waitForTimeout(400);
}

/**
 * 🚨 AP-31 族守卫：断言某个区域里**不出现「加载中」**。
 * 空态必须是空态，不是永久 loading 占位。
 */
export async function expectNoLoadingPlaceholder(page: Page, scope = '.ant-drawer') {
  const loading = page.locator(scope).getByText(/加载中/);
  await expect(loading, '🚫 空态区域出现了「加载中…」永久占位（AP-31 族）').toHaveCount(0);
}

/** 禁用按钮的 tooltip 文案（§1.2：禁用必须可见并说明原因）。 */
export async function tooltipOf(page: Page, button: ReturnType<Page['locator']>): Promise<string> {
  await button.hover({ force: true });
  await page.waitForTimeout(600);
  return (await page.locator('.ant-tooltip-inner').last().innerText().catch(() => '')).trim();
}

// ─────────────────────────── 步骤操作（按原型的可见文案定位）───────────────────────────

export async function fillStep1(page: any, productNo: string) {
  const input = drawer(page).getByPlaceholder(/客户产品编号|请输入.*编号/).first();
  await input.fill(productNo);
  await page.waitForTimeout(1200);
  await nextStep(page);
}

export async function nextStep(page: any) {
  const next = drawer(page).getByRole('button', { name: /下一步/ }).first();
  await expect(next, '「下一步」应可点（不可点说明上一步校验没过）').toBeEnabled({ timeout: 10000 });
  await next.click();
  await page.waitForTimeout(900);
}

/** 「+ 添加配件」→ 零件 → 新建零件 → 填品名/规格/尺寸/总重（原型 2 + 原型 3）。 */
export async function startNewPart(page: any, name: string, spec: string, dim: string, weight: string) {
  await drawer(page).getByRole('button', { name: /添加配件|添加第一个配件/ }).first().click();
  await page.waitForTimeout(500);
  await drawer(page).getByText('零件', { exact: true }).first().click();
  await page.waitForTimeout(400);
  await drawer(page).getByText('新建零件', { exact: false }).first().click();
  await page.waitForTimeout(800);
  await drawer(page).getByLabel(/品名/).first().fill(name);
  await drawer(page).getByLabel(/规格/).first().fill(spec);
  await drawer(page).getByLabel(/尺寸/).first().fill(dim);
  await drawer(page).getByLabel(/总重/).first().fill(weight);
  await page.waitForTimeout(300);
}

/**
 * 「+ 添加材质」→ 搜索 → 选择 → 填占比。
 * ⚠️ 250+ 条材质走虚拟滚动，**必须先输入过滤再点选**（靠滚动找会随机挂，对齐 AC-18 的踩坑记录）。
 */
export async function addMaterial(page: any, recipeCode: string, ratio: string) {
  await drawer(page).getByRole('button', { name: /添加材质/ }).first().click();
  await page.waitForTimeout(500);
  const kw = page.locator('.ant-drawer, .ant-modal').last().getByPlaceholder(/搜索|材质编号|材质名/).first();
  await kw.fill(recipeCode);
  await page.waitForTimeout(1000);
  const row = page.locator('.ant-drawer, .ant-modal').last()
    .locator('tr, li, .picker-row').filter({ hasText: recipeCode }).first();
  await row.getByRole('button', { name: /选择/ }).first().click();
  await page.waitForTimeout(700);
  const ratioInput = drawer(page).locator('tr').filter({ hasText: recipeCode })
    .locator('input').last();
  await ratioInput.fill(ratio);
  await ratioInput.blur();
  await page.waitForTimeout(500);
}

/** 打开材质选择器（不选，只打开）—— AC-17 / AC-18 / AC-18b 用。 */
export async function openMaterialPicker(page: any) {
  await drawer(page).getByRole('button', { name: /添加材质/ }).first().click();
  await page.waitForTimeout(700);
}

/** 材质选择器里输入关键词并等过滤结果稳定，返回可见行数。 */
export async function searchInPicker(page: any, keyword: string): Promise<number> {
  const picker = page.locator('.ant-drawer, .ant-modal').last();
  const kw = picker.getByPlaceholder(/搜索|材质编号|材质名|工序/).first();
  await kw.fill(keyword);
  await page.waitForTimeout(1200);
  return picker.locator('tbody tr, li.picker-row, .picker-row').count();
}

/**
 * 「+ 添加工序」→ 搜索 → 选择，按给定顺序依次加入**有序列表**（允许重复）。
 * ⚠️ 顺序即工艺顺序：它影响落库 `unit_price.seq_no` 与显示，**不影响料号复用判定**（A0 裁决）。
 */
export async function addProcesses(page: any, processNos: string[]) {
  for (const no of processNos) {
    await drawer(page).getByRole('button', { name: /添加工序/ }).first().click();
    await page.waitForTimeout(500);
    const p = page.locator('.ant-drawer, .ant-modal').last();
    await p.getByPlaceholder(/搜索|工序编号|工序名/).first().fill(no);
    await page.waitForTimeout(900);
    await p.locator('tr, li, .picker-row').filter({ hasText: no }).first()
      .getByRole('button', { name: /选择|添加/ }).first().click();
    await page.waitForTimeout(600);
  }
}

/** 表单里有这个字段就选，没有就跳过（并打印，便于事后判断夹具走了哪条路）。 */
export async function selectIfPresent(page: Page, label: string, search: string) {
  const n = await page.locator('.ant-form-item')
    .filter({ has: page.locator('label', { hasText: label }) }).count();
  if (n === 0) { console.log(`[夹具] 表单里没有「${label}」字段，跳过`); return false; }
  const sel = page.locator('.ant-form-item')
    .filter({ has: page.locator('label', { hasText: label }) }).first().locator('.ant-select').first();
  const cls = (await sel.getAttribute('class')) || '';
  if (cls.includes('ant-select-disabled')) {
    console.log(`[夹具] 「${label}」是禁用态（随客户自动带出），跳过`);
    return false;
  }
  await selectByLabel(page, label, search);
  return true;
}

/**
 * 断言「产品分类」这个**必填**项真的被解析出来了。
 *
 * 🚨 2026-09-03 实测：`苏州西门子(8000137).product_category_id = b9576df8-24bf-42b7-b5a7-58bda3a023d2`
 * 在 `product_category` 表里**不存在**（全库唯一一个悬空引用）⇒ 下拉找不到对应 option ⇒
 * 必填的产品分类显示为空、且控件是禁用态（用户也改不了）⇒「下一步」永远禁用 ⇒
 * <b>用这个客户建不出报价单</b>，走「新建报价单」的 E2E 全部卡在 Step1。
 * 这不是被测功能的缺陷，也不是选择器问题，是<b>共享库的数据完整性问题</b>。
 */
export async function assertCategoryResolved(page: Page) {
  const item = page.locator('.ant-form-item')
    .filter({ has: page.locator('label', { hasText: '产品分类' }) }).first();
  if (await item.count() === 0) return;                       // 没这个字段就不管
  const required = await item.locator('.ant-form-item-required').count() > 0
    || await page.locator('.ant-form-item-required').filter({ hasText: '产品分类' }).count() > 0;
  const shown = (await item.locator('.ant-select-selection-item').first()
    .innerText().catch(() => '')).trim();
  console.log(`[夹具] 产品分类 必填=${required} 当前值="${shown}"`);
  if (required && !shown) {
    throw new Error(
      '[夹具失败·共享库数据问题，非产品缺陷] 必填项「产品分类」随客户自动带出但解析为空，'
      + '且控件为禁用态 ⇒「下一步」永远不可点，本用例无法进入被测流程。\n'
      + '实测根因：该客户的 product_category_id 指向一条已不存在的产品分类（悬空外键）。\n'
      + '核对 SQL：SELECT c.code, c.product_category_id, pc.id IS NOT NULL AS 分类存在 '
      + "FROM customer c LEFT JOIN product_category pc ON pc.id=c.product_category_id WHERE c.name LIKE '%西门子%';\n"
      + '⇒ 需主线裁决：① 修数据（把该客户指向存在的分类）；② 换一个分类有效的夹具客户；'
      + '③ 前端对悬空分类做兜底。🚫 改共享库数据属红线，测试侧不自行处理。');
  }
}
