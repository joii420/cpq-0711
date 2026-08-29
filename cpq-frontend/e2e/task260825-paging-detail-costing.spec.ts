/**
 * E2E · task-260825 报价单大单量前端分页 —— 详情页 + 核价工作台分页
 *
 * 覆盖 AC-17 / AC-18（test.md T-17 / T-18）。
 *
 * AC-18 需要一个挂在 1845 行大单上的 costing_order（核价工作台数据来自
 * `GET /costing-orders/{coid}`，路由为 /costing-summary 下的详情，本文件写死路由为
 * `/costing-summary/{coid}`，若与实现路由不符，需要开发方在完成 F-4 时确认实际路由）。
 * 只读 SQL 已确认：QT-20260825-0180（1845 行）目前**没有**关联的 costing_order
 * （costing_order 只在报价单"提交核价"后才创建）。现网所有 costing_order 关联的
 * quotation 行数都 <= 1（造数背景见本文件末尾 beforeAll 里的说明与本次回报的"造数需求"一节）。
 * 因此 T-18 用 test.skip 挂起，等待主线批准"是否可以把 QT-20260825-0180 提交核价"
 * 或"另建一张 1845 行的核价单副本"。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { execSync } from 'child_process';
import { isBackendUp } from './fixtures/auth';
import {
  LARGE_QUOTATION_ID,
  loginAdmin,
  openDetail,
  countRenderedCards,
  DB_HOST, DB_NAME, DB_USER, DB_PASSWORD,
} from './fixtures/task260825-paging';

const __filename = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filename);
const SHOT_DIR = path.join(__dirnameLocal, 'screenshots', 'task260825');
fs.mkdirSync(SHOT_DIR, { recursive: true });
let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `detail-costing-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: false }).catch(() => {});
  return file;
}

function psqlScalar(sql: string): string {
  const cmd = `PGPASSWORD=${DB_PASSWORD} psql -h ${DB_HOST} -U ${DB_USER} -d ${DB_NAME} -t -A -c "${sql.replace(/"/g, '\\"')}"`;
  return execSync(cmd, { encoding: 'utf-8', shell: '/bin/bash' }).trim();
}

let backendUp = false;
let largeCostingOrderId: string | null = null;

test.beforeAll(async () => {
  backendUp = await isBackendUp();
  if (backendUp) {
    // 只读探测：是否已存在关联到 1845 行大单的 costing_order（若主线已批准造数并完成，这里会查到）
    const coid = psqlScalar(
      `SELECT id FROM costing_order WHERE quotation_id='${LARGE_QUOTATION_ID}' ORDER BY created_at DESC LIMIT 1;`
    );
    largeCostingOrderId = coid || null;
    console.log(`[beforeAll] 大单关联 costing_order = ${largeCostingOrderId ?? '(不存在，T-18 将 skip)'}`);
  }
});

test.describe('AC-17: 详情页翻页零写接口', () => {
  test('T-17 详情页翻 5 页期间 PUT/POST 请求数 == 0', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    const writeRequests: string[] = [];
    const allRequests: string[] = [];
    page.on('request', (req) => {
      if (req.url().includes('/api/')) {
        allRequests.push(`${req.method()} ${req.url()}`);
        if (req.method() === 'PUT' || req.method() === 'POST') {
          writeRequests.push(`${req.method()} ${req.url()}`);
        }
      }
    });

    await loginAdmin(page);
    await openDetail(page, LARGE_QUOTATION_ID);
    await page.waitForTimeout(1500);
    await shot(page, 'detail-page1');

    const cardCount = await countRenderedCards(page);
    console.log(`[T-17] 详情页首屏卡片数 = ${cardCount}`);
    expect(cardCount, '详情页应渲染出卡片（结果非空守卫）').toBeGreaterThan(0);
    expect(cardCount, 'AC-17 连带：详情页首屏也应受分页限制 <= 100').toBeLessThanOrEqual(100);

    for (let i = 2; i <= 6; i++) {
      const pageItem = page.locator(`.ant-pagination-item-${i}`).first();
      if (await pageItem.count() > 0) {
        await pageItem.click();
      } else {
        await page.locator('.ant-pagination-next').first().click();
      }
      await page.waitForTimeout(500);
    }
    await page.waitForTimeout(500);

    console.log(`[T-17] 全部 /api 请求(含GET)数 = ${allRequests.length}，写请求 = ${JSON.stringify(writeRequests)}`);
    // 阳性对照：allRequests 里应至少有首次打开时的 GET，证明监听器有效
    expect(allRequests.length, '阳性对照：监听器应至少捕获到打开详情页的 GET 请求').toBeGreaterThan(0);
    expect(writeRequests, 'AC-17: 翻 5 页期间不应出现任何 PUT/POST 请求').toEqual([]);
  });
});

test.describe('AC-18: 核价工作台分页（🚧 需 1845 行大单挂 costing_order，主线批准前 skip）', () => {
  test('T-18 核价工作台渲染卡片数 == 100', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    test.skip(!largeCostingOrderId, '现网无挂在 1845 行大单上的 costing_order，需主线批准造数方案（详见回报"造数需求"一节）');

    await loginAdmin(page);
    await page.goto(`/costing-summary/${largeCostingOrderId}`);
    await page.waitForLoadState('networkidle', { timeout: 45000 }).catch(() => {}); // 大单持续有后台请求，networkidle 可能等不到，不中断流程
    await page.waitForTimeout(1500);
    await shot(page, 'costing-workbench');

    const cardCount = await countRenderedCards(page);
    console.log(`[T-18] 核价工作台渲染卡片数 = ${cardCount}`);
    expect(cardCount, '核价工作台应渲染出卡片（结果非空守卫）').toBeGreaterThan(0);
    expect(cardCount, 'AC-18: 核价工作台渲染卡片数应 == 100（默认页大小）').toBe(100);

    const sizeChanger = page.locator('.ant-pagination-options-size-changer').first();
    const sizeChangerVisible = await sizeChanger.isVisible().catch(() => false);
    expect(sizeChangerVisible, 'AC-18: 核价工作台应有页大小选择器（与报价侧一致）').toBe(true);
  });
});
