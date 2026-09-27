/**
 * E2E · task-260923 报价单内搜索：回车触发 + 生产料号 —— 测试分片 S-1（样本单 A：编辑页 + 详情页）
 *
 * 覆盖 AC-1 ~ AC-10、AC-12（AC-11 核价工作台见 task260923-search-s1-costing.spec.ts）。
 * 派生来源 / 选择器来源 / 分片纪律：见 task260923-search-s1.helpers.ts 文件头。
 *
 * 🚦 执行步骤（执行轮，主线通知后）
 *  1) `pgrep -af "node.*[p]laywright test"` 为空；本文件 beforeAll 会再采一次，非空硬失败。
 *  2) worktree 临时前端 5392 已起并预热（testing.md §4.2.6）；后端用共享 8081（本任务后端零改动）。
 *  3) `T260923_S1_RUN=run-01 PW_BASE_URL=http://localhost:5392 npx playwright test e2e/task260923-search-s1 --reporter=list`
 *     证据落 `<任务目录>/证据/测试/S-1/<T260923_S1_RUN>/`，同名文件自动加序号，不覆盖。
 *
 * 样本单 A：每个 worker 的 beforeAll 用接口新建（POST 建单 + PUT /draft 写 14 行），用例里从界面打开
 * （= 「保存后重新打开」），afterAll 经 DELETE 接口删除并回读 404。
 */
import { test, expect, Page } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';
import * as H from './task260923-search-s1.helpers';

test.describe.configure({ mode: 'default' });

let cookie = '';
let src: Record<string, H.SrcRow> = {};
let A = { id: '', no: '', name: '' };

test.beforeAll(async () => {
  test.setTimeout(180_000);
  const others = H.otherPlaywright();
  H.appendEvidence('00-环境验明正身.txt', `${new Date().toISOString()} 其他 playwright test 进程采样=${JSON.stringify(others)}`);
  expect(others, '有别的 playwright test 在跑（无跨进程互斥、会写共享库 user 表）⇒ 等它结束再跑').toEqual([]);
  await H.assertStackIdentity();
  src = H.loadSource();
  // 样本自检：AC 写死的期望集合 = 用源数据按 AC 口径现算的集合（不等 ⇒ 样本漂移，判【未验证】，不是缺陷）
  expect(H.expectedHits(src, '30002'), '样本自检：30002 应只命中 S0004~S0007').toEqual(H.SET_30002);
  expect(H.expectedHits(src, '30003'), '样本自检：30003 应只命中 S0008~S0011').toEqual(H.SET_30003);
  expect(H.expectedHits(src, '300034'), '样本自检：300034 应只命中 S0011').toEqual(['S0011']);
  expect(H.expectedHits(src, 'S0011')).toEqual(['S0011']);
  expect(H.expectedHits(src, 'zt-c012')).toEqual(['S0012']);
  expect(H.expectedHits(src, '正泰端子')).toEqual(['S0012']);
  expect(H.expectedHits(src, 'XYZ-T260923')).toEqual([]);
  // 30002 / 30003 / 300034 不出现在销售料号 / 客户产品编号 / 客户料号名称里（= 只能靠生产料号命中）
  for (const q of ['30002', '30003', '300034']) {
    for (const s of H.ORDER) {
      const r = src[s];
      expect([r.material, r.custProductNo, r.custPartName].some((f) => f.includes(q)), `样本自检：${q} 不应出现在 ${s} 的非生产料号字段`).toBe(false);
    }
  }
  cookie = await H.loginApi();
  await H.cleanupStaleA(cookie);
  const tpl = H.resolveTemplates();
  A = await H.createSample(cookie, H.PREFIX_A, src, tpl);
});

test.afterAll(async () => {
  // finally：样本单 A 经接口删除（名称前缀守卫 + 回读 404）
  if (A.id) await H.deleteSampleA(cookie || await H.loginApi(), A.id);
});

async function login(page: Page) {
  await loginAsAdmin(page);
  await page.setViewportSize({ width: 1280, height: 800 });
}
/** 首页状态：共 14 条、第 1 页 10 张卡片（第 1~10 个产品）、有第 2 页。 */
async function expectInitial(page: Page, why: string) {
  expect(await H.countText(page), `${why}：计数`).toBe(H.TXT_ALL);
  const nos = await H.visibleSalesNos(page);
  expect(nos.length, `${why}：第 1 页卡片数（实际 ${JSON.stringify(nos)}）`).toBe(10);
  expect(H.sorted(nos), `${why}：第 1 页卡片 = 第 1~10 个产品`).toEqual(H.sorted(H.FIRST_PAGE));
  expect(await H.hasPage2(page), `${why}：分页器应仍有第 2 页`).toBe(true);
}
async function submit(page: Page, text: string) {
  const inp = H.searchInput(page);
  await inp.fill(text);
  await inp.press('Enter');
  await page.waitForTimeout(800);
}

// ─────────────────────────────────────────────────────────────────────────────
test('T-1 · AC-1 编辑页与详情页提示文字逐字 +完整可见（文字宽 ≤ 内容区宽）', async ({ page }) => {
  test.setTimeout(240_000);
  await login(page);
  const rec: any = {};
  for (const [where, open] of [['编辑页', () => H.openEdit(page, A.id)], ['详情页', () => H.openDetail(page, A.id)]] as const) {
    await open();
    const inputs = H.allSearchInputs(page);
    const n = await inputs.count();
    expect(n, `${where}：应至少有 1 个搜索框`).toBeGreaterThan(0);
    rec[where] = [];
    for (let i = 0; i < n; i++) {
      const inp = inputs.nth(i);
      const ph = await inp.getAttribute('placeholder');
      const fit = await H.placeholderFit(inp);
      const ctrl = await H.placeholderFit(inp, '，' + H.PLACEHOLDER); // 量具阳性对照：加长一倍必须判为放不下
      rec[where].push({ ph, fit, ctrl });
      expect(ph, `${where} 第 ${i + 1} 个搜索框提示文字逐字`).toBe(H.PLACEHOLDER);
      expect(fit.textW, `${where}：量具自检，文字宽应 > 0`).toBeGreaterThan(0);
      expect(ctrl.fits, `${where}：量具阳性对照失效（加长一倍仍判放得下）⇒ 宽度判据不可信`).toBe(false);
      expect(fit.fits, `${where}：提示文字宽 ${fit.textW.toFixed(1)}px 应 ≤ 内容区 ${fit.contentW.toFixed(1)}px（字体 ${fit.font}）`).toBe(true);
    }
    await H.shot(page, `T-1-${where}`);
  }
  H.writeEvidence('T-1.json', JSON.stringify(rec, null, 1));
});

test('T-2 / T-3 · AC-2 输入不回车不变 → AC-3 回车后生产料号命中第 2 页的 4 个', async ({ page }) => {
  test.setTimeout(240_000);
  await login(page);
  await H.openEdit(page, A.id);
  await expectInitial(page, 'AC-2 前置');
  // ── AC-2 ──
  const inp = H.searchInput(page);
  await inp.click();
  await inp.pressSequentially('30002', { delay: 80 });
  await page.waitForTimeout(2000);
  expect(await inp.inputValue(), 'AC-2：框内应为 30002（证明确实输入了）').toBe('30002');
  await expectInitial(page, 'AC-2 输入 30002 不回车等 2 秒');
  await H.shot(page, 'T-2-输入未回车');
  // ── AC-3 ──
  await inp.press('Enter');
  await page.waitForTimeout(800);
  const cnt = await H.countText(page);
  const nos = await H.visibleSalesNos(page);
  const hl: Record<string, string[]> = {};
  for (const s of nos) hl[s] = await H.yellowFragments((await H.cardBySales(page, s)).locator('.qt-card-header'));
  await H.shot(page, 'T-3-回车后');
  const card4 = await H.cardBySales(page, 'S0004');
  const pop = await H.popoverPartNo(page, card4);
  await H.shot(page, 'T-3-S0004浮层');
  H.writeEvidence('T-3.json', JSON.stringify({ cnt, nos, hl, pop, srcS0004: src.S0004 }, null, 1));
  expect(cnt, 'AC-3：计数').toBe(H.txtMatch(4));
  expect(nos.length, `AC-3：第 1 页恰好 4 张（实际 ${JSON.stringify(nos)}）`).toBe(4);
  expect(H.sorted(nos), 'AC-3：销售料号集合').toEqual(H.SET_30002);
  for (const s of nos) expect(hl[s], `AC-3：${s} 卡片头部黄底高亮片段数应为 0`).toEqual([]);
  expect(src.S0004.production, '源值非空').toBeTruthy();
  expect(pop.partNo, `AC-3：S0004 浮层「料号：」应 = 源 production_no ${src.S0004.production}\n原文=${pop.raw}`).toBe(src.S0004.production);
  // 量具阳性对照：销售料号命中必须能被 yellowFragments 抓到，否则上面「高亮 = 0」不可信
  await submit(page, 'S0004');
  const ctrl = await H.yellowFragments((await H.cardBySales(page, 'S0004')).locator('.qt-card-header'));
  H.writeEvidence('T-3-量具对照.json', JSON.stringify({ ctrl }));
  expect(ctrl.length, '量具阳性对照：搜 S0004 后 S0004 卡片应抓到 ≥1 个黄底片段；抓不到 ⇒ AC-3 高亮断言【未验证】').toBeGreaterThan(0);
});

test('T-4 · AC-4 原有三字段照常可搜（销售料号 / 客户产品编号大小写不敏感 / 客户料号名称）', async ({ page }) => {
  test.setTimeout(240_000);
  await login(page);
  await H.openEdit(page, A.id);
  const rec: any = {};
  // ①
  await submit(page, 'S0011');
  rec.q1 = { cnt: await H.countText(page), nos: await H.visibleSalesNos(page) };
  const c1 = await H.cardBySales(page, 'S0011');
  rec.q1.hl = await H.yellowFragments(c1.locator('.qt-part-badge'));
  await H.shot(page, 'T-4-①S0011');
  // ②
  await submit(page, 'zt-c012');
  rec.q2 = { cnt: await H.countText(page), nos: await H.visibleSalesNos(page) };
  const c2 = await H.cardBySales(page, 'S0012');
  rec.q2.hl = await H.yellowFragments(c2.locator('.qt-sku-badge'));
  await H.shot(page, 'T-4-②zt-c012');
  // ③
  await submit(page, '正泰端子');
  rec.q3 = { cnt: await H.countText(page), nos: await H.visibleSalesNos(page) };
  await H.shot(page, 'T-4-③正泰端子');
  H.writeEvidence('T-4.json', JSON.stringify(rec, null, 1));
  expect(rec.q1.cnt, 'AC-4①：计数').toBe(H.txtMatch(1));
  expect(rec.q1.nos, 'AC-4①：卡片').toEqual(['S0011']);
  expect(rec.q1.hl, 'AC-4①：销售料号徽标里 S0011 黄底高亮').toEqual(['S0011']);
  expect(rec.q2.cnt, 'AC-4②：计数「匹配 1 条」').toMatch(/^匹配 1 条/);
  expect(rec.q2.nos, 'AC-4②：卡片').toEqual(['S0012']);
  expect(rec.q2.hl, 'AC-4②：客户产品编号徽标里 ZT-C012 黄底高亮（大小写不敏感）').toEqual([src.S0012.custProductNo]);
  expect(src.S0012.custProductNo, '源值').toBe('ZT-C012');
  expect(rec.q3.cnt, 'AC-4③：计数「匹配 1 条」').toMatch(/^匹配 1 条/);
  expect(rec.q3.nos, 'AC-4③：卡片（客户料号名称命中）').toEqual(['S0012']);
});

test('T-5 · AC-5 点 ✕ 不回车，1 秒内恢复全部', async ({ page }) => {
  test.setTimeout(240_000);
  await login(page);
  await H.openEdit(page, A.id);
  await submit(page, '30002');
  expect(await H.countText(page), 'AC-5 前置：匹配 4 条').toBe(H.txtMatch(4));
  const clr = H.bar(page).locator('.ant-input-clear-icon').first();
  await expect(clr, '搜索框里的 ✕ 应可见').toBeVisible();
  await clr.click();
  const t = await H.within(1000, async () => [await H.searchInput(page).inputValue(), await H.countText(page),
    H.sorted(await H.visibleSalesNos(page))], ['', H.TXT_ALL, H.sorted(H.FIRST_PAGE)], 'AC-5 点 ✕ 后');
  await H.shot(page, 'T-5-点叉后');
  H.writeEvidence('T-5.json', JSON.stringify({ restoredMs: t }));
});

test('T-6 · AC-6 退格删光 / 纯空格：各自 1 秒内恢复；中间再回车仍匹配 4 条', async ({ page }) => {
  test.setTimeout(240_000);
  await login(page);
  await H.openEdit(page, A.id);
  const inp = H.searchInput(page);
  await submit(page, '30002');
  expect(await H.countText(page), 'AC-6 前置：匹配 4 条').toBe(H.txtMatch(4));
  const probe = async () => [await H.countText(page), H.sorted(await H.visibleSalesNos(page))];
  const ALL = [H.TXT_ALL, H.sorted(H.FIRST_PAGE)];
  const rec: any = {};
  // ① 退格删光
  await inp.click(); await inp.press('End');
  const mid: string[] = [];
  for (let i = 0; i < 5; i++) { await inp.press('Backspace'); if (i < 4) mid.push(await H.countText(page)); }
  expect(await inp.inputValue(), 'AC-6①：框内已删光').toBe('');
  rec.midCounts = mid; // 删到一半的计数（不属 AC-6 断言，仅记录）
  rec.t1 = await H.within(1000, probe, ALL, 'AC-6① 退格删光后');
  await H.shot(page, 'T-6-①删光');
  // ② 再输入回车
  await inp.pressSequentially('30002', { delay: 50 }); await inp.press('Enter'); await page.waitForTimeout(800);
  rec.c2 = await H.countText(page);
  expect(rec.c2, 'AC-6②：匹配 4 条').toBe(H.txtMatch(4));
  // ③ 全选改为 3 个空格
  await inp.click(); await inp.press('Control+A'); await page.keyboard.type('   ');
  expect(await inp.inputValue(), 'AC-6③：框内应为 3 个空格（证明操作生效）').toBe('   ');
  rec.t3 = await H.within(1000, probe, ALL, 'AC-6③ 改为 3 个空格后');
  await H.shot(page, 'T-6-③三个空格');
  H.writeEvidence('T-6.json', JSON.stringify(rec, null, 1));
});

test('T-7 · AC-7 空态：文案引用最近一次回车提交的文字；追加不回车不变；清空查询恢复', async ({ page }) => {
  test.setTimeout(240_000);
  await login(page);
  await H.openEdit(page, A.id);
  await submit(page, 'XYZ-T260923');
  const sub = H.emptySubtitle('XYZ-T260923');
  const rec: any = { cnt: await H.countText(page), cards: await H.visibleSalesNos(page), pag: await H.paginationVisible(page) };
  await H.shot(page, 'T-7-空态');
  await expect(page.getByText(H.EMPTY_TITLE, { exact: true }).first(), 'AC-7：标题').toBeVisible();
  await expect(page.getByText(sub, { exact: true }).first(), 'AC-7：副文案逐字').toBeVisible();
  expect(rec.cnt, 'AC-7：计数').toBe(H.TXT_NONE);
  expect(rec.pag, 'AC-7：分页器不显示').toBe(false);
  expect(rec.cards, 'AC-7：空态不应残留卡片').toEqual([]);
  // 追加 -9 不回车
  const inp = H.searchInput(page);
  await inp.click(); await inp.press('End'); await inp.pressSequentially('-9', { delay: 80 });
  await page.waitForTimeout(1500);
  expect(await inp.inputValue(), '框内应为 XYZ-T260923-9').toBe('XYZ-T260923-9');
  await expect(page.getByText(sub, { exact: true }).first(), 'AC-7：追加 -9 后副文案仍逐字引用「XYZ-T260923」').toBeVisible();
  await H.shot(page, 'T-7-追加未回车');
  // 清空查询
  await page.getByRole('button', { name: /清\s*空\s*查\s*询/ }).first().click();
  await page.waitForTimeout(800);
  const vals = await H.allSearchInputs(page).evaluateAll((els) => els.map((e) => (e as HTMLInputElement).value));
  rec.afterClear = { vals, cnt: await H.countText(page) };
  H.writeEvidence('T-7.json', JSON.stringify(rec, null, 1));
  expect(vals.length, '搜索框数应 ≥1').toBeGreaterThan(0);
  expect(vals.every((v) => v === ''), `AC-7：点「清空查询」后搜索框为空（实际 ${JSON.stringify(vals)}）`).toBe(true);
  expect(rec.afterClear.cnt, 'AC-7：点「清空查询」后计数').toBe(H.TXT_ALL);
  await H.shot(page, 'T-7-清空查询后');
});

test('T-8 · AC-8 输入法组字中回车不触发，普通回车触发', async ({ page }) => {
  test.setTimeout(240_000);
  await login(page);
  await H.openEdit(page, A.id);
  const inp = H.searchInput(page);
  await inp.click(); await inp.pressSequentially('30003', { delay: 60 });
  await inp.dispatchEvent('keydown', { key: 'Enter', code: 'Enter', isComposing: true, bubbles: true, cancelable: true });
  await page.waitForTimeout(1500);
  const rec: any = { afterComposing: { cnt: await H.countText(page), nos: await H.visibleSalesNos(page) } };
  await H.shot(page, 'T-8-组字中回车后');
  await inp.press('Enter'); await page.waitForTimeout(800);
  rec.afterEnter = { cnt: await H.countText(page), nos: await H.visibleSalesNos(page) };
  await H.shot(page, 'T-8-普通回车后');
  // 量具对照：同样用 dispatchEvent 合成、但 isComposing=false 的回车必须能触发，
  // 否则「组字中回车不触发」可能只是合成事件整体没被处理（空验证）
  await inp.fill(''); await page.waitForTimeout(800);
  await inp.pressSequentially('30003', { delay: 30 });
  await inp.dispatchEvent('keydown', { key: 'Enter', code: 'Enter', isComposing: false, bubbles: true, cancelable: true });
  await page.waitForTimeout(1500);
  rec.gauge = { cnt: await H.countText(page) };
  H.writeEvidence('T-8.json', JSON.stringify(rec, null, 1));
  expect(rec.gauge.cnt, '量具对照：合成的非组字回车应触发搜索；不触发 ⇒ 组字断言【未验证】').toBe(H.txtMatch(4));
  expect(rec.afterComposing.cnt, 'AC-8：组字中回车后计数不变').toBe(H.TXT_ALL);
  expect(H.sorted(rec.afterComposing.nos), 'AC-8：组字中回车后仍为第 1 页 10 张').toEqual(H.sorted(H.FIRST_PAGE));
  expect(rec.afterEnter.cnt, 'AC-8：普通回车后计数').toBe(H.txtMatch(4));
  expect(H.sorted(rec.afterEnter.nos), 'AC-8：普通回车后卡片').toEqual(H.SET_30003);
});

test('T-9 · AC-9 序列：卡片 → 核价单 → 报价单 Excel 视图 → 切回卡片 → 改词不回车 → 回车 → 刷新', async ({ page }) => {
  test.setTimeout(300_000);
  await login(page);
  await H.openEdit(page, A.id);
  const seg = (label: string) => page.locator('.ant-segmented-item', { hasText: label }).first();
  const rec: any = {};
  // ①
  await submit(page, '30002');
  rec.s1 = { cnt: await H.countText(page), nos: await H.visibleSalesNos(page) };
  await H.shot(page, 'T-9-①');
  expect(rec.s1.cnt, 'AC-9①：计数').toBe(H.txtMatch(4));
  expect(H.sorted(rec.s1.nos), 'AC-9①：卡片').toEqual(H.SET_30002);
  // ② 核价单
  await seg('核价单').click(); await page.waitForTimeout(4000);
  rec.s2 = { cnt: await H.countText(page), nos: await H.visibleSalesNos(page) };
  await H.shot(page, 'T-9-②核价单');
  expect(rec.s2.cnt, 'AC-9②：计数').toBe(H.txtMatch(4));
  expect(H.sorted(rec.s2.nos), 'AC-9②：卡片销售料号集合').toEqual(H.SET_30002);
  // ③（AC 变更 D-1，2026-09-24）切回「📝 报价单」，再切「📑 Excel 视图」⇒ 报价单侧 Excel 视图
  await seg('报价单').click(); await page.waitForTimeout(3000);
  await seg('Excel 视图').click(); await page.waitForTimeout(6000);
  const rows = await page.evaluate(() => Array.from(document.querySelectorAll('.ant-table-tbody tr.ant-table-row'))
    .filter((r) => (r as HTMLElement).offsetParent !== null).map((r) => (r as HTMLElement).innerText.replace(/\s+/g, ' ')));
  const selected = await page.locator('.ant-segmented-item-selected').allInnerTexts();
  rec.s3 = { selected, rows, salesNos: rows.map((r) => (r.match(/\bS\d{4}\b/) || [''])[0]), cnt: await H.countText(page) };
  await H.shot(page, 'T-9-③Excel视图');
  H.writeEvidence('T-9-①②③.json', JSON.stringify(rec, null, 1));
  expect(selected, 'AC-9③ 前置：此刻应处于「报价单 + Excel 视图」').toEqual(expect.arrayContaining(['📝 报价单', '📑 Excel 视图']));
  expect(rows.length, `AC-9③：报价单侧 Excel 视图表格恰 4 行（此刻选中=${JSON.stringify(selected)}）`).toBe(4);
  expect(H.sorted(rec.s3.salesNos), 'AC-9③：销售料号集合').toEqual(H.SET_30002);
  // ④ 切回「📋 产品卡片」
  await seg('产品卡片').click(); await page.waitForTimeout(3000);
  rec.s4 = { nos: await H.visibleSalesNos(page), cnt: await H.countText(page) };
  await H.shot(page, 'T-9-④切回');
  expect(H.sorted(rec.s4.nos), 'AC-9④：仍 4 张卡片 {S0004~S0007}').toEqual(H.SET_30002);
  // ⑤ 改成 30003 不回车
  const inp = H.searchInput(page);
  await inp.fill('30003'); await page.waitForTimeout(2000);
  rec.s5 = { nos: await H.visibleSalesNos(page), cnt: await H.countText(page) };
  expect(H.sorted(rec.s5.nos), 'AC-9⑤：仍 {S0004~S0007}').toEqual(H.SET_30002);
  expect(rec.s5.cnt, 'AC-9⑤：仍「匹配 4 条」').toBe(H.txtMatch(4));
  // ⑥ 回车
  await inp.press('Enter'); await page.waitForTimeout(800);
  rec.s6 = { nos: await H.visibleSalesNos(page), cnt: await H.countText(page) };
  await H.shot(page, 'T-9-⑥');
  expect(H.sorted(rec.s6.nos), 'AC-9⑥：变为 {S0008~S0011}').toEqual(H.SET_30003);
  expect(rec.s6.cnt, 'AC-9⑥：匹配 4 条').toBe(H.txtMatch(4));
  // ⑦ 刷新（刷新后编辑页回到 Step1，按用户路径点「下一步」回到卡片）
  await page.reload();
  await H.gotoStep2(page);
  const vals = await H.allSearchInputs(page).evaluateAll((els) => els.map((e) => (e as HTMLInputElement).value));
  rec.s7 = { vals, cnt: await H.countText(page) };
  await H.shot(page, 'T-9-⑦刷新后');
  H.writeEvidence('T-9.json', JSON.stringify(rec, null, 1));
  expect(vals.length).toBeGreaterThan(0);
  expect(vals.every((v) => v === ''), `AC-9⑦：刷新后搜索框为空（实际 ${JSON.stringify(vals)}）`).toBe(true);
  expect(rec.s7.cnt, 'AC-9⑦：刷新后共 14 条').toBe(H.TXT_ALL);
});

test('T-10 · AC-10 详情页：不回车不变 / 回车生产料号命中 / ✕ 恢复 / 空态引用提交词', async ({ page }) => {
  test.setTimeout(240_000);
  await login(page);
  await H.openDetail(page, A.id);
  const inp = H.searchInput(page);
  const rec: any = {};
  // ①
  await inp.click(); await inp.pressSequentially('300034', { delay: 80 }); await page.waitForTimeout(2000);
  expect(await inp.inputValue()).toBe('300034');
  rec.s1 = { cnt: await H.countText(page), nos: await H.visibleSalesNos(page) };
  await H.shot(page, 'T-10-①未回车');
  expect(rec.s1.cnt, 'AC-10①：共 14 条').toBe(H.TXT_ALL);
  expect(H.sorted(rec.s1.nos), 'AC-10①：第 1 页 10 张').toEqual(H.sorted(H.FIRST_PAGE));
  // ②
  await inp.press('Enter'); await page.waitForTimeout(800);
  rec.s2 = { cnt: await H.countText(page), nos: await H.visibleSalesNos(page) };
  await H.shot(page, 'T-10-②回车');
  expect(rec.s2.cnt, 'AC-10②：计数').toBe(H.txtMatch(1));
  expect(rec.s2.nos, 'AC-10②：卡片 = S0011').toEqual(['S0011']);
  // ③
  await H.bar(page).locator('.ant-input-clear-icon').first().click();
  rec.t3 = await H.within(1000, () => H.countText(page), H.TXT_ALL, 'AC-10③ 点 ✕ 后');
  // ④
  await submit(page, 'XYZ-T260923');
  const sub = H.emptySubtitle('XYZ-T260923');
  await expect(page.getByText(sub, { exact: true }).first(), 'AC-10④：空态副文案逐字').toBeVisible();
  await inp.click(); await inp.press('End'); await inp.pressSequentially('-9', { delay: 80 }); await page.waitForTimeout(1500);
  expect(await inp.inputValue()).toBe('XYZ-T260923-9');
  await expect(page.getByText(sub, { exact: true }).first(), 'AC-10④：追加 -9 不回车后副文案不变').toBeVisible();
  await H.shot(page, 'T-10-④空态追加');
  H.writeEvidence('T-10.json', JSON.stringify(rec, null, 1));
});

test('T-12 · AC-12 120 字符超长输入：空态引用全文、无 JS 报错、框宽 320、无横向滚动', async ({ page }) => {
  test.setTimeout(240_000);
  const errs = H.collectPageErrors(page);
  await login(page);
  await H.openEdit(page, A.id);
  const long = 'T260923-' + 'Z'.repeat(112);
  expect(long.length, '量具：构造的字符串应为 120 字符').toBe(120);
  await submit(page, long);
  await page.waitForTimeout(1000);
  await expect(page.getByText(H.EMPTY_TITLE, { exact: true }).first(), 'AC-12：空态标题').toBeVisible();
  const quoted = await page.evaluate(() => {
    const el = Array.from(document.querySelectorAll('body *')).find((e) =>
      e.children.length === 0 && /^「[\s\S]*」在本报价单的/.test((e as HTMLElement).innerText || ''));
    const t = (el as HTMLElement | undefined)?.innerText || '';
    return t.slice(1, t.indexOf('」'));
  });
  const width = await H.bar(page).locator('.ant-input-affix-wrapper').first().evaluate((e) => e.getBoundingClientRect().width);
  const scroll = await page.evaluate(() => [document.documentElement.scrollWidth, document.documentElement.clientWidth]);
  const rec = { quoted, quotedLen: quoted.length, width, scroll, pageErrors: errs };
  await H.shot(page, 'T-12-超长输入');
  H.writeEvidence('T-12.json', JSON.stringify(rec, null, 1));
  expect(quoted, 'AC-12：副文案引号内为这 120 个字符').toBe(long);
  expect(errs, 'AC-12：控制台 pageerror 数 = 0').toEqual([]);
  expect(Math.round(width), 'AC-12：分页栏搜索框宽度仍为 320px').toBe(320);
  expect(scroll[0], `AC-12：无横向滚动条（scrollWidth=${scroll[0]} clientWidth=${scroll[1]}）`).toBe(scroll[1]);
});
