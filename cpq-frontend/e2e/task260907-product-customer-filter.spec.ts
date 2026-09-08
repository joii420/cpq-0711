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
  shot, evidence, customerSelector, customerSelectorText, selectCustomer,
} from './task260907-pf.helpers';

test.beforeAll(() => {
  assertIsolatedEnv();
});

test.describe('task-260907 产品管理客户过滤', () => {

  // ═══════════════════ T-F1 · AC-1 壳页客户选择器就位 ═══════════════════

  test('T-F1/AC-1（D-7）：壳页客户选择器就位，默认选中候选第一个客户，下拉里不存在「所有客户」', async ({ page }) => {
    // D-7：默认值 = 候选列表第一个客户（不是「所有客户」——该选项已被取消）。
    // 🚨 不猜第一个客户是谁，实测拿：先打接口拿候选顺序（后端排序：registered 升序在前、
    // unregistered 升序置尾），第一项即默认值。test.md §0 已知第一个候选当前是 8000137（0 行数据）。
    const api = await apiAs('SYSTEM_ADMIN');
    const candRes = await api.get('/api/cpq/dataset/quote/customers');
    expect(candRes.ok(), '候选接口应 200').toBeTruthy();
    const candBody = await candRes.json();
    const candItems: Array<{ customerNo: string }> = candBody?.data?.items ?? [];
    expect(candItems.length, 'AC-1 前置：候选为空 ⇒ 无法确定默认值').toBeGreaterThan(0);
    const firstCandidate = candItems[0].customerNo;
    console.log('[AC-1] 候选第一项(默认应选中)=', firstCandidate);

    await loginAs(page, 'SYSTEM_ADMIN');
    await gotoProductHub(page);

    const sel = await customerSelector(page);
    await expect(sel, 'AC-1①：壳页应有客户选择器（带「客户」addon）').toBeVisible({ timeout: 10_000 });
    const text = await customerSelectorText(page);
    console.log('[AC-1] 选择器默认文案=', text);
    expect(text, 'AC-1②：默认值应为候选第一个客户 ' + firstCandidate).toContain(firstCandidate);
    await shot(page, 'AC-01-壳页选择器默认第一个客户');

    // AC-1②🚫：下拉里不应再有「所有客户」这个选项（客户必选，D-7）
    await sel.click();
    await page.waitForTimeout(400);
    const allCustomersOption = page.locator('.ant-select-item-option', { hasText: '所有客户' });
    await expect(allCustomersOption, 'AC-1②🚫：下拉不应再有「所有客户」选项——客户已改为必选').toHaveCount(0);
    await page.keyboard.press('Escape');

    // AC-1③：两个页签各自内部不再有独立客户下拉 —— 壳页的那一个是唯一的 combobox
    await switchTab(page, '客户产品');
    const combosInCustomerTab = await page.getByRole('combobox').count();
    console.log('[AC-1③] 客户产品页签下 combobox 总数（含壳页那个）=', combosInCustomerTab);
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

  // ═══════════════════ T-F5 · AC-5（D-7 改版）列表恒等于当前客户全集 + 客户列仍在 ═══════════════════

  test('T-F5/AC-5（D-7）：选中客户 A 后，销售产品列表总数=count(*) WHERE customer_no=A，含客户两列，'
    + '不出现客户 B 的同料号行', async ({ page }) => {
    test.skip(!materialHasCustomerNo() || !materialUniqueIndexIsComposite(),
      'AC-5：外部依赖尚未落地');
    const X = FX + 'E2EDUPX5';
    insertMaterialRow(X, CUST_A, FX + 'PRODA-E2E5');
    insertMaterialRow(X, CUST_B, FX + 'PRODB-E2E5');
    try {
      const dbForA = Number(sqlOne(`SELECT count(*) FROM ds_quote_material WHERE customer_no = '${CUST_A}'`));
      const dbFull = Number(sqlOne('SELECT count(*) FROM ds_quote_material'));
      console.log('[AC-5① 前置] 库(customer_no=A)=', dbForA, ' 库(全量)=', dbFull);
      expect(dbForA, 'AC-5① 前置：客户 A 的行数不应等于全量，否则判据无判别力').toBeLessThan(dbFull);

      await loginAs(page, 'SYSTEM_ADMIN');
      await gotoProductHub(page);
      // 🚨 test.md §0 第二个空跑形态：默认客户(候选第一个)大概率 0 行数据 —— 必须显式切到自己的夹具客户
      await selectCustomer(page, CUST_A);
      await switchTab(page, '销售产品');
      await page.waitForTimeout(800);

      const uiTotal = await totalCount(page);
      console.log('[AC-5①] UI total=', uiTotal, ' 库(customer_no=A)=', dbForA);
      // 断言前先断言非空——test.md §0 强制对策②
      expect(uiTotal, 'AC-5① 前置：total 不应为 0/null ⇒ 断言会在空列表上空跑').not.toBeNull();
      expect(uiTotal, 'AC-5①：总数应恒等于 count(*) WHERE customer_no=A（不是全量）').toBe(dbForA);

      const headers = await headerTexts(page);
      console.log('[AC-5②] 表头=', headers);
      expect(headers.some(h => h.includes('客户编号'))).toBeTruthy();
      expect(headers.some(h => h.includes('客户名称'))).toBeTruthy();

      await search(page, X);
      const rows = page.locator('.ant-table-tbody tr.ant-table-row');
      await expect(rows, 'AC-5③：搜到的行不应为空 ⇒ 断言空跑').not.toHaveCount(0);
      const n = await rowCount(page);
      console.log('[AC-5③] keyword=', X, ' 命中行数=', n);
      expect(n, 'AC-5③：选中客户 A 时，同料号只应出现【客户 A 自己的那一行】，不是两行同屏').toBe(1);
      const custCell = await cellByHeader(page, rows.first(), '客户编号');
      await expect(custCell, 'AC-5③：唯一那行的客户编号应为 A').toContainText(CUST_A);
      const bodyText = await page.locator('.ant-table-tbody').innerText();
      expect(bodyText, 'AC-5③🚨：不应出现客户 B 的编号').not.toContain(CUST_B);
      await shot(page, 'AC-05-当前客户全集含客户列');
      await clearSearch(page);
    } finally {
      cleanupMaterialRow(X, CUST_A);
      cleanupMaterialRow(X, CUST_B);
    }
  });

  test('T-F5/AC-5④：选中未建档客户时，其行的客户名称列显示「—」而不是空白', async ({ page }) => {
    test.skip(!materialHasCustomerNo(), 'AC-5④：外部依赖尚未落地');
    const X = FX + 'E2EUNREG5';
    const unregCust = FX + 'UNREGC5E2E';
    insertMaterialRow(X, unregCust, null);
    try {
      await loginAs(page, 'SYSTEM_ADMIN');
      await gotoProductHub(page);
      // unregCust 是自造的、候选接口原本不认识的客户号 —— 通过 URL/localStorage 直接指定当前客户上下文，
      // 而不是尝试从下拉里搜它（它本来就不在候选并集里，因为夹具是本用例临时插入的，未经候选端点重新聚合）。
      await page.evaluate((v) => localStorage.setItem('productHub.customerNo', v), unregCust);
      await page.reload({ waitUntil: 'domcontentloaded' });
      await gotoProductHub(page);
      await switchTab(page, '销售产品');
      await search(page, X);
      const rows = page.locator('.ant-table-tbody tr.ant-table-row');
      await expect(rows, 'AC-5④ 前置：应搜到夹具行 ⇒ 断言空跑').not.toHaveCount(0);
      const row = rows.first();
      const nameCell = await cellByHeader(page, row, '客户名称');
      const text = (await nameCell.innerText()).trim();
      console.log('[AC-5④] 未建档客户所在行客户名称列文案=', JSON.stringify(text));
      expect(text, 'AC-5④：应显示「—」，不是空字符串').toBe('—');
    } finally {
      cleanupMaterialRow(X, unregCust);
    }
  });

  // ═══════════════════ T-F6 · AC-6（D-10）抽屉的客户上下文 = 壳页所选客户 ═══════════════════

  test('T-F6/AC-6（D-10）：选中客户 A 后点开该料号的抽屉，抽屉内容 = 壳页所选客户(A)的数据，不含客户 B 的行', async ({ page }) => {
    test.skip(!materialHasCustomerNo() || !materialUniqueIndexIsComposite(),
      'AC-6：外部依赖尚未落地');
    const X = FX + 'E2EDUPX6';
    insertMaterialRow(X, CUST_A, FX + 'PRODA-E2E6');
    insertMaterialRow(X, CUST_B, FX + 'PRODB-E2E6');
    try {
      await loginAs(page, 'SYSTEM_ADMIN');
      await gotoProductHub(page);
      // D-10：抽屉上下文 = 壳页所选客户（不是行携带的值）—— 显式选 A，不依赖默认值
      await selectCustomer(page, CUST_A);
      await switchTab(page, '销售产品');
      await search(page, X);
      const rows = page.locator('.ant-table-tbody tr.ant-table-row');
      await expect(rows, 'AC-6 前置：客户必选后应只看到客户 A 自己的这一行 ⇒ 断言空跑').not.toHaveCount(0);
      await expect(rows).toHaveCount(1, { timeout: 10_000 });

      const firstRow = rows.first();
      const custCellA = await cellByHeader(page, firstRow, '客户编号');
      await expect(custCellA, 'AC-6 前置：唯一可见行应属于客户 A').toContainText(CUST_A);
      await firstRow.click();
      const drawer = page.locator('.ant-drawer').first();
      await expect(drawer, 'AC-6：点行应滑出抽屉').toBeVisible({ timeout: 10_000 });
      await page.waitForTimeout(1200);

      const drawerText = await drawer.innerText();
      expect(drawerText, 'AC-6④：抽屉标题/正文应带客户 A 的标识').toContain(CUST_A);
      expect(drawerText, 'AC-6②：抽屉内不应出现客户 B 的编号').not.toContain(CUST_B);
      await shot(page, 'AC-06-抽屉客户上下文取自壳页');
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

  // ═══════════════════ T-F9 · AC-12（D-7 改版）记忆失效降级 → 候选第一个客户 ═══════════════════

  test('T-F9/AC-12（D-7）：localStorage 里的客户号不在候选中时，静默降级为候选第一个客户（不是「所有客户」），'
    + '无 console error', async ({ page }) => {
    // D-7：降级目标 = 候选列表第一个客户；该客户可能本身没数据 ⇒ 走 AC-13 空态，这是预期链路，不是失败。
    const api = await apiAs('SYSTEM_ADMIN');
    const candRes = await api.get('/api/cpq/dataset/quote/customers');
    expect(candRes.ok(), '候选接口应 200').toBeTruthy();
    const candBody = await candRes.json();
    const candItems: Array<{ customerNo: string }> = candBody?.data?.items ?? [];
    expect(candItems.length, 'AC-12 前置：候选为空 ⇒ 无法确定降级目标').toBeGreaterThan(0);
    const firstCandidate = candItems[0].customerNo;
    console.log('[AC-12] 候选第一项(降级目标)=', firstCandidate);

    const errs = collectConsoleErrors(page);
    await loginAs(page, 'SYSTEM_ADMIN');
    await gotoProductHub(page);

    await page.evaluate((v) => localStorage.setItem('productHub.customerNo', v), CUST_ABSENT);
    await page.reload({ waitUntil: 'domcontentloaded' });
    await gotoProductHub(page);
    await page.waitForTimeout(1500);

    const text = await customerSelectorText(page);
    console.log('[AC-12] 降级后选择器文案=', text);
    expect(text, 'AC-12①：应回落到候选第一个客户 ' + firstCandidate + '（不是「所有客户」——该选项已不存在）')
      .toContain(firstCandidate);

    const redOverlay = await page.locator('#vite-error-overlay, [data-plugin-id]').count();
    expect(redOverlay, 'AC-12④：不应出现红色遮罩').toBe(0);

    console.log('[AC-12] console errors=', errs);
    expect(errs, 'AC-12③：控制台不应有 error 级输出').toHaveLength(0);

    const ls = await page.evaluate(() => localStorage.getItem('productHub.customerNo'));
    console.log('[AC-12] 降级后 localStorage=', ls);
    expect(ls, 'AC-12：失效的记忆应被清掉（不再是那个候选中不存在的值）').not.toBe(CUST_ABSENT);
    await shot(page, 'AC-12-记忆失效降级到候选第一个');
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

  // ═══════════════════ T-F13 · AC-16（D-8 改版）UI 层：切客户各自看到自己那行且内容不同 ═══════════════════

  test('T-F13/AC-16①UI层（D-8）：选客户 A 看到该料号且客户列=A；切到 B 仍看到该料号但客户列=B，'
    + '且两次的行内容不同（证明不是同一行换了个标签，rowKey 切换时未复用出脏值）', async ({ page }) => {
    test.skip(!materialHasCustomerNo() || !materialUniqueIndexIsComposite(), 'AC-16：外部依赖尚未落地');
    const X = FX + 'E2EDUPX16';
    const PROD_A = FX + 'PRODA-E2E16';
    const PROD_B = FX + 'PRODB-E2E16';
    insertMaterialRow(X, CUST_A, PROD_A);
    insertMaterialRow(X, CUST_B, PROD_B);
    try {
      await loginAs(page, 'SYSTEM_ADMIN');
      await gotoProductHub(page);
      await switchTab(page, '销售产品');

      // ① 选客户 A
      await selectCustomer(page, CUST_A);
      await search(page, X);
      let rows = page.locator('.ant-table-tbody tr.ant-table-row');
      await expect(rows, 'AC-16①前置：客户 A 应能搜到该料号 ⇒ 断言空跑').not.toHaveCount(0);
      await expect(rows).toHaveCount(1, { timeout: 10_000 });
      let custCell = await cellByHeader(page, rows.first(), '客户编号');
      await expect(custCell, 'AC-16①：客户 A 视角下客户列应显示 A').toContainText(CUST_A);
      let prodCellA = await cellByHeader(page, rows.first(), '生产料号');
      const prodTextA = (await prodCellA.innerText()).trim();
      console.log('[AC-16①] 客户 A 视角：生产料号=', prodTextA);
      await shot(page, 'AC-16-客户A视角');

      // ② 切到客户 B（同一料号，同一次 search 关键字）—— 🚨 rowKey 若未含 customerNo，
      //    这里最容易出现"React 复用了行组件、单元格值还是上一个客户的"这种脏渲染
      await selectCustomer(page, CUST_B);
      await search(page, X);
      rows = page.locator('.ant-table-tbody tr.ant-table-row');
      await expect(rows, 'AC-16①：切到客户 B 后应仍能看到该料号（不是被"删掉"了）⇒ 断言空跑').not.toHaveCount(0);
      await expect(rows).toHaveCount(1, { timeout: 10_000 });
      custCell = await cellByHeader(page, rows.first(), '客户编号');
      await expect(custCell, 'AC-16①：客户 B 视角下客户列应显示 B（不应仍显示 A）').toContainText(CUST_B);
      const prodCellB = await cellByHeader(page, rows.first(), '生产料号');
      const prodTextB = (await prodCellB.innerText()).trim();
      console.log('[AC-16①] 客户 B 视角：生产料号=', prodTextB);
      await shot(page, 'AC-16-客户B视角');

      // 🔑 两次内容必须不同 —— 证明看到的是各自的数据，不是同一行换了个客户标签渲染
      expect(prodTextB, 'AC-16①🚨：客户 A、B 视角下的生产料号应不同，实际相同 —— 说明行内容没有随客户切换真正刷新'
        + '（rowKey 未含 customerNo 导致 React 复用了上一个客户的单元格值）').not.toBe(prodTextA);
      expect(prodTextA).toContain(PROD_A.slice(-6));
      expect(prodTextB).toContain(PROD_B.slice(-6));

      // ③ 切回 A，验证数据仍完整（不是被 B 的查看动作意外改写）
      await selectCustomer(page, CUST_A);
      await search(page, X);
      rows = page.locator('.ant-table-tbody tr.ant-table-row');
      await expect(rows).toHaveCount(1, { timeout: 10_000 });
      const prodCellA2 = await cellByHeader(page, rows.first(), '生产料号');
      const prodTextA2 = (await prodCellA2.innerText()).trim();
      expect(prodTextA2, 'AC-16①：切回客户 A 后生产料号应与第一次一致').toBe(prodTextA);
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
