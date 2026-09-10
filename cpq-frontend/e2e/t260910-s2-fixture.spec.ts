/**
 * task-260910 · **S-造数片**（私有写，前缀 `T260910-`，自造自清）
 * 认领 AC-5 / AC-13（+ AC-14 第1条的批量导入入口）
 *
 * ── 为什么必须造数（testing.md §5.5「判据落在恒为 1 的维度」）────────────
 *   实查：`S0001` 在 `CUST-0001` 与 `CUST-0004` 下的 `ds_quote_material` 两行
 *   **production_no 完全相同（都是 300001）**，`ds_quote_customer_part` 也逐列相同。
 *   ⇒ 拿现网数据验「客户维度隔离」，**无论后端有没有 customer_no 过滤都会绿** = 零证据。
 *
 * ── 两阶段设计自带阳性对照（testing.md §4.4）──────────────────────────
 *   阶段A：**只**给「别的客户」造绑定 → 本客户必须仍是降级态（不许串号）。
 *     🔑 确定性来源：三个目标料号在对应 ds_* 表里的**全局行数 = 0**（已实查），
 *        所以漏掉 customer 过滤的查询**必然**返回我造的这一行，不依赖排序运气。
 *   阶段B：再给本客户造绑定 → 必须显示本客户的值（证明量具能看见值，不是"恒空"）。
 *
 * ── 写入面 ───────────────────────────────────────────────────────────
 *   ds_quote_customer_part / ds_quote_material / ds_cost_basic_material，全部带 T260910- 可识别。
 *   🚫 不 UPDATE 任何既有行；🚫 不动 material_customer_map；🚫 零 S-全局。
 */
import { test, expect } from '@playwright/test';
import * as H from './t260910.helpers';

// 三个确定性宿主（全局行数 = 0，已实查）
const CP_HOST_QUOTATION = 'QT-20260907-0592';  // CUST-0001 / mat=S-3110520789 / ds_quote_customer_part 全局 0 行
const CP_HOST_MAT = 'S-3110520789';
const QM_HOST_QUOTATION = 'QT-20260908-0628';  // CUST-0004 / mat=T260907T-B0000x / ds_quote_material 全局 0 行
const CAND_MAT = '3120011203';                 // CUST-0004 候选列表里的料号 / ds_quote_material 全局 0 行
const CUST0004 = '1f5818d8-b934-44be-b1c3-47df7053cff4';

test.describe.configure({ mode: 'serial' });

/** 🚦 兜底清理：serial 模式下任一阶段失败会跳过后续 test，但**造出来的行必须回收**。
 *  afterAll 无论成败都跑一遍；S2-Z 只负责「回读为 0」的确认。 */
const CLEAN_JOBS: [string, string][] = [
  ['ds_quote_material', `customer_no LIKE '${H.TAG}%' OR material_no LIKE '${H.TAG}%' OR production_no LIKE '${H.TAG}%'`],
  ['ds_quote_customer_part', `customer_no LIKE '${H.TAG}%' OR material_no LIKE '${H.TAG}%' OR customer_product_no LIKE '${H.TAG}%'`],
  ['ds_cost_basic_material', `production_no LIKE '${H.TAG}%'`],
];
test.afterAll(async () => {
  const lines: string[] = [];
  for (const [tb, w] of CLEAN_JOBS) {
    try { const r = H.cleanupPrefixed(tb, w); lines.push(`${tb}: before=${r.before} after=${r.after} ok=${r.ok}${r.err ? ' err=' + r.err : ''}`); }
    catch (e: any) { lines.push(`${tb}: 清理抛错 ${String(e?.message || e).slice(0, 300)}`); }
  }
  console.log('[afterAll cleanup]\n' + lines.join('\n'));
  H.appendEvidence('91-清理结果.txt', `[afterAll ${new Date().toISOString()}]\n` + lines.join('\n') + '\n');
});

let qmHostMat = '';

test('S2-0 · 前置：确认三个宿主料号的全局行数为 0（确定性判据的前提）', async () => {
  await H.assertWorktreeAndDb();
  const cpGlobal = Number(H.sqlScalar(`SELECT count(*) FROM ds_quote_customer_part WHERE material_no='${CP_HOST_MAT}'`));
  expect(cpGlobal, `前置未满足：${CP_HOST_MAT} 在 ds_quote_customer_part 全局已有 ${cpGlobal} 行 ⇒ decoy 不再是唯一候选，判据退化为「靠排序运气」，本组判【未验证】`).toBe(0);
  const candGlobal = Number(H.sqlScalar(`SELECT count(*) FROM ds_quote_material WHERE material_no='${CAND_MAT}'`));
  expect(candGlobal, `前置未满足：${CAND_MAT} 在 ds_quote_material 全局已有 ${candGlobal} 行`).toBe(0);
  qmHostMat = H.sqlScalar(`SELECT li.product_part_no_snapshot FROM quotation_line_item li
    JOIN quotation q ON q.id=li.quotation_id WHERE q.quotation_number='${QM_HOST_QUOTATION}'
      AND NOT EXISTS (SELECT 1 FROM ds_quote_material m WHERE m.material_no=li.product_part_no_snapshot)
    ORDER BY li.sort_order LIMIT 1`);
  expect(qmHostMat, `前置未满足：${QM_HOST_QUOTATION} 找不到「ds_quote_material 全局 0 行」的料号`).not.toBe('');
  // 残留检查：上一轮如果没清干净，这一轮的断言会被污染
  for (const [t, w] of [['ds_quote_material', `material_no LIKE '${H.TAG}%' OR customer_no LIKE '${H.TAG}%' OR production_no LIKE '${H.TAG}%'`],
                        ['ds_quote_customer_part', `material_no LIKE '${H.TAG}%' OR customer_no LIKE '${H.TAG}%' OR customer_product_no LIKE '${H.TAG}%'`],
                        ['ds_cost_basic_material', `production_no LIKE '${H.TAG}%'`]] as const) {
    const n = Number(H.sqlScalar(`SELECT count(*) FROM ${t} WHERE ${w}`));
    expect(n, `前置未满足：${t} 里还有 ${n} 行上一轮 ${H.TAG} 残留 ⇒ 先清干净再跑，否则断言被污染`).toBe(0);
  }
  H.writeEvidence('ac5-00-前置全局计数.txt',
    `cp(${CP_HOST_MAT}) 全局=${cpGlobal}（须 0）\nqm(${CAND_MAT}) 全局=${candGlobal}（须 0）\nqm 宿主料号=${qmHostMat}\n`);
});

test('S2-A · 阶段A：只给「别的客户」造绑定 → 本客户必须仍是降级态（不串号）', async ({ page }) => {
  // ① 客户料号侧 decoy
  H.sqlOwnedInsert(
    `INSERT INTO ds_quote_customer_part (customer_no, customer_part_name, customer_product_no, customer_drawing_no, material_no, source, created_by)
     VALUES ('T260910-C2','T260910-客户料号-B','T260910-CP-B','T260910-DWG-B','${CP_HOST_MAT}','IMPORT','t260910-tester')`,
    'decoy: ds_quote_customer_part @T260910-C2');
  // ② 生产料号侧 decoy（两处宿主共用同一个 BBB）
  H.sqlOwnedInsert(
    `INSERT INTO ds_cost_basic_material (production_no, material_name, specification, dimension, old_material_no, source, created_by)
     VALUES ('T260910-P-BBB','T260910-零件-BBB','T260910-规格-B','T260910-尺寸-B','T260910-旧-B','IMPORT','t260910-tester')`,
    'decoy: ds_cost_basic_material T260910-P-BBB');
  H.sqlOwnedInsert(
    `INSERT INTO ds_quote_material (customer_no, material_no, production_no, source, created_by)
     VALUES ('T260910-C2','${qmHostMat}','T260910-P-BBB','IMPORT','t260910-tester')`,
    `decoy: ds_quote_material @T260910-C2 / ${qmHostMat}`);
  H.sqlOwnedInsert(
    `INSERT INTO ds_quote_material (customer_no, material_no, production_no, source, created_by)
     VALUES ('T260910-C2','${CAND_MAT}','T260910-P-BBB','IMPORT','t260910-tester')`,
    `decoy: ds_quote_material @T260910-C2 / ${CAND_MAT}`);

  const log: string[] = [];
  await H.uiLogin(page);

  // T5.1a · B-1 路径：别的客户的客户料号不许泄漏
  await H.openStep2(page, CP_HOST_QUOTATION);
  const c1 = await H.readCards(page);
  expect(c1.length, `${CP_HOST_QUOTATION} 卡片数应 > 0（0 张则断言空跑）`).toBeGreaterThan(0);
  log.push(`[T5.1a] ${CP_HOST_QUOTATION} 卡片=${JSON.stringify(c1.map((x) => x.leftTexts))}`);
  for (const c of c1) {
    expect(c.leftTexts.join(' '), `AC-5 🚨 跨客户串号：CUST-0001 的卡片显示了 T260910-C2 的客户料号 ⇒ 客户料号查询漏了 customer_no 过滤`)
      .not.toContain('T260910-');
    expect(c.leftChildCount, `AC-5 阶段A：本客户无客户料号时左块应仍为空，实际 ${c.leftChildCount} 个子节点：${JSON.stringify(c.leftTexts)}`).toBe(0);
  }
  await H.shot(page, 'ac5-A-客户料号不串号');

  // T5.1b · B-2 路径：别的客户的生产料号绑定不许泄漏到浮层
  await H.openStep2(page, QM_HOST_QUOTATION);
  const cards2 = await H.readCards(page);
  expect(cards2.length, `${QM_HOST_QUOTATION} 卡片数应 > 0`).toBeGreaterThan(0);
  const idx = cards2.findIndex((c) => c.rightTexts.some((t) => t === `销售料号: ${qmHostMat}`));
  expect(idx, `找不到销售料号 ${qmHostMat} 的卡片 ⇒ **入口问题**，判【未验证】。实际=${JSON.stringify(cards2.map((c) => c.rightTexts))}`).toBeGreaterThanOrEqual(0);
  const popA = await H.openPopover(page, idx);
  log.push(`[T5.1b] ${qmHostMat} 浮层=\n${popA.raw}`);
  expect(popA.raw, `AC-5 🚨 跨客户串号：CUST-0004 的浮层显示了 T260910-C2 绑定的生产料号 ⇒ ds_quote_material 查询漏了 customer_no 过滤\n实际:\n${popA.raw}`)
    .not.toContain('T260910-');
  expect(popA.raw, `AC-5 阶段A：本客户未绑定生产料号时应显示「未绑定生产料号」\n实际:\n${popA.raw}`).toContain('未绑定生产料号');
  await H.shot(page, 'ac5-A-生产料号不串号');

  // T5.1c · B-4 路径（选品候选）：同样不许串号
  const cookie = await H.loginApi();
  const r = await H.apiGet(cookie, `/api/cpq/quotations/customer-part-candidates?customerId=${CUST0004}`);
  expect(r.status, '候选端点应 200').toBe(200);
  const items: any[] = r.json?.data ?? [];
  expect(items.length, `AC-13 前置：CUST-0004 候选列表为空 ⇒ 断言会空跑（testing.md §5.5 ③）`).toBeGreaterThan(0);
  const cand = items.find((x) => x.partNo === CAND_MAT);
  expect(cand, `AC-13 前置：候选列表里找不到 ${CAND_MAT}，实际=${JSON.stringify(items.map((x) => x.partNo))}`).toBeTruthy();
  log.push(`[T5.1c 阶段A] 候选 ${CAND_MAT} = ${JSON.stringify(cand)}`);
  expect(JSON.stringify(cand ?? {}), `AC-5 🚨 候选列表串号：hfPartInfo 取到了 T260910-C2 的绑定\n实际=${JSON.stringify(cand)}`).not.toContain('T260910-');
  H.appendEvidence('ac5-跨客户隔离.txt', `=== 阶段A（只有 T260910-C2 有绑定）===\n${log.join('\n')}\n\n`);
});

test('S2-B · 阶段B：补本客户绑定 → 必须显示本客户的值（阳性对照 + AC-13 契约）', async () => {
  H.sqlOwnedInsert(
    `INSERT INTO ds_cost_basic_material (production_no, material_name, specification, dimension, old_material_no, source, created_by)
     VALUES ('T260910-P-AAA','T260910-零件-AAA','T260910-规格-A','T260910-尺寸-A','T260910-旧-A','IMPORT','t260910-tester')`,
    'ds_cost_basic_material T260910-P-AAA');
  H.sqlOwnedInsert(
    `INSERT INTO ds_quote_material (customer_no, material_no, production_no, source, created_by)
     VALUES ('CUST-0004','${CAND_MAT}','T260910-P-AAA','IMPORT','t260910-tester')`,
    `ds_quote_material @CUST-0004 / ${CAND_MAT} → AAA`);

  const cookie = await H.loginApi();
  const r = await H.apiGet(cookie, `/api/cpq/quotations/customer-part-candidates?customerId=${CUST0004}`);
  expect(r.status, '候选端点应 200').toBe(200);
  const cand = (r.json?.data ?? []).find((x: any) => x.partNo === CAND_MAT);
  expect(cand, `候选列表里找不到 ${CAND_MAT}`).toBeTruthy();
  const hf = cand.hfPartInfo;
  console.log('[阶段B 候选] ' + JSON.stringify(cand, null, 1));
  // 阳性对照：证明这条判据在「本客户有绑定」时确实能看见值（阶段A 的 null 才有意义）
  expect(hf, `AC-5 阳性对照失败：本客户已有绑定但 hfPartInfo 仍为空 ⇒ 阶段A 的「没串号」可能只是**恒为空**（零证据）`).toBeTruthy();
  expect(hf.partNo, 'AC-5/AC-13 生产料号应取本客户(CUST-0004)的绑定').toBe('T260910-P-AAA');
  expect(hf.partName, 'AC-13 名称').toBe('T260910-零件-AAA');
  expect(hf.specification, 'AC-13 规格').toBe('T260910-规格-A');
  expect(hf.sizeInfo, 'AC-13 尺寸').toBe('T260910-尺寸-A');
  expect(hf.oldMaterialNo, 'AC-13 旧料号（本次新增字段）').toBe('T260910-旧-A');
  expect(Object.prototype.hasOwnProperty.call(hf, 'statusCode'),
    `AC-13 🚫 hfPartInfo 不得再返回 statusCode，实际=${JSON.stringify(hf)}`).toBe(false);
  expect(JSON.stringify(hf), 'AC-5 不得混入 T260910-C2 的 -B 值').not.toContain('-B');
  H.appendEvidence('ac5-跨客户隔离.txt', `=== 阶段B（本客户 CUST-0004 也有绑定）===\n候选=${JSON.stringify(cand, null, 1)}\n`);
  H.writeEvidence('ac13-候选契约.json', JSON.stringify(cand, null, 1));
});

test('S2-C · AC-13（2026-09-10 改写口径）· 候选列表 hfPartInfo 三条断言', async () => {
  const cookie = await H.loginApi();

  // ── 断言② 未绑定生产料号时 hfPartInfo 必须为 null ──────────────────────
  //    改动前的行为是「查不到就用销售料号+销售品名冒充」，本条就是要证明**假数据被消掉了**。
  //    🚫 现网正向数据为 0 是已知事实，「hfPartInfo 全 null」在这里**是期望值不是缺陷**。
  const custs = H.sqlRows(`SELECT id::text cid, code FROM customer WHERE code IN ('CUST-0001','CUST-0004','CUST-0058') ORDER BY code`);
  expect(custs.length, '前置：取不到对照客户').toBeGreaterThan(0);
  let scanned = 0, nullCnt = 0, nonNull: any[] = [];
  const detail: string[] = [];
  for (const c of custs) {
    const r = await H.apiGet(cookie, `/api/cpq/quotations/customer-part-candidates?customerId=${c.cid}`);
    expect(r.status, `候选端点 ${c.code} 应 200，实际 ${r.status}`).toBe(200);
    const items: any[] = r.json?.data ?? [];
    detail.push(`${c.code}: ${items.length} 个候选 → ${JSON.stringify(items.map((x) => ({ partNo: x.partNo, hf: x.hfPartInfo ?? null })))}`);
    for (const it of items) {
      scanned++;
      // 该候选料号在本客户下有没有生产料号绑定？（这是「应不应该有 hfPartInfo」的唯一依据）
      const bound = H.sqlScalar(
        `SELECT COALESCE(production_no,'') FROM ds_quote_material WHERE customer_no='${c.code}' AND material_no='${String(it.partNo).replace(/'/g, "''")}' LIMIT 1`);
      if (!bound) {
        nullCnt++;
        expect(it.hfPartInfo ?? null,
          `AC-13② ${c.code}/${it.partNo} 在 ds_quote_material 无生产料号绑定，hfPartInfo 必须为 null（改动前会用销售料号冒充）。实际=${JSON.stringify(it.hfPartInfo)}`)
          .toBeNull();
      } else if (it.hfPartInfo) {
        nonNull.push({ cust: c.code, partNo: it.partNo, bound, hf: it.hfPartInfo });
      }
    }
  }
  // 防空跑：必须真的扫到过样本，否则这条断言从未执行（testing.md §3 第3条）
  expect(scanned, 'AC-13② 三个客户的候选列表合计 0 行 ⇒ 断言从未执行（假绿），判【未验证】').toBeGreaterThan(0);
  expect(nullCnt, 'AC-13② 没有任何「无绑定」样本 ⇒ 该断言空跑').toBeGreaterThan(0);
  console.log(`[AC-13②] 扫描 ${scanned} 个候选，其中无绑定 ${nullCnt} 个（均已断言 hfPartInfo=null），有绑定且非空 ${nonNull.length} 个`);

  // ── 断言① 有 hfPartInfo 的行：不含 statusCode，含 oldMaterialNo ────────
  for (const n of nonNull) {
    expect(Object.prototype.hasOwnProperty.call(n.hf, 'statusCode'),
      `AC-13① ${n.cust}/${n.partNo} hfPartInfo 不得再含 statusCode，实际=${JSON.stringify(n.hf)}`).toBe(false);
    expect(Object.prototype.hasOwnProperty.call(n.hf, 'oldMaterialNo'),
      `AC-13① ${n.cust}/${n.partNo} hfPartInfo 应含新字段 oldMaterialNo，实际=${JSON.stringify(n.hf)}`).toBe(true);
  }
  // ⚠️ nonNull 可能为 0（现网正向数据为 0 是已知事实）⇒ 断言①/③ 的正向样本由**阶段B 造数**提供，
  //    那条在 S2-B 已断言过（partNo=T260910-P-AAA + 无 statusCode + 有 oldMaterialNo）。
  H.writeEvidence('ac13-候选契约-新口径.txt',
    `扫描候选=${scanned}，无绑定(断言 hfPartInfo=null)=${nullCnt}，有绑定且非空=${nonNull.length}
` +
    detail.join('\n') + `\n非空样本=${JSON.stringify(nonNull, null, 1)}\n`);
});

test('S2-Z · 回收：按前缀删除本片自造的行（先量化命中面，删后回读为 0）', async () => {
  // 🩹 harness 修复：上一轮把清理放在 `afterAll`，而 Playwright 的 afterAll 在**所有 test 之后**才跑，
  //    于是这条「回收确认」测到的是清理**前**的状态，报出「残留 6 行」——
  //    看起来像清理失效，实际是**我把顺序写反了**（afterAll 随后确实清成了 0）。
  //    改成本条自己清理+回读；afterAll 保留作兜底（幂等，第二次 before=0）。
  const report: string[] = [];
  const failed: string[] = [];
  for (const [tb, w] of CLEAN_JOBS) {
    const r = H.cleanupPrefixed(tb, w);
    report.push(`${tb}: before=${r.before} after=${r.after} ok=${r.ok}${r.err ? ' err=' + r.err : ''}`);
    if (!r.ok) failed.push(`${tb} 残留 ${r.after} 行（WHERE ${w}）${r.err ? '：' + r.err : ''}`);
  }
  H.writeEvidence('91-清理结果.txt', report.join('\n') + '\n');
  console.log('[cleanup]\n' + report.join('\n'));
  expect(failed, `🚨 清理未完成，需写进 test-report.md 的「待回收清单」交主线（🚫 不换写法重试）：\n${failed.join('\n')}`).toEqual([]);
});
