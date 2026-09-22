/** 只读探针：编辑页第 2 步产品卡 DOM 结构（放行 GET + batch-expand，其余写请求拦截）。 */
import { test } from '@playwright/test';
import { apiLogin, evid, sql } from './task260920-s2.helpers';
test('probe3', async ({ page }) => {
  test.setTimeout(240_000);
  await apiLogin(page);
  const blocked: string[] = [];
  await page.route('**/api/**', async r => { const q = r.request(); if (q.method() === 'GET' || /\/auth\/(login|refresh)|\/components\/batch-expand/.test(q.url())) return r.fallback(); blocked.push(`${q.method()} ${q.url()}`); return r.abort(); });
  const qid = sql<{ id: string }>(`select id::text from quotation where quotation_number='QT-20260911-0842'`)[0].id;
  await page.goto(`/quotations/${qid}/edit`);
  await page.waitForLoadState('networkidle', { timeout: 60_000 }).catch(() => {});
  await page.getByRole('button', { name: /下一步/ }).first().click().catch(e => console.log('next fail', String(e).slice(0, 100)));
  await page.waitForLoadState('networkidle', { timeout: 120_000 }).catch(() => {});
  for (let i = 0; i < 30; i++) { if (await page.getByText('材质元素', { exact: true }).count()) break; await page.waitForTimeout(2000); }
  await page.screenshot({ path: '/home/joii/project/cpq/.claude/worktrees/task-260920-review-list-lazy-compute/dev-docs/task-260729-客户价格调整策略和价格版本/task-260920-审核列表秒开与按需试算/证据/测试/S-2/R5-执行/AC-23-probe3.png' });
  console.log('steps', (await page.locator('.ant-steps-item').allInnerTexts()).join('|').replace(/\s+/g, ''));
  console.log('body', (await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 600));
  const html = await page.evaluate(() => {
    const t = [...document.querySelectorAll('*')].find(e => e.children.length === 0 && (e.textContent || '').trim() === '材质元素') as HTMLElement | undefined;
    if (!t) return 'NO-TAB-TEXT';
    const chain: string[] = []; let e: HTMLElement | null = t;
    for (let i = 0; i < 9 && e; i++) { chain.push(`${e.tagName}.${(e.className || '').toString().slice(0, 120)}`); e = e.parentElement; }
    let card: HTMLElement | null = t; for (let i = 0; i < 7 && card; i++) card = card.parentElement;
    return chain.join('\n') + '\n----\n' + (card?.outerHTML || '').slice(0, 2500);
  });
  evid('probe3-dom.txt', html + '\n\nblocked=' + JSON.stringify(blocked) + '\n加载中=' + (await page.getByText('加载中…').count()));
  console.log(html.slice(0, 1800)); console.log('blocked', blocked.length);
});
