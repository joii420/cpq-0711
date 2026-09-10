/**
 * task-260909「取数配置器字段类型选择」· 单片 S1 共享 helpers
 *
 * ══════════════════════════════════════════════════════════════════════════
 * 🚫 本文件与 5 个 spec 的**全部断言**，均从 `需求文档.md §③ 验收标准` 的 AC 原文
 *    与 `api.md` 的契约派生。**不曾读过** `cpq-backend/src/main/java/**`、
 *    `cpq-frontend/src/**`、`backtask.md`、`fronttask.md`（派工 prompt 段 c 点名禁止）。
 *    选择器词汇取自**既有 e2e 测试代码**（`task260907-builder-gaps.spec.ts` /
 *    `task260908-s1.helpers.ts` / `fixtures/task260909.ts` / `fixtures/task260909tree.ts`）
 *    与 `原型图/*.html`，两者都在允许范围内。
 *
 * ── 本片的写入面（逐项登记，`test.md §3`）────────────────────────────────
 *   1. 自建 `component` + `component_sql_view`（走 POST /components + PUT /builder）
 *   2. 自建 **DRAFT** `template` + `template_component`
 *   3. 自建 `quotation`（走 POST /quotations）+ 其 `quotation_line_item`
 *   ⇒ 三项全部「只写自己造的」= **私有写片**，本任务**零 S-全局**：
 *      🚫 不动 `costing_bom_tree_config`、🚫 不动任何模板发布态、
 *      🚫 不动既有单据快照、🚫 不碰 `核价模板1`(9d89e95d…) / `QT-20260909-0661`(3c38bfb7…)。
 *
 * ── 三条私有写片约束（缺一不可）─────────────────────────────────────────
 *   ① 造数一律带前缀 `T260909FT-`
 *   ② 只断言自己造的那批（🚫 全局计数断言）
 *   ③ `finally` 里**归档**自己那批（🚦 `DELETE` 属 CLAUDE.md §3.2 红线，本片无批准权
 *      ⇒ 组件置 DISABLED / 模板置 ARCHIVED；报价单无归档态 ⇒ 只登记「待回收清单」）
 */
import { expect, Locator, Page } from '@playwright/test';
import { execSync } from 'child_process';
import * as fs from 'fs';
import * as nodePath from 'path';
import { fileURLToPath } from 'url';

// 🚩 Vite ESM 项目没有 __dirname（AP-43 同族）
const HERE = nodePath.dirname(fileURLToPath(import.meta.url));

// ═══════════════════════════════════════════════════════════════════
// 0. 环境坐标 —— 🚫 端口一律从环境变量推，不写死
//    （`task-260909-核价树骨架` 的测试员写死 8081，打出过一行
//     「url=8131 但 cwd=主仓」的自相矛盾记录 —— `testing.md §5.5`）
// ═══════════════════════════════════════════════════════════════════

export const BASE_URL = process.env.PW_BASE_URL || 'http://localhost:5174';
export const BACKEND_URL = process.env.PW_BACKEND_URL || 'http://localhost:8081';

export const DB = {
  host: process.env.PW_DB_HOST || '10.177.152.12',
  port: process.env.PW_DB_PORT || '5432',
  user: process.env.PW_DB_USER || 'postgres',
  db: process.env.PW_DB_NAME || 'cpq_db_0724',
  password: process.env.PW_DB_PASSWORD || 'joii5231',
};

export const ADMIN = {
  username: process.env.PW_USER_ADMIN || 'admin',
  password: process.env.PW_PWD_ADMIN || 'Admin@2026',
};

/** 🔑 造数前缀 —— 共库并行下防串扰的唯一依据（回收守卫也靠它做归属校验）。 */
export const TAG = 'T260909FT-';

/**
 * 🚨 **本轮运行的唯一标记**。
 *
 * 2026-09-09 静态核查实测：`T260909FT-` 这个前缀**已被别的会话占用** —— 库里已存在
 * `T260909FT-BASIC`(COMP-2422) / `T260909FT-INPUT`(COMP-2423) / `T260909FT-模板`(PUBLISHED, 7 页签, 绑了 1 张单)
 * / `T260909FT-渲染等价对照`(QT-20260909-0795) 等 7 个对象，且**场景与我的 AC-6 完全同构**。
 *
 * 后果如果不处理：同名对象会让「按名字定位」落到**别人的东西**上 —— 那正是最难查的一类假绿
 * （在错的对象上做对的断言）。尤其他们那张 `T260909FT-模板` 是 **PUBLISHED**，
 * 而 AC-6 明确要求 DRAFT（PUBLISHED 吃冻结快照，读不到活表）。
 *
 * ⇒ 造数名一律追加本轮 RUN_ID。**前缀保持不变**（回收守卫依赖它），
 *   且 AC 原文里的 `T260909FT-BASIC` 等是**造数约定、不是可观测断言** —— 没有任何一条 AC 断言
 *   「组件名恰好等于该字符串」⇒ 加后缀不削弱任何断言，只消除歧义。
 */
export const RUN_ID = process.env.PW_RUN_ID || `r${Date.now().toString(36).slice(-5)}`;

/** 造一个本轮唯一的对象名：`T260909FT-<何物>#<RUN_ID>`。 */
export function ownName(what: string): string {
  return `${TAG}${what}#${RUN_ID}`;
}

/** 🚫 本片绝对不许碰的既有交付对象（`task-260909-核价树骨架` 刚交付）。 */
export const FORBIDDEN = {
  costingTemplateId: '9d89e95d-f0e1-42eb-bf6d-0a815e531f0e', // 核价模板1
  quotationId: '3c38bfb7-6af3-4f39-9c6d-f127e02d8712',       // QT-20260909-0661
};

/** AC 值域（`api.md §1.2`，跨端必须一致）。 */
export const FIELD_TYPES = ['BASIC_DATA', 'INPUT_TEXT', 'INPUT_NUMBER'] as const;
export type FieldType = (typeof FIELD_TYPES)[number];

/** 三个选项的中文标签（`原型图/02-选择器展开态.html` 逐字）。 */
export const FT_LABEL: Record<FieldType, string> = {
  BASIC_DATA: '基础数据',
  INPUT_TEXT: '文本输入',
  INPUT_NUMBER: '数字输入',
};
export const LABEL_TO_FT: Record<string, FieldType> = {
  基础数据: 'BASIC_DATA',
  文本输入: 'INPUT_TEXT',
  数字输入: 'INPUT_NUMBER',
};

/** 🚫 不许出现在选项里的三个值（`需求文档 §② 明确否决` + AC-1 原文）。 */
export const FORBIDDEN_OPTIONS = ['FORMULA', 'DATA_SOURCE', 'FIXED_VALUE', '公式', '数据源', '固定值'];

/** 数据集方言 → 界面上的中文标签（`原型图/01`、`03`）。 */
export const DIALECT_LABEL: Record<string, string> = {
  QUOTE: '报价',
  COST_BASIC: '基础核价',
  COST_DETAIL: '明细核价',
};

// ═══════════════════════════════════════════════════════════════════
// 1. 证据归档
//    🚨 `test-results/` 每轮开跑前会被清空 ⇒ 留在那里的截图**不算证据**
//       （`testing.md §2`：下一轮会不会删掉它？会 → 不算）。
// ═══════════════════════════════════════════════════════════════════

export const EVIDENCE_DIR = nodePath.resolve(
  HERE, '..', '..', 'dev-docs', 'task-260909-取数配置器字段类型选择', '证据',
);

function ensureEvidenceDir() {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
}

export function writeEvidence(file: string, text: string) {
  ensureEvidenceDir();
  const p = nodePath.join(EVIDENCE_DIR, file);
  fs.writeFileSync(p, text, 'utf-8');
  console.log(`[evidence] → ${p}`);
  return p;
}

export function appendEvidence(file: string, text: string) {
  ensureEvidenceDir();
  fs.appendFileSync(nodePath.join(EVIDENCE_DIR, file), text, 'utf-8');
}

export async function shot(page: Page, name: string) {
  ensureEvidenceDir();
  const p = nodePath.join(EVIDENCE_DIR, `${name}.png`);
  await page.screenshot({ path: p, fullPage: true }).catch(() => {});
  console.log(`[shot] → ${p}`);
  return p;
}

export async function shotOf(loc: Locator, name: string) {
  ensureEvidenceDir();
  const p = nodePath.join(EVIDENCE_DIR, `${name}.png`);
  await loc.screenshot({ path: p }).catch(() => {});
  console.log(`[shot] → ${p}`);
  return p;
}

// ═══════════════════════════════════════════════════════════════════
// 2. SQL —— 只读默认；写操作走白名单 + 命中面量化 + 前缀归属校验
// ═══════════════════════════════════════════════════════════════════

const RED_LINE_SQL = /\b(drop|truncate|alter|create\s+(table|database|schema)|grant|revoke|vacuum)\b/i;

function runPsql(sql: string, flags = "-X -A -F'|'"): string {
  const cmd =
    `PGPASSWORD=${DB.password} psql -h ${DB.host} -p ${DB.port} -U ${DB.user} -d ${DB.db} ` +
    `${flags} -c ${JSON.stringify(sql)}`;
  return execSync(cmd, { shell: '/bin/bash', encoding: 'utf-8' }).trim();
}

/** 只读 SQL（只允许 SELECT / WITH / EXPLAIN）。 */
export function sqlRO(sql: string): string {
  const t = sql.trim().replace(/;$/, '');
  if (!/^\s*(select|with|explain)\b/i.test(t)) {
    throw new Error(
      `🚨 拒绝执行非只读 SQL：${t.slice(0, 160)}\n` +
      `  （DELETE/UPDATE/DROP/TRUNCATE 属 CLAUDE.md §3.2 红线，测试无批准权 —— 写操作请走 sqlOwnedWrite）`,
    );
  }
  return runPsql(t);
}

export function sqlRows(sql: string): Record<string, string>[] {
  const out = sqlRO(sql).split('\n').filter((l) => l.length > 0 && !/^\(\d+ rows?\)$/.test(l.trim()));
  if (out.length === 0) return [];
  const headers = out[0].split('|');
  return out.slice(1).map((line) => {
    const cells = line.split('|');
    const o: Record<string, string> = {};
    headers.forEach((h, i) => (o[h] = cells[i] ?? ''));
    return o;
  });
}

/** 单标量（🚫 -t 模式，避免表头混进来）。 */
export function sqlScalar(sql: string): string {
  return runPsql(sql.trim().replace(/;$/, ''), '-X -A -t').split('\n')[0] ?? '';
}

export function sqlInt(sql: string, why: string): number {
  const raw = sqlScalar(sql);
  const n = Number(raw);
  expect(
    Number.isFinite(n),
    `${why}：基准查询没返回数字（拿到 ${JSON.stringify(raw)}）⇒ **量具坏了**，本条判【未验证】，不是产品缺陷`,
  ).toBe(true);
  return n;
}

/**
 * 本片自建对象的登记簿。**先登记、再断言** ——
 * 万一 id 解析不出，也不能让一行已经插进库的记录变成没人认领的孤儿
 * （`t260909tree-sg` 实证：留下过一份没人认领的 T260909- 孤儿模板）。
 */
export const OWNED = {
  componentIds: [] as string[],
  templateIds: [] as string[],
  quotationIds: [] as { id: string; number: string }[],
};

export function registerComponent(id: string) { if (id && !OWNED.componentIds.includes(id)) OWNED.componentIds.push(id); }
export function registerTemplate(id: string) { if (id && !OWNED.templateIds.includes(id)) OWNED.templateIds.push(id); }
export function registerQuotation(id: string, number: string) {
  if (id && !OWNED.quotationIds.some((q) => q.id === id)) OWNED.quotationIds.push({ id, number });
}

/**
 * 只对**本片自建对象**的受限写。三条前置（CLAUDE.md §3.2 的落地形态）：
 *  ① 语句必须是 INSERT / UPDATE，且**必须带 WHERE id / VALUES**（无 WHERE 的 UPDATE 直接拒）
 *  ② 目标 id 必须在 OWNED 登记簿里
 *  ③ 执行前先量化命中面（`SELECT count(*)`），执行后打印实际影响行数
 * 🚫 DELETE / DROP / TRUNCATE 一律拒 —— 撞红线要**停下报主线**，不是换个写法再试。
 */
export function sqlOwnedWrite(sql: string, why: string, expectRows?: number): string {
  const t = sql.trim().replace(/;$/, '');
  if (RED_LINE_SQL.test(t) || /\bdelete\b/i.test(t)) {
    throw new Error(
      `🚨 停下来报主线：本片试图执行红线操作（CLAUDE.md §3.2）：${t.slice(0, 200)}\n` +
      `  子代理没有批准权，🚫 不许换个写法/换个工具重试。`,
    );
  }
  if (!/^\s*(insert|update)\b/i.test(t)) {
    throw new Error(`sqlOwnedWrite 只接受 INSERT / UPDATE：${t.slice(0, 160)}`);
  }
  if (/^\s*update\b/i.test(t) && !/\bwhere\b/i.test(t)) {
    throw new Error(`🚨 无 WHERE 的 UPDATE 属 §3.2 红线，拒绝执行：${t.slice(0, 160)}`);
  }
  const out = runPsql(t, '-X -A -t');
  const affected = (out.match(/(?:INSERT 0|UPDATE|MERGE)\s+(\d+)/) || [])[1];
  console.log(`[owned-write] ${why} → ${out.replace(/\n/g, ' / ')}（影响 ${affected ?? '?'} 行）`);
  appendEvidence('90-写入面台账.txt',
    `${new Date().toISOString()} ${why} → ${out.replace(/\n/g, ' / ')}\n  SQL: ${t}\n`);
  if (expectRows !== undefined) {
    // 🚨 命中面量化不到时**不许静默放过** —— 那等于这条守卫根本没接上（testing.md §4.4）。
    //    调用方另有「改完回读状态」的后验，两道一起才算数。
    if (affected === undefined) {
      const warn = `⚠️ ${why}：psql 没回报影响行数（stdout=${JSON.stringify(out)}）⇒ 命中面这一道量化不到，`
        + `本次改动的正确性**只能靠调用方的回读后验**。`;
      console.warn(warn);
      appendEvidence('90-写入面台账.txt', `${new Date().toISOString()} ${warn}\n`);
    } else {
      expect(Number(affected), `${why}：期望影响 ${expectRows} 行，实际 ${affected} 行 ⇒ 命中面与预期不符，停下核对`)
        .toBe(expectRows);
    }
  }
  return out;
}

// ═══════════════════════════════════════════════════════════════════
// 3. 环境正身（防「测了旧代码」「打在别人的库上」）
// ═══════════════════════════════════════════════════════════════════

/** 记录被测后端监听进程的 cwd 与启动时刻。端口从 BACKEND_URL 推，🚫 不写死。 */
export function recordBackendIdentity(): string {
  const port = (BACKEND_URL.match(/:(\d+)/) || [])[1] || '8081';
  let pid = '', cwd = '', started = '';
  try {
    const ss = execSync(`ss -ltnp 2>/dev/null | /usr/bin/grep -a ':${port} ' || true`,
      { shell: '/bin/bash', encoding: 'utf-8' });
    pid = (ss.match(/pid=(\d+)/) || [])[1] || '';
    if (pid) {
      cwd = execSync(`readlink -f /proc/${pid}/cwd 2>/dev/null || true`, { shell: '/bin/bash', encoding: 'utf-8' }).trim();
      started = execSync(`ps -o lstart= -p ${pid} 2>/dev/null || true`, { shell: '/bin/bash', encoding: 'utf-8' }).trim();
    }
  } catch { /* 采样失败不阻断；下面会打印空值，一眼看得出来 */ }
  // ⚠️「当前采样为 X」是瞬时量不是状态（CLAUDE.md §5）⇒ 证据里带时刻
  const line = `[backend-identity] BASE_URL=${BASE_URL} BACKEND_URL=${BACKEND_URL} port=${port} `
    + `pid=${pid || '?'} cwd=${cwd || '?'} started="${started || '?'}"`;
  console.log(line);
  appendEvidence('00-环境正身.txt', `${new Date().toISOString()} ${line}\n`);
  return line;
}

/** 实测被测后端连的确实是 `cpq_db_0724`（`CLAUDE.md §1` 记载的手法，比读配置可靠）。 */
export async function assertServingExpectedDb(cookie: string): Promise<void> {
  const res = await fetch(`${BACKEND_URL}/api/cpq/quotations?page=1&size=1`, { headers: { Cookie: cookie } });
  expect(res.status, '取 quotations 分页应 200（否则鉴权/服务异常 ⇒ 判【未验证】）').toBe(200);
  const body: any = await res.json();
  const api = Number(body?.data?.totalElements ?? body?.totalElements ?? NaN);
  const db = Number(sqlScalar('SELECT count(*) FROM quotation'));
  // ⚠️ 这不是全局计数断言 —— 是「两个数字必须相等」的一致性校验，别的会话造数会让两边**同时**变
  expect(Number.isFinite(api), 'totalElements 取不到 ⇒ 契约/鉴权问题，判【未验证】').toBe(true);
  expect(api, `被测后端连的库与 ${DB.db} 对不上（api=${api} db=${db}）⇒ 后续断言会打在别人的库上，这是**环境问题**`)
    .toBe(db);
  appendEvidence('00-环境正身.txt', `${new Date().toISOString()} [db-identity] api=${api} db=${db}\n`);
}

// ═══════════════════════════════════════════════════════════════════
// 4. 鉴权 —— 🚨 Cookie 会话，**不是** Bearer token
//    `POST /api/cpq/auth/login` 响应体里没有 token，身份在 `Set-Cookie: CPQ_SESSION=…`。
//    按 token 写会在**登录成功(200)**时抛「取不到 token」，那种失败长得像产品鉴权契约变了。
// ═══════════════════════════════════════════════════════════════════

export async function loginApi(username = ADMIN.username, password = ADMIN.password): Promise<string> {
  let last = 0, body = '';
  for (let i = 0; i < 4; i++) {
    const res = await fetch(`${BACKEND_URL}/api/cpq/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
      redirect: 'manual',
    });
    last = res.status;
    body = await res.text();
    if (res.ok) {
      const raw: string[] = (res.headers as any).getSetCookie?.()
        ?? (res.headers.get('set-cookie') ? [res.headers.get('set-cookie') as string] : []);
      const jar = raw.map((c) => c.split(';')[0].trim()).filter(Boolean);
      if (jar.length === 0) {
        throw new Error(
          `登录 ${username} 返 200 但响应头没有 Set-Cookie ⇒ 鉴权契约变了？响应体=${body.slice(0, 200)}\n` +
          `⚠️ 先确认不是量具问题再判产品缺陷。`,
        );
      }
      return jar.join('; ');
    }
    // 登录限流 30 次/分/IP：打满时表现为「登录失败」，**看起来像鉴权坏了**，实际是测试基础设施问题
    if (last === 429) { await new Promise((r) => setTimeout(r, 3000 * (i + 1))); continue; }
    break;
  }
  throw new Error(
    `登录失败 ${username} → ${last} ${body.slice(0, 200)}\n` +
    `⚠️ 先判是**测试环境缺陷**（口令/限流）还是产品缺陷，🚫 不要默认后者。`,
  );
}

type ApiResult = { status: number; text: string; json: any };

export async function api(
  cookie: string, path: string, init: { method?: string; body?: any } = {},
): Promise<ApiResult> {
  const res = await fetch(`${BACKEND_URL}${path}`, {
    method: init.method || 'GET',
    headers: {
      Cookie: cookie,
      ...(init.body !== undefined ? { 'Content-Type': 'application/json; charset=utf-8' } : {}),
    },
    ...(init.body !== undefined ? { body: typeof init.body === 'string' ? init.body : JSON.stringify(init.body) } : {}),
  });
  const text = await res.text();
  let json: any = null;
  try { json = JSON.parse(text); } catch { /* 非 JSON 时保留 text */ }
  return { status: res.status, text, json };
}

// ═══════════════════════════════════════════════════════════════════
// 5. 组件 / builder 配置
// ═══════════════════════════════════════════════════════════════════

/** 建组件用的目录 —— 🚨 不带 directoryId 建出来的组件 `directory_id IS NULL`，
 *  **不在目录树里、搜索也搜不到** ⇒ UI 用例会以「找不到组件」失败，
 *  而那长得像产品缺陷，实际是夹具没落进 UI 看得见的地方（task-260904 / 260907 双实证）。 */
export function builderDirectoryId(): string {
  const v = sqlScalar(
    `SELECT id::text FROM component_directory WHERE name IN ('取值配置器测试','取值配置器测试2') ORDER BY name LIMIT 1`,
  );
  expect(v, '前置未满足：找不到组件目录「取值配置器测试」⇒ 新建组件无处可挂，UI 里会找不到。这是**夹具/环境缺失**')
    .toMatch(/^[0-9a-f-]{36}$/);
  return v;
}

export interface BuilderColumn {
  sourceNodeKey: string;
  sourceColumn: string;
  fieldName: string;
  /** 可选；不传 → 走 `api.md §1.3` 的方言默认 */
  fieldType?: string;
  dataType?: string;
  isAmount?: boolean;
  inSubtotal?: boolean;
  roles?: string[];
  /** 兼容旧词汇（`task260904-semantic-gate.spec.ts` 用的是这两个键） */
  isRowKey?: boolean;
  isPartNo?: boolean;
}

export interface BuilderConfigBody {
  tabType: string;
  variantKey: string;
  dialect: 'QUOTE' | 'COST_BASIC' | 'COST_DETAIL';
  columns: BuilderColumn[];
}

/** 建一个空白组件（自动带前缀 + 目录 + 登记）。 */
export async function createComponent(cookie: string, suffix: string): Promise<{ id: string; name: string }> {
  const name = ownName(suffix);
  const r = await api(cookie, '/api/cpq/components', {
    method: 'POST',
    body: { name, directoryId: builderDirectoryId() },
  });
  const id = r.json?.data?.id ?? r.json?.id ?? '';
  if (/^[0-9a-f-]{36}$/.test(id)) registerComponent(id);   // 先登记再断言，防孤儿
  expect(r.status, `建组件「${name}」应 2xx，实际 ${r.status}：${r.text.slice(0, 300)}`).toBeLessThan(300);
  expect(id, `建组件「${name}」没拿到 id：${r.text.slice(0, 300)}`).toMatch(/^[0-9a-f-]{36}$/);
  console.log(`[component] 建 ${name} = ${id}`);
  return { id, name };
}

/** 保存 builder 配置（`PUT /components/{id}/builder`，body 是**裸 builder_config**，🚫 不包一层）。 */
export async function saveBuilder(cookie: string, componentId: string, cfg: BuilderConfigBody): Promise<ApiResult> {
  return api(cookie, `/api/cpq/components/${componentId}/builder`, { method: 'PUT', body: cfg });
}

/** 读回 builder 配置（AC-11 刷新回填的取证口，`api.md §2`）。 */
export async function readBuilder(cookie: string, componentId: string): Promise<any> {
  const r = await api(cookie, `/api/cpq/components/${componentId}/builder`);
  expect(r.status, `读回 builder 应 200，实际 ${r.status}：${r.text.slice(0, 300)}`).toBe(200);
  return r.json?.data ?? r.json;
}

/** 库里的 `component.fields[]`（AC-3/4/5 的落库取证口）。 */
export interface DbField {
  field_name: string;
  field_type: string;
  /** 顶层 `basic_data_path`（BASIC_DATA 走这条） */
  basic_data_path: string | null;
  /** 嵌套 `default_source`（INPUT_* 走这条） */
  default_source: any | null;
  raw: any;
}

export function dbFieldsOf(componentId: string): DbField[] {
  const raw = sqlScalar(`SELECT coalesce(fields::text,'[]') FROM component WHERE id='${componentId}'`);
  let arr: any[] = [];
  try { arr = JSON.parse(raw || '[]'); } catch { arr = []; }
  expect(Array.isArray(arr),
    `读组件 ${componentId} 的 fields 解析不出数组（拿到 ${raw.slice(0, 200)}）⇒ **量具坏了**，判【未验证】`).toBe(true);
  return arr.map((f) => ({
    // 🔧 2026-09-09 量具修正（实测）：`component.fields[]` 里字段名的键是 **`name`**，
    //    🚫 不是 `field_name`。原来只试 field_name/fieldName ⇒ 全部读成 ''，
    //    后果是 AC-10 按字段名找料号列**永远找不到**（会以「前置未满足」的形式假红），
    //    而 AC-3/4/5/8/9 的报错信息里字段名全是空的，看着像「字段没落库」。
    //    实测键集 = name / notes / content / is_amount / field_type / sort_order /
    //               is_subtotal / default_source / basic_data_path / formula_id / formula_name
    field_name: f.name ?? f.field_name ?? f.fieldName ?? '',
    field_type: f.field_type ?? f.fieldType ?? '',
    basic_data_path: f.basic_data_path ?? f.basicDataPath ?? null,
    default_source: f.default_source ?? f.defaultSource ?? null,
    raw: f,
  }));
}

/** 组件字段的 md5 指纹（AC-8「校验失败不得有任何落库」的逐字对照口）。 */
export function fieldsFingerprint(componentId: string): string {
  return sqlScalar(`SELECT md5(coalesce(fields::text,'<null>')) || '/' || coalesce(length(fields::text),0)::text
                    FROM component WHERE id='${componentId}'`);
}

/** 库里 `component_sql_view.builder_config.columns[]`（AC-16 的契约取证口）。 */
export interface DbBuilderColumn {
  fieldName: string;
  /** 🚨 保存后**不应再为 null**（api.md §1.6） */
  fieldType: string | null;
  sourceColumn: string;
  raw: any;
}

export function builderColumnsOf(componentId: string): DbBuilderColumn[] {
  const raw = sqlScalar(
    `SELECT coalesce(builder_config::jsonb->'columns','[]'::jsonb)::text
       FROM component_sql_view WHERE component_id='${componentId}' LIMIT 1`);
  let arr: any[] = [];
  try { arr = JSON.parse(raw || '[]'); } catch { arr = []; }
  expect(Array.isArray(arr),
    `读组件 ${componentId} 的 builder_config.columns 解析不出数组（拿到 ${raw.slice(0, 200)}）⇒ **量具坏了**，判【未验证】`)
    .toBe(true);
  return arr.map((c) => ({
    fieldName: c.fieldName ?? c.field_name ?? '',
    fieldType: c.fieldType ?? c.field_type ?? null,
    sourceColumn: c.sourceColumn ?? c.source_column ?? '',
    raw: c,
  }));
}

/**
 * AC-16 的不变量检查：`builder_config.columns[].fieldType` 与
 * `component.fields[].field_type` **按字段名逐字相同**，且不再为 null。
 * 返回可直接贴进证据的并排对照表；不满足处由调用方断言（本函数只负责取值与制表）。
 */
export function fieldTypeParity(componentId: string): {
  rows: { fieldName: string; builderFieldType: string | null; fieldsFieldType: string | null }[];
  table: string;
  nullCount: number;
  mismatches: string[];
} {
  const cols = builderColumnsOf(componentId);
  const fields = dbFieldsOf(componentId);
  const byName = new Map(fields.map((f) => [f.field_name, f.field_type]));
  const rows = cols.map((c) => ({
    fieldName: c.fieldName,
    builderFieldType: c.fieldType,
    fieldsFieldType: byName.has(c.fieldName) ? byName.get(c.fieldName)! : null,
  }));
  const mismatches = rows
    .filter((r) => r.builderFieldType !== r.fieldsFieldType)
    .map((r) => `${r.fieldName}: builder_config=${JSON.stringify(r.builderFieldType)} vs fields=${JSON.stringify(r.fieldsFieldType)}`);
  const table = [
    '| 字段名 | builder_config.columns[].fieldType | component.fields[].field_type |',
    '|---|---|---|',
    ...rows.map((r) => `| ${r.fieldName} | ${JSON.stringify(r.builderFieldType)} | ${JSON.stringify(r.fieldsFieldType)} |`),
  ].join('\n');
  return { rows, table, nullCount: rows.filter((r) => r.builderFieldType == null).length, mismatches };
}

/** 组件的 `sql_view_name`（`$builder_xxxx`）—— AC-3 的 `basic_data_path` 形如 `$<view>.<列>`。 */
export function sqlViewNameOf(componentId: string): string {
  const v = sqlScalar(`SELECT sql_view_name FROM component_sql_view WHERE component_id='${componentId}' LIMIT 1`);
  expect(v, `组件 ${componentId} 没有 component_sql_view 行 ⇒ 保存没落库，判【未验证】`).not.toBe('');
  return v;
}

/**
 * 🔑 **行为探针：验明正身**（`test.md §4`「测了旧代码」）。
 *
 * 判据 = `api.md §1.4`：保存时传 `fieldType: "FORMULA"` 必须 **400**。
 * 旧代码是**零校验**（原样写进 `field_type`）⇒ 会 200 并落库。
 * 🚨 只探活（业务端点 401 / 首页 200）证明不了跑的是本次的代码。
 *
 * 探针自己建一个组件（带前缀、进登记簿），因为「保存请求」正是被校验的那条路径。
 */
export async function assertNewCodeServing(cookie: string, sample: BuilderConfigBody): Promise<void> {
  recordBackendIdentity();
  const { id, name } = await createComponent(cookie, `PROBE-${Date.now()}`);
  const bad: BuilderConfigBody = {
    ...sample,
    columns: sample.columns.map((c, i) => (i === 0 ? { ...c, fieldType: 'FORMULA' } : c)),
  };
  const r = await saveBuilder(cookie, id, bad);
  const landed = sqlScalar(`SELECT coalesce(fields::text,'') FROM component WHERE id='${id}'`);
  const line = `[probe] 传 fieldType="FORMULA" → HTTP ${r.status}；组件 ${name} 落库 fields 长度=${landed.length}\n`
    + `        响应=${r.text.slice(0, 300)}`;
  console.log(line);
  appendEvidence('00-环境正身.txt', `${new Date().toISOString()} ${line}\n`);
  expect(
    r.status,
    `🚨 **验明正身失败**：传非法 fieldType="FORMULA" 期望 400（api.md §1.4），实际 ${r.status}。\n` +
    `   ⇒ ${BACKEND_URL} 上跑的**很可能仍是改动前的代码**（旧代码零校验，会 200 并原样落库），\n` +
    `      本轮全部结论无效。先修环境（确认后端跑的是本 worktree 的代码），🚫 不要按产品缺陷解读。\n` +
    `   响应体=${r.text.slice(0, 400)}`,
  ).toBe(400);
}

// ═══════════════════════════════════════════════════════════════════
// 6. 模板 / 报价单（AC-6 / 7 / 10 / 12 / 14 的渲染前置）
// ═══════════════════════════════════════════════════════════════════

/** AC-6 前置：一个「production_no 在 ds_cost_basic_material 里有数据」的客户 + 销售料号。 */
export interface RenderFixture {
  customerId: string;
  customerCode: string;
  categoryId: string;
  quoteTemplateId: string;
  salesPartNo: string;
  productionNo: string;
  /** 该 production_no 闭包在 ds_cost_basic_material 里的行数（AC-6 的「行数相同」基准） */
  costBasicRows: number;
}

/**
 * 选一个可用的渲染前置。
 * 🚨 全部**当场从库里取**，🚫 不写死数字（共享库会漂移；写死数字会变成「红得像业务回归」的假红）。
 * 取不到就**硬失败并写明这是夹具缺失，不是产品缺陷**。
 */
export function pickRenderFixture(): RenderFixture {
  const rows = sqlRows(
    `SELECT cu.id::text AS customer_id, cu.code AS customer_code, cu.product_category_id::text AS category_id,
            qcp.material_no AS sales_part_no, dqm.production_no,
            (SELECT count(*) FROM ds_cost_basic_material d WHERE d.production_no = dqm.production_no) AS direct_rows,
            (SELECT count(*) FROM template t WHERE t.template_kind='QUOTATION' AND t.status='PUBLISHED'
               AND t.category_id = cu.product_category_id) AS pub_tpl
       FROM customer cu
       JOIN ds_quote_customer_part qcp ON qcp.customer_no = cu.code
       JOIN ds_quote_material dqm ON dqm.material_no = qcp.material_no
       JOIN ds_cost_basic_material dcbm ON dcbm.production_no = dqm.production_no
      WHERE cu.product_category_id IS NOT NULL
      GROUP BY 1,2,3,4,5
      ORDER BY pub_tpl DESC, cu.code, qcp.material_no
      LIMIT 5`,
  );
  const usable = rows.filter((r) => Number(r.pub_tpl) > 0);
  expect(
    usable.length,
    `🚨 **夹具缺失**（不是产品缺陷）：库里找不到「客户有产品分类 + 有 PUBLISHED 报价模板 + 其销售料号的 ` +
    `production_no 在 ds_cost_basic_material 里有数据」的组合。\n` +
    `  候选（含不合格）=${JSON.stringify(rows)}\n` +
    `  ⇒ AC-6/7/10/12/14 无可验前置，本组判【未验证】，请主线补数据。`,
  ).toBeGreaterThan(0);
  const r = usable[0];
  const f: RenderFixture = {
    customerId: r.customer_id,
    customerCode: r.customer_code,
    categoryId: r.category_id,
    quoteTemplateId: sqlScalar(
      `SELECT id::text FROM template WHERE template_kind='QUOTATION' AND status='PUBLISHED'
        AND category_id='${r.category_id}' ORDER BY updated_at DESC LIMIT 1`),
    salesPartNo: r.sales_part_no,
    productionNo: r.production_no,
    costBasicRows: Number(r.direct_rows),
  };
  console.log(`[fixture] 渲染前置：客户 ${f.customerCode} / 销售料号 ${f.salesPartNo} / 生产料号 ${f.productionNo} `
    + `/ 报价模板 ${f.quoteTemplateId}`);
  appendEvidence('01-渲染前置.txt', `${new Date().toISOString()} ${JSON.stringify(f)}\n`);
  return f;
}

/**
 * 建一张 **DRAFT** 核价模板并绑定给定组件为页签。
 *
 * 🚦 为什么用 SQL 而不是 API：本片**不改任何模板的发布态**，也不碰既有模板 ——
 *    新建 DRAFT 模板是纯增行。列清单照抄既有 COSTING 模板的形状
 *    （`t260909tree-sg.spec.ts` 已验证过这条派生路径可被应用识别）。
 * 🚫 `components_snapshot` / `sql_views_snapshot` 一律 NULL —— **DRAFT 读活表，不吃冻结快照**
 *    （`需求文档 §③ 三` 注）。
 */
export function createDraftCostingTemplate(
  name: string, categoryId: string, tabs: { componentId: string; tabName: string }[],
): string {
  expect(name.startsWith(TAG), `模板名必须带前缀 ${TAG}，实际「${name}」`).toBe(true);
  const out = sqlOwnedWrite(
    // 🔧 2026-09-09 静态核查修正：`template.template_series_id` 是 **NOT NULL 且无默认值**
    //    （实测 information_schema；无外键，也不存在 template_series 表；同名多版本共享同一个 series id）。
    //    原来这里传 NULL ⇒ INSERT 直接违反非空约束 **失败**，AC-6/7/10/12/14 会在搭场景时集体倒掉，
    //    而报出来的是一句裸 psql 错误，很容易被读成"产品/环境坏了"。新建独立模板 ⇒ 自成一系，给新 uuid。
    `INSERT INTO template (id, template_series_id, name, version, category, description, usage_note,
       product_attributes, subtotal_formula, components_snapshot, status, created_by, published_at,
       created_at, updated_at, excel_view_config, customer_id, category_id, template_kind, formulas,
       is_default, referenced_variables, sql_views_snapshot, template_sql_views_snapshot)
     VALUES (gen_random_uuid(), gen_random_uuid(), '${name}', 'v1.0', NULL, 'task-260909 字段类型选择 E2E 自建（DRAFT）', NULL,
       '[]'::jsonb, NULL, NULL, 'DRAFT', NULL, NULL,
       now(), now(), NULL, NULL, '${categoryId}'::uuid, 'COSTING', '[]'::jsonb,
       false, NULL, NULL, '{}'::jsonb)
     RETURNING id`,
    `建 DRAFT 核价模板 ${name}`,
  );
  const id = (out.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i) || [])[0] || '';
  // 🔑 先登记再断言：id 解析不出时也不能让已插进库的行成为孤儿
  if (id) registerTemplate(id);
  expect(id, `建 DRAFT 模板失败，没拿到 id。psql 原始输出=${JSON.stringify(out)}\n`
    + `  ⚠️ 若库里已多出一行同名模板，请手工归档 —— 本用例已失去它的 id。`).toMatch(/^[0-9a-f-]{36}$/);

  tabs.forEach((t, i) => {
    sqlOwnedWrite(
      `INSERT INTO template_component (id, template_id, component_id, tab_name, sort_order, preset_rows,
         formula_assignments, data_driver_path_override, fields_override)
       VALUES (gen_random_uuid(), '${id}'::uuid, '${t.componentId}'::uuid, '${t.tabName}', ${i}, '[]'::jsonb,
         '{}'::jsonb, NULL, NULL)`,
      `模板 ${name} 绑页签「${t.tabName}」→ ${t.componentId}`, 1,
    );
  });
  // 🔑 回读后验：页签数 + DRAFT 状态
  const landedTabs = Number(sqlScalar(`SELECT count(*)::text FROM template_component WHERE template_id='${id}'`));
  const landedStatus = sqlScalar(`SELECT status FROM template WHERE id='${id}'`);
  expect(landedTabs, `模板 ${name} 应绑 ${tabs.length} 个页签，实际落库 ${landedTabs} 个 ⇒ **夹具问题**`).toBe(tabs.length);
  expect(landedStatus, `模板 ${name} 应为 DRAFT（DRAFT 读活表、不吃冻结快照），实际 ${landedStatus}`).toBe('DRAFT');
  console.log(`[template] DRAFT 核价模板 ${name} = ${id}（${landedTabs} 页签 / ${landedStatus}）`);
  return id;
}

/** 建报价单（走应用自己的端点）。返回 id + 单号。 */
export async function createQuotation(cookie: string, f: RenderFixture, tag: string) {
  const name = ownName(tag);
  const r = await api(cookie, '/api/cpq/quotations', {
    method: 'POST',
    body: {
      customerId: f.customerId,
      name,
      quoteType: 'STANDARD',
      // 🚨 请求体字段名是 `categoryId`，**不是** `productCategoryId`（DB 列名 ≠ DTO 字段名）
      categoryId: f.categoryId,
      customerTemplateId: f.quoteTemplateId,
    },
  });
  const id = r.json?.data?.id ?? r.json?.id ?? '';
  const number = r.json?.data?.quotationNumber ?? r.json?.quotationNumber ?? '';
  if (/^[0-9a-f-]{36}$/.test(id)) registerQuotation(id, number);
  expect(r.status, `建报价单失败：${r.status} ${r.text.slice(0, 300)}`).toBeLessThan(300);
  expect(id, `建报价单没拿到 id：${r.text.slice(0, 300)}`).toMatch(/^[0-9a-f-]{36}$/);

  // 🚨 建完必须回查落库，不能只看 200 —— 未知字段被静默忽略时，单照样建、照样 200，
  //    只是两列是空的，然后 UI 用例倒在「下一步禁用 / 抽屉打不开」，那和产品缺陷无法区分。
  const landed = sqlScalar(
    `SELECT coalesce(product_category_id::text,'') || '|' || coalesce(customer_template_id::text,'')
       FROM quotation WHERE id='${id}'`);
  const [gotCat, gotTpl] = landed.split('|');
  expect(gotCat, `造数自检失败：${number} 的 product_category_id 没落库（期望 ${f.categoryId}）⇒ **夹具要修**，🚫 别按缺陷报`)
    .toBe(f.categoryId);
  expect(gotTpl, `造数自检失败：${number} 的 customer_template_id 没落库（期望 ${f.quoteTemplateId}）⇒ **夹具要修**`)
    .toBe(f.quoteTemplateId);
  console.log(`[quotation] 建单 ${number} (${id}) 客户=${f.customerCode}`);
  return { id, number, name };
}

/** 把自建 DRAFT 核价模板绑到自建报价单上（受限 UPDATE：命中 1 行，且该行必须是自己造的）。 */
export function bindCostingTemplate(quotationId: string, templateId: string) {
  expect(quotationId, `🚨 拒绝：${quotationId} 不在本片登记簿里（只许改自己造的单）`)
    .toBe(OWNED.quotationIds.find((q) => q.id === quotationId)?.id);
  expect(quotationId, '🚨 拒绝：不许碰 task-260909-核价树骨架 交付的 QT-20260909-0661').not.toBe(FORBIDDEN.quotationId);
  expect(templateId, '🚫 不许把「核价模板1」当自建模板绑').not.toBe(FORBIDDEN.costingTemplateId);
  sqlOwnedWrite(
    `UPDATE quotation SET costing_card_template_id='${templateId}'::uuid, updated_at=now()
      WHERE id='${quotationId}'::uuid AND name LIKE '${TAG}%'`,
    `绑核价模板 ${templateId} → 报价单 ${quotationId}`, 1,
  );
  // 🔑 回读后验：绑没绑上是后面所有渲染断言的前提，没绑上会表现为「页签一个都没有」，
  //    而那**长得像产品缺陷**，实际是夹具没落到位。
  const got = sqlScalar(`SELECT coalesce(costing_card_template_id::text,'') FROM quotation WHERE id='${quotationId}'`);
  expect(got, `绑核价模板后回读对不上（期望 ${templateId}，实际 ${JSON.stringify(got)}）⇒ **夹具问题**，🚫 不是产品缺陷`)
    .toBe(templateId);
}

/** 触发本单核价卡片值物化/刷新（只作用于自建单）。 */
export async function refreshCostingSnapshot(cookie: string, quotationId: string) {
  expect(OWNED.quotationIds.some((q) => q.id === quotationId), '🚨 只许刷新自建单').toBe(true);
  const paths = [
    `/api/cpq/configure-product/quotations/${quotationId}/refresh-snapshot`,
    `/api/cpq/quotations/${quotationId}/ensure-card-values`,
  ];
  for (const p of paths) {
    const r = await api(cookie, p, { method: 'POST' });
    console.log(`[refresh] POST ${p} → ${r.status}`);
    appendEvidence('91-刷新台账.txt', `${new Date().toISOString()} POST ${p} → ${r.status} ${r.text.slice(0, 200)}\n`);
  }
}

/** 读某报价单某产品的 `costing_card_values` 里指定页签的 baseRows（阳性对照 / 空跑守卫）。 */
export function costingBaseRows(quotationId: string, salesPartNo: string, tabName: string): any[] {
  const raw = sqlScalar(
    `SELECT coalesce((
       SELECT t::text FROM quotation_line_item li,
              LATERAL jsonb_array_elements((li.costing_card_values::jsonb)->'tabs') t
        WHERE li.quotation_id='${quotationId}' AND li.product_part_no_snapshot='${salesPartNo}'
          AND t->>'tabName'='${tabName}' LIMIT 1), '')`);
  if (!raw) return [];
  try { return JSON.parse(raw)?.baseRows ?? []; } catch { return []; }
}

/**
 * 🚦 **场景级前提守卫：DRAFT 核价模板到底渲不渲染得出来。**
 *
 * 🚨 2026-09-09 实测：本库 **一张 DRAFT 模板都没有**（COSTING 7 ARCHIVED + 3 PUBLISHED，
 *    QUOTATION 1 ARCHIVED + 15 PUBLISHED），且**从没有报价单绑过 DRAFT 核价模板**（0 张）。
 *    ⇒ `需求文档 §③ 三` 依赖的「DRAFT 模板读活表、不吃冻结快照」这条路径在本库里
 *      **零生产验证** —— 与「全库 field_type='BASIC_DATA' 只有 1 个字段」是同一种处境，
 *      而需求文档自己对后者写了「🚫 不许假定它是对的」。
 *
 * 不加这道守卫的后果：DRAFT 若压根渲染不出来，AC-6/7/10/12/14 会**集体失败**，
 * 而 AC-6 的失败文案会把它说成「BASIC_DATA 键格式转换没对上」—— **归因方向完全错**，
 * 会把人引去查 `bnfDriverLookupKey()`，而真实原因是这张模板根本没进渲染。
 *
 * 📌 职责边界：本守卫**只要求阳性对照侧（`INPUT_*` 页签）非空**。
 *    `BASIC_DATA` 侧是空还是非空，是 AC-6 要判的结论，🚫 守卫不抢它的活。
 */
export function assertCostingRenderable(
  quotationId: string, salesPartNo: string, controlTabName: string, subjectTabName: string,
): void {
  const control = costingBaseRows(quotationId, salesPartNo, controlTabName);
  const subject = costingBaseRows(quotationId, salesPartNo, subjectTabName);
  const tabNames = sqlScalar(
    `SELECT coalesce(string_agg(t->>'tabName', ', '), '(一个页签都没有)')
       FROM quotation_line_item li, LATERAL jsonb_array_elements((li.costing_card_values::jsonb)->'tabs') t
      WHERE li.quotation_id='${quotationId}' AND li.product_part_no_snapshot='${salesPartNo}'`);
  const line = `[scene-guard] 核价卡片页签 = ${tabNames}；`
    + `${controlTabName}(对照/INPUT_*) baseRows=${control.length}；${subjectTabName}(BASIC_DATA) baseRows=${subject.length}`;
  console.log(line);
  appendEvidence('03-DRAFT模板渲染前提.txt', `${new Date().toISOString()} ${line}\n`);

  expect(
    control.length,
    `🚦 **场景前提不成立：DRAFT 核价模板没渲染出内容。**\n` +
    `  报价单 ${quotationId} / 产品 ${salesPartNo} 的核价卡片页签 = ${tabNames}\n` +
    `  阳性对照页签「${controlTabName}」（INPUT_* 侧，与 field_type 无关）baseRows = ${control.length}\n` +
    `  ⇒ 连**跟本次改动无关**的那一侧都渲不出来 ⇒ 这是**前提/夹具问题，不是 field_type 的缺陷**，\n` +
    `     本组 AC-6/7/10/12/14 判【未验证】，**停下来报主线**。\n` +
    `  📌 已知背景：本库一张 DRAFT 模板都没有，也从没有报价单绑过 DRAFT 核价模板 ——\n` +
    `     「DRAFT 读活表」这条路径零生产验证。若确认 DRAFT 渲染不通，需主线裁决换别的挂载方式，\n` +
    `     🚫 不许改成「把模板发布掉」绕过去 —— PUBLISHED 吃冻结快照，改了组件字段也看不到新配置，\n` +
    `     那样验出来的东西没有意义（前端子代理疑似已踩过这个坑）。`,
  ).toBeGreaterThan(0);
}

// ═══════════════════════════════════════════════════════════════════
// 7. 回收：归档，🚫 不删除
//    🚦 `DELETE` 属 CLAUDE.md §3.2 红线，本片**没有批准权** ——
//       组件置 DISABLED / 模板置 ARCHIVED；报价单无归档态 ⇒ 只登记「待回收清单」。
// ═══════════════════════════════════════════════════════════════════

export function archiveOwned(): string {
  const lines: string[] = [`# task-260909 字段类型选择 · 自建产物回收台账（${new Date().toISOString()}）`, ''];

  // ① 组件 → DISABLED（component.status 实测枚举 = ACTIVE / DISABLED）
  // 🚨 逐个 try/catch：任何一个归档失败都**不许中断整轮回收** ——
  //    半途中断留下的未归档对象会污染下一轮的 AC-13 存量分布，且那个红长得像业务回归。
  for (const id of OWNED.componentIds) try {
    const row = sqlScalar(`SELECT coalesce(name,'') || '|' || coalesce(code,'') || '|' || coalesce(status,'')
                             FROM component WHERE id='${id}'`);
    if (!row) { lines.push(`- component ${id} 已不存在，跳过`); continue; }
    const [name, code, status] = row.split('|');
    if (!name.startsWith(TAG)) {
      // 🚨 归属校验不过 → 一个字节都不动，直接报主线
      lines.push(`- 🚨 component ${id} name=${JSON.stringify(name)} **不带 ${TAG} 前缀，未归档**，请主线核对`);
      console.warn(`[cleanup] 🚨 中止：${id} 不带前缀，不动它`);
      continue;
    }
    if (status !== 'DISABLED') {
      sqlOwnedWrite(`UPDATE component SET status='DISABLED', updated_at=now()
                      WHERE id='${id}'::uuid AND name LIKE '${TAG}%'`, `归档组件 ${name}`, 1);
    }
    lines.push(`- component ${code || '?'} / ${name} / ${id} → DISABLED（**待回收**：DELETE 属红线，需用户批准）`);
  } catch (e) {
    lines.push(`- 🚨 component ${id} 归档失败：${String(e).slice(0, 200)} —— **未归档，请主线处理**`);
  }

  // ② 模板 → ARCHIVED（template.status 实测枚举含 ARCHIVED）
  for (const id of OWNED.templateIds) try {
    const row = sqlScalar(`SELECT coalesce(name,'') || '|' || coalesce(status,'') FROM template WHERE id='${id}'`);
    if (!row) { lines.push(`- template ${id} 已不存在，跳过`); continue; }
    const [name, status] = row.split('|');
    if (!name.startsWith(TAG)) {
      lines.push(`- 🚨 template ${id} name=${JSON.stringify(name)} **不带 ${TAG} 前缀，未归档**，请主线核对`);
      continue;
    }
    if (status !== 'ARCHIVED') {
      sqlOwnedWrite(`UPDATE template SET status='ARCHIVED', updated_at=now()
                      WHERE id='${id}'::uuid AND name LIKE '${TAG}%'`, `归档模板 ${name}`, 1);
    }
    const tabs = sqlScalar(`SELECT count(*)::text FROM template_component WHERE template_id='${id}'`);
    lines.push(`- template ${name} / ${id} → ARCHIVED（${tabs} 个 template_component 行**待回收**）`);
  } catch (e) {
    lines.push(`- 🚨 template ${id} 归档失败：${String(e).slice(0, 200)} —— **未归档，请主线处理**`);
  }

  // ③ 报价单 —— 🚦 无归档态，且 DELETE 属红线 ⇒ **只登记，不执行**
  for (const q of OWNED.quotationIds) {
    const row = sqlScalar(`SELECT coalesce(name,'') || '|' || coalesce(status,'') FROM quotation WHERE id='${q.id}'`);
    if (!row) { lines.push(`- quotation ${q.number} / ${q.id} 已不存在，跳过`); continue; }
    const [name, status] = row.split('|');
    lines.push(
      `- quotation ${q.number} / ${q.id} / ${name} / ${status} → **待回收（未执行）**：` +
      `报价单没有归档态，删除走 \`DELETE /api/cpq/quotations/${q.id}\`，属 §3.2 红线，需用户批准`);
  }

  const text = lines.join('\n') + '\n';
  writeEvidence('99-待回收清单.md', text);
  console.log(text);
  return text;
}

// ═══════════════════════════════════════════════════════════════════
// 8. UI —— 取数配置器（选择器全部沿用既有 e2e 的实证结论）
// ═══════════════════════════════════════════════════════════════════

/** antd 会在两个汉字之间插空格（历史坑：「保 存」）⇒ 两字按钮一律用容忍空白的正则。 */
export function cjkBtn(text: string): RegExp {
  return new RegExp(`^\\s*[＋+]?\\s*${text.split('').join('\\s*')}\\s*$`);
}

export async function uiLogin(page: Page) {
  const { loginAsAdmin } = await import('./fixtures/auth');
  await loginAsAdmin(page);
}

/** 切 Tab。坑：弹层打开时 `.ant-modal-wrap` 拦所有 pointer 事件 ⇒ 先 Escape；用 role=tab 比 getByText 稳。 */
export async function switchTab(page: Page, name: string) {
  for (let i = 0; i < 4; i++) {
    if (await page.locator('.ant-modal-wrap').filter({ visible: true }).count() === 0) break;
    await page.keyboard.press('Escape');
    await page.waitForTimeout(400);
  }
  const tab = page.getByRole('tab', { name, exact: true }).first();
  await expect(tab, `找不到 Tab「${name}」⇒ **入口问题**，本条判【未验证】，不是产品缺陷`)
    .toBeVisible({ timeout: 15_000 });
  await tab.click();
  await page.waitForTimeout(2000);
}

/**
 * 打开组件。组件管理页是「目录树 + 右侧详情」，🚫 不是表格。
 *
 * 🚨 **按 `code` 搜，不按 `name` 搜**（`task260908-s?.helpers` 实证）：
 *    「产品」「BOM」「材质元素」这类名字**全库重名**（AC-15 点名的 `COMP-2299` 就叫「BOM」），
 *    按名字搜可能打开**另一个方言的同名组件** —— 那种错误不报错，只会让断言在错的组件上跑。
 *    ⇒ 传了 `code` 就用 `code` 搜，再用 `name` 校验打开的确实是它。
 */
export async function openComponentByName(page: Page, name: string, code?: string) {
  await page.goto('/components');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);
  const search = page.locator('input[placeholder*="搜索"]').first();
  await expect(search, '组件管理页应有搜索框（placeholder 含「搜索」）⇒ 取不到判【未验证】')
    .toBeVisible({ timeout: 15_000 });
  const key = code || name;
  await search.fill(key);
  await page.waitForTimeout(2500);
  // 搜到的卡片可能落在**折叠的目录**里 —— 那时报的是 `hidden`（元素在、不可见），
  // 与 `not found`（不存在）是两种完全不同的成因，别混为一谈
  for (let i = 0; i < 20; i++) {
    const closed = page.locator('.cmm-dir:not(.open) .cmm-dir-head');
    if (await closed.count() === 0) break;
    await closed.first().click({ timeout: 5000 }).catch(() => {});
    await page.waitForTimeout(250);
  }
  await page.waitForTimeout(600);
  const hit = page.getByText(key, { exact: false }).first();
  await expect(hit, `搜不到组件「${name}」(${key}) ⇒ **入口/夹具问题**（未挂目录或已被归档），本条判【未验证】`)
    .toBeVisible({ timeout: 15_000 });
  await hit.click({ force: true, timeout: 15_000 });
  await page.waitForTimeout(2000);
  await expect(page.locator('.cmm-detail-head'), `打开的不是「${name}」`).toContainText(name, { timeout: 10_000 });
}

/** 打开某组件的「取数配置」Tab，并确认面板真的渲染出来了（否则后面全是空跑）。 */
export async function openBuilder(page: Page, componentName: string, code?: string) {
  await openComponentByName(page, componentName, code);
  await switchTab(page, '取数配置');
  await page.waitForTimeout(2500);
  const panel = page.locator('.svb-recipe-bar').first();
  await expect(panel, '取数配置面板（.svb-recipe-bar）没渲染出来 ⇒ 后面的断言**全是空跑**，本条判【未验证】')
    .toBeVisible({ timeout: 15_000 });
}

/** 执行一个会触发重新编译的动作，并**等到 `/builder/compile` 真的回来且 2xx**。
 *  🚨 compile 400 时前端会**继续显示上一次的 SQL/列** ⇒ 不等它，断言就是在读旧结果（恒绿）。 */
export async function withCompile(page: Page, action: () => Promise<void>, why: string): Promise<any | null> {
  const pending = page.waitForResponse((r) => /\/builder\/compile(\?|$)/.test(r.url()), { timeout: 25_000 })
    .catch(() => null);
  await action();
  const resp = await pending;
  let parsed: any = null;
  if (resp) {
    const text = await resp.text().catch(() => '');
    expect(resp.ok(), `${why}：/builder/compile 返回 ${resp.status()} ⇒ 面板显示的是**上一次的结果**，`
      + `此时任何断言都在读旧值。响应=${text.slice(0, 400)}`).toBe(true);
    try { parsed = JSON.parse(text); } catch { parsed = null; }
  } else {
    console.log(`[量具] ${why}：未捕获到 /builder/compile 响应（可能是本地编译）。若后续断言异常，先怀疑这里。`);
  }
  await page.waitForTimeout(900);
  return parsed;
}

/** 选数据源。 */
export async function selectSource(page: Page, sourceLabel: string) {
  const sel = page.locator('[data-role="builder-source"]').first();
  await expect(sel, '应存在「数据源」下拉（data-role="builder-source"）⇒ 取不到判【未验证】')
    .toBeVisible({ timeout: 10_000 });
  await withCompile(page, async () => {
    await sel.click();
    await page.waitForTimeout(400);
    const option = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')
      .filter({ hasText: new RegExp(`^${sourceLabel}$`) }).first();
    await expect(option, `数据源下拉里找不到「${sourceLabel}」`).toBeVisible({ timeout: 10_000 });
    await option.click();
  }, `选数据源「${sourceLabel}」`);
  await page.waitForTimeout(1200);
}

/**
 * 选数据集（报价 / 基础核价 / 明细核价）。
 * 阳性对照：面板自己打印的「表前缀 ds_xxx_」是**数据集独有**的信号 ——
 * 中文显示名三套大量重名，用它判「切过去了」证不出东西（task260819v9 实证）。
 */
export async function selectDataset(page: Page, dialect: keyof typeof DIALECT_LABEL): Promise<string> {
  const label = DIALECT_LABEL[dialect];
  const bar = page.locator('.svb-recipe-bar').first();
  let target = bar.getByText(label, { exact: true }).first();
  if (await target.count() === 0) target = page.getByText(label, { exact: true }).first();
  await expect(target, `取数面板里找不到数据集「${label}」⇒ **入口问题**，本条判【未验证】`)
    .toBeVisible({ timeout: 10_000 });
  await target.click();
  const confirm = page.getByRole('button', { name: /确\s*定|确\s*认/ }).first();
  if (await confirm.isVisible().catch(() => false)) await confirm.click();
  await page.waitForTimeout(2000);
  const body = await page.locator('body').innerText();
  const m = body.match(/表前缀\s*(ds_[a-z_]+_)/);
  expect(m,
    `切到数据集「${label}」后读不到「表前缀 ds_xxx_」标记 —— 该标记是判定「数据集真的切过去了」的唯一精确信号。\n` +
    `  读不到 ⇒ 判据需重新校准，本条判【未验证】，不是产品缺陷。`).not.toBeNull();
  console.log(`[数据集] ${label} → 表前缀 ${m![1]}`);
  return m![1];
}

/** 展开左侧全部字段分组。🚨 折叠是 CSS `display:none`，不展开则 `toBeVisible()` 报 hidden。 */
export async function expandAllGroups(page: Page): Promise<number> {
  for (let i = 0; i < 200; i++) {
    const collapsed = page.locator('.svb-grp.collapsed');
    if (await collapsed.count() === 0) break;
    await collapsed.first().locator('.svb-grp-h').click({ timeout: 5000 }).catch(() => {});
    await page.waitForTimeout(180);
  }
  return page.locator('.svb-grp.collapsed').count();
}

/** 已选输出列的行（多路兜底：既有 e2e 用 data-role，原型图画的是表格行）。 */
export function selectedColumnRows(page: Page): Locator {
  return page.locator(
    '[data-role="selected-column"], .selected-column-row, .svb-selected-list tr[data-col], .svb-sel-col',
  );
}

export async function selectedColumnCount(page: Page): Promise<number> {
  return selectedColumnRows(page).count();
}

/** 把左侧某字段加入「已选输出列」（既有 spec 统一用双击）。加完断言数量真的 +1（防「根本没加进去」的空跑）。 */
export async function addField(page: Page, fieldLabel: string): Promise<void> {
  const before = await selectedColumnCount(page);
  await withCompile(page, async () => {
    const f = page.locator('.svb-grp').getByText(fieldLabel, { exact: true }).locator('visible=true').first();
    await expect(f, `字段面板里找不到可见的「${fieldLabel}」（若报 hidden ⇒ 分组没展开，不是字段缺失）`)
      .toBeVisible({ timeout: 10_000 });
    await f.dblclick();
  }, `加列「${fieldLabel}」`);
  const after = await selectedColumnCount(page);
  expect(after,
    `加「${fieldLabel}」后「已选输出列」数量没变（${before} → ${after}）⇒ 这一列**根本没加进去**，`
    + `后面所有关于它的断言都会是空跑`).toBeGreaterThan(before);
}

/**
 * 点「保 存」。
 * 🚨 页面上有【两个】「保 存」：第 0 个是**组件级**保存，最后一个才是**取数配置**的。
 *    用 `.first()` 会点到组件级那个 —— 配置不落库，随后读回 columns 为空，
 *    症状是「保存后数据丢了」，**长得像产品缺陷**（2026-09-07 实测结论）。
 */
export async function clickBuilderSave(page: Page) {
  const saveBtns = page.locator('button').filter({ hasText: /^保\s*存$/ });
  const n = await saveBtns.count();
  expect(n, '找不到「保 存」按钮 ⇒ **入口问题**，本条判【未验证】').toBeGreaterThan(0);
  console.log(`[save] 「保 存」按钮个数 = ${n} → 点最后一个（取数配置面板的那个）`);
  await saveBtns.last().click();
  await page.waitForTimeout(3500);
}

// ═══════════════════════════════════════════════════════════════════
// 9. UI —— 「字段类型」选择器（AC-1 / AC-2 / AC-11 的量具）
//
// 🚦 契约请求（已报主线）：前端若给选择器加上
//      `data-role="field-type-select"`（逐列）
//      `data-role="bulk-field-type"` + `data-role="apply-field-type-all"`（整列批量）
//    本片优先用它们；取不到时退到「按可见文本兜底」，并在日志里说明用的是哪条路径。
//    🚫 两条路径都取不到 ⇒ **硬失败并 dump DOM**，判【未验证】，不猜成产品缺陷。
// ═══════════════════════════════════════════════════════════════════

/**
 * 读「已选输出列」每一行显示的**字段名**（AC-15 要把 UI 行映射回具体字段）。
 * 🚫 取不到时返回空数组，由调用方降级到「多重集比对」并**在日志里写明用的是哪条路径** ——
 *    降级的是量具精度，不是断言强度。
 */
export async function readSelectedColumnFieldNames(page: Page): Promise<string[]> {
  const rows = selectedColumnRows(page);
  const n = await rows.count();
  const out: string[] = [];
  for (let i = 0; i < n; i++) {
    const row = rows.nth(i);
    const byRole = row.locator('[data-role="field-name"]');
    let v = '';
    if (await byRole.count() > 0) {
      v = ((await byRole.first().innerText().catch(() => '')) || '').replace(/\s+/g, '').trim();
    } else {
      // 兜底：原型 `01` 的表格列序是 [拖拽柄, 来源列, 字段名, 字段类型, 数据类型, 角色]
      //       ⇒ 字段名是「字段类型选择器所在单元格」的前一格
      const cells = row.locator('td');
      const c = await cells.count();
      if (c >= 3) v = ((await cells.nth(2).innerText().catch(() => '')) || '').replace(/\s+/g, '').trim();
    }
    out.push(v);
  }
  return out.every((x) => x === '') ? [] : out;
}

/** 某一行的字段类型选择器。 */
export async function fieldTypeSelectOf(page: Page, rowIndex: number): Promise<Locator> {
  const row = selectedColumnRows(page).nth(rowIndex);
  await expect(row, `已选输出列没有第 ${rowIndex + 1} 行 ⇒ **量具/入口问题**，判【未验证】`)
    .toBeVisible({ timeout: 10_000 });
  const byRole = row.locator('[data-role="field-type-select"]');
  if (await byRole.count() > 0) return byRole.first();

  // 兜底：行内文本恰为三个标签之一的 antd Select
  const selects = row.locator('.ant-select');
  const n = await selects.count();
  for (let i = 0; i < n; i++) {
    const t = ((await selects.nth(i).innerText().catch(() => '')) || '').replace(/\s+/g, '');
    if (Object.values(FT_LABEL).includes(t)) return selects.nth(i);
  }
  const dump = (await row.innerHTML().catch(() => '')).slice(0, 1500);
  throw new Error(
    `第 ${rowIndex + 1} 行里定位不到「字段类型」选择器。\n` +
    `  试过：[data-role="field-type-select"] / 行内文本为 ${JSON.stringify(Object.values(FT_LABEL))} 的 .ant-select\n` +
    `  行 HTML 片段=${dump}\n` +
    `  🚨 这是**量具未校准或入口问题** ⇒ 判【未验证】，🚫 不得记成产品缺陷。` +
    `请主线确认前端是否已加 data-role="field-type-select"。`,
  );
}

/** 读某一行选择器的当前值（返回枚举值，不是标签）。 */
export async function readFieldType(page: Page, rowIndex: number): Promise<FieldType> {
  const sel = await fieldTypeSelectOf(page, rowIndex);
  const item = sel.locator('.ant-select-selection-item').first();
  const raw = ((await item.count()) > 0
    ? await item.innerText()
    : await sel.innerText()).replace(/\s+/g, '');
  const ft = LABEL_TO_FT[raw];
  expect(ft,
    `第 ${rowIndex + 1} 行选择器读到的文案是 ${JSON.stringify(raw)}，不在三个合法标签 ` +
    `${JSON.stringify(Object.values(FT_LABEL))} 里 ⇒ 要么量具读错了元素，要么值域超出 AC-1 规定。` +
    `先分清是哪一种再下结论。`).toBeTruthy();
  return ft;
}

export async function readAllFieldTypes(page: Page): Promise<FieldType[]> {
  const n = await selectedColumnCount(page);
  expect(n, '「已选输出列」为 0 ⇒ 逐列断言会**空跑**，本条判【未验证】').toBeGreaterThan(0);
  const out: FieldType[] = [];
  for (let i = 0; i < n; i++) out.push(await readFieldType(page, i));
  return out;
}

/**
 * 展开某一行的选择器，返回**选项文案数组**。
 * 🚨 防「断言从未执行」：先断言下拉真的展开且**选项数 > 0**，再让调用方去数 3 项 ——
 *    选择器没渲染出来时 `allInnerTexts()` 返回空数组，「恰好 3 项」会以**读到空数组**的方式假绿/假红。
 */
export async function openFieldTypeOptions(page: Page, rowIndex: number): Promise<string[]> {
  const sel = await fieldTypeSelectOf(page, rowIndex);
  await sel.click({ force: true });
  await page.waitForTimeout(800);
  const ddSel = '.ant-select-dropdown:not(.ant-select-dropdown-hidden)';
  const optSel = `${ddSel} .ant-select-item-option`;
  for (let i = 0; i < 12 && (await page.locator(optSel).count()) === 0; i++) await page.waitForTimeout(400);
  const count = await page.locator(optSel).count();
  expect(count,
    `第 ${rowIndex + 1} 行的字段类型下拉展开后**一个选项都没有** ⇒ 「恰好 3 项」这条断言会在空数组上跑，` +
    `那是四类假绿之首（断言从未执行）。本条判【未验证】。`).toBeGreaterThan(0);
  // 虚拟滚动兜底：滚动累加，防选项被漏读
  const seen: string[] = [];
  const holder = page.locator(`${ddSel} .rc-virtual-list-holder`).first();
  for (let i = 0; i < 8; i++) {
    const texts = await page.locator(optSel).allInnerTexts();
    let added = false;
    for (const t of texts.map((x) => x.replace(/\s+/g, ' ').trim()).filter(Boolean)) {
      if (!seen.includes(t)) { seen.push(t); added = true; }
    }
    const done = await holder.evaluate((el) => el.scrollTop + el.clientHeight >= el.scrollHeight - 1)
      .catch(() => true);
    if (done && !added) break;
    await holder.evaluate((el) => { el.scrollTop += el.clientHeight; }).catch(() => {});
    await page.waitForTimeout(200);
  }
  return seen;
}

/** 在某一行选中某个字段类型。 */
export async function setFieldType(page: Page, rowIndex: number, ft: FieldType) {
  await openFieldTypeOptions(page, rowIndex);
  const opt = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')
    .filter({ hasText: new RegExp(FT_LABEL[ft]) }).first();
  await expect(opt, `下拉里找不到选项「${FT_LABEL[ft]}」`).toBeVisible({ timeout: 8000 });
  await opt.click();
  await page.waitForTimeout(700);
  const got = await readFieldType(page, rowIndex);
  expect(got, `第 ${rowIndex + 1} 行选完「${FT_LABEL[ft]}」后读回的是 ${got} ⇒ 选择没生效`).toBe(ft);
}

/**
 * 整列批量设为（AC-2）。原型 `01` 的交互是**两步**：先在工具栏选值，再点「应用到全部列」
 * （两步是刻意设计，避免误触一次改掉全部）。
 */
export async function bulkSetFieldType(page: Page, ft: FieldType) {
  // 值选择器
  let bulk = page.locator('[data-role="bulk-field-type"]').first();
  if (await bulk.count() === 0) {
    const hint = page.getByText('整列批量设为', { exact: false }).first();
    await expect(hint,
      `找不到「整列批量设为」入口（也没有 data-role="bulk-field-type"）⇒ **量具未校准或功能缺失**。\n` +
      `  🚨 两者必须分清：先确认前端 DOM 契约，再判 AC-2 是否达成。`).toBeVisible({ timeout: 10_000 });
    bulk = hint.locator('xpath=following::*[contains(@class,"ant-select")][1]');
  }
  await bulk.click({ force: true });
  await page.waitForTimeout(700);
  const opt = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')
    .filter({ hasText: new RegExp(FT_LABEL[ft]) }).first();
  await expect(opt, `批量下拉里找不到「${FT_LABEL[ft]}」`).toBeVisible({ timeout: 8000 });
  await opt.click();
  await page.waitForTimeout(500);

  // 应用按钮
  let apply = page.locator('[data-role="apply-field-type-all"]').first();
  if (await apply.count() === 0) {
    apply = page.locator('button').filter({ hasText: /应用到全部列/ }).first();
  }
  await expect(apply,
    `找不到「应用到全部列」按钮（也没有 data-role="apply-field-type-all"）⇒ **量具未校准或功能缺失**，先分清再下结论`)
    .toBeVisible({ timeout: 10_000 });
  await apply.click();
  await page.waitForTimeout(1200);
}

// ═══════════════════════════════════════════════════════════════════
// 10. UI —— 报价单 Step2 产品卡片（AC-6 / 7 / 10 / 12 / 14 的量具）
// ═══════════════════════════════════════════════════════════════════

export async function gotoStep2(page: Page, quotationId: string) {
  await page.goto(`/quotations/${quotationId}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1800);
  const next = page.locator('button').filter({ hasText: /下\s*一\s*步|继续/ }).first();
  if ((await next.count()) > 0 && (await next.isEnabled().catch(() => false))) {
    await next.click().catch(() => {});
    await page.waitForTimeout(2500);
  }
  await expect(page.locator('.qt-product-card').first(),
    '进不到 Step2 产品卡片（`.qt-product-card` 一个都没有）⇒ **入口问题**，本条判【未验证】，🚫 不得记成产品缺陷')
    .toBeVisible({ timeout: 20_000 });
}

export async function switchToCosting(page: Page) {
  const seg = page.locator('.ant-segmented-item', { hasText: '核价单' }).first();
  await expect(seg, '找不到「核价单」切换入口 ⇒ 入口问题，判【未验证】').toBeVisible({ timeout: 15_000 });
  await seg.click();
  await page.waitForTimeout(1500);
  const cardSeg = page.locator('.ant-segmented-item', { hasText: '产品卡片' }).first();
  if ((await cardSeg.count()) > 0) { await cardSeg.click().catch(() => {}); await page.waitForTimeout(1200); }
  await page.waitForTimeout(2500);
}

export async function switchToQuote(page: Page) {
  const seg = page.locator('.ant-segmented-item', { hasText: '报价单' }).first();
  if ((await seg.count()) > 0) { await seg.click().catch(() => {}); await page.waitForTimeout(1500); }
  const cardSeg = page.locator('.ant-segmented-item', { hasText: '产品卡片' }).first();
  if ((await cardSeg.count()) > 0) { await cardSeg.click().catch(() => {}); await page.waitForTimeout(1200); }
  await page.waitForTimeout(2000);
}

/** 按料号定位产品卡片。🚫 不用 `.nth(i)` 的位置口径 —— 靠位置定位会给出「错卡片上的正确断言」。 */
export async function cardOf(page: Page, partNo: string): Promise<Locator> {
  const all = page.locator('.qt-product-card');
  const n = await all.count();
  const heads: string[] = [];
  const hits: number[] = [];
  const strict = new RegExp(`料号[:：]\\s*${partNo}(?![0-9A-Za-z_])`);
  for (let i = 0; i < n; i++) {
    const head = (await all.nth(i).innerText().catch(() => '')).split('\n').slice(0, 6).join(' / ');
    heads.push(`[${i}] ${head.slice(0, 160)}`);
    if (strict.test(head)) hits.push(i);
  }
  if (hits.length !== 1) {
    throw new Error(
      `按料号 ${partNo} 定位产品卡片失败：命中 ${hits.length} 张（共 ${n} 张）。\n卡片头快照：\n${heads.join('\n')}\n` +
      `🚨 这是**定位/入口**问题（或卡片压根没渲染）⇒ 判【未验证】。`);
  }
  const card = all.nth(hits[0]);
  await card.scrollIntoViewIfNeeded().catch(() => {});
  await page.waitForTimeout(600);
  return card;
}

export async function switchTabInCard(card: Locator, tabName: string) {
  const btn = card.locator('button.qt-tab-btn').filter({ hasText: new RegExp(`^\\s*${tabName}\\s*$`) }).first();
  if (!(await btn.count())) {
    const all = await card.locator('button.qt-tab-btn').allInnerTexts();
    throw new Error(
      `卡片内找不到页签「${tabName}」。实有页签=${JSON.stringify(all.map((s) => s.trim()))}\n` +
      `🚨 入口/选择器问题 ⇒ 判【未验证】。`);
  }
  await btn.click();
  await card.page().waitForTimeout(2500);
}

export interface TabSnapshot {
  headers: string[];
  /** 每个单元格的**可见值**：文本 + 表单控件的 value（见下方注释）。 */
  rows: string[][];
  /** 每个单元格里承载值的 `input/select/textarea` 个数（AC-7 的判据）。 */
  inputCounts: number[][];
  /** 整个页签内的 input/select/textarea 总数 */
  totalInputs: number;
}

/**
 * 读当前卡片内活动页签的表格快照。
 *
 * 🚨 **AC-6 的假绿风险②**：单元格可能是 `<input>`，`innerText` **读不到它的 value**
 *    （上一个任务的测试员在这里栽过：4 行真数据被读成 `["","","",""]`，
 *     于是「两侧逐字相同」以**两侧都空**的方式恒真）。
 *    ⇒ 取值时必须同时抓 `input/select/textarea` 的 `value`。
 */
export async function readTab(card: Locator): Promise<TabSnapshot> {
  const table = card.locator('table').first();
  await expect(table, '卡片内没有表格 ⇒ 入口/渲染问题，判【未验证】').toBeVisible({ timeout: 15_000 });
  const headers = (await table.locator('thead th').allInnerTexts()).map((s) => s.replace(/\s+/g, ' ').trim());
  const data = await table.evaluate((tbl) => {
    const cellValue = (td: HTMLElement): { text: string; inputs: number } => {
      const ctrls = Array.from(td.querySelectorAll('input, select, textarea')) as HTMLInputElement[];
      // 只统计**承载值**的控件：勾选框/单选不算「输入框」
      const valueCtrls = ctrls.filter((c) => !['checkbox', 'radio', 'button'].includes((c.type || '').toLowerCase()));
      const fromCtrl = valueCtrls.map((c) => (c.value ?? '')).filter((v) => v !== '');
      const text = (td.innerText || '').replace(/\s+/g, ' ').trim();
      return { text: (text || fromCtrl.join(' ')).trim() || fromCtrl.join(' ').trim(), inputs: valueCtrls.length };
    };
    const trs = Array.from(tbl.querySelectorAll('tbody tr')) as HTMLElement[];
    const rows: string[][] = [];
    const inputCounts: number[][] = [];
    for (const tr of trs) {
      const tds = Array.from(tr.querySelectorAll('td')) as HTMLElement[];
      const rv = tds.map(cellValue);
      rows.push(rv.map((x) => x.text));
      inputCounts.push(rv.map((x) => x.inputs));
    }
    const totalInputs = (Array.from(tbl.querySelectorAll('input, select, textarea')) as HTMLInputElement[])
      .filter((c) => !['checkbox', 'radio', 'button'].includes((c.type || '').toLowerCase())).length;
    return { rows, inputCounts, totalInputs };
  });
  return { headers, ...data };
}

/** 页签内所有单元格的非空文本个数（阳性对照：AC-6 比对前必须两侧都 > 0）。 */
export function nonEmptyCellCount(t: TabSnapshot): number {
  return t.rows.flat().filter((v) => v !== '' && v !== '—' && v !== '-').length;
}
