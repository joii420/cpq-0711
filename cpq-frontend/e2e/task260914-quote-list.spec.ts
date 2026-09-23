/**
 * E2E · task-260914 报价单列表料号搜索与扩列 —— **S-UI 分片**（前端渲染与交互）
 *
 * 覆盖 AC：AC-1 / AC-5 / AC-8 / AC-9 / AC-10 / AC-11 / AC-12 / AC-16 / AC-17 / AC-20
 * （AC-2/3/4/6/7/13/14/15/18/19/21 属 S-API 片，本文件一律不碰）
 *
 * 🚦 分片纪律（`docs/rules/testing.md §4.5`）
 *  - 本片写入面 = **纯只读**：只打开列表 / 输入搜索 / 切筛选 / 翻页 / 切页签。
 *    🚫 不建单、不保存、不提交、不删除、不改任何数据；🚫 不读不改 S-API 片的数据。
 *  - 造数前缀 `T260914U-` 已分配但**本片不造数**（只读片无需造数）。
 *  - ⚠️ 本片确实存在「全局计数断言」（共 N 条），这是 AC-16 / AC-17 的 AC 原文要求，
 *    不是用例作者的选择。对策 = **SQL 三明治采样**：UI 读数前后各取一次基准 SQL，
 *    两次不等 ⇒ 判定为「采样窗口内共享库被并发写入」，报基础设施条件而**不是**产品缺陷
 *    （见 helper `expectTotalEqualsSql`）。🚫 一律不硬编码立项日采样值（187 / 117 / 43…）。
 *
 * 🚦 开跑前置（第二阶段执行时）
 *  1) `pgrep -f "node.*[p]laywright test"` 采样，确认没有别的 playwright 在跑
 *     —— `workers:1` 只管单进程内，跨进程零互斥，而 global-setup 每次都写共享库 `user` 表。
 *     本文件 beforeAll 已内建同名守卫（会排除自身进程树）。
 *  2) worktree 内起 8196（后端）+ 5196（前端），并以
 *     `PW_BASE_URL=http://localhost:5196 PW_BACKEND_URL=http://localhost:8196` 运行。
 *     ⚠️ 共享的 5174/8081 跑的是主工作区代码，看不到 worktree 改动。
 */
import { test, expect, Page } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { fileURLToPath } from 'url';
import { isBackendUp, loginAsAdmin } from './fixtures/auth';
import {
  EM_DASH,
  assertSafeTimeZone,
  activePageNo,
  bodyRows,
  clearPartNo,
  clearSelect,
  collectSelectOptions,
  closeDropdown,
  columnCells,
  gotoPage,
  headerTexts,
  headerTextsRaw,
  keywordInput,
  openQuotationList,
  otherPlaywrightPids,
  partNoInput,
  pickOption,
  quotationNumbersOnPage,
  resolveFilterSelectIndexes,
  cardTitleBox,
  rows,
  scalar,
  setPartNo,
  sqlActiveCategoryNames,
  sqlCategoryIdByName,
  sqlPartNoCount,
  sqlPublishedQuotationTemplateNames,
  sqlQuotationTemplateSeries,
  sqlTotalQuotations,
  sqlUncategorizedCount,
  switchStatusTab,
  toolbarButtonSnapshot,
  totalCount,
  withListRequest,
} from './task260914.helpers';

const __filename_ = fileURLToPath(import.meta.url);
const __dirname_ = path.dirname(__filename_);

/**
 * 🚨 证据必须留得下来（`testing.md §2`）：
 * 截图直接落**任务目录**，不落 `test-results/`（那里每轮开跑前会被清空 = 等于没有证据）。
 */
const EVIDENCE_DIR = path.join(
  __dirname_,
  '..',
  '..',
  'dev-docs',
  'task-260914-报价单列表料号搜索与扩列',
  '测试',
  's-ui',
  '证据'
);
fs.mkdirSync(EVIDENCE_DIR, { recursive: true });

let shotIdx = 0;
async function shot(page: Page, name: string) {
  const file = path.join(EVIDENCE_DIR, `${String(++shotIdx).padStart(2, '0')}-${name}.png`);
  await page.screenshot({ path: file, fullPage: true }).catch(() => {});
  console.log(`[证据] ${file}`);
  return file;
}

function writeEvidence(name: string, content: string) {
  const file = path.join(EVIDENCE_DIR, name);
  fs.writeFileSync(file, content, 'utf-8');
  console.log(`[证据] ${file}`);
}

/** 未捕获异常收集（AC-5 要求「控制台无未捕获异常」）。 */
function trackPageErrors(page: Page): string[] {
  const errs: string[] = [];
  page.on('pageerror', (e) => errs.push(String(e).slice(0, 300)));
  return errs;
}

/** vite 红色错误遮罩（AC-5：不得出现）。 */
async function redOverlayCount(page: Page): Promise<number> {
  return page.locator('vite-error-overlay').count();
}

/**
 * 「共 N 条」与基准 SQL 的比对。
 * SQL 三明治：UI 读数前后各采一次；两次不等说明共享库在窗口内被并发写入
 * ⇒ 报基础设施条件，🚫 不得当成产品缺陷（`testing.md §4.5` 共库片纪律）。
 */
async function expectTotalEqualsSql(page: Page, sqlFn: () => number, label: string): Promise<number> {
  const before = sqlFn();
  const ui = await totalCount(page);
  const after = sqlFn();
  console.log(`[${label}] SQL(before)=${before}  UI「共 N 条」=${ui}  SQL(after)=${after}`);
  expect(
    before,
    `[${label}] 共享库在采样窗口内被并发写入（${before} → ${after}），本条结论无效，需重跑。🚫 不要据此判产品缺陷`
  ).toBe(after);
  expect(ui, `[${label}] UI「共 N 条」应等于基准 SQL 现场重算值`).toBe(before);
  return ui;
}

let backendUp = false;

test.beforeAll(async () => {
  // 🚦 并发采样：别的 playwright 在跑就必须停 —— 它的 global-setup 会写共享库 user 表
  const others = otherPlaywrightPids();
  expect(
    others,
    `检测到另有 playwright 进程在跑（pid=${others.join(',')}）。workers:1 不跨进程互斥，` +
      `两轮同时跑会互相污染共享库；请等对方跑完再开跑`
  ).toEqual([]);

  backendUp = await isBackendUp();
  if (!backendUp) return;

  // 基准快照（只打印，不当断言值 —— 断言一律现场重算）
  console.log('=== 立项日之后的现场基准（仅供人读） ===');
  console.log(`Q0 报价单总数            = ${sqlTotalQuotations()}`);
  console.log(`Q6 未分类单数            = ${sqlUncategorizedCount()}`);
  console.log(`Q4 PUBLISHED 报价模板数  = ${sqlPublishedQuotationTemplateNames().length}`);
  console.log(`ACTIVE 分类              = ${JSON.stringify(sqlActiveCategoryNames())}`);
});

// ───────────────────────────── T-P0 · DOM 探针（非 AC，定位用） ─────────────────────────────

test('T-P0 · DOM 探针：工具栏 / 页签 / 表头 / 分页形态取证（不服务 AC，供失败时定位选择器）', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page);
  await openQuotationList(page);

  const raw = await headerTextsRaw(page);
  const selects = await resolveFilterSelectIndexes(page);
  const inputs = await page.locator('.ant-card-body input').evaluateAll((els) =>
    els.map((e) => (e as HTMLInputElement).placeholder || '(无 placeholder)')
  );
  const tabRegion = await page
    .locator('.ant-tabs, .ant-radio-group, .ant-segmented')
    .first()
    .innerText()
    .catch(() => '(没找到 Tabs/Radio/Segmented)');
  const pagText = await page.locator('.ant-pagination').first().innerText().catch(() => '(无分页)');
  const btns = await toolbarButtonSnapshot(page);

  const dump = [
    `表头(原样) = ${JSON.stringify(raw)}`,
    `工具栏 input placeholders = ${JSON.stringify(inputs)}`,
    `.ant-select 清单 = ${JSON.stringify(selects.all)}  → 分类 idx=${selects.category} 模板 idx=${selects.template}`,
    `页签区文本 = ${JSON.stringify(tabRegion.replace(/\s+/g, ' ').slice(0, 300))}`,
    `分页文本 = ${JSON.stringify(pagText.replace(/\s+/g, ' '))}`,
    `按钮区 = ${JSON.stringify(btns, null, 2)}`,
  ].join('\n');
  console.log(dump);
  writeEvidence('T-P0-dom-probe.txt', dump);
  await shot(page, 'TP0-列表默认态');

  // 非空守卫：探针自己也可能是空跑
  expect(raw.length, '表头应非空').toBeGreaterThan(0);
  expect(await bodyRows(page).count(), '默认态列表应有数据行（空列表会让后续所有断言空跑）').toBeGreaterThan(0);
});

// ───────────────────────────── AC-1 ─────────────────────────────

test('T-1 · AC-1：工具栏在既有搜索框右侧新增独立料号搜索框（placeholder / 240px / allowClear）', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page);
  await openQuotationList(page);

  // ① 存在性 + placeholder 全文（task-260922 D-4 / C-1 更正后：搜索销售/客户/生产料号）
  const input = partNoInput(page);
  await expect(input, 'AC-1: 应存在独立料号搜索框').toHaveCount(1);
  const ph = await input.getAttribute('placeholder');
  console.log(`[AC-1] 料号框 placeholder = ${JSON.stringify(ph)}`);
  expect(ph, 'AC-1: placeholder 应为「搜索销售/客户/生产料号」').toBe('搜索销售/客户/生产料号');

  // ② 位置：在既有「搜索报价单号/名称/客户」框的**右侧**
  const kwBox = await keywordInput(page).boundingBox();
  const pnBox = await input.boundingBox();
  console.log(`[AC-1] 关键字框 = ${JSON.stringify(kwBox)}  料号框 = ${JSON.stringify(pnBox)}`);
  expect(kwBox && pnBox, 'AC-1: 两个输入框都应可见').toBeTruthy();
  // ⚠️ C-10（用户已裁决接受）：1280 视口下条件区会排两行。两框仍在同一行时比 x，
  //    若料号框被挤到下一行则比 y —— 🚫 不能只比 x，否则会把「已裁决接受的换行」误报成缺陷。
  const partNoIsRight = Math.abs(pnBox!.y - kwBox!.y) <= 12 ? pnBox!.x > kwBox!.x : pnBox!.y > kwBox!.y;
  console.log(`[AC-1] 同行判定=${Math.abs(pnBox!.y - kwBox!.y) <= 12}　料号框在既有框右侧/下方=${partNoIsRight}`);
  expect(partNoIsRight, 'AC-1: 料号框应在既有搜索框右侧（同行时 x 更大；C-10 换行时位于其下一行）').toBeTruthy();

  // ③ 宽度 240px —— 从 input 向上找 4 层祖先，任一层宽度 ≈240 即认定（antd Search 嵌套层数不稳定）
  const widths = await input.evaluate((el) => {
    const out: number[] = [];
    let cur: HTMLElement | null = el as HTMLElement;
    for (let i = 0; i < 5 && cur; i++) {
      out.push(Math.round(cur.getBoundingClientRect().width));
      cur = cur.parentElement;
    }
    return out;
  });
  console.log(`[AC-1] 料号框自身及 4 层祖先宽度 = ${JSON.stringify(widths)}`);
  expect(
    widths.some((w) => Math.abs(w - 240) <= 2),
    `AC-1: 料号框宽度应为 240px（实测各层宽度 ${JSON.stringify(widths)}）`
  ).toBeTruthy();

  // ④ allowClear：输入后出现清除按钮，点它能清空
  await input.fill('S0004');
  // 🚨 页面上有**两个** .ant-input-clear-icon（关键字框一个、料号框一个），
  //    关键字框那个在空值时是 `ant-input-clear-icon-hidden`（visibility:hidden）。
  //    直接 .first() 会抓到隐藏的那个 → 假红。必须先框到料号框自己的 affix wrapper。
  const partNoWrap = page
    .locator('.ant-input-affix-wrapper')
    .filter({ has: partNoInput(page) })
    .first();
  const clear = partNoWrap.locator('.ant-input-clear-icon, .anticon-close-circle').first();
  await expect(clear, 'AC-1: allowClear 清除按钮应出现').toBeVisible({ timeout: 5_000 });
  await shot(page, 'AC-1-料号框有值态');
  await clear.click();
  await expect(input, 'AC-1: 点清除后输入框应为空').toHaveValue('');
});

// ───────────────────────────── AC-5 ─────────────────────────────

test('T-5 · AC-5：料号搜 zzz9999 → 0 行 + AntD 默认空态(No data) + 分页区不渲染 + 无红色遮罩 + 无未捕获异常', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  const errs = trackPageErrors(page);
  await loginAsAdmin(page);
  await openQuotationList(page);

  // 阳性对照：先确认非空态能正常渲染，证明「空」是搜索造成的，不是页面本来就坏
  const beforeRows = await bodyRows(page).count();
  console.log(`[AC-5] 搜索前数据行数 = ${beforeRows}`);
  expect(beforeRows, 'AC-5 前置：默认态必须有数据行（否则空态断言毫无意义）').toBeGreaterThan(0);

  await setPartNo(page, 'zzz9999');

  // 该关键字在库里确实 0 命中（现场重算，防止哪天真有人造了含 zzz9999 的料号）
  const sqlHit = sqlPartNoCount('zzz9999');
  console.log(`[AC-5] 基准 SQL Q1('zzz9999') = ${sqlHit}`);
  expect(sqlHit, 'AC-5 前置：zzz9999 在库里应 0 命中；若不为 0 需换一个无命中关键字').toBe(0);

  const n = await bodyRows(page).count();
  console.log(`[AC-5] 搜索后数据行数 = ${n}`);
  expect(n, 'AC-5: 列表应为空').toBe(0);

  // 🔬 先把四项事实全取下来再断言（soft）——
  //    否则第一项一红，后面三项根本不执行，报告就说不清「到底哪几项达标」。
  const bodyText = (await page.locator('.ant-table').first().innerText()).replace(/\s+/g, '');
  const pagCount = await page.locator('.ant-pagination').count();
  let totalText = '(分页区未渲染)';
  let totalNum: number | null = null;
  if (pagCount > 0) {
    totalText = (await page.locator('.ant-pagination').first().innerText()).replace(/\s+/g, '');
    const m = totalText.match(/共([\d,]+)条/);
    if (m) totalNum = Number(m[1].replace(/,/g, ''));
  }
  const overlays = await redOverlayCount(page);
  await shot(page, 'AC-5-空态');

  console.log(`[AC-5] 表格区文本 = ${JSON.stringify(bodyText.slice(0, 120))}`);
  console.log(`[AC-5] .ant-pagination 元素数 = ${pagCount}　分页区文本 = ${JSON.stringify(totalText)}　解析出的「共 N 条」= ${totalNum}`);
  console.log(`[AC-5] vite 红色遮罩数 = ${overlays}`);
  console.log(`[AC-5] pageerror = ${JSON.stringify(errs)}`);
  writeEvidence(
    'AC-5-空态四项取证.txt',
    [
      `① 列表行数 = ${n}（期望 0）`,
      `② 表格区文本 = ${bodyText}（C-9 期望：AntD 默认空态，本项目实际为英文 No data）→ ${bodyText.includes('Nodata') ? 'PASS' : 'FAIL'}`,
      `③ .ant-pagination 元素数 = ${pagCount}（C-9 期望 0，即 0 行时分页区不渲染）；分页区文本 = ${totalText}；解析到的共 N 条 = ${totalNum}`,
      `④ vite 红色遮罩 = ${overlays}（期望 0）；pageerror = ${JSON.stringify(errs)}（期望 []）`,
    ].join('\n')
  );

  // 工具栏与表头必须仍可见可操作（原型「空态纪律」）
  await expect(partNoInput(page), 'AC-5: 空态下料号框仍应可见可操作').toBeVisible();
  expect((await headerTexts(page)).length, 'AC-5: 空态下表头仍应完整').toBeGreaterThan(0);

  // 🚦 C-9：原断言（「暂无数据」+「共 0 条」）是凭印象写的，与本项目实际行为不符。
  //    主线 A/B 实测（改造前 5174 vs 改造后 5098）：空态文案两边都是英文 `No data`、
  //    0 行时分页元素数两边都是 0 ⇒ 既有行为，不是本次引入。AC-5 已按实测改写。
  expect.soft(bodyText, 'AC-5①(C-9): 应显示 AntD 默认空态（本项目实际呈现为英文 No data）').toContain('Nodata');
  expect.soft(pagCount, 'AC-5②(C-9): 0 行时分页区不渲染（.ant-pagination 元素数应为 0）').toBe(0);
  expect.soft(overlays, 'AC-5③: 不得出现 vite 红色错误遮罩').toBe(0);
  expect.soft(errs, 'AC-5④: 控制台不得有未捕获异常').toEqual([]);
});

// ───────────────────────────── AC-8 ─────────────────────────────

test('T-8 · AC-8：9 列的列序与三个新列的列宽（140 / 180 / 120）', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page);
  await openQuotationList(page);

  const raw = await headerTextsRaw(page);
  const heads = await headerTexts(page);
  console.log(`[AC-8] 表头(原样，含勾选列空表头) = ${JSON.stringify(raw)}`);
  console.log(`[AC-8] 表头(非空)               = ${JSON.stringify(heads)}`);

  const expected = ['报价单号', '名称', '客户', '产品分类', '报价模板', '状态', '总金额', '创建日期', '到期日'];
  expect(heads, 'AC-8: 列顺序与列数应与 AC 原文一致（共 9 列）').toEqual(expected);

  // 列宽：优先读 colgroup（antd 把 width 写在 <col> 上），读不到则退回 th 实测宽度
  const colWidths = await page.evaluate(() => {
    const table = Array.from(document.querySelectorAll('.ant-table table')).find((t) => t.querySelector('thead th'));
    if (!table) return null;
    const cols = Array.from(table.querySelectorAll('colgroup col')) as HTMLElement[];
    if (!cols.length) return null;
    return cols.map((c) => c.style.width || '');
  });
  const thWidths = await page
    .locator('.ant-table-thead th')
    .evaluateAll((els) => els.map((e) => Math.round(e.getBoundingClientRect().width)));
  console.log(`[AC-8] colgroup 宽度 = ${JSON.stringify(colWidths)}`);
  console.log(`[AC-8] th 实测宽度  = ${JSON.stringify(thWidths)}`);
  writeEvidence(
    'AC-8-列宽取证.txt',
    `表头=${JSON.stringify(raw)}\ncolgroup=${JSON.stringify(colWidths)}\nth实测=${JSON.stringify(thWidths)}`
  );

  const want: Record<string, number> = { 产品分类: 140, 报价模板: 180, 创建日期: 120 };
  for (const [name, px] of Object.entries(want)) {
    const idx = raw.findIndex((t) => t === name);
    expect(idx, `AC-8: 表头应含「${name}」`).toBeGreaterThanOrEqual(0);
    const declared = colWidths?.[idx] ?? '';
    const measured = thWidths[idx];
    const ok = declared === `${px}px` || Math.abs(measured - px) <= 2;
    console.log(`[AC-8] ${name}: colgroup=${declared} 实测=${measured} 期望=${px}px → ${ok ? 'OK' : 'NG'}`);
    expect(ok, `AC-8: 「${name}」列宽应为 ${px}px（colgroup=${declared}，实测=${measured}）`).toBeTruthy();
  }
  await shot(page, 'AC-8-表头九列');
});

// ───────────────────────────── AC-9 / AC-10 / AC-11 ─────────────────────────────

/** 用既有关键字框按单号精确定位一张单，返回该行的各列文本。只读，不改任何数据。 */
async function findRowByQuotationNo(page: Page, no: string): Promise<Record<string, string>> {
  await withListRequest(page, async () => {
    await keywordInput(page).fill(no);
    await keywordInput(page).press('Enter');
  });
  const n = await bodyRows(page).count();
  expect(n, `按单号 ${no} 搜索应至少命中 1 行（命中 0 行会让后续断言空跑）`).toBeGreaterThan(0);
  const raw = await headerTextsRaw(page);
  const tds = await bodyRows(page).first().locator('td').allInnerTexts();
  const out: Record<string, string> = {};
  raw.forEach((h, i) => {
    if (h) out[h] = (tds[i] ?? '').replace(/\s+/g, '');
  });
  console.log(`[行取证] ${no} → ${JSON.stringify(out)}`);
  return out;
}

test('T-9 · AC-9：产品分类列显示中文分类名；分类为空的存量单显示「—」（非空白/非 undefined/非 UUID）', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  // 夹具现场取：一张有分类的单 + 一张无分类的单（按 updated_at 倒序取最新，保证是真实业务数据）
  const withCat = rows(
    'SELECT q.quotation_number, pc.name FROM quotation q JOIN product_category pc ON pc.id=q.product_category_id ORDER BY q.updated_at DESC LIMIT 1'
  );
  const noCat = rows(
    'SELECT quotation_number FROM quotation WHERE product_category_id IS NULL ORDER BY updated_at DESC LIMIT 1'
  );
  expect(withCat.length, 'AC-9 前置：库里应存在有产品分类的单').toBe(1);
  expect(noCat.length, 'AC-9 前置：库里应存在无产品分类的存量单').toBe(1);
  const [catNo, catName] = withCat[0];
  const [nullNo] = noCat[0];
  console.log(`[AC-9] 夹具：有分类单 ${catNo} → 期望显示「${catName}」；无分类单 ${nullNo} → 期望显示「${EM_DASH}」`);

  await loginAsAdmin(page);
  await openQuotationList(page);

  const rowA = await findRowByQuotationNo(page, catNo);
  expect(rowA['产品分类'], `AC-9: ${catNo} 的产品分类列应显示中文名「${catName}」`).toBe(catName.replace(/\s+/g, ''));
  expect(
    /^[0-9a-f]{8}-[0-9a-f]{4}-/i.test(rowA['产品分类']),
    'AC-9: 产品分类列不得显示 UUID'
  ).toBeFalsy();
  await shot(page, 'AC-9-有分类');

  const rowB = await findRowByQuotationNo(page, nullNo);
  const cell = rowB['产品分类'];
  console.log(`[AC-9] 空分类单元格 = ${JSON.stringify(cell)} charCodes=${JSON.stringify([...cell].map((c) => c.charCodeAt(0)))}`);
  expect(cell, `AC-9: 分类为空时应显示全角破折号「${EM_DASH}」(U+2014)，不是空白/undefined/UUID`).toBe(EM_DASH);
  await shot(page, 'AC-9-无分类占位');
});

test('T-10 · AC-10：报价模板列显示模板名且不带版本号；无模板的单显示「—」', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  const withTpl = rows(
    'SELECT q.quotation_number, t.name FROM quotation q JOIN template t ON t.id=q.customer_template_id ORDER BY q.updated_at DESC LIMIT 1'
  );
  const noTpl = rows(
    'SELECT quotation_number FROM quotation WHERE customer_template_id IS NULL ORDER BY updated_at DESC LIMIT 1'
  );
  expect(withTpl.length, 'AC-10 前置：库里应存在绑了模板的单').toBe(1);
  expect(noTpl.length, 'AC-10 前置：库里应存在未绑模板的单').toBe(1);
  const [tplNo, tplName] = withTpl[0];
  const [nullNo] = noTpl[0];
  console.log(`[AC-10] 夹具：${tplNo} → 期望「${tplName}」；${nullNo} → 期望「${EM_DASH}」`);

  await loginAsAdmin(page);
  await openQuotationList(page);

  const rowA = await findRowByQuotationNo(page, tplNo);
  const cell = rowA['报价模板'];
  expect(cell, `AC-10: ${tplNo} 的报价模板列应显示模板名「${tplName}」`).toBe(tplName.replace(/\s+/g, ''));
  // D-8：不带版本号 —— 断言单元格里没有 vN.N / 版本 字样（除非模板名自身就含）
  const nameHasVersion = /v\d|版本/i.test(tplName);
  if (!nameHasVersion) {
    expect(/\bv?\d+\.\d+\b|版本/i.test(cell), 'AC-10(D-8): 报价模板列不得带版本号').toBeFalsy();
  } else {
    console.log(`[AC-10] ⚠️ 该模板名自身含版本样式字符（${tplName}），版本号断言跳过 —— 已在报告里登记`);
  }
  await shot(page, 'AC-10-有模板');

  const rowB = await findRowByQuotationNo(page, nullNo);
  console.log(`[AC-10] 空模板单元格 = ${JSON.stringify(rowB['报价模板'])}`);
  expect(rowB['报价模板'], `AC-10: 无模板时应显示「${EM_DASH}」`).toBe(EM_DASH);
});

test('T-11 · AC-11：创建日期按**浏览器本地时区**换算（跨零点单必须显示本地日，🚫 不是 UTC 日）', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');

  await loginAsAdmin(page);
  await openQuotationList(page);

  // 时区口径以**浏览器实际时区**为准（C-4 裁决），不写死 America/Los_Angeles ——
  // 换台机器跑时写死的时区会把用例变成假红/假绿。
  const tz = assertSafeTimeZone(
    await page.evaluate(() => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC')
  );
  console.log(`[AC-11] 浏览器时区 = ${tz}`);

  /**
   * 🚨 夹具必须**跨零点**（UTC 日 ≠ 本地日）。
   * 只挑不跨零点的单，两种口径结果相同 ⇒ 这条断言等于没验（`testing.md §5.5` 判据恒真形态）。
   * 同时要求到期日非空，否则「与到期日格式一致」这半条会空跑。
   */
  const r = rows(
    `SELECT quotation_number, to_char(created_at,'YYYY-MM-DD'), to_char(created_at AT TIME ZONE '${tz}','YYYY-MM-DD') ` +
      `FROM quotation WHERE expiry_date IS NOT NULL ` +
      `AND created_at::date <> (created_at AT TIME ZONE '${tz}')::date ` +
      `ORDER BY updated_at DESC LIMIT 1`
  );
  expect(
    r.length,
    `AC-11 前置：需要一张**跨零点**的单（UTC 日 ≠ ${tz} 本地日）才能区分两种口径。` +
      '库里找不到 ⇒ 本条无法验证，🚫 不许改用不跨零点的单蒙混过关'
  ).toBe(1);
  const [no, utcDate, localDate] = r[0];
  console.log(`[AC-11] 跨零点夹具 ${no}：UTC 日=${utcDate}　${tz} 本地日=${localDate}（期望显示本地日）`);
  expect(utcDate, 'AC-11 前置自检：夹具必须真的跨零点').not.toBe(localDate);

  const row = await findRowByQuotationNo(page, no);
  const created = row['创建日期'];
  const due = row['到期日'];
  console.log(`[AC-11] 创建日期单元格=${JSON.stringify(created)} 到期日单元格=${JSON.stringify(due)}`);

  expect(created, 'AC-11: 创建日期应为 YYYY-MM-DD 纯日期（不带时分秒）').toMatch(/^\d{4}-\d{2}-\d{2}$/);
  expect(due, 'AC-11: 到期日应为 YYYY-MM-DD（用于证明两列格式一致）').toMatch(/^\d{4}-\d{2}-\d{2}$/);

  // 🚦 C-4：口径钉死为本地时区，唯一期望值。显示 UTC 日 = 失败（那正是 slice(0,10) 的症状）。
  expect(
    created,
    `AC-11(C-4): ${no} 的创建日期应按浏览器本地时区显示 ${localDate}；` +
      `若显示 ${utcDate} 说明走的是 UTC 口径（createdAt.slice(0,10)），与单号 ${no} 对不上`
  ).toBe(localDate);

  // 单号里的 YYYYMMDD 与该列一致（AC-11 原文的可观测判据，且与时区口径互为佐证）
  const m = no.match(/(\d{4})(\d{2})(\d{2})/);
  if (m) {
    const fromNo = `${m[1]}-${m[2]}-${m[3]}`;
    console.log(`[AC-11] 单号内含日期=${fromNo}  该列=${created}`);
    expect(created, `AC-11: 创建日期应与单号里的日期段一致（${fromNo}）`).toBe(fromNo);
  }
  await shot(page, 'AC-11-创建日期-跨零点单');
});

// ───────────────────────────── AC-12 ─────────────────────────────

test('T-12 · AC-12：分类下拉 = ACTIVE 分类 + 未分类；模板下拉按系列聚合 + 零重名 + 按名称排序(zh-Hans-CN)', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page);
  await openQuotationList(page);

  const idx = await resolveFilterSelectIndexes(page);
  console.log(`[AC-12] .ant-select 清单 = ${JSON.stringify(idx.all)}`);
  expect(idx.category, 'AC-12: 工具栏应有「产品分类」筛选下拉（F-3）').toBeGreaterThanOrEqual(0);
  expect(idx.template, 'AC-12: 工具栏应有「报价模板」筛选下拉（F-3）').toBeGreaterThanOrEqual(0);

  // 📌 2026-09-14 用户裁决：筛选入口**只在工具栏**，表头不出筛选图标 ⏷（原型已同步去掉）。
  // AC-12 原文没把它写成断言 ⇒ 这里**只取证不断言**，避免造出 AC 之外的假红；判定交主线亲验。
  const headerFilterIcons = await page.locator('.ant-table-thead .ant-table-filter-trigger').count();
  console.log(`[AC-12·取证] 表头筛选图标数量 = ${headerFilterIcons}（用户裁决期望 0，非 AC 断言）`);

  // —— 分类下拉 ——
  const catOpts = await collectSelectOptions(page, idx.category);
  await shot(page, 'AC-12-分类下拉展开');
  await closeDropdown(page);
  const activeCats = sqlActiveCategoryNames().map((s) => s.replace(/\s+/g, ''));
  console.log(`[AC-12] 分类下拉选项 = ${JSON.stringify(catOpts)}`);
  console.log(`[AC-12] SQL ACTIVE 分类 = ${JSON.stringify(activeCats)}`);
  expect(catOpts.length, 'AC-12: 分类下拉选项应非空').toBeGreaterThan(0);
  expect([...catOpts].sort(), 'AC-12: 分类下拉 = 全部 ACTIVE 分类 + 一个「未分类」（D-10）').toEqual(
    [...activeCats, '未分类'].sort()
  );
  expect(catOpts.length, `AC-12: 分类下拉条目数应为 ${activeCats.length} + 1`).toBe(activeCats.length + 1);

  // —— 模板下拉 ——
  const tplOpts = await collectSelectOptions(page, idx.template);
  await shot(page, 'AC-12-模板下拉展开');
  await closeDropdown(page);
  // 🚦 C-3：模板下拉按**模板系列**聚合，每系列一条（🚫 不是 21 个模板版本）
  const series = sqlQuotationTemplateSeries();
  const allPublishedNames = sqlPublishedQuotationTemplateNames().map((s) => s.replace(/\s+/g, ''));
  const multiName = series.filter((s) => s.nameCount > 1);
  const seriesNames = series.map((s) => s.names[0].replace(/\s+/g, ''));
  console.log(`[AC-12] 模板下拉选项(${tplOpts.length}) = ${JSON.stringify(tplOpts)}`);
  console.log(`[AC-12] SQL Q4' 模板系列数 = ${series.length}（PUBLISHED 模板版本数 = ${allPublishedNames.length}）`);
  console.log(`[AC-12] SQL 每系列名称 = ${JSON.stringify(seriesNames)}`);
  writeEvidence(
    'AC-12-下拉选项取证.txt',
    [
      `分类下拉=${JSON.stringify(catOpts)}`,
      `SQL分类=${JSON.stringify(activeCats)}`,
      `模板下拉=${JSON.stringify(tplOpts)}`,
      `SQL系列数(Q4')=${series.length}  PUBLISHED版本数=${allPublishedNames.length}`,
      `SQL每系列名称=${JSON.stringify(seriesNames)}`,
      `跨版本改过名的系列=${JSON.stringify(multiName)}`,
    ].join('\n')
  );
  expect(tplOpts.length, 'AC-12: 模板下拉选项应非空').toBeGreaterThan(0);

  // ① 条目数 = 系列数（现场重算的 Q4'），🚫 不是模板版本数
  expect(
    tplOpts.length,
    `AC-12(C-3): 模板下拉应按系列聚合，条目数应 = ${series.length} 个系列；` +
      `若等于 ${allPublishedNames.length} 说明仍按模板版本逐条列出（同名条目无法区分）`
  ).toBe(series.length);

  // ② 🚨 零重名 —— C-3 真正要防住的东西（改之前是 5 个一模一样的「正泰测试模板2」）
  const dupes = tplOpts.filter((v, i) => tplOpts.indexOf(v) !== i);
  console.log(`[AC-12] 重复选项文本 = ${JSON.stringify([...new Set(dupes)])}`);
  expect(new Set(tplOpts).size, `AC-12(C-3): 模板下拉选项文本必须零重复，重复项=${JSON.stringify([...new Set(dupes)])}`).toBe(
    tplOpts.length
  );

  // ③ 集合比对：同一系列各版本同名时可直接比集合；若有系列跨版本改过名，退化为「⊆ 全部 PUBLISHED 名称」
  if (multiName.length === 0) {
    expect([...tplOpts].sort(), 'AC-12: 模板下拉集合应等于「每系列一条」的名称集合').toEqual([...seriesNames].sort());
  } else {
    console.log(`[AC-12] ⚠️ 有 ${multiName.length} 个系列跨版本改过名（${JSON.stringify(multiName)}），集合断言退化为子集校验 —— 已在报告登记`);
    for (const opt of tplOpts) {
      expect(allPublishedNames, `AC-12: 下拉项「${opt}」应来自 PUBLISHED 报价模板名称集合`).toContain(opt);
    }
  }

  // ④ 排序口径钉死 localeCompare('zh-Hans-CN')（C-5），🚫 不再「两种口径任一成立」
  const byZh = [...tplOpts].sort((a, b) => a.localeCompare(b, 'zh-Hans-CN'));
  console.log(`[AC-12] 渲染顺序 = ${JSON.stringify(tplOpts)}\n[AC-12] zh-Hans-CN 排序 = ${JSON.stringify(byZh)}`);
  expect(tplOpts, "AC-12(C-5): 模板下拉应按名称排序，口径 = localeCompare('zh-Hans-CN')").toEqual(byZh);
});

// ───────────────────────────── AC-16 ─────────────────────────────

test('T-16 · AC-16：「未分类」筛选走服务端 —— 第 2 / 第 5 页每行分类列都是「—」，共 N 条 = 未分类总数', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page);
  await openQuotationList(page);

  const idx = await resolveFilterSelectIndexes(page);
  expect(idx.category, 'AC-16 前置：需要分类筛选下拉').toBeGreaterThanOrEqual(0);

  const totalAll = await totalCount(page);
  await pickOption(page, idx.category, '未分类');

  const n = await expectTotalEqualsSql(page, sqlUncategorizedCount, 'AC-16 未分类共 N 条');
  expect(n, 'AC-16: 筛选后「共 N 条」必须小于全量（否则说明筛选根本没生效）').toBeLessThan(totalAll);

  const pageSize = await bodyRows(page).count();
  console.log(`[AC-16] 第 1 页行数 = ${pageSize}，未分类共 = ${n}`);
  expect(pageSize, 'AC-16: 第 1 页应有数据行').toBeGreaterThan(0);

  const lastPage = Math.ceil(n / Math.max(pageSize, 1));
  const targets = [2, 5].filter((p) => p <= lastPage);
  console.log(`[AC-16] 总页数≈${lastPage}，将校验的页 = ${JSON.stringify(targets)}`);
  expect(targets.length, `AC-16: 未分类单据应多于 1 页（当前共 ${n} 条，页容量 ${pageSize}）`).toBeGreaterThan(0);
  if (!targets.includes(5)) {
    console.log('[AC-16] ⚠️ 当前数据不足 5 页，第 5 页未校验 —— 已在报告里登记为「部分未验证」');
  }

  const page1Nos = await quotationNumbersOnPage(page);
  for (const p of targets) {
    await gotoPage(page, p);
    const cells = await columnCells(page, '产品分类');
    const nos = await quotationNumbersOnPage(page);
    console.log(`[AC-16] 第 ${p} 页：行数=${cells.length} 分类列=${JSON.stringify([...new Set(cells)])}`);
    expect(cells.length, `AC-16: 第 ${p} 页应有数据行（0 行会让下面的断言空跑）`).toBeGreaterThan(0);
    for (const c of cells) {
      expect(c, `AC-16: 第 ${p} 页每一行的产品分类列都应是「${EM_DASH}」`).toBe(EM_DASH);
    }
    expect(nos.some((x) => !page1Nos.includes(x)), `AC-16: 第 ${p} 页应换了数据（证明真的翻页）`).toBeTruthy();
    // 翻页后「共 N 条」不应回退到全量
    await expectTotalEqualsSql(page, sqlUncategorizedCount, `AC-16 第 ${p} 页共 N 条`);
    await shot(page, `AC-16-未分类第${p}页`);
  }
});

// ───────────────────────────── AC-17（序列） ─────────────────────────────

test('T-17 · AC-17【序列】七步连续操作：逐步断言中间态命中数 + 每次条件变化后页码重置到第 1 页', async ({ page }) => {
  test.setTimeout(180_000);
  test.skip(!backendUp, '后端未启动');

  const CAT = '默认分类';
  const KW = 'S0004';
  const catId = sqlCategoryIdByName(CAT);
  const andCat = ` AND q.product_category_id='${catId}'`;
  const andDraft = " AND q.status='DRAFT'";
  const q1 = () => sqlPartNoCount(KW);
  const q8 = () => sqlPartNoCount(KW, andCat);
  const q9 = () => sqlPartNoCount(KW, andCat + andDraft);
  console.log(`[AC-17] 现场基准：Q1(${KW})=${q1()} Q8(∧${CAT})=${q8()} Q9(∧草稿)=${q9()}`);
  expect(q9(), 'AC-17 前置：Q9 必须 > 0，否则整条序列在空数据上空跑').toBeGreaterThan(0);

  await loginAsAdmin(page);
  await openQuotationList(page);
  const idx = await resolveFilterSelectIndexes(page);
  expect(idx.category, 'AC-17 前置：需要分类筛选下拉').toBeGreaterThanOrEqual(0);

  // ① 料号框填 S0004
  await setPartNo(page, KW);
  await expectTotalEqualsSql(page, q1, 'AC-17 ① 料号');
  expect(await activePageNo(page), 'AC-17 ①: 条件变化后页码应重置到第 1 页').toBe(1);
  await shot(page, 'AC-17-step1-料号');

  // ② 分类筛选选「默认分类」→ 中间态 = Q8
  await pickOption(page, idx.category, CAT);
  await expectTotalEqualsSql(page, q8, 'AC-17 ② 料号∧分类');
  expect(await activePageNo(page), 'AC-17 ②: 条件变化后页码应重置到第 1 页').toBe(1);
  await shot(page, 'AC-17-step2-料号+分类');

  // ③ 切到「草稿」页签 → 中间态 = Q9，且料号条件不被清空
  await switchStatusTab(page, '草稿');
  await expectTotalEqualsSql(page, q9, 'AC-17 ③ 料号∧分类∧草稿');
  expect(await partNoInput(page).inputValue(), 'AC-17 ③: 切页签后料号框内容不得被清空').toBe(KW);
  expect(await activePageNo(page), 'AC-17 ③: 切页签后页码应重置到第 1 页').toBe(1);
  await shot(page, 'AC-17-step3-草稿页签');

  // ④ 翻到第 2 页（若有）
  const size = await bodyRows(page).count();
  const q9now = q9();
  const hasPage2 = q9now > size && size > 0;
  console.log(`[AC-17] ④ 当前页行数=${size} Q9=${q9now} → ${hasPage2 ? '有第 2 页，执行翻页' : '不足 2 页，跳过翻页'}`);
  if (hasPage2) {
    await gotoPage(page, 2);
    expect(await activePageNo(page), 'AC-17 ④: 应停在第 2 页').toBe(2);
    expect(await bodyRows(page).count(), 'AC-17 ④: 第 2 页应有数据行').toBeGreaterThan(0);
    await shot(page, 'AC-17-step4-第2页');
  }

  // ⑤ 切回「全部」页签 → 回到 Q8，且页码必须重置（🚨 这一步是 AC-17 的核心：
  //    停在越界页会导致「明明有数据却显示空列表」）
  await switchStatusTab(page, '全部');
  await expectTotalEqualsSql(page, q8, 'AC-17 ⑤ 切回全部 = Q8');
  expect(await activePageNo(page), 'AC-17 ⑤: 切回全部后页码应重置到第 1 页').toBe(1);
  expect(await bodyRows(page).count(), 'AC-17 ⑤: 切回后列表不得为空').toBeGreaterThan(0);
  await shot(page, 'AC-17-step5-切回全部');

  // ⑥ 清空料号框 → 只剩分类条件
  await clearPartNo(page);
  await expectTotalEqualsSql(page, () => scalar(`SELECT count(*) FROM quotation WHERE product_category_id='${catId}'`), 'AC-17 ⑥ 仅分类');
  expect(await activePageNo(page), 'AC-17 ⑥: 条件变化后页码应重置到第 1 页').toBe(1);
  await shot(page, 'AC-17-step6-清料号');

  // ⑦ 清空分类筛选 → 回到全量
  await clearSelect(page, idx.category);
  await expectTotalEqualsSql(page, sqlTotalQuotations, 'AC-17 ⑦ 最终态回到全量');
  expect(await activePageNo(page), 'AC-17 ⑦: 条件变化后页码应重置到第 1 页').toBe(1);
  expect(await bodyRows(page).count(), 'AC-17 ⑦: 最终态列表应有数据行').toBeGreaterThan(0);
  await shot(page, 'AC-17-step7-最终态');
});

// ───────────────────────────── AC-20（回归） ─────────────────────────────

/** AC-20 的改造前基准：来自 task-260907 原型 `01-报价单列表.html`（按钮这一点上仍有效）。 */
const BASELINE_BUTTONS = ['导入历史', '导入报价数据', '新建报价单'];

test('T-20a · AC-20（上半条）：三按钮位于**卡片标题栏右侧**，五档视口都不换行不溢出', async ({ page }) => {
  test.setTimeout(120_000);
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page);
  await openQuotationList(page);

  const snap = await toolbarButtonSnapshot(page);
  const title = await cardTitleBox(page);
  console.log(`[AC-20a] 卡片标题 = ${JSON.stringify(title)}`);
  console.log(`[AC-20a] 按钮区快照 = ${JSON.stringify(snap, null, 2)}`);
  writeEvidence('AC-20a-按钮区快照-admin.json', JSON.stringify({ title, buttons: snap }, null, 2));
  await shot(page, 'AC-20a-按钮区-admin');

  const found = BASELINE_BUTTONS.map((label) => snap.find((b) => b.text.includes(label.replace(/\s+/g, ''))));
  found.forEach((b, i) => {
    expect(b, `AC-20: 应仍有「${BASELINE_BUTTONS[i]}」按钮`).toBeTruthy();
  });

  // ① 🚦 C-8：三个按钮必须在**卡片标题栏**（.ant-card-head），不再在工具栏
  found.forEach((b, i) => {
    console.log(`[AC-20a] ${BASELINE_BUTTONS[i]}: zone=${b!.zone} x=${b!.x} y=${b!.y} w=${b!.w} disabled=${b!.disabled}`);
    expect(b!.zone, `AC-20(C-8): 「${BASELINE_BUTTONS[i]}」应挂在卡片标题栏 .ant-card-head，实测 zone=${b!.zone}`).toBe('card-head');
  });

  // ② 顺序不变 + 管理员下全部可用
  const orders = found.map((b) => b!.order);
  expect(orders[0] < orders[1] && orders[1] < orders[2], `AC-20: 顺序应为 ${BASELINE_BUTTONS.join(' → ')}，实测 order=${JSON.stringify(orders)}`).toBeTruthy();
  found.forEach((b, i) => {
    expect(b!.disabled, `AC-20: 管理员下「${BASELINE_BUTTONS[i]}」应可用`).toBeFalsy();
  });

  // ③ 与标题同一行、且在标题右侧
  expect(title, 'AC-20 前置：应能取到卡片标题').toBeTruthy();
  found.forEach((b, i) => {
    expect(Math.abs(b!.y - title!.y) <= 24, `AC-20(C-8): 「${BASELINE_BUTTONS[i]}」应与标题「${title!.text}」同一行（标题 y=${title!.y}，按钮 y=${b!.y}）`).toBeTruthy();
    expect(b!.x, `AC-20(C-8): 「${BASELINE_BUTTONS[i]}」应在标题右侧`).toBeGreaterThan(title!.x);
  });

  // ④ 🚨 C-8 的核心：五档视口都不换行、不溢出（改造前 1920 以下四档全换行）
  const matrix: Array<Record<string, unknown>> = [];
  for (const w of [1280, 1366, 1440, 1600, 1920]) {
    await page.setViewportSize({ width: w, height: 800 });
    await page.waitForTimeout(500);
    const s2 = await toolbarButtonSnapshot(page);
    const t2 = await cardTitleBox(page);
    const f2 = BASELINE_BUTTONS.map((label) => s2.find((b) => b.text.includes(label.replace(/\s+/g, ''))));
    const ys = f2.map((b) => b?.y ?? -1);
    const sameRow = f2.every((b) => !!b && Math.abs(b.y - (t2?.y ?? 0)) <= 24) && new Set(ys).size === 1;
    const maxRight = Math.max(...f2.map((b) => (b ? b.x + b.w : 0)));
    const overflow = maxRight > w;
    matrix.push({ viewport: w, titleY: t2?.y, buttonYs: ys, sameRow, maxRight, overflow });
    console.log(`[AC-20a] 视口 ${w}: 标题y=${t2?.y} 按钮y=${JSON.stringify(ys)} 同行=${sameRow} 右缘=${maxRight} 溢出=${overflow}`);
    await shot(page, `AC-20a-视口${w}`);
    expect(sameRow, `AC-20(C-8): 视口 ${w} 下三按钮应与标题同一行不换行，实测 标题y=${t2?.y} 按钮y=${JSON.stringify(ys)}`).toBeTruthy();
    expect(overflow, `AC-20(C-8): 视口 ${w} 下按钮右缘 ${maxRight} 不得超出视口`).toBeFalsy();
  }
  writeEvidence('AC-20a-五档视口矩阵.json', JSON.stringify(matrix, null, 2));
  await page.setViewportSize({ width: 1280, height: 720 });
});

test('T-20b · AC-20（下半条）取证·**未验证**：按钮区 A/B 快照 + 无权限角色禁用态未验证声明', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  await loginAsAdmin(page); // 🚫 不再尝试登录非白名单账号（用户裁决）
  await openQuotationList(page);

  const snap = await toolbarButtonSnapshot(page);

  // 逐个 hover，把 tooltip / title 一并取下来 —— 改造前后做 A/B 时，文案差异要能一眼看出
  const hovered: Record<string, string> = {};
  for (const label of BASELINE_BUTTONS) {
    const btn = page
      .locator('button')
      .filter({ hasText: new RegExp(label.split('').join('\\s*')) })
      .first();
    if (!(await btn.count())) {
      hovered[label] = '(按钮未找到)';
      continue;
    }
    await btn.locator('xpath=..').hover({ force: true }).catch(() => {});
    await page.waitForTimeout(500);
    const tipLoc = page.locator('.ant-tooltip-inner');
    const tip = (await tipLoc.count()) ? (await tipLoc.first().innerText()).replace(/\s+/g, '') : '';
    const title = (await btn.getAttribute('title')) || (await btn.locator('xpath=..').getAttribute('title')) || '';
    hovered[label] = JSON.stringify({ tooltip: tip, title });
    await page.mouse.move(0, 0);
    await page.waitForTimeout(200);
  }

  console.log(`[AC-20b·取证] 按钮区快照 = ${JSON.stringify(snap, null, 2)}`);
  console.log(`[AC-20b·取证] hover 文案 = ${JSON.stringify(hovered, null, 2)}`);
  writeEvidence(
    'AC-20b-按钮区A-B快照-admin.json',
    JSON.stringify({ role: 'SYSTEM_ADMIN(admin)', capturedAt: new Date().toISOString(), buttons: snap, hover: hovered }, null, 2)
  );
  await shot(page, 'AC-20b-按钮区取证-admin');

  writeEvidence(
    'AC-20b-无权限角色禁用态-未验证.md',
    [
      '# AC-20 下半条：**本次未验证**（用户裁决降级，非缺陷）',
      '',
      '**结论原文（请原样写进闸门 B 汇报）**：',
      '',
      '> 无权限角色的运行时禁用态：**本次未验证**。',
      '',
      '## 为什么',
      '',
      '- 导入白名单 = `SALES_REP` / `SALES_MANAGER` / `SYSTEM_ADMIN`；E2E 可用的 admin / alice / bob **全在白名单内**，验不出禁用态。',
      '- 白名单外可登录的只有 `t260903_pm` / `test_finance_c87a27ab`（均 `PRICING_MANAGER`），`password_hash` 与 admin 不同、口令不可得。',
      '- 重置口令属于写共享库 `user` 表的**全局状态**（`testing.md §4.3`），用户裁决**不做**。',
      '',
      '## 本条实际留下的证据（可做 A/B 对照）',
      '',
      '- `AC-20b-按钮区A-B快照-admin.json` —— 按钮顺序 / 文案 / 禁用位 / title / tooltip / 坐标宽度',
      '- 改造前基准拿同一结构在主工作区跑一次即可逐字段 diff（主线亲验时做）',
      '',
      '## 仍然被覆盖的部分',
      '',
      '- **AC-20 上半条**（管理员态：三按钮顺序 / 可用性 / 位置在新控件右侧 / 不超视口）由 **T-20a 正常断言**，未降级。',
    ].join('\n')
  );

  // 唯一断言：证据必须真的产出（防止本条自己变成空跑）
  expect(snap.length, 'AC-20b 取证：按钮区快照不得为空，否则本条等于什么也没留下').toBeGreaterThan(0);
  console.log('[AC-20b] ⚠️ 结论：无权限角色的运行时禁用态 —— **本次未验证**（用户裁决降级，非缺陷）');
});
