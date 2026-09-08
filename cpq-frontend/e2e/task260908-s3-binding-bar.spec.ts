/**
 * E2E · task-260908「取数配置器优化」· 分片 S3 之一：**组件绑定区**（AC-10 / AC-11 / AC-12）
 *
 * ════════════════════════════════════════════════════════════════════════════
 * AC 原文（`dev-docs/task-260908-取数配置器优化/需求文档.md §③`，逐字抄录，🚫 不得改写）
 * ────────────────────────────────────────────────────────────────────────────
 * AC-10 · 物料与元素BOM 显示元素列/元素单价列
 *   组件管理 → 打开 `dc3297cc-e9a3-4184-8bc8-bb61db2a07d2`（「材质元素」，绑定「物料与元素BOM」）
 *   → 绑定区显示 **4 个下拉**：料号列 / 名称列 / 元素列 / 元素单价列，
 *     且元素列回填 `元素`、元素单价列回填 `元素单价`。
 *
 * AC-11 · 其余数据源不显示元素列/元素单价列
 *   分别打开 `f6d51727-…`（「T260907-物料BOM」，BOM 源）与 `04375490-…`（「T260907-来料其他费用」，费用类源）
 *   → 两者绑定区均**只显示 2 个下拉**：料号列 / 名称列。
 *   🚫 页面上不出现「元素列」「元素单价列」字样。
 *
 * AC-12 · 货币列在任何数据源下都不显示
 *   遍历上述两种组件 → 页面上均**不出现「货币列」字样**。
 *   🚫 反向断言（D-4）：后端 `component.element_currency_field` 列仍在、
 *      `ComponentDTO.elementCurrencyField` 仍返回、存量有值的组件其值**未被清空**：
 *        select element_currency_field from component where id='196aadee-b89f-4f81-984b-4c6747b59149';
 *        -- 期望仍为 '货币'
 *      ⚠️ 断言写成点名单行，不写成 count(*)=1 —— 全局计数会被同批并行的别片造数打红。
 *
 * 视觉基准：`dev-docs/task-260908-取数配置器优化/原型图/01-组件绑定区.html` 状态 1 / 2 / 3
 * ════════════════════════════════════════════════════════════════════════════
 *
 * 🧩 分片纪律（`docs/rules/testing.md` §4.5）
 *   - 本片写入面 = **只读**：只打开既有组件看绑定区，🚫 不新建、不保存、不改任何一行。
 *   - 无造数前缀（不造数）。🚫 不碰 S2 的 `T260908-S2-` / S-全局 的 `T260908-G-` 数据。
 *   - 🚫 无全局计数断言。AC-12 反向断言按 AC 原文写成**点名单行**，
 *     🚫 不许"顺手"改成 `count(*)=1`（那会被别片造数打红，且红得像业务回归）。
 *   - psql 只走 `roSql()`，它硬拒任何非 SELECT 语句（守卫，见下）。
 *
 * 📸 证据归档：截图写进 `dev-docs/task-260908-取数配置器优化/证据/e2e-s3/`，
 *    🚫 不写 `test-results/` —— 那目录每轮开跑前被清空，留在那里等于没有证据（testing.md §2）。
 *
 * 🔬 量具自检（testing.md §4.4「先证明它会动」）
 *    带 `PW_T260908_BASELINE=1` 跑本 spec = **改动前基线模式**：
 *      AC-11/AC-12 的断言反转成「元素列/元素单价列/货币列**应当出现**」。
 *    ⇒ 在**未改动的 master**（或 F-1 落地前）跑一次基线模式，必须**全绿**；
 *      在 F-1 落地后跑基线模式，必须**全红**。
 *      两个方向都对上，才证明这几条断言真的接在「货币列在不在」这个维度上，
 *      而不是恒真/恒假。🚫 只跑正向一次 PASS 不构成证据。
 *
 * 运行：
 *   npx playwright test --config=e2e/playwright.config.ts e2e/task260908-s3-binding-bar.spec.ts --reporter=list
 *   （worktree 临时环境：PW_BASE_URL=... PW_BACKEND_URL=... 前置，🚫 不许占用主线的 5174/8081）
 */
import { test, expect, Page, Locator } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { execSync } from 'child_process';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const __filenameLocal = fileURLToPath(import.meta.url);
const BACKEND_URL = process.env.PW_BACKEND_URL || 'http://localhost:8081';
/** 基线模式：断言改动**前**的形态，用来证明量具会动（见文件头「量具自检」） */
const BASELINE = process.env.PW_T260908_BASELINE === '1';

const SHOT_DIR = path.resolve(
  path.dirname(__filenameLocal),
  '../../dev-docs/task-260908-取数配置器优化/证据/e2e-s3'
);
fs.mkdirSync(SHOT_DIR, { recursive: true });

/** 绑定区五个字段的**界面文案**（AC-10/11/12 断言的就是这些字串本身） */
const F_PART_NO = '料号列';
const F_PART_NAME = '名称列';
const F_ELEM = '元素列';
const F_ELEM_PRICE = '元素单价列';
const F_CURRENCY = '货币列';
const ALL_FIELDS = [F_PART_NO, F_PART_NAME, F_ELEM, F_ELEM_PRICE, F_CURRENCY] as const;

/** AC-12 反向断言点名的那一行（🚫 不许换成计数） */
const CURRENCY_COMPONENT_ID = '196aadee-b89f-4f81-984b-4c6747b59149';
const CURRENCY_EXPECTED = '货币';

// ═══════════════════════════════════════════════════════════════════════════
// 只读 SQL 守卫
// ═══════════════════════════════════════════════════════════════════════════

/**
 * 只读查询。**本片写入面是只读**，所以这里硬拒一切非 SELECT 语句 ——
 * 守卫比纪律值钱的地方是：它不依赖我当时想不想得起来（testing.md §5.6）。
 */
function roSql(sql: string): string {
  const head = sql.trim().slice(0, 6).toLowerCase();
  if (head !== 'select') {
    throw new Error(
      `[S3 只读守卫] 本片只允许 SELECT，收到：${sql.slice(0, 80)}…\n` +
        '  若确实需要写库，那说明用例分片分错了 —— 停下来报主线，🚫 不要绕过本守卫。'
    );
  }
  return execSync(
    `PGPASSWORD=joii5231 psql -h 10.177.152.12 -p 5432 -U postgres -d cpq_db_0724 -At -F '|' -c "${sql.replace(
      /"/g,
      '\\"'
    )}"`,
    { encoding: 'utf-8', stdio: ['pipe', 'pipe', 'pipe'], shell: '/bin/bash' }
  ).trim();
}

// ═══════════════════════════════════════════════════════════════════════════
// Fixture 解析
// ═══════════════════════════════════════════════════════════════════════════

type CompFixture = {
  /** AC 点名的 id（首选） */
  acId: string;
  /** 实际使用的 id（可能因 AC 允许的「同坐标替换」而不同） */
  id: string;
  code: string;
  name: string;
  /** builder_config 三段坐标 —— **AC 原文说「坐标才是判据」** */
  coord: string;
  /** 是否用了替身；用了就要在报告里点名说 */
  substituted: boolean;
};

/**
 * 按 AC 的规则解析组件 fixture。
 *
 * AC 原文：「若执行时组件已被清理，**换一个同 `(tabType, variantKey, dialect)` 坐标的组件即可**，坐标才是判据。」
 *
 * 🚨 但本项目还有第二道现实约束（2026-09-07 `task260907-builder-gaps.spec.ts:382` 实测、已报主线）：
 *    `directory_id IS NULL` 的组件**既不在目录树里、搜索也搜不到**，且**组件详情不进 URL**（无深链）
 *    ⇒ 这类组件在 UI 上**根本打不开**，拿它当 fixture 的用例会 100% 倒在"找不到组件"上，
 *      而那个失败**长得和产品缺陷一模一样**。
 *    ⇒ 因此候选必须额外满足 `directory_id IS NOT NULL`。
 *
 * ⚠️ 候选一律来自**固定 id 白名单**，🚫 不做「按条件全库扫描取第一个」——
 *    那会把 S2 正在造的 `T260908-S2-*` 组件扫进来，变成跨片串扰（testing.md §4.5）。
 */
function resolveComponent(acId: string, candidates: string[], why: string): CompFixture {
  const ids = [acId, ...candidates.filter((c) => c !== acId)];
  const rows = roSql(
    `select c.id::text, c.code, c.name, coalesce(c.directory_id::text,''), ` +
      `coalesce(v.builder_config->>'tabType',''), coalesce(v.builder_config->>'variantKey',''), ` +
      `coalesce(v.builder_config->>'dialect','') ` +
      `from component c left join component_sql_view v on v.component_id=c.id ` +
      `where c.id in (${ids.map((i) => `'${i}'`).join(',')})`
  );
  expect(rows, `[fixture] ${why}：候选组件在库里一个都不存在 —— 这是夹具漂移，不是产品缺陷`).not.toBe('');

  const parsed = rows.split('\n').map((l) => l.split('|'));
  const pick = (id: string) => parsed.find((p) => p[0] === id);

  for (const id of ids) {
    const row = pick(id);
    if (!row) continue;
    const [rid, code, name, dir, tt, vk, dl] = row;
    if (!dir) {
      console.log(
        `[fixture] ${why}：跳过 ${code}/${name}（${rid}）—— directory_id 为空，UI 上打不开（无深链、搜不到）`
      );
      continue;
    }
    const f: CompFixture = {
      acId,
      id: rid,
      code,
      name,
      coord: `${tt}|${vk}|${dl}`,
      substituted: rid !== acId,
    };
    if (f.substituted) {
      console.log(
        `⚠️ [fixture] ${why}：AC 点名的 ${acId} 不可用，改用同/近坐标替身 ${code}「${name}」coord=${f.coord}。` +
          '  ⚠️ 这一条必须写进 test-report.md 的「fixture 偏差」，🚫 不许静默替换。'
      );
    } else {
      console.log(`[fixture] ${why}：使用 AC 点名组件 ${code}「${name}」coord=${f.coord}`);
    }
    return f;
  }
  throw new Error(
    `[fixture] ${why}：候选 ${ids.join(' / ')} 全部不可用（不存在，或 directory_id 为空导致 UI 打不开）。\n` +
      '  🚨 这是 **fixture/harness 缺口，不是产品缺陷** —— 停下来报主线，' +
      '请其提供一个「同坐标 + 有目录」的组件，或把该组件挂进任一组件目录。'
  );
}

// ═══════════════════════════════════════════════════════════════════════════
// 页面操作
// ═══════════════════════════════════════════════════════════════════════════

let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `s3-bind-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}

/**
 * 在 /components 打开指定组件。
 *
 * 🚩 选择器口径（来自既有 spec 的实测，不是猜的）：
 *   - `/components` 是「组件目录树 + 右侧详情」，🚫 没有 table / ant-table-row（`task260904-semantic-gate.spec.ts:160`）
 *   - 搜索框 placeholder 含「搜索组件名」
 *   - 命中项 click 可能被遮挡 ⇒ 用 force
 *   - 用 **code** 搜（唯一），🚫 不用 name（「材质元素」「产品」「BOM」这类名字全库重名）
 */
async function openComponent(page: Page, f: CompFixture) {
  await page.goto('/components');
  await page.waitForLoadState('networkidle');
  const search = page.locator('input[placeholder*="搜索"]').first();
  await expect(search, '组件页应有搜索框（placeholder 含「搜索」）').toBeVisible({ timeout: 15_000 });
  await search.fill(f.code);
  await page.waitForTimeout(2000);

  const hit = page.getByText(f.code, { exact: false }).first();
  await expect(
    hit,
    `[harness] 组件目录树里搜不到 ${f.code}「${f.name}」—— 这是入口/数据归属问题，不是绑定区缺陷`
  ).toHaveCount(1, { timeout: 15_000 });
  await hit.click({ force: true, timeout: 15_000 });
  await page.waitForTimeout(1500);
}

/**
 * 取绑定区容器。
 *
 * 🚩 `.cmm-acts` 是组件详情右侧的动作/绑定区，`tmp-task0729-screen8-verify.spec.ts:42` 起沿用至今。
 * 原型图（状态 2 的 note）明确：本次改动**只是少两个下拉，不是布局重排** ⇒ 容器不变。
 * ⚠️ 若 F-1 落地后此处找不到，要改的是**这个选择器**，🚫 不是下面的断言 —— 断言直接来自 AC 原文。
 */
function bindingBar(page: Page): Locator {
  return page.locator('.cmm-acts').first();
}

/**
 * 读绑定区的**每个下拉当前显示什么**。
 *
 * 🚩 2026-09-08 实测口径（主线在临时端口上跑出的读数，与本函数逐字对上）：
 *      材质元素源 → ["材质料号","名称列","元素","元素单价"]
 *      其余源     → ["料号","名称列"]
 *    ⇒ 绑定区**没有独立的静态 label**：每个下拉「有值显示值、无值显示占位符」，
 *      而占位符文案恰好就是字段名（料号列 / 名称列 / 元素列 / 元素单价列 / 货币列）。
 *
 * 🩺 这一点决定了断言必须怎么写（我的初版按「页面上有没有『元素列』这四个字」判断，
 *    在「元素列已回填『元素』」时会**误判成没有这个下拉** —— 那是 harness 缺口不是产品缺陷）：
 *      · 判「某字段**在不在**」（AC-11/AC-12 的缺席断言）：该字段无值 ⇒ 占位符就是字段名 ⇒ 可按名字判
 *      · 判「某字段**回填了什么**」（AC-10）：按下拉个数 + 显示值判，🚫 不能按名字判
 */
async function readBindingBar(page: Page): Promise<{ slots: string[]; text: string }> {
  const bar = bindingBar(page);
  await expect(
    bar,
    '[harness] 找不到组件绑定区容器 `.cmm-acts` —— 选择器过期或组件详情没打开，🚫 不要据此判定 AC 失败'
  ).toBeVisible({ timeout: 15_000 });

  const sels = bar.locator('.ant-select');
  const n = await sels.count();
  const slots: string[] = [];
  for (let i = 0; i < n; i++) {
    const sel = sels.nth(i);
    const item = sel.locator('.ant-select-selection-item').first();
    if ((await item.count()) > 0) {
      slots.push(((await item.innerText().catch(() => '')) || '').trim());
      continue;
    }
    const ph = sel.locator('.ant-select-selection-placeholder').first();
    if ((await ph.count()) > 0) {
      slots.push(((await ph.innerText().catch(() => '')) || '').trim());
      continue;
    }
    slots.push(((await sel.innerText().catch(() => '')) || '').trim());
  }
  const text = ((await bar.innerText().catch(() => '')) || '').trim();
  return { slots, text };
}

/**
 * 判某个字段名（料号列 / 元素列 / 货币列 …）**是否出现在绑定区**。
 *
 * 🔑 只用于「该字段在库里没有值」的组件 —— 此时它若被渲染，占位符就是字段名本身。
 *    并集口径（占位符 ∪ 容器可见文本）对**缺席断言更严格**：任何残留都算「出现」⇒ 不会假绿。
 */
function fieldShown(bar: { slots: string[]; text: string }, field: string): boolean {
  return bar.slots.includes(field) || bar.text.includes(field);
}

// ═══════════════════════════════════════════════════════════════════════════

let backendUp = false;
let matElem: CompFixture;   // 材质元素 / QUOTE —— AC-10
let bomSrc: CompFixture;    // BOM / QUOTE —— AC-11 第一路
let feeSrc: CompFixture;    // 费用类 / INCOMING_OTHER_FEE / QUOTE —— AC-11 第二路

/** 组件在库里的绑定字段值（决定每个下拉该显示"值"还是"占位符"） */
type CompBinds = { partNo: string; partName: string; elem: string; price: string; currency: string };
function readBinds(id: string): CompBinds {
  const row = roSql(
    `select coalesce(part_no_field,''), coalesce(part_name_field,''), coalesce(element_code_field,''), ` +
      `coalesce(element_price_field,''), coalesce(element_currency_field,'') from component where id='${id}'`
  );
  expect(row, `[前置] 组件 ${id} 不存在`).not.toBe('');
  const [partNo, partName, elem, price, currency] = row.split('|');
  return { partNo, partName, elem, price, currency };
}

/** 某个下拉「应当显示什么」：有值显示值，无值显示占位符（= 字段名） */
function expectedSlot(value: string, fieldName: string): string {
  return value || fieldName;
}

test.beforeAll(async () => {
  backendUp = await isBackendUp();
  if (!backendUp) return;

  // 2026-09-08 主线已把三个 AC 点名组件全部挂进「取值配置器测试」目录（334c394b-…）
  // ⇒ 三条 AC 均可按**原文点名的组件**验，不再需要同坐标替身。
  matElem = resolveComponent('dc3297cc-e9a3-4184-8bc8-bb61db2a07d2', [], 'AC-10 · 物料与元素BOM 源');
  bomSrc = resolveComponent('f6d51727-bd1e-4cc3-a839-94208cc12f41', [], 'AC-11 · BOM 源');
  feeSrc = resolveComponent('04375490-927f-4802-bcc7-4156df4fc27d', [], 'AC-11 · 费用类源');
  for (const f of [matElem, bomSrc, feeSrc]) {
    expect(
      f.substituted,
      `[fixture] ${f.acId} 仍不可用而启用了替身 —— AC 原文点名的组件必须可达，先报主线`
    ).toBe(false);
  }
});

test.beforeEach(async ({ page }) => {
  test.skip(!backendUp, '后端未启动 —— harness 前置未满足。🚨 报告里必须记「未验证」，🚫 不许算通过');
  await loginAsAdmin(page);
  expect(page.url(), '登录后不应停在 /login').not.toContain('/login');
});

// ───────────────────────────────────────────────────────────────────────────
// T-S3-PRE-1 · 前置守卫：三个 fixture 都能打开且绑定区读得出下拉
//   目的：把「打不开组件」这类 harness 失败与「显示错了」这类 AC 失败分开归因。
// ───────────────────────────────────────────────────────────────────────────
test('T-S3-PRE-1 · 前置：AC-10/11/12 的三个组件都能在 /components 打开并渲染出绑定区（harness 守卫）', async ({
  page,
}) => {
  for (const f of [matElem, bomSrc, feeSrc]) {
    await openComponent(page, f);
    const bar = await readBindingBar(page);
    console.log(`[PRE-1] ${f.code}「${f.name}」coord=${f.coord} 下拉 = ${JSON.stringify(bar.slots)}`);
    // 非空守卫（testing.md §3「断言从未执行 = 假绿」）：
    // 一个下拉都读不到 ⇒ 下面所有「不出现 X」的断言都会恒真
    expect(
      bar.slots.length,
      `[PRE-1] ${f.code} 的绑定区读到 0 个下拉 ⇒ AC-11/AC-12 的缺席断言会**恒真**（零证据）。` +
        ' 先修选择器/入口，🚫 不许在这个状态下判 AC 通过'
    ).toBeGreaterThan(0);
    await shot(page, `pre1-${f.code}`);
  }
});

// ───────────────────────────────────────────────────────────────────────────
// T-S3-10 · AC-10
// ───────────────────────────────────────────────────────────────────────────
test('T-S3-10 · AC-10：物料与元素BOM 组件的绑定区显示 4 个下拉，且元素列/元素单价列已回填', async ({
  page,
}) => {
  const db = readBinds(matElem.id);
  // 前置：库里必须真有元素绑定值，否则 AC-10③ 验的是空样本（testing.md §5.5 形态③）
  expect(db.elem, `[前置] ${matElem.code} 的 element_code_field 在库里为空 ⇒ AC-10③ 会验空样本，属零证据`).not.toBe('');
  expect(db.price, `[前置] ${matElem.code} 的 element_price_field 在库里为空 ⇒ 同上`).not.toBe('');
  console.log(`[AC-10] 库中绑定值 = ${JSON.stringify(db)}`);

  await openComponent(page, matElem);
  await shot(page, 'ac10-material-element');
  const bar = await readBindingBar(page);
  console.log(`[AC-10] ${matElem.code} 下拉 = ${JSON.stringify(bar.slots)}`);

  if (!BASELINE) {
    // ① 恰好 4 个下拉（AC 原文：料号列 / 名称列 / 元素列 / 元素单价列）
    expect(
      bar.slots.length,
      `AC-10：物料与元素BOM 源的绑定区应显示 4 个下拉，实得 ${bar.slots.length} 个：${JSON.stringify(bar.slots)}`
    ).toBe(4);
  } else {
    expect(
      bar.slots.length,
      `[基线模式] 改动前应是 5 个下拉（含货币列），实得 ${JSON.stringify(bar.slots)}`
    ).toBe(5);
  }

  // ② 料号列 / 名称列 两个槽在（有值显示值、无值显示占位符）
  expect(bar.slots, `AC-10：应有料号列槽（显示「${expectedSlot(db.partNo, F_PART_NO)}」）`).toContain(
    expectedSlot(db.partNo, F_PART_NO)
  );
  expect(bar.slots, `AC-10：应有名称列槽（显示「${expectedSlot(db.partName, F_PART_NAME)}」）`).toContain(
    expectedSlot(db.partName, F_PART_NAME)
  );

  // ③ 元素列回填「元素」、元素单价列回填「元素单价」（AC 原文逐字）
  expect(bar.slots, `AC-10：元素列应回填「${db.elem}」，实得下拉 ${JSON.stringify(bar.slots)}`).toContain(db.elem);
  expect(bar.slots, `AC-10：元素单价列应回填「${db.price}」`).toContain(db.price);
  expect(db.elem, 'AC-10：元素列的回填值按 AC 原文应为「元素」').toBe('元素');
  expect(db.price, 'AC-10：元素单价列的回填值按 AC 原文应为「元素单价」').toBe('元素单价');

  // ④ 货币列即便在本源下也不出现（AC-12「任何数据源」含本源）
  if (!BASELINE) {
    expect(fieldShown(bar, F_CURRENCY), `AC-12：物料与元素BOM 源下也不应出现「${F_CURRENCY}」`).toBe(false);
  } else {
    expect(fieldShown(bar, F_CURRENCY), `[基线模式] 改动前「${F_CURRENCY}」应当仍在`).toBe(true);
  }
});

// ───────────────────────────────────────────────────────────────────────────
// T-S3-11 · AC-11（两路各一条，🚫 不许只验一路就推断另一路）
// ───────────────────────────────────────────────────────────────────────────
for (const which of ['BOM', 'FEE'] as const) {
  test(`T-S3-11${which === 'BOM' ? 'a' : 'b'} · AC-11：${which === 'BOM' ? 'BOM 源' : '费用类源'}的绑定区只显示料号列 + 名称列`, async ({
    page,
  }) => {
    const f = which === 'BOM' ? bomSrc : feeSrc;
    const db = readBinds(f.id);
    // 前置：这两个组件必须**没有**元素绑定值 —— 有值的话「不显示元素列」本就该被 AC-13 的
    // 「有值就显示」规则覆盖，两条 AC 会打架（testing.md §5.5：先证明判据落在取值 > 1 的维度上）
    expect(db.elem, `[前置] ${f.code} 的 element_code_field 非空（${db.elem}）⇒ 本条与 AC-13 冲突，先报主线`).toBe('');
    console.log(`[AC-11/${which}] ${f.code}「${f.name}」coord=${f.coord} 库中绑定值 = ${JSON.stringify(db)}`);

    await openComponent(page, f);
    await shot(page, `ac11-${f.code}`);
    const bar = await readBindingBar(page);
    console.log(`[AC-11/${which}] 下拉 = ${JSON.stringify(bar.slots)}`);

    // 非空守卫：读到 0 个下拉时，下面的缺席断言会恒真
    expect(bar.slots.length, `[AC-11/${which}] 绑定区读到 0 个下拉 ⇒ 缺席断言恒真，属零证据`).toBeGreaterThan(0);

    // 正向：料号列 / 名称列 两个槽都在
    expect(bar.slots, `AC-11：应有料号列槽（显示「${expectedSlot(db.partNo, F_PART_NO)}」）`).toContain(
      expectedSlot(db.partNo, F_PART_NO)
    );
    expect(bar.slots, `AC-11：应有名称列槽（显示「${expectedSlot(db.partName, F_PART_NAME)}」）`).toContain(
      expectedSlot(db.partName, F_PART_NAME)
    );

    if (!BASELINE) {
      // 反向：🚫 页面上不出现「元素列」「元素单价列」字样（该组件无值 ⇒ 若渲染则占位符就是字段名）
      expect(fieldShown(bar, F_ELEM), `AC-11：${f.code} 不应出现「${F_ELEM}」，实得 ${JSON.stringify(bar.slots)}`).toBe(
        false
      );
      expect(fieldShown(bar, F_ELEM_PRICE), `AC-11：${f.code} 不应出现「${F_ELEM_PRICE}」`).toBe(false);
      // 「只显示 2 个下拉」（AC 原文逐字）
      expect(
        bar.slots.length,
        `AC-11：绑定区应恰好只有 2 个下拉（料号列 / 名称列），实得 ${JSON.stringify(bar.slots)}`
      ).toBe(2);
    } else {
      expect(fieldShown(bar, F_ELEM), `[基线模式] 改动前 ${f.code} 应当仍显示「${F_ELEM}」`).toBe(true);
      expect(bar.slots.length, `[基线模式] 改动前应是 5 个下拉，实得 ${JSON.stringify(bar.slots)}`).toBe(5);
    }
  });
}

// ───────────────────────────────────────────────────────────────────────────
// T-S3-12a · AC-12 正向：遍历两种组件，页面上都不出现「货币列」
// ───────────────────────────────────────────────────────────────────────────
test('T-S3-12a · AC-12：遍历物料与元素BOM 源与其余源，绑定区均不出现「货币列」字样', async ({ page }) => {
  const seen: Record<string, string[]> = {};
  for (const f of [matElem, bomSrc, feeSrc]) {
    const db = readBinds(f.id);
    // 前置：这三个组件的 element_currency_field 必须为空 —— 否则货币列若渲染会显示"货币"这个**值**
    // 而不是"货币列"这个占位符，按名字判会漏检（判据必须先在已知答案上给得出正确结果）
    expect(
      db.currency,
      `[前置] ${f.code} 的 element_currency_field 非空（${db.currency}）⇒ 本条的按名判据会漏检，先报主线`
    ).toBe('');

    await openComponent(page, f);
    const bar = await readBindingBar(page);
    seen[f.code] = bar.slots;
    expect(bar.slots.length, `[AC-12] ${f.code} 读到 0 个下拉 ⇒ 缺席断言恒真，属零证据`).toBeGreaterThan(0);
    await shot(page, `ac12-${f.code}`);

    if (!BASELINE) {
      expect(
        fieldShown(bar, F_CURRENCY),
        `AC-12：${f.code}「${f.name}」的绑定区不应出现「${F_CURRENCY}」，实得 ${JSON.stringify(bar.slots)}`
      ).toBe(false);
    } else {
      expect(fieldShown(bar, F_CURRENCY), `[基线模式] 改动前 ${f.code} 应当仍显示「${F_CURRENCY}」`).toBe(true);
    }
  }
  console.log('[AC-12] 各组件绑定区下拉 =', JSON.stringify(seen));
});

// ───────────────────────────────────────────────────────────────────────────
// T-S3-12b · AC-12 反向断言（D-4：只从 UI 拿掉下拉，后端数据一行不动）
//   🚫 三条断言都是**点名单行 / 点名单列**，不是计数 —— 见 AC 原文的 ⚠️ 注。
// ───────────────────────────────────────────────────────────────────────────
test('T-S3-12b · AC-12 反向：element_currency_field 列仍在、DTO 仍返回、点名组件的值未被清空', async ({
  page,
}) => {
  // ① 列仍在（点名列，不是"共 N 列"）
  const colExists = roSql(
    `select count(*) from information_schema.columns ` +
      `where table_name='component' and column_name='element_currency_field'`
  );
  expect(colExists, 'AC-12 反向①：component.element_currency_field 列应仍存在（D-4：不做 DDL 删除）').toBe('1');

  // ② 点名单行的值未被清空 —— AC 原文给的就是这条 SQL
  const val = roSql(
    `select coalesce(element_currency_field,'(null)') from component where id='${CURRENCY_COMPONENT_ID}'`
  );
  expect(
    val,
    `AC-12 反向②：点名组件 ${CURRENCY_COMPONENT_ID}（T260907-物料与元素BOM）的 element_currency_field ` +
      `应仍为「${CURRENCY_EXPECTED}」，实得「${val}」`
  ).toBe(CURRENCY_EXPECTED);

  // ③ ComponentDTO 仍返回该字段（key 存在 且 值未变）
  //    🚩 用 hasOwnProperty 区分「key 被删」与「key 在但值为 null」—— 两者后果不同
  const resp = await page.request.get(
    `${BACKEND_URL}/api/cpq/components?keyword=${encodeURIComponent('COMP-2180')}`
  );
  expect(resp.status(), 'AC-12 反向③：组件查询接口应 200').toBe(200);
  const body = await resp.json();
  const list: any[] = body.data ?? body ?? [];
  const dto = list.find((c) => c.id === CURRENCY_COMPONENT_ID);
  expect(dto, `AC-12 反向③：接口应能返回点名组件 ${CURRENCY_COMPONENT_ID}（按 code COMP-2180 搜）`).toBeTruthy();
  expect(
    Object.prototype.hasOwnProperty.call(dto, 'elementCurrencyField'),
    'AC-12 反向③：ComponentDTO 仍应带 elementCurrencyField 这个 key（D-4：后端读写路径一行不动）'
  ).toBe(true);
  expect(
    dto.elementCurrencyField,
    `AC-12 反向③：DTO 的 elementCurrencyField 应仍为「${CURRENCY_EXPECTED}」`
  ).toBe(CURRENCY_EXPECTED);

  console.log(`[AC-12 反向] 列存在=${colExists} · 库值=「${val}」· DTO 值=「${dto.elementCurrencyField}」`);
});
