/**
 * E2E · task-260825 报价单大单量前端分页 —— 写路径下标纪律专项（AP-54 复发风险）
 *
 * 不直接对应单一 AC 编号，是 test.md §3 陷阱 #6 要求补的专项用例，
 * 属于 fronttask.md F-1「下标纪律」的护栏，间接支撑 AC-1/AC-2（分页切片正确性）
 * 与 AC-23（卡片内计算不受影响）——如果写路径下标算错，AC-23 的"逐字段相等"会被
 * 错误地判定为通过（因为改的是别的卡片，本卡片确实没变），必须单独测写目标本身。
 *
 * 背景（需求文档/反模式 AP-54）：QuotationStep2.tsx 曾因"渲染用过滤子集下标、
 * 写路径用原集合下标"而写错行。分页引入了另一层子集（当前页 vs 全量），
 * 同样的错配模式有复发风险：翻到第 3 页编辑一个单元格，如果写路径下标算成了
 * "全局下标 = 页内下标"而不是"全局下标 = (page-1)*pageSize + 页内下标"，
 * 写入会错落到第 3 页的第 1 行（对应 pageSize=100 时全局下标错位 200）。
 *
 * 🚨 写操作红线：编辑会触发 PUT .../quote-card-edit，本用例用 page.route 拦截该请求、
 *   只读 URL 与 body 用于断言写目标身份，然后 mock 响应短路，不放行到真实后端。
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
  switchPageSize,
  cardByPartNo,
  queryOrderedLineItems,
  queryLineItemCount,
} from './fixtures/task260825-paging';

const __filename = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filename);
const SHOT_DIR = path.join(__dirnameLocal, 'screenshots', 'task260825');
fs.mkdirSync(SHOT_DIR, { recursive: true });
let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `writeback-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: false }).catch(() => {});
  return file;
}

let backendUp = false;
let expectedOrder: ReturnType<typeof queryOrderedLineItems> = [];
test.beforeAll(async () => {
  backendUp = await isBackendUp();
  if (backendUp) {
    expect(queryLineItemCount(LARGE_QUOTATION_ID), '固定样本应仍为 1845 行').toBe(1845);
    expectedOrder = queryOrderedLineItems(LARGE_QUOTATION_ID);
    expect(expectedOrder.length, '只读 SQL 取数应非空').toBe(1845);
  }
});

async function jumpToPage(page: Page, n: number) {
  const jumper = page.locator('.ant-pagination-options-quick-jumper input').first();
  if (await jumper.count() > 0) {
    await jumper.fill(String(n));
    await jumper.press('Enter');
    await page.waitForTimeout(1000);
    return;
  }
  for (let i = 1; i < n; i++) {
    await page.locator('.ant-pagination-next').first().click();
    await page.waitForTimeout(400);
  }
}

test.describe('AP-54 复发风险：第 3 页编辑单元格，写回目标必须是该行本身', () => {
  test('翻到第 3 页，编辑第 6 行（页内 index=5，全局 index=205）的单元格，拦截 quote-card-edit 请求，断言 lineItemId 命中该行而非第 3 页首行', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');

    // 页大小已在 openEditStep2 之后显式切到 100（2026-08-28 默认值改为 10，不能再依赖默认）：第 3 页 = 全局下标 [200, 299]
    const decoyRow = expectedOrder[200]; // 第 3 页首行（AP-54 若复发，写入会错落到这里）
    const targetRow = expectedOrder[205]; // 第 3 页第 6 行（本次实际要编辑的行）
    expect(decoyRow.id, '诱饵行(第3页首行) id 应存在').toBeTruthy();
    expect(targetRow.id, '目标行(第3页第6行) id 应存在').toBeTruthy();
    expect(decoyRow.id).not.toBe(targetRow.id);
    console.log(`[writeback] 诱饵行(第3页首行) partNo=${decoyRow.productPartNo} id=${decoyRow.id}`);
    console.log(`[writeback] 目标行(第3页第6行) partNo=${targetRow.productPartNo} id=${targetRow.id}`);

    let interceptedUrl: string | null = null;
    let interceptedLineItemId: string | null = null;
    await page.route('**/api/cpq/quotations/line-items/*/quote-card-edit', async (route) => {
      const req = route.request();
      if (req.method() === 'PUT') {
        interceptedUrl = req.url();
        const m = interceptedUrl.match(/line-items\/([^/]+)\/quote-card-edit/);
        interceptedLineItemId = m ? m[1] : null;
        // 🚨 红线：不放行到真实后端，mock 短路
        await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 200, data: {} }) });
        return;
      }
      await route.continue();
    });

    await loginAdmin(page);
    await openEditStep2(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(1200);
    // 2026-08-28 默认页大小改为 10，本用例的诱饵行/目标行取自 SQL 全局下标 200/205
    //（"第 3 页 = 全局第 200~299 条"），必须显式切到 100 条/页才成立。
    await switchPageSize(page, 100);
    await jumpToPage(page, 3);
    await page.waitForTimeout(1000);
    await shot(page, 'page3-before-edit');

    const targetCard = await cardByPartNo(page, targetRow.productPartNo);
    const decoyCard = await cardByPartNo(page, decoyRow.productPartNo);
    const decoyTextBefore = await decoyCard.innerText();

    // 在目标卡内找一个可编辑的输入框（不限定字段名，取首个可见的 number/text input）
    const editableInput = targetCard.locator('input[type="number"]:visible, input[type="text"]:visible').first();
    const hasEditable = await editableInput.count() > 0;
    test.skip(!hasEditable, '目标卡当前 Tab 未找到可编辑输入框，需要主线确认默认 Tab 是否含可编辑字段');

    const originalValue = await editableInput.inputValue().catch(() => '');
    const probeValue = '123.456789'; // 明显区别于任何真实业务值的探针值
    console.log(`[writeback] 目标行原值="${originalValue}"，写入探针值="${probeValue}"`);

    await editableInput.fill(probeValue);
    await editableInput.blur();
    await page.waitForTimeout(1000);
    await shot(page, 'page3-after-edit');

    console.log(`[writeback] 拦截到的请求URL=${interceptedUrl}`);
    console.log(`[writeback] 拦截到的 lineItemId=${interceptedLineItemId}`);

    if (interceptedLineItemId === null) {
      console.warn('[writeback] 未拦截到 quote-card-edit 请求 —— 该字段可能走的是别的保存路径（如整单 saveDraft 而非单字段 API），需要主线确认保存机制');
      test.skip(true, '未捕获到 quote-card-edit 请求，无法判定写目标身份');
    }

    expect(interceptedLineItemId, 'AP-54 护栏：写请求的 lineItemId 必须命中被编辑的目标行').toBe(targetRow.id);
    expect(interceptedLineItemId, 'AP-54 护栏（反向）：写请求不应错落到第 3 页首行（诱饵行）').not.toBe(decoyRow.id);

    // 诱饵卡片文本不应因为编辑目标卡而发生变化（前端本地状态也不应串行）
    const decoyTextAfter = await decoyCard.innerText();
    expect(decoyTextAfter, 'AP-54 护栏：诱饵行（第3页首行）的卡片内容不应被本次编辑影响').toBe(decoyTextBefore);
  });
});
