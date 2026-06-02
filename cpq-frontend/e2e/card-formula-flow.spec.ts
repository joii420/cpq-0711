/**
 * E2E: CARD_FORMULA Excel 视图渲染验证（非破坏性版）
 *
 * 设计纪律（上一版因绑空模板+开向导删了组件数据，本版严格规避）：
 *   - 绝不修改任何 quotation_line_item.template_id（用报价单本就在用的真实模板）
 *   - 绝不 DELETE/UPDATE quotation_line_component_data（组件数据只读）
 *   - 唯一临时写：往"已有组件的真实报价模板"d16dd592 的 excel_view_config 注入 1 个 CARD_FORMULA 列；
 *     该模板原 excel_view_config = NULL，afterAll 还原为 NULL
 *   - 守卫：beforeAll/afterAll 各查一次该报价单页签总数，必须保持 18（变了=数据被破坏，报错）
 *
 * 夹具（完整、简单产品、模板有组件）：
 *   - 报价单 QT-20260602-1497 (b0fec225-20da-4668-8d83-39616f4c4e31)，3 个料号 3120012004/05/06
 *   - 模板 d16dd592（报价单模板0601 v1.4，PUBLISHED，有 components_snapshot）
 *   - 工序组件 5c47fb41:3 行"小计"字段之和 = 515.5632（每个 line item 一致）
 *   - CARD_FORMULA: =SUM_OVER([工序], c0)  (c0=小计) → 每行 ≈ 515.5632
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { execSync } from 'child_process';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const __filename = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filename);
const SHOT_DIR = path.join(__dirnameLocal, 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

// ── 夹具常量 ──────────────────────────────────────────────────────────
const QUOTATION_ID = 'b0fec225-20da-4668-8d83-39616f4c4e31';   // QT-20260602-1497
const TEMPLATE_ID = 'd16dd592-7c8c-481e-be78-3bddffc0bfd2';     // 报价单模板0601 v1.4（有组件）
const PROCESS_COMP_ID = '5c47fb41-f092-4ef8-a960-bce07c93ded0';
const PROCESS_SORT_ORDER = 3;
const EXPECTED_SUM = 515.5632;
const BACKEND_URL = 'http://localhost:8081';

const EXCEL_VIEW_CONFIG_JSON = JSON.stringify([
  {
    col_key: 'A',
    title: '工序总计(CARD_FORMULA)',
    source_type: 'CARD_FORMULA',
    formula: '=SUM_OVER([工序], c0)',
    refs: { '工序': { tab: `${PROCESS_COMP_ID}:${PROCESS_SORT_ORDER}`, cols: { c0: '小计' } } },
  },
]);

function psqlStdin(sql: string): string {
  try {
    return execSync('PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db',
      { input: sql, encoding: 'utf-8', stdio: ['pipe', 'pipe', 'pipe'] });
  } catch (e: any) {
    const msg = e.stdout ?? e.stderr ?? String(e);
    console.warn('[psql] warn:', msg.slice(0, 200));
    return msg;
  }
}

function tabCount(): number {
  const out = psqlStdin(
    `SELECT count(*) FROM quotation q JOIN quotation_line_item li ON li.quotation_id=q.id ` +
    `JOIN quotation_line_component_data lcd ON lcd.line_item_id=li.id WHERE q.id='${QUOTATION_ID}';`);
  const m = out.match(/\d+/);   // 鲁棒提取数字，避开 psql 可能的告警/空白行导致 NaN
  return m ? parseInt(m[0], 10) : -1;
}

let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `cf-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}

let backendUp = false;
let setupDone = false;
let baselineTabs = -1;

test.beforeAll(async () => {
  backendUp = await isBackendUp();
  if (!backendUp) { console.log('[beforeAll] 后端未启动'); return; }

  baselineTabs = tabCount();
  console.log(`[beforeAll] 基线页签数 = ${baselineTabs}`);

  // 只注入 excel_view_config（原值=NULL）；绝不改 template_id / 组件数据
  const cfg = EXCEL_VIEW_CONFIG_JSON.replace(/'/g, "''");
  const out = psqlStdin(`UPDATE template SET excel_view_config = '${cfg}'::jsonb WHERE id = '${TEMPLATE_ID}';`);
  console.log('[beforeAll] 注入 CARD_FORMULA 列:', out.trim());
  setupDone = true;
});

test.afterAll(async () => {
  if (!backendUp) return;
  // 还原 excel_view_config 为 NULL（原值）
  const out = psqlStdin(`UPDATE template SET excel_view_config = NULL WHERE id = '${TEMPLATE_ID}';`);
  console.log('[afterAll] 还原 excel_view_config=NULL:', out.trim());
  // 守卫：页签数必须不变（证明没删组件数据）
  const after = tabCount();
  const ok = after === baselineTabs;
  console.log(`[afterAll] 页签数 before=${baselineTabs} after=${after} 一致=${ok}`);
  if (!ok) console.error('🔴🔴 数据被破坏：组件页签数变化！必须人工排查');
});

// ── 测试 1（主线确定性）：API getExcelView ────────────────────────────
test('后端 getExcelView API：CARD_FORMULA 列 A ≈ 515.5632', async ({ request }) => {
  test.skip(!backendUp || !setupDone, '后端未启动或夹具失败');

  const loginResp = await request.post(`${BACKEND_URL}/api/cpq/auth/login`,
    { data: { username: 'admin', password: 'Admin@2026' } });
  expect(loginResp.status()).toBe(200);

  const resp = await request.get(`${BACKEND_URL}/api/cpq/quotations/${QUOTATION_ID}/excel-view`);
  expect(resp.status()).toBe(200);
  const body = await resp.json();
  const data = body?.data ?? body;
  const columns: any[] = Array.isArray(data?.columns) ? data.columns : [];
  const rows: any[] = Array.isArray(data?.rows) ? data.rows : [];

  console.log(`📋 columns: ${JSON.stringify(columns.map((c: any) => ({ k: c.col_key, t: c.source_type })))}`);
  rows.forEach((r: any, i: number) => console.log(`  row[${i}]: A=${r.A}`));

  expect(columns.filter((c: any) => c.source_type === 'CARD_FORMULA').length).toBeGreaterThanOrEqual(1);
  expect(rows.length).toBeGreaterThanOrEqual(1);
  for (let i = 0; i < rows.length; i++) {
    const v = typeof rows[i].A === 'number' ? rows[i].A : parseFloat(String(rows[i].A));
    expect(isNaN(v), `row[${i}].A 应为数值`).toBe(false);
    expect(Math.abs(v - EXPECTED_SUM), `row[${i}].A=${v} 应≈${EXPECTED_SUM}`).toBeLessThan(0.01);
  }
  console.log('✅ API CARD_FORMULA 全行 ≈ 515.5632');
});

// ── 测试 2（UI）：报价单 Excel 视图渲染 ───────────────────────────────
test('UI Excel 视图: 第一列料号 + CARD_FORMULA 列 ≈ 515.5632 + 加载中=0', async ({ page }) => {
  test.skip(!backendUp || !setupDone, '后端未启动或夹具失败');

  await loginAsAdmin(page);
  expect(page.url(), '登录后不应在 /login').not.toContain('/login');
  await shot(page, 'after-login');

  await page.goto(`/quotations/${QUOTATION_ID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);
  // 若停在 step1，点下一步进 step2
  const nextBtn = page.locator('button', { hasText: /下一步|继续/ }).first();
  if (await nextBtn.count() > 0 && await nextBtn.isEnabled().catch(() => false)) {
    await nextBtn.click().catch(() => {});
    await page.waitForTimeout(1500);
  }
  await shot(page, 'step2');

  // 切到 Excel 视图
  const excelTab = page.locator('.ant-segmented-item', { hasText: 'Excel 视图' }).first();
  await expect(excelTab, '应有 Excel 视图切换').toBeVisible({ timeout: 8000 });
  await excelTab.click();
  await page.waitForTimeout(3500);  // 等后端 getExcelView + 渲染
  await shot(page, 'excel-view');

  // 加载中=0
  const loading = await page.locator('text=加载中').count();
  console.log(`[excel] 加载中 count = ${loading}`);
  expect(loading, '加载中应为 0').toBe(0);

  // 表头
  const ths = page.locator('.ant-table-thead th');
  const n = await ths.count();
  const headers: string[] = [];
  for (let i = 0; i < n; i++) headers.push((await ths.nth(i).innerText().catch(() => '')).trim());
  console.log(`📋 表头: ${headers.map(h => `"${h}"`).join(', ')}`);
  expect(headers[0], '第一列应为 料号').toBe('料号');

  // 数据行 + CARD_FORMULA 列(第2列)值 ≈ 515.5632
  const rows = page.locator('.ant-table-tbody tr.ant-table-row');
  const rc = await rows.count();
  console.log(`📋 数据行数 = ${rc}`);
  expect(rc, '应有数据行').toBeGreaterThanOrEqual(1);

  let asserted = 0;
  for (let r = 0; r < rc; r++) {
    const cellText = (await rows.nth(r).locator('td').nth(1).innerText().catch(() => '')).trim();
    const num = parseFloat(cellText.replace(/,/g, '').replace(/%$/, ''));
    console.log(`  row[${r}] A 单元格 = "${cellText}" → ${num}`);
    expect(cellText, `row[${r}] A 不应为 —/空`).not.toMatch(/^(—|-|)$/);
    if (!isNaN(num)) { expect(Math.abs(num - EXPECTED_SUM)).toBeLessThan(0.1); asserted++; }
  }
  expect(asserted, '至少 1 行 A 值 ≈ 515.5632').toBeGreaterThanOrEqual(1);
  await shot(page, 'final');
  console.log('✅ UI Excel 视图 CARD_FORMULA 渲染断言通过');
});
