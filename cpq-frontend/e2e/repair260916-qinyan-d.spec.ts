/**
 * repair-260916 · 主线亲验 D：真实组件上的拦截与只读断言。
 * 宿主 = COMP-0002「物料」（行键 销售料号+料号）；来源 = COMP-0004「来料固定加工费」（加工费勾小计、项次未勾）。
 * 全程不点组件页「保存」——被拒的公式本就不落库；新增的草稿公式不保存。
 * AC-5a/b/c、AC-6a/c/d、AC-7、AC-9、AC-18⑤
 */
import { test, expect, type Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { PALETTE, readEditor, drawerLoc, editorOf, leftCards, cardOf, chip, clearEditor, formulaRows, messageTexts } from './repair260916-subtotal-suffix.helpers';

const OUT = path.resolve(process.cwd(), '..', 'dev-docs', 'task-260801-页签连表公式配置优化',
  'repair-260916-小计列无法选为字段引用', '证据', '亲验');
const note = (s: string) => { console.log(`[亲验] ${s}`); fs.appendFileSync(path.join(OUT, '亲验-运行日志.txt'), `${new Date().toISOString()} ${s}\n`); };
const w = (n: string, c: unknown) => fs.writeFileSync(path.join(OUT, n), typeof c === 'string' ? c : JSON.stringify(c, null, 2), 'utf8');

async function login(page: Page) {
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|change-password)/, { timeout: 60_000 });
}

async function openComponentByCode(page: Page, code: string) {
  await page.goto('/components');
  const search = page.getByPlaceholder('🔍 搜索组件名 / 编码');
  await expect(search).toBeVisible({ timeout: 30_000 });
  await search.fill(code);
  await page.waitForTimeout(1500);
  const dirs = page.locator('.cmm-dir');
  const deadline = Date.now() + 20_000;
  let hit = false;
  while (!hit && Date.now() < deadline) {
    for (let i = 0; i < await dirs.count(); i++) {
      const d = dirs.nth(i);
      const n = ((await d.locator('.cmm-dir-name').first().innerText().catch(() => '')) || '').replace(/^📁\s*/, '').trim();
      if (n === '施耐德成环检测') {
        if (!(await d.evaluate((el) => el.classList.contains('open')))) { await d.locator('.cmm-dir-head').first().click(); await page.waitForTimeout(500); }
        hit = true; break;
      }
    }
    if (!hit) await page.waitForTimeout(500);
  }
  expect(hit, '应看到目录「施耐德成环检测」').toBe(true);
  const card = page.locator('.cmm-card').filter({ hasText: code }).first();
  await expect(card, `应看到组件卡片 ${code}`).toBeVisible({ timeout: 20_000 });
  await card.click();
  await page.waitForTimeout(1200);
  const tab = page.getByRole('tab', { name: '公式', exact: true });
  if (await tab.count()) { await tab.first().click(); await page.waitForTimeout(800); }
}

/** 新增一条公式并打开抽屉（不保存组件 ⇒ 不落库）。 */
async function addFormulaDrawer(page: Page) {
  const before = await formulaRows(page).count();
  await page.getByRole('button', { name: '添加公式' }).click();
  await expect.poll(() => formulaRows(page).count(), { timeout: 15_000 }).toBe(before + 1);
  await formulaRows(page).last().getByRole('button', { name: '配置' }).click();
  const d = drawerLoc(page);
  await expect(d).toBeVisible({ timeout: 15_000 });
  await expect.poll(() => leftCards(d).count(), { timeout: 20_000 }).toBeGreaterThan(0);
  return d;
}

async function typeAndSave(page: Page, d: ReturnType<typeof drawerLoc>, expr: string) {
  const ed = editorOf(d);
  await clearEditor(page, ed);
  await ed.click();
  await page.keyboard.insertText(expr);
  await page.waitForTimeout(700);
  const r = await readEditor(ed);
  await d.locator('button.ant-btn-primary').filter({ hasText: /保\s*存/ }).first().click();
  await page.waitForTimeout(1200);
  const msgs = await messageTexts(page);
  return { blocks: r.blocks, msgs, open: await d.isVisible() };
}

test.describe.configure({ mode: 'serial' });

test('亲验 D · 真实组件上的非法写法与函数内小计（AC-5 / AC-6）', async ({ page }) => {
  await login(page);
  await openComponentByCode(page, 'COMP-0002');
  const d = await addFormulaDrawer(page);
  const out: any[] = [];
  const cases: Array<{ ac: string; expr: string; msg: string }> = [
    { ac: 'AC-5a', expr: '[来料固定加工费.项次(小计)]', msg: '页签「来料固定加工费」的列「项次」没有勾选小计，不能写成「(小计)」' },
    { ac: 'AC-5b', expr: '[物料.材料毛重(小计)]', msg: '不能引用本页签自身的小计：[物料.材料毛重(小计)]' },
    { ac: 'AC-5c', expr: '[来料固定加工费(小计)]', msg: '「(小计)」要写在列名后面，如 [页签.列(小计)]；整页签合计请写 [页签(总计)]' },
    { ac: 'AC-6a', expr: 'SUM([来料固定加工费.加工费(小计)])', msg: 'SUM() 里不能再对小计求和：[来料固定加工费.加工费(小计)] 已是整列小计' },
    { ac: 'AC-6c', expr: 'SUM(KSUM([来料固定加工费.加工费(小计)]))', msg: 'KSUM() 内不支持 (小计) 小计引用 [来料固定加工费.加工费(小计)]，请引用明细字段或把小计放到外层' },
    { ac: 'AC-6d', expr: 'SUMIF([来料固定加工费.项次] > 0, [来料固定加工费.加工费(小计)])', msg: 'SUMIF() 里不支持「(小计)」引用 [来料固定加工费.加工费(小计)]' },
  ];
  for (const c of cases) {
    const r = await typeAndSave(page, d, c.expr);
    const red = r.blocks.filter((b) => b.bg === PALETTE.red.bg).map((b) => b.display);
    note(`D ${c.ac}「${c.expr}」→ 红块=${JSON.stringify(red)} 提示=${JSON.stringify(r.msgs)} 抽屉仍开=${r.open}`);
    out.push({ ...c, red, msgs: r.msgs, drawerStillOpen: r.open });
    expect(r.msgs.join(' | '), `${c.ac}：提示应逐字包含方案文案`).toContain(c.msg);
    expect(r.open, `${c.ac}：被拒后抽屉应仍打开`).toBe(true);
  }
  w('D1-非法写法-实测.json', out);
  await page.screenshot({ path: path.join(OUT, 'D1-拒绝提示.png'), fullPage: true });
});

test('亲验 D2 · 本页签卡片无小计列组 + 搜索态插入一致（AC-7 / AC-9）', async ({ page }) => {
  await login(page);
  await openComponentByCode(page, 'COMP-0002');
  const d = await addFormulaDrawer(page);
  const ed = editorOf(d);

  // AC-7①：宿主自己的卡片没有「小计列」组（它确有勾小计的列：材料成本等）
  const self = cardOf(d, '物料');
  const selfText = (await self.innerText()).replace(/\s+/g, ' ');
  note(`D2 宿主卡片文本=「${selfText.slice(0, 200)}」`);
  w('D2-宿主卡片文本.txt', selfText);
  expect(selfText.includes('小计列'), 'AC-7①：宿主卡片不应有「小计列」组').toBe(false);
  // 对照：来源页签卡片有「小计列」组
  const src = cardOf(d, '来料固定加工费');
  const srcText = (await src.innerText()).replace(/\s+/g, ' ');
  expect(srcText.includes('小计列'), '对照：来源页签卡片应有「小计列」组').toBe(true);

  // AC-7②：点宿主「明细」组某列 → 紫色块
  await clearEditor(page, ed);
  await chip(self, '材料毛重').click();
  await page.waitForTimeout(400);
  const r1 = await readEditor(ed);
  note(`D2 宿主明细插入 raw=「${r1.raw}」 色=${r1.blocks[0]?.bg}`);
  expect(r1.raw.trim(), 'AC-7②：应插入 [材料毛重]').toBe('[材料毛重]');
  expect(r1.blocks[0]?.bg, 'AC-7②：本页签块应为原型紫色').toBe(PALETTE.purple.bg);

  // AC-9：搜索态与非搜索态插入文字一致
  const ins = async () => { await clearEditor(page, ed); await chip(cardOf(d, '来料固定加工费'), '加工费').click(); await page.waitForTimeout(400); const a = (await readEditor(ed)).raw.trim(); await clearEditor(page, ed); await chip(cardOf(d, '来料固定加工费'), '加工费(小计)').click(); await page.waitForTimeout(400); const b = (await readEditor(ed)).raw.trim(); return { a, b }; };
  const plain = await ins();
  const search = d.locator('input[placeholder*="搜索"]').first();
  await search.fill('加工费');
  await page.waitForTimeout(1200);
  const searched = await ins();
  note(`D2 AC-9 非搜索=${JSON.stringify(plain)} 搜索态=${JSON.stringify(searched)}`);
  w('D2-AC9-插入对照.json', { plain, searched });
  expect(searched, 'AC-9：搜索态与非搜索态插入文字应一致').toEqual(plain);
  expect(plain.a).toBe('[来料固定加工费.加工费]');
  expect(plain.b).toBe('[来料固定加工费.加工费(小计)]');
  await page.screenshot({ path: path.join(OUT, 'D2-抽屉.png'), fullPage: true });
});

test('亲验 D3 · Excel 组件抽屉没有 SUMIF 组（AC-18⑤）', async ({ page }) => {
  await login(page);
  // 宿主（页签组件）抽屉：应有 SUMIF 组
  await openComponentByCode(page, 'COMP-0002');
  const dh = await addFormulaDrawer(page);
  const hostBtns = await dh.locator('button:visible').allInnerTexts();
  const hostSumif = hostBtns.filter((t) => /^(SUMIF|COUNTIF|AVGIF|MINIF|MAXIF)$/.test(t.replace(/\s/g, '')));
  note(`D3 页签组件抽屉 SUMIF 类按钮=${JSON.stringify(hostSumif)}`);
  expect(hostSumif.length, '对照：页签组件抽屉应有 5 个 SUMIF 类按钮').toBe(5);
  await page.locator('.ant-drawer').last().locator('button').filter({ hasText: /取\s*消|关\s*闭/ }).first().click().catch(() => {});
  await page.waitForTimeout(800);

  // Excel 组件 COMP-0011「ex1」
  await openComponentByCode(page, 'COMP-0011');
  await page.screenshot({ path: path.join(OUT, 'D3-Excel组件页.png'), fullPage: true });
  w('D3-Excel组件页按钮.txt', (await page.locator('button:visible').allInnerTexts()).join('\n'));
  w('D3-Excel组件页正文.txt', (await page.locator('body').innerText()).slice(0, 4000));
  // 探测 260917：Excel 列的公式入口是行内按钮，已配的显示「公式：[原文]」，未配显示「配置公式」
  const cfg = page.locator('button:visible').filter({ hasText: /^\s*(配置公式|公式[:：])/ }).first();
  await expect(cfg, 'Excel 组件应有行内公式入口（见 D3-Excel组件页按钮.txt）').toBeVisible({ timeout: 20_000 });
  note(`D3 Excel 公式入口按钮文字=「${(await cfg.innerText()).slice(0, 60)}」`);
  await cfg.click();
  const de = drawerLoc(page);
  await expect(de, 'Excel 组件公式抽屉应打开').toBeVisible({ timeout: 20_000 });
  await page.waitForTimeout(1500);
  const excelBtns = await de.locator('button:visible').allInnerTexts();
  const excelSumif = excelBtns.filter((t) => /^(SUMIF|COUNTIF|AVGIF|MINIF|MAXIF)$/.test(t.replace(/\s/g, '')));
  note(`D3 Excel 抽屉 SUMIF 类按钮=${JSON.stringify(excelSumif)}（全部按钮 ${excelBtns.length} 个）`);
  w('D3-Excel抽屉按钮.txt', excelBtns.join('\n'));
  await page.screenshot({ path: path.join(OUT, 'D3-Excel抽屉.png'), fullPage: true });
  expect(excelSumif.length, 'AC-18⑤：Excel 组件抽屉不应有 SUMIF 类按钮').toBe(0);
});
