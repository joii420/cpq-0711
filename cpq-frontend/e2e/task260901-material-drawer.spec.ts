/**
 * task-260901 · E2E：**材质编辑抽屉**（T-E-01 / T-E-02 / T-E-03 / T-E-11 / T-E-12 / T-E-13）
 * → AC-13 / AC-14 / AC-15 / AC-16 / AC-17 / AC-30 / AC-31。
 *
 * 断言全部来自 `需求文档.md §③` 的 AC 原文与 `原型图/2-材质编辑抽屉.html`、`原型图/3-含量配置抽屉.html`。
 *
 * ⚠️ **选择器免责声明**：本文件在实现落地**之前**写就（testing.md §1：用例不得从实现派生）。
 * 文案类断言（表头文字、tooltip 文案、编号）直接来自 AC 原文，是**判据**，实现必须迁就它们；
 * 结构类定位（`.ant-drawer` / `.ant-table` 等）是**通道**，执行时若与实现不符，
 * 只允许改通道、🚫 不许改判据。
 */
import { test, expect, Page } from '@playwright/test';
import {
  assertIsolatedEnv, assertNoResidue, restoreGlobalState, sql, sqlOne,
  shot, evidence, login, gotoMaterialTab, tooltipOf, switchByLabel, chipCloseByLabel,
  REAL_RECIPE_CODE, AC_PREFIX,
} from './task260901-material.helpers';

test.describe.configure({ mode: 'serial' });

test.beforeAll(() => { assertIsolatedEnv(); restoreGlobalState(); });
// 🚨 还原写在 afterAll（等价 finally），不依赖「跑完手工清一下」
test.afterAll(() => { restoreGlobalState(); assertNoResidue(); });

/**
 * 打开某材质的编辑抽屉。
 * 🚨 **必须验明正身**：antd 会把**已关闭**的抽屉留在 DOM 里，
 * `page.locator('.ant-drawer').last()` 会抓到那个空壳（2026-09-02 实跑踩到：
 * 抓到的抽屉显示「共 0 组」、表头无元素列，看起来像「元素组成没渲染」的产品缺陷，
 * 实际是定位器选错了对象）。⇒ 只认**打开中且文本含目标材质编号**的那个。
 */
async function openRecipeDrawer(page: Page, keyword: string) {
  // 🚨 先关净已打开的抽屉：保存后抽屉不会自动关，遮罩盖住列表 ⇒ 下一次点行 click 超时
  //    （2026-09-02 实跑踩到：报的是「locator.click Timeout」，看起来像列表没渲染，
  //     实际是上一个抽屉还开着）。
  for (let i = 0; i < 3; i++) {
    if (await page.locator('.ant-drawer-open').count() === 0) break;
    await page.keyboard.press('Escape');
    await page.waitForTimeout(500);
  }
  await expect(page.locator('.ant-drawer-open'), '打开新抽屉前旧抽屉须已关净').toHaveCount(0);
  const search = page.getByPlaceholder(/搜索/).first();
  await search.fill(keyword);
  await page.waitForTimeout(900);
  await page.locator('.ant-table-tbody a').filter({ hasText: keyword }).first().click();
  const drawer = page.locator('.ant-drawer-open, .ant-drawer:not([style*="display: none"])')
    .filter({ hasText: keyword }).last();
  await expect(drawer, `应打开材质 ${keyword} 的抽屉`).toBeVisible({ timeout: 15_000 });
  const title = (await drawer.innerText()).split('\n').slice(0, 3).join(' / ');
  console.log(`[抽屉验明正身] keyword=${keyword} → 标题区: ${title}`);
  expect(title, `🚨 抽屉打开的不是 ${keyword}（选错对象会把定位问题误报成产品缺陷）`)
    .toContain(keyword);
  return drawer;
}

/** 配置矩阵：表头 + 数据行。 */
function matrix(drawer: any) {
  return drawer.locator('.ant-table').filter({ hasText: '配置编号' }).first();
}

// ══════════════ T-E-01 → AC-13 ══════════════

test('T-E-01 / AC-13：列表行不展开 · 抽屉宽 1200 · 元素组成区 · 配置矩阵列取自元素组成', async ({ page }) => {
  await login(page);
  await gotoMaterialTab(page);

  // ① 列表行没有展开箭头、点击不展开
  await expect(page.locator('.ant-table-row-expand-icon'),
    'AC-13①：列表行不得有展开箭头 —— 配置只在抽屉里看').toHaveCount(0);
  await shot(page, 'AC13-list-no-expand', { fullPage: true });

  // 前置：先补一条 00006-02（AC-13 的矩阵要有两行）
  const recipeId = sqlOne(`SELECT id FROM material_recipe WHERE code='${REAL_RECIPE_CODE}'`)!;
  const created = await page.request.post(`/api/cpq/material-recipes/${recipeId}/configs`, {
    data: { elements: [
      { elementNo: sqlOne(`SELECT element_no FROM element WHERE element_code='Ag'`), defaultPct: '85' },
      { elementNo: sqlOne(`SELECT element_no FROM element WHERE element_code='Ni'`), defaultPct: '15' },
    ] },
  });
  expect(created.ok(), `前置：新建 00006-02 应成功，实际 ${created.status()} ${await created.text()}`).toBeTruthy();

  const drawer = await openRecipeDrawer(page, REAL_RECIPE_CODE);

  // ② 抽屉宽度 1200px
  // ⚠️ antd v6 无 `.ant-drawer-content`；实际层级是
  //    `.ant-drawer-content-wrapper > .ant-drawer-section`，1200 宽在 section 上
  //    （前端 2026-09-02 实测）。
  const box = await drawer.locator('.ant-drawer-section').first().boundingBox();
  console.log('[AC-13②] 抽屉宽度 =', box?.width);
  expect(Math.round(box!.width), 'AC-13②：抽屉宽度 1200px').toBe(1200);

  // ③ 独立的「元素组成」区，显示 10001 / Ag / 银 与 10005 / Ni / 镍
  // ⚠️ 通道修正（2026-09-02 实跑）：`locator('*').filter(...).last()` 会选到只含
  //    「元素组成」四个字的**最内层文本节点**，chip 不在它里面 ⇒ 恒 not found。
  //    元素组成项渲染成 `10001 / Ag / 银` 这样的 chip，用抽屉整体文本断言最稳。
  await expect(drawer.getByText('元素组成', { exact: true }), 'AC-13③：应有独立的元素组成区').toBeVisible();
  const drawerText = await drawer.innerText();
  console.log('[AC-13③] 抽屉文本 =', drawerText.replace(/\n+/g, ' | ').slice(0, 500));
  expect(drawerText.length, 'AC-13③：抽屉文本非空（空 = 断言空跑）').toBeGreaterThan(0);
  for (const t of ['10001', 'Ag', '银', '10005', 'Ni', '镍']) {
    expect(drawerText, `AC-13③：元素组成区应显示「${t}」（期望形如 10001 / Ag / 银）`).toContain(t);
  }

  // ④ 配置矩阵：表头恰为 配置编号 | Ag | Ni | 合计（列与顺序取自元素组成）
  const m = matrix(drawer);
  const headers = (await m.locator('.ant-table-thead th').allInnerTexts()).map((s: string) => s.trim()).filter(Boolean);
  console.log('[AC-13④] 矩阵表头 =', headers);
  expect(headers.length, 'AC-13④：矩阵表头非空（空表头 = 断言空跑）').toBeGreaterThan(0);
  // 🚨 AC-13④ 判据（2026-09-02 用户裁决收紧）：判的是**元素列及其顺序**恰为元素组成，
  //    **不是**禁止其他列。实际表头为 配置编号 | Ag | Ni | 合计 | 创建时间 | 备注。
  expect(headers[0], 'AC-13④：首列是配置编号').toBe('配置编号');
  expect(headers, 'AC-13④：应有合计列').toContain('合计');
  const elemCols = headers.slice(1, headers.indexOf('合计'));
  console.log('[AC-13④] 元素列 =', elemCols);
  expect(elemCols, 'AC-13④：元素列及其顺序完全取自元素组成（composition.sort_order）').toEqual(
    ['Ag', 'Ni']);

  const rows = await m.locator('.ant-table-tbody tr.ant-table-row').allInnerTexts();
  console.log('[AC-13④] 矩阵数据行 =', rows);
  expect(rows.length, 'AC-13④：矩阵应有两行').toBe(2);
  expect(rows.join(' | ')).toContain('00006-01');
  expect(rows.join(' | ')).toContain('00006-02');
  // AC-30：去掉小数点后多余的 0
  expect(rows.join(' | '), 'AC-13④ / AC-30：90% 而不是 90.000000000000%').toContain('90%');
  expect(rows.join(' | ')).toContain('10%');
  expect(rows.join(' | ')).toContain('85%');
  expect(rows.join(' | ')).toContain('15%');
  expect(rows.join(' | '), 'AC-13④：合计 100%').toContain('100%');

  // ⑤ 矩阵自身横向滚动，body 不出现横向滚动条
  const scrollable = await m.evaluate((el: Element) => {
    // 往上找到第一个 overflow-x 允许滚动的祖先（AntD 是 .ant-table-content / .ant-table-body）
    let n: Element | null = el;
    while (n) {
      const ov = getComputedStyle(n).overflowX;
      if (ov === 'auto' || ov === 'scroll') return { tag: n.className, overflowX: ov };
      n = n.querySelector('.ant-table-content, .ant-table-body');
      if (n && (getComputedStyle(n).overflowX === 'auto' || getComputedStyle(n).overflowX === 'scroll')) {
        return { tag: n.className, overflowX: getComputedStyle(n).overflowX };
      }
      break;
    }
    return null;
  });
  console.log('[AC-13⑤] 矩阵可横向滚动的容器 =', scrollable);
  expect(scrollable, 'AC-13⑤：矩阵自身须有横向滚动容器（overflow-x: auto/scroll）').not.toBeNull();

  const bodyOverflow = await page.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth);
  console.log('[AC-13⑤] body 横向溢出 =', bodyOverflow);
  expect(bodyOverflow, 'AC-13⑤：页面 body 不得出现横向滚动条').toBeLessThanOrEqual(1);

  await shot(page, 'AC13-drawer-matrix');
  evidence('AC13-matrix', `表头: ${JSON.stringify(headers)}\n数据行:\n${rows.join('\n')}`);
});

// ══════════════ T-E-02 → AC-14 / AC-15 ══════════════

test('T-E-02 / AC-14：二级抽屉元素行预填且只读 → 保存得 00006-03 → 矩阵立即多一行', async ({ page }) => {
  await login(page);
  await gotoMaterialTab(page);
  const drawer = await openRecipeDrawer(page, REAL_RECIPE_CODE);

  await drawer.getByRole('button', { name: /新建配置/ }).click();
  const sub = page.locator('.ant-drawer').last();
  await expect(sub, 'AC-14①：二级抽屉应打开').toBeVisible();

  // ① 元素行已按元素组成预填且只读：恰两行 Ag/银、Ni/镍；无元素下拉、无「添加元素」、无行删除
  const elemRows = sub.locator('.ant-table-tbody tr.ant-table-row');
  await expect(elemRows, 'AC-14①：恰两行元素').toHaveCount(2);
  const elemText = (await elemRows.allInnerTexts()).join(' | ');
  console.log('[AC-14①] 二级抽屉元素行 =', elemText);
  expect(elemText).toContain('Ag');
  expect(elemText).toContain('Ni');
  await expect(sub.locator('.ant-select'), 'AC-14①：🚫 不得有元素下拉').toHaveCount(0);
  await expect(sub.getByRole('button', { name: /添加元素/ }), 'AC-14①：🚫 不得有「添加元素」').toHaveCount(0);
  await expect(sub.locator('.ant-table-tbody').getByRole('button', { name: /删除|移除/ }),
    'AC-14①：🚫 不得有行删除按钮').toHaveCount(0);
  await shot(page, 'AC14-sub-drawer-readonly-elements');

  // ② 填 75 / 25 保存
  const inputs = sub.locator('.ant-table-tbody input');
  await inputs.nth(0).fill('75');
  await inputs.nth(1).fill('25');
  // ⚠️ 两字按钮 antd 渲染成「保 存」，用正则不要用 exact
  await sub.locator('.ant-drawer-footer').getByRole('button', { name: /保\s*存/ }).click();
  await page.waitForTimeout(1500);

  const dbNos = sql(`SELECT c.config_no FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id
    WHERE r.code='${REAL_RECIPE_CODE}' AND c.status='ACTIVE' ORDER BY c.seq`);
  console.log('[AC-14②] 库内 ACTIVE 配置 =', dbNos);
  expect(dbNos, 'AC-14②：新配置编号 00006-03（承 AC-13 的 -01/-02）').toEqual(
    ['00006-01', '00006-02', '00006-03']);
  expect(sqlOne(`SELECT count(*) FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id
    WHERE r.code='${REAL_RECIPE_CODE}' AND c.status='ACTIVE'`), 'AC-14④：ACTIVE 配置数 = 3').toBe('3');

  // ③ 返回材质抽屉后矩阵立即多出一行 00006-03 | 75% | 25% | 100%
  const m = matrix(page.locator('.ant-drawer').last());
  await expect(m.locator('.ant-table-tbody tr.ant-table-row'),
    'AC-14③：矩阵立即多一行').toHaveCount(3);
  const row3 = await m.locator('.ant-table-tbody tr.ant-table-row').nth(2).innerText();
  console.log('[AC-14③] 新增行 =', row3);
  expect(row3).toContain('00006-03');
  expect(row3, 'AC-14③ / AC-30：75% 去零显示').toContain('75%');
  expect(row3).toContain('25%');
  expect(row3).toContain('100%');
  await shot(page, 'AC14-matrix-after-create');
});

test('T-E-02 / AC-15：矩阵勾选 00006-02 删除 → 剩 -01/-03，物理行仍在且 INACTIVE，编号不回收得 -04', async ({ page }) => {
  await login(page);
  await gotoMaterialTab(page);
  const drawer = await openRecipeDrawer(page, REAL_RECIPE_CODE);
  const m = matrix(drawer);

  // 勾选 00006-02 那一行
  const targetRow = m.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: '00006-02' }).first();
  await expect(targetRow, 'AC-15：应能定位到 00006-02 行').toBeVisible();
  await targetRow.locator('input[type="checkbox"]').check();

  await drawer.getByRole('button', { name: /删\s*除/ }).first().click();

  // 二次确认抽屉必须列出该配置编号与含量明细
  const confirm = page.locator('.ant-drawer, .ant-modal').last();
  await expect(confirm, 'AC-15：应有二次确认').toBeVisible();
  const confirmText = await confirm.innerText();
  console.log('[AC-15] 二次确认内容 =', confirmText);
  expect(confirmText, 'AC-15：二次确认须列出配置编号').toContain('00006-02');
  expect(confirmText, 'AC-15：二次确认须列出含量明细').toMatch(/85\s*%/);
  await shot(page, 'AC15-delete-confirm');
  await confirm.getByRole('button', { name: /确\s*认|确\s*定|删\s*除/ }).last().click();
  await page.waitForTimeout(1500);

  const active = sql(`SELECT c.config_no FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id
    WHERE r.code='${REAL_RECIPE_CODE}' AND c.status='ACTIVE' ORDER BY c.seq`);
  console.log('[AC-15] ACTIVE =', active);
  expect(active, 'AC-15：矩阵剩 -01 与 -03').toEqual(['00006-01', '00006-03']);
  expect(sqlOne(`SELECT count(*) FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id
    WHERE r.code='${REAL_RECIPE_CODE}'`), 'AC-15：不带状态过滤仍 = 3（物理行保留）').toBe('3');
  expect(sqlOne(`SELECT status FROM material_recipe_config WHERE config_no='00006-02'`),
    "AC-15：软删 ⇒ status='INACTIVE'").toBe('INACTIVE');
  await shot(page, 'AC15-matrix-after-delete');

  // 编号不回收：再新建一条得到 00006-04
  const recipeId = sqlOne(`SELECT id FROM material_recipe WHERE code='${REAL_RECIPE_CODE}'`)!;
  const res = await page.request.post(`/api/cpq/material-recipes/${recipeId}/configs`, {
    data: { elements: [
      { elementNo: sqlOne(`SELECT element_no FROM element WHERE element_code='Ag'`), defaultPct: '60' },
      { elementNo: sqlOne(`SELECT element_no FROM element WHERE element_code='Ni'`), defaultPct: '40' },
    ] },
  });
  expect(res.ok(), await res.text()).toBeTruthy();
  const body = await res.json();
  console.log('[AC-15] 删后再建 configNo =', body.configNo);
  expect(body.configNo, 'AC-15：🚨 编号不回收 —— 应得 00006-04 而不是复用 00006-02').toBe('00006-04');
  evidence('AC15-no-recycle', `删除后 ACTIVE=${JSON.stringify(active)}\n再新建 configNo=${body.configNo}`);
});

// ══════════════ T-E-03 → AC-16 ══════════════

test('T-E-03 / AC-16：「支持自定义含量」开关默认关 → 打开保存 → 重开仍为开', async ({ page }) => {
  await login(page);
  await gotoMaterialTab(page);
  const drawer = await openRecipeDrawer(page, REAL_RECIPE_CODE);

  // 🚨 抽屉里有两个开关（状态/启用 在前，支持自定义含量 在后）——按标签取，不按下标
  const sw = await switchByLabel(page, drawer, '支持自定义含量');
  await expect(sw, 'AC-16：应有「支持自定义含量」开关').toBeVisible();
  await expect(drawer.getByText('支持自定义含量', { exact: false }).first()).toBeVisible();
  expect(await sw.getAttribute('aria-checked'), 'AC-16：默认为关').toBe('false');
  await shot(page, 'AC16-switch-default-off');

  await sw.click();
  await drawer.locator('.ant-drawer-footer').getByRole('button', { name: /保\s*存/ }).click();
  await page.waitForTimeout(1500);

  expect(sqlOne(`SELECT allow_custom_content FROM material_recipe WHERE code='${REAL_RECIPE_CODE}'`),
    'AC-16：库内该字段为 true').toBe('t');

  const drawer2 = await openRecipeDrawer(page, REAL_RECIPE_CODE);
  const sw2 = await switchByLabel(page, drawer2, '支持自定义含量');
  expect(await sw2.getAttribute('aria-checked'),
    'AC-16：重新打开抽屉该开关仍为开').toBe('true');
  await shot(page, 'AC16-switch-persisted-on');
});

// ══════════════ T-E-11 → AC-17（抽屉空态） ══════════════

test('T-E-11 / AC-17：删光配置后 元素组成区照常 · 矩阵表头照常 · 表体空态 · 列表金色 tag', async ({ page }) => {
  await login(page);
  // 前置：建一条 AC测新材（Ag 0.70 / Cu 0.30），再删光其配置
  const ag = sqlOne(`SELECT element_no FROM element WHERE element_code='Ag'`);
  const cu = sqlOne(`SELECT element_no FROM element WHERE element_code='Cu'`);
  const create = await page.request.post('/api/cpq/material-recipes', {
    data: { symbol: `${AC_PREFIX}新材`, name: `${AC_PREFIX}新材`, recipeType: 'locked',
      configs: [{ elements: [
        { elementNo: ag, defaultPct: '70' },
        { elementNo: cu, defaultPct: '30' }] }] },
  });
  expect(create.ok(), `前置建材质应成功：${create.status()} ${await create.text()}`).toBeTruthy();
  const recipe = await create.json();
  const cfgId = sqlOne(`SELECT id FROM material_recipe_config WHERE recipe_id='${recipe.id}'`)!;
  const del = await page.request.delete(`/api/cpq/material-recipes/${recipe.id}/configs/${cfgId}`);
  expect(del.ok()).toBeTruthy();

  await gotoMaterialTab(page);
  const drawer = await openRecipeDrawer(page, recipe.code);

  // ① 元素组成区照常显示 Ag、Cu
  const drawerText = await drawer.innerText();
  console.log('[AC-17①] 抽屉文本片段 =', drawerText.slice(0, 400));
  expect(drawerText, 'AC-17①：元素组成区照常显示 Ag').toContain('Ag');
  expect(drawerText, 'AC-17①：元素组成区照常显示 Cu').toContain('Cu');

  // ② 矩阵表头照常渲染 + 表体空态
  const m = matrix(drawer);
  const headers = (await m.locator('.ant-table-thead th').allInnerTexts()).map((s: string) => s.trim()).filter(Boolean);
  console.log('[AC-17②] 空态下矩阵表头 =', headers);
  expect(headers[0], 'AC-17②：表头照常渲染，首列配置编号').toBe('配置编号');
  const emptyElemCols = headers.slice(1, headers.indexOf('合计'));
  expect(emptyElemCols, 'AC-17②：0 配置时元素列照常来自元素组成 Ag/Cu').toEqual(['Ag', 'Cu']);
  await expect(m.getByText('该材质尚未配置含量'), 'AC-17②：表体空态文案').toBeVisible();
  await expect(drawer.getByRole('button', { name: /新建配置/ }), 'AC-17②：空态仍有「新建配置」').toBeVisible();
  await shot(page, 'AC17-drawer-empty-state');

  // ③ 列表该行仍显示元素组成 tag + 金色「未配置含量」
  await drawer.locator('.ant-drawer-close').first().click().catch(() => {});
  await page.waitForTimeout(600);
  await page.getByPlaceholder(/搜索/).first().fill(recipe.code);
  await page.waitForTimeout(700);
  const listRow = page.locator('.ant-table-tbody tr.ant-table-row').filter({ hasText: recipe.code }).first();
  const rowText = await listRow.innerText();
  console.log('[AC-17③] 列表行 =', rowText);
  expect(rowText, 'AC-17③：「元素组成」列仍显示 Ag').toContain('Ag');
  expect(rowText, 'AC-17③：「元素组成」列仍显示 Cu').toContain('Cu');
  expect(rowText, 'AC-17③：「含量配置」列显示「未配置含量」').toContain('未配置含量');
  await shot(page, 'AC17-list-gold-tag');
});

// ══════════════ T-E-13 → AC-31 ══════════════

test('T-E-13 / AC-31：无配置时元素组成可改 → 建配置后整体只读 + hover 文案', async ({ page }) => {
  await login(page);
  await gotoMaterialTab(page);

  const code = sqlOne(`SELECT code FROM material_recipe WHERE symbol='${AC_PREFIX}新材'`);
  expect(code, '前置：承 AC-17 的 AC测新材 应存在').not.toBeNull();
  const drawer = await openRecipeDrawer(page, code!);

  // ① 无 ACTIVE 配置 ⇒ 元素组成可改：把 Cu 换成 Ni
  // ⚠️ 元素组成区按原型 2 是 **chip 列表**（chip 上挂 .anticon-close），不是表格行
  //    （前端 2026-09-02 实测）。用表格行定位会 timeout 并被误读成「删不掉」。
  // ⚠️ 同 AC-13③ 的坑：`filter({hasText:'元素组成'}).last()` 取到最内层文本节点，chip 不在里面。
  //    直接以抽屉正文为作用域，靠 chip 文本含 'Cu' 认领（抽屉自身的关闭按钮祖先文本不含 Cu）。
  const compArea = drawer.locator('.ant-drawer-body').first();
  const cuClose = await chipCloseByLabel(
    (await compArea.count()) > 0 ? compArea : drawer, 'Cu');
  await cuClose.click();
  await drawer.getByRole('button', { name: /添加元素/ }).click();
  const sel = drawer.locator('.ant-select').last();
  await sel.click();
  await page.keyboard.type('Ni');
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option')
    .filter({ hasText: 'Ni' }).first().click();
  await drawer.locator('.ant-drawer-footer').getByRole('button', { name: /保\s*存/ }).click();
  await page.waitForTimeout(1500);

  const comp = sql(`SELECT c.element_code FROM material_recipe_composition c
    JOIN material_recipe r ON r.id=c.recipe_id WHERE r.code='${code}' ORDER BY c.sort_order`);
  console.log('[AC-31①] 组成 =', comp);
  expect(comp, 'AC-31①：无 ACTIVE 配置时保存成功，组成变为 Ag + Ni').toEqual(['Ag', 'Ni']);
  await shot(page, 'AC31-composition-editable');

  // ② 建一条配置 Ag 0.7 / Ni 0.3
  const recipeId = sqlOne(`SELECT id FROM material_recipe WHERE code='${code}'`)!;
  const res = await page.request.post(`/api/cpq/material-recipes/${recipeId}/configs`, {
    data: { elements: [
      { elementNo: sqlOne(`SELECT element_no FROM element WHERE element_code='Ag'`), defaultPct: '70' },
      { elementNo: sqlOne(`SELECT element_no FROM element WHERE element_code='Ni'`), defaultPct: '30' },
    ] },
  });
  expect(res.ok(), `AC-31②：与新组成匹配的配置应保存成功，实际 ${res.status()} ${await res.text()}`).toBeTruthy();

  // ③ 有配置后元素组成区整体只读，hover 显示原因
  const drawer2 = await openRecipeDrawer(page, code!);
  const addBtn = drawer2.getByRole('button', { name: /添加元素/ });
  await expect(addBtn, 'AC-31③：增删按钮须「禁用可见」，不得消失').toBeVisible();
  await expect(addBtn, 'AC-31③：元素组成区整体只读').toBeDisabled();
  await shot(page, 'AC31-composition-locked');

  // 🚨 **后端 409 先验**（2026-09-02 调序，不弱化任何断言）：
  //    AC-31③ 有两个判据 —— 前端只读（体验）与后端拦截（正确性）。
  //    tooltip 的读取是**通道**，它不稳不该挡住正确性判据的验证。
  //    ⇒ 把后端 409 提到 tooltip 之前，两者都验，但先验重的那个。
  // 🚨 后端必须同样拦（AC-31 原文：绕过前端直接 PUT 返 409）
  const put = await page.request.put(`/api/cpq/material-recipes/${recipeId}`, {
    data: { symbol: `${AC_PREFIX}新材`, recipeType: 'locked', composition: [
      { elementNo: sqlOne(`SELECT element_no FROM element WHERE element_code='Ag'`), sortOrder: 1 },
      { elementNo: sqlOne(`SELECT element_no FROM element WHERE element_code='Cu'`), sortOrder: 2 },
    ] },
  });
  const putBody = await put.text();
  console.log('[AC-31③·后端] ', put.status(), putBody);
  expect(put.status(), 'AC-31③：🚨 绕过前端直接 PUT 必须返 409').toBe(409);
  expect(putBody).toContain('COMPOSITION_LOCKED');
  evidence('AC31-composition-locked-409', `PUT ${put.status()}\n${putBody}`);

  // 前端只读态的 hover 文案（体验判据，放在正确性判据之后）
  const tip = await tooltipOf(page, addBtn);   // 🚨 禁用元素必须 mouse.move，hover 取不到
  console.log('[AC-31③] hover 文案 =', tip);
  expect(tip, 'AC-31③：hover 文案须点名原因与出路').toContain('该材质已有');
  expect(tip).toContain('元素组成不可修改');
  expect(tip).toContain('新建材质');
  evidence('AC31-locked-tooltip', tip);
});
