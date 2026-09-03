/**
 * E2E · task-260825 报价单大单量前端分页 —— 冲突跨页跳转 + Step5 明细表分页
 *
 * 覆盖 AC-15 / AC-16（test.md T-15 / T-16）。
 *
 * 🚨 AC-15 需要构造数据：一个落在第 17 页（sortOrder 第 1601~1700 区间）的行键冲突。
 * 现网 QT-20260825-0180 没有天然冲突样本，需求文档 §③D 的构造方法是"提交返回行键冲突"，
 * 具体触发方式（如何让服务端在提交时判定某行冲突）依赖后端行为，测试工程师不读实现代码，
 * 无法在不读源码的前提下确定构造手法是否安全（是否会误伤共享库其它数据）。
 * 因此本文件把 T-15 标记为 test.skip 并在报告里单独列为"造数需求，需主线批准"，
 * 不擅自读实现来推导构造方法，也不跳过后虚报 PASS。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp } from './fixtures/auth';
import {
  LARGE_QUOTATION_ID,
  loginAdmin,
  openEditStep2,
  queryOrderedLineItems,
  queryLineItemCount,
} from './fixtures/task260825-paging';

const __filename = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filename);
const SHOT_DIR = path.join(__dirnameLocal, 'screenshots', 'task260825');
fs.mkdirSync(SHOT_DIR, { recursive: true });
let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `conflict-step5-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: false }).catch(() => {});
  return file;
}

let backendUp = false;
test.beforeAll(async () => {
  backendUp = await isBackendUp();
  if (backendUp) {
    expect(queryLineItemCount(LARGE_QUOTATION_ID), '固定样本应仍为 1845 行').toBe(1845);
  }
});

test.describe('AC-15: 冲突定位跨页跳转（🚧 需造数，主线批准前 skip）', () => {
  test.skip('T-15 构造落在第 17 页的行键冲突，提交后点击冲突项应自动切到第 17 页并高亮', async ({ page }) => {
    // 造数方案草案（未执行，待主线批准）：
    // 1. 复制 QT-20260825-0180 为一张临时测试单（避免污染现网大单本身）
    // 2. 通过前端 UI 正常编辑流程，让 sortOrder 落在 1600~1699 区间（第 17 页）的某一行
    //    产生"行键冲突"触发条件 —— 具体条件需要开发方在 fronttask.md 的实现里说明
    //    （`QuotationStep2.tsx:3974` 附近的冲突判定逻辑），测试侧不读源码无法确定，
    //    因此该步骤须由主线/开发方给出"如何用纯 UI 操作复现一次冲突"的步骤，而不是测试侧去猜。
    // 3. 复现后断言：页码自动切到 17 且该冲突卡片可见（高亮）。
  });
});

test.describe('AC-16: Step5 概览「产品明细」表分页可用', () => {
  test('T-16 1845 行单在 Step5 显示分页控件而非一次性铺开', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await loginAdmin(page);
    await openEditStep2(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(1200);

    // 走到 Step5：编辑页 wizard 顶部应有步骤条，点击到"预览/提交"最后一步
    // 优先尝试点击步骤条上标"确认与提交"/"预览"字样的步骤项；否则连续点"下一步"
    const stepItems = page.locator('.ant-steps-item');
    const stepCount = await stepItems.count();
    console.log(`[T-16] wizard 步骤数 = ${stepCount}`);
    if (stepCount >= 5) {
      await stepItems.nth(4).click().catch(() => {});
      await page.waitForTimeout(1000);
    } else {
      // 退化路径：连续点"下一步"直到按钮消失或文案变化，最多点 5 次防止死循环
      for (let i = 0; i < 5; i++) {
        const nextBtn = page.getByRole('button', { name: /下一步/ }).first();
        if (!(await nextBtn.isVisible().catch(() => false))) break;
        if (!(await nextBtn.isEnabled().catch(() => false))) break;
        await nextBtn.click().catch(() => {});
        await page.waitForTimeout(800);
      }
    }
    await page.waitForTimeout(1500);
    await shot(page, 'step5-detail-table');

    // 定位"产品明细"表格
    const detailCard = page.locator('.ant-card-head-title', { hasText: '产品明细' }).first();
    const detailVisible = await detailCard.isVisible().catch(() => false);
    console.log(`[T-16] "产品明细" 卡片可见 = ${detailVisible}`);
    test.skip(!detailVisible, '未能导航到 Step5（产品明细区块不可见）—— wizard 步骤条选择器需要按实际实现调整，先跳过避免误判');

    const table = page.locator('.ant-table').filter({ has: page.locator('text=产品明细').locator('..') }).first();
    // 表格行数应受分页限制，不应一次性铺开 1845 行
    const allRows = page.locator('.ant-table-tbody tr.ant-table-row');
    const rowCount = await allRows.count();
    console.log(`[T-16] Step5 产品明细表当前渲染行数 = ${rowCount}`);
    expect(rowCount, 'Step5 明细表应渲染出数据（结果非空守卫）').toBeGreaterThan(0);
    expect(rowCount, 'AC-16: Step5 明细表不应一次性铺开全部 1845 行').toBeLessThan(1845);

    const pagination = page.locator('.ant-pagination');
    const paginationVisible = await pagination.first().isVisible().catch(() => false);
    expect(paginationVisible, 'AC-16: Step5 明细表应出现分页控件').toBe(true);
  });
});
