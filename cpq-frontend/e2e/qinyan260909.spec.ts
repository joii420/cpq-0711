/**
 * 主线亲验 · repair-260909 —— 走真实 UI（5174 → 8081 → 共享 dev 库 → 真实数据）。
 *
 * 🚫 全程只读：切数据集 / 切数据源 / 看左侧字段面板，**不点保存、不建组件、不改任何数据**。
 * 覆盖：AC-P1（核价两方言出「价格策略」组）· AC-N1（非材质元素页签不出）· AC-R2（报价侧不变）
 *
 * ⚠️ 本 spec 故意跑在 5174/8081 上 —— 亲验的定义就是「在用户实际使用的环境跑」（CLAUDE.md §4.5 步骤 4a）。
 */
import { test, expect } from '@playwright/test';
import * as path from 'path';
import { openComponentByCode, switchTab, selectDataset, selectSource, expandAllGroups, closeAnyModal } from './task260908-s2.helpers';

const SHOT = '/home/joii/project/cpq/dev-docs/task-260908-取数配置器优化/repair-260909-核价侧价格策略配置/证据/亲验';
const CODE = process.env.QY_COMPONENT || 'COMP-2254';

let n = 0;
async function shot(page, name: string) {
  const f = path.join(SHOT, `qy-${String(++n).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: f, fullPage: false });
  console.log(`📸 ${f}`);
}

/** 读左侧字段面板里所有分组的标题；价格策略组是带框的 .svb-grp */
async function readGroups(page): Promise<string[]> {
  await expandAllGroups(page).catch(() => {});
  const titles = await page.locator('.svb-grp .svb-grp-h').allInnerTexts().catch(() => []);
  return titles.map(t => t.replace(/\s+/g, ' ').trim());
}

test.beforeEach(async ({ page }) => {
  await page.goto('/login');
  await page.fill('input[placeholder*="用户名"], input#username', 'admin');
  await page.fill('input[type="password"]', 'Admin@2026');
  await page.getByRole('button', { name: /登\s*录/ }).first().click();
  await page.waitForTimeout(4000);
});

test('亲验 · AC-P1 / AC-N1 / AC-R2：三方言 × 页签 的价格策略组分布', async ({ page }) => {
  const errs: string[] = [];
  page.on('console', m => { if (m.type() === 'error') errs.push(m.text()); });

  await openComponentByCode(page, CODE);
  await switchTab(page, '取数配置');
  await shot(page, '取数配置Tab已开');

  const results: Record<string, string[]> = {};

  // ── AC-P1：核价两方言的「物料与元素BOM」应出现「价格策略」组 ──
  for (const ds of ['基础核价', '明细核价'] as const) {
    await selectDataset(page, ds);
    await selectSource(page, '物料与元素BOM');
    const gs = await readGroups(page);
    results[`${ds}/物料与元素BOM`] = gs;
    console.log(`[AC-P1] ${ds} / 物料与元素BOM → 分组 = ${JSON.stringify(gs)}`);
    await shot(page, `AC-P1-${ds}`);
  }

  // ── AC-N1：核价侧 BOM 页签不应出现 ──
  // 🚨 核价侧数据源的**标签**是「物料BOM」，不是 tab_type 的「BOM」——
  //    首跑我按 tab_type 写成 'BOM' 直接找不到项，下拉实际可见 ["物料与元素BOM",…,"物料BOM"]。
  //    📌 又是「我按 A 层的名字去 B 层找」那一族：tab_type 是图侧的值，下拉显示的是 label。
  await selectDataset(page, '基础核价');
  await selectSource(page, '物料BOM');
  const bomGroups = await readGroups(page);
  results['基础核价/物料BOM'] = bomGroups;
  console.log(`[AC-N1] 基础核价 / 物料BOM → 分组 = ${JSON.stringify(bomGroups)}`);
  await shot(page, 'AC-N1-基础核价物料BOM');

  // ── AC-R2：报价侧应仍有且只有一个 ──
  await selectDataset(page, '报价');
  await selectSource(page, '物料与元素BOM');
  const quoteGroups = await readGroups(page);
  results['报价/物料与元素BOM'] = quoteGroups;
  console.log(`[AC-R2] 报价 / 物料与元素BOM → 分组 = ${JSON.stringify(quoteGroups)}`);
  await shot(page, 'AC-R2-报价');

  console.log('\n===== 亲验汇总 =====');
  for (const [k, v] of Object.entries(results)) {
    const price = v.filter(t => t.includes('价格策略')).length;
    console.log(`  ${k.padEnd(28)} 分组数=${v.length} 价格策略组数=${price}`);
  }
  console.log(`console error 数 = ${errs.length}`);
  if (errs.length) console.log('  首条:', errs[0].slice(0, 300));

  // ── 断言 ──
  for (const ds of ['基础核价', '明细核价']) {
    const gs = results[`${ds}/物料与元素BOM`];
    expect(gs.filter(t => t.includes('价格策略')).length,
      `AC-P1 ${ds}：应恰好 1 个价格策略组，实得分组=${JSON.stringify(gs)}`).toBe(1);
  }
  expect(bomGroups.filter(t => t.includes('价格策略')).length,
    `AC-N1：核价 BOM 页签不应有价格策略组，实得=${JSON.stringify(bomGroups)}`).toBe(0);
  expect(quoteGroups.filter(t => t.includes('价格策略')).length,
    `AC-R2：报价侧应仍恰好 1 个，实得=${JSON.stringify(quoteGroups)}`).toBe(1);
});
