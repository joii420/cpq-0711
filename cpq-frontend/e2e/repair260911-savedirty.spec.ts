/**
 * repair-260911 · 补充实验（**不是 AC-9 的判定**，AC-9 的「改组成用量」在本单不可执行）
 *
 * 目的：AC-9 唯一能触发落库的动作是「保存」，而本页的保存有脏检查（实测点击后浮层显示
 *      「无改动，无需保存」，且 0 次 /api 往返）。核价卡片全字段 BASIC_DATA/FORMULA 不可编辑，
 *      于是没有任何办法把表单弄脏 → 后端修复到底有没有把正确值落进 costing_card_values，
 *      在 UI 路径上无从验证。
 *      本实验用**报价单自己的「备注」字段**（本单私有、可逆）把表单弄脏，跑一次真实保存，
 *      从而观测落库结果。备注在实验末尾还原为空。
 *
 * 环境变量：R260911_REMARK = 要写入备注的文本（空串表示清空）
 * 写入面：仅本报价单（remarks + 保存带来的 card_values 重算）。不动任何全局状态。
 */
import { test, expect } from '@playwright/test';
import { login, openStep2, switchView, cardOf, clickTab, dumpTable, colIdx, rowByPartNo, num, shot } from './repair260911-helpers';

const REMARK = process.env.R260911_REMARK ?? '';

test(`savedirty: 备注="${REMARK}" → 保存 → 观测落库`, async ({ page }) => {
  test.setTimeout(300_000);
  const api: string[] = [];
  page.on('response', async (r) => {
    if (!r.url().includes('/api/') || r.request().method() === 'GET') return;
    api.push(`RES ${r.status()} ${r.request().method()} ${r.url().replace(/^https?:\/\/[^/]+/, '')}`);
  });

  await login(page);
  await openStep2(page);
  await switchView(page, '核价单');
  const card = cardOf(page);
  await clickTab(card, 'BOM');
  const d0 = await dumpTable(card);
  console.log('[页面·保存前] 00081 =', num(rowByPartNo(d0, '00081')[colIdx(d0, '物料成本')]),
              ' 00003 =', num(rowByPartNo(d0, '00003')[colIdx(d0, '物料成本')]), ' tfoot =', d0.footer);

  // 走到「交易条款」步，填备注（唯一可编辑且与本次公式无关的字段）
  for (let i = 0; i < 2; i++) {
    const next = page.locator('button', { hasText: '下一步' }).first();
    await expect(next).toBeEnabled({ timeout: 20_000 });
    await next.click();
    await page.waitForTimeout(2500);
  }
  const remarkBox = page.locator('textarea[placeholder="输入备注信息"]').first();
  await expect(remarkBox, '交易条款步应有备注输入框').toBeVisible({ timeout: 20_000 });
  await remarkBox.fill(REMARK);
  await remarkBox.blur();
  await page.waitForTimeout(1500);

  const seen: string[] = [];
  const poll = setInterval(async () => {
    const t = await page.locator('.ant-message-notice, .ant-notification-notice').allInnerTexts().catch(() => []);
    for (const x of t) { const v = x.replace(/\s+/g, ' ').trim(); if (v && !seen.includes(v)) seen.push(v); }
  }, 300);
  await page.locator('button').filter({ hasText: /保\s*存\s*草\s*稿/ }).first().click();
  await page.waitForTimeout(25_000);
  clearInterval(poll);
  await shot(page, `savedirty-${REMARK ? 'set' : 'clear'}`);
  console.log('浮层文案:', JSON.stringify(seen));
  console.log('非 GET 的 /api 往返:', JSON.stringify(api));
  expect(seen.join('|'), '这次必须真的发生保存（不得再是「无改动，无需保存」）').not.toContain('无改动');
  expect(api.filter((a) => / (PUT|POST|PATCH) /.test(a)).length, '应至少有一次写请求').toBeGreaterThan(0);
});
