/**
 * E2E-WITHDRAW-02 撤回流程
 *
 * 策略：API-driven 混合 E2E
 * - API 层一路推进到 APPROVED 状态（复用金路径前 7 步）
 * - alice（创建人 salesRepId）直接 POST /withdraw 一步撤回回 DRAFT
 * - UI 验证最终 DRAFT 状态
 *
 * 金路径：DRAFT → SUBMITTED → APPROVED → [一步直接撤回] → DRAFT
 *
 * 注：两步流程（withdraw-request + withdraw/approve）已废弃，
 *     当前后端允许 SUBMITTED/COSTING_REJECTED/APPROVED 状态由创建人或
 *     管理员/财务直接 POST /withdraw 一步撤回回 DRAFT。
 */

import { test, expect, request as playwrightRequest } from '@playwright/test';
import { isBackendUp, loginAsAlice } from './fixtures/auth';

const BACKEND = 'http://localhost:8081';

let backendUp = false;

test.beforeAll(async () => {
  backendUp = await isBackendUp();
});

// 保留原有烟雾测试（独立可跑）
test('E2E-WITHDRAW-02 报价列表存在操作区域', async ({ page }) => {
  if (!backendUp) {
    test.skip(true, '后端未启动，跳过 E2E-WITHDRAW-02');
  }

  await loginAsAlice(page);
  await page.goto('/quotations');
  await page.waitForLoadState('networkidle');
  await expect(page.locator('.ant-table')).toBeVisible({ timeout: 10_000 });
});

// 完整撤回流程金路径（一步直接撤回）
test('E2E-WITHDRAW-02 完整撤回流程 APPROVED → 一步直接撤回 → DRAFT', async ({ page }) => {
  if (!backendUp) {
    test.skip(true, '后端未启动，跳过 E2E-WITHDRAW-02 金路径');
  }

  // ---- API 层：独立 cookie store ----
  const aliceCtx = await playwrightRequest.newContext({ baseURL: BACKEND });
  const adminCtx = await playwrightRequest.newContext({ baseURL: BACKEND });

  // 1. alice 登录
  const aliceLogin = await aliceCtx.post('/api/cpq/auth/login', {
    data: { username: 'alice', password: 'Admin@2026' },
  });
  expect(aliceLogin.ok(), 'alice 登录失败').toBe(true);

  // 2. admin 登录（用于审批）
  const adminLogin = await adminCtx.post('/api/cpq/auth/login', {
    data: { username: 'admin', password: 'Admin@2026' },
  });
  expect(adminLogin.ok(), 'admin 登录失败').toBe(true);

  // 3. alice 创建客户
  const ts = Date.now();
  const custRes = await aliceCtx.post('/api/cpq/customers', {
    data: {
      name: `E2E Withdraw Customer ${ts}`,
      level: 'STANDARD',
      contacts: [{ name: 'WD Contact', phone: '13800005678', isPrimary: true }],
    },
  });
  expect(custRes.ok(), '创建客户失败').toBe(true);
  const customerId: string = (await custRes.json()).data.id;
  console.log('[E2E-WITHDRAW-02] customerId:', customerId);

  // 4. alice 创建报价单（DRAFT）
  const quotRes = await aliceCtx.post('/api/cpq/quotations', {
    data: {
      customerId,
      name: `E2E Withdraw Quotation ${ts}`,
      quoteType: 'STANDARD',
    },
  });
  expect(quotRes.ok(), '创建报价单失败').toBe(true);
  const quotationId: string = (await quotRes.json()).data.id;
  console.log('[E2E-WITHDRAW-02] quotationId:', quotationId);

  // 5. alice 提交（DRAFT → SUBMITTED）
  const submitRes = await aliceCtx.post(`/api/cpq/quotations/${quotationId}/submit`);
  expect(submitRes.ok(), '提交报价单失败').toBe(true);
  const afterSubmit = await aliceCtx.get(`/api/cpq/quotations/${quotationId}`);
  expect((await afterSubmit.json()).data.status).toBe('SUBMITTED');
  console.log('[E2E-WITHDRAW-02] SUBMITTED OK');

  // 6. admin 审批通过（SUBMITTED → APPROVED）
  const approveRes = await adminCtx.post(`/api/cpq/quotations/${quotationId}/approve`, {
    data: { note: 'E2E admin approved for withdraw test' },
  });
  expect(approveRes.ok(), 'admin 审批失败').toBe(true);
  const afterApprove = await aliceCtx.get(`/api/cpq/quotations/${quotationId}`);
  expect((await afterApprove.json()).data.status, '审批后状态应为 APPROVED').toBe('APPROVED');
  console.log('[E2E-WITHDRAW-02] APPROVED OK，准备一步撤回');

  // 7. alice 直接一步撤回（APPROVED → DRAFT）
  //    alice 是创建人(salesRepId)，后端允许直接撤回
  const withdrawRes = await aliceCtx.post(`/api/cpq/quotations/${quotationId}/withdraw`);
  expect(withdrawRes.ok(), 'alice 一步撤回失败').toBe(true);
  console.log('[E2E-WITHDRAW-02] withdraw OK');

  // 8. API 验证：报价单状态已回到 DRAFT
  const afterWithdraw = await aliceCtx.get(`/api/cpq/quotations/${quotationId}`);
  const withdrawnStatus = (await afterWithdraw.json()).data.status;
  expect(withdrawnStatus, '撤回后状态应为 DRAFT').toBe('DRAFT');
  console.log('[E2E-WITHDRAW-02] DRAFT OK，一步撤回流程完成');

  // 9. UI 终态验证：alice 视角看到"草稿"Tag
  await loginAsAlice(page);
  await page.goto(`/quotations/${quotationId}`);
  await page.waitForLoadState('networkidle');
  await expect(
    page.locator('.ant-tag').filter({ hasText: '草稿' }).first(),
  ).toBeVisible({ timeout: 15_000 });
  console.log('[E2E-WITHDRAW-02] UI 终态 DRAFT 验证通过');

  // 10. 验证报价单可以再次提交（DRAFT 状态允许提交）
  const resubmitRes = await aliceCtx.post(`/api/cpq/quotations/${quotationId}/submit`);
  expect(resubmitRes.ok(), 'DRAFT 状态应可再次提交').toBe(true);
  const resubmitStatus = (await resubmitRes.json()).data.status;
  expect(resubmitStatus, '再次提交后状态应为 SUBMITTED').toBe('SUBMITTED');
  console.log('[E2E-WITHDRAW-02] 再次提交验证通过 → SUBMITTED');

  // 清理 context
  await aliceCtx.dispose();
  await adminCtx.dispose();
});
