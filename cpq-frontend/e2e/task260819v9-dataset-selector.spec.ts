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
 *
 * ─────────────────────────────────────────────────────────────────────────
 * 🚦 2026-09-06 · task-260904 改写留痕（用户裁决由 task-260904 这边改）
 *
 * 【原断言】AC-115④：在取数配置面板内，「数据集」必须排在**「页签类型」**之前，
 *           判据 `dsBeforeTab === true`（本文件 :143 附近）。
 *
 * 【为什么失效】🚨 **不是回归。** task-260904 把「页签类型」下拉整个换成了「数据源」下拉
 *   （需求文档 §2.1 S-2，用户 2026-09-06 裁决）⇒ 参照物「页签类型」在面板里**不再存在**，
 *   原判据会以「DOM 里找不到『页签类型』文本节点」的形式恒红。
 *   ⚠️ 判它变红时别按回归归因 —— 消失是需求要求的。
 *
 * 【改成了什么】🚫 **布局约束本身保留，只换参照物**：
 *   「数据集」必须排在**「数据源」**之前。
 *   理由：用户当初提这条要求，要的是「先选数据集、再选源」的**顺序**
 *   （数据集决定字段面板出哪些表，所以必须先选）—— 这条语义在 task-260904 之后
 *   一个字都没变，变的只是后面那个控件叫什么。
 *   🚫 因此不允许把整条断言删掉或弱化成「页面加载成功」。
 *
 * 【顺带简化】原判据为绕开「组件详情页顶部配置条里也有一个『页签类型』」而做的
 *   "最近公共祖先"作用域收敛，现在依然保留 —— 顶部配置条的页签类型下拉虽已被 AC-28 移除，
 *   但按容器比文档序本来就是更稳的写法，不因参照物换名而回退成整页取下标。
 * ─────────────────────────────────────────────────────────────────────────
 */
import { test, expect, Page } from '@playwright/test';
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
test('AC-115: 新建 SQL 视图时出现数据集选择器，三个选项 报价/基础核价/明细核价，且位于「数据源」之前', async ({ page }) => {
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

  // ④ 位置在「数据源」之前 —— 原型明写「它决定字段面板出哪些表」，顺序是语义的一部分
  //
  // 🚦 2026-09-06 校准（task-260904）：参照物由「页签类型」换成「数据源」。
  //    布局约束本身**没有放松**：用户要的是「先选数据集、再选源」这个顺序，
  //    该语义一字未变，变的只是后面那个控件的名字（S-2）。
  //
  // 📌 保留 2026-09-04 的作用域收敛写法（先找同时含两个标签的最近祖先，再在容器内比文档序）——
  //    整页取下标的老写法会被页面别处的同名文本干扰，属于判据取错作用域的假红。
  const REF_LABEL = '数据源';
  const order = await page.evaluate((refLabel) => {
    const leaves = (t: string) =>
      Array.from(document.querySelectorAll('body *')).filter(
        (e) => e.childElementCount === 0 && e.textContent?.trim() === t
      );
    const dsEls = leaves('数据集');
    const refEls = leaves(refLabel);
    if (dsEls.length === 0) return { ok: false, why: 'DOM 里找不到「数据集」文本节点' };
    if (refEls.length === 0) {
      return {
        ok: false,
        why: `DOM 里找不到「${refLabel}」文本节点 —— 取数配置面板应有「数据源」下拉（task-260904 S-2）`,
      };
    }
    for (const ds of dsEls) {
      let anc: Element | null = ds.parentElement;
      while (anc) {
        const refIn = refEls.filter((t) => anc!.contains(t));
        if (refIn.length > 0) {
          // 找到同时含两者的最近容器 = 取数配置面板；在容器内比文档序
          const pos = ds.compareDocumentPosition(refIn[0]);
          return {
            ok: true,
            dsBeforeRef: (pos & Node.DOCUMENT_POSITION_FOLLOWING) !== 0,
            container: (anc as HTMLElement).className || anc.tagName,
            refCount: refEls.length,
          };
        }
        anc = anc.parentElement;
      }
    }
    return { ok: false, why: `「数据集」与「${refLabel}」没有共同祖先 —— DOM 结构与预期不符` };
  }, REF_LABEL);
  expect(order.ok, `AC-115④ 前置失败：${(order as any).why}`).toBe(true);
  console.log('[AC-115④] 比较容器 =', (order as any).container,
    `| 页面上「${REF_LABEL}」文本节点总数 =`, (order as any).refCount);
  expect(
    (order as any).dsBeforeRef,
    `AC-115④: 在取数配置面板内，「数据集」必须排在「${REF_LABEL}」之前`
      + '（原型 §9.9 / F-30① —— 数据集决定字段面板出哪些表，所以必须先选；'
      + 'task-260904 只换了参照物名字，约束本身不变）'
  ).toBe(true);

  await page.screenshot({ path: path.join(EVIDENCE_DIR, 'AC-115-数据集选择器三选一.png'), fullPage: true });
});

// ═══════════════════════════════════════════════════════════════════════
// AC-116（单点 + 边界 + 序列）跨数据集隔离
// ═══════════════════════════════════════════════════════════════════════
test('AC-116: 选「基础核价」后字段面板只出该数据集的表，另两套一张都不出现（不是置灰）', async ({ page }) => {
  // ── 运行期算出「该出现」与「绝不该出现」两个集合（🚫 不写死中文表名）
  const sets = await datasetSheetNames(page);
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

  // ── 阳性对照 ⓪【精确】：面板自报的表前缀必须是基础核价那一套
  //    这一条比下面按中文显示名找表**强得多** —— 三套数据集的显示名大量重名
  //    （`物料`/`物料BOM`/`物料与元素BOM` 三套同名），只按名字找到「物料」
  //    根本分不清是 ds_quote_material 还是 ds_cost_basic_material。
  const prefix = await readTablePrefix(page);
  console.log('[AC-116] 面板自报表前缀 =', prefix);
  expect(
    prefix,
    `AC-116【阳性对照·精确】: 选「基础核价」后面板表前缀应为 ds_cost_basic_，实际=${prefix}。\n` +
      `  不是它 ⇒ 数据集根本没切过去（或切错了），后面「另两套没泄漏」是空验证。`
  ).toBe('ds_cost_basic_');

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

test('AC-116（序列）: 基础核价 → 明细核价 → 切回基础核价，每一步面板都只出当前数据集的表', async ({ page }) => {
  const sets = await datasetSheetNames(page);
  expect(sets.costBasic.size, 'AC-116 序列前置：COST_BASIC 无 SHEET 节点 ⇒【未验证】').toBeGreaterThan(0);
  expect(sets.costDetail.size, 'AC-116 序列前置：COST_DETAIL 无 SHEET 节点 ⇒【未验证】').toBeGreaterThan(0);

  const onlyDetail = diff(sets.costDetail, sets.costBasic, sets.quote);
  // 🔄 2026-09-03 实跑修正：原判据要求「两套核价各有独有表名」，但
  //    **「基础核价独有」恒为空** —— §9.2 三套结构同构，COST_BASIC 的 10 张表名
  //    全被 QUOTE / COST_DETAIL 覆盖（实测：基础核价独有=∅，明细核价独有 8 个）。
  //    ⇒ 改用**单侧鉴别**：拿「明细核价独有」的 8 个名字当探针
  //      —— 选基础核价时它们必须一个都不出现，选明细核价时必须至少出现一个，切回又全部消失。
  //    这同样能证明「切换真的生效且不残留」，且不依赖一个恒空的集合。
  expect(
    onlyDetail.size,
    `AC-116 序列前置：明细核价必须有「独有表名」才能当切换探针。实际=${[...onlyDetail]}\n` +
      `  为空说明判据不成立 —— 停下来报主线换判据，不要自行放宽。`
  ).toBeGreaterThan(0);
  console.log('[AC-116 序列] 探针（明细核价独有）=', [...onlyDetail]);

  await openBuilderTab(page);

  // ① 基础核价
  await selectDataset(page, '基础核价');
  let text = await readFieldPanelText(page);
  // 阳性对照：面板得真的渲染出东西（否则「探针不出现」是空验证）
  const basicShown = [...sets.costBasic].filter((n) => text.includes(n));
  expect(
    basicShown.length,
    `序列①【阳性对照】: 选基础核价后面板应至少出现 1 张该数据集的表，候选=${[...sets.costBasic]}`
  ).toBeGreaterThan(0);
  expect(
    [...onlyDetail].filter((n) => text.includes(n)),
    '序列①: 基础核价面板不得出现明细核价独有的表（探针）'
  ).toEqual([]);

  // ② 切到明细核价（切数据集会弹确认 —— F-30④「跨数据集列完全不通，一个都保不住」）
  //
  // 🚦 2026-09-04 校准：原判据是「切过去后应出现明细核价**独有的表名**」，实测恒为 0。
  //    根因不是产品没切 —— 是**字段面板按「页签类型」过滤**，当前页签类型是「主件」，
  //    面板永远只出一张 `物料`，`产能/电镀成本/...` 这些独有表本就不该在「主件」下出现。
  //    ⇒ 原探针在这个页签类型下**永远取不到值**，属判据选错探针，不是回归。
  //    改用面板自报的**表前缀**做中间态探针：它随数据集变、与页签类型无关，才是「切换真的生效」的信号。
  await selectDataset(page, '明细核价');
  const prefixDetail = await readTablePrefix(page);
  console.log('[AC-116 序列②] 切到明细核价后表前缀 =', prefixDetail);
  expect(
    prefixDetail,
    `序列②【中间态】: 切到明细核价后面板表前缀应为 ds_cost_detail_，实际=${prefixDetail}。\n` +
      `  不变 = 面板没刷新（切换没生效），这正是「单点全过、串起来翻车」要抓的形态。`
  ).toBe('ds_cost_detail_');
  text = await readFieldPanelText(page);
  expect(
    [...diff(sets.costBasic, sets.costDetail, sets.quote)].filter((n) => text.includes(n)),
    '序列②: 切到明细核价后，基础核价**独有**的表不得残留（三套重名的表不算残留，故只看独有名）'
  ).toEqual([]);

  // ③ 切回基础核价：应与①完全一致（不残留、不叠加）
  await selectDataset(page, '基础核价');
  const prefixBack = await readTablePrefix(page);
  console.log('[AC-116 序列③] 切回基础核价后表前缀 =', prefixBack);
  expect(
    prefixBack,
    `序列③【中间态】: 切回基础核价后表前缀应回到 ds_cost_basic_，实际=${prefixBack}`
  ).toBe('ds_cost_basic_');
  text = await readFieldPanelText(page);
  const back = [...sets.costBasic].filter((n) => text.includes(n));
  expect(
    back.sort(),
    `序列③: 切走再切回后，基础核价面板应与首次完全一致。首次=${basicShown.sort()} 切回=${back.sort()}\n` +
      `  不一致 = 切数据集留下了残留状态，正是「单点全过、串起来用翻车」的典型形态。`
  ).toEqual(basicShown.sort());
  expect(
    [...onlyDetail].filter((n) => text.includes(n)),
    '序列③: 切回基础核价后不得残留明细核价的表（探针必须又全部消失）'
  ).toEqual([]);

  await page.screenshot({ path: path.join(EVIDENCE_DIR, 'AC-116-序列-切回基础核价.png'), fullPage: true });
});

// ═══════════════════════════════════════════════════════════════════════
// 辅助
// ═══════════════════════════════════════════════════════════════════════

/** 从语义图端点现算三套数据集各自的 SHEET 显示名（🚫 不写死中文表名）。 */
/**
 * ⚠️ 必须用 `page.request` 而不是独立的 `request` fixture ——
 * 后者不共享浏览器上下文的 cookie，`loginAsAdmin(page)` 拿到的 CPQ_SESSION 带不过去，恒 401。
 * 实跑栽过一次：三条用例都倒在「GET /config/semantic-graph 实际=401」。
 */
async function datasetSheetNames(page: Page) {
  const res = await page.request.get(`${BACKEND_URL}/api/cpq/config/semantic-graph`);
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

/**
 * 进入「取数配置」Tab（取数配置器 = 本任务的被测界面）。
 *
 * 🚦 2026-09-04 校准（实跑逐屏探明，替换首轮那版）。首轮那版照搬 `cross-tab-builder.spec.ts`
 * 的 `.cm-tree-container / .ant-tree / [role="tree"]`，**当前 UI 上这些容器一个都不存在** ——
 * 组件管理页左栏是自绘的 `.cm-layout` 目录列表（`📁 目录名` + `▶` 展开箭头），不是 antd Tree。
 * 首轮 3 条用例全部倒在这里，症状是 timeout，**长得像产品缺陷，其实是选择器写错**
 * （`cpq-playwright-selector-pitfalls` 的典型形态）。
 *
 * 实测有效的操作序列（逐步都验过）：
 *   ① `/components` → ② 点一个目录（📁 罗克韦尔）把它选中
 *   ③ 点工具栏「新建」——⚠️ **不选目录时点它没有任何反应**，不弹窗也不报错
 *   ④ 右侧**内联**出「新建组件」表单（不是 Modal，也不是 Drawer）：
 *      组件类型=页签组件 / 组件名称 `input[placeholder*="投料成本表"]` / 所属目录
 *   ⑤ 点「创 建」——⚠️ antd 两字按钮渲染成「创 建」（中间有空格），
 *      正则必须写 `/^创\s*建$/`，写 `/^创建$/` 匹配不到
 *   ⑥ 组件打开，右侧出 Tab 条：字段配置 / 取数配置 / 公式 / SQL 视图 → 点「取数配置」
 *
 * ⚠️ 本函数会**新建一个组件**（名字带 {@link TAG} 前缀）写进共享库。
 *    原因：打开既有组件必须先展开目录，而展开动作实跑会让 headless chrome 崩
 *    （"Target page, context or browser has been closed"）。新建是当前唯一稳定路径。
 *    产物在 `test.md §4.1` 登记，清理用正向条件 `name LIKE 'V9T-%'`。
 */
async function openBuilderTab(page: Page) {
  await page.goto('/components');
  await page.waitForTimeout(6000);

  // ② 选中一个目录（不选则「新建」无反应）
  const dir = page.getByText('罗克韦尔', { exact: false }).first();
  await expect(
    dir,
    '组件管理页左栏没有任何目录 ⇒ 入口问题，本条 AC【未验证】，不是产品缺陷'
  ).toBeVisible({ timeout: 15_000 });
  await dir.click();
  await page.waitForTimeout(2000);

  // ③ 新建
  const newBtn = page.locator('button').filter({ hasText: /^新\s*建$/ }).first();
  await expect(newBtn, '工具栏没有「新建」按钮 ⇒ 入口问题，本条 AC【未验证】').toBeVisible({ timeout: 10_000 });
  await newBtn.click();
  await page.waitForTimeout(2000);

  // ④⑤ 填名字并创建
  const nameInput = page.locator('input[placeholder*="投料成本表"]').first();
  await expect(
    nameInput,
    '点「新建」后没出现「新建组件」内联表单（组件名称输入框） ⇒ 入口问题，本条 AC【未验证】'
  ).toBeVisible({ timeout: 10_000 });
  const name = `${TAG}${Date.now()}`;
  await nameInput.fill(name);
  await page.waitForTimeout(400);
  await page.locator('button').filter({ hasText: /^创\s*建$/ }).first().click();
  await page.waitForTimeout(4000);
  console.log('[入口] 已新建组件 =', name, '（共享库产物，清理条件 name LIKE \'V9T-%\'）');

  // ⑥ 切到「取数配置」
  const tab = page.getByText('取数配置', { exact: true }).first();
  await expect(
    tab,
    '组件已打开但没有「取数配置」Tab ⇒ 入口问题，本条 AC【未验证】，不是产品缺陷'
  ).toBeVisible({ timeout: 15_000 });
  await tab.click();
  await page.waitForTimeout(4000);
}

/**
 * 读取取数配置面板自己打印的**表前缀**（`表前缀 ds_quote_` / `ds_cost_basic_` / `ds_cost_detail_`）。
 *
 * 🔑 为什么要这个：AC-116 原来的阳性对照用**中文显示名**判「基础核价的表出现了」，
 * 但三套数据集的显示名**大量重名**（`物料` / `物料BOM` / `物料与元素BOM` 三套都叫这个）。
 * 实测「主件」页签下面板只出一张 `物料` —— 这个名字无法区分它是 `ds_quote_material`
 * 还是 `ds_cost_basic_material`，⇒ 阳性对照其实**证不出数据集选对了**。
 * 面板自己渲染的 `表前缀 ds_xxx_` 是**数据集独有**的字符串，是真正能证伪的信号。
 */
async function readTablePrefix(page: Page): Promise<string> {
  const body = await page.locator('body').innerText();
  const m = body.match(/表前缀\s*(ds_[a-z_]+_)/);
  expect(
    m,
    '取数配置面板里读不到「表前缀 ds_xxx_」标记 —— 该标记是本用例判定「数据集真的切过去了」的唯一精确信号。\n' +
      '  读不到说明面板文案变了 ⇒ 判据需重新校准（本条 AC 判【未验证】，不是产品缺陷）。\n' +
      `  页面文本（截断 800）=\n${body.slice(0, 800)}`
  ).not.toBeNull();
  return m![1];
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
