/**
 * 🔧 量具探针（**不是 AC 用例**，不进追溯矩阵）
 *
 * 用途：T-A2（AC-21 阳性对照）在报价侧卡片里等 `.qt-cost-table` 等了 15s 超时。
 * 按 `testing.md §4`：**timeout 先怀疑选择器，再怀疑产品**。
 * 本探针只 dump 报价侧/核价侧卡片内真实的表格类名与行数，用来把
 * 「量具错了」和「产品坏了」分开 —— 🚫 不读 `src/`，只看运行时 DOM。
 */
import { test, expect } from '@playwright/test';
import { loginAs } from './fixtures/auth';
import { ADMIN, gotoStep2, switchToQuote, switchToCosting, cardOf } from './fixtures/task260909tree';

test('PROBE：报价侧 / 核价侧 产品卡片内的表格结构', async ({ page }) => {
  test.setTimeout(120_000);
  await loginAs(page, ADMIN.username, ADMIN.password);
  await gotoStep2(page);

  for (const side of ['quote', 'costing'] as const) {
    if (side === 'quote') await switchToQuote(page); else await switchToCosting(page);
    const card = await cardOf(page, 'S0001');

    const tabs = (await card.locator('button.qt-tab-btn').allInnerTexts()).map((s) => s.trim());
    console.log(`\n══════ ${side} ══════`);
    console.log('  qt-tab-btn =', JSON.stringify(tabs));

    for (const tab of tabs.slice(0, 6)) {
      const btn = card.locator('button.qt-tab-btn').filter({ hasText: new RegExp(`^\\s*${tab}\\s*$`) }).first();
      if (!(await btn.count())) continue;
      await btn.click().catch(() => {});
      await page.waitForTimeout(1800);
      const dump = await card.evaluate((el) => {
        const out: any[] = [];
        el.querySelectorAll('table').forEach((t) => {
          out.push({
            cls: t.className || '(无 class)',
            parentCls: (t.parentElement?.className || '').slice(0, 80),
            thead: Array.from(t.querySelectorAll('thead th')).map((h) => (h.textContent || '').trim()).slice(0, 6),
            tbodyRows: t.querySelectorAll('tbody tr').length,
            inputs: t.querySelectorAll('input').length,
          });
        });
        return out;
      });
      console.log(`  [${tab}] tables=${dump.length}`);
      dump.forEach((d: any, i: number) =>
        console.log(`     table[${i}] cls="${d.cls}" rows=${d.tbodyRows} inputs=${d.inputs} th=${JSON.stringify(d.thead)}`));
    }
  }
  expect(true).toBe(true);
});
