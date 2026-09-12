/**
 * repair-260911 · AC-1 页面层断言（只读，不点保存、不改任何数据）。
 * AC 原文：问题说明.md ⑥ AC-1 —— QT-20260911-0010 卡片 S0001 核价 Excel 视图四列
 *   元素小计 489985 / 物料小计 5438667.5 / 加工费 5.8 / 单价 5438673.3
 * 已知语义：四列表达式均为 [页签(总计)]，同一卡片每个 BOM 节点行显示同一整页签总计。
 * S0001 的树 = 7 行：300001 / 300012 / 300013 / 300014 / 300015 / 00003 / 00081
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

const QID = '405ab315-3ee6-43e0-8a4d-9837b762ab81';
const S0001_NODES = ['300001', '300012', '300013', '300014', '300015', '00003', '00081'];
const EXPECT: Record<string, string> = {
  '元素小计': '489985', '物料小计': '5438667.5', '加工费': '5.8', '单价': '5438673.3',
};
const SHOT = process.env.R260911_SHOT_DIR
  || '/tmp/claude-1000/-home-joii-project-cpq/1f85d6f5-a0ee-457f-9ae5-9cc114a41830/scratchpad/r260911-evidence';

async function login(page: Page) {
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/dashboard|change-password/, { timeout: 30000 });
  if (page.url().includes('change-password')) await page.goto('/dashboard');
}

test('AC-1 核价 Excel 视图 S0001 四列显示各页签总计', async ({ page }) => {
  fs.mkdirSync(SHOT, { recursive: true });
  await login(page);
  await page.goto(`/quotations/${QID}`);
  await page.waitForLoadState('networkidle');
  await page.locator('.ant-segmented-item').filter({ hasText: '核价单' }).first().click();
  await page.waitForTimeout(1200);
  await page.locator('.ant-segmented-item').filter({ hasText: 'Excel 视图' }).first().click();
  await page.waitForTimeout(2500);

  const th = await page.locator('.ant-table-thead th').allInnerTexts();
  const heads = th.map(s => s.trim());
  console.log('[AC-1] 表头 =', JSON.stringify(heads));
  // 断言空跑防护：列必须齐、行必须有
  for (const t of Object.keys(EXPECT)) expect(heads, `表头应含「${t}」`).toContain(t);
  const rows = page.locator('.ant-table-tbody tr.ant-table-row');
  const n = await rows.count();
  console.log('[AC-1] 数据行数 =', n);
  expect(n, '数据行不得为 0（否则断言空跑）').toBeGreaterThan(0);

  // 只取 S0001 的 7 个节点行（前 7 行）；按节点名核对，防止取错卡片
  const actual: Record<string, string[]> = {};
  const nodeCol = 0;
  const seenNodes: string[] = [];
  for (let i = 0; i < Math.min(n, S0001_NODES.length); i++) {
    const cells = (await rows.nth(i).locator('td').allInnerTexts()).map(s => s.trim());
    seenNodes.push(cells[nodeCol]);
    for (const t of Object.keys(EXPECT)) {
      (actual[t] ||= []).push(cells[heads.indexOf(t)]);
    }
  }
  console.log('[AC-1] 前 7 行节点 =', JSON.stringify(seenNodes));
  expect(seenNodes, 'S0001 的 BOM 节点顺序').toEqual(S0001_NODES);

  // 🚨 截图取证：BOM 表在横向滚动容器里，先滚到最右再截元素，避免截到恒真的左半屏
  const scroller = page.locator('.ant-table-content, .ant-table-body').first();
  await scroller.evaluate((el: HTMLElement) => { el.scrollLeft = el.scrollWidth; }).catch(() => {});
  await page.waitForTimeout(400);
  await page.locator('.ant-table').first()
    .screenshot({ path: path.join(SHOT, 'ac1-costing-excel-cols.png') }).catch(() => {});

  const bad: string[] = [];
  for (const [t, exp] of Object.entries(EXPECT)) {
    console.log(`[AC-1] ${t} 各行实际 = ${JSON.stringify(actual[t])}（期望每行 ${exp}）`);
    for (const [i, v] of (actual[t] || []).entries()) {
      if (Number(String(v).replace(/,/g, '')) !== Number(exp)) bad.push(`${S0001_NODES[i]} ${t}=${v}(期望${exp})`);
    }
  }
  expect(bad, `四列应显示对应页签总计；不符项：\n${bad.join('\n')}`).toEqual([]);
});
