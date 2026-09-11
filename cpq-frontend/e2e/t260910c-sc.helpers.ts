/**
 * task-260910 · 分片 S-C（方案② 与 `_record`）的 E2E 共享 helpers。
 *
 * 🚫 本文件与配套 spec 的全部断言，均从
 *    `dev-docs/task-260910-选配切ds新表与已有料号绑定/需求文档.md §③` 的 AC 原文 +
 *    同目录 `api.md` + `原型图/01-配件类型选择-加第三张卡.html` / `02-绑定料号搜索面板.html` 派生。
 *    **不曾读过** `cpq-backend/src/main/java/**` 与 `cpq-frontend/src/**`（派工 prompt 段 c）。
 *    选择器词汇来自**原型图文案** + 既有 e2e 代码（`t260910.helpers.ts` 等，可读），均在允许范围内。
 *
 * ── 写入面登记（testing.md §4.3）──────────────────────────────────────────
 *   本片造数前缀 **`T260910C-`**。只 INSERT 带该前缀可识别的行：
 *     customer / quotation / ds_quote_material / ds_quote_material_bom / ds_quote_element_bom。
 *   经 UI 产生：ds_quote_customer_part / quotation_line_item / `_record` 两表。
 *   🚫 零 S-全局：不动用户启停用 / 角色权限 / 模板发布态 / 系统开关 / 公共基础数据。
 *   🚫 不做核价通过（写 `ds_quote_*` 主表 + `_history` 属全局写 ⇒ 归 S-全局 片）。
 *   🚫 无 DROP / TRUNCATE / 无 WHERE 的 DELETE。
 *
 * ── 端口纪律（派工 prompt 段 h）────────────────────────────────────────────
 *   🚫 不占 8081 / 5174（主线亲验用）。默认走临时端口，可用 PW_BASE_URL / PW_BACKEND_URL 覆盖。
 */
import { expect, Page } from '@playwright/test';
import { execSync } from 'child_process';
import * as fs from 'fs';
import * as nodePath from 'path';
import { fileURLToPath } from 'url';

const HERE = nodePath.dirname(fileURLToPath(import.meta.url)); // AP-43: ESM 无 __dirname

export const BASE_URL = process.env.PW_BASE_URL || 'http://localhost:5187';
export const BACKEND_URL = process.env.PW_BACKEND_URL || 'http://localhost:8117';
/** 🚨 本片专属造数前缀。派工写死。 */
export const C = 'T260910C-';
export const RUN = Math.random().toString(36).slice(2, 7);

export const DB = {
  host: process.env.PW_DB_HOST || '10.177.152.12',
  port: process.env.PW_DB_PORT || '5432',
  user: process.env.PW_DB_USER || 'postgres',
  db: process.env.PW_DB_NAME || 'cpq_db_0724',
  password: process.env.PW_DB_PASSWORD || 'joii5231',
};

export const EVIDENCE_DIR = nodePath.resolve(
  HERE, '..', '..', 'dev-docs', 'task-260910-选配切ds新表与已有料号绑定', '证据', 'S-C',
);
function ensureDir() { fs.mkdirSync(EVIDENCE_DIR, { recursive: true }); }
export function writeEvidence(file: string, text: string) {
  ensureDir(); const p = nodePath.join(EVIDENCE_DIR, file);
  fs.writeFileSync(p, text, 'utf-8'); console.log(`[evidence] → ${p}`); return p;
}
export function appendEvidence(file: string, text: string) {
  ensureDir(); fs.appendFileSync(nodePath.join(EVIDENCE_DIR, file), text, 'utf-8');
}
/**
 * 🚨 截图必须归档到任务目录，**不能留在 `test-results/`** ——
 * 那个目录每轮开跑前会被 Playwright 清空 ⇒ 留在那里的截图不算证据（test.md/需求文档 的证据纪律）。
 */
export async function shot(page: Page, name: string) {
  ensureDir(); const p = nodePath.join(EVIDENCE_DIR, `${name}.png`);
  await page.screenshot({ path: p, fullPage: true }).catch(() => {});
  console.log(`[shot] → ${p}`); return p;
}

// ── SQL：只读为默认；写只允许「本片自造 + 前缀可识别」 ─────────────────────
const RED = /\b(drop|truncate|alter|create\s+(table|database|schema)|grant|revoke)\b/i;
function shellQuote(s: string) { return `'` + s.split(`'`).join(`'\\''`) + `'`; }
function runPsql(sql: string, flags = "-X -A -F'|'") {
  return execSync(
    `PGPASSWORD=${DB.password} psql -h ${DB.host} -p ${DB.port} -U ${DB.user} -d ${DB.db} ${flags} -c ${shellQuote(sql)}`,
    { shell: '/bin/bash', encoding: 'utf-8' },
  ).trim();
}
export function sqlRO(sql: string): string {
  if (RED.test(sql)) throw new Error(`🚨 §3.2 红线：只读通道里出现了破坏性语句 ⇒ 拒绝执行：${sql}`);
  if (/^\s*(delete|update|insert)\b/i.test(sql)) throw new Error(`sqlRO 只跑只读查询：${sql}`);
  return runPsql(sql);
}
export function sqlScalar(sql: string): string { return sqlRO(sql).split('\n')[0] ?? ''; }
/** 本片自造数据的写通道 —— 必须说明「为什么这行是我的」。 */
export function sqlOwnedWrite(sql: string, why: string): string {
  if (RED.test(sql)) throw new Error(`🚨 §3.2 红线：拒绝执行 ${sql}`);
  if (/\bdelete\b/i.test(sql) && !/\bwhere\b/i.test(sql)) {
    throw new Error(`🚨 §3.2 红线：无 WHERE 的 DELETE ⇒ 拒绝执行：${sql}`);
  }
  if (!/(T260910C-)/.test(sql)) {
    throw new Error(`🚨 分片纪律：写语句必须能靠 ${C} 前缀圈住命中面（why=${why}）：${sql}`);
  }
  return runPsql(sql);
}

/** 🚨 被测后端必须是 worktree 里的这份代码，且连的库必须是我查的这个库。 */
export async function assertEnvIdentity(): Promise<string> {
  const cookie = await loginApi();
  const res = await fetch(`${BACKEND_URL}/api/cpq/quotations?page=1&size=1`, { headers: { Cookie: cookie } });
  expect(res.status, `取 quotations 分页应 200（实际 ${res.status}）⇒ **环境问题**，不是产品缺陷`).toBe(200);
  const body: any = await res.json();
  const api = Number(body?.data?.totalElements);
  const db = Number(sqlScalar('SELECT count(*) FROM quotation'));
  // ⚠️ 这不是全局计数断言，是一致性不变式：别的会话造数会让两边**同时**变
  expect(api, `被测后端连的库与 ${DB.db} 对不上（api=${api} db=${db}）⇒ **环境问题**：`
    + `断言会打在别人的库上（探活只看 HTTP 200 探不出这件事）`).toBe(db);
  appendEvidence('00-环境正身.txt',
    `[${new Date().toISOString()}] backend=${BACKEND_URL} base=${BASE_URL} db=${DB.db} api=${api} db_count=${db}\n`);
  return cookie;
}

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
    throw new Error(`登录失败 ${res.status} ${(await res.text()).slice(0, 200)} `
      + `⇒ 先判限流/口令/Redis（test.md §6 已登记「Redis 不可用 ⇒ 登录 500」），🚫 不默认产品缺陷`);
  }
  throw new Error('登录反复 429（限流）⇒ 测试基础设施问题，不是 AC 结论');
}

export async function uiLogin(page: Page) {
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForTimeout(5000);
  expect(page.url(), '登录后应离开 /login').not.toContain('/login');
}

// ── 夹具：本片自造客户 + 报价单（带产品分类，否则卡片模板解析不出来）────────
export interface CFx { customerNo: string; quotationId: string; quotationNumber: string; }

export function seedCustomerAndQuotation(label: string): CFx {
  const customerNo = `${C}${label}${RUN}`.slice(0, 20);           // customer.code / customer_no 有长度上限
  const quotationNumber = `${C}QT-${label}${RUN}`;
  // 与共享参照客户同一个产品分类（🚫 只读它的 id，不改 CUST-0004 任何字段）
  const catId = sqlScalar(`SELECT product_category_id::text FROM customer WHERE code='CUST-0004'`);
  expect(catId, '夹具前置：参照客户 CUST-0004 应有产品分类（卡片模板经分类解析）'
    + ' —— 取不到时「页签 0 行」会是夹具问题而不是 AC-11 的结论').toMatch(/^[0-9a-f-]{36}$/);
  sqlOwnedWrite(
    `INSERT INTO customer (id,name,code,level,product_category_id,accumulated_amount,status,version,created_at,updated_at)
     VALUES (gen_random_uuid(),'${C}客户-${label}','${customerNo}','STANDARD','${catId}',0,'ACTIVE',0,NOW(),NOW())`,
    '本片自造客户');
  sqlOwnedWrite(
    `INSERT INTO quotation (id,quotation_number,customer_id,name,sales_rep_id,status,tax_rate,tax_amount,
       bound_global_variables_snapshot,product_category_id,user_data_version,created_at,updated_at)
     SELECT gen_random_uuid(),'${quotationNumber}',c.id,'${C}报价单-${label}',u.id,'DRAFT',0,0,'{}'::jsonb,'${catId}',0,NOW(),NOW()
       FROM customer c, "user" u WHERE c.code='${customerNo}' AND u.username='admin'`,
    '本片自造报价单');
  const quotationId = sqlScalar(`SELECT id::text FROM quotation WHERE quotation_number='${quotationNumber}'`);
  expect(quotationId, '夹具自检：自造报价单应能查回 id').toMatch(/^[0-9a-f-]{36}$/);
  console.log(`[夹具] customer=${customerNo} quotation=${quotationNumber} (${quotationId}) category=${catId}`);
  return { customerNo, quotationId, quotationNumber };
}

/** 造一个本片独有的「已有销售料号」（AC-20 的绑定目标）。 */
export function seedExistingSalesMaterial(fx: CFx, tag: string): string {
  const mno = `${C}${tag}${RUN}`.slice(0, 20);
  sqlOwnedWrite(
    `INSERT INTO ds_quote_material (customer_no,material_no,material_name,material_type,source,created_at)
     VALUES ('${fx.customerNo}','${mno}','${C}既有料号','零件','TEST',now())`, '本片自造既有料号主档');
  for (const [seq, input, ratio] of [[1, 'AgCu90', '60'], [2, '00006', '40']] as const) {
    sqlOwnedWrite(
      `INSERT INTO ds_quote_material_bom
         (customer_no,material_no,item_seq,input_material_no,material_ratio,version_no,row_fingerprint,source,created_at)
       VALUES ('${fx.customerNo}','${mno}',${seq},'${input}',${ratio},1,md5('${mno}-m${seq}')||md5('${RUN}'),'TEST',now())`,
      '本片自造既有 BOM 行');
  }
  const n = Number(sqlScalar(`SELECT count(*) FROM ds_quote_material_bom WHERE material_no='${mno}'`));
  expect(n, `夹具自检：既有料号 ${mno} 应有 2 行 BOM，否则「绑定后渲染出既有数据」是空验证`).toBe(2);
  return mno;
}

/** 还原：命中面全部被 `T260910C-` 前缀 + 本片自造 id 限死。🚫 无 TRUNCATE/DROP。 */
export function cleanupFixture(fx: CFx, materialNos: string[]) {
  const log: string[] = [];
  const del = (sql: string, why: string) => {
    try { log.push(`${why}: ${sqlOwnedWrite(sql, why)}`); }
    catch (e: any) { log.push(`${why}: ERR ${String(e).slice(0, 200)}`); }
  };
  for (const t of ['ds_quote_material_bom_record', 'ds_quote_element_bom_record']) {
    del(`DELETE FROM ${t} WHERE quotation_id IN (SELECT id FROM quotation WHERE quotation_number LIKE '${C}%' AND quotation_number='${fx.quotationNumber}')`, `清 ${t}`);
  }
  del(`DELETE FROM quotation_line_component_data WHERE line_item_id IN (SELECT li.id FROM quotation_line_item li JOIN quotation q ON q.id=li.quotation_id WHERE q.quotation_number='${fx.quotationNumber}' AND q.quotation_number LIKE '${C}%')`, '清卡片数据');
  del(`DELETE FROM quotation_line_process WHERE line_item_id IN (SELECT li.id FROM quotation_line_item li JOIN quotation q ON q.id=li.quotation_id WHERE q.quotation_number='${fx.quotationNumber}' AND q.quotation_number LIKE '${C}%')`, '清行工序');
  del(`DELETE FROM quotation_line_item WHERE quotation_id IN (SELECT id FROM quotation WHERE quotation_number='${fx.quotationNumber}' AND quotation_number LIKE '${C}%')`, '清报价行');
  del(`DELETE FROM quotation WHERE quotation_number='${fx.quotationNumber}' AND quotation_number LIKE '${C}%'`, '清报价单');
  del(`DELETE FROM ds_quote_customer_part WHERE customer_no='${fx.customerNo}' AND customer_no LIKE '${C}%'`, '清客户料号');
  for (const mno of materialNos) {
    del(`DELETE FROM ds_quote_element_bom WHERE material_no='${mno}' AND material_no LIKE '${C}%'`, `清元素BOM ${mno}`);
    del(`DELETE FROM ds_quote_material_bom WHERE material_no='${mno}' AND material_no LIKE '${C}%'`, `清物料BOM ${mno}`);
    del(`DELETE FROM ds_quote_material WHERE material_no='${mno}' AND material_no LIKE '${C}%'`, `清料号主档 ${mno}`);
  }
  del(`DELETE FROM sel_part_signature WHERE customer_no='${fx.customerNo}' AND customer_no LIKE '${C}%'`, '清指纹');
  del(`DELETE FROM customer WHERE code='${fx.customerNo}' AND code LIKE '${C}%'`, '清客户');
  appendEvidence('99-还原.txt', log.join('\n') + '\n');
  console.log('[还原]\n' + log.join('\n'));
  // 还原自检：残留必须以「残留」的名义可见
  const left = Number(sqlScalar(`SELECT count(*) FROM customer WHERE code='${fx.customerNo}'`));
  if (left !== 0) console.warn(`[还原] ⚠️ customer ${fx.customerNo} 仍残留 ${left} 行（已如实记录，不静默）`);
}

/** 打开报价单编辑页并进到 Step2「添加产品」。⚠️ 点 step 标题不生效，必须点「下一步」按钮。 */
export async function openStep2(page: Page, fx: CFx) {
  await page.goto(`/quotations/${fx.quotationId}/edit`);
  await page.waitForSelector('.ant-steps-item', { timeout: 90_000 }).catch(() => {
    throw new Error(`打开 ${fx.quotationNumber} 编辑页 90s 内没渲染出 Steps ⇒ **入口/环境问题**，判【未验证】`);
  });
  const next = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  await expect(next, 'Step1 找不到「下一步」⇒ **入口问题**，判【未验证】').toBeVisible({ timeout: 60_000 });
  await expect(next, 'Step1「下一步」不可点（常见于夹具单缺产品分类/模板）⇒ **夹具问题**，判【未验证】')
    .toBeEnabled({ timeout: 30_000 });
  await next.click();
  await page.waitForFunction(() => {
    const a = (document.querySelector('.ant-steps-item-active') as HTMLElement)?.innerText || '';
    return a.includes('添加产品');
  }, undefined, { timeout: 90_000 }).catch(() => { /* 下面显式断言给可读信息 */ });
  const active = await page.evaluate(() =>
    (document.querySelector('.ant-steps-item-active') as HTMLElement)?.innerText?.replace(/\s+/g, ' ').trim());
  expect(active, `没进到 Step2（当前=${active}）⇒ **入口问题**，判【未验证】`).toContain('添加产品');
}

/**
 * 点开某个页签并读回它的可见行。
 *
 * ⚠️ 既有选择器坑（派工 prompt 段 h）：antd 类名 / 两字按钮渲染成「保 存」/
 * 下拉虚拟滚动 / `fill()` 不触发 `onSearch` —— 这些**都表现为 timeout**，
 * 极易被误判成产品缺陷。⇒ 页签用 `.ant-tabs-tab` 的文本匹配（去空白后比较），
 * 找不到时给出「当前有哪些页签」的可读信息，而不是干等到超时。
 */
export async function openTabAndReadRows(page: Page, tabName: string): Promise<{ header: string[]; rows: string[][] }> {
  const tabs = await page.evaluate(() => Array.from(document.querySelectorAll('.ant-tabs-tab'))
    .map((e) => (e as HTMLElement).innerText.replace(/\s+/g, '').trim()));
  const want = tabName.replace(/\s+/g, '');
  expect(tabs.some((t) => t.includes(want)),
    `找不到页签「${tabName}」⇒ **入口/模板问题**，不是 AC-11 的结论。当前页签 = ${JSON.stringify(tabs)}`).toBe(true);
  await page.locator('.ant-tabs-tab').filter({ hasText: new RegExp(want.split('').join('\\s*')) }).first().click();
  // 🚫 不用固定 sleep 赌：等到表格里既没有「加载中」也至少出现一行，或超时后由断言给可读信息
  await page.waitForFunction(() => {
    const p = document.querySelector('.ant-tabs-tabpane-active');
    if (!p) return false;
    const txt = (p as HTMLElement).innerText || '';
    if (txt.includes('加载中')) return false;
    return p.querySelectorAll('tbody tr').length > 0 || txt.includes('暂无数据') || txt.includes('No data');
  }, undefined, { timeout: 60_000 }).catch(() => { /* 交给断言 */ });
  return await page.evaluate(() => {
    const p = document.querySelector('.ant-tabs-tabpane-active');
    const header = Array.from(p?.querySelectorAll('thead th') || [])
      .map((e) => (e as HTMLElement).innerText.replace(/\s+/g, ' ').trim());
    const rows = Array.from(p?.querySelectorAll('tbody tr') || [])
      .filter((tr) => !tr.classList.contains('ant-table-measure-row'))
      .map((tr) => Array.from(tr.querySelectorAll('td')).map((td) => (td as HTMLElement).innerText.replace(/\s+/g, ' ').trim()));
    return { header, rows };
  });
}

/** 🚨 AP-31 族守卫：空列表 / 0 行 / 「—」/「加载中…」一律不算通过。 */
export function assertTabNonEmpty(tabName: string, t: { header: string[]; rows: string[][] }, why: string) {
  console.log(`[页签 ${tabName}] header=${JSON.stringify(t.header)}`);
  console.log(`[页签 ${tabName}] rows=${JSON.stringify(t.rows)}`);
  expect(t.rows.length, `AC-11①：页签「${tabName}」应渲染出**非空行**，实际 ${t.rows.length} 行。`
    + `\n  🚫 空列表 / 0 行 / 「—」/「加载中…」一律不算通过（AP-31 族）。\n  ${why}`).toBeGreaterThan(0);
  const allDash = t.rows.every((r) => r.every((c) => c === '' || c === '—' || c === '-' || c.includes('加载中')));
  expect(allDash, `AC-11①：页签「${tabName}」有 ${t.rows.length} 行，但**每一格都是空/「—」/「加载中…」**`
    + ` ⇒ 这是 AP-31/AP-38 的鬼魂行形态，等同于渲染失败。\n  rows=${JSON.stringify(t.rows)}`).toBe(false);
}

/** 取某列（按表头文本模糊匹配）在各行的值。 */
export function columnValues(t: { header: string[]; rows: string[][] }, headerLike: string): string[] {
  const idx = t.header.findIndex((h) => h.replace(/\s+/g, '').includes(headerLike.replace(/\s+/g, '')));
  expect(idx, `找不到列「${headerLike}」⇒ 表头 = ${JSON.stringify(t.header)}`).toBeGreaterThanOrEqual(0);
  return t.rows.map((r) => r[idx] ?? '');
}
