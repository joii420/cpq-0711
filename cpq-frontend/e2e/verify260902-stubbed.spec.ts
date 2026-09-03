/**
 * task-260902 · 前端渲染取证（前端工程师自检用，**非正式测试用例**）
 *
 * 🚨 本用例用 `page.route` 按 `api.md` 的响应形状**打桩后端**：
 *    共享 8081 跑的是主工作区后端，`/api/cpq/dataset/*` 尚未部署（实测 404），
 *    因此这里验的是「**前端拿到契约形状的响应后渲染成什么样**」，
 *    **不构成与真实后端的联调证据** —— 联调仍需后端合并后重跑。
 *
 * 响应包按 2026-09-03 更正后的真实结构 `{ code, message, data }`（**无 success 字段**）。
 */
import { test, expect, Page } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';

const ok = (data: unknown) => ({ code: 200, message: 'success', data });

const SHEETS = [
  ['MATERIAL_BOM', '物料BOM'], ['ELEMENT_BOM', '物料与元素BOM'], ['IN_PROCESS_FEE', '来料加工费'],
  ['IN_OTHER_FEE', '来料其他费用'], ['IN_OTHER_FIXED', '来料其他固定费用'], ['PROCESS_ASSY', '加工费&组装费'],
  ['OUT_PROCESS', '其他外加工成本'], ['FG_RATIO_FEE', '成品其他比例费用'], ['FG_FIXED_FEE', '成品其他固定费用'],
];

/** 原型「核价数据-抽屉」的物料BOM 列（🔗 = compared） */
const BOM_COLUMNS = [
  { name: 'item_seq', label: '项次', role: 'VALUE', type: 'NUMBER', editable: true, compared: false },
  { name: 'comp_type', label: '组成类型', role: 'VALUE', type: 'STRING', editable: true, compared: true,
    dropdown: { kind: 'ENUM', options: ['料件', '元素'] } },
  { name: 'component_no', label: '组成料号', role: 'SUBDIM', type: 'STRING', editable: true, compared: true,
    dropdown: { kind: 'MASTER', masterType: 'material', nameColumn: 'component_name' } },
  { name: 'component_name', label: '组成料号名称', role: 'NAME', editable: false },
  { name: 'process_no', label: '工序编号', role: 'VALUE', type: 'STRING', editable: true, compared: true },
  { name: 'use_feature', label: '使用特性', role: 'VALUE', type: 'STRING', editable: true, compared: true },
  { name: 'component_qty', label: '组成用量', role: 'VALUE', type: 'DECIMAL', editable: true, compared: true },
  { name: 'unit', label: '单位', role: 'VALUE', type: 'STRING', editable: true, compared: true },
  { name: 'base_qty', label: '底数', role: 'VALUE', type: 'DECIMAL', editable: true, compared: true },
  { name: 'loss_rate', label: '损耗率%', role: 'VALUE', type: 'DECIMAL', editable: true, compared: true },
];

/** 原型左侧 tab 徽标：前 6 个有版本，后 3 个 `—` */
const OVERVIEW_SHEETS = [
  { sheetKey: 'MATERIAL_BOM', rowCount: 7, versionNo: 3, source: 'IMPORT' },
  { sheetKey: 'ELEMENT_BOM', rowCount: 4, versionNo: 1, source: 'IMPORT' },
  { sheetKey: 'IN_PROCESS_FEE', rowCount: 3, versionNo: 2, source: 'MANUAL' },
  { sheetKey: 'IN_OTHER_FEE', rowCount: 2, versionNo: 1, source: 'IMPORT' },
  { sheetKey: 'IN_OTHER_FIXED', rowCount: 1, versionNo: 1, source: 'IMPORT' },
  { sheetKey: 'PROCESS_ASSY', rowCount: 5, versionNo: 4, source: 'MANUAL' },
  { sheetKey: 'OUT_PROCESS', rowCount: 0, versionNo: null },
  { sheetKey: 'FG_RATIO_FEE', rowCount: 0, versionNo: null },
  { sheetKey: 'FG_FIXED_FEE', rowCount: 0, versionNo: null },
];

const ROWS_V3 = [
  { item_seq: 10, comp_type: '料件', component_no: 'S-2120011658', component_name: '半成品A',
    process_no: null, use_feature: '0:必要', component_qty: '1.000000000000', unit: 'PCS',
    base_qty: '1', loss_rate: '0', row_fingerprint: 'aaa' },
  { item_seq: 20, comp_type: '料件', component_no: 'S-2120033401', component_name: '外购件-弹簧',
    process_no: 'Z053', use_feature: '0:必要', component_qty: '2', unit: 'PCS',
    base_qty: '1', loss_rate: '1.5', row_fingerprint: 'bbb' },
  { item_seq: 30, comp_type: '料件', component_no: '00168', component_name: 'AgNi11#-Ⅰ',
    process_no: 'Z002', use_feature: '0:必要', component_qty: '41.138100000000', unit: 'g',
    base_qty: '1', loss_rate: '0', row_fingerprint: 'ccc' },
];

type SaveMode = 'UPGRADED' | 'UNCHANGED' | 'CONFLICT';
let saveMode: SaveMode = 'UPGRADED';
let importMode: 'OK' | 'BAD' = 'OK';

async function stub(page: Page) {
  await page.route('**/api/cpq/dataset/**', async (route) => {
    const url = new URL(route.request().url());
    const p = url.pathname;
    const method = route.request().method();

    if (method === 'PUT' && p.endsWith('/rows')) {
      if (saveMode === 'CONFLICT') {
        return route.fulfill({ status: 409, contentType: 'application/json',
          body: JSON.stringify({ code: 409, message: '数据已被他人更新至 v5，请刷新后重试',
                                 data: { currentVersion: 5, baseVersion: 3 } }) });
      }
      if (saveMode === 'UNCHANGED') {
        return route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify(ok({ result: 'UNCHANGED', versionNo: 3, rowCount: 3 })) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify(ok({ result: 'UPGRADED', versionNo: 4, rowCount: 3, message: '已升版至 v4' })) });
    }

    if (method === 'POST' && p.endsWith('/import')) {
      if (importMode === 'BAD') {
        return route.fulfill({ status: 400, contentType: 'application/json',
          body: JSON.stringify({ code: 400, message: '导入校验未通过，共 4 处问题，本次未写入任何数据', data: { errors: [
            { sheet: '产能', row: 1, column: '-', reason: 'sheet「产能」不属于基础核价数据集' },
            { sheet: '物料BOM', row: 3, column: '组成料号', reason: '必填项为空' },
            { sheet: '物料与元素BOM', row: 3, column: '元素代码', reason: '主数据不存在' },
            { sheet: '来料加工费', row: 3, column: '加工费', reason: '不是合法数值' },
          ] } }) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify(ok({ dataset: 'cost-basic', fileName: 'x.xlsx', durationMs: 4210, summary: [
          { sheet: '物料', versioned: false, inserted: 1, updated: 0 },
          { sheet: '物料BOM', versioned: true, axisCount: 1, created: 0, upgraded: 1, unchanged: 0 },
          { sheet: '成品其他固定费用', versioned: true, axisCount: 0, created: 0, upgraded: 0, unchanged: 0 },
        ] })) });
    }

    if (p.endsWith('/sheets')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ok({
        sheets: SHEETS.map(([k, n], i) => ({ sheetKey: k, sheetName: n, sortOrder: i + 1,
          axisColumn: 'production_no', axisLabel: '生产料号',
          columns: k === 'MATERIAL_BOM' ? BOM_COLUMNS : [
            { name: 'item_seq', label: '项次', role: 'VALUE', type: 'NUMBER', editable: true, compared: true },
            { name: 'fee', label: '费用', role: 'VALUE', type: 'DECIMAL', editable: true, compared: true },
          ] })) })) });
    }
    if (p.endsWith('/overview')) {
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify(ok({ axisValue: '3120014539', materialName: '主料1', sheets: OVERVIEW_SHEETS })) });
    }
    if (p.endsWith('/versions')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ok({ versions: [
        { versionNo: 3, isLatest: true, rowCount: 3, archivedAt: null, updatedAt: '2026-09-03T10:12:00+08:00', updatedBy: 'admin', source: 'IMPORT' },
        { versionNo: 2, isLatest: false, rowCount: 3, archivedAt: '2026-09-02T21:40:00+08:00', archivedBy: 'admin', archiveReason: 'MANUAL_UPGRADE' },
        { versionNo: 1, isLatest: false, rowCount: 2, archivedAt: '2026-09-01T14:05:00+08:00', archivedBy: 'admin', archiveReason: 'IMPORT_UPGRADE' },
      ] })) });
    }
    if (p.endsWith('/rows')) {
      const v = url.searchParams.get('version');
      if (v === '1') {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ok({
          versionNo: 1, isLatest: false, readOnly: true, source: 'IMPORT', rows: ROWS_V3.slice(0, 2) })) });
      }
      const sheetKey = p.split('/sheets/')[1]?.split('/')[0];
      const ovs = OVERVIEW_SHEETS.find((o) => o.sheetKey === sheetKey);
      if (ovs && ovs.versionNo === null) {
        return route.fulfill({ status: 200, contentType: 'application/json',
          body: JSON.stringify(ok({ versionNo: null, isLatest: true, readOnly: false, source: null, rows: [] })) });
      }
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ok({
        versionNo: 3, isLatest: true, readOnly: false, source: 'IMPORT', rows: ROWS_V3 })) });
    }
    if (p.endsWith('/parts')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ok({
        total: 4,
        items: [
          { axisValue: '3120014539', materialName: '主料1', specification: null, dimension: '3.5×3.5×0.6',
            oldMaterialNo: '8DLX.550.653', unitWeight: null, configuredCount: 6, totalSheetCount: 9,
            lastUpdatedAt: '2026-09-03T10:12:41+08:00' },
          { axisValue: '2120011658', materialName: '半成品A', specification: 'φ8×120', dimension: '8×8×1.2',
            oldMaterialNo: null, unitWeight: null, configuredCount: 9, totalSheetCount: 9,
            lastUpdatedAt: '2026-09-03T09:48:07+08:00' },
          { axisValue: '3120019902', materialName: '触点组件-银镍复合型双面焊接式', specification: 'AgNi11#-Ⅰ / 线材',
            dimension: '12.5×12.5×0.85', oldMaterialNo: '8DLX.550.981', unitWeight: null,
            configuredCount: 2, totalSheetCount: 9, lastUpdatedAt: '2026-09-02T22:03:55+08:00' },
          { axisValue: '2120033401', materialName: '外购件-弹簧', specification: null, dimension: null,
            oldMaterialNo: null, unitWeight: null, configuredCount: 0, totalSheetCount: 9, lastUpdatedAt: null },
        ] })) });
    }
    if (p.includes('/lookup/')) {
      return route.fulfill({ status: 200, contentType: 'application/json',
        body: JSON.stringify(ok({ items: [{ code: 'Z053', name: '铣割' }] })) });
    }
    return route.continue();
  });
}

async function openBasicTab(page: Page) {
  await loginAsAdmin(page);
  await stub(page);
  await page.goto('/master-data-hub');
  await page.waitForSelector('.ant-tabs-nav .ant-tabs-tab', { timeout: 60_000 });
  await page.locator('.ant-tabs-nav .ant-tabs-tab', { hasText: '基础核价' }).first().click();
  await page.waitForTimeout(1500);
}

async function openDrawer(page: Page, axis = '3120014539') {
  await page.locator('.ant-table-tbody tr', { hasText: axis }).first().locator('td').nth(1).click();
  await page.waitForSelector('.ant-drawer', { timeout: 15_000 });
  await page.waitForTimeout(2000);
}

test('AC-25/26/27/28/29 · 列表 + 抽屉 + 保存三态 + 历史只读', async ({ page }) => {
  test.setTimeout(180_000);
  const errs: string[] = [];
  page.on('pageerror', (e) => errs.push(String(e).slice(0, 200)));
  await openBasicTab(page);

  // ── AC-25 列表 ──
  const rows = page.locator('.ant-table-tbody tr.ant-table-row');
  console.log('AC25_ROW_COUNT =', await rows.count());
  console.log('AC25_ROW1 =', JSON.stringify((await rows.nth(0).innerText()).replace(/\s+/g, ' ').trim()));
  console.log('AC25_ROW3_EXTREME =', JSON.stringify((await rows.nth(2).innerText()).replace(/\s+/g, ' ').trim()));
  console.log('AC25_BADGES =', JSON.stringify(await page.locator('.ant-table-tbody .ant-tag').allInnerTexts()));
  await page.screenshot({ path: 'e2e/screenshots/stub-01-list.png', fullPage: true });

  // ── AC-26 抽屉 9 个 tab ──
  await openDrawer(page);
  console.log('AC26_DRAWER_TITLE =', JSON.stringify((await page.locator('.ant-drawer-title').innerText()).replace(/\s+/g, ' ').trim()));
  const tabs = await page.locator('.ant-drawer .ant-tabs-left .ant-tabs-tab').allInnerTexts();
  console.log('AC26_TAB_COUNT =', tabs.length);
  console.log('AC26_TABS =', JSON.stringify(tabs.map((t) => t.replace(/\s+/g, ' ').trim())));
  const th = await page.locator('.ant-drawer .ant-tabs-tabpane-active .ant-table-thead th').allInnerTexts();
  console.log('AC26_TABLE_HEADERS =', JSON.stringify(th.map((h) => h.trim())));
  const toolbar = (await page.locator('.ant-drawer .ant-tabs-tabpane-active').innerText()).replace(/\s+/g, ' ').trim();
  console.log('AC26_PANEL_TEXT =', JSON.stringify(toolbar.slice(0, 260)));
  await page.screenshot({ path: 'e2e/screenshots/stub-02-drawer.png', fullPage: true });

  // ── AC-28 无变化 ──
  saveMode = 'UNCHANGED';
  await page.locator('.ant-drawer button', { hasText: '保存' }).first().click();
  await page.waitForTimeout(1500);
  console.log('AC28_TOAST =', JSON.stringify((await page.locator('.ant-message').innerText().catch(() => '')).replace(/\s+/g, ' ').trim()));
  await page.waitForTimeout(3500);

  // ── AC-27 升版 ──
  saveMode = 'UPGRADED';
  await page.locator('.ant-drawer button', { hasText: '保存' }).first().click();
  await page.waitForTimeout(1500);
  console.log('AC27_TOAST =', JSON.stringify((await page.locator('.ant-message').innerText().catch(() => '')).replace(/\s+/g, ' ').trim()));
  await page.waitForTimeout(3500);

  // ── AC-29 历史版本只读 ──
  await page.locator('.ant-drawer .ant-tabs-tabpane-active .ant-select').first().click();
  await page.waitForTimeout(800);
  await page.locator('.ant-select-item-option', { hasText: 'v1' }).first().click();
  await page.waitForTimeout(2000);
  const saveBtn = page.locator('.ant-drawer .ant-tabs-tabpane-active button', { hasText: '保存' }).first();
  const addBtn = page.locator('.ant-drawer .ant-tabs-tabpane-active button', { hasText: '新增行' }).first();
  console.log('AC29_SAVE_DISABLED =', await saveBtn.isDisabled(), '| TITLE =', JSON.stringify(await saveBtn.getAttribute('title')));
  console.log('AC29_ADD_DISABLED  =', await addBtn.isDisabled(), '| TITLE =', JSON.stringify(await addBtn.getAttribute('title')));
  console.log('AC29_PANEL =', JSON.stringify((await page.locator('.ant-drawer .ant-tabs-tabpane-active').innerText()).replace(/\s+/g, ' ').trim().slice(0, 220)));
  await page.screenshot({ path: 'e2e/screenshots/stub-03-history-readonly.png', fullPage: true });

  console.log('PAGE_ERRORS =', JSON.stringify(errs));
  expect(errs).toEqual([]);
});

test('AC-32 · 空态 + AC-41 · 保存冲突', async ({ page }) => {
  test.setTimeout(180_000);
  await openBasicTab(page);
  await openDrawer(page);

  // ── AC-32 空态（versionNo=null 的 sheet）──
  await page.locator('.ant-drawer .ant-tabs-left .ant-tabs-tab', { hasText: '其他外加工成本' }).first().click();
  await page.waitForTimeout(2000);
  const pane = page.locator('.ant-drawer .ant-tabs-tabpane-active');
  console.log('AC32_PANEL =', JSON.stringify((await pane.innerText()).replace(/\s+/g, ' ').trim().slice(0, 240)));
  const sBtn = pane.locator('button', { hasText: '保存' }).first();
  console.log('AC32_SAVE_DISABLED =', await sBtn.isDisabled(), '| TITLE =', JSON.stringify(await sBtn.getAttribute('title')));
  console.log('AC32_ADD_DISABLED  =', await pane.locator('button', { hasText: '新增行' }).first().isDisabled());
  await page.screenshot({ path: 'e2e/screenshots/stub-04-empty.png', fullPage: true });

  // ── AC-41 保存冲突 ──
  await page.locator('.ant-drawer .ant-tabs-left .ant-tabs-tab', { hasText: '物料BOM' }).first().click();
  await page.waitForTimeout(2000);
  saveMode = 'CONFLICT';
  await page.locator('.ant-drawer .ant-tabs-tabpane-active button', { hasText: '保存' }).first().click();
  await page.waitForTimeout(2000);
  const p2 = page.locator('.ant-drawer .ant-tabs-tabpane-active');
  console.log('AC41_PANEL =', JSON.stringify((await p2.innerText()).replace(/\s+/g, ' ').trim().slice(0, 320)));
  console.log('AC41_ROWS_KEPT =', await p2.locator('.ant-table-tbody tr.ant-table-row').count());
  await page.screenshot({ path: 'e2e/screenshots/stub-05-conflict.png', fullPage: true });
  saveMode = 'UPGRADED';
});

test('AC-33 · 导入成功自动关闭+刷新 / AC-10+AC-34 · 校验失败列全', async ({ page }) => {
  test.setTimeout(180_000);
  await openBasicTab(page);

  // ── AC-34 / AC-10 失败 ──
  importMode = 'BAD';
  await page.locator('.ant-tabs-tabpane-active button', { hasText: '导入核价数据' }).first().click();
  await page.waitForSelector('.ant-drawer', { timeout: 15_000 });
  await page.locator('.ant-drawer input[type="file"]').setInputFiles({
    name: '核价2.xlsx', mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    buffer: Buffer.from('stub'),
  });
  await page.waitForTimeout(600);
  await page.locator('.ant-drawer button', { hasText: '开始导入' }).first().click();
  await page.waitForTimeout(2000);
  const body = (await page.locator('.ant-drawer-body').innerText()).replace(/\s+/g, ' ').trim();
  console.log('AC34_DRAWER_STILL_OPEN =', await page.locator('.ant-drawer').isVisible());
  console.log('AC34_BODY =', JSON.stringify(body.slice(0, 500)));
  console.log('AC10_ERROR_ROW_COUNT =', await page.locator('.ant-drawer .ant-table-tbody tr.ant-table-row').count());
  await page.screenshot({ path: 'e2e/screenshots/stub-06-import-fail.png', fullPage: true });

  // ── AC-33 成功 → 抽屉自动关闭 + 列表刷新 ──
  importMode = 'OK';
  let partsCalls = 0;
  page.on('request', (r) => { if (r.url().includes('/dataset/cost-basic/parts')) partsCalls++; });
  await page.locator('.ant-drawer button', { hasText: '开始导入' }).first().click();
  await page.waitForTimeout(2500);
  console.log('AC33_DRAWER_CLOSED =', !(await page.locator('.ant-drawer').first().isVisible().catch(() => false)));
  console.log('AC33_LIST_REFRESH_CALLS =', partsCalls);
  const notif = await page.locator('.ant-notification').innerText().catch(() => '(none)');
  console.log('AC33_SUMMARY =', JSON.stringify(notif.replace(/\s+/g, ' ').trim().slice(0, 400)));
  await page.screenshot({ path: 'e2e/screenshots/stub-07-import-ok.png', fullPage: true });
});

test('AC-30 · 新增行 / 删除行 / 只剩一行时禁止删除（422 前置拦截）', async ({ page }) => {
  test.setTimeout(180_000);
  await openBasicTab(page);
  await openDrawer(page);
  const pane = page.locator('.ant-drawer .ant-tabs-tabpane-active');
  const rows = pane.locator('.ant-table-tbody tr.ant-table-row');

  console.log('AC30_ROWS_INIT =', await rows.count());
  await pane.locator('button', { hasText: '新增行' }).first().click();
  await page.waitForTimeout(800);
  console.log('AC30_ROWS_AFTER_ADD =', await rows.count());

  // 删到只剩 1 行
  for (let i = 0; i < 3; i++) {
    const del = pane.locator('.ant-table-tbody tr.ant-table-row button', { hasText: '删除' }).last();
    if (await del.isDisabled()) break;
    await del.click();
    await page.waitForTimeout(500);
  }
  console.log('AC30_ROWS_AFTER_DELETES =', await rows.count());
  const lastDel = pane.locator('.ant-table-tbody tr.ant-table-row button', { hasText: '删除' }).last();
  console.log('AC30_LAST_DELETE_DISABLED =', await lastDel.isDisabled());
  console.log('AC30_LAST_DELETE_TITLE =', JSON.stringify(await lastDel.getAttribute('title')));
  await page.screenshot({ path: 'e2e/screenshots/stub-08-addrow-delete.png', fullPage: true });
});
