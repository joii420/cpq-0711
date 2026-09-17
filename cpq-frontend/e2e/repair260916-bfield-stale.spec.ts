/**
 * repair-260916（改上游页签后公式按旧值算）· S-E 片 · E-0 ~ E-12
 * 用例只从 问题说明.md ⑥ 的 AC 原文派生（AC-1~AC-10、AC-12；AC-14 另跑 quotation-flow）。
 *
 * 写入面（test.md §4 登记）：
 *   - 每轮 1 张副本 Q′（POST /quotations/{0879}/copy），只编辑 / 存草稿 Q′，finally 里按记下的 id DELETE；
 *   - 0879 / 0874 / 0866 / 0859 只进详情页（只读）。
 * 轮次：R260916_ROUND=master（共享 5174，阳性对照）| fix（临时 5197）。
 * 断言一律 expect.soft，所有实际值写进 summary JSON；master 轮另记「修复前」对照是否吻合（不断言）。
 */
import { test, expect, Page, Locator } from '@playwright/test';
import { execSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import * as XLSX from 'xlsx';

const ROUND = (process.env.R260916_ROUND || 'master') as 'master' | 'fix';
const SRC_ID = 'a566efeb-72fe-48f9-b2c8-e1543283beab'; // QT-20260916-0879
const Q0874 = '6ed88659-a30f-451b-979e-62fddc073770';
const Q0866 = '4b19dcc7-8f42-4770-8811-470a8058e5be';
const Q0859 = '21b93071-8a93-4aa6-b767-4da4edd621b1';
const LI_0879 = '83722b91-b2ce-4eb6-8f5c-2a882ba59471';
const WULIAO_CID = 'b3445979-e3d6-4cdb-ab2d-53758b5f2eb2';

const TASK_DIR = path.resolve(process.cwd(), '..', 'dev-docs', 'repair-260803-公式SUM内引用宿主页签字段', 'repair-260916-改上游页签后公式按旧值算');
const EVID = path.join(TASK_DIR, '证据', 'e2e', ROUND);
const OUT = path.join(process.cwd(), 'test-results', 'repair260916', ROUND);
fs.mkdirSync(OUT, { recursive: true });
fs.mkdirSync(EVID, { recursive: true });

const FORMULA_COLS = ['来料回收费', '来料财务费', '材料成本', '材料损耗成本', '来料损耗率', '来料加工费', '回收成本', '铆钉额外费用'];
const WULIAO_PARTS = ['S3120011203', 'S3110520422', '00144', '00255', '00256', '00257'];

// ---------------------------------------------------------------- summary
type Check = { step: string; ac: string; name: string; expected: string; actual: string; pass: boolean; control?: string; controlMatch?: boolean };
const checks: Check[] = [];
const notes: string[] = [];
function note(s: string) { const l = `[${new Date().toISOString()}] ${s}`; notes.push(l); console.log(l); }
function flush() {
  const f = path.join(OUT, 'summary.json');
  let prev: any = { checks: [], notes: [] };
  try { prev = JSON.parse(fs.readFileSync(f, 'utf8')); } catch { /* first */ }
  fs.writeFileSync(f, JSON.stringify({ round: ROUND, checks: prev.checks.concat(checks.splice(0)), notes: prev.notes.concat(notes.splice(0)) }, null, 2));
}
/** 断言 actual 与 expected（9 位归一后）相等；control = master 轮的「修复前」描述值，仅记录是否吻合。 */
function check(step: string, ac: string, name: string, actual: string | undefined, expected: string, control?: string) {
  const a = actual ?? '<undefined>';
  const pass = norm9(a) === norm9(expected);
  const c: Check = { step, ac, name, expected, actual: a, pass };
  if (control !== undefined) { c.control = control; c.controlMatch = norm9(a) === norm9(control); }
  checks.push(c);
  console.log(`[check] ${step} ${ac} ${name}: expected=${expected} actual=${a} ${pass ? 'PASS' : 'DIFF'}${control !== undefined ? ` (修复前对照=${control} ${c.controlMatch ? '吻合' : '不吻合'})` : ''}`);
  expect.soft(a, `${step} ${ac} ${name}`).toBe(norm9(a) === norm9(expected) ? a : expected);
}
function checkTrue(step: string, ac: string, name: string, ok: boolean, actual: string, expected: string) {
  checks.push({ step, ac, name, expected, actual, pass: ok });
  console.log(`[check] ${step} ${ac} ${name}: expected=${expected} actual=${actual} ${ok ? 'PASS' : 'DIFF'}`);
  expect.soft(ok, `${step} ${ac} ${name}: expected=${expected} actual=${actual}`).toBe(true);
}

// ---------------------------------------------------------------- decimal helpers (string / BigInt, no JS float)
function norm9(s: string): string {
  const t = (s ?? '').trim();
  const m = /^(-?)(\d+)(?:\.(\d+))?$/.exec(t);
  if (!m) return t;
  const frac = (m[3] || '');
  if (frac.length > 9) return t; // 超过 9 位不归一，原样比较（会暴露口径问题）
  return `${m[1]}${BigInt(m[2]).toString()}.${frac.padEnd(9, '0')}`;
}
function round9(s: string): string {
  const m = /^(-?)(\d+)(?:\.(\d+))?$/.exec((s ?? '').trim());
  if (!m) return s;
  const frac = (m[3] || '').padEnd(10, '0');
  let v = BigInt(m[2] + frac.slice(0, 9));
  if (Number(frac[9]) >= 5) v += 1n;
  const str = v.toString().padStart(10, '0');
  return `${m[1]}${BigInt(str.slice(0, -9)).toString()}.${str.slice(-9)}`;
}
function sum9(vals: string[]): string {
  let t = 0n;
  // 任一格非数字（如 ⚠）返回标记串，由断言记为偏差，不抛错中断后续步骤
  for (const v of vals) {
    const n = norm9(v);
    const m = /^(-?)(\d+)\.(\d{9})$/.exec(n);
    if (!m) return `<不可求和:${v}>`;
    const x = BigInt(m[2] + m[3]);
    t += m[1] ? -x : x;
  }
  const neg = t < 0n; const a = (neg ? -t : t).toString().padStart(10, '0');
  return `${neg ? '-' : ''}${BigInt(a.slice(0, -9)).toString()}.${a.slice(-9)}`;
}

// ---------------------------------------------------------------- DB (read-only SELECT)
function sql(q: string): string {
  return execSync(`psql -h 10.177.152.12 -U postgres -d cpq_db_0724 -At -c ${JSON.stringify(q)}`, {
    env: { ...process.env, PGPASSWORD: 'joii5231' }, encoding: 'utf8', shell: '/bin/bash',
  }).trim();
}
function cardValues(lineItemId: string): any { return JSON.parse(sql(`select quote_card_values::text from quotation_line_item where id='${lineItemId}'`)); }
function tabOf(cv: any, name: string): any { return (cv.tabs || []).find((t: any) => t.tabName === name); }
/** formulaResults → { 料号: {列: 值} }（rowKey 形如 a/b/料号::...） */
function wuliaoResults(cv: any): Record<string, Record<string, string>> {
  const out: Record<string, Record<string, string>> = {};
  for (const r of tabOf(cv, '物料')?.formulaResults || []) {
    const pathPart = String(r.rowKey).split('::')[0];
    out[pathPart.split('/').pop()!] = r.values;
  }
  return out;
}
function dbInputs(cv: any) {
  const mc = tabOf(cv, '材料成本');
  const price: Record<string, string> = {};
  for (const r of mc?.editRows || []) price[r.values['元素']] = String(r.values['元素单价']);
  return {
    Ni: price['Ni'], Cu: price['Cu'], Zn: price['Zn'], Ag: price['Ag'],
    税率: String(tabOf(cv, '产品')?.resolvedRows?.[0]?.['税率']),
    加工费小计: String(tabOf(cv, '来料固定加工费')?.subtotalByColumn?.['加工费']),
  };
}
const T0_INPUTS = { Ni: '105', Cu: '101.1392', Zn: '24.1695', Ag: '28892.5', 税率: '1.13', 加工费小计: '127.5' };
/** 输入守恒：任一项变了返回 false（调用方中止本轮） */
function checkInputs(step: string, ac: string, cv: any): boolean {
  const inp = dbInputs(cv) as Record<string, string>;
  let ok = true;
  for (const [k, v] of Object.entries(T0_INPUTS)) {
    const same = norm9(inp[k]) === norm9(v);
    checkTrue(step, ac, `输入守恒 ${k}`, same, inp[k], v);
    ok = ok && same;
  }
  return ok;
}

// ---------------------------------------------------------------- page helpers
/**
 * 公式列在横向滚动区右侧，不滚过去截图只拍到输入列（master 轮第 4 次实测：E-0/E-1/E-2 截图 md5 全同 = 恒真证据）。
 * 截图前把所有横向可滚容器滚到最右；另存一张只含产品卡片的截图。
 */
async function shot(page: Page, name: string, fullPage = false) {
  await page.evaluate(() => {
    document.querySelectorAll('*').forEach((el) => {
      const e = el as HTMLElement;
      if (e.scrollWidth > e.clientWidth + 4 && getComputedStyle(e).overflowX !== 'visible') e.scrollLeft = e.scrollWidth;
    });
  }).catch(() => {});
  await page.waitForTimeout(300);
  const f = path.join(OUT, `${name}.png`);
  const c = page.locator('.qt-product-card').first();
  if (!fullPage && await c.count()) await c.screenshot({ path: f }).catch(async () => { await page.screenshot({ path: f, fullPage: true }).catch(() => {}); });
  else await page.screenshot({ path: f, fullPage: true }).catch(() => {});
  return f;
}
function saveJson(name: string, o: unknown) { fs.writeFileSync(path.join(OUT, name), JSON.stringify(o, null, 2)); }

function card(page: Page): Locator { return page.locator('.qt-product-card').first(); }

async function clickTab(scope: Locator, name: string, settleMs = 1200) {
  const btn = scope.locator('button.qt-tab-btn').filter({ hasText: new RegExp(`^\\s*${name}\\s*$`) }).first();
  await btn.click();
  await expect(btn).toHaveClass(/active/, { timeout: 15_000 });
  await scope.page().waitForTimeout(settleMs);
}

/** 详情页：等首张产品卡片可见 + 卡片数连续 3 次采样不变（0859 有 5 张卡片，6s 固定等待实测会读到 0 张）。 */
async function gotoDetail(page: Page, qid: string) {
  await page.goto(`/quotations/${qid}`);
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.qt-product-card').first()).toBeVisible({ timeout: 90_000 });
  let last = -1; let stable = 0;
  for (let i = 0; i < 60 && stable < 3; i++) {
    const n = await page.locator('.qt-product-card').count();
    stable = n === last ? stable + 1 : 0; last = n;
    await page.waitForTimeout(1000);
  }
  await page.waitForTimeout(2000);
  return last;
}

async function openEditStep2(page: Page, qid: string) {
  await page.goto(`/quotations/${qid}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);
  const next = page.locator('button', { hasText: '下一步' }).first();
  await expect(next).toBeEnabled({ timeout: 30_000 });
  await next.click();
  await expect(page.locator('button.qt-tab-btn').first()).toBeVisible({ timeout: 60_000 });
  await page.waitForTimeout(5000);
}

type Cell = { text: string; isErr: boolean; title: string; isFormula: boolean };
type Grid = { headers: string[]; rows: { label: string; cells: Record<string, Cell> }[]; errCount: number };

/** 读当前可见产品卡片表格：按表头名取格；公式格读 .qt-formula-cell-value / .qt-formula-cell-error(title)。 */
async function readGrid(tbl: Locator): Promise<Grid> {
  return await tbl.evaluate((t: HTMLTableElement) => {
    const headers = Array.from(t.querySelectorAll('thead th')).map((th) => (th as HTMLElement).innerText.trim());
    const rows = Array.from(t.querySelectorAll('tbody tr')).map((tr) => {
      const tds = Array.from(tr.querySelectorAll('td'));
      const cells: Record<string, any> = {};
      tds.forEach((td, i) => {
        const h = headers[i] ?? `#${i}`;
        const err = td.querySelector('.qt-formula-cell-error') as HTMLElement | null;
        const val = td.querySelector('.qt-formula-cell-value') as HTMLElement | null;
        const inp = td.querySelector('input') as HTMLInputElement | null;
        let text: string;
        if (err) text = (err.textContent || '').trim();
        else if (val) text = (val.textContent || '').trim();
        else if (inp) text = inp.value;
        else text = ((td as HTMLElement).innerText || '').replace(/[▼▶✂＋✕]/g, '').trim();
        const key = cells[h] ? `${h}#${i}` : h;
        cells[key] = { text, isErr: !!err, title: err?.getAttribute('title') || '', isFormula: td.classList.contains('qt-formula-cell') };
      });
      const first = tds[0] ? ((tds[0] as HTMLElement).innerText || '').replace(/[▼▶✂＋✕]/g, '').trim() : '';
      return { label: first, cells };
    });
    const errCount = t.querySelectorAll('.qt-formula-cell-error').length;
    return { headers, rows, errCount };
  });
}

async function wuliaoGrid(page: Page): Promise<Grid> {
  const tbl = card(page).locator('table.qt-cost-table').first();
  await expect(tbl.locator('thead')).toContainText('铆钉额外费用', { timeout: 20_000 });
  const g = await readGrid(tbl);
  if (g.rows.length === 0) throw new Error('物料页签 0 行（断言会空跑，中止）');
  return g;
}
function wuRow(g: Grid, part: string) {
  const hit = g.rows.filter((r) => r.label === part);
  if (hit.length !== 1) throw new Error(`物料页签料号 ${part} 命中 ${hit.length} 行；labels=${JSON.stringify(g.rows.map((r) => r.label))}`);
  return hit[0];
}
function wuVal(g: Grid, part: string, col: string): string { return wuRow(g, part).cells[col]?.text ?? '<no-col>'; }
function gridErrs(g: Grid) {
  const out: string[] = [];
  for (const r of g.rows) for (const [k, c] of Object.entries(r.cells)) if (c.isErr) out.push(`${r.label}.${k}: ${c.title}`);
  return out;
}
function wuliaoMatrix(g: Grid) {
  const m: Record<string, Record<string, string>> = {};
  for (const p of WULIAO_PARTS) { m[p] = {}; for (const c of FORMULA_COLS) m[p][c] = wuVal(g, p, c); }
  return m;
}

/** 在「来料其他费用」里把 (料号, 要素) 行的 field 改成 value，点空白处失焦；返回 quote-card-edit 响应 promise（不 await）。 */
async function editOtherFee(page: Page, part: string, element: string, field: '费用' | '比例', value: string) {
  const c = card(page);
  await clickTab(c, '来料其他费用');
  const tbl = c.locator('table.qt-cost-table').first();
  await expect(tbl.locator('thead')).toContainText('要素');
  const headers = (await tbl.locator('thead th').allInnerTexts()).map((s) => s.trim());
  const iPart = headers.indexOf('料号'); const iEl = headers.indexOf('要素'); const iF = headers.indexOf(field);
  if (iPart < 0 || iEl < 0 || iF < 0) throw new Error(`来料其他费用表头缺列: ${JSON.stringify(headers)}`);
  const rows = tbl.locator('tbody tr');
  const n = await rows.count();
  let target = -1;
  for (let i = 0; i < n; i++) {
    const tds = rows.nth(i).locator('td');
    const p = await tds.nth(iPart).locator('input').inputValue().catch(() => '');
    const e = await tds.nth(iEl).locator('input').inputValue().catch(() => '');
    if (p === part && e === element) { if (target >= 0) throw new Error(`(${part},${element}) 命中多行`); target = i; }
  }
  if (target < 0) throw new Error(`来料其他费用找不到 (${part},${element})`);
  const input = rows.nth(target).locator('td').nth(iF).locator('input');
  const before = await input.inputValue();
  note(`编辑 来料其他费用 (${part},${element}).${field}: ${before} -> ${value}`);
  // api.md 写 POST；master 轮实测为 PUT —— 不按方法过滤
  const resp = page.waitForResponse((r) => r.url().includes('/quote-card-edit'), { timeout: 60_000 });
  await input.click();
  await input.fill(value);
  // 点空白处失焦：点本表「销售料号」表头（纯文本 th）
  await tbl.locator('thead th').first().click();
  return { before, resp };
}

// ======================================================================================
// 不用 serial 模式：master 轮第 1 个测试按预期会红，serial 会连带跳过 E-10/E-11。workers:1 已保证顺序。

test('Q′ 序列：E-0 ~ E-9（AC-1~AC-8、AC-12）+ E-12 清理', async ({ page }) => {
  const reqLog: any[] = [];
  page.on('request', (r) => {
    const u = r.url();
    if (/quote-card-edit|reconcile-report|\/draft\b/.test(u)) reqLog.push({ t: new Date().toISOString(), m: r.method(), u, body: r.postData()?.slice(0, 200000) });
  });
  page.on('response', async (r) => {
    const u = r.url();
    if (/quote-card-edit|reconcile-report|\/draft\b/.test(u)) reqLog.push({ t: new Date().toISOString(), status: r.status(), u });
  });

  // ---- 建副本
  const cr = await page.request.post(`/api/cpq/quotations/${SRC_ID}/copy`, { data: {} });
  const cj = JSON.parse(await cr.text());
  const QID: string = cj?.data?.id; const QNO: string = cj?.data?.quotationNumber;
  fs.appendFileSync(path.join(EVID, 'created-quotations.txt'), `${new Date().toISOString()} round=${ROUND} copy status=${cr.status()} id=${QID} no=${QNO}\n`);
  note(`Q′ 建立 status=${cr.status()} id=${QID} no=${QNO}`);
  expect(QID, '复制接口应返回 id').toBeTruthy();
  try {
    const LI = sql(`select id from quotation_line_item where quotation_id='${QID}'`);
    note(`Q′ line_item=${LI}`);
    expect(LI.split('\n').length, 'Q′ 应恰好 1 个行项').toBe(1);

    // ================= E-0 T0 =================
    let cv = cardValues(LI);
    const inputsOk = checkInputs('E-0', 'T0', cv);
    await openEditStep2(page, QID);
    await clickTab(card(page), '物料');
    let g = await wuliaoGrid(page);
    await shot(page, 'E-0-T0-物料');
    saveJson('E-0-物料.json', wuliaoMatrix(g));
    const t0: [string, string, string][] = [
      ['00257', '来料加工费', '170.404'], ['00257', '材料成本', '0.002418226'],
      ['00256', '来料损耗率', '5'], ['00256', '材料成本', '0.015491845'],
      ['S3110520422', '材料成本', '0.059189199'], ['00255', '材料成本', '1.437983994'], ['00144', '材料成本', '0.463735546'],
    ];
    let t0ok = inputsOk;
    for (const [p, c, v] of t0) { const a = wuVal(g, p, c); check('E-0', 'T0', `${p}.${c}`, a, v); t0ok = t0ok && norm9(a) === norm9(v); }
    check('E-0', 'T0', '⚠ 个数', String(g.errCount), '0'); t0ok = t0ok && g.errCount === 0;
    if (!t0ok) { note('E-0 T0 前置断言不成立 ⇒ 中止本轮（按约定报主线，不改期望值）'); flush(); throw new Error('T0 前置断言不成立'); }

    // ================= E-1 AC-1 =================
    const e1 = await editOtherFee(page, '00257', '来料加工费', '费用', '200');
    let respDone = false; let respAt = ''; e1.resp.then(() => { respDone = true; respAt = new Date().toISOString(); }, () => {});
    // 「不等响应立即点物料」：切页签后只留 200ms 渲染时间就读（编辑请求往返实测约 1s）
    await clickTab(card(page), '物料', 200);
    g = await wuliaoGrid(page);
    note(`E-1 读值时刻=${new Date().toISOString()} quote-card-edit 响应是否已返回=${respDone}`);
    if (respDone) note('E-1 ⚠ 读值时响应已返回 —— 本次观测不是「响应前」状态，见 respAt');
    await shot(page, 'E-1-AC-1-改200后立即切物料');
    saveJson('E-1-物料.json', wuliaoMatrix(g));
    check('E-1', 'AC-1', '00257.来料加工费', wuVal(g, '00257', '来料加工费'), '200');
    check('E-1', 'AC-1', '00257.材料成本', wuVal(g, '00257', '材料成本'), '0.002678099', '0.002418226');
    check('E-1', 'AC-1', 'S3110520422.材料成本', wuVal(g, 'S3110520422', '材料成本'), '0.059194397', '0.059189199');
    check('E-1', 'AC-1', '00255.材料成本', wuVal(g, '00255', '材料成本'), '1.437983994');
    check('E-1', 'AC-1', '00144.材料成本', wuVal(g, '00144', '材料成本'), '0.463735546');
    check('E-1', 'AC-1', '00256.材料成本', wuVal(g, '00256', '材料成本'), '0.015491845');

    // ================= E-2 AC-2 =================
    const r1 = await e1.resp;
    note(`E-2 quote-card-edit ${r1.request().method()} status=${r1.status()} 响应到达=${respAt} 请求体=${r1.request().postData()}`);
    {
      const g0 = await wuliaoGrid(page);
      note(`E-2 响应刚到时（未等 3s）物料：00257.材料成本=${wuVal(g0, '00257', '材料成本')} S3110520422.材料成本=${wuVal(g0, 'S3110520422', '材料成本')} ⚠=${g0.errCount}`);
      await shot(page, 'E-2-AC-2-响应刚到');
    }
    expect.soft(r1.status(), 'quote-card-edit 应 200').toBe(200);
    await page.waitForTimeout(3000);
    g = await wuliaoGrid(page);
    note(`E-2 响应返回+3s 仍停在物料：⚠=${g.errCount} ${JSON.stringify(gridErrs(g))}`);
    await shot(page, 'E-2-AC-2-响应后停留物料');
    await clickTab(card(page), '来料其他费用');
    await clickTab(card(page), '物料');
    g = await wuliaoGrid(page);
    await shot(page, 'E-2-AC-2-切走再切回');
    saveJson('E-2-物料.json', { matrix: wuliaoMatrix(g), errs: gridErrs(g) });
    const errs2 = gridErrs(g);
    check('E-2', 'AC-2', '⚠ 个数', String(g.errCount), '0', '2');
    checks[checks.length - 1].actual += ` ${JSON.stringify(errs2)}`;
    let allNum = true; const nonNum: string[] = [];
    for (const p of WULIAO_PARTS) for (const c of FORMULA_COLS) { const v = wuVal(g, p, c); if (!/^-?\d+(\.\d+)?$/.test(v)) { allNum = false; nonNum.push(`${p}.${c}=${v}`); } }
    checkTrue('E-2', 'AC-2', '6 行 × 8 公式列全部为数字', allNum, JSON.stringify(nonNum), '[]');
    const errTitles = errs2.map((e) => e.split(': ').slice(1).join(': '));
    if (errs2.length) note(`E-2 ⚠ 悬停提示（title）: ${JSON.stringify(errTitles)}；以「前后端算值不一致」开头=${errTitles.map((t) => t.startsWith('前后端算值不一致'))}`);
    if (errs2.length) {
      const errEl = card(page).locator('.qt-formula-cell-error').first();
      await errEl.hover().catch(() => {});
      await shot(page, 'E-2-AC-2-⚠悬停');
    }
    check('E-2', 'AC-2', '00257.来料加工费', wuVal(g, '00257', '来料加工费'), '200');
    check('E-2', 'AC-2', '00257.材料成本', wuVal(g, '00257', '材料成本'), '0.002678099', '⚠');
    check('E-2', 'AC-2', 'S3110520422.材料成本', wuVal(g, 'S3110520422', '材料成本'), '0.059194397', '⚠');

    // ================= E-3 AC-3 =================
    cv = cardValues(LI);
    const fr3 = wuliaoResults(cv);
    saveJson('E-3-AC-3-db-formulaResults.json', fr3);
    note(`E-3 DB 物料 formulaResults 00257=${JSON.stringify(fr3['00257'])} S3110520422=${JSON.stringify(fr3['S3110520422'])}`);
    check('E-3', 'AC-3', 'DB 00257.材料成本', fr3['00257']?.['材料成本'], '0.002678099');
    check('E-3', 'AC-3', 'DB S3110520422.材料成本', fr3['S3110520422']?.['材料成本'], '0.059194397');
    check('E-3', 'AC-3', '页面=DB 00257.材料成本', wuVal(g, '00257', '材料成本'), fr3['00257']?.['材料成本'] ?? '<db-missing>');
    check('E-3', 'AC-3', '页面=DB S3110520422.材料成本', wuVal(g, 'S3110520422', '材料成本'), fr3['S3110520422']?.['材料成本'] ?? '<db-missing>');
    if (!checkInputs('E-3', 'AC-3', cv)) { note('E-3 输入守恒被破坏 ⇒ 中止'); flush(); throw new Error('输入守恒被破坏'); }

    // ================= E-4 step1 AC-4 =================
    const e4 = await editOtherFee(page, '00256', '来料损耗率', '比例', '10');
    let r4done = false; e4.resp.then(() => { r4done = true; }, () => {});
    await clickTab(card(page), '物料', 200);
    g = await wuliaoGrid(page);
    note(`E-4.1 读值时 quote-card-edit 响应是否已返回=${r4done}`);
    await shot(page, 'E-4-1-AC-4-改比例10后切物料');
    saveJson('E-4-1-物料.json', wuliaoMatrix(g));
    check('E-4.1', 'AC-4', '00256.来料损耗率', wuVal(g, '00256', '来料损耗率'), '10');
    check('E-4.1', 'AC-4', '00256.材料成本', wuVal(g, '00256', '材料成本'), '0.016191348', '0.015491845');
    check('E-4.1', 'AC-4', 'S3110520422.材料成本', wuVal(g, 'S3110520422', '材料成本'), '0.059208387');
    check('E-4.1', 'AC-4', '00257.材料成本', wuVal(g, '00257', '材料成本'), '0.002678099');
    const r4 = await e4.resp;
    { const gx = await wuliaoGrid(page); note(`E-4.1 响应刚到时 00256.材料成本=${wuVal(gx, '00256', '材料成本')} ⚠=${gx.errCount} ${JSON.stringify(gridErrs(gx))}`); }
    note(`E-4.1 quote-card-edit status=${r4.status()} 请求体=${r4.request().postData()}`);
    await page.waitForTimeout(3000);
    g = await wuliaoGrid(page);
    check('E-4.1', 'AC-4', '⚠ 个数（响应后+3s）', String(g.errCount), '0');
    checks[checks.length - 1].actual += ` ${JSON.stringify(gridErrs(g))}`;

    // ================= E-5 AC-12（E-4 第 1 小步之后、刷新之前）=================
    const draftP = page.waitForResponse((r) => /\/api\/cpq\/quotations\/[^/]+\/draft$/.test(new URL(r.url()).pathname) && r.request().method() === 'PUT', { timeout: 90_000 });
    await page.locator('button', { hasText: '保存草稿' }).first().click();
    const dr = await draftP;
    const body = dr.request().postData() || '';
    fs.writeFileSync(path.join(OUT, 'E-5-AC-12-draft-request-body.json'), body);
    note(`E-5 PUT draft status=${dr.status()} body.len=${body.length}`);
    expect.soft(dr.status(), '保存草稿应 200').toBe(200);
    await page.waitForTimeout(2000);
    await shot(page, 'E-5-AC-12-保存草稿后');
    {
      const bj = JSON.parse(body);
      // api.md 写 lineItems[]；master 轮实测请求体为 added[] / modified[] —— 三者都收
      const lis = [...(bj.lineItems || []), ...(bj.added || []), ...(bj.modified || [])];
      note(`E-5 请求体行项: lineItems=${(bj.lineItems || []).length} added=${(bj.added || []).length} modified=${(bj.modified || []).length}; quoteExcelValues=${lis.map((l: any) => l.quoteExcelValues).join(' | ')}`);
      const cds = lis.flatMap((li: any) => li.componentData || []);
      const wc = cds.filter((c: any) => c.componentId === WULIAO_CID || c.tabName === '物料');
      checkTrue('E-5', 'AC-12', '请求体含物料页签 componentData', wc.length === 1, String(wc.length), '1');
      const rows: any[] = wc.length ? (typeof wc[0].rowData === 'string' ? JSON.parse(wc[0].rowData) : wc[0].rowData) : [];
      const byPart = (p: string) => rows.filter((r) => r['料号'] === p);
      const r256 = byPart('00256'); const r257 = byPart('00257');
      saveJson('E-5-AC-12-物料rowData-00256-00257.json', { '00256': r256, '00257': r257 });
      checkTrue('E-5', 'AC-12', '00256 行恰好 1 条', r256.length === 1, String(r256.length), '1');
      checkTrue('E-5', 'AC-12', '00257 行恰好 1 条', r257.length === 1, String(r257.length), '1');
      const a256 = r256[0] || {}; const a257 = r257[0] || {};
      checkTrue('E-5', 'AC-12', '00256 行含「材料成本」键', '材料成本' in a256, JSON.stringify(Object.keys(a256)), '含材料成本');
      checkTrue('E-5', 'AC-12', '00256 行含「来料损耗率」键', '来料损耗率' in a256, JSON.stringify(Object.keys(a256)), '含来料损耗率');
      check('E-5', 'AC-12', '00256.材料成本(9位舍入)', round9(String(a256['材料成本'])), '0.016191348', '0.015491845');
      checks[checks.length - 1].actual += ` (原文 ${a256['材料成本']})`;
      check('E-5', 'AC-12', '00256.来料损耗率', String(a256['来料损耗率']), '10');
      check('E-5', 'AC-12', '00257.来料加工费', String(a257['来料加工费']), '200');
      check('E-5', 'AC-12', '00257.材料成本(9位舍入)', round9(String(a257['材料成本'])), '0.002678099');
      checks[checks.length - 1].actual += ` (原文 ${a257['材料成本']})`;
    }

    // ================= E-4 step2 / step3 =================
    await clickTab(card(page), '产品');
    await clickTab(card(page), '物料');
    g = await wuliaoGrid(page);
    await shot(page, 'E-4-2-AC-4-切产品再回');
    saveJson('E-4-2-物料.json', wuliaoMatrix(g));
    check('E-4.2', 'AC-4', '00256.来料损耗率', wuVal(g, '00256', '来料损耗率'), '10');
    check('E-4.2', 'AC-4', '00256.材料成本', wuVal(g, '00256', '材料成本'), '0.016191348');
    check('E-4.2', 'AC-4', 'S3110520422.材料成本', wuVal(g, 'S3110520422', '材料成本'), '0.059208387');
    check('E-4.2', 'AC-4', '00257.材料成本', wuVal(g, '00257', '材料成本'), '0.002678099');
    check('E-4.2', 'AC-4', '⚠ 个数', String(g.errCount), '0');
    checks[checks.length - 1].actual += ` ${JSON.stringify(gridErrs(g))}`;

    await page.reload();
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(3000);
    const next = page.locator('button', { hasText: '下一步' }).first();
    await expect(next).toBeEnabled({ timeout: 30_000 });
    await next.click();
    await expect(page.locator('button.qt-tab-btn').first()).toBeVisible({ timeout: 60_000 });
    await page.waitForTimeout(5000);
    await clickTab(card(page), '物料');
    g = await wuliaoGrid(page);
    await shot(page, 'E-4-3-AC-4-刷新后');
    saveJson('E-4-3-物料.json', wuliaoMatrix(g));
    check('E-4.3', 'AC-4', '00256.来料损耗率', wuVal(g, '00256', '来料损耗率'), '10');
    check('E-4.3', 'AC-4', '00256.材料成本', wuVal(g, '00256', '材料成本'), '0.016191348');
    check('E-4.3', 'AC-4', 'S3110520422.材料成本', wuVal(g, 'S3110520422', '材料成本'), '0.059208387');
    check('E-4.3', 'AC-4', '00257.材料成本', wuVal(g, '00257', '材料成本'), '0.002678099');
    check('E-4.3', 'AC-4', '⚠ 个数', String(g.errCount), '0');
    checks[checks.length - 1].actual += ` ${JSON.stringify(gridErrs(g))}`;

    // ================= E-6 AC-5 =================
    const e6 = await editOtherFee(page, '00257', '来料加工费', '费用', '0');
    let r6done = false; e6.resp.then(() => { r6done = true; }, () => {});
    await clickTab(card(page), '物料', 200);
    g = await wuliaoGrid(page);
    note(`E-6 读值时 quote-card-edit 响应是否已返回=${r6done}`);
    await shot(page, 'E-6-AC-5-改0后切物料');
    check('E-6', 'AC-5', '00257.来料加工费', wuVal(g, '00257', '来料加工费'), '0');
    check('E-6', 'AC-5', '00257.材料成本', wuVal(g, '00257', '材料成本'), '0.000921968', '0.002678099');
    check('E-6', 'AC-5', 'S3110520422.材料成本', wuVal(g, 'S3110520422', '材料成本'), '0.059173264');
    check('E-6', 'AC-5', '00256.材料成本', wuVal(g, '00256', '材料成本'), '0.016191348');
    const r6 = await e6.resp;
    note(`E-6 quote-card-edit status=${r6.status()} 请求体=${r6.request().postData()}`);
    await page.waitForTimeout(3000);
    await clickTab(card(page), '来料其他费用');
    await clickTab(card(page), '物料');
    g = await wuliaoGrid(page);
    await shot(page, 'E-6-AC-5-响应后切走再回');
    saveJson('E-6-物料.json', { matrix: wuliaoMatrix(g), errs: gridErrs(g) });
    check('E-6', 'AC-5', '⚠ 个数（响应后切走再回）', String(g.errCount), '0');
    checks[checks.length - 1].actual += ` ${JSON.stringify(gridErrs(g))}`;
    const wuliaoCostE6 = WULIAO_PARTS.map((p) => wuVal(g, p, '材料成本'));
    note(`E-6 物料 材料成本列 6 格显示值=${JSON.stringify(wuliaoCostE6)}`);

    // ================= E-7 AC-6 =================
    const draft2 = page.waitForResponse((r) => /\/api\/cpq\/quotations\/[^/]+\/draft$/.test(new URL(r.url()).pathname) && r.request().method() === 'PUT', { timeout: 90_000 });
    await page.locator('button', { hasText: '保存草稿' }).first().click();
    const d2 = await draft2;
    fs.writeFileSync(path.join(OUT, 'E-7-AC-6-draft-request-body.json'), d2.request().postData() || '');
    note(`E-7 PUT draft status=${d2.status()}`);
    expect.soft(d2.status(), '保存草稿应 200').toBe(200);
    await page.waitForTimeout(4000);
    await shot(page, 'E-7-AC-6-保存草稿后');
    const row7 = sql(`select quote_excel_values::text || '|' || subtotal::text || '|' || coalesce(quote_values_at::text,'') from quotation_line_item where id='${LI}'`);
    note(`E-7 DB quote_excel_values|subtotal|quote_values_at = ${row7}`);
    const [qevText, subtotalText] = row7.split('|');
    const qev = JSON.parse(qevText);
    cv = cardValues(LI);
    const sbc = tabOf(cv, '物料')?.subtotalByColumn?.['材料成本'];
    const sqlOut = { quote_excel_values: qev, subtotal: subtotalText, 物料_subtotalByColumn_材料成本: sbc, formulaResults: wuliaoResults(cv), inputs: dbInputs(cv) };
    saveJson('E-7-AC-6-db.json', sqlOut);
    // AC-6「9 位完全相等」+ ⑥「数值断言一律按 9 位小数比较」：库里 col_1/col_3 可能多于 9 位，先舍入到 9 位再比；原文附在 actual 里
    const col1Raw = String(qev?.rows?.[0]?.col_1); const col3Raw = String(qev?.rows?.[0]?.col_3);
    const col1 = round9(col1Raw); const col3 = round9(col3Raw);
    const sbc9 = round9(String(sbc));
    const tag = (s: string) => { checks[checks.length - 1].actual += ` (库原文 col_1=${col1Raw} col_3=${col3Raw})`; return s; };
    check('E-7', 'AC-6', 'quote_excel_values.col_1', col1, '1.978006120'); tag('');
    checks[checks.length - 1].control = '≠ 后端材料成本小计'; checks[checks.length - 1].controlMatch = col1 !== sbc9;
    check('E-7', 'AC-6', 'col_1 = 页面材料成本列 6 格之和', col1, sum9(wuliaoCostE6)); tag('');
    check('E-7', 'AC-6', 'col_1 = subtotalByColumn.材料成本', col1, sbc9); tag('');
    check('E-7', 'AC-6', 'col_3 = quotation_line_item.subtotal', col3, round9(subtotalText)); tag('');
    checks[checks.length - 1].control = '≠ subtotal'; checks[checks.length - 1].controlMatch = col3 !== round9(subtotalText);
    checkInputs('E-7', 'AC-6', cv);
    const exp = await page.request.get(`/api/cpq/quotations/${QID}/export-excel-view`);
    const buf = await exp.body();
    const xf = path.join(OUT, `E-7-AC-6-export-${QNO}.xlsx`);
    fs.writeFileSync(xf, buf);
    note(`E-7 export status=${exp.status()} content-type=${exp.headers()['content-type']} bytes=${buf.length} -> ${xf}`);
    const wb = XLSX.read(buf, { type: 'buffer' });
    const aoa: any[][] = XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]], { header: 1, raw: false });
    note(`E-7 导出内容 sheet=${wb.SheetNames[0]} rows=${JSON.stringify(aoa)}`);
    saveJson('E-7-AC-6-export-parsed.json', aoa);
    // 导出表头：api.md 写中文列标题；实测为 col_1/col_2/col_3 —— 两种都接受，按证据 §1.5 映射 col_1=材料成本、col_3=产品单价
    const hdr = (aoa[0] || []).map((x) => String(x));
    const iC1 = hdr.findIndex((h) => h === 'col_1' || h.includes('材料成本'));
    const iC3 = hdr.findIndex((h) => h === 'col_3' || h.includes('产品单价'));
    checkTrue('E-7', 'AC-6', '导出含材料成本/产品单价列且有数据行', iC1 >= 0 && iC3 >= 0 && aoa.length >= 2, `hdr=${JSON.stringify(hdr)} rows=${aoa.length}`, 'col_1/col_3 + ≥1 数据行');
    checkTrue('E-7', 'AC-6', '导出只有 1 个数据行（Q′ 单产品）', aoa.length === 2, String(aoa.length - 1), '1');
    check('E-7', 'AC-6', '导出 材料成本 = col_1(9位)', round9(String(aoa[1]?.[iC1])), col1);
    check('E-7', 'AC-6', '导出 产品单价 = col_3(9位)', round9(String(aoa[1]?.[iC3])), col3);
    check('E-7', 'AC-6', '导出 材料成本 = 1.978006120', round9(String(aoa[1]?.[iC1])), '1.978006120');
    check('E-7', 'AC-6', '导出 产品单价 = subtotal', round9(String(aoa[1]?.[iC3])), round9(subtotalText));

    // ================= E-8 AC-7 =================
    await gotoDetail(page, QID);
    await clickTab(card(page), '物料');
    g = await wuliaoGrid(page);
    await shot(page, 'E-8-AC-7-详情页物料');
    saveJson('E-8-详情物料.json', { matrix: wuliaoMatrix(g), errs: gridErrs(g) });
    check('E-8', 'AC-7', '00257.材料成本', wuVal(g, '00257', '材料成本'), '0.000921968');
    check('E-8', 'AC-7', '00256.材料成本', wuVal(g, '00256', '材料成本'), '0.016191348');
    check('E-8', 'AC-7', 'S3110520422.材料成本', wuVal(g, 'S3110520422', '材料成本'), '0.059173264');
    check('E-8', 'AC-7', '⚠ 个数', String(g.errCount), '0');
    checks[checks.length - 1].actual += ` ${JSON.stringify(gridErrs(g))}`;

    // ================= E-9 AC-8 =================
    await openEditStep2(page, QID);
    const seg = page.locator('.ant-segmented-item').filter({ hasText: '核价单' }).first();
    await seg.click();
    await expect(seg).toHaveClass(/ant-segmented-item-selected/, { timeout: 30_000 });
    await page.waitForTimeout(8000);
    const cc = card(page);
    const tabNames = (await cc.locator('button.qt-tab-btn').allInnerTexts()).map((s) => s.trim());
    const costing: Record<string, any> = {};
    let fcount = 0;
    for (const tn of tabNames) {
      await clickTab(cc, tn);
      await page.waitForTimeout(1500);
      const tg = await readGrid(cc.locator('table.qt-cost-table').first());
      const rows = tg.rows.map((r) => ({ label: r.label, f: Object.fromEntries(Object.entries(r.cells).filter(([, c]) => c.isFormula).map(([k, c]) => [k, c.isErr ? `⚠${c.title}` : c.text])) }));
      rows.forEach((r) => { fcount += Object.keys(r.f).length; });
      costing[tn] = { headers: tg.headers, rows };
      await shot(page, `E-9-AC-8-核价单-${tn}`);
    }
    saveJson('E-9-AC-8-核价单公式值.json', costing);
    const nonZeroF = Object.values(costing).reduce((n: number, t: any) => n + t.rows.reduce((m: number, r: any) => m + Object.values(r.f).filter((v: any) => /^-?\d+(\.\d+)?$/.test(v) && Number(v) !== 0).length, 0), 0);
    note(`E-9 核价单页签=${JSON.stringify(tabNames)} 公式格总数=${fcount} 其中非0数字格=${nonZeroF}（样本鉴别力参考，AC-8 本身只要求非空）`);
    const hasVal = Object.values(costing).some((t: any) => t.rows.some((r: any) => Object.keys(r.f).length > 0));
    checkTrue('E-9', 'AC-8', '非空（至少 1 个页签有 ≥1 个公式值）', hasVal, `公式格总数=${fcount}`, '≥1');
    if (ROUND === 'fix') {
      const base = path.join(TASK_DIR, '证据', 'e2e', 'master', 'E-9-AC-8-核价单公式值.json');
      if (fs.existsSync(base)) {
        const b = fs.readFileSync(base, 'utf8');
        const same = b === JSON.stringify(costing, null, 2);
        checkTrue('E-9', 'AC-8', '与 master 轮基线逐格相同', same, same ? '相同' : '不同（见两份 JSON）', '相同');
      } else checkTrue('E-9', 'AC-8', 'master 基线存在', false, `缺 ${base}`, '存在');
    }
  } finally {
    fs.writeFileSync(path.join(OUT, 'request-log.json'), JSON.stringify(reqLog, null, 2));
    // ================= E-12 清理 =================
    if (QID) {
      const d = await page.request.delete(`/api/cpq/quotations/${QID}`).catch((e) => ({ status: () => `ERR ${e}` } as any));
      const st = d.status();
      note(`E-12 DELETE Q′ ${QID} (${QNO}) -> ${st}`);
      fs.appendFileSync(path.join(EVID, 'created-quotations.txt'), `${new Date().toISOString()} round=${ROUND} delete id=${QID} no=${QNO} -> ${st}\n`);
      const left = sql(`select count(*) from quotation where id='${QID}'`);
      note(`E-12 删除后 quotation 表该 id 行数=${left}`);
    }
    flush();
  }
});

test('E-10 AC-9：0874 详情页「产品」管理费仍显示 ⚠（细项引用命中多行）', async ({ page }) => {
  try {
    await gotoDetail(page, Q0874);
    await clickTab(card(page), '产品');
    const tbl = card(page).locator('table.qt-cost-table').first();
    await expect(tbl.locator('thead')).toContainText('管理费');
    const g = await readGrid(tbl);
    if (g.rows.length === 0) throw new Error('0874 产品页签 0 行');
    const cell = g.rows[0].cells['管理费'];
    note(`E-10 0874 产品.管理费 = ${JSON.stringify(cell)}；页签 ⚠ 总数=${g.errCount}`);
    saveJson('E-10-AC-9-0874-产品.json', g);
    checkTrue('E-10', 'AC-9', '管理费显示 ⚠', !!cell?.isErr && cell.text === '⚠', JSON.stringify(cell), '⚠');
    checkTrue('E-10', 'AC-9', '悬停提示含「细项引用命中多行」', (cell?.title || '').includes('细项引用命中多行'), cell?.title || '', '含「细项引用命中多行」');
    const err = tbl.locator('.qt-formula-cell-error').first();
    await err.hover().catch(() => {});
    await page.waitForTimeout(800);
    await shot(page, 'E-10-AC-9-0874-产品-管理费⚠');
  } finally { flush(); }
});

test('E-11 AC-10：未编辑单据详情页公式值（0879 物料 / 0866 BOM / 0859 材质元素）', async ({ page }) => {
  try {
    // ---- 0879 物料 vs DB formulaResults
    await gotoDetail(page, SRC_ID);
    await clickTab(card(page), '物料');
    const g = await wuliaoGrid(page);
    await shot(page, 'E-11-AC-10-0879-物料');
    const pageM = wuliaoMatrix(g);
    const dbM = wuliaoResults(cardValues(LI_0879));
    saveJson('E-11-AC-10-0879-物料.json', { page: pageM, db: dbM, errs: gridErrs(g) });
    const diffs: string[] = [];
    let n = 0;
    for (const p of WULIAO_PARTS) for (const c of FORMULA_COLS) {
      n++;
      if (norm9(pageM[p][c]) !== norm9(String(dbM[p]?.[c]))) diffs.push(`${p}.${c}: 页面=${pageM[p][c]} 库=${dbM[p]?.[c]}`);
    }
    checkTrue('E-11', 'AC-10', `0879 物料 6×8=${n} 格 = 库 formulaResults`, n === 48 && diffs.length === 0, JSON.stringify(diffs), '0 处差异');
    check('E-11', 'AC-10', '0879 物料 ⚠ 个数', String(g.errCount), '0');

    // ---- 0866 BOM / 0859 材质元素：每张卡片抓全部公式格
    const multi: Record<string, any> = {};
    for (const [qid, qno, tab] of [[Q0866, 'QT-20260914-0866', 'BOM'], [Q0859, 'QT-20260913-0859', '材质元素']] as const) {
      await gotoDetail(page, qid);
      const cards = page.locator('.qt-product-card');
      const nc = await cards.count();
      const perCard: any[] = [];
      let nonZero = 0;
      for (let i = 0; i < nc; i++) {
        const c = cards.nth(i);
        await clickTab(c, tab);
        const tg = await readGrid(c.locator('table.qt-cost-table').first());
        const head = ((await c.locator('.qt-card-header').first().innerText().catch(() => '')) || '').replace(/\s+/g, ' ').trim();
        const rows = tg.rows.map((r) => ({ label: r.label, f: Object.fromEntries(Object.entries(r.cells).filter(([, x]) => x.isFormula).map(([k, x]) => [k, x.isErr ? `⚠${x.title}` : x.text])) }));
        rows.forEach((r) => Object.values(r.f).forEach((v: any) => { if (/^-?\d+(\.\d+)?$/.test(v) && Number(v) !== 0) nonZero++; }));
        perCard.push({ card: i, head, headers: tg.headers, rows });
      }
      await shot(page, `E-11-AC-10-${qno}-${tab}`, true);
      multi[qno] = perCard;
      const fcells = perCard.reduce((s, pc) => s + pc.rows.reduce((t: number, r: any) => t + Object.keys(r.f).length, 0), 0);
      note(`E-11 ${qno} 卡片=${nc} 「${tab}」公式格=${fcells} 非0数字格=${nonZero}`);
      checkTrue('E-11', 'AC-10', `${qno} 至少 1 个非 0 公式格`, nonZero >= 1, String(nonZero), '≥1');
    }
    fs.writeFileSync(path.join(OUT, 'E-11-AC-10-0866-0859.json'), JSON.stringify(multi, null, 2));
    if (ROUND === 'fix') {
      const base = path.join(TASK_DIR, '证据', 'e2e', 'master', 'E-11-AC-10-0866-0859.json');
      if (fs.existsSync(base)) {
        const same = fs.readFileSync(base, 'utf8') === JSON.stringify(multi, null, 2);
        checkTrue('E-11', 'AC-10', '0866/0859 与 master 轮基线逐格相同', same, same ? '相同' : '不同（见两份 JSON）', '相同');
      } else checkTrue('E-11', 'AC-10', 'master 基线存在', false, `缺 ${base}`, '存在');
    }
  } finally {
    flush();
  }
});

test.afterAll(async () => {
  // 证据归档：test-results 下一轮会被清空，全部复制进任务目录
  for (const f of fs.readdirSync(OUT)) fs.copyFileSync(path.join(OUT, f), path.join(EVID, f));
  console.log(`[archive] ${OUT} -> ${EVID}`);
});
