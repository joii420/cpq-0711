/**
 * A/B 同型对比（testing.md §4.1.5 第3问「是本次改动引入的吗」）：
 *   同一张单的详情页，在 **worktree(5211/8211，含本次改动)** 与 **主工作区(5174/8081，master)** 各测一次
 *   `.qt-template-badge` 计数。🚫 只做只读导航，不启停任何共享服务。
 */
import { test, expect } from '@playwright/test';
import * as H from './t260910.helpers';

const NUM = 'QT-20260909-0794';

async function measure(page: any, base: string) {
  await page.goto(`${base}/login`);
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForTimeout(5000);
  const qid = H.quotationIdOf(NUM);
  await page.goto(`${base}/quotations/${qid}`);
  await page.waitForTimeout(16000);
  return page.evaluate(() => ({
    cards: document.querySelectorAll('.qt-product-card').length,
    tplBadges: Array.from(document.querySelectorAll('.qt-template-badge')).map((e) => (e as HTMLElement).innerText.replace(/\s+/g, ' ').trim()),
    anyCategoryText: /默认分类/.test(document.body.innerText),
    firstCardLeft: (document.querySelector('.qt-product-card .qt-card-header-left') as HTMLElement)?.innerText?.replace(/\s+/g, ' ').trim() ?? null,
  }));
}

test('A/B · 详情页 .qt-template-badge：worktree vs 主工作区(master)', async ({ page }) => {
  test.setTimeout(300_000);
  const B = await measure(page, 'http://localhost:5211');           // 本次改动
  console.log('[B worktree 5211] ' + JSON.stringify(B));
  const A = await measure(page, 'http://localhost:5174');           // master 对照（只读打开）
  console.log('[A master 5174] ' + JSON.stringify(A));
  H.writeEvidence('ac14-AB对比-详情页徽标.txt',
    `单=${NUM}\nA(master 5174)=${JSON.stringify(A, null, 1)}\nB(worktree 5211)=${JSON.stringify(B, null, 1)}\n` +
    `结论：master 徽标数=${A.tplBadges.length}，worktree 徽标数=${B.tplBadges.length}\n`);
  expect(A.cards, 'A 侧(master)详情页应能渲染卡片，否则对照本身无效').toBeGreaterThan(0);
  expect(B.cards, 'B 侧(worktree)详情页应能渲染卡片').toBeGreaterThan(0);
  console.log(`[A/B 结论] master=${A.tplBadges.length} 个徽标 / worktree=${B.tplBadges.length} 个徽标`);
});
