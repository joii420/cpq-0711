/**
 * E2E · task-260908「取数配置器优化」· 分片 S3 之二：**BOM 树表头改「BOM」**（AC-20 / AC-21 / AC-22）
 *
 * ════════════════════════════════════════════════════════════════════════════
 * AC 原文（`dev-docs/task-260908-取数配置器优化/需求文档.md §③`，逐字抄录，🚫 不得改写）
 * ────────────────────────────────────────────────────────────────────────────
 * AC-20 · 报价单**编辑页** Step2 → 打开含 BOM 树页签的产品卡片
 *         → 树结构列表头文案为 **「BOM」**（原「料号」），列内仍显示带展开箭头的料号值。
 * AC-21 · 报价单**详情页**（只读）→ 同上文案为 **「BOM」**。
 *   🚨 两条必须同时通过 —— `ReadonlyProductCard.tsx` 的既有注释记录过一次
 *      「只改编辑页、漏改只读页」的事故（AP-50 同族）。
 * AC-22 · 核价单视图下打开含 BOM 树的卡片 → 树结构列表头同为 **「BOM」**，
 *         其右侧的**「版本」列仍在**（`cardSide === 'COSTING'` 分支未被误删）。
 *
 * 🚫 F-4 不出原型（`frontend.md §1.3` 把纯文案改动列在不触发清单里）——
 *    本组 AC 由**截图证据**约束，不由原型约束。
 * ════════════════════════════════════════════════════════════════════════════
 *
 * 🚨 三条不许合并的纪律（本 spec 存在的理由）
 *   1. **AC-20 与 AC-21 是两条独立用例**，编辑页与详情页各跑各的。
 *      🚫 不许「编辑页绿了就推断详情页也绿」—— 那正是 AP-50 事故的形态。
 *   2. **AC-22 的反向断言不许丢**：改了树表头文案的同时，其右侧的「版本」列必须仍在。
 *      只验文案 = 验了一半，`cardSide === 'COSTING'` 分支被误删这件事看不出来。
 *   3. 表头文案对了但树没了（无展开箭头 / 首列空）同样是失败 ——
 *      AC-20 原文写明「列内仍显示带展开箭头的料号值」。
 *
 * 🧩 分片纪律（`docs/rules/testing.md` §4.5）
 *   - 本片写入面 = **只读**：只打开既有报价单看表头，🚫 不改单、不提交、不点保存。
 *   - 🚫 无全局计数断言；fixture 解析显式排除 `T260908-` 前缀（别片的造数）。
 *   - psql 只走 `roSql()`（硬拒非 SELECT）。
 *
 * 📸 证据归档：`dev-docs/task-260908-取数配置器优化/证据/e2e-s3/`，🚫 不写 test-results/。
 *
 * 🔬 量具自检（testing.md §4.4）
 *   `PW_T260908_BASELINE=1` = 改动前基线模式：断言表头仍是「料号」。
 *   ⇒ 未改动时基线模式必须**全绿**、正向模式必须**全红**；F-4 落地后两者对调。
 *     两个方向都对上，才证明断言真的挂在「表头文案」这个维度上。
 *
 * 运行：
 *   npx playwright test --config=e2e/playwright.config.ts e2e/task260908-s3-tree-header.spec.ts --reporter=list
 *   可用环境变量指定夹具（fixture 漂移时由主线提供）：
 *     PW_T260908_QUOTE_QID / PW_T260908_QUOTE_TAB
 *     PW_T260908_COSTING_QID / PW_T260908_COSTING_TAB
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { execSync } from 'child_process';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';

const __filenameLocal = fileURLToPath(import.meta.url);
const BASELINE = process.env.PW_T260908_BASELINE === '1';
/** 改动后的期望文案 / 改动前的基线文案 */
const TREE_HEADER_EXPECTED = BASELINE ? '料号' : 'BOM';
const VERSION_HEADER = '版本';

const SHOT_DIR = path.resolve(
  path.dirname(__filenameLocal),
  '../../dev-docs/task-260908-取数配置器优化/证据/e2e-s3'
);
fs.mkdirSync(SHOT_DIR, { recursive: true });

// ═══════════════════════════════════════════════════════════════════════════
// 只读 SQL 守卫（同 task260908-s3-binding-bar.spec.ts）
// ═══════════════════════════════════════════════════════════════════════════
function roSql(sql: string): string {
  if (sql.trim().slice(0, 6).toLowerCase() !== 'select') {
    throw new Error(
      `[S3 只读守卫] 本片只允许 SELECT，收到：${sql.slice(0, 80)}…\n` +
        '  需要写库说明分片分错了 —— 停下来报主线，🚫 不要绕过本守卫。'
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

type TreeFixture = { id: string; name: string; status: string; tab: string };

/**
 * 报价侧夹具：一张**其模板含 BOM 树组件、且明细里确实有树节点**的报价单。
 *
 * 🚨 为什么必须带 `nodeId` 这个条件：没有树节点时页签会退化成普通表格，
 *    首列压根不是树结构列 ⇒ 断言「表头=BOM」验的是另一个东西（testing.md §5.5 形态③）。
 *
 * 🚫 排除 `T260908-` 前缀 —— 那是 S2 / S-全局 正在造的数据，跨片串扰源。
 *
 * 🚨 2026-09-09 改判据：原来按 `created_at ASC`（「最老 = 最稳」）选，
 *    结果选中的恰恰是**只有根节点**的早期单 ⇒ 下面第②条断言「展开箭头 ≥ 1」**恒红**，
 *    而红的原因是**夹具选错**，看起来却像产品缺陷（亲验记录里 AC-20/21 那条「诚实标注」就是它）。
 *    ⇒ 改为要求 `parentId` 非空 + 按 `nodeId` 出现次数 DESC 取树最深的一张。
 *    📌 通则：夹具的选取判据必须包含「**这张夹具能让断言真的动起来**」——
 *       「合法样本」不够，只有根节点的单是完全合法的 BOM，但它验不动东西。
 *    次级排序用 `q.id ASC`（稳定、可复现），🚫 不用 created_at（最新那张常是在途单）。
 */
function resolveQuoteTreeFixture(): TreeFixture {
  const envId = process.env.PW_T260908_QUOTE_QID;
  if (envId) {
    const row = roSql(
      `select q.id::text, q.name, q.status, tc.tab_name from quotation q ` +
        `join template_component tc on tc.template_id=q.customer_template_id ` +
        `join component_sql_view v on v.component_id=tc.component_id and v.builder_config->>'tabType'='BOM' ` +
        `where q.id='${envId}' limit 1`
    );
    expect(row, `[fixture] 指定的 PW_T260908_QUOTE_QID=${envId} 不是一张含 BOM 树页签的报价单`).not.toBe('');
    const [id, name, status, tab] = row.split('|');
    return { id, name, status, tab: process.env.PW_T260908_QUOTE_TAB || tab };
  }

  const row = roSql(
    `select q.id::text, q.name, q.status, tc.tab_name from quotation q ` +
      `join template_component tc on tc.template_id=q.customer_template_id ` +
      `join component_sql_view v on v.component_id=tc.component_id and v.builder_config->>'tabType'='BOM' ` +
      `join quotation_line_item li on li.quotation_id=q.id ` +
      `where q.name not like 'T260908-%' and li.quote_card_values::text like '%nodeId%' ` +
      // 🚨 判「这张单的树有子节点」要用 **__parentNo 非空**，不是 __parentId。
      //    2026-09-09 实测：`__parentId` 在全库 1972 行 quote_card_values 里**全是 null**，
      //    拿它当判据要么恒空、要么（写错转义时）碰巧给对答案 —— 两种都不可信。
      //    `__parentNo` 干净分开：QT-20260907-0557=0（只有根节点）／0623、0627=8（真多层）。
      // 🚫 不要在这里写字面双引号：roSql 走 `psql -c "..."`，shell 先吃掉一层转义，
      //    实测把正则打成残句直接报 `Command failed` ——**是命令红不是断言红**，极易误读成环境挂了。
      //    ⇒ 一律用 chr(34) 拼。
      `and li.quote_card_values::text like '%__parentNo'||chr(34)||': '||chr(34)||'%' ` +
      `group by q.id, q.name, q.status, tc.tab_name ` +
      `order by (q.status='DRAFT') desc, ` +
      `max((select count(*) from regexp_matches(li.quote_card_values::text,'nodeId','g'))) desc, ` +
      `q.id asc limit 1`
  );
  expect(
    row,
    '[fixture] 全库找不到「模板含 BOM 树组件 + 明细带 nodeId」的报价单 ⇒ AC-20/21 没有可验样本。\n' +
      '  🚨 这是 **fixture 缺口，不是产品缺陷** —— 停下来报主线，或用 PW_T260908_QUOTE_QID 指定一张。'
  ).not.toBe('');
  const [id, name, status, tab] = row.split('|');
  console.log(`[fixture] 报价侧树夹具：${name}（${id}）status=${status} 页签=${tab}`);
  return { id, name, status, tab };
}

/**
 * 核价侧夹具。
 *
 * ⚠️ 2026-09-08 的那段注释（「全库 0/68 张带核价模板、AC-22 无样本」）**已于 2026-09-09 作废**，
 *    因为它背后的判断是错的：以为核价卡片需要独立的 COSTING 模板实体。实测
 *    `quotation_costing_card_template_fk → REFERENCES template(id)` 指向**普通 template 表**，
 *    且核价卡片编辑页只依赖 `costing_card_template_id` + 该模板的 componentsSnapshot，
 *    **不经过 quotation_view_structure**。⇒ 造夹具 = 一条 UPDATE。
 *
 * ✅ 夹具种子（幂等，带 --rollback）：
 *    `bash dev-docs/task-260908-取数配置器优化/夹具/seed-ac22-costing-card.sh`
 *
 * 🚨 但**必须显式指定 QID**：2026-09-09 实测没有一张单同时满足两侧 ——
 *    · `03e79d02`（报价模板·ds 原生）核价视图渲染成功，但 BOM 只有根节点（箭头断言会红）
 *    · `438f10f4`（正泰测试模板1）树有 24 节点，但核价侧 expand 硬失败：
 *      「树页签组件的 $view 未输出 parent_no 列」——**核价侧树的展开链路与报价侧不是同一套**，
 *      它按 (parent_no, material_no) 边键匹配。同一组件报价侧正常、核价侧失败。
 *    ⇒ 跑 AC-22 用 `PW_T260908_COSTING_QID=03e79d02-352b-4fcd-baa4-9aeb0b343008`。
 *
 * 仍然：找不到样本时**硬失败**并说明缺口 —— 🚫 不用 test.skip，跳过看起来和通过一模一样。
 */
function resolveCostingTreeFixture(): TreeFixture {
  const envId = process.env.PW_T260908_COSTING_QID;
  if (envId) {
    const row = roSql(
      `select q.id::text, q.name, q.status, coalesce(tc.tab_name,'') from quotation q ` +
        `left join template_component tc on tc.template_id=q.costing_card_template_id ` +
        `left join component_sql_view v on v.component_id=tc.component_id and v.builder_config->>'tabType'='BOM' ` +
        `where q.id='${envId}' order by (v.id is not null) desc limit 1`
    );
    expect(row, `[fixture] 指定的 PW_T260908_COSTING_QID=${envId} 在库里不存在`).not.toBe('');
    const [id, name, status, tab] = row.split('|');
    return { id, name, status, tab: process.env.PW_T260908_COSTING_TAB || tab };
  }

  const row = roSql(
    `select q.id::text, q.name, q.status, tc.tab_name from quotation q ` +
      `join template_component tc on tc.template_id=q.costing_card_template_id ` +
      `join component_sql_view v on v.component_id=tc.component_id and v.builder_config->>'tabType'='BOM' ` +
      `where q.name not like 'T260908-%' ` +
      `order by q.created_at asc limit 1`
  );
  const costingCount = roSql(
    `select count(*) from quotation where costing_card_template_id is not null`
  );
  expect(
    row,
    '[fixture] 🚨 AC-22 无样本：全库找不到「绑了核价卡片模板且其中含 BOM 树组件」的报价单' +
      `（带核价模板的单共 ${costingCount} 张）。\n` +
      '  这是 **fixture/环境缺口，不是产品缺陷**（2026-09-08 实测 quotation_view_structure 无 COSTING_CARD 行）。\n' +
      '  ⇒ 停下来报主线：需要一张含核价 BOM 树的单，或用 PW_T260908_COSTING_QID 指定。\n' +
      '  🚫 不许把本条改成 skip —— 未验证必须以红色呈现，否则报告里它长得和通过一样。'
  ).not.toBe('');
  const [id, name, status, tab] = row.split('|');
  console.log(`[fixture] 核价侧树夹具：${name}（${id}）status=${status} 页签=${tab}`);
  return { id, name, status, tab };
}

// ═══════════════════════════════════════════════════════════════════════════
// 页面操作
// 🚩 选择器口径全部来自既有 spec 的实测（quotation-bom-tree / costing-bom-tree），不是猜的：
//    - 卡片页签 = `button.qt-tab-btn`（🚫 不是 `.ant-tabs-tab`）
//    - 卡片表格 = `.qt-cost-table`（编辑页 / 详情页 / 核价页共用）
//    - 树展开箭头 = `.qt-cost-table tbody button` 且文本含 ▼ / ▶
//    - 视图切换 = `.ant-segmented-item`（报价单 / 核价单 · 产品卡片）
//    - antd 给两字按钮插空格 ⇒ 文本匹配一律用 /下\s*一\s*步/ 这类正则
// ═══════════════════════════════════════════════════════════════════════════

let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(SHOT_DIR, `s3-tree-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`📸 ${name} → ${file}`);
}

/** 当前可见的那张卡片表格（页签切换后只应有一张可见；多张时取第一张并打日志） */
async function visibleCardTable(page: Page) {
  const all = page.locator('.qt-cost-table');
  const n = await all.count();
  let visibleIdx = -1;
  let visibleCount = 0;
  for (let i = 0; i < n; i++) {
    if (await all.nth(i).isVisible().catch(() => false)) {
      visibleCount++;
      if (visibleIdx < 0) visibleIdx = i;
    }
  }
  console.log(`[tree] .qt-cost-table 共 ${n} 张，可见 ${visibleCount} 张，取第 ${visibleIdx}`);
  expect(
    visibleIdx,
    '[harness] 页面上没有可见的卡片表格 `.qt-cost-table` —— 页签没切到位或卡片没渲染，' +
      '🚫 不要据此判定表头文案 AC 失败'
  ).toBeGreaterThanOrEqual(0);
  return all.nth(visibleIdx);
}

async function headersOf(table: ReturnType<Page['locator']>): Promise<string[]> {
  const ths = table.locator('thead th');
  const n = await ths.count();
  const out: string[] = [];
  for (let i = 0; i < n; i++) out.push(((await ths.nth(i).innerText().catch(() => '')) || '').trim());
  return out;
}

async function firstColTexts(table: ReturnType<Page['locator']>): Promise<string[]> {
  const cells = table.locator('tbody tr td:first-child');
  const n = await cells.count();
  const out: string[] = [];
  for (let i = 0; i < n; i++) out.push(((await cells.nth(i).innerText().catch(() => '')) || '').trim());
  return out;
}

/** 走到编辑页 Step2 的产品卡片视图；side 决定进报价单还是核价单 */
async function gotoCardView(page: Page, qid: string, side: 'QUOTE' | 'COSTING') {
  await page.goto(`/quotations/${qid}/edit`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(1500);
  // 编辑页落在 Step1「选择客户」，步骤条不可点，必须点「下一步」（t260907-helpers.ts 实测）
  const next = page.locator('button').filter({ hasText: /下\s*一\s*步|继\s*续/ }).first();
  if ((await next.count()) > 0 && (await next.isEnabled().catch(() => false))) {
    await next.click().catch(() => {});
    await page.waitForTimeout(3000);
  }
  const seg = page
    .locator('.ant-segmented-item', { hasText: side === 'QUOTE' ? '报价单' : '核价单' })
    .first();
  expect(
    await seg.count(),
    `[harness] 找不到「${side === 'QUOTE' ? '报价单' : '核价单'}」视图切换项 —— ` +
      (side === 'COSTING'
        ? '该单可能根本没绑核价卡片模板（本库 2026-09-08 实测 0 张单有核价模板）'
        : '页面没到 Step2')
  ).toBeGreaterThan(0);
  await seg.click().catch(() => {});
  await page.waitForTimeout(1200);
  const cardSeg = page.locator('.ant-segmented-item', { hasText: '产品卡片' }).first();
  if ((await cardSeg.count()) > 0) {
    await cardSeg.click().catch(() => {});
    await page.waitForTimeout(1200);
  }
  await page.waitForTimeout(2000);
}

/** 点开指定的 BOM 树页签 */
async function openTreeTab(page: Page, tab: string) {
  const btn = page.locator('button.qt-tab-btn', { hasText: tab }).first();
  expect(
    await btn.count(),
    `[harness] 找不到页签「${tab}」（button.qt-tab-btn）—— 夹具与实际卡片结构对不上，` +
      '这是 fixture 问题不是表头文案缺陷'
  ).toBeGreaterThan(0);
  await btn.click().catch(() => {});
  await page.waitForTimeout(2000);
}

/**
 * 三条共用的断言：树结构列表头文案 + 「仍是一棵树」的证据。
 * @returns headers，供调用方继续做各自的追加断言（AC-22 的版本列）
 */
async function assertTreeHeader(page: Page, who: string): Promise<string[]> {
  const table = await visibleCardTable(page);
  const headers = await headersOf(table);
  console.log(`[${who}] 表头 = ${JSON.stringify(headers)}`);
  expect(headers.length, `[${who}] 表头为空 ⇒ 后面所有断言都会空跑（零证据）`).toBeGreaterThan(0);

  // ① 表头文案（AC-20/21/22 的核心断言）
  expect(
    headers[0],
    `[${who}] 树结构列（第 1 列）表头文案应为「${TREE_HEADER_EXPECTED}」，实得「${headers[0]}」。` +
      (BASELINE ? '（基线模式：验的是改动**前**的形态）' : '（AC-20/21/22）')
  ).toBe(TREE_HEADER_EXPECTED);

  // ② 「列内仍显示带展开箭头的料号值」—— 只改文案，树不许坏（AC-20 原文）
  const carets = table.locator('tbody button').filter({ hasText: /[▼▶]/ });
  const caretCount = await carets.count();
  console.log(`[${who}] 展开/折叠箭头数 = ${caretCount}`);
  expect(
    caretCount,
    `[${who}] 树结构列里应仍有展开/折叠箭头 —— 一个都没有说明它已不是树列，` +
      '此时"表头文案对了"没有意义（AC-20 原文：列内仍显示带展开箭头的料号值）'
  ).toBeGreaterThanOrEqual(1);

  // ③ 首列有真实料号值（非空、非占位）—— 防「表头对了但整列空」的假绿
  const col0 = await firstColTexts(table);
  const meaningful = col0.filter((t) => t && t !== '—' && t !== '-' && !t.includes('加载中'));
  console.log(`[${who}] 首列前 5 个值 = ${JSON.stringify(col0.slice(0, 5))}（有效 ${meaningful.length}/${col0.length}）`);
  expect(
    meaningful.length,
    `[${who}] 树结构列没有任何有效料号值（全空/全「—」/「加载中」）⇒ 夹具或渲染有问题，` +
      '此时不能判定 AC 通过（testing.md §4.5-d：空列表 / 0 行 / "—" 一律不算通过）'
  ).toBeGreaterThanOrEqual(1);

  // ④ 无「加载中…」残留（AP-31 / AP-38 族）
  const loading = await table.locator('text=加载中').count();
  expect(loading, `[${who}] 表内残留「加载中」${loading} 处（AP-31/AP-38 族）`).toBe(0);

  return headers;
}

// ═══════════════════════════════════════════════════════════════════════════

let backendUp = false;
let quoteFx: TreeFixture;

test.beforeAll(async () => {
  backendUp = await isBackendUp();
});

test.beforeEach(async ({ page }) => {
  test.skip(!backendUp, '后端未启动 —— harness 前置未满足。🚨 报告里必须记「未验证」，🚫 不许算通过');
  await loginAsAdmin(page);
  expect(page.url(), '登录后不应停在 /login').not.toContain('/login');
});

// ───────────────────────────────────────────────────────────────────────────
// T-S3-20 · AC-20（编辑页）
// ───────────────────────────────────────────────────────────────────────────
test('T-S3-20 · AC-20：报价单**编辑页** Step2 的 BOM 树页签，树结构列表头为「BOM」', async ({ page }) => {
  quoteFx = quoteFx || resolveQuoteTreeFixture();
  console.log(`[AC-20] 夹具：${quoteFx.name}（${quoteFx.id}）页签「${quoteFx.tab}」`);

  await gotoCardView(page, quoteFx.id, 'QUOTE');
  await openTreeTab(page, quoteFx.tab);
  await shot(page, 'ac20-edit-tree');

  const headers = await assertTreeHeader(page, 'AC-20 编辑页');
  // 报价侧历来无「版本」列（quotation-bom-tree.spec.ts 2026-07-22 裁决），此处只记录不断言 ——
  // AC-20 没有对版本列提要求，多断言一条就是超出 AC 范围。
  console.log(`[AC-20] 报价侧表头是否含「版本」= ${headers.includes(VERSION_HEADER)}（仅记录，非 AC 断言）`);
});

// ───────────────────────────────────────────────────────────────────────────
// T-S3-21 · AC-21（详情页 · 只读）
//   🚨 独立用例。🚫 不许因为 T-S3-20 绿了就跳过本条 —— AP-50 事故的形态正是"只改编辑页"。
// ───────────────────────────────────────────────────────────────────────────
test('T-S3-21 · AC-21：报价单**详情页（只读）**的 BOM 树页签，树结构列表头同为「BOM」', async ({ page }) => {
  quoteFx = quoteFx || resolveQuoteTreeFixture();
  console.log(`[AC-21] 夹具：${quoteFx.name}（${quoteFx.id}）页签「${quoteFx.tab}」`);

  // 详情页默认 mainTab='quote' + viewType='card'，直接渲染只读卡片（quotation-bom-tree.spec.ts:200 实测）
  await page.goto(`/quotations/${quoteFx.id}`);
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2500);
  await openTreeTab(page, quoteFx.tab);
  await shot(page, 'ac21-detail-tree');

  await assertTreeHeader(page, 'AC-21 详情页');

  // 🔑 只读页真的是只读页 —— 防止"详情页其实渲染的是编辑组件"导致本条与 AC-20 验的是同一份代码
  const editable = await page.locator('.qt-cost-table tbody input:not([disabled])').count();
  console.log(`[AC-21] 详情页表内可编辑 input 数 = ${editable}（只读页应为 0；非 AC 断言，仅作同源性佐证）`);
});

// ───────────────────────────────────────────────────────────────────────────
// T-S3-22 · AC-22（核价侧）+ 🚨 不许丢的反向断言：「版本」列仍在
// ───────────────────────────────────────────────────────────────────────────
test('T-S3-22 · AC-22：核价单视图的 BOM 树表头为「BOM」，且其右侧的「版本」列仍在', async ({ page }) => {
  const fx = resolveCostingTreeFixture();
  console.log(`[AC-22] 夹具：${fx.name}（${fx.id}）页签「${fx.tab}」`);

  await gotoCardView(page, fx.id, 'COSTING');
  await openTreeTab(page, fx.tab);
  await shot(page, 'ac22-costing-tree');

  const headers = await assertTreeHeader(page, 'AC-22 核价侧');

  // 🚨 反向断言（丢了这条就只验了一半）：cardSide === 'COSTING' 分支未被误删
  const verIdx = headers.indexOf(VERSION_HEADER);
  expect(
    verIdx,
    `AC-22 反向：核价侧表头应仍含「${VERSION_HEADER}」列，实得表头 ${JSON.stringify(headers)}。` +
      '  ⚠️ 它消失 = 改树表头文案时把 cardSide==="COSTING" 分支一起删了 —— ' +
      '这条不许省，只验文案会漏掉它'
  ).toBeGreaterThanOrEqual(0);
  expect(
    verIdx,
    `AC-22 反向：「${VERSION_HEADER}」列应在树结构列**右侧**（索引 > 0），实得索引 ${verIdx}`
  ).toBeGreaterThan(0);

  console.log(`[AC-22] 树列=「${headers[0]}」，版本列位于索引 ${verIdx}（共 ${headers.length} 列）`);
});
