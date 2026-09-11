/** task-260911 · S2 片 · **量具勘察**（不做 AC 断言，只把 UI 结构打出来） */
import { test, expect } from '@playwright/test';
import { gotoStep2, dumpAllCards, CASES } from './t0911s2.helpers';

test('PROBE · T0911S2-MAIN 五个明细行的卡片结构', async ({ page }) => {
  test.setTimeout(600_000);
  await gotoStep2(page, CASES['MAIN'], 5);
  const dump = await dumpAllCards(page, '产品');
  console.log(JSON.stringify(dump, null, 1));
  expect(dump.length, '卡片数应为 5').toBe(5);
});
