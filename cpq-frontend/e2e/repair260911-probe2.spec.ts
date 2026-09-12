import { test, Page } from '@playwright/test';
const QUOTATION_ID = '405ab315-3ee6-43e0-8a4d-9837b762ab81';
async function login(page: Page) {
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|change-password)/, { timeout: 30_000 });
  if (page.url().includes('/change-password')) { await page.goto('/dashboard'); await page.waitForLoadState('networkidle'); }
}
test('probe2: 列出所有按钮与步骤条', async ({ page }) => {
  await login(page);
  await page.goto(`/quotations/${QUOTATION_ID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(4000);
  for (let round = 0; round < 6; round++) {
    const btns = page.locator('button');
    const n = await btns.count();
    const info: any[] = [];
    for (let i = 0; i < n; i++) {
      const b = btns.nth(i);
      info.push({ i, text: ((await b.innerText().catch(() => '')) || '').replace(/\s+/g, ''), enabled: await b.isEnabled().catch(() => false), visible: await b.isVisible().catch(() => false) });
    }
    const steps = page.locator('.ant-steps-item');
    const sn = await steps.count();
    const stepInfo: string[] = [];
    for (let i = 0; i < sn; i++) stepInfo.push(((await steps.nth(i).innerText().catch(() => '')) || '').replace(/\s+/g, ' ').trim() + ' | cls=' + ((await steps.nth(i).getAttribute('class')) || ''));
    console.log(`=== ROUND ${round} url=${page.url()}`);
    console.log('STEPS:', JSON.stringify(stepInfo, null, 1));
    console.log('BUTTONS:', JSON.stringify(info.filter(x => x.visible)));
    const next = info.find(x => x.visible && x.enabled && /下一步/.test(x.text));
    if (!next) { console.log('no enabled 下一步, stop'); break; }
    await page.locator('button').nth(next.i).click().catch((e) => console.log('click err', e));
    await page.waitForTimeout(3000);
  }
  console.log('SEGMENTED:', JSON.stringify(await page.locator('.ant-segmented-item').allInnerTexts()));
  console.log('TABS:', JSON.stringify(await page.locator('.ant-tabs-tab').allInnerTexts()));
  console.log('TABLES:', await page.locator('.ant-table').count());
});
