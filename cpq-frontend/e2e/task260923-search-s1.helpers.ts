/**
 * 共享夹具 · task-260923 报价单内搜索：回车触发 + 生产料号 —— 测试分片 S-1（AC-1 ~ AC-12）
 *
 * 🚫 派生来源：全部断言取自
 *      dev-docs/task-260825-报价单大单量分页与料号查询/task-260923-单内搜索回车触发与生产料号/任务.md ③ AC 原文
 *    + 同目录 原型图/ + dev-docs/main-api.md（造数接口）。
 *    **不曾读过** cpq-frontend/src/** 与 cpq-backend/src/**。
 *    选择器全部来自浏览器实际 DOM（2026-09-23 对 worktree 临时栈 5392 的只读探针）：
 *      分页栏 `[data-testid="task260825-paging-bar"]`（顶部 + 底部各一条）、
 *      搜索框 `input[data-testid="paging-search-input"]`、✕ `.ant-input-clear-icon`、
 *      卡片 `.qt-product-card`、销售料号徽标 `.qt-part-badge`（文本「销售料号: Sxxxx」）、
 *      客户产品编号徽标 `.qt-sku-badge`、客户料号名称 `.qt-cust-part-name`、浮层 `.ant-popover`。
 *
 * 🚦 分片纪律（testing.md §4.5，私有写片）
 *  - 只写本片自造单：样本单 A 名称前缀 `T260923-S1-`（每轮新建、afterAll 经接口删除）；
 *    样本单 B 名称前缀 `T260923-S1B-`（提交后生成核价单，不删，列入残留清单）。
 *  - 🚫 不读、不改他人单据；🚫 不写全局计数断言；🚫 SQL 只许 SELECT（sqlRO 硬拒写语句）。
 *  - 删除走 `DELETE /api/cpq/quotations/{id}`，执行前回读名称必须以 `T260923-S1-` 开头（S1B 不匹配此前缀）。
 */
import { execSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { Page, Locator, expect } from '@playwright/test';

const __filename_ = fileURLToPath(import.meta.url);
const __dirname_ = path.dirname(__filename_);

// ───────────────────────────── 环境 ─────────────────────────────
export const BASE_URL = process.env.PW_BASE_URL || 'http://localhost:5174';
export const BACKEND_URL = process.env.PW_BACKEND_URL || 'http://localhost:8081';
export const WORKTREE_ROOT = path.resolve(__dirname_, '..', '..');
export const TASK_DIR = path.join(
  WORKTREE_ROOT, 'dev-docs', 'task-260825-报价单大单量分页与料号查询', 'task-260923-单内搜索回车触发与生产料号',
);

// ───────────────────────────── AC 原文常量 ─────────────────────────────
export const PREFIX_A = 'T260923-S1-';
export const PREFIX_B = 'T260923-S1B-';
export const CUSTOMER_CODE = 'CUST-0004';
export const QUOTE_TEMPLATE_NAME = '正泰测试模板2'; // AC-9 前置点名
export const COSTING_TEMPLATE_NAME = '核价模板1';
/** 任务.md ③「样本数据」：14 个产品的顺序（S0004~S0007 在第 11~14 位） */
export const ORDER = ['S0001', 'S0002', 'S0003', 'S0008', 'S0009', 'S0010', 'S0011', 'S0012', 'S0013', 'S0014',
  'S0004', 'S0005', 'S0006', 'S0007'];
export const FIRST_PAGE = ORDER.slice(0, 10);
export const PLACEHOLDER = '销售/客户/生产料号，回车搜索';
export const EMPTY_TITLE = '未找到匹配的料号';
export const emptySubtitle = (q: string, total = 14) =>
  `「${q}」在本报价单的 ${total} 个料号中无匹配。请换一个料号片段，或清空查询查看全部。`;
export const TXT_ALL = '共 14 条';
export const txtMatch = (n: number) => `匹配 ${n} 条 / 共 14 条`;
export const TXT_NONE = '未匹配到料号（共 14 条）';
/** AC 写死的期望集合（运行时还会用源数据现算一遍，两者不等 ⇒ 样本漂移，判「未验证」而非缺陷） */
export const SET_30002 = ['S0004', 'S0005', 'S0006', 'S0007'];
export const SET_30003 = ['S0008', 'S0009', 'S0010', 'S0011'];

// ───────────────────────────── 证据（任务目录，每轮独立子目录，不覆盖） ─────────────────────────────
const RUN_TAG = process.env.T260923_S1_RUN
  || `run-adhoc-${new Date().toISOString().replace(/[-:]/g, '').replace(/\..*$/, '')}`;
export const EVIDENCE_DIR = path.join(TASK_DIR, '证据', '测试', 'S-1', RUN_TAG);
fs.mkdirSync(EVIDENCE_DIR, { recursive: true });

function uniquePath(name: string): string {
  let p = path.join(EVIDENCE_DIR, name);
  const ext = path.extname(name); const base = name.slice(0, name.length - ext.length);
  for (let i = 2; fs.existsSync(p); i++) p = path.join(EVIDENCE_DIR, `${base}.${i}${ext}`);
  return p;
}
export function writeEvidence(name: string, content: string) {
  const p = uniquePath(name);
  fs.writeFileSync(p, content, 'utf-8');
  console.log(`[证据] ${p}`);
}
export function appendEvidence(name: string, line: string) {
  fs.appendFileSync(path.join(EVIDENCE_DIR, name), line + '\n', 'utf-8');
  console.log(line);
}
export async function shot(page: Page, name: string) {
  const p = uniquePath(`${name}.png`);
  await page.screenshot({ path: p, fullPage: false });
  console.log(`[证据] ${p}`);
}

// ───────────────────────────── 只读 SQL ─────────────────────────────
export function sqlRO(sql: string): string {
  if (!/^\s*(select|with)\b/i.test(sql) || /\b(insert|update|delete|drop|truncate|alter|create)\b/i.test(sql)) {
    throw new Error(`🚨 本片 SQL 只许 SELECT：${sql}`);
  }
  const cmd = `PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -t -A -F'|' -c "${sql.replace(/"/g, '\\"')}"`;
  return execSync(cmd, { encoding: 'utf-8', shell: '/bin/bash' }).trim();
}
export function sqlRows(sql: string, cols: string[]): Record<string, string>[] {
  const out = sqlRO(sql);
  if (!out) return [];
  return out.split('\n').filter(Boolean).map((l) => {
    const v = l.split('|'); const r: Record<string, string> = {};
    cols.forEach((c, i) => { r[c] = v[i] ?? ''; });
    return r;
  });
}

// ───────────────────────────── 源数据（ds_quote_material / ds_quote_customer_part，现查） ─────────────────────────────
export interface SrcRow { material: string; production: string; custProductNo: string; custPartName: string }
export function loadSource(): Record<string, SrcRow> {
  const inList = ORDER.map((s) => `'${s}'`).join(',');
  const rows = sqlRows(
    `SELECT m.material_no, coalesce(m.production_no,''), coalesce(cp.customer_product_no,''), coalesce(cp.customer_part_name,'')
       FROM ds_quote_material m
       LEFT JOIN LATERAL (SELECT customer_product_no, customer_part_name FROM ds_quote_customer_part c
                           WHERE c.customer_no=m.customer_no AND c.material_no=m.material_no
                           ORDER BY c.created_at, c.customer_product_no LIMIT 1) cp ON true
      WHERE m.customer_no='${CUSTOMER_CODE}' AND m.material_no IN (${inList}) ORDER BY m.material_no`,
    ['material', 'production', 'custProductNo', 'custPartName'],
  );
  const map: Record<string, SrcRow> = {};
  for (const r of rows) map[r.material] = r as unknown as SrcRow;
  // testing.md §5.7⑥：先断言条数，再断言每条非空
  expect(rows.length, `源数据条数应为 14（ds_quote_material, ${CUSTOMER_CODE}），实际 ${rows.length} ⇒ 样本漂移，判【未验证】`).toBe(14);
  for (const s of ORDER) {
    expect(map[s]?.production, `源数据 ${s} 的 production_no 应非空 ⇒ 否则后续生产料号断言会空跑`).toBeTruthy();
  }
  return map;
}
/** 按 AC 口径（四字段、大小写不敏感子串、去首尾空格）从源数据现算期望命中集合，保持 ORDER 顺序。 */
export function expectedHits(src: Record<string, SrcRow>, q: string): string[] {
  const t = q.trim().toLowerCase();
  return ORDER.filter((s) => {
    const r = src[s];
    return [r.material, r.custProductNo, r.custPartName, r.production].some((f) => f && f.toLowerCase().includes(t));
  });
}

// ───────────────────────────── 运行前置：互斥采样 + 验明正身 ─────────────────────────────
function ancestors(): Set<number> {
  const set = new Set<number>([process.pid]);
  let pid = process.ppid;
  for (let i = 0; i < 20 && pid > 1; i++) {
    set.add(pid);
    try {
      const stat = fs.readFileSync(`/proc/${pid}/stat`, 'utf-8');
      pid = Number(stat.slice(stat.lastIndexOf(')') + 2).split(' ')[1]);
    } catch { break; }
  }
  return set;
}
/** 采样其他 `playwright test` 进程（排除本进程祖先链 = 本次运行自己）。 */
export function otherPlaywright(): string[] {
  let out = '';
  try { out = execSync(`pgrep -af "node.*[p]laywright test"`, { encoding: 'utf-8' }); } catch { return []; }
  const mine = ancestors();
  return out.split('\n').filter(Boolean).filter((l) => !mine.has(Number(l.split(' ')[0])));
}
/** 验明正身：前端端口的监听进程 cwd 在本 worktree 下，且服务的是改动后的 PagingBar。 */
export async function assertStackIdentity() {
  const stamp = new Date().toISOString();
  const port = new URL(BASE_URL).port;
  let cwd = '(未取到)';
  try {
    const ss = execSync(`ss -ltnpH 'sport = :${port}'`, { encoding: 'utf-8' });
    const pid = ss.match(/pid=(\d+)/)?.[1];
    if (pid) cwd = fs.readlinkSync(`/proc/${pid}/cwd`);
  } catch { /* 下面断言给出可读信息 */ }
  let hit = -1;
  try {
    const src = await (await fetch(`${BASE_URL}/src/pages/quotation/PagingBar.tsx`)).text();
    hit = src.split(PLACEHOLDER).length - 1;
  } catch { /* 同上 */ }
  const line = `${stamp} BASE_URL=${BASE_URL} 端口 ${port} 监听进程 cwd=${cwd} PagingBar.tsx 含新提示文字次数=${hit}`;
  appendEvidence('00-环境验明正身.txt', line);
  if (process.env.T260923_S1_FOREIGN_STACK === '1') { appendEvidence('00-环境验明正身.txt', '⚠️ FOREIGN_STACK=1：本轮为证伪/对照轮，结果不作验收证据'); return; }
  expect(cwd.startsWith(WORKTREE_ROOT), `前端 ${port} 的进程 cwd 不在本 worktree（${cwd}）⇒ 测的不是本分支代码，判【未验证】`).toBe(true);
  expect(hit, `前端 ${port} 服务的 PagingBar.tsx 不含新提示文字 ⇒ 旧代码或实现未就绪，判【未验证】`).toBeGreaterThanOrEqual(1);
}

// ───────────────────────────── 接口造数 ─────────────────────────────
export async function loginApi(): Promise<string> {
  for (let i = 0; i < 4; i++) {
    const res = await fetch(`${BACKEND_URL}/api/cpq/auth/login`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'admin', password: 'Admin@2026' }),
    });
    if (res.ok) {
      const jar = ((res.headers as any).getSetCookie?.() ?? []).map((c: string) => c.split(';')[0]);
      expect(jar.length, '登录 200 但无 Set-Cookie ⇒ 量具问题').toBeGreaterThan(0);
      return jar.join('; ');
    }
    if (res.status === 429) { await new Promise((r) => setTimeout(r, 3000 * (i + 1))); continue; }
    throw new Error(`接口登录失败 ${res.status}`);
  }
  throw new Error('接口登录反复 429 ⇒ 基础设施问题');
}
export async function api(cookie: string, method: string, p: string, body?: unknown) {
  const res = await fetch(`${BACKEND_URL}${p}`, {
    method, headers: { Cookie: cookie, 'Content-Type': 'application/json; charset=utf-8' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let json: any = null; try { json = JSON.parse(text); } catch { /* keep text */ }
  return { status: res.status, json, text };
}

export interface Templates { quoteId: string; costingId: string }
export function resolveTemplates(): Templates {
  const cat = sqlRO(`SELECT product_category_id::text FROM customer WHERE code='${CUSTOMER_CODE}'`);
  expect(cat, `${CUSTOMER_CODE} 无产品分类 ⇒ 夹具前置不满足`).toMatch(/^[0-9a-f-]{36}$/);
  const q = sqlRows(`SELECT id::text, version, (excel_view_config IS NOT NULL AND excel_view_config::text NOT IN ('null','{}','[]'))::text
      FROM template WHERE name='${QUOTE_TEMPLATE_NAME}' AND template_kind='QUOTATION' AND status='PUBLISHED' AND category_id='${cat}'
      ORDER BY published_at DESC NULLS LAST LIMIT 1`, ['id', 'version', 'hasExcel'])[0];
  expect(q?.id, `找不到已发布的「${QUOTE_TEMPLATE_NAME}」⇒ 夹具前置不满足`).toMatch(/^[0-9a-f-]{36}$/);
  expect(q.hasExcel, `AC-9 前置：「${QUOTE_TEMPLATE_NAME}」${q.version} 的 excel_view_config 应非空`).toBe('true');
  const c = sqlRows(`SELECT id::text, version FROM template WHERE name='${COSTING_TEMPLATE_NAME}' AND template_kind='COSTING'
      AND status='PUBLISHED' AND category_id='${cat}' ORDER BY published_at DESC NULLS LAST LIMIT 1`, ['id', 'version'])[0];
  expect(c?.id, `找不到已发布的「${COSTING_TEMPLATE_NAME}」⇒ 夹具前置不满足`).toMatch(/^[0-9a-f-]{36}$/);
  appendEvidence('01-造数.txt', `${new Date().toISOString()} 报价模板=${QUOTE_TEMPLATE_NAME} ${q.version} (${q.id}) 核价模板=${COSTING_TEMPLATE_NAME} ${c.version} (${c.id})`);
  return { quoteId: q.id, costingId: c.id };
}

/** 建一张 14 个产品的草稿（POST 建单 + PUT /draft 写行项，顺序 = ORDER），回读校验后返回 id / 单号。 */
export async function createSample(cookie: string, prefix: string, src: Record<string, SrcRow>, tpl: Templates) {
  const cid = sqlRO(`SELECT id::text FROM customer WHERE code='${CUSTOMER_CODE}'`);
  const name = `${prefix}${new Date().toISOString().replace(/[-:]/g, '').slice(0, 15)}`;
  const c = await api(cookie, 'POST', '/api/cpq/quotations', {
    customerId: cid, name, quoteType: 'STANDARD', customerTemplateId: tpl.quoteId, costingTemplateId: tpl.costingId,
  });
  expect(c.status, `建单失败 ${c.status} ${c.text.slice(0, 300)}`).toBe(200);
  const q = c.json.data;
  const names = sqlRows(`SELECT material_no, coalesce(material_name,'') FROM ds_quote_material WHERE customer_no='${CUSTOMER_CODE}'
      AND material_no IN (${ORDER.map((s) => `'${s}'`).join(',')})`, ['m', 'n']);
  const nameOf = Object.fromEntries(names.map((r) => [r.m, r.n]));
  const d = await api(cookie, 'PUT', `/api/cpq/quotations/${q.id}/draft`, {
    name: q.name, customerTemplateId: q.customerTemplateId, costingCardTemplateId: q.costingCardTemplateId,
    lineItems: ORDER.map((s, i) => ({ productPartNo: s, productName: nameOf[s] || s, sortOrder: i })),
  });
  expect(d.status, `保存草稿失败 ${d.status} ${d.text.slice(0, 300)}`).toBe(200);
  appendEvidence('01-造数.txt', `${new Date().toISOString()} 建单 ${q.quotationNumber} (${q.id}) name=${name}`);
  await verifySampleByApi(cookie, q.id, src);
  return { id: q.id as string, no: q.quotationNumber as string, name };
}
/** 回读 GET 报价单：14 行、顺序 = ORDER、每行 hfPartInfo.partNo = 源 production_no（非空）。 */
export async function verifySampleByApi(cookie: string, id: string, src: Record<string, SrcRow>) {
  const g = await api(cookie, 'GET', `/api/cpq/quotations/${id}`);
  expect(g.status).toBe(200);
  const lis = [...(g.json.data.lineItems || [])].sort((a: any, b: any) => a.sortOrder - b.sortOrder);
  const got = lis.map((li: any) => `${li.productPartNo}:${li.hfPartInfo?.partNo ?? ''}:${li.customerProductNo ?? ''}`);
  appendEvidence('01-造数.txt', `  回读 ${id} 行项=${JSON.stringify(got)}`);
  expect(lis.length, '样本单产品数应为 14').toBe(14);
  expect(lis.map((li: any) => li.productPartNo), '样本单顺序应与任务.md ③ 一致').toEqual(ORDER);
  for (const li of lis) {
    expect(li.hfPartInfo?.partNo, `${li.productPartNo} 的生产料号应 = 源 ${src[li.productPartNo].production}`)
      .toBe(src[li.productPartNo].production);
  }
}
export async function deleteSampleA(cookie: string, id: string) {
  const g = await api(cookie, 'GET', `/api/cpq/quotations/${id}`);
  if (g.status === 404) return;
  const name: string = g.json?.data?.name ?? '';
  if (!name.startsWith(PREFIX_A)) throw new Error(`🚨 拒删：${id} 名称「${name}」不以 ${PREFIX_A} 开头`);
  const r = await api(cookie, 'DELETE', `/api/cpq/quotations/${id}`);
  const after = await api(cookie, 'GET', `/api/cpq/quotations/${id}`);
  appendEvidence('01-造数.txt', `${new Date().toISOString()} 删除样本单 A ${id}（${name}）DELETE=${r.status} 回读=${after.status}`);
  expect(after.status, `样本单 A ${id} 删除后回读应 404`).toBe(404);
}
/** 清理上轮崩溃遗留的 A（只认 `T260923-S1-` 前缀且 DRAFT；S1B 前缀天然不匹配）。 */
export async function cleanupStaleA(cookie: string) {
  const rows = sqlRows(`SELECT id::text, name FROM quotation WHERE name LIKE '${PREFIX_A}%' AND status='DRAFT'`, ['id', 'name']);
  for (const r of rows) await deleteSampleA(cookie, r.id);
}

// ───────────────────────────── UI ─────────────────────────────
export function collectPageErrors(page: Page): string[] {
  const errs: string[] = [];
  page.on('pageerror', (e) => errs.push(String(e)));
  return errs;
}
async function waitStep2Cards(page: Page) {
  await expect(page.locator('[data-testid="task260825-paging-bar"]').first(), '分页栏应出现（≥10 个产品才显示）')
    .toBeVisible({ timeout: 120_000 });
  await expect(page.locator('.qt-product-card').first()).toBeVisible({ timeout: 120_000 });
  await page.waitForTimeout(1500);
}
export async function openEdit(page: Page, id: string) {
  await page.goto(`/quotations/${id}/edit`);
  await gotoStep2(page);
}
/** 编辑页从 Step1 进 Step2（点「下一步」；antd 两字按钮带空格）。刷新后也走这条。 */
export async function gotoStep2(page: Page) {
  await page.waitForSelector('.ant-steps-item', { timeout: 120_000 });
  const next = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  await expect(next, 'Step1「下一步」不可见 ⇒ 入口问题，判【未验证】').toBeVisible({ timeout: 60_000 });
  await expect(next, 'Step1「下一步」不可点 ⇒ 夹具问题，判【未验证】').toBeEnabled({ timeout: 60_000 });
  await next.click();
  await waitStep2Cards(page);
}
export async function openDetail(page: Page, id: string) {
  await page.goto(`/quotations/${id}`);
  await waitStep2Cards(page);
}
export async function openCostingReview(page: Page, coid: string) {
  await page.goto(`/costing-orders/${coid}/review`);
  await waitStep2Cards(page);
}
export const bar = (page: Page) => page.locator('[data-testid="task260825-paging-bar"]').first();
export const searchInput = (page: Page) => bar(page).locator('input[data-testid="paging-search-input"]');
export const allSearchInputs = (page: Page) => page.locator('[data-testid="task260825-paging-bar"] input[data-testid="paging-search-input"]');
export async function countText(page: Page): Promise<string> {
  const el = bar(page).locator(':scope > span:not(.ant-input-affix-wrapper)').first();
  if (await el.count() === 0) return '';
  return (await el.innerText()).replace(/\s+/g, ' ').trim();
}
export async function paginationVisible(page: Page): Promise<boolean> {
  return page.evaluate(() => Array.from(document.querySelectorAll('[data-testid="task260825-paging-bar"] .ant-pagination'))
    .some((e) => (e as HTMLElement).offsetParent !== null));
}
export async function hasPage2(page: Page): Promise<boolean> {
  return (await bar(page).locator('.ant-pagination-item-2').count()) > 0;
}
/** 当前可见卡片的销售料号（按 DOM 顺序）。 */
export async function visibleSalesNos(page: Page): Promise<string[]> {
  return page.evaluate(() => Array.from(document.querySelectorAll('.qt-product-card'))
    .filter((c) => (c as HTMLElement).offsetParent !== null)
    .map((c) => ((c.querySelector('.qt-part-badge') as HTMLElement)?.innerText || '').replace(/^\s*销售料号\s*[:：]\s*/, '').trim()));
}
export const sorted = (a: string[]) => [...a].sort();
export async function cardBySales(page: Page, s: string): Promise<Locator> {
  const cards = page.locator('.qt-product-card:visible').filter({
    has: page.locator('.qt-part-badge').filter({ hasText: new RegExp(`销售料号\\s*[:：]\\s*${s}\\s*$`) }),
  });
  await expect(cards, `销售料号 ${s} 应唯一对应一张可见卡片`).toHaveCount(1, { timeout: 10_000 });
  return cards.first();
}
/**
 * 「黄底高亮片段」量具：scope 内背景色为黄色（R≥200, G≥180, B≤170, α>0）或 <mark> 的最外层元素，返回其文本。
 * 🚨 量具本身须有阳性对照（T-3 末尾 / T-4 ①②），否则「高亮数 = 0」可能只是量具抓不到。
 */
export async function yellowFragments(scope: Locator): Promise<string[]> {
  return scope.evaluate((root) => {
    const isYellow = (el: Element) => {
      if (el.tagName === 'MARK') return true;
      const m = getComputedStyle(el).backgroundColor.match(/rgba?\(([^)]+)\)/);
      if (!m) return false;
      const [r, g, b, a] = m[1].split(',').map((x) => parseFloat(x));
      return (a === undefined || a > 0) && r >= 200 && g >= 180 && b <= 170;
    };
    const out: string[] = [];
    root.querySelectorAll('*').forEach((el) => {
      if (!isYellow(el)) return;
      let p = el.parentElement; let nested = false;
      while (p && p !== root) { if (isYellow(p)) { nested = true; break; } p = p.parentElement; }
      if (!nested) out.push((el as HTMLElement).innerText);
    });
    return out;
  });
}
/** 在 ms 毫秒内轮询 fn() 直至等于期望；返回首次满足的耗时（ms），超时抛出。 */
export async function within<T>(ms: number, fn: () => Promise<T>, expected: T, what: string): Promise<number> {
  const t0 = Date.now(); let last: T | undefined;
  while (Date.now() - t0 <= ms) {
    last = await fn();
    if (JSON.stringify(last) === JSON.stringify(expected)) return Date.now() - t0;
    await new Promise((r) => setTimeout(r, 50));
  }
  throw new Error(`${what}：${ms}ms 内未达到期望 ${JSON.stringify(expected)}，最后一次 ${JSON.stringify(last)}`);
}
/** 浮层「料号：」一行（label / value 可能分两行渲染）。 */
export async function popoverPartNo(page: Page, card: Locator): Promise<{ partNo: string; raw: string }> {
  await page.keyboard.press('Escape').catch(() => {});
  await card.locator('.qt-part-badge').first().click();
  const pop = page.locator('.ant-popover:visible').last();
  await expect(pop, '点销售料号徽标后应出现浮层').toBeVisible({ timeout: 15_000 });
  await page.waitForTimeout(1500);
  const raw = await pop.innerText();
  const lines = raw.split('\n').map((s) => s.trim()).filter(Boolean);
  let partNo = '';
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(/^料号\s*[:：]\s*(.*)$/);
    if (m) { partNo = m[1].trim() || (lines[i + 1] ?? '').trim(); break; }
  }
  await page.keyboard.press('Escape').catch(() => {});
  return { partNo, raw };
}
/** 以输入框实际字体测提示文字宽度，与输入框内容区宽度比较。extra 给量具阳性对照用。 */
export async function placeholderFit(input: Locator, extra = '') {
  return input.evaluate((el, extraText) => {
    const inp = el as HTMLInputElement;
    const cs = getComputedStyle(inp);
    let ps: CSSStyleDeclaration | null = null;
    try { ps = getComputedStyle(inp, '::placeholder'); } catch { ps = null; }
    const font = (ps && ps.font) || cs.font;
    const ctx = document.createElement('canvas').getContext('2d')!;
    ctx.font = font;
    const text = inp.placeholder + extraText;
    const textW = ctx.measureText(text).width;
    const contentW = inp.clientWidth - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
    return { text, font, textW, contentW, fits: textW <= contentW };
  }, extra);
}
