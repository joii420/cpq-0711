/**
 * repair-260918 · 大单升版超时与涨跌率显示 · 测试分片 S-UI 共用助手。
 *
 * 用例只从 问题说明.md ⑥ 的 AC 原文（AC-1 / AC-2 / AC-4 / AC-16）+ test.md §3.3 + api.md §1/§5
 * + 原型图/进度抽屉.html 派生，**未读任何实现代码**（cpq-frontend/src、cpq-backend/src/main/java）。
 * 页面结构取自父任务 task-260729 的 api.md / 原型与既有 e2e 用例（tmp-task0729-* / tmp-element-matrix-crosspage）。
 *
 * 纪律（派工 d 段）：
 *  - 零业务写入：只读开发库 cpq_db_0724 既有数据 + page.route 改写接口响应；
 *    所有非 GET 的 /api 请求一律 abort 并记录（仅放行登录），用例结束断言记录为空；
 *  - SQL 只允许 SELECT/WITH，且会话强制 default_transaction_read_only；
 *  - 证据写任务目录 证据/测试/S-UI/<RP0918_RUN>/（可用 RP0918_EVID_DIR 整体改写 —— testing.md §5.7⑤）。
 */
import { expect, type Locator, type Page, type Route, type TestInfo } from '@playwright/test';
import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';

// ---------------------------------------------------------------- 坐标
export const WORKTREE = '/home/joii/project/cpq/.claude/worktrees/repair-260918-price-adjust-large-quote';
export const BASE_URL = process.env.PW_BASE_URL!;
/** worktree = 验改后代码（默认）；master = 证伪对照（期望变红） */
export const EXPECT_TREE = (process.env.RP0918_EXPECT_TREE || 'worktree') as 'worktree' | 'master';
if (!['worktree', 'master'].includes(EXPECT_TREE)) throw new Error(`RP0918_EXPECT_TREE 只能是 worktree/master：${EXPECT_TREE}`);
export const DB = 'cpq_db_0724';
const PG = { host: '10.177.152.12', port: '5432', user: 'postgres', password: process.env.RP0918_PGPASSWORD || 'joii5231' };

process.env.NO_PROXY = 'localhost,127.0.0.1';
process.env.no_proxy = 'localhost,127.0.0.1';

export const TASK_DIR = path.join(WORKTREE, 'dev-docs', 'task-260729-客户价格调整策略和价格版本', 'repair-260918-大单升版超时与涨跌率显示');
export const EVID_DIR = process.env.RP0918_EVID_DIR
  || path.join(TASK_DIR, '证据', '测试', 'S-UI', process.env.RP0918_RUN || 'run-unnamed');

/** 开发库既有数据（问题说明.md ⑥ 环境口径，2026-09-18 实查） */
export const CUST = { no: 'CUST-0004', name: '正泰' };
export const VERSION_NO = 'V26091802';
export const AG = { code: 'Ag', name: '银' };

/** 颜色口径（问题说明.md ⑤ 第 16 条 / fronttask F-1 / 原型图 index.html） */
export const RED = 'rgb(207, 19, 34)';     // #cf1322
export const GREEN = 'rgb(56, 158, 13)';   // #389e0d
export const BLUE = 'rgb(22, 119, 255)';   // #1677ff（原型「执行中 N」）
/**
 * 元素矩阵涨跌小字在中性值（0% / —）时的颜色。
 * ⚠️ 参考色由主线提供（2026-09-18 放行阶段二消息），来源是矩阵**现网原有样式**（master 上即如此，非本次改出）。
 */
export const MATRIX_NEUTRAL = 'rgba(0, 0, 0, 0.35)';

// ---------------------------------------------------------------- 证据
export function ensureDirs() {
  expect(BASE_URL, '必须给 PW_BASE_URL 并用 -c e2e/repair260918-sui.config.ts 运行').toBeTruthy();
  expect(process.cwd().startsWith(WORKTREE), `必须在 worktree 内运行（cwd=${process.cwd()}）`).toBe(true);
  expect(fs.existsSync(TASK_DIR), `任务目录必须存在：${TASK_DIR}`).toBe(true);
  fs.mkdirSync(EVID_DIR, { recursive: true });
}
export function writeEvid(name: string, content: unknown): string {
  fs.mkdirSync(EVID_DIR, { recursive: true });
  const p = path.join(EVID_DIR, name);
  fs.writeFileSync(p, typeof content === 'string' ? content : JSON.stringify(content, null, 2) + '\n', 'utf8');
  console.log(`[evidence] ${p}`);
  return p;
}
export function appendEvid(name: string, obj: unknown) {
  fs.mkdirSync(EVID_DIR, { recursive: true });
  fs.appendFileSync(path.join(EVID_DIR, name), JSON.stringify(obj) + '\n', 'utf8');
}
/** 截图直接落任务目录（不留在 test-results/，下一轮会被清空）。 */
export async function shot(target: Page | Locator, name: string) {
  fs.mkdirSync(EVID_DIR, { recursive: true });
  const p = path.join(EVID_DIR, `${name}.png`);
  if ('goto' in target) await target.screenshot({ path: p, fullPage: true });
  else await target.screenshot({ path: p });
  console.log(`[shot] ${p}`);
  return p;
}
/** 选择器落空时先 dump 再下结论（cpq-playwright-selector-pitfalls）。 */
export async function dump(page: Page, tag: string, scope = 'body') {
  const t = (await page.locator(scope).first().allInnerTexts().catch(() => ['<dump failed>'])).join('\n').replace(/\n{2,}/g, '\n');
  writeEvid(`dump-${tag}.txt`, t);
  console.log(`[dump:${tag}] ${t.slice(0, 1500)}`);
}
export function info(testInfo: TestInfo, k: string, v: unknown) {
  testInfo.annotations.push({ type: k, description: typeof v === 'string' ? v : JSON.stringify(v) });
  console.log(`[${k}] ${typeof v === 'string' ? v : JSON.stringify(v)}`);
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

/**
 * 前置数据（问题说明.md ⑥ 环境口径）：V26091802 银 本期 28893.5 / 上期 28892.5 / 库存涨跌率 0.000035。
 * 不符 ⇒ 前置数据已漂移，用例失败信息写明「不是产品缺陷」，停下报主线。
 */
export function versionRow() {
  const rows = sql<{ id: string; status: string; current_price: string; previous_price: string; change_rate: string }>(`
    SELECT v.id::text, v.status, i.current_price::text, i.previous_price::text, i.change_rate::text
      FROM public.element_price_version_item i JOIN public.element_price_version v ON v.id = i.version_id
     WHERE v.customer_no = '${CUST.no}' AND v.version_no = '${VERSION_NO}' AND i.element_code = '${AG.code}'`);
  console.log(`[precondition] ${CUST.no} ${VERSION_NO} Ag = ${JSON.stringify(rows)}`);
  expect(rows.length, `前置数据缺失：${CUST.no} ${VERSION_NO} 银 的版本明细应恰 1 行（不是产品缺陷，停下报主线）`).toBe(1);
  const r = rows[0];
  expect(normDec(r.change_rate), `前置数据漂移：${VERSION_NO} 银 change_rate 应为 0.000035（实为 ${r.change_rate}）`).toBe('0.000035');
  expect(normDec(r.current_price)).toBe('28893.5');
  expect(normDec(r.previous_price)).toBe('28892.5');
  return r;
}
export function normDec(s: string): string {
  const m = /^(-?)(\d+)(?:\.(\d*))?$/.exec(String(s).trim());
  if (!m) return `NaN(${s})`;
  const intPart = m[2].replace(/^0+(?=\d)/, '');
  const frac = (m[3] || '').replace(/0+$/, '');
  return `${m[1]}${intPart}${frac ? '.' + frac : ''}`;
}

// ---------------------------------------------------------------- 环境验明正身
function listenerPid(port: number): string {
  const ss = execFileSync('ss', ['-ltnpH', `sport = :${port}`], { encoding: 'utf8' });
  const pid = /pid=(\d+)/.exec(ss)?.[1];
  expect(pid, `端口 ${port} 上应有监听进程；ss：${ss}`).toBeTruthy();
  return pid!;
}
function procCwd(pid: string): string {
  try { return fs.readlinkSync(`/proc/${pid}/cwd`); } catch { return '?'; }
}

/** 没有别的 playwright 在跑（排除本进程祖先链；只认可执行文件为 node 的进程）。 */
export function assertNoOtherPlaywright() {
  let out = '';
  try { out = execFileSync('pgrep', ['-af', 'node.*[p]laywright test'], { encoding: 'utf8' }); } catch { out = ''; }
  const mine = new Set<string>();
  for (let pid = String(process.pid); pid && pid !== '0' && !mine.has(pid);) {
    mine.add(pid);
    try { pid = fs.readFileSync(`/proc/${pid}/stat`, 'utf8').replace(/^.*\)\s+\S+\s+/, '').split(' ')[0]; } catch { break; }
  }
  const exe = (pid: string) => { try { return path.basename(fs.readlinkSync(`/proc/${pid}/exe`)); } catch { return ''; } };
  const others = out.split('\n').filter(Boolean)
    .filter(l => !mine.has(l.split(' ')[0]))
    .filter(l => exe(l.split(' ')[0]) === 'node');
  console.log(`[pgrep] others=${JSON.stringify(others)}`);
  expect(others, '有别的 playwright 在跑 ⇒ 停下报主线').toEqual([]);
}

/**
 * 前端必须是期望的那棵树（worktree 临时 vite / 证伪时的 master 5174）；
 * 后端经 vite 代理到共享 8081，必须连 cpq_db_0724（totalElements 与库内行数对上 —— CLAUDE.md 口径）。
 */
export async function assertEnvIdentity(page: Page) {
  const fPort = Number(new URL(BASE_URL).port);
  const fPid = listenerPid(fPort);
  const fCwd = procCwd(fPid);
  console.log(`[identity] frontend :${fPort} pid=${fPid} cwd=${fCwd} expectTree=${EXPECT_TREE}`);
  if (EXPECT_TREE === 'worktree') {
    expect(fCwd.startsWith(WORKTREE), `前端必须是 worktree 代码（cwd=${fCwd}）`).toBe(true);
    expect(fPort, '改后验收不许占用共享 5174').not.toBe(5174);
  } else {
    expect(fCwd.startsWith(WORKTREE), '证伪对照必须跑在主仓 master 前端上').toBe(false);
  }
  const fe = await page.request.get(`${BASE_URL}/`, { failOnStatusCode: false });
  expect(fe.status(), '前端首页应 200').toBe(200);
  await apiLogin(page);
  const r = await page.request.get(`${BASE_URL}/api/cpq/quotations?page=1&size=1`);
  expect(r.status()).toBe(200);
  const j = await r.json();
  const total = Number(j?.data?.totalElements ?? j?.totalElements);
  const cnt = Number(sql<{ c: number }>('SELECT count(*) AS c FROM public.quotation')[0].c);
  console.log(`[identity] api totalElements=${total} ${DB}.quotation=${cnt}`);
  expect(total > 0 && total === cnt, `后端必须连 ${DB}（api=${total} db=${cnt}；并发建单可能瞬时不等，重试一次仍不等则停下报告）`).toBe(true);
}

// ---------------------------------------------------------------- 登录与写守卫
const AUTH_FILE = () => path.join(WORKTREE, 'cpq-frontend', 'e2e', '.auth', `repair260918-sui-${new URL(BASE_URL).port}.json`);
/** 同一前端端口整轮只登录一次（每条用例各登录会触发 429 登录限流）；失效才重登。🚫 不改 user 表。 */
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
  expect(res.status(), `admin 登录应 200（被锁/限流请停下报主线，🚫 不许改 user 表）；原文：${body.slice(0, 300)}`).toBe(200);
  fs.mkdirSync(path.dirname(f), { recursive: true });
  await page.context().storageState({ path: f });
}

export async function gotoApp(page: Page, url: string) {
  await page.goto(url);
  if (/\/login|change-password/.test(page.url())) { await apiLogin(page); await page.goto(url); }
  await page.waitForLoadState('networkidle').catch(() => {});
}

/**
 * 写请求拦截：非 GET 且不是登录 ⇒ abort + 记录。**必须最先注册**（Playwright 后注册的 route 先匹配，
 * 各用例自己的改写 handler 对不处理的请求调 route.fallback() 落到这里）。
 */
export async function blockWrites(page: Page): Promise<string[]> {
  const blocked: string[] = [];
  const allow: RegExp[] = [/\/api\/cpq\/auth\/login$/, /\/api\/cpq\/auth\/refresh/];
  await page.route('**/api/**', async (route) => {
    const req = route.request();
    const m = req.method();
    const u = req.url();
    if (m === 'GET' || m === 'HEAD' || m === 'OPTIONS' || allow.some(r => r.test(u))) return route.continue();
    blocked.push(`${m} ${u}`);
    console.log(`[blockWrites] ABORT ${m} ${u}`);
    return route.abort('blockedbyclient');
  });
  return blocked;
}

// ---------------------------------------------------------------- 响应改写（page.route）
/** 响应可能是裸对象，也可能包在 { data } 里（api.md §0.2 说不包，但不赌）：返回真正的业务体。 */
export function unwrap(json: any): any {
  return json && typeof json === 'object' && !Array.isArray(json) && json.data && typeof json.data === 'object' ? json.data : json;
}
export type RateValue = string | null; // 以十进制字符串书写的期望注入值；null = 空

/**
 * 把业务体里某处 changeRate 置为注入值，并**保留原字段的 JSON 类型**（原为字符串则写字符串，原为数字则写数字字面量，
 * 避免 JSON.stringify 把 0.000000034611 写成 3.4611e-8 这种与后端实际下发不同的形态）。
 * 用法：setter(holder) 里把目标字段赋成 PLACEHOLDER，再调 serializeWithRate。
 */
export const PLACEHOLDER = '__RP0918_RATE__';
export function serializeWithRate(json: any, value: RateValue, originalType: string): string {
  const text = JSON.stringify(json);
  expect(text.split(`"${PLACEHOLDER}"`).length - 1, '注入占位符应恰出现 1 次（0 次 = 没找到银那一格，注入没生效）').toBe(1);
  const lit = value === null ? 'null' : originalType === 'number' ? value : JSON.stringify(value);
  return text.replace(`"${PLACEHOLDER}"`, lit);
}
export async function fulfillText(route: Route, status: number, body: string, headers?: Record<string, string>) {
  await route.fulfill({ status, contentType: 'application/json', body, headers });
}

// ---------------------------------------------------------------- 表格读取（antd / 原生 table 通吃）
export interface RateHit { text: string; color: string; tag: string }
export interface CellProbe {
  found: boolean;
  why: string;
  headers: string[];
  rowTexts: string[];
  cellText: string;
  /** 单元格内「文本恰为一个百分比或 —」的最深元素 */
  rateHits: RateHit[];
  /**
   * 「无色」对照 ①（同行参考格）：同一行里第一个有文字、且不是目标格的单元格的 computed color
   * （= 该行继承下来的默认文字色）。refSource 记下是哪一格。
   */
  refColor: string;
  refSource: string;
  /**
   * 「无色」对照 ②（同格单价）：目标格里「文本恰为一个数」的最深元素的 computed color。
   * 用于元素矩阵 —— 涨跌小字与单价同格，小字的「无色」以同格单价文字为参考（主线 2026-09-18 审用例意见）。
   */
  priceColor: string;
  priceSource: string;
}

/**
 * 在 root（抽屉 / 页面区域）里找：表头匹配 headerRe 的那一列 × 某格文本按分隔符切开后含 rowTokens 之一的那一行，
 * 读出该格里「涨跌」文本与颜色。表头按 colSpan 计列位；数据行只认 tr.ant-table-row（跳过测量行），
 * 没有 antd 行时退回 tbody tr（原生表格）。多张表时取第一张同时命中表头与行的。
 */
export async function probeRateCell(root: Locator, headerRe: RegExp, rowTokens: string[]): Promise<CellProbe> {
  return root.evaluate((el: Element, arg: { hs: string; hf: string; tokens: string[] }) => {
    const headerRe = new RegExp(arg.hs, arg.hf);
    const clean = (s: string | null | undefined) => (s || '').replace(/\s+/g, ' ').trim();
    const rateRe = /^(?:[+-]?<?\d+(?:\.\d+)?%|—)$/;
    const wrappers = Array.from(el.querySelectorAll('.ant-table-wrapper'));
    const candidates: Element[] = wrappers.length ? wrappers : Array.from(el.querySelectorAll('table'));
    const out: any = { found: false, why: `候选表格 ${candidates.length} 张`, headers: [], rowTexts: [], cellText: '', rateHits: [], refColor: '', refSource: '', priceColor: '', priceSource: '' };
    for (const w of candidates) {
      // 列位：遍历所有表头行，找命中 headerRe 的 th，按其前面兄弟的 colSpan 累加求起始列
      let colIdx = -1;
      const headers: string[] = [];
      for (const tr of Array.from(w.querySelectorAll('thead tr'))) {
        let c = 0;
        for (const th of Array.from(tr.children)) {
          if (th.classList.contains('ant-table-cell-scrollbar')) continue;
          const t = clean((th as HTMLElement).innerText);
          headers.push(t);
          if (colIdx < 0 && headerRe.test(t)) colIdx = c;
          c += (th as HTMLTableCellElement).colSpan || 1;
        }
      }
      const antRows = w.querySelectorAll('tbody tr.ant-table-row');
      const rows = Array.from(antRows.length ? antRows : w.querySelectorAll('tbody tr'));
      const rowTexts = rows.map(r => clean((r as HTMLElement).innerText)).slice(0, 30);
      if (colIdx < 0) { out.why += ` | 某表表头无匹配：${JSON.stringify(headers)}`; out.headers = headers; out.rowTexts = rowTexts; continue; }
      const row = rows.find(r => Array.from(r.children).some(td =>
        clean((td as HTMLElement).innerText).split(/[\s·/()（）,，:：]+/).some(tok => arg.tokens.includes(tok))));
      if (!row) { out.why += ` | 表头命中(列${colIdx})但无目标行`; out.headers = headers; out.rowTexts = rowTexts; continue; }
      let c = 0;
      let cell: Element | null = null;
      for (const td of Array.from(row.children)) {
        const span = (td as HTMLTableCellElement).colSpan || 1;
        if (colIdx >= c && colIdx < c + span) { cell = td; break; }
        c += span;
      }
      if (!cell) { out.why += ` | 行里取不到第 ${colIdx} 列`; out.headers = headers; out.rowTexts = rowTexts; continue; }
      const all = [cell, ...Array.from(cell.querySelectorAll('*'))];
      const hits = all.filter(e => rateRe.test((e.textContent || '').trim()));
      const deepest = hits.filter(h => !hits.some(o => o !== h && h.contains(o)));
      // 对照 ①：同行第一个有文字的非目标格
      const tds = Array.from(row.children) as HTMLElement[];
      const refIdx = tds.findIndex(td => td !== cell && clean(td.innerText) !== '');
      const ref = refIdx >= 0 ? tds[refIdx] : undefined;
      // 对照 ②：同格里文本恰为一个数（单价）的最深元素；单价若是格子的直接文本节点，则取格子本身
      const numRe = /^-?[\d,]+(?:\.\d+)?$/;
      const numHits = all.filter(e => numRe.test((e.textContent || '').trim()));
      const numDeepest = numHits.filter(h => !numHits.some(o => o !== h && h.contains(o)));
      let priceEl: Element | undefined = numDeepest[0];
      if (!priceEl && Array.from(cell.childNodes).some(n => n.nodeType === 3 && numRe.test((n.textContent || '').trim()))) priceEl = cell;
      return {
        found: true, why: 'ok', headers, rowTexts,
        cellText: clean((cell as HTMLElement).innerText),
        rateHits: deepest.map(e => ({ text: (e.textContent || '').trim(), color: getComputedStyle(e).color, tag: e.tagName })),
        refColor: ref ? getComputedStyle(ref).color : '',
        refSource: ref ? `同行第 ${refIdx + 1} 格「${clean(ref.innerText).slice(0, 40)}」<${ref.tagName}>` : '',
        priceColor: priceEl ? getComputedStyle(priceEl).color : '',
        priceSource: priceEl ? `同格单价「${(priceEl.textContent || '').trim().slice(0, 40)}」<${priceEl.tagName}>` : '',
      };
    }
    return out;
  }, { hs: headerRe.source, hf: headerRe.flags, tokens: rowTokens });
}

/**
 * 按 AC-1/2/4 的口径断言一格：文本逐字相等 + 颜色。
 * 颜色口径：正 ⇒ 红 #cf1322；负 ⇒ 绿 #389e0d；`0%` 与 `—` ⇒ **无色 = 不设颜色、继承默认文字色**
 * （主线 2026-09-18 审用例裁定：不是「非红非绿」；灰、蓝等任何非继承色都判失败）。
 * 无色判据 = 涨跌文字 computed color 与参考色逐字相等：
 *   ref='row'       同行参考格（版本明细、审核抽屉）
 *   ref='matrixNeutral' 元素矩阵：固定为现网原有中性灰 rgba(0, 0, 0, 0.35)（主线提供），且非红非绿
 */
export function assertRate(p: CellProbe, expectedText: string, color: 'red' | 'green' | 'none', label: string,
  ref: 'row' | 'matrixNeutral' = 'row') {
  console.log(`[${label}] probe=${JSON.stringify(p)}`);
  expect(p.found, `${label}：没定位到「银 × 涨跌」这一格（${p.why}）—— 先怀疑选择器，看 dump`).toBe(true);
  expect(p.rateHits.length, `${label}：格内应恰有 1 个涨跌文本元素（实际 ${JSON.stringify(p.rateHits)}，格文本「${p.cellText}」）`).toBe(1);
  const hit = p.rateHits[0];
  expect(hit.text, `${label}：涨跌显示`).toBe(expectedText);
  if (color === 'red') expect(hit.color, `${label}：涨应为红 #cf1322`).toBe(RED);
  else if (color === 'green') expect(hit.color, `${label}：跌应为绿 #389e0d`).toBe(GREEN);
  else if (ref === 'matrixNeutral') {
    // 元素矩阵：参考色由主线提供，来源是现网原有样式（矩阵中性灰 rgba(0, 0, 0, 0.35)），不是本次改动引入
    console.log(`[${label}] 无色参考=矩阵现网中性灰 ${MATRIX_NEUTRAL}；涨跌文字 color=${hit.color}（同格单价 ${p.priceSource} ${p.priceColor} 仅记录）`);
    expect(hit.color, `${label}：「${expectedText}」不得是红色`).not.toBe(RED);
    expect(hit.color, `${label}：「${expectedText}」不得是绿色`).not.toBe(GREEN);
    expect(hit.color, `${label}：「${expectedText}」应为矩阵现网中性灰 ${MATRIX_NEUTRAL}`).toBe(MATRIX_NEUTRAL);
  } else {
    const refColor = p.refColor;
    const refSource = p.refSource;
    console.log(`[${label}] 无色参考=${refSource} color=${refColor}；涨跌文字 color=${hit.color}`);
    expect(refColor, `${label}：取不到无色参考色（${ref === 'row' ? '同行参考格' : '同格单价文字'}）—— 判据无效，先怀疑选择器`).toBeTruthy();
    // 参考本身若是红/绿，「等于参考」就不再意味着「无色」⇒ 判据无效，硬失败
    expect([RED, GREEN], `${label}：参考色 ${refColor}（${refSource}）本身是红/绿，不能当「无色」参考`).not.toContain(refColor);
    expect(hit.color, `${label}：「${expectedText}」应无色（继承默认文字色 = ${refSource} 的 ${refColor}）`).toBe(refColor);
  }
  return hit;
}

// ---------------------------------------------------------------- 页面入口
export const drawer = (page: Page, has: string | RegExp) =>
  page.locator('.ant-drawer:visible').filter({ hasText: has }).last();

/** 定价管理 → 定价策略 → 选客户 → 「价格调整策略」页签（写法同 tmp-element-matrix-crosspage 已跑通的 openTab）。 */
export async function openAdjustTab(page: Page) {
  await gotoApp(page, '/pricing');
  const search = page.locator('input[placeholder="搜索客户"]').first();
  await expect(search, '定价管理页应有「搜索客户」输入框').toBeVisible({ timeout: 30_000 });
  await search.fill(CUST.name);
  await search.press('Enter');
  await page.locator('.ant-list-item').filter({ hasText: CUST.name }).first().click();
  await page.locator('.ant-tabs-tab').filter({ hasText: '价格调整策略' }).first().click();
  await page.waitForLoadState('networkidle').catch(() => {});
}

/** 版本轨迹：点 V26091802 打开「版本明细」抽屉（AC-1 操作路径）。 */
export async function openVersionDetail(page: Page): Promise<Locator> {
  // 版本轨迹表 = 行里含版本号、且不是元素矩阵（矩阵表头含「元素符号」）的那张表
  const trail = page.locator('.ant-table-wrapper:visible')
    .filter({ has: page.locator('tr.ant-table-row').filter({ hasText: VERSION_NO }) })
    .filter({ hasNotText: '元素符号' }).first();
  await expect(trail, `应找到含 ${VERSION_NO} 的版本轨迹表`).toBeVisible({ timeout: 30_000 });
  const row = trail.locator('tr.ant-table-row').filter({ hasText: VERSION_NO }).first();
  // 阶段二实测（2026-09-18 22:06 页面快照）：版本号那一格是纯文本，行尾「操作」列有「明细」链接 —— 入口是它
  const link = row.locator('a, button').filter({ hasText: /^\s*明\s*细\s*$/ });
  await expect(link, `${VERSION_NO} 那一行应恰有 1 个「明细」入口`).toHaveCount(1);
  await link.click();
  const d = page.locator('.ant-drawer:visible, .ant-modal-wrap:visible').filter({ hasText: VERSION_NO }).last();
  await expect(d, '点「明细」后应打开含版本号的版本明细弹层').toBeVisible({ timeout: 20_000 });
  await expect(d.locator('tr.ant-table-row, tbody tr').filter({ hasText: AG.name }).first(), '版本明细应有银那一行').toBeVisible({ timeout: 30_000 });
  return d;
}

/** 元素矩阵：关键字筛到银（placeholder / 按钮文案来自 tmp-element-matrix-crosspage ④，已跑通）。 */
export async function elementMatrix(page: Page): Promise<Locator> {
  const wrapper = page.locator('.ant-table-wrapper:visible').filter({ hasText: '元素符号' }).first();
  await expect(wrapper, '应有参与调价元素矩阵（表头含「元素符号」）').toBeVisible({ timeout: 30_000 });
  const kw = page.locator('input[placeholder="元素符号 / 名称"]:visible').first();
  if (await kw.count()) {
    await kw.fill(AG.code);
    await page.locator('button:visible').filter({ hasText: /^查\s*询$/ }).first().click();
    await page.waitForLoadState('networkidle').catch(() => {});
  }
  await expect(wrapper.locator('tr.ant-table-row').filter({ hasText: AG.name }).first(), '元素矩阵应出现银那一行').toBeVisible({ timeout: 30_000 });
  return wrapper;
}

/**
 * 选一条 V26091802 名下「待处理 + 预算就绪」的审核：依据单行数最少的优先（旧后端对 1200 行大单打开抽屉会 60s 超时，
 * AC-2② 验的是涨跌显示，不是大单速度 —— 大单速度是 AC-6，归主线亲验）。
 * 0028-2609000056 排在最后：主线亲验要先通过它记 T₀（test.md §6 第 1 步）。
 */
export function pickReview(): { id: string; material_no: string; quotation_number: string; lines: number } {
  const rows = sql<{ id: string; material_no: string; quotation_number: string; lines: number }>(`
    SELECT r.id::text, r.material_no, q.quotation_number,
           (SELECT count(*) FROM public.quotation_line_item li WHERE li.quotation_id = q.id)::int AS lines
      FROM public.material_price_review r
      JOIN public.element_price_version v ON v.id = r.version_id
      LEFT JOIN public.quotation q ON q.id = r.basis_quotation_id
     WHERE v.customer_no = '${CUST.no}' AND v.version_no = '${VERSION_NO}'
       AND r.status = 'PENDING' AND r.budget_status = 'READY'
     ORDER BY (r.material_no = '0028-2609000056'), lines, r.material_no`);
  console.log(`[pickReview] candidates=${JSON.stringify(rows.slice(0, 5))}（共 ${rows.length}）`);
  expect(rows.length, `前置数据：${VERSION_NO} 名下应至少有 1 条待处理且预算就绪的审核（没有 = 版本已被取代或已全部处理，不是产品缺陷）`).toBeGreaterThan(0);
  return rows[0];
}

/**
 * 价格调整审核页 → 打开指定料号的审核抽屉。
 * 列表响应被改写成「只含这一条」（真实接口加 keyword/customerNo 查询后按 reviewId 过滤）——
 * 只为让目标行稳定出现在第 1 页，不改任何字段值；抽屉详情仍是真实接口（AC-4 另行改写银的 changeRate）。
 */
export async function openReviewDrawer(page: Page, review: { id: string; material_no: string }): Promise<Locator> {
  await page.route(/\/api\/cpq\/price-adjust\/reviews(\?|$)/, async (route) => {
    if (route.request().method() !== 'GET') return route.fallback();
    const u = new URL(route.request().url());
    u.searchParams.set('keyword', review.material_no);
    u.searchParams.set('customerNo', CUST.no);
    u.searchParams.set('size', '200'); // keyword 若不被后端识别，也尽量把目标行拉进同一页
    const resp = await route.fetch({ url: u.toString() });
    const json = await resp.json().catch(() => null);
    const body = unwrap(json);
    const before = Array.isArray(body?.content) ? body.content.length : -1;
    if (Array.isArray(body?.content)) {
      body.content = body.content.filter((r: any) => r.reviewId === review.id);
      if (typeof body.totalElements === 'number') body.totalElements = body.content.length;
      if (typeof body.totalPages === 'number') body.totalPages = 1;
    }
    console.log(`[reviews-list] ${u.pathname}${u.search} status=${resp.status()} content ${before} → ${body?.content?.length}`);
    await route.fulfill({ response: resp, json });
  });
  await gotoApp(page, '/pricing/reviews');
  const row = page.locator('tr.ant-table-row').filter({ hasText: review.material_no }).first();
  await expect(row, `审核列表应出现料号 ${review.material_no}`).toBeVisible({ timeout: 30_000 });
  const link = row.locator('a').filter({ hasText: review.material_no }).first();
  const t0 = Date.now();
  await link.click();
  const d = drawer(page, /为什么变/);
  await expect(d, '点料号应打开审核抽屉（含「一、为什么变」）').toBeVisible({ timeout: 20_000 });
  await expect(d.locator('tr.ant-table-row, tbody tr').filter({ hasText: AG.name }).first(), '「为什么变」应有银那一行')
    .toBeVisible({ timeout: 60_000 });
  console.log(`[review-drawer] ${review.material_no} 抽屉银行出现耗时 ${Date.now() - t0} ms`);
  return d;
}

/** 「更新任务」页路由（主线 2026-09-18 答复）。 */
export const JOBS_PATH = '/pricing/jobs';
export async function openJobsPage(page: Page) {
  await gotoApp(page, JOBS_PATH);
  console.log(`[jobs-page] url=${page.url()}`);
}

/**
 * 从批次列表打开「更新执行进度」抽屉。入口已由主线确认 = 列表行里「版本号」那一列的链接。
 * 🚫 不做逐个尝试的兜底：兜底会把「入口找错了」掩盖掉。
 */
export async function openJobDrawer(page: Page, versionNo: string): Promise<Locator> {
  const row = page.locator('tr.ant-table-row').filter({ hasText: versionNo }).first();
  await expect(row, `批次列表应出现 ${versionNo}`).toBeVisible({ timeout: 30_000 });
  const link = row.locator('a').filter({ hasText: new RegExp(`^\\s*${versionNo}\\s*$`) });
  if ((await link.count()) !== 1) await dump(page, 'AC-16-jobs-page');
  await expect(link, `批次行里应恰有 1 个版本号链接「${versionNo}」（入口 = 点版本号）`).toHaveCount(1);
  await link.click();
  const d = drawer(page, /更新执行进度/);
  await expect(d, '点版本号链接后应打开「更新执行进度」抽屉').toBeVisible({ timeout: 15_000 });
  return d;
}

/** 计数行：抽屉里同时含「总数」与「等待」的最小元素；返回其文本与各子片段颜色。 */
export async function readCounts(d: Locator): Promise<{ text: string; parts: Array<{ text: string; color: string; wrapperColor: string }> }> {
  return d.evaluate((root: Element) => {
    const clean = (s: string | null | undefined) => (s || '').replace(/\s+/g, ' ').trim();
    const cands = [root, ...Array.from(root.querySelectorAll('*'))]
      .filter(e => /总数/.test(e.textContent || '') && /等待/.test(e.textContent || ''))
      .filter(e => !e.querySelector('table') && e.tagName !== 'TABLE');
    cands.sort((a, b) => (a.textContent || '').length - (b.textContent || '').length);
    const box = cands[0] as HTMLElement | undefined;
    if (!box) return { text: '', parts: [] };
    // 颜色读「设色的那一层」：子片段里第一个非空文本节点的父元素（主线 2026-09-18 量具修正；外层包装元素读到的是继承色）
    const parts = Array.from(box.children).map(c => {
      const w = document.createTreeWalker(c, NodeFilter.SHOW_TEXT);
      let n: Node | null = w.nextNode();
      while (n && !(n.textContent || '').trim()) n = w.nextNode();
      const host = (n && n.parentElement) || c;
      return { text: clean((c as HTMLElement).innerText), color: getComputedStyle(host).color, wrapperColor: getComputedStyle(c).color };
    });
    return { text: clean(box.innerText), parts };
  });
}
