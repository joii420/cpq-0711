/**
 * 共享夹具 · task-260825 报价单大单量前端分页与料号查询
 *
 * 数据来源：全部为只读 SQL 查询（cpq_db_0724，只读 SELECT，不写库），
 * 在 spec 的 beforeAll 里现查，避免把可能随数据变化而漂移的具体值硬编码进代码。
 *
 * 🚫 本文件不 import 任何 src/ 下的实现代码 —— 测试与实现物理隔离。
 *
 * 🚨 两轮执行协议（A/B 快照对比类用例：task260825-paging-regression.spec.ts /
 *    task260825-paging-perf.spec.ts 均依赖此约定，🚫 全部前端地址一律走
 *    `process.env.PW_BASE_URL`，不许硬编码 5174）：
 *
 *      before: PW_BASE_URL=http://localhost:5174        TASK260825_PHASE=before   # 主仓 master
 *      after:  PW_BASE_URL=http://localhost:<临时端口>  TASK260825_PHASE=after    # worktree 临时 vite
 *
 *    原因：5174 是全会话共享的 dev server，服务的是**主工作区**代码，不是本 worktree。
 *    before 轮打 5174 天然正确（此刻主工作区就是改动前的 master）；after 轮必须在本
 *    worktree 内另起一个临时端口的 vite（`node_modules` 已软链，不需重装依赖），
 *    指向本 worktree 的分页代码，**不得复用 5174**（会测到 master 的旧代码，全线假红/假绿）。
 *    后端 8081 全程不变（本任务服务端零改动），可用 `PW_BACKEND_URL` 覆盖，默认不需要动。
 */
import { execSync } from 'child_process';
import { Page, expect } from '@playwright/test';

export const LARGE_QUOTATION_ID = '1ed36b62-cb35-4f88-82e8-9456e496544d'; // QT-20260825-0180, 1845 行, DRAFT
export const LARGE_QUOTATION_NO = 'QT-20260825-0180';
export const SMALL_QUOTATION_ID = '6a6df891-5192-4fa2-a33c-3f39442529e6'; // QT-20260814-0179, 1 行
export const DB_HOST = '10.177.152.12';
export const DB_NAME = 'cpq_db_0724';
export const DB_USER = 'postgres';
export const DB_PASSWORD = 'joii5231';

/** productPartNo 在本单的固定格式 —— 用于从渲染出的纯文本里正则抓取料号集合。已用 SQL 验证 1845/1845 条全部满足该正则。 */
export const PART_NO_REGEX = /202601\d{6}/g;

function psql(sql: string): string {
  const cmd = `PGPASSWORD=${DB_PASSWORD} psql -h ${DB_HOST} -U ${DB_USER} -d ${DB_NAME} -t -A -F'|' -c "${sql.replace(/"/g, '\\"')}"`;
  return execSync(cmd, { encoding: 'utf-8', shell: '/bin/bash' }).trim();
}

export interface LineItemRow {
  id: string;
  sortOrder: number;
  productPartNo: string;
  customerPartNo: string;
}

/** 按 sort_order 升序取出该单全部行的 (id, sort_order, productPartNo, customerPartNo)。只读，不写库。 */
export function queryOrderedLineItems(quotationId: string): LineItemRow[] {
  const out = psql(
    `SELECT id, sort_order, product_part_no_snapshot, coalesce(customer_part_no,'') FROM quotation_line_item WHERE quotation_id='${quotationId}' ORDER BY sort_order;`
  );
  if (!out) return [];
  return out.split('\n').filter(Boolean).map((line) => {
    const [id, so, ppn, cpn] = line.split('|');
    return { id, sortOrder: Number(so), productPartNo: ppn, customerPartNo: cpn };
  });
}

/** 查 material_customer_map 取某料号在某客户下的 customerProductNo（AC-10 用的字段，与 customer_part_no 列是两回事）。 */
export function queryCustomerProductNo(materialNo: string, customerCode: string): string | null {
  const out = psql(
    `SELECT customer_product_no FROM material_customer_map WHERE material_no='${materialNo}' AND customer_no='${customerCode}' LIMIT 1;`
  );
  return out || null;
}

export function queryQuotationCustomerCode(quotationId: string): string {
  const out = psql(
    `SELECT c.code FROM quotation q JOIN customer c ON c.id=q.customer_id WHERE q.id='${quotationId}';`
  );
  return out;
}

/** 该单总行数（只读校验用，避免样本本身漂移导致后续断言全部失真）。 */
export function queryLineItemCount(quotationId: string): number {
  const out = psql(`SELECT count(*) FROM quotation_line_item WHERE quotation_id='${quotationId}';`);
  return Number(out);
}

/** 登录（复用项目既有 storageState 机制）。 */
export async function loginAdmin(page: Page) {
  const { loginAsAdmin } = await import('./auth');
  await loginAsAdmin(page);
}

/**
 * 大单（1845 行）渲染耗时 30s+ 且期间持续有网络活动（自发 saveDraft / batch-evaluate 等），
 * `waitForLoadState('networkidle')` 在这种页面上经常在默认 30s 内等不到"网络安静"，
 * 属于测试基础设施的等待策略问题（不是业务 bug，见 testing.md §4 "先查测试基础设施"）。
 * 这里放宽超时且失败不中断，随后一律再用一个具体 DOM 信号（Segmented/卡片可见）兜底确认。
 */
async function waitNetworkIdleTolerant(page: Page, timeoutMs = 45000) {
  await page.waitForLoadState('networkidle', { timeout: timeoutMs }).catch(() => {
    console.warn(`[task260825] networkidle 在 ${timeoutMs}ms 内未达到（大单页面持续有后台请求属已知现象），改用 DOM 信号兜底确认`);
  });
}

/** 打开报价单编辑页并进入 Step2（若已在 Step1，点"下一步"）。 */
export async function openEditStep2(page: Page, quotationId: string) {
  await page.goto(`/quotations/${quotationId}/edit`);
  await waitNetworkIdleTolerant(page);
  await page.waitForTimeout(1200);
  const nextBtn = page.getByRole('button', { name: /下一步/ }).first();
  if (await nextBtn.isVisible().catch(() => false)) {
    await expect(nextBtn).toBeEnabled({ timeout: 20000 });
    await nextBtn.click();
    await waitNetworkIdleTolerant(page);
  }
  // Step2 标志性元素：mainTab Segmented + 分页栏或卡片（大单渲染慢，超时放宽到 60s）
  await expect(page.locator('.ant-segmented').first()).toBeVisible({ timeout: 60000 });
}

/** 打开报价单详情页（只读）。 */
export async function openDetail(page: Page, quotationId: string) {
  await page.goto(`/quotations/${quotationId}`);
  await waitNetworkIdleTolerant(page);
  await page.waitForTimeout(1200);
}

/** 切上层 Segmented（报价单 / 核价单 / 比对视图）。 */
export async function switchMainTab(page: Page, label: '报价单' | '核价单' | '比对视图') {
  const seg = page.locator('.ant-segmented-item', { hasText: label }).first();
  await expect(seg, `mainTab "${label}" 应可见`).toBeVisible({ timeout: 10000 });
  await seg.click();
  await page.waitForTimeout(500);
}

/** 切下层 Segmented（产品卡片 / Excel 视图）。 */
export async function switchViewType(page: Page, label: '产品卡片' | 'Excel 视图') {
  const seg = page.locator('.ant-segmented-item', { hasText: label }).first();
  await expect(seg, `viewType "${label}" 应可见`).toBeVisible({ timeout: 10000 });
  await seg.click();
  await page.waitForTimeout(800);
}

/** 从当前 DOM 纯文本里按 PART_NO_REGEX 抓取所有出现的料号（去重排序），用于跨视图集合比对。 */
export async function extractVisiblePartNoSet(page: Page, scopeSelector = 'body'): Promise<Set<string>> {
  const text = await page.locator(scopeSelector).first().innerText();
  const matches = text.match(PART_NO_REGEX) || [];
  return new Set(matches);
}

/**
 * 按料号精确定位唯一产品卡（复用既有 e2e 基础设施 fixtures/precision.ts:productCardByPartNo 的
 * 同一约定：`.qt-product-card` 内 `.qt-sku-badge` 文本形如 "料号: <partNo>"）。
 * 比"整卡 innerText 里 includes(partNo)"更精确 —— 避免同页面其它文字巧合命中子串。
 */
export async function cardByPartNo(page: Page, partNo: string) {
  const cards = page.locator('.qt-product-card').filter({
    has: page.locator('.qt-sku-badge').filter({ hasText: new RegExp(`料号:\\s*${partNo.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?:\\s|$)`) }),
  });
  await expect(cards, `料号 ${partNo} 应唯一对应一张可见产品卡`).toHaveCount(1, { timeout: 10000 });
  return cards.first();
}

/** 当前渲染的产品卡片数量（.qt-product-card）。 */
export async function countRenderedCards(page: Page): Promise<number> {
  return page.locator('.qt-product-card').count();
}

/**
 * 显式切页大小（2026-08-28 用户裁决：默认页大小 100→10，可选档位 [10,30,50,100,200,500]）。
 * 一些用例（如三视图切片/配对、AP-54 写回下标专项）为了测到"深页/大页"的边界，需要显式切到
 * 100 条/页而不是依赖默认值 —— 默认值已从 100 改成 10，不再天然满足这些用例原有的 SQL 期望值
 * （如"第 3 页 = 全局下标 200~299"）。调用方无需自己重算这些偏移量，只要先调本函数切到 100。
 */
export async function switchPageSize(page: Page, size: 10 | 30 | 50 | 100 | 200 | 500) {
  const sizeChanger = page.locator('.ant-pagination-options-size-changer').first();
  await expect(sizeChanger, `页大小切换器应可见（切到 ${size} 前）`).toBeVisible({ timeout: 10000 });
  await sizeChanger.click();
  await page.waitForTimeout(300);
  const opt = page.locator('.ant-select-item-option', { hasText: `${size} 条/页` }).first();
  await expect(opt, `下拉应有 "${size} 条/页" 选项`).toBeVisible({ timeout: 5000 });
  await opt.click();
  await page.waitForTimeout(1000);
}

/** 阻断除 GET 外的一切 /api 请求（性能测量 / 只读浏览类用例的红线要求）。返回被拦截请求数的引用计数器。 */
export async function blockNonGetApi(page: Page): Promise<{ blocked: number; requests: string[] }> {
  const counter = { blocked: 0, requests: [] as string[] };
  await page.route('**/api/**', async (route) => {
    const req = route.request();
    if (req.method() !== 'GET') {
      counter.blocked++;
      counter.requests.push(`${req.method()} ${req.url()}`);
      await route.abort();
      return;
    }
    await route.continue();
  });
  return counter;
}

/** 统计 /api 网络请求数（用于 AC-3/AC-17 翻页零请求断言），不拦截，只计数。 */
export function countApiRequests(page: Page): { count: number; urls: string[] } {
  const rec = { count: 0, urls: [] as string[] };
  page.on('request', (req) => {
    if (req.url().includes('/api/')) {
      rec.count++;
      rec.urls.push(`${req.method()} ${req.url()}`);
    }
  });
  return rec;
}
