/**
 * 独立验收 · 补验 AC-36 / AC-37
 *
 * 🚨 立项理由：需求文档定义 AC-1~AC-37，但 test.md 的可追溯矩阵只到 AC-35，
 *    4 个交付 spec 里也搜不到 AC-36 / AC-37 —— 这两条**从未被任何测试覆盖**，
 *    且 test-report.md §6「未执行」只列了 AC-22③，没有列出它们（testing.md §2：
 *    没有任何测试覆盖的 AC = 交付缺口，必须显式列出，不许沉默）。
 *
 * 两条都是纯读 UI 断言，零写库：
 *   AC-36 元素权威链显示（00262/SnO2 组成行是串位脏数据 element_code=10004）
 *   AC-37 点行开抽屉 / 勾选框不开抽屉
 * AC-37③ 的「停用」只看确认弹层、**不按下确认**（真按下会把 3 条真实材质置停用）。
 */
import { test, expect, Page } from '@playwright/test';
import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const OUT = path.resolve(path.dirname(fileURLToPath(import.meta.url)),
  '../../dev-docs/task-260901-材质管理模块定义规则更新/证据-独立验收');
let idx = 100;
async function shot(page: Page, name: string) {
  fs.mkdirSync(OUT, { recursive: true });
  const f = path.join(OUT, `V-${++idx}-${name}.png`);
  await page.screenshot({ path: f, fullPage: true });
  console.log(`📸 ${f}`);
}
function note(name: string, s: string) {
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, `V-${name}.txt`), s, 'utf-8');
  console.log(`🧾 V-${name}.txt`);
}
function sql(q: string): string[] {
  return execFileSync('psql', ['-h', '10.177.152.12', '-U', 'postgres', '-d', 'cpq_db_0724', '-tAF', '\t', '-c', q],
    { env: { ...process.env, PGPASSWORD: 'joii5231' }, encoding: 'utf-8' })
    .split('\n').map(s => s.trim()).filter(Boolean);
}
const sqlOne = (q: string) => { const r = sql(q); return r.length ? r[0] : null; };

async function login(page: Page) {
  for (let i = 0; i < 4; i++) {
    const r = await page.request.post('/api/cpq/auth/login',
      { data: { username: 'admin', password: 'Admin@2026' } });
    if (r.ok()) return;
    await page.waitForTimeout(20_000);
  }
  throw new Error('登录失败');
}
async function gotoMaterialTab(page: Page) {
  await page.goto('/master-data-hub');
  await page.getByRole('tab', { name: '材质' }).click();
  await page.waitForSelector('.ant-table-row', { timeout: 20_000 });
}

test('J-4 / AC-36：元素权威链显示（00262 组成行 element_code 是串位的 10004）', async ({ page }) => {
  const raw = sqlOne(`SELECT c.element_no||'|'||c.element_code||'|'||c.element_name
    FROM material_recipe_composition c JOIN material_recipe r ON r.id=c.recipe_id WHERE r.code='00262'`);
  console.log('[AC-36前置] 00262 组成行(库内原始) =', raw);
  expect(raw, 'AC-36 前置：00262 组成行须是串位脏数据 10004|10004|Sn').toBe('10004|10004|Sn');
  const master = sqlOne(`SELECT element_no||'|'||element_code||'|'||element_name FROM element WHERE element_no='10004'`);
  expect(master, 'AC-36 前置：element 主表 10004 = Sn/锡').toBe('10004|Sn|锡');

  await login(page);
  await gotoMaterialTab(page);
  await page.getByPlaceholder(/搜索/).first().fill('00262');
  await page.waitForTimeout(1500);
  const row = page.locator('.ant-table-tbody tr.ant-table-row').first();
  const rowText = (await row.innerText()).replace(/\s+/g, ' ').trim();
  console.log('[AC-36①] 列表行 =', rowText);
  await shot(page, 'AC36-list-row');

  expect(rowText, 'AC-36①：列表「元素组成」列应显示 Sn').toContain('Sn');
  expect(rowText, 'AC-36①：🚫 列表不得显示快照里的串位值 10004').not.toContain('10004');

  await row.locator('td').nth(2).click();
  await page.waitForTimeout(2000);
  const drawer = page.locator('.ant-drawer-open, .ant-drawer').last();
  await expect(drawer, 'AC-36②：编辑抽屉应打开').toBeVisible({ timeout: 15_000 });
  const dText = (await drawer.innerText()).replace(/\s+/g, ' ').trim();
  console.log('[AC-36②] 抽屉文本片段 =', dText.slice(0, 400));
  await shot(page, 'AC36-drawer');
  note('AC36', `库内原始组成行 = ${raw}\nelement 主表 = ${master}\n列表行 = ${rowText}\n\n抽屉全文:\n${dText}`);

  for (const seg of ['10004', 'Sn', '锡']) {
    expect(dText, `AC-36②：抽屉元素组成 chip 应含「${seg}」（三段全取自 element 主表）`).toContain(seg);
  }

  const after = sqlOne(`SELECT c.element_no||'|'||c.element_code||'|'||c.element_name
    FROM material_recipe_composition c JOIN material_recipe r ON r.id=c.recipe_id WHERE r.code='00262'`);
  expect(after, 'AC-36：🚫 只改显示不改数据 —— 库内该行必须逐字不变').toBe(raw);
});

test('J-5 / AC-37：点行开抽屉、勾选框不开抽屉', async ({ page }) => {
  const activeBefore = sqlOne(`SELECT count(*) FROM material_recipe WHERE status='ACTIVE'`)!;
  console.log('[AC-37前置] ACTIVE 材质数 =', activeBefore);

  await login(page);
  await gotoMaterialTab(page);
  await page.getByPlaceholder(/搜索/).first().fill('00006');
  await page.waitForTimeout(1500);
  const row = page.locator('.ant-table-tbody tr.ant-table-row').first();
  expect(await row.count(), 'AC-37 前置：应能搜到 00006').toBeGreaterThan(0);

  await row.locator('td').nth(2).click();
  await page.waitForTimeout(2000);
  const drawer = page.locator('.ant-drawer-open, .ant-drawer').last();
  await expect(drawer, 'AC-37①：点行应打开编辑抽屉').toBeVisible({ timeout: 15_000 });
  const title = (await drawer.locator('.ant-drawer-title').innerText().catch(() => '')).trim();
  console.log('[AC-37①] 抽屉标题 =', title);
  await shot(page, 'AC37-row-click-opens');
  expect(title, 'AC-37①：标题含 00006').toContain('00006');
  expect(title, 'AC-37①：标题含 AgNi10').toContain('AgNi10');

  await page.keyboard.press('Escape');
  await page.waitForTimeout(1500);
  await expect(page.locator('.ant-drawer-open'), 'AC-37：抽屉应已关闭').toHaveCount(0);

  await page.getByPlaceholder(/搜索/).first().fill('');
  await page.waitForTimeout(1500);
  const boxes = page.locator('.ant-table-tbody tr.ant-table-row input[type="checkbox"]');
  expect(await boxes.count(), 'AC-37②：列表须至少 3 行').toBeGreaterThanOrEqual(3);
  await boxes.nth(0).check();
  await page.waitForTimeout(1000);
  await expect(page.locator('.ant-drawer-open'), 'AC-37②：勾选复选框🚫不得打开抽屉').toHaveCount(0);

  await boxes.nth(1).check();
  await page.waitForTimeout(600);
  await boxes.nth(2).check();
  await page.waitForTimeout(1000);
  await expect(page.locator('.ant-drawer-open'), 'AC-37③：连续勾选 3 行全程🚫不得弹抽屉').toHaveCount(0);
  await shot(page, 'AC37-three-checked-no-drawer');

  const disable = page.getByRole('button', { name: /停\s*用/ }).first();
  await expect(disable, 'AC-37③：选 3 行时「停用」可用').toBeEnabled();
  await disable.click();
  await page.waitForTimeout(1500);
  const confirm = page.locator('.ant-drawer-open, .ant-modal-content').last();
  const cText = (await confirm.innerText().catch(() => '')).replace(/\s+/g, ' ').trim();
  console.log('[AC-37③] 停用确认层 =', cText.slice(0, 300));
  await shot(page, 'AC37-disable-confirm');
  note('AC37-disable-confirm', cText);

  // 🚫 取消，绝不确认
  const cancel = confirm.getByRole('button', { name: /取\s*消/ }).last();
  if (await cancel.isVisible().catch(() => false)) await cancel.click();
  await page.waitForTimeout(1200);

  const active = sqlOne(`SELECT count(*) FROM material_recipe WHERE status='ACTIVE'`);
  console.log('[AC-37] 收尾 ACTIVE 材质数 =', active);
  expect(active, 'AC-37：🚨 本条不得改变任何材质状态').toBe(activeBefore);
});
