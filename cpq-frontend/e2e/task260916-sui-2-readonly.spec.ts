/**
 * task-260916 · S-UI · 只读显示用例
 *   T-UI-09 = AC-12 明细 · T-UI-10 = AC-13 矩阵 · T-UI-11 = AC-14 元素编辑抽屉最新价
 *   T-UI-12 = AC-15 策略试算 · T-UI-13 = AC-16 调价页元素矩阵 + 版本明细 · T-UI-14 = AC-17 审核详情（P-6）
 *   T-UI-16 = AC-22 两个历史页（策略变更历史不崩溃 / 单价历史摘要不再「—」）
 *
 * SUI_PHASE=before（主仓 5174/8081、迁移前）：断言需求文档记载的「改前」值 ⇒ UI 层阳性对照；
 *   同时落盘 AC-16「涨跌」列、AC-17 金额类列的整表基线（after 阶段逐格比对）。
 * SUI_PHASE=after（worktree 临时前后端、迁移后）：断言 AC 值。
 *
 * 零写入：每个用例 blockWrites() —— 非 GET 请求除白名单外一律 abort 并记录，用例末断言记录为空。
 *   🚫 不点「立即生成一次」「通过」「驳回」「保存」；AC-14 只看不保存，关抽屉。
 */
import { test, expect, type Page } from '@playwright/test';
import * as H from './task260916-sui.helpers';

const P = H.PHASE;
const EXP = {
  ac12: { before: { Cu: '101.1392', Zn: '24.1695', Ni: '105' }, after: { Cu: '101.13921', Zn: '24.16947', Ni: '105' } },
  ac13: { before: { Cu: '101.14', Zn: '24.17' }, after: { Cu: '101.13921', Zn: '24.16947' } },
  ac14: { before: '101.1392', after: '101.13921' },
  ac15: { before: ['101.1392', '1.2', '50', '171.3671'], after: ['101.13921', '1.2', '50', '171.367052'] },
  ac16: { before: { matrixCu: '171.37', cur: '171.37', prev: '171.37' }, after: { matrixCu: '171.368', cur: '171.368', prev: '171.368' } },
  ac16Ag: '43358.75',
  ac17: { before: { prev: '171.37', cur: '171.37' }, after: { prev: '171.368', cur: '171.368' } },
};
const AMOUNT_COLS = ['对单价影响', '阈值', '报价·现', '报价·调整后', '核价·现', '核价·调整后', '现小计'];
const TEST_DAY = '2026-09-16';
const P6_ID = '32d0d398-692b-4b7d-ab4a-f4b64432d5b4';

// 不用 serial：before 阶段要尽量采全基线，一条失败不应跳过其余用例（beforeAll 只做只读核对，可重跑）。

let blocked: string[] = [];
test.beforeAll(async ({ browser }) => {
  H.ensureDirs();
  H.assertNoOtherPlaywright();
  const page = await browser.newPage();
  await H.assertEnvIdentity(page);
  await page.close();
});
test.beforeEach(async ({ page }) => {
  await H.apiLogin(page);
  blocked = await H.blockWrites(page, [/\/api\/cpq\/element-price\/strategies\/simulate$/]);
});
test.afterEach(async ({ page }, ti) => {
  await page.unrouteAll({ behavior: 'ignoreErrors' });
  H.info(ti, 'blocked-writes', blocked);
  expect(blocked, '只读用例不应发出任何写请求（已被拦截）').toEqual([]);
});

async function openPriceTable(page: Page, tab: '明细' | '矩阵' | '变更历史') {
  await H.openElementPage(page);
  await H.elementPriceMenu(page, '元素价格表');
  const d = H.drawer(page, /元素价格表/);
  await expect(d).toBeVisible();
  await d.locator('.ant-tabs-tab').filter({ hasText: tab }).first().click();
  return d;
}

test('T-UI-09 · AC-12 · 元素价格表「明细」长江有色网 2026-09-16', async ({ page }, ti) => {
  const d = await openPriceTable(page, '明细');
  const pane = d.locator('.ant-tabs-tabpane-active');
  await H.pickSelect(page, pane.locator('.ant-select:visible').first(), H.SRC_CJ, H.SRC_CJ);
  await H.setRange(pane.locator('.ant-picker-range').first(), TEST_DAY, TEST_DAY);
  await H.btn(pane, '查询').click();
  await page.waitForLoadState('networkidle').catch(() => {});
  const g = await H.readGrid(pane.locator('.ant-table-wrapper').first());
  const rows = g.rows.filter(r => H.col(r, '价格日期') === TEST_DAY);
  H.info(ti, 'AC-12 表头', g.headers);
  H.info(ti, 'AC-12 行', rows.map(r => r.byHeader));
  await H.shot(page, 'AC-12-明细');
  expect(rows.length, 'AC-12 2026-09-16 行非空').toBeGreaterThan(0);
  for (const [code, v] of Object.entries(EXP.ac12[P])) {
    const hit = rows.filter(r => H.isElem(H.col(r, '元素符号'), code) && H.col(r, '价格源') === H.SRC_CJ);
    expect(hit.length, `${code} 行应恰 1 行`).toBe(1);
    expect(H.col(hit[0], /^单价/), `${code} 单价`).toBe(v);
  }
});

test('T-UI-10 · AC-13 · 元素价格表「矩阵」Cu/Zn × 2026-09-16', async ({ page }, ti) => {
  const d = await openPriceTable(page, '矩阵');
  const pane = d.locator('.ant-tabs-tabpane-active');
  await H.pickSelect(page, pane.locator('.ant-select:visible').first(), H.SRC_CJ, H.SRC_CJ);
  await H.setRange(pane.locator('.ant-picker-range').first(), TEST_DAY, TEST_DAY);
  await H.btn(pane, '查询').click();
  // 日期列随查询结果动态生成：等表头出现日期列
  await expect.poll(async () => (await H.readGrid(pane.locator('.ant-table-wrapper').first())).headers.length, { timeout: 60_000 }).toBeGreaterThan(1);
  const g = await H.readGrid(pane.locator('.ant-table-wrapper').first());
  H.info(ti, 'AC-13 表头', g.headers);
  await H.shot(page, 'AC-13-矩阵');
  const dayHeader = g.headers.find(h => h.includes(TEST_DAY)) ?? g.headers.find(h => h.includes(TEST_DAY.slice(5)));
  expect(dayHeader, `矩阵应有 ${TEST_DAY} 列（表头=${JSON.stringify(g.headers)}）`).toBeTruthy();
  for (const [code, v] of Object.entries(EXP.ac13[P])) {
    const hit = g.rows.filter(r => H.isElem(r.cells[0] ?? '', code) || H.isElem(r.cells[1] ?? '', code));
    H.info(ti, `AC-13 ${code} 行`, hit.map(r => r.byHeader));
    expect(hit.length, `${code} 行应恰 1 行`).toBe(1);
    expect(hit[0].byHeader[dayHeader!], `${code} × ${TEST_DAY}`).toBe(v);
  }
});

test('T-UI-11 · AC-14 · 元素 Cu 编辑抽屉「各价格源最新价」（只看不保存）', async ({ page }, ti) => {
  await H.openElementPage(page);
  const search = page.getByPlaceholder(/搜索/).first();
  await search.fill('Cu');
  await page.waitForTimeout(800);
  const row = page.locator('.ant-table-tbody tr.ant-table-row').filter({ has: page.locator('td', { hasText: /^Cu$/ }) }).first();
  await expect(row, '列表应能找到 Cu 行').toBeVisible();
  await row.locator('input[type="checkbox"]').first().check();
  await H.btn(page, '编辑').click();
  const d = page.locator('.ant-drawer:visible').filter({ hasText: /编辑元素/ }).last();
  await expect(d).toBeVisible();
  const tbl = d.locator('.ant-table-wrapper').filter({ hasText: '价格源' }).first();
  await expect(tbl, '抽屉应有「各价格源最新价」表').toBeVisible();
  await page.waitForLoadState('networkidle').catch(() => {});
  const g = await H.readGrid(tbl);
  H.info(ti, 'AC-14 表头', g.headers);
  H.info(ti, 'AC-14 行', g.rows.map(r => r.byHeader));
  await H.shot(page, 'AC-14-元素编辑抽屉');
  const hit = g.rows.filter(r => H.col(r, '价格源').startsWith(H.SRC_CJ));
  expect(hit.length, '长江有色网行应恰 1 行').toBe(1);
  expect(H.col(hit[0], '最新价'), '长江有色网「最新价」').toBe(EXP.ac14[P]);
  // 只看不保存：关闭抽屉
  await d.locator('.ant-drawer-close').first().click();
  await expect(d).toBeHidden();
});

test('T-UI-12 · AC-15 · 罗克韦尔策略试算 基准日 2026-09-16（不保存）', async ({ page }, ti) => {
  await H.openPricingCustomerTab(page, H.CUST_ROCKWELL.name, '元素价格策略');
  await H.btn(page, '策略试算').click();
  const d = H.drawer(page, /试算/);
  await expect(d).toBeVisible();
  await H.setDate(d.locator('.ant-picker input').first(), TEST_DAY);
  await H.btn(d, '试算').click();
  await H.waitRows(d.locator('.ant-table-wrapper').first());
  const g = await H.readGrid(d.locator('.ant-table-wrapper').first());
  H.info(ti, 'AC-15 表头', g.headers);
  await H.shot(page, 'AC-15-策略试算');
  const hit = g.rows.filter(r => H.isElem(H.col(r, '元素'), 'Cu'));
  H.info(ti, 'AC-15 Cu 行', hit.map(r => r.byHeader));
  expect(hit.length, 'Cu 行应恰 1 行').toBe(1);
  const actual = [H.col(hit[0], '取值结果'), H.col(hit[0], /系数/), H.col(hit[0], /加价/), H.col(hit[0], '最终单价')];
  expect(actual, '取值结果 / × 系数 / + 加价 / 最终单价').toEqual(EXP.ac15[P]);
});

test('T-UI-13 · AC-16 · 罗克韦尔价格调整策略：元素矩阵 + 版本明细 V26090801', async ({ page }, ti) => {
  await H.openPricingCustomerTab(page, H.CUST_ROCKWELL.name, '价格调整策略');
  // ① 元素矩阵：列头含 V26090801
  const matrix = page.locator('.ant-table-wrapper:visible').filter({ has: page.locator('thead', { hasText: 'V26090801' }) }).first();
  await expect(matrix, '应有列头含 V26090801 的元素矩阵').toBeVisible({ timeout: 30_000 });
  const gm = await H.readGrid(matrix);
  const vHeader = gm.headers.find(h => h.includes('V26090801'))!;
  const cuM = gm.rows.filter(r => r.cells.slice(0, 3).some(c => H.isElem(c, 'Cu')));
  H.info(ti, 'AC-16 矩阵表头', gm.headers);
  H.info(ti, 'AC-16 矩阵 Cu 行', cuM.map(r => r.byHeader));
  await H.shot(page, 'AC-16-元素矩阵');
  expect(cuM.length, '矩阵 Cu 行应恰 1 行（若在第 2 页需翻页 → 判选择器问题再定）').toBe(1);
  // 格子可能同时显示单价与涨跌幅（api.md：pivot 单价 + 涨跌幅）⇒ 取格内第一个数字 token 作为单价，全文打印
  const cellText = cuM[0].byHeader[vHeader];
  const firstNum = (cellText.match(/-?\d[\d,]*(?:\.\d+)?/) || [''])[0];
  H.info(ti, 'AC-16 矩阵 Cu×V26090801 全文', cellText);
  expect(firstNum, 'Cu × V26090801 单价').toBe(EXP.ac16[P].matrixCu);

  // ② 版本轨迹 → V26090801 行「明细」
  const trail = page.locator('.ant-table-wrapper:visible').filter({ has: page.locator('tbody td', { hasText: /^V26090801$/ }) }).last();
  const trailRow = trail.locator('tbody tr.ant-table-row').filter({ has: page.locator('td', { hasText: /^V26090801$/ }) }).first();
  await expect(trailRow, '版本轨迹应有 V26090801 行').toBeVisible();
  const detailBtn = trailRow.locator('a, button').filter({ hasText: /明\s*细/ }).first();
  if (await detailBtn.count()) await detailBtn.click();
  else { await trailRow.locator('input[type="checkbox"]').first().check(); await H.btn(page, '明细').click(); }
  const d = H.drawer(page, /版本明细\s*·\s*V26090801/);
  await expect(d).toBeVisible();
  await H.waitRows(d.locator('.ant-table-wrapper').first());
  const gd = await H.readGrid(d.locator('.ant-table-wrapper').first());
  H.info(ti, 'AC-16 明细表头', gd.headers);
  H.info(ti, 'AC-16 明细行', gd.rows.map(r => r.byHeader));
  await H.shot(page, 'AC-16-版本明细');
  const cu = gd.rows.filter(r => H.isElem(H.col(r, '元素'), 'Cu'));
  const ag = gd.rows.filter(r => H.isElem(H.col(r, '元素'), 'Ag'));
  expect(cu.length, '明细 Cu 行恰 1').toBe(1);
  expect(ag.length, '明细 Ag 行恰 1').toBe(1);
  expect(H.col(cu[0], '本期价'), 'Cu 本期价').toBe(EXP.ac16[P].cur);
  expect(H.col(cu[0], '上期价'), 'Cu 上期价').toBe(EXP.ac16[P].prev);
  if (P === 'after') expect(H.col(ag[0], '本期价'), 'Ag 本期价').toBe(EXP.ac16Ag);
  // 「涨跌」列与合并前相同
  const trend = gd.rows.map(r => ({ el: H.col(r, '元素'), trend: H.col(r, /涨跌/) }));
  expect(trend.length).toBeGreaterThan(0);
  if (P === 'before') H.writeEvid('AC-16-涨跌-基线.json', trend);
  else {
    H.writeEvid('AC-16-涨跌-改后.json', trend);
    expect(trend, '「涨跌」列应与合并前逐格相同').toEqual(H.readBaseline('AC-16-涨跌-基线.json'));
  }
});

test('T-UI-14 · AC-17 · 价格调整审核详情 P-6 罗克韦尔 S-3120014539 已通过 2026-08-06 00:01（只看）', async ({ page }, ti) => {
  // P-6 前置（只读）：该审核单存在、已通过、对应 V26080505
  const p6 = H.sql(`SELECT r.status, v.version_no FROM material_price_review r JOIN element_price_version v ON v.id = r.version_id
                    WHERE r.id = '${P6_ID}'`);
  expect(p6, 'P-6 前置').toEqual([{ status: 'APPROVED', version_no: 'V26080505' }]);
  await H.gotoApp(page, '/pricing/reviews');
  // 实测（before 第 1 轮 dump）：列表列为「客户/料号/料号名称/当前版本 → 目标版本/依据单号·日期/…/审核状态」，
  // 不显示审核单创建时间 ⇒ P-6 按「罗克韦尔 + S-3120014539 + 目标版本 V26080505 + 已通过」定位（库中该组合唯一）；
  // 默认筛选为「待处理」，须把状态下拉切到「已通过」再查询。
  const findRow = () => page.locator('tbody tr.ant-table-row')
    .filter({ hasText: 'S-3120014539' }).filter({ hasText: H.CUST_ROCKWELL.name })
    .filter({ hasText: 'V26080505' }).filter({ hasText: '已通过' });
  if ((await findRow().count()) === 0) {
    // 选中后下拉文字会变，不能用「含待处理」做定位条件 ⇒ 先求出它在可见下拉中的序号再固定
    const sels = page.locator('.ant-select:visible');
    const texts = await sels.allInnerTexts();
    const idx = texts.findIndex(x => x.includes('待处理'));
    expect(idx, `审核列表应有状态下拉（当前「待处理」）；可见下拉文字=${JSON.stringify(texts)}`).toBeGreaterThanOrEqual(0);
    const st = sels.nth(idx);
    await H.pickSelect(page, st, '已通过', '已通过');
    // 实测：选中后列表自动刷新；「查询」在 DOM 里不是 <button>，按 .ant-btn / role=button 找，找不到就不点
    const kw = page.getByPlaceholder('搜索客户 / 料号 / 料号名称');
    if (await kw.count()) { await kw.fill('S-3120014539'); await kw.press('Enter'); }
    const q = page.locator('.ant-btn:visible, [role="button"]:visible').filter({ hasText: /^\s*查\s*询\s*$/ }).first();
    if (await q.count()) await q.click();
    await page.waitForLoadState('networkidle').catch(() => {});
    await expect.poll(async () => findRow().count(), { timeout: 30_000 }).toBeGreaterThan(0);
  }
  const n = await findRow().count();
  H.info(ti, 'AC-17 命中审核行数', n);
  if (n !== 1) await H.dump(page, 'AC-17-reviews-list', 'body');
  expect(n, '罗克韦尔 / S-3120014539 / 2026-08-06 00:01 / 已通过 应恰 1 行').toBe(1);
  const row = findRow().first();
  const link = row.locator('a').filter({ hasText: /详情|查看|S-3120014539/ }).first();
  if (await link.count()) await link.click();
  else { await row.locator('input[type="checkbox"]').first().check(); await H.btn(page, '详情').click(); }
  const d = page.locator('.ant-drawer:visible').last();
  await expect(d).toBeVisible();
  await page.waitForLoadState('networkidle').catch(() => {});
  await H.shot(page, 'AC-17-审核详情');
  // 元素表：含「本版价」列的表
  const elTbl = d.locator('.ant-table-wrapper').filter({ has: page.locator('thead', { hasText: '本版价' }) }).first();
  const ge = await H.readGrid(elTbl);
  H.info(ti, 'AC-17 元素表头', ge.headers);
  H.info(ti, 'AC-17 元素行', ge.rows.map(r => r.byHeader));
  const cu = ge.rows.filter(r => H.isElem(H.col(r, '元素'), 'Cu'));
  expect(cu.length, '元素表 Cu 行恰 1').toBe(1);
  expect(H.col(cu[0], '上版价'), 'Cu 上版价').toBe(EXP.ac17[P].prev);
  expect(H.col(cu[0], '本版价'), 'Cu 本版价').toBe(EXP.ac17[P].cur);

  // 金额类列：抽屉里所有表中，表头命中 AMOUNT_COLS 的列逐格取值（带行首列文字，便于人工对照）
  const tables = d.locator('.ant-table-wrapper');
  const amounts: Array<{ table: number; header: string; values: string[]; rowLabels: string[] }> = [];
  let totalRowNonEmpty = -1;
  for (let i = 0; i < await tables.count(); i++) {
    const g = await H.readGrid(tables.nth(i));
    const amountIdx = g.headers.map((h, hi) => (AMOUNT_COLS.some(a => h.includes(a)) ? hi : -1)).filter(x => x >= 0);
    if (!amountIdx.length) continue;
    for (const hi of amountIdx) {
      amounts.push({ table: i, header: g.headers[hi], values: g.rows.map(r => r.cells[hi] ?? ''), rowLabels: g.rows.map(r => r.cells.slice(0, 2).join(' | ')) });
    }
    const totalRows = g.rows.filter(r => r.cells.some(c => c.includes('产品总价')));
    if (totalRows.length) {
      totalRowNonEmpty = Math.max(totalRowNonEmpty, 0) + totalRows
        .flatMap(r => amountIdx.map(hi => r.cells[hi] ?? ''))
        .filter(v => v && v !== '—' && v !== '-').length;
    }
  }
  const fullText = (await d.allInnerTexts()).join('\n');
  H.info(ti, 'AC-17 金额列', amounts);
  H.info(ti, 'AC-17 产品总价行非空金额格数', totalRowNonEmpty);
  // 🚨 防空跑（AC-17 原文）：「产品总价」行至少一个非空金额格
  expect(amounts.length, `抽屉内应找到金额类列 ${AMOUNT_COLS.join('/')}`).toBeGreaterThan(0);
  expect(totalRowNonEmpty, '「产品总价」行应存在且至少有 1 个非空金额格').toBeGreaterThan(0);
  if (P === 'before') {
    H.writeEvid('AC-17-金额列-基线.json', amounts);
    H.writeEvid('AC-17-抽屉全文-基线.txt', fullText);
  } else {
    H.writeEvid('AC-17-金额列-改后.json', amounts);
    H.writeEvid('AC-17-抽屉全文-改后.txt', fullText);
    expect(amounts, '金额类列应与合并前逐格相同').toEqual(H.readBaseline('AC-17-金额列-基线.json'));
  }
  await d.locator('.ant-drawer-close').first().click();
});

test('T-UI-16 · AC-22 ① · 罗克韦尔「策略变更历史」不崩溃，Cu 例外新增摘要「系数：1.2」「加价：50」', async ({ page }, ti) => {
  // 前置（只读，AC-22 点名）：罗克韦尔 Cu 例外 2026-07-27 CREATE 日志存在
  const pre = H.sql(`SELECT action FROM element_price_strategy_log WHERE customer_no = 'CUST-0001' AND element_code = 'Cu' AND action = 'CREATE'`);
  expect(pre.length, 'AC-22 前置：罗克韦尔 Cu 例外新增日志应存在').toBeGreaterThan(0);
  const pageErrors: string[] = [];
  page.on('pageerror', e => pageErrors.push(String(e).slice(0, 300)));
  await H.openPricingCustomerTab(page, H.CUST_ROCKWELL.name, '元素价格策略');
  await H.btn(page, '变更历史').click();
  await page.waitForTimeout(3000);
  await page.waitForLoadState('networkidle').catch(() => {});
  await H.shot(page, 'AC-22-1-策略变更历史');
  const crashed = (await page.getByText('Unexpected Application Error').count()) > 0;
  const bodyText = (await page.locator('body').allInnerTexts()).join('\n');
  const trimErr = /value\.trim is not a function/.test(bodyText) || pageErrors.some(e => /trim is not a function/.test(e));
  H.info(ti, 'AC-22① 崩溃', { crashed, trimErr, pageErrors });
  if (P === 'before') {
    // 改前现象：整页崩溃 value.trim is not a function —— 记录，不作为本任务缺陷
    H.writeEvid('AC-22-1-改前现象.json', { crashed, trimErr, pageErrors, bodyHead: bodyText.slice(0, 1500) });
    expect(crashed && trimErr, 'AC-22① 改前现象应为整页崩溃 value.trim is not a function（不符则如实报告）').toBe(true);
    return;
  }
  expect(crashed, '改后不应出现 Unexpected Application Error').toBe(false);
  expect(trimErr, '改后不应出现 value.trim is not a function').toBe(false);
  const d = H.drawer(page, /变更历史/);
  await expect(d).toBeVisible();
  const g = await H.readGrid(d.locator('.ant-table-wrapper').first());
  H.writeEvid('AC-22-1-策略历史表.json', g);
  const objCol = g.headers.find(h => /对象|目标/.test(h))!;
  const opCol = g.headers.find(h => h === '操作' || h === '动作')!;
  const timeCol = g.headers.find(h => /时间/.test(h))!;
  const contentCol = g.headers.find(h => /内容/.test(h))!;
  expect([objCol, opCol, timeCol, contentCol].every(Boolean), `历史表列识别失败：${JSON.stringify(g.headers)}`).toBe(true);
  const hit = g.rows.filter(r => /Cu/.test(r.byHeader[objCol]) && /新建|新增/.test(r.byHeader[opCol]) && r.byHeader[timeCol].includes('2026-07-27'));
  H.info(ti, 'AC-22① Cu 新增记录', hit.map(r => r.byHeader));
  expect(hit.length, 'Cu 例外 2026-07-27 新增记录应恰 1 行（不在首页则需翻页 / 筛选变更对象 = Cu，执行时再定）').toBe(1);
  expect(hit[0].byHeader[contentCol]).toContain('系数：1.2');
  expect(hit[0].byHeader[contentCol]).toContain('加价：50');
});

test('T-UI-16 · AC-22 ② · 单价变更历史「白银 · 长江有色网 · 2026-08-30」新增摘要「单价 12345 CNY/kg」', async ({ page }, ti) => {
  // 前置（只读，AC-22 点名）：该条 CREATE 日志存在；取其操作时间以设定筛选范围
  const pre = H.sql<{ bj: string }>(`SELECT (changed_at AT TIME ZONE 'Asia/Shanghai')::date::text AS bj FROM element_daily_price_log l
    JOIN element_price_source s ON s.id = l.source_id
    WHERE l.element_name = '白银' AND l.price_date = DATE '2026-08-30' AND s.source_name = '${H.SRC_CJ}' AND l.action = 'CREATE'`);
  expect(pre.length, 'AC-22 前置：白银 · 长江有色网 · 2026-08-30 新增日志应恰 1 条').toBe(1);
  const opDay = pre[0].bj; // 实查 2026-08-30（北京时间）
  const d = await openPriceTable(page, '变更历史');
  const pane = d.locator('.ant-tabs-tabpane-active');
  await H.pickSelect(page, pane.locator('.ant-select:visible').first(), H.SRC_CJ, H.SRC_CJ);
  // 前后各放宽 1 天，规避服务端按哪个时区截日
  const shift = (s: string, n: number) => { const x = new Date(`${s}T12:00:00Z`); x.setUTCDate(x.getUTCDate() + n); return x.toISOString().slice(0, 10); };
  await H.setRange(pane.locator('.ant-picker-range').first(), shift(opDay, -1), shift(opDay, 1));
  const kw = pane.locator('input[placeholder*="元素"]:visible').first();
  if (await kw.count()) await kw.fill('白银');
  await H.btn(pane, '查询').click();
  await page.waitForLoadState('networkidle').catch(() => {});
  const g = await H.readGrid(pane.locator('.ant-table-wrapper').first());
  H.writeEvid(`AC-22-2-单价历史表.json`, g);
  await H.shot(page, 'AC-22-2-单价变更历史');
  const objCol = g.headers.find(h => /对象|目标/.test(h))!;
  const opCol = g.headers.find(h => h === '操作' || h === '动作')!;
  const contentCol = g.headers.find(h => /内容/.test(h))!;
  expect([objCol, opCol, contentCol].every(Boolean), `历史表列识别失败：${JSON.stringify(g.headers)}`).toBe(true);
  const hit = g.rows.filter(r => r.byHeader[objCol].includes('白银') && r.byHeader[objCol].includes(H.SRC_CJ)
    && r.byHeader[objCol].includes('2026-08-30') && /新建|新增/.test(r.byHeader[opCol]));
  H.info(ti, 'AC-22② 白银新增记录', hit.map(r => r.byHeader));
  expect(hit.length, '白银 · 长江有色网 · 2026-08-30 新增记录应恰 1 行').toBe(1);
  const content = hit[0].byHeader[contentCol].replace(/\s+/g, ' ');
  if (P === 'before') {
    H.writeEvid('AC-22-2-改前现象.txt', content);
    expect(content, 'AC-22② 改前现象应为「单价 — CNY/kg」（不符则如实报告）').toContain('单价 — CNY/kg');
  } else {
    expect(content).toContain('单价 12345 CNY/kg');
  }
});
