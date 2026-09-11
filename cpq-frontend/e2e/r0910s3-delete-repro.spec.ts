/**
 * repair-260910 · S3 片 —— 「删除行删错行」改动前现状复现（主线 2026-09-10 指令）
 *
 * 🚫 本文件的选择器与断言来自：AC 原文 / api.md / 既有 e2e 代码（repro-1982-delete.spec.ts、
 *    t260910-s3-addproduct.spec.ts）/ 运行时 DOM 探针。**未读过任何本次被改动的实现文件**。
 *
 * ── 写入面登记 ────────────────────────────────────────────────────────
 *   只写 R0910S3 前缀：ds_quote_material / ds_quote_customer_part（已由 SQL 预置）
 *   + 自建报价单 QT-20260910-0821（R0910S3-repro-*）。🚫 不碰 R0910S2，不碰 B17/B18 MANUAL 造数。
 *   被测目标 = 共享 5174/8081，其代码 = master = **改动前**（本次复现要的就是改动前现状）。
 */
import { test, expect, Page } from '@playwright/test';
import { execSync } from 'child_process';
import * as fs from 'fs';
import * as nodePath from 'path';
import { fileURLToPath } from 'url';

const HERE = nodePath.dirname(fileURLToPath(import.meta.url));
const EV = nodePath.resolve(HERE, '..', '..', 'dev-docs', 'task-260819-取数配置器',
  'repair-260910-客户产品编号维度与删行错位', '证据', 'S3');
const PREFIX = 'R0910S3';
const QID = 'ff570e47-424d-4595-964c-d145c2ba8457';   // 本片自建单 QT-20260910-0821
const COMP_PRODUCT = '221dc766-8ab6-4d95-82c0-08cc03e6267d';
const MAT = 'R0910S3-M1';

fs.mkdirSync(EV, { recursive: true });
function ev(f: string, t: string) { fs.writeFileSync(nodePath.join(EV, f), t, 'utf-8'); console.log(`[evidence] ${nodePath.join(EV, f)}`); }
function evA(f: string, t: string) { fs.appendFileSync(nodePath.join(EV, f), t, 'utf-8'); }
async function shot(page: Page, n: string) { await page.screenshot({ path: nodePath.join(EV, `${n}.png`), fullPage: true }).catch(() => {}); }

function psql(sql: string, flags = '-X -A -t') {
  const q = `'` + sql.split(`'`).join(`'\\''`) + `'`;
  return execSync(`PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 ${flags} -c ${q}`,
    { shell: '/bin/bash', encoding: 'utf-8', maxBuffer: 64 * 1024 * 1024 }).trim();
}
function sqlRO(sql: string, flags?: string) {
  if (!/^\s*(select|with)\b/i.test(sql.trim())) throw new Error(`拒绝非只读 SQL: ${sql.slice(0, 120)}`);
  return psql(sql.trim().replace(/;$/, ''), flags);
}

test.describe.configure({ mode: 'serial' });

/** 读当前激活页签的表格：每行 = 单元格值数组（input 取 value，否则取文本） */
async function readRows(page: Page): Promise<string[][]> {
  return page.evaluate(() => Array.from(document.querySelectorAll('.qt-cost-table tbody tr')).map((tr) =>
    Array.from(tr.querySelectorAll('td')).map((td) => {
      const inp = td.querySelector('input,select,textarea') as HTMLInputElement | null;
      return inp ? String(inp.value ?? '') : (td.textContent || '').trim();
    })));
}
async function readHeaders(page: Page): Promise<string[]> {
  return page.evaluate(() => Array.from(document.querySelectorAll('.qt-cost-table thead th')).map((th) => (th.textContent || '').trim()));
}
/** 每行的屏幕位置（top/height），用来说清「第 2 行」指的是屏幕上哪一行 */
async function readRowBoxes(page: Page): Promise<{ i: number; top: number; height: number }[]> {
  return page.evaluate(() => Array.from(document.querySelectorAll('.qt-cost-table tbody tr')).map((tr, i) => {
    const r = (tr as HTMLElement).getBoundingClientRect();
    return { i, top: Math.round(r.top + window.scrollY), height: Math.round(r.height) };
  }));
}
async function uiLogin(page: Page) {
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForTimeout(5000);
  expect(page.url(), '登录后应离开 /login').not.toContain('/login');
}
async function toTab(page: Page, tab: string) {
  const t = page.locator('.qt-tab-btn', { hasText: tab }).first();
  await expect(t, `找不到页签「${tab}」⇒ 入口问题，判【未验证】`).toBeVisible({ timeout: 30_000 });
  await t.click();
  await page.waitForTimeout(1500);
}

test('S3-R0 · 环境正身 + 夹具前置（必须先证明"多行"真的存在，否则断言空跑）', async () => {
  // ① 被测后端正身
  let ident = '';
  try {
    const ss = execSync(`ss -ltnp 2>/dev/null | /usr/bin/grep -a ':8081 ' || true`, { shell: '/bin/bash', encoding: 'utf-8' });
    const pid = (ss.match(/pid=(\d+)/) || [])[1] || '';
    const cwd = pid ? execSync(`readlink -f /proc/${pid}/cwd`, { shell: '/bin/bash', encoding: 'utf-8' }).trim() : '';
    const started = pid ? execSync(`ps -o lstart= -p ${pid}`, { shell: '/bin/bash', encoding: 'utf-8' }).trim() : '';
    ident = `backend 8081 pid=${pid} cwd=${cwd} started="${started}"`;
  } catch { ident = 'backend 8081 采样失败'; }
  const gitHead = execSync(`git -C ${nodePath.resolve(HERE, '..', '..')} rev-parse HEAD`, { shell: '/bin/bash', encoding: 'utf-8' }).trim();
  console.log('[identity] ' + ident);
  ev('00-环境正身.txt', `采样时刻=${new Date().toISOString()}\n${ident}\nworktree HEAD=${gitHead}\n` +
    `⚠️ 本次目标就是**改动前现状**：分支 HEAD == master，8081/5174 跑的即改动前代码。\n`);

  // ② 夹具：R0910S3-M1 在 ds_quote_customer_part 必须 >= 2 行（否则"多行"根本构造不出来）
  const n = Number(sqlRO(`SELECT count(*) FROM ds_quote_customer_part WHERE material_no='${MAT}' AND customer_no='CUST-0004'`));
  console.log(`[fixture] ds_quote_customer_part(${MAT}) = ${n} 行`);
  expect(n, `前置未满足：${MAT} 的客户料号 < 2 行，构造不出多行场景 ⇒ 断言会空跑`).toBeGreaterThanOrEqual(2);

  // ③ 视图层先验一次：改动前这个 JOIN 到底放大成几行
  const raw = sqlRO(
    `SELECT dqm.material_no, dqcp.id AS cp_id, dqcp.customer_product_no, dqcp.customer_part_name
     FROM ds_quote_material dqm LEFT JOIN ds_quote_customer_part dqcp
       ON dqcp.material_no = dqm.material_no AND dqcp.customer_no = 'CUST-0004'
     WHERE dqm.material_no = '${MAT}' AND dqm.customer_no = 'CUST-0004'
     ORDER BY dqm.material_no`, `-X -A -F'|'`);
  console.log('[view-level 放大]\n' + raw);
  ev('01-视图层放大-改动前.txt', `SQL 同 builder_221dc7668ab6 的 FROM/JOIN/WHERE/ORDER BY（未加 customer_product_no 谓词）\n\n${raw}\n`);
  expect(raw.split('\n').length, '视图层没放大成多行 ⇒ 后面的删除复现无意义').toBeGreaterThanOrEqual(2);
});

test('S3-R1 · 经 UI「从已有产品添加」把 R0910S3-M1 加进自建单并保存', async ({ page }) => {
  test.setTimeout(300_000);
  await uiLogin(page);
  await page.goto(`/quotations/${QID}/edit`);
  await page.waitForSelector('.ant-steps-item', { timeout: 90_000 });
  const next = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  await expect(next, 'Step1「下一步」不可点 ⇒ 夹具问题，判【未验证】').toBeEnabled({ timeout: 40_000 });
  await next.click();
  await page.waitForTimeout(8000);

  // 已有卡片就跳过添加（重跑幂等）
  if (await page.locator('.qt-product-card').count() === 0) {
    const addBtn = page.locator('button').filter({ hasText: /添加产品/ }).first();
    await expect(addBtn, '找不到「添加产品」⇒ 入口问题').toBeVisible({ timeout: 30_000 });
    await addBtn.click();
    await page.waitForTimeout(2500);
    await page.locator('.ant-dropdown:visible .ant-dropdown-menu-item').filter({ hasText: /从已有产品添加/ }).first().click();
    await page.waitForTimeout(8000);
    const layer = page.locator('.ant-drawer, .ant-modal').first();
    await expect(layer, '「从已有产品添加」弹层没打开 ⇒ 入口问题').toBeVisible({ timeout: 40_000 });
    // 搜销售料号，缩到只剩我的料号
    const search = layer.locator('input').first();
    await search.fill(PREFIX);
    await page.keyboard.press('Enter');
    await page.waitForTimeout(5000);
    const rows = layer.locator('.ant-table-row');
    const cnt = await rows.count();
    console.log('[抽屉候选行数] ' + cnt);
    expect(cnt, '抽屉里搜不到 R0910S3-M1 ⇒ 夹具/入口问题，判【未验证】').toBeGreaterThan(0);
    await shot(page, '10-抽屉-搜到自造料号');
    await rows.first().locator('input[type="checkbox"]').first().click();
    await page.waitForTimeout(1200);
    await layer.locator('button').filter({ hasText: /加\s*入\s*报\s*价\s*单/ }).last().click();
    await page.waitForTimeout(20_000);
  }
  const cards = await page.locator('.qt-product-card').count();
  console.log('[卡片数] ' + cards);
  expect(cards, '加完应至少 1 张卡片').toBeGreaterThan(0);
  await shot(page, '11-卡片已添加');

  // 保存草稿，让 quotation_line_component_data 落库
  const save = page.locator('button').filter({ hasText: /^\s*保\s*存\s*(草稿)?\s*$/ }).first();
  if (await save.count() > 0) { await save.click(); await page.waitForTimeout(20_000); }
  await shot(page, '12-已保存');

  const li = sqlRO(`SELECT id::text||' | '||COALESCE(product_part_no_snapshot,'∅')||' | cpn='||COALESCE(customer_part_no,'∅')
     FROM quotation_line_item WHERE quotation_id='${QID}'`);
  console.log('[line items]\n' + li);
  ev('13-明细行.txt', li + '\n');
  expect(li, '自建单里没有明细行 ⇒ 添加失败，判【未验证】').toContain(MAT);
});

test('S3-R2 · 改动前现状：打开「产品」页签，逐行记录完整内容 + 屏幕位置，删第 2 行', async ({ page }) => {
  test.setTimeout(300_000);
  await uiLogin(page);
  await page.goto(`/quotations/${QID}/edit`);
  await page.waitForSelector('.ant-steps-item', { timeout: 90_000 });
  const next = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  await expect(next).toBeEnabled({ timeout: 40_000 });
  await next.click();
  await page.waitForTimeout(12_000);
  await toTab(page, '产品');
  await page.waitForTimeout(3000);

  // ── 后端权威：该组件的 snapshot_rows 里每行 driverRow 全量键值 ──────────
  const liId = sqlRO(`SELECT id::text FROM quotation_line_item WHERE quotation_id='${QID}' AND product_part_no_snapshot='${MAT}' LIMIT 1`);
  expect(liId, '找不到明细行 id').toMatch(/^[0-9a-f-]{36}$/);
  const snapJson = sqlRO(`SELECT COALESCE(snapshot_rows,'[]')::text FROM quotation_line_component_data
      WHERE line_item_id='${liId}' AND component_id='${COMP_PRODUCT}'`);
  let snapRows: any[] = [];
  try { snapRows = JSON.parse(snapJson || '[]'); } catch { snapRows = []; }
  const driverRows = snapRows.map((r: any) => r?.driverRow ?? {});
  console.log('[snapshot_rows 行数] ' + driverRows.length);
  let dump = `line_item_id=${liId}\ncomponent_id=${COMP_PRODUCT}\nsnapshot_rows 行数=${driverRows.length}\n\n`;
  driverRows.forEach((d: any, i: number) => {
    dump += `--- driverRow[${i}] 全量键值 ---\n`;
    Object.keys(d).sort().forEach((k) => { dump += `  ${JSON.stringify(k)}: ${JSON.stringify(d[k])}\n`; });
  });
  // 两两比对：driverRow 序列化后是否逐字节相同
  const ser = driverRows.map((d: any) => JSON.stringify(Object.keys(d).sort().map((k) => [k, d[k]])));
  const dupPairs: string[] = [];
  for (let i = 0; i < ser.length; i++) for (let j = i + 1; j < ser.length; j++) if (ser[i] === ser[j]) dupPairs.push(`${i}==${j}`);
  dump += `\n=== driverRow 两两全量比对 ===\n互相逐字节相同的行对: ${dupPairs.length ? dupPairs.join(', ') : '（无，各行内容互不相同）'}\n`;
  dump += `driverRow 是否含「客户产品编号」列: ${driverRows.length && Object.keys(driverRows[0]).some((k) => k.includes('客户产品编号')) ? '是' : '否'}\n`;
  ev('20-driverRow全量键值-改动前.txt', dump);
  console.log(dump.slice(0, 4000));

  // ── 前端：删前每行完整内容 + 屏幕位置 ──────────────────────────────
  const headers = await readHeaders(page);
  const before = await readRows(page);
  const boxesB = await readRowBoxes(page);
  console.log('[表头] ' + JSON.stringify(headers));
  console.log('[删前行] ' + JSON.stringify(before));
  expect(before.length, '产品页签渲染 0 行 ⇒ 断言会空跑，判【未验证】').toBeGreaterThan(0);
  await shot(page, '21-删除前-产品页签');

  const tombBefore = sqlRO(`SELECT COALESCE(deleted_row_keys,'[]')::text FROM quotation_line_component_data
      WHERE line_item_id='${liId}' AND component_id='${COMP_PRODUCT}'`);

  let beforeTxt = `表头=${JSON.stringify(headers, null, 1)}\n\n`;
  before.forEach((r, i) => { beforeTxt += `屏幕第 ${i + 1} 行 (top=${boxesB[i]?.top}px): ${JSON.stringify(r)}\n`; });
  beforeTxt += `\n删除前 deleted_row_keys = ${tombBefore}\n`;
  ev('22-删除前-逐行内容与屏幕位置.txt', beforeTxt);

  test.skip(before.length < 2, `产品页签只有 ${before.length} 行，无法"删第 2 行" ⇒ 判【未验证】`);

  const target = before[1];                       // 屏幕上的第 2 行
  const survivors = before.filter((_, i) => i !== 1);
  console.log('[将删除 屏幕第2行] ' + JSON.stringify(target));

  // ── 拦截 delete-driver-row 实际发出的 effKey / fp ─────────────────
  let sent: any = null;
  page.on('request', (req) => {
    if (req.url().includes('/delete-driver-row') && req.method() === 'POST') {
      try { sent = req.postDataJSON(); } catch { sent = req.postData(); }
    }
  });
  const delBtn = page.locator('.qt-cost-table tbody tr').nth(1).locator('td:last-child button').first();
  await expect(delBtn, '第 2 行找不到删除按钮 ⇒ 入口问题，判【未验证】').toBeVisible({ timeout: 15_000 });
  await delBtn.click();
  await page.waitForTimeout(4000);
  // 可能有二次确认
  const confirm = page.locator('.ant-popconfirm:visible button, .ant-modal-confirm:visible button').filter({ hasText: /确\s*定|确\s*认|是/ }).first();
  if (await confirm.count() > 0) { await confirm.click().catch(() => {}); await page.waitForTimeout(4000); }
  await page.waitForTimeout(6000);

  const after = await readRows(page);
  console.log('[删后行] ' + JSON.stringify(after));
  console.log('[发出 body] ' + JSON.stringify(sent));
  await shot(page, '23-删除后-产品页签');

  const tombAfter = sqlRO(`SELECT COALESCE(deleted_row_keys,'[]')::text FROM quotation_line_component_data
      WHERE line_item_id='${liId}' AND component_id='${COMP_PRODUCT}'`);

  // 消失的是哪一行？（按整行内容比对）
  const key = (r: string[]) => JSON.stringify(r);
  const afterSet = new Set(after.map(key));
  const vanished = before.filter((r) => !afterSet.has(key(r)));

  let rep = `=== 改动前现状复现结果 ===\n`;
  rep += `删除动作：点击**屏幕第 2 行**的删除按钮\n`;
  rep += `屏幕第 2 行删前内容：${JSON.stringify(target)}\n\n`;
  rep += `删前行数=${before.length}  删后行数=${after.length}\n`;
  before.forEach((r, i) => { rep += `  删前第 ${i + 1} 行: ${JSON.stringify(r)}\n`; });
  after.forEach((r, i) => { rep += `  删后第 ${i + 1} 行: ${JSON.stringify(r)}\n`; });
  rep += `\n实际消失的行（按整行内容 diff）：${JSON.stringify(vanished)}\n`;
  rep += `是否 = 被点的那一行：${vanished.length === 1 && key(vanished[0]) === key(target) ? '是（删对了）' : '否（删错行）'}\n\n`;
  rep += `前端实际发出的 delete-driver-row body：\n${JSON.stringify(sent, null, 2)}\n\n`;
  rep += `删除前 deleted_row_keys=${tombBefore}\n删除后 deleted_row_keys=${tombAfter}\n`;
  ev('24-删除结果-改动前.txt', rep);
  console.log(rep);

  // 断言前先断言非空
  expect(sent, '没抓到 delete-driver-row 请求 ⇒ 量具问题（删除可能走了别的通道），判【未验证】').not.toBeNull();
  expect(after.length, '删后行数应比删前少 1').toBe(before.length - 1);
  // 这条就是 E-2 的判据；改动前预期**红**
  expect(after.map(key), 'E-2：删掉的必须是被点的那一行（剩余行应逐字段等于原第 1、3、4 行）').toEqual(survivors.map(key));
});
