/**
 * repair-260910「详情页卡片头部对齐编辑页」· S-只读片 共享 helpers
 *
 * 🚫 本文件与 spec 的全部断言，均从
 *      dev-docs/.../repair-260910-详情页卡片头部对齐/问题说明.md §⑥ AC-R1~AC-R10 原文
 *    + 同目录 原型图/详情页产品卡片.html
 *    派生。**不曾读过** cpq-frontend/src/** 与 cpq-backend/src/main/**（派工 prompt 段 c）。
 *    选择器词汇取自既有 e2e 代码（t260910.helpers.ts / quotation-detail-readonly-views.spec.ts）+ 原型图。
 *
 * ── 写入面登记（testing.md §4.3）────────────────────────────────
 *   S-只读片：**零写库**。本文件不提供任何写 SQL 通道（sqlRO 硬拒非 SELECT）。
 *   不造数、不清理、不动全局状态。
 */
import { expect, Page } from '@playwright/test';
import { execSync } from 'child_process';
import * as fs from 'fs';
import * as nodePath from 'path';
import { fileURLToPath } from 'url';

const HERE = nodePath.dirname(fileURLToPath(import.meta.url)); // AP-43: ESM 无 __dirname

export const BASE_URL = process.env.PW_BASE_URL || 'http://localhost:5212';
export const BACKEND_URL = process.env.PW_BACKEND_URL || 'http://localhost:8212';
export const WORKTREE_MARK = 'repair-260910-detail-header';

export const DB = {
  host: process.env.PW_DB_HOST || '10.177.152.12',
  port: process.env.PW_DB_PORT || '5432',
  user: process.env.PW_DB_USER || 'postgres',
  db: process.env.PW_DB_NAME || 'cpq_db_0724',
  password: process.env.PW_DB_PASSWORD || 'joii5231',
};

export const EVIDENCE_DIR = nodePath.resolve(
  HERE, '..', '..', 'dev-docs', 'task-260910-报价卡片客户料号与生产料号切新表',
  'repair-260910-详情页卡片头部对齐', '证据',
);
export const RUN_TAG = process.env.R260910_RUN || 'after';

function ensureDir() { fs.mkdirSync(EVIDENCE_DIR, { recursive: true }); }
export function writeEvidence(file: string, text: string) {
  ensureDir(); const p = nodePath.join(EVIDENCE_DIR, `${RUN_TAG}-${file}`);
  fs.writeFileSync(p, text, 'utf-8'); console.log(`[evidence] → ${p}`); return p;
}
export function appendEvidence(file: string, text: string) {
  ensureDir(); fs.appendFileSync(nodePath.join(EVIDENCE_DIR, `${RUN_TAG}-${file}`), text, 'utf-8');
}
export async function shot(page: Page, name: string) {
  ensureDir(); const p = nodePath.join(EVIDENCE_DIR, `${RUN_TAG}-${name}.png`);
  await page.screenshot({ path: p, fullPage: true }).catch(() => {});
  console.log(`[shot] → ${p}`); return p;
}

// ── SQL：只读（本片无写权限，硬拒） ─────────────────────────────
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
    throw new Error(`🚨 S-只读片拒绝非只读 SQL（本返修零写库需求）：${t.slice(0, 160)}`);
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
  const t = sql.trim().replace(/;$/, '');
  if (!/^\s*(select|with|explain)\b/i.test(t)) throw new Error(`🚨 只读片拒绝：${t.slice(0, 120)}`);
  return runPsql(t, '-X -A -t').split('\n')[0] ?? '';
}

// ── 环境正身（防「测了主工作区旧代码」「打在别人的库上」）────────
export function recordIdentity(): string {
  const port = (BACKEND_URL.match(/:(\d+)/) || [])[1] || '8212';
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
  // 🔬 证伪实验（A/B 负对照）专用开关：故意打到**改动前**的主工作区服务（5174/8081，master 代码），
  //    验证这批断言在「未修」时确实会**硬失败**。🚫 只在 R260910_SKIP_IDENTITY=1 时放行，
  //    正式验收轮**必须**走 worktree 临时端口（否则就是测了旧代码的假绿）。
  if (process.env.R260910_SKIP_IDENTITY === '1') {
    console.log('[⚠️ 负对照轮] 已跳过 worktree 正身断言 —— 本轮结果**不作为验收证据**，只用于证明断言能变红');
    appendEvidence('00-环境正身.txt', '[negative-control] 跳过 worktree 正身断言（本轮仅作证伪实验）\n');
  } else {
    expect(cwd, `🚨 被测后端 cwd 不在本 worktree（${cwd}）⇒ 可能测的是主工作区旧代码，这是**环境问题**不是产品缺陷`)
      .toContain(WORKTREE_MARK);
  }
  const cookie = await loginApi();
  const res = await fetch(`${BACKEND_URL}/api/cpq/quotations?page=1&size=1`, { headers: { Cookie: cookie } });
  expect(res.status, '取 quotations 分页应 200').toBe(200);
  const body: any = await res.json();
  const api = Number(body?.data?.totalElements);
  const db = Number(sqlScalar('SELECT count(*) FROM quotation'));
  // ⚠️ 不是全局计数断言，是「API 与 DB 同源」一致性不变式：别的会话造数会让两边**同时**变
  expect(api, `被测后端连的库与 ${DB.db} 对不上（api=${api} db=${db}）⇒ **环境问题**`).toBe(db);
  appendEvidence('00-环境正身.txt', `[db-identity] api=${api} db=${db}\n`);
}

// ── 鉴权 ─────────────────────────────────────────────────────
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
  expect(id, `前置未满足：库里找不到报价单 ${number} ⇒ **夹具缺失**，本条判【未验证】`).toMatch(/^[0-9a-f-]{36}$/);
  return id;
}

// ── 详情页入口（🚨 不是编辑页）────────────────────────────────
/** 打开报价单**详情页** /quotations/{id}，等「产品明细」+ 产品卡片渲染完。 */
export async function openDetail(page: Page, quotationNumber: string): Promise<string> {
  const qid = quotationIdOf(quotationNumber);
  await page.goto(`/quotations/${qid}`);
  await page.waitForSelector('.ant-card-head-title', { timeout: 90_000 })
    .catch(() => { throw new Error(`详情页 ${quotationNumber} 90s 未渲染 ⇒ **入口/环境问题**，判【未验证】`); });
  await page.locator('text=产品明细').first().scrollIntoViewIfNeeded().catch(() => {});
  await page.waitForFunction(() => document.querySelectorAll('.qt-product-card').length > 0, undefined, { timeout: 90_000 })
    .catch(() => { /* 下面用显式断言给可读信息 */ });
  await page.waitForTimeout(6000); // 让卡片内异步取数落定
  const n = await page.locator('.qt-product-card').count();
  expect(n, `详情页 ${quotationNumber} 未渲染出产品卡片（0 张）⇒ 断言会空跑，判【未验证】`).toBeGreaterThan(0);
  return qid;
}

/** 打开**编辑页** Step2（AC-R9 编辑页零回归用）。⚠️ 点 step 标题不生效，必须点「下一步」。 */
export async function openStep2(page: Page, quotationNumber: string): Promise<string> {
  const qid = quotationIdOf(quotationNumber);
  await page.goto(`/quotations/${qid}/edit`);
  await page.waitForSelector('.ant-steps-item', { timeout: 90_000 })
    .catch(() => { throw new Error(`编辑页 ${quotationNumber} 90s 未渲染 Steps ⇒ **入口/环境问题**，判【未验证】`); });
  const next = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  await expect(next, 'Step1 找不到「下一步」⇒ **入口问题**，判【未验证】').toBeVisible({ timeout: 60_000 });
  await expect(next, 'Step1「下一步」不可点（常见于夹具单缺产品分类/模板）⇒ **夹具问题**，判【未验证】').toBeEnabled({ timeout: 30_000 });
  await next.click();
  await page.waitForFunction(() => {
    const a = (document.querySelector('.ant-steps-item-active') as HTMLElement)?.innerText || '';
    return a.includes('添加产品') && document.querySelectorAll('.qt-product-card').length > 0;
  }, undefined, { timeout: 90_000 }).catch(() => { /* 下面显式断言 */ });
  await page.waitForTimeout(5000);
  const active = await page.evaluate(() => (document.querySelector('.ant-steps-item-active') as HTMLElement)?.innerText?.replace(/\s+/g, ' ').trim());
  expect(active, `没进到 Step2（当前=${active}）⇒ **入口问题**，判【未验证】`).toContain('添加产品');
  return qid;
}

// ── 卡片头部量具 ────────────────────────────────────────────────
export interface CardHeader {
  idx: number;
  hasHeader: boolean;
  hasRight: boolean;
  leftChildCount: number;
  rightChildCount: number;
  leftTexts: string[];
  rightTexts: string[];
  leftRaw: string;
  rightRaw: string;
  rightButtonCount: number;   // AC-R2 / E-7：右块不得有任何按钮
  templateBadgeCount: number; // AC-R6 的可观测面（现网恒 0，仅记录不断言）
  headerRaw: string;
  cardHeadText: string;       // 卡片前 200 字，用于定位是哪张卡
  domOrderOk: boolean;        // 左块在右块之前
}
export async function readCards(page: Page): Promise<CardHeader[]> {
  return page.evaluate(() => Array.from(document.querySelectorAll('.qt-product-card')).map((c, idx) => {
    const hdr = c.querySelector('.qt-card-header') as HTMLElement | null;
    const L = hdr?.querySelector('.qt-card-header-left') as HTMLElement | null;
    const R = hdr?.querySelector('.qt-card-header-right') as HTMLElement | null;
    const txt = (el: Element | null) => el
      ? Array.from(el.children).map((x) => (x as HTMLElement).innerText.replace(/\s+/g, ' ').trim()).filter(Boolean)
      : [];
    let domOrderOk = false;
    if (L && R) domOrderOk = !!(L.compareDocumentPosition(R) & Node.DOCUMENT_POSITION_FOLLOWING);
    return {
      idx,
      hasHeader: !!hdr,
      hasRight: !!R,
      leftChildCount: L ? L.childElementCount : -1,
      rightChildCount: R ? R.childElementCount : -1,
      leftTexts: txt(L),
      rightTexts: txt(R),
      leftRaw: (L?.innerText || '').replace(/\s+/g, ' ').trim(),
      rightRaw: (R?.innerText || '').replace(/\s+/g, ' ').trim(),
      rightButtonCount: R ? R.querySelectorAll('button, .ant-btn, [role="button"]').length : -1,
      templateBadgeCount: c.querySelectorAll('.qt-template-badge').length,
      headerRaw: (hdr?.innerText || '').replace(/\s+/g, ' ').trim(),
      cardHeadText: (c as HTMLElement).innerText.replace(/\s+/g, ' ').trim().slice(0, 200),
      domOrderOk,
    };
  }));
}

/** 点第 idx 张卡片头部的销售料号徽标，返回浮层文本。🚫 只在 .qt-card-header 内找，不误点表格里的徽标。 */
export async function openPopover(page: Page, idx: number): Promise<{ title: string; lines: string[]; raw: string; badgeText: string }> {
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(500);
  const badge = page.locator('.qt-product-card').nth(idx).locator('.qt-card-header .qt-part-badge').first();
  await expect(badge, `第 ${idx} 张卡片头部没有可点的销售料号徽标（.qt-card-header .qt-part-badge）⇒ AC-R3 的前提不成立`)
    .toBeVisible({ timeout: 15_000 });
  const badgeText = (await badge.innerText()).replace(/\s+/g, ' ').trim();
  await badge.click();
  await page.waitForTimeout(2500);
  // ⚠️ 量具坑（主任务实证）：浮层 label/value 是两个 <span>，innerText 会分行 ⇒ 读整块 raw 再配对
  const raw = await page.evaluate(() => Array.from(document.querySelectorAll('.ant-popover'))
    .filter((e) => (e as HTMLElement).offsetParent !== null)
    .map((e) => (e as HTMLElement).innerText).join('\n---\n'));
  const lines = raw.split('\n').map((s) => s.trim()).filter(Boolean);
  return { title: lines[0] ?? '', lines, raw, badgeText };
}

export function parsePopoverRows(lines: string[]): Record<string, string> {
  const LABELS = ['料号', '名称', '规格', '尺寸', '旧料号', '生产状态'];
  const out: Record<string, string> = {};
  for (let i = 0; i < lines.length; i++) {
    const m = lines[i].match(/^(料号|名称|规格|尺寸|旧料号|生产状态)\s*[：:]\s*(.*)$/);
    if (m) { out[m[1]] = m[2].trim() !== '' ? m[2].trim() : (lines[i + 1] ?? '').trim(); continue; }
    const bare = lines[i].replace(/[：:]\s*$/, '').trim();
    if (LABELS.includes(bare) && out[bare] === undefined) out[bare] = (lines[i + 1] ?? '').trim();
  }
  return out;
}

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
  expect(pop.raw.includes('生产状态'), `${why}：浮层仍出现「生产状态」行（AC-R3 要求无此行）\n原文=${pop.raw}`).toBe(false);
  const idx = (s: string) => pop.raw.indexOf(s);
  const order = ['料号', '名称', '规格', '尺寸', '旧料号'].map(idx);
  expect(order.every((v, i) => i === 0 || v > order[i - 1]),
    `${why}：五行顺序不是 料号/名称/规格/尺寸/旧料号，实际下标=${JSON.stringify(order)}\n原文=${pop.raw}`).toBe(true);
}

/** 收集 pageerror（AC-R5 / AC-R8 都要求「无 pageerror」）。 */
export function trapErrors(page: Page): string[] {
  const errs: string[] = [];
  page.on('pageerror', (e) => errs.push(String(e?.message || e)));
  return errs;
}
