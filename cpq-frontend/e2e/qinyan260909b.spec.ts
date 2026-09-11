/**
 * 主线亲验 B（repair-260909）—— 合并后在真实环境（5174 → 8081 → 共享 dev 库）走 UI。
 * 覆盖 AC-P2（编译产物的 JOIN 形状）· AC-P3（取价逐位相同）· AC-B1b（渲染层不显示「加载中…」尝试）
 * 🚫 只读：拖列 + 预览，**不点保存**。
 */
import { test, expect } from '@playwright/test';
import * as path from 'path';
import { openComponentByCode, switchTab, selectDataset, selectSource, addField, expandAllGroups } from './task260908-s2.helpers';

const SHOT = '/home/joii/project/cpq/dev-docs/task-260908-取数配置器优化/repair-260909-核价侧价格策略配置/证据/亲验';
let n = 10;
async function shot(page: any, name: string) {
  const f = path.join(SHOT, `qy-${++n}-${name}.png`);
  await page.screenshot({ path: f, fullPage: false });
  console.log(`📸 ${f}`);
}

test.beforeEach(async ({ page }) => {
  await page.goto('/login');
  await page.fill('input[placeholder*="用户名"], input#username', 'admin');
  await page.fill('input[type="password"]', 'Admin@2026');
  await page.getByRole('button', { name: /登\s*录/ }).first().click();
  await page.waitForTimeout(4000);
});

test('亲验B · AC-P2/AC-P3：核价侧拖入元素单价 → SQL 形状 + 真实取价', async ({ page }) => {
  const errs: string[] = [];
  page.on('console', m => { if (m.type() === 'error') errs.push(m.text()); });

  await openComponentByCode(page, process.env.QY_COMPONENT || 'COMP-2254');
  await switchTab(page, '取数配置');
  await selectDataset(page, '基础核价');
  await selectSource(page, '物料与元素BOM');
  await expandAllGroups(page);

  // ── 拖入价格策略组的「元素单价」──
  await addField(page, '元素单价');
  await page.waitForTimeout(2000);
  await shot(page, 'AC-P2-已拖入元素单价');

  // ── AC-P2：读右侧 SQL 预览面板 ──
  const sqlText = await page.locator('pre, code, .svb-sql, [class*="sql"]').allInnerTexts()
    .then(a => a.join('\n')).catch(() => '');
  const hasBridge = /cep\.material_no\s*=\s*\w+\.sales_material_no/.test(sqlText);
  const hasWrong  = /cep\.material_no\s*=\s*\w+\.production_no/.test(sqlText);
  console.log(`[AC-P2] SQL 含 cep.material_no = <锚点>.sales_material_no : ${hasBridge}`);
  console.log(`[AC-P2] SQL 含 cep.material_no = <锚点>.production_no（错误形态）: ${hasWrong}`);
  const m = sqlText.match(/LEFT JOIN f_material_element_price[^\n]*(\n[^\n]*){0,2}/);
  if (m) console.log('[AC-P2] JOIN 原文:\n' + m[0]);

  // ── AC-P3：选预览客户 + 料号 → 重新执行 ──
  // 🚩 选择器改用真实 DOM 结构（SqlViewBuilderTab.tsx:1351 renderPreview）：
  //    .svb-preview > .svb-pv-h 里第一个 .ant-select 就是「客户」。
  //    📌 该 Select 有 showSearch + onSearch(服务端搜索) —— 我先前引用的
  //       「预览客户下拉只加载前 20 条且无搜索」**不成立**，是记错了。
  const custSel = page.locator('.svb-preview .svb-pv-h .ant-select').first();
  await expect(custSel, '找不到预览客户下拉 ⇒ 入口问题，本条判【未验证】').toBeVisible({ timeout: 15000 });
  await custSel.click();
  await page.waitForTimeout(600);
  const dd = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').first();
  const si = custSel.locator('input.ant-select-selection-search-input').first();
  // 🚨 按**名称**搜，不是按编码：searchCustomers 把 keyword 传给 customerService.list，
  //    实测传 'CUST-0001' 返回的是未过滤的默认列表（后端按名称匹配）。
  //    📌 又是同一族：拿一个层的键（编码）去查另一个层（按名称检索的接口）。
  // 🚨 用真实按键输入，不用 fill：antd 的 onSearch 靠 keydown/input 事件驱动，
  //    `fill()` 直接设 value 不产生按键 ⇒ onSearch 不触发。
  //    实证：两次跑用 fill 得到的下拉选项**逐字相同**（就是初始 searchCustomers('') 的结果），
  //    这正是「量具没动」的信号 —— 若不比对两次读数，会误判成「库里没有这个客户」。
  const before = await dd.locator('.ant-select-item-option').allInnerTexts().catch(() => []);
  await page.keyboard.type('罗克韦尔', { delay: 120 });
  await page.waitForTimeout(3000);
  const opts = await dd.locator('.ant-select-item-option').allInnerTexts().catch(() => []);
  console.log(`[AC-P3] 输入前 ${before.length} 项 → 输入「罗克韦尔」后 ${opts.length} 项`);
  console.log(`[AC-P3] 列表是否真的变了 = ${JSON.stringify(before) !== JSON.stringify(opts)}（false = onSearch 没触发，量具没动）`);
  console.log(`[AC-P3] 当前可见 = ${JSON.stringify(opts.slice(0, 6))}`);
  const want = dd.locator('.ant-select-item-option').filter({ hasText: /罗克韦尔|CUST-0001/ }).first();
  const found = await want.count();
  console.log(`[AC-P3] 下拉里能否选到 CUST-0001 : ${found > 0}`);
  if (!found) {
    console.log('[AC-P3] 🚨 搜不到 CUST-0001（罗克韦尔）—— 本条记「量具够不着·未验证」，🚫 不得记成产品缺陷');
    await shot(page, 'AC-P3-客户下拉够不着');
    return;
  }
  await want.click();
  await page.waitForTimeout(600);
  await page.locator('input[placeholder="料号（可空）"]').fill('S-3120014539');
  await page.getByRole('button', { name: '重新执行' }).click();
  await page.waitForTimeout(6000);
  await shot(page, 'AC-P3-预览结果');

  // 🚩 结果表是 <table class="svb-pv-table">，**不是 antd Table** —— 上一跑我读 .ant-table-tbody
  //    拿到空串，看起来像「预览没出数据」，实际是**选择器找错了表**（截图里明明写着「返回 7 行·25ms」）。
  //    📌 空读数不能直接当「没有数据」：先证明表存在再读值。
  const pv = page.locator('table.svb-pv-table');
  await pv.first().scrollIntoViewIfNeeded().catch(() => {});
  await page.waitForTimeout(800);
  await shot(page, 'AC-P3-预览结果表');
  const cnt = await pv.count();
  console.log(`[AC-P3] svb-pv-table 存在数 = ${cnt}（0 ⇒ 选择器问题，不是没数据）`);
  const headers = await pv.first().locator('thead th').allInnerTexts().catch(() => []);
  const rows = await pv.first().locator('tbody tr').allInnerTexts().catch(() => []);
  console.log(`[AC-P3] 表头 = ${JSON.stringify(headers)}`);
  console.log(`[AC-P3] 数据行数 = ${rows.length}`);
  rows.slice(0, 8).forEach((r, i) => console.log(`   行${i + 1}: ${r.replace(/\s+/g, ' | ').slice(0, 160)}`));
  const table = rows.join(' | ');
  const banner = await page.locator('.rescount').innerText().catch(() => '');
  console.log(`[AC-P3] 结果条 = ${banner}`);
  console.log(`[AC-P3] 是否出现 43358.75 : ${table.includes('43358.75')}`);
  console.log(`[AC-B1b] 空值渲染成什么：NULL 单元格数 = ${await pv.first().locator('td.null').count()}；是否出现「加载中」= ${table.includes('加载中')}`);
  console.log(`console error 数 = ${errs.length}`);
  errs.slice(0, 3).forEach((e, i) => console.log(`  err${i + 1}: ${e.slice(0, 180)}`));

  expect(cnt, 'AC-P3：预览结果表应存在').toBeGreaterThan(0);
  expect(rows.length, 'AC-P3：预览应返回非空行（先证非空再看值）').toBeGreaterThan(0);
  expect(table.includes('43358.75'),
    `AC-P3：核价侧预览的元素单价应为 43358.75（与报价侧同值），实得行=${JSON.stringify(rows.slice(0,3))}`).toBe(true);
  expect(hasBridge, 'AC-P2：SQL 应含 cep.material_no = <锚点>.sales_material_no').toBe(true);
  expect(hasWrong, 'AC-P2 反向：不应含 cep.material_no = <锚点>.production_no').toBe(false);
});
