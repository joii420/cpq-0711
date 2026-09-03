/**
 * E2E · task-260901 —— A. 增量协议（需求文档 §③ A / api.md §1.2）
 *
 * 覆盖：T-1(AC-1) · T-2(AC-2) · T-4(AC-4) · T-5(AC-5)
 *   （T-3/AC-3 见 task260901-regression.spec.ts；其"4 张子表归零"的真正判据在后端
 *     Task260901IncrementalProtocolHttpTest —— dev 库 cpq_db_0724 的 quotation_line_process /
 *     quotation_line_composite_process / quotation_line_item_snapshot **全库 0 行**，
 *     在 dev 库上断言这三张表"删除后为 0"是 0==0 的空跑假绿，见回报的 AC 质量问题。）
 *
 * 🚫 本文件不 import src/ 下任何实现代码；断言口径全部来自 AC 原文 + api.md 契约。
 */
import { test, expect } from '@playwright/test';
import {
  BASELINE_QUOTATION_ID, BASELINE_QUOTATION_NO, SANDBOX_QUOTATION_ID, EMPTY_QUOTATION_ID,
  AC1_TAB_NAME, AC1_ROW_INDEX, AC1_COLUMN_NAME, AC1_TARGET_VALUE,
  assertBaselineShape, assertSandboxShape,
  loginAdmin, openEditStep2, cardByPartNo, openCardTab, editCell, readCellInputValue, distinctFrom,
  saveDraftCapture, clickSaveDraftExpectNoRequest, addFirstExistingProduct,
  queryLineAt, queryLineItemCount, queryQuotationHeader, queryRowDataOf, psqlScalar,
  archiveShot, archiveJson,
  waitBackendUp,
} from './fixtures/task260901-draft';

let backendUp = false;

test.beforeAll(async () => {
  backendUp = await waitBackendUp();
  if (backendUp) assertBaselineShape();
});

// ══════════════════════════════════════════════════════════════════════════
// T-1 / AC-1（单点）
// AC 原文：「在 Step2 页签『材料成本』第 2 行『材料净重』列把值改为 3.5 并失焦，点『保存草稿』。
//           F12 Network 里 PUT /draft 的请求体满足：modified 数组长度为 1 且其 id 等于该产品行 id；
//           added 与 removed 均为空数组；请求体大小 < 100 KB。」
// ⚠️ 页签名以实际数据为准取「物料」，理由见 fixtures/task260901-draft.ts 的 AC1_TAB_NAME 注释。
// ══════════════════════════════════════════════════════════════════════════
test('T-1 改一个格子后保存：modified 长度=1 且 id 命中该行，added/removed 空，请求体 < 100 KB', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(600_000);

  const target = queryLineAt(BASELINE_QUOTATION_ID, 0);
  console.log(`[T-1] 目标行 id=${target.id} 料号=${target.partNo}`);
  expect(target.partNo, '目标行必须有料号（否则定位不到产品卡，断言会空跑）').not.toBe('');

  await loginAdmin(page);
  await openEditStep2(page, BASELINE_QUOTATION_ID);

  const card = await cardByPartNo(page, target.partNo);
  await openCardTab(page, card, AC1_TAB_NAME);

  const before = await readCellInputValue(card, AC1_ROW_INDEX, AC1_COLUMN_NAME);
  const value = distinctFrom(before, AC1_TARGET_VALUE);
  console.log(`[T-1]「${AC1_TAB_NAME}」第 ${AC1_ROW_INDEX + 1} 行「${AC1_COLUMN_NAME}」: ${before} → ${value}`);
  expect(value, '新值必须与原值不同，否则会命中 AC-5「无改动不发请求」导致假红').not.toBe(before);

  await editCell(page, card, AC1_ROW_INDEX, AC1_COLUMN_NAME, value);
  await archiveShot(page, 'T-1-cell-edited');

  const cap = await saveDraftCapture(page, 200);
  archiveJson('T-1-request-payload', {
    baseVersion: cap.payload?.baseVersion,
    added: cap.payload?.added,
    modified: (cap.payload?.modified ?? []).map((m: any) => ({ id: m?.id, tabs: (m?.componentData ?? []).length })),
    removed: cap.payload?.removed,
    requestBytes: cap.requestBytes,
  });

  // ── 结果非空守卫：先证明 payload 真的是新协议形状，再谈长度 ──
  expect(cap.payload, 'PUT /draft 必须带 JSON 请求体').toBeTruthy();
  expect(Array.isArray(cap.payload.added), 'api.md §1.2：请求体必须有 added 数组').toBe(true);
  expect(Array.isArray(cap.payload.modified), 'api.md §1.2：请求体必须有 modified 数组').toBe(true);
  expect(Array.isArray(cap.payload.removed), 'api.md §1.2：请求体必须有 removed 数组').toBe(true);
  expect(cap.payload.lineItems, 'api.md §1.1：全量 lineItems 已被三数组取代，不应再出现').toBeUndefined();

  // ── AC-1 正文 ──
  expect(cap.payload.modified.length, 'AC-1：modified 数组长度必须为 1').toBe(1);
  expect(String(cap.payload.modified[0]?.id), 'AC-1：modified[0].id 必须等于被改的那一行的 DB id').toBe(target.id);
  expect(cap.payload.added, 'AC-1：added 必须为空数组').toEqual([]);
  expect(cap.payload.removed, 'AC-1：removed 必须为空数组').toEqual([]);
  expect(cap.requestBytes, `AC-1：请求体必须 < 100 KB，实际 ${cap.requestBytes} B`).toBeLessThan(100 * 1024);

  // ── 落库确证（AC-1 的改动必须真的存进去，否则"轻"是靠丢数据换来的）──
  const rowDataAfter = queryRowDataOf(target.id, AC1_TAB_NAME);
  expect(rowDataAfter, '目标行「物料」页签 row_data 不应为空').not.toBe('');
  expect(rowDataAfter, `AC-1：新值 ${value} 必须落库。实际 row_data=${rowDataAfter}`).toContain(value);
});

// ══════════════════════════════════════════════════════════════════════════
// T-2 / AC-2（单点）
// AC 原文：「用『+ 添加产品』加入 1 个产品后点『保存草稿』。请求体 added 长度为 1、该元素 id 为 null；
//           modified 与 removed 为空。保存成功后该行在库中存在，且响应回传了它的新 id。」
// ⚠️ 走「从已有产品添加」路径：dev 库 sel_template 表**全库 0 行**，「选配添加」抽屉必然空态
//    「缺少选配模板」，无法完成加产品 —— 用它会得到一条永远跑不通的用例。
// ⚠️ 本用例打**沙箱单**（不打基准单），避免把基准单的 1845/9225 判据打漂。
// ══════════════════════════════════════════════════════════════════════════
test('T-2 添加 1 个产品后保存：added 长度=1 且 id 为 null，modified/removed 空，新行落库并回传新 id', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(900_000);
  assertSandboxShape();

  const linesBefore = queryLineItemCount(SANDBOX_QUOTATION_ID);
  console.log(`[T-2] 加产品前行数 = ${linesBefore}`);
  expect(linesBefore, '沙箱单前置行数应 > 0').toBeGreaterThan(0);

  await loginAdmin(page);
  await openEditStep2(page, SANDBOX_QUOTATION_ID);

  // ── 加 1 个产品（走「从已有产品添加」，不走选配；抽屉需先点「查询」，见夹具注释）──
  await addFirstExistingProduct(page);
  await archiveShot(page, 'T-2-after-add');

  const cap = await saveDraftCapture(page, 200);
  archiveJson('T-2-request-payload', {
    added: (cap.payload?.added ?? []).map((a: any) => ({ id: a?.id, tempId: a?.tempId, sortOrder: a?.sortOrder })),
    modified: cap.payload?.modified,
    removed: cap.payload?.removed,
    responseLineItems: cap.responseBody?.data?.lineItems,
  });

  expect(Array.isArray(cap.payload?.added), '请求体必须有 added 数组').toBe(true);
  expect(cap.payload.added.length, 'AC-2：added 数组长度必须为 1').toBe(1);
  expect(cap.payload.added[0]?.id ?? null, 'AC-2：added[0].id 必须为 null（新行还没有 DB id）').toBeNull();
  expect(cap.payload.modified, 'AC-2：modified 必须为空').toEqual([]);
  expect(cap.payload.removed, 'AC-2：removed 必须为空').toEqual([]);

  // ── 响应必须回传新 id（api.md §1.3：added 行在响应里拿到 DB 生成的 id）──
  const tempId = cap.payload.added[0]?.tempId;
  expect(tempId,
    'api.md §1.3：added 行必须携带 tempId（前端生成的稳定 UUID），响应据此回传新 id'
  ).toBeTruthy();

  const respLines = cap.responseBody?.data?.lineItems;
  expect(Array.isArray(respLines), 'AC-2：响应 data.lineItems 应为数组').toBe(true);
  expect(respLines.length, 'AC-2：响应应至少回传新增的那 1 行').toBeGreaterThanOrEqual(1);
  const claimed = respLines.find((l: any) => l?.tempId === tempId);
  expect(claimed,
    `api.md §1.3 收敛表：added 行的响应元素必须**原样回传 tempId**（共 7 键 = 6 键 + tempId）。` +
    `实际 = ${JSON.stringify(respLines.map((l: any) => ({ id: l?.id, tempId: l?.tempId })))}`
  ).toBeTruthy();
  expect(claimed.id, 'AC-2：认领到的新行必须带非空 DB id').toBeTruthy();
  expect(Number(psqlScalar(`SELECT count(*) FROM quotation_line_item WHERE id='${claimed.id}';`)),
    'AC-2：响应回传的 id 必须真的在库里').toBe(1);

  // ── 落库确证 ──
  const linesAfter = queryLineItemCount(SANDBOX_QUOTATION_ID);
  console.log(`[T-2] 加产品后行数 = ${linesAfter}（前 ${linesBefore}）`);
  expect(linesAfter, 'AC-2：保存后库中应恰好多 1 行').toBe(linesBefore + 1);
});

// ══════════════════════════════════════════════════════════════════════════
// T-4 / AC-4（单点）
// AC 原文：「只修改单头『项目名称』为 AC4-<时间戳> 后保存。请求体 added/modified/removed 三个数组
//           全为空，projectName 字段为该值。保存后库中 project_name 等于该值，且 quotation_line_item
//           表零行被更新。」
// 打基准单：正因为它有 1845 行，"零行被更新"才有分辨力（打 0 行空单是空跑）。
// ══════════════════════════════════════════════════════════════════════════
test('T-4 只改单头项目名称后保存：三数组全空、projectName 落库，且 1845 行一行都没被 UPDATE', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(600_000);

  // 用 xmin 做「行有没有被 UPDATE」的物理判据（内容比对只能证明内容没变，证明不了没写过）
  const { psqlRows } = await import('./fixtures/task260901-draft');
  const xminOf = () => new Map(
    psqlRows(`SELECT id::text, xmin::text FROM quotation_line_item WHERE quotation_id='${BASELINE_QUOTATION_ID}';`)
      .map((r) => [r[0], r[1]] as [string, string])
  );
  const xminBefore = xminOf();
  expect(xminBefore.size, `前置守卫：基准单应有 1845 行 xmin 样本，实际 ${xminBefore.size}`).toBe(1845);

  await loginAdmin(page);
  await page.goto(`/quotations/${BASELINE_QUOTATION_ID}/edit`);
  await expect(page.getByRole('button', { name: /下一步/ }), '编辑向导应就绪').toBeEnabled({ timeout: 300_000 });
  await expect(page.getByRole('button', { name: /保\s*存\s*草\s*稿/ }), '编辑页应有保存草稿按钮').toBeVisible();
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(3000);

  const stamp = `AC4-${Date.now()}`;
  const projectInput = page.locator('input#projectName, input[id$="projectName"]').first();
  await expect(projectInput, '未找到「项目名称」输入框——选择器失效，本用例无分辨力').toBeVisible({ timeout: 60_000 });
  await projectInput.click();
  await projectInput.clear();
  // ⚠️ pressSequentially 而非 fill：antd 的 onValuesChange 只认真实键入；
  //    且打开阶段的异步回填会冲掉 fill 的值（repair-260830 已踩过）。
  await projectInput.pressSequentially(stamp, { delay: 25 });
  await projectInput.blur();
  await expect(projectInput, '输入未生效（被异步回填冲掉），本用例此时无分辨力').toHaveValue(stamp);

  const cap = await saveDraftCapture(page, 200);
  archiveJson('T-4-request-payload', {
    projectName: cap.payload?.projectName,
    added: cap.payload?.added, modified: cap.payload?.modified, removed: cap.payload?.removed,
  });

  expect(cap.payload?.added, 'AC-4：added 必须为空数组').toEqual([]);
  expect(cap.payload?.modified, 'AC-4：modified 必须为空数组').toEqual([]);
  expect(cap.payload?.removed, 'AC-4：removed 必须为空数组').toEqual([]);
  expect(cap.payload?.projectName, 'AC-4：projectName 必须是新值').toBe(stamp);

  const header = queryQuotationHeader(BASELINE_QUOTATION_ID);
  expect(header.projectName, 'AC-4：库中 project_name 应等于新值').toBe(stamp);

  const xminAfter = xminOf();
  const changed = [...xminBefore.entries()].filter(([id, x]) => xminAfter.get(id) !== x).map(([id]) => id);
  console.log(`[T-4] xmin 变化的 line item 行数 = ${changed.length}`);
  expect(changed.length,
    `AC-4：只改单头时 quotation_line_item 应零行被更新，实际 ${changed.length} 行 xmin 变了：${changed.slice(0, 5).join(',')}`
  ).toBe(0);
});

// ══════════════════════════════════════════════════════════════════════════
// T-5 / AC-5（边界）
// AC 原文：「什么都不改直接点『保存草稿』。不发出 PUT /draft 请求，页面提示『无改动，无需保存』。」
// ══════════════════════════════════════════════════════════════════════════
test('T-5 零编辑点保存草稿：不发 PUT /draft，且提示「无改动，无需保存」', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(600_000);

  await loginAdmin(page);
  await openEditStep2(page, BASELINE_QUOTATION_ID);

  // 🚨 防假绿：先证明按钮真的可点、页面真的进了 Step2；否则"0 条请求"是没分辨力的绿
  await expect(page.locator('.qt-product-card').first(), '应已渲染出产品卡（证明页面就绪）')
    .toBeVisible({ timeout: 120_000 });

  const { seen, toast } = await clickSaveDraftExpectNoRequest(page, 8_000);
  await archiveShot(page, 'T-5-no-change-toast');
  console.log(`[T-5] 观察窗口内的 PUT /draft 请求 = ${JSON.stringify(seen)}；toast = ${JSON.stringify(toast)}`);

  expect(seen, `AC-5：零编辑不应发出 PUT /draft，实际 ${seen.length} 条`).toHaveLength(0);
  // toast 是瞬时反馈，已在点击瞬间抓取（见夹具注释：等满观察窗口再找必然扑空）
  expect(toast, `AC-5：应提示「无改动，无需保存」（文案逐字比对）。实际捕获到的提示 = ${JSON.stringify(toast)}`)
    .toContain('无改动，无需保存');
});

// ══════════════════════════════════════════════════════════════════════════
// T-24 / AC-24（边界）
// AC 原文：「0 行的空单点保存：不报错，added/modified/removed 全空或不发请求，页面无异常。」
// ══════════════════════════════════════════════════════════════════════════
test('T-24 0 行空单点保存：不报错，三数组全空或不发请求，无红色错误提示', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(600_000);

  const n = queryLineItemCount(EMPTY_QUOTATION_ID);
  expect(n, `AC-24 的前置：${EMPTY_QUOTATION_ID} 必须确实是 0 行单，实际 ${n} 行`).toBe(0);

  const errors: string[] = [];
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`console.error: ${m.text()}`); });

  await loginAdmin(page);
  await page.goto(`/quotations/${EMPTY_QUOTATION_ID}/edit`);
  await expect(page.getByRole('button', { name: /下一步/ })).toBeEnabled({ timeout: 120_000 });
  await page.waitForTimeout(2500);

  let payload: any = null;
  let status: number | null = null;
  page.on('response', async (r) => {
    if (r.request().method() === 'PUT' && /\/quotations\/[^/]+\/draft/.test(r.url())) {
      status = r.status();
      try { payload = JSON.parse(r.request().postData() ?? '{}'); } catch { /* ignore */ }
    }
  });

  const buttons = page.getByRole('button', { name: /保存草稿$/ });
  await expect(buttons, '空单编辑页也应有「保存草稿」按钮').toHaveCount(1, { timeout: 30_000 });
  await buttons.first().click();
  await page.waitForTimeout(10_000);
  await archiveShot(page, 'T-24-empty-quotation-save');

  console.log(`[T-24] PUT /draft status=${status} payload=${JSON.stringify(payload)}`);
  if (status !== null) {
    expect(status, 'AC-24：若发出了请求，必须成功（不报错）').toBe(200);
    expect(payload?.added ?? [], 'AC-24：added 应为空').toEqual([]);
    expect(payload?.modified ?? [], 'AC-24：modified 应为空').toEqual([]);
    expect(payload?.removed ?? [], 'AC-24：removed 应为空').toEqual([]);
  } else {
    console.log('[T-24] 未发出 PUT /draft —— AC-24 允许的另一分支');
  }

  await expect(
    page.locator('.ant-message-error, .ant-notification-notice-error'),
    'AC-24：页面不应出现错误提示'
  ).toHaveCount(0);

  // AC-24「页面无异常」判为「无未捕获 JS 异常 + 无错误提示」。
  // ⚠️ 刻意**不把库的废弃告警计入**：antd 的 `Warning: [antd: Drawer] width is deprecated` /
  //    React 的 `In HTML, %s cannot be a descendant of %s` 是**本任务之前就存在**的既有噪音，
  //    把它们算进来会让本条恒红且与 AC-24 要防的东西无关。它们作为观察项打印，不作判据。
  const benign = /^Warning:|cannot be a descendant of|cannot contain a nested|hydration error/;
  const fatal = errors.filter((e) => e.startsWith('pageerror:') || !benign.test(e.replace(/^console\.error: /, '')));
  const noisy = errors.filter((e) => !fatal.includes(e));
  if (noisy.length) console.log(`[T-24][观察] 既有库告警 ${noisy.length} 条（不计入判据）：${noisy.slice(0, 3).join(' | ')}`);
  expect(fatal, `AC-24：不应有未捕获的页面级 JS 异常，实际：${fatal.slice(0, 5).join(' | ')}`).toEqual([]);
});
