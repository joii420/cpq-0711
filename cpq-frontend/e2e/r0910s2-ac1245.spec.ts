/**
 * repair-260910 · **S2 片**：AC-1 / AC-2 / AC-4 / AC-5（真机 UI 量具）
 *
 * 用例只从 `问题说明.md ⑥ 验收标准` 的 AC 原文派生，**未读任何本次被改动的实现文件**。
 *
 * 载体：本片自建的三张单（造数前缀 `R0910S2`），ID 由 `R0910S2_CASES` 指向的 JSON 提供。
 *   · A = S0004 / 客编 RW-A004        → AC-1（页签 1 行且客编 = RW-A004）、AC-5（其余 5 页签零回归）
 *   · B = 0028-2609000015 / B1SELF-…  → AC-4（页签 1 行，不是 7 行）
 *   · C = S0004 / 客编为空            → AC-2（仍 1 行、客编列空、物料侧列有值）
 *
 * 🚨 三条防假绿（`test.md §3`）：
 *   ① AC-1/AC-4 的前置行数（4 行 / 7 行）由 `00-基线-改动前.txt` 实查确认 —— 判据维度取值 > 1，
 *      不会像 `repair-260908` 那样落在「恒为 1」的维度上。
 *   ② 量具自证：先断言「卡片存在 + 表格有表头 + 页面文本非空」，否则任何行数断言都是空跑。
 *      🚫 0 行不算通过 —— AC-2 尤其，它要防的正是「把取不到修成全空」。
 *   ③ 断言范围锁死在「本片自己建的这张单的这一个页签」，**不含任何全局计数**。
 *
 * 🚨 选择器（既有 E2E 实测结论，抄自 `r260910.helpers.ts`，勿换 antd 原生类名）：
 *   卡片 = `.qt-product-card`；页签 = `button.qt-tab-btn`（🚫 不是 `.ant-tabs-tab`，那会 0 命中且不报错）。
 *   🚫 也不直接 `goto('/quotations/{id}/edit')` —— 既有实测那样进去得 0 张卡片；必须 详情 →「编辑」→「下一步」。
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { loginAsAdmin } from './fixtures/auth';

const __dir = path.dirname(fileURLToPath(import.meta.url));
const LABEL = process.env.R0910S2_LABEL || '未标注';
/** 证据落在任务目录，🚫 不落 test-results/ —— 那儿下一轮开跑就被清空，等于没有证据。 */
const OUT = process.env.R0910S2_OUT ||
  path.resolve(__dir, '../../dev-docs/task-260819-取数配置器/repair-260910-客户产品编号维度与删行错位/证据/S2');
const SHOT = path.join(OUT, `ui-${LABEL}`);
fs.mkdirSync(SHOT, { recursive: true });

type Case = { quotationId: string; quotationNumber: string; lineItemId: string; partNo: string; custPartNo: string | null };
const CASES: Record<string, Case> = JSON.parse(
  fs.readFileSync(process.env.R0910S2_CASES || path.join(__dir, 'r0910s2.cases.json'), 'utf-8'));

const OTHER_TABS = ['BOM', '材质元素', '产品单价', '加工费', '自制加工费'];

/** 一张表可能被拆成冻结列表 + 滚动列表 ⇒ 按行序把各可见表同一行拼起来。 */
async function readCardTab(page: import('@playwright/test').Page, cardIdx = 0) {
  return await page.evaluate((i) => {
    const norm = (s: string) => (s || '').replace(/\s+/g, ' ').trim();
    const card = document.querySelectorAll('.qt-product-card')[i] as HTMLElement | undefined;
    if (!card) return { err: 'no-card', headers: [] as string[], rows: [] as string[][], cardText: '' };
    const tables = (Array.from(card.querySelectorAll('table')) as HTMLElement[])
      .filter((t) => t.offsetParent !== null);
    if (!tables.length) return { err: 'no-visible-table', headers: [], rows: [], cardText: norm(card.innerText) };
    const headers: string[] = [];
    const rowCells: string[][] = [];
    for (const t of tables) {
      headers.push(...Array.from(t.querySelectorAll('thead th')).map((th) => norm((th as HTMLElement).textContent || '')));
      const trs = Array.from(t.querySelectorAll('tbody tr'));
      trs.forEach((tr, ri) => {
        const cells = Array.from(tr.querySelectorAll('td')).map((td) => {
          const inp = td.querySelector('input,textarea') as HTMLInputElement | null;
          return norm(inp ? inp.value : (td as HTMLElement).textContent || '');
        });
        rowCells[ri] = (rowCells[ri] || []).concat(cells);
      });
    }
    return { err: '', headers, rows: rowCells, cardText: norm(card.innerText) };
  }, cardIdx);
}

async function gotoStep2(page: import('@playwright/test').Page, c: Case) {
  await loginAsAdmin(page);
  await page.goto(`/quotations/${c.quotationId}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(4000);
  const editBtn = page.locator('button').filter({ hasText: /^\s*编\s*辑\s*$/ }).first();
  await expect(editBtn, '详情页必须有「编辑」按钮（两字按钮实测中间有空格，用正则匹配）').toBeVisible();
  await editBtn.click();
  await page.waitForTimeout(5000);
  const nextBtn = page.locator('button').filter({ hasText: /下\s*一\s*步/ }).first();
  await expect(nextBtn, 'Step1 必须有「下一步」').toBeVisible();
  await nextBtn.click();
  await page.waitForTimeout(12000);
  const cards = page.locator('.qt-product-card');
  await cards.first().waitFor({ state: 'visible', timeout: 90_000 }).catch(() => {});
  const n = await cards.count();
  console.log(`[S2][${LABEL}] ${c.quotationNumber} 进入 Step2，产品卡片 ${n} 张，URL=${page.url()}`);
  // 🔑 量具自证：卡片数必须正好 1（本片每张单只放 1 行明细）。0 张 = 量具失效，不是产品结论。
  expect(n, '🚨 Step2 上产品卡片 0 张 ⇒ 量具失效，不许据此下任何产品结论').toBe(1);
}

/** 卡片上真实存在的页签清单 —— AC-5 的对照量之一（页签增减本身就是回归）。 */
async function tabNames(page: import('@playwright/test').Page): Promise<string[]> {
  const card = page.locator('.qt-product-card').first();
  return (await card.locator('button.qt-tab-btn').allInnerTexts().catch(() => [] as string[]))
    .map((t) => t.replace(/\s+/g, '').trim());
}

/** 切页签并读出数据行；带「结果非空」保护。
 *  `optional=true` 时，页签在 UI 上不存在返回 null 而不抛 —— 用于 AC-5 文字与实际 UI 的出入
 *  （实测该模板卡片只有 产品/BOM/材质元素/加工费/自制加工费 五个页签，**没有「产品单价」**，
 *   改动前即如此，不是本次引入；产品单价改由数据层 batch-expand / quote_card_values 覆盖）。 */
async function readTabOpt(page: import('@playwright/test').Page, tab: string, tag: string, optional = false) {
  const card = page.locator('.qt-product-card').first();
  const btn = card.locator('button.qt-tab-btn').filter({ hasText: new RegExp(`^\\s*${tab}\\s*$`) }).first();
  if (!(await btn.isVisible().catch(() => false))) {
    const tabs = await tabNames(page);
    if (optional) {
      console.log(`[S2][${LABEL}] 页签「${tab}」在 UI 上不存在（实际页签=${JSON.stringify(tabs)}）`);
      return null;
    }
    throw new Error(`🚨 卡片内找不到页签「${tab}」。实际页签=${JSON.stringify(tabs)}`);
  }
  await btn.click();
  await page.waitForTimeout(4000);
  const got = await readCardTab(page, 0);
  await page.screenshot({ path: path.join(SHOT, `${tag}.png`), fullPage: true });
  console.log(`[S2][${LABEL}] ${tag} 表头=${JSON.stringify(got.headers)}`);
  got.rows.forEach((r, i) => console.log(`[S2][${LABEL}]   行#${i} = ${JSON.stringify(r)}`));
  return got;
}

/** 非可选版：页签必须存在，否则硬失败（返回类型非 null，省去每个断言前的 `!`）。 */
async function readTab(page: import('@playwright/test').Page, tab: string, tag: string) {
  const r = await readTabOpt(page, tab, tag, false);
  if (!r) throw new Error(`🚨 readTab 意外返回 null（tab=${tab}）`);
  return r;
}

/** 本页签的小计文本（AC-5 要「行数与小计」逐字节相同）。 */
async function readSubtotal(page: import('@playwright/test').Page) {
  return await page.evaluate(() => {
    const card = document.querySelector('.qt-product-card') as HTMLElement | null;
    if (!card) return '';
    const m = (card.innerText || '').match(/小计[^\n]*/g);
    return (m || []).join(' | ').replace(/\s+/g, ' ').trim();
  });
}

// 🚫 刻意**不用** `mode: 'serial'`：四条用例载体互不相同、彼此独立，
//    serial 会让 AC-1 一红就把 AC-2/4/5 判成 "did not run" ——
//    改动前那一轮正需要它们都跑出来当基线（AC-5 的 A 侧一旦漏采就再也拿不回来）。
//    串行性由 config 的 `workers: 1` 保证，不靠 serial。

test('AC-1 · S0004 产品页签只渲染 1 行，且客编 = 该明细行的 RW-A004', async ({ page }) => {
  test.setTimeout(420_000);
  const c = CASES['A'];
  await gotoStep2(page, c);
  const got = await readTab(page, '产品', 'AC-1-产品页签');

  // ② 量具自证 —— 表头必须读得到，否则下面的行数断言全是空跑
  expect(got.err, `读卡片失败：${got.err}`).toBe('');
  expect(got.headers.length, '🚨 产品页签表头 0 列 ⇒ 量具失效').toBeGreaterThan(0);
  expect(got.cardText.length, '🚨 卡片文本为空 ⇒ 量具失效').toBeGreaterThan(50);

  const idx = got.headers.findIndex((h) => h.includes('客户产品编号'));
  expect(idx, `🚨 表头里没有「客户产品编号」列，实际=${JSON.stringify(got.headers)}`).toBeGreaterThanOrEqual(0);

  fs.writeFileSync(path.join(SHOT, 'AC-1-产品页签.json'),
    JSON.stringify({ label: LABEL, case: c, headers: got.headers, rows: got.rows }, null, 1), 'utf-8');

  // ③ AC 原文断言（范围锁死在本片自建的这张单的这一个页签，无全局计数）
  expect(got.rows.length, `AC-1：产品页签应渲染 1 行，实际 ${got.rows.length} 行 → ${JSON.stringify(got.rows)}`).toBe(1);
  expect(got.rows[0][idx], `AC-1：客户产品编号应 = ${c.custPartNo}`).toBe(c.custPartNo);
});

test('AC-4 · 0028-2609000015（客户料号表 7 行）产品页签只渲染 1 行', async ({ page }) => {
  test.setTimeout(420_000);
  const c = CASES['B'];
  await gotoStep2(page, c);
  const got = await readTab(page, '产品', 'AC-4-产品页签');
  expect(got.err).toBe('');
  expect(got.headers.length, '🚨 表头 0 列 ⇒ 量具失效').toBeGreaterThan(0);
  const idx = got.headers.findIndex((h) => h.includes('客户产品编号'));
  expect(idx, '🚨 找不到「客户产品编号」列').toBeGreaterThanOrEqual(0);
  fs.writeFileSync(path.join(SHOT, 'AC-4-产品页签.json'),
    JSON.stringify({ label: LABEL, case: c, headers: got.headers, rows: got.rows }, null, 1), 'utf-8');
  expect(got.rows.length, `AC-4：应 1 行（改动前实测 7 行），实际 ${got.rows.length} → ${JSON.stringify(got.rows)}`).toBe(1);
  expect(got.rows[0][idx], `AC-4：客编应 = ${c.custPartNo}`).toBe(c.custPartNo);
});

test('AC-2 · 客编为空的明细行仍渲染 1 行（🚫 不是 0 行），客编列空、物料侧列有值', async ({ page }) => {
  test.setTimeout(420_000);
  const c = CASES['C'];
  await gotoStep2(page, c);
  const got = await readTab(page, '产品', 'AC-2-产品页签');
  expect(got.err).toBe('');
  expect(got.headers.length, '🚨 表头 0 列 ⇒ 量具失效').toBeGreaterThan(0);
  fs.writeFileSync(path.join(SHOT, 'AC-2-产品页签.json'),
    JSON.stringify({ label: LABEL, case: c, headers: got.headers, rows: got.rows }, null, 1), 'utf-8');

  // 🚨 这一条是 AC-2 的全部要害：谓词若写进 WHERE，这里就是 0 行且不报错
  expect(got.rows.length, `AC-2：应仍渲染 1 行，🚫 0 行是失败不是通过；实际 ${got.rows.length}`).toBe(1);

  const cpIdx = got.headers.findIndex((h) => h.includes('客户产品编号'));
  expect(cpIdx).toBeGreaterThanOrEqual(0);
  expect(got.rows[0][cpIdx], 'AC-2：客户产品编号列应为空').toBe('');

  // 物料侧列必须有值（防「修成全空」）
  for (const col of ['品名', '规格', '单重']) {
    const i = got.headers.findIndex((h) => h.includes(col));
    expect(i, `🚨 表头里没有「${col}」列，实际=${JSON.stringify(got.headers)}`).toBeGreaterThanOrEqual(0);
    expect(got.rows[0][i], `AC-2：物料侧「${col}」不得为空`).not.toBe('');
  }
});

test('AC-5 · 其余 5 个页签的行数与小计（采样落盘，供 A/B 逐字节对照）', async ({ page }) => {
  test.setTimeout(600_000);
  const c = CASES['A'];
  await gotoStep2(page, c);
  const uiTabs = await tabNames(page);
  console.log(`[S2][${LABEL}] AC-5 卡片页签清单 = ${JSON.stringify(uiTabs)}`);
  const snap: Record<string, { present: boolean; rows: number; subtotal: string; cells: string[][] }> = {};
  for (const tab of OTHER_TABS) {
    const got = await readTabOpt(page, tab, `AC-5-${tab}`, true);
    if (got === null) { snap[tab] = { present: false, rows: -1, subtotal: '(页签不存在)', cells: [] }; continue; }
    expect(got.err, `读页签「${tab}」失败：${got.err}`).toBe('');
    const sub = await readSubtotal(page);
    snap[tab] = { present: true, rows: got.rows.length, subtotal: sub, cells: got.rows };
    console.log(`[S2][${LABEL}] AC-5 页签「${tab}」行数=${got.rows.length} 小计文本="${sub}"`);
  }
  // 🔑 量具自证：不能全是 0 行 / 全不存在，否则这份"对照"两侧都是空的，恒相等 = 零证据
  const nonEmpty = OTHER_TABS.filter((t) => snap[t].present && snap[t].rows > 0).length;
  expect(nonEmpty, '🚨 其余页签全部 0 行或全不存在 ⇒ 对照样本为空，恒相等，证不出任何事').toBeGreaterThan(0);
  fs.writeFileSync(path.join(SHOT, 'AC-5-其余5页签.json'),
    JSON.stringify({ label: LABEL, case: c, uiTabs, snap }, null, 1), 'utf-8');
});
