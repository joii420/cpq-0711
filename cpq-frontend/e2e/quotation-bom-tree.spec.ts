/**
 * E2E: task-0721 报价侧树状结构与页签类型属性 —— 报价单卡片按 BOM 树渲染 + 树上编辑（F1/F3/F4/F5）。
 *
 * 夹具（DRAFT，持久化于共享库 cpq_db，2026-07-21 后端造好）：
 *   - 报价单 QT-20260721-2067, id = 1f8c146d-20cd-438b-9b4a-53a98f3cbdb9
 *   - line item id = 303c5789-ed9d-483e-96b0-d6b1d42fa986，产品料号 3120018220
 *   - 树组件 63b44325-0cb9-449d-ae6a-21b2e763278d（TASK0721-TREE-BOM，页签"BOM树"）
 *   - 材质页签 627c8529-4866-497b-be2d-7fc327494fe4（TASK0721-MAT-ELEMENT，页签"材质元素"）
 *   - 闭包 17 occurrence；DAG：3110520789 挂 2120011658/2120011659 两父（各 1 次，共 2 次），
 *     其材质子孙 2101110225 / 2111410069 各随之复制两份（各 2 次）
 *
 * ⚠️ 环境说明：共享后端 8081 跑的是 master（无树端点），本 spec 必须指向从 worktree 分支起的
 * 临时后端（PW_BACKEND_URL）+ 临时前端（PW_BASE_URL，proxy /api → 临时后端）。
 * 运行示例（临时后端 8099，临时前端 5299）：
 *   PW_BASE_URL=http://localhost:5299 PW_BACKEND_URL=http://localhost:8099 \
 *     npx playwright test --config=e2e/playwright.config.ts e2e/quotation-bom-tree.spec.ts --reporter=list
 *
 * ⚠️ 剪枝级联仅验证 delete-preview（只读，不改夹具）；execute 会写墓碑（deleted_tree_nodes/
 * deleted_row_keys）永久改动共享夹具，未在本 spec 里跑，交由后端单测覆盖（B7/B13）。
 *
 * ⚠️ 已知发现（非本 spec 的 bug，如实记录并按现状断言）：
 *   现有 BOM 树系统固定列渲染只出「料号」「版本」两列（父料号 2026-07-03 起隐藏，
 *   见 QuotationStep2.tsx 注释"父料号列已隐藏(仅用于建层级,不成列)"），且「版本」列是
 *   disabled <select> 而非纯文本 —— 与需求文档/任务清单描述的"三固定列(料号/父料号/版本)，
 *   只读非 select"不一致。这是 F1 复用核价既有渲染约定（2026-07-03 建立）的既定选择，
 *   本 spec 按**当前真实行为**断言（2 列 + select），不擅自改渲染代码；差异已在交付报告中
 *   向协调方标注，等待裁决（补父料号列 + 版本改纯文本 / 或维持现状更新需求文档）。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const __filename = fileURLToPath(import.meta.url);
const __dirnameLocal = path.dirname(__filename);
const SHOT_DIR = path.join(__dirnameLocal, 'screenshots');
fs.mkdirSync(SHOT_DIR, { recursive: true });

const QUOTATION_ID = '1f8c146d-20cd-438b-9b4a-53a98f3cbdb9';   // QT-20260721-2067
const ROOT_PART = '3120018220';
const DAG_CHILD = '3110520789';       // 挂 2120011658/2120011659 两父，各 1 次 → 树上出现 2 次
const DAG_GRANDCHILD_1 = '2101110225'; // 材质，随 DAG_CHILD 两父各复制一份 → 出现 2 次
const DAG_GRANDCHILD_2 = '2111410069'; // 同上
const TREE_TAB_NAME = 'BOM树';
const MAT_TAB_NAME = '材质元素';
const EXPECTED_ROW_COUNT = 17;
const BACKEND_URL = process.env.PW_BACKEND_URL || 'http://localhost:8081';

let backendUp = false;
let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `qbt-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
}

/** 进报价单编辑页的产品卡片视图（默认 mainTab='quote'），切到 BOM 树页签。返回是否成功。 */
async function enterQuoteTreeTab(page: Page): Promise<boolean> {
  await page.goto(`/quotations/${QUOTATION_ID}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  const nextBtn = page.locator('button', { hasText: /下一步|继续/ }).first();
  if (await nextBtn.count() > 0 && await nextBtn.isEnabled().catch(() => false)) {
    await nextBtn.click().catch(() => {});
    await page.waitForTimeout(1200);
  }
  const quoteSeg = page.locator('.ant-segmented-item', { hasText: '报价单' }).first();
  if (await quoteSeg.count() > 0) { await quoteSeg.click().catch(() => {}); await page.waitForTimeout(800); }
  const cardSeg = page.locator('.ant-segmented-item', { hasText: '产品卡片' }).first();
  if (await cardSeg.count() > 0) { await cardSeg.click().catch(() => {}); await page.waitForTimeout(800); }
  const treeTab = page.locator('.qt-tab-btn', { hasText: TREE_TAB_NAME }).first();
  if (await treeTab.count() === 0) return false;
  await treeTab.click().catch(() => {});
  await page.waitForTimeout(1200);
  return true;
}

async function tableHeaders(page: Page): Promise<string[]> {
  const ths = page.locator('.qt-cost-table thead th');
  const n = await ths.count();
  const out: string[] = [];
  for (let i = 0; i < n; i++) out.push((await ths.nth(i).innerText().catch(() => '')).trim());
  return out;
}

async function firstColTexts(page: Page): Promise<string[]> {
  const cells = page.locator('.qt-cost-table tbody tr td:first-child');
  const n = await cells.count();
  const out: string[] = [];
  for (let i = 0; i < n; i++) out.push((await cells.nth(i).innerText().catch(() => '')).trim());
  return out;
}

test.beforeAll(async () => {
  backendUp = await isBackendUp();
});

test('报价侧 BOM 树渲染：固定列 + 17 行 + 缩进 + 折叠 + DAG + 加载中=0 + 刷新稳定（AC-1/AC-2/AC-11/AP-51）', async ({ page }) => {
  test.skip(!backendUp, '临时后端未启动（本 spec 需要 worktree 分支代码，8081 主仓 master 无树端点）');

  await loginAsAdmin(page);
  expect(page.url()).not.toContain('/login');

  const entered = await enterQuoteTreeTab(page);
  expect(entered, '应能进入报价单 BOM 树页签').toBe(true);
  await shot(page, 'tree-tab');

  // 固定列——按当前真实实现断言（2 列：料号 + 版本；父料号已隐藏，见文件头注释）
  const headers = await tableHeaders(page);
  console.log('[QBT] BOM树表头:', JSON.stringify(headers));
  expect(headers[0], '第1列=料号').toBe('料号');
  expect(headers[1], '第2列=版本').toBe('版本');
  // 版本列现状是 disabled <select>（非纯文本）——与任务清单期望不同，如实记录，不在此断言"非 select"。
  const verSelect = page.locator('.qt-cost-table tbody select[disabled]');
  expect(await verSelect.count(), '版本列现状为 disabled select 占位').toBeGreaterThanOrEqual(1);

  // 行数 = 17（spine occurrence）
  const rows = page.locator('.qt-cost-table tbody tr');
  const rc = await rows.count();
  console.log('[QBT] BOM树行数 =', rc);
  expect(rc, `BOM 树应展开 ${EXPECTED_ROW_COUNT} 行 spine`).toBe(EXPECTED_ROW_COUNT);

  // 缩进：根节点（lvl=1）与 DAG 材质孙节点（lvl=4）应有不同缩进宽度
  const rootRowFirstCell = page.locator('.qt-cost-table tbody tr', { hasText: ROOT_PART }).first().locator('td').first();
  const deepRowFirstCell = page.locator('.qt-cost-table tbody tr', { hasText: DAG_GRANDCHILD_1 }).first().locator('td').first();
  const rootIndentSpan = rootRowFirstCell.locator('span').first().locator('span').first();
  const deepIndentSpan = deepRowFirstCell.locator('span').first().locator('span').first();
  const rootWidth = await rootIndentSpan.evaluate((el) => (el as HTMLElement).style.width).catch(() => '');
  const deepWidth = await deepIndentSpan.evaluate((el) => (el as HTMLElement).style.width).catch(() => '');
  console.log(`[QBT] 缩进宽度 root=${rootWidth} deep(lvl4)=${deepWidth}`);
  expect(parseFloat(deepWidth || '0'), '深层节点缩进应大于根节点（层级越深越靠右）')
    .toBeGreaterThan(parseFloat(rootWidth || '0'));

  // 折叠箭头存在（根/中间节点 hasChildren）
  const carets = page.locator('.qt-cost-table tbody button', { hasText: /[▼▶]/ });
  console.log('[QBT] 折叠箭头数 =', await carets.count());
  expect(await carets.count(), '应有展开/折叠箭头').toBeGreaterThanOrEqual(1);

  // DAG：3110520789 出现 2 次；其材质子孙各出现 2 次
  const firstCols = await firstColTexts(page);
  const dagCount = firstCols.filter((t) => t.includes(DAG_CHILD)).length;
  const g1Count = firstCols.filter((t) => t.includes(DAG_GRANDCHILD_1)).length;
  const g2Count = firstCols.filter((t) => t.includes(DAG_GRANDCHILD_2)).length;
  console.log(`[QBT] DAG ${DAG_CHILD} 出现 ${dagCount} 次；材质孙 ${DAG_GRANDCHILD_1}=${g1Count} 次 ${DAG_GRANDCHILD_2}=${g2Count} 次`);
  expect(dagCount, `${DAG_CHILD} 应作为 DAG 重复子件出现 2 次`).toBe(2);
  expect(g1Count, `材质孙 ${DAG_GRANDCHILD_1} 应随两条 DAG 路径各复制一份，出现 2 次`).toBe(2);
  expect(g2Count, `材质孙 ${DAG_GRANDCHILD_2} 应随两条 DAG 路径各复制一份，出现 2 次`).toBe(2);

  // 加载中 = 0（AC-11）—— 逐个页签检查（BOM树 + 材质元素）
  const tabs = page.locator('.qt-tab-btn');
  const tabN = await tabs.count();
  let totalLoading = 0;
  for (let i = 0; i < tabN; i++) {
    const name = (await tabs.nth(i).innerText().catch(() => '')).trim();
    await tabs.nth(i).click().catch(() => {});
    await page.waitForTimeout(700);
    const lc = await page.locator('text=加载中').count();
    console.log(`[QBT] tab '${name}' 加载中 = ${lc}`);
    totalLoading += lc;
    expect(lc, `tab '${name}' 加载中应为 0`).toBe(0);
  }
  console.log('[QBT] 加载中 count(汇总) =', totalLoading);
  expect(totalLoading, '加载中 count 汇总应为 0').toBe(0);

  await shot(page, 'final-render');

  // 刷新 3 次，行数稳定 = 17（AP-51）
  for (let i = 1; i <= 3; i++) {
    await page.reload();
    await page.waitForLoadState('networkidle');
    await enterQuoteTreeTab(page);
    const rcN = await page.locator('.qt-cost-table tbody tr').count();
    console.log(`[QBT] 第 ${i} 次刷新后行数 =`, rcN);
    expect(rcN, `第 ${i} 次刷新后行数应稳定为 ${EXPECTED_ROW_COUNT}（AP-51 不累加）`).toBe(EXPECTED_ROW_COUNT);
  }
});

test('加叶子：零件节点「+」可用 + 候选来自本单各页签；材质节点「+」置灰（AC-3/AC-5）', async ({ page }) => {
  test.skip(!backendUp, '临时后端未启动');

  await loginAsAdmin(page);
  const entered = await enterQuoteTreeTab(page);
  expect(entered).toBe(true);

  // 材质节点（DAG_GRANDCHILD_1，__nodeType='材质'）：+ 应置灰（<span title="...不可再添加下级">）
  const matRow = page.locator('.qt-cost-table tbody tr', { hasText: DAG_GRANDCHILD_1 }).first();
  const matDisabledPlus = matRow.locator('span[title="材质节点不可再添加下级"]');
  console.log('[QBT] 材质节点置灰「+」数 =', await matDisabledPlus.count());
  expect(await matDisabledPlus.count(), '材质节点「+」应置灰').toBeGreaterThanOrEqual(1);
  // 置灰态用 <span> 承载（非 disabled <button>），hover 原因通过 title 始终可读（列表操作规范）
  const matEnabledPlus = matRow.locator('button[title="在此节点下新增叶子料号"]');
  expect(await matEnabledPlus.count(), '材质节点不应出现可点击的「+」按钮').toBe(0);

  // 零件节点（DAG_CHILD，__nodeType='零件'）：+ 应可点击，打开候选料号抽屉
  const partRow = page.locator('.qt-cost-table tbody tr', { hasText: DAG_CHILD }).first();
  const partEnabledPlus = partRow.locator('button[title="在此节点下新增叶子料号"]');
  expect(await partEnabledPlus.count(), '零件节点「+」应可点击').toBeGreaterThanOrEqual(1);
  await partEnabledPlus.first().click();
  await page.waitForTimeout(800);

  const drawerTitle = page.locator('.ant-drawer-title', { hasText: '新增叶子料号' });
  expect(await drawerTitle.count(), '应弹出「新增叶子料号」抽屉').toBeGreaterThanOrEqual(1);
  await shot(page, 'add-leaf-drawer');

  // 候选料号来自本单各页签已渲染的行（本地采集，非远程查询）——树页签自身料号 + 材质页签料号均应可见
  // 用 ARIA role=dialog + 可访问名精确定位这一个抽屉的容器，避免 .ant-drawer-content 在页面上
  // 命中其它已挂载但当前关闭的 Drawer（同一 DOM 里 class 选择器不唯一，第一次跑时踩过这个坑）。
  const drawerBody = page.getByRole('dialog', { name: '新增叶子料号' });
  const bodyText = (await drawerBody.first().innerText().catch(() => ''));
  console.log('[QBT] 候选料号抽屉是否含材质料号', DAG_GRANDCHILD_1, ':', bodyText.includes(DAG_GRANDCHILD_1));
  console.log('[QBT] 候选料号抽屉是否含根料号', ROOT_PART, ':', bodyText.includes(ROOT_PART));
  expect(bodyText.includes(DAG_GRANDCHILD_1), '候选列表应包含材质页签的料号').toBe(true);
  expect(bodyText.includes(ROOT_PART), '候选列表应包含 BOM 树页签的根料号').toBe(true);

  // 关闭抽屉，不提交新增（避免改动共享夹具）
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(300);
});

test('剪枝级联预览（DAG 不误删，AC-7c）：3110520789 一支 → 另一支保留 + 材质子孙 retainedParts', async ({ page }) => {
  test.skip(!backendUp, '临时后端未启动');
  // 只读验证：调用 delete-preview 但不点确认（不 execute），避免写墓碑永久改动共享夹具。

  await loginAsAdmin(page);
  const entered = await enterQuoteTreeTab(page);
  expect(entered).toBe(true);

  // 3110520789 出现 2 次（2 个独立 nodeId），点第一个occurrence 的剪枝入口
  const dagRow = page.locator('.qt-cost-table tbody tr', { hasText: DAG_CHILD }).first();
  const pruneBtn = dagRow.locator(
    'button[title="剪掉该节点及其子树（将弹窗确认级联影响，跨页签联动删除）"]',
  );
  expect(await pruneBtn.count(), `${DAG_CHILD} 应有剪枝入口`).toBeGreaterThanOrEqual(1);
  await pruneBtn.first().click();
  await page.waitForTimeout(1500);   // 等 delete-preview 请求返回

  const drawerTitle = page.locator('.ant-drawer-title', { hasText: '确认剪枝' });
  expect(await drawerTitle.count(), '应弹出剪枝确认抽屉').toBeGreaterThanOrEqual(1);
  await shot(page, 'prune-preview');

  // 同上：用 role=dialog + 可访问名精确定位这一个抽屉，避免命中其它已挂载但关闭的 Drawer。
  const drawerBody = page.getByRole('dialog', { name: /确认剪枝/ }).first();
  const bodyText = await drawerBody.innerText().catch(() => '');
  console.log('[QBT] 剪枝预览抽屉内容片段:', bodyText.slice(0, 600));

  // ① 将从树上移除的节点：应含被剪的 3110520789 本身（这一支）
  expect(bodyText.includes('将从树上移除的节点'), '应有第①块标题').toBe(true);
  expect(bodyText.includes(DAG_CHILD), '第①块应含被剪的节点料号').toBe(true);

  // ③ 因仍有其他引用而【不删】的料号：材质子孙在另一支仍有 occurrence，应出现在保留清单
  expect(bodyText.includes('因仍有其他引用而【不删】的料号'), '应有第③块标题').toBe(true);
  const hasRetainedG1 = bodyText.includes(DAG_GRANDCHILD_1);
  const hasRetainedG2 = bodyText.includes(DAG_GRANDCHILD_2);
  console.log(`[QBT] retainedParts 是否含 ${DAG_GRANDCHILD_1}=${hasRetainedG1} ${DAG_GRANDCHILD_2}=${hasRetainedG2}`);
  expect(hasRetainedG1 || hasRetainedG2, 'DAG 场景下材质子孙应至少一个出现在 retainedParts（另一支仍引用，不应被删）')
    .toBe(true);

  // 不点「确认删除」，直接关闭抽屉（只读验证，不改动共享夹具）
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(300);
});
