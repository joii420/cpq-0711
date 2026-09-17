/**
 * repair-260916 · S-global · part 2 (write segment): T3.4 AC-6 → T3.5 AC-7 → T3.6 AC-10
 *
 * Derived only from `问题说明.md ⑥` AC text (AC-6 / AC-7 incl. the 2026-09-16 clarification of ② /
 * AC-10) + `api.md §1` + `test.md §0.3/§3/§4`. No implementation code was read.
 *
 * 🚦 Write surface (all on cpq_db_260916, all through the product API/UI, never via SQL):
 *    · POST /config-center/recompile-components confirm=true for COMP-0004/0005/0008 (once)
 *    · the operation_log rows that call writes
 *    · one new version of「施耐德5.4模板」(UI: 创建新草稿 → 发布)
 *    · one quotation with project name `R260916-G-AC10`
 *    🚫 refresh-all-snapshots is only ever called with confirm:false.
 *
 * Gates:
 *    · part 1 must have written its marker in this run (≤ 6 h old), unless 主线 sets R260916_FORCE_WRITE=1
 *    · the one-shot "before" state (test.md §0.3) must be intact before AC-6, otherwise HARD STOP
 *    · serial mode: any failure skips everything after it (AC-7 never executes after a failed AC-6)
 *    · R260916_RESUME_AC10=1 (主线 decision only): skip T3.4/T3.5, reuse their archived evidence, run T3.6
 *
 * Rerun safety for AC-10: created ids are persisted in 证据/e2e/AC-10-state.json immediately after creation
 * and reused on rerun (no second template version / quotation).
 */
import { test, expect, APIRequestContext, Page } from '@playwright/test';
import * as fs from 'fs';
import { createHash } from 'crypto';
import * as H from './repair260916-global.helpers';

test.describe.configure({ mode: 'serial' });

const RESUME = process.env.R260916_RESUME_AC10 === '1';
const C4 = H.COMPS[0];
const REQ = { componentIds: [...H.COMP_IDS] };
const inList = (xs: readonly string[]) => xs.map((x) => `'${x}'`).join(',');

let api: APIRequestContext;
let s0: H.Snapshot, s1: H.Snapshot;

test.beforeAll(async () => {
  if (process.env.R260916_FORCE_WRITE !== '1') {
    expect(fs.existsSync(H.READONLY_MARKER),
      'part 1 (read-only segment) did not pass in this run ⇒ write segment is locked; report 主线 (override: R260916_FORCE_WRITE=1)').toBe(true);
    const ageH = (Date.now() - fs.statSync(H.READONLY_MARKER).mtimeMs) / 3_600_000;
    expect(ageH, `part-1 marker is ${ageH.toFixed(1)} h old ⇒ stale, rerun part 1 first`).toBeLessThan(6);
  }
  await H.assertAcceptanceEnv('S-global part2 start');
  api = await H.apiContext();
});
test.afterAll(async () => { await api?.dispose(); });

async function postJson(path: string, data: any) {
  const r = await api.post(path, { data });
  const text = await r.text();
  let body: any = null;
  try { body = JSON.parse(text); } catch { /* keep text */ }
  return { status: r.status(), body, text };
}
const recompile = (confirm: boolean) => postJson('/api/cpq/config-center/recompile-components', { ...REQ, confirm });
const fullPreview = () => postJson('/api/cpq/config-center/refresh-all-snapshots', { recompile: true, confirm: false });

function currentSql(): Record<string, string> {
  const rows = H.sqlJson<{ n: string; s: string }>(
    `select sql_view_name n, sql_template s from component_sql_view where sql_view_name in (${inList(H.VIEW_NAMES)})`);
  expect(rows.length, 'expected the 3 target views').toBe(3);
  return Object.fromEntries(rows.map((r) => [r.n, r.s]));
}
function declaredNames(): Record<string, string[]> {
  const rows = H.sqlJson<{ n: string; d: any }>(
    `select sql_view_name n, declared_columns d from component_sql_view where sql_view_name in (${inList(H.VIEW_NAMES)})`);
  const names = (d: any): string[] => (Array.isArray(d) ? d : []).map((x: any) =>
    typeof x === 'string' ? x : String(x?.name ?? x?.columnName ?? x?.viewColumn ?? x?.column ?? JSON.stringify(x))).sort();
  const out = Object.fromEntries(rows.map((r) => [r.n, names(r.d)]));
  for (const v of H.VIEW_NAMES) expect(out[v]?.length ?? 0, `declared_columns of ${v} is empty ⇒ set comparison would be vacuous`).toBeGreaterThan(0);
  return out;
}
function fullPreviewNames(body: any, why: string): string[] {
  const d = body?.data ?? body;
  const names = d?.recompileChangedViewNames;
  expect(Array.isArray(names), `${why}: refresh-all-snapshots preview lacks recompileChangedViewNames; keys=${JSON.stringify(Object.keys(d ?? {}))}`).toBe(true);
  expect(d?.preview, `${why}: refresh-all-snapshots must be a preview`).toBe(true);
  return names;
}

// ═══════════════════════════════════════════════════════════════════
// T3.4 · AC-6  preview, zero write
// ═══════════════════════════════════════════════════════════════════

// ── Resume mode (主线 2026-09-17 续派): run 6 already executed AC-7 and then lost its API connection.
//    R260916_RESUME_FROM_EVIDENCE=1 changes ONLY the data source of T3.4/T3.5:
//      · responses + snapshots s0/s1/s3 come from the run-6 files in 证据/e2e/ (label「来源:run6实跑」)
//      · everything that can still be observed is re-queried read-only now (label「来源:续跑现查」)
//    Every expect() below is shared by both modes — no assertion or expected value is relaxed.
//    Run-6 evidence files are never overwritten in resume mode.
const RESUME_EV = process.env.R260916_RESUME_FROM_EVIDENCE === '1';
const md5 = (t: string) => createHash('md5').update(t, 'utf8').digest('hex');
function mustEvid(name: string): any {
  const j = H.readEvidJson(name);
  expect(j, `resume mode: run-6 evidence file ${name} is missing ⇒ cannot resume, report 主线`).toBeTruthy();
  return j;
}
const asResp = (j: any) => ({ status: j.status, body: j.body, text: typeof j.body === 'string' ? j.body : JSON.stringify(j.body) });

test('T3.4 AC-6 ① recompile-components confirm=false: 200, preview, views=3, changed=3, exactly the 3 views, new SQL has material_recipe / old has not; ② zero write', async () => {
  test.skip(RESUME, 'R260916_RESUME_AC10=1: AC-6 evidence from the earlier run stands');
  let r: { status: number; body: any; text: string };
  let oldStoredMd5: Record<string, string>;
  const SRC = RESUME_EV ? '来源:run6实跑' : '来源:本轮实跑';
  if (RESUME_EV) {
    s0 = mustEvid('AC-6-snapshot-调用前.json');
    s1 = mustEvid('AC-6-snapshot-调用后.json');
    r = asResp(mustEvid('AC-6-预览响应.json'));
    oldStoredMd5 = Object.fromEntries(H.VIEW_NAMES.map((n) => [n, H.viewRowByName(s0, n).sql_md5]));
    console.log(`[AC-6][${SRC}] s0.at=${s0.at} s1.at=${s1.at}`);
  } else {
    H.assertOneShotStateIntact('T3.4 before AC-6');
    const dbSqlBefore = currentSql();
    oldStoredMd5 = Object.fromEntries(Object.entries(dbSqlBefore).map(([k, v]) => [k, md5(v)]));
    s0 = H.snapshot();
    H.writeEvid('AC-6-snapshot-调用前.json', s0);
    H.appendMd5Report('AC-6 调用前', s0);
    r = await recompile(false);
    H.writeEvid('AC-6-预览响应.json', { request: { ...REQ, confirm: false }, status: r.status, body: r.body ?? r.text });
    s1 = H.snapshot();
    H.writeEvid('AC-6-snapshot-调用后.json', s1);
    H.appendMd5Report('AC-6 调用后', s1);
  }

  // ① response
  expect(r.status, `AC-6①: HTTP status; body=${r.text.slice(0, 500)}`).toBe(200);
  const d = r.body?.data;
  expect(d, 'AC-6①: response has no data envelope').toBeTruthy();
  console.log(`[AC-6][${SRC}] preview=${d.preview} componentCount=${d.componentCount} views=${d.views} changed=${d.changed} ` +
    `changes=${JSON.stringify((d.changes ?? []).map((c: any) => c.sqlViewName))} unchanged=${JSON.stringify(d.unchangedViewNames)} skipped=${JSON.stringify(d.skippedComponentIds)}`);
  expect(d.preview, 'AC-6①: preview').toBe(true);
  expect(d.views, 'AC-6①: views').toBe(3);
  expect(d.changed, 'AC-6①: changed').toBe(3);
  expect(Array.isArray(d.changes) && d.changes.length, 'AC-6①: changes must have exactly 3 entries').toBe(3);
  expect([...d.changes.map((c: any) => c.sqlViewName)].sort(), 'AC-6①: changes must be exactly the 3 views').toEqual([...H.VIEW_NAMES].sort());
  for (const c of d.changes) {
    expect(typeof c.newSqlTemplate === 'string' && c.newSqlTemplate.length > 20, `AC-6①: ${c.sqlViewName} newSqlTemplate empty`).toBe(true);
    expect(typeof c.oldSqlTemplate === 'string' && c.oldSqlTemplate.length > 20, `AC-6①: ${c.sqlViewName} oldSqlTemplate empty`).toBe(true);
    expect(H.hasIdent(c.newSqlTemplate, 'material_recipe'), `AC-6①: ${c.sqlViewName} newSqlTemplate lacks material_recipe`).toBe(true);
    expect(H.hasIdent(c.oldSqlTemplate, 'material_recipe'), `AC-6①: ${c.sqlViewName} oldSqlTemplate must not contain material_recipe`).toBe(false);
    const same = md5(c.oldSqlTemplate) === oldStoredMd5[c.sqlViewName];
    console.log(`[AC-6][${SRC}] ${c.sqlViewName} md5(oldSqlTemplate)=${md5(c.oldSqlTemplate)} stored-before=${oldStoredMd5[c.sqlViewName]} same=${same}`
      + (same ? '' : ' — note: api.md says oldSqlTemplate is the stored text; report 主线'));
  }

  // ② zero write on every item AC-6② lists
  const diffs = [
    ...H.diffRows('view', s0.views, s1.views, ['sql_md5', 'decl_md5', 'cfg_md5', 'builder_version', 'updated_at'],
      H.VIEW_NAMES.map((n) => H.viewRowByName(s0, n).id)),
    ...H.diffRows('component', s0.comps, s1.comps, ['fields_md5', 'formulas_md5', 'rkf_md5', 'updated_at']),
    ...H.diffRows('template', s0.templates, s1.templates, ['svs_md5', 'cs_md5', 'updated_at']),
  ];
  if (s0.tcsTableMd5 !== s1.tcsTableMd5) diffs.push(`template_component_snapshot table md5 ${s0.tcsTableMd5} → ${s1.tcsTableMd5}`);
  if (s0.opLogCount !== s1.opLogCount) diffs.push(`operation_log rows ${s0.opLogCount} → ${s1.opLogCount}`);
  // stricter extra (also asserted): the rest of component_sql_view did not move either
  if (s0.viewsTableMd5 !== s1.viewsTableMd5) diffs.push(`component_sql_view table md5 ${s0.viewsTableMd5} → ${s1.viewsTableMd5}`);
  console.log(`[AC-6②][${SRC}] diffs=${JSON.stringify(diffs)} viewsTableMd5 ${s0.viewsTableMd5}→${s1.viewsTableMd5} opLog ${s0.opLogCount}→${s1.opLogCount}`);
  expect(diffs, 'AC-6②: preview must write nothing').toEqual([]);
});

// ═══════════════════════════════════════════════════════════════════
// T3.5 · AC-7  execute
// ═══════════════════════════════════════════════════════════════════

test('T3.5 AC-7 ①②③ execute confirm=true: 3 views changed + 3 audit rows; text = AC-6 new text = full-recompile product; nothing else moved; E-2', async () => {
  test.skip(RESUME, 'R260916_RESUME_AC10=1: AC-7 evidence from the earlier run stands');
  expect(s1, 'T3.4 did not run in this process ⇒ no pre-state; rerun from part 2 start').toBeTruthy();
  const SRC = RESUME_EV ? '来源:run6实跑' : '来源:本轮实跑';
  const NOW = RESUME_EV ? '来源:续跑现查' : '来源:本轮实跑';
  const ac6 = H.readEvidJson('AC-6-预览响应.json');
  const newText: Record<string, string> = Object.fromEntries(ac6.body.data.changes.map((c: any) => [c.sqlViewName, c.newSqlTemplate]));

  let pre: { status: number; body: any; text: string };
  let ex: { status: number; body: any; text: string };
  let post: { status: number; body: any; text: string };
  let sPre: H.Snapshot;   // state right before execution
  let s3: H.Snapshot;     // state right after execution
  let sPost: H.Snapshot;  // state after the post-execution full preview
  let declBefore: Record<string, string[]> | null = null;

  if (RESUME_EV) {
    pre = asResp(mustEvid('AC-7-全量预览-执行前.json'));
    ex = asResp(mustEvid('AC-7-执行响应.json'));
    post = asResp(mustEvid('AC-7-全量预览-执行后.json'));
    s3 = mustEvid('AC-7-snapshot-执行后.json');
    // run 6 asserted s1≡s2 (zero write of the pre-execution full preview) as a hard expect BEFORE executing;
    // execution demonstrably happened (3 audit rows), so that expect passed ⇒ s1 is the pre-execution state.
    sPre = s1;
    // s4 was held in memory only; re-query now: anything that wrote to views after s3 (incl. the post preview) shows here.
    sPost = H.snapshot();
    H.writeEvid('AC-7-snapshot-续跑现查.json', sPost);
    H.appendMd5Report('续跑现查（AC-7 执行后、T3.6 之前）', sPost, '只读现查；与「AC-7 执行后」比对即可得出执行后全量预览及此后是否写过视图');
    console.log(`[AC-7][${SRC}] sPre.at=${sPre.at} s3.at=${s3.at}; [${NOW}] sPost.at=${sPost.at}`);
  } else {
    pre = await fullPreview();
    H.writeEvid('AC-7-全量预览-执行前.json', { status: pre.status, body: pre.body ?? pre.text });
    const s2 = H.snapshot();
    const fpDiff = [
      ...H.diffRows('view', s1.views, s2.views, ['sql_md5', 'decl_md5', 'cfg_md5', 'builder_version', 'updated_at']),
      ...H.diffRows('template', s1.templates, s2.templates, ['svs_md5', 'cs_md5', 'tsvs_md5', 'updated_at', 'tcs_md5']),
    ];
    if (s1.opLogCount !== s2.opLogCount) fpDiff.push(`operation_log ${s1.opLogCount} → ${s2.opLogCount}`);
    expect(pre.status, `full preview before: ${pre.text.slice(0, 300)}`).toBe(200);
    expect(fpDiff, 'full-recompile preview (confirm:false) must write nothing').toEqual([]);
    sPre = s2;
    declBefore = declaredNames();
    ex = await recompile(true);
    H.writeEvid('AC-7-执行响应.json', { request: { ...REQ, confirm: true }, status: ex.status, body: ex.body ?? ex.text });
    s3 = H.snapshot();
    H.writeEvid('AC-7-snapshot-执行后.json', s3);
    H.appendMd5Report('AC-7 执行后', s3);
    post = await fullPreview();
    H.writeEvid('AC-7-全量预览-执行后.json', { status: post.status, body: post.body ?? post.text });
    sPost = H.snapshot();
  }

  // ② (before execution) the full-recompile preview lists the 3 views
  expect(pre.status, `full preview before: ${pre.text.slice(0, 300)}`).toBe(200);
  const preNames = fullPreviewNames(pre.body, 'AC-7② before');
  console.log(`[AC-7][${SRC}] full preview before: ${preNames.length} names=${JSON.stringify(preNames)}`);
  expect(preNames, 'AC-7② (before execution): the full-recompile preview must list the 3 views').toEqual(expect.arrayContaining([...H.VIEW_NAMES]));

  // ① response + audit
  expect(ex.status, `AC-7①: HTTP; body=${ex.text.slice(0, 500)}`).toBe(200);
  const d = ex.body?.data;
  console.log(`[AC-7][${SRC}] preview=${d?.preview} changed=${d?.changed} changedViewNames=${JSON.stringify(d?.changedViewNames)} operationLogIds=${JSON.stringify(d?.operationLogIds)}`);
  expect(d?.preview, 'AC-7①: preview=false').toBe(false);
  expect(d?.changed, 'AC-7①: changed').toBe(3);
  expect([...(d?.changedViewNames ?? [])].sort(), 'AC-7①: changedViewNames').toEqual([...H.VIEW_NAMES].sort());
  const ids: string[] = d?.operationLogIds ?? [];
  expect(ids.length, 'AC-7①: operationLogIds must have exactly 3').toBe(3);
  expect(new Set(ids).size, 'AC-7①: operationLogIds must be distinct').toBe(3);
  const logs = H.sqlJson<{ id: string; operation_type: string; target_type: string; target_id: string; summary: string; details: any; created_at: string }>(
    `select id::text, operation_type, target_type, target_id::text, summary, details, created_at::text from operation_log where id in (${inList(ids)})`);
  H.writeEvid(RESUME_EV ? 'AC-7-operation_log-续跑现查.json' : 'AC-7-operation_log.json', logs);
  console.log(`[AC-7①][${NOW}] operation_log rows: ${JSON.stringify(logs.map((l) => [l.id, l.operation_type, l.target_type, l.target_id, l.created_at]))}`);
  expect(logs.length, 'AC-7①: every operationLogId must exist in operation_log').toBe(3);
  const viewToComp = Object.fromEntries(H.COMPS.map((c) => [c.view, c.id]));
  for (let i = 0; i < 3; i++) {
    const row = logs.find((l) => l.id === ids[i])!;
    expect(row.operation_type, `AC-7①: ${row.id} operation_type`).toBe('COMPONENT_VIEW_RECOMPILE');
    expect(row.target_type, `AC-7①: ${row.id} target_type`).toBe('COMPONENT');
    // api.md: operationLogIds are in the same order as changedViewNames ⇒ target_id is that view's component
    expect(row.target_id, `AC-7①: log ${row.id} should target the component of ${d.changedViewNames[i]}`).toBe(viewToComp[d.changedViewNames[i]]);
  }

  // ② text identity (stored text now vs AC-6 preview newSqlTemplate)
  const sqlAfter = currentSql();
  for (const v of H.VIEW_NAMES) {
    console.log(`[AC-7②][${NOW}] ${v} md5(stored)=${md5(sqlAfter[v])} md5(AC-6 new)=${md5(newText[v])}`);
    expect(sqlAfter[v], `AC-7②: stored sql_template of ${v} must equal AC-6 newSqlTemplate byte-for-byte`).toBe(newText[v]);
  }
  expect(post.status, `full preview after: ${post.text.slice(0, 300)}`).toBe(200);
  const postNames = fullPreviewNames(post.body, 'AC-7② after');
  console.log(`[AC-7][${SRC}] full preview after: ${postNames.length} names=${JSON.stringify(postNames)}`);
  expect(postNames.filter((n) => (H.VIEW_NAMES as readonly string[]).includes(n)),
    'AC-7②: right after execution the full-recompile preview must NOT list the 3 views (stored text = compile product)').toEqual([]);
  expect(H.diffRows('view', s3.views, sPost.views, ['sql_md5', 'updated_at']), 'full preview after execution must write nothing').toEqual([]);
  if (declBefore) {
    expect(declaredNames(), 'AC-7②: declared_columns name set must be unchanged').toEqual(declBefore);
  } else {
    // resume: before-state names were not persisted; declared_columns bytes (md5) before vs after are in the snapshots.
    // Byte-identical jsonb ⇒ identical name set (strictly stronger than the name-set check).
    const declDiff = H.VIEW_NAMES.map((n) => [n, H.viewRowByName(sPre, n).decl_md5, H.viewRowByName(s3, n).decl_md5]);
    console.log(`[AC-7②][${SRC}] declared_columns md5 before/after: ${JSON.stringify(declDiff)}; [${NOW}] names now: ${JSON.stringify(declaredNames())}`);
    expect(declDiff.filter(([, a, b]) => a !== b), 'AC-7②: declared_columns must be unchanged (md5 identical ⇒ name set unchanged)').toEqual([]);
  }
  for (const c of H.COMPS) {
    const g = await api.get(`/api/cpq/components/${c.id}/builder`);
    expect(g.status(), `GET builder ${c.code}`).toBe(200);
    const gj = await g.json(); // run-7 harness fix: GET /builder is NOT wrapped in {data} (seen in the browser probe)
    const gd = gj?.data ?? gj;
    const bv = Number(H.sqlScalar(`select builder_version from component_sql_view where sql_view_name='${c.view}'`));
    console.log(`[AC-7②][${NOW}] ${c.code} builder_version(db)=${bv} currentCompilerVersion=${gd?.currentCompilerVersion} isStale=${gd?.isStale}`);
    expect(typeof gd?.currentCompilerVersion, `${c.code}: GET /builder lacks currentCompilerVersion`).toBe('number');
    expect(bv, `AC-7②: ${c.code} builder_version must equal the current compiler version`).toBe(gd.currentCompilerVersion);
  }

  // ③ nothing else moved (pre-execution state vs right after execution)
  const threeIds = H.VIEW_NAMES.map((n) => H.viewRowByName(sPre, n).id);
  const otherIds = Object.keys(sPre.views).filter((id) => !threeIds.includes(id));
  const schneider = Object.values(sPre.templates).filter((t) => t.name === H.TEMPLATE_NAME).map((t) => t.id);
  expect(schneider.length, `AC-7③: no「${H.TEMPLATE_NAME}」versions found before execution`).toBeGreaterThan(0);
  const sameSource = H.sqlJson<{ code: string; n: string }>(
    `select c.code, v.sql_view_name n from component c join component_sql_view v on v.component_id=c.id where c.code in ('COMP-2413','COMP-2296','COMP-2414','COMP-2424')`);
  console.log(`[AC-7③][${SRC}] same-source components present: ${JSON.stringify(sameSource)}; other views checked: ${otherIds.length}; schneider versions: ${schneider.length}`);
  expect(sameSource.length, 'AC-7③: the named same-source components are missing ⇒ the "others unchanged" check would not cover them').toBeGreaterThan(0);
  const unchanged = [
    ...H.diffRows('component', sPre.comps, s3.comps, ['fields_md5', 'formulas_md5', 'rkf_md5', 'part_no_field', 'part_name_field', 'sort_field']),
    ...H.diffRows('schneider', sPre.templates, s3.templates, ['svs_md5', 'cs_md5', 'updated_at', 'tcs_md5'], schneider),
    ...H.diffRows('other-view', sPre.views, s3.views, ['sql_md5', 'updated_at'], otherIds),
    ...H.diffRows('any-template', sPre.templates, s3.templates, ['svs_md5', 'cs_md5', 'tsvs_md5', 'updated_at', 'tcs_md5']),
  ];
  if (sPre.tcsTableMd5 !== s3.tcsTableMd5) unchanged.push(`template_component_snapshot table md5 ${sPre.tcsTableMd5} → ${s3.tcsTableMd5}`);
  console.log(`[AC-7③][${SRC}] diffs=${JSON.stringify(unchanged)}`);
  expect(unchanged, 'AC-7③: execution must not touch components / templates / other views').toEqual([]);

  // E-2: the same observation means DO see the write
  const e2 = {
    viewsTableMd5Changed: sPre.viewsTableMd5 !== s3.viewsTableMd5,
    threeSqlChanged: H.VIEW_NAMES.every((n) => H.viewRowByName(sPre, n).sql_md5 !== H.viewRowByName(s3, n).sql_md5),
    opLogDelta: s3.opLogCount - sPre.opLogCount,
    recompileAuditDelta: s3.opLogRecompileForComps - sPre.opLogRecompileForComps,
  };
  const e2doc = { source: SRC, before: { at: sPre.at, viewsTableMd5: sPre.viewsTableMd5, opLogCount: sPre.opLogCount }, after: { at: s3.at, viewsTableMd5: s3.viewsTableMd5, opLogCount: s3.opLogCount }, ...e2 };
  H.writeEvid('E-2-观察手段阳性对照.json', e2doc);
  fs.appendFileSync(H.MD5_REPORT, `\n## E-2 阳性对照（${SRC}：AC-6 调用后 → AC-7 执行后）\n\n\`\`\`json\n${JSON.stringify(e2doc, null, 2)}\n\`\`\`\n`, 'utf-8');
  console.log(`[E-2][${SRC}] ${JSON.stringify(e2doc)}`);
  expect(e2, 'E-2: the md5/count gauges used for AC-6 must detect the AC-7 write').toEqual({
    viewsTableMd5Changed: true, threeSqlChanged: true, opLogDelta: 3, recompileAuditDelta: 3,
  });
});

test('T3.5 AC-7 ④ COMP-0004「取数配置」shows no stale hint (isStale=false)', async ({ page }) => {
  const blocked = await H.installWriteGuard(page);
  await H.uiLogin(page);
  const fp = H.viewFingerprint();
  const { get } = await H.openBuilderTab(page, C4);
  const panelText = await page.locator('.ant-tabs-tabpane-active').filter({ has: page.locator('.svb-recipe-bar') }).first().innerText();
  expect(panelText.length, 'AC-7④: builder tab text empty ⇒ gauge broken').toBeGreaterThan(20);
  await H.shot(page, 'AC-7-4-COMP-0004-取数配置-执行后');
  H.writeEvid('AC-7-4-builder-GET.json', get.raw);
  console.log(`[AC-7④] isStale=${get.isStale} blocked=${JSON.stringify(blocked)}`);
  expect(get.raw, 'AC-7④: GET /builder response not captured ⇒ 判【未验证】').toBeTruthy();
  expect(get.isStale, 'AC-7④: isStale must be false').toBe(false);
  expect(panelText, 'AC-7④: no「过期」-style hint on the builder tab').not.toMatch(/过期|stale/i);
  expect(H.viewFingerprint(), 'opening the builder must not write').toBe(fp);
});

// ═══════════════════════════════════════════════════════════════════
// T3.6 · AC-10  user-path sequence
// ═══════════════════════════════════════════════════════════════════

type Ac10State = { beforeTemplateIds?: string[]; latestBeforeId?: string; latestBeforeVersion?: string; newTemplateId?: string; newVersion?: string; quotationId?: string; quotationNumber?: string; oldQuotationId?: string };
const loadState = (): Ac10State => H.readEvidJson<Ac10State>(H.AC10_STATE.split('/').pop()!) ?? {};
const saveState = (s: Ac10State) => H.writeEvid(H.AC10_STATE.split('/').pop()!, s);
const verNum = (v: string) => (v.match(/v?(\d+)\.(\d+)/) || []).slice(1).map(Number);
const verGt = (a: string, b: string) => { const [a1, a2] = verNum(a), [b1, b2] = verNum(b); return a1 > b1 || (a1 === b1 && a2 > b2); };

async function tabNames(page: Page, tab: string, part: string, why: string) {
  await H.clickCardTab(page, tab);
  const t = await H.readCardTable(page);
  const names = H.namesOf(t, part, why);
  console.log(`[AC-10] ${why}: ${tab} ${part} → ${JSON.stringify(names)}`);
  return { t, names };
}

test('T3.6 AC-10 steps 1–8: preview → recompile → publish new version → new quote → switch tabs → F5 → detail → old quote still「—」', async ({ page }) => {
  test.setTimeout(1_500_000);
  const st = loadState();
  await H.uiLogin(page);

  // ── step 1: COMP-0004 preview → 00144 = H85 (pre-execution copy archived by part 1 as AC-10-step1-…-执行前; re-checked now)
  {
    const blocked = await H.installWriteGuard(page);
    const { get } = await H.openBuilderTab(page, C4);
    const partCol = (get.builderConfig?.columns ?? []).find((c: any) => c.fieldName === '料号')?.viewColumn;
    expect(partCol, 'step1: builder config has no 料号 column').toBeTruthy();
    const pr = await H.runPreview(page, H.CUSTOMER_CODE, 'S3120011203', 'AC-10 step1');
    await H.shot(page, 'AC-10-step1-COMP-0004-预览-执行后');
    const m = H.namesByPart(pr.rows, partCol);
    expect(m['00144'], 'AC-10 step1: 00144 → H85').toEqual(['H85']);
    await page.unroute('**/api/**');
    if (blocked.length) console.log(`[AC-10 step1] aborted writes: ${JSON.stringify(blocked)}`);
  }

  // ── step 2: recompile (preview → execute) ⇒ AC-7 ①② — executed once in T3.5; re-verify the resulting state
  {
    const ex = H.readEvidJson('AC-7-执行响应.json');
    const ac6 = H.readEvidJson('AC-6-预览响应.json');
    expect(ex?.status === 200 && ex?.body?.data?.changed === 3, 'AC-10 step2: AC-7 execution evidence missing or not changed=3').toBe(true);
    const again = await recompile(false);
    H.writeEvid('AC-10-step2-再次预览.json', { status: again.status, body: again.body ?? again.text });
    expect(again.status).toBe(200);
    expect(again.body?.data?.changed, 'AC-10 step2: after execution, a new preview must report changed=0').toBe(0);
    const now = currentSql();
    for (const c of ac6.body.data.changes) expect(now[c.sqlViewName], `AC-10 step2: ${c.sqlViewName} stored text = AC-6 new text`).toBe(c.newSqlTemplate);
  }

  // ── step 3: template → new draft from the latest version → publish
  const tplRows = H.sqlJson<{ id: string; version: string; status: string; created_at: string }>(
    `select id::text, version, status, created_at::text from template where name='${H.TEMPLATE_NAME}' order by created_at`);
  if (!st.newTemplateId) {
    expect(tplRows.filter((t) => t.status === 'DRAFT'), `step3 precondition: an existing DRAFT of ${H.TEMPLATE_NAME} exists ⇒ report 主线`).toEqual([]);
    const latest = tplRows[tplRows.length - 1];
    expect(latest?.status, 'step3 precondition: latest version must be PUBLISHED').toBe('PUBLISHED');
    st.beforeTemplateIds = tplRows.map((t) => t.id);
    st.latestBeforeId = latest.id; st.latestBeforeVersion = latest.version;
    saveState(st);
    await H.gotoReady(page, `/templates/${latest.id}`, 'button:has-text("创建新草稿")', 'step3 template page');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(3000);
    await H.shot(page, 'AC-10-step3a-模板最新版本');
    const draftResp = page.waitForResponse((r) => r.request().method() === 'POST' && /\/templates\/[^/]+\/new-draft(\?|$)/.test(r.url()), { timeout: 60_000 });
    await page.locator('button').filter({ hasText: /创建新草稿/ }).first().click();
    await page.waitForTimeout(800);
    const cm = page.locator('.ant-modal:visible .ant-btn-primary, .ant-popover:visible .ant-btn-primary').first();
    if (await cm.isVisible().catch(() => false)) await cm.click();
    const dr = await draftResp;
    const dj = await dr.json().catch(() => null);
    expect(dr.ok(), `step3: new-draft returned ${dr.status()} ${JSON.stringify(dj).slice(0, 300)}`).toBe(true);
    st.newTemplateId = dj?.data?.id;
    expect(st.newTemplateId, 'step3: new-draft response has no id').toMatch(/^[0-9a-f-]{36}$/);
    saveState(st);
    await page.waitForTimeout(3000);
  }
  // Publish step is resumable: a draft created by an interrupted run is published here instead of creating another one.
  const draftStatus = H.sqlScalar(`select status from template where id='${st.newTemplateId}'`);
  console.log(`[AC-10 step3] new template ${st.newTemplateId} status=${draftStatus}`);
  if (draftStatus === 'DRAFT') {
    if (!page.url().includes(st.newTemplateId!)) await H.gotoReady(page, `/templates/${st.newTemplateId}`, '.ant-tabs, button', 'step3 draft page');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(3000);
    await H.shot(page, 'AC-10-step3b-新草稿');
    const pubResp = page.waitForResponse((r) => r.request().method() === 'POST' && new RegExp(`/templates/${st.newTemplateId}/publish`).test(r.url()), { timeout: 90_000 });
    const pubBtn = page.locator('button:visible').filter({ hasText: /^\s*(发\s*布|发布模板|发布新版本)\s*$/ }).first();
    await expect(pubBtn, 'step3: publish button not found on the draft page ⇒ 入口问题').toBeEnabled({ timeout: 30_000 });
    await pubBtn.click();
    for (let i = 0; i < 3; i++) {
      await page.waitForTimeout(1200);
      const ok = page.locator('.ant-modal:visible, .ant-popover:visible').locator('.ant-btn-primary').last();
      if (await ok.isVisible().catch(() => false)) await ok.click(); else break;
    }
    const pr = await pubResp;
    expect(pr.ok(), `step3: publish returned ${pr.status()} ${(await pr.text()).slice(0, 300)}`).toBe(true);
    await page.waitForTimeout(3000);
    await H.shot(page, 'AC-10-step3c-新版本已发布');
  }
  const newTpl = H.sqlJson<{ status: string; version: string }>(`select status, version from template where id='${st.newTemplateId}'`)[0];
  expect(newTpl?.status, 'step3: new template must be PUBLISHED').toBe('PUBLISHED');
  expect(verGt(newTpl.version, st.latestBeforeVersion!), `step3: new version ${newTpl.version} must be newer than ${st.latestBeforeVersion}`).toBe(true);
  st.newVersion = newTpl.version; saveState(st);
  for (const c of H.COMPS) { // supporting: the new version froze the recompiled text
    const row = H.sqlJson<{ has_recipe: boolean; eq_current: boolean; in_old_latest: boolean }>(
      `select position('material_recipe' in (t.sql_views_snapshot -> '${c.id}::${c.view}' ->> 'sql_template')) > 0 has_recipe,
              (t.sql_views_snapshot -> '${c.id}::${c.view}' ->> 'sql_template') = v.sql_template eq_current,
              position('material_recipe' in coalesce((o.sql_views_snapshot -> '${c.id}::${c.view}' ->> 'sql_template'),'')) > 0 in_old_latest
         from template t, component_sql_view v, template o
        where t.id='${st.newTemplateId}' and v.sql_view_name='${c.view}' and o.id='${st.latestBeforeId}'`)[0];
    console.log(`[AC-10 step3] ${c.view} frozen snapshot: new version has material_recipe=${row?.has_recipe} equals current=${row?.eq_current}; previous latest has material_recipe=${row?.in_old_latest}`);
  }

  // ── step 4: new quotation (CUST-0004, new template version) + product S3120011203 + save draft
  if (!st.quotationId) {
    const existing = H.sqlScalar(`select count(*) from quotation where project_name='${H.PROJECT_NAME}'`);
    expect(existing, `step4: a quotation with project ${H.PROJECT_NAME} already exists but is not in the state file ⇒ report 主线`).toBe('0');
    await H.gotoReady(page, '/quotations/new', '.ant-steps-item', 'step4 new quotation');
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);
    const custItem = page.locator('.ant-form-item').filter({ has: page.locator('label', { hasText: /^客户$/ }) }).first();
    await custItem.locator('.ant-select').first().click();
    await page.keyboard.type(H.CUSTOMER_CODE, { delay: 50 });
    await page.waitForTimeout(1500);
    await page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option').filter({ hasText: H.CUSTOMER_CODE }).locator('visible=true').first().click();
    await page.waitForTimeout(3000);
    // run-8 harness fix: after picking the customer the name field's placeholder is「请填写报价单名称」(probe p9) ⇒ locate by label.
    const nameInput = page.locator('.ant-form-item').filter({ has: page.locator('label', { hasText: /^报价单名称$/ }) }).locator('input').locator('visible=true').first();
    await expect(nameInput, 'step4: 报价单名称 input missing').toBeVisible({ timeout: 20_000 });
    await nameInput.fill(`${H.PROJECT_NAME} 正泰`);
    await page.locator('input[placeholder="关联项目"]').first().fill(H.PROJECT_NAME);
    const tplItem = page.locator('.ant-form-item').filter({ has: page.locator('label', { hasText: '报价模板' }) }).first();
    await expect(tplItem, 'step4: 报价模板 field missing').toBeVisible({ timeout: 20_000 });
    await tplItem.locator('.ant-select').first().click();
    await page.waitForTimeout(1200);
    const opt = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')
      .filter({ hasText: new RegExp(`${H.TEMPLATE_NAME.replace('.', '\\.')}\\s+${st.newVersion!.replace('.', '\\.')}(\\s|$)`) }).locator('visible=true').first();
    await expect(opt, `step4: template option「${H.TEMPLATE_NAME} ${st.newVersion}」not offered`).toBeVisible({ timeout: 15_000 });
    await opt.click();
    await page.keyboard.press('Escape');
    await page.waitForTimeout(800);
    expect(await tplItem.innerText(), 'step4: selected template').toContain(st.newVersion!);
    await H.shot(page, 'AC-10-step4a-新建报价单-Step1');
    const createResp = page.waitForResponse((r) => r.request().method() === 'POST' && /\/api\/cpq\/quotations(\?|$)/.test(r.url()), { timeout: 60_000 }).catch(() => null);
    const next = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
    await expect(next, 'step4: 下一步 not enabled').toBeEnabled({ timeout: 30_000 });
    await next.click();
    const cr = await createResp;
    let qid: string | undefined = cr ? (await cr.json().catch(() => null))?.data?.id : undefined;
    if (!qid) qid = H.sqlScalar(`select id::text from quotation where project_name='${H.PROJECT_NAME}' order by created_at desc limit 1`) || undefined;
    expect(qid, 'step4: quotation was not created').toMatch(/^[0-9a-f-]{36}$/);
    st.quotationId = qid;
    st.quotationNumber = H.sqlScalar(`select quotation_number from quotation where id='${qid}'`);
    saveState(st);
    await page.waitForFunction(() => ((document.querySelector('.ant-steps-item-active') as HTMLElement)?.innerText || '').includes('添加产品'), undefined, { timeout: 120_000 });
    await page.waitForTimeout(3000);
    await page.locator('button').filter({ hasText: /添加产品/ }).first().click();
    await page.waitForTimeout(1500);
    await page.locator('.ant-dropdown:visible .ant-dropdown-menu-item').filter({ hasText: /从已有产品添加/ }).first().click();
    const drawer = page.locator('.ant-drawer').filter({ hasText: '从已有产品' }).first();
    await expect(drawer, 'step4: add-product drawer did not open').toBeVisible({ timeout: 30_000 });
    await drawer.locator('input[placeholder="销售料号"]').fill('S3120011203');
    await drawer.locator('button').filter({ hasText: /^\s*查\s*询\s*$/ }).click();
    await page.waitForTimeout(3000);
    const row = drawer.locator('.ant-table-row').filter({ hasText: 'S3120011203' }).filter({ hasText: 'W003374021711' }).first();
    await expect(row, 'step4: product row S3120011203 / W003374021711 not offered').toBeVisible({ timeout: 20_000 });
    await row.locator('input[type="checkbox"]').first().click();
    await H.shot(page, 'AC-10-step4b-添加产品');
    await drawer.locator('button').filter({ hasText: /加\s*入\s*报\s*价\s*单/ }).last().click();
    await expect(page.locator('.qt-product-card').first(), 'step4: product card not rendered').toBeVisible({ timeout: 120_000 });
    await page.waitForTimeout(8000);
    const saveResp = page.waitForResponse((r) => r.request().method() === 'PUT' && /\/quotations\/[^/]+\/draft/.test(r.url()), { timeout: 120_000 });
    await page.getByRole('button', { name: /保\s*存\s*草\s*稿/ }).first().click();
    const sr = await saveResp;
    expect(sr.ok(), `step4: save draft returned ${sr.status()}`).toBe(true);
    await page.waitForTimeout(4000);
  } else {
    console.log(`[AC-10] reusing quotation ${st.quotationNumber} (${st.quotationId}) from state file`);
    await H.openEditStep2(page, st.quotationId);
  }
  const q = H.sqlJson<{ project_name: string; tpl: string; items: number }>(
    `select q.project_name, q.customer_template_id::text tpl, (select count(*) from quotation_line_item li where li.quotation_id=q.id and li.product_part_no_snapshot='S3120011203') items from quotation q where q.id='${st.quotationId}'`)[0];
  console.log(`[AC-10 step4] quotation ${st.quotationNumber}: ${JSON.stringify(q)}`);
  expect(q, 'step4: quotation row').toBeTruthy();
  expect(q.project_name).toBe(H.PROJECT_NAME);
  expect(q.tpl, 'step4: quotation must use the NEW template version').toBe(st.newTemplateId);
  expect(q.items, 'step4: saved draft must contain product S3120011203').toBeGreaterThan(0);

  let r = await tabNames(page, '来料固定加工费', '00144', 'step4');
  expect(r.names, 'AC-10 step4: 来料固定加工费 00144 → H85').toEqual(r.names.map(() => 'H85'));
  expect(H.namesOf(r.t, 'S3110520422', 'step4'), 'AC-10 step4: S3110520422 → 料号2').toEqual(['料号2']);
  await H.assertNoBadStates(page, 'step4');
  await H.shot(page, 'AC-10-step4c-来料固定加工费');

  // ── step 5: switch tabs and back
  r = await tabNames(page, '来料其他费用', '00144', 'step5a');
  expect(r.names, 'AC-10 step5: 来料其他费用 00144 → H85').toEqual(r.names.map(() => 'H85'));
  await H.shot(page, 'AC-10-step5a-来料其他费用');
  r = await tabNames(page, '来料回收', '00144', 'step5b');
  expect(r.names, 'AC-10 step5: 来料回收 00144 → H85').toEqual(r.names.map(() => 'H85'));
  await H.shot(page, 'AC-10-step5b-来料回收');
  r = await tabNames(page, '来料固定加工费', '00144', 'step5c');
  expect(r.names, 'AC-10 step5: back on 来料固定加工费 00144 still H85').toEqual(r.names.map(() => 'H85'));
  await H.assertNoBadStates(page, 'step5');
  await H.shot(page, 'AC-10-step5c-切回来料固定加工费');

  // ── step 6: F5
  await page.reload();  // the AC's F5
  await page.waitForLoadState('networkidle');
  if (!(await page.locator('.ant-steps-item, button.qt-tab-btn').first().isVisible({ timeout: 45_000 }).catch(() => false))) {
    console.log('[AC-10 step6] page blank 45s after F5 (known 5196 dev-server flake) — reloading once more');
    await page.reload();
  }
  console.log(`[AC-10 step6] url after reload: ${page.url()}`);
  if (page.url().includes(`/quotations/${st.quotationId}/edit`)) await H.gotoStep2FromStep1(page);
  else { console.log('[AC-10 step6] note: reload did not land on the edit route of the new quote; navigating explicitly'); await H.openEditStep2(page, st.quotationId!); }
  r = await tabNames(page, '来料固定加工费', '00144', 'step6');
  expect(r.names, 'AC-10 step6: after F5 00144 → H85').toEqual(r.names.map(() => 'H85'));
  await H.assertNoBadStates(page, 'step6');
  await H.shot(page, 'AC-10-step6-刷新后');

  // ── step 7: detail page
  await H.gotoReady(page, `/quotations/${st.quotationId}`, 'button.qt-tab-btn', 'step7 detail');
  await page.waitForFunction(() => document.querySelectorAll('button.qt-tab-btn').length > 0, undefined, { timeout: 120_000 });
  await page.waitForTimeout(3000);
  r = await tabNames(page, '来料固定加工费', '00144', 'step7');
  expect(r.names, 'AC-10 step7: detail page 00144 → H85').toEqual(r.names.map(() => 'H85'));
  await H.assertNoBadStates(page, 'step7');
  await H.shot(page, 'AC-10-step7-详情页');

  // ── step 8 (reverse): a pre-existing quotation on an older version still shows「—」
  const before = st.beforeTemplateIds ?? tplRows.filter((t) => t.id !== st.newTemplateId).map((t) => t.id);
  const shotAt = '2026-09-17 01:09:23+00'; // shot_20260916_180923 (PDT) — the user's screenshot
  const old = process.env.R260916_OLD_QNO
    ? H.sqlJson<{ id: string; qn: string; tpl: string }>(`select id::text, quotation_number qn, customer_template_id::text tpl from quotation where quotation_number='${process.env.R260916_OLD_QNO}'`)[0]
    : H.sqlJson<{ id: string; qn: string; tpl: string }>(`
        select q.id::text, q.quotation_number qn, q.customer_template_id::text tpl from quotation q
         where q.id <> '${st.quotationId}' and q.customer_template_id in (${inList(before)})
           and exists (select 1 from quotation_line_item li where li.quotation_id=q.id and li.customer_part_no='W003374021711')
         order by (q.created_at <= '${shotAt}') desc, q.created_at desc limit 1`)[0];
  expect(old, 'step8: no pre-existing quotation with W003374021711 on an older version').toBeTruthy();
  expect(before, 'step8: chosen quotation must use a version that existed before step 3').toContain(old.tpl);
  const stamp = () => H.sqlScalar(`select q.updated_at::text||' / '||coalesce(max(li.quote_values_at)::text,'-')||' / '||coalesce(max(li.card_snapshot_at)::text,'-') from quotation q left join quotation_line_item li on li.quotation_id=q.id where q.id='${old.id}' group by q.updated_at`);
  const stampBefore = stamp();
  await H.gotoReady(page, `/quotations/${old.id}`, 'button.qt-tab-btn', 'step8 old detail');
  await page.waitForFunction(() => document.querySelectorAll('button.qt-tab-btn').length > 0, undefined, { timeout: 120_000 });
  await page.waitForTimeout(3000);
  r = await tabNames(page, '来料固定加工费', '00144', `step8 ${old.qn}`);
  expect(H.namesOf(r.t, 'S3110520422', 'step8'), 'step8 positive control: S3110520422 → 料号2 (table really rendered)').toEqual(['料号2']);
  expect(r.names, `AC-10 step8: old quotation ${old.qn} 00144 must still be「—」`).toEqual(r.names.map(() => '—'));
  await H.assertNoBadStates(page, 'step8');
  await H.shot(page, `AC-10-step8-旧版本报价单-${old.qn}`);
  const stampAfter = stamp();
  st.oldQuotationId = old.id; saveState(st);
  H.writeEvid('AC-10-step8-旧单时间戳.txt', `${old.qn}\nbefore: ${stampBefore}\nafter:  ${stampAfter}\n${stampBefore === stampAfter ? 'unchanged' : '⚠️ CHANGED by read-only open — report 主线'}\n`);
  if (stampBefore !== stampAfter) console.log(`[AC-10 step8] ⚠️ opening the old quotation changed its timestamps: ${stampBefore} → ${stampAfter}`);
});
