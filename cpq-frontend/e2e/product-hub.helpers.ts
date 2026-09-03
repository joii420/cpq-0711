/**
 * task-260903「产品管理页重做」E2E 公共件。
 *
 * 本页是**纯只读页**，所以本文件的纪律与 task-260901 那套有一处根本不同：
 *   task-260901 需要「改了要还原」；本套需要「**证明一个字节都没改**」。
 *   ⇒ `snapshotDataState()` / `assertNoWrite()` 是本套最重要的守卫：
 *     它把 AC-8 / AC-9「全只读」从「界面上看不到保存按钮」提升到「数据层确实没被写」。
 *     只验前者会漏掉「按钮没渲染但组件仍在后台调 PUT」这种形态。
 *
 * 三条与 task-260901 相同的纪律（不重复论证，见那份的头注释）：
 *   1. 🚨 端口/库避让：默认拒绝跑在 5174 / 8081。
 *   2. 🚨 证据归档：截图写任务目录 `证据/`，不写 `test-results/`（下一轮会被清空 ⇒ 等于没证据）。
 *   3. 🚨 只读 SQL：本文件所有 SQL 均为 SELECT。**没有任何 DELETE / UPDATE / TRUNCATE。**
 */
import { expect, Page } from '@playwright/test';
import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const __f = fileURLToPath(import.meta.url);

/** 证据归档目录（任务目录下，随任务提交）。 */
export const EVIDENCE_DIR = path.resolve(
  path.dirname(__f),
  '../../dev-docs/task-260903-产品管理页重做/证据'
);

export const BASE_URL = process.env.PW_BASE_URL || '';
export const BACKEND_URL = process.env.PW_BACKEND_URL || '';

export const DB_NAME = process.env.PW_DB || 'cpq_db_0724';
const DB_HOST = process.env.PW_DB_HOST || '10.177.152.12';
const DB_USER = process.env.PW_DB_USER || 'postgres';
const DB_PASS = process.env.PW_DB_PASS || 'joii5231';

// ─────────────────────────── AC 常量（全部来自 需求文档.md ③，禁止就地改数） ───────────────────────────

/** AC-5 / AC-7 / AC-11 的主角料号。 */
export const HERO = 'S-3120014539';

/** AC-2 / AC-4 的基线行数（`需求文档.md` ③ 数据基线表）。 */
export const N_CUSTOMER_PART = 17;
export const N_MATERIAL = 42;

/** AC-7：主角料号在「物料BOM」的行数。 */
export const N_HERO_MATERIAL_BOM = 9;
/** AC-11 步骤④：主角料号在「物料与元素BOM」的行数。 */
export const N_HERO_ELEMENT_BOM = 2;

/** AC-2：客户产品列表 6 列，顺序固定。 */
export const CUSTOMER_PART_COLUMNS = [
  '客户编号', '客户名称', '客户料号名称', '客户产品编号', '客户图号', '销售料号',
];

/** AC-4：销售产品列表 7 列，顺序固定。 */
export const MATERIAL_COLUMNS = [
  '销售料号', '品名', '规格', '尺寸', '旧料号', '单重', '生产料号',
];

/** AC-6：抽屉左侧 13 个 tab，文案与顺序固定。 */
export const DRAWER_TABS = [
  '物料BOM', '物料与元素BOM', '来料固定加工费', '来料其他费用', '来料回收折扣',
  '自制加工费', '成品其他费用', '组成件其他费用', '组装加工费', '组装加工费年降',
  '电镀费用', '来料年降', '年降系数',
];

/** AC-6 反向断言：三个免版本 sheet **不得**出现在抽屉 tab 里。 */
export const FORBIDDEN_TABS = ['物料', '客户料号', '电镀方案'];

/** AC-12：样例数据中确定为 0 行的 tab（`样例-数据说明.md` §4.5）。 */
export const EMPTY_TAB = '年降系数';

/**
 * 本页涉及的 16 张 `ds_quote_*` 表 —— `assertNoWrite` 的观察面。
 * 🚨 表名是 2026-09-03 从 `information_schema` **实读**的，不是按 sheet 名推的。
 *    （推名会错：`成品其他费用`→`finished_other_fee` 而非 `product_other_fee`，
 *      `组成件其他费用`→`sub_component_fee`，`来料回收折扣`→`incoming_recovery`，
 *      `年降系数`→`annual_discount`。表名写错 ⇒ `tableExists` 恒 false ⇒ 守卫静默失效。）
 */
export const DS_QUOTE_TABLES = [
  'ds_quote_material', 'ds_quote_customer_part', 'ds_quote_material_bom',
  'ds_quote_element_bom', 'ds_quote_incoming_fixed_fee', 'ds_quote_incoming_other_fee',
  'ds_quote_incoming_recovery', 'ds_quote_self_process_fee',
  'ds_quote_finished_other_fee', 'ds_quote_sub_component_fee',
  'ds_quote_assembly_fee', 'ds_quote_assembly_fee_annual', 'ds_quote_plating_fee',
  'ds_quote_plating_scheme', 'ds_quote_incoming_annual', 'ds_quote_annual_discount',
];

// ─────────────────────────── 环境守卫 ───────────────────────────

/**
 * 🚨 隔离守卫。默认**拒绝**跑在共享 dev 环境上（5174 / 8081 保留给主线亲验与用户验收）。
 * 需要跑在 dev 环境时由主线显式 `PW_ALLOW_DEV_ENV=1` —— 那是主线的裁决，不是本用例自作主张。
 */
export function assertIsolatedEnv() {
  if (!BASE_URL || !BACKEND_URL) {
    throw new Error(
      '[task260903] 必须显式给 PW_BASE_URL / PW_BACKEND_URL（临时前端/后端端口）。\n' +
      '本页虽是只读页，但整套用例会持续占用浏览器与后端连接，跑在共享 5174/8081 会挤占主线亲验环境。'
    );
  }
  const onShared = /:5174(\/|$)/.test(BASE_URL) || /:8081(\/|$)/.test(BACKEND_URL);
  if (onShared && process.env.PW_ALLOW_DEV_ENV !== '1') {
    throw new Error(
      `[task260903] 🚫 拒绝在共享 dev 环境执行：BASE=${BASE_URL} BACKEND=${BACKEND_URL}\n` +
      '请起临时端口（如 5175 → 8082），或由主线显式设 PW_ALLOW_DEV_ENV=1。'
    );
  }
  console.log(`[task260903] env: base=${BASE_URL} backend=${BACKEND_URL} db=${DB_NAME}` +
    (onShared ? '  ⚠️ 已由 PW_ALLOW_DEV_ENV=1 显式放行' : ''));
}

/**
 * 🚨 前置数据守卫（`testing.md §3` 假绿红线 + 共享库漂移防护）。
 *
 * 两个独立的失败模式，必须分开报，否则会把环境问题误判成产品缺陷：
 *   ① 表不存在 / 0 行 ⇒ **所有断言空跑**，表现得和「全部通过」一模一样。
 *   ② 库里行数 ≠ AC 基线 ⇒ 样例没导 or **`task-260902` 的并发测试在同库写了数据**。
 *
 * 🚨 **不要把「库里就该是 42 行」当断言**（主线 2026-09-03 通知）：
 *    `cpq_db_0724` 是共享库，`task-260902` 五路子代理的 `@QuarkusTest` 实连同一个库，
 *    行数在跑测期间会漂移。⇒ 页面侧断言一律走 `expectTotalMatchesDb()` 的**不变量**形式。
 *    本函数只在开跑前**记录基线并给出预警**，不作为页面断言的依据。
 */
export function assertSampleDataLoaded(): { material: number; customerPart: number } {
  if (!tableExists('ds_quote_material')) {
    throw new Error(
      '🚨 停下报告：表 `ds_quote_material` 不存在。\n' +
      '本套用例依赖 task-260902 的建表迁移 + 导入 `样例-报价数据.xlsx`。\n' +
      '在此之前跑，全部断言都会空跑 —— 那是假绿，不是通过。'
    );
  }
  const material = Number(sqlOne('SELECT count(*) FROM ds_quote_material'));
  const customerPart = Number(sqlOne('SELECT count(*) FROM ds_quote_customer_part'));
  console.log(`[前置数据基线] ds_quote_material=${material}  ds_quote_customer_part=${customerPart}`);

  if (material === 0 || customerPart === 0) {
    throw new Error(
      `🚨 停下报告：前置数据为空（material=${material} customerPart=${customerPart}）。\n` +
      '0 行 ⇒ 循环 0 次 ⇒ 断言压根不执行，测试照样报绿（testing.md §3）。\n' +
      '请先导入 `dev-docs/task-260903-产品管理页重做/样例-报价数据.xlsx`。'
    );
  }
  if (material !== N_MATERIAL || customerPart !== N_CUSTOMER_PART) {
    console.warn(
      `⚠️ 库中行数与 AC 基线不一致：material ${material} (AC 基线 ${N_MATERIAL})、` +
      `customerPart ${customerPart} (AC 基线 ${N_CUSTOMER_PART})。\n` +
      '  可能原因：① 样例未按 样例-数据说明.md §3 补齐前置就部分导入；' +
      '② task-260902 的并发测试在同库写入。\n' +
      '  ⇒ 本轮页面断言走「页面总数 == 同一时刻库中 count(*)」的不变量形式，不用绝对值。'
    );
  }
  return { material, customerPart };
}

/**
 * 🚨 共享库漂移下唯一站得住的列表总数断言。
 *
 * 断的是**不变量**：「页面显示的总数 == 同一时刻库里的 count(*)」。
 * 这样即使 `task-260902` 的并发测试改了行数，本断言也不会假红；
 * 而「页面读错表 / 漏了过滤 / 分页 page 没减 1」这些**真缺陷**照样会被抓到。
 *
 * 同时把 AC 基线值打印出来供人工核对 —— 不一致只提示，不失败（那是数据问题不是页面问题）。
 */
export async function expectTotalMatchesDb(
  page: Page, table: string, acBaseline: number, label: string, where = ''
) {
  const db = Number(sqlOne(`SELECT count(*) FROM ${table}${where ? ' WHERE ' + where : ''}`));
  const ui = await totalCount(page);
  console.log(`[${label}] 页面总数=${ui}  库中 count(*)=${db}  AC 基线=${acBaseline}` +
    (db === acBaseline ? '' : '  ⚠️ 库 ≠ AC 基线（共享库漂移或前置数据未补齐）'));
  expect(ui, `${label}：分页器未渲染总数（拿不到「共 N 条」）`).not.toBeNull();
  expect(ui, `${label}：页面总数 ${ui} ≠ 同一时刻库中 ${table} 的 ${db} 行 —— ` +
    `这是**页面侧缺陷**（读错表 / 漏过滤 / 分页换算错），与共享库漂移无关`).toBe(db);
  return { ui: ui!, db };
}

/**
 * 主角料号的锚定断言。
 * 🚩 按 `material_no` 过滤计数 ⇒ **不受其它任务往同库写数据的影响**，
 *    是共享库上最稳的一类断言（主线 2026-09-03 通知的「按前缀/主键过滤后计数」）。
 */
export function heroRowsInDb(table: string): number {
  return Number(sqlOne(`SELECT count(*) FROM ${table} WHERE material_no = '${HERO}'`));
}

// ─────────────────────────── 只读 SQL ───────────────────────────

/** 只读 SQL，返回制表分隔的行数组。🚫 本文件不提供任何写库入口。 */
export function sql(q: string): string[] {
  const out = execFileSync('psql',
    ['-h', DB_HOST, '-U', DB_USER, '-d', DB_NAME, '-tAF', '\t', '-c', q],
    { env: { ...process.env, PGPASSWORD: DB_PASS }, encoding: 'utf-8' });
  return out.split('\n').map(s => s.trim()).filter(Boolean);
}

export function sqlOne(q: string): string | null {
  const r = sql(q);
  return r.length ? r[0] : null;
}

export function tableExists(t: string): boolean {
  return sqlOne(`SELECT to_regclass('public.${t}') IS NOT NULL`) === 't';
}

// ─────────────────────────── 只读证明（本套的核心守卫） ───────────────────────────

export type DataState = Record<string, string>;

/**
 * 快照 16 张 `ds_quote_*` 的行数 + 内容指纹。
 * 用 `md5(string_agg(t::text))` 而不是只数行数 —— 只数行数抓不到「行数不变但值被改」。
 */
export function snapshotDataState(): DataState {
  const st: DataState = {};
  for (const t of DS_QUOTE_TABLES) {
    if (!tableExists(t)) { st[t] = 'MISSING'; continue; }
    st[t] = sqlOne(
      `SELECT count(*)::text || ':' || coalesce(md5(string_agg(x.t, '|' ORDER BY x.t)), 'empty')
       FROM (SELECT ${t}::text AS t FROM ${t}) x`) ?? 'ERR';
  }
  return st;
}

/**
 * 🚨 AC-8 / AC-9 的数据层证据：跑完整套用例后，16 张表**逐表指纹不变**。
 * 界面上没有保存按钮 ≠ 没有写入 —— 这一条才是「全只读」的硬证据。
 */
export function assertNoWrite(before: DataState, after: DataState) {
  const diff = Object.keys(before).filter(k => before[k] !== after[k]);
  if (diff.length) {
    const detail = diff.map(k => `  ${k}: ${before[k]}  →  ${after[k]}`).join('\n');
    expect(false, `🚨 只读页产生了写入！以下表的行数或内容指纹发生变化：\n${detail}`).toBeTruthy();
  }
  console.log(`[只读证明] ${Object.keys(before).length} 张 ds_quote_* 表指纹全部未变 ✅`);
}

// ─────────────────────────── 证据归档 ───────────────────────────

let shotIdx = 0;
/** 截图 → `证据/`（不是 test-results/，后者下一轮开跑会被清空）。 */
export async function shot(page: Page, name: string, opts: { fullPage?: boolean } = {}) {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const file = path.join(EVIDENCE_DIR, `E2E-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: opts.fullPage ?? false });
  console.log(`📸 证据 → ${file}`);
  return file;
}

/** 文本证据（SQL 输出、接口原文、实际列名数组）→ `证据/`。 */
export function evidence(name: string, content: string) {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const file = path.join(EVIDENCE_DIR, `E2E-${name}.txt`);
  fs.writeFileSync(file, content, 'utf-8');
  console.log(`🧾 证据 → ${file}`);
  return file;
}

// ─────────────────────────── 登录 ───────────────────────────

export type RoleKey = 'SYSTEM_ADMIN' | 'PRICING_MANAGER' | 'SALES_REP' | 'SALES_MANAGER';

/**
 * 角色账号：**env 优先，默认值兜底**。
 *
 * 默认值是 2026-09-03 用户批准后为本任务新建的**专用账号**（只 INSERT，未改动任何现有账号）：
 *   t260903_pm     → PRICING_MANAGER
 *   t260903_sales  → SALES_REP
 * 三个账号均已**实打 `/api/cpq/auth/login` 验证**返回 200 且 `forceChangePassword=false`
 * （🚫 不采信 DB 的 `is_first_login` 字段 —— 字段对不代表登得进去）。
 *
 * 🚨 **为什么仍保留 env 覆盖 + 硬失败**：既有 `tc0712-roles.spec.ts` 写死的
 *    `salesrep` / `pricingmgr` / `salesmgr` 三个账号**如今在库里已不存在** —— 写死的账号会烂。
 *    ⇒ 账号一旦失效，`loginAs` 的报错会把「口令不对（**测试环境缺陷**）」与
 *      「鉴权坏了（**产品缺陷**）」分开说，不让人把环境问题误判成回归。
 *
 * 🚫 缺账号时**硬失败，不 skip** —— skip 掉的角色断言会以「全部通过」的样子混过去，
 *    那正是 `testing.md §3` 说的「断言从未执行 = 假绿」。
 */
export function credOf(role: RoleKey): { username: string; password: string } {
  const map: Record<RoleKey, [string, string]> = {
    SYSTEM_ADMIN:    [process.env.PW_USER_ADMIN   || 'admin', process.env.PW_PWD_ADMIN   || 'Admin@2026'],
    PRICING_MANAGER: [process.env.PW_USER_PRICING || 't260903_pm',    process.env.PW_PWD_PRICING || 'Admin@2026'],
    SALES_REP:       [process.env.PW_USER_SALES   || 't260903_sales', process.env.PW_PWD_SALES   || 'Admin@2026'],
    // SALES_MANAGER 本任务 AC 未用到（AC-16 只点名 SALES_REP）；需要时由 env 传入
    SALES_MANAGER:   [process.env.PW_USER_SMGR    || '',              process.env.PW_PWD_SMGR    || ''],
  };
  const [username, password] = map[role];
  if (!username || !password) {
    throw new Error(
      `🚨 停下报告：角色 ${role} 没有可用测试账号。\n` +
      `请由主线提供 PW_USER_* / PW_PWD_*。\n` +
      `⚠️ 这是**测试环境缺陷**，不是产品缺陷 —— 不得因此把用例改成 skip 或降级断言。`
    );
  }
  return { username, password };
}

/**
 * API 登录。
 * 🚨 带退避重试：登录限流 30 次/分/IP，整套 spec 反复重跑很容易打满。
 * 打满后表现为「登录失败」，**看起来像鉴权坏了**，实际是测试基础设施问题。
 */
export async function loginAs(page: Page, role: RoleKey) {
  const { username, password } = credOf(role);
  let last = 0, body = '';
  for (let i = 0; i < 4; i++) {
    const res = await page.request.post('/api/cpq/auth/login', { data: { username, password } });
    if (res.ok()) { console.log(`[login] ${role} = ${username} ✅`); return; }
    last = res.status();
    body = await res.text().catch(() => '');
    console.warn(`[login] ${role}(${username}) 第 ${i + 1} 次失败 status=${last} ` +
      (last === 429 ? '疑似登录限流(30/min/IP)，退避后重试' : `body=${body.slice(0, 200)}`));
    if (last !== 429) break;
    await page.waitForTimeout(20_000);
  }
  expect(false, `${role}(${username}) 登录失败，status=${last} body=${body}。` +
    `429 = 登录限流（测试基础设施问题）；其它码需区分「口令不对」（环境缺陷）与「鉴权坏了」（产品缺陷）`).toBeTruthy();
}

// ─────────────────────────── 页面动作 ───────────────────────────

/** 打开产品管理页。⚠️ 登录后可能被重定向到改密页，命中即视为环境缺陷并硬失败。 */
export async function gotoProductHub(page: Page) {
  await page.goto('/products-hub');
  if (/change-password/.test(page.url())) {
    throw new Error('🚨 停下报告：该账号 is_first_login=true，被强制跳改密页。' +
      '改密 = 改共享库全局状态，本用例不做。请主线提供一个 is_first_login=false 的账号。');
  }
  if (/\/login/.test(page.url())) await page.goto('/products-hub');
  await expect(page.getByRole('tab', { name: '客户产品' }),
    '页面应渲染出「客户产品」页签（渲染不出 = 页面未实现或路由错）').toBeVisible({ timeout: 20_000 });
}

/**
 * 切页签。
 * ⚠️ antd Tabs 的 tab 是 `role="tab"`。**不用 `.ant-tabs-tab` 类名** —— 类名在 antd 大版本间变过。
 */
export async function switchTab(page: Page, name: '客户产品' | '销售产品') {
  await page.getByRole('tab', { name, exact: true }).click();
  await page.waitForTimeout(800);
}

/** 读当前可见表格的表头文案数组（去掉空白与排序图标的干扰）。 */
export async function headerTexts(scope: any): Promise<string[]> {
  const th = scope.locator('.ant-table-thead th');
  const n = await th.count();
  const out: string[] = [];
  for (let i = 0; i < n; i++) {
    const t = (await th.nth(i).innerText()).replace(/\s+/g, '').trim();
    if (t) out.push(t);
  }
  return out;
}

/**
 * 读 antd 分页器的总数。
 * ⚠️ `共 N 条` 里 antd 会在数字两侧留空格，正则必须容忍：`/共\s*(\d+)\s*条/`。
 * 拿不到时返回 null，由调用方给出「分页器未渲染」的明确失败，而不是把 null 当 0 比。
 */
export async function totalCount(page: Page): Promise<number | null> {
  const el = page.getByText(/共\s*\d+\s*条/).last();
  if (!(await el.count())) return null;
  const m = (await el.innerText()).match(/共\s*(\d+)\s*条/);
  return m ? Number(m[1]) : null;
}

/** 列表可见行数（当前页）。 */
export async function rowCount(scope: any): Promise<number> {
  return scope.locator('.ant-table-tbody tr.ant-table-row').count();
}

/** 在当前页签的搜索框里输入关键词。⚠️ 占位符文案可能是「搜索」也可能带具体字段，用 `*=` 宽松匹配。 */
export async function search(page: Page, keyword: string) {
  const box = page.locator('input[placeholder*="搜索"], input[type="search"]').first();
  await expect(box, '工具栏应有搜索框（空态下也必须渲染，AC-13）').toBeVisible({ timeout: 10_000 });
  await box.fill(keyword);
  await box.press('Enter').catch(() => {});
  await page.waitForTimeout(1200);
}

export async function clearSearch(page: Page) {
  const box = page.locator('input[placeholder*="搜索"], input[type="search"]').first();
  await box.fill('');
  await box.press('Enter').catch(() => {});
  await page.waitForTimeout(1200);
}

/** 点开某销售料号的抽屉。 */
export async function openDrawer(page: Page, materialNo: string) {
  await search(page, materialNo);
  const cell = page.getByRole('cell', { name: materialNo, exact: true }).first();
  await expect(cell, `列表里应能搜到料号 ${materialNo}（搜不到 = 前置数据缺失，断言会空跑）`)
    .toBeVisible({ timeout: 10_000 });
  await cell.click();
  const drawer = page.locator('.ant-drawer').first();
  await expect(drawer, 'AC-5：点行应滑出 Drawer').toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(1500);
  return drawer;
}

export async function closeDrawer(page: Page) {
  const close = page.locator('.ant-drawer .ant-drawer-close').first();
  if (await close.count()) await close.click();
  else await page.keyboard.press('Escape');
  await expect(page.locator('.ant-drawer-open'), '抽屉应已关闭').toHaveCount(0, { timeout: 8_000 });
}

/** 抽屉左侧 tab 文案数组（顺序即渲染顺序）。 */
export async function drawerTabTexts(drawer: any): Promise<string[]> {
  const tabs = drawer.locator('[role="tab"]');
  const n = await tabs.count();
  const out: string[] = [];
  for (let i = 0; i < n; i++) {
    // 徽标数字与 tab 文案在同一个节点里，去掉尾部数字才是 tab 名
    const raw = (await tabs.nth(i).innerText()).replace(/\s+/g, '');
    out.push(raw.replace(/\d+$/, ''));
  }
  return out;
}

export async function clickDrawerTab(page: Page, drawer: any, name: string) {
  await drawer.locator('[role="tab"]').filter({ hasText: name }).first().click();
  await page.waitForTimeout(1500);
}

/**
 * 🚨 只读断言（AC-8 的控件层面）。
 * 「两字按钮」antd 会在两个汉字之间插空格 ⇒ **必须用 `/保\s*存/` 而不是精确文本**，
 * 否则永远 0 命中 —— 而 0 命中在「断言不存在」的场景里会**恒定通过**，是最隐蔽的假绿。
 * ⇒ 所以下面配了 `assertReadOnlyProbeWorks()` 做阳性对照。
 */
export async function assertReadOnly(drawer: any, label: string) {
  for (const [name, re] of [['保存', /保\s*存/], ['新增行', /新\s*增\s*行/], ['删除', /删\s*除/]] as const) {
    await expect(drawer.getByRole('button', { name: re }),
      `AC-8[${label}]：抽屉内不得渲染「${name}」按钮`).toHaveCount(0);
  }
  for (const tag of ['input', 'select', 'textarea']) {
    await expect(drawer.locator(`.ant-table ${tag}`),
      `AC-8[${label}]：表格内不得存在可编辑控件 <${tag}>`).toHaveCount(0);
  }
}

/**
 * 🚨 阳性对照（`testing.md §4.4`）。
 * `assertReadOnly` 全是「断言不存在」，选择器写错也会通过。
 * ⇒ 用同一套选择器去抓**已知一定存在**的东西（抽屉标题里的轴值），证明观察手段是活的。
 * 抓不到 ⇒ 说明 `drawer` 定位本身就是空的，`assertReadOnly` 的通过毫无意义。
 */
export async function assertReadOnlyProbeWorks(drawer: any, axisValue: string) {
  await expect(drawer.getByText(axisValue, { exact: false }).first(),
    `阳性对照失败：抽屉里连轴值 ${axisValue} 都抓不到 ⇒ drawer 定位是空的，` +
    `本轮所有「不存在」类断言都是空验证，不可采信`).toBeVisible({ timeout: 8_000 });
}

/** 收集 console 的 error 级日志（AC-11 要求全过程无 error）。 */
export function collectConsoleErrors(page: Page): string[] {
  const errs: string[] = [];
  page.on('console', m => { if (m.type() === 'error') errs.push(m.text()); });
  page.on('pageerror', e => errs.push(`pageerror: ${e.message}`));
  return errs;
}
