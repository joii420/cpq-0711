/**
 * repair-260916 · 主线亲验 F：小计组件宿主（AC-8b）。
 * 宿主 = COMP-0009「产品小计」（SUBTOTAL，无行键）；来源 = COMP-0004「来料固定加工费」。
 * 点「明细·加工费」应红并被拒；点「小计列·加工费(小计)」应黄并可存。不点组件页「保存」⇒ 不落库。
 */
import { test, expect, type Page } from '@playwright/test';
import * as fs from 'fs'; import * as path from 'path';
import { PALETTE, readEditor, drawerLoc, editorOf, leftCards, cardOf, chip, clearEditor, messageTexts } from './repair260916-subtotal-suffix.helpers';
const OUT = path.resolve(process.cwd(), '..', 'dev-docs', 'task-260801-页签连表公式配置优化',
  'repair-260916-小计列无法选为字段引用', '证据', '亲验');
const note = (s: string) => { console.log(`[亲验] ${s}`); fs.appendFileSync(path.join(OUT, '亲验-运行日志.txt'), `${new Date().toISOString()} ${s}\n`); };
const w = (n: string, c: unknown) => fs.writeFileSync(path.join(OUT, n), typeof c === 'string' ? c : JSON.stringify(c, null, 2), 'utf8');
test('亲验 F · 小计组件：明细被拒 / 小计列可存（AC-8b）', async ({ page }) => {
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|change-password)/, { timeout: 60_000 });
  await page.goto('/components');
  const search = page.getByPlaceholder('🔍 搜索组件名 / 编码');
  await expect(search).toBeVisible({ timeout: 30_000 });
  await search.fill('COMP-0009');
  await page.waitForTimeout(1500);
  const dirs = page.locator('.cmm-dir');
  for (let i = 0; i < await dirs.count(); i++) {
    const d = dirs.nth(i);
    const n = ((await d.locator('.cmm-dir-name').first().innerText().catch(() => '')) || '').replace(/^📁\s*/, '').trim();
    if (n === '施耐德成环检测') { if (!(await d.evaluate((el) => el.classList.contains('open')))) await d.locator('.cmm-dir-head').first().click(); break; }
  }
  await page.waitForTimeout(1200);
  await page.locator('.cmm-card').filter({ hasText: 'COMP-0009' }).first().click();
  await page.waitForTimeout(1500);
  w('F-AC8b-小计组件页按钮.txt', (await page.locator('button:visible').allInnerTexts()).join('\n'));
  await page.screenshot({ path: path.join(OUT, 'F-AC8b-小计组件页.png'), fullPage: true });
  // 小计组件没有「公式」页签：「添加公式」直接在页面上（S-B 探测）
  const add = page.locator('button:visible').filter({ hasText: /添加公式/ }).first();
  await expect(add, '小计组件页应有「添加公式」').toBeVisible({ timeout: 20_000 });
  await add.click();
  await page.waitForTimeout(1200);
  const cfg = page.locator('button:visible').filter({ hasText: /^\s*配置\s*$/ }).last();
  await cfg.click();
  const d = drawerLoc(page);
  await expect(d).toBeVisible({ timeout: 20_000 });
  await expect.poll(() => leftCards(d).count(), { timeout: 20_000 }).toBeGreaterThan(0);
  const ed = editorOf(d);
  const res: any = {};
  // ① 明细 → 红 + 保存被拒
  await clearEditor(page, ed);
  await chip(cardOf(d, '来料固定加工费'), '加工费').click();
  await page.waitForTimeout(600);
  const r1 = await readEditor(ed);
  await d.locator('button.ant-btn-primary').filter({ hasText: /保\s*存/ }).first().click();
  await page.waitForTimeout(1500);
  res.明细 = { raw: r1.raw, color: r1.blocks[0]?.color, msgs: await messageTexts(page), drawerOpen: await d.isVisible() };
  note(`F AC-8b 明细：raw=「${r1.raw}」色=${r1.blocks[0]?.color} 提示=${JSON.stringify(res.明细.msgs)} 抽屉仍开=${res.明细.drawerOpen}`);
  await page.screenshot({ path: path.join(OUT, 'F-AC8b-明细被拒.png'), fullPage: true });
  expect(r1.blocks[0]?.bg, 'AC-8b：明细引用应为红块').toBe(PALETTE.red.bg);
  expect(res.明细.drawerOpen, 'AC-8b：明细引用保存应被拒（抽屉不关）').toBe(true);
  // ② 小计列 → 黄 + 可存
  await clearEditor(page, ed);
  await chip(cardOf(d, '来料固定加工费'), '加工费(小计)').click();
  await page.waitForTimeout(600);
  const r2 = await readEditor(ed);
  await d.locator('button.ant-btn-primary').filter({ hasText: /保\s*存/ }).first().click();
  await page.waitForTimeout(1500);
  res.小计列 = { raw: r2.raw, color: r2.blocks[0]?.color, msgs: await messageTexts(page), drawerOpen: await d.isVisible() };
  note(`F AC-8b 小计列：raw=「${r2.raw}」色=${r2.blocks[0]?.color} 抽屉仍开=${res.小计列.drawerOpen}`);
  w('F-AC8b-小计组件.json', res);
  expect(r2.blocks[0]?.bg, 'AC-8b：小计列引用应为黄块').toBe(PALETTE.yellow.bg);
  expect(r2.raw.trim()).toBe('[来料固定加工费.加工费(小计)]');
  expect(res.小计列.drawerOpen, 'AC-8b：小计列引用应保存成功（抽屉关闭）').toBe(false);
});
