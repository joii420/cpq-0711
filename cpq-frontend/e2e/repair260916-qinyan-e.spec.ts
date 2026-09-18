/**
 * repair-260916 · 主线亲验 E：序列用例 + Excel 列 + 存量 Excel 视图 + 导入预览。
 * AC-3（四种写法混用：存 → 关 → 重开 → 原样再存）、AC-18①②③（Excel 列两种块）、
 * AC-15③（QT-20260916-0881 编辑页 Excel 视图三列）、AC-16⑤（导入预览「旧格式」提示）。
 * 只在一次性库 cpq_db_rp0916d 上跑；AC-3 用新增的草稿公式，不点组件页「保存」⇒ 不落库。
 */
import { test, expect, type Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { PALETTE, readEditor, drawerLoc, editorOf, leftCards, cardOf, chip, clearEditor, formulaRows, messageTexts } from './repair260916-subtotal-suffix.helpers';

const OUT = path.resolve(process.cwd(), '..', 'dev-docs', 'task-260801-页签连表公式配置优化',
  'repair-260916-小计列无法选为字段引用', '证据', '亲验');
const FIX = path.resolve(process.cwd(), '..', 'dev-docs', 'task-260801-页签连表公式配置优化',
  'repair-260916-小计列无法选为字段引用', '证据', '夹具');
const note = (s: string) => { console.log(`[亲验] ${s}`); fs.appendFileSync(path.join(OUT, '亲验-运行日志.txt'), `${new Date().toISOString()} ${s}\n`); };
const w = (n: string, c: unknown) => fs.writeFileSync(path.join(OUT, n), typeof c === 'string' ? c : JSON.stringify(c, null, 2), 'utf8');

const EXPR4 = '[来料固定加工费.加工费] + [来料固定加工费.加工费(小计)] + [来料固定加工费(总计)] + SUM([来料固定加工费.加工费])';

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
  let hit = false; const deadline = Date.now() + 20_000;
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
  await expect(card).toBeVisible({ timeout: 20_000 });
  await card.click();
  await page.waitForTimeout(1200);
  const tab = page.getByRole('tab', { name: '公式', exact: true });
  if (await tab.count()) { await tab.first().click(); await page.waitForTimeout(800); }
}

test.describe.configure({ mode: 'serial' });

test('亲验 E1 · 四种写法混用：存 → 关 → 重开（AC-3 / AC-4 颜色与文字）', async ({ page }) => {
  await login(page);
  await openComponentByCode(page, 'COMP-0002');
  const before = await formulaRows(page).count();
  await page.getByRole('button', { name: '添加公式' }).click();
  await expect.poll(() => formulaRows(page).count(), { timeout: 15_000 }).toBe(before + 1);
  const idx = before; // 新增行在最后
  await formulaRows(page).nth(idx).getByRole('button', { name: '配置' }).click();
  let d = drawerLoc(page);
  await expect(d).toBeVisible({ timeout: 15_000 });
  await expect.poll(() => leftCards(d).count(), { timeout: 20_000 }).toBeGreaterThan(0);
  await clearEditor(page, editorOf(d));
  await editorOf(d).click();
  await page.keyboard.insertText(EXPR4);
  await page.waitForTimeout(800);
  const first = await readEditor(editorOf(d));
  note(`E1 首次输入块色=${JSON.stringify(first.blocks.map((b) => b.color))} 文字=「${first.raw}」`);
  await d.locator('button.ant-btn-primary').filter({ hasText: /保\s*存/ }).first().click();
  await page.waitForTimeout(1200);
  note(`E1 抽屉保存提示=${JSON.stringify(await messageTexts(page))}`);
  await expect(d, '合法写法保存后抽屉应关闭').toBeHidden({ timeout: 15_000 });

  // 重开同一行：按「表达式」列文字定位（行序可能变），并等公式框内容出现
  await page.waitForTimeout(2000);
  const targetRow = formulaRows(page).filter({ hasText: '来料固定加工费(总计)' }).first();
  await expect(targetRow, '公式列表应能按文字找到刚存的那一行').toBeVisible({ timeout: 20_000 });
  note(`E1 列表行文字=「${(await targetRow.innerText()).replace(/\s+/g, ' ').slice(0, 200)}」`);
  await targetRow.getByRole('button', { name: '配置' }).click();
  d = drawerLoc(page);
  await expect(d).toBeVisible({ timeout: 15_000 });
  await expect.poll(() => leftCards(d).count(), { timeout: 20_000 }).toBeGreaterThan(0);
  await expect.poll(async () => (await readEditor(editorOf(d))).raw.trim().length, { timeout: 20_000, message: '重开后公式框应有内容' }).toBeGreaterThan(0);
  const again = await readEditor(editorOf(d));
  note(`E1 重开块色=${JSON.stringify(again.blocks.map((b) => b.color))} 文字=「${again.raw}」`);
  w('E1-AC3-四种写法.json', { first: { raw: first.raw, blocks: first.blocks }, reopen: { raw: again.raw, blocks: again.blocks } });
  await d.screenshot({ path: path.join(OUT, 'E1-AC3-抽屉重开.png') });
  expect(again.raw.replace(/\s+/g, ' ').trim(), 'AC-3②：重开文字应与输入逐字相同').toBe(EXPR4);
  expect(again.blocks.map((b) => b.color), 'AC-3②：四块颜色应为 蓝/黄/绿/蓝').toEqual(['blue', 'yellow', 'green', 'blue']);
  // 不保存组件 ⇒ 不落库
  await d.locator('button').filter({ hasText: /取\s*消/ }).first().click().catch(() => {});
});

test('亲验 E2 · Excel 列：小计列黄块 / 明细蓝块（AC-18①③）', async ({ page }) => {
  await login(page);
  await openComponentByCode(page, 'COMP-0011');
  const cfg = page.locator('button:visible').filter({ hasText: /^\s*(配置公式|公式[:：])/ }).first();
  await expect(cfg).toBeVisible({ timeout: 20_000 });
  const label = (await cfg.innerText()).trim();
  note(`E2 打开 Excel 列「${label}」`);
  await cfg.click();
  const d = drawerLoc(page);
  await expect(d).toBeVisible({ timeout: 20_000 });
  await expect.poll(() => leftCards(d).count(), { timeout: 20_000 }).toBeGreaterThan(0);
  const ed = editorOf(d);
  const orig = await readEditor(ed);
  note(`E2 该列原文=「${orig.raw}」 块色=${JSON.stringify(orig.blocks.map((b) => b.color))}`);

  // 小计列芯片 → 黄块
  await clearEditor(page, ed);
  await chip(cardOf(d, '物料'), '材料成本(小计)').click();
  await page.waitForTimeout(500);
  const y = await readEditor(ed);
  // 明细芯片 → 蓝块
  await clearEditor(page, ed);
  await chip(cardOf(d, '物料'), '材料成本').click();
  await page.waitForTimeout(500);
  const b = await readEditor(ed);
  note(`E2 小计列插入=「${y.raw}」(${y.blocks[0]?.color}) 明细插入=「${b.raw}」(${b.blocks[0]?.color})`);
  w('E2-AC18-Excel两种块.json', { 原文: orig.raw, 小计: { raw: y.raw, color: y.blocks[0]?.color, bg: y.blocks[0]?.bg }, 明细: { raw: b.raw, color: b.blocks[0]?.color, bg: b.blocks[0]?.bg } });
  await d.screenshot({ path: path.join(OUT, 'E2-Excel抽屉-明细块.png') });
  expect(y.raw.trim()).toBe('[物料.材料成本(小计)]');
  expect(y.blocks[0]?.bg, 'AC-18①：小计列块应为黄色').toBe(PALETTE.yellow.bg);
  expect(b.raw.trim()).toBe('[物料.材料成本]');
  expect(b.blocks[0]?.bg, 'AC-18①：明细块应为蓝色').toBe(PALETTE.blue.bg);
  // 还原成原文后取消（不保存组件）
  await clearEditor(page, ed);
  await ed.click();
  await page.keyboard.insertText(orig.raw);
  await page.waitForTimeout(400);
  await d.locator('button').filter({ hasText: /取\s*消/ }).first().click().catch(() => {});
});

test('亲验 E3 · 存量单 0881 编辑页 Excel 视图三列（AC-15③）', async ({ page }) => {
  await login(page);
  await page.goto('/quotations/40fe7ae7-c6e1-403a-aa30-17989ec758e9/edit');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);
  const next = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  await expect(next).toBeEnabled({ timeout: 120_000 });
  await next.click();
  await page.waitForTimeout(6000);
  const excelBtn = page.locator('button:visible, label:visible').filter({ hasText: /Excel\s*视图/ }).first();
  await expect(excelBtn, '第二步应有「Excel 视图」切换').toBeVisible({ timeout: 30_000 });
  await excelBtn.click();
  await page.waitForTimeout(8000);
  const tbl = page.locator('table').filter({ hasText: '材料成本' }).first();
  await expect(tbl, 'Excel 视图应渲染出表格').toBeVisible({ timeout: 60_000 });
  const data = await tbl.evaluate((t) => ({
    headers: Array.from(t.querySelectorAll('thead th')).map((th) => (th as HTMLElement).innerText.replace(/\s+/g, ' ').trim()),
    rows: Array.from(t.querySelectorAll('tbody tr')).map((tr) => Array.from(tr.querySelectorAll('td')).map((td) => (td as HTMLElement).innerText.replace(/\s+/g, ' ').trim())),
  }));
  note(`E3 0881 Excel 视图 表头=${JSON.stringify(data.headers)} 行=${JSON.stringify(data.rows)}`);
  w('E3-AC15③-0881-Excel视图.json', data);
  await page.screenshot({ path: path.join(OUT, 'E3-0881-Excel视图.png'), fullPage: true });
  const flat = data.rows.flat().join(' | ');
  for (const v of ['1.978941064', '0.317766357', '1.804589425']) {
    expect(flat, `AC-15③：Excel 视图应显示 ${v}`).toContain(v);
  }
});

test('亲验 E4 · 导入预览：旧包有「旧格式」提示、新包没有（AC-16⑤）', async ({ page }) => {
  await login(page);
  await page.goto('/components');
  await expect(page.getByPlaceholder('🔍 搜索组件名 / 编码')).toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(1500);
  // 任取一个目录的导入图标（无文本 icon 按钮）
  const dir = page.locator('.cmm-dir').first();
  await dir.hover();
  const imp = dir.locator('.anticon-import').first();
  await expect(imp, '目录行应有导入图标').toBeVisible({ timeout: 20_000 });
  await imp.click();
  const dr = page.locator('.ant-drawer').last();
  await expect(dr, '应打开导入抽屉').toBeVisible({ timeout: 20_000 });
  const out: any = {};
  for (const [tag, file] of [['v1.0', '施耐德成环检测-导出包-v1.0.json'], ['v1.1', '施耐德成环检测-导出包-v1.1.json']] as const) {
    await dr.locator('input[type="file"]').first().setInputFiles(path.join(FIX, file));
    await page.waitForTimeout(1500);
    await dr.locator('button:visible').filter({ hasText: /预\s*览/ }).first().click();
    await page.waitForTimeout(5000);
    const txt = (await dr.innerText()).replace(/\s+/g, ' ');
    const hasHint = txt.includes('旧格式');
    out[tag] = { hasHint, snippet: txt.slice(0, 400) };
    note(`E4 ${tag} 预览「旧格式」提示=${hasHint}`);
    await page.screenshot({ path: path.join(OUT, `E4-导入预览-${tag}.png`), fullPage: true });
  }
  w('E4-AC16⑤-导入预览.json', out);
  expect(out['v1.0'].hasHint, 'AC-16⑤：v1.0 包应出现「旧格式」提示').toBe(true);
  expect(out['v1.1'].hasHint, 'AC-16⑤：v1.1 包不应出现「旧格式」提示').toBe(false);
  // 只预览，不提交导入
  await dr.locator('button').filter({ hasText: /关\s*闭|取\s*消/ }).first().click().catch(() => {});
});
