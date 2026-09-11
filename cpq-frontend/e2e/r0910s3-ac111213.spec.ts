/**
 * repair-260910 · S3 片 —— AC-11 / AC-12 / AC-13（回归断言）
 *
 * 被测：worktree 新代码 —— 前端 5233 → 后端 8123（cwd 在 worktree 内，库 cpq_db_0724，已验明正身）
 * 🚫 未读任何本次被改动的实现文件；选择器来自既有 e2e 代码 + 运行时 DOM 探针。
 *
 * ── 写入面 ──────────────────────────────────────────────────────────
 *   AC-11：**纯只读**。只开 SUBMITTED 的 QT-20260910-0812 详情页，前后各拍一次库快照证明没改它。
 *          🚫 绝不碰 DRAFT 的 QT-20260908-0627（打开会触发 autosave 改写，全库仅存 2 张旧口径墓碑之一）。
 *   AC-12/13：只在**本片自建**的 QT-20260910-0830 上操作；夹具全 R0910S3 前缀。
 */
import { test, expect, Page } from '@playwright/test';
import { execSync } from 'child_process';
import * as fs from 'fs';
import * as nodePath from 'path';
import { fileURLToPath } from 'url';

const HERE = nodePath.dirname(fileURLToPath(import.meta.url));
const EV = nodePath.resolve(HERE, '..', '..', 'dev-docs', 'task-260819-取数配置器',
  'repair-260910-客户产品编号维度与删行错位', '证据', 'S3');
const LEGACY_QID = '5a0a0000-0000-0000-0000-000000000000'; // 占位，下面用 SQL 取真值
const LEGACY_NO = 'QT-20260910-0812';
const LEGACY_LI = 'e2203b0a-17fe-4778-b6f5-205a92476df9';
const COMP_PRODUCT = '221dc766-8ab6-4d95-82c0-08cc03e6267d';
const COMP_BOM = '7f9a5bbf-264f-4b07-8dff-3118a9428a48';
const REG_QID = '24032985-36d7-458d-a460-634d3239857d';   // 本片自建 QT-20260910-0830
const MAT = 'R0910S3-M1';

fs.mkdirSync(EV, { recursive: true });
function ev(f: string, t: string) { fs.writeFileSync(nodePath.join(EV, f), t, 'utf-8'); console.log(`[evidence] ${f}`); }
async function shot(page: Page, n: string) { await page.screenshot({ path: nodePath.join(EV, `${n}.png`), fullPage: true }).catch(() => {}); }
function psql(sql: string) {
  const q = `'` + sql.split(`'`).join(`'\\''`) + `'`;
  return execSync(`PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -X -A -t -c ${q}`,
    { shell: '/bin/bash', encoding: 'utf-8', maxBuffer: 64 * 1024 * 1024 }).trim();
}
function sqlRO(sql: string) {
  if (!/^\s*(select|with)\b/i.test(sql.trim())) throw new Error(`拒绝非只读 SQL: ${sql.slice(0, 120)}`);
  return psql(sql.trim().replace(/;$/, ''));
}
function qlcd(li: string, comp: string, col: string) {
  return sqlRO(`SELECT COALESCE(${col}::text,'∅') FROM quotation_line_component_data WHERE line_item_id='${li}' AND component_id='${comp}'`);
}
async function uiLogin(page: Page) {
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForTimeout(5000);
  expect(page.url(), '登录后应离开 /login').not.toContain('/login');
}
async function tabTexts(page: Page): Promise<string[]> {
  return page.evaluate(() => Array.from(document.querySelectorAll('.qt-tab-btn')).map((b) => (b as HTMLElement).innerText.replace(/\s+/g, '')));
}
async function rows(page: Page): Promise<string[][]> {
  return page.evaluate(() => Array.from(document.querySelectorAll('.qt-cost-table tbody tr')).map((tr) =>
    Array.from(tr.querySelectorAll('td')).map((td) => {
      const inp = td.querySelector('input,select,textarea') as HTMLInputElement | null;
      return inp ? String(inp.value ?? '') : (td.textContent || '').trim();
    })));
}
async function headers(page: Page): Promise<string[]> {
  return page.evaluate(() => Array.from(document.querySelectorAll('.qt-cost-table thead th')).map((th) => (th.textContent || '').trim()));
}
async function toStep2(page: Page, qid: string) {
  await page.goto(`/quotations/${qid}/edit`);
  await page.waitForSelector('.ant-steps-item', { timeout: 120_000 });
  const next = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  await expect(next, 'Step1「下一步」不可点 ⇒ 夹具问题，判【未验证】').toBeEnabled({ timeout: 60_000 });
  await next.click();
  await page.waitForTimeout(12_000);
}
async function toTab(page: Page, tab: string) {
  const t = page.locator('.qt-tab-btn', { hasText: tab }).first();
  await expect(t, `找不到页签「${tab}」⇒ 入口问题，判【未验证】`).toBeVisible({ timeout: 40_000 });
  await t.click();
  await page.waitForTimeout(2500);
}


/** 点掉任何可见的确认层（Modal / Popconfirm / 级联删除预览），并把它的文案打出来备查。 */
async function confirmAnyDialog(page: Page) {
  for (let i = 0; i < 3; i++) {
    const dlg = page.locator('.ant-modal-wrap:visible, .ant-modal:visible, .ant-popconfirm:visible').first();
    if (await dlg.count() === 0) break;
    const txt = await dlg.innerText().catch(() => '');
    console.log(`[确认层 #${i}] ` + JSON.stringify(txt.replace(/\s+/g, ' ').slice(0, 300)));
    const btn = dlg.locator('button').filter({ hasText: /确\s*定|确\s*认|删\s*除|是|OK/ }).last();
    if (await btn.count() === 0) { console.log('[确认层] 找不到确认按钮'); break; }
    await btn.click().catch(() => {});
    await page.waitForTimeout(4000);
  }
}

test.describe.configure({ mode: 'serial' });

// ─────────────────────────── AC-11 ───────────────────────────
test('AC-11 · 存量单（SUBMITTED QT-20260910-0812）打开后：已删的仍是删的、编辑值仍在，且打开不改库', async ({ page }) => {
  test.setTimeout(300_000);
  const before = {
    tomb: qlcd(LEGACY_LI, COMP_PRODUCT, 'deleted_row_keys'),
    rowData: qlcd(LEGACY_LI, COMP_PRODUCT, 'row_data'),
    snap: qlcd(LEGACY_LI, COMP_PRODUCT, 'snapshot_rows'),
    ver: qlcd(LEGACY_LI, COMP_PRODUCT, 'row_version'),
  };
  // 与改动前基线逐字节对照
  const baseFile = nodePath.join(EV, 'baseline-before', 'AC-11-legacy-tombstones-BEFORE.json');
  const baseTxt = fs.readFileSync(baseFile, 'utf-8');
  const baseObjs = baseTxt.split(/(?=\{\n    "qlcd_id")/).filter((s) => s.trim().startsWith('{'));
  const baseline = baseObjs.map((s) => JSON.parse(s)).find((o: any) => o.line_item_id === LEGACY_LI);
  expect(baseline, '找不到改动前基线 ⇒ 判【未验证】').toBeTruthy();

  expect(JSON.parse(before.tomb), 'AC-11：墓碑必须与**改动前基线**逐字节相同').toEqual(baseline.deleted_row_keys);
  expect(JSON.parse(before.rowData), 'AC-11：editRows(row_data) 必须与改动前基线逐字节相同').toEqual(baseline.row_data_editRows);
  const tombCount = JSON.parse(before.tomb).length;
  const snapCount = JSON.parse(before.snap).length;
  expect(tombCount, 'AC-11 前置：该单应有 3 条旧口径墓碑，否则断言空跑').toBe(3);
  expect(snapCount, 'AC-11 前置：该单 snapshot_rows 应为 4 行').toBe(4);

  const qid = sqlRO(`SELECT quotation_id::text FROM quotation_line_item WHERE id='${LEGACY_LI}'`);
  await uiLogin(page);
  await page.goto(`/quotations/${qid}`);           // SUBMITTED ⇒ 详情（只读）页
  await page.waitForTimeout(18_000);
  await shot(page, '50-AC11-存量单详情页');

  const hdr = await headers(page);
  const rs = await rows(page);
  const allText = await page.evaluate(() => document.body.innerText);
  console.log('[AC-11 表头] ' + JSON.stringify(hdr));
  console.log('[AC-11 行] ' + JSON.stringify(rs));

  const after = {
    tomb: qlcd(LEGACY_LI, COMP_PRODUCT, 'deleted_row_keys'),
    rowData: qlcd(LEGACY_LI, COMP_PRODUCT, 'row_data'),
    snap: qlcd(LEGACY_LI, COMP_PRODUCT, 'snapshot_rows'),
    ver: qlcd(LEGACY_LI, COMP_PRODUCT, 'row_version'),
  };

  let rep = `=== AC-11 存量单回归（改动后 · 8123/5233） ===\n报价单 ${LEGACY_NO} (${qid}) status=SUBMITTED\n明细行 ${LEGACY_LI} / 组件「产品」\n\n`;
  rep += `【打开前】\n deleted_row_keys = ${before.tomb}\n row_data(editRows) = ${before.rowData}\n row_version = ${before.ver}\n`;
  rep += ` snapshot_rows 客编 = ${JSON.stringify(JSON.parse(before.snap).map((r: any) => r?.driverRow?.['_客户料号_客户产品编号']))}\n\n`;
  rep += `【与改动前基线逐字节对照】墓碑 ✅一致  editRows ✅一致\n\n`;
  rep += `【详情页渲染】表头=${JSON.stringify(hdr)}\n`;
  rs.forEach((r, i) => { rep += ` 第 ${i + 1} 行: ${JSON.stringify(r)}\n`; });
  rep += `\n【打开后】\n deleted_row_keys = ${after.tomb}\n row_data(editRows) = ${after.rowData}\n row_version = ${after.ver}\n`;
  ev('51-AC11-存量单-改动后实录.txt', rep);
  console.log(rep);

  // ① 打开不得改库（这是最严重的回归形态）
  expect(after.tomb, 'AC-11：打开存量单**改写了墓碑** ⇒ 严重回归').toBe(before.tomb);
  expect(after.rowData, 'AC-11：打开存量单**改写了 editRows** ⇒ 严重回归（编辑值会大面积消失）').toBe(before.rowData);
  expect(after.snap, 'AC-11：打开存量单改写了 snapshot_rows').toBe(before.snap);
  expect(after.ver, 'AC-11：打开存量单 bump 了 row_version').toBe(before.ver);

  // ② 编辑值仍在：row_data 里那条客编必须出现在页面上
  const editVal = JSON.parse(before.rowData)[0]?.['客户产品编号'];
  expect(editVal, 'AC-11 前置：row_data 里取不到客编 ⇒ 断言空跑').toBeTruthy();
  expect(allText.includes(editVal), `AC-11：此前的编辑值「${editVal}」必须仍显示在详情页上`).toBe(true);

  // ③ 已删的仍是删的：3 条墓碑对应的行不得同时全部出现
  const deletedCPs = ['B17-DUP-0910-1', 'B17-DUP-0910-2', 'B18-BIND-0910B'];
  const stillShown = deletedCPs.filter((c) => allText.includes(c));
  console.log('[AC-11 墓碑对应客编仍显示的] ' + JSON.stringify(stillShown));
  fs.appendFileSync(nodePath.join(EV, '51-AC11-存量单-改动后实录.txt'),
    `\n【墓碑对应的 3 个客编里仍出现在页面上的】${JSON.stringify(stillShown)}\n` +
    `【此前的编辑值 ${editVal} 是否仍在页面】${allText.includes(editVal)}\n`, 'utf-8');
});

// ─────────────────────────── AC-12 ───────────────────────────
test('AC-12 · 树页签（BOM，__nodeId 口径）删除行为', async ({ page }) => {
  test.setTimeout(420_000);
  await uiLogin(page);
  await toStep2(page, REG_QID);

  // 首次进来先把产品加进去
  if (await page.locator('.qt-product-card').count() === 0) {
    await page.locator('button').filter({ hasText: /添加产品/ }).first().click();
    await page.waitForTimeout(2500);
    await page.locator('.ant-dropdown:visible .ant-dropdown-menu-item').filter({ hasText: /从已有产品添加/ }).first().click();
    await page.waitForTimeout(8000);
    const layer = page.locator('.ant-drawer, .ant-modal').first();
    await expect(layer, '弹层没打开 ⇒ 入口问题').toBeVisible({ timeout: 40_000 });
    await layer.locator('input').first().fill('R0910S3');
    await page.keyboard.press('Enter');
    await page.waitForTimeout(5000);
    const cand = layer.locator('.ant-table-row');
    expect(await cand.count(), '抽屉里搜不到 R0910S3-M1 ⇒ 夹具问题，判【未验证】').toBeGreaterThan(0);
    await cand.first().locator('input[type="checkbox"]').first().click();
    await page.waitForTimeout(1200);
    await layer.locator('button').filter({ hasText: /加\s*入\s*报\s*价\s*单/ }).last().click();
    await page.waitForTimeout(25_000);
    const save = page.locator('button').filter({ hasText: /^\s*保\s*存\s*(草稿)?\s*$/ }).first();
    if (await save.count() > 0) { await save.click(); await page.waitForTimeout(20_000); }
  }
  console.log('[页签] ' + JSON.stringify(await tabTexts(page)));
  await toTab(page, 'BOM');
  await page.waitForTimeout(3000);

  const liId = sqlRO(`SELECT id::text FROM quotation_line_item WHERE quotation_id='${REG_QID}' AND product_part_no_snapshot='${MAT}' LIMIT 1`);
  expect(liId, 'AC-12 前置：找不到明细行 ⇒ 判【未验证】').toMatch(/^[0-9a-f-]{36}$/);

  const hdr = await headers(page);
  const before = await rows(page);
  console.log('[AC-12 表头] ' + JSON.stringify(hdr));
  console.log('[AC-12 删前] ' + JSON.stringify(before));
  await shot(page, '60-AC12-树页签-删除前');
  expect(before.length, 'AC-12 前置：BOM 树页签渲染 <2 行，删不了也断不了 ⇒ 判【未验证】').toBeGreaterThanOrEqual(2);

  const delTombBefore = sqlRO(`SELECT COALESCE(deleted_tree_nodes::text,'∅') FROM quotation_line_item WHERE id='${liId}'`);
  const rowTombBefore = qlcd(liId, COMP_BOM, 'deleted_row_keys');

  let sent: any = null;
  page.on('request', (q) => {
    if (/delete-driver-row|delete-tree-node|tree/.test(q.url()) && q.method() === 'POST') {
      try { sent = { url: q.url(), body: q.postDataJSON() }; } catch { sent = { url: q.url(), body: q.postData() }; }
    }
  });
  const target = before[1];                       // 屏幕第 2 行（树上的一个子节点）
  // 🩹 harness 修复（首轮实证）：末列是「＋✕」两个按钮，`.first()` 点到的是**新增**，
  //    结果 0 请求、0 变化，报出来长得像「删除功能坏了」，实际是**我选错了按钮**。
  const delBtn = page.locator('.qt-cost-table tbody tr').nth(1).locator('td:last-child button')
    .filter({ hasText: '✕' }).first();
  await expect(delBtn, 'AC-12：第 2 行没有 ✕ 删除按钮 ⇒ 入口问题，判【未验证】').toBeVisible({ timeout: 20_000 });
  await delBtn.click();
  await page.waitForTimeout(4000);
  // 🩹 harness 修复（第 2 轮实证）：树删除走 `tree/delete-preview`，弹出的是**级联删除预览**
  //    对话框（.ant-modal，不是 .ant-modal-confirm / .ant-popconfirm）。上一轮选择器没命中 ⇒
  //    请求发出了、确认没点、**一行都没删**，报出来长得像「删除功能坏了」，实际仍是量具问题。
  await confirmAnyDialog(page);
  await page.waitForTimeout(8000);

  const after = await rows(page);
  await shot(page, '61-AC12-树页签-删除后');
  const key = (r: string[]) => JSON.stringify(r);
  const vanished = before.filter((r) => !after.map(key).includes(key(r)));

  let rep = `=== AC-12 树页签（BOM / __nodeId 口径）删除 · 改动后（8123/5233） ===\n`;
  rep += `报价单 QT-20260910-0830 明细行 ${liId}\n表头=${JSON.stringify(hdr)}\n\n`;
  before.forEach((r, i) => { rep += ` 删前第 ${i + 1} 行: ${JSON.stringify(r)}\n`; });
  rep += `\n点击的是**屏幕第 2 行**: ${JSON.stringify(target)}\n\n`;
  after.forEach((r, i) => { rep += ` 删后第 ${i + 1} 行: ${JSON.stringify(r)}\n`; });
  rep += `\n实际消失: ${JSON.stringify(vanished)}\n`;
  rep += `是否 = 被点那一行: ${vanished.length === 1 && key(vanished[0]) === key(target) ? '是' : '否'}\n`;
  rep += `\n发出的请求: ${JSON.stringify(sent, null, 2)}\n`;
  rep += `deleted_tree_nodes 前=${delTombBefore}\n`;
  rep += `deleted_tree_nodes 后=${sqlRO(`SELECT COALESCE(deleted_tree_nodes::text,'∅') FROM quotation_line_item WHERE id='${liId}'`)}\n`;
  rep += `BOM deleted_row_keys 前=${rowTombBefore}\n`;
  rep += `BOM deleted_row_keys 后=${qlcd(liId, COMP_BOM, 'deleted_row_keys')}\n`;
  ev('62-AC12-树删除实录.txt', rep);
  console.log(rep);

  expect(sent, 'AC-12：没抓到删除请求 ⇒ 量具问题，判【未验证】').not.toBeNull();
  expect(after.length, 'AC-12：删后行数应减少').toBeLessThan(before.length);
  expect(after.map(key), 'AC-12：树页签删掉的必须是被点的那一行').toEqual(before.filter((_, i) => i !== 1).map(key));
});

// ─────────────────────────── AC-13 ───────────────────────────
test('AC-13 · 手工新增行（_origin:manual）删除行为', async ({ page }) => {
  test.setTimeout(420_000);
  await uiLogin(page);
  await toStep2(page, REG_QID);
  await toTab(page, 'BOM');
  await page.waitForTimeout(3000);

  const liId = sqlRO(`SELECT id::text FROM quotation_line_item WHERE quotation_id='${REG_QID}' AND product_part_no_snapshot='${MAT}' LIMIT 1`);
  const before = await rows(page);
  console.log('[AC-13 新增前] ' + JSON.stringify(before));
  expect(before.length, 'AC-13 前置：页签 0 行 ⇒ 断言空跑').toBeGreaterThan(0);

  // 🩹 harness 修复：本页签没有全局「新增行」按钮，新增入口是**每行末列的「＋」**
  const addBtn = page.locator('.qt-cost-table tbody tr').last().locator('td:last-child button')
    .filter({ hasText: '＋' }).first();
  await expect(addBtn, 'AC-13：找不到行内「＋」新增入口 ⇒ 入口问题，判【未验证】').toBeVisible({ timeout: 30_000 });
  await addBtn.click();
  await page.waitForTimeout(6000);
  const added = await rows(page);
  console.log('[AC-13 新增后] ' + JSON.stringify(added));
  await shot(page, '70-AC13-手工行-新增后');
  expect(added.length, 'AC-13：点了「新增行」但行数没变 ⇒ 入口问题，判【未验证】').toBe(before.length + 1);

  // 给手工行填一个可识别值，确保「消失的是它」这件事可断言
  const lastTr = page.locator('.qt-cost-table tbody tr').nth(added.length - 1);
  const firstInput = lastTr.locator('input').first();
  if (await firstInput.count() > 0) { await firstInput.fill('R0910S3-MANUAL'); await firstInput.blur(); await page.waitForTimeout(2500); }
  const marked = await rows(page);
  console.log('[AC-13 打标后] ' + JSON.stringify(marked));

  let sent: any = null;
  page.on('request', (q) => { if (/delete-driver-row|delete-tree-node/.test(q.url()) && q.method() === 'POST') { try { sent = { url: q.url(), body: q.postDataJSON() }; } catch { sent = { url: q.url(), body: q.postData() }; } } });
  await page.locator('.qt-cost-table tbody tr').nth(marked.length - 1).locator('td:last-child button')
    .filter({ hasText: '✕' }).first().click();
  await page.waitForTimeout(4000);
  await confirmAnyDialog(page);
  await page.waitForTimeout(7000);
  const afterDel = await rows(page);
  await shot(page, '71-AC13-手工行-删除后');

  let rep = `=== AC-13 手工新增行删除 · 改动后（8123/5233） ===\n报价单 QT-20260910-0830 明细行 ${liId} 页签 BOM\n\n`;
  before.forEach((r, i) => { rep += ` 新增前第 ${i + 1} 行: ${JSON.stringify(r)}\n`; });
  rep += `\n`; marked.forEach((r, i) => { rep += ` 打标后第 ${i + 1} 行: ${JSON.stringify(r)}\n`; });
  rep += `\n`; afterDel.forEach((r, i) => { rep += ` 删手工行后第 ${i + 1} 行: ${JSON.stringify(r)}\n`; });
  rep += `\n发出的请求: ${JSON.stringify(sent, null, 2)}\n`;
  rep += `BOM deleted_row_keys = ${qlcd(liId, COMP_BOM, 'deleted_row_keys')}\n`;
  ev('72-AC13-手工行删除实录.txt', rep);
  console.log(rep);

  const key = (r: string[]) => JSON.stringify(r);
  expect(afterDel.length, 'AC-13：删手工行后应回到新增前的行数').toBe(before.length);
  expect(afterDel.map(key), 'AC-13：删手工行不得影响其它行（应逐字段等于新增前）').toEqual(before.map(key));
});
