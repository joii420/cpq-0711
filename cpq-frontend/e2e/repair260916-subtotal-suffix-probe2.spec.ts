/** repair-260916 S-B · DOM 探测 2：Excel 新增列行结构 + 小计组件公式入口 + 灰芯片悬停（只操作本片自建数据） */
import { test, type Page } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';
import * as H from './repair260916-subtotal-suffix.helpers';
import { fx, N } from './repair260916-subtotal-suffix.helpers';
import * as path from 'path';

test.use({ viewport: { width: 1920, height: 1080 } });
const P = (n: string) => path.join('探测', n);
test.beforeAll(async () => { await H.setupFixtures(); });
test.afterAll(async () => { await H.cleanupFixtures(); });

const rowsDump = (page: Page) => page.evaluate(() => Array.from(document.querySelectorAll('.ant-table-tbody tr'))
  .filter((r) => (r as HTMLElement).offsetParent !== null).map((r) => (r as HTMLElement).outerHTML.slice(0, 2500)));

test('探测2', async ({ page }) => {
  test.setTimeout(300_000);
  page.on('console', (m) => { if (m.type() === 'error') console.log(`[console.error] ${m.text().slice(0, 300)}`); });
  page.on('pageerror', (e) => console.log(`[pageerror] ${String(e).slice(0, 300)}`));
  await loginAsAdmin(page);
  console.log(`[probe2] after login url=${page.url()}`);
  const log: Record<string, unknown> = {};
  const reqs: string[] = [];
  page.on('request', (r) => { if (r.url().includes('/api/') && r.method() !== 'GET') reqs.push(`${r.method()} ${r.url()} ${(r.postData() || '').slice(0, 600)}`); });

  // 灰芯片悬停
  await H.openComponent(page, fx.host!);
  const d0 = await H.addFormulaAndOpen(page);
  const c = H.chip(H.cardOf(d0, N.proc), '工时');
  log.procChip = await c.evaluate((el) => ({ html: el.outerHTML, cursor: getComputedStyle(el).cursor, color: getComputedStyle(el).color, parent: el.parentElement?.outerHTML.slice(0, 600) }));
  await c.hover();
  await page.waitForTimeout(800);
  log.tooltips = await page.locator('[role="tooltip"], .ant-tooltip').allInnerTexts();
  await H.shot(page, P('11-灰芯片悬停'));
  await page.reload();

  H.writeEvidence(P('探测2-结果.json'), log);
  // 小计组件
  await H.openComponent(page, fx.sub!);
  log.subHasFormulaTab = await page.getByRole('tab', { name: '公式' }).count();
  log.subRowsBefore = await rowsDump(page);
  await page.getByRole('button', { name: '添加公式' }).click();
  await page.waitForTimeout(500);
  log.subRowsAfter = await rowsDump(page);
  await H.shot(page, P('12-小计组件添加公式'));
  await page.reload();

  H.writeEvidence(P('探测2-结果.json'), log);
  // Excel 组件
  await H.openComponent(page, fx.excel!);
  await page.locator('button.ant-btn').filter({ hasText: /添加列/ }).first().click();
  await page.waitForTimeout(600);
  log.excelRowsAfterAdd = await rowsDump(page);
  log.excelSelects = await page.locator('.ant-table-tbody .ant-select').count();
  await H.shot(page, P('13-Excel添加列'));
  const sel = page.locator('.ant-table-tbody .ant-select').first();
  if (await sel.count()) {
    await sel.click();
    await page.waitForTimeout(400);
    log.excelOptions = await page.locator('.ant-select-item-option').allInnerTexts();
    await H.shot(page, P('14-Excel来源下拉'));
    const opt = page.locator('.ant-select-item-option').filter({ hasText: '页签连表' }).first();
    if (await opt.count()) { await opt.click(); await page.waitForTimeout(500); }
    log.excelRowsAfterType = await rowsDump(page);
    await H.shot(page, P('15-Excel选页签连表后'));
  }
  log.excelButtons = await page.locator('button').filter({ visible: true }).allInnerTexts();
  const cfg = page.locator('.ant-table-tbody button.ant-btn').filter({ hasText: /配\s*置|编\s*辑|公\s*式/ }).first();
  log.cfgCount = await cfg.count();
  if (await cfg.count()) {
    await cfg.click();
    await page.waitForTimeout(1000);
    log.drawerTitles = await page.locator('.ant-drawer-title, .ant-modal-title').allInnerTexts();
    await H.shot(page, P('16-Excel列配置弹层'));
    const d = H.drawerLoc(page);
    if (await d.count()) {
      log.excelDrawerText = await d.innerText();
      await H.chip(H.cardOf(d, N.fee), '加工费(小计)').click().catch((e) => { log.chipErr = String(e); });
      await page.waitForTimeout(400);
      log.excelEditor = await H.readEditor(H.editorOf(d)).catch((e) => String(e));
      await H.drawerSave(page, d).catch((e) => { log.saveErr = String(e); });
      log.drawerAfterSave = await d.isVisible();
      log.excelRowsAfterSave = await rowsDump(page);
      await H.shot(page, P('17-Excel列抽屉保存后'));
      const n = reqs.length;
      await H.pageSave(page).catch((e) => { log.pageSaveErr = String(e); });
      log.reqsPageSave = reqs.slice(n);
      log.excelColumnsDb = (await H.getComponent(fx.excel!.id)).data.excelColumns;
    }
  }
  H.writeEvidence(P('探测2-结果.json'), log);
  H.writeEvidence(P('探测2-请求.txt'), reqs.join('\n'));
});
