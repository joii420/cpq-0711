/**
 * repair-260910 · S3 片 —— 复现的**第二层观察**（改动前现状）
 * 目的：把「消失的是哪一行」这件事分解到三个时点，区分「渲染层」与「持久层」。
 *   P1 刷新页面后（墓碑=CP2）究竟哪一行不见了
 *   P2 从干净态删**第 1 行**，删后当场 / 刷新后各是哪一行不见了
 * 🚫 未读任何实现文件；结论只写观察，不下根因。
 */
import { test, expect, Page } from '@playwright/test';
import { execSync } from 'child_process';
import * as fs from 'fs';
import * as nodePath from 'path';
import { fileURLToPath } from 'url';

const HERE = nodePath.dirname(fileURLToPath(import.meta.url));
const EV = nodePath.resolve(HERE, '..', '..', 'dev-docs', 'task-260819-取数配置器',
  'repair-260910-客户产品编号维度与删行错位', '证据', 'S3');
const QID = 'ff570e47-424d-4595-964c-d145c2ba8457';
const LI = 'a14765be-2f12-49d0-a063-80b8ca9682ff';
const COMP = '221dc766-8ab6-4d95-82c0-08cc03e6267d';
const BACKEND = 'http://localhost:8081';

fs.mkdirSync(EV, { recursive: true });
function ev(f: string, t: string) { fs.writeFileSync(nodePath.join(EV, f), t, 'utf-8'); }
async function shot(page: Page, n: string) { await page.screenshot({ path: nodePath.join(EV, `${n}.png`), fullPage: true }).catch(() => {}); }
function psql(sql: string) {
  const q = `'` + sql.split(`'`).join(`'\\''`) + `'`;
  return execSync(`PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -X -A -t -c ${q}`,
    { shell: '/bin/bash', encoding: 'utf-8', maxBuffer: 64 * 1024 * 1024 }).trim();
}
function tomb() { return psql(`SELECT COALESCE(deleted_row_keys,'[]')::text FROM quotation_line_component_data WHERE line_item_id='${LI}' AND component_id='${COMP}'`); }
function snapCPs() {
  const j = psql(`SELECT COALESCE(snapshot_rows,'[]')::text FROM quotation_line_component_data WHERE line_item_id='${LI}' AND component_id='${COMP}'`);
  try { return JSON.parse(j || '[]').map((r: any) => r?.driverRow?.['_客户料号_客户产品编号'] ?? '∅'); } catch { return ['<parse-fail>']; }
}
async function login(page: Page) {
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForTimeout(5000);
}
/** 只读「客户产品编号」那一列，够用且好读 */
async function cps(page: Page): Promise<string[]> {
  return page.evaluate(() => Array.from(document.querySelectorAll('.qt-cost-table tbody tr')).map((tr) => {
    const td = tr.querySelectorAll('td')[1];
    const inp = td?.querySelector('input') as HTMLInputElement | null;
    return inp ? String(inp.value ?? '') : (td?.textContent || '').trim();
  }));
}
async function openProductTab(page: Page) {
  await page.goto(`/quotations/${QID}/edit`);
  await page.waitForSelector('.ant-steps-item', { timeout: 90_000 });
  const next = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  await expect(next).toBeEnabled({ timeout: 40_000 });
  await next.click();
  await page.waitForTimeout(12_000);
  await page.locator('.qt-tab-btn', { hasText: '产品' }).first().click();
  await page.waitForTimeout(3500);
}
async function apiLogin(): Promise<string> {
  const res = await fetch(`${BACKEND}/api/cpq/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'Admin@2026' }),
  });
  const raw: string[] = (res.headers as any).getSetCookie?.() ?? [res.headers.get('set-cookie') || ''];
  return raw.filter(Boolean).map((c) => c.split(';')[0].trim()).join('; ');
}

test.describe.configure({ mode: 'serial' });
let log = '';

test('P1 · 墓碑=CP2 时刷新页面，看哪一行不见了', async ({ page }) => {
  test.setTimeout(240_000);
  const t = tomb();
  log += `=== P1 ===\n刷新前 deleted_row_keys = ${t}\nsnapshot_rows 客编序列 = ${JSON.stringify(snapCPs())}\n`;
  expect(t, '前置：墓碑应已含 CP2（由 r0910s3-delete-repro 写入）⇒ 否则本条判【未验证】').toContain('R0910S3-CP2');
  await login(page);
  await openProductTab(page);
  const v = await cps(page);
  log += `刷新后页面渲染的客编序列 = ${JSON.stringify(v)}\n`;
  log += `缺失的是：${['R0910S3-CP1','R0910S3-CP2','R0910S3-CP3','R0910S3-CP4'].filter((x) => !v.includes(x)).join(',') || '（无）'}\n\n`;
  console.log(log);
  await shot(page, '30-P1-刷新后');
  expect(v.length, '刷新后渲染 0 行 ⇒ 断言空跑').toBeGreaterThan(0);
});

test('P2 · 恢复干净态 → 删【第 1 行】→ 当场 / 刷新后各少了哪一行', async ({ page }) => {
  test.setTimeout(300_000);
  // 恢复：用产品自己的 restore-driver-rows 端点（不写 SQL，不越权）
  const cookie = await apiLogin();
  const r = await fetch(`${BACKEND}/api/cpq/quotations/${QID}/line-items/${LI}/restore-driver-rows`, {
    method: 'POST', headers: { Cookie: cookie, 'Content-Type': 'application/json' },
    body: JSON.stringify({ componentId: COMP }),
  });
  log += `=== P2 ===\nrestore-driver-rows → ${r.status}\n恢复后 deleted_row_keys = ${tomb()}\n`;
  expect(r.status, 'restore 端点应 2xx').toBeLessThan(300);

  await login(page);
  await openProductTab(page);
  const before = await cps(page);
  log += `恢复后渲染 = ${JSON.stringify(before)}\n`;
  expect(before.length, '恢复后应回到 4 行，否则后面断言空跑').toBe(4);
  await shot(page, '31-P2-恢复干净态');

  let sent: any = null;
  page.on('request', (q) => { if (q.url().includes('/delete-driver-row') && q.method() === 'POST') { try { sent = q.postDataJSON(); } catch { sent = q.postData(); } } });
  await page.locator('.qt-cost-table tbody tr').nth(0).locator('td:last-child button').first().click();
  await page.waitForTimeout(8000);
  const afterNow = await cps(page);
  log += `点【第 1 行】(${before[0]}) 后当场渲染 = ${JSON.stringify(afterNow)}\n`;
  log += `当场消失的是：${before.filter((x) => !afterNow.includes(x)).join(',') || '（无）'}\n`;
  log += `前端发出 body = ${JSON.stringify(sent)}\n`;
  log += `落库 deleted_row_keys = ${tomb()}\n`;
  await shot(page, '32-P2-删第1行-当场');

  await openProductTab(page);
  const afterReload = await cps(page);
  log += `刷新后渲染 = ${JSON.stringify(afterReload)}\n`;
  log += `刷新后消失的是：${before.filter((x) => !afterReload.includes(x)).join(',') || '（无）'}\n`;
  await shot(page, '33-P2-删第1行-刷新后');
  console.log(log);
  ev('30-第二层观察-改动前.txt', log);
  expect(afterReload.length, '刷新后渲染 0 行 ⇒ 断言空跑').toBeGreaterThan(0);
});
