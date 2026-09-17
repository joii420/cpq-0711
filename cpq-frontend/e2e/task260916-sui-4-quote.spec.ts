/**
 * task-260916 · S-UI · T-UI-03 = AC-4（只在 after 阶段跑）
 *   admin 在报价单管理「导入报价数据」上传 tmp/报价 - 数据测试-施耐德.xlsx（客户正泰、模板施耐德5.4模板最新发布版）新建报价单
 *   → 打开该单 S3120011203 卡片「材料成本」页签 → Cu 行元素单价 101.13921、Zn 行 24.16947
 *   → 该单 ds_quote_element_bom_record 中 S3120011203 的 Cu = 101.13921、Zn = 24.16947
 *
 * 写面：新建 1 张正泰报价单（保留，不删，单号写进证据 AC-4-新单号.txt）。
 * ⚠️ 已知额外写面（已报主线待确认）：本导入会按正泰（CUST-0004）把施耐德文件写入 ds_quote_* 数据集表并留 import_record；
 *    开发库中该文件内容此前已导入过（ds_quote_element_bom 版本号 = 1），重导的升版行为以实现为准，执行后如实记录前后版本号。
 * 守卫：任何 URL 含 QT-20260916-0875~0881 的 id/单号的请求一律 abort；非 GET 请求全部记录进证据。
 * 夹具：证据/S-UI/fixtures/ac4-报价-数据测试-施耐德.xlsx（与 /home/joii/project/cpq/tmp 原件 md5 992911a11879b90753bc0cbced9249d7 一致）。
 */
import { test, expect } from '@playwright/test';
import * as path from 'path';
import * as fs from 'fs';
import * as crypto from 'crypto';
import * as H from './task260916-sui.helpers';

test.skip(H.PHASE !== 'after', 'AC-4 只在 after 阶段跑');
test.afterEach(async ({ page }) => { await page.unrouteAll({ behavior: 'ignoreErrors' }); });

const FILE = path.join(H.FIXTURE_DIR, 'ac4-报价-数据测试-施耐德.xlsx');
const FILE_MD5 = '992911a11879b90753bc0cbced9249d7';
const MN = 'S3120011203';
const TEMPLATE_NAME = '施耐德5.4模板';
const EXP = { Cu: '101.13921', Zn: '24.16947' };

function versionsSnapshot() {
  return H.sql(`SELECT material_no, element_code, version_no, customer_no FROM ds_quote_element_bom
                WHERE customer_no = 'CUST-0004' AND material_no = '${MN}' ORDER BY element_code, version_no`);
}

test('T-UI-03 · AC-4 · 导入报价数据新建正泰单 → 材料成本页签元素单价 9 位 + 本单元素快照 9 位', async ({ page }, ti) => {
  test.setTimeout(900_000);
  H.ensureDirs();
  H.assertNoOtherPlaywright();
  await H.assertEnvIdentity(page);
  const md5 = crypto.createHash('md5').update(fs.readFileSync(FILE)).digest('hex');
  expect(md5, '夹具文件应与 tmp 原件一致').toBe(FILE_MD5);

  // 前置：P-2（正泰仅默认策略、无版本指针）、P-9（模板最新发布版）
  const ptr = H.sql<{ c: number }>(`SELECT count(*) AS c FROM material_price_version_ref WHERE customer_no = 'CUST-0004'`);
  expect(Number(ptr[0].c), 'P-2：正泰不应有版本指针（否则走冻结价，AC-4 不成立）').toBe(0);
  const tpl = H.sql<{ id: string; version: string }>(`SELECT id::text, version FROM template
    WHERE name = '${TEMPLATE_NAME}' AND status = 'PUBLISHED' ORDER BY published_at DESC NULLS LAST, created_at DESC LIMIT 1`);
  expect(tpl.length, 'P-9：施耐德5.4模板应有已发布版').toBe(1);
  H.info(ti, 'AC-4 目标模板', tpl[0]);
  const verBefore = versionsSnapshot();
  const t0 = new Date().toISOString();

  // 守卫：禁止碰 7 张存量草稿；记录所有写请求
  const forbidIds = H.sql<{ id: string }>(`SELECT id::text FROM public.quotation WHERE quotation_number IN (${H.FORBIDDEN_QUOTES.map(q => `'${q}'`).join(',')})`).map(r => r.id);
  expect(forbidIds.length, '应取到全部禁开单据的 id 用于守卫').toBe(H.FORBIDDEN_QUOTES.length);
  // 导入前正泰已有单号集合（新单以差集为准，不以「最新一张」为准 —— 库里有他人新建的单）
  const chintNos = () => H.sql<{ n: string }>(`SELECT q.quotation_number AS n FROM public.quotation q
    JOIN public.customer c ON c.id = q.customer_id WHERE c.code = 'CUST-0004'`).map(r => r.n);
  const beforeNos = new Set(chintNos());
  H.writeEvid('AC-4-导入前正泰单号.json', [...beforeNos].sort());
  const writes: string[] = [];
  const hits: string[] = [];
  await H.apiLogin(page);
  await page.route('**/api/**', async (route) => {
    const req = route.request();
    const u = req.url() + ' ' + (req.postData() || '');
    if ([...forbidIds, ...H.FORBIDDEN_QUOTES].some(k => u.includes(k))) { hits.push(`${req.method()} ${req.url()}`); return route.abort('blockedbyclient'); }
    if (req.method() !== 'GET') writes.push(`${req.method()} ${req.url()}`);
    return route.continue();
  });

  // 复跑开关：SUI_AC4_QUOTATION=<本片此前已建出的单号> ⇒ 跳过导入建单（避免重复建单），只重做「打开卡片 → 读页签 → 查快照」
  const reuse = process.env.SUI_AC4_QUOTATION || '';
  let q: Array<{ id: string; customer_code: string; created_at: string; is_new: boolean }>;
  let qn: string | undefined;
  if (reuse) {
    expect(H.FORBIDDEN_QUOTES).not.toContain(reuse);
    q = H.sql(`SELECT q.id::text, c.code AS customer_code, q.created_at::text, true AS is_new
      FROM public.quotation q JOIN public.customer c ON c.id = q.customer_id WHERE q.quotation_number = '${reuse}'`);
    expect(q.length, `复用单 ${reuse} 应存在`).toBe(1);
    expect(q[0].customer_code).toBe('CUST-0004');
    qn = reuse;
    H.info(ti, 'AC-4 复用本片已建单', { qn, id: q[0].id });
    await H.gotoApp(page, `/quotations/${q[0].id}/edit`);
  } else {
  // 1. 报价单管理 → 导入报价数据
  await H.gotoApp(page, '/quotations');
  await page.locator('button:visible').filter({ hasText: '导入报价数据' }).first().click();
  const d = H.drawer(page, /导入报价数据/);
  await expect(d).toBeVisible();
  // 2. 选客户 正泰
  await H.pickSelect(page, H.formItem(d, /客户/).locator('.ant-select').first(), '正泰', /正泰/);
  // 3. 上传 → 开始导入
  await d.locator('input[type="file"]').first().setInputFiles(FILE);
  await expect(d.getByText(/数据测试-施耐德/)).toBeVisible();
  await H.shot(page, 'AC-4-step1-选客户上传');
  await d.locator('.ant-drawer-footer button').filter({ hasText: /开始导入/ }).first().click();
  // 4. 导入结果页（实测：显示「导入完成 · 共 N 行写入」+ 逐 Sheet 结果，footer「上一步 / 下一步」）→ 下一步 → Step 2
  await expect(d.getByText(/导入完成/), '导入应完成').toBeVisible({ timeout: 300_000 });
  H.writeEvid('AC-4-导入结果页.txt', (await d.allInnerTexts()).join('\n'));
  await H.shot(page, 'AC-4-step1b-导入结果');
  const nextBtn = d.locator('.ant-drawer-footer button').filter({ hasText: /下\s*一\s*步/ }).first();
  await expect(nextBtn).toBeEnabled({ timeout: 60_000 });
  await nextBtn.click();
  const createBtn = d.locator('.ant-drawer-footer button').filter({ hasText: /创建报价单|仍然创建/ }).first();
  await expect(createBtn, '导入完成后应进入 Step 2（选模板并建单）').toBeVisible({ timeout: 300_000 });
  await H.dump(page, 'AC-4-step2', '.ant-drawer:visible');
  // 5. 模板 = 施耐德5.4模板 最新发布版
  const tplSelect = H.formItem(d, /报价模板/).locator('.ant-select').first();
  const cur = (await tplSelect.innerText()).replace(/\s+/g, ' ');
  H.info(ti, 'AC-4 模板默认带出', cur);
  if (!(cur.includes(TEMPLATE_NAME) && cur.includes(tpl[0].version))) {
    await H.pickSelect(page, tplSelect, TEMPLATE_NAME, new RegExp(`${TEMPLATE_NAME}.*${tpl[0].version.replace('.', '\\.')}`));
  }
  await expect.poll(async () => (await tplSelect.innerText()).replace(/\s+/g, ' ')).toContain(tpl[0].version);
  await H.shot(page, 'AC-4-step2-选模板');
  // 6. 创建报价单
  await createBtn.click();
  await expect(d.getByText(/报价单已创建/)).toBeVisible({ timeout: 120_000 });
  const dText = (await d.allInnerTexts()).join('\n');
  qn = /QT-\d{8}-\d{4}/.exec(dText)?.[0];
  expect(qn, `抽屉应显示新单号；抽屉全文：${dText.slice(0, 500)}`).toBeTruthy();
  expect(H.FORBIDDEN_QUOTES).not.toContain(qn);
  const added = chintNos().filter(n => !beforeNos.has(n));
  H.info(ti, 'AC-4 正泰新增单号（差集）', added);
  expect(added, '导入建单后正泰应恰新增 1 张单，且就是抽屉显示的单号').toEqual([qn]);
  q = H.sql<{ id: string; customer_code: string; created_at: string; is_new: boolean }>(`
    SELECT q.id::text, c.code AS customer_code, q.created_at::text, q.created_at >= TIMESTAMPTZ '${t0}' AS is_new
    FROM public.quotation q JOIN public.customer c ON c.id = q.customer_id WHERE q.quotation_number = '${qn}'`);
  expect(q.length, `库中应有 ${qn}`).toBe(1);
  expect(q[0].customer_code, '新单客户应为正泰').toBe('CUST-0004');
  expect(q[0].is_new, `新单应为本次创建（created_at=${q[0].created_at}, t0=${t0}）`).toBe(true);
  H.writeEvid('AC-4-新单号.txt', `${qn}\n${q[0].id}\ncreated_at=${q[0].created_at}\ntemplate=${TEMPLATE_NAME} ${tpl[0].version} (${tpl[0].id})\n`);
  H.info(ti, 'AC-4 新单', { qn, id: q[0].id });

  // 7. 进入编辑页（物化完成后可进）
  // 原型：计算完成后自动进入编辑页；也可手点「进入编辑页」。两种都接受。
  const enter = d.locator('button').filter({ hasText: /进入编辑页/ }).first();
  await expect.poll(async () => page.url().includes(q[0].id) || (await enter.isEnabled().catch(() => false)),
    { timeout: 600_000, message: '物化完成后应自动进入或可点「进入编辑页」' }).toBe(true);
  if (!page.url().includes(q[0].id)) await enter.click();
  await page.waitForURL(new RegExp(q[0].id), { timeout: 60_000 });
  await page.waitForLoadState('networkidle').catch(() => {});
  }

  // 8. S3120011203 卡片 → 材料成本页签
  const card = page.locator('.qt-product-card, [class*="product-card"], .ant-card').filter({ hasText: MN }).first();
  if (!(await card.isVisible().catch(() => false))) {
    const next = page.getByRole('button', { name: /下一步/ }).first();
    if (await next.isVisible().catch(() => false)) await next.click();
  }
  await expect(card, `应有 ${MN} 产品卡片`).toBeVisible({ timeout: 120_000 });
  await card.locator('button.qt-tab-btn, .ant-tabs-tab, [role="tab"]').filter({ hasText: /^\s*材料成本\s*$/ }).first().click();
  await expect.poll(async () => card.locator('text=加载中').count(), { timeout: 120_000 }).toBe(0);
  let rows: H.GridRow[] = [];
  let headers: string[] = [];
  await expect.poll(async () => {
    rows = []; headers = [];
    // antd 固定表头时表头/表体是两张 <table>，须按外层 wrapper 读；卡片若是自绘表格则退回 <table>
    const wrappers = card.locator('.ant-table-wrapper:visible');
    const tables = (await wrappers.count()) ? wrappers : card.locator('table:visible');
    for (let i = 0; i < await tables.count(); i++) {
      const g = await H.readGrid(tables.nth(i));
      if (g.headers.includes('元素') && g.headers.some(h => h.includes('元素单价'))) { rows = g.rows; headers = g.headers; break; }
    }
    return rows.filter(r => ['Cu', 'Zn'].includes(H.col(r, '元素'))).length;
  }, { timeout: 120_000, message: '材料成本页签应渲染出 Cu / Zn 行' }).toBeGreaterThanOrEqual(2);
  H.writeEvid('AC-4-材料成本页签.json', { headers, rows: rows.map(r => r.byHeader) });
  await card.screenshot({ path: path.join(H.PHASE_DIR, 'AC-4-材料成本页签.png') });
  for (const [code, v] of Object.entries(EXP)) {
    const hit = rows.filter(r => H.col(r, '元素') === code);
    H.info(ti, `AC-4 ${code} 行元素单价`, hit.map(r => H.col(r, '元素单价')));
    expect(hit.length, `${code} 行非空`).toBeGreaterThan(0);
    for (const r of hit) expect(H.col(r, '元素单价'), `${code} 元素单价`).toBe(v);
  }

  // 9. 本单元素快照
  const snapSql = `SELECT r.material_no, r.material_part_no, r.element_code, r.element_price FROM ds_quote_element_bom_record r
    WHERE r.quotation_id = '${q[0].id}' AND r.material_no = '${MN}' ORDER BY r.element_code`;
  H.writeEvid('AC-4-SQL.txt', H.sqlText(snapSql));
  const snap = H.sql<{ element_code: string; element_price: string }>(
    `SELECT element_code, element_price::text AS element_price FROM (${snapSql}) t`);
  for (const [code, v] of Object.entries(EXP)) {
    const hit = snap.filter(r => r.element_code === code);
    expect(hit.length, `快照 ${code} 应恰 1 行（实际 ${JSON.stringify(snap)}）`).toBe(1);
    expect(H.decEq(hit[0].element_price, v), `快照 ${code} 实际=${hit[0].element_price} 期望=${v}`).toBe(true);
  }

  // 证据：写请求清单、守卫命中、数据集版本前后
  const verAfter = versionsSnapshot();
  H.writeEvid('AC-4-写请求与数据集版本.json', { writes, forbiddenHits: hits, verBefore, verAfter });
  expect(hits, '不应有请求触及 QT-20260916-0875~0881').toEqual([]);
});
