/**
 * E2E · task-260908「取数配置器优化」· 分片 S1（语义图与编译）· 预览篇
 *
 * 认领 AC：**AC-2 / AC-4 / AC-6 / AC-23 / AC-24**
 * （同片的 AC-1 / AC-3 / AC-5 / AC-5b / AC-7 在 `task260908-s1-lookup-sql.spec.ts`。）
 *
 * 🚦 写入面 = **只读**。全程不点「保存」；readOnlyGuard 兜底。
 *
 * ═══════════════════════════════════════════════════════════════════
 * 🚨 本文件的三条量具纪律（testing.md §3/§4.4 + test.md §4.2）
 *
 * ① **行数一律取自 `/builder/preview` 的响应体**，不取 DOM 表格行数。
 *    请求 400 时前端会继续显示上一次的结果 —— 用 DOM 数行会以「读到旧结果」的方式通过。
 *    ⇒ `runPreview()` 强制要求：抓到响应 + 2xx + 能读出 rowCount，三者缺一即硬失败。
 *
 * ② **基准数字在运行期重采，🚫 不写死 AC 里的 2026-09-08 实测值。**
 *    需求文档 §③ 开头已声明「共享库数据会漂移，数字对不上先按括号里的口径重采」。
 *    2026-09-08 撰写用例时实测就已经漂了：
 *      · AC-2  文档 97/61/23 → 实测 103/67/23
 *      · AC-4  文档 107/58   → 实测 112/63
 *      · AC-24 明细 文档 22/7 → 实测 22/5
 *    写死这些数字 = 每次跑都红，而且**红得像功能回归**。
 *    ⇒ 每条用例先用 AC 括号里的口径跑一遍 SQL 拿基准，再和预览结果比；文档值只打印作漂移记录。
 *
 * ③ **每条都带阳性对照**：`rowCount > 0` 且「材料名非空行数 > 0」。
 *    没有这两条，「非空 N 行」在 N=0 时会以**空跑**的方式通过（testing.md §3 的假绿形态）。
 *    ⚠️ 唯一的例外是 AC-24 明细核价那半条 —— 它的口径本身就允许命中核价物料表 0 行，
 *       但**材质表那一路仍必须 > 0**，否则整列全空同样是空跑。
 * ═══════════════════════════════════════════════════════════════════
 */
import { test, expect, Page } from '@playwright/test';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';
import {
  ANCHORS, F_MATERIAL_NAME,
  openBuilderOf, expandAllGroups, addField, sqlPanelText,
  selectDataset, selectSource, runPreview, previewColumn, isBlank,
  tableRefCount, leftJoinsOf, onConditionCount,
  scalarInt, readOnlyGuard, archive, shot, PreviewResult,
} from './task260908-s1.helpers';

let backendUp = true;

test.beforeAll(async () => { backendUp = await isBackendUp(); });

test.beforeEach(async ({ page }) => {
  test.skip(!backendUp, '后端未启动，跳过（不视为失败，也不视为通过）');
  await loginAsAdmin(page);
});

/** SQL 的锚点关系名（`FROM <relation>`）—— 用来交叉核对「预览没被额外过滤/截断」。 */
function anchorRelation(sql: string): string | null {
  const m = sql.match(/\bFROM\s+(?:public\.)?([A-Za-z_][A-Za-z0-9_]*)/i);
  return m ? m[1] : null;
}

/**
 * 断言「材料名」这一列的空/非空分布。
 *
 * 返回 `{ total, nonEmpty, blank, degraded }`。
 * ⚠️ 若预览响应带的行数少于 rowCount（后端做了展示截断），逐行统计只能覆盖返回的那一页 ——
 *    此时**不静默放行**：标记 degraded=true，由调用方决定怎么报，并在证据里写明降级。
 */
function materialNameStats(pr: PreviewResult, why: string) {
  const vals = previewColumn(pr, F_MATERIAL_NAME, why);
  const blank = vals.filter(isBlank).length;
  const nonEmpty = vals.length - blank;
  const degraded = pr.rowCount !== null && vals.length !== pr.rowCount;
  console.log(`[${why}] rowCount=${pr.rowCount} 返回行=${vals.length} 非空=${nonEmpty} 空=${blank}`
    + (degraded ? '  ⚠️【降级】响应行数 ≠ rowCount，逐行统计只覆盖返回的这一页' : ''));
  return { total: vals.length, nonEmpty, blank, degraded, vals };
}

function reportDrift(label: string, documented: number, actual: number) {
  if (documented !== actual) {
    console.log(`[基准漂移] ${label}：需求文档记的 2026-09-08 实测值 = ${documented}，`
      + `本次重采 = ${actual}。已按重采值断言（需求文档 §③ 授权）。`);
  }
}

// ═══════════════════════════════════════════════════════════════════
// AC-2 · 报价 BOM 的材料名是双表 COALESCE
// ═══════════════════════════════════════════════════════════════════

test('AC-2: 报价 BOM 的「材料名」= COALESCE(物料别名.material_name, 材质别名.symbol)，'
  + '双 LEFT JOIN 并存；预览行数 / 非空行数与口径重采值一致', async ({ page }) => {
  test.setTimeout(300_000);

  // ── 基准重采（口径逐字来自 AC-2 括号内）──
  const total = scalarInt('select count(*) from ds_quote_material_bom', 'AC-2 基准 总行数');
  const hitMat = scalarInt(
    'select count(*) from ds_quote_material_bom b where exists(select 1 from ds_quote_material m ' +
    'where m.material_no=b.input_material_no and m.customer_no=b.customer_no)', 'AC-2 基准 命中物料表');
  const hitRec = scalarInt(
    'select count(*) from ds_quote_material_bom b where exists(select 1 from material_recipe r ' +
    'where r.code=b.input_material_no)', 'AC-2 基准 命中材质表');
  const overlap = scalarInt(
    'select count(*) from ds_quote_material_bom b where exists(select 1 from ds_quote_material m ' +
    'where m.material_no=b.input_material_no and m.customer_no=b.customer_no) and exists(' +
    'select 1 from material_recipe r where r.code=b.input_material_no)', 'AC-2 基准 交集');
  expect(overlap,
    `AC-2 基准前置：AC 口径声明「两者交集 0 行」，本次重采交集 = ${overlap}。\n` +
    `  ⇒ 口径的前提（材质号与物料表料号不重号）在当前数据下不成立，「非空 = 命中物料 + 命中材质」这条加法失效。\n` +
    `  停下来报主线重新给口径，🚫 不要自行改成别的算法。`
  ).toBe(0);
  // 🔧 D-15 订正（执行期按 AC 原文修正）：断言写成**不变量**而不是具体数字 ——
  //    共享库在被并发写入（97→103 / 107→112），写死数字会被合法数据变化误触发。
  //    覆盖率层的不变量 = 交集 0（上方已断言）· 命中物料 > 0 · 命中材质 > 0（两路都通，
  //    缺一路 COALESCE 就退化成单源而不会报错）。
  expect(hitMat, 'AC-2 覆盖率层：命中物料表 0 行 ⇒ COALESCE 的物料这一路无法验证（退化成单源也测不出来）').toBeGreaterThan(0);
  expect(hitRec, 'AC-2 覆盖率层：命中材质表 0 行 ⇒ COALESCE 的材质这一路无法验证').toBeGreaterThan(0);
  const nonEmptyExpected = hitMat + hitRec;
  reportDrift('AC-2 总行数', 97, total);
  reportDrift('AC-2 非空行数', 84, nonEmptyExpected);
  expect(total, 'AC-2 基准：ds_quote_material_bom 一行都没有 ⇒ 预览断言会空跑，本条判【未验证】').toBeGreaterThan(0);
  expect(nonEmptyExpected, 'AC-2 阳性对照：按口径算出的非空行数为 0 ⇒ 「非空 N 行」会以空跑方式通过').toBeGreaterThan(0);

  const a = ANCHORS.materialBom;
  const { id, name } = await openBuilderOf(page, a);
  const guard = readOnlyGuard(id, name);
  try {
    await expandAllGroups(page);
    const sqlBefore = await sqlPanelText(page);
    await addField(page, F_MATERIAL_NAME);
    const sql = await sqlPanelText(page);
    expect(sql, 'AC-2 证伪对照：加了「材料名」后 SQL 逐字未变 ⇒ 列没加进去或读到旧结果').not.toBe(sqlBefore);

    // ── ① 双 LEFT JOIN 并存 ──
    const matJoins = leftJoinsOf(sql, 'ds_quote_material');
    const recJoins = leftJoinsOf(sql, 'material_recipe');
    expect(matJoins.length,
      `AC-2①：FROM 段应有 LEFT JOIN ds_quote_material。SQL=\n${sql.slice(0, 2000)}`).toBeGreaterThan(0);
    expect(recJoins.length,
      `AC-2①：FROM 段应有 LEFT JOIN material_recipe。SQL=\n${sql.slice(0, 2000)}`).toBeGreaterThan(0);

    // ── ② 该列表达式形如 COALESCE(<物料别名>.material_name, <材质别名>.symbol) ──
    const m = sql.match(
      /COALESCE\s*\(\s*([A-Za-z_][A-Za-z0-9_]*)\s*\.\s*material_name\s*,\s*([A-Za-z_][A-Za-z0-9_]*)\s*\.\s*symbol\s*\)/i);
    expect(m,
      `AC-2②：找不到形如 COALESCE(<物料别名>.material_name, <材质别名>.symbol) 的表达式。\n` +
      `  🔑 D-1 明确材质侧取的是 material_recipe.symbol（牌号，NOT NULL），不是 name。\n` +
      `  SQL=\n${sql.slice(0, 2000)}`
    ).not.toBeNull();
    expect(matJoins.map((j) => j.alias),
      `AC-2②：COALESCE 第一个分支的别名 ${m![1]} 不属于任何一条 LEFT JOIN ds_quote_material ` +
      `（别名们=${JSON.stringify(matJoins.map((j) => j.alias))}）⇒ 取的不是物料表的值`
    ).toContain(m![1]);
    expect(recJoins.map((j) => j.alias),
      `AC-2②：COALESCE 第二个分支的别名 ${m![2]} 不属于任何一条 LEFT JOIN material_recipe ` +
      `（别名们=${JSON.stringify(recJoins.map((j) => j.alias))}）⇒ 取的不是材质表的值`
    ).toContain(m![2]);

    // ── ③ 预览 ──
    const pr = await runPreview(page, 'AC-2 预览');
    // 🔧 D-13 订正（执行期按 AC 原文修正）：预览按 `:total_material_no`（该单 BOM 闭包）**收窄**，
    //    返回的是个位数，🚫 不得拿它对全表口径（原用例 .toBe(total) 是被订正掉的旧口径，
    //    照原样跑必然红，且会红成「功能坏了」）。预览层只验「功能通」。
    expect(pr.rowCount,
      `AC-2③ 预览层：rowCount 应 > 0（预览真的执行了且有行可看），实际 ${pr.rowCount}。` +
      `elapsedMs=${pr.elapsedMs} HTTP=${pr.status}`
    ).toBeGreaterThan(0);
    console.log(`[AC-2③] 预览层 rowCount=${pr.rowCount}（闭包口径，与全表 ${total} 不同量级属预期）`);

    const st = materialNameStats(pr, 'AC-2③');
    expect(st.nonEmpty,
      `AC-2③ 阳性对照：材料名非空行数为 0 ⇒ 双表 COALESCE 一个值都没取到（或列取错了），` +
      `此时「空的那些行显示空」是空跑通过。`
    ).toBeGreaterThan(0);
    // 🔧 D-13：全量「非空 N 行」由上方**覆盖率层的直连 SQL**承担，预览层只断言「至少一行非空」。
    console.log(`[AC-2③] 预览返回 ${st.total} 行，其中材料名非空 ${st.nonEmpty} 行、空 ${st.blank} 行；`
      + `全表口径（覆盖率层）总=${total} 命中物料=${hitMat} 命中材质=${hitRec} 交集=${overlap}`);

    archive('AC-2-预览.txt',
      `# AC-2 组件 ${id} / ${name}\n` +
      `## 基准重采\n总=${total} 命中物料=${hitMat} 命中材质=${hitRec} 交集=${overlap} 非空期望=${nonEmptyExpected}\n` +
      `（需求文档记的 2026-09-08 值：97 / 61 / 23 / 0 / 84）\n\n` +
      `## 预览\nHTTP=${pr.status} rowCount=${pr.rowCount} elapsedMs=${pr.elapsedMs} ` +
      `返回行=${st.total} 非空=${st.nonEmpty} 空=${st.blank} 降级=${st.degraded}\n\n` +
      `## COALESCE 表达式\n${m![0]}\n\n## SQL\n${sql}\n`);
    await shot(page, 'AC-2-报价BOM双表COALESCE.png');
  } finally {
    guard.assertUntouched();
  }
});

// ═══════════════════════════════════════════════════════════════════
// AC-23 · 材料名取不到值时显示空，不是报错也不是「加载中…」
// ═══════════════════════════════════════════════════════════════════

test('AC-23【边界】: AC-2 的配置下，两表都不命中的那些行，材料名显示空/—；'
  + '🚫 不出现「加载中…」、不出现 null 字面量、diagnostics 无该列 ERROR', async ({ page }) => {
  test.setTimeout(300_000);

  const total = scalarInt('select count(*) from ds_quote_material_bom', 'AC-23 基准 总行数');
  const hitMat = scalarInt(
    'select count(*) from ds_quote_material_bom b where exists(select 1 from ds_quote_material m ' +
    'where m.material_no=b.input_material_no and m.customer_no=b.customer_no)', 'AC-23 基准 命中物料表');
  const hitRec = scalarInt(
    'select count(*) from ds_quote_material_bom b where exists(select 1 from material_recipe r ' +
    'where r.code=b.input_material_no)', 'AC-23 基准 命中材质表');
  const blankExpected = total - hitMat - hitRec;
  reportDrift('AC-23 两表都不命中的行数', 13, blankExpected);
  expect(blankExpected,
    `AC-23 前置：按口径重采，两表都不命中的行数 = ${blankExpected}。为 0 就没有「取不到值」的样本，` +
    `本条会验一个不存在的场景（空跑）⇒ 判【未验证】，报主线补数据。`
  ).toBeGreaterThan(0);

  const a = ANCHORS.materialBom;
  const { id, name } = await openBuilderOf(page, a);
  const guard = readOnlyGuard(id, name);
  try {
    await expandAllGroups(page);
    await addField(page, F_MATERIAL_NAME);
    const pr = await runPreview(page, 'AC-23 预览');
    const st = materialNameStats(pr, 'AC-23');

    // ── 阳性对照：这一列必须**既有值又有空**，否则「空的显示空」可能只是整列都没取到 ──
    expect(st.nonEmpty,
      'AC-23 阳性对照：材料名整列全空 ⇒ 无法区分「取不到值时正确显示空」和「这一列压根没工作」'
    ).toBeGreaterThan(0);
    expect(st.blank,
      'AC-23 阳性对照：材料名整列全非空 ⇒ 本条要验的「取不到值」场景一行都没出现，是空跑'
    ).toBeGreaterThan(0);

    if (!st.degraded) {
      expect(st.blank,
        `AC-23①：两表都不命中的行应为 ${blankExpected} 行（口径重采），实际空单元格 ${st.blank} 行`
      ).toBe(blankExpected);
    } else {
      console.log(`[AC-23]【降级】预览只返回 ${st.total}/${pr.rowCount} 行，`
        + `「恰好 ${blankExpected} 行为空」未能全量验证，仅验证了「有空有非空」。`);
    }

    // ── 🚫 反向断言 a：空值不得是「加载中…」或 null 字面量 ──
    const badTokens = st.vals
      .filter((v) => typeof v === 'string')
      .filter((v) => /加载中/.test(v) || /^\s*null\s*$/i.test(v) || /^\s*undefined\s*$/i.test(v));
    expect(badTokens,
      `AC-23②：材料名列里出现了占位/字面量脏值 ${JSON.stringify(badTokens.slice(0, 10))}（AP-31/AP-38 族）。` +
      `取不到值应是空单元格或「—」。`
    ).toEqual([]);

    // ── 🚫 反向断言 b：预览区 DOM 里不出现「加载中」──
    const previewText = await page.locator('body').innerText();
    expect(previewText,
      'AC-23②：预览执行完之后页面上仍出现「加载中」⇒ AP-31/AP-38 的永久占位形态'
    ).not.toContain('加载中');

    // ── 🚫 反向断言 c：diagnostics 里没有针对该列的 ERROR ──
    const errs = pr.diagnostics.filter((d: any) => {
      const lv = String(d?.level ?? d?.severity ?? d?.type ?? '').toUpperCase();
      return lv.includes('ERROR');
    });
    const errsOnCol = errs.filter((d: any) => JSON.stringify(d).includes(F_MATERIAL_NAME));
    console.log(`[AC-23] diagnostics 共 ${pr.diagnostics.length} 条，ERROR ${errs.length} 条，`
      + `涉及材料名 ${errsOnCol.length} 条`);
    expect(errsOnCol,
      `AC-23②：预览 diagnostics 里出现了「${F_MATERIAL_NAME}」的 ERROR 级条目：` +
      `${JSON.stringify(errsOnCol, null, 2)}`
    ).toEqual([]);

    archive('AC-23-空值边界.txt',
      `# AC-23 组件 ${id} / ${name}\n` +
      `基准：总=${total} 命中物料=${hitMat} 命中材质=${hitRec} ⇒ 都不命中=${blankExpected}（文档值 13）\n` +
      `预览：rowCount=${pr.rowCount} 返回行=${st.total} 非空=${st.nonEmpty} 空=${st.blank} 降级=${st.degraded}\n` +
      `空值样本（前 20）=${JSON.stringify(st.vals.filter(isBlank).slice(0, 20))}\n` +
      `diagnostics=${JSON.stringify(pr.diagnostics, null, 2)}\n`);
    await shot(page, 'AC-23-材料名空值边界.png');
  } finally {
    guard.assertUntouched();
  }
});

// ═══════════════════════════════════════════════════════════════════
// AC-4 · 物料与元素BOM 只连材质表
// ═══════════════════════════════════════════════════════════════════

test('AC-4: 「材质元素」拖入「材料名」→ SQL 含 LEFT JOIN material_recipe、'
  + '🚫 不含 ds_quote_material；预览行数 / 非空行数与口径重采值一致', async ({ page }) => {
  test.setTimeout(300_000);

  const total = scalarInt('select count(*) from ds_quote_element_bom', 'AC-4 基准 总行数');
  const hitRec = scalarInt(
    'select count(*) from ds_quote_element_bom e where exists(select 1 from material_recipe r ' +
    'where r.code=e.material_part_no)', 'AC-4 基准 命中材质表');
  reportDrift('AC-4 总行数', 107, total);
  reportDrift('AC-4 非空行数', 58, hitRec);
  expect(total, 'AC-4 基准：ds_quote_element_bom 一行都没有 ⇒ 空跑，本条判【未验证】').toBeGreaterThan(0);
  expect(hitRec, 'AC-4 阳性对照：按口径算出的非空行数为 0 ⇒ 「非空 N 行」会空跑通过').toBeGreaterThan(0);

  const a = ANCHORS.elementBom;
  const { id, name } = await openBuilderOf(page, a);
  const guard = readOnlyGuard(id, name);
  try {
    await expandAllGroups(page);
    const sqlBefore = await sqlPanelText(page);
    await addField(page, F_MATERIAL_NAME);
    const sql = await sqlPanelText(page);
    expect(sql, 'AC-4 证伪对照：加了「材料名」后 SQL 逐字未变 ⇒ 列没加进去或读到旧结果').not.toBe(sqlBefore);

    // ── ① 含材质表 ──
    expect(leftJoinsOf(sql, 'material_recipe').length,
      `AC-4①：应含 LEFT JOIN material_recipe。SQL=\n${sql.slice(0, 2000)}`).toBeGreaterThan(0);

    // ── ② 🚫 不含物料表（用户澄清：物料BOM元素只连材质表）──
    // 🚨 边界匹配：ds_quote_material 是 ds_quote_material_bom 的前缀，裸 substring 会误报。
    expect(tableRefCount(sql, 'ds_quote_material'),
      `AC-4②：SQL 里出现了 ds_quote_material —— 「物料与元素BOM」只应连材质表。SQL=\n${sql.slice(0, 2000)}`
    ).toBe(0);

    // ── ③ 预览 ──
    const pr = await runPreview(page, 'AC-4 预览');
    // 🔧 D-13 订正：同 AC-2 —— 预览按闭包收窄，🚫 不与全表 ${total} 对数。
    expect(pr.rowCount,
      `AC-4③ 预览层：rowCount 应 > 0，实际 ${pr.rowCount}（HTTP=${pr.status} elapsedMs=${pr.elapsedMs}）`
    ).toBeGreaterThan(0);
    const st = materialNameStats(pr, 'AC-4③');
    expect(st.nonEmpty, 'AC-4③ 阳性对照：材料名非空行数为 0 ⇒ 材质表那一路没取到值').toBeGreaterThan(0);
    console.log(`[AC-4③] 预览 ${st.total} 行 / 非空 ${st.nonEmpty}；覆盖率层（全表）总=${total} 命中材质=${hitRec}`);

    archive('AC-4-预览.txt',
      `# AC-4 组件 ${id} / ${name}\n基准：总=${total}（文档 107） 命中材质=${hitRec}（文档 58）\n` +
      `预览：rowCount=${pr.rowCount} 非空=${st.nonEmpty} 降级=${st.degraded}\n` +
      `ds_quote_material 引用次数=${tableRefCount(sql, 'ds_quote_material')}（期望 0）\n\n## SQL\n${sql}\n`);
    await shot(page, 'AC-4-材质元素只连材质表.png');
  } finally {
    guard.assertUntouched();
  }
});

// ═══════════════════════════════════════════════════════════════════
// AC-6 · 核价两套的材料名（ON 只有一个条件）
// ═══════════════════════════════════════════════════════════════════

/** 核价侧一半的公共动作：切数据集 → 切数据源 → 加材料名 → 断 SQL → 预览。 */
async function costSideHalf(
  page: Page, opts: {
    datasetLabel: string; sourceLabel: string; lookupTable: string;
    baselineSql: string; nonEmptySql: string; docTotal: number; docNonEmpty: number; tag: string;
  },
) {
  const total = scalarInt(opts.baselineSql, `${opts.tag} 基准 总行数`);
  const hit = scalarInt(opts.nonEmptySql, `${opts.tag} 基准 非空行数`);
  reportDrift(`${opts.tag} 总行数`, opts.docTotal, total);
  reportDrift(`${opts.tag} 非空行数`, opts.docNonEmpty, hit);
  expect(total, `${opts.tag} 基准：锚点表一行都没有 ⇒ 空跑，本条判【未验证】`).toBeGreaterThan(0);
  expect(hit, `${opts.tag} 阳性对照：按口径算出的非空行数为 0 ⇒ 断言会空跑`).toBeGreaterThan(0);

  const prefix = await selectDataset(page, opts.datasetLabel);
  await selectSource(page, opts.sourceLabel);
  await expandAllGroups(page);
  const sqlBefore = await sqlPanelText(page);
  await addField(page, F_MATERIAL_NAME);
  const sql = await sqlPanelText(page);
  expect(sql, `${opts.tag} 证伪对照：加了「材料名」后 SQL 逐字未变 ⇒ 列没加进去或读到旧结果`).not.toBe(sqlBefore);

  // ── ① LEFT JOIN 对应的核价物料表 ──
  const joins = leftJoinsOf(sql, opts.lookupTable);
  expect(joins.length,
    `${opts.tag}①：应含 LEFT JOIN ${opts.lookupTable}（表前缀已确认切到 ${prefix}）。SQL=\n${sql.slice(0, 2000)}`
  ).toBeGreaterThan(0);

  // ── ② ON 只有一个条件：production_no（D-11：核价侧无客户维度）──
  const j = joins.find((x) => /\bproduction_no\b/.test(x.on));
  expect(j,
    `${opts.tag}②：找不到 ON 用 production_no 的那条 LEFT JOIN ${opts.lookupTable}。` +
    `joins=${JSON.stringify(joins, null, 2)}`
  ).toBeTruthy();
  expect(onConditionCount(j!.on),
    `${opts.tag}②：核价侧的 ON 应**只有一个条件**（.production_no = <别名>.production_no），` +
    `D-11 明确核价侧无客户维度、生产料号不重号。实际 ON=${j!.on}`
  ).toBe(1);
  expect(j!.on,
    `${opts.tag}②：核价侧的 ON 里不应出现 customer_no（那是报价侧才有的维度）。ON=${j!.on}`
  ).not.toMatch(/\bcustomer_no\b/);

  // ── ③ 预览：行数 = 口径重采，且**全部非空**（AC 原文 8/8、2/2）──
  const pr = await runPreview(page, `${opts.tag} 预览`);
  const rel = anchorRelation(sql);
  console.log(`[${opts.tag}] 锚点关系=${rel} 预览 rowCount=${pr.rowCount} 基准=${total}`);
  expect(pr.rowCount, `${opts.tag}③：预览行数应为 ${total}（口径重采），实际 ${pr.rowCount}`).toBe(total);

  const st = materialNameStats(pr, `${opts.tag}③`);
  expect(st.nonEmpty, `${opts.tag}③ 阳性对照：材料名非空行数为 0`).toBeGreaterThan(0);
  if (!st.degraded) {
    expect(st.nonEmpty,
      `${opts.tag}③：材料名非空应为 ${hit} 行（口径重采：production_no 命中 ${opts.lookupTable}），` +
      `实际 ${st.nonEmpty} 行`
    ).toBe(hit);
    expect(st.blank,
      `${opts.tag}③：AC 原文口径下这一源应**全部命中**（${total}/${total}），实际有 ${st.blank} 行为空`
    ).toBe(total - hit);
  } else {
    console.log(`[${opts.tag}]【降级】预览只返回 ${st.total}/${pr.rowCount} 行，全量非空数未验证。`);
  }
  return { total, hit, sql, pr, st, prefix, joinOn: j!.on };
}

test('AC-6: 基础核价「加工费&组装费」与明细核价「模具工装成本」拖入「材料名」→ '
  + 'LEFT JOIN ds_cost_*_material 且 ON 只有一个 production_no 条件；预览全部命中', async ({ page }) => {
  test.setTimeout(360_000);

  // 🚩 入口说明：AC-6 没点名组件，且 2026-09-08 实测**全库没有 COST_DETAIL 侧的 builder 组件**
  //    （builder_config 里 dialect=COST_BASIC 仅 1 个、COST_DETAIL 0 个）。
  //    S1 是只读片、🚫 不许建组件（那是 S2 的写入面），故从一个 QUOTE 侧的测试组件进去，
  //    在取数面板里**切数据集**到核价两套。切数据集是面板内的客户端动作，不落库；
  //    finally 的 readOnlyGuard 会证明这一点。
  const a = ANCHORS.incomingOtherFee;
  const { id, name } = await openBuilderOf(page, a);
  const guard = readOnlyGuard(id, name);
  try {
    // ── 基础核价 ──
    const basic = await costSideHalf(page, {
      tag: 'AC-6 基础核价',
      datasetLabel: '基础核价', sourceLabel: '加工费&组装费',
      lookupTable: 'ds_cost_basic_material',
      baselineSql: 'select count(*) from ds_cost_basic_process_assembly_fee',
      nonEmptySql: 'select count(*) from ds_cost_basic_process_assembly_fee f where exists(' +
        'select 1 from ds_cost_basic_material m where m.production_no=f.production_no)',
      docTotal: 8, docNonEmpty: 8,
    });
    expect(tableRefCount(basic.sql, 'ds_quote_material'),
      'AC-6 基础核价：SQL 里不应出现报价侧的 ds_quote_material（D-10：核价 BOM 不接报价物料表）'
    ).toBe(0);
    await shot(page, 'AC-6-基础核价-加工费组装费.png');

    // ── 明细核价 ──
    const detail = await costSideHalf(page, {
      tag: 'AC-6 明细核价',
      datasetLabel: '明细核价', sourceLabel: '模具工装成本',
      lookupTable: 'ds_cost_detail_material',
      baselineSql: 'select count(*) from ds_cost_detail_tooling',
      nonEmptySql: 'select count(*) from ds_cost_detail_tooling f where exists(' +
        'select 1 from ds_cost_detail_material m where m.production_no=f.production_no)',
      docTotal: 2, docNonEmpty: 2,
    });
    expect(tableRefCount(detail.sql, 'ds_quote_material'),
      'AC-6 明细核价：SQL 里不应出现报价侧的 ds_quote_material'
    ).toBe(0);
    await shot(page, 'AC-6-明细核价-模具工装成本.png');

    archive('AC-6-核价两套.txt',
      `# AC-6 入口组件 ${id} / ${name}（QUOTE 侧组件，面板内切数据集；全库无 COST_DETAIL builder 组件）\n\n` +
      `## 基础核价 · 加工费&组装费（表前缀 ${basic.prefix}）\n` +
      `基准 ${basic.total}/${basic.hit}（文档 8/8）  预览 rowCount=${basic.pr.rowCount} 非空=${basic.st.nonEmpty}\n` +
      `ON=${basic.joinOn}\n\n${basic.sql}\n\n` +
      `## 明细核价 · 模具工装成本（表前缀 ${detail.prefix}）\n` +
      `基准 ${detail.total}/${detail.hit}（文档 2/2）  预览 rowCount=${detail.pr.rowCount} 非空=${detail.st.nonEmpty}\n` +
      `ON=${detail.joinOn}\n\n${detail.sql}\n`);
  } finally {
    guard.assertUntouched();
  }
});

// ═══════════════════════════════════════════════════════════════════
// AC-24 · 核价 BOM 材料名的已知低覆盖（显式缺口断言）
// ═══════════════════════════════════════════════════════════════════

test('AC-24【边界·显式缺口】: 核价 BOM 的材料名覆盖率**就是**很低 —— '
  + '按 D-10 裁决的口径钉住这个数字，🚫 不得当成缺陷', async ({ page }) => {
  test.setTimeout(360_000);

  // 🔑 本条断言的是「按 D-10 裁决的口径，覆盖率就是这么低」，**不是断言功能正常**。
  //    它存在的意义：把这个已知情裁决的缺口钉在验收记录里，
  //    防止后续会话把「大部分为空」误判成 BUG 又去查一遍。
  //    根因（需求文档已写明）：component_no 实测是**销售料号口径**（S-3110520789 vs 核价物料表 3110520789，差 S- 前缀）。
  //    ⇒ 🚫 用例发现「大部分为空」时**不许报 BUG**，那是预期结果。

  const bTotal = scalarInt('select count(*) from v_ds_cost_basic_material_bom_all', 'AC-24 基础 总行数');
  const bMat = scalarInt(
    'select count(*) from v_ds_cost_basic_material_bom_all v where exists(select 1 from ds_cost_basic_material m ' +
    'where m.production_no=v.component_no)', 'AC-24 基础 命中核价物料表');
  const bRec = scalarInt(
    'select count(*) from v_ds_cost_basic_material_bom_all v where exists(select 1 from material_recipe r ' +
    'where r.code=v.component_no)', 'AC-24 基础 命中材质表');
  const dTotal = scalarInt('select count(*) from v_ds_cost_detail_material_bom_all', 'AC-24 明细 总行数');
  const dMat = scalarInt(
    'select count(*) from v_ds_cost_detail_material_bom_all v where exists(select 1 from ds_cost_detail_material m ' +
    'where m.production_no=v.component_no)', 'AC-24 明细 命中核价物料表');
  const dRec = scalarInt(
    'select count(*) from v_ds_cost_detail_material_bom_all v where exists(select 1 from material_recipe r ' +
    'where r.code=v.component_no)', 'AC-24 明细 命中材质表');

  reportDrift('AC-24 基础 总/非空', 100, bTotal);
  reportDrift('AC-24 基础 非空', 17, bMat + bRec);
  reportDrift('AC-24 明细 总', 22, dTotal);
  reportDrift('AC-24 明细 非空', 7, dMat + dRec);
  console.log(`[AC-24] 基础 ${bTotal} 行：命中核价物料 ${bMat} + 命中材质 ${bRec} = ${bMat + bRec}（文档 100 → 10+7=17）`);
  console.log(`[AC-24] 明细 ${dTotal} 行：命中核价物料 ${dMat} + 命中材质 ${dRec} = ${dMat + dRec}（文档 22 → 0+7=7）`);

  // 覆盖率「低」本身就是本条要钉的事实 —— 反过来若突然变高，说明有人偷偷接了 ds_quote_material（违反 D-10），
  // 那同样要红，且要红在这里而不是留到上线。
  expect(bMat + bRec,
    `AC-24：基础核价 BOM 的材料名覆盖率突然升到 ${bMat + bRec}/${bTotal}（>50%）。\n` +
    `  🚨 D-10 裁决了「只连对应 ds_ 物料表 + 材质表，不接 ds_quote_material」，覆盖率就应该很低。\n` +
    `  覆盖率大幅上升说明**接了不该接的表**（实测接 ds_quote_material 能到 96/100）。这是违反裁决，不是改进。`
  ).toBeLessThan(bTotal * 0.5);
  // 阳性对照：材质表那一路必须有命中，否则整列全空 = 无法区分「低覆盖」和「功能压根没接上」
  expect(bRec, 'AC-24 阳性对照：基础核价 BOM 连材质表一行都没命中 ⇒ 整列全空，与「功能没实现」不可区分').toBeGreaterThan(0);
  expect(dRec, 'AC-24 阳性对照：明细核价 BOM 连材质表一行都没命中 ⇒ 同上').toBeGreaterThan(0);
  // 🔧 D-14 / D-15 订正（执行期按 AC 原文修正）：
  //    · 需求文档原写「明细命中材质 7」是抄错的（抄了基础侧的 7），实测 5 —— 故这里只断言 > 0，不写数字
  //    · 「明细命中核价物料表恒为 0」是**结构性事实**（component_no 是销售料号口径 S-xxx，
  //      核价物料表主键是生产料号 xxx），它是本条 AC 要钉住的核心不变量
  expect(dMat,
    `AC-24 结构性不变量：明细核价 BOM 命中核价物料表应恒为 0（component_no 是销售料号口径 S-xxxx，` +
    `而 ds_cost_detail_material 主键是生产料号 xxxx），实际 ${dMat}。\n` +
    `  变成非 0 说明口径变了 —— 停下来报主线重新采口径，🚫 不要当成「改进」。`
  ).toBe(0);
  expect(dMat + dRec,
    `AC-24：明细核价 BOM 覆盖率突然升到 ${dMat + dRec}/${dTotal}（>50%）⇒ 同基础侧，怀疑接了 ds_quote_material（违反 D-10）`
  ).toBeLessThan(dTotal * 0.5);

  const a = ANCHORS.incomingOtherFee;
  const { id, name } = await openBuilderOf(page, a);
  const guard = readOnlyGuard(id, name);
  try {
    const results: string[] = [];
    for (const half of [
      { tag: 'AC-24 基础核价', ds: '基础核价', total: bTotal, hit: bMat + bRec, lookup: 'ds_cost_basic_material' },
      { tag: 'AC-24 明细核价', ds: '明细核价', total: dTotal, hit: dMat + dRec, lookup: 'ds_cost_detail_material' },
    ]) {
      const prefix = await selectDataset(page, half.ds);
      await selectSource(page, '物料BOM');
      await expandAllGroups(page);
      const sqlBefore = await sqlPanelText(page);
      await addField(page, F_MATERIAL_NAME);
      const sql = await sqlPanelText(page);
      expect(sql, `${half.tag} 证伪对照：加了「材料名」后 SQL 逐字未变`).not.toBe(sqlBefore);

      // 🚫 D-10 反向断言：核价 BOM 不得接报价物料表
      expect(tableRefCount(sql, 'ds_quote_material'),
        `${half.tag}：核价 BOM 的 SQL 里出现了 ds_quote_material —— 违反 D-10 用户裁决。SQL=\n${sql.slice(0, 2000)}`
      ).toBe(0);
      expect(leftJoinsOf(sql, half.lookup).length + leftJoinsOf(sql, 'material_recipe').length,
        `${half.tag}：应至少连上 ${half.lookup} 或 material_recipe 之一。SQL=\n${sql.slice(0, 2000)}`
      ).toBeGreaterThan(0);

      const pr = await runPreview(page, `${half.tag} 预览`);
      // 🔧 D-13 订正：AC-24 的覆盖率结论由**上方直连 SQL 的不变量**承担
      //    （明细命中核价物料表恒 0 · 两套覆盖率 < 50% · 材质那一路 > 0），
      //    预览按闭包收窄 ⇒ 🚫 不与全表行数对数，只验预览真的跑通了。
      expect(pr.rowCount,
        `${half.tag} 预览层：rowCount 应 > 0，实际 ${pr.rowCount}（HTTP=${pr.status} elapsedMs=${pr.elapsedMs}）`
      ).toBeGreaterThan(0);
      const st = materialNameStats(pr, half.tag);
      console.log(`[${half.tag}] 预览 ${st.total} 行 / 非空 ${st.nonEmpty} / 空 ${st.blank}；`
        + `覆盖率层（全表）总=${half.total} 非空=${half.hit}`);
      results.push(`## ${half.tag}（表前缀 ${prefix}）\n基准 ${half.total} 行 / 非空 ${half.hit} 行\n` +
        `预览 rowCount=${pr.rowCount} 非空=${st.nonEmpty} 空=${st.blank} 降级=${st.degraded}\n\n${sql}\n`);
      await shot(page, `AC-24-${half.ds}-物料BOM.png`);
    }

    archive('AC-24-核价BOM低覆盖.txt',
      `# AC-24 显式缺口断言（D-10 用户知情裁决，🚫 不是缺陷）\n` +
      `入口组件 ${id} / ${name}\n` +
      `基础：${bTotal} 行，命中核价物料 ${bMat} + 命中材质 ${bRec} = ${bMat + bRec}（文档 100 / 10+7=17）\n` +
      `明细：${dTotal} 行，命中核价物料 ${dMat} + 命中材质 ${dRec} = ${dMat + dRec}（文档 22 / 0+7=7）\n` +
      `根因（需求文档已写明）：component_no 是销售料号口径（S-3110520789 vs 3110520789，差 S- 前缀）\n\n` +
      results.join('\n'));
  } finally {
    guard.assertUntouched();
  }
});
