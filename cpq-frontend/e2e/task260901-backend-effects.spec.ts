/**
 * E2E · task-260901 —— B. 后端只动该动的（需求文档 §③ B）
 *
 * 覆盖：T-6(AC-6) · T-7(AC-7) · T-8(AC-8)
 *
 * 三条共用同一个用户操作（= AC-1 的操作），但**各自独立执行一遍**，不共享状态 ——
 * 用例之间共享中间态是不稳定失败的常见来源（testing.md §4）。
 *
 * 🚫 不 import src/；断言口径来自 AC 原文。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import {
  BASELINE_QUOTATION_ID, BASELINE_COMPONENT_DATA_COUNT, BASELINE_LINE_COUNT,
  AC1_TAB_NAME, AC1_ROW_INDEX, AC1_COLUMN_NAME, AC1_TARGET_VALUE,
  assertBaselineShape, loginAdmin, openEditStep2, cardByPartNo, openCardTab,
  editCell, readCellInputValue, distinctFrom, saveDraftCapture,
  queryLineAt, queryRowDataFingerprints, queryComponentDataXmin, queryNullCardValueCounts,
  psqlRows, psqlScalar, archiveJson, archiveShot,
  waitBackendUp,
} from './fixtures/task260901-draft';

let backendUp = false;
test.beforeAll(async () => {
  backendUp = await waitBackendUp();
  if (backendUp) assertBaselineShape();
});

/** 执行 AC-1 的那一次用户操作（改一个格子），返回目标行 id 与写入的新值。 */
async function performAc1Edit(page: Page): Promise<{ lineId: string; partNo: string; value: string }> {
  const target = queryLineAt(BASELINE_QUOTATION_ID, 0);
  expect(target.partNo, '目标行必须有料号').not.toBe('');
  await loginAdmin(page);
  await openEditStep2(page, BASELINE_QUOTATION_ID);
  const card = await cardByPartNo(page, target.partNo);
  await openCardTab(page, card, AC1_TAB_NAME);
  const before = await readCellInputValue(card, AC1_ROW_INDEX, AC1_COLUMN_NAME);
  const value = distinctFrom(before, AC1_TARGET_VALUE);
  expect(value, '新值必须与原值不同').not.toBe(before);
  await editCell(page, card, AC1_ROW_INDEX, AC1_COLUMN_NAME, value);
  return { lineId: target.id, partNo: target.partNo, value };
}

// ══════════════════════════════════════════════════════════════════════════
// T-6 / AC-6
// AC 原文：「执行 AC-1 后，quotation_line_component_data 表中该报价单的 9225 条记录里，
//           只有 1 条的 row_data 内容发生变化（比对保存前后的整表 md5 指纹，仅目标行不同）。」
// ══════════════════════════════════════════════════════════════════════════
test('T-6 保存后 9225 条 componentData 里只有 1 条 row_data 指纹变化', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(900_000);

  const fpBefore = queryRowDataFingerprints(BASELINE_QUOTATION_ID);
  const xminBefore = queryComponentDataXmin(BASELINE_QUOTATION_ID);
  // 🚨 结果非空守卫：先证明指纹集合本身是满的，否则"只有 1 条不同"可能是"一条都没取到"
  expect(fpBefore.size, `AC-6 前置：componentData 指纹样本必须是 ${BASELINE_COMPONENT_DATA_COUNT} 条，实际 ${fpBefore.size}`)
    .toBe(BASELINE_COMPONENT_DATA_COUNT);
  expect([...fpBefore.values()].every((v) => v && v.length === 32), '每条指纹都应是 32 位 md5（非空守卫）').toBe(true);

  const { lineId, value } = await performAc1Edit(page);
  const cap = await saveDraftCapture(page, 200);
  console.log(`[T-6] 保存完成，请求体 ${cap.requestBytes} B`);

  const fpAfter = queryRowDataFingerprints(BASELINE_QUOTATION_ID);
  const xminAfter = queryComponentDataXmin(BASELINE_QUOTATION_ID);
  expect(fpAfter.size, 'AC-6：保存后条数不应变化').toBe(BASELINE_COMPONENT_DATA_COUNT);

  const contentChanged = [...fpBefore.entries()].filter(([id, md5]) => fpAfter.get(id) !== md5).map(([id]) => id);
  const xminChanged = [...xminBefore.entries()].filter(([id, x]) => xminAfter.get(id) !== x).map(([id]) => id);

  // 目标条 = 该 line item 下 tab_name = 物料 的那条
  const targetCdId = psqlScalar(
    `SELECT id::text FROM quotation_line_component_data WHERE line_item_id='${lineId}' AND tab_name='${AC1_TAB_NAME}' LIMIT 1;`
  );
  expect(targetCdId, '应能定位到目标 componentData 记录').not.toBe('');

  archiveJson('T-6-fingerprint-diff', {
    total: fpAfter.size, contentChanged, xminChanged: xminChanged.length,
    xminChangedSample: xminChanged.slice(0, 20), targetCdId, writtenValue: value,
  });
  console.log(`[T-6] 内容变化条数 = ${contentChanged.length}（${contentChanged.join(',')}）；xmin 变化条数 = ${xminChanged.length}`);

  expect(contentChanged.length,
    `AC-6：只有 1 条 row_data 内容应变化，实际 ${contentChanged.length} 条：${contentChanged.slice(0, 10).join(',')}`
  ).toBe(1);
  expect(contentChanged[0], 'AC-6：变化的那条必须正是被改的那一条').toBe(targetCdId);

  // 附带诊断（需求文档 §② ①「未变的行不 UPDATE」的物理层证据）——不作为 AC-6 的硬判据，
  // 因为 AC-6 说的是"内容变化"，而 xmin 还会被同事务里其它列的写动作带动。
  console.log(`[T-6][诊断] 物理层被 UPDATE（xmin 变化）的 componentData 条数 = ${xminChanged.length}`);
});

// ══════════════════════════════════════════════════════════════════════════
// T-7 / AC-7
// AC 原文：「执行 AC-1 后立即查库：SELECT count(*) ... WHERE quote_card_values IS NULL 结果为 1
//           （只有被改的那一行卡片值被清空），costing_card_values IS NULL 同样为 1。」
// 🚨 关键：AC-7 说的是「保存后立刻」。保存后后台会跑 ensure-card-values 把它填回去，
//    所以本用例在 PUT /draft 响应到达后**立即**查库，且先断言保存前 NULL 数为 0。
// ══════════════════════════════════════════════════════════════════════════
test('T-7 保存后立即查库：quote/costing card values 各恰好 1 行被置 NULL，且正是被改的那行', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(900_000);

  const before = queryNullCardValueCounts(BASELINE_QUOTATION_ID);
  console.log(`[T-7] 保存前 NULL 计数 quote=${before.quoteNull} costing=${before.costingNull} total=${before.total}`);
  expect(before.total, `AC-7 前置：基准单应有 ${BASELINE_LINE_COUNT} 行`).toBe(BASELINE_LINE_COUNT);
  // 🚨 非空守卫：保存前必须一条 NULL 都没有，否则"保存后 = 1"可能是上一轮遗留，不是本次清空
  expect(before.quoteNull,
    `AC-7 前置：保存前 quote_card_values NULL 行数必须为 0（实际 ${before.quoteNull}）——` +
    '不为 0 说明卡片值还没 warm 完或上一轮残留，此时 AC-7 的 "=1" 没有分辨力。请先跑完 ensure-card-values 再测。'
  ).toBe(0);
  expect(before.costingNull, 'AC-7 前置：保存前 costing_card_values NULL 行数必须为 0').toBe(0);

  const { lineId } = await performAc1Edit(page);
  await saveDraftCapture(page, 200);

  // 立即查（不等后台 warm）
  const after = queryNullCardValueCounts(BASELINE_QUOTATION_ID);
  const nullIds = psqlRows(
    `SELECT id::text FROM quotation_line_item WHERE quotation_id='${BASELINE_QUOTATION_ID}' AND quote_card_values IS NULL;`
  ).map((r) => r[0]);
  archiveJson('T-7-null-card-values', { before, after, nullIds, targetLineId: lineId });
  console.log(`[T-7] 保存后 NULL 计数 quote=${after.quoteNull} costing=${after.costingNull}，NULL 行=${nullIds.join(',')}`);

  expect(after.quoteNull, `AC-7：quote_card_values IS NULL 应为 1，实际 ${after.quoteNull}`).toBe(1);
  expect(after.costingNull, `AC-7：costing_card_values IS NULL 应为 1，实际 ${after.costingNull}`).toBe(1);
  expect(nullIds, 'AC-7：被清空的必须正是被改的那一行').toEqual([lineId]);
});

// ══════════════════════════════════════════════════════════════════════════
// T-8 / AC-8（2026-09-01 判据已改，见 需求文档.md AC-8 的 📌 注）
// AC 原文：「执行 AC-1 后，保存所触发的卡片值补算**只覆盖 1 行**。判据（DB，可留存）：
//           保存完成后轮询至该单 quote_card_values IS NULL 的行数归 0，期间该计数的**峰值为 1**
//           （不是 1845）。后端日志 `[ensure-cardvalues] 补算 N 行` 作为**可选第二路取证**，
//           不作为唯一判据。」
//
// 🔑 采样必须**跨过保存提交那一刻**：NULL 计数的轨迹是 0 →（saveDraft 提交后）N →（补算完成）0。
//    只在 PUT 返回之后才开始采样，会漏掉 N 这一段 —— 所以轮询与点击**并发启动**。
// ══════════════════════════════════════════════════════════════════════════
test('T-8 保存后 quote_card_values IS NULL 计数的峰值为 1（不是 1845），最终归 0', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(900_000);

  const start = queryNullCardValueCounts(BASELINE_QUOTATION_ID);
  console.log(`[T-8] 起始 NULL 计数 quote=${start.quoteNull} total=${start.total}`);
  expect(start.total, `AC-8 前置：基准单应有 ${BASELINE_LINE_COUNT} 行`).toBe(BASELINE_LINE_COUNT);
  // 🚨 非空守卫：起点必须是 0，否则"峰值 1"可能是上一轮残留，不是本次清空
  expect(start.quoteNull,
    `AC-8 前置：保存前 quote_card_values NULL 行数必须为 0（实际 ${start.quoteNull}）——` +
    '不为 0 说明上一轮的补算还没跑完，此时峰值判据没有分辨力。'
  ).toBe(0);

  const logPath = process.env.TASK260901_BACKEND_LOG || '';
  const logSizeBefore = logPath && fs.existsSync(logPath) ? fs.statSync(logPath).size : -1;

  // 先把编辑做完（编辑本身不触发保存），再并发启动「点保存」与「轮询采样」
  const { lineId } = await performAc1Edit(page);

  const samples: number[] = [];
  let peak = 0;
  let sawNonZero = false;
  let returnedToZero = false;

  const poller = (async () => {
    const deadline = Date.now() + 420_000;
    while (Date.now() < deadline) {
      const n = Number(psqlScalar(
        `SELECT count(*) FROM quotation_line_item WHERE quotation_id='${BASELINE_QUOTATION_ID}' AND quote_card_values IS NULL;`
      ));
      samples.push(n);
      if (n > peak) peak = n;
      if (n > 0) sawNonZero = true;
      if (sawNonZero && n === 0) { returnedToZero = true; break; }
      await new Promise((r) => setTimeout(r, 250));
    }
  })();

  const [cap] = await Promise.all([saveDraftCapture(page, 200), poller]);
  console.log(`[T-8] 采样序列（前 40 个）= ${JSON.stringify(samples.slice(0, 40))}；峰值 = ${peak}；样本数 = ${samples.length}`);
  archiveJson('T-8-null-count-trace', {
    lineId, peak, sawNonZero, returnedToZero, sampleCount: samples.length,
    samples: samples.slice(0, 200), putMs: cap.putMs,
  });

  // 🚨 分辨力守卫：没采到任何非 0，说明要么根本没清空、要么采样过疏 —— 两种都不能算通过
  expect(sawNonZero,
    'AC-8：整个观察窗口里 NULL 计数从未 >0 —— 要么被改的行压根没被清空（AC-7 会同时红），' +
    '要么采样过疏漏掉了那一段。无论哪种，本用例此时都没有分辨力。'
  ).toBe(true);
  expect(returnedToZero, 'AC-8：NULL 计数必须在 420 s 内回落到 0（补算完成）').toBe(true);

  expect(peak,
    `AC-8：NULL 计数峰值必须为 1，实际 ${peak}（1845 = 全量重算，正是本次要修的病灶；` +
    `介于两者之间说明清空范围超出了被改的那一行）`
  ).toBe(1);

  // 补充确证：被清空的那一行必须正是被改的那一行（峰值 1 本身不说明是"哪一行"）
  const finalNull = queryNullCardValueCounts(BASELINE_QUOTATION_ID);
  expect(finalNull.quoteNull, 'AC-8：最终 NULL 行数应为 0').toBe(0);
  const filled = psqlScalar(
    `SELECT count(*) FROM quotation_line_item WHERE id='${lineId}' AND quote_card_values IS NOT NULL;`);
  expect(filled, 'AC-8：目标行的卡片值应已被补算回填').toBe('1');

  // ── 可选第二路：日志原文旁证 ──
  if (logPath && logSizeBefore >= 0) {
    const tail = fs.readFileSync(logPath).subarray(logSizeBefore).toString('utf-8');
    const lines = tail.split('\n').filter((l) => l.includes('[ensure-cardvalues]'));
    console.log(`[T-8] 日志新增的 [ensure-cardvalues] 记录:\n${lines.join('\n')}`);
    if (lines.length > 0) {
      expect(lines.some((l) => /补算\s*1845\s*行/.test(l)),
        `AC-8 旁证：日志不得出现「补算 1845 行」。原文：${lines.join(' / ')}`).toBe(false);
    } else {
      console.warn('[T-8] 日志里没有 [ensure-cardvalues] 记录 —— 旁证未取到，不影响上面的 DB 主判据');
    }
  } else {
    console.log(
      '[T-8] 日志旁证未取（未设 TASK260901_BACKEND_LOG）。' +
      'AC-8 已于 2026-09-01 改为 DB 主判据，日志仅为可选第二路，故**不影响本条结论**。'
    );
  }
  await archiveShot(page, 'T-8-after-recompute');
});
