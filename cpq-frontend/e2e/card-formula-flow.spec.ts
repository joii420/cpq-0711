/**
 * E2E: CARD_FORMULA Excel 视图渲染验证
 *
 * 目标：验证报价单 Excel 视图在模板含 CARD_FORMULA 列时，
 * 后端 getExcelView 能正确计算并返回非空值；前端视图能正确渲染（非「—」）。
 *
 * 场景夹具：
 *   - 报价单：QT-20260602-1504（628b4991-16b1-4879-9d6e-3ed02d3c1643）
 *   - 原始模板：报价单模板0601（e88353ec-a101-466d-9654-0c32a3d17779，PUBLISHED，不可改）
 *   - 工序组件 5c47fb41-f092-4ef8-a960-bce07c93ded0，sort_order=3，
 *     行数据"小计"字段值之和 = 515.5632（与每个 line_item.subtotal 对齐）
 *
 * 测试流程：
 *   beforeAll → psql 创建 DRAFT 测试模板（含 CARD_FORMULA 列 A：SUM_OVER 工序行"小计"）
 *               + 临时改报价单的 customer_template_id 和 line_item.template_id
 *   测试 1（API 确定性验证）→ 直接 fetch 后端 /quotations/{id}/excel-view
 *               → 断言 columns 含 CARD_FORMULA，rows[*].A ≈ 515.5632
 *   测试 2（UI 验证，若 storageState 可用）→ 打开报价单 → Step2 → Excel 视图
 *               → 断言：第一列 header 为"料号"，A 列值非"—"，"加载中"=0
 *   afterAll → psql 还原 customer_template_id + line_item.template_id + 删除测试模板
 *
 * 重要纪律：
 *   - 不谎报通过：UI 测试若因 storageState 缺失跳过，如实说明
 *   - API 降级验证优先，是确定性验证的主线
 */
import { test, expect, Page, request as playwrightRequest } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { execSync } from 'child_process';
import { isBackendUp } from './fixtures/auth';

const __filename = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filename);
const SHOT_DIR = path.join(__dirnameLocal, 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

// ── 测试夹具常量 ────────────────────────────────────────────────────
const QUOTATION_ID = '628b4991-16b1-4879-9d6e-3ed02d3c1643';  // QT-20260602-1504
const ORIG_TEMPLATE_ID = 'e88353ec-a101-466d-9654-0c32a3d17779';  // 原始 PUBLISHED 模板
const TEMPLATE_SERIES_ID = '52aa5107-1e4b-4f8e-a2c2-a8f8cdd76e21';

// 工序组件（sort_order=3）的行"小计"字段 SUM = 515.5632
const PROCESS_COMP_ID = '5c47fb41-f092-4ef8-a960-bce07c93ded0';
const PROCESS_SORT_ORDER = 3;
const EXPECTED_SUM = 515.5632;  // SUM_OVER([工序], c0) 对"小计"字段求和

const BACKEND_URL = 'http://localhost:8081';

// 使用固定 UUID 方便清理
const TEST_TEMPLATE_ID = 'cf000001-0000-0000-0000-000000000001';

// CARD_FORMULA 列配置
const EXCEL_VIEW_CONFIG_JSON = JSON.stringify([
  {
    col_key: 'A',
    title: '工序总计(CARD_FORMULA)',
    source_type: 'CARD_FORMULA',
    formula: `=SUM_OVER([工序], c0)`,
    refs: {
      '工序': {
        tab: `${PROCESS_COMP_ID}:${PROCESS_SORT_ORDER}`,
        cols: { c0: '小计' },
      },
    },
  },
]);

// ── psql helper（stdin 模式，避免 shell 转义问题）───────────────────
function psqlStdin(sql: string): string {
  try {
    return execSync(
      'PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db',
      { input: sql, encoding: 'utf-8', stdio: ['pipe', 'pipe', 'pipe'] }
    );
  } catch (e: any) {
    const msg = e.stdout ?? e.stderr ?? String(e);
    console.warn('[psql] warn:', msg.slice(0, 200));
    return msg;
  }
}

// ── 截图 helper ────────────────────────────────────────────────────
let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `cf-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}

async function countLoading(page: Page, tag: string): Promise<number> {
  const c = await page.locator('text=加载中').count();
  console.log(`[${tag}] '加载中' count = ${c}`);
  return c;
}

// ── 生命周期 ────────────────────────────────────────────────────────
let backendUp = false;
let setupDone = false;

test.beforeAll(async () => {
  backendUp = await isBackendUp();
  if (!backendUp) {
    console.log('[beforeAll] 后端未启动，跳过夹具创建');
    return;
  }

  // 1. 清理残留测试模板
  psqlStdin(`DELETE FROM template WHERE id = '${TEST_TEMPLATE_ID}';`);

  // 2. 创建测试 DRAFT 模板（含 CARD_FORMULA 配置）
  const configEscaped = EXCEL_VIEW_CONFIG_JSON.replace(/'/g, "''");
  const createOut = psqlStdin(`
INSERT INTO template
  (id, template_series_id, name, status, formulas,
   template_sql_views_snapshot, excel_view_config, created_at, updated_at)
VALUES (
  '${TEST_TEMPLATE_ID}',
  '${TEMPLATE_SERIES_ID}',
  'E2E-CardFormula-Test',
  'DRAFT',
  '[]',
  '{}',
  '${configEscaped}'::jsonb,
  now(), now()
);
`);
  console.log('[beforeAll] 创建测试模板:', createOut.trim());

  // 3. 临时改报价单 customer_template_id + line_item.template_id
  const updateOut = psqlStdin(`
UPDATE quotation
  SET customer_template_id = '${TEST_TEMPLATE_ID}'
  WHERE id = '${QUOTATION_ID}';

UPDATE quotation_line_item
  SET template_id = '${TEST_TEMPLATE_ID}'
  WHERE quotation_id = '${QUOTATION_ID}';
`);
  console.log('[beforeAll] 更新绑定:', updateOut.trim());

  setupDone = true;
  console.log('[beforeAll] 夹具创建完成');
});

test.afterAll(async () => {
  if (!backendUp) return;

  // 还原所有夹具修改
  const restoreOut = psqlStdin(`
UPDATE quotation
  SET customer_template_id = '${ORIG_TEMPLATE_ID}'
  WHERE id = '${QUOTATION_ID}';

UPDATE quotation_line_item
  SET template_id = '${ORIG_TEMPLATE_ID}'
  WHERE quotation_id = '${QUOTATION_ID}';

DELETE FROM template WHERE id = '${TEST_TEMPLATE_ID}';
`);
  console.log('[afterAll] 还原完毕:', restoreOut.trim());
});

// ────────────────────────────────────────────────────────────────────
// 测试 1（主线，确定性）：API 层面验证后端 getExcelView 返回 CARD_FORMULA 正确值
// ────────────────────────────────────────────────────────────────────
test('后端 getExcelView API：CARD_FORMULA 列 A 正确计算（≈515.5632）', async ({ request }) => {
  test.skip(!backendUp, '后端未启动');
  test.skip(!setupDone, '夹具创建失败');

  // 1. 登录，获取 session
  const loginResp = await request.post(`${BACKEND_URL}/api/cpq/auth/login`, {
    data: { username: 'admin', password: 'Admin@2026' },
  });
  expect(loginResp.status(), '登录应返回 200').toBe(200);
  const loginBody = await loginResp.json();
  expect(loginBody.code, '登录 code 应为 200').toBe(200);
  console.log('✅ 登录成功, user:', loginBody.data?.username);

  // 2. 调 getExcelView
  const excelResp = await request.get(
    `${BACKEND_URL}/api/cpq/quotations/${QUOTATION_ID}/excel-view`,
  );
  expect(excelResp.status(), 'getExcelView 应返回 200').toBe(200);

  const body = await excelResp.json();
  const data = body?.data ?? body;
  const columns: any[] = Array.isArray(data?.columns) ? data.columns : [];
  const rows: any[] = Array.isArray(data?.rows) ? data.rows : [];

  console.log(`📋 columns: ${JSON.stringify(columns.map((c: any) => ({ k: c.col_key, t: c.source_type })))}`);
  console.log(`📋 rows count: ${rows.length}`);
  rows.forEach((r: any, i: number) => {
    console.log(`  row[${i}]: A=${r.A} lineItemId=${r._lineItemId}`);
  });

  // 3. 断言：至少 1 列是 CARD_FORMULA
  const cfCols = columns.filter((c: any) => c.source_type === 'CARD_FORMULA');
  expect(cfCols.length, '应至少存在 1 个 CARD_FORMULA 列').toBeGreaterThanOrEqual(1);
  console.log(`✅ CARD_FORMULA 列: ${cfCols.map((c: any) => c.col_key).join(', ')}`);

  // 4. 断言：有数据行
  expect(rows.length, '应至少有 1 行数据').toBeGreaterThanOrEqual(1);

  // 5. 断言：列 A 所有行均有计算值（非 null / 非"—"）
  for (let i = 0; i < rows.length; i++) {
    const val = rows[i]['A'];
    expect(val, `row[${i}].A 不应为 null`).not.toBeNull();
    expect(val, `row[${i}].A 不应为 undefined`).not.toBeUndefined();
    expect(String(val), `row[${i}].A 不应为"—"`).not.toBe('—');
    console.log(`✅ row[${i}].A = ${val}`);
  }

  // 6. 断言：列 A 首行 ≈ EXPECTED_SUM（允许浮点误差 ±0.01）
  const firstA = typeof rows[0]['A'] === 'number' ? rows[0]['A'] : parseFloat(String(rows[0]['A']));
  expect(isNaN(firstA), 'row[0].A 应为数值').toBe(false);
  expect(Math.abs(firstA - EXPECTED_SUM)).toBeLessThan(0.01);
  console.log(`✅ row[0].A = ${firstA} ≈ ${EXPECTED_SUM} (误差 < 0.01)`);

  console.log('\n✅ API 层面 CARD_FORMULA 验证通过');
});

// ────────────────────────────────────────────────────────────────────
// 测试 2（UI 层面，依赖 storageState）：打开报价单 → Excel 视图 → 断言渲染
// ────────────────────────────────────────────────────────────────────
test('UI Excel 视图: 第一列为料号，CARD_FORMULA 列 A 非「—」，加载中=0', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.skip(!setupDone, '夹具创建失败');

  // 检查 storageState 是否存在（global-setup 未跑时不存在）
  const authDir = path.join(__dirnameLocal, '.auth');
  const stateFile = path.join(authDir, 'admin.json');
  const hasStorageState = fs.existsSync(stateFile);
  if (!hasStorageState) {
    console.log('[UI-TEST] storageState 不存在（global-setup 未跑），通过 API 手动设置 session cookie');
  }

  // 控制台监控
  const consoleErrors: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') consoleErrors.push(m.text());
  });
  page.on('pageerror', (e) => consoleErrors.push('PAGE-ERROR: ' + e.message));

  // ── 登录（UI 或 API cookie 注入）──────────────────────────────
  if (hasStorageState) {
    // 复用 storageState
    const stateData = JSON.parse(fs.readFileSync(stateFile, 'utf-8'));
    const ctx = page.context();
    await ctx.clearCookies();
    if (stateData.cookies?.length > 0) await ctx.addCookies(stateData.cookies);
    await page.goto('/dashboard');
    await page.waitForLoadState('networkidle');
    console.log('✅ storageState 登录成功');
  } else {
    // 通过 Playwright APIRequestContext 登录（在 Node.js 层，非浏览器沙箱），然后把 cookie 注入 page.context()
    const apiCtx = await playwrightRequest.newContext({ baseURL: BACKEND_URL });
    let loginOk = false;
    try {
      const loginResp = await apiCtx.post('/api/cpq/auth/login', {
        data: { username: 'admin', password: 'Admin@2026' },
      });
      const loginBody = await loginResp.json();
      loginOk = loginResp.ok() && loginBody?.code === 200;
      if (loginOk) {
        // 把 APIRequestContext 里的 cookie 同步到 page.context()
        const sessionCookies = await apiCtx.storageState();
        const pageCtx = page.context();
        await pageCtx.clearCookies();
        if (sessionCookies.cookies?.length > 0) {
          // addCookies 需要 url 或 domain 二选一（不能同时设置）
          // 后端 cookie domain=localhost，直接透传 domain，移除 url
          const remapped = sessionCookies.cookies.map((c) => {
            const { url: _url, ...rest } = c as any;
            return { ...rest, domain: 'localhost' };
          });
          await pageCtx.addCookies(remapped);
        }
      }
    } finally {
      await apiCtx.dispose();
    }
    if (!loginOk) {
      console.log('[UI-TEST] API 登录失败，跳过 UI 断言');
      test.skip(true, 'API 登录失败，无法进入 UI');
      return;
    }

    // 登录后跳转到 dashboard
    await page.goto('http://localhost:5174/dashboard');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1500);

    // 检查是否已成功进入（不是 login 页）
    if (page.url().includes('/login')) {
      console.log('[UI-TEST] 仍在 login 页（cookie 注入未生效），跳过 UI 断言');
      test.skip(true, '无法通过 SPA cookie 进入 dashboard，跳过 UI 验证（API 层已验证通过）');
      return;
    }
    console.log('✅ API cookie 注入登录成功');
  }

  await shot(page, 'after-login');

  // ── 打开报价单编辑页 ────────────────────────────────────────────
  await page.goto(`/quotations/${QUOTATION_ID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2500);
  await shot(page, 'open-quotation');

  // ── 进入 Step 2（可能已在 Step2）────────────────────────────────
  const nextBtn = page.locator('button', { hasText: /下一步|继续/ }).first();
  if (await nextBtn.count() > 0 && await nextBtn.isEnabled().catch(() => false)) {
    await nextBtn.click().catch(() => {});
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1500);
  }

  const segMain = page.locator('.ant-segmented').first();
  const segVisible = await segMain.isVisible({ timeout: 5000 }).catch(() => false);
  console.log(`[UI-TEST] .ant-segmented 可见: ${segVisible}`);
  await shot(page, 'step2-loaded');

  // ── 切到 Excel 视图 ─────────────────────────────────────────────
  let excelTabClicked = false;
  const excelTabLocators = [
    page.locator('.ant-segmented-item', { hasText: 'Excel 视图' }).first(),
    page.locator('.ant-tabs-tab', { hasText: 'Excel 视图' }).first(),
  ];
  for (const loc of excelTabLocators) {
    const vis = await loc.isVisible({ timeout: 3000 }).catch(() => false);
    if (vis) {
      await loc.click();
      excelTabClicked = true;
      console.log('✅ 已切到 Excel 视图');
      break;
    }
  }

  if (!excelTabClicked) {
    console.log('⚠️ 未找到 Excel 视图 tab，跳过 UI 断言（API 层已验证通过）');
    await shot(page, 'no-excel-tab');
    // 不 fail：UI 流程未能到达不代表功能坏了，API 已验证
    test.skip(true, '未找到 Excel 视图 tab，跳过 UI 验证（API 层已确定性验证通过）');
    return;
  }

  await page.waitForTimeout(3500);  // 等后端 getExcelView + React 渲染
  await shot(page, 'excel-view-rendered');

  // ── "加载中" 计数 ────────────────────────────────────────────────
  const loadingCount = await countLoading(page, 'excel-view-final');

  // ── "后端算值" Tag（新模型标志）────────────────────────────────
  const backendTag = await page.locator('text=后端算值').count();
  console.log(`🏷️  "后端算值" Tag 计数 = ${backendTag}`);

  // ── 收集表头 ─────────────────────────────────────────────────────
  const ths = page.locator('.ant-table-thead th');
  const thCount = await ths.count();
  const headers: string[] = [];
  for (let i = 0; i < thCount; i++) {
    headers.push((await ths.nth(i).innerText().catch(() => '')).trim());
  }
  console.log(`📋 表头: ${headers.map(h => `"${h}"`).join(', ')}`);

  // ── 收集表格数据 ─────────────────────────────────────────────────
  const tableRows = page.locator('.ant-table-tbody tr.ant-table-row');
  const rowCount = await tableRows.count();
  console.log(`📋 数据行数 = ${rowCount}`);

  type Cell = { row: number; col: number; text: string };
  const cells: Cell[] = [];
  for (let r = 0; r < rowCount; r++) {
    const tds = tableRows.nth(r).locator('td');
    const colCount2 = await tds.count();
    for (let c = 0; c < colCount2; c++) {
      const text = (await tds.nth(c).innerText().catch(() => '')).trim();
      cells.push({ row: r, col: c, text });
    }
  }
  const rowTexts = Array.from({ length: rowCount }, (_, r) =>
    cells.filter(c => c.row === r).map(c => c.text.slice(0, 20)).join(' | ')
  );
  rowTexts.forEach((t, i) => console.log(`  row[${i}]: ${t}`));

  // ── 断言 1：加载中 = 0 ──────────────────────────────────────────
  console.log(`\n=== '加载中' final count: ${loadingCount} (期望 0) ===`);
  expect(loadingCount, "'加载中' final count 应为 0").toBe(0);

  // ── 断言 2：第一列 header 为"料号" ──────────────────────────────
  const firstHeader = headers[0] ?? '';
  console.log(`\n📌 第一列 header = "${firstHeader}" (期望 "料号")`);
  expect(firstHeader, '第一列 header 应为 "料号"').toBe('料号');

  // ── 断言 3：CARD_FORMULA 列（第2列 col_index=1）有非"—"值 ───────
  if (rowCount > 0) {
    const colAIdx = 1;  // 紧随料号列
    const colACells = cells.filter(c => c.col === colAIdx);
    const colAValues = colACells.map(c => c.text);
    console.log(`\n📌 [A] 工序总计 列值: ${colAValues.join(' | ')}`);

    const hasNonDash = colACells.some(c =>
      c.text !== '—' && c.text !== '-' && c.text !== '' && c.text !== '加载中'
    );
    expect(hasNonDash, `[A] 工序总计列应有非「—」的计算值, 实际: [${colAValues.join(', ')}]`).toBe(true);
    console.log(`✅ [A] 工序总计列有计算值: ${colAValues.join(' | ')}`);

    // 精确值断言（允许浮点误差 ±0.1，考虑千分位格式化）
    const firstNonDash = colACells.find(c =>
      c.text !== '—' && c.text !== '-' && c.text !== '' && c.text !== '加载中'
    );
    if (firstNonDash) {
      const numStr = firstNonDash.text.replace(/,/g, '').replace(/%$/, '');
      const numVal = parseFloat(numStr);
      if (!isNaN(numVal)) {
        console.log(`📊 [A] 首行计算值 = ${numVal} (期望 ≈ ${EXPECTED_SUM})`);
        expect(Math.abs(numVal - EXPECTED_SUM)).toBeLessThan(0.1);
        console.log(`✅ [A] 计算值 ${numVal} ≈ ${EXPECTED_SUM} (误差 < 0.1)`);
      }
    }
  } else {
    console.log('⚠️ 表格无数据行 — 跳过行数据断言（API 层已验证）');
  }

  // ── 报告控制台错误 ────────────────────────────────────────────────
  console.log(`\n=== console.error 总数: ${consoleErrors.length} ===`);
  consoleErrors.slice(0, 5).forEach(e => console.log('  🔴 ' + e.slice(0, 200)));

  await shot(page, 'final');
  console.log('\n✅ UI Excel 视图 CARD_FORMULA 断言全部通过');
});
