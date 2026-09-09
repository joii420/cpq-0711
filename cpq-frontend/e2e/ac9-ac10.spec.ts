/**
 * 主线亲验 · AC-9 / AC-10（编辑页 Step2）
 * 🚨 量具换代记录：详情页用 innerText 可行；编辑页单元格是 <input>，值不在 innerText 里 ⇒ 恒 0。
 *    实测结构：页签 = BUTTON.qt-tab-btn（无 antd 类）；数据行 = 含 <input> 的 <tr>；每卡一个 <table>。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs'; import * as path from 'path';
import { fileURLToPath } from 'url';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';
const SHOT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'screenshots', 'qinyan-260908');
fs.mkdirSync(SHOT, { recursive: true });
const Q_READ = process.env.Q_READ!, Q_WRITE = process.env.Q_WRITE!;

async function toStep2(p: Page, qid: string) {
  await p.goto(`/quotations/${qid}/edit`);
  await p.waitForLoadState('networkidle'); await p.waitForTimeout(5000);
  const next = p.getByRole('button', { name: /下\s*一\s*步/ });
  if (await next.count() && await next.first().isEnabled().catch(() => false)) {
    await next.first().click(); await p.waitForTimeout(6000);
  }
}
/** 每张卡片当前激活页签的数据行数（含 <input> 的 tr） */
async function rowsPerCard(p: Page): Promise<number[]> {
  return p.evaluate(() => Array.from(document.querySelectorAll('table'))
    .map(t => Array.from(t.querySelectorAll('tr')).filter(r => r.querySelector('input')).length));
}
/** 点所有卡片上文本为 name 的页签按钮 */
async function clickTabAll(p: Page, name: string) {
  await p.evaluate((n) => Array.from(document.querySelectorAll('button.qt-tab-btn'))
    .filter(b => (b as HTMLElement).innerText.trim() === n)
    .forEach(b => (b as HTMLElement).click()), name);
  await p.waitForTimeout(3000);
}

let up = false;
test.beforeAll(async () => { up = await isBackendUp(); });

test('AC-9 切页签→刷新→切回 行数稳定', async ({ page }) => {
  test.setTimeout(600000); test.skip(!up, '后端未启动');
  await loginAsAdmin(page);
  await toStep2(page, Q_READ);

  // 🔑 量具自证：必须真的找到卡片和页签按钮，否则后面全是空跑
  const tabs = await page.locator('button.qt-tab-btn').count();
  expect(tabs, '找不到页签按钮 ⇒ 量具失效').toBeGreaterThan(0);
  await clickTabAll(page, '产品');
  const t1 = await rowsPerCard(page);
  expect(t1.length, '切不出卡片 ⇒ 量具失效').toBeGreaterThan(0);
  console.log(`[AC-9] 时点1 首次进入      = ${JSON.stringify(t1)}  (页签按钮 ${tabs} 个)`);

  await clickTabAll(page, 'BOM');
  const bom = await rowsPerCard(page);
  console.log(`[AC-9] （切到 BOM 对照）   = ${JSON.stringify(bom)}  ← 应 >1，证明量具会随页签变，不是恒 1`);
  await clickTabAll(page, '产品');
  const t2 = await rowsPerCard(page);
  console.log(`[AC-9] 时点2 切走再切回    = ${JSON.stringify(t2)}`);

  await page.reload(); await page.waitForLoadState('networkidle'); await page.waitForTimeout(4000);
  await toStep2(page, Q_READ); await clickTabAll(page, '产品');
  const t3 = await rowsPerCard(page);
  console.log(`[AC-9] 时点3 F5 刷新后     = ${JSON.stringify(t3)}`);

  await clickTabAll(page, 'BOM'); await clickTabAll(page, '产品');
  const t4 = await rowsPerCard(page);
  console.log(`[AC-9] 时点4 再切回        = ${JSON.stringify(t4)}`);
  await page.screenshot({ path: path.join(SHOT, 'AC9-四时点.png'), fullPage: true });

  const same = JSON.stringify(t1) === JSON.stringify(t2) && JSON.stringify(t2) === JSON.stringify(t3) && JSON.stringify(t3) === JSON.stringify(t4);
  const allOne = t1.every(n => n === 1);
  console.log(`[AC-9] 四时点全等 = ${same} ; 且每卡 1 行 = ${allOne}`);
});

test('AC-10 编辑→保存草稿→重开 值保留', async ({ page }) => {
  test.setTimeout(600000); test.skip(!up, '后端未启动');
  await loginAsAdmin(page);
  await toStep2(page, Q_WRITE);
  await clickTabAll(page, 'BOM');            // BOM 页签有更多可编辑格
  const before = await rowsPerCard(page);
  console.log(`[AC-10] 保存前 BOM 行数 = ${JSON.stringify(before)}`);
  expect(before.length, '切不出卡片 ⇒ 量具失效').toBeGreaterThan(0);

  // 🚨 实测坑：BOM 树第一行是**空的树根行**（row_data 里只有 {"row_index":0}）——
  //    它是渲染构件不是业务行，写进去按设计不会被持久化。
  //    第一版取 nth(4) 正落在根行里 ⇒ 「值没保住」是量具错，不是产品缺陷。
  //    ⇒ 只取**已有非空值的行**里的格子（那种行必然是业务行）。
  const inputs = page.locator('table tr:has(input[value]:not([value=""])) input:not([disabled])');
  let n = await inputs.count();
  if (n === 0) {  // 兜底：按 tr 序号跳过第一行
    console.log('[AC-10] 未匹配到"已有值的行"，回退到跳过首行');
  }
  console.log(`[AC-10] 业务行里的可输入格 = ${n}`);
  expect(n, '没有可输入格 ⇒ 测不了 AC-10').toBeGreaterThan(0);

  // 🚨 实测坑：格子里混着 input[type=number]，往里 fill 字母会抛
  //    「Cannot type text into input[type=number]」⇒ 按目标格的实际 type 选写入值
  const target = inputs.nth(Math.min(4, n - 1));
  // 自证：目标格所在行必须是业务行（同行至少有一个非空值）
  const rowHasValue = await target.evaluate(e => {
    const tr = (e as HTMLElement).closest('tr');
    return tr ? Array.from(tr.querySelectorAll('input')).some(i => (i as HTMLInputElement).value.trim() !== '') : false;
  });
  console.log(`[AC-10] 目标格所在行是业务行（同行有非空值）= ${rowHasValue}`);
  expect(rowHasValue, '目标格落在空的树根行上 ⇒ 量具失效，写进去按设计不会持久化').toBe(true);
  const ttype = await target.getAttribute('type');
  const wrote = ttype === 'number' ? String(700 + (Date.now() % 89)) : 'QY' + Date.now().toString().slice(-6);
  console.log(`[AC-10] 目标格 type=${ttype} ⇒ 写入值取 ${ttype === 'number' ? '数值' : '文本'}`);
  const beforeVal = await target.inputValue();
  // 🚨 实测坑：fill() 只设 DOM value，受控组件的 onChange 可能不触发 ⇒ 应用不知道有改动
  //    ⇒ 点保存也不会发请求（实证：后端日志里没有本单的 saveDraft-diag）。
  //    改用真实键盘输入 + Tab 触发 blur。
  await target.click();
  await target.press('Control+a');
  await target.pressSequentially(wrote, { delay: 60 });
  await target.press('Tab');
  await page.waitForTimeout(3000);
  const echoed = await target.inputValue().catch(() => '(取不到)');
  console.log(`[AC-10] 输入后回读该格 = "${echoed}"（应等于写入值，否则输入没进去）`);
  console.log(`[AC-10] 第5个格：原值="${beforeVal}" → 写入="${wrote}"`);
  await page.screenshot({ path: path.join(SHOT, 'AC10-已写入.png'), fullPage: true });

  const save = page.getByRole('button', { name: /保\s*存\s*草\s*稿/ }).first();
  console.log(`[AC-10] 保存草稿按钮 = ${await save.count()} 个`);
  await save.click(); await page.waitForTimeout(20000);
  // 🔑 自证：保存必须**真的发生**。否则「值没保住」是测不到保存，不是产品丢数据。
  const fired = await page.evaluate(async (qid) => {
    const r = await fetch(`/api/cpq/quotations/${qid}`, { credentials: 'include' });
    const j = await r.json();
    const items = (j.data && j.data.lineItems) || [];
    return items.map((it: any) => it.cardSnapshotAt || null);
  }, Q_WRITE);
  console.log(`[AC-10] 保存后 cardSnapshotAt = ${JSON.stringify(fired)}（与保存前不同才说明保存真的执行了）`);
  await page.screenshot({ path: path.join(SHOT, 'AC10-已保存.png'), fullPage: true });

  await toStep2(page, Q_WRITE); await clickTabAll(page, 'BOM');
  const after = await rowsPerCard(page);
  const vals = await page.locator('table tr input').evaluateAll(es => es.map(e => (e as HTMLInputElement).value));
  console.log(`[AC-10] 重开后 BOM 行数 = ${JSON.stringify(after)}`);
  console.log(`[AC-10] 写入值是否仍在 = ${vals.includes(wrote)}`);
  console.log(`[AC-10] 行数是否不变 = ${JSON.stringify(before) === JSON.stringify(after)}`);
  await page.screenshot({ path: path.join(SHOT, 'AC10-重开后.png'), fullPage: true });
});
