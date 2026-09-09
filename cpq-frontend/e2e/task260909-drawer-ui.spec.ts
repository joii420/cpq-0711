/**
 * task-260909「已有产品抽屉数据源收敛」· 抽屉 UI 用例
 *
 * 覆盖：AC-3 / AC-4 / AC-4b / AC-5 / AC-5b / AC-6 / AC-7①② / AC-8 / AC-10 / AC-11 / AC-12 / AC-13
 *
 * 🚫 断言全部从 `需求文档.md §③` AC 原文派生，未读 `AddProductModal.tsx` 与 `existingProduct.ts`。
 * 🚨 四个 Playwright 已知坑（antd 类名 / 两字按钮插空格 / 虚拟滚动 / 勾选层级）**都表现为 timeout**，
 *    夹具里所有定位失败都会打印实际候选值再硬失败 —— 让"选择器没对上"与"产品 bug"在报告里可区分。
 */

import { test, expect, APIRequestContext, Locator, Page } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';
import {
  BACKEND_URL, CUSTOMERS, EXPECTED_COLUMNS,
  apiContext, createTestQuotation, cleanupTestQuotations,
  sqlRaw, sqlScalar, distinctMaterialCount, pickEmptyDataCustomerWithTemplate,
  gotoStep2, openExistingProductDrawer, clickDrawerSearch,
  readDrawerHeaders, readDrawerRows, fillDrawerFilter,
  readPagerCurrent, readPagerTotal, gotoPage, checkRow, confirmAdd, shot,
} from './fixtures/task260909';

let api: APIRequestContext;
const q: Record<string, string> = {};
/** AC-12 用的空数据客户 code，beforeAll 里运行时解析。 */
let emptyCustomer = '';

test.describe.configure({ mode: 'serial' });
test.setTimeout(180_000);

test.beforeAll(async () => {
  api = await apiContext(BACKEND_URL);
  q[CUSTOMERS.CHINT] = (await createTestQuotation(api, CUSTOMERS.CHINT, 'UI-CHINT')).id;
  q[CUSTOMERS.ROCKWELL] = (await createTestQuotation(api, CUSTOMERS.ROCKWELL, 'UI-RW')).id;
  // AC-12 前置：需要一个「dqcp 零行 **且** 有可用报价模板」的客户。
  // 🚨 不能用苏州西门子 —— 它实测一个模板都没有，Step1「下一步」禁用、进不去 Step2。
  //    这批客户运行时解析（是别的会话的 E2E 夹具，随时可能被清理），解析不到就硬失败。
  emptyCustomer = pickEmptyDataCustomerWithTemplate();
  expect(emptyCustomer,
    '库里找不到「dqcp 零行 + 有 PUBLISHED 报价模板」的客户 —— AC-12 的原始场景无前置可用，请报主线',
  ).not.toBe('');
  console.log(`[task260909] AC-12 空数据客户 = ${emptyCustomer}`);
  q['EMPTY'] = (await createTestQuotation(api, emptyCustomer, 'UI-EMPTY')).id;
  // AC-11 单独一张单，避免它写入的明细行污染其他只读用例
  q['AC11'] = (await createTestQuotation(api, CUSTOMERS.CHINT, 'UI-AC11')).id;
});

test.afterAll(async () => {
  await cleanupTestQuotations(api);
  await api.dispose();
});

/** 登录 → 进 Step2 → 打开抽屉（默认点过「查询」）。 */
async function openDrawerFor(page: Page, quotationId: string, clickSearch = true): Promise<Locator> {
  await loginAsAdmin(page);
  await gotoStep2(page, quotationId);
  return openExistingProductDrawer(page, clickSearch);
}

/** 过滤到某个销售料号并取回该行；带非空保护与诊断输出。 */
async function rowOf(page: Page, drawer: Locator, materialNo: string) {
  await fillDrawerFilter(page, drawer, '销售料号', materialNo);
  const rows = await readDrawerRows(drawer);
  expect(rows.length,
    `按销售料号「${materialNo}」过滤后列表为空 —— 空列表会让后续断言空跑（假绿），先确认数据前提`,
  ).toBeGreaterThan(0);
  const row = rows.find((r) => (r['销售料号'] ?? '').includes(materialNo));
  expect(row, `过滤结果里找不到 ${materialNo}；实际 = ${JSON.stringify(rows.map((r) => r['销售料号']))}`).toBeTruthy();
  return row!;
}

// ──────────────────────────────────────────────────────────────────────────
// AC-3 列构成
// ──────────────────────────────────────────────────────────────────────────

test('T4 · AC-3 · 表格恰好 7 列且顺序正确', async ({ page }) => {
  const drawer = await openDrawerFor(page, q[CUSTOMERS.CHINT]);
  const headers = await readDrawerHeaders(drawer);
  expect(headers,
    `列构成应为 ${JSON.stringify(EXPECTED_COLUMNS)}（顺序敏感），实际 = ${JSON.stringify(headers)}`,
  ).toEqual([...EXPECTED_COLUMNS]);
  await shot(page, 'AC-3-列构成7列');
});

// ──────────────────────────────────────────────────────────────────────────
// AC-4 客户图号（定点 + 两态；🚫 不按比例断言）
// ──────────────────────────────────────────────────────────────────────────

test('T5 · AC-4 · 客户图号有值态（罗克韦尔 S0004 → DWG-A004）', async ({ page }) => {
  const drawer = await openDrawerFor(page, q[CUSTOMERS.ROCKWELL]);
  const row = await rowOf(page, drawer, 'S0004');
  const expected = sqlRaw(
    `SELECT customer_drawing_no FROM ds_quote_customer_part ` +
    `WHERE customer_no='${CUSTOMERS.ROCKWELL}' AND material_no='S0004' ORDER BY created_at, customer_product_no LIMIT 1`,
  );
  expect(expected, '前提失效：库里 S0004 没有图号').toBeTruthy();
  expect(row['客户图号'], `S0004 的「客户图号」列应显示 ${expected}`).toBe(expected);
  await shot(page, 'AC-4-图号有值态-S0004');
});

test('T6 · AC-4 · 客户图号空值态（正泰 0028-2609000001 → 显示「—」）', async ({ page }) => {
  const target = '0028-2609000001';
  // 前提自检：库里该行图号确实为空，否则本条验的不是空值渲染路径
  const dbVal = sqlRaw(
    `SELECT COALESCE(NULLIF(customer_drawing_no,''),'<EMPTY>') FROM ds_quote_customer_part ` +
    `WHERE customer_no='${CUSTOMERS.CHINT}' AND material_no='${target}'`,
  );
  expect(dbVal, `前提失效：${target} 在库里已有图号（${dbVal}），空值渲染路径不会被触发`).toBe('<EMPTY>');

  const drawer = await openDrawerFor(page, q[CUSTOMERS.CHINT]);
  const row = await rowOf(page, drawer, target);
  expect(row['客户图号'], `${target} 的图号应渲染占位符「—」（AP-31 族：宁可占位也不要空白）`).toBe('—');
  for (const bad of ['', 'undefined', 'null']) {
    expect(row['客户图号'], `图号列不得渲染成 ${JSON.stringify(bad)}`).not.toBe(bad);
  }
  await shot(page, 'AC-4-图号空值态-0028-2609000001');
});

test('T20b · AC-4b · 一料号多编号时，图号与客户物料名取代表行（整行同源）', async ({ page }) => {
  const target = 'T260907-M1';
  // 前提自检：该料号确实挂 ≥2 个编号，且两行的图号/物料名不同 —— 否则这条"错配"检测无区分力
  const n = sqlScalar(
    `SELECT count(*) FROM ds_quote_customer_part WHERE customer_no='${CUSTOMERS.CHINT}' AND material_no='${target}'`,
  );
  expect(n, `前提失效：${target} 只有 ${n} 行，无法验证多编号取值`).toBeGreaterThan(1);
  const distinctDrawings = sqlScalar(
    `SELECT count(DISTINCT COALESCE(customer_drawing_no,'')) FROM ds_quote_customer_part ` +
    `WHERE customer_no='${CUSTOMERS.CHINT}' AND material_no='${target}'`,
  );
  expect(distinctDrawings,
    `前提失效：${target} 各行图号相同 —— 那么"取哪一行"无法观测，本条是恒真判据`,
  ).toBeGreaterThan(1);

  // 代表行 = ORDER BY material_no, created_at, customer_product_no 的第一行（api.md §1.4）
  const rep = sqlRaw(
    `SELECT customer_product_no || '|' || COALESCE(customer_drawing_no,'') || '|' || COALESCE(customer_part_name,'') ` +
    `FROM ds_quote_customer_part WHERE customer_no='${CUSTOMERS.CHINT}' AND material_no='${target}' ` +
    `ORDER BY created_at, customer_product_no LIMIT 1`,
  );
  const [repNo, repDrawing, repName] = rep.split('|');

  const drawer = await openDrawerFor(page, q[CUSTOMERS.CHINT]);
  const row = await rowOf(page, drawer, target);
  expect(row['客户产品编号'], `代表编号应为 ${repNo}`).toContain(repNo);
  expect(row['客户产品编号'], `应带「等 ${n} 个」Tag`).toMatch(new RegExp(`等\\s*${n}\\s*个`));
  // 🚨 整行同源：🚫 不许出现「编号来自 A 行、图号来自 B 行」的错配
  expect(row['客户图号'], `图号应与代表编号同行（${repDrawing}），不得取另一行的值`).toBe(repDrawing);
  expect(row['客户物料名'], `客户物料名应与代表编号同行（${repName}），不得取另一行的值`).toBe(repName);
  await shot(page, 'AC-4b-多编号取代表行-T260907-M1');
});

// ──────────────────────────────────────────────────────────────────────────
// AC-5 / AC-5b 品名与客户物料名
// ──────────────────────────────────────────────────────────────────────────

test('T8 · AC-5 · 品名与客户物料名是两个不同的值（罗克韦尔三个定点料号）', async ({ page }) => {
  const points = [
    { no: 'S0004', custName: '罗克韦尔触桥组件A', prodName: '触桥组件A' },
    { no: 'S0001', custName: '示例客户料号', prodName: '铆钉' },
    { no: 'S0012', custName: '正泰端子组件C', prodName: '端子组件C' },
  ];
  const drawer = await openDrawerFor(page, q[CUSTOMERS.ROCKWELL]);
  for (const p of points) {
    const row = await rowOf(page, drawer, p.no);
    expect(row['客户物料名'], `${p.no} 的「客户物料名」`).toBe(p.custName);
    expect(row['品名'], `${p.no} 的「品名」`).toBe(p.prodName);
    expect(row['客户物料名'], `${p.no}：两列文本不得相同（改动前它们同取 r[2]，必然相同）`)
      .not.toBe(row['品名']);
  }
  await shot(page, 'AC-5-品名与客户物料名不同');
});

test('T10 · AC-5b · 品名为空时回退销售料号', async ({ page }) => {
  const target = '0028-2609000001';
  const hit = sqlScalar(`SELECT count(*) FROM v_compat_material_master WHERE material_no='${target}'`);
  expect(hit, `前提失效：${target} 在主数据视图里已有记录，兜底路径不会被触发`).toBe(0);

  const drawer = await openDrawerFor(page, q[CUSTOMERS.CHINT]);
  const row = await rowOf(page, drawer, target);
  expect(row['品名'], `品名为空应回退显示销售料号本身`).toBe(target);
  for (const bad of ['—', '']) {
    expect(row['品名'], `品名列不得显示 ${JSON.stringify(bad)}`).not.toBe(bad);
  }
  expect(row['品名'], `🚫 不许回退到客户物料名（那会让两列又变回相同，AC-5 白修）`).not.toBe(row['客户物料名']);
  await shot(page, 'AC-5b-品名兜底-0028-2609000001');
});

// ──────────────────────────────────────────────────────────────────────────
// AC-6 来源标签
// ──────────────────────────────────────────────────────────────────────────

test('T11 · AC-6 · 来源标签：已有 / 选配·单件 / 选配·组合', async ({ page }) => {
  const cases = [
    { productNo: 'A002', expected: '已有', why: 'source=IMPORT' },
    { productNo: 'T260907R-SEL-D40', expected: '选配·单件', why: 'source=MANUAL 且 product_type=SIMPLE' },
    { productNo: '2222222', expected: '选配·组合', why: 'source=MANUAL 且 product_type=COMPOSITE' },
  ];
  const drawer = await openDrawerFor(page, q[CUSTOMERS.ROCKWELL]);
  for (const c of cases) {
    await fillDrawerFilter(page, drawer, '客户产品编号', c.productNo);
    const rows = await readDrawerRows(drawer);
    expect(rows.length, `按编号 ${c.productNo} 过滤后列表为空（非空保护）`).toBeGreaterThan(0);
    expect(rows[0]['来源'].replace(/\s+/g, ''),
      `编号 ${c.productNo}（${c.why}）的「来源」Tag 应为「${c.expected}」`,
    ).toBe(c.expected);
  }
  // 🚫 裸标签「选配」不作断言：实测 4 个 MANUAL 料号全部有 signature，
  //    当前库里不存在"MANUAL 但无 signature"的行 ⇒ 无正例可验（AC-6 已注明）。
  await shot(page, 'AC-6-来源标签');
});

// ──────────────────────────────────────────────────────────────────────────
// AC-7 3D 预览已移除
// ──────────────────────────────────────────────────────────────────────────

test('T12 · AC-7① · 抽屉内无 3D 预览面板与缩略图', async ({ page }) => {
  const drawer = await openDrawerFor(page, q[CUSTOMERS.CHINT]);
  const rows = await readDrawerRows(drawer);
  expect(rows.length, '需要有数据的抽屉才能验"面板不存在"（空抽屉里什么都不存在，恒真）').toBeGreaterThan(0);

  await expect(drawer.getByText(/交互查看/), '抽屉不应有「⤢ 交互查看」按钮').toHaveCount(0);
  await expect(drawer.getByText(/3D|预览/), '抽屉不应有 3D / 预览相关文案').toHaveCount(0);
  expect(await drawer.locator('img').count(), '抽屉不应有缩略图 img 节点').toBe(0);
  expect(await drawer.locator('canvas').count(), '抽屉不应有 3D canvas').toBe(0);
  await shot(page, 'AC-7-无3D预览面板');
});

test('T13 · AC-7② · 连续切换 5 行不发任何 /model-configs/current 请求', async ({ page }) => {
  const urls: string[] = [];
  page.on('request', (r) => urls.push(r.url()));

  const drawer = await openDrawerFor(page, q[CUSTOMERS.CHINT]);
  const rows = drawer.locator('.ant-table-row');
  const n = await rows.count();
  expect(n, '需要 ≥5 行才能"连续切换 5 行"').toBeGreaterThanOrEqual(5);
  for (let i = 0; i < 5; i++) {
    await rows.nth(i).click();
    await page.waitForTimeout(700);
  }

  // 🚨 阳性对照：同一个收集器必须抓到本抽屉自己的请求。
  //    抓不到 ⇒ 监听器压根没接上，"没有 model-configs 请求"这条是空验证（testing.md §4.4）。
  const positive = urls.filter((u) => u.includes('existing-products'));
  expect(positive.length,
    `阳性对照失败：request 收集器一条 existing-products 请求都没抓到（共收集 ${urls.length} 条）—— ` +
    `本条"无 3D 请求"的断言此刻是空验证，不算通过`,
  ).toBeGreaterThan(0);

  const bad = urls.filter((u) => u.includes('/model-configs/current'));
  expect(bad,
    `抽屉仍在请求 3D 模型配置：${JSON.stringify(bad.slice(0, 5))}（组件删了但 useEffect 还在发请求？）`,
  ).toEqual([]);
  console.log(`[T13] 共捕获 ${urls.length} 条请求，其中 existing-products ${positive.length} 条，model-configs 0 条`);
});

// ──────────────────────────────────────────────────────────────────────────
// AC-8 3D 模型管理页无回归（🚫 只验可用性，不真跑上传/设为当前版本 —— 那会改全局配置状态）
// ──────────────────────────────────────────────────────────────────────────

test('T15 · AC-8 · 3D 模型配置页可打开、列表有数据、操作入口可用', async ({ page }) => {
  await loginAsAdmin(page);
  await page.goto('/model-configs');
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(2000);

  // 无红色遮罩 / 无「加载中…」滞留
  await expect(page.locator('#vite-error-overlay, .vite-error-overlay'), '页面不应出现红色错误遮罩').toHaveCount(0);
  await expect(page.getByText('加载中…'), '不应残留「加载中…」永久占位').toHaveCount(0);

  const rows = page.locator('.ant-table-row');
  await expect(rows.first(), '3D 模型配置列表应有数据行（非空保护）').toBeVisible({ timeout: 30_000 });
  expect(await rows.count(), '列表数据行数应 > 0').toBeGreaterThan(0);

  // 🚫 只断言入口可用，不点击 —— 上传/设为当前版本会改全局配置状态（testing.md §4.3）
  const upload = page.getByRole('button', { name: /上\s*传/ }).first();
  await expect(upload, '「上传」入口应存在且可用').toBeEnabled({ timeout: 15_000 });
  await shot(page, 'AC-8-3D模型配置页无回归');
});

// ──────────────────────────────────────────────────────────────────────────
// AC-10 序列：翻页 → 过滤 → 清空 → 翻页
// ──────────────────────────────────────────────────────────────────────────

test('T17 · AC-10 · 序列：翻页 → 过滤 → 清空过滤 → 翻页', async ({ page }) => {
  const drawer = await openDrawerFor(page, q[CUSTOMERS.CHINT]);
  const invariant = distinctMaterialCount(CUSTOMERS.CHINT);
  expect(invariant, '正泰应有数据').toBeGreaterThan(60); // 至少够翻到第 3 页

  // 基线：先记录第 2 页的料号（步骤④要与它逐元素比对）
  await gotoPage(page, drawer, 2);
  const page2Before = (await readDrawerRows(drawer)).map((r) => r['销售料号']);
  expect(page2Before.length, '第 2 页应有数据').toBeGreaterThan(0);
  await gotoPage(page, drawer, 1);

  // ① 翻到第 3 页
  await gotoPage(page, drawer, 3);
  expect(await readPagerCurrent(drawer), '步骤①：分页器 current 应为 3').toBe(3);
  const page3 = await readDrawerRows(drawer);
  expect(page3.length, '步骤①：第 3 页应有 20 行（即全量的第 41–60 行）').toBe(20);
  const totalAt1 = await readPagerTotal(drawer);
  if (totalAt1 !== null) {
    expect(totalAt1, `步骤①：total 应等于当场实测的不变量 ${invariant}（🚫 不写死 2662）`).toBe(invariant);
  }

  // ② 过滤 S000
  await fillDrawerFilter(page, drawer, '销售料号', 'S000');
  expect(await readPagerCurrent(drawer), '步骤②：过滤后页码应自动回到第 1 页').toBe(1);
  const filteredExpected = sqlScalar(
    `SELECT count(DISTINCT material_no) FROM ds_quote_customer_part ` +
    `WHERE customer_no='${CUSTOMERS.CHINT}' AND material_no ILIKE '%S000%'`,
  );
  // 🚨 「命中数 > 0」不能省：空列表会让下面的「每行都含 S000」空跑通过 = 假绿
  expect(filteredExpected, '过滤 S000 的命中数必须 > 0，否则本步是空验证').toBeGreaterThan(0);
  const filteredRows = await readDrawerRows(drawer);
  expect(filteredRows.length, `步骤②：过滤后应有 ${filteredExpected} 行`).toBe(filteredExpected);
  const totalAt2 = await readPagerTotal(drawer);
  if (totalAt2 !== null) expect(totalAt2, '步骤②：total 应变为过滤后命中数').toBe(filteredExpected);
  for (const r of filteredRows) {
    expect(r['销售料号'], `步骤②：每行销售料号都应含 S000，实际 ${r['销售料号']}`).toContain('S000');
  }

  // ③ 清空过滤
  await fillDrawerFilter(page, drawer, '销售料号', '');
  expect(await readPagerCurrent(drawer), '步骤③：清空后页码应回到第 1 页').toBe(1);
  const totalAt3 = await readPagerTotal(drawer);
  if (totalAt3 !== null) expect(totalAt3, '步骤③：total 应恢复到不变量值').toBe(invariant);

  // ④ 再翻到第 2 页，与基线逐元素比对（无错行、无重复行）
  await gotoPage(page, drawer, 2);
  const page2After = (await readDrawerRows(drawer)).map((r) => r['销售料号']);
  expect(page2After, '步骤④：第 2 页内容应与序列开始前完全一致（无错行、无重复行）').toEqual(page2Before);
  expect(new Set(page2After).size, '步骤④：第 2 页不应出现重复行').toBe(page2After.length);
  await shot(page, 'AC-10-序列-翻页过滤清空翻页');
});

// ──────────────────────────────────────────────────────────────────────────
// AC-11 序列：选中 → 加入报价单 → 去重回归
// ──────────────────────────────────────────────────────────────────────────

test('T18 · AC-11 · 序列：勾选 2 行加入报价单，再次加入按既有去重规则', async ({ page }) => {
  const drawer = await openDrawerFor(page, q['AC11']);
  const rows = await readDrawerRows(drawer);
  expect(rows.length, '抽屉应有 ≥3 行以完成本序列').toBeGreaterThanOrEqual(3);
  const picked = [rows[0]['销售料号'], rows[1]['销售料号']];
  const third = rows[2]['销售料号'];
  console.log(`[T18] 第一次勾选 = ${JSON.stringify(picked)}；第二次追加 = ${third}`);

  await checkRow(page, drawer, 0);
  await checkRow(page, drawer, 1);
  await confirmAdd(page, drawer);

  // 断言：两个产品进了报价单明细，且 customerProductNo 已回填
  const lineNos = sqlRaw(
    `SELECT string_agg(DISTINCT product_part_no, ',' ORDER BY product_part_no) ` +
    `FROM quotation_line_item WHERE quotation_id='${q['AC11']}'`,
  );
  expect(lineNos, '加入后报价单应有明细行（非空保护）').toBeTruthy();
  for (const p of picked) {
    expect(lineNos.split(',').some((x) => x === p),
      `料号 ${p} 应已加入报价单明细，实际明细 = ${lineNos}`).toBe(true);
  }
  const cpnFilled = sqlScalar(
    `SELECT count(*) FROM quotation_line_item WHERE quotation_id='${q['AC11']}' ` +
    `AND NULLIF(customer_product_no,'') IS NOT NULL`,
  );
  expect(cpnFilled, 'customerProductNo 应正确回填到明细行').toBeGreaterThan(0);
  await shot(page, 'AC-11-加入报价单后Step2');

  // 再次打开抽屉，勾选同样 2 行 + 1 个新行 → 同 productPartNo 只保留一份（以现有为准）
  const drawer2 = await openExistingProductDrawer(page);
  const rows2 = await readDrawerRows(drawer2);
  const idxOf = (no: string) => rows2.findIndex((r) => r['销售料号'] === no);
  for (const no of [...picked, third]) {
    const i = idxOf(no);
    expect(i, `第二次打开抽屉时应仍能找到 ${no}`).toBeGreaterThanOrEqual(0);
    await checkRow(page, drawer2, i);
  }
  await confirmAdd(page, drawer2);

  const finalCount = sqlScalar(
    `SELECT count(DISTINCT product_part_no) FROM quotation_line_item WHERE quotation_id='${q['AC11']}'`,
  );
  const dupCount = sqlScalar(
    `SELECT count(*) FROM quotation_line_item WHERE quotation_id='${q['AC11']}'`,
  );
  expect(finalCount, `去重后应有 3 个不同料号（原 2 个 + 新增 1 个）`).toBe(3);
  expect(dupCount, `同 productPartNo 只保留一份 —— 明细总行数应为 3，实际 ${dupCount}（重复加入未去重？）`).toBe(3);
});

// ──────────────────────────────────────────────────────────────────────────
// AC-12 空数据客户
// ──────────────────────────────────────────────────────────────────────────

test('T19 · AC-12 · 空数据客户点过查询后显示 AntD 标准空态', async ({ page }) => {
  // 前提自检：该客户在 dqcp 确实 0 行
  const n = distinctMaterialCount(emptyCustomer);
  expect(n, `前提失效：客户 ${emptyCustomer} 在 dqcp 里已有 ${n} 个料号，不再是空数据客户`).toBe(0);

  // 🚨 clickSearch=false：先证明"打开时本来就是空的"，再点查询 —— 否则两种空无法区分。
  //    不点查询就断言空态，是 AC-12 明令的恒真假绿。
  const drawer = await openDrawerFor(page, q['EMPTY'], false);
  const beforeSearch = await readDrawerRows(drawer);
  expect(beforeSearch.length, '抽屉打开时列表本来就是空的（这正是"必须先点查询"的原因）').toBe(0);

  await clickDrawerSearch(page, drawer);
  const afterSearch = await readDrawerRows(drawer);
  expect(afterSearch.length, '点过查询后，无数据客户仍应是 0 行').toBe(0);

  // AntD 标准空态，且不是红色遮罩、不是「加载中…」永久占位
  await expect(drawer.locator('.ant-empty'), '应显示 AntD 标准空态').toHaveCount(1);
  await expect(drawer.getByText('加载中…'), '不应残留「加载中…」永久占位').toHaveCount(0);
  await expect(page.locator('#vite-error-overlay, .vite-error-overlay'), '不应出现红色错误遮罩').toHaveCount(0);
  // 分页器不显示（total <= PAGE_SIZE）
  expect(await drawer.locator('.ant-pagination').count(), '空数据时分页器不应显示').toBe(0);
  await shot(page, 'AC-12-空数据客户空态');
});

test('T19b · AC-12 · 阳性对照：同一段空态判据在有数据客户下必须【不】命中', async ({ page }) => {
  // 🚨 T19 断言的是"空"，而"空"在抽屉坏掉时同样成立（渲染失败也是 0 行 + 可能有 .ant-empty）。
  //    所以必须配一个阳性对照，证明这套判据有区分力，不是恒真（`testing.md §5.5`）。
  const drawer = await openDrawerFor(page, q[CUSTOMERS.CHINT]);
  const rows = await readDrawerRows(drawer);
  expect(rows.length, '阳性对照：有数据客户点过查询后应有数据行').toBeGreaterThan(0);
  await expect(drawer.locator('.ant-empty'), '阳性对照：有数据时不应出现空态').toHaveCount(0);
  expect(await drawer.locator('.ant-pagination').count(), '阳性对照：有数据时分页器应显示').toBeGreaterThan(0);
});

// ──────────────────────────────────────────────────────────────────────────
// AC-13 一料号多编号
// ──────────────────────────────────────────────────────────────────────────

test('T20 · AC-13 · 一料号多编号只出一行，带「等 N 个」Tag 且 hover 显示全部编号', async ({ page }) => {
  const target = '0526-2609000006';
  const n = sqlScalar(
    `SELECT count(*) FROM ds_quote_customer_part WHERE customer_no='${CUSTOMERS.ROCKWELL}' AND material_no='${target}'`,
  );
  expect(n, `前提失效：${target} 只有 ${n} 行，无法验证多编号去重`).toBeGreaterThan(1);
  const allNos = sqlRaw(
    `SELECT string_agg(customer_product_no, ',' ORDER BY created_at, customer_product_no) ` +
    `FROM ds_quote_customer_part WHERE customer_no='${CUSTOMERS.ROCKWELL}' AND material_no='${target}'`,
  ).split(',');

  const drawer = await openDrawerFor(page, q[CUSTOMERS.ROCKWELL]);
  await fillDrawerFilter(page, drawer, '客户产品编号', allNos[0]);
  const rows = await readDrawerRows(drawer);
  expect(rows.length, `按编号 ${allNos[0]} 过滤后应恰好命中 1 行（DISTINCT ON 去重）`).toBe(1);
  expect(rows[0]['销售料号'], `命中行的料号应为 ${target}`).toBe(target);
  expect(rows[0]['客户产品编号'], `应显示代表编号 ${allNos[0]}`).toContain(allNos[0]);
  expect(rows[0]['客户产品编号'], `应带「等 ${n} 个」Tag（不因列宽截断而消失）`)
    .toMatch(new RegExp(`等\\s*${n}\\s*个`));

  // hover Tag 看 tooltip 是否列出全部编号
  const tag = drawer.locator('.ant-table-row').first().locator('.ant-tag').filter({ hasText: /等\s*\d+\s*个/ }).first();
  await expect(tag, '「等 N 个」Tag 应可见').toBeVisible({ timeout: 15_000 });
  await tag.hover();
  await page.waitForTimeout(1200);
  const tip = page.locator('.ant-tooltip-inner').last();
  await expect(tip, 'hover 后应出现 tooltip').toBeVisible({ timeout: 10_000 });
  const tipText = await tip.innerText();
  for (const no of allNos) {
    expect(tipText, `tooltip 应包含编号 ${no}，实际 = ${JSON.stringify(tipText)}`).toContain(no);
  }
  await shot(page, 'AC-13-一料号多编号-等N个');
});
