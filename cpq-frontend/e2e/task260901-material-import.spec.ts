/**
 * task-260901 · E2E：**导入抽屉与导入报告**（T-E-04 / T-E-05 / T-E-12b / T-E-08）
 * → AC-6 / AC-11 / AC-12 / AC-21(序列) / AC-30(导入报告含量摘要)。
 *
 * 导入类用例统一用任务目录里的固定夹具 `夹具-材质库导入验收.xlsx`（§3.0）。
 * AC-21 需要「改含量 + 改组号」的变体，才在运行时用 `xlsx` 生成 —— 其余不另造夹具。
 * 对照 `原型图/4-导入抽屉与报告.html`。选择器免责声明同 drawer spec。
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import * as XLSX from 'xlsx';
import {
  assertIsolatedEnv, assertNoResidue, restoreGlobalState, sql, sqlOne,
  shot, evidence, login, gotoMaterialTab, AC_PREFIX, REAL_RECIPE_CODE,
} from './task260901-material.helpers';

const __f = fileURLToPath(import.meta.url);
const FIXTURE = path.resolve(path.dirname(__f),
  '../../dev-docs/task-260901-材质管理模块定义规则更新/夹具-材质库导入验收.xlsx');
const LEGACY = path.resolve(path.dirname(__f),
  '../../dev-docs/task-260708-材质库规范澄清/材质库.xlsx');
const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';

test.describe.configure({ mode: 'serial' });
test.beforeAll(() => {
  assertIsolatedEnv();
  // 🚨 夹具缺失直接失败，不许悄悄跳过（跳过看起来和「全部通过」一模一样）
  expect(fs.existsSync(FIXTURE), `夹具缺失：${FIXTURE}`).toBeTruthy();
  restoreGlobalState();
});
test.afterAll(() => { restoreGlobalState(); assertNoResidue(); });

async function openImportDrawer(page: any) {
  await page.getByRole('button', { name: /导入材质库|导\s*入/ }).first().click();
  const drawer = page.locator('.ant-drawer').last();
  await expect(drawer).toBeVisible();
  return drawer;
}

async function upload(page: any, drawer: any, name: string, buf: Buffer) {
  await drawer.locator('input[type="file"]').setInputFiles({ name, mimeType: XLSX_MIME, buffer: buf });
  await page.waitForTimeout(500);
  const submit = drawer.getByRole('button', { name: /开始导入|确\s*定|导\s*入|上\s*传/ }).last();
  if (await submit.isVisible().catch(() => false)) await submit.click();
  await page.waitForTimeout(4000);
}

/** 用 xlsx 生成 4 列单表（全部按字符串写，保住 12 位小数）。 */
function buildSheet(rows: string[][]): Buffer {
  const aoa = [['材质', '组号', '元素符号', '含量'], ...rows];
  const ws = XLSX.utils.aoa_to_sheet(aoa);
  const wb = XLSX.utils.book_new();
  XLSX.utils.book_append_sheet(wb, ws, '材质含量');
  return XLSX.write(wb, { type: 'buffer', bookType: 'xlsx' }) as Buffer;
}

// ══════════════ T-E-04 → AC-6 + AC-30（报告） ══════════════

test('T-E-04 / AC-6：导入夹具 → 报告列出「本次自动新建元素」Xx，含量摘要去尾随零', async ({ page }) => {
  await login(page);
  await gotoMaterialTab(page);
  const baseElNo = Number(sqlOne(`SELECT max(element_no::bigint) FROM element WHERE element_no ~ '^[0-9]+$'`));
  expect(baseElNo, '前置：element 主表须有纯数字编号').toBeGreaterThan(0);

  const drawer = await openImportDrawer(page);
  await upload(page, drawer, '夹具-材质库导入验收.xlsx', fs.readFileSync(FIXTURE));

  const reportText = await page.locator('.ant-drawer, .ant-modal').last().innerText();
  console.log('[AC-6] 导入报告 =\n', reportText);
  expect(reportText.length, 'AC-6：报告内容非空').toBeGreaterThan(0);
  expect(reportText, 'AC-6：报告须列出自动新建的元素符号 Xx').toContain('Xx');
  expect(reportText, `AC-6：报告须列出自动新建的元素编号 ${baseElNo + 1}`).toContain(String(baseElNo + 1));

  // AC-30：导入报告的含量摘要去掉小数点后多余的 0
  expect(reportText, 'AC-30：报告摘要显示 85% 而不是 85.000000000000%').toMatch(/85\s*%/);
  expect(reportText, 'AC-30：🚫 报告里不得出现未去零的 85.000000000000').not.toContain('85.000000000000');
  await shot(page, 'AC06-import-report', { fullPage: true });

  // 库内交叉确认（页面说到 ≠ 库里做到）
  expect(sqlOne(`SELECT element_no FROM element WHERE element_code='Xx'`),
    'AC-6：Xx 编号 = 纯数字最大值 + 1').toBe(String(baseElNo + 1));
  evidence('AC06-import-report', reportText);
});

// ══════════════ T-E-05 → AC-11 ══════════════

test('T-E-05 / AC-11：上传旧两 sheet 模板 → 明确错误文案，不按旧语义静默执行，库内条数不变', async ({ page }) => {
  test.skip(!fs.existsSync(LEGACY), `旧模板不在预期路径：${LEGACY}`);
  await login(page);
  await gotoMaterialTab(page);
  const before = sqlOne(`SELECT count(*) FROM material_recipe`);

  const drawer = await openImportDrawer(page);
  await upload(page, drawer, '材质库.xlsx', fs.readFileSync(LEGACY));

  const text = await page.locator('body').innerText();
  console.log('[AC-11] 页面错误提示片段 =', text.slice(0, 600));
  expect(text, 'AC-11：错误文案').toContain('导入模板格式已更新');
  expect(text, 'AC-11：文案须点名新模板').toContain('新模板');
  for (const c of ['材质', '组号', '元素符号', '含量']) {
    expect(text, `AC-11：文案须列出新模板的列「${c}」`).toContain(c);
  }
  expect(sqlOne(`SELECT count(*) FROM material_recipe`), 'AC-11：库内 material_recipe 条数不变').toBe(before);
  await shot(page, 'AC11-legacy-template-rejected', { fullPage: true });
  evidence('AC11-legacy-rejected', text.slice(0, 2000));
});

// ══════════════ T-E-12b → AC-12 ══════════════

test('T-E-12b / AC-12：下载导入模板 = 单 sheet、表头恰四列、2 行示例、不含旧编号列', async ({ page }) => {
  await login(page);
  await gotoMaterialTab(page);

  const [dl] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: /下载导入模板/ }).click(),
  ]);
  const file = await dl.path();
  expect(file, 'AC-12：应真的下载到文件').toBeTruthy();
  const wb = XLSX.read(fs.readFileSync(file!), { type: 'buffer' });
  console.log('[AC-12] sheets =', wb.SheetNames);
  expect(wb.SheetNames.length, 'AC-12：单 sheet，不含第二个工作表').toBe(1);

  const aoa = XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]], { header: 1 }) as any[][];
  console.log('[AC-12] aoa =', JSON.stringify(aoa));
  expect(aoa.length, 'AC-12：表头 + 2 行示例').toBe(3);
  expect(aoa[0].map(String), 'AC-12：表头恰为 材质/组号/元素符号/含量').toEqual(
    ['材质', '组号', '元素符号', '含量']);
  expect(aoa[0].map(String), 'AC-12：🚫 不含「材质编号」').not.toContain('材质编号');
  expect(aoa[0].map(String), 'AC-12：🚫 不含「元素编号」').not.toContain('元素编号');
  const archived = path.join(path.dirname(__f),
    '../../dev-docs/task-260901-材质管理模块定义规则更新/证据/AC12-下载模板.xlsx');
  fs.copyFileSync(file!, archived);
  console.log('🧾 证据 →', archived);
});

// ══════════════ T-E-08 → AC-21（序列） ══════════════

test('T-E-08 / AC-21（序列）：改含量+改组号重导 → 3 条配置且旧配置不被覆盖 → 切走切回 → 刷新仍是 3 条', async ({ page }) => {
  await login(page);
  await gotoMaterialTab(page);

  // 步骤① 导入夹具
  let drawer = await openImportDrawer(page);
  await upload(page, drawer, '夹具-材质库导入验收.xlsx', fs.readFileSync(FIXTURE));
  await page.keyboard.press('Escape').catch(() => {});
  let cfgs = sql(`SELECT c.config_no || ':' || (
      SELECT string_agg(e.element_code || '=' || e.default_pct::text, ',' ORDER BY e.element_code)
      FROM material_recipe_element e WHERE e.config_id = c.id)
    FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id
    WHERE r.code='${REAL_RECIPE_CODE}' AND c.status='ACTIVE' ORDER BY c.seq`);
  console.log('[AC-21·中间态①] ', cfgs);
  expect(cfgs.length, 'AC-21 中间态：第一次导入后 00006 下 2 条配置').toBe(2);

  // 步骤② 组号 2 → 9，含量 0.85/0.15 → 0.80/0.20
  const variant = buildSheet([
    ['AgNi10', '1', 'Ag', '0.90'], ['AgNi10', '1', 'Ni', '0.10'],
    ['AgNi10', '9', 'Ag', '0.80'], ['AgNi10', '9', 'Ni', '0.20'],
  ]);

  // 步骤③ 重导
  await gotoMaterialTab(page);
  drawer = await openImportDrawer(page);
  await upload(page, drawer, '夹具变体.xlsx', variant);
  await page.keyboard.press('Escape').catch(() => {});

  cfgs = sql(`SELECT c.config_no || ':' || (
      SELECT string_agg(e.element_code || '=' || e.default_pct::text, ',' ORDER BY e.element_code)
      FROM material_recipe_element e WHERE e.config_id = c.id)
    FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id
    WHERE r.code='${REAL_RECIPE_CODE}' AND c.status='ACTIVE' ORDER BY c.seq`);
  console.log('[AC-21·步骤③] ', cfgs);
  expect(cfgs.length, 'AC-21：应变为 3 条配置').toBe(3);
  expect(cfgs[0], 'AC-21：-01 Ag 90 —— 旧配置不被覆盖').toContain('Ag=90.000000000000');
  expect(cfgs[1], 'AC-21：-02 Ag 85 —— 组号变化不影响归属，旧配置原样保留').toContain('Ag=85.000000000000');
  expect(cfgs[2], 'AC-21：-03 Ag 80 —— 新配比新增').toContain('Ag=80.000000000000');
  expect(cfgs.map(s => s.split(':')[0]), 'AC-21：编号顺序 -01/-02/-03').toEqual(
    ['00006-01', '00006-02', '00006-03']);
  evidence('AC21-after-reimport', cfgs.join('\n'));

  // 步骤④ 切到别的页面再切回
  await page.getByRole('tab', { name: '元素' }).click();
  await page.waitForTimeout(800);
  await page.getByRole('tab', { name: '材质' }).click();
  await page.waitForSelector('.ant-table-row', { timeout: 20_000 });
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(400);
  await page.getByPlaceholder(/搜索/).first().fill(REAL_RECIPE_CODE);
  await page.waitForTimeout(900);
  await page.locator('.ant-table-tbody a').filter({ hasText: REAL_RECIPE_CODE }).first().click();
  // 🚨 `.ant-drawer').last()` 会抓到 DOM 里遗留的**已关闭**抽屉（2026-09-02 实跑：
  //    rows 恒为 []，看起来像「切走切回后配置没了」的产品缺陷，实际是选错了对象）。
  //    只认打开中且文本含 00006 的那个。
  let drawerNow = page.locator('.ant-drawer-open').filter({ hasText: REAL_RECIPE_CODE }).last();
  await expect(drawerNow, 'AC-21 步骤④：应打开 00006 的抽屉').toBeVisible({ timeout: 15_000 });
  let m = drawerNow.locator('.ant-table').filter({ hasText: '配置编号' }).first();
  let rows = await m.locator('.ant-table-tbody tr.ant-table-row').allInnerTexts();
  console.log('[AC-21·步骤④ 切走切回] ', rows);
  expect(rows.length, 'AC-21 步骤④：仍是 3 条').toBe(3);
  expect(rows.map(r => (r.match(/00006-\d+/) || [''])[0]), 'AC-21 步骤④：顺序稳定').toEqual(
    ['00006-01', '00006-02', '00006-03']);
  await shot(page, 'AC21-after-tab-switch');

  // 步骤⑤ 刷新浏览器
  await page.reload();
  await page.waitForSelector('.ant-table-row', { timeout: 20_000 });
  await page.getByRole('tab', { name: '材质' }).click().catch(() => {});
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(400);
  await page.getByPlaceholder(/搜索/).first().fill(REAL_RECIPE_CODE);
  await page.waitForTimeout(900);
  await page.locator('.ant-table-tbody a').filter({ hasText: REAL_RECIPE_CODE }).first().click();
  drawerNow = page.locator('.ant-drawer-open').filter({ hasText: REAL_RECIPE_CODE }).last();
  await expect(drawerNow, 'AC-21 步骤⑤：应打开 00006 的抽屉').toBeVisible({ timeout: 15_000 });
  m = drawerNow.locator('.ant-table').filter({ hasText: '配置编号' }).first();
  rows = await m.locator('.ant-table-tbody tr.ant-table-row').allInnerTexts();
  console.log('[AC-21·步骤⑤ 刷新] ', rows);
  expect(rows.length, 'AC-21 步骤⑤：刷新后仍是 3 条').toBe(3);
  expect(rows.map(r => (r.match(/00006-\d+/) || [''])[0]), 'AC-21 步骤⑤：顺序稳定').toEqual(
    ['00006-01', '00006-02', '00006-03']);
  await shot(page, 'AC21-after-reload');
});
