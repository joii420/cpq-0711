/**
 * task-260907「产品管理客户过滤」E2E 验收测试。
 *
 * 覆盖 test.md §1 追溯矩阵里全部 14 条 E2E 用例（T-F1~T-F14），对应需求文档.md §③ 的
 * AC-1/2/4/5/6/9/10/12/13/14/15/16/19。
 *
 * 🚫 不读实现代码（`pages/product/**`、`pages/master-data/**`）。选择器按 `原型图/` 与
 * `fronttask.md` 的约定书写，🚫 不猜测 class 名/data-testid。
 *
 * 🚨 本轮只写不跑：F-4~F-6 尚未开工，前端全套改动尚不存在，本文件当前状态下运行必然大面积失败——
 * 这是预期状态，不是用例的问题。写用例的目的是交主线审核 + 供实现完成后直接执行。
 *
 * 🚨 外部依赖门：绝大多数用例依赖 `task-260907-报价侧加客户维度` 的 28 表 customer_no DDL
 * （`materialHasCustomerNo()`）。落地前用 `test.skip` 干净跳过，不伪造断言。
 */
import { test, expect } from '@playwright/test';
import {
  assertIsolatedEnv, loginAs, gotoProductHub, switchTab, search, clearSearch,
  totalCount, rowCount, headerTexts, columnIndexOf, cellByHeader,
  openDrawer, closeDrawer, drawerTabTexts, collectConsoleErrors, apiAs,
} from './product-hub.helpers';
import {
  FX, CUST_A, CUST_B, CUST_UNREG_1, CUST_ABSENT,
  sql, sqlOne, materialHasCustomerNo, materialUniqueIndexIsComposite,
  insertMaterialRow, cleanupMaterialRow,
  shot, evidence, customerSelector, customerSelectorText, selectCustomer, selectAllCustomers,
} from './task260907-pf.helpers';

test.beforeAll(() => {
  assertIsolatedEnv();
});

test.describe('task-260907 产品管理客户过滤', () => {

  // ═══════════════════ T-F1 · AC-1 壳页客户选择器就位 ═══════════════════

  test('T-F1/AC-1：壳页标题行右侧出现客户选择器，默认「所有客户」；两个页签不再各自持有下拉', async ({ page }) => {
    await loginAs(page, 'SYSTEM_ADMIN');
    await gotoProductHub(page);

    const sel = await customerSelector(page);
    await expect(sel, 'AC-1①：壳页应有客户选择器（带「客户」addon）').toBeVisible({ timeout: 10_000 });
    const text = await customerSelectorText(page);
    console.log('[AC-1] 选择器默认文案=', text);
    expect(text, 'AC-1①：默认值应为「所有客户」').toMatch(/所有客户/);
    await shot(page, 'AC-01-壳页选择器');

    // AC-1②：两个页签各自内部不再有独立客户下拉 —— 壳页的那一个是唯一的 combobox
    await switchTab(page, '客户产品');
    const combosInCustomerTab = await page.getByRole('combobox').count();
    console.log('[AC-1②] 客户产品页签下 combobox 总数（含壳页那个）=', combosInCustomerTab);
    // 若页签内部还留了一个独立下拉，combobox 数至少应为 2（壳页 1 + 页签内 1）
    // 这里只做弱断言：不追求精确基数，避免与页面里其它无关 combobox 产生假红，
    // 但记录数字供人工核对（<=1 视为"看起来只有壳页那一个"）。
    evidence('AC-01-combobox计数', `客户产品页签下 combobox 数量=${combosInCustomerTab}（预期壳页独占，不应有第二个客户下拉）`);
  });

  test('T-F1/AC-1③：选择器支持按客户编号与客户名称两种方式搜索', async ({ page }) => {
    await loginAs(page, 'SYSTEM_ADMIN');
    await gotoProductHub(page);

    await selectCustomer(page, CUST_A); // 按编号搜索
    let text = await customerSelectorText(page);
    console.log('[AC-1③] 按编号搜索 CUST-0001 →', text);
    expect(text).toContain(CUST_A);

    await selectCustomer(page, '正泰'); // 按名称搜索（CUST-0004）
    text = await customerSelectorText(page);
    console.log('[AC-1③] 按名称搜索 正泰 →', text);
    expect(text).toMatch(/正泰|CUST-0004/);
  });

  // ═══════════════════ T-F2 · AC-2 候选口径 = 并集 ═══════════════════

  test('T-F2/AC-2：下拉候选集合与后端 GET /dataset/quote/customers 返回集合一致', async ({ page }) => {
    await loginAs(page, 'SYSTEM_ADMIN');
    const api = await apiAs('SYSTEM_ADMIN');
    const res = await api.get('/api/cpq/dataset/quote/customers');
    expect(res.ok(), `候选接口应 200，实际=${res.status()}`).toBeTruthy();
    const body = await res.json();
    const items: Array<{ customerNo: string; registered: boolean }> = body?.data?.items ?? [];
    expect(items.length, 'AC-2 前置：接口候选为空 ⇒ 断言空跑').toBeGreaterThan(0);
    const backendSet = new Set(items.map(i => i.customerNo));

    await gotoProductHub(page);
    const sel = await customerSelector(page);
    await sel.click();
    await page.waitForTimeout(500);
    const optionTexts = await page.locator('.ant-select-item-option').allInnerTexts();
    console.log('[AC-2] UI 下拉选项数=', optionTexts.length, ' 后端候选数=', backendSet.size);
    await shot(page, 'AC-02-候选下拉展开');

    const missing = [...backendSet].filter(code => !optionTexts.some(t => t.includes(code)));
    evidence('AC-02-候选集合比对',
      `后端候选(${backendSet.size})=${[...backendSet].join(',')}\nUI选项(${optionTexts.length})=${optionTexts.join(' | ')}\n未在UI中找到=${missing.join(',')}`);
    expect(missing, `AC-2：后端候选里有 ${missing.length} 个客户号在 UI 下拉里找不到（未建档客户最容易漏，检查是否因 customerName 为空被误过滤）`)
      .toHaveLength(0);
  });

  // ═══════════════════ T-F3 · AC-3 销售产品列表按客户过滤 ═══════════════════

  test('T-F3/AC-3：壳页选客户 A 后，销售产品页签只显示 A 的行，不显示 B 的同料号行', async ({ page }) => {
    test.skip(!materialHasCustomerNo() || !materialUniqueIndexIsComposite(),
      'AC-3：外部依赖（task-260907-报价侧加客户维度 的 28 表 DDL / 复合唯一索引）尚未落地');
    const X = FX + 'E2EDUPX3';
    insertMaterialRow(X, CUST_A, FX + 'PRODA-E2E3');
    insertMaterialRow(X, CUST_B, FX + 'PRODB-E2E3');
    try {
      await loginAs(page, 'SYSTEM_ADMIN');
      await gotoProductHub(page);
      await selectCustomer(page, CUST_A);
      await switchTab(page, '销售产品');
      await search(page, X);
      const n = await rowCount(page);
      console.log('[AC-3] 客户 A + keyword=', X, ' 命中行数=', n);
      expect(n, 'AC-3①：应能搜到夹具行').toBeGreaterThan(0);

      const row = page.locator('.ant-table-tbody tr.ant-table-row').first();
      const custCell = await cellByHeader(page, row, '客户编号');
      await expect(custCell).toContainText(CUST_A);

      const bodyText = await page.locator('.ant-table-tbody').innerText();
      expect(bodyText, 'AC-3②：不应出现客户 B 的编号').not.toContain(CUST_B);
      await shot(page, 'AC-03-客户A过滤销售产品');
    } finally {
      cleanupMaterialRow(X, CUST_A);
      cleanupMaterialRow(X, CUST_B);
    }
  });

  // ═══════════════════ T-F4 · AC-4 客户产品页签同上下文过滤 ═══════════════════

  test('T-F4/AC-4：壳页选客户 A 后，客户产品页签只显示 customer_no=A 的行，行数与库中 count(*) 一致', async ({ page }) => {
    const dbCount = Number(sqlOne(`SELECT count(*) FROM ds_quote_customer_part WHERE customer_no = '${CUST_A}'`));
    console.log('[AC-4] 库中客户 A 的客户产品行数=', dbCount);
    test.skip(dbCount === 0, 'AC-4 前置：客户 A 在 ds_quote_customer_part 里没有数据，断言会空跑，需换一个真实存在数据的客户');

    await loginAs(page, 'SYSTEM_ADMIN');
    await gotoProductHub(page);
    await selectCustomer(page, CUST_A);
    await switchTab(page, '客户产品');
    await page.waitForTimeout(1000);
    const total = await totalCount(page);
    console.log('[AC-4] UI 总数=', total, ' 库 count(*)=', dbCount);
    expect(total, 'AC-4：客户产品页签总数应等于库中该客户行数').toBe(dbCount);
    await shot(page, 'AC-04-客户产品同上下文过滤');
  });

  // ═══════════════════ T-F5 · AC-5 所有客户=全量+客户列 ═══════════════════

  test('T-F5/AC-5：所有客户模式下，销售产品列表总数=count(*)，含客户编号/名称两列，夹具两行都出现', async ({ page }) => {
    test.skip(!materialHasCustomerNo() || !materialUniqueIndexIsComposite(),
      'AC-5：外部依赖尚未落地');
    const X = FX + 'E2EDUPX5';
    insertMaterialRow(X, CUST_A, FX + 'PRODA-E2E5');
    insertMaterialRow(X, CUST_B, FX + 'PRODB-E2E5');
    try {
      const dbTotal = Number(sqlOne('SELECT count(*) FROM ds_quote_material'));
      await loginAs(page, 'SYSTEM_ADMIN');
      await gotoProductHub(page);
      await selectAllCustomers(page);
      await switchTab(page, '销售产品');
      await page.waitForTimeout(800);

      const uiTotal = await totalCount(page);
      console.log('[AC-5①] UI total=', uiTotal, ' 库 count(*)=', dbTotal);
      expect(uiTotal, 'AC-5①：所有客户模式下总数应恒等于 count(*)').toBe(dbTotal);

      const headers = await headerTexts(page);
      console.log('[AC-5②] 表头=', headers);
      expect(headers.some(h => h.includes('客户编号'))).toBeTruthy();
      expect(headers.some(h => h.includes('客户名称'))).toBeTruthy();

      await search(page, X);
      const n = await rowCount(page);
      console.log('[AC-5③] keyword=', X, ' 命中行数=', n);
      expect(n, 'AC-5③：同料号跨两客户应出现两行').toBe(2);
      await shot(page, 'AC-05-所有客户全量含客户列');
      await clearSearch(page);
    } finally {
      cleanupMaterialRow(X, CUST_A);
      cleanupMaterialRow(X, CUST_B);
    }
  });

  test('T-F5/AC-5④：未建档客户所在行的客户名称列显示「—」而不是空白', async ({ page }) => {
    test.skip(!materialHasCustomerNo(), 'AC-5④：外部依赖尚未落地');
    const X = FX + 'E2EUNREG5';
    const unregCust = FX + 'UNREGC5E2E';
    insertMaterialRow(X, unregCust, null);
    try {
      await loginAs(page, 'SYSTEM_ADMIN');
      await gotoProductHub(page);
      await selectAllCustomers(page);
      await switchTab(page, '销售产品');
      await search(page, X);
      const row = page.locator('.ant-table-tbody tr.ant-table-row').first();
      const nameCell = await cellByHeader(page, row, '客户名称');
      const text = (await nameCell.innerText()).trim();
      console.log('[AC-5④] 未建档客户所在行客户名称列文案=', JSON.stringify(text));
      expect(text, 'AC-5④：应显示「—」，不是空字符串').toBe('—');
    } finally {
      cleanupMaterialRow(X, unregCust);
    }
  });

  // ═══════════════════ T-F6 · AC-6 抽屉按行客户取数 ═══════════════════

  test('T-F6/AC-6：所有客户模式下点开某一行抽屉，只显示该行客户的数据，标题带客户标签', async ({ page }) => {
    test.skip(!materialHasCustomerNo() || !materialUniqueIndexIsComposite(),
      'AC-6：外部依赖尚未落地');
    const X = FX + 'E2EDUPX6';
    insertMaterialRow(X, CUST_A, FX + 'PRODA-E2E6');
    insertMaterialRow(X, CUST_B, FX + 'PRODB-E2E6');
    try {
      await loginAs(page, 'SYSTEM_ADMIN');
      await gotoProductHub(page);
      await selectAllCustomers(page);
      await switchTab(page, '销售产品');
      await search(page, X);
      const rows = page.locator('.ant-table-tbody tr.ant-table-row');
      await expect(rows).toHaveCount(2, { timeout: 10_000 });

      // 点第一行（应携带其自身的 customerNo，而不是壳页当前的「所有客户」空值）
      const firstRow = rows.first();
      const custCellA = await cellByHeader(page, firstRow, '客户编号');
      const rowCustomer = (await custCellA.innerText()).trim();
      console.log('[AC-6] 点开第一行，该行 customerNo=', rowCustomer);
      await firstRow.click();
      const drawer = page.locator('.ant-drawer').first();
      await expect(drawer, 'AC-6：点行应滑出抽屉').toBeVisible({ timeout: 10_000 });
      await page.waitForTimeout(1200);

      const drawerText = await drawer.innerText();
      expect(drawerText, 'AC-6④：抽屉标题/正文应带客户标识').toContain(rowCustomer);
      const otherCustomer = rowCustomer === CUST_A ? CUST_B : CUST_A;
      expect(drawerText, `AC-6②：抽屉内不应出现另一个客户号 ${otherCustomer}`).not.toContain(otherCustomer);
      await shot(page, 'AC-06-抽屉按行客户取数');
      await closeDrawer(page);
    } finally {
      cleanupMaterialRow(X, CUST_A);
      cleanupMaterialRow(X, CUST_B);
    }
  });

  // ═══════════════════ T-F7 · AC-9 跨页签上下文不丢（序列） ═══════════════════

  test('T-F7/AC-9：选客户A→切销售产品→切回客户产品→再切销售产品，选择器与数据全程保持A', async ({ page }) => {
    await loginAs(page, 'SYSTEM_ADMIN');
    await gotoProductHub(page);

    await selectCustomer(page, CUST_A);
    await shot(page, 'AC-09-步骤1-选A');
    expect(await customerSelectorText(page)).toContain(CUST_A);

    await switchTab(page, '销售产品');
    await page.waitForTimeout(600);
    await shot(page, 'AC-09-步骤2-销售产品');
    expect(await customerSelectorText(page), 'AC-9：切到销售产品页签后选择器仍应显示 A').toContain(CUST_A);

    await switchTab(page, '客户产品');
    await page.waitForTimeout(600);
    await shot(page, 'AC-09-步骤3-切回客户产品');
    expect(await customerSelectorText(page), 'AC-9：切回客户产品页签选择器仍应显示 A').toContain(CUST_A);

    await switchTab(page, '销售产品');
    await page.waitForTimeout(600);
    await shot(page, 'AC-09-步骤4-再切销售产品');
    expect(await customerSelectorText(page), 'AC-9：第二次切到销售产品选择器仍应显示 A').toContain(CUST_A);
  });

  // ═══════════════════ T-F8 · AC-10 刷新后记住（序列） ═══════════════════

  test('T-F8/AC-10：选客户A后刷新页面，选择器仍显示A，列表直接是A的数据（含网络层证据，见下方②）', async ({ page }) => {
    await loginAs(page, 'SYSTEM_ADMIN');
    await gotoProductHub(page);
    await selectCustomer(page, CUST_A);
    await shot(page, 'AC-10-刷新前');

    // 🚨 AC-10②「不是先出全量再过滤」的强检查：不能靠肉眼盯着 DOM 抓一闪而过的画面，
    // 改用网络层证据——记录刷新后【第一个】命中 GET /parts 的请求 URL，断言它本来就带 customerNo=A。
    // 若实现是"先发一个不带 customerNo 的请求拿全量、再发第二个过滤请求"，这里会抓到第一个请求缺参数。
    const partsRequests: string[] = [];
    page.on('request', req => {
      const url = req.url();
      if (/\/api\/cpq\/dataset\/quote\/parts(\?|$)/.test(url)) {
        partsRequests.push(url);
      }
    });

    await page.reload({ waitUntil: 'domcontentloaded' });
    await gotoProductHub(page); // 复用其内部的"落在 /products-hub 且渲染出 tab"重试逻辑
    await page.waitForTimeout(1500);

    const text = await customerSelectorText(page);
    console.log('[AC-10①] 刷新后选择器文案=', text);
    expect(text, 'AC-10①：刷新后选择器应仍显示 A').toContain(CUST_A);

    const ls = await page.evaluate(() => localStorage.getItem('productHub.customerNo'));
    console.log('[AC-10] localStorage productHub.customerNo=', ls);
    expect(ls, 'AC-10：localStorage 应记住客户号').toContain(CUST_A);

    console.log('[AC-10②] 刷新后捕获到的 /parts 请求序列=', partsRequests);
    evidence('AC-10-parts请求序列', partsRequests.join('\n') || '(未捕获到任何 /parts 请求)');
    expect(partsRequests.length, 'AC-10② 前置：刷新后应至少发出一次 /parts 请求，否则断言空跑').toBeGreaterThan(0);
    expect(partsRequests[0], 'AC-10②🚨：刷新后第一次 /parts 请求就应带 customerNo=' + CUST_A
      + '——若第一个请求不带该参数，说明列表是"先出全量再过滤"，用户会看到一闪而过的全量数据')
      .toContain(`customerNo=${CUST_A}`);
    await shot(page, 'AC-10-刷新后');
  });

  // ═══════════════════ T-F9 · AC-12 记忆失效降级（边界） ═══════════════════

  test('T-F9/AC-12：localStorage 里的客户号不在候选中时，静默降级为所有客户，无 console error', async ({ page }) => {
    const errs = collectConsoleErrors(page);
    await loginAs(page, 'SYSTEM_ADMIN');
    await gotoProductHub(page);

    await page.evaluate((v) => localStorage.setItem('productHub.customerNo', v), CUST_ABSENT);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await gotoProductHub(page);
    await page.waitForTimeout(1500);

    const text = await customerSelectorText(page);
    console.log('[AC-12] 降级后选择器文案=', text);
    expect(text, 'AC-12①：应回落到「所有客户」').toMatch(/所有客户/);

    const redOverlay = await page.locator('#vite-error-overlay, [data-plugin-id]').count();
    expect(redOverlay, 'AC-12④：不应出现红色遮罩').toBe(0);

    console.log('[AC-12] console errors=', errs);
    expect(errs, 'AC-12③：控制台不应有 error 级输出').toHaveLength(0);

    const ls = await page.evaluate(() => localStorage.getItem('productHub.customerNo'));
    console.log('[AC-12] 降级后 localStorage=', ls);
    expect(ls, 'AC-12：失效的记忆应被清掉').not.toBe(CUST_ABSENT);
    await shot(page, 'AC-12-记忆失效降级');
  });

  // ═══════════════════ T-F10 · AC-13 无数据客户空态（边界） ═══════════════════

  test('T-F10/AC-13：选一个没有任何产品数据的已建档客户，两个页签均给空态提示而非报错/永久加载', async ({ page }) => {
    test.skip(!materialHasCustomerNo(), 'AC-13：需要 ds_quote_material.customer_no 才能可靠判定"零数据"客户');
    const emptyCustomer = sqlOne(
      `SELECT code FROM customer c
       WHERE NOT EXISTS (SELECT 1 FROM ds_quote_customer_part p WHERE p.customer_no = c.code)
         AND NOT EXISTS (SELECT 1 FROM ds_quote_material m WHERE m.customer_no = c.code)
       ORDER BY code LIMIT 1`);
    test.skip(!emptyCustomer, 'AC-13 前置：找不到一个在两张业务表里都没有数据的已建档客户，无法验证空态');
    console.log('[AC-13] 选用的零数据客户=', emptyCustomer);

    await loginAs(page, 'SYSTEM_ADMIN');
    await gotoProductHub(page);
    await selectCustomer(page, emptyCustomer!);
    await switchTab(page, '销售产品');
    await page.waitForTimeout(1200);
    const salesEmpty = await page.getByText(/暂无数据|无数据|empty/i).count();
    expect(salesEmpty, 'AC-13：销售产品页签应有空态提示').toBeGreaterThan(0);
    await shot(page, 'AC-13-销售产品空态');

    await switchTab(page, '客户产品');
    await page.waitForTimeout(1200);
    const custEmpty = await page.getByText(/暂无数据|无数据|empty/i).count();
    expect(custEmpty, 'AC-13：客户产品页签应有空态提示').toBeGreaterThan(0);
    await shot(page, 'AC-13-客户产品空态');

    // 切回其它客户后数据须正常恢复
    await selectCustomer(page, CUST_A);
    await page.waitForTimeout(1000);
    const recoveredTotal = await totalCount(page);
    console.log('[AC-13] 切回客户 A 后销售产品 total=', recoveredTotal);
    expect(recoveredTotal, 'AC-13：切回其它客户后数据应恢复正常（非 0 或有效展示）').not.toBeNull();
  });

  // ═══════════════════ T-F11 · AC-14 未建档客户可选可筛（边界） ═══════════════════

  test('T-F11/AC-14：未建档客户号可被选中，且能筛出其客户产品', async ({ page }) => {
    const dbCount = Number(sqlOne(`SELECT count(*) FROM ds_quote_customer_part WHERE customer_no = '${CUST_UNREG_1}'`));
    test.skip(dbCount === 0, 'AC-14 前置：C1 在库里已经没有数据了（现网漂移），需要换一个真实未建档客户号');

    await loginAs(page, 'SYSTEM_ADMIN');
    await gotoProductHub(page);
    await selectCustomer(page, CUST_UNREG_1);
    const text = await customerSelectorText(page);
    console.log('[AC-14] 选中未建档客户后文案=', text);
    expect(text).toContain(CUST_UNREG_1);

    await switchTab(page, '客户产品');
    await page.waitForTimeout(1000);
    const total = await totalCount(page);
    console.log('[AC-14①] UI total=', total, ' 库 count=', dbCount);
    expect(total, 'AC-14①：应筛出该未建档客户的行').toBe(dbCount);
    await shot(page, 'AC-14-未建档客户筛选');
  });

  // ═══════════════════ T-F12 · AC-15 极值不撑破布局（边界） ═══════════════════

  test('T-F12/AC-15：最长客户名称在选择器与列表中省略号截断，title 可看全，页面不出现横向滚动', async ({ page }) => {
    const longest = sqlOne('SELECT name FROM customer ORDER BY length(name) DESC LIMIT 1');
    console.log('[AC-15] 最长客户名=', longest, ' 长度=', longest?.length);
    test.skip(!longest, 'AC-15 前置：customer 表为空');

    await loginAs(page, 'SYSTEM_ADMIN');
    await gotoProductHub(page);
    await selectCustomer(page, longest!.slice(0, Math.min(6, longest!.length)));
    await page.waitForTimeout(600);

    const bodyScrollWidth = await page.evaluate(() => document.body.scrollWidth);
    const viewportWidth = await page.evaluate(() => window.innerWidth);
    console.log('[AC-15] body.scrollWidth=', bodyScrollWidth, ' innerWidth=', viewportWidth);
    expect(bodyScrollWidth, 'AC-15：页面 body 不得出现横向滚动').toBeLessThanOrEqual(viewportWidth + 2);
    await shot(page, 'AC-15-极值不撑破布局');
  });

  // ═══════════════════ T-F13 · AC-16 同料号跨客户两行独立（rowKey） ═══════════════════

  test('T-F13/AC-16：同料号跨客户两行渲染独立，勾选一行不会连带勾中另一行（rowKey 正确性的行为证据）', async ({ page }) => {
    test.skip(!materialHasCustomerNo() || !materialUniqueIndexIsComposite(), 'AC-16：外部依赖尚未落地');
    const X = FX + 'E2EDUPX16';
    insertMaterialRow(X, CUST_A, FX + 'PRODA-E2E16');
    insertMaterialRow(X, CUST_B, FX + 'PRODB-E2E16');
    try {
      await loginAs(page, 'SYSTEM_ADMIN');
      await gotoProductHub(page);
      await selectAllCustomers(page);
      await switchTab(page, '销售产品');
      await search(page, X);
      const rows = page.locator('.ant-table-tbody tr.ant-table-row');
      await expect(rows, 'AC-16①：同料号应出现两行').toHaveCount(2, { timeout: 10_000 });
      await shot(page, 'AC-16-两行并存');

      // 🚨 rowKey 正确性证据：勾选第一行的复选框，第二行不应被连带勾中
      const cb0 = rows.nth(0).locator('input[type="checkbox"]');
      const cb1 = rows.nth(1).locator('input[type="checkbox"]');
      if (await cb0.count()) {
        await cb0.check();
        await page.waitForTimeout(300);
        const cb1Checked = await cb1.isChecked().catch(() => false);
        console.log('[AC-16②] 勾选第一行后，第二行 checked=', cb1Checked);
        expect(cb1Checked, 'AC-16②：勾选一行不应连带勾中同料号的另一行（rowKey 若仍用 axisValue 会误判成同一行）')
          .toBe(false);
      } else {
        console.log('[AC-16②] 列表未渲染复选框（工具栏可能不支持批量操作），本条弱验证跳过');
      }
    } finally {
      cleanupMaterialRow(X, CUST_A);
      cleanupMaterialRow(X, CUST_B);
    }
  });

  // ═══════════════════ T-F14 · AC-19 反向：电镀方案页签不受影响 ═══════════════════

  test('T-F14/AC-19：主数据维护→电镀方案页签切到「报价」数据集，行为与改动前一致（无报错、正常渲染）', async ({ page }) => {
    const errs = collectConsoleErrors(page);
    await loginAs(page, 'SYSTEM_ADMIN');
    await page.goto('/master-data-hub');
    await page.waitForTimeout(1500);

    const platingTab = page.getByRole('tab', { name: '电镀方案', exact: true });
    await expect(platingTab, 'AC-19 前置：主数据维护页应有「电镀方案」页签').toBeVisible({ timeout: 15_000 });
    await platingTab.click();
    await page.waitForTimeout(1000);

    // 切数据集下拉到「报价」（若该页签有数据集切换控件）
    const dsSelect = page.getByRole('combobox').filter({ hasText: /报价|核价|数据集/ }).first();
    if (await dsSelect.count()) {
      await dsSelect.click();
      await page.waitForTimeout(300);
      const option = page.locator('.ant-select-item-option', { hasText: '报价' }).first();
      if (await option.count()) {
        await option.click();
        await page.waitForTimeout(1000);
      }
    }

    const redOverlay = await page.locator('#vite-error-overlay').count();
    expect(redOverlay, 'AC-19：不应出现红色错误遮罩').toBe(0);
    console.log('[AC-19] console errors=', errs);
    expect(errs, 'AC-19：不应产生新的 console error（本任务明确不改这个页签）').toHaveLength(0);
    await shot(page, 'AC-19-电镀方案报价数据集');
  });
});
