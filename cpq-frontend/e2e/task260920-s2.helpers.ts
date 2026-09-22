/**
 * task-260920 · S-2 界面脚本共享助手（由 R1 用例抽出；R1 与 R2~R4 共用，🚫 各写一份）。
 * 用例只从 AC 原文 / test.md / api.md / 原型图派生；选择器**未验证**。
 */
import { expect, type Page, type Locator } from '@playwright/test';
import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';

// ------------------------------------------------------------------ 坐标
export const WORKTREE = '/home/joii/project/cpq/.claude/worktrees/task-260920-review-list-lazy-compute';
export const TASK_DIR = path.join(WORKTREE, 'dev-docs', 'task-260729-客户价格调整策略和价格版本', 'task-260920-审核列表秒开与按需试算');
export const RUN = process.env.S2_RUN!;
export const EVID = path.join(TASK_DIR, '证据', '测试', 'S-2', RUN);
export const BASE_URL = process.env.PW_BASE_URL!;
export const BACKEND_PORT = Number(process.env.S2_BACKEND_PORT || '8130');
export const BACKEND_LOG = process.env.S2_BACKEND_LOG || '';
export const CUST = { no: 'CUST-0004', name: '正泰' };
export const Q0628 = 'QT-20260908-0628', Q0629 = 'QT-20260909-0629', Q0842 = 'QT-20260911-0842';

/** 候选料号（准备期只读查出，见 证据/测试/S-2/准备/R1-候选料号-输出-1.txt；组内按 material_no 升序处理，序号越大越靠后） */
export const M = {
  ac1: 'T260907T-B01844',          // 0628 组第 1844/1845 位
  ac5a: 'T260907T-B01845',         // 0628 组第 1845/1845 位（计时，结果应为缺数据两侧）
  ac5b: 'PERFHOT-B00291',          // 0842 组第 1110/1179 位（取值；上一版报价·现/调整后都非空）→ 同时是 AC-6 的料号
  ac21: 'PERFHOT-B00239',          // 0842 组第 1058/1179 位
  // Q-5 改选（主线 2026-09-21 裁决）：搜索词「S00」只命中 6 个料号 ⇒ 恒在一页；小单组在三大组之后才派 ⇒ 未计算窗口长。
  //   跌破预警线：S0011 / S0014 上一版 breached_count=1；不会被更新的单：见回报 §10.3（正泰无非活单，依赖「已提交」是否计入）。
  //   🚫 不选 S0004（它挂在 0842 上 4 行，AC-13 R1~R3 期间不碰 0842 的任何写 —— 本用例只取消不通过，仍避开）。
  ac8kw: 'S00',
  ac8computed: ['S0008', 'S0012'],               // 先点「计算」算好
  ac8pending: ['S0001', 'S0011', 'S0014'],       // 保持未计算
};

process.env.NO_PROXY = 'localhost,127.0.0.1';
process.env.no_proxy = 'localhost,127.0.0.1';
export const R: Record<string, any> = {};

// ------------------------------------------------------------------ 证据
export function evid(name: string, c: unknown) {
  fs.mkdirSync(EVID, { recursive: true });
  const p = path.join(EVID, name);
  fs.writeFileSync(p, typeof c === 'string' ? c : JSON.stringify(c, null, 2) + '\n', 'utf8');
  return p;
}
export function save(name = 'R1-界面结果.json') { evid(name, R); }
export async function shot(t: Page | Locator, name: string) {
  fs.mkdirSync(EVID, { recursive: true });
  const p = path.join(EVID, `${name}.png`);
  if ('goto' in t) await t.screenshot({ path: p, fullPage: true }); else await t.screenshot({ path: p });
  return p;
}
export async function dump(page: Page, tag: string) {
  const t = (await page.locator('body').allInnerTexts().catch(() => ['<dump failed>'])).join('\n');
  evid(`dump-${tag}.txt`, t);
}

// ------------------------------------------------------------------ 只读 SQL（会话强制只读）
export function sql<T = any>(q: string): T[] {
  const s = q.trim().replace(/;+\s*$/, '');
  if (!/^(select|with)\b/i.test(s)) throw new Error(`S-2 界面脚本只许 SELECT/WITH：${s.slice(0, 60)}`);
  const out = execFileSync('psql', ['-h', '10.177.152.12', '-U', 'postgres', '-d', 'cpq_db_0724', '-X', '-A', '-t', '-q',
    '-v', 'ON_ERROR_STOP=1', '-c', 'SET default_transaction_read_only = on',
    '-c', `SELECT coalesce(jsonb_agg(q), '[]'::jsonb)::text FROM (${s}) q`],
    { env: { ...process.env, PGPASSWORD: 'joii5231' }, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });
  const lines = out.split('\n').map(x => x.trim()).filter(Boolean);
  if (lines.length !== 1) throw new Error(`psql 输出应恰 1 行：${lines.length}`);
  return JSON.parse(lines[0]);
}
export const pendingVersion = () => sql<{ id: string; version_no: string; created_at: string }>(
  `select id::text, version_no, created_at::text from element_price_version where customer_no='${CUST.no}' and status='PENDING'`);
export function review(verId: string, mat: string) {
  return sql<any>(`select r.id::text, r.status, r.budget_status, r.budget_error, r.updated_at::text, q.quotation_number basis,
      c.quote_current::text qc, c.quote_adjusted::text qa, c.costing_adjusted::text ca, c.diff_adjusted::text da, c.status col_status, c.missing_side
    from material_price_review r left join quotation q on q.id=r.basis_quotation_id
    left join material_price_review_column c on c.review_id=r.id and c.column_id='col-default'
    where r.version_id='${verId}' and r.material_no='${mat}'`);
}
export function logSize() { return BACKEND_LOG && fs.existsSync(BACKEND_LOG) ? fs.statSync(BACKEND_LOG).size : -1; }
export function logSince(off: number): string {
  if (!BACKEND_LOG || off < 0) return '';
  const fd = fs.openSync(BACKEND_LOG, 'r'); const size = fs.statSync(BACKEND_LOG).size;
  const buf = Buffer.alloc(Math.max(0, size - off)); fs.readSync(fd, buf, 0, buf.length, off); fs.closeSync(fd);
  return buf.toString('utf8');
}

// ------------------------------------------------------------------ 环境验明正身
export function listenerCwd(port: number): string {
  const ss = execFileSync('ss', ['-ltnpH', `sport = :${port}`], { encoding: 'utf8' });
  const pid = /pid=(\d+)/.exec(ss)?.[1];
  expect(pid, `端口 ${port} 上应有监听进程：${ss}`).toBeTruthy();
  try { return fs.readlinkSync(`/proc/${pid}/cwd`); } catch { return '?'; }
}
export function assertNoOtherPlaywright() {
  let out = ''; try { out = execFileSync('pgrep', ['-af', 'node.*[p]laywright test'], { encoding: 'utf8' }); } catch { out = ''; }
  const mine = new Set<string>();
  for (let pid = String(process.pid); pid && pid !== '0' && !mine.has(pid);) {
    mine.add(pid);
    try { pid = fs.readFileSync(`/proc/${pid}/stat`, 'utf8').replace(/^.*\)\s+\S+\s+/, '').split(' ')[0]; } catch { break; }
  }
  const others = out.split('\n').filter(Boolean).filter(l => !mine.has(l.split(' ')[0]));
  expect(others, 'E-4：有别的 playwright 在跑 ⇒ 停下报主线').toEqual([]);
}

// ------------------------------------------------------------------ 登录 / 写拦截 / 页面操作
export const AUTH = path.join(WORKTREE, 'cpq-frontend', 'e2e', '.auth', 'task260920-s2-admin.json');
export async function apiLogin(page: Page) {
  if (fs.existsSync(AUTH)) {
    const st = JSON.parse(fs.readFileSync(AUTH, 'utf8'));
    if (st.cookies?.length) await page.context().addCookies(st.cookies);
    const probe = await page.request.get(`${BASE_URL}/api/cpq/price-adjust/reviews?page=1&size=1`, { failOnStatusCode: false });
    if (probe.status() === 200) return;
  }
  const res = await page.request.post(`${BASE_URL}/api/cpq/auth/login`, { data: { username: 'admin', password: 'Admin@2026' } });
  expect(res.status(), `admin 登录应 200（被锁/限流 ⇒ 停下报主线，🚫 改 user 表）：${(await res.text()).slice(0, 300)}`).toBe(200);
  fs.mkdirSync(path.dirname(AUTH), { recursive: true });
  await page.context().storageState({ path: AUTH });
}
/** 写请求白名单：只放行本片允许的写；其余 abort 并记录（用例末断言为空）。 */
export async function guardWrites(page: Page, allow: RegExp[]): Promise<string[]> {
  const blocked: string[] = [];
  await page.route('**/api/**', async (route) => {
    const req = route.request();
    if (req.method() === 'GET' || /\/auth\/(login|refresh)/.test(req.url()) || allow.some(a => a.test(req.url()))) return route.fallback();
    blocked.push(`${req.method()} ${req.url()}`); return route.abort();
  });
  return blocked;
}
export const W_COMPUTE = /\/price-adjust\/reviews\/[0-9a-f-]+\/compute-now$/;
export const W_RECOMPUTE = /\/price-adjust\/reviews\/[0-9a-f-]+\/recompute-budget$/;
export const W_IMPACT = /\/price-adjust\/reviews\/impact$/;       // 只读语义的 POST（影响面预览）

export async function gotoReviews(page: Page) {
  await page.goto('/pricing/reviews');
  if (/\/login|change-password/.test(page.url())) { await apiLogin(page); await page.goto('/pricing/reviews'); }
  await page.waitForLoadState('networkidle').catch(() => {});
}
export const searchBox = (page: Page) => page.getByPlaceholder('搜索客户 / 料号 / 料号名称');
export async function search(page: Page, kw: string) {
  const respP = page.waitForResponse(r => /\/api\/cpq\/price-adjust\/reviews\?/.test(r.url()) && r.request().method() === 'GET', { timeout: 60_000 });
  await searchBox(page).fill(kw); await searchBox(page).press('Enter');
  const resp = await respP; const j = await resp.json();
  return j?.data ?? j;
}
export const rowOf = (page: Page, mat: string, vno: string) =>
  page.locator('tr.ant-table-row').filter({ hasText: mat }).filter({ hasText: vno }).first();
/** 行内「比对状态」单元格（按表头文字定位列，🚫 写死列下标）。 */
export async function cellText(page: Page, row: Locator, header: string): Promise<string> {
  const idx = await page.locator('.ant-table-thead th').evaluateAll((ths, h) => ths.findIndex(th => (th as HTMLElement).innerText.includes(h as string)), header);
  expect(idx, `表头应含「${header}」`).toBeGreaterThanOrEqual(0);
  return (await row.locator('td').nth(idx).innerText()).replace(/\s+/g, ' ').trim();
}
export const drawerOf = (page: Page) => page.locator('.ant-drawer:visible').last();


// ------------------------------------------------------------------ R2~R4 共用
/** 界面「立即生成」（写库 🚦，调用方负责报批闸）。返回生成请求（供 AC-20 重放）与新版本。 */
export async function generateViaUi(page: Page, tag: string) {
  const before = pendingVersion();
  expect(before.length, '生成前正泰应恰有 1 个待处理版本').toBe(1);
  await page.goto('/pricing'); await page.waitForLoadState('networkidle').catch(() => {});
  await page.locator('input[placeholder="搜索客户"]').first().fill(CUST.name);
  await page.locator('.ant-list-item').filter({ hasText: CUST.name }).first().click();
  await page.locator('.ant-tabs-tab').filter({ hasText: '价格调整策略' }).first().click();
  const genBtn = page.getByRole('button', { name: /立即生成/ }).first();
  await expect(genBtn).toBeVisible({ timeout: 30_000 });
  const respP = page.waitForResponse(r => r.request().method() === 'POST' && /\/api\/cpq\/price-adjust\//.test(r.url()), { timeout: 120_000 });
  await genBtn.click();
  const confirm = page.locator('.ant-modal:visible, .ant-popover:visible').last();
  if (await confirm.isVisible({ timeout: 3000 }).catch(() => false)) await confirm.getByRole('button', { name: /确\s*定|确\s*认|生\s*成/ }).last().click();
  const resp = await respP;
  const t0 = Date.now();
  const after = pendingVersion();
  expect(after[0].id, `${tag}：应生成了新的待处理版本`).not.toBe(before[0].id);
  return { before: before[0], V: after[0], t0, request: { url: resp.url(), postData: resp.request().postData() }, status: resp.status() };
}
/** 等本版本后台试算跑完：待处理行 QUEUED/COMPUTING = 0（AC-12 口径；Q-1 裁决要求 R4 结束前必须等到这一步）。 */
export async function waitLoopDone(page: Page, verId: string, maxMin = 90) {
  const t0 = Date.now(); let s: any;
  for (;;) {
    s = sql(`select count(*) filter (where budget_status in ('QUEUED','COMPUTING'))::int unfinished, count(*)::int pending,
      count(*) filter (where budget_status='FAILED')::int failed, max(updated_at)::text last from material_price_review where version_id='${verId}' and status='PENDING'`)[0];
    if (s.unfinished === 0 && s.pending > 0) break;
    if (Date.now() - t0 > maxMin * 60_000) break;
    await page.waitForTimeout(10_000);
  }
  return { ...s, waitedMs: Date.now() - t0 };
}
/** 经既有「重算」入口（POST …/{id}/recompute-budget，api.md §4）**逐条串行**重算：上一条回到 READY/FAILED 且更新时间晚于发起时刻才发下一条。 */
export async function serialRecompute(page: Page, verId: string, mats: string[], onEach?: (x: any) => void) {
  const out: any[] = [];
  for (const m of mats) {
    const r = review(verId, m)[0];
    expect(r, `版本内应有 ${m} 的审核行`).toBeTruthy();
    const t = sql<{ t: string }>(`select now()::text t`)[0].t;
    const res = await page.request.post(`${BASE_URL}/api/cpq/price-adjust/reviews/${r.id}/recompute-budget`, { failOnStatusCode: false });
    let fin: any;
    for (let i = 0; i < 180; i++) {
      fin = sql(`select budget_status, updated_at::text, (updated_at > '${t}'::timestamptz) fresh from material_price_review where id='${r.id}'`)[0];
      if (fin.fresh && ['READY', 'FAILED'].includes(fin.budget_status)) break;
      await page.waitForTimeout(500);
    }
    const x = { m, http: res.status(), ...fin }; out.push(x); onEach?.(x);
  }
  return out;
}
/** 依据行（J-3 口径）当前报价侧金额 subtotal —— 主线裁决 Q-3：升版后依据行 subtotal 须 = 升版前该行预算 quote_adjusted（numeric 全位数）。 */
export function basisSubtotal(verId: string, mats: string[]) {
  return sql<any>(`with b as (select r.material_no, r.basis_quotation_id from material_price_review r where r.version_id='${verId}' and r.material_no in (${mats.map(m => `'${m}'`).join(',')}))
    select distinct on (b.material_no) b.material_no, q.quotation_number, li.id::text line_id, li.subtotal::text subtotal, li.quote_excel_values is null excel_null
      from b join quotation q on q.id=b.basis_quotation_id join quotation_line_item li on li.quotation_id=q.id and li.product_part_no_snapshot=b.material_no
       and (li.composite_type is null or li.composite_type<>'PART')
     order by b.material_no, li.sort_order asc nulls last, li.id asc`);
}
/** 列表上同页勾选 mats → 「通过并升版」→ 等确认框 → 读「各料号金额」→（confirm=true 时）点「确认通过并升版」。返回 jobId 与确认框金额文本。 */
export async function approveUi(page: Page, verNo: string, keyword: string, mats: string[], confirm: boolean, tag: string) {
  await gotoReviews(page);
  await search(page, keyword);
  for (const m of mats) { const row = rowOf(page, m, verNo); await expect(row, `同一页应出现 ${m}`).toBeVisible(); await row.locator('input[type=checkbox]').first().check(); }
  await page.getByRole('button', { name: /通过并升版/ }).first().click();
  const modal = page.locator('.ant-modal:visible').filter({ hasText: '通过前影响面确认' });
  await expect(modal).toBeVisible({ timeout: 180_000 });
  const amounts: Record<string, string> = {};
  for (const m of mats) amounts[m] = (await modal.locator('tr').filter({ hasText: m }).last().innerText().catch(() => '')).replace(/\s+/g, ' ');
  await shot(modal, `${tag}-确认框`);
  if (!confirm) return { amounts };
  const respP = page.waitForResponse(r => /\/price-adjust\/reviews\/approve$/.test(r.url()), { timeout: 60_000 });
  await modal.getByRole('button', { name: /确认通过并升版/ }).click();
  const resp = await respP; const j = await resp.json(); const body = j?.data ?? j;
  return { amounts, status: resp.status(), jobId: body.jobId as string, tApprove: Date.now() };
}
export async function waitJob(jobId: string, maxMs = 600_000) {
  const t0 = Date.now(); let j: any;
  for (;;) {
    j = sql(`select status, total_count, success_count, failed_count, finished_at::text from material_price_update_job where id='${jobId}'`)[0];
    if (j && j.status !== 'RUNNING') break;
    if (Date.now() - t0 > maxMs) break;
    await new Promise(r => setTimeout(r, 2000));
  }
  return j;
}
