/**
 * task-261030 · 亲验辅助：核价金额区元素级截图（test.md「T-10 补充②」）
 *
 * 只产出截图，供主线逐屏比对原型 `01-核价编辑态.html` / `02-核价只读与边界态.html`；不承担 AC 判定
 * （AC 判定在 task261030-costing-subtotal-flow.spec.ts）。每张图前只做「目标存在」的前置断言，防止截到空白。
 * 夹具与拦截同 flow spec（`task261030-costing-subtotal.fixtures.ts`），不连后端、不连任何库。
 *
 * 运行：npx playwright test -c e2e/task261030-costing-subtotal-shots.config.ts
 */
import { expect, test, type Page } from '@playwright/test';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { PART_NO, QUOTATION_ID_BY_VARIANT, installApiMocks, type QuotationVariant } from './task261030-costing-subtotal.fixtures';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const TASK_DIR = path.resolve(HERE, '..', '..', 'dev-docs', 'task-260801-公式计算精度优化', 'task-261030-核价卡片小计展示调整');
const SHOT_DIR = process.env.T261030_SHOT_DIR?.trim()
  || path.join(TASK_DIR, '证据', `shots-${new Date().toISOString().replace(/[:.]/g, '-')}`);

const SCENARIOS: Array<{ name: string; page: 'edit' | 'detail'; variant: QuotationVariant; width: number; height: number; cols: number }> = [
  { name: 'edit-costing-USD-2cols', page: 'edit', variant: 'USD', width: 1280, height: 720, cols: 2 },
  { name: 'edit-costing-CNY-1col', page: 'edit', variant: 'CNY', width: 1280, height: 720, cols: 1 },
  { name: 'detail-costing-USD-2cols', page: 'detail', variant: 'USD', width: 1280, height: 720, cols: 2 },
  { name: 'detail-costing-NO_COSTING_STRUCTURE-dash', page: 'detail', variant: 'NO_COSTING_STRUCTURE', width: 1280, height: 720, cols: 1 },
  { name: 'detail-costing-COSTING_FAILED-dash', page: 'detail', variant: 'COSTING_FAILED', width: 1280, height: 720, cols: 1 },
  { name: 'edit-costing-USD-2cols', page: 'edit', variant: 'USD', width: 390, height: 844, cols: 2 },
  { name: 'detail-costing-USD-2cols', page: 'detail', variant: 'USD', width: 390, height: 844, cols: 2 },
];

async function chooseSegment(page: Page, text: string): Promise<void> {
  const item = page.locator('.ant-segmented-item').filter({ hasText: text }).first();
  await expect(item, `视图切换项「${text}」必须存在`).toBeVisible();
  await item.click();
  await page.waitForLoadState('networkidle');
}

test.beforeAll(() => {
  fs.mkdirSync(SHOT_DIR, { recursive: true });
});

for (const s of SCENARIOS) {
  test(`SHOT ${s.name} ${s.width}`, async ({ page }) => {
    await page.setViewportSize({ width: s.width, height: s.height });
    const ledger = await installApiMocks(page);
    const id = QUOTATION_ID_BY_VARIANT[s.variant];
    await page.goto(s.page === 'edit' ? `/quotations/${id}/edit` : `/quotations/${id}`);
    await page.waitForLoadState('networkidle');
    if (s.page === 'edit' && !(await page.locator('.qt-product-card').first().isVisible().catch(() => false))) {
      await page.getByRole('button', { name: /下一步/ }).first().click();
    }
    await chooseSegment(page, '核价单');
    const card = page.locator('.qt-product-card').filter({ hasText: PART_NO });
    await expect(card, '目标卡片唯一').toHaveCount(1);
    await expect(page.getByText('加载中', { exact: false })).toHaveCount(0, { timeout: 30_000 });
    const costing = card.locator('.qt-subtotal-costing');
    await expect(costing, '核价金额区恰好 1 个').toHaveCount(1);
    await expect(costing.locator('.qt-subtotal-costing-col'), `列数 ${s.cols}`).toHaveCount(s.cols);

    // 「整张目标卡片底部区域」= 包住金额区的外壳（编辑页 .qt-subtotal-bar-multi / 只读页 .qt-subtotal-bar.qt-subtotal-bar-multi）。
    const shell = card.locator('.qt-subtotal-bar-multi').filter({ has: page.locator('.qt-subtotal-costing') }).first();
    await expect(shell, '金额区外壳必须可见').toBeVisible();
    await shell.scrollIntoViewIfNeeded();
    const stem = `${s.name}-${s.width}`;
    await shell.screenshot({ path: path.join(SHOT_DIR, `${stem}-footer.png`) });
    await card.screenshot({ path: path.join(SHOT_DIR, `${stem}-card.png`) });
    await page.screenshot({ path: path.join(SHOT_DIR, `${stem}-viewport.png`) });
    const text = (await shell.innerText()).replace(/\s+/g, ' ').trim();
    // 仅记录（不断言）：页面与卡片的实际宽度，便于判读窄屏截图。
    const geometry = await page.evaluate(() => ({
      docScrollWidth: document.documentElement.scrollWidth,
      docClientWidth: document.documentElement.clientWidth,
    }));
    const cardBox = await card.boundingBox();
    const shellBox = await shell.boundingBox();
    const info = `${text}\n${JSON.stringify({ ...geometry, cardWidth: cardBox?.width, shellWidth: shellBox?.width })}\n`;
    fs.writeFileSync(path.join(SHOT_DIR, `${stem}-footer.txt`), info, 'utf8');
    console.info(`[T261030_COST_][SHOT ${stem}] ${info.replace(/\n/g, ' | ')}`);
    expect(ledger.unhandled, '夹具应答完整').toEqual([]);
  });
}
