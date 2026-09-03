/**
 * 主线亲验（task-260902）—— 临时文件，验完即删，不提交。
 * 目的：不采信子代理结论，自己走用户视角的 UI 路径复核关键 AC。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { execSync } from 'child_process';

const ADMIN = 'admin';
const ADMIN_PWD = 'Admin@2026';
const OUT = '/tmp/claude-1000/-home-joii-project-cpq/e9cec752-22f5-4ef4-9a8d-9b63de975487/scratchpad/verify';

test.use({ actionTimeout: 15_000 });

async function login(page: Page, u = ADMIN, p = ADMIN_PWD) {
  await page.goto('/login', { waitUntil: 'domcontentloaded' });
  await expect(page.locator('input[placeholder="用户名或邮箱"]')).toBeVisible({ timeout: 25_000 });
  await page.locator('input[placeholder="用户名或邮箱"]').fill(u);
  await page.locator('input[placeholder="密码"]').fill(p);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|master-data)/, { timeout: 25_000 });
}

async function gotoMaterialTab(page: Page) {
  await page.goto('/master-data-hub', { waitUntil: 'domcontentloaded' });
  await page.getByRole('tab', { name: '材质' }).click();
  await expect(page.getByRole('button', { name: /导\s*出\s*材\s*质\s*库/ })).toBeVisible({ timeout: 20_000 });
}

/** antd 分页「共 N 条」 */
async function listTotal(page: Page): Promise<{total: number; rows: number; src: string}> {
  const rows = await page.locator('.ant-table-tbody tr.ant-table-row').count();
  for (const sel of ['.ant-pagination-total-text', '.ant-pagination-total', 'li.ant-pagination-total-text']) {
    const t = page.locator(sel).first();
    if (await t.count() > 0) {
      const m = (await t.innerText()).trim().match(/(\d+)/);
      if (m) return { total: Number(m[1]), rows, src: sel };
    }
  }
  // 兜底：扫整页文本找「共 N 条」
  const body = await page.locator('body').innerText();
  const m = body.match(/共\s*(\d+)\s*条/);
  return { total: m ? Number(m[1]) : -1, rows, src: m ? 'body:共N条' : '未找到' };
}

/** 状态下拉当前显示的文本 */
async function statusFilterText(page: Page): Promise<string> {
  const items = page.locator('.ant-select-selection-item, .ant-select-selection-placeholder');
  const n = await items.count();
  for (let i = 0; i < n; i++) {
    const s = (await items.nth(i).innerText()).trim();
    if (s.includes('状态') || s === '启用' || s === '停用') return s;
  }
  return `(未找到, 共${n}个select)`;
}

/** 下载导出文件，返回 sheet1 的数据行数（不含表头）。xlsx 即 zip，用 unzip 数 <row> */
async function exportAndCountRows(page: Page, tag: string): Promise<number> {
  fs.mkdirSync(OUT, { recursive: true });
  const [dl] = await Promise.all([
    page.waitForEvent('download', { timeout: 30_000 }),
    page.getByRole('button', { name: /导\s*出\s*材\s*质\s*库/ }).click(),
  ]);
  const f = path.join(OUT, `${tag}.xlsx`);
  await dl.saveAs(f);
  const xml = execSync(`unzip -p "${f}" xl/worksheets/sheet1.xml`, { maxBuffer: 64 * 1024 * 1024 }).toString();
  const rows = (xml.match(/<row[ >]/g) || []).length;
  return rows - 1; // 减表头
}

test('V-1 · AC-22 决定性实验：切页签往返后，筛选框显示值 与 列表实际条数 是否自洽', async ({ page }) => {
  await login(page);
  await gotoMaterialTab(page);

  const all = await listTotal(page);
  console.log(`[V-1] 初始（未筛选）: 共=${all.total} 当前页行数=${all.rows} (来源:${all.src})`);
  const totalAll = all.total;

  // 状态筛「停用」
  const statusSel = page.locator('.ant-select').filter({ hasText: '状态' }).first();
  await statusSel.click();
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option', { hasText: '停用' }).first().click();
  await page.waitForTimeout(1200);

  const filterBefore = await statusFilterText(page);
  const b4 = await listTotal(page); const totalBefore = b4.total; const rowsInTableBefore = b4.rows;
  const rowsBefore = await exportAndCountRows(page, 'AC22-切换前-停用');
  console.log(`[V-1] 切换前：筛选框="${filterBefore}" 列表共=${totalBefore} 表格行=${rowsInTableBefore} 导出行数=${rowsBefore}`);

  // 切到工序页签，再切回材质
  await page.getByRole('tab', { name: '工序' }).click();
  await page.waitForTimeout(1000);
  await page.getByRole('tab', { name: '材质' }).click();
  await expect(page.getByRole('button', { name: /导\s*出\s*材\s*质\s*库/ })).toBeVisible({ timeout: 20_000 });
  await page.waitForTimeout(1500);

  const filterAfter = await statusFilterText(page);
  const af = await listTotal(page); const totalAfter = af.total; const rowsInTableAfter = af.rows;
  const rowsAfter = await exportAndCountRows(page, 'AC22-切换后');
  console.log(`[V-1] 切换后：筛选框="${filterAfter}" 列表共=${totalAfter} 表格行=${rowsInTableAfter} 导出行数=${rowsAfter}`);

  const summary = [
    `初始未筛选列表共 = ${totalAll}`,
    `切换前：筛选框="${filterBefore}" 列表共=${totalBefore} 表格行=${rowsInTableBefore} 导出=${rowsBefore} 行`,
    `切换后：筛选框="${filterAfter}" 列表共=${totalAfter} 表格行=${rowsInTableAfter} 导出=${rowsAfter} 行`,
    ``,
    `判定依据：导出是否 == 页面所见`,
    `  切换前 导出${rowsBefore} vs 列表${totalBefore} → ${rowsBefore === totalBefore ? '一致' : '不一致(注:一个材质可能多行元素)'}`,
    `  切换后 导出${rowsAfter} vs 列表${totalAfter} → ${'见下'}`,
    `  筛选框是否被重置：${filterBefore} → ${filterAfter}`,
  ].join('\n');
  fs.writeFileSync(path.join(OUT, 'V-1-AC22判定.txt'), summary);
  console.log('\n' + summary);

  await page.screenshot({ path: path.join(OUT, 'V-1-切换后.png'), fullPage: true });
});

const PSQL = `PGPASSWORD=joii5231 psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -t -A -c`;
function counts(): {r: number; c: number; e: number} {
  const q = `SELECT (SELECT count(*) FROM material_recipe)||','||(SELECT count(*) FROM material_recipe_config)||','||(SELECT count(*) FROM material_recipe_element);`;
  const out = execSync(`${PSQL} "${q}"`).toString().trim();
  const [r, c, e] = out.split(',').map(Number);
  return { r, c, e };
}

test('V-2 · AC-19 回环亲验：筛「启用」导出 → 原样回导 → 必须零新增', async ({ page }) => {
  await login(page);
  await gotoMaterialTab(page);

  // ① 筛「状态 = 启用」
  const statusSel = page.locator('.ant-select').filter({ hasText: '状态' }).first();
  await statusSel.click();
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option', { hasText: '启用' }).first().click();
  await page.waitForTimeout(1500);
  const after = await listTotal(page);
  console.log(`[V-2] 筛「启用」后列表共 ${after.total} 条`);

  // ② 导出
  fs.mkdirSync(OUT, { recursive: true });
  const [dl] = await Promise.all([
    page.waitForEvent('download', { timeout: 30_000 }),
    page.getByRole('button', { name: /导\s*出\s*材\s*质\s*库/ }).click(),
  ]);
  const file = path.join(OUT, 'AC19-筛启用导出.xlsx');
  await dl.saveAs(file);
  const xml = execSync(`unzip -p "${file}" xl/worksheets/sheet1.xml`, { maxBuffer: 64 * 1024 * 1024 }).toString();
  const dataRows = (xml.match(/<row[ >]/g) || []).length - 1;
  console.log(`[V-2] 导出文件数据行数 = ${dataRows}`);

  // ③ 回导前基线（与回导紧邻，减小共享库漂移干扰）
  const before = counts();
  console.log(`[V-2] 回导前: material_recipe=${before.r} config=${before.c} element=${before.e}`);

  // ④ 从 UI 导入这份文件
  await page.getByRole('button', { name: /导\s*入\s*材\s*质\s*库/ }).click();
  await expect(page.getByText('模板格式', { exact: false }).first()).toBeVisible({ timeout: 15_000 });
  await page.locator('input[type="file"]').setInputFiles(file);
  await page.waitForTimeout(800);
  await page.getByRole('button', { name: /开\s*始\s*导\s*入/ }).click();

  // ⑤ 读导入报告
  await expect(page.getByText(/导入完成|新增材质|读取行数/).first()).toBeVisible({ timeout: 120_000 });
  await page.waitForTimeout(2000);
  const reportText = await page.locator('body').innerText();
  console.log(`[V-2] 导入报告全文:\n${reportText}`);
  await page.screenshot({ path: path.join(OUT, 'V-2-AC19导入报告.png'), fullPage: true });

  // ⑥ 回导后
  const post = counts();
  console.log(`[V-2] 回导后: material_recipe=${post.r} config=${post.c} element=${post.e}`);

  const delta = { r: post.r - before.r, c: post.c - before.c, e: post.e - before.e };
  const verdict = [
    `导出文件数据行数 = ${dataRows}（筛「启用」，列表共 ${after.total} 条材质）`,
    `回导前: recipe=${before.r} config=${before.c} element=${before.e}`,
    `回导后: recipe=${post.r} config=${post.c} element=${post.e}`,
    `增量  : recipe=${delta.r} config=${delta.c} element=${delta.e}`,
    `AC-19 判据（三个增量必须全为 0）: ${delta.r === 0 && delta.c === 0 && delta.e === 0 ? '✅ 通过' : '❌ 失败'}`,
  ].join('\n');
  fs.writeFileSync(path.join(OUT, 'V-2-AC19判定.txt'), verdict + '\n\n【导入报告原文】\n' + reportText);
  console.log('\n' + verdict);

  expect(delta.r, 'AC-19: material_recipe 增量必须为 0').toBe(0);
  expect(delta.c, 'AC-19: material_recipe_config 增量必须为 0').toBe(0);
  expect(delta.e, 'AC-19: material_recipe_element 增量必须为 0').toBe(0);
});
