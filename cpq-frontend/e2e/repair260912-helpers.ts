/** repair-260912 · S1 共用助手。纪律：纯只读，不点保存、不改任何数据。 */
import { Page, Locator, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

export const QID = '658ad486-dabe-4770-a851-73e4005ce66f';   // QT-20260912-0011
export const QNO = 'QT-20260912-0011';
export const QID_0010 = '405ab315-3ee6-43e0-8a4d-9837b762ab81'; // QT-20260911-0010（AC-6 用：BOM 总计确为 0）

/** 问题说明 ⑥ 的期望值表（已独立复核 = costing_card_values 各页签 subtotal）。 */
export const EXPECT: Record<string, Record<string, number>> = {
  S0001: { '元素小计': 489985,  '物料小计': 5438667.5,   '加工费': 5.8,  '单价': 5438673.3 },
  S0004: { '元素小计': 632046,  '物料小计': 16459679.88, '加工费': 3.8,  '单价': 16459683.68 },
  S0008: { '元素小计': 677615,  '物料小计': 38485323.75, '加工费': 2.05, '单价': 38485325.8 },
  S0012: { '元素小计': 641025,  '物料小计': 34249252.5,  '加工费': 4.7,  '单价': 34249257.2 },
};

export const OUT = process.env.R260912_OUT || path.join(process.cwd(), 'e2e', 'repair260912-out');
fs.mkdirSync(OUT, { recursive: true });

export async function login(page: Page) {
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|change-password)/, { timeout: 30_000 });
  if (page.url().includes('/change-password')) { await page.goto('/dashboard'); await page.waitForLoadState('networkidle'); }
  expect(page.url(), '登录后不应停留在 /login').not.toContain('/login');
}

export async function switchSeg(page: Page, label: string) {
  const seg = page.locator('.ant-segmented-item').filter({ hasText: label }).first();
  await expect(seg, `应有「${label}」分段入口`).toBeVisible({ timeout: 40_000 });
  await seg.click();
  // 点完轮询确认它真的进入 selected 态，再等渲染 —— 固定 sleep 在慢机器上会漏
  await expect(seg, `「${label}」应进入选中态`).toHaveClass(/ant-segmented-item-selected/, { timeout: 30_000 });
  await page.waitForTimeout(5000);
}

/**
 * 🚨 定位 Excel 视图那张表 —— 绝不用 table.first()（页面第一张表是「基本信息」）。
 * 判据：thead 里同时含「元素小计」与「单价」。
 */
export async function excelTable(page: Page): Promise<Locator> {
  const tables = page.locator('table');
  const n = await tables.count();
  for (let i = 0; i < n; i++) {
    const t = tables.nth(i);
    const head = ((await t.locator('thead').innerText().catch(() => '')) || '').replace(/\s+/g, '');
    if (head.includes('元素小计') && head.includes('单价')) return t;
  }
  throw new Error(`页面 ${n} 张表里找不到含「元素小计」+「单价」表头的 Excel 视图表`);
}

export interface Dump { headers: string[]; rows: string[][]; }

export async function dump(t: Locator): Promise<Dump> {
  const headers = (await t.locator('thead th').allInnerTexts()).map(s => s.replace(/\s+/g, ' ').trim());
  const rl = t.locator('tbody tr');
  const rc = await rl.count();
  const rows: string[][] = [];
  for (let r = 0; r < rc; r++) {
    const cells = rl.nth(r).locator('td');
    const cn = await cells.count();
    const v: string[] = [];
    for (let c = 0; c < cn; c++) {
      const td = cells.nth(c);
      const inp = td.locator('input');
      if (await inp.count() > 0) v.push(((await inp.first().inputValue().catch(() => '')) || '').trim());
      else v.push(((await td.innerText().catch(() => '')) || '').replace(/\s+/g, ' ').trim());
    }
    rows.push(v);
  }
  // 该表把表头也渲染进 tbody 第一行（实测 antd 结构），仅丢弃「第一行且与 thead 逐格相同」的那一行；
  // 之后任何重复行都保留，避免掩盖真实的重复渲染缺陷。
  if (rows.length > 0 && JSON.stringify(rows[0]) === JSON.stringify(headers)) rows.shift();
  return { headers, rows };
}

export function num(s: string): number {
  const v = Number((s || '').replace(/[¥,\s]/g, ''));
  if (Number.isNaN(v)) throw new Error(`「${s}」无法解析为数字`);
  return v;
}

/** 截图前先把横向滚动容器滚到最右，否则改前改后可能 md5 相同（验了恒真的东西）。 */
export async function shot(scope: Page | Locator, name: string) {
  const page: Page = 'page' in scope ? (scope as Locator).page() : (scope as Page);
  await page.evaluate(() => {
    document.querySelectorAll('*').forEach((el) => {
      const e = el as HTMLElement;
      if (e.scrollWidth > e.clientWidth + 4) e.scrollLeft = e.scrollWidth;
    });
  }).catch(() => {});
  await page.waitForTimeout(600);
  const f = path.join(OUT, `${name}.png`);
  await (scope as any).screenshot({ path: f, ...(('goto' in scope) ? { fullPage: true } : {}) }).catch(() => {});
  console.log(`[shot] ${f}`);
  return f;
}

export function saveJson(name: string, o: unknown) {
  const f = path.join(OUT, name);
  fs.writeFileSync(f, JSON.stringify(o, null, 2));
  console.log(`[json] ${f}`);
}
