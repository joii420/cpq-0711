import { test } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';
const QID = process.env.PROBE_QID!;
test('probe edit dom', async ({ page }) => {
  test.setTimeout(300000);
  await loginAsAdmin(page);
  await page.goto(`/quotations/${QID}/edit`);
  await page.waitForLoadState('networkidle'); await page.waitForTimeout(5000);
  const next = page.getByRole('button', { name: /下\s*一\s*步/ });
  if (await next.count() && await next.first().isEnabled().catch(()=>false)) { await next.first().click(); await page.waitForTimeout(6000); }
  const info = await page.evaluate(() => {
    const q = (s: string) => document.querySelectorAll(s).length;
    // 找承载数据行的容器：有 input 的 tr / div[role=row]
    const trs = Array.from(document.querySelectorAll('tr'));
    const trWithInput = trs.filter(r => r.querySelector('input')).length;
    const firstTab = document.querySelector('.ant-tabs-tab, [role=tab]');
    return {
      tables: q('table'), antTable: q('.ant-table'), tr: trs.length, trWithInput,
      roleRow: q('[role=row]'), inputs: q('input'), antTabs: q('.ant-tabs-tab'), roleTab: q('[role=tab]'),
      firstTabText: firstTab ? (firstTab as HTMLElement).innerText : null,
      // 页签标签怎么渲染的
      tabLike: Array.from(document.querySelectorAll('div,span,button'))
        .filter(e => ['产品','BOM','材质元素','加工费'].includes((e as HTMLElement).innerText?.trim()))
        .map(e => `${e.tagName}.${(e as HTMLElement).className}`.slice(0,70)).slice(0,6),
    };
  });
  console.log('[EDIT-DOM] ' + JSON.stringify(info, null, 1));
  await page.screenshot({ path: 'e2e/screenshots/qinyan-260908/probe-edit-step2.png', fullPage: true });
});
