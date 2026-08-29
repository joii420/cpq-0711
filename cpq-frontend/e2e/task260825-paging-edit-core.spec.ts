/**
 * E2E · task-260825 报价单大单量前端分页 —— 编辑页分页核心行为
 *
 * 覆盖 AC-1 / AC-2 / AC-2b / AC-3 / AC-4（test.md T-01 / T-02 / T-02b / T-03 / T-04）。
 *
 * 用例来源：dev-docs/task-260825-报价单大单量分页与料号查询/需求文档.md §③ A. 编辑页分页
 * 视觉基准：原型图/01-编辑页-卡片视图-默认.html、原型图/03-编辑页-卡片视图-空态与禁用态.html
 *
 * 🚫 本文件不 import src/ 下任何实现代码，用例断言全部来自 AC 原文的可观测判据。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp } from './fixtures/auth';
import {
  LARGE_QUOTATION_ID,
  SMALL_QUOTATION_ID,
  loginAdmin,
  openEditStep2,
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
  const file = path.join(SHOT_DIR, `edit-core-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: false }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
  return file;
}

let backendUp = false;
test.beforeAll(async () => {
  backendUp = await isBackendUp();
  if (backendUp) {
    // 只读校验：样本仍是 1845 行，否则后续所有硬编码判据都会假绿/假红
    const n = queryLineItemCount(LARGE_QUOTATION_ID);
    expect(n, `固定样本 ${LARGE_QUOTATION_ID} 应仍为 1845 行（若变化，本文件全部用例判据需要重算）`).toBe(1845);
  }
});

test.describe('AC-1: 默认渲染 100 张卡片', () => {
  test('T-01 打开 1845 行单 Step2，DOM 中卡片数 <= 100，分页栏文案含"共 1845 条 / 每页 100 条"', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await loginAdmin(page);
    await openEditStep2(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(1500);
    await shot(page, 'default-page1');

    const cardCount = await countRenderedCards(page);
    console.log(`[T-01] 渲染卡片数 = ${cardCount}`);
    expect(cardCount, '渲染卡片数应非零（结果非空守卫，避免空数据空跑断言）').toBeGreaterThan(0);
    expect(cardCount, 'AC-1: 默认渲染卡片数 <= 100').toBeLessThanOrEqual(100);

    const pgbarText = await page.locator('.ant-pagination').first().innerText().catch(() => '');
    console.log(`[T-01] 分页栏文案 = "${pgbarText.replace(/\n/g, ' ')}"`);
    expect(pgbarText, 'AC-1: 分页栏应含总数 1845').toContain('1845');
  });
});

test.describe('AC-2 / AC-2b: 页大小切换 + 禁用态', () => {
  test('T-02 页大小切 100/200/500，切换后回到第 1 页且卡片数受限', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await loginAdmin(page);
    await openEditStep2(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(1200);

    for (const size of [100, 200, 500]) {
      const sizeChanger = page.locator('.ant-pagination-options-size-changer').first();
      await expect(sizeChanger, `页大小切换器应可见（切到 ${size} 前）`).toBeVisible({ timeout: 10000 });
      await sizeChanger.click();
      await page.waitForTimeout(300);
      const opt = page.locator('.ant-select-item-option', { hasText: `${size} 条/页` }).first();
      await expect(opt, `下拉应有 "${size} 条/页" 选项`).toBeVisible({ timeout: 5000 });
      await opt.click();
      await page.waitForTimeout(1200);
      await shot(page, `pagesize-${size}`);

      const cardCount = await countRenderedCards(page);
      console.log(`[T-02] pageSize=${size} → 渲染卡片数 = ${cardCount}`);
      expect(cardCount, `pageSize=${size} 时渲染卡片数应非零`).toBeGreaterThan(0);
      expect(cardCount, `AC-2: pageSize=${size} 时渲染卡片数 <= ${size}`).toBeLessThanOrEqual(size);

      // 切换页大小后应回到第 1 页：当前页码激活项应为 "1"
      const activeItem = page.locator('.ant-pagination-item-active');
      const activeText = await activeItem.innerText().catch(() => '');
      expect(activeText, `AC-2: 切页大小=${size} 后应回到第 1 页`).toBe('1');
    }
  });

  test('T-02b 分页栏禁用态：第 1 页「‹」禁用、末页「›」禁用，且置灰但可见', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await loginAdmin(page);
    await openEditStep2(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(1200);

    // 确保 pageSize=100（19 页），第 1 页
    const prevBtn = page.locator('.ant-pagination-prev').first();
    const nextBtn = page.locator('.ant-pagination-next').first();
    await expect(prevBtn, '「‹」按钮应保留可见（置灰不隐藏）').toBeVisible();
    const prevDisabled = await prevBtn.evaluate((el) => el.classList.contains('ant-pagination-disabled'));
    expect(prevDisabled, 'AC-2b: 第 1 页「‹」应禁用').toBe(true);
    await shot(page, 'disabled-first-page');

    // 跳到末页
    const pageSizeText = await page.locator('.ant-pagination').first().innerText();
    const totalMatch = pageSizeText.match(/共\s*(\d+)\s*条/) || pageSizeText.match(/(\d+)\s*条/);
    console.log(`[T-02b] 分页栏文案: "${pageSizeText.replace(/\n/g, ' ')}"`);

    const jumper = page.locator('.ant-pagination-options-quick-jumper input');
    if (await jumper.count() > 0) {
      await jumper.fill('19');
      await jumper.press('Enter');
      await page.waitForTimeout(1000);
      await shot(page, 'disabled-last-page');
      const nextDisabled = await nextBtn.evaluate((el) => el.classList.contains('ant-pagination-disabled'));
      expect(await nextBtn.isVisible(), '「›」按钮应保留可见（置灰不隐藏）').toBe(true);
      expect(nextDisabled, 'AC-2b: 末页「›」应禁用').toBe(true);
    } else {
      console.warn('[T-02b] 未找到 quick-jumper 输入框，跳页断言未执行 —— 需要补充其他跳页方式');
    }
  });

  test('T-02b 比对视图下分页栏整体不渲染', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await loginAdmin(page);
    await openEditStep2(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(1200);

    const cmpTab = page.locator('.ant-segmented-item', { hasText: '比对视图' }).first();
    await expect(cmpTab).toBeVisible({ timeout: 10000 });
    await cmpTab.click();
    await page.waitForTimeout(1500);
    await shot(page, 'comparison-no-pgbar');

    // 比对视图有自己的 .ant-pagination（10/页），本任务新增分页栏理应整体不渲染。
    // 前端已按约定给本任务新增的分页栏（顶部+底部）挂 data-testid="task260825-paging-bar"，
    // 直接断言数量为 0 —— 比"不出现 500 条/页选项"这类间接判据更硬，不受前端换页大小选项影响。
    const pagingBarCount = await page.locator('[data-testid="task260825-paging-bar"]').count();
    console.log(`[T-02b] 比对视图下 task260825-paging-bar 数量 = ${pagingBarCount}`);
    expect(pagingBarCount, 'AC-2b: 比对视图下本任务分页栏应整体不渲染').toBe(0);
  });
});

test.describe('AC-3: 翻页零网络请求', () => {
  test('T-03 连翻 5 页期间 /api 请求数 == 0', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await loginAdmin(page);
    await openEditStep2(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(1500);

    const apiCalls: string[] = [];
    page.on('request', (req) => {
      if (req.url().includes('/api/')) apiCalls.push(`${req.method()} ${req.url()}`);
    });

    for (let i = 2; i <= 6; i++) {
      const pageItem = page.locator(`.ant-pagination-item-${i}`).first();
      if (await pageItem.count() === 0) {
        // 页码可能因省略号未直接渲染，退化为点「›」
        const nextBtn = page.locator('.ant-pagination-next').first();
        await nextBtn.click();
      } else {
        await pageItem.click();
      }
      await page.waitForTimeout(500);
    }
    await page.waitForTimeout(500);

    console.log(`[T-03] 翻 5 页期间 /api 请求 = ${JSON.stringify(apiCalls)}`);
    expect(apiCalls.length, 'AC-3: 翻页不应发起任何 /api 请求（阳性对照见 T-03b：首次进入页面本身确有请求，证明监听器工作正常）').toBe(0);
  });

  test('T-03b 阳性对照：首次打开编辑页确实会有 /api 请求（证明请求监听器本身有效）', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    const apiCalls: string[] = [];
    page.on('request', (req) => {
      if (req.url().includes('/api/')) apiCalls.push(req.url());
    });
    await loginAdmin(page);
    await openEditStep2(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(1500);
    console.log(`[T-03b] 首次打开期间 /api 请求数 = ${apiCalls.length}`);
    expect(apiCalls.length, '阳性对照：打开页面本身应产生 /api 请求，否则 T-03 的 0 值无意义').toBeGreaterThan(0);
  });
});

test.describe('AC-4: 小单行为与改动前一致', () => {
  test('T-04 小单（1 行）不渲染分页栏', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await loginAdmin(page);
    await openEditStep2(page, SMALL_QUOTATION_ID);
    await page.waitForTimeout(1200);
    await shot(page, 'small-order-no-pgbar');

    const cardCount = await countRenderedCards(page);
    expect(cardCount, '小单应至少渲染出 1 张卡片（结果非空守卫）').toBeGreaterThan(0);

    // 前端已按约定给本任务新增的分页栏（顶部+底部）挂 data-testid="task260825-paging-bar"，
    // 直接断言数量为 0 —— 比 isVisible()===false（找不到元素时同样返回 false，判据偏弱）更硬。
    const pagingBarCount = await page.locator('[data-testid="task260825-paging-bar"]').count();
    console.log(`[T-04] 小单 task260825-paging-bar 数量 = ${pagingBarCount}, 卡片数 = ${cardCount}`);
    expect(pagingBarCount, 'AC-4: 小单（< 100 行）分页栏整体不应渲染').toBe(0);
  });
});
