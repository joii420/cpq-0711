import { test, expect, Page, Locator } from '@playwright/test';

const QID = '56bb60e2-3718-4dc0-97cf-2c340ec45fb4';
const PART = 'S0001';
const TAB_B = 'T260909FT-BASIC';
const TAB_I = 'T260909FT-INPUT';

async function uiLogin(page: Page) {
  await page.context().clearCookies();
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products|components|change-password)/, { timeout: 30_000 });
  if (page.url().includes('/change-password')) { await page.goto('/dashboard'); await page.waitForLoadState('networkidle'); }
}

async function enterCosting(page: Page) {
  await page.goto(`/quotations/${QID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2500);
  const nextBtn = page.getByRole('button', { name: /下一步|继续/ }).first();
  if (await nextBtn.count() > 0 && await nextBtn.isEnabled().catch(() => false)) {
    await nextBtn.click().catch(() => {});
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2500);
  }
  const costing = page.locator('.ant-segmented-item').filter({ hasText: /核价单/ }).first();
  if (await costing.count() === 0) {
    const segs = await page.locator('.ant-segmented-item').allInnerTexts().catch(() => []);
    throw new Error('找不到「核价单」切换项。实际 segmented=' + JSON.stringify(segs));
  }
  await costing.click();
  await page.waitForTimeout(1800);
  const cardSeg = page.locator('.ant-segmented-item').filter({ hasText: /产品卡片/ }).first();
  if (await cardSeg.count() > 0) { await cardSeg.click().catch(() => {}); await page.waitForTimeout(1500); }
  await page.waitForTimeout(2500);
}

async function cardOf(page: Page, part: string): Promise<Locator> {
  const cards = page.locator('.qt-product-card');
  const hit = cards.filter({ hasText: part });
  const n = await hit.count();
  const total = await cards.count();
  if (n !== 1) {
    const titles: string[] = [];
    for (let i = 0; i < total; i++) titles.push((await cards.nth(i).innerText().catch(() => '')).split('\n').slice(0, 2).join(' / '));
    throw new Error(`料号 ${part} 命中 ${n} 张卡片（期望 1），总卡片=${total}\n${titles.join('\n')}`);
  }
  await hit.first().scrollIntoViewIfNeeded();
  await page.waitForTimeout(400);
  return hit.first();
}

/** 读某页签的可见矩阵：表头 + 每行每格的「可见文本或 input 值」，并统计 input 数量 */
async function readTab(card: Locator, tabName: string) {
  const page = card.page();
  const btn = card.locator('button.qt-tab-btn').filter({ hasText: new RegExp(`^\\s*${tabName}\\s*$`) }).first();
  if (!(await btn.isVisible().catch(() => false))) {
    const tabs = await card.locator('button.qt-tab-btn').allInnerTexts().catch(() => []);
    throw new Error(`卡片内找不到页签「${tabName}」。实际=${JSON.stringify(tabs.map((t) => t.trim()))}`);
  }
  await btn.click();
  await page.waitForTimeout(3000);
  const tables = card.locator('.qt-cost-table');
  const tCount = await tables.count();
  const headers: string[] = [];
  const matrix: string[][] = [];
  let inputCount = 0;
  for (let t = 0; t < tCount; t++) {
    const ths = await tables.nth(t).locator('thead th').allInnerTexts().catch(() => []);
    headers.push(...ths.map((s) => s.replace(/\s+/g, '')));
    const rows = tables.nth(t).locator('tbody tr');
    const rc = await rows.count();
    for (let i = 0; i < rc; i++) {
      const cells = rows.nth(i).locator('td');
      const cc = await cells.count();
      const vals: string[] = [];
      for (let j = 0; j < cc; j++) {
        const cell = cells.nth(j);
        const inp = cell.locator('input, textarea');
        if (await inp.count() > 0) {
          inputCount += await inp.count();
          vals.push(String(await inp.first().inputValue().catch(() => '')));
        } else {
          vals.push((await cell.innerText().catch(() => '')).replace(/\s+/g, ' ').trim());
        }
      }
      if (!matrix[i]) matrix[i] = [];
      matrix[i].push(...vals);
    }
  }
  return { headers, matrix, inputCount, tableCount: tCount };
}

test('F-6 / AC-6+AC-7：同源双组件（BASIC_DATA vs INPUT_*）逐行逐列可见值对照', async ({ page }) => {
  test.setTimeout(300_000);
  await uiLogin(page);
  await enterCosting(page);
  const card = await cardOf(page, PART);
  const tabs = (await card.locator('button.qt-tab-btn').allInnerTexts()).map((t) => t.trim());
  console.log('[FT6] 卡片页签 =', JSON.stringify(tabs));

  const B = await readTab(card, TAB_B);
  const I = await readTab(card, TAB_I);
  await card.screenshot({ path: 'e2e/screenshots/ft6-INPUT-tab.png' }).catch(() => {});
  await readTab(card, TAB_B);
  await card.screenshot({ path: 'e2e/screenshots/ft6-BASIC-tab.png' }).catch(() => {});

  console.log('[FT6] BASIC 表头 =', JSON.stringify(B.headers));
  console.log('[FT6] INPUT 表头 =', JSON.stringify(I.headers));
  console.log(`[FT6] BASIC: 行=${B.matrix.length} 表数=${B.tableCount} input数=${B.inputCount}`);
  console.log(`[FT6] INPUT: 行=${I.matrix.length} 表数=${I.tableCount} input数=${I.inputCount}`);
  B.matrix.forEach((r, i) => console.log(`[FT6] BASIC #${i} ${JSON.stringify(r)}`));
  I.matrix.forEach((r, i) => console.log(`[FT6] INPUT #${i} ${JSON.stringify(r)}`));

  // AC-6：行数相同 + 每行每列可见文本相同
  expect(B.matrix.length, 'BASIC/INPUT 行数不一致').toBe(I.matrix.length);
  expect(B.matrix.length, 'BASIC 页签 0 行 —— 空结果不算通过').toBeGreaterThan(0);

  // ① 先把「严格逐字」的差异全打出来（不吞，供主线判断），再做规范化断言。
  //    已知且**与本次改动无关**的一类差异：值为空时两条分支的**空占位形态**不同 ——
  //    只读分支渲染 '—'，输入分支渲染一个空 <input>（value=''）。
  //    它是 ComponentCell 两条分支固有的表现差异（本任务明令不许动 ComponentCell）。
  const strictDiffs: string[] = [];
  for (let i = 0; i < B.matrix.length; i++) {
    for (let j = 0; j < Math.max(B.matrix[i].length, I.matrix[i].length); j++) {
      if (B.matrix[i][j] !== I.matrix[i][j]) {
        strictDiffs.push(`行${i} 列${j}(${B.headers[j] ?? '?'}): BASIC=${JSON.stringify(B.matrix[i][j])} INPUT=${JSON.stringify(I.matrix[i][j])}`);
      }
    }
  }
  console.log(`[FT6] 严格逐字差异 ${strictDiffs.length} 处：`);
  strictDiffs.forEach((d) => console.log('[FT6]   ' + d));

  const norm = (v: string) => (v === '—' || v === '' ? '<空>' : v);
  // ② 非空值必须逐字相同；空值只要两边都空即可
  let nonEmptyCompared = 0;
  for (let i = 0; i < B.matrix.length; i++) {
    for (let j = 0; j < B.matrix[i].length; j++) {
      const b = norm(B.matrix[i][j]); const ii = norm(I.matrix[i][j]);
      if (b !== '<空>') nonEmptyCompared++;
      expect(b, `第 ${i} 行第 ${j} 列（${B.headers[j] ?? '?'}）不一致`).toBe(ii);
    }
  }
  // 🚨 防空验证：若可比的非空格子为 0，这条"一致"就是拿两片空白互相印证
  expect(nonEmptyCompared, '可比的非空单元格为 0 —— 空对空不算通过').toBeGreaterThan(10);
  console.log(`[FT6] 非空可比单元格数 = ${nonEmptyCompared}`);
  // AC-7：BASIC 页签无 input；INPUT 页签有 input（阳性对照）
  expect(B.inputCount, 'BASIC_DATA 页签不应有 <input>').toBe(0);
  expect(I.inputCount, 'INPUT_* 页签必须有 <input>（阳性对照）').toBeGreaterThan(0);
});
