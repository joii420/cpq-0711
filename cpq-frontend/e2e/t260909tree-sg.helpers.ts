/**
 * task-260909「核价树骨架分档与轴口径统一」· **S-全局片**（串行殿后）测试夹具
 *
 * ── 判据来源（🚫 全程未读实现源码）──────────────────────────────────────────
 *   dev-docs/task-260909-核价树骨架分档与轴口径统一/需求文档.md §③ AC 原文
 *   dev-docs/task-260909-核价树骨架分档与轴口径统一/api.md      §1 值域 / §2 角色 / §3 warnings / §4 取证口
 *   dev-docs/task-260909-核价树骨架分档与轴口径统一/test.md     §3 分片计划 / §4 四类假绿
 *   🚫 未读 cpq-backend/src/main/**、cpq-frontend/src/**、backtask.md、fronttask.md
 *
 * ── 本片认领的 6 条 AC ──────────────────────────────────────────────────────
 *   AC-3 / AC-4 / AC-5 / AC-16 / AC-19 / AC-20
 *
 * ── 🔒 写入面（test.md §3 已登记，逐项对照）─────────────────────────────────
 *   ① costing_bom_tree_config 的**生效配置**（AC-3/4/5/19）—— 全局单例
 *   ② template 的**发布态**（AC-16）—— 只在 `T260909-` 派生副本上做
 *   ③ QT-20260909-0661 的**卡片值快照**（AC-20 的「刷新基础数据」）
 *
 * ── 🚨 红线纪律（CLAUDE.md §3.2 / testing.md §4.3）───────────────────────────
 *   · 本文件的 SQL 通道分两条，且都带硬闸：
 *       `sqlRead`  —— 只允许 SELECT / WITH / EXPLAIN
 *       `sqlWrite` —— **只允许**「INSERT INTO template(_component)」与
 *                     「UPDATE template SET status='ARCHIVED'」，且语句必须命中
 *                     本片自建对象（`T260909-` 前缀或本次运行登记的 id）。
 *                     DROP / TRUNCATE / DELETE / 清库 一律抛错，**不许换写法重试**。
 *   · `costing_bom_tree_config` 的增删改**全部走产品自己的 REST 端点**（POST /
 *     PUT / activate / DELETE），不写一行裸 SQL —— 这样"清理"是一次正常产品操作，
 *     而不是一条数据库销毁命令。
 *   · 模板副本的回收用 **归档**（status='ARCHIVED'）而非 DELETE，见 §5 还原清单。
 *
 * ── 假绿防线（test.md §4）────────────────────────────────────────────────────
 *   1. **实例正身**：跑任何断言前先证明 8081 上跑的确实是含本次改动的代码
 *      （`assertShardPreconditions`）。探活返 401 只证明「有个 Quarkus 在跑」。
 *   2. **阳性对照**：AC-4 的 400 断言前，AC-3 已用**同一套 body 形状**拿到过 2xx
 *      ⇒ 400 不可能是"body 形状写错"造成的。
 *   3. **断言前先断言非空**：所有行数断言先打印实际行文本再比数字。
 *   4. **选择器坑与产品 bug 可区分**：每个定位失败都 dump 候选值再硬失败。
 */
import { expect, request, APIRequestContext, Page, Locator } from '@playwright/test';
import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const __f = fileURLToPath(import.meta.url);

// ─────────────────────────────── 0. 环境坐标 ───────────────────────────────

export const BASE_URL = process.env.PW_BASE_URL || 'http://localhost:5174';
export const BACKEND_URL = process.env.PW_BACKEND_URL || 'http://localhost:8081';

export const DB = {
  host: process.env.PW_DB_HOST || '10.177.152.12',
  port: process.env.PW_DB_PORT || '5432',
  user: process.env.PW_DB_USER || 'postgres',
  db: process.env.PW_DB || 'cpq_db_0724',
  password: process.env.PW_DB_PASS || 'joii5231',
};

/** 本片造数前缀 —— 跨片/跨会话防串扰的唯一依据（派工书 §d）。 */
export const PREFIX = 'T260909-';

/** SALES_MANAGER 账号（主线 2026-09-09 下发）——用于「角色收紧」这条**行为型**正身判据。 */
export const SMGR_USER = process.env.PW_USER_SMGR || '';
export const SMGR_PWD = process.env.PW_PWD_SMGR || '';

/**
 * 🚨 交付物（**不是本片造数**）——主线 2026-09-09 补配，退场必须把生效权交还给它。
 * 🚫 不许删除、不许改名、不许当成上一轮残留清掉。
 */
export const DELIVERED_COST_BASIC_ID = '537d2267-ccf2-4453-bc30-a9cee741e153';
export const DELIVERED_QUOTE_ID = 'd6defaa0-354f-4e92-8e89-4bc8454888c3';

/**
 * 🚨 本片必须跑在**临时栈**上（worktree 代码）。
 * 5174/8081 服务的是主工作区已合并代码，看不到本 worktree 的 B-1~B-10 ⇒ 拿它跑 = 假绿；
 * 而且本片会写全局生效配置，打共享栈等于污染主线亲验用的环境。
 */
export function assertIsolatedEnv() {
  if (process.env.SG_ALLOW_SHARED === '1') {
    console.warn('[SG] ⚠️ SG_ALLOW_SHARED=1：已放行共享栈，出问题请先怀疑这一条');
    return;
  }
  const bad = [/:8081(\/|$)/.test(BACKEND_URL), /:5174(\/|$)/.test(BASE_URL)];
  expect(bad.some(Boolean),
    `🚨 本片拒绝跑在共享 dev 栈上：BACKEND_URL=${BACKEND_URL} / BASE_URL=${BASE_URL}\n` +
    `   请用主线下发的临时栈：PW_BASE_URL=http://localhost:5212 PW_BACKEND_URL=http://localhost:8131`,
  ).toBe(false);
}

/** 统一测试单据（需求文档 §③ 前置）。 */
export const QUOTATION_NO = 'QT-20260909-0661';
export const QUOTATION_ID = '3c38bfb7-6af3-4f39-9c6d-f127e02d8712';

/** 取证对象模板（🚫 本片不动它本体，只派生副本）。 */
export const COSTING_TEMPLATE_NAME = '核价模板1';

/** AC-7 / AC-10 的行数常量（本片 AC-20 复用，🚫 禁止就地改数）。 */
export const AC7_BOM_ROWS = 7;
export const AC10_ELEMENT_ROWS = 4;

/** AC-16 两份副本的名字（AC 原文逐字）。 */
export const TPL_MIXED = `${PREFIX}混方言验证`;
export const TPL_SAME = `${PREFIX}同方言验证`;

// ─────────────────────────────── 1. SQL 通道 ───────────────────────────────

function psql(args: string[], sql: string): string {
  return execFileSync(
    'psql',
    ['-h', DB.host, '-p', DB.port, '-U', DB.user, '-d', DB.db, '-X', '-P', 'pager=off', ...args, '-c', sql],
    { env: { ...process.env, PGPASSWORD: DB.password }, encoding: 'utf-8', maxBuffer: 32 * 1024 * 1024 },
  );
}

/** 只读单值。🚨 硬闸：只允许 SELECT / WITH / EXPLAIN。 */
export function sqlRead(sql: string): string {
  const t = sql.trim().replace(/;$/, '');
  if (!/^(SELECT|WITH|EXPLAIN)\b/i.test(t)) {
    throw new Error(
      `🚨 [SG] sqlRead 只允许只读 SQL，拒绝执行：${t.slice(0, 120)}\n` +
      `（DELETE/UPDATE/TRUNCATE/DROP 属 CLAUDE.md §3.2 红线，测试无批准权）`,
    );
  }
  return psql(['-t', '-A'], t).trim();
}

export function sqlNum(sql: string): number {
  const v = sqlRead(sql);
  const n = Number(v === '' ? NaN : v);
  if (!Number.isFinite(n)) throw new Error(`[SG] SQL 未返回数字：${JSON.stringify(v)}\nSQL: ${sql}`);
  return n;
}

/** 只读多行多列（字段分隔用 \x1f，避免业务值里的 | 把列切错）。 */
export function sqlRows(sql: string): string[][] {
  const out = sqlRead(sql);
  if (out === '') return [];
  return out.split('\n').map((line) => line.split('\x1f'));
}
export function sqlRowsF(sql: string): string[][] {
  const t = sql.trim().replace(/;$/, '');
  if (!/^(SELECT|WITH)\b/i.test(t)) throw new Error(`🚨 [SG] sqlRowsF 只允许只读 SQL：${t.slice(0, 80)}`);
  const out = psql(['-t', '-A', '-F', '\x1f'], t).trim();
  return out === '' ? [] : out.split('\n').map((l) => l.split('\x1f'));
}

/**
 * 🚨 受限写通道。**只为 AC-16 的模板副本存在**，白名单之外一律抛错。
 *
 * 允许的两类：
 *   ① `INSERT INTO template …` / `INSERT INTO template_component …`
 *   ② `UPDATE template SET status='ARCHIVED' WHERE id IN (…)`  ← 副本回收
 * 且语句必须包含 `T260909-` 前缀或本次运行登记的 uuid，保证命中面 = 本片自建对象。
 *
 * 🚫 DROP / TRUNCATE / DELETE / ALTER / 无 WHERE 的 UPDATE 一律拒绝。
 *    被拒后**不要换写法重试**（换工具、包脚本、改 ORM 都算绕路）——停下来报主线。
 */
const ownedIds = new Set<string>();
export function registerOwnedId(id: string) { ownedIds.add(id); }

export function sqlWrite(sql: string, reason: string): string {
  const t = sql.trim().replace(/;$/, '');
  if (/\b(DROP|TRUNCATE|DELETE|ALTER|GRANT|REVOKE|CREATE\s+DATABASE)\b/i.test(t)) {
    throw new Error(
      `🚨 [SG] 拒绝执行：命中 CLAUDE.md §3.2 不可逆操作红线。\n  SQL: ${t.slice(0, 200)}\n` +
      `  正确动作是停下来报主线（说明要做什么 + 为什么需要 + 影响面），🚫 不许换写法重试。`,
    );
  }
  const okShape =
    /^INSERT\s+INTO\s+template(_component)?\b/i.test(t) ||
    /^UPDATE\s+template\s+SET\s+status\s*=\s*'ARCHIVED'/i.test(t);
  if (!okShape) {
    throw new Error(`🚨 [SG] sqlWrite 白名单外语句被拒（只允许模板副本 INSERT / 副本归档 UPDATE）：\n${t.slice(0, 200)}`);
  }
  const hitsOwn = t.includes(PREFIX) || [...ownedIds].some((id) => t.includes(id));
  if (!hitsOwn) {
    throw new Error(
      `🚨 [SG] sqlWrite 拒绝：语句没有命中本片自建对象（既不含前缀 ${PREFIX}，也不含已登记 id）。\n${t.slice(0, 200)}`,
    );
  }
  console.log(`[SG][write] ${reason}`);
  return psql(['-t', '-A'], t).trim();
}

// ─────────────────────────── 2. 证据归档（不落 test-results）───────────────────────────

/**
 * 🚨 证据目录 = 任务目录，**不是** `test-results/`。
 * testing.md §2 判据：「下一轮跑测试会不会把它删掉？会 → 不算证据。」
 */
export function evidenceDir(): string {
  const dir = path.resolve(
    path.dirname(__f),
    '../../dev-docs/task-260909-核价树骨架分档与轴口径统一/证据/e2e-sg',
  );
  // 结构自检：路径层数算错时截图会被静默写到别处（不报错，最难发现）
  const repoRoot = path.resolve(path.dirname(__f), '../..');
  for (const marker of ['cpq-frontend', 'cpq-backend', 'dev-docs']) {
    expect(
      fs.existsSync(path.join(repoRoot, marker)),
      `证据目录落点自检失败：推导出的仓库根 ${repoRoot} 下没有 ${marker}/`,
    ).toBe(true);
  }
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

let shotIdx = 0;
export async function shot(page: Page, name: string): Promise<string> {
  const file = path.join(evidenceDir(), `${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`[SG][证据] ${name} → ${file}`);
  return file;
}

/** 把原始 JSON / SQL 输出也归档成文本证据（AC-3/AC-4/AC-16 的取证形式）。 */
export function saveEvidence(name: string, content: string): string {
  const file = path.join(evidenceDir(), `${name}.txt`);
  fs.writeFileSync(file, content, 'utf-8');
  console.log(`[SG][证据] ${name} → ${file}`);
  return file;
}

// ─────────────────────────── 3. 接口通道 ───────────────────────────

export const CFG_PATH = '/api/cpq/costing-bom-tree-config';   // ⚠️ 单数，见 api.md §1

/**
 * 建一个已登录的 APIRequestContext。
 *
 * 🚨 **鉴权是 Cookie 不是 Bearer token**：`POST /auth/login` 的响应体里没有 token，
 *    身份在 `Set-Cookie: CPQ_SESSION=<uuid>; HttpOnly; SameSite=Lax`。
 *    ⇒ 这里**只判 login 成功**、后续请求靠 APIRequestContext 自带的 cookie jar，
 *      🚫 不去响应体里挖 token（那会在"登录成功 200"时抛"取不到 token"，
 *      失败长得像产品鉴权契约变了 —— S1 片已踩过）。
 */
export async function apiContext(
  baseURL = BACKEND_URL, username = 'admin', password = 'Admin@2026',
): Promise<APIRequestContext> {
  const ctx = await request.newContext({ baseURL, ignoreHTTPSErrors: true });
  const res = await ctx.post('/api/cpq/auth/login', { data: { username, password } });
  expect(res.ok(), `登录 ${baseURL} 失败（${username}）：${res.status()} ${await res.text()}`).toBe(true);
  return ctx;
}

/** 尝试登录，失败返回 null（用于 test1 这种"有就用、没有就跳过"的探针）。 */
export async function tryApiContext(username: string, password: string): Promise<APIRequestContext | null> {
  const ctx = await request.newContext({ baseURL: BACKEND_URL, ignoreHTTPSErrors: true });
  const res = await ctx.post('/api/cpq/auth/login', { data: { username, password } });
  if (!res.ok()) { await ctx.dispose(); return null; }
  return ctx;
}

export type CfgRow = { id: string; name: string; usage: string; isActive: boolean };

/** 直接查库拿"生效配置"，比接口更贴近 AC-3 的断言口径（AC 原文就是一条 SQL）。 */
export function activeConfigRows(): CfgRow[] {
  return sqlRowsF(
    `SELECT usage, id, name FROM costing_bom_tree_config WHERE is_active ORDER BY usage`,
  ).map(([usage, id, name]) => ({ usage, id, name, isActive: true }));
}

export function activeIdOf(usage: string): string | null {
  const r = activeConfigRows().find((x) => x.usage === usage);
  return r ? r.id : null;
}

/** AC-3 的断言口径：`SELECT usage,count(*) … WHERE is_active GROUP BY 1` 原始输出。 */
export function activeCountByUsageRaw(): string {
  return psql(['-A', '-F', '\x1f'],
    `SELECT usage, count(*) FROM costing_bom_tree_config WHERE is_active GROUP BY 1 ORDER BY 1`).trim();
}
export function activeCountByUsage(): Record<string, number> {
  const out: Record<string, number> = {};
  for (const [u, c] of sqlRowsF(
    `SELECT usage, count(*)::text FROM costing_bom_tree_config WHERE is_active GROUP BY 1`)) {
    out[u] = Number(c);
  }
  return out;
}

export function configTotalCount(): number {
  return sqlNum(`SELECT count(*) FROM costing_bom_tree_config`);
}

export async function postConfig(
  api: APIRequestContext, body: { name: string; sqlTemplate: string; usage: string; isActive?: boolean },
) {
  const res = await api.post(CFG_PATH, { data: body });
  const text = await res.text();
  return { status: res.status(), text, json: safeJson(text) };
}

export async function putConfig(api: APIRequestContext, id: string, body: Record<string, unknown>) {
  const res = await api.put(`${CFG_PATH}/${id}`, { data: body });
  const text = await res.text();
  return { status: res.status(), text, json: safeJson(text) };
}

export async function activateConfig(api: APIRequestContext, id: string) {
  const res = await api.post(`${CFG_PATH}/${id}/activate`);
  const text = await res.text();
  return { status: res.status(), text, json: safeJson(text) };
}

export async function deleteConfig(api: APIRequestContext, id: string) {
  const res = await api.delete(`${CFG_PATH}/${id}`);
  const text = await res.text();
  return { status: res.status(), text, json: safeJson(text) };
}

/**
 * 从 psql 输出里挖第一个 uuid。
 * 🚨 `psql -t -A -c "INSERT … RETURNING id"` 的 stdout 是**两行**：`<uuid>\nINSERT 0 1`
 *    —— 直接拿整串去比 uuid 正则会失败，而那时候行**已经插进库了**（run#1 实证：
 *    留下一份没人认领的 T260909- 孤儿模板）。
 */
export function firstUuid(out: string): string {
  const m = out.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i);
  return m ? m[0] : '';
}

export function safeJson(text: string): any {
  try { return JSON.parse(text); } catch { return null; }
}

/** 从任意错误响应体里挖出人读的 message（形状未知时也别丢信息）。 */
export function messageOf(text: string): string {
  const j = safeJson(text);
  if (!j) return text;
  return String(j.message ?? j.error ?? j.detail ?? j.data?.message ?? text);
}

// ─────────────────────────── 4. 实例正身与前置（防假绿 #1）───────────────────────────

/**
 * 🚨 跑任何断言之前先证明「8081 上跑的确实是含本次改动的代码 + 配置动作已做」。
 *
 * 📌 为什么不能只探活：401 只证明「有个 Quarkus 在跑、鉴权正常」，**证明不了它跑的是我的代码**
 *    （task-260909 抽屉片 2026-09-09 实证：主线给的端口被别的 worktree 占着，
 *      A/B 两侧都是旧代码，看起来像"修复没生效"的产品缺陷）。
 *
 * 三个探针，全部只读：
 *   P1（代码）TemplateDTO 含 `warnings` 键 —— api.md §3「恒非 null，其它端点恒 []」
 *   P2（代码）SALES_MANAGER 调 GET 配置端点 → 403 —— api.md §2 角色收紧
 *   P3（配置）库里存在 usage='COST_BASIC' 的行 —— 交付项 10「配置动作」已执行
 *
 * 判定：P3 必须成立（否则本片 6 条 AC 全都没有前置）；P1/P2 至少一条成立。
 */
export async function assertShardPreconditions(api: APIRequestContext) {
  assertIsolatedEnv();
  const lines: string[] = [];

  // 环境指纹（不是断言，是让报告能复核"这一轮跑在哪"）
  const pgFingerprint = sqlRead(
    `SELECT coalesce(to_char(min(backend_start),'YYYY-MM-DD HH24:MI:SS'),'(取不到)') ` +
    `FROM pg_stat_activity WHERE datname='${DB.db}'`);
  lines.push(`BASE_URL=${BASE_URL}  BACKEND_URL=${BACKEND_URL}  DB=${DB.host}/${DB.db}`);
  lines.push(`最早后端连接建立时间（近似"后端启动时刻"）= ${pgFingerprint}`);

  // ── P3：配置动作 ──
  const cfgAll = sqlRowsF(
    `SELECT usage, id, name, is_active::text FROM costing_bom_tree_config ORDER BY usage, created_at`);
  lines.push('costing_bom_tree_config 全表：\n' + cfgAll.map((r) => '  ' + r.join(' | ')).join('\n'));
  const activeBasic = activeIdOf('COST_BASIC');
  expect(
    activeBasic,
    `🚨 前置不满足：usage='COST_BASIC' 没有生效配置。\n` +
    `   ⇒ 交付项 10（配置动作：配出 COST_BASIC 骨架 SQL 并设为生效）尚未执行，\n` +
    `      本片 AC-3/4/5/19/20 全部没有可验前置。**这不是产品缺陷，请报主线补配置**。\n` +
    `   现有配置：\n${cfgAll.map((r) => '     ' + r.join(' | ')).join('\n')}`,
  ).not.toBeNull();
  lines.push(`P3 ✅ usage=COST_BASIC 生效配置 id=${activeBasic}`);

  // ── P1：TemplateDTO.warnings ──
  const tplId = costingTemplateId();
  const detail = await api.get(`/api/cpq/templates/${tplId}`);
  const dText = await detail.text();
  const dJson = safeJson(dText);
  const p1 = !!dJson?.data && Object.prototype.hasOwnProperty.call(dJson.data, 'warnings');
  lines.push(`P1 ${p1 ? '✅' : '❌'} GET /templates/{核价模板1} 的 data ${p1 ? '含' : '不含'} warnings 键；` +
    `实际键集=${dJson?.data ? JSON.stringify(Object.keys(dJson.data).sort()) : '(解析不出)'}`);

  // ── P2：角色收紧（**行为型**正身判据，主线指定用它而不是配置）──
  //     smgr GET /costing-bom-tree-config：新代码 403 / 旧代码(8081) 200。
  //     返 200 就说明打错了实例 —— 这条比"端口号对不对"可靠得多。
  let p2: boolean | null = null;
  if (!SMGR_USER || !SMGR_PWD) {
    lines.push('P2 ⏭ 未提供 PW_USER_SMGR / PW_PWD_SMGR —— 行为型正身判据缺席');
  } else {
    const sm = await tryApiContext(SMGR_USER, SMGR_PWD);
    if (!sm) {
      lines.push(`P2 ❌ ${SMGR_USER} 登录失败（凭据不对，或打到了没有这个账号的实例）`);
      p2 = false;
    } else {
      const r = await sm.get(CFG_PATH);
      p2 = r.status() === 403;
      lines.push(`P2 ${p2 ? '✅' : '❌'} ${SMGR_USER}(SALES_MANAGER) GET ${CFG_PATH} → ${r.status()}` +
        `（期望 403；返 200 = 打到了旧代码实例，本轮结论全部无效）`);
      await sm.dispose();
    }
  }

  const banner = `[SG] 实例正身与前置探针\n${lines.join('\n')}`;
  console.log(banner);
  saveEvidence('00-实例正身与前置探针', banner);

  expect(
    p1 || p2 === true,
    `🚨 实例正身无法确认：P1（TemplateDTO.warnings）与 P2（SALES_MANAGER→403）都没成立。\n` +
    `   ⇒ ${BACKEND_URL} 上跑的**可能仍是改动前的代码**，本轮全部结论无效。\n` +
    `   先修环境（确认后端跑的是本 worktree 的代码），🚫 不要按产品缺陷解读。\n${banner}`,
  ).toBe(true);
}

export function costingTemplateId(): string {
  const id = sqlRead(
    `SELECT id FROM template WHERE name='${COSTING_TEMPLATE_NAME}' AND template_kind='COSTING' ` +
    `AND status='PUBLISHED' ORDER BY updated_at DESC LIMIT 1`);
  expect(id, `库里找不到 PUBLISHED 的 COSTING 模板「${COSTING_TEMPLATE_NAME}」`).toMatch(/^[0-9a-f-]{36}$/);
  return id;
}

// ─────────────────────────── 5. UI 定位（选择器坑一律 dump 候选）───────────────────────────

/**
 * 四个已知 Playwright 坑（cpq-playwright-selector-pitfalls），**都表现为 timeout**：
 *  1. 页签用 role=tab / .ant-tabs-tab，🚫 不用 getByText().first()
 *  2. antd 两字按钮渲染成「保 存」（中间有空格）⇒ 一律用 /保\s*存/
 *  3. 下拉虚拟滚动，选项先 scrollIntoViewIfNeeded
 *  4. antd 6.3.5 起 `.ant-drawer-content` / `.ant-select-selection-item` 类名没了
 *     ⇒ 抽屉用 `.ant-drawer`，选中值读 `.ant-select` 的 innerText
 */
export async function dumpCandidates(page: Page, label: string, selectors: string[]) {
  const out: string[] = [`[SG][dump] ${label}`];
  for (const sel of selectors) {
    const texts = await page.locator(sel).allInnerTexts().catch(() => [] as string[]);
    out.push(`  ${sel} → ${texts.length} 个：${JSON.stringify(texts.map((t) => t.trim().slice(0, 40)))}`);
  }
  const msg = out.join('\n');
  console.log(msg);
  return msg;
}

/** 进「组件管理 → 核价树配置」页签。定位不到就 dump 全部页签名再硬失败。 */
export async function gotoTreeConfigTab(page: Page, navigate = true) {
  if (navigate) {
    await page.goto('/components');
    await page.waitForLoadState('networkidle');
  }
  await page.waitForTimeout(1200);
  const byRole = page.getByRole('tab', { name: '核价树配置', exact: true });
  if (await byRole.count() > 0) { await byRole.first().click(); await page.waitForTimeout(1200); return; }
  const byClass = page.locator('.ant-tabs-tab').filter({ hasText: /^核价树配置$/ });
  if (await byClass.count() > 0) { await byClass.first().click(); await page.waitForTimeout(1200); return; }
  const dump = await dumpCandidates(page, '找不到「核价树配置」页签', ['[role="tab"]', '.ant-tabs-tab']);
  await shot(page, 'ERR-找不到核价树配置页签');
  throw new Error(
    `🚨 /components 上定位不到「核价树配置」页签。\n${dump}\n` +
    `  ⚠️ 先怀疑选择器（4 个已知坑都表现为 timeout），再怀疑产品：\n` +
    `     若页签栏确实只有 3 个且当前账号是 admin，那才是 AC-17 的产品缺陷（属 S1 片）。`);
}

/** 选择 usage 切换控件的某一项（报价 / 基础核价 / 详细核价）。控件形态未知 ⇒ 逐种尝试。 */
export async function selectUsage(page: Page, label: '报价' | '基础核价' | '详细核价') {
  const rx = new RegExp(`^\\s*${label}\\s*$`);
  const candidates: Locator[] = [
    page.locator('.ant-segmented-item').filter({ hasText: rx }),
    page.locator('.ant-radio-button-wrapper').filter({ hasText: rx }),
    page.getByRole('tab', { name: label, exact: true }),
    page.locator('.ant-tabs-tab').filter({ hasText: rx }),
  ];
  for (const c of candidates) {
    if (await c.count() > 0) {
      await c.first().click();
      await page.waitForTimeout(1500);
      return;
    }
  }
  const dump = await dumpCandidates(page, `找不到 usage 切换项「${label}」`,
    ['.ant-segmented-item', '.ant-radio-button-wrapper', '[role="tab"]', '.ant-tabs-tab']);
  await shot(page, `ERR-找不到usage项-${label}`);
  throw new Error(`🚨 定位不到 usage 切换项「${label}」（F-x 交付项 9 要求三项：报价/基础核价/详细核价）。\n${dump}`);
}

export type UiCfgRow = { text: string; active: boolean };

/** 读当前 usage 下配置列表的每一行（整行文本 + 是否标「生效中」）。 */
export async function readConfigTableRows(page: Page): Promise<UiCfgRow[]> {
  const rows = page.locator('.ant-table-tbody tr.ant-table-row');
  const n = await rows.count();
  const out: UiCfgRow[] = [];
  for (let i = 0; i < n; i++) {
    const t = (await rows.nth(i).innerText().catch(() => '')).replace(/\s+/g, ' ').trim();
    out.push({ text: t, active: /生效中/.test(t) });
  }
  console.log(`[SG][ui] 配置列表 ${n} 行：\n${out.map((r, i) => `   #${i} active=${r.active} | ${r.text}`).join('\n')}`);
  return out;
}

// ─────────────────────────── 6. 报价单 / 核价卡片 UI ───────────────────────────

/** 进「报价单编辑页 → 核价单 → 产品卡片」视图。 */
export async function enterCostingCard(page: Page): Promise<void> {
  await page.goto(`/quotations/${QUOTATION_ID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);
  const nextBtn = page.getByRole('button', { name: /下一步|继续/ }).first();
  if (await nextBtn.count() > 0 && await nextBtn.isEnabled().catch(() => false)) {
    await nextBtn.click().catch(() => {});
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);
  }
  // ⚠️ 实测 segmented 文案带 emoji 前缀：["📝 报价单","📊 核价单","📈 比对视图","📋 产品卡片","📑 Excel 视图"]
  //    ⇒ 锚定正则 /^核价单$/ 永远匹配不上，症状是纯 timeout，长得像"进不了核价单视图"的产品缺陷。
  const costing = page.locator('.ant-segmented-item').filter({ hasText: /核价单/ }).first();
  if (await costing.count() === 0) {
    const dump = await dumpCandidates(page, '找不到「核价单」切换项', ['.ant-segmented-item', '.ant-tabs-tab']);
    await shot(page, 'ERR-找不到核价单切换项');
    throw new Error(`🚨 进不了核价单视图（${QUOTATION_NO}）。\n${dump}`);
  }
  await costing.click();
  await page.waitForTimeout(1500);
  const cardSeg = page.locator('.ant-segmented-item').filter({ hasText: /产品卡片/ }).first();
  if (await cardSeg.count() > 0) { await cardSeg.click().catch(() => {}); await page.waitForTimeout(1200); }
  await page.waitForTimeout(2500);
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
    throw new Error(
      `🚨 料号 ${part} 的产品卡片命中 ${n} 张（期望恰好 1）。总卡片数=${total}\n` +
      `   各卡片首两行：\n${titles.map((t, i) => `     #${i} ${t}`).join('\n')}`);
  }
  await hit.first().scrollIntoViewIfNeeded();
  await page.waitForTimeout(500);
  return hit.first();
}

/** 在卡片内切页签，返回该页签的数据行 locator（限定在卡片内，不跨卡片）。 */
export async function switchTabInCard(card: Locator, tabName: string): Promise<Locator> {
  const page = card.page();
  const btn = card.locator('button.qt-tab-btn').filter({ hasText: new RegExp(`^\\s*${tabName}\\s*$`) }).first();
  if (!(await btn.isVisible().catch(() => false))) {
    const tabs = await card.locator('button.qt-tab-btn').allInnerTexts().catch(() => [] as string[]);
    await shot(page, `ERR-卡片页签-${tabName}`);
    throw new Error(`🚨 卡片内找不到页签「${tabName}」。实际页签=${JSON.stringify(tabs.map((t) => t.trim()))}`);
  }
  await btn.click();
  await page.waitForTimeout(2500);
  return card.locator('.qt-cost-table tbody tr');
}

/**
 * 读某页签的行数**并打印每行首列**。
 * 🚨 testing.md §3：行数断言前必须能看到实际值，否则 0 行和"断言空跑"分不开。
 */
export async function readTabRows(
  card: Locator, tabName: string,
): Promise<{ count: number; firstCells: string[]; fullTexts: string[]; nonEmpty: number }> {
  const rows = await switchTabInCard(card, tabName);
  const count = await rows.count();
  const firstCells: string[] = [];
  const fullTexts: string[] = [];
  // ⚠️ 一个页签可能被拆成**多张 .qt-cost-table**（冻结列 + 滚动列）。
  //    只读第一张时，行数是对的、内容却只有冻结那几列 —— 拿它当"非空"证据会严重高估。
  //    ⇒ 同一行索引把卡片内所有表的该行文本拼起来。
  const tables = card.locator('.qt-cost-table');
  const tableCount = await tables.count();
  for (let i = 0; i < count; i++) {
    firstCells.push((await rows.nth(i).locator('td').first().innerText().catch(() => '')).replace(/\s+/g, '').trim());
    const parts: string[] = [];
    for (let t = 0; t < tableCount; t++) {
      const r = tables.nth(t).locator('tbody tr').nth(i);
      if (await r.count() === 0) continue;
      const txt = (await r.innerText().catch(() => '')).replace(/\s+/g, ' ').trim();
      // 🚨 单元格多是 <input>，`innerText` **读不到它们的值** —— 只靠文本判"非空"会把
      //    一整行可编辑的真数据读成空行（run 实证：BOM 行文本只有 "▼ 300001 3 🔗"）。
      const vals = (await r.locator('input, select, textarea')
        .evaluateAll((els: any[]) => els.map((e) => String(e.value ?? '')).filter((v) => v !== ''))
        .catch(() => [] as string[]));
      parts.push(vals.length ? `${txt} 〔值: ${vals.join(', ')}〕` : txt);
    }
    fullTexts.push(parts.join(' ‖ ').trim());
  }
  // 🚨 「有 N 行」≠「有 N 行数据」：baseRows 为空时表格仍会渲空壳行。
  //    行数对上、内容全空的那种绿，是四类假绿里最像真的一种 ⇒ 这里把非空行数也算出来。
  const nonEmpty = fullTexts.filter((t) => t.replace(/[\s—\-|]/g, '') !== '').length;
  console.log(`[SG][ui] 页签「${tabName}」行数=${count}（非空 ${nonEmpty}，卡片内表数=${tableCount}），首列=${JSON.stringify(firstCells)}`);
  fullTexts.forEach((t, i) => console.log(`[SG][ui]   #${i} ${t}`));
  return { count, firstCells, fullTexts, nonEmpty };
}

/** 页面上「核价渲染失败」红框的数量与文案。 */
export async function renderErrors(page: Page): Promise<string[]> {
  const boxes = page.locator('text=/核价渲染失败/');
  const n = await boxes.count();
  const out: string[] = [];
  for (let i = 0; i < n; i++) out.push((await boxes.nth(i).innerText().catch(() => '')).replace(/\s+/g, ' ').trim());
  return out;
}

/** 点「刷新基础数据」并在确认框里确认。返回是否真的发出了刷新请求。 */
export async function clickRefreshBasicData(page: Page): Promise<boolean> {
  const seen: string[] = [];
  const onResp = (r: any) => {
    if (/\/quotations\/[^/]+\/(refresh-card-snapshot|refresh-snapshot)/.test(r.url())) {
      seen.push(`${r.status()} ${r.url()}`);
    }
  };
  page.on('response', onResp);
  try {
    const btn = page.locator('[data-testid="refresh-basic-data-btn"]');
    if (!(await btn.isVisible().catch(() => false))) {
      // 按钮可能只挂在「报价单」侧工具栏 ⇒ 切过去点，再切回来
      const quote = page.locator('.ant-segmented-item').filter({ hasText: /报价单/ }).first();
      if (await quote.count() > 0) { await quote.click(); await page.waitForTimeout(1500); }
    }
    await btn.waitFor({ state: 'visible', timeout: 15_000 });
    await btn.click();
    await page.waitForTimeout(600);
    const okBtn = page.locator('.ant-modal-confirm-btns button')
      .filter({ hasText: /刷\s*新|确\s*定|确\s*认/ }).first();
    await okBtn.waitFor({ state: 'visible', timeout: 10_000 });
    await okBtn.click();
    await page.waitForResponse(
      (r) => /\/quotations\/[^/]+\/(refresh-card-snapshot|refresh-snapshot)/.test(r.url()),
      { timeout: 120_000 });
    await page.waitForTimeout(4000);
    console.log(`[SG][ui] 刷新基础数据请求：${JSON.stringify(seen)}`);
    return seen.length > 0;
  } finally {
    page.off('response', onResp);
  }
}


// ─────────────────────────── 7. UI 登录（不复用 .auth/ storageState）───────────────────────────

/**
 * 每次都走 UI 登录。
 * 🚫 刻意**不用** `fixtures/auth.ts` 的 storageState 分支：那份 state 是仓库 globalSetup
 *    在 **5174/8081** 上存的，而 Cookie 不区分端口 —— 拿到临时栈 5212 上可能"看起来登上了"
 *    却是另一实例的会话，属最难查的一类环境串扰。
 */
export async function uiLogin(page: Page, username = 'admin', password = 'Admin@2026') {
  await page.context().clearCookies();
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill(username);
  await page.locator('input[placeholder="密码"]').fill(password);
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|components|change-password)/, { timeout: 20_000 });
  if (page.url().includes('/change-password')) {
    await page.goto('/dashboard');
    await page.waitForLoadState('networkidle');
  }
  expect(page.url(), `UI 登录后仍停在 /login（${username}）`).not.toContain('/login');
}
