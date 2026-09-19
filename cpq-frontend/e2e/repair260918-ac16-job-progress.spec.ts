/**
 * repair-260918 · S-UI · T-UI-16 —— 进度抽屉「执行中」可见 + 批次结束后停止轮询（page.route 注入执行中 → 完成）。
 *
 * AC-16（单点 · 执行中可见 · 显示）：更新任务执行中，进度抽屉计数行显示「执行中 1」，「等待」= 总数 − 已完成 − 执行中；
 *         该明细状态标签为「执行中」；批次结束后 30 秒内不再发出 GET /jobs/{jobId} 请求。
 * 布局基准：原型图/进度抽屉.html（计数行在「冲突」与「等待」之间新增「执行中 N」，蓝 #1677ff；N=0 仍显示）。
 * 注入口径（test.md §3.3）：先 status=RUNNING, total=3, success=1, running=1（期望「执行中 1」「等待 1」），
 *         再 status=SUCCESS, success=3, running=0；之后 30 秒内对该路径的请求次数必须为 0。
 *
 * 全部批次数据是**伪造的**（jobId / 单号 / 料号都带 RP0918-MOCK 字样，截图不会被误认成真实批次），
 * 批次列表 / 批次详情 / 明细三个 GET 全由本用例应答；零业务写入（非 GET 一律 abort 并断言为空，🚫 不点重试等按钮）。
 *
 * 判别力：旧口径「等待 = 总数 − 已终态」在注入数据下 = 2，新口径 = 1 ⇒ 断言「等待 1」能区分新旧实现。
 * 阳性对照：执行中阶段必须观察到 ≥ 2 次 GET /jobs/{id}（证明轮询真的被本用例的计数器抓到），
 *          否则「结束后 0 次」没有意义（testing.md §4.4「断言某事没发生必须配阳性对照」）。
 */
import { test, expect, type Route } from '@playwright/test';
import {
  BLUE, CUST, GREEN, RED,
  apiLogin, assertEnvIdentity, assertNoOtherPlaywright, blockWrites, dump, ensureDirs, info,
  openJobDrawer, openJobsPage, readCounts, shot, unwrap, writeEvid,
} from './repair260918-sui.helpers';

const JOB_ID = '0918a0a0-0000-4000-8000-00000000a016';
const VERSION = 'V26091899'; // 刻意不用真实版本号，避免截图被误认成 V26091802 的真实批次
const NOW = '2026-09-18T20:00:00+08:00';

type Phase = 'RUNNING' | 'DONE';
let phase: Phase = 'RUNNING';

function jobDto(p: Phase) {
  const base = {
    jobId: JOB_ID, customerNo: CUST.no, versionNo: VERSION, triggeredBy: 'RP0918-MOCK', triggeredAt: NOW,
    total: 3, failed: 0, conflict: 0, stale: 0, skipped: 0, notified: false,
  };
  return p === 'RUNNING'
    ? { ...base, status: 'RUNNING', success: 1, running: 1, finishedAt: null }
    : { ...base, status: 'SUCCESS', success: 3, running: 0, finishedAt: '2026-09-18T20:01:00+08:00', notified: true };
}
const item = (n: number, status: string) => ({
  itemId: `0918a0a0-0000-4000-8000-00000000b01${n}`,
  quotationId: `0918a0a0-0000-4000-8000-00000000c01${n}`,
  quotationNo: 'QT-RP0918-MOCK-0001',
  materialNo: `RP0918-MOCK-B0000${n}`,
  lineItemId: `0918a0a0-0000-4000-8000-00000000d01${n}`,
  status,
  errorCode: null,
  errorMessage: status === 'SUCCESS' ? '升版成功（RP0918 注入数据）' : null,
  diffValue: null,
  retryCount: 0,
  updatedAt: NOW,
});
function itemsDto(p: Phase) {
  const content = p === 'RUNNING'
    ? [item(1, 'SUCCESS'), item(2, 'RUNNING'), item(3, 'WAITING')]
    : [item(1, 'SUCCESS'), item(2, 'SUCCESS'), item(3, 'SUCCESS')];
  return { content, page: 1, size: 20, totalElements: 3, totalPages: 1 };
}

test.describe('S-UI · AC-16 进度抽屉', () => {
  test.beforeAll(async ({ browser }) => {
    ensureDirs();
    assertNoOtherPlaywright();
    const page = await browser.newPage();
    await assertEnvIdentity(page);
    await page.close();
  });

  test('T-UI-16 · 执行中 1 / 等待 1 / 明细「执行中」→ 完成后 30 秒内 0 次 GET /jobs/{id}', async ({ page }, ti) => {
    test.setTimeout(180_000);
    phase = 'RUNNING';
    const blocked = await blockWrites(page);
    const detailHits: Array<{ t: number; phase: Phase; url: string }> = [];
    const otherHits: string[] = [];
    let wrapped = false; // 真实列表接口是否包在 { data } 里 —— 伪造响应照同样形态回

    const send = async (route: Route, body: unknown) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(wrapped ? { data: body } : body) });

    await page.route(/\/api\/cpq\/price-adjust\/jobs(\/|\?|$)/, async (route) => {
      if (route.request().method() !== 'GET') return route.fallback();
      const u = new URL(route.request().url());
      const m = /\/api\/cpq\/price-adjust\/jobs(?:\/([^/]+))?(\/items)?\/?$/.exec(u.pathname);
      if (!m) { otherHits.push(u.pathname); return route.fallback(); }
      const [, id, items] = m;
      if (!id) {
        // 批次列表：取真实响应只为探明包装形态，内容整体换成伪造批次
        const resp = await route.fetch();
        const json = await resp.json().catch(() => null);
        wrapped = !!(json && typeof json === 'object' && !Array.isArray(json) && json.data && typeof json.data === 'object');
        const body = unwrap(json) ?? {};
        const page1 = { ...(typeof body === 'object' && !Array.isArray(body) ? body : {}), content: [jobDto(phase)], page: 1, size: 20, totalElements: 1, totalPages: 1 };
        console.log(`[jobs-list] real status=${resp.status()} wrapped=${wrapped} → 伪造 1 条（${phase}）`);
        return send(route, page1);
      }
      if (id !== JOB_ID) { otherHits.push(u.pathname); return route.fallback(); }
      if (items) return send(route, itemsDto(phase));
      detailHits.push({ t: Date.now(), phase, url: u.pathname + u.search });
      return send(route, jobDto(phase));
    });

    await apiLogin(page);
    await openJobsPage(page);
    const d = await openJobDrawer(page, VERSION);
    await expect(d.locator('.ant-drawer-title'), '抽屉标题（原型：更新执行进度 · 版本号）').toContainText(VERSION);

    // ---------------- 执行中阶段 ----------------
    await expect.poll(async () => (await readCounts(d)).text, { message: '计数行应出现「执行中」', timeout: 20_000 })
      .toMatch(/执行中/);
    const running = await readCounts(d);
    writeEvid('AC-16-counts-running.json', running);
    info(ti, 'AC-16 执行中计数行', running.text);
    expect(running.text, '总数 3').toMatch(/总数\s*3/);
    expect(running.text, '成功 1').toMatch(/成功\s*1/);
    expect(running.text, 'AC-16：计数行显示「执行中 1」').toMatch(/执行中\s*1(?!\d)/);
    expect(running.text, 'AC-16：「等待」= 总数 3 − 已完成 1 − 执行中 1 = 1（旧口径会是 2）').toMatch(/等待\s*1(?!\d)/);
    const iConflict = running.text.indexOf('冲突'), iRun = running.text.indexOf('执行中'), iWait = running.text.indexOf('等待');
    expect(iConflict >= 0 && iConflict < iRun && iRun < iWait, `原型布局：「冲突」<「执行中」<「等待」（实际 ${running.text}）`).toBe(true);
    // 原型还原项（非 AC 原文）：先用同一读法做阳性对照（成功=绿、失败=红），确认读对了设色层，再断言「执行中」为蓝
    const part = (re: RegExp) => running.parts.find(p => re.test(p.text));
    expect(part(/^成功/)?.color, '阳性对照：同一读法读「成功」应为绿（读不到 ⇒ 量具读错层，下面的蓝色断言无效）').toBe(GREEN);
    expect(part(/^失败/)?.color, '阳性对照：同一读法读「失败」应为红').toBe(RED);
    expect(part(/^执行中/)?.color, '原型：「执行中 N」为蓝 #1677ff').toBe(BLUE);

    const row2 = d.locator('tr.ant-table-row, tbody tr').filter({ hasText: 'RP0918-MOCK-B00002' }).first();
    await expect(row2, '明细表应有 RUNNING 那一条').toBeVisible();
    await expect(row2.getByText('执行中', { exact: true }), 'AC-16：该明细状态标签为「执行中」').toHaveCount(1);
    const row3 = d.locator('tr.ant-table-row, tbody tr').filter({ hasText: 'RP0918-MOCK-B00003' }).first();
    await expect(row3.getByText('等待', { exact: true }), 'WAITING 明细仍标「等待」（与执行中区分）').toHaveCount(1);
    await shot(d, 'AC-16-执行中');

    // 阳性对照：执行中阶段轮询必须被计数器抓到 ≥ 2 次
    await expect.poll(() => detailHits.filter(h => h.phase === 'RUNNING').length,
      { message: '阳性对照：执行中阶段应观察到 ≥ 2 次 GET /jobs/{id}（抓不到 ⇒「结束后 0 次」的结论无效）', timeout: 15_000 })
      .toBeGreaterThanOrEqual(2);
    const runTimes = detailHits.map(h => h.t);
    const gaps = runTimes.slice(1).map((t, i) => t - runTimes[i]);
    info(ti, 'AC-16 执行中轮询间隔(ms)', gaps);

    // ---------------- 切到完成 ----------------
    phase = 'DONE';
    await expect.poll(async () => (await readCounts(d)).text, { message: '批次完成后计数行应变为「成功 3」', timeout: 20_000 })
      .toMatch(/成功\s*3/);
    const doneShownAt = Date.now();
    const firstDone = detailHits.find(h => h.phase === 'DONE');
    expect(firstDone, '完成态必须是经由 GET /jobs/{id} 拿到的').toBeTruthy();
    const done = await readCounts(d);
    writeEvid('AC-16-counts-done.json', done);
    info(ti, 'AC-16 完成计数行', done.text);
    expect.soft(done.text, '原型状态 2：「执行中 0」仍显示').toMatch(/执行中\s*0(?!\d)/);
    expect(done.text, '完成后等待 0').toMatch(/等待\s*0(?!\d)/);
    await shot(d, 'AC-16-完成');

    // 观察窗口：从 UI 显示完成起 30 秒
    await page.waitForTimeout(30_000);
    const after = detailHits.filter(h => h.t > firstDone!.t);
    const evidence = {
      jobId: JOB_ID,
      firstDoneServedAt: new Date(firstDone!.t).toISOString(),
      doneShownAt: new Date(doneShownAt).toISOString(),
      windowEnd: new Date().toISOString(),
      runningPhaseHits: detailHits.filter(h => h.phase === 'RUNNING').length,
      doneHits: detailHits.filter(h => h.phase === 'DONE').length,
      hitsAfterFirstDone: after.map(h => ({ at: new Date(h.t).toISOString(), url: h.url })),
      otherJobPaths: otherHits,
      pollGapsMs: gaps,
    };
    writeEvid('AC-16-requests.json', evidence);
    info(ti, 'AC-16 结束后请求', evidence.hitsAfterFirstDone);
    expect(after.length, `AC-16：批次结束后 30 秒内不得再发 GET /jobs/${JOB_ID}（实际 ${after.length} 次：${JSON.stringify(evidence.hitsAfterFirstDone)}）`).toBe(0);
    if (blocked.length) await dump(page, 'AC-16-blocked', '.ant-drawer:visible');
    expect(blocked, '零业务写入').toEqual([]);
  });
});
