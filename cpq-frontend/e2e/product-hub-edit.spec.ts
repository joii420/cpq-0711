/**
 * task-260903「产品维护能力增强」（子任务）E2E + 接口层 —— `E-01`~`E-13` / `A-01`~`A-04`。
 *
 * 🚫 **本文件从 `需求文档.md` ④ 的 AC-1~AC-15 原文派生，没有读过任何实现代码**
 *    （`cpq-frontend/src/pages/product/` 与 `com.cpq.product.dataset` 全程未打开）。
 *    从实现派生的测试只能证明「代码按实现者的理解工作」，证明不了「功能符合需求」。
 *
 * 追溯矩阵见 `dev-docs/task-260903-产品管理页重做/task-260903-产品维护能力增强/test.md §3`。
 *
 * ── 为什么**不**用 `mode: 'serial'` ──
 *   父任务那套用了 serial（前后有状态依赖）。本套刻意不用：
 *   serial 下第一条红了，后面全部标 skipped ⇒ 报告里 14 条 AC 只剩 1 条有结论。
 *   本套每个用例**自带前置**（自己登录、自己导航、自己选过滤器），失败互不掩盖，
 *   代价是慢一些 —— 换来的是「哪条 AC 达成、哪条没达成」逐条可读。
 *   （`playwright.config.ts` 的 `workers: 1` / `fullyParallel: false` 保证了执行顺序仍是声明顺序，
 *     `workers: 1` 是**契约不是性能参数**，🚫 不要为了跑得快调大它。）
 *
 * ── 🚨 共享库写纪律（本套首次引入写操作）──
 *   1. 唯一允许写的料号是 `HERO_EDIT = S-1630010773`；
 *   2. 每个写用例 `try/finally` 自己还原；
 *   3. `afterAll` 断言**那一行逐字节回到开跑前**（行级残留证明，见 helpers 里的论证）。
 */
import { test, expect } from '@playwright/test';
import {
  assertIsolatedEnv, assertEditFixtureReady, snapshotHeroRow, reportTableDrift,
  sql, sqlOne, loginAs, gotoProductHub, switchTab, headerTexts, totalCount, rowCount,
  search, clearSearch, openDrawer, closeDrawer, clickDrawerTab,
  assertReadOnly, assertReadOnlyProbeWorks, collectConsoleErrors,
  customerFilter, currentFilterLabel, openFilterOptions, closeFilterOptions, selectCustomer,
  expectFilteredTotal, columnValues, columnIndex, customerDistributionInDb, unregisteredCustomersInDb,
  materialCell, readProductionNoOnPage, editProductionNoViaUi, probeEnterEdit, cellHasEditor,
  dismissDrawer, assertNoRedOverlay,
  readProductionNo, readColumn, restoreProductionNo,
  anonymousApi, apiAs, putPartUrl,
  shot, evidence,
  HERO_EDIT, HERO_DRAWER, ALL_CUSTOMERS_LABEL, FILTER_CUSTOMER,
  AC_EXPECTED_CUSTOMERS, AC_UNREGISTERED_CUSTOMERS, AC_N_CUSTOMER_PART, AC_N_CUST0004,
  AC_SEARCH_KEY, NEW_PRODUCTION_NO, PRODUCTION_NO_MAXLEN, OVERLONG_VALUE,
  NONEXISTENT_KEYWORD, NON_WHITELIST_FIELD, MATERIAL_COLUMNS,
} from './product-hub-edit.helpers';

/** 开跑前的基线：`HERO_EDIT` 那一行的整行文本 + 两张表的行数（漂移提示用）。 */
let heroRowBefore: string | null = null;
let baselineProductionNo: string | null = null;
let beforeCounts: Record<string, number> = {};

test.beforeAll(() => {
  assertIsolatedEnv();
  const { productionNo, source } = assertEditFixtureReady();
  baselineProductionNo = productionNo;
  heroRowBefore = snapshotHeroRow();
  beforeCounts = {
    ds_quote_material: Number(sqlOne('SELECT count(*) FROM ds_quote_material')),
    ds_quote_customer_part: Number(sqlOne('SELECT count(*) FROM ds_quote_customer_part')),
  };
  console.log(`[基线] ${HERO_EDIT}.production_no=${JSON.stringify(productionNo)} source=${JSON.stringify(source)}`);
  console.log(`[基线] ds_quote_material=${beforeCounts.ds_quote_material} ds_quote_customer_part=${beforeCounts.ds_quote_customer_part}`);
  evidence('00-baseline',
    `HERO_EDIT=${HERO_EDIT}\nproduction_no=${JSON.stringify(productionNo)}\nsource=${JSON.stringify(source)}\n` +
    `整行=${heroRowBefore}\n表行数=${JSON.stringify(beforeCounts)}\n采样时刻=${new Date().toISOString()}`);
});

test.afterAll(() => {
  // 🚨 无残留证明。beforeAll 抛错时 heroRowBefore 是 null ——
  //    此时若硬比对，会制造第二个假故障，把 beforeAll 的真实原因盖掉
  //    （父任务 2026-09-03 证伪实验实测踩过这个坑）。
  if (!heroRowBefore) {
    console.warn('[无残留证明] 跳过：beforeAll 未取到基线（真实失败原因见上一条错误）');
    return;
  }
  reportTableDrift(beforeCounts);
  const after = snapshotHeroRow();
  if (after !== heroRowBefore) {
    // ⚠️ 这里不能只喊「有残留」——要把差异原文打出来，否则下一个人要重跑一遍才知道差在哪
    expect(false,
      `🚨 共享库残留：${HERO_EDIT} 那一行跑完之后与开跑前不同。\n` +
      `  before = ${heroRowBefore}\n  after  = ${after}\n` +
      `  ⇒ 某个写用例没有还原。**必须上报主线并人工复位**，🚫 不要重跑掩盖。`).toBeTruthy();
  }
  console.log(`[无残留证明] ${HERO_EDIT} 整行与开跑前逐字节相同 ✅`);
  evidence('99-no-residue', `before=${heroRowBefore}\nafter =${after}\n结论=逐字节相同`);
});

// ═══════════════════════════════════════════════════════════════════════════
//  第一组：客户过滤（AC-1 ~ AC-5、AC-14）—— 全部只读，不写库
// ═══════════════════════════════════════════════════════════════════════════

test('E-01 / AC-1：客户过滤器存在、默认「所有客户」、此时总数与未过滤一致', async ({ page }) => {
  const errs = collectConsoleErrors(page);
  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);
  await switchTab(page, '客户产品');

  const filter = await customerFilter(page);
  await expect(filter, 'AC-1：「客户产品」工具栏应存在客户下拉').toBeVisible();

  const label = await currentFilterLabel(page);
  console.log(`[AC-1] 过滤器默认文案 = ${JSON.stringify(label)}`);
  expect(label,
    `AC-1：过滤器默认值文案应为「${ALL_CUSTOMERS_LABEL}」，实际 ${JSON.stringify(label)}` +
    `（基准：原型图/客户产品-过滤器.html —— 过滤器在搜索框左侧，默认文案 ${ALL_CUSTOMERS_LABEL}）`)
    .toContain(ALL_CUSTOMERS_LABEL);

  // 总数走不变量（共享库漂移下唯一站得住的形式），AC 基线 17 只打印供人工核对
  const { ui, db } = await expectFilteredTotal(page, 'AC-1 未过滤总数', '', AC_N_CUSTOMER_PART);
  expect(await rowCount(page), 'AC-1：首屏应渲染出行（0 行 ⇒ 后续断言空跑 = 假绿）').toBeGreaterThan(0);

  // 过滤器必须在搜索框「左侧」（原型基准）—— 用 boundingBox 的 x 比较，比 DOM 顺序更贴近可观测事实
  const box = await filter.boundingBox();
  // 🚨 排除 `.ant-select-input`：antd Select 内部也有个 `type="search"` 的 readonly input，
  //    不排掉会拿到过滤器自己的坐标，于是「过滤器在搜索框左边」变成和自己比，恒真。
  const searchBox = await page.locator(
    'input[placeholder*="搜索"]:not(.ant-select-input), input[type="search"]:not(.ant-select-input)'
  ).first().boundingBox();
  if (box && searchBox) {
    console.log(`[AC-1] 过滤器 x=${box.x}  搜索框 x=${searchBox.x}`);
    expect(box.x, 'AC-1：过滤器应在搜索框**左侧**（原型图/客户产品-过滤器.html）').toBeLessThan(searchBox.x);
  } else {
    console.warn('[AC-1] 取不到 boundingBox，跳过左右位置断言（不作为通过依据，需人工比对原型）');
  }

  evidence('AC01-default-filter', `默认文案=${JSON.stringify(label)}\n页面总数=${ui}\n库 count(*)=${db}\nAC 基线=${AC_N_CUSTOMER_PART}`);
  await shot(page, 'AC01-filter-default', { fullPage: true });
  expect(errs, `AC-1：过程中不得有 console error：${JSON.stringify(errs)}`).toEqual([]);
});

test('E-02 / AC-2：选 CUST-0004 → 总数变 11，可见行的客户编号全是 CUST-0004', async ({ page }) => {
  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);
  await switchTab(page, '客户产品');

  await selectCustomer(page, FILTER_CUSTOMER);

  const { ui, db } = await expectFilteredTotal(
    page, `AC-2 ${FILTER_CUSTOMER} 过滤后总数`, `customer_no = '${FILTER_CUSTOMER}'`, AC_N_CUST0004);

  const n = await rowCount(page);
  expect(n, 'AC-2：过滤后应仍有行（0 行 ⇒ 下面的「全是 CUST-0004」会空跑 = 假绿）').toBeGreaterThan(0);

  const cnos = await columnValues(page, '客户编号');
  console.log(`[AC-2] 可见 ${n} 行的客户编号 = ${JSON.stringify(cnos)}`);
  for (const c of cnos) {
    expect(c, `AC-2：过滤后所有可见行的「客户编号」都必须是 ${FILTER_CUSTOMER}，出现了 ${JSON.stringify(c)}`)
      .toBe(FILTER_CUSTOMER);
  }
  evidence('AC02-filtered', `页面总数=${ui} 库=${db} AC 基线=${AC_N_CUST0004}\n可见行客户编号=${JSON.stringify(cnos)}`);
  await shot(page, 'AC02-filter-cust0004', { fullPage: true });
});

test('E-03 / AC-3【序列】：过滤 + 搜索叠加 → 清空搜索回到 11（不是 17），过滤器不被重置', async ({ page }) => {
  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);
  await switchTab(page, '客户产品');

  // ── 中间态 1：只选客户 ──
  await selectCustomer(page, FILTER_CUSTOMER);
  await expectFilteredTotal(page, 'AC-3 中间态1 只过滤',
    `customer_no = '${FILTER_CUSTOMER}'`, AC_N_CUST0004);

  // ── 中间态 2：叠加搜索 ──
  await search(page, AC_SEARCH_KEY);
  const n1 = await rowCount(page);
  const total1 = await totalCount(page);
  const cnos1 = await columnValues(page, '客户编号');
  const mnos1 = await columnValues(page, '销售料号');
  console.log(`[AC-3] 过滤+搜索 → 共 ${total1} 条，可见 ${n1} 行，客户=${JSON.stringify(cnos1)} 料号=${JSON.stringify(mnos1)}`);
  expect(total1, `AC-3：${FILTER_CUSTOMER} + 关键字「${AC_SEARCH_KEY}」应得 1 行`).toBe(1);
  expect(cnos1[0], `AC-3：命中行的客户编号应为 ${FILTER_CUSTOMER}`).toBe(FILTER_CUSTOMER);
  expect(mnos1[0], `AC-3：命中行的销售料号应为 ${AC_SEARCH_KEY}`).toBe(AC_SEARCH_KEY);

  // ── 🚩 AND 判别式（AC-3 原文「可叠加」的实质）──
  //    只断「1 行」证明不了 AND：不带过滤器搜同一个关键字也是 1 行。
  //    换成**另一个客户** + 同一个关键字 ⇒ 真 AND 必须得 0 行；
  //    若前端只按关键字过滤（把客户条件丢了），这里会得 1 行 —— 这才是有判别力的断言。
  await selectCustomer(page, 'CUST-0001');
  const totalCross = await totalCount(page);
  console.log(`[AC-3] AND 判别式：CUST-0001 + 「${AC_SEARCH_KEY}」→ 共 ${totalCross} 条（期望 0）`);
  expect(totalCross,
    `AC-3：「${AC_SEARCH_KEY}」属于 ${FILTER_CUSTOMER}，在 CUST-0001 下必须搜不到。` +
    `得到 ${totalCross} ⇒ 客户条件与关键字**不是 AND**（大概率客户条件被丢弃）`).toBe(0);

  // ── 最终态：切回 CUST-0004，清空搜索 ──
  await selectCustomer(page, FILTER_CUSTOMER);
  await clearSearch(page);
  const labelAfter = await currentFilterLabel(page);
  const { ui: finalUi } = await expectFilteredTotal(page, 'AC-3 最终态 清空搜索后',
    `customer_no = '${FILTER_CUSTOMER}'`, AC_N_CUST0004);
  console.log(`[AC-3] 清空搜索后：过滤器文案=${JSON.stringify(labelAfter)} 总数=${finalUi}`);
  expect(labelAfter,
    `AC-3：清空搜索**不得**把客户过滤器一并重置（原型：切客户重置到第 1 页但不清空搜索框；反向亦然）。` +
    `实际过滤器文案 = ${JSON.stringify(labelAfter)}`).toContain(FILTER_CUSTOMER);
  expect(finalUi,
    `AC-3：清空搜索应回到 ${FILTER_CUSTOMER} 的行数，**不是**全量 —— ` +
    `若等于全量总数说明过滤器被搜索清空连带重置了`).not.toBe(beforeCounts.ds_quote_customer_part);

  evidence('AC03-sequence',
    `中间态1(只过滤)=${AC_N_CUST0004}(基线)\n中间态2(过滤+搜索)=${total1}\n` +
    `AND 判别式(CUST-0001+同关键字)=${totalCross}\n最终态(清空搜索)=${finalUi}  过滤器=${JSON.stringify(labelAfter)}`);
  await shot(page, 'AC03-filter-search-sequence', { fullPage: true });
});

test('E-04 / AC-4：切回「所有客户」→ 总数还原', async ({ page }) => {
  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);
  await switchTab(page, '客户产品');

  await selectCustomer(page, FILTER_CUSTOMER);
  await expectFilteredTotal(page, 'AC-4 前置 已过滤', `customer_no = '${FILTER_CUSTOMER}'`, AC_N_CUST0004);

  await selectCustomer(page, ALL_CUSTOMERS_LABEL);
  const { ui, db } = await expectFilteredTotal(page, 'AC-4 切回所有客户', '', AC_N_CUSTOMER_PART);
  expect(await rowCount(page), 'AC-4：还原后应渲染出行').toBeGreaterThan(0);

  evidence('AC04-restore', `切回「${ALL_CUSTOMERS_LABEL}」后 页面总数=${ui} 库=${db} AC 基线=${AC_N_CUSTOMER_PART}`);
  await shot(page, 'AC04-all-customers');
});

test('E-05 / AC-5：候选来自数据中出现过的客户（含未在 customer 表建档的 2 个）', async ({ page }) => {
  // 🚩 期望值**从库里取**而不是写死 —— 共享库会漂移；同时打印 AC 基线供人工核对
  const dist = customerDistributionInDb();
  const unreg = unregisteredCustomersInDb();
  console.log(`[AC-5] 库中客户分布 = ${JSON.stringify(dist)}`);
  console.log(`[AC-5] 未在 customer 表建档的 = ${JSON.stringify(unreg)}`);

  expect(dist.length, 'AC-5：库里至少要有客户数据，否则本条断言空跑').toBeGreaterThan(0);
  expect(unreg.length,
    `AC-5 的验证素材缺失：库中已没有「未在 customer 表建档」的客户了。\n` +
    `  ${AC_UNREGISTERED_CUSTOMERS.join(' / ')} 若被人「顺手修好」建了档，本条 AC 就**永远无法证伪** ——\n` +
    `  候选只从 customer 表取也会通过。⇒ 停下报告主线，不要降级断言。`).toBeGreaterThan(0);

  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);
  await switchTab(page, '客户产品');

  const opts = await openFilterOptions(page);
  evidence('AC05-options', `候选项原文 = ${JSON.stringify(opts, null, 2)}\n` +
    `库中分布 = ${JSON.stringify(dist, null, 2)}\n未建档 = ${JSON.stringify(unreg)}`);
  await shot(page, 'AC05-filter-options');

  expect(opts.length, 'AC-5：下拉展开后应有候选项（0 项 ⇒ 下面的包含断言全部空跑 = 假绿）').toBeGreaterThan(0);

  const joined = opts.join(' | ');
  for (const { customerNo } of dist) {
    expect(joined,
      `AC-5：候选项里必须出现客户 ${customerNo}（以**数据中出现过的客户**为准，不是 customer 建档表）。\n` +
      `  实际候选 = ${JSON.stringify(opts)}`).toContain(customerNo);
  }
  // 未建档的两个是本条 AC 的全部意义：候选只从 customer 表取 ⇒ 它们的产品看得见却筛不出来
  for (const c of unreg) {
    expect(joined,
      `🚩 AC-5 的关键分支：${c} **未在 customer 表建档**，若候选只从 customer 表取，` +
      `它的产品在列表里看得见、却永远筛不出来。实际候选 = ${JSON.stringify(opts)}`).toContain(c);
  }
  // AC 基线核对（不作为断言，共享库会漂移）
  const missingFromAc = AC_EXPECTED_CUSTOMERS.filter(c => !dist.some(d => d.customerNo === c));
  if (missingFromAc.length) {
    console.warn(`⚠️ [AC-5] AC 基线里的 ${JSON.stringify(missingFromAc)} 已不在库中（数据漂移），` +
      `本轮以库中实际分布为准`);
  }
  await closeFilterOptions(page);
});

test('E-13 / AC-14【边界】：过滤命中 0 行 → 空态 + 共 0 条 + 过滤器仍可交互', async ({ page }) => {
  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);
  await switchTab(page, '客户产品');

  // AC-14 原文：下拉候选均有产品时，用搜索框输入「不存在的料号XYZ」制造空结果
  await selectCustomer(page, FILTER_CUSTOMER);
  await search(page, NONEXISTENT_KEYWORD);

  const total = await totalCount(page);
  const n = await rowCount(page);
  console.log(`[AC-14] 共 ${total} 条 / 可见 ${n} 行`);
  expect(n, 'AC-14：应无数据行').toBe(0);
  expect(total, 'AC-14：分页器应显示「共 0 条」').toBe(0);

  await expect(page.getByText('暂无数据').first(),
    'AC-14：应渲染 Empty 空态 + 文案「暂无数据」（原型图/边界-过滤空结果与极值.html ①）')
    .toBeVisible({ timeout: 8_000 });
  await expect(page.getByText('加载中', { exact: false }),
    'AC-14：空结果不得停在「加载中…」永久占位（AP-31 族）').toHaveCount(0);
  await assertNoRedOverlay(page, 'AC-14');

  // 🚨 「过滤器仍可交互」是本条 AC 最实质的一句：不因无结果而禁用，
  //    否则用户切不回「所有客户」就卡死在空页面上了。
  const filter = await customerFilter(page);
  await expect(filter, 'AC-14：空结果下过滤器仍应可见').toBeVisible();
  const disabled = await filter.evaluate((e: any) =>
    e.classList?.contains('ant-select-disabled') || e.disabled === true).catch(() => false);
  expect(disabled, 'AC-14：空结果下过滤器**不得**被禁用（否则用户切不回「所有客户」）').toBe(false);

  // 真正证明「可交互」的方式是**真的去操作它**，而不是只看 disabled 属性
  await selectCustomer(page, ALL_CUSTOMERS_LABEL);
  const labelAfter = await currentFilterLabel(page);
  expect(labelAfter, 'AC-14：空结果下应仍能把过滤器切回「所有客户」').toContain(ALL_CUSTOMERS_LABEL);

  await shot(page, 'AC14-empty-result', { fullPage: true });
  evidence('AC14-empty', `共 ${total} 条 / 可见 ${n} 行 / 过滤器 disabled=${disabled} / 切回后=${JSON.stringify(labelAfter)}`);

  await clearSearch(page);
});

// ═══════════════════════════════════════════════════════════════════════════
//  第二组：只读反向断言 + 产品分类列（AC-7、AC-8）—— 不写库
// ═══════════════════════════════════════════════════════════════════════════

test('E-08 / AC-7【反向】：抽屉仍然全只读 —— 编辑能力不得渗进抽屉', async ({ page }) => {
  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);
  await switchTab(page, '销售产品');

  const drawer = await openDrawer(page, HERO_DRAWER);

  // 🚨 阳性对照先行：证明 drawer 定位不是空的。
  //    否则下面全是「断言不存在」，选择器写错会**恒定通过** —— 最隐蔽的一类假绿。
  await assertReadOnlyProbeWorks(drawer, HERO_DRAWER);

  await assertReadOnly(drawer, `${HERO_DRAWER} 默认 tab`);
  // 换一个 tab 再验一次：只验默认 tab 挡不住「某个 tab 单独用了可编辑表格」
  await clickDrawerTab(page, drawer, '物料BOM');
  await assertReadOnly(drawer, `${HERO_DRAWER} 物料BOM`);

  // AC-7 还点名了「双击单元格不进编辑态」—— 与 AC-6 用同一套探测序列，保持对称
  const cell = drawer.locator('.ant-table-tbody tr td').first();
  if (await cell.count()) {
    const probe = await probeEnterEdit(page, cell, '抽屉 物料BOM 首格');
    expect(probe.entered,
      `AC-7：抽屉内单元格**不得**进入编辑态（本次经「${probe.via}」进去了）。\n` +
      `  🚨 S-1 只开放「销售产品列表的生产料号」这一个格子，编辑能力渗进抽屉 = 越界，立刻停下报主线。`)
      .toBe(false);
  } else {
    // 🚫 不静默通过：没有单元格 ⇒ 这条断言从未执行
    expect(false,
      `AC-7 的「双击不进编辑态」分支**无法取证**：抽屉「物料BOM」里一个单元格都没有。\n` +
      `  ⇒ 前置数据缺失或 tab 未渲染，需上报，不得当作通过。`).toBeTruthy();
  }

  const inputs = await drawer.locator('.ant-table input, .ant-table textarea, .ant-table select').count();
  const saves = await drawer.getByRole('button', { name: /保\s*存/ }).count();
  evidence('AC07-drawer-readonly', `抽屉 ${HERO_DRAWER}：input/textarea/select=${inputs}  保存按钮=${saves}（期望 0 / 0）`);
  await shot(page, 'AC07-drawer-readonly', { fullPage: true });
  await closeDrawer(page);
});

test('E-09 / AC-8：销售产品显示「产品分类」列（⏸ 依赖 task-260902 的 B-16）', async ({ page }) => {
  const errs = collectConsoleErrors(page);
  // 先看库里落地了没 —— 决定本条是「验值」还是「验兜底」，两种结论不能混着报
  const catCols = sql(
    `SELECT column_name FROM information_schema.columns
     WHERE table_schema='public' AND table_name='ds_quote_material' AND column_name ILIKE '%categ%'`);
  console.log(`[AC-8] ds_quote_material 的分类相关列 = ${JSON.stringify(catCols)}`);

  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);
  await switchTab(page, '销售产品');

  const cols = await headerTexts(page);
  console.log(`[AC-8] 销售产品实际列 = ${JSON.stringify(cols)}`);
  evidence('AC08-columns', `实际列=${JSON.stringify(cols)}\n父任务 7 列基线=${JSON.stringify(MATERIAL_COLUMNS)}\n` +
    `库中分类列=${JSON.stringify(catCols)}`);

  expect(cols, 'AC-8：销售产品列表应出现「产品分类」列').toContain('产品分类');

  // 父任务 7 列必须一列不少（AC-8 只是**加**一列，不是重排/替换）
  for (const c of MATERIAL_COLUMNS) {
    expect(cols, `AC-8 不得破坏父任务 AC-4 的既有列：缺少「${c}」`).toContain(c);
  }

  const vals = await columnValues(page, '产品分类');
  expect(vals.length, 'AC-8：首屏应有行（0 行 ⇒ 下面的值断言空跑 = 假绿）').toBeGreaterThan(0);
  console.log(`[AC-8] 产品分类列可见值 = ${JSON.stringify(vals)}`);

  for (const v of vals) {
    expect(v, `AC-8：产品分类单元格不得是 undefined / null / 空白，实际 ${JSON.stringify(v)}`)
      .not.toMatch(/^(undefined|null|)$/);
    expect(['默认分类', '—'].includes(v) || v.length > 0,
      `AC-8：分类值 ${JSON.stringify(v)} 既不是「默认分类」也不是「—」`).toBeTruthy();
  }
  if (!catCols.length) {
    // 字段未落地时的兜底要求（`api.md` C-2）：渲染 `—` 且不崩
    const allDash = vals.every(v => v === '—');
    console.log(`[AC-8] 字段尚未落库 ⇒ 走兜底分支，全部为「—」= ${allDash}`);
    expect(allDash,
      `AC-8 兜底：task-260902 的分类字段尚未落库（information_schema 无 %categ% 列），` +
      `此时产品分类列必须全部渲染「—」且不崩。实际值 = ${JSON.stringify(vals)}`).toBe(true);
    console.warn('⚠️ [AC-8] 本轮只验到「字段未落地时的兜底」，**没有验到真实分类值** —— ' +
      '报告里必须写「AC-8 部分验证：默认值落点待 task-260902 B-16 落库后复验」');
  }
  await assertNoRedOverlay(page, 'AC-8');
  await shot(page, 'AC08-category-column', { fullPage: true });
  expect(errs, `AC-8：过程中不得有 console error：${JSON.stringify(errs)}`).toEqual([]);
});

// ═══════════════════════════════════════════════════════════════════════════
//  第三组：生产料号单列编辑（AC-6 / AC-6b / AC-10 / AC-12 / AC-13）
//  🚨 以下用例**会写共享库**，每条自己 try/finally 还原
// ═══════════════════════════════════════════════════════════════════════════

test('E-06 / AC-6：生产料号可进编辑态，其余 6 列不可（同一套交互，对称验证）', async ({ page }) => {
  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);
  await switchTab(page, '销售产品');

  // ── 正向：生产料号 ──
  const target = await materialCell(page, HERO_EDIT, '生产料号');
  const okProbe = await probeEnterEdit(page, target, '生产料号');
  expect(okProbe.entered,
    `AC-6：${HERO_EDIT} 的「生产料号」单元格应能进入编辑态。\n` +
    `  已尝试：双击 / 悬停后点编辑图标 / 单击（三者全落空）\n` +
    `  ⇒ S-1 未落地，或交互与 原型图/销售产品-可编辑生产料号.html 状态①② 不符。`).toBe(true);
  console.log(`[AC-6] 进入编辑态的交互方式 = ${okProbe.via}`);
  await page.keyboard.press('Escape');   // Esc 取消，🚫 不留改动
  await page.waitForTimeout(500);
  await dismissDrawer(page);

  // ── 反向：其余 6 列，用**同一套**探测序列 ──
  //   不对称（正向试 3 种、反向只试 1 种）会得出一个漂亮但毫无意义的结论。
  const readOnlyCols = MATERIAL_COLUMNS.filter(c => c !== '生产料号');
  const report: string[] = [`生产料号: 进编辑态 ✅ via=${okProbe.via}`];
  for (const col of readOnlyCols) {
    const cell = await materialCell(page, HERO_EDIT, col);
    const p = await probeEnterEdit(page, cell, col);
    report.push(`${col}: entered=${p.entered} via=${p.via}`);
    expect(p.entered,
      `AC-6：「${col}」列**不得**进入编辑态（本次经「${p.via}」进去了）。\n` +
      `  🚨 用户裁决只开放 production_no 一列，其余列与整个抽屉保持只读。`).toBe(false);
    await page.keyboard.press('Escape').catch(() => {});
    await dismissDrawer(page);
  }
  evidence('AC06-editable-matrix', report.join('\n'));
  await shot(page, 'AC06-editable-cell');

  // 本用例只进出编辑态、不保存 ⇒ 库应当没变（afterAll 的行级残留证明会兜底再验一次）
  const now = readProductionNo();
  expect(now.raw, 'AC-6：只进出编辑态不保存，库值不应变化').toBe(baselineProductionNo);
});

test('E-10 / AC-10【序列】：保存 → 提示成功 → 刷新仍在 → 落库 → source 保持 IMPORT', async ({ page }) => {
  const orig = readProductionNo().raw;
  const origSource = readColumn('source');
  console.log(`[AC-10] 改前：production_no=${JSON.stringify(orig)} source=${JSON.stringify(origSource)}`);
  try {
    await loginAs(page, 'SYSTEM_ADMIN');
    await gotoProductHub(page);
    await switchTab(page, '销售产品');

    const r = await editProductionNoViaUi(page, HERO_EDIT, NEW_PRODUCTION_NO);

    // ① 页面提示保存成功
    expect(r.failedResponses, `AC-10：保存过程中不得出现 4xx/5xx：${JSON.stringify(r.failedResponses)}`).toEqual([]);
    expect(r.messages.join(' | '),
      `AC-10 ①：保存后应有「成功」提示，实际 message = ${JSON.stringify(r.messages)}`).toMatch(/成功/);

    // ② 刷新页面，值仍在（这一步才排除「只改了前端 state」）
    await page.reload({ waitUntil: 'domcontentloaded' });
    await gotoProductHub(page);
    await switchTab(page, '销售产品');
    const onPage = await readProductionNoOnPage(page, HERO_EDIT);
    console.log(`[AC-10] 刷新后页面显示 = ${JSON.stringify(onPage)}`);
    expect(onPage, `AC-10 ②：刷新后该行应仍显示 ${NEW_PRODUCTION_NO}`).toBe(NEW_PRODUCTION_NO);

    // ③ 直接查库（绕过页面）—— FS-1 的正题
    const db = readProductionNo();
    expect(db.raw, `AC-10 ③：库中 production_no 应为 ${NEW_PRODUCTION_NO}，实际 ${JSON.stringify(db.raw)}`)
      .toBe(NEW_PRODUCTION_NO);

    // ④ source 保持原值（对方约定：PUT 只改目标列，不把整行标成 MANUAL）
    const nowSource = readColumn('source');
    expect(nowSource,
      `AC-10 ④：source 是**行级**来源，改一列不应把整行标成 MANUAL。` +
      `改前=${JSON.stringify(origSource)} 改后=${JSON.stringify(nowSource)}`).toBe(origSource);
    expect(nowSource, `AC-10 ④：AC 原文点名 source 应保持 'IMPORT'（本行改前实测即为 IMPORT）`).toBe('IMPORT');

    evidence('AC10-persist',
      `改前 production_no=${JSON.stringify(orig)} source=${JSON.stringify(origSource)}\n` +
      `message=${JSON.stringify(r.messages)}\n刷新后页面=${JSON.stringify(onPage)}\n` +
      `库中=${JSON.stringify(db.raw)}\n改后 source=${JSON.stringify(nowSource)}`);
    await shot(page, 'AC10-saved-persisted');
  } finally {
    restoreProductionNo(orig);
  }
});

test('E-11 / AC-12【边界】：清空 → 列表渲染「—」→ 落库 NULL（不是空字符串）', async ({ page }) => {
  const orig = readProductionNo().raw;
  try {
    await loginAs(page, 'SYSTEM_ADMIN');
    await gotoProductHub(page);
    await switchTab(page, '销售产品');

    const r = await editProductionNoViaUi(page, HERO_EDIT, '');
    expect(r.failedResponses, `AC-12：清空保存不得出现 4xx/5xx：${JSON.stringify(r.failedResponses)}`).toEqual([]);

    const onPage = await readProductionNoOnPage(page, HERO_EDIT);
    console.log(`[AC-12] 清空后页面显示 = ${JSON.stringify(onPage)}`);
    expect(onPage,
      `AC-12：清空后该单元格应渲染「—」（原型图/边界-过滤空结果与极值.html ③），实际 ${JSON.stringify(onPage)}`)
      .toBe('—');

    // 🚨 NULL vs 空字符串是本条 AC 的**全部要害**：
    //    空串在页面上同样渲染成「—」，只看页面永远发现不了这个区别。
    const db = readProductionNo();
    console.log(`[AC-12] 库中：raw=${JSON.stringify(db.raw)} isNull=${db.isNull} isEmptyString=${db.isEmptyString}`);
    expect(db.isNull,
      `AC-12：库中 production_no 必须是 **NULL**，不是空字符串。` +
      `实际 isNull=${db.isNull} isEmptyString=${db.isEmptyString} raw=${JSON.stringify(db.raw)}\n` +
      `  ⚠️ 空串与 NULL 在页面上都渲染成「—」，只看 UI 抓不到这个差别；` +
      `而它会直接影响 AC-9 的 COALESCE 语义（COALESCE 认 NULL 不认空串）。`).toBe(true);

    evidence('AC12-clear-null',
      `清空后页面=${JSON.stringify(onPage)}\n库中 raw=${JSON.stringify(db.raw)} isNull=${db.isNull} isEmptyString=${db.isEmptyString}`);
    await shot(page, 'AC12-cleared-dash');
  } finally {
    restoreProductionNo(orig);
  }
});

test('E-12 / AC-13【边界】：超长输入被拒 —— 可读提示、无 500、无红色遮罩、库中原值未被截断写入', async ({ page }) => {
  const orig = readProductionNo().raw;
  const errs = collectConsoleErrors(page);
  try {
    await loginAs(page, 'SYSTEM_ADMIN');
    await gotoProductHub(page);
    await switchTab(page, '销售产品');

    const r = await editProductionNoViaUi(page, HERO_EDIT, OVERLONG_VALUE);
    console.log(`[AC-13] 输入 ${OVERLONG_VALUE.length} 字符（列长 ${PRODUCTION_NO_MAXLEN}）`);

    // ① 不出现 500
    const server500 = r.failedResponses.filter(s => /^5\d\d /.test(s));
    expect(server500, `AC-13：不得出现 5xx。实际 = ${JSON.stringify(r.failedResponses)}`).toEqual([]);

    // ② 不出现红色错误遮罩
    await assertNoRedOverlay(page, 'AC-13');

    // ③ 有可读提示（前端或后端给出均可 —— AC 原文允许两者之一）
    const inlineErr = await page.locator('.ant-form-item-explain-error, .ant-input-status-error, .ant-message-error')
      .allInnerTexts().then(a => a.map(s => s.trim())).catch(() => []);
    const allHints = [...r.messages, ...inlineErr].filter(Boolean);
    console.log(`[AC-13] 可读提示 = ${JSON.stringify(allHints)}`);
    expect(allHints.length,
      `AC-13：超长输入必须给出**可读提示**（原型图/边界-过滤空结果与极值.html ②：` +
      `「生产料号最长 128 字符，当前 147」这类）。实际一条提示都没有 ⇒ 用户会以为保存成功了。`)
      .toBeGreaterThan(0);
    expect(allHints.join(' | '),
      `AC-13：提示应当可读（含长度/字符相关字样），实际 = ${JSON.stringify(allHints)}`)
      .toMatch(/长|字符|超|限/);

    // ④ 🚨 库中原值未被**截断写入** —— 这是本条最容易漏的：
    //    有些实现会 substring(0,128) 后照写，页面看着"成功"，数据已经被悄悄改了。
    const db = readProductionNo();
    console.log(`[AC-13] 库中 = ${JSON.stringify(db.raw)}（长度 ${db.raw?.length ?? 0}）`);
    expect(db.raw,
      `AC-13：超长输入被拒后，库中 production_no 必须**保持原值** ${JSON.stringify(orig)}，` +
      `实际 ${JSON.stringify(db.raw)}（长度 ${db.raw?.length ?? 0}）。\n` +
      `  若长度恰为 ${PRODUCTION_NO_MAXLEN} ⇒ 被截断写入了，那是静默数据损坏，比报错严重得多。`)
      .toBe(orig);

    evidence('AC13-overlong',
      `输入长度=${OVERLONG_VALUE.length} 列长=${PRODUCTION_NO_MAXLEN}\n` +
      `4xx/5xx=${JSON.stringify(r.failedResponses)}\n提示=${JSON.stringify(allHints)}\n` +
      `库中=${JSON.stringify(db.raw)}（长度 ${db.raw?.length ?? 0}）`);
    await shot(page, 'AC13-overlong-rejected');
    const fatal = errs.filter(e => !/Failed to load resource|400|422/.test(e));
    console.log(`[AC-13] console error（已滤掉预期的 4xx 资源报错）= ${JSON.stringify(fatal)}`);
  } finally {
    restoreProductionNo(orig);
  }
});

test('E-07a / AC-6b：SALES_REP / PRICING_MANAGER / SYSTEM_ADMIN 三个角色均可改并保存', async ({ page }) => {
  const orig = readProductionNo().raw;
  const results: string[] = [];
  try {
    for (const role of ['SALES_REP', 'PRICING_MANAGER', 'SYSTEM_ADMIN'] as const) {
      const val = `TEST-${role.slice(0, 6)}-001`;
      await page.context().clearCookies();
      await loginAs(page, role);
      await gotoProductHub(page);
      await switchTab(page, '销售产品');

      const r = await editProductionNoViaUi(page, HERO_EDIT, val);
      expect(r.failedResponses,
        `AC-6b：角色 ${role} 保存时不得出现 4xx/5xx（用户 2026-09-03 裁决四角色都能改，` +
        `403 ⇒ 后端权限注解没跟上 UI）。实际 = ${JSON.stringify(r.failedResponses)}`).toEqual([]);

      // 「保存成功」的判据是**库里真的变了**，不是页面出现了绿条
      const db = readProductionNo();
      expect(db.raw, `AC-6b：角色 ${role} 保存后库值应为 ${val}，实际 ${JSON.stringify(db.raw)}`).toBe(val);

      // AC-6b 原文：保存后**重新加载列表**，值为新值
      await page.reload({ waitUntil: 'domcontentloaded' });
      await gotoProductHub(page);
      await switchTab(page, '销售产品');
      const onPage = await readProductionNoOnPage(page, HERO_EDIT);
      expect(onPage, `AC-6b：角色 ${role} 重新加载列表后应显示 ${val}`).toBe(val);

      results.push(`${role}: 保存 ✅ 库值=${db.raw} 重载后页面=${onPage}`);
      console.log(`[AC-6b] ${role} ✅`);
    }
    evidence('AC06b-roles', results.join('\n'));
  } finally {
    restoreProductionNo(orig);
  }
});

test('E-07b / AC-6b：SALES_MANAGER 角色（🚨 无可用测试账号 → 硬失败，不 skip）', async ({ page }) => {
  const orig = readProductionNo().raw;
  try {
    // credOf('SALES_MANAGER') 在没有 PW_USER_SMGR 时会抛「测试环境缺陷」。
    // 🚫 **刻意不 catch 成 skip** —— skip 掉的角色断言会以「全部通过」的样子混过去
    //    （testing.md §3：断言从未执行 = 假绿）。AC-6b 点名了四个角色，缺一条就是缺一条。
    await page.context().clearCookies();
    await loginAs(page, 'SALES_MANAGER');
    await gotoProductHub(page);
    await switchTab(page, '销售产品');

    const val = 'TEST-SMGR-001';
    const r = await editProductionNoViaUi(page, HERO_EDIT, val);
    expect(r.failedResponses, `AC-6b：SALES_MANAGER 保存不得 4xx/5xx：${JSON.stringify(r.failedResponses)}`).toEqual([]);
    expect(readProductionNo().raw, `AC-6b：SALES_MANAGER 保存后库值应为 ${val}`).toBe(val);
    evidence('AC06b-sales-manager', `SALES_MANAGER 保存 ✅ 库值=${val}`);
  } finally {
    restoreProductionNo(orig);
  }
});

// ═══════════════════════════════════════════════════════════════════════════
//  第四组：接口层（AC-10 / AC-11 / AC-12 / AC-15）
//  📌 AC-11 / AC-15 **必须**在接口层验 —— 它们防的正是「UI 藏起来了但后端没拦住」
// ═══════════════════════════════════════════════════════════════════════════

test('A-01 / AC-10：PUT productionNo → 200 且只改目标列（source 不动）', async () => {
  const orig = readProductionNo().raw;
  const origSource = readColumn('source');
  const origName = readColumn('material_name');
  const ctx = await apiAs('SYSTEM_ADMIN');
  try {
    const res = await ctx.put(putPartUrl(HERO_EDIT), { data: { productionNo: NEW_PRODUCTION_NO } });
    const body = await res.text();
    console.log(`[A-01] PUT ${putPartUrl(HERO_EDIT)} → ${res.status()}  body=${body.slice(0, 300)}`);
    expect(res.status(), `A-01：白名单内字段 productionNo 应被接受（200），body=${body.slice(0, 300)}`).toBe(200);

    expect(readProductionNo().raw, 'A-01：库中 production_no 应为新值').toBe(NEW_PRODUCTION_NO);
    expect(readColumn('source'),
      'A-01：PUT 只更新传入的列 —— source 是行级来源，不得被改成 MANUAL').toBe(origSource);
    expect(readColumn('material_name'),
      'A-01：PUT 只更新传入的列 —— 未传的 material_name 不得被动（整行回传型实现会踩到这条）').toBe(origName);

    evidence('A01-put-ok', `PUT → ${res.status()}\nbody=${body.slice(0, 500)}\n` +
      `production_no ${JSON.stringify(orig)} → ${JSON.stringify(readProductionNo().raw)}\n` +
      `source=${JSON.stringify(readColumn('source'))}（改前 ${JSON.stringify(origSource)}）\n` +
      `material_name=${JSON.stringify(readColumn('material_name'))}（改前 ${JSON.stringify(origName)}）`);
  } finally {
    restoreProductionNo(orig);
    await ctx.dispose();
  }
});

test('A-02 / AC-11：非白名单字段 → 400，且库中 material_name 未被改', async () => {
  const origName = readColumn('material_name');
  const origPn = readProductionNo().raw;
  const ctx = await apiAs('SYSTEM_ADMIN');
  try {
    const res = await ctx.put(putPartUrl(HERO_EDIT), { data: NON_WHITELIST_FIELD });
    const body = await res.text();
    console.log(`[A-02] PUT 非白名单字段 → ${res.status()}  body=${body.slice(0, 300)}`);
    expect(res.status(),
      `AC-11：不在白名单的字段（${JSON.stringify(NON_WHITELIST_FIELD)}）必须返回 400。\n` +
      `  🚩 白名单在**后端**（Registry 的 ColumnDef.editable）—— 绕过 UI 直接打接口也必须被拦住。\n` +
      `  只靠「前端没渲染输入框」不算达成。实际 ${res.status()}，body=${body.slice(0, 300)}`).toBe(400);

    expect(readColumn('material_name'),
      `AC-11：被拒之后库中 material_name 必须保持 ${JSON.stringify(origName)}，` +
      `实际 ${JSON.stringify(readColumn('material_name'))}`).toBe(origName);
    expect(readProductionNo().raw, 'AC-11：被拒之后 production_no 也不得被顺带改动').toBe(origPn);

    evidence('A02-whitelist-400', `PUT ${JSON.stringify(NON_WHITELIST_FIELD)} → ${res.status()}\n` +
      `body=${body.slice(0, 500)}\nmaterial_name 改前=${JSON.stringify(origName)} 改后=${JSON.stringify(readColumn('material_name'))}`);
  } finally {
    await ctx.dispose();
  }
});

test('A-03 / AC-12：PUT productionNo=null → 落库 NULL（不是空字符串）', async () => {
  const orig = readProductionNo().raw;
  const ctx = await apiAs('SYSTEM_ADMIN');
  try {
    const res = await ctx.put(putPartUrl(HERO_EDIT), { data: { productionNo: null } });
    const body = await res.text();
    console.log(`[A-03] PUT productionNo=null → ${res.status()}  body=${body.slice(0, 300)}`);
    expect(res.status(), `AC-12：清空是合法操作（用户原话「生产料号选填」），应 200。body=${body.slice(0, 300)}`).toBe(200);

    const db = readProductionNo();
    expect(db.isNull,
      `AC-12：传 null 后库中必须是 NULL，实际 isNull=${db.isNull} isEmptyString=${db.isEmptyString} ` +
      `raw=${JSON.stringify(db.raw)}。\n` +
      `  ⚠️ 落成空字符串会让 AC-9 的 COALESCE 失效（COALESCE 认 NULL 不认空串）—— ` +
      `症状是「页面清空后，下次导入不但没保留，反而写进了空串」。`).toBe(true);

    // 📌 空串与 null 的行为差异需与对方实测对齐（api.md C-1 第 5 条：「联调时实测确认，不猜」）
    const res2 = await ctx.put(putPartUrl(HERO_EDIT), { data: { productionNo: '' } });
    const db2 = readProductionNo();
    console.log(`[A-03] PUT productionNo='' → ${res2.status()}  库中 isNull=${db2.isNull} isEmptyString=${db2.isEmptyString}`);
    evidence('A03-clear-null',
      `PUT null → ${res.status()}  库中 isNull=${db.isNull}\n` +
      `PUT ''   → ${res2.status()}  库中 isNull=${db2.isNull} isEmptyString=${db2.isEmptyString}\n` +
      `📌 空串行为为实测记录，供与 task-260902 对齐（api.md C-1 第 5 条）`);
    expect(db2.isEmptyString,
      `AC-12：传空串也不应落成**空字符串**（NULL 与空串必须收敛成 NULL，` +
      `否则库里会同时存在两种「没有值」的表示，下游 COALESCE / IS NULL 判断全部要各写两遍）`).toBe(false);
  } finally {
    restoreProductionNo(orig);
    await ctx.dispose();
  }
});

test('A-04 / AC-15：未登录 → 401；SALES_REP → 保存成功（接口层复核 AC-6b）', async () => {
  const orig = readProductionNo().raw;

  // ── ① 未登录 ──
  const anon = await anonymousApi();
  const res401 = await anon.put(putPartUrl(HERO_EDIT), { data: { productionNo: 'SHOULD-NOT-LAND' } });
  const body401 = await res401.text();
  console.log(`[A-04] 匿名 PUT → ${res401.status()}  body=${body401.slice(0, 200)}`);
  expect(res401.status(), `AC-15：未登录调用写端点必须 401，实际 ${res401.status()}`).toBe(401);
  expect(readProductionNo().raw,
    `AC-15：匿名请求被拒后库值不得变化（若变了说明鉴权只挡了响应没挡住副作用）`).toBe(orig);
  await anon.dispose();

  // ── ② SALES_REP（用户裁决四角色都能改 ⇒ 这里断言**成功**，不是 403）──
  const ctx = await apiAs('SALES_REP');
  try {
    const val = 'TEST-SALESREP-API';
    const res = await ctx.put(putPartUrl(HERO_EDIT), { data: { productionNo: val } });
    const body = await res.text();
    console.log(`[A-04] SALES_REP PUT → ${res.status()}  body=${body.slice(0, 300)}`);
    expect(res.status(),
      `AC-15：SALES_REP 应能保存成功（用户 2026-09-03 裁决四角色都能改，与 task-260902 原建议相反）。\n` +
      `  🚩 得到 403 ⇒ **后端权限注解没跟上 UI**：前端放开了、接口没放开，` +
      `用户点保存会看到失败。实际 ${res.status()} body=${body.slice(0, 300)}`).toBe(200);
    expect(readProductionNo().raw, 'AC-15：SALES_REP 保存后库值应为新值').toBe(val);

    evidence('A04-auth', `匿名 PUT → ${res401.status()}（期望 401）\n` +
      `SALES_REP PUT → ${res.status()}（期望 200）\n库值=${JSON.stringify(readProductionNo().raw)}`);
  } finally {
    restoreProductionNo(orig);
    await ctx.dispose();
  }
});
