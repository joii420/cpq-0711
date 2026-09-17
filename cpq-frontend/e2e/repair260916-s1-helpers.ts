/**
 * repair-260916 · 测试分片 S-1（只读片）共用助手。
 *
 * 纪律（test.md §1 / 派工 prompt e 段）：
 *  - 本片零写入：HTTP 只调 refresh-all-snapshots {recompile:true, confirm:false} 与 /builder/compile（+ 登录）；
 *  - SQL 只连 cpq_db_260916，且每个 psql 会话先 `SET default_transaction_read_only = on`（库层面强制只读）；
 *  - 用例只从 问题说明.md ⑥ 的 AC 原文派生，不读实现代码。
 */
import { expect, request as pwRequest, type APIRequestContext } from '@playwright/test';
import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';

// ---------------------------------------------------------------- 坐标
export const API = process.env.R260916_API || 'http://localhost:8196';
export const API_PORT = Number(new URL(API).port || 80);
export const DB = 'cpq_db_260916';
const PG = { host: '10.177.152.12', port: '5432', user: 'postgres', password: process.env.R260916_PGPASSWORD || 'joii5231' };

// 本机 shell 带 http_proxy，访问本机一律绕开代理
process.env.NO_PROXY = 'localhost,127.0.0.1';
process.env.no_proxy = 'localhost,127.0.0.1';

/** 任务目录（从 cpq-frontend 目录下运行）。 */
export const TASK_DIR = path.resolve(process.cwd(), '..', 'dev-docs', 'task-260908-取数配置器优化', 'repair-260916-来料类材料名补查材质表');
export const BASELINE_DIR = path.join(TASK_DIR, '证据', '基线-改动前');
export const EVIDENCE_DIR = path.join(TASK_DIR, '证据');
export const FIXTURE_DIR = path.join(TASK_DIR, '夹具', 's1');
export const OUT_DIR = path.join(EVIDENCE_DIR, 'S-1');
export const SET_A_FILE = path.join(EVIDENCE_DIR, 'AC-11-集合A.json');

export function assertTaskDirs() {
  for (const d of [TASK_DIR, BASELINE_DIR, FIXTURE_DIR]) {
    expect(fs.existsSync(d), `目录必须存在（请在 worktree 的 cpq-frontend 下运行）：${d}`).toBe(true);
  }
  fs.mkdirSync(OUT_DIR, { recursive: true });
}

export function writeEvidence(name: string, content: unknown) {
  fs.mkdirSync(OUT_DIR, { recursive: true });
  const p = path.join(OUT_DIR, name);
  fs.writeFileSync(p, typeof content === 'string' ? content : JSON.stringify(content, null, 2) + '\n', 'utf8');
  console.log(`[evidence] ${p}`);
  return p;
}

// ---------------------------------------------------------------- 11 个数据源（AC-1 表，原文逐字）
export const DS11: Array<{ dialect: string; nodeKey: string; anchor: string }> = [
  ...['INCOMING_FIXED_FEE', 'INCOMING_OTHER_FEE', 'INCOMING_RECOVERY', 'INCOMING_ANNUAL', 'SELF_PROCESS_FEE']
    .map(k => ({ dialect: 'QUOTE', nodeKey: k, anchor: 'input_material_no' })),
  ...['INCOMING_OTHER_FEE', 'INCOMING_OTHER_FIXED_FEE', 'INCOMING_PROCESS_FEE']
    .map(k => ({ dialect: 'COST_BASIC', nodeKey: k, anchor: 'incoming_material_no' })),
  ...['INCOMING_OTHER_FEE', 'INCOMING_OTHER_FIXED_FEE', 'INCOMING_PROCESS_FEE']
    .map(k => ({ dialect: 'COST_DETAIL', nodeKey: k, anchor: 'incoming_material_no' })),
];
/** AC-1 ①：物料表连线迁移前的连接键（原文逐字）。 */
export function expectedMatKeys(dialect: string): string {
  return dialect === 'QUOTE'
    ? '0:input_material_no=material_no;1:customer_no=customer_no'
    : '0:incoming_material_no=production_no';
}

// ---------------------------------------------------------------- 只读 SQL
/** 执行一条 SELECT/WITH，返回行对象数组。会话强制只读；拒绝非 SELECT。 */
export function sql<T = Record<string, any>>(query: string): T[] {
  const q = query.trim().replace(/;+\s*$/, '');
  if (!/^(select|with)\b/i.test(q)) throw new Error(`S-1 只允许 SELECT/WITH：${q.slice(0, 60)}`);
  const out = execFileSync('psql', [
    '-h', PG.host, '-p', PG.port, '-U', PG.user, '-d', DB,
    '-X', '-A', '-t', '-q', '-v', 'ON_ERROR_STOP=1',
    '-c', 'SET default_transaction_read_only = on',
    '-c', `SELECT coalesce(jsonb_agg(q), '[]'::jsonb)::text FROM (${q}) q`,
  ], { env: { ...process.env, PGPASSWORD: PG.password }, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });
  const lines = out.split('\n').map(s => s.trim()).filter(Boolean);
  if (lines.length !== 1) throw new Error(`psql 输出应恰 1 行（jsonb 单行），实为 ${lines.length} 行：${q.slice(0, 80)}`);
  const line = lines[0];
  return JSON.parse(line) as T[];
}

/** 连到的必须是验收库（防连错库）。 */
export function assertDbIdentity() {
  const r = sql<{ db: string }>('SELECT current_database() AS db');
  expect(r[0]?.db, 'psql 必须连 cpq_db_260916').toBe(DB);
}

// ---------------------------------------------------------------- 零写入观察手段（AC-9 / AC-11 前置）
export interface WriteProbe { [k: string]: string | number }
/** 对本片关心的写入面取指纹（整行文本 md5 + 计数）。调用时机：登录之后、被测调用之前/之后。 */
export function writeProbe(): WriteProbe {
  const r = sql<WriteProbe>(`
    SELECT
      (SELECT md5(coalesce(string_agg(t::text, E'\\n' ORDER BY t.id), '')) FROM component_sql_view t) AS component_sql_view_md5,
      (SELECT count(*) FROM component_sql_view) AS component_sql_view_rows,
      (SELECT md5(coalesce(string_agg(t::text, E'\\n' ORDER BY t.id), '')) FROM component t) AS component_md5,
      (SELECT md5(coalesce(string_agg(t::text, E'\\n' ORDER BY t.id), '')) FROM template t) AS template_md5,
      (SELECT md5(coalesce(string_agg(t::text, E'\\n' ORDER BY t.id), '')) FROM template_component_snapshot t) AS template_component_snapshot_md5,
      (SELECT md5(coalesce(string_agg(t::text, E'\\n' ORDER BY t.id), '')) FROM semantic_edge t) AS semantic_edge_md5,
      (SELECT count(*) FROM operation_log) AS operation_log_rows,
      (SELECT coalesce(max(created_at)::text, '') FROM operation_log) AS operation_log_max_created
  `);
  expect(r.length, '写入指纹查询应返回 1 行').toBe(1);
  return r[0];
}

// ---------------------------------------------------------------- HTTP
export async function adminApi(): Promise<APIRequestContext> {
  const ctx = await pwRequest.newContext({ baseURL: API, extraHTTPHeaders: { 'Content-Type': 'application/json' } });
  const res = await ctx.post('/api/cpq/auth/login', { data: { username: 'admin', password: 'Admin@2026' } });
  const body = await res.text();
  expect(res.status(), `admin 登录应 200（若被锁请报主线，禁止改 user 表）；原文：${body.slice(0, 300)}`).toBe(200);
  const j = JSON.parse(body);
  console.log(`[login] role=${j?.data?.role} forceChangePassword=${j?.data?.forceChangePassword}`);
  expect(j?.data?.role, 'admin 应为 SYSTEM_ADMIN').toBe('SYSTEM_ADMIN');
  return ctx;
}

/**
 * 探活验明正身（testing.md §4.2）：
 *  ① 8196 监听进程的环境/命令行里点名 cpq_db_260916（能区分「连的是 0724」—— 两库 quotation 行数相同，①是唯一强判别）；
 *  ② GET /quotations totalElements = 验收库 quotation 行数（test.md §0.2 规定口径；单独用它无法区分 0724 克隆源）；
 *  ③ 验收库 flyway V444 success=t；④ 验收库上有本进程以外的连接。
 */
export async function assertBackendIdentity(ctx: APIRequestContext) {
  // ①
  const ss = execFileSync('ss', ['-ltnpH', `sport = :${API_PORT}`], { encoding: 'utf8' });
  const pid = /pid=(\d+)/.exec(ss)?.[1];
  expect(pid, `端口 ${API_PORT} 上应有监听进程；ss 输出：${ss}`).toBeTruthy();
  const environ = fs.readFileSync(`/proc/${pid}/environ`, 'utf8').split('\0');
  const cmdline = fs.readFileSync(`/proc/${pid}/cmdline`, 'utf8').split('\0').join(' ');
  const dbName = environ.find(e => e.startsWith('DB_NAME='))?.slice('DB_NAME='.length);
  console.log(`[identity] pid=${pid} DB_NAME=${dbName} cmdline~db=${/cpq_db_\w+/.exec(cmdline)?.[0]}`);
  const named = dbName ?? /cpq_db_\w+/.exec(cmdline)?.[0];
  expect(named, `8196 进程必须点名连 ${DB}（环境变量 DB_NAME 或命令行）`).toBe(DB);
  // ②
  const res = await ctx.get('/api/cpq/quotations?page=1&size=1');
  expect(res.status(), 'GET /quotations 应 200').toBe(200);
  const j = await res.json();
  const total = Number(j?.data?.totalElements ?? j?.totalElements);
  const cnt = Number(sql<{ c: number }>('SELECT count(*) AS c FROM quotation')[0].c);
  console.log(`[identity] api totalElements=${total} db quotation=${cnt}`);
  expect(Number.isFinite(total) && total > 0, 'totalElements 应为正数').toBe(true);
  expect(total).toBe(cnt);
  // ③
  const fw = sql<{ success: boolean }>(`SELECT success FROM flyway_schema_history WHERE version = '444'`);
  console.log(`[identity] flyway 444 = ${JSON.stringify(fw)}`);
  expect(fw.length, '验收库应已有 V444 行（主线启动 8196 时落入）').toBe(1);
  expect(fw[0].success).toBe(true);
  // ④
  const conn = sql<{ c: number }>(`SELECT count(*) AS c FROM pg_stat_activity WHERE datname = '${DB}' AND pid <> pg_backend_pid()`);
  console.log(`[identity] other connections on ${DB} = ${conn[0].c}`);
  expect(Number(conn[0].c), `${DB} 上应有后端连接池连接`).toBeGreaterThan(0);
}

/** 全量重编译**预览**（零写入）。只允许 confirm:false。返回原始文本与解析体。 */
export async function refreshAllPreview(ctx: APIRequestContext): Promise<{ status: number; text: string; json: any }> {
  const body = { recompile: true, confirm: false } as const;
  const res = await ctx.post('/api/cpq/config-center/refresh-all-snapshots', { data: body, timeout: 280_000 });
  const text = await res.text();
  let json: any = null;
  try { json = JSON.parse(text); } catch { /* 留给断言 */ }
  return { status: res.status(), text, json };
}

/** 取数配置器实时编译（不落库）。 */
export async function builderCompile(ctx: APIRequestContext, componentId: string, builderConfig: unknown) {
  const res = await ctx.post(`/api/cpq/components/${componentId}/builder/compile`, { data: builderConfig });
  const text = await res.text();
  let json: any = null;
  try { json = JSON.parse(text); } catch { /* 留给断言 */ }
  return { status: res.status(), text, json };
}

// ---------------------------------------------------------------- 纯函数（可离线证伪）
export function readBaselineJson(name: string): any {
  return JSON.parse(fs.readFileSync(path.join(BASELINE_DIR, name), 'utf8'));
}

/** 简易 CSV（基线文件无引号字段；遇到引号直接报错，防静默误读）。 */
export function readBaselineCsv(name: string): Record<string, string>[] {
  const lines = fs.readFileSync(path.join(BASELINE_DIR, name), 'utf8').split('\n').filter(l => l.length > 0);
  const head = lines[0].split(',');
  return lines.slice(1).map(l => {
    if (l.includes('"')) throw new Error(`基线 CSV 含引号字段，简易解析不适用：${l}`);
    const cells = l.split(',');
    if (cells.length !== head.length) throw new Error(`CSV 列数不符：${l}`);
    return Object.fromEntries(head.map((h, i) => [h, cells[i]]));
  });
}

/** 递归键路径集合（数组的各元素合并到 `[]` 下）。 */
export function keyPaths(o: unknown, prefix = ''): Set<string> {
  const s = new Set<string>();
  const walk = (v: unknown, p: string) => {
    if (Array.isArray(v)) { for (const e of v) walk(e, `${p}[]`); }
    else if (v && typeof v === 'object') {
      for (const [k, c] of Object.entries(v as Record<string, unknown>)) { const kp = p ? `${p}.${k}` : k; s.add(kp); walk(c, kp); }
    }
  };
  walk(o, prefix);
  return s;
}

export function minus<T>(a: Iterable<T>, b: Iterable<T>): T[] {
  const bs = new Set(b);
  return [...new Set(a)].filter(x => !bs.has(x)).sort() as T[];
}

export interface EdgeRow {
  dialect: string; from_key: string; to_key: string; to_dialect?: string; edge_id: string;
  coalesce_group: string | null; fallback_order: number | string | null; status: string; edge_keys: string | null;
}

/**
 * AC-1 判据（纯函数，便于在基线上证伪）。返回违反项列表；空 = 通过。
 * before = 迁移前 46 行（基线 CSV），after = 现查。
 */
export function checkAc1(before: EdgeRow[], after: EdgeRow[], opts: { checkToDialect: boolean }): string[] {
  const v: string[] = [];
  const norm = (x: unknown) => (x === null || x === undefined || x === '' ? null : String(x));
  const byId = new Map(after.map(r => [r.edge_id, r]));
  const beforeIds = new Set(before.map(r => r.edge_id));
  if (before.length === 0) v.push('迁移前样本为空（不可能通过）');
  if (after.length === 0) v.push('现查样本为空');

  const isDs11 = (r: EdgeRow) => DS11.some(d => d.dialect === r.dialect && d.nodeKey === r.from_key);
  const matBefore = before.filter(r => isDs11(r) && r.to_key === 'MAT_NAME_LK');
  if (matBefore.length !== 11) v.push(`迁移前 11 数据源的 MAT_NAME_LK 连线应 11 条，实为 ${matBefore.length}`);

  // 主断言：每个数据源恰 2 条同组 PART_NAME 查名连线
  for (const d of DS11) {
    const tag = `${d.dialect}/${d.nodeKey}`;
    const mine = after.filter(r => r.dialect === d.dialect && r.from_key === d.nodeKey);
    if (mine.length !== 2) { v.push(`${tag}: 查名连线应恰 2 条，实为 ${mine.length} → ${JSON.stringify(mine.map(r => r.to_key))}`); continue; }
    const s1 = mine.find(r => norm(r.fallback_order) === '1');
    const s2 = mine.find(r => norm(r.fallback_order) === '2');
    if (!s1 || !s2) { v.push(`${tag}: fallback_order 应为 {1,2}，实为 ${JSON.stringify(mine.map(r => r.fallback_order))}`); continue; }
    for (const r of mine) if (norm(r.coalesce_group) !== 'PART_NAME') v.push(`${tag}: ${r.to_key} coalesce_group 应 PART_NAME，实为 ${r.coalesce_group}`);
    if (s1.to_key !== 'MAT_NAME_LK') v.push(`${tag}: 顺序 1 应指向 MAT_NAME_LK，实为 ${s1.to_key}`);
    if (s2.to_key !== 'RECIPE_NAME_LK') v.push(`${tag}: 顺序 2 应指向 RECIPE_NAME_LK，实为 ${s2.to_key}`);
    if (opts.checkToDialect) for (const r of mine) if (r.to_dialect !== d.dialect) v.push(`${tag}: ${r.to_key} 应指向本方言节点，实为 ${r.to_dialect}`);
    // 材质连线：seq0 单键 <anchor>=code
    if (norm(s2.edge_keys) !== `0:${d.anchor}=code`) v.push(`${tag}: 材质连线连接键应恰为 "0:${d.anchor}=code"，实为 "${s2.edge_keys}"`);
    // ① 物料连线：同一条（edge_id 与迁移前相同）+ 键逐字相同 + 等于原文期望
    const mb = matBefore.find(r => r.dialect === d.dialect && r.from_key === d.nodeKey);
    if (mb && s1.edge_id !== mb.edge_id) v.push(`${tag}: 物料连线 edge_id 变了 ${mb.edge_id} → ${s1.edge_id}`);
    if (mb && norm(s1.edge_keys) !== norm(mb.edge_keys)) v.push(`${tag}: 物料连线键变了 "${mb.edge_keys}" → "${s1.edge_keys}"`);
    if (norm(s1.edge_keys) !== expectedMatKeys(d.dialect)) v.push(`${tag}: 物料连线键应为 "${expectedMatKeys(d.dialect)}"，实为 "${s1.edge_keys}"`);
    if (beforeIds.has(s2.edge_id)) v.push(`${tag}: 材质连线应为新增连线，但 edge_id ${s2.edge_id} 迁移前已存在`);
  }

  // ② 总数 = 前 + 11；键总数 = 前 + 11
  if (after.length !== before.length + 11) v.push(`查名连线总数应 = ${before.length}+11 = ${before.length + 11}，实为 ${after.length}`);
  const keyCount = (rows: EdgeRow[]) => rows.reduce((n, r) => n + (r.edge_keys ? r.edge_keys.split(';').length : 0), 0);
  if (keyCount(after) !== keyCount(before) + 11) v.push(`连接键总数应 = ${keyCount(before)}+11 = ${keyCount(before) + 11}，实为 ${keyCount(after)}`);
  // 新增的恰是那 11 条材质连线
  const added = after.filter(r => !beforeIds.has(r.edge_id));
  const addedOk = added.filter(r => isDs11(r) && r.to_key === 'RECIPE_NAME_LK');
  if (added.length !== 11 || addedOk.length !== 11) v.push(`新增连线应恰为 11 条 DS11→RECIPE_NAME_LK，实为 ${JSON.stringify(added.map(r => `${r.dialect}/${r.from_key}->${r.to_key}`))}`);

  // ③ 其余 35 条逐条不变
  const others = before.filter(r => !matBefore.includes(r));
  if (others.length !== 35) v.push(`迁移前「其余」查名连线应 35 条，实为 ${others.length}`);
  for (const o of others) {
    const a = byId.get(o.edge_id);
    const tag = `${o.dialect}/${o.from_key}->${o.to_key}(${o.edge_id})`;
    if (!a) { v.push(`${tag}: 迁移后不见了（或非 ACTIVE）`); continue; }
    for (const k of ['dialect', 'from_key', 'to_key', 'coalesce_group', 'fallback_order', 'status', 'edge_keys'] as const) {
      if (norm(a[k]) !== norm(o[k])) v.push(`${tag}: ${k} 变了 "${o[k]}" → "${a[k]}"`);
    }
  }
  return v;
}

/** AC-1 现查 SQL（已在克隆后、V444 前对验收库实跑，与基线 CSV 逐字节相同）。 */
export const AC1_SQL = `
  SELECT f.dialect, f.node_key AS from_key, t.node_key AS to_key, t.dialect AS to_dialect, e.id::text AS edge_id,
         e.coalesce_group, e.fallback_order, e.status,
         (SELECT string_agg(k.seq || ':' || k.left_column || '=' || k.right_column, ';' ORDER BY k.seq)
            FROM semantic_edge_key k WHERE k.edge_id = e.id) AS edge_keys
    FROM semantic_edge e
    JOIN semantic_node f ON f.id = e.from_node_id
    JOIN semantic_node t ON t.id = e.to_node_id
   WHERE e.status = 'ACTIVE' AND e.edge_kind = 'LOOKUP'
     AND t.node_key IN ('MAT_NAME_LK', 'MAT_PROD_LK', 'RECIPE_NAME_LK')
   ORDER BY 1, 2, 3`;

/**
 * AC-11 期望集合 SQL（方言感知）：
 *  builder_config.columns 中有 sourceNodeKey='MAT_NAME_LK' 的列，
 *  且其余列（sourceNodeKey 非空、且不以 _LK 结尾）至少 1 列、全部属于 (本视图 dialect, 11 个数据源)。
 * V444 前在验收库实跑 = 9 个视图（COMP-0004/2413/0005/0008/2296/2414/2424/2425/2426），与 AC 原文开发库实测一致。
 */
export const AC11_EXPECTED_SQL = `
  WITH ds11(dialect, node_key) AS (VALUES ${DS11.map(d => `('${d.dialect}','${d.nodeKey}')`).join(',')}),
  v AS (
    SELECT sv.sql_view_name, c.code AS component_code, sv.builder_config->>'dialect' AS dialect, sv.builder_config->'columns' AS cols
      FROM component_sql_view sv JOIN component c ON c.id = sv.component_id
     WHERE sv.builder_config IS NOT NULL AND jsonb_typeof(sv.builder_config->'columns') = 'array')
  SELECT v.sql_view_name, v.component_code, v.dialect,
         (SELECT string_agg(DISTINCT coalesce(col->>'sourceNodeKey', '<null>'), ',') FROM jsonb_array_elements(v.cols) col) AS source_keys,
         (SELECT count(*) FROM jsonb_array_elements(v.cols) col WHERE col->>'sourceNodeKey' IS NULL) AS null_key_cols
    FROM v
   WHERE EXISTS (SELECT 1 FROM jsonb_array_elements(v.cols) col WHERE col->>'sourceNodeKey' = 'MAT_NAME_LK')
     AND EXISTS (SELECT 1 FROM jsonb_array_elements(v.cols) col
                  WHERE col->>'sourceNodeKey' IS NOT NULL AND col->>'sourceNodeKey' NOT LIKE '%\\_LK' ESCAPE '\\')
     AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements(v.cols) col
                      WHERE col->>'sourceNodeKey' IS NOT NULL AND col->>'sourceNodeKey' NOT LIKE '%\\_LK' ESCAPE '\\'
                        AND NOT EXISTS (SELECT 1 FROM ds11 d WHERE d.dialect = v.dialect AND d.node_key = col->>'sourceNodeKey'))
   ORDER BY v.sql_view_name`;
