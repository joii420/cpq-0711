/**
 * task-260910 · **AC-12 完整序列（含写库的「保存」）** · S-造数片
 *
 * 🚦 🚫 **不在基准单 `QT-20260909-0799` 上做写操作** —— 它是主线亲验的基准，
 *    来回保存会触发快照重算，风险不对称（主线 2026-09-10 已确认此设计）。
 *    改在本片自建的 `T260910-AC15-*` 单上跑完整序列；
 *    0799 上的**不写库子序列**（切走切回 + F5）已由 `t260910-s1-readonly.spec.ts` 的 T12.2 覆盖。
 */
import { test, expect } from '@playwright/test';
import * as H from './t260910.helpers';

test('T12.1 · 自建单：改值 → 保存 → 切走切回 → F5 → 头部与浮层逐字不变', async ({ page }) => {
  const own = H.sqlRows(`SELECT id::text id, quotation_number qn FROM quotation
     WHERE name LIKE '${H.TAG}AC15-%' ORDER BY created_at DESC LIMIT 1`)[0];
  expect(own?.id, `前置未满足：找不到本片自建的 ${H.TAG}AC15-* 报价单 ⇒ 先跑 S3，判【未验证】`).toMatch(/^[0-9a-f-]{36}$/);
  await H.uiLogin(page);
  const snap: any[] = [];
  const goStep2 = async () => {
    await page.waitForSelector('.ant-steps-item', { timeout: 90_000 });
    const nx = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
    await expect(nx).toBeEnabled({ timeout: 40_000 });
    await nx.click();
    await page.waitForFunction(() => document.querySelectorAll('.qt-product-card').length > 0, undefined, { timeout: 90_000 });
    await page.waitForTimeout(4000);
  };
  const capture = async (tag: string) => {
    const cards = await H.readCards(page);
    expect(cards.length, `${tag}：卡片数应 > 0`).toBeGreaterThan(0);
    const pop = await H.openPopover(page, 0);
    await page.keyboard.press('Escape').catch(() => {});
    await page.waitForTimeout(500);
    const s = { tag, left: cards[0].leftTexts, right: cards[0].rightTexts, inline: cards[0].inlineStyle,
      border: cards[0].borderTopColor, partInfo: cards[0].hasPartInfoBtn, pop: H.parsePopoverRows(pop.lines) };
    snap.push(s); console.log(`[${tag}] ` + JSON.stringify(s));
  };

  await page.goto(`/quotations/${own.id}/edit`);
  // 🩹 自建单可能还没有产品行（S3 只加到前端态、没保存）⇒ 先经「从已有产品添加」加一行并保存，
  //    否则本条断言会**空跑**（testing.md §3 第3条：数据为空 → 断言压根没跑，测试照样报绿）。
  await page.waitForSelector('.ant-steps-item', { timeout: 90_000 });
  const nx0 = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  await expect(nx0).toBeEnabled({ timeout: 40_000 });
  await nx0.click();
  await page.waitForTimeout(9000);
  if (await page.locator('.qt-product-card').count() === 0) {
    const addBtn = page.locator('button').filter({ hasText: /添加产品/ }).first();
    await addBtn.click();
    await page.waitForTimeout(2500);
    await page.locator('.ant-dropdown-menu-item').filter({ hasText: /从已有产品添加/ }).first().click();
    await page.waitForTimeout(8000);
    const dw = page.locator('.ant-drawer').first();
    await expect(dw, '「从已有产品添加」抽屉没打开 ⇒ **入口问题**，判【未验证】').toBeVisible({ timeout: 40_000 });
    await dw.locator('.ant-table-row input[type="checkbox"]').first().click();
    await page.waitForTimeout(1200);
    await dw.locator('button').filter({ hasText: /加\s*入\s*报\s*价\s*单/ }).last().click();
    await page.waitForTimeout(12000);
  }
  await page.waitForFunction(() => document.querySelectorAll('.qt-product-card').length > 0, undefined, { timeout: 60_000 });
  await page.waitForTimeout(3000);

  // 🚨 防「恒空判据」（testing.md §5.5 ③）：首轮实测该产品未绑生产料号 ⇒ 浮层四态全是 {}，
  //    「四态相同」在 {} == {} 上**必然成立**，等于没验。这里给它造一条本片前缀的绑定，
  //    让浮层**有值**，四态比较才有意义。造数前先确认该料号在 ds_quote_material **全局 0 行**（只增不改）。
  const mat = (await page.locator('.qt-product-card .qt-part-badge').first().innerText())
    .replace(/\s+/g, '').replace(/^销售料号[:：]/, '');
  expect(mat, '取不到销售料号 ⇒ **入口问题**').not.toBe('');
  const globalRows = Number(H.sqlScalar(`SELECT count(*) FROM ds_quote_material WHERE material_no='${mat}'`));
  if (globalRows === 0) {
    H.sqlOwnedInsert(
      `INSERT INTO ds_cost_basic_material (production_no, material_name, specification, dimension, old_material_no, source, created_by)
       VALUES ('${H.TAG}P-SEQ','${H.TAG}零件-SEQ','${H.TAG}规格-SEQ','${H.TAG}尺寸-SEQ','${H.TAG}旧-SEQ','IMPORT','t260910-tester')`,
      'AC-12 夹具: ds_cost_basic_material T260910-P-SEQ');
    H.sqlOwnedInsert(
      `INSERT INTO ds_quote_material (customer_no, material_no, production_no, source, created_by)
       VALUES ('CUST-0004','${mat}','${H.TAG}P-SEQ','IMPORT','t260910-tester')`,
      `AC-12 夹具: ds_quote_material @CUST-0004 / ${mat} → ${H.TAG}P-SEQ`);
    await page.reload();
    await goStep2();
  } else {
    console.log(`[AC-12 夹具] ${mat} 在 ds_quote_material 已有 ${globalRows} 行（别人的数据）⇒ 🚫 不造数、不覆盖，直接用现状`);
  }
  await capture('初始态');
  // 防空跑：浮层必须**有内容**，否则「四态相同」是 {} == {} 的恒真比较
  expect(Object.keys(snap[0].pop).length,
    `AC-12 浮层解析为空 ⇒「四态逐字相同」会退化成恒真比较（testing.md §5.5 ③），本条判【未验证】。浮层=${JSON.stringify(snap[0].pop)}`)
    .toBeGreaterThan(0);

  // ① 在「产品」页签任一输入框改一个值
  const inputs = page.locator('.qt-product-card input[type="text"], .qt-product-card input:not([type])');
  const cnt = await inputs.count();
  expect(cnt, 'AC-12 前置：卡片里找不到可编辑输入框 ⇒ **入口问题**，判【未验证】').toBeGreaterThan(0);
  const target = inputs.first();
  const before = await target.inputValue();
  const marker = `T260910-SEQ-${Date.now().toString(36).slice(-4)}`;
  await target.fill(marker);
  await target.blur();
  await page.waitForTimeout(2500);
  console.log(`[改值] 输入框 0：「${before}」→「${marker}」`);

  // ② 保存
  const save = page.locator('button').filter({ hasText: /保\s*存\s*草\s*稿|^\s*保\s*存\s*$/ }).first();
  await expect(save, 'AC-12 找不到保存按钮 ⇒ **入口问题**').toBeVisible({ timeout: 20_000 });
  await save.click();
  await page.waitForTimeout(15000);
  await capture('保存后');

  // ③ 切走到 Step1 再切回 Step2
  await page.locator('button').filter({ hasText: /^\s*上\s*一\s*步\s*$/ }).first().click();
  await page.waitForTimeout(6000);
  const nx = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  await expect(nx).toBeEnabled({ timeout: 40_000 });
  await nx.click();
  await page.waitForFunction(() => document.querySelectorAll('.qt-product-card').length > 0, undefined, { timeout: 90_000 });
  await page.waitForTimeout(4000);
  await capture('切走再切回');

  // ④ F5 刷新整页
  await page.reload();
  await goStep2();
  await capture('F5刷新后');

  const base = snap[0];
  for (const s of snap.slice(1)) {
    expect(s.left, `AC-12「${s.tag}」左侧应与初始态逐字相同`).toEqual(base.left);
    expect(s.right, `AC-12「${s.tag}」右侧应与初始态逐字相同`).toEqual(base.right);
    expect(s.pop, `AC-12「${s.tag}」浮层应与初始态逐字相同`).toEqual(base.pop);
    expect(s.inline ?? '', `AC-12「${s.tag}」仍不得有内联 border`).not.toContain('border');
    expect(s.border, `AC-12「${s.tag}」边框仍应是默认灰`).toBe('rgb(224, 224, 224)');
    expect(s.partInfo, `AC-12「${s.tag}」仍不得有「料号信息」按钮`).toBe(false);
  }
  const moban = await page.locator('.qt-product-card').locator('text=/模板\s*[:：]/').count();
  expect(moban, `AC-12/AC-14 F5 后仍不得出现「模板: xxx」，实际 ${moban} 处`).toBe(0);
  await H.shot(page, 'ac12-完整序列-F5后');
  // 清理本条造的夹具（前缀 WHERE + 先量化命中面 + 删后回读）
  for (const [tb, w] of [['ds_quote_material', `production_no LIKE '${H.TAG}%'`],
                         ['ds_cost_basic_material', `production_no LIKE '${H.TAG}%'`]] as const) {
    const r = H.cleanupPrefixed(tb, w);
    console.log(`[cleanup] ${tb}: before=${r.before} after=${r.after} ok=${r.ok}`);
    expect(r.ok, `🚨 ${tb} 清理未完成（残留 ${r.after} 行）⇒ 写进 test-report.md 待回收清单`).toBe(true);
  }
  H.writeEvidence('ac12-完整序列四态.txt',
    `自建单=${own.qn}\n改值：输入框0「${before}」→「${marker}」\n` + JSON.stringify(snap, null, 1) + `\nF5后模板徽标计数=${moban}\n`);
});
