/**
 * task-260909 · S1 只读片 · **C 组：核价树配置页 UI + 页签权限可见性**
 *
 * 覆盖 AC：**AC-1**（三项切换 / 默认基础核价 / 标题）、**AC-2**（详细核价空态）、
 *          **AC-17**（admin 4 页签 vs SALES_MANAGER 3 页签）
 *
 * 断言来源：`需求文档.md §③` AC 原文 + `原型图/01|02|03-*.html` 的定稿文案。
 * 允许的实现偏差只有一类：原型手写的分段控件换成 AntD `Segmented`
 * （`原型图/index.html` 的还原纪律已写明）⇒ 选择器按 `.ant-segmented-item` 写，
 * 并对「换成 Radio.Group」等变体给出显式的入口问题报错，不让它伪装成产品缺陷。
 *
 * 🚫 写入面：无。本组只读页面、不新增/不激活/不删除任何配置
 *    （AC-3 / AC-4 / AC-5 / AC-19 那些写操作属 **S-全局** 片）。
 */
import { test, expect, Page, Locator } from '@playwright/test';
import { loginAs } from './fixtures/auth';
import {
  ADMIN, salesManagerCred, cjkBtn, shot, writeEvidence, appendEvidence,
} from './fixtures/task260909tree';

test.describe.configure({ mode: 'serial' });

const TREE_TAB = '核价树配置';

/** 顶层页签栏（组件详情里也有 Tabs，必须只取最外层那一条，否则计数会混）。 */
function topTabNav(page: Page): Locator {
  return page.locator('.ant-tabs-nav').first();
}

async function topTabNames(page: Page): Promise<string[]> {
  const nav = topTabNav(page);
  await expect(nav, '组件管理页没有顶层页签栏 ⇒ 入口问题，判【未验证】').toBeVisible({ timeout: 20_000 });
  const names = await nav.locator('.ant-tabs-tab').allInnerTexts();
  return names.map((s) => s.trim()).filter((s) => s.length > 0);
}

async function gotoComponents(page: Page) {
  await page.goto('/components');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3000);
}

/** 打开「核价树配置」页签；返回其内容容器。 */
async function openTreeConfigTab(page: Page): Promise<Locator> {
  await gotoComponents(page);
  const tab = topTabNav(page).locator('.ant-tabs-tab').filter({ hasText: new RegExp(`^\\s*${TREE_TAB}\\s*$`) }).first();
  if (!(await tab.count())) {
    throw new Error(
      `找不到页签「${TREE_TAB}」。顶层页签=${JSON.stringify(await topTabNames(page))}\n` +
        '🚨 以 admin 登录时它必须存在（AC-17）；取不到则本条判【未验证】或按 AC-17 记失败。',
    );
  }
  await tab.click();
  await page.waitForTimeout(2500);
  const pane = page.locator('.ant-tabs-tabpane-active').first();
  await expect(pane, '「核价树配置」页签内容没渲染 ⇒ 入口问题').toBeVisible({ timeout: 15_000 });
  return pane;
}

/** 读三项切换控件的标签与选中项。 */
async function readSegmented(pane: Locator): Promise<{ labels: string[]; selected: string }> {
  const items = pane.locator('.ant-segmented-item');
  const n = await items.count();
  if (n === 0) {
    throw new Error(
      '「核价树配置」页里找不到 `.ant-segmented-item` 数据集切换控件。\n' +
        '🚨 两种成因请分开判：①实现换了别的控件（Radio.Group/Tabs）⇒ **入口/选择器**问题，判【未验证】；' +
        '②控件压根没做 ⇒ AC-1 失败。🚫 不许猜。',
    );
  }
  const labels = (await items.allInnerTexts()).map((s) => s.trim());
  const sel = pane.locator('.ant-segmented-item-selected').first();
  const selected = (await sel.count()) ? (await sel.innerText()).trim() : '';
  return { labels, selected };
}

// ══════════════════════════════════════════════════════════════════════════
test('T-C1 / AC-1：核价树配置页三项切换、默认选中「基础核价」、标题「基础核价树配置」', async ({ page }) => {
  await loginAs(page, ADMIN.username, ADMIN.password);
  const pane = await openTreeConfigTab(page);
  await shot(page, 'AC-01-tree-config-default');

  const { labels, selected } = await readSegmented(pane);
  console.log('[AC-1] 切换项 =', JSON.stringify(labels), ' 选中 =', selected);

  // 顺序固定为 报价 / 基础核价 / 详细核价（原型 01 的还原要点 ①）
  expect(labels, 'AC-1：切换控件应恰好三项，且顺序为 报价 / 基础核价 / 详细核价')
    .toEqual(['报价', '基础核价', '详细核价']);
  expect(selected, 'AC-1：默认应选中「基础核价」').toBe('基础核价');

  // 页面标题「基础核价树配置」
  const title = pane.locator('text=基础核价树配置').first();
  await expect(title, 'AC-1：页面标题应显示「基础核价树配置」').toBeVisible({ timeout: 10_000 });

  writeEvidence('AC-01-segmented.txt',
    `切换项=${JSON.stringify(labels)}\n默认选中=${selected}\n标题「基础核价树配置」可见=true\n`);
});

// ══════════════════════════════════════════════════════════════════════════
test('T-C2 / AC-2：切到「详细核价」→ 0 行 + 空态文案「暂无详细核价树配置」+ 新增仍可点', async ({ page }) => {
  await loginAs(page, ADMIN.username, ADMIN.password);
  const pane = await openTreeConfigTab(page);

  const detail = pane.locator('.ant-segmented-item').filter({ hasText: /^\s*详细核价\s*$/ }).first();
  await expect(detail, 'AC-2：找不到「详细核价」切换项 ⇒ 入口问题').toBeVisible({ timeout: 10_000 });
  await detail.click();
  await page.waitForTimeout(2500);
  await shot(page, 'AC-02-cost-detail-empty');

  // 标题跟随
  await expect(pane.locator('text=详细核价树配置').first(),
    'AC-2：标题应跟随为「详细核价树配置」').toBeVisible({ timeout: 10_000 });

  // ① 表格 0 行
  //    ⚠️ 这里 0 行是 AC 明确要求的**阳性结果**，不是空跑：
  //       所以必须同时证明「表确实渲染了」——空态元素可见 —— 否则 0 行也可能是页面没加载。
  const empty = pane.locator('text=暂无详细核价树配置').first();
  await expect(empty, 'AC-2：应显示空态文案「暂无详细核价树配置」').toBeVisible({ timeout: 15_000 });

  const dataRows = pane.locator('.ant-table-tbody tr.ant-table-row');
  const n = await dataRows.count();
  console.log('[AC-2] 详细核价 数据行 =', n);
  expect(n, `AC-2：详细核价下应 0 条配置，实得 ${n} 条`).toBe(0);

  // ② 「新增」按钮仍可点（不禁用、不隐藏）—— 原型 02 的硬要求
  //    ⚠️ antd 会把两个汉字渲染成「新 增」，选择器必须容忍中间的空白
  const addBtn = pane.locator('button').filter({ hasText: cjkBtn('新增') }).first();
  await expect(addBtn, 'AC-2：「新增」按钮应可见（🚫 不许隐藏）').toBeVisible({ timeout: 10_000 });
  expect(await addBtn.isEnabled(), 'AC-2：「新增」按钮在空态下必须仍可点（不禁用）').toBe(true);

  // ③ 页面不报错
  // 🚨 2026-09-09 实跑修正：原写成 `.ant-alert-error, text=渲染失败` ——
  //    Playwright **不允许把 `text=` 引擎混进 CSS 选择器列表**，直接抛
  //    `Unexpected token "=" while parsing css selector`。
  //    ⚠️ 它以「用例失败」的形式出现，长得和产品缺陷一模一样，实为量具语法错。
  const errAlert = await pane.locator('.ant-alert-error').count();
  const errText = await pane.getByText('渲染失败', { exact: false }).count();
  console.log(`[AC-2] .ant-alert-error=${errAlert}  「渲染失败」文本=${errText}`);
  expect(errAlert + errText, 'AC-2：详细核价空态页面不应报错').toBe(0);

  writeEvidence('AC-02-empty-state.txt',
    `标题=详细核价树配置\n空态文案「暂无详细核价树配置」可见=true\n数据行=${n}\n新增按钮 enabled=true\n` +
    `错误块: .ant-alert-error=${errAlert} 「渲染失败」文本=${errText}\n`);
});

// ══════════════════════════════════════════════════════════════════════════
test('T-C3 / AC-17a：admin（SYSTEM_ADMIN）打开 /components 应见 4 个页签', async ({ page }) => {
  await loginAs(page, ADMIN.username, ADMIN.password);
  await gotoComponents(page);
  const names = await topTabNames(page);
  console.log('[AC-17a] admin 顶层页签 =', JSON.stringify(names));
  await shot(page, 'AC-17a-admin-4tabs');
  // 🚨 必须 append：本文件由 T-C3(admin) 与 T-C4(smgr) **两条**用例共同写入，
  //    用 writeEvidence 会让后跑的那条把前一条的证据覆盖掉（首轮实测已发生）。
  appendEvidence('AC-17-tabs.txt', `admin 顶层页签=${JSON.stringify(names)}\n`);

  expect(names, 'AC-17：admin 应看到 4 个页签：组件 / 数据源 / 全局变量 / 核价树配置')
    .toEqual(['组件', '数据源', '全局变量', TREE_TAB]);
});

test('T-C4 / AC-17b：test1（SALES_MANAGER）打开 /components 应见 3 个页签且不含「核价树配置」',
  async ({ page }) => {
    // 🚨 缺口令时**硬失败**（不 skip）：skip 掉的权限断言会以「全部通过」混过去。
    //    报告里按【未验证 · 测试环境缺陷】记，🚫 不得记成产品缺陷。
    const sm = salesManagerCred();
    await loginAs(page, sm.username, sm.password);
    await gotoComponents(page);
    const names = await topTabNames(page);
    console.log(`[AC-17b] ${sm.username} 顶层页签 =`, JSON.stringify(names));
    await shot(page, 'AC-17b-salesmanager-3tabs');
    appendEvidence('AC-17-tabs.txt', `${sm.username} 顶层页签=${JSON.stringify(names)}\n`);

    expect(names, `AC-17：${sm.username}（SALES_MANAGER）应只看到 3 个页签`)
      .toEqual(['组件', '数据源', '全局变量']);
    expect(names.includes(TREE_TAB), `AC-17：${sm.username} 不应看到「${TREE_TAB}」页签`).toBe(false);
  });
