/** 主线独立亲验 —— 核价 Excel 视图（只读；断言直接对着 AC 原文，不复用测试员脚本） */
import { test, expect } from '@playwright/test';
import { login } from './repair260911-helpers';

const Q0011 = '658ad486-dabe-4770-a851-73e4005ce66f';   // QT-20260912-0011
const EXPECT: Record<string, string[]> = {
  S0001: ['489985', '5438667.5', '5.8', '5438673.3'],
  S0004: ['632046', '16459679.88', '3.8', '16459683.68'],
  S0008: ['677615', '38485323.75', '2.05', '38485325.8'],
  S0012: ['641025', '34249252.5', '4.7', '34249257.2'],
};
const CHILD_PARTS = ['300012', '300013', '300014', '300015', '00003', '00081', '300021', '300031', '300041'];

/** 定位到含「元素小计」的那张表（页面第一个 table 是「基本信息」）。 */
async function excelTable(page: any) {
  await expect(page.getByText('元素小计').first(), '页面应出现「元素小计」列头')
    .toBeVisible({ timeout: 20_000 });
  const t = page.locator('table').filter({ has: page.locator('th', { hasText: '元素小计' }) }).first();
  await expect(t).toBeVisible({ timeout: 20_000 });
  return t;
}
async function readRows(t: any) {
  const head = (await t.locator('thead th').allTextContents()).map((s: string) => s.trim());
  const rows = t.locator('tbody tr');
  const n = await rows.count();
  expect(n, '必须有数据行（防空跑）').toBeGreaterThan(0);
  const out: string[][] = [];
  for (let i = 0; i < n; i++) out.push((await rows.nth(i).locator('td').allTextContents()).map((s: string) => s.trim()));
  // 🚨 该表把表头行也渲染进 tbody 第一行。只丢弃「第一行且与 thead 逐格相同」的那一行；
  //    之后若再出现重复行则保留 —— 否则会掩盖真实的重复渲染缺陷。
  if (out.length > 0 && out[0].length === head.length && out[0].every((c, i) => c === head[i])) {
    console.log('[亲验] 丢弃 tbody 内的表头副本行 =', JSON.stringify(out[0]));
    out.shift();
  }
  return out;
}

test('主线亲验 · 详情页 AC-2（每产品一行 + 无子件行）', async ({ page }) => {
  await login(page);
  await page.goto(`/quotations/${Q0011}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(4000);
  await page.locator('.ant-segmented-item, button').filter({ hasText: /^\s*核价单\s*$/ }).first().click();
  await page.waitForTimeout(2500);
  await page.locator('.ant-segmented-item, button').filter({ hasText: /Excel\s*视图/ }).first().click();
  await page.waitForTimeout(4000);

  const rows = await readRows(await excelTable(page));
  console.log('[亲验·详情页] 行数 =', rows.length);
  for (const r of rows) console.log('[亲验·详情页] 行 =', JSON.stringify(r));
  await page.screenshot({ path: 'e2e/mainline-excel2-out/详情页-核价Excel.png', fullPage: true }).catch(()=>{});

  // AC-2 阳性：四个产品各一行、值对
  for (const [pn, exp] of Object.entries(EXPECT)) {
    const hit = rows.find(r => r.some(c => c === pn));
    expect(hit, `应有 ${pn} 这一行`).toBeTruthy();
    for (const v of exp) expect(hit!.join('|'), `${pn} 应含 ${v}`).toContain(v);
  }
  // AC-2 阴性：不得出现子件料号行
  const flat = rows.map(r => r[0]).join('|');
  for (const child of CHILD_PARTS) {
    expect(rows.some(r => r[0] === child), `AC-2 阴性：详情页不得出现子件料号行 ${child}（首列实为 ${flat}）`).toBeFalsy();
  }
  expect(rows.length, 'AC-2：应为每产品一行 = 4 行').toBe(4);
});
