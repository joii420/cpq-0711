/**
 * repair-260916（小计后缀）· 测试分片 S-C · T-C4（AC-15③④）/ T-C5 界面侧（AC-16⑤）—— D-15 修订版
 *
 * RP_SC_AB_LABEL = branch（缺省，本分支栈）| master（master 栈，连同一个一次性库）
 * AC-15③（仅 branch）打开 QT-20260916-0881 编辑页 Excel 视图，材料成本 / 回收价格 / 产品单价 = 1.978941064 / 0.317766357 / 1.804589425
 * AC-15④（两栈各跑）GET /api/cpq/quotations/{id}/excel-view 原始响应与三列值落盘；两两相同由 T-C5 compare 判定（不断言等于③，D-13 / BL-0304）
 * AC-16⑤（两栈各跑）导入预览：v1.0 包出现「导入包是旧格式(bundleVersion 1.0)…」提示、v1.1 包不出现；截图；两栈一致由 T-C5 compare 判定
 * ⚠️ 编辑页 Excel 视图入口与导入入口的选择器未经实测（测试员不可读前端实现），解锁后先在一次性库探测一次，记录落 证据/测试/S-C/探测/。
 *
 * 只允许打连 cpq_db_rp0916d 的临时栈（5293 → 8293）；启动前库身份与端口由 stack.sh 验明正身。
 * 证据写入任务目录 证据/测试/S-C/out-C4/（不放 test-results，下一轮会被清空）。
 */
import { test, expect, Page } from '@playwright/test';
import { execFileSync } from 'node:child_process';
import * as fs from 'node:fs';
import * as path from 'node:path';

const WT = '/home/joii/project/cpq/.claude/worktrees/repair-260916-subtotal-suffix';
const T = path.join(WT, 'dev-docs/task-260801-页签连表公式配置优化/repair-260916-小计列无法选为字段引用');
const OUT = path.join(T, '证据/测试/S-C', process.env.RP_SC_OUT || 'out-C4');
const LABEL = process.env.RP_SC_AB_LABEL || 'branch';
if (!['branch', 'master'].includes(LABEL)) throw new Error(`RP_SC_AB_LABEL 只能是 branch/master：${LABEL}`);
fs.mkdirSync(OUT, { recursive: true });

const QNO = 'QT-20260916-0881';
const EXPECT: Record<string, string> = { '材料成本': '1.978941064', '回收价格': '0.317766357', '产品单价': '1.804589425' };
const COLKEY: Record<string, string> = { '材料成本': 'col_1', '回收价格': 'col_2', '产品单价': 'col_3' };

function sql(q: string): string {
  return execFileSync('psql', ['-h', '10.177.152.12', '-U', 'postgres', '-d', 'cpq_db_rp0916d', '-At', '-F', '|', '-c', q],
    { env: { ...process.env, PGPASSWORD: 'joii5231' }, encoding: 'utf-8' }).trim();
}

test.beforeAll(() => {
  expect(process.env.PW_BASE_URL, '必须指向临时 vite 5293').toBe('http://localhost:5293');
  expect(process.env.PW_BACKEND_URL, '必须指向临时后端 8293').toBe('http://localhost:8293');
  const c = sql("SELECT shobj_description(oid,'pg_database') FROM pg_database WHERE datname=current_database()");
  expect(c.startsWith('repair-260916-subtotal-suffix 一次性库（第二个'), `库身份: ${c}`).toBeTruthy();
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


/** 前置：取 0881 唯一报价行；确认存量列仍是旧文字（D-15：不迁移） */
function precondition() {
  const row = sql(`select q.id, li.id, li.quote_excel_values from quotation q join quotation_line_item li on li.quotation_id=q.id where q.quotation_number='${QNO}'`);
  console.log(`[前置/${LABEL}] 0881 行 =`, row);
  const lines = row.split('\n').filter(Boolean);
  expect(lines.length, '0881 恰 1 个报价行（防空跑/防多行歧义）').toBe(1);
  const exprs = sql("select e->>'col_key'||'='||(e->>'expression') from component c, jsonb_array_elements(c.excel_columns) e where c.code='COMP-0011' order by 1");
  console.log(`[前置/${LABEL}] COMP-0011 列文字 =`, exprs.replace(/\n/g, ' ; '));
  expect(exprs, '存量列未被改写（D-15）').toBe('col_1=[物料.材料成本]\ncol_2=[物料.回收成本]\ncol_3=[产品小计(总计)]');
  const [QID, LID, qev] = lines[0].split('|');
  return { QID, LID, qev };
}

test('AC-15④ · 后端重算 excel-view 采样（两栈各一次，两两比较在 T-C5 compare）', async ({ page }) => {
  const { QID } = precondition();
  await apiLogin(page);
  const ev = await page.request.get(`/api/cpq/quotations/${QID}/excel-view`);
  const evText = await ev.text();
  fs.writeFileSync(path.join(OUT, `AC-15④-excel-view-${LABEL}-原始响应.json`), evText);
  expect(ev.status(), 'excel-view 200').toBe(200);
  const hits = findRows(JSON.parse(evText));
  console.log(`[④/${LABEL}] 含 col_1..3 的对象数 =`, hits.length, JSON.stringify(hits).slice(0, 600));
  expect(hits.length, '④ 响应里应找到恰 1 个行对象（非空保护）').toBe(1);
  const vals: Record<string, unknown> = {};
  for (const k of ['col_1', 'col_2', 'col_3']) {
    vals[k] = hits[0][k];
    expect(hits[0][k] ?? '', `④ ${k} 非空`).not.toBe('');
  }
  console.log(`[④/${LABEL}] 三列原始值 =`, JSON.stringify(vals));
  fs.writeFileSync(path.join(OUT, `AC-15④-values-${LABEL}.json`), JSON.stringify({ at: new Date().toISOString(), label: LABEL, values: vals }, null, 2));
});

test('AC-15③ · 0881 编辑页 Excel 视图三列显示值（本分支栈）', async ({ page }) => {
  test.skip(LABEL !== 'branch', '③ 只在本分支栈上断言');
  const { QID, LID, qev } = precondition();
  fs.writeFileSync(path.join(OUT, 'quote_excel_values-打开前.txt'), `${new Date().toISOString()}\n${qev}\n`);
  await apiLogin(page);
  await page.goto(`/quotations/${QID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(4000);
  let seg = page.locator('.ant-segmented-item').filter({ hasText: /Excel\s*视图/ }).first();
  if (!(await seg.isVisible().catch(() => false))) {
    // 探测（探测/探测记录.txt 11:51Z）：编辑页是 5 步向导，打开停在「选择客户」；点「下一步」进入「添加产品」后才有 Excel 视图分段
    await page.getByRole('button', { name: /下一步/ }).first().click();
    await expect(page.locator('.ant-steps-item-process'), '应进入「添加产品」步骤').toContainText('添加产品', { timeout: 30_000 });
    await page.waitForTimeout(6000);
    seg = page.locator('.ant-segmented-item').filter({ hasText: /Excel\s*视图/ }).first();
  }
  await page.screenshot({ path: path.join(OUT, 'AC-15③-编辑页-切换前.png'), fullPage: true });
  await expect(seg, '编辑页应有「Excel 视图」入口').toBeVisible({ timeout: 40_000 });
  await seg.click();
  await expect(seg).toHaveClass(/ant-segmented-item-selected/, { timeout: 30_000 });
  await page.waitForTimeout(6000);

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
  console.log('[③] 表头 =', JSON.stringify(headers));
  data.forEach((d) => console.log('[③] 行 =', JSON.stringify(d)));
  await table.screenshot({ path: path.join(OUT, 'AC-15③-编辑页-Excel视图表.png') });
  await page.screenshot({ path: path.join(OUT, 'AC-15③-编辑页-Excel视图-整页.png'), fullPage: true });
  fs.writeFileSync(path.join(OUT, 'AC-15③-表格文字.json'), JSON.stringify({ headers, data }, null, 2));
  const dataRows = data.filter((d) => JSON.stringify(d.map((x) => x.replace(/\s+/g, ''))) !== JSON.stringify(headers));
  expect(dataRows.length, '③ 恰 1 行数据（非空保护）').toBe(1);
  for (const [label, want] of Object.entries(EXPECT)) {
    // 表头实测形如「[col_1]材料成本」（探测记录 edit2.tableHeads）
    const ci = headers.findIndex((h) => h === label || h.endsWith(`]${label}`));
    expect(ci, `表头「${label}」`).toBeGreaterThanOrEqual(0);
    const off = dataRows[0].length - headers.length;   // tbody 多出选择列等时右对齐，并打印供人工复核
    const cell = dataRows[0][ci + (off > 0 ? off : 0)];
    console.log(`[③] ${label} 显示=${cell}（列偏移 ${off}）`);
    expect.soft(norm(cell), `AC-15③ ${label}`).toBe(want);
  }
  const qevAfter = sql(`select quote_excel_values from quotation_line_item where id='${LID}'`);
  fs.writeFileSync(path.join(OUT, 'quote_excel_values-打开后.txt'), `${new Date().toISOString()}\n${qevAfter}\n`);
  console.log('[③] 打开后 quote_excel_values =', qevAfter);
  for (const [label, want] of Object.entries(EXPECT)) expect.soft(qevAfter, `打开后存值 ${label}`).toContain(`"${COLKEY[label]}": "${want}"`);
});

test('AC-16⑤ · 导入预览「旧格式」提示：v1.0 有（版本 1.0）、v1.1 无（两栈各跑）', async ({ page }) => {
  await apiLogin(page);
  const dirName = 'RP0916C-导入v10';
  const o5 = path.join(T, '证据/测试/S-C/out-C5'); fs.mkdirSync(o5, { recursive: true });
  const PKGS: Record<string, string> = {
    'v1.0': path.join(T, '证据/夹具/施耐德成环检测-导出包-v1.0.json'),
    'v1.1': path.join(T, '证据/夹具/施耐德成环检测-导出包-v1.1.json'),
  };
  // 提示原文：导入包是旧格式(bundleVersion …)，不含取数配置器信息 —— 括号兼容全/半角，捕获版本号
  const HINT = /导入包是旧格式[（(]\s*bundleVersion\s*[:：=]?\s*["'“]?([^)）"'”\s]*)["'”]?\s*[)）]/;
  const result: Record<string, { hint: boolean; version: string | null; line: string }> = {};
  for (const ver of ['v1.0', 'v1.1']) {
    await page.goto('/components');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(3000);   // 与探测 p2 同步：列表加载后再定位（第 1 轮在第二个包处 20s 内找不到目录，原因未查明）
    const dir = page.locator('.cmm-dir').filter({ hasText: dirName }).first();
    if (!(await dir.isVisible().catch(() => false))) {
      console.log(`[AC-16⑤ ${LABEL}] ${ver} 首次未见目录，诊断：`, JSON.stringify(await page.locator('.cmm-dir .cmm-dir-name').allInnerTexts()), page.url());
      await page.screenshot({ path: path.join(OUT, `AC-16⑤-${LABEL}-${ver}-目录未见诊断.png`), fullPage: true });
      await page.reload(); await page.waitForLoadState('networkidle'); await page.waitForTimeout(3000);
    }
    await expect(dir, `应能看到目录 ${dirName}（T-C5 run 建）`).toBeVisible({ timeout: 20_000 });
    // 探测（探测记录 11:45Z）：目录行操作按钮 aria-label = edit/export/import/tool/delete；点 import 出「导入组件到目录:…」弹层，须选文件后点「预览」
    const entry = dir.locator('.cmm-dir-head').first().locator('.cmm-dir-acts button').filter({ has: page.locator('.anticon-import') }).first();
    await expect(entry, '目录行应有导入按钮').toBeVisible({ timeout: 10_000 });
    await entry.click();
    const panel = page.locator('.ant-modal, .ant-drawer').filter({ hasText: /导入组件到目录/ }).last();
    await expect(panel, '导入弹层可见').toBeVisible({ timeout: 20_000 });
    await expect(panel, '弹层标题指向前缀目录').toContainText(dirName);
    await panel.locator('input[type=file]').first().setInputFiles(PKGS[ver]);
    await page.waitForTimeout(800);
    const pv = page.waitForResponse((r) => r.url().includes('/import') && !r.url().includes('/commit') && r.request().method() === 'POST', { timeout: 30_000 });
    await panel.getByRole('button', { name: /预\s*览/ }).first().click();
    const pvRes = await pv;
    console.log(`[AC-16⑤ ${LABEL}] ${ver} 预览请求 status=${pvRes.status()}`);
    await page.waitForTimeout(2500);   // 等预览渲染
    const text = (await panel.innerText().catch(() => '')) || '';
    await panel.screenshot({ path: path.join(OUT, `AC-16⑤-${LABEL}-${ver}-导入预览.png`) }).catch(() => {});
    expect(text.length, `${ver} 预览弹层文字非空`).toBeGreaterThan(0);
    // 阳性保护：预览必须真的渲染出本包内容（ex1 组件名），否则「没有提示」可能只是没加载出来
    expect(text, `${ver} 预览里应出现包内组件 ex1（证明预览已渲染）`).toContain('ex1');
    const m = text.replace(/\n/g, ' ').match(HINT);
    result[ver] = { hint: /旧格式/.test(text), version: m ? m[1] : null, line: text.split('\n').filter((l) => /旧格式/.test(l)).join(' / ') };
    console.log(`[AC-16⑤ ${LABEL}] ${ver}`, JSON.stringify(result[ver]));
    await panel.getByRole('button', { name: /关\s*闭/ }).first().click({ timeout: 5000 }).catch(async (e) => { console.log('关闭按钮失败，改按 Escape', String(e).slice(0, 120)); await page.keyboard.press('Escape'); });
    await expect(page.locator('.ant-modal:visible'), '导入弹层已关闭').toHaveCount(0, { timeout: 10_000 });
  }
  fs.writeFileSync(path.join(o5, `ab-ui-${LABEL}.json`), JSON.stringify(result, null, 2));
  expect.soft(result['v1.0'].version, `AC-16⑤ v1.0 应出现提示且版本号为 1.0（实际: ${result['v1.0'].line || '无'}）`).toBe('1.0');
  expect.soft(result['v1.1'].hint, `AC-16⑤ v1.1 不应出现「旧格式」提示（实际: ${result['v1.1'].line}）`).toBe(false);
});
