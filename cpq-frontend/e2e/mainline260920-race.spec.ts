// task-260920 · 主线复现：进页即输搜索词查询时，列表是否被过期响应覆盖（测试库夹具，5295 → 8295，只读）
import { test } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
const BASE = process.env.PW_BASE_URL!;
const EV = process.env.MV_EVIDENCE!;
const KW = 'T260920-43482dfe';
test('进页即搜 × 5', async ({ browser }) => {
  const out: unknown[] = [];
  for (let i = 1; i <= 5; i++) {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    const r = await page.request.post(`${BASE}/api/cpq/auth/login`, { data: { username: 't260920-accept-43482dfe', password: 'T260920@Accept1' } });
    if (!r.ok()) throw new Error('login ' + r.status());
    const log: string[] = [];
    const t0 = Date.now();
    page.on('request', (q) => { if (/\/price-adjust\/reviews\?/.test(q.url())) log.push(`${Date.now() - t0}ms REQ ${decodeURIComponent(q.url().replace(/^.*reviews\?/, ''))}`); });
    page.on('response', (s) => { if (/\/price-adjust\/reviews\?/.test(s.url())) log.push(`${Date.now() - t0}ms RESP ${decodeURIComponent(s.url().replace(/^.*reviews\?/, ''))}`); });
    await page.goto('/pricing/reviews');
    await page.getByPlaceholder('搜索客户 / 料号 / 料号名称').fill(KW);
    await page.getByText('查询', { exact: true }).click();
    await page.waitForTimeout(4000);
    const allRows = await page.locator('.ant-table-tbody tr.ant-table-row').count();
    const kwRows = await page.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: KW }).count();
    const header = await page.getByText(/共\s*\d+\s*条，其中/).first().innerText().catch(() => '(无表头)');
    const pagerTotal = await page.locator('.ant-pagination-total-text').first().innerText().catch(() => '(无分页总数)');
    const api = await (await page.request.get(`${BASE}/api/cpq/price-adjust/reviews?page=1&size=20&status=PENDING&keyword=${KW}`)).json();
    const apiTotal = (api.data ?? api).totalElements;
    out.push({ i, allRows, kwRows, header, pagerTotal, apiTotal, ok: allRows === kwRows && pagerTotal.includes(String(apiTotal)), log });
    await ctx.close();
  }
  fs.writeFileSync(path.join(EV, process.env.MV_OUT || 'race-repro.json'), JSON.stringify(out, null, 2));
});
