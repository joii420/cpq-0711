/**
 * E2E: 验证 LIST_FORMULA 配置 {mat_bom.net_qty}*5 公式在报价单渲染的结果 (2026-05-20)
 *
 * 流程: 罗克韦尔 + v1.12 + 独立产品 + 3120012574 + 工序总装配 → 确认
 * 验证: 切到 "选配-工序列表" tab, 读 MRO-AS-0001 行的成材率单元格, 打印实际值
 *
 * 后端真实状态:
 *   - mat_bom 对 3120012574 有 3 行 net_qty: NULL / 1.288 / 9.045 (INCOMING / Sn / Ag)
 *   - BNF resolver 返数组 [{net_qty:9.04},{net_qty:1.28},{net_qty:null}]
 *   - 前端 formulaEngine 对多行数组求值结果未知 — 这正是要验证的
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

let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `yrb-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}

async function selectByLabel(page: Page, label: string, search: string, optionText?: string) {
  const item = page.locator('.ant-form-item').filter({ has: page.locator('label', { hasText: label }) }).first();
  const sel = item.locator('.ant-select').first();
  await sel.click();
  await page.waitForTimeout(300);
  if (search) {
    await page.keyboard.type(search, { delay: 60 });
    await page.waitForTimeout(900);
  }
  const opt = optionText || search;
  await page.locator('.ant-select-item-option').filter({ hasText: opt }).first().click();
  await page.waitForTimeout(400);
}

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

test('LIST_FORMULA BNF: v1.12 + 3120012574 + MRO-AS-0001 → 成材率渲染', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(120_000);

  const consoleErrors: string[] = [];
  const lfDebug: string[] = [];
  page.on('console', (m) => {
    const text = m.text();
    if (m.type() === 'error') consoleErrors.push(text);
    if (text.includes('[LF-') || text.includes('path-formula') || text.includes('mat_bom') || text.includes('net_qty')) lfDebug.push(text);
  });
  page.on('pageerror', (e) => consoleErrors.push('PAGE-ERROR: ' + e.message));

  await loginAsAdmin(page);
  await page.goto('/quotations/new');
  await page.waitForLoadState('networkidle');

  // 客户 罗克韦尔
  await selectByLabel(page, '客户', '罗克韦尔');
  await page.locator('text=产品分类').first().waitFor({ state: 'visible', timeout: 10000 }).catch(() => {});
  await page.waitForTimeout(500);

  await page.locator('input[placeholder*="报价单名称"]').first().fill('E2E-yrb-' + Date.now());
  await page.waitForTimeout(200);

  await selectByLabel(page, '产品分类', '默认分类');
  await page.waitForTimeout(1200);

  // 模板 v1.14 (createNewDraft 合并机制验证: 含组件最新 *15 公式)
  await selectByLabel(page, '报价模板', '组合产品 v1.14', '组合产品 v1.14');
  await shot(page, 'template-v1-14-selected');

  await page.getByRole('button', { name: /下一步/ }).first().click();
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);

  // 添加产品 → 选配添加
  await page.getByRole('button', { name: /添加产品/ }).first().click();
  await page.waitForTimeout(500);
  await page.locator('text=选配添加').first().click();
  await page.waitForTimeout(800);

  // 独立产品
  await page.locator('.ant-drawer').locator('text=独立产品').first().click().catch(() => {});
  await page.waitForTimeout(400);
  await page.locator('.ant-drawer button:has-text("下一步")').last().click();
  await page.waitForTimeout(800);

  // 料号 3120012574
  const partInput = page.locator('.ant-drawer input[placeholder*="料号"]').first();
  await partInput.fill('3120012574');
  await page.waitForTimeout(1500);
  await page.locator('.ant-drawer .ant-list-item').filter({ hasText: '3120012574' }).first().click();
  await page.waitForTimeout(600);

  // 下一步 (材质 → 工序)
  await page.locator('.ant-drawer button:has-text("下一步")').last().click();
  await page.waitForTimeout(1500);
  await page.locator('.ant-drawer button:has-text("下一步")').last().click();
  await page.waitForTimeout(1500);
  await shot(page, 'p3-process');

  // 工序: 只选 总装配 (MRO-AS-0001)
  const row = page.locator('.ant-drawer .ant-list-item').filter({ hasText: '总装配' }).first();
  const addBtn = row.locator('button').filter({ hasText: '添加' }).first();
  if (await addBtn.count() > 0) {
    await addBtn.click();
    console.log('[P3] 已添加 总装配 (MRO-AS-0001)');
  }
  await page.waitForTimeout(500);

  // 继续点下一步直到确认按钮
  for (let i = 0; i < 3; i++) {
    const next = page.locator('.ant-drawer button:has-text("下一步")').last();
    if (await next.isVisible().catch(() => false) && await next.isEnabled().catch(() => false)) {
      await next.click();
      await page.waitForTimeout(900);
    } else break;
  }

  // 确认添加
  const confirmBtn = page.locator('.ant-drawer button').filter({ hasText: /确认添加|完成选配|提交/ }).last();
  if (await confirmBtn.isVisible().catch(() => false)) {
    await confirmBtn.click();
    console.log('✓ 已点击 确认添加');
  }
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);
  await shot(page, 'after-confirm');

  // 切到 选配-工序列表 Tab
  const tabs = page.locator('button.qt-tab-btn');
  const tabCount = await tabs.count();
  console.log(`\n=== Tab 总数: ${tabCount} ===`);

  const targetTab = tabs.filter({ hasText: /^选配-工序列表$/ }).first();
  expect(await targetTab.count()).toBeGreaterThan(0);
  await targetTab.click();
  await page.waitForTimeout(2500);
  await shot(page, 'tab-yield-rate');

  // 找到产品卡内的工序列表表格 — 不限定 .ant-table-row, 直接遍历所有 tr 找含 MRO-AS-0001 的行
  const allTr = page.locator('tr');
  const trCount = await allTr.count();
  console.log(`tr 总数: ${trCount}`);

  let mroAs0001YieldValue: string | null = null;
  let headerCells: string[] = [];
  for (let i = 0; i < trCount; i++) {
    const tr = allTr.nth(i);
    const cellsText = await tr.locator('th, td').allInnerTexts().catch(() => []);
    const cellsTrimmed = cellsText.map(c => c.trim());
    // 表头行 (含"成材率")
    if (cellsTrimmed.some(c => c.includes('成材率')) && headerCells.length === 0) {
      headerCells = cellsTrimmed;
      console.log(`📋 表头: ${cellsTrimmed.map(c => `"${c}"`).join(' | ')}`);
    }
    // 数据行 (含 MRO-AS-0001)
    if (cellsTrimmed.some(c => c.includes('MRO-AS-0001'))) {
      console.log(`📦 MRO-AS-0001 行: ${cellsTrimmed.map(c => `"${c}"`).join(' | ')}`);
      const yieldIdx = headerCells.findIndex(c => c.includes('成材率'));
      if (yieldIdx >= 0 && yieldIdx < cellsTrimmed.length) {
        mroAs0001YieldValue = cellsTrimmed[yieldIdx];
        console.log(`✅ 成材率单元格 = "${mroAs0001YieldValue}"`);
      }
    }
  }

  console.log(`\n=== console.error: ${consoleErrors.length} ===`);
  consoleErrors.slice(0, 5).forEach(e => console.log('  🔴 ' + e.slice(0, 200)));

  console.log(`\n=== LF-DEBUG: ${lfDebug.length} 条 ===`);
  lfDebug.slice(0, 20).forEach(e => console.log('  🟡 ' + e.slice(0, 300)));

  await shot(page, 'final');

  // 关键断言: 找到 MRO-AS-0001 行, 渲染值不是"加载中"
  expect(mroAs0001YieldValue, 'MRO-AS-0001 行必须找到').not.toBeNull();
  expect(mroAs0001YieldValue, '不能是"加载中..."').not.toContain('加载中');
  console.log(`\n📌 实际渲染值: "${mroAs0001YieldValue}"`);
  // 业务预期: 公式 {mat_bom[element_name='Sn'].net_qty}*15 (v1.14 由 createNewDraft 合并机制带入组件最新值)
  //   mat_bom 对 3120012574 + element_name='Sn' 单值 net_qty = 1.2880195780
  //   → 1.288... * 15 = 19.3202937
  const numValue = parseFloat(mroAs0001YieldValue || '0');
  console.log(`💡 解析后数值: ${numValue} (期望 ≈ 19.32)`);
  expect(numValue, '成材率应 ≈ 19.32 = 1.288 * 15').toBeCloseTo(19.32, 1);
});
