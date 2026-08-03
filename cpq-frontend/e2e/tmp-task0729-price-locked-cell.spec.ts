/**
 * task-0729 跨屏 · 元素单价列只读态渲染证据（fronttask §8 / api.md §4.3）。
 * 后端 B10 归位机制未上线，`__priceLocked`/`__priceVersion` 真实数据里还不存在，
 * 用 Playwright 网络层拦截「取真实响应 → 注入这两个字段 → 原样放行」的方式验证渲染，
 * 而不是手写一份假的整单 JSON（QuotationStep2/ReadonlyProductCard 依赖的结构极深，
 * 手写 mock 极易因缺字段而整页崩溃，与本次要验证的目标无关）。源码零 mock。用完即删。
 *
 * 验证目标（对应 fronttask §8.2 渲染口径 1/2/5）：
 *   1. 有 __priceLocked=true 标记的 INPUT_* 单元格 → 渲染只读文本 + "🔒{version}" 徽标，
 *      不是 <input>，也不是普通只读文本（无徽标）
 *   2. 同一页面里没有该标记的 INPUT_* 单元格 → 渲染保持原样可编辑 <input>（无标记不改变现状）
 *   3. 编辑页（QuotationStep2）与详情页（ReadonlyProductCard）两处都要出现徽标（AP-50 同步）
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const SHOT_DIR = path.join(path.dirname(fileURLToPath(import.meta.url)), 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

/** 在报价单整单 JSON 里找第一个 INPUT_NUMBER/INPUT_TEXT/INPUT 字段所在的行，注入锁定标记
 *  （不要求该格当前有值——isEmpty 时渲染逻辑走 "—" + 徽标分支，同样是要验证的路径之一）。 */
function injectPriceLockOnFirstInputCell(payload: any): { injected: boolean; fieldName?: string; materialNo?: string; fieldTypesSeen?: string[] } {
  const lineItems = payload?.lineItems;
  if (!Array.isArray(lineItems)) return { injected: false };
  const fieldTypesSeen: string[] = [];
  for (const li of lineItems) {
    const componentData = li?.componentData;
    if (!Array.isArray(componentData)) continue;
    for (const comp of componentData) {
      const fields = comp?.fields;
      const rows = comp?.rows;
      if (!Array.isArray(fields)) continue;
      for (const f of fields) fieldTypesSeen.push(f?.field_type);
      if (!Array.isArray(rows) || rows.length === 0) continue;
      const inputField = fields.find((f: any) => ['INPUT_NUMBER', 'INPUT_TEXT', 'INPUT'].includes(f?.field_type));
      if (inputField) {
        rows[0].__priceLocked = true;
        rows[0].__priceVersion = 'V26080501';
        return { injected: true, fieldName: inputField.name, materialNo: li.productPartNo };
      }
    }
  }
  return { injected: false, fieldTypesSeen: Array.from(new Set(fieldTypesSeen)) };
}

async function listDraftQuotationIds(page: import('@playwright/test').Page): Promise<string[]> {
  const res = await page.request.get('/api/cpq/quotations?page=1&size=30&status=DRAFT');
  if (!res.ok()) return [];
  const json = await res.json();
  const list = json?.content ?? json?.data?.content ?? [];
  return (list ?? []).map((q: any) => q.id).filter(Boolean);
}

test('元素单价列只读态：编辑页 + 详情页渲染证据（网络层注入 __priceLocked，源码零 mock）', async ({ page }) => {
  test.skip(!backendUp, 'backend down');
  await loginAsAdmin(page);

  const candidates = await listDraftQuotationIds(page);
  test.skip(candidates.length === 0, '本库暂无 DRAFT 报价单，跳过（非本次改动问题）');

  let injection: { injected: boolean; fieldName?: string; materialNo?: string; fieldTypesSeen?: string[] } = { injected: false };
  let qid: string | null = null;

  // 依次尝试候选单，直到找到一个含 INPUT_* 字段的组件为止（不同单模板不同，不保证第一个就有）
  for (const candidate of candidates) {
    let localInjection: typeof injection = { injected: false };
    const handler = async (route: import('@playwright/test').Route) => {
      const response = await route.fetch();
      const json = await response.json();
      localInjection = injectPriceLockOnFirstInputCell(json);
      await route.fulfill({ response, json });
    };
    await page.route(new RegExp(`/api/cpq/quotations/${candidate}$`), handler);
    await page.goto(`/quotations/${candidate}/edit`);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1500);
    await page.unroute(new RegExp(`/api/cpq/quotations/${candidate}$`), handler);
    console.log(`[T0729][price-locked] 候选单 ${candidate} 注入结果=`, JSON.stringify(localInjection));
    if (localInjection.injected) { injection = localInjection; qid = candidate; break; }
  }
  test.skip(!qid, '候选 DRAFT 单里没有一张含 INPUT_* 字段的组件，跳过（数据库样本限制，非本次改动问题）');
  if (!qid) return;

  // 找到含 INPUT_* 字段的单后，重新挂持久拦截 + 正式导航（上面循环里已经导航过一次，这里用同一 qid 重新走一遍，
  // 确保断言时页面状态是"确定注入成功"的那次导航，不依赖循环内最后一次残留状态）
  await page.route(new RegExp(`/api/cpq/quotations/${qid}$`), async (route) => {
    const response = await route.fetch();
    const json = await response.json();
    injectPriceLockOnFirstInputCell(json);
    await route.fulfill({ response, json });
  });

  // ── 编辑页（QuotationStep2，readonly=false）──
  await page.goto(`/quotations/${qid}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2500);
  console.log('[T0729][price-locked] 编辑页注入结果=', JSON.stringify(injection));

  if (injection.injected) {
    await expect(page.locator('.qt-price-locked-badge')).toHaveCount(1);
    const badgeText = await page.locator('.qt-price-locked-badge').first().textContent();
    console.log('[T0729][price-locked] 编辑页徽标文案=', badgeText);
    expect(badgeText).toContain('V26080501');
    // 反向断言：徽标不是渲染在 <input> 内部——即该锁定单元格没有对应可编辑 input
    const lockedCellHasInput = await page.locator('.qt-price-locked-cell input').count();
    expect(lockedCellHasInput).toBe(0);
    // 页面上仍应有其它正常可编辑的 INPUT_* input（无标记不改变现状）
    const stillEditableInputs = await page.locator('table input').count();
    console.log('[T0729][price-locked] 编辑页仍可编辑 input 数量=', stillEditableInputs);
    expect(stillEditableInputs).toBeGreaterThan(0);
  }
  await page.screenshot({ path: path.join(SHOT_DIR, 't0729-pricelocked-01-edit.png'), fullPage: true }).catch(() => {});

  // ── 详情页（ReadonlyProductCard，readonly=true）── AP-50 两处同步
  await page.goto(`/quotations/${qid}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2500);
  if (injection.injected) {
    const detailBadgeCount = await page.locator('.qt-price-locked-badge').count();
    console.log('[T0729][price-locked] 详情页徽标出现次数=', detailBadgeCount);
    expect(detailBadgeCount).toBeGreaterThanOrEqual(1);
  }
  await page.screenshot({ path: path.join(SHOT_DIR, 't0729-pricelocked-02-detail.png'), fullPage: true }).catch(() => {});
});
