/**
 * repair-260911 · 分片 **S-A**（落库与渲染）
 *
 * AC 原文出处：`dev-docs/task-260910-选配切ds新表与已有料号绑定/repair-260911-卡片渲染三缺陷/问题说明.md §⑦`
 *   AC-R1 客户产品编号落到行上（①DB ②刷新后卡片列 ③snapshot_rows）
 *   AC-R2 BOM 渲染成树（**刷新后**：①data-node-id ②有缩进 ③无整行全空）
 *   AC-R3 项次显示（**刷新后**：项次非空行数 = 材质数 = 2，值 1、2）
 *   AC-R4 合法空卡片不被误伤
 *   AC-R6 **不刷新**那一帧行↔值必须对齐（①根行业务列全空 ②子件料号==nodeId末段 ③项次1,2不重复 ④与刷新后一致）
 *
 * 🚨 断言口径：`textContent || input.value` 两者都取（派工书 ⑧）。
 * 🚨 每条断言前先断言「结果非空」，避免空跑假绿。
 */
import { test, expect } from '@playwright/test';
import * as H from './r260911sa.helpers';

const MAT_A = '00005';   // AgNi25C2
const MAT_B = '00006';   // AgNi10
const TAB_BOM = 'BOM';
const TAB_PRODUCT = '产品';
const PROD_COMPONENT = '221dc766-8ab6-4d95-82c0-08cc03e6267d';
const CPN_BASIC_KEY = '{$builder_221dc7668ab6._客户料号_客户产品编号}';

/** 本轮的客户产品编号 —— 前缀写死在造数前缀里，一眼可回溯归属。 */
const CPN = `${H.TAG}CPN-${H.RUN}`;

test.describe.configure({ mode: 'serial' });

/** 选配抽屉：新建零件 + 2 材质 + 提交 + 点「完成」。 */
async function configureNewPartWith2Materials(page: any, partName: string, custProductNo: string) {
  const drawer = page.locator('.ant-drawer').last();
  const nextBtn = () => drawer.locator('button').filter({ hasText: /^\s*下\s*一\s*步\s*$/ }).last();
  const dump = async (tag: string, n = 500) =>
    console.log(`[${tag}] ` + (await drawer.innerText()).replace(/\s+/g, ' ').slice(0, n));

  // 宿主 Step1 客户产品编号
  const cpn = drawer.locator('input').first();
  await expect(cpn, '选配第 1 步应有客户产品编号输入框').toBeVisible({ timeout: 40_000 });
  await cpn.fill(custProductNo);
  await page.waitForTimeout(1500);
  await expect(nextBtn(), '第 1 步「下一步」应可点').toBeEnabled({ timeout: 40_000 });
  await nextBtn().click();
  await page.waitForTimeout(2000);

  // 宿主 Step2 → 内层「添加配件」面板
  const addPart = drawer.locator('button').filter({ hasText: /\+\s*添加第一个配件|\+\s*添加配件/ }).first();
  await expect(addPart, 'Step2 找不到「添加配件」⇒ **入口问题**').toBeVisible({ timeout: 40_000 });
  await addPart.click();
  await page.waitForTimeout(1800);

  const typeCard = drawer.locator('div').filter({ hasText: /本厂加工的零件/ }).last();
  await expect(typeCard, '配件第 1 步找不到类型卡「零件」').toBeVisible({ timeout: 30_000 });
  await typeCard.click();
  await page.waitForTimeout(800);
  await nextBtn().click();
  await page.waitForTimeout(1500);

  const srcCard = drawer.locator('div').filter({ hasText: /填品名\s*\/\s*规格/ }).last();
  await expect(srcCard, '配件第 2 步找不到来源卡「新建零件」').toBeVisible({ timeout: 30_000 });
  await srcCard.click();
  await page.waitForTimeout(800);
  await nextBtn().click();
  await page.waitForTimeout(1800);

  for (const [ph, value] of [
    ['如 动触头', partName], ['如 φ12×3', 'SA-spec'], ['如 12×8×3', '12'], ['如 10', '20'],
  ] as const) {
    const input = drawer.locator(`input[placeholder="${ph}"]`).first();
    await expect(input, `找不到 placeholder=「${ph}」的输入框 ⇒ 表单结构变了`).toBeVisible({ timeout: 20_000 });
    await input.fill(String(value));
    await page.waitForTimeout(300);
  }

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

  const ratios = drawer.locator('input[placeholder="如 70"]');
  const n = await ratios.count();
  console.log(`[材质表] 占比输入框 ${n} 个`);
  expect(n, '应有 2 个材质（2 个占比输入框）⇒ 少于 2 说明材质没加上，后续断言会空跑').toBe(2);
  for (const [i, r] of ['60', '40'].entries()) {
    await ratios.nth(i).fill(r);
    await page.waitForTimeout(400);
  }
  const sels = drawer.locator('select');
  const sn = await sels.count();
  for (let i = 0; i < sn; i++) {
    const opts = await sels.nth(i).locator('option').allTextContents();
    const pick = opts.find((o: string) => o && !/请选择/.test(o));
    if (pick) { await sels.nth(i).selectOption({ label: pick }); await page.waitForTimeout(400); }
    console.log(`[含量配置 ${i}] 候选=${JSON.stringify(opts)} 选=${pick}`);
  }
  await H.shot(page, `SA-01-选配新建零件-2材质-${H.RUN}`);

  const ok = drawer.locator('button').filter({ hasText: /^\s*确\s*定\s*$/ }).last();
  await expect(ok, '新建零件面板找不到「确定」').toBeVisible({ timeout: 20_000 });
  const okReason = await ok.getAttribute('title');
  const okEnabled = await ok.isEnabled();
  console.log(`[新建零件·确定] enabled=${okEnabled} reason=${okReason}`);
  expect(okEnabled, `「确定」禁用，原因=「${okReason}」⇒ 表单没填全，判【未验证】`).toBe(true);
  await ok.click({ timeout: 20_000 });
  await page.waitForTimeout(2500);

  const addBtn = () => drawer.locator('button').filter({ hasText: /添\s*加\s*到\s*报\s*价\s*单/ }).first();
  for (let i = 0; i < 5 && !(await addBtn().count()); i++) {
    if (await nextBtn().isEnabled().catch(() => false)) await nextBtn().click();
    else await dump(`提交前·下一步不可点(${i})`, 400);
    await page.waitForTimeout(2500);
  }
  await expect(addBtn(), '走到最后一步应出现「添加到报价单」⇒ 找不到判【未验证】').toBeVisible({ timeout: 40_000 });
  await expect(addBtn(), '「添加到报价单」应可点').toBeEnabled({ timeout: 30_000 });
  await addBtn().click();

  // 🔑 「添加到报价单」只是提交；`onConfirm` 要等用户点**「完成」**才触发。
  const doneBtn = drawer.locator('button').filter({ hasText: /^\s*完\s*成\s*$/ }).last();
  await expect(doneBtn, '提交后应出现结果页的「完成」按钮 ⇒ 找不到说明提交失败').toBeVisible({ timeout: 150_000 });
  await dump('提交结果页', 300);
  await H.shot(page, `SA-02-提交结果页-${H.RUN}`);
  await doneBtn.click();
}

test('S-A 主流程 · AC-R1 / AC-R2 / AC-R3 / AC-R6', async ({ page }) => {
  const EV = `SA-主流程-${H.RUN}.txt`;
  const env = await H.assertEnvIdentity();
  H.appendEvidence(EV, `\n===== ${new Date().toISOString()} RUN=${H.RUN} =====\n`
    + `env=${JSON.stringify(env)}\nbaseURL=${H.BASE_URL}\nCPN=${CPN}\n`);

  const fx = H.seedCustomerAndQuotation('SA');
  const errs: string[] = [];
  page.on('pageerror', (e: any) => errs.push('PAGEERROR ' + String(e).slice(0, 300)));

  await H.uiLogin(page);
  await H.openStep2(page, fx);

  await page.locator('button').filter({ hasText: /添加产品/ }).first().click();
  await page.waitForTimeout(1500);
  const selItem = page.locator('.ant-dropdown:visible .ant-dropdown-menu-item').filter({ hasText: /选配/ }).first();
  await expect(selItem, '「添加产品」下拉里找不到「选配添加」⇒ **入口问题**').toBeVisible({ timeout: 30_000 });
  const selDisabled = await selItem.getAttribute('aria-disabled');
  expect(selDisabled, '「选配添加」被禁用（多为本单没有 customer_template_id）⇒ **夹具问题**，判【未验证】')
    .not.toBe('true');
  await selItem.click({ timeout: 20_000 });
  await page.waitForTimeout(2500);

  await configureNewPartWith2Materials(page, `${H.TAG}零件${H.RUN}`, CPN);

  // ══ 帧 A：不刷新（AC-R6 的断言帧）════════════════════════════════
  await page.waitForFunction(() => document.querySelectorAll('.qt-product-card').length > 0,
    undefined, { timeout: 150_000 }).catch(() => { /* 下面断言给可读信息 */ });
  await page.waitForTimeout(25_000);   // 等 warm(ensure-card-values) + 渲染稳定
  await page.waitForFunction(() => !((document.body.innerText || '').includes('加载中')),
    undefined, { timeout: 60_000 }).catch(() => { /* 交给断言 */ });

  const bomNoRefresh = await H.probeTab(page, TAB_BOM);
  await H.shot(page, `SA-03-不刷新-BOM-${H.RUN}`);
  console.log('[不刷新·BOM表头] ' + JSON.stringify(bomNoRefresh.header));
  console.log('[不刷新·BOM行] ' + JSON.stringify(bomNoRefresh.rows));
  H.appendEvidence(EV, `\n【帧A·不刷新·BOM】表头=${JSON.stringify(bomNoRefresh.header)}\n`
    + `行=${JSON.stringify(bomNoRefresh.rows, null, 1)}\npageerror=${JSON.stringify(errs.slice(0, 5))}\n`);

  // ══ 帧 B：刷新后（AC-R1② / AC-R2 / AC-R3 的断言帧 —— AC 原文明写「刷新后」）══
  await H.reloadToStep2(page);
  const bomRefresh = await H.probeTab(page, TAB_BOM);
  await H.shot(page, `SA-04-刷新后-BOM-${H.RUN}`);
  const prodRefresh = await H.probeTab(page, TAB_PRODUCT);
  await H.shot(page, `SA-05-刷新后-产品-${H.RUN}`);
  console.log('[刷新后·BOM行] ' + JSON.stringify(bomRefresh.rows));
  console.log('[刷新后·产品行] ' + JSON.stringify(prodRefresh.rows));
  H.appendEvidence(EV, `\n【帧B·刷新后·BOM】表头=${JSON.stringify(bomRefresh.header)}\n`
    + `行=${JSON.stringify(bomRefresh.rows, null, 1)}\n`
    + `\n【帧B·刷新后·产品】表头=${JSON.stringify(prodRefresh.header)}\n`
    + `行=${JSON.stringify(prodRefresh.rows, null, 1)}\n`);

  // ══ DB 侧取证 ═════════════════════════════════════════════════════
  const lines = H.sqlRows(
    `SELECT id::text, product_part_no_snapshot pn, coalesce(customer_part_no,'<NULL>') cpn,
            composite_type ct, coalesce(parent_line_item_id::text,'<NULL>') parent
       FROM quotation_line_item WHERE quotation_id='${fx.quotationId}' ORDER BY sort_order, created_at`);
  console.log('[DB·line_item] ' + JSON.stringify(lines));
  H.appendEvidence(EV, `\n【DB·quotation_line_item】\n${JSON.stringify(lines, null, 1)}\n`);
  expect(lines.length, 'AC-R1 前置：本单应至少有 1 个 line_item ⇒ 0 行说明选配没落库，断言会空跑，判【未验证】')
    .toBeGreaterThan(0);
  const topLine = lines.find((l) => l.parent === '<NULL>') ?? lines[0];

  const snapCpn = H.sqlRows(
    `SELECT coalesce(r->'basicDataValues'->>'${CPN_BASIC_KEY}','<NULL>') AS cpn
       FROM quotation_line_component_data d, LATERAL jsonb_array_elements(d.snapshot_rows) r
      WHERE d.line_item_id='${topLine.id}' AND d.component_id='${PROD_COMPONENT}'`);
  console.log('[DB·snapshot_rows 客户产品编号] ' + JSON.stringify(snapCpn));
  H.appendEvidence(EV, `\n【DB·snapshot_rows(产品组件)._客户料号_客户产品编号】\n${JSON.stringify(snapCpn)}\n`);

  // ══════════════════ AC-R1 ══════════════════
  // ① DB 落库
  expect.soft(topLine.cpn,
    `AC-R1①：quotation_line_item.customer_part_no 应 = 「${CPN}」（🚫 不是 NULL）。`
    + `\n  实际 = ${topLine.cpn}（line_item=${topLine.id} 料号=${topLine.pn}）`).toBe(CPN);
  // ② 刷新后卡片「客户产品编号」列
  expect(prodRefresh.rows.length, 'AC-R1② 前置：产品页签 0 行 ⇒ 断言会空跑，判【未验证】').toBeGreaterThan(0);
  const prodCpnCells = prodRefresh.rows.map((r) => H.cellByHeader(prodRefresh, r, '客户产品编号'));
  console.log('[AC-R1②·产品页签客户产品编号列] ' + JSON.stringify(prodCpnCells));
  expect.soft(prodCpnCells,
    `AC-R1②：刷新后「产品」页签的「客户产品编号」列应显示 ${CPN}。`
    + `\n  表头 = ${JSON.stringify(prodRefresh.header)}\n  实际该列各行 = ${JSON.stringify(prodCpnCells)}`)
    .toContain(CPN);
  // ③ snapshot_rows
  expect(snapCpn.length, 'AC-R1③ 前置：产品组件 snapshot_rows 取到 0 行 ⇒ 断言会空跑，判【未验证】')
    .toBeGreaterThan(0);
  expect.soft(snapCpn.map((r) => r.cpn),
    `AC-R1③：snapshot_rows 里 ${CPN_BASIC_KEY} 应非 null。实际 = ${JSON.stringify(snapCpn)}`)
    .toContain(CPN);

  // ══════════════════ AC-R2（刷新后）══════════════════
  expect(bomRefresh.rows.length, 'AC-R2 前置：刷新后 BOM 页签 0 行 ⇒ 断言会空跑，判【未验证】').toBeGreaterThan(0);
  const withNode = bomRefresh.rows.filter((r) => r.nodeId != null && r.nodeId !== '');
  expect.soft(withNode.length,
    `AC-R2①：刷新后 BOM 页签的 <tr> 应带 data-node-id（只在树模式下有值）。`
    + `\n  实际带 nodeId 的行 = ${withNode.length} / ${bomRefresh.rows.length}`
    + `\n  行 = ${JSON.stringify(bomRefresh.rows)}`).toBeGreaterThan(0);
  const indented = bomRefresh.rows.filter((r) => r.indentPx > 0);
  expect.soft(indented.length,
    `AC-R2②：至少一行应带缩进（层级 > 0）。各行缩进 = ${JSON.stringify(bomRefresh.rows.map((r) => r.indentPx))}`)
    .toBeGreaterThan(0);
  const empties = bomRefresh.rows.filter((r) => H.rowIsAllEmpty(r.cells));
  expect.soft(empties.length,
    `AC-R2③：不得出现整行全空的行。实际全空行 = ${empties.length}：${JSON.stringify(empties)}`).toBe(0);

  // ══════════════════ AC-R3（刷新后）══════════════════
  const seqCells = bomRefresh.rows.map((r) => H.cellByHeader(bomRefresh, r, '项次'));
  console.log('[AC-R3·项次列] ' + JSON.stringify(seqCells));
  expect(seqCells.every((c) => c !== '__NO_SUCH_HEADER__'),
    `AC-R3 前置：BOM 表头里找不到「项次」列 ⇒ 判【未验证】。表头 = ${JSON.stringify(bomRefresh.header)}`).toBe(true);
  const seqNonEmpty = seqCells.filter((c) => c !== '' && c !== '—' && c !== '-');
  expect.soft(seqNonEmpty.length,
    `AC-R3：刷新后 BOM「项次」列非空的行数应 = 材质数 2。`
    + `\n  实际非空 = ${seqNonEmpty.length}，各行项次 = ${JSON.stringify(seqCells)}`).toBe(2);
  expect.soft(seqNonEmpty.slice().sort(),
    `AC-R3：项次值应为 1、2。实际 = ${JSON.stringify(seqNonEmpty)}`).toEqual(['1', '2']);

  // ══════════════════ AC-R6（不刷新那一帧）══════════════════
  // ⏳ F-3 未落地时本段仅记录基线（PW_AC_R6=1 才硬断言）。
  const hardR6 = process.env.PW_AC_R6 === '1';
  const r6 = { hardR6, notes: [] as string[] };
  const nrRows = bomNoRefresh.rows;
  const rootRows = nrRows.filter((r) => r.nodeId != null && !r.nodeId.includes('/'));
  const childRows = nrRows.filter((r) => r.nodeId != null && r.nodeId.includes('/'));
  r6.notes.push(`不刷新帧：总行=${nrRows.length} 根行=${rootRows.length} 子件行=${childRows.length}`);
  const bizHeaders = ['项次', '料号', '材料名', '组成数量'];
  const rootBiz = rootRows.map((r) => bizHeaders.map((h) => H.cellByHeader(bomNoRefresh, r, h)));
  const childPn = childRows.map((r) => ({
    nodeIdTail: (r.nodeId || '').split('/').pop(),
    料号: H.cellByHeader(bomNoRefresh, r, '料号'),
    项次: H.cellByHeader(bomNoRefresh, r, '项次'),
  }));
  const sameAsRefresh = JSON.stringify(nrRows) === JSON.stringify(bomRefresh.rows);
  r6.notes.push(`AC-R6①根行业务列=${JSON.stringify(rootBiz)}`);
  r6.notes.push(`AC-R6②③子件行=${JSON.stringify(childPn)}`);
  r6.notes.push(`AC-R6④不刷新==刷新后 ? ${sameAsRefresh}`);
  console.log('[AC-R6] ' + r6.notes.join('\n  '));
  H.appendEvidence(EV, `\n【AC-R6 观测】hard=${hardR6}\n  ${r6.notes.join('\n  ')}\n`);

  if (hardR6) {
    expect(nrRows.length, 'AC-R6 前置：不刷新帧 BOM 0 行 ⇒ 断言会空跑，判【未验证】').toBeGreaterThan(0);
    expect(childRows.length, 'AC-R6 前置：不刷新帧应有 2 个子件行 ⇒ 0 说明没渲染成树，判【未验证】').toBe(2);
    for (const [i, rb] of rootBiz.entries()) {
      expect.soft(rb.every((c) => c === '' || c === '—' || c === '-'),
        `AC-R6①：根行（第 ${i} 个，nodeId=${rootRows[i].nodeId}）业务列应全空，实际 = ${JSON.stringify(rb)}`).toBe(true);
    }
    for (const c of childPn) {
      expect.soft(c.料号, `AC-R6②：子件行「料号」应 == nodeId 末段 ${c.nodeIdTail}，实际 = ${c.料号}`)
        .toBe(c.nodeIdTail);
    }
    expect.soft(childPn.map((c) => c.项次).slice().sort(),
      `AC-R6③：子件行项次应为 1、2 且不重复，实际 = ${JSON.stringify(childPn.map((c) => c.项次))}`)
      .toEqual(['1', '2']);
    expect.soft(sameAsRefresh,
      `AC-R6④：不刷新与刷新后应逐字节一致。\n  不刷新 = ${JSON.stringify(nrRows)}\n  刷新后 = ${JSON.stringify(bomRefresh.rows)}`)
      .toBe(true);
  }

  H.writeEvidence(`SA-主流程-结论-${H.RUN}.txt`,
    `RUN=${H.RUN}\n单=${fx.quotationNumber} (${fx.quotationId})\nline_item=${topLine.id} 料号=${topLine.pn}\n`
    + `CPN=${CPN}\n`
    + `AC-R1① customer_part_no=${topLine.cpn}\n`
    + `AC-R1② 产品页签客户产品编号列=${JSON.stringify(prodCpnCells)}\n`
    + `AC-R1③ snapshot_rows=${JSON.stringify(snapCpn.map((r) => r.cpn))}\n`
    + `AC-R2 刷新后 行数=${bomRefresh.rows.length} 带nodeId=${withNode.length} 缩进行=${indented.length} 全空行=${empties.length}\n`
    + `AC-R3 项次列=${JSON.stringify(seqCells)}\n`
    + `AC-R6 ${r6.notes.join('\n     ')}\n`);
});

/**
 * AC-R4（边界）：模板确实没绑组件的**合法空卡片**，行为与改动前逐字一致、不无限转圈。
 * 🚫 纯只读：不建单、不写库。对照单为现成的空卡片单（**勿删**）。
 * 🧪 A/B：同一张单分别在「改动后」(PW_BASE_URL) 与「改动前」(PW_BASELINE_URL, 主仓 master 5174) 打开，
 *        比对 Spin 消失、页面文案、ensure-card-values 调用数。
 */
test('AC-R4 · 合法空卡片单：不无限转圈、不误触 warm（改动前后 A/B）', async ({ page }) => {
  const EV = `SA-AC-R4-${H.RUN}.txt`;
  const emptyId = H.sqlScalar(
    `SELECT id::text FROM quotation WHERE id::text LIKE '75426cf9%' LIMIT 1`);
  expect(emptyId, 'AC-R4 前置：空卡片对照单 75426cf9… 查不到 ⇒ 判【未验证】').toMatch(/^[0-9a-f-]{36}$/);
  const info = H.sqlRO(
    `SELECT q.quotation_number, q.status, count(li.id) AS lines,
            count(*) FILTER (WHERE li.template_id IS NULL) AS tpl_null,
            count(*) FILTER (WHERE li.quote_card_values IS NULL OR li.costing_card_values IS NULL) AS any_null,
            count(*) FILTER (WHERE li.quote_card_values::text LIKE '%__cardValueFailed%'
                               OR li.costing_card_values::text LIKE '%__cardValueFailed%') AS failed
       FROM quotation q LEFT JOIN quotation_line_item li ON li.quotation_id=q.id
      WHERE q.id::text='${emptyId}' GROUP BY 1,2`);
  console.log('[AC-R4 对照单] ' + info);
  H.appendEvidence(EV, `\n===== ${new Date().toISOString()} =====\n对照单=${emptyId}\n${info}\n`);
  const anyNull = H.sqlScalar(`SELECT count(*) FROM quotation_line_item
     WHERE quotation_id='${emptyId}' AND (quote_card_values IS NULL OR costing_card_values IS NULL
       OR quote_card_values::text LIKE '%__cardValueFailed%'
       OR costing_card_values::text LIKE '%__cardValueFailed%')`);
  expect(anyNull, 'AC-R4 前置：对照单应是**合法空卡片**（两侧非 NULL、无失败哨兵）⇒ 否则断言换了对象').toBe('0');

  async function openAndMeasure(base: string, label: string) {
    const ensureCalls: string[] = [];
    page.removeAllListeners('request');
    page.on('request', (r: any) => {
      if (r.url().includes('/ensure-card-values')) ensureCalls.push(`${r.method()} ${r.url()}`);
    });
    await page.goto(`${base}/login`);
    await page.locator('input[placeholder="用户名或邮箱"]').fill('admin');
    await page.locator('input[placeholder="密码"]').fill('Admin@2026');
    await page.locator('button[type="submit"]').click();
    await page.waitForTimeout(5000);
    const t0 = Date.now();
    await page.goto(`${base}/quotations/${emptyId}/edit`);
    await page.waitForSelector('.ant-steps-item', { timeout: 90_000 });
    let spinGone = true;
    await page.waitForFunction(() => document.querySelectorAll('.ant-spin-spinning').length === 0,
      undefined, { timeout: 60_000 }).catch(() => { spinGone = false; });
    const elapsed = Date.now() - t0;
    const bodyText = (await page.locator('body').innerText()).replace(/\s+/g, ' ').slice(0, 300);
    const out = { label, base, spinGone, elapsed, ensureCalls, bodyText };
    console.log(`[AC-R4·${label}] ` + JSON.stringify(out));
    H.appendEvidence(EV, `${label}: ${JSON.stringify(out, null, 1)}\n`);
    await H.shot(page, `SA-06-AC-R4-${label}-${H.RUN}`);
    return out;
  }

  const after = await openAndMeasure(H.BASE_URL, '改动后-5208');
  const baselineUrl = process.env.PW_BASELINE_URL || '';
  const before = baselineUrl ? await openAndMeasure(baselineUrl, '改动前-master') : null;

  expect.soft(after.spinGone, `AC-R4：空卡片单 60s 内 Spin 必须消失（不无限转圈）。实测 ${after.elapsed}ms`).toBe(true);
  expect.soft(after.bodyText, 'AC-R4：不得停在「卡片数据准备失败」').not.toContain('卡片数据准备失败');
  expect.soft(after.ensureCalls.length,
    `AC-R4：合法空卡片单打开时不应触发 ensure-card-values。实际 = ${JSON.stringify(after.ensureCalls)}`).toBe(0);
  if (before) {
    expect.soft(after.spinGone, `AC-R4 A/B：改动前 spinGone=${before.spinGone}，改动后应一致`).toBe(before.spinGone);
    expect.soft(after.ensureCalls.length,
      `AC-R4 A/B：ensure 调用数应与改动前一致（前=${before.ensureCalls.length} 后=${after.ensureCalls.length}）`)
      .toBe(before.ensureCalls.length);
    expect.soft(after.bodyText.slice(0, 120),
      `AC-R4 A/B：页面文案应与改动前一致\n  前=${before.bodyText.slice(0, 120)}\n  后=${after.bodyText.slice(0, 120)}`)
      .toBe(before.bodyText.slice(0, 120));
  }
  H.writeEvidence(`SA-AC-R4-结论-${H.RUN}.txt`,
    `对照单=${emptyId}\n改动后=${JSON.stringify(after)}\n改动前=${JSON.stringify(before)}\n`);
});
