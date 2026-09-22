/**
 * task-260920 · S-2（正泰 CUST-0004 · S-全局）· R1「未计算窗口」界面子片
 *
 * 用例只从 需求文档.md ③ AC 原文（AC-1/4/5/6/8/9/21）+ test.md §2/§3 + api.md + 原型图/ 派生，
 * **未读** cpq-frontend/src、cpq-backend/src/main（E-6 例外只用于备份清单，未用于本文件）。
 *
 * 🚦 第一个用例会**真实生成版本**（写开发库，作废上一版全部待处理行）——
 *    只有设了 S2_ALLOW_GENERATE=R1-APPROVED（= 用户已对 R1 报批点头）才执行，否则整套 skip。
 * 🔒 除「立即生成 / 计算 / 重算预算 / 通过并升版→取消」外不发任何写请求（guardWrites 白名单）；「确认通过并升版」绝不点。
 * 🔒 断言一律限定本次版本 + 点击时刻之后（E-7）；🚫 全表计数。
 * ⚠️ 选择器按原型图 + 既有 e2e 写，**未在新代码页面上跑过 = 未验证**。
 */
import { test, expect, type Page } from '@playwright/test';
import * as fs from 'fs';
import {
  R, M, Q0842, CUST, BASE_URL, BACKEND_PORT, BACKEND_LOG, WORKTREE, evid, save, shot, sql, pendingVersion, review,
  logSize, logSince, listenerCwd, assertNoOtherPlaywright, apiLogin, guardWrites, W_COMPUTE, W_RECOMPUTE, W_IMPACT,
  gotoReviews, search, rowOf, cellText, drawerOf,
} from './task260920-s2.helpers';

// ------------------------------------------------------------------ 用例
test.describe.configure({ mode: 'serial' });
test.skip(process.env.S2_ALLOW_GENERATE !== 'R1-APPROVED', '🚦 R1 未报批通过（S2_ALLOW_GENERATE≠R1-APPROVED）⇒ 不执行任何会写开发库的步骤');

let V: { id: string; version_no: string; created_at: string };
let T0 = 0;

test.beforeAll(async () => {
  assertNoOtherPlaywright();
  const fCwd = listenerCwd(Number(new URL(BASE_URL).port));
  const bCwd = listenerCwd(BACKEND_PORT);
  R.identity = { frontend: { url: BASE_URL, cwd: fCwd }, backend: { port: BACKEND_PORT, cwd: bCwd }, log: BACKEND_LOG };
  expect(fCwd.startsWith(WORKTREE), `前端必须是 worktree 的临时 vite：cwd=${fCwd}`).toBe(true);
  expect(bCwd.startsWith(WORKTREE), `后端必须是 worktree 的临时后端：cwd=${bCwd}`).toBe(true);
  expect(BACKEND_LOG && fs.existsSync(BACKEND_LOG), 'S2_BACKEND_LOG 必须指向临时后端日志（AC-14/21 要用）').toBeTruthy();
  const h = new Date().getHours() * 60 + new Date().getMinutes();
  expect(h < 17 * 60 + 30 || h > 18 * 60 + 30, 'E-2：本机 17:30~18:30 禁跑').toBe(true);
  expect(sql(`select 1 from material_price_update_job where status='RUNNING'`).length, '全库在跑更新批次必须为 0').toBe(0);
  save();
});

test('R1-0 · 生成 V1（🚦写库：作废上一版全部待处理行）+ 记 T₀', async ({ page }) => {
  await apiLogin(page);
  const before = pendingVersion();
  expect(before.length, '生成前正泰应恰有 1 个待处理版本').toBe(1);
  R.gen = { before: before[0], willVoid: sql(`select count(*)::int n from material_price_review where version_id='${before[0].id}' and status='PENDING'`)[0] };
  const blocked = await guardWrites(page, [/\/price-adjust\//]);   // 生成接口路径未在 api.md 登记 ⇒ 限定在 price-adjust 前缀内
  await page.goto('/pricing'); await page.waitForLoadState('networkidle').catch(() => {});
  await page.locator('input[placeholder="搜索客户"]').first().fill(CUST.name);
  await page.locator('.ant-list-item').filter({ hasText: CUST.name }).first().click();
  await page.locator('.ant-tabs-tab').filter({ hasText: '价格调整策略' }).first().click();
  const genBtn = page.getByRole('button', { name: /立即生成/ }).first();
  await expect(genBtn).toBeVisible({ timeout: 30_000 });
  await shot(page, 'R1-0-生成前');
  const respP = page.waitForResponse(r => r.request().method() === 'POST' && /\/api\/cpq\/price-adjust\//.test(r.url()), { timeout: 120_000 });
  await genBtn.click();
  const confirm = page.locator('.ant-modal:visible, .ant-popover:visible').last();
  if (await confirm.isVisible({ timeout: 3000 }).catch(() => false)) {
    await shot(page, 'R1-0-生成确认框');
    await confirm.getByRole('button', { name: /确\s*定|确\s*认|生\s*成/ }).last().click();
  }
  const resp = await respP;
  T0 = Date.now();
  R.gen.response = { url: resp.url(), status: resp.status(), body: (await resp.text()).slice(0, 2000), t0: new Date(T0).toISOString() };
  R.gen.request = { method: resp.request().method(), url: resp.url(), postData: resp.request().postData() };   // R4 · AC-20 复用同一请求
  evid('生成请求.json', R.gen.request);
  const after = pendingVersion();
  expect(after.length).toBe(1);
  expect(after[0].id, '应生成了新的待处理版本').not.toBe(before[0].id);
  V = after[0]; R.V1 = V; save();
  expect(blocked, `被拦的写请求：${blocked}`).toEqual([]);
});

test('AC-1 · T₀ 后 ≤15 秒搜得到 · 静态「未计算」+「计算」· 金额「—」· 共 N 条其中 M 条未计算', async ({ page }) => {
  await apiLogin(page);
  const blocked = await guardWrites(page, []);
  await gotoReviews(page);
  let body: any; let found = false; let tFound = 0;
  while (Date.now() - T0 < 60_000) {
    body = await search(page, M.ac1);
    if (await rowOf(page, M.ac1, V.version_no).isVisible().catch(() => false)) { found = true; tFound = Date.now(); break; }
    await page.waitForTimeout(1000);
  }
  const row = rowOf(page, M.ac1, V.version_no);
  R.ac1 = { searchable: found, msAfterT0: found ? tFound - T0 : null };
  expect(found, `T₀ 后 60 秒内都搜不到 ${M.ac1}`).toBe(true);
  const status = await cellText(page, row, '比对状态');
  const amounts = [await cellText(page, row, '报价侧成本'), await cellText(page, row, '核价侧成本'), await cellText(page, row, '差异')];
  const spinInRow = await row.locator('.ant-spin').count();
  const hint = (await page.getByText(/共\s*\d+\s*条，其中/).first().innerText().catch(() => '')).replace(/\s+/g, '');
  // 提示条的 N/M 与「同一请求」的接口返回比：重新无关键词查一次，取响应与页面同时刻
  const listBody = await search(page, '');
  const hintAll = (await page.getByText(/共\s*\d+\s*条，其中/).first().innerText().catch(() => '')).replace(/\s+/g, '');
  const mm = /共(\d+)条，其中(\d+)条未计算/.exec(hintAll);
  R.ac1 = { ...R.ac1, status, amounts, spinInRow, hintWithKeyword: hint, hintAll,
    api: { totalElements: listBody.totalElements, notComputedTotal: listBody.notComputedTotal },
    sqlNow: sql(`select count(*) filter (where budget_status in ('QUEUED','COMPUTING'))::int m, count(*)::int n from material_price_review where version_id='${V.id}' and status='PENDING'`)[0],
    row: review(V.id, M.ac1)[0] };
  await shot(page, 'AC-1-列表'); save();
  expect(R.ac1.msAfterT0, 'AC-1：T₀ 后 ≤15 秒搜得到').toBeLessThanOrEqual(15_000);
  expect(status).toContain('未计算'); expect(status).toContain('计算');
  expect(spinInRow, '「未计算」不转圈').toBe(0);
  for (const a of amounts) { expect(a, `金额列应为「—」不为 0：${amounts}`).toMatch(/^—/); expect(a).not.toMatch(/^0(\.0*)?$/); }
  expect(mm, `顶部提示应为「共 N 条，其中 M 条未计算」：${hintAll}`).toBeTruthy();
  expect(Number(mm![1])).toBe(listBody.totalElements);
  expect(Number(mm![2])).toBe(listBody.notComputedTotal);
  expect(listBody.notComputedTotal, 'M > 0').toBeGreaterThan(0);
  expect(blocked).toEqual([]);
});

test('AC-4 · 「只看标红」不把未计算当未超阈值 + 提示「另有 M 个料号尚未计算」', async ({ page }) => {
  await apiLogin(page);
  const blocked = await guardWrites(page, []);
  await gotoReviews(page);
  const respP = page.waitForResponse(r => /\/reviews\?.*breachedOnly=true/.test(r.url()), { timeout: 60_000 });
  await page.getByText('只看标红', { exact: false }).first().click();
  const j = await (await respP).json(); const body = j?.data ?? j;
  await page.waitForTimeout(500);
  const rowsTxt = await page.locator('tr.ant-table-row').allInnerTexts();
  const tip = (await page.getByText(/另有\s*\d+\s*个料号\s*尚未计算/).first().innerText().catch(() => '')).replace(/\s+/g, '');
  const mm = /另有(\d+)个料号尚未计算，不参与本次筛选/.exec(tip);
  R.ac4 = { excludedByNotComputed: body.excludedByNotComputed, tip, rowsShown: rowsTxt.length,
    rowsWithNotComputed: rowsTxt.filter(t => t.includes('未计算')).length };
  await shot(page, 'AC-4-只看标红'); save();
  expect(rowsTxt.length, '前置：标红筛选结果非空（否则「没有未计算行」是空验证）').toBeGreaterThan(0);
  expect(R.ac4.rowsWithNotComputed).toBe(0);
  expect(mm, `提示文案：${tip}`).toBeTruthy();
  expect(Number(mm![1])).toBe(body.excludedByNotComputed);
  expect(body.excludedByNotComputed).toBeGreaterThan(0);
  expect(blocked).toEqual([]);
});

async function openDrawerTimed(page: Page, mat: string) {
  const before = review(V.id, mat)[0];
  expect(before?.budget_status, `前置：${mat} 在 V1 中应仍为未计算（QUEUED）；已被后台算到 = 窗口错过，不是产品缺陷，停下报主线`).toBe('QUEUED');
  await search(page, mat);
  const row = rowOf(page, mat, V.version_no);
  await expect(row).toBeVisible();
  // 后台是否在算同一张单（AC-5 要求记录）：该依据单组最近 5 秒内有无行被写
  const busy = sql(`select count(*)::int n, max(updated_at)::text last from material_price_review where version_id='${V.id}'
    and basis_quotation_id=(select basis_quotation_id from material_price_review where version_id='${V.id}' and material_no='${mat}')
    and budget_status in ('READY','FAILED','COMPUTING') and updated_at > now() - interval '5 seconds'`)[0];
  const t0 = Date.now();
  await row.locator('a').filter({ hasText: mat }).first().click();
  const d = drawerOf(page);
  await expect(d).toBeVisible();
  const sawComputing = await d.getByText(/正在计算/).first().isVisible({ timeout: 2000 }).catch(() => false);
  await expect(d.getByText(/能不能接受/).first()).toBeVisible({ timeout: 90_000 });
  await expect(d.getByText(/正在计算/)).toHaveCount(0, { timeout: 90_000 });
  const ms = Date.now() - t0;
  return { before, busy, ms, sawComputing, d, row };
}

test('AC-5 ① · 0628 料号点开抽屉 ≤5 秒算完 + 缺数据两侧 + 写回', async ({ page }) => {
  await apiLogin(page);
  const blocked = await guardWrites(page, [W_COMPUTE]);
  await gotoReviews(page);
  const r = await openDrawerTimed(page, M.ac5a);
  const dText = (await r.d.innerText()).replace(/\s+/g, ' ');
  await shot(r.d, 'AC-5-1-抽屉算完');
  await r.d.locator('.ant-drawer-close').first().click();
  await page.waitForTimeout(800);
  const status = await cellText(page, rowOf(page, M.ac5a, V.version_no), '比对状态');
  R.ac5a = { mat: M.ac5a, ms: r.ms, sawComputing: r.sawComputing, bgBusySameQuote: r.busy, statusAfterClose: status,
    db: review(V.id, M.ac5a)[0], missingBoth: /缺数据：两侧/.test(dText) };
  await shot(page, 'AC-5-1-关闭后列表'); save();
  expect(r.ms, 'AC-5①：≤5 秒').toBeLessThanOrEqual(5000);
  expect(R.ac5a.missingBoth, '抽屉比对列应显示「—（缺数据：两侧）」').toBe(true);
  expect(status).not.toContain('未计算');
  expect(R.ac5a.db.budget_status).toBe('READY');
  expect(blocked).toEqual([]);
});

test('AC-5 ② + AC-6 · 0842 料号取值非空并写回；「重算」后逐位相同', async ({ page }) => {
  await apiLogin(page);
  const blocked = await guardWrites(page, [W_COMPUTE, W_RECOMPUTE]);
  await gotoReviews(page);
  const r = await openDrawerTimed(page, M.ac5b);
  await shot(r.d, 'AC-5-2-抽屉算完');
  const cols = await r.d.locator('tr').filter({ hasText: /产品总价|col-default/ }).first().innerText().catch(() => '');
  await r.d.locator('.ant-drawer-close').first().click();
  await page.waitForTimeout(800);
  const listAmount = await cellText(page, rowOf(page, M.ac5b, V.version_no), '报价侧成本');
  const v1 = review(V.id, M.ac5b)[0];
  R.ac5b = { mat: M.ac5b, ms: r.ms, bgBusySameQuote: r.busy, drawerRow: cols, listAmount, db: v1 };
  await shot(page, 'AC-5-2-关闭后列表'); save();
  expect(v1.budget_status).toBe('READY');
  expect(v1.qc, '报价·现非空').not.toBeNull(); expect(v1.qa, '报价·调整后非空').not.toBeNull();
  expect(listAmount, '关闭后列表该行出现金额').not.toMatch(/^—/);
  // AC-6：既有「重算」入口（工具栏「重算预算」）→ 等回到 READY（updated_at 晚于点击）→ V2 与 V1 逐位相同
  const row = rowOf(page, M.ac5b, V.version_no);
  await row.locator('input[type=checkbox]').first().check();
  const tClick = sql<{ t: string }>(`select now()::text t`)[0].t;
  await page.getByRole('button', { name: /重算预算/ }).first().click();
  const pc = page.locator('.ant-popover:visible, .ant-modal:visible').last();
  if (await pc.isVisible({ timeout: 2000 }).catch(() => false)) await pc.getByRole('button', { name: /确\s*定|确\s*认/ }).last().click();
  let v2: any;
  for (let i = 0; i < 180; i++) {
    v2 = sql(`select r.budget_status, r.updated_at::text, (r.updated_at > '${tClick}'::timestamptz) fresh, c.quote_adjusted::text qa, c.costing_adjusted::text ca, c.diff_adjusted::text da
      from material_price_review r left join material_price_review_column c on c.review_id=r.id and c.column_id='col-default'
      where r.version_id='${V.id}' and r.material_no='${M.ac5b}'`)[0];
    if (v2.fresh && ['READY', 'FAILED'].includes(v2.budget_status)) break;
    await page.waitForTimeout(1000);
  }
  R.ac6 = { tClick, V1: { qa: v1.qa, ca: v1.ca, da: v1.da }, V2: v2 }; save();
  expect(v2.fresh, '重算确实发生（updated_at 晚于点击）').toBe(true);
  expect(v2.budget_status).toBe('READY');
  expect(v1.qa).not.toBeNull();
  // numeric::text 全位数文本相等 ⇔ numeric 相等（同列同 scale）
  expect({ qa: v2.qa, ca: v2.ca, da: v2.da }).toEqual({ qa: v1.qa, ca: v1.ca, da: v1.da });
  expect(blocked).toEqual([]);
});

test('AC-21 · 两个窗口同时点「计算」⇒ 只算一次、一条审核行、READY 且非空', async ({ browser }) => {
  const mat = M.ac21;
  const pre = review(V.id, mat)[0];
  expect(pre?.budget_status, `前置：${mat} 应仍未计算（窗口错过则停下报主线）`).toBe('QUEUED');
  const ctxs = await Promise.all([browser.newContext({ baseURL: BASE_URL }), browser.newContext({ baseURL: BASE_URL })]);
  const pages = await Promise.all(ctxs.map(c => c.newPage()));
  for (const p of pages) { await apiLogin(p); await guardWrites(p, [W_COMPUTE]); await gotoReviews(p); await search(p, mat); }
  const links = pages.map(p => rowOf(p, mat, V.version_no).getByText('计算', { exact: true }).first());
  for (const l of links) await expect(l).toBeVisible();
  const off = logSize();
  const posts: number[] = [];
  pages.forEach((p, i) => p.on('response', r => { if (W_COMPUTE.test(r.url())) posts.push(r.status()); }));
  await Promise.all(links.map(l => l.click()));
  let fin: any;
  for (let i = 0; i < 90; i++) { fin = review(V.id, mat)[0]; if (['READY', 'FAILED'].includes(fin.budget_status)) break; await pages[0].waitForTimeout(1000); }
  await pages[0].waitForTimeout(2000);
  const log = logSince(off);
  const basisLine = sql<{ id: string }>(`select li.id::text from quotation_line_item li join quotation q on q.id=li.quotation_id
     where q.quotation_number='${Q0842}' and li.product_part_no_snapshot='${mat}' order by li.sort_order nulls last, li.id limit 1`)[0]?.id;
  // ⚠️ 计数口径（未验证）：以依据行 id + dryRun=true 的升版日志行计次（[perf] upgrade 埋点见 证据/立项期实测 §3）。
  //    若该埋点不含行 id，本断言会假红（计 0）而非假绿 —— 跑前先在 R1-0 之后的日志里确认格式，对不上报主线改口径。
  const dryRunLines = log.split('\n').filter(l => !!basisLine && l.includes(basisLine) && /dryRun=true/.test(l));
  R.ac21 = { mat, postStatuses: posts, final: fin, reviewRows: sql(`select count(*)::int n from material_price_review where version_id='${V.id}' and material_no='${mat}'`)[0].n,
    basisLine, dryRunLogLines: dryRunLines.length, dryRunSample: dryRunLines.slice(0, 5) };
  for (const [i, p] of pages.entries()) await shot(p, `AC-21-窗口${i + 1}`);
  evid('AC-21-日志片段.log', log.split('\n').filter(l => l.includes(basisLine || '~') || l.includes(mat)).join('\n'));
  save();
  expect(posts.length, '两个窗口都发出了 compute-now（否则不是「同时点」）').toBe(2);
  expect(R.ac21.reviewRows).toBe(1);
  expect(fin.budget_status).toBe('READY');
  expect(fin.qa, '金额非空').not.toBeNull();
  expect(R.ac21.dryRunLogLines, '只发生一次试算（依据行 dryRun 日志条数）').toBe(1);
  // 「与 AC-13 参照里同一料号的值逐位相同」在 R3 后由 ac13_tool 对比补判（R1-界面结果.json 已记值）
  await Promise.all(ctxs.map(c => c.close()));
});

test('AC-8 + AC-9 · 同页勾 3 未计算 + 2 已计算 → 逐条算 → 确认框 → 取消', async ({ page }) => {
  await apiLogin(page);
  const blocked = await guardWrites(page, [W_COMPUTE, W_IMPACT]);   // 🚫 approve 不在白名单 —— 即使误点「确认」也会被拦
  await gotoReviews(page);
  // 先把 2 个算好（「计算」链接）
  for (const m of M.ac8computed) {
    expect(review(V.id, m)[0]?.budget_status, `前置：${m} 应仍未计算`).toBe('QUEUED');
    await search(page, m);
    await rowOf(page, m, V.version_no).getByText('计算', { exact: true }).first().click();
    for (let i = 0; i < 90 && review(V.id, m)[0].budget_status !== 'READY'; i++) await page.waitForTimeout(1000);
  }
  for (const m of M.ac8pending) expect(review(V.id, m)[0]?.budget_status, `前置：${m} 应仍未计算（窗口错过 ⇒ 停下报主线）`).toBe('QUEUED');
  const all = [...M.ac8computed, ...M.ac8pending];
  const ptrBefore = sql(`select material_no, version_id::text, updated_at::text from material_price_version_ref where customer_no='${CUST.no}' and material_no in (${all.map(m => `'${m}'`).join(',')}) order by 1`);
  await search(page, M.ac8kw);
  for (const m of all) { const row = rowOf(page, m, V.version_no); await expect(row, `同一页应出现 ${m}`).toBeVisible(); await row.locator('input[type=checkbox]').first().check(); }
  await expect(page.getByText(/已选\s*5\s*项/).first()).toBeVisible();
  const approveBtn = page.getByRole('button', { name: /通过并升版/ }).first();
  const rejectBtn = page.getByRole('button', { name: /驳\s*回/ }).first();
  const hint = (await page.getByText(/其中\s*\d+\s*项未计算/).first().innerText().catch(() => '')).replace(/\s+/g, '');
  await rejectBtn.hover({ force: true });
  const rejectTip = (await page.locator('.ant-tooltip:visible, [role=tooltip]:visible').last().innerText().catch(() => '')).replace(/\s+/g, '');
  await shot(page, 'AC-8-1-已勾选');
  R.ac8 = { hint, rejectTip, approveEnabled: await approveBtn.isEnabled(), rejectEnabled: await rejectBtn.isEnabled() };
  expect(R.ac8.approveEnabled).toBe(true);
  expect(hint).toContain('其中3项未计算，点通过会先计算');
  expect(R.ac8.rejectEnabled).toBe(false);
  expect(rejectTip).toContain('含3项尚未计算金额，需先计算才能驳回');
  // ② 点通过 → 逐条进度
  const tClick = sql<{ t: string }>(`select now()::text t`)[0].t;
  const impactP = page.waitForResponse(r => W_IMPACT.test(r.url()), { timeout: 180_000 });
  await approveBtn.click();
  const prog = page.locator('.ant-modal:visible').last();
  const progSamples: string[] = [];
  for (let i = 0; i < 40; i++) {
    const t = (await prog.innerText().catch(() => '')).replace(/\s+/g, ' ');
    progSamples.push(t.slice(0, 400));
    if (/通过前影响面确认/.test(t)) break;
    if (i === 1) await shot(page, 'AC-8-2-逐条进度');
    await page.waitForTimeout(250);
  }
  const impactBody = await (await impactP).json().then(j => j?.data ?? j);
  const modal = page.locator('.ant-modal:visible').filter({ hasText: '通过前影响面确认' });
  await expect(modal).toBeVisible({ timeout: 120_000 });
  const mText = (await modal.innerText()).replace(/\s+/g, ' ');
  await shot(modal, 'AC-8-3-确认框');
  const jobsAfter = sql(`select id::text, triggered_at::text from material_price_update_job where customer_no='${CUST.no}' and version_id='${V.id}' and triggered_at >= '${tClick}'::timestamptz`);
  R.ac8 = { ...R.ac8, tClick, progSamples, sawProgress: progSamples.some(s => /正在计算\s*\d\s*\/\s*3/.test(s)),
    sawRowStates: ['等待', '计算中', '已完成'].filter(k => progSamples.some(s => s.includes(k))),
    modalBlocks: { 料号数: mText.includes('料号数'), 单数: /将更新的单数/.test(mText), 版本推进路径: mText.includes('版本推进路径'),
      按状态分组: /按状态分组/.test(mText), 不会被更新的单: /不会被更新/.test(mText), 跌破预警线: /跌破预警线/.test(mText), 各料号金额: /各料号金额/.test(mText) },
    amountsTableHasAll5: all.every(m => mText.includes(m)), impact: impactBody, jobsAfterClick: jobsAfter };
  save();
  expect(R.ac8.sawProgress, `应看到「正在计算 n/3」：${JSON.stringify(progSamples.slice(0, 5))}`).toBe(true);
  expect(R.ac8.amountsTableHasAll5).toBe(true);
  expect(jobsAfter, 'AC-8④：此刻本次版本、点击之后无新更新批次').toEqual([]);
  // 🔎「现网既有区块一个不少」：不会被更新的单 / 跌破预警线 是数据条件区块 —— 期望值由 SQL 与 impact 响应给出后在报告里逐项核（此处只记录）
  // AC-9 · 取消
  await modal.getByRole('button', { name: /取\s*消/ }).first().click();
  await page.waitForTimeout(1500);
  await shot(page, 'AC-9-取消后');
  const after = all.map(m => review(V.id, m)[0]);
  const ptrAfter = sql(`select material_no, version_id::text, updated_at::text from material_price_version_ref where customer_no='${CUST.no}' and material_no in (${all.map(m => `'${m}'`).join(',')}) order by 1`);
  const jobs2 = sql(`select id::text from material_price_update_job where customer_no='${CUST.no}' and version_id='${V.id}' and triggered_at >= '${tClick}'::timestamptz`);
  R.ac9 = { after, ptrBefore, ptrAfter, jobs: jobs2 }; save();
  expect(jobs2).toEqual([]);
  for (const a of after) expect(a.status).toBe('PENDING');
  expect(ptrAfter).toEqual(ptrBefore);
  for (const m of M.ac8pending) { const a = after.find((x: any) => x && review(V.id, m)[0].id === x.id); expect(a?.budget_status, `${m} 保留已计算`).toBe('READY'); }
  expect(blocked, `被拦的写请求（含误点的 approve）：${blocked}`).toEqual([]);
});

test.afterAll(async () => { save(); });
