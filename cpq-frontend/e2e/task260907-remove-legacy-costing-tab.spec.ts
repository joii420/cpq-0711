/**
 * task-260907「移除主数据维护『料号核价』」· E2E 验收
 *
 * ── 判据来源（🚫 全程不读实现源码）────────────────────────────────────────
 *   dev-docs/task-260907-移除料号核价功能/需求文档.md §③ AC 原文
 *   dev-docs/task-260907-移除料号核价功能/api.md      §1 删除端点 / §2 反向对照端点
 *   dev-docs/task-260907-移除料号核价功能/test.md     §0 五种假绿形态 / §2 夹具
 *
 * ── 🚨 删除类任务的一号风险：假绿（test.md §0）────────────────────────────
 *   本 spec 的断言几乎全是「某某不存在了」，而「不存在」是最容易被环境问题伪装出来的：
 *     · 后端没起来 ⇒ **所有**路径都返 404 ⇒ 「删干净了」全绿
 *     · 料号没数据 ⇒ 表格空 ⇒ 「渲染正常」全过
 *   ⇒ 本文件的纪律：
 *     1. **每条 404 断言必须配一条同轮次的 200 反向对照**（T-4 / T-5 内建，🚫 不许省）
 *     2. **每条「渲染出行」的断言必须先从库里查出期望行数**，再与页面比对；
 *        期望行数为 0 时**硬失败并要求换夹具**，🚫 不许降级成「列表能打开就算过」
 *
 * ── 四个已知 Playwright 选择器坑（cpq-playwright-selector-pitfalls，都表现为 timeout）──
 *   1. 页签一律 `getByRole('tab', {name, exact:true})`，🚫 不用 `getByText(...).first()`
 *      （product-hub-edit-fs.spec.ts:191 实测：文案在页面多处出现，`.first()` 落到不可点击的那个 ⇒ 300s 超时）
 *   2. antd 两字按钮渲染成「保 存」（中间有空格）⇒ 一律用正则 /保\s*存/
 *   3. 下拉是虚拟滚动，选项要先 scrollIntoViewIfNeeded
 *   4. `.ant-select-content` 是采样陷阱，别拿它当选中值
 *   5. 🚨 **antd 6.3.5 起 `.ant-drawer-content` 与 `.ant-select-selection-item` 两个类名都没了**
 *      （2026-09-07 实测：抽屉打开时 `.ant-drawer`=1 / `[role=dialog]`=1 / `.ant-drawer-content`=**0**；
 *        Select 选中值不再有 `.ant-select-selection-item`，值在 `.ant-select` 的 innerText 里）。
 *      症状是**纯 timeout**，长得和「抽屉打不开 / 页面坏了」一模一样，极易误判成产品缺陷。
 *      ⇒ 抽屉一律用 `.ant-drawer`，下拉选中值一律读 `.ant-select` 的 innerText。
 *      📌 `dataset-maintenance.spec.ts` / `dataset-plating-scheme.spec.ts` 里仍是老类名，
 *         它们在 P_before（master）上就已经因此失败 —— 属既存失败，不是本次回归。
 *
 * ── 全局状态纪律（testing.md §4.3）────────────────────────────────────────
 *   本 spec **不动**用户启停用 / 角色权限 / 模板发布态 / 系统开关。
 *   唯一的写库动作是 AC-8a / AC-8b 要求的「改值保存 → 复原」，写的是
 *   `ds_cost_basic_*` / `ds_cost_detail_*`（**不在 AC-12 的 7 张 V6 表清单内**），
 *   且用例在 finally 里把值改回去。🚫 无任何 DELETE / TRUNCATE / 清库动作。
 */
import { test, expect, request as pwRequest, Page, Locator } from '@playwright/test';
import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { loginAs, loginAsAdmin, isBackendUp } from './fixtures/auth';

const __f = fileURLToPath(import.meta.url);

/**
 * 🚨 证据归档目录 —— **不是** `test-results/`。
 * testing.md §2 判据：「下一轮跑测试会不会把它删掉？会 → 不算证据。」
 * Playwright 每轮开跑前清空 test-results/，故验收证据必须落在任务目录里。
 */
const SHOT_DIR = path.resolve(
  path.dirname(__f),
  '../../dev-docs/task-260907-移除料号核价功能/证据/e2e'
);
fs.mkdirSync(SHOT_DIR, { recursive: true });

let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`[screenshot] ${name} => ${file}`);
  return file;
}

// ─────────────────────────── 只读 SQL（夹具实查，不写死数字）───────────────────────────

const DB_NAME = process.env.PW_DB || 'cpq_db_0724';
const DB_HOST = process.env.PW_DB_HOST || '10.177.152.12';
const DB_USER = process.env.PW_DB_USER || 'postgres';
const DB_PASS = process.env.PW_DB_PASS || 'joii5231';

/**
 * 只读单值查询。
 * 🚨 本文件**只跑 SELECT**：下面这个断言是硬闸，写类语句直接抛错，
 *    免得日后有人往这里塞 UPDATE/DELETE（CLAUDE.md §3.2 环境销毁红线）。
 */
function sqlOne(sql: string): string | null {
  if (!/^\s*select/i.test(sql)) {
    throw new Error(`🚨 sqlOne 只允许 SELECT，收到：${sql.slice(0, 80)}`);
  }
  const out = execFileSync(
    'psql',
    ['-h', DB_HOST, '-U', DB_USER, '-d', DB_NAME, '-t', '-A', '-c', sql],
    { env: { ...process.env, PGPASSWORD: DB_PASS }, encoding: 'utf-8' }
  ).trim();
  return out === '' ? null : out;
}

function sqlNum(sql: string): number {
  const v = sqlOne(sql);
  return v === null ? 0 : Number(v);
}

// ─────────────────────────── AC 常量（来自 需求文档.md §③，🚫 禁止就地改数）───────────────────────────

/** AC-1：删除后应剩的 6 个页签，顺序即判据。 */
const HUB_TABS_6 = ['材质', '元素', '工序', '基础核价', '详细核价', '电镀方案'];

/** AC-2：删除后的默认落点。 */
const DEFAULT_TAB = '材质';

/** test.md §2：基础核价与详细核价**唯一**数据充分的料号。 */
const FIXTURE_PART = '3120014539';

/** AC-8c：电镀方案页顶固定说明（逐字判据）。 */
const PLATING_NOTICE =
  '电镀方案为导入维护，如需修改请通过「导入报价数据」/「导入核价数据」重新导入';

/**
 * AC-8a / AC-8b 点名的 sheet ↔ 实际 UI 页签名 ↔ 库表 的三方映射。
 *
 * 🚩 **AC 原文用的是库表口径的名字，UI 页签名并不逐字相同**（2026-09-07 实测）：
 *   AC-8a「元素BOM」  ← UI 实际叫「物料与元素BOM」（表 ds_cost_basic_element_bom）
 *   AC-8a「装配工序费」← UI 实际叫「加工费&组装费」  （表 ds_cost_basic_process_assembly_fee）
 * ⇒ 这里给出别名清单，运行时按「AC 名 → 别名」依次找 tab，并把**实际命中的 tab 名打印出来**，
 *   免得读报告的人以为用例偷换了对象。命名差异已上报主线（🚫 未自行改 AC）。
 *
 * 行数期望**不写死**，一律按 `table` 当场查库（共享库在漂移，写死必然过期）。
 */
type SheetSpec = { acName: string; aliases: string[]; table: string };

const BASIC_SHEETS: SheetSpec[] = [
  { acName: '物料BOM', aliases: ['物料BOM'], table: 'ds_cost_basic_material_bom' },
  { acName: '元素BOM', aliases: ['元素BOM', '物料与元素BOM'], table: 'ds_cost_basic_element_bom' },
  { acName: '装配工序费', aliases: ['装配工序费', '加工费&组装费', '加工费＆组装费'], table: 'ds_cost_basic_process_assembly_fee' },
];

const DETAIL_SHEETS: SheetSpec[] = [
  { acName: '物料BOM', aliases: ['物料BOM'], table: 'ds_cost_detail_material_bom' },
  { acName: '产能', aliases: ['产能'], table: 'ds_cost_detail_capacity' },
  { acName: '设备折旧', aliases: ['设备折旧'], table: 'ds_cost_detail_depreciation' },
];

/** 该 (表, 料号) 在**当前版本**上的行数。0 ⇒ 断言会空跑 ⇒ 用例必须硬失败。 */
function currentVersionRows(table: string, part: string): number {
  return sqlNum(
    `SELECT count(*) FROM ${table} WHERE production_no='${part}'
       AND version_no = (SELECT max(version_no) FROM ${table} WHERE production_no='${part}')`
  );
}

// ─────────────────────────── 前置 ───────────────────────────

let backendUp = false;

test.beforeAll(async () => {
  backendUp = await isBackendUp();
  if (!backendUp) {
    // 🚨 这里绝不能悄悄 skip 就完事：后端没起来时**所有** 404 断言都会「通过」。
    console.warn(
      '[task260907] 🚨 后端不可用 —— 全套 skip。\n' +
      '  🚫 skip **不是**「通过」：删除类任务里，后端没起来会让每一条 404 断言都变绿。\n' +
      '  报告里必须记为「未验证」。'
    );
  }

  // 🚨 夹具实查（test.md §2 明令：执行时必须重查，为空则停下报主线换夹具）
  const basicRows = currentVersionRows('ds_cost_basic_material_bom', FIXTURE_PART);
  const detailRows = currentVersionRows('ds_cost_detail_material_bom', FIXTURE_PART);
  console.log(
    `[task260907] 夹具实查 ${FIXTURE_PART}：` +
    `基础核价物料BOM=${basicRows} 行；详细核价物料BOM=${detailRows} 行`
  );
});

test.beforeEach(async ({ page }) => {
  test.skip(!backendUp, '后端未启动 —— 记为「未验证」，不是通过');
  await loginAsAdmin(page);
});

// ─────────────────────────── 页面动作 ───────────────────────────

/** Hub 顶层页签容器（🚫 不要用全局 `[role=tab]`，抽屉里也有 tab）。 */
function hubTabNav(page: Page): Locator {
  return page.locator('.ant-tabs > .ant-tabs-nav').first();
}

function hubTab(page: Page, name: string): Locator {
  // 坑 1：一律按 role 定位，不按文案 `.first()`
  return hubTabNav(page).getByRole('tab', { name, exact: true });
}

async function openHub(page: Page) {
  await page.goto('/master-data-hub');
  await page.waitForLoadState('networkidle');

  // React ErrorBoundary 崩溃单独拎出来报：崩了的话后面每条断言都 timeout，但根因完全不同
  const body = (await page.locator('body').textContent()) ?? '';
  expect(body, '🚨 主数据维护页崩溃（React ErrorBoundary），后续断言无从执行')
    .not.toContain('Unexpected Application Error');

  await page.waitForSelector('.ant-tabs > .ant-tabs-nav [role="tab"]', { timeout: 30_000 });
  await page.waitForFunction(() => !document.querySelector('.ant-spin-spinning'), { timeout: 15_000 })
    .catch(() => {});
}

async function hubTabNames(page: Page): Promise<string[]> {
  return (await hubTabNav(page).locator('[role="tab"]').allTextContents()).map((t) => t.trim());
}

async function clickHubTab(page: Page, name: string) {
  const tab = hubTab(page, name);
  await expect(tab, `Hub 页签「${name}」不存在或不可见`).toBeVisible({ timeout: 15_000 });
  await tab.click();
  await page.waitForTimeout(1000);
  await page.waitForFunction(() => !document.querySelector('.ant-spin-spinning'), { timeout: 20_000 })
    .catch(() => {});
}

/** 在当前页签的列表里搜料号。 */
async function searchPart(page: Page, keyword: string) {
  const box = page.locator('.ant-tabs-tabpane-active input.ant-input').first();
  await expect(box, '搜索框不可见').toBeVisible({ timeout: 15_000 });
  await box.fill(keyword);
  await page.waitForTimeout(1500);
  await page.waitForFunction(() => !document.querySelector('.ant-spin-spinning'), { timeout: 20_000 })
    .catch(() => {});
}

/** 点开某个料号的抽屉。⚠️ 用 cell 精确匹配，避免 S-3120014539 与 3120014539 串行。 */
async function openPartDrawer(page: Page, part: string) {
  const cell = page.locator('.ant-tabs-tabpane-active')
    .getByRole('cell', { name: part, exact: true }).first();
  await expect(cell, `列表里找不到料号 ${part} —— 夹具已漂移，停下报主线换夹具`)
    .toBeVisible({ timeout: 20_000 });
  await cell.click();
  const drawer = page.locator('.ant-drawer').first();
  await expect(drawer, '抽屉没打开').toBeVisible({ timeout: 20_000 });
  await page.waitForTimeout(2000);
  return drawer;
}

async function closeDrawer(page: Page) {
  await page.keyboard.press('Escape');
  await page.waitForTimeout(800);
}

/**
 * 在抽屉里按 SheetSpec 找并点开 sheet tab。
 * 返回**实际命中的 tab 名**（写进日志/报告，证明没有偷换对象）。
 */
async function clickSheetTab(drawer: Locator, spec: SheetSpec): Promise<string> {
  const all = (await drawer.locator('[role="tab"]').allTextContents()).map((t) => t.trim());
  for (const alias of spec.aliases) {
    const hit = all.find((t) => t === alias || t.startsWith(alias));
    if (hit) {
      await drawer.locator('[role="tab"]').filter({ hasText: new RegExp(`^${escapeRe(alias)}`) })
        .first().click();
      await drawer.page().waitForTimeout(2500);
      return hit;
    }
  }
  throw new Error(
    `🚨 抽屉里找不到 AC 点名的 sheet「${spec.acName}」（别名尝试过 ${JSON.stringify(spec.aliases)}）。\n` +
    `  实际 tab 清单 = ${JSON.stringify(all)}\n` +
    `  两种可能，处置不同：① AC 与 UI 文案口径不一致（文档问题，报主线）；` +
    `② 本次改动把公共件弄坏了（产品缺陷）。不要自行改断言。`
  );
}

function escapeRe(s: string) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function drawerRows(drawer: Locator): Locator {
  return drawer.locator('.ant-tabs-tabpane-active .ant-table-tbody tr.ant-table-row');
}

// ═══════════════════════════════════════════════════════════════════
// T-1 / AC-1 · 页签集合
// ═══════════════════════════════════════════════════════════════════

test('T-1 / AC-1：/master-data-hub 页签集合逐字等于 6 项，且不含「料号核价」', async ({ page }) => {
  await openHub(page);
  const tabs = await hubTabNames(page);
  console.log('[T-1] 实际页签 =', JSON.stringify(tabs));

  // 🚨 反向对照：先证明「读到的确实是页签」。读空了的话下面的 not.toContain 会恒真 ⇒ 假绿。
  expect(tabs.length, 'T-1 前置：一个页签都没读到 ⇒ 下面的「不含料号核价」是空跑').toBeGreaterThan(0);

  expect(tabs, `AC-1：页签集合应逐字等于 ${JSON.stringify(HUB_TABS_6)}，实际 ${JSON.stringify(tabs)}`)
    .toEqual(HUB_TABS_6);
  expect(tabs, 'AC-1：「料号核价」仍在页签里 ⇒ 入口没摘干净').not.toContain('料号核价');

  await shot(page, 'T1-AC1-六页签');
});

// ═══════════════════════════════════════════════════════════════════
// T-2 / AC-2 · 默认落点
// ═══════════════════════════════════════════════════════════════════

test('T-2 / AC-2：不点任何页签，默认落在「材质」并渲染出表格，无红屏无 console.error', async ({ page }) => {
  const consoleErrors: string[] = [];
  const pageErrors: string[] = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text().slice(0, 300)); });
  page.on('pageerror', (e) => pageErrors.push(String(e).slice(0, 300)));

  await openHub(page);
  // 🚫 全程不点任何页签

  const selected = hubTabNav(page).locator('[role="tab"][aria-selected="true"]');
  await expect(selected, 'AC-2：没有任何页签处于选中态').toHaveCount(1);
  const selectedName = (await selected.textContent())?.trim();
  console.log('[T-2] aria-selected=true 的页签 =', JSON.stringify(selectedName));
  expect(selectedName, `AC-2：默认落点应为「${DEFAULT_TAB}」，实际「${selectedName}」`).toBe(DEFAULT_TAB);

  // 内容区渲染出表格
  const table = page.locator('.ant-tabs-tabpane-active [role="table"], .ant-tabs-tabpane-active .ant-table');
  await expect(table.first(), 'AC-2：材质页签内容区没有渲染出表格').toBeVisible({ timeout: 20_000 });

  // 🚨 反向对照：不只看到 <table> 壳，还要有真实行 —— 否则「渲染正常」是空跑
  const rows = page.locator('.ant-tabs-tabpane-active .ant-table-tbody tr.ant-table-row');
  const uiRows = await rows.count();
  const dbRows = sqlNum('SELECT count(*) FROM material_recipe');
  console.log(`[T-2] 材质页首屏行数=${uiRows}；库 material_recipe 总行数=${dbRows}`);
  expect(dbRows, 'T-2 前置：material_recipe 为空 ⇒ 断言空跑，停下报主线换夹具').toBeGreaterThan(0);
  expect(uiRows, 'AC-2：材质页首屏 0 行 ⇒「渲染出材质列表」这句没被验证到').toBeGreaterThan(0);

  const body = (await page.locator('body').textContent()) ?? '';
  expect(body, 'AC-2：页面出现红色遮罩 / ErrorBoundary').not.toContain('Unexpected Application Error');

  console.log('[T-2] console.error =', JSON.stringify(consoleErrors));
  console.log('[T-2] pageerror   =', JSON.stringify(pageErrors));
  expect(pageErrors, 'AC-2：出现未捕获运行时异常').toEqual([]);

  // 🚩 **归因说明（2026-09-07 实测 A/B，写在断言旁边，免得下一个人误判成本次回归）**：
  //    改动前 master(5174) 默认落点上就有 2 条 antd v6 弃用告警
  //      Warning: [antd: Tabs] `destroyInactiveTabPane` is deprecated…
  //      Warning: [antd: Drawer] `width` is deprecated…
  //    本分支多出的第 3 条 `[antd: Modal] destroyOnClose is deprecated` 来自**材质页**组件，
  //    该组件本任务一个字节都没改 —— 它只是因为默认落点从原首位页签换成了「材质」而提前渲染。
  //    ⇒ 三条都不是本次引入的缺陷，但 AC-2 原文写的是「无 console.error」，字面上无法满足。
  //    🚫 **刻意不在这里加白名单**（那是降级断言）。保留硬断言，由主线裁决：
  //       改 AC（把判据收窄成「无 pageerror + 无新增 console.error」）还是改代码（换掉 3 个弃用 prop）。
  expect(consoleErrors, 'AC-2 原文要求「无 console.error」。\n' +
    '  ⚠️ 归因：命中的若全是 antd 弃用告警（destroyInactiveTabPane / destroyOnClose / width），\n' +
    '     则改动前 master 上同样存在（实测 2 条），**不是本次回归** —— 见用例内注释。')
    .toEqual([]);

  await shot(page, 'T2-AC2-默认落材质');
});

// ═══════════════════════════════════════════════════════════════════
// T-3 / AC-3 · 「导入核价数据」按钮消失
// ═══════════════════════════════════════════════════════════════════

/**
 * 🚨 **2026-09-07 判据整条重写**（主线修正 AC-3 后同步；🚫 不是我自行降级断言）。
 *
 * 原判据「页面上不存在文案含『导入核价数据』的按钮」**必然失败且不该修**：
 *   · `datasetConfig.ts:36,44` —— 基础核价 / 详细核价两个页签的导入按钮文案**就叫**「导入核价数据」
 *     （`task-260902` 的交付物，必须保留）
 *   · `PlatingSchemeTab.tsx:30` 的只读提示逐字含这四个字，而 **AC-8c② 恰恰要求它逐字保留**
 *   ⇒ 两条 AC 内部打架，「修好」它等于破坏 task-260902 的交付。
 *
 * ⇒ 新判据是**行为**判据，不是文案判据：入口还在、请求打向新端点、旧端点一次都没被调。
 * 📌 通用教训（已写进 AC）：删除类判据要断言**请求去向**，不要断言「界面上不能出现某字符串」。
 */
test('T-3 / AC-3：两个页签的「导入核价数据」入口仍在且打向 /dataset/{ds}/import；全程不出现 /v6/pricing 请求', async ({ page }) => {
  test.setTimeout(240_000);

  // ── 全程网络取证（③ 的观察通道），必须在任何导航之前挂上 ──
  const allReq: string[] = [];
  page.on('request', (r) => {
    const u = r.url();
    if (u.includes('/api/cpq/')) allReq.push(`${r.method()} ${u.split('/api/cpq')[1]}`);
  });

  await openHub(page);

  const importUrls: string[] = [];
  for (const [tabName, ds] of [['基础核价', 'cost-basic'], ['详细核价', 'cost-detail']] as const) {
    await clickHubTab(page, tabName);

    // ── ① 🔁 反向对照（先做）：按钮必须仍在且可点开，误删即不通过 ──
    const btn = page.locator('.ant-tabs-tabpane-active button', { hasText: /导入核价数据/ }).first();
    await expect(btn,
      `AC-3 ①：「${tabName}」页签的「导入核价数据」按钮不见了 ⇒ 误删了 task-260902 的交付物（越界）`)
      .toBeVisible({ timeout: 20_000 });
    await btn.click();

    const drawer = page.locator('.ant-drawer').first();
    await expect(drawer, `AC-3 ①：「${tabName}」的导入抽屉没打开`).toBeVisible({ timeout: 20_000 });
    const title = ((await page.locator('.ant-drawer-title').first().textContent()) ?? '').replace(/\s+/g, '');
    console.log(`[T-3] 「${tabName}」导入抽屉标题 = ${JSON.stringify(title)}`);
    await shot(page, `T3-AC3-${tabName}-导入抽屉`);

    // ── ② 走到上传，取证请求 URL ──
    //    🚨 刻意上传一个**非法文件**：整份拒收语义 ⇒ 请求发得出去、但一行都不会写进共享库。
    //       用合法夹具会真的导入数据（污染共享库），本条只需要证明 URL 去向。
    const importReqs: string[] = [];
    const onReq = (r: any) => {
      const u = r.url();
      if (/\/(import)(\?|$)/.test(u) || u.includes('basic-data-import')) importReqs.push(`${r.method()} ${u.split('/api/cpq')[1] ?? u}`);
    };
    page.on('request', onReq);

    const fileInput = drawer.locator('input[type="file"]').first();
    await fileInput.setInputFiles({
      name: 'task260907-invalid.xlsx',
      mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      buffer: Buffer.from('not-a-real-xlsx'),
    });
    await page.waitForTimeout(800);
    const startBtn = drawer.locator('button', { hasText: /开始导入/ }).first();
    if (await startBtn.count()) {
      await startBtn.click();
      await page.waitForTimeout(6000);
    }
    page.off('request', onReq);

    console.log(`[T-3] 「${tabName}」上传阶段发出的导入类请求 =`, JSON.stringify(importReqs));
    // 🚨 反向对照：一个请求都没抓到 ⇒ 下面的 URL 断言是空跑
    expect(importReqs.length,
      `AC-3 ②：「${tabName}」点「开始导入」后一个导入类请求都没抓到 ⇒ URL 断言空跑。\n` +
      '  可能是按钮文案变了 / 前端做了前置校验没发请求 —— 先查清楚再定性。')
      .toBeGreaterThan(0);
    expect(importReqs.some((u) => u.includes(`/dataset/${ds}/import`)),
      `AC-3 ②：「${tabName}」的导入请求应指向 /api/cpq/dataset/${ds}/import，实际 ${JSON.stringify(importReqs)}`)
      .toBe(true);
    importUrls.push(...importReqs);

    await page.keyboard.press('Escape');
    await page.waitForTimeout(1000);
  }

  // ── ③ 全程 Network 不出现旧端点 ──
  const legacyHits = allReq.filter((u) =>
    u.includes('/basic-data-import/v6/pricing') || u.includes('/pricing-basic-data'));
  console.log(`[T-3] 本次共抓到 ${allReq.length} 条 /api/cpq 请求；旧端点命中 =`, JSON.stringify(legacyHits));
  // 🚨 反向对照：一条请求都没抓到 ⇒ 「不出现旧端点」是空跑
  expect(allReq.length,
    'AC-3 ③ 前置：整轮一条 /api/cpq 请求都没抓到 ⇒ 观察通道没接上，「不出现旧端点」是空断言')
    .toBeGreaterThan(0);
  expect(legacyHits,
    'AC-3 ③：仍有请求打向已下线的旧端点：' + JSON.stringify(legacyHits)).toEqual([]);

  // ── ④ 电镀方案只读提示逐字保留（与 AC-8c② 一致）──
  await clickHubTab(page, '电镀方案');
  const platingText = ((await page.locator('.ant-tabs-tabpane-active').innerText()) ?? '').replace(/\s+/g, '');
  expect(platingText,
    `AC-3 ④：电镀方案的只读提示应逐字保留（含「导入核价数据」字样）：${PLATING_NOTICE}`)
    .toContain(PLATING_NOTICE.replace(/\s+/g, ''));

  await shot(page, 'T3-AC3-电镀方案说明逐字保留');
});

// ═══════════════════════════════════════════════════════════════════
// T-4 / AC-4 · 维护端点已下线（含反向对照）
// ═══════════════════════════════════════════════════════════════════

test('T-4 / AC-4：/pricing-basic-data/* 返 404；同轮次反向对照 /dataset/cost-basic/parts 返 200', async ({ page }) => {
  await loginAsAdmin(page);

  // ── 被删的端点 ──
  const r1 = await page.request.get('/api/cpq/pricing-basic-data/parts?page=1&size=1');
  const r2 = await page.request.get('/api/cpq/pricing-basic-data/sheets');
  const b1 = (await r1.text()).slice(0, 200);
  const b2 = (await r2.text()).slice(0, 200);
  console.log(`[T-4] GET /pricing-basic-data/parts  -> ${r1.status()}  body=${JSON.stringify(b1)}`);
  console.log(`[T-4] GET /pricing-basic-data/sheets -> ${r2.status()}  body=${JSON.stringify(b2)}`);

  // ── 🚨 反向对照（同一次会话、同一轮请求）──
  //    没有这一步，「后端根本没起来」会让上面两条 404 全绿（test.md §0 进程级假绿）。
  const rc = await page.request.get('/api/cpq/dataset/cost-basic/parts?page=0&size=1');
  const rcBody = await rc.text();
  let rcJson: any = null;
  try { rcJson = JSON.parse(rcBody); } catch { /* 保留原文报错 */ }
  console.log(`[T-4 反向对照] GET /dataset/cost-basic/parts -> ${rc.status()} body=${JSON.stringify(rcBody.slice(0, 200))}`);

  expect(rc.status(),
    '🚨 AC-4 反向对照失败：/dataset/cost-basic/parts 不是 200 ⇒ **后端根本没在正常服务**，\n' +
    '   此时上面两条 404 证明不了任何事（进程级假绿）。先修环境再谈删除是否成功。').toBe(200);
  expect(rcJson?.data, 'AC-4 反向对照：/dataset/cost-basic/parts 的 data 为 null ⇒ 服务半死').not.toBeNull();

  // 反向对照通过之后，404 才是有意义的
  expect(r1.status(), 'AC-4：GET /pricing-basic-data/parts 应为 404').toBe(404);
  expect(r2.status(), 'AC-4：GET /pricing-basic-data/sheets 应为 404').toBe(404);
});

// ═══════════════════════════════════════════════════════════════════
// T-5 / AC-5 · 导入端点已下线（含反向对照）
// ═══════════════════════════════════════════════════════════════════

/**
 * 🚨 **2026-09-07 判据修正**（主线修正 AC-5 后同步）：
 *   `POST /v6/pricing` 删掉后**不是 404 而是 405** —— `/pricing` 是单段路径，
 *   落到同类保留的 `@GET @Path("/{recordId}")` 模板上；JAX-RS **先匹路径再匹方法** ⇒ 405。
 *
 * 🚨 **而「等于 405」这个绝对值不是证据** —— 乱打 `POST /v6/zzz-not-a-path` 在 master 上**也是 405**
 *   （那是 `/{recordId}` 模板的既有兜底）。⇒ **主判据是「同一请求：master 401 → 本分支 405」的变化量。**
 *   本用例因此会额外开一个指向 master 后端的 APIRequestContext 做 A/B。
 */
test('T-5 / AC-5：POST /v6/pricing 由 master 的 401 变为本分支的 405（A/B 变化量）；template 返 404；反向对照 /v6/{recordId} 返 200', async ({ page }) => {
  test.setTimeout(180_000);
  await loginAsAdmin(page);

  // 📌 recordId 是**夹具不是断言值** ⇒ 执行时实查，🚫 不写死 UUID（写死会在数据清理后变假红）
  const recordId = sqlOne('SELECT id FROM import_record ORDER BY created_at DESC LIMIT 1');
  expect(recordId, 'T-5 前置：import_record 一行都没有 ⇒ 反向对照没有夹具，停下报主线').not.toBeNull();
  console.log('[T-5] 实查 recordId =', recordId);

  const multipart = {
    file: { name: 'x.xlsx', mimeType: 'application/octet-stream', buffer: Buffer.from('x') },
  };

  // ── B 侧：本分支后端（PW_BACKEND_URL，默认临时端口）──
  const rp = await page.request.post('/api/cpq/basic-data-import/v6/pricing', { multipart });
  const rt = await page.request.get('/api/cpq/basic-data-import/v6/pricing/template');
  const rz = await page.request.post('/api/cpq/basic-data-import/v6/zzz-not-a-path', { multipart });
  console.log(`[T-5·B 本分支] POST /v6/pricing          -> ${rp.status()}`);
  console.log(`[T-5·B 本分支] GET  /v6/pricing/template -> ${rt.status()}`);
  console.log(`[T-5·B 本分支] POST /v6/zzz-not-a-path   -> ${rz.status()}  ← 405 的既有兜底，说明绝对值不是证据`);

  // ── 🚨 反向对照（先做）：与被删的两个端点**同在一个 Resource 类**里 ──
  const rr = await page.request.get(`/api/cpq/basic-data-import/v6/${recordId}`);
  const rrBody = await rr.text();
  let rrJson: any = null;
  try { rrJson = JSON.parse(rrBody); } catch { /* noop */ }
  console.log(`[T-5 反向对照] GET /v6/${recordId} -> ${rr.status()} body=${JSON.stringify(rrBody.slice(0, 200))}`);
  expect(rr.status(),
    '🚨 AC-5 反向对照失败：GET /basic-data-import/v6/{recordId} 不是 200。\n' +
    '   两种可能：① 后端没起来（进程级假绿，下面的状态码全部不作数）；\n' +
    '   ② **整个 Resource 类被误删了**（越界，本任务只许摘两个方法）。').toBe(200);
  expect(rrJson?.data, 'AC-5 反向对照：data 为 null ⇒ 端点在但读不到记录').not.toBeNull();

  // ── A 侧：master 后端（改动前）──
  const masterUrl = process.env.PW_MASTER_BACKEND_URL || 'http://localhost:8081';
  const isSameHost = masterUrl === (process.env.PW_BACKEND_URL || 'http://localhost:8081');
  console.log(`[T-5·A master] backend = ${masterUrl}（与本分支同址？${isSameHost}）`);
  expect(isSameHost,
    '🚨 T-5 前置：A/B 两侧指向同一个后端 ⇒ 变化量恒为 0，本条判据失效。\n' +
    '  请用 PW_BACKEND_URL 指向本分支临时后端、PW_MASTER_BACKEND_URL 指向 master 的 8081。')
    .toBe(false);

  const masterCtx = await pwRequest.newContext({ baseURL: masterUrl });
  let mPricing = -1, mZzz = -1;
  try {
    mPricing = (await masterCtx.post('/api/cpq/basic-data-import/v6/pricing', { multipart })).status();
    mZzz = (await masterCtx.post('/api/cpq/basic-data-import/v6/zzz-not-a-path', { multipart })).status();
  } finally {
    await masterCtx.dispose();
  }
  console.log(`[T-5·A master] POST /v6/pricing        -> ${mPricing}`);
  console.log(`[T-5·A master] POST /v6/zzz-not-a-path -> ${mZzz}`);

  // 🚨 阳性对照：master 上 /pricing 与 /zzz 的状态码必须**不同**。
  //    相同 ⇒ master 侧观察通道本身就分辨不出「端点存在」与「端点不存在」，A/B 变化量无从谈起。
  expect(mPricing,
    `🚨 T-5 阳性对照失败：master 上 POST /v6/pricing (${mPricing}) 与 POST /v6/zzz-not-a-path (${mZzz}) ` +
    '状态码相同 ⇒ 该观察通道分辨不出端点存不存在，本条 A/B 判据无效。\n' +
    '  （预期 master：/pricing = 401「端点在、鉴权拦下」；/zzz = 405「路径落到 /{recordId} 模板」）')
    .not.toBe(mZzz);

  // ── 主判据：变化量 ──
  expect({ master: mPricing, branch: rp.status() },
    'AC-5 主判据：同一个 POST /v6/pricing 请求应从 master 的 401 变为本分支的 405。\n' +
    `  实际 master=${mPricing} 本分支=${rp.status()}。\n` +
    '  ⚠️ 🚫 不要只看「本分支 = 405」就判通过 —— 乱打不存在的路径也是 405。')
    .toEqual({ master: 401, branch: 405 });

  expect(rt.status(), 'AC-5：GET /basic-data-import/v6/pricing/template 应为 404').toBe(404);
});

// ═══════════════════════════════════════════════════════════════════
// T-8a / AC-8a · 基础核价 读写序列（本任务最大风险点：公共件迁移后回归）
// ═══════════════════════════════════════════════════════════════════

/**
 * 序列覆盖（testing.md §3.2 要求的「序列」这一类）：
 *   打开 → 搜索 → 开抽屉 → 切 3 个 sheet（中间态：每个都出真实行）
 *   → 改值 → 保存 → 关抽屉 → 重开（最终态：值已持久化）→ 复原
 */
async function runSheetSequence(page: Page, tabName: string, sheets: SheetSpec[], tag: string) {
  // ── 前置：三个 sheet 的期望行数一律当场查库，为 0 直接硬失败 ──
  const expected: Record<string, number> = {};
  for (const s of sheets) {
    expected[s.acName] = currentVersionRows(s.table, FIXTURE_PART);
  }
  console.log(`[${tag}] 库中 ${FIXTURE_PART} 当前版本行数 =`, JSON.stringify(expected));
  for (const s of sheets) {
    expect(expected[s.acName],
      `🚨 ${tag} 前置：${s.table} 上料号 ${FIXTURE_PART} 当前版本 0 行 ⇒ 本用例所有渲染断言都会空跑。\n` +
      `   test.md §2 明令：夹具为空 **停下来报主线重挑夹具**，🚫 不许降级成「列表能打开就算过」。`)
      .toBeGreaterThan(0);
  }

  await openHub(page);
  await clickHubTab(page, tabName);
  await searchPart(page, FIXTURE_PART);
  const drawer = await openPartDrawer(page, FIXTURE_PART);

  // ── 中间态：三个 sheet 都渲染出 ≥1 行真实数据 ──
  const hitNames: string[] = [];
  for (const s of sheets) {
    const actualTab = await clickSheetTab(drawer, s);
    hitNames.push(`${s.acName}→${actualTab}`);
    const n = await drawerRows(drawer).count();
    const body = (await drawer.textContent()) ?? '';
    console.log(`[${tag}] sheet「${actualTab}」UI 行数=${n}，库期望=${expected[s.acName]}`);

    expect(body, `AC-8：「${actualTab}」卡在「加载中…」`).not.toContain('加载中…');
    expect(body, `AC-8：「${actualTab}」出现红色遮罩 / ErrorBoundary`).not.toContain('Unexpected Application Error');
    expect(n, `AC-8：「${actualTab}」渲染出 ${n} 行，库里当前版本有 ${expected[s.acName]} 行 ⇒ ` +
      `0 行即空态，AC 原文明确「不得是空态」`).toBeGreaterThan(0);
    await shot(page, `${tag}-sheet-${actualTab.replace(/[\\/:*?"<>|&]/g, '')}`);
  }
  console.log(`[${tag}] AC 名 → 实际 tab 名映射 =`, JSON.stringify(hitNames));

  // ── 改值 → 保存 → 重开 → 断言持久化 → 复原 ──
  // 回到第一个 sheet（物料BOM）改一个数值单元格
  const firstTab = await clickSheetTab(drawer, sheets[0]);
  const numInput = drawer.locator('.ant-tabs-tabpane-active .ant-input-number-input').first();
  await expect(numInput,
    `AC-8：「${firstTab}」里找不到可编辑的数字单元格 ⇒ 无法执行「改值保存」序列。\n` +
    '  两种可能：① 该 sheet 无数值列（AC 选错 sheet，报主线）；\n' +
    '  ② 公共件 EditableSheetTable 迁移后失去编辑能力（**产品缺陷，本任务的最大风险点**）。')
    .toBeVisible({ timeout: 15_000 });

  const before = (await numInput.inputValue()).trim();
  const beforeNum = Number(before || '0');
  const newVal = String(beforeNum + 1);
  console.log(`[${tag}] 改值：${JSON.stringify(before)} -> ${newVal}`);

  let saved = false;
  try {
    await numInput.click();
    await numInput.fill(newVal);
    await numInput.blur();
    await page.waitForTimeout(500);

    // 坑 2：两字按钮渲染成「保 存」
    const saveBtn = drawer.getByRole('button', { name: /保\s*存/ }).first();
    await expect(saveBtn, `AC-8：「${firstTab}」没有保存按钮 ⇒ 序列走不下去`).toBeVisible({ timeout: 15_000 });
    await saveBtn.click();
    await page.waitForTimeout(3000);

    const toasts = await page.locator('.ant-message-notice-content, .ant-message-success, .ant-message-info')
      .allTextContents();
    console.log(`[${tag}] 保存提示 =`, JSON.stringify(toasts.map((t) => t.replace(/\s+/g, ' ').trim())));
    expect(toasts.join(' '), 'AC-8 步骤 5：保存后应出现保存成功提示').toMatch(/已保存|已升版|成功|未变化|无变化/);
    saved = true;
    await shot(page, `${tag}-saved`);

    // ── 最终态：关抽屉 → 重开同料号同 sheet → 值已持久化 ──
    await closeDrawer(page);
    const drawer2 = await openPartDrawer(page, FIXTURE_PART);
    await clickSheetTab(drawer2, sheets[0]);
    const readBack = (await drawer2.locator('.ant-tabs-tabpane-active .ant-input-number-input')
      .first().inputValue()).trim();
    console.log(`[${tag}] 重开读回 = ${JSON.stringify(readBack)}（期望 ${newVal}）`);
    expect(Number(readBack),
      `AC-8 步骤 7：重开后该单元格应为 ${newVal}，实际 ${readBack} ⇒ 保存没落库或读回走了别的行`)
      .toBeCloseTo(Number(newVal), 6);
    await shot(page, `${tag}-persisted`);
  } finally {
    // ── 收尾：改回原值（AC-8a 步骤 8）。🚫 失败也要尽力还原，不留脏数据 ──
    //
    // 🚨 **2026-09-07 事故修正 —— 这一段原来只打印「已复原」，从不回读校验，结果谎报了一次。**
    //    实测：`item_seq`（项次）从 10 改到 11 能存下并升版，但**改回 11→10 后端返回
    //    `{"result":"UNCHANGED","message":"数据无变化，未升版"}`，编辑被静默丢弃**，
    //    于是共享库上留下了脏数据，而报告里写着「已复原」。
    //    ⇒ 现在：① 捕获 PUT 的响应体；② 关抽屉重开**回读**实际值；
    //       ③ 对不上就 **console.error 大声报**，并把「怎么人工复原」写进日志。
    //    🚫 刻意不在这里做 SQL 直改 —— 那是 CLAUDE.md §3.2 的写库操作，测试代理没有批准权，
    //       而且绕过版本化写入器会留下不一致的 row_fingerprint。停下来报主线才是正确动作。
    if (saved) {
      let putBody = '';
      const onResp = async (r: any) => {
        if (r.request().method() === 'PUT' && r.url().includes('/rows')) {
          putBody = (await r.text().catch(() => '')).slice(0, 300);
        }
      };
      page.on('response', onResp);
      try {
        const d = page.locator('.ant-drawer').first();
        const visible = await d.isVisible().catch(() => false);
        const dd = visible ? d : await (async () => {
          await openHub(page);
          await clickHubTab(page, tabName);
          await searchPart(page, FIXTURE_PART);
          return openPartDrawer(page, FIXTURE_PART);
        })();
        await clickSheetTab(dd, sheets[0]);
        const inp = dd.locator('.ant-tabs-tabpane-active .ant-input-number-input').first();
        await inp.click();
        await inp.fill(before);
        await inp.blur();
        await page.waitForTimeout(600);
        await dd.getByRole('button', { name: /保\s*存/ }).first().click();
        await page.waitForTimeout(4000);
        console.log(`[${tag}] 复原保存的 PUT 响应 = ${JSON.stringify(putBody)}`);

        // 🚨 回读校验：不回读就等于没复原（本段事故的直接教训）
        await closeDrawer(page);
        const d2 = await openPartDrawer(page, FIXTURE_PART);
        await clickSheetTab(d2, sheets[0]);
        const actual = (await d2.locator('.ant-tabs-tabpane-active .ant-input-number-input')
          .first().inputValue()).trim();
        if (actual === before) {
          console.log(`[${tag}] ✅ 复原已回读确认 = ${JSON.stringify(actual)}`);
        } else {
          console.error(
            `[${tag}] 🚨🚨 复原失败且被后端静默吞掉：期望 ${JSON.stringify(before)}，回读 ${JSON.stringify(actual)}。\n` +
            `  PUT 响应 = ${putBody}\n` +
            `  ⇒ 共享库 ${sheets[0].table}（料号 ${FIXTURE_PART}）上残留了本次测试的脏数据。\n` +
            `  🚫 测试代理**不得**用 SQL 直改复原（§3.2 + 会破坏 row_fingerprint 一致性）。\n` +
            `  ⇒ 停下来报主线，由主线裁决复原方式。`);
        }
      } catch (e) {
        console.error(`[${tag}] 🚨 复原过程抛错，共享库可能残留脏数据（需人工复核）：${String(e).slice(0, 300)}`);
      } finally {
        page.off('response', onResp);
      }
    }
  }
}

test('T-8a / AC-8a：基础核价 —— 三个 sheet 出真实行 + 改值保存 + 重开持久化 + 复原', async ({ page }) => {
  test.setTimeout(300_000);
  await runSheetSequence(page, '基础核价', BASIC_SHEETS, 'T8a');
});

test('T-8b / AC-8b：详细核价 —— 三个 sheet 出真实行 + 改值保存 + 重开持久化 + 复原', async ({ page }) => {
  test.setTimeout(300_000);
  await runSheetSequence(page, '详细核价', DETAIL_SHEETS, 'T8b');
});

// ═══════════════════════════════════════════════════════════════════
// T-8c / AC-8c · 电镀方案只读形态
// ═══════════════════════════════════════════════════════════════════

test('T-8c / AC-8c：电镀方案切「详细核价」后出行；说明文案逐字；无增删改存按钮；点单元格不进编辑态', async ({ page }) => {
  test.setTimeout(180_000);

  const dbRows = sqlNum('SELECT count(*) FROM ds_cost_detail_plating_scheme');
  console.log(`[T-8c] 库 ds_cost_detail_plating_scheme = ${dbRows} 行`);
  expect(dbRows, 'T-8c 前置：电镀方案（详细核价）0 行 ⇒ 断言空跑，停下报主线换夹具').toBeGreaterThan(0);

  await openHub(page);
  await clickHubTab(page, '电镀方案');

  // 默认数据集应为「报价」
  // 🚨 antd v6 没有 `.ant-select-selection-item` 了（实测 count=0）；选中值读 `.ant-select` 的 innerText。
  //    第 2 个 `.ant-select` 是分页的「20 / page」，所以取 first()。
  const defaultDs = ((await page.locator('.ant-tabs-tabpane-active .ant-select').first()
    .innerText()) ?? '').replace(/\s+/g, '').trim();
  console.log('[T-8c] 数据集下拉默认值 =', JSON.stringify(defaultDs));
  expect(defaultDs, 'T-8c 前置：读不到数据集下拉的选中值 ⇒ 后面的「切到详细核价」无从验证')
    .not.toBe('');

  // 切到「详细核价」（坑 3：虚拟滚动，先滚到可见）
  await page.locator('.ant-tabs-tabpane-active .ant-select').first().click();
  const opt = page.locator('.ant-select-item-option', { hasText: '详细核价' }).first();
  await opt.scrollIntoViewIfNeeded();
  await opt.click();
  await page.waitForTimeout(1500);
  await page.waitForFunction(() => !document.querySelector('.ant-spin-spinning'), { timeout: 20_000 })
    .catch(() => {});

  // ① 出行
  const rows = page.locator('.ant-tabs-tabpane-active .ant-table-tbody tr.ant-table-row');
  const n = await rows.count();
  console.log(`[T-8c] UI 行数=${n}，库=${dbRows}`);
  expect(n, 'AC-8c ①：电镀方案（详细核价）渲染 0 行 ⇒ 只读形态断言全部空跑').toBeGreaterThan(0);

  // ② 说明文案逐字
  const paneText = ((await page.locator('.ant-tabs-tabpane-active').innerText()) ?? '').replace(/\s+/g, '');
  expect(paneText, `AC-8c ②：页顶固定说明文案不符。期望包含：${PLATING_NOTICE}`)
    .toContain(PLATING_NOTICE.replace(/\s+/g, ''));

  // ③ 无「新增 / 编辑 / 删除 / 保存」按钮
  const btns = (await page.locator('.ant-tabs-tabpane-active button').allTextContents())
    .map((b) => b.replace(/\s+/g, '')).filter(Boolean);
  console.log('[T-8c] 工具栏按钮 =', JSON.stringify(btns));
  for (const forbidden of ['新增', '编辑', '删除', '保存']) {
    expect(btns.filter((b) => b.includes(forbidden)),
      `AC-8c ③：电镀方案是只读页签，不该出现含「${forbidden}」的按钮，实际 ${JSON.stringify(btns)}`)
      .toEqual([]);
  }

  // ④ 点单元格不进编辑态
  const beforeInputs = await page.locator('.ant-tabs-tabpane-active .ant-table input').count();
  await rows.first().locator('td').nth(1).click();
  await page.waitForTimeout(800);
  const afterInputs = await page.locator('.ant-tabs-tabpane-active .ant-table input').count();
  console.log(`[T-8c] 点击前后表内 input 数 = ${beforeInputs} -> ${afterInputs}`);
  expect(afterInputs, 'AC-8c ④：点击单元格后出现了输入控件 ⇒ 进入了编辑态').toBe(0);

  await shot(page, 'T8c-AC8c-电镀方案只读');
});

// ═══════════════════════════════════════════════════════════════════
// T-16b / AC-16 守卫 · MASTER 下拉仍能取到候选
// ═══════════════════════════════════════════════════════════════════

/**
 * 🚨 **本条是本任务唯一一处「公共件函数体确实被改了」的守卫，不是锦上添花。**
 *
 * T-16 审阅 `git diff -M master` 时发现：`shared/EditableSheetTable.tsx` 的
 * `MasterSelectCell` 里，`lookupFn` 从「必填带默认值（默认走被删的 legacy `lookup`）」
 * 改成「可选，未传时返回空候选」：
 *     - const { ... lookupFn = lookup } = props;      // 改动前
 *     + const { ... lookupFn } = props;               // 改动后
 *     - const r = await lookupFn(master, kw);
 *     + const r = (await lookupFn?.(master, kw)) ?? { items: [] };
 *
 * 这是 AC-16 定义的「第四类改动」（props 定义 + 函数体），已上报主线；
 * 它**不可避免** —— 那个默认值指向的正是 AC-6 要求删掉的 legacy 导出。
 *
 * ⇒ 风险落到「幸存的消费方有没有恒传 lookupFn」上。没传的话**不会报错**，
 *   只会静默变成空下拉 —— 典型的静默失效。本条就是它的可观测判据：
 *   下拉必须能返回**非空**候选。
 *
 * 🚫 断言「候选非空」而不是「下拉能打开」：能打开但恒空，正是回归后的样子。
 */
test('T-16b / AC-16 守卫：基础核价抽屉里的 MASTER 下拉仍能返回非空候选（lookupFn 默认值被删后的静默失效守卫）', async ({ page }) => {
  test.setTimeout(240_000);

  const rows = currentVersionRows(BASIC_SHEETS[0].table, FIXTURE_PART);
  expect(rows, 'T-16b 前置：物料BOM 0 行 ⇒ 表里没有下拉可点，断言空跑').toBeGreaterThan(0);

  await openHub(page);
  await clickHubTab(page, '基础核价');
  await searchPart(page, FIXTURE_PART);
  const drawer = await openPartDrawer(page, FIXTURE_PART);
  const tabHit = await clickSheetTab(drawer, BASIC_SHEETS[0]);

  const selects = drawer.locator('.ant-tabs-tabpane-active .ant-table .ant-select');
  const nSelect = await selects.count();
  console.log(`[T-16b] 「${tabHit}」表内 antd Select 单元格数 = ${nSelect}`);
  expect(nSelect,
    'T-16b 前置：该 sheet 里一个 MASTER 下拉都没有 ⇒ 本守卫无从执行。\n' +
    '  换一个含 MASTER 列的 sheet，或报主线换夹具 —— 🚫 不要把本条当「通过」。')
    .toBeGreaterThan(0);

  // 点开第一个下拉并触发远程搜索
  await selects.first().click();
  await page.waitForTimeout(600);
  const searchInput = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) input, ' +
    '.ant-tabs-tabpane-active .ant-select-focused input').first();
  await searchInput.fill('').catch(() => {});
  await page.keyboard.type('0');       // 单字符关键词，尽量命中
  await page.waitForTimeout(2500);

  const opts = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option');
  const nOpt = await opts.count();
  const sample = (await opts.allTextContents()).slice(0, 5).map((t) => t.replace(/\s+/g, ' ').trim());
  console.log(`[T-16b] 下拉候选数 = ${nOpt}，样例 = ${JSON.stringify(sample)}`);
  await shot(page, 'T16b-AC16-master下拉候选');

  expect(nOpt,
    '🚨 AC-16 守卫失败：MASTER 下拉候选为 0。\n' +
    '  最可能的根因：`EditableSheetTable` 的 `lookupFn` 默认值随 legacy `lookup` 一起被删后，\n' +
    '  某个消费方没有恒传 `lookupFn` ⇒ 走进 `?? { items: [] }` 兜底 ⇒ **静默空下拉，不报错**。\n' +
    '  ⚠️ 也可能是关键词「0」恰好无匹配 —— 先换关键词复核再定性（testing.md §4.1.5）。')
    .toBeGreaterThan(0);

  await page.keyboard.press('Escape');
  await closeDrawer(page);
});

// ═══════════════════════════════════════════════════════════════════
// T-9 / AC-9 · 页签切换与刷新
// ═══════════════════════════════════════════════════════════════════

test('T-9 / AC-9：材质→详细核价→工序→材质 每次都渲染出内容；F5 后回到「材质」', async ({ page }) => {
  test.setTimeout(180_000);
  await openHub(page);

  // 起点：默认落在材质
  expect((await hubTabNav(page).locator('[role="tab"][aria-selected="true"]').textContent())?.trim(),
    'AC-9 起点：应默认落在「材质」').toBe(DEFAULT_TAB);

  for (const name of ['详细核价', '工序', '材质']) {
    await clickHubTab(page, name);
    const sel = (await hubTabNav(page).locator('[role="tab"][aria-selected="true"]').textContent())?.trim();
    expect(sel, `AC-9：点了「${name}」但选中态是「${sel}」`).toBe(name);

    const paneText = ((await page.locator('.ant-tabs-tabpane-active').innerText()) ?? '').trim();
    console.log(`[T-9] 「${name}」内容区前 120 字 = ${JSON.stringify(paneText.slice(0, 120))}`);
    expect(paneText.length,
      `AC-9：切到「${name}」后内容区是空的 ⇒「每次切换目标页签都渲染出内容」没被满足`)
      .toBeGreaterThan(0);
    expect(paneText, `AC-9：「${name}」卡在「加载中…」`).not.toContain('加载中…');
    await shot(page, `T9-AC9-切到-${name}`);
  }

  // F5 刷新
  await page.reload({ waitUntil: 'networkidle' });
  await page.waitForSelector('.ant-tabs > .ant-tabs-nav [role="tab"]', { timeout: 30_000 });
  await page.waitForTimeout(1200);
  const afterReload = (await hubTabNav(page).locator('[role="tab"][aria-selected="true"]').textContent())?.trim();
  console.log('[T-9] 刷新后选中页签 =', JSON.stringify(afterReload));
  expect(afterReload, 'AC-9：刷新后应回到「材质」（activeTab 不持久化，保持现状）').toBe(DEFAULT_TAB);

  await shot(page, 'T9-AC9-刷新后回材质');
});

// ═══════════════════════════════════════════════════════════════════
// T-11 / AC-11 · 角色边界（迁自 product-hub-readonly.spec.ts:381）
// ═══════════════════════════════════════════════════════════════════

test('T-11 / AC-11：PRICING_MANAGER 有「主数据维护」菜单；页签集合同 AC-1；基础核价抽屉保存按钮可见', async ({ page }) => {
  test.setTimeout(240_000);

  const user = process.env.PW_USER_PRICING || 't260903_pm';
  const pwd = process.env.PW_PWD_PRICING || 'Admin@2026';
  // 🚫 拿不到账号不得改 skip —— skip 掉的角色断言会以「全部通过」的样子混过去
  await loginAs(page, user, pwd);
  console.log(`[T-11] 以 PRICING_MANAGER = ${user} 登录`);

  // ① 左侧菜单存在「主数据维护」
  await page.goto('/dashboard');
  await page.waitForLoadState('networkidle');
  const menuHit = await page.locator(
    '.ant-menu a[href*="master-data-hub"], .ant-menu-item:has-text("主数据维护"), .ant-menu-title-content:has-text("主数据维护")'
  ).count();
  console.log('[T-11] 左侧菜单「主数据维护」命中数 =', menuHit);
  expect(menuHit, 'AC-11 ①：PRICING_MANAGER 的左侧菜单里应存在「主数据维护」').toBeGreaterThan(0);

  // ② 页签集合同 AC-1
  await openHub(page);
  const tabs = await hubTabNames(page);
  console.log('[T-11] PRICING_MANAGER 看到的页签 =', JSON.stringify(tabs));
  expect(tabs, 'AC-11 ②：PRICING_MANAGER 的页签集合应与 AC-1 相同').toEqual(HUB_TABS_6);

  // ③ 基础核价抽屉内保存按钮可见
  await clickHubTab(page, '基础核价');
  const rows = page.locator('.ant-tabs-tabpane-active .ant-table-tbody tr.ant-table-row');
  const listRows = await rows.count();
  // 🚨 反向对照：列表 0 行 ⇒ 打不开抽屉 ⇒ 保存按钮断言空跑
  expect(listRows, 'AC-11 ③ 前置：基础核价列表 0 行 ⇒ 抽屉断言空跑，停下报主线').toBeGreaterThan(0);

  await rows.first().locator('td').nth(0).click();
  const drawer = page.locator('.ant-drawer').first();
  await expect(drawer, 'AC-11 ③：点第一行没有打开抽屉').toBeVisible({ timeout: 20_000 });
  await page.waitForTimeout(2500);

  // 🚨 必须先切到**确实有数据**的 sheet 再断言保存按钮
  //    （product-hub-readonly.spec.ts:365 记着这个坑：默认 sheet 无数据时本来就没有可保存的东西，
  //      不切 tab 就断言，会把「默认 tab 无数据」误报成「被改成只读了」）
  const tabNames = (await drawer.locator('[role="tab"]').allTextContents()).map((t) => t.trim());
  console.log('[T-11] 抽屉 sheet tab =', JSON.stringify(tabNames));
  expect(tabNames.length, 'AC-11 ③ 前置：抽屉一个 sheet tab 都没有').toBeGreaterThan(0);
  await clickSheetTab(drawer, BASIC_SHEETS[0]);

  const drawerRowCount = await drawerRows(drawer).count();
  const inputs = await drawer.locator('.ant-tabs-tabpane-active .ant-table input').count();
  const saveBtns = await drawer.getByRole('button', { name: /保\s*存/ }).count();
  console.log(`[T-11] 抽屉「物料BOM」行数=${drawerRowCount} input=${inputs} 保存按钮=${saveBtns}`);

  expect(drawerRowCount, 'AC-11 ③ 前置：该 sheet 0 行 ⇒ 保存按钮断言空跑').toBeGreaterThan(0);
  expect(saveBtns,
    'AC-11 ③：PRICING_MANAGER 在「基础核价」抽屉里必须能看到保存按钮 —— ' +
    '本任务不得把可编辑的核价维护改成只读').toBeGreaterThan(0);

  await shot(page, 'T11-AC11-PRICING_MANAGER-基础核价可保存');
});

/**
 * ⚠️ 跑完若出现「登录不上」：E2E 反复跑会把 admin 置成 INACTIVE。
 * 还原（只改这一个账号，**不是清库**）：
 *   PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 \
 *     -c "UPDATE \"user\" SET status='ACTIVE', locked_until=NULL, failed_login_attempts=0 WHERE username='admin';"
 */
