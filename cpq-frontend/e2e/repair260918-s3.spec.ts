/**
 * repair-260918（BL-0304）· 测试片 S3（只读界面片）· AC-9「页面 Excel 视图显示不变（序列 · 界面）」
 *
 * AC-9 原文（问题说明.md ⑥）：
 *   前置：临时前端（worktree，临时端口，/api 代理到本分支临时后端）；admin 登录；
 *         先 SELECT quote_excel_values, quote_card_values 记下 0881 行的两份值。
 *   操作：打开 QT-20260916-0881 编辑页 → 切到「Excel 视图」→ 记下三列 → 切回卡片视图 →
 *         再切「Excel 视图」→ 刷新浏览器 → 再切「Excel 视图」。
 *   断言：三次看到的列标题均为「材料成本 / 回收价格 / 产品单价」，数值均为
 *         1.978941064 / 0.317766357 / 1.804589425（与立项时页面存值一致；若运行时判定值已变，
 *         以运行时 quote_excel_values 为准）；操作前后再查一次两份值，逐字未变。每一步截图归档进 证据/。
 *
 * 只读纪律：不编辑任何值、不点保存/提交/刷新基础数据等写入类按钮；DB 只执行 SELECT。
 *
 * 环境变量：
 *   S3_EVID_DIR      证据输出目录（必须是尚不存在的新目录；默认 证据/测试/S3/<yymmdd-HHMM>/）
 *   S3_STACK_LABEL   本轮栈标签，仅写进输出（如 "master-8081" / "branch-8338"）
 *   S3_FALSIFY=1     证伪实验：把期望值第 3 列篡改为错误值，断言必须硬失败
 */
import { test, expect, Page, Response } from '@playwright/test';
import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const QUOTATION_ID = '40fe7ae7-c6e1-403a-aa30-17989ec758e9'; // QT-20260916-0881
const LINE_ID = '44954ae6-f5a1-4f0f-9847-d2e6ad23b800';
const TITLES = ['材料成本', '回收价格', '产品单价'];
const FOUNDING_VALUES = ['1.978941064', '0.317766357', '1.804589425']; // 立项时页面存值
const STACK = process.env.S3_STACK_LABEL || 'unlabeled';
const FALSIFY = process.env.S3_FALSIFY === '1';

const TASK_DIR = path.resolve(
  __dirname,
  '../../dev-docs/task-260801-页签连表公式配置优化/repair-260918-报价Excel后端重算与卡片值不一致',
);

function stamp(): string {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${String(d.getFullYear()).slice(2)}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}`;
}

const EVID = process.env.S3_EVID_DIR || path.join(TASK_DIR, '证据/测试/S3', stamp());

function psql(sql: string): string {
  if (!/^\s*select\b/i.test(sql)) throw new Error('S3 只读片只允许 SELECT: ' + sql);
  return execFileSync(
    'psql',
    ['-h', '10.177.152.12', '-U', 'postgres', '-d', 'cpq_db_0724', '-At', '-v', 'ON_ERROR_STOP=1', '-c', sql],
    { env: { ...process.env, PGPASSWORD: 'joii5231' }, encoding: 'utf8' },
  ).replace(/\n$/, '');
}

type DbSnap = { excel: string; card: string; meta: string };
function snapDb(): DbSnap {
  const excel = psql(`select coalesce(quote_excel_values::text,'<NULL>') from quotation_line_item where id='${LINE_ID}'`);
  const card = psql(`select coalesce(quote_card_values::text,'<NULL>') from quotation_line_item where id='${LINE_ID}'`);
  // 仅供参考（不是 AC 断言）：单头 updated_at / user_data_version（quotation_line_item 无 updated_at 列），用来发现“打开页面”是否触发了别的写入
  const meta = psql(
    `select 'quotation.updated_at='||q.updated_at||' user_data_version='||coalesce(q.user_data_version::text,'null')` +
      ` from quotation q where q.id='${QUOTATION_ID}'`,
  );
  return { excel, card, meta };
}

function write(name: string, content: string) {
  fs.writeFileSync(path.join(EVID, name), content);
}

async function shot(page: Page, name: string) {
  await page.screenshot({ path: path.join(EVID, `${name}.png`), fullPage: true });
}

async function login(page: Page) {
  await page.goto('/login');
  // 登录框显式限时：证伪-260918-2234 一轮卡在 Fill 直到 180s 用例超时
  await expect(page.locator('input[placeholder="用户名或邮箱"]')).toBeVisible({ timeout: 30_000 });
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/dashboard|change-password/, { timeout: 30_000 });
  if (page.url().includes('change-password')) await page.goto('/dashboard');
}

/** 视图切换器：编辑页上含「Excel 视图」选项的那个 antd Segmented。 */
function viewSwitcher(page: Page) {
  return page.locator('.ant-segmented').filter({ has: page.locator('.ant-segmented-item', { hasText: 'Excel 视图' }) }).first();
}

async function switchTo(page: Page, label: RegExp | string) {
  const sw = viewSwitcher(page);
  await expect(sw).toBeVisible({ timeout: 30_000 });
  const item = sw.locator('.ant-segmented-item', { hasText: label }).first();
  await expect(item).toBeVisible({ timeout: 10_000 });
  await item.click();
  await expect(item).toHaveClass(/ant-segmented-item-selected/, { timeout: 10_000 });
}

/** 读 Excel 视图表格：按列标题定位三列，取第 1 个数据行（0881 只有 1 行）。 */
async function readExcelView(page: Page, tag: string): Promise<{ heads: string[]; values: string[]; rowCount: number }> {
  const table = page.locator('.ant-table').filter({ has: page.locator('th', { hasText: TITLES[0] }) }).first();
  await expect(table, `[${tag}] 应出现含「${TITLES[0]}」列的表格`).toBeVisible({ timeout: 30_000 });
  // 等“加载中”消失
  await expect(page.getByText('加载中', { exact: false })).toHaveCount(0, { timeout: 30_000 });
  const heads = (await table.locator('.ant-table-thead th').allInnerTexts()).map((s) => s.trim());
  const rows = table.locator('.ant-table-tbody tr.ant-table-row');
  const rowCount = await rows.count();
  console.log(`[AC-9][${tag}] 表头=${JSON.stringify(heads)} 数据行数=${rowCount}`);
  expect(rowCount, `[${tag}] 数据行不得为 0（否则断言空跑）`).toBeGreaterThan(0);
  const cells = (await rows.first().locator('td').allInnerTexts()).map((s) => s.trim());
  console.log(`[AC-9][${tag}] 第1行全部单元格=${JSON.stringify(cells)}`);
  // 页面表头形如「[col_1]材料成本」：去掉 [col_key] 前缀后与 AC 标题逐字比较，并核对前缀 col_key 与标题的对应关系
  const titleOf = (h: string) => h.replace(/^\[col_\d+\]/, '');
  const values = TITLES.map((t) => {
    const idx = heads.findIndex((h) => titleOf(h) === t);
    return idx >= 0 ? cells[idx] ?? '<无此单元格>' : '<无此列>';
  });
  console.log(`[AC-9][${tag}] 三列=${JSON.stringify(values)}`);
  return { heads, values, rowCount };
}

/**
 * 编辑页默认停在第 1 步「选择客户」，Excel 视图切换器在第 2 步「添加产品」。
 * 正规路径（主线 22:13 答复）= 点第 1 步的「下一步」；步骤条标题不可点。
 * 「下一步」会调静默存草稿；主线称零编辑时不发请求 —— 本用例用非 GET 请求白名单断言 + 前后 SELECT 逐字比对来证明，不采信。
 * 按钮若禁用：截图 + 记录 disabled 状态后硬失败，🚫 不许绕过。
 */
async function gotoStep2(page: Page, tag: string, stepLog: string[]) {
  const sw = viewSwitcher(page);
  if (await sw.isVisible().catch(() => false)) { stepLog.push(`${tag}: 已在第 2 步`); return; }
  const next = page.getByRole('button', { name: /下\s*一\s*步/ }).first();
  await expect(next, `[${tag}] 第 1 步应有「下一步」按钮`).toBeVisible({ timeout: 60_000 });
  await next.scrollIntoViewIfNeeded();
  const disabled = await next.isDisabled();
  stepLog.push(`${new Date().toISOString()} ${tag}: 「下一步」disabled=${disabled}`);
  if (disabled) {
    await shot(page, `${tag}-next-button-disabled`);
    throw new Error(`[${tag}] 「下一步」按钮为禁用状态（disabled=true），按主线指示停下，不绕过`);
  }
  await next.click();
  stepLog.push(`${new Date().toISOString()} ${tag}: 已点击「下一步」`);
  await expect(sw, `[${tag}] 进入第 2 步后应出现含「Excel 视图」的切换器`).toBeVisible({ timeout: 60_000 });
}

/** 非 GET 请求白名单：只允许登录 POST 与两个补算 POST。 */
function isAllowedNonGet(method: string, url: string): boolean {
  const u = url.split('?')[0];
  if (method === 'POST' && /\/api\/cpq\/auth\/login$/.test(u)) return true;
  if (method === 'POST' && /\/(ensure-card-values|ensure-excel-values)$/.test(u)) return true;
  return false;
}
function isDraftLike(method: string, url: string): boolean {
  const u = url.split('?')[0];
  return /draft|save/i.test(u) || ((method === 'PUT' || method === 'PATCH') && /\/quotations\//.test(u));
}

test('AC-9 · 0881 编辑页 Excel 视图：切走切回 + 刷新后三列不变，存值与正式账逐字未变', async ({ page }) => {
  // ── 证据目录：必须是新目录，🚫 不覆盖已有证据（testing.md §5.7⑤）
  expect(fs.existsSync(EVID), `证据目录已存在，拒绝覆盖: ${EVID}`).toBe(false);
  fs.mkdirSync(EVID, { recursive: true });
  console.log(`[AC-9] 证据目录=${EVID} 栈=${STACK} FALSIFY=${FALSIFY}`);

  // ── 网络旁路记录：excel-view 原始响应 + 所有非 GET 请求（发现只读流程里的写入）
  const netLog: string[] = [];
  const nonGet: { method: string; url: string }[] = [];
  const excelViewBodies: string[] = [];
  page.on('request', (req) => {
    if (req.url().includes('/api/') && req.method() !== 'GET') {
      netLog.push(`${new Date().toISOString()} ${req.method()} ${req.url()}`);
      nonGet.push({ method: req.method(), url: req.url() });
    }
  });
  page.on('response', async (resp: Response) => {
    const u = resp.url();
    if (/\/quotations\/[^/]+\/excel-view(\?|$)/.test(u) && resp.request().method() === 'GET') {
      try {
        excelViewBodies.push(`${new Date().toISOString()} ${resp.status()} ${u}\n${await resp.text()}\n`);
      } catch { /* ignore */ }
    }
  });

  // ── 步骤 1：操作前 SELECT 两份值
  const before = snapDb();
  write('01-before-quote_excel_values.json', before.excel + '\n');
  write('01-before-quote_card_values.json', before.card + '\n');
  write('01-before-meta.txt', before.meta + '\n');
  console.log(`[AC-9] 操作前 quote_excel_values=${before.excel}`);
  console.log(`[AC-9] 操作前 quote_card_values 长度=${before.card.length} meta=${before.meta}`);
  expect(before.card, 'quote_card_values 不得为空').not.toBe('<NULL>');
  expect(before.excel, 'quote_excel_values 不得为空').not.toBe('<NULL>');

  // 期望值：以运行时 quote_excel_values 为准（AC 原文），并记录与立项值是否一致
  const excelJson = JSON.parse(before.excel);
  const row0 = excelJson?.rows?.[0];
  expect(row0, 'quote_excel_values.rows[0] 必须存在').toBeTruthy();
  const expected: string[] = ['col_1', 'col_2', 'col_3'].map((k) => String(row0[k]));
  console.log(`[AC-9] 运行时期望三列=${JSON.stringify(expected)} 立项值=${JSON.stringify(FOUNDING_VALUES)} 一致=${JSON.stringify(expected) === JSON.stringify(FOUNDING_VALUES)}`);
  if (FALSIFY) {
    expected[2] = '9.999999999';
    console.log(`[AC-9][证伪] 期望第 3 列被篡改为 ${expected[2]}，本次必须硬失败`);
  }

  const stepLog: string[] = [];
  const observed: Record<string, { heads: string[]; values: string[]; rowCount: number }> = {};

  try {
    // ── 步骤 2：登录 + 打开编辑页
    await login(page);
    await page.goto(`/quotations/${QUOTATION_ID}/edit`);
    await page.waitForLoadState('domcontentloaded'); // 🚫 不用 networkidle：编辑页有持续请求，networkidle 无超时会挂死
    await expect(page.locator('.ant-steps-item').first()).toBeVisible({ timeout: 60_000 });
    await shot(page, '02a-open-edit-page-step1');
    await gotoStep2(page, 'open', stepLog);
    const segTexts = await viewSwitcher(page).locator('.ant-segmented-item').allInnerTexts();
    console.log(`[AC-9] 视图切换项=${JSON.stringify(segTexts.map((s) => s.trim()))}`);
    await shot(page, '02b-step2-card-view');

    // ── 步骤 3：切到 Excel 视图，记下三列
    await switchTo(page, 'Excel 视图');
    observed['03-first'] = await readExcelView(page, '03-first');
    await shot(page, '03-excel-view-first');

    // ── 步骤 4：切回卡片视图，再切 Excel 视图
    await switchTo(page, /卡片/);
    await expect(page.locator('.ant-table').filter({ has: page.locator('th', { hasText: TITLES[0] }) })).toHaveCount(0, { timeout: 15_000 });
    await shot(page, '04a-back-to-card-view');
    await switchTo(page, 'Excel 视图');
    observed['04-second'] = await readExcelView(page, '04-second');
    await shot(page, '04b-excel-view-second');

    // ── 步骤 5：刷新浏览器，再切 Excel 视图
    await page.reload();
    await page.waitForLoadState('domcontentloaded'); // 🚫 不用 networkidle：编辑页有持续请求，networkidle 无超时会挂死
    await expect(page.locator('.ant-steps-item').first()).toBeVisible({ timeout: 60_000 });
    await shot(page, '05a-after-reload');
    await gotoStep2(page, 'reload', stepLog);
    await shot(page, '05b-after-reload-step2');
    await switchTo(page, 'Excel 视图');
    observed['05-after-reload'] = await readExcelView(page, '05-after-reload');
    await shot(page, '05c-excel-view-after-reload');
  } catch (e) {
    await shot(page, '99-failure-state').catch(() => {});
    throw e;
  } finally {
    // ── 步骤 7：操作后再 SELECT 一次（无论前面成败都留证据）
    // 用 Node 级 sleep：page 可能已因超时关闭，page.waitForTimeout 会抛错导致操作后 SELECT 被跳过（证伪-260918-2234 实测）
    await new Promise((r) => setTimeout(r, 3000)); // 给可能的异步写入留出落库时间，再采样
    const after = snapDb();
    write('07-after-quote_excel_values.json', after.excel + '\n');
    write('07-after-quote_card_values.json', after.card + '\n');
    write('07-after-meta.txt', after.meta + '\n');
    write('06-observed-excel-view.json', JSON.stringify({ stack: STACK, expected, founding: FOUNDING_VALUES, stepLog, observed }, null, 2) + '\n');
    write('08-non-get-requests.txt', (netLog.length ? netLog.join('\n') : '(无非 GET 的 /api 请求)') + '\n');
    write('09-excel-view-responses.txt', excelViewBodies.join('\n') || '(未捕获到 GET excel-view 响应)\n');
    const excelSame = before.excel === after.excel;
    const cardSame = before.card === after.card;
    write(
      '10-compare.txt',
      `stack=${STACK}\nquote_excel_values 逐字相同=${excelSame}\nquote_card_values 逐字相同=${cardSame}\n` +
        `before.meta=${before.meta}\nafter.meta =${after.meta}\n`,
    );
    console.log(`[AC-9] 操作后 quote_excel_values=${after.excel}`);
    console.log(`[AC-9] 存值逐字相同=${excelSame} 正式账逐字相同=${cardSame}`);
    console.log(`[AC-9] meta 前=${before.meta}`);
    console.log(`[AC-9] meta 后=${after.meta}`);
    console.log(`[AC-9] 非 GET 请求=${JSON.stringify(netLog)}`);
    expect.soft(after.excel, 'quote_excel_values 操作前后必须逐字相同').toBe(before.excel);
    expect.soft(after.card, 'quote_card_values 操作前后必须逐字相同').toBe(before.card);
  }

  // ── 断言：零编辑点「下一步」不得发出存草稿类请求；非 GET 只允许白名单
  const draftLike = nonGet.filter((r) => isDraftLike(r.method, r.url));
  const notAllowed = nonGet.filter((r) => !isAllowedNonGet(r.method, r.url));
  console.log(`[AC-9] 存草稿类请求=${JSON.stringify(draftLike)} 白名单外请求=${JSON.stringify(notAllowed)}`);
  expect(draftLike, '整个用例期间不得发出存草稿类请求（url 含 draft/save 或对报价单的 PUT/PATCH）').toEqual([]);
  expect(notAllowed, '非 GET 请求只允许登录 POST 与 ensure-card-values / ensure-excel-values').toEqual([]);

  // ── 断言：三次列标题 + 数值
  const steps = Object.keys(observed);
  expect(steps, '三次 Excel 视图观测都必须执行到').toEqual(['03-first', '04-second', '05-after-reload']);
  for (const s of steps) {
    const o = observed[s];
    TITLES.forEach((t, i) => {
      expect(o.heads, `[${s}] 列标题应含「[col_${i + 1}]${t}」（去前缀后为「${t}」）`).toContain(`[col_${i + 1}]${t}`);
    });
    o.values.forEach((v, i) => {
      const shown = v.replace(/,/g, '');
      expect(Number.isFinite(Number(shown)) && shown !== '', `[${s}] ${TITLES[i]} 显示值应为数字，实际「${v}」`).toBe(true);
      expect(Number(shown), `[${s}] ${TITLES[i]} 显示「${v}」应等于 ${expected[i]}`).toBe(Number(expected[i]));
    });
  }
});
