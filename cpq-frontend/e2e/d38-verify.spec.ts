/**
 * task-260910 · D-38（修 BL-0202）后端侧自测：选配建的报价行写 template_id
 *
 * AC-11 四条**必须从 UI 验**，且**要「打开报价单编辑页」之后再看** ——
 * 缺陷的准确形态是「确认那一刻能渲染（前端有内存兜底），一进编辑页就空」。
 *
 * 端口：临时栈 5211 → 8093（🚫 不占 8081/5174，也不碰主线的 8098/5200）。
 * 库：cpq_db_0724（fixtures/task260902.ts 的 query() 写死的就是它）。
 */
import { test, expect, Page, Locator } from '@playwright/test';
import * as fs from 'fs';
import * as nodePath from 'path';
import { fileURLToPath } from 'url';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';
import {
  query, drawer, fillStep1, startNewPart, addMaterial, addProcesses,
  nextStep, submitToQuotation, selectByLabel, assertStep1Passable,
} from './fixtures/task260902';

const HERE = nodePath.dirname(fileURLToPath(import.meta.url));
const EV = nodePath.resolve(HERE, '..', '..', 'dev-docs',
  'task-260910-选配切ds新表与已有料号绑定', '证据', 'D-38');
fs.mkdirSync(EV, { recursive: true });

const CUST = { code: 'CUST-0004', name: '正泰' };
const TPL_NAME = '正泰测试模板1';
const TPL_ID = '45cc0267-a340-43ba-80e1-3de17af47443';
/** 改动前后两轮跑用不同 tag，证据不会互相覆盖。 */
const TAG = process.env.D38_TAG || 'AFTER';
const RUN = `D38-${TAG}-${Date.now()}`;

async function shot(page: Page, name: string) {
  const p = nodePath.join(EV, `${TAG}-${name}.png`);
  await page.screenshot({ path: p, fullPage: true }).catch(() => {});
  console.log(`[shot] → ${p}`);
  return p;
}
function save(name: string, body: string) {
  const p = nodePath.join(EV, `${TAG}-${name}`);
  fs.writeFileSync(p, body, 'utf-8'); console.log(`[evidence] → ${p}`); return p;
}

/**
 * 读卡片里当前可见的最大那张表。
 *
 * 🚨 **量具坑（本次实测踩到，差点误报产品缺陷）**：报价卡片的可编辑单元格值在
 * `<input>` 的 `value` 里，`td.textContent` 一律是**空串** —— 只读 textContent 会把
 * 「Ag 90 / Cu 10 明明渲染出来了」读成「全空」。⇒ 单元格取值必须
 * `textContent || input.value || select.value`。
 */
async function readVisibleTable(card: Locator) {
  return card.evaluate((root: any) => {
    const cellText = (d: any): string => {
      const own = (d.textContent || '').trim();
      const vals = [...d.querySelectorAll('input, select, textarea')]
        .map((i: any) => String(i.value ?? '').trim()).filter(Boolean);
      return [own, ...vals].filter(Boolean).join(' ');
    };
    const tables = [...root.querySelectorAll('table')].filter((t: any) => t.offsetParent !== null);
    const t: any = tables.sort((a: any, b: any) =>
      b.querySelectorAll('tbody tr').length - a.querySelectorAll('tbody tr').length)[0];
    if (!t) return { head: [] as string[], rows: [] as string[][], rowCount: 0 };
    const head = [...t.querySelectorAll('thead th')].map((h: any) => (h.textContent || '').trim());
    const rows = [...t.querySelectorAll('tbody tr')].map((r: any) =>
      [...r.querySelectorAll('td')].map(cellText));
    return { head, rows, rowCount: rows.length };
  });
}

async function clickTab(page: Page, card: Locator, name: string | RegExp) {
  const btn = card.locator('button.qt-tab-btn').filter({ hasText: name }).first();
  const n = await btn.count();
  if (n === 0) {
    const tabs = await card.locator('button.qt-tab-btn').allInnerTexts();
    throw new Error(`[量具] 卡片里没有「${name}」页签。现有页签 = ${JSON.stringify(tabs.map(s => s.trim()))}`);
  }
  await btn.click({ force: true });
  await page.waitForTimeout(5000);
}

let backendUp = false;
test.beforeAll(async () => {
  backendUp = await isBackendUp();
  console.log(`[D-38] backendUp=${backendUp}  TAG=${TAG}  证据目录=${EV}`);
});

test('D-38 / AC-11：选配 → 打开编辑页 → 两页签渲染非空', async ({ page }) => {
  test.skip(!backendUp, '临时后端未启动');
  test.setTimeout(600_000);

  // ── ① 建单（正泰 + 正泰测试模板1）─────────────────────────────────────────
  await loginAsAdmin(page);
  await page.goto('/quotations/new');
  await page.waitForLoadState('networkidle');
  await selectByLabel(page, '客户', CUST.name);
  await page.waitForTimeout(1500);
  await page.keyboard.press('Escape').catch(() => {});
  await selectByLabel(page, '报价模板', TPL_NAME);
  await page.waitForTimeout(600);
  await page.locator('input[placeholder*="报价单名称"]').first().fill(RUN);
  await assertStep1Passable(page);
  await page.getByRole('button', { name: /下一步/ }).first().click();
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);

  // ── ② 选配抽屉：新建零件 + AgCu90 100% + 工序 Z008 ───────────────────────
  await page.getByRole('button', { name: /添加产品/ }).first().click();
  await page.waitForTimeout(500);
  await page.locator('text=选配添加').first().click();
  await page.waitForTimeout(1000);
  await expect(drawer(page), '「选配添加」抽屉应打开').toBeVisible({ timeout: 15000 });

  const productNo = `${RUN}-P1`;
  await fillStep1(page, productNo);
  await startNewPart(page, 'D38验证件', 'φ5', '5×3×2', '10');
  await addMaterial(page, 'AgCu90', '100');
  await addProcesses(page, ['Z008']);
  await drawer(page).getByRole('button', { name: /确\s*定/ }).last().click();
  await page.waitForTimeout(800);
  await nextStep(page);           // → 组合工序
  await nextStep(page);           // → 确认并添加
  await submitToQuotation(page, `D-38·${TAG}`);
  await shot(page, '01-提交后-未刷新');

  // ── ③ 拿 quotationId + 落库实况（🚫 不点保存草稿，AC-10「不做任何其它操作」）──
  const qid = query(`SELECT q.id FROM quotation q JOIN customer c ON c.id=q.customer_id
                     WHERE c.code='${CUST.code}' AND q.name='${RUN}'
                     ORDER BY q.created_at DESC LIMIT 1`);
  expect(qid, `阳性对照：按名称 ${RUN} 找不到刚建的报价单 ⇒ 后面全部结论无效`).not.toBe('');
  console.log(`[D-38] quotationId = ${qid}`);

  const liDump = query(`SELECT li.id || ' | pn=' || coalesce(li.product_part_no_snapshot,'(null)')
                        || ' | template_id=' || coalesce(li.template_id::text,'(NULL)')
                        || ' | product_id=' || coalesce(li.product_id::text,'(null)')
                        FROM quotation_line_item li WHERE li.quotation_id='${qid}'`);
  const qDump = query(`SELECT 'customer_template_id=' || coalesce(customer_template_id::text,'(NULL)')
                       || ' | status=' || status || ' | user_data_version=' || coalesce(user_data_version::text,'?')
                       FROM quotation WHERE id='${qid}'`);
  console.log(`[D-38] quotation: ${qDump}`);
  console.log(`[D-38] line items:\n${liDump}`);
  const bomDump = query(`SELECT 'ds_quote_material_bom=' || (SELECT count(*) FROM ds_quote_material_bom b
                            WHERE b.input_material_no='AgCu90' AND b.material_no IN
                              (SELECT product_part_no_snapshot FROM quotation_line_item WHERE quotation_id='${qid}'))`);
  console.log(`[D-38] ${bomDump}`);
  save('db-落库实况.txt',
    `RUN=${RUN}\nquotationId=${qid}\n${qDump}\n--- line items ---\n${liDump}\n${bomDump}\n`);

  // ── ④ 打开报价单编辑页（= AC-11 的「操作」，也是缺陷的真实触发点）──────────
  await page.goto(`/quotations/${qid}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(6000);
  await shot(page, '02a-编辑页-第1步');
  // ⚠️ /quotations/{id}/edit 从**第 1 步（选择客户）**打开，产品卡在第 2 步。
  //    这是页面本身的行为，不是缺陷 —— 但量具必须自己走过去，否则会把
  //    「卡片还没渲染」误判成「卡片渲染不出来」。
  await page.getByRole('button', { name: /下一步/ }).first().click();
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(9000);
  await shot(page, '02-编辑页-第2步');

  const card = page.locator('.qt-product-card').first();
  await card.waitFor({ state: 'visible', timeout: 60000 });
  const cardText = (await card.innerText()).replace(/\n+/g, ' | ');
  const tabs = (await card.locator('button.qt-tab-btn').allInnerTexts()).map(s => s.trim());
  console.log(`[D-38] 页签 = ${JSON.stringify(tabs)}`);
  console.log(`[D-38] 卡片全文（前 1200 字）= ${cardText.slice(0, 1200)}`);

  // AC-11 ④：不得出现那句占位文案
  const EMPTY = '请通过添加产品选择模板后自动加载组件结构';
  const hasEmptyText = cardText.includes(EMPTY);
  console.log(`[D-38] AC-11④ 占位文案出现 = ${hasEmptyText}`);

  // 小计（同屏取值，不做恒真断言，原样记录）
  const subtotalHits = (cardText.match(/产品小计[\s|]*¥?[\s]*[\d.,]*/g) || []).slice(0, 6);
  console.log(`[D-38] 产品小计文本 = ${JSON.stringify(subtotalHits)}`);
  console.log(`[D-38] 卡片尾部 = ${cardText.slice(-260)}`);

  let bomTable: any = null, elemTable: any = null;
  let bomErr = '', elemErr = '';
  try { await clickTab(page, card, 'BOM'); bomTable = await readVisibleTable(card);
        await shot(page, '03-页签-BOM'); } catch (e: any) { bomErr = String(e.message || e); }
  try { await clickTab(page, card, '材质元素'); elemTable = await readVisibleTable(card);
        await shot(page, '04-页签-材质元素'); } catch (e: any) { elemErr = String(e.message || e); }

  console.log(`[D-38] BOM 页签: err=${bomErr} table=${JSON.stringify(bomTable)}`);
  console.log(`[D-38] 材质元素 页签: err=${elemErr} table=${JSON.stringify(elemTable)}`);

  save('ui-实测.txt', [
    `RUN=${RUN}  TAG=${TAG}`,
    `quotationId=${qid}`,
    `页签 = ${JSON.stringify(tabs)}`,
    `AC-11④ 占位文案「${EMPTY}」出现 = ${hasEmptyText}`,
    `小计相关文本 = ${JSON.stringify(subtotalHits)}`,
    `--- BOM 页签 (err=${bomErr}) ---`, JSON.stringify(bomTable, null, 1),
    `--- 材质元素 页签 (err=${elemErr}) ---`, JSON.stringify(elemTable, null, 1),
    `--- 卡片全文 ---`, cardText,
  ].join('\n'));

  if (TAG === 'BEFORE') {
    // 阳性对照轮：改回去之后，卡片**必须重新变空**
    expect(hasEmptyText, '阳性对照：把写 template_id 那行改回去后，卡片必须重新显示占位文案').toBe(true);
    console.log('✅ 阳性对照成立：改回去 ⇒ 卡片重新变空');
    return;
  }

  // ── AC-11 四条（TAG=AFTER）───────────────────────────────────────────────
  expect(liDump, 'D-38 前提：选配行的 template_id 必须已写入').toContain(`template_id=${TPL_ID}`);
  expect(hasEmptyText, `AC-11④：卡片内容区不得显示「${EMPTY}」`).toBe(false);

  expect(bomErr, 'AC-11①：BOM 页签必须能点开').toBe('');
  expect(bomTable.rowCount, 'AC-11①：「物料BOM」页签必须渲染非空行（0 行会让后面恒真）').toBeGreaterThan(0);
  const bomFlat = JSON.stringify(bomTable.rows);
  expect(bomFlat, 'AC-11②：BOM 树里必须出现子节点 AgCu90').toContain('AgCu90');
  expect(bomTable.rowCount, 'AC-11②：「物料BOM」必须是树形两层（根 + AgCu90）⇒ ≥ 2 行').toBeGreaterThanOrEqual(2);

  expect(elemErr, 'AC-11①：材质元素页签必须能点开').toBe('');
  expect(elemTable.rowCount, 'AC-11①：「物料与元素BOM」页签必须渲染非空行').toBeGreaterThan(0);
  const elemFlat = JSON.stringify(elemTable.rows);
  expect(elemFlat, 'AC-11③：必须出现元素 Ag').toContain('Ag');
  expect(elemFlat, 'AC-11③：必须出现元素 Cu').toContain('Cu');
  expect(elemFlat, 'AC-11③：Ag 含量必须是 90').toMatch(/\b90(\.0+)?\b/);
  expect(elemFlat, 'AC-11③：Cu 含量必须是 10').toMatch(/\b10(\.0+)?\b/);
  console.log('✅ AC-11 ①②③④ 全部在 UI 层达成');
});
