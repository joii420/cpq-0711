// task-260920 · 主线亲验（开发库 R1 未计算窗口；5296 → 8130 → cpq_db_0724）。只点 2 次「计算」；等首屏加载完再输入搜索词。
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
const BASE = process.env.PW_BASE_URL!;
const EV = process.env.MV_EVIDENCE!;
const obs: Record<string, unknown> = {};
const save = () => fs.writeFileSync(path.join(EV, 'obs.json'), JSON.stringify(obs, null, 2));
const tbodyRows = (p: Page) => p.locator('.ant-table-tbody tr.ant-table-row');
async function api(p: Page, qs: string) { const j = await (await p.request.get(`${BASE}/api/cpq/price-adjust/reviews?${qs}`)).json(); return j.data ?? j; }
async function search(p: Page, kw: string) {
  await p.getByPlaceholder('搜索客户 / 料号 / 料号名称').fill(kw);
  await p.getByText('查询', { exact: true }).click();
  await expect(tbodyRows(p).filter({ hasText: kw }).first()).toBeVisible();
  await p.waitForTimeout(1500);
}
async function drawerCompute(p: Page, mat: string, tag: string) {
  await search(p, mat);
  const t0 = Date.now();
  await tbodyRows(p).filter({ hasText: mat }).getByText(mat, { exact: true }).click();
  const body = p.locator('.ant-drawer-body').last();
  await expect(body).toBeVisible();
  let doneMs: number | null = null;
  while (Date.now() - t0 < 30_000) {
    const txt = await body.innerText().catch(() => '');
    if (/二、能不能接受/.test(txt) && !/计算中|正在计算/.test(txt) && /产品总价|比对列/.test(txt)) { doneMs = Date.now() - t0; break; }
    await p.waitForTimeout(100);
  }
  const text = (await body.innerText()).replace(/\s+/g, ' ');
  await p.screenshot({ path: path.join(EV, `${tag}-抽屉.png`) });
  await p.keyboard.press('Escape');
  await p.waitForTimeout(1200);
  const rowAfter = (await tbodyRows(p).filter({ hasText: mat }).first().innerText()).replace(/\s+/g, ' ');
  const apiRow = await api(p, `page=1&size=20&keyword=${mat}`);
  return { mat, doneMs, drawerText: text.slice(0, 900), rowAfter, apiRow: (apiRow.content || []).map((r: any) => ({ m: r.materialNo, b: r.budgetStatus, qc: r.quoteCostCurrent, qa: r.quoteCostAdjusted, cc: r.costingCost, d: r.diffAdjusted })) };
}
test('主线亲验 · 开发库 R1 窗口', async ({ page }) => {
  fs.mkdirSync(EV, { recursive: true });
  obs.startedAt = new Date().toISOString();
  const lr = await page.request.post(`${BASE}/api/cpq/auth/login`, { data: { username: 'admin', password: 'Admin@2026' } });
  obs.login = lr.status();
  await page.goto('/pricing/reviews');
  await expect(tbodyRows(page).first()).toBeVisible();
  await page.waitForLoadState('networkidle').catch(() => undefined);
  await page.waitForTimeout(2000);
  // 全量首屏（AC-1 表头 / 原型比对 状态 1）
  obs.full = { header: await page.getByText(/共\s*\d+\s*条，其中/).first().innerText().catch(() => '(无表头)'), api: await api(page, 'page=1&size=20').then((d: any) => ({ totalElements: d.totalElements, notComputedTotal: d.notComputedTotal })) };
  await page.screenshot({ path: path.join(EV, '01-全量首屏-未计算态.png') });
  // AC-1 显示语义：未计算行（B01843）
  await search(page, 'T260907T-B01843');
  const r1843 = tbodyRows(page).filter({ hasText: 'T260907T-B01843' }).first();
  obs.ac1 = {
    row: (await r1843.innerText()).replace(/\s+/g, ' '),
    tagText: await r1843.locator('.ant-tag').allInnerTexts(),
    hasComputeLink: await r1843.getByText('计算', { exact: true }).count(),
    hasSpinner: await r1843.locator('.ant-spin').count(),
    header: await page.getByText(/共\s*\d+\s*条，其中/).first().innerText().catch(() => '(无表头)'),
    api: await api(page, 'page=1&size=20&keyword=T260907T-B01843').then((d: any) => ({ totalElements: d.totalElements, notComputedTotal: d.notComputedTotal, row: d.content?.[0] && { b: d.content[0].budgetStatus, qc: d.content[0].quoteCostCurrent, qa: d.content[0].quoteCostAdjusted, cc: d.content[0].costingCost, d: d.content[0].diffAdjusted } })),
  };
  await page.screenshot({ path: path.join(EV, '02-AC1-未计算行.png') });
  save();
  // AC-4：只看标红（全量）
  await page.getByPlaceholder('搜索客户 / 料号 / 料号名称').fill('');
  await page.locator('.ant-checkbox-wrapper').filter({ hasText: '只看标红' }).click();
  await page.getByText('查询', { exact: true }).click();
  await page.waitForTimeout(2500);
  obs.ac4 = {
    alert: await page.locator('.ant-alert').filter({ hasText: '尚未计算' }).first().innerText().catch(() => '(无提示)'),
    notComputedTagsInResult: await tbodyRows(page).getByText('未计算', { exact: true }).count(),
    rowsShown: await tbodyRows(page).count(),
    api: await api(page, 'page=1&size=20&breachedOnly=true').then((d: any) => ({ totalElements: d.totalElements, notComputedTotal: d.notComputedTotal, excludedByNotComputed: d.excludedByNotComputed })),
  };
  await page.screenshot({ path: path.join(EV, '03-AC4-只看标红.png') });
  await page.locator('.ant-checkbox-wrapper').filter({ hasText: '只看标红' }).click();
  await page.getByText('查询', { exact: true }).click();
  await page.waitForTimeout(2000);
  save();
  // AC-5 ①（0628 末尾料号，缺数据两侧）、AC-5 ②（0842 有值料号）
  obs.ac5a = await drawerCompute(page, 'T260907T-B01842', '04-AC5-1');
  save();
  obs.ac5b = await drawerCompute(page, 'PERFHOT-B00221', '05-AC5-2');
  obs.finishedAt = new Date().toISOString();
  save();
});
