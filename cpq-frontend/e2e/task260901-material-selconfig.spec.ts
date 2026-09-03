/**
 * task-260901 · E2E：**选配侧含量配置**（T-E-06 / T-E-07 / T-E-09 / T-E-16③④）
 * → AC-17(选配灰显) / AC-18 / AC-19 / AC-22(序列) / AC-30(选配下拉去零) / AC-35③④。
 *
 * 对照 `原型图/5-选配含量配置选择.html`。
 *
 * ⚠️ **依赖既有报价夹具**（苏州西门子 + 报价模板0608 + 默认分类）。
 *    memory 已登记：`quotation-flow.spec.ts` 在干净 master 上恒 3 条失败（夹具缺产品分类）。
 *    本 spec 若在 Step1 卡住，**先做 A/B 同型对比再归因**，别当成本任务改坏的。
 *
 * ⚠️ **虚拟滚动**：选配材质候选实测 258 项，AntD Select 走虚拟滚动 ——
 *    没渲染的选项在 DOM 里不存在。**一律先输入过滤再点选**，靠滚动找会随机挂。
 */
import { test, expect, Page } from '@playwright/test';
import {
  assertIsolatedEnv, assertNoResidue, restoreGlobalState, sql, sqlOne,
  shot, evidence, login, gotoMaterialTab, tooltipOf, selectByNearbyLabel,
  restoreCustomConfiguredParts, REAL_RECIPE_CODE,
} from './task260901-material.helpers';

test.describe.configure({ mode: 'serial' });
test.beforeAll(() => { assertIsolatedEnv(); restoreGlobalState(); seedTwoConfigs(); });
/**
 * 🚨 还原面登记（2026-09-02 扩大）：AC-19 的自定义含量提交会**真建料号** ——
 * `material_master` + `material_bom_item` + `element_bom_item` 各落行。
 * 这三张表不在 test.md §1 最初登记的三张表里，故在此显式清理，
 * 判据「material_recipe_id 与 config_fingerprint 双非空」在 dev 库基线实测为 0 行。
 */
test.afterAll(() => {
  console.log('[还原]', restoreCustomConfiguredParts());
  restoreGlobalState();
  assertNoResidue();
});

const elNo = (s: string) => sqlOne(`SELECT element_no FROM element WHERE element_code='${s}'`)!;

/** 前置：AC-18 要求 00006 有两条配置。直接打接口建，避免依赖别的 spec 的执行顺序。 */
function seedTwoConfigs() {
  // 由 spec 内的 request 上下文建不方便（需登录），改用 SQL 兜底断言；实际新增在 test 内做。
  console.log('[selconfig] 前置：00006 现有 ACTIVE 配置 =',
    sql(`SELECT config_no FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id
         WHERE r.code='${REAL_RECIPE_CODE}' AND c.status='ACTIVE' ORDER BY seq`));
}

async function ensureSecondConfig(page: Page) {
  const recipeId = sqlOne(`SELECT id FROM material_recipe WHERE code='${REAL_RECIPE_CODE}'`)!;
  const active = sql(`SELECT config_no FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id
    WHERE r.code='${REAL_RECIPE_CODE}' AND c.status='ACTIVE' ORDER BY seq`);
  if (active.length >= 2) return active;
  const res = await page.request.post(`/api/cpq/material-recipes/${recipeId}/configs`, {
    data: { elements: [
      // 与 AC-7 导入夹具产出的 00006-02 一致（Ag 85 / Ni 15），
      // 这样 AC-18 的下拉断言与 AC-22 的删除对象口径统一
      { elementNo: elNo('Ag'), defaultPct: '85' },
      { elementNo: elNo('Ni'), defaultPct: '15' } ] },
  });
  expect(res.ok(), `前置：建第二条配置应成功，实际 ${res.status()} ${await res.text()}`).toBeTruthy();
  return sql(`SELECT config_no FROM material_recipe_config c JOIN material_recipe r ON r.id=c.recipe_id
    WHERE r.code='${REAL_RECIPE_CODE}' AND c.status='ACTIVE' ORDER BY seq`);
}

/** 用既有草稿（DRAFT）报价单，供选配用例复用。 */
const SEED_QUOTATION = process.env.PW_QUOTATION || 'QT-20260901-0234';

/**
 * 走到选配子抽屉的「Step① 材质」。
 *
 * 🚨 **刻意不走 `/quotations/new` 新建路径**（2026-09-02 改）：
 * 「苏州西门子 + 默认分类」下页面提示
 * 「未找到适用的报价模板：该客户在此产品分类下没有客户专属模板，系统中也没有通用模板」，
 * 「下一步」**恒禁用**。这与 memory 里登记的 `quotation-flow.spec.ts` 干净 master 恒 3 失败
 * **同源（夹具单缺产品分类），非本次引入**。
 * ⇒ 若照旧走新建路径，红出来的是「下一步点不动」，**长得像我们的功能没做出来**。
 * ⇒ 改走**既有草稿的编辑态**直接进 Step2。
 */
async function openSelConfigDrawer(page: Page, _unusedName: string) {
  // 🚨 直接走 `/quotations/{id}/edit` 深链：
  //    ① 点列表里的单号进的是**详情页**（只读），还得再点「编辑」才进向导；
  //    ② 而详情页那个「编辑」按钮 `getByRole('button',{name:/^编\s*辑$/})` 定位不到
  //       （2026-09-02 实测：按 innerText 逐个扫才点得到）。
  //    ⇒ 从库取 id 直接深链，比在页面上转两跳稳得多。
  const qid = sqlOne(`SELECT id FROM quotation WHERE quotation_number = '${SEED_QUOTATION}'`);
  expect(qid,
    `前置：草稿报价单 ${SEED_QUOTATION} 应存在（可用 PW_QUOTATION 换一张 DRAFT 单）。` +
    `🚫 不要退回 /quotations/new —— 那条路被夹具堵死（无适用报价模板），会把夹具问题红成产品缺陷`)
    .not.toBeNull();
  await page.goto(`/quotations/${qid}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);
  const steps = await page.locator('.ant-steps-item-title').allInnerTexts().catch(() => []);
  console.log('[selconfig] 向导步骤 =', steps);
  expect(steps.length, '应进入报价向导编辑态').toBeGreaterThan(0);
  // 编辑态停在 Step1「选择客户」，往后推一步到 Step2「添加产品」
  const next = page.getByRole('button', { name: /下一步/ }).first();
  if (await next.isVisible().catch(() => false) && await next.isEnabled().catch(() => false)) {
    await next.click();
    await page.waitForLoadState('networkidle');
    await page.waitForTimeout(2000);
  }

  await page.getByRole('button', { name: /添加产品/ }).first().click();
  await page.waitForTimeout(500);
  await page.locator('text=选配添加').first().click();
  await page.waitForTimeout(800);
  const drawer = page.locator('.ant-drawer').last();
  await drawer.locator('button:has-text("新增材质料号")').first().click();
  await page.waitForTimeout(600);
  return drawer;
}


/**
 * 逐步走完「加入报价单」。
 * 🚨 每一步都**断言按钮可见后再点**，🚫 不用 `.catch(()=>{})` 吞掉失败 ——
 * 吞掉之后失败会漂到后面的 DB 断言上，报成「写入点没跑」，把定位问题伪装成产品缺陷
 * （2026-09-02 实跑踩到）。
 */
async function confirmAddIntoQuotation(page: Page, drawer: any, tag: string) {
  for (const label of [/下一步/, /确认添加/, /确认加入/]) {
    const btn = drawer.locator('button').filter({ hasText: label }).last();
    const cnt = await btn.count();
    console.log(`[${tag}] 步骤按钮 ${label} 命中 ${cnt} 个`);
    if (cnt === 0) continue;
    await expect(btn, `${tag}：按钮 ${label} 应可点`).toBeEnabled({ timeout: 10_000 });
    await btn.click();
    await page.waitForTimeout(1200);
  }
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3500);
}

async function selectByLabel(page: Page, label: string, search: string) {
  const item = page.locator('.ant-form-item').filter({ has: page.locator('label', { hasText: label }) }).first();
  await item.locator('.ant-select').first().click();
  await page.waitForTimeout(300);
  await page.keyboard.type(search, { delay: 60 });
  await page.waitForTimeout(900);
  await page.locator('.ant-select-item-option').filter({ hasText: search }).first().click();
  await page.waitForTimeout(400);
}

// ══════════════ T-E-06 → AC-18 + AC-30 + AC-35③④ ══════════════

test('T-E-06 / AC-18：选中 AgNi10 → 含量配置下拉列出 2 项 · 自定义入口可见但禁用 + tooltip', async ({ page }) => {
  await login(page);
  const active = await ensureSecondConfig(page);
  expect(active.length, 'AC-18 前置：00006 应有 2 条 ACTIVE 配置').toBeGreaterThanOrEqual(2);
  expect(sqlOne(`SELECT allow_custom_content FROM material_recipe WHERE code='${REAL_RECIPE_CODE}'`),
    'AC-18 前置：「支持自定义含量」= 关').toBe('f');

  const drawer = await openSelConfigDrawer(page, 'AC260901-sel-' + Date.now());

  // AC-35③ 材质下拉：输入 00006 与 AgNi10 都能筛出（🚨 必须先输入过滤，258 项虚拟滚动）
  const matSel = drawer.locator('.ant-select').first();
  for (const kw of ['00006', 'AgNi10']) {
    await matSel.click();
    await page.keyboard.press('Control+A');
    await page.keyboard.type(kw, { delay: 50 });
    const opt = page.locator('.ant-select-dropdown:visible .ant-select-item-option')
      .filter({ hasText: '00006' });
    await expect(opt.first(), `AC-35③：输入「${kw}」应筛出 00006 / AgNi10`).toBeVisible({ timeout: 8_000 });
    console.log(`[AC-35③] 输入 ${kw} → `, await opt.allInnerTexts());
    await shot(page, `AC35-sel-material-filter-${kw}`);
  }
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option')
    .filter({ hasText: '00006' }).first().click();
  await page.waitForTimeout(800);

  // AC-18 含量配置下拉：2 项，文案形如 00006-01（Ag 90% / Ni 10%）
  const cfgLabel = drawer.getByText(/含量配置|只列该材质启用中的配置/).first();
  await expect(cfgLabel, 'AC-18：应出现「含量配置」下拉').toBeVisible();
  const cfgSel = await selectByNearbyLabel(drawer, /含量配置|只列该材质启用中的配置|\d{5}-\d{2}（/);
  await cfgSel.click();
  await page.waitForTimeout(600);
  const opts = page.locator('.ant-select-dropdown:visible .ant-select-item-option');
  const optTexts = await opts.allInnerTexts();
  console.log('[AC-18] 含量配置候选 =', optTexts);
  expect(optTexts.length, 'AC-18：应列出 2 项').toBe(2);
  expect(optTexts[0], 'AC-18：文案形如 00006-01（Ag 90% / Ni 10%）').toContain('00006-01');
  expect(optTexts[0]).toMatch(/Ag\s*90\s*%/);
  expect(optTexts[0], 'AC-30：选配下拉同样去尾随零').not.toContain('90.000000000000');
  expect(optTexts[0]).toMatch(/Ni\s*10\s*%/);
  await shot(page, 'AC18-config-dropdown');

  // AC-35④ 输入 -02 能筛出 00006-02
  await page.keyboard.type('-02', { delay: 50 });
  await page.waitForTimeout(600);
  const filtered = await page.locator('.ant-select-dropdown:visible .ant-select-item-option').allInnerTexts();
  console.log('[AC-35④] 输入 -02 → ', filtered);
  expect(filtered.length, 'AC-35④：应筛出结果（空 = 断言空跑）').toBeGreaterThan(0);
  expect(filtered.join(' '), 'AC-35④：输入 -02 应筛出 00006-02').toContain('00006-02');
  await shot(page, 'AC35-sel-config-filter');
  await page.keyboard.press('Escape');

  // AC-18 自定义含量入口：可见但禁用 + hover tooltip
  const custom = drawer.getByText('自定义含量', { exact: false }).first();
  await expect(custom, 'AC-18：「自定义含量」入口可见（🚫 不许隐藏）').toBeVisible();
  const customCtl = drawer.locator('.ant-radio-wrapper, .ant-btn, label')
    .filter({ hasText: /自定义含量/ }).first();
  await expect(customCtl, 'AC-18：「自定义含量」入口禁用').toBeDisabled();
  // 🚨 禁用元素的 tooltip 必须 mouse.move（前端 2026-09-02 实测，hover 取不到）
  const tip = await tooltipOf(page, customCtl);
  console.log('[AC-18] 自定义含量 tooltip =', tip);
  expect(tip, 'AC-18：hover 显示「该材质不支持自定义含量」').toContain('该材质不支持自定义含量');
  await shot(page, 'AC18-custom-disabled-tooltip');
  evidence('AC18-selconfig', `配置候选=${JSON.stringify(optTexts)}\n-02 过滤=${JSON.stringify(filtered)}\ntooltip=${tip}`);
});

// ══════════════ T-E-06b → AC-17（选配侧灰显） ══════════════

test('T-E-06b / AC-17④：0 配置的材质在选配材质下拉里灰显不可选，右侧写「该材质尚未配置含量」', async ({ page }) => {
  await login(page);
  // 造一条 0 配置材质
  const create = await page.request.post('/api/cpq/material-recipes', {
    data: { symbol: 'AC测无配置选配', name: 'AC测无配置选配', recipeType: 'locked',
      configs: [{ elements: [{ elementNo: elNo('Ag'), defaultPct: '100' }] }] },
  });
  expect(create.ok(), await create.text()).toBeTruthy();
  const r = await create.json();
  const cfgId = sqlOne(`SELECT id FROM material_recipe_config WHERE recipe_id='${r.id}'`)!;
  expect((await page.request.delete(`/api/cpq/material-recipes/${r.id}/configs/${cfgId}`)).ok()).toBeTruthy();
  expect(sqlOne(`SELECT count(*) FROM material_recipe_config c JOIN material_recipe rr ON rr.id=c.recipe_id
    WHERE rr.code='${r.code}' AND c.status='ACTIVE'`), '前置：该材质 0 条 ACTIVE 配置').toBe('0');

  const drawer = await openSelConfigDrawer(page, 'AC260901-sel-noconfig-' + Date.now());
  const matSel = drawer.locator('.ant-select').first();
  await matSel.click();
  await page.keyboard.type(r.code, { delay: 50 });
  const opt = page.locator('.ant-select-dropdown:visible .ant-select-item-option')
    .filter({ hasText: r.code }).first();
  await expect(opt, 'AC-17④：材质仍在候选里 —— 🚫 不许从列表消失，否则用户以为材质丢了').toBeVisible({ timeout: 8_000 });
  const cls = await opt.getAttribute('class');
  const txt = await opt.innerText();
  console.log('[AC-17④] 候选项 class =', cls, ' text =', txt);
  expect(cls, 'AC-17④：灰显不可选').toContain('ant-select-item-option-disabled');
  expect(txt, 'AC-17④：右侧写明原因').toContain('该材质尚未配置含量');
  await shot(page, 'AC17-selconfig-disabled-option');
  evidence('AC17-selconfig-disabled', `class=${cls}\ntext=${txt}`);
});

// ══════════════ T-E-07 → AC-19 ══════════════

test('T-E-07 / AC-19：开关打开 → 自定义 Ag 0.88 / Ni 0.12 提交成功，element_bom_item 落 88/12，材质库零新增；Σ=1.08 被拦', async ({ page }) => {
  await login(page);
  const recipeId = sqlOne(`SELECT id FROM material_recipe WHERE code='${REAL_RECIPE_CODE}'`)!;
  const put = await page.request.put(`/api/cpq/material-recipes/${recipeId}`, {
    data: { symbol: 'AgNi10', recipeType: 'locked', allowCustomContent: true },
  });
  expect(put.ok(), `前置：开关置 true 应成功，实际 ${put.status()} ${await put.text()}`).toBeTruthy();
  expect(sqlOne(`SELECT allow_custom_content FROM material_recipe WHERE code='${REAL_RECIPE_CODE}'`),
    '前置确认：开关真的置成 true（不确认就往下 = 可能在验一个没生效的前置）').toBe('t');

  const recipesBefore = sqlOne(`SELECT count(*) FROM material_recipe`);
  const configsBefore = sqlOne(`SELECT count(*) FROM material_recipe_config`);

  const name = 'AC260901-custom-' + Date.now();
  const drawer = await openSelConfigDrawer(page, name);
  const matSel = drawer.locator('.ant-select').first();
  await matSel.click();
  await page.keyboard.type('00006', { delay: 50 });
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option')
    .filter({ hasText: '00006' }).first().click();
  await page.waitForTimeout(800);

  // 切到自定义含量
  await drawer.locator('.ant-radio-wrapper, .ant-btn, label').filter({ hasText: /自定义含量/ }).first().click();
  await page.waitForTimeout(500);
  await shot(page, 'AC19-custom-mode');

  // ① Σ=1.08 应被拦
  let inputs = drawer.locator('.ant-table-tbody input.ant-input-number-input, .ant-table-tbody input');
  await inputs.nth(0).fill('88');
  await inputs.nth(1).fill('20');
  await inputs.nth(1).blur();
  await page.waitForTimeout(700);
  const drawerText = await drawer.innerText();
  console.log('[AC-19·Σ] 抽屉提示 =', drawerText.slice(0, 400));
  expect(drawerText, 'AC-19：Σ=1.08 应报「含量合计必须为 1，实际 1.08」').toContain('含量合计必须为 1');
  expect(drawerText).toContain('1.08');
  await shot(page, 'AC19-sum-not-one');

  // ② 改回 Ag 88 / Ni 12 提交
  await inputs.nth(1).fill('12');
  await inputs.nth(1).blur();
  await page.waitForTimeout(600);
  await confirmAddIntoQuotation(page, drawer, 'AC-19');
  await shot(page, 'AC19-after-submit', { fullPage: true });

  // 🚨 逐表断言（2026-09-02 补）：insertMaterialMasterV6 / insertElementBomV6 /
  //    insertMaterialBomItemV6 这三个写入点**在本任务之前从未真实执行过**
  //    —— dev 库基线实测 material_master 1890 行里 material_recipe_id 与
  //    config_fingerprint 双非空 **0 行**（258 条材质全 locked，custom 路径走不通）。
  //    打开 allow_custom_content 等于让一段死代码转活，
  //    🚫 只断言「提交成功」会放过整条零验证的写库链路。

  // ② material_master：出现该自定义料号一行，且两个字段双双非空
  const mm = sql(`SELECT material_no || '|' || COALESCE(material_recipe_id::text,'<NULL>')
      || '|' || COALESCE(config_fingerprint,'<NULL>')
    FROM material_master WHERE material_recipe_id IS NOT NULL`);
  console.log('[AC-19②] material_master 自定义料号 =', mm);
  expect(mm.length, 'AC-19②：material_master 应恰有本次建的 1 行自定义料号（0 = 写入点没跑）').toBe(1);
  const partNo = mm[0].split('|')[0];
  console.log('[AC-19②] 料号 =', partNo);
  expect(mm[0].split('|')[1], 'AC-19②a：material_recipe_id 非空').not.toBe('<NULL>');

  // ① element_bom_item：Ag=88 / Ni=12
  //    ⚠️ 该表**没有** material_master_id / element_code 列：料号在 `material_no`，
  //       元素符号在 `component_no`（2026-09-02 查 information_schema 实证）。
  const bom = sql(`SELECT component_no || '=' || content::text FROM element_bom_item
    WHERE material_no = '${partNo}' ORDER BY component_no`);
  console.log('[AC-19①] element_bom_item =', bom);
  expect(bom.length, 'AC-19①：element_bom_item 应有 2 行（空 = 断言空跑）').toBe(2);
  expect(bom.join(' '), 'AC-19①：Ag = 88').toMatch(/Ag=88(\.0+)?/);
  expect(bom.join(' '), 'AC-19①：Ni = 12').toMatch(/Ni=12(\.0+)?/);

  // ③ material_bom_item：自指物料行
  const mbi = sql(`SELECT COALESCE(component_no,'<NULL>') || '|' || COALESCE(characteristic,'<NULL>')
    FROM material_bom_item WHERE material_no = '${partNo}'`);
  console.log('[AC-19③] material_bom_item =', mbi);
  expect(mbi.length, 'AC-19③：material_bom_item 应有自指物料行（0 = insertMaterialBomItemV6 没跑）')
    .toBeGreaterThanOrEqual(1);

  // ②b 🔒 **护既有不变量**：config_fingerprint 必须为 NULL（选配 Plan 3b · R1）。
  //     出处：`RECORD.md:4071`（2026-07-08）+ `ConfigureProductService:108 / :376 / :1162`
  //     —— `insertMaterialMasterV6` 的 fingerprint 实参恒传 null，
  //        为的是**防跨客户同物质撞 `uq_material_master_fingerprint` 全局唯一索引**
  //        （撞了会让选配提交 500）。
  //     ⚠️ 这不是「漏写」，是刻意的。**将来若有人把它改成写值，本条会红** —— 那正是本条存在的意义。
  //     📌 `material_recipe_id` 与 `config_fingerprint` 在存量里同为 0 行，但成因完全不同：
  //        前者的 0 = 这条路从没跑通过（提交后即非空）；后者的 0 = 设计如此。别用同一个理由读这两个 0。
  expect(mm[0].split('|')[2],
    'AC-19②b：config_fingerprint 必须为 NULL（Plan 3b R1：防撞 uq_material_master_fingerprint）。'
    + '此条红 = 有人给选配落库写了指纹，会导致跨客户同物质提交 500')
    .toBe('<NULL>');

  // 🚨 不回流：材质与配置表零新增
  expect(sqlOne(`SELECT count(*) FROM material_recipe`),
    'AC-19：material_recipe 零新增（自定义不回流材质库）').toBe(recipesBefore);
  expect(sqlOne(`SELECT count(*) FROM material_recipe_config`),
    'AC-19：material_recipe_config 零新增').toBe(configsBefore);
  evidence('AC19-custom-content',
    `material_master=${JSON.stringify(mm)}\nmaterial_bom_item=${JSON.stringify(mbi)}\n` +
    `element_bom_item=${JSON.stringify(bom)}\n` +
    `material_recipe ${recipesBefore}→${sqlOne(`SELECT count(*) FROM material_recipe`)}\n` +
    `material_recipe_config ${configsBefore}→${sqlOne(`SELECT count(*) FROM material_recipe_config`)}`);
});

// ══════════════ T-E-09 → AC-22（序列） ══════════════

/**
 * 🚨 删除对象是 **00006-02**（测试自建 / AC-7 导入新建），不是 00006-01。
 * 主线 2026-09-02 更正 AC-22 的原因：00006-01 是 S-6 存量迁移来的**真实数据**，
 * 用例若在「删除」与「还原」之间崩溃，会把一条在用的真实配置永久留在 INACTIVE。
 */
test('T-E-09 / AC-22（序列）：选 00006-02 提交 → 删除 00006-02 → 重开报价单，含量仍 Ag 85 / Ni 15', async ({ page }) => {
  await login(page);
  const recipeId = sqlOne(`SELECT id FROM material_recipe WHERE code='${REAL_RECIPE_CODE}'`)!;
  await page.request.put(`/api/cpq/material-recipes/${recipeId}`, {
    data: { symbol: 'AgNi10', recipeType: 'locked', allowCustomContent: false },
  });

  // 前置：确保 00006-02 存在（Ag 85 / Ni 15）—— 它才是本条的删除对象
  const active = await ensureSecondConfig(page);
  expect(active, 'AC-22 前置：00006 下须有 -01 与 -02 两条 ACTIVE 配置').toContain('00006-02');

  // ① 选 00006-02 提交生成报价单
  const name = 'AC260901-seq22-' + Date.now();
  const drawer = await openSelConfigDrawer(page, name);
  const matSel = drawer.locator('.ant-select').first();
  await matSel.click();
  await page.keyboard.type('00006', { delay: 50 });
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option')
    .filter({ hasText: '00006' }).first().click();
  await page.waitForTimeout(800);
  const cfgSel = await selectByNearbyLabel(drawer, /含量配置|只列该材质启用中的配置|\d{5}-\d{2}（/);
  await cfgSel.click();
  await page.waitForTimeout(600);
  await page.keyboard.type('-02', { delay: 50 });
  await page.locator('.ant-select-dropdown:visible .ant-select-item-option')
    .filter({ hasText: '00006-02' }).first().click();
  await page.waitForTimeout(600);
  await confirmAddIntoQuotation(page, drawer, 'AC-22');
  const url = page.url();
  console.log('[AC-22] 报价单 URL =', url);
  await shot(page, 'AC22-quotation-created');

  // ② 在材质管理页删除 00006-02（软删；afterAll 的 restoreGlobalState 会把它物理清掉）
  await gotoMaterialTab(page);
  const cfgId = sqlOne(`SELECT id FROM material_recipe_config WHERE config_no='00006-02'`)!;
  const del = await page.request.delete(`/api/cpq/material-recipes/${recipeId}/configs/${cfgId}`);
  expect(del.ok(), `AC-22②：删除 00006-02 应成功，实际 ${del.status()}`).toBeTruthy();
  expect(sqlOne(`SELECT status FROM material_recipe_config WHERE config_no='00006-02'`),
    'AC-22②：软删 ⇒ INACTIVE').toBe('INACTIVE');
  // 🚨 顺带守住真实数据：本条从头到尾不该碰 00006-01
  expect(sqlOne(`SELECT status FROM material_recipe_config WHERE config_no='00006-01'`),
    'AC-22：🚨 存量真实配置 00006-01 不得被本条用例波及').toBe('ACTIVE');

  // ③ 重新打开该报价单
  await page.goto(url);
  await page.waitForLoadState('networkidle');
  // 🚨 等到页面**真的有内容**再读：直接 innerText 会读到 ""，
  //    然后 `toMatch(/85/)` 在空串上失败，报成「含量丢了」的产品缺陷（2026-09-02 实跑踩到）。
  await expect(page.locator('.ant-steps-item-title').first(),
    'AC-22③：报价单编辑态应渲染出来').toBeVisible({ timeout: 30_000 });
  // 元素含量在 Step2「添加产品」的卡片里，往后推一步
  const nx = page.getByRole('button', { name: /下一步/ }).first();
  if (await nx.isVisible().catch(() => false) && await nx.isEnabled().catch(() => false)) {
    await nx.click();
    await page.waitForLoadState('networkidle');
  }
  let body = '';
  for (let i = 0; i < 12; i++) {
    body = await page.locator('body').innerText();
    if (body.trim().length > 50) break;
    await page.waitForTimeout(1500);
  }
  expect(body.trim().length, 'AC-22③：页面文本非空（空 = 没渲染完就读了，不是含量丢了）')
    .toBeGreaterThan(50);
  console.log('[AC-22③] 报价单页面片段 =', body.slice(0, 800));
  expect(await page.locator('text=加载中').count(),
    'AC-22③：🚫 不得出现「加载中…」').toBe(0);
  expect(body, 'AC-22③：元素含量仍显示 Ag 85（已落 element_bom_item，不随配置删除而变）').toMatch(/85/);
  expect(body, 'AC-22③：Ni 15').toMatch(/15/);
  await shot(page, 'AC22-reopen-after-config-deleted', { fullPage: true });
  evidence('AC22-sequence', `报价单=${url}\n00006-02 状态=INACTIVE（00006-01 仍 ACTIVE，未被波及）\n页面「加载中」计数=0`);
});
