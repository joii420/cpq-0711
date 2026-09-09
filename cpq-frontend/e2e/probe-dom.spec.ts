import { test } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';
const QID = '4ce0fcc4-a73b-4672-ba45-6387e03491ad';
test('probe2', async ({ page }) => {
  test.setTimeout(300000);
  await loginAsAdmin(page);
  await page.goto(`/quotations/${QID}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(10000);
  // 滚动真正可滚的那个容器
  await page.evaluate(() => {
    const all = Array.from(document.querySelectorAll('*')) as HTMLElement[];
    const sc = all.filter(e => e.scrollHeight > e.clientHeight + 50);
    sc.forEach(e => { e.scrollTop = e.scrollHeight; });
    return sc.length;
  });
  await page.waitForTimeout(6000);
  const info = await page.evaluate(() => ({
    tables: document.querySelectorAll('.ant-table').length,
    tabs: Array.from(document.querySelectorAll('.ant-tabs-tab')).map(e => (e as HTMLElement).innerText).slice(0, 30),
    cards: document.querySelectorAll('.ant-card').length,
    bodyLen: document.body.innerText.length,
    has产品: document.body.innerText.includes('产品'),
    hasBOM: document.body.innerText.includes('BOM'),
    has暂无: document.body.innerText.includes('暂无'),
    tail: document.body.innerText.slice(-400),
  }));
  console.log('[PROBE2]', JSON.stringify(info, null, 1));
  await page.screenshot({ path: 'e2e/screenshots/qinyan-260908/probe2.png', fullPage: true });
});
