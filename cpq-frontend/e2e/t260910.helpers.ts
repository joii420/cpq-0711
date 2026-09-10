/**
 * task-260910「报价卡片客户料号与生产料号切 ds_* 新体系」共享 helpers
 *
 * 🚫 本文件与两个 spec 的全部断言，均从 `任务.md §③ 验收标准` AC 原文 +
 *    `原型图/报价单产品卡片.html` + `dev-docs/main-api.md` 契约派生。
 *    **不曾读过** `cpq-backend/src/main/java/**` 与 `cpq-frontend/src/**`（派工 prompt 段 c）。
 *    选择器词汇来自**运行时 DOM 探针** + 既有 e2e 代码 + 原型图，均在允许范围内。
 *
 * ── 写入面登记（testing.md §4.3）────────────────────────────────────────
 *   S-只读片：零写库。
 *   S-造数片：只 INSERT 带 `T260910-` 前缀可识别的行 ——
 *     ds_quote_material / ds_quote_customer_part / ds_cost_basic_material。
 *   🚫 零 S-全局：不动用户启停用 / 角色权限 / 模板发布态 / 系统开关 / 公共基础数据。
 *   🚫 不动 material_customer_map（在途 task-260909-V6老表退役 AC-10 断言它行数不变）。
 */
import { expect, Page } from '@playwright/test';
import { execSync } from 'child_process';
import * as fs from 'fs';
import * as nodePath from 'path';
import { fileURLToPath } from 'url';

const HERE = nodePath.dirname(fileURLToPath(import.meta.url)); // AP-43: ESM 无 __dirname

export const BASE_URL = process.env.PW_BASE_URL || 'http://localhost:5211';
export const BACKEND_URL = process.env.PW_BACKEND_URL || 'http://localhost:8211';
export const TAG = 'T260910-';

export const DB = {
  host: process.env.PW_DB_HOST || '10.177.152.12',
  port: process.env.PW_DB_PORT || '5432',
  user: process.env.PW_DB_USER || 'postgres',
  db: process.env.PW_DB_NAME || 'cpq_db_0724',
  password: process.env.PW_DB_PASSWORD || 'joii5231',
};

export const EVIDENCE_DIR = nodePath.resolve(
  HERE, '..', '..', 'dev-docs', 'task-260910-报价卡片客户料号与生产料号切新表', '证据',
);
function ensureDir() { fs.mkdirSync(EVIDENCE_DIR, { recursive: true }); }
export function writeEvidence(file: string, text: string) {
  ensureDir(); const p = nodePath.join(EVIDENCE_DIR, file);
  fs.writeFileSync(p, text, 'utf-8'); console.log(`[evidence] → ${p}`); return p;
}
export function appendEvidence(file: string, text: string) {
  ensureDir(); fs.appendFileSync(nodePath.join(EVIDENCE_DIR, file), text, 'utf-8');
}
export async function shot(page: Page, name: string) {
  ensureDir(); const p = nodePath.join(EVIDENCE_DIR, `${name}.png`);
  await page.screenshot({ path: p, fullPage: true }).catch(() => {});
  console.log(`[shot] → ${p}`); return p;
}

// ── SQL：只读默认；写只允许「本片自造 + 前缀可识别」的 INSERT ───────────────
const RED = /\b(drop|truncate|alter|create\s+(table|database|schema)|grant|revoke)\b/i;
function shellQuote(s: string) { return `'` + s.split(`'`).join(`'\\''`) + `'`; }
function runPsql(sql: string, flags = "-X -A -F'|'") {
  return execSync(
    `PGPASSWORD=${DB.password} psql -h ${DB.host} -p ${DB.port} -U ${DB.user} -d ${DB.db} ${flags} -c ${shellQuote(sql)}`,
    { shell: '/bin/bash', encoding: 'utf-8' },
  ).trim();
}
export function sqlRO(sql: string): string {
  const t = sql.trim().replace(/;$/, '');
  if (!/^\s*(select|with|explain)\b/i.test(t)) {
    throw new Error(`🚨 拒绝非只读 SQL：${t.slice(0, 160)}（写操作走 sqlOwnedInsert）`);
  }
  return runPsql(t);
}
export function sqlRows(sql: string): Record<string, string>[] {
  const out = sqlRO(sql).split('\n').filter((l) => l.length > 0 && !/^\(\d+ rows?\)$/.test(l.trim()));
  if (out.length === 0) return [];
  const headers = out[0].split('|');
  return out.slice(1).map((line) => {
    const cells = line.split('|'); const o: Record<string, string> = {};
    headers.forEach((h, i) => (o[h] = cells[i] ?? '')); return o;
  });
}
export function sqlScalar(sql: string): string {
  return runPsql(sql.trim().replace(/;$/, ''), '-X -A -t').split('\n')[0] ?? '';
}
/** 只允许 INSERT，且语句里必须出现本片前缀（保证造出来的行认得出是谁的）。 */
export function sqlOwnedInsert(sql: string, why: string): string {
  const t = sql.trim().replace(/;$/, '');
  if (RED.test(t) || /\b(delete|update)\b/i.test(t)) {
    throw new Error(`🚨 停下来报主线：本片无批准权执行该语句（CLAUDE.md §3.2）：${t.slice(0, 200)}`);
  }
  if (!/^\s*insert\b/i.test(t)) throw new Error(`sqlOwnedInsert 只接受 INSERT：${t.slice(0, 160)}`);
  if (!t.includes(TAG)) throw new Error(`🚨 造数必须带前缀 ${TAG}：${t.slice(0, 200)}`);
  const out = runPsql(t, '-X -A -t');
  console.log(`[owned-insert] ${why} → ${out}`);
  appendEvidence('90-写入面台账.txt', `${new Date().toISOString()} ${why} → ${out}\n  SQL: ${t}\n`);
  return out;
}

// ── 环境正身（防「测了主工作区旧代码」「打在别人的库上」）───────────────────
export function recordIdentity(): string {
  const port = (BACKEND_URL.match(/:(\d+)/) || [])[1] || '8211';
  let pid = '', cwd = '', started = '';
  try {
    const ss = execSync(`ss -ltnp 2>/dev/null | /usr/bin/grep -a ':${port} ' || true`, { shell: '/bin/bash', encoding: 'utf-8' });
    pid = (ss.match(/pid=(\d+)/) || [])[1] || '';
    if (pid) {
      cwd = execSync(`readlink -f /proc/${pid}/cwd 2>/dev/null || true`, { shell: '/bin/bash', encoding: 'utf-8' }).trim();
      started = execSync(`ps -o lstart= -p ${pid} 2>/dev/null || true`, { shell: '/bin/bash', encoding: 'utf-8' }).trim();
    }
  } catch { /* 采样失败不阻断，空值一眼看得出 */ }
  const line = `[identity ${new Date().toISOString()}] BASE=${BASE_URL} BACKEND=${BACKEND_URL} pid=${pid || '?'} cwd=${cwd || '?'} started="${started || '?'}"`;
  console.log(line); appendEvidence('00-环境正身.txt', line + '\n');
  return line;
}
export async function assertWorktreeAndDb(): Promise<void> {
  const line = recordIdentity();
  const cwd = (line.match(/cwd=(\S+)/) || [])[1] || '';
  expect(cwd, `🚨 被测后端的 cwd 不在 worktree 内（${cwd}）⇒ 测的可能是主工作区旧代码，这是**环境问题**不是产品缺陷`)
    .toContain('.claude/worktrees/task-260910-card-partno');
  const cookie = await loginApi();
  const res = await fetch(`${BACKEND_URL}/api/cpq/quotations?page=1&size=1`, { headers: { Cookie: cookie } });
  expect(res.status, '取 quotations 分页应 200').toBe(200);
  const body: any = await res.json();
  const api = Number(body?.data?.totalElements);
  const db = Number(sqlScalar('SELECT count(*) FROM quotation'));
  // ⚠️ 不是全局计数断言，是一致性不变式：别的会话造数会让两边**同时**变
  expect(api, `被测后端连的库与 ${DB.db} 对不上（api=${api} db=${db}）⇒ **环境问题**`).toBe(db);
  appendEvidence('00-环境正身.txt', `[db-identity] api=${api} db=${db}\n`);
}

// ── 鉴权：Cookie 会话（🚫 不是 Bearer） ───────────────────────────────────
export async function loginApi(username = 'admin', password = 'Admin@2026'): Promise<string> {
  for (let i = 0; i < 4; i++) {
    const res = await fetch(`${BACKEND_URL}/api/cpq/auth/login`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }), redirect: 'manual',
    });
    if (res.ok) {
      const raw: string[] = (res.headers as any).getSetCookie?.() ?? [res.headers.get('set-cookie') || ''];
      const jar = raw.filter(Boolean).map((c) => c.split(';')[0].trim());
      if (!jar.length) throw new Error('登录 200 但无 Set-Cookie ⇒ 鉴权契约变了？先判量具再判缺陷');
      return jar.join('; ');
    }
    if (res.status === 429) { await new Promise((r) => setTimeout(r, 3000 * (i + 1))); continue; }
    throw new Error(`登录失败 ${res.status} ${(await res.text()).slice(0, 200)}（先判限流/口令，🚫 不默认产品缺陷）`);
  }
  throw new Error('登录反复 429（限流）⇒ 测试基础设施问题');
}
export async function apiGet(cookie: string, path: string) {
  const res = await fetch(`${BACKEND_URL}${path}`, { headers: { Cookie: cookie } });
  const text = await res.text();
  let json: any = null; try { json = JSON.parse(text); } catch { /* 非 JSON 保留 text */ }
  return { status: res.status, text, json };
}

// ── UI ────────────────────────────────────────────────────────────────
export async function uiLogin(page: Page) {
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForTimeout(5000);
  expect(page.url(), '登录后应离开 /login').not.toContain('/login');
}
export function quotationIdOf(number: string): string {
  const id = sqlScalar(`SELECT id::text FROM quotation WHERE quotation_number='${number}'`);
  expect(id, `前置未满足：库里找不到报价单 ${number} ⇒ **夹具缺失**（共享库可能被别的会话清过），本条判【未验证】`)
    .toMatch(/^[0-9a-f-]{36}$/);
  return id;
}
/** 打开编辑页并进到 Step2「添加产品」。⚠️ 点 step 标题不生效，必须点「下一步」按钮。 */
export async function openStep2(page: Page, quotationNumber: string): Promise<string> {
  const qid = quotationIdOf(quotationNumber);
  await page.goto(`/quotations/${qid}/edit`);
  // 🩹 harness 修复（首轮实证）：原来 goto 后只 `waitForTimeout(9000)` 就找「下一步」，
  //    并发跑测试时首屏偶尔更慢 ⇒ 同一条用例第一轮过、第二轮倒在「找不到下一步」。
  //    那种失败**长得和产品缺陷一模一样**，实际是等待策略不稳（testing.md §4）。
  //    改成条件式等待：先等 Steps 渲染出来，再等按钮可点。
  await page.waitForSelector('.ant-steps-item', { timeout: 90_000 })
    .catch(() => { throw new Error(`打开 ${quotationNumber} 编辑页 90s 内没渲染出 Steps ⇒ **入口/环境问题**，判【未验证】`); });
  const next = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  await expect(next, `Step1 找不到「下一步」按钮 ⇒ **入口问题**，判【未验证】`).toBeVisible({ timeout: 60_000 });
  await expect(next, `Step1「下一步」按钮不可点（常见于夹具单缺产品分类/模板）⇒ **夹具问题**，判【未验证】`).toBeEnabled({ timeout: 30_000 });
  await next.click();
  // 等真正进到 Step2 且卡片渲染完（🚫 不用固定 sleep 赌）
  await page.waitForFunction(() => {
    const a = (document.querySelector('.ant-steps-item-active') as HTMLElement)?.innerText || '';
    return a.includes('添加产品') && document.querySelectorAll('.qt-product-card').length > 0;
  }, undefined, { timeout: 90_000 }).catch(() => { /* 下面用显式断言给出可读信息 */ });
  await page.waitForTimeout(4000); // 让卡片内异步取数落定
  const active = await page.evaluate(() => (document.querySelector('.ant-steps-item-active') as HTMLElement)?.innerText?.replace(/\s+/g, ' ').trim());
  expect(active, `没进到 Step2（当前=${active}）⇒ **入口问题**，判【未验证】`).toContain('添加产品');
  return qid;
}

export interface CardHeader {
  leftChildCount: number;
  leftTexts: string[];
  rightTexts: string[];
  inlineStyle: string | null;
  borderTopColor: string;
  hasPartInfoBtn: boolean;
  templateBadgeTexts: string[];
  domOrderOk: boolean; // 左块在右块之前
}
export async function readCards(page: Page): Promise<CardHeader[]> {
  return page.evaluate(() => Array.from(document.querySelectorAll('.qt-product-card')).map((c) => {
    const hdr = c.querySelector('.qt-card-header') as HTMLElement | null;
    const L = hdr?.querySelector('.qt-card-header-left') as HTMLElement | null;
    const R = hdr?.querySelector('.qt-card-header-right') as HTMLElement | null;
    const txt = (el: Element | null) => el ? Array.from(el.children).map((x) => (x as HTMLElement).innerText.replace(/\s+/g, ' ').trim()).filter(Boolean) : [];
    let domOrderOk = false;
    if (L && R) domOrderOk = !!(L.compareDocumentPosition(R) & Node.DOCUMENT_POSITION_FOLLOWING);
    return {
      leftChildCount: L ? L.childElementCount : -1,
      leftTexts: txt(L),
      rightTexts: txt(R),
      inlineStyle: (c as HTMLElement).getAttribute('style'),
      borderTopColor: getComputedStyle(c as HTMLElement).borderTopColor,
      hasPartInfoBtn: !!hdr && hdr.innerText.includes('料号信息'),
      templateBadgeTexts: Array.from(c.querySelectorAll('.qt-template-badge')).map((e) => (e as HTMLElement).innerText.replace(/\s+/g, ' ').trim()),
      domOrderOk,
    };
  }));
}

/** 点第 idx 张卡片的销售料号徽标，返回浮层纯文本行（已 trim、已去空行）。 */
export async function openPopover(page: Page, idx: number): Promise<{ title: string; lines: string[]; raw: string }> {
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(400);
  const badge = page.locator('.qt-product-card').nth(idx).locator('.qt-part-badge').first();
  await expect(badge, `第 ${idx} 张卡片没有销售料号徽标 ⇒ **入口问题**`).toBeVisible({ timeout: 15_000 });
  await badge.click();
  await page.waitForTimeout(2500);
  const raw = await page.evaluate(() => Array.from(document.querySelectorAll('.ant-popover'))
    .filter((e) => (e as HTMLElement).offsetParent !== null)
    .map((e) => (e as HTMLElement).innerText).join('\n---\n'));
  const lines = raw.split('\n').map((s) => s.trim()).filter(Boolean);
  return { title: lines[0] ?? '', lines, raw };
}

/** 把浮层纯文本解析成 label→value。兼容「label：value 同一行」与「label / value 分两行」两种渲染。 */
export function parsePopoverRows(lines: string[]): Record<string, string> {
  const LABELS = ['料号', '名称', '规格', '尺寸', '旧料号', '生产状态'];
  const out: Record<string, string> = {};
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(/^(料号|名称|规格|尺寸|旧料号|生产状态)\s*[：:]\s*(.*)$/);
    // 🩹 harness 修复：本项目浮层把 label 与 value 渲染成两个 <span>，innerText 得到
    //    「料号：」「300001」**两行**。原实现同一行正则匹配成功但 value 为空字符串，
    //    五行全被解析成 ''，报出来长得像「产品没渲染值」—— 实际是**量具坏了**（testing.md §5.5）。
    if (m) { out[m[1]] = m[2].trim() !== '' ? m[2].trim() : (lines[i + 1] ?? '').trim(); continue; }
    const bare = lines[i].replace(/[：:]\s*$/, '').trim();
    if (LABELS.includes(bare) && out[bare] === undefined) out[bare] = (lines[i + 1] ?? '').trim();
  }
  return out;
}

/** 断言浮层五行**逐字**等于期望，并检查行序与「生产状态」已删除。 */
export function expectPopoverFive(
  pop: { title: string; lines: string[]; raw: string },
  expected: { 料号: string; 名称: string; 规格: string; 尺寸: string; 旧料号: string },
  why: string,
) {
  expect(pop.title, `${why}：浮层标题应为「生产料号」，实际「${pop.title}」\n原文=${pop.raw}`).toBe('生产料号');
  const rows = parsePopoverRows(pop.lines);
  for (const k of ['料号', '名称', '规格', '尺寸', '旧料号'] as const) {
    expect(rows[k], `${why}：浮层缺「${k}」行 ⇒ 五行不全\n原文=${pop.raw}`).toBeDefined();
    expect(rows[k], `${why}：「${k}」期望「${expected[k]}」实际「${rows[k]}」\n原文=${pop.raw}`).toBe(expected[k]);
  }
  expect(pop.raw.includes('生产状态'), `${why}：浮层仍出现「生产状态」行（AC-3 要求删除）\n原文=${pop.raw}`).toBe(false);
  const idx = (s: string) => pop.raw.indexOf(s);
  const order = ['料号', '名称', '规格', '尺寸', '旧料号'].map(idx);
  expect(order.every((v, i) => i === 0 || v > order[i - 1]),
    `${why}：五行顺序不是 料号/名称/规格/尺寸/旧料号，实际下标=${JSON.stringify(order)}\n原文=${pop.raw}`).toBe(true);
}

// ── 清理：只删本片自造、且必须带前缀 WHERE、且执行前先量化命中面 ──────────
/**
 * 🚦 `CLAUDE.md §3.2` 的落地形态：DELETE 必须
 *    ① 带 WHERE 且 WHERE 里出现本片前缀；② 执行前 `SELECT count(*)` 量化命中面；③ 删后回读 = 0。
 * 🚨 若被 hook 拒 → **停下报主线**，🚫 不换写法重试；调用方负责把这批行写进「待回收清单」。
 */
export function cleanupPrefixed(table: string, whereClause: string): { before: number; after: number; ok: boolean; err?: string } {
  if (!whereClause.includes(TAG)) throw new Error(`🚨 清理 WHERE 必须含前缀 ${TAG}：${whereClause}`);
  const before = Number(sqlScalar(`SELECT count(*) FROM ${table} WHERE ${whereClause}`));
  appendEvidence('90-写入面台账.txt', `${new Date().toISOString()} [cleanup-precount] ${table} WHERE ${whereClause} → ${before} 行\n`);
  if (before === 0) return { before, after: 0, ok: true };
  try {
    runPsql(`DELETE FROM ${table} WHERE ${whereClause}`, '-X -A -t');
  } catch (e: any) {
    const err = String(e?.message || e).slice(0, 400);
    appendEvidence('90-写入面台账.txt', `${new Date().toISOString()} [cleanup-BLOCKED] ${table}: ${err}\n`);
    return { before, after: before, ok: false, err };
  }
  const after = Number(sqlScalar(`SELECT count(*) FROM ${table} WHERE ${whereClause}`));
  appendEvidence('90-写入面台账.txt', `${new Date().toISOString()} [cleanup] ${table} ${before} → ${after}\n`);
  return { before, after, ok: after === 0 };
}
