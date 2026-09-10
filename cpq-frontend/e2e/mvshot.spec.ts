import { test } from '@playwright/test';
const SHOT='/tmp/claude-1000/-home-joii-project-cpq/dd3dc6c5-18d8-4be3-b01e-677976918873/scratchpad/verify';
test.use({ viewport: { width: 1920, height: 1200 } });

async function login(page:any){
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products)/,{timeout:20000});
}

test('原型比对截图：列配置区', async ({ page }) => {
  test.setTimeout(180000);
  await login(page);
  await page.goto('/components');
  await page.waitForLoadState('networkidle');
  await page.locator('input[placeholder*="搜索组件"]').fill('COMP-2422');
  await page.waitForTimeout(1800);
  const card = page.locator('.cmm-card').filter({ hasText: 'COMP-2422' });
  const dir = page.locator('.cmm-dir').filter({ has: card });
  if (!(await card.isVisible())) { await dir.click({position:{x:12,y:12}}); await page.waitForTimeout(1500); }
  await card.locator('.cmm-c-name').first().click();
  await page.waitForTimeout(2500);
  await page.locator('.ant-tabs-tab', { hasText: '取数配置' }).first().click();
  await page.waitForTimeout(3500);

  await page.screenshot({ path: SHOT+'/40-impl-full.png', fullPage: true });
  // 只截「已选列」那一块（含字段类型选择器）
  const sel = page.locator('[data-role="field-type-select"]').first();
  await sel.scrollIntoViewIfNeeded();
  await page.waitForTimeout(600);
  await page.screenshot({ path: SHOT+'/41-impl-列配置区.png' });
  // 批量入口那一行
  const bulk = page.locator('[data-role="bulk-field-type"]');
  console.log('批量入口存在 =', await bulk.count());
  const box = await bulk.boundingBox();
  if (box) {
    await page.screenshot({ path: SHOT+'/42-impl-批量入口.png',
      clip: { x: Math.max(0,box.x-560), y: Math.max(0,box.y-30), width: 900, height: 110 } });
  }
  console.log('✅ 截图完成');
});
