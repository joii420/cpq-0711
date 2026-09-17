/**
 * repair-260916 · S-global · part 1 (read-only UI segment): T0 gauges → T3.1 AC-2 → T3.2 AC-3 → T3.3 AC-4
 *
 * Derived only from `问题说明.md ⑥` AC text + `test.md §3/§4`. No implementation code was read.
 *
 * 🚦 Write surface of THIS file: none.
 *    · Every non-GET /api call except compile/preview/inspect/row-key-candidates/semantic-graph is ABORTED
 *      (installWriteGuard) — the 3 target views have exactly one "before" state (test.md §0.3) which AC-6 needs.
 *    · Each test additionally asserts the DB fingerprint of the 3 views is unchanged (hard).
 *    · Nothing is ever saved; dataset/source switches are UI-local.
 *
 * ⚠️ Deviation to confirm with 主线 (reported, not silently reinterpreted):
 *    AC-2/AC-3 core-costing parts say「新建组件 → 数据集选…」. Creating a component is a DB write outside this
 *    slice's write surface (test.md §1: only 3 views / operation_log / one template version / one quotation).
 *    This file therefore performs the same dataset → source → field steps inside COMP-0004's builder WITHOUT
 *    saving (same panel, same compile endpoint). Set R260916_NEWCOMP=1 only if 主线 approves writing one
 *    `R260916-G-` component (then the literal「新建组件」path is used; cleanup = 待回收清单, no DELETE here).
 *
 * Run (after 主线 unlocks; 8196/5196 started by 主线; check `pgrep -f "node.*[p]laywright test"` first):
 *   cd cpq-frontend && npx playwright test --config=e2e/repair260916-global.config.ts
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import * as H from './repair260916-global.helpers';

test.describe.configure({ mode: 'serial' });

const C4 = H.COMPS[0], C5 = H.COMPS[1], C8 = H.COMPS[2];

// Remove a stale marker up front: the write segment must only ever see a marker produced by THIS run.
test.beforeAll(() => { if (fs.existsSync(H.READONLY_MARKER)) fs.unlinkSync(H.READONLY_MARKER); });

async function guardedPage(page: Page) {
  const blocked = await H.installWriteGuard(page);
  await H.uiLogin(page);
  return blocked;
}
function finishGuard(tag: string, fpBefore: string, blocked: string[]) {
  if (blocked.length) {
    H.writeEvid(`${tag}-blocked-writes.txt`, blocked.join('\n'));
    console.log(`[guard] ${tag}: UI attempted ${blocked.length} write(s), all aborted — see evidence (report to 主线)`);
  }
  expect(H.viewFingerprint(),
    `🚨 ${tag}: the 3 target views changed during a read-only test ⇒ one-shot state consumed; STOP and report 主线 (test.md §0.3)`)
    .toBe(fpBefore);
}

// ═══════════════════════════════════════════════════════════════════
// T0 · gauges (testing.md §4.4 / §5.5) + environment identity
// ═══════════════════════════════════════════════════════════════════

test('T0.1 env: 5196 → 8196 → cpq_db_260916 identity, V444 applied', async () => {
  await H.assertAcceptanceEnv('S-global part1 start');
  const base = H.baselineViewMd5();
  const now = H.sqlJson<{ sql_view_name: string; m: string }>(
    `select sql_view_name, md5(sql_template) m from component_sql_view where sql_view_name in (${H.VIEW_NAMES.map((v) => `'${v}'`).join(',')})`);
  const audit = H.sqlScalar(`select count(*) from operation_log where operation_type='COMPONENT_VIEW_RECOMPILE'`);
  console.log(`[one-shot] baseline=${JSON.stringify(base)} now=${JSON.stringify(now)} recompileAudit=${audit}`);
  H.writeEvid('T0-one-shot-state.json', { at: new Date().toISOString(), baseline: base, now, recompileAudit: audit });
});

test('T0.2 gauge E-1: the AC-4/AC-10 discriminator says "NOT fixed" on the pre-change baseline and "fixed" on a fixed copy', async () => {
  const f = path.join(H.BASELINE_DIR, 'E-1-COMP-0004-预览-8081.json');
  expect(fs.existsSync(f), `E-1 baseline file missing: ${f} ⇒ E-1 positive control unavailable, report 主线`).toBe(true);
  const base = JSON.parse(fs.readFileSync(f, 'utf-8'));
  const rows: any[] = base.rows ?? base.data?.rows ?? [];
  expect(rows.length, 'E-1 baseline has 0 rows ⇒ cannot serve as positive control').toBeGreaterThan(0);
  const partCol = '_来料固定加工费_投入料号';
  const judge = (rs: any[]) => {
    const m = H.namesByPart(rs, partCol);
    return (m['00144'] ?? []).length > 0 && m['00144'].every((v) => v === 'H85') && rs.every((r) => !H.isBlank(r[H.MATERIAL_COL]));
  };
  const onBaseline = judge(rows);
  const fixed = rows.map((r) => (r[partCol] === '00144' ? { ...r, [H.MATERIAL_COL]: 'H85' } : r));
  console.log(`[E-1] baseline 00144 → ${JSON.stringify(H.namesByPart(rows, partCol)['00144'])}; judge(baseline)=${onBaseline}; judge(fixed)=${judge(fixed)}`);
  expect(onBaseline, 'E-1: discriminator must reject the pre-change baseline (00144 material name null)').toBe(false);
  expect(judge(fixed), 'E-1: discriminator must accept a fixed copy').toBe(true);
});

test('T0.3 gauge: SQL-shape helpers reject the pre-change COMP-0004 SQL and accept the COMP-0002 shape', async () => {
  const before = JSON.parse(fs.readFileSync(path.join(H.BASELINE_DIR, 'compile-COMP-0004-8081.json'), 'utf-8'));
  const sqlBefore: string = before.sql ?? before.data?.sql;
  expect(sqlBefore?.length ?? 0, 'baseline compile SQL missing').toBeGreaterThan(20);
  expect(H.coalesceMatch(sqlBefore), 'gauge: pre-change SQL has no COALESCE(material_name, symbol) — must be null').toBeNull();
  expect(H.leftJoinsOf(sqlBefore, 'material_recipe').length, 'gauge: pre-change SQL has no material_recipe join').toBe(0);
  expect(H.leftJoinsOf(sqlBefore, 'ds_quote_material').length, 'gauge: boundary-aware join finder must see ds_quote_material once').toBe(1);
  const synthetic = 'SELECT COALESCE(dqm.material_name, mr.symbol) AS "_物料_材料名" FROM ds_quote_incoming_fixed_fee dqiff ' +
    'LEFT JOIN ds_quote_material dqm ON dqm.material_no = dqiff.input_material_no AND dqm.customer_no = dqiff.customer_no ' +
    'LEFT JOIN material_recipe mr ON mr.code = dqiff.input_material_no WHERE 1=1';
  const c = H.coalesceMatch(synthetic)!;
  expect(c && c.asCol, 'gauge: COALESCE matcher').toBe(H.MATERIAL_COL);
  const rec = H.leftJoinsOf(synthetic, 'material_recipe')[0];
  expect(H.isEquality(H.onConditions(rec.on)[0], rec.alias, 'code', 'dqiff', 'input_material_no'), 'gauge: equality matcher').toBe(true);
  expect(H.leftJoinsOf('LEFT JOIN ds_quote_material_bom b ON b.x = a.x', 'ds_quote_material').length,
    'gauge: ds_quote_material must NOT match ds_quote_material_bom').toBe(0);
});

// ═══════════════════════════════════════════════════════════════════
// Shared assertions
// ═══════════════════════════════════════════════════════════════════

function assertOneMaterialName(panel: H.FieldPanel, why: string, groupHead?: string) {
  const all = panel.groups.flatMap((g) => g.fields.filter((f) => f === '材料名').map(() => g.head));
  const materialGroups = panel.groups.filter((g) => g.head === '材质' || g.head === '材质（查名）' || g.head === '材质(查名)');
  console.log(`[AC-2] ${why}: groups=${JSON.stringify(panel.groups.map((g) => `${g.head}(${g.fields.length})`))} 材料名 in=${JSON.stringify(all)}`);
  if (groupHead) {
    const g = panel.groups.find((x) => x.head === groupHead);
    expect(g, `${why}: group「${groupHead}」not found; groups=${JSON.stringify(panel.groups.map((x) => x.head))}`).toBeTruthy();
    expect(g!.fields.filter((f) => f === '材料名').length, `${why}: group「${groupHead}」should contain exactly 1「材料名」; fields=${JSON.stringify(g!.fields)}`).toBe(1);
  }
  expect(all.length, `${why}: whole field pane should contain exactly 1「材料名」, found in ${JSON.stringify(all)}`).toBe(1);
  expect(materialGroups.map((g) => g.head), `${why}: a standalone「材质」/「材质（查名）」group must not exist`).toEqual([]);
  const suspicious = panel.groups.filter((g) => /材质/.test(g.head)).map((g) => g.head);
  if (suspicious.length) console.log(`[AC-2] ${why}: note — group heads containing「材质」: ${JSON.stringify(suspicious)}`);
}

type CostCase = { dataset: '基础核价' | '明细核价'; prefix: string; source: string; materialTable: string; tag: string };
const COST_CASES: CostCase[] = [
  { dataset: '基础核价', prefix: 'ds_cost_basic_', source: '来料加工费', materialTable: 'ds_cost_basic_material', tag: 'basic-来料加工费' },
  { dataset: '明细核价', prefix: 'ds_cost_detail_', source: '来料其他固定费用', materialTable: 'ds_cost_detail_material', tag: 'detail-来料其他固定费用' },
];

async function enterCostSource(page: Page, cc: CostCase) {
  if (process.env.R260916_NEWCOMP === '1') {
    throw new Error('R260916_NEWCOMP=1 path is reserved for an explicit 主线 approval and is not implemented in this read-only file; report 主线');
  }
  await H.openBuilderTab(page, C4);
  await H.selectDataset(page, cc.dataset, cc.prefix);
  await H.selectSource(page, cc.source);
  await H.expandAllGroups(page);
}

// ═══════════════════════════════════════════════════════════════════
// T3.1 · AC-2
// ═══════════════════════════════════════════════════════════════════

test('T3.1 AC-2 ① COMP-0004「取数配置」: group「来料固定加工费」has exactly one「材料名」; no「材质」group', async ({ page }) => {
  const fp = H.viewFingerprint();
  const blocked = await guardedPage(page);
  try {
    const { get } = await H.openBuilderTab(page, C4);
    console.log(`[T3.1] GET /builder isStale(before execution)=${get.isStale} builderVersion=${get.builderVersion} current=${get.currentCompilerVersion}`);
    await H.expandAllGroups(page);
    const panel = await H.readFieldPanel(page);
    H.writeEvid('AC-2-COMP-0004-字段面板.json', panel);
    await H.shot(page, 'AC-2-1-COMP-0004-可用字段');
    assertOneMaterialName(panel, 'AC-2 COMP-0004', '来料固定加工费');
  } finally { finishGuard('AC-2-1', fp, blocked); }
});

for (const cc of COST_CASES) {
  test(`T3.1 AC-2 ② ${cc.dataset} → ${cc.source}: exactly one「材料名」; no「材质」group`, async ({ page }) => {
    const fp = H.viewFingerprint();
    const blocked = await guardedPage(page);
    try {
      await enterCostSource(page, cc);
      const panel = await H.readFieldPanel(page);
      H.writeEvid(`AC-2-${cc.tag}-字段面板.json`, panel);
      await H.shot(page, `AC-2-2-${cc.tag}-可用字段`);
      assertOneMaterialName(panel, `AC-2 ${cc.dataset}/${cc.source}`);
    } finally { finishGuard(`AC-2-${cc.tag}`, fp, blocked); }
  });
}

// ═══════════════════════════════════════════════════════════════════
// T3.2 · AC-3
// ═══════════════════════════════════════════════════════════════════

test('T3.2 AC-3 ①②③ COMP-0004「生成的 SQL」: COALESCE(物料.material_name, 材质.symbol) AS "_物料_材料名"; recipe join without customer; material join keeps 2 keys', async ({ page }) => {
  const fp = H.viewFingerprint();
  const blocked = await guardedPage(page);
  try {
    const { compile } = await H.openBuilderTab(page, C4);
    const sql = await H.sqlPaneText(page);
    H.writeEvid('AC-3-COMP-0004-生成的SQL.txt', sql);
    await H.shot(page, 'AC-3-COMP-0004-生成的SQL');
    if (compile?.sql) {
      expect(H.normSql(sql), 'SQL pane text differs from the compile response ⇒ pane is stale, 判【未验证】').toBe(H.normSql(compile.sql));
    } else {
      console.log('[T3.2] no compile response captured on open; relying on pane text only');
    }
    console.log(`[AC-3] COMP-0004 SQL:\n${sql}`);

    const co = H.coalesceMatch(sql);
    expect(co, `AC-3①: no COALESCE(<a>.material_name, <b>.symbol) in SQL:\n${sql}`).not.toBeNull();
    expect(co!.asCol, `AC-3①: COALESCE column alias must be literally "${H.MATERIAL_COL}"; got ${co!.asCol}`).toBe(H.MATERIAL_COL);
    expect(sql, 'AC-3①: the old single-source form must be gone').not.toMatch(/(?<!COALESCE\s*\()\b[A-Za-z_]+\.material_name\s+AS\s+"_物料_材料名"/);

    const anchor = H.fromAlias(sql, 'ds_quote_incoming_fixed_fee');
    expect(anchor, `AC-3: cannot find FROM ds_quote_incoming_fixed_fee <alias>:\n${sql}`).not.toBeNull();

    const rec = H.leftJoinsOf(sql, 'material_recipe');
    expect(rec.length, `AC-3②: expected exactly one LEFT JOIN material_recipe, got ${rec.length}`).toBe(1);
    const recConds = H.onConditions(rec[0].on);
    console.log(`[AC-3②] recipe join alias=${rec[0].alias} ON ${rec[0].on}`);
    expect(recConds.length, `AC-3②: recipe ON must have exactly one condition; got ${JSON.stringify(recConds)}`).toBe(1);
    expect(H.isEquality(recConds[0], rec[0].alias, 'code', anchor!, 'input_material_no'),
      `AC-3②: recipe ON should be ${rec[0].alias}.code = ${anchor}.input_material_no; got「${recConds[0]}」`).toBe(true);
    expect(rec[0].on, 'AC-3②: recipe ON must not contain customer_no').not.toMatch(/customer_no/i);

    const mat = H.leftJoinsOf(sql, 'ds_quote_material');
    expect(mat.length, `AC-3③: expected exactly one LEFT JOIN ds_quote_material (boundary-aware), got ${mat.length}`).toBe(1);
    const matConds = H.onConditions(mat[0].on);
    console.log(`[AC-3③] material join alias=${mat[0].alias} ON ${mat[0].on}`);
    expect(matConds.length, `AC-3③: material ON must still have 2 conditions; got ${JSON.stringify(matConds)}`).toBe(2);
    expect(matConds.some((c) => /\binput_material_no\b/.test(c)), `AC-3③: material ON lacks input_material_no: ${mat[0].on}`).toBe(true);
    expect(matConds.some((c) => /\bcustomer_no\b/.test(c)), `AC-3③: material ON lacks customer_no: ${mat[0].on}`).toBe(true);

    expect(co!.matAlias, 'AC-3①: COALESCE first arg must be the material-table alias').toBe(mat[0].alias);
    expect(co!.recAlias, 'AC-3①: COALESCE second arg must be the recipe-table alias').toBe(rec[0].alias);
  } finally { finishGuard('AC-3-COMP-0004', fp, blocked); }
});

for (const cc of COST_CASES) {
  test(`T3.2 AC-3 核价 ${cc.dataset}「${cc.source}」+ 材料名: COALESCE(material_name, symbol); ${cc.materialTable} ON production_no = incoming_material_no; material_recipe ON code = incoming_material_no`, async ({ page }) => {
    const fp = H.viewFingerprint();
    const blocked = await guardedPage(page);
    try {
      await enterCostSource(page, cc);
      const compiled = await H.addFieldAndCompile(page, '材料名');
      const sql = await H.sqlPaneText(page);
      H.writeEvid(`AC-3-${cc.tag}-生成的SQL.txt`, sql);
      await H.shot(page, `AC-3-${cc.tag}-生成的SQL`);
      if (compiled?.sql) {
        expect(H.normSql(sql), 'SQL pane differs from the compile response after adding 材料名 ⇒ stale pane').toBe(H.normSql(compiled.sql));
      }
      console.log(`[AC-3 ${cc.tag}] SQL:\n${sql}`);

      const co = H.coalesceMatch(sql);
      expect(co, `AC-3 ${cc.tag}: no COALESCE(<a>.material_name, <b>.symbol):\n${sql}`).not.toBeNull();
      console.log(`[AC-3 ${cc.tag}] COALESCE alias column = ${co!.asCol} (not asserted by AC for 核价)`);

      const mat = H.leftJoinsOf(sql, cc.materialTable);
      expect(mat.length, `AC-3 ${cc.tag}: expected one LEFT JOIN ${cc.materialTable}; got ${mat.length}`).toBe(1);
      const matConds = H.onConditions(mat[0].on);
      const anchorM = matConds.map((c) => c.match(new RegExp(`^(?:${mat[0].alias}\\.production_no\\s*=\\s*([A-Za-z_][A-Za-z0-9_]*)\\.incoming_material_no|([A-Za-z_][A-Za-z0-9_]*)\\.incoming_material_no\\s*=\\s*${mat[0].alias}\\.production_no)$`, 'i')))
        .find(Boolean);
      expect(anchorM, `AC-3 ${cc.tag}: ${cc.materialTable} ON should contain ${mat[0].alias}.production_no = <anchor>.incoming_material_no; got ${mat[0].on}`).toBeTruthy();
      const anchor = anchorM![1] || anchorM![2];

      const rec = H.leftJoinsOf(sql, 'material_recipe');
      expect(rec.length, `AC-3 ${cc.tag}: expected one LEFT JOIN material_recipe; got ${rec.length}`).toBe(1);
      const recConds = H.onConditions(rec[0].on);
      expect(recConds.some((c) => H.isEquality(c, rec[0].alias, 'code', anchor, 'incoming_material_no')),
        `AC-3 ${cc.tag}: material_recipe ON should be ${rec[0].alias}.code = ${anchor}.incoming_material_no; got ${rec[0].on}`).toBe(true);

      expect(co!.matAlias, `AC-3 ${cc.tag}: COALESCE first arg must be the ${cc.materialTable} alias`).toBe(mat[0].alias);
      expect(co!.recAlias, `AC-3 ${cc.tag}: COALESCE second arg must be the material_recipe alias`).toBe(rec[0].alias);
    } finally { finishGuard(`AC-3-${cc.tag}`, fp, blocked); }
  });
}

// ═══════════════════════════════════════════════════════════════════
// T3.3 · AC-4 (real preview on the acceptance DB)
// ═══════════════════════════════════════════════════════════════════

type Comp = (typeof H.COMPS)[number];
type PvCase = { comp: Comp; part: string; expect: Record<string, string>; rowCount?: number };
const PV_CASES: { comp: Comp; runs: Omit<PvCase, 'comp'>[] }[] = [
  { comp: C4, runs: [{ part: 'S3120011203', expect: { '00144': 'H85', 'S3110520422': '料号2' }, rowCount: 2 }] },
  { comp: C5, runs: [
    { part: 'S3110520422', expect: { '00256': 'TU2丝', '00257': '羰基镍粉' } },
    { part: 'S3120011203', expect: { '00144': 'H85', 'S3110520422': '料号2' } },
  ] },
  { comp: C8, runs: [{ part: 'S3120011203', expect: { '00144': 'H85' } }] },
];

function assertPreview(pr: H.Preview, run: Omit<PvCase, 'comp'>, partCol: string, why: string) {
  expect(pr.rows.length, `${why}: preview returned 0 rows (rowCount=${pr.rowCount}) ⇒ per-row assertions would be vacuous`).toBeGreaterThan(0);
  if (run.rowCount !== undefined) {
    expect(pr.rows.length, `${why}: AC-4 expects ${run.rowCount} rows, got ${pr.rows.length}`).toBe(run.rowCount);
    if (pr.rowCount !== null) expect(pr.rowCount, `${why}: rowCount`).toBe(run.rowCount);
  }
  expect(Object.keys(pr.rows[0]), `${why}: row keys lack ${partCol}/${H.MATERIAL_COL}`).toEqual(expect.arrayContaining([partCol, H.MATERIAL_COL]));
  const byPart = H.namesByPart(pr.rows, partCol);
  console.log(`[AC-4] ${why}: ${JSON.stringify(byPart)}`);
  for (const [part, name] of Object.entries(run.expect)) {
    expect(byPart[part]?.length ?? 0, `${why}: no row for 投入料号 ${part}; parts=${JSON.stringify(Object.keys(byPart))}`).toBeGreaterThan(0);
    expect(byPart[part], `${why}: every row of ${part} should have 材料名「${name}」`).toEqual(byPart[part].map(() => name));
  }
  const blanks = pr.rows.filter((r) => H.isBlank(r[H.MATERIAL_COL]));
  expect(blanks, `${why}: AC-4 requires no row with an empty 材料名`).toEqual([]);
  const errs = pr.diagnostics.filter((d) => /ERROR/i.test(String(d?.level ?? d?.severity ?? d?.type ?? '')));
  const related = errs.filter((d) => /材料名|_物料_材料名|material_name|symbol|material_recipe/.test(JSON.stringify(d)));
  if (errs.length) console.log(`[AC-4] ${why}: ERROR diagnostics (all) = ${JSON.stringify(errs)}`);
  expect(related, `${why}: AC-4 requires no ERROR diagnostic for the 材料名 column`).toEqual([]);
}

for (const pc of PV_CASES) {
  test(`T3.3 AC-4 ${pc.comp.code} preview (${H.CUSTOMER_CODE} / ${pc.runs.map((r) => r.part).join(', ')})`, async ({ page }) => {
    const fp = H.viewFingerprint();
    const blocked = await guardedPage(page);
    try {
      const { get } = await H.openBuilderTab(page, pc.comp);
      const cols: any[] = get.builderConfig?.columns ?? [];
      const partCol = cols.find((c) => c.fieldName === '料号')?.viewColumn;
      expect(partCol, `${pc.comp.code}: builder config has no「料号」column ⇒ cannot locate 投入料号 in preview rows; cols=${JSON.stringify(cols.map((c) => c.fieldName))}`).toBeTruthy();
      for (const run of pc.runs) {
        const why = `AC-4 ${pc.comp.code} ${H.CUSTOMER_CODE}/${run.part}`;
        const pr = await H.runPreview(page, H.CUSTOMER_CODE, run.part, why);
        H.writeEvid(`AC-4-${pc.comp.code}-${run.part}-预览响应.json`, { request: pr.requestBody, status: pr.status, response: pr.body });
        await page.locator('.svb-preview').first().scrollIntoViewIfNeeded().catch(() => {});
        await H.shot(page, `AC-4-${pc.comp.code}-${run.part}-预览`);
        assertPreview(pr, run, partCol, why);
        // The on-screen preview table must show the same names (not only the network payload).
        const shown = await page.locator('.svb-preview').first().innerText();
        for (const name of Object.values(run.expect)) {
          expect(shown, `${why}: preview table on screen does not show「${name}」`).toContain(name);
        }
        if (pc.comp.code === 'COMP-0004') {
          // AC-10 step 1 uses the same action on the same component; keep a copy under the AC-10 name.
          await H.shot(page, 'AC-10-step1-COMP-0004-预览-执行前');
        }
      }
    } finally { finishGuard(`AC-4-${pc.comp.code}`, fp, blocked); }
  });
}

test('T3.9 marker: read-only segment fully green in this run (unlocks part 2)', async () => {
  H.writeEvid(path.basename(H.READONLY_MARKER), `passed at ${new Date().toISOString()} pid=${process.pid}\n`);
});
