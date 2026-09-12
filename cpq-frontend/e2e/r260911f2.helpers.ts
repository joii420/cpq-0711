/**
 * repair-260911 · F-2（AC-R2 / AC-R4）自测 helpers
 *
 * 🚫 本文件与 spec 只服务于 F-2 的**开发自测**，不是正式测试资产（那归 test-engineer）。
 * ⚠️ 运行方式（🚫 不占 8081 / 5174 —— 5174 保留给主线亲验）：
 *     PW_BASE_URL=http://localhost:5205 npx playwright test --config=e2e/r260911f2.config.ts
 *   临时 vite 5205 的 /api 代理到共享后端 8081（默认 profile → cpq_db_0724）。
 *
 * ── 写入面登记 ─────────────────────────────────────────────────────────
 *   只 INSERT 带 `R260911F2-` 前缀的 customer / quotation 两行，其余行由**产品自身**
 *   （选配 configure 接口）产生。🚫 **零 DELETE / 零 UPDATE / 零 DDL** ——
 *   派工书明写「不许删任何存量数据，包括自己造的测试单」⇒ 一律登记进「待回收清单」交主线。
 */
import { expect, Page } from '@playwright/test';
import { execSync } from 'child_process';
import * as fs from 'fs';
import * as nodePath from 'path';
import { fileURLToPath } from 'url';

const HERE = nodePath.dirname(fileURLToPath(import.meta.url)); // AP-43: ESM 无 __dirname

export const BASE_URL = process.env.PW_BASE_URL || 'http://localhost:5205';
export const TAG = 'R260911F2-';
export const RUN = Math.random().toString(36).slice(2, 7);

export const DB = {
  host: process.env.PW_DB_HOST || '10.177.152.12',
  port: process.env.PW_DB_PORT || '5432',
  user: process.env.PW_DB_USER || 'postgres',
  db: process.env.PW_DB_NAME || 'cpq_db_0724',
  password: process.env.PW_DB_PASSWORD || 'joii5231',
};

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
const RED = /\b(drop|truncate|alter|delete|update|grant|revoke|create\s+(table|database|schema))\b/i;
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
 * 解析成对象数组。
 * ⚠️ psql `-A -F'|'` 的输出尾部有一行 `(N rows)` —— 不剔掉它会被当成一行数据，
 *    于是「0 行」被读成「有一行，第一列 = `(0 rows)`」，夹具带着 `undefined` 的 id 往下跑，
 *    最后红在一个完全无关的地方（首轮实跑就这么栽了：报成「选配菜单禁用」）。
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
/** 只允许 INSERT，且必须带本片前缀。🚫 DELETE/UPDATE/DDL 一律拒绝（红线由 §3.2 管，此处再加一道）。 */
export function sqlOwnedInsert(sql: string, why: string): string {
  const t = sql.trim().replace(/;$/, '');
  if (!/^\s*insert\b/i.test(t)) throw new Error(`🚨 只允许 INSERT：${t.slice(0, 160)}`);
  if (RED.test(t)) throw new Error(`🚨 语句含破坏性关键字：${t.slice(0, 160)}`);
  if (!t.includes(TAG)) throw new Error(`🚨 INSERT 必须带前缀 ${TAG}：${t.slice(0, 160)}`);
  console.log(`[写库·${why}] ${t.replace(/\s+/g, ' ').slice(0, 180)}`);
  return runPsql(t, '-X -A -t');
}

/** 环境同一性自检：库必须是 cpq_db_0724，前端必须是本 worktree 的临时端口。 */
export async function assertEnvIdentity() {
  const db = sqlScalar('SELECT current_database()');
  expect(db, `连错库：${db}`).toBe(DB.db);
  const res = await fetch(`${BASE_URL}/src/pages/quotation/QuotationWizard.tsx`);
  const src = await res.text();
  expect(res.status, `${BASE_URL} 取不到 QuotationWizard 模块`).toBe(200);
  return { db, hasFix: src.includes('warmCardValues(quotationId, basicItems') };
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
 * 自造客户 + 草稿报价单。
 * 🔑 **逐项对齐用户那张单 `QT-20260911-0837`**（分类 / 报价模板 / 核价模板三者照抄）——
 *    少了 `customer_template_id` 时 Step1「下一步」恒禁用（title=「请先填写产品分类和报价模板」），
 *    那种失败长得像产品缺陷，实际是夹具缺件（首轮实跑踩到，7 分钟超时）。
 */
export function seedCustomerAndQuotation(label: string): Fx {
  const customerNo = `${TAG}${label}${RUN}`.slice(0, 20);
  const quotationNumber = `${TAG}QT-${label}${RUN}`;
  const ref = sqlRows(`SELECT q.product_category_id::text cat, q.customer_template_id::text tpl,
                              q.costing_card_template_id::text ctpl
                         FROM quotation q WHERE q.quotation_number='QT-20260911-0837'`)[0] ?? {};
  const catId = ref.cat ?? '', tplId = ref.tpl ?? '', ctplId = ref.ctpl ?? '';
  expect(catId, '夹具前置：参照单 QT-20260911-0837 应有产品分类').toMatch(/^[0-9a-f-]{36}$/);
  expect(tplId, '夹具前置：参照单应有报价模板（缺它 Step1「下一步」恒禁用）').toMatch(/^[0-9a-f-]{36}$/);
  console.log(`[夹具·照抄参照单] cat=${catId} quoteTpl=${tplId} costingTpl=${ctplId}`);
  // 🩹 复用本片上一轮的夹具 —— 每跑一次就多一个客户 + 一张单，待回收清单会越滚越长。
  //    复用不影响 A/B：每轮都**新加一个选配产品**，断言只看新加那张卡片当帧的渲染。
  // PW_FRESH_FX=1 强制新建：同一张单堆多个产品时，卡片间可能互相干扰，
  //   要判「值不对是不是我堆出来的」就必须能开一张**只有 1 个产品**的干净单。
  const reuse = process.env.PW_FRESH_FX === '1' ? undefined : sqlRows(`SELECT c.code cust, q.quotation_number qn, q.id::text qid
                           FROM quotation q JOIN customer c ON c.id=q.customer_id
                          WHERE q.quotation_number LIKE '${TAG}%' AND q.status='DRAFT'
                            AND q.customer_template_id IS NOT NULL
                          ORDER BY q.created_at DESC LIMIT 1`)[0];
  if (reuse?.qid) {
    console.log(`[夹具·复用] customer=${reuse.cust} quotation=${reuse.qn} (${reuse.qid})`);
    return { customerNo: reuse.cust, quotationId: reuse.qid, quotationNumber: reuse.qn };
  }
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
  appendEvidence('F2-待回收清单.txt',
    `${new Date().toISOString()} customer=${customerNo} quotation=${quotationNumber} (${quotationId}) `
    + `—— 🚦 本片**不自行删除**（派工书：不许删任何存量数据，含自造单），交主线呈报用户\n`);
  console.log(`[夹具] customer=${customerNo} quotation=${quotationNumber} (${quotationId})`);
  return { customerNo, quotationId, quotationNumber };
}

/** 打开报价单编辑页并推进到 Step2「添加产品」。 */
export async function openStep2(page: Page, fx: Fx) {
  await page.goto(`/quotations/${fx.quotationId}/edit`);
  await page.waitForSelector('.ant-steps-item', { timeout: 90_000 });
  const next = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  // 🚨 快失败：禁用原因写在 button[title] 里（如「请先填写产品分类和报价模板」）。
  //    不先把它读出来的话，click 会一路重试到测试超时，7 分钟后报成「点不动」——
  //    那长得和产品缺陷一模一样，实际是夹具缺件。
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
  /** 每行：nodeId（树模式才有）/ 缩进像素 / 各格文本（textContent 取不到时回落 input.value） */
  rows: { nodeId: string | null; indentPx: number; cells: string[] }[];
}

/**
 * 点开页签并读回结构化行。
 * 🚨 量具口径：单元格值 = `textContent.trim() || 该格内 input/select/textarea 的 value`。
 *    只读 textContent 会把所有输入框判空 ⇒ 「整行全空」的断言会假阳（B-1 踩过）。
 */
export async function probeTab(page: Page, tabNameLike: string): Promise<TabProbe> {
  // 🔑 **只看最后一张产品卡片**（= 本轮新加的那一个）。一张单可能有多张卡片，
  //    全局选择器会读到别的卡片的页签 ⇒ 断言指向错误对象（既可能假绿也可能假红）。
  const card = page.locator('.qt-product-card').last();
  await expect(card, '没渲染出产品卡片 ⇒ 前置未满足，判【未验证】').toBeVisible({ timeout: 60_000 });
  // 🚨 页签**不是 antd Tabs**：报价卡片自己实现的 `.qt-tab-header > button.qt-tab-btn`
  //    + 内容区 `table.qt-cost-table`（`QuotationStep2.tsx:3211-3230`）。
  //    按 `.ant-tabs-tab` 找会恒定拿到空数组 —— 而那种失败长得像「模板没绑组件」（我先踩了一次）。
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
  const idx = tabs.findIndex((t) => t === want) >= 0 ? tabs.findIndex((t) => t === want)
                                                     : tabs.findIndex((t) => t.includes(want));
  await card.locator('.qt-tab-btn').nth(idx).click();
  // 等这张卡片的表格稳定：没有「加载中」，且要么有行、要么明确空态
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
        // 树模式下 BOM 固定列的缩进是首格里的空 span（width = depth × 16px）
        const spacer = tds[0]?.querySelector('span > span') as HTMLElement | null;
        const indentPx = spacer ? Math.round(spacer.getBoundingClientRect().width) : 0;
        return { nodeId: tr.getAttribute('data-node-id'), indentPx, cells: tds.map(cellText) };
      });
    return { tabNames: tabsArg, header, rows };
  }, tabs);
}

export function rowIsAllEmpty(cells: string[]): boolean {
  return cells.length > 0 && cells.every((c) => c === '' || c === '—' || c === '-' || c.includes('加载中'));
}
