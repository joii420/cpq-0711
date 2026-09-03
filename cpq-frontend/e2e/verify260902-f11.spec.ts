/**
 * task-260902 · F-11 前导零显示修复取证（前端工程师自检用，**非正式测试用例**）
 * AC-53：STRING 编码列前导零不被改写（00168 / 00006），只读态同样成立
 * AC-54：DECIMAL/NUMBER 列**仍然**去尾零（22.000000000000 → 22；5.000000000000 → 5）——防修过头
 * 🚨 `page.route` 打桩（8081 未部署 /dataset/*），验的是前端渲染，不是联调。
 */
import { test, expect } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';

const ok = (data: unknown) => ({ code: 200, message: 'success', data });

/** 物料与元素BOM：材质料号=STRING（带前导零）；含量/损耗率=DECIMAL（定标补零） */
const EB_COLUMNS = [
  { name: 'item_seq', label: '项次', role: 'VALUE', type: 'NUMBER', editable: true, compared: false },
  { name: 'material_part_no', label: '材质料号', role: 'VALUE', type: 'STRING', editable: true, compared: true },
  { name: 'element_code', label: '元素代码', role: 'VALUE', type: 'STRING', editable: true, compared: true },
  { name: 'content_pct', label: '组成含量（%）', role: 'VALUE', type: 'DECIMAL', editable: true, compared: true },
  { name: 'loss_rate', label: '损耗率（%）', role: 'VALUE', type: 'DECIMAL', editable: true, compared: true },
];

const EB_ROWS = [
  { item_seq: 1, material_part_no: '00168', element_code: 'Ag', content_pct: '22.000000000000', loss_rate: '5.000000000000' },
  { item_seq: 2, material_part_no: '00006', element_code: 'Ni', content_pct: '2.780000000000', loss_rate: '0.000000000000' },
  { item_seq: 3, material_part_no: '991', element_code: 'Cu', content_pct: '1.100000000000', loss_rate: '0E-12' },
];

test('AC-53/54 · 前导零保留 + 数值列仍去尾零', async ({ page }) => {
  test.setTimeout(180_000);
  const errs: string[] = [];
  page.on('pageerror', (e) => errs.push(String(e).slice(0, 200)));
  await loginAsAdmin(page);

  await page.route('**/api/cpq/dataset/**', async (route) => {
    const url = new URL(route.request().url());
    const p = url.pathname;
    if (p.endsWith('/sheets')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ok({
        sheets: [{ sheetKey: 'ELEMENT_BOM', sheetName: '物料与元素BOM', sortOrder: 1,
          axisColumn: 'production_no', axisLabel: '生产料号', columns: EB_COLUMNS }] })) });
    }
    if (p.endsWith('/overview')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ok({
        axisValue: '3120014539', materialName: '主料1',
        sheets: [{ sheetKey: 'ELEMENT_BOM', rowCount: 3, versionNo: 2, source: 'IMPORT' }] })) });
    }
    if (p.endsWith('/versions')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ok({ versions: [
        { versionNo: 2, isLatest: true, rowCount: 3, updatedAt: '2026-09-03T10:12:00+08:00', updatedBy: 'admin', source: 'IMPORT' },
        { versionNo: 1, isLatest: false, rowCount: 3, archivedAt: '2026-09-01T14:05:00+08:00', archivedBy: 'admin' },
      ] })) });
    }
    if (p.endsWith('/rows')) {
      const v = url.searchParams.get('version');
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ok({
        versionNo: v === '1' ? 1 : 2, isLatest: v !== '1', readOnly: v === '1', source: 'IMPORT', rows: EB_ROWS })) });
    }
    if (p.endsWith('/parts')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ok({
        total: 1, items: [{ axisValue: '3120014539', materialName: '主料1', specification: null,
          dimension: '3.5×3.5×0.6', oldMaterialNo: '8DLX.550.653', configuredCount: 1,
          totalSheetCount: 1, lastUpdatedAt: '2026-09-03T10:12:41+08:00' }] })) });
    }
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(ok({ items: [] })) });
  });

  await page.goto('/master-data-hub');
  await page.waitForSelector('.ant-tabs-nav .ant-tabs-tab', { timeout: 60_000 });
  await page.locator('.ant-tabs-nav .ant-tabs-tab', { hasText: '基础核价' }).first().click();
  await page.waitForTimeout(1800);
  await page.locator('.ant-table-tbody tr.ant-table-row').first().locator('td').nth(1).click();
  await page.waitForSelector('.ant-drawer', { timeout: 15_000 });
  await page.waitForTimeout(2500);

  const pane = page.locator('.ant-drawer .ant-tabs-tabpane-active');

  // ── 可编辑态：STRING 走 Input（value 属性），DECIMAL 走 InputNumber ──
  const editVals = await pane.locator('.ant-table-tbody tr.ant-table-row input').evaluateAll(
    (els) => els.map((e) => (e as HTMLInputElement).value));
  console.log('AC53_EDITABLE_INPUT_VALUES =', JSON.stringify(editVals));

  // ── 只读态（历史版本 v1）：全部走 displayText ──
  await pane.locator('.ant-select').first().click();
  await page.waitForTimeout(800);
  await page.locator('.ant-select-item-option', { hasText: 'v1' }).first().click();
  await page.waitForTimeout(2200);
  const rows = pane.locator('.ant-table-tbody tr.ant-table-row');
  for (let i = 0; i < await rows.count(); i++) {
    const cells = await rows.nth(i).locator('td').allInnerTexts();
    console.log(`READONLY_ROW${i + 1} =`, JSON.stringify(cells.map((c) => c.trim())));
  }
  console.log('READONLY_HEADERS =', JSON.stringify((await pane.locator('.ant-table-thead th').allInnerTexts()).map((h) => h.trim())));
  await page.screenshot({ path: 'e2e/screenshots/f11-readonly-leading-zero.png', fullPage: true });

  console.log('PAGE_ERRORS =', JSON.stringify(errs));
  expect(errs).toEqual([]);
});
