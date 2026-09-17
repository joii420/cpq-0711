/**
 * repair-260916 · 测试分片 S-1（只读片，验收库 cpq_db_260916，后端 8196）
 *
 * 认领 AC（原文见 问题说明.md ⑥）：AC-1 / AC-9 / AC-11。
 * 执行顺序（文件内按声明顺序串行，workers=1）：
 *   T1.0 判据自检（离线，不访问后端）
 *   T1.2 AC-11 —— 第一个访问后端；先把集合 A 落盘到 证据/AC-11-集合A.json 再做任何别的
 *   T1.1 AC-1
 *   T1.3 AC-9
 * 本片写入：数据库零写入；文件只写 证据/AC-11-集合A.json 与 证据/S-1/*。
 */
import { test, expect, type APIRequestContext } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import {
  AC1_SQL, AC11_EXPECTED_SQL, DS11, FIXTURE_DIR, SET_A_FILE,
  adminApi, assertBackendIdentity, assertDbIdentity, assertTaskDirs, builderCompile,
  checkAc1, keyPaths, minus, readBaselineCsv, readBaselineJson, refreshAllPreview, sql,
  writeEvidence, writeProbe, type EdgeRow,
} from './repair260916-s1-helpers';

let ctx: APIRequestContext | null = null;
async function api(): Promise<APIRequestContext> {
  if (!ctx) {
    ctx = await adminApi();
    await assertBackendIdentity(ctx);
  }
  return ctx;
}

test.beforeAll(() => {
  assertTaskDirs();
  assertDbIdentity();
});
test.afterAll(async () => { await ctx?.dispose(); ctx = null; });

const baselineEdges = (): EdgeRow[] =>
  readBaselineCsv('查名连线-cpq_db_0724.csv').map(r => ({
    dialect: r.dialect, from_key: r.from_key, to_key: r.to_key, edge_id: r.edge_id,
    coalesce_group: r.coalesce_group || null, fallback_order: r.fallback_order || null,
    status: r.status, edge_keys: r.edge_keys || null,
  }));

// =====================================================================================
test('T1.0 判据自检（离线）：AC-1 判据在改动前基线上必须失败、在构造的「已修」样本上必须通过；键集合比较能抓到增删', () => {
  const before = baselineEdges();
  console.log(`[T1.0] 基线查名连线 ${before.length} 行`);
  expect(before.length, '基线 CSV 非空').toBeGreaterThan(0);

  // 证伪①：改动前基线（after = before）→ 必须报违反
  const vBase = checkAc1(before, before, { checkToDialect: false });
  console.log(`[T1.0] 基线上 AC-1 违反项 ${vBase.length} 条，示例：\n  ${vBase.slice(0, 5).join('\n  ')}`);
  expect(vBase.length, 'AC-1 判据在改动前基线上必须失败').toBeGreaterThan(0);

  // 阳性对照：按 AC-1 原文构造「已修」样本 → 必须通过（证明判据不是恒红）
  const fixed: EdgeRow[] = before.map(r => {
    const isTarget = DS11.some(d => d.dialect === r.dialect && d.nodeKey === r.from_key) && r.to_key === 'MAT_NAME_LK';
    return isTarget ? { ...r, coalesce_group: 'PART_NAME', fallback_order: 1 } : { ...r };
  });
  DS11.forEach((d, i) => fixed.push({
    dialect: d.dialect, from_key: d.nodeKey, to_key: 'RECIPE_NAME_LK', edge_id: `synthetic-${i}`,
    coalesce_group: 'PART_NAME', fallback_order: 2, status: 'ACTIVE', edge_keys: `0:${d.anchor}=code`,
  }));
  const vFixed = checkAc1(before, fixed, { checkToDialect: false });
  expect(vFixed, '构造的「已修」样本应无违反').toEqual([]);

  // 证伪②：材质连线多带一个客户键 → 必须失败
  const withCust = fixed.map(r => r.edge_id === 'synthetic-0' ? { ...r, edge_keys: '0:input_material_no=code;1:customer_no=customer_no' } : r);
  expect(checkAc1(before, withCust, { checkToDialect: false }).join('\n')).toContain('材质连线连接键');
  // 证伪③：物料连线键被改（去掉客户键）→ 必须失败
  const matChanged = fixed.map(r => r.dialect === 'QUOTE' && r.from_key === 'INCOMING_FIXED_FEE' && r.to_key === 'MAT_NAME_LK'
    ? { ...r, edge_keys: '0:input_material_no=material_no' } : r);
  expect(checkAc1(before, matChanged, { checkToDialect: false }).join('\n')).toContain('物料连线键');
  // 证伪④：35 条「其余」里有一条被顺手归组 → 必须失败
  const otherChanged = fixed.map(r => r.dialect === 'QUOTE' && r.from_key === 'ASSEMBLY_FEE' ? { ...r, coalesce_group: 'PART_NAME', fallback_order: 1 } : r);
  expect(checkAc1(before, otherChanged, { checkToDialect: false }).join('\n')).toContain('QUOTE/ASSEMBLY_FEE');
  // 证伪⑤：范围外多加一条材质连线 → 总数/新增集合必须失败
  const extra = [...fixed, { ...fixed[fixed.length - 1], from_key: 'ANNUAL_DISCOUNT', dialect: 'QUOTE', edge_id: 'synthetic-x' }];
  expect(checkAc1(before, extra, { checkToDialect: false }).length).toBeGreaterThan(0);

  // 键集合比较器：删一个键 / 加一个嵌套键都能被抓到
  const base = readBaselineJson('refresh-all-snapshots-预览-8081.json');
  const kp = keyPaths(base);
  console.log(`[T1.0] 基线预览键路径 ${kp.size} 个：${[...kp].sort().join(', ')}`);
  expect(kp.size).toBeGreaterThan(10);
  const removed = JSON.parse(JSON.stringify(base)); delete removed.data.recompileViews;
  expect(minus(kp, keyPaths(removed))).toEqual(['data.recompileViews']);
  const added = JSON.parse(JSON.stringify(base)); added.data.affectedTemplates[0].newKey = 1;
  expect(minus(keyPaths(added), kp)).toEqual(['data.affectedTemplates[].newKey']);

  // AC-11 期望集合 SQL 能取到非空结果，且含本次三个更新对象（锚定，不用总数）
  const exp = sql<{ sql_view_name: string; component_code: string }>(AC11_EXPECTED_SQL);
  console.log(`[T1.0] AC-11 期望集合现查 ${exp.length} 个：${exp.map(r => `${r.component_code}:${r.sql_view_name}`).join(', ')}`);
  expect(exp.length).toBeGreaterThan(0);
  for (const v of ['builder_c35c2bd590fe', 'builder_4602c64a0c38', 'builder_46f244df7ede']) {
    expect(exp.map(r => r.sql_view_name), `期望集合应含 ${v}`).toContain(v);
  }
  // 负向锚：物料BOM 视图（COMP-0002，已两段查）不应在期望集合里
  expect(exp.map(r => r.component_code)).not.toContain('COMP-0002');
});

// =====================================================================================
test('T1.2 AC-11：全量重编译预览的变化面 A−B 恰等于「选了材料名且数据源在 11 个内」；COMP-0002/0003 编译 SQL 前后逐字相同', async () => {
  // ---- 前置 0：集合 A 只能取一次
  if (fs.existsSync(SET_A_FILE) && process.env.R260916_S1_ALLOW_A_OVERWRITE !== '1') {
    throw new Error(`集合 A 已存在：${SET_A_FILE}。它只允许在「任何按组件/全量重编译执行之前」取一次，禁止覆盖；如确需重取请报主线（env R260916_S1_ALLOW_A_OVERWRITE=1）。`);
  }
  // ---- 前置 1：证明验收库还处于「执行前」状态（test.md §0.3）
  const recompileLogs = sql<{ c: number }>(`SELECT count(*) AS c FROM operation_log WHERE operation_type = 'COMPONENT_VIEW_RECOMPILE'`);
  console.log(`[T1.2] COMPONENT_VIEW_RECOMPILE 审计行 = ${recompileLogs[0].c}`);
  expect(Number(recompileLogs[0].c), '已有按组件重编译审计行 ⇒ 执行前状态已失效，报主线重建验收库').toBe(0);
  const pre = JSON.parse(fs.readFileSync(path.join(FIXTURE_DIR, '验收库-8196启动前-视图快照.json'), 'utf8'));
  const nowViews = sql<{ id: string; sqlMd5: string; updatedAt: string }>(
    `SELECT id::text AS "id", md5(sql_template) AS "sqlMd5", updated_at::text AS "updatedAt" FROM component_sql_view ORDER BY id`);
  expect(nowViews.length, '视图行非空').toBeGreaterThan(0);
  const drift = [
    ...pre.views.filter((p: any) => { const n = nowViews.find(x => x.id === p.id); return !n || n.sqlMd5 !== p.sqlMd5 || n.updatedAt !== p.updatedAt; })
      .map((p: any) => `${p.componentCode}:${p.sqlViewName}`),
    ...minus(nowViews.map(n => n.id), pre.views.map((p: any) => p.id)).map(id => `new:${id}`),
  ];
  console.log(`[T1.2] 与 8196 启动前快照（${pre.capturedAt}）相比变化的视图：${JSON.stringify(drift)}`);
  expect(drift, '8196 启动前后 component_sql_view 不应有变化，否则 A 不再是「执行前」样本 → 报主线').toEqual([]);

  // ---- 取 A（先落盘，再做任何别的）
  const c = await api();
  const probeBefore = writeProbe();
  const t0 = new Date().toISOString();
  const resp = await refreshAllPreview(c);
  writeEvidence('AC-11-全量重编译预览-8196-原始响应.json', resp.text);
  expect(resp.status, `全量重编译预览应 200；原文：${resp.text.slice(0, 500)}`).toBe(200);
  const dataA = resp.json?.data;
  expect(dataA, '响应应含 data').toBeTruthy();
  const A: string[] = dataA.recompileChangedViewNames;
  expect(Array.isArray(A), 'recompileChangedViewNames 应为数组').toBe(true);
  fs.writeFileSync(SET_A_FILE, JSON.stringify({
    capturedAt: t0, backend: 'http://localhost:8196 (worktree, cpq_db_260916, V444 applied)',
    request: { recompile: true, confirm: false },
    recompileViews: dataA.recompileViews, recompileChanged: dataA.recompileChanged,
    A: [...A].sort(),
  }, null, 2) + '\n', 'utf8');
  console.log(`[T1.2] 集合 A 已落盘 ${SET_A_FILE}：recompileChanged=${dataA.recompileChanged} A=${JSON.stringify([...A].sort())}`);

  // ---- 预览零写入
  const probeAfter = writeProbe();
  writeEvidence('AC-11-预览前后写入指纹.json', { before: probeBefore, after: probeAfter });
  expect(probeAfter, 'AC-11 所用全量预览应零写入').toEqual(probeBefore);

  // ---- 集合比较
  expect(new Set(A).size, 'A 内无重复').toBe(A.length);
  expect(A.length, 'recompileChanged 应与名单长度一致（防名单被截断）').toBe(Number(dataA.recompileChanged));
  const baseline = readBaselineJson('refresh-all-snapshots-预览-8081.json');
  const B: string[] = baseline.data.recompileChangedViewNames;
  expect(B.length, '基线 B 的名单长度应与其 recompileChanged 一致').toBe(Number(baseline.data.recompileChanged));
  const expRows = sql<{ sql_view_name: string; component_code: string; dialect: string; source_keys: string; null_key_cols: number }>(AC11_EXPECTED_SQL);
  const E = expRows.map(r => r.sql_view_name);
  console.log(`[T1.2] 期望集合（SQL 现查）${E.length} 个：\n  ${expRows.map(r => `${r.component_code} ${r.sql_view_name} ${r.dialect} [${r.source_keys}] nullKeyCols=${r.null_key_cols}`).join('\n  ')}`);
  expect(E.length, '期望集合非空（否则 A−B=∅ 也会"通过"）').toBeGreaterThan(0);
  const AminusB = minus(A, B);
  const BminusA = minus(B, A);
  const report = {
    B_source: '证据/基线-改动前/refresh-all-snapshots-预览-8081.json',
    B: [...B].sort(), A: [...A].sort(), expected: [...E].sort(), expectedDetail: expRows,
    A_minus_B: AminusB, B_minus_A: BminusA,
    A_minus_B_not_expected: minus(AminusB, E), expected_not_in_A_minus_B: minus(E, AminusB),
  };
  writeEvidence('AC-11-集合对照.json', report);
  console.log(`[T1.2] A−B=${JSON.stringify(AminusB)}\n[T1.2] B−A=${JSON.stringify(BminusA)}\n[T1.2] 多出=${JSON.stringify(report.A_minus_B_not_expected)} 缺少=${JSON.stringify(report.expected_not_in_A_minus_B)}`);
  expect(AminusB, 'A − B 应恰等于期望集合').toEqual([...E].sort());
  expect(BminusA, 'B − A 应为空').toEqual([]);

  // ---- 第二段：COMP-0002 / COMP-0003 编译 SQL 前后逐字相同
  for (const code of ['COMP-0002', 'COMP-0003']) {
    const rows = sql<{ component_id: string; sql_view_name: string; cfg: string }>(
      `SELECT c.id::text AS component_id, v.sql_view_name, v.builder_config::text AS cfg
         FROM component c JOIN component_sql_view v ON v.component_id = c.id
        WHERE c.code = '${code}' AND v.builder_config IS NOT NULL`);
    expect(rows.length, `${code} 应恰有 1 个取数配置器视图`).toBe(1);
    const r = await builderCompile(c, rows[0].component_id, JSON.parse(rows[0].cfg));
    writeEvidence(`AC-11-compile-${code}-8196.json`, r.text);
    expect(r.status, `${code} compile 应 200；原文：${r.text.slice(0, 300)}`).toBe(200);
    const before = readBaselineJson(`compile-${code}-8081.json`);
    expect(typeof before.sql === 'string' && before.sql.length > 0, '基线 SQL 非空').toBe(true);
    expect(typeof r.json?.sql === 'string' && r.json.sql.length > 0, `${code} 新 SQL 非空`).toBe(true);
    console.log(`[T1.2] ${code} sql 长度 基线=${before.sql.length} 现在=${r.json.sql.length} 逐字相同=${before.sql === r.json.sql}`
      + `；declaredColumns 相同=${JSON.stringify(before.declaredColumns) === JSON.stringify(r.json.declaredColumns)}`);
    expect(r.json.sql, `${code} 编译 SQL 应与 V444 前逐字相同`).toBe(before.sql);
  }
});

// =====================================================================================
test('T1.1 AC-1：11 个数据源各恰 2 条同组查名连线；物料连线键不变；总数 +11；其余 35 条不变', async () => {
  const fw = sql<{ success: boolean }>(`SELECT success FROM flyway_schema_history WHERE version = '444'`);
  expect(fw.length === 1 && fw[0].success === true, `V444 必须已成功执行：${JSON.stringify(fw)}`).toBe(true);
  const before = baselineEdges();
  const after = sql<EdgeRow>(AC1_SQL);
  console.log(`[T1.1] 迁移前 ${before.length} 条，迁移后 ${after.length} 条`);
  expect(after.length, '现查非空').toBeGreaterThan(0);
  const ds11Now = after.filter(r => DS11.some(d => d.dialect === r.dialect && d.nodeKey === r.from_key));
  console.log(`[T1.1] 11 数据源现状：\n  ${ds11Now.map(r => `${r.dialect}/${r.from_key} -> ${r.to_key}@${r.to_dialect} grp=${r.coalesce_group} ord=${r.fallback_order} keys=${r.edge_keys}`).join('\n  ')}`);
  const violations = checkAc1(before, after, { checkToDialect: true });
  writeEvidence('AC-1-查名连线-迁移后.json', { sql: AC1_SQL, rows: after, violations });
  expect(violations, 'AC-1 违反项').toEqual([]);
});

// =====================================================================================
test('T1.3 AC-9：refresh-all-snapshots 预览键集合与改动前相同；recompileViews = 库内 builder 视图数；零写入', async () => {
  const c = await api();
  const probeBefore = writeProbe();
  const resp = await refreshAllPreview(c);
  const probeAfter = writeProbe();
  writeEvidence('AC-9-全量重编译预览-8196-原始响应.json', resp.text);
  writeEvidence('AC-9-预览前后写入指纹.json', { before: probeBefore, after: probeAfter });
  expect(resp.status, `应 200；原文：${resp.text.slice(0, 500)}`).toBe(200);
  expect(resp.json?.data, '响应应含 data').toBeTruthy();

  const base = readBaselineJson('refresh-all-snapshots-预览-8081.json');
  const kb = keyPaths(base), ka = keyPaths(resp.json);
  const onlyBase = minus(kb, ka), onlyNow = minus(ka, kb);
  writeEvidence('AC-9-键集合对照.json', { baseline: [...kb].sort(), now: [...ka].sort(), onlyInBaseline: onlyBase, onlyInNow: onlyNow });
  console.log(`[T1.3] 键路径 基线=${kb.size} 现在=${ka.size} 仅基线有=${JSON.stringify(onlyBase)} 仅现在有=${JSON.stringify(onlyNow)}`);
  expect(kb.size).toBeGreaterThan(10);
  expect(onlyBase, '改动前有、现在没有的键').toEqual([]);
  expect(onlyNow, '现在有、改动前没有的键').toEqual([]);
  expect(resp.json.data.preview, 'preview 仍为 true').toBe(true);

  const cnt = Number(sql<{ c: number }>(`SELECT count(*) AS c FROM component_sql_view WHERE builder_config IS NOT NULL`)[0].c);
  console.log(`[T1.3] recompileViews=${resp.json.data.recompileViews} 库内 builder 视图=${cnt}`);
  expect(cnt).toBeGreaterThan(0);
  expect(Number(resp.json.data.recompileViews), 'recompileViews = 库内 builder 视图数（口径不符请报主线，不许调判据）').toBe(cnt);

  expect(probeAfter, 'AC-9 预览应零写入').toEqual(probeBefore);
});
