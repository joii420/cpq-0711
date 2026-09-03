/**
 * E2E · task-260901 —— F. 反向 AC：不许把功能改没了（需求文档 §③ F）
 *
 * 覆盖：T-3(AC-3, E2E 侧) · T-19(AC-19) · T-20(AC-20) · T-21(AC-21) · T-22(AC-22) · T-23(AC-23)
 *
 * 🚫 不 import src/。
 *
 * ⚠️ 共享库副作用登记：
 *   · T-3 会在**沙箱单**上先加一行再删一行（自还原，整单行数回到初值）。
 *   · T-22 会把沙箱单提交审批，随后在 finally 里 POST /withdraw 还原为 DRAFT，并断言还原成功。
 *   · T-21 会把沙箱单某一行的 annual_volume 改成 100（业务数据，不还原）。
 */
import { test, expect } from '@playwright/test';
import {
  BASELINE_QUOTATION_ID, SANDBOX_QUOTATION_ID,
  AC1_TAB_NAME, AC1_ROW_INDEX, AC1_COLUMN_NAME, AC1_TARGET_VALUE,
  assertBaselineShape, assertSandboxShape,
  loginAdmin, openEditStep2, cardByPartNo, openCardTab, editCell, readCellInputValue, distinctFrom,
  saveDraftCapture, queryLineAt, queryLineItemCount, queryLineSubTableCounts, queryProcessFingerprint,
  addFirstExistingProduct,
  queryNullCardValueCounts, queryQuotationHeader, queryRowDataOf, psqlScalar, psqlRows,
  archiveShot, archiveJson,
  waitBackendUp,
} from './fixtures/task260901-draft';

let backendUp = false;
test.beforeAll(async () => {
  backendUp = await waitBackendUp();
  if (backendUp) { assertBaselineShape(); assertSandboxShape(); }
});

// ══════════════════════════════════════════════════════════════════════════
// T-3 / AC-3（2026-09-01 判据已改，见 需求文档.md AC-3 的 📌 注）
// AC 原文：「删除 1 个产品行后保存。请求体 removed 数组为该行 id 的单元素数组；added/modified 为空。
//           保存后库中该行的 quotation_line_item 与 quotation_line_component_data 记录均已删除
//           （各 0 行），且**未列入 removed 的其它行一行未少**。」
//
// 🚨 用户 2026-09-01 裁决：`quotation_line_process` / `quotation_line_composite_process` /
//    `quotation_line_item_snapshot` 在 dev 库**全库 0 行**，E2E 层对它们的断言是 0==0 的空跑。
//    这三张表的**权威判据已移到后端** `Task260901IncrementalProtocolHttpTest#ac3_...`
//    （自建工序/快照数据，前置计数 >0）。本用例对它们只做**观察**，且必须显式打印
//    「本轮无分辨力」——不得让 0==0 冒充绿。
//
// 自还原：先加一行再删这一行，整单行数回到初值。
// ══════════════════════════════════════════════════════════════════════════
test('T-3 删除 1 个产品行后保存：removed 为该行 id 的单元素数组，行与其子表记录清零', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(1_500_000);

  const linesBefore = queryLineItemCount(SANDBOX_QUOTATION_ID);
  await loginAdmin(page);
  await openEditStep2(page, SANDBOX_QUOTATION_ID);

  // ── 先加一行（避免删掉基线数据）──
  const addedPartNo = await addFirstExistingProduct(page);

  const knownBefore = new Set(psqlRows(
    `SELECT id::text FROM quotation_line_item WHERE quotation_id='${SANDBOX_QUOTATION_ID}';`).map((r) => r[0]));
  const cap1 = await saveDraftCapture(page, 200);
  expect(cap1.payload?.added?.length, 'T-3 前置：应先成功新增 1 行').toBe(1);
  const idsAfterAdd = psqlRows(
    `SELECT id::text FROM quotation_line_item WHERE quotation_id='${SANDBOX_QUOTATION_ID}';`).map((r) => r[0]);
  const newId = idsAfterAdd.find((id) => !knownBefore.has(id));
  expect(newId, 'T-3 前置：应能识别出新增的那一行 id').toBeTruthy();

  // ── 前置计数（非空守卫 + 无分辨力标注）──
  const sub = queryLineSubTableCounts(newId!);
  const linesAfterAdd = queryLineItemCount(SANDBOX_QUOTATION_ID);
  console.log(`[T-3] 待删行 ${newId} 的子表前置计数 = ${JSON.stringify(sub)}；整单行数 = ${linesAfterAdd}`);
  archiveJson('T-3-subtable-before', { ...sub, linesBefore, linesAfterAdd });
  expect(sub.lineItem, 'T-3 前置：该行必须存在').toBe(1);
  expect(sub.componentData, 'T-3 前置：componentData 必须 >0，否则「删除后为 0」是空跑').toBeGreaterThan(0);
  expect(linesAfterAdd, 'T-3 前置：加行后整单应恰好多 1 行').toBe(linesBefore + 1);
  for (const [tbl, n] of [
    ['quotation_line_process', sub.process],
    ['quotation_line_item_snapshot', sub.lineItemSnapshot],
  ] as const) {
    if (n === 0) {
      console.warn(
        `[T-3] ⚠️ 本轮无分辨力：${tbl} 前置计数为 0（dev 库全库 0 行），` +
        '对它「删除后为 0」的观察是 0==0 的空跑。' +
        '该表的权威判据在后端 Task260901IncrementalProtocolHttpTest#ac3_removedLineAndAllFourSubTablesGoToZero。'
      );
    }
  }

  // ── 删除这一行 ──
  const newPartNo = psqlScalar(`SELECT coalesce(product_part_no_snapshot,'') FROM quotation_line_item WHERE id='${newId}';`);
  // 同 fixtures 的 cardByPartNo：徽标是「客户产品编号: Axxx」，按整卡文本匹配定长料号
  const cards = page.locator('.qt-product-card').filter({
    hasText: new RegExp(`(?<!\\d)${newPartNo}(?!\\d)`),
  });
  const cardCount = await cards.count();
  expect(cardCount, `T-3：应能定位到料号 ${newPartNo} 的产品卡`).toBeGreaterThan(0);
  const targetCard = cards.last();
  await targetCard.scrollIntoViewIfNeeded();
  const delBtn = targetCard.getByRole('button', { name: /删除|移除/ }).first();
  await expect(delBtn, 'T-3：产品卡上应有删除按钮').toBeVisible({ timeout: 20_000 });
  await delBtn.click();
  await page.waitForTimeout(600);
  const confirmDel = page.locator('.ant-popover, .ant-modal-confirm').getByRole('button', { name: /确定|确认|删除/ }).last();
  if (await confirmDel.isVisible().catch(() => false)) { await confirmDel.click(); }
  await page.waitForTimeout(2500);
  await archiveShot(page, 'T-3-after-delete-click');

  const cap2 = await saveDraftCapture(page, 200);
  archiveJson('T-3-request-payload', {
    added: cap2.payload?.added, modified: (cap2.payload?.modified ?? []).map((m: any) => m?.id),
    removed: cap2.payload?.removed, deletedId: newId,
  });

  expect(cap2.payload?.removed, 'AC-3：removed 必须是该行 id 的单元素数组').toEqual([newId]);
  expect(cap2.payload?.added, 'AC-3：added 必须为空').toEqual([]);
  expect(cap2.payload?.modified, 'AC-3：modified 必须为空').toEqual([]);

  const after = queryLineSubTableCounts(newId!);
  console.log(`[T-3] 删除后子表计数 = ${JSON.stringify(after)}`);
  archiveJson('T-3-subtable-after', after);

  // ── AC-3 的两条硬判据（这两张表在本库有真实数据，断言有分辨力）──
  expect(after.lineItem, 'AC-3：quotation_line_item 应为 0 行').toBe(0);
  expect(after.componentData,
    `AC-3：quotation_line_component_data 应为 0 行（前置 ${sub.componentData} 行）`).toBe(0);

  // ── 观察项：前置为 0 时只记录，不冒充判据 ──
  console.log(
    `[T-3][观察] quotation_line_process ${sub.process} → ${after.process}；` +
    `quotation_line_item_snapshot ${sub.lineItemSnapshot} → ${after.lineItemSnapshot}` +
    (sub.process === 0 && sub.lineItemSnapshot === 0 ? '（两者前置皆 0 ⇒ 本轮无分辨力）' : '')
  );
  if (sub.process > 0) expect(after.process, 'AC-3：quotation_line_process 应为 0 行').toBe(0);
  if (sub.lineItemSnapshot > 0) expect(after.lineItemSnapshot, 'AC-3：quotation_line_item_snapshot 应为 0 行').toBe(0);

  // ── AC-3 新增判据：未列入 removed 的其它行一行未少 ──
  const linesFinal = queryLineItemCount(SANDBOX_QUOTATION_ID);
  expect(linesFinal,
    `AC-3：只应删掉 removed 里的那 1 行 —— 其它行一行未少。期望 ${linesBefore} 行，实际 ${linesFinal} 行` +
    '（少于期望 = 显式删除语义没生效，退回了「payload 里没出现 = 删」的老语义）'
  ).toBe(linesBefore);
});

// ══════════════════════════════════════════════════════════════════════════
// T-19 / AC-19（反向）
// AC 原文：「保存后，核价侧数据完整：该单 costing_card_values IS NULL 的行数与保存前一致
//           （除被改的那 1 行外不新增空值），详情页核价视图各页签有数据、不显示『暂无组件数据』。」
// ══════════════════════════════════════════════════════════════════════════
test('T-19 保存后核价侧完整：costing 空值只多 1 行，详情页核价视图不显示「暂无组件数据」', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(1_200_000);

  const before = queryNullCardValueCounts(BASELINE_QUOTATION_ID);
  console.log(`[T-19] 保存前 costing NULL = ${before.costingNull} / ${before.total}`);

  const target = queryLineAt(BASELINE_QUOTATION_ID, 0);
  await loginAdmin(page);
  await openEditStep2(page, BASELINE_QUOTATION_ID);
  const card = await cardByPartNo(page, target.partNo);
  await openCardTab(page, card, AC1_TAB_NAME);
  const cur = await readCellInputValue(card, AC1_ROW_INDEX, AC1_COLUMN_NAME);
  await editCell(page, card, AC1_ROW_INDEX, AC1_COLUMN_NAME, distinctFrom(cur, AC1_TARGET_VALUE));
  await saveDraftCapture(page, 200);

  const after = queryNullCardValueCounts(BASELINE_QUOTATION_ID);
  archiveJson('T-19-costing-null', { before, after });
  console.log(`[T-19] 保存后 costing NULL = ${after.costingNull}`);
  expect(after.costingNull,
    `AC-19：costing_card_values 空值行数最多只应比保存前多 1（被改的那行），实际 ${before.costingNull} → ${after.costingNull}`
  ).toBeLessThanOrEqual(before.costingNull + 1);

  // 等核价侧补算完，再看详情页
  const deadline = Date.now() + 300_000;
  while (Date.now() < deadline) {
    const n = Number(psqlScalar(
      `SELECT count(*) FROM quotation_line_item WHERE quotation_id='${BASELINE_QUOTATION_ID}' AND costing_card_values IS NULL;`));
    if (n <= before.costingNull) break;
    await page.waitForTimeout(2000);
  }

  await page.goto(`/quotations/${BASELINE_QUOTATION_ID}`);
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(3000);
  const costingTab = page.locator('.ant-segmented-item', { hasText: '核价单' }).first();
  await expect(costingTab, 'AC-19：详情页应有「核价单」视图').toBeVisible({ timeout: 60_000 });
  await costingTab.click();
  await page.waitForTimeout(4000);
  await archiveShot(page, 'T-19-costing-detail');

  // 结果非空守卫：先证明核价卡片真的渲染出来了，再断言没有空态文案
  await expect(page.locator('.qt-product-card').first(), 'AC-19：核价视图应渲染出产品卡（非空守卫）')
    .toBeVisible({ timeout: 120_000 });
  await expect(page.getByText('暂无组件数据', { exact: false }),
    'AC-19：核价视图不得出现「暂无组件数据」').toHaveCount(0);
  await expect(page.getByText('加载中', { exact: false }),
    'AC-19：核价视图不得残留「加载中…」占位').toHaveCount(0);
});

// ══════════════════════════════════════════════════════════════════════════
// T-20 / AC-20（反向）
// AC 原文：「保存后，quotation_line_process（工序）、quotation_line_composite_process（选配-组合工艺）
//           的记录数与内容和保存前一致（未改动的行不丢工序）。」
//
// 🚨 dev 库上这两张表**全库 0 行**（2026-09-01 SQL 实测）→ 本条在 E2E 层是 0==0 的空跑。
//    用户 2026-09-01 裁决：**权威判据移到后端自建夹具**
//    （Task260901IncrementalProtocolHttpTest#ac20_processRowsSurviveIncrementalSave，前置 >0）；
//    E2E 层保留观察，但**必须显式打印「本轮无分辨力」**，不得让它冒充绿。
// ══════════════════════════════════════════════════════════════════════════
test('T-20 保存后工序 / 选配组合工艺的条数与内容不变', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(1_200_000);

  const before = queryProcessFingerprint(BASELINE_QUOTATION_ID);
  console.log(`[T-20] 保存前：process=${before.processCount}(${before.processMd5}) composite=${before.compositeCount}(${before.compositeMd5})`);
  const vacuous = before.processCount === 0 && before.compositeCount === 0;
  if (vacuous) {
    console.warn(
      '[T-20] ⚠️ **本轮无分辨力**：基准单的 quotation_line_process / quotation_line_composite_process ' +
      '前置计数均为 0（dev 库全库 0 行），「保存后一致」退化成 0==0 的空跑，**不构成 AC-20 的验收证据**。' +
      'AC-20 的权威判据在后端 Task260901IncrementalProtocolHttpTest#ac20_processRowsSurviveIncrementalSave' +
      '（自建工序数据，前置 >0）。用户 2026-09-01 裁决。'
    );
  }

  const target = queryLineAt(BASELINE_QUOTATION_ID, 0);
  await loginAdmin(page);
  await openEditStep2(page, BASELINE_QUOTATION_ID);
  const card = await cardByPartNo(page, target.partNo);
  await openCardTab(page, card, AC1_TAB_NAME);
  const cur = await readCellInputValue(card, AC1_ROW_INDEX, AC1_COLUMN_NAME);
  await editCell(page, card, AC1_ROW_INDEX, AC1_COLUMN_NAME, distinctFrom(cur, AC1_TARGET_VALUE));
  await saveDraftCapture(page, 200);

  const after = queryProcessFingerprint(BASELINE_QUOTATION_ID);
  archiveJson('T-20-process-fingerprint', { before, after, vacuous });
  expect(after.processCount, 'AC-20：工序条数不得变化').toBe(before.processCount);
  expect(after.processMd5, 'AC-20：工序内容指纹不得变化').toBe(before.processMd5);
  expect(after.compositeCount, 'AC-20：选配组合工艺条数不得变化').toBe(before.compositeCount);
  expect(after.compositeMd5, 'AC-20：选配组合工艺内容指纹不得变化').toBe(before.compositeMd5);
});

// ══════════════════════════════════════════════════════════════════════════
// T-21 / AC-21（反向）
// AC 原文：「在 Step3 修改年用量为 100 后点『下一步』，保存成功且库中 annual_volume = 100。」
// 防的是 repair-260830 的陷阱：Step3 初始物化走程序化通道，增量判定漏掉它 → Step3 的改动永不落库。
// ══════════════════════════════════════════════════════════════════════════
test('T-21 Step3 改年用量为 100 后点下一步：保存成功且库中 annual_volume = 100', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(1_200_000);

  // 🚨 防假绿（2026-09-01 实测踩到）：AC-21 的判据若写成「库中存在 annual_volume=100 的行」，
  //    第二轮起就恒真——上一轮已经写过 100 了，保存什么都不做也会绿，而 AC-21 恰恰是防
  //    「Step3 的改动永不落库」的，前置已满足的断言对此零分辨力。
  //    改为：每轮挑一行**当前值不是 100** 的来改，并断言它由「旧值」变成 100。
  //    沙箱单 1845 行，永远有得挑。
  // 🚨 row_number() 必须在**未过滤的整表**上求值：窗口函数在 WHERE 之后执行，
  //    若把 `annual_volume IS DISTINCT FROM 100` 写进同一层的 WHERE，
  //    算出来的是「过滤后集合」的序号，而 Step3 渲染的是整表 —— 下标会错位。
  //    （2026-09-01 实测：过滤后第 1 行是 202601010002，它在整表里其实是第 2 行。）
  const target = psqlRows(
    `SELECT idx, part_no, av FROM (` +
    `  SELECT (row_number() OVER (ORDER BY sort_order))-1 AS idx, product_part_no_snapshot AS part_no, ` +
    `         coalesce(annual_volume::text,'<NULL>') AS av, annual_volume ` +
    `  FROM quotation_line_item WHERE quotation_id='${SANDBOX_QUOTATION_ID}'` +
    `) t WHERE t.annual_volume IS DISTINCT FROM 100 ORDER BY idx LIMIT 1;`
  )[0];
  expect(target, 'AC-21 前置：沙箱单里应存在 annual_volume ≠ 100 的行；全是 100 说明夹具需重置').toBeTruthy();
  const [rowIdxStr, targetPartNo, beforeValue] = target;
  const rowIdx = Number(rowIdxStr);
  console.log(`[T-21] 目标行：Step3 第 ${rowIdx + 1} 行，料号 ${targetPartNo}，改前 annual_volume = ${beforeValue}`);
  expect(beforeValue, 'AC-21 前置：目标行改前不得已经是 100，否则本条无分辨力').not.toBe('100');

  await loginAdmin(page);
  await openEditStep2(page, SANDBOX_QUOTATION_ID);

  // Step2 → Step3
  const next = page.getByRole('button', { name: /下一步/ }).first();
  await expect(next).toBeEnabled({ timeout: 120_000 });
  await next.click();
  await page.waitForTimeout(4000);
  await archiveShot(page, 'T-21-step3');

  // 定位「年用量」列的输入框；找不到就硬失败并打印实际表头（不静默跳过）
  const table = page.locator('table:visible').first();
  await expect(table, 'Step3 应渲染出表格').toBeVisible({ timeout: 60_000 });
  // 探针实测 Step3 = 「优惠策略」，表头 = 产品/原小计(单价)/年用量/折扣来源/折扣率(%)/折扣金额/折后单价/行合计
  await expect(page.locator('.ant-steps-item-active'), 'AC-21：应已进入 Step3')
    .toContainText('优惠策略', { timeout: 30_000 });
  const headers = await table.locator('thead tr').last().locator('th').allInnerTexts();
  const colIdx = headers.findIndex((t) => t.replace(/\s+/g, '').includes('年用量'));
  expect(colIdx,
    `AC-21：Step3 表格应有「年用量」列；实际表头 = ${JSON.stringify(headers)}`
  ).toBeGreaterThanOrEqual(0);

  // 🚨 用 .ant-table-row，不能用 tbody tr —— antd 的第一个 tbody tr 是 **measure row**（无内容、无 input）。
  //    2026-09-01 探针实测：tbody tr = 1846 / .ant-table-row = 1845，
  //    对 tbody tr 取 .first() 拿到的就是那行空的，locator('input') 恒为 0。
  const firstRow = table.locator('.ant-table-row').nth(rowIdx);
  await expect(firstRow, `AC-21：Step3 应有第 ${rowIdx + 1} 行`).toBeVisible({ timeout: 30_000 });
  // 🔒 行下标 ↔ DB 行的对应关系必须验证，不能假定 Step3 的渲染顺序恒等于 sort_order
  await expect(firstRow, `AC-21：Step3 第 ${rowIdx + 1} 行应是料号 ${targetPartNo}（下标与 DB 对不上会改错行）`)
    .toContainText(targetPartNo, { timeout: 20_000 });
  // 「年用量」是 antd InputNumber：<div class="ant-input-number"><input role="spinbutton">
  const input = firstRow.locator('td').nth(colIdx).locator('input');
  await expect(input, 'AC-21：「年用量」应是可编辑输入框').toHaveCount(1, { timeout: 20_000 });
  // 🚨 antd InputNumber 只认真实键入：fill() 直接设 value 时 React 的 onChange 可能不触发，
  //    值会在失焦后被回退。repair-260830 在单头字段上踩过同一个坑，这里是同型问题。
  //    用 click → 全选删除 → pressSequentially 逐字键入，贴近真实用户输入。
  await input.click();
  await input.press('ControlOrMeta+a');
  await input.press('Delete');
  await input.pressSequentially('100', { delay: 40 });
  await input.blur();
  await page.waitForTimeout(1500);
  await expect(input, 'AC-21：输入未生效（antd InputNumber 回退了值），本用例此时无分辨力')
    .toHaveValue('100');

  const waiter = page.waitForResponse(
    (r) => r.request().method() === 'PUT' && /\/quotations\/[^/]+\/draft/.test(r.url()),
    { timeout: 300_000 }
  );
  await page.getByRole('button', { name: /下一步/ }).first().click();
  const resp = await waiter;
  // ⚠️ 响应体只用于丰富失败信息，不能让它成为失败源：Chrome 的 inspector 缓存会淘汰已读完的
  //    响应体，resp.text() 会抛 Protocol error (Network.getResponseBody)。2026-09-01 实测因此
  //    把一条功能上已通过的用例判成红。
  const body = await resp.text().catch((e) => `<响应体已被 inspector 缓存淘汰：${String(e).slice(0, 80)}>`);
  console.log(`[T-21] 点「下一步」触发的 PUT /draft → ${resp.status()}`);
  expect(resp.status(), `AC-21：保存应成功（响应体：${body.slice(0, 300)}）`).toBe(200);
  await page.waitForTimeout(3000);

  const afterValue = psqlScalar(
    `SELECT coalesce(annual_volume::text,'<NULL>') FROM quotation_line_item ` +
    `WHERE quotation_id='${SANDBOX_QUOTATION_ID}' AND product_part_no_snapshot='${targetPartNo}';`
  );
  archiveJson('T-21-annual-volume', { rowIdx, targetPartNo, beforeValue, afterValue });
  console.log(`[T-21] 料号 ${targetPartNo} 的 annual_volume：${beforeValue} → ${afterValue}`);
  expect(afterValue,
    `AC-21：目标行的 annual_volume 应由 ${beforeValue} 变为 100（这一轮真的写进去了，不是上一轮的残留）`
  ).toBe('100');
});

test('T-23 序列：改 A 保存 → 改 B 保存 → 刷新，两处改动都在且卡片值都是新值', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(1_800_000);

  const lineA = queryLineAt(BASELINE_QUOTATION_ID, 0);
  const lineB = queryLineAt(BASELINE_QUOTATION_ID, 1);
  expect(lineA.id, 'A/B 必须是两条不同的行').not.toBe(lineB.id);
  expect(lineA.partNo, 'A 行应有料号').not.toBe('');
  expect(lineB.partNo, 'B 行应有料号').not.toBe('');
  console.log(`[T-23] A=${lineA.partNo}(${lineA.id})  B=${lineB.partNo}(${lineB.id})`);

  await loginAdmin(page);
  await openEditStep2(page, BASELINE_QUOTATION_ID);

  // ── 改 A → 保存 ──
  const cardA = await cardByPartNo(page, lineA.partNo);
  await openCardTab(page, cardA, AC1_TAB_NAME);
  const curA = await readCellInputValue(cardA, AC1_ROW_INDEX, AC1_COLUMN_NAME);
  const valA = distinctFrom(curA, '7.1');
  await editCell(page, cardA, AC1_ROW_INDEX, AC1_COLUMN_NAME, valA);
  const capA = await saveDraftCapture(page, 200);
  expect((capA.payload?.modified ?? []).map((m: any) => String(m?.id)),
    'T-23：第一次保存的 modified 应命中 A 行').toContain(lineA.id);

  // ── 改 B → 保存 ──
  const cardB = await cardByPartNo(page, lineB.partNo);
  await openCardTab(page, cardB, AC1_TAB_NAME);
  const curB = await readCellInputValue(cardB, AC1_ROW_INDEX, AC1_COLUMN_NAME);
  const valB = distinctFrom(curB, '7.2');
  await editCell(page, cardB, AC1_ROW_INDEX, AC1_COLUMN_NAME, valB);
  const capB = await saveDraftCapture(page, 200);
  const modB = (capB.payload?.modified ?? []).map((m: any) => String(m?.id));
  expect(modB, 'T-23：第二次保存的 modified 应命中 B 行').toContain(lineB.id);
  expect(modB, 'T-23：第二次保存不应重复携带已保存的 A 行（那是全量回退的信号）').not.toContain(lineA.id);

  // ── 等两行卡片值都补算回来 ──
  const deadline = Date.now() + 300_000;
  while (Date.now() < deadline) {
    const n = Number(psqlScalar(
      `SELECT count(*) FROM quotation_line_item WHERE id IN ('${lineA.id}','${lineB.id}') AND quote_card_values IS NOT NULL;`));
    if (n === 2) break;
    await page.waitForTimeout(2000);
  }

  // ── 刷新页面 ──
  await page.reload();
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(4000);
  await archiveShot(page, 'T-23-after-reload');

  // ── 库侧：两处改动都在 ──
  const rdA = queryRowDataOf(lineA.id, AC1_TAB_NAME);
  const rdB = queryRowDataOf(lineB.id, AC1_TAB_NAME);
  expect(rdA, 'T-23：A 行 row_data 不应为空').not.toBe('');
  expect(rdB, 'T-23：B 行 row_data 不应为空').not.toBe('');
  expect(rdA, `AC-23：A 行的改动 ${valA} 必须还在。实际 ${rdA}`).toContain(valA);
  expect(rdB, `AC-23：B 行的改动 ${valB} 必须还在。实际 ${rdB}`).toContain(valB);

  // ── 卡片值都是新值：card values 非空且含新值 ──
  for (const [label, line, val] of [['A', lineA, valA], ['B', lineB, valB]] as const) {
    const cv = psqlScalar(`SELECT coalesce(quote_card_values::text,'') FROM quotation_line_item WHERE id='${line.id}';`);
    expect(cv, `AC-23：${label} 行卡片值不应为空（非空守卫）`).not.toBe('');
    expect(cv, `AC-23：${label} 行卡片值应含新值 ${val}`).toContain(val);
  }

  // ── UI 侧：刷新后页面上仍是新值 ──
  const cardA2 = await cardByPartNo(page, lineA.partNo);
  await openCardTab(page, cardA2, AC1_TAB_NAME);
  expect(await readCellInputValue(cardA2, AC1_ROW_INDEX, AC1_COLUMN_NAME),
    `AC-23：刷新后 A 行页面上应显示 ${valA}`).toBe(valA);
  const cardB2 = await cardByPartNo(page, lineB.partNo);
  await openCardTab(page, cardB2, AC1_TAB_NAME);
  expect(await readCellInputValue(cardB2, AC1_ROW_INDEX, AC1_COLUMN_NAME),
    `AC-23：刷新后 B 行页面上应显示 ${valB}`).toBe(valB);
});

// ══════════════════════════════════════════════════════════════════════════
// T-22 / AC-22（序列）
// AC 原文：「走完整提交流程（Step1→…→提交审批），单据状态变为已提交，总价与保存前一致。」
// ⚠️ 本用例放在最后：它会把沙箱单提交掉。finally 里 POST /withdraw 还原为 DRAFT 并断言还原成功。
// ══════════════════════════════════════════════════════════════════════════
test('T-22 走完整提交流程：状态变为已提交，总价与提交前一致', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(1_800_000);

  const before = queryQuotationHeader(SANDBOX_QUOTATION_ID);
  console.log(`[T-22] 提交前 status=${before.status} totalAmount=${before.totalAmount}`);
  expect(before.status, 'T-22 前置：沙箱单必须是 DRAFT').toBe('DRAFT');
  expect(before.totalAmount, 'T-22 前置：提交前总价不应为空（否则"总价一致"是空跑）').not.toBe('');

  await loginAdmin(page);
  try {
    await page.goto(`/quotations/${SANDBOX_QUOTATION_ID}/edit`);
    await expect(page.getByRole('button', { name: /下一步/ })).toBeEnabled({ timeout: 300_000 });

    // Step1 → … → 提交步骤
    for (let i = 0; i < 6; i++) {
      const submit = page.getByRole('button', { name: /提交审批$/ });
      if (await submit.count() === 1 && await submit.isVisible().catch(() => false)) break;
      const next = page.getByRole('button', { name: /下一步/ }).first();
      await expect(next, 'AC-22：应可继续到提交审批步骤').toBeEnabled({ timeout: 300_000 });
      await next.click();
      await page.waitForTimeout(3000);
    }
    const submit = page.getByRole('button', { name: /提交审批$/ });
    await expect(submit, 'AC-22：提交步骤应唯一存在「提交审批」按钮').toHaveCount(1, { timeout: 60_000 });
    await submit.click();
    await page.waitForTimeout(2000);
    const confirm = page.locator('.ant-modal').getByRole('button', { name: /确定|确认|提交/ }).last();
    if (await confirm.isVisible().catch(() => false)) await confirm.click();
    await page.waitForTimeout(8000);
    await archiveShot(page, 'T-22-after-submit');

    const after = queryQuotationHeader(SANDBOX_QUOTATION_ID);
    archiveJson('T-22-submit', { before, after });
    console.log(`[T-22] 提交后 status=${after.status} totalAmount=${after.totalAmount}`);
    expect(after.status, 'AC-22：单据状态应变为已提交（SUBMITTED）').toBe('SUBMITTED');
    expect(Number(after.totalAmount), `AC-22：总价应与提交前一致（${before.totalAmount} vs ${after.totalAmount}）`)
      .toBeCloseTo(Number(before.totalAmount), 6);
  } finally {
    // 还原：撤回为 DRAFT（这是产品自带的状态机动作，不是绕过状态机的直改库）
    const cur = queryQuotationHeader(SANDBOX_QUOTATION_ID);
    if (cur.status !== 'DRAFT') {
      const res = await page.request.post(`/api/cpq/quotations/${SANDBOX_QUOTATION_ID}/withdraw`, { timeout: 300_000 });
      console.log(`[T-22] 还原撤回 → ${res.status()}`);
      const restored = queryQuotationHeader(SANDBOX_QUOTATION_ID);
      expect(restored.status,
        `T-22 还原失败：沙箱单仍是 ${restored.status}，后续用例会全线失效。请人工撤回 ${SANDBOX_QUOTATION_ID}。`
      ).toBe('DRAFT');
    }
  }
});
