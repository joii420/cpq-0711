/**
 * repair-260918 · 主线亲验（开发环境 5174 → 8081 → cpq_db_0724 · 真实数据 · 用户许可 D-6）。
 *
 * ⚠️ 本脚本会做真实业务写操作（通过料号、重试失败明细）—— 只能由主线按亲验计划手动运行一次：
 *   cd <worktree>/cpq-frontend && PW_BASE_URL=http://localhost:5174 RP0918_EXPECT_TREE=master \
 *     RP0918_EVID_DIR=<主工作区任务目录>/证据/亲验/写流程 npx playwright test -c e2e/mainline260918.config.ts
 * 顺序：AC-6/7 → AC-8/20 → T₀ → AC-9/11/16 → AC-10（问题说明.md ⑥ / test.md §6）。
 * 取证：页面操作 + 接口原始响应 + 只读 SQL + 8081 日志行；结论写 mainline-results.json。
 */
import { test, expect, type Page, type Locator } from '@playwright/test';
import fs from 'node:fs';
import { apiLogin, gotoApp, sql, shot, writeEvid, assertNoOtherPlaywright, drawer, dump } from './repair260918-sui.helpers';

const LOG_8081 = '/tmp/claude-1000/-home-joii-project-cpq/4b0ef42f-bb97-49fa-8d26-13f122f32e2b/scratchpad/shared-8081.log';
const VNO = 'V26091802';
const Q0842 = 'QT-20260911-0842';
const Q0629 = 'QT-20260909-0629';
// 第 2 轮只重跑 AC-9 / AC-10（前三项已写过库，不可重放）：先读回第 1 轮结果，免得 save() 把 ac6/ac8/t0 覆盖掉。
const PREV = process.env.RP0918_EVID_DIR ? `${process.env.RP0918_EVID_DIR}/mainline-results.json` : '';
const R: Record<string, any> = PREV && fs.existsSync(PREV) ? JSON.parse(fs.readFileSync(PREV, 'utf8')) : {};
const save = () => writeEvid('mainline-results.json', R);

const qid = (no: string) => sql<{ id: string }>(`select id from quotation where quotation_number='${no}'`)[0].id;
const reviewOf = (mat: string) => sql<{ id: string; status: string; budget_status: string }>(
  `select r.id, r.status, r.budget_status from material_price_review r join element_price_version v on v.id=r.version_id
   where v.customer_no='CUST-0004' and v.version_no='${VNO}' and r.material_no='${mat}'`)[0];
const logLines = () => fs.readFileSync(LOG_8081, 'utf8').split('\n');
const lineStates = (no: string, mats: string[]) => sql<any>(
  `select li.product_part_no_snapshot mat, li.id, li.subtotal::text subtotal, md5(coalesce(li.quote_card_values::text,'')) qcv,
     (select md5(coalesce(string_agg(cd.component_id::text||md5(coalesce(cd.snapshot_rows::text,''))||md5(coalesce(cd.row_data::text,'')),',' order by cd.component_id),''))
        from quotation_line_component_data cd where cd.line_item_id=li.id) cd
   from quotation_line_item li join quotation q on q.id=li.quotation_id
   where q.quotation_number='${no}' and li.product_part_no_snapshot in (${mats.map(m => `'${m}'`).join(',')}) order by 1`);
const revisions = (no: string) => sql<any>(
  `select r.revision_no, r.sealed, r.based_version_id is null as initial, v.version_no,
          r.upgraded_material_nos::text nos, r.last_updated_at::text updated
   from quotation_price_revision r join quotation q on q.id=r.quotation_id left join element_price_version v on v.id=r.based_version_id
   where q.quotation_number='${no}' order by r.revision_no`);
const wholeMd5 = (no: string) => sql<{ m: string }>(
  `select md5(string_agg(li.id::text||':'||coalesce(li.subtotal::text,'∅')||':'||coalesce(md5(li.quote_card_values::text),'∅'), ',' order by li.id)) m
   from quotation_line_item li join quotation q on q.id=li.quotation_id where q.quotation_number='${no}'`)[0].m;
const jobOf = (id: string) => sql<any>(`select id, status, total_count, success_count, failed_count,
   extract(epoch from (finished_at - triggered_at)) secs, triggered_at::text, finished_at::text from material_price_update_job where id='${id}'`)[0];
const eqDec = (a: string, b: string) => Number(sql<{ e: boolean }>(`select ('${a}'::numeric = '${b}'::numeric) e`)[0].e) === 1 || sql<{ e: boolean }>(`select ('${a}'::numeric = '${b}'::numeric) e`)[0].e === true;

async function waitJobDone(jobId: string, timeoutMs = 600_000) {
  const t = Date.now();
  for (;;) {
    const j = jobOf(jobId);
    if (j && j.status !== 'RUNNING') return j;
    if (Date.now() - t > timeoutMs) throw new Error(`job ${jobId} 超时未结束`);
    await new Promise(r => setTimeout(r, 1000));
  }
}

/** 审核页：真实搜索框搜料号，返回 V26091802 那一行。 */
async function searchReviewRow(page: Page, mat: string): Promise<Locator> {
  const box = page.getByPlaceholder('搜索客户 / 料号 / 料号名称');
  await box.fill(mat);
  await box.press('Enter');
  const row = page.locator('tr.ant-table-row').filter({ hasText: mat }).filter({ hasText: VNO }).first();
  await expect(row, `审核列表（待处理）应出现 ${mat} @ ${VNO}`).toBeVisible({ timeout: 30_000 });
  return row;
}

/** 点料号打开审核抽屉，返回 { ms, json }（接口原始响应 + 从点击到依据单行显示数值的耗时）。 */
async function openDrawerAndRead(page: Page, mat: string, basisQuotationNo: string) {
  const rv = reviewOf(mat);
  const row = await searchReviewRow(page, mat);
  const respP = page.waitForResponse(r => r.url().includes(`/api/cpq/price-adjust/reviews/${rv.id}`) && r.request().method() === 'GET', { timeout: 60_000 });
  const t0 = Date.now();
  await row.locator('a').filter({ hasText: mat }).first().click();
  const resp = await respP;
  const apiMs = Date.now() - t0;
  const json = await resp.json();
  const body = json?.data ?? json;
  const d = drawer(page, /为什么变/);
  const basisRow = d.locator('tr').filter({ hasText: basisQuotationNo }).first();
  await expect(basisRow).toBeVisible({ timeout: 30_000 });
  const uiMs = Date.now() - t0;
  const basis = (body.quotations || []).find((q: any) => q.isBasis);
  const rowText = (await basisRow.innerText()).replace(/\s+/g, ' ');
  await shot(d, `drawer-${mat}`);
  await d.locator('.ant-drawer-close').first().click().catch(() => {});
  return { reviewId: rv.id, status: resp.status(), apiMs, uiMs, basis, elementImpactTotal: body.elementImpactTotal, rowText };
}

test.describe.serial('repair-260918 主线亲验（写流程）', () => {
  test.beforeAll(async () => { assertNoOtherPlaywright(); });

  test('AC-6 / AC-7 · 大单审核抽屉 5 秒内打开 + 试算不写库', async ({ page }) => {
    await apiLogin(page);
    const before = { revs: revisions(Q0842), md5: wholeMd5(Q0842) };
    await gotoApp(page, '/pricing/reviews');
    const r = await openDrawerAndRead(page, 'PERF600-B00007', Q0842);
    const after = { revs: revisions(Q0842), md5: wholeMd5(Q0842) };
    R.ac6 = { ...r, pass: r.status === 200 && r.uiMs <= 5000 && r.basis?.adjustedComputed === true && r.basis?.quoteSubtotalAdjusted != null && r.elementImpactTotal != null };
    R.ac7 = { before, after, pass: JSON.stringify(before) === JSON.stringify(after) };
    save();
    expect(R.ac6.pass, JSON.stringify(R.ac6)).toBe(true);
    expect(R.ac7.pass, JSON.stringify(R.ac7)).toBe(true);
  });

  test('AC-8 / AC-20 · 失败明细单条重试（首次升版）', async ({ page }) => {
    await apiLogin(page);
    const mat = 'PERF600-B00432';
    const li = lineStates(Q0842, [mat])[0];
    const qcvBefore = sql<{ v: string | null }>(`select quote_card_values::text v from quotation_line_item where id='${li.id}'`)[0].v;
    await gotoApp(page, '/pricing/reviews');
    const st = page.locator('.ant-select').filter({ hasText: /待处理/ }).first();
    await st.click(); await page.locator('[class*=dropdown]:visible').getByText('已通过', { exact: true }).click();
    const d = await openDrawerAndRead(page, mat, Q0842);
    const X = d.basis?.quoteSubtotalAdjusted;
    expect(X, `抽屉应给出调整后小计 X：${JSON.stringify(d)}`).toBeTruthy();
    const jobs = sql<any>(`select id from material_price_update_job where version_no='${VNO}' and customer_no='CUST-0004'`);
    expect(jobs.length, '重试前 V26091802 名下应只有原失败批次 526e5d91').toBe(1);
    await gotoApp(page, '/pricing/jobs');
    const jrow = page.locator('tr.ant-table-row').filter({ hasText: VNO }).first();
    await jrow.locator('a').filter({ hasText: new RegExp(`^\\s*${VNO}\\s*$`) }).click();
    const jd = drawer(page, /更新执行进度/);
    const irow = jd.locator('tr').filter({ hasText: mat }).first();
    await expect(irow).toBeVisible({ timeout: 30_000 });
    await shot(jd, 'AC-8-重试前');
    const tClick = Date.now();
    await irow.getByRole('button', { name: /重\s*试/ }).click();
    const item = '941fa233-f0e0-45a5-bef6-3da4a30af618';
    let it: any;
    for (;;) {
      it = sql<any>(`select status, error_code, error_message, retry_count from material_price_update_job_item where id='${item}'`)[0];
      if (it.status === 'SUCCESS' || (it.status === 'FAILED' && Date.now() - tClick > 3000)) break;
      if (Date.now() - tClick > 300_000) break;
      await new Promise(r => setTimeout(r, 500));
    }
    const retryMs = Date.now() - tClick;
    await page.waitForTimeout(2500);
    await shot(jd, 'AC-8-重试后');
    const liAfter = lineStates(Q0842, [mat])[0];
    const agHit = sql<{ n: number }>(`select count(*) n from quotation_line_component_data where line_item_id='${li.id}' and snapshot_rows::text like '%28893.5%'`)[0].n;
    const revs = revisions(Q0842);
    const snap = sql<any>(`select r.based_version_id is null initial, (r.quote_card_values -> '${li.id}') = coalesce(li.quote_card_values,'null'::jsonb) cur_eq,
         (r.quote_card_values -> '${li.id}')::text v from quotation_price_revision r join quotation_line_item li on li.id='${li.id}'
       where r.quotation_id='${qid(Q0842)}' order by r.revision_no`);
    await page.reload(); await page.waitForLoadState('networkidle').catch(() => {});
    const itAfterReload = sql<any>(`select status from material_price_update_job_item where id='${item}'`)[0].status;
    R.ac8 = { X, item: it, retryMs, subtotalBefore: li.subtotal, subtotalAfter: liAfter.subtotal, subtotalEqX: eqDec(String(X), liAfter.subtotal), agRowsWith28893_5: agHit, itAfterReload };
    R.ac20 = { revs, snap, qcvBeforeWasNull: qcvBefore == null, pass: revs.length === 2 && new Set(revs.map((x: any) => x.revision_no)).size === 2
      && revs.some((x: any) => x.initial && x.sealed) && revs.some((x: any) => !x.initial && x.version_no === VNO)
      && snap.some((s: any) => !s.initial && s.cur_eq === true) };
    save();
    expect(it.status, JSON.stringify(R.ac8)).toBe('SUCCESS');
    expect(R.ac8.subtotalEqX, JSON.stringify(R.ac8)).toBe(true);
    expect(R.ac20.pass, JSON.stringify(R.ac20)).toBe(true);
  });

  /**
   * 勾选并通过。⚠️ 列表跨搜索/跨页会保留勾选 key，但工具栏动作只拿「当前页」上的已选行（SelectableTable 存量行为），
   * 所以多个料号必须在同一页里勾：`locate` 给定一次搜索词 + 页码；不给则逐个搜（只适用于单个料号）。
   */
  /** 按列表接口同一口径（status=PENDING · materialNo like %kw% · createdAt desc · 每页 20）现算目标行所在页；跨页即报错（此时尚未提交任何东西）。 */
  function pageOf(keyword: string, mats: string[]): number {
    const rows = sql<{ m: string; pg: number }>(
      `select m, pg from (select r.material_no m, v.version_no vno, (row_number() over (order by r.created_at desc) - 1) / 20 + 1 pg
         from material_price_review r join element_price_version v on v.id = r.version_id
        where r.status = 'PENDING' and r.material_no like '%${keyword}%') t
        where vno = '${VNO}' and m in (${mats.map(m => `'${m}'`).join(',')})`);
    const pages = [...new Set(rows.map(r => Number(r.pg)))];
    if (rows.length !== mats.length || pages.length !== 1) throw new Error(`[${keyword}] 目标行不在同一页或缺行（未提交任何东西）：${JSON.stringify(rows)}`);
    return pages[0];
  }

  async function approve(page: Page, mats: string[], tag: string, locate?: { keyword: string }) {
    await gotoApp(page, '/pricing/reviews');
    if (locate) {
      const box = page.getByPlaceholder('搜索客户 / 料号 / 料号名称');
      await box.fill(locate.keyword);
      await box.press('Enter');
      await expect(page.locator('tr.ant-table-row').first()).toContainText(locate.keyword, { timeout: 30_000 });
      const pageNo = pageOf(locate.keyword, mats);
      R[`${tag}-locate`] = { keyword: locate.keyword, pageNo, at: new Date().toISOString() };
      if (pageNo > 1) {
        await page.locator(`.ant-pagination-item-${pageNo}`).click();
        await expect(page.locator(`.ant-pagination-item-active`)).toHaveText(String(pageNo), { timeout: 30_000 });
      }
      for (const m of mats) {
        const row = page.locator('tr.ant-table-row').filter({ hasText: m }).filter({ hasText: VNO }).first();
        await expect(row, `第 ${pageNo} 页应出现 ${m} @ ${VNO}`).toBeVisible({ timeout: 30_000 });
        await row.locator('input[type=checkbox]').first().check();
      }
    } else {
      expect(mats.length, '不给 locate 时只能勾 1 个料号').toBe(1);
      const row = await searchReviewRow(page, mats[0]);
      await row.locator('input[type=checkbox]').first().check();
    }
    await expect(page.getByText(/已选\s*\d+\s*项/).first()).toHaveText(new RegExp(`已选\\s*${mats.length}\\s*项`));
    await shot(page, `${tag}-已勾选`);
    await page.getByRole('button', { name: /通过并升版/ }).first().click();
    const modal = page.locator('.ant-modal:visible').filter({ hasText: '通过前影响面确认' });
    await expect(modal).toBeVisible({ timeout: 30_000 });
    await expect(modal).toContainText(`共 ${mats.length} 个料号`);
    const okBtn = modal.getByRole('button', { name: /确认通过并升版/ });
    await expect(okBtn).toBeEnabled({ timeout: 60_000 });
    const respP = page.waitForResponse(r => r.url().includes('/api/cpq/price-adjust/reviews/approve'), { timeout: 60_000 });
    const logN0 = logLines().length;
    await okBtn.click();
    const resp = await respP;
    const json = await resp.json();
    const body = json?.data ?? json;
    return { jobId: body.jobId as string, status: resp.status(), logN0 };
  }

  test('T₀ · 12 行小单单条基准', async ({ page }) => {
    await apiLogin(page);
    const a = await approve(page, ['0028-2609000056'], 'T0');
    const j = await waitJobDone(a.jobId);
    R.t0 = { jobId: a.jobId, job: j, T0secs: Number(j.secs) };
    save();
    expect(j.status).toBe('SUCCESS');
  });

  test('AC-9 / AC-11 / AC-16 · 1200 行单批量通过 7 个料号', async ({ page }) => {
    await apiLogin(page);
    // 🔁 料号替换（第 3 轮）：AC-9 原列的 7 个在预算续跑后分散在两页，界面上无法同批勾选（列表动作只拿当前页已选行）；
    // 且续跑仍在进行、新审核行不断插到列表头部，按页码选会漂移。改取搜索词「PERF600-B0023」（只命中 10 个料号 ⇒ 恒在一页）里的 7 个：
    // 同为 V26091802 / 预算就绪 / 活单只在 QT-20260911-0842（2026-09-18 23:39 只读 SQL 实查）。断言不变。
    const mats = ['PERF600-B00230', 'PERF600-B00231', 'PERF600-B00232', 'PERF600-B00233', 'PERF600-B00235', 'PERF600-B00236', 'PERF600-B00237'];
    const controls = ['PERF600-B00001', 'PERF600-B00100', 'PERF600-B00999'];
    const ctlBefore = lineStates(Q0842, controls);
    await gotoApp(page, '/pricing/reviews');
    const X: Record<string, string> = {};
    for (const m of mats) { const d = await openDrawerAndRead(page, m, Q0842); X[m] = d.basis?.quoteSubtotalAdjusted; }
    const a = await approve(page, mats, 'AC-9', { keyword: 'PERF600-B0023' });
    const jd = drawer(page, /更新执行进度/);
    await expect(jd).toBeVisible({ timeout: 30_000 });
    const probes: any[] = [];
    let shotTaken = false;
    for (let i = 0; i < 600; i++) {
      const counts = (await jd.locator('.ant-space').first().innerText().catch(() => '')).replace(/\s+/g, ' ');
      const j = jobOf(a.jobId);
      probes.push({ t: Date.now(), counts, dbStatus: j.status });
      if (!shotTaken && /执行中 1/.test(counts) && /成功 [1-9]/.test(counts)) { await shot(jd, 'AC-9-执行中'); shotTaken = true; }
      if (j.status !== 'RUNNING') break;
      await page.waitForTimeout(1000);
    }
    const j = await waitJobDone(a.jobId);
    await page.waitForTimeout(2500);
    await shot(jd, 'AC-9-完成');
    const after = lineStates(Q0842, mats);
    const ctlAfter = lineStates(Q0842, controls);
    const revs = revisions(Q0842);
    const cur = revs.filter((x: any) => !x.initial && x.version_no === VNO);
    const q = qid(Q0842);
    const logs = logLines().slice(a.logN0).filter(l => l.includes('[perf] revision-write') && l.includes(`quotation=${q}`) && l.includes('kind=CURRENT'));
    const items = sql<any>(`select status, count(*) n from material_price_update_job_item where job_id='${a.jobId}' group by 1`);
    R.ac9 = { substitutedMaterials: mats, originalMaterials: ['PERF600-B00007', 'PERF600-B00217', 'PERF600-B00259', 'PERF600-B00368', 'PERF600-B00485', 'PERF600-B00598', 'PERF0909-B00150'],
      jobId: a.jobId, job: j, items, perItemSecs: Number(j.secs) / mats.length, T0secs: R.t0?.T0secs,
      ratioOk: R.t0 ? Number(j.secs) / mats.length <= 1.5 * R.t0.T0secs : null, currentRevRows: cur.length, currentRevNos: cur[0]?.nos,
      revisionWriteCurrentLogLines: logs.length, revisionWriteLog: logs, probes: probes.filter((_, i) => i % 1 === 0).slice(0, 80), sawRunningWithSuccess: shotTaken };
    R.ac11 = { controlsUnchanged: JSON.stringify(ctlBefore) === JSON.stringify(ctlAfter), ctlBefore, ctlAfter,
      approved: after.map((l: any) => ({ mat: l.mat, subtotal: l.subtotal, X: X[l.mat], eq: eqDec(String(X[l.mat]), l.subtotal) })) };
    save();
    expect(j.status).toBe('SUCCESS');
    expect(cur.length).toBe(1);
    expect(logs.length).toBe(1);
    expect(R.ac11.controlsUnchanged).toBe(true);
    expect(R.ac11.approved.every((x: any) => x.eq), JSON.stringify(R.ac11.approved)).toBe(true);
  });

  test('AC-10 · 跨两张大单批量通过 3 个料号', async ({ page }) => {
    await apiLogin(page);
    const mats = ['PERFHOT-B00221', 'PERFHOT-B00239', 'PERFHOT-B00291'];
    const a = await approve(page, mats, 'AC-10', { keyword: 'PERFHOT-B002' });
    const j = await waitJobDone(a.jobId);
    const q29 = qid(Q0629), q42 = qid(Q0842);
    const tail = logLines().slice(a.logN0);
    const cnt = (q: string, k: string) => tail.filter(l => l.includes('[perf] revision-write') && l.includes(`quotation=${q}`) && l.includes(`kind=${k}`)).length;
    const items = sql<any>(`select status, count(*) n from material_price_update_job_item where job_id='${a.jobId}' group by 1`);
    R.ac10 = { jobId: a.jobId, job: j, items, perItemSecs: Number(j.secs) / 6, T0secs: R.t0?.T0secs,
      ratioOk: R.t0 ? Number(j.secs) / 6 <= 1.5 * R.t0.T0secs : null,
      current0629: cnt(q29, 'CURRENT'), current0842: cnt(q42, 'CURRENT'), initial0629: cnt(q29, 'INITIAL'),
      revs0629: revisions(Q0629), revs0842: revisions(Q0842) };
    save();
    expect(j.status).toBe('SUCCESS');
    expect(R.ac10.current0629).toBe(1);
    expect(R.ac10.current0842).toBe(1);
  });
});
