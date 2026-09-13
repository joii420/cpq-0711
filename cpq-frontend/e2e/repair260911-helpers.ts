/**
 * repair-260911 · S1 共用导航/取值助手
 * 纪律：只读为主；唯一的写入点是 AC-9 的「组成用量」单元格，且由 spec 自己在 finally 还原。
 */
import { Page, Locator, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

export const QUOTATION_ID = '405ab315-3ee6-43e0-8a4d-9837b762ab81';
export const QUOTATION_NO = 'QT-20260911-0010';
export const LINE_ITEM_ID = 'b7066093-ebc9-4a78-b1e4-4ca47298b73e';
export const CARD_SALES_NO = 'S0001';

export const OUT_DIR = process.env.R260911_OUT || path.join(process.cwd(), 'e2e', 'repair260911-out');
fs.mkdirSync(OUT_DIR, { recursive: true });

export async function shot(page: Page, name: string) {
  const f = path.join(OUT_DIR, `${name}.png`);
  await page.screenshot({ path: f, fullPage: true }).catch(() => {});
  console.log(`[shot] ${f}`);
  return f;
}

export async function login(page: Page) {
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|change-password)/, { timeout: 30_000 });
  if (page.url().includes('/change-password')) {
    await page.goto('/dashboard');
    await page.waitForLoadState('networkidle');
  }
  expect(page.url(), '登录后不应停留在 /login').not.toContain('/login');
}

/** 打开报价单编辑页并进到 step2（添加产品）。 */
export async function openStep2(page: Page) {
  await page.goto(`/quotations/${QUOTATION_ID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3500);
  const next = page.locator('button', { hasText: '下一步' }).first();
  await expect(next, 'step1 应有可点的「下一步」').toBeEnabled({ timeout: 20_000 });
  await next.click();
  await page.waitForTimeout(6000);
  await expect(page.locator('[class*=product-card]').first(), 'step2 应渲染出产品卡片').toBeVisible({ timeout: 30_000 });
}

/** 切到「核价单」或「报价单」视图。 */
export async function switchView(page: Page, label: '报价单' | '核价单' | '比对视图') {
  const seg = page.locator('.ant-segmented-item', { hasText: label }).first();
  await expect(seg, `应有「${label}」分段入口`).toBeVisible({ timeout: 20_000 });
  await seg.click();
  await page.waitForTimeout(6000);
}

/** 定位销售料号 = S0001 的产品卡片。 */
export function cardOf(page: Page, salesNo = CARD_SALES_NO): Locator {
  return page.locator('[class*=product-card]').filter({ hasText: `销售料号: ${salesNo}` }).first();
}

/** 点卡片内的某个页签按钮（BOM / 材质元素 / …）。 */
export async function clickTab(card: Locator, tabName: string) {
  const btn = card.locator('button').filter({ hasText: new RegExp(`^\\s*${tabName}\\s*$`) }).first();
  await expect(btn, `卡片内应有「${tabName}」页签按钮`).toBeVisible({ timeout: 20_000 });
  await btn.click();
  await card.page().waitForTimeout(4000);
}


/**
 * 截「能看见物料成本列」的图。
 * 🚨 为什么不能直接 page.screenshot({fullPage:true})：BOM 表在横向滚动容器里，物料成本是最右侧列，
 *    默认视口下它根本不在像素里 —— 实测未修复态与修复后的整页截图**逐字节相同**（md5 一致），
 *    那种截图当证据等于 testing.md §5.5 的「验了一个恒真的东西」。
 */
export async function shotTable(card: Locator, name: string) {
  const page = card.page();
  await card.locator('table').first().evaluate((el) => {
    let n: HTMLElement | null = el as HTMLElement;
    while (n) {
      if (n.scrollWidth > n.clientWidth + 4) { n.scrollLeft = n.scrollWidth; return; }
      n = n.parentElement;
    }
  });
  await page.waitForTimeout(800);
  const f = path.join(OUT_DIR, `${name}.png`);
  await card.screenshot({ path: f }).catch(async () => { await page.screenshot({ path: f }); });
  console.log(`[shot-table] ${f}`);
  return f;
}

export interface TableDump {
  headers: string[];
  rows: string[][];
  footer: string;
}

/** 抓卡片内第一张表：表头 / 各行单元格（input 取 value）/ tfoot 文本。 */
export async function dumpTable(card: Locator): Promise<TableDump> {
  const t = card.locator('table').first();
  await expect(t, '卡片内应有表格').toBeVisible({ timeout: 20_000 });
  const headers = (await t.locator('thead th').allInnerTexts()).map((s) => s.replace(/\s+/g, ' ').trim());
  const rowsLoc = t.locator('tbody tr');
  const rc = await rowsLoc.count();
  const rows: string[][] = [];
  for (let r = 0; r < rc; r++) {
    const cells = rowsLoc.nth(r).locator('td');
    const cn = await cells.count();
    const vals: string[] = [];
    for (let c = 0; c < cn; c++) {
      const td = cells.nth(c);
      const inp = td.locator('input');
      if (await inp.count() > 0) vals.push(((await inp.first().inputValue().catch(() => '')) || '').trim());
      else vals.push(((await td.innerText().catch(() => '')) || '').replace(/\s+/g, ' ').trim());
    }
    rows.push(vals);
  }
  const footer = ((await t.locator('tfoot').innerText().catch(() => '')) || '').replace(/\s+/g, ' ').trim();
  return { headers, rows, footer };
}

export function colIdx(d: TableDump, header: string): number {
  const i = d.headers.findIndex((h) => h === header);
  if (i < 0) throw new Error(`表头里找不到「${header}」，实际表头=${JSON.stringify(d.headers)}`);
  return i;
}

/** 按「料号」列定位行（料号列 = 表头 '料号'）。 */
export function rowByPartNo(d: TableDump, partNo: string): string[] {
  const i = colIdx(d, '料号');
  const hit = d.rows.filter((r) => r[i] === partNo);
  if (hit.length !== 1) {
    throw new Error(`料号 ${partNo} 命中 ${hit.length} 行（期望恰好 1 行）；实际料号列=${JSON.stringify(d.rows.map((r) => r[i]))}`);
  }
  return hit[0];
}

export function num(cell: string): number {
  const cleaned = (cell || '').replace(/[¥,\s]/g, '');
  const v = Number(cleaned);
  if (Number.isNaN(v)) throw new Error(`单元格「${cell}」无法解析为数字`);
  return v;
}

export function saveJson(name: string, obj: unknown) {
  const f = path.join(OUT_DIR, name);
  fs.writeFileSync(f, JSON.stringify(obj, null, 2));
  console.log(`[json] ${f}`);
}
