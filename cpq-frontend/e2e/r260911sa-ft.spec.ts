/**
 * repair-260911 · S-A 证伪实验（阳性对照）
 *
 * FT-3（AC-R2 的阳性对照，问题说明 §⑦ AC-R2 原文）：
 *   把该单 `quote_card_values` 置 NULL ⇒ 必须退回平铺 + 空行。
 *   🚨 只打**本片自造单**（`T260911RA-` 前缀），先量化命中面，跑完立刻还原并核对。
 *   🚫 绝不碰任何存量单据（`CLAUDE.md §3.2` 红线，测试员无批准权）。
 *
 * 运行：
 *   PW_QUOTATION=<自造单id> PW_LINE_ITEM=<自造行id> PW_RUN_ID=ft3 \
 *   npx playwright test --config=e2e/r260911sa.config.ts r260911sa-ft.spec.ts
 */
import { test, expect } from '@playwright/test';
import * as H from './r260911sa.helpers';
import * as fs from 'fs';
import * as nodePath from 'path';

const TAB_BOM = 'BOM';
const SCRATCH = process.env.PW_SCRATCH
  || '/tmp/claude-1000/-home-joii-project-cpq/64be906e-6bde-4ab8-bd71-5430f69406f7/scratchpad';

test.describe.configure({ mode: 'serial' });

test('FT-3 · quote_card_values 置 NULL ⇒ AC-R2 必须变红（退回平铺 + 空行）', async ({ page }) => {
  const EV = `SA-FT3-${H.RUN}.txt`;
  const qid = process.env.PW_QUOTATION || '';
  const lid = process.env.PW_LINE_ITEM || '';
  expect(qid, 'FT-3 前置：必须传 PW_QUOTATION（自造单 id）').toMatch(/^[0-9a-f-]{36}$/);
  expect(lid, 'FT-3 前置：必须传 PW_LINE_ITEM（自造行 id）').toMatch(/^[0-9a-f-]{36}$/);

  // 🔒 归属校验：这张单必须是本片自造的（单号带 T260911RA- 前缀），否则拒绝往下走。
  const own = H.sqlRows(
    `SELECT q.quotation_number qn, li.id::text lid, li.product_part_no_snapshot pn
       FROM quotation q JOIN quotation_line_item li ON li.quotation_id=q.id
      WHERE q.id='${qid}' AND li.id='${lid}'`);
  expect(own.length, 'FT-3 前置：单 + 行对不上 ⇒ 拒绝执行').toBe(1);
  expect(own[0].qn, `🚨 FT-3 只许打本片自造单（前缀 ${H.TAG}）。实际单号 = ${own[0].qn} ⇒ 停下报主线`)
    .toContain(H.TAG);
  console.log(`[FT-3 归属校验通过] ${JSON.stringify(own[0])}`);
  H.appendEvidence(EV, `\n===== ${new Date().toISOString()} FT-3 =====\n归属=${JSON.stringify(own[0])}\n`);

  await H.uiLogin(page);
  const fx = { customerNo: '', quotationId: qid, quotationNumber: own[0].qn };

  // ── 干预前：基线（树）────────────────────────────────────────────
  await H.openStep2(page, fx);
  await page.waitForTimeout(15_000);
  const before = await H.probeTab(page, TAB_BOM);
  console.log('[FT-3 干预前·行] ' + JSON.stringify(before.rows));
  H.appendEvidence(EV, `【干预前】行=${JSON.stringify(before.rows, null, 1)}\n`);
  expect(before.rows.length, 'FT-3 前置：干预前 BOM 0 行 ⇒ 断言会空跑，判【未验证】').toBeGreaterThan(0);

  // ── 干预：备份 → 置 NULL ─────────────────────────────────────────
  const bak = nodePath.join(SCRATCH, `ft3-qcv-${lid}.json`);
  const bytes = H.backupJsonb('quotation_line_item', 'id', lid, 'quote_card_values', bak);
  expect(bytes, 'FT-3：备份为空 ⇒ 没得还原，拒绝继续').toBeGreaterThan(10);
  let restored = false;
  try {
    H.sqlOwnedUpdate(
      `UPDATE quotation_line_item SET quote_card_values = NULL WHERE id='${lid}'`,
      `SELECT count(*) FROM quotation_line_item WHERE id='${lid}'`, 1, 'FT-3 置 NULL');
    // 🔬 确认干预真的生效（不是"改了没落库"）
    const nowNull = H.sqlScalar(
      `SELECT CASE WHEN quote_card_values IS NULL THEN 'NULL' ELSE 'NOT-NULL' END
         FROM quotation_line_item WHERE id='${lid}'`);
    console.log(`[FT-3 干预生效确证] quote_card_values = ${nowNull}`);
    H.appendEvidence(EV, `干预生效确证：quote_card_values=${nowNull}（备份 ${bytes} 字节 → ${bak}）\n`);
    expect(nowNull, 'FT-3：干预没生效 ⇒ 后面的红/绿都说明不了问题').toBe('NULL');

    // 🚧 同时掐掉 warm 回补：否则页面一打开就 ensure-card-values 把快照算回来，
    //    平铺态只在极短的瞬间存在 ⇒ 观测不到，实验会假绿。
    //    这是**纯测试侧**干预（route abort），不改任何产品代码。
    const blocked: string[] = [];
    await page.route('**/ensure-card-values*', async (route) => {
      blocked.push(route.request().url());
      await route.abort();
    });

    await H.openStep2(page, fx);
    await page.waitForTimeout(25_000);
    const after = await H.probeTab(page, TAB_BOM);
    await H.shot(page, `SA-FT3-置NULL后-${H.RUN}`);
    console.log('[FT-3 拦截的 ensure 调用] ' + JSON.stringify(blocked));
    console.log('[FT-3 干预后·行] ' + JSON.stringify(after.rows));
    H.appendEvidence(EV, `拦截 ensure=${JSON.stringify(blocked)}\n`
      + `【干预后】表头=${JSON.stringify(after.header)}\n行=${JSON.stringify(after.rows, null, 1)}\n`);

    const nodeRows = after.rows.filter((r) => r.nodeId != null && r.nodeId !== '');
    const emptyRows = after.rows.filter((r) => H.rowIsAllEmpty(r.cells));
    const summary = `干预前: 行=${before.rows.length} 带nodeId=${before.rows.filter((r) => r.nodeId).length} `
      + `全空行=${before.rows.filter((r) => H.rowIsAllEmpty(r.cells)).length}\n`
      + `干预后: 行=${after.rows.length} 带nodeId=${nodeRows.length} 全空行=${emptyRows.length}\n`;
    console.log('[FT-3 对照]\n' + summary);
    H.appendEvidence(EV, summary);

    // 🧪 判据：AC-R2 的三条断言必须**真的变红**
    expect.soft(nodeRows.length,
      `FT-3 判据①：置 NULL 后应退回**平铺**（<tr> 不带 data-node-id）。实际带 nodeId 的行 = ${nodeRows.length}`
      + `\n  🚨 若仍 > 0 ⇒ AC-R2① 的绿是空断言，说明它并不依赖 quote_card_values`).toBe(0);
    expect.soft(emptyRows.length,
      `FT-3 判据②：置 NULL 后应出现**整行全空的行**（合成根行的业务列全 null）。实际全空行 = ${emptyRows.length}`
      + `\n  行 = ${JSON.stringify(after.rows)}`).toBeGreaterThan(0);

    H.writeEvidence(`SA-FT3-结论-${H.RUN}.txt`,
      `单=${own[0].qn} (${qid}) 行=${lid}\n${summary}`
      + `干预前行=${JSON.stringify(before.rows)}\n干预后行=${JSON.stringify(after.rows)}\n`);
  } finally {
    // ── 还原（无论成败都必须做）──────────────────────────────────
    const len = H.restoreJsonb('quotation_line_item', 'id', lid, 'quote_card_values', bak);
    restored = len > 10;
    const check = H.sqlScalar(
      `SELECT CASE WHEN quote_card_values IS NULL THEN 'NULL' ELSE 'NOT-NULL' END
         FROM quotation_line_item WHERE id='${lid}'`);
    console.log(`[FT-3 还原核对] ${check} 长度=${len}`);
    H.appendEvidence(EV, `还原核对：${check} 长度=${len}（原 ${bytes} 字节）\n`);
    fs.existsSync(bak) && console.log(`[FT-3 备份保留] ${bak}`);
    expect(check, '🚨 FT-3 还原失败 —— 立即报主线').toBe('NOT-NULL');
    expect(restored, '🚨 FT-3 还原后长度异常 —— 立即报主线').toBe(true);
  }
});

/**
 * FT-3 补充 · **量具非空断言自检**（不依赖真机）。
 *
 * 背景：FT-3 实跑里「退回平铺」确实发生了（带 nodeId 的行 3 → 0），但**没有**出现整行全空的行
 * ⇒ AC-R2③ 那条断言这一轮没被证伪过。为了至少证明**判据本身不是恒真**，
 * 这里直接喂合成输入验证 `rowIsAllEmpty` 会真的返回 true / false。
 * 🚨 这只能证明「量具会红」，**证明不了 AC-R2③ 的绿是有因果的** —— 报告里如实标注。
 */
test('FT-3 补充 · rowIsAllEmpty 量具非恒真自检（剥掉控件字形后才判空）', async () => {
  // ① 平铺态下的合成根行：业务列全空，只剩操作列按钮 ⇒ 必须判为「全空」
  expect(H.rowIsAllEmpty(['', '', '', '', '', '', '', '', '', '', '＋✕']),
    '量具①：业务列全空、只剩「＋✕」操作列的行，必须判为全空（否则 AC-R2③ 恒真）').toBe(true);
  // ② 树模式下的根行：BOM 固定列里有料号 ⇒ 不算空
  expect(H.rowIsAllEmpty(['▼✂1009-2609000001', '', '', '', '', '', '', '', '', '', '＋✕']),
    '量具②：树模式根行在「BOM」固定列里有料号，不算空行').toBe(false);
  // ③ 有业务值的普通行 ⇒ 不算空
  expect(H.rowIsAllEmpty(['✂00005', '1009-2609000001', '1', '00005', 'AgNi25C2', '', '', '', '', '', '＋✕']),
    '量具③：有业务值的行不算空').toBe(false);
  // ④ 「加载中」占位也算空（AP-31 族）
  expect(H.rowIsAllEmpty(['加载中…', '', '']), '量具④：加载中占位算空').toBe(true);
});
