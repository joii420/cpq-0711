/**
 * task-260920 · S-2 · R4（D-17 次序）：GEN → AC26A（趁 0842 组在算：计算+通过）→ AC1015 → AC26B（0842 组算完后复算 20 个）→ AC20
 * 每步用 S2_STEP 选择，由人按次序串起来。🚦 写库步骤须 S2_ALLOW=R4-APPROVED。选择器未全部验证。
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import {
  R, Q0842, BASE_URL, evid, save, shot, sql, pendingVersion, review, logSize, logSince, apiLogin, guardWrites,
  gotoReviews, search, rowOf, generateViaUi, serialRecompute, basisSubtotal, approveUi, waitJob, waitLoopDone,
} from './task260920-s2.helpers';

const STEP = process.env.S2_STEP || '';
const OUT = `R4-${STEP}-结果.json`;
const OK = process.env.S2_ALLOW === 'R4-APPROVED';
const AC26 = ['PERF600-B00598', 'PERF600-B00599'];
const AC10 = ['PERF600-B00011', 'PERF600-B00012'];
const AC15 = ['PERF600-B00021', 'PERF600-B00022', 'PERF600-B00023'];
const EXCL = [...AC26, ...AC10, ...AC15, 'PERFHOT-B00291', 'PERFHOT-B00239'];
const q0842Group = (v: string) => `version_id='${v}' and basis_quotation_id=(select id from quotation where quotation_number='${Q0842}')`;

async function approveAndCheck(page: any, V: any, mats: string[], kw: string, tag: string) {
  for (let i = 0; i < 600; i++) { if (mats.every(m => review(V.id, m)[0]?.budget_status === 'READY')) break; await page.waitForTimeout(2000); }
  const budget = Object.fromEntries(mats.map(m => [m, review(V.id, m)[0]]));
  for (const m of mats) expect(budget[m]?.budget_status, `${m} 已 READY`).toBe('READY');
  const pre = basisSubtotal(V.id, mats);
  for (const m of mats) expect(pre.find((x: any) => x.material_no === m)?.subtotal, `${m} 前置：升版前 ≠ 预算`).not.toBe(budget[m].qa);
  const off = logSize();
  const a = await approveUi(page, V.version_no, kw, mats, true, tag);
  const job = await waitJob(a.jobId!);
  const sub = basisSubtotal(V.id, mats);
  const log = logSince(off);
  evid(`${tag}-日志片段.log`, log);
  const cmp = mats.map(m => ({ m, budget_qa: budget[m].qa, budget_status: budget[m].budget_status, budget_updated: budget[m].updated_at,
    pre: pre.find((x: any) => x.material_no === m)?.subtotal, after: sub.find((x: any) => x.material_no === m)?.subtotal,
    excelNull: sub.find((x: any) => x.material_no === m)?.excel_null, modal: a.amounts[m] }));
  return { approveStatus: a.status, jobId: a.jobId, job, cmp, lockLines: log.split('\n').filter(l => /price-adjust-lock/.test(l)).slice(0, 80) };
}

test.describe.configure({ mode: 'serial' });

test('R4-GEN · 生成 V3', async ({ page }) => {
  test.skip(STEP !== 'GEN' || !OK, '非本步或未报批');
  await apiLogin(page);
  const blocked = await guardWrites(page, [/\/price-adjust\/versions\/generate$/]);
  const g = await generateViaUi(page, 'R4-V3');
  R.gen = { V3: g.V, tSend: g.tSend, tReturned: g.tReturned, exchanges: g.exchanges.map((x: any) => ({ ...x, body: x.body?.slice(0, 400) })) };
  console.log(`[V3] ${g.V.version_no} ${g.V.id} sent=${g.tSend} returned=${g.tReturned}`);
  save(OUT); expect(blocked).toEqual([]);
});

test('R4-AC26A · 趁后台在算 0842 组：计算 598/599 → 通过 → 逐位 + 锁日志', async ({ page }) => {
  test.skip(STEP !== 'AC26A' || !OK, '非本步或未报批');
  test.setTimeout(900_000);
  await apiLogin(page);
  const blocked = await guardWrites(page, [/\/price-adjust\//]);
  const V = pendingVersion()[0];
  const st0 = sql(`select count(*) filter (where budget_status='READY')::int ready, count(*) filter (where budget_status='QUEUED')::int queued from material_price_review where ${q0842Group(V.id)}`)[0];
  for (const m of AC26) expect(review(V.id, m)[0]?.budget_status, `前置：${m} 仍未计算`).toBe('QUEUED');
  await gotoReviews(page);
  for (const m of AC26) {
    await search(page, m);
    await rowOf(page, m, V.version_no).getByText('计算', { exact: true }).first().click();
    for (let i = 0; i < 90 && review(V.id, m)[0].budget_status !== 'READY'; i++) await page.waitForTimeout(1000);
  }
  const r = await approveAndCheck(page, V, AC26, 'PERF600-B0059', 'AC-26');
  const st1 = sql(`select count(*) filter (where budget_status='QUEUED')::int queued, count(*) filter (where budget_status='COMPUTING')::int computing from material_price_review where ${q0842Group(V.id)}`)[0];
  R.ac26a = { V: V.version_no, groupAtStart: st0, groupAfterJob: st1, ...r }; save(OUT);
  expect(r.job?.status).toBe('SUCCESS');
  for (const c of r.cmp) { expect(c.after, `${c.m} 升版后 = 预算`).toBe(c.budget_qa); expect(c.after, `${c.m} ≠ 升版前`).not.toBe(c.pre); }
  expect(blocked).toEqual([]);
});

test('R4-AC1015 · 后台算到后分两批通过', async ({ page }) => {
  test.skip(STEP !== 'AC1015' || !OK, '非本步或未报批');
  test.setTimeout(1_200_000);
  await apiLogin(page);
  const blocked = await guardWrites(page, [/\/price-adjust\//]);
  const V = pendingVersion()[0];
  const r10 = await approveAndCheck(page, V, AC10, 'PERF600-B0001', 'AC-10');
  R.ac10 = r10; save(OUT);
  const r15 = await approveAndCheck(page, V, AC15, 'PERF600-B0002', 'AC-15');
  R.ac15 = r15; save(OUT);
  for (const r of [r10, r15]) { expect(r.job?.status).toBe('SUCCESS'); for (const c of r.cmp) { expect(c.after, `${c.m} = 预算`).toBe(c.budget_qa); expect(c.after, `${c.m} ≠ 升版前`).not.toBe(c.pre); } }
  expect(blocked).toEqual([]);
});

test('R4-AC26B · 0842 组算完后抽 20 个复算，与循环值逐位比', async ({ page }) => {
  test.skip(STEP !== 'AC26B' || !OK, '非本步或未报批');
  test.setTimeout(1_200_000);
  await apiLogin(page);
  const V = pendingVersion()[0];
  let st: any;
  for (let i = 0; i < 400; i++) { st = sql(`select count(*) filter (where status='PENDING' and budget_status in ('QUEUED','COMPUTING'))::int left_ from material_price_review where ${q0842Group(V.id)}`)[0]; if (st.left_ === 0) break; await page.waitForTimeout(3000); }
  expect(st.left_, '0842 组已算完').toBe(0);
  const loop = sql<any>(`select r.material_no m, r.budget_status bs, c.column_id, c.quote_current::text qc, c.quote_adjusted::text qa, c.costing_current::text cc, c.costing_adjusted::text ca, c.diff_adjusted::text da
     from material_price_review r join material_price_review_column c on c.review_id=r.id where ${q0842Group(V.id).replace(/version_id/g, 'r.version_id').replace('basis_quotation_id', 'r.basis_quotation_id')} and r.status='PENDING' and c.quote_adjusted is not null order by r.material_no, c.column_id`);
  const pool = [...new Set(loop.map(x => x.m))].filter(m => !EXCL.includes(m)).sort();
  // 固定种子等距抽 20 个（确定性：按料号排序后步长取样）
  const step = Math.max(1, Math.floor(pool.length / 20));
  const pick = pool.filter((_, i) => i % step === 0).slice(0, 20);
  evid('AC-26-②-循环值快照.json', loop.filter(x => pick.includes(x.m)));
  const tStart = sql<{ t: string }>(`select now()::text t`)[0].t;
  const res = await serialRecompute(page, V.id, pick);
  const after = sql<any>(`select r.material_no m, r.budget_status bs, c.column_id, c.quote_current::text qc, c.quote_adjusted::text qa, c.costing_current::text cc, c.costing_adjusted::text ca, c.diff_adjusted::text da
     from material_price_review r join material_price_review_column c on c.review_id=r.id where r.version_id='${V.id}' and r.material_no in (${pick.map(m => `'${m}'`).join(',')}) order by r.material_no, c.column_id`);
  const before = loop.filter(x => pick.includes(x.m));
  const diff = before.map((b, i) => [b, after[i]]).filter(([b, a]) => JSON.stringify(b) !== JSON.stringify(a));
  R.ac26b = { poolSize: pool.length, step, pick, tStart, recompute: res, diff, nonzero: before.filter(x => x.qa !== '0.000000000000').length };
  save(OUT);
  expect(pick.length).toBe(20);
  expect(res.every(x => x.fresh && x.budget_status === 'READY')).toBe(true);
  expect(diff).toEqual([]);
});

test('R4-AC20 · 点「计算」未算完时生成 V4', async ({ page, browser }) => {
  test.skip(STEP !== 'AC20' || !OK, '非本步或未报批');
  test.setTimeout(3_600_000);
  const mat = process.env.S2_AC20_MAT || 'T260907T-B01820';
  await apiLogin(page);
  const blocked = await guardWrites(page, [/\/price-adjust\//]);
  const V3 = pendingVersion()[0];
  const r0 = review(V3.id, mat)[0];
  expect(r0?.budget_status, `前置：${mat} 仍未计算（否则停下报主线）`).toBe('QUEUED');
  await gotoReviews(page); await search(page, mat);
  const row = rowOf(page, mat, V3.version_no);
  const tClickDb = sql<{ t: string }>(`select now()::text t`)[0].t;
  const computeRespP = page.waitForResponse(r => /\/compute-now$/.test(r.url()), { timeout: 30_000 });
  await row.getByText('计算', { exact: true }).first().click();
  const cr = await computeRespP;
  // 另一个通道（接口，同一登录会话）立即发生成请求
  const genT0 = sql<{ t: string }>(`select now()::text t`)[0].t;
  const gen = await page.request.post(`${BASE_URL}/api/cpq/price-adjust/versions/generate`, { data: { customerNo: 'CUST-0004', confirmSupersede: true }, failOnStatusCode: false });
  const genReturned = sql<{ t: string }>(`select now()::text t`)[0].t;
  const genBody = (await gen.text()).slice(0, 600);
  const V4 = pendingVersion()[0];
  await page.waitForTimeout(8000);
  const rowAfter = sql<any>(`select r.status, r.budget_status, r.updated_at::text, (r.updated_at <= '${genReturned}'::timestamptz) committed_before_gen_return,
      (select max(c.created_at)::text from material_price_review_column c where c.review_id=r.id) col_created, (select count(*) from material_price_review_column c where c.review_id=r.id)::int cols from material_price_review r where r.id='${r0.id}'`)[0];
  await page.waitForTimeout(10_000);
  const rowLater = sql<any>(`select updated_at::text, (select max(c.created_at)::text from material_price_review_column c where c.review_id=r.id) col_created, (select count(*) from material_price_review_column c where c.review_id=r.id)::int cols from material_price_review r where r.id='${r0.id}'`)[0];
  await shot(page, 'AC-20-作废后-未刷新');
  const rowTextBefore = (await row.innerText().catch(() => '<行已不在>')).replace(/\s+/g, ' ');
  // ④ 页面甲不刷新，再点「计算 / 重算」；没有可点的链接则如实记录，并经同一接口补发一次
  const againP = page.waitForResponse(r => /\/compute-now$/.test(r.url()), { timeout: 15_000 }).catch(() => null);
  const link = row.getByText(/^计算$|重算|重新计算/).first();
  const hasLink = await link.isVisible().catch(() => false);
  if (hasLink) await link.click().catch(() => {});
  let againResp = await againP; let via = hasLink ? 'ui' : 'none';
  let againStatus = againResp?.status() ?? null; let againBody = againResp ? await againResp.text() : null;
  if (!againResp) {
    const x = await page.request.post(`${BASE_URL}/api/cpq/price-adjust/reviews/${r0.id}/compute-now`, { failOnStatusCode: false });
    againStatus = x.status(); againBody = await x.text(); via = hasLink ? 'ui-no-request+api' : 'api';
  }
  const toast = await page.getByText('该价格版本已被新版本取代').first().isVisible({ timeout: 8000 }).catch(() => false);
  await shot(page, 'AC-20-再点计算后');
  R.ac20 = { mat, V3: V3.version_no, V4: V4.version_no, V4id: V4.id, tClickDb, computeNow: { status: cr.status(), body: (await cr.text()).slice(0, 300) },
    genT0, genReturned, genStatus: gen.status(), genBody, rowAfter, rowLater, rowTextBefore, hasLink, via, againStatus, againBody: againBody?.slice(0, 600), toast,
    bodyLen: (await page.locator('body').innerText()).trim().length };
  save(OUT);
  expect(gen.status()).toBeLessThan(300);
  expect(V4.id).not.toBe(V3.id);
  expect(rowAfter.committed_before_gen_return, '① 试算提交早于生成返回').toBe(true);
  expect(rowAfter.status, '② 已作废').toBe('VOIDED');
  expect({ u: rowLater.updated_at, c: rowLater.col_created, n: rowLater.cols }, '③ 作废后无写入').toEqual({ u: rowAfter.updated_at, c: rowAfter.col_created, n: rowAfter.cols });
  expect(againStatus).toBe(409);
  expect(againBody).toContain('REVIEW_NOT_PENDING');
  expect(blocked).toEqual([]);
});

test('R4-DRAIN · 等 V4 全部算完', async ({ page }) => {
  test.skip(STEP !== 'DRAIN', '非本步');
  test.setTimeout(3_600_000);
  const V = pendingVersion()[0];
  R.drain = { V: V.version_no, ...(await waitLoopDone(page, V.id, 55)) }; save(OUT);
  expect(R.drain.unfinished).toBe(0);
});
