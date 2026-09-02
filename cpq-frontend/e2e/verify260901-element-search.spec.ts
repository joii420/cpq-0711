/**
 * 独立验收 · J-3：元素选择框的搜索候选顺序（纯读，不保存）
 *
 * 由来：交付方 AC-33 用例在 5174 上失败，实到 `TEST-PT-AG` 而非 `Ag`。
 * 表面看是并发会话种的脏数据，但真实 element 表里本就有 13 对包含关系
 * （C⊂Cu/Cd/WC/Cr/DC04/Ce、Ni⊂Ni36/Ni42、Sn⊂SnO2、W⊂WC、Zn⊂ZnO、P⊂Pd/Pt），
 * 所以「精确匹配是否置顶」在**干净库上同样会发生**。
 *
 * 硬断言只放 AC-35 原文要求的（三种输入都能筛出目标项）；
 * 候选顺序作为**观察事实**记录，供交付会话裁决是否算缺陷。
 */
import { test, expect, Page } from '@playwright/test';
import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const OUT = path.resolve(path.dirname(fileURLToPath(import.meta.url)),
  '../../dev-docs/task-260901-材质管理模块定义规则更新/证据-独立验收');
function note(name: string, s: string) {
  fs.mkdirSync(OUT, { recursive: true });
  fs.writeFileSync(path.join(OUT, `V-${name}.txt`), s, 'utf-8');
  console.log(`🧾 V-${name}.txt`);
}
function sql(q: string): string[] {
  return execFileSync('psql', ['-h','10.177.152.12','-U','postgres','-d','cpq_db_0724','-tAF','\t','-c',q],
    { env: { ...process.env, PGPASSWORD: 'joii5231' }, encoding: 'utf-8' })
    .split('\n').map(s => s.trim()).filter(Boolean);
}

async function login(page: Page) {
  for (let i = 0; i < 4; i++) {
    const r = await page.request.post('/api/cpq/auth/login',
      { data: { username: 'admin', password: 'Admin@2026' } });
    if (r.ok()) return;
    await page.waitForTimeout(20_000);
  }
  throw new Error('登录失败');
}

test('J-3 元素选择框：精确匹配是否置顶（纯读，不保存）', async ({ page }) => {
  await login(page);

  // 干净库上也成立的证据：真实元素里的包含关系
  const pairs = sql(`SELECT a.element_code||' ⊂ '||b.element_code FROM element a JOIN element b
    ON b.element_code ILIKE '%'||a.element_code||'%' AND a.element_code<>b.element_code
    WHERE a.element_code NOT ILIKE 'TEST%' AND b.element_code NOT ILIKE 'TEST%'
    ORDER BY a.element_code`);
  console.log(`[J-3] 真实元素包含关系 ${pairs.length} 对：`, pairs.join(', '));

  await page.goto('/master-data-hub');
  await page.getByRole('tab', { name: '材质' }).click();
  await page.waitForSelector('.ant-table-row', { timeout: 20_000 });

  await page.getByRole('button', { name: /新建材质/ }).click();
  const drawer = page.locator('.ant-drawer').last();
  await expect(drawer).toBeVisible();
  // ⚠️ 配方卡片不是 .ant-card —— 直接在抽屉里找「添加元素」（DOM 快照实证）
  const addEl = drawer.getByRole('button', { name: /添加元素/ }).first();
  await expect(addEl, '新建材质抽屉应有「添加元素」按钮').toBeVisible({ timeout: 15_000 });
  const card = drawer;
  const rowsBefore = await card.locator('.ant-table-tbody tr.ant-table-row').count();
  if (rowsBefore === 0) { await addEl.click(); await page.waitForTimeout(600); }
  console.log('[J-3] 元素行数 =', await card.locator('.ant-table-tbody tr.ant-table-row').count());

  const lines: string[] = [`真实元素包含关系 ${pairs.length} 对：${pairs.join(', ')}`, ''];
  const results: Record<string, string[]> = {};

  for (const kw of ['Ag', 'Ni', 'C', 'Sn', '镍', '10005']) {
    const row = card.locator('.ant-table-tbody tr.ant-table-row').first();
    const sel = row.locator('.ant-select').first();
    await sel.click();
    await page.waitForTimeout(300);
    // 清掉上一轮输入
    await page.keyboard.press('Control+A');
    await page.keyboard.type(kw, { delay: 60 });
    await page.waitForTimeout(900);
    const opts = await page.locator('.ant-select-dropdown:visible .ant-select-item-option')
      .allInnerTexts().catch(() => []);
    const clean = opts.map(s => s.replace(/\s+/g, ' ').trim());
    results[kw] = clean;
    console.log(`[J-3] 输入「${kw}」→ 候选 ${clean.length} 项：`, JSON.stringify(clean.slice(0, 8)));
    lines.push(`输入「${kw}」→ ${clean.length} 项`);
    clean.slice(0, 8).forEach((o, i) => lines.push(`   [${i}] ${o}`));
    lines.push('');
    await page.keyboard.press('Escape');
    await page.waitForTimeout(300);
  }
  note('J3-element-search-order', lines.join('\n'));

  // === AC-35 原文要求：三种输入都能筛出同一项 10005 / Ni / 镍 ===
  for (const kw of ['Ni', '镍', '10005']) {
    expect(results[kw].length, `AC-35：输入「${kw}」候选不得为空`).toBeGreaterThan(0);
    expect(results[kw].some(o => /Ni/.test(o) && /镍/.test(o)),
      `AC-35：输入「${kw}」应能筛出「10005 / Ni / 镍」，实到 ${JSON.stringify(results[kw].slice(0,5))}`)
      .toBeTruthy();
  }

  // === 回归守卫（2026-09-02 起由观察升级为硬断言）===
  // 修复前实测（V-J3-*-修复前.txt）：输入「Ag」银排 4/4、输入「C」碳排 5/8 —— 候选直接用接口
  // 返回序（最近更新时间倒序）。修复 = `elementOptions.ts` 的 `sortElementOption` 挂到两处
  // Select 的 `filterSort`。⚠️ 用的是 antd **已 deprecated 的顶层 `filterSort`**（与同处
  // `filterOption` 写法一致），若将来 antd 移除该 prop 会**静默失效** —— 这条断言就是那道闸。
  const firstIsExact: Record<string, boolean> = {};
  for (const kw of ['Ag', 'Ni', 'C', 'Sn']) {
    const first = results[kw][0] || '';
    // 精确匹配 = 候选里出现独立的该符号（形如 "10001 Ag 银"）
    firstIsExact[kw] = new RegExp(`(^|\\s|/)${kw}(\\s|/|$)`).test(first);
    console.log(`[J-3] 「${kw}」首项 = ${JSON.stringify(first)} → 首项即精确匹配? ${firstIsExact[kw]}`);
    expect(firstIsExact[kw],
      `精确匹配置顶：输入「${kw}」首项应为该符号本身，实到 ${JSON.stringify(first)}；` +
      `完整候选 ${JSON.stringify(results[kw])}`).toBeTruthy();
  }
  note('J3-verdict', JSON.stringify({ firstIsExact, results }, null, 2));

  // 不保存，直接关抽屉
  await page.keyboard.press('Escape');
  await page.waitForTimeout(500);
});
