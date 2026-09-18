import { test, expect, type Page } from '@playwright/test';
import * as fs from 'fs'; import * as path from 'path';
const OUT = path.resolve(process.cwd(), '..', 'dev-docs', 'task-260801-页签连表公式配置优化',
  'repair-260916-小计列无法选为字段引用', '证据', '亲验', '探测');
fs.mkdirSync(OUT, { recursive: true });
const w = (n: string, s: string) => fs.writeFileSync(path.join(OUT, n), s, 'utf8');
async function login(page: Page) {
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|change-password)/, { timeout: 60_000 });
}
test('探测4 · 已建单 0906 的 Step2 按钮与卡片', async ({ page }) => {
  await login(page);
  await page.goto('/quotations/aa854b50-2c3f-4d28-978b-f53f5b9abb62/edit');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);
  w('0906-step1-按钮.txt', (await page.locator('button:visible').allInnerTexts()).join('\n'));
  const next = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  if (await next.count()) { await next.click(); await page.waitForTimeout(4000); }
  w('0906-step2-按钮.txt', (await page.locator('button:visible').allInnerTexts()).join('\n'));
  await page.screenshot({ path: path.join(OUT, '0906-step2.png'), fullPage: true });
  w('0906-卡片数.txt', String(await page.locator('.qt-product-card').count()));
});
