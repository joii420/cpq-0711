/**
 * repair-260910「核价树版本切换：候选列表恒空（树分支硬编码查 V6 老表）」
 * **S1 片**（唯一分片）共享夹具
 *
 * ── 判据来源（🚫 全程未读实现源码）────────────────────────────────────────
 *   dev-docs/task-260909-…/repair-260910-…/问题说明.md  §③ 业务模型 / §⑥ AC-1~AC-15 原文
 *   dev-docs/task-260909-…/repair-260910-…/test.md      §1 环境 / §2 追溯矩阵 / §4 防假绿
 *   dev-docs/task-260909-…/repair-260910-…/api.md       接口契约
 *   🚫 未读 cpq-backend/src/main/**、cpq-frontend/src/**、backtask.md、fronttask.md
 *
 * ── 造数前缀 ────────────────────────────────────────────────────────────
 *   R260910-（本片新建的对象一律带此前缀；🚫 不碰任何非该前缀的对象）
 *
 * ── 🚨 红线纪律（CLAUDE.md §3.2）─────────────────────────────────────────
 *   · 本文件的 SQL 通道**只有只读一条**（`sqlRead` 硬闸：只允许 SELECT / WITH / EXPLAIN）。
 *     DROP / TRUNCATE / DELETE / UPDATE / ALTER 一律抛错，**不许换写法重试** —— 停下来报主线。
 *   · 本片**不删除任何库对象**。跑完产生的残留（override 行等）写进 test-report 的
 *     「待回收清单」交主线，🚫 测试自己不执行回收。
 *   · 🚦 不许改任何存量核价单的**状态**（AC-9 / AC-12 需要只读一张 APPROVED 单）。
 *   · 🚫 不碰 `costing_bom_tree_config`（骨架 SQL 由主线改并记还原点）。
 *
 * ── 防假绿（test.md §4，四条全部落到代码里）──────────────────────────────
 *   ① 空验证：候选断言**先断言下拉存在且选项数 > 0**，再比内容（`assertOptionsExactly`）
 *   ② 阳性对照：AC-2 叶子无下拉的同时断言同表非叶子行**有**下拉；
 *              AC-8 的 400 断言前先用**同一 body 形状**在非叶子料号上拿到 2xx
 *   ③ 假红（环境）：`assertEnvIdentity` 经**前端代理**与直连后端逐字比对（test.md §1）
 *   ④ 选择器坑：antd v6 类名与 v5 不同；一律 dump 候选值再硬失败，🚫 不静默 0 命中
 */
import { expect, request, APIRequestContext, Page, Locator } from '@playwright/test';
import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const __f = fileURLToPath(import.meta.url);

// ─────────────────────────── 0. 环境坐标 ───────────────────────────

export const BASE_URL = process.env.PW_BASE_URL || 'http://localhost:5174';
export const BACKEND_URL = process.env.PW_BACKEND_URL || 'http://localhost:8081';

export const DB = {
  host: process.env.PW_DB_HOST || '10.177.152.12',
  port: process.env.PW_DB_PORT || '5432',
  user: process.env.PW_DB_USER || 'postgres',
  db: process.env.PW_DB || 'cpq_db_0724',
  password: process.env.PW_DB_PASS || 'joii5231',
};

/** 本片造数前缀 —— 跨片/跨会话防串扰的唯一依据（派工书 §f）。 */
export const PREFIX = 'R260910-';

// ─────────────────────────── 1. 载体常量（问题说明 §⑥ 统一环境 + 主线已核实事实）───────────────────────────

/**
 * 🚨 载体是**存量单** HJ-20260910-0975（PENDING，客户「正泰」），不是本片自建。
 *    理由：AC-1~AC-7 的基线树 `300001 ▸ {…}` 全库仅此一张单具备（已实查）。
 *    ⚠️ 这与 `test.md §3`「两项写入面都只写自己造的单」有出入 —— 已在 test-cases.md
 *      §0 与回报里显式登记，等主线确认。本片对它的写入只有两类，且都可还原：
 *        · `costing_order_version_override` 新增/更新 (COMP-2299, 300001)
 *        · 该单的 `costing_render` / `costing_total_amount` 随切换重算
 *      退场统一切回 v3（AC-7 本身就是这条断言）。
 */
export const COID = process.env.R260910_COID || 'bea4c465-d15a-411e-991d-43f9e108c087';
export const COSTING_ORDER_NO = 'HJ-20260910-0975';
export const LIID = process.env.R260910_LIID || '3c582035-d35a-4f99-9372-cb4b908a29bb'; // S0001 铆钉
export const QUOTATION_NO = process.env.R260910_QUOTATION_NO || 'QT-20260910-0802';

/** 树组件：COMP-2299「BOM」，`bom_recursive_expand=true`（问题说明 §② 前置数据）。 */
export const CID_TREE = process.env.R260910_CID_TREE || '32ab8212-df6c-4844-9721-ac7dc41d6cf2';
export const TAB_TREE = 'BOM';
/** 非树对照组件：COMP-2300「材质元素」（AC-11 的不回归基线，走 `dsCostBase != null` 分支）。 */
export const CID_FLAT = process.env.R260910_CID_FLAT || '9291b050-b6a9-42c7-8c56-d3066c3c2369';
export const TAB_FLAT = '材质元素';

/**
 * COST_DETAIL **方言的树组件** —— AC-10 的载体。
 * 🚨 实查（2026-09-10）：库里 9 个引用 `ds_cost_detail_*` 的组件**全部 `bom_recursive_expand=f` 且 DISABLED**
 *    ⇒ 现有数据下**没有**能触达本次所改树分支的 COST_DETAIL 组件。
 *    ⇒ 未提供本变量时 AC-10 **硬失败**（而不是 skip）——skip 会把覆盖缺口洗成绿。
 */
export const CID_DETAIL_TREE = process.env.R260910_CID_DETAIL_TREE || '';

// ─────────────────────────── 2. AC 期望值（🚨 逐字来自 AC 原文，不从库里推）───────────────────────────

/**
 * 🚨 下面三组常量**照抄 `问题说明.md §⑥` 的 AC 原文**，🚫 不许改成"跑一遍查库拿到什么就是什么"。
 *    从库里现算期望值 = 断言退化成「实现和它自己一致」，AC 就白写了。
 *    库里的实际分布只用于**前置漂移体检**（`assertFixtureIntegrity`），漂移时报「前置数据变了」
 *    而不是报「产品坏了」—— 两者的处理动作完全不同。
 */

/** AC-1：树上每行版本 = **自己那张清单**的当前版本。null = AC-2 的叶子（版本列为 `—`）。 */
export const EXPECTED_CURRENT_VERSION: Record<string, string | null> = {
  '300001': '3',
  '300012': '1',
  '300013': '1',
  '300015': '1',
  '300014': null, // 叶子
  '991': null,    // 叶子
  '992': null,    // 叶子
};

/** AC-3 / AC-4：候选**恰好**等于（顺序按接口返回原样比较前先归一化排序，见 assertOptionsExactly）。 */
export const EXPECTED_OPTIONS: Record<string, string[]> = {
  '300001': ['3', '2', '1'], // AC-3
  '300012': ['1'],           // AC-4
  '300013': ['1'],           // AC-4
  '300015': ['1'],           // AC-4 —— 🚫 不得出现 '2'（它挂 300001 下那条边的版本，非它自己的）
  '300014': [],              // AC-2 叶子
  '991': [],
  '992': [],
};

/** AC-2：叶子集合（作为 `production_no` 在 BOM 表里查不到者）。 */
export const LEAF_PARTS = ['300014', '991', '992'];
/** 非叶子集合（AC-2 阳性对照用）。 */
export const NON_LEAF_PARTS = ['300001', '300012', '300013', '300015'];

/**
 * AC-13 / AC-7 基线树（问题说明 §⑥「基线树（主任务交付，**本次不得改变**）」）。
 * 键 = `__nodeId`（node_path），值 = { lvl, parentNo }。**行数 7**。
 */
export const BASELINE_TREE: { nodePath: string; lvl: number; parentNo: string | null; partNo: string }[] = [
  { nodePath: '300001',                    lvl: 1, parentNo: null,     partNo: '300001' },
  { nodePath: '300001/300012',             lvl: 2, parentNo: '300001', partNo: '300012' },
  { nodePath: '300001/300013',             lvl: 2, parentNo: '300001', partNo: '300013' },
  { nodePath: '300001/300014',             lvl: 2, parentNo: '300001', partNo: '300014' },
  { nodePath: '300001/300012/300015',      lvl: 3, parentNo: '300012', partNo: '300015' },
  { nodePath: '300001/300013/991',         lvl: 3, parentNo: '300013', partNo: '991' },
  { nodePath: '300001/300012/300015/992',  lvl: 4, parentNo: '300015', partNo: '992' },
];
export const BASELINE_ROW_COUNT = 7;

/**
 * AC-6：`300001` 由 3 切到 2 后的期望树。
 * 依据 = 视图里 300001 v2 的组成 `{300012, 300013, 300015}`（问题说明 §④ E-3 实证：v2 **不含 300014**）
 * 叠加各子件自己那张 current 清单（300012→300015→992 / 300013→991 / 300015→992）。
 */
export const V2_TREE_PART_MULTISET = ['300001', '300012', '300013', '300015', '300015', '991', '992', '992'];
/** AC-6 的核心可观测断言：`300014` **消失**。 */
export const V2_DISAPPEARING_PART = '300014';

// ─────────────────────────── 3. SQL 只读通道（唯一通道）───────────────────────────

function psql(args: string[], sql: string): string {
  return execFileSync(
    'psql',
    ['-h', DB.host, '-p', DB.port, '-U', DB.user, '-d', DB.db, '-X', '-P', 'pager=off', ...args, '-c', sql],
    { env: { ...process.env, PGPASSWORD: DB.password }, encoding: 'utf-8', maxBuffer: 32 * 1024 * 1024 },
  );
}

/**
 * 🚨 硬闸：只允许 SELECT / WITH / EXPLAIN。
 * 本片**没有写通道** —— 需要改库的动作一律走产品自己的 REST 端点（version-switch），
 * 这样"造数"是一次正常业务操作而不是裸 SQL 写入，且天然受产品的状态校验保护。
 */
export function sqlRead(sql: string): string {
  const t = sql.trim().replace(/;$/, '');
  if (!/^(SELECT|WITH|EXPLAIN)\b/i.test(t)) {
    throw new Error(
      `🚨 [R260910] sqlRead 只允许只读 SQL，拒绝执行：${t.slice(0, 160)}\n` +
      `（DELETE/UPDATE/TRUNCATE/DROP 属 CLAUDE.md §3.2 红线，测试无批准权 —— 停下来报主线，🚫 不许换写法重试）`,
    );
  }
  return psql(['-t', '-A'], t).trim();
}

export function sqlNum(sql: string): number {
  const v = sqlRead(sql);
  const n = Number(v === '' ? NaN : v);
  if (!Number.isFinite(n)) throw new Error(`[R260910] SQL 未返回数字：${JSON.stringify(v)}\nSQL: ${sql}`);
  return n;
}

/**
 * 只读多行多列。字段分隔用 \x1f，避免业务值里的 `|` 把列切错。
 *
 * 🚨 **不适用于含换行的列**（如 `sql_template` / jsonb 原文）：本函数按 `\n` 切行，
 *    多行值会被切成多条"记录"，且**不报错** —— 2026-09-10 实测把 1 条配置读成 60 条。
 *    ⇒ 这类列要么在 SQL 侧算成标量（md5 / length / 正则布尔），要么用 `sqlRead` 整段取回。
 */
export function sqlRows(sql: string): string[][] {
  const t = sql.trim().replace(/;$/, '');
  if (!/^(SELECT|WITH)\b/i.test(t)) throw new Error(`🚨 [R260910] sqlRows 只允许只读 SQL：${t.slice(0, 120)}`);
  const out = psql(['-t', '-A', '-F', '\x1f'], t).trim();
  return out === '' ? [] : out.split('\n').map((l) => l.split('\x1f'));
}

// ─────────────────────────── 4. 证据归档（🚫 不落 test-results）───────────────────────────

/**
 * 🚨 证据目录 = 任务目录，**不是** `test-results/`。
 * testing.md 判据：「下一轮跑测试会不会把它删掉？会 → 不算证据。」
 * Playwright 每轮开跑会清 `test-results/` ⇒ 留在那儿的截图不算验收证据。
 */
export function evidenceDir(): string {
  const dir = path.resolve(
    path.dirname(__f),
    '../../dev-docs/task-260909-核价树骨架分档与轴口径统一/repair-260910-核价树版本切换查V6老表/证据/e2e-s1',
  );
  // 结构自检：路径层数算错时截图会被静默写到别处（不报错，最难发现）
  const repoRoot = path.resolve(path.dirname(__f), '../..');
  for (const marker of ['cpq-frontend', 'cpq-backend', 'dev-docs']) {
    expect(fs.existsSync(path.join(repoRoot, marker)),
      `证据目录落点自检失败：推导出的仓库根 ${repoRoot} 下没有 ${marker}/`).toBe(true);
  }
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

let shotIdx = 0;
export async function shot(page: Page, name: string): Promise<string> {
  const file = path.join(evidenceDir(), `${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`[R260910][证据] ${name} → ${file}`);
  return file;
}

export function saveEvidence(name: string, content: string): string {
  const file = path.join(evidenceDir(), `${name}.txt`);
  fs.writeFileSync(file, content, 'utf-8');
  console.log(`[R260910][证据] ${name} → ${file}`);
  return file;
}

/** 读改动前基线文件（由 `e2e/r260910-baseline.sh` 在主线改配置**之前**采集）。 */
export const BASELINE_FILE = path.join(
  path.resolve(path.dirname(__f), '../../dev-docs/task-260909-核价树骨架分档与轴口径统一/repair-260910-核价树版本切换查V6老表/证据/e2e-s1'),
  '基线-改动前.txt');

export function readBaseline(): string {
  expect(fs.existsSync(BASELINE_FILE),
    `🚨 找不到改动前基线 ${BASELINE_FILE}\n` +
    `   AC-12/AC-13/AC-14 是 before/after 型断言，before 侧一旦被配置改动覆盖就取不回来。\n` +
    `   ⇒ 这不是产品缺陷，是取证前置缺失。先跑 \`bash e2e/r260910-baseline.sh\`（改配置**之前**）。`,
  ).toBe(true);
  return fs.readFileSync(BASELINE_FILE, 'utf-8');
}

/** 从基线文件里挑一段（以 `## [Bn]` 开头到下一个 `## [` 之前）。 */
export function baselineSection(tag: string): string {
  const txt = readBaseline();
  const i = txt.indexOf(`## [${tag}]`);
  expect(i, `基线文件里找不到分节 [${tag}]（基线文件可能是旧版本，请重采）`).toBeGreaterThanOrEqual(0);
  const rest = txt.slice(i);
  const j = rest.indexOf('\n## [', 1);
  return j < 0 ? rest : rest.slice(0, j);
}

// ─────────────────────────── 5. 接口通道 ───────────────────────────

/**
 * 建一个已登录的 APIRequestContext。
 * 🚨 **鉴权是 Cookie 不是 Bearer**：`POST /auth/login` 响应体里没有 token，身份在
 *    `Set-Cookie: CPQ_SESSION=…`。⇒ 只判 login 成功，后续靠 context 自带 cookie jar；
 *    🚫 不去响应体里挖 token（那会在"登录 200"时抛"取不到 token"，看着像鉴权契约变了）。
 */
export async function apiContext(
  baseURL = BACKEND_URL, username = 'admin', password = 'Admin@2026',
): Promise<APIRequestContext> {
  const ctx = await request.newContext({ baseURL, ignoreHTTPSErrors: true });
  const res = await ctx.post('/api/cpq/auth/login', { data: { username, password } });
  expect(res.ok(), `登录 ${baseURL} 失败（${username}）：${res.status()} ${await res.text()}`).toBe(true);
  return ctx;
}

export function safeJson(text: string): any { try { return JSON.parse(text); } catch { return null; } }

export function messageOf(text: string): string {
  const j = safeJson(text);
  if (!j) return text;
  return String(j.message ?? j.error ?? j.detail ?? j.data?.message ?? text);
}

export type VersionOptions = {
  status: number; raw: string;
  componentId?: string; partNo?: string;
  currentVersion?: string | null; options?: string[];
};

/** `GET /costing-orders/{coid}/version-options`（api.md：参数与响应形状**均不变**）。 */
export async function getVersionOptions(
  api: APIRequestContext, partNo: string,
  opts: { componentId?: string; coid?: string; lineItemId?: string } = {},
): Promise<VersionOptions> {
  const cid = opts.componentId ?? CID_TREE;
  const coid = opts.coid ?? COID;
  const li = opts.lineItemId ?? LIID;
  const res = await api.get(
    `/api/cpq/costing-orders/${coid}/version-options?lineItemId=${li}&componentId=${cid}&partNo=${encodeURIComponent(partNo)}`);
  const raw = await res.text();
  const j = safeJson(raw);
  const d = j?.data ?? {};
  const out: VersionOptions = {
    status: res.status(), raw,
    componentId: d.componentId, partNo: d.partNo,
    currentVersion: d.currentVersion ?? null,
    options: Array.isArray(d.options) ? d.options.map(String) : undefined,
  };
  console.log(`[R260910][api] version-options partNo=${partNo} cid=${cid} → HTTP ${out.status} ${raw}`);
  return out;
}

/**
 * `POST /costing-orders/{coid}/version-switch`。
 *
 * ⚠️ **请求体形状未从文档拿到**（`api.md` 只写「请求/响应 **不变**」，没给字段名；
 *    🚫 不许翻实现补齐 —— 派工书 §c）。⇒ 这里按候选形状**逐个试**，并把命中的形状
 *    缓存下来给后续调用用；命中过程全部打印，形状确定后主线可用
 *    `R260910_SWITCH_SHAPE=A|B|C` 钉死，去掉试探。
 *
 * 🚨 之所以能这么写而不算"猜"：AC-8 的 400 断言前有**阳性对照**（同一形状先在非叶子
 *    料号上拿到 2xx）—— 形状若是错的，阳性对照会先红，不会把"body 写错"误读成"叶子守卫生效"。
 */
/**
 * `POST /costing-orders/{coid}/version-switch` 的请求体。
 *
 * 🚨 契约由**主线读实现后下发**（2026-09-10），已去掉原先的 A/B/C 三候选试探：
 * ```java
 * public class VersionSwitchRequest {
 *     public UUID   lineItemId;
 *     public UUID   componentId;
 *     public String partNo;
 *     public String viewVersion;   // ← 不是 version / targetVersion
 * }
 * ```
 * 形状虽已钉死，`s1e` 的**阳性对照仍然保留** —— 它现在的职责变成「证明这一轮切换链路
 * 整体是通的」，从而让 AC-8 的 400 能被归因到叶子守卫而不是环境/鉴权/单据状态。
 */
export type SwitchShape = 'CONTRACT';
export function currentSwitchShape(): SwitchShape | null { return 'CONTRACT'; }

export async function switchVersion(
  api: APIRequestContext, partNo: string, version: string,
  opts: { componentId?: string; coid?: string; lineItemId?: string; shape?: SwitchShape } = {},
): Promise<{ status: number; raw: string; shape: SwitchShape }> {
  const cid = opts.componentId ?? CID_TREE;
  const coid = opts.coid ?? COID;
  const li = opts.lineItemId ?? LIID;
  const body = { lineItemId: li, componentId: cid, partNo, viewVersion: version };
  const res = await api.post(`/api/cpq/costing-orders/${coid}/version-switch`, { data: body });
  const raw = await res.text();
  console.log(`[R260910][api] version-switch body=${JSON.stringify(body)} → HTTP ${res.status()} ${raw.slice(0, 300)}`);
  return { status: res.status(), raw, shape: 'CONTRACT' };
}

// ─────────────────────────── 6. 库侧读取（树 / override）───────────────────────────

export type TreeRow = { nodePath: string; lvl: string; parentNo: string; bomVersion: string | null; partNo: string };

/** 从 `costing_order.costing_render` 读某行项 BOM 页签的树（保持渲染顺序）。 */
export function readTreeFromDb(coid = COID, liid = LIID, tabName = TAB_TREE): TreeRow[] {
  const rows = sqlRows(`
    SELECT r->>'__nodeId', r->>'__lvl', COALESCE(r->>'__parentNo',''),
           COALESCE(r->>'__bomVersion','(nullver)'), COALESCE(r->>'__hfPartNo','')
    FROM costing_order co, jsonb_each(co.costing_render) li,
         jsonb_array_elements((li.value->>'costingCardValues')::jsonb->'tabs') tab,
         jsonb_array_elements(COALESCE(tab->'baseRows','[]'::jsonb)) WITH ORDINALITY t(r,ord)
    WHERE co.id='${coid}' AND li.key='${liid}' AND tab->>'tabName'='${tabName}'
    ORDER BY ord`);
  const out = rows.map(([nodePath, lvl, parentNo, v, partNo]) => ({
    nodePath, lvl, parentNo, bomVersion: v === '(nullver)' ? null : v, partNo,
  }));
  console.log(`[R260910][db] 树(${coid.slice(0, 8)}/${liid.slice(0, 8)}/${tabName}) 共 ${out.length} 行：`);
  out.forEach((r, i) => console.log(
    `[R260910][db]   #${i} path=${r.nodePath} lvl=${r.lvl} parent=${r.parentNo || '(root)'} ver=${r.bomVersion ?? '(null)'}`));
  return out;
}

export function overrideRows(coid = COID): { componentId: string; partNo: string; viewVersion: string }[] {
  const rows = sqlRows(
    `SELECT component_id, part_no, view_version FROM costing_order_version_override WHERE costing_order_id='${coid}' ORDER BY part_no`);
  return rows.map(([componentId, partNo, viewVersion]) => ({ componentId, partNo, viewVersion }));
}

export function overrideTotalRows(): number {
  return sqlNum('SELECT count(*) FROM costing_order_version_override');
}

export function renderMd5(coid = COID): string {
  return sqlRead(`SELECT md5(COALESCE(costing_render::text,'(null)')) FROM costing_order WHERE id='${coid}'`);
}

export function costingTotals(coid = COID): { renderMd5: string; costingTotal: string; status: string } {
  const [r] = sqlRows(
    `SELECT md5(COALESCE(costing_render::text,'(null)')), COALESCE(costing_total_amount::text,'(null)'), status ` +
    `FROM costing_order WHERE id='${coid}'`);
  expect(r, `库里找不到 costing_order ${coid}`).toBeTruthy();
  return { renderMd5: r[0], costingTotal: r[1], status: r[2] };
}

/** 取一张**非 PENDING** 的核价单（AC-9 / AC-12 用；🚦 只读，🚫 不改它的状态）。 */
export function pickFrozenOrder(): { id: string; no: string; status: string; renderMd5: string; bomRows: number } {
  const rows = sqlRows(`
    SELECT co.id, co.costing_order_number, co.status, md5(COALESCE(co.costing_render::text,'(null)')),
           (SELECT count(*) FROM jsonb_each(COALESCE(co.costing_render,'{}'::jsonb)) li,
                   jsonb_array_elements((li.value->>'costingCardValues')::jsonb->'tabs') tb,
                   jsonb_array_elements(COALESCE(tb->'baseRows','[]'::jsonb)) rr
             WHERE tb->>'tabName'='${TAB_TREE}')::text
    FROM costing_order co
    WHERE co.status IN ('APPROVED','REJECTED','WITHDRAWN')
    -- 🚨 优先选**含 BOM 树行**的冻结单：树行数为 0 的单据上，AC-9/AC-12 的断言证据强度极弱
    --    （只证明"没被顺手重算"，证明不了"冻结的树不受影响"）。
    ORDER BY (SELECT count(*) FROM jsonb_each(COALESCE(co.costing_render,'{}'::jsonb)) li2,
                     jsonb_array_elements((li2.value->>'costingCardValues')::jsonb->'tabs') tb2,
                     jsonb_array_elements(COALESCE(tb2->'baseRows','[]'::jsonb)) rr2
               WHERE tb2->>'tabName'='${TAB_TREE}') DESC,
             length(COALESCE(co.costing_render::text,'')) DESC
    LIMIT 1`);
  expect(rows.length, '库里找不到任何 APPROVED/REJECTED/WITHDRAWN 核价单（AC-9/AC-12 无前置）').toBe(1);
  const [id, no, status, md5v, bomRows] = rows[0];
  return { id, no, status, renderMd5: md5v, bomRows: Number(bomRows) };
}

// ─────────────────────────── 7. 环境正身（防假红 #3，test.md §1）───────────────────────────

/**
 * 生效的 COST_BASIC 骨架配置（AC-15 还原点 / 配置改动是否落地的独立判据）。
 *
 * 🚨 **不要把 `sql_template` 原文经 `sqlRows` 取回来** —— 它是多行文本，`sqlRows` 按 `\n`
 *    切行会把 1 条记录切成几十"行"，症状是"生效配置有 60 条"这种荒谬数字
 *    （2026-09-10 自查实测）。⇒ 需要看内容的判断一律在 **SQL 侧**算成单行标量。
 */
export function activeCostBasicConfig(): {
  id: string; name: string; md5: string; len: number; recursiveUsesOwnList: boolean;
} {
  const rows = sqlRows(
    `SELECT id, name, md5(sql_template), length(sql_template)::text, ` +
    `(sql_template ~ 'sv\\.production_no[[:space:]]*=[[:space:]]*ch\\.component_no')::text ` +
    `FROM costing_bom_tree_config WHERE usage='COST_BASIC' AND is_active`);
  expect(rows.length,
    `🚨 前置不满足：usage='COST_BASIC' 的生效配置不是恰好 1 条（实得 ${rows.length} 条）。\n` +
    `   ⇒ 这不是产品缺陷，是配置前置缺失/冲突，报主线。`).toBe(1);
  const [id, name, md5v, len, uses] = rows[0];
  // 🚨 `boolean::text` 返回 'true'/'false'，**不是** psql 裸 boolean 列的 't'/'f'。
  //    写成 === 't' 会恒 false —— 又一个"不报错的静默错值"（2026-09-10 自查实测）。
  return { id, name, md5: md5v, len: Number(len), recursiveUsesOwnList: uses === 'true' };
}

/**
 * 🚨 **更正（2026-09-10 执行期实测）**：这个 md5 **不是"改动前"值，而是"改动 1 落地后"的值**。
 *
 * 时间线实证：
 *   · `costing_bom_tree_config` 的 COST_BASIC 行 `updated_at = 2026-09-10 10:56:56Z`（改动 1 落库）
 *   · 我的基线脚本跑在 `11:0x` ⇒ **[B1] 的 COST_BASIC 那行采到的已经是改动后的原文**
 *   · 佐证：该模板递归体第 32 行现为 `WHERE sv.production_no = ch.component_no`（改动 1 的目标写法）
 *
 * ⚠️ 但基线文件的**其余分节仍是有效的 before 侧**：配置虽在 10:56 改了，`costing_render`
 *    当时**尚未重算**（[B2] 记的版本列是旧值 `3/3/3/3/1/1/1`）⇒ [B2]~[B6] 可正常用于 AC-12/13/14。
 *    唯一失效的是 [B1] 里 COST_BASIC 这一行的"before"身份；QUOTE 那行 `updated_at=09-09 17:59`，
 *    早于本次一切动作，**是有效 before 侧**，AC-14 不受影响。
 */
export const POSTCHANGE_COST_BASIC_MD5 = '0b69d87d5c0a2755ac0db2c64a5bad8c';
/** 改动前 QUOTE 骨架 md5（AC-14：本次**一字节未动**）。 */
export const BASELINE_QUOTE_MD5 = '1c089ee61054d18b9151c8f03d23bacb';

/**
 * 🚨 跑任何断言前先确认「我打的是哪个栈、UI 和我的 API 用例打的是不是同一个后端」。
 *
 * test.md §1 的原文要求：**UI 走哪条链路就验哪条链路** —— 若起临时栈，必须经**前端代理**
 * 调一次 `version-options` 确认打到的是本分支后端，🚫 直连后端不算数。
 *
 * 实现：同一组参数，① 直连 BACKEND_URL ② 经 BASE_URL 的 `/api` 代理，两者响应体**逐字相同**才放行。
 * 不同 ⇒ 前端代理指向了另一个后端，本轮 UI 结论与 API 结论不可互相印证 —— 硬失败。
 */
export async function assertEnvIdentity(): Promise<string> {
  const lines: string[] = [];
  lines.push(`BASE_URL=${BASE_URL}  BACKEND_URL=${BACKEND_URL}  DB=${DB.host}/${DB.db}`);

  const direct = await apiContext(BACKEND_URL);
  const viaProxy = await apiContext(BASE_URL);
  try {
    const a = await getVersionOptions(direct, '300001');
    const b = await getVersionOptions(viaProxy, '300001');
    lines.push(`直连后端   : HTTP ${a.status} ${a.raw}`);
    lines.push(`经前端代理 : HTTP ${b.status} ${b.raw}`);
    expect(b.raw,
      `🚨 前端代理与直连后端返回不一致 ⇒ 5174/${BASE_URL} 的 /api 打到了**另一个后端**。\n` +
      `   本轮 UI 用例与 API 用例测的不是同一份代码，结论不可互相印证（test.md §1）。\n` +
      `   ⇒ 先修环境，🚫 不要按产品缺陷解读。\n   直连=${a.raw}\n   代理=${b.raw}`,
    ).toBe(a.raw);

    // ── 配置侧是否已改（独立于任何一条 AC 的判据）──
    const cfg = activeCostBasicConfig();
    // 🚨 判据是**内容**（递归体是否取"自己那张清单"），不是 md5 —— 我的基线晚于配置改动 5 分钟，
    //    md5 差异法会得出"配置没改"的错误结论（2026-09-10 首跑实测踩到）。
    const cfgChanged = cfg.recursiveUsesOwnList;
    lines.push(`生效 COST_BASIC 配置: ${cfg.name} md5=${cfg.md5} len=${cfg.len}` +
      `  递归体已取"自己那张清单" = ${cfgChanged}${cfgChanged ? '' : '（⚠️ 改动 1 未落地）'}`);

    // ── 代码侧是否已改（**只打横幅、不断言**）──
    //    不敢拿它当前置：它就是 AC-3 本身，当前置会让 AC-3 变成"自己证明自己"。
    const codeLooksNew = (a.options?.length ?? 0) > 0;
    lines.push(`树分支候选(300001) 非空 = ${codeLooksNew}` +
      `（改动后应为 true；若 false 且 AC-3~AC-5 全红，**先怀疑打到了未改动的后端**再怀疑产品）`);
    if (!codeLooksNew || !cfgChanged) {
      console.warn(
        '\n' + '='.repeat(78) + '\n' +
        '⚠️  [R260910] 疑似打在**改动前**的环境上：\n' +
        `    · 配置改动落地 = ${cfgChanged}\n` +
        `    · 树分支候选非空 = ${codeLooksNew}\n` +
        '    两者有一为 false 时，本轮的红**不一定是产品缺陷**（可能是环境）。\n' +
        '    ⇒ 请先与主线核对：后端跑的是不是本 worktree 的分支代码、配置改动是否已执行。\n' +
        '='.repeat(78) + '\n');
    }
  } finally {
    await direct.dispose(); await viaProxy.dispose();
  }

  const banner = `[R260910] 环境正身\n${lines.join('\n')}`;
  console.log(banner);
  saveEvidence('00-环境正身', banner);
  return banner;
}

/**
 * 前置数据漂移体检（**共享库纪律**）。
 * 🚨 目的是把「前置数据被别人改了」与「产品坏了」分开 —— 两者症状一样，处理动作完全相反。
 * 失败信息里明确写「这不是产品缺陷」。
 */
export function assertFixtureIntegrity(): string {
  const lines: string[] = [];
  const viewRows = sqlRows(`
    SELECT production_no,
           string_agg(DISTINCT version_no::text, ',' ORDER BY version_no::text DESC),
           COALESCE(max(version_no) FILTER (WHERE is_current)::text,'(none)')
    FROM v_ds_cost_basic_material_bom_all
    WHERE production_no IN ('300001','300012','300013','300014','300015','991','992')
    GROUP BY production_no ORDER BY production_no`);
  const byPart = new Map(viewRows.map(([p, vs, cur]) => [p, { vs, cur }]));
  for (const p of Object.keys(EXPECTED_OPTIONS)) {
    const got = byPart.get(p);
    lines.push(`  ${p.padEnd(7)} 视图版本=${got ? got.vs : '(无记录=叶子)'} 当前=${got ? got.cur : '—'}` +
      `  | AC 期望候选=${JSON.stringify(EXPECTED_OPTIONS[p])} 当前=${EXPECTED_CURRENT_VERSION[p] ?? '—'}`);
  }
  const drift: string[] = [];
  for (const p of NON_LEAF_PARTS) {
    const got = byPart.get(p);
    if (!got) { drift.push(`${p} 在视图里查不到（AC 认为它是非叶子）`); continue; }
    const vs = got.vs.split(',');
    if (JSON.stringify(vs) !== JSON.stringify(EXPECTED_OPTIONS[p])) drift.push(`${p} 视图版本=${got.vs}，AC 期望=${EXPECTED_OPTIONS[p].join(',')}`);
    if (got.cur !== EXPECTED_CURRENT_VERSION[p]) drift.push(`${p} 视图 current=${got.cur}，AC 期望=${EXPECTED_CURRENT_VERSION[p]}`);
  }
  for (const p of LEAF_PARTS) {
    if (byPart.has(p)) drift.push(`${p} 在视图里作为 production_no **查得到**，AC 却把它当叶子`);
  }
  const banner = `[R260910] 前置数据体检\n${lines.join('\n')}` +
    (drift.length ? `\n漂移项：\n  - ${drift.join('\n  - ')}` : '\n漂移项：无');
  console.log(banner);
  saveEvidence('00-前置数据体检', banner);
  expect(drift,
    `🚨 **前置数据已漂移，不是产品缺陷** —— AC 原文钉死的期望值与库里现状对不上。\n` +
    `   共享库上别人改了 ds_cost_basic_material_bom(_history)。\n` +
    `   ⇒ 停下来报主线（要么恢复数据，要么由用户重新裁定 AC 期望值），🚫 不许就地把期望值改成"现在是什么"。\n${banner}`,
  ).toEqual([]);
  return banner;
}

// ─────────────────────────── 8. 断言工具（防假绿 #1）───────────────────────────

/**
 * 🚨 候选列表断言的统一入口。
 * 先证明「拿到了一个**非空**的候选集合」，再比内容 —— 否则接口返 `options: []`（正是本次要修的 bug）
 * 或下拉没渲染出来时，`toEqual([])` 会**恒真**。
 */
export function assertOptionsExactly(actual: string[] | undefined, expected: string[], label: string) {
  expect(actual, `${label}：响应里没有 options 数组（形状变了？）`).toBeDefined();
  const a = [...(actual as string[])].map(String);
  console.log(`[R260910][assert] ${label} 实际候选=${JSON.stringify(a)} 期望=${JSON.stringify(expected)}`);
  if (expected.length > 0) {
    // ① 先断言非空 —— 空候选恰是本次待修 bug 的样子，必须单独报出来而不是混在"内容不等"里
    expect(a.length,
      `${label}：候选列表**为空** —— 这正是 repair-260910 待修的症状（树分支查 V6 老表 ⇒ 恒空）。\n` +
      `   期望 ${JSON.stringify(expected)}`).toBeGreaterThan(0);
  }
  // ② 再比内容（顺序不作为断言维度：AC 原文说的是"恰好 [3,2,1]"这个集合）
  expect([...a].sort(), `${label}：候选集合不等（实际 ${JSON.stringify(a)} / 期望 ${JSON.stringify(expected)}）`)
    .toEqual([...expected].sort());
}

// ─────────────────────────── 9. UI（选择器坑一律 dump 再硬失败）───────────────────────────

/**
 * 四个已知 Playwright 坑（cpq-playwright-selector-pitfalls），**都表现为 timeout**：
 *  1. 核价卡片页签是 `button.qt-tab-btn`，🚫 不是 `.ant-tabs-tab`
 *  2. antd 两字按钮渲染成「保 存」（中间有空格）⇒ 一律用 /保\s*存/
 *  3. 下拉虚拟滚动，选项先 scrollIntoViewIfNeeded
 *  4. antd v6：`.ant-select-selection-item` → `.ant-select-content-value`；
 *     按 v5 类名写会**匹配 0 个且不报错**
 */
export async function dumpCandidates(page: Page, label: string, selectors: string[]): Promise<string> {
  const out: string[] = [`[R260910][dump] ${label}`];
  for (const sel of selectors) {
    const texts = await page.locator(sel).allInnerTexts().catch(() => [] as string[]);
    out.push(`  ${sel} → ${texts.length} 个：${JSON.stringify(texts.map((t) => t.trim().slice(0, 40)))}`);
  }
  const msg = out.join('\n');
  console.log(msg);
  return msg;
}

export async function uiLogin(page: Page, username = 'admin', password = 'Admin@2026') {
  await page.context().clearCookies();
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill(username);
  await page.locator('input[placeholder="密码"]').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|components|change-password)/, { timeout: 20_000 });
  if (page.url().includes('/change-password')) { await page.goto('/dashboard'); await page.waitForLoadState('networkidle'); }
  expect(page.url(), `UI 登录后仍停在 /login（${username}）`).not.toContain('/login');
}

/**
 * 从**列表页**进入核价单卡片视图。
 * 🚨 🚫 不直接 `goto('/quotations/{id}/edit')` —— 主线实测那样进去得 0 张卡片
 *    （症状是"卡片定位不到"，长得和产品缺陷一模一样）。
 * 🚨 搜索框 fill 之后**必须按 Enter**，否则不过滤，会点进列表第一行的**另一张单**。
 */
export async function enterCostingCard(page: Page, quotationNo = QUOTATION_NO): Promise<void> {
  await page.goto('/quotations');
  await page.waitForLoadState('networkidle');
  const box = page.locator('input[placeholder*="搜索报价单号"]');
  await box.waitFor({ state: 'visible', timeout: 20_000 });
  await box.fill(quotationNo);
  await box.press('Enter');                       // ← 少这一下就不过滤
  await page.waitForTimeout(3000);

  const rows = page.locator('tbody tr');
  const n = await rows.count();
  const firstText = n > 0 ? (await rows.first().innerText().catch(() => '')).replace(/\s+/g, ' ') : '(无行)';
  console.log(`[R260910][ui] 列表命中 ${n} 行，首行=${firstText}`);
  expect(n, `列表里搜不到报价单 ${quotationNo}（搜索框按了 Enter 吗？）`).toBeGreaterThan(0);
  expect(firstText, `列表首行不是 ${quotationNo} —— 会点进别的单，断言全部错位`).toContain(quotationNo);

  await rows.first().locator('a').first().click();
  await page.waitForTimeout(4000);

  const editBtn = page.locator('button').filter({ hasText: /^\s*编\s*辑\s*$/ }).first();
  if (await editBtn.count() === 0 || !(await editBtn.isVisible().catch(() => false))) {
    const dump = await dumpCandidates(page, '详情页找不到「编辑」按钮', ['button', '.ant-btn']);
    await shot(page, 'ERR-详情页无编辑按钮');
    throw new Error(
      `🚨 ${quotationNo} 详情页没有「编辑」按钮（单据状态可能不允许编辑）。\n${dump}\n` +
      `   ⇒ 这是**取证路径**问题不是产品缺陷：报主线要一条能进核价卡片的路径`);
  }
  await editBtn.click();
  await page.waitForTimeout(5000);

  const next = page.locator('button').filter({ hasText: /下\s*一\s*步/ }).first();
  if (await next.count() > 0 && await next.isEnabled().catch(() => false)) {
    await next.click(); await page.waitForTimeout(9000);
  }

  // ⚠️ segmented 文案带 emoji 前缀（实测 ["📝 报价单","📊 核价单",…]）⇒ 锚定 /^核价单$/ 永远不命中
  const costing = page.locator('.ant-segmented-item, .ant-radio-button-wrapper, button, [role=tab]')
    .filter({ hasText: /核价单/ }).first();
  if (await costing.count() === 0) {
    const dump = await dumpCandidates(page, '找不到「核价单」切换项', ['.ant-segmented-item', '.ant-tabs-tab', 'button']);
    await shot(page, 'ERR-找不到核价单切换项');
    throw new Error(`🚨 进不了核价单视图（${quotationNo}）。\n${dump}`);
  }
  await costing.click();
  await page.waitForTimeout(9000);
}

/**
 * 进**核价工作台** `/costing-orders/{coid}/review`。
 *
 * 🚨 这是全项目**唯一**渲染可交互 `VersionSelectDropdown` 的视图（主线 2026-09-10 下发，
 *    前端工程师静态 + 真机双重确证）。三个视图的形态差异是本任务最大的取证陷阱：
 *
 *   | 视图 | 版本列形态 |
 *   |---|---|
 *   | 核价工作台 `/costing-orders/{coid}/review` | **可交互 antd 下拉**（本函数的目标） |
 *   | 报价单编辑页 Step2 → 核价单 | **原生 `<select disabled>` 只读壳**（AC-16 的地盘） |
 *   | 报价单详情页 | 纯文本，零下拉 |
 *
 * ⚠️ 且该单必须是 `PENDING` —— 非 PENDING 时下拉不该出现（与 AC-9 同源）。
 * 🚫 在编辑页 Step2 上验 AC-3/AC-4 会得出"候选交互不存在"的错误结论（本片首跑实测踩到）。
 */
export async function enterCostingReviewPage(page: Page, coid = COID): Promise<void> {
  await page.goto(`/costing-orders/${coid}/review`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(4000);
  const cards = page.locator('.qt-product-card');
  // 卡片可能要等渲染；给一次显式等待再 dump，避免把"还没渲染完"读成"页面不对"
  await cards.first().waitFor({ state: 'visible', timeout: 60_000 }).catch(() => {});
  if (await cards.count() === 0) {
    const dump = await dumpCandidates(page, `核价工作台 ${coid} 没有产品卡片`,
      ['.qt-product-card', '.ant-tabs-tab', 'button.qt-tab-btn', '.ant-empty', '.ant-result-title']);
    await shot(page, 'ERR-核价工作台无卡片');
    throw new Error(
      `🚨 /costing-orders/${coid}/review 上 0 张产品卡片。\n${dump}\n` +
      `   ⚠️ 先怀疑取证前置（单据状态 / 路由 / 权限），再怀疑产品。当前 URL=${page.url()}`);
  }
  console.log(`[R260910][ui] 核价工作台加载完成，产品卡片 ${await cards.count()} 张，URL=${page.url()}`);

  // 🚨 页面**默认停在「报价单」分段**，那一侧的 BOM 页签根本没有「版本」列
  //    （实测表头 = BOM|销售料号|项次|料号|材料名|组成数量|材料毛重，树是 S0001/S0002 销售料号）。
  //    漏这一步的症状是"找不到版本壳"，长得和产品缺陷一模一样（本片首跑实测踩到）。
  const seg = page.locator('.ant-segmented-item, .ant-radio-button-wrapper, [role=tab], button')
    .filter({ hasText: /核价单/ }).first();
  if (await seg.count() === 0) {
    const dump = await dumpCandidates(page, '核价工作台找不到「核价单」分段',
      ['.ant-segmented-item', '.ant-radio-button-wrapper', '[role=tab]']);
    await shot(page, 'ERR-工作台无核价单分段');
    throw new Error(`🚨 核价工作台上定位不到「核价单」分段。\n${dump}`);
  }
  await seg.click();
  await page.waitForTimeout(6000);
  console.log('[R260910][ui] 已切到「核价单」分段');
}

/** 取指定料号的那张产品卡片；必须唯一命中，否则硬失败（防"读错卡片"型假绿/假红）。 */
export async function cardOf(page: Page, part: string): Promise<Locator> {
  const cards = page.locator('.qt-product-card');
  const total = await cards.count();
  const hit = cards.filter({ hasText: part });
  const n = await hit.count();
  if (n !== 1) {
    const titles: string[] = [];
    for (let i = 0; i < total; i++) {
      titles.push((await cards.nth(i).innerText().catch(() => '')).split('\n').slice(0, 2).join(' / ').trim());
    }
    await shot(page, `ERR-卡片定位-${part}`);
    throw new Error(`🚨 料号 ${part} 的产品卡片命中 ${n} 张（期望恰好 1）。总卡片数=${total}\n` +
      `   各卡片首两行：\n${titles.map((t, i) => `     #${i} ${t}`).join('\n')}`);
  }
  await hit.first().scrollIntoViewIfNeeded();
  await page.waitForTimeout(500);
  return hit.first();
}

/** 在卡片内切页签。🚨 页签是 `button.qt-tab-btn`，不是 `.ant-tabs-tab`。 */
export async function switchTabInCard(card: Locator, tabName: string): Promise<void> {
  const page = card.page();
  const btn = card.locator('button.qt-tab-btn').filter({ hasText: new RegExp(`^\\s*${tabName}\\s*$`) }).first();
  if (!(await btn.isVisible().catch(() => false))) {
    const tabs = await card.locator('button.qt-tab-btn').allInnerTexts().catch(() => [] as string[]);
    await shot(page, `ERR-卡片页签-${tabName}`);
    throw new Error(`🚨 卡片内找不到页签「${tabName}」。实际页签=${JSON.stringify(tabs.map((t) => t.trim()))}`);
  }
  await btn.click();
  await page.waitForTimeout(3000);
}

export type UiTreeRow = {
  partNo: string;          // 第 1 列（BOM 列）文本，去掉 ▼/▶ 与缩进
  versionText: string;     // 「版本」列的可见文本（下拉选中值 或 纯文本占位）
  hasSelect: boolean;      // 「版本」列里有没有**任何形态的版本控件壳**（AC-2 / AC-16 的核心可观测量）
  antdSelect: number;      // 其中 antd `.ant-select` 的个数
  nativeSelect: number;    // 其中原生 `<select>` 的个数
  nativeSelectDisabled: number; // 其中原生 `<select disabled>`（AC-16 说的「只读壳」）的个数
  rowText: string;
};

/**
 * 读核价卡片里 BOM 页签的每一行：料号 / 版本列文本 / 版本列有无下拉。
 *
 * 🚨 三个坑：
 *  ① 「版本」列的**列序不能写死** —— 从 thead 里按表头文字找，找不到就 dump 硬失败
 *  ② antd v6 选中值不在 `.ant-select-selection-item`（v5 类名）而在 `.ant-select-content-value`，
 *    ⇒ 这里两种都取，再退回整个 `.ant-select` 的 innerText；还取 `<input>` 的 value
 *    （`innerText` 读不到 input 的值）
 *  ③ 一个页签可能被拆成多张 `.qt-cost-table`（冻结列 + 滚动列）⇒ 按行索引把各表同一行拼起来
 */
export async function readUiTree(card: Locator): Promise<UiTreeRow[]> {
  const page = card.page();
  const out = await card.evaluate((root: any) => {
    const norm = (s: string) => (s || '').replace(/\s+/g, ' ').trim();
    const tables = Array.from(root.querySelectorAll('table')).filter((t: any) => t.offsetParent !== null) as any[];
    if (!tables.length) return { err: 'no-visible-table', headers: [] as string[], rows: [] as any[] };
    // 取行数最多的那张表当主表
    tables.sort((a: any, b: any) => b.querySelectorAll('tbody tr').length - a.querySelectorAll('tbody tr').length);
    const t = tables[0];
    const headers = Array.from(t.querySelectorAll('thead th')).map((th: any) => norm(th.textContent));
    const verIdx = headers.findIndex((h: string) => h === '版本' || /^版本/.test(h));
    const rows = Array.from(t.querySelectorAll('tbody tr')).map((tr: any) => {
      const tds = Array.from(tr.querySelectorAll('td')) as any[];
      const first = tds[0];
      const partNo = norm(first ? first.textContent : '').replace(/^[▼▶►▾\s]+/, '');
      const vtd = verIdx >= 0 ? tds[verIdx] : null;
      // 🚨 版本控件在本产品里**有两种形态**，只认一种会得出"整表都没渲染下拉"的错误结论：
      //    · antd `.ant-select`（可交互下拉）
      //    · 原生 `<select>`（AC-16 说的「只读壳」，既有 quotation-bom-tree.spec.ts 用的就是它）
      //    AC-2/AC-16 的判据是「叶子行**没有任何壳**」，所以这里两种都数。
      const antdSelect = vtd ? vtd.querySelectorAll('.ant-select').length : 0;
      const nativeSelect = vtd ? vtd.querySelectorAll('select').length : 0;
      const nativeSelectDisabled = vtd ? vtd.querySelectorAll('select[disabled]').length : 0;
      let versionText = '';
      if (vtd) {
        const v6 = vtd.querySelector('.ant-select-content-value');       // antd v6
        const v5 = vtd.querySelector('.ant-select-selection-item');      // antd v5
        const inp = vtd.querySelector('input');
        versionText = norm(
          (v6 && v6.textContent) || (v5 && v5.textContent) ||
          (inp && inp.value) || vtd.textContent || '');
      }
      return {
        partNo, versionText,
        hasSelect: antdSelect + nativeSelect > 0,
        antdSelect, nativeSelect, nativeSelectDisabled,
        rowText: norm(tr.textContent),
      };
    });
    return { err: '', headers, rows, verIdx };
  });

  if ((out as any).err || (out as any).verIdx < 0) {
    const dump = await dumpCandidates(page, 'BOM 表头/版本列定位失败',
      ['.qt-cost-table thead th', '.qt-product-card table thead th']);
    await shot(page, 'ERR-版本列定位失败');
    throw new Error(
      `🚨 定位不到「版本」列（err=${(out as any).err} headers=${JSON.stringify((out as any).headers)}）。\n${dump}\n` +
      `   ⚠️ 先怀疑选择器（4 个已知坑都表现为 timeout / 0 命中），再怀疑产品。`);
  }
  const rows = (out as any).rows as UiTreeRow[];
  console.log(`[R260910][ui] BOM 表头=${JSON.stringify((out as any).headers)}（版本列序=${(out as any).verIdx}）`);
  console.log(`[R260910][ui] BOM ${rows.length} 行：`);
  rows.forEach((r, i) => console.log(
    `[R260910][ui]   #${i} 料号=${r.partNo} 版本="${r.versionText}" 壳=${r.hasSelect}` +
    `(antd ${r.antdSelect} / native ${r.nativeSelect}，其中 disabled ${r.nativeSelectDisabled}) | ${r.rowText.slice(0, 80)}`));
  return rows;
}

/**
 * 展开某一行的版本下拉并读出选项文本。
 * 🚨 防假绿：先断言「下拉确实展开且渲染出了 option 节点」，再返回内容 ——
 *    下拉没打开时 `.ant-select-item-option` 恒 0 个，直接比 `toEqual([])` 会恒真。
 * 返回 `{ options, emptyText }`：`emptyText` 是 antd 的空态文案（本产品实测为「无可选版本」）。
 */
export async function openVersionDropdown(
  card: Locator, partNo: string,
): Promise<{ options: string[]; emptyText: string }> {
  const page = card.page();

  // 🚨 **不要用 `filter({ hasText: /…partNo…/ })` 定位行**（量具 bug #10，2026-09-10 实测）：
  //    行的 innerText 把相邻单元格**无分隔地拼在一起** —— 料号 300001 后面紧跟版本值 3，
  //    整行文本是 `▼3000013————…`，于是 `(^|[^0-9])300001([^0-9]|$)` 这种词边界正则**命中 0 行**，
  //    表现为"这一行没有任何版本控件壳"，长得和产品缺陷一模一样（而截图里下拉明明在）。
  // ⇒ 改为与 `readUiTree` **同一张表**（行数最多的可见表）+ **首列精确匹配**取行下标。
  const idx = await card.evaluate((root: any, p: string) => {
    const norm = (x: string) => (x || '').replace(/\s+/g, ' ').trim();
    const tables = Array.from(root.querySelectorAll('table')).filter((t: any) => t.offsetParent !== null) as any[];
    if (!tables.length) return -1;
    tables.sort((a: any, b: any) => b.querySelectorAll('tbody tr').length - a.querySelectorAll('tbody tr').length);
    const trs = Array.from(tables[0].querySelectorAll('tbody tr')) as any[];
    return trs.findIndex((tr: any) => {
      const td = tr.querySelector('td');
      return norm(td ? td.textContent : '').replace(/^[▼▶►▾\s]+/, '') === p;
    });
  }, partNo);

  if (idx < 0) {
    const dump = await dumpCandidates(page, `行定位失败：首列没有恰好等于 ${partNo} 的行`, ['table tbody tr td:first-child']);
    await shot(page, `ERR-行定位-${partNo}`);
    throw new Error(`🚨 表里找不到首列 = ${partNo} 的行。\n${dump}`);
  }

  const rows = card.locator('table:visible').first().locator('tbody tr');
  const row = rows.nth(idx);
  await row.scrollIntoViewIfNeeded().catch(() => {});

  const antd = row.locator('.ant-select');
  const native = row.locator('select');
  const nAntd = await antd.count();
  const nNative = await native.count();
  if (nAntd === 0 && nNative === 0) {
    await shot(page, `ERR-无版本壳-${partNo}`);
    throw new Error(`🚨 料号 ${partNo}（行下标 ${idx}）**没有任何版本控件壳**（antd 0 / native 0）—— 若它是非叶子，这就是 AC-2 的反向缺陷`);
  }
  if (nAntd === 0) {
    // 🚨 只有原生 <select>：这一视图渲染的是**只读壳**（AC-16 口径），点不开候选面板。
    //    ⇒ 这是"取证视图选错了"，不是"候选列表为空"。硬失败并说清区别，🚫 不许退化成读 <option> 就报绿。
    const disabled = await native.first().getAttribute('disabled');
    const opts = await native.first().locator('option').allInnerTexts().catch(() => [] as string[]);
    await shot(page, `ERR-只读壳-${partNo}`);
    throw new Error(
      `🚨 料号 ${partNo} 的版本列是**原生 <select>**（disabled=${disabled !== null}），不是可交互的 antd 下拉。\n` +
      `   其 <option> = ${JSON.stringify(opts)}\n` +
      `   ⇒ 当前视图渲染的是 AC-16 说的「只读壳」，**候选面板不在这里**。\n` +
      `      这是取证路径问题，不是"候选列表为空"的产品缺陷 —— 报主线要可交互下拉所在的视图。`);
  }
  await antd.first().click();
  await page.waitForTimeout(900);

  // antd 下拉挂在 body 上，不在 card 里 ⇒ 从 page 找，且只看可见的那一层
  const opts = page.locator('.ant-select-dropdown:visible .ant-select-item-option');
  const empty = page.locator('.ant-select-dropdown:visible .ant-empty, .ant-select-dropdown:visible .ant-select-item-empty');
  const n = await opts.count();
  const texts: string[] = [];
  for (let i = 0; i < n; i++) {
    await opts.nth(i).scrollIntoViewIfNeeded().catch(() => {});   // 虚拟滚动
    texts.push((await opts.nth(i).innerText().catch(() => '')).replace(/\s+/g, '').trim());
  }
  const emptyText = (await empty.count()) > 0
    ? (await empty.first().innerText().catch(() => '')).replace(/\s+/g, '').trim() : '';
  console.log(`[R260910][ui] ${partNo}（行 ${idx}）下拉：选项 ${n} 个 = ${JSON.stringify(texts)}；空态文案="${emptyText}"`);
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(400);
  return { options: texts, emptyText };
}
