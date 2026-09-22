/**
 * task-260920 · S-2（正泰 · S-全局）· R2~R4 界面 / 接口步骤（AC-13 第 2 份 · AC-13 R3 · AC-23 · AC-26 · AC-10 · AC-15 · AC-20 · Q-1 收尾）
 *
 * 用例只从 需求文档.md ③ AC 原文 + test.md §3 跑批表 + api.md 派生；选择器**未验证**。
 * 每次只跑一步：S2_STEP=<步骤名>（步骤之间要重启临时后端 / 报批 / 备份，由人按跑批表串起来，🚫 一把跑完）。
 *   R2          🚦S2_ALLOW=R2-APPROVED：生成 V2 → 不做任何界面操作 → 等跑完（之后在 shell 跑 AC-13-S2-哈希指纹.sql 取 H2/F2 并导出）
 *   R3          冷启动后：按 S2_SAMPLE（ac13_tool.py sample 的输出）逐条串行重算 V2（S2_VERSION_ID），记 R3 开始时刻（之后 ac13_tool compare）
 *   R4-AC23     打开三张大单编辑页，记 页签数 / 各页签行数 / 「加载中…」数；PW_BASE_URL=5230（新）与 5174（旧，S2_AC23_OLD=1）各跑一次，全程拦截一切写请求
 *   R4-AC26     🚦S2_ALLOW=R4-APPROVED + 已备份：生成 V3 → 趁后台在算 0842 组 → 「计算」2 个 → 通过 → 比对 → 等跑完 → 该组其余行串行重算比对
 *   R4-AC10-15  🚦同上：AC-10（2 个，比确认框金额）、AC-15（3 个，比后台预算）
 *   R4-AC20     🚦同上（跑批表最后一步）：点「计算」未算完时生成 V4 → 四条断言 → **等 V4 后台全部算完**（Q-1）才结束
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import {
  R, CUST, Q0628, Q0629, Q0842, BASE_URL, BACKEND_PORT, BACKEND_LOG, WORKTREE, evid, save, shot, sql, pendingVersion, review,
  logSize, logSince, listenerCwd, assertNoOtherPlaywright, apiLogin, guardWrites, W_COMPUTE, W_RECOMPUTE, W_IMPACT,
  gotoReviews, search, rowOf, drawerOf, generateViaUi, waitLoopDone, serialRecompute, basisSubtotal, approveUi, waitJob,
} from './task260920-s2.helpers';

const STEP = process.env.S2_STEP || '';
const OUT = `${STEP || 'none'}-结果.json`;
const matsEnv = (k: string, d: string[]) => (process.env[k] ? process.env[k]!.split(',').map(s => s.trim()).filter(Boolean) : d);
/**
 * 默认料号 = 用户裁决 D-14（「A：全用 0842 的 PERF600」）后按只读 SQL 实时选出（准备/D14-选料号-输出.txt，2026-09-22 06:12:27 UTC）：
 *   依据行升版前 subtotal = 0、上一版预算「调整后」= 9118.2、只挂 QT-20260911-0842 一张活单一行、无版本指针；三组互不重叠。
 *   AC-26 取 0842 组第 817/818 位（「趁后台在算 Q」时仍未算到）；AC-10 取第 230/231、AC-15 取第 240~242 位（V3 生成后先被后台循环算到）。
 *   搜索词各自恒一页（前缀命中 10 个）：PERF600-B0059 / PERF600-B0001 / PERF600-B0002。
 * 数据若漂移（R4 前复跑 准备/D14-选料号.sql），用 S2_AC10_MATS / S2_AC15_MATS / S2_AC26_MATS 覆盖，🚫 改这里的默认值而不留痕。
 */
const AC26 = matsEnv('S2_AC26_MATS', ['PERF600-B00598', 'PERF600-B00599']);
const AC10 = matsEnv('S2_AC10_MATS', ['PERF600-B00011', 'PERF600-B00012']);
const AC15 = matsEnv('S2_AC15_MATS', ['PERF600-B00021', 'PERF600-B00022', 'PERF600-B00023']);
const AC20 = process.env.S2_AC20_MAT || 'T260907T-B01800';   // 0628 大单料号：单次试算最长，最容易「在它算完之前」插入生成

test.describe.configure({ mode: 'serial' });
const oldSide = STEP === 'R4-AC23' && process.env.S2_AC23_OLD === '1';

test.beforeAll(async () => {
  assertNoOtherPlaywright();
  const fCwd = listenerCwd(Number(new URL(BASE_URL).port));
  R.identity = { step: STEP, frontend: { url: BASE_URL, cwd: fCwd }, backendPort: BACKEND_PORT, log: BACKEND_LOG, oldSide };
  if (!oldSide) {
    expect(fCwd.startsWith(WORKTREE), `前端必须是 worktree 临时 vite：${fCwd}`).toBe(true);
    expect(listenerCwd(BACKEND_PORT).startsWith(WORKTREE), '后端必须是 worktree 临时后端').toBe(true);
  }
  const h = new Date().getHours() * 60 + new Date().getMinutes();
  expect(h < 17 * 60 + 30 || h > 18 * 60 + 30, 'E-2：本机 17:30~18:30 禁跑').toBe(true);
  save(OUT);
});

test('R2 · 生成 V2 → 不做任何界面操作 → 等跑完', async ({ page }) => {
  test.skip(STEP !== 'R2' || process.env.S2_ALLOW !== 'R2-APPROVED', '非本步或未报批');
  await apiLogin(page);
  const blocked = await guardWrites(page, [/\/price-adjust\//]);
  const g = await generateViaUi(page, 'R2');
  R.R2 = { gen: g };
  await page.goto('about:blank');                          // 生成后离开业务页面：本轮「不做任何界面操作」
  R.R2.done = await waitLoopDone(page, g.V.id); save(OUT);
  expect(R.R2.done.unfinished, 'AC-12 口径：跑完无 QUEUED/COMPUTING').toBe(0);
  expect(blocked).toEqual([]);
});

test('R3 · 冷缓存逐条串行重算抽样料号（「重算」入口）', async ({ page }) => {
  test.skip(STEP !== 'R3', '非本步');
  const ver = process.env.S2_VERSION_ID!, sampleFile = process.env.S2_SAMPLE!;
  expect(ver && sampleFile && fs.existsSync(sampleFile), '需要 S2_VERSION_ID（V2）与 S2_SAMPLE（R2 导出件上 ac13_tool sample 的输出）').toBeTruthy();
  const mats = fs.readFileSync(sampleFile, 'utf8').split('\n').filter(l => l.includes('\t')).map(l => l.split('\t')[1].trim());
  expect(mats.length, '抽样非空（200+20）').toBe(220);
  await apiLogin(page);
  const r3Start = sql<{ t: string }>(`select now()::text t`)[0].t;
  R.R3 = { r3Start, n: mats.length }; evid('R3-开始时刻.txt', r3Start + '\n'); save(OUT);
  const res = await serialRecompute(page, ver, mats, x => fs.appendFileSync(`${process.env.S2_SAMPLE}.progress.jsonl`, JSON.stringify(x) + '\n'));
  R.R3.bad = res.filter(x => !x.fresh || x.http >= 400 || !['READY', 'FAILED'].includes(x.budget_status));
  R.R3.failed = res.filter(x => x.budget_status === 'FAILED').map(x => x.m);
  save(OUT);
  expect(R.R3.bad, '每条都确实重算过且结束').toEqual([]);
});

test('R4 · AC-23 三张大单编辑页：页签数 / 各页签行数 / 「加载中…」', async ({ page }) => {
  test.skip(STEP !== 'R4-AC23', '非本步');
  await apiLogin(page);
  const blocked = await guardWrites(page, []);            // 打开编辑页可能触发自动保存 —— 一律拦下并记录（autosave 被拦不影响渲染计数）
  const side = oldSide ? 'old-8081' : 'new-8130';
  R.ac23 = { side, quotes: {} };
  for (const qn of [Q0628, Q0629, Q0842]) {
    const qid = sql<{ id: string }>(`select id::text from quotation where quotation_number='${qn}'`)[0].id;
    await page.goto(`/quotations/${qid}/edit`);
    await page.waitForLoadState('networkidle', { timeout: 180_000 }).catch(() => {});
    await page.waitForTimeout(5000);
    const tabs = page.locator('.ant-tabs-tab:visible');
    const tabNames = (await tabs.allInnerTexts()).map(t => t.replace(/\s+/g, ''));
    const perTab: Record<string, any> = {};
    for (let i = 0; i < tabNames.length; i++) {
      await tabs.nth(i).click().catch(() => {});
      await page.waitForTimeout(1500);
      const pane = page.locator('.ant-tabs-tabpane-active').last();
      perTab[tabNames[i]] = {
        domRows: await pane.locator('tr.ant-table-row').count(),
        totalText: (await pane.getByText(/共\s*\d+\s*(条|行|项)/).first().innerText().catch(() => '')).replace(/\s+/g, ''),
        loading: await page.getByText('加载中…').count(),
      };
    }
    R.ac23.quotes[qn] = { tabCount: tabNames.length, tabNames, perTab, loadingTotal: await page.getByText('加载中…').count() };
    await shot(page, `AC-23-${side}-${qn}`);
    save(OUT);
  }
  R.ac23.blockedWrites = blocked; save(OUT);
  for (const qn of [Q0628, Q0629, Q0842]) expect(R.ac23.quotes[qn].tabCount, `${qn} 页签数非 0（否则比较是空对空）`).toBeGreaterThan(0);
  // 判据（新旧两份结果逐项相等 + 「加载中…」= 0）在两侧都跑完后由报告比对两个 JSON；⚠️ 虚拟滚动下 domRows 只是可见行数，两侧同口径才可比
});

test('R4 · AC-26 后台在算 0842 组时「计算」2 个 → 通过 → 逐位 + 锁日志 + 其余行串行复算', async ({ page }) => {
  test.skip(STEP !== 'R4-AC26' || process.env.S2_ALLOW !== 'R4-APPROVED', '非本步或未报批');
  await apiLogin(page);
  const blocked = await guardWrites(page, [/\/price-adjust\//]);
  const g = await generateViaUi(page, 'V3'); const V = g.V;
  R.ac26 = { gen: g, mats: AC26 };
  // 等到「后台正在算 0842 组」：该组已有 READY、且还有 QUEUED，且目标两个料号仍 QUEUED
  let st: any;
  for (let i = 0; i < 600; i++) {
    st = sql(`select count(*) filter (where budget_status='READY')::int ready, count(*) filter (where budget_status='QUEUED')::int queued,
       count(*) filter (where budget_status='COMPUTING')::int computing from material_price_review
      where version_id='${V.id}' and basis_quotation_id=(select id from quotation where quotation_number='${Q0842}')`)[0];
    if (st.ready > 0 && st.queued > 0) break;
    await page.waitForTimeout(1000);
  }
  R.ac26.groupStateAtStart = st;
  for (const m of AC26) expect(review(V.id, m)[0]?.budget_status, `前置：${m} 仍未计算（错过 ⇒ 报主线）`).toBe('QUEUED');
  await gotoReviews(page);
  for (const m of AC26) {
    await search(page, m);
    await rowOf(page, m, V.version_no).getByText('计算', { exact: true }).first().click();
    for (let i = 0; i < 90 && review(V.id, m)[0].budget_status !== 'READY'; i++) await page.waitForTimeout(1000);
  }
  const budget = Object.fromEntries(AC26.map(m => [m, review(V.id, m)[0]]));
  const pre26 = basisSubtotal(V.id, AC26);                    // D-14：升版前依据行金额（应为 0，且 ≠ 预算）
  const off = logSize();
  const a = await approveUi(page, V.version_no, AC26[0].slice(0, -1), AC26, true, 'AC-26');   // ⚠️ 搜索词取公共前缀，跑前核「同页」
  R.ac26.approve = a; R.ac26.groupStateAtApprove = sql(`select count(*) filter (where budget_status='QUEUED')::int queued from material_price_review
      where version_id='${V.id}' and basis_quotation_id=(select id from quotation where quotation_number='${Q0842}')`)[0];
  R.ac26.job = await waitJob(a.jobId!);
  const sub = basisSubtotal(V.id, AC26);
  R.ac26.compare = AC26.map(m => ({ m, budget_qa: budget[m].qa, pre: pre26.find((x: any) => x.material_no === m)?.subtotal, subtotal: sub.find((x: any) => x.material_no === m)?.subtotal }));
  const log = logSince(off);
  evid('AC-26-日志片段.log', log);
  R.ac26.lockLogLines = log.split('\n').filter(l => /lock|锁|wait|等待/i.test(l)).slice(0, 50);   // ⚠️ 关键字未验证，原样留全段日志供人工核
  save(OUT);
  expect(R.ac26.groupStateAtApprove.queued, '通过时后台仍在算 0842 组（否则不是 AC-26 的场景）').toBeGreaterThan(0);
  expect(R.ac26.job?.status).toBe('SUCCESS');
  for (const c of R.ac26.compare) {
    expect(c.budget_qa, `${c.m} 预算非空`).not.toBeNull();
    expect(c.pre, `${c.m} 前置（D-14）：升版前金额 ≠ 预算，否则比较无区分力`).not.toBe(c.budget_qa);
    expect(c.subtotal, `${c.m} 升版后 subtotal = 预算 quote_adjusted（Q-3）`).toBe(c.budget_qa);
    expect(c.subtotal, `${c.m} 升版后 ≠ 升版前（D-14）`).not.toBe(c.pre);
  }
  // ② 等 V3 跑完 → 该组其余「调整后非空」行：先快照循环算出的值，再逐条串行重算，逐位比
  R.ac26.done = await waitLoopDone(page, V.id);
  const loopVals = sql<any>(`select r.material_no m, c.quote_adjusted::text qa, c.costing_adjusted::text ca, c.diff_adjusted::text da
      from material_price_review r join material_price_review_column c on c.review_id=r.id
     where r.version_id='${V.id}' and r.status='PENDING' and r.basis_quotation_id=(select id from quotation where quotation_number='${Q0842}')
       and c.quote_adjusted is not null order by r.material_no, c.column_id`);
  const n = Number(process.env.S2_AC26_RECHECK_N || '0');   // 0 = 全部；时间不够时由主线定抽样数
  // 排除 AC-10 / AC-15 的料号：它们要保留「后台算出」的值给下一步（重算会覆盖它）
  const pick = [...new Set(loopVals.map(x => x.m))].filter(m => !AC26.includes(m) && !AC10.includes(m) && !AC15.includes(m)).slice(0, n || undefined);
  evid('AC-26-循环值快照.json', loopVals);
  await serialRecompute(page, V.id, pick);
  const after = sql<any>(`select r.material_no m, c.quote_adjusted::text qa, c.costing_adjusted::text ca, c.diff_adjusted::text da
      from material_price_review r join material_price_review_column c on c.review_id=r.id
     where r.version_id='${V.id}' and r.material_no in (${pick.map(m => `'${m}'`).join(',')}) order by r.material_no, c.column_id`);
  const before = loopVals.filter(x => pick.includes(x.m));
  R.ac26.recheck = { n: pick.length, diff: before.filter((b, i) => JSON.stringify(b) !== JSON.stringify(after[i])).slice(0, 20) };
  save(OUT);
  expect(pick.length, '复算样本非空').toBeGreaterThan(0);
  expect(R.ac26.recheck.diff).toEqual([]);
  expect(blocked).toEqual([]);
});

test('R4 · AC-10（确认框金额 = 升版后实际）+ AC-15（后台预算 = 升版后实际）', async ({ page }) => {
  test.skip(STEP !== 'R4-AC10-15' || process.env.S2_ALLOW !== 'R4-APPROVED', '非本步或未报批');
  await apiLogin(page);
  const blocked = await guardWrites(page, [/\/price-adjust\//]);
  const V = pendingVersion()[0];
  for (const m of [...AC10, ...AC15]) expect(review(V.id, m)[0]?.budget_status, `前置：${m} 已计算（READY）`).toBe('READY');
  // AC-10
  const b10 = Object.fromEntries(AC10.map(m => [m, review(V.id, m)[0]]));
  const pre10 = basisSubtotal(V.id, AC10);
  for (const m of AC10) expect(pre10.find((x: any) => x.material_no === m)?.subtotal, `${m} 前置（D-14）：升版前金额 ≠ 预算「调整后」`).not.toBe(b10[m].qa);
  const a10 = await approveUi(page, V.version_no, process.env.S2_AC10_KW || AC10[0].slice(0, -1), AC10, true, 'AC-10');
  const j10 = await waitJob(a10.jobId!);
  const s10 = basisSubtotal(V.id, AC10);
  R.ac10 = { mats: AC10, modalAmounts: a10.amounts, budget: b10, pre: pre10, job: j10, subtotal: s10 };
  save(OUT);
  expect(j10?.status).toBe('SUCCESS');
  for (const m of AC10) {
    const sub = s10.find((x: any) => x.material_no === m)?.subtotal;
    expect(sub, `${m} 升版后依据行 subtotal = 升版前预算 quote_adjusted`).toBe(b10[m].qa);
    expect(sub, `${m} 升版后 ≠ 升版前（D-14）`).not.toBe(pre10.find((x: any) => x.material_no === m)?.subtotal);
    // 确认框显示按 DISPLAY_SCALE(9) 去尾零 ⇒ 比「显示值」与 subtotal 按 9 位截断去尾零后的文本
    const shown = (a10.amounts[m].match(/-?\d[\d,]*\.?\d*/g) || []).map(s => s.replace(/,/g, ''));
    const want = String(Number(sub)).length ? sub!.replace(/(\.\d{0,9})\d*$/, '$1').replace(/\.?0+$/, '') : '';
    expect(shown, `${m} 确认框「调整后」应含 ${want}：${a10.amounts[m]}`).toContain(want);
  }
  // AC-15
  const b15 = Object.fromEntries(AC15.map(m => [m, review(V.id, m)[0]]));
  const pre15 = basisSubtotal(V.id, AC15);
  // AC-15 要「后台算出」的值：这些行的更新时间应早于任何人工重算，且 AC-26 复算已排除它们
  for (const m of AC15) expect(pre15.find((x: any) => x.material_no === m)?.subtotal, `${m} 前置（D-14）：升版前 ≠ 预算`).not.toBe(b15[m].qa);
  const a15 = await approveUi(page, V.version_no, process.env.S2_AC15_KW || AC15[0].slice(0, -1), AC15, true, 'AC-15');
  const j15 = await waitJob(a15.jobId!);
  const s15 = basisSubtotal(V.id, AC15);
  R.ac15 = { mats: AC15, budget: b15, pre: pre15, job: j15, subtotal: s15 }; save(OUT);
  expect(j15?.status).toBe('SUCCESS');
  for (const m of AC15) {
    const sub = s15.find((x: any) => x.material_no === m)?.subtotal;
    expect(sub, `${m} 逐位相同`).toBe(b15[m].qa);
    expect(sub, `${m} 升版后 ≠ 升版前（D-14）`).not.toBe(pre15.find((x: any) => x.material_no === m)?.subtotal);
  }
  expect(blocked).toEqual([]);
});

test('R4 · AC-20 在途试算时生成 V4 + Q-1 收尾（等 V4 全部算完）', async ({ page }) => {
  test.skip(STEP !== 'R4-AC20' || process.env.S2_ALLOW !== 'R4-APPROVED', '非本步或未报批');
  const genReqFile = process.env.S2_GEN_REQUEST!;          // R1 落盘的 生成请求.json
  expect(genReqFile && fs.existsSync(genReqFile), '需要 S2_GEN_REQUEST 指向 R1 的 生成请求.json').toBeTruthy();
  const gen = JSON.parse(fs.readFileSync(genReqFile, 'utf8'));
  await apiLogin(page);
  const blocked = await guardWrites(page, [/\/price-adjust\//]);
  const V3 = pendingVersion()[0];
  const r0 = review(V3.id, AC20)[0];
  expect(r0?.budget_status, `前置：${AC20} 仍未计算`).toBe('QUEUED');
  await gotoReviews(page); await search(page, AC20);
  const row = rowOf(page, AC20, V3.version_no);
  const computeRespP = page.waitForResponse(r => W_COMPUTE.test(r.url()), { timeout: 30_000 });
  await row.getByText('计算', { exact: true }).first().click();
  await computeRespP;                                        // 202 受理 = 已开始算
  const genRes = await page.request.fetch(gen.url, { method: 'POST', data: gen.postData ?? undefined, headers: { 'content-type': 'application/json' }, failOnStatusCode: false });
  const tGenReturned = sql<{ t: string }>(`select now()::text t`)[0].t;
  const V4 = pendingVersion()[0];
  await page.waitForTimeout(10_000);
  const rowAfter = sql<any>(`select r.status, r.budget_status, r.updated_at::text, (r.updated_at <= '${tGenReturned}'::timestamptz) committed_before_gen_return,
      (select max(c.created_at)::text from material_price_review_column c where c.review_id=r.id) col_created from material_price_review r where r.id='${r0.id}'`)[0];
  await page.waitForTimeout(10_000);
  const rowLater = sql<any>(`select updated_at::text, (select max(c.created_at)::text from material_price_review_column c where c.review_id=r.id) col_created from material_price_review r where r.id='${r0.id}'`)[0];
  // ④ 再点「计算」（页面不刷新，旧行仍在）→ 409 REVIEW_NOT_PENDING → 提示「该价格版本已被新版本取代」
  const again = page.waitForResponse(r => W_COMPUTE.test(r.url()), { timeout: 30_000 }).catch(() => null);
  await row.getByText(/计算|重算/).first().click().catch(() => {});
  const againResp = await again;
  const againBody = againResp ? await againResp.text() : null;
  const toast = await page.getByText('该价格版本已被新版本取代').first().isVisible({ timeout: 10_000 }).catch(() => false);
  await shot(page, 'AC-20-再点计算');
  R.ac20 = { mat: AC20, V3: V3.version_no, V4: V4.version_no, genStatus: genRes.status(), tGenReturned, rowAfter, rowLater,
    againStatus: againResp?.status() ?? null, againBody: againBody?.slice(0, 600), toast, whiteScreen: (await page.locator('body').innerText()).trim().length < 20 };
  save(OUT);
  expect(genRes.status(), '生成成功').toBeLessThan(300);
  expect(V4.id).not.toBe(V3.id);
  expect(rowAfter.committed_before_gen_return, '① 试算提交早于生成返回').toBe(true);
  expect(rowAfter.status, '② 最终为已作废').toBe('VOIDED');
  expect(rowLater, '③ 作废后无写入').toEqual({ updated_at: rowAfter.updated_at, col_created: rowAfter.col_created });
  expect(R.ac20.againStatus).toBe(409);
  expect(againBody).toContain('REVIEW_NOT_PENDING');
  expect(toast).toBe(true);
  expect(R.ac20.whiteScreen).toBe(false);
  // Q-1 收尾（主线裁决）：V4 后台试算全部算完才允许停临时后端
  R.drainV4 = await waitLoopDone(page, V4.id); save(OUT);
  expect(R.drainV4.unfinished, 'V4 无 QUEUED/COMPUTING 残留 ⇒ 才能停 8130').toBe(0);
  expect(blocked).toEqual([]);
});
