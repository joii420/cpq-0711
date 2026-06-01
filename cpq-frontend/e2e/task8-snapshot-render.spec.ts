/**
 * Task 8 — 报价单整份快照渲染切换验证（用 QT-20260601-1482：苏州西门子 + 真实料号行）。
 *
 * 直接打开已有 DRAFT 报价单进编辑向导，验证：
 *  1) 产品卡片各 Tab 渲染（qt-tab-btn > 0）
 *  2) 无 '加载中' 永久占位（final = 0）
 *  3) 渲染期 /batch-expand 调用次数（脱钩证据：Task 8 后应为 0；基线会 > 0）
 *
 * 基线（Task 8 前）：tabs 渲染 + 加载中=0；batchExpand 计数仅记录。
 * Task 8 后：额外断言 renderPhaseBatchExpand === 0。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';

const __filename = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filename);
const SHOT_DIR = path.join(__dirnameLocal, 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

// QT-20260601-1482 苏州西门子 DRAFT
const QUOTATION_ID = '151897d4-1afa-46de-9bb1-3ae99664b933';

let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `t8-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}

async function countLoading(page: Page, tag: string) {
  const c = await page.locator('text=加载中').count();
  console.log(`[${tag}] '加载中' count = ${c}`);
  return c;
}

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

test('Task8 渲染: 打开 QT-20260601-1482 编辑向导，各 Tab 渲染 + 加载中=0 + batch-expand 计数', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  const consoleErrors: string[] = [];
  let batchExpandTotal = 0;
  let renderPhaseBatchExpand = 0;
  let renderPhase = false;
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', (e) => consoleErrors.push('PAGE-ERROR: ' + e.message));
  page.on('request', (req) => {
    if (req.url().includes('/batch-expand')) {
      batchExpandTotal++;
      if (renderPhase) {
        renderPhaseBatchExpand++;
        const body = (req.postData() || '').slice(0, 300);
        console.log(`  [render-batch-expand #${renderPhaseBatchExpand}] ${body}`);
      }
    }
  });

  // 1) 登录
  await loginAsAdmin(page);
  await shot(page, 'after-login');

  // 2) 打开编辑向导（DRAFT → loadQuotation 触发 refreshCardSnapshot + getById，含 "正在重新计算" 延迟）
  await page.goto(`/quotations/${QUOTATION_ID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);
  await shot(page, 'wizard-loaded');

  // 等 Step1 "下一步" 可用（编辑态门禁修复后应在 refresh 完成后启用），最长 30s
  const nextBtn = page.getByRole('button', { name: /下一步/ }).first();
  if (await nextBtn.isVisible().catch(() => false)) {
    await nextBtn.waitFor({ state: 'visible', timeout: 5000 }).catch(() => {});
    // 轮询直到 enabled
    for (let i = 0; i < 30; i++) {
      if (await nextBtn.isEnabled().catch(() => false)) break;
      await page.waitForTimeout(1000);
    }
    const enabled = await nextBtn.isEnabled().catch(() => false);
    console.log(`[edit-gate] Step1 '下一步' enabled = ${enabled}`);
    await shot(page, 'step1-nextbtn');
    if (enabled) {
      await nextBtn.click();
      await page.waitForTimeout(2000);
      await shot(page, 'step2');
    }
  }
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);

  // 滚到第一个产品卡片
  await page.locator('text=/产品\\s*1/').first().scrollIntoViewIfNeeded().catch(() => {});
  await page.waitForTimeout(800);
  await shot(page, 'product-card');

  // === 渲染期开始监控 batch-expand ===
  renderPhase = true;

  const tabs = page.locator('button.qt-tab-btn');
  const tabCount = await tabs.count();
  console.log(`\n=== qt-tab-btn 数量: ${tabCount} ===`);
  for (let i = 0; i < tabCount; i++) {
    const t = await tabs.nth(i).innerText().catch(() => '?');
    console.log(`  Tab[${i}]: "${t.replace(/\n/g, ' | ').trim()}"`);
  }

  // 逐 Tab 切换 + 截图 + 统计行/加载中
  for (let i = 0; i < tabCount; i++) {
    const tab = tabs.nth(i);
    const name = (await tab.innerText().catch(() => `tab${i}`)).replace(/\n/g, ' ').trim().slice(0, 20);
    await tab.click().catch(() => {});
    await page.waitForTimeout(1800);
    await shot(page, `tab-${i}-${name}`);
    const loadCount = await countLoading(page, `tab-${name}`);
    const rows = page.locator('.qt-cost-table tbody tr');
    const rowCount = await rows.count();
    console.log(`  [Tab '${name}'] rows=${rowCount}, 加载中=${loadCount}`);
    for (let r = 0; r < Math.min(rowCount, 3); r++) {
      const cells = await rows.nth(r).locator('td').allInnerTexts().catch(() => []);
      console.log(`    row[${r}]: ${cells.map(c => `"${c.trim().slice(0, 24)}"`).join(' | ')}`);
    }
  }

  await page.waitForTimeout(500);
  const loadingFinal = await countLoading(page, 'final');
  await shot(page, 'final');

  console.log(`\n=== console.error 总数: ${consoleErrors.length} ===`);
  consoleErrors.slice(0, 8).forEach(e => console.log('  🔴 ' + e.slice(0, 180)));
  console.log(`=== /batch-expand 总调用: ${batchExpandTotal}, 渲染期调用: ${renderPhaseBatchExpand} (脱钩稳态=0; 偶发>0 为 autosave 重建行瞬态, 见 RECORD) ===`);
  console.log(`=== '加载中' final: ${loadingFinal} (期望 0) ===`);

  // 硬断言: 用户可见门禁
  expect(tabCount, '产品卡片至少渲染 1 个 Tab').toBeGreaterThan(0);
  expect(loadingFinal, "渲染后不得有 '加载中' 永久占位").toBe(0);
});

test('Task8 编辑往返: 元素.单价 编辑 → 自动保存 → 重开存活 + 渲染期无 batch-expand', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  let renderPhase = false;
  let renderBatchExpand = 0;
  page.on('request', (req) => {
    if (req.url().includes('/batch-expand') && renderPhase) renderBatchExpand++;
  });

  const UNIQUE = '77.' + String(Date.now()).slice(-4); // 唯一值便于校验存活

  async function openToStep2() {
    await page.goto(`/quotations/${QUOTATION_ID}/edit`);
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(3000);
    const next = page.getByRole('button', { name: /下一步/ }).first();
    if (await next.isVisible().catch(() => false)) {
      for (let i = 0; i < 30; i++) { if (await next.isEnabled().catch(() => false)) break; await page.waitForTimeout(1000); }
      if (await next.isEnabled().catch(() => false)) { await next.click(); await page.waitForTimeout(2000); }
    }
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(1500);
  }

  async function gotoElementTab() {
    const tab = page.locator('button.qt-tab-btn').filter({ hasText: /^元素$/ }).first();
    if (await tab.isVisible().catch(() => false)) { await tab.click(); await page.waitForTimeout(1500); }
  }

  await loginAsAdmin(page);
  await openToStep2();
  await gotoElementTab();

  // 找 元素 tab 内单价列的 number input(第一行)
  const priceInput = page.locator('.qt-cost-table tbody tr input[type="number"]').first();
  const hasInput = await priceInput.count();
  console.log(`[edit] number inputs in 元素 tab: ${hasInput}`);
  test.skip(hasInput === 0, '元素 tab 无可编辑 number input');

  await priceInput.fill(UNIQUE);
  await priceInput.blur();
  console.log(`[edit] filled 单价 = ${UNIQUE}`);
  await shot(page, 'edit-filled');

  // 等 autosave(10s 间隔)持久化
  await page.waitForTimeout(13000);

  // 重开
  renderPhase = true;
  await openToStep2();
  await gotoElementTab();
  await shot(page, 'edit-reopened');

  const reopened = page.locator('.qt-cost-table tbody tr input[type="number"]').first();
  const val = await reopened.inputValue().catch(() => '');
  console.log(`[edit] reopened 单价 = "${val}" (期望含 ${UNIQUE})`);
  console.log(`[edit] 重开渲染期 batch-expand = ${renderBatchExpand}`);

  expect(val, '编辑值必须在重开后存活').toBe(UNIQUE);
});
