/**
 * repair-260916（小计后缀）· 测试分片 S-C · T-C4 / T-C5④(界面)
 *
 * AC-15⑤ 打开 QT-20260916-0881 编辑页 Excel 视图，材料成本 / 回收价格 / 产品单价 = 1.978941064 / 0.317766357 / 1.804589425
 * AC-15⑥ GET /api/cpq/quotations/{id}/excel-view（后端重算）该行三列值与⑤相同
 * AC-16⑤（D-7，正式证据，分支栈）导入预览：1.2 包不出现「导入包是旧格式(bundleVersion …)，不含取数配置器信息」，v1.0 包出现且版本号 1.0；截图为证。
 *   同一用例带 RP_SC_AB_LABEL=master 在 master 栈上跑，只采集作 AC-16④ 界面侧参考（④ 正式证据为 T-C5 接口侧 A/B）。
 *   ⚠️ 导入入口选择器未经实测（测试员不可读前端实现），解锁后先在一次性库探测一次，记录落 证据/测试/S-C/探测/。
 *
 * 只允许打连 cpq_db_rp0916c 的临时栈（5293 → 8293）；启动前库身份与端口由 stack.sh 验明正身。
 * 证据写入任务目录 证据/测试/S-C/out-C4/（不放 test-results，下一轮会被清空）。
 */
import { test, expect, Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import * as fs from 'node:fs';
import * as path from 'node:path';

const WT = '/home/joii/project/cpq/.claude/worktrees/repair-260916-subtotal-suffix';
const T = path.join(WT, 'dev-docs/task-260801-页签连表公式配置优化/repair-260916-小计列无法选为字段引用');
const OUT = path.join(T, '证据/测试/S-C', process.env.RP_SC_OUT || 'out-C4');
fs.mkdirSync(OUT, { recursive: true });

const QNO = 'QT-20260916-0881';
const EXPECT: Record<string, string> = { '材料成本': '1.978941064', '回收价格': '0.317766357', '产品单价': '1.804589425' };
const COLKEY: Record<string, string> = { '材料成本': 'col_1', '回收价格': 'col_2', '产品单价': 'col_3' };

function sql(q: string): string {
  return execFileSync('psql', ['-h', '10.177.152.12', '-U', 'postgres', '-d', 'cpq_db_rp0916c', '-At', '-F', '|', '-c', q],
    { env: { ...process.env, PGPASSWORD: 'joii5231' }, encoding: 'utf-8' }).trim();
}

test.beforeAll(() => {
  expect(process.env.PW_BASE_URL, '必须指向临时 vite 5293').toBe('http://localhost:5293');
  expect(process.env.PW_BACKEND_URL, '必须指向临时后端 8293').toBe('http://localhost:8293');
  const c = sql("SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()");
  expect(c.startsWith('repair-260916-subtotal-suffix 一次性库'), `库身份: ${c}`).toBeTruthy();
});

async function apiLogin(page: Page) {
  const r = await page.request.post('/api/cpq/auth/login', { data: { username: 'admin', password: 'Admin@2026' } });
  expect(r.ok(), `API 登录应成功（${r.status()}）`).toBeTruthy();
}

const norm = (s: string) => (s ?? '').replace(/[¥,\s]/g, '');

/** 在任意 JSON 里找含 col_1/col_2/col_3 三键的对象（excel-view 响应结构未在文档中细化，按键名定位，不猜路径） */
function findRows(o: unknown, acc: Record<string, unknown>[] = []): Record<string, unknown>[] {
  if (Array.isArray(o)) o.forEach((x) => findRows(x, acc));
  else if (o && typeof o === 'object') {
    const r = o as Record<string, unknown>;
    if (['col_1', 'col_2', 'col_3'].every((k) => k in r)) acc.push(r);
    Object.values(r).forEach((v) => findRows(v, acc));
  }
  return acc;
}

/** 9 位显示口径：半入到 9 位后比较（原始值另行打印；若后端返回超过 9 位也如实记录） */
function to9(v: unknown): string {
  const s = String(v ?? '');
  if (!/^-?\d+(\.\d+)?$/.test(s)) return `非数值:${s}`;
  const [i, f = ''] = s.replace(/^-/, '').split('.');
  let digits = BigInt(i + (f + '0000000000').slice(0, 9));
  if (Number((f + '0000000000')[9]) >= 5) digits += 1n;
  const t = digits.toString().padStart(10, '0');
  return (s.startsWith('-') ? '-' : '') + t.slice(0, -9) + '.' + t.slice(-9);
}

test('AC-15⑤⑥ · 0881 Excel 视图三列值（迁移后与迁移前相同）', async ({ page }) => {
  const row = sql(`select q.id, li.id, li.quote_excel_values from quotation q join quotation_line_item li on li.quotation_id=q.id where q.quotation_number='${QNO}'`);
  console.log('[前置] 0881 行 =', row);
  const lines = row.split('\n').filter(Boolean);
  expect(lines.length, '0881 恰 1 个报价行（防空跑/防多行歧义）').toBe(1);
  const [QID, LID, qevBefore] = lines[0].split('|');
  fs.writeFileSync(path.join(OUT, 'quote_excel_values-打开前.txt'), `${new Date().toISOString()}\n${qevBefore}\n`);
  const mig = sql("select version, success from flyway_schema_history where description ilike '%repair260916%subtotal%'");
  console.log('[前置] 本任务迁移 =', mig);
  expect(mig, '一次性库上本任务迁移须已 success').toMatch(/\|t$/);
  const exprs = sql("select e->>'col_key'||'='||(e->>'expression') from component c, jsonb_array_elements(c.excel_columns) e where c.code='COMP-0011' order by 1");
  console.log('[前置] COMP-0011 列文字 =', exprs.replace(/\n/g, ' ; '));
  expect(exprs, '迁移后的新文字（确认测的是迁移后状态）').toContain('col_1=[物料.材料成本(小计)]');

  await apiLogin(page);

  // ⑥ 后端重算
  const ev = await page.request.get(`/api/cpq/quotations/${QID}/excel-view`);
  const evText = await ev.text();
  fs.writeFileSync(path.join(OUT, 'AC-15⑥-excel-view-原始响应.json'), evText);
  expect(ev.status(), 'excel-view 200').toBe(200);
  const hits = findRows(JSON.parse(evText));
  console.log('[⑥] 含 col_1..3 的对象数 =', hits.length, JSON.stringify(hits).slice(0, 600));
  expect(hits.length, '⑥ 响应里应找到恰 1 个行对象（非空保护）').toBe(1);
  for (const [label, want] of Object.entries(EXPECT)) {
    const raw = hits[0][COLKEY[label]];
    const v = raw && typeof raw === 'object' && 'value' in (raw as object) ? (raw as { value: unknown }).value : raw;
    console.log(`[⑥] ${label}(${COLKEY[label]}) 原始=${JSON.stringify(raw)} → 9位=${to9(v)}`);
    expect.soft(to9(v), `AC-15⑥ ${label}`).toBe(want);
  }

  // ⑤ 编辑页 Excel 视图（前端求值）
  await page.goto(`/quotations/${QID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(4000);
  let seg = page.locator('.ant-segmented-item').filter({ hasText: /Excel\s*视图/ }).first();
  if (!(await seg.isVisible().catch(() => false))) {
    // 编辑页若为分步向导，Excel 视图在产品步骤里：尝试点一次含「产品」的步骤
    const step = page.locator('.ant-steps-item').filter({ hasText: /产品/ }).first();
    if (await step.isVisible().catch(() => false)) { await step.click(); await page.waitForTimeout(4000); }
    seg = page.locator('.ant-segmented-item').filter({ hasText: /Excel\s*视图/ }).first();
  }
  await page.screenshot({ path: path.join(OUT, 'AC-15⑤-编辑页-切换前.png'), fullPage: true });
  await expect(seg, '编辑页应有「Excel 视图」入口').toBeVisible({ timeout: 40_000 });
  await seg.click();
  await expect(seg).toHaveClass(/ant-segmented-item-selected/, { timeout: 30_000 });
  await page.waitForTimeout(6000);

  // 定位含三列表头的那张表（页面第一张表可能是基本信息）
  const tables = page.locator('table');
  let idx = -1;
  for (let i = 0; i < (await tables.count()); i++) {
    const head = ((await tables.nth(i).locator('thead').innerText().catch(() => '')) || '').replace(/\s+/g, '');
    if (Object.keys(EXPECT).every((h) => head.includes(h))) { idx = i; break; }
  }
  expect(idx, '应找到表头同时含 材料成本/回收价格/产品单价 的表').toBeGreaterThanOrEqual(0);
  const table = tables.nth(idx);
  const headers = (await table.locator('thead th').allInnerTexts()).map((s) => s.replace(/\s+/g, ''));
  const rows = table.locator('tbody tr');
  const data: string[][] = [];
  for (let r = 0; r < (await rows.count()); r++) {
    const tds = rows.nth(r).locator('td');
    const cells: string[] = [];
    for (let c = 0; c < (await tds.count()); c++) {
      const td = tds.nth(c);
      cells.push((await td.locator('input').count()) ? await td.locator('input').first().inputValue() : (await td.innerText()).trim());
    }
    if (cells.some((x) => x !== '')) data.push(cells);
  }
  console.log('[⑤] 表头 =', JSON.stringify(headers));
  data.forEach((d) => console.log('[⑤] 行 =', JSON.stringify(d)));
  await table.screenshot({ path: path.join(OUT, 'AC-15⑤-编辑页-Excel视图表.png') });
  await page.screenshot({ path: path.join(OUT, 'AC-15⑤-编辑页-Excel视图-整页.png'), fullPage: true });
  fs.writeFileSync(path.join(OUT, 'AC-15⑤-表格文字.json'), JSON.stringify({ headers, data }, null, 2));
  const dataRows = data.filter((d) => JSON.stringify(d.map((x) => x.replace(/\s+/g, ''))) !== JSON.stringify(headers));
  expect(dataRows.length, '⑤ 恰 1 行数据（非空保护）').toBe(1);
  for (const [label, want] of Object.entries(EXPECT)) {
    const ci = headers.findIndex((h) => h === label);
    expect(ci, `表头「${label}」`).toBeGreaterThanOrEqual(0);
    // thead 与 tbody 列数不一致时（如选择列）按右对齐兜底并打印，便于人工复核
    const off = dataRows[0].length - headers.length;
    const cell = dataRows[0][ci + (off > 0 ? off : 0)];
    console.log(`[⑤] ${label} 显示=${cell}（列偏移 ${off}）`);
    expect.soft(norm(cell), `AC-15⑤ ${label}`).toBe(want);
  }
  const qevAfter = sql(`select quote_excel_values from quotation_line_item where id='${LID}'`);
  fs.writeFileSync(path.join(OUT, 'quote_excel_values-打开后.txt'), `${new Date().toISOString()}\n${qevAfter}\n`);
  console.log('[⑤] 打开后 quote_excel_values =', qevAfter);
  for (const [label, want] of Object.entries(EXPECT)) expect.soft(qevAfter, `打开后存值 ${label}`).toContain(`"${COLKEY[label]}": "${want}"`);
});

test('AC-16⑤（正式证据，分支栈）/ AC-16④ 界面侧参考（master 栈）· 导入预览的「旧格式」提示', async ({ page }) => {
  // RP_SC_AB_LABEL 缺省 = branch：跑 AC-16⑤ 正式断言；= master：只采集文字与截图作 AC-16④ 界面侧参考，不做 ⑤ 断言
  const label = process.env.RP_SC_AB_LABEL || 'branch';
  expect(['branch', 'master']).toContain(label);
  await apiLogin(page);
  const dirName = 'RP0916C-导入v10';
  const o5 = path.join(T, '证据/测试/S-C/out-C5'); fs.mkdirSync(o5, { recursive: true });
  const PKGS: Record<string, string> = {
    '1.2': path.join(o5, '导出-fromv1.1.json'),   // AC-16② 导出的包（T-C5 run 产出）
    'v1.0': path.join(T, '证据/夹具/施耐德成环检测-导出包-v1.0.json'),
    'v1.1': path.join(T, '证据/夹具/施耐德成环检测-导出包-v1.1.json'),
  };
  expect(fs.existsSync(PKGS['1.2']), '需先跑 T-C5 run 产出 1.2 导出包').toBeTruthy();
  expect(JSON.parse(fs.readFileSync(PKGS['1.2'], 'utf-8')).bundleVersion, '导出包版本').toBe('1.2');
  // 提示原文：导入包是旧格式(bundleVersion …)，不含取数配置器信息 —— 括号兼容全/半角，捕获版本号
  const HINT = /导入包是旧格式[（(]\s*bundleVersion\s*[:：=]?\s*["'“]?([^)）"'”\s]*)["'”]?\s*[)）]，?\s*不含取数配置器信息/;
  const result: Record<string, { hint: boolean; version: string | null; line: string }> = {};
  for (const ver of ['1.2', 'v1.0', 'v1.1']) {
    await page.goto('/components');
    await page.waitForLoadState('networkidle');
    const dir = page.locator('.cmm-dir').filter({ hasText: dirName }).first();
    await expect(dir, `应能看到目录 ${dirName}`).toBeVisible({ timeout: 20_000 });
    const head = dir.locator('.cmm-dir-head').first();
    await head.click({ button: 'right' }).catch(() => {});
    await page.waitForTimeout(500);
    let entry = page.getByText(/^导入/).first();
    if (!(await entry.isVisible().catch(() => false))) { await head.hover(); entry = page.getByRole('button', { name: /导\s*入/ }).first(); }
    await expect(entry, '应能找到「导入」入口（选择器未实测，失败先落探测记录报主线）').toBeVisible({ timeout: 10_000 });
    await entry.click();
    await page.locator('input[type=file]').first().setInputFiles(PKGS[ver]);
    const panel = page.locator('.ant-modal, .ant-drawer').filter({ hasText: /导入/ }).last();
    await expect(panel, '导入预览弹层可见').toBeVisible({ timeout: 20_000 });
    await page.waitForTimeout(4000);   // 等预览请求返回并渲染
    const text = (await panel.innerText().catch(() => '')) || '';
    await panel.screenshot({ path: path.join(OUT, `AC-16⑤-${label}-${ver}-导入预览.png`) }).catch(() => {});
    expect(text.length, `${ver} 预览弹层文字非空`).toBeGreaterThan(0);
    // 阳性保护：预览必须真的渲染出本包内容（ex1 组件名），否则「没有提示」可能只是没加载出来
    expect(text, `${ver} 预览里应出现包内组件 ex1（证明预览已渲染）`).toContain('ex1');
    const m = text.replace(/\n/g, ' ').match(HINT);
    result[ver] = { hint: /旧格式/.test(text), version: m ? m[1] : null, line: text.split('\n').filter((l) => /旧格式/.test(l)).join(' / ') };
    console.log(`[AC-16⑤ ${label}] ${ver}`, JSON.stringify(result[ver]));
    await page.keyboard.press('Escape');
    await page.waitForTimeout(800);
  }
  fs.writeFileSync(path.join(o5, `ab-ui-${label}.json`), JSON.stringify(Object.fromEntries(
    Object.entries(result).flatMap(([k, v]) => [[k, v.hint ? 'HINT' : 'NO_HINT'], [`${k}_text`, v.line]])), null, 2));
  if (label === 'branch') {
    expect.soft(result['1.2'].hint, `AC-16⑤ 1.2 包不应出现「旧格式」提示（实际: ${result['1.2'].line}）`).toBe(false);
    expect.soft(result['v1.0'].version, `AC-16⑤ v1.0 包应出现完整提示且版本号为 1.0（实际: ${result['v1.0'].line}）`).toBe('1.0');
  }
});
