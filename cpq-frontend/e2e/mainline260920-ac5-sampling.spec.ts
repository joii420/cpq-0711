// task-260920 · 主线亲验 AC-5 ① 分段计时采样（R4 窗口，后台正在算 0628 组时；5296 → 8130 → cpq_db_0724）
// 每个料号：点料号链接 → 抽屉自动 compute-now → 轮询 /row 到 READY → 拉详情 → 抽屉渲染完成。逐段记网络时刻。
// 只点这几个料号（均为 0628 组末尾、S-2 未选用）；每个只触发一次点击即算。
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

const BASE = process.env.PW_BASE_URL!;
const EV = process.env.MV_EVIDENCE!;
const MATS = (process.env.MV_AC5_MATS || 'T260907T-B01830,T260907T-B01831,T260907T-B01832,T260907T-B01833,T260907T-B01834').split(',');

const tbodyRows = (p: Page) => p.locator('.ant-table-tbody tr.ant-table-row');

test('AC-5 ① 分段计时采样', async ({ page }) => {
  fs.mkdirSync(EV, { recursive: true });
  const lr = await page.request.post(`${BASE}/api/cpq/auth/login`, { data: { username: 'admin', password: 'Admin@2026' } });
  expect(lr.ok()).toBeTruthy();
  await page.goto('/pricing/reviews');
  await expect(tbodyRows(page).first()).toBeVisible();
  await page.waitForLoadState('networkidle').catch(() => undefined);
  await page.waitForTimeout(1500);

  const samples: unknown[] = [];
  for (const mat of MATS) {
    await page.getByPlaceholder('搜索客户 / 料号 / 料号名称').fill(mat);
    await page.getByText('查询', { exact: true }).click();
    const row = tbodyRows(page).filter({ hasText: mat }).first();
    await expect(row).toBeVisible();
    await page.waitForTimeout(1200);
    const before = (await row.innerText()).replace(/\s+/g, ' ');
    if (!/未计算/.test(before)) { samples.push({ mat, skipped: '点击前已不是「未计算」', before }); continue; }

    const ev: { t: number; what: string }[] = [];
    let t0 = 0;
    const onReq = (r: any) => { const u = r.url(); if (/compute-now$/.test(u)) ev.push({ t: Date.now() - t0, what: 'REQ compute-now' }); else if (/\/reviews\/[0-9a-f-]{36}$/.test(u) && r.method() === 'GET') ev.push({ t: Date.now() - t0, what: 'REQ detail' }); };
    const onResp = async (s: any) => {
      const u = s.url();
      if (/compute-now$/.test(u)) ev.push({ t: Date.now() - t0, what: `RESP compute-now ${s.status()}` });
      else if (/\/reviews\/[0-9a-f-]{36}\/row$/.test(u)) { let b = ''; try { b = (await s.json())?.data?.budgetStatus ?? (await s.json())?.budgetStatus; } catch { /* ignore */ } ev.push({ t: Date.now() - t0, what: `RESP row ${b}` }); }
      else if (/\/reviews\/[0-9a-f-]{36}$/.test(u) && s.request().method() === 'GET') ev.push({ t: Date.now() - t0, what: `RESP detail ${s.status()}` });
    };
    page.on('request', onReq);
    page.on('response', onResp);
    t0 = Date.now();
    await row.getByText(mat, { exact: true }).click();
    const body = page.locator('.ant-drawer-body').last();
    await expect(body).toBeVisible();
    let renderedMs: number | null = null;
    while (Date.now() - t0 < 30_000) {
      const txt = await body.innerText().catch(() => '');
      if (/缺数据/.test(txt) && /二、能不能接受/.test(txt)) { renderedMs = Date.now() - t0; break; }
      await page.waitForTimeout(50);
    }
    const drawerText = (await body.innerText()).replace(/\s+/g, ' ').slice(0, 300);
    page.off('request', onReq);
    page.off('response', onResp);
    await page.screenshot({ path: path.join(EV, `AC5-${mat}.png`) });
    await page.keyboard.press('Escape');
    await page.waitForTimeout(1000);
    const after = (await tbodyRows(page).filter({ hasText: mat }).first().innerText()).replace(/\s+/g, ' ');
    samples.push({ mat, clickAt: new Date(t0).toISOString(), renderedMs, events: ev, drawerText, before, after });
    fs.writeFileSync(path.join(EV, 'ac5-samples.json'), JSON.stringify(samples, null, 2));
    await page.waitForTimeout(2500); // 样本之间错开，让点击时刻落在后台试算周期的不同位置
  }
  fs.writeFileSync(path.join(EV, 'ac5-samples.json'), JSON.stringify(samples, null, 2));
});
