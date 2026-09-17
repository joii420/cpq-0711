/**
 * task-260916「元素价格支持 9 位小数」· test slice **S-全局 (S-GL)** · T-GL-01 → AC-19. DRAFT (not executed).
 *
 * Source of truth: 需求文档.md §③ AC-19 (verbatim below) + P-8 + test.md §1/§4/§5 + api.md §2 row 5/6.
 * 🚫 Written WITHOUT reading implementation code (cpq-frontend/src/**, cpq-backend/src/main/**).
 *    Selectors: existing e2e specs (tmp-strategy-default-off / tmp-task0729-*) + 主线立项实查的页面描述.
 *    Anything the docs don't pin down is logged (printed actual text) instead of guessed into a hard assertion.
 *
 * AC-19（序列 · 策略例外录入 → 保存 → 修改 → 看历史 → 删除）  前置 P-8
 *   1. 定价策略 → 选客户 QA0729测试客户 →「元素价格策略」→「新增例外」，元素 Zn、价格源长江有色网、取值方式最新一条价；
 *      「系数」输入 1.1234567891、「加价」输入 0.0000000015，分别失焦 → 输入框显示 1.123456789、0.000000002
 *   2. 保存 → 例外列表 Zn 行「系数」1.123456789、「加价」0.000000002
 *   3. 编辑该例外，系数改 1.123456788 → 保存 → 列表显示 1.123456788
 *   4. 打开「变更历史」→ 最新一条修改记录的系数变更为 1.123456789 → 1.123456788；
 *      新增记录的摘要中系数 1.123456789、加价 0.000000002（改前均显示为 2 位：1.12、0）
 *   5. 删除该例外 → 列表无 Zn 行；「变更历史」出现删除记录，摘要中系数 1.123456788
 *
 * ── Write surface (test.md §1 S-全局 registration) ─────────────────────────────────
 *   cpq_db_0724 · element_price_strategy: exactly ONE row (CUST-0729-QA × Zn) created → updated → deleted, via UI only.
 *   cpq_db_0724 · element_price_strategy_log: the CREATE / UPDATE / DELETE rows this produces (cannot be deleted —
 *     accepted residue, listed in the report).
 *   🚫 psql here is SELECT-only (hard guard). 🚫 Never touches CUST-0729-QA's default strategy (md5 guard before/after),
 *   never any other customer, never 「立即生成一次」, never the default-strategy form's save button.
 *   finally: if the Zn exception still exists → DELETE /api/cpq/element-price/strategies/exceptions/{id} (only an id
 *   that was NOT there at start, i.e. ours), then SQL proves count = 0.
 *
 * ── How to run (after 主线 unlocks S-全局, i.e. S-UI finished) ─────────────────────
 *   0. pgrep -f "node.*[p]laywright test"   → must be empty (sampled right before the run; write the sampling time into the report)
 *   0b. only after 主线's unlock notice: export T916_SGL_UNLOCKED=1
 *   Note (需求文档 AC-19 注 / D-12): steps 4-5 depend on B-11 (history drawer crashed on create/delete records before it).
 *   1. cp <this dir>/task260916-sgl-ac19.spec.ts <this dir>/task260916-sgl.config.ts $W/cpq-frontend/e2e/
 *   2. cd $W/cpq-frontend && PW_BASE_URL=http://localhost:<fe> PW_BACKEND_URL=http://localhost:<be> \
 *        npx playwright test --config=e2e/task260916-sgl.config.ts --reporter=list
 *   Both temporary servers must be started from THIS worktree (identity gate below checks /proc/<pid>/cwd).
 */
import { test, expect, Page, Locator, APIRequestContext, request as pwRequest } from '@playwright/test';
import { execFileSync, execSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const HERE = path.dirname(fileURLToPath(import.meta.url)); // ESM: no __dirname (AP-43)

// ═══════════════════════════════════════════════════════════════════
// Coordinates
// ═══════════════════════════════════════════════════════════════════

const BASE_URL = process.env.PW_BASE_URL || '';
const BACKEND_URL = process.env.PW_BACKEND_URL || '';
const DB_NAME = 'cpq_db_0724';
const CUSTOMER_NO = 'CUST-0729-QA';
const CUSTOMER_NAME = 'QA0729测试客户';
const ELEMENT = 'Zn';
const SOURCE_NAME = '长江有色网';
const SOURCE_ID = '6b038405-6126-48f5-8d19-2af8124ae916'; // P-1 / P-8 (only used in SQL cross-checks)
const METHOD_LABEL = '最新一条价';

const IN_FACTOR = '1.1234567891';
const IN_PREMIUM = '0.0000000015';
const SHOW_FACTOR_1 = '1.123456789';
const SHOW_PREMIUM = '0.000000002';
const IN_FACTOR_2 = '1.123456788';
const SHOW_FACTOR_2 = '1.123456788';

/** Walk up until a dir containing dev-docs/ + cpq-frontend/ (works both from the draft dir and from cpq-frontend/e2e/). */
function findWorktreeRoot(start: string): string {
  let d = start;
  for (let i = 0; i < 10; i++) {
    if (fs.existsSync(path.join(d, 'dev-docs')) && fs.existsSync(path.join(d, 'cpq-frontend'))) return d;
    d = path.dirname(d);
  }
  throw new Error(`cannot locate worktree root from ${start}`);
}
const ROOT = findWorktreeRoot(HERE);
const TASK_DIR = path.join(ROOT, 'dev-docs', 'task-260916-元素价格支持9位小数');
const EVID = path.join(TASK_DIR, '证据', 'S-GL');

// ═══════════════════════════════════════════════════════════════════
// Evidence
// ═══════════════════════════════════════════════════════════════════

function ensureEvid() { fs.mkdirSync(EVID, { recursive: true }); }
function evidLog(line: string) {
  ensureEvid();
  const s = `[${new Date().toISOString()}] ${line}\n`;
  process.stdout.write(s);
  fs.appendFileSync(path.join(EVID, 'AC-19-run.log'), s, 'utf-8');
}
/** Screenshots go straight into the task evidence dir (test-results/ is wiped each run — testing.md §2). */
async function shot(page: Page, name: string) {
  ensureEvid();
  const p = path.join(EVID, `${name}.png`);
  await page.screenshot({ path: p, fullPage: true }).catch((e) => evidLog(`⚠️ screenshot ${name} failed: ${e}`));
  evidLog(`[shot] ${p}`);
}

// ═══════════════════════════════════════════════════════════════════
// DB: SELECT-only on cpq_db_0724
// ═══════════════════════════════════════════════════════════════════

const PG = { host: '10.177.152.12', port: '5432', user: 'postgres', password: 'joii5231' };

function psql(sql: string): string {
  const t = sql.trim().replace(/;\s*$/, '');
  if (!/^(select|with)\b/i.test(t) || /;\s*\S/.test(t)) {
    throw new Error(`🚨 S-GL psql is SELECT-only; refused: ${t.slice(0, 160)} —— 停下报主线 (CLAUDE.md §3.2)`);
  }
  return execFileSync('psql', ['-h', PG.host, '-p', PG.port, '-U', PG.user, '-d', DB_NAME, '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-c', t], {
    encoding: 'utf-8', env: { ...process.env, PGPASSWORD: PG.password }, maxBuffer: 64 * 1024 * 1024,
  }).trim();
}
function sqlJson<T = any>(sql: string): T[] {
  return JSON.parse(psql(`select coalesce(json_agg(t), '[]'::json) from (${sql}) t`) || '[]');
}
function sqlInt(sql: string, why: string): number {
  const raw = psql(sql).split('\n')[0] ?? '';
  const n = Number(raw);
  expect(raw !== '' && Number.isFinite(n), `${why}: query returned ${JSON.stringify(raw)} ⇒ gauge broken, 判【未验证】`).toBe(true);
  return n;
}

type ZnRow = { id: string; source_id: string; method: string; window_num: number | null; factor: string; premium: string; status: string };
const znRows = () => sqlJson<ZnRow>(
  `select id::text, source_id::text, method, window_num, factor::text, premium::text, status
     from element_price_strategy where customer_no='${CUSTOMER_NO}' and element_code='${ELEMENT}'`);
const znCount = () => sqlInt(
  `select count(*) from element_price_strategy where customer_no='${CUSTOMER_NO}' and element_code='${ELEMENT}'`, 'Zn exception count');
const defaultMd5 = () => psql(
  `select coalesce(string_agg(md5(row_to_json(s)::text), ',' order by id), '(none)')
     from element_price_strategy s where customer_no='${CUSTOMER_NO}' and element_code is null`);
/** Only log rows written at/after this run's start (a rerun must not be reddened by a previous run's residue). */
const logRows = (sinceTs: string) => sqlJson<{ action: string; changed_at: string; snapshot: any }>(
  `select action, changed_at::text, snapshot from element_price_strategy_log
    where customer_no='${CUSTOMER_NO}' and element_code='${ELEMENT}'
      and changed_at >= '${sinceTs}'::timestamptz order by changed_at`);
/** numeric equality without float noise: compare normalized decimal strings */
const normDec = (s: string) => {
  const [i, f = ''] = String(s).trim().split('.');
  const ff = f.replace(/0+$/, '');
  return ff ? `${i}.${ff}` : i;
};

// ═══════════════════════════════════════════════════════════════════
// Environment identity (testing.md §4.2: the probe must prove WHO answered)
// ═══════════════════════════════════════════════════════════════════

function portOwner(port: string) {
  const sh = (c: string) => execSync(c, { shell: '/bin/bash', encoding: 'utf-8' }).trim();
  let pid = '', cwd = '', cmd = '', dbNameEnv: string | null = null;
  try {
    pid = (sh(`ss -ltnp 2>/dev/null | /usr/bin/grep -a ':${port} ' || true`).match(/pid=(\d+)/) || [])[1] || '';
    if (pid) {
      cwd = sh(`readlink -f /proc/${pid}/cwd 2>/dev/null || true`);
      cmd = sh(`tr '\\0' ' ' < /proc/${pid}/cmdline 2>/dev/null || true`);
      const m = sh(`tr '\\0' '\\n' < /proc/${pid}/environ 2>/dev/null || true`).match(/^DB_NAME=(.*)$/m);
      dbNameEnv = m ? m[1] : null;
    }
  } catch { /* sampling failure → empty fields → gate fails loudly */ }
  return { pid, cwd, cmd: cmd.slice(0, 300), dbNameEnv };
}

async function apiContext(): Promise<APIRequestContext> {
  const ctx = await pwRequest.newContext({ baseURL: BACKEND_URL });
  for (let i = 0; i < 4; i++) {
    const r = await ctx.post('/api/cpq/auth/login', { data: { username: 'admin', password: 'Admin@2026' } });
    if (r.ok()) return ctx;
    if (r.status() === 429) { await new Promise((res) => setTimeout(res, 3000 * (i + 1))); continue; }
    throw new Error(`admin login on ${BACKEND_URL} failed ${r.status()} ${(await r.text()).slice(0, 200)} —— 报主线，🚫 不改 user 表`);
  }
  throw new Error('admin login kept returning 429 ⇒ harness problem, 报主线');
}

async function assertEnv() {
  expect(BACKEND_URL, 'PW_BACKEND_URL required').not.toBe('');
  expect(BACKEND_URL, 'S-GL must not hit the shared 8081').not.toMatch(/:8081\b/);
  expect(BASE_URL, 'S-GL must not drive the shared 5174').not.toMatch(/:5174\b/);
  const be = portOwner((BACKEND_URL.match(/:(\d+)/) || [])[1] || '');
  const fe = portOwner((BASE_URL.match(/:(\d+)/) || [])[1] || '');
  // AC-1's own query, read-only: proves the 9-digit pricing function is live on this DB (前提 test.md §4 item 1)
  const cu = psql(`select unit_price::text from f_customer_element_price('CUST-0004', DATE '2026-09-16') where element_code='Cu'`);
  const ctx = await apiContext();
  const unauth = await (await pwRequest.newContext({ baseURL: BACKEND_URL })).get('/api/cpq/components');
  // Concurrent sessions may create quotations between the two reads ⇒ on mismatch wait 2 s and resample once.
  const sample = async () => {
    const r = await ctx.get('/api/cpq/quotations?page=1&size=1');
    const api = Number((await r.json())?.data?.totalElements);
    const db = sqlInt('select count(*) from quotation', 'identity: quotation count');
    return { api, db };
  };
  let { api: apiTotal, db: dbTotal } = await sample();
  if (apiTotal !== dbTotal) {
    evidLog(`ENV quotation count mismatch api=${apiTotal} db=${dbTotal} → resample in 2 s`);
    await new Promise((res) => setTimeout(res, 2000));
    ({ api: apiTotal, db: dbTotal } = await sample());
  }
  await ctx.dispose();
  evidLog(`ENV backend ${BACKEND_URL} pid=${be.pid} cwd=${be.cwd} DB_NAME(env)=${be.dbNameEnv ?? '(unset/unreadable)'} unauth=${unauth.status()} cmd=${be.cmd}`);
  evidLog(`ENV frontend ${BASE_URL} pid=${fe.pid} cwd=${fe.cwd}`);
  evidLog(`ENV f_customer_element_price(CUST-0004,2026-09-16) Cu = ${cu}; quotations api=${apiTotal} db(${DB_NAME})=${dbTotal}`);
  expect(unauth.status(), 'backend business endpoint without login must be 401').toBe(401);
  expect(be.pid, `nothing listens on ${BACKEND_URL}`).not.toBe('');
  expect(fe.pid, `nothing listens on ${BASE_URL}`).not.toBe('');
  expect(be.cwd.startsWith(ROOT), `backend cwd ${be.cwd} is not inside worktree ${ROOT} ⇒ testing old code, 判【未验证】`).toBe(true);
  expect(fe.cwd.startsWith(ROOT), `frontend cwd ${fe.cwd} is not inside worktree ${ROOT} ⇒ testing old code, 判【未验证】`).toBe(true);
  if (be.dbNameEnv !== null) expect(be.dbNameEnv, 'backend must run on the default profile DB').toBe(DB_NAME);
  expect(apiTotal, `backend quotation total ${apiTotal} ≠ ${DB_NAME} count ${dbTotal} ⇒ backend is on another DB`).toBe(dbTotal);
  // V445 is already on cpq_db_0724 via master, so this no longer means "主线放行" — it only proves the function is live.
  expect(normDec(cu), '9-digit pricing function not live on cpq_db_0724 ⇒ 判【未验证】').toBe('101.13921');
  // The unlock signal is 主线's notification (S-UI finished), passed explicitly:
  expect(process.env.T916_SGL_UNLOCKED, 'S-GL not unlocked by 主线 (set T916_SGL_UNLOCKED=1 only after the unlock notice)').toBe('1');
}

// ═══════════════════════════════════════════════════════════════════
// UI helpers
// ═══════════════════════════════════════════════════════════════════

async function uiLogin(page: Page) {
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|change-password)/, { timeout: 30_000 });
}

/** 定价管理 →「定价策略」(route /pricing) → 选客户 →「元素价格策略」页签 */
async function openElementStrategyTab(page: Page) {
  await page.goto('/pricing');
  await page.waitForLoadState('networkidle');
  const search = page.locator('input[placeholder="搜索客户"]').first();
  await search.fill(CUSTOMER_NAME);
  await search.press('Enter');
  const item = page.locator('.ant-list-item').filter({ hasText: CUSTOMER_NAME }).first();
  await item.waitFor({ state: 'visible' });
  await item.click();
  await page.locator('.ant-tabs-tab').filter({ hasText: '元素价格策略' }).first().click();
  await expect(activePane(page).getByText('客户级默认策略').first()).toBeVisible();
  await page.waitForLoadState('networkidle');
}

/**
 * The「元素价格策略」tab panel only: both strategy tabs are mounted and each has a「变更历史」/「保存」button
 * (tmp-task0729 lessons). Located by panel NAME — run 2 showed the customer list on the left has its own tabs
 * (全部/钻石/…), so `.ant-tabs-tabpane-active`.first() picked that list's「全部」panel instead.
 */
const activePane = (page: Page) => page.getByRole('tabpanel', { name: '元素价格策略' });

/** The exception table: the table inside the active pane whose header has both「系数」and「加价」and「元素」. */
function exceptionTable(page: Page): Locator {
  return activePane(page).locator('.ant-table-wrapper')
    .filter({ has: page.locator('thead th', { hasText: '系数' }) })
    .filter({ has: page.locator('thead th', { hasText: '加价' }) })
    .first();
}

/** Read header texts once; returns column index by exact header text (falls back to "contains"). */
async function colIndex(table: Locator, header: string): Promise<number> {
  const heads = (await table.locator('thead th').allInnerTexts()).map((s) => s.replace(/\s+/g, ''));
  let idx = heads.findIndex((h) => h === header);
  if (idx < 0) idx = heads.findIndex((h) => h.includes(header));
  evidLog(`exception table headers = ${JSON.stringify(heads)} → "${header}" @ ${idx}`);
  expect(idx, `exception table has no「${header}」column`).toBeGreaterThanOrEqual(0);
  return idx;
}

/** Zn rows of the exception list (tr.ant-table-row, never tbody tr — measure-row pitfall). */
function znListRows(page: Page): Locator {
  return exceptionTable(page).locator('tr.ant-table-row').filter({
    has: page.locator('td', { hasText: new RegExp(`^\\s*${ELEMENT}(\\b|\\s|（|\\()`) }),
  });
}

async function readZnRow(page: Page, tag: string) {
  const table = exceptionTable(page);
  await table.waitFor({ state: 'visible' });
  const rows = znListRows(page);
  const n = await rows.count();
  evidLog(`[${tag}] Zn rows in exception list = ${n}`);
  expect(n, `[${tag}] exactly one Zn row expected in the exception list`).toBe(1);
  const iF = await colIndex(table, '系数');
  const iP = await colIndex(table, '加价');
  const cells = rows.first().locator('td');
  const factor = (await cells.nth(iF).innerText()).trim();
  const premium = (await cells.nth(iP).innerText()).trim();
  evidLog(`[${tag}] Zn row full text = ${JSON.stringify((await rows.first().innerText()).replace(/\s+/g, ' '))}`);
  evidLog(`[${tag}] Zn row 系数="${factor}" 加价="${premium}"`);
  expect(factor, `[${tag}] 系数 cell must not be empty`).not.toBe('');
  expect(premium, `[${tag}] 加价 cell must not be empty`).not.toBe('');
  return { factor, premium };
}

/** Currently open drawer (antd v6: .ant-drawer-body / .ant-drawer-title / .ant-drawer-footer; no .ant-drawer-content). */
const openDrawer = (page: Page, title: RegExp) =>
  page.locator('.ant-drawer').filter({ has: page.locator('.ant-drawer-title', { hasText: title }) }).last();

const formItem = (scope: Locator, label: string) =>
  scope.locator('.ant-form-item').filter({ has: scope.page().locator('.ant-form-item-label', { hasText: label }) }).first();

/** antd Select inside a Form.Item: type to filter (virtual list), then click the option whose text matches. */
async function pickSelect(scope: Locator, label: string, typed: string, option: RegExp) {
  const page = scope.page();
  const item = formItem(scope, label);
  await item.locator('.ant-select').first().click();
  const input = item.locator('input').first();
  if (await input.isEditable().catch(() => false)) await input.fill(typed).catch(() => {});
  const opt = page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: option }).first();
  await opt.waitFor({ state: 'visible' });
  await opt.click();
  const shown = (await item.locator('.ant-select').first().innerText()).trim();
  evidLog(`select「${label}」→ shown "${shown}"`);
  expect(shown, `select「${label}」should show the picked option`).toMatch(option);
}

async function numberInput(scope: Locator, label: string): Promise<Locator> {
  const input = formItem(scope, label).locator('input').first();
  await input.waitFor({ state: 'visible' });
  return input;
}

/** Save inside the drawer only (never the default-strategy form's save button). */
async function saveDrawer(drawer: Locator) {
  const btn = drawer.locator('.ant-drawer-footer button, .ant-drawer-extra button').filter({ hasText: /^\s*(保\s*存|确\s*定)\s*$/ }).first();
  evidLog(`drawer save button text = "${(await btn.innerText()).trim()}"`);
  await btn.click();
  await expect(drawer, 'drawer should close after a successful save').toBeHidden();
}

/**
 * The exception list follows the toolbar pattern (docs/列表操作规范.md; run 3 snapshot: row checkbox +
 * toolbar「编辑」「删除」disabled while "未选择行"). Select the Zn row, then click the toolbar button.
 */
async function selectZnAndClickToolbar(page: Page, name: RegExp, tag: string) {
  const row = znListRows(page).first();
  const cb = row.locator('input[type="checkbox"]').first();
  if (!(await cb.isChecked())) await cb.check();
  expect(await cb.isChecked(), `[${tag}] Zn row should be selected`).toBe(true);
  const btns = activePane(page).getByRole('button', { name });
  expect(await btns.count(), `[${tag}] exactly one toolbar button ${name} expected in the element-strategy panel`).toBe(1);
  await expect(btns.first()).toBeEnabled();
  await btns.first().click();
}

/** Open「变更历史」(active pane toolbar) and return the drawer body text split into records. */
async function readHistory(page: Page, tag: string) {
  const btn = activePane(page).getByRole('button', { name: /变更历史/ }).first();
  await btn.click();
  const drawer = openDrawer(page, /变更历史/);
  await drawer.locator('.ant-drawer-body').waitFor({ state: 'visible' });
  await page.waitForLoadState('networkidle');
  const body = drawer.locator('.ant-drawer-body');
  // run 4: the table renders "No data" first and fills asynchronously ⇒ wait for real rows (records are tr.ant-table-row)
  await body.locator('tr.ant-table-row').first().waitFor({ state: 'visible', timeout: 20_000 })
    .catch(() => evidLog(`[${tag}] no tr.ant-table-row appeared within 20 s`));
  // Record containers are not specified by the docs: try the usual antd shapes, first non-empty wins.
  const shapes = ['.ant-timeline-item', 'tr.ant-table-row', '.ant-list-item', '.ant-card', '.ant-collapse-item'];
  let records: string[] = [];
  let used = '';
  for (const s of shapes) {
    const texts = await body.locator(s).allInnerTexts();
    if (texts.length > 0) { records = texts.map((t) => t.replace(/\s+/g, ' ').trim()); used = s; break; }
  }
  const full = (await body.innerText()).replace(/\s+/g, ' ');
  evidLog(`[${tag}] history drawer: record shape="${used}" count=${records.length}`);
  records.forEach((r, i) => evidLog(`[${tag}]   #${i} ${r}`));
  evidLog(`[${tag}] history drawer full text = ${full}`);
  expect(records.length, `[${tag}] history drawer shows no record (or unknown record shape) ⇒ gauge broken, 报主线`).toBeGreaterThan(0);
  return { drawer, records, full };
}

async function closeDrawer(page: Page, drawer: Locator) {
  const close = drawer.locator('.ant-drawer-close').first();
  if (await close.isVisible().catch(() => false)) await close.click(); else await page.keyboard.press('Escape');
  await expect(drawer).toBeHidden();
}

/**
 * Parse the change time shown in a history record (format not specified by the docs):
 * accepts YYYY-MM-DD / YYYY/MM/DD + HH:mm[:ss], interpreted in the local TZ (browser and node share the machine TZ).
 * Returns { ms, hasSeconds } or null.
 */
function recordTime(r: string): { ms: number; hasSeconds: boolean } | null {
  const m = r.match(/(\d{4})[-/](\d{1,2})[-/](\d{1,2})[ T]+(\d{1,2}):(\d{2})(?::(\d{2}))?/);
  if (!m) return null;
  const [, y, mo, d, h, mi, se] = m;
  return { ms: new Date(+y, +mo - 1, +d, +h, +mi, se ? +se : 0).getTime(), hasSeconds: !!se };
}
/** Keep only records at/after run start; the comparison is done at the precision the page shows. */
function sinceRunStart(records: string[], runStartMs: number, tag: string): string[] {
  return records.filter((r) => {
    const t = recordTime(r);
    expect(t, `[${tag}] cannot parse a change time from history record ${JSON.stringify(r)} ⇒ gauge broken, 报主线`).not.toBeNull();
    const floor = t!.hasSeconds ? Math.floor(runStartMs / 1000) * 1000 : Math.floor(runStartMs / 60000) * 60000;
    return t!.ms >= floor;
  });
}

const isZn = (r: string) => new RegExp(`(^|[^A-Za-z])${ELEMENT}([^A-Za-z]|$)`).test(r);
const isCreate = (r: string) => /新建|新增|创建|CREATE/.test(r); // run 4: page label is「新建」
const isUpdate = (r: string) => /修改|更新|编辑|UPDATE/.test(r);
const isDelete = (r: string) => /删除|DELETE/.test(r);
const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

// ═══════════════════════════════════════════════════════════════════
// T-GL-01 · AC-19
// ═══════════════════════════════════════════════════════════════════

test.describe.configure({ mode: 'serial' });

test('T-GL-01 · AC-19 策略例外 9 位：录入 → 保存 → 修改 → 历史 → 删除', async ({ page }) => {
  ensureEvid();
  const errs: string[] = [];
  page.on('pageerror', (e) => { errs.push(e.message); evidLog(`PAGEERROR ${e.message}`); });
  page.on('console', (m) => { if (m.type() === 'error') evidLog(`CONSOLE.error ${m.text().slice(0, 300)}`); });
  page.on('requestfailed', (r) => evidLog(`REQFAIL ${r.url()} ${r.failure()?.errorText}`));

  // ── precondition P-8 + guards (all read-only) ──
  await assertEnv();
  const preCount = znCount();
  const preDefault = defaultMd5();
  // Run start taken from the DB clock (log changed_at is DB time); page-side filter uses the same instant.
  const runStartTs = psql(`select to_char(now(), 'YYYY-MM-DD"T"HH24:MI:SS.USOF')`);
  const runStartMs = new Date(runStartTs.replace(/([+-]\d{2})$/, '$1:00')).getTime();
  expect(Number.isFinite(runStartMs), `cannot parse run start ${runStartTs}`).toBe(true);
  const preLog = logRows(runStartTs);
  evidLog(`PRE runStart=${runStartTs} (node clock ${new Date().toISOString()}); Zn exception count=${preCount}; default strategy md5=${preDefault}; Zn log rows since start=${preLog.length}`);
  expect(preLog.length, 'no log row can exist after run start yet ⇒ clock/gauge problem').toBe(0);
  expect(preDefault, 'P-8: CUST-0729-QA must have a default strategy').not.toBe('(none)');
  // A leftover Zn row is NOT ours → do not touch it, stop and report (it may belong to a previous failed run).
  expect(preCount, 'P-8 violated: CUST-0729-QA already has a Zn exception ⇒ 🚫 不动它，停下报主线').toBe(0);

  let createdId: string | null = null;
  try {
    await uiLogin(page);
    await openElementStrategyTab(page);
    await shot(page, 'AC19-00-元素价格策略页签');
    expect(await znListRows(page).count(), 'before step 1 the list must have no Zn row').toBe(0);

    // ── Step 1: 新增例外 → 输入 → 分别失焦 ──
    await test.step('1 新增例外，系数/加价失焦舍入', async () => {
      await activePane(page).getByRole('button', { name: /新增例外/ }).first().click();
      const drawer = openDrawer(page, /新增元素例外/);
      await drawer.locator('.ant-drawer-body').waitFor({ state: 'visible' });
      await pickSelect(drawer, '元素', ELEMENT, new RegExp(`(^|\\s)${ELEMENT}(\\b|\\s|（|\\(|$)`));
      await pickSelect(drawer, '价格源', SOURCE_NAME, new RegExp(esc(SOURCE_NAME)));
      await pickSelect(drawer, '取值方式', METHOD_LABEL, new RegExp(esc(METHOD_LABEL)));

      const f = await numberInput(drawer, '系数');
      await f.fill(IN_FACTOR);
      await drawer.locator('.ant-drawer-title').click(); // blur
      const fShown = await f.inputValue();
      evidLog(`[1] 系数 typed ${IN_FACTOR} → after blur "${fShown}"`);

      const p = await numberInput(drawer, '加价');
      await p.fill(IN_PREMIUM);
      await drawer.locator('.ant-drawer-title').click(); // blur
      const pShown = await p.inputValue();
      evidLog(`[1] 加价 typed ${IN_PREMIUM} → after blur "${pShown}"`);
      await shot(page, 'AC19-01-失焦后');

      expect(fShown, '[1] 系数 input must not be empty after blur').not.toBe('');
      expect(pShown, '[1] 加价 input must not be empty after blur').not.toBe('');
      expect(fShown, 'AC-19 ① 系数失焦后显示').toBe(SHOW_FACTOR_1);
      expect(pShown, 'AC-19 ① 加价失焦后显示').toBe(SHOW_PREMIUM);

      // ── Step 2: 保存 → 列表 ──
      await saveDrawer(drawer);
    });

    await test.step('2 保存后列表 Zn 行 系数/加价', async () => {
      const rows = znRows();
      evidLog(`[2] DB Zn rows = ${JSON.stringify(rows)}`);
      expect(rows.length, '[2] save should create exactly one Zn exception').toBe(1);
      createdId = rows[0].id;
      // auxiliary DB cross-check (supports AC-19 ②; the AC itself is about the list display)
      expect(rows[0].source_id, '[2] source = 长江有色网').toBe(SOURCE_ID);
      expect(rows[0].method, '[2] method = LATEST (最新一条价)').toBe('LATEST');
      expect(normDec(rows[0].factor), '[2] DB factor').toBe(SHOW_FACTOR_1);
      expect(normDec(rows[0].premium), '[2] DB premium').toBe(SHOW_PREMIUM);

      const v = await readZnRow(page, '2');
      await shot(page, 'AC19-02-保存后列表');
      expect(v.factor, 'AC-19 ② 列表「系数」').toBe(SHOW_FACTOR_1);
      expect(v.premium, 'AC-19 ② 列表「加价」').toBe(SHOW_PREMIUM);
    });

    // ── Step 3: 编辑 → 系数 1.123456788 → 保存 ──
    await test.step('3 编辑系数 → 保存 → 列表', async () => {
      await selectZnAndClickToolbar(page, /^(edit\s+)?编\s*辑$/, '3');
      const drawer = openDrawer(page, /编辑元素例外/);
      await drawer.locator('.ant-drawer-body').waitFor({ state: 'visible' });
      const f = await numberInput(drawer, '系数');
      const before = await f.inputValue();
      const pBefore = await (await numberInput(drawer, '加价')).inputValue();
      evidLog(`[3] edit drawer prefilled 系数="${before}" 加价="${pBefore}"`);
      expect(before, '[3] edit drawer should prefill the saved factor').toBe(SHOW_FACTOR_1); // same AC口径 as step 2
      await f.fill(IN_FACTOR_2);
      await drawer.locator('.ant-drawer-title').click();
      evidLog(`[3] 系数 after blur "${await f.inputValue()}"`);
      await shot(page, 'AC19-03a-编辑抽屉');
      await saveDrawer(drawer);

      const rows = znRows();
      evidLog(`[3] DB Zn rows = ${JSON.stringify(rows)}`);
      expect(rows.length).toBe(1);
      expect(rows[0].id, '[3] edit must update the same row, not recreate').toBe(createdId);
      expect(normDec(rows[0].factor), '[3] DB factor').toBe(SHOW_FACTOR_2);

      const v = await readZnRow(page, '3');
      await shot(page, 'AC19-03b-修改后列表');
      // value-distinct check: the same gauge that read 1.123456789 in step 2 must now read 1.123456788
      expect(v.factor, 'AC-19 ③ 列表「系数」').toBe(SHOW_FACTOR_2);
      expect(v.premium, '[3] 加价 unchanged').toBe(SHOW_PREMIUM);
    });

    // ── Step 4: 变更历史 ──
    await test.step('4 变更历史：修改记录 + 新增记录摘要', async () => {
      const dbLog = logRows(runStartTs);
      evidLog(`[4] DB Zn log rows since run start = ${JSON.stringify(dbLog)}`);
      expect(dbLog.map((l) => l.action), '[4] our ops should have logged CREATE then UPDATE').toEqual(['CREATE', 'UPDATE']);

      const { drawer, records } = await readHistory(page, '4');
      await shot(page, 'AC19-04-变更历史');
      const zn = sinceRunStart(records.filter(isZn), runStartMs, '4');
      const updates = zn.filter((r) => isUpdate(r) && !isCreate(r) && !isDelete(r));
      const creates = zn.filter((r) => isCreate(r) && !isDelete(r));
      evidLog(`[4] Zn records=${zn.length} update=${updates.length} create=${creates.length}`);
      // Since run start there is exactly one UPDATE (DB-checked above) ⇒ it is "最新一条修改记录" regardless of sort order.
      expect(updates.length, '[4] exactly one Zn modification record expected in the drawer').toBe(1);
      expect(creates.length, '[4] exactly one Zn create record expected in the drawer').toBe(1);
      expect(updates[0], 'AC-19 ④ 修改记录：系数 1.123456789 → 1.123456788')
        .toMatch(new RegExp(`${esc(SHOW_FACTOR_1)}\\s*→\\s*${esc(SHOW_FACTOR_2)}`));
      expect(updates[0], '[4] must not be the legacy 2-digit text').not.toMatch(/1\.12\s*→\s*1\.12(?!\d)/);
      expect(creates[0], 'AC-19 ④ 新增记录摘要：系数 1.123456789').toContain(SHOW_FACTOR_1);
      expect(creates[0], 'AC-19 ④ 新增记录摘要：加价 0.000000002').toContain(SHOW_PREMIUM);
      // informational only (AC does not specify): premium was not changed in step 3
      evidLog(`[4] (info) update record mentions 加价: ${/加价/.test(updates[0])}`);
      await closeDrawer(page, drawer);
    });

    // ── Step 5: 删除 → 列表无 Zn → 历史删除记录 ──
    await test.step('5 删除例外 → 列表无 Zn → 历史删除记录', async () => {
      await selectZnAndClickToolbar(page, /^(delete\s+)?删\s*除$/, '5');
      // confirm (run 5 snapshot): dialog「确认删除选中的 1 条元素例外？」listing「Zn 锌」with buttons「取 消」「删 除」
      const dlg = page.getByRole('dialog', { name: /确认删除选中的\s*1\s*条元素例外/ });
      await dlg.waitFor({ state: 'visible' });
      evidLog(`[5] confirm dialog text = ${JSON.stringify((await dlg.innerText()).replace(/\s+/g, ' '))}`);
      await expect(dlg, '[5] dialog should list the Zn row being deleted').toContainText(ELEMENT);
      const confirmBtn = dlg.getByRole('button', { name: /^删\s*除$/ });
      await confirmBtn.waitFor({ state: 'visible' });
      evidLog(`[5] confirm button text = "${(await confirmBtn.innerText()).trim()}"`);
      await shot(page, 'AC19-05a-删除确认');
      await confirmBtn.click();
      await page.waitForLoadState('networkidle');

      await expect(znListRows(page), 'AC-19 ⑤ 列表无 Zn 行').toHaveCount(0);
      // non-vacuity: the exception table itself is still rendered (the 0 is not "table missing")
      await expect(exceptionTable(page)).toBeVisible();
      await shot(page, 'AC19-05b-删除后列表');
      const c = znCount();
      evidLog(`[5] DB Zn count after delete = ${c}`);
      expect(c, '[5] DB row gone').toBe(0);

      const { drawer, records } = await readHistory(page, '5');
      await shot(page, 'AC19-05c-变更历史-删除记录');
      const dels = sinceRunStart(records.filter(isZn), runStartMs, '5').filter(isDelete);
      evidLog(`[5] Zn delete records=${dels.length}`);
      expect(dels.length, '[5] exactly one Zn delete record expected').toBe(1);
      expect(dels[0], 'AC-19 ⑤ 删除记录摘要：系数 1.123456788').toContain(SHOW_FACTOR_2);
      await closeDrawer(page, drawer);
    });

    expect(errs, `pageerror: ${errs.join(' | ')}`).toEqual([]);
  } finally {
    // ── cleanup: only an id that did not exist at start (preCount was 0 ⇒ any Zn row now is ours) ──
    let left: ZnRow[] = [];
    try { left = znRows(); } catch (e) { evidLog(`🚨 cleanup: cannot query Zn rows: ${e}`); }
    for (const r of left) {
      evidLog(`cleanup: Zn exception ${r.id} still exists → DELETE via API`);
      const ctx = await apiContext();
      const resp = await ctx.delete(`/api/cpq/element-price/strategies/exceptions/${r.id}`);
      evidLog(`cleanup: DELETE ${r.id} → ${resp.status()} ${(await resp.text()).slice(0, 200)}`);
      await ctx.dispose();
    }
    const finalCount = znCount();
    const finalDefault = defaultMd5();
    const finalLog = logRows(runStartTs);
    const report = [
      `SELECT count(*) FROM element_price_strategy WHERE customer_no='${CUSTOMER_NO}' AND element_code='${ELEMENT}' → ${finalCount}`,
      `default strategy md5 before=${preDefault} after=${finalDefault}`,
      `element_price_strategy_log (${CUSTOMER_NO} × ${ELEMENT}) rows written since run start ${runStartTs}: ${finalLog.length} (accepted residue):`,
      ...finalLog.map((l) => `  ${l.changed_at} ${l.action} ${JSON.stringify(l.snapshot)}`),
    ].join('\n');
    evidLog(`FINAL\n${report}`);
    fs.writeFileSync(path.join(EVID, 'AC-19-清理后SQL.txt'), report + '\n', 'utf-8');
    expect(finalCount, '🚨 finally: Zn exception still present ⇒ 报主线').toBe(0);
    expect(finalDefault, '🚨 default strategy of CUST-0729-QA changed ⇒ 越界，报主线').toBe(preDefault);
  }
});
