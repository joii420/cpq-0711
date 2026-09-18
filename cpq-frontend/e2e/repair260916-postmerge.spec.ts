/** repair-260916 · 合并后在共享 dev server（5174 → 8081 → cpq_db_0724）上的只读复核：不保存、不改数据。 */
import { test, expect, type Page } from '@playwright/test';
import * as fs from 'fs'; import * as path from 'path';
import { readEditor, drawerLoc, editorOf, leftCards, formulaRows, formulaListExpr, PALETTE } from './repair260916-subtotal-suffix.helpers';
const OUT = path.resolve(process.cwd(), '..', 'dev-docs', 'task-260801-页签连表公式配置优化',
  'repair-260916-小计列无法选为字段引用', '证据', '亲验');
const w = (n: string, c: unknown) => fs.writeFileSync(path.join(OUT, n), typeof c === 'string' ? c : JSON.stringify(c, null, 2), 'utf8');
test('合并后复核 · 开发库存量公式回显带 (小计)（只读）', async ({ page }) => {
  const blocked: string[] = [];
  await page.route('**/api/**', async (r) => {
    const q = r.request();
    // 放行：登录 + 纯计算端点（row-key-candidates 只算不写，与 S-C 写守卫同口径）
    if (q.method() === 'GET' || /\/auth\/login$/.test(q.url()) || /\/row-key-candidates(\?|$)/.test(q.url())) return r.continue();
    blocked.push(`${q.method()} ${q.url()}`); return r.abort();
  });
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|change-password)/, { timeout: 60_000 });
  await page.goto('/components');
  const s = page.getByPlaceholder('🔍 搜索组件名 / 编码');
  await expect(s).toBeVisible({ timeout: 30_000 });
  await s.fill('COMP-0002');
  await page.waitForTimeout(1500);
  const dirs = page.locator('.cmm-dir');
  for (let i = 0; i < await dirs.count(); i++) {
    const d = dirs.nth(i);
    const n = ((await d.locator('.cmm-dir-name').first().innerText().catch(() => '')) || '').replace(/^📁\s*/, '').trim();
    if (n === '施耐德成环检测') { if (!(await d.evaluate((el) => el.classList.contains('open')))) await d.locator('.cmm-dir-head').first().click(); break; }
  }
  await page.waitForTimeout(1200);
  await page.locator('.cmm-card').filter({ hasText: 'COMP-0002' }).first().click();
  await page.waitForTimeout(1500);
  const tab = page.getByRole('tab', { name: '公式', exact: true });
  if (await tab.count()) { await tab.first().click(); await page.waitForTimeout(900); }
  const n = await formulaRows(page).count();
  let idx = -1, text = '';
  for (let i = 0; i < n; i++) { const t = await formulaListExpr(page, i); if (t && t.includes('来料固定加工费.加工费')) { idx = i; text = t; break; } }
  console.log(`[合并后复核] 公式行数=${n} 命中第 ${idx} 行：「${text}」`);
  expect(idx, '开发库 COMP-0002 应有引用加工费的公式').toBeGreaterThanOrEqual(0);
  expect(text, '合并后：存量公式应回显为带 (小计) 的写法').toContain('[来料固定加工费.加工费(小计)]');
  await formulaRows(page).nth(idx).getByRole('button', { name: '配置' }).click();
  const d = drawerLoc(page);
  await expect(d).toBeVisible({ timeout: 15_000 });
  await expect.poll(() => leftCards(d).count(), { timeout: 20_000 }).toBeGreaterThan(0);
  const r = await readEditor(editorOf(d));
  const sub = r.blocks.find((b) => b.display.includes('加工费(小计)'));
  console.log(`[合并后复核] 小计块色=${sub?.bg}`);
  expect(sub?.bg, '小计块应为黄色').toBe(PALETTE.yellow.bg);
  await page.screenshot({ path: path.join(OUT, 'G-合并后-开发库-公式抽屉.png'), fullPage: true });
  w('G-合并后-开发库复核.json', { 列表文字: text, 小计块: sub, 被拦截的写请求: blocked });
  expect(blocked, '本复核应为只读（不得有写请求）').toEqual([]);
});
