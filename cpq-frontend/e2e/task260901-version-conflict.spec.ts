/**
 * E2E · task-260901 —— C. 版本指纹（需求文档 §③ C）
 *
 * 覆盖：T-11(AC-11) · T-12(AC-12) · T-14(AC-14)
 *   （T-13/AC-13 是"派生写入不递增版本号"，必须在不受浏览器行为干扰的条件下测，
 *     放在后端 Task260901VersionFingerprintHttpTest —— 见回报。）
 *
 * 视觉基准：原型图/冲突提示.html（状态 1 是唯一交付形态）
 * 🚫 不 import src/。
 *
 * ⚠️ 共享库副作用登记：T-12 会把基准单的 project_name 改成 `AC12-A-<时间戳>`（模拟"会话 A 保存"）。
 *    该字段是业务数据不是全局配置；用例不还原，跑完如需还原：
 *      UPDATE quotation SET project_name=NULL WHERE id='<基准单 id>' AND project_name LIKE 'AC12-A-%';
 *    （🚨 上面这条 UPDATE 属人工运维动作，不由测试执行 —— 测试内只跑 SELECT。）
 */
import { test, expect } from '@playwright/test';
import {
  BASELINE_QUOTATION_ID,
  AC1_TAB_NAME, AC1_ROW_INDEX, AC1_COLUMN_NAME, AC1_TARGET_VALUE,
  assertBaselineShape, loginAdmin, openEditStep2, cardByPartNo, openCardTab,
  editCell, readCellInputValue, distinctFrom, saveDraftCapture, clickSaveDraft,
  queryLineAt, queryUserDataVersion, queryQuotationHeader,
  apiGetQuotation, apiSaveDraftHeaderOnly, archiveShot, archiveJson,
  waitBackendUp,
} from './fixtures/task260901-draft';

let backendUp = false;
test.beforeAll(async () => {
  backendUp = await waitBackendUp();
  if (backendUp) assertBaselineShape();
});

// ══════════════════════════════════════════════════════════════════════════
// T-11 / AC-11（单点；2026-09-01 基准点已改，见 需求文档.md AC-11 的 📌 注）
// AC 原文：「**以「发出保存请求前一刻」的版本号为基准 N**（不是「打开单据时」的版本号）。
//           保存一次成功后，响应里的 userDataVersion 为 N+1，库中 quotation.user_data_version
//           也为 N+1。」
//
// 📌 为什么基准点是「保存前一刻」：用户改完格子失焦会先触发 quote-card-edit，而 AC-14 要求
//    该端点也 +1 —— 若以「打开时」为基准，保存后实为 N+2，AC-11 与 AC-14 自相矛盾。
//    本用例因此**两个版本号都读**：打开时的（仅作诊断打印）+ 保存前一刻的（AC-11 的基准）。
// ══════════════════════════════════════════════════════════════════════════
test('T-11 以「保存请求前一刻」为基准 N：保存成功后响应与库均为 N+1', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(900_000);

  await loginAdmin(page);

  const opened = await apiGetQuotation(page, BASELINE_QUOTATION_ID);
  const nFromApi = opened?.userDataVersion;
  expect(typeof nFromApi, 'api.md §3：GET /quotations/{id} 响应根部必须有 userDataVersion（数字）').toBe('number');
  const nFromDb = queryUserDataVersion(BASELINE_QUOTATION_ID);
  expect(nFromDb, 'AC-11 前置：GET 返回的版本号应与库一致').toBe(nFromApi);
  console.log(`[T-11] 打开时 userDataVersion N = ${nFromApi}`);

  const target = queryLineAt(BASELINE_QUOTATION_ID, 0);
  await openEditStep2(page, BASELINE_QUOTATION_ID);
  const card = await cardByPartNo(page, target.partNo);
  await openCardTab(page, card, AC1_TAB_NAME);
  const before = await readCellInputValue(card, AC1_ROW_INDEX, AC1_COLUMN_NAME);
  await editCell(page, card, AC1_ROW_INDEX, AC1_COLUMN_NAME, distinctFrom(before, AC1_TARGET_VALUE));

  // ── AC-11 的基准点：**发出保存请求前一刻**的库版本 ──
  const nBeforeSave = queryUserDataVersion(BASELINE_QUOTATION_ID);
  console.log(
    `[T-11] 保存前一刻库中版本 N = ${nBeforeSave}（打开时 ${nFromApi}；` +
    `两者差 ${nBeforeSave - nFromApi} 由单元格失焦触发的 quote-card-edit 造成，属 api.md §4.1 预期）`
  );
  expect(nBeforeSave,
    'AC-11 前置：保存前一刻的版本号不应小于打开时的版本号（版本号只增不减）'
  ).toBeGreaterThanOrEqual(nFromApi);

  const cap = await saveDraftCapture(page, 200);
  const respVersion = cap.responseBody?.data?.userDataVersion;
  const dbVersion = queryUserDataVersion(BASELINE_QUOTATION_ID);
  archiveJson('T-11-versions', { openedVersion: nFromApi, beforeSave: nBeforeSave, respVersion, dbVersion });

  expect(typeof respVersion, 'api.md §1.3：PUT /draft 响应必须回传 userDataVersion').toBe('number');
  expect(respVersion,
    `AC-11：保存成功后响应版本号应为「保存前一刻」N+1 = ${nBeforeSave + 1}，实际 ${respVersion}`
  ).toBe(nBeforeSave + 1);
  expect(dbVersion, 'AC-11：库中 user_data_version 应与响应一致').toBe(respVersion);
});

// ══════════════════════════════════════════════════════════════════════════
// T-14 / AC-14（反向）
// AC 原文：「通过 PUT /line-items/{id}/quote-card-edit 改一个单元格后，响应中带回新的 userDataVersion，
//           前端更新本地版本号；随后点『保存草稿』不出现 409。」
// ══════════════════════════════════════════════════════════════════════════
test('T-14 quote-card-edit 回传新 userDataVersion，随后保存草稿不出现 409', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(900_000);

  await loginAdmin(page);
  const target = queryLineAt(BASELINE_QUOTATION_ID, 0);
  await openEditStep2(page, BASELINE_QUOTATION_ID);
  const card = await cardByPartNo(page, target.partNo);
  await openCardTab(page, card, AC1_TAB_NAME);

  const vBefore = queryUserDataVersion(BASELINE_QUOTATION_ID);
  const cur = await readCellInputValue(card, AC1_ROW_INDEX, AC1_COLUMN_NAME);
  const val = distinctFrom(cur, AC1_TARGET_VALUE);

  const editWaiter = page.waitForResponse(
    (r) => r.request().method() === 'PUT' && r.url().includes('/quote-card-edit'),
    { timeout: 120_000 }
  ).catch(() => null);

  await editCell(page, card, AC1_ROW_INDEX, AC1_COLUMN_NAME, val);
  const editResp = await editWaiter;

  if (editResp) {
    const body = await editResp.json();
    console.log(`[T-14] quote-card-edit → ${editResp.status()}，userDataVersion=${body?.data?.userDataVersion}`);
    expect(editResp.status(), 'quote-card-edit 应成功').toBe(200);
    expect(typeof body?.data?.userDataVersion,
      'AC-14 / api.md §2：quote-card-edit 响应必须新增 userDataVersion 字段'
    ).toBe('number');
    const vAfterEdit = queryUserDataVersion(BASELINE_QUOTATION_ID);
    expect(body.data.userDataVersion, 'AC-14：回传的版本号应与库一致').toBe(vAfterEdit);
    expect(vAfterEdit, 'AC-14 / api.md §4.1：quote-card-edit 写的是用户数据，必须递增版本号').toBeGreaterThan(vBefore);
  } else {
    // 该字段没走单元格级端点 —— 那 AC-14 就没被触发，本用例此时无分辨力，必须显式失败而不是放绿。
    throw new Error(
      `[T-14] 编辑「${AC1_TAB_NAME}/${AC1_COLUMN_NAME}」未触发 PUT /quote-card-edit —— ` +
      'AC-14 的前置条件未成立，本用例无分辨力。需要换一个确实走单元格级端点的字段，或由主线裁决 AC-14 的可测形式。'
    );
  }

  // 随后保存草稿，必须 200 而不是 409（前端已同步新版本号）
  const cap = await saveDraftCapture(page, 200);
  console.log(`[T-14] 后续 PUT /draft → ${cap.status}`);
  expect(cap.status, 'AC-14：quote-card-edit 之后保存草稿不得 409').toBe(200);
});

// ══════════════════════════════════════════════════════════════════════════
// T-12 / AC-12（边界·并发）
// AC 原文：「会话 A 与 B 同时打开同一张单（都拿到版本 N）。A 保存成功（版本→N+1）。B 随后保存，
//           收到 HTTP 409，响应 reason 为 STALE_VERSION；页面弹出对话框，标题「保存失败」，
//           正文包含「这张报价单已被他人修改」，只有一个「刷新页面」按钮（无「强制覆盖」「忽略」等
//           其他按钮）；点击后页面重新加载，数据为 A 保存后的版本。」
//
// 构造手法（test.md §2）：B = 浏览器；A = 直接 API 调用（不依赖两个真实浏览器）。
// ⚠️ 顺序刻意是「B 先编辑单元格 → A 再保存」：若反过来，B 的单元格失焦会触发 quote-card-edit
//    （AC-14）把 B 的本地版本刷成最新，冲突就构造不出来了 —— 那会是一条永远绿的假用例。
// ══════════════════════════════════════════════════════════════════════════
test('T-12 并发冲突：B 保存收到 409 STALE_VERSION，弹窗只有一个「刷新页面」按钮，点后数据为 A 的版本', async ({ page }) => {
  test.skip(!backendUp, '后端未启动');
  test.setTimeout(900_000);

  await loginAdmin(page);
  const target = queryLineAt(BASELINE_QUOTATION_ID, 0);

  // ── B：打开单据并改一个格子（此时 B 本地版本已是"编辑后"的最新值）──
  await openEditStep2(page, BASELINE_QUOTATION_ID);
  const card = await cardByPartNo(page, target.partNo);
  await openCardTab(page, card, AC1_TAB_NAME);
  const curB = await readCellInputValue(card, AC1_ROW_INDEX, AC1_COLUMN_NAME);
  await editCell(page, card, AC1_ROW_INDEX, AC1_COLUMN_NAME, distinctFrom(curB, AC1_TARGET_VALUE));

  // ── A：用 API 直接保存一次（只改单头），把库版本推到 B 手上那个之后 ──
  const vBeforeA = queryUserDataVersion(BASELINE_QUOTATION_ID);
  const aProjectName = `AC12-A-${Date.now()}`;
  await apiSaveDraftHeaderOnly(page, BASELINE_QUOTATION_ID, vBeforeA, aProjectName);
  const vAfterA = queryUserDataVersion(BASELINE_QUOTATION_ID);
  console.log(`[T-12] 会话 A 保存：版本 ${vBeforeA} → ${vAfterA}，projectName=${aProjectName}`);
  // 🚨 分辨力守卫：A 必须真的推进了版本号，否则后面的 409 无从谈起
  expect(vAfterA, 'AC-12 前置：会话 A 的保存必须让版本号 +1，否则冲突根本构造不出来').toBe(vBeforeA + 1);

  // ── B：点保存 → 期望 409 ──
  const cap = await saveDraftCapture(page, 409);
  archiveJson('T-12-conflict-response', cap.responseBody);
  const data = cap.responseBody?.data ?? {};
  expect(data.reason, 'AC-12 / api.md §1.4：409 响应 data.reason 必须是 STALE_VERSION').toBe('STALE_VERSION');
  expect(cap.responseBody?.message, 'api.md §1.4：409 message 为「这张报价单已被他人修改」')
    .toContain('这张报价单已被他人修改');

  // ── 弹窗形态（对照 原型图/冲突提示.html 状态 1）──
  const modal = page.locator('.ant-modal-confirm').last();
  await expect(modal, 'AC-12：应弹出冲突对话框').toBeVisible({ timeout: 30_000 });
  await archiveShot(page, 'T-12-stale-version-modal');

  await expect(modal.locator('.ant-modal-confirm-title'), 'AC-12：标题必须是「保存失败」').toHaveText(/^保存失败$/);
  await expect(modal.locator('.ant-modal-confirm-content'), 'AC-12：正文必须包含「这张报价单已被他人修改」')
    .toContainText('这张报价单已被他人修改');

  const btns = modal.locator('.ant-modal-confirm-btns button');
  const btnTexts = await btns.allInnerTexts();
  console.log(`[T-12] 弹窗按钮 = ${JSON.stringify(btnTexts)}`);
  expect(btns, `AC-12：弹窗必须只有一个按钮，实际 ${JSON.stringify(btnTexts)}`).toHaveCount(1);
  expect(btnTexts[0].replace(/\s+/g, ''), 'AC-12：唯一按钮文案必须是「刷新页面」').toBe('刷新页面');
  // 原型图批注：closable=false / maskClosable=false，不得有右上角 ×
  await expect(page.locator('.ant-modal-close'), 'AC-12：不得有右上角关闭 ×（原型图 closable=false）').toHaveCount(0);
  const modalText = await modal.innerText();
  for (const forbidden of ['强制覆盖', '忽略', '稍后']) {
    expect(modalText, `AC-12：弹窗不得出现「${forbidden}」按钮/文案`).not.toContain(forbidden);
  }

  // ── 点「刷新页面」→ 页面重新加载 → 数据为 A 保存后的版本 ──
  const navigated = page.waitForNavigation({ timeout: 120_000 }).catch(() => null);
  await btns.first().click();
  await navigated;
  await page.waitForLoadState('domcontentloaded');
  await page.waitForTimeout(3000);
  await archiveShot(page, 'T-12-after-reload');

  const reopened = await apiGetQuotation(page, BASELINE_QUOTATION_ID);
  const header = queryQuotationHeader(BASELINE_QUOTATION_ID);
  console.log(`[T-12] 刷新后 userDataVersion=${reopened?.userDataVersion} projectName=${header.projectName}`);
  expect(reopened?.userDataVersion, 'AC-12：刷新后应拿到 A 保存后的版本号').toBe(vAfterA);
  expect(header.projectName, 'AC-12：刷新后数据应是 A 保存的那一版（B 的本次编辑丢失属预期）').toBe(aProjectName);
});
