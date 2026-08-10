/**
 * repair-0808 主线亲验（AC-1 / AC-2）：直接打开报障单 QT-20260807-0146 的编辑页，
 * 切到「物料」页签，读小计行的「材料成本 / 材料损耗成本」两格与页脚「产品小计」。
 *
 * 为什么不复用 quotation-flow.spec.ts：那个 spec 硬编码的客户/模板在库里已不存在（BL-0158），
 * 卡在 Step1 根本进不到 Step2。本脚本走 URL 直达既有单据，绕开夹具漂移。
 *
 * ⚠️ 本 spec 同样硬编码单据 id —— 这是**刻意的**：按 BL-0157/BL-0158 的教训，
 * 「护栏的价值全在夹具没了要吵」，所以单据被删时它应当**硬失败**，而不是静默跳过。
 * 逻辑层的等价护栏是 `src/pages/quotation/crossTabOrderParityQt0146.repair0808.test.ts`
 * （夹具已固化进仓库，不随库变动），两者互补：那条守算法，这条守真实浏览器渲染。
 */
import { test, expect, Page } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';

const QUOTATION_ID = '6d014a9a-fe27-432a-bce9-7f6c86c50775'; // QT-20260807-0146

test('repair-0808 AC-1/AC-2：物料页签小计行非 0，产品小计回到 137.53 量级', async ({ page }: { page: Page }) => {
  test.setTimeout(180_000);
  const consoleErrors: string[] = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });

  await loginAsAdmin(page);
  await page.goto(`/quotations/${QUOTATION_ID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);
  await page.screenshot({ path: 'e2e/screenshots/r0808-01-open.png', fullPage: true });

  // 若落在 Step1，点「下一步」进 Step2（编辑态客户/模板已预填）
  const next = page.getByRole('button', { name: /下一步/ }).first();
  if (await next.count() > 0 && await next.isEnabled().catch(() => false)) {
    await next.click();
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(3000);
  }

  // 切到「物料」页签
  const wuliaoTab = page.locator('button.qt-tab-btn').filter({ hasText: /^物料$/ }).first();
  await expect(wuliaoTab, '找不到「物料」页签按钮').toHaveCount(1);
  await wuliaoTab.click();
  await page.waitForTimeout(3000);
  await page.screenshot({ path: 'e2e/screenshots/r0808-02-wuliao.png', fullPage: true });

  // 小计行
  const subtotalRow = page.locator('tr.qt-subtotal-row').first();
  await expect(subtotalRow, '找不到小计行').toHaveCount(1);
  const cells = await subtotalRow.locator('td').allInnerTexts();
  console.log('[小计行]', JSON.stringify(cells));

  const amounts = cells.filter((t) => t.includes('¥'));
  console.log('[小计行金额格]', JSON.stringify(amounts));

  // 产品小计
  const prodSub = page.locator('text=产品小计').first();
  const prodSubText = await prodSub.locator('xpath=ancestor::*[1]').innerText().catch(() => '');
  console.log('[产品小计块]', JSON.stringify(prodSubText));

  console.log('[console.error 数量]', consoleErrors.length);
  for (const e of consoleErrors.slice(0, 10)) console.log('[console.error]', e);

  // 断言：不再有 ¥ 0；出现 131.9 / 5.52；产品小计 137.53 量级
  expect(amounts.some((t) => /131\.90/.test(t)), `材料成本小计未出现 131.90，实际 ${JSON.stringify(amounts)}`).toBe(true);
  expect(amounts.some((t) => /5\.5256/.test(t)), `材料损耗成本小计未出现 5.5256，实际 ${JSON.stringify(amounts)}`).toBe(true);
  expect(/137\.53/.test(prodSubText), `产品小计不是 137.53 量级，实际 ${JSON.stringify(prodSubText)}`).toBe(true);
  expect(consoleErrors.filter((e) => e.includes('组件依赖成环')).length, '不应出现假环 console.error').toBe(0);
});
