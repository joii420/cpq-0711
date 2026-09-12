/** AC-9 追查：点「保存草稿」到底有没有发出保存请求、后端返回什么。 */
import { test, expect } from '@playwright/test';
import { login, openStep2, switchView, cardOf, clickTab, dumpTable, colIdx, rowByPartNo, num, shot } from './repair260911-helpers';

test('savetrace: 保存草稿的网络往返', async ({ page }) => {
  test.setTimeout(300_000);
  const calls: string[] = [];
  page.on('request', (r) => { if (r.url().includes('/api/')) calls.push(`REQ ${r.method()} ${r.url().replace(/^https?:\/\/[^/]+/, '')}`); });
  page.on('response', async (r) => {
    if (!r.url().includes('/api/')) return;
    let body = '';
    if (r.request().method() !== 'GET') { body = (await r.text().catch(() => '')).slice(0, 300); }
    calls.push(`RES ${r.status()} ${r.request().method()} ${r.url().replace(/^https?:\/\/[^/]+/, '')} ${body}`);
  });
  page.on('console', (m) => { if (m.type() === 'error') calls.push(`CONSOLE-ERR ${m.text().slice(0, 200)}`); });

  await login(page);
  await openStep2(page);
  await switchView(page, '核价单');
  const card = cardOf(page);
  await clickTab(card, 'BOM');
  const d = await dumpTable(card);
  console.log('保存前 00081 =', num(rowByPartNo(d, '00081')[colIdx(d, '物料成本')]));

  calls.length = 0;
  const saveBtn = page.locator('button').filter({ hasText: /保\s*存\s*草\s*稿/ }).first();
  await expect(saveBtn).toBeVisible({ timeout: 20_000 });
  const seen: string[] = [];
  const poll = setInterval(async () => {
    const t = await page.locator('.ant-message-notice, .ant-notification-notice, .ant-modal-confirm').allInnerTexts().catch(() => []);
    for (const x of t) { const v = x.replace(/\s+/g, ' ').trim(); if (v && !seen.includes(v)) seen.push(v); }
  }, 300);
  await saveBtn.click();
  await page.waitForTimeout(20_000);
  clearInterval(poll);
  console.log('点击后抓到的浮层文案:', JSON.stringify(seen));
  await shot(page, 'savetrace-after-click');

  console.log('=== 点击后 20s 内的 /api 往返 ===');
  for (const c of calls) console.log('  ', c);
  // 页面上的提示条
  const msgs = await page.locator('.ant-message, .ant-notification, .ant-alert').allInnerTexts().catch(() => []);
  console.log('页面提示:', JSON.stringify(msgs.map((s) => s.replace(/\s+/g, ' ').trim())));
  expect(calls.length, '点保存后应至少有一次 /api 往返').toBeGreaterThan(0);
});
