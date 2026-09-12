import { test } from '@playwright/test';
import { login, openStep2, switchView, cardOf, clickTab, dumpTable, colIdx } from './repair260911-helpers';
test('probe5: 00081 行「组成用量」单元格 DOM（只读，不改值）', async ({ page }) => {
  await login(page);
  await openStep2(page);
  await switchView(page, '核价单');
  const card = cardOf(page);
  await clickTab(card, 'BOM');
  const d = await dumpTable(card);
  const pi = colIdx(d, '料号'); const qi = colIdx(d, '组成用量');
  const rIdx = d.rows.findIndex((r) => r[pi] === '00081');
  console.log('row index of 00081 =', rIdx, ' 组成用量 col =', qi);
  const tr = card.locator('table tbody tr').nth(rIdx);
  const td = tr.locator('td').nth(qi);
  console.log('TD OUTER HTML:', (await td.evaluate((el) => el.outerHTML)).slice(0, 1500));
  console.log('TR class:', await tr.getAttribute('class'));
  // 单击后是否出现输入框？（点击不等于改值，不写库）
  await td.click();
  await page.waitForTimeout(1200);
  console.log('AFTER CLICK TD OUTER HTML:', (await td.evaluate((el) => el.outerHTML)).slice(0, 1500));
  console.log('inputs in td after click:', await td.locator('input').count());
  // 双击
  await td.dblclick();
  await page.waitForTimeout(1200);
  console.log('AFTER DBLCLICK:', (await td.evaluate((el) => el.outerHTML)).slice(0, 1500));
  console.log('inputs after dblclick:', await td.locator('input').count());
  console.log('page total inputs:', await page.locator('input').count());
  // 保存按钮文案侦察（antd 两字按钮会渲染成「保 存」）
  console.log('buttons on page:', JSON.stringify((await page.locator('button').allInnerTexts()).map((s) => s.replace(/\s+/g, '')).filter(Boolean).slice(0, 40)));
});
