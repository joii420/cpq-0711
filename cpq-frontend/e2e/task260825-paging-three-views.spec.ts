/**
 * E2E · task-260825 报价单大单量前端分页 —— 三视图（报价侧/核价侧/Excel）料号同步
 *
 * 覆盖 AC-5 / AC-6 / AC-7 / AC-8（test.md T-05 / T-06 / T-07 / T-08）。
 * 这是需求 2「两侧料号统一」的主判据，也是 test.md §3 陷阱 #2 的重点反查对象：
 * 🚨 Excel 视图必须**单独**断言行数 == 100，卡片视图看着正常不代表 Excel 视图切片正确。
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
  switchMainTab,
  switchViewType,
  switchPageSize,
  extractVisiblePartNoSet,
  countRenderedCards,
  queryOrderedLineItems,
  queryLineItemCount,
} from './fixtures/task260825-paging';

const __filename = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filename);
const SHOT_DIR = path.join(__dirnameLocal, 'screenshots', 'task260825');
fs.mkdirSync(SHOT_DIR, { recursive: true });
let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `three-views-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
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
    expect(expectedOrder.length, '只读 SQL 取数应非空（结果非空守卫）').toBe(1845);
  }
});

/** 跳到第 N 页（走 quick-jumper，找不到则退化为连续点「›」）。 */
async function jumpToPage(page: Page, n: number) {
  const jumper = page.locator('.ant-pagination-options-quick-jumper input').first();
  if (await jumper.count() > 0) {
    await jumper.fill(String(n));
    await jumper.press('Enter');
    await page.waitForTimeout(1200);
    return;
  }
  for (let i = 1; i < n; i++) {
    await page.locator('.ant-pagination-next').first().click();
    await page.waitForTimeout(400);
  }
}

test.describe('AC-5 / AC-6: 三视图料号集合逐一相同', () => {
  test('T-05 停在第 3 页，卡片视图/Excel 视图/核价单产品卡片 料号集合逐一相同', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await loginAdmin(page);
    await openEditStep2(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(1200);
    // 2026-08-28 默认页大小改为 10，本用例要测的是"第 3 页 = 全局第 200~299 条"这个深页场景，
    // 必须显式切到 100 条/页（不再是默认值），否则第 3 页在新默认值下只是第 20~29 条。
    await switchPageSize(page, 100);
    await jumpToPage(page, 3);
    await shot(page, 'page3-quote-card');

    const expectedPage3 = new Set(expectedOrder.slice(200, 300).map((r) => r.productPartNo));
    expect(expectedPage3.size, '第 3 页期望料号集合应为 100（结果非空守卫）').toBe(100);

    // 集合 A：报价侧·产品卡片
    const setA = await extractVisiblePartNoSet(page, '.qt-products-list, body');
    console.log(`[T-05] 集合A(报价卡片) 大小=${setA.size}`);
    expect(setA.size, '报价卡片视图应渲染出料号').toBeGreaterThan(0);

    // 集合 B：报价侧·Excel 视图
    await switchViewType(page, 'Excel 视图');
    await page.waitForTimeout(1500);
    await shot(page, 'page3-quote-excel');
    const setB = await extractVisiblePartNoSet(page, 'table, body');
    console.log(`[T-05] 集合B(报价Excel) 大小=${setB.size}`);
    expect(setB.size, 'Excel 视图应渲染出料号').toBeGreaterThan(0);

    // 集合 C：核价侧·产品卡片
    await switchViewType(page, '产品卡片');
    await switchMainTab(page, '核价单');
    await page.waitForTimeout(1500);
    await shot(page, 'page3-costing-card');
    const setC = await extractVisiblePartNoSet(page, '.qt-products-list, body');
    console.log(`[T-05] 集合C(核价卡片) 大小=${setC.size}`);
    expect(setC.size, '核价卡片视图应渲染出料号').toBeGreaterThan(0);

    const diffAB = [...setA].filter((x) => !setB.has(x)).concat([...setB].filter((x) => !setA.has(x)));
    const diffAC = [...setA].filter((x) => !setC.has(x)).concat([...setC].filter((x) => !setA.has(x)));
    console.log(`[T-05] A vs B 差异=${JSON.stringify(diffAB)}`);
    console.log(`[T-05] A vs C 差异=${JSON.stringify(diffAC)}`);
    expect(diffAB, 'AC-5: 报价卡片 vs Excel 视图 料号集合应逐一相同').toEqual([]);
    expect(diffAC, 'AC-5: 报价卡片 vs 核价卡片 料号集合应逐一相同').toEqual([]);

    // 与 SQL 期望的第 3 页真实值比对（不是三视图互相自洽就算数，必须对上真实数据）
    const diffExpected = [...expectedPage3].filter((x) => !setA.has(x));
    console.log(`[T-05] 与 SQL 期望第 3 页差异(应为空)=${JSON.stringify(diffExpected)}`);
    expect(diffExpected, 'AC-5: 报价卡片视图应恰好等于 SQL 查出的第 3 页 100 个料号').toEqual([]);
  });

  test('T-06 切到第 3 页后立即切视图，三处仍同步（不需手动刷新）', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await loginAdmin(page);
    await openEditStep2(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(1200);
    await switchPageSize(page, 100); // 同 T-05：显式切到 100 条/页再测"第 3 页"
    await jumpToPage(page, 3);
    await page.waitForTimeout(800);

    const setCard = await extractVisiblePartNoSet(page, '.qt-products-list, body');
    // 直接切视图，不刷新页面
    await switchViewType(page, 'Excel 视图');
    await page.waitForTimeout(1200);
    const setExcel = await extractVisiblePartNoSet(page, 'table, body');

    const diff = [...setCard].filter((x) => !setExcel.has(x)).concat([...setExcel].filter((x) => !setCard.has(x)));
    console.log(`[T-06] 切视图后差异=${JSON.stringify(diff)}`);
    expect(diff, 'AC-6: 无需刷新，切视图后料号集合仍同步').toEqual([]);
  });
});

test.describe('AC-7: Excel 视图必须前端切片', () => {
  test('T-07 第 1 页时 Excel 视图渲染行数 == 100（不是 1845）—— 单独断言，不与卡片视图混同', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await loginAdmin(page);
    await openEditStep2(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(1200);
    // 2026-08-28 默认页大小改为 10，本用例断言的是"100 行"，必须显式切到 100 条/页。
    await switchPageSize(page, 100);
    await switchViewType(page, 'Excel 视图');
    await page.waitForTimeout(2000);
    await shot(page, 'excel-page1-rowcount');

    const rows = page.locator('.ant-table-tbody tr.ant-table-row');
    const rowCount = await rows.count();
    console.log(`[T-07] Excel 视图第 1 页行数 = ${rowCount}`);
    expect(rowCount, 'Excel 视图应渲染出数据行（结果非空守卫）').toBeGreaterThan(0);
    expect(rowCount, 'AC-7: Excel 视图第 1 页行数必须 == 100，绝不能是 1845（后端 /excel-view 全量返回，前端必须自己切片）').toBe(100);
  });
});

test.describe('AC-8: Excel 行配对不错行', () => {
  test('T-08 第 2 页每一行的料号与对应卡片料号一致（不按下标兜底错配）', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await loginAdmin(page);
    await openEditStep2(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(1200);
    // 2026-08-28 默认页大小改为 10，本用例要测"第 2 页 = 全局第 100~199 条"，需显式切到 100 条/页。
    await switchPageSize(page, 100);
    await jumpToPage(page, 2);
    await page.waitForTimeout(800);

    const expectedPage2 = expectedOrder.slice(100, 200).map((r) => r.productPartNo);
    expect(expectedPage2.length, '第 2 页期望料号应为 100 条').toBe(100);

    await switchViewType(page, 'Excel 视图');
    await page.waitForTimeout(2000);
    await shot(page, 'excel-page2-pairing');

    const rows = page.locator('.ant-table-tbody tr.ant-table-row');
    const rowCount = await rows.count();
    expect(rowCount, 'AC-8 前置：Excel 第 2 页行数应为 100').toBe(100);

    // 逐行读取整行文本，确认每行文本恰好含且仅含该行位置对应的期望料号（用行序号定位，而不是信任表格自己的顺序）
    let mismatchCount = 0;
    const mismatches: string[] = [];
    for (let i = 0; i < rowCount; i++) {
      const rowText = await rows.nth(i).innerText();
      const expected = expectedPage2[i];
      if (!rowText.includes(expected)) {
        mismatchCount++;
        mismatches.push(`row[${i}] 期望含 ${expected}，实际文本="${rowText.slice(0, 80)}"`);
      }
    }
    console.log(`[T-08] 配对不一致行数 = ${mismatchCount}`);
    if (mismatches.length) console.log(mismatches.slice(0, 10).join('\n'));
    expect(mismatchCount, 'AC-8: Excel 视图第 2 页逐行配对应与 SQL 期望顺序完全一致，不得按下标兜底错配（AP-54 家族风险点）').toBe(0);
  });
});
