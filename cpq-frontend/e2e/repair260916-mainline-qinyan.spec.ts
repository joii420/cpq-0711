/**
 * repair-260916 · 主线亲验（mainline hands-on verification, CLAUDE.md §4.5 step 4）.
 * Walks the user path in the acceptance UI (5196 -> 8196 -> cpq_db_260916) and records screenshots
 * into 证据/亲验/. Builder pages run behind a write guard (nothing is saved).
 * Reuses the S-global UI helpers; page readiness uses waitFor (the helper's isVisible() does not wait).
 */
import { test, expect, type Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import * as H from './repair260916-global.helpers';

const OUT = path.join(H.EVID, '亲验');
const log: string[] = [];
function note(line: string) { console.log(line); log.push(line); }
async function snap(page: Page, name: string) {
  fs.mkdirSync(OUT, { recursive: true });
  await page.screenshot({ path: path.join(OUT, `${name}.png`), fullPage: true });
  note(`[shot] 证据/亲验/${name}.png`);
}
async function snapTable(page: Page, name: string) {
  fs.mkdirSync(OUT, { recursive: true });
  const t = page.locator('.qt-product-card').first().locator('table.qt-cost-table').locator('visible=true').first();
  await t.scrollIntoViewIfNeeded();
  await page.waitForTimeout(500);
  await t.screenshot({ path: path.join(OUT, `${name}-表格.png`) });
  note(`[shot] 证据/亲验/${name}-表格.png`);
}
async function waitReady(page: Page, url: string, selector: string) {
  await page.goto(url);
  const el = page.locator(selector).first();
  try {
    await el.waitFor({ state: 'visible', timeout: 45_000 });
  } catch {
    note(`[waitReady] ${selector} not visible after 45s on ${url}; reloading once`);
    await page.reload();
    await el.waitFor({ state: 'visible', timeout: 90_000 });
  }
}

test.afterAll(() => {
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, '亲验-控制台摘录.txt'), log.join('\n') + '\n', 'utf-8');
});

test('亲验 A · 取数配置器：COMP-0004 面板 / SQL / 预览（AC-2/3/4/7④）+ 基础核价来料加工费（AC-2/3）', async ({ page }) => {
  await H.uiLogin(page);
  const blocked = await H.installWriteGuard(page);
  const c4 = H.COMPS.find((c) => c.code === 'COMP-0004')!;
  const { get } = await H.openBuilderTab(page, c4);
  note(`[A] COMP-0004 GET /builder isStale=${get.isStale} builderVersion=${get.builderVersion} currentCompilerVersion=${get.currentCompilerVersion}`);
  expect(get.isStale).toBe(false);
  const tabText = await page.locator('.svb-recipe-bar').first().innerText();
  expect(tabText).not.toMatch(/过期/);

  await H.expandAllGroups(page);
  const panel = await H.readFieldPanel(page);
  const nameHits = panel.groups.flatMap((g) => g.fields.filter((f) => f === '材料名').map(() => g.head));
  note(`[A] COMP-0004 panel groups=${JSON.stringify(panel.groups.map((g) => g.head))} 材料名 in groups=${JSON.stringify(nameHits)}`);
  expect(nameHits.length).toBe(1);
  expect(nameHits[0]).toContain('来料固定加工费');
  expect(panel.groups.some((g) => /材质/.test(g.head))).toBe(false);
  await snap(page, 'A1-COMP-0004-可用字段');

  const sql = H.normSql(await H.sqlPaneText(page));
  note(`[A] COMP-0004 SQL pane: ${sql}`);
  const co = H.coalesceMatch(sql);
  expect(co, 'COALESCE(material_name, symbol) present').not.toBeNull();
  expect(co!.asCol).toBe('_物料_材料名');
  const recJoin = H.leftJoinsOf(sql, 'material_recipe');
  const matJoin = H.leftJoinsOf(sql, 'ds_quote_material');
  note(`[A] material_recipe joins=${JSON.stringify(recJoin)} ds_quote_material joins=${JSON.stringify(matJoin)}`);
  expect(recJoin.length).toBe(1);
  expect(recJoin[0].on).not.toMatch(/customer_no/);
  expect(recJoin[0].on).toMatch(/input_material_no/);
  expect(matJoin.length).toBe(1);
  expect(matJoin[0].on).toMatch(/input_material_no/);
  expect(matJoin[0].on).toMatch(/customer_no/);
  await snap(page, 'A2-COMP-0004-生成的SQL');

  const p = await H.runPreview(page, H.CUSTOMER_CODE, 'S3120011203', 'mainline COMP-0004');
  const byPart = H.namesByPart(p.rows, '_来料固定加工费_投入料号');
  note(`[A] COMP-0004 preview rowCount=${p.rowCount} names=${JSON.stringify(byPart)} diagnostics=${JSON.stringify(p.diagnostics)}`);
  expect(p.rows.length).toBe(2);
  expect(byPart['00144']).toEqual(['H85']);
  expect(byPart['S3110520422']).toEqual(['料号2']);
  const previewText = await page.locator('.svb-preview').first().innerText();
  expect(previewText).toContain('H85');
  expect(previewText).toContain('料号2');
  await snap(page, 'A3-COMP-0004-预览-00144为H85');

  await H.selectDataset(page, '基础核价', 'ds_cost_basic_');
  await H.selectSource(page, '来料加工费');
  await H.expandAllGroups(page);
  const bp = await H.readFieldPanel(page);
  const bHits = bp.groups.flatMap((g) => g.fields.filter((f) => f === '材料名').map(() => g.head));
  note(`[A] 基础核价/来料加工费 材料名 in groups=${JSON.stringify(bHits)}`);
  expect(bHits.length).toBe(1);
  await H.addFieldAndCompile(page, '材料名');
  const bsql = H.normSql(await H.sqlPaneText(page));
  note(`[A] 基础核价/来料加工费 SQL: ${bsql}`);
  const bco = H.coalesceMatch(bsql);
  expect(bco).not.toBeNull();
  const bRec = H.leftJoinsOf(bsql, 'material_recipe');
  const bMat = H.leftJoinsOf(bsql, 'ds_cost_basic_material');
  expect(bRec.length).toBe(1);
  expect(bRec[0].on).toMatch(/incoming_material_no/);
  expect(bMat.length).toBe(1);
  expect(bMat[0].on).toMatch(/production_no/);
  expect(bMat[0].on).toMatch(/incoming_material_no/);
  await snap(page, 'A4-基础核价-来料加工费-SQL');
  note(`[A] write guard blocked=${JSON.stringify(blocked)}`);
});

test('亲验 B · 取数配置器：COMP-0005 / COMP-0008 预览（AC-4）', async ({ page }) => {
  await H.uiLogin(page);
  const blocked = await H.installWriteGuard(page);
  const c5 = H.COMPS.find((c) => c.code === 'COMP-0005')!;
  await H.openBuilderTab(page, c5);
  const p5 = await H.runPreview(page, H.CUSTOMER_CODE, 'S3110520422', 'mainline COMP-0005');
  const n5 = H.namesByPart(p5.rows, '_来料其他费用_投入料号');
  note(`[B] COMP-0005 S3110520422 names=${JSON.stringify(n5)}`);
  expect(n5['00256']?.length).toBeGreaterThan(0);
  expect(new Set(n5['00256'])).toEqual(new Set(['TU2丝']));
  expect(n5['00257']).toEqual(['羰基镍粉']);
  await snap(page, 'B1-COMP-0005-预览-S3110520422');

  const c8 = H.COMPS.find((c) => c.code === 'COMP-0008')!;
  await H.openBuilderTab(page, c8);
  const p8 = await H.runPreview(page, H.CUSTOMER_CODE, 'S3120011203', 'mainline COMP-0008');
  const n8 = H.namesByPart(p8.rows, '_来料回收折扣_投入料号');
  note(`[B] COMP-0008 S3120011203 names=${JSON.stringify(n8)}`);
  expect(n8['00144']).toEqual(['H85']);
  await snap(page, 'B2-COMP-0008-预览-S3120011203');
  note(`[B] write guard blocked=${JSON.stringify(blocked)}`);
});

test('亲验 C · 报价单：新单 QT-20260916-0880（v1.7）三页签 / 详情页 H85，旧单 QT-20260916-0877 仍「—」（AC-10）', async ({ page }) => {
  await H.uiLogin(page);
  const q = H.sqlJson<{ id: string; qn: string }>(`select id::text, quotation_number qn from quotation where quotation_number in ('QT-20260916-0880','QT-20260916-0877') order by quotation_number`);
  const newQ = q.find((r) => r.qn === 'QT-20260916-0880')!;
  const oldQ = q.find((r) => r.qn === 'QT-20260916-0877')!;
  expect(newQ && oldQ).toBeTruthy();
  const oldUpdatedBefore = H.sqlScalar(`select updated_at::text from quotation where id='${oldQ.id}'`);

  // detail page of the new quotation: three tabs
  await waitReady(page, `/quotations/${newQ.id}`, 'button.qt-tab-btn');
  for (const [tab, i] of [['来料固定加工费', 1], ['来料其他费用', 2], ['来料回收', 3]] as const) {
    await H.clickCardTab(page, tab);
    const t = await H.readCardTable(page);
    const names = H.namesOf(t, '00144', `detail ${tab}`);
    note(`[C] ${newQ.qn} 详情页 ${tab} 00144 → ${JSON.stringify(names)}`);
    expect(names.every((n) => n === 'H85')).toBe(true);
    if (tab === '来料固定加工费') expect(H.namesOf(t, 'S3110520422', 'detail fixed fee')).toEqual(['料号2']);
    await snap(page, `C${i}-${newQ.qn}-详情页-${tab}`); await snapTable(page, `C${i}-${newQ.qn}-详情页-${tab}`);
  }
  await H.assertNoBadStates(page, 'new quotation detail');

  // edit page step2 of the new quotation
  await H.openEditStep2(page, newQ.id);
  await H.clickCardTab(page, '来料固定加工费');
  const et = await H.readCardTable(page);
  const en = H.namesOf(et, '00144', 'edit fixed fee');
  note(`[C] ${newQ.qn} 编辑页 来料固定加工费 00144 → ${JSON.stringify(en)}`);
  expect(en.every((n) => n === 'H85')).toBe(true);
  await snap(page, `C4-${newQ.qn}-编辑页-来料固定加工费`); await snapTable(page, `C4-${newQ.qn}-编辑页-来料固定加工费`);

  // old quotation (v1.4): unchanged by design
  await waitReady(page, `/quotations/${oldQ.id}`, 'button.qt-tab-btn');
  await H.clickCardTab(page, '来料固定加工费');
  const ot = await H.readCardTable(page);
  const on = H.namesOf(ot, '00144', 'old fixed fee');
  const op = H.namesOf(ot, 'S3110520422', 'old fixed fee');
  note(`[C] ${oldQ.qn} 详情页 来料固定加工费 00144 → ${JSON.stringify(on)}，S3110520422 → ${JSON.stringify(op)}`);
  expect(on.every((n) => H.isBlank(n))).toBe(true);
  expect(op).toEqual(['料号2']);
  await snap(page, `C5-${oldQ.qn}-详情页-来料固定加工费-旧版仍为空`); await snapTable(page, `C5-${oldQ.qn}-详情页-来料固定加工费-旧版仍为空`);
  const oldUpdatedAfter = H.sqlScalar(`select updated_at::text from quotation where id='${oldQ.id}'`);
  note(`[C] ${oldQ.qn} updated_at before=${oldUpdatedBefore} after=${oldUpdatedAfter}`);
});
