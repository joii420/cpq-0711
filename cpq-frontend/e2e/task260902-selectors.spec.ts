/**
 * task-260902 · E2E 选择器与空态：**材质选择器 / 含量配置 / 外购件**
 *
 * 覆盖 **AC-5 / AC-6 / AC-16 / AC-17 / AC-18 / AC-18b / AC-23（前端半句）**。
 * 断言逐条来自 `需求文档.md §③` 与 `原型图/` 的逐字文案。
 *
 * 🚨 **两处前置现网构造不出**（外购件 0 条 / 0 组配置的材质），本 spec 用 `page.route()`
 * 拦截前端自己的请求来注入，**不写共享库**（`testing.md §4.3`）。
 * 这两条的**后端半句**分别由 `MaterialAndOutsourcedAcTest#ac16_*` 与 `#ac18b_*` 真跑真库覆盖 ⇒
 * 不存在「只 mock 不验真」的缺口。
 */
import { test, expect, Page } from '@playwright/test';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';
import {
  shot, query, drawer, openSelConfigDrawer, tooltipOf,
  fillStep1, startNewPart, addMaterial, openMaterialPicker, expectNoLoadingPlaceholder,
} from './fixtures/task260902';

let backendUp = false;
test.beforeAll(async () => { backendUp = await isBackendUp(); });

const picker = (page: Page) => page.locator('.ant-drawer, .ant-modal').last();
const NEW_NO = () => `T260902-SEL-${Date.now()}`;

/** 选择器里的命中条数（原型的计数文案：「12 / 262 条」或「共 1 条」）。 */
async function pickerCount(page: Page): Promise<number> {
  const text = await picker(page).innerText();
  const m1 = text.match(/(\d+)\s*\/\s*\d+\s*条/);
  if (m1) return parseInt(m1[1], 10);
  const m2 = text.match(/共\s*(\d+)\s*条/);
  if (m2) return parseInt(m2[1], 10);
  return picker(page).locator('tbody tr, .picker-row, li.picker-row').count();
}

async function typeKeyword(page: Page, kw: string) {
  const box = picker(page).getByPlaceholder(/搜索|材质编号|材质名/).first();
  await box.fill(kw);
  await page.waitForTimeout(1200);
}

test.describe('task-260902 选择器与空态', () => {

  /**
   * **AC-18**（边界）：材质选择器的六次搜索输入，**每次一张截图**。
   * ①空 ②`00006` ③`AgNi` ④`agni` ⑤`镀铜` ⑥`法兰`。
   * ⚠️ 250+ 条走虚拟滚动 ⇒ **必须先输入过滤再点选**，靠滚动找会随机挂。
   * 📌 ④ 的「编号集合完全相同」在接口层（`MaterialAndOutsourcedAcTest#ac18_*`）做精确集合比较；
   *    E2E 这层验的是**同一次运行内两种大小写的命中面一致**，两层合起来才完整。
   */
  test('AC-18 材质选择器六种搜索输入', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await loginAsAdmin(page);
    await openSelConfigDrawer(page, 'ac18');
    await fillStep1(page, NEW_NO());
    await startNewPart(page, '触点', 'φ5', '5×3×2', '10');
    await openMaterialPicker(page);

    // ① 空输入：每行显示 材质编号/材质名/首个配置含量/含量配置组数/是否支持自定义
    await expectNoLoadingPlaceholder(page, '.ant-drawer, .ant-modal');
    const firstRow = picker(page).locator('tbody tr, .picker-row').first();
    await expect(firstRow, 'AC-18①：默认应列出材质（空列表 ⇒ 后面全部断言空跑）').toBeVisible({ timeout: 10000 });
    const headerText = await picker(page).innerText();
    for (const col of ['材质编号', '材质名', '含量配置', '自定义']) {
      expect(headerText, `AC-18①：选择器须显示「${col}」列`).toContain(col);
    }
    await shot(page, 'AC-18-1-空输入');

    // ② 00006 → 恰好 1 条
    await typeKeyword(page, '00006');
    const rows00006 = picker(page).locator('tbody tr, .picker-row');
    await expect(rows00006, 'AC-18②：搜 00006 应恰好命中 1 条').toHaveCount(1);
    await expect(picker(page), 'AC-18②：命中的应是 00006 / AgNi10').toContainText('AgNi10');
    await shot(page, 'AC-18-2-编号精确匹配');

    // ③ AgNi → ≥40 条，且必含 00006 与 00197
    await typeKeyword(page, 'AgNi');
    const upperCount = await pickerCount(page);
    console.log(`[AC-18③] AgNi 命中 ${upperCount} 条`);
    expect(upperCount, 'AC-18③：搜 AgNi 应命中 ≥40 条（取数当日 42）').toBeGreaterThanOrEqual(40);
    await expect(picker(page), 'AC-18③：结果必含 00006').toContainText('00006');
    await typeKeyword(page, '00197');
    await expect(picker(page), 'AC-18③：结果必含 00197 / AgNi10/Ag15CuP').toContainText('00197');
    await typeKeyword(page, 'AgNi');
    await shot(page, 'AC-18-3-AgNi大写');

    // ④ agni → 与 ③ 一致（大小写不敏感）
    await typeKeyword(page, 'agni');
    const lowerCount = await pickerCount(page);
    console.log(`[AC-18④] agni 命中 ${lowerCount} 条`);
    expect(lowerCount, 'AC-18④：大小写不敏感 ⇒ agni 与 AgNi 的命中面必须一致').toBe(upperCount);
    await expect(picker(page), 'AC-18④：小写搜索同样应命中 00006').toContainText('00006');
    await shot(page, 'AC-18-4-agni小写');

    // ⑤ 镀铜 → 中文名可搜
    await typeKeyword(page, '镀铜');
    await expect(picker(page), 'AC-18⑤：应筛出 00150 / DCO3镀铜（中文名可搜）').toContainText('00150');
    await expect(picker(page), 'AC-18⑤：应筛出 00151 / 铁镀铜').toContainText('00151');
    await shot(page, 'AC-18-5-中文名搜索');

    // ⑥ 法兰 → 空态文案，🚫 不是空白也不是「加载中…」
    await typeKeyword(page, '法兰');
    await expect(picker(page).getByText('没有匹配「法兰」的材质'),
      'AC-18⑥：无匹配时应显示空态文案「没有匹配「法兰」的材质」（原型逐字）'
    ).toBeVisible({ timeout: 8000 });
    await expect(picker(page), 'AC-18⑥：空态须提示可用编号或名称搜索').toContainText(/编号|名称/);
    await expectNoLoadingPlaceholder(page, '.ant-drawer, .ant-modal');
    await shot(page, 'AC-18-6-空态');
  });

  /**
   * **AC-17**（边界）：「零件已添加材质 `00006/AgNi10`，再次点『+ 添加材质』」⇒
   * 「材质选择器里 `00006` 那一行**灰显不可选**，『选择』按钮禁用、hover tooltip 显示『该材质已添加』。
   * 🚫 **不得从列表中过滤掉该行**（§1.2：禁止 `if(...) return null` 隐藏能力）」。
   */
  test('AC-17 已添加的材质在选择器里灰显，且不得被过滤掉', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await loginAsAdmin(page);
    await openSelConfigDrawer(page, 'ac17');
    await fillStep1(page, NEW_NO());
    await startNewPart(page, '触点', 'φ5', '5×3×2', '10');
    await addMaterial(page, '00006', '100');

    await openMaterialPicker(page);
    await typeKeyword(page, '00006');
    const row = picker(page).locator('tbody tr, .picker-row').filter({ hasText: '00006' }).first();
    await expect(row, 'AC-17：🚫 已添加的材质**仍须出现在列表中**（过滤掉 = 用户以为材质丢了）')
      .toBeVisible({ timeout: 8000 });
    const btn = row.getByRole('button', { name: /选\s*择/ }).first();
    await expect(btn, 'AC-17：「选择」按钮必须可见').toBeVisible();
    await expect(btn, 'AC-17：「选择」按钮必须禁用').toBeDisabled();
    const tip = await tooltipOf(page, btn);
    console.log(`[AC-17] tooltip = ${tip}`);
    expect(tip, 'AC-17：tooltip 应为「该材质已添加」（原型逐字）').toContain('该材质已添加');
    await shot(page, 'AC-17-已添加材质灰显');
  });

  /**
   * **AC-6**：「材质 `00006/AgNi10` 的 `allow_custom_content=false` → 点『自定义含量』」⇒
   * 「入口**可见但禁用**，tooltip『该材质不支持自定义含量』」。
   * 🚫 禁用 ≠ 隐藏（§1.2）。
   */
  test('AC-6 不支持自定义含量 → 入口可见但禁用 + tooltip', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    const allow = query(`SELECT allow_custom_content FROM material_recipe WHERE code='00006'`);
    expect(allow, 'AC-6 前置：00006 的 allow_custom_content 应为 false').toBe('f');

    await loginAsAdmin(page);
    await openSelConfigDrawer(page, 'ac6');
    await fillStep1(page, NEW_NO());
    await startNewPart(page, '触点', 'φ5', '5×3×2', '10');
    await addMaterial(page, '00006', '100');

    const custom = drawer(page).getByRole('button', { name: /自定义含量/ }).first();
    await expect(custom, 'AC-6：「自定义含量」入口必须可见（🚫 不许隐藏）').toBeVisible({ timeout: 8000 });
    await expect(custom, 'AC-6：不支持自定义时入口必须禁用').toBeDisabled();
    const tip = await tooltipOf(page, custom);
    console.log(`[AC-6] tooltip = ${tip}`);
    expect(tip, 'AC-6：tooltip 应为「该材质不支持自定义含量」（原型逐字）').toContain('该材质不支持自定义含量');
    await shot(page, 'AC-6-自定义含量禁用');
  });

  /**
   * **AC-18b**（边界）：「**0 组 ACTIVE 配置**的材质」⇒
   * 「该材质**出现在列表中但灰显**，『选择』禁用、tooltip『该材质尚未配置含量』，
   * 『含量配置』列显示红色 `0 组`。🚫 **不得从列表中过滤掉**」。
   *
   * 🚨 现网 258 条 ACTIVE 材质**每条恰好 1 组**，0 组的一条都没有 ⇒
   * 本用例用 `page.route()` 往真实响应里**注入一条 0 组材质**（不写库、零全局副作用）。
   * 后端侧「0 组材质不得被过滤掉」由 `MaterialAndOutsourcedAcTest#ac18b_*` 在真库上验。
   */
  test('AC-18b 0 组配置的材质：出现但灰显 + 红色 0 组', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    const FAKE = 'T260902-M18b';

    await page.route('**/api/cpq/material-recipes**', async (route) => {
      const res = await route.fetch();
      let body: any;
      try { body = await res.json(); } catch { return route.fulfill({ response: res }); }
      const row = {
        id: '00000000-0000-0000-0000-000000260902', code: FAKE, symbol: FAKE, name: FAKE,
        status: 'ACTIVE', sortOrder: 9902, recipeType: 'locked', specLabel: null,
        configCount: 0, allowCustomContent: false, elementCodes: ['Ag'],
      };
      if (Array.isArray(body)) body.unshift(row);
      else if (Array.isArray(body?.data)) body.data.unshift(row);
      await route.fulfill({ response: res, body: JSON.stringify(body) });
    });

    await loginAsAdmin(page);
    await openSelConfigDrawer(page, 'ac18b');
    await fillStep1(page, NEW_NO());
    await startNewPart(page, '触点', 'φ5', '5×3×2', '10');
    await openMaterialPicker(page);
    await typeKeyword(page, 'T260902');

    const row = picker(page).locator('tbody tr, .picker-row').filter({ hasText: FAKE }).first();
    await expect(row, 'AC-18b：0 组配置的材质🚫 不得被过滤掉，必须出现在列表里').toBeVisible({ timeout: 8000 });
    await expect(row, 'AC-18b：「含量配置」列应显示 0 组').toContainText('0 组');
    const btn = row.getByRole('button', { name: /选\s*择/ }).first();
    await expect(btn, 'AC-18b：「选择」按钮必须可见').toBeVisible();
    await expect(btn, 'AC-18b：0 组配置时「选择」必须禁用').toBeDisabled();
    const tip = await tooltipOf(page, btn);
    console.log(`[AC-18b] tooltip = ${tip}`);
    expect(tip, 'AC-18b：tooltip 应为「该材质尚未配置含量」（原型逐字）').toContain('该材质尚未配置含量');
    await shot(page, 'AC-18b-零组配置灰显');
  });

  /**
   * **AC-5**：「选『外购件』类型 → 打开外购件选择列表」⇒
   * 「列表**只列** `material_master.material_type='外购件'` 的料号」。
   * ⚠️ 实测仅 1 条（`TEST-Q13-CODE / 组成件1`，**规格与单重均为空**）⇒
   * 🚫 列宽/空值处理不得假设这两列有值。
   */
  test('AC-5 外购件列表只列外购件类型料号', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    const expected = query(`SELECT count(*) FROM material_master WHERE material_type='外购件'`);
    console.log(`[AC-5] SQL 对账：外购件 ${expected} 条`);
    expect(Number(expected), 'AC-5 前置：库里应至少 1 条外购件').toBeGreaterThan(0);

    await loginAsAdmin(page);
    await openSelConfigDrawer(page, 'ac5');
    await fillStep1(page, NEW_NO());
    await drawer(page).getByRole('button', { name: /添加配件|添加第一个配件/ }).first().click();
    await page.waitForTimeout(500);
    await drawer(page).getByText('外购件', { exact: false }).first().click();
    await page.waitForTimeout(1200);

    await expectNoLoadingPlaceholder(page);
    await expect(drawer(page), 'AC-5：列表应含实测唯一的外购件 TEST-Q13-CODE / 组成件1')
      .toContainText('TEST-Q13-CODE', { timeout: 10000 });
    const text = await drawer(page).innerText();
    const m = text.match(/共\s*(\d+)\s*条/);
    if (m) {
      console.log(`[AC-5] 列表显示 ${m[1]} 条，SQL ${expected} 条`);
      expect(m[1], 'AC-5：列表条数应与 material_type=\'外购件\' 的 SQL 结果一致').toBe(expected);
    }
    await shot(page, 'AC-5-外购件候选列表');
  });

  /**
   * **AC-16**（边界）：「外购件列表为**空**」⇒
   * 「显示空态文案 + 指路到料号维护，**不得**显示『加载中…』永久占位（AP-31 族）」。
   *
   * 🚨 现网有 1 条，直接跑会看到列表而不是空态 ⇒ 用 `page.route()` 把该端点响应置成 0 条
   * （🚫 不改库；改库属 `testing.md §4.3` 禁止的全局状态污染）。
   * 后端「0 条也必须 200 + total=0」由 `MaterialAndOutsourcedAcTest#ac16_*` 在真库上验
   * （它自建 0 条场景并在 finally 还原）。
   */
  test('AC-16 外购件为空 → 空态 + 两个出口，🚫 不是「加载中…」', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await page.route('**/quotations/configure/outsourced-parts**', (route) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ total: 0, items: [] }) }));

    await loginAsAdmin(page);
    await openSelConfigDrawer(page, 'ac16');
    await fillStep1(page, NEW_NO());
    await drawer(page).getByRole('button', { name: /添加配件|添加第一个配件/ }).first().click();
    await page.waitForTimeout(500);
    await drawer(page).getByText('外购件', { exact: false }).first().click();
    await page.waitForTimeout(1500);

    await expect(drawer(page).getByText('料号库里还没有外购件'),
      'AC-16：应显示空态文案「料号库里还没有外购件」（原型 5 逐字）'
    ).toBeVisible({ timeout: 10000 });
    await expect(drawer(page).getByRole('button', { name: /打开料号维护/ }),
      'AC-16：空态须给出「→ 打开料号维护」出口'
    ).toBeVisible();
    await expect(drawer(page).getByRole('button', { name: /改为添加零件/ }),
      'AC-16：空态须给出「← 改为添加零件」出口'
    ).toBeVisible();
    // 🚨 AP-31 族的直接守卫
    await expectNoLoadingPlaceholder(page);
    await shot(page, 'AC-16-外购件空态');
  });

  /**
   * **AC-23**（边界，前端半句）：「品名输入 **101 个字符**」⇒
   * 「① 前端在输入框层面**拦住**（maxLength 或校验提示）」。
   * 后端半句（400 `PART_TEXT_TOO_LONG` + 🚫 落库截断）由
   * `SubmitAndValidationAcTest#ac23_*` 覆盖。
   */
  test('AC-23 品名 101 字符 → 前端层面拦住', async ({ page }) => {
    test.skip(!backendUp, '后端未启动');
    await loginAsAdmin(page);
    await openSelConfigDrawer(page, 'ac23');
    await fillStep1(page, NEW_NO());
    await drawer(page).getByRole('button', { name: /添加配件|添加第一个配件/ }).first().click();
    await page.waitForTimeout(500);
    await drawer(page).getByText('零件', { exact: true }).first().click();
    await page.waitForTimeout(400);
    await drawer(page).getByText('新建零件', { exact: false }).first().click();
    await page.waitForTimeout(800);

    const longName = 'T260902-' + '长'.repeat(93);   // 101 字符
    expect(longName.length).toBe(101);
    const nameInput = drawer(page).getByLabel(/品名/).first();
    await nameInput.fill(longName);
    await page.waitForTimeout(500);

    const value = await nameInput.inputValue();
    const hasError = await drawer(page).getByText(/不能超过|最多.*100|超长/).count();
    console.log(`[AC-23] 输入框实际长度 = ${value.length}，校验提示条数 = ${hasError}`);
    expect(value.length <= 100 || hasError > 0,
      `AC-23①：品名 101 字符必须被前端拦住（maxLength 截到 ≤100 或给出校验提示），实际长度=${value.length}`
    ).toBeTruthy();
    await shot(page, 'AC-23-超长品名被拦');
  });
});
