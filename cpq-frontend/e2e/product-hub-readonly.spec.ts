/**
 * task-260903「产品管理页重做」E2E —— `E2E-01` ~ `E2E-16` + `ST-01`。
 *
 * 🚫 **本文件从 `需求文档.md` ③ 的 AC-1~AC-17 原文派生，没有读过任何实现代码**
 *    （`cpq-frontend/src/pages/product/` 与 `part-costing/` 全程未打开）。
 *    从实现派生的测试只能证明「代码按实现者的理解工作」，证明不了「功能符合需求」。
 *
 * 追溯矩阵见 `dev-docs/task-260903-产品管理页重做/test.md §3`。
 *
 * ⚠️ **共享库纪律**（主线 2026-09-03 通知）：`cpq_db_0724` 与 `task-260902` 的
 *    `@QuarkusTest` 共用，行数会漂移。⇒ 列表总数一律走
 *    `expectTotalMatchesDb()`（页面总数 == 同一时刻库中 count(*)）这个**不变量**，
 *    🚫 不写死绝对行数。主角料号相关的计数按 `material_no` 过滤，天然不受漂移影响。
 *
 * 🚫 本套用例**一行都不写库**：afterAll 用 `assertNoWrite()` 对 16 张 `ds_quote_*`
 *    逐表比对「行数 + 内容 md5」，变了即硬失败。这也是 AC-8/AC-9「全只读」的数据层证据。
 *
 * ⚠️ **一个会伪装成产品 bug 的环境坑**（主线通知）：多个 vite 共用软链的
 *    `node_modules/.vite` 会互相踢掉依赖预构建缓存 ⇒ 表现为加载超时 / 白屏。
 *    遇到诡异 timeout **先怀疑它，再怀疑实现**。
 */
import { test, expect } from '@playwright/test';
import {
  assertIsolatedEnv, assertSameDatabase,
  assertSampleDataLoaded, assertEmptyWindow, snapshotDataState, assertNoWrite,
  expectTotalMatchesDb, heroRowsInDb, sql, sqlOne, tableExists,
  shot, evidence, loginAs, gotoProductHub, switchTab, headerTexts, totalCount,
  rowCount, search, clearSearch, openDrawer, closeDrawer, drawerTabTexts,
  cellByHeader, firstRowCellByHeader,
  clickDrawerTab, assertReadOnly, assertReadOnlyProbeWorks, collectConsoleErrors,
  HERO, N_MATERIAL, N_CUSTOMER_PART, N_HERO_MATERIAL_BOM, N_HERO_ELEMENT_BOM,
  CUSTOMER_PART_COLUMNS, MATERIAL_COLUMNS, DRAWER_TABS, FORBIDDEN_TABS, EMPTY_TAB,
  type DataState,
} from './product-hub.helpers';
import * as fs from 'fs';
import * as path from 'path';

test.describe.configure({ mode: 'serial' });

let before: DataState;

/** AC-13 的前提与其余用例相反（要求 0 行），由 PW_EMPTY_WINDOW=1 切换前置守卫。 */
const EMPTY_WINDOW = process.env.PW_EMPTY_WINDOW === '1';

test.beforeAll(async () => {
  assertIsolatedEnv();

  // 🚨 2026-09-04 新增：证明「页面后端连的库」与「本文件断言查的库」是同一个库。
  //
  //    本套最强的一条证据是 afterAll 的「16 张表指纹全未变」（AC-8/AC-9 的只读证明）。
  //    但它读的是 `PW_DB`，而页面读的是 `PW_BACKEND_URL` 那个后端所连的库 ——
  //    两者若不是同一个库（实测踩到过：临时后端连的是**克隆库**），
  //    这条证明会给出一个**假的干净结论**：页面明明在另一个库上写了，这边却报「全未变 ✅」。
  //    ⇒ **留着这个洞的回归，不如没有回归。**
  //
  //    ⚠️ 次序是硬要求：必须在 `snapshotDataState()` **之前**跑完。
  //       守卫内部会写一个哨兵并自复位；顺序错了，`assertNoWrite` 会把哨兵算成
  //       「页面写的」⇒ 变成一条**假红**，而假红比没有守卫更糟 —— 它让人怀疑一个本来正确的结论。
  //
  //    📌 被测对象仍然是只读的：写的是**测试自己的守卫**，不是产品行为。
  //       「页面不写库」这个结论不受影响。
  if (EMPTY_WINDOW) {
    // 空态窗口下 ds_quote_material 就是 0 行，没有可用作哨兵的那一行 ⇒ 本守卫**不适用**。
    // 🚫 这不是「静默跳过」：大声打出来，并记为该模式下的**已知覆盖缺口**。
    console.warn('⚠️ [DB同一性] 空态窗口模式（PW_EMPTY_WINDOW=1）下跳过 —— ' +
      '表为 0 行，没有哨兵行可用。⇒ 该模式下「同库」这一前提未经校验，结论请人工确认后端连的库。');
  } else {
    await assertSameDatabase();
  }

  if (EMPTY_WINDOW) assertEmptyWindow(); else assertSampleDataLoaded();
  before = snapshotDataState();
  console.log('[基线快照] 已记录 16 张 ds_quote_* 的行数与内容指纹');
});

test.afterAll(() => {
  // 🚨 只读证明。放 afterAll 而不是每个用例后 —— 整套跑完一次都没写，才是「页面只读」。
  // ⚠️ beforeAll 抛错时 `before` 是 undefined，此处若直接用会抛 TypeError，
  //    **把 beforeAll 的真实失败原因盖掉**（2026-09-03 证伪实验实测踩到）。
  //    ⇒ 没有基线就直说没有，不要制造第二个假故障。
  if (!before) {
    console.warn('[只读证明] 跳过：beforeAll 未取到基线快照（真实失败原因见上一条错误）');
    return;
  }
  assertNoWrite(before, snapshotDataState());
});

// ══════════════════════════ E2E-01 → AC-1 页面结构 ══════════════════════════

test('E2E-01 / AC-1：页签恰好 2 个、文案与顺序、默认选中客户产品、旧页签文案消失', async ({ page }) => {
  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);

  await expect(page.getByRole('heading', { name: '产品管理' }).first(),
    'AC-1：页面标题为「产品管理」').toBeVisible();

  const tabs = page.getByRole('tab');
  const texts: string[] = [];
  for (let i = 0; i < await tabs.count(); i++) {
    texts.push((await tabs.nth(i).innerText()).replace(/\s+/g, ''));
  }
  console.log('[AC-1] 实际页签 =', JSON.stringify(texts));
  evidence('AC01-tabs', JSON.stringify(texts, null, 2));

  expect(texts, 'AC-1：页签恰好 2 个，从左到右为「客户产品」「销售产品」')
    .toEqual(['客户产品', '销售产品']);

  await expect(page.getByRole('tab', { name: '客户产品', exact: true }),
    'AC-1：默认选中「客户产品」').toHaveAttribute('aria-selected', 'true');

  // 反向断言：旧页签文案必须从页面上消失
  for (const gone of ['产品主数据', '客户对应主数据']) {
    await expect(page.getByText(gone, { exact: true }),
      `AC-1：页面上不得再出现「${gone}」页签`).toHaveCount(0);
  }

  // 「产品分类管理」按钮保留且可点开抽屉（②范围：保留不动）
  const catBtn = page.getByRole('button', { name: /产品分类管理/ });
  await expect(catBtn, 'AC-1：「产品分类管理」按钮存在').toBeVisible();
  await catBtn.click();
  await expect(page.locator('.ant-drawer').first(),
    'AC-1：「产品分类管理」应能点开抽屉').toBeVisible({ timeout: 10_000 });
  await shot(page, 'AC01-category-drawer');
  await closeDrawer(page);
});

// ══════════════════════════ E2E-02 → AC-2 客户产品列与行数 ══════════════════════════

test('E2E-02 / AC-2：客户产品 6 列顺序 + 总数 == 库中 count(*) + 客户名称 JOIN 不到渲染「—」', async ({ page }) => {
  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);
  await switchTab(page, '客户产品');

  const cols = await headerTexts(page);
  console.log('[AC-2] 实际列 =', JSON.stringify(cols));
  evidence('AC02-columns', JSON.stringify(cols, null, 2));
  expect(cols, 'AC-2：列依次为 客户编号/客户名称/客户料号名称/客户产品编号/客户图号/销售料号')
    .toEqual(CUSTOMER_PART_COLUMNS);

  const { db } = await expectTotalMatchesDb(
    page, 'ds_quote_customer_part', N_CUSTOMER_PART, 'AC-2 客户产品总数');

  // 🚨 前置非空守卫：0 行时下面的「—」断言会空跑
  expect(await rowCount(page), 'AC-2：首屏应渲染出行（0 行 ⇒ 后续断言空跑，属假绿）')
    .toBeGreaterThan(0);

  // 客户名称：JOIN 不到时必须是「—」，不得空白、不得 undefined
  const unmatched = sql(`
    SELECT t.customer_no FROM ds_quote_customer_part t
    LEFT JOIN customer c ON c.code = t.customer_no
    WHERE c.code IS NULL GROUP BY 1`);
  console.log('[AC-2] 库中 JOIN 不到 customer.code 的客户编号 =', JSON.stringify(unmatched));

  const bodyText = await page.locator('.ant-table-tbody').innerText();
  expect(bodyText, 'AC-2：表格里不得出现 undefined').not.toContain('undefined');

  if (unmatched.length) {
    // 取一个 JOIN 不到的客户编号，定位它那一行，断言「客户名称」单元格是「—」
    const cno = unmatched[0];
    await search(page, cno);
    const row = page.locator('.ant-table-tbody tr.ant-table-row').first();
    await expect(row, `AC-2：应能搜到客户编号 ${cno} 的行`).toBeVisible({ timeout: 10_000 });
    // 🚨 按列头文案取列，不写死 `td.nth(1)`（见 helpers 的 `columnIndexOf` 注释：
    //    列序会随需求变更右移，硬下标会静默验错列）。
    const nameCell = (await (await cellByHeader(page, row, '客户名称')).innerText()).trim();
    console.log(`[AC-2] 客户编号 ${cno} 的「客户名称」单元格 = ${JSON.stringify(nameCell)}`);
    evidence('AC02-dash-cell', `customer_no=${cno}  客户名称单元格=${JSON.stringify(nameCell)}`);
    expect(nameCell, `AC-2：${cno} 在 customer 表查无此人 ⇒ 客户名称须渲染「—」，` +
      `不得空白、不得 undefined`).toBe('—');
    await clearSearch(page);
  } else {
    // 🚨 不 skip、不静默通过 —— 断言从未执行就是假绿（testing.md §3）
    expect(false,
      `AC-2 的「JOIN 不到 → —」分支**无法取证**：库中 ${db} 行客户料号全部能 JOIN 到 customer.code。\n` +
      `这与 样例-数据说明.md §3.4 的处置直接相关（C1 / Q13CUST0617 若已建档，此分支永远不会被执行）。\n` +
      `⇒ 需主线裁决：要么保留未登记客户以留出取证样本，要么把该断言从 AC-2 拆走。`).toBeTruthy();
  }
  await shot(page, 'AC02-customer-part-list', { fullPage: true });
});

// ══════════════════════════ E2E-03 → AC-3 客户产品无抽屉 ══════════════════════════

test('E2E-03 / AC-3：客户产品点任意单元格不弹抽屉、不进编辑态', async ({ page }) => {
  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);
  await switchTab(page, '客户产品');

  const n = await rowCount(page);
  expect(n, 'AC-3：需有行可点（0 行 ⇒ 断言空跑）').toBeGreaterThan(0);

  // 逐列点一遍第一行 —— 只点第一格挡不住「某一列绑了 onClick」
  const cells = page.locator('.ant-table-tbody tr.ant-table-row').first().locator('td');
  const nc = await cells.count();
  for (let i = 0; i < nc; i++) {
    await cells.nth(i).click();
    await page.waitForTimeout(250);
    await expect(page.locator('.ant-drawer'),
      `AC-3：点第 ${i + 1} 列后不得出现 .ant-drawer`).toHaveCount(0);
  }
  await expect(page.locator('.ant-table-tbody input, .ant-table-tbody select, .ant-table-tbody textarea'),
    'AC-3：行不得进入编辑态').toHaveCount(0);
  await shot(page, 'AC03-no-drawer');
});

// ══════════════════════════ E2E-04 → AC-4 销售产品列与行数 ══════════════════════════

test('E2E-04 / AC-4：销售产品 7 列顺序 + 总数 == 库中 count(*)', async ({ page }) => {
  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);
  await switchTab(page, '销售产品');

  const cols = await headerTexts(page);
  console.log('[AC-4] 实际列 =', JSON.stringify(cols));
  evidence('AC04-columns', JSON.stringify(cols, null, 2));
  expect(cols, 'AC-4：列依次为 销售料号/品名/规格/尺寸/旧料号/单重/生产料号')
    .toEqual(MATERIAL_COLUMNS);

  await expectTotalMatchesDb(page, 'ds_quote_material', N_MATERIAL, 'AC-4 销售产品总数');
  expect(await rowCount(page), 'AC-4：首屏应渲染出行').toBeGreaterThan(0);
  await shot(page, 'AC04-material-list', { fullPage: true });
});

// ══════════════════════════ E2E-05 → AC-5 点行开抽屉 ══════════════════════════

test('E2E-05 / AC-5：点主角料号开抽屉，标题含轴值，左侧竖排 tab 可见', async ({ page }) => {
  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);
  await switchTab(page, '销售产品');

  const drawer = await openDrawer(page, HERO);
  await expect(drawer.getByText(HERO, { exact: false }).first(),
    `AC-5：Drawer 标题应包含「${HERO}」`).toBeVisible();
  expect(await drawer.locator('[role="tab"]').count(),
    'AC-5：左侧竖排 tab 列表应可见').toBeGreaterThan(0);
  await shot(page, 'AC05-drawer-open', { fullPage: true });
  await closeDrawer(page);
});

// ══════════════════════════ E2E-06 → AC-6 抽屉 13 个 tab ══════════════════════════

test('E2E-06 / AC-6：抽屉恰好 13 个 tab、文案与顺序、不含 3 个免版本 sheet', async ({ page }) => {
  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);
  await switchTab(page, '销售产品');
  const drawer = await openDrawer(page, HERO);

  const tabs = await drawerTabTexts(drawer);
  console.log('[AC-6] 实际 tab =', JSON.stringify(tabs));
  evidence('AC06-drawer-tabs', JSON.stringify(tabs, null, 2));

  expect(tabs, 'AC-6：tab 恰好 13 个，文案与顺序须与 AC 原文逐字一致').toEqual(DRAWER_TABS);
  for (const bad of FORBIDDEN_TABS) {
    expect(tabs, `AC-6：免版本 sheet「${bad}」不得出现在抽屉 tab 里`).not.toContain(bad);
  }
  await shot(page, 'AC06-13-tabs', { fullPage: true });
  await closeDrawer(page);
});

// ══════════════════════════ E2E-07 → AC-7 抽屉表格数据 ══════════════════════════

test('E2E-07 / AC-7：物料BOM 行数 == 库中该料号行数、含指定列、不渲染轴列', async ({ page }) => {
  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);
  await switchTab(page, '销售产品');
  const drawer = await openDrawer(page, HERO);
  await clickDrawerTab(page, drawer, '物料BOM');

  // 🚩 按 material_no 过滤计数 ⇒ 不受共享库漂移影响
  const dbRows = heroRowsInDb('ds_quote_material_bom');
  const uiRows = await rowCount(drawer);
  console.log(`[AC-7] 物料BOM 页面行数=${uiRows}  库中 ${HERO} 行数=${dbRows}  AC 基线=${N_HERO_MATERIAL_BOM}`);
  evidence('AC07-rowcount', `ui=${uiRows} db=${dbRows} ac=${N_HERO_MATERIAL_BOM}`);

  expect(uiRows, `AC-7：页面 ${uiRows} 行 ≠ 库中 ${dbRows} 行 —— 页面侧缺陷（漏行/多行/轴过滤错）`)
    .toBe(dbRows);
  expect(dbRows, `AC-7：AC 原文要求 ${HERO} 的物料BOM 为 ${N_HERO_MATERIAL_BOM} 行，库中实为 ${dbRows} 行。\n` +
    `若为 ${N_HERO_MATERIAL_BOM - 1}，极可能是 样例-数据说明.md §3.2 的「幽灵行」被导入必填校验拒收 ⇒ 需主线裁决`)
    .toBe(N_HERO_MATERIAL_BOM);

  const cols = await headerTexts(drawer);
  console.log('[AC-7] 物料BOM 列 =', JSON.stringify(cols));
  evidence('AC07-columns', JSON.stringify(cols, null, 2));
  for (const must of ['投入料号', '组成数量', '材料净重']) {
    expect(cols, `AC-7：应存在列「${must}」`).toContain(must);
  }
  expect(cols, 'AC-7：role=AXIS 的轴列「销售料号」在抽屉内必须隐藏（轴值已在标题上）')
    .not.toContain('销售料号');

  await shot(page, 'AC07-material-bom', { fullPage: true });
  await closeDrawer(page);
});

// ══════════════════════════ E2E-08 → AC-8 全只读（控件层面） ══════════════════════════

test('E2E-08 / AC-8：抽屉内无保存/新增行/删除按钮、无编辑控件、双击不进编辑态、文本可选中', async ({ page }) => {
  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);
  await switchTab(page, '销售产品');
  const drawer = await openDrawer(page, HERO);
  await clickDrawerTab(page, drawer, '物料BOM');

  // 🚨 阳性对照先行：证明「不存在」类断言的观察手段是活的（testing.md §4.4）
  await assertReadOnlyProbeWorks(drawer, HERO);

  await assertReadOnly(drawer, 'SYSTEM_ADMIN');

  // 双击单元格不进编辑态
  const cell = drawer.locator('.ant-table-tbody tr.ant-table-row td').first();
  await expect(cell, 'AC-8：需有单元格可双击（0 个 ⇒ 断言空跑）').toBeVisible();
  await cell.dblclick();
  await page.waitForTimeout(500);
  await expect(drawer.locator('.ant-table input, .ant-table select, .ant-table textarea'),
    'AC-8：双击单元格后仍不得出现编辑控件').toHaveCount(0);

  // 文本可被选中复制（只读 ≠ 不可选）
  const selected = await cell.evaluate((el) => {
    const r = document.createRange(); r.selectNodeContents(el);
    const s = window.getSelection(); s?.removeAllRanges(); s?.addRange(r);
    return (window.getSelection()?.toString() || '').trim();
  });
  console.log('[AC-8] 选中的单元格文本 =', JSON.stringify(selected));
  expect(selected.length, 'AC-8：单元格文本必须可被选中复制（user-select 不得被禁掉）')
    .toBeGreaterThan(0);

  await shot(page, 'AC08-readonly-admin', { fullPage: true });
  await closeDrawer(page);
});

// ══════════════════════════ E2E-09 → AC-9 全只读（角色层面） ══════════════════════════

test('E2E-09 / AC-9：PRICING_MANAGER 与 SYSTEM_ADMIN 结果一致；反向 —— 核价侧仍可编辑', async ({ page }) => {
  // 🚨 credOf 拿不到账号会硬失败并说明「这是测试环境缺陷」。
  //    🚫 不得改成 skip —— skip 掉的角色断言会以「全部通过」的样子混过去。
  await loginAs(page, 'PRICING_MANAGER');
  await gotoProductHub(page);
  await switchTab(page, '销售产品');
  const drawer = await openDrawer(page, HERO);
  await clickDrawerTab(page, drawer, '物料BOM');

  await assertReadOnlyProbeWorks(drawer, HERO);
  await assertReadOnly(drawer, 'PRICING_MANAGER');
  expect(await rowCount(drawer), 'AC-9：PRICING_MANAGER 看到的行数应与 SYSTEM_ADMIN 一致')
    .toBe(heroRowsInDb('ds_quote_material_bom'));
  await shot(page, 'AC09-readonly-pricingmgr', { fullPage: true });
  await closeDrawer(page);

  // ── 反向断言：本任务不得把核价侧改成只读 ──
  await page.goto('/master-data-hub');
  await page.getByText('料号核价', { exact: true }).first().click();
  await page.waitForTimeout(1500);
  await search(page, HERO);
  const costCell = page.getByRole('cell', { name: HERO, exact: true }).first();
  await expect(costCell, '反向断言前置：核价侧应能搜到主角料号').toBeVisible({ timeout: 10_000 });
  await costCell.click();
  const costDrawer = page.locator('.ant-drawer').first();
  await expect(costDrawer, '反向断言前置：核价侧抽屉应打开').toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(3000);

  // 🚨 必须先切到**该料号确实有数据**的 tab 再断言。
  //    核价抽屉默认停在第一个 tab「生产耗材BOM」，而 S-3120014539 在该 tab 上是「未维护」
  //    （版本下拉 0 个选项）⇒ 本来就没有可编辑的东西，自然没有保存按钮。
  //    2026-09-03 实测：不切 tab 时保存按钮 = 0，切到「物料BOM」后 = 1（96 个 input、8 行、版本 2000（当前））。
  //    ⚠️ 这是**导航选错**，不是产品缺陷 —— A/B 已证主仓 master 与本分支行为逐项一致。
  //    不切 tab 就断言，会把「默认 tab 无数据」误报成「核价侧被改成只读」。
  const costTab = costDrawer.locator('[role="tab"]').filter({ hasText: '物料BOM' }).first();
  await expect(costTab, '反向断言前置：核价抽屉应有「物料BOM」tab').toHaveCount(1);
  await costTab.click();
  await page.waitForTimeout(3500);
  const costRows = await costDrawer.locator('.ant-table-tbody tr.ant-table-row').count();
  const costInputs = await costDrawer.locator('.ant-table input').count();
  console.log(`[AC-9 反向] 核价侧「物料BOM」行数=${costRows} 可编辑 input=${costInputs}`);
  expect(costRows, '反向断言前置：该 tab 须有行（0 行 ⇒ 断言空跑）').toBeGreaterThan(0);

  const saveBtns = await costDrawer.getByRole('button', { name: /保\s*存/ }).count();
  console.log('[AC-9 反向] 核价侧抽屉「保存」按钮数 =', saveBtns);
  evidence('AC09-costing-still-editable',
    `核价侧 PRICING_MANAGER：物料BOM tab 行数=${costRows} input=${costInputs} 保存按钮数=${saveBtns}`);
  await shot(page, 'AC09-costing-editable', { fullPage: true });
  expect(costInputs, '🚨 AC-9 反向断言：核价侧表格必须仍有可编辑控件').toBeGreaterThan(0);
  expect(saveBtns, '🚨 AC-9 反向断言：本任务不得把核价侧改成只读 —— ' +
    'PRICING_MANAGER 在「料号核价」抽屉里必须仍能看到保存按钮').toBeGreaterThan(0);
});

// ══════════════════════════ E2E-10 → AC-10 版本切换 ══════════════════════════

test('E2E-10 / AC-10：版本下拉存在；有 ≥2 版本则切换后内容变化，且任何版本都无编辑控件', async ({ page }) => {
  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);
  await switchTab(page, '销售产品');
  const drawer = await openDrawer(page, HERO);
  await clickDrawerTab(page, drawer, '物料BOM');

  // 库中该 (表, 轴值) 有几个版本 —— 决定走主断言还是 AC 原文的降级断言
  const versions = tableExists('ds_quote_material_bom_history')
    ? Number(sqlOne(
        `SELECT count(DISTINCT v) FROM (
           SELECT version_no v FROM ds_quote_material_bom WHERE material_no='${HERO}'
           UNION SELECT version_no FROM ds_quote_material_bom_history WHERE material_no='${HERO}') x`))
    : Number(sqlOne(`SELECT count(DISTINCT version_no) FROM ds_quote_material_bom WHERE material_no='${HERO}'`));
  console.log(`[AC-10] 库中 ${HERO} 的物料BOM 版本数 = ${versions}`);

  const select = drawer.locator('.ant-select').first();
  await expect(select, 'AC-10：版本下拉应存在').toBeVisible({ timeout: 10_000 });

  // ✅ 2026-09-03 主线裁决：取消「单版本降级断言」条款，改为由样例准备步骤③**造第二个版本**。
  //    ⇒ 这里不再有降级分支；版本数 <2 即为前置未就绪，硬失败。
  expect(versions,
    `AC-10：需要 ≥2 个版本才能验版本切换（样例准备步骤③应为 ${HERO} 的物料BOM 造 v2：` +
    `v2 为当前、v1 整组入 _history）。当前版本数 = ${versions} ⇒ 步骤③未执行或未生效。`)
    .toBeGreaterThanOrEqual(2);

  const beforeText = await drawer.locator('.ant-table-tbody').innerText();
  await select.click();
  // ⚠️ antd Select 走虚拟滚动：没渲染的选项在 DOM 里不存在。选项少时直接点，多时须先过滤。
  const opts = page.locator('.ant-select-dropdown:visible .ant-select-item-option');
  await expect(opts.first(), 'AC-10：版本下拉应有可选项').toBeVisible({ timeout: 8_000 });
  const n = await opts.count();
  expect(n, 'AC-10：版本下拉选项数应 ≥2').toBeGreaterThanOrEqual(2);
  await opts.nth(1).click();   // 切到非最新版本
  await page.waitForTimeout(1500);
  const afterText = await drawer.locator('.ant-table-tbody').innerText();
  expect(afterText, 'AC-10：切到另一个版本后表格内容应发生变化').not.toBe(beforeText);
  // 🚨 历史版本也只读 —— 且**当前版也只读**，这正是与核价侧的根本差异
  await assertReadOnly(drawer, 'AC-10 历史版本');

  await assertReadOnly(drawer, 'AC-10 当前版');
  await shot(page, 'AC10-version', { fullPage: true });
  await closeDrawer(page);
});

// ══════════════════════════ E2E-11 → AC-11 序列一致性 ══════════════════════════

test('E2E-11 / AC-11：八步序列，中间态与最终态都断言，全过程 console 无 error', async ({ page }) => {
  const errors = collectConsoleErrors(page);
  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);

  // ① 停在客户产品，记下总数
  await switchTab(page, '客户产品');
  const cp1 = await totalCount(page);
  console.log('[AC-11 ①] 客户产品总数 =', cp1);
  expect(cp1, 'AC-11 ①：应能读到客户产品总数').not.toBeNull();

  // ② 切到销售产品，记下总数
  await switchTab(page, '销售产品');
  const mp1 = await totalCount(page);
  console.log('[AC-11 ②] 销售产品总数 =', mp1);
  expect(mp1, 'AC-11 ②：应能读到销售产品总数').not.toBeNull();

  // ③ 点开主角料号抽屉
  let drawer = await openDrawer(page, HERO);

  // ④ 切到「物料与元素BOM」，记下行数
  await clickDrawerTab(page, drawer, '物料与元素BOM');
  const eDb = heroRowsInDb('ds_quote_element_bom');
  const e1 = await rowCount(drawer);
  console.log(`[AC-11 ④] 物料与元素BOM 页面=${e1} 库=${eDb} AC 基线=${N_HERO_ELEMENT_BOM}`);
  expect(e1, 'AC-11 ④：页面行数应等于库中该料号行数').toBe(eDb);
  expect(eDb, `AC-11 ④：AC 原文要求 ${N_HERO_ELEMENT_BOM} 行，库中为 ${eDb} 行 ` +
    `（若为 0，见 样例-数据说明.md §3.3：这两行的「组成含量（%）」在旧表为空，会被必填校验拒收）`)
    .toBe(N_HERO_ELEMENT_BOM);

  // ⑤ 关闭抽屉
  await closeDrawer(page);

  // ⑥ 切回客户产品 —— 总数不因抽屉开过而变化
  await switchTab(page, '客户产品');
  const cp2 = await totalCount(page);
  console.log('[AC-11 ⑥] 客户产品总数 =', cp2);
  expect(cp2, 'AC-11 ⑥：客户产品总数不得因抽屉开过而变化').toBe(cp1);

  // ⑦ 再切回销售产品，重新点开 —— 默认停在第一个 tab
  await switchTab(page, '销售产品');
  drawer = await openDrawer(page, HERO);
  const activeTab = (await drawer.locator('[role="tab"][aria-selected="true"]').innerText())
    .replace(/\s+/g, '').replace(/\d+$/, '');
  console.log('[AC-11 ⑦] 重新打开后选中的 tab =', activeTab);
  expect(activeTab, 'AC-11 ⑦：抽屉重新打开须默认停在第一个 tab「物料BOM」，不保留上次停留的 tab')
    .toBe('物料BOM');
  expect(await rowCount(drawer), 'AC-11 ⑦：物料BOM 行数应与库一致')
    .toBe(heroRowsInDb('ds_quote_material_bom'));
  await closeDrawer(page);

  // ⑧ 刷新后重复 ②③④
  await page.reload();
  await page.waitForLoadState('networkidle');
  await switchTab(page, '销售产品');
  const mp2 = await totalCount(page);
  console.log('[AC-11 ⑧] 刷新后销售产品总数 =', mp2);
  expect(mp2, 'AC-11 ⑧：刷新后销售产品总数应与刷新前一致').toBe(mp1);
  drawer = await openDrawer(page, HERO);
  await clickDrawerTab(page, drawer, '物料与元素BOM');
  expect(await rowCount(drawer), 'AC-11 ⑧：刷新后物料与元素BOM 行数应不变').toBe(e1);
  await shot(page, 'AC11-sequence-final', { fullPage: true });
  await closeDrawer(page);

  console.log('[AC-11] console error 条数 =', errors.length);
  evidence('AC11-console-errors', errors.join('\n') || '(无)');
  expect(errors, `AC-11：全过程 console 不得有 error 级日志，实际 ${errors.length} 条：\n` +
    errors.join('\n')).toEqual([]);
});

// ══════════════════════════ E2E-12 → AC-12 空 tab 空态 ══════════════════════════

test('E2E-12 / AC-12：空 tab 显示「暂无数据」，不显示「加载中…」，无表体行', async ({ page }) => {
  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);
  await switchTab(page, '销售产品');
  const drawer = await openDrawer(page, HERO);

  // 前置：确认该 tab 对应的表里，该料号确实 0 行（否则断言的是别的东西）
  const empty = Number(sqlOne(
    `SELECT count(*) FROM ds_quote_annual_discount WHERE material_no='${HERO}'`));
  console.log(`[AC-12] 库中 ${HERO} 在「${EMPTY_TAB}」对应表的行数 = ${empty}`);
  expect(empty, `AC-12 前置：「${EMPTY_TAB}」须确为 0 行才能验空态（不是 0 就换一个空 tab）`).toBe(0);

  await clickDrawerTab(page, drawer, EMPTY_TAB);
  await page.waitForTimeout(2500);   // 留足时间，"加载中"若是永久占位这里就抓得到

  const txt = await drawer.innerText();
  await shot(page, 'AC12-empty-tab', { fullPage: true });
  evidence('AC12-empty-tab-text', txt.slice(0, 2000));

  await expect(drawer.getByText('暂无数据').first(),
    'AC-12：空 tab 须显示 antd Empty 的「暂无数据」').toBeVisible({ timeout: 8_000 });
  expect(txt, '🚨 AC-12：不得停在「加载中…」—— 那是 AP-31「加载中永久占位族」的典型症状')
    .not.toContain('加载中');
  expect(await rowCount(drawer), 'AC-12：不得渲染表头以外的任何行').toBe(0);
  await closeDrawer(page);
});

// ══════════════════════════ E2E-13 → AC-13 空列表空态 ══════════════════════════

test('E2E-13 / AC-13：0 行时 Empty 空态 + 总数 0 + 工具栏仍渲染 + 点空白不报错', async ({ page }) => {
  const errors = collectConsoleErrors(page);
  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);
  await switchTab(page, '销售产品');

  const db = Number(sqlOne('SELECT count(*) FROM ds_quote_material'));
  // ✅ 2026-09-03 主线裁决：AC-13 改**时序解法** —— 在灌数据**之前**跑，此刻表 0 行即真实空态。
  //    零结果搜索的代理条件已取消。
  // 🚨 若此处 db ≠ 0，说明空态窗口已被消耗 —— **硬失败，不降级**。
  //    清表是 CLAUDE.md §3.2 红线，窗口一旦没了就无法重建，必须报主线裁决而不是换个条件糊过去。
  expect(db,
    `🚨 AC-13 的空态窗口已消耗：ds_quote_material 现有 ${db} 行。\n` +
    `AC-13 要求「0 行的干净库」，而清表是 §3.2 红线 ⇒ 窗口不可重建。\n` +
    `⇒ 停下报主线裁决，🚫 不得改用零结果搜索等代理条件把它变绿。`).toBe(0);
  console.log('[AC-13] 走原条件：库中 ds_quote_material 确为 0 行（真实空态）');

  const total = await totalCount(page);
  console.log('[AC-13] 空态下分页器总数 =', total);
  expect(total, 'AC-13：空态下分页器须显示总数 0').toBe(0);
  await expect(page.getByText('暂无数据').first(),
    'AC-13：须显示 Empty 空态，不许白屏、不许无限转圈').toBeVisible({ timeout: 10_000 });
  expect(await rowCount(page), 'AC-13：空态下不得有数据行').toBe(0);

  // 空态下工具栏仍渲染（否则用户连「刷新重试」都点不到）
  await expect(page.locator('input[placeholder*="搜索"], input[type="search"]').first(),
    'AC-13：空态下搜索框仍须渲染').toBeVisible();
  await expect(page.getByRole('button', { name: /刷\s*新/ }),
    'AC-13：空态下「刷新」按钮仍须渲染（两字按钮 antd 会插空格，故用 /刷\\s*新/）').toBeVisible();

  // 不得出现红色错误遮罩
  await expect(page.locator('.ant-result-error, .ant-alert-error'),
    'AC-13：不得出现错误遮罩').toHaveCount(0);

  // 点空白区域不报错
  await page.locator('.ant-table-placeholder, .ant-empty').first().click({ force: true });
  await page.waitForTimeout(500);
  await shot(page, 'AC13-empty-list', { fullPage: true });
  evidence('AC13-console-errors', errors.join('\n') || '(无)');
  expect(errors, `AC-13：空态下点击不得报错，实际 ${errors.length} 条`).toEqual([]);

});

// ══════════════════════════ E2E-14 → AC-14 搜索与分页 ══════════════════════════

test('E2E-14 / AC-14：销售产品搜主角料号得 1 行、清空恢复；客户产品搜 CUST-0004 得库中对应行数', async ({ page }) => {
  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);

  // ── 销售产品侧 ──
  await switchTab(page, '销售产品');
  const before = await totalCount(page);
  await search(page, HERO);
  const hit = await rowCount(page);
  console.log(`[AC-14] 搜「${HERO}」得 ${hit} 行`);
  expect(hit, `AC-14：搜索 ${HERO} 应得 1 行`).toBe(1);
  const cell0 = (await page.locator('.ant-table-tbody tr.ant-table-row').first().locator('td').first().innerText()).trim();
  expect(cell0, 'AC-14：命中行的销售料号应为主角料号').toBe(HERO);
  await clearSearch(page);
  expect(await totalCount(page), 'AC-14：清空搜索后总数应恢复').toBe(before);

  // ── 客户产品侧 ──
  await switchTab(page, '客户产品');
  const KW = 'CUST-0004';
  // 🚩 期望值取自**同一时刻的库**，不写死 —— 共享库会漂移，且 AC 原文的 12 与实测 11 有出入（见回报）
  const dbHit = Number(sqlOne(`SELECT count(*) FROM ds_quote_customer_part WHERE customer_no='${KW}'`));
  await search(page, KW);
  const uiHit = await rowCount(page);
  console.log(`[AC-14] 搜「${KW}」页面 ${uiHit} 行，库中 customer_no='${KW}' 为 ${dbHit} 行（AC 原文写 12）`);
  evidence('AC14-cust0004', `ui=${uiHit} db=${dbHit} ac原文=11`);
  expect(dbHit, `AC-14 前置：库中 ${KW} 应有行（0 行 ⇒ 断言空跑）`).toBeGreaterThan(0);
  expect(uiHit, `AC-14：页面搜索结果 ${uiHit} 行 ≠ 库中 ${dbHit} 行 —— 搜索条件与库口径不一致`)
    .toBe(dbHit);
  // ✅ 2026-09-03 主线裁决：AC 原文的「12 行」是笔误，已改为 11（实测 material_customer_map
  //    与 原型图/客户产品-默认态.html 双基准一致）。此处按修订后的 11 断言。
  expect(dbHit, `AC-14：${KW} 应占 11 行（修订后 AC）`).toBe(11);
  await shot(page, 'AC14-search', { fullPage: true });
  await clearSearch(page);
});

// ══════════════════════════ E2E-15 → AC-15 超长文案不破版 ══════════════════════════

test('E2E-15 / AC-15：超长品名省略号截断、页面不出现横向滚动条撑破容器', async ({ page }) => {
  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);
  await switchTab(page, '销售产品');

  // 结构性断言（与数据无关，永远可执行）：页面 body 不得横向溢出
  const overflow = await page.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth);
  console.log('[AC-15] 文档横向溢出像素 =', overflow);
  expect(overflow, 'AC-15：表格宽内容须在自己的 overflow-x 容器内滚动，页面本体不得横向溢出')
    .toBeLessThanOrEqual(1);

  // 品名列须有省略号截断能力
  // 🚨 2026-09-04：原为 `td.nth(1)`，在子任务插入「产品分类」列后指向了错的列
  //    （详见 helpers `columnIndexOf` 的注释）。改为按列头文案定位。
  await expect(page.locator('.ant-table-tbody tr.ant-table-row').first(),
    'AC-15：销售产品首屏应有行（0 行 ⇒ 下面的样式断言空跑）').toBeVisible({ timeout: 10_000 });
  const nameCell = await firstRowCellByHeader(page, '品名');
  const ellipsis = await nameCell.evaluate((el) => {
    const cs = getComputedStyle(el as HTMLElement);
    return { textOverflow: cs.textOverflow, whiteSpace: cs.whiteSpace, overflow: cs.overflow };
  });
  console.log('[AC-15] 品名单元格样式 =', JSON.stringify(ellipsis));
  evidence('AC15-ellipsis-style', JSON.stringify(ellipsis, null, 2));
  expect(ellipsis.textOverflow, 'AC-15：品名列须以省略号截断（antd `ellipsis: true`）').toBe('ellipsis');

  // 抽屉标题不换行破版
  const drawer = await openDrawer(page, HERO);
  const titleLines = await drawer.locator('.ant-drawer-title').first().evaluate((el) => {
    const cs = getComputedStyle(el as HTMLElement);
    return Math.round((el as HTMLElement).clientHeight / (parseFloat(cs.lineHeight) || 22));
  });
  console.log('[AC-15] 抽屉标题占行数 =', titleLines);
  expect(titleLines, 'AC-15：抽屉标题不得换行破版').toBeLessThanOrEqual(2);
  await shot(page, 'AC15-long-text', { fullPage: true });
  await closeDrawer(page);

  // ── ≥60 字符的正样本（修订后 AC：**替换**某行品名为 60+ 合成值，总数仍 42）──
  const longest = Number(sqlOne(
    `SELECT coalesce(max(length(material_name)),0)::text FROM ds_quote_material`));
  console.log(`[AC-15] 库中最长品名 = ${longest} 字符`);
  evidence('AC15-longest-name', `max(length(material_name)) = ${longest}`);
  expect(longest,
    `AC-15：应存在一条品名 ≥60 字符的记录（由样例准备步骤④**替换**某行品名produced，` +
    `不是新增 —— 新增会让 ds_quote_material 变 43 行、与 AC-4 的 42 冲突）。` +
    `当前库中最长仅 ${longest} 字符 ⇒ 步骤④未执行或未生效。`).toBeGreaterThanOrEqual(60);

  // 该超长行必须以省略号截断，且不把容器撑破
  const longRow = sqlOne(
    `SELECT material_no FROM ds_quote_material WHERE length(material_name) >= 60 LIMIT 1`)!;
  await search(page, longRow);
  // 🚨 同上：先等到行，再**按列头文案**取「品名」单元格。
  const longRowLoc = page.locator('.ant-table-tbody tr.ant-table-row').first();
  await expect(longRowLoc, `AC-15：应能搜到超长品名的行 ${longRow}`).toBeVisible({ timeout: 10_000 });
  const cell = await cellByHeader(page, longRowLoc, '品名');
  await expect(cell, `AC-15：${longRow} 的「品名」单元格应可见`).toBeVisible({ timeout: 10_000 });
  const clipped = await cell.evaluate((el) => ({
    scrollW: el.scrollWidth, clientW: el.clientWidth,
    textOverflow: getComputedStyle(el as HTMLElement).textOverflow,
  }));
  console.log('[AC-15] 超长单元格 =', JSON.stringify(clipped));
  evidence('AC15-clipped', `${longRow}: ${JSON.stringify(clipped)}`);
  expect(clipped.textOverflow, 'AC-15：超长品名须省略号截断').toBe('ellipsis');
  expect(clipped.scrollW, 'AC-15：内容确实溢出了单元格（未溢出则截断断言是空跑）')
    .toBeGreaterThan(clipped.clientW);

  const overflow2 = await page.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth);
  expect(overflow2, 'AC-15：超长文案在场时页面本体仍不得横向溢出').toBeLessThanOrEqual(1);
  await shot(page, 'AC15-long-name-row', { fullPage: true });
  await clearSearch(page);
});

// ══════════════════════════ E2E-16 → AC-16 权限不足 ══════════════════════════

test('E2E-16 / AC-16：SALES_REP 两个页签均可正常查看，无 403、无红色错误遮罩', async ({ page }) => {
  const errors = collectConsoleErrors(page);
  const forbidden: string[] = [];
  page.on('response', r => {
    if (r.status() === 403 && r.url().includes('/api/')) forbidden.push(`${r.status()} ${r.url()}`);
  });

  await loginAs(page, 'SALES_REP');
  await gotoProductHub(page);

  await switchTab(page, '客户产品');
  await expectTotalMatchesDb(page, 'ds_quote_customer_part', N_CUSTOMER_PART, 'AC-16 客户产品(SALES_REP)');
  expect(await rowCount(page), 'AC-16：SALES_REP 应能看到客户产品的行').toBeGreaterThan(0);
  await shot(page, 'AC16-salesrep-customer', { fullPage: true });

  await switchTab(page, '销售产品');
  await expectTotalMatchesDb(page, 'ds_quote_material', N_MATERIAL, 'AC-16 销售产品(SALES_REP)');
  expect(await rowCount(page), 'AC-16：SALES_REP 应能看到销售产品的行').toBeGreaterThan(0);

  // 抽屉内的成本性信息对 SALES_REP 也开放（闸门 A 裁决：四个角色都可看）
  const drawer = await openDrawer(page, HERO);
  await clickDrawerTab(page, drawer, '物料BOM');
  expect(await rowCount(drawer), 'AC-16：SALES_REP 也能看到抽屉内 BOM 数据（闸门 A 裁决）')
    .toBe(heroRowsInDb('ds_quote_material_bom'));
  await assertReadOnly(drawer, 'SALES_REP');
  await shot(page, 'AC16-salesrep-drawer', { fullPage: true });
  await closeDrawer(page);

  await expect(page.locator('.ant-result-403, .ant-result-error, .ant-alert-error'),
    'AC-16：不得出现 403 页或红色错误遮罩').toHaveCount(0);
  evidence('AC16-403-responses', forbidden.join('\n') || '(无 403)');
  expect(forbidden, `AC-16：本页对 SALES_REP 只读开放，不得有 403 响应：\n${forbidden.join('\n')}`)
    .toEqual([]);
  expect(errors, `AC-16：不得有 console error：\n${errors.join('\n')}`).toEqual([]);
});

// ══════════════════════════ ST-01 → AC-17 规范合规与文档回写 ══════════════════════════

test('ST-01 / AC-17：列表操作规范白名单 + RECORD 豁免说明 + INDEX 当前项目态势', async () => {
  const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '../..');
  const read = (p: string) => {
    const f = path.join(root, p);
    expect(fs.existsSync(f), `AC-17：文件不存在 ${p}`).toBeTruthy();
    return fs.readFileSync(f, 'utf-8');
  };

  const spec = read('docs/列表操作规范.md');
  for (const name of ['ProductCustomerPartTab', 'ProductSalesPartTab']) {
    expect(spec, `AC-17：docs/列表操作规范.md 的例外白名单须新增「${name}」—— ` +
      `不登记的后果不是形式问题：下一个人做代码审查会把这两个页签判成违规，` +
      `「顺手改成 SelectableTable」，把只读页改出批量勾选框`).toContain(name);
  }

  const record = read('docs/RECORD.md');
  expect(record, 'AC-17：docs/RECORD.md 须追加本次白名单豁免说明（含任务号 260903）')
    .toMatch(/260903/);

  const index = read('dev-docs/INDEX.md');
  expect(index, 'AC-17：dev-docs/INDEX.md「当前项目态势」须含本任务条目')
    .toMatch(/task-260903|产品管理页重做/);

  evidence('AC17-static-check', [
    `列表操作规范.md 含 ProductCustomerPartTab: ${spec.includes('ProductCustomerPartTab')}`,
    `列表操作规范.md 含 ProductSalesPartTab:   ${spec.includes('ProductSalesPartTab')}`,
    `RECORD.md 含 260903:                      ${/260903/.test(record)}`,
    `INDEX.md 含本任务:                        ${/task-260903|产品管理页重做/.test(index)}`,
  ].join('\n'));
});
