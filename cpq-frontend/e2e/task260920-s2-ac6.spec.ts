/** task-260920 S-2 · AC-6（R1 报批范围内的「重算预算」1 次）：界面工具栏「重算预算」只对「预算失败」行可用（探针实测，既有设计），
 *  故经同一既有入口的接口 POST …/{id}/recompute-budget（api.md §4）对 AC-5② 料号重算一次，等回到 READY 后与 V1 逐位比。 */
import { test, expect } from '@playwright/test';
import { apiLogin, sql, review, serialRecompute, evid } from './task260920-s2.helpers';
test('AC-6 · 点击即算结果 = 既有「重算」入口结果（逐位）', async ({ page }) => {
  test.setTimeout(180_000);
  const ver = process.env.S2_V1_ID!; const mat = 'PERFHOT-B00291';
  expect(ver).toBeTruthy();
  await apiLogin(page);
  const v1 = review(ver, mat)[0];
  expect(v1.budget_status).toBe('READY'); expect(v1.qa, 'V1 非空').not.toBeNull();
  const cols1 = sql(`select column_id, quote_current::text qc, quote_adjusted::text qa, costing_adjusted::text ca, diff_adjusted::text da, status, missing_side, created_at::text from material_price_review_column where review_id='${v1.id}' order by column_id`);
  const res = await serialRecompute(page, ver, [mat]);
  const cols2 = sql(`select column_id, quote_current::text qc, quote_adjusted::text qa, costing_adjusted::text ca, diff_adjusted::text da, status, missing_side, created_at::text from material_price_review_column where review_id='${v1.id}' order by column_id`);
  const strip = (c: any[]) => c.map(({ created_at, ...x }) => x);
  const out = { mat, reviewId: v1.id, before: { review: v1, cols: cols1 }, recompute: res, after: { review: review(ver, mat)[0], cols: cols2 } };
  evid('AC-6-结果.json', out);
  expect(res[0].http, 'recompute-budget 受理').toBeLessThan(300);
  expect(res[0].fresh, '确实重算过（审核行更新时间晚于发起）').toBe(true);
  expect(res[0].budget_status).toBe('READY');
  expect(strip(cols2), 'V1 与 V2 的 quote_adjusted / costing_adjusted / diff_adjusted 逐位相同').toEqual(strip(cols1));
});
