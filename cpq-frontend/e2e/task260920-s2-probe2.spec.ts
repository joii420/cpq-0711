/** task-260920 S-2 · AC-6 探针（写请求全部拦截，不落库）：勾选 READY 行 → 「重算预算」发什么、是否有确认框。 */
import { test } from '@playwright/test';
import { apiLogin, guardWrites, shot, evid, gotoReviews, search, rowOf } from './task260920-s2.helpers';
test('probe2 · 重算预算', async ({ page }) => {
  test.setTimeout(90_000);
  const log: string[] = []; const L = (s: string) => { log.push(s); console.log(s); };
  await apiLogin(page);
  const blocked = await guardWrites(page, []);
  await gotoReviews(page);
  await search(page, 'PERFHOT-B00291');
  const row = rowOf(page, 'PERFHOT-B00291', 'V26092102');
  L(`row visible=${await row.isVisible()}`);
  const cb = row.locator('input[type=checkbox]').first();
  await cb.check({ timeout: 5000 }).catch(e => L('check failed ' + String(e).slice(0, 150)));
  L(`checked=${await cb.isChecked().catch(() => 'n/a')} 已选=${await page.getByText(/已选\s*\d+\s*项/).first().innerText().catch(() => '?')}`);
  const btn = page.getByRole('button', { name: /重算预算/ }).first();
  L(`重算预算 count=${await page.getByRole('button', { name: /重算预算/ }).count()} enabled=${await btn.isEnabled().catch(() => 'n/a')} text=${await btn.innerText().catch(() => '?')}`);
  await btn.hover().catch(() => {}); await page.waitForTimeout(600);
  L(`tooltip=${(await page.locator('.ant-tooltip:visible').allInnerTexts()).join('|')}`);
  await shot(page, 'probe2-1-before-click');
  await btn.click({ timeout: 5000 }).catch(e => L('click failed ' + String(e).slice(0, 150)));
  await page.waitForTimeout(1500);
  await shot(page, 'probe2-2-after-click');
  const dlg = page.locator('.ant-modal:visible, .ant-popover:visible, .ant-popconfirm:visible');
  L(`dialogs=${await dlg.count()} text=${JSON.stringify((await dlg.allInnerTexts()).map(s => s.replace(/\s+/g, ' ').slice(0, 300)))}`);
  L(`dialog buttons=${JSON.stringify((await dlg.getByRole('button').allInnerTexts()).map(s => s.replace(/\s+/g, '')))}`);
  L(`blocked=${JSON.stringify(blocked)}`);
  evid('probe2-log.txt', log.join('\n'));
});
