/**
 * repair-260910 · S1 片 · **DOM 探针**（不认领 AC，纯取证）
 *
 * 用途：AC-2 阳性对照测到「非叶子行也没有 `.ant-select`」时，先分辨是**量具错**还是**产品缺陷**。
 * 既有 `quotation-bom-tree.spec.ts` 用的是原生 `select[disabled]`，与我按 antd 写的选择器不同 ——
 * 这里把版本列每一行的 outerHTML 原样打出来，让判断基于事实而不是猜测。
 */
import { test } from '@playwright/test';
import { uiLogin, enterCostingCard, cardOf, switchTabInCard, saveEvidence, TAB_TREE } from './r260910.helpers';

test('DOM 探针 · 版本列每行的真实 DOM', async ({ page }) => {
  test.setTimeout(300_000);
  await uiLogin(page);
  await enterCostingCard(page);
  const card = await cardOf(page, 'S0001');
  await switchTabInCard(card, TAB_TREE);

  const dump = await card.evaluate((root: any) => {
    const norm = (s: string) => (s || '').replace(/\s+/g, ' ').trim();
    const tables = Array.from(root.querySelectorAll('table')).filter((t: any) => t.offsetParent !== null) as any[];
    tables.sort((a: any, b: any) => b.querySelectorAll('tbody tr').length - a.querySelectorAll('tbody tr').length);
    const t = tables[0];
    const headers = Array.from(t.querySelectorAll('thead th')).map((th: any) => norm(th.textContent));
    const vi = headers.findIndex((h: string) => /^版本/.test(h));
    return Array.from(t.querySelectorAll('tbody tr')).map((tr: any) => {
      const tds = Array.from(tr.querySelectorAll('td')) as any[];
      const part = norm(tds[0] ? tds[0].textContent : '').replace(/^[▼▶►▾\s]+/, '');
      const cell = tds[vi];
      return {
        part,
        html: cell ? cell.innerHTML.slice(0, 400) : '(无该列)',
        nativeSelect: cell ? cell.querySelectorAll('select').length : -1,
        nativeSelectDisabled: cell ? cell.querySelectorAll('select[disabled]').length : -1,
        antdSelect: cell ? cell.querySelectorAll('.ant-select').length : -1,
        text: cell ? norm(cell.textContent) : '',
      };
    });
  });
  const out = dump.map((d: any) =>
    `${d.part}\n  text="${d.text}"  native<select>=${d.nativeSelect}(disabled ${d.nativeSelectDisabled})  .ant-select=${d.antdSelect}\n  html: ${d.html}`
  ).join('\n');
  console.log('[R260910][DOM探针]\n' + out);
  saveEvidence('90-DOM探针-版本列', out);
});
