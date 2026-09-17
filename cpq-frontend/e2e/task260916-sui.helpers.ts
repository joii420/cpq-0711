/**
 * task-260916 · 元素价格支持 9 位小数 · 测试分片 S-UI 共用助手（草稿）。
 *
 * 用例只从 需求文档.md §③ 的 AC 原文派生，不读实现代码。页面结构来自
 * task-260722 / update-260724 / task-260729 / task-260907 的文档与原型，执行时以 allInnerTexts() 探查为准。
 *
 * 纪律（派工 prompt d 段 + test.md §1/§4）：
 *  - 库：cpq_db_0724。SQL 只允许 SELECT/WITH，且会话强制只读；
 *  - 私有写面：价格源 TEST-PM-SRC-A（56c41905-…）× 日期 2020-09-08/09/10 的日价 + AC-4 新建的 1 张正泰单；
 *  - 只读用例在网络层拦截一切非白名单写请求（abort + 记录），防误点「立即生成一次 / 通过 / 驳回 / 保存」；
 *  - 🚫 不打开 QT-20260916-0875~0881（URL 守卫）。
 */
import { expect, type Locator, type Page, type TestInfo } from '@playwright/test';
import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';

// ---------------------------------------------------------------- 坐标
export const PHASE = (process.env.SUI_PHASE || 'after') as 'before' | 'after';
if (!['before', 'after'].includes(PHASE)) throw new Error(`SUI_PHASE 只能是 before/after：${PHASE}`);
export const BASE_URL = process.env.PW_BASE_URL!;
export const BACKEND_URL = process.env.PW_BACKEND_URL!;
export const EXPECT_MIGRATION = process.env.SUI_EXPECT_MIGRATION || '';
export const DB = 'cpq_db_0724';
export const WORKTREE = '/home/joii/project/cpq/.claude/worktrees/task-260916-element-price-scale9';
const PG = { host: '10.177.152.12', port: '5432', user: 'postgres', password: process.env.SUI_PGPASSWORD || 'joii5231' };

/** 取价函数改前底稿 / V445 后的 md5（test.md §4 第 1 条）。 */
export const OLD_FN_MD5 = '0b55508cabf384f70437c58c0e8990d3';
export const NEW_FN_MD5 = 'eee7891ed23fa2f72115af73b710607a';

export const SRC_A = { id: '56c41905-b695-4675-ab3b-ae0b37d0f2e9', name: 'TEST-PM-SRC-A' };
export const MY_DATES = ['2020-09-08', '2020-09-09', '2020-09-10'] as const;
export const SRC_CJ = '长江有色网';
export const CUST_ROCKWELL = { no: 'CUST-0001', name: '罗克韦尔' };
export const CUST_CHINT = { no: 'CUST-0004', name: '正泰' };
export const FORBIDDEN_QUOTES = ['QT-20260916-0875', 'QT-20260916-0876', 'QT-20260916-0877', 'QT-20260916-0878',
  'QT-20260916-0879', 'QT-20260916-0880', 'QT-20260916-0881',
  'QT-20260916-0890', // 主线 2026-09-16 通知：22:31 PDT 他人新建的正泰单，不许打开
];

process.env.NO_PROXY = 'localhost,127.0.0.1';
process.env.no_proxy = 'localhost,127.0.0.1';

export const TASK_DIR = path.join(WORKTREE, 'dev-docs', 'task-260916-元素价格支持9位小数');
export const EVID_DIR = path.join(TASK_DIR, '证据', 'S-UI');
export const FIXTURE_DIR = path.join(EVID_DIR, 'fixtures');
export const PHASE_DIR = path.join(EVID_DIR, PHASE === 'before' ? '基线-改前' : '改后');

export function ensureDirs() {
  expect(process.cwd().startsWith(WORKTREE), `必须在 worktree 内运行（cwd=${process.cwd()}）`).toBe(true);
  for (const d of [TASK_DIR, FIXTURE_DIR]) expect(fs.existsSync(d), `目录必须存在：${d}`).toBe(true);
  fs.mkdirSync(PHASE_DIR, { recursive: true });
}

export function writeEvid(name: string, content: unknown): string {
  fs.mkdirSync(PHASE_DIR, { recursive: true });
  const p = path.join(PHASE_DIR, name);
  fs.writeFileSync(p, typeof content === 'string' ? content : JSON.stringify(content, null, 2) + '\n', 'utf8');
  console.log(`[evidence] ${p}`);
  return p;
}
export function readBaseline(name: string): any {
  const p = path.join(EVID_DIR, '基线-改前', name);
  expect(fs.existsSync(p), `缺改前基线 ${p} —— 必须先以 SUI_PHASE=before 在迁移/合并前跑过一次，缺基线则该断言判【未验证】`).toBe(true);
  return JSON.parse(fs.readFileSync(p, 'utf8'));
}

export function readBaselineRaw(name: string): string {
  const p = path.join(EVID_DIR, '基线-改前', name);
  expect(fs.existsSync(p), `缺改前基线 ${p} —— 缺基线则该断言判【未验证】`).toBe(true);
  return fs.readFileSync(p, 'utf8');
}

/** 截图直接落任务目录（不留在 test-results/，下一轮会被清空）。 */
export async function shot(page: Page | Locator, name: string) {
  fs.mkdirSync(PHASE_DIR, { recursive: true });
  const p = path.join(PHASE_DIR, `${name}.png`);
  if ('goto' in page) await page.screenshot({ path: p, fullPage: true });
  else await page.screenshot({ path: p });
  console.log(`[shot] ${p}`);
  return p;
}

/** 选择器落空时先 dump 再下结论。 */
export async function dump(page: Page, tag: string, scope = 'body') {
  const t = (await page.locator(scope).first().allInnerTexts()).join('\n').replace(/\n{2,}/g, '\n');
  writeEvid(`dump-${tag}.txt`, t);
  console.log(`[dump:${tag}] ${t.slice(0, 1500)}`);
}

// ---------------------------------------------------------------- 只读 SQL
export function sql<T = Record<string, any>>(query: string): T[] {
  const q = query.trim().replace(/;+\s*$/, '');
  if (!/^(select|with)\b/i.test(q)) throw new Error(`S-UI 只允许 SELECT/WITH：${q.slice(0, 60)}`);
  const out = execFileSync('psql', [
    '-h', PG.host, '-p', PG.port, '-U', PG.user, '-d', DB,
    '-X', '-A', '-t', '-q', '-v', 'ON_ERROR_STOP=1',
    '-c', 'SET default_transaction_read_only = on',
    '-c', `SELECT coalesce(jsonb_agg(q), '[]'::jsonb)::text FROM (${q}) q`,
  ], { env: { ...process.env, PGPASSWORD: PG.password }, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 });
  const lines = out.split('\n').map(s => s.trim()).filter(Boolean);
  if (lines.length !== 1) throw new Error(`psql 输出应恰 1 行，实为 ${lines.length}：${q.slice(0, 80)}`);
  return JSON.parse(lines[0]) as T[];
}

/** psql 原样文本输出（证据用）。同样强制只读。 */
export function sqlText(query: string): string {
  const q = query.trim().replace(/;+\s*$/, '');
  if (!/^(select|with)\b/i.test(q)) throw new Error(`S-UI 只允许 SELECT/WITH：${q.slice(0, 60)}`);
  return execFileSync('psql', ['-h', PG.host, '-p', PG.port, '-U', PG.user, '-d', DB, '-X', '-v', 'ON_ERROR_STOP=1',
    '-c', 'SET default_transaction_read_only = on', '-c', q],
    { env: { ...process.env, PGPASSWORD: PG.password }, encoding: 'utf8' });
}

/** 数值相等（按十进制比较，不走浮点）。numeric 列经 jsonb 出来是 JSON number，这里先转字符串再规整。 */
export function decEq(actual: unknown, expected: string): boolean {
  return normDec(String(actual)) === normDec(expected);
}
export function normDec(s: string): string {
  const m = /^(-?)(\d+)(?:\.(\d*))?$/.exec(s.trim());
  if (!m) return `NaN(${s})`;
  const intPart = m[2].replace(/^0+(?=\d)/, '');
  const frac = (m[3] || '').replace(/0+$/, '');
  return `${m[1]}${intPart}${frac ? '.' + frac : ''}`;
}

// ---------------------------------------------------------------- 环境验明正身（testing.md §4.2 / test.md §4）
function listenerPid(port: number): string {
  const ss = execFileSync('ss', ['-ltnpH', `sport = :${port}`], { encoding: 'utf8' });
  const pid = /pid=(\d+)/.exec(ss)?.[1];
  expect(pid, `端口 ${port} 上应有监听进程；ss：${ss}`).toBeTruthy();
  return pid!;
}
function procCwd(pid: string): string {
  try { return fs.readlinkSync(`/proc/${pid}/cwd`); } catch { return '?'; }
}

/** 没有别的 playwright 在跑（排除本进程与父进程）。 */
export function assertNoOtherPlaywright() {
  let out = '';
  try { out = execFileSync('pgrep', ['-af', 'node.*[p]laywright test'], { encoding: 'utf8' }); } catch { out = ''; }
  // 排除本进程整条祖先链（含 shell 包装：其命令行里本身就带匹配串），且只认 comm=node 的进程
  const mine = new Set<string>();
  for (let pid = String(process.pid); pid && pid !== '0' && !mine.has(pid);) {
    mine.add(pid);
    try { pid = fs.readFileSync(`/proc/${pid}/stat`, 'utf8').replace(/^.*\)\s+\S+\s+/, '').split(' ')[0]; } catch { break; }
  }
  // 实测：Node 24 的 /proc/<pid>/comm 是「MainThread」而非「node」⇒ 按可执行文件名判断
  const exe = (pid: string) => { try { return path.basename(fs.readlinkSync(`/proc/${pid}/exe`)); } catch { return ''; } };
  const others = out.split('\n').filter(Boolean)
    .filter(l => !mine.has(l.split(' ')[0]))
    .filter(l => exe(l.split(' ')[0]) === 'node');
  console.log(`[pgrep] others=${JSON.stringify(others)}`);
  expect(others, '有别的 playwright 在跑（global-setup 会写开发库 user 表）⇒ 停下报主线').toEqual([]);
}

/**
 * after：前后端都必须是 worktree 代码、后端连 cpq_db_0724、新取价函数已生效；
 * before：前后端是主仓 master（5174/8081），取价函数仍是改前底稿。
 */
export async function assertEnvIdentity(page: Page) {
  const bPort = Number(new URL(BACKEND_URL).port);
  const fPort = Number(new URL(BASE_URL).port);
  const bPid = listenerPid(bPort), fPid = listenerPid(fPort);
  const bCwd = procCwd(bPid), fCwd = procCwd(fPid);
  console.log(`[identity] backend :${bPort} pid=${bPid} cwd=${bCwd}; frontend :${fPort} pid=${fPid} cwd=${fCwd}`);
  if (PHASE === 'after') {
    expect(bCwd.startsWith(WORKTREE), '后端必须是 worktree 代码').toBe(true);
    expect(fCwd.startsWith(WORKTREE), '前端必须是 worktree 代码').toBe(true);
    expect([8081, 5174], '改后验收不许占用共享 8081/5174').not.toContain(bPort);
    expect([8081, 5174]).not.toContain(fPort);
  } else {
    expect(bCwd.startsWith(WORKTREE), '基线必须跑在主仓 master 代码上').toBe(false);
    expect(fCwd.startsWith(WORKTREE)).toBe(false);
  }
  // 后端 401（未登录）= 应用在跑、鉴权正常
  const anon = await page.request.get(`${BACKEND_URL}/api/cpq/components`, { failOnStatusCode: false });
  expect(anon.status(), '后端业务端点未登录应 401').toBe(401);
  const fe = await page.request.get(`${BASE_URL}/`, { failOnStatusCode: false });
  expect(fe.status(), '前端首页应 200').toBe(200);

  // 连的是哪个库：totalElements 与 cpq_db_0724 quotation 行数对上（CLAUDE.md 口径）
  await apiLogin(page);
  const r = await page.request.get(`${BACKEND_URL}/api/cpq/quotations?page=1&size=1`);
  expect(r.status()).toBe(200);
  const j = await r.json();
  const total = Number(j?.data?.totalElements ?? j?.totalElements);
  const cnt = Number(sql<{ c: number }>('SELECT count(*) AS c FROM public.quotation')[0].c);
  console.log(`[identity] api totalElements=${total} ${DB}.quotation=${cnt}`);
  expect(total > 0 && total === cnt, `后端必须连 ${DB}（api=${total} db=${cnt}；并发建单可能造成瞬时不等，重试一次仍不等则停下报告）`).toBe(true);

  // 取价函数版本
  const fn = sql<{ md5: string; r4: boolean; r9: boolean }>(`
    SELECT md5(pg_get_functiondef('f_customer_element_price'::regproc)) AS md5,
           pg_get_functiondef('f_customer_element_price'::regproc) ~ 'ROUND\\([^;]*,\\s*4\\s*\\)' AS r4,
           pg_get_functiondef('f_customer_element_price'::regproc) ~ 'ROUND\\([^;]*,\\s*9\\s*\\)' AS r9`)[0];
  console.log(`[identity] f_customer_element_price md5=${fn.md5} round4=${fn.r4} round9=${fn.r9}`);
  if (PHASE === 'after') {
    expect(fn.md5, '取价函数应为 V445 版本（md5 不符 ⇒ 停下报主线）').toBe(NEW_FN_MD5);
    expect(EXPECT_MIGRATION, '改后运行必须给 SUI_EXPECT_MIGRATION（主线告知的新迁移版本号）').not.toBe('');
    const fw = sql<{ success: boolean }>(`SELECT success FROM flyway_schema_history WHERE version = '${EXPECT_MIGRATION.replace(/'/g, '')}'`);
    console.log(`[identity] flyway ${EXPECT_MIGRATION} = ${JSON.stringify(fw)}`);
    expect(fw.length).toBe(1);
    expect(fw[0].success).toBe(true);
  } else {
    // D-10：V445 已于 23:04 PDT 先行应用到开发库。before 阶段只采页面层基线（前端 + Java 仍为改前代码，
    // AC-12~15 显示位数与 AC-16/17/22 不受 V445 影响）；SQL 层改前值以 RX-2-改前.txt 为准。这里只记录不判。
    console.log(`[identity] before 阶段函数 md5=${fn.md5}（V445 已先行应用属预期；SQL 改前值见 RX-2）`);
  }
}

// ---------------------------------------------------------------- 登录与守卫
/**
 * 登录会话缓存：after 第 1 轮每条用例各登录一次，触发服务端「登录尝试过于频繁」429（admin 未被锁，user 表未动）。
 * ⇒ 同一前端端口只登录一次，cookie 存 e2e/.auth/（已被 .gitignore 忽略，不进证据目录），后续用例复用，失效才重登。
 */
const AUTH_FILE = () => path.join(WORKTREE, 'cpq-frontend', 'e2e', '.auth', `task260916-sui-${new URL(BASE_URL).port}.json`);
export async function apiLogin(page: Page) {
  const f = AUTH_FILE();
  if (fs.existsSync(f)) {
    const st = JSON.parse(fs.readFileSync(f, 'utf8'));
    if (st.cookies?.length) await page.context().addCookies(st.cookies);
    const probe = await page.request.get(`${BASE_URL}/api/cpq/components?page=1&size=1`, { failOnStatusCode: false });
    if (probe.status() !== 401 && probe.status() !== 403) return;
  }
  const res = await page.request.post(`${BASE_URL}/api/cpq/auth/login`, { data: { username: 'admin', password: 'Admin@2026' } });
  const body = await res.text();
  expect(res.status(), `admin 登录应 200（被锁请停下报主线，🚫 不许改 user 表）；原文：${body.slice(0, 300)}`).toBe(200);
  fs.mkdirSync(path.dirname(f), { recursive: true });
  await page.context().storageState({ path: f });
}

export async function gotoApp(page: Page, url: string) {
  guardUrl(url);
  await page.goto(url);
  if (/\/login|change-password/.test(page.url())) { await apiLogin(page); await page.goto(url); }
  await page.waitForLoadState('networkidle').catch(() => {});
}

function guardUrl(url: string) {
  for (const q of FORBIDDEN_QUOTES) expect(url.includes(q), `🚫 禁止打开 ${q}`).toBe(false);
}

/**
 * 写请求拦截：非 GET 且不在白名单 ⇒ abort + 记录。只读用例结束时断言记录为空。
 * allow 里放「本用例允许的写/计算型 POST」的路径片段。
 */
export async function blockWrites(page: Page, allow: RegExp[] = []): Promise<string[]> {
  const blocked: string[] = [];
  const base: RegExp[] = [/\/api\/cpq\/auth\/login$/, /\/api\/cpq\/auth\/refresh/];
  await page.route('**/api/**', async (route) => {
    const req = route.request();
    const m = req.method();
    const u = req.url();
    if (m === 'GET' || m === 'HEAD' || m === 'OPTIONS' || [...base, ...allow].some(r => r.test(u))) return route.continue();
    blocked.push(`${m} ${u}`);
    console.log(`[blockWrites] ABORT ${m} ${u}`);
    return route.abort('blockedbyclient');
  });
  page.on('framenavigated', (f) => { if (f === page.mainFrame()) guardUrl(f.url()); });
  return blocked;
}

// ---------------------------------------------------------------- 表格读取（antd）
export interface GridRow { cells: string[]; byHeader: Record<string, string> }
/** 读表：表头取最后一行 th（跳过滚动条列）；数据行优先 tr.ant-table-row，没有则取 tbody tr（报价卡片可能是自绘表格）。 */
export async function readGrid(table: Locator): Promise<{ headers: string[]; rows: GridRow[] }> {
  const raw = await table.evaluate((root: Element) => {
    const clean = (s: string) => s.replace(/\s+/g, ' ').trim();
    const ths = Array.from(root.querySelectorAll('thead tr:last-child th'))
      .filter(th => !th.classList.contains('ant-table-cell-scrollbar'));
    const headers = ths.map(th => clean((th as HTMLElement).innerText || ''));
    const antRows = root.querySelectorAll('tbody tr.ant-table-row');
    const rowEls = antRows.length ? antRows : root.querySelectorAll('tbody tr');
    const rows = Array.from(rowEls)
      .map(tr => Array.from(tr.querySelectorAll(':scope > td')).map(td => {
        // 可编辑单元格（报价卡片）是 <input>，innerText 为空 ⇒ 退回读输入框的值（排除勾选框/单选框）
        const text = clean((td as HTMLElement).innerText || '');
        if (text) return text;
        const vals = Array.from(td.querySelectorAll('input, textarea'))
          .filter(i => !['checkbox', 'radio', 'hidden', 'file'].includes((i as HTMLInputElement).type))
          .map(i => clean((i as HTMLInputElement).value || ''));
        return vals.join(' ');
      }));
    return { headers, rows };
  });
  const rows = raw.rows.map(cells => {
    const byHeader: Record<string, string> = {};
    raw.headers.forEach((h, i) => { if (h) byHeader[h] = cells[i] ?? ''; });
    return { cells, byHeader };
  });
  return { headers: raw.headers, rows };
}
/** 取列值：header 精确名优先，否则按正则找第一个匹配的表头。 */
export function col(row: GridRow, header: string | RegExp): string {
  if (typeof header === 'string' && header in row.byHeader) return row.byHeader[header];
  const re = typeof header === 'string' ? new RegExp(header.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) : header;
  const k = Object.keys(row.byHeader).find(h => re.test(h));
  if (k === undefined) throw new Error(`找不到列 ${header}；现有表头=${JSON.stringify(Object.keys(row.byHeader))}`);
  return row.byHeader[k];
}
/** 元素列形如「Cu」或「Cu 铜」：按首个 token 精确匹配。 */
export function isElem(cell: string, code: string): boolean {
  return cell.split(/[\s·(（]/)[0] === code;
}

// ---------------------------------------------------------------- antd 控件
/** 按钮：文案逐字之间允许空白（antd 两字按钮「保 存」），前后允许图标/符号（「＋ 新建」「🧮 策略试算」「元素价格 ▾」）。 */
export const btn = (scope: Locator | Page, text: string) =>
  scope.locator('button:visible').filter({
    hasText: new RegExp(`^[^\\u4e00-\\u9fa5]*${text.split('').map(c => c.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('\\s*')}[^\\u4e00-\\u9fa5]*$`),
  }).first();

export function formItem(scope: Locator | Page, label: string | RegExp): Locator {
  // has 子定位必须从 page 起（相对被过滤元素求值）；以 scope 起会变成「表单项里再找一个抽屉」，永远落空
  const root: Page = 'goto' in scope ? scope : scope.page();
  return scope.locator('.ant-form-item:visible').filter({ has: root.locator('label').filter({ hasText: label }) }).first();
}

/**
 * 非 antd Form.Item 结构的表单（实测：价格导入抽屉的「价格源」不是 .ant-form-item + <label>）：
 * 取「含该标签文字、且含输入控件」的最深容器（祖先链在文档序中越深越靠后 ⇒ last()）。
 */
export function fieldBox(scope: Locator, label: string): Locator {
  const root = scope.page();
  // 标签按整段文字锚定（允许前置必填星号）：实测占位文字「请选择价格源」也含「价格源」，不锚定会选中下拉框内部容器
  const esc = label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const re = new RegExp(`^\\*?\\s*${esc}\\s*$`);
  return scope.locator('div:visible')
    .filter({ has: root.getByText(re) })
    .filter({ has: root.locator('input, .ant-select, .ant-picker') })
    .last();
}

/** 下拉：点开 → 在搜索框输入关键字过滤（虚拟滚动）→ 按完整文案精确点选 → 以 innerText 包含校验回显。 */
export async function pickSelect(page: Page, select: Locator, keyword: string, optionText: string | RegExp) {
  await select.click();
  const input = select.locator('input').first();
  if (await input.isEditable().catch(() => false)) await input.fill(keyword);
  const dd = page.locator('.ant-select-dropdown:visible').last();
  const opt = dd.locator('.ant-select-item-option').filter({
    hasText: typeof optionText === 'string' ? new RegExp(`^\\s*${optionText.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*$`) : optionText,
  }).first();
  await expect(opt, `下拉里找不到选项 ${optionText}`).toBeVisible();
  await opt.click();
  await expect.poll(async () => (await select.innerText()).replace(/\s+/g, ' '), `下拉回显应含 ${keyword}`).toContain(keyword);
}

/** 单日期 DatePicker：清空后键入并回车，回读 inputValue。 */
export async function setDate(input: Locator, value: string) {
  await input.click();
  await input.press('Control+A');
  await input.fill(value);
  await input.press('Enter');
  await expect(input).toHaveValue(value);
}

/** RangePicker：两个 input 依次键入。 */
export async function setRange(picker: Locator, from: string, to: string) {
  const inputs = picker.locator('input');
  await expect(inputs, 'RangePicker 应有 2 个 input').toHaveCount(2);
  await inputs.nth(0).click();
  await inputs.nth(0).press('Control+A');
  await inputs.nth(0).fill(from);
  await inputs.nth(0).press('Enter');
  await inputs.nth(1).click();
  await inputs.nth(1).press('Control+A');
  await inputs.nth(1).fill(to);
  await inputs.nth(1).press('Enter');
  await expect(inputs.nth(0)).toHaveValue(from);
  await expect(inputs.nth(1)).toHaveValue(to);
}

/** 等表格出现真实数据行（接口返回前 networkidle 可能已判定空闲；空态行不是 .ant-table-row）。 */
export async function waitRows(wrapper: Locator, timeout = 60_000) {
  await expect(wrapper.locator('tbody tr.ant-table-row').first(), '表格应出现数据行').toBeVisible({ timeout });
}

export const drawer = (page: Page, title: string | RegExp) =>
  page.locator('.ant-drawer:visible').filter({ has: page.locator('.ant-drawer-title').filter({ hasText: title }) }).last();

// ---------------------------------------------------------------- 页面入口
/** 元素管理页（/master-data-hub → 元素 页签）。 */
export async function openElementPage(page: Page) {
  await gotoApp(page, '/master-data-hub');
  // 实测 after 第 2 轮出现过一次整页白屏（未渲染）⇒ 等不到页签就刷新重试一次
  const tab = page.getByRole('tab', { name: '元素' });
  if (!(await tab.isVisible({ timeout: 20_000 }).catch(() => false))) {
    console.log('[openElementPage] 元素页签 20s 未出现，刷新重试');
    await page.reload();
    await page.waitForLoadState('networkidle').catch(() => {});
  }
  await tab.click();
  // 实测（before 第 1 轮）：页面上没有「元素管理」字样；以元素页搜索框出现为准
  await expect(page.getByPlaceholder('搜索 元素编号 / 符号 / 中文名')).toBeVisible({ timeout: 30_000 });
  await page.waitForSelector('.ant-table-row', { timeout: 30_000 });
}
/** 工具栏「元素价格 ▾」→ 菜单项。 */
export async function elementPriceMenu(page: Page, item: '价格导入' | '元素价格表' | '价格源管理') {
  const trigger = page.locator('button:visible').filter({ hasText: /元素价格/ }).first();
  await expect(trigger, '工具栏应有「元素价格 ▾」').toBeVisible();
  await trigger.click();
  const mi = page.locator('.ant-dropdown:visible .ant-dropdown-menu-item').filter({ hasText: item }).first();
  if (!(await mi.isVisible().catch(() => false))) { await trigger.hover(); }
  await expect(mi, `下拉菜单应有「${item}」`).toBeVisible();
  await mi.click();
}
/** 定价管理 → 定价策略 → 选客户 → 页签。 */
export async function openPricingCustomerTab(page: Page, customerName: string, tab: '元素价格策略' | '价格调整策略') {
  await gotoApp(page, '/pricing');
  const search = page.locator('input[placeholder="搜索客户"]').first();
  await search.fill(customerName);
  await search.press('Enter');
  await page.locator('.ant-list-item').filter({ hasText: customerName }).first().click();
  await page.locator('.ant-tabs-tab').filter({ hasText: tab }).first().click();
  await page.waitForLoadState('networkidle').catch(() => {});
}

// ---------------------------------------------------------------- 私有写面：只删 TEST-PM-SRC-A × MY_DATES 的日价
export function myPriceRows(): Array<{ id: string; element_name: string; price_date: string; raw_price: string }> {
  return sql(`SELECT id::text, element_name, price_date::text, raw_price::text
              FROM element_daily_price
              WHERE source_id = '${SRC_A.id}' AND price_date IN (${MY_DATES.map(d => `DATE '${d}'`).join(',')})
              ORDER BY price_date, element_name`);
}
export async function cleanupMyPrices(page: Page, tag: string) {
  const before = myPriceRows();
  console.log(`[cleanup:${tag}] before=${JSON.stringify(before)}`);
  for (const r of before) {
    // 双保险：只删本片写面
    expect(MY_DATES as readonly string[]).toContain(r.price_date);
    const res = await page.request.delete(`${BASE_URL}/api/cpq/element-price/prices/${r.id}`, { failOnStatusCode: false });
    console.log(`[cleanup:${tag}] DELETE ${r.id} (${r.element_name} ${r.price_date}) -> ${res.status()}`);
  }
  const after = myPriceRows();
  const txt = sqlText(`SELECT id, element_name, price_date, raw_price FROM element_daily_price
                       WHERE source_id = '${SRC_A.id}' AND price_date IN (${MY_DATES.map(d => `DATE '${d}'`).join(',')})`);
  writeEvid(`cleanup-${tag}.txt`, `before=${JSON.stringify(before, null, 1)}\n\nafter (SQL):\n${txt}`);
  expect(after, `清理后 ${SRC_A.name} × ${MY_DATES.join('/')} 应为 0 行`).toEqual([]);
}

export function info(testInfo: TestInfo, k: string, v: unknown) {
  testInfo.annotations.push({ type: k, description: typeof v === 'string' ? v : JSON.stringify(v) });
  console.log(`[${k}] ${typeof v === 'string' ? v : JSON.stringify(v)}`);
}
