/**
 * task-260903「产品维护能力增强」（子任务）· 证伪实验 `FS-1` ~ `FS-4`。
 *
 * **这不是验收用例，是验证「验收用例本身有没有牙」。**
 * `testing.md §4.4`：首次 PASS 证明不了守卫有效 —— 可能它压根没接上。
 * 断言「某事不存在」「某事返回 400」的用例尤其危险：**选择器/端点写错会恒定通过**。
 *
 * 🚫 与 `product-hub-edit.spec.ts` 一样，全程未读实现代码。
 *
 * 🚨 FS-1 与 FS-2 **会写共享库**，只碰 `S-1630010773`，用例自己 `finally` 还原。
 */
import { test, expect } from '@playwright/test';
import {
  assertIsolatedEnv, assertSameDatabase, assertEditFixtureReady, snapshotHeroRow,
  sqlOne, loginAs, search, gotoProductHub, switchTab,
  assertReadOnly, customerFilter, selectCustomer, columnValues,
  readProductionNo, restoreProductionNo,
  apiAs, putPartUrl, evidence,
  HERO, HERO_EDIT, FILTER_CUSTOMER, NON_WHITELIST_FIELD, AC_N_CUST0004,
} from './product-hub-edit.helpers';

test.beforeAll(async () => {
  assertIsolatedEnv();
  await assertSameDatabase();   // 见主 spec 的说明：跨库比较只会假绿，必须先验明正身
  assertEditFixtureReady();
});

/**
 * **FS-1 · 还原实验：`afterAll` 的「无残留证明」真的能抓到残留吗？**
 *
 * 主用例套的最后一道防线是「跑完之后 `HERO_EDIT` 那一行逐字节相同」。
 * 它平时永远是绿的 —— 而**一条永远绿的守卫和一条没接上的守卫长得一模一样**。
 *
 * ⇒ 这里故意制造一次残留（把值改掉、不还原），确认指纹比对**确实变红**，然后立刻复位。
 *   不变红 = 那道守卫是摆设，主用例套里所有写用例的「已还原」都不可采信。
 */
test('FS-1：故意留残留 → 行级指纹比对必须能抓到（还原实验）', async () => {
  const orig = readProductionNo().raw;
  const before = snapshotHeroRow();
  try {
    // 干预：把值改成一个不可能自然出现的哨兵
    restoreProductionNo('FS1-RESIDUE-PROBE');
    const after = snapshotHeroRow();
    console.log(`[FS-1] before = ${before}`);
    console.log(`[FS-1] after  = ${after}`);
    expect(after,
      '🚨 FS-1 失败：整行文本在 production_no 被改掉之后竟然没有变化 ⇒ ' +
      'snapshotHeroRow 没在读真实数据，afterAll 的「无残留证明」是空验证，' +
      '主用例套里所有写用例的「已还原」结论都不可采信').not.toBe(before);

    // 干预复原后必须变回来（证明它不是「只要读两次就不同」的那种假差异，比如带时间戳）
    restoreProductionNo(orig);
    const restored = snapshotHeroRow();
    expect(restored,
      '🚨 FS-1 失败：把值改回原值之后整行文本仍与开跑前不同 ⇒ ' +
      '整行里含有每次都变的列（如 updated_at），指纹比对会**恒定报残留**，属假红，需改窄观察面')
      .toBe(before);
    evidence('FS-1', `before=${before}\n改成哨兵后=${after}\n复位后=${restored}\n` +
      `结论：指纹比对能抓到残留，且复位后不假红`);
  } finally {
    restoreProductionNo(orig);
  }
});

/**
 * **FS-2 · 白名单真的有牙吗（阴阳对照）**
 *
 * `A-02` 断言「非白名单字段 → 400」。但**端点根本不存在时也会返 4xx**，
 * 于是 A-02 会以「通过」的样子混过去，而实际上 S-1 压根没落地。
 *
 * ⇒ 同一个端点、同一个鉴权，打两发：
 *   - 白名单内 `productionNo` → 必须 **200 且真的生效**
 *   - 白名单外 `materialName` → 必须 **400**
 *   两者**必须不同**。都 400 ⇒ 端点没工作，A-02 的 400 是假通过。
 */
test('FS-2：白名单内 200 生效 / 白名单外 400 —— 两者必须不同（阴阳对照）', async () => {
  const orig = readProductionNo().raw;
  const ctx = await apiAs('SYSTEM_ADMIN');
  try {
    const probeVal = 'FS2-WHITELIST-PROBE';
    const good = await ctx.put(putPartUrl(HERO_EDIT), { data: { productionNo: probeVal } });
    const goodBody = await good.text();
    const landed = readProductionNo().raw;

    const bad = await ctx.put(putPartUrl(HERO_EDIT), { data: NON_WHITELIST_FIELD });
    const badBody = await bad.text();

    console.log(`[FS-2] 白名单内 productionNo → ${good.status()}（库值 = ${JSON.stringify(landed)}）`);
    console.log(`[FS-2] 白名单外 materialName → ${bad.status()}`);
    evidence('FS-2',
      `白名单内 productionNo → ${good.status()}  body=${goodBody.slice(0, 300)}  库值=${JSON.stringify(landed)}\n` +
      `白名单外 materialName → ${bad.status()}  body=${badBody.slice(0, 300)}`);

    expect(good.status(),
      `🚨 FS-2：白名单**内**的 productionNo 也被拒（${good.status()}）⇒ 端点根本没在工作。\n` +
      `  此时 A-02 的「400」不是白名单起作用，是端点本身坏了/不存在 —— **A-02 的通过是假的**。`)
      .toBe(200);
    expect(landed,
      `🚨 FS-2：白名单内字段返回了 200 但库值没变 ⇒ 端点「答应了却没做」，` +
      `A-01 若只断言状态码就会假绿`).toBe(probeVal);
    expect(bad.status(),
      `FS-2：白名单外字段必须 400（实际 ${bad.status()}）`).toBe(400);
    expect(good.status(), 'FS-2：两种字段的响应码必须不同，否则白名单没有判别力').not.toBe(bad.status());
  } finally {
    restoreProductionNo(orig);
    await ctx.dispose();
  }
});

/**
 * **FS-3 · 客户过滤真的在后端做吗**
 *
 * 若前端一次性捞 17 行、自己在内存里 `filter`，页面上看起来完全正常 ——
 * 直到数据量超过一页，**翻页就会错**（第 2 页拿不到该客户的行，或总数与实际不符）。
 * 这类缺陷在 17 行的样例上**永远暴露不出来**，所以必须直接看请求。
 *
 * 判据两条，缺一不可：
 *   ① 请求 URL 带 `customerNo` 参数（过滤条件真的发给了后端）
 *   ② 响应体 `total` **直接是** 该客户的行数（后端算的，不是前端减出来的）
 */
test('FS-3：选客户时请求带 customerNo，且后端直接返回过滤后的 total', async ({ page }) => {
  const dbCount = Number(sqlOne(
    `SELECT count(*) FROM ds_quote_customer_part WHERE customer_no = '${FILTER_CUSTOMER}'`));
  const dbAll = Number(sqlOne('SELECT count(*) FROM ds_quote_customer_part'));
  console.log(`[FS-3] 库中 ${FILTER_CUSTOMER}=${dbCount} 全部=${dbAll}（AC 基线 ${AC_N_CUST0004} / 17）`);
  expect(dbCount, 'FS-3 前置：该客户必须真有行，否则本实验空跑').toBeGreaterThan(0);
  expect(dbCount, 'FS-3 前置：该客户的行数必须小于全部，否则「过滤有没有生效」不可分辨')
    .toBeLessThan(dbAll);

  const captured: Array<{ url: string; total: unknown; itemCount: unknown }> = [];
  page.on('response', async r => {
    if (!/customer-parts/.test(r.url())) return;
    let total: unknown = '<解析失败>';
    let itemCount: unknown = '<解析失败>';
    try {
      const j = await r.json();
      total = j?.data?.total ?? j?.total;
      itemCount = (j?.data?.items ?? j?.items)?.length;
    } catch { /* 非 JSON 忽略 */ }
    captured.push({ url: r.url(), total, itemCount });
  });

  await loginAs(page, 'SYSTEM_ADMIN');
  await gotoProductHub(page);
  await switchTab(page, '客户产品');
  captured.length = 0;                 // 只关心「选客户之后」发出的请求
  await selectCustomer(page, FILTER_CUSTOMER);
  await page.waitForTimeout(1500);

  console.log(`[FS-3] 选客户后捕获的请求 = ${JSON.stringify(captured, null, 2)}`);
  evidence('FS-3', `库中 ${FILTER_CUSTOMER}=${dbCount} 全部=${dbAll}\n` +
    `捕获请求 = ${JSON.stringify(captured, null, 2)}`);

  expect(captured.length,
    `🚨 FS-3：选客户之后**一个 customer-parts 请求都没发** ⇒ 过滤是前端在内存里做的。\n` +
    `  17 行样例上看不出问题，但数据一超过一页**翻页就会错**（api.md B-1 明确要求后端过滤）。`)
    .toBeGreaterThan(0);

  const withParam = captured.filter(c => /[?&]customerNo=/.test(c.url));
  expect(withParam.length,
    `🚨 FS-3：请求里没有 customerNo 参数 ⇒ 过滤条件没发给后端。\n` +
    `  实际请求 = ${JSON.stringify(captured.map(c => c.url))}`).toBeGreaterThan(0);

  const last = withParam[withParam.length - 1];
  expect(last.total,
    `🚨 FS-3：带 customerNo 的响应 total=${last.total}，期望后端直接返回 ${dbCount}。\n` +
    `  若等于 ${dbAll} ⇒ 后端收了参数但没用，前端自己 filter 的（页面看着对，翻页会错）。`)
    .toBe(dbCount);

  // 双向交叉：页面上看到的也必须与之一致
  const cnos = await columnValues(page, '客户编号');
  expect(cnos.length, 'FS-3：页面应有行').toBeGreaterThan(0);
  expect([...new Set(cnos)], 'FS-3：页面可见行应只剩该客户').toEqual([FILTER_CUSTOMER]);
});

/**
 * **FS-4 · `assertReadOnly` 有牙吗（阳性对照，沿用父任务 FS-1a）**
 *
 * `E-08 / AC-7` 全是「断言不存在」：没有保存按钮、没有 input。
 * 选择器写错时，`toHaveCount(0)` 会**恒定通过** —— 这是最隐蔽的一类假绿。
 *
 * ⇒ 把**同一个** `assertReadOnly` 指向已知**可编辑**的核价侧抽屉。
 *   父任务实测那里有 `input=96 / 保存=1`，所以它**必须抛错**。
 *   不抛错 ⇒ E-08 的「抽屉全只读」结论是空验证，不可采信。
 *
 * 📌 顺带覆盖 `test.md §6` 的 **RG-5**：核价侧「料号核价」仍可编辑（本任务不得波及它）。
 */
test('FS-4：assertReadOnly 指向已知可编辑的核价抽屉，必须硬失败（阳性对照 + RG-5）', async ({ page }) => {
  await loginAs(page, 'PRICING_MANAGER');
  await page.goto('/master-data-hub');
  // 🚨 **不用 `getByText('料号核价',{exact:true}).first()`**（2026-09-04 实测踩到，300s 超时）：
  //    该文案在页面上出现多处（页签 + 面包屑/导航），`.first()` 落到的那个**不可点击**，
  //    于是 click 一直等 actionability，表现为纯超时 —— **看起来像页面坏了**，
  //    实测诊断：页面渲染完全正常、无 4xx/5xx、`role=tab` 里就有「料号核价」。
  //    ⇒ 按**角色**定位，不按文案。（父任务 FS-1a 的写法在当时可用，DOM 变了之后就烂了。）
  await page.getByRole('tab', { name: '料号核价', exact: true }).click();
  await page.waitForTimeout(1500);
  await search(page, HERO);
  await page.getByRole('cell', { name: HERO, exact: true }).first().click();
  const drawer = page.locator('.ant-drawer').first();
  await expect(drawer).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(3000);
  await drawer.locator('[role="tab"]').filter({ hasText: '物料BOM' }).first().click();
  await page.waitForTimeout(3500);

  const inputs = await drawer.locator('.ant-table input').count();
  const saves = await drawer.getByRole('button', { name: /保\s*存/ }).count();
  console.log(`[FS-4] 核价抽屉 input=${inputs} 保存=${saves}（父任务实测基线 96 / 1）`);

  // 这一条同时就是 RG-5：核价侧仍可编辑
  expect(inputs,
    'FS-4 前置 / RG-5：核价抽屉必须确实有编辑控件。\n' +
    '  为 0 有两种可能，处置完全不同：① 阳性对照样本失效（测试问题）；' +
    '② **本任务把核价侧也改成只读了（产品缺陷，越界）**。需人工分辨后再下结论。')
    .toBeGreaterThan(0);

  let threw = false;
  let msg = '';
  try {
    await assertReadOnly(drawer, 'FS-4 阳性对照');
  } catch (e) {
    threw = true;
    msg = (e as Error).message.slice(0, 200);
  }
  evidence('FS-4', `核价抽屉 input=${inputs} 保存=${saves}\nassertReadOnly 是否抛错=${threw}\n${msg}`);
  expect(threw,
    `🚨 FS-4 失败：assertReadOnly 面对 ${inputs} 个 input + ${saves} 个保存按钮竟然通过了 ⇒ ` +
    `它没在真正检查编辑控件，**E-08 / AC-7 的「抽屉全只读」结论是空验证，不可采信**`).toBe(true);
});
