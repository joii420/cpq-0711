/**
 * repair-260910 · S-只读片 · 详情页卡片头部对齐编辑页
 *
 * 断言全部派生自 `问题说明.md §⑥ AC-R1~AC-R9` 原文 + `原型图/详情页产品卡片.html`。
 * 🚫 未读 cpq-frontend/src/** 与 cpq-backend/src/main/**。
 * 🚫 零写库：本 spec 只跑 SELECT（sqlRO 硬拒非只读），不造数、不清理、不改全局状态。
 *
 * 入口：报价单**详情页** /quotations/{id}（🚫 不是 /edit）。
 *
 * 夹具（2026-09-10 执行时 re-verify，见每条用例开头的前置断言）：
 *   QT-20260909-0799  CUST-0004 正泰  1 张卡  A002/S0001  → AC-R1/R2/R3
 *   QT-20260909-0794  CUST-0004 正泰  5 张卡              → AC-R4（card1=S0004 无核价明细 / card4=0028-2609000014 未绑定）
 *                                                          + AC-R8（唯一有 costing_card_template_id 的夹具单）
 *   QT-20260907-0592  CUST-0001 罗克韦尔 customer_part_no IS NULL 且客户料号表无匹配 → AC-R5
 */
import { test, expect } from '@playwright/test';
import * as H from './r260910.helpers';

const Q_MAIN = 'QT-20260909-0799';
const Q_MULTI = 'QT-20260909-0794';
const Q_NOLEFT = 'QT-20260907-0592';

/** AC-R3 的五行期望：来自 ds_quote_material → ds_cost_basic_material，执行时实查 */
function expectedFiveOf(customerNo: string, materialNo: string) {
  const m = H.sqlRows(
    `SELECT production_no FROM ds_quote_material WHERE customer_no='${customerNo}' AND material_no='${materialNo}'`)[0];
  const prod = (m?.production_no || '').trim();
  if (!prod) return { prod: '', detail: null as any };
  const d = H.sqlRows(
    `SELECT production_no, material_name, specification, dimension, old_material_no
       FROM ds_cost_basic_material WHERE production_no='${prod}'`)[0] || null;
  return { prod, detail: d };
}
const dash = (v?: string) => (v && v.trim() !== '' ? v.trim() : '—');

test.beforeAll(async () => {
  await H.assertWorktreeAndDb();
});

// ───────────────────────────── AC-R1 / AC-R2 / AC-R3 ─────────────────────────────
test('R1+R2+R3 · 详情页头部左右分区 + 销售料号浮层（QT-20260909-0799）', async ({ page }) => {
  // 前置：实查夹具，缺了就判【未验证】而不是让断言空跑
  const li = H.sqlRows(
    `SELECT li.sort_order, li.customer_part_no, li.product_part_no_snapshot ppn, li.product_name_snapshot pns,
            (SELECT p.customer_part_name FROM ds_quote_customer_part p
               WHERE p.customer_no='CUST-0004' AND p.customer_product_no=li.customer_part_no LIMIT 1) cpname
       FROM quotation_line_item li JOIN quotation q ON q.id=li.quotation_id
      WHERE q.quotation_number='${Q_MAIN}' ORDER BY li.sort_order`);
  expect(li.length, `前置未满足：${Q_MAIN} 没有行项目 ⇒ 断言会空跑，判【未验证】`).toBe(1);
  expect(li[0].cpname, `前置未满足：${Q_MAIN} 客户料号名称在库里不是「示例客户料号」而是「${li[0].cpname}」`).toBe('示例客户料号');
  expect(li[0].customer_part_no).toBe('A002');
  expect(li[0].ppn).toBe('S0001');
  expect(li[0].pns).toBe('铆钉');

  const five = expectedFiveOf('CUST-0004', 'S0001');
  expect(five.prod, `前置未满足：ds_quote_material(CUST-0004,S0001).production_no 为空 ⇒ AC-R3 基准变了`).toBe('300001');
  expect(five.detail, `前置未满足：ds_cost_basic_material(300001) 无行 ⇒ AC-R3 基准变了`).not.toBeNull();

  const errs = H.trapErrors(page);
  await H.uiLogin(page);
  await H.openDetail(page, Q_MAIN);
  const cards = await H.readCards(page);
  console.log('[R1/R2 详情页卡片] ' + JSON.stringify(cards, null, 1));
  expect(cards.length, `${Q_MAIN} 详情页应有 1 张卡片，实际 ${cards.length}`).toBe(1);
  const c = cards[0];
  expect(c.cardHeadText, `定位失败：第 0 张卡不是 S0001 那张（实际前 200 字=${c.cardHeadText}）`).toContain('S0001');

  // ── AC-R1：左块 = ["示例客户料号","客户产品编号: A002"]，且不得再出现「料号: S0001」
  expect(c.leftTexts, `AC-R1 左块逐项应为 ["示例客户料号","客户产品编号: A002"]，实际 ${JSON.stringify(c.leftTexts)}`)
    .toEqual(['示例客户料号', '客户产品编号: A002']);
  expect(/料号\s*[:：]\s*S0001/.test(c.leftRaw),
    `AC-R1 左块**不得**再出现「料号: S0001」旧徽标，实际左块原文=「${c.leftRaw}」`).toBe(false);

  // ── AC-R2：右块存在、内容 = ["铆钉","销售料号: S0001"]、无任何按钮
  expect(c.hasRight, `AC-R2 右块 .qt-card-header-right 必须存在（当前详情页无右块 = 本次要修的现象）`).toBe(true);
  expect(c.rightTexts, `AC-R2 右块逐项应为 ["铆钉","销售料号: S0001"]，实际 ${JSON.stringify(c.rightTexts)}`)
    .toEqual(['铆钉', '销售料号: S0001']);
  expect(c.rightButtonCount, `AC-R2/E-7 详情页右块不得有任何按钮，实际 ${c.rightButtonCount} 个：${c.rightRaw}`).toBe(0);
  expect(c.rightRaw.includes('删除'), `AC-R2/E-7 右块不得出现「删除」，实际右块原文=「${c.rightRaw}」`).toBe(false);
  expect(c.domOrderOk, 'AC-R2 DOM 顺序应为 左块在前、右块在后').toBe(true);

  await H.shot(page, 'R1R2-详情页头部-0799');

  // ── AC-R3：点右块销售料号徽标 → 生产料号浮层
  const pop = await H.openPopover(page, 0);
  console.log('[R3 浮层原文]\n' + pop.raw);
  expect(pop.badgeText, `AC-R3 点的应是右块「销售料号: S0001」徽标，实际点到「${pop.badgeText}」`).toBe('销售料号: S0001');
  H.expectPopoverFive(pop, {
    料号: five.detail.production_no,
    名称: dash(five.detail.material_name),
    规格: dash(five.detail.specification),
    尺寸: dash(five.detail.dimension),
    旧料号: dash(five.detail.old_material_no),
  }, 'AC-R3');
  // 逐字对照 AC 原文写死的期望（防「实查值恰好也漂了」）
  H.expectPopoverFive(pop, { 料号: '300001', 名称: '零件1', 规格: '—', 尺寸: '3.5×3.5×0.6', 旧料号: '8DLX.550.653' }, 'AC-R3 逐字');
  const s0001Count = (pop.raw.match(/S0001/g) || []).length;
  expect(s0001Count, `AC-R3 浮层内「S0001」应出现 0 次，实际 ${s0001Count} 次\n原文=${pop.raw}`).toBe(0);

  await H.shot(page, 'R3-浮层-0799');
  H.writeEvidence('R1R2R3-0799.txt',
    `单=${Q_MAIN}\nDB前置=${JSON.stringify(li)}\n浮层数据源=${JSON.stringify(five)}\n卡片=${JSON.stringify(cards, null, 1)}\n浮层原文=\n${pop.raw}\npageerror=${JSON.stringify(errs)}\n`);
  expect(errs, `AC-R1~R3 页面不应有 pageerror：${JSON.stringify(errs)}`).toEqual([]);
});

// ───────────────────────────── AC-R4 两种降级 ─────────────────────────────
test('R4 · 两种降级：未绑定生产料号 / 核价侧无明细（QT-20260909-0794）', async ({ page }) => {
  const rows = H.sqlRows(
    `SELECT li.sort_order, li.product_part_no_snapshot ppn,
            (SELECT m.production_no FROM ds_quote_material m
               WHERE m.customer_no='CUST-0004' AND m.material_no=li.product_part_no_snapshot LIMIT 1) prod
       FROM quotation_line_item li JOIN quotation q ON q.id=li.quotation_id
      WHERE q.quotation_number='${Q_MULTI}' ORDER BY li.sort_order`);
  expect(rows.length, `前置未满足：${Q_MULTI} 行项目数变了（实际 ${rows.length}）`).toBe(5);
  const unbound = rows.find((r) => r.ppn === '0028-2609000014');
  const noDetail = rows.find((r) => r.ppn === 'S0004');
  expect(unbound, `前置未满足：${Q_MULTI} 里找不到未绑定样本 0028-2609000014`).toBeTruthy();
  expect((unbound!.prod || '').trim(), `前置未满足：0028-2609000014 现在**有**生产料号「${unbound!.prod}」⇒ 不再是「未绑定」样本`).toBe('');
  expect(noDetail, `前置未满足：${Q_MULTI} 里找不到 S0004`).toBeTruthy();
  expect(noDetail!.prod, `前置未满足：S0004 的 production_no 变了`).toBe('300021');
  const detailCnt = Number(H.sqlScalar(`SELECT count(*) FROM ds_cost_basic_material WHERE production_no='300021'`));
  expect(detailCnt, `前置未满足：300021 现在**有**核价明细（${detailCnt} 行）⇒ 不再是「核价侧无明细」样本`).toBe(0);

  const errs = H.trapErrors(page);
  await H.uiLogin(page);
  await H.openDetail(page, Q_MULTI);
  const cards = await H.readCards(page);
  console.log('[R4 卡片] ' + JSON.stringify(cards.map((c) => ({ i: c.idx, r: c.rightTexts, l: c.leftTexts })), null, 1));
  expect(cards.length, `${Q_MULTI} 详情页应有 5 张卡片，实际 ${cards.length}`).toBe(5);

  // 按右块销售料号徽标文案定位卡片（🚫 不靠下标猜；表格里也有料号，只在头部找）
  const idxOf = (ppn: string) => cards.findIndex((c) => c.rightTexts.some((t) => t === `销售料号: ${ppn}`));
  const iNoDetail = idxOf('S0004');
  const iUnbound = idxOf('0028-2609000014');
  expect(iNoDetail, `AC-R4 找不到右块徽标「销售料号: S0004」的卡片；实际右块=${JSON.stringify(cards.map((c) => c.rightTexts))}`).toBeGreaterThanOrEqual(0);
  expect(iUnbound, `AC-R4 找不到右块徽标「销售料号: 0028-2609000014」的卡片；实际右块=${JSON.stringify(cards.map((c) => c.rightTexts))}`).toBeGreaterThanOrEqual(0);

  // 降级 ②：核价侧无明细 → 首行有值，其余四行「—」
  const popB = await H.openPopover(page, iNoDetail);
  console.log('[R4-b S0004 浮层]\n' + popB.raw);
  H.expectPopoverFive(popB, { 料号: '300021', 名称: '—', 规格: '—', 尺寸: '—', 旧料号: '—' }, 'AC-R4 核价侧无明细');
  await H.shot(page, 'R4b-浮层-S0004-核价无明细');

  // 降级 ①：未绑定生产料号 → 徽标仍可点，浮层单行「未绑定生产料号」
  const popA = await H.openPopover(page, iUnbound);
  console.log('[R4-a 0028-2609000014 浮层]\n' + popA.raw);
  expect(popA.title, `AC-R4 未绑定：浮层标题应为「生产料号」，实际「${popA.title}」\n原文=${popA.raw}`).toBe('生产料号');
  expect(popA.raw.includes('未绑定生产料号'), `AC-R4 未绑定：浮层应显示「未绑定生产料号」，实际原文=${popA.raw}`).toBe(true);
  for (const k of ['名称', '规格', '尺寸', '旧料号']) {
    expect(popA.raw.includes(k), `AC-R4 未绑定：浮层应是**单行**文案，不应出现「${k}」行\n原文=${popA.raw}`).toBe(false);
  }
  await H.shot(page, 'R4a-浮层-未绑定');

  H.writeEvidence('R4-降级-0794.txt',
    `单=${Q_MULTI}\nDB前置=${JSON.stringify(rows)}\n300021核价明细行数=${detailCnt}\n` +
    `S0004 卡片#${iNoDetail} 浮层=\n${popB.raw}\n\n0028-2609000014 卡片#${iUnbound} 浮层=\n${popA.raw}\npageerror=${JSON.stringify(errs)}\n`);
  expect(errs, `AC-R4 页面不应有 pageerror：${JSON.stringify(errs)}`).toEqual([]);
});

// ───────────────────────────── AC-R5 左块整块不渲染 ─────────────────────────────
test('R5 · 客户料号两路都取不到 → 左块 childElementCount = 0，右块仍正常（QT-20260907-0592）', async ({ page }) => {
  const li = H.sqlRows(
    `SELECT li.sort_order, li.customer_part_no, li.product_part_no_snapshot ppn, li.product_name_snapshot pns
       FROM quotation_line_item li JOIN quotation q ON q.id=li.quotation_id
      WHERE q.quotation_number='${Q_NOLEFT}' ORDER BY li.sort_order`);
  expect(li.length, `前置未满足：${Q_NOLEFT} 行项目数变了`).toBe(1);
  expect((li[0].customer_part_no || '').trim(), `前置未满足：${Q_NOLEFT} 的 customer_part_no 现在**有值**「${li[0].customer_part_no}」⇒ 不再是本 AC 的样本`).toBe('');
  const byMat = Number(H.sqlScalar(
    `SELECT count(*) FROM ds_quote_customer_part p JOIN customer c ON c.code=p.customer_no
      WHERE c.code='CUST-0001' AND p.material_no='${li[0].ppn}'`));
  expect(byMat, `前置未满足：客户料号表现在能按 material_no=${li[0].ppn} 命中 ${byMat} 行 ⇒ 左块本就该有内容，不再是本 AC 的样本`).toBe(0);

  const errs = H.trapErrors(page);
  await H.uiLogin(page);
  await H.openDetail(page, Q_NOLEFT);
  const cards = await H.readCards(page);
  console.log('[R5 卡片] ' + JSON.stringify(cards, null, 1));
  expect(cards.length, `${Q_NOLEFT} 详情页应有 1 张卡片`).toBe(1);
  const c = cards[0];

  expect(c.leftChildCount,
    `AC-R5 左块应**整块不渲染**（childElementCount=0，不是渲染成「—」或空徽标）；实际 ${c.leftChildCount}，内容=${JSON.stringify(c.leftTexts)}`)
    .toBe(0);
  expect(c.hasRight, 'AC-R5 右块仍应存在').toBe(true);
  expect(c.rightTexts.length, `AC-R5 右块应正常显示 [产品名][销售料号] 两项，实际 ${JSON.stringify(c.rightTexts)}`).toBe(2);
  expect(c.rightTexts[1], `AC-R5 右块销售料号应为「销售料号: ${li[0].ppn}」，实际「${c.rightTexts[1]}」`).toBe(`销售料号: ${li[0].ppn}`);
  expect(c.rightTexts[0].length, `AC-R5 右块产品名不应为空（回退链要兜住），实际 ${JSON.stringify(c.rightTexts)}`).toBeGreaterThan(0);
  expect(c.rightButtonCount, `AC-R5/E-7 右块不得有按钮，实际 ${c.rightButtonCount}`).toBe(0);

  await H.shot(page, 'R5-左块空-0592');
  H.writeEvidence('R5-左块空-0592.txt',
    `单=${Q_NOLEFT}\nDB前置=${JSON.stringify(li)}\n按material_no命中客户料号行数=${byMat}\n卡片=${JSON.stringify(cards, null, 1)}\npageerror=${JSON.stringify(errs)}\n`);
  expect(errs, `AC-R5 页面不应有 pageerror：${JSON.stringify(errs)}`).toEqual([]);
});

// ───────────────────────────── AC-R7 产品名回退链 ─────────────────────────────
test('R7 · 产品名回退链未退化（customerPartName 空 + attrValues[产品名称] 有值）', async ({ page }) => {
  const cnt = Number(H.sqlScalar(
    `SELECT count(*) FROM quotation_line_item WHERE COALESCE(product_attribute_values->>'产品名称','')<>''`));
  const anyAttr = Number(H.sqlScalar(
    `SELECT count(*) FROM quotation_line_item WHERE product_attribute_values IS NOT NULL AND product_attribute_values <> '{}'::jsonb`));
  H.writeEvidence('R7-恒空样本.txt',
    `执行时实查（${new Date().toISOString()}）：\n` +
    `  quotation_line_item 中 product_attribute_values->>'产品名称' 非空的行数 = ${cnt}\n` +
    `  quotation_line_item 中 product_attribute_values 非空对象的行数 = ${anyAttr}\n` +
    `⇒ 全库没有满足 AC-R7 前置（customerPartName 空 + attrValues['产品名称'] 有值）的样本。\n` +
    `⇒ 本条判【未验证 · 恒空样本】（testing.md §5.5 ③）。造数属写库，S-只读片无批准权 ⇒ 停下报主线。\n`);
  console.log(`[R7] 产品名称 attr 非空行数=${cnt}，任意 attr 非空行数=${anyAttr}`);
  test.skip(cnt === 0, `AC-R7 前置数据不存在（全库 0 行）⇒ 判【未验证·恒空样本】，已写证据并报主线；🚫 不造数（S-只读片零写库）`);

  // 真有样本时才跑到这里
  const s = H.sqlRows(
    `SELECT q.quotation_number qn, cu.code cust, li.sort_order,
            li.product_attribute_values->>'产品名称' pn, li.product_part_no_snapshot ppn
       FROM quotation_line_item li JOIN quotation q ON q.id=li.quotation_id LEFT JOIN customer cu ON cu.id=q.customer_id
      WHERE COALESCE(li.product_attribute_values->>'产品名称','')<>''
      ORDER BY q.created_at DESC LIMIT 1`)[0];
  await H.uiLogin(page);
  await H.openDetail(page, s.qn);
  const cards = await H.readCards(page);
  const c = cards[Number(s.sort_order)];
  expect(c.rightTexts[0], `AC-R7 右块产品名应显示 attrValues['产品名称'] = 「${s.pn}」，实际 ${JSON.stringify(c.rightTexts)}`).toBe(s.pn);
  await H.shot(page, 'R7-回退链');
});

// ───────────────────────────── AC-R8 核价单详情页 ─────────────────────────────
test('R8 · 核价单详情页同步生效且不塌（QT-20260909-0794）', async ({ page }) => {
  const tpl = H.sqlScalar(`SELECT COALESCE(costing_card_template_id::text,'') FROM quotation WHERE quotation_number='${Q_MULTI}'`);
  expect(tpl, `前置未满足：${Q_MULTI} 没有 costing_card_template_id ⇒ 切不到核价单视图，判【未验证】`).toMatch(/^[0-9a-f-]{36}$/);

  const errs = H.trapErrors(page);
  await H.uiLogin(page);
  await H.openDetail(page, Q_MULTI);

  const costingBtn = page.locator('.ant-segmented-item').filter({ hasText: '核价单' }).first();
  await expect(costingBtn, 'AC-R8 详情页应有「核价单」视图切换（.ant-segmented-item）⇒ 找不到属**入口问题**').toBeVisible({ timeout: 30_000 });
  await costingBtn.click();
  await page.waitForTimeout(9000);

  const cards = await H.readCards(page);
  console.log('[R8 核价卡片] ' + JSON.stringify(cards.map((c) => ({ i: c.idx, l: c.leftTexts, r: c.rightTexts, btn: c.rightButtonCount })), null, 1));
  expect(cards.length, `AC-R8 核价单视图应渲染出产品卡片，实际 0 张 ⇒ 断言会空跑/视图塌了`).toBeGreaterThan(0);
  for (const c of cards) {
    expect(c.hasRight, `AC-R8 核价单卡片 #${c.idx} 缺右块 .qt-card-header-right（头部未左右分区）`).toBe(true);
    expect(c.rightButtonCount, `AC-R8/E-7 核价单卡片 #${c.idx} 右块不得有按钮，实际 ${c.rightButtonCount}`).toBe(0);
    expect(c.rightTexts.length, `AC-R8 核价单卡片 #${c.idx} 右块应有内容，实际 ${JSON.stringify(c.rightTexts)}`).toBeGreaterThan(0);
  }
  // 首张卡（S0001）逐字对齐报价侧
  const c0 = cards[0];
  expect(c0.leftTexts, `AC-R8 核价单首卡左块应为 ["示例客户料号","客户产品编号: A002"]，实际 ${JSON.stringify(c0.leftTexts)}`)
    .toEqual(['示例客户料号', '客户产品编号: A002']);
  expect(c0.rightTexts, `AC-R8 核价单首卡右块应为 ["铆钉","销售料号: S0001"]，实际 ${JSON.stringify(c0.rightTexts)}`)
    .toEqual(['铆钉', '销售料号: S0001']);

  // 页签正常渲染 + 无「加载中」残留
  const tabs = await page.locator('.qt-product-card button.qt-tab-btn').count();
  expect(tabs, `AC-R8 核价单卡片页签（button.qt-tab-btn）应 > 0，实际 ${tabs} ⇒ 页签未渲染`).toBeGreaterThan(0);
  const loading = await page.locator('text=加载中').count();
  expect(loading, `AC-R8 核价单视图不应有「加载中」残留，实际 ${loading} 处`).toBe(0);

  await H.shot(page, 'R8-核价单详情页');
  H.writeEvidence('R8-核价单-0794.txt',
    `单=${Q_MULTI} costing_card_template_id=${tpl}\n卡片=${JSON.stringify(cards, null, 1)}\n页签数=${tabs} 加载中=${loading}\npageerror=${JSON.stringify(errs)}\n`);
  expect(errs, `AC-R8 核价单视图不应有 pageerror：${JSON.stringify(errs)}`).toEqual([]);
});

// ───────────────────────────── AC-R9 编辑页零回归 ─────────────────────────────
test('R9 · 编辑页零回归：Step2 卡片头部与主任务亲验结果逐字相同（QT-20260909-0799）', async ({ page }) => {
  const errs = H.trapErrors(page);
  await H.uiLogin(page);
  await H.openStep2(page, Q_MAIN);
  const cards = await H.readCards(page);
  console.log('[R9 编辑页卡片] ' + JSON.stringify(cards, null, 1));
  expect(cards.length, `AC-R9 编辑页 Step2 应有 1 张卡片，实际 ${cards.length}`).toBe(1);
  const c = cards[0];
  expect(c.leftTexts, `AC-R9 编辑页左块应为 ["示例客户料号","客户产品编号: A002"]，实际 ${JSON.stringify(c.leftTexts)}`)
    .toEqual(['示例客户料号', '客户产品编号: A002']);
  expect(c.rightTexts, `AC-R9 编辑页右块应为 ["铆钉","销售料号: S0001","删除"]，实际 ${JSON.stringify(c.rightTexts)}`)
    .toEqual(['铆钉', '销售料号: S0001', '删除']);

  const pop = await H.openPopover(page, 0);
  console.log('[R9 编辑页浮层]\n' + pop.raw);
  H.expectPopoverFive(pop, { 料号: '300001', 名称: '零件1', 规格: '—', 尺寸: '3.5×3.5×0.6', 旧料号: '8DLX.550.653' }, 'AC-R9 编辑页浮层');

  await H.shot(page, 'R9-编辑页Step2');
  H.writeEvidence('R9-编辑页-0799.txt',
    `单=${Q_MAIN}（编辑页 Step2）\n卡片=${JSON.stringify(cards, null, 1)}\n浮层原文=\n${pop.raw}\npageerror=${JSON.stringify(errs)}\n`);
});
