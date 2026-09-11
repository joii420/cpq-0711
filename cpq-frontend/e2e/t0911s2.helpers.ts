/**
 * task-260911 · S2 片共用量具。
 * 🚫 本片未读任何被改动的实现文件；用例从 `需求文档.md ③` 的 AC 原文派生。
 *
 * 选择器沿用既有 E2E 实测结论（`r0910s2-ac1245.spec.ts`）：
 *   卡片 = `.qt-product-card`；页签 = `button.qt-tab-btn`（🚫 不是 `.ant-tabs-tab`，那会 0 命中且不报错）。
 *   🚫 不直接 goto `/quotations/{id}/edit` —— 实测那样进去 0 张卡片；必须 详情 →「编辑」→「下一步」。
 */
import { expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { loginAsAdmin } from './fixtures/auth';

const __dir = path.dirname(fileURLToPath(import.meta.url));
export const LABEL = process.env.T0911S2_LABEL || '未标注';
/** 证据落任务目录，🚫 不落 test-results/ —— 那儿下一轮开跑就被清空，等于没有证据。 */
export const OUT = process.env.T0911S2_OUT ||
  path.resolve(__dir, '../../dev-docs/task-260911-行级取数与合桶并存/证据/S2');
export const SHOT = path.join(OUT, `ui-${LABEL}`);
fs.mkdirSync(SHOT, { recursive: true });

export type LineItem = { id: string; sortOrder: number; custPartNo: string | null };
export type Case = { quotationId: string; quotationNumber: string; name: string; lineItems: LineItem[] };
export const CASES: Record<string, Case> = JSON.parse(
  fs.readFileSync(process.env.T0911S2_CASES || path.join(__dir, 't0911s2.cases.json'), 'utf-8'));

/** 一张表可能被拆成冻结列表 + 滚动列表 ⇒ 按行序把各可见表同一行拼起来。 */
async function readCardTab(page: Page, cardIdx: number) {
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
      Array.from(t.querySelectorAll('tbody tr')).forEach((tr, ri) => {
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

export async function gotoStep2(page: Page, c: Case, expectCards: number) {
  await loginAsAdmin(page);
  await page.goto(`/quotations/${c.quotationId}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(4000);
  const editBtn = page.locator('button').filter({ hasText: /^\s*编\s*辑\s*$/ }).first();
  await expect(editBtn, '详情页必须有「编辑」按钮（两字按钮实测中间有空格，用正则匹配）').toBeVisible();
  await editBtn.click();
  await page.waitForTimeout(6000);
  const nextBtn = page.locator('button').filter({ hasText: /下\s*一\s*步/ }).first();
  await expect(nextBtn, 'Step1 必须有「下一步」').toBeVisible();
  await nextBtn.click();
  await page.waitForTimeout(15000);
  const cards = page.locator('.qt-product-card');
  await cards.first().waitFor({ state: 'visible', timeout: 120_000 }).catch(() => {});
  const n = await cards.count();
  console.log(`[S2][${LABEL}] ${c.quotationNumber}(${c.name}) 进入 Step2，产品卡片 ${n} 张，URL=${page.url()}`);
  // 🔑 量具自证：卡片数必须与明细行数一致。0 张 = 量具失效，不是产品结论。
  expect(n, `🚨 Step2 卡片数 ${n} ≠ 明细行数 ${expectCards} ⇒ 量具失效，不许据此下任何产品结论`).toBe(expectCards);
}

/** 切到指定页签（对第 idx 张卡片）。 */
async function clickTab(page: Page, cardIdx: number, tab: string) {
  const card = page.locator('.qt-product-card').nth(cardIdx);
  const btn = card.locator('button.qt-tab-btn').filter({ hasText: new RegExp(`^\\s*${tab}\\s*$`) }).first();
  if (!(await btn.isVisible().catch(() => false))) {
    const tabs = (await card.locator('button.qt-tab-btn').allInnerTexts().catch(() => [] as string[]))
      .map((t) => t.replace(/\s+/g, ''));
    throw new Error(`🚨 卡片#${cardIdx} 内找不到页签「${tab}」。实际页签=${JSON.stringify(tabs)}`);
  }
  await btn.click();
  await page.waitForTimeout(2500);
}

/** 把每张卡片的指定页签都读出来（含卡片标题，用于卡片↔明细行映射）。 */
export async function dumpAllCards(page: Page, tab: string) {
  const n = await page.locator('.qt-product-card').count();
  const out: Array<{ cardIdx: number; title: string; headers: string[]; rows: string[][]; err: string }> = [];
  for (let i = 0; i < n; i++) {
    await clickTab(page, i, tab);
    const got = await readCardTab(page, i);
    const title = await page.locator('.qt-product-card').nth(i)
      .evaluate((el) => ((el as HTMLElement).innerText || '').split('\n').slice(0, 6).join(' / ').replace(/\s+/g, ' ').trim());
    out.push({ cardIdx: i, title, headers: got.headers, rows: got.rows, err: got.err });
    console.log(`[S2][${LABEL}] 卡片#${i} 标题="${title}"`);
    console.log(`[S2][${LABEL}] 卡片#${i} 「${tab}」表头=${JSON.stringify(got.headers)}`);
    got.rows.forEach((r, ri) => console.log(`[S2][${LABEL}] 卡片#${i}   行#${ri} = ${JSON.stringify(r)}`));
  }
  return out;
}

export function colIdx(headers: string[], keyword: string) {
  return headers.findIndex((h) => h.includes(keyword));
}

export async function shot(page: Page, name: string) {
  await page.screenshot({ path: path.join(SHOT, `${name}.png`), fullPage: true });
}

export function save(name: string, obj: unknown) {
  fs.writeFileSync(path.join(SHOT, name), JSON.stringify(obj, null, 1), 'utf-8');
}
