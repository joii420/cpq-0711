/**
 * task-260909「取数配置器字段类型选择」· **主线亲验**（CLAUDE.md §4.5 步骤 4）
 * 🚫 不复用 test-engineer 的 helpers —— 亲验不采信子代理的量具，独立走一遍 UI。
 * 环境：5175（VITE_API_TARGET=8099，worktree 代码）+ cpq_db_0724 真实数据。
 */
import { test, expect } from '@playwright/test';
const SHOT = '/tmp/claude-1000/-home-joii-project-cpq/dd3dc6c5-18d8-4be3-b01e-677976918873/scratchpad/verify';

async function login(page: any) {
  await page.goto('/login');
  await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
  await page.locator('input[placeholder="密码"]').fill('Admin@2026');
  await page.locator('button[type="submit"]').click();
  await page.waitForURL(/\/(dashboard|customers|quotations|system|products)/, { timeout: 20000 });
}

/**
 * 走用户视角完整路径打开某组件的「取数配置」。
 * 🔑 用**编码**搜索（搜索框 placeholder = "🔍 搜索组件名 / 编码"）——
 *    全库有 4 个同名「BOM」（COMP-2266/2294/2299/2345），按名字搜会点错组件。
 */
async function openBuilder(page: any, code: string, displayName: string, tag: string) {
  await page.goto('/components');
  await page.waitForLoadState('networkidle');
  await page.locator('input[placeholder*="搜索组件"]').fill(code);
  await page.waitForTimeout(1500);
  await page.screenshot({ path: `${SHOT}/10-search-${tag}.png`, fullPage: true });

  // 🔑 定位纪律（实测得出，勿简化）：
  //   ① 搜索后目录默认**折叠**，命中的组件在 DOM 里但不可见 —— 必须先展开它所在的目录。
  //   ② 目录标题实际是「📁 核价组件」（带 emoji），`getByText('核价组件',{exact:true})` **匹配不到**。
  //   ③ 全库有 4 个同名「BOM」，且目录名也有重名（「取值配置器测试」/「…测试2」）——
  //      唯一可靠锚点是卡片文本里的**编码**。所以这里**按 code 反查它的目录祖先**，不传目录名。
  const card = page.locator('.cmm-card').filter({ hasText: code });
  const dir = page.locator('.cmm-dir').filter({ has: card });
  await expect(dir, `含 ${code} 的目录必须唯一命中`).toHaveCount(1);
  //   ④ 展开动作必须**幂等**：目录可能本来就是展开的（如「取值配置器测试」），
  //      无条件点行首会把它**折叠**掉 —— 实测这样连挂 3 次重试。判据用「卡片可不可见」。
  if (!(await card.isVisible())) {
    await dir.click({ position: { x: 12, y: 12 } });
    await page.waitForTimeout(1500);
    console.log(`（目录原为折叠态，已展开）`);
  } else {
    console.log(`（目录原已展开，未动它）`);
  }
  await page.screenshot({ path: `${SHOT}/11-expanded-${tag}.png`, fullPage: true });

  await expect(card, `卡片 ${code} 必须可见（0 命中会让后续断言空跑）`).toHaveCount(1);
  await card.locator('.cmm-c-name').first().click();
  console.log(`✅ 已点开 ${code}（按编码锚定）`);
  await page.waitForTimeout(2500);

  for (const t of ['取数配置', 'SQL 视图', '取数']) {
    const tab = page.locator('.ant-tabs-tab', { hasText: t });
    if (await tab.count()) { await tab.first().click(); console.log('✅ 进入页签:', t); break; }
  }
  await page.waitForTimeout(3000);
  await page.screenshot({ path: `${SHOT}/12-builder-${tag}.png`, fullPage: true });
}

/** 读出逐列的「字段类型」显示值。antd v6：🚫 不用 .ant-select-selection-item（v5 类名，匹配 0 个且不报错）。 */
async function readFieldTypes(page: any) {
  const sels = page.locator('[data-role="field-type-select"]');
  const n = await sels.count();
  expect(n, '选择器必须非空，否则后面的断言全是空跑').toBeGreaterThan(0);
  const got: Record<string, string> = {};
  for (let i = 0; i < n; i++) {
    const el = sels.nth(i);
    const name = await el.getAttribute('data-field-name');
    got[name || `#${i}`] = (await el.innerText()).trim().replace(/\s+/g, '');
  }
  return got;
}

test('AC-15 亲验：存量核价组件 COMP-2299 打开后逐列显示库内真值', async ({ page }) => {
  test.setTimeout(180000);
  await login(page);
  await openBuilder(page, 'COMP-2299', 'BOM', 'comp2299');

  const got = await readFieldTypes(page);
  console.log('列数 =', Object.keys(got).length);
  console.log('UI 逐列显示 =', JSON.stringify(got));
  const dist: Record<string, number> = {};
  Object.values(got).forEach(v => { dist[v] = (dist[v] || 0) + 1; });
  console.log('UI 显示分布 =', JSON.stringify(dist));

  // AC-15 核心断言：🚫 不是统一的兜底值
  expect(Object.keys(dist).length, 'AC-15：必须不是统一兜底值（应同时出现文本输入与数字输入）').toBeGreaterThan(1);
  expect(dist['基础数据'] ?? 0, 'AC-15：COST_BASIC 存量组件不许显示成基础数据').toBe(0);
});


test('AC-1 亲验：选择器恰好 3 个选项，且不出现 FORMULA/DATA_SOURCE/FIXED_VALUE', async ({ page }) => {
  test.setTimeout(180000);
  await login(page);
  // 在 COMP-2299 上验（🚫 全程只读、不保存 —— 它的 12/12-null 是 AC-15 的一次性额度）
  await openBuilder(page, 'COMP-2299', 'BOM', 'ac1');

  const sel = page.locator('[data-role="field-type-select"]').first();
  await expect(sel, 'AC-1：选择器必须存在（不存在时"选项恰好3项"会读到空数组而恒真）').toHaveCount(1);
  await sel.click();
  await page.waitForTimeout(1000);
  await page.screenshot({ path: SHOT + '/30-ac1-选择器展开态.png', fullPage: true });

  // antd v6：下拉项只有 .ant-select-item-option
  const opts = page.locator('.ant-select-item-option:visible');
  const texts = (await opts.allInnerTexts()).map(t => t.trim());
  console.log('AC-1 展开后选项 =', JSON.stringify(texts), ' 计数 =', texts.length);
  expect(texts.length, 'AC-1：恰好 3 个选项').toBe(3);
  for (const forbidden of ['FORMULA', '公式', 'DATA_SOURCE', '数据源', 'FIXED_VALUE', '固定值']) {
    expect(texts.join('|'), `AC-1：🚫 不许出现 ${forbidden}`).not.toContain(forbidden);
  }
});

test('AC-2 亲验：整列批量入口，应用后逐列同步变更', async ({ page }) => {
  test.setTimeout(180000);
  await login(page);
  // 🔑 在主线自建的一次性组件上做（会改 UI 状态），🚫 不碰 COMP-2299
  // 🔑 用前端造的一次性组件（在「取值配置器测试」目录里，树中可达）；
  //    主线自建的 COMP-2501 无 directory_id，树里不显示 ⇒ 不能用。
  //    🚫 全程只点不保存 ⇒ 对 COMP-2422 零写入（收尾用指纹复核）。
  await openBuilder(page, 'COMP-2422', 'T260909FT-BASIC', 'ac2');

  const before = await readFieldTypes(page);
  console.log('AC-2 批量前 =', JSON.stringify(before));
  expect(Object.keys(before).length, 'AC-2：列必须非空').toBeGreaterThan(0);

  const bulk = page.locator('[data-role="bulk-field-type"]');
  await expect(bulk, 'AC-2：整列批量入口必须存在').toHaveCount(1);
  await bulk.click();
  await page.waitForTimeout(800);
  await page.locator('.ant-select-item-option:visible', { hasText: '文本输入' }).first().click();
  await page.waitForTimeout(500);
  await page.locator('[data-role="apply-field-type-all"]').click();
  await page.waitForTimeout(1200);
  await page.screenshot({ path: SHOT + '/31-ac2-批量应用后.png', fullPage: true });

  const after = await readFieldTypes(page);
  console.log('AC-2 批量后 =', JSON.stringify(after));
  const vals = [...new Set(Object.values(after))];
  expect(vals, 'AC-2：应用后所有列应同为「文本输入」').toEqual(['文本输入']);
  console.log('✅ AC-2：', Object.keys(after).length, '列全部同步为「文本输入」');
});

/** 保存配置器。⚠️ antd 两字按钮会渲染成「保 存」（中间有空格），所以用正则而非等值匹配。 */
async function saveBuilder(page: any) {
  const actions = page.locator('[data-role="builder-actions"]');
  const btn = actions.locator('button').filter({ hasText: /保\s*存/ }).first();
  await expect(btn, '保存按钮必须存在').toHaveCount(1);
  await btn.click();
  await page.waitForTimeout(3000);
  // 若弹出影响面二次确认（AC-31），点确认
  const ok = page.locator('.ant-modal button').filter({ hasText: /确\s*定|确\s*认/ }).first();
  if (await ok.count() && await ok.isVisible()) { await ok.click(); await page.waitForTimeout(2500); }
}

test('AC-11 亲验：改类型→保存→刷新→回填，往返两轮', async ({ page }) => {
  test.setTimeout(300000);
  await login(page);
  const CODE = 'COMP-2501';           // 主线自建的一次性组件（🚫 不碰任何存量/他人对象）
  const NAME = 'T260909MAIN-B2-on8099';
  const COL = '生产料号';

  // ── 第 1 轮：当前应回填为 BASIC_DATA（B-2 实验保存的结果）
  await openBuilder(page, CODE, NAME, 'ac11-r1');
  let got = await readFieldTypes(page);
  console.log('AC-11 ①打开 =', JSON.stringify(got));
  expect(got[COL], 'AC-11①：应回填 B-2 实验落库的 BASIC_DATA').toBe('基础数据');

  // ── 改成「文本输入」→ 保存
  const sel = page.locator(`[data-role="field-type-select"][data-field-name="${COL}"]`);
  await expect(sel, `列「${COL}」的选择器必须存在`).toHaveCount(1);
  await sel.click(); await page.waitForTimeout(800);
  await page.locator('.ant-select-item-option:visible', { hasText: '文本输入' }).first().click();
  await page.waitForTimeout(500);
  console.log('AC-11 ②改后（未保存）=', JSON.stringify(await readFieldTypes(page)));
  await saveBuilder(page);
  await page.screenshot({ path: SHOT + '/32-ac11-保存后.png', fullPage: true });

  // ── 刷新页面重新打开 → 应回填 INPUT_TEXT
  await openBuilder(page, CODE, NAME, 'ac11-r2');
  got = await readFieldTypes(page);
  console.log('AC-11 ③刷新后回填 =', JSON.stringify(got));
  expect(got[COL], 'AC-11③：刷新后应回填「文本输入」').toBe('文本输入');

  // ── 改回「基础数据」→ 保存 → 再刷新 → 应回填 BASIC_DATA
  const sel2 = page.locator(`[data-role="field-type-select"][data-field-name="${COL}"]`);
  await sel2.click(); await page.waitForTimeout(800);
  await page.locator('.ant-select-item-option:visible', { hasText: '基础数据' }).first().click();
  await page.waitForTimeout(500);
  await saveBuilder(page);

  await openBuilder(page, CODE, NAME, 'ac11-r3');
  got = await readFieldTypes(page);
  console.log('AC-11 ④改回后回填 =', JSON.stringify(got));
  expect(got[COL], 'AC-11④：应回填回「基础数据」').toBe('基础数据');
  console.log('✅ AC-11 往返两轮闭合');
});
