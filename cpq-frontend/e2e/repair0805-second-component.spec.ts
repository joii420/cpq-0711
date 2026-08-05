/**
 * repair-0805 —— C 项：第二个组件（COMP-0157「物料」/ 施耐德BUG2 v1.3）的端到端验证探针
 *
 * 立项理由：全库只有 `QT-20260804-0068` 一张单的**冻结结构**带 `formulaId`（V375 刻意没迁
 * `quotation_view_structure`），所以「修复对别的组件也生效」此前无据。本探针新建一张单
 * （`POST /quotations/{src}/copy` + `ensure-card-values` + `refresh-card-snapshot`，冻结结构
 * 由后端按当前组件定义重新生成 → 带 formulaId），换一个组件把同一条链路再跑一遍。
 *
 * COMP-0157「物料」8 个 FORMULA 列：**7 个绑 formulaId**，`材料成本` 未绑 —— 天然的对照组：
 * 修复缺失时应当是「7 列整列 '—'、材料成本仍出数」，而不是 8 列一起坏。
 *
 * 只 console.log 不硬断言（归因探针）。用法见 repair0805-ab-probe.spec.ts 文件头。
 */
import { test, Page } from '@playwright/test';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';

const QUOTATION_ID = process.env.R0805_QID || '6118811d-a482-4570-9429-56e1a5e7616b';  // QT-20260805-0070
const TAB_NAME = '物料';
/** 7 个绑了 formulaId 的列（本次缺陷的靶子）。 */
const BOUND_COLUMNS = ['来料回收费', '来料财务费', '材料损耗成本', '来料损耗率', '来料加工费', '回收成本', '公式10'];
/** 未绑 formulaId 的对照列。 */
const UNBOUND_COLUMN = '材料成本';

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
    };
  });
}

function report(tag: string, t: { headerTexts: string[]; bodyRows: string[][] } | null) {
  if (!t) { console.log(`[C][${tag}] 未抓到表格`); return; }
  const col = (c: string) => {
    const i = t.headerTexts.findIndex(h => h === c);
    return i < 0 ? null : t.bodyRows.map(r => (r[i] ?? '').trim());
  };
  let dashCols = 0;
  console.log(`[C][${tag}] 行=${t.bodyRows.length}`);
  for (const c of [...BOUND_COLUMNS, UNBOUND_COLUMN]) {
    const v = col(c);
    const allDash = !!v && v.length > 0 && v.every(x => x === '—');
    if (allDash && c !== UNBOUND_COLUMN) dashCols++;
    console.log(`  [C][${tag}] ${c.padEnd(8)} ${v === null ? '(无此列)' : JSON.stringify(v)}${allDash ? '   ← 整列 —' : ''}`);
  }
  console.log(`[C][${tag}] 7 个绑 id 列中整列 '—' 的数量 = ${dashCols}`);
}

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

test('C · 第二组件 COMP-0157 编辑页 + 详情页 物料 公式列', async ({ page, baseURL }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(240_000);
  console.log(`\n########## C 探针 BASE_URL=${baseURL} quotation=${QUOTATION_ID} ##########`);
  await loginAsAdmin(page);

  // 详情页（只读，不触发 autosave —— 优先用它，避免写库）
  await page.goto(`/quotations/${QUOTATION_ID}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);
  const tab = page.locator('.qt-tab-btn').filter({ hasText: new RegExp(`^${TAB_NAME}$`) }).first();
  if (await tab.isVisible().catch(() => false)) {
    await tab.click();
    await page.waitForTimeout(1500);
    report('详情页', await grabTable(page));
  } else {
    console.log('[C][详情页] 物料页签不可见');
  }
  console.log(`########## END C 探针 ##########\n`);
});
