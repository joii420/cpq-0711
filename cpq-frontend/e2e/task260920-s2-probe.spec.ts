/** task-260920 S-2 · 选择器探针（只读：拦截一切非 GET；不点任何写按钮）。定位 R1-0 卡在哪一步。 */
import { test } from '@playwright/test';
import { apiLogin, guardWrites, shot, evid, CUST } from './task260920-s2.helpers';
test('probe · 定价页 → 正泰 → 价格调整策略 → 找「立即生成」', async ({ page }) => {
  test.setTimeout(120_000);
  const log: string[] = []; const t0 = Date.now(); const L = (s: string) => { log.push(`${Date.now() - t0}ms ${s}`); console.log(s); };
  await apiLogin(page); L('login ok');
  const blocked = await guardWrites(page, []);
  await page.goto('/pricing', { timeout: 30_000 }); L(`goto /pricing url=${page.url()}`);
  await page.waitForLoadState('networkidle', { timeout: 15_000 }).catch(() => L('networkidle timeout'));
  await shot(page, 'probe-1-pricing');
  evid('probe-1-body.txt', await page.locator('body').innerText({ timeout: 5000 }).catch(e => String(e)));
  const inputs = await page.locator('input').evaluateAll(els => els.map(e => (e as HTMLInputElement).placeholder));
  L(`inputs placeholders=${JSON.stringify(inputs)}`);
  L(`ant-list-item count=${await page.locator('.ant-list-item').count()}`);
  await page.locator('input[placeholder="搜索客户"]').first().fill(CUST.name);
  await page.locator('input[placeholder="搜索客户"]').first().press('Enter');
  await page.waitForTimeout(2000);
  L(`after search ant-list-item count=${await page.locator('.ant-list-item').count()} texts=${JSON.stringify((await page.locator('.ant-list-item').allInnerTexts()).map(s => s.replace(/\s+/g, ' ').slice(0, 60)))}`);
  const item = page.locator('.ant-list-item, tr.ant-table-row, [class*=customer]').filter({ hasText: CUST.name }).first();
  L(`正泰 candidates=${await page.getByText(CUST.name).count()}`);
  await item.click({ timeout: 8000 }).catch(e => L(`click 正泰 failed: ${String(e).slice(0, 200)}`));
  await page.waitForTimeout(1500);
  await shot(page, 'probe-2-after-customer');
  L(`tabs=${JSON.stringify(await page.locator('.ant-tabs-tab').allInnerTexts())}`);
  await page.locator('.ant-tabs-tab').filter({ hasText: '价格调整策略' }).first().click({ timeout: 8000 }).catch(e => L(`tab click failed: ${String(e).slice(0, 200)}`));
  await page.waitForTimeout(1500);
  await shot(page, 'probe-3-tab');
  L(`buttons=${JSON.stringify((await page.getByRole('button').allInnerTexts()).map(s => s.replace(/\s+/g, '')).slice(0, 60))}`);
  L(`立即生成 count=${await page.getByRole('button', { name: /立即生成/ }).count()}`);
  // 点「立即生成」看确认框（写请求全部被 guardWrites 拦下 abort，不会落库）
  await page.getByRole('button', { name: /立即生成/ }).first().click({ timeout: 8000 });
  await page.waitForTimeout(1500);
  await shot(page, 'probe-4-after-generate-click');
  const dlg = page.locator('.ant-modal:visible, .ant-popover:visible, .ant-popconfirm:visible, .ant-modal-confirm:visible');
  L(`dialogs=${await dlg.count()} text=${JSON.stringify((await dlg.allInnerTexts()).map(s => s.replace(/\s+/g, ' ').slice(0, 300)))}`);
  L(`dialog buttons=${JSON.stringify((await dlg.getByRole('button').allInnerTexts()).map(s => s.replace(/\s+/g, '')))}`);
  L(`blocked(after click)=${JSON.stringify(blocked)}`);
  const ok = dlg.getByRole('button', { name: /确\s*定|确\s*认|生\s*成/ }).last();
  if (await ok.count()) { await ok.click({ timeout: 5000 }).catch(e => L('confirm click failed ' + String(e).slice(0, 120))); await page.waitForTimeout(1500); }
  await shot(page, 'probe-5-after-confirm');
  L(`blocked(after confirm)=${JSON.stringify(blocked)}`);
  evid('probe-log.txt', log.join('\n'));
});
