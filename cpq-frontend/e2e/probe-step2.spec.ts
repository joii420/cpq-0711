import { test } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';
const QID = process.env.PROBE_QID || '';
test('probe step2', async ({ page }) => {
  test.setTimeout(300000);
  await loginAsAdmin(page);
  await page.goto(`/quotations/${QID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(6000);
  const next = page.getByRole('button', { name: /下\s*一\s*步/ });
  const cnt = await next.count();
  const enabled = cnt ? await next.first().isEnabled().catch(() => false) : false;
  console.log(`[STEP1] 「下一步」按钮数=${cnt} 可点=${enabled}`);
  if (enabled) {
    await next.first().click(); await page.waitForTimeout(4000);
    const t = await page.evaluate(() => document.body.innerText);
    console.log(`[STEP2] 进入后文本长度=${t.length} 含"产品小计"=${t.includes('产品小计')} 含"添加产品"=${t.includes('添加产品')}`);
    // 数据行提取（沿用详情页那套文本量具）
    const cards = t.split(/料号:\s*/).slice(1);
    console.log(`[STEP2] 切出卡片=${cards.length}`);
  } else {
    const t = await page.evaluate(() => document.body.innerText);
    const req = await page.locator('.ant-form-item-explain-error, .ant-form-item-has-error').count();
    console.log(`[STEP1] 被挡住。表单错误元素=${req}；页面尾部=${t.slice(-260).replace(/\n/g,' | ')}`);
  }
  await page.screenshot({ path: `e2e/screenshots/qinyan-260908/probe-step2-${QID.slice(0,8)}.png`, fullPage: true });
});
