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
 *   3. 🚨 只读 SQL —— **2026-09-04 起有且仅有一个例外**，必须写清楚免得下一个人以为本文件仍是纯读：
 *      `setProductionNoInDb()` 会 `UPDATE ds_quote_material.production_no`，
 *      **只服务于 `assertSameDatabase()` 的哨兵与写用例的还原**（见文件末尾那一节的完整论证）。
 *      它把表名列名写死、`WHERE` 恒为 `material_no = 'S-1630010773'`、执行前先数命中行数（≠1 拒绝执行）、
 *      写后回读校验。**除它之外，本文件所有 SQL 仍是 SELECT，没有任何 DELETE / TRUNCATE。**
 *
 *      ⚠️ 对「只读证明」的影响：哨兵**必须在 `snapshotDataState()` 取基线之前**完成写入与复位。
 *      顺序错了，`assertNoWrite` 会把哨兵算成「页面写的」⇒ 变成一条**假红**，
 *      而假红比没有守卫更糟 —— 它会让人怀疑一个本来正确的结论。
 */
import { expect, Page, APIRequestContext, request as pwRequest } from '@playwright/test';
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
 * 🚨 空态窗口守卫（AC-13 专用，`PW_EMPTY_WINDOW=1` 时替代 `assertSampleDataLoaded`）。
 *
 * AC-13 的前提与其余用例**正好相反**：它要求 `ds_quote_material` **就是 0 行**。
 * ⇒ 不能沿用「0 行就硬失败」的前置守卫，否则唯一能验空态的用例反而跑不起来。
 *
 * 但这**不是放宽**：本守卫同样是硬断言，只是断言方向相反 ——
 *   ① 表必须存在（不存在 ⇒ 页面 404 而非空态，验的不是一回事）
 *   ② 四张表必须**确实为 0 行**（非 0 ⇒ 窗口已被占用，硬失败并要求上报，
 *      🚫 不许改用「搜一个不存在的关键词」等代理条件把自己糊绿）
 */
export function assertEmptyWindow() {
  for (const t of ['ds_quote_material', 'ds_quote_customer_part']) {
    if (!tableExists(t)) {
      throw new Error(`🚨 停下报告：表 ${t} 不存在 —— 页面会 404，验的不是空态。`);
    }
  }
  const rows = ['ds_quote_material', 'ds_quote_customer_part',
                'ds_quote_material_bom', 'ds_quote_element_bom']
    .map(t => [t, Number(sqlOne(`SELECT count(*) FROM ${t}`))] as const);
  console.log('[空态窗口] ' + rows.map(([t, n]) => `${t}=${n}`).join('  '));
  for (const [t, n] of rows) {
    expect(n, `🚨 AC-13 的空态窗口已被占用：${t} 有 ${n} 行。\n` +
      `窗口须由 dev-docs/task-260903-产品管理页重做/回退-清理我方数据.sql 精确回收后重开。\n` +
      `🚫 不得改用代理条件把本用例变绿。`).toBe(0);
  }
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
  // ⚠️ SPA 鉴权态未就绪时会被弹回 /login。重试到真正落在 /products-hub 为止。
  for (let i = 0; i < 3 && /\/login/.test(page.url()); i++) {
    await page.waitForTimeout(1000);
    await page.goto('/products-hub');
  }

  // 🚨 空白页兜底（2026-09-03 实测的**测试基础设施**问题，不是产品缺陷）：
  //    症状 = URL 正确停在 /products-hub、但 body 全空、20s 内 React never mounts，
  //    且每轮挂的用例都不一样（第一轮 E2E-06、第二轮 E2E-07）——
  //    `testing.md §4`「随机挂一条、每次不一样」= 先查测试基础设施。
  //    已排除：vite 未发生依赖重新预构建（日志无 optimize/reload）、登录返回 200、服务存活。
  //    ⇒ 这里做**有界重载**，但每次重试都 console.warn 打出来，
  //    🚫 绝不静默 —— 静默重试会把真实的「页面白屏」缺陷一起吞掉。
  for (let i = 0; i < 3; i++) {
    const txt = (await page.locator('body').innerText().catch(() => '')).trim();
    if (txt.length > 0) break;
    console.warn(`[gotoProductHub] ⚠️ 第 ${i + 1} 次检测到空白 body（URL=${page.url()}），reload 重试。` +
      `这是已知的 dev-server 瞬态；若最终仍空白会硬失败。`);
    await page.reload({ waitUntil: 'domcontentloaded' }).catch(() => {});
    await page.waitForTimeout(2000);
  }
  // 🚨 失败信息必须自带现场：只说「页面未实现或路由错」会把**会话/路由问题**误导成产品缺陷
  //    （2026-09-03 实测踩到：手工驱动同一页面渲染完全正常，但用例报「页面未实现」）。
  try {
    await expect(page.getByRole('tab', { name: '客户产品' })).toBeVisible({ timeout: 20_000 });
  } catch (e) {
    const url = page.url();
    const body = (await page.locator('body').innerText().catch(() => '<取不到>')).slice(0, 300).replace(/\n+/g, ' | ');
    const tabs = await page.locator('[role="tab"]').allInnerTexts().catch(() => []);
    const html = (await page.content().catch(() => '')).length;
    console.warn(`[gotoProductHub] 失败现场：document.length=${html}`);
    throw new Error(
      `「客户产品」页签未渲染。\n  当前 URL = ${url}\n  页面上的 role=tab = ${JSON.stringify(tabs)}\n` +
      `  body[0:300] = ${body}\n` +
      `  ⇒ URL 停在 /login = 会话未建立（测试基础设施问题）；URL 正确但无 tab 才是产品缺陷。`);
  }
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
 * 🚨 按**列头文案**取列下标 —— 替代硬编码 `td.nth(i)`。
 *
 * 2026-09-04 实证（`E2E-15` 假红）：父任务写死 `td.nth(1)` = 「品名」，成立的前提是
 * 销售产品**恰好 7 列**。子任务按用户裁决在第 2 位插入「产品分类」列后，列序整体右移，
 * `nth(1)` 变成「产品分类」（内容 `默认分类`，4 字不溢出）⇒ 那条「内容确实溢出了单元格」
 * 的非空守卫报 `Expected > 140, Received 140`。
 * **产品行为是对的，红的是用例的下标脆弱性** —— 与 `docs/反模式.md` AP-54
 * 「过滤后下标当原数组下标」同型：**渲染顺序会变的东西，不能当稳定标识**。
 *
 * ⇒ 一律先读表头文案定位下标，再取对应 `td`。列序再变也不会验错列；
 *   而列**改名或消失**时本函数**硬失败**（不是静默退回 0 号列），这正是它该有的牙。
 */
export async function columnIndexOf(scope: any, header: string): Promise<number> {
  const th = scope.locator('.ant-table-thead th');
  const n = await th.count();
  // 🚨 这里**不过滤空表头**（`headerTexts()` 会过滤）：过滤会让数组下标与 `td` 下标错位，
  //    正是本函数要根治的那类 bug。
  const raw: string[] = [];
  for (let i = 0; i < n; i++) {
    raw.push((await th.nth(i).innerText()).replace(/\s+/g, '').trim());
  }
  const idx = raw.indexOf(header);
  expect(idx,
    `列「${header}」不在当前表头里。实际表头 = ${JSON.stringify(raw)}\n` +
    '⇒ 列被改名/删除/尚未渲染。🚫 不得退回硬编码下标 —— 那会静默验错列。')
    .toBeGreaterThanOrEqual(0);
  return idx;
}

/**
 * 取某一行里「列头文案为 `header`」的那个单元格。
 *
 * 附带一条对齐守卫：表头 `th` 数必须等于该行 `td` 数。不等说明有 `colSpan` /
 * 展开列 / 选择列之类的结构，**按下标取列本身就不成立**，此时硬失败而不是取错一列继续跑。
 */
export async function cellByHeader(scope: any, row: any, header: string) {
  const idx = await columnIndexOf(scope, header);
  const ths = await scope.locator('.ant-table-thead th').count();
  const tds = await row.locator('td').count();
  expect(tds,
    `表头 ${ths} 列，行内 ${tds} 个 td —— 数量不等时按下标取列不可靠（colSpan/展开列/选择列）。`)
    .toBe(ths);
  return row.locator('td').nth(idx);
}

/** 取当前表格**第一行**里 `header` 那一列的单元格。 */
export async function firstRowCellByHeader(page: Page, header: string) {
  const row = page.locator('.ant-table-tbody tr.ant-table-row').first();
  return cellByHeader(page, row, header);
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

/**
 * 在当前页签的搜索框里输入关键词。⚠️ 占位符文案可能是「搜索」也可能带具体字段，用 `*=` 宽松匹配。
 *
 * 🚨 **必须排除 `.ant-select-input`**（2026-09-04 子任务 `产品维护能力增强` 实测踩到）：
 *    antd `Select` 内部会渲染一个 `<input type="search" readonly class="ant-select-input">`，
 *    子任务给「客户产品」加了客户过滤器（位置在搜索框**左侧**）之后，它在 DOM 里排在真搜索框**前面**
 *    ⇒ `.first()` 命中的是那个 **readonly** 的假搜索框，`fill()` 永远等不到 editable，
 *      表现为 `Test timeout` —— **看起来完全像产品 bug**（"搜索框点不动了"），实则是选择器撞车。
 *    ⚠️ 这条同样影响父任务的 `product-hub-readonly.spec.ts`（其 `E2E-02` 会在客户产品页签搜客户编号）。
 */
export async function search(page: Page, keyword: string) {
  const box = page.locator(
    'input[placeholder*="搜索"]:not(.ant-select-input), input[type="search"]:not(.ant-select-input)'
  ).first();
  await expect(box, '工具栏应有搜索框（空态下也必须渲染，AC-13）').toBeVisible({ timeout: 10_000 });
  await box.fill(keyword);
  await box.press('Enter').catch(() => {});
  await page.waitForTimeout(1200);
}

export async function clearSearch(page: Page) {
  // 同 search()：必须排除 antd Select 内部那个 readonly 的 `type="search"` 输入框
  const box = page.locator(
    'input[placeholder*="搜索"]:not(.ant-select-input), input[type="search"]:not(.ant-select-input)'
  ).first();
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

/**
 * 🚨 **本套用例唯一允许写的料号**（`test.md §1` 纪律 2）。
 * 2026-09-03 实测其 `production_no='1630010773'`、`source='IMPORT'`。
 */
export const HERO_EDIT = 'S-1630010773';
// ─────────────────────────── 库读 / 唯一的库写（还原用） ───────────────────────────

/** 读 `production_no`（区分 NULL 与空串 —— AC-12 的断言正是这个区别）。 */
export function readProductionNo(): { raw: string | null; isNull: boolean; isEmptyString: boolean } {
  const marker = sqlOne(
    `SELECT CASE WHEN production_no IS NULL THEN '<NULL>'
                 ELSE '<VAL>' || production_no END
     FROM ds_quote_material WHERE material_no = '${HERO_EDIT}'`);
  if (marker === null) throw new Error(`🚨 ${HERO_EDIT} 在库里查不到 —— 前置守卫应该先拦住才对`);
  if (marker === '<NULL>') return { raw: null, isNull: true, isEmptyString: false };
  const raw = marker.slice('<VAL>'.length);
  return { raw, isNull: false, isEmptyString: raw === '' };
}

export function readColumn(col: 'source' | 'material_name' | 'updated_by'): string | null {
  const m = sqlOne(
    `SELECT CASE WHEN ${col} IS NULL THEN '<NULL>' ELSE '<VAL>' || ${col} END
     FROM ds_quote_material WHERE material_no = '${HERO_EDIT}'`);
  return m === '<NULL>' || m === null ? null : m.slice('<VAL>'.length);
}

/**
 * 🚨 **本文件唯一的写库入口**，且只用于**还原**。
 *
 * 四道硬约束（缺一即拒绝执行，`CLAUDE.md §3.2` 不可逆操作红线的落实）：
 *   1. 表名 / 列名**写死在模板里**，不接受参数拼接 ⇒ 不可能变成别的表。
 *   2. `WHERE` 恒为 `material_no = 'S-1630010773'`（`HERO_EDIT` 常量）⇒ 不可能无 WHERE、
 *      也不可能出现反向条件（`NOT LIKE` 那类会把别人的数据带走）。
 *   3. **执行前先数命中行数**，`≠ 1` 直接抛错拒绝执行 ⇒ 影响面在执行前就是已量化的「1 行」。
 *   4. 只 `UPDATE` `production_no` 一列 ⇒ 🚫 不碰 `source` / `updated_*`
 *      （AC-10 断言 `source` 保持 `IMPORT`，还原时把它一起写反而会掩盖缺陷）。
 *
 * 📌 为什么还原走 SQL 而不是走页面：页面正是被测对象。它若坏了，走页面还原**也会失败**，
 *    于是残留留在共享库里没人知道。还原路径必须与被测路径正交。
 */
export function restoreProductionNo(original: string | null): void {
  setProductionNoInDb(original, '还原');
}

/**
 * 🚨 **本文件唯一真正执行 UPDATE 的地方**（`restoreProductionNo` 与 DB 同一性哨兵都走它）。
 * 四道硬约束集中在这一处，就不会出现「某个调用方绕过了守卫」。
 */
function setProductionNoInDb(value: string | null, purpose: string): void {
  const hit = Number(sqlOne(`SELECT count(*) FROM ds_quote_material WHERE material_no = '${HERO_EDIT}'`));
  if (hit !== 1) {
    throw new Error(
      `🚨 拒绝执行写入[${purpose}]：WHERE material_no='${HERO_EDIT}' 命中 ${hit} 行（要求恰好 1 行）。\n` +
      `  影响面不是 1 行就不许写 —— 停下报告主线（CLAUDE.md §3.2 第 1 步：先量化影响面）。`);
  }
  if (value !== null && value.includes("'")) {
    throw new Error(`🚨 拒绝执行写入[${purpose}]：值含单引号，本模板不做转义。值=${JSON.stringify(value)}`);
  }
  const setExpr = value === null ? 'NULL' : `'${value}'`;
  const q = `UPDATE ds_quote_material SET production_no = ${setExpr} WHERE material_no = '${HERO_EDIT}'`;
  console.log(`[写库:${purpose}] db=${process.env.PW_DB || 'cpq_db_0724'}  ${q}`);
  const out = execFileSync('psql',
    ['-h', process.env.PW_DB_HOST || '10.177.152.12', '-U', process.env.PW_DB_USER || 'postgres',
     '-d', process.env.PW_DB || 'cpq_db_0724', '-tAF', '\t', '-c', q],
    { env: { ...process.env, PGPASSWORD: process.env.PW_DB_PASS || 'joii5231' }, encoding: 'utf-8' });
  const affected = out.trim();
  console.log(`[写库:${purpose}] psql 回执 = ${affected}`);
  if (affected !== 'UPDATE 1') {
    throw new Error(`🚨 写入[${purpose}]未按预期影响 1 行（psql 回执 = ${JSON.stringify(affected)}）—— 请人工核对并上报。`);
  }
  const now = readProductionNo();
  const ok = value === null ? now.isNull : now.raw === value;
  if (!ok) {
    throw new Error(
      `🚨 写入[${purpose}]后校验失败：期望 ${JSON.stringify(value)}，实际 ${JSON.stringify(now.raw)}（isNull=${now.isNull}）。\n` +
      `  ⚠️ 库上可能留下了残留，**必须上报主线**，不要重试掩盖。`);
  }
  console.log(`[写库:${purpose}] ✅ ${HERO_EDIT}.production_no = ${JSON.stringify(value)}`);
}
// ─────────────────────────── 接口层（A-01~A-04） ───────────────────────────

/** 直连后端的匿名请求上下文（AC-15 的 401 必须匿名打，走前端代理会带上 cookie）。 */
export async function anonymousApi(): Promise<APIRequestContext> {
  return pwRequest.newContext({ baseURL: BACKEND_URL });
}

/** 直连后端 + 指定角色登录后的请求上下文（鉴权是 **Cookie** 不是 Bearer，回归执行手册 §1 实证）。 */
export async function apiAs(role: RoleKey): Promise<APIRequestContext> {
  const { username, password } = credOf(role);
  const ctx = await pwRequest.newContext({ baseURL: BACKEND_URL });
  const res = await ctx.post('/api/cpq/auth/login', { data: { username, password } });
  const body = await res.text().catch(() => '');
  expect(res.ok(),
    `接口层登录失败 role=${role} user=${username} status=${res.status()} body=${body.slice(0, 200)}\n` +
    `  429 = 登录限流（30 次/分/IP，测试基础设施问题）；其它码需区分「口令不对」（环境缺陷）与「鉴权坏了」（产品缺陷）`)
    .toBe(true);
  return ctx;
}

// ─────────────────────────── 🚨 DB 同一性守卫（2026-09-04 事故后新增） ───────────────────────────

/**
 * 🚨 **断言「页面后端连的库」与「断言查的库」是同一个库。**
 *
 * ── 为什么需要它（2026-09-04 真实事故）──
 *   本套断言用的是不变量形式「页面总数 == 同一时刻库中 count(*)」——
 *   这个形式是为了抗共享库漂移，**但它引入了一个新维度：哪个库**。
 *   实测踩到：借用的临时后端 `:8199` 连的是**克隆库**，而 helpers 的 `PW_DB`
 *   默认写死 `cpq_db_0724`（共享库）⇒ 两个 17 来自**不同的库**。
 *   克隆库数据一致 ⇒ 数字碰巧相等 ⇒ **断言 PASS，但验证逻辑是错位的**。
 *
 *   ⚠️ 这类错位 **只会假绿不会假红**：两库一致时永远 PASS，
 *   只有在两库不一致时才以「产品 bug」的面目爆出来。
 *   而且**断言写得越严谨越容易中招** —— 写死绝对值 17 反而没这个问题。
 *
 *   ⚠️ 写侧更糟：UI 写克隆库、`finally` 的还原去 UPDATE 共享库（空转），
 *   而 `afterAll` 的「无残留证明」读共享库 ⇒ 报「逐字节相同 ✅」。
 *   **用来证明「没弄脏环境」的那条证据，本身也被同一个洞骗过去了。**
 *
 * ── 四步，第 ④ 步是这条守卫成不成立的分界 ──
 *   ① 取后端侧的 `total`
 *   ② 取 `PW_DB` 侧的 `count(*)`
 *   ③ 不相等 → 硬失败
 *   ④ 🚨 **验明正身（哨兵法）**：往 `PW_DB` 写一个高熵哨兵，
 *      再**经页面后端的只读端点**读回来；读不到 ⇒ 不是同一个库。
 *
 *   🚨 **①②③ 在本次事故里同样会通过（42 == 42）—— 判别力为零。**
 *   原因是克隆库与源库逐行一致，任何**只读**的比对都分辨不出它们；
 *   **只有一次「变更」能区分**。所以 ④ 必须是写，且不做 ④ 就别做这条守卫 ——
 *   那只会让人以为「已经有防护了」，比没有更糟。
 *
 * ── 哨兵为什么用 UPDATE 而不是 INSERT ──
 *   备选是插一行 `PWDBPROBE-xxx` 再删。否决理由：
 *     · INSERT/DELETE 会让 `ds_quote_material` 行数瞬时变化 ⇒ 撞坏别人正在跑的行数断言
 *       与父任务 `assertNoWrite` 的表级指纹；
 *     · 崩溃在 INSERT 与 DELETE 之间会留下一行**孤儿**，而清理它需要一条前缀条件，
 *       前缀条件的命中面不如「等于某个主键」那么确定。
 *   改用 UPDATE `HERO_EDIT` 的 `production_no`：
 *     · 只碰**本套已获授权的那一行**（`test.md §1` 纪律 2）；
 *     · 行数恒定不变；
 *     · 清理条件就是它自己的主键 `material_no = 'S-1630010773'`，命中面恒为 1 行；
 *     · 复用同一个带四道守卫的写入口 `setProductionNoInDb`。
 */
export async function assertSameDatabase(
  opts: { __fsSkipSentinelWrite?: boolean } = {}
): Promise<void> {
  // 🔬 `__fsSkipSentinelWrite` **只给证伪实验用**（`product-hub-db-identity-fs.spec.ts`）：
  //    跳过哨兵的写入、其余一字不改，于是步骤④ 必须失败。
  //    这样证伪实验跑的是**这条守卫本身**，而不是一份平行实现 —— 平行实现证明不了正品有牙。
  const dbName = process.env.PW_DB || 'cpq_db_0724';
  const ctx = await apiAs('SYSTEM_ADMIN');
  try {
    const url = '/api/cpq/dataset/quote/parts?page=0&size=500';

    // ① 后端侧
    const res1 = await ctx.get(url);
    const raw1 = await res1.text();
    if (!res1.ok()) {
      throw new Error(`🚨 DB 同一性守卫无法执行：GET ${url} → ${res1.status()}  body=${raw1.slice(0, 300)}`);
    }
    let body1: any;
    try { body1 = JSON.parse(raw1); } catch {
      throw new Error(`🚨 DB 同一性守卫无法执行：响应不是 JSON。body=${raw1.slice(0, 300)}`);
    }
    const backendTotal = body1?.data?.total ?? body1?.total;

    // ② PW_DB 侧
    const dbTotal = Number(sqlOne('SELECT count(*) FROM ds_quote_material'));
    console.log(`[DB同一性] ①后端 total=${backendTotal}  ②${dbName} count(*)=${dbTotal}`);

    // ③ 粗差拦截（能抓「读错表 / 完全不同的库」，抓不到克隆库）
    if (backendTotal !== dbTotal) {
      throw new Error(
        `🚨 DB 同一性守卫失败（步骤③）：后端 total=${backendTotal} ≠ ${dbName} 的 ${dbTotal} 行。\n` +
        `  ⇒ 页面后端（PW_BACKEND_URL=${BACKEND_URL}）连的不是 PW_DB=${dbName}。\n` +
        `  修法：把 PW_DB 指向后端实际连的库，或换一个连 ${dbName} 的后端。`);
    }

    // ③.5 🚨 阳性对照：先证明「这个观察通道看得见 HERO 行」。
    //     少了这一步，④ 的「读不到哨兵」会有两种解释（不同库 / 通道压根看不见这行），
    //     而失败信息会把后者说成前者 —— 把环境问题误报成同一性问题。
    const itemsOf = (b: any): any[] => (b?.data?.items ?? b?.items ?? []) as any[];
    const heroOf = (b: any) => itemsOf(b).find(it => JSON.stringify(it).includes(`"${HERO_EDIT}"`));
    const heroBefore = heroOf(body1);
    if (!heroBefore) {
      throw new Error(
        `🚨 DB 同一性守卫无法执行（步骤③.5 阳性对照失败）：后端返回的 ${itemsOf(body1).length} 条里` +
        `找不到 ${HERO_EDIT}。\n` +
        `  ⇒ 通道本身看不见这一行（分页/过滤/字段命名问题），此时步骤④ 的结论没有意义。`);
    }
    console.log(`[DB同一性] ③.5 阳性对照 ✅ 后端能看到 ${HERO_EDIT}：${JSON.stringify(heroBefore)}`);

    // ④ 🚨 验明正身：哨兵
    const orig = readProductionNo().raw;
    const sentinel = `PWDBPROBE-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
    try {
      if (opts.__fsSkipSentinelWrite) {
        console.warn('[DB同一性] 🔬 证伪模式：**跳过哨兵写入**，步骤④ 应当失败');
      } else {
        setProductionNoInDb(sentinel, 'DB 同一性哨兵');
      }
      const res2 = await ctx.get(url);
      const raw2 = await res2.text();
      const body2 = JSON.parse(raw2);
      const heroAfter = heroOf(body2);
      const seen = JSON.stringify(heroAfter ?? {}).includes(sentinel);
      console.log(`[DB同一性] ④哨兵=${sentinel}  后端读回 HERO=${JSON.stringify(heroAfter)}  命中=${seen}`);
      if (!seen) {
        throw new Error(
          `🚨 **DB 同一性守卫失败（步骤④ 验明正身）** —— 这正是 2026-09-04 那次错位。\n` +
          `  哨兵已写入 PW_DB=${dbName} 的 ${HERO_EDIT}.production_no = ${sentinel}\n` +
          `  但页面后端（${BACKEND_URL}）读回的仍是 ${JSON.stringify(heroAfter)}\n` +
          `  ⇒ **后端连的不是 ${dbName}**（很可能是它的一个克隆库）。\n` +
          `  ⚠️ 注意 ①②③ 刚刚是**通过**的（${backendTotal} == ${dbTotal}）—— ` +
          `克隆库逐行一致，只读比对分辨不出，只有这一步能分辨。\n` +
          `  🚫 不要因为「数字对得上」就放行：此时所有「页面 == 库」断言都是错位的，` +
          `写用例的还原更会写错库、而「无残留证明」会给出假的"干净"结论。`);
      }
      console.log(`[DB同一性] ✅ 验明正身：后端 ${BACKEND_URL} 与 PW_DB=${dbName} 是同一个库`);
    } finally {
      // 哨兵自清理：条件落在哨兵自己的主键上，命中面恒为 1 行，🚫 无任何反向条件。
      // 证伪模式没写过，就不需要复位（也不该白写一次库）。
      if (!opts.__fsSkipSentinelWrite) setProductionNoInDb(orig, '哨兵复位');
    }
  } finally {
    await ctx.dispose();
  }
}
