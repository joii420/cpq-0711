/**
 * E2E · task-260901 —— D. 回传瘦身（需求文档 §③ D）
 *
 * 覆盖：T-15(AC-15) · T-16(AC-16) · T-17(AC-17)
 * 🚫 不 import src/。
 */
import { test, expect } from '@playwright/test';
import {
  BASELINE_QUOTATION_ID, SANDBOX_QUOTATION_ID,
  AC1_TAB_NAME, AC1_ROW_INDEX, AC1_COLUMN_NAME, AC1_TARGET_VALUE,
  assertBaselineShape, assertSandboxShape,
  loginAdmin, openEditStep2, cardByPartNo, openCardTab, editCell, readCellInputValue, distinctFrom,
  saveDraftCapture, queryLineAt, queryLineItemCount, queryLineItemCountById, psqlScalar, psqlRows,
  addFirstExistingProduct,
  archiveJson, archiveShot,
  waitBackendUp,
} from './fixtures/task260901-draft';

let backendUp = false;
test.beforeAll(async () => {
  backendUp = await waitBackendUp();
  if (backendUp) assertBaselineShape();
});

/**
 * AC-16 列的 6 个键 —— 按 `api.md §1.3`「tempId 的作用域」收敛表，这是 **`modified` 行**的完整键集。
 *
 * 🚨 `added` 行**另有第 7 个键 `tempId`**（新行认领 DB id 的唯一手段）。
 *    T-16 的断言对象必须限定在 `modified` 行；若把它套到 `added` 行上，后端为了过这条用例
 *    就会砍掉 `added` 行的 `tempId` —— 新增行永远拿不到 id ⇒ 下次保存重复插入，
 *    **正是 AC-17 要防的那件事**。两条 AC 会互相打死。
 */
const MODIFIED_LINE_KEYS = [
  'id', 'partVersionLocked', 'quoteCardValues', 'costingCardValues', 'quoteExcelValues', 'costingExcelValues',
];
/** `added` 行的键集 = 上面 6 个 + tempId。 */
const ADDED_LINE_KEYS = [...MODIFIED_LINE_KEYS, 'tempId'];

// ══════════════════════════════════════════════════════════════════════════
// T-15 / AC-15
// AC 原文：「执行 AC-1 后，PUT /draft 的响应体 < 500 KB（现状实测 24.6 MB），
//           且响应中 lineItems 数组只含被修改的那 1 行。」
// 🔑 必须打 1845 行基准单 —— 在小单上测"< 500 KB"是空跑（小单本来就小）。
// ══════════════════════════════════════════════════════════════════════════
test('T-15 改一个格子后保存：响应体 < 500 KB 且 lineItems 只含被改的那 1 行', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(900_000);

  const target = queryLineAt(BASELINE_QUOTATION_ID, 0);
  await loginAdmin(page);
  await openEditStep2(page, BASELINE_QUOTATION_ID);
  const card = await cardByPartNo(page, target.partNo);
  await openCardTab(page, card, AC1_TAB_NAME);
  const cur = await readCellInputValue(card, AC1_ROW_INDEX, AC1_COLUMN_NAME);
  await editCell(page, card, AC1_ROW_INDEX, AC1_COLUMN_NAME, distinctFrom(cur, AC1_TARGET_VALUE));

  const cap = await saveDraftCapture(page, 200);
  const lines = cap.responseBody?.data?.lineItems;
  archiveJson('T-15-response-meta', {
    responseBytes: cap.responseBytes, lineItemCount: Array.isArray(lines) ? lines.length : null,
    returnedIds: Array.isArray(lines) ? lines.map((l: any) => l?.id) : null,
  });
  console.log(`[T-15] 响应体 = ${cap.responseBytes} B（${(cap.responseBytes / 1024).toFixed(1)} KB）；lineItems = ${Array.isArray(lines) ? lines.length : 'N/A'} 行`);

  // 结果非空守卫：先证明响应确实带回了内容，再谈"小"
  expect(cap.responseBytes, '响应体不应为空（0 字节的响应当然 < 500 KB，那是假绿）').toBeGreaterThan(50);
  expect(cap.responseBody?.data, '响应必须有 data').toBeTruthy();
  expect(Array.isArray(lines), 'AC-15：响应 data.lineItems 应为数组').toBe(true);

  expect(cap.responseBytes, `AC-15：响应体必须 < 500 KB，实际 ${(cap.responseBytes / 1024).toFixed(1)} KB（现状基线 24.6 MB）`)
    .toBeLessThan(500 * 1024);
  expect(lines.length, 'AC-15：lineItems 只应含被修改的那 1 行').toBe(1);
  expect(String(lines[0]?.id), 'AC-15：回传的那一行必须就是被改的行').toBe(target.id);
});

// ══════════════════════════════════════════════════════════════════════════
// T-16 / AC-16
// AC 原文：「响应中每个 line 元素只含这 6 个键：id、partVersionLocked、quoteCardValues、
//           costingCardValues、quoteExcelValues、costingExcelValues（外加单头字段与 userDataVersion），
//           不含 componentData。」
//
// 🚨 断言对象**限定为 `modified` 行**（`test.md §1` AC-16 行 + `api.md §1.3` 收敛表）：
//    本用例只做「改一个格子」，请求里 added=[]，故响应里的行必然全部来自 modified。
//    用例最后额外断言「不含 tempId」—— 因为 tempId 是 added 行的专属键，出现在 modified 行上
//    说明后端没按作用域实现。`added` 行必须带 tempId 这件事由 T-2 / T-17 正面守。
// ══════════════════════════════════════════════════════════════════════════
test('T-16 modified 行的响应元素恰好 6 个键（无 tempId、无 componentData）', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(900_000);

  const target = queryLineAt(BASELINE_QUOTATION_ID, 0);
  await loginAdmin(page);
  await openEditStep2(page, BASELINE_QUOTATION_ID);
  const card = await cardByPartNo(page, target.partNo);
  await openCardTab(page, card, AC1_TAB_NAME);
  const cur = await readCellInputValue(card, AC1_ROW_INDEX, AC1_COLUMN_NAME);
  await editCell(page, card, AC1_ROW_INDEX, AC1_COLUMN_NAME, distinctFrom(cur, AC1_TARGET_VALUE));

  const cap = await saveDraftCapture(page, 200);

  // 🚨 前置：本用例只在 added 为空时成立（否则响应里会混入合法带 tempId 的 added 行）
  expect(cap.payload?.added ?? [], 'T-16 前置：本用例只改格子，added 必须为空，断言对象才全是 modified 行')
    .toEqual([]);
  const modifiedIds = new Set((cap.payload?.modified ?? []).map((m: any) => String(m?.id)));
  expect(modifiedIds.size, 'T-16 前置：modified 应至少有 1 行').toBeGreaterThan(0);

  const lines = cap.responseBody?.data?.lineItems;
  expect(Array.isArray(lines) && lines.length > 0, 'AC-16 前置：响应必须至少回传 1 行，否则键集断言空跑').toBe(true);

  for (const [i, line] of lines.entries()) {
    const keys = Object.keys(line).sort();
    console.log(`[T-16] lineItems[${i}] 键集 = ${JSON.stringify(keys)}`);
    archiveJson(`T-16-line-keys-${i}`, keys);
    expect(modifiedIds.has(String(line.id)),
      `T-16 前置：lineItems[${i}] 应是本次 modified 的行（id=${line.id}），否则它不在本用例的断言作用域内`).toBe(true);
    expect(keys.length,
      `AC-16：modified 行不应有多余键（恰好 ${MODIFIED_LINE_KEYS.length} 个），实际 ${JSON.stringify(keys)}`
    ).toBe(MODIFIED_LINE_KEYS.length);
    for (const k of MODIFIED_LINE_KEYS) {
      expect(keys, `AC-16：modified 行必须含键 ${k}，实际键集 = ${JSON.stringify(keys)}`).toContain(k);
    }
    expect(line.tempId,
      'api.md §1.3：tempId 是 added 行的专属键，modified 行不应带它（该行本来就有 DB id）').toBeUndefined();
    expect(line.componentData,
      `AC-16：lineItems[${i}] 绝不能含 componentData（9.3 MB、前端一个字节都没读）`).toBeUndefined();
  }

  expect(typeof cap.responseBody?.data?.userDataVersion, 'AC-16：响应根部应带 userDataVersion').toBe('number');
});

// ══════════════════════════════════════════════════════════════════════════
// T-17 / AC-17（序列·关键；2026-09-01 判据已改，见 需求文档.md AC-17 的 📌 注）
// AC 原文：「新增 1 个产品 → 保存（响应回传其新 id）→ 立即再修改这个新产品的一个格子 → 再次保存。
//           第二次保存的 modified 数组中该行 id 为**第一次保存返回的 DB id**（不是 null），
//           且**该 line_item id 在库中唯一存在**（SELECT count(*) FROM quotation_line_item
//           WHERE id = ? 恒为 1），未出现重复插入。」
//
// 📌 判据从「该产品只有 1 行」改为「该 line_item id 唯一存在」：CUST-0004 映射的 1845 个料号
//    已被基准/沙箱单用满，空闲料号为 0，任何新增都必然与既有行同料号 —— 原判据不可判定。
//
// 🚨 本用例同时是 `added` 行 `tempId` 契约（api.md §1.3 收敛表）的正面守卫：
//    T-16 要求 modified 行恰好 6 键，若后端把这条规则误套到 added 行上砍掉 tempId，
//    新行就永远认领不到 DB id ⇒ 第二次保存只能再发 id=null ⇒ 重复插入。这里必须红。
// ══════════════════════════════════════════════════════════════════════════
test('T-17 新增产品 → 保存 → 改新行一个格子 → 再保存：第二次 modified 带 DB id，该 id 在库中唯一', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(1_200_000);
  assertSandboxShape();

  const linesBefore = queryLineItemCount(SANDBOX_QUOTATION_ID);
  await loginAdmin(page);
  await openEditStep2(page, SANDBOX_QUOTATION_ID);

  // ── 步骤 1：从已有产品添加 1 个（🚫 不走选配入口，AC-2 已注明 sel_template 全库 0 行）──
  const addedPartNo = await addFirstExistingProduct(page);
  await archiveShot(page, 'T-17-after-add');

  // ── 步骤 2：第一次保存 → 按 tempId 认领新行的 DB id ──
  const knownIdsBefore = new Set(
    psqlRows(`SELECT id::text FROM quotation_line_item WHERE quotation_id='${SANDBOX_QUOTATION_ID}';`).map((r) => r[0])
  );
  const cap1 = await saveDraftCapture(page, 200);
  expect(cap1.payload?.added?.length, 'T-17 前置：第一次保存的 added 应为 1').toBe(1);
  expect(cap1.payload.added[0]?.id ?? null, 'T-17 前置：added[0].id 应为 null').toBeNull();
  const tempId = cap1.payload.added[0]?.tempId;
  expect(tempId,
    'api.md §1.3：added 行必须携带 tempId（前端生成的稳定 UUID）—— 它是新行认领 DB id 的唯一手段'
  ).toBeTruthy();

  const resp1Lines = cap1.responseBody?.data?.lineItems ?? [];
  expect(Array.isArray(resp1Lines) && resp1Lines.length > 0, 'AC-17：第一次保存必须回传行').toBe(true);
  const claimed = resp1Lines.find((l: any) => l?.tempId === tempId);
  expect(claimed,
    `api.md §1.3 收敛表：响应必须原样回传 added 行的 tempId=${tempId}（added 行 7 键），前端才认领得到新 id。` +
    `实际回传的行 = ${JSON.stringify(resp1Lines.map((l: any) => ({ id: l?.id, tempId: l?.tempId })))}` +
    '（🚫 若这里为空，多半是后端为了过 T-16「恰好 6 键」而把 added 行的 tempId 也砍了 —— 那正是 AC-17 要防的事）'
  ).toBeTruthy();
  const newRowId = String(claimed.id);
  expect(newRowId, 'AC-17：认领到的行必须带非空 DB id').not.toBe('null');
  expect(knownIdsBefore.has(newRowId), 'AC-17：回传的 id 应是**新**行的 id，不能是加产品之前就存在的行').toBe(false);
  archiveJson('T-17-first-save', {
    added: cap1.payload.added.map((a: any) => ({ id: a?.id, tempId: a?.tempId, sortOrder: a?.sortOrder })),
    respLines: resp1Lines.map((l: any) => ({ id: l?.id, tempId: l?.tempId })), newRowId, tempId,
  });

  // 该 id 在库中唯一存在（AC-17 修正版判据）
  expect(queryLineItemCountById(newRowId), 'AC-17：第一次保存后该 line_item id 应唯一存在').toBe(1);
  expect(queryLineItemCount(SANDBOX_QUOTATION_ID), 'AC-17：第一次保存后整单应恰好多 1 行').toBe(linesBefore + 1);

  // ── 步骤 3：改这个新产品的一个格子 ──
  const newPartNo = psqlScalar(`SELECT coalesce(product_part_no_snapshot,'') FROM quotation_line_item WHERE id='${newRowId}';`);
  expect(newPartNo, 'AC-17：新行应有料号（否则定位不到它的产品卡）').not.toBe('');
  // ⚠️ 沙箱单已用满 CUST-0004 的全部料号 ⇒ 同料号必然有多张卡，cardByPartNo 的「唯一」前提不成立，
  //    这里直接按「同料号的最后一张卡」定位新增行（新行 sortOrder 最大，渲染在最后）。
  // 同 fixtures 的 cardByPartNo：徽标是「客户产品编号: Axxx」，不含「料号:」，按整卡文本匹配定长料号
  const cards = page.locator('.qt-product-card').filter({
    hasText: new RegExp(`(?<!\\d)${newPartNo}(?!\\d)`),
  });
  const cardN = await cards.count();
  expect(cardN, `AC-17：应能定位到料号 ${newPartNo} 的产品卡`).toBeGreaterThan(0);
  console.log(`[T-17] 同料号产品卡 ${cardN} 张，取最后一张作为新增行`);
  const newCard = cards.last();
  await newCard.scrollIntoViewIfNeeded();
  await openCardTab(page, newCard, AC1_TAB_NAME);
  const cur = await readCellInputValue(newCard, AC1_ROW_INDEX, AC1_COLUMN_NAME);
  await editCell(page, newCard, AC1_ROW_INDEX, AC1_COLUMN_NAME, distinctFrom(cur, AC1_TARGET_VALUE));

  // ── 步骤 4：第二次保存 ──
  const cap2 = await saveDraftCapture(page, 200);
  const modifiedIds = (cap2.payload?.modified ?? []).map((m: any) => String(m?.id));
  archiveJson('T-17-second-save', {
    added: cap2.payload?.added, modified: modifiedIds, removed: cap2.payload?.removed, newRowId,
  });
  console.log(`[T-17] 第二次保存 modified ids = ${JSON.stringify(modifiedIds)}`);

  expect(cap2.payload?.added, 'AC-17：第二次保存 added 必须为空（新行已有 id，不该再走新增）').toEqual([]);
  expect(modifiedIds,
    `AC-17：第二次保存的 modified 必须带**第一次返回的 DB id** ${newRowId}（不是 null）。实际 ${JSON.stringify(modifiedIds)}`
  ).toContain(newRowId);
  expect(modifiedIds.includes('null') || modifiedIds.includes('undefined'),
    'AC-17：modified 中不得出现 null id').toBe(false);

  // ── 不重复插入（AC-17 修正版的核心判据）──
  expect(queryLineItemCountById(newRowId),
    `AC-17：该 line_item id (${newRowId}) 在库中必须唯一存在（count(*) WHERE id=? 恒为 1）`).toBe(1);
  expect(queryLineItemCount(SANDBOX_QUOTATION_ID),
    `AC-17：两次保存后整单行数应仍是 ${linesBefore + 1}（出现 ${linesBefore + 2} = 重复插入）`
  ).toBe(linesBefore + 1);
});
