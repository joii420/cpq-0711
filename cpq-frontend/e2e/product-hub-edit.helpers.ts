/**
 * task-260903「产品维护能力增强」（子任务）E2E 公共件。
 *
 * 🚫 **本文件与配套 spec 全部从 `需求文档.md` ④ 的 AC-1~AC-15 原文派生**，
 *    全程未打开 `cpq-frontend/src/pages/product/` 与 `com.cpq.product.dataset`
 *    的任何实现文件。从实现派生的测试只能证明「代码按实现者的理解工作」，
 *    证明不了「功能符合需求」。
 *
 * ── 与父任务 `product-hub.helpers.ts` 的关系 ──
 *   父任务那套是**纯只读**的（`assertNoWrite` 证明「一个字节都没改」）。
 *   本子任务**首次引入写操作**（S-1 开放 `production_no` 单列编辑），所以纪律换了一条：
 *
 *     父任务：证明「什么都没写」          本子任务：证明「写了，但跑完一个残留都没留下」
 *
 *   ⇒ 本文件复用父任务的 `snapshotDataState()` / `assertNoWrite()`，
 *     但用途从「只读证明」改成「**无残留证明**」：
 *     开跑前快照 16 张表 → 整套跑完（含所有写用例的自复原）→ 指纹必须逐字节相同。
 *     只要有一个用例忘了还原，`afterAll` 就硬失败。
 *
 * ── 三条写纪律（主线 2026-09-03 派工时点名，不许自行放宽）──
 *   1. 每个写用例**自己复原**：改前记原值，断言完改回去。🚫 不留残留。
 *   2. **只碰测试专用料号** `S-1630010773`（`HERO_EDIT`）。🚫 不许换成别的料号。
 *   3. 🚫 严禁 `TRUNCATE` / 无 `WHERE` 的 `DELETE` / 任何反向条件（`WHERE ... NOT LIKE ...`）。
 *      ⇒ 唯一的写库入口是 `setProductionNoInDb()`（2026-09-04 已**下沉到共享件**
 *        `product-hub.helpers.ts`，本文件只 re-export）：SQL 模板写死、
 *        `WHERE` 恒为 `material_no = 'S-1630010773'`，且**执行前先数命中行数，≠1 就拒绝执行**。
 */
import { expect, Page, Locator } from '@playwright/test';
import { execFileSync } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';

// 复用父任务已验证过的件（登录 / 导航 / 搜索 / 只读断言 / 只读 SQL / 环境守卫）
export {
  assertIsolatedEnv, snapshotDataState, assertNoWrite, sql, sqlOne, tableExists,
  loginAs, gotoProductHub, switchTab, headerTexts, totalCount, rowCount,
  search, clearSearch, openDrawer, closeDrawer, drawerTabTexts, clickDrawerTab,
  assertReadOnly, assertReadOnlyProbeWorks, collectConsoleErrors, credOf,
  BASE_URL, BACKEND_URL, DB_NAME, HERO, MATERIAL_COLUMNS, CUSTOMER_PART_COLUMNS,
  DS_QUOTE_TABLES, N_MATERIAL, N_CUSTOMER_PART,
  // ↓ 2026-09-04 下沉到共享件：DB 同一性守卫及其依赖。
  //   下沉理由：父任务 product-hub-readonly.spec.ts 有**同一个洞** ——
  //   它一旦跑在错的库上，「16 张表指纹全未变」这条只读证明会给出**假的干净结论**，
  //   而那恰恰是父任务最强的一条证据。**留着洞的回归，不如没有回归。**
  //   下沉而不是让父任务 import 子任务的件 ⇒ 依赖方向是正的：共享件是底座，两套 spec 都往上依赖。
  HERO_EDIT, readProductionNo, readColumn, restoreProductionNo,
  anonymousApi, apiAs, assertSameDatabase,
  type DataState, type RoleKey,
} from './product-hub.helpers';

import {
  sql, sqlOne, totalCount, rowCount, search, BACKEND_URL, credOf, type RoleKey,
  HERO_EDIT, readProductionNo, readColumn, restoreProductionNo,
} from './product-hub.helpers';

const __f = fileURLToPath(import.meta.url);

/** 证据归档目录 —— **子任务自己的** `证据/`，不与父任务混。 */
export const EVIDENCE_DIR = path.resolve(
  path.dirname(__f),
  '../../dev-docs/task-260903-产品管理页重做/task-260903-产品维护能力增强/证据'
);

// ─────────────────────────── AC 常量（来自 需求文档.md ④，🚫 禁止就地改数） ───────────────────────────


/** AC-7 的抽屉主角（只读反向断言用，不写）。 */
export const HERO_DRAWER = 'S-3120014539';

/** AC-1 / AC-4：过滤器默认文案。 */
export const ALL_CUSTOMERS_LABEL = '所有客户';

/** AC-2 / AC-3：过滤用客户。 */
export const FILTER_CUSTOMER = 'CUST-0004';

/** AC-5：数据基线里出现过的 5 个客户（`需求文档.md` ④ 数据基线）。 */
export const AC_EXPECTED_CUSTOMERS = ['CUST-0001', 'CUST-0002', 'CUST-0004', 'Q13CUST0617', 'C1'];

/** AC-5 的两个「未在 `customer` 表建档」的客户 —— 本条 AC 的全部意义所在。 */
export const AC_UNREGISTERED_CUSTOMERS = ['Q13CUST0617', 'C1'];

/** AC-1 / AC-4 基线：客户产品总行数。 */
export const AC_N_CUSTOMER_PART = 17;
/** AC-2 / AC-3 基线：`CUST-0004` 的行数。 */
export const AC_N_CUST0004 = 11;

/** AC-3：叠加搜索用的销售料号（实测唯一，且属于 `CUST-0004`）。 */
export const AC_SEARCH_KEY = '0028-2609000001';

/** AC-10 / AC-9 的目标值。 */
export const NEW_PRODUCTION_NO = 'TEST-PROD-001';

/** AC-13：`ds_quote_material.production_no` 是 `varchar(128)`（2026-09-03 实读 `information_schema`）。 */
export const PRODUCTION_NO_MAXLEN = 128;
/** AC-13 的超长输入（129 字符，刚好越界 1 位 —— 比灌 1000 字符更能抓到 off-by-one）。 */
export const OVERLONG_VALUE = 'X'.repeat(PRODUCTION_NO_MAXLEN + 1);

/** AC-14：制造空结果用的关键词（AC 原文点名）。 */
export const NONEXISTENT_KEYWORD = '不存在的料号XYZ';

/** AC-11：不在白名单内的字段（AC 原文点名）。 */
export const NON_WHITELIST_FIELD = { materialName: '改名试试' };

// ─────────────────────────── 证据归档（写子任务目录，不写 test-results/） ───────────────────────────

let shotIdx = 0;
/**
 * 截图 → 子任务 `证据/`。
 * 🚨 **不写 `test-results/`** —— 那个目录下一轮开跑会被清空，等于没证据（`testing.md` 证据形式判据）。
 */
export async function shot(page: Page, name: string, opts: { fullPage?: boolean } = {}) {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const file = path.join(EVIDENCE_DIR, `EDIT-${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: opts.fullPage ?? false });
  console.log(`📸 证据 → ${file}`);
  return file;
}

/** 文本证据（SQL 输出、接口原文、实际列名数组）→ 子任务 `证据/`。 */
export function evidence(name: string, content: string) {
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  const file = path.join(EVIDENCE_DIR, `EDIT-${name}.txt`);
  fs.writeFileSync(file, content, 'utf-8');
  console.log(`🧾 证据 → ${file}`);
  return file;
}

// ─────────────────────────── 前置数据守卫 ───────────────────────────

/**
 * 🚨 本套用例的前置守卫（`testing.md §3` 假绿红线）。
 *
 * 三件事分开报，别混成一句「前置数据有问题」——
 * 三者的处置完全不同（等对方建表 / 等灌样例 / 等窗口协调）。
 */
export function assertEditFixtureReady(): { productionNo: string | null; source: string | null } {
  const n = Number(sqlOne(`SELECT count(*) FROM ds_quote_material WHERE material_no = '${HERO_EDIT}'`));
  if (n !== 1) {
    throw new Error(
      `🚨 停下报告：测试专用料号 ${HERO_EDIT} 在 ds_quote_material 里有 ${n} 行（期望恰好 1 行）。\n` +
      `  0 行 ⇒ 样例未导入，所有写断言会空跑（那是假绿不是通过）；\n` +
      `  >1 行 ⇒ 主键语义被破坏，restoreProductionNo 的单行还原守卫会拒绝执行。\n` +
      `  ⇒ 这是**测试环境问题**，🚫 不得改用别的料号把自己糊绿（test.md §1 纪律 2）。`
    );
  }
  // 🚩 用 `<NULL>` / `<VAL>` 哨兵读，**不用 `coalesce('')`** ——
  //    coalesce 会把「NULL」和「空字符串」压成同一个值，而 AC-12 断言的正是这两者的区别。
  const pn = readProductionNo().raw;
  const sc = readColumn('source');
  console.log(`[前置基线] ${HERO_EDIT}  production_no=${JSON.stringify(pn)}  source=${JSON.stringify(sc)}`);
  return { productionNo: pn, source: sc };
}

/** 客户产品的客户分布（AC-5 的期望值从**库里取**，不写死 —— 共享库会漂移）。 */
export function customerDistributionInDb(): Array<{ customerNo: string; count: number }> {
  return sql(`SELECT customer_no, count(*) FROM ds_quote_customer_part GROUP BY 1 ORDER BY 1`)
    .map(l => { const [c, n] = l.split('\t'); return { customerNo: c, count: Number(n) }; });
}

/** 未在 `customer` 表建档的客户编号（AC-5 的验证素材，🚫 不要「顺手修好」它）。 */
export function unregisteredCustomersInDb(): string[] {
  return sql(`SELECT t.customer_no FROM ds_quote_customer_part t
              LEFT JOIN customer c ON c.code = t.customer_no
              WHERE c.code IS NULL GROUP BY 1 ORDER BY 1`);
}


// ─────────────────────────── 客户过滤器（AC-1~AC-5、AC-14） ───────────────────────────

/**
 * 定位工具栏上的客户过滤器。
 *
 * 🚫 **不猜实现的选择器**。按「AC 描述的可观测特征」逐级探测，全部落空时
 * 抛一个**自带现场**的错：把工具栏文案与候选 DOM 一并打出来，
 * 让「过滤器压根没做」（产品缺陷）与「做了但控件形态不同」（用例选择器要调）**当场可分辨**。
 * ⇒ 这是父任务 `gotoProductHub` 的同一条教训：报错不带现场，会把环境/选择器问题误判成产品缺陷。
 */
export async function customerFilter(page: Page): Promise<Locator> {
  const candidates: Array<[string, Locator]> = [
    ['antd Select 含默认文案', page.locator('.ant-select').filter({ hasText: ALL_CUSTOMERS_LABEL }).first()],
    ['antd Select 含已选客户', page.locator('.ant-select').filter({ hasText: /CUST-|Q13CUST|^C1$/ }).first()],
    ['原生 select', page.locator('select').first()],
    ['任意 antd Select（工具栏第一个）', page.locator('.ant-select').first()],
  ];
  for (const [label, loc] of candidates) {
    if (await loc.count()) {
      console.log(`[客户过滤器] 命中策略「${label}」`);
      return loc;
    }
  }
  const toolbar = await page.locator('body').innerText().catch(() => '<取不到>');
  const selects = await page.locator('select, .ant-select').count();
  throw new Error(
    `AC-1：「客户产品」页签的工具栏上找不到客户过滤器。\n` +
    `  页面上 select/.ant-select 元素数 = ${selects}\n` +
    `  页面文案[0:400] = ${toolbar.slice(0, 400).replace(/\n+/g, ' | ')}\n` +
    `  ⇒ 元素数为 0 ⇒ **过滤器未实现（产品缺陷）**；元素数 >0 但文案不含「${ALL_CUSTOMERS_LABEL}」` +
    `⇒ 默认文案不符 AC-1，或控件形态与原型不同（须比对 原型图/客户产品-过滤器.html）。`);
}

/** 读过滤器当前显示的文案。 */
export async function currentFilterLabel(page: Page): Promise<string> {
  const f = await customerFilter(page);
  return (await f.innerText()).replace(/\s+/g, ' ').trim();
}

/** 展开过滤器下拉，返回所有候选项的文案（已处理 antd 虚拟滚动）。 */
export async function openFilterOptions(page: Page): Promise<string[]> {
  const f = await customerFilter(page);
  await f.click();
  await page.waitForTimeout(600);

  // ⚠️ antd Select 的下拉挂在 body 上（不在 .ant-select 里），且用 rc-virtual-list ——
  //    候选多时**未进入视野的项不在 DOM 里**。⇒ 先把列表滚到底，把所有项刷出来再采集。
  const list = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').last();
  const seen = new Map<number, string>();
  for (let round = 0; round < 8; round++) {
    const opts = list.locator('.ant-select-item-option');
    const n = await opts.count();
    for (let i = 0; i < n; i++) {
      const t = (await opts.nth(i).innerText()).replace(/\s+/g, ' ').trim();
      const key = seen.size + i;
      if (![...seen.values()].includes(t)) seen.set(key, t);
    }
    const holder = list.locator('.rc-virtual-list-holder').first();
    if (!(await holder.count())) break;
    const before = await holder.evaluate((e: any) => e.scrollTop).catch(() => 0);
    await holder.evaluate((e: any) => { e.scrollTop = e.scrollTop + e.clientHeight; }).catch(() => {});
    await page.waitForTimeout(250);
    const after = await holder.evaluate((e: any) => e.scrollTop).catch(() => 0);
    if (after === before) break;
  }
  const texts = [...seen.values()];
  // 原生 <select> 兜底
  if (!texts.length) {
    const native = page.locator('select').first();
    if (await native.count()) {
      const os = await native.locator('option').allInnerTexts();
      return os.map(s => s.replace(/\s+/g, ' ').trim());
    }
  }
  console.log(`[客户过滤器] 候选项 = ${JSON.stringify(texts)}`);
  return texts;
}

export async function closeFilterOptions(page: Page) {
  await page.keyboard.press('Escape').catch(() => {});
  await page.waitForTimeout(300);
}

/**
 * 选中某个客户（传 `ALL_CUSTOMERS_LABEL` 表示「所有客户」）。
 * 用「候选项文案包含该客户编号」定位 —— 不依赖具体展示格式（`CUST-0004 正泰` / `CUST-0004（11）` 都能中）。
 */
export async function selectCustomer(page: Page, customerNo: string) {
  const f = await customerFilter(page);
  await f.click();
  await page.waitForTimeout(500);
  const list = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden)').last();
  if (await list.count()) {
    const opt = list.locator('.ant-select-item-option')
      .filter({ hasText: customerNo === ALL_CUSTOMERS_LABEL ? ALL_CUSTOMERS_LABEL : customerNo }).first();
    await expect(opt, `客户过滤器候选里找不到「${customerNo}」—— ` +
      `若它是 ${AC_UNREGISTERED_CUSTOMERS.join(' / ')} 之一，正是 AC-5 要防的缺陷：` +
      `候选只从 customer 表取 ⇒ 未建档客户的产品看得见却筛不出来`).toBeVisible({ timeout: 8_000 });
    await opt.scrollIntoViewIfNeeded().catch(() => {});
    await opt.click();
  } else {
    const native = page.locator('select').first();
    await native.selectOption({ label: customerNo }).catch(async () => {
      await native.selectOption(customerNo);
    });
  }
  await page.waitForTimeout(1200);
  console.log(`[客户过滤器] 已选 ${customerNo}，当前文案 = ${JSON.stringify(await currentFilterLabel(page))}`);
}

/**
 * 列表总数断言（共享库漂移下唯一站得住的形式，沿用父任务 `expectTotalMatchesDb` 的思路）。
 * 断的是**不变量**：页面总数 == 同一时刻库中同条件的 `count(*)`。
 * AC 基线只打印供人工核对，不作为断言 —— `task-260902` 的 `@QuarkusTest` 与我们同库。
 */
export async function expectFilteredTotal(
  page: Page, label: string, where: string, acBaseline: number
): Promise<{ ui: number; db: number }> {
  const db = Number(sqlOne(
    `SELECT count(*) FROM ds_quote_customer_part${where ? ' WHERE ' + where : ''}`));
  const ui = await totalCount(page);
  console.log(`[${label}] 页面总数=${ui}  库中 count(*)=${db}  AC 基线=${acBaseline}` +
    (db === acBaseline ? '' : '  ⚠️ 库 ≠ AC 基线（共享库漂移）'));
  expect(ui, `${label}：分页器未渲染总数（拿不到「共 N 条」）`).not.toBeNull();
  expect(ui, `${label}：页面总数 ${ui} ≠ 同一时刻库中的 ${db} 行` +
    (where ? `（条件 ${where}）` : '') +
    ` —— 这是**页面/接口侧缺陷**（过滤没生效 / 前端自己 filter / 分页换算错），与共享库漂移无关`)
    .toBe(db);
  return { ui: ui!, db };
}

/** 读某一列在当前页所有可见行里的值（按表头文案定位列，🚫 不写死列下标 —— AC-8 会插入新列）。 */
export async function columnValues(page: Page, headerText: string): Promise<string[]> {
  const idx = await columnIndex(page, headerText);
  const rows = page.locator('.ant-table-tbody tr.ant-table-row');
  const n = await rows.count();
  const out: string[] = [];
  for (let i = 0; i < n; i++) {
    out.push((await rows.nth(i).locator('td').nth(idx).innerText()).replace(/\s+/g, ' ').trim());
  }
  return out;
}

/**
 * 按表头文案求列下标。
 * 🚨 **不写死下标**：AC-8 会在销售产品插入「产品分类」列，写死下标的用例会在那天悄悄断言到隔壁列
 * （`AP-54` 同型：过滤后下标当原下标）。
 */
export async function columnIndex(page: Page, headerText: string): Promise<number> {
  const th = page.locator('.ant-table-thead th');
  const n = await th.count();
  const seen: string[] = [];
  for (let i = 0; i < n; i++) {
    const t = (await th.nth(i).innerText()).replace(/\s+/g, '').trim();
    seen.push(t);
    if (t === headerText) return i;
  }
  throw new Error(`表头里找不到列「${headerText}」。实际表头 = ${JSON.stringify(seen)}`);
}

// ─────────────────────────── 单元格编辑（AC-6 / AC-6b / AC-10 / AC-12 / AC-13） ───────────────────────────

/** 关掉可能被误触打开的抽屉（销售产品点行会开抽屉，这是父任务 AC-5 的既有行为）。 */
export async function dismissDrawer(page: Page) {
  if (await page.locator('.ant-drawer-open').count()) {
    await page.keyboard.press('Escape').catch(() => {});
    await page.waitForTimeout(600);
    const close = page.locator('.ant-drawer .ant-drawer-close').first();
    if (await close.count()) await close.click().catch(() => {});
    await page.waitForTimeout(500);
  }
}

/** 单元格内是否出现了可编辑控件。 */
export async function cellHasEditor(cell: Locator): Promise<number> {
  return cell.locator('input, textarea, select, .ant-select, [contenteditable="true"]').count();
}

/**
 * 🚨 探测「这个单元格能不能进编辑态」。
 *
 * **为什么要探测而不是写死交互**：AC-6 原文写的是「**双击（或按实现约定的交互）**」——
 * 交互方式本身没被 AC 钉死（这条已作为 AC 质量问题上报主线）。
 * ⇒ 用例若写死 `dblclick`，实现改成「点铅笔图标」时会红，而那**不是缺陷**。
 *
 * **对称性是本函数的关键**：AC-6 的正向（生产料号可编辑）与反向（其余 6 列不可编辑）
 * 必须用**同一套探测序列**。否则「正向用 3 种交互试、反向只试 1 种」会得出一个
 * 看起来很漂亮但毫无意义的结论。
 *
 * 返回命中的交互方式，供报告里写清「实际是靠什么进的编辑态」。
 */
export async function probeEnterEdit(
  page: Page, cell: Locator, label: string
): Promise<{ entered: boolean; via: string | null }> {
  const tries: Array<[string, () => Promise<void>]> = [
    ['dblclick', async () => { await cell.dblclick({ timeout: 5_000 }); }],
    ['hover+铅笔图标', async () => {
      await cell.hover({ timeout: 5_000 });
      await page.waitForTimeout(300);
      const pen = cell.locator('.anticon-edit, .anticon-form, [aria-label*="edit"], svg[data-icon="edit"]').first();
      if (await pen.count()) await pen.click({ timeout: 5_000 });
      else throw new Error('该单元格内没有编辑图标');
    }],
    ['单击', async () => { await cell.click({ timeout: 5_000 }); }],
  ];
  for (const [via, act] of tries) {
    try { await act(); } catch (e) { console.log(`[probeEnterEdit:${label}] ${via} 未执行成功：${(e as Error).message.slice(0, 80)}`); continue; }
    await page.waitForTimeout(600);
    const n = await cellHasEditor(cell);
    if (n > 0) {
      console.log(`[probeEnterEdit:${label}] ✅ 经「${via}」进入编辑态（控件数=${n}）`);
      return { entered: true, via };
    }
    await dismissDrawer(page);   // 单击可能开抽屉（父任务 AC-5 既有行为），关掉再试下一种
  }
  console.log(`[probeEnterEdit:${label}] ✖ 三种交互（dblclick / 铅笔 / 单击）均未进入编辑态`);
  return { entered: false, via: null };
}

/** 定位「销售产品」列表里某料号某列的单元格（会先搜索把它显到首屏）。 */
export async function materialCell(page: Page, materialNo: string, header: string): Promise<Locator> {
  await search(page, materialNo);
  const row = page.locator('.ant-table-tbody tr.ant-table-row')
    .filter({ has: page.getByRole('cell', { name: materialNo, exact: true }) }).first();
  await expect(row, `列表里应能搜到料号 ${materialNo}（搜不到 ⇒ 前置数据缺失，后续断言会空跑 = 假绿）`)
    .toBeVisible({ timeout: 10_000 });
  const idx = await columnIndex(page, header);
  return row.locator('td').nth(idx);
}

/** 从页面读某料号的生产料号显示值。 */
export async function readProductionNoOnPage(page: Page, materialNo: string): Promise<string> {
  const cell = await materialCell(page, materialNo, '生产料号');
  return (await cell.innerText()).replace(/\s+/g, ' ').trim();
}

/**
 * 走 UI 把某料号的生产料号改成 `value`（`''` = 清空，AC-12）。
 * 返回本次保存过程中观察到的现场，供断言与证据使用。
 */
export async function editProductionNoViaUi(
  page: Page, materialNo: string, value: string
): Promise<{ via: string | null; messages: string[]; failedResponses: string[] }> {
  const failedResponses: string[] = [];
  const onResp = (r: any) => {
    if (r.url().includes('/api/cpq/') && r.status() >= 400) {
      failedResponses.push(`${r.status()} ${r.request().method()} ${r.url()}`);
    }
  };
  page.on('response', onResp);

  const cell = await materialCell(page, materialNo, '生产料号');
  const probe = await probeEnterEdit(page, cell, `${materialNo}.生产料号`);
  expect(probe.entered,
    `AC-6：料号 ${materialNo} 的「生产料号」单元格未能进入编辑态。\n` +
    `  已尝试的交互：双击 / 悬停后点编辑图标 / 单击（三者全落空）。\n` +
    `  ⇒ 若该列本就该可编辑，这是**产品缺陷**（S-1 未落地或交互与原型不符，` +
    `见 原型图/销售产品-可编辑生产料号.html 状态①②）。`).toBe(true);

  const input = cell.locator('input, textarea').first();
  await expect(input, '编辑态应出现输入控件').toBeVisible({ timeout: 8_000 });
  await input.fill(value);
  await input.press('Enter');
  await page.waitForTimeout(2500);

  // antd message 是瞬时的，采集当下所有 notice 文案
  const messages = await page.locator('.ant-message-notice, .ant-notification-notice').allInnerTexts()
    .then(a => a.map(s => s.replace(/\s+/g, ' ').trim())).catch(() => []);
  page.off('response', onResp);
  console.log(`[编辑] ${materialNo}.production_no ← ${JSON.stringify(value)}  via=${probe.via}  ` +
    `message=${JSON.stringify(messages)}  4xx/5xx=${JSON.stringify(failedResponses)}`);
  return { via: probe.via, messages, failedResponses };
}

/** 红色错误遮罩（vite overlay / antd 全局错误页）—— AC-13 / AC-14 都要求它不出现。 */
export async function assertNoRedOverlay(page: Page, label: string) {
  const overlay = page.locator('vite-error-overlay, #vite-error-overlay, .ant-result-error');
  await expect(overlay, `${label}：不得出现红色错误遮罩（vite overlay / ant-result-error）`).toHaveCount(0);
}

/** `PUT /api/cpq/dataset/quote/parts/{axisValue}`（`api.md` C-1）。 */
export function putPartUrl(materialNo: string, dataset = 'quote'): string {
  return `/api/cpq/dataset/${dataset}/parts/${encodeURIComponent(materialNo)}`;
}

// ─────────────────────────── 无残留证明（本套最重要的守卫） ───────────────────────────

/**
 * 🚨 **行级**残留证明，而不是表级。
 *
 * 父任务用「16 张表的内容 md5」证明只读，那是对的 —— 它一个字节都不写。
 * 本套**会写**，若沿用表级指纹，`task-260902` 的 `@QuarkusTest` 在同库并发写
 * （`TEST-DS-` 前缀夹具）会让指纹必然变化 ⇒ 守卫**恒红**，然后被人为放宽 ⇒ 守卫死掉。
 *
 * ⇒ 改成断言「**我们唯一碰过的那一行**跑完之后逐字节相同」。
 *   它对别人的漂移免疫，却能百分之百抓到「某个写用例忘了还原」。
 */
export function snapshotHeroRow(): string {
  // 🚨 **排除审计列 `updated_at` / `updated_by`**（2026-09-04 实测踩到）：
  //    对方的 `PUT parts/{axisValue}` 按约定会更新这两列（api.md C-1），而我们的还原**有意不碰它们**
  //    —— 还原时把审计列一起写回去，等于抹掉「这一行被动过」的痕迹，反而会掩盖缺陷。
  //    ⇒ 观察整行 ⇒ 只要跑过一次写用例就**恒定报残留**，是**假红**。
  //    而假红比没有守卫更糟：它让人怀疑一个本来正确的结论，几次之后守卫就被放宽或删掉了。
  //
  //    🚩 用 `to_jsonb(t) - 'updated_at' - 'updated_by'` 而不是手写列清单 ——
  //    手写清单会在别人给表加列时**静默漏掉**新列（观察面悄悄变窄，且没人会发现）。
  const s = sqlOne(
    `SELECT (to_jsonb(t) - 'updated_at' - 'updated_by')::text
     FROM ds_quote_material t WHERE material_no = '${HERO_EDIT}'`);
  if (s === null) {
    throw new Error(`🚨 ${HERO_EDIT} 在 ds_quote_material 里查不到 —— 前置守卫应先拦住`);
  }
  return s;
}

/** 表级指纹只做**提示**，不做断言（共享库漂移会让它必然变化，断言它等于制造假红）。 */
/** 审计列快照：**不参与残留断言**，只作为「PUT 确实发生过」的正向佐证打印出来。 */
export function heroAuditColumns(): string {
  return sqlOne(
    `SELECT coalesce(updated_at::text,'<NULL>') || ' / ' || coalesce(updated_by,'<NULL>')
     FROM ds_quote_material WHERE material_no = '${HERO_EDIT}'`) ?? '<读不到>';
}

export function reportTableDrift(beforeCounts: Record<string, number>) {
  for (const t of ['ds_quote_material', 'ds_quote_customer_part']) {
    const now = Number(sqlOne(`SELECT count(*) FROM ${t}`));
    if (now !== beforeCounts[t]) {
      console.warn(`⚠️ [漂移提示] ${t}: ${beforeCounts[t]} → ${now} 行。` +
        `本套用例只 UPDATE 单行、从不 INSERT/DELETE ⇒ 行数变化来自**别的会话**（如 task-260902 的 @QuarkusTest）。` +
        `这不是本套的残留，但会让绝对行数类断言失真 —— 所以本套一律用不变量形式。`);
    }
  }
}
