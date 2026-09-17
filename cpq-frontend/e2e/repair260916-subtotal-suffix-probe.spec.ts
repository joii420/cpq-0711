/**
 * repair-260916 S-B · DOM 探测（主线审核 260917 第 3 条授权；只操作本片自建数据 RP0916B-E2E）
 * 探测四件事：落库路径 / Excel 新增列入口 / 小计组件公式页签 / 报错位置。结果落 证据/测试/S-B/探测/。
 * 本文件不做 AC 断言，只采样。
 */
import { test, expect, type Page } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';
import * as H from './repair260916-subtotal-suffix.helpers';
import { fx, N } from './repair260916-subtotal-suffix.helpers';
import * as fs from 'fs';
import * as path from 'path';

test.use({ viewport: { width: 1920, height: 1080 } });
test.describe.configure({ timeout: 300_000 });
const P = (n: string) => path.join('探测', n);

test.beforeAll(async () => { fs.mkdirSync(path.join(H.EVID, '探测'), { recursive: true }); await H.setupFixtures(); });
test.afterAll(async () => { await H.cleanupFixtures(); });

async function dumpButtons(page: Page) {
  return page.evaluate(() => Array.from(document.querySelectorAll('button'))
    .filter((b) => (b as HTMLElement).offsetParent !== null)
    .map((b) => ({ text: (b.textContent || '').trim(), cls: b.className, inDrawer: !!b.closest('.ant-drawer') })));
}

test('探测', async ({ page }) => {
  await loginAsAdmin(page);
  const reqs: string[] = [];
  page.on('request', (r) => { if (r.url().includes('/api/') && r.method() !== 'GET') reqs.push(`${r.method()} ${r.url()} ${(r.postData() || '').slice(0, 400)}`); });
  const log: Record<string, unknown> = {};

  // ① 宿主：公式页签 + 抽屉结构 + 落库路径
  await H.openComponent(page, fx.host!);
  await H.shot(page, P('01-宿主组件页'));
  log.hostButtons = await dumpButtons(page);
  await H.gotoFormulaTab(page);
  log.formulaHeaders = await page.locator('.ant-table-thead th').filter({ visible: true }).allInnerTexts();
  const drawer = await H.addFormulaAndOpen(page);
  await page.waitForTimeout(800);
  await H.shot(page, P('02-宿主抽屉'));
  log.drawerButtons = await dumpButtons(page);
  log.leftCardCount = await H.leftCards(drawer).count();
  log.leftCardTexts = await H.leftCards(drawer).allInnerTexts();
  log.leftTags = await H.leftCol(drawer).locator('.ant-tag').evaluateAll((els) =>
    els.map((e) => ({ t: (e.textContent || '').trim(), style: e.getAttribute('style'), cursor: getComputedStyle(e).cursor, color: getComputedStyle(e).color })));
  const editor = H.editorOf(drawer);
  log.editorCount = await editor.count();
  log.editorAttrs = await editor.evaluate((el) => Array.from(el.attributes).map((a) => `${a.name}=${a.value}`));
  log.editorBefore = await editor.evaluate((el) => ({ before: getComputedStyle(el, '::before').content, html: el.outerHTML.slice(0, 1500) }));
  log.drawerText = await drawer.innerText();
  await H.chip(H.cardOf(drawer, N.fee), '加工费').click();
  await page.waitForTimeout(400);
  log.editorAfterChip = await editor.evaluate((el) => el.outerHTML.slice(0, 3000));
  log.readEditor1 = await H.readEditor(editor);
  const reqBefore = reqs.length;
  await drawer.locator('button.ant-btn-primary').filter({ hasText: /保\s*存/ }).first().click();
  await page.waitForTimeout(2000);
  log.reqsOnDrawerSave = reqs.slice(reqBefore);
  log.drawerVisibleAfterSave = await drawer.isVisible();
  log.formulasAfterDrawerSave = ((await H.getComponent(fx.host!.id)).data.formulas ?? []).length;
  log.pageButtonsAfterDrawer = await dumpButtons(page);
  await H.shot(page, P('03-抽屉保存后'));
  const r2 = reqs.length;
  try { await H.pageSave(page); log.pageSave = 'clicked'; } catch (e) { log.pageSave = String(e).slice(0, 300); }
  log.reqsOnPageSave = reqs.slice(r2);
  log.formulasAfterPageSave = (await H.getComponent(fx.host!.id)).data.formulas;
  log.listCells = await page.locator('.ant-table-tbody tr').filter({ visible: true }).allInnerTexts();
  await H.shot(page, P('04-组件保存后'));
  try { log.listExpr0 = await H.formulaListExpr(page, 0); } catch (e) { log.listExpr0 = String(e); }

  // ② 报错位置：非法 (小计)
  const d2 = await H.addFormulaAndOpen(page);
  const ed2 = H.editorOf(d2);
  await H.clearEditor(page, ed2);
  await ed2.click();
  await page.keyboard.insertText('[RP0916加工费.备注数(小计)]');
  await page.waitForTimeout(500);
  log.illegalEditor = await ed2.evaluate((el) => el.outerHTML.slice(0, 2000));
  await d2.locator('button.ant-btn-primary').filter({ hasText: /保\s*存/ }).first().click();
  await page.waitForTimeout(600);
  log.messages = await page.locator('.ant-message-notice').allInnerTexts();
  log.messagesAlt = await page.locator('.ant-message, .ant-notification, .ant-alert, .ant-form-item-explain').allInnerTexts();
  log.drawerTextAfterIllegal = await d2.innerText();
  await H.shot(page, P('05-非法写法保存'));
  await page.reload();

  // ③ 小计组件
  await H.openComponent(page, fx.sub!);
  await H.shot(page, P('06-小计组件页'));
  log.subTabs = await page.getByRole('tab').allInnerTexts();
  log.subButtons = await dumpButtons(page);

  // ④ Excel 组件
  await H.openComponent(page, fx.excel!);
  await H.shot(page, P('07-Excel组件页'));
  log.excelTabs = await page.getByRole('tab').allInnerTexts();
  log.excelButtons = await dumpButtons(page);
  log.excelTables = await page.locator('.ant-table').filter({ visible: true }).allInnerTexts();
  log.excelMainText = (await page.locator('body').innerText()).slice(0, 6000);

  H.writeEvidence(P('探测-结果.json'), log);
  H.writeEvidence(P('探测-请求.txt'), reqs.join('\n'));
  expect(true).toBe(true);
});
