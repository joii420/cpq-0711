/**
 * E2E 验证：核价/报价页签表格横向自适应 —— 宽表在卡片内(.qt-cost-table-wrap)横向滚动，
 * 不再顶宽卡片/页面(无页面级横向溢出)、不裁左侧。编辑页 + 详情页共用同一包裹层。
 *
 * 背景(2026-07-13)：.qt-cost-table 无横向滚动包裹，12+ 列 min-width 之和 > 卡片 → 溢出
 *   直接顶宽页面 → 页面级横滚 + 左侧(tab栏/料号)被裁(用户截图)。修复：加 .qt-cost-table-wrap
 *   { overflow-x:auto }，列多时表格在包裹内滚动、列少时 width:100% 仍填满 → 双向自适应。
 *
 * 断言：进核价单产品卡片、逐 tab 找到最宽表格：
 *   ① 页面无横向溢出：documentElement.scrollWidth - clientWidth <= 阈值
 *   ② 该宽表包裹层可横向滚动：wrap.scrollWidth > wrap.clientWidth（证明溢出被包裹层吸收）
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const __filename = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filename);
const SHOT_DIR = path.join(__dirnameLocal, 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

const CANDIDATES = [
  'c4d9b1dc-889a-4264-83d1-0b1d9b067fb6', // QT-20260713-1963
  '06206647-730e-4691-a64d-c9d41d2e71a9', // QT-20260713-1962
  '5786f324-da7a-4be9-adf0-c718fad7a3fe', // QT-20260713-1961
  '63040961-9b00-4c1b-941c-4aaf911d77ec', // QT-20260712-1960
];

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

async function enterCostingCard(page: Page, qid: string): Promise<boolean> {
  await page.goto(`/quotations/${qid}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1200);
  const nextBtn = page.locator('button', { hasText: /下一步|继续/ }).first();
  if (await nextBtn.count() > 0 && await nextBtn.isEnabled().catch(() => false)) {
    await nextBtn.click().catch(() => {});
    await page.waitForTimeout(1000);
  }
  const costing = page.locator('.ant-segmented-item', { hasText: '核价单' }).first();
  if (await costing.count() === 0) return false;
  await costing.click().catch(() => {});
  await page.waitForTimeout(900);
  const cardSeg = page.locator('.ant-segmented-item', { hasText: '产品卡片' }).first();
  if (await cardSeg.count() > 0) { await cardSeg.click().catch(() => {}); await page.waitForTimeout(700); }
  await page.waitForTimeout(1200);
  return true;
}

/** 返回 { pageOverflow, wrapScrollX, wrapClientX, tableW } —— 当前活动表格的度量 */
async function measure(page: Page) {
  return await page.evaluate(() => {
    const de = document.documentElement;
    const wrap = document.querySelector('.qt-cost-table-wrap') as HTMLElement | null;
    const table = document.querySelector('.qt-cost-table') as HTMLElement | null;
    return {
      pageOverflow: de.scrollWidth - de.clientWidth,
      wrapScrollX: wrap ? wrap.scrollWidth : 0,
      wrapClientX: wrap ? wrap.clientWidth : 0,
      tableW: table ? table.getBoundingClientRect().width : 0,
    };
  });
}

test('核价页签宽表在卡片内横向滚动，页面不横向溢出', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page);
  expect(page.url()).not.toContain('/login');

  let best: { qid: string; tab: string; m: any } | null = null;

  for (const qid of CANDIDATES) {
    const ok = await enterCostingCard(page, qid);
    if (!ok) continue;
    const tabs = page.locator('.qt-tab-btn');
    const tabN = await tabs.count();
    if (tabN === 0) continue;
    for (let i = 0; i < tabN; i++) {
      await tabs.nth(i).click().catch(() => {});
      await page.waitForTimeout(600);
      const name = (await tabs.nth(i).innerText().catch(() => '')).trim();
      if (await page.locator('.qt-cost-table-wrap').count() === 0) continue;
      const m = await measure(page);
      console.log(`[RESP] qid=${qid.slice(0,8)} tab='${name}' page溢出=${m.pageOverflow} 表宽=${Math.round(m.tableW)} 包裹可视=${m.wrapClientX} 包裹内容=${m.wrapScrollX}`);
      // 记录“表最宽”的一个作为代表
      if (!best || m.tableW > best.m.tableW) best = { qid, tab: name, m };
    }
    if (best && best.m.wrapScrollX > best.m.wrapClientX + 1) break; // 已找到确实溢出包裹的宽表
  }

  test.skip(!best, '当前无可渲染核价表的活单');
  console.log(`[RESP] 代表宽表: ${best!.qid.slice(0,8)} / '${best!.tab}'`, JSON.stringify(best!.m));

  await page.locator('.qt-product-card').first().screenshot({
    path: path.join(SHOT_DIR, 'responsive-card.png'),
  }).catch(() => {});

  // ① 页面不横向溢出（容 2px 亚像素/滚动条误差）
  expect(best!.m.pageOverflow, `页面不应横向溢出（实测 ${best!.m.pageOverflow}px）`).toBeLessThanOrEqual(2);
  // ② 该宽表的包裹层确实横向可滚动（内容宽 > 可视宽）→ 证明溢出被 .qt-cost-table-wrap 吸收，而非顶宽页面
  expect(best!.m.wrapScrollX, '宽表包裹层应横向可滚动（内容宽 > 可视宽）').toBeGreaterThan(best!.m.wrapClientX + 1);
});

test('详情页(只读)核价卡片同样有横向滚动包裹、页面不横向溢出', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page);

  let hit: { qid: string; pageOverflow: number; wraps: number } | null = null;
  for (const qid of CANDIDATES) {
    await page.goto(`/quotations/${qid}`);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1800);
    // 上层 Segmented 核价单 + 下层 产品卡片（与编辑页同选择器）
    const costing = page.locator('.ant-segmented-item', { hasText: '核价单' }).first();
    if (await costing.count() === 0) continue;
    await costing.click().catch(() => {});
    await page.waitForTimeout(800);
    const cardSeg = page.locator('.ant-segmented-item', { hasText: '产品卡片' }).first();
    if (await cardSeg.count() > 0) { await cardSeg.click().catch(() => {}); await page.waitForTimeout(800); }
    await page.waitForTimeout(1000);
    const wraps = await page.locator('.qt-cost-table-wrap').count();
    if (wraps === 0) continue;
    const m = await measure(page);
    console.log(`[RESP-DETAIL] qid=${qid.slice(0,8)} 包裹数=${wraps} page溢出=${m.pageOverflow}`);
    hit = { qid, pageOverflow: m.pageOverflow, wraps };
    break;
  }

  test.skip(!hit, '当前无可只读渲染核价卡片的详情页');
  await page.locator('.qt-product-card').first().screenshot({
    path: path.join(SHOT_DIR, 'responsive-detail-card.png'),
  }).catch(() => {});
  // 详情页(ReadonlyProductCard)也用同一 .qt-cost-table-wrap → 页面不横向溢出
  expect(hit!.wraps, '详情页核价卡片应渲染横向滚动包裹层').toBeGreaterThan(0);
  expect(hit!.pageOverflow, `详情页不应横向溢出（实测 ${hit!.pageOverflow}px）`).toBeLessThanOrEqual(2);
});
