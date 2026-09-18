/** repair-260916 亲验 · 重开已存报价单，读物料页签 00144(H85) 的「材料成本」显示值。 */
import { test, expect, type Page } from '@playwright/test';
import * as fs from 'fs'; import * as path from 'path';
const OUT = path.resolve(process.cwd(), '..', 'dev-docs', 'task-260801-页签连表公式配置优化',
  'repair-260916-小计列无法选为字段引用', '证据', '亲验');
const w = (n: string, c: unknown) => fs.writeFileSync(path.join(OUT, n), typeof c === 'string' ? c : JSON.stringify(c, null, 2), 'utf8');
const note = (s: string) => { console.log(`[亲验] ${s}`); fs.appendFileSync(path.join(OUT, '亲验-运行日志.txt'), `${new Date().toISOString()} ${s}\n`); };
const QID = process.env.RP_QID!;
async function login(page: Page) {
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|change-password)/, { timeout: 60_000 });
}
test('亲验 C2 · 重开报价单读 H85 材料成本', async ({ page }) => {
  expect(QID, '需传 RP_QID').toBeTruthy();
  await login(page);
  await page.goto(`/quotations/${QID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);
  const next = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  await expect(next).toBeEnabled({ timeout: 120_000 });
  await next.click();
  await page.waitForTimeout(6000);
  const card = page.locator('.qt-product-card').first();
  await expect(card).toBeVisible({ timeout: 60_000 });
  const tab = card.locator('button.qt-tab-btn').filter({ hasText: /^\s*物料\s*$/ }).first();
  await tab.click();
  await page.waitForTimeout(6000);
  const read = async () => card.locator('table.qt-cost-table').locator('visible=true').first().evaluate((tbl) => {
    const heads = Array.from(tbl.querySelectorAll('thead th')).map((th) => (th as HTMLElement).innerText.replace(/\s+/g, ' ').trim());
    const i = heads.findIndex((h) => h.includes('材料成本') && !h.includes('损耗'));
    const rows = Array.from(tbl.querySelectorAll('tbody tr'));
    for (const tr of rows) {
      const tds = Array.from(tr.querySelectorAll('td'));
      const txt = (td: Element) => { const inp = td.querySelector('input,textarea') as HTMLInputElement | null; return inp ? (inp.value ?? '').trim() : (td as HTMLElement).innerText.replace(/\s+/g, ' ').trim(); };
      if (tds.some((td) => txt(td) === '00144')) return { header: heads[i], value: txt(tds[i]), title: (tds[i] as HTMLElement).getAttribute('title') || '', row: tds.map(txt) };
    }
    return null;
  });
  let r = await read();
  for (let k = 0; k < 10 && (!r || r.value === '0' || r.value === ''); k++) { await page.waitForTimeout(3000); r = await read(); }
  note(`C2 重开后 00144「${r?.header}」显示=「${r?.value}」`);
  w('C8-重开后-H85材料成本.json', { quotationId: QID, ...r });
  const cell = card.locator('table.qt-cost-table').locator('visible=true').first().locator('tbody tr').filter({ hasText: '00144' }).first();
  await cell.scrollIntoViewIfNeeded().catch(() => {});
  await page.screenshot({ path: path.join(OUT, 'C8-重开后-物料页签.png'), fullPage: true });
  expect(r?.value, '00144 的材料成本应显示为按本行加工费算出的值').toBe('0.212585113');
});
