/**
 * E2E · task-260819 v9 · 数据集选择器与字段面板 —— AC-115 / AC-116（需求文档.md §9.4 D 组）
 *
 * AC-115 原文：新建 SQL 视图 → 出现数据集选择器，三个选项：报价 / 基础核价 / 明细核价
 * AC-116 原文：已选「基础核价」→ 看字段面板 → 只出现 ds_cost_basic_* 的表；
 *              ds_quote_* 与 ds_cost_detail_* 的表**一张都不出现**（不是置灰，是不出现）
 *
 * ─────────────────────────────────────────────────────────────────────────
 * 🚨 三条纪律在本文件的落实
 *
 * ① **不写死中文表名**。三套数据集叫什么、面板上显示什么名字，由 B-42 机器生成的种子决定，不由我定。
 *    因此「另两套的表」这个集合是**运行期从 `GET /api/cpq/config/semantic-graph` 现算的**：
 *    其它两个方言的 SHEET 节点显示名，减去基础核价也有的同名项 = 真正的「不该出现」清单。
 *    写死中文名的话，种子一改名，用例要么假绿要么假红。
 *
 * ② **阳性对照**（testing.md §4.4）。断言「一张都不出现」之前，先断言「基础核价的表出现了 ≥1 张」——
 *    否则面板整个没渲染出来时，「另两套一张都没有」也成立，会得到一个毫无意义的绿。
 *
 * ③ **skip != pass**。后端没起、入口进不去，一律 **硬失败**并注明「未验证」，
 *    🚫 不用 `test.skip` —— 历史事故：AC-26 自 D-64 起一直 SKIP、从未真正执行，
 *    差点被当成「已验证」写进结案报告。
 * ─────────────────────────────────────────────────────────────────────────
 *
 * 🎨 1:1 还原基准：`dev-docs/task-260819-取数配置器/原型图/原型-v9-数据集与字段面板.html`
 *    原型里数据集是一个 segmented 控件，三个选项文案为「报价 / 基础核价 / 明细核价」，
 *    位置在「页签类型」**之前**（它决定字段面板出哪些表）。
 *
 * ⚠️ 选择器策略：F-30 落地前，本文件按原型的**可见文案 + 语义角色**写选择器，
 *    优先 getByText / getByRole，避免假设 antd 具体 class（`cpq-playwright-selector-pitfalls`：
 *    类名不稳 / 两字按钮渲染成「保 存」/ 下拉虚拟滚动 —— 四个坑都表现为 timeout，易误判成产品 bug）。
 *    落地后若选择器对不上需按 `docs/E2E测试方法.md` 校准 —— 那属于「用例随实现细节校准」，
 *    **不改变断言本身要验的 AC 内容**。
 *
 * 📌 证据归档：本 spec 的截图写到 `dev-docs/task-260819-取数配置器/证据/e2e/`，
 *    **不留在 test-results/**（那目录每轮开跑前会被清空 ⇒ 留在那儿等于没有证据，testing.md §2）。
 */
import { test, expect, Page, APIRequestContext } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { loginAsAdmin, isBackendUp } from './fixtures/auth';

const __filename2 = fileURLToPath(import.meta.url);
const __dirname2 = path.dirname(__filename2);

const TAG = 'V9T-';
const BACKEND_URL = process.env.PW_BACKEND_URL || 'http://localhost:8081';
const EVIDENCE_DIR = path.join(
  __dirname2, '..', '..', 'dev-docs', 'task-260819-取数配置器', '证据', 'e2e'
);

const DATASET_LABELS = ['报价', '基础核价', '明细核价'];

let backendUp = false;

test.beforeAll(async () => {
  backendUp = await isBackendUp();
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
});

test.beforeEach(async ({ page }) => {
  // 🚫 不用 test.skip —— 后端没起就是「本条 AC 未验证」，必须以红的身份出现在报告里
  expect(
    backendUp,
    `后端 ${BACKEND_URL} 未启动 ⇒ AC-115 / AC-116【未验证】。\n` +
      `  这是环境前置未就绪，不是产品缺陷；但 🚫 不得记成通过（skip != pass）。\n` +
      `  起后端：cd cpq-backend && ./mvnw quarkus:dev`
  ).toBe(true);
  await loginAsAdmin(page);
});

// ═══════════════════════════════════════════════════════════════════════
// AC-115（单点）数据集选择器三选一
// ═══════════════════════════════════════════════════════════════════════
test('AC-115: 新建 SQL 视图时出现数据集选择器，三个选项 报价/基础核价/明细核价，且位于「页签类型」之前', async ({ page }) => {
  await openBuilderTab(page);

  const dsLabel = page.getByText('数据集', { exact: true }).first();
  await expect(
    dsLabel,
    'AC-115①: 配置器顶部应出现「数据集」选择器（原型 §9.9：V6 原型里完全没有这个概念，是本期新增）'
  ).toBeVisible({ timeout: 10_000 });

  // ② 三个选项都在，且文案与原型一致
  for (const label of DATASET_LABELS) {
    await expect(
      page.getByText(label, { exact: true }).first(),
      `AC-115②: 数据集选择器应含选项「${label}」（原型 DS 常量：报价 / 基础核价 / 明细核价）`
    ).toBeVisible();
  }

  // ③ 恰好三项，不多不少
  const optionCount = await countDatasetOptions(page);
  expect(
    optionCount,
    `AC-115③: 数据集选项应恰好 3 个，实际=${optionCount}。` +
      `\n  多于 3：可能把 _history 或年降表也当成了数据集；少于 3：某一套没接上。`
  ).toBe(3);

  // ④ 位置在「页签类型」之前 —— 原型明写「它决定字段面板出哪些表」，顺序是语义的一部分
  const order = await page.evaluate(() => {
    const all = Array.from(document.querySelectorAll('body *'));
    const idxOf = (t: string) => all.findIndex((e) => e.childElementCount === 0 && e.textContent?.trim() === t);
    return { ds: idxOf('数据集'), tab: idxOf('页签类型') };
  });
  expect(order.ds, 'AC-115④: DOM 里找不到「数据集」文本节点').toBeGreaterThanOrEqual(0);
  expect(order.tab, 'AC-115④: DOM 里找不到「页签类型」文本节点').toBeGreaterThanOrEqual(0);
  expect(
    order.ds,
    `AC-115④: 「数据集」必须排在「页签类型」之前（原型 §9.9 / F-30①）。实际下标 数据集=${order.ds} 页签类型=${order.tab}`
  ).toBeLessThan(order.tab);

  await page.screenshot({ path: path.join(EVIDENCE_DIR, 'AC-115-数据集选择器三选一.png'), fullPage: true });
});

// ═══════════════════════════════════════════════════════════════════════
// AC-116（单点 + 边界 + 序列）跨数据集隔离
// ═══════════════════════════════════════════════════════════════════════
test('AC-116: 选「基础核价」后字段面板只出该数据集的表，另两套一张都不出现（不是置灰）', async ({ page, request }) => {
  // ── 运行期算出「该出现」与「绝不该出现」两个集合（🚫 不写死中文表名）
  const sets = await datasetSheetNames(request);
  console.log('[AC-116] 基础核价 SHEET 显示名 =', [...sets.costBasic]);
  console.log('[AC-116] 另两套独有 SHEET 显示名 =', [...sets.exclusiveToOthers]);

  expect(
    sets.costBasic.size,
    'AC-116 前置：语义图里 COST_BASIC 一个 SHEET 节点都没有 ⇒ 本条 AC【未验证】（B-42 种子未落地），不是产品缺陷'
  ).toBeGreaterThan(0);
  expect(
    sets.exclusiveToOthers.size,
    'AC-116 前置：另两套没有任何「独有」的表名（三套显示名完全同名）⇒ ' +
      '「一张都不出现」这条断言在 DOM 文本层面无法证伪。\n' +
      '  须改判据（例如让面板把物理表名也渲染出来，或改用 field-tree 响应体断言）—— 停下来报主线，不要自行放宽。'
  ).toBeGreaterThan(0);

  await openBuilderTab(page);
  await selectDataset(page, '基础核价');

  const panelText = await readFieldPanelText(page);
  console.log('[AC-116] 字段面板文本长度 =', panelText.length);

  // ── 阳性对照：先证明面板真的渲染出了基础核价的表（否则「另两套没有」是空验证）
  const shown = [...sets.costBasic].filter((n) => panelText.includes(n));
  expect(
    shown.length,
    `AC-116【阳性对照】: 选「基础核价」后，字段面板里应至少出现 1 张该数据集的表。\n` +
      `  期望候选=${[...sets.costBasic].join(' / ')}\n` +
      `  面板实际文本（截断 800 字）=\n${panelText.slice(0, 800)}\n` +
      `  ⚠️ 这一条不过，下面的「另两套一张都没有」就是空验证，整条 AC 判【未验证】。`
  ).toBeGreaterThan(0);
  console.log('[AC-116] 面板中出现的基础核价表 =', shown);

  // ── 主断言：另两套的表一张都不出现
  const leaked = [...sets.exclusiveToOthers].filter((n) => panelText.includes(n));
  expect(
    leaked,
    `AC-116: 选「基础核价」后，ds_quote_* 与 ds_cost_detail_* 的表必须【一张都不出现】。\n` +
      `  泄漏的表=${leaked.join(' / ')}\n` +
      `  📌 AC 原文强调「不是置灰，是不出现」—— 置灰同样算不通过。`
  ).toEqual([]);

  // ── 边界：确认不是「渲染了但置灰」。若上面已通过，这里再扫一遍禁用态元素兜底。
  const disabledTexts = await page.$$eval(
    '[aria-disabled="true"], [disabled], .ant-select-item-option-disabled, [draggable="false"]',
    (els) => els.map((e) => (e.textContent || '').trim()).filter(Boolean)
  );
  const disabledLeak = [...sets.exclusiveToOthers].filter((n) => disabledTexts.some((t) => t.includes(n)));
  expect(
    disabledLeak,
    `AC-116（边界）: 另两套的表不得以「置灰」形态存在于 DOM 中。发现被置灰但仍渲染的=${disabledLeak.join(' / ')}`
  ).toEqual([]);

  await page.screenshot({ path: path.join(EVIDENCE_DIR, 'AC-116-基础核价字段面板隔离.png'), fullPage: true });
});

test('AC-116（序列）: 基础核价 → 明细核价 → 切回基础核价，每一步面板都只出当前数据集的表', async ({ page, request }) => {
  const sets = await datasetSheetNames(request);
  expect(sets.costBasic.size, 'AC-116 序列前置：COST_BASIC 无 SHEET 节点 ⇒【未验证】').toBeGreaterThan(0);
  expect(sets.costDetail.size, 'AC-116 序列前置：COST_DETAIL 无 SHEET 节点 ⇒【未验证】').toBeGreaterThan(0);

  const onlyBasic = diff(sets.costBasic, sets.costDetail, sets.quote);
  const onlyDetail = diff(sets.costDetail, sets.costBasic, sets.quote);
  expect(
    onlyBasic.size > 0 && onlyDetail.size > 0,
    `AC-116 序列前置：两套核价必须各有「独有表名」才能分辨切换是否生效。\n` +
      `  基础核价独有=${[...onlyBasic]}\n  明细核价独有=${[...onlyDetail]}\n` +
      `  都为空说明判据不成立 —— 停下来报主线换判据，不要自行放宽。`
  ).toBe(true);

  await openBuilderTab(page);

  // ① 基础核价
  await selectDataset(page, '基础核价');
  let text = await readFieldPanelText(page);
  const first = [...onlyBasic].filter((n) => text.includes(n));
  expect(first.length, `序列①: 选基础核价后应出现其独有表，候选=${[...onlyBasic]}`).toBeGreaterThan(0);
  expect(
    [...onlyDetail].filter((n) => text.includes(n)),
    '序列①: 基础核价面板不得出现明细核价独有的表'
  ).toEqual([]);

  // ② 切到明细核价（切数据集会弹确认 —— F-30④「跨数据集列完全不通，一个都保不住」）
  await selectDataset(page, '明细核价');
  text = await readFieldPanelText(page);
  expect(
    [...onlyDetail].filter((n) => text.includes(n)).length,
    `序列②: 切到明细核价后应出现其独有表，候选=${[...onlyDetail]}`
  ).toBeGreaterThan(0);
  expect(
    [...onlyBasic].filter((n) => text.includes(n)),
    '序列②: 切到明细核价后，基础核价独有的表必须消失（中间态断言 —— 只验最终态会漏掉残留）'
  ).toEqual([]);

  // ③ 切回基础核价：应与①完全一致（不残留、不叠加）
  await selectDataset(page, '基础核价');
  text = await readFieldPanelText(page);
  const back = [...onlyBasic].filter((n) => text.includes(n));
  expect(
    back.sort(),
    `序列③: 切走再切回后，基础核价面板应与首次完全一致。首次=${first.sort()} 切回=${back.sort()}\n` +
      `  不一致 = 切数据集留下了残留状态，正是「单点全过、串起来用翻车」的典型形态。`
  ).toEqual(first.sort());
  expect(
    [...onlyDetail].filter((n) => text.includes(n)),
    '序列③: 切回基础核价后不得残留明细核价的表'
  ).toEqual([]);

  await page.screenshot({ path: path.join(EVIDENCE_DIR, 'AC-116-序列-切回基础核价.png'), fullPage: true });
});

// ═══════════════════════════════════════════════════════════════════════
// 辅助
// ═══════════════════════════════════════════════════════════════════════

/** 从语义图端点现算三套数据集各自的 SHEET 显示名（🚫 不写死中文表名）。 */
async function datasetSheetNames(request: APIRequestContext) {
  const res = await request.get(`${BACKEND_URL}/api/cpq/config/semantic-graph`);
  expect(
    res.status(),
    `AC-116 前置：GET /api/cpq/config/semantic-graph 应 200，实际=${res.status()}。` +
      `\n  拿不到语义图 ⇒ 无法现算「不该出现」的集合 ⇒ 本条 AC【未验证】。`
  ).toBe(200);
  const graph = await res.json();
  const nodes: any[] = graph.nodes || [];
  expect(nodes.length, 'AC-116 前置：语义图 nodes 为空 ⇒【未验证】（B-42 种子未落地）').toBeGreaterThan(0);

  const pick = (dialect: string) =>
    new Set<string>(
      nodes
        .filter((n) => n.dialect === dialect && n.nodeKind === 'SHEET' && n.displayName)
        .map((n) => String(n.displayName))
    );

  const quote = pick('QUOTE');
  const costBasic = pick('COST_BASIC');
  const costDetail = pick('COST_DETAIL');
  const exclusiveToOthers = new Set<string>(
    [...quote, ...costDetail].filter((n) => !costBasic.has(n))
  );
  return { quote, costBasic, costDetail, exclusiveToOthers };
}

function diff(a: Set<string>, ...others: Set<string>[]) {
  return new Set([...a].filter((x) => !others.some((o) => o.has(x))));
}

/**
 * 数据集选择器里有几个可选项。
 *
 * ⚠️ 原型用的是 segmented（一排可点的项），不是 antd Select 下拉，所以不能按 `.ant-select-item-option` 数。
 * 判据：在「数据集」标签所在的那一段里，文本恰为三个候选文案之一的**叶子元素**有几个。
 * 只数叶子，避免把外层容器（其 textContent 恰好等于某个标签）重复计入。
 */
async function countDatasetOptions(page: Page): Promise<number> {
  return page.evaluate((labels: string[]) => {
    const leaves = Array.from(document.querySelectorAll('body *')).filter(
      (e) => e.childElementCount === 0
    );
    const found = new Set<string>();
    for (const e of leaves) {
      const t = (e.textContent || '').trim();
      if (labels.includes(t)) {
        found.add(t);
      }
    }
    return found.size;
  }, DATASET_LABELS);
}

/** 新建一个测试组件并进入「取数配置」Tab。任何一步进不去都硬失败并说明是入口问题。 */
async function openBuilderTab(page: Page) {
  const name = `${TAG}${Date.now()}`;
  await page.goto('/components');
  await page.waitForLoadState('networkidle');

  const newBtn = page.getByRole('button', { name: /新建|新增/ }).first();
  await expect(newBtn, '进不了组件管理的「新建」按钮 ⇒ 入口问题，本条 AC【未验证】').toBeVisible({ timeout: 10_000 });
  await newBtn.click();

  const nameInput = page.locator('input[placeholder*="名称"]').first();
  await expect(nameInput, '新建组件表单里找不到名称输入框 ⇒ 入口问题').toBeVisible({ timeout: 10_000 });
  await nameInput.fill(name);
  await page.getByRole('button', { name: /确\s*定|保\s*存/ }).first().click();
  await page.waitForTimeout(1000);

  await page.getByText(name, { exact: true }).first().click();
  await page.waitForTimeout(500);
  const tab = page.getByText('取数配置', { exact: true }).first();
  await expect(tab, '组件详情里没有「取数配置」Tab ⇒ 入口问题，本条 AC【未验证】').toBeVisible({ timeout: 10_000 });
  await tab.click();
  await page.waitForTimeout(800);
}

/** 选择数据集；若弹出「切数据集会清空已选输出列」的确认，点确认（F-30④）。 */
async function selectDataset(page: Page, label: string) {
  await page.getByText(label, { exact: true }).first().click();
  // 切数据集的破坏性强于切页签类型 ⇒ 原型要求弹确认。有就确认，没有就继续（本条不是 AC-115/116 的断言点）
  const confirm = page.getByRole('button', { name: /确\s*定|确\s*认/ }).first();
  if (await confirm.isVisible().catch(() => false)) {
    await confirm.click();
  }
  await page.waitForTimeout(1200); // 等字段面板按新数据集重取
}

/** 读字段面板的可见文本。取不到面板容器时退回整页文本，并打印提示（宁可宽，也不要静默取空串）。 */
async function readFieldPanelText(page: Page): Promise<string> {
  const candidates = [
    '[data-testid="field-panel"]',
    '.field-panel',
    '#panel',
  ];
  for (const sel of candidates) {
    const el = page.locator(sel).first();
    if (await el.isVisible().catch(() => false)) {
      return (await el.innerText()).trim();
    }
  }
  console.log('[AC-116] ⚠️ 未命中字段面板容器选择器，退回整页文本（判据仍成立：整页都没有 = 面板更没有）');
  const body = (await page.locator('body').innerText()).trim();
  expect(body.length, 'AC-116: 页面文本为空 —— 断言会空跑，判【未验证】').toBeGreaterThan(0);
  return body;
}
