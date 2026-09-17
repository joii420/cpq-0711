/**
 * task-260916 · S-UI · 私有写面用例（只在 after 阶段跑）
 *   T-UI-06 = AC-7 导入写入与结果显示 · T-UI-07 = AC-8 舍入后为 0 判失败
 *   T-UI-08 = AC-9 手工录入输入框与落库 · T-UI-15 = AC-18 序列 6 步
 *
 * 写面（唯一）：价格源 TEST-PM-SRC-A × 2020-09-08 / 09 / 10 的日价（及其变更日志，日志不可删，报告里列为残留）。
 * beforeAll / afterAll 都清一次本写面（经 DELETE /api/cpq/element-price/prices/{id}，逐行核对日期后才删）。
 * 网络层：只放行 价格导入 / 新建价 / 改价 这三类写请求，其它写请求 abort 并判红（防误触别的保存）。
 * 夹具：证据/S-UI/fixtures/*.xlsx（由 测试草稿/S-UI/gen_fixtures.py 生成，数值单元格，格式同用户样例）。
 */
import { test, expect, type Page, type Locator } from '@playwright/test';
import * as path from 'path';
import * as H from './task260916-sui.helpers';

test.skip(H.PHASE !== 'after', '写用例只在 after 阶段跑');
// 不用 serial：AC-8/AC-9 失败不应连带跳过 AC-18。失败后 worker 重启会重跑 afterAll/beforeAll 清理，
// 所以 AC-18 自带前置补齐（缺 AC-7 数据时重导 F7，并在报告里注明）。

const D7 = '2020-09-09', D8 = '2020-09-08', D9 = '2020-09-10';
const F7 = path.join(H.FIXTURE_DIR, 'ac7-import-3rows.xlsx');
const F8 = path.join(H.FIXTURE_DIR, 'ac8-import-zero.xlsx');
const F18 = path.join(H.FIXTURE_DIR, 'ac18-import-cu.xlsx');
/** AC-18 第 1 步（D-11 修正后原文）：结果列显示「覆盖」。 */
const RESULT_UPDATED = '覆盖';

let blocked: string[] = [];
const ALLOW = [
  /\/api\/cpq\/element-price\/import$/,
  /\/api\/cpq\/element-price\/prices$/,          // POST 新建
  /\/api\/cpq\/element-price\/prices\/[0-9a-f-]{36}$/, // PUT 改价
];

test.beforeAll(async ({ browser }) => {
  H.ensureDirs();
  H.assertNoOtherPlaywright();
  const page = await browser.newPage();
  await H.assertEnvIdentity(page);
  // P-7 前置：源存在且启用、0 条策略引用
  const src = H.sql(`SELECT status FROM element_price_source WHERE id = '${H.SRC_A.id}' AND source_name = '${H.SRC_A.name}'`);
  expect(src, 'TEST-PM-SRC-A 应存在且启用').toEqual([{ status: 'ACTIVE' }]);
  const refs = H.sql<{ c: number }>(`SELECT count(*) AS c FROM element_price_strategy WHERE source_id = '${H.SRC_A.id}'`);
  expect(Number(refs[0].c), 'TEST-PM-SRC-A 不应被任何策略引用（否则写价会影响客户取价）⇒ 停下报主线').toBe(0);
  await H.cleanupMyPrices(page, 'pre');
  await page.close();
});
test.afterAll(async ({ browser }) => {
  const page = await browser.newPage();
  await H.apiLogin(page);
  await H.cleanupMyPrices(page, 'post');
  await page.close();
});
test.beforeEach(async ({ page }) => {
  await H.apiLogin(page);
  blocked = await H.blockWrites(page, ALLOW);
});
test.afterEach(async ({ page }, ti) => {
  await page.unrouteAll({ behavior: 'ignoreErrors' });
  H.info(ti, 'blocked-writes', blocked);
  expect(blocked, '不应发出白名单外的写请求').toEqual([]);
});

// ---------------------------------------------------------------- 页面动作
async function importPrices(page: Page, date: string, file: string, tag: string) {
  await H.openElementPage(page);
  await H.elementPriceMenu(page, '价格导入');
  const d = H.drawer(page, /价格导入/);
  await expect(d).toBeVisible();
  await H.pickSelect(page, H.fieldBox(d, '价格源').locator('.ant-select').first(), H.SRC_A.name, H.SRC_A.name);
  await H.setDate(H.fieldBox(d, '价格日期').locator('.ant-picker input').first(), date);
  await d.locator('input[type="file"]').first().setInputFiles(file);
  await expect(d.getByText(path.basename(file))).toBeVisible();
  // 有「预览」步骤就先点预览（以页面实际为准）
  const preview = H.btn(d, '预览');
  if (await preview.count()) await preview.click();
  const start = d.locator('.ant-drawer-footer button').filter({ hasText: /开始导入|确认导入/ }).first();
  await start.click();
  const tbl = d.locator('.ant-table-wrapper').filter({ has: page.locator('thead', { hasText: '结果' }) }).first();
  await expect(tbl, '导入后应出现结果表').toBeVisible({ timeout: 60_000 });
  const g = await H.readGrid(tbl);
  H.writeEvid(`${tag}-导入结果.json`, g);
  await H.shot(page, `${tag}-导入结果`);
  return { d, g };
}

async function openDetail(page: Page): Promise<Locator> {
  await H.openElementPage(page);
  await H.elementPriceMenu(page, '元素价格表');
  const d = H.drawer(page, /元素价格表/);
  await expect(d).toBeVisible();
  await d.locator('.ant-tabs-tab').filter({ hasText: '明细' }).first().click();
  return d;
}
async function queryDetail(page: Page, d: Locator, from: string, to: string) {
  const pane = d.locator('.ant-tabs-tabpane-active');
  await H.pickSelect(page, pane.locator('.ant-select:visible').first(), H.SRC_A.name, H.SRC_A.name);
  await H.setRange(pane.locator('.ant-picker-range').first(), from, to);
  await H.btn(pane, '查询').click();
  await page.waitForLoadState('networkidle').catch(() => {});
  const g = await H.readGrid(pane.locator('.ant-table-wrapper').first());
  return { pane, g };
}
/** 二级抽屉（新建 / 编辑价格）：按标题排除父抽屉「元素价格表」；否则子抽屉关闭后 last() 会落到父抽屉上。 */
const editDrawer = (page: Page) => page.locator('.ant-drawer:visible')
  .filter({ has: page.locator('.ant-drawer-title').filter({ hasNotText: '元素价格表' }) }).last();

function cuRowsOf(g: { rows: H.GridRow[] }, date: string) {
  return g.rows.filter(r => H.isElem(H.col(r, '元素符号'), 'Cu') && H.col(r, '价格日期') === date && H.col(r, '价格源') === H.SRC_A.name);
}
function dbPrice(code: string, date: string): string[] {
  return H.sql<{ p: string }>(`SELECT raw_price::text AS p FROM element_daily_price
    WHERE source_id = '${H.SRC_A.id}' AND price_date = DATE '${date}' AND element_name = '${code}'`).map(r => r.p);
}

// ---------------------------------------------------------------- T-UI-06 · AC-7
test('T-UI-06 · AC-7 · 导入 Cu 101.13921 / Zn 24.123456789 / Ni 2.0000000005', async ({ page }, ti) => {
  const { g } = await importPrices(page, D7, F7, 'AC-7');
  H.info(ti, 'AC-7 结果表', g.rows.map(r => r.byHeader));
  expect(g.rows.length, 'AC-7 结果表应恰 3 行').toBe(3);
  const exp = [['Cu', '101.13921'], ['Zn', '24.123456789'], ['Ni', '2.000000001']];
  exp.forEach(([code, price], i) => {
    const r = g.rows[i];
    expect(H.col(r, '元素符号'), `第 ${i + 1} 行元素`).toBe(code);
    expect(H.col(r, '结果'), `${code} 结果`).toBe('新增');
    expect(H.col(r, /^单价/), `${code} 单价`).toBe(price);
  });
  const txt = H.sqlText(`SELECT element_name, raw_price, fetch_status FROM element_daily_price
    WHERE source_id = '${H.SRC_A.id}' AND price_date = DATE '${D7}' ORDER BY element_name`);
  H.writeEvid('AC-7-SQL.txt', txt);
  for (const [code, price] of exp) {
    const p = dbPrice(code, D7);
    expect(p.length, `库中 ${code} 应恰 1 行`).toBe(1);
    expect(H.decEq(p[0], price), `库中 ${code} raw_price 实际=${p[0]} 期望=${price}`).toBe(true);
  }
});

// ---------------------------------------------------------------- T-UI-07 · AC-8
test('T-UI-07 · AC-8 · 导入 Cu 0.0000000004 → 失败「单价必须大于 0」且不落库', async ({ page }, ti) => {
  const { g } = await importPrices(page, D8, F8, 'AC-8');
  H.info(ti, 'AC-8 结果表', g.rows.map(r => r.byHeader));
  expect(g.rows.length, 'AC-8 结果表应恰 1 行').toBe(1);
  expect(H.col(g.rows[0], '元素符号')).toBe('Cu');
  expect(H.col(g.rows[0], '结果')).toBe('失败');
  expect(H.col(g.rows[0], '说明')).toBe('单价必须大于 0');
  H.writeEvid('AC-8-SQL.txt', H.sqlText(`SELECT count(*) AS cu_rows FROM element_daily_price
    WHERE source_id = '${H.SRC_A.id}' AND price_date = DATE '${D8}' AND element_name = 'Cu'`));
  expect(dbPrice('Cu', D8), '库中该源该日不应有 Cu 行').toEqual([]);
});

// ---------------------------------------------------------------- T-UI-08 · AC-9
/** 明细「新建」Cu / TEST-PM-SRC-A / 2020-09-10，单价 3.1234567891 → 失焦断言 → 填货币单位 → 保存。返回价格表抽屉。 */
async function createCuManually(page: Page, tag: string): Promise<Locator> {
  const d = await openDetail(page);
  const pane = d.locator('.ant-tabs-tabpane-active');
  await H.btn(pane, '新建').click();
  const ed = editDrawer(page);
  await expect(ed, '应弹出二级抽屉（新建价格）').toBeVisible();
  await H.pickSelect(page, H.formItem(ed, /^元素/).locator('.ant-select').first(), 'Cu', /^\s*Cu\b/);
  await H.pickSelect(page, H.formItem(ed, /价格源/).locator('.ant-select').first(), H.SRC_A.name, H.SRC_A.name);
  await H.setDate(H.formItem(ed, /价格日期/).locator('.ant-picker input').first(), D9);
  const priceInput = H.formItem(ed, /^单价/).locator('input').first();
  await priceInput.click();
  await priceInput.fill('3.1234567891');
  // 点其他输入框使其失焦
  await H.formItem(ed, /货币/).locator('input').first().click();
  await expect(priceInput, '失焦后单价输入框').toHaveValue('3.123456789');
  await H.shot(page, `${tag}-失焦后`);
  // 货币 CNY、计价单位 kg（输入框或下拉，按页面实际）
  for (const [label, val] of [[/货币/, 'CNY'], [/计价单位/, 'kg']] as const) {
    const fi = H.formItem(ed, label);
    if (await fi.locator('.ant-select').count()) await H.pickSelect(page, fi.locator('.ant-select').first(), val, val);
    else await fi.locator('input').first().fill(val);
  }
  await ed.locator('.ant-drawer-footer button').filter({ hasText: /保\s*存/ }).first().click();
  await expect(ed).toBeHidden({ timeout: 20_000 });
  return d;
}

test('T-UI-08 · AC-9 · 明细「新建」单价 3.1234567891 失焦 → 3.123456789 → 保存 → 列表与库', async ({ page }, ti) => {
  const d = await createCuManually(page, 'AC-9');
  const { g } = await queryDetail(page, d, D9, D9);
  const cu = cuRowsOf(g, D9);
  H.info(ti, 'AC-9 列表 Cu 行', cu.map(r => r.byHeader));
  await H.shot(page, 'AC-9-保存后列表');
  expect(cu.length, '列表应恰 1 行 Cu').toBe(1);
  expect(H.col(cu[0], /^单价/)).toBe('3.123456789');
  H.writeEvid('AC-9-SQL.txt', H.sqlText(`SELECT element_name, raw_price, currency, price_unit, fetch_status FROM element_daily_price
    WHERE source_id = '${H.SRC_A.id}' AND price_date = DATE '${D9}'`));
  const p = dbPrice('Cu', D9);
  expect(p.length).toBe(1);
  expect(H.decEq(p[0], '3.123456789'), `库中 raw_price 实际=${p[0]}`).toBe(true);
});

// ---------------------------------------------------------------- T-UI-15 · AC-18（D-11 修正版）
test('T-UI-15 · AC-18 · 导入覆盖 → 手工改 → 看历史 → 关开抽屉 → 刷新', async ({ page }, ti) => {
  // 前置：AC-7（2020-09-09 Cu 101.13921，导入产生）、AC-9（2020-09-10 Cu 3.123456789，手工新建产生）。
  // 缺失时（清理或 worker 重启所致）就地补齐，并在证据里注明。
  if (dbPrice('Cu', D7).length === 0) {
    H.info(ti, 'step0 前置补齐 AC-7', '2020-09-09 Cu 不在 ⇒ 重导 F7');
    const { d: d0 } = await importPrices(page, D7, F7, 'AC-18-step0-AC7');
    await d0.locator('.ant-drawer-close').first().click();
  }
  if (dbPrice('Cu', D9).length === 0) {
    H.info(ti, 'step0 前置补齐 AC-9', '2020-09-10 Cu 不在 ⇒ 按 AC-9 手工新建');
    const d0 = await createCuManually(page, 'AC-18-step0-AC9');
    await d0.locator('.ant-drawer-close').first().click();
  }
  const pre7 = dbPrice('Cu', D7), pre9 = dbPrice('Cu', D9);
  H.info(ti, 'step0 前置', { pre7, pre9 });
  expect(pre7.length === 1 && H.decEq(pre7[0], '101.13921'), `前置：${D7} Cu 应为 101.13921（实际 ${JSON.stringify(pre7)}）`).toBe(true);
  expect(pre9.length === 1 && H.decEq(pre9[0], '3.123456789'), `前置：${D9} Cu 应为 3.123456789（实际 ${JSON.stringify(pre9)}）`).toBe(true);

  const both = (g: { rows: H.GridRow[] }) => ({ d7: cuRowsOf(g, D7), d9: cuRowsOf(g, D9) });

  // 1. 同源同日（2020-09-09）再导入 Cu 101.13922 → 结果「覆盖」+ 说明
  {
    const { g, d } = await importPrices(page, D7, F18, 'AC-18-step1');
    H.info(ti, 'step1 结果表', g.rows.map(r => r.byHeader));
    expect(g.rows.length).toBe(1);
    expect(H.col(g.rows[0], '结果'), 'step1 结果').toBe(RESULT_UPDATED);
    expect(H.col(g.rows[0], '说明'), 'step1 说明').toBe('原值 101.13921 → 新值 101.13922');
    expect(H.decEq(dbPrice('Cu', D7)[0], '101.13922'), 'step1 库值').toBe(true);
    await d.locator('.ant-drawer-close').first().click();
  }
  // 2. 明细查该源，日期范围含 2020-09-09 与 2020-09-10 → 09-09 Cu 101.13922
  const d = await openDetail(page);
  {
    const { g } = await queryDetail(page, d, D7, D9);
    const { d7 } = both(g);
    H.info(ti, 'step2 09-09 Cu', d7.map(r => r.byHeader));
    await H.shot(page, 'AC-18-step2');
    expect(d7.length).toBe(1);
    expect(H.col(d7[0], /^单价/), 'step2 09-09 单价').toBe('101.13922');
  }
  // 3. 选中 2020-09-10 Cu 行「编辑」→ 回显 3.123456789 → 改 3.123456788 → 保存 → 列表 3.123456788
  {
    const pane = d.locator('.ant-tabs-tabpane-active');
    const row = pane.locator('tbody tr.ant-table-row')
      .filter({ has: page.locator('td', { hasText: /^Cu$/ }) }).filter({ hasText: D9 }).filter({ hasText: H.SRC_A.name });
    await expect(row).toHaveCount(1);
    await row.locator('input[type="checkbox"]').first().check();
    await H.btn(pane, '编辑').click();
    const ed = editDrawer(page);
    await expect(ed).toBeVisible();
    const priceInput = H.formItem(ed, /^单价/).locator('input').first();
    await expect(priceInput, 'step3 编辑态回显').toHaveValue('3.123456789');
    await priceInput.fill('3.123456788');
    await H.formItem(ed, /货币/).locator('input').first().click();
    await expect(priceInput).toHaveValue('3.123456788');
    await ed.locator('.ant-drawer-footer button').filter({ hasText: /保\s*存/ }).first().click();
    await expect(ed).toBeHidden({ timeout: 20_000 });
    const { g } = await queryDetail(page, d, D7, D9);
    const { d9 } = both(g);
    await H.shot(page, 'AC-18-step3');
    expect(d9.length).toBe(1);
    expect(H.col(d9[0], /^单价/), 'step3 09-10 单价').toBe('3.123456788');
    expect(H.decEq(dbPrice('Cu', D9)[0], '3.123456788'), 'step3 库值').toBe(true);
  }
  // 4. 变更历史：源 = TEST-PM-SRC-A，日期范围保持默认（按操作时间）
  {
    await d.locator('.ant-tabs-tab').filter({ hasText: '变更历史' }).first().click();
    const pane = d.locator('.ant-tabs-tabpane-active');
    await H.pickSelect(page, pane.locator('.ant-select:visible').first(), H.SRC_A.name, H.SRC_A.name);
    await H.btn(pane, '查询').click();
    await page.waitForLoadState('networkidle').catch(() => {});
    const g = await H.readGrid(pane.locator('.ant-table-wrapper').first());
    H.writeEvid('AC-18-step4-历史表.json', g);
    await H.shot(page, 'AC-18-step4');
    const objCol = g.headers.find(h => /对象|目标/.test(h));
    const opCol = g.headers.find(h => h === '操作' || h === '动作');
    const contentCol = g.headers.find(h => /内容/.test(h));
    const timeCol = g.headers.find(h => /时间/.test(h));
    expect([objCol, opCol, contentCol, timeCol].every(Boolean), `历史表列识别失败：${JSON.stringify(g.headers)}`).toBe(true);
    // 对象含「Cu」「TEST-PM-SRC-A」「2020-09-10」三段分别匹配（主线确认可以）；按时间倒序，同分钟保持表内原顺序（稳定排序）
    const mine = g.rows
      .filter(r => /^Cu\b/.test(r.byHeader[objCol!]) && r.byHeader[objCol!].includes(H.SRC_A.name) && r.byHeader[objCol!].includes(D9))
      .sort((a, b) => b.byHeader[timeCol!].localeCompare(a.byHeader[timeCol!]));
    H.info(ti, 'step4 本对象记录', mine.map(r => r.byHeader));
    expect(mine.length, 'step4 本对象历史非空').toBeGreaterThan(0);
    const priceChange = (s: string) => (/单价[：:]?\s*([\d.]+)\s*→\s*([\d.]+)/.exec(s) || []).slice(1).join(' → ');
    const updates = mine.filter(r => /修改|更新/.test(r.byHeader[opCol!]));
    expect(updates.length, 'step4 至少 1 条修改记录').toBeGreaterThanOrEqual(1);
    expect(priceChange(updates[0].byHeader[contentCol!]), '最近一条修改记录的单价变更').toBe('3.123456789 → 3.123456788');
    const creates = mine.filter(r => /新建|新增/.test(r.byHeader[opCol!]));
    expect(creates.length, 'step4 应有新增记录').toBeGreaterThan(0);
    const cText = creates[0].byHeader[contentCol!];
    H.info(ti, 'step4 新增记录摘要', cText);
    expect(cText, '新增记录摘要单价（依赖 B-11；改前为「—」）').toMatch(/单价[^\d—]*3\.123456789(?!\d)/);
  }
  // 5 / 6. 关开抽屉 → 明细重新查询；刷新后再做一次
  for (const [step, reload] of [['step5', false], ['step6', true]] as const) {
    if (reload) { await page.reload(); await page.waitForLoadState('networkidle').catch(() => {}); }
    else { await d.locator('.ant-drawer-close').first().click(); await expect(d).toBeHidden(); }
    const dx = await openDetail(page);
    const { g } = await queryDetail(page, dx, D7, D9);
    const { d7, d9 } = both(g);
    await H.shot(page, `AC-18-${step}`);
    expect(d7.length, `${step} 09-09 Cu 行`).toBe(1);
    expect(d9.length, `${step} 09-10 Cu 行`).toBe(1);
    expect(H.col(d7[0], /^单价/), `${step} 09-09 单价`).toBe('101.13922');
    expect(H.col(d9[0], /^单价/), `${step} 09-10 单价`).toBe('3.123456788');
    if (!reload) await dx.locator('.ant-drawer-close').first().click();
  }
});
