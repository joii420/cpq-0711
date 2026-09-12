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
test('probe3: step2 卡片结构', async ({ page }) => {
  await login(page);
  await page.goto(`/quotations/${QID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3500);
  await page.locator('button', { hasText: '下一步' }).first().click();
  await page.waitForTimeout(6000);
  await page.screenshot({ path: path.join(OUT, 'p3-step2.png'), fullPage: true });
  const dump = async (sel: string) => JSON.stringify((await page.locator(sel).allInnerTexts()).map(s => s.replace(/\s+/g, ' ').trim()));
  console.log('SEGMENTED:', await dump('.ant-segmented-item'));
  console.log('RADIO:', await dump('.ant-radio-button-wrapper'));
  console.log('CARD_HEADS:', await dump('.ant-card-head'));
  console.log('TABLES:', await page.locator('.ant-table').count());
  console.log('S0001 occurrences:', await page.locator('text=S0001').count());
  // 卡片容器候选
  for (const sel of ['.ant-card', '[class*=product-card]', '[class*=ProductCard]']) {
    console.log('sel', sel, 'count', await page.locator(sel).count());
  }
  // 打印整页可见文本前 4000 字
  const body = (await page.locator('body').innerText()).replace(/\n{2,}/g, '\n');
  fs.writeFileSync(path.join(OUT, 'p3-body.txt'), body);
  console.log('--- BODY (first 4000) ---');
  console.log(body.slice(0, 4000));
});
