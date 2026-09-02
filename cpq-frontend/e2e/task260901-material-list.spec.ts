/**
 * task-260901 · E2E：**材质管理页工具栏 / 新建材质抽屉 / 选择框过滤**
 * （T-E-10 / T-E-12a / T-E-14 / T-E-15 / T-E-16①②）
 * → AC-29 / AC-30(编辑输入框) / AC-33 / AC-34 / AC-35①②与反向断言。
 *
 * 对照 `原型图/1-材质管理页.html` 状态 A/D 与 `原型图/6-新建材质抽屉.html` 状态 A。
 * 选择器免责声明同 `task260901-material-drawer.spec.ts`。
 */
import { test, expect } from '@playwright/test';
import {
  assertIsolatedEnv, assertNoResidue, restoreGlobalState, sql, sqlOne,
  shot, evidence, login, gotoMaterialTab, tooltipOf, AC_PREFIX, REAL_RECIPE_CODE,
} from './task260901-material.helpers';

test.describe.configure({ mode: 'serial' });
test.beforeAll(() => { assertIsolatedEnv(); restoreGlobalState(); });
test.afterAll(() => { restoreGlobalState(); assertNoResidue(); });

const elNo = (sym: string) => sqlOne(`SELECT element_no FROM element WHERE element_code='${sym}'`)!;

// ══════════════ T-E-10 → AC-29 ══════════════

test('T-E-10 / AC-29：0 选与 3 选下的工具栏禁用态与 tooltip，且工具栏没有「新增含量配置」', async ({ page }) => {
  await login(page);
  await gotoMaterialTab(page);

  const edit = page.getByRole('button', { name: /^编\s*辑$/ });
  const disable = page.getByRole('button', { name: /^停\s*用$/ });

  // 未选中：两个按钮「可见且置灰」—— 不得消失
  await expect(edit, 'AC-29：未选中时「编辑」可见').toBeVisible();
  await expect(edit, 'AC-29：未选中时「编辑」置灰').toBeDisabled();
  await expect(disable, 'AC-29：未选中时「停用」可见').toBeVisible();
  await expect(disable, 'AC-29：未选中时「停用」置灰').toBeDisabled();

  // 🚨 禁用按钮的 tooltip 必须 mouse.move，hover({force:true}) 取不到（前端 2026-09-02 实测）
  const tipEdit0 = await tooltipOf(page, edit);
  console.log('[AC-29·0选] 编辑 tooltip =', tipEdit0);
  expect(tipEdit0, 'AC-29：文案须带当前选中数').toContain('请先选择一个材质');
  expect(tipEdit0).toContain('当前选中 0 个');

  const tipDis0 = await tooltipOf(page, disable);
  console.log('[AC-29·0选] 停用 tooltip =', tipDis0);
  expect(tipDis0).toContain('请先选择材质');
  expect(tipDis0).toContain('当前选中 0 个');
  await shot(page, 'AC29-toolbar-0-selected', { fullPage: true });

  // ⚠️ 工具栏没有「新增含量配置」—— 配置操作一律在材质编辑抽屉内
  await expect(page.getByRole('button', { name: /新增含量配置/ }),
    'AC-29：🚫 工具栏不得出现「新增含量配置」').toHaveCount(0);

  // 选中 3 行
  const boxes = page.locator('.ant-table-tbody tr.ant-table-row input[type="checkbox"]');
  expect(await boxes.count(), 'AC-29：列表须至少 3 行（前置数据非空，否则断言空跑）').toBeGreaterThanOrEqual(3);
  for (let i = 0; i < 3; i++) await boxes.nth(i).check();

  await expect(edit, 'AC-29：选 3 行时「编辑」仍置灰').toBeDisabled();
  const tipEdit3 = await tooltipOf(page, edit);
  console.log('[AC-29·3选] 编辑 tooltip =', tipEdit3);
  expect(tipEdit3).toContain('只能选择一个材质');
  expect(tipEdit3).toContain('当前选中 3 个');

  await expect(disable, 'AC-29：选 3 行时「停用」变为可用').toBeEnabled();
  await shot(page, 'AC29-toolbar-3-selected', { fullPage: true });

  // 点「停用」应弹出抽屉逐条列出 3 个材质的编号与名称（只看不确认，避免动全局状态）
  // ⚠️ 别用整行文本正则抓编号：行里还有别的数字，`\b\d{5}\b` 抓不稳（实测 3 行只抓到 2 个）。
  //    改成逐行遍历单元格，取形如 ^\d{5}$ 的那个 —— 材质编号有独立列。
  const codes: string[] = [];
  for (let i = 0; i < 3; i++) {
    const cells = await page.locator('.ant-table-tbody tr.ant-table-row').nth(i)
      .locator('td').allInnerTexts();
    // ⚠️ 🚫 别按 `^\d{5}$` 筛：材质编号**不都是 5 位** —— 实测列表里有脏数据 `992`
    //    （需求文档 §2.2 明确本期不清理，被 element_bom_item 引用着）。
    //    材质编号是**固定第 2 列**（第 1 列是勾选框），直接取列，不猜格式。
    const code = (cells[1] || '').trim();
    console.log(`[AC-29] 第 ${i} 行单元格 =`, JSON.stringify(cells.map(c => c.trim())), '→ code =', code);
    expect(code, `AC-29：第 ${i} 行应能取到材质编号`).toBeTruthy();
    codes.push(code);
  }
  await disable.click();
  const confirm = page.locator('.ant-drawer, .ant-modal').last();
  await expect(confirm, 'AC-29：应弹出二次确认').toBeVisible();
  const text = await confirm.innerText();
  console.log('[AC-29] 停用确认内容 =', text);
  expect(codes.length, 'AC-29：应能从列表取到 3 个材质编号（取不到 = 定位问题，不是产品缺陷）').toBe(3);
  for (const c of codes) expect(text, `AC-29：确认抽屉须逐条列出材质 ${c}`).toContain(c);
  await shot(page, 'AC29-disable-confirm');
  // 🚨 只看不确认 —— AC-29（2026-09-02 收紧）原文已写明「只验工具栏与确认弹层，
  //    不真按下「停用」」：「停用」是既有功能不在本次范围，真按下去会把 3 条真实材质
  //    置为停用，属全局状态变更。
  await confirm.getByRole('button', { name: /取\s*消/ }).last().click();
  evidence('AC29-toolbar', `0选 编辑=${tipEdit0}\n0选 停用=${tipDis0}\n3选 编辑=${tipEdit3}\n确认抽屉:\n${text}`);
});

// ══════════════ T-E-14 → AC-33 ══════════════

test('T-E-14 / AC-33：新建材质抽屉 = 配方卡片（无独立元素组成输入区），保存后组成取自配方1', async ({ page }) => {
  await login(page);
  await gotoMaterialTab(page);
  const base = sqlOne(`SELECT max(code) FROM material_recipe WHERE code ~ '^[0-9]{5}$'`)!;

  await page.getByRole('button', { name: /新建材质/ }).click();
  const drawer = page.locator('.ant-drawer').last();
  await expect(drawer).toBeVisible();

  // ① 没有独立的「元素组成」输入区 —— 元素在卡片内填
  await expect(drawer.getByText('元素组成', { exact: true }),
    'AC-33①：新建抽屉不得有独立的「元素组成」输入区').toHaveCount(0);

  await drawer.locator('#symbol, input[id$="symbol"]').first().fill(`${AC_PREFIX}配方卡`);

  // 建两张配方卡
  // ⚠️ 抽屉默认已有 1 张配方卡片 ⇒ 只点**一次**「添加配方」就得到 2 张。
  //    点两次会变 3 张、第 3 张空，按原型 6「每张至少 1 个元素」保存被拦，用例必红
  //    （前端 2026-09-02 实测）。
  await drawer.getByRole('button', { name: /添加配方/ }).click();
  const cards = drawer.locator('.ant-card, [data-testid^="recipe-card"]');
  await expect(cards, 'AC-33②：应恰有两张配方卡片').toHaveCount(2);

  // ② 卡片自带表头与「添加元素」，标题为 配方1 / 配方2，各自实时显示合计 100%
  const c1 = cards.nth(0);
  const c1Text = await c1.innerText();
  console.log('[AC-33②] 配方1 卡片 =', c1Text.slice(0, 200));
  expect(c1Text, 'AC-33②：卡片标题 配方1').toContain('配方1');
  for (const h of ['元素', '元素名称', '含量']) {
    expect(c1Text, `AC-33②：卡片表头须含「${h}」`).toContain(h);
  }
  await expect(c1.getByRole('button', { name: /添加元素/ }), 'AC-33②：卡片自带「添加元素」').toBeVisible();
  expect(await cards.nth(1).innerText(), 'AC-33②：卡片标题 配方2').toContain('配方2');

  await fillCard(c1, [['Ag', '90'], ['Ni', '10']]);
  await fillCard(cards.nth(1), [['Ag', '85'], ['Ni', '15']]);
  expect(await c1.innerText(), 'AC-33②：配方1 实时合计 100%').toContain('100%');
  expect(await cards.nth(1).innerText(), 'AC-33②：配方2 实时合计 100%').toContain('100%');
  await shot(page, 'AC33-create-drawer-cards');

  await drawer.locator('.ant-drawer-footer').getByRole('button', { name: /保\s*存/ }).click();
  await page.waitForTimeout(2000);

  // ③ 库内三连
  const code = sqlOne(`SELECT code FROM material_recipe WHERE symbol='${AC_PREFIX}配方卡'`);
  console.log('[AC-33③] code =', code, ' 基线 =', base);
  expect(code, 'AC-33③：材质编号 = 基线+1').toBe(String(Number(base) + 1).padStart(base.length, '0'));
  const comp = sql(`SELECT c.element_code || '#' || c.sort_order FROM material_recipe_composition c
    JOIN material_recipe r ON r.id=c.recipe_id WHERE r.code='${code}' ORDER BY c.sort_order`);
  expect(comp, 'AC-33③：组成恰两行，取自配方1 的元素及其顺序').toEqual(['Ag#1', 'Ni#2']);
  const cfgs = sql(`SELECT c.config_no FROM material_recipe_config c
    JOIN material_recipe r ON r.id=c.recipe_id WHERE r.code='${code}' ORDER BY c.seq`);
  expect(cfgs, 'AC-33③：配置恰两行 -01 / -02').toEqual([`${code}-01`, `${code}-02`]);
  evidence('AC33-db', `code=${code}\ncomposition=${JSON.stringify(comp)}\nconfigs=${JSON.stringify(cfgs)}`);

  // ④ 列表新行
  await page.getByPlaceholder(/搜索/).first().fill(code!);
  await page.waitForTimeout(800);
  const rowText = await page.locator('.ant-table-tbody tr.ant-table-row').first().innerText();
  console.log('[AC-33④] 列表行 =', rowText);
  expect(rowText, 'AC-33④：「元素组成」列显示 Ag').toContain('Ag');
  expect(rowText, 'AC-33④：「元素组成」列显示 Ni').toContain('Ni');
  expect(rowText, 'AC-33④：「含量配置」列显示 2 组').toContain('2 组');
  await shot(page, 'AC33-list-new-row');
});

// ══════════════ T-E-15 → AC-34 ══════════════

test('T-E-15 / AC-34：两张配方卡片元素种类不同 → 保存被拦、指名报错、保存按钮置灰、后端也拦', async ({ page }) => {
  await login(page);
  await gotoMaterialTab(page);
  const base = sqlOne(`SELECT max(code) FROM material_recipe WHERE code ~ '^[0-9]{5}$'`)!;

  await page.getByRole('button', { name: /新建材质/ }).click();
  const drawer = page.locator('.ant-drawer').last();
  await drawer.locator('#symbol, input[id$="symbol"]').first().fill(`${AC_PREFIX}配方卡不一致`);
  await drawer.getByRole('button', { name: /添加配方/ }).click();   // 默认已有 1 张，只点一次
  const cards = drawer.locator('.ant-card, [data-testid^="recipe-card"]');
  await expect(cards, 'AC-34：应恰有两张配方卡片').toHaveCount(2);
  await fillCard(cards.nth(0), [['Ag', '50'], ['Ni', '50']]);
  await fillCard(cards.nth(1), [['Ag', '50'], ['Cu', '50']]);

  const save = drawer.locator('.ant-drawer-footer').getByRole('button', { name: /保\s*存/ });
  await expect(save, 'AC-34：「保存」按钮置灰').toBeDisabled();
  const tip = await tooltipOf(page, save).catch(() => '');
  const drawerText = await drawer.innerText();
  console.log('[AC-34] 抽屉报错 =', drawerText.slice(0, 500), '\n hover =', tip);
  const shown = drawerText + '\n' + tip;
  expect(shown, 'AC-34：报错须指名到配方1').toContain('配方1');
  expect(shown, 'AC-34：报错须指名到配方2').toContain('配方2');
  expect(shown, 'AC-34：须列出两边元素集合').toContain('Ni');
  expect(shown, 'AC-34：须列出两边元素集合').toContain('Cu');
  expect(shown).toContain('同一材质下各配方必须使用相同的元素');
  await shot(page, 'AC34-inconsistent-cards-error');

  // 库内零落库 + 编号未消耗
  expect(sqlOne(`SELECT count(*) FROM material_recipe WHERE symbol='${AC_PREFIX}配方卡不一致'`),
    'AC-34：material_recipe 0 行').toBe('0');
  expect(sqlOne(`SELECT max(code) FROM material_recipe WHERE code ~ '^[0-9]{5}$'`),
    'AC-34：不消耗材质编号').toBe(base);

  // 🚨 后端必须同样拦（AC-34 原文）
  const res = await page.request.post('/api/cpq/material-recipes', {
    data: { symbol: `${AC_PREFIX}配方卡不一致`, recipeType: 'locked', configs: [
      { elements: [{ elementNo: elNo('Ag'), defaultPct: '50' },
                   { elementNo: elNo('Ni'), defaultPct: '50' }] },
      { elements: [{ elementNo: elNo('Ag'), defaultPct: '50' },
                   { elementNo: elNo('Cu'), defaultPct: '50' }] },
    ] },
  });
  const body = await res.text();
  console.log('[AC-34·后端]', res.status(), body);
  expect(res.status(), 'AC-34：绕过前端直接 POST 必须返 400').toBe(400);
  expect(body).toContain('COMPOSITION_INCONSISTENT_ACROSS_CONFIGS');
  expect(body, 'AC-34：与导入侧 AC-32 是同一条规则，判据必须一致').toContain('配方1');
  expect(sqlOne(`SELECT max(code) FROM material_recipe WHERE code ~ '^[0-9]{5}$'`),
    'AC-34：后端拒绝后同样不消耗编号').toBe(base);
  evidence('AC34-backend-400', `POST ${res.status()}\n${body}\n\n前端提示:\n${shown}`);
});

// ══════════════ T-E-16 ①② → AC-35（元素选择框三种输入 + 反向断言） ══════════════

test('T-E-16 / AC-35①②：元素框 编号/符号/中文名 三种输入都命中；zzz 显示空态；「类型」下拉不得有搜索框', async ({ page }) => {
  await login(page);
  await gotoMaterialTab(page);

  // ① 新建材质抽屉的元素框
  await page.getByRole('button', { name: /新建材质/ }).click();
  let drawer = page.locator('.ant-drawer').last();
  // 默认已有 1 张配方卡片，本用例只需在它里面加元素，不必再「添加配方」
  await drawer.getByRole('button', { name: /添加元素/ }).first().click();
  const elemSel = drawer.locator('.ant-select').filter({ hasNot: page.locator('#recipeType') }).last();

  for (const kw of ['10005', 'Ni', '镍']) {
    await elemSel.click();
    await page.keyboard.press('Control+A');
    await page.keyboard.type(kw);
    const opt = page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: '10005' });
    await expect(opt.first(), `AC-35①：输入「${kw}」应筛出 10005 / Ni / 镍`).toBeVisible({ timeout: 8_000 });
    const texts = await opt.allInnerTexts();
    console.log(`[AC-35①] 输入 ${kw} → `, texts);
    expect(texts.join(' '), `AC-35①：候选须含符号 Ni`).toContain('Ni');
    expect(texts.join(' '), `AC-35①：候选须含中文名 镍`).toContain('镍');
    await shot(page, `AC35-create-elem-filter-${kw}`);
  }
  await elemSel.click();
  await page.keyboard.press('Control+A');
  await page.keyboard.type('zzz');
  await expect(page.locator('.ant-select-dropdown:visible').getByText(/无匹配|暂无数据|No data/),
    'AC-35①：输入 zzz 应显示「无匹配」空态，而不是空白下拉').toBeVisible({ timeout: 8_000 });
  await shot(page, 'AC35-create-elem-no-match');

  // 🚫 反向断言：「类型」下拉（locked/editable/partial 固定三项）不得出现搜索输入框
  const typeSel = drawer.locator('.ant-form-item').filter({ hasText: '类型' }).locator('.ant-select').first();
  await expect(typeSel, 'AC-35：应能定位到「类型」下拉').toBeVisible();
  // ⚠️ antd v6 的搜索输入是 `input.ant-select-input`，不是
  //    `input.ant-select-selection-search-input`；`readonly=""` 确实挂在前者上
  //    （前端 2026-09-02 实测）。取错 class 会拿到 null 而误判成「开了搜索框」。
  const typeInput = typeSel.locator('input.ant-select-input').first();
  await expect(typeInput, 'AC-35：应能定位到类型下拉的 input').toHaveCount(1);
  expect(await typeInput.getAttribute('readonly'),
    'AC-35 反向断言：固定枚举（locked/editable/partial）的「类型」下拉不得开搜索框 —— '
    + 'readonly 属性必须存在').not.toBeNull();
  await shot(page, 'AC35-type-select-no-search');
  await drawer.locator('.ant-drawer-close').first().click().catch(() => {});
  await page.waitForTimeout(500);

  // ② 材质编辑抽屉「元素组成」区的添加元素框（用一条 0 配置的 AC测% 材质，组成可改）
  const create = await page.request.post('/api/cpq/material-recipes', {
    data: { symbol: `${AC_PREFIX}选择框`, name: `${AC_PREFIX}选择框`, recipeType: 'locked',
      configs: [{ elements: [{ elementNo: elNo('Ag'), defaultPct: '100' }] }] },
  });
  expect(create.ok(), await create.text()).toBeTruthy();
  const r = await create.json();
  const cfgId = sqlOne(`SELECT id FROM material_recipe_config WHERE recipe_id='${r.id}'`)!;
  await page.request.delete(`/api/cpq/material-recipes/${r.id}/configs/${cfgId}`);

  await gotoMaterialTab(page);
  await page.getByPlaceholder(/搜索/).first().fill(r.code);
  await page.waitForTimeout(700);
  await page.locator('.ant-table-tbody a').filter({ hasText: r.code }).first().click();
  drawer = page.locator('.ant-drawer').last();
  await drawer.getByRole('button', { name: /添加元素/ }).click();
  const compSel = drawer.locator('.ant-select').last();
  for (const kw of ['10005', 'Ni', '镍']) {
    await compSel.click();
    await page.keyboard.press('Control+A');
    await page.keyboard.type(kw);
    const opt = page.locator('.ant-select-dropdown:visible .ant-select-item-option').filter({ hasText: '10005' });
    await expect(opt.first(), `AC-35②：编辑抽屉元素框 输入「${kw}」应命中`).toBeVisible({ timeout: 8_000 });
    console.log(`[AC-35②] 输入 ${kw} → `, await opt.allInnerTexts());
  }
  await shot(page, 'AC35-edit-composition-elem-filter');
});

// ══════════════ T-E-12a → AC-30（编辑输入框回填去零） ══════════════

test('T-E-12a / AC-30：打开 00006-01 的编辑抽屉，Ag 那格显示 90 而不是 90.000000000000', async ({ page }) => {
  await login(page);
  await gotoMaterialTab(page);
  await page.getByPlaceholder(/搜索/).first().fill(REAL_RECIPE_CODE);
  await page.waitForTimeout(700);
  await page.locator('.ant-table-tbody a').filter({ hasText: REAL_RECIPE_CODE }).first().click();
  const drawer = page.locator('.ant-drawer').last();
  await drawer.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: '00006-01' })
    .locator('a, button').first().click();
  const sub = page.locator('.ant-drawer').last();
  await expect(sub).toBeVisible();

  const values = await sub.locator('.ant-table-tbody input').evaluateAll(
    (els: any[]) => els.map(e => e.value));
  console.log('[AC-30·编辑输入框] values =', values);
  expect(values.length, 'AC-30：输入框须非空（空 = 断言空跑）').toBeGreaterThan(0);
  expect(values[0], 'AC-30：编辑输入框同样回填去零值 —— 90 而不是 90.000000000000').toBe('90');
  expect(values[1], 'AC-30：Ni 那格显示 10').toBe('10');
  await shot(page, 'AC30-edit-input-stripped');

  // 🚫 去零不得改变存储：不改任何东西直接看库
  expect(sqlOne(`SELECT e.default_pct::text FROM material_recipe_element e
    JOIN material_recipe_config c ON c.id=e.config_id
    WHERE c.config_no='00006-01' AND e.element_code='Ag'`),
    'AC-30：库内仍是 90.000000000000').toBe('90.000000000000');
  evidence('AC30-edit-inputs', `inputs=${JSON.stringify(values)}\nDB Ag=${sqlOne(
    `SELECT e.default_pct::text FROM material_recipe_element e
     JOIN material_recipe_config c ON c.id=e.config_id
     WHERE c.config_no='00006-01' AND e.element_code='Ag'`)}`);
});

/**
 * T-E-12c / AC-30 —— **12 位小数的「读→存」往返**，这是 FT-3b 的靶子。
 *
 * 🚨 为什么必须做往返：只「读」验不出去零是否改值 —— 去零函数把 `90.000000000000`
 * 显示成 `90` 时，即便它内部真改了值，不提交就落不到库里，SQL 断言永远是绿的。
 * 只有「读出去零值 → 原样保存 → 回查库」这一圈，才能证明
 * **「去零只发生在渲染层」** 这个不变量真的成立。
 */
test('T-E-12c / AC-30（往返）：12 位小数配置 读出去零值 → 原样保存 → 库内仍逐字不变', async ({ page }) => {
  await login(page);
  const recipeId = sqlOne(`SELECT id FROM material_recipe WHERE code='${REAL_RECIPE_CODE}'`)!;
  const created = await page.request.post(`/api/cpq/material-recipes/${recipeId}/configs`, {
    data: { elements: [
      { elementNo: elNo('Ag'), defaultPct: '12.345678901200' },
      { elementNo: elNo('Ni'), defaultPct: '87.654321098800' } ] },
  });
  expect(created.ok(), `前置：建 12 位小数配置应成功，实际 ${created.status()} ${await created.text()}`).toBeTruthy();
  const cfgNo = (await created.json()).configNo as string;
  console.log('[AC-30·往返] 配置编号 =', cfgNo);

  const before = sql(`SELECT e.element_code || '=' || e.default_pct::text FROM material_recipe_element e
    JOIN material_recipe_config c ON c.id=e.config_id WHERE c.config_no='${cfgNo}' ORDER BY 1`);
  console.log('[AC-30·往返] 保存前库内 =', before);
  expect(before, '前置：库内应是完整 12 位').toEqual(
    ['Ag=12.345678901200', 'Ni=87.654321098800']);

  await gotoMaterialTab(page);
  await page.getByPlaceholder(/搜索/).first().fill(REAL_RECIPE_CODE);
  await page.waitForTimeout(900);
  await page.locator('.ant-table-tbody a').filter({ hasText: REAL_RECIPE_CODE }).first().click();
  const drawer = page.locator('.ant-drawer-open').filter({ hasText: REAL_RECIPE_CODE }).last();
  await expect(drawer).toBeVisible({ timeout: 15_000 });
  await drawer.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: cfgNo })
    .locator('a, button').first().click();
  const sub = page.locator('.ant-drawer-open').last();
  await expect(sub).toBeVisible();

  const shown = await sub.locator('.ant-table-tbody input').evaluateAll((els: any[]) => els.map(e => e.value));
  console.log('[AC-30·往返] 输入框回填 =', shown);
  expect(shown.length, 'AC-30：输入框须非空（空 = 断言空跑）').toBeGreaterThan(0);
  await shot(page, 'AC30-roundtrip-inputs-stripped');

  // 一个字都不改，直接保存
  await sub.locator('.ant-drawer-footer').getByRole('button', { name: /保\s*存/ }).click();
  await page.waitForTimeout(2000);

  const after = sql(`SELECT e.element_code || '=' || e.default_pct::text FROM material_recipe_element e
    JOIN material_recipe_config c ON c.id=e.config_id WHERE c.config_no='${cfgNo}' ORDER BY 1`);
  console.log('[AC-30·往返] 保存后库内 =', after);
  expect(after.length, 'AC-30：保存后应仍有 2 行（空 = 断言空跑，什么都没验到）').toBe(2);

  // 🚨 **存储不变式先验**（2026-09-02 由 FT-3b 调序，不弱化任何断言）：
  //    AC-30 有两个判据 —— 显示去零（体验）与存储不变（正确性）。
  //    若显示断言排在前面，一个「会真改值」的去零实现会先把显示断言打红，
  //    **把存储断言挡在后面永远跑不到** —— 那条 SQL 就成了从没被证明接上的断言。
  //    ⇒ 存储断言排前，显示断言排后，两条都验。
  expect(after, 'AC-30：🚨 原样保存后库内必须逐字不变 —— 去零只发生在渲染层，不发生在存储里').toEqual(
    ['Ag=12.345678901200', 'Ni=87.654321098800']);

  // 显示层判据（体验）
  expect(shown, 'AC-30：回填去零 —— 12.3456789012 而不是 12.345678901200').toEqual(
    ['12.3456789012', '87.6543210988']);
  evidence('AC30-roundtrip', `configNo=${cfgNo}\n输入框=${JSON.stringify(shown)}\n` +
    `保存前=${JSON.stringify(before)}\n保存后=${JSON.stringify(after)}`);
});

/** 在一张配方卡片里填 [[元素符号, 含量], ...]。 */
async function fillCard(card: any, rows: [string, string][]) {
  const page = card.page();
  for (let i = 0; i < rows.length; i++) {
    const [sym, pct] = rows[i];
    const existing = await card.locator('.ant-table-tbody tr.ant-table-row').count();
    if (i >= existing) await card.getByRole('button', { name: /添加元素/ }).click();
    const row = card.locator('.ant-table-tbody tr.ant-table-row').nth(i);
    await row.locator('.ant-select').first().click();
    await page.keyboard.type(sym);
    await page.locator('.ant-select-dropdown:visible .ant-select-item-option')
      .filter({ hasText: sym }).first().click();
    await row.locator('input[type="text"], input.ant-input-number-input').last().fill(pct);
  }
}
