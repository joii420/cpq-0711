/**
 * repair-260911 · 分片 **S-A**（落库与渲染）测试 helpers
 *
 * 认领 AC：AC-R1 / AC-R2 / AC-R3 / AC-R4 / AC-R6（AC 原文见
 * `dev-docs/task-260910-.../repair-260911-卡片渲染三缺陷/问题说明.md §⑦`）。
 *
 * ── 环境 ────────────────────────────────────────────────────────────
 *   后端 8104 / 前端 5208 —— 都跑在 scratchpad 的 rsync 副本上（构建与源码双隔离，
 *   避免与同 worktree 并行的 F-3 抢 `target/` 与 HMR）。
 *   运行：PW_BASE_URL=http://localhost:5208 npx playwright test --config=e2e/r260911sa.config.ts
 *
 * ── 写入面登记（分片隔离）──────────────────────────────────────────
 *   造数前缀 **`T260911RA-`**。只 INSERT 带该前缀的 customer / quotation 两行，
 *   其余行由产品自身（选配 configure 接口）产生。
 *   🚫 零 DELETE / 零 DDL；UPDATE 仅限证伪实验且只打自己造的行（`sqlOwnedUpdate` 强校验）。
 *   🚫 断言一律「存在性 + 指定主键」，禁用全局 count(*) / 时间窗（共享库有并发会话在造数）。
 */
import { expect, Page } from '@playwright/test';
import { execSync } from 'child_process';
import * as fs from 'fs';
import * as nodePath from 'path';
import { fileURLToPath } from 'url';

const HERE = nodePath.dirname(fileURLToPath(import.meta.url)); // AP-43: ESM 无 __dirname

export const BASE_URL = process.env.PW_BASE_URL || 'http://localhost:5208';
export const TAG = 'T260911RA-';
export const RUN = process.env.PW_RUN_ID || Math.random().toString(36).slice(2, 7);

export const DB = {
  host: process.env.PW_DB_HOST || '10.177.152.12',
  port: process.env.PW_DB_PORT || '5432',
  user: process.env.PW_DB_USER || 'postgres',
  db: process.env.PW_DB_NAME || 'cpq_db_0724',
  password: process.env.PW_DB_PASSWORD || 'joii5231',
};

/** 参照单：只读地照抄它的产品分类 / 报价模板 / 核价模板（🚫 不改它一个字节）。 */
export const REF_QUOTATION = 'QT-20260911-0837';

export const EVIDENCE_DIR = nodePath.resolve(
  HERE, '..', '..', 'dev-docs', 'task-260910-选配切ds新表与已有料号绑定',
  'repair-260911-卡片渲染三缺陷', '证据',
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

// ── SQL ────────────────────────────────────────────────────────────────
const RED = /\b(drop|truncate|alter|grant|revoke|create\s+(table|database|schema))\b/i;
function shellQuote(s: string) { return `'` + s.split(`'`).join(`'\\''`) + `'`; }
function runPsql(sql: string, flags = "-X -A -F'|'") {
  return execSync(
    `PGPASSWORD=${DB.password} psql -h ${DB.host} -p ${DB.port} -U ${DB.user} -d ${DB.db} ${flags} -c ${shellQuote(sql)}`,
    { shell: '/bin/bash', encoding: 'utf-8' },
  ).trim();
}
export function sqlRO(sql: string): string {
  const t = sql.trim().replace(/;$/, '');
  if (!/^\s*(select|with|explain)\b/i.test(t)) throw new Error(`🚨 拒绝非只读 SQL：${t.slice(0, 160)}`);
  return runPsql(t);
}
/**
 * ⚠️ psql `-A -F'|'` 的输出尾部有一行 `(N rows)`；不剔掉它会被当成一行数据，
 *    「0 行」于是被读成「有一行」，夹具带着 undefined 往下跑，最后红在无关的地方。
 */
export function sqlRows(sql: string): Record<string, string>[] {
  const out = sqlRO(sql).split('\n').filter((l) => l.length > 0 && !/^\(\d+ rows?\)$/.test(l.trim()));
  if (out.length <= 1) return [];
  const headers = out[0].split('|');
  return out.slice(1).map((line) => {
    const cells = line.split('|'); const o: Record<string, string> = {};
    headers.forEach((h, i) => (o[h] = cells[i] ?? '')); return o;
  });
}
export function sqlScalar(sql: string): string {
  const t = sql.trim().replace(/;$/, '');
  if (!/^\s*(select|with)\b/i.test(t)) throw new Error(`🚨 拒绝非只读 SQL：${t.slice(0, 160)}`);
  return runPsql(t, '-X -A -t').split('\n')[0] ?? '';
}
/** 只允许 INSERT，且必须带本片前缀。 */
export function sqlOwnedInsert(sql: string, why: string): string {
  const t = sql.trim().replace(/;$/, '');
  if (!/^\s*insert\b/i.test(t)) throw new Error(`🚨 只允许 INSERT：${t.slice(0, 160)}`);
  if (RED.test(t)) throw new Error(`🚨 语句含破坏性关键字：${t.slice(0, 160)}`);
  if (!t.includes(TAG)) throw new Error(`🚨 INSERT 必须带前缀 ${TAG}：${t.slice(0, 160)}`);
  console.log(`[写库·${why}] ${t.replace(/\s+/g, ' ').slice(0, 200)}`);
  return runPsql(t, '-X -A -t');
}
/**
 * 证伪实验专用 UPDATE。三道闸：
 *   ① 必须是 UPDATE；② 必须带 WHERE；③ **命中面必须先量化**（调用方传 expectRows，
 *      本函数先跑同 WHERE 的 SELECT count(*) 核对，对不上直接抛）。
 * 🚨 只许打本片自己造的行（调用方负责把 WHERE 锁死在自造单上）——
 *    改存量单据数据属 `CLAUDE.md §3.2` 红线，测试员无批准权。
 */
export function sqlOwnedUpdate(sql: string, countSql: string, expectRows: number, why: string): string {
  const t = sql.trim().replace(/;$/, '');
  if (!/^\s*update\b/i.test(t)) throw new Error(`🚨 只允许 UPDATE：${t.slice(0, 160)}`);
  if (!/\bwhere\b/i.test(t)) throw new Error(`🚨 UPDATE 必须带 WHERE（§3.2 红线）：${t.slice(0, 160)}`);
  if (RED.test(t)) throw new Error(`🚨 语句含破坏性关键字：${t.slice(0, 160)}`);
  const actual = Number(sqlScalar(countSql));
  if (actual !== expectRows) {
    throw new Error(`🚨 命中面核对不过：期望 ${expectRows} 行，实测 ${actual} 行 —— 拒绝执行。countSql=${countSql}`);
  }
  console.log(`[证伪·UPDATE·${why}] 命中面已量化 = ${actual} 行\n  ${t.replace(/\s+/g, ' ').slice(0, 260)}`);
  const out = runPsql(t, '-X -A -t');
  console.log(`[证伪·UPDATE·${why}] psql 回显 = ${out}`);
  return out;
}

/** 环境同一性自检：库 + 前端来源 + 关键改动是否在被测代码里。 */
export async function assertEnvIdentity() {
  const db = sqlScalar('SELECT current_database()');
  expect(db, `连错库：${db}`).toBe(DB.db);
  const res = await fetch(`${BASE_URL}/src/pages/quotation/QuotationWizard.tsx`);
  expect(res.status, `${BASE_URL} 取不到 QuotationWizard 模块 ⇒ 前端不是 vite dev 或端口错`).toBe(200);
  const wizard = await res.text();
  const res2 = await fetch(`${BASE_URL}/src/pages/quotation/inputDefaults.ts`);
  const inputDefaults = res2.status === 200 ? await res2.text() : '';
  const id = {
    db,
    // F-2（AC-R2 落点丙）：onConfigureConfirm 末尾补 warmCardValues
    f2_warm: /warmCardValues\(\s*quotationId\s*,\s*basicItems/.test(wizard),
    // F-1（AC-R3/R5）：整数型 BASIC_DATA 放行
    f1_safeInt: /isSafeInteger/.test(inputDefaults),
    // F-3（AC-R6，X1）：syncLineItemsFromResponse 回灌 comp.rows
    f3_x1: /componentData/.test(wizard) && /rowData/i.test(wizard),
  };
  console.log('[env] ' + JSON.stringify(id));
  return id;
}

export async function uiLogin(page: Page) {
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForTimeout(5000);
  expect(page.url(), '登录后应离开 /login').not.toContain('/login');
}

export interface Fx { customerNo: string; quotationId: string; quotationNumber: string; }

/**
 * 自造客户 + 空草稿报价单（每次都新建 —— 一张单只放一个选配产品，
 * 卡片间不互相干扰，`.qt-product-card` 的 `.last()` 就一定是本轮那张）。
 *
 * 🔑 产品分类 / 报价模板 / 核价模板三者照抄参照单 `QT-20260911-0837`（CUST-0004 正泰同一套）。
 *    缺 `customer_template_id` 时 Step1「下一步」恒禁用 ⇒ 失败长得像产品缺陷，实为夹具缺件。
 * 📌 **与 AC-R1 前置「CUST-0004 报价单」的偏差**：本片按派工书要求全部造数带 `T260911RA-` 前缀，
 *    故用自造客户而非直接挂 CUST-0004。等价性：同一个 product_category_id + 同一个报价模板
 *    ⇒ 同一套组件与视图；且 `ds_quote_customer_part` 按 `customer_no` JOIN，选配会为自造客户
 *    写入自己的那一行（实测 F-2 轮次同型数据已证）。
 */
export function seedCustomerAndQuotation(label: string): Fx {
  const customerNo = `${TAG}${label}${RUN}`.slice(0, 20);
  const quotationNumber = `${TAG}QT-${label}${RUN}`;
  const ref = sqlRows(`SELECT q.product_category_id::text cat, q.customer_template_id::text tpl,
                              q.costing_card_template_id::text ctpl
                         FROM quotation q WHERE q.quotation_number='${REF_QUOTATION}'`)[0] ?? {};
  const catId = ref.cat ?? '', tplId = ref.tpl ?? '', ctplId = ref.ctpl ?? '';
  expect(catId, `夹具前置：参照单 ${REF_QUOTATION} 应有产品分类`).toMatch(/^[0-9a-f-]{36}$/);
  expect(tplId, '夹具前置：参照单应有报价模板（缺它 Step1「下一步」恒禁用）').toMatch(/^[0-9a-f-]{36}$/);
  console.log(`[夹具·照抄参照单] cat=${catId} quoteTpl=${tplId} costingTpl=${ctplId}`);
  sqlOwnedInsert(
    `INSERT INTO customer (id,name,code,level,product_category_id,accumulated_amount,status,version,created_at,updated_at)
     VALUES (gen_random_uuid(),'${TAG}客户-${label}','${customerNo}','STANDARD','${catId}',0,'ACTIVE',0,NOW(),NOW())`,
    '自造客户');
  sqlOwnedInsert(
    `INSERT INTO quotation (id,quotation_number,customer_id,name,sales_rep_id,status,tax_rate,tax_amount,
       bound_global_variables_snapshot,product_category_id,customer_template_id,costing_card_template_id,
       user_data_version,created_at,updated_at)
     SELECT gen_random_uuid(),'${quotationNumber}',c.id,'${TAG}报价单-${label}',u.id,'DRAFT',0,0,'{}'::jsonb,
            '${catId}','${tplId}',${ctplId ? `'${ctplId}'` : 'NULL'},0,NOW(),NOW()
       FROM customer c, "user" u WHERE c.code='${customerNo}' AND u.username='admin'`,
    '自造报价单');
  const quotationId = sqlScalar(`SELECT id::text FROM quotation WHERE quotation_number='${quotationNumber}'`);
  expect(quotationId, '夹具自检：自造报价单应能查回 id').toMatch(/^[0-9a-f-]{36}$/);
  appendEvidence('SA-待回收清单.txt',
    `${new Date().toISOString()} customer=${customerNo} quotation=${quotationNumber} (${quotationId})`
    + ` —— 🚦 本片不自行删除（§3.2 红线，无批准权），交主线随闸门 B 呈报用户\n`);
  console.log(`[夹具] customer=${customerNo} quotation=${quotationNumber} (${quotationId})`);
  return { customerNo, quotationId, quotationNumber };
}

/** 打开报价单编辑页并推进到 Step2「添加产品」。 */
export async function openStep2(page: Page, fx: Fx) {
  await page.goto(`/quotations/${fx.quotationId}/edit`);
  await page.waitForSelector('.ant-steps-item', { timeout: 90_000 });
  const next = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  await expect(next, 'Step1 找不到「下一步」⇒ **入口问题**').toBeVisible({ timeout: 60_000 });
  const why = await next.getAttribute('title');
  const enabled = await next.isEnabled();
  console.log(`[Step1 下一步] enabled=${enabled} title=${why}`);
  expect(enabled, `Step1「下一步」禁用，原因=「${why}」⇒ **夹具问题**，判【未验证】`).toBe(true);
  await next.click({ timeout: 30_000 });
  await page.waitForFunction(() => {
    const a = (document.querySelector('.ant-steps-item-active') as HTMLElement)?.innerText || '';
    return a.includes('添加产品');
  }, undefined, { timeout: 90_000 }).catch(() => { /* 下面断言给可读信息 */ });
  const active = await page.evaluate(() =>
    (document.querySelector('.ant-steps-item-active') as HTMLElement)?.innerText?.replace(/\s+/g, ' ').trim());
  expect(active, `没进到 Step2（当前=${active}）⇒ **入口问题**，判【未验证】`).toContain('添加产品');
}

// ── 量具：读页签的行结构 ────────────────────────────────────────────────
export interface TabProbe {
  tabNames: string[];
  header: string[];
  rows: { nodeId: string | null; indentPx: number; cells: string[] }[];
}

/**
 * 点开页签并读回结构化行。
 * 🚨 量具口径（派工书 ⑧ 点名）：单元格值 = `textContent.trim() || 该格内 input/select/textarea 的 value`。
 *    只读 textContent 会把所有输入框判空 ⇒ 「整行全空」的断言假阳。
 */
export async function probeTab(page: Page, tabNameLike: string): Promise<TabProbe> {
  const card = page.locator('.qt-product-card').last();
  await expect(card, '没渲染出产品卡片 ⇒ 前置未满足，判【未验证】').toBeVisible({ timeout: 60_000 });
  await card.evaluate((el: Element) => new Promise<void>((res) => {
    const t0 = Date.now();
    const tick = () => {
      if (el.querySelectorAll('.qt-tab-btn').length > 0 || Date.now() - t0 > 90_000) res();
      else setTimeout(tick, 500);
    };
    tick();
  }));
  const tabs = await card.evaluate((el: Element) => Array.from(el.querySelectorAll('.qt-tab-btn'))
    .map((e) => (e as HTMLElement).innerText.replace(/\s+/g, '').trim()));
  const want = tabNameLike.replace(/\s+/g, '');
  const cardText = (await card.innerText()).replace(/\s+/g, ' ').slice(0, 500);
  expect(tabs.some((t) => t === want || t.includes(want)),
    `找不到页签「${tabNameLike}」⇒ **入口/模板问题**，不是 AC 的结论。`
    + `\n  当前页签 = ${JSON.stringify(tabs)}\n  卡片文本 = ${cardText}`).toBe(true);
  const exact = tabs.findIndex((t) => t === want);
  const idx = exact >= 0 ? exact : tabs.findIndex((t) => t.includes(want));
  await card.locator('.qt-tab-btn').nth(idx).click();
  await card.evaluate((el: Element) => new Promise<void>((res) => {
    const t0 = Date.now();
    const tick = () => {
      const t = el.querySelector('table.qt-cost-table');
      const txt = (el.querySelector('.qt-tab-section') as HTMLElement | null)?.innerText || '';
      const ok = !!t && !txt.includes('加载中')
        && (t.querySelectorAll('tbody tr').length > 0 || txt.includes('暂无') || txt.includes('没有'));
      if (ok || Date.now() - t0 > 90_000) res(); else setTimeout(tick, 500);
    };
    tick();
  }));
  return await card.evaluate((el: Element, tabsArg: string[]) => {
    const t = el.querySelector('table.qt-cost-table');
    const cellText = (td: Element) => {
      const own = (td as HTMLElement).textContent?.replace(/\s+/g, ' ').trim() ?? '';
      if (own) return own;
      const f = td.querySelector('input, select, textarea') as HTMLInputElement | null;
      return (f?.value ?? '').trim();
    };
    const header = Array.from(t?.querySelectorAll('thead th') || [])
      .map((e) => (e as HTMLElement).innerText.replace(/\s+/g, ' ').trim());
    const rows = Array.from(t?.querySelectorAll('tbody tr') || [])
      .map((tr) => {
        const tds = Array.from(tr.querySelectorAll('td'));
        const spacer = tds[0]?.querySelector('span > span') as HTMLElement | null;
        const indentPx = spacer ? Math.round(spacer.getBoundingClientRect().width) : 0;
        return { nodeId: tr.getAttribute('data-node-id'), indentPx, cells: tds.map(cellText) };
      });
    return { tabNames: tabsArg, header, rows };
  }, tabs);
}

/** 按表头名取某行某列的值（表头名可能带换行 / 单位后缀 ⇒ 用 includes 匹配）。 */
export function cellByHeader(p: TabProbe, row: { cells: string[] }, headerLike: string): string {
  const i = p.header.findIndex((h) => h.replace(/\s+/g, '') === headerLike.replace(/\s+/g, ''));
  const j = i >= 0 ? i : p.header.findIndex((h) => h.replace(/\s+/g, '').includes(headerLike.replace(/\s+/g, '')));
  if (j < 0) return '__NO_SUCH_HEADER__';
  return row.cells[j] ?? '';
}

/**
 * 「整行全空」判据（AC-R2③）。
 *
 * 🚨 **这里踩过一次假绿**：初版直接 `cells.every(c => c==='')`，但每行末尾都有操作列
 *    「＋✕」、树模式首格还有「▼」「✂」等**纯控件字形** ⇒ 任何一行都不可能被判为全空
 *    ⇒ AC-R2③ 变成**永远为真的空断言**。
 *    ⇒ 判空前必须先剥掉控件字形，只看**业务文本**。FT-3（把 quote_card_values 置 NULL
 *      退回平铺）就是用来证明本判据真的会变红的。
 */
const CONTROL_GLYPHS = /[＋✕✂▼▶＞－​\s]/g;
export function rowIsAllEmpty(cells: string[]): boolean {
  const biz = cells.map((c) => c.replace(CONTROL_GLYPHS, ''));
  return biz.length > 0 && biz.every((c) => c === '' || c === '—' || c === '-' || c.includes('加载中'));
}

// ── 证伪实验用：自造行的 jsonb 列备份 / 置空 / 还原 ────────────────────
export function backupJsonb(table: string, idCol: string, id: string, col: string, file: string): number {
  const json = runPsql(`SELECT coalesce(${col}::text,'') FROM ${table} WHERE ${idCol}='${id}'`, '-X -A -t');
  fs.writeFileSync(file, json, 'utf-8');
  console.log(`[备份] ${table}.${col} (${id}) → ${file}  ${json.length} 字节`);
  return json.length;
}
export function restoreJsonb(table: string, idCol: string, id: string, col: string, file: string) {
  const json = fs.readFileSync(file, 'utf-8').trim();
  if (!json) throw new Error(`🚨 备份文件为空，拒绝还原：${file}`);
  if (json.includes('$j$')) throw new Error('🚨 备份内容含 $j$ 定界符，换一个定界符再来');
  const sql = `UPDATE ${table} SET ${col} = $j$${json}$j$::jsonb WHERE ${idCol}='${id}'`;
  const out = sqlOwnedUpdate(sql, `SELECT count(*) FROM ${table} WHERE ${idCol}='${id}'`, 1, `还原 ${col}`);
  const now = runPsql(`SELECT coalesce(${col}::text,'') FROM ${table} WHERE ${idCol}='${id}'`, '-X -A -t');
  if (now.length === 0) throw new Error('🚨 还原后仍为空 —— 立即报主线');
  console.log(`[还原] ${table}.${col} (${id}) ← ${file}  回显=${out} 现长度=${now.length}`);
  return now.length;
}

/** 刷新编辑页并推进回 Step2（AC-R1②/AC-R2/AC-R3 的「刷新后」帧）。 */
export async function reloadToStep2(page: Page) {
  await page.reload();
  await page.waitForSelector('.ant-steps-item', { timeout: 90_000 });
  const nx = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  await expect(nx, '刷新后 Step1 找不到「下一步」').toBeVisible({ timeout: 60_000 });
  await expect(nx, '刷新后 Step1「下一步」应可点').toBeEnabled({ timeout: 60_000 });
  await nx.click();
  await page.waitForTimeout(18_000);
  await page.waitForFunction(() => !((document.body.innerText || '').includes('加载中')),
    undefined, { timeout: 60_000 }).catch(() => { /* 交给断言 */ });
}
