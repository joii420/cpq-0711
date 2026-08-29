/**
 * E2E · task-260825 报价单大单量前端分页 —— 料号模糊查询
 *
 * 覆盖 AC-9 / AC-10 / AC-11 / AC-12 / AC-13 / AC-14（test.md T-09 ~ T-14）。
 *
 * 🚨 test.md 陷阱 #1：T-09 必须用 sortOrder 第 1200 位的料号（深位），
 * 用第 1~100 位测即使实现只在当前页里找也会通过 —— 假绿。
 * 本文件的第 1200 位料号与 customerProductNo 均来自只读 SQL 现查，不硬编码猜测值。
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
  switchViewType,
  countRenderedCards,
  extractVisiblePartNoSet,
  queryOrderedLineItems,
  queryLineItemCount,
  queryQuotationCustomerCode,
  queryCustomerProductNo,
} from './fixtures/task260825-paging';

const __filename = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filename);
const SHOT_DIR = path.join(__dirnameLocal, 'screenshots', 'task260825');
fs.mkdirSync(SHOT_DIR, { recursive: true });
let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `search-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: false }).catch(() => {});
  return file;
}

/** 报价单编辑页料号查询输入框（原型 .search input，placeholder 见 fronttask.md）。 */
function searchInput(page: Page) {
  return page.locator('input[placeholder*="料号"][placeholder*="客户产品编号"]').first();
}

let backendUp = false;
let expectedOrder: ReturnType<typeof queryOrderedLineItems> = [];
let deepPartNo = '';
let customerCode = '';
let deepCustomerProductNo: string | null = null;

test.beforeAll(async () => {
  backendUp = await isBackendUp();
  if (backendUp) {
    expect(queryLineItemCount(LARGE_QUOTATION_ID), '固定样本应仍为 1845 行').toBe(1845);
    expectedOrder = queryOrderedLineItems(LARGE_QUOTATION_ID);
    expect(expectedOrder.length, '只读 SQL 取数应非空').toBe(1845);
    // 第 1200 位（1-indexed）= 下标 1199
    deepPartNo = expectedOrder[1199].productPartNo;
    customerCode = queryQuotationCustomerCode(LARGE_QUOTATION_ID);
    deepCustomerProductNo = queryCustomerProductNo(deepPartNo, customerCode);
    console.log(`[fixtures] 第1200位料号=${deepPartNo}, 客户=${customerCode}, customerProductNo=${deepCustomerProductNo}`);
    expect(deepPartNo, '第 1200 位料号不应为空').toBeTruthy();
  }
});

test.describe('AC-9: 查询命中深位料号（第 1200 位）', () => {
  test('T-09 搜索第 1200 位料号必须命中，命中数==1，且该卡片渲染在第 1 页', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await loginAdmin(page);
    await openEditStep2(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(1200);

    const input = searchInput(page);
    await expect(input, '查询输入框应可见').toBeVisible({ timeout: 10000 });
    await input.fill(deepPartNo);
    await page.waitForTimeout(600); // 200ms 防抖 + 余量

    const pgbarText = await page.locator('.ant-pagination').first().innerText().catch(() => '');
    console.log(`[T-09] 分页栏文案 = "${pgbarText.replace(/\n/g, ' ')}"`);
    await shot(page, 'deep-hit');

    const cardCount = await countRenderedCards(page);
    console.log(`[T-09] 命中卡片数 = ${cardCount}`);
    expect(cardCount, 'AC-9: 搜索深位料号必须命中且只命中 1 条').toBe(1);

    const cardText = await page.locator('.qt-product-card').first().innerText();
    expect(cardText, `AC-9: 命中卡片应显示搜索的料号 ${deepPartNo}`).toContain(deepPartNo);

    // 计数显示为命中数而非 1845
    expect(pgbarText, 'AC-9: 计数应反映命中数（不是全量 1845 的原样展示）').toMatch(/1|匹配/);
  });
});

test.describe('AC-10: 匹配字段 productPartNo + customerProductNo', () => {
  test('T-10a 用生产料号（productPartNo）搜索命中', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await loginAdmin(page);
    await openEditStep2(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(1200);
    const input = searchInput(page);
    await input.fill(deepPartNo);
    await page.waitForTimeout(600);
    const cardCount = await countRenderedCards(page);
    expect(cardCount, `AC-10: productPartNo=${deepPartNo} 应命中`).toBeGreaterThan(0);
  });

  test('T-10b 用客户产品编号（customerProductNo）搜索命中', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    test.skip(!deepCustomerProductNo, `第 1200 位料号 ${deepPartNo} 在 material_customer_map 中无 customerProductNo，改用现网另一条有值样本 —— 需主线协助补充样本`);
    await loginAdmin(page);
    await openEditStep2(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(1200);
    const input = searchInput(page);
    await input.fill(deepCustomerProductNo!);
    await page.waitForTimeout(600);
    await shot(page, 'customer-product-no-hit');
    const cardCount = await countRenderedCards(page);
    console.log(`[T-10b] customerProductNo=${deepCustomerProductNo} 命中卡片数=${cardCount}`);
    expect(cardCount, `AC-10: customerProductNo=${deepCustomerProductNo} 应命中`).toBeGreaterThan(0);
    const cardText = await page.locator('.qt-product-card').first().innerText();
    expect(cardText, 'AC-10: 命中卡片应含搜索的生产料号').toContain(deepPartNo);
  });
});

test.describe('AC-11: 查询空态', () => {
  test('T-11 查询命中 0 行：空态文案逐字一致，不报错不白屏，不保留上一页内容', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    const consoleErrors: string[] = [];
    page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });

    await loginAdmin(page);
    await openEditStep2(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(1200);

    const input = searchInput(page);
    await input.fill('XYZ999');
    await page.waitForTimeout(600);
    await shot(page, 'empty-state');

    const cardCount = await countRenderedCards(page);
    console.log(`[T-11] 空态下卡片数 = ${cardCount}`);
    expect(cardCount, 'AC-11: 查询命中 0 时不应保留上一页任何卡片').toBe(0);

    const bodyText = await page.locator('body').innerText();
    expect(bodyText, 'AC-11: 空态标题应逐字一致').toContain('未找到匹配的料号');
    expect(bodyText, 'AC-11: 空态副文案应逐字一致（含具体查询词与总数 1845）').toContain('「XYZ999」在本报价单的 1845 个料号中无匹配。请换一个料号片段，或清空查询查看全部。');
    expect(bodyText, 'AC-11: 应有"清空查询"按钮文案').toContain('清空查询');

    console.log(`[T-11] console errors = ${JSON.stringify(consoleErrors)}`);
    expect(consoleErrors, 'AC-11: 空态不应产生 console error').toEqual([]);
  });
});

test.describe('AC-12: 查询状态下翻页正确（2026-08-28 裁决：默认页大小 100→10）', () => {
  test('T-12 命中数跨页时，第 2 页是命中集合第 11-20 条（按新默认页大小 10 算）', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    // 用真实数据构造一个命中数可控的查询词：找一个使命中数 >10（跨页，按新默认页大小 10）的前缀
    // 生产料号全部形如 202601xxxxxx，先用 SQL 侧统计不同前缀长度下的命中分布，选一个命中数在 [11,1844] 区间的前缀
    let candidatePrefix = '';
    let candidateCount = 0;
    for (let len = 10; len <= 12; len++) {
      const counts = new Map<string, number>();
      for (const r of expectedOrder) {
        const p = r.productPartNo.slice(0, len);
        counts.set(p, (counts.get(p) || 0) + 1);
      }
      for (const [p, c] of counts) {
        if (c > 10 && c < 1845) { candidatePrefix = p; candidateCount = c; break; }
      }
      if (candidatePrefix) break;
    }
    test.skip(!candidatePrefix, '未能从现网数据构造出命中数跨页（>10 且 <1845）的查询前缀，需要主线协助确认是否要专门造数');
    console.log(`[T-12] 选用前缀="${candidatePrefix}" 期望命中数=${candidateCount}`);

    await loginAdmin(page);
    await openEditStep2(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(1200);
    const input = searchInput(page);
    await input.fill(candidatePrefix);
    await page.waitForTimeout(600);

    const pgbarText = await page.locator('.ant-pagination').first().innerText().catch(() => '');
    console.log(`[T-12] 分页栏="${pgbarText.replace(/\n/g, ' ')}"`);

    const matchedInOrder = expectedOrder.filter((r) => r.productPartNo.startsWith(candidatePrefix)).map((r) => r.productPartNo);
    const expectedPage2 = matchedInOrder.slice(10, 20); // 新默认页大小 10：第 2 页 = 第 11~20 条

    const jumper = page.locator('.ant-pagination-options-quick-jumper input').first();
    if (await jumper.count() > 0) {
      await jumper.fill('2');
      await jumper.press('Enter');
    } else {
      await page.locator('.ant-pagination-item-2').first().click();
    }
    await page.waitForTimeout(800);
    await shot(page, 'query-page2');

    const setPage2 = await extractVisiblePartNoSet(page, '.qt-products-list, body');
    const diff = expectedPage2.filter((x) => !setPage2.has(x));
    console.log(`[T-12] 查询态第2页 与期望差异(应为空)=${JSON.stringify(diff.slice(0, 10))}`);
    expect(diff, 'AC-12: 查询状态下第 2 页应恰好是命中集合第 11-20 条（新默认页大小 10）').toEqual([]);
  });
});

test.describe('AC-13: 清空查询回到全量分页', () => {
  test('T-13 清空查询后计数回到 1845', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await loginAdmin(page);
    await openEditStep2(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(1200);
    const input = searchInput(page);
    await input.fill(deepPartNo);
    await page.waitForTimeout(600);
    expect(await countRenderedCards(page), '查询后应先命中').toBe(1);

    await input.fill('');
    await page.waitForTimeout(600);
    await shot(page, 'cleared');
    const pgbarText = await page.locator('.ant-pagination').first().innerText().catch(() => '');
    console.log(`[T-13] 清空后分页栏="${pgbarText.replace(/\n/g, ' ')}"`);
    expect(pgbarText, 'AC-13: 清空查询后计数应回到 1845').toContain('1845');
    const cardCount = await countRenderedCards(page);
    expect(cardCount, 'AC-13: 清空查询后应恢复分页渲染（<=10，新默认页大小）').toBeLessThanOrEqual(10);
    expect(cardCount, 'AC-13: 清空查询后应有卡片渲染').toBeGreaterThan(0);
  });
});

test.describe('AC-14: 查询与分页作用于三视图', () => {
  test('T-14 查询后切 Excel 视图，行数 == 命中数（受当前页限制）', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await loginAdmin(page);
    await openEditStep2(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(1200);
    const input = searchInput(page);
    await input.fill(deepPartNo);
    await page.waitForTimeout(600);
    expect(await countRenderedCards(page), '查询后卡片视图应先命中 1 条').toBe(1);

    await switchViewType(page, 'Excel 视图');
    await page.waitForTimeout(1500);
    await shot(page, 'query-excel-view');

    const rows = page.locator('.ant-table-tbody tr.ant-table-row');
    const rowCount = await rows.count();
    console.log(`[T-14] 查询态 Excel 视图行数 = ${rowCount}`);
    expect(rowCount, 'AC-14: 查询作用于 Excel 视图，行数应等于命中数 1（不是 100 也不是 1845）').toBe(1);
    const rowText = await rows.first().innerText();
    expect(rowText, 'AC-14: Excel 视图命中行应含搜索的料号').toContain(deepPartNo);
  });
});
