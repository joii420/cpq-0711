/**
 * repair-260911 · S1 探针（只读）
 * 目的：在写断言之前先把「核价单 → 卡片 S0001 → BOM 页签」的真实 DOM 形态摸清楚，
 *       并把当前（未修复态）的「物料成本」列实际值原样打印出来 —— 这既是 DOM 侦察，
 *       也是还原实验 2 的前半段「红基线」。
 * 纪律：全程只读，不点保存、不改任何输入框。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const __filenameLocal = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filenameLocal);

const QUOTATION_ID = '405ab315-3ee6-43e0-8a4d-9837b762ab81';
const OUT_DIR = process.env.R260911_OUT || path.join(__dirnameLocal, 'repair260911-out');
fs.mkdirSync(OUT_DIR, { recursive: true });

async function login(page: Page) {
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|change-password)/, { timeout: 30_000 });
  if (page.url().includes('/change-password')) {
    await page.goto('/dashboard');
    await page.waitForLoadState('networkidle');
  }
}

async function dumpTexts(page: Page, selector: string): Promise<string[]> {
  const loc = page.locator(selector);
  const n = await loc.count();
  const out: string[] = [];
  for (let i = 0; i < n; i++) out.push(((await loc.nth(i).innerText().catch(() => '')) || '').replace(/\s+/g, ' ').trim());
  return out;
}

test('probe: 核价单卡片 S0001 → BOM 页签 DOM 形态 + 物料成本列当前值', async ({ page }) => {
  const consoleErrors: string[] = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });

  await login(page);
  await page.goto(`/quotations/${QUOTATION_ID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(4000);

  const report: any = { url: page.url(), steps: [] };

  // step1 → step2
  for (let i = 0; i < 3; i++) {
    const next = page.locator('button', { hasText: /^\s*下一步\s*$/ }).first();
    if (await next.count() > 0 && await next.isEnabled().catch(() => false)) {
      await next.click().catch(() => {});
      await page.waitForTimeout(2500);
    } else break;
  }
  await page.waitForTimeout(2000);

  report.segmented = await dumpTexts(page, '.ant-segmented-item');
  report.tabsBefore = await dumpTexts(page, '.ant-tabs-tab');
  await page.screenshot({ path: path.join(OUT_DIR, 'probe-01-step2.png'), fullPage: true }).catch(() => {});

  // 切核价单
  const seg = page.locator('.ant-segmented-item', { hasText: /核价单/ }).first();
  if (await seg.count() > 0) {
    await seg.click();
    await page.waitForTimeout(4000);
    report.clickedCosting = true;
  } else {
    const tab = page.locator('.ant-tabs-tab', { hasText: /核价单/ }).first();
    if (await tab.count() > 0) { await tab.click(); await page.waitForTimeout(4000); report.clickedCosting = 'tab'; }
    else report.clickedCosting = false;
  }
  await page.screenshot({ path: path.join(OUT_DIR, 'probe-02-costing.png'), fullPage: true }).catch(() => {});

  report.tabsAfter = await dumpTexts(page, '.ant-tabs-tab');
  report.cardTitles = await dumpTexts(page, '.ant-card-head-title');

  // 点 BOM 页签
  const bomTab = page.locator('.ant-tabs-tab', { hasText: /^\s*BOM/ }).first();
  report.bomTabCount = await bomTab.count();
  if (report.bomTabCount > 0) {
    await bomTab.click().catch(() => {});
    await page.waitForTimeout(3000);
  }
  await page.screenshot({ path: path.join(OUT_DIR, 'probe-03-bom.png'), fullPage: true }).catch(() => {});

  // 表格结构
  const tables = page.locator('.ant-table');
  report.tableCount = await tables.count();
  report.tables = [];
  for (let t = 0; t < Math.min(report.tableCount, 4); t++) {
    const tbl = tables.nth(t);
    const headers = await dumpTexts(page, `.ant-table >> nth=${t} >> .ant-table-thead th`);
    const rows = tbl.locator('.ant-table-tbody tr.ant-table-row');
    const rc = await rows.count();
    const rowData: string[][] = [];
    for (let r = 0; r < rc; r++) {
      const cells = rows.nth(r).locator('td');
      const cn = await cells.count();
      const cellVals: string[] = [];
      for (let c = 0; c < cn; c++) {
        const td = cells.nth(c);
        const input = td.locator('input');
        let v = '';
        if (await input.count() > 0) v = '[input]' + ((await input.first().inputValue().catch(() => '')) || '');
        else v = ((await td.innerText().catch(() => '')) || '').replace(/\s+/g, ' ').trim();
        cellVals.push(v);
      }
      rowData.push(cellVals);
    }
    const summary = await dumpTexts(page, `.ant-table >> nth=${t} >> .ant-table-summary td`);
    report.tables.push({ index: t, headers, rowCount: rc, rowData, summary });
  }

  report.consoleErrors = consoleErrors.slice(0, 20);
  fs.writeFileSync(path.join(OUT_DIR, 'probe-report.json'), JSON.stringify(report, null, 2));
  console.log(JSON.stringify(report, null, 2).slice(0, 12000));
  expect(report.tableCount, '页面上应至少有一张表格').toBeGreaterThan(0);
});
