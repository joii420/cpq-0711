/**
 * 主线亲验 v2 · AC-9 / AC-10 / AC-21（编辑页 Step2 ·「产品」页签）
 *
 * 相对 v1 的三处更正：
 *  ① v1 的 AC-10 用的是 **BOM** 页签，而 AC-10 原文写的是**「产品」页签** —— 换错了主体。
 *  ② v1 的「保存是否真发生」自证读 `it.cardSnapshotAt` —— **DTO 上没有这个字段** ⇒ 恒 null，
 *     是典型的「无论好坏都给同一答案」的判据。改读 `userDataVersion`（saveDraft 必 +1）。
 *  ③ v1 只 console.log 不 expect ⇒ 跑绿了也什么都没证明。本版全部落 expect。
 *
 * 结构（实测）：页签 = BUTTON.qt-tab-btn；数据行 = 含 <input> 的 <tr>；每卡一个 <table>。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs'; import * as path from 'path';
import { fileURLToPath } from 'url';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';

const SHOT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'screenshots', 'qinyan-260909-v2');
fs.mkdirSync(SHOT, { recursive: true });
const QID = process.env.Q_WRITE!;

async function toStep2(p: Page) {
  await p.goto(`/quotations/${QID}/edit`);
  await p.waitForLoadState('networkidle'); await p.waitForTimeout(5000);
  const next = p.getByRole('button', { name: /下\s*一\s*步/ });
  if (await next.count() && await next.first().isEnabled().catch(() => false)) {
    await next.first().click(); await p.waitForTimeout(7000);
  }
}
const rowsPerCard = (p: Page) => p.evaluate(() => Array.from(document.querySelectorAll('table'))
  .map(t => Array.from(t.querySelectorAll('tr')).filter(r => r.querySelector('input')).length));
async function clickTabAll(p: Page, name: string) {
  const hit = await p.evaluate((n) => {
    const bs = Array.from(document.querySelectorAll('button.qt-tab-btn'))
      .filter(b => (b as HTMLElement).innerText.trim() === n);
    bs.forEach(b => (b as HTMLElement).click());
    return bs.length;
  }, name);
  await p.waitForTimeout(3000);
  return hit;
}
const version = (p: Page) => p.evaluate(async (qid) => {
  const r = await fetch(`/api/cpq/quotations/${qid}`, { credentials: 'include' });
  const j = await r.json(); return (j.data ?? j)?.userDataVersion ?? null;
}, QID);

let up = false;
test.beforeAll(async () => { up = await isBackendUp(); });

test('AC-9 + AC-10 + AC-21 ·「产品」页签', async ({ page }) => {
  test.setTimeout(900000); test.skip(!up, '后端未启动');

  // 抓 /draft 的真实状态码 + 冲突弹层文案（AC-21 的直接判据）
  const draftRes: string[] = [];
  page.on('response', async r => {
    if (/\/draft\b/.test(r.url())) draftRes.push(`${r.status()}`);
  });

  await loginAsAdmin(page);
  await toStep2(page);

  // ── 量具自证 ①：选择器必须真的命中，0 = 找不到元素，不是「0 行」 ──
  const tabBtns = await page.locator('button.qt-tab-btn').count();
  expect(tabBtns, '找不到 button.qt-tab-btn ⇒ 量具失效（不是"没有页签"）').toBeGreaterThan(0);
  const hitProd = await clickTabAll(page, '产品');
  expect(hitProd, '没有一个卡片上有「产品」页签 ⇒ 量具失效').toBeGreaterThan(0);

  const t1 = await rowsPerCard(page);
  expect(t1.length, '一个 <table> 都没有 ⇒ 量具失效').toBeGreaterThan(0);
  console.log(`[量具] 页签按钮 ${tabBtns} 个；「产品」页签命中 ${hitProd} 个卡片`);
  console.log(`[AC-9] 时点1 首次进入   = ${JSON.stringify(t1)}`);

  // ── 量具自证 ②：换到 BOM 行数必须变 —— 证明它不是恒 1 ──
  await clickTabAll(page, 'BOM');
  const bom = await rowsPerCard(page);
  console.log(`[量具] 对照 BOM 页签     = ${JSON.stringify(bom)}  ← 必须 ≠ 产品，否则判据恒定`);
  expect(JSON.stringify(bom), '产品与 BOM 行数完全相同 ⇒ 量具没随页签变，判据恒定不可用')
    .not.toBe(JSON.stringify(t1));

  await clickTabAll(page, '产品');
  const t2 = await rowsPerCard(page);
  console.log(`[AC-9] 时点2 切走再切回 = ${JSON.stringify(t2)}`);

  await toStep2(page); await clickTabAll(page, '产品');
  const t3 = await rowsPerCard(page);
  console.log(`[AC-9] 时点3 F5 刷新后   = ${JSON.stringify(t3)}`);
  await clickTabAll(page, 'BOM'); await clickTabAll(page, '产品');
  const t4 = await rowsPerCard(page);
  console.log(`[AC-9] 时点4 再切回     = ${JSON.stringify(t4)}`);
  await page.screenshot({ path: path.join(SHOT, 'AC9-四时点-产品页签.png'), fullPage: true });

  expect(t2, 'AC-9 时点2 ≠ 时点1').toEqual(t1);
  expect(t3, 'AC-9 时点3 ≠ 时点1').toEqual(t1);
  expect(t4, 'AC-9 时点4 ≠ 时点1').toEqual(t1);
  expect(t1.every(n => n === 1), `AC-9/AC-1 终态每卡应恰 1 行，实得 ${JSON.stringify(t1)}`).toBe(true);

  // ══════════════ AC-10 + AC-21 ══════════════
  const inputs = page.locator('table tr:has(input) input:not([disabled]):not([readonly])');
  const n = await inputs.count();
  console.log(`[AC-10]「产品」页签可输入格 = ${n}`);
  expect(n, 'AC-10:「产品」页签一个可输入格都没有 ⇒ 本条在此单测不了（🚫 不许换成 BOM 页签冒充）')
    .toBeGreaterThan(0);

  const target = inputs.first();
  const ttype = await target.getAttribute('type');
  const beforeVal = await target.inputValue();
  const wrote = ttype === 'number' ? String(700 + (Date.now() % 89)) : 'QY' + Date.now().toString().slice(-6);
  console.log(`[AC-10] 目标格 type=${ttype} 原值="${beforeVal}" → 写入 "${wrote}"`);

  await target.click(); await target.press('Control+a');
  await target.pressSequentially(wrote, { delay: 60 });
  const vBefore = await version(page);
  await target.press('Tab');                       // blur ⇒ quote-card-edit 起飞
  // 🚨 AC-21 的核心时序：**不等它落地**，立刻点保存草稿
  await page.getByRole('button', { name: /保\s*存\s*草\s*稿/ }).first().click({ noWaitAfter: true, force: true });
  await page.waitForTimeout(25000);

  const body = await page.evaluate(() => document.body.innerText);
  const stale = /已被他人修改|数据已过期/.test(body);
  const vAfter = await version(page);
  console.log(`[AC-21] /draft 响应状态 = ${JSON.stringify(draftRes)}`);
  console.log(`[AC-21] 页面出现「已被他人修改」= ${stale}`);
  console.log(`[AC-10] userDataVersion  ${vBefore} → ${vAfter}`);
  await page.screenshot({ path: path.join(SHOT, 'AC10-保存后.png'), fullPage: true });

  // ── 自证 ③：保存必须**真的发生**（版本必须动）；否则「值保住了」是没测到保存 ──
  expect(vAfter, `保存后 userDataVersion 没变（${vBefore}→${vAfter}）⇒ 根本没保存成功，后面的"值还在"是零证据`)
    .not.toBe(vBefore);
  expect(draftRes.some(s => s === '409'), `AC-21: /draft 出现 409 ⇒ 竞态未修好。实得 ${JSON.stringify(draftRes)}`).toBe(false);
  expect(stale, 'AC-21: 页面弹出「已被他人修改」').toBe(false);

  // ── 重开 ──
  await toStep2(page); await clickTabAll(page, '产品');
  const after = await rowsPerCard(page);
  const vals = await page.locator('table tr input').evaluateAll(es => es.map(e => (e as HTMLInputElement).value));
  console.log(`[AC-10] 重开后行数 = ${JSON.stringify(after)}；写入值仍在 = ${vals.includes(wrote)}`);
  await page.screenshot({ path: path.join(SHOT, 'AC10-重开后.png'), fullPage: true });
  expect(after, 'AC-10: 重开后行数变了').toEqual(t1);
  expect(vals.includes(wrote), `AC-10: 重开后找不到写入值 "${wrote}"，实得 ${JSON.stringify(vals.slice(0, 20))}`).toBe(true);
});
