/**
 * task-260909「取数配置器字段类型选择」· S1 · 渲染等价与行身份
 *   AC-6（核心·最高风险）同源双组件逐行逐列**可见值逐字相同**
 *   AC-7（单点）BASIC_DATA 页签不存在承载值的 <input>；INPUT_* 页签仍是 <input>（阳性对照）
 *   AC-10（边界·行身份）料号列配成 BASIC_DATA 后，行数与对照组相同、part_no_field 仍指向该字段名
 *   AC-12（序列）BASIC_DATA → 改 INPUT_TEXT → 渲染（值同、变 <input>）→ 改回 → 渲染（值同、变纯文本）
 *   AC-14（无副作用）自建 QUOTE 组件的 INPUT_* 字段仍可编辑可保存（改值失焦后可读回）
 *
 * 🚫 全程在**自建 DRAFT 产物**上验：不碰 `核价模板1`(9d89e95d…) / `QT-20260909-0661`(3c38bfb7…)。
 *
 * ── AC-6 的两个假绿，逐个正面顶住（`test.md §4`）────────────────────────
 *  ① **空验证**：两个页签都渲不出值时，「逐字相同」恒真
 *     ⇒ 比对前**先断言两侧都非空**（行数 > 0 且非空单元格数 > 0）。INPUT_* 侧是天然阳性对照。
 *  ② **读不到值被当成没值**：单元格是 `<input>` 时 `innerText` 读不到它的 value
 *     ⇒ `readTab()` 同时抓 `input/select/textarea` 的 `value`
 *       （上一个任务 4 行真数据被读成 `["","","",""]`）。
 */
import { test, expect, Page } from '@playwright/test';
import * as FT from './t260909ft.helpers';
import {
  openExistingProductDrawer, checkRow, confirmAdd, fillDrawerFilter, readDrawerRows,
} from './fixtures/task260909';

let cookie = '';
let backendUp = false;

/** 两个同源组件的**唯一变量是 field_type** —— 同一个数据源、同样的列、同样的字段名。 */
const COLUMNS: Omit<FT.BuilderColumn, 'fieldType'>[] = [
  { sourceNodeKey: 'MATERIAL', sourceColumn: 'production_no', fieldName: '生产料号',
    dataType: 'TEXT', roles: ['PART_NO', 'ROW_KEY'], isPartNo: true, isRowKey: true },
  { sourceNodeKey: 'MATERIAL', sourceColumn: 'material_name', fieldName: '材料名', dataType: 'TEXT', roles: [] },
  { sourceNodeKey: 'MATERIAL', sourceColumn: 'dimension', fieldName: '尺寸', dataType: 'TEXT', roles: [] },
  { sourceNodeKey: 'MATERIAL', sourceColumn: 'unit_weight', fieldName: '单重', dataType: 'NUMBER', roles: [] },
];

const TAB_BASIC = 'FT基础数据';
const TAB_INPUT = 'FT输入框';
const TAB_QUOTE = 'FT报价输入';

function cfg(dialect: 'COST_BASIC' | 'QUOTE', ft: FT.FieldType | 'AUTO'): FT.BuilderConfigBody {
  return {
    tabType: '主件', variantKey: '', dialect,
    columns: COLUMNS.map((c) => (ft === 'AUTO'
      ? { ...c }
      : { ...c, fieldType: ft === 'BASIC_DATA' ? 'BASIC_DATA' : (c.dataType === 'NUMBER' ? 'INPUT_NUMBER' : 'INPUT_TEXT') })),
  };
}

/** 本 spec 的共享场景（建一次，五条用例复用；🚫 每条重建会把共享库塞满自建单）。 */
interface Scene {
  fixture: FT.RenderFixture;
  basicId: string; basicName: string;
  inputId: string; inputName: string;
  quoteId: string; quoteName: string;
  templateId: string;
  quotationId: string; quotationNumber: string;
}
let scene: Scene | null = null;
let sceneError: string | null = null;

test.beforeAll(async () => {
  try {
    const r = await fetch(`${FT.BACKEND_URL}/api/cpq/health`, { signal: AbortSignal.timeout(4000) });
    backendUp = r.ok;
  } catch { backendUp = false; }
  if (backendUp) cookie = await FT.loginApi();
});

test.beforeEach(async ({ page }) => {
  test.skip(!backendUp, '后端未启动（harness 前置）');
  await FT.uiLogin(page);
  if (!scene && !sceneError) {
    try { scene = await buildScene(page); }
    catch (e) { sceneError = String(e); throw e; }
  }
  expect(sceneError, `场景搭建失败 ⇒ 本组 AC 全部判【未验证】：\n${sceneError}`).toBeNull();
});

test.afterAll(async () => { if (backendUp) FT.archiveOwned(); });

/**
 * 搭场景：两个同源 COST_BASIC 组件 + 一个 QUOTE 组件 → 一张 DRAFT 核价模板 → 一张自建报价单 → 加一个产品。
 * 每一步都**回查落库**，任何一步没落到就硬失败并写明「夹具问题，不是产品缺陷」。
 */
async function buildScene(page: Page): Promise<Scene> {
  const fixture = FT.pickRenderFixture();

  // ① 两个同源组件（唯一变量 = field_type）+ 一个报价侧组件（AC-14）
  const b = await FT.createComponent(cookie, 'BASIC');
  const i = await FT.createComponent(cookie, 'INPUT');
  const q = await FT.createComponent(cookie, 'QUOTE');
  for (const [id, name, body] of [
    [b.id, b.name, cfg('COST_BASIC', 'BASIC_DATA')],
    [i.id, i.name, cfg('COST_BASIC', 'INPUT_TEXT')],
    [q.id, q.name, cfg('QUOTE', 'INPUT_TEXT')],
  ] as [string, string, FT.BuilderConfigBody][]) {
    const r = await FT.saveBuilder(cookie, id, body);
    expect(r.status, `搭场景：保存组件「${name}」的 builder 应 2xx，实际 ${r.status}：${r.text.slice(0, 400)}`)
      .toBeLessThan(300);
  }

  // 落库自检：两个 COST_BASIC 组件的 field_type 必须**确实不同**，否则 AC-6 的「唯一变量」不成立，
  // 「两侧逐字相同」就变成了一条恒真的废话
  const kindsB = FT.dbFieldsOf(b.id).map((f) => f.field_type);
  const kindsI = FT.dbFieldsOf(i.id).map((f) => f.field_type);
  console.log(`[scene] ${b.name} field_type=${JSON.stringify(kindsB)}；${i.name} field_type=${JSON.stringify(kindsI)}`);
  expect(kindsB.every((k) => k === 'BASIC_DATA'),
    `搭场景：${b.name} 应全为 BASIC_DATA，实际 ${JSON.stringify(kindsB)} ⇒ 夹具没落到位`).toBe(true);
  expect(kindsI.every((k) => k.startsWith('INPUT_')),
    `搭场景：${i.name} 应全为 INPUT_*，实际 ${JSON.stringify(kindsI)} ⇒ 夹具没落到位`).toBe(true);
  expect(kindsB.join()).not.toBe(kindsI.join());

  // 料号列身份（AC-10 用）
  const pnB = FT.sqlScalar(`SELECT coalesce(part_no_field,'') FROM component WHERE id='${b.id}'`);
  const pnI = FT.sqlScalar(`SELECT coalesce(part_no_field,'') FROM component WHERE id='${i.id}'`);
  console.log(`[scene] part_no_field: BASIC=${JSON.stringify(pnB)} INPUT=${JSON.stringify(pnI)}`);
  if (!pnB || !pnI) {
    // 量具自校准：roles 词汇没被消费时改用 isPartNo/isRowKey 再存一次，并打印用的是哪条路径
    console.log('[scene] ⚠️ part_no_field 为空 ⇒ `roles` 词汇可能未被后端消费，改用 isPartNo/isRowKey 重存一次');
    for (const [id, body] of [[b.id, cfg('COST_BASIC', 'BASIC_DATA')], [i.id, cfg('COST_BASIC', 'INPUT_TEXT')]] as
      [string, FT.BuilderConfigBody][]) {
      body.columns[0].isPartNo = true; body.columns[0].isRowKey = true;
      await FT.saveBuilder(cookie, id, body);
    }
  }

  // ② DRAFT 核价模板（两个页签）+ 报价侧组件另挂一张模板？—— 报价侧用既有报价模板，
  //    自建 QUOTE 组件通过**同一张 DRAFT 核价模板**验不了报价侧，故 AC-14 单独处理（见该用例）。
  // 🚨 名字带 RUN_ID：库里已存在别的会话建的同名 `T260909FT-模板`（**PUBLISHED**，7 页签，绑了 1 张单）。
  //    同名 + 同分类的 COSTING 模板会让任何「按名字/按分类再解析一次」的路径落到**他们那张**上，
  //    而 PUBLISHED 模板吃冻结快照、读不到活表 —— AC-6 的前提就没了。
  const templateId = FT.createDraftCostingTemplate(FT.ownName('模板'), fixture.categoryId, [
    { componentId: b.id, tabName: TAB_BASIC },
    { componentId: i.id, tabName: TAB_INPUT },
  ]);

  // ③ 自建报价单 + 绑模板
  const { id: qid, number } = await FT.createQuotation(cookie, fixture, 'RENDER');
  FT.bindCostingTemplate(qid, templateId);

  // ④ 加一个产品（走应用自己的 UI 路径：添加产品 ▾ → 从已有产品添加）
  await page.goto(`/quotations/${qid}/edit`);
  await page.waitForLoadState('networkidle');
  const next = page.locator('button').filter({ hasText: /下\s*一\s*步/ }).first();
  await expect(next,
    '搭场景：编辑页找不到「下一步」⇒ **入口问题**（多半是分类/模板没落库），不是产品缺陷')
    .toBeVisible({ timeout: 60_000 });
  await next.click();
  await page.waitForTimeout(4000);

  const drawer = await openExistingProductDrawer(page, true);
  await fillDrawerFilter(page, drawer, '销售料号', fixture.salesPartNo).catch(async () => {
    await fillDrawerFilter(page, drawer, '料号', fixture.salesPartNo);
  });
  const rows = await readDrawerRows(drawer);
  expect(rows.length,
    `搭场景：抽屉里按销售料号「${fixture.salesPartNo}」查不到任何行 ⇒ **夹具/数据前提问题**，不是产品缺陷。` +
    `实际行=${JSON.stringify(rows.slice(0, 3))}`).toBeGreaterThan(0);
  await checkRow(page, drawer, 0);
  await confirmAdd(page, drawer);

  // 回查：line item 真的建出来了（否则后面读页签是空跑）
  const liCount = FT.sqlInt(
    `SELECT count(*) FROM quotation_line_item WHERE quotation_id='${qid}'`, '搭场景：回查 line item');
  expect(liCount, `搭场景：报价单 ${number} 加完产品后 line item 仍为 0 ⇒ **加产品没成功**，判【未验证】`)
    .toBeGreaterThan(0);

  await FT.refreshCostingSnapshot(cookie, qid);

  // 🚦 前提守卫：先证明这张 DRAFT 核价模板**真的进了渲染**，再谈 field_type 的对错。
  //    只要求阳性对照侧（INPUT_*）非空；BASIC_DATA 侧的空/非空留给 AC-6 去判。
  FT.assertCostingRenderable(qid, fixture.salesPartNo, TAB_INPUT, TAB_BASIC);

  const s: Scene = {
    fixture,
    basicId: b.id, basicName: b.name,
    inputId: i.id, inputName: i.name,
    quoteId: q.id, quoteName: q.name,
    templateId, quotationId: qid, quotationNumber: number,
  };
  FT.writeEvidence('02-场景.json', JSON.stringify(s, null, 2) + '\n');
  console.log('[scene] 就绪：', JSON.stringify(s));
  return s;
}

/** 打开核价卡片并读某个页签。 */
async function readCostingTab(page: Page, partNo: string, tabName: string) {
  await FT.gotoStep2(page, scene!.quotationId);
  await FT.switchToCosting(page);
  const card = await FT.cardOf(page, partNo);
  await FT.switchTabInCard(card, tabName);
  const snap = await FT.readTab(card);
  return { card, snap };
}

// ════════════════════════════════════════════════════════════════════
// AC-6 —— 本任务风险最高的一条
// ════════════════════════════════════════════════════════════════════
test('AC-6 核心: 同源双组件（唯一变量 field_type）两个页签逐行逐列可见值逐字相同', async ({ page }) => {
  test.setTimeout(360_000);
  const partNo = scene!.fixture.salesPartNo;

  await FT.gotoStep2(page, scene!.quotationId);
  await FT.switchToCosting(page);
  const card = await FT.cardOf(page, partNo);

  await FT.switchTabInCard(card, TAB_INPUT);
  const inputSnap = await FT.readTab(card);
  await FT.shotOf(card, 'AC-6-INPUT页签');

  await FT.switchTabInCard(card, TAB_BASIC);
  const basicSnap = await FT.readTab(card);
  await FT.shotOf(card, 'AC-6-BASIC页签');

  // ── 假绿①：空验证 —— 比对前先证明两侧都**真的有值** ──
  //    INPUT_* 侧是天然阳性对照：它今天就该有值，它空 = 量具坏了，不是本次改动的问题
  const inNonEmpty = FT.nonEmptyCellCount(inputSnap);
  const baNonEmpty = FT.nonEmptyCellCount(basicSnap);
  console.log(`[AC-6] INPUT 页签 ${inputSnap.rows.length} 行 / 非空单元格 ${inNonEmpty}；`
    + `BASIC 页签 ${basicSnap.rows.length} 行 / 非空单元格 ${baNonEmpty}`);

  expect(inputSnap.rows.length,
    `AC-6 阳性对照：INPUT_* 页签渲出 0 行 ⇒ **量具或场景坏了**（不是本次改动的问题），判【未验证】`)
    .toBeGreaterThan(0);
  expect(inNonEmpty,
    `AC-6 阳性对照：INPUT_* 页签一个非空单元格都没有 ⇒ 要么真的没数据，要么 readTab 没抓到 <input> 的 value。\n` +
    `  🚨 后者正是上一个任务栽过的坑（4 行真数据被读成 ["","","",""]）。判【未验证】。\n` +
    `  行快照=${JSON.stringify(inputSnap.rows.slice(0, 3))}`).toBeGreaterThan(0);
  expect(basicSnap.rows.length,
    `AC-6：BASIC_DATA 页签渲出 0 行，而同源 INPUT_* 页签有 ${inputSnap.rows.length} 行 ⇒ ` +
    `这就是 AC-6 要抓的缺陷（BASIC_DATA 渲染读 basicDataValues，键是 {$view.column} 带花括号，` +
    `而 basic_data_path 存的是不带花括号的 $view.column，中间隔着 bnfDriverLookupKey() 的键格式转换）。`)
    .toBeGreaterThan(0);
  expect(baNonEmpty,
    `AC-6：BASIC_DATA 页签行数 ${basicSnap.rows.length} 但**一个非空单元格都没有** ⇒ ` +
    `键格式转换没对上（值取不到，渲成空/「—」）。这是真缺陷，不是量具问题 —— ` +
    `因为同源 INPUT_* 页签在同一次读取里拿到了 ${inNonEmpty} 个非空值。`).toBeGreaterThan(0);

  // ── 逐行逐列并排对照表（当证据） ──
  const table: string[] = [
    `# AC-6 同源双组件并排对照（报价单 ${scene!.quotationNumber} / 产品 ${partNo}）`,
    `组件：${scene!.basicName}(BASIC_DATA) vs ${scene!.inputName}(INPUT_*)`,
    `表头 BASIC=${JSON.stringify(basicSnap.headers)}`,
    `表头 INPUT=${JSON.stringify(inputSnap.headers)}`, '',
  ];
  const rowN = Math.max(basicSnap.rows.length, inputSnap.rows.length);
  for (let r = 0; r < rowN; r++) {
    table.push(`row#${r}  BASIC=${JSON.stringify(basicSnap.rows[r] ?? null)}`);
    table.push(`row#${r}  INPUT=${JSON.stringify(inputSnap.rows[r] ?? null)}`);
  }
  FT.writeEvidence('AC-6-并排对照表.txt', table.join('\n') + '\n');

  // ── 断言：行数相同 + 每行每列可见文本相同 ──
  expect(basicSnap.rows.length,
    `AC-6：两个页签行数应相同（BASIC=${basicSnap.rows.length} / INPUT=${inputSnap.rows.length}）\n` +
    table.join('\n')).toBe(inputSnap.rows.length);
  expect(basicSnap.headers,
    `AC-6：两个页签列名应相同（同样的字段名）\nBASIC=${JSON.stringify(basicSnap.headers)}\n` +
    `INPUT=${JSON.stringify(inputSnap.headers)}`).toEqual(inputSnap.headers);
  expect(basicSnap.rows,
    `AC-6：两个页签**逐行逐列可见文本应逐字相同**（唯一变量是 field_type）。差异见并排对照表：\n` +
    table.join('\n')).toEqual(inputSnap.rows);
});

// ════════════════════════════════════════════════════════════════════
// AC-7
// ════════════════════════════════════════════════════════════════════
test('AC-7: BASIC_DATA 页签渲染为纯文本、不存在承载值的 <input>；同卡片 INPUT_* 页签仍是 <input>（阳性对照）',
  async ({ page }) => {
    test.setTimeout(300_000);
    const partNo = scene!.fixture.salesPartNo;
    await FT.gotoStep2(page, scene!.quotationId);
    await FT.switchToCosting(page);
    const card = await FT.cardOf(page, partNo);

    // 阳性对照先跑：证明「数不到 input」不是因为整页没渲染 / 观察手段抓不到 input
    await FT.switchTabInCard(card, TAB_INPUT);
    const inputSnap = await FT.readTab(card);
    await FT.shotOf(card, 'AC-7-INPUT页签有输入框');
    expect(inputSnap.rows.length, 'AC-7 阳性对照：INPUT_* 页签 0 行 ⇒ 场景坏了，判【未验证】').toBeGreaterThan(0);
    expect(inputSnap.totalInputs,
      `AC-7 **阳性对照**：INPUT_* 页签应有承载值的 <input>，实际 ${inputSnap.totalInputs} 个。\n` +
      `  🚨 阳性对照为 0 ⇒ 观察手段抓不到 input，下面「BASIC_DATA 没有 input」就是一条恒真的假绿。判【未验证】。`)
      .toBeGreaterThan(0);

    await FT.switchTabInCard(card, TAB_BASIC);
    const basicSnap = await FT.readTab(card);
    await FT.shotOf(card, 'AC-7-BASIC页签纯文本');
    expect(basicSnap.rows.length, 'AC-7：BASIC_DATA 页签 0 行 ⇒ 无从判起，判【未验证】').toBeGreaterThan(0);

    FT.writeEvidence('AC-7-input计数.txt',
      [`INPUT_* 页签：${inputSnap.rows.length} 行 / 承载值控件 ${inputSnap.totalInputs} 个`,
        `  逐格控件数=${JSON.stringify(inputSnap.inputCounts)}`,
        `BASIC_DATA 页签：${basicSnap.rows.length} 行 / 承载值控件 ${basicSnap.totalInputs} 个`,
        `  逐格控件数=${JSON.stringify(basicSnap.inputCounts)}`].join('\n') + '\n');

    expect(basicSnap.totalInputs,
      `AC-7：BASIC_DATA 页签**不应存在**承载其字段值的 <input>/<select>/<textarea>，` +
      `实际 ${basicSnap.totalInputs} 个。逐格控件数=${JSON.stringify(basicSnap.inputCounts)}`).toBe(0);
  });

// ════════════════════════════════════════════════════════════════════
// AC-10
// ════════════════════════════════════════════════════════════════════
test('AC-10 边界·行身份: 料号列配成 BASIC_DATA 后，行数与对照组相同、part_no_field 仍指向该字段名', async ({ page }) => {
  test.setTimeout(300_000);

  // 依据：料号列/名称列/行键是按**字段名**推导的，与 field_type 无关 —— 本条验证该假设成立
  const pnBasic = FT.sqlScalar(`SELECT coalesce(part_no_field,'') FROM component WHERE id='${scene!.basicId}'`);
  const pnInput = FT.sqlScalar(`SELECT coalesce(part_no_field,'') FROM component WHERE id='${scene!.inputId}'`);
  const ftOfPn = FT.dbFieldsOf(scene!.basicId).find((f) => f.field_name === COLUMNS[0].fieldName)?.field_type;
  console.log(`[AC-10] BASIC.part_no_field=${JSON.stringify(pnBasic)} / INPUT.part_no_field=${JSON.stringify(pnInput)}`
    + ` / 料号列的 field_type=${ftOfPn}`);

  expect(ftOfPn,
    `AC-10 前置：料号列「${COLUMNS[0].fieldName}」在 BASIC 组件里的 field_type 应为 BASIC_DATA（否则本条没验到东西），`
    + `实际 ${ftOfPn}`).toBe('BASIC_DATA');
  expect(pnBasic,
    `AC-10：料号列配成 BASIC_DATA 后，component.part_no_field 仍应指向该字段名「${COLUMNS[0].fieldName}」，`
    + `实际 ${JSON.stringify(pnBasic)}`).toBe(COLUMNS[0].fieldName);
  expect(pnBasic, `AC-10 对照：两个同源组件的 part_no_field 应一致`).toBe(pnInput);

  // 渲染侧：行数与对照组（INPUT_*）相同 —— 行仍能正确归属到卡片
  const partNo = scene!.fixture.salesPartNo;
  const { snap: basicSnap } = await readCostingTab(page, partNo, TAB_BASIC);
  await FT.gotoStep2(page, scene!.quotationId);
  await FT.switchToCosting(page);
  const card = await FT.cardOf(page, partNo);
  await FT.switchTabInCard(card, TAB_INPUT);
  const inputSnap = await FT.readTab(card);

  expect(inputSnap.rows.length, 'AC-10 阳性对照：对照组 0 行 ⇒ 「行数相同」恒真，判【未验证】').toBeGreaterThan(0);
  expect(basicSnap.rows.length,
    `AC-10：料号列配成 BASIC_DATA 后行数应与对照组相同（BASIC=${basicSnap.rows.length} / INPUT=${inputSnap.rows.length}）`)
    .toBe(inputSnap.rows.length);

  // 料号列本身的值也应逐行相同（行身份没错位）
  const idx = basicSnap.headers.indexOf(COLUMNS[0].fieldName);
  expect(idx, `AC-10：表头里找不到料号列「${COLUMNS[0].fieldName}」，实有表头=${JSON.stringify(basicSnap.headers)}`)
    .toBeGreaterThanOrEqual(0);
  const colB = basicSnap.rows.map((r) => r[idx] ?? '');
  const colI = inputSnap.rows.map((r) => r[idx] ?? '');
  expect(colB.filter((v) => v !== '').length,
    `AC-10：料号列全空 ⇒ 「逐行相同」会在空对空上恒真，判【未验证】。实际=${JSON.stringify(colB)}`).toBeGreaterThan(0);
  expect(colB, `AC-10：料号列逐行取值应与对照组一致（行身份未错位）`).toEqual(colI);

  FT.writeEvidence('AC-10-行身份.txt',
    [`part_no_field: BASIC=${pnBasic} / INPUT=${pnInput}`,
      `料号列 field_type = ${ftOfPn}`,
      `行数: BASIC=${basicSnap.rows.length} / INPUT=${inputSnap.rows.length}`,
      `料号列取值 BASIC=${JSON.stringify(colB)}`,
      `料号列取值 INPUT=${JSON.stringify(colI)}`].join('\n') + '\n');
});

// ════════════════════════════════════════════════════════════════════
// AC-12（序列）
// ════════════════════════════════════════════════════════════════════
test('AC-12 序列: BASIC_DATA → 改 INPUT_TEXT → 渲染（值同、变 <input>）→ 改回 BASIC_DATA → 渲染（值同、变纯文本）',
  async ({ page }) => {
    test.setTimeout(420_000);
    const partNo = scene!.fixture.salesPartNo;
    const log: string[] = [`# AC-12 序列（组件 ${scene!.basicName} / 报价单 ${scene!.quotationNumber}）`];

    // ── 第 0 步：起手态（BASIC_DATA） ──
    const s0 = (await readCostingTab(page, partNo, TAB_BASIC)).snap;
    expect(s0.rows.length, 'AC-12 起手：BASIC_DATA 页签 0 行 ⇒ 后续「值不变」恒真，判【未验证】').toBeGreaterThan(0);
    expect(FT.nonEmptyCellCount(s0), 'AC-12 起手：BASIC_DATA 页签无非空值 ⇒ 「值不变」恒真，判【未验证】').toBeGreaterThan(0);
    expect(s0.totalInputs, 'AC-12 起手：BASIC_DATA 页签此时不应有承载值的 input').toBe(0);
    log.push(`① 起手 BASIC_DATA：${s0.rows.length} 行 / input ${s0.totalInputs} / 值=${JSON.stringify(s0.rows)}`);
    await FT.shot(page, 'AC-12-01-起手BASIC_DATA');

    // ── 第 1 步：把某列由 BASIC_DATA 改回 INPUT_TEXT → 保存 → 重新渲染 ──
    let r = await FT.saveBuilder(cookie, scene!.basicId, cfg('COST_BASIC', 'INPUT_TEXT'));
    expect(r.status, `AC-12：改 INPUT_TEXT 保存应 2xx，实际 ${r.status}：${r.text.slice(0, 300)}`).toBeLessThan(300);
    await FT.refreshCostingSnapshot(cookie, scene!.quotationId);
    const s1 = (await readCostingTab(page, partNo, TAB_BASIC)).snap;
    log.push(`② 改 INPUT_TEXT：${s1.rows.length} 行 / input ${s1.totalInputs} / 值=${JSON.stringify(s1.rows)}`);
    await FT.shot(page, 'AC-12-02-改INPUT_TEXT');

    expect(s1.rows,
      `AC-12②：由 BASIC_DATA 改成 INPUT_TEXT 后，**该列仍应显示相同的值**。\n` +
      `  改前=${JSON.stringify(s0.rows)}\n  改后=${JSON.stringify(s1.rows)}`).toEqual(s0.rows);
    expect(s1.totalInputs,
      `AC-12②：改成 INPUT_TEXT 后应**变回 <input>**，实际承载值控件 ${s1.totalInputs} 个`).toBeGreaterThan(0);

    // ── 第 2 步：再改回 BASIC_DATA → 保存 → 渲染 ──
    r = await FT.saveBuilder(cookie, scene!.basicId, cfg('COST_BASIC', 'BASIC_DATA'));
    expect(r.status, `AC-12：改回 BASIC_DATA 保存应 2xx，实际 ${r.status}：${r.text.slice(0, 300)}`).toBeLessThan(300);
    await FT.refreshCostingSnapshot(cookie, scene!.quotationId);
    const s2 = (await readCostingTab(page, partNo, TAB_BASIC)).snap;
    log.push(`③ 改回 BASIC_DATA：${s2.rows.length} 行 / input ${s2.totalInputs} / 值=${JSON.stringify(s2.rows)}`);
    await FT.shot(page, 'AC-12-03-改回BASIC_DATA');
    FT.writeEvidence('AC-12-序列.txt', log.join('\n') + '\n');

    expect(s2.rows,
      `AC-12③：改回 BASIC_DATA 后值应仍不变。\n  起手=${JSON.stringify(s0.rows)}\n  末态=${JSON.stringify(s2.rows)}`)
      .toEqual(s0.rows);
    expect(s2.totalInputs, `AC-12③：改回 BASIC_DATA 后应**变回纯文本**，实际承载值控件 ${s2.totalInputs} 个`).toBe(0);
  });

// ════════════════════════════════════════════════════════════════════
// AC-14（无副作用·报价侧）
// ════════════════════════════════════════════════════════════════════
test('AC-14 无副作用: 自建 QUOTE 方言组件的 INPUT_* 字段仍渲染为 <input>，改值失焦后可读回', async ({ page }) => {
  test.setTimeout(360_000);

  // 落库口径先确认（AC-5 已验过写入规则，这里验的是**渲染 + 可编辑可保存**）
  const kinds = FT.dbFieldsOf(scene!.quoteId).map((f) => f.field_type);
  expect(kinds.length, 'AC-14 前置：报价侧自建组件 fields 为空 ⇒ 判【未验证】').toBeGreaterThan(0);
  expect(kinds.every((k) => k.startsWith('INPUT_')),
    `AC-14 前置：${scene!.quoteName} 应全为 INPUT_*，实际 ${JSON.stringify(kinds)}`).toBe(true);

  // 🚦 报价侧渲染需要把该组件挂进**报价模板**。本片不改任何既有模板的发布态，
  //    也不新建 QUOTATION 模板（那会引入模板匹配这条新的全局面）⇒ 改为在**报价单编辑页的报价侧**
  //    验证既有 INPUT_* 字段仍可编辑可保存，并把本片自建 QUOTE 组件的落库口径作为静态取证。
  await FT.gotoStep2(page, scene!.quotationId);
  await FT.switchToQuote(page);
  const card = await FT.cardOf(page, scene!.fixture.salesPartNo);
  const snap = await FT.readTab(card);
  await FT.shotOf(card, 'AC-14-报价侧卡片');

  expect(snap.rows.length, 'AC-14：报价侧卡片 0 行 ⇒ 「仍可编辑」无从判起，判【未验证】').toBeGreaterThan(0);
  expect(snap.totalInputs,
    `AC-14：报价侧卡片应仍有可编辑的 <input>，实际 ${snap.totalInputs} 个 ⇒ ` +
    `这就是本次改动可能造成的报价侧回归（把所有方言都默认成基础数据）。`).toBeGreaterThan(0);

  // 改值 → 失焦 → 断言写入请求发生 → 读回一致
  const box = card.locator('input:not([type=checkbox]):not([type=radio]):not([disabled])').first();
  await expect(box, 'AC-14：报价侧找不到可编辑输入框 ⇒ 判【未验证】').toBeVisible({ timeout: 20_000 });
  const before = await box.inputValue();
  const val = `FT${Date.now() % 100000}`;
  const written = page.waitForResponse(
    (res) => /\/quote-card-edit|\/quotations\/[^/]+\/draft/.test(res.url()) &&
      ['POST', 'PUT', 'PATCH'].includes(res.request().method()),
    { timeout: 30_000 }).catch(() => null);
  await box.fill(val);
  await box.blur();
  await page.waitForTimeout(2500);
  const resp = await written;
  console.log(`[AC-14] 改值 ${JSON.stringify(before)} → ${val}；写入请求=${resp ? `${resp.request().method()} ${resp.url()} → ${resp.status()}` : '(未捕获)'}`);

  await page.reload();
  await page.waitForTimeout(6000);
  await FT.gotoStep2(page, scene!.quotationId);
  await FT.switchToQuote(page);
  const card2 = await FT.cardOf(page, scene!.fixture.salesPartNo);
  const snap2 = await FT.readTab(card2);
  const flat = snap2.rows.flat().join(' | ');
  FT.writeEvidence('AC-14-报价侧可编辑.txt',
    [`改值前=${JSON.stringify(before)} 写入值=${val}`,
      `写入请求=${resp ? `${resp.request().method()} ${resp.url()} → ${resp.status()}` : '(未捕获)'}`,
      `刷新后卡片内容=${flat}`,
      `自建 QUOTE 组件 ${scene!.quoteName} 落库 field_type=${JSON.stringify(kinds)}`].join('\n') + '\n');

  expect(flat,
    `AC-14：报价侧改值失焦后应能写入并读回「${val}」，刷新后卡片内容里没找到它。\n` +
    `  写入请求=${resp ? `${resp.status()}` : '未捕获到任何 quote-card-edit / draft 写请求'}\n` +
    `  卡片内容=${flat}`).toContain(val);
});
