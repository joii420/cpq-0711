/**
 * repair-260830 主线亲验：`next()`（「下一步」）不再无条件整单回写。
 *
 * 病灶（本次实测）：`next()` 无条件 `handleSaveDraft(true)` ——
 *   导入建单后后端已服务端建好 1845 行并花 97.5s 算完所有值
 *   （`[create-quotation-timing] 总计=97484ms`），点一下「下一步」就把它删掉重建：
 *   `[draft-profile] total=61184ms | S1.saveDraft=55530ms`，超过 `api.ts` 的 60s 超时 ⇒
 *   后端 22:41:30 其实存成功了、前端 60s 时已掉头，用户看到的是「保存失败」。
 *
 * 两条用例是**成对**的，缺一不可：
 *   TC-1 证明「不该发的不发」——修复本身；
 *   TC-2 证明「该发的照发，且只发轻量单头」——防止闸开过头把真编辑也挡掉（那是丢数据）。
 * 断言的都是**可观测的网络行为**（发没发、发的 payload 什么形状），不是内部 ref 状态。
 *
 * ✅ 证伪实验（2026-08-31）：把 `handleSaveDraft` 的两层闸临时改成 `if (false && ...)`，
 *    TC-1 立刻变红「实际 1 条」——复现病灶，证明这个绿有分辨力，不是空验证。
 *
 * ⚠️ **测试副作用（共享库）**：TC-2 会把 `EMPTY_QUOTATION_ID` 那张单的 `project_name`
 *    改成 `r260830-<时间戳>` 且**不自动还原**。该单是 0 行测试单，原值为 NULL。
 *    跑完如需还原：
 *      UPDATE quotation SET project_name=NULL
 *       WHERE id='3696690b-f44a-42dc-8504-b2e407cf9898' AND project_name LIKE 'r260830-%';
 */
import { test, expect } from '@playwright/test';
import type { Page, Request } from '@playwright/test';
import { loginAsAdmin } from './fixtures/auth';

/** 1845 行的真实大单（用户报告 61s 超时的同型单）。 */
const BIG_QUOTATION_ID =
  process.env.PW_R260830_BIG_ID || '1288120e-c187-4c37-9388-7cc9c3cc90c4';
/** 0 行单，用于单头改动对照——写入面仅一个 projectName 字段。 */
const EMPTY_QUOTATION_ID =
  process.env.PW_R260830_EMPTY_ID || '3696690b-f44a-42dc-8504-b2e407cf9898';

function collectDraftPuts(page: Page): Request[] {
  const puts: Request[] = [];
  page.on('request', req => {
    if (req.method() === 'PUT' && /\/api\/cpq\/quotations\/[^/]+\/draft/.test(req.url())) {
      puts.push(req);
    }
  });
  return puts;
}

/** 等编辑向导真正就绪：「下一步」可点 = loading 结束。 */
async function waitWizardReady(page: Page) {
  const nextBtn = page.getByRole('button', { name: /下一步/ });
  await expect(nextBtn).toBeEnabled({ timeout: 240_000 });
  // 断言前置非空：确实进了编辑向导而非空壳页（防「断言从未执行」的假绿）
  await expect(page.getByRole('button', { name: /保\s*存\s*草\s*稿/ })).toBeVisible();
  return nextBtn;
}

test('TC-1 零编辑点「下一步」不再整单回写 draft（1845 行真实大单）', async ({ page }) => {
  const puts = collectDraftPuts(page);
  await loginAsAdmin(page);
  await page.goto(`/quotations/${BIG_QUOTATION_ID}/edit`);
  const nextBtn = await waitWizardReady(page);

  // 打开阶段的请求不在本用例断言范围，从此刻起只看点击引发的
  puts.length = 0;
  await nextBtn.click();

  // 🚨 防假绿：必须先证明这一脚**真的踩下去了**。若按钮没点到，同样一条请求都不会发，
  //    "0 条 PUT" 就成了没有分辨力的绿。以「步骤真的前进到第 2 步」作为点击生效的判据。
  await expect(
    page.locator('.ant-steps-item-active', { hasText: '添加产品' }),
    '点击「下一步」后未进入第 2 步——点击没生效，本用例此时无分辨力',
  ).toBeVisible({ timeout: 30_000 });

  await page.waitForTimeout(6_000);   // 给 fire-and-forget 足够的发出窗口

  expect(
    puts.map(r => r.url()),
    `零编辑点「下一步」不应发出 PUT /draft，实际 ${puts.length} 条`,
  ).toHaveLength(0);
});

test('TC-2 只改单头再点「下一步」：照发，且必须是 lineItems=null 的轻量保存', async ({ page }) => {
  const puts = collectDraftPuts(page);
  await loginAsAdmin(page);
  await page.goto(`/quotations/${EMPTY_QUOTATION_ID}/edit`);
  const nextBtn = await waitWizardReady(page);

  // 真实用户交互改一个单头字段（antd 的 onValuesChange 只认交互，不认 setFieldsValue）
  const projectInput = page.locator('input#projectName, input[id$="projectName"]').first();
  await expect(projectInput, '未找到项目名称输入框——选择器失效，本用例无分辨力').toBeVisible({
    timeout: 30_000,
  });
  // ⚠️ 必须先等网络静默再输入：打开阶段有异步回填（loadQuotation / loadCustomerDetail 的
  //    form.setFieldsValue）会把刚填进去的值整个冲掉。实测 `fill()` 后 inputValue 仍为空串，
  //    onValuesChange 从未触发 —— 表现为「改了单头却不保存」，极易误判成产品 bug。
  //    改用 pressSequentially 逐字键入，更贴近真实用户输入。
  await page.waitForLoadState('networkidle');
  await page.waitForTimeout(3_000);
  const stamp = `r260830-${Date.now()}`;
  await projectInput.click();
  await projectInput.clear();   // pressSequentially 是追加：不清空会拼到上一轮遗留值后面
  await projectInput.pressSequentially(stamp, { delay: 30 });
  await projectInput.blur();
  // 断言输入真的落进去了，否则后面的「没发保存」没有分辨力
  await expect(projectInput, '输入未生效——被异步回填冲掉了，本用例无分辨力').toHaveValue(stamp);

  puts.length = 0;
  await nextBtn.click();
  await page.waitForTimeout(6_000);

  expect(puts.length, '改了单头就必须发保存，否则是丢数据').toBeGreaterThan(0);
  const body = puts[0].postDataJSON();
  expect(body, 'PUT /draft 应带 JSON body').toBeTruthy();
  // 核心：单头改动不得携带明细行。后端 QuotationService.java:420 判的是 `!= null`，
  // 传 [] 会被当成「用户删光了所有行」，传整份则退回全删全建（1845 行实测 55.5s）。
  expect(body.lineItems, '只改单头时 lineItems 必须是 null，不能是数组').toBeNull();
  expect(body.projectName, '单头字段本身要发出去').toContain('r260830-');
});
