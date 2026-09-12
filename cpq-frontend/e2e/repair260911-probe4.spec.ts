import { test, Page } from '@playwright/test';
import * as fs from 'fs'; import * as path from 'path'; import { fileURLToPath } from 'url';
const __d = path.dirname(fileURLToPath(import.meta.url));
const OUT = process.env.R260911_OUT || path.join(__d, 'repair260911-out'); fs.mkdirSync(OUT, { recursive: true });
const QID = '405ab315-3ee6-43e0-8a4d-9837b762ab81';
async function login(page: Page) {
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|change-password)/, { timeout: 30_000 });
  if (page.url().includes('/change-password')) { await page.goto('/dashboard'); await page.waitForLoadState('networkidle'); }
}
test('probe4: 核价单 S0001 卡片 BOM 页签表格', async ({ page }) => {
  await login(page);
  await page.goto(`/quotations/${QID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3500);
  await page.locator('button', { hasText: '下一步' }).first().click();
  await page.waitForTimeout(6000);
  await page.locator('.ant-segmented-item', { hasText: '核价单' }).first().click();
  await page.waitForTimeout(6000);
  await page.screenshot({ path: path.join(OUT, 'p4-costing.png'), fullPage: true });
  const cards = page.locator('[class*=product-card]');
  console.log('cards:', await cards.count());
  const card = cards.filter({ hasText: 'S0001' }).first();
  console.log('card S0001 found:', await card.count());
  const btns = await card.locator('button').allInnerTexts();
  console.log('card buttons:', JSON.stringify(btns.map(s => s.replace(/\s+/g, ''))));
  const bom = card.locator('button', { hasText: /^\s*BOM\s*$/ }).first();
  await bom.click();
  await page.waitForTimeout(5000);
  await page.screenshot({ path: path.join(OUT, 'p4-costing-bom.png'), fullPage: true });
  const txt = (await card.innerText()).replace(/\n{2,}/g, '\n');
  fs.writeFileSync(path.join(OUT, 'p4-card-bom.txt'), txt);
  console.log('--- CARD TEXT ---'); console.log(txt.slice(0, 5000));
  // 结构化：表头 + 行
  const tables = card.locator('table');
  console.log('tables in card:', await tables.count());
  const t = tables.first();
  console.log('HEADERS:', JSON.stringify((await t.locator('thead th').allInnerTexts()).map(s => s.replace(/\s+/g, ' ').trim())));
  const rows = t.locator('tbody tr');
  const rc = await rows.count();
  console.log('rows:', rc);
  for (let r = 0; r < rc; r++) {
    const cells = rows.nth(r).locator('td');
    const cn = await cells.count(); const vals: string[] = [];
    for (let c = 0; c < cn; c++) {
      const td = cells.nth(c); const inp = td.locator('input');
      vals.push(await inp.count() > 0 ? '[i]' + (await inp.first().inputValue().catch(() => '')) : ((await td.innerText().catch(() => '')) || '').replace(/\s+/g, ' ').trim());
    }
    console.log(`row${r}:`, JSON.stringify(vals));
  }
  console.log('TFOOT:', JSON.stringify((await t.locator('tfoot').allInnerTexts()).map(s => s.replace(/\s+/g, ' ').trim())));
});
