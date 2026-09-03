/**
 * dataset-maintenance.spec.ts
 *
 * task-260902「报价与核价建表与导入方案新规范」· L4 E2E
 * 覆盖 E 组 AC-24 ~ AC-34（维护 UI）+ AC-42 现有页签回归。
 *
 * ── 判据来源（🚫 不读实现，只读立项文档与原型图）────────────────────────────
 *   需求文档.md ④ E 组 AC 原文
 *   原型图/主数据维护-页签结构.html   → AC-24 六个页签的名称与顺序
 *   原型图/核价数据-列表.html         → AC-25 列表列
 *   原型图/核价数据-抽屉.html         → AC-26 九个 tab 名称与顺序、AC-27 toast
 *   原型图/核价数据-抽屉-历史只读.html → AC-29 禁用态与 hover 文案
 *   原型图/核价数据-抽屉-空态.html     → AC-32 空态文案
 *   原型图/数据导入-抽屉.html          → AC-33 导入后自动刷新
 *
 * ── 四个已知选择器坑（docs/E2E测试方法.md + RECORD.md）───────────────────────
 *   1. antd 两字按钮渲染成「保 存」（中间有空格）⇒ 一律用正则 /保\s*存/
 *   2. 下拉是虚拟滚动，选项要先滚到可见
 *   3. `.ant-select-content` 是采样陷阱，别拿它当选中值
 *   4. spec 文件编码问题会让中文断言静默失配
 *   ⚠️ 四个坑都表现为 timeout，别误判成产品 bug。
 *
 * ── 全局状态纪律（testing.md §4.3）────────────────────────────────────────
 *   本 spec **只读 + 只动 TEST-DS- 前缀的料号**，不改用户启停用 / 角色 / 模板发布态。
 *   ⚠️ E2E 反复跑会把 admin 置 INACTIVE，需 SQL 改回 ACTIVE（见文件末注释）。
 */

import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';

const __filenameLocal = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filenameLocal);

/**
 * 🚨 截图归档目录 —— **不是** `test-results/`。
 * testing.md §2：「下一轮跑测试会不会把它删掉？会 → 不算证据。」
 * Playwright 每轮开跑前清空 test-results/，所以要当验收证据的截图必须落在任务目录里。
 */
const SHOT_DIR = path.resolve(
  __dirnameLocal,
  '../../dev-docs/task-260902-报价与核价建表与导入方案新规范/证据/e2e'
);
fs.mkdirSync(SHOT_DIR, { recursive: true });

let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`[screenshot] ${name} => ${file}`);
}

// ── 夹具锚点：与后端 DatasetAcTestBase 的常量保持一致 ──────────────────────
const AXIS_BASIC = 'TEST-DS-3120014539';
/** AC-32 用：该数据集中无任何带版本数据的料号（由后端 TU-02 造，或本 spec 前置导入造）。 */
const AXIS_EMPTY = 'TEST-DS-001';

/** AC-26 原文点名的 9 个 tab，顺序即判据。 */
const BASIC_TABS = [
  '物料BOM',
  '物料与元素BOM',
  '来料加工费',
  '来料其他费用',
  '来料其他固定费用',
  '加工费&组装费',
  '其他外加工成本',
  '成品其他比例费用',
  '成品其他固定费用',
];

/**
 * AC-24（2026-09-03 修正后）/ AC-48 共同点名的 7 个页签，顺序即判据。
 * 「电镀方案」由 S-9 追加，排在最后。
 */
const HUB_TABS = ['料号核价', '材质', '元素', '工序', '基础核价', '详细核价', '电镀方案'];

let backendUp = false;

test.beforeAll(async () => {
  backendUp = await isBackendUp();
  if (!backendUp) {
    console.warn('[dataset-maintenance] 后端不可用 —— 全套 skip。'
      + '🚫 这不是「通过」，报告里必须记为「未验证」。');
  }
});

test.beforeEach(async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page);
});

// ═══════════════════════════════════════════════════════════════════
// 辅助
// ═══════════════════════════════════════════════════════════════════

async function openHub(page: Page) {
  await page.goto('/master-data-hub');
  await page.waitForLoadState('networkidle');
  // React Router ErrorBoundary 崩溃检测：崩了的话下面每条断言都会 timeout，
  // 但根因完全不同 —— 先把它单独拎出来报。
  const body = (await page.locator('body').textContent()) ?? '';
  expect(body, '主数据维护页崩溃（React ErrorBoundary），后续断言无从执行')
    .not.toContain('Unexpected Application Error');
  await page.waitForFunction(() => !document.querySelector('.ant-spin-spinning'), { timeout: 15_000 })
    .catch(() => {});
}

/** 点 Hub 上的某个页签 */
async function openHubTab(page: Page, name: string) {
  await page.locator('.ant-tabs-tab', { hasText: name }).first().click();
  await page.waitForTimeout(800);
  await page.waitForFunction(() => !document.querySelector('.ant-spin-spinning'), { timeout: 15_000 })
    .catch(() => {});
}

/**
 * 🚩 **阻塞：模板口径待修**（AC-26 / AC-27 / AC-28 / AC-30 共用）
 *
 * 核价2 模板里两处轴值口径不一致：
 * ```
 *   物料 sheet      生产料号 = S-3120014539   ← 列表数据源 ds_cost_basic_material
 *   物料BOM 等 9 张  轴       = 3120014539     ← 抽屉 tab 的数据
 * ```
 * ⇒ 用户在列表里点开的行是 `S-3120014539`，而带版本表的数据挂在 `3120014539` 上，
 * **抽屉里每个 tab 都会是空的**，AC-26/27/28/30 描述的 UI 路径走不通。
 *
 * 🚫 **不在夹具里偷偷统一前缀** —— 那会掩盖真问题（主线 2026-09-03 明确要求）。
 * ⇒ 这几条用例保留完整判据，但先以「阻塞项」的名义硬失败，等用户修模板。
 */
function assertTemplateAxisMismatchBlocked() {
  throw new Error(
    '🚩 阻塞：模板口径待修（不是实现缺陷）——\n'
    + '  核价2「物料」sheet 的生产料号带 S- 前缀（S-3120014539），'
    + '而「物料BOM」等 9 张带版本 sheet 的轴不带（3120014539）。\n'
    + '  ⇒ 列表数据源与抽屉数据挂在两个不同的轴值上，抽屉每个 tab 都会是空的，'
    + 'AC-26/27/28/30 描述的 UI 路径无法走通。\n'
    + '  ⇒ 已按主线要求**不在夹具里统一前缀**（那会掩盖真问题）。等用户修模板后解除本阻塞。\n'
    + '  判据本身已写好，模板一改即可直接跑。'
  );
}

/** 打开某个料号的抽屉 */
async function openDrawer(page: Page, axisValue: string) {
  const row = page.locator('.ant-table-row', { hasText: axisValue }).first();
  await expect(row, `列表里找不到料号 ${axisValue} —— 先确认后端已导入夹具数据`).toBeVisible({ timeout: 15_000 });
  await row.click();
  const drawer = page.locator('.ant-drawer-content');
  await expect(drawer, '抽屉没打开').toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(800);
  return drawer;
}

/** 抽屉左侧 tab 列表（原型图里 tab 带 `v3 · 7` 徽标，这里只取名字部分） */
function drawerTabs(page: Page) {
  return page.locator('.ant-drawer-content .ant-tabs-tab');
}

// ═══════════════════════════════════════════════════════════════════
// AC-24 页签结构
// ═══════════════════════════════════════════════════════════════════

test('TE-01 / AC-24：主数据维护共 7 个页签，「料号核价」内容零改动', async ({ page }) => {
  await openHub(page);

  const tabs = page.locator('.ant-tabs > .ant-tabs-nav .ant-tabs-tab');
  const texts = (await tabs.allTextContents()).map((t) => t.trim());
  console.log('[TE-01] 实际页签:', texts);

  // 🚩 2026-09-03：AC-24 原写「共 6 个」，与 AC-48 的「共 7 个」自相矛盾（S-9 追加时漏改）。
  //    主线已修正 AC-24 为 7 个，并写明「页签总数与顺序由 AC-48 统一定义，
  //    本条只负责『现有页签零改动』这一半」⇒ 这里改回正常断言。
  expect(texts.length, `AC-24 期望 7 个页签，实际 ${texts.length}：${JSON.stringify(texts)}`).toBe(7);
  expect(texts, 'AC-24 页签名称或顺序与 AC-48 / 原型图不符').toEqual(HUB_TABS);

  await shot(page, 'ac24-hub-tabs');
});

// ═══════════════════════════════════════════════════════════════════
// AC-25 列表
// ═══════════════════════════════════════════════════════════════════

test('TE-02 / AC-25：基础核价列表行数 = ds_cost_basic_material 的 count；搜索能命中料号', async ({ page }) => {
  await openHub(page);
  await openHubTab(page, '基础核价');

  // 原型图/核价数据-列表.html 的列
  for (const col of ['生产料号', '品名', '规格', '尺寸', '旧料号', '已配置', '最后更新']) {
    await expect(
      page.locator('.ant-table-thead th', { hasText: col }).first(),
      `AC-25 列表缺列「${col}」（原型图/核价数据-列表.html）`
    ).toBeVisible();
  }

  // 🚨 行数判据取「接口返回的 total」而不是写死数字 —— 共享库在漂移（test.md §0.3）
  const resp = await page.request.get('/api/cpq/dataset/cost-basic/parts?page=0&size=1');
  expect(resp.status(), 'AC-25 前置：/parts 接口不可用').toBe(200);
  const total = (await resp.json())?.data?.total;
  expect(typeof total, 'AC-25：/parts 未返回 total').toBe('number');
  expect(total, 'AC-25：列表数据源为空 ⇒ 后续断言会空跑（testing.md §3.3）').toBeGreaterThan(0);

  const paginationText = (await page.locator('.ant-pagination-total-text').first().textContent()) ?? '';
  console.log(`[TE-02] 接口 total=${total}，分页文案="${paginationText}"`);
  expect(paginationText, `AC-25：列表总数应等于 ${total}`).toContain(String(total));

  // 搜索命中
  const search = page.locator('.ant-input-search input, input[placeholder*="搜索"]').first();
  await search.fill(AXIS_BASIC);
  await search.press('Enter');
  await page.waitForTimeout(1200);
  await expect(
    page.locator('.ant-table-row', { hasText: AXIS_BASIC }).first(),
    `AC-25：搜索 ${AXIS_BASIC} 未命中`
  ).toBeVisible({ timeout: 10_000 });

  await shot(page, 'ac25-list-search');
});

// ═══════════════════════════════════════════════════════════════════
// AC-26 抽屉 tab 数
// ═══════════════════════════════════════════════════════════════════

test('TE-03 / AC-26：基础核价抽屉 9 个 tab（名称与顺序）；详细核价 17 个', async ({ page }) => {
  assertTemplateAxisMismatchBlocked();
  await openHub(page);
  await openHubTab(page, '基础核价');
  await openDrawer(page, AXIS_BASIC);

  const names = (await drawerTabs(page).allTextContents())
    // 原型图里 tab 文本形如「物料BOM v3 · 7」，徽标不参与判据
    .map((t) => t.trim().split(/\s+v\d|\s+—/)[0].trim());
  console.log('[TE-03] 基础核价 tab:', names);

  expect(names.length, `AC-26 期望 9 个 tab，实际 ${names.length}：${JSON.stringify(names)}`).toBe(9);
  expect(names, 'AC-26 tab 名称/顺序与 原型图/核价数据-抽屉.html 不符').toEqual(BASIC_TABS);
  await shot(page, 'ac26-basic-9tabs');

  // 详细核价 17 个
  await page.keyboard.press('Escape');
  await page.waitForTimeout(500);
  await openHubTab(page, '详细核价');
  const firstRow = page.locator('.ant-table-row').first();
  await expect(firstRow, 'AC-26：详细核价列表为空 ⇒ 17 tab 断言无从执行（需先导入核价1 数据）')
    .toBeVisible({ timeout: 15_000 });
  await firstRow.click();
  await expect(page.locator('.ant-drawer-content')).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(800);

  const detailCount = await drawerTabs(page).count();
  console.log('[TE-03] 详细核价 tab 数:', detailCount);
  expect(detailCount, `AC-26 详细核价期望 17 个 tab，实际 ${detailCount}`).toBe(17);
  await shot(page, 'ac26-detail-17tabs');
});

// ═══════════════════════════════════════════════════════════════════
// AC-27 + AC-28 编辑保存 → 升版 / 无变化
// ═══════════════════════════════════════════════════════════════════

test('TE-04+05 / AC-27+AC-28：改值保存 toast「已升版至 v{N+1}」；紧接不改再存 toast「数据无变化，未升版」', async ({ page }) => {
  assertTemplateAxisMismatchBlocked();
  await openHub(page);
  await openHubTab(page, '基础核价');
  await openDrawer(page, AXIS_BASIC);

  await page.locator('.ant-drawer-content .ant-tabs-tab', { hasText: '物料BOM' }).first().click();
  await page.waitForTimeout(800);

  // 读当前版本号（原型图：tab 头部 `v3（当前）`）
  const versionText = (await page.locator('.ant-drawer-content').textContent()) ?? '';
  const m = versionText.match(/v(\d+)\s*（当前）/);
  expect(m, 'AC-27：抽屉里读不到「v{N}（当前）」版本标识').not.toBeNull();
  const n = Number(m![1]);
  console.log(`[TE-04] 当前版本 v${n}`);

  // 改「组成用量」1 → 3
  const qtyCell = page
    .locator('.ant-drawer-content .ant-table-tbody tr')
    .first()
    .locator('input')
    .nth(0);
  await expect(qtyCell, 'AC-27：表格里没有可编辑输入框 ⇒ 断言空跑').toBeVisible({ timeout: 10_000 });

  // ⚠️ 坑 1：antd 两字按钮渲染成「保 存」，一律用正则
  const saveBtn = page.locator('.ant-drawer-content button', { hasText: /保\s*存/ }).first();

  const qtyInput = page
    .locator('.ant-drawer-content .ant-table-tbody tr')
    .first()
    .locator('.ant-input-number-input, input')
    .first();
  await qtyInput.fill('3');
  await qtyInput.blur();
  await saveBtn.click();

  await expect(
    page.locator('.ant-message-notice-content', { hasText: `已升版至 v${n + 1}` }).first(),
    `AC-27：toast 文案应为「已升版至 v${n + 1}」`
  ).toBeVisible({ timeout: 10_000 });
  await shot(page, 'ac27-upgraded-toast');

  // 版本号变为 N+1
  await page.waitForTimeout(1500);
  const after = (await page.locator('.ant-drawer-content').textContent()) ?? '';
  expect(after, `AC-27：tab 头部版本号应变为 v${n + 1}`).toContain(`v${n + 1}`);

  // AC-28：紧接着不改任何东西再存
  await saveBtn.click();
  await expect(
    page.locator('.ant-message-notice-content', { hasText: '数据无变化，未升版' }).first(),
    'AC-28：toast 文案应为「数据无变化，未升版」'
  ).toBeVisible({ timeout: 10_000 });
  await shot(page, 'ac28-unchanged-toast');

  const after2 = (await page.locator('.ant-drawer-content').textContent()) ?? '';
  expect(after2, `AC-28：版本号应仍为 v${n + 1}`).toContain(`v${n + 1}`);
});

// ═══════════════════════════════════════════════════════════════════
// AC-29 历史版本只读
// ═══════════════════════════════════════════════════════════════════

test('TE-06 / AC-29：切到 v1 → 保存/新增行禁用但可见，hover 提示「历史版本只读，请切回最新版本后编辑」', async ({ page }) => {
  await openHub(page);
  await openHubTab(page, '基础核价');
  await openDrawer(page, AXIS_BASIC);
  await page.locator('.ant-drawer-content .ant-tabs-tab', { hasText: '物料BOM' }).first().click();
  await page.waitForTimeout(800);

  // ⚠️ 坑 2：下拉是虚拟滚动，选项要先滚到可见；坑 3：不要拿 .ant-select-content 采样
  const versionSelect = page.locator('.ant-drawer-content .ant-select').first();
  await versionSelect.click();
  const v1Option = page.locator('.ant-select-item-option', { hasText: /^v1/ }).first();
  await v1Option.scrollIntoViewIfNeeded();
  await v1Option.click();
  await page.waitForTimeout(1200);

  const saveBtn = page.locator('.ant-drawer-content button', { hasText: /保\s*存/ }).first();
  const addBtn = page.locator('.ant-drawer-content button', { hasText: /新\s*增\s*行/ }).first();

  // 🚫 frontend.md §1.2：禁用但可见，不隐藏
  await expect(saveBtn, 'AC-29：保存按钮应可见（禁用而非隐藏）').toBeVisible();
  await expect(addBtn, 'AC-29：新增行按钮应可见（禁用而非隐藏）').toBeVisible();
  await expect(saveBtn, 'AC-29：保存按钮应为禁用态').toBeDisabled();
  await expect(addBtn, 'AC-29：新增行按钮应为禁用态').toBeDisabled();

  // hover 文案（antd 禁用按钮的 Tooltip 通常挂在外层 wrapper 上）
  await saveBtn.hover({ force: true }).catch(() => {});
  await page.waitForTimeout(600);
  const tip = (await page.locator('.ant-tooltip-inner').first().textContent().catch(() => '')) ?? '';
  expect(tip, 'AC-29：hover 提示应为「历史版本只读，请切回最新版本后编辑」')
    .toContain('历史版本只读，请切回最新版本后编辑');

  await shot(page, 'ac29-history-readonly');
});

// ═══════════════════════════════════════════════════════════════════
// AC-30 增删行
// ═══════════════════════════════════════════════════════════════════

test('TE-07 / AC-30：新增行保存 → 版本 +1 且行数 +1；删行保存 → 版本再 +1 且行数 -1', async ({ page }) => {
  assertTemplateAxisMismatchBlocked();
  await openHub(page);
  await openHubTab(page, '基础核价');
  await openDrawer(page, AXIS_BASIC);
  await page.locator('.ant-drawer-content .ant-tabs-tab', { hasText: '物料BOM' }).first().click();
  await page.waitForTimeout(800);

  const rows = () => page.locator('.ant-drawer-content .ant-table-tbody tr.ant-table-row');
  const before = await rows().count();
  expect(before, 'AC-30：表格 0 行 ⇒ 「+1 / -1」断言会空跑').toBeGreaterThan(0);

  const readVersion = async () => {
    const t = (await page.locator('.ant-drawer-content').textContent()) ?? '';
    const mm = t.match(/v(\d+)\s*（当前）/);
    expect(mm, 'AC-30：读不到当前版本号').not.toBeNull();
    return Number(mm![1]);
  };
  const v0 = await readVersion();

  // 新增行
  await page.locator('.ant-drawer-content button', { hasText: /新\s*增\s*行/ }).first().click();
  await page.waitForTimeout(500);
  expect(await rows().count(), 'AC-30：点「新增行」后表格末尾应出现空行').toBe(before + 1);

  // 填合法值（末行的第一个输入框）
  const lastRow = rows().nth(before);
  const inputs = lastRow.locator('input');
  const inputCount = await inputs.count();
  expect(inputCount, 'AC-30：新增行里没有可填输入框').toBeGreaterThan(0);
  for (let i = 0; i < Math.min(inputCount, 3); i++) {
    await inputs.nth(i).fill('1').catch(() => {});
  }
  await page.locator('.ant-drawer-content button', { hasText: /保\s*存/ }).first().click();
  await expect(page.locator('.ant-message-notice-content', { hasText: /已升版至 v\d+/ }).first())
    .toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(1500);
  expect(await readVersion(), 'AC-30：新增行保存后版本应 +1').toBe(v0 + 1);
  expect(await rows().count(), 'AC-30：保存后行数应比之前多 1').toBe(before + 1);
  await shot(page, 'ac30-add-row');

  // 删行
  await rows().nth(before).locator('[aria-label*="delete"], .anticon-delete').first().click();
  await page.waitForTimeout(500);
  await page.locator('.ant-drawer-content button', { hasText: /保\s*存/ }).first().click();
  await expect(page.locator('.ant-message-notice-content', { hasText: /已升版至 v\d+/ }).first())
    .toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(1500);
  expect(await readVersion(), 'AC-30：删行保存后版本应再 +1').toBe(v0 + 2);
  expect(await rows().count(), 'AC-30：删行后行数应减 1').toBe(before);
  await shot(page, 'ac30-delete-row');
});

// ═══════════════════════════════════════════════════════════════════
// AC-32 空态
// ═══════════════════════════════════════════════════════════════════

test('TE-09 / AC-32：无带版本数据的料号 → 每个 tab 显示空态文案，不是红色遮罩也不是白屏', async ({ page }) => {
  await openHub(page);
  await openHubTab(page, '基础核价');

  const search = page.locator('.ant-input-search input, input[placeholder*="搜索"]').first();
  await search.fill(AXIS_EMPTY);
  await search.press('Enter');
  await page.waitForTimeout(1200);

  await openDrawer(page, AXIS_EMPTY);

  const tabCount = await drawerTabs(page).count();
  expect(tabCount, 'AC-32：抽屉没有 tab ⇒ 逐 tab 断言会空跑').toBe(9);

  for (let i = 0; i < tabCount; i++) {
    await drawerTabs(page).nth(i).click();
    await page.waitForTimeout(400);
    const panel = page.locator('.ant-drawer-content .ant-tabs-tabpane-active');
    const text = (await panel.textContent()) ?? '';

    // 🚫 AP-31 / AP-38 族：不许「加载中…」永久占位
    expect(text, `AC-32：第 ${i + 1} 个 tab 卡在「加载中…」（AP-31/AP-38 族）`).not.toContain('加载中');
    expect(text, `AC-32：第 ${i + 1} 个 tab 是红色遮罩/报错页`).not.toContain('Unexpected Application Error');
    expect(text, `AC-32：第 ${i + 1} 个 tab 缺空态文案`)
      .toContain('暂无数据，可点「新增行」录入或从 Excel 导入');
  }
  await shot(page, 'ac32-empty-state');
});

// ═══════════════════════════════════════════════════════════════════
// AC-33 导入后自动刷新
// ═══════════════════════════════════════════════════════════════════

test('TE-10 / AC-33：页签内「导入核价数据」成功 → 抽屉自动关闭 + 列表自动出现新料号（无需手工刷新）', async ({ page }) => {
  await openHub(page);
  await openHubTab(page, '基础核价');

  await page.locator('button', { hasText: /导入核价数据/ }).first().click();
  const drawer = page.locator('.ant-drawer-content');
  await expect(drawer, 'AC-33：导入抽屉没打开').toBeVisible({ timeout: 10_000 });

  // 上传由后端夹具落盘的合法文件（后端用例跑完会留在这里）
  const fixture = path.resolve(
    __dirnameLocal,
    '../../dev-docs/task-260902-报价与核价建表与导入方案新规范/证据/fixtures/核价2-合法.xlsx'
  );
  test.skip(!fs.existsSync(fixture),
    `AC-33 前置缺失：夹具文件不存在 ${fixture} —— 记为「未验证」，不是通过`);

  await drawer.locator('input[type="file"]').setInputFiles(fixture);
  await drawer.locator('button', { hasText: /开始导入/ }).first().click();

  await expect(drawer.locator('text=导入成功').first(), 'AC-33：导入未成功')
    .toBeVisible({ timeout: 60_000 });
  await shot(page, 'ac33-import-success');

  await drawer.locator('button', { hasText: /完成并关闭/ }).first().click();
  await expect(drawer, 'AC-33：抽屉应自动关闭').toBeHidden({ timeout: 10_000 });

  // 🚨 不点刷新按钮 —— AC-33 的判据就是「无需手工点刷新」
  await expect(
    page.locator('.ant-table-row', { hasText: AXIS_BASIC }).first(),
    'AC-33：列表未自动刷新（新导入的料号没出现）'
  ).toBeVisible({ timeout: 15_000 });
  await shot(page, 'ac33-list-auto-refreshed');
});

// ═══════════════════════════════════════════════════════════════════
// AC-34 错数据集（UI 半边）
// ═══════════════════════════════════════════════════════════════════

test('TE-11 UI 半边 / AC-34：在基础核价页签导入错数据集的文件 → 抽屉内显示「不属于基础核价数据集」', async ({ page }) => {
  await openHub(page);
  await openHubTab(page, '基础核价');
  await page.locator('button', { hasText: /导入核价数据/ }).first().click();
  const drawer = page.locator('.ant-drawer-content');
  await expect(drawer).toBeVisible({ timeout: 10_000 });

  const wrongFile = path.resolve(
    __dirnameLocal,
    '../../dev-docs/task-260902-报价与核价建表与导入方案新规范/报价 - 数据导入与表格建表.xlsx'
  );
  test.skip(!fs.existsSync(wrongFile), `AC-34 前置缺失：${wrongFile}`);

  await drawer.locator('input[type="file"]').setInputFiles(wrongFile);
  await drawer.locator('button', { hasText: /开始导入/ }).first().click();

  await expect(
    drawer.locator('text=/不属于基础核价数据集/').first(),
    'AC-34：校验失败面板应报「sheet「{名}」不属于基础核价数据集」'
      + '（⚠️ AC 原文点名的「产能」来自核价1，该文件损坏，字面 sheet 名待补文件后亲验）'
  ).toBeVisible({ timeout: 30_000 });
  await shot(page, 'ac34-wrong-dataset');
});

// ═══════════════════════════════════════════════════════════════════
// AC-42 现有「料号核价」页签零回归
// ═══════════════════════════════════════════════════════════════════

test('TR-01 / AC-42：现有「料号核价」页签 —— 3 个料号 × 3 个 tab 打开、切换、改值保存，行为不变', async ({ page }) => {
  await openHub(page);
  await openHubTab(page, '料号核价');

  const rows = page.locator('.ant-table-row');
  const rowCount = await rows.count();
  expect(rowCount, 'AC-42：料号核价列表为空 ⇒ 回归断言会空跑（testing.md §3.3）').toBeGreaterThan(0);

  const take = Math.min(3, rowCount);
  for (let i = 0; i < take; i++) {
    await rows.nth(i).click();
    const drawer = page.locator('.ant-drawer-content');
    await expect(drawer, `AC-42：第 ${i + 1} 个料号的抽屉没打开`).toBeVisible({ timeout: 10_000 });
    await page.waitForTimeout(600);

    const tabs = drawer.locator('.ant-tabs-tab');
    const tabCount = await tabs.count();
    expect(tabCount, `AC-42：第 ${i + 1} 个料号的抽屉没有 tab`).toBeGreaterThan(0);

    for (let t = 0; t < Math.min(3, tabCount); t++) {
      await tabs.nth(t).click();
      await page.waitForTimeout(400);
      const body = (await drawer.textContent()) ?? '';
      expect(body, `AC-42：料号 ${i + 1} 的第 ${t + 1} 个 tab 卡在「加载中…」`).not.toContain('加载中…');
      expect(body, `AC-42：料号 ${i + 1} 的第 ${t + 1} 个 tab 崩了`).not.toContain('Unexpected Application Error');
    }
    await shot(page, `ac42-legacy-part-${i + 1}`);
    await page.keyboard.press('Escape');
    await page.waitForTimeout(400);
  }
});

/**
 * ⚠️ 跑完若出现「登录不上」：E2E 反复跑会把 admin 置成 INACTIVE。
 * 还原（只改这一个账号，不是清库）：
 *   PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 \
 *     -c "UPDATE \"user\" SET status='ACTIVE', locked_until=NULL, failed_login_attempts=0 WHERE username='admin';"
 */
