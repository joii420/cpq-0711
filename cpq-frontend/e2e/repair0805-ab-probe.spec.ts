/**
 * repair-0805 —— A/B 探针（**不是验收用例**，是归因工具）
 *
 * 同一段采集逻辑跑两次：
 *   PW_BASE_URL=http://localhost:5199  → worktree（含本次修复）
 *   PW_BASE_URL=http://localhost:5174  → 主仓 master（不含本次修复）
 * 只 console.log 不断言，靠对比两份输出判断某现象是「本次引入」还是「master 既有」。
 */
import { test, Page } from '@playwright/test';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';

const QUOTATION_ID = 'be95ded9-9f31-4a18-95ac-ba856a4d51cc';
const TAB_NAME = '物料';
const FORMULA_COLUMNS = [
  '来料回收费', '来料财务费', '材料成本', '材料损耗成本', '来料损耗率',
  '来料加工费', '回收成本', '公式10', '原材料成本', '材料价格', '铆钉额外费用',
];

async function grabTable(page: Page) {
  return await page.evaluate(() => {
    const tables = Array.from(document.querySelectorAll('table.qt-cost-table'))
      .filter(t => (t as HTMLElement).offsetParent !== null);
    if (tables.length === 0) return null;
    const t = tables[0];
    const txt = (el: Element | null | undefined) => {
      if (!el) return '';
      const input = el.querySelector('input, textarea') as HTMLInputElement | null;
      if (input) return (input.value ?? '').trim();
      return (el.textContent ?? '').replace(/\s+/g, ' ').trim();
    };
    const headers = Array.from(t.querySelectorAll('thead tr')).slice(-1)[0];
    return {
      headerTexts: headers ? Array.from(headers.children).map(txt) : [],
      bodyRows: Array.from(t.querySelectorAll('tbody tr')).map(tr => Array.from(tr.children).map(txt)),
      footRows: Array.from(t.querySelectorAll('tfoot tr')).map(tr => Array.from(tr.children).map(txt)),
    };
  });
}

async function openTab(page: Page, name: string) {
  const tab = page.locator('.qt-tab-btn').filter({ hasText: new RegExp(`^${name}$`) }).first();
  if (!(await tab.isVisible().catch(() => false))) return false;
  await tab.click();
  await page.waitForTimeout(1500);
  return true;
}

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

test('AB 探针：详情页物料公式列 + 列小计 + 核价单整列破折号统计', async ({ page, baseURL }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(240_000);
  console.log(`\n########## BASE_URL = ${baseURL} ##########`);
  await loginAsAdmin(page);

  // ── 详情页 · 报价单 · 物料 ───────────────────────────────────────────
  await page.goto(`/quotations/${QUOTATION_ID}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);
  const opened = await openTab(page, TAB_NAME);
  console.log(`[AB][详情/报价] 「${TAB_NAME}」页签可见 = ${opened}`);
  const t = await grabTable(page);
  if (t) {
    const idxOf = (c: string) => t.headerTexts.findIndex(h => h === c);
    let blanks = 0;
    const matrix: Record<string, string[]> = {};
    for (const c of FORMULA_COLUMNS) {
      const i = idxOf(c);
      const vals = i < 0 ? [] : t.bodyRows.map(r => r[i] ?? '');
      matrix[c] = vals;
      vals.forEach(v => { const s = v.trim(); if (s === '' || s === '—' || s.startsWith('加载中')) blanks++; });
    }
    console.log(`[AB][详情/报价] 行=${t.bodyRows.length} 公式列空格数=${blanks}`);
    console.log(`[AB][详情/报价] 材料成本列 = ${JSON.stringify(matrix['材料成本'])}`);
    console.log(`[AB][详情/报价] 列小计「材料成本」= "${t.footRows[0]?.[idxOf('材料成本')] ?? ''}"`);
    console.log(`[AB][详情/报价] tfoot 全部行 = ${JSON.stringify(t.footRows)}`);
  } else {
    console.log('[AB][详情/报价] 未抓到可见表格');
  }

  // ── 详情页 · 核价单 ─────────────────────────────────────────────────
  const seg = page.locator('.ant-segmented-item').filter({ hasText: '核价单' }).first();
  if (await seg.isVisible().catch(() => false)) {
    await seg.click();
    await page.waitForLoadState('networkidle').catch(() => {});
    await page.waitForTimeout(3500);
    const loading = await page.locator('text=加载中').count();
    const recompute = await page.locator('text=待重算').count();
    const sentinel = await page.locator('text=__loading__').count();
    console.log(`[AB][详情/核价] '加载中'=${loading} '待重算'=${recompute} '__loading__'=${sentinel}`);
    const btns = page.locator('.qt-tab-btn');
    const n = await btns.count();
    console.log(`[AB][详情/核价] 页签数=${n}`);
    const dash: string[] = [];
    const summary: string[] = [];
    for (let i = 0; i < n; i++) {
      const name = (await btns.nth(i).textContent())?.trim() ?? `#${i}`;
      await btns.nth(i).click();
      await page.waitForTimeout(700);
      const ct = await grabTable(page);
      if (!ct || ct.bodyRows.length === 0) { summary.push(`${name}:0行`); continue; }
      let cols = 0;
      for (let c = 0; c < ct.headerTexts.length; c++) {
        const vals = ct.bodyRows.map(r => (r[c] ?? '').trim());
        if (vals.length && vals.every(v => v === '—')) { dash.push(`${name}/${ct.headerTexts[c]}`); cols++; }
      }
      summary.push(`${name}:${ct.bodyRows.length}行/${ct.headerTexts.length}列/整列—=${cols}`);
    }
    console.log(`[AB][详情/核价] 各页签: ${summary.join('  |  ')}`);
    console.log(`[AB][详情/核价] 整列'—'总数=${dash.length}`);
    console.log(`[AB][详情/核价] 整列'—'明细=${JSON.stringify(dash)}`);
  } else {
    console.log('[AB][详情/核价] 「核价单」切换项不可见');
  }
  console.log(`########## END BASE_URL = ${baseURL} ##########\n`);
});
