/**
 * E2E · task-260901 —— E. 性能（需求文档 §③ E / AC-18）
 *
 * AC 原文：「在基准单上执行 AC-1 的完整操作，从点击『保存草稿』到页面可继续操作
 *           （保存请求返回 **且** 其触发的卡片值重算完成），端到端 ≤ 10 秒。测 3 次取最大值。」
 *           现状实测 97.6 秒（43.2 + 54.4）。
 *
 * 🚨 执行前提（test.md §0，来自 证据/E4-链路基线.md）：**确认无其他大请求在跑**。
 *    实测 4 个并发大请求期间，同一个 0 行空单的响应从 0.18 s 劣化到 9.8 s、ping 从 32 ms 涨到 878 ms；
 *    同码同数据曾测出 16 s 与 43 s 两种结果。**不满足该前提时测得的数字一律作废** ——
 *    所以本用例开跑前先做一次链路探针，探不通就直接失败，绝不让一个被污染的数字进报告。
 *
 * 🚫 不 import src/。
 */
import { test, expect } from '@playwright/test';
import { execSync } from 'child_process';
import {
  BASELINE_QUOTATION_ID, EMPTY_QUOTATION_ID,
  AC1_TAB_NAME, AC1_ROW_INDEX, AC1_COLUMN_NAME, AC1_TARGET_VALUE,
  assertBaselineShape, loginAdmin, openEditStep2, cardByPartNo, openCardTab,
  editCell, readCellInputValue, distinctFrom, saveDraftCapture,
  queryLineAt, psqlScalar, apiGetQuotation, archiveJson,
  DB_HOST, DB_NAME, DB_USER, DB_PASSWORD,
  waitBackendUp,
} from './fixtures/task260901-draft';

const AC18_BUDGET_MS = 10_000;
const ROUNDS = 3;

let backendUp = false;
test.beforeAll(async () => {
  backendUp = await waitBackendUp();
  if (backendUp) assertBaselineShape();
});

/**
 * 链路探针：确认此刻没有并发大请求把环境拖垮。两条判据：
 *   ① 库侧：pg_stat_activity 里本库 active 的非本连接查询数
 *   ② 应用侧：GET 一张 0 行空单的耗时（E4 基线：干净时 0.18 s，被拖垮时 9.8 s）
 */
async function assertQuietEnvironment(page: any, label: string) {
  const activeQueries = execSync(
    `PGPASSWORD=${DB_PASSWORD} psql -h ${DB_HOST} -U ${DB_USER} -d ${DB_NAME} -t -A -c ` +
    `"SELECT count(*) FROM pg_stat_activity WHERE datname='${DB_NAME}' AND state='active' AND pid<>pg_backend_pid();"`,
    { encoding: 'utf-8', shell: '/bin/bash' }
  ).trim();

  const t0 = Date.now();
  await apiGetQuotation(page, EMPTY_QUOTATION_ID);
  const probeMs = Date.now() - t0;

  console.log(`[T-18][${label}] 环境探针：库内 active 查询=${activeQueries}，0 行空单 GET=${probeMs} ms`);
  expect(probeMs,
    `AC-18 前提不满足：0 行空单 GET 耗时 ${probeMs} ms（E4 干净基线 ~180 ms，被并发大请求拖垮时 ~9800 ms）。` +
    '此刻测得的性能数字一律作废 —— 请等其它大请求跑完再重测。'
  ).toBeLessThan(3000);
  return { activeQueries: Number(activeQueries), probeMs };
}

test('T-18 改一个格子的保存端到端 ≤ 10 秒（3 次取最大值）', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(1_800_000);

  await loginAdmin(page);
  const target = queryLineAt(BASELINE_QUOTATION_ID, 0);
  const rounds: any[] = [];

  for (let i = 1; i <= ROUNDS; i++) {
    const env = await assertQuietEnvironment(page, `round-${i}-pre`);

    await openEditStep2(page, BASELINE_QUOTATION_ID);
    const card = await cardByPartNo(page, target.partNo);
    await openCardTab(page, card, AC1_TAB_NAME);
    const cur = await readCellInputValue(card, AC1_ROW_INDEX, AC1_COLUMN_NAME);
    const val = distinctFrom(cur, AC1_TARGET_VALUE);
    expect(val, '每轮都必须改成一个不同的值，否则命中 AC-5「无改动不发请求」').not.toBe(cur);
    await editCell(page, card, AC1_ROW_INDEX, AC1_COLUMN_NAME, val);

    // ── 计时开始：点击 → PUT 返回 ──
    const t0 = Date.now();
    const cap = await saveDraftCapture(page, 200);
    const putMs = Date.now() - t0;

    // ── 继续等到"其触发的卡片值重算完成"：目标行 quote_card_values 由 NULL 回填为非 NULL ──
    let recomputeDoneMs = -1;
    const deadline = Date.now() + 300_000;
    while (Date.now() < deadline) {
      const n = psqlScalar(
        `SELECT count(*) FROM quotation_line_item WHERE id='${target.id}' AND quote_card_values IS NOT NULL;`
      );
      if (n === '1') { recomputeDoneMs = Date.now() - t0; break; }
      await page.waitForTimeout(250);
    }
    expect(recomputeDoneMs, 'AC-18：卡片值重算必须在 300 s 内完成，否则端到端无从计时').toBeGreaterThan(0);

    const row = {
      round: i, putMs, endToEndMs: recomputeDoneMs,
      requestBytes: cap.requestBytes, responseBytes: cap.responseBytes,
      envActiveQueries: env.activeQueries, envProbeMs: env.probeMs,
      // 便于超标时定位：后端 [draft-profile] 的 S1/S2/S3 需人工从日志取（AC 未要求接口回传）
    };
    rounds.push(row);
    console.log(`[T-18] 第 ${i} 轮：PUT=${putMs} ms，端到端=${recomputeDoneMs} ms，请求体=${cap.requestBytes} B，响应体=${cap.responseBytes} B`);

    // 轮次间留一点间隔，避免上一轮的后台任务污染下一轮的探针
    await page.waitForTimeout(5000);
  }

  const worst = Math.max(...rounds.map((r) => r.endToEndMs));
  archiveJson('T-18-perf-rounds', { budgetMs: AC18_BUDGET_MS, rounds, worstMs: worst });
  console.log(`[T-18] 三轮端到端 = ${rounds.map((r) => r.endToEndMs).join(' / ')} ms，取最大值 ${worst} ms`);

  expect(rounds.length, 'AC-18：必须测满 3 轮').toBe(ROUNDS);
  expect(worst,
    `AC-18：端到端（保存 + 其触发的卡片值重算）取 3 次最大值必须 ≤ ${AC18_BUDGET_MS} ms，实际 ${worst} ms（现状基线 97600 ms）`
  ).toBeLessThanOrEqual(AC18_BUDGET_MS);
});
