/**
 * T1.3 验收：QT-20260714-1982 干净单，报价侧「来料」页签删第1行 → 删对行 + 不串行 + 多次重渲染稳定。
 * 拦截 delete-driver-row 请求 + 读表单值签名。测后 SQL 清墓碑还原。
 */
import { test, expect, Page } from '@playwright/test';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const QID = 'bf0a6a25-b591-4bb0-b9f2-60e83b1743fb';
const TAB = '来料';
let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

async function toTab(page: Page, tab: string): Promise<boolean> {
  const t = page.locator('.qt-tab-btn', { hasText: tab }).first();
  if (await t.count() === 0) return false;
  await t.click().catch(() => {}); await page.waitForTimeout(800);
  return true;
}
async function enter(page: Page): Promise<boolean> {
  await page.goto(`/quotations/${QID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1300);
  const nextBtn = page.locator('button', { hasText: /下一步|继续/ }).first();
  if (await nextBtn.count() > 0 && await nextBtn.isEnabled().catch(() => false)) {
    await nextBtn.click().catch(() => {}); await page.waitForTimeout(1000);
  }
  const quote = page.locator('.ant-segmented-item', { hasText: '报价单' }).first();
  if (await quote.count() > 0) { await quote.click().catch(() => {}); await page.waitForTimeout(700); }
  const cardSeg = page.locator('.ant-segmented-item', { hasText: '产品卡片' }).first();
  if (await cardSeg.count() > 0) { await cardSeg.click().catch(() => {}); await page.waitForTimeout(700); }
  return toTab(page, TAB);
}
async function rowSigs(page: Page): Promise<string[]> {
  return await page.evaluate(() => {
    const trs = Array.from(document.querySelectorAll('.qt-cost-table tbody tr'));
    return trs.map((tr) => Array.from(tr.querySelectorAll('td')).slice(0, -1).map((td) => {
      const inp = td.querySelector('input,select') as HTMLInputElement | null;
      return inp ? String(inp.value ?? '') : (td.textContent || '').trim();
    }).join('|'));
  });
}

test('来料删第1行: 删对行 + 剩余行值不串 + 多次重渲染稳定', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page);
  expect(await enter(page), '应进入来料页签').toBe(true);

  const before = await rowSigs(page);
  console.log('[T1.3] 删前行:', JSON.stringify(before));
  expect(before.length, '来料应≥3行').toBeGreaterThanOrEqual(3);
  const clicked = before[0];               // AgNi11#-Ⅰ 行
  const survivors = before.slice(1);        // 期望删后剩这些(逐字段不变)

  let sent: any = null;
  page.on('request', (req) => {
    if (req.url().includes('/delete-driver-row') && req.method() === 'POST') {
      try { sent = req.postDataJSON(); } catch { sent = req.postData(); }
    }
  });
  // 点第1行 ✕，等服务端权威投影原子重灌
  await page.locator('.qt-cost-table tbody tr').nth(0).locator('td:last-child button').click().catch(() => {});
  await page.waitForTimeout(2500);
  const after = await rowSigs(page);
  console.log('[T1.3] 删后行:', JSON.stringify(after));
  console.log('[T1.3] 发出 body:', JSON.stringify(sent));

  // A1: 被点第1行(AgNi)消失；剩余行 == 原第2、3行(逐字段不变，无串行/无污染)
  expect(after, 'A1: 删后应恰为原第2、3行(删对行+值不串)').toEqual(survivors);
  expect(after.includes(clicked), 'A1: 被点的 AgNi 行应消失').toBe(false);
  // A2: 两存储同数(展开 N-1 == 渲染行数)
  expect(after.length, 'A2: 剩 2 行').toBe(before.length - 1);

  // B1: 多次重渲染不劣化 —— 切走再回来 3 次，行内容稳定不变
  for (let k = 0; k < 3; k++) {
    await toTab(page, '元素');
    await toTab(page, TAB);
    const again = await rowSigs(page);
    console.log(`[T1.3] 第${k + 1}次重渲染后:`, JSON.stringify(again));
    expect(again, `B1: 第${k + 1}次重渲染行应稳定不劣化`).toEqual(survivors);
  }

  // 加载中残留
  const loading = await page.locator('text=加载中').count();
  expect(loading, '加载中应为0').toBe(0);
});
