// task-260920 · 主线亲验 AC-20 ④ 界面侧：点「计算」时后端回 409 REVIEW_NOT_PENDING ⇒ 界面提示「该价格版本已被新版本取代」、不白屏
// compute-now 请求被 route 截下并以真实信封格式回 409（不到达后端、不写库）；列表 / 单行读取走真实后端（5296 → 8130）
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
const BASE = process.env.PW_BASE_URL!;
const EV = process.env.MV_EVIDENCE!;
const MAT = process.env.MV_AC20_MAT || 'T260907T-B01810';
test('AC-20 ④ 界面提示', async ({ page }) => {
  const lr = await page.request.post(`${BASE}/api/cpq/auth/login`, { data: { username: 'admin', password: 'Admin@2026' } });
  expect(lr.ok()).toBeTruthy();
  let intercepted = 0;
  await page.route('**/api/cpq/price-adjust/reviews/*/compute-now', async (route) => {
    intercepted++;
    await route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ code: 409, message: '该料号已不是待处理状态', data: { code: 'REVIEW_NOT_PENDING', invalidItems: [{ reviewId: 'x', materialNo: MAT, reason: '状态已变化(VOIDED)' }] } }) });
  });
  const rows = page.locator('.ant-table-tbody tr.ant-table-row');
  await page.goto('/pricing/reviews');
  await expect(rows.first()).toBeVisible();
  await page.waitForLoadState('networkidle').catch(() => undefined);
  await page.waitForTimeout(1500);
  await page.getByPlaceholder('搜索客户 / 料号 / 料号名称').fill(MAT);
  await page.getByText('查询', { exact: true }).click();
  const row = rows.filter({ hasText: MAT }).first();
  await expect(row).toBeVisible();
  await page.waitForTimeout(1000);
  const before = (await row.innerText()).replace(/\s+/g, ' ');
  const msgs: string[] = [];
  const t0 = Date.now();
  await row.getByText('计算', { exact: true }).click();
  while (Date.now() - t0 < 4000) {
    for (const t of await page.locator('.ant-message').allInnerTexts()) { const s = t.trim(); if (s && !msgs.includes(s)) msgs.push(s); }
    if (msgs.some((m) => /已被新版本取代/.test(m))) break;
    await page.waitForTimeout(100);
  }
  await page.screenshot({ path: path.join(EV, 'AC20-4-界面提示.png') });
  const bodyText = await page.locator('body').innerText();
  const out = { mat: MAT, before, intercepted, messages: msgs, msMessageSeen: Date.now() - t0, blankPage: bodyText.trim().length < 50, has500: /500|Internal Server Error/.test(bodyText) };
  fs.writeFileSync(path.join(EV, 'ac20-ui.json'), JSON.stringify(out, null, 2));
});
