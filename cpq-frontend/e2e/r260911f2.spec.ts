/**
 * repair-260911 · F-2 开发自测：**AC-R2**（主）+ **AC-R4**（回归）
 *
 * AC-R2 原文（问题说明 §⑦ 现行版本）：
 *   前置：选配新建一个零件、挂 2 个材质，点「添加到报价单」。
 *   🚨 主断言在「点完成后 **不刷新**」那一帧：
 *     ① BOM 页签 `<tr>` 带 `data-node-id`；② 至少一行带缩进；③ 没有整行全空的行。
 *     ④（可选补充）刷新后仍成立 —— 回归护栏，🚫 不当主判据。
 *
 * AC-R4：合法空卡片（`{"tabs": []}`）行为逐字不变，且不无限转圈。
 */
import { test, expect } from '@playwright/test';
import * as H from './r260911f2.helpers';

const MAT_A = '00005';   // AgNi25C2（1 条 ACTIVE 含量配置）
const MAT_B = '00006';   // AgNi10 （1 条 ACTIVE 含量配置）
const TAB_BOM = 'BOM';   // 🔬 运行时探针确认：报价卡片的页签是 产品 / BOM / 材质元素 / 加工费 / 自制加工费（不是「物料BOM」）

test.describe.configure({ mode: 'serial' });

/**
 * 选配抽屉：新建零件 + 2 材质 + 提交。
 *
 * 真实结构（2026-09-11 运行时探针确认，🚫 不是我猜的）：
 *   宿主 4 步：① 客户产品编号 → ② 添加配件 → ③ 组合工序 → ④ 确认并添加
 *   ②「+ 添加第一个配件」打开的是**内层绝对定位面板**（`AddPartSubDrawer`，不是嵌套 Drawer），
 *     它自己还有两问：`type`（零件/外购件/直接绑定）→ `source`（新建零件/已有零件）→ `NewPartPanel`
 * ⚠️ 输入框一律按 **placeholder** 定位：label 是裸 `<label>` 不是 `.ant-form-item`，
 *    按 label 文本反查会撞到说明文字（首轮就靠这个才发现流程猜错了）。
 */
async function configureNewPartWith2Materials(page: any, partName: string, custProductNo: string) {
  const drawer = page.locator('.ant-drawer').last();
  const nextBtn = () => drawer.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).last();
  const dump = async (tag: string, n = 600) =>
    console.log(`[${tag}] ` + (await drawer.innerText()).replace(/\s+/g, ' ').slice(0, n));

  // ── 宿主 Step1 客户产品编号 ─────────────────────────────────────
  const cpn = drawer.locator('input').first();
  await expect(cpn, '选配第 1 步应有客户产品编号输入框').toBeVisible({ timeout: 40_000 });
  await cpn.fill(custProductNo);
  await page.waitForTimeout(1500);
  await expect(nextBtn(), '第 1 步「下一步」应可点').toBeEnabled({ timeout: 40_000 });
  await nextBtn().click();
  await page.waitForTimeout(2000);
  await dump('宿主Step2');

  // ── 宿主 Step2 → 打开「添加配件」内层面板 ────────────────────────
  const addPart = drawer.locator('button').filter({ hasText: /\+\s*添加第一个配件|\+\s*添加配件/ }).first();
  await expect(addPart, 'Step2 找不到「添加配件」⇒ **入口问题**').toBeVisible({ timeout: 40_000 });
  await addPart.click();
  await page.waitForTimeout(1800);
  await dump('配件·选类型');

  // 内层第 1 问：类型卡「零件」（靠它独有的描述文案定位，🚫 不用「零件」二字 —— 三张卡都含它）
  const typeCard = drawer.locator('div').filter({ hasText: /本厂加工的零件/ }).last();
  await expect(typeCard, '配件第 1 步找不到类型卡「零件」').toBeVisible({ timeout: 30_000 });
  await typeCard.click();
  await page.waitForTimeout(800);
  await nextBtn().click();
  await page.waitForTimeout(1500);
  await dump('配件·选来源');

  // 内层第 2 问：来源卡「新建零件」
  const srcCard = drawer.locator('div').filter({ hasText: /填品名\s*\/\s*规格/ }).last();
  await expect(srcCard, '配件第 2 步找不到来源卡「新建零件」').toBeVisible({ timeout: 30_000 });
  await srcCard.click();
  await page.waitForTimeout(800);
  await nextBtn().click();
  await page.waitForTimeout(1800);
  await dump('新建零件表单');

  // ── 零件信息（按 placeholder 定位）────────────────────────────────
  for (const [ph, value] of [
    ['如 动触头', partName], ['如 φ12×3', 'F2-spec'], ['如 12×8×3', '12'], ['如 10', '20'],
  ] as const) {
    const input = drawer.locator(`input[placeholder="${ph}"]`).first();
    await expect(input, `找不到 placeholder=「${ph}」的输入框 ⇒ 表单结构变了`).toBeVisible({ timeout: 20_000 });
    await input.fill(String(value));
    await page.waitForTimeout(300);
  }

  // ── 2 个材质 ────────────────────────────────────────────────────
  for (const [i, code] of [MAT_A, MAT_B].entries()) {
    const openPicker = drawer.locator('button')
      .filter({ hasText: /\+\s*添加第一个材质|\+\s*添加材质/ }).first();
    await expect(openPicker, `打不开材质选择器（第 ${i + 1} 个材质）`).toBeVisible({ timeout: 30_000 });
    await openPicker.click();
    await page.waitForTimeout(1200);
    const picker = drawer.locator('.cfg-material-picker').first();
    await expect(picker, '材质选择器 .cfg-material-picker 没出现').toBeVisible({ timeout: 30_000 });
    await picker.locator('input').first().fill(code);
    await page.waitForTimeout(1500);
    const row = picker.locator('.ant-table-row').filter({ hasText: code }).first();
    await expect(row, `材质 ${code} 没被筛出 ⇒ **夹具问题**`).toBeVisible({ timeout: 20_000 });
    await row.locator('button').filter({ hasText: /选\s*择/ }).first().click();
    await page.waitForTimeout(2000);
  }

  // 占比 60 / 40 —— 占比列的 Input placeholder = 「如 70」
  const ratios = drawer.locator('input[placeholder="如 70"]');
  const n = await ratios.count();
  console.log(`[材质表] 占比输入框 ${n} 个`);
  expect(n, '应有 2 个材质（2 个占比输入框）⇒ 少于 2 说明材质没加上，后续断言会空跑').toBe(2);
  for (const [i, r] of ['60', '40'].entries()) {
    await ratios.nth(i).fill(r);
    await page.waitForTimeout(400);
  }
  // 含量配置：每行一个原生 <select>
  const sels = drawer.locator('select');
  const sn = await sels.count();
  for (let i = 0; i < sn; i++) {
    const opts = await sels.nth(i).locator('option').allTextContents();
    const pick = opts.find((o: string) => o && !/请选择/.test(o));
    if (pick) { await sels.nth(i).selectOption({ label: pick }); await page.waitForTimeout(400); }
    console.log(`[含量配置 ${i}] 候选=${JSON.stringify(opts)} 选=${pick}`);
  }
  await H.shot(page, 'F2-01-选配新建零件-2材质');

  // ── 内层「确定」→ 回宿主 Step2 ───────────────────────────────────
  const ok = drawer.locator('button').filter({ hasText: /^\s*确\s*定\s*$/ }).last();
  await expect(ok, '新建零件面板找不到「确定」').toBeVisible({ timeout: 20_000 });
  const okReason = await ok.getAttribute('title');
  const okEnabled = await ok.isEnabled();
  console.log(`[新建零件·确定] enabled=${okEnabled} reason=${okReason}`);
  expect(okEnabled, `「确定」禁用，原因=「${okReason}」⇒ 表单没填全，判【未验证】`).toBe(true);
  await ok.click({ timeout: 20_000 });
  await page.waitForTimeout(2500);
  await dump('回到宿主Step2');

  // ── 宿主 Step3/4 → 添加到报价单 ─────────────────────────────────
  const addBtn = () => drawer.locator('button').filter({ hasText: /添\s*加\s*到\s*报\s*价\s*单/ }).first();
  for (let i = 0; i < 5 && !(await addBtn().count()); i++) {
    if (await nextBtn().isEnabled().catch(() => false)) await nextBtn().click();
    else await dump(`提交前·下一步不可点(${i})`, 500);
    await page.waitForTimeout(2500);
  }
  await expect(addBtn(), '走到最后一步应出现「添加到报价单」⇒ 找不到判【未验证】').toBeVisible({ timeout: 40_000 });
  await expect(addBtn(), '「添加到报价单」应可点').toBeEnabled({ timeout: 30_000 });
  await H.shot(page, 'F2-01b-确认并添加');
  await addBtn().click();

  // 🔑 「添加到报价单」只是**提交**（`ConfigureProductDrawer.tsx:315 setResult(resp)`），
  //    抽屉随后换成结果页，`onConfirm` 要等用户点 **「完成」**（`handleClose:339`）才触发。
  //    漏掉这一步的症状极具误导性：**DB 里行已建好、页面却是「还未添加任何产品」**
  //    ——看起来像产品丢数据，实际是脚本没走完。AC 原文说的「点『完成』后」就是这一下。
  const doneBtn = drawer.locator('button').filter({ hasText: /^\s*完\s*成\s*$/ }).last();
  await expect(doneBtn, '提交后应出现结果页的「完成」按钮 ⇒ 找不到说明提交失败').toBeVisible({ timeout: 120_000 });
  await dump('提交结果页', 400);
  await H.shot(page, 'F2-01c-提交结果页');
  await doneBtn.click();
}

test('AC-R2 · 选配新建零件挂 2 材质 →「不刷新」那一帧 BOM 页签即为树（且刷新后仍成立）', async ({ page }) => {
  const env = await H.assertEnvIdentity();
  console.log(`[env] db=${env.db} 前端模块含 F-2 改动=${env.hasFix}`);
  H.appendEvidence('F2-AC-R2.txt', `\n===== ${new Date().toISOString()} hasFix=${env.hasFix} =====\n`);

  const fx = H.seedCustomerAndQuotation('R2');
  // 🔬 干预生效的直接证据：F-2 那句 warm 会打出一次 POST …/ensure-card-values。
  //    阳性对照（去掉那句）里它必须**消失** —— 没有这条计数，「红了」也说不清是不是干预生效造成的。
  const ensureCalls: string[] = [];
  const errs: string[] = [];
  page.on('request', (r: any) => {
    if (r.url().includes('/ensure-card-values')) ensureCalls.push(`${r.method()} ${new Date().toISOString()}`);
  });
  page.on('pageerror', (e: any) => errs.push('PAGEERROR ' + String(e).slice(0, 300)));
  await H.uiLogin(page);
  await H.openStep2(page, fx);

  // 打开选配抽屉
  await page.locator('button').filter({ hasText: /添加产品/ }).first().click();
  await page.waitForTimeout(1500);
  const selItem = page.locator('.ant-dropdown:visible .ant-dropdown-menu-item').filter({ hasText: /选配/ }).first();
  await expect(selItem, '「添加产品」下拉里找不到「选配添加」⇒ **入口问题**').toBeVisible({ timeout: 30_000 });
  // 🚨 快失败：该项在 `customerTemplateId` 为空时被禁用（QuotationStep2.tsx:4540），
  //    直接 click 会重试到测试超时，7 分钟后报成「点不动」—— 那是夹具缺报价模板，不是产品缺陷。
  const selDisabled = await selItem.getAttribute('aria-disabled');
  expect(selDisabled, '「选配添加」被禁用（多为本单没有 customer_template_id）⇒ **夹具问题**，判【未验证】')
    .not.toBe('true');
  await selItem.click({ timeout: 20_000 });
  await page.waitForTimeout(2500);

  await configureNewPartWith2Materials(page, `${H.TAG}零件${H.RUN}`, `${H.TAG}CPN${H.RUN}`);

  // ══ 主断言帧：点完成之后 **不刷新** ═════════════════════════════
  // 等抽屉关闭 + 卡片出现；🚫 不 reload、🚫 不 goto。
  await page.waitForFunction(() => document.querySelectorAll('.qt-product-card').length > 0,
    undefined, { timeout: 120_000 }).catch(() => { /* 下面断言给可读信息 */ });
  await page.waitForTimeout(25_000);   // 等 warm(ensure-card-values) + 渲染稳定
  await page.waitForFunction(() => !((document.body.innerText || '').includes('加载中')),
    undefined, { timeout: 60_000 }).catch(() => { /* 交给断言 */ });

  console.log('[不刷新·ensure调用] ' + JSON.stringify(ensureCalls));
  console.log('[不刷新·pageerror] ' + JSON.stringify(errs.slice(0, 5)));
  const noRefresh = await H.probeTab(page, TAB_BOM);
  console.log('[不刷新·页签] ' + JSON.stringify(noRefresh.tabNames));
  console.log('[不刷新·表头] ' + JSON.stringify(noRefresh.header));
  console.log('[不刷新·行] ' + JSON.stringify(noRefresh.rows));
  await H.shot(page, 'F2-02-AC-R2-不刷新那一帧-BOM页签');
  H.appendEvidence('F2-AC-R2.txt',
    `【不刷新】单=${fx.quotationNumber}\n页签=${JSON.stringify(noRefresh.tabNames)}\n`
    + `表头=${JSON.stringify(noRefresh.header)}\nensure调用=${JSON.stringify(ensureCalls)}\n行=${JSON.stringify(noRefresh.rows, null, 1)}\n`);

  expect(noRefresh.rows.length, 'AC-R2 前置：BOM 页签 0 行 ⇒ 断言会空跑，判【未验证】').toBeGreaterThan(0);

  // ① data-node-id
  const withNode = noRefresh.rows.filter((r) => r.nodeId != null && r.nodeId !== '');
  expect(withNode.length,
    `AC-R2①：不刷新那一帧 BOM 页签的 <tr> 应带 data-node-id（只在树模式下有值，QuotationStep2.tsx:3585）。`
    + `\n  实际带 nodeId 的行 = ${withNode.length} / ${noRefresh.rows.length}`
    + `\n  🔑 0 = useSnapQuote 仍为 false ⇒ 走实时 batch-expand ⇒ 无 spine ⇒ 平铺（本次缺陷原貌）。`
    + `\n  行 = ${JSON.stringify(noRefresh.rows)}`).toBeGreaterThan(0);

  // ② 至少一行带缩进
  const indented = noRefresh.rows.filter((r) => r.indentPx > 0);
  expect(indented.length,
    `AC-R2②：至少一行应带缩进（子节点，depth≥1 ⇒ 首格 spacer 宽度 = depth×16px）。`
    + `\n  实际缩进行 = ${indented.length}，各行缩进 = ${JSON.stringify(noRefresh.rows.map((r) => r.indentPx))}`)
    .toBeGreaterThan(0);

  // ③ 没有整行全空
  const empties = noRefresh.rows.filter((r) => H.rowIsAllEmpty(r.cells));
  expect(empties.length,
    `AC-R2③：不得出现整行全空的行（平铺态下合成根行的业务列全 null ⇒ 一行空行，正是用户报的现象）。`
    + `\n  实际全空行 = ${empties.length} 行：${JSON.stringify(empties)}`).toBe(0);

  // ④ 可选补充：刷新后仍成立（回归护栏，🚫 不是主判据）
  await page.reload();
  await page.waitForSelector('.ant-steps-item', { timeout: 90_000 });
  const nx = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  await expect(nx).toBeEnabled({ timeout: 40_000 });
  await nx.click();
  await page.waitForTimeout(20_000);
  const afterRefresh = await H.probeTab(page, TAB_BOM);
  console.log('[刷新后·行] ' + JSON.stringify(afterRefresh.rows));
  await H.shot(page, 'F2-03-AC-R2-刷新后');
  H.appendEvidence('F2-AC-R2.txt', `【刷新后】行=${JSON.stringify(afterRefresh.rows, null, 1)}\n`);
  expect(afterRefresh.rows.filter((r) => r.nodeId).length, 'AC-R2④：刷新后仍应是树').toBeGreaterThan(0);
  expect(afterRefresh.rows.filter((r) => H.rowIsAllEmpty(r.cells)).length, 'AC-R2④：刷新后不得有全空行').toBe(0);

  H.writeEvidence('F2-AC-R2-结论.txt',
    `单=${fx.quotationNumber} (${fx.quotationId})\n`
    + `【不刷新】行数=${noRefresh.rows.length} 带nodeId=${withNode.length} 缩进行=${indented.length} 全空行=${empties.length}\n`
    + `【刷新后】行数=${afterRefresh.rows.length} 带nodeId=${afterRefresh.rows.filter((r) => r.nodeId).length}\n`);
});

/**
 * AC-R4 回归：合法空卡片（`{"tabs":[]}`）行为不变、不无限转圈。
 * 🚫 纯只读：拿现成的阳性对照单 `75426cf9…`（template_id 为 NULL 的空卡片对照单，**勿删**）。
 */
test('AC-R4 · 合法空卡片单打开后行为不变、不无限转圈', async ({ page }) => {
  const real = H.sqlScalar(`SELECT id::text FROM quotation WHERE id::text LIKE '75426cf9%' LIMIT 1`);
  expect(real, 'AC-R4 前置：对照单 75426cf9… 查不到 ⇒ 判【未验证】').toMatch(/^[0-9a-f-]{36}$/);
  const info = H.sqlRO(
    `SELECT q.quotation_number, q.status,
            count(li.id) AS lines,
            count(*) FILTER (WHERE li.template_id IS NULL) AS tpl_null,
            count(*) FILTER (WHERE li.quote_card_values IS NULL
                               OR li.costing_card_values IS NULL) AS any_null,
            count(*) FILTER (WHERE li.quote_card_values::text LIKE '%__cardValueFailed%'
                               OR li.costing_card_values::text LIKE '%__cardValueFailed%') AS failed
       FROM quotation q LEFT JOIN quotation_line_item li ON li.quotation_id=q.id
      WHERE q.id::text='${real}' GROUP BY 1,2`);
  console.log('[AC-R4 对照单] ' + info);
  H.appendEvidence('F2-AC-R4.txt', `\n===== ${new Date().toISOString()} =====\n对照单=${real}\n${info}\n`);
  // 🔑 语义前置：两侧卡片值都非 NULL 且无失败哨兵 ⇒ shouldWarmCardValues 必为 false ⇒ 根本不进 warm。
  //    这一条不成立的话，下面「零次 ensure-card-values」就不是 AC-R4 的结论。
  const anyNull = H.sqlScalar(`SELECT count(*) FROM quotation_line_item
     WHERE quotation_id='${real}' AND (quote_card_values IS NULL OR costing_card_values IS NULL
       OR quote_card_values::text LIKE '%__cardValueFailed%'
       OR costing_card_values::text LIKE '%__cardValueFailed%')`);
  expect(anyNull, 'AC-R4 前置：对照单应是**合法空卡片**（两侧非 NULL、无失败哨兵）').toBe('0');

  await H.uiLogin(page);
  // 🧪 行为断言：打开这张单不得触发 ensure-card-values（合法空卡片语义 = 「算完了，结果就是空」）
  const ensureCalls: string[] = [];
  page.on('request', (r: any) => {
    if (r.url().includes('/ensure-card-values')) ensureCalls.push(`${r.method()} ${r.url()}`);
  });
  const t0 = Date.now();
  await page.goto(`/quotations/${real}/edit`);
  await page.waitForSelector('.ant-steps-item', { timeout: 90_000 });
  // 「不无限转圈」：60s 内 Spin 必须消失（warmCardValues 上界 20×800ms=16s + ensure 本身）
  await page.waitForFunction(() => document.querySelectorAll('.ant-spin-spinning').length === 0,
    undefined, { timeout: 60_000 });
  const elapsed = Date.now() - t0;
  console.log(`[AC-R4] Spin 消失耗时 ${elapsed}ms`);
  const bodyText = (await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 400);
  console.log('[AC-R4 页面] ' + bodyText);
  await H.shot(page, 'F2-04-AC-R4-空卡片对照单');
  console.log('[AC-R4] ensure-card-values 调用 = ' + JSON.stringify(ensureCalls));
  H.appendEvidence('F2-AC-R4.txt',
    `Spin消失=${elapsed}ms\nensure调用=${JSON.stringify(ensureCalls)}\n页面=${bodyText}\n`);
  expect(elapsed, 'AC-R4：空卡片单不得无限转圈').toBeLessThan(60_000);
  expect(bodyText, 'AC-R4：不得停在「卡片数据准备失败」').not.toContain('卡片数据准备失败');
  expect(ensureCalls.length,
    `AC-R4：合法空卡片单打开时**不应**触发 ensure-card-values（shouldWarmCardValues 只认缺失/失败哨兵，`
    + `🚫 不许退化成「tabs 为空就 warm」）。实际 = ${JSON.stringify(ensureCalls)}`).toBe(0);
});

// ═══════════════════════════════════════════════════════════════════════════
// repair-260911 · F-3 开发自测：**AC-R6**（不刷新那一帧行↔值必须对齐）
//
// AC-R6 原文（问题说明 §⑦）：
//   前置：选配新建一个零件、挂 2 个材质，点「添加到报价单」。🚨 **不刷新**。
//   ① 根行（nodeId 不含 `/`）业务列全为空
//   ② 每个子件行的**料号列** == 该行 nodeId 的末段
//   ③ 项次列按子件顺序递增且不重复（2 材质 = 1、2）
//   ④ 刷新后与不刷新逐字节一致
//   量具：`textContent || input.value`（probeTab 已按此实现）
//
// 🚫 复用 F-2 的 helpers 与 configureNewPartWith2Materials，不新建一次性 spec（裁决 R-9）。
// ═══════════════════════════════════════════════════════════════════════════
function colIndex(header: string[], want: string): number {
  const norm = (s: string) => s.replace(/\s+/g, '');
  let i = header.findIndex((h) => norm(h) === want);
  if (i < 0) i = header.findIndex((h) => norm(h).endsWith(want));
  return i;
}

test('AC-R6 · 不刷新那一帧 BOM 行值必须与该行 data-node-id 对齐（且与刷新后逐字节一致）', async ({ page }) => {
  const env = await H.assertEnvIdentity();
  // 🔬 干预生效的直接证据 ①：前端模块正文里必须能读到 F-3 的重灌函数。
  const src = await (await fetch(`${H.BASE_URL}/src/pages/quotation/QuotationWizard.tsx`)).text();
  // ⚠️ 探针字符串必须用**转换后**的形态：vite 服务的是已 strip TS 注解的 JS，
  //    `(item as any).componentData` 在那里是 `item.componentData` —— 按 TS 原文探会恒 false（首轮就栽在这）。
  const hasF3 = src.includes('reflowComponentRowsFromResponse(item.componentData');
  console.log(`[env] db=${env.db} F-2改动=${env.hasFix} **F-3重灌调用=${hasF3}**`);

  const fx = H.seedCustomerAndQuotation('R6');
  const ensureCalls: string[] = [];
  page.on('request', (r: any) => {
    if (r.url().includes('/ensure-card-values')) ensureCalls.push(`${r.method()} ${new Date().toISOString()}`);
  });
  await H.uiLogin(page);
  await H.openStep2(page, fx);

  await page.locator('button').filter({ hasText: /添加产品/ }).first().click();
  await page.waitForTimeout(1500);
  const selItem = page.locator('.ant-dropdown:visible .ant-dropdown-menu-item').filter({ hasText: /选配/ }).first();
  await expect(selItem, '找不到「选配添加」⇒ **入口问题**').toBeVisible({ timeout: 30_000 });
  expect(await selItem.getAttribute('aria-disabled'), '「选配添加」被禁用 ⇒ **夹具问题**，判【未验证】').not.toBe('true');
  await selItem.click({ timeout: 20_000 });
  await page.waitForTimeout(2500);

  await configureNewPartWith2Materials(page, `${H.TAG}零件${H.RUN}R6`, `${H.TAG}CPN${H.RUN}R6`);

  // ══ 主断言帧：**不刷新** ═══════════════════════════════════════
  await page.waitForFunction(() => document.querySelectorAll('.qt-product-card').length > 0,
    undefined, { timeout: 120_000 }).catch(() => {});
  await page.waitForTimeout(25_000);   // 等 warm(ensure-card-values) 回来并重灌
  await page.waitForFunction(() => !((document.body.innerText || '').includes('加载中')),
    undefined, { timeout: 60_000 }).catch(() => {});

  const noRefresh = await H.probeTab(page, TAB_BOM);
  // 🔬 干预生效的直接证据 ②：warm 真的发生了（没有它，「对齐」可能只是因为压根没翻通道）。
  console.log('[不刷新·ensure调用] ' + JSON.stringify(ensureCalls));
  console.log('[不刷新·表头] ' + JSON.stringify(noRefresh.header));
  console.log('[不刷新·行] ' + JSON.stringify(noRefresh.rows));
  await H.shot(page, 'F3-01-AC-R6-不刷新那一帧-BOM页签');
  H.appendEvidence('F3-AC-R6.txt',
    `\n===== ${new Date().toISOString()} F-3重灌=${hasF3} =====\n单=${fx.quotationNumber} (${fx.quotationId})\n`
    + `表头=${JSON.stringify(noRefresh.header)}\nensure调用=${JSON.stringify(ensureCalls)}\n`
    + `【不刷新】行=${JSON.stringify(noRefresh.rows, null, 1)}\n`);

  expect(ensureCalls.length, 'AC-R6 前置：整帧没发过 ensure-card-values ⇒ 通道没翻，断言空跑，判【未验证】').toBeGreaterThan(0);
  expect(noRefresh.rows.length, 'AC-R6 前置：BOM 页签 0 行 ⇒ 断言空跑，判【未验证】').toBeGreaterThan(0);
  const rootRows = noRefresh.rows.filter((r) => r.nodeId && !r.nodeId.includes('/'));
  const childRows = noRefresh.rows.filter((r) => r.nodeId && r.nodeId.includes('/'));
  expect(rootRows.length, `AC-R6 前置：应恰好 1 个根行，实际 ${rootRows.length}；行=${JSON.stringify(noRefresh.rows)}`).toBe(1);
  expect(childRows.length, `AC-R6 前置：2 材质应有 2 个子件行，实际 ${childRows.length}`).toBe(2);

  // ① 根行业务列全空
  //   🚨 量具口径：业务列 = **表头非空且不是首列**的那些格。
  //     · 首列是树列「BOM」（展开符 + 行删除符），不是业务列；
  //     · **末列表头为空**，是操作列（＋/✕ 加行删行按钮），同样不是业务列 —— 首轮把它算进去红了一次。
  const rootBiz = rootRows[0].cells.filter((_, i) => i > 0 && (noRefresh.header[i] ?? '').trim() !== '');
  expect(rootBiz.filter((c) => c !== '' && c !== '—' && c !== '-'),
    `AC-R6①：根行是合成根，业务列必须全空。实际非空格 = ${JSON.stringify(rootBiz)}`
    + `\n  🔑 非空 = comp.rows 未被服务端权威行重灌，根行拿到了子件1的值（本次缺陷原貌）。`).toEqual([]);

  // ② 子件行料号列 == nodeId 末段
  const iPartNo = colIndex(noRefresh.header, '料号');
  expect(iPartNo, `AC-R6②：表头里找不到「料号」列 ⇒ 量具失准，判【未验证】。表头=${JSON.stringify(noRefresh.header)}`).toBeGreaterThanOrEqual(0);
  for (const r of childRows) {
    const tail = r.nodeId!.split('/').pop();
    expect(r.cells[iPartNo],
      `AC-R6②：行 ${r.nodeId} 的料号列应 = ${tail}，实际 = ${r.cells[iPartNo]}；整行=${JSON.stringify(r.cells)}`).toBe(tail);
  }

  // ③ 项次列递增不重复
  const iSeq = colIndex(noRefresh.header, '项次');
  expect(iSeq, `AC-R6③：表头里找不到「项次」列。表头=${JSON.stringify(noRefresh.header)}`).toBeGreaterThanOrEqual(0);
  const seqs = childRows.map((r) => r.cells[iSeq]);
  expect(seqs, `AC-R6③：项次应递增不重复，实际 = ${JSON.stringify(seqs)}`).toEqual(['1', '2']);

  // ④ 刷新后逐字节一致
  await page.reload();
  await page.waitForSelector('.ant-steps-item', { timeout: 90_000 });
  const nx = page.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).first();
  await expect(nx).toBeEnabled({ timeout: 40_000 });
  await nx.click();
  await page.waitForTimeout(20_000);
  const after = await H.probeTab(page, TAB_BOM);
  console.log('[刷新后·行] ' + JSON.stringify(after.rows));
  await H.shot(page, 'F3-02-AC-R6-刷新后');
  H.appendEvidence('F3-AC-R6.txt', `【刷新后】行=${JSON.stringify(after.rows, null, 1)}\n`);
  expect(JSON.stringify(after.rows),
    `AC-R6④：刷新后应与不刷新逐字节一致。\n  不刷新=${JSON.stringify(noRefresh.rows)}\n  刷新后=${JSON.stringify(after.rows)}`)
    .toBe(JSON.stringify(noRefresh.rows));

  H.writeEvidence('F3-AC-R6-结论.txt',
    `单=${fx.quotationNumber} (${fx.quotationId})  F-3重灌=${hasF3}\n`
    + `表头=${JSON.stringify(noRefresh.header)}\n`
    + `【不刷新】${JSON.stringify(noRefresh.rows, null, 1)}\n【刷新后】${JSON.stringify(after.rows, null, 1)}\n`);
});
