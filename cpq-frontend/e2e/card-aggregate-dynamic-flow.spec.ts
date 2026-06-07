/**
 * E2E: Excel 卡片公式 聚合 WHERE 动态查找键（第二期）完整闭环
 *
 * 业务约束导致的闭环形态（已与用户确认 + 兜底纪律）：
 *   - 报价单只能绑 PUBLISHED 模板；PUBLISHED 模板 excel_view_config 在 UI 只读（disabled={!isDraft}）。
 *   - 故"单一模板 配→渲染 一条龙"不可达，采用同构桥接：
 *     · UI 配置端 = DRAFT 模板 14999990（关联同一工序组件 5c47fb41）真实抽屉点配两个同页签聚合 → 保存 → DB 断言落库结构（证 Task3/4）。
 *     · 渲染端    = 注入同构配置到 PUBLISHED d16dd592（QT-1497 绑定、有工序数据）→ getExcelView 真实链路断言聚合值（证 Task1/2）。
 *
 * 数据纪律（仿 card-formula-flow.spec.ts）：
 *   - 仅临时写两模板 excel_view_config；beforeAll 用备份表 zz_evc_bak 存原值，afterAll 还原 + DROP。
 *   - 绝不改 quotation_line_item.template_id，绝不动组件数据。
 *
 * 夹具：
 *   - 报价单 QT-20260602-1497 (b0fec225)，料号 3120012004/05/06
 *   - 工序组件 5c47fb41:3，每行：Z012/10110003/126.3158、Z011/10110003/136.3636、Z028/10110003/122.449、Z013/10110002/130.4348
 *   - 渲染端期望：A = SUM(工序代码==Z011) + SUM(子件==10110002) = 136.3636 + 130.4348 = 266.7984；B = 动态 子件==料号 → 0
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { execSync } from 'child_process';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const __filename = fileURLToPath(import.meta.url);
const SHOT_DIR = path.join(path.dirname(__filename), 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

const BACKEND_URL = 'http://localhost:8081';
const QUOTATION_ID = 'b0fec225-20da-4668-8d83-39616f4c4e31';
const RENDER_TMPL = 'd16dd592-7c8c-481e-be78-3bddffc0bfd2'; // PUBLISHED，QT-1497 绑定，有工序数据
// 自包含专属 DRAFT 测试模板（beforeAll 克隆 d16dd592 结构 + 工序组件关联，afterAll 删除）；
// 不复用环境里现成 DRAFT，因并发会话可能把它发布成 PUBLISHED 导致 UI 只读。
const DRAFT_TMPL = 'aaaaaaaa-0000-0000-0000-0000000e2e01';
const PROC_TAB = '5c47fb41-f092-4ef8-a960-bce07c93ded0:3';

const EXPECTED_A = 266.7984; // 136.3636 + 130.4348

// 渲染端注入配置（结构与 UI 配出的一致：同页签多聚合 唯一 keying + 动态 condRows）
const RENDER_CFG = JSON.stringify([
  {
    col_key: 'A', title: '同页签双聚合(CARD)', source_type: 'CARD_FORMULA',
    formula: "=SUM_OVER([工序#1] WHERE c0=='Z011', c1) + SUM_OVER([工序#2] WHERE c0=='10110002', c1)",
    refs: {
      '工序#1': { tab: PROC_TAB, cols: { c0: '工序代码', c1: '小计' } },
      '工序#2': { tab: PROC_TAB, cols: { c0: '子件', c1: '小计' } },
    },
  },
  {
    col_key: 'B', title: '动态空键(CARD)', source_type: 'CARD_FORMULA',
    formula: '=SUM_OVER([工序#3], c1)',
    refs: {
      '工序#3': {
        tab: PROC_TAB, cols: { c0: '子件', c1: '小计' },
        condRows: [{ left: '子件', op: 'eq', logic: 'and', rhs: { type: 'product', value: '__partNo__' } }],
      },
    },
  },
]);

function psql(sql: string): string {
  try {
    return execSync('PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db',
      { input: sql, encoding: 'utf-8', stdio: ['pipe', 'pipe', 'pipe'] });
  } catch (e: any) {
    const msg = e.stdout ?? e.stderr ?? String(e);
    console.warn('[psql] warn:', msg.slice(0, 300));
    return msg;
  }
}

let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `cad-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}

// AntD Select：点开后在可见下拉里按文本选 option
async function pickOption(page: Page, opener: ReturnType<Page['locator']>, optionText: string) {
  await opener.click();
  const opt = page.locator('.ant-select-dropdown:visible .ant-select-item-option', { hasText: optionText }).first();
  await opt.waitFor({ state: 'visible', timeout: 5000 });
  await opt.click();
}

let backendUp = false;
let setupDone = false;

test.beforeAll(async () => {
  backendUp = await isBackendUp();
  if (!backendUp) { console.log('[beforeAll] 后端未启动'); return; }
  // 备份两模板 excel_view_config（备份表），再写渲染端配置
  psql(`DROP TABLE IF EXISTS zz_evc_bak;
        CREATE TABLE zz_evc_bak AS SELECT id, excel_view_config FROM template WHERE id IN ('${RENDER_TMPL}','${DRAFT_TMPL}');`);
  const bak = psql(`SELECT count(*) FROM zz_evc_bak;`);
  console.log('[beforeAll] 备份行数:', bak.replace(/\s+/g, ' ').trim());
  // 渲染端注入（dollar-quote 防转义）
  psql(`UPDATE template SET excel_view_config = $cfg$${RENDER_CFG}$cfg$::jsonb WHERE id = '${RENDER_TMPL}';`);
  // 新建自包含 DRAFT 测试模板（克隆 d16dd592 整行 + 工序组件关联）
  psql(`DELETE FROM template_component WHERE template_id='${DRAFT_TMPL}';
        DELETE FROM template WHERE id='${DRAFT_TMPL}';
        INSERT INTO template
        SELECT r.* FROM template t,
          LATERAL jsonb_populate_record(NULL::template,
            to_jsonb(t) || jsonb_build_object('id','${DRAFT_TMPL}','status','DRAFT','name','__E2E聚合动态测试模板','version',NULL,'excel_view_config',NULL)) AS r
        WHERE t.id='${RENDER_TMPL}';
        INSERT INTO template_component
        SELECT r.* FROM template_component tc,
          LATERAL jsonb_populate_record(NULL::template_component,
            to_jsonb(tc) || jsonb_build_object('id', gen_random_uuid()::text, 'template_id','${DRAFT_TMPL}')) AS r
        WHERE tc.template_id='${RENDER_TMPL}';`);
  const chk = psql(`SELECT status FROM template WHERE id='${DRAFT_TMPL}';`);
  console.log('[beforeAll] DRAFT 测试模板:', chk.replace(/\s+/g, ' ').trim());
  setupDone = true;
});

test.afterAll(async () => {
  if (!backendUp) return;
  // 还原 RENDER_TMPL + 清备份表 + 删除自建 DRAFT 测试模板
  const out = psql(`UPDATE template t SET excel_view_config = b.excel_view_config FROM zz_evc_bak b WHERE t.id = b.id;
                    DROP TABLE IF EXISTS zz_evc_bak;
                    DELETE FROM template_component WHERE template_id='${DRAFT_TMPL}';
                    DELETE FROM template WHERE id='${DRAFT_TMPL}';`);
  console.log('[afterAll] 还原+清理:', out.replace(/\s+/g, ' ').trim().slice(0, 160));
});

// ── 测试 1（渲染端 · 真实 getExcelView 链路）：动态 SUMIF + 同页签多聚合互不串 + 空键→0 ──
test('渲染端: A=两同页签聚合互不串≈266.7984 + B=动态空键→0', async ({ request }) => {
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
  rows.forEach((r: any, i: number) => console.log(`  row[${i}]: A=${r.A} B=${r.B}`));

  expect(rows.length, '应有数据行').toBeGreaterThanOrEqual(1);
  let assertedA = 0;
  for (let i = 0; i < rows.length; i++) {
    const a = typeof rows[i].A === 'number' ? rows[i].A : parseFloat(String(rows[i].A));
    const b = typeof rows[i].B === 'number' ? rows[i].B : parseFloat(String(rows[i].B));
    expect(isNaN(a), `row[${i}].A 应为数值`).toBe(false);
    expect(Math.abs(a - EXPECTED_A), `row[${i}].A=${a} 应≈${EXPECTED_A}（同页签两聚合互不串）`).toBeLessThan(0.01);
    expect(Math.abs(b), `row[${i}].B=${b} 应=0（动态 子件==料号 无匹配）`).toBeLessThan(0.0001);
    assertedA++;
  }
  expect(assertedA).toBeGreaterThanOrEqual(1);
  console.log(`✅ 渲染端真实链路：A≈266.7984 互不串 + B=0 空键，共 ${assertedA} 行`);
});

// ── 测试 2（UI 配置端 · 真实抽屉点配 → 保存 → DB 断言落库）：唯一 keying + 动态省WHERE ──
test('UI 配置端: DRAFT 抽屉配两个同页签聚合(静态#1+动态#2) → 保存 → DB 落库 工序#1/工序#2 独立 ref', async ({ page }) => {
  test.skip(!backendUp || !setupDone, '后端未启动或夹具失败');

  await loginAsAdmin(page);
  expect(page.url()).not.toContain('/login');

  await page.goto(`/templates/${DRAFT_TMPL}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  await shot(page, 'tmpl-config');

  // 切 Excel 视图
  await page.locator('button.tm-view-btn', { hasText: 'Excel视图' }).first().click();
  await page.waitForTimeout(1200);
  await shot(page, 'excel-view-mode');

  // 添加列 → 卡片公式 CARD_FORMULA
  await page.getByRole('button', { name: '添加列' }).click();
  await page.locator('.ant-dropdown-menu-item', { hasText: '卡片公式 CARD_FORMULA' }).first().click();
  await page.waitForTimeout(800);
  await shot(page, 'col-added');

  // 打开新列的卡片公式抽屉：定位 CARD_FORMULA 行（input placeholder 含"编辑卡片公式"）内的按钮
  const cardRow = page.locator('.ant-space-compact')
    .filter({ has: page.locator('input[placeholder*="编辑卡片公式"]') }).first();
  await cardRow.scrollIntoViewIfNeeded();
  await cardRow.getByRole('button').first().click();
  await page.waitForTimeout(1000);
  await shot(page, 'after-edit-click');
  const drawer = page.locator('.ant-drawer-content-wrapper').first();
  await expect(drawer.getByText('插入卡片引用')).toBeVisible({ timeout: 8000 });
  await shot(page, 'drawer-open');

  // 选页签 = 工序
  await pickOption(page, drawer.locator('.ant-form-item:has(label:text-is("选择页签")) .ant-select'), '工序');
  // 引用类型 = 聚合
  await drawer.getByText('聚合（SUM/AVG…）').click();
  await page.waitForTimeout(400);
  // 行内聚合表达式 = 小计
  await drawer.locator('.ant-form-item:has-text("行内聚合表达式") input').fill('小计');

  // 条件构建器：条件行 ant-select 顺序 = [字段(0), 运算符(1), 值来源(2), 值select(3,仅product/column)]
  const condBox = drawer.locator('.ant-form-item:has-text("条件构建器")');
  // ── 聚合 #1（静态 literal）：工序代码 等于 Z011（值来源默认字面量）──
  await pickOption(page, condBox.locator('.ant-select').nth(0), '工序代码'); // 字段
  await condBox.getByPlaceholder('值', { exact: true }).fill('Z011');        // literal 值（排除字段 select 的搜索框）
  await shot(page, 'cond1-literal');
  await drawer.getByRole('button', { name: '插入到公式光标处' }).click();
  await page.waitForTimeout(600);
  const f1 = await drawer.locator('textarea').first().inputValue();
  console.log('[公式 after #1]', f1);
  expect(f1).toContain('SUM_OVER([工序#1]');
  expect(f1, '#1 静态应保留 WHERE 且含填入的 Z011').toContain("WHERE c0=='Z011'");

  // ── 聚合 #2（动态 product）：子件 等于 料号(__partNo__) → 省 WHERE ──
  await pickOption(page, condBox.locator('.ant-select').nth(0), '子件');     // 字段
  await pickOption(page, condBox.locator('.ant-select').nth(2), '产品字段'); // 值来源 → product
  await page.waitForTimeout(300);
  await pickOption(page, condBox.locator('.ant-select').nth(3), '料号');     // 值 = 料号(__partNo__)
  await shot(page, 'cond2-product');
  await drawer.getByRole('button', { name: '插入到公式光标处' }).click();
  await page.waitForTimeout(600);
  const f2 = await drawer.locator('textarea').first().inputValue();
  console.log('[公式 after #2]', f2);
  expect(f2).toContain('SUM_OVER([工序#2]');
  expect(f2, '#2 动态应省略 WHERE').toMatch(/SUM_OVER\(\[工序#2\],/);

  // refs（工序#1 cols / 工序#2 condRows）已由 UI 点配注册；公式文本因光标插入顺序需整理为合法式
  // （用 UI 生成的同一组 token，仅修排版以通过 validateCardFormula 落库）
  await drawer.locator('textarea').first().fill(
    "=SUM_OVER([工序#1] WHERE c0=='Z011', c1) + SUM_OVER([工序#2], c1)");

  // 抽屉保存：抽屉内唯一 primary 按钮即「保存」（AntD 对 2 中文字按钮自动插空格，name 匹配不可靠）
  await page.locator('.ant-drawer .ant-btn-primary').last().click();
  await page.waitForTimeout(1200);
  await shot(page, 'drawer-saved');

  // 保存 Excel 视图配置（ExcelViewConfigTab 的「保存配置」按钮才持久化 excel_view_config）
  await page.locator('button:has-text("保存配置")').first().click();
  await page.getByText('Excel 视图配置已保存').waitFor({ state: 'visible', timeout: 8000 }).catch(() => {});
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(1500);
  await shot(page, 'tmpl-saved');

  // ── DB 断言：excel_view_config 落库含 工序#1 + 工序#2 两独立 ref（轮询等落库）──
  let dump = '';
  for (let i = 0; i < 6; i++) {
    dump = psql(`SELECT COALESCE(excel_view_config::text,'__NULL__') FROM template WHERE id='${DRAFT_TMPL}';`);
    if (dump.includes('工序#1')) break;
    console.log(`[DB poll ${i}]`, dump.replace(/\s+/g, ' ').trim().slice(0, 80));
    await new Promise(r => setTimeout(r, 1000));
  }
  console.log('[DB excel_view_config]', dump.replace(/\s+/g, ' ').trim().slice(0, 700));
  expect(dump).toContain('工序#1');
  expect(dump).toContain('工序#2');
  // 工序#1 cols 含 工序代码（静态）；工序#2 含 condRows + 子件（动态 product）
  expect(dump).toContain('工序代码');
  expect(dump).toContain('condRows');
  expect(dump).toContain('__partNo__');
  console.log('✅ UI 配置端：DRAFT 抽屉配两同页签聚合(静态#1+动态#2) 经 UI→API→DB 落库为 工序#1/工序#2 独立 ref');
});
