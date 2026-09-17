/**
 * repair-260916「来料类材料名补查材质表」· test slice **S-global** shared helpers.
 *
 * Source of truth for every assertion: `问题说明.md ⑥` AC text (AC-2/3/4/6/7/10) + `api.md` + `test.md §3/§4`.
 * 🚫 This file was written WITHOUT reading implementation code
 *    (cpq-backend/src/main/java/**, db/migration/**, cpq-frontend/src/**, deploy/db/update-*.sql).
 *    Selectors come from existing e2e specs (task260908-s1.helpers.ts, t260910.helpers.ts, quotation-flow.spec.ts)
 *    and from a write-blocked read-only browse of the shared 5174 on 2026-09-16.
 *
 * ── Write surface of this slice (test.md §1) ─────────────────────────────────────────
 *   Only on cpq_db_260916 (acceptance clone), only through the product's own API/UI:
 *     · the 3 builder views of COMP-0004/0005/0008 (via POST /config-center/recompile-components confirm=true)
 *     · operation_log rows produced by that call
 *     · one new published version of「施耐德5.4模板」(UI: 创建新草稿 → 发布)
 *     · one new quotation whose project name is `R260916-G-AC10`
 *   🚫 No SQL writes at all: psql here is SELECT-only (hard guard below).
 *   🚫 Never refresh-all-snapshots with confirm:true.
 */
import { expect, Page, APIRequestContext, request as pwRequest } from '@playwright/test';
import { execFileSync, execSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

const HERE = path.dirname(fileURLToPath(import.meta.url)); // ESM: no __dirname (AP-43)

// ═══════════════════════════════════════════════════════════════════
// Coordinates
// ═══════════════════════════════════════════════════════════════════

export const BASE_URL = process.env.PW_BASE_URL || 'http://localhost:5196';
export const BACKEND_URL = process.env.PW_BACKEND_URL || 'http://localhost:8196';
export const DB_NAME = 'cpq_db_260916';
export const TAG = 'R260916-G-';
export const PROJECT_NAME = 'R260916-G-AC10';

export const TASK_DIR = path.resolve(
  HERE, '..', '..', 'dev-docs', 'task-260908-取数配置器优化', 'repair-260916-来料类材料名补查材质表',
);
export const EVID = path.join(TASK_DIR, '证据');
export const EVID_E2E = path.join(EVID, 'e2e');
export const BASELINE_DIR = path.join(EVID, '基线-改动前');
export const MD5_REPORT = path.join(EVID, 'AC-6-7-md5-前后.md');
export const READONLY_MARKER = path.join(EVID_E2E, '.S-global-readonly-passed');
export const AC10_STATE = path.join(EVID_E2E, 'AC-10-state.json');

/** The three target components (立项期实测 §4). The id is authoritative; code/view are cross-checked against the DB. */
export const COMPS = [
  { code: 'COMP-0004', id: '4db28822-85c6-4522-ac62-61ef2393a99c', view: 'builder_c35c2bd590fe', name: '来料固定加工费' },
  { code: 'COMP-0005', id: '57554055-0896-4cc8-be98-65e45b0a5985', view: 'builder_4602c64a0c38', name: '来料其他费用' },
  { code: 'COMP-0008', id: '39fa3d9d-54b9-4605-93ca-dd38cbfa2831', view: 'builder_46f244df7ede', name: '来料回收' },
] as const;
export const COMP_IDS = COMPS.map((c) => c.id);
export const VIEW_NAMES = COMPS.map((c) => c.view);
export const TEMPLATE_NAME = '施耐德5.4模板';
export const CUSTOMER_CODE = 'CUST-0004';
export const MATERIAL_COL = '_物料_材料名'; // AC-3①: column name must stay literally this

// ═══════════════════════════════════════════════════════════════════
// Evidence
// ═══════════════════════════════════════════════════════════════════

export function ensureDirs() { fs.mkdirSync(EVID_E2E, { recursive: true }); }
export function writeEvid(file: string, content: string | object) {
  ensureDirs();
  const p = path.join(EVID_E2E, file);
  fs.writeFileSync(p, typeof content === 'string' ? content : JSON.stringify(content, null, 2), 'utf-8');
  console.log(`[evidence] → ${p}`);
  return p;
}
export function readEvidJson<T = any>(file: string): T | null {
  const p = path.join(EVID_E2E, file);
  if (!fs.existsSync(p)) return null;
  return JSON.parse(fs.readFileSync(p, 'utf-8'));
}
/** Screenshots go straight into the task evidence dir (test-results/ is wiped each run — testing.md §2). */
export async function shot(page: Page, name: string) {
  ensureDirs();
  const p = path.join(EVID_E2E, `${name}.png`);
  await page.screenshot({ path: p, fullPage: true }).catch(() => {});
  console.log(`[shot] → ${p}`);
  return p;
}

// ═══════════════════════════════════════════════════════════════════
// DB: SELECT-only on cpq_db_260916
// ═══════════════════════════════════════════════════════════════════

const PG = { host: '10.177.152.12', port: '5432', user: 'postgres', password: 'joii5231' };

function psqlRaw(sql: string): string {
  const t = sql.trim().replace(/;\s*$/, '');
  if (!/^(select|with)\b/i.test(t) || /;\s*\S/.test(t)) {
    throw new Error(`🚨 S-global psql is SELECT-only; refused: ${t.slice(0, 160)} —— 停下报主线 (CLAUDE.md §3.2)`);
  }
  return execFileSync('psql', ['-h', PG.host, '-p', PG.port, '-U', PG.user, '-d', DB_NAME, '-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-c', t], {
    encoding: 'utf-8', env: { ...process.env, PGPASSWORD: PG.password }, maxBuffer: 256 * 1024 * 1024,
  }).trim();
}
export function sqlScalar(sql: string): string { return psqlRaw(sql).split('\n')[0] ?? ''; }
/** Runs `select coalesce(json_agg(t),'[]') from (<sql>) t` and parses it. */
export function sqlJson<T = any>(sql: string): T[] {
  const out = psqlRaw(`select coalesce(json_agg(t), '[]'::json) from (${sql}) t`);
  return JSON.parse(out || '[]');
}
export function sqlInt(sql: string, why: string): number {
  const raw = sqlScalar(sql);
  const n = Number(raw);
  expect(raw !== '' && Number.isFinite(n), `${why}: query returned ${JSON.stringify(raw)} (not a number) ⇒ gauge broken, 判【未验证】`).toBe(true);
  return n;
}
const inList = (ids: readonly string[]) => ids.map((i) => `'${i}'`).join(',');

// ═══════════════════════════════════════════════════════════════════
// Environment identity (testing.md §4.2: probe must verify WHO answered)
// ═══════════════════════════════════════════════════════════════════

export function portOwner(port: string): { pid: string; cwd: string; dbNameEnv: string | null; cmd: string } {
  let pid = '', cwd = '', cmd = '', dbNameEnv: string | null = null;
  try {
    const ss = execSync(`ss -ltnp 2>/dev/null | /usr/bin/grep -a ':${port} ' || true`, { shell: '/bin/bash', encoding: 'utf-8' });
    pid = (ss.match(/pid=(\d+)/) || [])[1] || '';
    if (pid) {
      cwd = execSync(`readlink -f /proc/${pid}/cwd 2>/dev/null || true`, { shell: '/bin/bash', encoding: 'utf-8' }).trim();
      cmd = execSync(`tr '\\0' ' ' < /proc/${pid}/cmdline 2>/dev/null || true`, { shell: '/bin/bash', encoding: 'utf-8' }).trim();
      const env = execSync(`tr '\\0' '\\n' < /proc/${pid}/environ 2>/dev/null || true`, { shell: '/bin/bash', encoding: 'utf-8' });
      const m = env.match(/^DB_NAME=(.*)$/m);
      dbNameEnv = m ? m[1] : null;
    }
  } catch { /* sampling failure shows up as empty fields */ }
  return { pid, cwd, dbNameEnv, cmd: cmd.slice(0, 300) };
}

export async function apiContext(): Promise<APIRequestContext> {
  const ctx = await pwRequest.newContext({ baseURL: BACKEND_URL });
  for (let i = 0; i < 4; i++) {
    const r = await ctx.post('/api/cpq/auth/login', { data: { username: 'admin', password: 'Admin@2026' } });
    if (r.ok()) return ctx;
    if (r.status() === 429) { await new Promise((res) => setTimeout(res, 3000 * (i + 1))); continue; }
    throw new Error(`admin login on ${BACKEND_URL} failed ${r.status()} ${(await r.text()).slice(0, 200)} —— 登录被锁则报主线，🚫 不改 user 表`);
  }
  throw new Error('admin login kept returning 429 (rate limit) ⇒ harness problem');
}

/** Hard identity gate. Anything off here is an ENVIRONMENT problem, not a product defect. */
export async function assertAcceptanceEnv(label: string) {
  expect(BACKEND_URL, 'S-global must never hit the shared 8081').not.toMatch(/:8081\b/);
  expect(BASE_URL, 'S-global must never drive the shared 5174').not.toMatch(/:5174\b/);
  const owner = portOwner((BACKEND_URL.match(/:(\d+)/) || [])[1] || '8196');
  const fe = portOwner((BASE_URL.match(/:(\d+)/) || [])[1] || '5196');
  const v444 = sqlScalar(`select success::text from flyway_schema_history where version='444'`);
  const ctx = await apiContext();
  const r = await ctx.get('/api/cpq/quotations?page=1&size=1');
  expect(r.status(), 'GET /quotations on 8196 should be 200').toBe(200);
  const apiTotal = Number((await r.json())?.data?.totalElements);
  const dbTotal = sqlInt('select count(*) from quotation', 'identity: quotation count');
  const line = `[${new Date().toISOString()}] ${label}\n` +
    `  backend ${BACKEND_URL} pid=${owner.pid || '?'} cwd=${owner.cwd || '?'} DB_NAME(env)=${owner.dbNameEnv ?? '(unreadable)'}\n` +
    `  cmd=${owner.cmd}\n` +
    `  frontend ${BASE_URL} pid=${fe.pid || '?'} cwd=${fe.cwd || '?'}\n` +
    `  flyway 444 success on ${DB_NAME} = ${v444 || '(missing)'}\n` +
    `  quotations totalElements api=${apiTotal} db(${DB_NAME})=${dbTotal}\n`;
  console.log(line);
  ensureDirs();
  fs.appendFileSync(path.join(EVID_E2E, '00-环境正身.txt'), line, 'utf-8');
  expect(owner.pid, `nothing listens on ${BACKEND_URL} ⇒ acceptance backend not started (主线启动), 判【未验证】`).not.toBe('');
  expect(v444, `V444 not applied on ${DB_NAME} ⇒ acceptance backend not (yet) on this DB, 判【未验证】`).toBe('true');
  if (owner.dbNameEnv !== null) {
    expect(owner.dbNameEnv, `backend on ${BACKEND_URL} runs with DB_NAME=${owner.dbNameEnv} ⇒ wrong database, environment problem`).toBe(DB_NAME);
  } else {
    // cpq_db_0724 and cpq_db_260916 are clones, so the count check alone cannot tell them apart.
    expect(process.env.R260916_IDENTITY_ACK,
      `cannot read /proc/${owner.pid}/environ to prove DB_NAME; the count check cannot discriminate the two clones. ` +
      `Set R260916_IDENTITY_ACK=1 only after 主线 confirmed 8196 → ${DB_NAME}.`).toBe('1');
  }
  expect(apiTotal, `8196 totalElements ${apiTotal} ≠ ${DB_NAME} count ${dbTotal} ⇒ backend is on another DB`).toBe(dbTotal);
  await ctx.dispose();
}

// ═══════════════════════════════════════════════════════════════════
// State snapshots for AC-6 / AC-7 (zero-write and "only these changed")
// ═══════════════════════════════════════════════════════════════════

export type ViewRow = { id: string; component_id: string; sql_view_name: string; sql_md5: string; decl_md5: string; cfg_md5: string; builder_version: number | null; updated_at: string };
export type CompRow = { id: string; code: string; fields_md5: string; formulas_md5: string; rkf_md5: string; part_no_field: string | null; part_name_field: string | null; sort_field: string | null; updated_at: string };
export type TplRow = { id: string; name: string; version: string; status: string; svs_md5: string; cs_md5: string; tsvs_md5: string; updated_at: string; tcs_md5: string };
export type Snapshot = {
  at: string;
  viewsTableMd5: string;
  views: Record<string, ViewRow>;
  comps: Record<string, CompRow>;
  templates: Record<string, TplRow>;
  tcsTableMd5: string;
  opLogCount: number;
  opLogRecompileForComps: number;
};

export function snapshot(): Snapshot {
  const views = sqlJson<ViewRow>(`
    select id::text, component_id::text, sql_view_name,
           md5(coalesce(sql_template,'<null>')) sql_md5,
           md5(coalesce(declared_columns::text,'<null>')) decl_md5,
           md5(coalesce(builder_config::text,'<null>')) cfg_md5,
           builder_version, coalesce(updated_at::text,'<null>') updated_at
      from component_sql_view`);
  const comps = sqlJson<CompRow>(`
    select id::text, code,
           md5(coalesce(fields::text,'<null>')) fields_md5,
           md5(coalesce(formulas::text,'<null>')) formulas_md5,
           md5(coalesce(row_key_fields::text,'<null>')) rkf_md5,
           part_no_field, part_name_field, sort_field,
           coalesce(updated_at::text,'<null>') updated_at
      from component where id in (${inList(COMP_IDS)})`);
  const templates = sqlJson<TplRow>(`
    select t.id::text, t.name, t.version, t.status,
           md5(coalesce(t.sql_views_snapshot::text,'<null>')) svs_md5,
           md5(coalesce(t.components_snapshot::text,'<null>')) cs_md5,
           md5(coalesce(t.template_sql_views_snapshot::text,'<null>')) tsvs_md5,
           coalesce(t.updated_at::text,'<null>') updated_at,
           coalesce((select md5(string_agg(row_to_json(s)::text, '|' order by s.id))
                       from template_component_snapshot s where s.template_id = t.id), '<none>') tcs_md5
      from template t`);
  const viewsTableMd5 = sqlScalar(`select md5(string_agg(row_to_json(v)::text, '|' order by v.id)) from component_sql_view v`);
  const tcsTableMd5 = sqlScalar(`select md5(string_agg(row_to_json(s)::text, '|' order by s.id)) from template_component_snapshot s`);
  const opLogCount = sqlInt('select count(*) from operation_log', 'snapshot: operation_log count');
  const opLogRecompileForComps = sqlInt(
    `select count(*) from operation_log where operation_type='COMPONENT_VIEW_RECOMPILE' and target_id in (${inList(COMP_IDS)})`,
    'snapshot: recompile audit rows for the 3 components');
  const byId = <T extends { id: string }>(rows: T[]) => Object.fromEntries(rows.map((r) => [r.id, r]));
  expect(views.length, 'snapshot: component_sql_view returned 0 rows ⇒ gauge broken').toBeGreaterThan(0);
  expect(comps.length, `snapshot: expected the 3 target components, got ${comps.length}`).toBe(3);
  expect(templates.length, 'snapshot: template returned 0 rows ⇒ gauge broken').toBeGreaterThan(0);
  return {
    at: new Date().toISOString(), viewsTableMd5, views: byId(views), comps: byId(comps), templates: byId(templates),
    tcsTableMd5, opLogCount, opLogRecompileForComps,
  };
}

export function viewRowByName(s: Snapshot, name: string): ViewRow {
  const r = Object.values(s.views).find((v) => v.sql_view_name === name);
  expect(r, `snapshot has no component_sql_view named ${name}`).toBeTruthy();
  return r!;
}

/** Field-level diff. Returns human-readable lines; empty = identical on the listed keys. */
export function diffRows<T extends Record<string, any>>(label: string, a: Record<string, T>, b: Record<string, T>, keys: (keyof T)[], only?: string[]): string[] {
  const out: string[] = [];
  const ids = only ?? Array.from(new Set([...Object.keys(a), ...Object.keys(b)]));
  for (const id of ids) {
    const x = a[id], y = b[id];
    if (!x || !y) { out.push(`${label} ${id}: ${!x ? 'appeared' : 'disappeared'}`); continue; }
    for (const k of keys) if (String(x[k]) !== String(y[k])) out.push(`${label} ${id} ${String(k)}: ${x[k]} → ${y[k]}`);
  }
  return out;
}

export function appendMd5Report(title: string, s: Snapshot, note = '') {
  ensureDirs();
  const lines: string[] = [];
  if (!fs.existsSync(MD5_REPORT)) {
    lines.push('# AC-6 / AC-7 · md5 清单（S-全局，验收库 `cpq_db_260916`）', '',
      '> 由 `cpq-frontend/e2e/repair260916-global-2-write.spec.ts` 自动生成；每个快照一节，按时间追加。', '');
  }
  lines.push(`## ${title}`, '', `采样时刻（UTC）：${s.at}${note ? `　${note}` : ''}`, '',
    `- component_sql_view 全表 md5：\`${s.viewsTableMd5}\``,
    `- template_component_snapshot 全表 md5：\`${s.tcsTableMd5}\``,
    `- operation_log 行数：${s.opLogCount}（其中 3 组件的 COMPONENT_VIEW_RECOMPILE：${s.opLogRecompileForComps}）`, '',
    '| 视图 | sql_template md5 | declared_columns md5 | builder_config md5 | builder_version | updated_at |', '|---|---|---|---|---|---|');
  for (const v of VIEW_NAMES) {
    const r = viewRowByName(s, v);
    lines.push(`| ${v} | ${r.sql_md5} | ${r.decl_md5} | ${r.cfg_md5} | ${r.builder_version} | ${r.updated_at} |`);
  }
  lines.push('', '| 组件 | fields md5 | formulas md5 | row_key_fields md5 | part_no / part_name / sort | updated_at |', '|---|---|---|---|---|---|');
  for (const c of COMPS) {
    const r = s.comps[c.id];
    lines.push(`| ${r.code} | ${r.fields_md5} | ${r.formulas_md5} | ${r.rkf_md5} | ${r.part_no_field} / ${r.part_name_field} / ${r.sort_field} | ${r.updated_at} |`);
  }
  lines.push('', `| ${TEMPLATE_NAME} 版本 | id | sql_views_snapshot md5 | components_snapshot md5 | template_sql_views_snapshot md5 | 各自 tcs 行 md5 | updated_at |`, '|---|---|---|---|---|---|---|');
  for (const t of Object.values(s.templates).filter((t) => t.name === TEMPLATE_NAME).sort((a, b) => a.version.localeCompare(b.version, 'en', { numeric: true }))) {
    lines.push(`| ${t.version} (${t.status}) | ${t.id} | ${t.svs_md5} | ${t.cs_md5} | ${t.tsvs_md5} | ${t.tcs_md5} | ${t.updated_at} |`);
  }
  const allTpl = sqlScalar(`select md5(string_agg(id::text||md5(coalesce(sql_views_snapshot::text,''))||md5(coalesce(components_snapshot::text,''))||updated_at::text, '|' order by id)) from template`);
  lines.push('', `- template 全表（id + 两份快照 md5 + updated_at）聚合 md5：\`${allTpl}\``, '');
  fs.appendFileSync(MD5_REPORT, lines.join('\n') + '\n', 'utf-8');
  console.log(`[md5-report] ${title} → ${MD5_REPORT}`);
}

/** Baseline sql md5 of the 3 views, taken by 主线 at clone time (`基线-改动前/三组件-cpq_db_0724.csv`). */
export function baselineViewMd5(): Record<string, string> {
  const csv = fs.readFileSync(path.join(BASELINE_DIR, '三组件-cpq_db_0724.csv'), 'utf-8').trim().split('\n');
  const head = csv[0].split(',');
  const iView = head.indexOf('sql_view_name'), iMd5 = head.indexOf('sql_md5');
  expect(iView >= 0 && iMd5 >= 0, 'baseline csv header changed ⇒ gauge broken').toBe(true);
  return Object.fromEntries(csv.slice(1).map((l) => { const c = l.split(','); return [c[iView], c[iMd5]]; }));
}

/** The one-shot "before" state (test.md §0.3). Throws with the exact instruction if it was already consumed. */
export function assertOneShotStateIntact(label: string) {
  const base = baselineViewMd5();
  const now = sqlJson<{ sql_view_name: string; m: string }>(
    `select sql_view_name, md5(sql_template) m from component_sql_view where sql_view_name in (${inList(VIEW_NAMES)})`);
  const audit = sqlInt(`select count(*) from operation_log where operation_type='COMPONENT_VIEW_RECOMPILE' and target_id in (${inList(COMP_IDS)})`, 'one-shot audit count');
  const drift = now.filter((r) => base[r.sql_view_name] !== r.m).map((r) => `${r.sql_view_name}: baseline ${base[r.sql_view_name]} now ${r.m}`);
  expect(now.length, `${label}: expected 3 target views in ${DB_NAME}, got ${now.length}`).toBe(3);
  expect({ drift, audit },
    `${label}: 🚨 一次性「执行前」状态已被消耗（视图文本已非基线 或 已有按组件重编译审计行）。\n` +
    `  test.md §0.3：AC-6 / AC-11 证据作废，必须报主线重建验收库；🚫 不许在已执行的库上补跑。`).toEqual({ drift: [], audit: 0 });
}

// ═══════════════════════════════════════════════════════════════════
// SQL text utilities (identifier-boundary aware; ds_quote_material is a prefix of ds_quote_material_bom)
// ═══════════════════════════════════════════════════════════════════

export function hasIdent(sql: string, ident: string): boolean {
  return new RegExp(`(?<![A-Za-z0-9_])${ident}(?![A-Za-z0-9_])`).test(sql);
}
export type JoinInfo = { alias: string; on: string };
export function leftJoinsOf(sql: string, table: string): JoinInfo[] {
  const re = new RegExp(
    `LEFT\\s+(?:OUTER\\s+)?JOIN\\s+(?:public\\.)?${table}(?![A-Za-z0-9_])\\s+(?:AS\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s+ON\\s+` +
    `([\\s\\S]*?)(?=\\bLEFT\\s+(?:OUTER\\s+)?JOIN\\b|\\bINNER\\s+JOIN\\b|\\bRIGHT\\s+JOIN\\b|\\bJOIN\\b|\\bWHERE\\b|\\bGROUP\\s+BY\\b|\\bORDER\\s+BY\\b|\\bUNION\\b|$)`, 'gi');
  const out: JoinInfo[] = [];
  let m: RegExpExecArray | null;
  while ((m = re.exec(sql)) !== null) out.push({ alias: m[1], on: m[2].replace(/\s+/g, ' ').trim() });
  return out;
}
export function fromAlias(sql: string, table: string): string | null {
  const m = sql.match(new RegExp(`\\bFROM\\s+(?:public\\.)?${table}(?![A-Za-z0-9_])\\s+(?:AS\\s+)?([A-Za-z_][A-Za-z0-9_]*)`, 'i'));
  return m ? m[1] : null;
}
export function onConditions(on: string): string[] {
  return on.split(/\bAND\b/i).map((s) => s.replace(/[()]/g, ' ').replace(/\s+/g, ' ').trim()).filter(Boolean);
}
/** true when `cond` is `<a>.<ca> = <b>.<cb>` in either orientation (equality is symmetric). */
export function isEquality(cond: string, a: string, ca: string, b: string, cb: string): boolean {
  const x = `${a}\\s*\\.\\s*${ca}`, y = `${b}\\s*\\.\\s*${cb}`;
  return new RegExp(`^(${x}\\s*=\\s*${y}|${y}\\s*=\\s*${x})$`, 'i').test(cond);
}
export function coalesceMatch(sql: string): { matAlias: string; recAlias: string; asCol: string | null; raw: string } | null {
  const m = sql.match(/COALESCE\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\.\s*material_name\s*,\s*([A-Za-z_][A-Za-z0-9_]*)\s*\.\s*symbol\s*\)(\s+AS\s+"([^"]+)")?/i);
  return m ? { matAlias: m[1], recAlias: m[2], asCol: m[4] ?? null, raw: m[0] } : null;
}

// ═══════════════════════════════════════════════════════════════════
// UI: login, write-guard, component builder
// ═══════════════════════════════════════════════════════════════════

export async function uiLogin(page: Page) {
  // Run-5 harness fix: /login once rendered a blank page (HTTP 200, React not mounted) — consistent with the
  // Vite dev server re-optimising deps and forcing a reload. Wait longer, reload once, and log page errors
  // so a real frontend failure still surfaces instead of being retried away.
  const errs: string[] = [];
  page.on('pageerror', (e) => errs.push(String(e)));
  const user = page.locator('input[placeholder="用户名或邮箱"]');
  await page.goto('/login');
  if (!(await user.isVisible({ timeout: 30_000 }).catch(() => false))) {
    console.log(`[uiLogin] login form not rendered after 30s; pageerrors=${JSON.stringify(errs)} — reloading once`);
    await page.reload();
    await expect(user, `login form still blank after reload; pageerrors=${JSON.stringify(errs)} ⇒ environment problem`).toBeVisible({ timeout: 60_000 });
  }
  await user.fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|change-password)/, { timeout: 30_000 });
  if (page.url().includes('/change-password')) { await page.goto('/dashboard'); await page.waitForLoadState('networkidle'); }
  expect(page.url(), 'login should leave /login').not.toContain('/login');
}

/**
 * Structural write guard for the read-only segment (T3.1–T3.3 and AC-7④):
 * every non-GET /api call is aborted except the pure-compute endpoints the builder needs.
 * The 3 target views have exactly ONE "before" state (test.md §0.3) — an accidental save/autosave here would
 * destroy AC-6's evidence, so we make it impossible instead of merely detecting it.
 */
export async function installWriteGuard(page: Page): Promise<string[]> {
  const blocked: string[] = [];
  const allow = [/\/auth\/login$/, /\/builder\/compile(\?|$)/, /\/builder\/preview(\?|$)/, /\/builder\/inspect(\?|$)/,
    /\/row-key-candidates(\?|$)/, /\/semantic-graph\//];
  await page.route('**/api/**', (route) => {
    const r = route.request();
    if (['GET', 'HEAD', 'OPTIONS'].includes(r.method()) || allow.some((re) => re.test(r.url()))) return route.continue();
    blocked.push(`${r.method()} ${r.url()}`);
    return route.abort();
  });
  return blocked;
}

export function viewFingerprint(): string {
  return sqlScalar(`select string_agg(sql_view_name||':'||md5(coalesce(sql_template,''))||':'||md5(coalesce(builder_config::text,''))||':'||updated_at::text, ' ' order by sql_view_name)
                      from component_sql_view where sql_view_name in (${inList(VIEW_NAMES)})`);
}


/**
 * Run-9 harness fix: the 5196 Vite dev server intermittently serves a blank shell (HTTP 200, React not mounted;
 * seen on /login and /components with pageerror=[]). Navigate, wait for a page-specific element, reload ONCE if blank.
 * Console errors / failed requests are logged so a genuine frontend failure is still visible, not retried away.
 */
export async function gotoReady(page: Page, url: string, ready: string, why: string) {
  const errs: string[] = [];
  const onErr = (e: any) => errs.push(`pageerror ${String(e).slice(0, 200)}`);
  const onFail = (r: any) => errs.push(`requestfailed ${r.url()} ${r.failure()?.errorText ?? ''}`);
  page.on('pageerror', onErr);
  page.on('requestfailed', onFail);
  try {
    await page.goto(url);
    const target = page.locator(ready).first();
    if (!(await target.isVisible({ timeout: 45_000 }).catch(() => false))) {
      console.log(`[gotoReady] ${why}: "${ready}" not rendered after 45s on ${url}; errors=${JSON.stringify(errs.slice(0, 10))} — reloading once`);
      await page.reload();
      await expect(target, `${why}: page still blank after reload (${url}); errors=${JSON.stringify(errs.slice(0, 10))} ⇒ environment problem`)
        .toBeVisible({ timeout: 90_000 });
    }
  } finally {
    page.off('pageerror', onErr);
    page.off('requestfailed', onFail);
  }
}

export async function openComponentByCode(page: Page, code: string, name: string) {
  await gotoReady(page, '/components', 'input[placeholder*="搜索"]', `open component ${code}`);
  await page.waitForTimeout(1500);
  const search = page.locator('input[placeholder*="搜索"]').first();
  await expect(search, 'component page search box missing ⇒ 入口问题, 判【未验证】').toBeVisible({ timeout: 15_000 });
  await search.fill(code);
  await page.waitForTimeout(2500);
  for (let i = 0; i < 20; i++) { // search hits can sit inside collapsed directories (hidden ≠ missing)
    const closed = page.locator('.cmm-dir:not(.open) .cmm-dir-head');
    if (await closed.count() === 0) break;
    await closed.first().click({ timeout: 5000 }).catch(() => {});
    await page.waitForTimeout(250);
  }
  // Harness fix (run 1): the first text match was not the visible tree card ⇒ click landed nowhere.
  const hit = page.getByText(code, { exact: false }).locator('visible=true').first();
  await expect(hit, `component ${code} not found in the tree ⇒ 入口/夹具问题, 判【未验证】`).toBeVisible({ timeout: 15_000 });
  for (let i = 0; i < 3; i++) {
    await hit.scrollIntoViewIfNeeded().catch(() => {});
    await hit.click().catch(() => {});
    await page.waitForTimeout(2000);
    if (await page.locator('.cmm-detail-head').count() > 0) break;
  }
  await expect(page.locator('.cmm-detail-head'), `opened component is not ${code}`).toContainText(code, { timeout: 15_000 });
  await expect(page.locator('.cmm-detail-head'), `opened component is not「${name}」`).toContainText(name);
}

export type BuilderGet = { builderConfig: any; builderVersion: number | null; isStale: boolean | undefined; currentCompilerVersion: number | null; raw: any };

/** Opens the「取数配置」tab and returns the GET /builder payload plus the first compile response triggered by opening. */
export async function openBuilderTab(page: Page, c: { code: string; name: string; id: string }): Promise<{ get: BuilderGet; compile: any | null }> {
  await openComponentByCode(page, c.code, c.name);
  const getP = page.waitForResponse((r) => r.request().method() === 'GET' && new RegExp(`/components/${c.id}/builder$`).test(r.url()), { timeout: 30_000 }).catch(() => null);
  const compP = page.waitForResponse((r) => /\/builder\/compile(\?|$)/.test(r.url()), { timeout: 30_000 }).catch(() => null);
  const tab = page.getByRole('tab', { name: '取数配置', exact: true }).first();
  await expect(tab, '「取数配置」tab missing ⇒ 入口问题').toBeVisible({ timeout: 15_000 });
  await tab.click();
  const [getR, compR] = await Promise.all([getP, compP]);
  await expect(page.locator('.svb-recipe-bar').first(), 'builder panel (.svb-recipe-bar) not rendered ⇒ everything after would be a no-op, 判【未验证】')
    .toBeVisible({ timeout: 20_000 });
  await page.waitForTimeout(2500);
  let raw: any = null;
  if (getR) { const j = await getR.json().catch(() => null); raw = j?.data ?? j; }
  let compile: any = null;
  if (compR) {
    expect(compR.ok(), `opening builder: /builder/compile returned ${compR.status()} ⇒ the SQL pane shows stale text`).toBe(true);
    const j = await compR.json().catch(() => null); compile = j?.data ?? j;
  }
  return {
    get: { builderConfig: raw?.builderConfig, builderVersion: raw?.builderVersion ?? null, isStale: raw?.isStale, currentCompilerVersion: raw?.currentCompilerVersion ?? null, raw },
    compile,
  };
}

export async function expandAllGroups(page: Page) {
  for (let i = 0; i < 200; i++) {
    const collapsed = page.locator('.svb-grp.collapsed');
    if (await collapsed.count() === 0) break;
    await collapsed.first().locator('.svb-grp-h').click({ timeout: 5000 }).catch(() => {});
    await page.waitForTimeout(150);
  }
}

export type FieldPanel = { groups: { head: string; fields: string[] }[]; total: number };
/** Reads the left「可用字段」pane. Field name = 2nd <span> of `.svb-fld` (1st is the drag handle `⋮⋮`). */
export async function readFieldPanel(page: Page): Promise<FieldPanel> {
  const groups = await page.evaluate(() => Array.from(document.querySelectorAll('.svb-pane.left .svb-grp')).map((g) => ({
    head: ((g.querySelector('.svb-grp-h') as HTMLElement)?.innerText || '').replace(/[▾▸▶]/g, '').replace(/\s+/g, ' ').trim(),
    fields: Array.from(g.querySelectorAll('.svb-fld')).map((f) => ((f.querySelectorAll('span')[1] as HTMLElement)?.textContent || '').trim()),
  })));
  const total = groups.reduce((n, g) => n + g.fields.length, 0);
  expect(total, `field pane read 0 fields (groups=${JSON.stringify(groups)}) ⇒ gauge broken, 判【未验证】`).toBeGreaterThan(0);
  return { groups, total };
}

export async function currentSource(page: Page): Promise<string> {
  const c = page.locator('[data-role="builder-source"] .ant-select-content').first();
  return ((await c.getAttribute('title').catch(() => null)) || (await c.innerText().catch(() => '')) || '').trim();
}

export async function tablePrefix(page: Page): Promise<string | null> {
  const t = await page.locator('.svb-recipe-bar').first().innerText();
  const m = t.match(/表前缀\s*(ds_[a-z_]+_)/);
  return m ? m[1] : null;
}

async function confirmIfAsked(page: Page) {
  const btn = page.locator('.ant-modal:visible, .ant-popover:visible, .ant-popconfirm:visible')
    .locator('button').filter({ hasText: /^\s*(确\s*定|确\s*认|确认切换|继续切换|继\s*续|清\s*空.*)\s*$/ }).first();  // run-2 fix: dataset switch modal button is「确认切换」
  if (await btn.isVisible().catch(() => false)) { await btn.click(); await page.waitForTimeout(1200); }
}

/** Switch dataset segment (报价 / 基础核价 / 明细核价). Positive control: the panel's own `表前缀 ds_xxx_` marker. */
export async function selectDataset(page: Page, label: '报价' | '基础核价' | '明细核价', expectPrefix: string) {
  const seg = page.locator('.svb-recipe-bar .ant-segmented-item').filter({ hasText: new RegExp(`^\\s*${label}\\s*$`) }).first();
  await expect(seg, `dataset segment「${label}」missing ⇒ 入口问题`).toBeVisible({ timeout: 10_000 });
  await seg.click();
  await page.waitForTimeout(800);
  await confirmIfAsked(page);
  await page.waitForTimeout(1500);
  await expect.poll(() => tablePrefix(page), { timeout: 15_000, message: `dataset「${label}」did not take effect (表前缀 marker)` }).toBe(expectPrefix);
}

/** Pick a source in `[data-role=builder-source]`; option list is virtualised ⇒ scroll until found. */
export async function selectSource(page: Page, label: string) {
  const sel = page.locator('[data-role="builder-source"]').first();
  await expect(sel, 'source select missing ⇒ 入口问题').toBeVisible({ timeout: 10_000 });
  await sel.click();
  await page.waitForTimeout(500);
  // run-3 fix: several dropdown instances exist; `.last()` picked a non-visible one ⇒ use visible options only.
  const dd = page.locator('.ant-select-dropdown').locator('visible=true').last();
  const opt = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: new RegExp(`^\\s*${label}\\s*$`) }).locator('visible=true').first();
  for (let i = 0; i < 40 && !(await opt.isVisible().catch(() => false)); i++) {
    await dd.locator('.rc-virtual-list-holder').first().evaluate((el) => { el.scrollTop += 120; }).catch(() => {});
    await page.waitForTimeout(150);
  }
  const seen = await page.locator('.ant-select-dropdown .ant-select-item-option').locator('visible=true').allInnerTexts().catch(() => []);
  await expect(opt, `source option「${label}」not found (visible=${JSON.stringify(seen)})`).toBeVisible({ timeout: 5_000 });
  await opt.click();
  await page.waitForTimeout(800);
  await confirmIfAsked(page);
  await page.waitForTimeout(2000);
  await expect.poll(() => currentSource(page), { timeout: 15_000, message: `source did not switch to「${label}」` }).toBe(label);
}

/** Double-click a field (by exact name) into「已选输出列」and wait for a 200 compile; returns the compile payload. */
export async function addFieldAndCompile(page: Page, fieldName: string): Promise<any> {
  const before = await page.locator('[data-role="selected-column"]').count();
  const resp = page.waitForResponse((r) => /\/builder\/compile(\?|$)/.test(r.url()), { timeout: 30_000 });
  const fld = page.locator('.svb-pane.left .svb-fld').filter({ has: page.locator('span', { hasText: new RegExp(`^${fieldName}$`) }) })
    .locator('visible=true').first();
  await expect(fld, `field「${fieldName}」not visible in the left pane`).toBeVisible({ timeout: 10_000 });
  await fld.dblclick();
  const r = await resp;
  const text = await r.text();
  expect(r.ok(), `/builder/compile after adding「${fieldName}」returned ${r.status()}: ${text.slice(0, 400)}`).toBe(true);
  await page.waitForTimeout(1200);
  const after = await page.locator('[data-role="selected-column"]').count();
  expect(after, `「${fieldName}」was not added (selected columns ${before} → ${after})`).toBeGreaterThan(before);
  const j = JSON.parse(text);
  return j?.data ?? j;
}

/** Text of the「生成的 SQL（实时·只读）」pane. Empty text is a gauge failure, never a pass. */
export async function sqlPaneText(page: Page): Promise<string> {
  const pane = page.locator('.svb-pane.sql').first();
  await expect(pane, 'SQL pane (.svb-pane.sql) missing').toBeVisible({ timeout: 10_000 });
  const pre = pane.locator('pre, .svb-livesql').first();
  const txt = ((await pre.innerText().catch(() => '')) || (await pane.innerText())).trim();
  expect(txt.length, 'SQL pane text is empty ⇒ gauge broken (not.toContain would pass vacuously), 判【未验证】').toBeGreaterThan(20);
  return txt;
}
export const normSql = (s: string) => s.replace(/\s+/g, ' ').trim();

// ═══════════════════════════════════════════════════════════════════
// UI: real preview (AC-4)
// ═══════════════════════════════════════════════════════════════════

export type Preview = { status: number; body: any; rowCount: number | null; rows: any[]; diagnostics: any[]; requestBody: string };

export async function runPreview(page: Page, customerCode: string, partNo: string, why: string): Promise<Preview> {
  const bar = page.locator('.svb-preview').first();
  await expect(bar, `${why}: preview bar (.svb-preview) missing ⇒ 入口问题`).toBeVisible({ timeout: 15_000 });
  // customer select (showSearch; options virtualised)
  const sel = bar.locator('.ant-select').first();
  await sel.click({ force: true });
  await page.waitForTimeout(400);
  await page.keyboard.type(customerCode, { delay: 40 });
  await page.waitForTimeout(1500);
  const dd = page.locator('.ant-select-dropdown').locator('visible=true').last();
  const opt = page.locator('.ant-select-dropdown .ant-select-item-option').filter({ hasText: customerCode }).locator('visible=true').first();
  for (let i = 0; i < 40 && !(await opt.isVisible().catch(() => false)); i++) {
    await dd.locator('.rc-virtual-list-holder').first().evaluate((el) => { el.scrollTop += 200; }).catch(() => {});
    await page.waitForTimeout(200);
  }
  await expect(opt, `${why}: customer option containing ${customerCode} not found in preview customer select`).toBeVisible({ timeout: 10_000 });
  await opt.click();
  await page.waitForTimeout(600);
  const selected = (await sel.innerText()).trim();
  expect(selected, `${why}: preview customer select should now show ${customerCode}`).toContain(customerCode);
  const part = bar.locator('input[placeholder*="料号"]').first();
  await part.fill(partNo);
  const pending = page.waitForResponse((r) => /\/builder\/preview(\?|$)/.test(r.url()), { timeout: 90_000 }).catch(() => null);
  await bar.locator('button').filter({ hasText: /重新执行/ }).first().click();
  const resp = await pending;
  expect(resp, `${why}: clicked「重新执行」but no /builder/preview response ⇒ page shows stale rows, 判【未验证】`).not.toBeNull();
  const requestBody = resp!.request().postData() || resp!.request().url();
  const text = await resp!.text();
  expect(resp!.ok(), `${why}: /builder/preview returned ${resp!.status()}: ${text.slice(0, 600)}`).toBe(true);
  // The request must carry the parameters we typed, otherwise rows belong to another input.
  expect(requestBody, `${why}: preview request does not carry part no ${partNo}: ${requestBody.slice(0, 400)}`).toContain(partNo);
  let body: any = JSON.parse(text);
  body = body?.data ?? body;
  await page.waitForTimeout(1500);
  const rows = Array.isArray(body?.rows) ? body.rows : [];
  const diagnostics = Array.isArray(body?.diagnostics) ? body.diagnostics : [];
  const rowCount = typeof body?.rowCount === 'number' ? body.rowCount : null;
  console.log(`[preview] ${why}: HTTP ${resp!.status()} rowCount=${rowCount} rows=${rows.length} diagnostics=${JSON.stringify(diagnostics).slice(0, 300)}`);
  return { status: resp!.status(), body, rowCount, rows, diagnostics, requestBody };
}

/** Discriminator used by AC-4 / AC-10 and gauge-checked against the E-1 baseline. */
export function namesByPart(rows: any[], partCol: string): Record<string, any[]> {
  const out: Record<string, any[]> = {};
  for (const r of rows) (out[String(r[partCol])] ??= []).push(r[MATERIAL_COL]);
  return out;
}
export const isBlank = (v: any) => v === null || v === undefined || String(v).trim() === '' || String(v).trim() === '—' || String(v).trim() === '-';

// ═══════════════════════════════════════════════════════════════════
// UI: quotation product-card table (AC-10)
// ═══════════════════════════════════════════════════════════════════

export type CardTable = { headers: string[]; rows: string[][] };

/** Reads the visible `.qt-cost-table` of the first product card; input cells are read by value (edit page). */
export async function readCardTable(page: Page): Promise<CardTable> {
  const card = page.locator('.qt-product-card').first();
  await expect(card, 'no product card rendered ⇒ 入口问题').toBeVisible({ timeout: 60_000 });
  const t = card.locator('table.qt-cost-table').locator('visible=true').first();
  await expect(t, 'no visible .qt-cost-table in the product card').toBeVisible({ timeout: 30_000 });
  return t.evaluate((tbl) => ({
    headers: Array.from(tbl.querySelectorAll('thead th')).map((th) => (th as HTMLElement).innerText.replace(/\s+/g, ' ').trim()),
    rows: Array.from(tbl.querySelectorAll('tbody tr')).map((tr) => Array.from(tr.querySelectorAll('td')).map((td) => {
      const inp = td.querySelector('input, textarea') as HTMLInputElement | null;
      return inp ? (inp.value ?? '').trim() : (td as HTMLElement).innerText.replace(/\s+/g, ' ').trim();
    })),
  }));
}

export async function clickCardTab(page: Page, tabName: string) {
  const tab = page.locator('.qt-product-card').first().locator('button.qt-tab-btn').filter({ hasText: new RegExp(`^\\s*${tabName}\\s*$`) }).first();
  await expect(tab, `card tab「${tabName}」missing`).toBeVisible({ timeout: 30_000 });
  await tab.click();
  await page.waitForTimeout(3500);
}

/** Material-name values of every row whose「料号」cell equals partNo. Asserts the columns exist and the row exists. */
export function namesOf(t: CardTable, partNo: string, why: string): string[] {
  const iPart = t.headers.findIndex((h) => h === '料号');
  const iName = t.headers.findIndex((h) => h === '材料名');
  expect(iPart >= 0 && iName >= 0, `${why}: headers lack 料号/材料名: ${JSON.stringify(t.headers)}`).toBe(true);
  const hits = t.rows.filter((r) => r[iPart] === partNo).map((r) => r[iName]);
  expect(hits.length, `${why}: no row with 料号=${partNo}; rows=${JSON.stringify(t.rows)}`).toBeGreaterThan(0);
  return hits;
}

/** AC-10 global negative checks: no permanent「加载中」, no literal null, no red error. */
export async function assertNoBadStates(page: Page, why: string) {
  await page.waitForTimeout(1500);
  const loading = await page.locator('text=加载中').count();
  const errors = await page.locator('.ant-message-error:visible, .ant-alert-error:visible, .ant-notification-notice-error:visible').count();
  const nullCells = await page.locator('.qt-product-card').first().evaluate((c) =>
    Array.from(c.querySelectorAll('td')).filter((td) => {
      const inp = td.querySelector('input') as HTMLInputElement | null;
      const v = (inp ? inp.value : (td as HTMLElement).innerText || '').trim();
      return /^(null|undefined|NaN)$/i.test(v);
    }).length).catch(() => -1);
  console.log(`[bad-states] ${why}: 加载中=${loading} error=${errors} nullCells=${nullCells}`);
  expect({ loading, errors, nullCells }, `${why}: page shows 加载中 / red error / null literal`).toEqual({ loading: 0, errors: 0, nullCells: 0 });
}

/** Opens /quotations/{id}/edit and goes to Step2 (edit route lands on Step1; the step title is not clickable). */
export async function openEditStep2(page: Page, qid: string) {
  await gotoReady(page, `/quotations/${qid}/edit`, '.ant-steps-item', `edit ${qid}`);
  await gotoStep2FromStep1(page);
}
export async function gotoStep2FromStep1(page: Page) {
  await page.waitForSelector('.ant-steps-item', { timeout: 90_000 });
  const next = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  await expect(next, 'Step1「下一步」not enabled ⇒ 夹具/入口问题').toBeEnabled({ timeout: 120_000 });
  await next.click();
  await page.waitForFunction(() => {
    const a = (document.querySelector('.ant-steps-item-active') as HTMLElement)?.innerText || '';
    return a.includes('添加产品');
  }, undefined, { timeout: 120_000 });
  await page.waitForTimeout(3000);
}
