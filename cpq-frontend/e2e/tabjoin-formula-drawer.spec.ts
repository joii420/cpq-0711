/**
 * task-0801 页签连表公式配置抽屉 —— 回归 E2E（AC-22）
 *
 * 覆盖：AC-1(两栏) / AC-3(chip 插光标处) / AC-7(深度着色) / AC-8(配对高亮)
 *       / AC-9(未闭合标红+保存禁用) / AC-12(连打不错位) / AC-16(左栏搜索) / AC-18(宽度自适应)
 *
 * ⚠️ 三个已踩过的坑，改动本文件前必读：
 *  1. antd 把**双字按钮**渲染成「保 存」「取 消」（中间带空格）→ `getByRole('button',{name:'保存'})`
 *     永远匹配不到，且表现为 15s **超时**而非报错，极易误判成功能 bug。用 `button.ant-btn-primary`。
 *  2. 组件页左侧**目录**的展开只认 `openDirs`（手动 toggle），**搜索不驱动目录展开**（只驱动分区）。
 *     必须先幂等展开目录才点得到卡片。
 *  3. 页面同时存在字段表与公式表，切 tab 只是 display:none、DOM 仍在 →
 *     `.ant-table-tbody tr` 会抓到隐藏的字段表行。要用「含『配置』按钮的行」定位公式行。
 */
import { test, expect, Page } from '@playwright/test';

const NORMAL_COMPONENT = 'COMP-0088'; // 罗克韦尔/产品（NORMAL）

test.use({ viewport: { width: 1920, height: 1080 } });

/** 登录 → 组件管理 → 搜索并展开目录 → 打开该组件的公式配置抽屉 */
async function openFormulaDrawer(page: Page, code: string) {
  const login = await page.request.post('/api/cpq/auth/login', {
    data: { username: 'admin', password: 'Admin@2026' },
  });
  expect(login.ok(), 'API 登录应成功').toBeTruthy();

  await page.goto('/components');
  const search = page.getByPlaceholder('🔍 搜索组件名 / 编码');
  await expect(search).toBeVisible({ timeout: 20_000 });
  await search.fill(code);
  await page.waitForTimeout(700);

  // 坑 2：幂等展开目录（用 class 判断，避免已展开时点一下反而折叠）
  const dirs = page.locator('.cmm-dir');
  for (let i = 0; i < (await dirs.count()); i++) {
    const d = dirs.nth(i);
    if (!(await d.evaluate((el) => el.classList.contains('open')))) {
      await d.locator('.cmm-dir-head').first().click();
      await page.waitForTimeout(250);
    }
  }

  const card = page.locator('.cmm-card').filter({ hasText: code }).first();
  await expect(card, `应能看到组件卡片 ${code}`).toBeVisible({ timeout: 10_000 });
  await card.click();
  await page.waitForTimeout(500);

  await page.getByRole('tab', { name: '公式' }).click();
  await page.getByRole('button', { name: '添加公式' }).click();
  await page.waitForTimeout(400);

  // 坑 3：用「含『配置』按钮的行」定位公式行，取最后一行 = 刚新增的那条
  await page
    .locator('.ant-table-tbody tr')
    .filter({ has: page.getByRole('button', { name: '配置' }) })
    .last()
    .getByRole('button', { name: '配置' })
    .click();

  const drawer = page.locator('.ant-drawer').filter({ hasText: '配置页签连表公式' }).last();
  await expect(drawer).toBeVisible({ timeout: 10_000 });
  return drawer;
}

async function clearEditor(page: Page, editor: ReturnType<Page['locator']>) {
  await editor.click();
  await page.keyboard.press('Control+A');
  await page.keyboard.press('Backspace');
  await page.waitForTimeout(150);
}

test('括号可视化与光标行为（AC-7 / AC-8 / AC-9 / AC-12）', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(String(e)));

  const drawer = await openFormulaDrawer(page, NORMAL_COMPONENT);
  const editor = drawer.locator('.tabjoin-formula-rich-input');

  // ── AC-7：深度着色 4 色循环 ──
  await editor.click();
  await page.keyboard.type('((((1))))');
  await page.waitForTimeout(250);
  const classes = await editor.locator('span.par').evaluateAll((els) =>
    els.map((e) => (e.getAttribute('class') ?? '').match(/\bp[0-3]\b/)?.[0] ?? '?'),
  );
  expect(classes, '8 个括号应按深度取 p0~p3 并循环').toEqual(
    ['p0', 'p1', 'p2', 'p3', 'p3', 'p2', 'p1', 'p0'],
  );

  // ── AC-8：光标停括号旁 → 该括号与配对的另一半同时高亮，恰好 2 个 ──
  await page.keyboard.press('Home');
  for (let i = 0; i < 3; i++) await page.keyboard.press('ArrowRight');
  await page.waitForTimeout(250);
  const hits = await editor.locator('.parHit').evaluateAll((els) =>
    els.map((e) => e.getAttribute('data-paren-idx')),
  );
  expect(hits.length, '配对高亮应恰为 2 个（自身 + 配对）').toBe(2);

  // ── AC-9：未闭合 '(' → 红波浪线 + 保存按钮禁用 ──
  await clearEditor(page, editor);
  await editor.click();
  await page.keyboard.type('SUM([投料.金额]');
  await page.waitForTimeout(250);
  expect(await editor.locator('span.parErr').count(), '未闭合括号应标红').toBeGreaterThan(0);
  // 坑 1：不能用 name:'保存'
  expect(await drawer.locator('button.ant-btn-primary').first().isDisabled(), '括号不平衡时保存应禁用')
    .toBe(true);

  // ── AC-12：在 '(' 后连打 10 个字符，按序落位不错位（R1 递归 restoreCaret 的核心验证）──
  await clearEditor(page, editor);
  await editor.click();
  await page.keyboard.type('SUM([投料.金额])');
  await page.waitForTimeout(250);
  await page.keyboard.press('Home');
  for (let i = 0; i < 4; i++) await page.keyboard.press('ArrowRight'); // 落到 "SUM(" 之后
  await page.keyboard.type('0123456789');
  await page.waitForTimeout(300);
  const finalText = (await editor.textContent()) ?? '';
  expect(finalText, '10 个字符应连续有序落在 "(" 之后').toContain('SUM(0123456789');
  expect(finalText.indexOf('0123456789'), '字符应落在引用块之前，不能跑到块后面')
    .toBeLessThan(finalText.indexOf('投料'));

  expect(errors, '不应有运行时错误').toEqual([]);
});

test('两栏布局 / 左栏搜索 / chip 插入光标处 / 宽度自适应（AC-1 / AC-3 / AC-16 / AC-18）', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(String(e)));

  const drawer = await openFormulaDrawer(page, NORMAL_COMPONENT);
  const grid = drawer.locator('.ant-drawer-body > div').first();

  // ── AC-1：两栏 + 各自独立滚动 ──
  const cols = await grid.evaluate((el) => getComputedStyle(el).gridTemplateColumns);
  const parts = cols.trim().split(/\s+/).map(parseFloat);
  expect(parts.length, '@1920 应为两栏').toBe(2);
  const ratio = parts[0] / (parts[0] + parts[1]);
  expect(ratio, '左栏应约占 42%').toBeGreaterThan(0.38);
  expect(ratio, '左栏应约占 42%').toBeLessThan(0.46);
  const overflows = await grid.locator('> div').evaluateAll((els) =>
    els.map((e) => getComputedStyle(e).overflow),
  );
  expect(overflows.every((o) => o === 'auto'), '两栏应各自独立滚动').toBe(true);

  // ── AC-18：宽屏宽度 1520 ──
  const wBox = await drawer.locator('.ant-drawer-content-wrapper').boundingBox();
  expect(Math.round(wBox?.width ?? 0), '@1920 抽屉宽应为 1520').toBe(1520);

  // ── AC-16：左栏搜索（无匹配占位 + 清空恢复）──
  const leftCol = grid.locator('> div').first();
  const cardSel = leftCol.locator('div[style*="border-radius: 8px"]');
  // 左栏页签卡来自 tab-defs 异步请求，抽屉刚打开时可能尚未返回 → 必须等，不能立即 count
  await expect
    .poll(async () => await cardSel.count(), { timeout: 15_000 })
    .toBeGreaterThan(0);
  const beforeN = await cardSel.count();

  const searchBox = drawer.getByPlaceholder('搜索页签或字段名');
  await searchBox.fill('不存在的字段XYZ');
  await page.waitForTimeout(400);
  await expect(drawer.getByText('无匹配的页签或字段'), '无匹配应出占位文案而非空白').toBeVisible();

  await searchBox.fill('');
  await page.waitForTimeout(400);
  expect(await cardSel.count(), '清空搜索后应全量恢复').toBe(beforeN);

  // ── AC-3：点 chip → token 插到公式框光标处（不是末尾）──
  const editor = drawer.locator('.tabjoin-formula-rich-input');
  await clearEditor(page, editor);
  await editor.click();
  await page.keyboard.type('1+');
  await leftCol.locator('.ant-tag:not([style*="not-allowed"])').first().click();
  await page.waitForTimeout(300);
  expect((await editor.textContent()) ?? '', 'token 应插在光标处，保留前缀 "1+"').toMatch(/^1\+.+/);

  // ── AC-18：窄屏降级 ──
  await page.setViewportSize({ width: 1000, height: 900 });
  await page.waitForTimeout(500);
  const narrowCols = await grid.evaluate((el) => getComputedStyle(el).gridTemplateColumns);
  expect(narrowCols.trim().split(/\s+/).length, '<1100px 应降为单栏').toBe(1);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth),
    '窄屏不应出现横向滚动').toBe(true);

  expect(errors, '不应有运行时错误').toEqual([]);
});
